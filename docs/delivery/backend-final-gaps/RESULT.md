# backend-final-gaps — result (2026-11-14)

Branch `fix/backend-final-gaps` off `integration/backend-app-r2` `067da9748`, merged back with `--no-ff`. TEST only (VPS `work`,
TEST 8120, DB `cy_e2e_r2`). Production was not touched. hengyin `master` was not pushed. No migration and no ledger change.

**Outcome: no backend gap.** All 204 `.missing` rows of the app's `ChinaReviewedRouteCapability` (app `ui/main` `a2a9d82e7`, the
integrator's `capability-missing-after-067da97.md`) are served, intentionally closed, or app-side literals that the app never sends.
The 59 rows the integrator recorded as 404 are 41 prefix artifacts + 5 `{}`-action-segment artifacts (the children are served) +
13 app literals that are not requests. No main-source change was needed, so the jar on 8120 is unchanged (see §3).

## 1. `/api/merchant/offer-withdrawal/request`

The app client (`Core/MerchantOfferWithdrawalOriginalFlow.swift`) uses `base = "api/merchant/offer-withdrawal/request/"` and calls only:

| App call | Backend (`ApiMerchantGapController`) |
|---|---|
| `GET current?merchantId&offerId` → `MerchantOfferWithdrawalOriginalCurrent` | `@GetMapping(".../request/current")` |
| `GET result?merchantId&offerId&requestId` → `MerchantOfferWithdrawalOriginalReceipt` | `@GetMapping(".../request/result")` |
| `POST withdraw`, JSON with exactly `merchantId, offerId, requestId, intentSHA256, expectedSHA256, reasonSHA256, reason` | `@PostMapping(".../request/withdraw")` |

The bare `/request` (and `/request/`, `/request/{id}`) is not a route and answers 404 for every verb. That is by design: the app never
calls it. The 404 came from probing the prefix.

**App-side defect (for the app team):** `beGapMerchantOwnerGrant` in `ChinaReviewedRouteCapability.swift` lists the bare
`"/api/merchant/offer-withdrawal/request"`. `admitsChinaProductionCall` normalizes the real call paths to `…/request/current`,
`…/request/result` and `…/request/withdraw`, and none of them is in the set. So on the China production host the flow is refused by
the app's own gate. Fix: list the three child paths in the grant (and mark them), not the bare prefix.

Curl on TEST 8120 with the app's exact query items and 7-key body (`smoke_ow.py`, `smoke-ow.log`; offer 992991 reset to `status=1` first):

```
GET/POST/PUT /api/merchant/offer-withdrawal/request (bare)   HTTP 404 请求路径不存在
GET current                                          HTTP 200 code=200  status=1 canWithdraw=True isOrdinary=True keys=24
POST withdraw (7 keys)                               HTTP 200 code=200  outcome=WITHDRAWN statusAtWrite=0 completedAt>0=True
POST withdraw replay (same requestId)                HTTP 200 code=200  outcome=WITHDRAWN
GET result                                           HTTP 200 code=200  outcome=WITHDRAWN
GET result (unseen requestId)                        HTTP 200 code=200  outcome=UNKNOWN
GET current (after)                                  HTTP 200 code=200  status=0 canWithdraw=False
GET current (club user 9003)                         HTTP 403 code=403 MERCHANT_FORBIDDEN
app accept() on WITHDRAWN receipt: PASS
```

(The last line checks the receipt against every rule of the app's `accept()`: version, actor/merchant/owner/offer, requestId, the three echoed
hashes, `receivedSHA256`, `receivedReasonSHA256`, `completedAt`, `topicId`/`chapterId`, `statusAtWrite==0`, `requiredAtWrite∈{0,1}`, `offerUpdatedAt`.)

Unit test: new `ApiMerchantOfferWithdrawalRouteTest` pins the three child mappings and verbs, the 2/3 `@RequestParam`s and the
`@RequestBody`, and that no mapping sits on the bare prefix. With `MerchantOfferWithdrawalRequestServiceTest` (6) and
`ApiMerchantGapControllersTest` (3): **10/10**, BUILD SUCCESS (`flock`, `-T 1`, `MAVEN_OPTS=-Xmx1536m`).

## 2. Probe of every `.missing` row

Method (`probe_all.py`, merchant fixture 9002 on loopback 8120; reviewer 9004 for the reward-pledge gate):

* The backend route table was extracted from this branch with the capgraph `backend_routes.py` (2,305 routes, 0 unresolved).
* Each row was matched to backend routes (`{}` = any segment). Literal matches win over a `{var}` regex route.
* Every matched route was probed **with its mapped verb** (`{}` → `999999999`, POST/PUT body `{}`). Prefix rows (a trailing `/`, or a
  family root) probe every backend child, plus the bare prefix itself for evidence.
* The app verb was read from the call-site files (±12 lines). Where it differed from the backend verb, the app verb was probed too. No 405 and no
  verb mismatch was found.
* Classes: `404` = Spring "请求路径不存在" on every probe; **served** = the route answers (any business code, including 400 validation,
  403 role, 404 business not-found such as `CHANNEL_NOT_FOUND`); **intentionally closed** = an OFF stub answering 503.

| Class | Rows |
|---|---|
| served (route exists at the literal path; the app table is out of date) | 125 |
| served via children (prefix artifact: the app appends a suffix or id to the prefix) | 49 |
| served via children (`{}` is an action segment such as `join`/`dispatch`, not an id) | 5 |
| intentionally closed (`ApiAppGapsWorkshopController` OFF stubs: `WORKSHOP_COMMERCE_DISABLED` for `/api/mod-commerce`, `WORKSHOP_FEATURE_NOT_ENABLED` for mod installation/upgrade/owned-report/settlement and creator pending-packages/public-theme-use) | 12 |
| app literal that the app never sends (display text, unused constants, or repointed) | 13 |
| **backend 404 (gap)** | **0** |

