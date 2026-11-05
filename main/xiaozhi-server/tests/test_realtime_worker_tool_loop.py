"""Real loopback HTTP/WSS tool protocol. The fake Java authority is explicit test data."""
import asyncio
import copy
import json
import unittest
from types import SimpleNamespace as NS
import httpx
from aiohttp import web
import websockets
from realtime_worker.tools import arguments, CAPS, RealtimeTools
from realtime_worker.control import Fault, Manager, AuthorityLost
from test_realtime_worker_network import Harness, receive, audio, jpeg, task

TOOL = {'type':'function','function':{'name':'server_capabilities','parameters':{'type':'object','properties':{},'additionalProperties':False}}}


class ToolHarness(Harness):
    def __init__(self):
        super().__init__()
        self.task.update(tools=[TOOL], tool_policy=dict(CAPS), turn_generation=0)
        self.generation=0;self.tool_entered=asyncio.Event();self.tool_release=asyncio.Event();self.tool_release.set()
        self.proposals=[];self.steps=[];self.acks=[];self.continuations=[];self.result_ids=set()
        self.tool_turns=1;self.tool_raw='{}';self.wrong_echo=False;self.malformed_result=False;self.late_function_output=False
        self.waiting_steps=set();self.active_continuation=None
        self.hold_output_echo=False;self.deferred_echo=None;self.output_sent=asyncio.Event();self.omit_usage=False
        self.step_text=''
        self.early_tool=False
        self.frame_pending=False
        self.hold_context_echo=False

    async def control(self, request):
        body=await request.json();tail=request.match_info['tail']
        if tail.endswith('/tool-proposals'):
            self.bodies.append((request.path,body))
            if request.headers.get('X-Realtime-Worker-Key')!='w'*48 or body.get('lease_token')!=self.task['lease_token']: return web.Response(status=401)
            if body.get('response_id') not in self.waiting_steps or body.get('turn_generation')!=self.generation: return web.Response(status=409)
            self.proposals.append(copy.deepcopy(body));self.tool_entered.set()
            await self.tool_release.wait()
            if not self.active or body['turn_generation']!=self.generation: return web.Response(status=409)
            results=[]
            for call in body['calls']:
                result_id='rt-tool-result-'+call['provider_call_id'];self.result_ids.add(result_id)
                results.append(dict(**call,result_item_id=result_id,status='succeeded',output={'technical_acceptance_only':True},error_code=None))
            if self.malformed_result: results[0]['provider_call_id']='conflict'
            return web.json_response(dict(code=0,data=dict(results=results,remaining_calls=6-len(results),turn_generation=self.generation)))
        if tail.endswith('/events'):
            if body['type']=='input.started':
                if body.get('turn_generation')!=self.generation:return web.Response(status=409)
                self.generation+=1
            elif body['type']=='response.step.completed':self.steps.append(copy.deepcopy(body));self.waiting_steps.add(body['response_id'])
            elif body['type']=='tool.result.ack':
                if body.get('request_item_id') not in self.result_ids or body.get('turn_generation')!=self.generation:return web.Response(status=409)
                self.acks.append(copy.deepcopy(body))
            elif body['type']=='response.continue':
                if not self.acks or body.get('turn_generation')!=self.generation:return web.Response(status=409)
                self.active_continuation='continuation-'+body['response_id'];self.continuations.append(copy.deepcopy(body))
            elif body['type']=='response.started' and self.active_continuation:
                if body.get('continuation_id')!=self.active_continuation:return web.Response(status=409)
                self.active_continuation=None
        response=await super().control(request)
        if response.status==200:
            payload=json.loads(response.body)
            if isinstance(payload.get('data'),dict):
                payload['data']['turn_generation']=self.generation
                if tail.endswith('/events') and body['type']=='response.continue':payload['data']['continuation_id']='continuation-'+body['response_id']
            return web.json_response(payload)
        return response

    async def close(self):
        self.tool_release.set()
        await super().close()

    async def provider(self, ws):
        self.provider_clients.add(ws)
        try:
            await self.emit(ws,'session.created',session={'id':'provider-session'})
            async for raw in ws:
                event=json.loads(raw);self.provider_sent.append(event);kind=event['type']
                if kind=='session.update':
                    self.updated=event
                    await self.emit(ws,'session.updated',session=dict(id='provider-session',turn_detection=event['session']['turn_detection'],tools=event['session']['tools']))
                elif kind=='input_audio_buffer.append':
                    if not self.audio_open:
                        self.audio_open=True;self.input_number+=1;self.current_audio_item='audio-'+str(self.input_number)
                        if self.task.get('turn_detection_mode')=='server_vad':await self.emit(ws,'input_audio_buffer.speech_started',item_id=self.current_audio_item,audio_start_ms=0)
                elif kind=='input_audio_buffer.commit':
                    self.audio_open=False
                    await self.emit(ws,'input_audio_buffer.committed',item_id=self.current_audio_item)
                    parts=[{'type':'input_audio'}]+([{'type':'input_image'}] if self.frame_pending else [])
                    await self.emit(ws,'conversation.item.created',item=dict(id=self.current_audio_item,type='message',role='user',content=parts),previous_item_id=self.last_item)
                    self.last_item=self.current_audio_item;self.frame_pending=False
                    await self.emit(ws,'conversation.item.input_audio_transcription.completed',item_id=self.current_audio_item,transcript='technical protocol question')
                elif kind=='input_image_buffer.append':self.frame_pending=True
                elif kind=='response.create':
                    self.response_number+=1
                    await self.response(ws,'response-'+str(self.response_number))
                elif kind=='conversation.item.create':
                    item=copy.deepcopy(event['item']);item['id']='allocated-'+str(self.provider_count)
                    if self.wrong_echo and item.get('type')=='function_call_output':item['output']='conflicting result'
                    if self.ack_mode=='wrong_material' and item.get('type')=='message':item['content'][0]['text']='unmatched material text'
                    if (self.hold_output_echo and item.get('type')=='function_call_output'
                            or self.hold_context_echo and item.get('type')=='message'):
                        self.deferred_echo=(copy.deepcopy(item),self.last_item);self.output_sent.set();continue
                    await self.emit(ws,'conversation.item.created',item=item,previous_item_id=self.last_item)
                    self.last_item=item['id']
                elif kind=='input_audio_buffer.clear':await self.emit(ws,'input_audio_buffer.cleared')
                elif kind=='response.cancel':await self.emit(ws,'response.done',response=dict(id='response-'+str(self.response_number),status='cancelled',usage={'input_tokens':2,'output_tokens':1,'total_tokens':3}))
        except websockets.exceptions.ConnectionClosed:pass
        finally:self.provider_clients.discard(ws);self.provider_closed.set()

    async def response(self, ws, rid):
        if self.early_tool and self.response_number<=self.tool_turns:
            await self.emit(ws,'response.function_call_arguments.delta',response_id=rid,item_id='function-'+str(self.response_number),
                            call_id='call-'+str(self.response_number),output_index=0,delta=self.tool_raw[:1])
        await self.emit(ws,'response.created',response={'id':rid})
        item={'id':'assistant-'+rid,'type':'message','role':'assistant','content':[]}
        await self.emit(ws,'conversation.item.created',item=item,previous_item_id=self.last_item);self.last_item=item['id']
        if self.response_number<=self.tool_turns:
            if self.step_text:await self.emit(ws,'response.audio_transcript.delta',response_id=rid,delta=self.step_text)
            call_id='call-'+str(self.response_number);item_id='function-'+str(self.response_number)
            item=dict(id=item_id,type='function_call',call_id=call_id,name='server_capabilities',arguments='')
            await self.emit(ws,'response.output_item.added',response_id=rid,output_index=0,item=item)
            await self.emit(ws,'conversation.item.created',response_id=rid,item=item,previous_item_id=self.last_item);self.last_item=item_id
            if not self.early_tool:await self.emit(ws,'response.function_call_arguments.delta',response_id=rid,item_id=item_id,call_id=call_id,output_index=0,delta=self.tool_raw[:1])
            await self.emit(ws,'response.function_call_arguments.delta',response_id=rid,item_id=item_id,call_id=call_id,output_index=0,delta=self.tool_raw[1:])
            await self.emit(ws,'response.function_call_arguments.done',response_id=rid,item_id=item_id,call_id=call_id,output_index=0,name='server_capabilities',arguments=self.tool_raw)
            item['arguments']=self.tool_raw
            await self.emit(ws,'response.output_item.done',response_id=rid,output_index=0,item=item)
            await self.emit(ws,'response.done',response=dict(id=rid,status='completed',output=[item],usage=None if self.omit_usage else {'input_tokens':5,'output_tokens':2,'total_tokens':7}))
        else:
            await self.emit(ws,'response.audio_transcript.delta',response_id=rid,delta='Technical result verified')
            await self.emit(ws,'response.done',response=dict(id=rid,status='completed',output=[],usage={'input_tokens':8,'output_tokens':3,'total_tokens':11}))


