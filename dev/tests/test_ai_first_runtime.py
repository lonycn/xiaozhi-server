import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch, Mock
import zipfile

spec = importlib.util.spec_from_file_location('ai_first_manage', Path(__file__).resolve().parents[1] / 'manage.py')
manage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(manage)


class AiFirstRuntimeTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(); self.addCleanup(temp.cleanup)
        root = Path(temp.name); self.runtime = root / 'runtime'; self.runtime.mkdir()
        self.api = root / 'api'; (self.api / 'target').mkdir(parents=True)
        for name, value in [('RUNTIME', self.runtime), ('API', self.api)]:
            override = patch.object(manage, name, value); override.start(); self.addCleanup(override.stop)

    def jar(self, complete=True):
        names=['xiaozhi/modules/agenttool/AgentToolExecutionCore.class','xiaozhi/modules/agenttool/PublicToolResultStore.class',
               'xiaozhi/modules/aicontext/ConversationEvidenceService.class','xiaozhi/modules/realtimetool/RealtimeToolService.class',
               'db/changelog/agent-tools.changelog.yaml','db/changelog/ai-context.changelog.yaml','db/changelog/ai-public-results.changelog.yaml','db/changelog/realtime-tools.changelog.yaml']
        with zipfile.ZipFile(self.api / 'target/xiaozhi-esp32-api.jar','w') as archive:
            for name in names if complete else names[:-1]: archive.writestr('BOOT-INF/classes/'+name,b'fixture')

    def base(self):
        manage.private_json('ai-settings.json',{'enabled':True,'model':'qwen-flash'})
        manage.private_json('agent-tools-settings.json',{'enabled':True,'technical_local':False})
        manage.private_json('ai-worker.json',{'fixture':'preserve'})
        (self.runtime/'ai-worker-key').write_text('fixture-private-key')

    def props(self):
        manage.configure(); return (self.runtime/'application-dev.properties').read_text()

    def test_defaults_off_and_require_both_base_features(self):
        self.base();self.assertNotIn('features.ai-context-enabled',self.props())
        manage.private_json('ai-first-settings.json',{'enabled':True,'technical_local':False,'realtime_tools':False})
        props=self.props();self.assertIn('features.ai-context-enabled=true',props)
        self.assertIn('features.agent-tool-results-enabled=true',props)
        self.assertNotIn('agent-tools.technical-local-enabled',props)
        self.assertNotIn('features.realtime-tools-enabled',props)
        self.assertNotIn('ai.execution.multimodal-tools-enabled',props)
        manage.private_json('agent-tools-settings.json',{'enabled':False,'technical_local':False})
        self.assertNotIn('features.ai-context-enabled',self.props())
        manage.private_json('agent-tools-settings.json',{'enabled':True,'technical_local':False})
        manage.private_json('ai-settings.json',{'enabled':False,'model':'qwen-flash'})
        self.assertNotIn('features.agent-tool-results-enabled',self.props())

    def test_configured_models_only_and_unknown_rejected(self):
        self.base()
        manage.private_json('media-settings.json',{'enabled':True,'vision_model':'qwen3.8-flash'})
        self.assertEqual(manage.ai_first_models(),['qwen3.8-flash'])
        for name in ('media-worker-key','media-ticket-key'): (self.runtime/name).write_text('x'*40)
        manage.private_json('ai-first-settings.json',{'enabled':True,'technical_local':False,'realtime_tools':False})
        props=self.props(); self.assertIn('ai.execution.multimodal-tools-enabled=true',props)
        self.assertIn('ai.execution.multimodal-tools-models=qwen3.8-flash',props)
        self.jar()
        with self.assertRaisesRegex(RuntimeError,'does not match'): manage.prepare_ai_first()
        manage.private_json('ai-worker.json',{'vision_model':'qwen3.8-flash'})
        with patch.object(manage.subprocess,'run'): self.assertTrue(manage.prepare_ai_first()['enabled'])
        manage.private_json('ai-settings.json',{'enabled':True,'model':'qwen-flash','omni_model':'qwen3.8-omni-flash'})
        self.assertEqual(manage.ai_first_models(),['qwen3.8-flash','qwen3.8-omni-flash'])
        manage.private_json('media-settings.json',{'enabled':True,'vision_model':'qwen3.unknown'})
        with self.assertRaisesRegex(RuntimeError,'vision model'): manage.ai_first_models()
        manage.private_json('media-settings.json',{'enabled':False,'vision_model':'qwen3.unknown'})
        self.assertEqual(manage.ai_first_models(),['qwen3.8-omni-flash'])

    def test_prepare_requires_fresh_jar_and_explicit_technical_opt_in(self):
        self.base();self.jar(False)
        with self.assertRaisesRegex(RuntimeError,'freshly built'): manage.prepare_ai_first(True)
        self.assertFalse((self.runtime/'ai-first-settings.json').exists())
        self.jar()
        with patch.object(manage.subprocess,'run'):
            settings=manage.prepare_ai_first()
            self.assertEqual(settings,{'enabled':True,'technical_local':False,'realtime_tools':False,'materials_enabled':False,'speech_enabled':False,'skills_enabled':False,'runtime_control_enabled':False})
            settings=manage.prepare_ai_first(True)
        manage.private_json('ai-first-settings.json',settings)
        self.assertTrue(manage.ai_first_settings()['technical_local'])
        self.assertEqual((self.runtime/'ai-first-settings.json').stat().st_mode & 0o777,0o600)
        self.assertIn('agent-tools.technical-local-enabled=true',self.props())
        self.assertEqual(json.loads((self.runtime/'ai-worker.json').read_text()),{'fixture':'preserve'})

    def test_realtime_tools_only_when_realtime_was_enabled_and_no_config_mutation(self):
        self.base();self.jar()
        worker={'enabled':True,'worker_key':'fixture','server_id':'fixture','host':'127.0.0.1','port':18899,'manager_url':manage.API_URL,'origins':['http://127.0.0.1:18894','http://localhost:18894'],'allow_missing_origin':False}
        manage.private_json('realtime-worker.json',worker)
        rtc={'enabled':True,'worker_key':'fixture','server_id':'fixture','public_url':'ws://127.0.0.1:18899/realtime'}
        with patch.object(manage,'realtime_settings',return_value=rtc),patch.object(manage,'validate_realtime_settings'),patch.object(manage,'realtime_python'),patch.object(manage.subprocess,'run'):
            settings=manage.prepare_ai_first(True)
            manage.private_json('ai-first-settings.json',settings)
            self.assertTrue(manage.ai_first_settings()['realtime_tools'])
            self.assertIn('features.realtime-tools-enabled=true',self.props())
        self.assertEqual(json.loads((self.runtime/'realtime-worker.json').read_text()),worker)
        self.assertNotIn('features.realtime-tools-enabled',self.props())

    def materials_setup(self):
        self.base(); self.jar()
        source={'vision_model':'qwen3.8-flash','vision_base_url':'https://dashscope.aliyuncs.com/compatible-mode/v1',
                'vision_api_key':'test-secret','vision_workspace_id':'workspace'}
        target={'enabled':True,'worker_key':'worker-secret','server_id':'server','host':'127.0.0.1',
                'port':18899,'manager_url':manage.API_URL,'origins':['http://127.0.0.1:18894','http://localhost:18894'],
                'allow_missing_origin':False,'provider_url':'wss://provider','provider_api_key':'other-rt-secret'}
        manage.private_json('ai-worker.json', source); manage.private_json('realtime-worker.json', target)
        return source,target

    def test_materials_default_legacy_and_config_flag(self):
        self.materials_setup()
        manage.private_json('realtime-worker.json',manage.ai_first_materials_worker_config())
        manage.private_json('ai-first-settings.json',{'enabled':True,'technical_local':False,'realtime_tools':True})
        self.assertFalse(manage.ai_first_settings()['materials_enabled'])
        self.assertNotIn('features.realtime-materials-enabled',self.props())
        manage.private_json('ai-first-settings.json',{'enabled':True,'technical_local':False,'realtime_tools':True,'materials_enabled':True})
        with patch.object(manage,'realtime_settings',return_value={'enabled':False}):
            self.assertNotIn('features.realtime-materials-enabled',self.props())
        with patch.object(manage,'realtime_settings',return_value={'enabled':True,'worker_key':'fixture','public_url':'ws://localhost'}), patch.object(manage,'validate_realtime_settings'):
            props=self.props()
            self.assertIn('features.realtime-materials-enabled=true',props)
            self.assertIn('realtime.materials.perception-model=qwen3.8-flash',props)
        manage.private_json('ai-first-settings.json',{'enabled':True,'technical_local':False,'realtime_tools':True,'materials_enabled':'yes'})
        with self.assertRaisesRegex(RuntimeError,'Invalid AI-first settings'):manage.ai_first_settings()

    def test_materials_jar_requires_all_classes_migration_and_include(self):
        self.jar()
        with self.assertRaisesRegex(RuntimeError,'freshly built API'):manage.ai_first_materials_jar_check()
        path=self.api/'target/xiaozhi-esp32-api.jar'
        with zipfile.ZipFile(path,'a') as archive:
            for name in ('RealtimeInputService','RealtimeInputController','RealtimeInputWorkerController','RealtimeInputModels'):
                archive.writestr('BOOT-INF/classes/xiaozhi/modules/realtimeinput/'+name+'.class','fixture')
            archive.writestr('BOOT-INF/classes/db/changelog/realtime-inputs.changelog.yaml','fixture')
            archive.writestr('BOOT-INF/classes/db/changelog/db.changelog-master.yaml','other')
        with self.assertRaisesRegex(RuntimeError,'freshly built API'):manage.ai_first_materials_jar_check()
        with zipfile.ZipFile(path,'a') as archive:
            archive.writestr('BOOT-INF/classes/db/changelog/db.changelog-master.yaml','  file: realtime-inputs.changelog.yaml')
        manage.ai_first_materials_jar_check()

    def test_materials_rejects_changed_private_config(self):
        self.materials_setup()
        manage.private_json('ai-first-settings.json',{'enabled':True,'technical_local':False,'realtime_tools':True,'materials_enabled':True})
        with patch.object(manage,'realtime_settings',return_value={'enabled':True,'worker_key':'fixture','public_url':'ws://localhost'}), patch.object(manage,'validate_realtime_settings'):
            with self.assertRaisesRegex(RuntimeError,'configuration changed'):self.props()

    def test_materials_prepares_exact_private_copy_without_write(self):
        source,target=self.materials_setup(); before=(self.runtime/'realtime-worker.json').read_bytes()
        result=manage.ai_first_materials_worker_config()
        self.assertEqual(result,{**target,**{f'perception_{key}':source[f'vision_{key}'] for key in ('model','base_url','api_key','workspace_id')}})
        self.assertEqual(before,(self.runtime/'realtime-worker.json').read_bytes())
        manage.private_json('realtime-worker.json', result)
        self.assertEqual((self.runtime/'realtime-worker.json').stat().st_mode & 0o777,0o600)

    def test_materials_falls_back_to_existing_base_credentials(self):
        source,target=self.materials_setup()
        source.update(vision_base_url='',vision_api_key='',vision_workspace_id='',base_url='https://dashscope.aliyuncs.com/compatible-mode/v1',api_key='fallback-key',workspace_id='fallback-workspace')
        manage.private_json('ai-worker.json',source)
        result=manage.ai_first_materials_worker_config()
        self.assertEqual((result['perception_base_url'],result['perception_api_key'],result['perception_workspace_id']),
                         (source['base_url'],source['api_key'],source['workspace_id']))
        self.assertEqual(result['provider_url'],target['provider_url'])

    def test_materials_rejects_invalid_model_url_and_missing_credentials(self):
        source,_=self.materials_setup()
        for field,value in [('vision_model','unknown'),('vision_base_url','http://dashscope.aliyuncs.com'),
                            ('vision_base_url','https://dashscope.aliyuncs.com.attacker.invalid'),('vision_api_key',''),
                            ('vision_workspace_id','bad workspace')]:
            with self.subTest(field=field,value=value):
                manage.private_json('ai-worker.json',{**source,field:value})
                with self.assertRaisesRegex(RuntimeError,'approved Bailian') as caught: manage.ai_first_materials_worker_config()
                self.assertNotIn('test-secret',str(caught.exception))
        (self.runtime/'ai-worker.json').chmod(0o644)
        with self.assertRaisesRegex(RuntimeError,'private AI vision'):manage.ai_first_materials_worker_config()

    def test_materials_rejected_for_other_actions_before_mutation(self):
        with patch('sys.argv',['manage.py','ai-first-disable','--materials']),patch.object(manage,'configure') as configure:
            with self.assertRaises(SystemExit):manage.main()
            configure.assert_not_called()

    def test_materials_preflight_failure_leaves_private_files_untouched(self):
        self.materials_setup(); before={p.name:p.read_bytes() for p in self.runtime.iterdir() if p.is_file()}
        with patch('sys.argv',['manage.py','ai-first-enable','--materials']), \
             patch.object(manage,'prepare_ai_first',return_value={'enabled':True,'materials_enabled':True}), \
             patch.object(manage,'ai_first_gateway_preflight',side_effect=RuntimeError('preflight')), \
             patch.object(manage,'stop_realtime_worker') as stop_rt, patch.object(manage,'stop_process') as stop, \
             patch.object(manage,'private_json') as write, patch.object(manage,'recreate_api') as recreate:
            with self.assertRaisesRegex(RuntimeError,'preflight'):manage.main()
            for op in (stop_rt,stop,write,recreate):op.assert_not_called()
        self.assertEqual(before,{p.name:p.read_bytes() for p in self.runtime.iterdir() if p.is_file()})

    def test_materials_enable_writes_only_after_preflight(self):
        source,target=self.materials_setup(); order=[]
        with patch('sys.argv',['manage.py','ai-first-enable','--materials']), \
             patch.object(manage,'prepare_ai_first',return_value={'enabled':True,'materials_enabled':True}), \
             patch.object(manage,'ai_first_gateway_preflight',side_effect=lambda:order.append('preflight')), \
             patch.object(manage,'stop_realtime_worker',side_effect=lambda:order.append('stop_rt')), \
             patch.object(manage,'stop_process',side_effect=lambda *a:order.append('stop_ai')), \
             patch.object(manage,'configure'),patch.object(manage,'recreate_api'),patch.object(manage,'start_ai_worker'), \
             patch.object(manage,'realtime_settings',return_value={'enabled':False}):
            manage.main()
        self.assertEqual(order,['preflight','stop_rt','stop_ai'])
        self.assertEqual(json.loads((self.runtime/'realtime-worker.json').read_text()),{**target,**{f'perception_{key}':source[f'vision_{key}'] for key in ('model','base_url','api_key','workspace_id')}})
        self.assertEqual((self.runtime/'realtime-worker.json').stat().st_mode & 0o777,0o600)

    def test_runtime_control_legacy_and_flag_are_explicit(self):
        self.base()
        manage.private_json('ai-first-settings.json',{'enabled':True,'technical_local':False,'realtime_tools':False})
        self.assertFalse(manage.ai_first_settings()['runtime_control_enabled'])
        self.assertNotIn('ai.runtime-control.enabled',self.props())
        manage.private_json('ai-first-settings.json',{'enabled':True,'technical_local':False,'realtime_tools':False,'runtime_control_enabled':True})
        props=self.props();self.assertIn('ai.runtime-control.enabled=true',props)
        self.assertNotIn('ai.runtime-control.per-user-concurrency',props)
        manage.private_json('ai-first-settings.json',{'enabled':False,'technical_local':False,'realtime_tools':False,'runtime_control_enabled':True})
        self.assertNotIn('ai.runtime-control.enabled',self.props())
        manage.private_json('ai-first-settings.json',{'enabled':True,'technical_local':False,'realtime_tools':False,'runtime_control_enabled':'true'})
        with self.assertRaisesRegex(RuntimeError,'Invalid AI-first settings'):manage.ai_first_settings()

    def test_runtime_control_rejects_other_actions_before_mutation(self):
        with patch('sys.argv',['manage.py','ai-first-status','--runtime-control']),patch.object(manage,'configure') as configure:
            with self.assertRaises(SystemExit):manage.main()
            configure.assert_not_called()

    def test_runtime_control_preflight_rejects_old_jar_without_mutation(self):
        self.base();self.jar()
        before={p.name:p.read_bytes() for p in self.runtime.iterdir() if p.is_file()}
        with patch('sys.argv',['manage.py','ai-first-enable','--runtime-control']), \
             patch.object(manage,'stop_speech_worker') as stop_speech,patch.object(manage,'stop_realtime_worker') as stop_rt, \
             patch.object(manage,'stop_process') as stop_ai,patch.object(manage,'private_json') as write,patch.object(manage,'recreate_api') as recreate:
            with self.assertRaisesRegex(RuntimeError,'runtime-control requires a freshly built API'):manage.main()
            for op in (stop_speech,stop_rt,stop_ai,write,recreate):op.assert_not_called()
        self.assertEqual(before,{p.name:p.read_bytes() for p in self.runtime.iterdir() if p.is_file()})

    def test_runtime_control_jar_requires_classes_migration_and_master_include(self):
        self.jar();path=self.api/'target/xiaozhi-esp32-api.jar'
        with zipfile.ZipFile(path,'a') as archive:
            for name in ('AiRuntimeControl','AiRuntimeControlController'):
                archive.writestr('BOOT-INF/classes/xiaozhi/modules/airuntime/'+name+'.class','fixture')
            archive.writestr('BOOT-INF/classes/db/changelog/ai-runtime-control.changelog.yaml','fixture')
            archive.writestr('BOOT-INF/classes/db/changelog/db.changelog-master.yaml','other')
        with self.assertRaisesRegex(RuntimeError,'runtime-control requires a freshly built API'):
            manage.ai_first_optional_jar_check('runtime-control')
        with zipfile.ZipFile(path,'a') as archive:
            archive.writestr('BOOT-INF/classes/db/changelog/db.changelog-master.yaml','  file: ai-runtime-control.changelog.yaml')
        manage.ai_first_optional_jar_check('runtime-control')

    def test_runtime_control_cli_saves_only_after_preflight(self):
        self.base()
        settings={'enabled':True,'technical_local':False,'realtime_tools':False,'materials_enabled':False,'speech_enabled':False,'skills_enabled':False,'runtime_control_enabled':True}
        order=[]
        with patch('sys.argv',['manage.py','ai-first-enable','--runtime-control']), \
             patch.object(manage,'prepare_ai_first',return_value=settings) as prepare, \
             patch.object(manage,'ai_first_gateway_preflight',side_effect=lambda:order.append('preflight')), \
             patch.object(manage,'stop_speech_worker',side_effect=lambda:order.append('stop')), \
             patch.object(manage,'stop_realtime_worker'),patch.object(manage,'stop_process'),patch.object(manage,'configure'), \
             patch.object(manage,'recreate_api'),patch.object(manage,'start_ai_worker'),patch.object(manage,'realtime_settings',return_value={'enabled':False}):
            manage.main()
        prepare.assert_called_once_with(False,False,False,False,True)
        self.assertEqual(order,['preflight','stop'])
        self.assertTrue(manage.ai_first_settings()['runtime_control_enabled'])

    def test_runtime_control_status_is_readonly(self):
        self.base();manage.private_json('ai-first-settings.json',{'enabled':True,'technical_local':False,'realtime_tools':False,'runtime_control_enabled':True})
        before={p.name:p.read_bytes() for p in self.runtime.iterdir() if p.is_file()}
        with patch('sys.argv',['manage.py','ai-first-status']),patch.object(manage,'configure') as configure,patch('builtins.print') as output:
            manage.main()
        configure.assert_not_called();self.assertEqual(before,{p.name:p.read_bytes() for p in self.runtime.iterdir() if p.is_file()})
        self.assertTrue(any(call.args==('AI-first runtime control:',True) for call in output.call_args_list))

    def test_speech_skills_legacy_settings_and_flags(self):
        self.base()
        manage.private_json('ai-first-settings.json',{'enabled':True,'technical_local':False,'realtime_tools':False})
        self.assertFalse(manage.ai_first_settings()['speech_enabled']);self.assertFalse(manage.ai_first_settings()['skills_enabled'])
        self.assertNotIn('features.answer-speech-enabled',self.props());self.assertNotIn('features.ai-skills-enabled',self.props())
        key='a'*64;(self.runtime/'answer-speech-worker-key').write_text(key);(self.runtime/'answer-speech-worker-key').chmod(0o600)
        manage.private_json('ai-first-settings.json',{'enabled':True,'technical_local':False,'realtime_tools':False,'speech_enabled':True,'skills_enabled':True})
        props=self.props();self.assertIn('features.answer-speech-enabled=true',props);self.assertIn('features.ai-skills-enabled=true',props)
        self.assertIn('answer-speech.storage-root=/app/attachments/answer-speech',props)
        self.assertIn('answer-speech.model=cosyvoice-v3.5-flash',props);self.assertIn('answer-speech.voice=longantai_v3',props)
        manage.private_json('agent-tools-settings.json',{'enabled':False,'technical_local':False})
        props=self.props();self.assertNotIn('features.ai-skills-enabled',props);self.assertIn('features.answer-speech-enabled=true',props)

    def test_optional_jar_markers_and_master_include(self):
        for feature,module,classes,migration in [('speech','answerspeech',['AnswerSpeechService','AnswerSpeechController','AnswerSpeechWorkerController','AnswerSpeechWorkerKeyFilter'],'answer-speech.changelog.yaml'),('skills','aiskill',['AiSkillService','AiSkillController'],'ai-skills.changelog.yaml')]:
            with self.subTest(feature=feature):
                self.jar()
                with self.assertRaisesRegex(RuntimeError,'freshly built API'):manage.ai_first_optional_jar_check(feature)
                with zipfile.ZipFile(self.api/'target/xiaozhi-esp32-api.jar','a') as archive:
                    for name in classes:archive.writestr(f'BOOT-INF/classes/xiaozhi/modules/{module}/{name}.class','fixture')
                    archive.writestr('BOOT-INF/classes/db/changelog/'+migration,'fixture')
                    archive.writestr('BOOT-INF/classes/db/changelog/db.changelog-master.yaml','  file: '+migration)
                manage.ai_first_optional_jar_check(feature)

    def test_speech_config_reads_only_approved_tts_and_writes_nothing(self):
        self.base();source={'ws_url':'wss://workspace.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference','api_key':'private-token','model':'cosyvoice-v3.5-flash','voice':'other','private_voice':'longantai_v3','volume':99}
        before={p.name:p.read_bytes() for p in self.runtime.iterdir() if p.is_file()}
        with patch.object(manage,'compose',return_value=Mock(stdout=json.dumps(source))) as compose,patch.object(manage,'speech_worker_python',return_value='python'),patch.object(manage.subprocess,'run'):
            worker=manage.prepare_speech_worker()
        self.assertEqual(worker['provider_url'],source['ws_url']);self.assertEqual(worker['voice'],'longantai_v3');self.assertEqual(worker['workspace_id'],'')
        self.assertNotIn('volume',worker);self.assertNotEqual(worker['worker_key'],source['api_key']);self.assertEqual(len(worker['worker_key']),64)
        self.assertIn("model_code='TTS_BailianCosyVoice'",compose.call_args.args[-1]);self.assertIn('SELECT ',compose.call_args.args[-1])
        self.assertEqual(before,{p.name:p.read_bytes() for p in self.runtime.iterdir() if p.is_file()})

    def test_speech_manager_uses_provisioned_legacy_profile_during_rollout(self):
        self.base()
        key='a'*64
        (self.runtime/'answer-speech-worker-key').write_text(key)
        (self.runtime/'answer-speech-worker-key').chmod(0o600)
        manage.private_json('ai-first-settings.json',{'enabled':True,'technical_local':False,'realtime_tools':False,'speech_enabled':True})
        manage.private_json('answer-speech-worker.json',{'model':'cosyvoice-v2','voice':'longxiaochun_v2'})
        props=self.props()
        self.assertIn('answer-speech.model=cosyvoice-v2',props)
        self.assertIn('answer-speech.voice=longxiaochun_v2',props)
        manage.private_json('answer-speech-worker.json',{'model':'cosyvoice-v3.5-flash','voice':'longxiaochun_v2'})
        with self.assertRaisesRegex(RuntimeError,'Invalid private answer speech profile'):
            self.props()

    def test_speech_config_rejects_bad_provider_and_does_not_leak_key(self):
        self.base();source={'ws_url':'wss://dashscope.aliyuncs.com/api-ws/v1/inference','api_key':'private-token','model':'cosyvoice-v3.5-flash','voice':'longantai_v3'}
        for update in ({'ws_url':'wss://dashscope.aliyuncs.com.attacker.invalid/api-ws/v1/inference'},{'voice':'other'},{'model':'other'},{'api_key':''}):
            with self.subTest(update=update),patch.object(manage,'compose',return_value=Mock(stdout=json.dumps({**source,**update}))):
                with self.assertRaises(RuntimeError) as caught:manage.prepare_speech_worker()
                self.assertNotIn('private-token',str(caught.exception))
        with patch.object(manage,'compose',return_value=Mock(stdout='')):
            with self.assertRaises(RuntimeError):manage.prepare_speech_worker()

    def test_optional_cli_flags_rejected_for_other_actions(self):
        for flag in ('--speech','--skills'):
            with self.subTest(flag=flag),patch('sys.argv',['manage.py','status',flag]),patch.object(manage,'configure') as configure:
                with self.assertRaises(SystemExit):manage.main()
                configure.assert_not_called()

    def test_speech_preflight_failure_does_not_stop_or_write(self):
        self.base();before={p.name:p.read_bytes() for p in self.runtime.iterdir() if p.is_file()}
        with patch('sys.argv',['manage.py','ai-first-enable','--speech']),patch.object(manage,'prepare_ai_first',return_value={}), \
             patch.object(manage,'prepare_speech_worker',side_effect=RuntimeError('unavailable')),patch.object(manage,'stop_speech_worker') as stop, \
             patch.object(manage,'private_json') as write,patch.object(manage,'recreate_api') as recreate:
            with self.assertRaisesRegex(RuntimeError,'unavailable'):manage.main()
            stop.assert_not_called();write.assert_not_called();recreate.assert_not_called()
        self.assertEqual(before,{p.name:p.read_bytes() for p in self.runtime.iterdir() if p.is_file()})

    def test_speech_enable_writes_after_preflight_and_preserves_key(self):
        self.base();key='d'*64;worker={'worker_key':key,'api_key':'provider','enabled':True};order=[]
        with patch('sys.argv',['manage.py','ai-first-enable','--speech','--skills']), \
             patch.object(manage,'prepare_ai_first',return_value={'enabled':True,'technical_local':False,'realtime_tools':False,'materials_enabled':False,'speech_enabled':True,'skills_enabled':True}), \
             patch.object(manage,'prepare_speech_worker',return_value=worker), \
             patch.object(manage,'ai_first_gateway_preflight',side_effect=lambda:order.append('preflight')), \
             patch.object(manage,'stop_speech_worker',side_effect=lambda:order.append('stop_speech')), \
             patch.object(manage,'stop_realtime_worker'),patch.object(manage,'stop_process'),patch.object(manage,'configure'), \
             patch.object(manage,'recreate_api'),patch.object(manage,'start_ai_worker'),patch.object(manage,'start_speech_worker') as start, \
             patch.object(manage,'realtime_settings',return_value={'enabled':False}):
            manage.main()
        self.assertEqual(order,['preflight','stop_speech']);start.assert_called_once()
        self.assertEqual((self.runtime/'answer-speech-worker-key').read_text(),key)
        self.assertEqual((self.runtime/'answer-speech-worker-key').stat().st_mode & 0o777,0o600)
        self.assertEqual((self.runtime/'answer-speech-worker.json').stat().st_mode & 0o777,0o600)
        self.assertTrue(manage.ai_first_settings()['skills_enabled'])
        manage.write_speech_key(key)
        with self.assertRaisesRegex(RuntimeError,'changed during preflight'):manage.write_speech_key('e'*64)

    def test_ai_first_status_is_read_only_even_if_private_key_missing(self):
        self.base()
        manage.private_json('ai-first-settings.json',{'enabled':True,'technical_local':False,'realtime_tools':False,'speech_enabled':True})
        before={p.name:p.read_bytes() for p in self.runtime.iterdir() if p.is_file()}
        with patch('sys.argv',['manage.py','ai-first-status']),patch.object(manage,'configure') as configure, \
             patch.object(manage,'speech_worker_status',return_value='unverified (process identity or command mismatch)'), \
             patch('builtins.print') as output:
            manage.main()
        configure.assert_not_called()
        self.assertEqual(before,{p.name:p.read_bytes() for p in self.runtime.iterdir() if p.is_file()})
        lines=' '.join(' '.join(map(str, call.args)) for call in output.call_args_list)
        self.assertIn('unverified (process identity or command mismatch)',lines)
        self.assertIn('provider and job health are not checked',lines)
        self.assertNotIn('fixture-private-key',lines)

    def test_speech_status_does_not_confuse_unverified_with_stopped(self):
        self.assertEqual(manage.speech_worker_status(),'stopped (no process record)')
        path=self.runtime/'answer-speech-worker-process.json';path.write_text('invalid')
        self.assertEqual(manage.speech_worker_status(),'unverified (invalid or unreadable process record)')
        manage.private_json('answer-speech-worker-process.json',{'pid':1234,'identity':'old'})
        with patch.object(manage,'speech_worker_process',return_value=None),patch.object(manage,'process_identity',return_value=''):
            self.assertEqual(manage.speech_worker_status(),'unverified (process record stale)')
        with patch.object(manage,'speech_worker_process',return_value=None),patch.object(manage,'process_identity',return_value='other'):
            self.assertEqual(manage.speech_worker_status(),'unverified (process identity or command mismatch)')
        with patch.object(manage,'speech_worker_process',return_value={'pid':1234,'identity':'old'}):
            self.assertEqual(manage.speech_worker_status(),'running (identity verified)')

    def test_speech_worker_identity_mismatch_refuses_stop(self):
        manage.private_json('answer-speech-worker-process.json',{'pid':1234,'identity':'old'})
        with patch.object(manage,'speech_worker_process',return_value=None),patch.object(manage,'process_identity',return_value='different'),patch.object(manage.os,'killpg') as kill:
            with self.assertRaisesRegex(RuntimeError,'identity mismatch'):manage.stop_speech_worker()
            kill.assert_not_called()

    def gateway_jar(self, include=True, strict=False):
        self.jar()
        with zipfile.ZipFile(self.api / 'target/xiaozhi-esp32-api.jar','a') as archive:
            archive.writestr('BOOT-INF/classes/db/changelog/esp32-gateway.changelog.yaml',
                             '  - changeSet:\n      id: 202611021102-esp32-gateway-strict-revocation\n      author: codex\n' if strict else 'fixture')
            archive.writestr('BOOT-INF/classes/db/changelog/db.changelog-master.yaml',
                             '  - include:\n      file: esp32-gateway.changelog.yaml\n' if include else 'other')
        (self.runtime / 'compose.env').write_text('fixture')

    def test_gateway_preflight_readonly_conditions(self):
        self.gateway_jar()
        for output, blocked in [('1\t0\t0', True), ('0\t0\t0', False), ('1\t1\t0', False), ('1\t0\t1', False)]:
            with self.subTest(output=output), patch.object(manage, 'compose', return_value=Mock(stdout=output)) as compose:
                if blocked:
                    with self.assertRaisesRegex(RuntimeError, 'pending esp32-gateway trigger migration'):
                        manage.ai_first_gateway_preflight()
                else:
                    manage.ai_first_gateway_preflight()
                args = compose.call_args.args
                self.assertEqual(args[:3], ('exec','-T','mysql'))
                self.assertIn('SELECT @@GLOBAL.log_bin', args[-1])
                self.assertNotRegex(args[-1].upper(), r'\b(SET|CREATE|DROP|DELETE|UPDATE|INSERT|GRANT)\b')

    def test_gateway_preflight_new_strict_migration_combinations(self):
        self.gateway_jar(strict=True)
        cases = [('1\t0\t1\t0', True), ('1\t0\t0\t1', True),
                 ('1\t0\t1\t1', False), ('0\t0\t1\t0', False),
                 ('1\t1\t1\t0', False)]
        for output, blocked in cases:
            with self.subTest(output=output), patch.object(manage, 'compose', return_value=Mock(stdout=output)) as compose:
                if blocked:
                    with self.assertRaisesRegex(RuntimeError, 'pending esp32-gateway trigger migration'):
                        manage.ai_first_gateway_preflight()
                else:
                    manage.ai_first_gateway_preflight()
                sql = compose.call_args.args[-1]
                self.assertIn('202611020700-esp32-gateway', sql)
                self.assertIn('202611021102-esp32-gateway-strict-revocation', sql)
                self.assertNotRegex(sql.upper(), r'\b(SET|CREATE|DROP|DELETE|UPDATE|INSERT|GRANT)\b')
        with patch.object(manage, 'compose', return_value=Mock(stdout='1\t0\t1')):
            with self.assertRaisesRegex(RuntimeError, 'invalid Dev MySQL'):
                manage.ai_first_gateway_preflight()

    def test_gateway_strict_migration_only_when_included(self):
        self.gateway_jar(include=False, strict=True)
        with patch.object(manage, 'compose') as compose:
            manage.ai_first_gateway_preflight()
            compose.assert_not_called()
        self.gateway_jar(strict=False)
        with patch.object(manage, 'compose', return_value=Mock(stdout='1\t0\t1')) as compose:
            manage.ai_first_gateway_preflight()
            self.assertNotIn('202611021102-esp32-gateway-strict-revocation', compose.call_args.args[-1])

    def test_gateway_preflight_manifest_and_database_fail_closed(self):
        self.jar()
        with patch.object(manage, 'compose') as compose:
            manage.ai_first_gateway_preflight(); compose.assert_not_called()
        self.gateway_jar(include=False)
        with patch.object(manage, 'compose') as compose:
            manage.ai_first_gateway_preflight(); compose.assert_not_called()
        self.gateway_jar()
        with patch.object(manage, 'compose', side_effect=manage.subprocess.CalledProcessError(1, 'mysql')):
            with self.assertRaisesRegex(RuntimeError, 'cannot read the existing Dev database'):
                manage.ai_first_gateway_preflight()
        with patch.object(manage, 'compose', return_value=Mock(stdout='bad')):
            with self.assertRaisesRegex(RuntimeError, 'invalid Dev MySQL'):
                manage.ai_first_gateway_preflight()
        (self.runtime / 'compose.env').unlink()
        with self.assertRaisesRegex(RuntimeError, 'existing Dev stack and database'):
            manage.ai_first_gateway_preflight()

    def test_gateway_preflight_failure_precedes_mutations(self):
        self.base(); self.gateway_jar()
        before = {p.name:p.read_bytes() for p in self.runtime.iterdir() if p.is_file()}
        with patch('sys.argv',['manage.py','ai-first-enable']), \
             patch.object(manage,'prepare_ai_first',return_value={'enabled':True}), \
             patch.object(manage,'compose',return_value=Mock(stdout='1\t0\t0')), \
             patch.object(manage,'configure') as configure, \
             patch.object(manage,'stop_realtime_worker') as stop_rt, \
             patch.object(manage,'stop_process') as stop, \
             patch.object(manage,'private_json') as write, \
             patch.object(manage,'recreate_api') as recreate:
            with self.assertRaisesRegex(RuntimeError,'pending esp32-gateway'):
                manage.main()
            for operation in (configure,stop_rt,stop,write,recreate): operation.assert_not_called()
        self.assertEqual(before,{p.name:p.read_bytes() for p in self.runtime.iterdir() if p.is_file()})

    def test_disable_preserves_base_settings_and_worker_configs(self):
        self.base();manage.private_json('ai-first-settings.json',{'enabled':True,'technical_local':True,'realtime_tools':False})
        with patch('sys.argv',['manage.py','ai-first-disable']),patch.object(manage,'stop_realtime_worker'),patch.object(manage,'stop_process'),patch.object(manage,'recreate_api'),patch.object(manage,'start_ai_worker'):
            manage.main()
        self.assertFalse(manage.ai_first_settings()['enabled']);self.assertTrue(manage.agent_tools_settings()['enabled'])
        self.assertFalse(manage.agent_tools_settings()['technical_local']);self.assertNotIn('features.ai-context-enabled',self.props())
        self.assertEqual(json.loads((self.runtime/'ai-worker.json').read_text()),{'fixture':'preserve'})


if __name__=='__main__': unittest.main()