Column "integrator" = the outcome in the integrator's receipt (bare POST/GET with `{}`→`1`).

| App `.missing` row | integrator | Class | Evidence (TEST 8120) |
|---|---|---|---|
| `/api/activity/create-requests/read` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/activity/create-requests/read` → 200/400 协议版本不符 |
| `/api/activity/create-requests/resume-post-processing` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/activity/create-requests/resume-post-processing` → 200/400 协议版本不符 |
| `/api/activity/create-requests/submit` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/activity/create-requests/submit` → 200/400 协议版本不符 |
| `/api/approved-topic-release/v1/prepare` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/approved-topic-release/v1/prepare` → 400/400 缺少字段 |
| `/api/approved-topic-release/v1/publish` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/approved-topic-release/v1/publish` → 400/400 缺少字段 |
| `/api/approved-topic-release/v1/review/current` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/approved-topic-release/v1/review/current` → 400/400 缺少字段 |
| `/api/approved-topic-release/v1/review/prepare` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/approved-topic-release/v1/review/prepare` → 400/400 缺少字段 |
| `/api/approved-topic-release/v1/review/sources` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/approved-topic-release/v1/review/sources` → 400/400 缺少字段 |
| `/api/approved-topic-release/v1/review/status` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/approved-topic-release/v1/review/status` → 400/400 缺少字段 |
| `/api/approved-topic-release/v1/review/submit` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/approved-topic-release/v1/review/submit` → 400/400 缺少字段 |
| `/api/approved-topic-release/v1/status` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/approved-topic-release/v1/status` → 400/400 缺少字段 |
| `/api/channel-platform/admission/requests/` | 404 | served via children (prefix artifact) | 1/1 backend routes answer; GET `/api/channel-platform/admission/requests/999999999` → 200/200 |
| `/api/channel-platform/governance/moderation/requests/` | 404 | served via children (prefix artifact) | 1/1 backend routes answer; GET `/api/channel-platform/governance/moderation/requests/999999999` → 400/400 INVALID_FIELD |
| `/api/channel-platform/governance/reports/requests/` | 404 | served via children (prefix artifact) | 1/1 backend routes answer; GET `/api/channel-platform/governance/reports/requests/999999999` → 400/400 INVALID_FIELD |
| `/api/channel-platform/governance/requests/` | 404 | served via children (prefix artifact) | 1/1 backend routes answer; GET `/api/channel-platform/governance/requests/999999999` → 200/200 |
| `/api/channel-platform/requests/` | 404 | served via children (prefix artifact) | 1/1 backend routes answer; GET `/api/channel-platform/requests/999999999` → 200/200 |
| `/api/channel/{}/board` | answers | served (app table wrong) | 2/2 backend routes answer; GET `/api/channel/999999999/board` → 404/404 CHANNEL_NOT_FOUND; POST `/api/channel/999999999/board` → 400/400 INVALID_FIELD |
| `/api/channel/{}/board/` | answers | served via children (prefix artifact) | 5/5 backend routes answer; GET `/api/channel/999999999/board/999999999/top` → 404/404 CHANNEL_NOT_FOUND; GET `/api/channel/999999999/board/999999999/detail` → 404/404 CHANNEL_NOT_FOUND |
| `/api/channel/{}/board/{}` | 404 | served via children (prefix artifact) | 5/5 backend routes answer; GET `/api/channel/999999999/board/999999999/top` → 404/404 CHANNEL_NOT_FOUND; GET `/api/channel/999999999/board/999999999/detail` → 404/404 CHANNEL_NOT_FOUND |
| `/api/channel/{}/progress/overview` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/channel/999999999/progress/overview` → 404/404 CHANNEL_NOT_FOUND |
| `/api/channel/{}/progress/privacy` | answers | served (app table wrong) | 2/2 backend routes answer; GET `/api/channel/999999999/progress/privacy` → 404/404 CHANNEL_NOT_FOUND; POST `/api/channel/999999999/progress/privacy` → 400/400 INVALID_FIELD |
| `/api/city-game/` | 404 | served via children (prefix artifact) | 43/43 backend routes answer; GET `/api/city-game/action-budget/state` → 200/500 Required request parameter '; POST `/api/city-game/action-budget/command` → 400/400 CITY_BUDGET_INVALID |
| `/api/city-game/acquisition` | 404 | served via children (prefix artifact) | 5/5 backend routes answer; GET `/api/city-game/acquisition/state` → 200/500 Required request parameter '; GET `/api/city-game/acquisition/titles` → 200/500 Required request parameter ' |
| `/api/city-game/acquisition/` | 404 | served via children (prefix artifact) | 5/5 backend routes answer; GET `/api/city-game/acquisition/state` → 200/500 Required request parameter '; GET `/api/city-game/acquisition/titles` → 200/500 Required request parameter ' |
| `/api/city-game/action-budget` | 404 | served via children (prefix artifact) | 3/3 backend routes answer; GET `/api/city-game/action-budget/state` → 200/500 Required request parameter '; POST `/api/city-game/action-budget/command` → 400/400 CITY_BUDGET_INVALID |
| `/api/city-game/action-budget/command` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/city-game/action-budget/command` → 400/400 CITY_BUDGET_INVALID |
| `/api/city-game/action-budget/request/{}` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/city-game/action-budget/request/999999999` → 404/404 CITY_RECEIPT_NOT_FOUND |
| `/api/city-game/action-budget/state` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/city-game/action-budget/state` → 200/500 Required request parameter ' |
| `/api/city-game/arrival-evidence` | 404 | served via children (prefix artifact) | 5/5 backend routes answer; GET `/api/city-game/arrival-evidence/state` → 200/500 Required request parameter '; GET `/api/city-game/arrival-evidence/merchant` → 200/200 |
| `/api/city-game/arrival-evidence/` | 404 | served via children (prefix artifact) | 5/5 backend routes answer; GET `/api/city-game/arrival-evidence/state` → 200/500 Required request parameter '; GET `/api/city-game/arrival-evidence/merchant` → 200/200 |
| `/api/city-game/merchant/operations` | 404 | served via children (prefix artifact) | 3/3 backend routes answer; GET `/api/city-game/merchant/operations/availability` → 200/500 Required request parameter '; POST `/api/city-game/merchant/operations/intent` → 400/400 OBJECT_SCOPE |
| `/api/city-game/merchant/operations/availability` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/city-game/merchant/operations/availability` → 200/500 Required request parameter ' |
| `/api/city-game/merchant/operations/intent` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/city-game/merchant/operations/intent` → 400/400 OBJECT_SCOPE |
| `/api/city-game/merchant/operations/request/` | 404 | served via children (prefix artifact) | 1/1 backend routes answer; GET `/api/city-game/merchant/operations/request/999999999` → 404/404 CITY_RECEIPT_NOT_FOUND |
| `/api/city-game/neutral-tasks` | 404 | served via children (prefix artifact) | 3/3 backend routes answer; GET `/api/city-game/neutral-tasks/state` → 200/500 Required request parameter '; POST `/api/city-game/neutral-tasks/command` → 400/400 CITY_TASK_INVALID |
| `/api/city-game/neutral-tasks/` | 404 | served via children (prefix artifact) | 3/3 backend routes answer; GET `/api/city-game/neutral-tasks/state` → 200/500 Required request parameter '; POST `/api/city-game/neutral-tasks/command` → 400/400 CITY_TASK_INVALID |
| `/api/city-game/onsite-tasks` | 404 | served via children (prefix artifact) | 4/4 backend routes answer; GET `/api/city-game/onsite-tasks/state` → 200/500 Required request parameter '; POST `/api/city-game/onsite-tasks/command` → 400/400 CITY_TASK_INVALID |
| `/api/city-game/onsite-tasks/` | 404 | served via children (prefix artifact) | 4/4 backend routes answer; GET `/api/city-game/onsite-tasks/state` → 200/500 Required request parameter '; POST `/api/city-game/onsite-tasks/command` → 400/400 CITY_TASK_INVALID |
| `/api/city-game/player-rules` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/city-game/player-rules` → 400/400 regionId |
| `/api/city-game/state/deltas` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/city-game/state/deltas` → 400/400 cursor |
| `/api/city-game/state/snapshot` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/city-game/state/snapshot` → 400/400 regionId |
| `/api/city-game/task-history` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/city-game/task-history` → 200/500 Required request parameter ' |
| `/api/city-game/team-participation` | 404 | served via children (prefix artifact) | 3/3 backend routes answer; GET `/api/city-game/team-participation/state` → 200/500 Required request parameter '; GET `/api/city-game/team-participation/invite` → 200/500 Required request parameter ' |
| `/api/city-game/team-participation/command` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/city-game/team-participation/command` → 400/400 requestId |
| `/api/city-game/team-participation/invite` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/city-game/team-participation/invite` → 200/500 Required request parameter ' |
| `/api/city-game/team-participation/state` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/city-game/team-participation/state` → 200/500 Required request parameter ' |
| `/api/city-game/{}/request/{}` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/city-game/999999999/request/999999999` → 404/404 CITY_RECEIPT_NOT_FOUND |
| `/api/city-value/visits` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/city-value/visits` → 400/400 CITY_VALUE_INVALID |
| `/api/city-value/visits/` | answers | served via children (prefix artifact) | 3/3 backend routes answer; GET `/api/city-value/visits/services` → 400/400 regionId; POST `/api/city-value/visits/feedback` → 404/404 CITY_VALUE_VISIT_NOT_FOUND |
| `/api/city-value/visits/feedback` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/city-value/visits/feedback` → 404/404 CITY_VALUE_VISIT_NOT_FOUND |
| `/api/city-value/visits/services` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/city-value/visits/services` → 400/400 regionId |
| `/api/city/nodes/complete` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/city/nodes/999999999` → 200/410 据点未开放或已下线 |
| `/api/city/nodes/favorite` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/city/nodes/999999999` → 200/410 据点未开放或已下线 |
| `/api/city/walking/target` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/city/walking/target` → 200/500 Required request parameter ' |
| `/api/club/admission/receipt` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/club/admission/receipt` → 200/400 读回参数无效 |
| `/api/club/badge/original-receipt` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/club/badge/original-receipt` → 200/400 读回参数无效 |
| `/api/club/event-ops/series/original-result` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/club/event-ops/series/original-result` → 200/400 读回参数无效 |
| `/api/club/event-ops/waitlist/{}` | 404 | served via children (`{}` is an action segment) | 3/3 backend routes answer; POST `/api/club/event-ops/waitlist/join` → 200/400 候补参数不完整; POST `/api/club/event-ops/waitlist/status` → 200/400 候补参数不完整 |
| `/api/club/open-settings/welcome-note-receipt` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/club/open-settings/welcome-note-receipt` → 200/400 读回参数无效 |
| `/api/club/post/operation/` | 404 | served via children (prefix artifact) | 5/5 backend routes answer; POST `/api/club/post/operation/create` → 200/400 原始操作参数无效; POST `/api/club/post/operation/update` → 200/400 原始操作参数无效 |
| `/api/club/post/operation/comment/` | 404 | served via children (prefix artifact) | 2/2 backend routes answer; POST `/api/club/post/operation/comment/create` → 200/400 原始操作参数无效; POST `/api/club/post/operation/comment/receipt` → 200/400 原始操作参数无效 |
| `/api/club/post/operation/receipt` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/club/post/operation/receipt` → 200/400 原始操作参数无效 |
| `/api/club/roles/assign-receipt` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/club/roles/assign-receipt` → 200/400 读回参数无效 |
| `/api/club/roles/revoke-receipt` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/club/roles/revoke-receipt` → 200/400 读回参数无效 |
| `/api/club/settings/notify-level` | answers | served (app table wrong) | 2/2 backend routes answer; GET `/api/club/settings/notify-level` → 200/500 Required request parameter '; POST `/api/club/settings/notify-level` → 400/400 INVALID_FIELD |
| `/api/club/settlements` | 404 | served via children (prefix artifact) | 1/1 backend routes answer; POST `/api/club/settlements/export` → 400/400 BAD_REQUEST |
| `/api/club/update` | 404 | app literal, not called | the app sends `POST api/club/update-mine` (`ClubOperationsContracts.swift:135`, `RePoint03ClubUpdate.swift:11`), which is served; `api/club/update` is only a label in `ChannelDiscordMessaging.swift:3029` |
| `/api/common/uploadOSS/receipt` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/common/uploadOSS/receipt` → 200/400 读回参数无效 |
| `/api/compliance/usage-records` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/compliance/usage-records` → 200/200 |
| `/api/config/public` | 404 | app literal, not called | only text in `AuthAndSessionAdmissionParityAudit.swift:90` and `BackendSyncAndContractGateAudit.swift:61`; no request is built from it |
| `/api/coupon/command-receipt` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/coupon/command-receipt` → 200/400 参数缺失 |
| `/api/free-explore/bundle/transfer` | answers | served (app table wrong) | 2/2 backend routes answer; GET `/api/free-explore/bundle/transfer` → 200/409 自由探索套票暂未开放; POST `/api/free-explore/bundle/transfer` → 200/409 套票退款与转赠在当前版本不开放 |
| `/api/free-explore/bundle/voucher/appeal` | answers | served (app table wrong) | 2/2 backend routes answer; POST `/api/free-explore/bundle/voucher/appeal` → 200/409 自由探索套票暂未开放; GET `/api/free-explore/bundle/voucher/appeal` → 200/409 自由探索套票暂未开放 |
| `/api/friend/` | 404 | served via children (prefix artifact) | 28/29 backend routes answer; GET `/api/friend/list` → 200/500 Required request parameter '; GET `/api/friend/requests` → 200/500 Required request parameter ' |
| `/api/friend/channel-invite/` | 404 | served via children (prefix artifact) | 7/7 backend routes answer; GET `/api/friend/channel-invite/picker` → 200/500 Required request parameter '; POST `/api/friend/channel-invite/command` → 200/400 INVALID_FIELD |
| `/api/friend/directory` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/friend/directory` → 200/500 Required request parameter ' |
| `/api/friend/directory/detail` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/friend/directory/detail` → 200/500 Required request parameter ' |
| `/api/friend/group/delete` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/friend/group/delete` → 200/400 INVALID_FIELD |
| `/api/friend/group/list` | answers | served (app table wrong) | 2/2 backend routes answer; GET `/api/friend/group/list` → 200/200; POST `/api/friend/group/list` → 200/200 |
| `/api/friend/group/save` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/friend/group/save` → 200/400 INVALID_FIELD |
| `/api/friend/metadata` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/friend/metadata` → 200/500 Required request parameter ' |
| `/api/friend/metadata/receipt` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/friend/metadata/receipt` → 200/500 Required request parameter ' |
| `/api/friend/metadata/settle` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/friend/metadata/settle` → 200/400 INVALID_FIELD |
| `/api/friend/progress` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/friend/progress` → 200/500 Required request parameter ' |
| `/api/friend/remark` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/friend/remark` → 200/400 INVALID_FIELD |
| `/api/friend/removal/receipt` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/friend/removal/receipt` → 200/500 Required request parameter ' |
| `/api/friend/removal/settle` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/friend/removal/settle` → 200/400 INVALID_FIELD |
| `/api/friend/remove` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/friend/remove` → 200/400 INVALID_FIELD |
| `/api/friend/team-invite/` | 404 | served via children (prefix artifact) | 3/3 backend routes answer; GET `/api/friend/team-invite/picker` → 200/500 Required request parameter '; GET `/api/friend/team-invite/landing` → 200/500 Required request parameter ' |
| `/api/growth/leaderboard/anonymous` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/growth/leaderboard/anonymous` → 200/200 |
| `/api/invite/revoke` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/invite/revoke` → 400/400 INVALID_FIELD |
| `/api/join-request/approve` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/join-request/approve` → 400/400 INVALID_FIELD |
| `/api/join-request/cancel` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/join-request/cancel` → 400/400 INVALID_FIELD |
| `/api/join-request/reject` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/join-request/reject` → 400/400 INVALID_FIELD |
| `/api/join-request/submit` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/join-request/submit` → 400/400 INVALID_FIELD |
| `/api/location-share/personal` | 404 | served via children (prefix artifact) | 8/8 backend routes answer; GET `/api/location-share/personal/context` → 200/500 Required request parameter '; POST `/api/location-share/personal/grants` → 400/400 INVALID_REQUEST |
| `/api/map/public-layers` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/map/public-layers` → 200/400 图层请求无效 |
| `/api/marketing/affiliate/summary` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/marketing/affiliate/summary` → 200/200 |
| `/api/marketing/product` | 404 | served via children (prefix artifact) | 11/11 backend routes answer; GET `/api/marketing/product/list` → 200/200; GET `/api/marketing/product/999999999` → 200/404 商品不存在 |
| `/api/marketing/product/list` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/marketing/product/list` → 200/200 |
| `/api/marketing/product/orders` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/marketing/product/orders` → 200/200 |
| `/api/merchant/authoring/player/` | 404 | served via children (prefix artifact) | 2/2 backend routes answer; GET `/api/merchant/authoring/player/999999999/game` → 200/200; GET `/api/merchant/authoring/player/999999999/requests/999999999` → 400/400 INVALID_REQUEST |
| `/api/merchant/crm/campaigns/{}/{}` | 404 | served via children (`{}` is an action segment) | 2/2 backend routes answer; POST `/api/merchant/crm/campaigns/999999999/dispatch` → 200/404 触达任务不存在或不属于当前商家; POST `/api/merchant/crm/campaigns/999999999/retry` → 200/404 触达任务不存在或不属于当前商家 |
| `/api/merchant/offer-withdrawal/request/` | 404 | served via children (prefix artifact) | 3/3 backend routes answer; GET `/api/merchant/offer-withdrawal/request/current` → 403/403 MERCHANT_SCOPE_MISMATCH; GET `/api/merchant/offer-withdrawal/request/result` → 403/403 MERCHANT_SCOPE_MISMATCH; with the app's query items and body the full protocol is 200 and WITHDRAWN (see §1, `smoke-ow.log`) |
| `/api/merchant/reviews/{}` | 404 | served via children (`{}` is an action segment) | 5/5 backend routes answer; POST `/api/merchant/reviews/public` → 200/500 Required request parameter '; POST `/api/merchant/reviews/manage` → 200/200 |
| `/api/mod-commerce/` | answers | intentionally closed | `ApiAppGapsWorkshopController` OFF stub (`WORKSHOP_COMMERCE_DISABLED`), 2/2 routes; GET `/api/mod-commerce/x` → 200/503 WORKSHOP_COMMERCE_DISABLED |
| `/api/mod-installation/` | answers | intentionally closed | `ApiAppGapsWorkshopController` OFF stub (`WORKSHOP_FEATURE_NOT_ENABLED`), 2/2 routes; GET `/api/mod-installation/x` → 200/503 WORKSHOP_FEATURE_NOT_ENABLED |
| `/api/mod-owned-report/licenses/` | answers | intentionally closed | `ApiAppGapsWorkshopController` OFF stub (`WORKSHOP_FEATURE_NOT_ENABLED`), 2/2 routes; GET `/api/mod-owned-report/licenses/` → 200/503 WORKSHOP_FEATURE_NOT_ENABLED |
| `/api/mod-settlement/mine` | answers | intentionally closed | `ApiAppGapsWorkshopController` OFF stub (`WORKSHOP_FEATURE_NOT_ENABLED`), 1/1 routes; GET `/api/mod-settlement/mine` → 200/503 WORKSHOP_FEATURE_NOT_ENABLED |
| `/api/mod-settlement/mine/income` | answers | intentionally closed | `ApiAppGapsWorkshopController` OFF stub (`WORKSHOP_FEATURE_NOT_ENABLED`), 2/2 routes; GET `/api/mod-settlement/mine/income` → 200/503 WORKSHOP_FEATURE_NOT_ENABLED |
| `/api/mod-upgrade/` | answers | intentionally closed | `ApiAppGapsWorkshopController` OFF stub (`WORKSHOP_FEATURE_NOT_ENABLED`), 2/2 routes; GET `/api/mod-upgrade/x` → 200/503 WORKSHOP_FEATURE_NOT_ENABLED |
| `/api/native/channels/read-through` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/native/channels/read-through` → 200/500 Required request parameter ' |
| `/api/official/v2/events` | 404 | served via children (prefix artifact) | 6/6 backend routes answer; GET `/api/official/v2/events/999999999/publish-check` → 200/500 无权查看该活动发布检查; POST `/api/official/v2/events/999999999/schedule` → 200/500 无官方发布权限 |
| `/api/official/v2/organizer-invites/{}/{}` | 404 | served via children (`{}` is an action segment) | 2/2 backend routes answer; POST `/api/official/v2/organizer-invites/999999999/accept` → 200/500 无官方发布权限; POST `/api/official/v2/organizer-invites/999999999/decline` → 200/500 无官方发布权限 |
| `/api/onboarding/get` | answers | served (app table wrong) | 2/2 backend routes answer; POST `/api/onboarding/get` → 400/400 INVALID_FIELD; GET `/api/onboarding/get` → 200/500 Required request parameter ' |
| `/api/onboarding/submit` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/onboarding/submit` → 400/400 INVALID_FIELD |
| `/api/order/` | 404 | app literal, not called | only a label in `App/NativeSupersetOverMiniProgramSectionView.swift:108` (`api/order/* · …`) |
| `/api/play/advanced/time-window` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/play/advanced/time-window` → 200/400 参数有误 |
| `/api/play/city-orientation/` | 404 | served via children (prefix artifact) | 2/2 backend routes answer; POST `/api/play/city-orientation/answer` → 400/400 CITY_ORIENTATION_INVALID; GET `/api/play/city-orientation/receipt` → 200/500 Required request parameter ' |
| `/api/play/city-orientation/answer` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/play/city-orientation/answer` → 400/400 CITY_ORIENTATION_INVALID |
| `/api/play/city-orientation/receipt` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/play/city-orientation/receipt` → 200/500 Required request parameter ' |
| `/api/play/egg/collect` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/play/egg/collect` → 200/410 ROUTE_EGG_OFFLINE |
| `/api/play/player-journey/choices` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/play/player-journey/choices` → 200/500 参数有误 |
| `/api/play/player-journey/memory` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/play/player-journey/memory` → 200/500 参数有误 |
| `/api/play/player-journey/report` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/play/player-journey/report` → 200/500 参数有误 |
| `/api/play/player-journey/seats` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/play/player-journey/seats` → 200/500 参数有误 |
| `/api/play/route-action` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/play/route-action` → 200/500 Required request parameter ' |
| `/api/play/seat-invitations` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/play/seat-invitations` → 200/400 参数有误 |
| `/api/privacy/progress` | answers | served (app table wrong) | 2/2 backend routes answer; GET `/api/privacy/progress` → 200/200; POST `/api/privacy/progress` → 400/400 INVALID_FIELD |
| `/api/progress/meter` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/progress/meter` → 200/500 Required request parameter ' |
| `/api/progress/requests` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/progress/requests` → 200/500 Required request parameter ' |
| `/api/progress/requests/` | answers | served via children (prefix artifact) | 1/1 backend routes answer; GET `/api/progress/requests/999999999` → 400/400 INVALID_FIELD |
| `/api/progress/requests/{}` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/progress/requests/999999999` → 400/400 INVALID_FIELD |
| `/api/project/state-requests/context` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/project/state-requests/context` → 200/400 参数无效 |
| `/api/project/state-requests/read` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/project/state-requests/read` → 200/400 读回参数无效 |
| `/api/project/state-requests/submit` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/project/state-requests/submit` → 200/400 协议版本不符 |
| `/api/public-catalog/city/list` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/public-catalog/city/list` → 200/200 |
| `/api/public-catalog/filters` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/public-catalog/filters` → 200/200 |
| `/api/public-catalog/ids` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/public-catalog/ids` → 200/200 |
| `/api/public-catalog/info` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/public-catalog/info` → 200/400 id 参数不合规 |
| `/api/public-catalog/list` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/public-catalog/list` → 200/200 |
| `/api/public-catalog/map` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/public-catalog/map` → 200/200 |
| `/api/rank/list` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/rank/list` → 200/500 scope 不合法 |
| `/api/referral/landing/gift` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/referral/landing/gift` → 200/200 |
| `/api/referral/landing/referral` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/referral/landing/referral` → 200/200 |
| `/api/reward-pledge` | 404 | served via children (prefix artifact) | 17/20 backend routes answer; POST `/api/reward-pledge/drafts` → 200/400 INVALID_INPUT; POST `/api/reward-pledge/999999999/submit` → 200/404 NOT_FOUND; `POLICY_CLOSED` rows are the reviewer gate (9004 reviewer gets 200) |
| `/api/reward-pledge/` | 404 | served via children (prefix artifact) | 17/20 backend routes answer; POST `/api/reward-pledge/drafts` → 200/400 INVALID_INPUT; POST `/api/reward-pledge/999999999/submit` → 200/404 NOT_FOUND; `POLICY_CLOSED` rows are the reviewer gate (9004 reviewer gets 200) |
| `/api/reward-pledge/awards/` | answers | intentionally closed | `ApiAppGapsWorkshopController` OFF stub (`POLICY_CLOSED`), 1/1 routes; POST `/api/reward-pledge/awards/999999999/return-to-pool` → 200/409 POLICY_CLOSED |
| `/api/reward-pledge/ops/` | answers | served via children (prefix artifact) | 1/1 backend routes answer; GET `/api/reward-pledge/ops/999999999/999999999` → 200/404 NOT_FOUND |
| `/api/reward-pledge/review/` | answers | intentionally closed | `ApiAppGapsWorkshopController` OFF stub (`POLICY_CLOSED`), 1/1 routes; GET `/api/reward-pledge/review/999999999` → 200/409 POLICY_CLOSED |
| `/api/reward-pledge/versions/` | answers | served via children (prefix artifact) | 2/2 backend routes answer; POST `/api/reward-pledge/versions/999999999/withdraw` → 200/404 NOT_FOUND; POST `/api/reward-pledge/versions/999999999/hold` → 200/404 NOT_FOUND |
| `/api/rewards/noncash/` | answers | served via children (prefix artifact) | 7/7 backend routes answer; POST `/api/rewards/noncash/claims` → 200/400 INVALID_INPUT; POST `/api/rewards/noncash/claims/recover` → 200/400 INVALID_INPUT |
| `/api/rewards/noncash/subjects/` | answers | served via children (prefix artifact) | 1/1 backend routes answer; GET `/api/rewards/noncash/subjects/999999999` → 200/404 NOT_FOUND |
| `/api/rewards/noncash/versions/` | answers | served via children (prefix artifact) | 1/1 backend routes answer; GET `/api/rewards/noncash/versions/999999999` → 200/400 INVALID_INPUT |
| `/api/roam/check-in.` | 404 | app literal, not called | sentence text in `App/RoamAndExplorationParitySectionView.swift:134`; the backend route is `POST /api/roam/checkin` |
| `/api/roam/finished` | 404 | app literal, not called | summary text in `App/RoamAndExplorationParitySectionView.swift:109`; backend has `POST /api/roam/finish` |
| `/api/roam/point/{}` | 404 | app literal, not called | summary text in `App/RoamAndExplorationParitySectionView.swift:109` |
| `/api/roam/route/{}` | 404 | app literal, not called | summary text in `App/RoamAndExplorationParitySectionView.swift:109` |
| `/api/roam/routes` | 404 | app literal, not called | summary text in `App/RoamAndExplorationParitySectionView.swift:109` |
| `/api/space/join-pause` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/space/join-pause` → 400/400 INVALID_FIELD |
| `/api/space/leave` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/space/leave` → 400/400 INVALID_FIELD |
| `/api/stamps` | 404 | app literal, not called | summary text in `App/RoamAndExplorationParitySectionView.swift:49,125`; backend stamps are `POST /api/roam/stamp/{list,received}` |
| `/api/support/tickets` | 404 | served via children (prefix artifact) | 2/2 backend routes answer; POST `/api/support/tickets/list` → 200/400 参数无效; POST `/api/support/tickets/detail` → 200/400 参数无效 |
| `/api/team/original-request` | answers | served (app table wrong) | 2/2 backend routes answer; POST `/api/team/original-request` → 200/400 INVALID_FIELD; GET `/api/team/original-request` → 200/500 Required request parameter ' |
| `/api/team/{}` | 404 | served via children (`{}` is an action segment) | 20/20 backend routes answer; POST `/api/team/original-request` → 200/400 INVALID_FIELD; GET `/api/team/original-request` → 200/500 Required request parameter ' |
| `/api/template/author-requests/read` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/template/author-requests/read` → 200/400 协议版本不符 |
| `/api/template/author-requests/submit` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/template/author-requests/submit` → 200/400 协议版本不符 |
| `/api/template/report-receipt` | 404 | app literal, not called | `TemplateContentReportTarget.receiptPath`/`readPaths` (`TemplateContentReportContracts.swift:11-13`) are never referenced; the report write goes through `api/template/report` / `personal-report` (served) with no receipt decoder to implement |
| `/api/topic/attribute-author` | answers | served (app table wrong) | 3/3 backend routes answer; GET `/api/topic/attribute-author` → 200/500 Required request parameter '; POST `/api/topic/attribute-author` → 400/400 ATTRIBUTE_REQUEST_INVALID |
| `/api/topic/attribute-author/references` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/topic/attribute-author/references` → 200/500 Required request parameter ' |
| `/api/topic/attribute-author/request` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/topic/attribute-author/request` → 400/400 BAD_REQUEST |
| `/api/topic/attribute-author/validate` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/topic/attribute-author/validate` → 400/400 ATTRIBUTE_REQUEST_INVALID |
| `/api/topic/cover` | 404 | served via children (prefix artifact) | 5/5 backend routes answer; GET `/api/topic/cover/selection/current` → 200/500 Required request parameter '; POST `/api/topic/cover/upload` → 200/400 上传数据无法解析，请重试或更换文件 |
| `/api/topic/cover/selection` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/topic/cover/selection` → 400/400 COVER_SELECTION_INVALID |
| `/api/topic/cover/selection/current` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/topic/cover/selection/current` → 200/500 Required request parameter ' |
| `/api/topic/cover/selection/status` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/topic/cover/selection/status` → 400/400 COVER_SELECTION_INVALID |
| `/api/topic/cover/upload` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/topic/cover/upload` → 200/400 上传数据无法解析，请重试或更换文件 |
| `/api/topic/cover/{}/content` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/topic/cover/999999999/content` → 200/500 Required request parameter ' |
| `/api/topic/detail` | 404 | app literal, not called | repointed to `POST /api/topic/info-to-user` (`RePoint10Routes.swift:22`, `Adapt2MerchantTopicRoutes.swift:39`); otherwise a default display string in `PublishTopicAndCommentModerationSectionView.swift:78` |
| `/api/topic/edit-requests/read` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/topic/edit-requests/read` → 400/400 EDIT_REQUEST_INVALID |
| `/api/topic/edit-requests/resume-post-processing` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/topic/edit-requests/resume-post-processing` → 400/400 EDIT_REQUEST_INVALID |
| `/api/topic/edit-requests/submit` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/topic/edit-requests/submit` → 400/400 EDIT_REQUEST_INVALID |
| `/api/topic/module-preflight` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/topic/module-preflight` → 200/500 Required request parameter ' |
| `/api/topic/publish-status` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/topic/publish-status` → 200/500 参数不合规 |
| `/api/user/coupons/{}/use` | 404 | app literal, not called | text "… remain fail-closed" in `App/RoamAndExplorationParitySectionView.swift:118` |
| `/api/user/follow-list` | answers | served (app table wrong) | 1/1 backend routes answer; GET `/api/user/follow-list` → 200/500 Required request parameter ' |
| `/api/user/stamps/received` | 404 | app literal, not called | summary text in `App/RoamAndExplorationParitySectionView.swift:49,125` and `MiniProgramVsNativeSupersetClassifierSectionView.swift:92`; backend is `POST /api/roam/stamp/received` |
| `/api/welcome-screen/get` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/welcome-screen/get` → 400/400 INVALID_FIELD |
| `/api/workshop/creator/pending-packages` | answers | intentionally closed | `ApiAppGapsWorkshopController` OFF stub (`WORKSHOP_FEATURE_NOT_ENABLED`), 2/2 routes; GET `/api/workshop/creator/pending-packages/x` → 200/503 WORKSHOP_FEATURE_NOT_ENABLED |
| `/api/workshop/creator/pending-packages/` | answers | intentionally closed | `ApiAppGapsWorkshopController` OFF stub (`WORKSHOP_FEATURE_NOT_ENABLED`), 2/2 routes; GET `/api/workshop/creator/pending-packages/x` → 200/503 WORKSHOP_FEATURE_NOT_ENABLED |
| `/api/workshop/creator/public-theme-use` | answers | intentionally closed | `ApiAppGapsWorkshopController` OFF stub (`WORKSHOP_FEATURE_NOT_ENABLED`), 2/2 routes; GET `/api/workshop/creator/public-theme-use/x` → 200/503 WORKSHOP_FEATURE_NOT_ENABLED |
| `/api/workshop/creator/public-theme-use/` | answers | intentionally closed | `ApiAppGapsWorkshopController` OFF stub (`WORKSHOP_FEATURE_NOT_ENABLED`), 2/2 routes; GET `/api/workshop/creator/public-theme-use/x` → 200/503 WORKSHOP_FEATURE_NOT_ENABLED |
| `/api/workshop/owned` | 404 | served via children (prefix artifact) | 3/3 backend routes answer; POST `/api/workshop/owned/list` → 200/400 列表不接受参数; POST `/api/workshop/owned/detail` → 200/400 领取编号无效 |
| `/api/workshop/owned/` | 404 | served via children (prefix artifact) | 3/3 backend routes answer; POST `/api/workshop/owned/list` → 200/400 列表不接受参数; POST `/api/workshop/owned/detail` → 200/400 领取编号无效 |
| `/api/workshop/owned/detail` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/workshop/owned/detail` → 200/400 领取编号无效 |
| `/api/workshop/owned/list` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/workshop/owned/list` → 200/400 列表不接受参数 |
| `/api/workshop/owned/package` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/workshop/owned/package` → 200/400 领取编号无效 |
| `/api/workshop/purchased` | 404 | served via children (prefix artifact) | 13/13 backend routes answer; POST `/api/workshop/purchased/professional-targets/list` → 400/400 BAD_REQUEST; POST `/api/workshop/purchased/professional-targets/detail` → 400/400 BAD_REQUEST |
| `/api/workshop/purchased/` | 404 | served via children (prefix artifact) | 13/13 backend routes answer; POST `/api/workshop/purchased/professional-targets/list` → 400/400 BAD_REQUEST; POST `/api/workshop/purchased/professional-targets/detail` → 400/400 BAD_REQUEST |
| `/api/workshop/purchased/detail` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/workshop/purchased/detail` → 200/409 WORKSHOP_CONTRACT_UPGRADE_RE |
| `/api/workshop/purchased/install` | 404 | served via children (prefix artifact) | 4/4 backend routes answer; POST `/api/workshop/purchased/install/targets` → 200/409 WORKSHOP_CONTRACT_UPGRADE_RE; POST `/api/workshop/purchased/install/history` → 200/409 WORKSHOP_CONTRACT_UPGRADE_RE |
| `/api/workshop/purchased/install/` | 404 | served via children (prefix artifact) | 4/4 backend routes answer; POST `/api/workshop/purchased/install/targets` → 200/409 WORKSHOP_CONTRACT_UPGRADE_RE; POST `/api/workshop/purchased/install/history` → 200/409 WORKSHOP_CONTRACT_UPGRADE_RE |
| `/api/workshop/purchased/installed-text/detail` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/workshop/purchased/installed-text/detail` → 200/409 WORKSHOP_CONTRACT_UPGRADE_RE |
| `/api/workshop/purchased/list` | answers | served (app table wrong) | 1/1 backend routes answer; POST `/api/workshop/purchased/list` → 200/409 WORKSHOP_CONTRACT_UPGRADE_RE |

## 3. Merge, TEST 8120, push

* `fix/backend-final-gaps` adds only `ApiMerchantOfferWithdrawalRouteTest` (test source) and this folder. It is merged into
  `integration/backend-app-r2` with `--no-ff`; both branches are pushed.
* **8120 was not redeployed.** There is no main-source change and no migration, so a rebuilt jar would carry the same code
  as the running `067da9748` jar. No migration was applied, and the R6 rollback (`app/*.prev-6737904`) and the R6 deploy are unchanged.
* TEST data touched: `merchant_chapter_offer` 992991 was set back to `status=1` twice for the curl run and ends WITHDRAWN (`status=0`), as R5/R6 left it.

## 4. For the app team

1. Replace `"/api/merchant/offer-withdrawal/request"` in `beGapMerchantOwnerGrant` with the three child paths (§1).
2. Flip the 179 served rows (125 literal + 49 prefix + 5 action-segment). For prefix rows the mark key is a call-site prefix, so mark or grant the
   concrete child patterns the client calls.
3. Keep the 12 workshop OFF-stub rows hidden: the backend closes them on purpose.
4. Drop or reword the 13 non-request literals (roam/stamps/coupons/order/config text, `report-receipt`, `club/update`, `topic/detail`) so they stop counting as `.missing`.

Files here: `probe_lib.py`, `match.py`, `probe_all.py`, `classify.py`, `table.py`, `smoke_ow.py`, `smoke-ow.log`, `probe-table.md`
(the table above). VPS copies, raw `probe.json` and `test-unit.log` are in `/data/workspace/be-int/final-gaps/`.
