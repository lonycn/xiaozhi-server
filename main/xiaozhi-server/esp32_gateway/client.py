"""Bounded internal API client; credentials are never included in errors."""
import aiohttp
import re
from urllib.parse import urlsplit

FENCE_FIELDS = ("deviceId", "connectionId", "generation", "gatewayId", "bindingVersion")


class GatewayError(Exception):
    def __init__(self, status=0, code=None):
        self.status = status
        self.code = code if isinstance(code,str) and re.fullmatch(r'[A-Za-z0-9_]{1,64}',code) else None
        super().__init__(f"ESP32 internal request failed ({status})")


class GatewayClient:
    def __init__(self, base_url, key, session):
        parsed = urlsplit(base_url)
        if parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError("invalid ESP32 internal base URL")
        if not key:
            raise ValueError("ESP32 gateway key required")
        self.base_url = base_url.rstrip('/')
        self._key = key
        self.http = session

    async def post(self, path, body):
        try:
            async with self.http.post(self.base_url + '/internal/esp32/' + path,
                    json=body, headers={"X-ESP32-Gateway-Key": self._key},
                    timeout=aiohttp.ClientTimeout(total=5)) as response:
                data = bytearray()
                async for chunk in response.content.iter_chunked(8192):
                    data.extend(chunk)
                    if len(data) > 65536:
                        raise GatewayError(response.status)
                import json
                try:body=json.loads(data) if data else None
                except (ValueError,TypeError):body=None
                if response.status>=400:
                    code=body.get('msg',body.get('error',body.get('code'))) if isinstance(body,dict) else None
                    raise GatewayError(response.status,code)
                if data and body is None:raise GatewayError()
                return body
        except GatewayError:
            raise
        except Exception as exc:
            raise GatewayError() from exc

    async def register(self, mac, client_id, token, gateway_id, capabilities):
        context = await self.post('register', {"deviceId": None, "deviceMac": mac,
            "clientId": client_id, "token": token, "gatewayId": gateway_id, "capabilities": capabilities})
        if not isinstance(context, dict) or any(not context.get(k) for k in FENCE_FIELDS) or context['gatewayId'] != gateway_id or type(context['generation']) is not int:
            raise GatewayError()
        return {k: context[k] for k in FENCE_FIELDS}

    async def fence(self, path, fence):
        return await self.post(path, fence)

    async def session(self, operation, fence, **data):
        return await self.post('sessions/' + operation, {"fence": fence, **data})
