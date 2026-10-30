"""B3 media-context bridge: real SDK wire, isolated HTTP and lease cancellation."""
import asyncio
import base64
import json
import unittest
import httpx
from openai import AsyncOpenAI
from ai_worker.provider import Bailian, ProviderFailure
from ai_worker.transport import Manager, LeaseLost
from ai_worker.worker import Worker
from test_ai_worker import config, task, FakeManager, FakeProvider, FakeStream, chunk

RUN='a'*32


def ref(variant='display', kind='image', **extra):
    value = dict(attachment_id='123', variant=variant,
                mime_type='image/jpeg' if variant=='display' or variant.startswith('frame_') else 'text/plain',
                kind=kind)
    value.update(extra)
    return value


def media_task(refs=None, **extra):
    value = dict(run_id=RUN, model='vision-model', messages=[{'role':'user','content':'question',
                'attachments': refs if refs is not None else [ref()]}])
    value.update(extra)
    return task(**value)


class MediaManager(FakeManager):
    def __init__(self, data=b'\xff\xd8\xff\xd9', **kwargs):
        super().__init__(**kwargs)
        self.data=data
        self.downloads=[]
    async def media(self, task, ref, maximum):
        self.downloads.append(ref)
        return self.data


class Stream(httpx.AsyncByteStream):
    def __init__(self, data): self.data=data
    async def __aiter__(self):
        for start in range(0,len(self.data),65536): yield self.data[start:start+65536]


class MediaPreparationTests(unittest.IsolatedAsyncioTestCase):
    async def test_image_video_time_and_internal_fields_removed(self):
        manager=MediaManager()
        worker=Worker(config(vision_model='vision-model'),manager,None)
        prepared=await worker.prepare(media_task([ref(),ref('frame_0','video',time_ms=1500)]))
        self.assertNotIn('attachments',prepared['messages'][0])
        content=prepared['messages'][0]['content']
        images=[part for part in content if part['type']=='image_url']
        self.assertEqual(len(images),2)
        self.assertEqual(images[0]['image_url']['url'],'data:image/jpeg;base64,'+base64.b64encode(manager.data).decode())
        self.assertIn('1500 ms',json.dumps(content))

    async def test_plain_b2_text_unchanged(self):
        prepared=await Worker(config(),MediaManager(),None).prepare(task())
        self.assertEqual(prepared['messages'],task()['messages'])

    async def test_java_long_frame_timestamps_are_bounded_and_ids_preserved(self):
        for timestamp in ('0', '501', '180000', 0, 501, 180000):
            with self.subTest(timestamp=timestamp):
                manager = MediaManager()
                reference = ref('frame_0', 'video', time_ms=timestamp,
                                attachment_id='2116127778922262529')
                prepared = await Worker(config(vision_model='vision-model'),manager,None).prepare(media_task([reference]))
                self.assertIn(f'{int(timestamp)} ms', json.dumps(prepared['messages']))
                self.assertEqual(manager.downloads[0]['attachment_id'], '2116127778922262529')
                self.assertEqual(reference['time_ms'], timestamp)

    async def test_noncanonical_or_out_of_range_frame_timestamps_never_download(self):
        for timestamp in ('00','01',' 1','1 ','+1','-1','1e3','1.0','NaN',
                          '180001','9223372036854775807','',None,True,1.0):
            with self.subTest(timestamp=timestamp):
                manager = MediaManager()
                with self.assertRaisesRegex(ProviderFailure, 'MEDIA_INPUT_UNAVAILABLE'):
                    await Worker(config(vision_model='vision-model'),manager,None).prepare(
                        media_task([ref('frame_0','video',time_ms=timestamp)]))
                self.assertEqual(manager.downloads, [])

    async def test_ref_limit_rejected_before_download(self):
        manager=MediaManager()
        with self.assertRaisesRegex(ProviderFailure,'MEDIA_INPUT_UNAVAILABLE'):
            await Worker(config(vision_model='vision-model'),manager,None).prepare(media_task([ref()]*17))
        self.assertEqual(manager.downloads,[])

    async def test_history_original_unknown_variant_and_timestamp_rejected(self):
        worker=Worker(config(vision_model='vision-model'),MediaManager(),None)
        bad=[media_task([ref('original')]),media_task([ref('frame_0','video',time_ms=-1)]),
             media_task([ref('frame_0','video',time_ms=True)])]
        history=media_task()
        history['messages'].append({'role':'user','content':'next input'})
        bad.append(history)
        for item in bad:
            with self.subTest(item=item):
                with self.assertRaisesRegex(ProviderFailure,'MEDIA_INPUT_UNAVAILABLE'): await worker.prepare(item)

    async def test_images_need_declared_vision_model(self):
        with self.assertRaisesRegex(ProviderFailure,'MODEL_CAPABILITY_UNAVAILABLE'):
            await Worker(config(),MediaManager(),None).prepare(media_task())

    async def test_total_image_bytes_bounded(self):
        with self.assertRaisesRegex(ProviderFailure,'MEDIA_INPUT_UNAVAILABLE'):
            await Worker(config(vision_model='vision-model'),MediaManager(b'x'*(8<<20)),None).prepare(media_task([ref()]*4))

    async def test_text_char_limit_invalid_utf8_and_empty_scan_explicit(self):
        refs=[ref('text','file'),ref('transcript','audio')]
        for raw in (b'x'*40000,b'\xff',b'bad\x00text'):
            with self.subTest(raw_size=len(raw)):
                with self.assertRaisesRegex(ProviderFailure,'MEDIA_INPUT_UNAVAILABLE'):
                    await Worker(config(),MediaManager(raw),None).prepare(media_task(refs, model='qwen-flash'))
        prepared=await Worker(config(),MediaManager(b''),None).prepare(media_task([refs[0]],model='qwen-flash'))
        self.assertIn('No extractable text',json.dumps(prepared))

    async def test_lease_revocation_cancels_media_download(self):
        canceled=asyncio.Event()
        class Blocking(MediaManager):
            async def media(self,*args):
                try: await asyncio.Event().wait()
                except asyncio.CancelledError:
                    canceled.set()
                    raise
        manager=Blocking(heartbeat=False)
        cfg=config(vision_model='vision-model')
        cfg.heartbeat_seconds=.01
        with self.assertRaises(LeaseLost):
            await Worker(cfg,manager,FakeProvider()).execute(media_task())
        self.assertTrue(canceled.is_set())
        self.assertFalse(any(action in ('fail','complete') for action,_ in manager.events))


