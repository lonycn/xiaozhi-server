"""A5 pause/material protocol over real loopback HTTP+WSS; no cloud or business data."""
import asyncio
import base64
import copy
import io
import json
import unittest
from unittest.mock import patch
import httpx
from aiohttp import web
from PIL import Image
from realtime_worker.config import Config
from realtime_worker.perception import Perception,PerceptionFailure
from realtime_worker.materials import CAPS,Materials,MAX_CAPTURE_TAIL_CHUNKS,MAX_CAPTURE_TAIL_BYTES
from realtime_worker.control import Fault,Manager
from test_realtime_worker_network import receive,audio,jpeg,TICKET,ORIGIN
from test_realtime_worker_tool_loop import ToolHarness

MID='d'*32;PID='e'*32


def image():
    out=io.BytesIO();Image.new('RGB',(32,32),'red').save(out,'JPEG');return out.getvalue()


class SyntheticPerception:
    def __init__(self):self.calls=[];self.closed=False
    async def describe(self,data,aid,model,prompt=''):
        self.calls.append((aid,model))
        return dict(attachment_id=aid,model=model,text='Directly visible red square; no verified equipment identity.',
                    usage={'input_tokens':7,'output_tokens':2,'total_tokens':9},provider_request_id='technical-perception-request')
    async def close(self):self.closed=True


class MaterialHarness(ToolHarness):
    async def connect(self,ready=True,ticket=TICKET,capture=True):
        if not capture:return await super().connect(ready=ready,ticket=ticket)
        ws=await self.client.ws_connect(self.url,origin=ORIGIN)
        class Captured:
            token=None
            async def send_json(inner,event):
                if event.get('type') in ('audio.append','audio.commit','frame.append','listening.start','listening.stop'):
                    event={'capture_token':inner.token,**event}
                await ws.send_json(event)
            async def receive(inner):
                result=await ws.receive()
                if result.type==1:
                    event=json.loads(result.data)
                    if event.get('type') in ('ready','material.resume_ready'):
                        inner.token=event.get('capture_token')
                return result
            def __getattr__(inner,name):return getattr(ws,name)
        wrapper=Captured();self.browsers.append(wrapper)
        await ws.send_json(dict(type='connect',ticket=ticket,capture_protocol='material_capture_v1'))
        if ready:await receive(wrapper,'ready')
        return wrapper
    def __init__(self):
        super().__init__();self.tool_turns=0
        self.task['material_policy']=dict(**CAPS,perception_model='qwen3.8-flash')
        self.input_control=None;self.material=None;self.phase='live';self.material_calls=[];self.audits=[]
        self.prepared_entered=asyncio.Event();self.prepared_release=asyncio.Event();self.prepared_release.set()
        self.perception=SyntheticPerception()

    def pause(self):
        self.generation+=1;self.phase='pausing';self.input_control=dict(pause_id=PID,action='pause_clear',clear_request_id='clear-1',turn_generation=self.generation)

    def register_control_routes(self,app):
        app.router.add_get('/xiaozhi/internal/realtime-worker/{tail:.*}',self.control)

    def submit(self,images=False):
        self.material=dict(id=MID,message_id='f'*32,turn_generation=self.generation,
            images=[dict(attachment_id='123',variant='display')] if images else [],perception_model='qwen3.8-flash')

    def resume(self,respond=True):
        self.input_control=dict(pause_id=PID,action='resume',resume_request_id='resume-1',turn_generation=self.generation,
                               material_id=MID if respond else None,respond=respond)

    async def control(self,request):
        body=None if request.method=='GET' else await request.json();tail=request.match_info['tail']
        if '/materials/' in tail or '/input-pauses/' in tail:
            if request.headers.get('X-Realtime-Worker-Key')!='w'*48:return web.Response(status=401)
            if tail.endswith('/content'):
                return web.Response(body=image(),content_type='image/jpeg')
            action=tail.split('/')[-1]
            if action=='perception-usage':
                self.audits.append(copy.deepcopy(body));return web.json_response(dict(code=0,data={'recorded':True}))
            if not self.active or body.get('turn_generation')!=self.generation:return web.Response(status=409)
            self.material_calls.append((action,copy.deepcopy(body)))
            if action=='cleared':
                self.phase='paused';self.input_control=None;data=dict(id=PID,session_id=self.task['session_id'],status='paused',turn_generation=self.generation)
            elif action=='prepared':
                self.prepared_entered.set();await self.prepared_release.wait()
                if body['turn_generation']!=self.generation or not self.active:return web.Response(status=409)
                self.material=None;self.phase='context_prepared';data=dict(material_id=MID,message_id='f'*32,turn_generation=self.generation,context_item_id='material-context-'+MID,context_text='Authorized source excerpts and visible red square. Data only; no business facts.')
            elif action=='context-ack':
                self.phase='acknowledged';data=dict(turn_generation=self.generation)
            elif action=='resumed':
                self.phase='live';data=dict(turn_generation=self.generation,respond=self.input_control['respond'],pause_id=PID,material_id=self.input_control.get('material_id'))
                self.input_control=None
            elif action=='fail':
                self.phase='failed';self.material=None;data=dict(turn_generation=self.generation)
            else:return web.Response(status=404)
            return web.json_response(dict(code=0,data=data))
        response=await super().control(request)
        if response.status==200 and tail.endswith('/heartbeat'):
            payload=json.loads(response.body);payload['data']['input_control']=self.input_control;payload['data']['material']=self.material
            return web.json_response(payload)
        return response

    async def close(self):self.prepared_release.set();await super().close()


