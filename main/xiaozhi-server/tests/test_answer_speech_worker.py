"""Independent fixed-text TTS boundary and real loopback transport tests."""
import asyncio
import dataclasses
import hashlib
import io
import json
import os
import tempfile
import unittest
import wave
import httpx
from pathlib import Path
from unittest.mock import patch

from websockets.asyncio.client import connect as real_connect
from websockets.asyncio.server import serve

from answer_speech_worker.config import Config
from answer_speech_worker.provider import (CosyVoice, MAX_AUDIO_BYTES, MAX_PCM_BYTES,
                                          FixedOriginConnect, Observation, SpeechFailure, observe, valid_text, wav_audio)
from answer_speech_worker.transport import ControlError, LeaseLost, Manager
from answer_speech_worker.worker import Worker, validate


def config(**overrides):
    values = dict(manager_url='http://127.0.0.1:18101/xiaozhi', worker_key='manager-' + 'x' * 40,
                  provider_url='wss://fixture.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference',
                  api_key='provider-secret', model='cosyvoice-v3.5-flash', voice='longantai_v3', enabled=True)
    return Config(**(values | overrides))


class ConfigurationTests(unittest.TestCase):
    def test_legacy_config_remains_valid_and_new_model_rejects_v2_voice(self):
        self.assertEqual(config(model='cosyvoice-v2', voice='longxiaochun_v2').model, 'cosyvoice-v2')
        with self.assertRaises(ValueError):
            config(voice='longxiaochun_v2')

    def test_config_is_private_explicit_and_disabled_by_default(self):
        self.assertFalse(dataclasses.replace(config(), enabled=False).enabled)
        self.assertNotIn('provider-secret', repr(config()))
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'private.json'
            path.write_text(json.dumps(dataclasses.asdict(config())))
            path.chmod(0o644)
            with self.assertRaises(ValueError):
                Config.load(path)
            path.chmod(0o600)
            self.assertEqual(Config.load(path).model, 'cosyvoice-v3.5-flash')
            path.write_text(json.dumps(dataclasses.asdict(config()) | {'audio_url': 'https://arbitrary'}))
            with self.assertRaises(ValueError):
                Config.load(path)

    def test_provider_origin_model_and_credentials_cannot_be_replaced_by_untrusted_values(self):
        invalid = [dict(provider_url='ws://127.0.0.1/inference'),
                   dict(provider_url='wss://dashscope.aliyuncs.com.evil.test/api-ws/v1/inference'),
                   dict(provider_url='wss://dashscope.aliyuncs.com/api-ws/v1/inference?key=a'),
                   dict(provider_url='wss://user@dashscope.aliyuncs.com/api-ws/v1/inference'),
                   dict(model='qwen3.8-omni-flash'), dict(voice='voice\r\nInjected: yes'),
                   dict(manager_url='http://example.com/xiaozhi'), dict(manager_url='http://localhost/x/../y'),
                   dict(worker_key='provider-secret'), dict(api_key='x\n'),
                   dict(provider_timeout_seconds=float('nan')), dict(heartbeat_seconds=True),
                   dict(enabled='true'), dict(workspace_id='x\n')]
        for overrides in invalid:
            with self.subTest(overrides=overrides), self.assertRaises(ValueError):
                config(**overrides)

    def test_fixed_text_is_not_cleaned_or_rewritten_and_utf16_is_bounded(self):
        text = '**原文** <speak>照读</speak>\nhttps://invalid.test/a\tEND'
        self.assertEqual(valid_text(text), text)
        self.assertEqual(valid_text('😀' * 4000), '😀' * 4000)
        for value in ('', '  ', 'a\0b', '\ud800', '😀' * 4001, 123):
            with self.subTest(value=repr(value)[:30]), self.assertRaises(SpeechFailure):
                valid_text(value)

    def test_wav_has_exact_pcm_and_bounded_valid_frames(self):
        pcm = b'\x01\x00' * 24000
        audio = wav_audio(pcm)
        with wave.open(io.BytesIO(audio.data), 'rb') as wav:
            self.assertEqual((wav.getframerate(), wav.getnchannels(), wav.getsampwidth()), (24000, 1, 2))
            self.assertEqual(wav.readframes(24000), pcm)
        self.assertEqual(audio.duration_ms, 1000)
        self.assertEqual(audio.sha256, hashlib.sha256(audio.data).hexdigest())
        self.assertLessEqual(len(audio.data), MAX_AUDIO_BYTES)
        for pcm in (b'', b'x', b'x' * (MAX_PCM_BYTES + 2)):
            with self.assertRaises(SpeechFailure):
                wav_audio(pcm)

    def test_usage_is_actual_cumulative_characters_not_text_length_or_tokens(self):
        observation = Observation()
        self.assertIsNone(observation.usage)
        observe({'header': {}, 'payload': {'usage': {'characters': True}}}, observation)
        self.assertIsNone(observation.usage)
        for count in (3, 3, 9):
            observe({'header': {'attributes': {'request_uuid': 'request-a'}},
                     'payload': {'usage': {'characters': count}}}, observation)
        self.assertEqual(observation.usage, {'characters': 9})
        self.assertEqual(observation.provider_request_id, 'request-a')
        with self.assertRaises(SpeechFailure):
            observe({'header': {}, 'payload': {'usage': {'characters': 8}}}, observation)


class ProviderTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.commands = []
        self.entered = asyncio.Event()
        self.release = asyncio.Event()
        self.closed = asyncio.Event()
        self.mode = 'success'
        self.handshakes = 0
        async def handshake(connection, request):
            self.handshakes += 1
            if self.mode == 'redirect':
                response = connection.respond(307, 'redirect must not be followed')
                response.headers['Location'] = 'ws://127.0.0.1:' + str(self.server.sockets[0].getsockname()[1]) + '/different-origin'
                return response
        self.server = await serve(self.handler, '127.0.0.1', 0, process_request=handshake)
        port = self.server.sockets[0].getsockname()[1]
        def connector(url, **kwargs):
            self.assertEqual(url, config().provider_url)
            self.assertEqual(kwargs['additional_headers']['Authorization'], 'Bearer provider-secret')
            kwargs.pop('additional_headers')
            return FixedOriginConnect('ws://127.0.0.1:' + str(port), **kwargs)
        self.mock = patch('answer_speech_worker.provider.connect', connector)
        self.mock.start()

    async def asyncTearDown(self):
        self.release.set()
        self.server.close()
        await self.server.wait_closed()
        self.mock.stop()

    async def handler(self, socket):
        try:
            run = json.loads(await socket.recv()); self.commands.append(run)
            task_id = run['header']['task_id']
            async def event(kind, usage=None, **header):
                payload = {} if usage is None else {'usage': {'characters': usage}}
                await socket.send(json.dumps({'header': {'event': kind, 'task_id': task_id, **header}, 'payload': payload}))
            if self.mode == 'denied':
                await event('task-failed', error_code='AccessDenied', error_message='SECRET must not propagate')
                return
            if self.mode == 'wrong_id':
                await event('task-started', task_id='wrong')
                return
            if self.mode == 'early_audio':
                await socket.send(b'\x00\x00')
                return
            await event('task-started')
            self.commands.extend([json.loads(await socket.recv()), json.loads(await socket.recv())])
            await event('result-generated', None if self.mode == 'unknown_usage' else 23,
                        attributes={'request_uuid': 'actual-provider-request'})
            self.entered.set()
            if self.mode == 'wait':
                await socket.wait_closed()
                return
            if self.mode == 'malformed':
                await socket.send('{oops')
                return
            if self.mode == 'over_limit':
                await socket.send(b'\0' * 2000)
                return
            if self.mode == 'odd':
                await socket.send(b'\0')
            else:
                await socket.send(b'\x01\x00' * 24000)
            if self.mode == 'disconnect':
                return
            await event('task-finished', None if self.mode == 'unknown_usage' else 23)
        finally:
            self.closed.set()

    async def test_real_wss_protocol_sends_exact_text_once_and_uses_local_voice(self):
        text = '**不要改写** <speak>样本</speak>\nEND'
        observation = Observation()
        audio = await CosyVoice(config()).synthesize(text, observation)
        self.assertEqual(audio.duration_ms, 1000)
        self.assertEqual([c['header']['action'] for c in self.commands], ['run-task', 'continue-task', 'finish-task'])
        parameters = self.commands[0]['payload']['parameters']
        self.assertEqual(parameters['text_type'], 'PlainText')
        self.assertFalse(parameters['enable_ssml'])
        self.assertEqual(self.commands[0]['payload']['model'], 'cosyvoice-v3.5-flash')
        self.assertEqual(parameters['voice'], 'longantai_v3')
        self.assertNotIn('tools', self.commands[0])
        self.assertEqual(self.commands[1]['payload']['input']['text'], text)
        self.assertEqual(observation.usage, {'characters': 23})
        self.assertEqual(observation.provider_request_id, 'actual-provider-request')

    async def test_missing_usage_stays_null(self):
        self.mode = 'unknown_usage'; observation = Observation()
        await CosyVoice(config()).synthesize('test', observation)
        self.assertIsNone(observation.usage)

    async def test_provider_redirect_is_rejected_without_forwarding_credentials(self):
        self.mode = 'redirect'
        with self.assertRaises(SpeechFailure) as failure:
            await CosyVoice(config()).synthesize('test', Observation())
        self.assertEqual(failure.exception.code, 'SPEECH_PROVIDER_ERROR')
        self.assertEqual(self.handshakes, 1)
        self.assertFalse(self.commands)

    async def test_cancellation_during_io_closes_socket_and_keeps_only_observed_usage(self):
        self.mode = 'wait'; observation = Observation()
        job = asyncio.create_task(CosyVoice(config()).synthesize('test', observation))
        await asyncio.wait_for(self.entered.wait(), 2)
        job.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await job
        await asyncio.wait_for(self.closed.wait(), 2)
        self.assertEqual(observation.usage, {'characters': 23})

    async def test_provider_failures_limits_and_incomplete_output_never_return_audio(self):
        for mode, code in [('denied', 'SPEECH_PROVIDER_AUTH'), ('wrong_id', 'SPEECH_PROVIDER_PROTOCOL'),
                           ('early_audio', 'SPEECH_AUDIO_INVALID'), ('malformed', 'SPEECH_PROVIDER_PROTOCOL'),
                           ('odd', 'SPEECH_AUDIO_INVALID'), ('disconnect', 'SPEECH_PROVIDER_ERROR'),
                           ('over_limit', 'SPEECH_AUDIO_LIMIT')]:
            self.mode = mode
            with self.subTest(mode=mode), self.assertRaises(SpeechFailure) as failure:
                await CosyVoice(config()).synthesize('test', Observation(), max_bytes=1044 if mode == 'over_limit' else MAX_AUDIO_BYTES)
            self.assertEqual(failure.exception.code, code)
            self.assertNotIn('SECRET', str(failure.exception))

    async def test_timeout_stops_synthesis_without_retries_and_keeps_observed_usage(self):
        self.mode = 'wait'; observation = Observation()
        with self.assertRaises(SpeechFailure) as failure:
            await CosyVoice(config(provider_timeout_seconds=1)).synthesize('test', observation)
        self.assertEqual(failure.exception.code, 'SPEECH_PROVIDER_TIMEOUT')
        self.assertEqual(len([c for c in self.commands if c['header']['action'] == 'run-task']), 1)
        self.assertEqual(observation.usage, {'characters': 23})


