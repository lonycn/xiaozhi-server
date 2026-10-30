"""Optional Bailian compatible ASR. Only actual provider text/usage are persisted."""
import asyncio
import base64
import json
import io
import wave
from pathlib import Path
import httpx
from .transport import MediaFailure


class Transcriber:
    def __init__(self, config, client=None):
        self.config = config
        self.client = client or httpx.AsyncClient(timeout=httpx.Timeout(config.asr_timeout_seconds, connect=5),
                                                  follow_redirects=False, trust_env=False)

    async def transcribe(self, source, directory):
        if not self.config.asr_api_key:
            raise MediaFailure('TRANSCRIPTION_NOT_CONFIGURED')
        if source.stat().st_size > 20 << 20:
            raise MediaFailure('INVALID_MEDIA')
        try:
            with wave.open(str(source), 'rb') as wav:
                if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate(), wav.getcomptype()) != (1, 2, 16000, 'NONE'):
                    raise MediaFailure('INVALID_MEDIA')
                if wav.getnframes() > 600 * 16000:
                    raise MediaFailure('DURATION_LIMIT')
                if wav.getnframes() <= 0:
                    raise MediaFailure('INVALID_MEDIA')
                transcripts, usages = [], []
                while frames := wav.readframes(240 * 16000):
                    buffer = io.BytesIO()
                    with wave.open(buffer, 'wb') as segment:
                        segment.setnchannels(1)
                        segment.setsampwidth(2)
                        segment.setframerate(16000)
                        segment.writeframes(frames)
                    text, usage = await self.segment(buffer.getvalue())
                    transcripts.append(text)
                    usages.append(usage)
                    if sum(map(len, transcripts)) + len(transcripts) - 1 > 60000:
                        raise MediaFailure('PROVIDER_ERROR')
        except (wave.Error, EOFError):
            raise MediaFailure('INVALID_MEDIA') from None
        metadata = {'model': self.config.asr_model}
        if all(usage is not None for usage in usages):
            metadata['usage'] = {key: sum(usage[key] for usage in usages)
                                 for key in ('input_tokens', 'output_tokens', 'total_tokens')}
        path = directory / 'transcript.txt'
        path.write_text('\n'.join(transcripts), encoding='utf-8')
        return metadata, [('transcript', 'text/plain', path)]

    async def segment(self, data):
        encoded = base64.b64encode(data).decode('ascii')
        body = {'model': self.config.asr_model, 'stream': False,
                'messages': [{'role': 'user', 'content': [{'type': 'input_audio', 'input_audio':
                            {'data': 'data:audio/wav;base64,' + encoded, 'format': 'wav'}}]}],
                'asr_options': {'enable_itn': True}}
        headers = {'Authorization': 'Bearer ' + self.config.asr_api_key}
        if self.config.asr_workspace_id:
            headers['X-DashScope-WorkSpace'] = self.config.asr_workspace_id
        try:
            async with asyncio.timeout(self.config.asr_timeout_seconds):
                async with self.client.stream('POST', self.config.asr_base_url.rstrip('/') + '/chat/completions',
                                              json=body, headers=headers) as response:
                    if response.status_code in (401, 403):
                        raise MediaFailure('PROVIDER_AUTH')
                    if response.status_code == 429:
                        raise MediaFailure('PROVIDER_RATE_LIMIT')
                    if response.status_code != 200:
                        raise MediaFailure('PROVIDER_ERROR')
                    raw = bytearray()
                    async for chunk in response.aiter_bytes(65536):
                        raw.extend(chunk)
                        if len(raw) > 1 << 20:
                            raise MediaFailure('PROVIDER_ERROR')
            payload = json.loads(raw)
            text = payload['choices'][0]['message']['content']
            if not isinstance(text, str) or len(text) > 60000 or '\x00' in text:
                raise MediaFailure('PROVIDER_ERROR')
            reported = None
            usage = payload.get('usage')
            if isinstance(usage, dict):
                reported = {target: usage.get(original) for original, target in
                            [('prompt_tokens', 'input_tokens'), ('completion_tokens', 'output_tokens'),
                             ('total_tokens', 'total_tokens')]}
                if not all(type(value) is int and value >= 0 for value in reported.values()):
                    reported = None
            return text, reported
        except (httpx.TimeoutException, TimeoutError):
            raise MediaFailure('PROVIDER_TIMEOUT') from None
        except (httpx.TransportError, ValueError, KeyError, IndexError, TypeError):
            raise MediaFailure('PROVIDER_ERROR') from None

    async def close(self):
        await self.client.aclose()
