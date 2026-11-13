# App gaps A: backend routes for the app (player side)

Source list: app branch `ui/capgraph` (c2202dbe3), `docs/release/capability-graph.md` section (2)
APP-NOT-IN-BACKEND, areas city game, rewards, play runtime, roam, profile, topics/routes.
Base: `integration/backend-app-20261112` (c96d345ff). Branch: `feature/app-gaps-a` (pass 1 + pass 2).

Each row says **served** (route on this branch), **mapped** (an equivalent route already exists under
another path or on another branch, so the app should call that; nothing duplicated), or **N/A-v1**
(deliberately not offered in v1 by owner decision; the reason is given).

## Summary (89 graph paths)

| Area | Paths | Served | Mapped | N/A-v1 |
|---|---:|---:|---:|---:|
| city game | 26 | 26 (+7 game-loop and fairness routes not in the graph) | 0 | 0 |
| rewards | 18 | 0 | 0 | 18 (moves money) |
| play runtime | 13 | 6 | 7 | 0 |
| roam | 10 | 8 | 2 | 0 (2 of the 8 serve reads only; their money writes refuse) |
| profile | 8 | 2 | 6 | 0 |
| topics/routes | 14 | 14 | 0 | 0 |
| **total** | **89** | **56** | **15** | **18** |

Owner decisions applied in pass 2 (20261113, via the coordinator): rewards/pledge stays off in v1 because it
moves money; everything else deferred in pass 1 is built; bundle commerce serves reads and refuses every money
write; one CITY board lifecycle for every route. (`npc_route_egg` is **not** restored: the 2026-09-07 decommission stands, see Data notes.)

## Board lifecycle (CITY, every route)

One rule, enforced by the server and documented here for the app:

| Lifecycle | Meaning | Reads (current, points, participation, state/snapshot, player-rules, budget/arrival/task/acquisition state, history, value) | Actions and commands (team-participation, game/action, game/quote, action-budget, arrival-evidence, onsite/neutral tasks, acquisition, value visit begin) |
|---|---|---|---|
| `OPEN` | published and running | served | accepted |
| `FROZEN` | settled, read-only | served | refused (`BOARD_NOT_OPEN` / `CITY_BOARD_NOT_OPEN`) |
| anything else | not published | `404`/`409` as before | refused |

A board becomes `FROZEN` by itself: when the last locked team settles its last round (`max_rounds`), or on the
first read or write after `valid_until`. Each freeze appends a `BOARD_FROZEN` board event.

**App changes this needs** (the backend no longer serves "FROZEN only" reads): `CityCurrentSnapshot.validate`,
`CityPlayerRulesClient`, `CityPlayerRulesContracts`, `CityValueBoundedMapReading`, `CityValueBoundedMapScopedTransport`
and `CityPlayerValueMapPage`/`CityPlayerValueServiceReference` must accept `lifecycle in [OPEN, FROZEN]`; actions stay
gated on `OPEN` exactly as `CityActionContract` and `CityGameCoordinator` already do. The strings
`releasePolicy: CURRENT_FROZEN_RELEASE_ONLY` (acquisition) mean "the rules release published for this board is
immutable"; they do not refer to the board lifecycle.

## Verification

All on VPS `work`, MySQL 8.0.45 `be-int-mysql`, never production, never the shared `cy_e2e_beint`:
- Unit: `CityGameRulesTest` (9), `AdvancedTimeWindowTest` (3), `AppGapsPass2RulesTest` (6),
  `CityOrientationAnswerServiceTest` (1).
- Real MySQL IT (batch `app-gaps-a` in `ci/real-storage-it-registry.json`, each in its own scratch schema):
  `CityGameMysqlIntegrationTest` (3), `CityR491MysqlIntegrationTest` (3), `PlayerGapsMysqlIntegrationTest` (2),
  `AppGapsPass2MysqlIntegrationTest` (4).
- Migrations `20261114_gaps_a_city_r491`, `20261114_gaps_a_player`, `doc2_f3_bundle_extras_20261114`
  pass `ci/migration_ledger.py audit` (`20261114_gaps_a_npc_route_egg` was removed in integration/backend-app-r2); dependencies are declared in
  `ci/migration-dependencies.json`; all were applied to `cy_gaps_a`.
