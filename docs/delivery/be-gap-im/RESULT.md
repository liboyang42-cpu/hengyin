# be-gap-im — BACKEND GAP PACKAGE 19 (messaging 23 + channels 7)

Branch `feature/be-gap-im`, cut from `origin/integration/backend-app-r2` (2e6f6651c). Worktree `/data/workspace/be-gap-im` on VPS `work`.
Source of the path list: app repo `origin/docs/backend-gaps:docs/release/backend-gap-163.tsv` (rows `messaging` and `channels*`).
Client contract read from app repo `origin/ui/main`: `App/BeGapImDesk.swift`, `Core/NativeChannelAuditFlow.swift`,
`Core/NativeWebhook*.swift`, `Core/NativeChannelChain.swift`, `Core/NativeChannelProgressService.swift`,
`Core/NativeChannelModerationFlow.swift`, `Core/NativeChannelRoleFlow.swift`, `Core/ChinaReviewedRouteCapability.swift` (`beGapImOwnerGrant`).
The mini-program and the owner docs (capgraph `mp.json` / `hy_docs.json`) have no audit-log, webhook or `/api/channels` calls, so the app Core decoders are the contract.

## R3 (2026-11-14): outbound dispatcher, signed inbound ingestion, capgraph fix

Commit `d9e28fecf` on this branch (base `092ae3830`). Migration `20261117_im_webhook_dispatch` (rerunnable, ledger entry
`"20261117_im_webhook_dispatch": ["20261116_im_gap_audit_webhook"]` is the only ledger change; manifest orders it after 20261116).

### Outbound (`ChannelWebhookDispatcher`)

| Item | Behaviour |
|---|---|
| Secret at rest | Each subscription keeps SHA-256 + prefix and now also `secret_cipher` = `PiiCryptoService` AES-256-GCM (`v1:`), the existing PII helper and key (`chengyin.pii.key`). Create/rotate refuse with 409 `WEBHOOK_CRYPTO_UNAVAILABLE` when no key is configured, so no subscription exists that could never be signed. Subscriptions created before R3 have no cipher: their deliveries go `DEAD SECRET_UNRECOVERABLE` until the owner rotates the secret. |
| Outbox | `ImServiceImpl.send` enqueues `message.created` in the send transaction for visible group messages whose conversation **is** a channel conversation (same id as the channel and `im_conversation.type = 5`, or the legacy bridge). An ordinary group sharing an id with a channel never matches (IT). Data: message id, conversation id, sender member id, msg type, content. `(subscription_id, event_id)` is unique, so an event is queued once. |
| Claim | Every `interval-ms` (default 5 s) it takes up to `batch` (20) due rows: `PENDING` (no or past `next_retry_at`), `FAILED` past `next_retry_at`, `INFLIGHT` past `lease_until`. Claim = CAS on (state, attempt) → `INFLIGHT`, attempt+1, lease 60 s. The finish write is CAS on (`INFLIGHT`, attempt), so a lease that expired mid-flight cannot overwrite a newer attempt. Safe with several app instances. |
| Request | `POST` JSON `{delivery_id, event_id, event_type, attempt, created_at, payload}`, headers `X-Chengyin-Timestamp` (epoch s), `X-Chengyin-Nonce` (32 hex, fresh per attempt), `X-Chengyin-Signature: v1=hex(HMAC-SHA256(secret, timestamp + "." + nonce + "." + body))`, `X-Chengyin-Event`, `X-Chengyin-Delivery`, `X-Chengyin-Key-Version`. No redirects, connect 5 s, request 10 s, at most 256 bytes of the response kept (`response_snippet`). |
| Target guard | https only, and every address the host resolves to must be public (no loopback, RFC1918, link-local, CGNAT, multicast, ULA) → otherwise `DEAD URL_BLOCKED`. The JDK client resolves again when it connects, so a DNS-rebinding window remains (noted, not closed). |
| Outcome | 2xx → `SENT`. 408/429/5xx/timeout/network → `FAILED`, `next_retry_at` = base·2^(attempt-1) ±20 %, capped (`backoff-base-ms` 30 s, `backoff-max-ms` 1 h). The 5th failed attempt → `DEAD` (`MAX_ATTEMPTS:<cause>`) = the dead-letter state. Any other 4xx → `DEAD HTTP_<code>` at once. Paused or deleted subscription → `CANCELLED`. `fail_count` / `last_error` / `last_delivery_at` are kept on the subscription. |
| Visibility / redrive | Everything shows on the existing `GET …/subscriptions/{id}/deliveries` (state, attempt, reason, http_status, latency_ms, next_retry_at). `POST …/deliveries/{id}/retry` still requeues FAILED/UNKNOWN under 5 attempts and now also **redrives a DEAD row** (attempt reset to 0). `test` queues a plain PENDING (reason `null`; `DISPATCH_NOT_CONFIGURED` only while the dispatcher is off). |
| Switch | `chengyin.im.webhook.dispatch.enabled` (default **false**), plus `interval-ms`, `initial-delay-ms`, `batch`, `lease-ms`, `backoff-base-ms`, `backoff-max-ms`, `connect-timeout-ms`, `request-timeout-ms`. It does nothing without `chengyin.pii.key`. Production needs an explicit decision to turn it on. |

