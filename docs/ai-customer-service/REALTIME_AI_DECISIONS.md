# Realtime AI implementation decisions

## 2026-10-30 — P3 controlled WebSocket bridge

Use one authenticated Python provider WebSocket execution path behind the existing Java conversation authority. The browser sends a single-use body ticket, never a provider key. Canonical transcripts, partial/terminal replies and real usage enter the existing ai_message/event ledger through leased worker callbacks. Explicit text injection is persisted before provider acceptance and distinguishes queued, dispatched, acknowledged and failed; ambiguous dispatch is never automatically replayed.

This extends the original P3 isolated Omni investigation into an opt-in implementation; the original AIMEET_INTEGRATION_PLAN is preserved. Parent verified the enabled qwen3.8-omni-flash-realtime endpoint using actual PCM input/output and retained a separate sanitized provider proof. This implementation's mock/SQL tests do not replace that proof or browser media acceptance.

Direct provider WebRTC/AOQ may reduce mobile latency and improve weak-network behavior, but neither is claimed validated here. Evaluate those paths in a later bounded experiment before changing transport. Do not build two simultaneous production AI media channels. Human B5 LiveKit media remains separate and preserves the same conversation when switching to support.

Camera input in this phase is explicitly consented, rate/size-bounded image sampling aligned to audio turns; it is not full-motion video streaming. No arbitrary URL, business tool, device control or fabricated execution output is accepted. Microphone audio and sampled frames are ephemeral bridge inputs; only provider-derived text and actual usage are persisted. Realtime worker/provider reconnect does not replay ambiguous text injections or resume an old session: terminate visibly, preserve accepted text/history, and require a new explicit session.

## 2026-10-30 — Literal canonical transcript boundary

The exact `/internal/realtime-worker/sessions/<32hex>/events` route bypasses the legacy XSS JSON body rewriter, behind its dedicated worker key and before strict bounded parsing (1MiB). Transcripts retain literal `<>&` and are rendered as plain text. The older B3 `/internal/ai-worker/runs/<32hex>/delta` path has the same potential text-rewrite issue and is recorded for integration follow-up; this phase does not broadly disable XSS or alter legacy manager endpoints.

## 2026-10-30 — Provider ASR ordering and cancel completion races

Reserve a user message on the real input_audio_buffer.committed event and wait for the Java ACK before requesting a response. Fill that same message from the eventual real transcription. ASR-first is accepted and later commit reuses its completed message. No fake text is inserted; empty/missing transcription is visibly failed while preserving its row. This keeps the user's audio turn before the assistant in unified history even when provider ASR completion arrives late.

An HTTP interrupt fences future text deltas immediately. A completion racing the interrupt keeps only the previously accepted partial text, records canceled status, and returns message_status so the bridge cannot expose rejected final text. A known real provider termination can confirm a subsequently delivered interrupt command; socket send alone cannot. Actual complete usage, including canceled responses, is retained; missing/incomplete usage stays null.

The Java implementation's final isolated UTC and matching +08 JDBC/MySQL suites each passed 84 targeted tests, including 20 real MySQL cases and real Spring AOP/wiring/filter/wire regressions. These are not a claim of real browser audio/camera, provider latency, weak-network or WebRTC/AOQ acceptance; integration acceptance belongs to the parent task. B6 external Bearer identities remain fail closed for realtime until source-session binding is explicitly implemented.

## 2026-10-30 — Real assigned item IDs and isolated bridge acceptance

R6 completed 23 checks through real owner HTTP → actual Spring API → Python bridge → Bailian, including audio/transcription/history/order/usage, persisted text injection, real interruption, new-session context ACKs, B2 resumption, human handoff and rejection of a genuine old-lease callback replay. This is technical-file acceptance without a browser or microphone. The provider assigned new item IDs instead of echoing requested IDs. Injection ACK now carries both `request_item_id` for Java dispatch validation and the actual `provider_item_id`; the worker requires one pending request, exact echoed role/text/content type and its real previous-item anchor. Context follows the same rule. There is no synthetic ACK or ID substitution. New regression results are 31 loopback network cases and 30 targeted Java cases, including 21 on a new isolated schema; parent full checks follow integration. Full evidence and remaining device/camera/network boundaries are in `REALTIME_WORKER_VALIDATION.md`.
