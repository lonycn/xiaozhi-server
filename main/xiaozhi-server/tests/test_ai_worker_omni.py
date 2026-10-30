"""Non-realtime raw sound bridge, without external provider or business data."""
import asyncio
import base64
import io
import json
import unittest
import wave
from types import SimpleNamespace as NS
import httpx
from openai import AsyncOpenAI
from ai_worker.provider import Bailian, ProviderFailure
from ai_worker.worker import Worker, MAX_AUDIO_BYTES, actual_usage
from ai_worker.transport import Manager, LeaseLost
from test_ai_worker import config, FakeProvider
from test_ai_worker_media import MediaManager, media_task, ref, Stream

OMNI = 'qwen3.8-omni-flash'


def cfg(**extra):
    values = dict(omni_model=OMNI, omni_base_url='https://dashscope.aliyuncs.com/compatible-mode/v1',
                  omni_api_key='explicit-omni-test-key')
    values.update(extra)
    return config(**values)


def wav(seconds=1):
    buffer = io.BytesIO()
    with wave.open(buffer, 'wb') as output:
        output.setnchannels(1); output.setsampwidth(2); output.setframerate(16000)
        output.writeframes(b'\x00\x00' * (16000 * seconds))
    return buffer.getvalue()


def audio_ref(**extra):
    return ref('audio', 'audio', mime_type='audio/wav', **extra)


