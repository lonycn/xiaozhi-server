import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('dev_manage_ai_test', Path(__file__).resolve().parents[1] / 'manage.py')
manage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(manage)


class AiRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.runtime = Path(self.tmp.name) / 'runtime'
        self.runtime.mkdir()
        self.patch = patch.object(manage, 'RUNTIME', self.runtime)
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def write(self, name, data):
        (self.runtime / name).write_text(json.dumps(data))

    def test_configure_defaults_off_and_enabled_uses_private_values(self):
        manage.configure()
        props = (self.runtime / 'application-dev.properties').read_text()
        self.assertNotIn('features.ai-text-enabled=true', props)
        (self.runtime / 'ai-worker-key').write_text('secret-key')
        self.write('ai-settings.json', {'enabled': True, 'model': 'qwen-flash'})
        manage.configure()
        props = (self.runtime / 'application-dev.properties').read_text()
        self.assertIn('features.ai-text-enabled=true', props)
        self.assertIn('ai.worker.key=secret-key', props)
        self.assertEqual((self.runtime / 'application-dev.properties').stat().st_mode & 0o777, 0o600)

    def test_export_preserves_workspace_url_and_key(self):
        (self.runtime / 'ai-worker-key').write_text('existing-key')
        config = {'base_url': 'https://example.maas.aliyuncs.com/v1/workspaces/ws/compatible-mode/v1', 'api_key': 'secret', 'model_name': 'qwen-flash', 'max_tokens': 500}
        with patch.object(manage, 'compose', return_value=SimpleNamespace(stdout=json.dumps(config))) as compose:
            self.assertEqual(manage.export_ai_config(), 'qwen-flash')
        worker = json.loads((self.runtime / 'ai-worker.json').read_text())
        self.assertEqual(worker['base_url'], config['base_url'])
        self.assertEqual(worker['worker_key'], 'existing-key')
        self.assertIn('SELECT config_json', compose.call_args.args[-1])
        self.assertEqual((self.runtime / 'ai-worker.json').stat().st_mode & 0o777, 0o600)

    def test_omni_export_is_explicit_private_and_reuses_selected_credentials(self):
        config = {'base_url': 'https://example.maas.aliyuncs.com/compatible-mode/v1',
                  'api_key': 'omni-private-secret', 'model_name': 'qwen-flash'}
        with patch.object(manage, 'compose', return_value=SimpleNamespace(stdout=json.dumps(config))):
            manage.export_ai_config()
            self.assertNotIn('omni_model', json.loads((self.runtime / 'ai-worker.json').read_text()))
            manage.export_ai_config('qwen3.8-omni-flash')
        worker = json.loads((self.runtime / 'ai-worker.json').read_text())
        self.assertEqual(worker['omni_api_key'], config['api_key'])
        self.assertEqual(worker['omni_base_url'], config['base_url'])
        self.assertEqual((self.runtime / 'ai-worker.json').stat().st_mode & 0o777, 0o600)
        self.write('ai-settings.json', {'enabled': True, 'model': 'qwen-flash', 'omni_model': worker['omni_model']})
        manage.configure()
        props = (self.runtime / 'application-dev.properties').read_text()
        self.assertIn('ai.execution.omni-model=qwen3.8-omni-flash', props)
        self.assertNotIn(config['api_key'], props)
        with patch.object(manage, 'compose', return_value=SimpleNamespace(stdout=json.dumps(config))):
            with self.assertRaises(RuntimeError): manage.export_ai_config('unknown-model')

    def test_rejects_other_provider_without_writing_worker(self):
        config = {'base_url': 'https://dashscope.aliyuncs.com.evil.test/v1', 'api_key': 'secret', 'model_name': 'model'}
        with patch.object(manage, 'compose', return_value=SimpleNamespace(stdout=json.dumps(config))):
            with self.assertRaisesRegex(RuntimeError, 'Bailian'):
                manage.export_ai_config()
        self.assertFalse((self.runtime / 'ai-worker.json').exists())

    def test_worker_started_with_managed_identity(self):
        self.write('ai-worker.json', {})
        with patch.object(manage, 'ai_process', return_value=None), patch.object(manage.subprocess, 'run') as run, patch.object(manage.subprocess, 'Popen', return_value=SimpleNamespace(pid=123)) as popen, patch.object(manage, 'process_identity', return_value='identity'):
            manage.start_ai_worker()
        self.assertEqual(json.loads((self.runtime / 'ai-worker-process.json').read_text()), {'pid': 123, 'identity': 'identity'})
        self.assertIn('ai_worker', popen.call_args.args[0])
        run.assert_called_once()

    def test_disable_stops_worker_and_recreates_only_api(self):
        self.write('ai-settings.json', {'enabled': True, 'model': 'qwen-flash'})
        (self.runtime / 'ai-worker-key').write_text('secret')
        self.write('ai-worker-process.json', {'pid': 123, 'identity': 'identity'})
        with patch('sys.argv', ['manage.py', 'ai-disable']), patch.object(manage, 'process_identity', return_value='identity'), patch.object(manage.os, 'killpg') as kill, patch.object(manage, 'compose') as compose, patch.object(manage, 'stage_api_jar'), patch.object(manage, 'wait_http'), contextlib.redirect_stdout(io.StringIO()):
            manage.main()
        kill.assert_called_once()
        self.assertFalse(manage.ai_settings()['enabled'])
        self.assertEqual(compose.call_args.args, ('up', '-d', '--force-recreate', '--no-deps', 'api'))

    def test_enable_orders_export_reconfigure_and_start(self):
        calls = []
        with patch('sys.argv', ['manage.py', 'ai-enable']), patch.object(manage, 'export_ai_config', side_effect=lambda: calls.append('export') or 'qwen-flash'), patch.object(manage, 'configure', side_effect=lambda: calls.append('configure')), patch.object(manage, 'recreate_api', side_effect=lambda: calls.append('api')), patch.object(manage, 'start_ai_worker', side_effect=lambda: calls.append('worker')), patch.object(manage, 'start_candidate', side_effect=lambda: calls.append('vue')), patch.object(manage, 'wait_http'), contextlib.redirect_stdout(io.StringIO()):
            manage.main()
        self.assertEqual(calls, ['configure', 'export', 'configure', 'api', 'worker', 'vue'])
        self.assertEqual(manage.ai_settings(), {'enabled': True, 'model': 'qwen-flash'})

    def test_missing_dependencies_fails_readably(self):
        with patch.object(manage, 'ai_process', return_value=None), patch.object(manage.subprocess, 'run', side_effect=FileNotFoundError), patch.object(manage.subprocess, 'Popen') as popen:
            with self.assertRaisesRegex(RuntimeError, 'openai and httpx'):
                manage.start_ai_worker()
            popen.assert_not_called()

    def test_reused_pid_is_not_signalled(self):
        self.write('ai-worker-process.json', {'pid': 123, 'identity': 'old'})
        with patch.object(manage, 'process_identity', return_value='new'), patch.object(manage.os, 'killpg') as kill:
            manage.stop_process(manage.ai_process, 'ai-worker-process.json')
        kill.assert_not_called()

    def test_stage_preserves_open_handle_until_container_recreated(self):
        api = Path(self.tmp.name) / 'api'
        (api / 'target').mkdir(parents=True)
        source = api / 'target/xiaozhi-esp32-api.jar'
        staged = self.runtime / 'manager-api.jar'
        staged.write_bytes(b'old jar')
        source.write_bytes(b'new jar')
        with patch.object(manage, 'API', api), staged.open('rb') as old:
            manage.stage_api_jar()
            self.assertEqual(old.read(), b'old jar')
            self.assertEqual(staged.read_bytes(), b'new jar')
        self.assertEqual(list(self.runtime.glob('.manager-api-*.jar')), [])

    def test_recreate_stages_before_compose(self):
        calls = []
        with patch.object(manage, 'stage_api_jar', side_effect=lambda: calls.append('stage')), patch.object(manage, 'compose', side_effect=lambda *args: calls.append('compose')), patch.object(manage, 'wait_http', side_effect=lambda *args: calls.append('wait')):
            manage.recreate_api()
        self.assertEqual(calls, ['stage', 'compose', 'wait'])

    def test_up_stages_before_api_container(self):
        calls = []
        with patch('sys.argv', ['manage.py', 'up']), patch.object(manage, 'configure'), patch.object(manage, 'build'), patch.object(manage, 'ai_settings', return_value={'enabled': False}), patch.object(manage, 'stage_api_jar', side_effect=lambda: calls.append('stage')), patch.object(manage, 'compose', side_effect=lambda *args: calls.append('api' if 'api' in args else 'dependencies')), patch.object(manage, 'wait_http'), patch.object(manage, 'start_candidate'), contextlib.redirect_stdout(io.StringIO()):
            manage.main()
        self.assertEqual(calls, ['dependencies', 'stage', 'api'])

    def test_status_does_not_print_secret(self):
        self.write('ai-settings.json', {'enabled': False, 'model': 'qwen-flash'})
        (self.runtime / 'ai-worker-key').write_text('secret')
        output = io.StringIO()
        with patch('sys.argv', ['manage.py', 'ai-status']), contextlib.redirect_stdout(output):
            manage.main()
        self.assertNotIn('secret', output.getvalue())
        self.assertIn('qwen-flash', output.getvalue())


if __name__ == '__main__': unittest.main()
