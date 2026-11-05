# Opt-in realtime AI worker

## VAD and external authorization update

The manual-mode description below remains the manual protocol. Its earlier statement that automatic VAD is not delivered is superseded by this update; WebRTC and full video transport remain outside this worker. `manual` is still the default. Java gates automatic mode with `features.realtime-server-vad-enabled` (default `false`); `GET /api/v1/ai/realtime/capabilities` advertises the allowed `turn_detection_modes` and `default_turn_detection_mode`. Session creation accepts `turn_detection_mode: "manual" | "server_vad"`, fixed for that session, and the claimed worker task carries that mode. A mode change requires ending the old session and creating a new one.

For `server_vad`, an explicitly consented user starts capture with `listening.start` and can stop it with `listening.stop`. Worker events include `listening.started`, `listening.stopped`, `speech.started` and `speech.stopped`. A stop sends provider `input_audio_buffer.clear`; `listening.stopped` follows the real provider clear acknowledgement, not merely the outbound send. Provider VAD detects the end of speech and initiates the audio turn/response; the browser must not send manual `audio.commit`, and the worker does not issue a manual `response.create` for that automatic audio turn. Discovery, reconnect and ready must not silently start the microphone. On speech during an answer, local playback is flushed; authoritative cancellation follows real provider termination.

While automatic listening is active, text injections remain queued to avoid racing audio; dispatch resumes after listening is paused and the clear is acknowledged. The existing provider item acknowledgement and no-uncertain-replay rules below still apply. External media is separately gated by `features.external-realtime-media-enabled` (default `false`). Java binds external media to the captured source session **and specific Bearer**, rechecking authorization during requests and callbacks; logout, source revocation, expiry or loss of authorization ends the affected media. A new Bearer does not take over an old session. See [external media and VAD operations](../../../docs/ai-customer-service/EXTERNAL_MEDIA_VAD_OPERATIONS.md) for routing, cleanup and acceptance boundaries.

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

Provider initialization waits for `session.created`, sends the modern `audio.input/output.format` configuration with `turn_detection:null`, explicit input transcription, 24 kHz output/longanlingxin, tools/search off by default, then waits for `session.updated`. Prior context is seeded without requesting responses and its item ACKs are awaited. Only after Java acknowledges readiness does the browser receive `ready`. For audio, real `input_audio_buffer.committed` is persisted by Java before requesting a response; a late final ASR transcript fills the same earlier user message rather than reversing user/assistant order.

Output after authentication always includes numeric `control_epoch` and string `session_id`. Response events include `response_id` and Java's `message_id`:

- `connected` means connecting; `ready` means actual provider configuration/context initialization succeeded and Java accepted it.
- `audio.delta {audio}` is mono little-endian PCM16 at 24 kHz; playback must have a bounded queue.
- `transcript.delta {delta}` is the selected assistant text representation after Java persistence. Final input transcription is delivered through ordinary messages/events APIs.
- `response.started`, `response.completed {text,finish_reason,usage}` and `response.canceled {status,clear_audio:true,usage?}` are tied to that same response/message. Use only one text representation per provider response to avoid duplicating text and spoken transcript. Missing/incomplete usage is null; cancellation preserves complete actual usage if supplied.
- `injection.status {injection_id,message_id,provider_item_id,status:"acknowledged"}` follows a real item ACK.
- A durable interrupt command first causes `response.canceled {status:"interrupting",clear_audio:true}` so playback is flushed immediately; provider terminal confirmation then produces `status:"canceled"`. Do not resume capture/playback until terminal confirmation. New manual audio while a response is unfinished receives `error {code:"RESPONSE_IN_PROGRESS"}` without sending it to the provider.
- `ended {status,clear_audio:true}` can follow a terminal Java callback receipt; authority loss may instead close the socket directly. `error {code,clear_audio:true,response_id?}` is sanitized. Stop recording, camera and playback on either terminal event/close; normal recovery/history uses Java APIs.

Every media input, outgoing audio and canonical output is checked against the current Java lease/epoch/account/mode. Idle heartbeat is every two seconds. `active:false`, failed auth, epoch/lease conflicts, lost heartbeat or ambiguous callback failure stop both sockets and cancel readers/timers. Canonical callbacks retry the identical sequence/event ID/body once; sequence advances only on an exact ACK. The sole recoverable 409 is `RESPONSE_INTERRUPTED_OR_TERMINAL` on `response.delta`: discard that delta, keep the sequence, suppress audio, and fetch the durable interrupt. Other 409s close. A Java `message_status:canceled` receipt suppresses full final text, and a bounded cache of real provider terminal responses confirms later interrupt commands without sending a second cancellation to a finished response.