### Inbound (`ChannelWebhookInboundService`, `POST /api/im/webhook/inbound/{endpointId}`, `@Anonymous`)

* **Endpoint writes are real now** (no more 409): `POST /api/im/webhook/endpoints`, `PUT/DELETE …/{id}`, `POST …/{id}/rotate-token`, with the same
  `MANAGE_WEBHOOKS` check, `request_id` receipts (family HOOK) and revision CAS as subscriptions. Fields: `name`, `avatar_url` (https, public),
  `allowed_ips` (≤ 20 IPv4 or IPv4/CIDR with prefix 8–32, echoed exactly as sent, which the app compares), `rate_limit_per_min` (1–300, default 30),
  `status`. Create and rotate return `signing_secret` (`whin_…`) and `url` (`<inbound-base-url>/api/im/webhook/inbound/{id}`) once, with
  `secret_shown_once` / `url_shown_once`. Neither is ever written to the receipt or returned on replay. The secret is stored as SHA-256 + prefix
  (`token_*`) plus `secret_cipher` (PII helper). Rotation invalidates the old secret immediately (`old_token_invalid_after` = rotation time).
  409 `WEBHOOK_INBOUND_UNAVAILABLE` remains only when `chengyin.im.webhook.inbound-base-url` (must be https) or the PII key is missing.
* **Ingestion order** (every decision writes `im_webhook_inbound_log` in the shape `NativeWebhookHTTPService.inboundLogs` accepts): unknown or deleted
  endpoint 404 (no log) → paused `REJECTED ENDPOINT_PAUSED` 409 → IP allowlist (`TrustedProxyIpResolver`) `BLOCKED IP_NOT_ALLOWED` 403 →
  per-minute limit `RATE_LIMITED` 429 → `|now − X-Chengyin-Timestamp| ≤ 300 s` else `REJECTED TIMESTAMP_OUT_OF_WINDOW` 401 → nonce
  `[A-Za-z0-9_-]{16,64}` → HMAC `v1=hex(HMAC-SHA256(secret, ts.nonce.body))`, constant-time, else `REJECTED SIGNATURE_INVALID` 401 →
  **only then** the nonce is consumed (`im_webhook_inbound_nonce` primary key, so forged requests cannot burn it), else `REJECTED REPLAYED` 409 →
  body exactly `{"content": 1–2000 chars}` (≤ 16 KB) → the channel must have its conversation (type 5 / bridge) and be normal → text moderation with the
  same `IWxContentSecurityService.checkText` as `/api/im/send`.
