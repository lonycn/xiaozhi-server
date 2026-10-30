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


if __name__ == '__main__':
    unittest.main()