class OmniTests(unittest.IsolatedAsyncioTestCase):
    async def test_raw_audio_and_video_frames_without_transcript(self):
        class Mixed(MediaManager):
            async def media(self, task, reference, maximum):
                self.downloads.append(reference)
                return wav() if reference['variant'] == 'audio' else b'jpeg'
        manager = Mixed()
        refs = [audio_ref(attachment_id='2116127778922262529'), ref('frame_0', 'video', time_ms='1500')]
        prepared = await Worker(cfg(), manager, None).prepare(media_task(refs, model=OMNI))
        content = prepared['messages'][0]['content']
        audio = next(p['input_audio'] for p in content if p['type'] == 'input_audio')
        self.assertEqual(audio, {'data': 'data:audio/wav;base64,' + base64.b64encode(wav()).decode(), 'format': 'wav'})
        self.assertIn('cannot establish a definite fault', json.dumps(content))
        self.assertEqual(manager.downloads[0]['attachment_id'], '2116127778922262529')
        self.assertTrue(any(p['type'] == 'image_url' for p in content))

    async def test_audio_limits_malformed_and_history_rejected(self):
        for data, code in [(b'not-wave', 'MEDIA_INPUT_UNAVAILABLE'), (wav(235), 'OMNI_AUDIO_LIMIT')]:
            with self.assertRaisesRegex(ProviderFailure, code):
                await Worker(cfg(), MediaManager(data), None).prepare(media_task([audio_ref()], model=OMNI))
        task = media_task([audio_ref()], model=OMNI)
        task['messages'].append({'role':'user','content':'new input'})
        manager = MediaManager(wav())
        with self.assertRaises(ProviderFailure): await Worker(cfg(), manager, None).prepare(task)
        self.assertEqual(manager.downloads, [])

    async def test_disabled_omni_does_not_use_raw_audio_or_fallback(self):
        manager = MediaManager(wav())
        with self.assertRaisesRegex(ProviderFailure, 'MODEL_CAPABILITY_UNAVAILABLE'):
            await Worker(config(), manager, None).prepare(media_task([audio_ref()], model='qwen-flash'))
        self.assertEqual(manager.downloads, [])
        await Worker(config(), manager, FakeProvider()).execute(media_task([audio_ref()], model=OMNI))
        self.assertEqual(manager.events[-1][0], 'fail')

    async def test_lease_revocation_cancels_audio_download(self):
        canceled = asyncio.Event()
        class Blocking(MediaManager):
            async def media(self, *args):
                try: await asyncio.Event().wait()
                except asyncio.CancelledError: canceled.set(); raise
        manager = Blocking(heartbeat=False)
        settings = cfg(); settings.heartbeat_seconds = .01
        with self.assertRaises(LeaseLost):
            await Worker(settings, manager, FakeProvider()).execute(media_task([audio_ref()], model=OMNI))
        self.assertTrue(canceled.is_set())
        self.assertFalse(any(action in ('complete','fail') for action,_ in manager.events))

    async def test_180_second_video_audio_is_fully_preserved_and_ids_must_be_strings(self):
        reference = audio_ref(); reference['kind'] = 'video'
        data = wav(180)
        prepared = await Worker(cfg(), MediaManager(data), None).prepare(media_task([reference], model=OMNI))
        part = next(p for p in prepared['messages'][0]['content'] if p['type']=='input_audio')
        self.assertEqual(base64.b64decode(part['input_audio']['data'].split(',',1)[1]), data)
        manager = MediaManager(wav())
        with self.assertRaises(ProviderFailure):
            await Worker(cfg(), manager, None).prepare(media_task([audio_ref(attachment_id=2116127778922262529)], model=OMNI))
        self.assertEqual(manager.downloads, [])

    async def test_provider_auth_failure_is_explicit_and_has_no_fallback(self):
        async def forbidden(request): self.fail('no fallback request is allowed')
        async def denied(request): return httpx.Response(403, json={'error': {'message':'private provider diagnostic'}})
        text = AsyncOpenAI(api_key='text', max_retries=0, http_client=httpx.AsyncClient(transport=httpx.MockTransport(forbidden)))
        omni = AsyncOpenAI(api_key='omni', max_retries=0, http_client=httpx.AsyncClient(transport=httpx.MockTransport(denied)))
        provider = Bailian(cfg(), text, omni_client=omni)
        manager = MediaManager(wav())
        await Worker(cfg(), manager, provider).execute(media_task([audio_ref()], model=OMNI))
        self.assertEqual(manager.events[-1], ('fail', {'error_code':'PROVIDER_AUTH'}))
        self.assertNotIn('private provider', json.dumps(manager.events))
        await provider.close()

    async def test_transport_audio_cap_and_authority(self):
        requests = []
        async def handler(request):
            requests.append(request)
            return httpx.Response(200, headers={'content-type':'audio/wav'}, stream=Stream(b'12345'))
        async with httpx.AsyncClient(base_url='http://control/', transport=httpx.MockTransport(handler)) as client:
            manager = Manager(config(), client)
            self.assertEqual(await manager.media(media_task(), audio_ref(), 5), b'12345')
            with self.assertRaisesRegex(ProviderFailure, 'OMNI_AUDIO_LIMIT'):
                await manager.media(media_task(), audio_ref(), 4)
        self.assertEqual(requests[0].url.params['variant'], 'audio')
        self.assertEqual(requests[0].headers['X-AI-Lease'], 'lease')

    async def test_real_sdk_wire_separate_key_usage_and_no_reasoning_output(self):
        captured = []
        async def forbidden(request): self.fail('must not call text/vision fallback')
        async def handler(request):
            captured.append(request)
            chunks = [dict(choices=[dict(index=0, delta={'reasoning_content':'private reasoning'}, finish_reason=None)]),
                      dict(choices=[dict(index=0, delta={'content':'可能有周期性声音，需结合测量确认。'}, finish_reason='stop')]),
                      dict(choices=[], usage=dict(prompt_tokens=109, completion_tokens=248, total_tokens=357,
                            prompt_tokens_details={'audio_tokens':44}, completion_tokens_details={'reasoning_tokens':200}))]
            return httpx.Response(200, headers={'content-type':'text/event-stream'}, content=''.join(
                'data: '+json.dumps(c)+'\n\n' for c in chunks)+'data: [DONE]\n\n')
        def sdk(key, h):
            return AsyncOpenAI(api_key=key, base_url='https://dashscope.aliyuncs.com/compatible-mode/v1', max_retries=0,
                               http_client=httpx.AsyncClient(transport=httpx.MockTransport(h)))
        provider = Bailian(cfg(), sdk('text-key', forbidden), omni_client=sdk('omni-key', handler))
        manager = MediaManager(wav())
        await Worker(cfg(), manager, provider).execute(media_task([audio_ref()], model=OMNI))
        body = json.loads(captured[0].content)
        self.assertEqual(body['model'], OMNI)
        self.assertEqual(body['reasoning_effort'], 'none')
        self.assertEqual(body['modalities'], ['text'])
        self.assertEqual(captured[0].headers['authorization'], 'Bearer omni-key')
        self.assertNotIn('attachments', body['messages'][0])
        self.assertNotIn('lease', json.dumps(body))
        self.assertNotIn('private reasoning', json.dumps(manager.events))
        self.assertEqual(manager.events[-1][1]['usage'], dict(input_tokens=109, output_tokens=248, total_tokens=357, input_audio_tokens=44))
        await provider.close()

    def test_explicit_config_and_actual_audio_usage_only(self):
        with self.assertRaises(ValueError): config(omni_model=OMNI)
        with self.assertRaises(ValueError): config(omni_api_key='key')
        with self.assertRaises(ValueError): cfg(omni_workspace_id='ws', omni_base_url='https://evil.invalid')
        self.assertLess(4*((MAX_AUDIO_BYTES+2)//3), 10_000_000)
        self.assertEqual(actual_usage(NS(prompt_tokens=3,completion_tokens=2,total_tokens=5,prompt_tokens_details=NS(audio_tokens=4))),
                         dict(input_tokens=3,output_tokens=2,total_tokens=5))
