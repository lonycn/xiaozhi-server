# Independent private-media worker (B3)

Python **3.11+**, Pillow 11.3.0, pypdf 6.1.1, httpx 0.28.1 and local `ffmpeg`/`ffprobe` are required. This process imports no ESP32 core or database client. Original input and every artifact travel through the dedicated `/internal/media-worker` HTTP API; no manager object paths, public storage URLs, or user Bearer tokens are accepted. Concurrency is one task per process; the server issues a 90-second lease renewed every 12 seconds.

## Isolated setup

From `main/xiaozhi-server`:

```sh
python3.11 -m venv /tmp/xiaozhi-b3-media-venv
/tmp/xiaozhi-b3-media-venv/bin/pip install -r media_worker/requirements.txt
install -m 600 media_worker/config.example.json /private/path/media-worker.json
# Edit that private file: manager_url, dedicated worker_key and unique worker_id.
/tmp/xiaozhi-b3-media-venv/bin/python -m media_worker --config /private/path/media-worker.json --check
/tmp/xiaozhi-b3-media-venv/bin/python -m media_worker --config /private/path/media-worker.json
```

Do not put credentials in the example, repository, logs, or command arguments. Config field overrides use `MEDIA_WORKER_<UPPERCASE_FIELD>`; `MEDIA_WORKER_CONFIG` can select the file. Keep a private, writable `temp_root` if the system temporary directory is unsuitable. `--check` checks imports, config and executable availability without contacting a service. Graceful SIGINT/SIGTERM cancels the current HTTP requests, kills the child process group, and removes only this worker's random temporary directory. Lost heartbeat ACKs, permission denial and expired/revoked leases halt work. Lost claim ACKs are never automatically retried; the next poll is a new claim and the server remains responsible for lease expiry and attempt limits.

No worker, service, container or database is started by installation or tests. Enabling attachments, applying migrations, and starting this worker are separate deployment steps. Set the server's dedicated media-worker key and private storage before starting. The worker does not run storage garbage collection.

## Processing and limits

- Originals stream in 64KiB chunks with strict declared byte count and SHA-256 verification: images/PDF/UTF-8 text 20MiB, video 100MiB, audio 50MiB. Transport has a 5-second idle timeout and download has a 60-second total timeout by default.
- Filename extension, declared MIME, signatures and actual parser/container must agree. Animated raster files, HTML/SVG masquerading as text, invalid UTF-8, NUL text, encrypted/broken PDFs, playlists and unsupported formats fail.
- Pillow performs real verification and full decode, at most 24 million pixels and 8192 pixels per side. EXIF orientation is applied; a fresh white-background RGB raster produces JPEG `display` (20MiB) and `thumbnail` (512px, 1MiB), carrying no EXIF/ICC/comments. Originals are immutable.
- pypdf parses PDFs with at most 200 pages; actual text extraction and UTF-8 decoding produce at most 60,000 characters/240,000 bytes. `text_truncated` reports clipping; blank text is permitted and `text_available=false`.
- FFprobe verifies stream metadata. FFmpeg then decodes the full selected media stream before generating output, with `-xerror` and strict decoder error handling. Video is at most 180 seconds and 3840x2160; a JPEG poster plus 1–6 evenly spaced samples (at most 1280x720/2MiB each) carry actual requested `time_ms` points. Audio is at most 600 seconds and becomes mono 16kHz 16-bit PCM WAV (20MiB). Videos with audio also produce this normalized `audio` artifact for an independent transcription task; silent videos do not.
- FFmpeg/FFprobe receive only local random input paths, forced demuxers, and `file,pipe` protocol whitelists. MOV external data references and absolute reference paths are disabled. No shell, URL input, concat demuxer or external document renderer is used. PDF embedded content is not executed.
- All decoders are disposable subprocesses. Default total inspection time is 60 seconds (config maximum 120), CPU is capped at 120 seconds, per-output file cap is 25MiB, and captured stdout/stderr are bounded. Process RSS is monitored at 768MiB on macOS/Linux; Linux additionally caps virtual address space at 1GiB. The RSS guard polls every 100ms, so transient allocations can exceed the threshold before termination. POSIX open files are capped at 128. Each artifact is checked against its smaller kind-specific cap before upload. macOS/Linux are supported; other OSes fail closed.
- Artifact uploads retry immutable identical bytes after a lost ACK. Only successful persistence of every required artifact permits `complete`. Lease failure stops subprocesses and HTTP; temporary files are removed. Server-side CAS is responsible for authorization at each read/write/heartbeat/completion and for retry/cleanup of already persisted orphan artifacts.

## Optional audio transcription

`audio_transcription` is a separate task whose claim `size`, `sha256`, `filename=audio.wav`, and `mime_type=audio/wav` refer to the **normalized input**, not the original recording. The input is again downloaded and hash-checked. Inspect success does not depend on ASR.

Set private `asr_api_key`, `asr_base_url`, `asr_model` (default `qwen3-asr-flash`), optional `asr_workspace_id`, and `asr_timeout_seconds`. Endpoints must be HTTPS on Bailian DashScope or `*.maas.aliyuncs.com`. No ASR key produces `TRANSCRIPTION_NOT_CONFIGURED`, never invented text. The compatible chat-completions adapter sends `input_audio` with a data URL. Mono16k PCM input is validated and split sequentially into at most three 240-second WAV segments, preserving the full accepted recording up to 600 seconds. Each provider call has a bounded timeout, response <=1MiB and aggregate transcript <=60,000 characters. Partial successful segments are never uploaded as a complete transcript after a later segment fails. Only provider-returned text is written. Token usage is summed only when **every** segment returned complete valid usage; otherwise it is omitted. HTTP auth/rate-limit/timeout/errors become sanitized contract codes; raw provider response bodies are neither logged nor returned.

The adapter protocol is covered with mocked responses; live provider acceptance must be recorded separately. It follows the existing core `qwen3_asr_flash.py` provider's Qwen model/ASR-option intent while avoiding its global SDK configuration and raw exception logging. This worker does not import or alter that provider.

## Verification

```sh
/tmp/xiaozhi-b3-media-venv/bin/pip install pytest==8.4.2 pytest-asyncio==1.2.0
/tmp/xiaozhi-b3-media-venv/bin/python -m pytest --noconftest -q tests/test_media_worker.py
```

`--noconftest` is mandatory for this isolated suite: the parent ESP32 test conftest loads business configuration and monkey-patches HTTP clients and asyncio sleep. These tests use actual Pillow/pypdf/FFmpeg decoding of synthetic fixtures, bounded execution, EXIF stripping, malformed-media rejection, duration/page/pixel limits, UTF-8 clipping, streaming hash rejection, immutable upload recovery, revoked lease cleanup, child termination, and synthetic provider protocol responses. Synthetic fixtures do not constitute physical device, real user media, storage deployment, or live ASR acceptance.

## Provenance

`/Users/lengwang/code/aimeet/services/api/app/uploads.py` was read as a local reference for validation-before-storage, metadata removal and extension/MIME checks. No source code was copied. No root license was found for that local service, so this implementation is independently written; no right to redistribute that service is asserted. The existing repository's `ai_worker` supplied the internal HTTP Result/lease design pattern. Pillow, pypdf, httpx and FFmpeg retain their own licenses; install them separately rather than vendoring their source or binaries.
