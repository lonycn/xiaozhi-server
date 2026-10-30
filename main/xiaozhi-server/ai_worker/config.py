import json
import os
from dataclasses import dataclass, fields
from pathlib import Path
from urllib.parse import urlsplit


@dataclass(repr=False)
class Config:
    manager_url: str
    worker_key: str
    worker_id: str
    base_url: str
    api_key: str
    model: str
    workspace_id: str = ""
    vision_model: str = ""
    vision_base_url: str = ""
    vision_api_key: str = ""
    vision_workspace_id: str = ""
    max_tokens: int = 500
    heartbeat_seconds: float = 12
    poll_seconds: float = 2
    provider_timeout_seconds: float = 60

    def __post_init__(self):
        for name in ('manager_url', 'worker_key', 'worker_id', 'base_url', 'api_key', 'model'):
            if not isinstance(getattr(self, name), str) or not getattr(self, name).strip():
                raise ValueError('invalid worker configuration')
        manager = urlsplit(self.manager_url)
        provider = urlsplit(self.base_url)
        if manager.scheme not in ('http', 'https') or not manager.hostname or manager.query or manager.fragment or manager.username:
            raise ValueError('invalid manager URL')
        host = provider.hostname or ''
        if (provider.scheme != 'https' or provider.username or provider.query or provider.fragment
                or not (host in ('dashscope.aliyuncs.com', 'dashscope-intl.aliyuncs.com')
                        or host.endswith('.maas.aliyuncs.com'))):
            raise ValueError('provider must be the configured Bailian HTTPS endpoint')
        if self.vision_model:
            endpoint = urlsplit(self.vision_base_url or self.base_url)
            host = endpoint.hostname or ''
            if (endpoint.scheme != 'https' or endpoint.username or endpoint.password or endpoint.query or endpoint.fragment
                    or not (host in ('dashscope.aliyuncs.com', 'dashscope-intl.aliyuncs.com')
                            or host.endswith('.maas.aliyuncs.com'))):
                raise ValueError('vision provider must be the configured Bailian HTTPS endpoint')
        elif self.vision_base_url or self.vision_api_key or self.vision_workspace_id:
            raise ValueError('vision_model is required for vision configuration')
        if not 10 <= self.heartbeat_seconds <= 15 or self.poll_seconds <= 0 or self.provider_timeout_seconds <= 0:
            raise ValueError('invalid worker timing')
        if isinstance(self.max_tokens, bool) or not isinstance(self.max_tokens, int) or self.max_tokens <= 0:
            raise ValueError('invalid max_tokens')

    @classmethod
    def load(cls, filename=None):
        filename = filename or os.environ.get('AI_WORKER_CONFIG')
        data = json.loads(Path(filename).read_text()) if filename else {}
        if not isinstance(data, dict) or set(data) - {f.name for f in fields(cls)}:
            raise ValueError('invalid worker configuration fields')
        for f in fields(cls):
            value = os.environ.get('AI_WORKER_' + f.name.upper())
            if value is not None:
                data[f.name] = int(value) if f.type is int else float(value) if f.type is float else value
        return cls(**data)
