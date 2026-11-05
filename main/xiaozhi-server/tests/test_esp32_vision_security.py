import unittest
from unittest.mock import patch, MagicMock
from aiohttp import web, FormData
from aiohttp.test_utils import TestClient, TestServer
from core.api.vision_handler import VisionHandler, MAX_QUESTION_SIZE, MAX_FILE_SIZE


class Provider:
    def response(self, question, image):
        return "mock result"


class VisionTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.logger_patch = patch("core.api.base_handler.setup_logging", return_value=MagicMock())
        self.logger_patch.start()
        self.handler = VisionHandler({"server": {"auth_key": "unit-test-key-at-least-32-bytes-long"}, "selected_module": {"VLLM": "mock"}, "VLLM": {"mock": {}}})
        app = web.Application(client_max_size=MAX_FILE_SIZE * 3)
        app.router.add_post('/vision', self.handler.handle_post)
        self.client = TestClient(TestServer(app))
        await self.client.start_server()
        self.token = self.handler.auth.generate_token("device-a")
        self.provider = patch('core.api.vision_handler.create_instance', return_value=Provider())
        self.mock = self.provider.start()

    async def asyncTearDown(self):
        self.provider.stop()
        self.logger_patch.stop()
        await self.client.close()

    def form(self, question='hello', image=b'\x89PNG\r\n\x1a\n' + b'\0' * 30):
        data = FormData(); data.add_field('question', question); data.add_field('file', image, filename='image.png', content_type='image/png')
        return data

    async def post(self, headers, form=None):
        return await self.client.post('/vision', data=form or self.form(), headers=headers)

    async def test_auth_and_device_binding(self):
        response = await self.post({'Client-Id': 'web_test_client', 'Device-Id': 'device-a'})
        self.assertEqual(response.status, 401)
        response = await self.post({'Authorization': 'Bearer invalid', 'Device-Id': 'device-a'})
        self.assertEqual(response.status, 401)
        response = await self.post({'Authorization': 'Bearer ' + self.token, 'Device-Id': 'device-b'})
        self.assertEqual(response.status, 403)
        self.mock.assert_not_called()

    async def test_expired_token_and_chunked_oversize(self):
        import jwt
        import time
        auth = self.handler.auth
        payload = auth._encrypt_payload({"device_id": "device-a", "exp": time.time() - 10})
        expired = jwt.encode({"data": payload}, auth.secret_key, algorithm="HS256")
        response = await self.post({"Authorization": "Bearer " + expired, "Device-Id": "device-a"})
        self.assertEqual(response.status, 401)
        boundary = "bounded-test-boundary"
        async def chunks():
            yield (f"--{boundary}\r\nContent-Disposition: form-data; name=\"question\"\r\n\r\n").encode()
            for _ in range(3):
                yield b"x" * 4096
            yield (f"\r\n--{boundary}--\r\n").encode()
        response = await self.client.post('/vision', data=chunks(), headers={"Authorization": "Bearer " + self.token, "Device-Id": "device-a", "Content-Type": "multipart/form-data; boundary=" + boundary})
        self.assertEqual(response.status, 413)
        self.mock.assert_not_called()

    async def test_valid_legacy_token_and_bounds(self):
        headers = {'Authorization': 'Bearer ' + self.token, 'Device-Id': 'device-a'}
        with patch('core.api.vision_handler.is_valid_image_file', return_value=True):
            response = await self.post(headers)
            self.assertEqual(response.status, 200)
            self.assertEqual((await response.json())['response'], 'mock result')
            self.mock.reset_mock()
            response = await self.post(headers, self.form(question='x' * (MAX_QUESTION_SIZE + 1)))
            self.assertEqual(response.status, 413)
            response = await self.post(headers, self.form(image=b'x' * (MAX_FILE_SIZE + 1)))
            self.assertEqual(response.status, 413)
            self.mock.assert_not_called()
