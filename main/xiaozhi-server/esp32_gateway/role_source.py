"""Resolve a bound device's role from its management console."""
import hashlib
import json
import os
import re
import time
from pathlib import Path
from tempfile import NamedTemporaryFile
from dataclasses import dataclass
from urllib.parse import urlsplit

import aiohttp

from loguru import logger
from .client import GatewayError

logger = logger.bind(tag=__name__)
MAX_PROFILE_BYTES = 256 * 1024
CACHE_SECONDS = 24 * 60 * 60
VOICE_ID = re.compile(r"[A-Za-z0-9_-]{1,128}")


@dataclass(frozen=True)
class DeviceRole:
    prompt: str | None
    voice: dict


def parse_role(body):
    if body.get("code") != 0:
        raise ValueError("role source rejected the request")
    profile = body["data"]
    tts = profile["TTS"][profile["selected_module"]["TTS"]]
    if tts.get("type") != "alibl_stream":
        raise ValueError("device role requires a supported CosyVoice provider")
    return parse_snapshot(profile.get("prompt"), tts)


def parse_snapshot(prompt, tts):
    if prompt is not None and (not isinstance(prompt, str) or len(prompt.encode("utf-16-le")) > 32000):
        raise ValueError("invalid device role prompt")
    voice = tts.get("private_voice") or tts["voice"]
    model = tts["model"]
    if not all(isinstance(value, str) and VOICE_ID.fullmatch(value) for value in (model, voice)):
        raise ValueError("invalid CosyVoice role profile")
    options = {"model": model, "voice": voice}
    for name in ("ttsVolume", "ttsRate", "ttsPitch"):
        value = tts.get(name)
        if value is not None:
            if isinstance(value, bool) or not -100 <= float(value) <= 100:
                raise ValueError("invalid CosyVoice role adjustment")
            options[name] = float(value)
    return DeviceRole(prompt if prompt and prompt.strip() else None, options)


class RoleSource:
    def __init__(self, config):
        self.device_mac = config["device_mac"]
        if not re.fullmatch(r"(?:[0-9a-f]{2}:){5}[0-9a-f]{2}", self.device_mac):
            raise ValueError("invalid ESP32 role source device MAC")
        url = urlsplit(config["base_url"])
        if (url.scheme not in ("http", "https") or url.hostname not in ("127.0.0.1", "::1")
                or url.username or url.password or url.query or url.fragment or not config["secret"]):
            raise ValueError("invalid ESP32 role source configuration")
        self._endpoint = url.geturl().rstrip("/") + "/config/agent-models"
        self._secret = config["secret"]
        self._last_good = None
        self._last_good_at = 0
        self._cache_file = Path(config["cache_file"]) if config.get("cache_file") else None

    def _load_cache(self):
        if not self._cache_file:
            return None
        try:
            with self._cache_file.open("rb") as f:
                cached = json.loads(f.read(MAX_PROFILE_BYTES))
            if (cached["device_mac"] == self.device_mac and cached["endpoint"] == self._endpoint
                    and 0 <= time.time() - cached["verified_at"] < CACHE_SECONDS):
                return parse_snapshot(cached["prompt"], cached["voice"])
        except (OSError, ValueError, TypeError, KeyError):
            pass
        return None

    def _save_cache(self, role):
        if not self._cache_file:
            return
        temp = None
        try:
            with NamedTemporaryFile(dir=self._cache_file.parent, delete=False, mode="w", encoding="utf-8") as f:
                temp = Path(f.name)
                json.dump({"device_mac": self.device_mac, "endpoint": self._endpoint,
                    "verified_at": time.time(), "prompt": role.prompt, "voice": role.voice}, f,
                    ensure_ascii=False)
            os.replace(temp, self._cache_file)
        except OSError:
            logger.warning("ESP32 role cache could not be written")
        finally:
            if temp and temp.exists():
                temp.unlink(missing_ok=True)

    async def resolve(self, http, device_mac, client_id):
        if device_mac != self.device_mac:
            raise GatewayError(403)
        try:
            async with http.post(
                self._endpoint,
                json={"macAddress": device_mac, "clientId": client_id, "selectedModule": {}},
                headers={"Authorization": "Bearer " + self._secret},
                timeout=aiohttp.ClientTimeout(total=2),
                allow_redirects=False,
            ) as response:
                if response.status != 200:
                    raise GatewayError(response.status)
                data = bytearray()
                async for chunk in response.content.iter_chunked(8192):
                    data.extend(chunk)
                    if len(data) > MAX_PROFILE_BYTES:
                        raise ValueError("oversized role profile")
                role = parse_role(json.loads(data))
        except (aiohttp.ClientError, TimeoutError, GatewayError, ValueError, TypeError, KeyError, AttributeError) as exc:
            # Only temporary transport/service outages may reuse the previous
            # profile. A denied binding or invalid role must fail closed.
            temporary = isinstance(exc, (aiohttp.ClientError, TimeoutError)) or (
                isinstance(exc, GatewayError) and exc.status >= 500
            )
            if temporary:
                role = self._last_good if time.monotonic() - self._last_good_at < CACHE_SECONDS else None
                role = role or self._load_cache()
                if role:
                    logger.warning("ESP32 role source temporarily unavailable; using recent verified role")
                else:
                    logger.warning("ESP32 role source temporarily unavailable; using device defaults")
                return role
            logger.warning("ESP32 role source rejected the role")
            raise GatewayError(503) from None

        if role != self._last_good:
            digest = hashlib.sha256((role.prompt or "").encode()).hexdigest()
            logger.info(f"ESP32 role resolved: prompt_sha256={digest} voice={role.voice['voice']}")
        self._last_good, self._last_good_at = role, time.monotonic()
        self._save_cache(role)
        return role