* **Accept**: pass → system message (`sender_id 0`, `extra_json {sender_type 4, webhook_id, webhook_name}`) visible at once, conversation summary and unread
  updated, log `ACCEPTED` with `message_id`, `sender_type 4`, `webhook_id`, `original_message_stored 1` (HTTP 200). Needs review, or machine review
  unavailable → the message is stored **hidden** (`check_status 0`, `visible_at NULL`), log `PENDING_REVIEW` (HTTP 202). Violation → no message,
  `BLOCKED CONTENT_BLOCKED` (403). Note: a webhook has no WeChat openid, so with the production `WxContentSecurityServiceImpl` every message that passes the
  sensitive-word library lands in `PENDING_REVIEW`. Nothing in this package releases held text messages; that needs an owner decision (review UI or a
  server-side text moderation path without openid).
* Nonces older than 2× the window are purged every 10 minutes.

### Tests (VPS, flock + `-T 1` + `-Xmx1536m`, be-int-mysql 3330)

`ChannelWebhookDispatcherTest` 6, `ChannelWebhookInboundServiceTest` 7, `ChannelWebhookServiceTest` 12 (was 9), `ApiChannelGapControllerTest` 7 (was 6),
**`ChannelsWebhookDispatchMySqlIT` 3** (new: the migration applied twice and its shape; outbox → claim → signed SENT → backoff → DEAD at attempt 5 → redrive →
expired-lease reclaim → `SECRET_UNRECOVERABLE`; inbound accept/replay/pending/rate limit/rotation/nonce purge on real rows), `ChannelsGapImMySqlIT` 6:
**41/41**. A wider run that also included `ChannelAuditLogServiceTest`, `ChannelChainLatestTest`, `ChannelGovernanceServiceTest`, `ChannelPlatformServiceTest`,
`ImServiceImpl*Test`, `ApiIm*Test`, `ChannelsSecondPassMySqlIT` and `ChannelsFourthPassMySqlIT` had 149/150 green. The single failure was the 4xx-only
`ChannelRejection` rule in a new test, which was fixed and is part of the 41 above. Gates: `migration_ledger audit` problems=[], manifest order OK,
`migration-layout-check.sh` and `migration-safety-check.sh` pass, `dup_create_table_gate.py` passes. The full suite was not rerun.

### curl on 8122 / `cy_gap_im` (transcript `/data/workspace/be-gap-im-out/curl-r3.txt`, script `smoke_r3.py`, secrets redacted)

`cy_gap_im` got `20261117` applied twice (rerun OK). The 8122 overlay set `dispatch.enabled=true`, `backoff-base-ms=2000` and
`inbound-base-url=https://8122-…instabox.tech`. The outbound target was a TEST-only receiver on 8123 that verifies the HMAC itself. It was published as a
temporary public https URL for this check, because the dispatcher refuses loopback, and was unpublished afterwards.

| Check | Result |
|---|---|
| subscription → `/ok`, `test` + a real `/api/im/send` in channel 5 | both deliveries `SENT a1 http=200`; the receiver logged `sig_ok: true`, `event_id msg-13` (the real message), 32-hex nonce, skew < 1 s |
| subscription → `/fail` (500) | `FAILED a2 HTTP_500` → receiver hits 2, 4, 8, 16 s apart → `DEAD a5 MAX_ATTEMPTS:HTTP_500`; `retry` on the DEAD row 200 `PENDING attempt 0` and it was sent again |
| subscription → `/gone` (410) | `DEAD a1 HTTP_410` at once |
| endpoint create / replay / as member | 200 with `signing_secret` + `url` once; replay without them; member 403 `WEBHOOK_FORBIDDEN` |
| inbound signed | 200 `ACCEPTED`, message 14 (`sender_id 0`, `extra_json sender_type 4, webhook_id 1`, visible) |
| replay / bad signature / stale timestamp / wrong body | 409 `REPLAYED` / 401 `SIGNATURE_INVALID` / 401 `TIMESTAMP_OUT_OF_WINDOW` / 400 `BODY_INVALID`, each logged without a message |
| rate 1/min, allowlist `198.51.100.0/24` | 429 `RATE_LIMITED`; 403 `BLOCKED IP_NOT_ALLOWED` |
| rotate-token | old secret 401, new secret 200 `ACCEPTED` (message 15) |
| delete endpoint | then inbound 404 |