def task(text='固定原文 **不改写**。'):
    return dict(job_id='a' * 32, conversation_id='b' * 32, message_id='c' * 32,
                message_version='d' * 64, text_sha256=hashlib.sha256(text.encode()).hexdigest(), text=text,
                attempt=1, lease_token='lease-' + 'x' * 40, lease_seconds=45, model='cosyvoice-v3.5-flash',
                voice='longantai_v3', audio=dict(encoding='s16le', sample_rate=24000, channels=1, container='wav'),
                policy=dict(max_text_chars=8000, max_audio_bytes=5242880, max_duration_ms=120000))


class FakeManager:
    def __init__(self):
        self.events = []
        self.active = True
        self.hold_audio = False
        self.uploading = asyncio.Event()
        self.release = asyncio.Event()
        self.authorized = asyncio.Event()
        self.claims = 0

    async def callback(self, current, action, **payload):
        self.events.append((action, dict(current), payload))
        if action == 'heartbeat':
            self.authorized.set()
            return {'active': self.active}
        return {'status': 'completed'}

    async def audio(self, current, audio):
        self.events.append(('audio', dict(current), {'sha256': audio.sha256}))
        self.uploading.set()
        if self.hold_audio:
            await self.release.wait()
        return dict(audio_sha256=audio.sha256, audio_bytes=len(audio.data), duration_ms=audio.duration_ms)

    async def claim(self):
        self.claims += 1
        return None

    async def close(self):
        pass


