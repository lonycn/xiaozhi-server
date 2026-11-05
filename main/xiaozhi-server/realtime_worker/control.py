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

    async def tools(self, task, provider_session_id, response_id, generation, round_number, calls, maximum):
        body = dict(lease_token=task['lease_token'], control_epoch=task['control_epoch'], turn_generation=generation,
                    provider_session_id=provider_session_id, response_id=response_id, round=round_number, calls=calls)
        path = f"internal/realtime-worker/sessions/{task['session_id']}/tool-proposals"
        # Idempotency is owned by Java. Lost ACK retries retain exact call/body identities.
        for attempt in range(2):
            try:
                async with self.client.stream('POST', path, json=body, timeout=httpx.Timeout(30, connect=1.5),
                        headers={'Accept-Encoding': 'identity'}) as response:
                    if response.status_code in (401,403,404,409,410): raise AuthorityLost()
                    if response.status_code != 200 or response.headers.get('content-encoding', 'identity') != 'identity': raise ValueError()
                    raw = bytearray()
                    async for part in response.aiter_bytes(8192):
                        raw.extend(part)
                        if len(raw) > maximum: raise Fault('TOOL_LIMIT_EXCEEDED')
                    import json
                    result = json.loads(raw)
                    if not isinstance(result, dict) or result.get('code') != 0 or 'data' not in result: raise ValueError()
                    return result['data']
            except (httpx.TransportError, ValueError):
                if attempt: raise AuthorityLost() from None
                await asyncio.sleep(.05)

    async def material_callback(self,task,material_id,action,generation,**payload):
        import re
        if not isinstance(material_id,str) or not re.fullmatch(r'[a-f0-9]{32}',material_id) or action not in ('prepared','context-ack','perception-usage','fail'):
            raise Fault('MATERIAL_INPUT_UNAVAILABLE')
        return await self.material_post(f"sessions/{task['session_id']}/materials/{material_id}/{action}",
            dict(lease_token=task['lease_token'],control_epoch=task['control_epoch'],turn_generation=generation,**payload))

    async def material_control(self,task,pause_id,action,generation,**payload):
        import re
        if not isinstance(pause_id,str) or not re.fullmatch(r'[a-f0-9]{32}',pause_id) or action not in ('cleared','resumed'):
            raise Fault('MATERIAL_INPUT_UNAVAILABLE')
        return await self.material_post(f"sessions/{task['session_id']}/input-pauses/{pause_id}/{action}",
            dict(lease_token=task['lease_token'],control_epoch=task['control_epoch'],turn_generation=generation,**payload))

    async def material_post(self,path,body):
        import json
        for attempt in range(2):
            try:
                async with self.client.stream('POST','internal/realtime-worker/'+path,json=body,headers={'Accept-Encoding':'identity'}) as response:
                    if response.status_code in (401,403,404,409,410):raise AuthorityLost()
                    if response.headers.get('content-encoding','identity')!='identity':raise ValueError()
                    raw=bytearray()
                    async for part in response.aiter_bytes(8192):
                        raw.extend(part)
                        if len(raw)>65536:raise Fault('MATERIAL_LIMIT')
                    result=json.loads(raw)
                    if response.status_code!=200:
                        code=result.get('msg') if isinstance(result,dict) else None
                        if code in ('MATERIAL_PERCEPTION_NOT_CONFIGURED','PERCEPTION_AUTH','PERCEPTION_ERROR','PERCEPTION_TIMEOUT',
                                    'MATERIAL_INPUT_UNAVAILABLE','MATERIAL_LIMIT','MATERIAL_CONTEXT_UNCONFIRMED'):
                            raise Fault(code)
                        raise ValueError()
                    if not isinstance(result,dict) or result.get('code')!=0 or 'data' not in result:raise ValueError()
                    return result['data']
            except (httpx.TransportError,ValueError):
                if attempt:raise AuthorityLost() from None
                await asyncio.sleep(.05)

    async def material_media(self,task,material_id,attachment_id,maximum):
        import re
        if (not isinstance(material_id,str) or not re.fullmatch(r'[a-f0-9]{32}',material_id)
                or not isinstance(attachment_id,str) or not re.fullmatch(r'[1-9][0-9]{0,18}',attachment_id)):
            raise Fault('MATERIAL_INPUT_UNAVAILABLE')
        path=f"internal/realtime-worker/sessions/{task['session_id']}/materials/{material_id}/attachments/{attachment_id}/content"
        try:
            async with self.client.stream('GET',path,params={'variant':'display'},headers={
                    'X-Realtime-Lease':task['lease_token'],'Accept-Encoding':'identity'}) as response:
                if response.status_code in (401,403,404,409,410):raise AuthorityLost()
                if response.status_code!=200:raise Fault('MATERIAL_INPUT_UNAVAILABLE')
                if response.headers.get('content-type','').split(';',1)[0].strip().lower()!='image/jpeg' or response.headers.get('content-encoding','identity')!='identity':
                    raise Fault('MATERIAL_INPUT_UNAVAILABLE')
                data=bytearray()
                async for chunk in response.aiter_bytes(16384):
                    data.extend(chunk)
                    if len(data)>min(maximum,1048576):raise Fault('MATERIAL_LIMIT')
                return bytes(data)
        except httpx.TransportError:raise AuthorityLost() from None
