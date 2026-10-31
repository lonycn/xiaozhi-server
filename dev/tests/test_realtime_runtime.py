import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('realtime_dev_manage', Path(__file__).resolve().parents[1] / 'manage.py')
manage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(manage)


class RealtimeConfigTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.runtime = self.root / 'runtime'
        self.runtime.mkdir()
        self.api = self.root / 'api'
        (self.api / 'target').mkdir(parents=True)
        for name, value in [('RUNTIME', self.runtime), ('API', self.api)]:
            override = patch.object(manage, name, value)
            override.start()
            self.addCleanup(override.stop)
        self.jar = self.api / 'target/xiaozhi-esp32-api.jar'

    def jar_with_realtime(self, vad=True):
        with zipfile.ZipFile(self.jar, 'w') as jar:
            for name in ('RealtimeController', 'RealtimeMaintenance'):
                jar.writestr('BOOT-INF/classes/xiaozhi/modules/realtime/' + name + '.class', b'test')
            if vad:
                jar.writestr('BOOT-INF/classes/db/changelog/realtime-vad.changelog.yaml', b'test')

    def source(self, base='https://workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1', **extra):
        manage.private_json('ai-worker.json', {'base_url': base, 'api_key': 'synthetic-provider-key', **extra})

    def test_default_off_and_missing_requirements_fail_closed(self):
        manage.configure()
        self.assertNotIn('features.realtime-ai-enabled=true', (self.runtime / 'application-dev.properties').read_text())
        with self.assertRaisesRegex(RuntimeError, 'built API'):
            manage.prepare_realtime()
        self.jar_with_realtime()
        with self.assertRaisesRegex(RuntimeError, 'private workspace'):
            manage.prepare_realtime()
        self.assertFalse((self.runtime / 'realtime-settings.json').exists())

    def test_config_private_stable_and_provider_key_only_worker(self):
        self.jar_with_realtime()
        self.source()
        manage.prepare_realtime()
        settings = manage.realtime_settings()
        worker = json.loads((self.runtime / 'realtime-worker.json').read_text())
        self.assertEqual(worker['provider_url'], 'wss://workspace.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime')
        self.assertEqual(worker['origins'], ['http://127.0.0.1:18894', 'http://localhost:18894'])
        self.assertEqual(worker['server_id'], settings['server_id'])
        self.assertEqual(worker['worker_key'], settings['worker_key'])
        self.assertNotEqual(worker['api_key'], worker['worker_key'])
        for name in ('realtime-settings.json', 'realtime-worker.json'):
            self.assertEqual((self.runtime / name).stat().st_mode & 0o777, 0o600)
        manage.configure()
        props = (self.runtime / 'application-dev.properties').read_text()
        self.assertIn('features.realtime-ai-enabled=true', props)
        self.assertIn('realtime.worker.public-url=ws://127.0.0.1:18899/realtime', props)
        self.assertNotIn('synthetic-provider-key', props)
        manage.prepare_realtime('wss://rt.example.test/realtime')
        updated = manage.realtime_settings()
        self.assertEqual(updated['server_id'], settings['server_id'])
        self.assertEqual(updated['worker_key'], settings['worker_key'])
        self.assertEqual(updated['public_url'], 'wss://rt.example.test/realtime')

    def test_provider_and_public_url_fail_without_mutating_snapshot(self):
        self.jar_with_realtime()
        self.source('https://dashscope.aliyuncs.com/compatible-mode/v1', workspace_id='ws-unit')
        manage.prepare_realtime()
        self.assertEqual(json.loads((self.runtime / 'realtime-worker.json').read_text())['provider_url'],
                         'wss://ws-unit.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime')
        before = (self.runtime / 'realtime-settings.json').read_bytes()
        with self.assertRaisesRegex(RuntimeError, 'WebSocket URL'):
            manage.prepare_realtime('ws://remote.example.test/realtime?ticket=secret')
        self.source('https://untrusted.example.test/v1')
        with self.assertRaisesRegex(RuntimeError, 'private workspace'):
            manage.prepare_realtime()
        self.assertEqual((self.runtime / 'realtime-settings.json').read_bytes(), before)

    def test_server_vad_explicit_enable_preserve_disable_and_invalid_value(self):
        self.jar_with_realtime()
        self.source()
        manage.prepare_realtime()
        manage.configure()
        props = self.runtime / 'application-dev.properties'
        self.assertNotIn('features.realtime-server-vad-enabled=true', props.read_text())
        manage.prepare_realtime(server_vad=True)
        manage.prepare_realtime('wss://rt.example.test/realtime')
        self.assertTrue(manage.realtime_settings()['server_vad_enabled'])
        manage.configure()
        self.assertIn('features.realtime-server-vad-enabled=true', props.read_text())
        before = (self.runtime / 'realtime-settings.json').read_bytes()
        with self.assertRaisesRegex(RuntimeError, 'server VAD'):
            manage.prepare_realtime(server_vad='true')
        self.assertEqual((self.runtime / 'realtime-settings.json').read_bytes(), before)
        manage.prepare_realtime(server_vad=False)
        manage.configure()
        self.assertNotIn('features.realtime-server-vad-enabled=true', props.read_text())
        settings = manage.realtime_settings()
        settings['server_vad_enabled'] = 'true'
        with self.assertRaisesRegex(RuntimeError, 'server VAD'):
            manage.validate_realtime_settings(settings)

    def test_old_realtime_jar_cannot_silently_enable_vad_or_mutate_settings(self):
        self.jar_with_realtime(vad=False)
        self.source()
        manage.prepare_realtime()
        before = (self.runtime / 'realtime-settings.json').read_bytes()
        with self.assertRaisesRegex(RuntimeError, 'freshly built API'):
            manage.prepare_realtime(server_vad=True)
        self.assertEqual((self.runtime / 'realtime-settings.json').read_bytes(), before)

    def test_stop_rejects_live_process_with_mismatched_identity(self):
        manage.private_json('realtime-worker-process.json', {'pid': 321, 'identity': 'old-start'})
        with patch.object(manage, 'process_identity', return_value='other-start'), patch.object(manage.os, 'killpg') as kill:
            with self.assertRaisesRegex(RuntimeError, 'identity mismatch'):
                manage.stop_realtime_worker()
            kill.assert_not_called()
        self.assertTrue((self.runtime / 'realtime-worker-process.json').exists())

    def test_process_pid_and_command_must_belong_to_checkout(self):
        manage.private_json('realtime-worker-process.json', {'pid': 321, 'identity': 'start-time'})
        result = type('Result', (), {'stdout': 'python -m realtime_worker --config ' + str(self.runtime / 'realtime-worker.json')})()
        with patch.object(manage, 'process_identity', return_value='start-time'), patch.object(manage.subprocess, 'run', return_value=result):
            self.assertEqual(manage.realtime_process()['pid'], 321)
            result.stdout = 'python -m realtime_worker --config /other/runtime/realtime-worker.json'
            self.assertIsNone(manage.realtime_process())
        with patch.object(manage, 'process_identity', return_value='different'):
            self.assertIsNone(manage.realtime_process())


if __name__ == '__main__':
    unittest.main()
