import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

spec = importlib.util.spec_from_file_location('manage', Path(__file__).parents[1] / 'manage.py')
manage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(manage)


class CandidateSafetyTests(unittest.TestCase):
    def test_stop_reused_pid_does_not_signal(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(manage, 'RUNTIME', Path(tmp)):
            (Path(tmp) / 'candidate-process.json').write_text(json.dumps({'pid': 123, 'identity': 'old'}))
            with patch.object(manage, 'configure'), patch.object(manage, 'process_identity', return_value='new'), \
                 patch.object(manage.os, 'killpg') as kill, patch('sys.argv', ['manage.py', 'candidate-stop']):
                manage.main()
                kill.assert_not_called()

    def test_start_refuses_occupied_port(self):
        with tempfile.TemporaryDirectory() as tmp:
            candidate = Path(tmp)
            (candidate / 'node_modules').mkdir()
            sock = MagicMock()
            sock.__enter__.return_value.connect_ex.return_value = 0
            with patch.object(manage, 'CANDIDATE', candidate), patch.object(manage, 'candidate_process', return_value=None), \
                 patch.object(manage.socket, 'socket', return_value=sock), patch.object(manage.subprocess, 'Popen') as popen:
                with self.assertRaisesRegex(RuntimeError, '18894'):
                    manage.start_candidate()
                popen.assert_not_called()



class CommandTests(unittest.TestCase):
    def run_action(self, action):
        with tempfile.TemporaryDirectory() as tmp, patch.object(manage, 'RUNTIME', Path(tmp)), \
             patch('sys.argv', ['manage.py', action]), patch.object(manage, 'configure'), \
             patch.object(manage, 'compose') as compose, patch.object(manage, 'start_candidate') as candidate, \
             patch.object(manage, 'start_web') as legacy, patch.object(manage, 'wait_http') as wait, \
             patch.object(manage, 'candidate_process', return_value={'pid': 11}), \
             patch.object(manage, 'web_process', return_value={'pid': 12}), \
             patch.object(manage.os, 'killpg') as kill, patch.object(manage, 'API', Path('/nonexistent')), \
             patch.object(manage, 'build'), patch.object(manage, 'stage_api_jar'):
            manage.main()
            return compose, candidate, legacy, wait, kill

    def test_default_up_uses_vue3(self):
        _, candidate, legacy, wait, _ = self.run_action('up')
        candidate.assert_called_once()
        legacy.assert_not_called()
        wait.assert_any_call(manage.CANDIDATE_URL)

    def test_stop_stops_both_and_containers(self):
        compose, _, _, _, kill = self.run_action('stop')
        self.assertEqual([call.args[0] for call in kill.call_args_list], [11, 12])
        compose.assert_called_once_with('stop')

    def test_candidate_alias_and_legacy(self):
        _, candidate, legacy, wait, _ = self.run_action('candidate-up')
        candidate.assert_called_once()
        legacy.assert_not_called()
        wait.assert_called_once_with(manage.CANDIDATE_URL)
        _, candidate, legacy, wait, _ = self.run_action('legacy-up')
        candidate.assert_not_called()
        legacy.assert_called_once()
        wait.assert_called_once_with(manage.WEB_URL)
        _, _, _, _, kill = self.run_action('legacy-stop')
        kill.assert_called_once_with(12, manage.signal.SIGTERM)

    def test_legacy_reused_pid_does_not_signal(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(manage, 'RUNTIME', Path(tmp)):
            (Path(tmp) / 'web-process.json').write_text(json.dumps({'pid': 123, 'identity': 'old'}))
            with patch.object(manage, 'configure'), patch.object(manage, 'process_identity', return_value='new'), \
                 patch.object(manage.os, 'killpg') as kill, patch('sys.argv', ['manage.py', 'legacy-stop']):
                manage.main()
                kill.assert_not_called()

    def test_build_refuses_linked_dependencies(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'target').mkdir()
            (root / 'node_modules').symlink_to(root / 'target')
            with patch.object(manage, 'CANDIDATE', root), patch.object(manage, 'RUNTIME', root), \
                 patch.object(manage.subprocess, 'run') as run:
                with self.assertRaisesRegex(RuntimeError, 'Linked'):
                    manage.build()
                self.assertEqual(run.call_count, 1)

if __name__ == '__main__':
    unittest.main()
