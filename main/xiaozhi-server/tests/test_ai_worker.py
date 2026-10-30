"""Run with unittest to avoid device tests/conftest.py and its global SDK mocks."""
import asyncio
from contextlib import asynccontextmanager
import json
from types import SimpleNamespace as NS
import unittest

import httpx
from openai import AsyncOpenAI
from ai_worker.config import Config
from ai_worker.provider import Bailian, ProviderFailure
from ai_worker.transport import Manager, LeaseLost, ControlError
from ai_worker.worker import Worker, actual_usage


def config(**overrides):
    data = dict(manager_url='http://127.0.0.1:18893/xiaozhi', worker_key='test-worker-secret',
                worker_id='test', base_url='https://dashscope.aliyuncs.com/compatible-mode/v1',
                api_key='test-provider-secret', model='qwen-flash')
    data.update(overrides)
    return Config(**data)


def task(**overrides):
    data = dict(run_id='123', model='qwen-flash', lease_token='lease', lease_seconds=45,
                messages=[{'role': 'user', 'content': 'private test prompt'}])
    data.update(overrides)
    return data


def chunk(text='', finish=None, usage=None):
    return NS(choices=[NS(delta=NS(content=text), finish_reason=finish)], usage=usage)


class FakeStream:
    def __init__(self, chunks=(), block=False):
        self.chunks = iter(chunks)
        self.block = block
        self.closed = False
        self.response = NS(headers={'x-request-id': 'request-1'})

    def __aiter__(self):
        return self

    async def __anext__(self):
        try:
            return next(self.chunks)
        except StopIteration:
            if self.block:
                await asyncio.Event().wait()
            raise StopAsyncIteration

    async def close(self):
        self.closed = True


class FakeProvider:
    def __init__(self, stream=None, failure=None, before_first=False):
        self.output = stream or FakeStream()
        self.failure, self.before_first = failure, before_first
        self.canceled = False

    @asynccontextmanager
    async def stream(self, task):
        try:
            if self.before_first:
                await asyncio.Event().wait()
            if self.failure:
                raise ProviderFailure(self.failure)
            yield self.output
        except asyncio.CancelledError:
            self.canceled = True
            raise
        finally:
            await self.output.close()


class FakeManager:
    def __init__(self, heartbeat=True, reject_delta=False):
        self.events = []
        self.active = heartbeat
        self.reject_delta = reject_delta

    async def callback(self, task, action, **body):
        self.events.append((action, body))
        if action == 'heartbeat':
            if isinstance(self.active, Exception):
                raise self.active
            return {'active': self.active}
        if self.reject_delta and action == 'delta':
            raise LeaseLost()
        return {}


