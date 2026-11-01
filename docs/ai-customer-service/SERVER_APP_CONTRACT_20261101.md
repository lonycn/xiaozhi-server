# Server / App contract, 2026-11-01

All routes use the existing `Result` envelope and existing authenticated session or allowed external Bearer boundary. IDs remain strings; clients must not turn them into JavaScript numbers. Errors use existing HTTP status and machine-readable code semantics.

## Human support lifecycle

`POST /api/v1/support/requests/{request_id}/end`, optional body `{"reason":"..."}` (at most 500 characters). Only the conversation owner may end an active request with its current assignment; a queued, canceled or stale request returns 409. The request becomes `resolved`, assignment is released, the conversation becomes `RESOLVED`, and the media cleanup hook is enqueued within the transaction. Physical room cleanup is asynchronous. A retry of the same resolved request returns it without another transition, including after a later handoff. This endpoint never resumes AI: use `POST /api/v1/ai/conversations/{conversation_id}/resume-ai` explicitly. Existing `cancel` is queued-only; media session `end` closes media only; agent `resolve` keeps its permission requirement. Messages, summaries and drafts are retained.

## Capabilities

`GET /api/v1/app/capabilities` requires authentication. `data` includes `contract_version`, `features`, `limits`, `errors`, and `notes`. Features are configuration flags combined with their prerequisites, not runtime readiness checks. Missing flags default to false. Limits include pagination default 50/max 100 and support end reason 500 characters. The existing `/api/v1/ai/realtime/capabilities` retains its distinct P3 turn-detection contract. No provider keys, internal addresses or deployment secrets are returned.

## External invitation discovery

`GET /api/v1/external/media-invitations?after=0&limit=50` requires an active external Bearer, external media enabled, and a live dynamically validated source and token. Limit is 1–100; the result contains `items`, `next_cursor`, and `has_more`. The owner, customer and invitee must match the authenticated principal, whose issuer, subject and tenant are checked against the source and identity registry. Invitations that have no media binding are minimal previews visible across source logins for that same persistent identity and tenant, consistent with conversation history. An already bound session and pending customer transfer approval appear only for their exact source and token. Different issuer, subject, tenant or user cannot see the preview.

Preview returns invitation metadata only, no room credentials, media URLs or tickets. It does not bind or grant media access. First explicit acceptance establishes the source/token binding using existing locked media policy; competing source/token callers cannot operate the bound session. Source or token revocation is checked on every discovery call. No legacy actor-only invitation route is externally allowed.

## Tool activity in the manager UI

The selected conversation polls `GET /api/v1/ai/conversations/{id}/tools?before=0&limit=50` and presents read-only status metadata. The response is a descending audit page `{items,next_cursor,has_more}`; clients use the exact server cursor for older pages, aggregate the newest visible event per call, and retain the server's history authorization and released-assignment cutoff. A feature-disabled 404 hides the panel; authorization, network and server failures remain visible. Requests and polling are canceled when the selected conversation or identity changes and on unmount. Tool arguments, provider results, credentials and internal endpoints are never displayed by this panel.

## Isolated MySQL verification

On 2026-11-01, `ExternalAppContractPersistenceTest` passed 2 tests against the new retained loopback schema `xiaozhi_external_test_app_20261101175004_612de3`, using Java 21 and an independent build directory. It exercises real MySQL invitation selection for same-identity sources, different tenant/issuer/user, exact source/token transfer isolation and first consent binding; owner end preserves messages and internal summary revisions, releases assignment, is idempotent, and queues then completes cleanup against a synthetic LiveKit `not_found` response. Capability defaults and prerequisite flags are also checked. Earlier fixture failures and their schemas were retained. This does not verify real LiveKit, network clients, deployed Dev, Android or business systems; fixture transport and transfer rows are synthetic.

## Administrator diagnostics

`GET /api/v1/admin/diagnostics/readiness` is read-only and requires an authenticated, enabled `sys_user` with `super_admin=1`; external Bearer identities are denied. Use a local administrator session in the management workbench, or supply its existing token privately:

```bash
curl --fail-with-body -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  'http://127.0.0.1:18893/xiaozhi/api/v1/admin/diagnostics/readiness'
```

Do not paste credentials into reports. The `no-store` response has `readiness_scope=database_and_job_ledgers`: `limited` means only the database and enabled ledger queries succeeded, not that providers, workers, LiveKit or business systems are reachable. `degraded` includes a query whose status is `unknown`; a database or administrator-check failure returns HTTP 503 with database state `unknown`. Disabled features are marked `disabled` without querying their tables. Active and expired lease counts describe database rows only. Tool failures/discards are grouped over 60 minutes; unrecognized error codes are combined as `OTHER`. No prompt, result, endpoint, credential or worker token is returned.

A positive expired-lease count also marks the limited snapshot `degraded`; an expired lease is evidence for investigation and does not establish the cause. Tool totals and groups count audit events, not distinct conversations.