8122 and the receiver were stopped and 8123 was unpublished. The receiver's secret file and the copied jar were deleted. 8120 was not touched.

### (A) capgraph fix (app branch `ui/capgraph` `45a3c1bdd`; also installed in `/data/workspace/capgraph/tools`)

`backend_routes.py` split annotation strings on every `+`, so `{id:[0-9]+}` became `{expr}{expr}` = `/{}`. It now splits only outside string literals,
reads fully qualified `@org.springframework…Mapping`, and `norm_path` collapses braces balanced (`{y:\d{4}}`). Unresolved routes 14 → 0.
Matching now honours `/**` catch-alls and refuses a cross-wise double-hole match. A literal prefix (`"api/x/" + op`, `prefix = …`, `root + "/list"`)
counts as served when every app call under it is served; otherwise its missing children are listed on their own rows.
On this branch (`092ae3830`, the R3 code adds no app-called path) × app `origin/ui/main` `f9a08d041`: **85 really missing (75 routes + 10 prefix families
with nothing under them)**. The old scanner reports 165 on the same inputs. List: `/data/workspace/capgraph/out/true-missing.tsv`.
None of the 85 is in messaging or channels.

## What the 30 rows really are

The gap list is capgraph's normalized app literals compared with `backend_routes.py`. Probing every app call on TEST 8120 with a real player
token (before this branch) showed three causes:

1. **Really missing (now implemented here):** `GET /api/channels`, `GET /api/im/audit-log`, `GET /api/im/webhook/context` and the whole
   `/api/im/webhook/*` family the Core client sends; `GET /api/im/chain/info` required `chain_id`, but BeGapImDesk only sends `conversation_id`.
2. **Already served, but capgraph misses it:** routes declared with a regex path variable (`{id:[0-9]+}`) are parsed by
   `capgraph/tools/backend_routes.py` as `/{}` (`raw: '{expr}{expr}'`). Examples: `/api/im/appeals/{id:[0-9]+}/evidence`,
   `/api/im/message/{id:[0-9]+}/attachment`, `/api/im/moderation/{actionId:[0-9]+}/revoke`,
   `/api/im/{kind:file|video|voice}/message/{id:[0-9]+}/authorization` and the other media reads, and the member/role writes. They answer with business codes on 8120 and on 8122 (table below). This is a scanner bug, not a backend gap.
3. **Literal prefixes, not routes:** the app builds the path from a prefix literal plus a suffix (`"api/im/chain/" + op`,
   `"api/im/channel/" + suffix`, `"api/im/file/message/" + id + "/preview"`, `"api/channel-platform/…/requests/" + requestId`,
   `boardPath(...) + "/top"`, `NativeWebhookRuntimeRoute.prefix = "/api/im/webhook"`). The concrete routes behind each prefix are listed below.
   No bare-prefix endpoint was invented.

## Path → controller → test → curl (8122, DB cy_gap_im)

Controllers are in `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/`. Tests: unit = `ApiChannelGapControllerTest` (route,
verb, query/body mapping, `err`), service tests named per row; IT = `ChannelsGapImMySqlIT` (real MySQL). The curl column uses club 7001
(owner 13900000003 = member 9003, member 13900000004 = 9004), channel 5. Full transcript: `curl.txt` (secrets redacted).