class WorkerTests(unittest.IsolatedAsyncioTestCase):
    async def test_serial_delta_usage_complete_and_close(self):
        stream = FakeStream([chunk('a' * 5000), chunk('tail'),
                             chunk(finish='stop', usage=NS(prompt_tokens=4, completion_tokens=8, total_tokens=12))])
        manager = FakeManager()
        await Worker(config(), manager, FakeProvider(stream)).execute(task())
        deltas = [body for action, body in manager.events if action == 'delta']
        self.assertEqual([d['sequence'] for d in deltas], [1, 2, 3])
        self.assertEqual(''.join(d['text'] for d in deltas), 'a' * 5000 + 'tail')
        action, complete = manager.events[-1]
        self.assertEqual(action, 'complete')
        self.assertEqual(complete['last_sequence'], 3)
        self.assertEqual(complete['usage'], dict(input_tokens=4, output_tokens=8, total_tokens=12))
        self.assertEqual(complete['provider_request_id'], 'request-1')
        self.assertTrue(stream.closed)

    async def test_absent_usage_is_null(self):
        manager = FakeManager()
        await Worker(config(), manager, FakeProvider(FakeStream([chunk('answer', 'stop')]))).execute(task())
        self.assertIsNone(manager.events[-1][1]['usage'])
        self.assertIsNone(actual_usage(NS(prompt_tokens=True, completion_tokens=1, total_tokens=2)))
        self.assertIsNone(actual_usage(NS(prompt_tokens=-1, completion_tokens=1, total_tokens=0)))

    async def test_heartbeat_revokes_before_first_token_and_during_open(self):
        for before_first in (False, True):
            for failure in (False, LeaseLost(), ControlError()):
                with self.subTest(before_first=before_first, failure=type(failure).__name__):
                    manager = FakeManager(heartbeat=failure)
                    provider = FakeProvider(FakeStream(block=True), before_first=before_first)
                    cfg = NS(model='qwen-flash', heartbeat_seconds=.01)
                    with self.assertRaises(LeaseLost):
                        await asyncio.wait_for(Worker(cfg, manager, provider).execute(task()), 1)
                    self.assertTrue(provider.canceled)
                    self.assertTrue(provider.output.closed)
                    self.assertFalse(any(action in ('complete', 'fail') for action, _ in manager.events))

    async def test_409_delta_closes_stream_and_does_not_complete(self):
        provider = FakeProvider(FakeStream([chunk('answer')], block=True))
        manager = FakeManager(reject_delta=True)
        with self.assertRaises(LeaseLost):
            await Worker(config(), manager, provider).execute(task())
        self.assertTrue(provider.output.closed)
        self.assertEqual([action for action, _ in manager.events], ['delta'])

    async def test_shutdown_cleans_both_tasks(self):
        provider = FakeProvider(FakeStream(block=True))
        executing = asyncio.create_task(Worker(config(), FakeManager(), provider).execute(task()))
        await asyncio.sleep(.01)
        executing.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await executing
        self.assertTrue(provider.output.closed)
        self.assertEqual(len([t for t in asyncio.all_tasks() if t is not asyncio.current_task() and not t.done()]), 0)

    async def test_model_mismatch_never_calls_provider(self):
        manager = FakeManager()
        provider = FakeProvider(before_first=True)
        await Worker(config(), manager, provider).execute(task(model='untrusted-model'))
        self.assertEqual(manager.events, [('fail', {'error_code': 'MODEL_MISMATCH'})])
        self.assertFalse(provider.output.closed)

    async def test_incomplete_stream_fails_without_completion(self):
        manager = FakeManager()
        await Worker(config(), manager, FakeProvider(FakeStream([chunk('partial')]))).execute(task())
        self.assertEqual(manager.events[-1], ('fail', {'error_code': 'PROVIDER_ERROR'}))


class TransportTests(unittest.IsolatedAsyncioTestCase):
    async def test_lost_delta_ack_retries_identical_body_sequence_and_private_header(self):
        requests = []
        async def handle(request):
            requests.append(request)
            if len(requests) == 1:
                raise httpx.ReadTimeout('sensitive exception text', request=request)
            return httpx.Response(200, json={'code': 0, 'data': {'ack': True}})
        async with httpx.AsyncClient(base_url='http://control/xiaozhi/',
                                    headers={'X-AI-Worker-Key': 'secret'},
                                    transport=httpx.MockTransport(handle)) as client:
            manager = Manager(config(), client)
            await manager.callback(task(), 'delta', sequence=1, text='hello')
        self.assertEqual(len(requests), 2)
        self.assertEqual(requests[0].content, requests[1].content)
        self.assertEqual(json.loads(requests[0].content), {'lease_token': 'lease', 'sequence': 1, 'text': 'hello'})
        self.assertEqual(requests[0].headers['X-AI-Worker-Key'], 'secret')
        self.assertEqual(requests[0].url.path, '/xiaozhi/internal/ai-worker/runs/123/delta')

    async def test_409_401_and_403_never_retry(self):
        for status in (409, 401, 403):
            count = 0
            def handle(request):
                nonlocal count
                count += 1
                return httpx.Response(status, text='private response')
            async with httpx.AsyncClient(base_url='http://control/', transport=httpx.MockTransport(handle)) as client:
                with self.assertRaises(LeaseLost) as caught:
                    await Manager(config(), client).callback(task(), 'delta', sequence=1, text='x')
                self.assertEqual(str(caught.exception), '')
                self.assertEqual(count, 1)

    async def test_claim_no_job_and_heartbeat_network_failure_not_retried(self):
        count = 0
        def handle(request):
            nonlocal count
            count += 1
            if request.url.path.endswith('/claim'):
                return httpx.Response(200, json={'code': 0, 'data': None})
            raise httpx.ConnectError('private secret')
        async with httpx.AsyncClient(base_url='http://control/', transport=httpx.MockTransport(handle)) as client:
            manager = Manager(config(), client)
            self.assertIsNone(await manager.claim())
            with self.assertRaises(ControlError) as caught:
                await manager.callback(task(), 'heartbeat')
            self.assertEqual(str(caught.exception), '')
            self.assertEqual(count, 2)


