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
    heartbeat_seconds: float = 12
    poll_seconds: float = 2
    process_timeout_seconds: float = 60
    request_timeout_seconds: float = 5
    temp_root: str = ''
    ffmpeg: str = 'ffmpeg'
    ffprobe: str = 'ffprobe'
    asr_base_url: str = 'https://dashscope.aliyuncs.com/compatible-mode/v1'
    asr_api_key: str = ''
    asr_model: str = 'qwen3-asr-flash'
    asr_workspace_id: str = ''
    asr_timeout_seconds: float = 90

    def __post_init__(self):
        for field in ('manager_url', 'worker_key', 'worker_id', 'ffmpeg', 'ffprobe'):
            if not isinstance(getattr(self, field), str) or not getattr(self, field).strip():
                raise ValueError('invalid worker configuration')
        parsed = urlsplit(self.manager_url)
        if (parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username
                or parsed.password or parsed.query or parsed.fragment):
            raise ValueError('invalid manager URL')
        provider = urlsplit(self.asr_base_url)
        if (provider.scheme != 'https' or provider.username or provider.password or provider.query
                or provider.fragment or not (provider.hostname in
                ('dashscope.aliyuncs.com', 'dashscope-intl.aliyuncs.com')
                or (provider.hostname or '').endswith('.maas.aliyuncs.com'))):
            raise ValueError('invalid Bailian endpoint')
        if not 5 <= self.heartbeat_seconds <= 15 or not 0 < self.request_timeout_seconds <= 10:
            raise ValueError('invalid worker timing')
        if not 0 < self.process_timeout_seconds <= 120 or not 0 < self.asr_timeout_seconds <= 180 or self.poll_seconds <= 0:
            raise ValueError('invalid worker timing')

    @classmethod
    def load(cls, filename=None):
        filename = filename or os.environ.get('MEDIA_WORKER_CONFIG')
        data = json.loads(Path(filename).read_text()) if filename else {}
        if not isinstance(data, dict) or set(data) - {f.name for f in fields(cls)}:
            raise ValueError('invalid configuration fields')
        for field in fields(cls):
            value = os.environ.get('MEDIA_WORKER_' + field.name.upper())
            if value is not None:
                data[field.name] = float(value) if field.type is float else value
        return cls(**data)
