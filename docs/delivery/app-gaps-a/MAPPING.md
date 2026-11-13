# App gaps A: backend routes for the app (player side)

Source list: app branch `ui/capgraph` (c2202dbe3), `docs/release/capability-graph.md` section (2)
APP-NOT-IN-BACKEND, areas city game, rewards, play runtime, roam, profile, topics/routes.
Base: `integration/backend-app-20261112` (c96d345ff). Branch: `feature/app-gaps-a`.

Each row says **served** (new route on this branch), **mapped** (the backend already has an
equivalent route under another path, so the app should call that path; nothing duplicated), or
**deferred** (not built; reason given).

## City game (26 paths in the graph)

All new routes live in `ApiCityGameController` (`/api/city-game/**`) on one server-authoritative
engine (`com.chengyinhub.business.citygame`, migration `migration_20261113_gaps_a_city_game.sql`).
The server rolls the die from the weighted table published on the board, prices every
point-spending action, charges only the confirming member, and stores one receipt per
(member, requestId). A retry with the same body replays that receipt; a reused requestId with a
different body is `409 CITY_REQUEST_REUSED`.

| App path (graph) | App file | Status | Backend |
|---|---|---|---|
| `/api/city-game` GET (`current`, `participation`, `points`) | Core/CityPlayerReading.swift | served | `GET /api/city-game/{current,participation,points}` (`CITY_PLAYER_READ_V1`; stale board tuple → `409 CITY_SNAPSHOT_CHANGED`) |
| `/api/city-game/state/snapshot` | Core/CityCurrentSnapshotReading.swift | served | `CITY_CURRENT_SNAPSHOT_V1`, board must be `FROZEN`; too many points in the viewport → `422 CITY_VIEWPORT_TOO_DENSE` |
| `/api/city-game/player-rules` | Core/CityPlayerRulesClient.swift | served | `CITY_PLAYER_RULES_V1`, 14 sections incl. published dice faces/weights; not FROZEN → 404, changed tuple → 409 |
| `/api/city-game/team-participation/state` | Core/CityParticipationStateReading.swift | served | `CITY_TEAM_PARTICIPATION_V1` |
| `/api/city-game/team-participation/command` POST | Core/CityParticipationCommandRecovery.swift | served | CREATE / JOIN / LOCK / CONFIRM / EXIT / RESUME; HTTP 200/400/409 with `requestStatus: COMPLETE` |
| `/api/city-game/{}/request/{}` GET | Core/CityOriginalReceiptReading.swift | served for `team-participation` | `action-budget` and `acquisition` domains answer 404 (no such commands here, see below) |
| `/api/city-game/team-participation` (prefix) | Core/CityParticipationScopedTransport.swift | served | prefix of the two rows above |
| `/api/city-game/team-participation/invite` | Core/CityParticipationInvitationReading.swift | served | `?regionId=&code=` → `{binding, teamId, version, memberCount}`; unknown or locked team → 404 `CITY_INVITE_INVALID` |
| `/api/city-game/state/deltas` | Core/CityOrderedMapScopedTransport.swift | deferred | the snapshot declares `deltaStatus: DELTAS_UNAVAILABLE`; the ordered game event stream is served as `GET /api/city-game/game/deltas` (below) |
| `/api/city-game/action-budget`, `/command`, `/request/{}`, `/state` | Core/CityActionBudget*.swift | deferred (budget served elsewhere) | the team action budget is part of the game snapshot (`rules.actionsRemaining` / `actionsPerRound`) and is spent by `POST /api/city-game/game/action`; the separate r491 budget command contract was not built |
| `/api/city-game/acquisition` | Core/CityAcquisitionService.swift | deferred (occupy served elsewhere) | occupying a property is `ACQUIRE_PROPERTY` via `game/quote` + `game/action`; the separate r491 acquisition contract was not built |
| `/api/city-game/arrival-evidence` | Core/CityArrivalEvidenceService.swift | deferred | needs a GPS/merchant-attested evidence pipeline; `SUBMIT_EVIDENCE` answers `REJECTED UNSUPPORTED_ACTION` |
| `/api/city-game/merchant/operations` (+ availability, intent, request) | Core/CityMerchantOperationsService.swift | deferred | merchant-side operations (pause, service catalogue) are outside the player scope of this task |
| `/api/city-game/neutral-tasks`, `/onsite-tasks`, `/task-history` | Core/CityNeutralTaskService.swift, CityOnsiteTaskService.swift, CityTaskHistoryService.swift | deferred | their own task-catalogue contracts; tasks in the game loop are `ACCEPT_TASK` on a property (`taskAttemptKept` in the snapshot) |
| `/api/city-value/visits` (+ feedback, services) | Core/CityPlayerValueService.swift | deferred | city-value visit programme is a separate merchant-value product |
| `/api/city/walking/target` | Core/CityWalkingAuthorityReader.swift | deferred | walking navigation authority; snapshot points carry `grantsNavigation: false` |

