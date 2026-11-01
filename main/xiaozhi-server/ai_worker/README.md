# Independent Bailian AI text worker

This process only uses the manager's `/internal/ai-worker/**` HTTP API. It never
imports the ESP32 core, opens a database, migrates data, or starts the device server.
Use the existing configured Bailian/Qwen account and model. Do not provision another
provider account. Keep private config and credentials outside Git.

## Isolated startup

From `main/xiaozhi-server`, using Python 3.9+:

```sh
python3 -m venv /tmp/xiaozhi-b2-worker-venv
/tmp/xiaozhi-b2-worker-venv/bin/pip install -r ai_worker/requirements.txt
/tmp/xiaozhi-b2-worker-venv/bin/python -m ai_worker --config /absolute/private/ai-worker.json
```

Private JSON fields (values below are placeholders):

```json
{
  "manager_url": "http://127.0.0.1:18893/xiaozhi",
  "worker_key": "private-worker-key",
  "worker_id": "dev-text-1",
  "base_url": "https://YOUR-WORKSPACE.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
  "api_key": "existing-bailian-key",
  "model": "qwen-flash",
  "workspace_id": "",
  "max_tokens": 500
}
```

`manager_url` includes the manager context path. Model must exactly match Java's
`ai.execution.model`. Worker key must match Java's private `ai.worker.key`.
A workspace encoded in the configured URL requires no `workspace_id`. When supplied,
`workspace_id` is sent as `X-DashScope-WorkSpace`. Provider endpoints are restricted
to Alibaba HTTPS hosts. Environment variables `AI_WORKER_<FIELD_NAME>` override JSON;
`AI_WORKER_CONFIG` can supply the JSON path. Numeric variables are parsed as numbers.
Optional `heartbeat_seconds` (10–15, default 12), `poll_seconds` (default 2), and
`provider_timeout_seconds` (default 60) bound polling and model reads.

## Runtime contract

Concurrency is one task. The manager owns task history, context construction,
lease/epoch validation, retry/reset policy, and persisted usage. The worker never
chooses context from a database. Claim uses the private `X-AI-Worker-Key`; all
callbacks include the opaque lease token. A lost claim ACK leaves that task to the
manager's expiry recovery. Provider requests are never automatically retried.

Delta callbacks are serialized and split at 4096 characters. Transient manager
transport/5xx errors use at most three attempts, preserving sequence and body.
HTTP 401/403/409 is terminal. Heartbeat failure immediately cancels model opening
or consumption, including before the first token. Failed heartbeat is never
retried while generation continues. Stream and heartbeat tasks are awaited and
closed on SIGTERM/SIGINT. The manager's default lease is 45 seconds.

The provider sends `enable_thinking=false` and `stream_options.include_usage=true`.
Only provider-reported prompt/completion/total tokens are persisted; missing or
invalid usage remains null. Incomplete streams fail instead of claiming completion.
Only completion text is forwarded; provider error payloads, prompts, keys and
headers are never logged. Error codes are `MODEL_MISMATCH`, `PROVIDER_AUTH`,
`PROVIDER_RATE_LIMIT`, `PROVIDER_TIMEOUT`, `PROVIDER_UNAVAILABLE`, `PROVIDER_ERROR`,
and `WORKER_ERROR`.

## Optional structured tools

Tools remain disabled when a claimed task omits `tools` or supplies an empty list;
plain-text request and completion callback fields retain the original contract.
When Java authorizes tools, the claim includes OpenAI function schemas, `attempt`,
`control_epoch`, and `tool_policy`. Only the documented Qwen model families in
`tools.supported()` accept these schemas. Unknown models and unsolicited tool calls
fail closed. Schemas never grant authority to execute anything in this process.

The worker merges streamed function names/arguments by call index, checks stable
IDs, and requires complete object JSON (no duplicate keys or nonfinite numbers).
It posts a bounded batch to `/internal/ai-worker/runs/{id}/tools` with
`lease_token`, `attempt`, `control_epoch`, `round`, and
`calls: [{provider_call_id, name, arguments}]`. Java executes serially and returns
`results: [{provider_call_id, name, status, output, error_code}]`. The worker validates
identity and size, appends the assistant's `tool_calls` and matching `role: tool`
messages, rechecks the lease, then resumes the provider. Waiting for a tool never
completes the assistant message. No arbitrary Python function, URL, or SQL executes.

