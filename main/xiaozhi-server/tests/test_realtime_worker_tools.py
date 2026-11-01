"""Tools remain explicitly disabled; no real provider, business API, or database."""
import asyncio
import json
import unittest
from aiohttp import WSMsgType
from realtime_worker.config import MODEL
from realtime_worker.control import Fault
from realtime_worker.provider import Provider
from realtime_worker.tool_contract import capability, require_disabled, reject_tool_event
from test_realtime_worker_network import Harness, receive


PRIVATE = 'private-untrusted-tool-payload'
DECLARATION = {'type': 'function', 'function': {'name': 'get_device', 'parameters': {'type': 'object', 'properties': {}}}}


class CapabilityTests(unittest.TestCase):
    def test_documented_provider_support_never_claims_worker_execution(self):
        state = Provider.tool_capability()
        self.assertEqual(state, capability())
        self.assertEqual(state['model'], MODEL)
        self.assertEqual(state['provider_function_calling'], 'documented')
        self.assertEqual(state['worker_function_calling'], 'disabled')
        self.assertEqual(state['error_code'], 'UNSUPPORTED_TOOL')
        state['worker_function_calling'] = 'enabled'
        self.assertEqual(capability()['worker_function_calling'], 'disabled')

    def test_default_manual_and_vad_wire_unchanged_empty_tools_only(self):
        for mode in ('manual', 'server_vad'):
            original = Provider.session_update(mode)
            self.assertEqual(original, Provider.session_update(mode, tools=[]))
            self.assertEqual(original['session']['tools'], [])
            self.assertFalse(original['session']['enable_search'])
            self.assertEqual(original['session']['audio']['input']['format']['sample_rate'], 16000)
            self.assertEqual(original['session']['audio']['output']['format']['sample_rate'], 24000)
            for tools in ([DECLARATION], {}, False, 'get_device', ()):  # No implicit enable by malformed declarations.
                with self.subTest(mode=mode, tools=tools), self.assertRaises(Fault) as error:
                    Provider.session_update(mode, tools=tools)
                self.assertEqual(error.exception.code, 'UNSUPPORTED_TOOL')
        require_disabled(None)

    def test_every_native_tool_envelope_rejected_without_argument_parsing(self):
        events = [
            {'type': 'response.function_call_arguments.delta', 'delta': PRIVATE},
            {'type': 'response.function_call_arguments.done', 'arguments': PRIVATE},
            {'type': 'response.mcp_call_arguments.delta', 'delta': PRIVATE},
            {'type': 'session.created', 'session': {'tools': [DECLARATION]}},
            {'type': 'session.updated', 'session': {'tools': {}}},
        ]
        for kind in ('function_call', 'function_call_output', 'mcp_call', 'mcp_list_tools', 'mcp_approval_request'):
            for envelope in ('conversation.item.created', 'response.output_item.added', 'response.output_item.done'):
                events.append({'type': envelope, 'item': {'type': kind, 'arguments': PRIVATE}})
            events.append({'type': 'response.done', 'response': {'output': [{'type': kind, 'output': PRIVATE}]}})
        for event in events:
            with self.subTest(event=event), self.assertRaises(Fault) as error:
                reject_tool_event(event)
            self.assertEqual(str(error.exception), 'UNSUPPORTED_TOOL')
            self.assertNotIn(PRIVATE, str(error.exception))

    def test_audio_text_usage_and_empty_session_metadata_are_not_tool_events(self):
        for event in ({'type': 'response.audio.delta', 'delta': 'AA=='},
                      {'type': 'response.text.delta', 'delta': 'function_call is data in a sentence'},
                      {'type': 'response.done', 'response': {'status': 'completed', 'usage': {'total_tokens': 3},
                                                             'output': [{'type': 'message', 'content': []}]}},
                      {'type': 'session.updated', 'session': {'tools': []}},
                      {'type': 'conversation.item.created', 'item': {'type': 'message', 'role': 'user'}}):
            reject_tool_event(event)


class DisabledToolsNetworkTests(unittest.IsolatedAsyncioTestCase):
    async def harness(self):
        harness = await Harness().start()
        self.addAsyncCleanup(harness.close)
        return harness

    async def test_claimed_tools_fail_explicitly_before_provider_connection(self):
        h = await self.harness()
        h.task['tools'] = [DECLARATION]
        ws = await h.connect(ready=False)
        error = await receive(ws, 'error')
        self.assertEqual(error['code'], 'UNSUPPORTED_TOOL')
        self.assertTrue(error['clear_audio'])
        self.assertFalse(hasattr(h, 'asserted_provider_url'))
        self.assertEqual([event['type'] for event in h.events], ['session.error'])
        self.assertNotIn(PRIVATE, json.dumps(h.events))

    async def test_unsolicited_tool_result_echo_and_metadata_never_complete(self):
        for kind, payload in (
            ('conversation.item.created', {'item': {'id': 'untrusted-tool', 'type': 'function_call_output', 'output': PRIVATE}}),
            ('response.output_item.done', {'item': {'id': 'untrusted-tool', 'type': 'mcp_list_tools', 'tools': PRIVATE}}),
            ('response.done', {'response': {'id': 'untrusted-response', 'status': 'completed', 'output': [
                {'type': 'function_call', 'call_id': 'untrusted-call', 'name': 'get_device', 'arguments': PRIVATE}]}}),
            ('session.updated', {'session': {'id': 'provider-session', 'tools': [DECLARATION]}}),
        ):
            h = await self.harness()
            ws = await h.connect()
            await h.emit(next(iter(h.provider_clients)), kind, **payload)
            error = await receive(ws, 'error')
            self.assertEqual(error['code'], 'UNSUPPORTED_TOOL')
            self.assertFalse(any(event['type'] in ('response.completed', 'response.canceled') for event in h.events))
            self.assertFalse(any(event['type'] == 'conversation.item.create' and event['item'].get('type') == 'function_call_output'
                                 for event in h.provider_sent))
            self.assertNotIn(PRIVATE, json.dumps(h.events))
            self.assertNotIn(PRIVATE, json.dumps(error))

    async def test_authority_loss_wins_before_tool_rejection_callback(self):
        h = await self.harness()
        ws = await h.connect()
        h.active = False
        await h.emit(next(iter(h.provider_clients)), 'response.function_call_arguments.done',
                     response_id='old-response', call_id='old-call', arguments=PRIVATE)
        frame = await asyncio.wait_for(ws.receive(), 2)
        self.assertIn(frame.type, (WSMsgType.CLOSE, WSMsgType.CLOSED, WSMsgType.CLOSING))
        self.assertFalse(any(event['type'] == 'session.error' for event in h.events))
        self.assertNotIn(PRIVATE, json.dumps(h.events))


if __name__ == '__main__':
    unittest.main()
