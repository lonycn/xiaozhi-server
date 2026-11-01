"""Structured function calls with a real SDK wire and bounded, authority-fenced loop."""
import asyncio
import copy
import json
from contextlib import asynccontextmanager
from types import SimpleNamespace as NS
import unittest

import httpx
from openai import AsyncOpenAI
from openai.types.chat.chat_completion_chunk import ChoiceDeltaToolCall
from ai_worker.provider import Bailian, ProviderFailure
from ai_worker.transport import Manager, LeaseLost
from ai_worker.tools import Proposals, policy, strict_arguments, result_messages
from ai_worker.worker import Worker
from test_ai_worker import config, task, chunk, FakeStream, FakeManager


TOOLS = [{'type': 'function', 'function': {'name': 'get_device', 'description': 'Authorized lookup',
          'parameters': {'type': 'object', 'properties': {'id': {'type': 'string'}}, 'required': ['id']}}}]


def tool_task(**kwargs):
    return task(tools=copy.deepcopy(TOOLS), attempt=1, control_epoch='2', **kwargs)


def fragment(index=0, call_id=None, name=None, arguments=None):
    return NS(index=index, id=call_id, type='function', function=NS(name=name, arguments=arguments))


def tool_chunk(*fragments, finish=None, text='', usage=None):
    return NS(choices=[NS(index=0, delta=NS(content=text, tool_calls=list(fragments)),
                          finish_reason=finish)], usage=usage)


def usage(a=2, b=3):
    return NS(prompt_tokens=a, completion_tokens=b, total_tokens=a+b)


def call_stream(call_id='call_1', raw='{"id":"42"}', reported=True):
    return FakeStream([tool_chunk(fragment(call_id=call_id, name='get_device', arguments=raw)),
                       tool_chunk(finish='tool_calls', usage=usage() if reported else None)])


class RoundProvider:
    def __init__(self, *streams):
        self.streams, self.inputs = list(streams), []

    @asynccontextmanager
    async def stream(self, current):
        self.inputs.append(copy.deepcopy(current))
        output = self.streams.pop(0)
        if isinstance(output, Exception):
            raise output
        try:
            yield output
        finally:
            await output.close()


class ToolManager(FakeManager):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.batches = []
        self.entered, self.release, self.canceled = asyncio.Event(), asyncio.Event(), asyncio.Event()
        self.block = False
        self.result_status = 'succeeded'

    async def tools(self, task, round_number, calls, maximum):
        self.batches.append(copy.deepcopy((round_number, calls)))
        self.entered.set()
        if self.block:
            try:
                await self.release.wait()
            except asyncio.CancelledError:
                self.canceled.set()
                raise
        return {'results': [dict(provider_call_id=call['provider_call_id'], name=call['name'],
                status=self.result_status, output={'source': 'test', 'observed_at': '2026-11-01'},
                error_code='NOT_CONFIGURED' if self.result_status == 'failed' else None) for call in reversed(calls)],
                'remaining_calls': 8-len(calls)}


