"""Private configuration, independent from ESP32 and ordinary AI workers."""
import ipaddress
import json
import re
from dataclasses import dataclass, fields
from pathlib import Path
from urllib.parse import urlsplit

MODEL = 'qwen3.8-omni-flash-realtime'


@dataclass(repr=False)
class Config:
    manager_url: str
    worker_key: str
    provider_url: str
    api_key: str
    enabled: bool = False
    host: str = '127.0.0.1'
    port: int = 18899
    worker_id: str = 'realtime-local'
    server_id: str = 'realtime-local-1'
    origins: tuple = ('http://127.0.0.1:18894',)
    allow_missing_origin: bool = False
    max_connections: int = 8
    queue_size: int = 32
    auth_timeout_seconds: float = 5
    manager_timeout_seconds: float = 1.5
    provider_timeout_seconds: float = 3
    ack_timeout_seconds: float = 10
    max_session_seconds: int = 1800
    max_input_audio_bytes: int = 57_600_000
    max_output_audio_bytes: int = 86_400_000
    max_frame_total_bytes: int = 64_000_000
    max_turns: int = 100

    def __post_init__(self):
        if type(self.enabled) is not bool or type(self.allow_missing_origin) is not bool:
            raise ValueError('invalid realtime feature configuration')
        for value in (self.worker_key, self.api_key):
            if not isinstance(value, str) or not value.strip() or '\r' in value or '\n' in value:
                raise ValueError('invalid private realtime credential')
        if len(self.worker_key) < 32 or self.worker_key == self.api_key:
            raise ValueError('realtime worker key must be independent')
        for value in (self.worker_id, self.server_id):
            if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_.:-]{1,100}', value):
                raise ValueError('invalid realtime worker identity')
        try:
            if not ipaddress.ip_address(self.host).is_loopback:
                raise ValueError()
        except ValueError:
            raise ValueError('realtime worker binds only to a loopback address') from None
        for value in (self.port, self.max_connections, self.queue_size, self.max_session_seconds,
                      self.max_input_audio_bytes, self.max_output_audio_bytes, self.max_frame_total_bytes, self.max_turns):
            if type(value) is not int or value <= 0:
                raise ValueError('invalid realtime resource limit')
        if self.port > 65535 or self.max_connections > 64 or self.queue_size > 128 or self.max_session_seconds > 1800 or self.max_turns > 100:
            raise ValueError('invalid realtime resource limit')
        if (not 0 < self.auth_timeout_seconds <= 5 or not 0 < self.manager_timeout_seconds <= 1.5
                or not 0 < self.provider_timeout_seconds <= 3 or not 0 < self.ack_timeout_seconds <= 10):
            raise ValueError('invalid realtime timeout')
        manager = urlsplit(self.manager_url)
        provider = urlsplit(self.provider_url)
        if (manager.scheme not in ('http', 'https') or not manager.hostname or manager.username or manager.password
                or manager.query or manager.fragment):
            raise ValueError('invalid manager endpoint')
        if (provider.scheme != 'wss' or not (provider.hostname or '').endswith('.maas.aliyuncs.com')
                or provider.username or provider.password or provider.query or provider.fragment
                or provider.path != '/api-ws/v1/realtime' or provider.port not in (None, 443)):
            raise ValueError('provider must be a configured workspace Bailian WSS endpoint')
        if not isinstance(self.origins, (list, tuple)) or not self.origins:
            raise ValueError('an explicit Origin allowlist is required')
        for origin in self.origins:
            if not isinstance(origin, str): raise ValueError('invalid Origin')
            parts = urlsplit(origin)
            if (parts.scheme not in ('http', 'https') or not parts.hostname or parts.username or parts.password
                    or parts.path or parts.query or parts.fragment):
                raise ValueError('invalid Origin')

    @classmethod
    def load(cls, filename):
        path = Path(filename)
        if path.stat().st_mode & 0o077:
            raise ValueError('private realtime configuration must have mode 0600')
        data = json.loads(path.read_text())
        if not isinstance(data, dict) or set(data) - {field.name for field in fields(cls)}:
            raise ValueError('invalid realtime configuration fields')
        return cls(**data)