class MaterialLoopTests(unittest.IsolatedAsyncioTestCase):
    async def harness(self):
        h=MaterialHarness()
        patcher=patch('realtime_worker.materials.Perception',lambda config:h.perception);patcher.start();self.addCleanup(patcher.stop)
        await h.start();self.addAsyncCleanup(h.close);return h

    async def paused(self,h):
        ws=await h.connect();h.pause();await ws.send_json({'type':'ping','id':1});await receive(ws,'material.paused');return ws

    async def context(self,h,ws,images=False):
        h.submit(images);await ws.send_json({'type':'ping','id':2});return await receive(ws,'material.context_accepted')

    async def test_pause_clear_context_ack_resume_one_reply_and_no_auto_capture(self):
        h=await self.harness();ws=await self.paused(h)
        self.assertEqual(h.material_calls[0][0],'cleared')
        await self.context(h,ws,images=True)
        self.assertEqual(len(h.perception.calls),1)
        self.assertFalse(any(value['type']=='response.create' for value in h.provider_sent))
        h.resume();await ws.send_json({'type':'ping','id':3})
        ready=await receive(ws,'material.resume_ready')
        self.assertEqual((ready['pause_id'],ready['material_id'],ready['turn_generation']),(PID,MID,1))
        self.assertFalse(ready['listening_allowed']);self.assertTrue(ready['requires_user_action'])
        await receive(ws,'response.completed');await ws.send_json({'type':'ping','id':4});await receive(ws,'pong')
        self.assertEqual(len([value for value in h.provider_sent if value['type']=='response.create']),1)
        self.assertFalse(any(value['type']=='input_audio_buffer.append' for value in h.provider_sent))
        ack=next(body for action,body in h.material_calls if action=='context-ack')
        self.assertEqual(ack['request_item_id'],'material-context-'+MID);self.assertTrue(ack['provider_item_id'].startswith('allocated-'))
        self.assertEqual(h.audits[0]['images'][0]['usage']['total_tokens'],9)
        self.assertNotIn('text',h.audits[0]['images'][0])

    async def test_document_context_uses_no_perception_and_empty_resume_is_null(self):
        h=await self.harness();ws=await self.paused(h);await self.context(h,ws,images=False)
        self.assertEqual(h.perception.calls,[])
        h.resume(False);h.input_control['material_id']='failed-old-id'
        await ws.send_json({'type':'ping','id':3});ready=await receive(ws,'material.resume_ready')
        self.assertIsNone(ready['material_id']);self.assertTrue(ready['listening_allowed'])
        self.assertFalse(any(value['type']=='response.create' for value in h.provider_sent))

    async def test_paused_capture_tail_is_dropped_without_error_or_provider_input(self):
        h=await self.harness();ws=await self.paused(h);await ws.send_json(audio())
        await ws.send_json({'type':'ping','id':5});self.assertEqual((await receive(ws,'pong'))['id'],5)
        self.assertFalse(any(value['type']=='input_audio_buffer.append' for value in h.provider_sent))

    async def test_recording_pause_absorbs_18_inflight_pcm_chunks_then_material_completes(self):
        h=await self.harness();ws=await h.connect()
        await ws.send_json(audio());await ws.send_json({'type':'ping','id':1});await receive(ws,'pong')
        before=sum(e['type']=='input_audio_buffer.append' for e in h.provider_sent)
        h.pause()
        for _ in range(18):await ws.send_json(audio())
        await receive(ws,'material.paused');await ws.send_json({'type':'ping','id':2});await receive(ws,'pong')
        self.assertEqual(sum(e['type']=='input_audio_buffer.append' for e in h.provider_sent),before)
        generation=h.generation
        await self.context(h,ws,images=False);h.resume();await ws.send_json({'type':'ping','id':3})
        await receive(ws,'material.resume_ready');await receive(ws,'response.completed')
        self.assertEqual(h.generation,generation)
        self.assertEqual(sum(e['type']=='input_audio_buffer.clear' for e in h.provider_sent),1)
        self.assertEqual(len([e for e in h.events if e['type']=='response.completed']),1)

    async def test_old_capture_token_after_resume_cannot_start_a_new_turn_or_clear_current_input(self):
        h=await self.harness();h.tool_turns=0;ws=await self.paused(h);old=ws.token
        await self.context(h,ws,images=False);h.resume();await ws.send_json({'type':'ping','id':3})
        await receive(ws,'material.resume_ready');await receive(ws,'response.completed');fresh=ws.token
        self.assertRegex(fresh,r'^[A-Za-z0-9_-]{32}$');self.assertNotEqual(old,fresh)
        generation=h.generation;before=len(h.provider_sent)
        for _ in range(18):await ws.send_json(dict(audio(),capture_token=old))
        for kind in ('audio.commit','listening.stop','listening.start'):
            await ws.send_json(dict(type=kind,capture_token=old))
        await ws.send_json({'type':'ping','id':9});await receive(ws,'pong')
        self.assertEqual(h.generation,generation)
        self.assertEqual(len([e for e in h.events if e['type']=='response.completed']),1)
        self.assertFalse(any(e['type'] in ('input_audio_buffer.append','input_audio_buffer.clear','input_audio_buffer.commit') for e in h.provider_sent[before:]))
        await ws.send_json(audio());await ws.send_json({'type':'audio.commit'});await receive(ws,'response.completed')
        self.assertEqual(h.generation,generation+1)

    async def test_unknown_or_missing_capture_token_and_legacy_material_control_fail_closed(self):
        h=await self.harness();ws=await self.paused(h)
        await ws.send_json(dict(audio(),capture_token='x'*32))
        self.assertEqual((await receive(ws,'error'))['code'],'INPUT_LIMIT')
        missing=await self.harness();untagged=await missing.connect()
        await untagged.send_str(json.dumps(audio()))
        self.assertEqual((await receive(untagged,'error'))['code'],'INPUT_LIMIT')
        legacy=await self.harness();old=await legacy.connect(capture=False);legacy.pause()
        await old.send_json({'type':'ping','id':1})
        self.assertEqual((await receive(old,'error'))['code'],'MATERIAL_CAPTURE_PROTOCOL_REQUIRED')

    async def test_locked_frame_tail_requires_camera_consent_and_is_not_forwarded(self):
        h=await self.harness();h.task['consent']['camera']=True;ws=await self.paused(h)
        await ws.send_json(dict(type='frame.append',image=jpeg(),mime_type='image/jpeg'))
        await ws.send_json({'type':'ping','id':5});await receive(ws,'pong')
        self.assertFalse(any(e['type']=='input_image_buffer.append' for e in h.provider_sent))

    async def test_locked_explicit_commit_listening_and_invalid_capture_still_rejected(self):
        h=await self.harness();ws=await self.paused(h)
        for event in ({'type':'audio.commit'},{'type':'listening.start'}):
            await ws.send_json(event);self.assertEqual((await receive(ws,'error'))['code'],'INPUT_PAUSED')
        await ws.send_json(audio(b'odd'))
        self.assertEqual((await receive(ws,'error'))['code'],'INPUT_LIMIT')
        self.assertFalse(any(e['type']=='input_audio_buffer.commit' for e in h.provider_sent))

    async def test_paused_capture_tail_is_bounded_and_unauthorized_camera_is_not_absorbed(self):
        h=await self.harness();ws=await self.paused(h)
        for _ in range(MAX_CAPTURE_TAIL_CHUNKS+1):await ws.send_json(audio(b'\0\0'))
        self.assertEqual((await receive(ws,'error'))['code'],'INPUT_LIMIT')
        other=await self.harness();camera=await self.paused(other)
        await camera.send_json(dict(type='frame.append',image=jpeg(),mime_type='image/jpeg'))
        self.assertEqual((await receive(camera,'error'))['code'],'INPUT_LIMIT')

    def test_capture_tail_counters_are_bounded_without_retaining_payload(self):
        from types import SimpleNamespace as NS
        flow=Materials.__new__(Materials)
        flow.discarded_input_chunks=flow.discarded_input_bytes=0
        flow.total_discarded_input_chunks=flow.total_discarded_input_bytes=0
        flow.discard_capture_tail(MAX_CAPTURE_TAIL_BYTES)
        self.assertEqual((flow.discarded_input_chunks,flow.discarded_input_bytes),(1,MAX_CAPTURE_TAIL_BYTES))
        with self.assertRaises(Fault):flow.discard_capture_tail(2)
        self.assertEqual(flow.total_discarded_input_chunks,1)

    async def test_new_generation_during_prepared_keeps_known_usage_but_no_context(self):
        h=await self.harness();ws=await self.paused(h);h.prepared_release.clear();h.submit(True)
        await ws.send_json({'type':'ping','id':2});await asyncio.wait_for(h.prepared_entered.wait(),2)
        before=len([body for path,body in h.bodies if path.endswith('/heartbeat')])
        await asyncio.sleep(1.1)
        self.assertGreater(len([body for path,body in h.bodies if path.endswith('/heartbeat')]),before)
        h.generation+=1;h.material=None;await ws.send_json({'type':'ping','id':3});await receive(ws,'pong')
        h.prepared_release.set();await asyncio.sleep(.15)
        self.assertTrue(h.audits);self.assertEqual(h.audits[0]['images'][0]['usage']['total_tokens'],9)
        self.assertFalse(any(value['type']=='conversation.item.create' for value in h.provider_sent))

    async def test_perception_failure_stays_paused_and_unknown_usage_is_not_zero(self):
        h=await self.harness();ws=await self.paused(h)
        async def failure(data,aid,model,prompt=''):
            raise PerceptionFailure('PERCEPTION_AUTH',dict(attachment_id=aid,model=model,usage=None,provider_request_id=None))
        h.perception.describe=failure;h.submit(True);await ws.send_json({'type':'ping','id':2})
        self.assertEqual((await receive(ws,'material.failed'))['code'],'PERCEPTION_AUTH')
        self.assertEqual(h.phase,'failed');self.assertIsNone(h.audits[0]['images'][0]['usage'])
        h.resume(False);await ws.send_json({'type':'ping','id':3})
        self.assertIsNone((await receive(ws,'material.resume_ready'))['material_id'])
        self.assertFalse(any(value['type']=='response.create' for value in h.provider_sent))

    async def test_wrong_context_echo_and_cancel_after_send_require_new_session(self):
        h=await self.harness();h.ack_mode='wrong_material';ws=await self.paused(h)
        h.submit();await ws.send_json({'type':'ping','id':2})
        self.assertEqual((await receive(ws,'error'))['code'],'MATERIAL_CONTEXT_UNCONFIRMED')
        self.assertFalse(any(action=='context-ack' for action,body in h.material_calls))
        h=await self.harness();h.hold_context_echo=True;ws=await self.paused(h)
        h.submit();await ws.send_json({'type':'ping','id':2});await asyncio.wait_for(h.output_sent.wait(),2)
        h.generation+=1;await ws.send_json({'type':'ping','id':3})
        self.assertEqual((await receive(ws,'error'))['code'],'MATERIAL_CONTEXT_UNCONFIRMED')
        self.assertFalse(any(value['type']=='response.create' for value in h.provider_sent))


