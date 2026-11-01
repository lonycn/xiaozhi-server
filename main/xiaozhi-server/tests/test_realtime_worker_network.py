"""Real loopback HTTP/WebSocket tests. No provider credentials or business database."""
import asyncio
import base64
import copy
import io
import json
from pathlib import Path
import tempfile
import time
import unittest
from aiohttp import web, ClientSession, WSServerHandshakeError, WSMsgType
from PIL import Image
import websockets
from realtime_worker.config import Config, MODEL
from realtime_worker.control import Manager
from realtime_worker.provider import Provider
from realtime_worker.server import Server

ORIGIN = 'http://127.0.0.1:18894'
TICKET = 'opaque_short_ticket_' + 'a'*32
SESSION = 'a'*32


def task():
    return dict(session_id=SESSION, conversation_id='b'*32, control_epoch=12,
        lease_token='lease_'+'c'*64, lease_seconds=12, model=MODEL,server_id='realtime-local-1',
        consent=dict(microphone=True,camera=False), context=[], context_seq=7,
        limits=dict(heartbeat_seconds=1,max_session_seconds=1800,max_audio_chunk_bytes=65536,
            max_audio_turn_bytes=1920000,max_frame_bytes=190000,max_frame_pixels=921600,
            min_frame_interval_ms=1000,max_event_text_chars=16000,max_output_chars=128000),
        input_audio=dict(encoding='s16le',sample_rate=16000,channels=1),
        output_audio=dict(encoding='s16le',sample_rate=24000,channels=1))


def audio(data=b'\0\0'*1600): return dict(type='audio.append',audio=base64.b64encode(data).decode())


def jpeg():
    data=io.BytesIO()
    Image.new('RGB',(32,32),'blue').save(data,format='JPEG')
    return base64.b64encode(data.getvalue()).decode()


