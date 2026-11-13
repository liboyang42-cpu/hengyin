# be-gap-im — BACKEND GAP PACKAGE 19 (messaging 23 + channels 7)

Branch `feature/be-gap-im`, cut from `origin/integration/backend-app-r2` (2e6f6651c). Worktree `/data/workspace/be-gap-im` on VPS `work`.
Source of the path list: app repo `origin/docs/backend-gaps:docs/release/backend-gap-163.tsv` (rows `messaging` and `channels*`).
Client contract read from app repo `origin/ui/main`: `App/BeGapImDesk.swift`, `Core/NativeChannelAuditFlow.swift`,
`Core/NativeWebhook*.swift`, `Core/NativeChannelChain.swift`, `Core/NativeChannelProgressService.swift`,
`Core/NativeChannelModerationFlow.swift`, `Core/NativeChannelRoleFlow.swift`, `Core/ChinaReviewedRouteCapability.swift` (`beGapImOwnerGrant`).
The mini-program and the owner docs (capgraph `mp.json` / `hy_docs.json`) have no audit-log, webhook or `/api/channels` calls, so the app Core decoders are the contract.

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
| `POST …/{id}/test` `{request_id, revision, event_type}` | `test` | `testDeliveryIsQueuedNotClaimedSent` | 200 `state PENDING, attempt 0, reason DISPATCH_NOT_CONFIGURED` |
| `POST …/{id}/deliveries/{deliveryId}/retry` | `retry` | `retryOnlyRequeuesFailedOrUnknownUnderFiveAttempts`, IT | 409 `DELIVERY_NOT_RETRYABLE` for the PENDING delivery (FAILED/UNKNOWN with attempt < 5 → PENDING, covered by unit + IT) |
| `GET /api/im/webhook/endpoints?owner_type&owner_id&channel_id` | `listEndpoints` | `endpointsAreReadableButInboundWritesAreRefused`, IT | 200 empty |
| `GET /api/im/webhook/endpoints/{id}`, `GET …/{id}/logs` | `endpoint`, `endpointLogs` | same, IT `endpointReadsRunOnRealRows` | 404 `WEBHOOK_NOT_FOUND` (no endpoint exists) |
| `POST /api/im/webhook/endpoints`, `PUT/DELETE …/{id}`, `POST …/{id}/rotate-token` | `createEndpoint` / `mutateEndpoint` | same, controller `endpointRoutes` | 409 `WEBHOOK_INBOUND_UNAVAILABLE` |

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
  receipt and never returned on replay. **No outbound dispatcher exists**, so `test` and `retry` only queue `PENDING` with reason `DISPATCH_NOT_CONFIGURED`, as the
  owner asked ("webhook test does not claim a delivery was sent"). Callback URLs must be https, with no userinfo or fragment, and must not be localhost, an IPv6 literal or a private/reserved IPv4 address.
  **Inbound endpoints are not opened** (no ingestion route, and no URL or token is handed out). The reads work. Every inbound write returns 409 `WEBHOOK_INBOUND_UNAVAILABLE` after authorization.
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

- capgraph will keep listing rows 1–6, 8–9, 11–29 until `backend_routes.py` handles regex path variables and the gap report treats
  literal prefixes as prefixes. That is a tooling fix in `/data/workspace/capgraph`, which this package did not change.
- An outbound webhook dispatcher (signing needs a recoverable secret, e.g. KMS-encrypted, not just the hash) and inbound ingestion are both
  out of scope and need an owner decision.