Default resource bounds: eight total connections including authentication, 32 queued events, five-second auth, 1.5-second control requests, three-second provider connect, ten-second injection/commit/response event stall, 20-second initialization, 30-minute session, 100 response turns, 64 KiB audio chunk, 1.92 MB/60-second manual turn, 57.6 MB total input PCM, 86.4 MB total output PCM and 64 MB total frame bytes. These are explicit engineering limits, not all provider maxima. Each camera frame needs initial camera consent, earlier audio in the same turn, a fully decoded JPEG of at most 190,000 bytes/921,600 pixels, and at least one-second spacing. Its Base64 must not exceed 256,000 bytes. PNG, arbitrary URLs, tool/model messages, excess frames and malformed PCM are rejected. Without an authorized tool task, provider function/MCP events fail explicitly `UNSUPPORTED_TOOL`. MCP/search remain disabled for every task; no local tool or model-supplied URL is executed.

Only provider final input transcripts and assistant content are persisted. `reasoning_content`, audio/frame Base64, prompts, keys and raw provider diagnostic strings are never logged or forwarded to Java. Sound/sampled images alone do not establish a definite mechanical fault or safe operating condition; measurements and qualified human review remain necessary.

Run the dedicated socket tests, without importing device test `conftest.py` or calling an external provider:

```sh
PYTHONPATH=main/xiaozhi-server <prepared-python> -m unittest discover \
  -s main/xiaozhi-server/tests -p 'test_realtime_worker*.py' -q
```

See `docs/ai-customer-service/REALTIME_WORKER_VALIDATION.md` for the measured test boundary. Actual browser microphone permissions, hardware/audio quality, production proxy/TLS, weak networks and on-site mechanical sound interpretation require separate acceptance. The isolated real Java/Python/Bailian R6 technical-file bridge passed 23 checks; this does not establish browser microphone or deployment acceptance.

## Authorized realtime tool tasks (default off)

Only Java's consumed task can supply `tools`, `tool_policy`, and numeric
`turn_generation`. Missing/empty declarations preserve the legacy wire exactly.
Nonempty declarations without the independent realtime policy fail closed before
provider connection. The worker never creates an `ai_run`, executes local Python
functions, contacts model-supplied URLs, or manufactures business data.

The enabled path posts bounded proposals to
`/internal/realtime-worker/sessions/{id}/tool-proposals`, authenticated with the
independent realtime worker key. Each body binds `lease_token`, `control_epoch`,
`turn_generation`, `provider_session_id`, `response_id`, tool round and original
provider item/call IDs. One cancellable asynchronous HTTP task returns its result
through the existing bounded event queue. Heartbeats, VAD, new audio, interruption
and source revocation keep running while Java executes tools serially.

`response.step.completed` records actual per-response usage without completing
the assistant. Java supplies `result_item_id`; the worker sends it in
`function_call_output`, then verifies the provider echo's exact call/output and
predecessor. `tool.result.ack` binds the requested ID and actual allocated provider
ID. Only Java's `response.continue` receipt authorizes `response.create`; the new
`response.started` carries its one-use continuation ID and reuses the same message.
The final browser reply retains text from previous steps and aggregates actual
usage; any missing step usage keeps aggregate usage null.

Manual first audio and VAD speech start submit sequenced `input.started`; Java
increments generation. Heartbeat/receipt generation changes cancel old tool waits
and discard old results/echoes before any continuation. Camera/microphone consent,
lease, epoch and session limits continue to apply independently.

If generation changes after an old continuation `response.create` was sent but
before its `response.created` arrives, the worker reports `TOOL_RESULT_UNCERTAIN`
and closes the old session. It cannot safely relabel an uncorrelated provider
response as belonging to new audio. Recovery uses the existing explicit new
session/ticket path; no PCM or tool output is replayed automatically.

Worker hard caps are 3 rounds, 6 calls, 4 KiB arguments per call, 32 KiB results per
turn, 30 seconds of tool workflow, 120,000 logical output characters, 24 calls and
128 KiB results per session, 32 provider steps and 100,000 known reported tokens.
Stricter task policy wins. Unknown usage is never guessed. Arguments require
complete object JSON, reject duplicate keys/nonfinite numbers, and must agree
with streamed fragments. A transport retry preserves the original proposal body.

Loopback tests establish protocol/fault behavior. Real provider, real source
business queries, long calls and device acceptance require separate evidence.

## Ordered materials (explicit opt-in)