class ProviderTests(unittest.IsolatedAsyncioTestCase):
    async def test_real_sdk_wire_workspace_usage_and_thinking(self):
        captured = []
        async def handle(request):
            captured.append(request)
            return httpx.Response(200, headers={'content-type': 'text/event-stream'}, content=(
                'data: {"id":"c","choices":[{"index":0,"delta":{"content":"hello"},"finish_reason":null}]}\n\n'
                'data: {"id":"c","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}\n\n'
                'data: {"id":"c","choices":[],"usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5}}\n\n'
                'data: [DONE]\n\n'))
        provider = Bailian(config(workspace_id='workspace-test'))
        await provider.client.close()
        provider.client = AsyncOpenAI(api_key='test', base_url=config().base_url, max_retries=0,
            default_headers={'X-DashScope-WorkSpace': 'workspace-test'},
            http_client=httpx.AsyncClient(transport=httpx.MockTransport(handle)))
        async with provider.stream(task()) as stream:
            chunks = [c async for c in stream]
        await provider.close()
        body = json.loads(captured[0].content)
        self.assertEqual(body['stream_options'], {'include_usage': True})
        self.assertEqual(body['enable_thinking'], False)
        self.assertEqual(body['model'], 'qwen-flash')
        self.assertEqual(captured[0].headers['X-DashScope-WorkSpace'], 'workspace-test')
        self.assertEqual(actual_usage(chunks[-1].usage)['total_tokens'], 5)

    async def test_provider_errors_are_sanitized_and_not_retried(self):
        for status, expected in ((401, 'PROVIDER_AUTH'), (429, 'PROVIDER_RATE_LIMIT'),
                                 (503, 'PROVIDER_UNAVAILABLE'), (400, 'PROVIDER_ERROR'),
                                 (None, 'PROVIDER_TIMEOUT')):
            requests = []
            async def handle(request):
                requests.append(request)
                if status is None:
                    raise httpx.ReadTimeout('private provider secret', request=request)
                return httpx.Response(status, json={'error': {'message': 'private provider secret'}})
            client = AsyncOpenAI(api_key='test', max_retries=0,
                                 http_client=httpx.AsyncClient(transport=httpx.MockTransport(handle)))
            provider = Bailian(config(), client)
            with self.assertRaises(ProviderFailure) as caught:
                async with provider.stream(task()):
                    self.fail('provider unexpectedly succeeded')
            self.assertEqual(str(caught.exception), expected)
            self.assertEqual(len(requests), 1)
            await provider.close()

    async def test_cancel_real_sdk_while_awaiting_headers(self):
        entered, canceled = asyncio.Event(), asyncio.Event()
        async def handle(request):
            entered.set()
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                canceled.set()
                raise
        client = AsyncOpenAI(api_key='test', max_retries=0,
                             http_client=httpx.AsyncClient(transport=httpx.MockTransport(handle)))
        provider = Bailian(config(), client)
        executing = asyncio.create_task(Worker(NS(model='qwen-flash', heartbeat_seconds=.02),
                                              FakeManager(heartbeat=False), provider).execute(task()))
        await asyncio.wait_for(entered.wait(), 1)
        with self.assertRaises(LeaseLost):
            await asyncio.wait_for(executing, 1)
        self.assertTrue(canceled.is_set())
        await provider.close()


class ConfigTests(unittest.TestCase):
    def test_provider_is_fixed_bailian_and_secret_repr_is_redacted(self):
        self.assertNotIn('test-provider-secret', repr(config()))
        with self.assertRaises(ValueError):
            config(base_url='https://untrusted.example/v1')
        with self.assertRaises(ValueError):
            config(heartbeat_seconds=30)


if __name__ == '__main__':
    unittest.main()
