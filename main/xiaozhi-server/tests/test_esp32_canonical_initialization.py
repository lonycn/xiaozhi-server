import unittest
from unittest.mock import MagicMock, patch
from core.websocket_server import WebSocketServer
class InitializationTests(unittest.TestCase):
 def test_canonical_does_not_construct_legacy_executors(self):
  for enabled in (True,False):
   config={'server':{'auth_key':'isolated-fixture-key','esp32':{'enabled':enabled,'execution_mode':'ai_worker'}},'selected_module':{'VAD':'v','ASR':'a','LLM':'l','Memory':'m','Intent':'i'}}
   with patch('core.websocket_server.setup_logging',return_value=MagicMock()),patch('core.websocket_server.initialize_modules',return_value={}) as initialize:
    server=WebSocketServer(config)
   arguments=initialize.call_args.args
   self.assertEqual(arguments[4],not enabled);self.assertEqual(arguments[6],not enabled);self.assertEqual(arguments[7],not enabled)
