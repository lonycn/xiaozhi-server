import asyncio
import httpx


class LeaseLost(Exception):
    """Authority revoked or callback outcome uncertain. Never continue generation."""


class ControlError(Exception):
    """Sanitized manager transport/protocol error."""


class Manager:
    def __init__(self, config, client=None):
        self.client = client or httpx.AsyncClient(
            base_url=config.manager_url.rstrip('/') + '/',
            headers={'X-AI-Worker-Key': config.worker_key},
            timeout=httpx.Timeout(5), follow_redirects=False, trust_env=False)
        self.worker_id = config.worker_id

    async def close(self):
        await self.client.aclose()

    async def post(self, path, body, *, retry=True):
        # One immutable body is reused after lost ACKs, including delta sequence.
        for attempt in range(3 if retry else 1):
            try:
                response = await self.client.post('internal/ai-worker/' + path, json=body)
                if response.status_code in (401, 403, 409):
                    raise LeaseLost() from None
                if response.status_code >= 500:
                    raise ControlError()
                if response.status_code != 200:
                    raise ControlError() from None
                payload = response.json()
                if not isinstance(payload, dict) or payload.get('code') != 0 or 'data' not in payload:
                    raise ControlError() from None
                return payload['data']
            except (httpx.TransportError, ControlError, ValueError):
                if not retry or attempt == 2:
                    raise ControlError() from None
                await asyncio.sleep(.25 * (2 ** attempt))

    async def claim(self):
        # A lost claim ACK must expire server-side, never claim a second job immediately.
        return await self.post('claim', {'worker_id': self.worker_id}, retry=False)

    async def callback(self, task, action, **body):
        return await self.post('runs/' + task['run_id'] + '/' + action,
                               {'lease_token': task['lease_token'], **body}, retry=action != 'heartbeat')
