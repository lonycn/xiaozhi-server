"""CosyVoice PlainText synthesis, with no rewriting or generated continuation."""
import asyncio
import hashlib
import io
import json
import re
import uuid
import wave
from dataclasses import dataclass

from websockets.asyncio.client import connect as WebSocketConnect


class FixedOriginConnect(WebSocketConnect):
    def process_redirect(self, exception):
        # websockets otherwise follows cross-origin redirects with supplied
        # Authorization headers. A trusted URL never grants a second origin.
        return exception


def connect(*args, **kwargs):
    return FixedOriginConnect(*args, **kwargs)

SAMPLE_RATE = 24000
MAX_SECONDS = 120
MAX_AUDIO_BYTES = 5 * 1024 * 1024
MAX_PCM_BYTES = min(MAX_AUDIO_BYTES - 44, SAMPLE_RATE * 2 * MAX_SECONDS)
MAX_TEXT_CHARS = 8000


class SpeechFailure(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


@dataclass
class Observation:
    provider_request_id: str | None = None
    usage: dict | None = None


@dataclass
class Audio:
    data: bytes
    duration_ms: int
    sha256: str


def valid_text(text):
    if not isinstance(text, str) or not text.strip() or '\x00' in text:
        raise SpeechFailure('SPEECH_TEXT_INVALID')
    try:
        size = len(text.encode('utf-16-le')) // 2
    except UnicodeError:
        raise SpeechFailure('SPEECH_TEXT_INVALID') from None
    if size > MAX_TEXT_CHARS:
        raise SpeechFailure('SPEECH_TEXT_LIMIT')
    return text


def wav_audio(pcm):
    if len(pcm) < 48 or len(pcm) % 2:
        raise SpeechFailure('SPEECH_AUDIO_INVALID')
    if len(pcm) > MAX_PCM_BYTES:
        raise SpeechFailure('SPEECH_AUDIO_LIMIT')
    output = io.BytesIO()
    with wave.open(output, 'wb') as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(SAMPLE_RATE)
        wav.writeframes(pcm)
    data = output.getvalue()
    return Audio(data, len(pcm) * 1000 // (2 * SAMPLE_RATE), hashlib.sha256(data).hexdigest())


def observe(event, observation):
    header = event.get('header', {})
    attributes = header.get('attributes', {})
    request_id = attributes.get('request_uuid') if isinstance(attributes, dict) else None
    if request_id is not None:
        if not isinstance(request_id, str) or not re.fullmatch(r'[A-Za-z0-9_.:/-]{1,200}', request_id):
            raise SpeechFailure('SPEECH_PROVIDER_PROTOCOL')
        if observation.provider_request_id not in (None, request_id):
            raise SpeechFailure('SPEECH_PROVIDER_PROTOCOL')
        observation.provider_request_id = request_id
    usage = event.get('payload', {}).get('usage')
    if isinstance(usage, dict) and type(usage.get('characters')) is int and 0 <= usage['characters'] <= 10_000_000:
        previous = observation.usage
        if previous is not None and usage['characters'] < previous['characters']:
            raise SpeechFailure('SPEECH_PROVIDER_PROTOCOL')
        observation.usage = {'characters': usage['characters']}


class CosyVoice:
    def __init__(self, config):
        self.config = config

    async def synthesize(self, text, observation, *, max_bytes=MAX_AUDIO_BYTES, max_seconds=MAX_SECONDS):
        valid_text(text)
        if type(max_bytes) is not int or not 44 < max_bytes <= MAX_AUDIO_BYTES or type(max_seconds) is not int or not 0 < max_seconds <= MAX_SECONDS:
            raise SpeechFailure('SPEECH_AUDIO_LIMIT')
        limit = min(max_bytes - 44, max_seconds * SAMPLE_RATE * 2, MAX_PCM_BYTES)
        task_id, pcm = str(uuid.uuid4()), bytearray()
        headers = {'Authorization': 'Bearer ' + self.config.api_key, 'X-DashScope-DataInspection': 'enable'}
        if self.config.workspace_id:
            headers['X-DashScope-WorkSpace'] = self.config.workspace_id
        ws = None
        try:
            async with asyncio.timeout(self.config.provider_timeout_seconds):
                # websockets 14.x does not inherit HTTP(S)_PROXY environment settings.
                async with connect(self.config.provider_url, additional_headers=headers, open_timeout=8,
                                   close_timeout=1, max_size=256 * 1024, max_queue=4,
                                   ping_interval=20, ping_timeout=10) as ws:
                    def command(action, payload):
                        return json.dumps({'header': {'action': action, 'task_id': task_id, 'streaming': 'duplex'},
                                           'payload': payload}, ensure_ascii=False)
                    await ws.send(command('run-task', {'task_group': 'audio', 'task': 'tts',
                        'function': 'SpeechSynthesizer', 'model': self.config.model, 'parameters': {
                            'text_type': 'PlainText', 'voice': self.config.voice, 'format': 'pcm',
                            'sample_rate': SAMPLE_RATE, 'volume': 50, 'rate': 1.0, 'pitch': 1.0,
                            'enable_ssml': False}, 'input': {}}))
                    started = False
                    async for raw in ws:
                        if isinstance(raw, bytes):
                            if not started:
                                raise SpeechFailure('SPEECH_AUDIO_INVALID')
                            if len(pcm) + len(raw) > limit:
                                raise SpeechFailure('SPEECH_AUDIO_LIMIT')
                            pcm.extend(raw)
                            continue
                        if len(raw.encode('utf-8')) > 64 * 1024:
                            raise SpeechFailure('SPEECH_PROVIDER_PROTOCOL')
                        try:
                            event = json.loads(raw)
                            if (not isinstance(event, dict) or not isinstance(event.get('header'), dict)
                                    or not isinstance(event.get('payload', {}), dict)
                                    or event['header'].get('task_id') != task_id):
                                raise ValueError()
                        except (ValueError, TypeError):
                            raise SpeechFailure('SPEECH_PROVIDER_PROTOCOL') from None
                        observe(event, observation)
                        kind = event['header'].get('event')
                        if kind == 'task-started':
                            if started:
                                raise SpeechFailure('SPEECH_PROVIDER_PROTOCOL')
                            started = True
                            await ws.send(command('continue-task', {'input': {'text': text}}))
                            await ws.send(command('finish-task', {'input': {}}))
                        elif kind == 'task-failed':
                            code = event['header'].get('error_code')
                            if code in ('AccessDenied', 'InvalidApiKey', 'Unauthorized', 'Forbidden', 'InvalidApiKeyOrToken'):
                                raise SpeechFailure('SPEECH_PROVIDER_AUTH')
                            raise SpeechFailure('SPEECH_PROVIDER_ERROR')
                        elif kind == 'task-finished':
                            if not started:
                                raise SpeechFailure('SPEECH_PROVIDER_PROTOCOL')
                            return wav_audio(pcm)
                        elif kind != 'result-generated':
                            raise SpeechFailure('SPEECH_PROVIDER_PROTOCOL')
                    raise SpeechFailure('SPEECH_PROVIDER_ERROR')
        except asyncio.CancelledError:
            # Closing the dedicated socket stops the old task; audio is never returned.
            if ws is not None:
                try:
                    await asyncio.wait_for(ws.close(), 1.5)
                except Exception:
                    pass
            raise
        except TimeoutError:
            raise SpeechFailure('SPEECH_PROVIDER_TIMEOUT') from None
        except SpeechFailure:
            raise
        except Exception as error:
            status = getattr(getattr(error, 'response', None), 'status_code', None)
            raise SpeechFailure('SPEECH_PROVIDER_AUTH' if status in (401, 403) else 'SPEECH_PROVIDER_ERROR') from None
