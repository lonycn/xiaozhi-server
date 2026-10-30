"""Internal HTTP only: never accesses manager storage paths or databases."""
import asyncio
import hashlib
import re
from pathlib import Path
import httpx


class LeaseLost(Exception):
    pass


class ControlError(Exception):
    pass


class MediaFailure(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


LIMITS = {'image/jpeg': 20 << 20, 'image/png': 20 << 20, 'image/webp': 20 << 20,
          'application/pdf': 20 << 20, 'text/plain': 20 << 20,
          'video/mp4': 100 << 20, 'video/webm': 100 << 20,
          'audio/wav': 50 << 20, 'audio/mpeg': 50 << 20, 'audio/mp4': 50 << 20, 'audio/ogg': 50 << 20}
ARTIFACT_LIMITS = {'display': 20 << 20, 'thumbnail': 1 << 20, 'poster': 2 << 20,
                  'text': 240000, 'transcript': 240000, 'audio': 20 << 20,
                  **{f'frame_{i}': 2 << 20 for i in range(6)}}


def wire_integer(value, minimum, maximum):
    """Accept Java long strings without accepting coercions, IDs or user scene values."""
    if isinstance(value, str):
        # Bound length before int() and require canonical ASCII decimal notation.
        if not re.fullmatch(r'0|[1-9][0-9]{0,18}', value):
            raise MediaFailure('INVALID_MEDIA')
        value = int(value)
    if type(value) is not int or not minimum <= value <= maximum:
        raise MediaFailure('INVALID_MEDIA')
    return value


def normalize_task(task):
    if task is None:
        return None
    if not isinstance(task, dict):
        raise MediaFailure('INVALID_MEDIA')
    result = dict(task)
    result['size'] = wire_integer(task.get('size'), 1, LIMITS.get(task.get('mime_type'), 0))
    for key, lower, upper in [('attempt', 1, 3), ('lease_seconds', 1, 300)]:
        if key in task:
            result[key] = wire_integer(task[key], lower, upper)
    metadata = task.get('metadata')
    if metadata is not None:
        if not isinstance(metadata, dict):
            raise MediaFailure('INVALID_MEDIA')
        metadata = dict(metadata)
        bounds = {'width': (1, 8192), 'height': (1, 8192),
                  'expected_width': (1, 8192), 'expected_height': (1, 8192),
                  'duration_ms': (0, 600000), 'time_ms': (0, 180000),
                  'requested_time_ms': (0, 179999), 'actual_time_ms': (0, 180000)}
        for key, (lower, upper) in bounds.items():
            if key in metadata:
                if key == 'requested_time_ms' and metadata[key] is None:
                    continue
                metadata[key] = wire_integer(metadata[key], lower, upper)
        if 'frames' in metadata:
            frames = metadata['frames']
            if not isinstance(frames, list) or len(frames) > 6 or any(not isinstance(frame, dict) for frame in frames):
                raise MediaFailure('INVALID_MEDIA')
            metadata['frames'] = [dict(frame) for frame in frames]
            for frame in metadata['frames']:
                if 'time_ms' in frame:
                    frame['time_ms'] = wire_integer(frame['time_ms'], 0, 180000)
        # Deliberately never recurse into scene, text, IDs, URLs or arbitrary keys.
        result['metadata'] = metadata
    return result


class Manager:
    def __init__(self, config, client=None):
        self.worker_id = config.worker_id
        self.client = client or httpx.AsyncClient(
            base_url=config.manager_url.rstrip('/') + '/',
            headers={'X-Media-Worker-Key': config.worker_key},
            timeout=httpx.Timeout(config.request_timeout_seconds),
            follow_redirects=False, trust_env=False)

    @staticmethod
    def path(task, suffix):
        if not re.fullmatch(r'[0-9]+', str(task.get('job_id', ''))):
            raise LeaseLost()
        return 'internal/media-worker/jobs/' + str(task['job_id']) + '/' + suffix

    @staticmethod
    def check(response, binary=False):
        if response.status_code in (401, 403, 404, 409, 410):
            raise LeaseLost()
        if response.status_code != 200:
            raise ControlError()
        if binary:
            return
        try:
            payload = response.json()
            if not isinstance(payload, dict) or payload.get('code') != 0 or 'data' not in payload:
                raise ControlError()
            return payload['data']
        except ValueError:
            raise ControlError() from None

    async def post(self, path, body, retry=False):
        for attempt in range(3 if retry else 1):
            try:
                return self.check(await self.client.post('internal/media-worker/' + path, json=body))
            except (httpx.TransportError, ControlError):
                if not retry or attempt == 2:
                    raise ControlError() from None
                await asyncio.sleep(.25 * 2 ** attempt)

    async def claim(self):
        return normalize_task(await self.post('claim', {'worker_id': self.worker_id}))

    async def callback(self, task, action, **body):
        path = self.path(task, action).removeprefix('internal/media-worker/')
        return await self.post(path, {'lease_token': task['lease_token'], **body}, retry=action != 'heartbeat')

    async def download(self, task, target):
        maximum = LIMITS.get(task.get('mime_type'), 0)
        declared = wire_integer(task.get('size'), 1, maximum)
        digest = hashlib.sha256()
        size = 0
        try:
            async with self.client.stream('GET', self.path(task, 'input'),
                                          headers={'X-Media-Lease': task['lease_token']}) as response:
                self.check(response, binary=True)
                if response.headers.get('content-encoding', 'identity') != 'identity':
                    raise MediaFailure('INVALID_MEDIA')
                with Path(target).open('xb') as output:
                    async for chunk in response.aiter_raw(65536):
                        size += len(chunk)
                        if size > declared or size > maximum:
                            raise MediaFailure('INVALID_MEDIA')
                        output.write(chunk)
                        digest.update(chunk)
        except httpx.TransportError:
            raise ControlError() from None
        if size != declared or digest.hexdigest() != task.get('sha256'):
            raise MediaFailure('INVALID_MEDIA')

    async def upload(self, task, kind, mime, path):
        if kind not in ARTIFACT_LIMITS or not 0 <= Path(path).stat().st_size <= ARTIFACT_LIMITS[kind]:
            raise MediaFailure('PROCESSING_FAILED')
        async def chunks():
            with Path(path).open('rb') as source:
                while chunk := source.read(65536):
                    yield chunk
        # Immutable bytes allow retry after a lost persistence ACK.
        for attempt in range(3):
            try:
                response = await self.client.put(self.path(task, 'artifacts/' + kind), content=chunks(),
                    headers={'X-Media-Lease': task['lease_token'], 'Content-Type': mime,
                             'Content-Length': str(Path(path).stat().st_size)})
                self.check(response)
                return
            except (httpx.TransportError, ControlError):
                if attempt == 2:
                    raise ControlError() from None
                await asyncio.sleep(.25 * 2 ** attempt)

    async def close(self):
        await self.client.aclose()