### Game loop routes for `CityGameServing` (app has no transport yet)

`CityGameCoordinator` talks to `CityGameServing` and only DEBUG fixtures implement it, so the app
names no path. These are the backend paths to wire it to:

| CityGameServing call | Backend |
|---|---|
| `snapshot(for:)` → `CityGameSnapshot` | `GET /api/city-game/game/snapshot?boardId=` (`CITY_GAME_SNAPSHOT_V1`; extra `team` block: `trackPosition`, `landedPropertyId`, `score`) |
| `deltas(after:)` → `[CityDelta]` | `GET /api/city-game/game/deltas?boardId=&seasonId=&after=<sequence>` (contiguous sequences, `eventId = evt-<sequence>`) |
| `quote(_:targetId:read:)` → `CityActionQuote` | `POST /api/city-game/game/quote` `{action, boardId, targetId}` |
| `submit(_:)` → `CityActionReceipt` | `POST /api/city-game/game/action` with `CityActionIntent.body()` |
| `receipt(for:)` → `CityActionReceipt?` | `GET /api/city-game/game/action/{requestId}`; `404 CITY_RECEIPT_NOT_FOUND` means nil |

Rules the server applies (all values come from `city_game_board`):

- **Roll**: `ROLL_ROUND` (target = boardId, expectedVersion = boardVersion) costs `roll_cost` action
  units; the server rolls the weighted die and moves the team token along the property track
  (`track_index`). Receipt carries `diceFace`.
- **Occupy**: `ACQUIRE_PROPERTY` needs a quote, the team landed on that property, an accepted task
  there, and holdings below `holding_limit`. Charges `acquire_points` from the payer only; the property
  becomes `PROTECTED` for `protection_seconds`.
- **Toll**: `ACCEPT_SERVICE_VISIT` (target = serviceId) on a property another team holds; charges the
  published fee, which scores for the operating team. A service with no published fee cannot be quoted.
- **Challenge**: `START_CHALLENGE` on a `HELD` (not protected) property of another team, with an
  accepted task there; a server dice duel decides, ties keep the defender.
- **Settle**: when a team round's budget reaches 0 the round settles (held properties ×
  `holding_yield` added to team score) and the next round opens until `max_rounds`.
- Decisions: `APPLIED`, `VERSION_CONFLICT` (currentVersion differs), `QUOTE_CHANGED`, `REJECTED` /
  `BLOCKED` with a reasonCode (`NOT_JOINED`, `ROUND_CHANGED`, `ACTION_BUDGET_EXHAUSTED`, `NOT_LANDED`,
  `TASK_REQUIRED`, `HOLDING_LIMIT`, `INSUFFICIENT_POINTS`, `QUOTE_EXPIRED`, `QUOTE_USED`,
  `BOARD_NOT_OPEN`, `PROPERTY_PAUSED`, `SAFETY_BLOCKED`, ...), `EFFECT_ALREADY_APPLIED`.

Membership: `JOINED` means present and confirmed in a locked team. `participationId` is the
team session id and `membershipVersion` is the team version (the r491 rule
`CityPlayerRulesEligibility` checks). Round 1 opens when every member has confirmed.

**Lifecycle conflict in the app, flagged for the app side.** `CityActionContract` accepts actions
only on an `OPEN` board, while the r491 reads (`CityCurrentSnapshot`, `CityPlayerRulesSnapshot`)
require `FROZEN`. The backend follows each contract as written: actions need `OPEN`; the bounded
snapshot and player rules need `FROZEN`. A single board cannot satisfy both at once, so the app
must pick one meaning of FROZEN before the two surfaces ship together.

### Fairness

| App path | Status | Backend |
|---|---|---|
| `/api/city-game/fairness/report` | served (not in the graph list; named in the brief) | formula `city-fairness-cohort-v1`; envelope has `candidate: true`; scope mismatch → 403 `scope-rejected`; unpublished parameters → 422 `NOT_COMPUTABLE` |
| `/api/city-game/fairness/outing` | served | the caller's own outings only |

