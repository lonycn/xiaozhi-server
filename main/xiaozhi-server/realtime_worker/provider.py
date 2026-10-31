"""Modern Qwen3.8 WSS connection. Provider errors are never copied to clients."""
import asyncio
import websockets
from .config import MODEL
from .control import Fault


def provider_error(event):
    raw = event.get('error', event)
    code = str(raw.get('code', '')) if isinstance(raw, dict) else ''
    if code in ('InvalidApiKey', 'InvalidApiKeyError', 'Model.AccessDenied', 'AccessDenied', 'Unauthorized', '401', '403'):
        return 'PROVIDER_AUTH'
    if code in ('Throttling', 'Throttling.RateQuota', 'RateLimitExceeded', '429'):
        return 'PROVIDER_RATE_LIMIT'
    return 'PROVIDER_ERROR'


class Provider:
    def __init__(self, config, connector=None):
        self.config = config
        self.connector = connector or websockets.connect

    async def connect(self):
        try:
            return await self.connector(
                self.config.provider_url + '?model=' + MODEL,
                additional_headers={'Authorization': 'Bearer ' + self.config.api_key},
                open_timeout=self.config.provider_timeout_seconds, close_timeout=.25,
                max_size=1 << 20, max_queue=8, ping_interval=15, ping_timeout=5)
        except (TimeoutError, asyncio.TimeoutError):
            raise Fault('PROVIDER_TIMEOUT') from None
        except websockets.exceptions.InvalidStatus as exc:
            status = exc.response.status_code
            raise Fault('PROVIDER_AUTH' if status in (401, 403) else
                        'PROVIDER_RATE_LIMIT' if status == 429 else 'PROVIDER_UNAVAILABLE') from None
        except Exception:
            raise Fault('PROVIDER_UNAVAILABLE') from None

    @staticmethod
    def session_update(turn_detection_mode="manual"):
        return dict(type='session.update', session={
            'modalities': ['text', 'audio'], 'turn_detection':
                {'type': 'server_vad', 'threshold': 0.5, 'silence_duration_ms': 800}
                if turn_detection_mode == 'server_vad' else None,
            'input_audio_transcription': {'model': 'qwen3-asr-flash-realtime'},
            'instructions': 'You are a maintenance assistant. Distinguish audible/visible evidence from hypotheses. '
                            'Sound or sampled images alone cannot establish a definite fault or safe operation. '
                            'No tools or equipment control are available; ask for measurements and expert confirmation.',
            'enable_search': False, 'tools': [],
            'audio': {'input': {'format': {'type': 'pcm', 'sample_rate': 16000, 'sample_format': 's16le',
                'channels': 1, 'packing': 'interleaved', 'channel_layout': 'mono'}},
                'output': {'voice': 'longanlingxin', 'format': {'type': 'pcm', 'sample_rate': 24000}}}})
