import asyncio
import json
import math
import os
import signal
import resource
import sys
from pathlib import Path
from .transport import ARTIFACT_LIMITS, MediaFailure

EXTENSIONS = {'image/jpeg': ('.jpg', '.jpeg'), 'image/png': ('.png',), 'image/webp': ('.webp',),
              'application/pdf': ('.pdf',), 'text/plain': ('.txt',), 'video/mp4': ('.mp4',),
              'video/webm': ('.webm',), 'audio/wav': ('.wav',), 'audio/mpeg': ('.mp3',),
              'audio/mp4': ('.m4a', '.mp4'), 'audio/ogg': ('.ogg', '.oga')}


def resource_limits():
    resource.setrlimit(resource.RLIMIT_CPU, (120, 120))
    resource.setrlimit(resource.RLIMIT_FSIZE, (25 << 20, 25 << 20))
    resource.setrlimit(resource.RLIMIT_NOFILE, (128, 128))
    if sys.platform.startswith('linux'):
        resource.setrlimit(resource.RLIMIT_AS, (1 << 30, 1 << 30))


async def monitor_memory(process, maximum=768 << 20):
    """RSS guard also covers native decoder allocations on macOS."""
    while process.returncode is None:
        if sys.platform.startswith('linux'):
            try:
                status = Path(f'/proc/{process.pid}/status').read_text()
                line = next((line for line in status.splitlines() if line.startswith('VmRSS:')), '')
                rss = int(line.split()[1]) * 1024 if line else 0
            except (FileNotFoundError, ProcessLookupError):
                return
        elif sys.platform == 'darwin':
            probe = await asyncio.create_subprocess_exec('/bin/ps', '-o', 'rss=', '-p', str(process.pid),
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
            try:
                output, _ = await asyncio.wait_for(probe.communicate(), 1)
                rss = int(output.strip() or b'0') * 1024
            finally:
                if probe.returncode is None:
                    probe.kill()
                    await probe.wait()
        else:
            raise MediaFailure('PROCESSING_FAILED')
        if rss > maximum:
            raise MediaFailure('PROCESSING_FAILED')
        await asyncio.sleep(.1)


async def command(args, timeout=60, max_output=1 << 20):
    """Bound stdout/stderr and kill the complete process group on timeout/cancellation."""
    process = await asyncio.create_subprocess_exec(*map(str, args), stdin=asyncio.subprocess.DEVNULL,
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
        start_new_session=True, preexec_fn=resource_limits if os.name == 'posix' else None)
    async def read(stream):
        data = bytearray()
        while chunk := await stream.read(65536):
            data.extend(chunk)
            if len(data) > max_output:
                raise MediaFailure('PROCESSING_FAILED')
        return bytes(data)
    readers = [asyncio.create_task(read(process.stdout)), asyncio.create_task(read(process.stderr)),
               asyncio.create_task(monitor_memory(process))]
    try:
        async with asyncio.timeout(timeout):
            result = await asyncio.gather(*readers)
            code = await process.wait()
            if code:
                raise MediaFailure('INVALID_MEDIA')
            return result[0]
    except TimeoutError:
        raise MediaFailure('PROCESSING_TIMEOUT') from None
    finally:
        if process.returncode is None:
            if os.name == 'posix':
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            else:
                process.kill()
        await process.wait()
        for reader in readers:
            reader.cancel()
        await asyncio.gather(*readers, return_exceptions=True)


class Processor:
    def __init__(self, config):
        self.config = config

    async def inspect(self, task, source, directory):
        mime = task['mime_type']
        filename = task.get('filename', '')
        if (not isinstance(filename, str) or '/' in filename or '\\' in filename
                or any(ord(c) < 32 for c in filename)
                or Path(filename).suffix.lower() not in EXTENSIONS.get(mime, ())):
            raise MediaFailure('TYPE_MISMATCH')
        async with asyncio.timeout(self.config.process_timeout_seconds):
            if mime.startswith('image/') or mime in ('application/pdf', 'text/plain'):
                raw = await command([sys.executable, '-m', 'media_worker.decode', source, mime, directory],
                                    timeout=self.config.process_timeout_seconds, max_output=16384)
                try:
                    result = json.loads(raw)
                except ValueError:
                    raise MediaFailure('PROCESSING_FAILED') from None
                if 'error' in result:
                    raise MediaFailure(result['error'])
            else:
                result = await self.av(source, mime, directory)
        artifacts = []
        for kind, artifact_mime, name in result['artifacts']:
            path = directory / name
            if not path.is_file() or path.stat().st_size > ARTIFACT_LIMITS[kind]:
                raise MediaFailure('PROCESSING_FAILED')
            artifacts.append((kind, artifact_mime, path))
        return result['metadata'], artifacts

    def input_options(self, source, mime):
        with source.open('rb') as raw:
            header = raw.read(4096)
            head = header[:16]
        if mime in ('video/mp4', 'audio/mp4'):
            if len(head) < 12 or head[4:8] != b'ftyp':
                raise MediaFailure('TYPE_MISMATCH')
            return ['-protocol_whitelist', 'file,pipe', '-f', 'mov', '-enable_drefs', '0', '-use_absolute_path', '0']
        formats = {'video/webm': ('matroska', head.startswith(b'\x1aE\xdf\xa3') and b'\x42\x82\x84webm' in header),
                   'audio/wav': ('wav', head.startswith(b'RIFF') and head[8:12] == b'WAVE'),
                   'audio/ogg': ('ogg', head.startswith(b'OggS')),
                   'audio/mpeg': ('mp3', head.startswith(b'ID3') or (len(head) >= 2 and head[0] == 255 and head[1] & 224 == 224))}
        demux, valid = formats.get(mime, ('', False))
        if not valid:
            raise MediaFailure('TYPE_MISMATCH')
        return ['-protocol_whitelist', 'file,pipe', '-f', demux]

    async def av(self, source, mime, directory):
        options = self.input_options(source, mime)
        probe = await command([self.config.ffprobe, '-v', 'error', *options, '-show_format', '-show_streams', '-of', 'json', source])
        try:
            data = json.loads(probe)
            streams = data['streams']
            duration = float(data['format'].get('duration', 'nan'))
        except (ValueError, KeyError, TypeError):
            raise MediaFailure('INVALID_MEDIA') from None
        video = next((s for s in streams if s.get('codec_type') == 'video' and not s.get('disposition', {}).get('attached_pic')), None)
        audio = next((s for s in streams if s.get('codec_type') == 'audio'), None)
        is_video = mime.startswith('video/')
        if not math.isfinite(duration) or duration <= 0:
            raise MediaFailure('INVALID_MEDIA')
        if duration > (180 if is_video else 600):
            raise MediaFailure('DURATION_LIMIT')
        if (is_video and video is None) or (not is_video and (audio is None or video is not None)):
            raise MediaFailure('TYPE_MISMATCH')
        if mime == 'video/webm' and (not video or video.get('codec_name') not in ('vp8', 'vp9', 'av1')):
            raise MediaFailure('TYPE_MISMATCH')
        if is_video and (not 0 < video.get('width', 0) <= 3840 or not 0 < video.get('height', 0) <= 2160):
            raise MediaFailure('PIXEL_LIMIT')
        base = [self.config.ffmpeg, '-nostdin', '-hide_banner', '-loglevel', 'error', '-xerror',
                '-threads', '1', *options, '-err_detect', 'explode', '-i', source]
        # Decode every packet, so a valid header alone cannot make corrupt media ready.
        await command([*base, '-map', '0:v:0?' if is_video else '0:a:0', '-map', '0:a:0?', '-f', 'null', '-'],
                      timeout=self.config.process_timeout_seconds)
        metadata = {'mime_type': mime, 'duration_ms': round(duration * 1000), 'has_audio': audio is not None}
        artifacts = []
        if is_video:
            metadata.update(width=video['width'], height=video['height'])
            frames = []
            count = min(6, max(1, math.ceil(duration / 30)))
            for index in range(count):
                seconds = duration * (index + .5) / count
                name = f'frame_{index}.jpg'
                await command([*base, '-ss', f'{seconds:.3f}', '-map', '0:v:0', '-an', '-frames:v', '1',
                               '-vf', 'scale=1280:720:force_original_aspect_ratio=decrease', '-q:v', '3',
                               '-map_metadata', '-1', '-threads', '1', '-y', directory / name])
                artifacts.append([f'frame_{index}', 'image/jpeg', name])
                frames.append({'artifact': f'frame_{index}', 'time_ms': round(seconds * 1000)})
            # Poster is the first decoded sample; no alternate source or fabricated frame.
            (directory / 'poster.jpg').write_bytes((directory / 'frame_0.jpg').read_bytes())
            artifacts.append(['poster', 'image/jpeg', 'poster.jpg'])
            metadata['frames'] = frames
        if audio is not None:
            await command([*base, '-map', '0:a:0', '-vn', '-ac', '1', '-ar', '16000', '-c:a', 'pcm_s16le',
                           '-map_metadata', '-1', '-threads', '1', '-y', directory / 'audio.wav'])
            artifacts.append(['audio', 'audio/wav', 'audio.wav'])
        return {'metadata': metadata, 'artifacts': artifacts}
