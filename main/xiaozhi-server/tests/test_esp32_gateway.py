import asyncio
import json
import time
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

from aiohttp import web
from aiohttp.test_utils import TestServer
import aiohttp
from esp32_gateway.client import GatewayClient, GatewayError
from esp32_gateway.runtime import GatewayRuntime
# Existing providers initialize their logger at import time, before the test event loop.
import core.providers.tools.device_mcp.mcp_handler
import core.handle.sendAudioHandle


class GatewayTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.calls = []
        self.generation = 1
        self.device_id = 'db-id'
        self.command = []
        self.status = 'pending'
        app = web.Application()
        app.router.add_post('/internal/esp32/{path:.*}', self.api)
        app.router.add_post('/config/agent-models', self.role_api)
        self.server = TestServer(app)
        await self.server.start_server()
        self.http = aiohttp.ClientSession()
        self.client = GatewayClient(str(self.server.make_url('/')).rstrip('/'), 'local-gateway-secret', self.http)

    async def asyncTearDown(self):
        await self.http.close()
        await self.server.close()

    async def api(self, request):
        self.assertEqual(request.headers['X-ESP32-Gateway-Key'], 'local-gateway-secret')
        path = request.match_info['path']; body = await request.json()
        self.calls.append((path, body))
        if path == 'register':
            if body['token'] != 'proof': return web.json_response({}, status=401)
            return web.json_response({'deviceId':self.device_id, 'connectionId':'conn', 'generation':self.generation, 'gatewayId':body['gatewayId'], 'bindingVersion':'v1'})
        fence = body.get('fence', body)
        if fence['generation'] != self.generation: return web.json_response({}, status=409)
        if path == 'sessions/connect': return web.json_response({'id':'session'})
        if path == 'sessions/input': return web.json_response({'id':'a' * 32, 'control_version':1})
        if path == 'sessions/reply': return web.json_response({'status':self.status, 'text':'hello', 'runId':'run-1'} if self.status == 'completed' else {'status':'pending'})
        if path == 'poll':
            commands, self.command = self.command, []
            return web.json_response(commands)
        return web.json_response({'ok':True})

    async def role_api(self, request):
        self.assertEqual(request.headers['Authorization'], 'Bearer role-secret')
        body = await request.json()
        self.assertEqual(body, {'macAddress': '02:00:00:00:00:01', 'clientId': 'client', 'selectedModule': {}})
        return web.json_response({'code': 0, 'data': {
            'prompt': self.role_prompt,
            'selected_module': {'TTS': 'workspace-voice'},
            'TTS': {'workspace-voice': {'type': 'alibl_stream', 'model': 'cosyvoice-v2',
                'voice': 'fallback_voice', 'private_voice': self.role_voice, 'ttsRate': 10,
                'api_key': 'must-not-leave-role-source'}},
        }})

    async def test_bound_role_reloads_between_turns_and_pairs_prompt_with_voice(self):
        from core.providers.tts.dto.dto import SentenceType

        conn = self.conn()
        mac = self.device_id = conn.headers['device-id'] = '02:00:00:00:00:01'
        config = self.config()
        config['role_source'] = {'base_url': config['base_url'], 'device_mac': mac, 'secret': 'role-secret'}
        runtime = GatewayRuntime(conn, config)
        await runtime.start()
        self.addAsyncCleanup(runtime.close)
        self.status = 'completed'
        for index in range(2):
            self.role_prompt = f'角色设定 {index}'
            self.role_voice = f'voice_{index}'
            runtime.played_turns.clear()  # The fake Manager returns one fixed turn ID.
            await runtime._turn('hello')
            request = [body for path, body in self.calls if path == 'sessions/input'][-1]
            self.assertEqual(request['rolePrompt'], self.role_prompt)
            first, middle, last = [conn.tts.tts_text_queue.get_nowait() for _ in range(3)]
            self.assertEqual(first.sentence_type, SentenceType.FIRST)
            self.assertEqual(first.voice_profile,
                {'model': 'cosyvoice-v2', 'voice': self.role_voice, 'ttsRate': 10.0})
            self.assertIsNone(middle.voice_profile)
            self.assertIsNone(last.voice_profile)

    def config(self):
        return {'base_url': str(self.server.make_url('/')).rstrip('/'), 'gateway_key': 'local-gateway-secret', 'gateway_id':'gateway-1', 'execution_mode':'ai_worker'}

    def conn(self):
        import queue
        return SimpleNamespace(headers={'authorization':'Bearer proof', 'device-id':'aa:bb', 'client-id':'client'}, websocket=SimpleNamespace(close=AsyncMock(), send=AsyncMock()), session_id='ws-session', mcp_client=None, tts=SimpleNamespace(tts_text_queue=queue.Queue()), client_abort=False)

    async def test_integrated_mode_defaults_to_ai_worker(self):
        config = self.config(); del config['execution_mode']
        runtime = GatewayRuntime(self.conn(), config)
        self.assertEqual(runtime.mode, 'ai_worker')

    async def test_mcp_cold_start_can_finish_after_five_seconds(self):
        conn = self.conn()
        checks = 0

        async def is_ready():
            nonlocal checks
            checks += 1
            return checks > 60

        conn.mcp_client = SimpleNamespace(
            name_mapping={'self_get_device_status': 'self.get_device_status'},
            is_ready=AsyncMock(side_effect=is_ready))
        runtime = GatewayRuntime(conn, self.config())
        self.addAsyncCleanup(runtime.close)
        with patch('esp32_gateway.runtime.asyncio.sleep', new=AsyncMock()), \
                patch.object(runtime, 'spawn', side_effect=lambda coro: coro.close()):
            await runtime.start()
        self.assertTrue(runtime.active)
        conn.websocket.close.assert_not_awaited()
        self.assertIn('self.get_device_status', self.calls[0][1]['capabilities'])

    async def test_mcp_never_ready_still_fails_closed(self):
        conn = self.conn()
        conn.mcp_client = SimpleNamespace(name_mapping={}, is_ready=AsyncMock(return_value=False))
        runtime = GatewayRuntime(conn, self.config())
        self.addAsyncCleanup(runtime.close)
        with patch('esp32_gateway.runtime.asyncio.sleep', new=AsyncMock()):
            await runtime.start()
        self.assertFalse(runtime.active)
        self.assertFalse(self.calls)
        conn.websocket.close.assert_awaited_once()

    async def test_role_source_selection_preserves_original_device(self):
        first, second = '02:00:00:00:00:01', '90:70:69:32:88:e0'
        config = self.config()
        config['role_source'] = {'base_url': config['base_url'], 'device_mac': first, 'secret': 'role-secret'}
        config['role_sources'] = {second: {**config['role_source'], 'device_mac': second}}
        for mac in (first, second):
            conn = self.conn()
            conn.headers['device-id'] = mac
            runtime = GatewayRuntime(conn, config)
            self.assertEqual(runtime.role_source.device_mac, mac)

    async def test_deadline_wire_validation(self):
        from esp32_gateway.runtime import deadline_millis
        self.assertEqual(deadline_millis('1730000000000'), 1730000000000)
        self.assertEqual(deadline_millis(1730000000000), 1730000000000)
        for invalid in (True, 0, -1, '', '1.0', '-1', '１２', '1' * 17, 1.0):
            with self.assertRaises(ValueError):
                deadline_millis(invalid)

    async def test_register_fence_and_rejected_stale_generation(self):
        fence = await self.client.register('aa:bb', 'client', 'proof', 'gateway-1', [])
        self.assertEqual(fence['deviceId'], 'db-id')
        self.assertNotIn('token', fence)
        self.generation = 2
        with self.assertRaises(GatewayError) as error:
            await self.client.fence('heartbeat', fence)
        self.assertEqual(error.exception.status, 409)
        self.assertNotIn('proof', str(error.exception))

    async def test_runtime_lifecycle_and_canonical_reply(self):
        conn = self.conn(); runtime = GatewayRuntime(conn, self.config())
        await runtime.start()
        self.assertTrue(runtime.active)
        self.status = 'completed'
        await runtime._turn('what happened')
        self.assertEqual(conn.tts.tts_text_queue.qsize(), 3)
        self.assertEqual([p for p, _ in self.calls].count('sessions/input'), 1)
        await runtime.close()
        self.assertFalse(runtime.tasks)
        self.assertEqual(self.calls[-1][0], 'disconnect')

    async def test_dispatched_command_result_and_stale_fail_closed(self):
        conn = self.conn(); conn.mcp_client = SimpleNamespace(name_mapping={'self_get_device_status':'self.get_device_status'}, is_ready=AsyncMock(return_value=True))
        runtime = GatewayRuntime(conn, self.config()); await runtime.start()
        command = {'id':'cmd', 'tool':'self.get_device_status', 'arguments':'{}', 'deadline':str(int(time.time()*1000)+5000)}
        with patch('core.providers.tools.device_mcp.mcp_handler.call_mcp_tool', new=AsyncMock(return_value='{"audio_speaker":{"volume":20}}')) as call:
            await runtime._command(command)
            call.assert_awaited_once()
        result = next(body for path, body in self.calls if path == 'result')
        self.assertTrue(result['success'])
        self.generation = 2
        with patch('core.providers.tools.device_mcp.mcp_handler.call_mcp_tool', new=AsyncMock()) as blocked:
            with self.assertRaises(GatewayError):
                await runtime._command(command)
            blocked.assert_not_awaited()
        await runtime._turn('test')
        self.assertFalse(runtime.active)
        conn.websocket.close.assert_awaited_once()
        await runtime.close()

    async def test_only_conditional_stale_abort_is_ignored(self):
        runtime=GatewayRuntime(self.conn(),self.config());await runtime.start()
        with patch.object(runtime.client,'session',AsyncMock(side_effect=GatewayError(409,'STALE_ABORT'))):
            await runtime._abort('a'*32,1)
        self.assertTrue(runtime.active)
        with patch.object(runtime.client,'session',AsyncMock(side_effect=GatewayError(409,'STALE_CONNECTION'))):
            await runtime._abort('a'*32,1)
        self.assertFalse(runtime.active);await runtime.close()

    async def test_legacy_is_explicitly_failed_without_execution(self):
        conn = self.conn(); conn.intent_type = 'function_call'; conn.chat = MagicMock()
        config = self.config(); config['execution_mode'] = 'legacy_record_only'
        runtime = GatewayRuntime(conn, config); await runtime.start()
        await runtime._turn('please help')
        event = next(body for path, body in self.calls if path == 'sessions/event')
        self.assertEqual(event['eventType'], 'failure')
        self.assertEqual(event['data'], 'legacy_executor_unavailable_for_ai_only')
        conn.chat.assert_not_called()
        self.assertNotIn('sessions/output', [path for path, _ in self.calls])
        self.assertNotIn('sessions/reply', [path for path, _ in self.calls])
        await runtime.close()

    async def test_completed_turn_is_not_played_twice(self):
        conn = self.conn(); runtime = GatewayRuntime(conn, self.config()); await runtime.start()
        self.status = 'completed'; runtime.current_turn = 'a' * 32; runtime.current_control_version = 1
        await runtime._canonical_turn('a' * 32)
        await runtime._canonical_turn('a' * 32)
        self.assertEqual(conn.tts.tts_text_queue.qsize(), 3)
        await runtime.close()

    async def test_keeps_listening_until_first_synthesized_packet(self):
        from core.handle.sendAudioHandle import sendAudioMessage
        from core.providers.tts.dto.dto import SentenceType

        conn = self.conn()
        conn.logger = MagicMock()
        conn.client_is_speaking = False
        conn.calling = False
        conn.tts.tts_audio_first_sentence = True
        conn.esp32_gateway = runtime = GatewayRuntime(conn, self.config())
        await runtime.start()
        self.status = 'completed'
        runtime.current_turn = 'a' * 32
        runtime.current_control_version = 1
        await runtime._canonical_turn(runtime.current_turn)
        sid = conn.sentence_id
        conn.websocket.send.assert_not_awaited()
        await sendAudioMessage(conn, SentenceType.FIRST, [], '现在是下午两点', sid)
        conn.websocket.send.assert_not_awaited()
        self.assertFalse(conn.client_is_speaking)
        with patch('core.handle.sendAudioHandle.sendAudio', AsyncMock()) as send_audio:
            await sendAudioMessage(conn, SentenceType.MIDDLE, b'first-opus', None, sid)
        self.assertTrue(conn.client_is_speaking)
        self.assertEqual([json.loads(call.args[0])['state'] for call in conn.websocket.send.await_args_list],
                         ['start', 'sentence_start'])
        send_audio.assert_awaited_once_with(conn, b'first-opus', sentence_id=sid)
        await runtime.close()

    async def test_real_websocket_publish_fences_and_cleanup(self):
        import websockets
        from core.handle.sendAudioHandle import _do_send_audio, send_tts_message
        accepted = asyncio.get_running_loop().create_future()
        async def server(ws):
            accepted.set_result(ws)
            await ws.wait_closed()
        async with websockets.serve(server, '127.0.0.1', 0) as listener:
            port = listener.sockets[0].getsockname()[1]
            async with websockets.connect(f'ws://127.0.0.1:{port}') as peer:
                ws = await accepted
                conn = self.conn(); conn.websocket = ws; conn.conn_from_mqtt_gateway = False
                conn.session_id = 'ws-session'; conn.sentence_id = 'sentence-a'; conn.config = {}
                conn.clearSpeakStatus = MagicMock()
                conn.esp32_gateway = None
                await _do_send_audio(conn, b'legacy-frame', {'sentence_id':'sentence-a'})
                self.assertEqual(await asyncio.wait_for(peer.recv(), 1), b'legacy-frame')
                runtime = GatewayRuntime(conn, self.config()); await runtime.start()
                conn.esp32_gateway = runtime
                runtime.current_turn = 'a' * 32; runtime.current_control_version = 1; runtime.playback_authority = ('sentence-a', 'a' * 32, 'run-1', 1)
                self.status = 'completed'
                flow = {'sentence_id': 'sentence-a', 'packet_count': 0, 'sequence': 0}
                await _do_send_audio(conn, b'allowed', flow)
                self.assertEqual(await asyncio.wait_for(peer.recv(), 1), b'allowed')
                # A newer turn invalidates the old callback before it reaches the socket.
                runtime.current_turn = 'new-turn'
                await _do_send_audio(conn, b'old', flow)
                with self.assertRaises(asyncio.TimeoutError): await asyncio.wait_for(peer.recv(), .03)
                await send_tts_message(conn, 'stop', sentence_id='sentence-a')
                with self.assertRaises(asyncio.TimeoutError): await asyncio.wait_for(peer.recv(), .03)
                runtime.current_turn = 'a' * 32
                await send_tts_message(conn, 'stop', sentence_id='sentence-a')
                tagged = json.loads(await asyncio.wait_for(peer.recv(), 1))
                self.assertEqual(tagged['turn_id'], 'a' * 32); self.assertIs(type(tagged['control_version']), int)
                # Revocation after queueing rejects both control and first audio.
                runtime.current_turn = 'a' * 32; runtime.current_control_version = 1; self.generation = 2
                await _do_send_audio(conn, b'revoked', flow)
                await send_tts_message(conn, 'sentence_start', 'secret', sentence_id='sentence-a')
                await asyncio.sleep(.05)
                self.assertFalse(runtime.active)
                with self.assertRaises(websockets.exceptions.ConnectionClosed):
                    await asyncio.wait_for(peer.recv(), 1)
                await runtime.close()
    async def test_playback_stop_and_legacy_audio(self):
        from core.handle.sendAudioHandle import _do_send_audio, send_tts_message
        conn = self.conn(); conn.sentence_id = 's'; conn.config = {}; conn.conn_from_mqtt_gateway = False
        conn.clearSpeakStatus = MagicMock()
        conn.esp32_gateway = SimpleNamespace(authorize_playback=AsyncMock(return_value=False))
        await _do_send_audio(conn, b'denied', {'sentence_id':'s'})
        await send_tts_message(conn, 'start', sentence_id='s')
        await send_tts_message(conn, 'sentence_start', 'hidden', sentence_id='s')
        self.assertEqual(conn.websocket.send.await_count, 0)
        await send_tts_message(conn, 'stop', sentence_id='s')
        self.assertEqual(json.loads(conn.websocket.send.await_args.args[0])['state'], 'stop')
        conn.websocket.send.reset_mock(); conn.esp32_gateway = None
        await _do_send_audio(conn, b'legacy', {'sentence_id':'s'})
        conn.websocket.send.assert_awaited_once_with(b'legacy')

    async def test_turn_switch_during_authorization_blocks_queued_packet(self):
        conn = self.conn(); runtime = GatewayRuntime(conn, self.config()); await runtime.start()
        conn.sentence_id = 'old'; runtime.current_turn = 'old-turn'; runtime.playback_authority = ('old', 'old-turn', 'run-1', 1)
        entered, release = asyncio.Event(), asyncio.Event()
        async def delayed(*args, **kwargs):
            entered.set(); await release.wait()
            return {'status':'completed', 'runId':'run-1'}
        with patch.object(runtime.client, 'session', side_effect=delayed):
            check = asyncio.create_task(runtime.authorize_playback('old'))
            await entered.wait(); runtime.current_turn = 'new-turn'; release.set()
            self.assertFalse(await check)
        await runtime.close()

    async def test_abort_stops_late_reply(self):
        conn = self.conn()
        runtime = GatewayRuntime(conn, self.config())
        await runtime.start()
        self.addAsyncCleanup(runtime.close)
        reply_requested = asyncio.Event()

        async def session(operation, fence, **data):
            if operation == 'input':
                return {'id': 'a' * 32, 'control_version': 1}
            if operation == 'reply':
                reply_requested.set()
                await asyncio.Future()
            return {}

        with patch.object(runtime.client, 'session', side_effect=session) as api:
            runtime.submit('question')
            await asyncio.wait_for(reply_requested.wait(), 2)
            runtime.abort()
            await asyncio.wait_for(runtime.abort_task, 2)
            self.assertTrue(runtime.turn_task.cancelled())
            self.assertEqual(conn.tts.tts_text_queue.qsize(), 0)
            api.assert_any_await('abort-turn', runtime.fence, sessionId=runtime.session_id,
                                 turnId='a' * 32, controlVersion=1)
