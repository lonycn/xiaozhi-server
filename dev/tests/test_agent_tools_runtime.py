import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('agent_tools_manage', Path(__file__).resolve().parents[1] / 'manage.py')
manage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(manage)


class AgentToolsRuntimeTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        self.runtime = root / 'runtime'; self.runtime.mkdir()
        self.api = root / 'api'; (self.api / 'target').mkdir(parents=True)
        for name, value in [('RUNTIME', self.runtime), ('API', self.api)]:
            override = patch.object(manage, name, value); override.start(); self.addCleanup(override.stop)

    def ai(self, enabled=True):
        manage.private_json('ai-settings.json', {'enabled': enabled, 'model': 'qwen-flash'})
        (self.runtime / 'ai-worker-key').write_text('private-worker-key')
        manage.private_json('ai-worker.json', {'fixture': 'preserve-private-config'})

    def jar(self, include=True):
        with zipfile.ZipFile(self.api / 'target/xiaozhi-esp32-api.jar', 'w') as archive:
            if include:
                for name in ['xiaozhi/modules/agenttool/AgentToolService.class', 'xiaozhi/modules/ai/AiProviderUsageStore.class', 'db/changelog/agent-tools.changelog.yaml']:
                    archive.writestr('BOOT-INF/classes/' + name, b'fixture')

    def properties(self):
        manage.configure()
        return (self.runtime / 'application-dev.properties').read_text()

    def test_defaults_off_and_gated_by_ai(self):
        self.assertEqual(manage.agent_tools_settings(), {'enabled': False, 'technical_local': False})
        self.assertNotIn('features.agent-tools-enabled', self.properties())
        manage.private_json('agent-tools-settings.json', {'enabled': True, 'technical_local': True})
        self.ai(False)
        self.assertNotIn('features.agent-tools-enabled', self.properties())
        self.assertNotIn('agent-tools.technical-local-enabled', self.properties())
        self.ai(True)
        props = self.properties()
        self.assertIn('features.agent-tools-enabled=true', props)
        self.assertIn('agent-tools.technical-local-enabled=true', props)

    def test_prepare_requires_updated_jar_ai_and_private_config_without_persisting_on_failure(self):
        self.ai(); self.jar(False)
        with self.assertRaisesRegex(RuntimeError, 'freshly built'):
            manage.prepare_agent_tools()
        self.assertFalse((self.runtime / 'agent-tools-settings.json').exists())
        self.jar(); self.ai(False)
        with self.assertRaisesRegex(RuntimeError, 'AI worker'):
            manage.prepare_agent_tools()
        self.ai()
        with patch.object(manage.subprocess, 'run'):
            manage.prepare_agent_tools()
        self.assertEqual(manage.agent_tools_settings(), {'enabled': True, 'technical_local': False})
        self.assertEqual((self.runtime / 'agent-tools-settings.json').stat().st_mode & 0o777, 0o600)
        self.assertEqual(json.loads((self.runtime / 'ai-worker.json').read_text()), {'fixture': 'preserve-private-config'})
        self.assertNotIn('agent-tools.technical-local-enabled', self.properties())

    def test_explicit_technical_opt_in_and_disable_preserve_private_configuration(self):
        self.ai(); self.jar()
        with patch.object(manage.subprocess, 'run'):
            manage.prepare_agent_tools(True)
        self.assertTrue(manage.agent_tools_settings()['technical_local'])
        with patch('sys.argv', ['manage.py', 'agent-tools-disable']), patch.object(manage, 'stop_process'), patch.object(manage, 'recreate_api'), patch.object(manage, 'start_ai_worker'):
            manage.main()
        self.assertEqual(manage.agent_tools_settings(), {'enabled': False, 'technical_local': False})
        self.assertEqual(json.loads((self.runtime / 'ai-worker.json').read_text()), {'fixture': 'preserve-private-config'})
        self.assertNotIn('features.agent-tools-enabled', self.properties())

    def test_invalid_and_insecure_settings_fail_closed(self):
        manage.private_json('agent-tools-settings.json', {'enabled': True, 'technical_local': 'true'})
        with self.assertRaisesRegex(RuntimeError, 'Invalid agent tools settings'):
            manage.agent_tools_settings()
        self.ai(); self.jar(); (self.runtime / 'ai-worker.json').chmod(0o644)
        with patch.object(manage.subprocess, 'run'), self.assertRaisesRegex(RuntimeError, 'private AI'):
            manage.prepare_agent_tools(True)

    def test_enable_cli_requires_explicit_flag_for_local_tools(self):
        self.ai(); self.jar()
        with patch.object(manage.subprocess, 'run'), patch.object(manage, 'stop_process'), patch.object(manage, 'recreate_api'), patch.object(manage, 'start_ai_worker'):
            with patch('sys.argv', ['manage.py', 'agent-tools-enable']): manage.main()
            self.assertFalse(manage.agent_tools_settings()['technical_local'])
            with patch('sys.argv', ['manage.py', 'agent-tools-enable', '--technical-local']): manage.main()
            self.assertTrue(manage.agent_tools_settings()['technical_local'])


if __name__ == '__main__':
    unittest.main()