| # | gap path | concrete app call(s) | controller | test | curl result |
|---|---|---|---|---|---|
| 1 | `/api/channel-platform/admission/requests` | prefix → `GET …/admission/requests/{requestId}` | `ApiChannelAdmissionController` (existing) | existing | 200 `state NOT_FOUND` |
| 2 | `/api/channel-platform/governance/moderation/requests` | prefix → `GET …/{requestId}` | `ApiChannelGovernanceController.moderationReceipt` (existing) | existing | 200 `state NOT_FOUND` |
| 3 | `/api/channel-platform/governance/reports/requests` | prefix → `GET …/{requestId}` | `ApiChannelGovernanceController.reportReceipt` (existing) | existing | 200 `state NOT_FOUND` |
| 4 | `/api/channel-platform/governance/requests` | prefix → `GET …/{requestId}` | `ApiChannelPlatformController` (existing) | existing | 200 `state NOT_FOUND` |
| 5 | `/api/channel-platform/requests` | prefix → `GET …/{requestId}` | `ApiChannelPlatformController` (existing) | existing | 200 `state NOT_FOUND` |
| 6 | `/api/channel/{}/board/{}` | prefix → `GET …/board/{key}/top`, `/detail`, `/me/best`, `/me/recent`, `/version` | `ApiChannelProgressController` (existing) | existing | all 5: 404 `BOARD_NOT_FOUND` (no board bound on channel 5; route served); `GET /api/channel/5/board` 200 |
| 7 | `/api/channels` | `GET /api/channels?guild_kind=1&guild_id=` (BeGapImDesk.channels) | **`ApiChannelGapController.channels`** → `ChannelPlatformService.directory` | `ApiChannelGapControllerTest.channelsListsTheGuildDirectory` | 200, groups of club 7001 (owner and member) |
| 8 | `/api/im/appeals/{}/evidence` | `POST` `{request_id, evidence_json}` | `ApiChannelGovernanceController.evidence` (existing, regex path) | existing | 404 `APPEAL_NOT_FOUND` (no appeal 1) |
| 9 | `/api/im/appeals/{}/withdraw` | `POST` `{request_id, reason}` | `ApiChannelGovernanceController.withdraw` (existing) | existing | 404 `APPEAL_NOT_FOUND` |
| 10 | `/api/im/audit-log` | `GET ?guild_kind=1&guild_id&limit=50&verify=0/1[&before]` (NativeChannelAuditFlow, BeGapImDesk.audit) | **`ApiChannelGapController.auditLog`** → `ChannelAuditLogService.read` | `ChannelAuditLogServiceTest` (5), `ApiChannelGapControllerTest.auditLogPassesVerifyAndCursor`, IT `auditProjectsRealOriginalsAndVerifiesAfterRoundTrip`, `auditPagesWithTheBeforeCursor` | owner 200: 5 `CHANNEL_CREATE`, then `MODERATION_TIMEOUT` and `MODERATION_REVOKE` after a real timeout + revoke; `verify.chain_ok=true`, `checked_rows=7`, `through=verified_through=7`, `has_more`/`next_cursor` paging by `before`; member 403 `CHANNEL_FORBIDDEN` |
| 11 | `/api/im/chain` | prefix → `POST /api/im/chain/{create,join,withdraw,close}`, `GET /api/im/chain/info` | `ApiChannelInteractionController` (existing) + **`chain_id` now optional** | `ChannelChainLatestTest`, `ApiChannelGapControllerTest.chainInfoAcceptsAConversationWithoutChainId`, IT `latestChainOfAConversation` | `GET /api/im/chain/info?conversation_id=5&limit=50` → body code 404 `CHAIN_NOT_FOUND` (no chain yet; before: 500 "Required parameter chain_id") |
| 12 | `/api/im/channel` | prefix → `GET /api/im/channel/checkin-status`, `checkin-rank`, `checkin-request`, `POST checkin` | `ApiChannelInteractionController` (existing) | existing | `checkin-status?channel_id=5` 200 |
| 13 | `/api/im/file/message` | prefix of rows 14–15 | — | — | — |
| 14 | `/api/im/file/message/{}/authorization` | `GET` | `ApiImMediaController` (existing, `/api/im/{kind:file\|video\|voice}/message/{id:[0-9]+}/authorization`) | existing | 404 `MEDIA_NOT_FOUND` (message 1 is not a file) |
| 15 | `/api/im/file/message/{}/preview` | `GET` | `ApiImMediaController` (existing) | existing | 404 `MEDIA_NOT_FOUND` |
| 16 | `/api/im/members/{}/roles` | `PUT` (NativeChannelRoleFlow) | `ApiChannelGovernanceController` (existing) | existing | 400 `INVALID_FIELD guild_kind` (route served; probe body empty) |
| 17 | `/api/im/message/{}/attachment` | `GET` | existing `ApiChannelSurfaceController.attachment` | existing | 200 `LEGACY_SINGLE_IMAGE / UNSUPPORTED` |
| 18 | `/api/im/moderation` | prefix → `POST /api/im/moderation/{timeout,kick,ban}`, `GET /actions`, `GET /me` | `ApiChannelGovernanceController` (existing) | existing | `POST timeout` 200 (action 1); `GET actions` 200 |
| 19 | `/api/im/moderation/{}/revoke` | `POST {request_id, version, reason}` | existing `ApiChannelGovernanceController.revoke` | existing | 200 `status REVOKED, version 2` |
| 20 | `/api/im/notification-requests` | prefix → `GET /api/im/notification-requests/{requestId}` | `ApiChannelNotifyController` (existing) | existing | 200 `status NOT_FOUND` |
| 21 | `/api/im/poll/create-receipt` | `GET ?conversation_id&client_poll_key` | `ApiImPollController` (existing) | existing | body 404 "群聊不存在或已结束" (channel 5 is not a poll group) |
| 22 | `/api/im/roles/{}/members` | `PUT` | `ApiChannelGovernanceController` (existing) | existing | 400 `INVALID_FIELD ids` (route served) |
| 23 | `/api/im/video/message` | prefix of rows 24–25 | — | — | — |
| 24 | `/api/im/video/message/{}/authorization` | `GET` | `ApiImMediaController` (existing) | existing | 404 `MEDIA_NOT_FOUND` |
| 25 | `/api/im/video/message/{}/preview` | `GET` | `ApiImMediaController` (existing) | existing | 404 `MEDIA_NOT_FOUND` |
| 26 | `/api/im/voice/message` | prefix of rows 27–28 | — | — | — |
| 27 | `/api/im/voice/message/{}` | `GET` | `ApiImMediaController` (existing) | existing | 404 `MEDIA_NOT_FOUND` |
| 28 | `/api/im/voice/message/{}/authorization` | `GET` | `ApiImMediaController` (existing) | existing | 404 `MEDIA_NOT_FOUND` |
| 29 | `/api/im/webhook` | prefix (`NativeWebhookRuntimeRoute.prefix`) → the routes below | **`ApiChannelGapController`** | see below | see below |
| 30 | `/api/im/webhook/context` | `GET ?owner_id&channel_id` (NativeWebhookManagementContextSource, BeGapImDesk.webhookContext) | **`ApiChannelGapController.webhookContext`** → `ChannelWebhookService.context` | `ChannelWebhookServiceTest.contextNamesTheManagerAndTheEventCatalogue`, `ApiChannelGapControllerTest.webhookContextAndRejectionCarryErr` | owner 200 `actor_id 9003, CLUB/7001/5, authorization_version v1-…, management_permission im:webhook:manage, event_revision im-webhook-events-v1, 5 events`; member 403 `WEBHOOK_FORBIDDEN` (with `err`) |

