"""SDK wire combinations: image/frame/original audio plus authoritative tool messages."""
import json
import unittest
import httpx
from openai import AsyncOpenAI
from ai_worker.provider import Bailian
from ai_worker.worker import Worker
from ai_worker.tools import policy
from test_ai_worker import config
from test_ai_worker_tools import ToolManager, TOOLS
from test_ai_worker_media import media_task, ref
from test_ai_worker_omni import cfg, wav, audio_ref, OMNI


class MediaTools(ToolManager):
    def __init__(self):super().__init__();self.downloads=[]
    async def media(self,task,reference,maximum):
        self.downloads.append(reference)
        return wav() if reference['variant']=='audio' else b'\xff\xd8technical-jpeg\xff\xd9'


def combined(refs,model,modalities=None):
    values=dict(tools=TOOLS,attempt=1,control_epoch=2)
    if modalities is not None:values['tool_policy']={'input_modalities':modalities}
    return media_task(refs,model=model,**values)


class MultimodalToolsTests(unittest.IsolatedAsyncioTestCase):
    async def test_real_sdk_image_audio_and_video_tool_round_trip(self):
        for model,refs,modalities in (
            ('qwen3-vl-plus',[ref()],['text','image']),
            ('qwen3.8-flash',[ref()],['text','image']),
            (OMNI,[audio_ref()],['text','audio']),
            (OMNI,[ref('frame_0','video',time_ms='1200'),{**audio_ref(),'kind':'video'}],['text','image','audio']),
        ):
            captured=[]
            async def forbidden(request):self.fail('No provider/model fallback is allowed')
            async def handler(request):
                captured.append(json.loads(request.content))
                if len(captured)==1:
                    choices=[dict(index=0,delta={'tool_calls':[dict(index=0,id='call-media',type='function',function={'name':'get_device','arguments':'{"id":"42"}'})]},finish_reason='tool_calls')]
                else:choices=[dict(index=0,delta={'content':'Technical result with source'},finish_reason='stop')]
                chunks=[dict(choices=choices),dict(choices=[],usage=dict(prompt_tokens=7,completion_tokens=3,total_tokens=10,
                        prompt_tokens_details={'audio_tokens':4} if 'audio' in modalities else {}))]
                return httpx.Response(200,headers={'content-type':'text/event-stream','x-request-id':'media-'+str(len(captured))},
                    content=''.join('data: '+json.dumps(value)+'\n\n' for value in chunks)+'data: [DONE]\n\n')
            def sdk(handle):return AsyncOpenAI(api_key='isolated-test',base_url=config().base_url,max_retries=0,
                http_client=httpx.AsyncClient(transport=httpx.MockTransport(handle)))
            settings=cfg(vision_model=model if model!=OMNI else 'qwen3-vl-plus')
            provider=Bailian(settings,client=sdk(forbidden),vision_client=sdk(handler),omni_client=sdk(handler))
            manager=MediaTools()
            await Worker(settings,manager,provider).execute(combined(refs,model,modalities))
            await provider.close()
            self.assertEqual(manager.events[-1][0],'complete')
            self.assertEqual(len(captured),2);self.assertEqual(captured[0]['tools'],TOOLS)
            self.assertEqual(captured[1]['messages'][-1]['role'],'tool')
            self.assertEqual(captured[0]['messages'][0],captured[1]['messages'][0], 'Original evidence survives the tool continuation')
            content=captured[0]['messages'][0]['content']
            if 'audio' in modalities:
                self.assertTrue(any(part['type']=='input_audio' for part in content))
                self.assertFalse(any(reference['variant']=='transcript' for reference in manager.downloads))
                self.assertEqual(captured[0]['modalities'],['text']);self.assertEqual(captured[0]['reasoning_effort'],'none')
                self.assertEqual(manager.events[-1][1]['usage']['input_audio_tokens'],8)
            if 'image' in modalities:self.assertTrue(any(part['type']=='image_url' for part in content))
            self.assertEqual(manager.events[-1][1]['usage']['total_tokens'],20)
            self.assertEqual(len(manager.events[-1][1]['provider_requests']),2)

    async def test_server_policy_required_and_model_matrix_cannot_be_overridden(self):
        for model,refs,declared in (('qwen3-vl-plus',[ref()],None),
                                    (OMNI,[audio_ref()],['text','image']),
                                    ('qwen-flash',[ref()],['text','image']),
                                    ('qwen3-vl-plus',[audio_ref()],['text','audio'])):
            manager=MediaTools()
            await Worker(cfg(vision_model='qwen3-vl-plus'),manager,None).execute(combined(refs,model,declared))
            self.assertEqual(manager.events[-1],('fail',{'error_code':'MODEL_CAPABILITY_UNAVAILABLE'}))
            self.assertEqual(manager.downloads,[]);self.assertEqual(manager.batches,[])

    async def test_provider_rejects_combination_without_transcription_or_model_fallback(self):
        captured=[]
        async def handler(request):captured.append(request);return httpx.Response(400,json={'error':{'message':'private diagnostic'}})
        client=AsyncOpenAI(api_key='isolated-test',base_url=config().base_url,max_retries=0,http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
        provider=Bailian(cfg(),client=client,omni_client=client);manager=MediaTools()
        await Worker(cfg(),manager,provider).execute(combined([audio_ref()],OMNI,['text','audio']))
        await provider.close()
        self.assertEqual(len(captured),1);self.assertEqual(manager.batches,[])
        action,body=manager.events[-1];self.assertEqual((action,body['error_code']),('fail','PROVIDER_ERROR'))
        self.assertIsNone(body['usage']);self.assertEqual(len(body['provider_requests']),1)
        self.assertNotIn('private diagnostic',json.dumps(manager.events))
        self.assertFalse(any(reference['variant']=='transcript' for reference in manager.downloads))

    def test_malformed_matrix_rejected_and_prepared_content_still_checked(self):
        from ai_worker.provider import ProviderFailure
        for declared in ([],['image','image'],['text','url'],True,{'image':True}):
            with self.assertRaises(ProviderFailure):policy(combined([ref()],'qwen3-vl-plus',declared))
        prepared=combined([],'qwen3-vl-plus',['text'])
        prepared['messages'][0]['content']=[{'type':'image_url','image_url':{'url':'data:image/jpeg;base64,AA=='}}]
        with self.assertRaises(ProviderFailure):policy(prepared)


if __name__=='__main__':unittest.main()