`material_policy` in the authorized task enables the separate materials protocol.
Missing policy leaves every legacy frame unchanged. Java first sends a
`pause_clear` control; capture stops, and the real provider clear ACK is posted to
the fixed `input-pauses/{id}/cleared` endpoint before Java considers the input
paused. Ordinary realtime input remains text-only; only the dedicated public
materials route may save the single user message without creating an `ai_run`.

JPEG materials use a separately configured HTTP vision observation. They are not
silently presented as original images understood by the realtime model. Verified
document text and generated observations are assembled by Java, with original
attachment references and explicit partial-text markers. The worker sends only
that authorized `context_text`, verifies its exact text/role/predecessor echo,
and ACKs both the requested and allocated provider item IDs. A socket send alone
never makes the context accepted.

Optional private configuration (all provider values must be explicitly copied
from the already authorized vision configuration by deployment tooling):

```json
{
  "perception_model": "qwen3.8-flash",
  "perception_base_url": "https://YOUR-WORKSPACE.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
  "perception_api_key": "existing-authorized-vision-key",
  "perception_workspace_id": "",
  "perception_timeout_seconds": 20,
  "perception_max_tokens": 500
}
```

Defaults are empty/disabled. Only the exact configured model is allowed, and the
Task never supplies a URL or key. HTTPS Bailian endpoints are required, redirects
and environment proxies are disabled, and the perception key must differ from
the manager worker key. Perception requests contain no tools. No provider account
or private runtime configuration is created by the worker.

After durable Java resume ACK, the worker sends `material.resume_ready` before
issuing exactly one `response.create` when a material reply is authorized. The
event always includes session/epoch, pause ID, generation, and the acknowledged
material ID; an empty resume explicitly carries `material_id: null`. Listening
stays off. Capture is blocked until that logical reply finishes and then still
requires an explicit user action. No camera/microphone is reopened automatically.

One asynchronous observation job retains normal heartbeats and cancellation.
Current hard caps are two JPEGs of at most 1 MiB each, 2,000 observation characters
per image, 12,000 context UTF-16 units, six materials/12 images per session,
20,000 known perception tokens, 45 seconds per workflow and 24 clear controls.
Smaller Java policy wins. Context uncertainty after a send ends the old session;
it never silently retries or resumes on ambiguous delivery.

Observed perception usage/request IDs are submitted through a separate immutable
audit endpoint, including when generation/source changes. That audit operation
cannot return context or authorize continuation. Missing usage remains null.
Known usage is retained when text fails a size/finish limit or cancellation occurs
after a provider response was observed.

## Capture boundary for materials

Materials clients must negotiate `capture_protocol: "material_capture_v1"` in
the first `{type:"connect",ticket,...}` frame. A successful `ready` includes that
protocol plus `capture_token`, exactly 32 base64url characters. Each durable
`material.resume_ready` rotates the token and retains all existing session,
epoch, pause, material and generation checks. It does not reopen a microphone or
camera. See [capture.schema.json](capture.schema.json) for the message shapes.

After negotiation, `audio.append`, `frame.append`, `audio.commit`,
`listening.start` and `listening.stop` require `capture_token`. `ping` is unchanged.
Clients bind the token when creating a capture/queue entry; asynchronous PCM or
JPEG callbacks must never relabel an old sample with a later permission token.
Unknown or missing tokens fail closed. At most 24 retired tokens are retained.

Valid audio/frame tail packets received while materials lock capture are silently
discarded. Known retired-token packets are also discarded after resume, including
old commit/stop actions, so they cannot start a new turn or clear fresh input.
Discard is bounded per authorized pause to 64 packets and 2 MiB, with internal
packet/byte counters; payload bytes are not retained. Base64, PCM alignment, JPEG
size/pixels and camera consent remain checked. Overflow or invalid input fails
closed. A current-token explicit commit/listening action while locked is rejected
with `INPUT_PAUSED`.

Plain sessions that do not negotiate retain their original input and ready wire.
A materials task without negotiation may continue ordinary voice, but a material
control is rejected with `MATERIAL_CAPTURE_PROTOCOL_REQUIRED`; it cannot silently
resume an unsafe legacy capture path. Java authorization is still checked for
every input. A capture token is a queue fence, not an authorization credential.

Provider startup also renews Java authority with `input_busy: true` while the
WebSocket handshake is pending. It does not dispatch material, injection or
capture input, or publish `ready`, before the actual connection and provider
protocol setup succeed. A fresh authority check follows connection completion.
Timeout, source loss and browser close cancel startup; a socket returned after
cancellation is closed without being used. The private provider-connect timeout
still defaults to three seconds; explicitly trusted configurations may choose a
bounded value up to ten seconds. This does not change a private runtime file.