- HTTP smoke on the packaged jar (port 8131, `cy_gaps_a`, Redis db 11): pass 1 `scripts/e2e/smoke_app_gaps_a.py`
  and pass 2 `scripts/e2e/smoke_app_gaps_a_pass2.py` after `seed_app_gaps_a.py` + `seed_app_gaps_a_pass2.py`
  (results in the pass 2 commit message and the coordinator report).

## City game (26 paths in the graph)

All routes run on one server-authoritative engine (`com.chengyinhub.business.citygame`, migrations
`20261113_gaps_a_city_game` + `20261114_gaps_a_city_r491`). Commands store one receipt per
(member, domain, requestId) in the same transaction as their effect; the POST reply and the
`/request/{id}` read are the same stored body with the same HTTP status (`code == status`, `errorCode` on
refusal). A reused requestId with another body is `409 CITY_REQUEST_REUSED`.

| App path (graph) | App file | Status | Backend |
|---|---|---|---|
| `/api/city-game` GET (`current`, `participation`, `points`) | Core/CityPlayerReading.swift, CityValueBoundedMapReading.swift | served | `CITY_PLAYER_READ_V1`; `current` now carries `mapRead` (`CITY_BOUNDED_MAP_V1`); `points` with a viewport answers one bounded page (50 points, 4 pages, content `watermark`, `nextCursor`; stale cursor `409 CITY_MAP_CURSOR_STALE`) |
| `/api/city-game/state/snapshot` | Core/CityCurrentSnapshotReading.swift | served | `CITY_CURRENT_SNAPSHOT_V1`, readable while OPEN or FROZEN |
| `/api/city-game/state/deltas` | Core/CityOrderedMapScopedTransport.swift | served | `CITY_TRANSACTIONAL_MAP_V1`: `state/snapshot?...&sync=TRANSACTIONAL_EVENTS_V1` opens a stream (snapshot + per-point `SLOT`/`MERCHANT`/`PLATFORM` versions); `state/deltas?...&cursor=<stream>.<seq>` replays committed `POINT_REPLACED` events in the viewport, contiguous; stale stream, other viewport, revision or team change → `409 CITY_ORDERED_RESET_REQUIRED` |
| `/api/city-game/player-rules` | Core/CityPlayerRulesClient.swift | served | `CITY_PLAYER_RULES_V1`, 14 sections, readable while OPEN or FROZEN |
| `/api/city-game/team-participation/state`, `/command`, `/invite`, prefix | Core/CityParticipation*.swift | served | `CITY_TEAM_PARTICIPATION_V1`; commands need OPEN |
| `/api/city-game/{}/request/{}` GET | Core/CityOriginalReceiptReading.swift | served | `team-participation`, `action-budget`, `acquisition` (and the other r491 domains under their own paths) |
| `/api/city-game/action-budget`, `/state`, `/command`, `/request/{}` | Core/CityActionBudget*.swift | served | `CITY_ACTION_BUDGET_V2` on the team round of the engine: `version = rolls + acquisitions`, `usedUnits = rolls×rollCost + acquisitions×acquisitionCost`; `ROLL` is server-rolled from the published weighted die, **recorded only** (no movement, ownership or points effect); a board that prices tasks, services or challenges answers `NOT_CONFIGURED` |
| `/api/city-game/acquisition` | Core/CityAcquisitionService.swift | served | `CITY_ACQUISITION_V1`: `state`, `titles`, `quote/{id}`, `command` (`QUOTE` no effect / `CONFIRM` charges the payer's member points and the team's acquisition units, makes the team the holder of the point's property on the engine, records a title; replay charges once), `request/{id}`; needs a PASSED onsite task with unexpired store evidence; titles never transfer, no service fees |
| `/api/city-game/arrival-evidence` | Core/CityArrivalEvidenceService.swift | served | `CITY_ARRIVAL_EVIDENCE_V1`: member `ISSUE` (server challenge, display code shown once in the receipt) → `SUBMIT` on site → store staff (`MERCHANT_OWNER/MANAGER/CHECKIN` of the point's merchant, never the member) `CONFIRM` with the same code or `REJECT` with a reason → one evidence record (`MERCHANT_ATTESTED`, 2 h); `state`, `merchant`, `request/{id}`, `evidence/{id}`; no GPS, no automatic effect |
| `/api/city-game/merchant/operations` (+ `availability`, `intent`, `request/{}`) | Core/CityMerchantOperationsService.swift | served | store asks to `PAUSE_MERCHANT` / `RESTORE`; goes to platform review (`IN_REVIEW`), `runtimeApplied` is always false; bound to the published relation by `approvalHash` |
| `/api/city-game/neutral-tasks` | Core/CityNeutralTaskService.swift | served | `CITY_NEUTRAL_TASK_V1`, team-scoped QA pick, graded by the server, zero effect |
| `/api/city-game/onsite-tasks` | Core/CityOnsiteTaskService.swift | served | `CITY_ONSITE_TASK_V1`, member-scoped, consumes one store evidence (single use) valid on accept and every answer; `result/{id}` |
| `/api/city-game/task-history` | Core/CityTaskHistoryService.swift | served | `CITY_PERSONAL_TASK_HISTORY_V1`, own onsite instances only, 20 per page, detail + per-request receipt |
| `/api/city-value/visits` (+ `feedback`, `services`) | Core/CityPlayerValueService.swift | served | services = available public POI points of the exact bounded page the app captured; a visit observes only (`publicGoal`, `cooperativeGoal`, `netPoints`, `budget` are `UNAVAILABLE`) |
| `/api/city/walking/target` | Core/CityWalkingAuthorityReader.swift | served | bare JSON (no envelope): reviewed `roam_poi`, GCJ-02 converted once to WGS84 on the server, `authorityRevision city:<id>:<hash>`, valid 120 s |

TEST data contract for r491 boards: points carry `merchant_id` (mms_merchant) and `place_id` (roam_poi);
zero-effect tasks are rows in `city_task_definition`; `task_cost = service_cost = challenge_cost = 0`.

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

**Lifecycle (pass 2 decision, applies to every CITY route).** See "Board lifecycle" above: actions need
`OPEN`; reads accept `OPEN` and `FROZEN`; the board freezes itself after the last settlement or at
`valid_until`.

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
| `/api/play` GET/POST | Core/PlayExperienceService.swift | mapped | existing `/api/play/*` routes |
| `/api/game/session` | Core/PlayExperienceService.swift | mapped | `GET /api/game/session/view`, `/receipt`, `POST /command` |
| `/api/play/check` | Core/JourneyContentService.swift | mapped | `/api/play/check/{roll,reroll,settle,bargain,momentum,rest}`, `/receipt` |
| `/api/play/theme` GET | Core/PlayThemeBackpackActions.swift | mapped | `/api/play/theme/*` |
| `/api/play/route-action` GET | App/PlayRouteMapView.swift | served | own `topic_route_action` receipt |
| `/api/play/egg/collect` | Core/JourneyContentService.swift | closed | 410 `ROUTE_EGG_OFFLINE` before login/params; no table read (route eggs decommissioned 2026-09-07, see Data notes) |
| `/api/play/advanced/time-window` | Core/NativePlatformService.swift | served | `NativeTimeWindow` |
| `/api/play/city-orientation`, `/answer`, `/receipt` | Core/CityOrientationTask*.swift | served | `CITY_ORIENTATION_ANSWER_V1`: `POST answer {sessionId,nodeId,requestId,expectedVersion,answer}` runs the existing answer path for the caller's own run session (requestId is also the route action id) and stores `{payloadHash, configVersion, revision, outcome APPLIED/FAILED, response}` in the same transaction; `GET receipt?sessionId&nodeId&requestId` returns it or `outcome: PENDING` |
| `/api/progress/meter`, `/api/progress/requests`, `/api/progress/requests/{}` | Core/NativeChannelProgress*.swift | mapped | served on `feature/im-channels` (channel platform, e01d7f6e5); not duplicated here |

## Profile (8 paths)

| App path (graph) | App file | Status | Backend |
|---|---|---|---|
| `/api/user/address` | Core/ProfileParticipantTransport.swift | mapped | `/api/user/address/{list,info,action,delete}` |
| `/api/user/deregister` | Core/AccountDeregistrationOriginal.swift | mapped | `/api/user/deregister/{precheck,apply,cancel,status}` |
| `/api/user/sms/send` POST | App/RegionalEndpointAllowlistSectionView.swift | mapped | `POST /api/sms/send` |
| `/api/user/follow-list` | Core/ProfileFollowListService.swift | served | own following/followers keyset |
| `/api/user/deregister/result` | Core/DeregistrationStatusRules.swift | served | `apply` revokes every session, so its PENDING reply now carries a one-time `resultToken` (only the SHA-256 is stored, 45 days); `GET /result?resultToken=` works without a session and reads only that member's `{status, executeAfter, final, verifiedBy}`; a caller still logged in may omit the token; unknown/expired/foreign token → `404 DEREGISTER_RESULT_NOT_FOUND` |
| `/api/onboarding/get`, `/api/onboarding/submit` | Core/NativeChannelOnboarding.swift | mapped | served on `feature/im-channels` |
| `/api/welcome-screen/get` | Core/NativeChannelWelcome.swift | mapped | served on `feature/im-channels` |

## Roam (10 paths)

Bundle routes keep the Phase 1 envelope (`{code:200,data}`, refusal `{code:409,msg,businessCode}`,
`LOGIN_REQUIRED`), are holder-keyed, and answer `BUNDLE_NOT_FOUND` alike for not-found and not-yours.

| App path (graph) | App file | Status | Backend |
|---|---|---|---|
| `/api/free-explore/bundle` GET/POST | App/BundleCommerceReadTransport.swift | mapped | Phase 1 routes: `catalog`, `order`, `order/receipt`, `order/refund-quote` (refund QUOTE read), `reservation*`, `exit-*` |
| `/api/roam/passport` | Core/RoamPassportHistoryReading.swift | mapped | `/api/roam/passport/{history,record,summary}` |
| `/api/free-explore/bundle/shelf` | Core/BundleShelfClient.swift | served | catalog of on-sale free-explore topics (keyset, ≤ 50); `fromPrice` is null (price is decided per ticket at checkout) |
| `/api/free-explore/bundle/voucher` (+ `/voucher/version`, `/redemption/result`, `/wallet`, `/waiver`) | Core/BundleVoucherClient.swift | served | my bundles (`wallet`, with `activation`), voucher (main code = the registration's existing verification code the chapter scanner accepts; per-unit sub codes), 16-hex `voucherVersion` that changes only on unit transitions, redemption result (`SUCCESS`/`NONE`; `REVIEW`/`REJECTED` reserved), monthly waiver read |
| `/api/free-explore/bundle/voucher/appeal` (+ `/receipt`, list) | Core/BundleAppealClient.swift | served | record only: `OPEN`, `applyState NOT_APPLIED`; approval never consumes the unit in v1 (settlement owner decision) |
| `/api/free-explore/bundle/review` (+ `/review/mine`, `/review/list`) | Core/BundleReviewClient.swift | served | one review per order and holder, needs a redeemed unit, `PENDING` until moderated, list shows `APPROVED` only |
| `/api/free-explore/bundle/renewal-intent` | Core/BundleRenewalClient.swift | served | intent only (no price lock, no order), latest wins, history kept |
| `/api/free-explore/bundle/order/refund` | App/BundleRefundTransport.swift | served (read) / write N/A-v1 | `GET ?registrationId=` → `{state: NONE}`; `POST` always `409 BUNDLE_MONEY_WRITE_DISABLED` |
| `/api/free-explore/bundle/transfer` | App/BundleTransfer*.swift | served (read) / write N/A-v1 | `GET /transfer`, `/transfer/preview` (`eligible: false`), `/transfer/receipt` (`NOT_FOUND`); `POST /transfer`, `/transfer/claim`, `/transfer/{id}/revoke` always `409 BUNDLE_MONEY_WRITE_DISABLED` (gifting a paid entitlement) |
| `/api/location-share/personal` | Core/PersonalLocationShareHTTPService.swift | served | see "Personal location share" below |

### Personal location share (v1 rules)

`/api/location-share/personal/{context, grants, requests/{key}, requests/{key}/revoke, owned, received,
received/events, grants/{id}/observations}`. Privacy and retention, as built:
- owner = the logged-in member; exactly one recipient per grant; only between members who **follow each other**;
- one published policy (`location_share_policy`, operations-owned; none published → `POLICY_NOT_PUBLISHED`);
  the client must acknowledge all six disclosures and name the policy digest it was shown;
- foreground only; `COARSE` grants store the snapped grid cell, never the exact fix;
- only the **latest** observation of an ACTIVE grant is stored; it is **deleted** when the grant is revoked,
  expires, or the follow relationship ends; the recipient's change feed says `INVALIDATED`.

## Topics / routes (14 paths)

| App path (graph) | App file | Status | Backend |
|---|---|---|---|
| `/api/topic/attribute-author` GET/POST/DELETE, `/references`, `/validate` | Core/NativeAttributeAuthor*.swift | served | versioned contract on the existing state-key registry: `configVersion` = `theme_attribute_registry_version`, at the envelope root with `topicId`; reads and writes name the version they are based on (`409 CONFIG_VERSION_CHANGED`); a write advances it by exactly one; `validate` returns the complete merged catalog without writing; a referenced key is never deleted (`deleted: false` + positions) |
| `/api/topic/attribute-author/request` | Core/NativeAttributeAuthorExtendedContract.swift | served | mutations carry `X-Attribute-Request-{Protocol,Id,SHA256}` + `X-Attribute-Session-Epoch`; the server checks the app's payload digest, stores the reply with `requestReceipt` in the same transaction; `GET /request` replays it or answers `state: NOT_FOUND` |
| `/api/topic/module-preflight` | Core/NativeAttributeAuthorHostTransport.swift | served | `{topicId, configVersion, ready, issues[level, code, nodeId, message, field]}` from the existing publish checks; `ready` = no `error` issue |
| `/api/topic/cover`, `/selection`, `/selection/current`, `/selection/status`, `/upload`, `/{}/content` | Core/OwnedTopicCover*.swift | served | author-only: upload is decoded and re-encoded as plain JPEG (metadata dropped) into a private directory outside the public `/profile` mapping (`chengyin.topic-cover.private-dir`; unset → `503 COVER_STORAGE_UNAVAILABLE`); content is served only to its owner, pinned by `sourceVersion` + `contentHash`; selection is a CAS on topic config version, selection version and content slot, advances the topic config version by one, is refused while sale-frozen, and replays by requestId |
| `/api/topic/edit-requests/submit`, `/read`, `/resume-post-processing` | Core/ProjectEditRequestContract.swift | served | `original-project-edit-request-v1`: decodes the exact hashed payload, runs the original writer (same validation and author checks) and stores the receipt in the same transaction (`COMMITTED` with `originalResult`) or records the refusal (`REJECTED`); same requestId replays; `postProcessing: UNAVAILABLE` (nothing tracked) |

## Rewards (18 paths)

| App path (graph) | App file | Status | Reason |
|---|---|---|---|
| `/api/reward-pledge` (+ `/awards`, `/drafts`, `/fulfilment/{awards,expire,preview,redeem,resolve}`, `/ops`, `/pledges`, `/review`, `/versions`) | Core/RewardPledge.swift | N/A-v1 | owner decision: the pledge product moves money (holds, awards, fulfilment); off in v1 |
| `/api/rewards/noncash` (+ `/cases`, `/claims`, `/claims/recover`, `/subjects`, `/versions`) | Core/NonCashRewardService.swift, RewardPledge.swift | N/A-v1 | reads awards only the pledge fulfilment produces; off with it. Coupon rewards stay on `api/coupon/myrecvlist` |

## Data notes

- **`npc_route_egg`**: `migration_x11_decommission_route_eggs_20260907.sql` dropped this table by owner decision
  (route eggs offline), and that decision stands (coordinator, 20261113, superseding the earlier restore request).
  The restore migration was removed in integration/backend-app-r2, and `POST /api/play/egg/collect` is closed:
  410 with `errorCode=ROUTE_EGG_OFFLINE`, answered before login and parameter checks, with no table access.
- New config: `chengyin.topic-cover.private-dir` (private cover bytes; must not be under `ruoyi.profile`).
- New anonymous route: `GET /api/user/deregister/result` (token-only, see Profile).
