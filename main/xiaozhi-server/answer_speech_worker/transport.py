"""Only fixed authenticated manager routes; no task-controlled origins."""
import asyncio
import json
import re

import httpx


class LeaseLost(Exception):
    """Authority lost or completion outcome uncertain. Stop publishing."""


class ControlError(Exception):
    """Sanitized manager protocol/transport failure."""


class Manager:
    def __init__(self, config, client=None):
        self.client = client or httpx.AsyncClient(base_url=config.manager_url.rstrip('/') + '/',
            headers={'X-Answer-Speech-Worker-Key': config.worker_key},
            timeout=httpx.Timeout(config.manager_timeout_seconds), follow_redirects=False, trust_env=False)
        self.worker_id = config.worker_id

    async def close(self):
        await self.client.aclose()

    async def request(self, method, path, *, body=None, data=None, headers=None, retry=True):
        for attempt in range(2 if retry else 1):
            try:
                async with self.client.stream(method, 'internal/answer-speech-worker/' + path,
                        json=body, content=data, headers={'Accept-Encoding': 'identity', **(headers or {})}) as response:
                    if response.status_code in (401, 403, 404, 409, 410):
                        raise LeaseLost()
                    if response.status_code != 200 or response.headers.get('content-encoding', 'identity') != 'identity':
                        raise ControlError()
                    raw = bytearray()
                    async for chunk in response.aiter_bytes(4096):
                        raw.extend(chunk)
                        if len(raw) > 65536:
                            raise ControlError()
                    payload = json.loads(raw)
                    if not isinstance(payload, dict) or type(payload.get('code')) is not int or payload['code'] != 0 or 'data' not in payload:
                        raise ControlError()
                    return payload['data']
            except (httpx.TransportError, ControlError, ValueError):
                if not retry or attempt:
                    raise ControlError() from None
                await asyncio.sleep(.2)

    async def claim(self):
        # Lost claim ACK is left to the server lease; do not claim again immediately.
        return await self.request('POST', 'claim', body={'worker_id': self.worker_id}, retry=False)

    @staticmethod
    def job_path(task):
        job_id = task.get('job_id')
        if not isinstance(job_id, str) or not re.fullmatch(r'[a-f0-9]{32}', job_id):
            raise ControlError()
        return 'jobs/' + job_id

    @staticmethod
    def fence(task):
        return {key: task[key] for key in ('lease_token', 'attempt', 'message_version', 'text_sha256')}

    async def callback(self, task, action, **payload):
        if action not in ('heartbeat', 'complete', 'fail', 'usage'):
            raise ControlError()
        return await self.request('POST', self.job_path(task) + '/' + action,
            body={**self.fence(task), **payload}, retry=action != 'heartbeat')

    async def audio(self, task, audio):
        return await self.request('PUT', self.job_path(task) + '/audio', data=audio.data,
            headers={'Content-Type': 'audio/wav', 'X-Speech-Lease': task['lease_token'],
                'X-Speech-Attempt': str(task['attempt']), 'X-Message-Version': task['message_version'],
                'X-Text-SHA256': task['text_sha256'], 'X-Audio-SHA256': audio.sha256})
