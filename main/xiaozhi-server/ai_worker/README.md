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

## Offline verification

```sh
/tmp/xiaozhi-b2-worker-venv/bin/python -m unittest discover -s tests -p 'test_ai_worker*.py' -v
```

Use unittest directly: device tests' `tests/conftest.py` globally replaces SDKs and
network clients. Worker tests use isolated HTTP mocks, including the actual async
SDK streaming parser and cancellation during request headers. They never call a
cloud model. A real configured-provider request is a separate acceptance step.
