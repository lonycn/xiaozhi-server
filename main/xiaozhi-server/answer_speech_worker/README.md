# Answer speech worker

This is an independent, opt-in worker for reading an already completed assistant
message. It has no ESP32, device TTS, ordinary model, realtime session, microphone
or camera interface. Java owns message authorization, immutable version/hash,
job leases, cancellation and authenticated audio access.

The provider receives the fixed text as `PlainText` using the existing authorized
Bailian voice configuration. New deployments default to `cosyvoice-v3.5-flash` /
`longantai_v3` (龙安台); an explicitly provisioned `cosyvoice-v2` configuration
remains accepted during rollout. The manager and worker must use the same pair.
The requested new pair returned provider engine error `418` on 2026-11-05, so
the current private runtime has not been switched. Verify complete synthesis
with the authorized workspace before rollout. There is no generated answer, model
fallback, SSML, Markdown cleaning, pronunciation replacement or client-selected
voice. Provider pronunciation and text normalization remain properties of the
TTS service; a successful protocol exchange does not certify every spoken word.

Configuration is explicit private JSON, mode 0600; credentials are never supplied
in task payloads. `enabled` defaults to false. Required fields are `manager_url`,
`worker_key`, `provider_url`, `api_key`, `model`, `voice`; optional
`worker_id` (`answer-speech-local`), `workspace_id`, `heartbeat_seconds` (10), `poll_seconds` (2),
`provider_timeout_seconds` (60), `manager_timeout_seconds` (5). Reuse the trusted
existing speech configuration through deployment tooling; do not create or copy
credentials into source files. The manager key must be independent from the
provider key. Plain HTTP manager access is restricted to loopback, and provider
WSS is restricted to official Bailian inference endpoints.

Audio is synthesized once per claimed job over a dedicated WebSocket. Complete
PCM signed 16-bit little-endian, 24 kHz mono is wrapped in a deterministic WAV.
The worker never publishes partial audio. Hard caps are 8,000 UTF-16 text units,
5 MiB including the WAV header, 120 seconds of audio and 120 seconds of provider
work. Smaller manager policy wins. The size cap can limit duration before the
duration cap. Synthesis is never automatically retried because an ambiguous
provider response can already have incurred charges.

Usage is the provider's cumulative `characters` measurement. It is not the local
text length and is not a token count. Missing usage/request UUID remains null.
Observed usage survives failures or cancellation for a separate audit callback;
that audit cannot make audio available or authorize job completion.

Official protocol references (checked 2026-11-02):

- [Connection and ordered task workflow](https://help.aliyun.com/en/model-studio/cosyvoice-websocket-api)
- [Client events and PlainText synthesis](https://help.aliyun.com/en/model-studio/cosyvoice-client-events)
- [Server completion and cumulative billed characters](https://help.aliyun.com/en/model-studio/cosyvoice-server-events)

Loopback tests exercise exact text transmission, actual character accounting,
provider failures and cancellation. A small generated technical sample established
that the existing Dev account can synthesize with `cosyvoice-v2` and
`longxiaochun_v2`. Full Server authorization/job and App playback acceptance are
separate checks.

Run from `main/xiaozhi-server` using a dedicated environment:

```sh
python -m pip install -r answer_speech_worker/requirements.txt
python -m answer_speech_worker --config /absolute/private/answer-speech-worker.json
```

The fixed manager prefix is `/internal/answer-speech-worker`, authenticated with
`X-Answer-Speech-Worker-Key`. The task binds a 64-hex immutable `message_version`,
the exact UTF-8 `text_sha256`, and a numeric dispatch `attempt`. Every callback
repeats those fences. Audio uploads additionally carry their computed SHA-256.
Completion requires renewed authority before upload and before publication.
Lost manager ACKs retry only identical callback/upload bytes; synthesis itself
is never retried. Expired dispatched jobs must fail rather than be requeued.

Heartbeat and synthesis run independently. Cancellation or loss of authorization
cancels outstanding provider/upload IO; no subsequent audio or completion is
published. A bounded final usage-only callback may still record actual observed
cost for the original dispatch. Provider and manager redirects are rejected,
and manager transport does not inherit environment proxy settings.