class FakeProvider:
    def __init__(self):
        self.calls = []
        self.entered = asyncio.Event()
        self.release = asyncio.Event()
        self.cancelled = asyncio.Event()
        self.wait = False
        self.error = None

    async def synthesize(self, text, observation, **limits):
        self.calls.append((text, limits))
        observation.usage = {'characters': 42}
        observation.provider_request_id = 'real-reported-fixture-id'
        self.entered.set()
        try:
            if self.wait:
                await self.release.wait()
            if self.error:
                raise self.error
            return wav_audio(b'\1\0' * 2400)
        except asyncio.CancelledError:
            self.cancelled.set()
            raise


class WorkerTests(unittest.IsolatedAsyncioTestCase):
    def worker(self):
        current = config()
        # Speed up only the fixture's heartbeat; production Config validates >=1s.
        current.heartbeat_seconds = .02
        self.manager, self.provider = FakeManager(), FakeProvider()
        return Worker(current, self.manager, self.provider)

    async def test_completed_job_binds_exact_text_version_hash_and_real_usage(self):
        worker = self.worker(); current = task()
        await worker.execute(current)
        self.assertEqual(self.provider.calls[0][0], current['text'])
        self.assertEqual([event[0] for event in self.manager.events],
                         ['heartbeat', 'heartbeat', 'audio', 'heartbeat', 'complete', 'usage'])
        complete = next(event for event in self.manager.events if event[0] == 'complete')
        self.assertEqual(complete[1]['message_version'], current['message_version'])
        self.assertEqual(complete[2]['usage'], {'characters': 42})
        self.assertNotIn('tokens', complete[2]['usage'])

    async def test_task_model_voice_text_hash_codec_and_policy_must_match(self):
        mutations = [dict(model='arbitrary-model'), dict(voice='arbitrary-voice'), dict(text='changed'),
                     dict(message_version='12'), dict(attempt=True), dict(attempt='1'), dict(lease_seconds=True),
                     dict(lease_token='x\n' + 'z' * 40), dict(audio=dict(encoding='opus', sample_rate=24000, channels=1, container='wav')),
                     dict(audio=dict(encoding='s16le', sample_rate=24000, channels=True, container='wav')),
                     dict(policy=dict(max_text_chars=8001, max_audio_bytes=5242880, max_duration_ms=120000))]
        for mutation in mutations:
            worker = self.worker()
            with self.subTest(keys=list(mutation)):
                await worker.execute(task() | mutation)
                self.assertFalse(self.provider.calls)
                self.assertEqual([e[0] for e in self.manager.events], ['fail'])

    async def test_heartbeat_remains_live_and_revocation_cancels_inflight_synthesis(self):
        worker = self.worker(); self.provider.wait = True
        pending = asyncio.create_task(worker.execute(task()))
        await asyncio.wait_for(self.provider.entered.wait(), 1)
        self.manager.active = False
        await asyncio.wait_for(pending, 1)
        self.assertTrue(self.provider.cancelled.is_set())
        self.assertNotIn('audio', [e[0] for e in self.manager.events])
        self.assertNotIn('complete', [e[0] for e in self.manager.events])
        self.assertEqual(self.manager.events[-1][0], 'usage')
        self.assertEqual(self.manager.events[-1][2]['usage'], {'characters': 42})

    async def test_revocation_while_uploading_stops_completion_and_only_audits_cost(self):
        worker = self.worker(); self.manager.hold_audio = True
        pending = asyncio.create_task(worker.execute(task()))
        await asyncio.wait_for(self.manager.uploading.wait(), 1)
        self.manager.active = False
        await asyncio.wait_for(pending, 1)
        self.assertNotIn('complete', [e[0] for e in self.manager.events])
        self.assertEqual(self.manager.events[-1][0], 'usage')

    async def test_shutdown_cancels_provider_and_audits_observed_usage(self):
        worker = self.worker(); self.provider.wait = True
        pending = asyncio.create_task(worker.execute(task()))
        await asyncio.wait_for(self.provider.entered.wait(), 1)
        pending.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await pending
        self.assertTrue(self.provider.cancelled.is_set())
        self.assertEqual(self.manager.events[-1][0], 'usage')
        self.assertNotIn('complete', [e[0] for e in self.manager.events])

    async def test_provider_failure_uses_registered_error_and_keeps_usage_without_audio(self):
        worker = self.worker(); self.provider.error = SpeechFailure('SPEECH_PROVIDER_PROTOCOL')
        await worker.execute(task())
        failure = next(event[2] for event in self.manager.events if event[0] == 'fail')
        self.assertEqual(failure['error_code'], 'SPEECH_PROVIDER_ERROR')
        self.assertEqual(failure['usage'], {'characters': 42})
        self.assertNotIn('audio', [e[0] for e in self.manager.events])

    async def test_disabled_worker_never_claims(self):
        worker = self.worker(); worker.config.enabled = False
        await worker.run()
        self.assertEqual(self.manager.claims, 0)


