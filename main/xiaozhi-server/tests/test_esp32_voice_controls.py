import asyncio
import json
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, Mock

from esp32_gateway.voice_controls import classify
from esp32_gateway.runtime import GatewayRuntime
from esp32_gateway.client import GatewayError


class ClassifierTests(unittest.TestCase):
    def test_exit_wins_only_in_complete_control_sequences(self):
        for value in ('停止，暂停，退下', '停止暂停退下', '暂停，退一下吧', '退下了，暂停',
                      '好了，退下了', '大冷总管，退一下吧', '停止，退出'):
            with self.subTest(value=value):
                self.assertEqual(classify(value), 'exit')
        self.assertEqual(classify('停止，暂停'), 'stop')
        for value in ('暂停，不要退下', '停止退出', '暂停退出', '暂停后为什么不能退下', '请说停止暂停退下'):
            with self.subTest(value=value):
                self.assertIsNone(classify(value))

    def test_addressed_repeated_and_misrecognized_exit_words(self):
        for value in ('再见吧再见吧。', '推下吧。', '你好，中管，退下吧！'):
            with self.subTest(value=value):
                self.assertEqual(classify(value), 'exit')
        for value in ('然后我说推下吧，他没有反应。', '大冷总管，不要退出。', '请说再见吧再见吧。'):
            with self.subTest(value=value):
                self.assertIsNone(classify(value))

    def test_exit_and_repeated_polite_commands(self):
        for value in ['退出','退出、退出、再见！','再见再见，退出退下。','再见','没事了，退下吧','结束对话吧','好的，再见，谢谢',
                      '好的，知道了，再见。','再见呀。','大冷总管，嗯，我知道了，退下吧。']:
            with self.subTest(value=value): self.assertEqual(classify(value),'exit')

    def test_stop_without_matching_quoted_or_negative_language(self):
        for value in ['停止播放','别说了','别别说了。','请停止播报','暂停一下','打断一下','停止回答']:
            with self.subTest(value=value):self.assertEqual(classify(value),'stop')
        for value in ['不要退出','再见是什么意思','教我如何退出','退出账号会怎么样','停止播放这个词怎么读','请查询停机报警',
                      '好的，知道了','好的，知道了，不要退出','请告诉我再见是什么意思','']:
            with self.subTest(value=value):self.assertIsNone(classify(value))


class RuntimeTests(unittest.IsolatedAsyncioTestCase):
    def runtime(self):
        conn=SimpleNamespace(headers={'device-id':'aa:bb:cc:dd:ee:ff'},websocket=SimpleNamespace(send=AsyncMock(),close=AsyncMock()),close=AsyncMock(),
            session_id='physical-ws',clear_queues=Mock(),client_abort=False,config={'wakeup_words':['大冷总管']})
        runtime=GatewayRuntime(conn,{'gateway_id':'test','execution_mode':'ai_worker'})
        runtime.active=True;runtime.client=SimpleNamespace(session=AsyncMock(),fence=AsyncMock())
        runtime.fence={};runtime.session_id='session';runtime.current_turn='a'*32;runtime.current_control_version=1
        return runtime,conn

    async def test_exit_cancels_and_closes_without_creating_ai_input(self):
        runtime,conn=self.runtime();runtime.submit('停止，暂停，退一下吧。')
        await runtime.turn_task
        self.assertEqual(json.loads(conn.websocket.send.call_args.args[0])['action'],'exit')
        conn.close.assert_awaited_once()
        self.assertEqual([call.args[0] for call in runtime.client.session.await_args_list],['abort-turn'])
        conn.clear_queues.assert_called()
        await runtime.close()

    async def test_stop_keeps_socket_and_late_exit_cannot_close_new_input(self):
        runtime,conn=self.runtime();runtime.submit('别说了');await runtime.turn_task
        self.assertEqual(json.loads(conn.websocket.send.call_args.args[0])['action'],'stop')
        conn.close.assert_not_awaited()
        generation=runtime.input_generation;runtime.input_generation+=1
        await runtime._voice_control('exit',generation);conn.close.assert_not_awaited()
        await runtime.close()

    async def test_exit_after_stop_closes_without_a_new_ai_turn(self):
        runtime, conn = self.runtime()
        runtime.submit('暂停播放')
        await runtime.turn_task
        conn.close.assert_not_awaited()
        runtime.submit('退一下吧')
        await runtime.turn_task
        actions = [json.loads(call.args[0])['action'] for call in conn.websocket.send.await_args_list]
        self.assertEqual(actions, ['stop', 'exit'])
        self.assertEqual([call.args[0] for call in runtime.client.session.await_args_list], ['abort-turn'])
        conn.close.assert_awaited_once()
        await runtime.close()

    async def test_wake_word_does_not_create_unwanted_model_greeting(self):
        runtime,conn=self.runtime();runtime.submit('大冷总管！')
        if runtime.abort_task:await runtime.abort_task
        self.assertIsNone(runtime.turn_task)
        self.assertEqual([c.args[0] for c in runtime.client.session.await_args_list],['abort-turn'])
        await runtime.close()

    async def test_exit_cleanup_can_run_inside_its_own_runtime_task(self):
        runtime,conn=self.runtime();conn.close=AsyncMock(side_effect=runtime.close)
        runtime.submit('再见');task=runtime.turn_task
        await asyncio.wait_for(task,1)
        self.assertTrue(runtime.closed)

    async def test_configured_exit_dispatches_on_the_bound_session(self):
        runtime, conn = self.runtime()
        runtime.client.session.return_value = {'intent_action': 'exit'}
        runtime.submit('先到这里')
        await runtime.turn_task
        self.assertEqual(json.loads(conn.websocket.send.call_args.args[0])['action'], 'exit')
        conn.close.assert_awaited_once()
        self.assertEqual([call.args[0] for call in runtime.client.session.await_args_list], ['input'])
        await runtime.close()

    async def test_late_playback_check_cannot_close_a_new_turn(self):
        runtime, conn = self.runtime()
        conn.sentence_id = 'sentence'
        runtime.playback_authority = ('sentence', runtime.current_turn, 'reply', 1)
        runtime.client.session.return_value = {'status': 'completed', 'replyId': 'reply'}
        self.assertTrue(await runtime.authorize_playback('sentence'))

        async def superseded(*args, **kwargs):
            runtime._clear_playback()
            raise GatewayError(409, 'TURN_SUPERSEDED')

        runtime.client.session.side_effect = superseded
        self.assertFalse(await runtime.authorize_playback('sentence'))
        await asyncio.sleep(0)
        conn.websocket.close.assert_not_awaited()
        await runtime.close()
