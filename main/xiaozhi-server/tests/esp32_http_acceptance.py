#!/usr/bin/env python3
"""Opt-in acceptance against an isolated Java fixture and synthetic ESP32.

Required environment: ESP32_ACCEPTANCE_BASE_URL, ESP32_ACCEPTANCE_GATEWAY_KEY,
ESP32_ACCEPTANCE_MAC, ESP32_ACCEPTANCE_CLIENT_ID, ESP32_ACCEPTANCE_DEVICE_TOKEN,
ESP32_ACCEPTANCE_OWNER_TOKEN. The fixture must provide a deterministic AI worker.
Never point this script at a native/business service: registration, turns and a
status command intentionally write to the isolated fixture's ledger.
"""
import asyncio
import json
import os
import queue
import sys
import time
import uuid
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

test_directory = Path(__file__).resolve().parent
sys.path[:] = [entry for entry in sys.path if Path(entry or ".").resolve() != test_directory]
sys.path.insert(0, str(test_directory.parent))
# Existing modules create logging at import time; load before the event loop.
import core.providers.tools.device_mcp.mcp_handler  # noqa: E402,F401
import aiohttp  # noqa: E402
from esp32_gateway.client import GatewayError  # noqa: E402
from esp32_gateway.runtime import GatewayRuntime  # noqa: E402

REQUIRED = ('BASE_URL', 'GATEWAY_KEY', 'MAC', 'CLIENT_ID', 'DEVICE_TOKEN', 'OWNER_TOKEN')


def environment():
    result = {}
    for key in REQUIRED:
        value = os.environ.get('ESP32_ACCEPTANCE_' + key)
        if not value:
            raise RuntimeError('missing ESP32_ACCEPTANCE_' + key)
        result[key] = value
    return result


async def run():
    env = environment()
    base = env['BASE_URL'].rstrip('/')
    cfg = {'base_url': base, 'gateway_key': env['GATEWAY_KEY'],
           'gateway_id': 'acceptance-' + uuid.uuid4().hex[:16], 'execution_mode': 'ai_worker'}
    mcp = SimpleNamespace(is_ready=AsyncMock(return_value=True),
        name_mapping={'self_get_device_status': 'self.get_device_status'})
    conn = SimpleNamespace(headers={'device-id': env['MAC'], 'client-id': env['CLIENT_ID'],
        'authorization': 'Bearer ' + env['DEVICE_TOKEN']}, mcp_client=mcp,
        websocket=SimpleNamespace(close=AsyncMock(), send=AsyncMock()), session_id="synthetic-session", tts=SimpleNamespace(tts_text_queue=queue.Queue()),
        client_abort=False)
    runtime = GatewayRuntime(conn, cfg)
    synthetic_calls = []
    replacement_fence = None

    async def synthetic_mcp(_conn, _client, tool, args, timeout):
        synthetic_calls.append((tool, args))
        return '{"audio_speaker":{"volume":30}}'

    try:
        with patch('core.providers.tools.device_mcp.mcp_handler.call_mcp_tool', side_effect=synthetic_mcp):
            await runtime.start()
            if not runtime.active:
                raise RuntimeError('registration or session connect failed')
            fence, sid = runtime.fence, runtime.session_id
            key = uuid.uuid4().hex
            text = 'synthetic ESP32 acceptance question'
            turn = await runtime.client.session('input', fence, sessionId=sid, inputKey=key, text=text)
            duplicate = await runtime.client.session('input', fence, sessionId=sid, inputKey=key, text=text)
            if turn['id'] != duplicate['id']:
                raise RuntimeError('input replay created a second turn')
            runtime.bind_turn(turn)
            await runtime._canonical_turn(turn['id'])
            await runtime._canonical_turn(turn['id'])
            if conn.tts.tts_text_queue.qsize() != 3:
                raise RuntimeError('synthetic TTS callback was not delivered exactly once')

            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=5)) as owner:
                headers = {'Authorization': 'Bearer ' + env['OWNER_TOKEN']}
                async with owner.post(base + '/api/v1/esp32/devices/' + fence['deviceId'] + '/commands', headers=headers,
                        json={'tool': 'self.get_device_status', 'arguments': {},
                              'idempotencyKey': uuid.uuid4().hex, 'timeoutSeconds': 20}) as response:
                    if response.status >= 400:
                        raise RuntimeError('owner command submission rejected: HTTP ' + str(response.status))
                    submitted = await response.json()
                command_id = submitted['id']
                deadline = time.monotonic() + 15
                state = None
                while time.monotonic() < deadline:
                    async with owner.get(base + '/api/v1/esp32/commands/' + command_id, headers=headers) as response:
                        if response.status >= 400:
                            raise RuntimeError('owner command query rejected: HTTP ' + str(response.status))
                        state = (await response.json()).get('state')
                    if state in ('succeeded', 'failed', 'timed_out', 'unknown'):
                        break
                    await asyncio.sleep(.25)
                if state != 'succeeded' or len(synthetic_calls) != 1:
                    raise RuntimeError('synthetic MCP command was not acknowledged exactly once')

            # A second registration represents a new connection and invalidates the old generation.
            replacement_fence = await runtime.client.register(env['MAC'], env['CLIENT_ID'],
                env['DEVICE_TOKEN'], cfg['gateway_id'], ['self.get_device_status'])
            try:
                await runtime.client.fence('heartbeat', fence)
            except GatewayError as exc:
                if exc.status != 409:
                    raise RuntimeError('old fence failed with unexpected status') from None
            else:
                raise RuntimeError('old fence unexpectedly remained valid')
            print(json.dumps({'result': 'passed', 'input_replay': 'same_turn',
                'tts': 'synthetic_callback_once', 'mcp': 'synthetic_result_once',
                'old_generation': 'rejected'}, ensure_ascii=False))
    finally:
        if replacement_fence and runtime.client:
            try:
                await runtime.client.fence('disconnect', replacement_fence)
            except GatewayError:
                pass
        await runtime.close()


if __name__ == '__main__':
    try:
        asyncio.run(run())
    except Exception as error:
        # Do not print HTTP bodies, credentials, tokens, traceback or exception chains.
        print(json.dumps({'result': 'failed', 'error_type': type(error).__name__,
                          'message': str(error) if isinstance(error, RuntimeError) else 'acceptance operation failed'}), file=sys.stderr)
        sys.exit(1)
