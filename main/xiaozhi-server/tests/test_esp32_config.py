import copy
import unittest
from unittest.mock import AsyncMock, patch
from config.config_loader import get_config_from_api_async


class ConfigurationTests(unittest.IsolatedAsyncioTestCase):
    async def test_only_local_gateway_policy_survives_api_mode(self):
        remote = {'server': {'auth': {'enabled': True}, 'esp32': {'enabled': True, 'base_url': 'http://untrusted'}}, 'TTS': {'unchanged': True}}
        local = {'manager-api': {'url': 'http://localhost', 'secret': 'fixture'}, 'server': {
            'port': 19980, 'websocket': 'ws://localhost:19980/', 'auth_key': 'fixture',
            'auth': {'allowed_devices': ['device-one']}, 'esp32': {'enabled': True, 'gateway_id': 'canary'}}}
        original = copy.deepcopy(local)
        with patch('config.config_loader.init_service'), patch('config.config_loader.get_server_config', AsyncMock(return_value=remote)):
            result = await get_config_from_api_async(local)
        self.assertEqual(result['server']['esp32'], local['server']['esp32'])
        self.assertEqual(result['server']['auth']['allowed_devices'], ['device-one'])
        self.assertTrue(result['server']['auth']['enabled'])
        self.assertEqual(result['server']['websocket'], 'ws://localhost:19980/')
        self.assertEqual(result['TTS'], {'unchanged': True})
        self.assertEqual(local, original)

    async def test_remote_cannot_enable_gateway(self):
        with patch('config.config_loader.init_service'), patch('config.config_loader.get_server_config', AsyncMock(return_value={'server': {'esp32': {'enabled': True}}})):
            result = await get_config_from_api_async({'manager-api': {'url': 'http://localhost', 'secret': 'fixture'}})
        self.assertNotIn('esp32', result['server'])

    async def test_explicit_local_providers_never_reads_remote_provider_config(self):
        from config.config_loader import load_config
        local={'manager-api':{'url':'http://127.0.0.1:29993/xiaozhi'},'server':{'esp32':{'enabled':True,'local_providers':True}},'ASR':{'Workspace':{'type':'qwen_workspace'}}}
        with patch('core.utils.cache.manager.cache_manager.get',return_value=None),patch('core.utils.cache.manager.cache_manager.set'),patch('config.config_loader.read_config',side_effect=[{},local]),patch('config.config_loader.ensure_directories'),patch('config.config_loader.get_config_from_api_async',AsyncMock()) as api:
            result=await load_config()
        api.assert_not_called();self.assertFalse(result['read_config_from_api']);self.assertEqual(result['ASR'],local['ASR'])