class MergeTests(unittest.TestCase):
    def test_qwen_sdk_empty_continuation_id_preserves_first_call_identity(self):
        names, limits = policy(tool_task())
        proposals = Proposals(names, limits)
        for data in (
            dict(index=0,id='call_real_qwen',type='function',function=dict(name='get_device',arguments='')),
            dict(index=0,id='',type='function',function=dict(name=None,arguments='')),
            dict(index=0,id='',type='function',function=dict(name=None,arguments='{"id":"42"}')),
        ):
            proposals.add([ChoiceDeltaToolCall(**data)])
        self.assertEqual(proposals.complete(set()),[dict(provider_call_id='call_real_qwen',name='get_device',arguments={'id':'42'})])
        missing = Proposals(names, limits)
        missing.add([fragment(0, '', 'get_device', '{}')])
        with self.assertRaises(ProviderFailure): missing.complete(set())

    def test_interleaved_indices_name_arguments_and_repeated_id(self):
        names, limits = policy(tool_task())
        proposals = Proposals(names, limits)
        proposals.add([fragment(1, 'b', 'get_', '{"id":'), fragment(0, 'a', 'get_device', '{')])
        proposals.add([fragment(0, 'a', 'get_device', '"id":"1"}'), fragment(1, 'b', 'device', '"2"}')])
        self.assertEqual(proposals.complete(set()), [dict(provider_call_id='a', name='get_device', arguments={'id': '1'}),
                                                     dict(provider_call_id='b', name='get_device', arguments={'id': '2'})])

    def test_strict_json_never_executes_partial_duplicate_or_nonobject(self):
        for raw in ('{"id":', '{"id":1,"id":2}', '{"id":NaN}', '[]', '{} trailing', '{"id":Infinity}', '{"id":"\\ud800"}'):
            with self.subTest(raw=raw), self.assertRaises(ProviderFailure):
                strict_arguments(raw)

    def test_conflicting_ids_duplicate_indices_unknown_names_and_replays(self):
        names, limits = policy(tool_task())
        for fragments, seen in (([fragment(0, 'a', 'get_device', '{}'), fragment(1, 'a', 'get_device', '{}')], set()),
                                ([fragment(1, 'a', 'get_device', '{}')], set()),
                                ([fragment(0, 'a', 'unregistered', '{}')], set()),
                                ([fragment(0, 'a', 'get_device', '{}')], {'a'}),
                                ([fragment(0, 'a', 'get_device', '{}'), fragment(0, 'b')], set()),
                                ([fragment(0, 'a', 'get_device', '{}'), fragment(0, arguments='{"id":2}')], set())):
            with self.subTest(fragments=fragments), self.assertRaises(ProviderFailure):
                proposals = Proposals(names, limits)
                proposals.add(fragments)
                proposals.complete(seen)

    def test_memory_bounds_and_unsupported_model(self):
        names, limits = policy(tool_task(tool_policy={'max_argument_bytes': 16}))
        with self.assertRaises(ProviderFailure):
            Proposals(names, limits).add([fragment(0, 'a', 'get_device', 'x' * 100)])
        with self.assertRaises(ProviderFailure) as error:
            policy(tool_task(model='qwen-vl-unknown'))
        self.assertEqual(error.exception.code, 'UNSUPPORTED_TOOL')

    def test_result_identity_shape_and_size_must_match(self):
        calls = [dict(provider_call_id='a', name='get_device', arguments={})]
        good = dict(provider_call_id='a', name='get_device', status='succeeded', output={}, error_code=None)
        for results in ([], [dict(good, provider_call_id='b')], [good, good], [dict(good, name='other')],
                        [dict(good, output='untrusted')], [dict(good, status='pending')]):
            with self.subTest(results=results), self.assertRaises(ProviderFailure):
                result_messages({'results': results}, calls, 4096)
        with self.assertRaises(ProviderFailure):
            result_messages({'results': [dict(good, output={'text': 'x' * 2048})]}, calls, 1024)


