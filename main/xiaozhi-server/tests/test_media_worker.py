"""Synthetic fixtures test decode/transport; no physical or provider acceptance."""
import asyncio
import hashlib
import io
import json
import os
import shutil
import sys
import wave
import httpx
from PIL import Image
from pypdf import PdfWriter
import pytest
from media_worker.config import Config
from media_worker.processor import Processor, command
from media_worker.transport import Manager, LeaseLost, ControlError, MediaFailure
from media_worker.worker import Worker
from media_worker.transcription import Transcriber


def config(**kwargs):
    return Config(manager_url='http://manager.test', worker_key='private', worker_id='test', **kwargs)


def task(data=b'hello'):
    return dict(job_id='1', attachment_id='2', job_type='inspect_media', attempt=1, lease_token='lease',
                lease_seconds=90, filename='test.txt', mime_type='text/plain', size=len(data),
                sha256=hashlib.sha256(data).hexdigest(), metadata={})


async def inspect(tmp_path, mime, filename, data):
    source = tmp_path / 'input'
    source.write_bytes(data)
    claim = task()
    claim.update(mime_type=mime, filename=filename)
    return await Processor(config()).inspect(claim, source, tmp_path)


def wav_bytes(seconds=1):
    output = io.BytesIO()
    with wave.open(output, 'wb') as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(16000)
        wav.writeframes(b'\x00\x00' * (16000 * seconds))
    return output.getvalue()


@pytest.mark.asyncio
async def test_image_exif_rotation_and_stripping(tmp_path):
    output = io.BytesIO()
    exif = Image.Exif()
    exif[274] = 6
    exif[270] = 'private original metadata'
    Image.new('RGB', (40, 20), 'red').save(output, 'JPEG', exif=exif)
    metadata, artifacts = await inspect(tmp_path, 'image/jpeg', 'photo.jpg', output.getvalue())
    assert (metadata['width'], metadata['height']) == (20, 40)
    for _, mime, path in artifacts:
        with Image.open(path) as picture:
            assert mime == 'image/jpeg' and not picture.getexif() and 'exif' not in picture.info


@pytest.mark.asyncio
@pytest.mark.parametrize('mime,name,data,code', [
    ('image/png', 'bad.png', b'not png', 'INVALID_MEDIA'),
    ('image/jpeg', 'bad.png', b'x', 'TYPE_MISMATCH'),
    ('text/plain', 'bad.txt', b'<html>payload</html>', 'TYPE_MISMATCH'),
    ('text/plain', 'bad.txt', b'prefix<script>x</script>', 'TYPE_MISMATCH'),
    ('text/plain', 'bad.txt', b'abc\x00def', 'TYPE_MISMATCH'),
    ('text/plain', 'bad.txt', b'\xff', 'INVALID_MEDIA'),
    ('application/pdf', 'bad.pdf', b'%PDF-1.7\ninvalid', 'INVALID_MEDIA'),
    ('video/mp4', 'bad.mp4', b'#EXTM3U\nhttps://test.invalid', 'TYPE_MISMATCH'),
])
async def test_reject_invalid_media(tmp_path, mime, name, data, code):
    with pytest.raises(MediaFailure, match=code):
        await inspect(tmp_path, mime, name, data)


@pytest.mark.asyncio
async def test_wrong_real_image_format_and_animation(tmp_path):
    output = io.BytesIO()
    Image.new('RGB', (4, 4)).save(output, 'PNG')
    with pytest.raises(MediaFailure, match='TYPE_MISMATCH'):
        await inspect(tmp_path, 'image/jpeg', 'x.jpg', output.getvalue())
    output = io.BytesIO()
    Image.new('RGB', (4, 4)).save(output, 'PNG', save_all=True,
        append_images=[Image.new('RGB', (4, 4), 'white')], duration=10)
    with pytest.raises(MediaFailure, match='INVALID_MEDIA'):
        await inspect(tmp_path, 'image/png', 'x.png', output.getvalue())


@pytest.mark.asyncio
async def test_single_side_pixel_limit(tmp_path):
    output = io.BytesIO()
    Image.new('RGB', (8193, 1)).save(output, 'PNG')
    with pytest.raises(MediaFailure, match='PIXEL_LIMIT'):
        await inspect(tmp_path, 'image/png', 'x.png', output.getvalue())


