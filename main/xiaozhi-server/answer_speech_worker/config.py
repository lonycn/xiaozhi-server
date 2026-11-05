"""Private, explicitly provisioned speech credentials and bounded resources."""
import ipaddress
import json
import math
import re
from dataclasses import dataclass, fields
from pathlib import Path
from urllib.parse import urlsplit


@dataclass(repr=False)
class Config:
    manager_url: str
    worker_key: str
    provider_url: str
    api_key: str
    model: str
    voice: str
    enabled: bool = False
    worker_id: str = 'answer-speech-local'
    workspace_id: str = ''
    heartbeat_seconds: float = 10
    poll_seconds: float = 2
    provider_timeout_seconds: float = 60
    manager_timeout_seconds: float = 5

    def __post_init__(self):
        if type(self.enabled) is not bool:
            raise ValueError('invalid speech feature configuration')
        for value in (self.worker_key, self.api_key):
            if not isinstance(value, str) or not value.strip() or any(c in value for c in '\r\n'):
                raise ValueError('invalid speech credentials')
        if len(self.worker_key) < 32 or self.worker_key == self.api_key:
            raise ValueError('speech worker key must be independent')
        if not isinstance(self.worker_id, str) or not re.fullmatch(r'[A-Za-z0-9_.:-]{1,100}', self.worker_id):
            raise ValueError('invalid speech worker identity')
        manager = urlsplit(self.manager_url)
        if (manager.scheme not in ('http', 'https') or not manager.hostname or manager.username or manager.password
                or manager.query or manager.fragment or '..' in manager.path or '%' in manager.path
                or manager.port == 0):
            raise ValueError('invalid speech manager origin')
        if manager.scheme == 'http':
            try:
                loopback = manager.hostname == 'localhost' or ipaddress.ip_address(manager.hostname).is_loopback
            except ValueError:
                loopback = False
            if not loopback:
                raise ValueError('unencrypted manager must be loopback')
        provider = urlsplit(self.provider_url)
        host = provider.hostname or ''
        if (provider.scheme != 'wss' or not (host in ('dashscope.aliyuncs.com', 'dashscope-intl.aliyuncs.com')
                or re.fullmatch(r'[a-z0-9][a-z0-9.-]*\.maas\.aliyuncs\.com', host))
                or provider.path.rstrip('/') != '/api-ws/v1/inference'
                or provider.username or provider.password or provider.query or provider.fragment
                or provider.port not in (None, 443)):
            raise ValueError('speech provider must be a trusted Bailian WSS inference endpoint')
        if self.model not in ('cosyvoice-v2', 'cosyvoice-v3.5-flash'):
            raise ValueError('unsupported speech model')
        if self.model == 'cosyvoice-v3.5-flash' and self.voice != 'longantai_v3':
            raise ValueError('unsupported speech model and voice combination')
        if not isinstance(self.voice, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,100}', self.voice):
            raise ValueError('invalid trusted speech voice')
        if not isinstance(self.workspace_id, str) or len(self.workspace_id) > 100 or any(c in self.workspace_id for c in '\r\n'):
            raise ValueError('invalid speech workspace')
        for name, low, high in (('heartbeat_seconds', 1, 12), ('poll_seconds', .1, 30),
                                ('provider_timeout_seconds', 1, 120), ('manager_timeout_seconds', .1, 10)):
            value = getattr(self, name)
            if type(value) not in (int, float) or not math.isfinite(value) or not low <= value <= high:
                raise ValueError('invalid speech timing')

    @classmethod
    def load(cls, filename):
        path = Path(filename)
        if path.stat().st_mode & 0o077:
            raise ValueError('private speech configuration must have mode 0600')
        data = json.loads(path.read_text())
        if not isinstance(data, dict) or set(data) - {field.name for field in fields(cls)}:
            raise ValueError('invalid speech configuration fields')
        return cls(**data)