An outing is a member's first board action on a calendar day (Asia/Shanghai). A member is mature
when their first outing is older than `fairness_horizon_seconds`; `matureCount` counts mature
members whose second outing came within `fairness_delta_seconds`. Only the caller's own class row is
returned, so the app's `showsRatio` holds. Facts the platform does not record yet (GPS false
rejections, safety tickets, merchant verifications, challenge compensation exits) are `null` and
listed in `coverageGaps`.

## Play runtime (13 paths)

| App path (graph) | App file | Status | Backend |
|---|---|---|---|
| `/api/play` GET/POST | Core/PlayExperienceService.swift | mapped | prefix of the existing `/api/play/*` routes the service builds (`answer`, `checkin`, `arrive`, `photo`, `sensor-result`, `run-session/*`, `nodes`, `route-state`, `action-receipt`, ...); all exist |
| `/api/game/session` | Core/PlayExperienceService.swift | mapped | `GET /api/game/session/view`, `GET /api/game/session/receipt`, `POST /api/game/session/command` (existing) |
| `/api/play/check` | Core/JourneyContentService.swift | mapped | `/api/play/check/{roll,reroll,settle,bargain,momentum,rest}` POST and `/api/play/check/receipt` GET (existing) |
| `/api/play/theme` GET | Core/PlayThemeBackpackActions.swift | mapped | `/api/play/theme/{action,card,state,history,replay,gameplay-action,gameplay-preview,support-matrix}` (existing) |
| `/api/play/route-action` GET | App/PlayRouteMapView.swift | served | `?sessionId=&memberId=&actionId=` → `{sessionId, memberId, actionId, responseJson}` from `topic_route_action`; memberId must be the caller (403), unknown → 404; never inserts |
| `/api/play/egg/collect` | Core/JourneyContentService.swift | served | form `eggId`, `topicId` (client `content` is ignored): stores the reviewed `npc_route_egg.text` into `member_collectible` (type 2); idempotent; unreviewed egg → 404, wrong topic → 400 |
| `/api/play/advanced/time-window` | Core/NativePlatformService.swift | served | `GET ?activityId=&topicId=&nodeId=` → `NativeTimeWindow` fields (`enabled`, `notificationProvider: LOCAL_ONLY`, `timeZone: Asia/Shanghai`, `serverNow`, `openNow`, `nextOpenAt`, `nextCloseAt`, `windowVersion`, plus `openFrom`/`openTo`); same eligibility as resuming a node; not configured / malformed → 409 with `data.reasonCode` |
| `/api/play/city-orientation`, `/answer`, `/receipt` | Core/CityOrientationTask*.swift | deferred | separate orientation-answer receipt contract (`payloadHash`, `configVersion`); the existing orient session routes (`/api/play/orient/session/*`) are not the same wire shape and were not wrapped |
| `/api/progress/meter`, `/api/progress/requests`, `/api/progress/requests/{}` | Core/NativeChannelProgress*.swift | deferred | built on the channel platform (`/api/channel/{id}/board`, `/progress/overview`), which the backend does not have; belongs with the messaging/channel area |

## Profile (8 paths)

| App path (graph) | App file | Status | Backend |
|---|---|---|---|
| `/api/user/address` | Core/ProfileParticipantTransport.swift | mapped | `/api/user/address/{list,info,action,delete}` POST (existing) |
| `/api/user/deregister` | Core/AccountDeregistrationOriginal.swift | mapped | `/api/user/deregister/{precheck,apply,cancel}` POST, `/status` GET (existing) |
| `/api/user/sms/send` POST | App/RegionalEndpointAllowlistSectionView.swift | mapped | `POST /api/sms/send` (existing). The app file is the endpoint audit list, not a live caller |
| `/api/user/follow-list` | Core/ProfileFollowListService.swift | served | `GET ?kind=following\|followers&cursor=&limit=` (limit ≤ 50) → `{owner_id, kind, rows[{id,nickname,avatar,isFollow}], has_more, next_cursor}`; keyset by member id; the caller's own lists only; deleted members and legacy duplicate follow rows are dropped |
| `/api/user/deregister/result` | Core/DeregistrationStatusRules.swift | deferred | `apply` revokes every session of the account, so no authenticated read can follow it; the app's default `derivedFromStatus` receipt (from `/status`) stays the supported path |
| `/api/onboarding/get`, `/api/onboarding/submit` | Core/NativeChannelOnboarding.swift | deferred | guild/channel onboarding (`guild_kind`, `channel_ids`, `role_ids`, receipts under `/api/channel-platform/admission/requests/`); the channel platform does not exist in the backend — belongs with messaging/channels |
| `/api/welcome-screen/get` | Core/NativeChannelWelcome.swift | deferred | same channel-platform dependency |

