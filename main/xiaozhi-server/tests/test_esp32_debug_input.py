import asyncio
import json
import time
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch
from esp32_gateway.runtime import GatewayRuntime, TEXT_INPUT
from esp32_gateway.client import GatewayError
import core.handle.receiveAudioHandle as receive


class DeviceTextInputTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.conn = SimpleNamespace(config={}, headers={'device-id': 'aa:bb:cc:dd:ee:ff'}, need_bind=False, max_output_size=0,
            client_is_speaking=False, client_listen_mode='auto', current_speaker=None,
            websocket=SimpleNamespace(send=AsyncMock(), close=AsyncMock()), mcp_client=None,
            client_abort=False)
        self.runtime = GatewayRuntime(self.conn, {'gateway_id': 'gateway', 'execution_mode': 'ai_worker'})
        self.conn.esp32_gateway = self.runtime
        self.runtime.active = True
        self.runtime.session_id = 'session'
        self.runtime.fence = {'deviceId': 'device', 'connectionId': 'connection', 'generation': 1, 'gatewayId': 'gateway', 'bindingVersion': 'binding'}
        self.runtime.client = SimpleNamespace(fence=AsyncMock(), post=AsyncMock())
        self.runtime._turn = AsyncMock()
        self.stt = patch.object(receive, 'send_stt_message', AsyncMock())
        self.stt_mock = self.stt.start()
        self.addCleanup(self.stt.stop)
        self.addAsyncCleanup(self.runtime.close)

    def command(self, args):
        return {'id': 'command', 'tool': TEXT_INPUT, 'arguments': json.dumps(args), 'deadline': str(int(time.time()*1000)+30000)}

    async def test_debug_and_recognized_speech_use_the_same_asr_handoff(self):
        await self.runtime._command(self.command({'text': '<测试>\n文字', 'session_id': 'session'}))
        await asyncio.sleep(0)
        self.stt_mock.assert_awaited_once_with(self.conn, '<测试>\n文字')
        self.runtime._turn.assert_awaited_once_with('<测试>\n文字')
        result = self.runtime.client.post.call_args.args[1]
        self.assertEqual(result['status'], 'succeeded')
        self.assertEqual(result['result'], {'accepted': True})
        self.assertNotIn('文字', json.dumps(result))
        self.runtime._turn.reset_mock()
        await receive.startToChat(self.conn, '语音识别结果')
        await asyncio.sleep(0)
        self.runtime._turn.assert_awaited_once_with('语音识别结果')

    async def test_wrong_session_invalid_text_and_extra_fields_never_reach_asr(self):
        for args in ({'text': 'hello', 'session_id': 'old'}, {'text': '', 'session_id': 'session'},
                     {'text': 'x'*2001, 'session_id': 'session'}, {'text': 'hello', 'session_id': 'session', 'url': 'http://untrusted'}):
            await self.runtime._command(self.command(args))
            self.assertEqual(self.runtime.client.post.call_args.args[1]['status'], 'failed')
        self.stt_mock.assert_not_awaited()
        self.runtime._turn.assert_not_awaited()

    async def test_limits_and_binding_rejections_are_not_reported_as_accepted(self):
        self.conn.need_bind = True
        with patch.object(receive, 'check_bind_device', AsyncMock()):
            await self.runtime._command(self.command({'text': 'hello', 'session_id': 'session'}))
        self.assertEqual(self.runtime.client.post.call_args.args[1]['status'], 'failed')
        self.runtime._turn.assert_not_awaited()

    async def test_stale_connection_is_rechecked_before_the_asr_handoff(self):
        self.runtime.client.fence.side_effect = GatewayError(409)
        with self.assertRaises(GatewayError):
            await self.runtime._command(self.command({'text': 'hello', 'session_id': 'session'}))
        self.stt_mock.assert_not_awaited()

    async def test_stop_keeps_the_existing_voice_control_behavior(self):
        self.runtime._voice_control = AsyncMock()
        await self.runtime._command(self.command({'text': '停止', 'session_id': 'session'}))
        await asyncio.sleep(0)
        self.runtime._voice_control.assert_awaited_once_with('stop', 1)
        self.runtime._turn.assert_not_awaited()