### Webhook child routes (Core `NativeWebhookHTTPService`, owner grant `beGapImOwnerGrant`)

| route | service | test | curl |
|---|---|---|---|
| `GET /api/im/webhook/subscriptions?owner_type=CLUB&owner_id&channel_id&limit[&cursor]` | `listSubscriptions` | `listsPageWithCursorAndHideSecrets`, controller `subscriptionRoutes`, IT | 200, list of 1 (no secret, no full URL) |
| `POST /api/im/webhook/subscriptions` (BeGapImDesk.createWebhook body) | `createSubscription` | `createShowsTheSecretOnceAndReplaysWithoutIt`, `createValidatesTheChange`, IT `subscriptionLifecycleOnRealRows` | 200 `revision 1, key_version 1, secret_shown_once, secret <redacted>`; same request_id replay → `replayed true`, no secret |
| `GET /api/im/webhook/subscriptions/{id}` | `subscription` | controller, IT | 200 |
| `PUT /api/im/webhook/subscriptions/{id}` | `updateSubscription` (revision CAS) | `updateDeleteAndRotateUseTheRevision`, IT | 200 `revision 2`, events updated |
| `DELETE /api/im/webhook/subscriptions/{id}` `{request_id, revision}` | `deleteSubscription` | same | 200 `deleted true, status 0, revision 4` |
| `POST …/{id}/rotate-secret` | `rotateSecret` | same | 200 `revision 3, key_version 2`, new secret once |
| `GET …/{id}/deliveries?limit&include_payload[&state&cursor]` | `deliveries` | `listsPage…`, IT | 200, the test delivery with payload |
| `POST …/{id}/test` `{request_id, revision, event_type}` | `test` | `testDeliveryIsQueuedNotClaimedSent` | 200 `state PENDING, attempt 0, reason DISPATCH_NOT_CONFIGURED` (first pass; with the R3 dispatcher on: reason `null`, then `SENT`) |
| `POST …/{id}/deliveries/{deliveryId}/retry` | `retry` | `retryOnlyRequeuesFailedOrUnknownUnderFiveAttempts`, IT | 409 `DELIVERY_NOT_RETRYABLE` for the PENDING delivery (FAILED/UNKNOWN with attempt < 5 → PENDING, covered by unit + IT) |
| `GET /api/im/webhook/endpoints?owner_type&owner_id&channel_id` | `listEndpoints` | `endpointsAreReadableButInboundWritesAreRefused`, IT | 200 empty |
| `GET /api/im/webhook/endpoints/{id}`, `GET …/{id}/logs` | `endpoint`, `endpointLogs` | same, IT `endpointReadsRunOnRealRows` | 404 `WEBHOOK_NOT_FOUND` (no endpoint exists) |
| `POST /api/im/webhook/endpoints`, `PUT/DELETE …/{id}`, `POST …/{id}/rotate-token` | first pass: `createEndpoint` / `mutateEndpoint`; **R3: `createEndpoint` / `updateEndpoint` / `deleteEndpoint` / `rotateEndpointToken`** | `endpointLifecycleIssuesTheSigningSecretOnce`, controller `endpointRoutes`, IT | first pass 409 `WEBHOOK_INBOUND_UNAVAILABLE`; R3 200 (see R3) |