class ToolLoopTests(unittest.IsolatedAsyncioTestCase):
    async def harness(self):
        h=await ToolHarness().start();self.addAsyncCleanup(h.close);return h

    async def start_turn(self,h):
        ws=await h.connect();await ws.send_json(audio());await ws.send_json({'type':'audio.commit'});return ws

    async def test_end_to_end_ack_continuation_stable_message_real_usage(self):
        h=await self.harness();ws=await self.start_turn(h);final=await receive(ws,'response.completed')
        self.assertEqual(final['text'],'Technical result verified')
        self.assertEqual(len(h.steps),1);self.assertEqual(h.steps[0]['usage']['total_tokens'],7)
        self.assertEqual(len(h.proposals),1);self.assertEqual(h.proposals[0]['calls'][0]['arguments'],{})
        self.assertEqual(h.acks[0]['request_item_id'],'rt-tool-result-call-1');self.assertTrue(h.acks[0]['provider_item_id'].startswith('allocated-'))
        self.assertEqual(len(h.continuations),1)
        started=[event for event in h.events if event['type']=='response.started']
        self.assertEqual(started[1]['continuation_id'],'continuation-response-1')
        completed=[event for event in h.events if event['type']=='response.completed']
        self.assertEqual(len(completed),1);self.assertEqual(completed[0]['usage']['total_tokens'],11)
        self.assertEqual(final['message_id'],'d'*32)
        self.assertEqual(final['usage']['total_tokens'],18)

    async def test_tool_wait_keeps_heartbeats_then_continues(self):
        h=await self.harness();h.tool_release.clear();ws=await self.start_turn(h)
        await asyncio.wait_for(h.tool_entered.wait(),2)
        before=len([body for path,body in h.bodies if path.endswith('/heartbeat')])
        await asyncio.sleep(1.2)
        after=len([body for path,body in h.bodies if path.endswith('/heartbeat')])
        self.assertGreater(after,before);self.assertFalse(any(event['type']=='response.completed' for event in h.events))
        h.tool_release.set();await receive(ws,'response.completed')

    async def test_new_manual_input_fences_old_wait_and_no_late_result(self):
        h=await self.harness();h.tool_release.clear();ws=await self.start_turn(h)
        await asyncio.wait_for(h.tool_entered.wait(),2)
        await ws.send_json(audio());await ws.send_json({'type':'audio.commit'})
        final=await receive(ws,'response.completed');h.tool_release.set();await asyncio.sleep(.1)
        self.assertEqual(final['response_id'],'response-2');self.assertEqual(h.generation,2)
        self.assertEqual(h.acks,[]);self.assertEqual(h.continuations,[])
        self.assertFalse(any(event['type']=='conversation.item.create' and event['item'].get('type')=='function_call_output' for event in h.provider_sent))

    async def test_revocation_stops_wait_without_publishing_result(self):
        h=await self.harness();h.tool_release.clear();ws=await self.start_turn(h)
        await asyncio.wait_for(h.tool_entered.wait(),2);h.active=False
        await asyncio.wait_for(h.provider_closed.wait(),3);h.tool_release.set()
        self.assertEqual(h.acks,[]);self.assertEqual(h.continuations,[])
        self.assertFalse(any(event['type']=='response.completed' for event in h.events))

    async def test_new_vad_speech_cancels_wait_and_old_result(self):
        h=await self.harness();h.task['turn_detection_mode']='server_vad';h.tool_release.clear()
        ws=await h.connect();await ws.send_json({'type':'listening.start'});await receive(ws,'listening.started')
        await ws.send_json(audio());await receive(ws,'speech.started');await h.vad_commit()
        await asyncio.wait_for(h.tool_entered.wait(),2)
        await ws.send_json(audio());await receive(ws,'speech.started');await h.vad_commit()
        final=await receive(ws,'response.completed');h.tool_release.set();await asyncio.sleep(.05)
        self.assertEqual(final['response_id'],'response-2');self.assertEqual(h.generation,2)
        self.assertEqual(h.acks,[]);self.assertEqual(h.continuations,[])

    async def test_input_during_result_ack_wait_discards_late_echo(self):
        h=await self.harness();h.hold_output_echo=True;ws=await self.start_turn(h)
        await asyncio.wait_for(h.output_sent.wait(),2)
        await ws.send_json(audio());await ws.send_json({'type':'audio.commit'})
        await receive(ws,'response.completed')
        item,anchor=h.deferred_echo;await h.emit(next(iter(h.provider_clients)),'conversation.item.created',item=item,previous_item_id=anchor)
        await asyncio.sleep(.1)
        self.assertEqual(h.acks,[]);self.assertEqual(h.continuations,[])
        self.assertFalse(any(event['type']=='session.error' for event in h.events))

    async def test_interrupt_while_waiting_does_not_cancel_finished_provider_response(self):
        h=await self.harness();h.tool_release.clear();ws=await self.start_turn(h)
        await asyncio.wait_for(h.tool_entered.wait(),2)
        h.generation+=1;h.pending_commands=[dict(id='tool-interrupt',response_id='response-1',type='interrupt')]
        await receive(ws,'response.canceled');h.tool_release.set();await asyncio.sleep(.05)
        self.assertTrue(any(event['type']=='command.ack' for event in h.events))
        self.assertFalse(any(event['type']=='response.cancel' for event in h.provider_sent))
        self.assertEqual(h.continuations,[])

    async def test_unknown_step_usage_stays_null_and_total_timeout_is_bounded(self):
        h=await self.harness();h.omit_usage=True;ws=await self.start_turn(h);await receive(ws,'response.completed')
        self.assertIsNone(h.steps[0]['usage'])
        h=await self.harness();h.task['tool_policy']['timeout_seconds']=1;h.tool_release.clear();ws=await self.start_turn(h)
        self.assertEqual((await receive(ws,'error'))['code'],'TOOL_TIMEOUT')
        self.assertEqual(h.acks,[]);self.assertEqual(h.continuations,[])

    async def test_logical_reply_keeps_prefix_and_budget_failure_keeps_actual_step_usage(self):
        h=await self.harness();h.step_text='Checking. ';ws=await self.start_turn(h)
        final=await receive(ws,'response.completed');self.assertEqual(final['text'],'Checking. Technical result verified')
        self.assertEqual([event['text'] for event in h.events if event['type']=='response.completed'],['Technical result verified'])
        h=await self.harness();h.task['tool_policy']['max_session_tokens']=1;ws=await self.start_turn(h)
        self.assertEqual((await receive(ws,'error'))['code'],'TOOL_LIMIT_EXCEEDED')
        self.assertEqual(h.steps[0]['usage']['total_tokens'],7);self.assertEqual(h.proposals,[])

    async def test_malformed_json_result_conflict_and_wrong_echo_fail_closed(self):
        for mode,code in [('json','TOOL_ARGUMENTS_INVALID'),('result','TOOL_PROTOCOL_ERROR'),('echo','TOOL_PROTOCOL_ERROR')]:
            h=await self.harness()
            if mode=='json':h.tool_raw='{"x":1,"x":2}'
            if mode=='result':h.malformed_result=True
            if mode=='echo':h.wrong_echo=True
            ws=await self.start_turn(h);self.assertEqual((await receive(ws,'error'))['code'],code)
            self.assertEqual(h.continuations,[]);self.assertFalse(any(event['type']=='response.completed' for event in h.events))

    async def test_round_budget_no_extra_dispatch(self):
        h=await self.harness();h.task['tool_policy']['max_rounds']=1;h.tool_turns=2
        ws=await self.start_turn(h);self.assertEqual((await receive(ws,'error'))['code'],'TOOL_LIMIT_EXCEEDED')
        self.assertEqual(len(h.proposals),1)

    async def test_early_argument_fragment_waits_for_response_identity(self):
        h=await self.harness();h.early_tool=True;ws=await self.start_turn(h)
        await receive(ws,'response.completed');self.assertEqual(len(h.proposals),1)

    async def test_real_provider_order_committed_then_audio_image_item_echo(self):
        h=await self.harness();h.task['consent']['camera']=True;ws=await h.connect()
        await ws.send_json(audio());await ws.send_json(dict(type='frame.append',image=jpeg(),mime_type='image/jpeg'))
        await ws.send_json({'type':'audio.commit'});await receive(ws,'response.completed')
        self.assertEqual(len(h.proposals),1)

    async def test_committed_item_identity_does_not_allow_unrequested_media_or_user_text(self):
        from realtime_worker.session import Session
        for known,part,camera,frame,accepted in (
            (True,'input_audio',False,False,True),(False,'input_audio',False,False,False),
            (True,'input_image',True,True,True),(True,'input_image',True,False,False),
            (True,'input_image',False,True,False),(True,'input_text',True,True,False)):
            job=task();job['consent']['camera']=camera
            session=Session(Harness().cfg,None,None,None,job);session.ready=True;session.waiting_response=True
            if known:session.audio_items['committed-item']=frame
            content=[{'type':'input_audio'}]+([{'type':part}] if part!='input_audio' else [])
            event={'type':'conversation.item.created','item':dict(id='committed-item',type='message',role='user',content=content),'previous_item_id':None}
            if accepted:await session.item_created(event)
            else:
                with self.assertRaises(Fault):await session.item_created(event)

    def test_complete_json_and_budget_validation(self):
        for raw in ('[]','{"a":NaN}','{"a":1,"a":2}','{} trailing','{"partial":'):
            with self.subTest(raw=raw),self.assertRaises(Fault):arguments(raw,4096)

    def test_interleaved_call_identity_and_conflicting_complete_arguments(self):
        session=NS(task={'tools':[TOOL],'tool_policy':dict(CAPS),'turn_generation':0},response={'id':'r','generation':0})
        loop=RealtimeTools(session)
        a=loop.record('r','item-a','call-a','server_capabilities');b=loop.record('r','item-b','call-b','server_capabilities')
        a['raw']='{';b['raw']='{}';a['raw']+='}'
        loop.complete(b,'{}');loop.complete(a,'{}')
        self.assertEqual(set(loop.calls),{'call-a','call-b'})
        with self.assertRaises(Fault):loop.record('r','conflicting-item','call-a','server_capabilities')
        with self.assertRaises(Fault):loop.complete(a,'{"changed":true}')
        with self.assertRaises(Fault):loop.synchronize(None)
        session.response=None;session.waiting_response=True;loop.continuation='already-issued'
        with self.assertRaises(Fault) as error:loop.synchronize(1)
        self.assertEqual(error.exception.code,'TOOL_RESULT_UNCERTAIN');self.assertEqual(loop.generation,1)


