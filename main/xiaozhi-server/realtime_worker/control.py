"""Lease-gated Java control plane. No URL or credential is taken from a browser."""
import asyncio
import httpx


class AuthorityLost(Exception):
    pass


class ResponseInterrupted(Exception):
    """Only a delta fencing response is recoverable; sequence did not advance."""


class Fault(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


class Manager:
    def __init__(self, config, client=None):
        self.config = config
        self.client = client or httpx.AsyncClient(
            base_url=config.manager_url.rstrip('/') + '/',
            headers={'X-Realtime-Worker-Key': config.worker_key},
            timeout=httpx.Timeout(config.manager_timeout_seconds),
            follow_redirects=False, trust_env=False)

    async def close(self):
        await self.client.aclose()

    async def post(self, path, body, retry=False):
        # A consume ACK is never retried. Canonical events retry the identical body.
        for attempt in range(2 if retry else 1):
            try:
                response = await self.client.post('internal/realtime-worker/' + path, json=body)
                if response.status_code == 409 and body.get('type') == 'response.delta':
                    try: conflict = response.json()
                    except ValueError: conflict = None
                    if isinstance(conflict, dict) and conflict.get('msg') == 'RESPONSE_INTERRUPTED_OR_TERMINAL':
                        raise ResponseInterrupted()
                if response.status_code in (401, 403, 404, 409, 410):
                    raise AuthorityLost()
                if response.status_code != 200:
                    raise ValueError()
                result = response.json()
                if not isinstance(result, dict) or result.get('code') != 0 or 'data' not in result:
                    raise ValueError()
                return result['data']
            except (httpx.TransportError, ValueError):
                if not retry or attempt == 1:
                    raise AuthorityLost() from None
                await asyncio.sleep(.05)

    async def consume(self, ticket):
        return await self.post('consume', dict(ticket=ticket, worker_id=self.config.worker_id,
                                               server_id=self.config.server_id))

    async def heartbeat(self, task, busy):
        data = await self.post(f"sessions/{task['session_id']}/heartbeat",
                               dict(lease_token=task['lease_token'], input_busy=busy))
        if not isinstance(data, dict) or data.get('active') is not True:
            raise AuthorityLost()
        return data

    async def event(self, task, sequence, event_id, kind, **payload):
        data = await self.post(f"sessions/{task['session_id']}/events",
                               dict(lease_token=task['lease_token'], sequence=sequence,
                                    provider_event_id=event_id, type=kind, **payload), retry=True)
        if not isinstance(data, dict) or type(data.get('sequence')) is not int or data['sequence'] != sequence:
            raise AuthorityLost()
        return data