class TransportTests(unittest.IsolatedAsyncioTestCase):
    async def test_fixed_paths_independent_headers_numeric_attempt_and_version_binding(self):
        calls = []
        async def handler(request):
            calls.append(request)
            if request.method == 'PUT':
                return httpx.Response(200, json={'code': 0, 'data': {'uploaded': True}})
            return httpx.Response(200, json={'code': 0, 'data': {'active': True}})
        client = httpx.AsyncClient(base_url=config().manager_url + '/',
            headers={'X-Answer-Speech-Worker-Key': config().worker_key}, transport=httpx.MockTransport(handler))
        manager = Manager(config(), client); current = task()
        try:
            await manager.callback(current, 'heartbeat')
            audio = wav_audio(b'\0\0' * 2400)
            await manager.audio(current, audio)
            self.assertEqual(calls[0].url.path, '/xiaozhi/internal/answer-speech-worker/jobs/' + current['job_id'] + '/heartbeat')
            body = json.loads(calls[0].content)
            self.assertEqual(body, Manager.fence(current))
            self.assertIs(type(body['attempt']), int)
            self.assertEqual(calls[1].content, audio.data)
            self.assertEqual(calls[1].headers['X-Message-Version'], current['message_version'])
            self.assertEqual(calls[1].headers['X-Speech-Attempt'], '1')
            self.assertNotIn('Authorization', calls[1].headers)
            with self.assertRaises(ControlError):
                await manager.callback(current | {'job_id': 'https://evil.test'}, 'complete')
            with self.assertRaises(ControlError):
                await manager.callback(current, 'arbitrary')
            self.assertEqual(len(calls), 2)
        finally:
            await manager.close()

    async def test_lost_acks_retry_identical_body_without_new_provider_call(self):
        bodies = []
        async def handler(request):
            bodies.append(request.content)
            if len(bodies) == 1:
                raise httpx.ReadError('private details never returned')
            return httpx.Response(200, json={'code': 0, 'data': None})
        manager = Manager(config(), httpx.AsyncClient(base_url=config().manager_url + '/', transport=httpx.MockTransport(handler)))
        try:
            await manager.callback(task(), 'usage', usage=None, provider_request_id=None)
            self.assertEqual(len(bodies), 2)
            self.assertEqual(bodies[0], bodies[1])
        finally:
            await manager.close()

    async def test_claim_loss_is_not_immediately_retried_and_authority_errors_stop(self):
        for status in (401, 403, 404, 409, 410, 302):
            calls = []
            async def handler(request):
                calls.append(request)
                return httpx.Response(status, headers={'location': 'http://evil.test'}, json={'code': 0, 'data': {}})
            manager = Manager(config(), httpx.AsyncClient(base_url=config().manager_url + '/', transport=httpx.MockTransport(handler)))
            try:
                with self.assertRaises(LeaseLost if status != 302 else ControlError):
                    await manager.claim()
                self.assertEqual(len(calls), 1)
            finally:
                await manager.close()

    async def test_response_size_envelope_and_encoding_are_bounded(self):
        responses = [httpx.Response(200, content=b'x' * 65537),
                     httpx.Response(200, json={'code': 7, 'data': {}}),
                     httpx.Response(200, json={'code': False, 'data': {}}),
                     httpx.Response(200, json={'code': 0}),
                     httpx.Response(200, headers={'content-encoding': 'gzip'}, content=b'')]
        for response in responses:
            manager = Manager(config(), httpx.AsyncClient(base_url=config().manager_url + '/',
                transport=httpx.MockTransport(lambda request: response)))
            try:
                with self.assertRaises(ControlError):
                    await manager.claim()
            finally:
                await manager.close()


if __name__ == '__main__':
    unittest.main()
