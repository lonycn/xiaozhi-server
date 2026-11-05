import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
def module(name):
    spec=importlib.util.spec_from_file_location(name,ROOT/(name+'.py'))
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result

cli=module('esp32_firmware')

class FirmwareCliBoundaryTest(unittest.TestCase):
    def test_no_http_or_url_credentials_without_explicit_private_development(self):
        for url in ['http://192.168.3.152:18993/xiaozhi','https://user:secret@example.com/xiaozhi','https://example.com/xiaozhi?q=1','https://example.com/#token']:
            with self.assertRaises(ValueError):cli.trusted_base(url)
        self.assertEqual(cli.trusted_base('http://192.168.3.152:18993/xiaozhi',True),'http://192.168.3.152:18993/xiaozhi')
        for url in ['http://8.8.8.8/xiaozhi','http://127.0.0.1.example.com/xiaozhi','http://100.64.0.1/xiaozhi']:
            with self.assertRaises(ValueError):cli.trusted_base(url,True)

    def test_public_token_file_rejected_before_authentication(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'token';path.write_text('fixture-private-token');path.chmod(0o644)
            with self.assertRaisesRegex(ValueError,'private'):cli.Client('https://example.com/xiaozhi',path)

class OtaProxyBoundaryTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        from aiohttp import web
        from aiohttp.test_utils import TestClient,TestServer
        self.calls=[]
        async def manager(request):
            self.calls.append((request.path,request.headers.get('Authorization'),await request.read()))
            if request.path.endswith('/receipts'):return web.Response(status=204)
            if request.query.get('ticket')=='redirect':return web.Response(status=302,headers={'Location':'https://different-host.invalid/'})
            if request.path.endswith('/app'):return web.Response(body=b'fixture-app',content_type='application/octet-stream')
            return web.json_response({'firmware':None})
        app=web.Application();app.router.add_route('*','/{tail:.*}',manager)
        self.manager=TestServer(app);await self.manager.start_server()
        proxy=module('esp32_ota_proxy')
        self.client=TestClient(TestServer(proxy.app_for(str(self.manager.make_url('/xiaozhi')))))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close();await self.manager.close()

    async def test_admin_config_and_arbitrary_routes_never_reach_manager(self):
        for path in ['/xiaozhi/api/v1/esp32/firmware/releases','/xiaozhi/config/server-base','/admin','/xiaozhi/ota/?other=1']:
            response=await self.client.post(path)
            self.assertEqual(response.status,404)
        self.assertEqual(self.calls,[])

    async def test_device_json_receipt_and_binary_are_relayed_without_redirect(self):
        response=await self.client.post('/xiaozhi/ota/',json={'version':2},headers={'Device-Id':'fixture'})
        self.assertEqual(response.status,200);self.assertEqual((await response.json())['firmware'],None)
        response=await self.client.post('/xiaozhi/ota/esp32/receipts',json={'status':'booted'},headers={'Authorization':'Bearer fixture-only'})
        self.assertEqual(response.status,204);self.assertEqual(self.calls[-1][1],'Bearer fixture-only')
        path='/xiaozhi/ota/esp32/releases/'+'a'*32+'/app'
        response=await self.client.get(path+'?ticket=fixture')
        self.assertEqual(response.status,200);self.assertEqual(await response.read(),b'fixture-app');self.assertEqual(response.headers['Cache-Control'],'private, no-store')
        response=await self.client.get(path+'?ticket=redirect');self.assertEqual(response.status,502)

    async def test_large_receipt_rejected_without_forwarding(self):
        response=await self.client.post('/xiaozhi/ota/esp32/receipts',data=b'x'*16385)
        self.assertEqual(response.status,413);self.assertEqual(self.calls,[])

if __name__=='__main__':unittest.main()