class LoopTests(unittest.IsolatedAsyncioTestCase):
    async def test_plain_wire_unchanged_and_unsolicited_tools_rejected(self):
        manager = ToolManager()
        provider = RoundProvider(FakeStream([chunk('plain', 'stop')]))
        await Worker(config(), manager, provider).execute(task())
        self.assertNotIn('tools', provider.inputs[0])
        self.assertEqual(set(manager.events[-1][1]), {'last_sequence', 'finish_reason', 'usage', 'provider_request_id'})
        for delta in (NS(content=None, tool_calls=[fragment(0, 'a', 'get_device', '{}')]),
                      NS(content=None, function_call=NS(name='get_device', arguments='{}'))):
            manager = ToolManager()
            stream = FakeStream([NS(choices=[NS(delta=delta, finish_reason='stop')], usage=None)])
            await Worker(config(), manager, RoundProvider(stream)).execute(task())
            self.assertEqual(manager.events[-1], ('fail', {'error_code': 'UNSUPPORTED_TOOL'}))
            self.assertEqual(manager.batches, [])

    async def test_two_rounds_proper_tool_context_and_real_usage(self):
        manager = ToolManager()
        provider = RoundProvider(call_stream(), FakeStream([chunk('Verified response', 'stop', usage(5, 7))]))
        await Worker(config(), manager, provider).execute(tool_task())
        self.assertEqual(len(manager.batches), 1)
        messages = provider.inputs[1]['messages']
        self.assertEqual(messages[-2]['role'], 'assistant')
        self.assertEqual(messages[-2]['tool_calls'][0]['id'], 'call_1')
        self.assertEqual(json.loads(messages[-2]['tool_calls'][0]['function']['arguments']), {'id': '42'})
        self.assertEqual(messages[-1]['role'], 'tool')
        self.assertEqual(messages[-1]['tool_call_id'], 'call_1')
        self.assertEqual(json.loads(messages[-1]['content'])['output']['source'], 'test')
        complete = manager.events[-1][1]
        self.assertEqual(complete['usage'], {'input_tokens': 7, 'output_tokens': 10, 'total_tokens': 17})
        self.assertEqual([record['round'] for record in complete['provider_requests']], [1, 2])
        self.assertEqual(complete['tool_count'], 1)
        self.assertEqual([action for action, _ in manager.events], ['heartbeat', 'delta', 'complete'])

    async def test_missing_usage_is_unknown_and_business_failure_still_uses_tool_role(self):
        manager = ToolManager()
        manager.result_status = 'failed'
        provider = RoundProvider(call_stream(reported=False), FakeStream([chunk('Not configured', 'stop', usage())]))
        await Worker(config(), manager, provider).execute(tool_task())
        self.assertIsNone(manager.events[-1][1]['usage'])
        self.assertEqual(json.loads(provider.inputs[1]['messages'][-1]['content'])['error_code'], 'NOT_CONFIGURED')

    async def test_provider_failure_preserves_previous_actual_usage_and_closes(self):
        first = call_stream()
        manager = ToolManager()
        await Worker(config(), manager, RoundProvider(first, ProviderFailure('PROVIDER_RATE_LIMIT'))).execute(tool_task())
        action, body = manager.events[-1]
        self.assertEqual((action, body['error_code']), ('fail', 'PROVIDER_RATE_LIMIT'))
        self.assertEqual(body['provider_requests'][0]['usage']['total_tokens'], 5)
        self.assertIsNone(body['usage'])
        self.assertTrue(first.closed)

    async def test_malformed_and_unsupported_proposals_never_dispatch(self):
        for stream in (call_stream(raw='{"id":'), FakeStream([tool_chunk(fragment(call_id='x', name='get_device', arguments='{}'), finish='stop')]),
                       FakeStream([tool_chunk(finish='tool_calls')])):
            manager = ToolManager()
            await Worker(config(), manager, RoundProvider(stream)).execute(tool_task())
            self.assertEqual(manager.events[-1][0], 'fail')
            self.assertEqual(manager.batches, [])

    async def test_round_budget_and_repeated_ids_stop(self):
        for second, expected in ((call_stream('call_2'), 'TOOL_LIMIT_EXCEEDED'), (call_stream('call_1'), 'TOOL_PROTOCOL_ERROR')):
            manager = ToolManager()
            await Worker(config(), manager, RoundProvider(call_stream(), second)).execute(tool_task(tool_policy={'max_rounds': 1}))
            self.assertEqual(manager.events[-1][1]['error_code'], expected)
            self.assertEqual(len(manager.batches), 1)

    async def test_wait_is_not_completion_and_cancel_stops_result_resume(self):
        manager = ToolManager()
        manager.block = True
        provider = RoundProvider(call_stream(), FakeStream([chunk('must not publish', 'stop')]))
        settings = config()
        settings.heartbeat_seconds = .01
        executing = asyncio.create_task(Worker(settings, manager, provider).execute(tool_task()))
        await asyncio.wait_for(manager.entered.wait(), 1)
        self.assertFalse(any(action in ('complete', 'fail') for action, _ in manager.events))
        manager.active = False  # models cancellation/new message/handoff/lease revocation authority
        with self.assertRaises(LeaseLost):
            await asyncio.wait_for(executing, 1)
        self.assertTrue(manager.canceled.is_set())
        self.assertEqual(len(provider.inputs), 1)
        self.assertFalse(any(action in ('complete', 'fail', 'delta') for action, _ in manager.events))

    async def test_post_tool_fence_and_output_budget(self):
        manager = ToolManager(heartbeat=False)
        provider = RoundProvider(call_stream(), FakeStream([chunk('late', 'stop')]))
        with self.assertRaises(LeaseLost):
            await Worker(config(), manager, provider).execute(tool_task())
        self.assertEqual(len(provider.inputs), 1)
        manager = ToolManager()
        await Worker(config(), manager, RoundProvider(FakeStream([chunk('excess', 'stop')]))).execute(
            tool_task(tool_policy={'max_output_chars': 2}))
        self.assertEqual(manager.events[-1][1]['error_code'], 'TOOL_LIMIT_EXCEEDED')
        self.assertFalse(any(action == 'delta' for action, _ in manager.events))

    async def test_call_budget_and_timeout_never_complete(self):
        manager = ToolManager()
        stream = FakeStream([tool_chunk(fragment(0, 'a', 'get_device', '{}'), fragment(1, 'b', 'get_device', '{}'), finish='tool_calls')])
        await Worker(config(), manager, RoundProvider(stream)).execute(tool_task(tool_policy={'max_calls': 1}))
        self.assertEqual(manager.events[-1][1]['error_code'], 'TOOL_LIMIT_EXCEEDED')
        self.assertEqual(manager.batches, [])
        manager = ToolManager()
        manager.block = True
        await Worker(config(), manager, RoundProvider(call_stream())).execute(tool_task(tool_policy={'timeout_seconds': 1}))
        self.assertTrue(manager.canceled.is_set())
        self.assertEqual(manager.events[-1][1]['error_code'], 'TOOL_TIMEOUT')
        self.assertEqual(manager.events[-1][1]['provider_requests'][0]['usage']['total_tokens'], 5)
        self.assertFalse(any(action == 'complete' for action, _ in manager.events))


