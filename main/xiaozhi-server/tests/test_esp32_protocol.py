import unittest
from core.esp32_protocol import ProtocolError, validate_hello, valid_opus_frame, MAX_OPUS_BYTES
from unittest.mock import patch
# Protocol tests exercise the real connection hook without loading unrelated plugins.
with patch("plugins_func.loadplugins.auto_import_modules"):
    from core.connection import ConnectionHandler, sanitized_connection_headers
from core.handle.helloHandle import handleHelloMessage


class ProtocolTests(unittest.TestCase):
    def hello(self):
        return {"type": "hello", "version": 1, "features": {"mcp": True}, "audio_params": {"format": "opus", "sample_rate": 16000, "channels": 1, "frame_duration": 60}}

    def test_log_header_allowlist_does_not_mutate_credentials(self):
        headers = {'Device-Id': 'device', 'Authorization': 'Bearer secret',
                   'Cookie': 'session=secret', 'X-ESP32-Gateway-Key': 'gateway-secret',
                   'X-AI-Worker-Key': 'worker-secret', 'X-Api-Key': 'api-secret',
                   'X-Unknown-Secret': 'unknown-secret'}
        original = headers.copy()
        safe = sanitized_connection_headers(headers)
        self.assertEqual(headers, original)
        self.assertEqual(safe['Device-Id'], 'device')
        for key in set(headers) - {'Device-Id'}:
            self.assertEqual(safe[key], '[REDACTED]')
        for secret in ('Bearer secret', 'session=secret', 'gateway-secret', 'worker-secret', 'api-secret', 'unknown-secret'):
            self.assertNotIn(secret, repr(safe))

    def test_firmware_hello_and_frames(self):
        validate_hello(self.hello())
        self.assertTrue(valid_opus_frame(b'\x01' * MAX_OPUS_BYTES))
        self.assertFalse(valid_opus_frame(b''))
        self.assertFalse(valid_opus_frame(b'\x01' * (MAX_OPUS_BYTES + 1)))

    def test_reject_unsupported_versions_and_audio(self):
        for key, value in [("version", 2), ("version", 3), ("version", True)]:
            hello = self.hello(); hello[key] = value
            with self.assertRaises(ProtocolError): validate_hello(hello)
        for key, value in [("format", "pcm"), ("sample_rate", 48000), ("channels", 2), ("frame_duration", 120)]:
            hello = self.hello(); hello["audio_params"][key] = value
            with self.assertRaises(ProtocolError): validate_hello(hello)


class WebSocketProtocolTests(unittest.IsolatedAsyncioTestCase):
    async def test_real_socket_hello_audio_and_rejected_version(self):
        import asyncio
        import json
        import queue
        from types import SimpleNamespace
        from unittest.mock import MagicMock
        import websockets

        received = []
        async def handler(ws):
            conn = SimpleNamespace(websocket=ws, logger=MagicMock(), welcome_msg={"type": "hello"},
                conn_from_mqtt_gateway=False, esp32_hello_complete=False,
                bind_completed_event=asyncio.Event(), need_bind=False, vad=object(), asr=object(),
                asr_audio_queue=queue.Queue(maxsize=1), _decode_opus_packet=lambda packet: received.append(packet) or b'pcm')
            conn.bind_completed_event.set()
            try:
                async for message in ws:
                    if isinstance(message, str):
                        await handleHelloMessage(conn, json.loads(message))
                    else:
                        await ConnectionHandler._route_message(conn, message)
            except websockets.exceptions.ConnectionClosed:
                pass

        async with websockets.serve(handler, '127.0.0.1', 0) as server:
            port = server.sockets[0].getsockname()[1]
            async with websockets.connect(f'ws://127.0.0.1:{port}') as client:
                hello = ProtocolTests().hello(); hello['features'] = {}
                await client.send(json.dumps(hello))
                self.assertEqual(json.loads(await client.recv())['type'], 'hello')
                await client.send(b'valid-opus')
                await client.send(b'queue-full')
                with self.assertRaises(websockets.exceptions.ConnectionClosed) as closed:
                    await client.recv()
                self.assertEqual(closed.exception.code, 1013)
            self.assertEqual(received, [b'valid-opus', b'queue-full'])
            async with websockets.connect(f'ws://127.0.0.1:{port}') as client:
                hello['version'] = 2
                await client.send(json.dumps(hello))
                with self.assertRaises(websockets.exceptions.ConnectionClosed) as closed:
                    await client.recv()
                self.assertEqual(closed.exception.code, 1008)