class Harness:
    def __init__(self, **config):
        self.cfg = Config(manager_url='http://127.0.0.1:1/xiaozhi',worker_key='w'*48,
            provider_url='wss://test.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime',api_key='private-provider-test-key',
            enabled=True, **config)
        self.task = task(); self.active=True; self.used=False; self.events=[]; self.bodies=[]
        self.provider_sent=[]; self.provider_clients=set(); self.response_tasks=set(); self.callback_seen={}
        self.pending_injections=[]; self.pending_commands=[]; self.browsers=[]
        self.silent=False; self.sequence=0; self.mode='normal'; self.ack_items=True; self.lost_event_ack=False
        self.false_heartbeat=False; self.error_http=False; self.callback_interrupt=False; self.delta_fence=False; self.force_canceled=False; self.terminal_receipt=False; self.reject_commit=False
        self.response_number=0; self.provider_count=0; self.input_number=0; self.audio_open=False; self.current_audio_item=None; self.last_item=None; self.ack_mode='allocated'; self.old_ack=None; self.provider_closed=asyncio.Event()
        self.media_received=asyncio.Event(); self.hold_response=asyncio.Event()
        self.control_delay=0; self.provider_delay=0

    async def start(self):
        app=web.Application()
        app.router.add_post('/xiaozhi/internal/realtime-worker/{tail:.*}',self.control)
        self.control_runner=web.AppRunner(app,access_log=None)
        await self.control_runner.setup()
        control_site=web.TCPSite(self.control_runner,'127.0.0.1',0)
        await control_site.start()
        port=control_site._server.sockets[0].getsockname()[1]
        self.cfg.manager_url=f'http://127.0.0.1:{port}/xiaozhi'
        self.provider_server=await websockets.serve(self.provider,'127.0.0.1',0,max_size=1<<20)
        provider_port=self.provider_server.sockets[0].getsockname()[1]
        async def connect(url, **kwargs):
            self.asserted_provider_url=url
            return await websockets.connect(f'ws://127.0.0.1:{provider_port}/realtime', **kwargs)
        self.server=Server(self.cfg,provider=Provider(self.cfg,connector=connect))
        self.runner=web.AppRunner(self.server.app,access_log=None)
        await self.runner.setup()
        site=web.TCPSite(self.runner,'127.0.0.1',0)
        await site.start()
        self.url=f'http://127.0.0.1:{site._server.sockets[0].getsockname()[1]}/realtime'
        self.client=ClientSession()
        return self

    async def close(self):
        for browser in self.browsers: await browser.close()
        await self.client.close()
        await self.runner.cleanup()
        self.provider_server.close()
        await self.provider_server.wait_closed()
        await self.control_runner.cleanup()
        for job in self.response_tasks: job.cancel()
        await asyncio.gather(*self.response_tasks,return_exceptions=True)

    async def control(self, request):
        try: body=await request.json()
        except ConnectionResetError: return web.Response(status=499)
        self.bodies.append((request.path,body))
        if request.headers.get('X-Realtime-Worker-Key')!='w'*48 or request.headers.get('Authorization'):
            return web.Response(status=401)
        tail=request.match_info['tail']
        if self.control_delay: await asyncio.sleep(self.control_delay)
        if tail=='consume':
            if self.used or body.get('ticket')!=TICKET: return web.Response(status=409)
            self.used=True
            return web.json_response(dict(code=0,data=self.task))
        if self.error_http: return web.Response(status=503)
        if not self.active or body.get('lease_token')!=self.task['lease_token']:
            return web.Response(status=409)
        if tail.endswith('/heartbeat'):
            if type(body.get('input_busy')) is not bool: return web.Response(status=400)
            if self.false_heartbeat: return web.json_response(dict(code=0,data=dict(active=False,lease_seconds=0,injections=[],commands=[])))
            if self.callback_interrupt and any(e['type']=='response.delta' for e in self.events):
                self.callback_interrupt=False
                self.pending_commands=[dict(id='command-1',response_id='response-1',type='interrupt',status='dispatched')]
            injections=[]
            if not body['input_busy'] and self.pending_injections:
                injections=[self.pending_injections.pop(0)]
            commands=self.pending_commands[:1];self.pending_commands=self.pending_commands[1:]
            return web.json_response(dict(code=0,data=dict(active=True,lease_seconds=12,injections=injections,commands=commands)))
        if tail.endswith('/events'):
            if self.reject_commit and body['type']=='input.committed': return web.Response(status=409)
            if self.delta_fence and body['type']=='response.delta':
                self.delta_fence=False
                self.pending_commands=[dict(id='command-1',response_id='response-1',type='interrupt',status='dispatched')]
                return web.json_response(dict(code=409,msg='RESPONSE_INTERRUPTED_OR_TERMINAL',data=None),status=409)
            identity=body['provider_event_id'];fingerprint=json.dumps(body,sort_keys=True)
            if identity in self.callback_seen:
                if self.callback_seen[identity]!=fingerprint: return web.Response(status=409)
                return web.json_response(dict(code=0,data=dict(sequence=body['sequence'],session={})))
            if body['sequence']!=self.sequence+1: return web.Response(status=409)
            self.sequence=body['sequence'];self.callback_seen[identity]=fingerprint;self.events.append(body)
            if self.lost_event_ack:
                self.lost_event_ack=False
                return web.Response(status=503)
            status='streaming'
            if body['type']=='response.completed':
                status='canceled' if self.force_canceled else 'completed'
                if self.force_canceled:
                    self.pending_commands=[dict(id='command-1',response_id='response-1',type='interrupt',status='dispatched')]
            elif body['type']=='response.canceled': status='canceled'
            return web.json_response(dict(code=0,data=dict(sequence=self.sequence,session={'status':'ended'} if self.terminal_receipt else {},message_id='d'*32,message_status=status)))
        return web.Response(status=404)

    async def emit(self, ws, kind=None, **payload):
        self.provider_count+=1
        event=dict(event_id='provider_'+str(self.provider_count), **payload)
        if kind: event['type']=kind
        await ws.send(json.dumps(event))
        return event

    async def response(self, ws, rid):
        if self.mode=='out_of_order':
            await self.emit(ws,'response.audio_transcript.delta',response_id=rid,delta='first')
        await self.emit(ws,'response.created',response=dict(id=rid))
        assistant='assistant-item-'+rid
        await self.emit(ws,'conversation.item.created',item={'id':assistant,'type':'message','role':'assistant','content':[]},previous_item_id=self.last_item)
        self.last_item=assistant
        if self.mode=='late_asr':
            await self.emit(ws,'conversation.item.input_audio_transcription.completed',item_id='audio-input-1',transcript='late technical speech')
        event=await self.emit(ws,'response.audio_transcript.delta',response_id=rid,delta='possible ')
        if self.mode=='duplicates': await ws.send(json.dumps(event))
        if self.mode=='changed_duplicate':
            event['delta']='changed duplicate';await ws.send(json.dumps(event));return
        if self.mode=='tool':
            await self.emit(ws,'response.function_call_arguments.done',response_id=rid,call_id='call-1',arguments='private-tool-arguments');return
        await self.emit(ws,'response.text.delta',response_id=rid,delta='MUST_NOT_DUPLICATE')
        await self.emit(ws,'response.audio.delta',response_id=rid,delta=base64.b64encode(b'\1\0'*240).decode())
        if self.mode=='hold':
            await self.hold_response.wait()
            return
        await self.emit(ws,'response.audio_transcript.done',response_id=rid,transcript='possible periodic noise; verify measurements')
        await self.emit(ws,'response.done',response=dict(id=rid,status='completed',
            output=[],usage=dict(input_tokens=452,output_tokens=95,total_tokens=547)))

    async def provider(self, ws):
        self.provider_clients.add(ws)
        self.provider_header=ws.request.headers.get('Authorization')
        try:
            if self.provider_delay: await asyncio.sleep(self.provider_delay)
            if self.mode=='typed_error':
                await self.emit(ws,'error',error={'code':'429','message':'do not leak rate detail'});return
            if self.mode=='untyped_error':
                await self.emit(ws,code='Model.AccessDenied',message='do not leak provider diagnostic');return
            await self.emit(ws,'session.created',session=dict(id='provider-session'))
            async for raw in ws:
                event=json.loads(raw);self.provider_sent.append(event)
                kind=event['type']
                if kind=='session.update':
                    self.updated=event
                    await self.emit(ws,'session.updated',session=dict(id='provider-session',turn_detection=event['session'].get('turn_detection')))
                elif kind=='conversation.item.create':
                    if self.ack_items:
                        item=copy.deepcopy(event['item']);item['id']='allocated-item-'+str(self.provider_count+1)
                        previous=self.last_item
                        if self.ack_mode=='wrong_text': item['content'][0]['text']='different text'
                        if self.ack_mode=='wrong_role': item['role']='assistant'
                        if self.ack_mode=='wrong_anchor': previous='unrelated-predecessor'
                        if self.ack_mode=='late_ack' and self.old_ack:
                            late=copy.deepcopy(self.old_ack);late['event_id']='late-'+str(self.provider_count+1)
                            await ws.send(json.dumps(late))
                        ack=await self.emit(ws,'conversation.item.created',item=item,previous_item_id=previous)
                        self.old_ack=copy.deepcopy(ack)
                        self.last_item=item['id']
                        if self.ack_mode=='duplicate_ack':
                            duplicate=copy.deepcopy(ack);duplicate['event_id']='duplicate-'+str(self.provider_count+1)
                            await ws.send(json.dumps(duplicate))
                elif kind=='input_audio_buffer.append':
                    self.media_received.set()
                    if self.silent: continue
                    if not self.audio_open:
                        self.input_number+=1;self.audio_open=True
                        self.current_audio_item='audio-input-'+str(self.input_number)
                        if self.task.get('turn_detection_mode') == 'server_vad':
                            await self.emit(ws,'input_audio_buffer.speech_started',item_id=self.current_audio_item,audio_start_ms=0)
                        await self.emit(ws,'conversation.item.created',item={'id':self.current_audio_item,'type':'message','role':'user','content':[{'type':'input_audio'}]},previous_item_id=self.last_item)
                        self.last_item=self.current_audio_item
                elif kind=='input_audio_buffer.clear':
                    self.audio_open=False
                    await self.emit(ws,'input_audio_buffer.cleared')
                elif kind=='input_audio_buffer.commit':
                    audio_item=self.current_audio_item;self.audio_open=False
                    if self.mode!='late_asr': await self.emit(ws,'conversation.item.input_audio_transcription.completed',item_id=audio_item,transcript='technical speech')
                    await self.emit(ws,'input_audio_buffer.committed',item_id=audio_item)
                elif kind=='response.create':
                    self.response_number+=1
                    rid='response-'+str(self.response_number)
                    job=asyncio.create_task(self.response(ws,rid));self.response_tasks.add(job)
                elif kind=='response.cancel':
                    await self.emit(ws,'response.audio_transcript.delta',response_id='response-'+str(self.response_number),delta='LATE_AFTER_INTERRUPT')
                    await self.emit(ws,'response.done',response=dict(id='response-'+str(self.response_number),status='cancelled',usage={'input_tokens':10,'output_tokens':5,'total_tokens':15}))
        except websockets.exceptions.ConnectionClosed:
            pass
        finally:
            self.provider_clients.discard(ws);self.provider_closed.set()

    async def vad_commit(self):
        ws=next(iter(self.provider_clients))
        audio_item=self.current_audio_item;self.audio_open=False
        await self.emit(ws,'input_audio_buffer.speech_stopped',item_id=audio_item,audio_end_ms=100)
        await self.emit(ws,'input_audio_buffer.committed',item_id=audio_item)
        await self.emit(ws,'conversation.item.input_audio_transcription.completed',item_id=audio_item,transcript='automatic speech')
        self.response_number+=1
        job=asyncio.create_task(self.response(ws,'response-'+str(self.response_number)));self.response_tasks.add(job)

    async def connect(self, ready=True, ticket=TICKET):
        ws=await self.client.ws_connect(self.url,origin=ORIGIN)
        self.browsers.append(ws)
        await ws.send_json(dict(type='connect',ticket=ticket))
        if ready: await receive(ws,'ready')
        return ws


