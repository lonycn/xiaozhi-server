# Opt-in realtime AI worker

This standalone loopback WebSocket bridge executes `qwen3.8-omni-flash-realtime` for Java's existing persistent conversations. It does not change ESP32 WebSocket/OTA/ASR/TTS or human LiveKit calls. Java owns identity, explicit microphone/camera consent, the single-use connection ticket, session/control epoch, lease, accepted messages, injections, interruption and usage. The Bailian API key is held only in this Python process's private configuration.

The delivered mode is **manual**: start recording, append PCM, then explicitly end the segment/commit. It is not automatic VAD or WebRTC. Camera support is bounded sampled JPEG frames accompanying that audio turn, sent to the actual provider connection; it is not a full video stream. This module does not record binary media or control equipment.

Install the independent environment from `requirements.txt`. Copy `config.example.json` outside Git, make it mode 0600 before filling in secrets, set `enabled:true`, choose an independent worker key shared only with Java's realtime-worker filter, and configure an actual workspace WSS endpoint/API key. Configure the Java realtime feature and public WebSocket URL explicitly, following `docs/ai-customer-service/REALTIME_AI_CONTRACT.md`. Neither enabling this module nor opening a socket proves model access or deployment acceptance.

From `main/xiaozhi-server`:

```sh
python -m pip install -r realtime_worker/requirements.txt
chmod 600 /private/path/realtime-worker.json
python -m realtime_worker --config /private/path/realtime-worker.json
```

The process defaults to `127.0.0.1:18899/realtime`, binds only loopback, is disabled by default, rejects a query string or browser Authorization header, and requires an exact Origin from `origins` (default `http://127.0.0.1:18894`). Missing Origin is rejected unless explicitly configured. Remote clients require a separately configured same-site HTTPS/WSS reverse proxy; never expose provider credentials or weaken the worker-key filter to obtain remote access. The CLI suppresses access logs and reports generic configuration errors.

Browser first frame, within five seconds:

```json
{"type":"connect","ticket":"OPAQUE_SHORT_LIVED_SINGLE_USE_TICKET"}
```

There is no browser text/model/event/tool passthrough. Subsequent input is exactly `audio.append {audio:<base64 PCM>}`, `audio.commit`, or `frame.append {image:<base64 JPEG>,mime_type:"image/jpeg"}`. Audio is mono little-endian PCM16 at 16 kHz. Text is saved by Java first, dispatched in a heartbeat only when input is idle, then sent as `conversation.item.create`; a real `conversation.item.created` ACK correlated by one pending request, exact user/input_text echo and its real predecessor is required before Java injection ACK and `response.create`. The provider may allocate a different item ID. Java validates the separate `request_item_id` and stores the actual `provider_item_id`; requested IDs are not presented as provider acceptance. Context uses the same role/text/predecessor correlation. A socket send is not acceptance. Dispatched text is never replayed after uncertain delivery or reconnect. Obtain a new explicit session/ticket/context after a terminal disconnect.

Provider initialization waits for `session.created`, sends the modern `audio.input/output.format` configuration with `turn_detection:null`, explicit input transcription, 24 kHz output/longanlingxin, no tools/search, then waits for `session.updated`. Prior context is seeded without requesting responses and its item ACKs are awaited. Only after Java acknowledges readiness does the browser receive `ready`. For audio, real `input_audio_buffer.committed` is persisted by Java before requesting a response; a late final ASR transcript fills the same earlier user message rather than reversing user/assistant order.

Output after authentication always includes numeric `control_epoch` and string `session_id`. Response events include `response_id` and Java's `message_id`:

- `connected` means connecting; `ready` means actual provider configuration/context initialization succeeded and Java accepted it.
- `audio.delta {audio}` is mono little-endian PCM16 at 24 kHz; playback must have a bounded queue.
- `transcript.delta {delta}` is the selected assistant text representation after Java persistence. Final input transcription is delivered through ordinary messages/events APIs.
- `response.started`, `response.completed {text,finish_reason,usage}` and `response.canceled {status,clear_audio:true,usage?}` are tied to that same response/message. Use only one text representation per provider response to avoid duplicating text and spoken transcript. Missing/incomplete usage is null; cancellation preserves complete actual usage if supplied.
- `injection.status {injection_id,message_id,provider_item_id,status:"acknowledged"}` follows a real item ACK.
- A durable interrupt command first causes `response.canceled {status:"interrupting",clear_audio:true}` so playback is flushed immediately; provider terminal confirmation then produces `status:"canceled"`. Do not resume capture/playback until terminal confirmation. New manual audio while a response is unfinished receives `error {code:"RESPONSE_IN_PROGRESS"}` without sending it to the provider.
- `ended {status,clear_audio:true}` can follow a terminal Java callback receipt; authority loss may instead close the socket directly. `error {code,clear_audio:true,response_id?}` is sanitized. Stop recording, camera and playback on either terminal event/close; normal recovery/history uses Java APIs.

Every media input, outgoing audio and canonical output is checked against the current Java lease/epoch/account/mode. Idle heartbeat is every two seconds. `active:false`, failed auth, epoch/lease conflicts, lost heartbeat or ambiguous callback failure stop both sockets and cancel readers/timers. Canonical callbacks retry the identical sequence/event ID/body once; sequence advances only on an exact ACK. The sole recoverable 409 is `RESPONSE_INTERRUPTED_OR_TERMINAL` on `response.delta`: discard that delta, keep the sequence, suppress audio, and fetch the durable interrupt. Other 409s close. A Java `message_status:canceled` receipt suppresses full final text, and a bounded cache of real provider terminal responses confirms later interrupt commands without sending a second cancellation to a finished response.

Default resource bounds: eight total connections including authentication, 32 queued events, five-second auth, 1.5-second control requests, three-second provider connect, ten-second injection/commit/response event stall, 20-second initialization, 30-minute session, 100 response turns, 64 KiB audio chunk, 1.92 MB/60-second manual turn, 57.6 MB total input PCM, 86.4 MB total output PCM and 64 MB total frame bytes. These are explicit engineering limits, not all provider maxima. Each camera frame needs initial camera consent, earlier audio in the same turn, a fully decoded JPEG of at most 190,000 bytes/921,600 pixels, and at least one-second spacing. Its Base64 must not exceed 256,000 bytes. PNG, arbitrary URLs, tool/model messages, excess frames and malformed PCM are rejected. Provider function/MCP events fail explicitly `UNSUPPORTED_TOOL`, associated with the current persisted response when available; no business tool is executed or fake tool result generated.

Only provider final input transcripts and assistant content are persisted. `reasoning_content`, audio/frame Base64, prompts, keys and raw provider diagnostic strings are never logged or forwarded to Java. Sound/sampled images alone do not establish a definite mechanical fault or safe operating condition; measurements and qualified human review remain necessary.

Run the dedicated socket tests, without importing device test `conftest.py` or calling an external provider:

```sh
PYTHONPATH=main/xiaozhi-server <prepared-python> -m unittest discover \
  -s main/xiaozhi-server/tests -p 'test_realtime_worker*.py' -q
```

See `docs/ai-customer-service/REALTIME_WORKER_VALIDATION.md` for the measured test boundary. Actual browser microphone permissions, hardware/audio quality, production proxy/TLS, weak networks and on-site mechanical sound interpretation require separate acceptance. The isolated real Java/Python/Bailian R6 technical-file bridge passed 23 checks; this does not establish browser microphone or deployment acceptance.