class ToolWireTests(unittest.IsolatedAsyncioTestCase):
    async def test_lost_ack_retries_fixed_authenticated_body_and_size_limit(self):
        requests=[];settings=Harness().cfg
        async def handle(request):
            requests.append(request)
            if len(requests)==1:raise httpx.ReadTimeout('private diagnostic')
            return httpx.Response(200,json={'code':0,'data':{'results':[],'remaining_calls':6,'turn_generation':2}})
        async with httpx.AsyncClient(base_url='http://control/',headers={'X-Realtime-Worker-Key':'isolated'},transport=httpx.MockTransport(handle)) as client:
            manager=Manager(settings,client)
            result=await manager.tools({'session_id':'a'*32,'lease_token':'lease','control_epoch':12},'provider','response',2,1,[],1024)
        self.assertEqual(result['turn_generation'],2)
        self.assertEqual(requests[0].content,requests[1].content)
        self.assertEqual(requests[0].url.path,'/internal/realtime-worker/sessions/'+'a'*32+'/tool-proposals')
        self.assertEqual(requests[0].headers['X-Realtime-Worker-Key'],'isolated');self.assertNotIn('Authorization',requests[0].headers)
        async with httpx.AsyncClient(base_url='http://control/',transport=httpx.MockTransport(lambda _:httpx.Response(200,content='x'*2048))) as client:
            with self.assertRaises(Fault):await Manager(settings,client).tools({'session_id':'a'*32,'lease_token':'lease','control_epoch':12},'p','r',2,1,[],1024)

    async def test_revocation_never_retries(self):
        for status in (401,403,404,409,410):
            requests=[]
            def handle(request):requests.append(request);return httpx.Response(status,text='private diagnostic')
            async with httpx.AsyncClient(base_url='http://control/',transport=httpx.MockTransport(handle)) as client:
                with self.assertRaises(AuthorityLost):await Manager(Harness().cfg,client).tools({'session_id':'a'*32,'lease_token':'lease','control_epoch':12},'p','r',2,1,[],1024)
            self.assertEqual(len(requests),1)


if __name__=='__main__':unittest.main()
