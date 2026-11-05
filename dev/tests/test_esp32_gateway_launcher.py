import importlib.util
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location('gateway_launcher',Path(__file__).parents[1]/'esp32_gateway.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class LauncherTests(unittest.TestCase):
 def config(self):return {'manager-api':{'url':'http://127.0.0.1:29993/xiaozhi','secret':'provider-secret'},'server':{'port':29980,'http_port':29983,'auth_key':'existing-ota-secret','auth':{'enabled':True},'esp32':{'enabled':True,'gateway_id':'isolated-canary','gateway_key':'gateway-secret-'*4,'base_url':'http://127.0.0.1:29993/xiaozhi','allowed_devices':['aa:bb:cc:dd:ee:ff']}}}
 def test_safe_private_plan(self):
  result=module.preflight(self.config());self.assertEqual(result['device_count'],1);self.assertFalse(result['automatic_ota_changes']);self.assertNotIn('secret',str(result))
 def test_native_ports_and_auth_bypass_rejected(self):
  for port in module.RESERVED:
   config=self.config();config['server']['port']=port
   with self.assertRaises(ValueError):module.preflight(config)
  config=self.config();config['server']['auth']['allowed_devices']=['aa:bb']
  with self.assertRaises(ValueError):module.preflight(config)
 def test_lan_candidate_is_explicit_and_private_only(self):
  config=self.config();config['server']['esp32']['deployment_profile']='lan_candidate';config['server']['esp32']['base_url']='http://192.168.3.152:29993/xiaozhi';config['manager-api']['url']=config['server']['esp32']['base_url'];self.assertEqual(module.preflight(config)['deployment_profile'],'lan_candidate')
  config['server']['esp32']['deployment_profile']='formal'
  with self.assertRaises(ValueError):module.preflight(config)
 def test_explicit_canary_and_same_manager_required(self):
  config=self.config();config['server']['esp32']['allowed_devices']=[]
  with self.assertRaises(ValueError):module.preflight(config)
  config=self.config();config['manager-api']['url']='http://127.0.0.1:18882/xiaozhi'
  with self.assertRaises(ValueError):module.preflight(config)
if __name__=='__main__':unittest.main()
