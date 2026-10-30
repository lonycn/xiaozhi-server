import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from types import SimpleNamespace
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('dev_media_runtime', Path(__file__).resolve().parents[1] / 'manage.py')
manage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(manage)


class MediaRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.override = patch.object(manage, 'RUNTIME', self.root)
        self.override.start()
        self.addCleanup(self.override.stop)
        self.asr = {'type': 'qwen_workspace', 'model_name': 'qwen3-asr-flash',
                    'base_url': 'https://unit.maas.aliyuncs.com/compatible-mode/v1', 'api_key': 'synthetic-asr-key'}

    def mock_db(self):
        return patch.object(manage, 'compose', return_value=SimpleNamespace(stdout=json.dumps(self.asr)))

    def storage_file(self, bucket='unit-bucket', endpoint='http://127.0.0.1:18333'):
        path = self.root / 'source.env'
        path.write_text('S3_ENDPOINT=' + endpoint + '\nS3_ACCESS_KEY=synthetic-access\nS3_SECRET_KEY=synthetic-secret\nS3_BUCKET=' + bucket + '\nS3_REGION=us-east-1\n')
        return str(path)

    def test_default_off_does_not_open_media_or_model_source(self):
        with patch.object(manage, 'compose') as db:
            manage.configure()
        db.assert_not_called()
        self.assertNotIn('features.attachments-enabled=true', (self.root / 'application-dev.properties').read_text())

    def test_annotations_require_media_and_do_not_mutate_configuration_on_failure(self):
        with self.assertRaisesRegex(RuntimeError, 'require enabled media'):
            manage.set_annotations_enabled(True)
        self.assertFalse((self.root / 'media-settings.json').exists())
        manage.private_json('media-settings.json', {'enabled': True, 'storage': 's3'})
        before = (self.root / 'media-settings.json').read_bytes()
        with self.assertRaisesRegex(RuntimeError, 'worker configuration'):
            manage.set_annotations_enabled(True)
        manage.private_json('media-worker.json', {'annotation_font': str(self.root / 'missing.ttf')})
        with self.assertRaisesRegex(RuntimeError, 'font is missing'):
            manage.set_annotations_enabled(True)
        self.assertEqual((self.root / 'media-settings.json').read_bytes(), before)

    def test_annotations_opt_in_reuses_private_media_and_disables_without_data_change(self):
        with self.mock_db():
            manage.export_media_config('s3', self.storage_file())
        font = self.root / 'fixed.ttf'
        font.write_bytes(b'local-font-placeholder')
        worker_path = self.root / 'media-worker.json'
        worker = json.loads(worker_path.read_text())
        worker['annotation_font'] = str(font)
        manage.private_json('media-worker.json', worker)
        storage = (self.root / 'media-storage.json').read_bytes()
        worker_before = worker_path.read_bytes()
        manage.configure()
        self.assertNotIn('features.annotations-enabled=true', (self.root / 'application-dev.properties').read_text())
        manage.set_annotations_enabled(True)
        manage.configure()
        self.assertIn('features.annotations-enabled=true', (self.root / 'application-dev.properties').read_text())
        self.assertEqual((self.root / 'media-storage.json').read_bytes(), storage)
        self.assertEqual(worker_path.read_bytes(), worker_before)
        self.assertEqual((self.root / 'media-settings.json').stat().st_mode & 0o777, 0o600)
        manage.set_annotations_enabled(False)
        manage.configure()
        self.assertNotIn('features.annotations-enabled=true', (self.root / 'application-dev.properties').read_text())
        self.assertEqual((self.root / 'media-storage.json').read_bytes(), storage)

    def test_annotations_use_worker_default_font_when_not_overridden(self):
        with self.mock_db():
            manage.export_media_config()
        font = self.root / 'default.ttf'
        font.write_bytes(b'local-font-placeholder')
        with patch.object(manage, 'ANNOTATION_DEFAULT_FONT', font):
            manage.set_annotations_enabled(True)
        self.assertTrue(manage.media_settings()['annotations_enabled'])

    def rtc_source(self, internal='http://127.0.0.1:17880', public='http://example.test:17880', key='synthetic-key', secret='synthetic-secret'):
        path = self.root / 'rtc-source.env'
        path.write_text(f'LIVEKIT_INTERNAL_URL={internal}\nLIVEKIT_URL={public}\nLIVEKIT_API_KEY={key}\nLIVEKIT_API_SECRET={secret}\n')
        return str(path)

    def test_rtc_opt_in_preserves_private_source_and_room_prefix(self):
        manage.configure()
        self.assertNotIn('features.support-media-enabled=true', (self.root / 'application-dev.properties').read_text())
        source = self.rtc_source()
        manage.enable_rtc(source, 'https://rtc.example.test')
        settings = manage.rtc_settings()
        self.assertEqual(settings['internal-url'], 'http://host.docker.internal:17880')
        self.assertEqual(settings['public-url'], 'wss://rtc.example.test')
        self.assertEqual((self.root / 'rtc-settings.json').stat().st_mode & 0o777, 0o600)
        manage.configure()
        self.assertIn('features.support-media-enabled=true', (self.root / 'application-dev.properties').read_text())
        self.assertIn('support-media.livekit.room-prefix=' + settings['room-prefix'], (self.root / 'application-dev.properties').read_text())
        manage.enable_rtc(source, 'wss://new.example.test')
        self.assertEqual(manage.rtc_settings()['room-prefix'], settings['room-prefix'])
        api = self.root / 'api'
        (api / 'target').mkdir(parents=True)
        jar = api / 'target/xiaozhi-esp32-api.jar'
        with patch.object(manage, 'API', api):
            with self.assertRaisesRegex(RuntimeError, 'drain support'):
                manage.disable_rtc()
            self.assertTrue(manage.rtc_settings()['enabled'])
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('BOOT-INF/classes/xiaozhi/modules/supportmedia/MediaDrainController.class', b'test-marker')
            manage.disable_rtc()
        manage.configure()
        props = (self.root / 'application-dev.properties').read_text()
        self.assertNotIn('features.support-media-enabled=true', props)
        self.assertIn('support-media.livekit.room-prefix=' + settings['room-prefix'], props)
        self.assertIn('support-media.livekit.api-key=synthetic-key', props)
        self.assertFalse(manage.rtc_settings()['enabled'])
        manage.enable_rtc()
        self.assertEqual(manage.rtc_settings()['room-prefix'], settings['room-prefix'])
        before = (self.root / 'rtc-settings.json').read_bytes()
        with self.assertRaisesRegex(RuntimeError, 'redirect'):
            manage.enable_rtc(self.rtc_source(internal='http://other.test:17880'))
        self.assertEqual((self.root / 'rtc-settings.json').read_bytes(), before)

    def test_rtc_invalid_config_fails_closed_without_snapshot(self):
        for kw in ({'internal': 'http://user:pass@example.test'}, {'secret': ''},
                   {'public': 'https://example.test/?token=secret'}, {'key': 'bad\\key'}):
            with self.subTest(kw=kw), self.assertRaises(RuntimeError):
                manage.enable_rtc(self.rtc_source(**kw))
            self.assertFalse((self.root / 'rtc-settings.json').exists())
        with self.assertRaisesRegex(RuntimeError, 'requires --rtc-env'):
            manage.enable_rtc()

    def test_existing_asr_credentials_exported_privately_without_db_writes(self):
        with self.mock_db() as db:
            manage.export_media_config(vision_model='qwen3.8-flash')
        worker = json.loads((self.root / 'media-worker.json').read_text())
        self.assertEqual(worker['asr_base_url'], self.asr['base_url'])
        self.assertTrue(manage.media_settings()['transcription'])
        self.assertEqual(manage.media_settings()['vision_model'], 'qwen3.8-flash')
        self.assertIn('SELECT config_json', db.call_args.args[-1])
        for filename in ('media-worker-key', 'media-ticket-key', 'media-worker.json', 'media-settings.json'):
            self.assertEqual((self.root / filename).stat().st_mode & 0o777, 0o600)

    def test_unsupported_asr_does_not_claim_transcription_available(self):
        self.asr['base_url'] = 'https://dashscope.aliyuncs.com.evil.test/v1'
        with self.mock_db():
            manage.export_media_config()
        self.assertFalse(manage.media_settings()['transcription'])
        self.assertNotIn('asr_api_key', json.loads((self.root / 'media-worker.json').read_text()))

    def test_s3_source_preserves_location_and_reuses_own_prefix(self):
        with self.mock_db():
            manage.export_media_config('s3', self.storage_file())
            first = json.loads((self.root / 'media-storage.json').read_text())
            manage.export_media_config('s3', self.storage_file())
        second = json.loads((self.root / 'media-storage.json').read_text())
        self.assertEqual(first, second)
        self.assertEqual(first['endpoint'], 'http://host.docker.internal:18333')
        self.assertTrue(first['prefix'].startswith('xiaozhi-dev/'))
        self.assertTrue(first['prefix'].endswith('/'))
        self.assertEqual((self.root / 'media-storage.json').stat().st_mode & 0o777, 0o600)

    def test_refuses_switching_store_or_redirecting_existing_objects(self):
        with self.mock_db():
            manage.export_media_config('s3', self.storage_file())
            original = (self.root / 'media-storage.json').read_bytes()
            with self.assertRaisesRegex(RuntimeError, 'switched'):
                manage.export_media_config('filesystem')
            with self.assertRaisesRegex(RuntimeError, 'redirect'):
                manage.export_media_config('s3', self.storage_file(bucket='another-bucket'))
        self.assertEqual((self.root / 'media-storage.json').read_bytes(), original)

    def test_unsafe_storage_url_rejected_before_storage_file_written(self):
        with self.mock_db(), self.assertRaisesRegex(RuntimeError, 'endpoint'):
            manage.export_media_config('s3', self.storage_file(endpoint='https://user:secret@example.test/'))
        self.assertFalse((self.root / 'media-storage.json').exists())

    def test_enabled_properties_require_separate_worker_and_ticket_keys(self):
        with self.mock_db():
            manage.export_media_config()
        manage.configure()
        props = (self.root / 'application-dev.properties').read_text()
        self.assertIn('features.attachments-enabled=true', props)
        self.assertIn('media.transcription-enabled=true', props)
        self.assertNotEqual((self.root / 'media-worker-key').read_text(), (self.root / 'media-ticket-key').read_text())
        (self.root / 'media-worker-key').write_text('short')
        with self.assertRaisesRegex(RuntimeError, 'keys'):
            manage.configure()


if __name__ == '__main__':
    unittest.main()