class WireTests(unittest.IsolatedAsyncioTestCase):
    async def test_real_sdk_tools_pass_through_and_tool_messages(self):
        requests = []
        async def handle(request):
            requests.append(json.loads(request.content))
            text = ('data: {"id":"c","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_device","arguments":"{\\\"id\\\":\\\"42\\\"}"}}]},"finish_reason":null}]}\n\n'
                    'data: {"id":"c","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}\n\n') if len(requests) == 1 else (
                    'data: {"id":"c","choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}\n\n')
            return httpx.Response(200, headers={'content-type': 'text/event-stream', 'x-request-id': f'request-{len(requests)}'},
                                  content=text + 'data: [DONE]\n\n')
        client = AsyncOpenAI(api_key='test', base_url=config().base_url, max_retries=0,
            http_client=httpx.AsyncClient(transport=httpx.MockTransport(handle)))
        provider = Bailian(config(), client)
        manager = ToolManager()
        await Worker(config(), manager, provider).execute(tool_task())
        await provider.close()
        self.assertEqual(manager.events[-1][0], 'complete')
        self.assertEqual(requests[0]['tools'], TOOLS)
        self.assertEqual(requests[0]['tool_choice'], 'auto')
        self.assertEqual(requests[1]['messages'][-1]['tool_call_id'], 'call_1')
        self.assertEqual([r['provider_request_id'] for r in manager.events[-1][1]['provider_requests']], ['request-1', 'request-2'])

    async def test_manager_idempotent_retry_fixed_path_and_oversize(self):
        requests = []
        async def handle(request):
            requests.append(request)
            if len(requests) == 1:
                raise httpx.ReadTimeout('private')
            return httpx.Response(200, json={'code': 0, 'data': {'results': [], 'remaining_calls': 8}})
        async with httpx.AsyncClient(base_url='http://control/', headers={'X-AI-Worker-Key': 'test'},
                transport=httpx.MockTransport(handle)) as client:
            manager = Manager(config(), client)
            result = await manager.tools(tool_task(), 1, [], 1024)
        self.assertEqual(result['results'], [])
        self.assertEqual(requests[0].content, requests[1].content)
        self.assertEqual(requests[0].url.path, '/internal/ai-worker/runs/123/tools')
        body = json.loads(requests[0].content)
        self.assertEqual((body['attempt'], body['control_epoch'], body['round']), (1, 2, 1))
        self.assertEqual(requests[0].headers['X-AI-Worker-Key'], 'test')
        async with httpx.AsyncClient(base_url='http://control/', transport=httpx.MockTransport(
                lambda _: httpx.Response(200, content='x' * 4096))) as client:
            with self.assertRaises(ProviderFailure) as error:
                await Manager(config(), client).tools(tool_task(), 1, [], 1024)
        self.assertEqual(error.exception.code, 'TOOL_LIMIT_EXCEEDED')

    async def test_manager_revocation_no_retry_or_late_result(self):
        for status in (401, 403, 409, 410):
            requests = []
            def handle(request):
                requests.append(request)
                return httpx.Response(status, text='private')
            async with httpx.AsyncClient(base_url='http://control/', transport=httpx.MockTransport(handle)) as client:
                with self.assertRaises(LeaseLost):
                    await Manager(config(), client).tools(tool_task(), 1, [], 1024)
            self.assertEqual(len(requests), 1)


if __name__ == '__main__':
    unittest.main()