## Semantics (decisions the coordinator should know)

- **Audit log** is an unanchored projection, as the owner grant says. The original sources are `im_channel_op_log` (channel lifecycle, edit, permission
  overwrite) and `im_moderation_action` (timeout/kick/ban, and its revocation). On each read, under a per-guild head-row lock, rows that are not yet
  projected are appended in original-time order to `im_channel_audit_log` with `row_hash = SHA-256(prev_hash ‖ length-prefixed fields)`.
  The time in the hash is the DB wall-clock text, so the JDBC/JVM time zone does not change the hash. Guild-scoped moderation is attached to the guild's default channel (the client
  requires a positive `channel_id`). Free-text reasons (`reason_text`, `revoke_reason`) never enter the audit; those rows carry `changes_redacted=true`.
  `verify=1` returns `chain_ok / linked_projection_ok / unproven_original_rows / checked_rows / through / verified_through / head_hash / frozen`
  with boundary `original-channel-audit-projection-v1; no historical backfill or external-anchor claim`, `anchor_evidence=null`,
  `externally_anchored=false`. On a broken link it answers `frozen=true` with an empty list (the client's rule). Permission: `VIEW_AUDIT` (guild bits) or `ADMINISTRATOR`.
- **Webhooks**: management needs `MANAGE_WEBHOOKS` (or `ADMINISTRATOR`) on a club channel (guild_kind 1, `owner_id` = club). Club admins do not
  have it by default (`ADMIN_BASE`), only the owner or a role that grants bit 25. Every write is idempotent by `request_id` (receipt family `HOOK`,
  added to the check constraint) and checks the revision. The signing secret is stored only as SHA-256 + prefix and is returned once. It is never written to the
  receipt and never returned on replay. *(First pass; superseded by R3 above.)* **No outbound dispatcher exists**, so `test` and `retry` only queue `PENDING` with reason `DISPATCH_NOT_CONFIGURED`, as the
  owner asked ("webhook test does not claim a delivery was sent"). Callback URLs must be https, with no userinfo or fragment, and must not be localhost, an IPv6 literal or a private/reserved IPv4 address.
  *(First pass; superseded by R3.)* **Inbound endpoints are not opened** (no ingestion route, and no URL or token is handed out). The reads work. Every inbound write returns 409 `WEBHOOK_INBOUND_UNAVAILABLE` after authorization.
  Errors in this family also carry `err` (= `errorCode`), because `NativeWebhookHTTPService` reads `err` / `trace_id`.
- **Event catalogue** (`im-webhook-events-v1`): `message.created`, `message.updated`, `message.deleted`, `member.joined`, `member.left`.

## Migration

`chengyinhub-admin/sql/migration_20261116_im_gap_audit_webhook.sql`, ledger entry in `ci/migration-dependencies.json`:
`"20261116_im_gap_audit_webhook": ["20261115_im_channels_fourth_pass", "20261113_im_channels_second_pass", "20261105_l8_channel_lifecycle"]`
(only this entry was added). `ci.migration_ledger.build_manifest` at the commit orders it after the fourth pass.
Tables: `im_channel_audit_log`, `im_channel_audit_head`, `im_webhook_subscription`, `im_webhook_delivery`, `im_webhook_endpoint`,
`im_webhook_inbound_log`. The receipt family check constraint gains `HOOK`. Additive only, no backfill.

## Tests run (VPS, `flock /data/workspace/.mvn.lock`, `-T 1`, `MAVEN_OPTS=-Xmx1536m`)

`mvn -pl chengyinhub-admin -am test -Dtest=ChannelAuditLogServiceTest,ChannelWebhookServiceTest,ApiChannelGapControllerTest,ChannelChainLatestTest,
ChannelGovernanceServiceTest,ChannelPlatformServiceTest,ChannelsGapImMySqlIT,ChannelsSecondPassMySqlIT,ChannelsFourthPassMySqlIT`
with `GITHUB_ACTIONS=true CHENGYIN_MYBATIS_IT_PORT=3330` (be-int-mysql): **59 tests, 0 failures** (new: controller 6, audit 5, webhook 9,
chain 1, IT 6; the regression ITs for the second and fourth pass still pass with the `chainInfo` signature change).
`ci/dup_create_table_gate.py` passes. The full suite was not rerun.

## Runtime check

`cy_gap_im` = mysqldump of `cy_e2e_r2` + this migration. The app jar from this branch ran on 127.0.0.1:8122 (redis db 12, its own upload dir), was
exercised with `curl.txt`, and was then stopped. 8120 was not touched (it still answers).
Leftovers on the VPS (own outputs only): DB `cy_gap_im` (kept for re-checks), logs in `/data/workspace/be-gap-im-out/`. The three `chengyin_mybatis_*_gapim` IT databases and the copied jar were deleted.

## Not done / follow-ups

- Done in R3: the capgraph tooling fix (rows 1–6, 8–9, 11–29 now count as served or as served prefixes), the outbound dispatcher, and inbound ingestion.
- Open (owner decisions): (1) whether to turn `chengyin.im.webhook.dispatch.enabled` on in production; (2) a release path for webhook text held
  in `PENDING_REVIEW`, because production machine review needs an openid that a webhook does not have; (3) only `message.created` has a producer.
  `message.updated/deleted` and `member.joined/left` are in the catalogue, but nothing enqueues them yet.
- The DNS-rebinding window between the dispatcher's address check and the JDK client's own lookup is not closed. Closing it needs a pinned-address connector.
