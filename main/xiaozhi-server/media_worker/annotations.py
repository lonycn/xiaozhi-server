"""B4 generation tasks: precise local frame capture and server-only annotation rendering."""
import asyncio
import json
import math
import re
import sys
from fractions import Fraction
from pathlib import Path
from .annotation_decode import dimensions, identifier
from .processor import Processor, command
from .transport import ARTIFACT_LIMITS, MediaFailure

CAPTURE_FIELDS = {'frame_id', 'source_attachment_id', 'requested_time_ms', 'capture_policy', 'expected_width', 'expected_height'}
ANNOTATION_FIELDS = {'annotation_id', 'frame_id', 'source_attachment_id', 'expected_width', 'expected_height', 'scene'}


class Annotations:
    def __init__(self, config):
        self.config = config
        self.processor = Processor(config)

    def parameters(self, task):
        metadata = task.get('metadata')
        capture = task.get('job_type') == 'capture_frame'
        fields = CAPTURE_FIELDS if capture else ANNOTATION_FIELDS
        if not isinstance(metadata, dict) or set(metadata) != fields:
            raise MediaFailure('INVALID_MEDIA')
        try:
            dimensions(metadata)
            if any(not identifier(metadata[field]) for field in ('frame_id', 'source_attachment_id')):
                raise ValueError()
            if capture:
                if metadata['capture_policy'] != 'first_at_or_after':
                    raise ValueError()
                time = metadata['requested_time_ms']
                if task.get('mime_type') == 'image/jpeg':
                    if time is not None:
                        raise ValueError()
                elif task.get('mime_type') in ('video/mp4', 'video/webm'):
                    if type(time) is not int or not 0 <= time < 180000:
                        raise ValueError()
                else:
                    raise ValueError()
            elif task.get('job_type') != 'annotation_preview' or task.get('mime_type') != 'image/jpeg' or not identifier(metadata['annotation_id']):
                raise ValueError()
        except (ValueError, TypeError, KeyError):
            raise MediaFailure('INVALID_MEDIA') from None
        return metadata

    async def process(self, task, source, directory):
        metadata = self.parameters(task)
        actual_time = None
        async with asyncio.timeout(self.config.process_timeout_seconds):
            if task['job_type'] == 'capture_frame' and task['mime_type'].startswith('video/'):
                source, actual_time = await self.capture_video(source, task['mime_type'], metadata, directory)
            parameters = directory / 'parameters.json'
            try:
                encoded = json.dumps(metadata, ensure_ascii=False, allow_nan=False, separators=(',', ':')).encode('utf-8')
            except (ValueError, UnicodeError, TypeError):
                raise MediaFailure('INVALID_MEDIA') from None
            if len(encoded) > (256 << 10) + 4096:
                raise MediaFailure('INVALID_MEDIA')
            parameters.write_bytes(encoded)
            font = self.config.annotation_font or str(Path(__file__).resolve().parents[2] /
                    'manager-web/public/generator/static/fonts/noto_qwen.ttf')
            if task['job_type'] == 'annotation_preview' and not Path(font).is_file():
                raise MediaFailure('PROCESSING_FAILED')
            mode = 'annotation' if task['job_type'] == 'annotation_preview' else 'capture'
            result = await command([sys.executable, '-m', 'media_worker.annotation_decode', mode,
                                    source, parameters, directory, font],
                                   timeout=self.config.process_timeout_seconds, max_output=16384)
            try:
                output = json.loads(result)
                if 'error' in output:
                    raise MediaFailure(output['error'])
                artifacts = []
                for kind, mime, filename in output['artifacts']:
                    if kind not in ('display', 'thumbnail') or mime != 'image/jpeg' or filename != kind + '.jpg':
                        raise ValueError()
                    path = directory / filename
                    if not path.is_file() or not 0 < path.stat().st_size <= ARTIFACT_LIMITS[kind]:
                        raise ValueError()
                    artifacts.append((kind, mime, path))
                if {kind for kind, _, _ in artifacts} != {'display', 'thumbnail'}:
                    raise ValueError()
                result_metadata = output['metadata']
            except (ValueError, KeyError, TypeError):
                raise MediaFailure('PROCESSING_FAILED') from None
            if actual_time is not None:
                result_metadata['actual_time_ms'] = actual_time
            return result_metadata, artifacts

    async def capture_video(self, source, mime, metadata, directory):
        options = self.processor.input_options(source, mime)
        probe = await command([self.config.ffprobe, '-v', 'error', '-threads', '1', *options,
            '-show_streams', '-show_format', '-show_entries',
            'stream=index,codec_type,codec_name,width,height,time_base,duration:stream_disposition=attached_pic:format=duration',
            '-of', 'json', source], timeout=self.config.process_timeout_seconds)
        try:
            info = json.loads(probe)
            stream = next(stream for stream in info['streams'] if stream.get('codec_type') == 'video'
                          and not stream.get('disposition', {}).get('attached_pic'))
            index = stream['index']
            if type(index) is not int or index < 0:
                raise ValueError()
            if not 0 < stream['width'] <= 3840 or not 0 < stream['height'] <= 2160:
                raise MediaFailure('PIXEL_LIMIT')
            if mime == 'video/webm' and stream.get('codec_name') not in ('vp8', 'vp9', 'av1'):
                raise MediaFailure('TYPE_MISMATCH')
            time_base = Fraction(stream['time_base'])
            if time_base <= 0:
                raise ValueError()
            try:
                known_duration = Fraction(stream['duration'])
            except (KeyError, ValueError):
                known_duration = None
            if known_duration is not None:
                if known_duration <= 0:
                    raise ValueError()
                if known_duration > 180:
                    raise MediaFailure('DURATION_LIMIT')
        except (ValueError, KeyError, TypeError, StopIteration, ZeroDivisionError):
            raise MediaFailure('INVALID_MEDIA') from None
        raw_frames = await command([self.config.ffprobe, '-v', 'error', '-threads', '1', *options,
            '-select_streams', str(index), '-show_frames', '-show_entries', 'frame=pts,pkt_duration,duration',
            '-of', 'json', source], timeout=self.config.process_timeout_seconds, max_output=8 << 20)
        try:
            frames = json.loads(raw_frames)['frames']
            if not isinstance(frames, list) or not 0 < len(frames) <= 100000:
                raise ValueError()
            if any(type(frame.get('pts')) is not int for frame in frames):
                raise ValueError()
            origin = frames[0]['pts'] * time_base
            times = [(frame['pts'] * time_base - origin) * 1000 for frame in frames]
            if any(current < previous for previous, current in zip(times, times[1:])):
                raise ValueError()
            duration = known_duration
            if duration is None:
                duration = Fraction(info['format']['duration'])
                if origin > 0 and duration > origin:
                    duration -= origin
            if duration <= 0:
                raise ValueError()
            if duration > 180 or times[-1] > 180000:
                raise MediaFailure('DURATION_LIMIT')
            requested = metadata['requested_time_ms']
            if requested >= duration * 1000:
                raise ValueError()
            selected = next((i for i, time in enumerate(times) if time >= requested), None)
            if selected is None:
                raise ValueError()
            # Report integer ms by ceiling the actual rational timestamp: never earlier than its PTS.
            actual = math.ceil(times[selected])
        except (ValueError, KeyError, TypeError, ZeroDivisionError):
            raise MediaFailure('INVALID_MEDIA') from None
        output = directory / 'captured.jpg'
        # Decode from origin, select exact decoded-frame index; no timestamp seek or B3 sample reuse.
        await command([self.config.ffmpeg, '-nostdin', '-hide_banner', '-loglevel', 'error', '-xerror',
            '-threads', '1', *options, '-err_detect', 'explode', '-i', source, '-map', f'0:{index}',
            '-vf', f'select=eq(n\\,{selected})', '-frames:v', '1', '-fps_mode', 'vfr', '-an', '-sn', '-dn',
            '-map_metadata', '-1', '-threads', '1', '-q:v', '2', '-y', output],
            timeout=self.config.process_timeout_seconds)
        if not output.is_file() or not 0 < output.stat().st_size <= ARTIFACT_LIMITS['display']:
            raise MediaFailure('INVALID_MEDIA')
        return output, actual