Hard ceilings are three tool rounds, eight calls, 16 KiB streamed arguments per
round, 64 KiB cumulative tool results, 120,000 output characters, and 30 seconds
for the entire loop; stricter task policy wins. Heartbeat failure cancels tool
waiting as well as provider streaming. Retries use identical manager request bodies
and provider call IDs; provider requests themselves are never retried.

For enabled tasks, completion/failure adds `provider_requests` (one-based provider
round, request ID, actual usage, finish reason), `tool_count`, and aggregate usage.
Aggregate usage is null if any observed request lacks reported usage. Failed
business tools remain explicit tool errors for the model; a missing source never
becomes fabricated business data. Added failures are `UNSUPPORTED_TOOL`,
`TOOL_PROTOCOL_ERROR`, `TOOL_ARGUMENTS_INVALID`, `TOOL_LIMIT_EXCEEDED`, `TOOL_TIMEOUT`.
Offline tests do not establish real provider or business integration acceptance.

## Offline verification

```sh
/tmp/xiaozhi-b2-worker-venv/bin/python -m unittest discover -s tests -p 'test_ai_worker*.py' -v
```

Use unittest directly: device tests' `tests/conftest.py` globally replaces SDKs and
network clients. Worker tests use isolated HTTP mocks, including the actual async
SDK streaming parser and cancellation during request headers. They never call a
cloud model. A real configured-provider request is a separate acceptance step.

## B3 verified media context

Java may add `attachments` to the current user context message. These references
contain only `attachment_id`, `variant`, `mime_type`, `kind`, and optional `time_ms`.
Historical turns carry source descriptions and existing text, without replaying
older media. The worker strips internal reference fields before calling a model.
Images use private `display` derivatives; video uses at most six sampled JPEG
frames with their time points. Documents use extracted UTF-8 text, and audio or
videos with speech use separately completed transcripts. Empty extracted text is
explicitly labelled unavailable; the worker never invents content for scanned pages.

The manager keeps AI queued while a required transcript is queued/running, without
consuming a claim attempt. Missing/failed/unconfigured transcription fails with
`MEDIA_INPUT_UNAVAILABLE`; image/video input without a configured visual model
fails with `MODEL_CAPABILITY_UNAVAILABLE`. Manual AI retry remains available after
the underlying capability is corrected.

Configure Java `ai.execution.vision-model` and these optional private worker fields:

```json
{
  "vision_model": "YOUR_CONFIGURED_BAILIAN_VISION_MODEL",
  "vision_base_url": "https://YOUR-WORKSPACE.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
  "vision_api_key": "existing-bailian-key",
  "vision_workspace_id": ""
}
```

`vision_model` must exactly match Java; endpoints have the same Bailian-only HTTPS
allowlist as text. Empty vision endpoint/key reuse the configured text endpoint/key.
A distinct model gets a separate SDK client; unknown models never silently route to
text. If the visual model is the same as the configured text model, its existing
text client handles both. Provider auto-retry remains disabled. Existing plain-text
`MODEL_MISMATCH` behavior is retained for B2; configured multi-model failures use
`MODEL_CAPABILITY_UNAVAILABLE`.

Every derivative GET uses `/internal/ai-worker/runs/{runId}/attachments/{id}/content`
with `X-AI-Lease`. Java rechecks run lease, account, mode and epoch plus the current
input's exact allowed reference before opening an object stream, outside its
lease-authorization transaction. Original files, unrelated attachments and variants
cannot be requested. No storage URL or long-lived user Bearer reaches the model:
JPEG bytes become `image_url` data URLs; text remains text. Download limits are
8MiB per image, 24MiB total image bytes, 256KiB per text artifact, 60,000 total
extracted characters, and 16 references per input. Preparation is bounded by
`provider_timeout_seconds` and runs under the existing heartbeat supervision;
revocation cancels an in-flight download before model generation.

The `test_ai_worker_media.py` suite adds actual SDK multimodal wire assertions,
strict reference and byte/character bounds, model routing and cancellation tests.
It uses mocked control/model HTTP responses and does not establish live provider
or deployed-storage acceptance.

Non-realtime raw audio is opt-in through `omni_model=qwen3.8-omni-flash` plus
explicit `omni_base_url` and `omni_api_key` (optional `omni_workspace_id`). An
independent SDK client accepts only current-run normalized WAV capabilities;
no ASR fallback is made for sound analysis. Inline audio is capped at 7,499,982
bytes, including a conservative allowance for the Data URI prefix below the
provider's 10 MB Base64 limit. Over-limit runs fail `OMNI_AUDIO_LIMIT` and retain
the uploaded evidence. See `docs/ai-customer-service/OMNI_AUDIO_CONTRACT.md`.