@pytest.mark.asyncio
async def test_text_truncation_and_real_pdf_page_limit(tmp_path):
    metadata, artifacts = await inspect(tmp_path, 'text/plain', 'x.txt', ('中' * 60001).encode())
    assert metadata['text_truncated'] and len(artifacts[0][2].read_text()) == 60000
    pdf = io.BytesIO()
    writer = PdfWriter()
    writer.add_blank_page(width=100, height=100)
    writer.write(pdf)
    metadata, _ = await inspect(tmp_path, 'application/pdf', 'x.pdf', pdf.getvalue())
    assert metadata['text_available'] is False
    for _ in range(200): writer.add_blank_page(width=100, height=100)
    pdf = io.BytesIO()
    writer.write(pdf)
    with pytest.raises(MediaFailure, match='PAGE_LIMIT'):
        await inspect(tmp_path, 'application/pdf', 'x.pdf', pdf.getvalue())


@pytest.mark.asyncio
@pytest.mark.skipif(not shutil.which('ffmpeg'), reason='FFmpeg unavailable')
async def test_real_audio_normalization(tmp_path):
    metadata, artifacts = await inspect(tmp_path, 'audio/wav', 'record.wav', wav_bytes())
    assert 990 <= metadata['duration_ms'] <= 1010 and metadata['has_audio']
    with wave.open(str(artifacts[0][2])) as wav:
        assert (wav.getnchannels(), wav.getframerate(), wav.getsampwidth()) == (1, 16000, 2)


@pytest.mark.asyncio
@pytest.mark.skipif(not shutil.which('ffmpeg'), reason='FFmpeg unavailable')
async def test_real_video_poster_frames(tmp_path):
    source = tmp_path / 'synthetic.mp4'
    await command(['ffmpeg', '-nostdin', '-v', 'error', '-f', 'lavfi', '-i', 'color=c=blue:s=64x48:d=1',
                   '-c:v', 'mpeg4', '-y', source])
    metadata, artifacts = await inspect(tmp_path, 'video/mp4', 'clip.mp4', source.read_bytes())
    assert metadata['width'] == 64 and metadata['height'] == 48
    assert len(metadata['frames']) == 1 and metadata['frames'][0]['time_ms'] == 500
    assert {kind for kind, _, _ in artifacts} == {'poster', 'frame_0'}
    for _, _, path in artifacts:
        with Image.open(path) as image: image.verify()


class Bytes(httpx.AsyncByteStream):
    def __init__(self, data): self.data = data
    async def __aiter__(self):
        for index in range(0, len(self.data), 3): yield self.data[index:index+3]


def client(handler):
    return httpx.AsyncClient(base_url='http://manager.test/', transport=httpx.MockTransport(handler))


@pytest.mark.asyncio
@pytest.mark.parametrize('changed', [False, True])
async def test_stream_download_hash(tmp_path, changed):
    async def handler(request):
        assert request.headers['X-Media-Lease'] == 'lease'
        return httpx.Response(200, stream=Bytes(b'changed' if changed else b'hello'))
    manager = Manager(config(), client(handler))
    if changed:
        with pytest.raises(MediaFailure, match='INVALID_MEDIA'):
            await manager.download(task(), tmp_path / 'input')
    else:
        await manager.download(task(), tmp_path / 'input')
        assert (tmp_path / 'input').read_bytes() == b'hello'
    await manager.close()


@pytest.mark.asyncio
async def test_claim_lost_ack_no_retry_and_recovery():
    calls = 0
    async def handler(request):
        nonlocal calls
        calls += 1
        if calls == 1: raise httpx.ReadTimeout('sensitive provider content')
        return httpx.Response(200, json={'code': 0, 'msg': '', 'data': task()})
    manager = Manager(config(), client(handler))
    with pytest.raises(ControlError): await manager.claim()
    assert calls == 1
    assert (await manager.claim())['job_id'] == '1'
    await manager.close()


@pytest.mark.asyncio
@pytest.mark.parametrize('status', [401, 403, 404, 409, 410])
async def test_revocation_not_retried(status):
    calls = 0
    async def handler(request):
        nonlocal calls
        calls += 1
        return httpx.Response(status)
    manager = Manager(config(), client(handler))
    with pytest.raises(LeaseLost): await manager.callback(task(), 'complete', metadata={})
    assert calls == 1
    await manager.close()