async def receive(ws, kind, timeout=4):
    async def loop():
        while True:
            frame=await ws.receive()
            if frame.type != WSMsgType.TEXT: raise AssertionError('socket closed before '+kind)
            event=json.loads(frame.data)
            if event.get('type')==kind: return event
    return await asyncio.wait_for(loop(),timeout)


class NetworkTests(unittest.IsolatedAsyncioTestCase):
    async def harness(self, **kwargs):
        value=await Harness(**kwargs).start(); self.addAsyncCleanup(value.close); return value

    async def test_server_vad_waits_for_real_turn_events_and_never_creates_duplicate_response(self):
        h=await self.harness();h.task['turn_detection_mode']='server_vad'
        ws=await h.connect()
        self.assertEqual(h.updated['session']['turn_detection'],dict(type='server_vad',threshold=.5,silence_duration_ms=800))
        self.assertNotIn('create_response',h.updated['session']['turn_detection'])
        await ws.send_json(dict(type='listening.start'));await receive(ws,'listening.started')
        for turn in range(2):
            await ws.send_json(audio());await receive(ws,'speech.started')
            await h.vad_commit();await receive(ws,'speech.stopped');await receive(ws,'response.completed')
        self.assertEqual(len([e for e in h.events if e['type']=='input.committed']),2)
        self.assertEqual(len([e for e in h.events if e['type']=='response.completed']),2)
        self.assertFalse(any(e['type'] in ('response.create','input_audio_buffer.commit') for e in h.provider_sent))
        await ws.send_json(dict(type='listening.stop'));await receive(ws,'listening.stopped')
        self.assertEqual(len([e for e in h.provider_sent if e['type']=='input_audio_buffer.clear']),1)

    async def test_server_vad_silence_does_not_consume_utterance_limit_and_injections_wait_for_pause(self):
        h=await self.harness();h.task['turn_detection_mode']='server_vad';h.silent=True
        ws=await h.connect();await ws.send_json(dict(type='listening.start'));await receive(ws,'listening.started')
        h.pending_injections=[dict(id='injection-auto',message_id='message-auto',provider_item_id='text-auto',text='queued text')]
        for _ in range(31): await ws.send_json(audio(b'\0\0'*32000))
        await asyncio.sleep(.15)
        self.assertFalse(any(e['type']=='response.create' for e in h.provider_sent))
        self.assertFalse(any(e['type']=='conversation.item.create' for e in h.provider_sent))
        await ws.send_json(dict(type='listening.stop'));await receive(ws,'listening.stopped')
        await receive(ws,'injection.status');await receive(ws,'response.completed')
        self.assertEqual(len([e for e in h.events if e['type']=='injection.ack']),1)

    async def test_server_vad_barge_in_clears_output_and_waits_for_provider_terminal_ack(self):
        h=await self.harness();h.task['turn_detection_mode']='server_vad';h.mode='hold'
        ws=await h.connect();await ws.send_json(dict(type='listening.start'));await receive(ws,'listening.started')
        await ws.send_json(audio());await receive(ws,'speech.started');await h.vad_commit();await receive(ws,'audio.delta')
        await ws.send_json(audio());event=await receive(ws,'response.canceled')
        self.assertEqual(event['status'],'interrupting');self.assertTrue(event['clear_audio'])
        self.assertFalse(any(e['type']=='response.canceled' for e in h.events))
        provider=next(iter(h.provider_clients))
        await h.emit(provider,'response.audio.delta',response_id='response-1',delta=base64.b64encode(b'\0\0').decode())
        await h.emit(provider,'response.done',response=dict(id='response-1',status='cancelled'))
        self.assertEqual((await receive(ws,'response.canceled'))['status'],'canceled')
        self.assertEqual(len([e for e in h.events if e['type']=='response.canceled']),1)
        self.assertFalse(any(e['type']=='response.cancel' for e in h.provider_sent))
        h.mode='normal';await h.vad_commit();await receive(ws,'response.completed')

    async def test_server_vad_barge_in_before_response_created_suppresses_late_old_turn(self):
        h=await self.harness();h.task['turn_detection_mode']='server_vad'
        ws=await h.connect();await ws.send_json(dict(type='listening.start'));await receive(ws,'listening.started')
        await ws.send_json(audio());await receive(ws,'speech.started')
        provider=next(iter(h.provider_clients));item=h.current_audio_item;h.audio_open=False
        await h.emit(provider,'input_audio_buffer.speech_stopped',item_id=item)
        await h.emit(provider,'input_audio_buffer.committed',item_id=item)
        await ws.send_json(audio());await receive(ws,'speech.started')
        h.response_number=1
        await h.emit(provider,'response.created',response=dict(id='response-1'))
        await h.emit(provider,'response.audio.delta',response_id='response-1',delta=base64.b64encode(b'\0\0').decode())
        await h.emit(provider,'response.done',response=dict(id='response-1',status='cancelled'))
        self.assertEqual((await receive(ws,'response.canceled'))['status'],'canceled')
        self.assertEqual([e['type'] for e in h.events if e['type'].startswith('response.')],['response.started','response.canceled'])

    async def test_server_vad_command_during_automatic_cancel_is_acked_without_duplicate_cancel(self):
        h=await self.harness();h.task['turn_detection_mode']='server_vad';h.mode='hold'
        ws=await h.connect();await ws.send_json(dict(type='listening.start'));await receive(ws,'listening.started')
        await ws.send_json(audio());await receive(ws,'speech.started');await h.vad_commit();await receive(ws,'audio.delta')
        await ws.send_json(audio());await receive(ws,'speech.started')
        h.pending_commands=[dict(id='command-vad',type='interrupt',response_id='response-1')]
        provider=next(iter(h.provider_clients))
        await h.emit(provider,'response.done',response=dict(id='response-1',status='cancelled'))
        await receive(ws,'response.canceled')
        await asyncio.sleep(.05)
        self.assertFalse(any(e['type']=='response.cancel' for e in h.provider_sent))
        self.assertEqual(len([e for e in h.events if e['type']=='command.ack']),1)

    def test_server_vad_duration_limit_is_speech_based_and_mode_is_validated(self):
        from realtime_worker.session import Session
        from realtime_worker.control import Fault
        cfg=Config(manager_url='http://127.0.0.1',worker_key='w'*48,provider_url='wss://test.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime',api_key='test')
        job=task();job['turn_detection_mode']='semantic_vad'
        with self.assertRaises(Fault): Session(cfg,None,None,None,job)
        job['turn_detection_mode']='server_vad';session=Session(cfg,None,None,None,job)
        session.ready=True;session.turn_audio=0;session.check_deadlines()
        session.speech_started=time.monotonic()-61
        with self.assertRaises(Fault) as failure: session.check_deadlines()
        self.assertEqual(failure.exception.code,'AUDIO_TURN_LIMIT')

    async def test_server_vad_revocation_and_no_audio_before_explicit_listening(self):
        h=await self.harness();h.task['turn_detection_mode']='server_vad'
        ws=await h.connect();await ws.send_json(audio());self.assertEqual((await receive(ws,'error'))['code'],'INPUT_LIMIT')
        second=await self.harness();second.task['turn_detection_mode']='server_vad'
        ws=await second.connect();await ws.send_json(dict(type='listening.start'));await receive(ws,'listening.started')
        second.active=False;await ws.send_json(audio());await asyncio.wait_for(second.provider_closed.wait(),2)
        self.assertFalse(any(e['type']=='input_audio_buffer.append' for e in second.provider_sent))

    async def test_application_heartbeat_is_authorized_and_never_a_provider_or_canonical_turn(self):
        h=await self.harness();ws=await h.connect()
        await ws.send_json(dict(type='ping',id=1));self.assertEqual((await receive(ws,'pong'))['id'],1)
        self.assertFalse(any(e['type']=='ping' for e in h.provider_sent))
        self.assertFalse(any(e['type']=='pong' for e in h.events))
        h.active=False;await ws.send_json(dict(type='ping',id=2));await asyncio.wait_for(h.provider_closed.wait(),2)
        self.assertEqual([e['type'] for e in h.events],['session.ready'])

    async def test_temporary_manager_failure_closes_old_transport_and_never_replays_after_return(self):
        h=await self.harness();ws=await h.connect();await ws.send_json(audio());await asyncio.wait_for(h.media_received.wait(),2)
        before=len([e for e in h.provider_sent if e['type']=='input_audio_buffer.append']);h.error_http=True
        await ws.send_json(dict(type='ping',id=1));await asyncio.wait_for(h.provider_closed.wait(),2)
        h.error_http=False;await asyncio.sleep(.1)
        self.assertEqual(len([e for e in h.provider_sent if e['type']=='input_audio_buffer.append']),before)
        self.assertEqual([e['type'] for e in h.events],['session.ready'])

    async def test_provider_abrupt_disconnect_closes_both_sockets_without_synthetic_usage(self):
        h=await self.harness();ws=await h.connect();provider=next(iter(h.provider_clients));provider.transport.abort()
        self.assertEqual((await receive(ws,'error'))['code'],'PROVIDER_UNAVAILABLE')
        self.assertEqual([e['type'] for e in h.events],['session.ready','session.error'])
        self.assertFalse(any(e.get('usage') for e in h.events))

    async def test_audio_roundtrip_authoritative_history_usage_and_private_keys(self):
        h=await self.harness();ws=await h.connect()
        await ws.send_json(audio());await ws.send_json(dict(type='audio.commit'))
        reply=await receive(ws,'response.completed')
        self.assertEqual(reply['usage'],dict(input_tokens=452,output_tokens=95,total_tokens=547))
        self.assertEqual(reply['message_id'],'d'*32)
        self.assertNotIn('MUST_NOT_DUPLICATE',reply['text'])
        self.assertEqual(h.provider_header,'Bearer private-provider-test-key')
        self.assertEqual(h.updated['session']['audio']['input']['format']['sample_rate'],16000)
        self.assertIsNone(h.updated['session']['turn_detection'])
        self.assertEqual(h.updated['session']['input_audio_transcription'],{'model':'qwen3-asr-flash-realtime'})
        self.assertEqual([e['type'] for e in h.events],['session.ready','input.transcript','input.committed','response.started','response.delta','response.completed'])
        self.assertEqual([e['sequence'] for e in h.events],list(range(1,7)))
        self.assertNotIn('private-provider-test-key',json.dumps(h.bodies))
        self.assertNotIn('base64',json.dumps(h.events))
        await ws.close();await asyncio.wait_for(h.provider_closed.wait(),2)

    async def test_input_commit_ack_precedes_response_even_when_asr_completes_later(self):
        h=await self.harness();h.mode='late_asr';ws=await h.connect()
        await ws.send_json(audio());await ws.send_json(dict(type='audio.commit'))
        await receive(ws,'response.completed')
        kinds=[e['type'] for e in h.events]
        self.assertLess(kinds.index('input.committed'),kinds.index('response.started'))
        self.assertGreater(kinds.index('input.transcript'),kinds.index('response.started'))
        self.assertEqual(next(e for e in h.events if e['type']=='input.committed')['provider_item_id'],'audio-input-1')
        other=await self.harness();other.reject_commit=True;second=await other.connect()
        await second.send_json(audio());await second.send_json(dict(type='audio.commit'))
        frame=await asyncio.wait_for(second.receive(),2)
        self.assertNotEqual(frame.type,WSMsgType.TEXT)
        self.assertFalse(any(e['type']=='response.create' for e in other.provider_sent))

    async def test_camera_consent_actual_image_wire_and_one_fps_limit(self):
        h=await self.harness();h.task['consent']['camera']=True
        ws=await h.connect();await ws.send_json(audio())
        frame=dict(type='frame.append',image=jpeg(),mime_type='image/jpeg')
        await ws.send_json(frame)
        for _ in range(100):
            if any(e['type']=='input_image_buffer.append' for e in h.provider_sent): break
            await asyncio.sleep(.01)
        self.assertTrue(any(e.get('image')==frame['image'] for e in h.provider_sent))
        await ws.send_json(frame)
        self.assertEqual((await receive(ws,'error'))['code'],'INPUT_LIMIT')
        self.assertEqual(h.events[-1]['type'],'session.error')
        self.assertEqual(sum(e['type']=='input_image_buffer.append' for e in h.provider_sent),1)

    async def test_frame_format_pixels_bytes_and_turn_audio_limits(self):
        png=io.BytesIO();Image.new('RGB',(8,8),'blue').save(png,format='PNG')
        for data,pixels in [(base64.b64encode(png.getvalue()).decode(),921600),
                            (base64.b64encode(b'not-jpeg').decode(),921600),
                            (base64.b64encode(b'x'*190001).decode(),921600),
                            (jpeg(),100)]:
            h=await self.harness();h.task['consent']['camera']=True;h.task['limits']['max_frame_pixels']=pixels
            ws=await h.connect();await ws.send_json(audio())
            await ws.send_json(dict(type='frame.append',image=data,mime_type='image/jpeg'))
            self.assertEqual((await receive(ws,'error'))['code'],'INPUT_LIMIT')
            self.assertFalse(any(e['type']=='input_image_buffer.append' for e in h.provider_sent))
        other=await self.harness();other.task['limits']['max_audio_turn_bytes']=4000
        ws=await other.connect();await ws.send_json(audio());await ws.send_json(audio(b'\0\0'*500))
        self.assertEqual((await receive(ws,'error'))['code'],'INPUT_LIMIT')
        self.assertEqual(sum(e['type']=='input_audio_buffer.append' for e in other.provider_sent),1)

    async def test_camera_without_consent_is_not_sent(self):
        h=await self.harness();ws=await h.connect();await ws.send_json(audio())
        await ws.send_json(dict(type='frame.append',image=jpeg(),mime_type='image/jpeg'))
        self.assertEqual((await receive(ws,'error'))['code'],'INPUT_LIMIT')
        self.assertFalse(any(e['type']=='input_image_buffer.append' for e in h.provider_sent))

    async def test_text_injection_requires_matching_provider_ack_and_is_once(self):
        h=await self.harness()
        h.task['context']=[dict(role='user',content='previous accepted text'),dict(role='assistant',content='previous answer')]
        h.pending_injections=[dict(id='inject-1',message_id='message-1',text='new persisted text',provider_item_id='server-item-1')]
        ws=await h.connect()
        status=await receive(ws,'injection.status',timeout=4)
        self.assertEqual(status['status'],'acknowledged')
        self.assertTrue(status['provider_item_id'].startswith('allocated-item-'))
        ack=next(event for event in h.events if event['type']=='injection.ack')
        self.assertEqual(ack['request_item_id'],'server-item-1')
        self.assertEqual(ack['provider_item_id'],status['provider_item_id'])
        await receive(ws,'response.completed')
        self.assertEqual(sum(e['type']=='injection.ack' for e in h.events),1)
        new=[e for e in h.provider_sent if e['type']=='conversation.item.create' and e['item']['id']=='server-item-1']
        self.assertEqual(len(new),1)
        self.assertEqual(len([e for e in h.provider_sent if e['type']=='conversation.item.create']),3)
        self.assertEqual(sum(e['type']=='response.create' for e in h.provider_sent),1)

    async def test_allocated_ack_requires_exact_echo_role_and_real_predecessor(self):
        for mode in ('wrong_text','wrong_role','wrong_anchor'):
            h=await self.harness();h.ack_mode=mode
            h.pending_injections=[dict(id='inject-1',message_id='message-1',text='persisted exact text',provider_item_id='server-item-1')]
            ws=await h.connect()
            self.assertEqual((await receive(ws,'error'))['code'],'PROVIDER_ERROR')
            self.assertFalse(any(event['type']=='injection.ack' for event in h.events))
            self.assertFalse(any(event['type']=='response.create' for event in h.provider_sent))
            self.assertEqual(next(event for event in h.events if event['type']=='injection.failed')['error_code'],'PROVIDER_REJECTED')

    async def test_context_echo_mismatch_never_reaches_ready(self):
        h=await self.harness();h.ack_mode='wrong_text'
        h.task['context']=[dict(role='user',content='immutable previous context')]
        ws=await h.connect(ready=False)
        self.assertEqual((await receive(ws,'error'))['code'],'PROVIDER_ERROR')
        self.assertFalse(any(event['type']=='session.ready' for event in h.events))

    async def test_duplicate_and_late_allocated_item_acks_do_not_reinject_or_rewind(self):
        h=await self.harness();h.ack_mode='duplicate_ack'
        h.pending_injections=[dict(id='inject-1',message_id='message-1',text='first persisted text',provider_item_id='server-item-1')]
        ws=await h.connect();await receive(ws,'injection.status');await receive(ws,'response.completed')
        self.assertEqual(sum(event['type']=='injection.ack' for event in h.events),1)
        h.ack_mode='late_ack'
        h.pending_injections=[dict(id='inject-2',message_id='message-2',text='second persisted text',provider_item_id='server-item-2')]
        ack=await receive(ws,'injection.status');await receive(ws,'response.completed')
        self.assertEqual(ack['injection_id'],'inject-2')
        self.assertEqual(sum(event['type']=='injection.ack' for event in h.events),2)
        self.assertEqual(sum(event['type']=='response.create' for event in h.provider_sent),2)
        self.assertFalse(any(event['type']=='session.error' for event in h.events))

    async def test_missing_ack_fails_injection_without_synthetic_success(self):
        h=await self.harness(ack_timeout_seconds=.15);h.ack_items=False
        h.pending_injections=[dict(id='inject-1',message_id='message-1',text='persisted text',provider_item_id='server-item-1')]
        ws=await h.connect()
        error=await receive(ws,'error',timeout=4)
        self.assertEqual(error['code'],'PROVIDER_TIMEOUT')
        self.assertFalse(any(e['type']=='injection.ack' for e in h.events))
        self.assertTrue(any(e['type']=='injection.failed' for e in h.events))
        self.assertFalse(any(e['type']=='response.create' for e in h.provider_sent))

    async def test_interrupt_flushes_then_waits_for_real_provider_cancellation(self):
        h=await self.harness();h.mode='hold';ws=await h.connect()
        await ws.send_json(audio());await ws.send_json(dict(type='audio.commit'))
        started=await receive(ws,'response.started');await receive(ws,'transcript.delta')
        h.pending_commands=[dict(id='command-1',response_id=started['response_id'],type='interrupt',status='dispatched')]
        first=await receive(ws,'response.canceled')
        final=await receive(ws,'response.canceled')
        self.assertEqual(first['status'],'interrupting');self.assertTrue(first['clear_audio'])
        self.assertEqual(final['status'],'canceled')
        self.assertEqual(final['usage'],dict(input_tokens=10,output_tokens=5,total_tokens=15))
        self.assertEqual(next(e for e in h.events if e['type']=='response.canceled')['usage'],final['usage'])
        self.assertEqual([e['type'] for e in h.events][-2:],['response.canceled','command.ack'])
        self.assertNotIn('LATE_AFTER_INTERRUPT',json.dumps(h.events))
        self.assertFalse(any(e['type']=='response.completed' for e in h.events))

    async def test_revoked_lease_closes_both_sockets_and_no_late_callbacks(self):
        h=await self.harness();ws=await h.connect();before=len(h.events)
        h.active=False
        await ws.send_json(audio())
        message=await asyncio.wait_for(ws.receive(),2)
        self.assertNotEqual(message.type,WSMsgType.TEXT)
        await asyncio.wait_for(h.provider_closed.wait(),2)
        self.assertEqual(len(h.events),before)
        self.assertFalse(any(e['type']=='input_audio_buffer.append' for e in h.provider_sent))

    async def test_short_ticket_single_use_query_bearer_origin_and_timeout(self):
        h=await self.harness(auth_timeout_seconds=.1)
        for url,headers,origin in [(h.url+'?ticket='+TICKET,{},ORIGIN),
                                   (h.url,{'Authorization':'Bearer long-secret'},ORIGIN),
                                   (h.url,{},'https://evil.invalid')]:
            with self.assertRaises(WSServerHandshakeError): await h.client.ws_connect(url,headers=headers,origin=origin)
        unauth=await h.client.ws_connect(h.url,origin=ORIGIN);h.browsers.append(unauth)
        self.assertEqual((await receive(unauth,'error'))['code'],'AUTHENTICATION_REQUIRED')
        ws=await h.connect();await ws.close()
        second=await h.connect(ready=False)
        self.assertEqual((await receive(second,'error'))['code'],'AUTHENTICATION_REQUIRED')

    async def test_active_false_and_unavailable_control_stop_without_callbacks(self):
        for unavailable in (False,True):
            h=await self.harness();ws=await h.connect();before=len(h.events)
            if unavailable: h.error_http=True
            else: h.false_heartbeat=True
            await ws.send_json(audio())
            message=await asyncio.wait_for(ws.receive(),2)
            self.assertNotEqual(message.type,WSMsgType.TEXT)
            self.assertEqual(len(h.events),before)
            self.assertFalse(any(e['type']=='input_audio_buffer.append' for e in h.provider_sent))
            await asyncio.wait_for(h.provider_closed.wait(),2)

    async def test_commands_retrieved_during_output_gate_cannot_send_after_flush(self):
        h=await self.harness();h.mode='hold';h.callback_interrupt=True;ws=await h.connect()
        await ws.send_json(audio());await ws.send_json(dict(type='audio.commit'))
        first=await receive(ws,'response.canceled');self.assertEqual(first['status'],'interrupting')
        late=[]
        while True:
            event=json.loads((await asyncio.wait_for(ws.receive(),3)).data)
            late.append(event)
            if event['type']=='response.canceled' and event.get('status')=='canceled': break
        self.assertFalse(any(e['type'] in ('audio.delta','transcript.delta') for e in late))
        self.assertTrue(any(e['type']=='command.ack' for e in h.events))

    async def test_interrupted_delta_409_fence_is_recoverable_without_sequence_gap(self):
        h=await self.harness();h.mode='hold';h.delta_fence=True;ws=await h.connect()
        await ws.send_json(audio());await ws.send_json(dict(type='audio.commit'))
        await receive(ws,'response.canceled');await receive(ws,'response.canceled')
        self.assertFalse(any(e['type']=='response.delta' for e in h.events))
        self.assertEqual([e['sequence'] for e in h.events],list(range(1,len(h.events)+1)))
        self.assertEqual(h.events[-1]['type'],'command.ack')
        self.assertFalse(any(e['type']=='session.error' for e in h.events))

    async def test_late_canceled_audio_during_next_response_is_discarded(self):
        h=await self.harness();h.mode='hold';ws=await h.connect()
        await ws.send_json(audio());await ws.send_json(dict(type='audio.commit'))
        await receive(ws,'response.started');await receive(ws,'transcript.delta')
        h.pending_commands=[dict(id='command-1',response_id='response-1',type='interrupt',status='dispatched')]
        await receive(ws,'response.canceled');await receive(ws,'response.canceled')
        await ws.send_json(audio());await ws.send_json(dict(type='audio.commit'))
        started=await receive(ws,'response.started');self.assertEqual(started['response_id'],'response-2')
        provider=next(iter(h.provider_clients))
        await h.emit(provider,'response.audio.delta',response_id='response-1',delta=base64.b64encode(b'late').decode())
        await h.emit(provider,'response.audio_transcript.delta',response_id='response-1',delta='late canceled response')
        await h.emit(provider,'response.done',response=dict(id='response-2',status='completed',usage=None))
        done=await receive(ws,'response.completed')
        self.assertEqual(done['response_id'],'response-2')
        self.assertNotIn('late canceled',json.dumps(h.events))
        self.assertFalse(any(e['type']=='session.error' for e in h.events))

    async def test_server_shutdown_closes_active_provider_and_worker_tasks(self):
        h=await self.harness();h.mode='hold';ws=await h.connect()
        await ws.send_json(audio());await ws.send_json(dict(type='audio.commit'))
        await receive(ws,'response.started')
        await h.runner.cleanup()
        await asyncio.wait_for(h.provider_closed.wait(),2)
        self.assertFalse(h.server.sessions);self.assertEqual(h.server.connections,0)
        self.assertTrue(h.server.manager.client.is_closed)
        self.assertFalse(any('Session.' in getattr(t.get_coro(),'__qualname__','') for t in asyncio.all_tasks() if t is not asyncio.current_task()))

    async def test_terminal_callback_receipt_stops_media_and_injection_side_effects(self):
        h=await self.harness();h.terminal_receipt=True
        ws=await h.connect(ready=False)
        event=await receive(ws,'ended')
        self.assertTrue(event['clear_audio'])
        self.assertFalse(any(e['type']=='response.create' for e in h.provider_sent))
        self.assertEqual([e['type'] for e in h.events],['session.ready'])
        await asyncio.wait_for(h.provider_closed.wait(),2)

    async def test_completion_receipt_canceled_never_exposes_final_and_acks_done_race(self):
        h=await self.harness();h.force_canceled=True;ws=await h.connect()
        await ws.send_json(audio());await ws.send_json(dict(type='audio.commit'))
        canceled=await receive(ws,'response.canceled')
        self.assertNotIn('text',canceled);self.assertTrue(canceled['clear_audio'])
        self.assertTrue(any(e['type']=='command.ack' for e in h.events))
        self.assertFalse(any(e['type']=='response.cancel' for e in h.provider_sent))

    async def test_manual_mode_rejects_unsolicited_provider_response(self):
        h=await self.harness();ws=await h.connect()
        provider=next(iter(h.provider_clients))
        await h.emit(provider,'response.created',response={'id':'unsolicited-response'})
        self.assertEqual((await receive(ws,'error'))['code'],'PROVIDER_ERROR')
        self.assertFalse(any(e['type']=='response.started' for e in h.events))

    async def test_typed_provider_errors_tools_and_changed_duplicate_are_explicit(self):
        for mode,code in [('typed_error','PROVIDER_RATE_LIMIT'),('tool','UNSUPPORTED_TOOL'),('changed_duplicate','PROVIDER_ERROR')]:
            h=await self.harness();h.mode=mode;ws=await h.connect(ready=mode!='typed_error')
            if mode!='typed_error':
                await ws.send_json(audio());await ws.send_json(dict(type='audio.commit'))
            error=await receive(ws,'error')
            self.assertEqual(error['code'],code)
            if mode=='tool': self.assertEqual(error['response_id'],'response-1')
            self.assertFalse(any(e['type']=='response.completed' for e in h.events))
            self.assertNotIn('private-tool-arguments',json.dumps(h.events))

    async def test_bad_audio_and_unknown_url_frame_are_bounded(self):
        cases=[audio(b'x'),audio(b'x'*65538),dict(type='audio.commit'),
               dict(type='audio.append',audio='data:audio/pcm;base64,AA=='),
               dict(type='frame.append',image='https://evil.invalid/picture.jpg',mime_type='image/jpeg')]
        for event in cases:
            h=await self.harness();ws=await h.connect();await ws.send_json(event)
            self.assertEqual((await receive(ws,'error'))['code'],'INPUT_LIMIT')
            self.assertFalse(any(e['type'] in ('input_audio_buffer.append','input_audio_buffer.commit','input_image_buffer.append') for e in h.provider_sent))

    async def test_manual_audio_busy_prevents_text_dispatch_until_response_done(self):
        h=await self.harness();ws=await h.connect();await ws.send_json(audio())
        await asyncio.wait_for(h.media_received.wait(),2)
        h.pending_injections=[dict(id='inject-1',message_id='message-1',text='queued while recording',provider_item_id='server-item-1')]
        await asyncio.sleep(1.1)
        self.assertTrue(h.pending_injections)
        self.assertFalse(any(e['type']=='conversation.item.create' for e in h.provider_sent))
        await ws.send_json(dict(type='audio.commit'));await receive(ws,'response.completed')
        await receive(ws,'injection.status')
        self.assertFalse(h.pending_injections)

    async def test_input_before_provider_ready_and_idle_expiry_release_resources(self):
        h=await self.harness();h.provider_delay=.2;ws=await h.connect(ready=False)
        await receive(ws,'connected');await ws.send_json(audio())
        self.assertEqual((await receive(ws,'error'))['code'],'INPUT_LIMIT')
        self.assertFalse(any(e['type']=='input_audio_buffer.append' for e in h.provider_sent))
        await asyncio.wait_for(h.provider_closed.wait(),2)
        other=await self.harness();other.task['limits']['max_session_seconds']=1
        second=await other.connect();error=await receive(second,'error')
        self.assertEqual(error['code'],'INPUT_LIMIT')
        await asyncio.wait_for(other.provider_closed.wait(),2)

    async def test_untyped_provider_error_is_sanitized(self):
        h=await self.harness();h.mode='untyped_error';ws=await h.connect(ready=False)
        error=await receive(ws,'error')
        self.assertEqual(error['code'],'PROVIDER_AUTH')
        self.assertNotIn('diagnostic',json.dumps(h.events))
        self.assertEqual(h.events[-1]['error_code'],'PROVIDER_AUTH')

    async def test_duplicate_and_out_of_order_provider_events_remain_ordered(self):
        for mode in ('duplicates','out_of_order'):
            h=await self.harness();h.mode=mode;ws=await h.connect()
            await ws.send_json(audio());await ws.send_json(dict(type='audio.commit'))
            await receive(ws,'response.completed')
            kinds=[e['type'] for e in h.events]
            self.assertLess(kinds.index('response.started'),kinds.index('response.delta'))
            self.assertEqual(sum(e['type']=='response.completed' for e in h.events),1)
            if mode=='duplicates': self.assertEqual(sum(e['type']=='response.delta' for e in h.events),1)
            await ws.close()

    async def test_lost_callback_ack_retries_identical_event_without_duplicate_history(self):
        h=await self.harness();h.lost_event_ack=True;ws=await h.connect()
        ready=[b for p,b in h.bodies if p.endswith('/events') and b['type']=='session.ready']
        self.assertEqual(len(ready),2);self.assertEqual(ready[0],ready[1])
        self.assertEqual(len(h.events),1)
        await ws.close()

    async def test_input_limits_no_client_assistant_or_url_and_capacity(self):
        h=await self.harness(max_connections=1);ws=await h.connect()
        with self.assertRaises(WSServerHandshakeError): await h.client.ws_connect(h.url,origin=ORIGIN)
        await ws.send_json(dict(type='response.completed',text='forged assistant'))
        self.assertEqual((await receive(ws,'error'))['code'],'INPUT_LIMIT')
        self.assertNotIn('forged assistant',json.dumps(h.events))

    async def test_queue_pressure_stops_and_releases_provider_resources(self):
        h=await self.harness(queue_size=2);ws=await h.connect();h.control_delay=.08
        for _ in range(20): await ws.send_json(audio())
        await receive(ws,'error')
        await asyncio.wait_for(h.provider_closed.wait(),2)
        for _ in range(100):
            if h.server.connections==0: break
            await asyncio.sleep(.01)
        self.assertEqual(h.server.connections,0)
        self.assertFalse(h.server.sessions)
        self.assertEqual(h.events[-1]['error_code'],'INPUT_LIMIT')

    def test_configuration_is_private_disabled_and_loopback_only(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'config.json'
            values=dict(manager_url='http://127.0.0.1:1/xiaozhi',worker_key='w'*48,
                provider_url='wss://test.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime',api_key='test-api-key')
            path.write_text(json.dumps(values));path.chmod(0o644)
            with self.assertRaises(ValueError): Config.load(path)
            path.chmod(0o600);self.assertFalse(Config.load(path).enabled)
            with self.assertRaises(ValueError): Config(**dict(values,host='0.0.0.0'))
            with self.assertRaises(ValueError): Config(**dict(values,provider_url='wss://evil.invalid/api-ws/v1/realtime'))
            self.assertNotIn('test-api-key',repr(Config(**values)))