class MediaTransportTests(unittest.IsolatedAsyncioTestCase):
    async def test_only_internal_url_and_lease_header(self):
        requests=[]
        async def handler(request):
            requests.append(request)
            return httpx.Response(200,headers={'content-type':'image/jpeg'},stream=Stream(b'bytes'))
        async with httpx.AsyncClient(base_url='http://control/',transport=httpx.MockTransport(handler)) as client:
            result=await Manager(config(),client).media(media_task(),ref(),8<<20)
        self.assertEqual(result,b'bytes')
        self.assertEqual(requests[0].url.path,f'/internal/ai-worker/runs/{RUN}/attachments/123/content')
        self.assertEqual(requests[0].url.params['variant'],'display')
        self.assertEqual(requests[0].headers['X-AI-Lease'],'lease')
        self.assertNotIn('Authorization',requests[0].headers)

    async def test_stream_limit_mime_and_authority_rejection(self):
        for status,mime,data,error in [(200,'image/jpeg',b'x'*101,ProviderFailure),
                                      (200,'text/html',b'x',ProviderFailure),(409,'image/jpeg',b'x',LeaseLost)]:
            async def handler(request): return httpx.Response(status,headers={'content-type':mime},stream=Stream(data))
            async with httpx.AsyncClient(base_url='http://control/',transport=httpx.MockTransport(handler)) as client:
                with self.assertRaises(error): await Manager(config(),client).media(media_task(),ref(),100)

    async def test_arbitrary_url_id_or_original_rejected_without_request(self):
        async def handler(request): self.fail('network must not be contacted')
        async with httpx.AsyncClient(base_url='http://control/',transport=httpx.MockTransport(handler)) as client:
            manager=Manager(config(),client)
            for reference in [ref('original'),ref(attachment_id='../../private'),ref(attachment_id='https://evil.invalid')]:
                with self.assertRaises(ProviderFailure): await manager.media(media_task(),reference,100)


class VisionWireTests(unittest.IsolatedAsyncioTestCase):
    async def test_real_sdk_vision_route_multimodal_no_internal_fields(self):
        captured=[]
        async def text_handler(request): self.fail('vision task must not hit text endpoint')
        async def vision_handler(request):
            captured.append(request)
            return httpx.Response(200,headers={'content-type':'text/event-stream'},content=(
                'data: {"id":"c","choices":[{"index":0,"delta":{"content":"seen image"},"finish_reason":null}]}\n\n'
                'data: {"id":"c","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}\n\n'
                'data: [DONE]\n\n'))
        text=AsyncOpenAI(api_key='text-key',base_url='https://dashscope.aliyuncs.com/compatible-mode/v1',max_retries=0,
                        http_client=httpx.AsyncClient(transport=httpx.MockTransport(text_handler)))
        vision=AsyncOpenAI(api_key='vision-key',base_url='https://dashscope.aliyuncs.com/compatible-mode/v1',max_retries=0,
                          http_client=httpx.AsyncClient(transport=httpx.MockTransport(vision_handler)))
        cfg=config(vision_model='vision-model')
        provider=Bailian(cfg,text,vision)
        manager=MediaManager()
        await Worker(cfg,manager,provider).execute(media_task())
        self.assertEqual(manager.events[-1][0],'complete')
        body=json.loads(captured[0].content)
        self.assertEqual(body['model'],'vision-model')
        self.assertNotIn('attachments',body['messages'][0])
        self.assertNotIn('lease',json.dumps(body))
        self.assertEqual(captured[0].headers['Authorization'],'Bearer vision-key')
        await provider.close()

    async def test_unmatched_provider_model_never_falls_back(self):
        async def handler(request): self.fail('unknown model must not call network')
        sdk=AsyncOpenAI(api_key='key',max_retries=0,http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
        provider=Bailian(config(),sdk)
        with self.assertRaisesRegex(ProviderFailure,'MODEL_CAPABILITY_UNAVAILABLE'):
            async with provider.stream(task(model='unknown')): pass
        await provider.close()

    def test_vision_config_rejects_untrusted_provider(self):
        with self.assertRaises(ValueError): config(vision_model='vision',vision_base_url='https://untrusted.invalid/v1')
        with self.assertRaises(ValueError): config(vision_api_key='key')


if __name__=='__main__': unittest.main()