@pytest.mark.asyncio
async def test_artifact_lost_ack_reuses_identical_bytes(tmp_path):
    seen = []
    async def handler(request):
        seen.append(await request.aread())
        if len(seen) == 1: raise httpx.ReadTimeout('lost ACK')
        return httpx.Response(200, json={'code': 0, 'data': None})
    manager = Manager(config(), client(handler))
    path = tmp_path / 'output'
    path.write_bytes(b'private evidence')
    await manager.upload(task(), 'text', 'text/plain', path)
    assert seen == [b'private evidence', b'private evidence']
    await manager.close()


@pytest.mark.asyncio
async def test_heartbeat_revocation_cancels_download_and_cleans_temp(tmp_path):
    cleaned = asyncio.Event()
    class FakeManager:
        async def download(self, task, source):
            source.write_bytes(b'partial')
            try: await asyncio.sleep(10)
            finally: cleaned.set()
        async def callback(self, task, action, **body):
            assert action == 'heartbeat'
            raise LeaseLost()
    conf = config(temp_root=str(tmp_path))
    conf.heartbeat_seconds = .01
    worker = Worker(conf, FakeManager(), None, None)
    with pytest.raises(LeaseLost): await worker.execute(task())
    assert cleaned.is_set() and list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
async def test_cancellation_terminates_child_process(tmp_path):
    pid_file = tmp_path / 'pid'
    running = asyncio.create_task(command([sys.executable, '-c',
        'import os,time,pathlib;pathlib.Path(__import__("sys").argv[1]).write_text(str(os.getpid()));time.sleep(30)', pid_file]))
    for _ in range(100):
        if pid_file.exists(): break
        await asyncio.sleep(.01)
    assert pid_file.exists()
    pid = int(pid_file.read_text())
    running.cancel()
    with pytest.raises(asyncio.CancelledError): await running
    with pytest.raises(ProcessLookupError): os.kill(pid, 0)


@pytest.mark.asyncio
async def test_command_timeout():
    with pytest.raises(MediaFailure, match='PROCESSING_TIMEOUT'):
        await command([sys.executable, '-c', 'import time;time.sleep(10)'], timeout=.02)


@pytest.mark.asyncio
async def test_asr_not_configured(tmp_path):
    provider = Transcriber(config())
    with pytest.raises(MediaFailure, match='TRANSCRIPTION_NOT_CONFIGURED'):
        await provider.transcribe(tmp_path / 'unused', tmp_path)
    await provider.close()


@pytest.mark.asyncio
@pytest.mark.parametrize('with_usage', [True, False])
async def test_asr_response_usage_and_240_second_segmentation(tmp_path, with_usage):
    calls = []
    async def handler(request):
        calls.append(json.loads(await request.aread()))
        result = {'choices': [{'message': {'content': 'actual provider text'}}]}
        if with_usage: result['usage'] = {'prompt_tokens': 3, 'completion_tokens': 4, 'total_tokens': 7}
        return httpx.Response(200, json=result)
    provider = Transcriber(config(asr_api_key='private'), client(handler))
    source = tmp_path / 'audio.wav'
    source.write_bytes(wav_bytes(241))
    metadata, artifacts = await provider.transcribe(source, tmp_path)
    assert len(calls) == 2
    assert artifacts[0][2].read_text() == 'actual provider text\nactual provider text'
    assert ('usage' in metadata) is with_usage
    if with_usage: assert metadata['usage']['total_tokens'] == 14
    await provider.close()


@pytest.mark.asyncio
async def test_pdf_extracts_actual_content(tmp_path):
    from pypdf.generic import DictionaryObject, NameObject, DecodedStreamObject
    writer = PdfWriter()
    page = writer.add_blank_page(width=100, height=100)
    font = DictionaryObject({NameObject('/Type'): NameObject('/Font'),
        NameObject('/Subtype'): NameObject('/Type1'), NameObject('/BaseFont'): NameObject('/Helvetica')})
    page[NameObject('/Resources')] = DictionaryObject({NameObject('/Font'):
        DictionaryObject({NameObject('/F1'): writer._add_object(font)})})
    content = DecodedStreamObject()
    content.set_data(b'BT /F1 12 Tf 10 50 Td (actual PDF content) Tj ET')
    page[NameObject('/Contents')] = writer._add_object(content)
    pdf = io.BytesIO()
    writer.write(pdf)
    metadata, artifacts = await inspect(tmp_path, 'application/pdf', 'real.pdf', pdf.getvalue())
    assert metadata['text_available'] and artifacts[0][2].read_text() == 'actual PDF content'


