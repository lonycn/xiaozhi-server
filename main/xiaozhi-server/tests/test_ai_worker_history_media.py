"""Explicit same-run history capabilities; old tasks still reject historical media."""
import unittest
from ai_worker.worker import Worker
from ai_worker.provider import ProviderFailure
from test_ai_worker import config
from test_ai_worker_media import media_task, ref, MediaManager
from test_ai_worker_omni import cfg, audio_ref, wav, OMNI


class HistoryMediaTests(unittest.IsolatedAsyncioTestCase):
    def history(self,refs=None,**kwargs):
        value=media_task(refs,**kwargs)
        value['messages'].append({'role':'user','content':'Please inspect the original evidence again.'})
        return value

    async def test_history_requires_explicit_manager_policy_and_preserves_refs(self):
        for allowed in (None,False,True):
            current=self.history(model='qwen3-vl-plus')
            if allowed is not None:current['media_policy']={'allow_history':allowed}
            manager=MediaManager()
            if allowed is not True:
                with self.assertRaises(ProviderFailure):await Worker(config(vision_model='qwen3-vl-plus'),manager,None).prepare(current)
                self.assertEqual(manager.downloads,[])
            else:
                result=await Worker(config(vision_model='qwen3-vl-plus'),manager,None).prepare(current)
                self.assertEqual(manager.downloads[0]['attachment_id'],'123')
                self.assertTrue(any(part['type']=='image_url' for part in result['messages'][0]['content']))
                self.assertEqual(result['messages'][-1]['content'],current['messages'][-1]['content'])
                self.assertNotIn('attachments',result['messages'][0])

    async def test_raw_history_remains_original_audio_and_model_routing_is_strict(self):
        current=self.history([audio_ref()],model=OMNI,media_policy={'allow_history':True})
        manager=MediaManager(wav());result=await Worker(cfg(),manager,None).prepare(current)
        self.assertTrue(any(part['type']=='input_audio' for part in result['messages'][0]['content']))
        self.assertEqual([item['variant'] for item in manager.downloads],['audio'])
        current['model']='qwen-flash';manager=MediaManager(wav())
        with self.assertRaisesRegex(ProviderFailure,'MODEL_CAPABILITY_UNAVAILABLE'):await Worker(cfg(),manager,None).prepare(current)
        self.assertEqual(manager.downloads,[])

    async def test_history_flag_does_not_relax_roles_counts_mime_or_input_budgets(self):
        cases=[]
        role=self.history(media_policy={'allow_history':True});role['messages'][0]['role']='assistant';cases.append(role)
        count=self.history([ref() for _ in range(17)],media_policy={'allow_history':True});cases.append(count)
        mime=self.history([ref(mime_type='image/png')],media_policy={'allow_history':True});cases.append(mime)
        malformed=self.history(media_policy={'allow_history':'true'});cases.append(malformed)
        for current in cases:
            with self.assertRaises(ProviderFailure):await Worker(config(vision_model='vision-model'),MediaManager(),None).prepare(current)
        text=self.history([ref('text','file')],media_policy={'allow_history':True})
        with self.assertRaises(ProviderFailure):await Worker(config(),MediaManager(b'x'*60001),None).prepare(text)


if __name__=='__main__':unittest.main()
