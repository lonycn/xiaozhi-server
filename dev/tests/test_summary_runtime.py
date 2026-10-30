import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('summary_dev_manage', Path(__file__).resolve().parents[1] / 'manage.py')
manage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(manage)


class SummaryRuntimeTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.runtime = self.root / 'runtime'
        self.runtime.mkdir()
        self.api = self.root / 'api'
        (self.api / 'target').mkdir(parents=True)
        for name, value in [('RUNTIME', self.runtime), ('API', self.api)]:
            override = patch.object(manage, name, value)
            override.start()
            self.addCleanup(override.stop)

    def jar(self):
        with zipfile.ZipFile(self.api / 'target/xiaozhi-esp32-api.jar', 'w') as archive:
            archive.writestr('BOOT-INF/classes/xiaozhi/modules/supportsummary/SupportAssistanceService.class', b'test')

    def ai(self, enabled=True):
        manage.private_json('ai-settings.json', {'enabled': enabled, 'model': 'qwen-flash'})
        (self.runtime / 'ai-worker-key').write_text('synthetic-worker-key')

    def test_default_off_and_enabled_summary_requires_existing_ai(self):
        manage.configure()
        self.assertNotIn('features.support-summary-assistance-enabled=true', (self.runtime / 'application-dev.properties').read_text())
        manage.private_json('summary-settings.json', {'enabled': True})
        manage.configure()
        self.assertNotIn('features.support-summary-assistance-enabled=true', (self.runtime / 'application-dev.properties').read_text())
        self.ai()
        manage.configure()
        self.assertIn('features.support-summary-assistance-enabled=true', (self.runtime / 'application-dev.properties').read_text())

    def test_enable_validates_jar_and_ai_before_writing_enabled_state(self):
        with self.assertRaisesRegex(RuntimeError, 'freshly built'):
            manage.prepare_summary()
        self.jar()
        with self.assertRaisesRegex(RuntimeError, 'configured AI'):
            manage.prepare_summary()
        self.assertFalse((self.runtime / 'summary-settings.json').exists())
        self.ai()
        with patch.object(manage, 'summary_python', side_effect=RuntimeError('environment missing')), patch.object(manage, 'export_ai_config') as export:
            with self.assertRaises(RuntimeError):
                manage.prepare_summary()
            export.assert_not_called()
        self.assertFalse((self.runtime / 'summary-settings.json').exists())
        with patch.object(manage, 'export_ai_config', side_effect=RuntimeError('unavailable')):
            with self.assertRaises(RuntimeError):
                manage.prepare_summary()
        self.assertFalse((self.runtime / 'summary-settings.json').exists())
        with patch.object(manage, 'export_ai_config', return_value='qwen-flash'):
            manage.prepare_summary()
        self.assertEqual(manage.summary_settings(), {'enabled': True})
        self.assertEqual((self.runtime / 'summary-settings.json').stat().st_mode & 0o777, 0o600)

    def test_start_refuses_disabled_or_world_readable_credentials(self):
        with self.assertRaisesRegex(RuntimeError, 'both be enabled'):
            manage.start_summary_worker()
        self.ai()
        manage.private_json('summary-settings.json', {'enabled': True})
        config = self.runtime / 'ai-worker.json'
        config.write_text('{}')
        config.chmod(0o644)
        with patch.object(manage.subprocess, 'Popen') as spawn:
            with self.assertRaisesRegex(RuntimeError, 'private AI'):
                manage.start_summary_worker()
            spawn.assert_not_called()

    def test_process_identity_and_exact_module_and_configuration_are_required(self):
        manage.private_json('summary-worker-process.json', {'pid': 321, 'identity': 'start'})
        result = type('Result', (), {'stdout': 'python -m ai_worker.summary --config ' + str(self.runtime / 'ai-worker.json')})()
        with patch.object(manage, 'process_identity', return_value='start'), patch.object(manage.subprocess, 'run', return_value=result):
            self.assertEqual(manage.summary_process()['pid'], 321)
            result.stdout = 'python -m ai_worker --config ' + str(self.runtime / 'ai-worker.json')
            self.assertIsNone(manage.summary_process())
            result.stdout = 'python -m ai_worker.summary --config /other/ai-worker.json'
            self.assertIsNone(manage.summary_process())
        with patch.object(manage, 'process_identity', return_value='reused-pid'):
            self.assertIsNone(manage.summary_process())

    def test_stop_does_not_signal_a_reused_pid_or_delete_its_manifest(self):
        manage.private_json('summary-worker-process.json', {'pid': 321, 'identity': 'old'})
        with patch.object(manage, 'process_identity', return_value='new'), patch.object(manage.os, 'killpg') as kill:
            with self.assertRaisesRegex(RuntimeError, 'identity mismatch'):
                manage.stop_summary_worker()
            kill.assert_not_called()
        self.assertTrue((self.runtime / 'summary-worker-process.json').exists())

    def test_ai_disable_stops_both_workers_and_keeps_summary_records_and_settings(self):
        self.ai()
        manage.private_json('summary-settings.json', {'enabled': True})
        calls = []
        with patch('sys.argv', ['manage.py', 'ai-disable']), patch.object(manage, 'stop_summary_worker', side_effect=lambda: calls.append('summary')), patch.object(manage, 'stop_process', side_effect=lambda *a: calls.append('ai')), patch.object(manage, 'recreate_api'):
            manage.main()
        self.assertEqual(calls, ['summary', 'ai'])
        self.assertTrue(manage.summary_settings()['enabled'])
        self.assertFalse(manage.ai_settings()['enabled'])
        self.assertNotIn('features.support-summary-assistance-enabled=true', (self.runtime / 'application-dev.properties').read_text())


if __name__ == '__main__':
    unittest.main()