@pytest.mark.asyncio
@pytest.mark.skipif(not shutil.which('ffmpeg'), reason='FFmpeg unavailable')
async def test_long_audio_rejected_before_decode(tmp_path):
    with pytest.raises(MediaFailure, match='DURATION_LIMIT'):
        await inspect(tmp_path, 'audio/wav', 'long.wav', wav_bytes(601))


@pytest.mark.asyncio
async def test_output_and_process_timeout_limits():
    with pytest.raises(MediaFailure, match='PROCESSING_FAILED'):
        await command([sys.executable, '-c', 'print("x" * 20000)'], max_output=100)


@pytest.mark.asyncio
async def test_input_revocation_and_oversize(tmp_path):
    async def handler(request): return httpx.Response(409)
    manager = Manager(config(), client(handler))
    with pytest.raises(LeaseLost): await manager.download(task(), tmp_path / 'input')
    invalid = task()
    invalid['size'] = (20 << 20) + 1
    with pytest.raises(MediaFailure, match='INVALID_MEDIA'):
        await manager.download(invalid, tmp_path / 'input')
    assert not (tmp_path / 'input').exists()
    await manager.close()


@pytest.mark.asyncio
@pytest.mark.parametrize('status,code', [(401, 'PROVIDER_AUTH'), (429, 'PROVIDER_RATE_LIMIT'), (500, 'PROVIDER_ERROR')])
async def test_provider_errors_sanitized(tmp_path, status, code):
    async def handler(request): return httpx.Response(status, text='secret upstream response')
    provider = Transcriber(config(asr_api_key='private'), client(handler))
    source = tmp_path / 'audio.wav'
    source.write_bytes(wav_bytes())
    with pytest.raises(MediaFailure, match=code) as error:
        await provider.transcribe(source, tmp_path)
    assert 'secret' not in str(error.value) and not (tmp_path / 'transcript.txt').exists()
    await provider.close()


@pytest.mark.asyncio
async def test_worker_end_to_end_http_flow(tmp_path):
    raw = '真实UTF8证据'.encode()
    claimed = task(raw)
    calls = []
    async def handler(request):
        calls.append(request.url.path)
        if request.url.path.endswith('/input'): return httpx.Response(200, stream=Bytes(raw))
        if '/artifacts/' in request.url.path:
            assert await request.aread() == raw and request.headers['content-type'] == 'text/plain'
        if request.url.path.endswith('/complete'):
            assert json.loads(await request.aread())['metadata']['text_available']
        return httpx.Response(200, json={'code': 0, 'data': None})
    conf = config(temp_root=str(tmp_path))
    manager = Manager(conf, client(handler))
    transcriber = Transcriber(conf)
    await Worker(conf, manager, Processor(conf), transcriber).execute(claimed)
    assert calls == ['/internal/media-worker/jobs/1/input', '/internal/media-worker/jobs/1/artifacts/text',
                     '/internal/media-worker/jobs/1/complete']
    assert list(tmp_path.iterdir()) == []
    await manager.close()
    await transcriber.close()


@pytest.mark.asyncio
@pytest.mark.skipif(not shutil.which('ffmpeg'), reason='FFmpeg unavailable')
async def test_video_with_audio_also_normalizes_for_transcription(tmp_path):
    source = tmp_path / 'synthetic-audio.mp4'
    await command(['ffmpeg', '-nostdin', '-v', 'error', '-f', 'lavfi', '-i', 'color=c=blue:s=64x48:d=1',
                   '-f', 'lavfi', '-i', 'sine=frequency=440:duration=1', '-c:v', 'mpeg4', '-c:a', 'aac', '-y', source])
    metadata, artifacts = await inspect(tmp_path, 'video/mp4', 'clip.mp4', source.read_bytes())
    assert metadata['has_audio'] and {kind for kind, _, _ in artifacts} == {'poster', 'frame_0', 'audio'}
    audio = next(path for kind, _, path in artifacts if kind == 'audio')
    with wave.open(str(audio)) as wav:
        assert wav.getnchannels() == 1 and wav.getframerate() == 16000