## Roam (10 paths)

| App path (graph) | App file | Status | Backend |
|---|---|---|---|
| `/api/free-explore/bundle` GET/POST | App/BundleCommerceReadTransport.swift | mapped | prefix of the existing `/api/free-explore/bundle/*` routes (`catalog`, `order`, `order/receipt`, `order/refund-quote`, `reservation`, `reservation/receipt`, `reservation/{}/cancel(-quote)`, `exit-options`, `exit-decision`) |
| `/api/roam/passport` | Core/RoamPassportHistoryReading.swift | mapped | `/api/roam/passport/{history,record,summary}` GET (existing) |
| `/api/free-explore/bundle/order/refund` | App/BundleRefundTransport.swift | deferred | money movement (original-route refund); only `order/refund-quote` exists. Needs the payments owner's refund state machine, not a new parallel path |
| `/api/free-explore/bundle/transfer`, `/voucher`, `/voucher/appeal`, `/review`, `/shelf`, `/renewal-intent` | App/BundleCommerceMount.swift, BundleTransfer*/BundleAppeal* | deferred | bundle commerce extensions (gifting a paid bundle, vouchers, appeals, shelf, renewal) change paid entitlements; they need product and payments decisions first |
| `/api/location-share/personal` | Core/PersonalLocationShareHTTPService.swift | deferred | live personal location sharing needs a privacy and retention decision (who sees it, how long it is kept) before any storage is built |

## Topics / routes (14 paths)

| App path (graph) | App file | Status | Backend |
|---|---|---|---|
| `/api/topic/attribute-author` GET/POST/DELETE, `/references`, `/validate` | Core/NativeAttributeAuthor*.swift | mapped (wire shape differs) | existing `ApiTopicAttributeController`: `GET/POST/PUT/DELETE /api/topic/attributes`, `PUT /api/topic/attributes/order`, `GET /api/topic/state-keys/usage` (references), `POST /api/topic/state-keys/validate` (validate). The app already keeps a legacy branch for `/api/topic/state-keys`; the versioned `expectedConfigVersion` envelope is not on the backend |
| `/api/topic/attribute-author/request` | Core/NativeAttributeAuthorExtendedContract.swift | deferred | original-request receipt for the versioned author contract above; no equivalent |
| `/api/topic/module-preflight` | Core/NativeAttributeAuthorHostTransport.swift | deferred | the closest existing read is `GET /api/topic/publish-status`; a module preflight was not built |
| `/api/topic/cover`, `/selection`, `/selection/current`, `/selection/status`, `/upload`, `/{}/content` | Core/OwnedTopicCover*.swift, ApprovedTopicSelectedCover.swift | deferred | author-only cover pipeline: private object storage, moderation, author-only content serving and topic config/slot versions; needs the media owner's private-storage path |
| `/api/topic/edit-requests/submit`, `/read`, `/resume-post-processing` | Core/ProjectEditRequestContract.swift | deferred | durable request wrapper around `api/topic/{create,update,v2/create,v2/update}`; touching the publishing core needs the topic owner |

## Rewards (18 paths)

| App path (graph) | App file | Status | Reason |
|---|---|---|---|
| `/api/reward-pledge` (+ `/awards`, `/drafts`, `/fulfilment/{awards,expire,preview,redeem,resolve}`, `/ops`, `/pledges`, `/review`, `/versions`) | Core/RewardPledge.swift | deferred | a complete merchant/creator pledge product: draft → submit → confirm → freeze → platform review → versions/holds/withdraw → award pool → fulfilment (preview/redeem/expire/resolve) → service cases. No part of it exists; building it needs product rules for review, holds and expiry |
| `/api/rewards/noncash` (+ `/cases`, `/claims`, `/claims/recover`, `/subjects`, `/versions`) | Core/NonCashRewardService.swift, RewardPledge.swift | deferred | the player-side list and claims read awards that only the pledge fulfilment above produces; a list with no producer would always be empty. Coupon rewards stay on `api/coupon/myrecvlist` |