class PerceptionTests(unittest.IsolatedAsyncioTestCase):
    def config(self,**extra):
        values=dict(manager_url='http://127.0.0.1',worker_key='w'*48,provider_url='wss://test.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime',api_key='rt-test',
                    perception_model='qwen3.8-flash',perception_base_url='https://dashscope.aliyuncs.com/compatible-mode/v1',perception_api_key='vision-test')
        values.update(extra);return Config(**values)

    async def test_fixed_native_vision_request_no_tools_actual_usage(self):
        requests=[]
        def handle(request):
            requests.append(request);return httpx.Response(200,headers={'x-request-id':'perception-request'},json=dict(
                choices=[dict(finish_reason='stop',message={'content':'A directly visible red square.'})],usage=dict(prompt_tokens=12,completion_tokens=4,total_tokens=16)))
        client=httpx.AsyncClient(base_url=self.config().perception_base_url+'/',transport=httpx.MockTransport(handle))
        provider=Perception(self.config(),client);result=await provider.describe(image(),'123','qwen3.8-flash');await provider.close()
        body=json.loads(requests[0].content);self.assertNotIn('tools',body);self.assertFalse(body['enable_thinking']);self.assertEqual(body['max_tokens'],500)
        self.assertEqual(result['usage']['total_tokens'],16);self.assertEqual(result['provider_request_id'],'perception-request')
        self.assertTrue(body['messages'][0]['content'][1]['image_url']['url'].startswith('data:image/jpeg;base64,'))

    async def test_limited_output_preserves_observed_usage_and_errors_are_sanitized(self):
        for status,code in ((401,'PERCEPTION_AUTH'),(429,'PERCEPTION_ERROR'),(200,'MATERIAL_LIMIT')):
            def handle(request):return httpx.Response(status,json=dict(choices=[dict(finish_reason='length',message={'content':'partial'})],usage=dict(prompt_tokens=3,completion_tokens=4,total_tokens=7),error={'message':'private diagnostic'}))
            async with httpx.AsyncClient(base_url=self.config().perception_base_url+'/',transport=httpx.MockTransport(handle)) as client:
                with self.assertRaises(PerceptionFailure) as error:await Perception(self.config(),client).describe(image(),'123','qwen3.8-flash')
                self.assertEqual(error.exception.code,code);self.assertNotIn('private',str(error.exception))
                if status==200:self.assertEqual(error.exception.observed['usage']['total_tokens'],7)

    def test_optin_config_does_not_accept_task_endpoints_or_unknown_models(self):
        for extra in ({'perception_model':'other'},{'perception_base_url':'http://localhost:1'},
                      {'perception_base_url':'https://arbitrary.invalid/v1'},{'perception_timeout_seconds':30},
                      {'perception_api_key':'bad\r\nkey'},{'perception_max_tokens':True}):
            with self.assertRaises(ValueError):self.config(**extra)
        with self.assertRaises(ValueError):self.config(perception_api_key='w'*48)

    async def test_cancellation_retains_observed_request_id_and_no_guessed_usage(self):
        entered=asyncio.Event()
        class Body(httpx.AsyncByteStream):
            async def __aiter__(self):
                entered.set();await asyncio.Event().wait();yield b''
        def handle(request):return httpx.Response(200,headers={'x-request-id':'observed-before-cancel'},stream=Body())
        async with httpx.AsyncClient(base_url=self.config().perception_base_url+'/',transport=httpx.MockTransport(handle)) as client:
            job=asyncio.create_task(Perception(self.config(),client).describe(image(),'123','qwen3.8-flash'))
            await asyncio.wait_for(entered.wait(),1);job.cancel()
            with self.assertRaises(asyncio.CancelledError) as error:await job
            self.assertEqual(error.exception.observed['provider_request_id'],'observed-before-cancel')
            self.assertIsNone(error.exception.observed['usage'])

    async def test_invalid_or_oversized_images_never_reach_provider(self):
        def forbidden(request):self.fail('Invalid images must never call provider')
        async with httpx.AsyncClient(base_url=self.config().perception_base_url+'/',transport=httpx.MockTransport(forbidden)) as client:
            for data in (b'not-jpeg',b'x'*1048577):
                with self.assertRaises(PerceptionFailure):await Perception(self.config(),client).describe(data,'123','qwen3.8-flash')

    async def test_material_transport_uses_fixed_paths_and_bounds_semantic_failures(self):
        captured=[]
        def handle(request):
            captured.append(request)
            if request.url.path.endswith('/prepared'):return httpx.Response(502,json={'code':502,'msg':'MATERIAL_INPUT_UNAVAILABLE','data':None})
            return httpx.Response(200,headers={'content-type':'image/jpeg'},content=b'x'*1025)
        task=dict(session_id='a'*32,lease_token='opaque-lease',control_epoch=7)
        async with httpx.AsyncClient(base_url='http://control/',headers={'X-Realtime-Worker-Key':'technical'},transport=httpx.MockTransport(handle)) as client:
            manager=Manager(self.config(),client)
            with self.assertRaises(Fault) as error:await manager.material_callback(task,MID,'prepared',2,images=[])
            self.assertEqual(error.exception.code,'MATERIAL_INPUT_UNAVAILABLE');self.assertEqual(len(captured),1)
            with self.assertRaises(Fault):await manager.material_media(task,MID,'123',1024)
            self.assertEqual(captured[-1].headers['X-Realtime-Lease'],'opaque-lease')
            self.assertEqual(captured[-1].headers['X-Realtime-Worker-Key'],'technical')
            self.assertEqual(captured[-1].url.params['variant'],'display')
            with self.assertRaises(Fault):await manager.material_media(task,'https://untrusted.invalid','123',1024)


if __name__=='__main__':unittest.main()
