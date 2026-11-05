import asyncio
import base64
import hashlib
import time
import unittest

from core.providers.tts.alibl_stream import TTSProvider
from core.utils.alibl_endpoint import build_ws_connect_options


class TtsLatencyTests(unittest.IsolatedAsyncioTestCase):
    async def test_unresponsive_old_transport_cannot_delay_the_next_turn_ten_seconds(self):
        async def peer(reader, writer):
            try:
                request = (await reader.readuntil(b"\r\n\r\n")).decode("ascii")
                key = next(line.split(": ", 1)[1] for line in request.splitlines()
                           if line.lower().startswith("sec-websocket-key:"))
                digest = hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()
                accept = base64.b64encode(digest).decode("ascii")
                writer.write(("HTTP/1.1 101 Switching Protocols\r\n"
                              "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                              f"Sec-WebSocket-Accept: {accept}\r\n\r\n").encode())
                await writer.drain()
                # Consume the close frame without acknowledging it.
                await reader.read()
            finally:
                writer.close()
                await writer.wait_closed()

        server = await asyncio.start_server(peer, "127.0.0.1", 0)
        async with server:
            tts = object.__new__(TTSProvider)
            tts.ws = None
            tts._monitor_task = None
            tts.last_active_time = None
            tts._sentence_text_map = {}
            tts.ws_url = f"ws://127.0.0.1:{server.sockets[0].getsockname()[1]}"
            tts.ws_connect_options = build_ws_connect_options(2)
            tts.header = {}
            await tts._ensure_connection()
            started = time.monotonic()
            await tts.close()
            self.assertLess(time.monotonic() - started, 2.5)
            self.assertIsNone(tts.ws)


if __name__ == "__main__":
    unittest.main()
