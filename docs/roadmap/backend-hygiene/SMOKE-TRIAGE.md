# Smoke failure triage (R9 BE-05)

Source: the 387 `SMOKE-*` rows of the app completeness matrix (`chengyin-app` `docs/release/completeness-matrix.md`, refresh 5, results.json 2026-11-14T16:36Z, backend `c879749c8`) and `docs/delivery/backend-app-r2/R5-BE-INFRA-STUBS-AND-SMOKE.md` (241 backend errors after R5; that run did not publish a per-route list, so this table classifies the 387 matrix rows and marks what R5 already closed). No live server was called for this triage; classification is from the recorded responses plus reading the code.

Classes:

- **HARNESS**: the smoke run sent bad/missing parameters, used unseeded ids, or the TEST environment lacks a provider/fixture. The backend answer is correct for that input.
- **EXPECTED**: a correct business refusal for the seeded state, a switched-off feature, or an honest "not configured" answer.
- **DEFECT**: the backend is wrong in code.

## Totals

| class | rows |
|---|---:|
| HARNESS | 285 |
| EXPECTED | 99 |
| DEFECT | 3 |
| **total** | **387** |

By matrix category:

| matrix category | HARNESS | EXPECTED | DEFECT |
|---|---:|---:|---:|
| AUTH_401 | 1 | 0 | 0 |
| BAD_REQUEST_400_422 | 142 | 5 | 0 |
| BIZ_500_APPID_MISMATCH | 1 | 0 | 0 |
| BIZ_500_INTERNAL_BUSY | 0 | 0 | 1 |
| BIZ_500_STATE_OR_RULE | 52 | 87 | 0 |
| BIZ_500_UNIMPLEMENTED_VOICE | 0 | 1 | 0 |
| BIZ_500_WXACODE_BUSY | 4 | 0 | 0 |
| BIZ_520_PENDING_RECEIPT | 0 | 2 | 0 |
| CHANNELS_STATE_SEED | 3 | 0 | 0 |
| DIRECT_CURL_200_HARNESS_PARAM | 9 | 0 | 2 |
| ENTITY_NOT_FOUND_404 | 62 | 0 | 0 |
| FORBIDDEN_403 | 2 | 1 | 0 |
| HTTP_404_NO_ROUTE | 3 | 0 | 0 |
| HTTP_500_OUTCOME_UNKNOWN | 5 | 0 | 0 |
| NOT_IMPLEMENTED_501 | 0 | 3 | 0 |
| SHAPE_MISMATCH | 1 | 0 | 0 |

## Defects and what was done

| # | rows | defect | fix |
|---|---|---|---|
| 1 | 139 `BIZ_500_STATE_OR_RULE` + every other refusal returned as `code: 500` | A deliberate business refusal and a system fault share `code: 500`; nobody (app, harness, metrics) can tell them apart. Changing the code is not safe: the app (`Core/CityNodeRedemptionOutcome.swift:96`) and the mini-program (`utils/merchant-verification.js`, `citynode-redeem-outcome.js`) branch on `code == 500` plus the message. | R9 adds an additive `errorKind` key (`BUSINESS` / `SYSTEM` / `NOT_BUILT` / `POLICY_CLOSED`). `GlobalExceptionHandler` sets it for thrown exceptions; `ApiEnvelopeMetricsAdvice` sets it for controllers that return `AjaxResult.error(...)` themselves (generic "系统繁忙/操作失败" texts → `SYSTEM`). `code` and `msg` are unchanged. The smoke harness should count `errorKind=BUSINESS` as an expected refusal and only `SYSTEM` as a backend error. |
| 2 | SMOKE-304, SMOKE-306 | One damaged saved CRM segment made `GET /api/merchant/crm/segments` fail for the whole merchant. | `MerchantCrmSegmentServiceImpl.list` leaves the damaged row out and logs it; using that segment still refuses loudly. Test `MerchantCrmSegmentListResilienceTest`. |
| 3 | SMOKE-150 | Broadcast finalize wrote `audience_count = null` → "系统繁忙". | Closed in R5 (`MerchantCrmBroadcastServiceImpl`). |
| 4 | (base test) | `ChapterFlowWriteServiceTest.openBundleReviewCannotBeBypassedBySwitchingToSimpleMode` failed on the base. | Not a product defect: the service already refuses with `ServiceException(409)` (a proper business refusal); the test still expected the old `IllegalStateException`. Test updated to assert the 409 refusal. |
| 5 | not in the failing set | Wildcard `/**` workshop stubs answered every path, so the smoke run and the capability graph counted them as served (BE-06). | Replaced by the named `NotBuiltFeature` list; answers carry `notBuilt=true`, `feature`, header `X-Chengyin-Not-Built`; `GET /api/meta/not-built` lists them. The harness should classify these as NOT_BUILT, never OK. |

## Harness actions (for the `api-smoke` owner)

1. Read `errorKind`: `BUSINESS` → BUSINESS-REFUSAL-EXPECTED, `NOT_BUILT`/`POLICY_CLOSED` → NOT-BUILT (new bucket, never OK), `SYSTEM` → BACKEND-ERROR. This alone moves the 139 coded-500 refusals out of BACKEND-ERROR.
2. Seed or resolve real ids for the 62 `ENTITY_NOT_FOUND_404` rows and the "不存在" refusals (address, club, task, invite, node, label, state key, receipts). Most need a create-then-read chain using the id returned by the create call.
3. Fix parameter shapes for the 147 `BAD_REQUEST_400_422` rows; the backend messages name the field (`requestId`, `regionId`, `licenseId`, `cursor`, `revision`, `answers`, ...). Request ids must match `[A-Za-z0-9._:-]{6,64}` where the message says so.
4. Use real signed dynamic codes for `/api/verify/*` and `/api/registration/scan_dynamic_code` (format `v1.<regId>.<type>.<exp>.<nonce>.<sig>`).
5. Run activity-time-dependent calls (`/api/play/help*`, `/api/play/advanced/*`, hints) against a started activity fixture.

## Per-row classification

| row | route | role | http/code | class | reason | closed by |
|---|---|---|---|---|---|---|
| SMOKE-001 | `GET /api/us/auth/session` | player | 401/401 | HARNESS | US test session not seeded — `US_SESSION_INVALID` | R5 seeded us_player 9011 |
| SMOKE-002 | `POST /api/ai/template/fill` | merchant(9002) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `商品事实参数不合法` |  |
| SMOKE-003 | `POST /api/ai/theme/draft` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `请先准备本次起草` |  |
| SMOKE-004 | `POST /api/ai/theme/draft/execute` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `本次起草暂不能继续` |  |
| SMOKE-005 | `POST /api/ai/theme/draft/prepare` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `本次起草暂不能继续` |  |
| SMOKE-006 | `GET /api/ai/theme/draft/request` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `请提供一个原请求编号` |  |
| SMOKE-007 | `POST /api/approved-topic-release/v1/review/current` | club(9003) | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `缺少字段` |  |
| SMOKE-008 | `POST /api/approved-topic-release/v1/status` | club(9003) | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `缺少字段` |  |
| SMOKE-009 | `POST /api/audit/appeal` | creator | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `申诉参数不完整` |  |
| SMOKE-010 | `GET /api/audit/appeal/receipt` | creator | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `申诉参数不完整` |  |
| SMOKE-011 | `POST /api/audit/guard/appeal` | creator | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `原到达请求号无效` |  |
| SMOKE-012 | `GET /api/author-write/receipt` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `原请求参数无效` |  |
| SMOKE-013 | `POST /api/capsules/create` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `请保留有效的原请求编号重试` |  |
| SMOKE-014 | `POST /api/channel/redeem` | club(9003) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `兑换码无效` |  |
| SMOKE-015 | `POST /api/city-game/acquisition/command` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `占领指令无效` |  |
| SMOKE-016 | `POST /api/city-game/action-budget/command` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `预算指令无效` |  |
| SMOKE-017 | `POST /api/city-game/arrival-evidence/command` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `到店指令无效` |  |
| SMOKE-018 | `POST /api/city-game/game/action` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `行动请求无效` |  |
| SMOKE-019 | `POST /api/city-game/game/quote` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `报价请求无效` |  |
| SMOKE-020 | `POST /api/city-game/merchant/operations/intent` | merchant | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `门店指令无效` |  |
| SMOKE-021 | `POST /api/city-game/neutral-tasks/command` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `任务指令无效` |  |
| SMOKE-022 | `POST /api/city-game/onsite-tasks/command` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `任务指令无效` |  |
| SMOKE-023 | `GET /api/city-game/participation` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `revision invalid` |  |
| SMOKE-024 | `GET /api/city-game/player-rules` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `regionId invalid` |  |
| SMOKE-025 | `GET /api/city-game/points` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `regionId invalid` |  |
| SMOKE-026 | `GET /api/city-game/state/deltas` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `cursor invalid` |  |
| SMOKE-027 | `GET /api/city-game/state/snapshot` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `sync invalid` |  |
| SMOKE-028 | `GET /api/city-game/task-history` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `beforeInstanceId invalid` |  |
| SMOKE-029 | `POST /api/city-game/team-participation/command` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `参与指令无效` |  |
| SMOKE-030 | `GET /api/city-game/team-participation/invite` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `code invalid` |  |
| SMOKE-031 | `POST /api/city-value/visits` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `到访请求无效` |  |
| SMOKE-032 | `GET /api/city-value/visits/services` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `regionId invalid` |  |
| SMOKE-033 | `POST /api/club/admission/receipt` | club(9003) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `读回参数无效` |  |
| SMOKE-034 | `POST /api/club/badge/original-receipt` | club(9003) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `读回参数无效` |  |
| SMOKE-035 | `POST /api/club/event-ops/series/create` | club(9003) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `requestId 需为 1 至 64 字` |  |
| SMOKE-036 | `POST /api/club/event-ops/series/original-result` | club(9003) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `读回参数无效` |  |
| SMOKE-037 | `POST /api/club/events/save` | club(9003) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `requestId 需为 1 至 64 字` |  |
| SMOKE-038 | `POST /api/club/governance/cases/report` | club(9003) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `举报目标类型只能是 CLUB、ACTIVITY 或 MEMBER` |  |
| SMOKE-039 | `POST /api/club/open-settings/welcome-note-receipt` | club(9003) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `读回参数无效` |  |
| SMOKE-040 | `POST /api/club/post/history` | club(9003) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `动态不存在` |  |
| SMOKE-041 | `POST /api/club/post/operation/comment/create` | club(9003) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `原始操作参数无效` |  |
| SMOKE-042 | `POST /api/club/post/operation/comment/receipt` | club(9003) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `原始操作参数无效` |  |
| SMOKE-043 | `POST /api/club/post/operation/receipt` | club(9003) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `原始操作参数无效` |  |
| SMOKE-044 | `POST /api/club/roles/assign` | club(9003) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `角色不合法` |  |
| SMOKE-045 | `POST /api/club/roles/assign-receipt` | club(9003) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `读回参数无效` |  |
| SMOKE-046 | `POST /api/club/roles/revoke-receipt` | club(9003) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `读回参数无效` |  |
| SMOKE-047 | `POST /api/common/uploadOSS` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `上传请求编号无效` |  |
| SMOKE-048 | `GET /api/common/uploadOSS/receipt` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `读回参数无效` |  |
| SMOKE-049 | `POST /api/community-trust/contributions/submit` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `贡献类型不合法` |  |
| SMOKE-050 | `GET /api/community-trust/reports/mine` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `举报阶段筛选不合法` |  |
| SMOKE-051 | `POST /api/community-trust/reports/submit` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `举报对象不合法` |  |
| SMOKE-052 | `POST /api/community-trust/safety/ratings` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `安全档位不合法` |  |
| SMOKE-053 | `POST /api/coupon/command-receipt` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `参数缺失` |  |
| SMOKE-054 | `POST /api/coupon/stop` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `参数缺失` |  |
| SMOKE-055 | `GET /api/creator/analytics/post-game-outcome` | creator | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `日期格式必须为 yyyy-MM-dd` |  |
| SMOKE-056 | `GET /api/creator/maintenance/requests/{}` | creator | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `requestId invalid` |  |
| SMOKE-057 | `GET /api/exploration-anniversary` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `回忆游标失效，请重新加载当前日期` |  |
| SMOKE-058 | `POST /api/exploration-goals/create` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `请保留有效的原请求编号` |  |
| SMOKE-059 | `POST /api/external-game/reports` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `requestId invalid` |  |
| SMOKE-060 | `POST /api/guilds/{}/{}/channels` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `request_id 不合法` |  |
| SMOKE-061 | `POST /api/host-tip/config` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `币种必须是 3 位大写 ISO-4217 代码` |  |
| SMOKE-062 | `POST /api/im/messages` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `消息增量位置无效` |  |
| SMOKE-063 | `POST /api/invite/revoke` | club(9003) | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `request_id` |  |
| SMOKE-064 | `POST /api/location-share/personal/grants/{}/observations` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `位置上报无效` |  |
| SMOKE-065 | `POST /api/map/public-layers` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `图层请求无效` |  |
| SMOKE-066 | `POST /api/marketing/attribution/claim` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `推广凭据无效或已过期` |  |
| SMOKE-067 | `POST /api/marketing/group-checkout/create` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `报名幂等键不能为空` |  |
| SMOKE-068 | `GET /api/media/asset/attribution` | player | 200/422 | HARNESS | request params missing/invalid; backend validation correct — `媒体来源未经审核` |  |
| SMOKE-069 | `POST /api/merchant/aftercare/list` | merchant(9002) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `bucket 仅允许 PENDING/PROCESSING/COMPLETED/` |  |
| SMOKE-070 | `POST /api/merchant/aftercare/respond` | merchant(9002) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `decision 仅允许 AGREE/REJECT/EVIDENCE` |  |
| SMOKE-071 | `POST /api/merchant/authoring/draft-selection/resolve` | merchant(9002) | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `请求无效` |  |
| SMOKE-072 | `POST /api/merchant/authoring/drafts/{}` | merchant(9002) | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `INPUT` |  |
| SMOKE-073 | `GET /api/merchant/authoring/jobs` | merchant(9002) | 400/400 | EXPECTED | environment: stored AI job FAILED because TEST has no AI upstream; FAILED job is presented as 400 by contract — `{"msg":"UPSTREAM_UNAVAILABLE","code":400` |  |
| SMOKE-074 | `POST /api/merchant/authoring/releases` | merchant(9002) | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `INPUT` |  |
| SMOKE-075 | `GET /api/merchant/city-value/event/{}/benefit` | merchant(9002) | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `请求无效` |  |
| SMOKE-076 | `GET /api/merchant/city-value/events` | merchant(9002) | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `请求无效` |  |
| SMOKE-077 | `GET /api/merchant/city-value/report` | merchant(9002) | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `请求无效` |  |
| SMOKE-078 | `GET /api/merchant/city-value/result/{}` | merchant(9002) | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `请求无效` |  |
| SMOKE-079 | `POST /api/merchant/crm/campaigns` | merchant(9002) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `请填写触达标题` |  |
| SMOKE-080 | `POST /api/merchant/crm/customers/{}/contact` | merchant(9002) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `联系方式用途无效` |  |
| SMOKE-081 | `POST /api/merchant/crm/customers/{}/notes/hide` | merchant(9002) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `请求标识无效` |  |
| SMOKE-082 | `GET /api/merchant/crm/marketing-consents/result` | merchant(9002) | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `请求无效` |  |
| SMOKE-083 | `POST /api/merchant/desk/anchor/save` | merchant(9002) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `请在地图上选择核销锚点` |  |
| SMOKE-084 | `POST /api/merchant/desk/products/sku/save` | merchant(9002) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `缺少商品` |  |
| SMOKE-085 | `POST /api/merchant/npc/avatar/generate` | merchant(9002) | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `形象图片地址无效` |  |
| SMOKE-086 | `POST /api/merchant/npc/knowledge-review` | merchant(9002) | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `请求无效` |  |
| SMOKE-087 | `POST /api/merchant/npc/voice/revoke` | merchant(9002) | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `请求无效` |  |
| SMOKE-088 | `POST /api/merchant/offer-withdrawal/request/withdraw` | merchant(9002) | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `请求字段不完整` |  |
| SMOKE-089 | `POST /api/merchant/operators/invite` | merchant(9002) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `员工角色不合法` |  |
| SMOKE-090 | `POST /api/merchant/operators/invite/accept` | merchant(9002) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `邀请无效或已失效` |  |
| SMOKE-091 | `POST /api/merchant/operators/invite/preview` | merchant(9002) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `邀请无效或已失效` |  |
| SMOKE-092 | `POST /api/merchant/operators/role` | merchant(9002) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `员工角色不合法` |  |
| SMOKE-093 | `POST /api/merchant/reviews/create` | merchant(9002) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `评价图片必须来自城瘾安全上传链路` |  |
| SMOKE-094 | `POST /api/merchant/reviews/manage` | merchant(9002) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `filter 不合法` |  |
| SMOKE-095 | `POST /api/merchant/reviews/reply/receipt` | merchant(9002) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `回复回读参数无效` |  |
| SMOKE-096 | `DELETE /api/native/home` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `HOME_REQUEST_INVALID` |  |
| SMOKE-097 | `PUT /api/native/home` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `HOME_REQUEST_INVALID` |  |
| SMOKE-098 | `POST /api/native/home/return/restore` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `INVALID_HOME_RESTORE_REQUEST` |  |
| SMOKE-099 | `GET /api/native/home/return/runs` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `INVALID_HOME_RUN_CURSOR` |  |
| SMOKE-100 | `POST /api/native/home/return/verify` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `INVALID_HOME_RETURN_REQUEST` |  |
| SMOKE-101 | `POST /api/onboarding/submit` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `answers` |  |
| SMOKE-102 | `POST /api/order-center/detail` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `订单编号不合法` |  |
| SMOKE-103 | `POST /api/platform-pass/orders` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `支付方式无效` |  |
| SMOKE-104 | `POST /api/play/advanced/action` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `玩法会话不存在或无权访问` |  |
| SMOKE-105 | `GET /api/play/check/receipt` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `原检定参数有误` |  |
| SMOKE-106 | `POST /api/play/city-orientation/answer` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `作答请求无效` |  |
| SMOKE-107 | `GET /api/play/help` | player | 200/400 | EXPECTED | seeded activity has not started — `活动未开始` |  |
| SMOKE-108 | `GET /api/play/help/advanced/receipt` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `原局不存在或无权访问` |  |
| SMOKE-109 | `GET /api/play/help/receipt` | player | 200/400 | EXPECTED | seeded activity has not started — `活动未开始` |  |
| SMOKE-110 | `POST /api/play/help/skip` | player | 200/400 | EXPECTED | seeded activity has not started — `活动未开始` |  |
| SMOKE-111 | `POST /api/play/help/use` | player | 200/400 | EXPECTED | seeded activity has not started — `活动未开始` |  |
| SMOKE-112 | `POST /api/project/state-requests/context` | creator | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `参数无效` |  |
| SMOKE-113 | `POST /api/project/state-requests/read` | creator | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `读回参数无效` |  |
| SMOKE-114 | `POST /api/project/state-requests/submit` | creator | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `原始状态请求不符合协议` |  |
| SMOKE-115 | `POST /api/public-catalog/map` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `sort 枚举无效，支持值: [NEWEST, DURATION_ASC, DU` |  |
| SMOKE-116 | `POST /api/registration/create` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `请先补全参与人姓名，再购买` |  |
| SMOKE-117 | `POST /api/registration/explore-completion` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `参数不合规` |  |
| SMOKE-118 | `POST /api/reward-pledge/drafts` | merchant(9002) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `INVALID_INPUT` |  |
| SMOKE-119 | `POST /api/reward-pledge/fulfilment/redeem` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `NOT_CONFIRM` |  |
| SMOKE-120 | `GET /api/rewards/noncash` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `INVALID_INPUT` |  |
| SMOKE-121 | `POST /api/rewards/noncash/claims` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `INVALID_INPUT` |  |
| SMOKE-122 | `POST /api/team/original-request` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `INVALID_FIELD` |  |
| SMOKE-123 | `POST /api/template/adopt-draft` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `采用范围不规范` |  |
| SMOKE-124 | `GET /api/template/adoption-source` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `模板编号不规范` |  |
| SMOKE-125 | `POST /api/template/author-requests/submit` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `原始模板请求只支持个人草稿/发布` |  |
| SMOKE-126 | `POST /api/template/{}/market-submit` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `模板不存在或无权提交` |  |
| SMOKE-127 | `DELETE /api/topic/attribute-author` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `缺少原始请求标识` |  |
| SMOKE-128 | `POST /api/topic/attribute-author` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `缺少原始请求标识` |  |
| SMOKE-129 | `GET /api/topic/attribute-author/request` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `topicId invalid` |  |
| SMOKE-130 | `POST /api/topic/attribute-author/validate` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `属性请求无效` |  |
| SMOKE-131 | `POST /api/topic/cover/selection` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `封面选择请求无效` |  |
| SMOKE-132 | `POST /api/topic/cover/selection/status` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `封面选择请求无效` |  |
| SMOKE-133 | `POST /api/topic/edit-requests/read` | creator | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `编辑请求无效` |  |
| SMOKE-134 | `POST /api/topic/edit-requests/resume-post-processing` | creator | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `编辑请求无效` |  |
| SMOKE-135 | `POST /api/topic/edit-requests/submit` | creator | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `编辑请求无效` |  |
| SMOKE-136 | `PUT /api/topic/play-setup` | creator(9003) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `请选择可玩时段。` |  |
| SMOKE-137 | `POST /api/topic/publish-draft` | creator(9003) | 200/422 | HARNESS | request params missing/invalid; backend validation correct — `媒体来源未经审核，请重新上传` |  |
| SMOKE-138 | `POST /api/topic/state-keys/named` | creator(9003) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `请选择种类。` |  |
| SMOKE-139 | `PUT /api/topic/state-keys/{}` | creator(9003) | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `参数不合规` |  |
| SMOKE-140 | `POST /api/us/auth/apple/exchange` | player | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `US_APPLE_INVALID_REQUEST` |  |
| SMOKE-141 | `GET /api/user/deregister/result` | player2(9009) | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `结果凭证无效` |  |
| SMOKE-142 | `GET /api/user/follow-list` | player | 200/400 | HARNESS | request params missing/invalid; backend validation correct — `kind 无效` |  |
| SMOKE-143 | `POST /api/workshop/purchased/professional-operations/cancel` | club(9003) | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `licenseId invalid` |  |
| SMOKE-144 | `POST /api/workshop/purchased/professional-operations/history` | club(9003) | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `licenseId invalid` |  |
| SMOKE-145 | `POST /api/workshop/purchased/professional-operations/status` | club(9003) | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `requestId invalid` |  |
| SMOKE-146 | `POST /api/workshop/purchased/professional-operations/submit` | club(9003) | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `licenseId invalid` |  |
| SMOKE-147 | `POST /api/workshop/purchased/professional-targets/detail` | club(9003) | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `topicId invalid` |  |
| SMOKE-148 | `POST /api/workshop/purchased/professional-targets/list` | club(9003) | 400/400 | HARNESS | request params missing/invalid; backend validation correct — `licenseId invalid` |  |
| SMOKE-149 | `POST /api/club/personal-receiver/bind` | club(9003) | 200/500 | HARNESS | fixture app.wechat.appid not seeded — `PERSONAL_RECEIVER_APPID_MISMATCH` | R5 seed |
| SMOKE-150 | `POST /api/merchant/crm/broadcast` | merchant(9002) | 200/500 | DEFECT | audience_count null on broadcast finalize — `系统繁忙，请稍后重试` | fixed in R5 (MerchantCrmBroadcastServiceImpl) |
| SMOKE-151 | `POST /api/activity/cancel` | club(9003) | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `场次状态不完整或范围不一致` | errorKind=BUSINESS (R9) |
| SMOKE-152 | `POST /api/activity/delete` | club(9003) | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `活动不存在` | errorKind=BUSINESS (R9) |
| SMOKE-153 | `POST /api/activity/publish` | club(9003) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `{"msg":"媒体来源未经审核，请重新上传","code":500,"writ` | errorKind=BUSINESS (R9) |
| SMOKE-154 | `POST /api/activity/update` | club(9003) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `{"msg":"活动已售出，时间不能修改；文字、图片、音频仍可改","code"` | errorKind=BUSINESS (R9) |
| SMOKE-155 | `POST /api/cart/order/settlement` | player | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `收货地址不正确` | errorKind=BUSINESS (R9) |
| SMOKE-156 | `POST /api/circle-theme/instance/configure` | creator(9003) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `只有城市实例的内容主体本人可以配置供给角色` | errorKind=BUSINESS (R9) |
| SMOKE-157 | `POST /api/circle-theme/instance/review` | creator(9003) | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `圈层城市实例不存在` | errorKind=BUSINESS (R9) |
| SMOKE-158 | `GET /api/circle-theme/instance/{}/offers` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `请先购买或领取该主题` | errorKind=BUSINESS (R9) |
| SMOKE-159 | `POST /api/circle-theme/session` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `请先购买或领取该主题` | errorKind=BUSINESS (R9) |
| SMOKE-160 | `POST /api/circle-theme/session/answer` | player | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `缺少探索会话` | errorKind=BUSINESS (R9) |
| SMOKE-161 | `POST /api/circle-theme/session/join` | player | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `同行邀请码不存在或会话已结束` | errorKind=BUSINESS (R9) |
| SMOKE-162 | `POST /api/circle-theme/session/record` | player | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `缺少探索会话` | errorKind=BUSINESS (R9) |
| SMOKE-163 | `GET /api/circle-theme/session/{}/card` | player | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `探索会话不存在` | errorKind=BUSINESS (R9) |
| SMOKE-164 | `POST /api/club/badge/grant` | club(9003) | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `参数不完整` | errorKind=BUSINESS (R9) |
| SMOKE-165 | `POST /api/club/dissolve` | club(9003) | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `俱乐部不存在` | errorKind=BUSINESS (R9) |
| SMOKE-166 | `POST /api/club/join-request/approve` | club(9003) | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `入会申请不存在或已处理` | errorKind=BUSINESS (R9) |
| SMOKE-167 | `POST /api/club/join-request/reject` | club(9003) | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `入会申请不存在或已处理` | errorKind=BUSINESS (R9) |
| SMOKE-168 | `POST /api/club/lead/arrive` | player(9004) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `集合前60分钟才可签到` | errorKind=BUSINESS (R9) |
| SMOKE-169 | `POST /api/club/lead/edit-ops` | club(9003) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `无可改运营字段` | errorKind=BUSINESS (R9) |
| SMOKE-170 | `POST /api/club/lead/release-station` | club(9003) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `该成员不在本场` | errorKind=BUSINESS (R9) |
| SMOKE-171 | `POST /api/club/quit` | club(9003) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `主理人不能直接退出；请先在成员治理中转让主理人，或解散俱乐部` | errorKind=BUSINESS (R9) |
| SMOKE-172 | `POST /api/club/remove-member` | club(9003) | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `俱乐部不存在` | errorKind=BUSINESS (R9) |
| SMOKE-173 | `POST /api/coop/complaint/report` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `你对该主题已有处理中的投诉` | errorKind=BUSINESS (R9) |
| SMOKE-174 | `POST /api/coop/deposit/refund/retry` | merchant(9002) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `仅取消合作或履约完成后可重试退款` | errorKind=BUSINESS (R9) |
| SMOKE-175 | `POST /api/coop/invite` | creator(9003) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `邀请类型与受邀方类型不匹配` | errorKind=BUSINESS (R9) |
| SMOKE-176 | `POST /api/coop/review/summary` | player | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `缺少被评人` | errorKind=BUSINESS (R9) |
| SMOKE-177 | `POST /api/game/session/command` | player | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `游戏命令参数不完整` | errorKind=BUSINESS (R9) |
| SMOKE-178 | `GET /api/game/session/recap/export` | club(9003) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `复盘数据尚未生成` | errorKind=BUSINESS (R9) |
| SMOKE-179 | `GET /api/game/session/shared/export` | club(9003) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `本局没有共享账` | errorKind=BUSINESS (R9) |
| SMOKE-180 | `POST /api/game/session/shared/export/anchor` | club(9003) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `本局没有共享账` | errorKind=BUSINESS (R9) |
| SMOKE-181 | `POST /api/game/session/shared/preview` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `本局没有共享任务` | errorKind=BUSINESS (R9) |
| SMOKE-182 | `POST /api/invoice/apply` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `原订单现金或退款状态无法确认，暂不可开票` | errorKind=BUSINESS (R9) |
| SMOKE-183 | `POST /api/invoice/cancel` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `该申请已处理，不能撤回` | errorKind=BUSINESS (R9) |
| SMOKE-184 | `POST /api/invoice/request` | player | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `暂未查到原申请` | errorKind=BUSINESS (R9) |
| SMOKE-185 | `POST /api/login` | public | 200/500 | EXPECTED | environment: provider not configured on TEST — `微信服务暂时不可用，请稍后重试` | errorKind=BUSINESS (R9) |
| SMOKE-186 | `POST /api/login/apple` | public | 200/500 | EXPECTED | environment: provider not configured on TEST — `Apple登录未配置` | errorKind=BUSINESS (R9) |
| SMOKE-187 | `POST /api/login/wechat/app` | public | 200/500 | EXPECTED | environment: provider not configured on TEST — `原生微信未配置` | errorKind=BUSINESS (R9) |
| SMOKE-188 | `GET /api/media/asset/{}` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `无权读取该媒体` | errorKind=BUSINESS (R9) |
| SMOKE-189 | `POST /api/merchant/chapter-application/apply` | merchant(9002) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `该章节未开放商家承接` | errorKind=BUSINESS (R9) |
| SMOKE-190 | `POST /api/merchant/chapter-application/audit` | merchant(9002) | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `缺少审核结果` | errorKind=BUSINESS (R9) |
| SMOKE-191 | `POST /api/merchant/chapter-application/invite` | merchant(9002) | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `缺少章节` | errorKind=BUSINESS (R9) |
| SMOKE-192 | `POST /api/merchant/chapter-application/invite/respond` | merchant(9002) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `请选择邀请及处理方式` | errorKind=BUSINESS (R9) |
| SMOKE-193 | `POST /api/merchant/chapter-application/invite/revoke` | merchant(9002) | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `邀请不存在` | errorKind=BUSINESS (R9) |
| SMOKE-194 | `POST /api/merchant/chapter-application/withdraw` | merchant(9002) | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `申请不存在` | errorKind=BUSINESS (R9) |
| SMOKE-195 | `POST /api/merchant/chapter-node/npc/voice/enroll` | merchant(9002) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `请先单独同意声音克隆处理` | errorKind=BUSINESS (R9) |
| SMOKE-196 | `POST /api/merchant/chapter-node/poster-code` | merchant(9002) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `该点位还没有打卡码，请联系主办方` | errorKind=BUSINESS (R9) |
| SMOKE-197 | `POST /api/merchant/chapter-node/submit` | merchant(9002) | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `缺少章节` | errorKind=BUSINESS (R9) |
| SMOKE-198 | `POST /api/merchant/city-node/offline` | merchant(9002) | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `据点不存在或无权操作` | errorKind=BUSINESS (R9) |
| SMOKE-199 | `POST /api/merchant/city-node/save` | merchant(9002) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `已上线据点不能复用投放申请入口` | errorKind=BUSINESS (R9) |
| SMOKE-200 | `POST /api/merchant/city-node/template/submit` | merchant(9002) | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `据点模板不存在或无权修改` | errorKind=BUSINESS (R9) |
| SMOKE-201 | `POST /api/merchant/commerce/order` | merchant(9002) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `该权益尚未配置价格，请联系官方` | errorKind=BUSINESS (R9) |
| SMOKE-202 | `POST /api/merchant/commerce/order/status` | merchant(9002) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `无权查看该订单` | errorKind=BUSINESS (R9) |
| SMOKE-203 | `POST /api/merchant/finance/public-transfer-batch-detail` | merchant(9002) | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `记录不可见` | errorKind=BUSINESS (R9) |
| SMOKE-204 | `POST /api/merchant/finance/redemption-detail` | merchant(9002) | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `记录不可见` | errorKind=BUSINESS (R9) |
| SMOKE-205 | `POST /api/merchant/merchant_registration` | merchant(9002) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `已提交过商家入驻申请，请勿重复提交` | errorKind=BUSINESS (R9) |
| SMOKE-206 | `POST /api/merchant/npc/voice/enroll` | merchant(9002) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `请先单独同意声音克隆处理` | errorKind=BUSINESS (R9) |
| SMOKE-207 | `POST /api/merchant/predict/settle` | merchant(9002) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `这个点位不是预测玩法` | errorKind=BUSINESS (R9) |
| SMOKE-208 | `POST /api/merchant/shop/apply` | merchant(9002) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `已提交过商家入驻申请，请勿重复提交` | errorKind=BUSINESS (R9) |
| SMOKE-209 | `POST /api/merchant/update` | merchant(9002) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `没有可更新的资料` | errorKind=BUSINESS (R9) |
| SMOKE-210 | `POST /api/official/events/{}/complete` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `请完成对应任务后自动验证；旧版活动请联系主办方处理` | errorKind=BUSINESS (R9) |
| SMOKE-211 | `POST /api/official/events/{}/signup` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `活动当前不可报名` | errorKind=BUSINESS (R9) |
| SMOKE-212 | `GET /api/official/v2/events/{}/publish-check` | ops(9001) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `无权查看该活动发布检查` | errorKind=BUSINESS (R9) |
| SMOKE-213 | `POST /api/official/v2/organizer-invites/{}/{}` | ops(9001) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `该邀约不是可拒绝的官方主办关系` | errorKind=BUSINESS (R9) |
| SMOKE-214 | `POST /api/orient/scoring/appeal` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `已超过申诉期限` | errorKind=BUSINESS (R9) |
| SMOKE-215 | `POST /api/orient/scoring/dispute` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `已超过成绩争议期限` | errorKind=BUSINESS (R9) |
| SMOKE-216 | `POST /api/orient/task/alternative` | creator(9003) | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `任务不存在` | errorKind=BUSINESS (R9) |
| SMOKE-217 | `GET /api/orient/task/alternative/receipt` | player | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `任务不存在` | errorKind=BUSINESS (R9) |
| SMOKE-218 | `POST /api/orient/task/alternative/submit` | player | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `缺少或非法的幂等键(idempotencyKey,8-64 位)` | errorKind=BUSINESS (R9) |
| SMOKE-219 | `GET /api/orient/task/state` | player | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `任务不存在` | errorKind=BUSINESS (R9) |
| SMOKE-220 | `GET /api/play/advanced/leaderboard` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `活动未开始` | errorKind=BUSINESS (R9) |
| SMOKE-221 | `POST /api/play/advanced/start` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `活动未开始` | errorKind=BUSINESS (R9) |
| SMOKE-222 | `GET /api/play/advanced/state` | player | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `玩法会话不存在或无权访问` | errorKind=BUSINESS (R9) |
| SMOKE-223 | `GET /api/play/content-manifest` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `活动未开始` | errorKind=BUSINESS (R9) |
| SMOKE-224 | `GET /api/play/encounter` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `本局状态不可用` | errorKind=BUSINESS (R9) |
| SMOKE-225 | `POST /api/play/hint/unlock` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `活动未开始` | errorKind=BUSINESS (R9) |
| SMOKE-226 | `POST /api/play/journey/ask` | player | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `参数有误` | errorKind=BUSINESS (R9) |
| SMOKE-227 | `GET /api/play/journey/casebook` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `本局状态不可用` | errorKind=BUSINESS (R9) |
| SMOKE-228 | `GET /api/play/journey/ending` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `本局状态不可用` | errorKind=BUSINESS (R9) |
| SMOKE-229 | `POST /api/play/journey/opening/complete` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `开局流程不匹配` | errorKind=BUSINESS (R9) |
| SMOKE-230 | `GET /api/play/journey/stage-end` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `本主题未配置剧情` | errorKind=BUSINESS (R9) |
| SMOKE-231 | `POST /api/play/journey/thought/sync` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `请先购买自玩通行证` | errorKind=BUSINESS (R9) |
| SMOKE-232 | `GET /api/play/nodes` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `路线局状态不可用` | errorKind=BUSINESS (R9) |
| SMOKE-233 | `GET /api/play/preference/{}` | player | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `节点不存在` | errorKind=BUSINESS (R9) |
| SMOKE-234 | `POST /api/play/preference/{}/submit` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `{"msg":"必须提供动作标识、期望版本和原运行时身份","retryable` | errorKind=BUSINESS (R9) |
| SMOKE-235 | `POST /api/play/puzzle/hint` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `活动未开始` | errorKind=BUSINESS (R9) |
| SMOKE-236 | `GET /api/play/route-state` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `路线局状态不可用` | errorKind=BUSINESS (R9) |
| SMOKE-237 | `POST /api/play/scan-entry` | player | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `{"msg":"无效的打卡码","retryable":false,"code"` | errorKind=BUSINESS (R9) |
| SMOKE-238 | `POST /api/play/sensor-result` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `{"msg":"必须提供动作标识、期望版本和原运行时身份","retryable` | errorKind=BUSINESS (R9) |
| SMOKE-239 | `POST /api/play/tag/{}/confirm` | player | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `标签不存在或已撤回` | errorKind=BUSINESS (R9) |
| SMOKE-240 | `POST /api/play/tag/{}/correct` | player | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `标签不存在` | errorKind=BUSINESS (R9) |
| SMOKE-241 | `POST /api/play/tag/{}/revoke` | player | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `标签不存在` | errorKind=BUSINESS (R9) |
| SMOKE-242 | `POST /api/play/theme/action` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `此动作不接受任选费用组` | errorKind=BUSINESS (R9) |
| SMOKE-243 | `GET /api/play/theme/card` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `本局尚未结束` | errorKind=BUSINESS (R9) |
| SMOKE-244 | `POST /api/play/theme/gameplay-action` | player | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `动作参数不正确` | errorKind=BUSINESS (R9) |
| SMOKE-245 | `POST /api/play/theme/replay` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `此主题未开放重玩` | errorKind=BUSINESS (R9) |
| SMOKE-246 | `GET /api/play/theme/state` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `旅程数值规则版本不支持` | errorKind=BUSINESS (R9) |
| SMOKE-247 | `POST /api/registration/cancel` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `该报名状态已变化,无法取消` | errorKind=BUSINESS (R9) |
| SMOKE-248 | `POST /api/registration/cancel-by-owner` | club(9003) | 200/500 | HARNESS | seeded ticket inventory already full; guard correctly refuses — `票务库存回补失败，请刷新后重试` | already coded 409 in service |
| SMOKE-249 | `POST /api/registration/cancel-refund` | player | 200/500 | HARNESS | seeded ticket inventory already full; guard correctly refuses — `票务库存回补失败，请刷新后重试` | already coded 409 in service |
| SMOKE-250 | `POST /api/registration/quote` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `主题活动已下架` | errorKind=BUSINESS (R9) |
| SMOKE-251 | `POST /api/registration/scan_dynamic_code` | player | 200/500 | HARNESS | malformed scan code sent by harness — `格式错误` | errorKind=BUSINESS (R9); code 500 kept (app keys not-redeemed on it) |
| SMOKE-252 | `POST /api/registration/scan_group_member_ticket` | player | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `核销码无效` | errorKind=BUSINESS (R9) |
| SMOKE-253 | `POST /api/registration/scan_qr_code` | player | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `核销码无效` | errorKind=BUSINESS (R9) |
| SMOKE-254 | `POST /api/roam/session/records` | player | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `采集记录结构无效` | errorKind=BUSINESS (R9) |
| SMOKE-255 | `GET /api/roam/share/snapshot` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `这条足迹已不再公开` | errorKind=BUSINESS (R9) |
| SMOKE-256 | `POST /api/roam/share/snapshot` | player | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `足迹数据无效` | errorKind=BUSINESS (R9) |
| SMOKE-257 | `POST /api/team/handle` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `申请已处理或已失效` | errorKind=BUSINESS (R9) |
| SMOKE-258 | `POST /api/team/kick` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `成员不在队伍中` | errorKind=BUSINESS (R9) |
| SMOKE-259 | `POST /api/team/quit` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `你不在该队伍中` | errorKind=BUSINESS (R9) |
| SMOKE-260 | `POST /api/team/withdraw` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `申请已处理或已失效` | errorKind=BUSINESS (R9) |
| SMOKE-261 | `POST /api/template/topic-template/save-as` | creator(9003) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `来源修订与当前发布快照不一致` | errorKind=BUSINESS (R9) |
| SMOKE-262 | `POST /api/template/topic-template/use` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `{"msg":"该主题不是可用模板","code":500,"writeRece` | errorKind=BUSINESS (R9) |
| SMOKE-263 | `POST /api/template/updateLibraryStatus` | creator(9003) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `上架复检暂不可用，请刷新后重试` | errorKind=BUSINESS (R9) |
| SMOKE-264 | `POST /api/topic/beta/graduate` | creator(9003) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `该主题不在 Beta 试玩期` | errorKind=BUSINESS (R9) |
| SMOKE-265 | `POST /api/topic/cancel` | creator(9003) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `俱乐部主题请在俱乐部「主题设置 → 结束主题」里取消` | errorKind=BUSINESS (R9) |
| SMOKE-266 | `POST /api/topic/cancel_preview` | creator(9003) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `俱乐部主题请在俱乐部「主题设置 → 结束主题」里取消` | errorKind=BUSINESS (R9) |
| SMOKE-267 | `POST /api/topic/chapter/finish` | creator(9003) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `本章已经结束过了` | errorKind=BUSINESS (R9) |
| SMOKE-268 | `POST /api/topic/chapter/recruit` | creator(9003) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `本章已结束，不能再改商家承接` | errorKind=BUSINESS (R9) |
| SMOKE-269 | `POST /api/topic/chapter/supply-terms` | creator(9003) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `主题已开售，这一站的档位已冻结` | errorKind=BUSINESS (R9) |
| SMOKE-270 | `POST /api/topic/create` | creator | 200/500 | EXPECTED | business rule/state refusal coded 500 — `请上传主题封面` | errorKind=BUSINESS (R9) |
| SMOKE-271 | `POST /api/topic/delete` | creator(9003) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `该主题的场次仍有待支付或已支付报名，请先处理场次报名，再删除主题` | errorKind=BUSINESS (R9) |
| SMOKE-272 | `POST /api/topic/merchant-recruitment-chapters` | merchant(9002) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `仅自由探索主题支持章节承接` | errorKind=BUSINESS (R9) |
| SMOKE-273 | `POST /api/topic/pricing/confirm` | creator(9003) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `主题未在定价阶段,不能确认终价` | errorKind=BUSINESS (R9) |
| SMOKE-274 | `POST /api/topic/report` | player | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `主题不存在` | errorKind=BUSINESS (R9) |
| SMOKE-275 | `DELETE /api/topic/state-keys` | creator(9003) | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `状态键不存在` | errorKind=BUSINESS (R9) |
| SMOKE-276 | `POST /api/topic/transfer-to-club` | creator(9003) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `来源修订与当前发布快照不一致` | errorKind=BUSINESS (R9) |
| SMOKE-277 | `POST /api/topic/v2/create` | creator(9003) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `请上传主题封面` | errorKind=BUSINESS (R9) |
| SMOKE-278 | `POST /api/user/address/delete` | player | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `地址不存在或不可用` | errorKind=BUSINESS (R9) |
| SMOKE-279 | `POST /api/user/address/info` | player | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `地址不存在或不可用` | errorKind=BUSINESS (R9) |
| SMOKE-280 | `POST /api/user/address/setDefault` | player | 200/500 | HARNESS | random/unseeded id or incomplete params; refusal coded 500 — `地址不存在或不可用` | errorKind=BUSINESS (R9) |
| SMOKE-281 | `POST /api/user/deregister/apply` | player2(9009) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `请先同意账号注销须知` | errorKind=BUSINESS (R9) |
| SMOKE-282 | `POST /api/user/deregister/cancel` | player2(9009) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `当前没有可撤销的注销申请` | errorKind=BUSINESS (R9) |
| SMOKE-283 | `POST /api/user/setInviter` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `邀请关系设置失败` | errorKind=BUSINESS (R9) |
| SMOKE-284 | `POST /api/verify/citynode/receipt` | merchant(9002) | 200/500 | HARNESS | malformed scan code sent by harness — `格式错误` | errorKind=BUSINESS (R9); code 500 kept (app keys not-redeemed on it) |
| SMOKE-285 | `POST /api/verify/citynode/redeem` | merchant(9002) | 200/500 | HARNESS | malformed scan code sent by harness — `格式错误` | errorKind=BUSINESS (R9); code 500 kept (app keys not-redeemed on it) |
| SMOKE-286 | `POST /api/verify/dyncode/issue` | merchant(9002) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `无权操作该报名单` | errorKind=BUSINESS (R9) |
| SMOKE-287 | `POST /api/verify/groupcode/issue` | merchant(9002) | 200/500 | EXPECTED | business rule/state refusal coded 500 — `您没有该场次的团码核销权限` | errorKind=BUSINESS (R9) |
| SMOKE-288 | `POST /api/verify/groupcode/redeem` | merchant(9002) | 200/500 | HARNESS | malformed scan code sent by harness — `格式错误` | errorKind=BUSINESS (R9); code 500 kept (app keys not-redeemed on it) |
| SMOKE-289 | `POST /api/video-bounty/submission` | player | 200/500 | EXPECTED | business rule/state refusal coded 500 — `不支持的平台` | errorKind=BUSINESS (R9) |
| SMOKE-290 | `POST /api/ai/npc/voice-chat` | player | 200/500 | EXPECTED | no speech-to-text provider: honest "not connected" refusal — `语音识别还没接入，先打字问我` | R5 e2e-test STT provider for TEST |
| SMOKE-291 | `POST /api/merchant/share-code` | merchant(9002) | 200/500 | HARNESS | environment: TEST has no WeChat QR credentials — `个人码生成服务繁忙，请稍后重试` | R5 e2e-test WxCode provider |
| SMOKE-292 | `POST /api/topic/host-qrcode` | creator(9003) | 200/500 | HARNESS | environment: TEST has no WeChat QR credentials — `个人码生成服务繁忙，请稍后重试` | R5 e2e-test WxCode provider |
| SMOKE-293 | `POST /api/topic/share-code` | player | 200/500 | HARNESS | environment: TEST has no WeChat QR credentials — `个人码生成服务繁忙，请稍后重试` | R5 e2e-test WxCode provider |
| SMOKE-294 | `POST /api/user/player-code` | player | 200/500 | HARNESS | environment: TEST has no WeChat QR credentials — `个人码生成服务繁忙，请稍后重试` | R5 e2e-test WxCode provider |
| SMOKE-295 | `GET /api/club/lead/unlock-chapter-receipt` | club(9003) | 200/520 | EXPECTED | command outcome pending by design (retryable receipt) — `章节命令结果待确认` |  |
| SMOKE-296 | `GET /api/play/action-receipt` | player | 200/520 | EXPECTED | command outcome pending by design (retryable receipt) — `{"msg":"原命令结果待确认，请稍后查询","retryable":true` |  |
| SMOKE-297 | `GET /api/channels/member-state-requests` | club(9003) | 404/404 | HARNESS | channel fixture missing / wrong overwrite payload — `频道不存在` |  |
| SMOKE-298 | `GET /api/channels/{}/op-log` | club(9003) | 404/404 | HARNESS | channel fixture missing / wrong overwrite payload — `频道不存在` |  |
| SMOKE-299 | `PUT /api/channels/{}/permissions/{}/{}` | club(9003) | 400/400 | HARNESS | channel fixture missing / wrong overwrite payload — `空间级权限不能放进频道覆盖` |  |
| SMOKE-300 | `POST /api/club/event-notification/audience-counts` | club(9003) | 200/400 | HARNESS | direct curl with valid params answers 200 (matrix refresh 5) — `{"msg":"俱乐部身份无效或权限不足","code":400} \` |  |
| SMOKE-301 | `POST /api/club/events` | club(9003) | 200/400 | HARNESS | direct curl with valid params answers 200 (matrix refresh 5) — `{"msg":"缺少俱乐部参数","code":400} \` |  |
| SMOKE-302 | `POST /api/club/notices` | club(9003) | 200/400 | HARNESS | direct curl with valid params answers 200 (matrix refresh 5) — `{"msg":"俱乐部身份无效或权限不足","code":400} \` |  |
| SMOKE-303 | `GET /api/compliance/usage-records` | player | 200/400 | HARNESS | direct curl with valid params answers 200 (matrix refresh 5) — `{"msg":"beforeId 无效","code":400} \` |  |
| SMOKE-304 | `POST /api/merchant/crm/campaigns/preview` | merchant(9002) | 200/500 | DEFECT | one damaged saved segment made the whole list fail (seeded row) — `{"msg":"保存分群条件已损坏","code":500} \` | fixed in R9: list skips + logs damaged row; preview of that segment still refuses |
| SMOKE-305 | `POST /api/merchant/crm/exports` | merchant(9002) | 200/400 | HARNESS | direct curl with valid params answers 200 (matrix refresh 5) — `{"msg":"请求标识无效","code":400} \` |  |
| SMOKE-306 | `GET /api/merchant/crm/segments` | merchant(9002) | 200/500 | DEFECT | one damaged saved segment made the whole list fail (seeded row) — `{"msg":"分群条件损坏","code":500} \` | fixed in R9: list skips + logs damaged row; preview of that segment still refuses |
| SMOKE-307 | `POST /api/merchant/crm/segments` | merchant(9002) | 200/400 | HARNESS | direct curl with valid params answers 200 (matrix refresh 5) — `{"msg":"请填写分群名称","code":400} \` |  |
| SMOKE-308 | `POST /api/public-catalog/info` | player | 200/404 | HARNESS | direct curl with valid params answers 200 (matrix refresh 5) — `{"msg":"作品不存在或未公开","code":404} \` |  |
| SMOKE-309 | `GET /api/roam/passport/history` | player | 200/400 | HARNESS | direct curl with valid params answers 200 (matrix refresh 5) — `{"msg":"记录编号无效","code":400} \` |  |
| SMOKE-310 | `POST /api/topic/info-to-user` | player | 200/404 | HARNESS | direct curl with valid params answers 200 (matrix refresh 5) — `{"msg":"主题活动不存在","code":404} \` |  |
| SMOKE-311 | `POST /api/approved-topic-release/v1/review/status` | club(9003) | 404/404 | HARNESS | random/unseeded id; 404 correct — `没有该送审请求` |  |
| SMOKE-312 | `GET /api/city-game/acquisition/quote/{}` | player | 404/404 | HARNESS | random/unseeded id; 404 correct — `报价不存在` |  |
| SMOKE-313 | `GET /api/city-game/acquisition/request/{}` | player | 404/404 | HARNESS | random/unseeded id; 404 correct — `没有这条请求的回执` |  |
| SMOKE-314 | `GET /api/city-game/action-budget/request/{}` | player | 404/404 | HARNESS | random/unseeded id; 404 correct — `没有这条请求的回执` |  |
| SMOKE-315 | `GET /api/city-game/arrival-evidence/evidence/{}` | player | 404/404 | HARNESS | random/unseeded id; 404 correct — `到店凭证不存在` |  |
| SMOKE-316 | `GET /api/city-game/arrival-evidence/request/{}` | player | 404/404 | HARNESS | random/unseeded id; 404 correct — `没有这条请求的回执` |  |
| SMOKE-317 | `GET /api/city-game/game/action/{}` | player | 404/404 | HARNESS | random/unseeded id; 404 correct — `没有这条请求的回执` |  |
| SMOKE-318 | `GET /api/city-game/merchant/operations/request/{}` | merchant | 404/404 | HARNESS | random/unseeded id; 404 correct — `没有这条请求的回执` |  |
| SMOKE-319 | `GET /api/city-game/neutral-tasks/request/{}` | player | 404/404 | HARNESS | random/unseeded id; 404 correct — `没有这条请求的回执` |  |
| SMOKE-320 | `GET /api/city-game/onsite-tasks/request/{}` | player | 404/404 | HARNESS | random/unseeded id; 404 correct — `没有这条请求的回执` |  |
| SMOKE-321 | `GET /api/city-game/onsite-tasks/result/{}` | player | 404/404 | HARNESS | random/unseeded id; 404 correct — `结果不存在` |  |
| SMOKE-322 | `GET /api/city-game/team-participation/request/{}` | player | 404/404 | HARNESS | random/unseeded id; 404 correct — `没有这条请求的回执` |  |
| SMOKE-323 | `GET /api/city-game/{}/request/{}` | player | 404/404 | HARNESS | random/unseeded id; 404 correct — `没有这条请求的回执` |  |
| SMOKE-324 | `POST /api/city-value/visits/feedback` | player | 404/404 | HARNESS | random/unseeded id; 404 correct — `到访不存在` |  |
| SMOKE-325 | `POST /api/club/post/create` | club(9003) | 200/404 | HARNESS | random/unseeded id; 404 correct — `引用的记录不存在或当前不可公开` |  |
| SMOKE-326 | `POST /api/content-draft/publish` | creator | 200/404 | HARNESS | random/unseeded id; 404 correct — `草稿不存在` |  |
| SMOKE-327 | `POST /api/content-draft/restore` | creator | 200/404 | HARNESS | random/unseeded id; 404 correct — `草稿不存在或已结束` |  |
| SMOKE-328 | `GET /api/exploration-goals/receipt` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `尚未查到原请求，请保留编号重试` |  |
| SMOKE-329 | `GET /api/friend/metadata/receipt` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `FRIEND_RECEIPT_NOT_FOUND` |  |
| SMOKE-330 | `POST /api/friend/metadata/settle` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `FRIEND_RECEIPT_NOT_FOUND` |  |
| SMOKE-331 | `GET /api/friend/receipt` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `FRIEND_RECEIPT_NOT_FOUND` |  |
| SMOKE-332 | `POST /api/friend/reject` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `FRIEND_REQUEST_NOT_FOUND` |  |
| SMOKE-333 | `GET /api/friend/removal/receipt` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `FRIEND_RECEIPT_NOT_FOUND` |  |
| SMOKE-334 | `POST /api/friend/removal/settle` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `FRIEND_RECEIPT_NOT_FOUND` |  |
| SMOKE-335 | `POST /api/host-tip/intent/{}/cancel` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `小费记录不存在` |  |
| SMOKE-336 | `POST /api/host-tip/prepare` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `该主持尚未开通小费` |  |
| SMOKE-337 | `POST /api/im/moderation/kick` | club(9003) | 404/404 | HARNESS | random/unseeded id; 404 correct — `对方不是空间成员` |  |
| SMOKE-338 | `POST /api/im/moderation/{}/revoke` | club(9003) | 404/404 | HARNESS | random/unseeded id; 404 correct — `频道不存在` |  |
| SMOKE-339 | `POST /api/im/poll/close` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `群聊不存在或已结束` |  |
| SMOKE-340 | `POST /api/im/poll/create` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `群聊不存在或已结束` |  |
| SMOKE-341 | `POST /api/im/poll/vote` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `群聊不存在或已结束` |  |
| SMOKE-342 | `POST /api/im/route-share-source` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `该路线暂不可用` |  |
| SMOKE-343 | `POST /api/marketing/group-checkout/quote` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `商品不存在` |  |
| SMOKE-344 | `POST /api/marketing/product/activate` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `没有待激活的通行证` |  |
| SMOKE-345 | `POST /api/marketing/product/order` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `商品不存在` |  |
| SMOKE-346 | `POST /api/marketing/product/quote` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `商品不存在` |  |
| SMOKE-347 | `POST /api/marketing/referral/token` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `推广目标不存在或未上架` |  |
| SMOKE-348 | `POST /api/merchant/authoring/jobs/cancel` | merchant(9002) | 404/404 | HARNESS | random/unseeded id; 404 correct — `NOT_FOUND` |  |
| SMOKE-349 | `GET /api/merchant/authoring/player/{}/requests/{}` | merchant(9002) | 404/404 | HARNESS | random/unseeded id; 404 correct — `没有这条原请求` |  |
| SMOKE-350 | `POST /api/merchant/crm/customers/{}/tags/remove` | merchant(9002) | 200/404 | HARNESS | random/unseeded id; 404 correct — `标签不存在或不属于当前客户` |  |
| SMOKE-351 | `GET /api/merchant/crm/exports/{}/download` | merchant(9002) | 404/404 | HARNESS | random/unseeded id; 404 correct — `导出任务不存在或不属于当前商家` |  |
| SMOKE-352 | `POST /api/merchant/crm/exports/{}/status` | merchant(9002) | 200/404 | HARNESS | random/unseeded id; 404 correct — `导出任务不存在或不属于当前商家` |  |
| SMOKE-353 | `GET /api/merchant/npc/knowledge-review/request` | merchant(9002) | 404/404 | HARNESS | random/unseeded id; 404 correct — `没有这条送审请求` |  |
| SMOKE-354 | `POST /api/merchant/reviews/report` | merchant(9002) | 200/404 | HARNESS | random/unseeded id; 404 correct — `评价不存在或无权访问` |  |
| SMOKE-355 | `GET /api/native/home/return/account` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `HOME_RUN_NOT_FOUND` |  |
| SMOKE-356 | `GET /api/play/advanced/receipt` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `玩法会话不存在或无权访问` |  |
| SMOKE-357 | `GET /api/play/route-action` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `没有这条路线动作` |  |
| SMOKE-358 | `GET /api/play/seat-invitations/{}` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `邀请不存在` |  |
| SMOKE-359 | `POST /api/play/seat-invitations/{}/cancel` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `邀请不存在` |  |
| SMOKE-360 | `GET /api/progress/requests` | player | 404/404 | HARNESS | random/unseeded id; 404 correct — `没有找到该请求` |  |
| SMOKE-361 | `GET /api/progress/requests/{}` | player | 404/404 | HARNESS | random/unseeded id; 404 correct — `没有找到该请求` |  |
| SMOKE-362 | `GET /api/reward-pledge/versions` | merchant(9002) | 200/404 | HARNESS | random/unseeded id; 404 correct — `NOT_FOUND` |  |
| SMOKE-363 | `POST /api/rewards/noncash/cases` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `NOT_FOUND` |  |
| SMOKE-364 | `POST /api/rewards/noncash/claims/recover` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `NOT_FOUND` |  |
| SMOKE-365 | `GET /api/rewards/noncash/subjects` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `NOT_FOUND` |  |
| SMOKE-366 | `GET /api/rewards/noncash/versions` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `NOT_FOUND` |  |
| SMOKE-367 | `POST /api/template/topic-template/info` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `模板不存在` |  |
| SMOKE-368 | `GET /api/topic/cover/{}/content` | player | 404/404 | HARNESS | random/unseeded id; 404 correct — `封面不存在` |  |
| SMOKE-369 | `DELETE /api/topic/state-keys/named` | creator(9003) | 200/404 | HARNESS | random/unseeded id; 404 correct — `这一项不存在。` |  |
| SMOKE-370 | `POST /api/v1/community/appeals` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `处置记录不存在` |  |
| SMOKE-371 | `POST /api/v1/community/reports` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `帖文不存在或不可见` |  |
| SMOKE-372 | `POST /api/workshop/v2/purchased/installed-text/detail` | player | 200/404 | HARNESS | random/unseeded id; 404 correct — `INSTALL_NOT_FOUND` |  |
| SMOKE-373 | `POST /api/ai/npc/chat` | player | 200/403 | EXPECTED | NPC free follow-up switched off — `NPC 自由追问未开放` |  |
| SMOKE-374 | `GET /api/audit/guard/reference` | creator | 200/403 | HARNESS | caller lacks the seat/guard fixture — `没有本人对应的守卫记录` |  |
| SMOKE-375 | `POST /api/verify/seat/receipt` | merchant(9002) | 200/403 | HARNESS | caller lacks the seat/guard fixture — `必须明确原订单中的合法席位，不能用整单码代替多人核销` |  |
| SMOKE-376 | `POST /api/friend/channel-invite` | player | 404/404 | HARNESS | retired route still in the catalog — `请求路径不存在` | R5 removed from catalog |
| SMOKE-377 | `POST /api/merchant/payout-account/save` | merchant(9002) | 404/404 | HARNESS | retired route still in the catalog — `请求路径不存在` | R5 removed from catalog |
| SMOKE-378 | `GET /api/workshop/purchased` | player | 404/404 | HARNESS | retired route still in the catalog — `请求路径不存在` | R5 removed from catalog |
| SMOKE-379 | `POST /api/im/file/upload` | player | 500/500 | HARNESS | environment: TEST had no media object store — `结果待确认，请稍后查询原请求` | R5 e2e-test IM media store |
| SMOKE-380 | `GET /api/im/video/message/{}/preview` | player | 500/500 | HARNESS | environment: TEST had no media object store — `结果待确认，请稍后查询原请求` | R5 e2e-test IM media store |
| SMOKE-381 | `POST /api/im/video/upload` | player | 500/500 | HARNESS | environment: TEST had no media object store — `结果待确认，请稍后查询原请求` | R5 e2e-test IM media store |
| SMOKE-382 | `GET /api/im/voice/message/{}` | player | 500/500 | HARNESS | environment: TEST had no media object store — `结果待确认，请稍后查询原请求` | R5 e2e-test IM media store |
| SMOKE-383 | `POST /api/im/voice/upload` | player | 500/500 | HARNESS | environment: TEST had no media object store — `结果待确认，请稍后查询原请求` | R5 e2e-test IM media store |
| SMOKE-384 | `POST /api/club/event-notification/preview` | club(9003) | 200/501 | EXPECTED | WeChat subscription templates not configured: honest 501 — `WECHAT_SUBSCRIPTION_UNAVAILABLE: 订阅模板或授权` |  |
| SMOKE-385 | `POST /api/club/event-notification/send` | club(9003) | 200/501 | EXPECTED | WeChat subscription templates not configured: honest 501 — `WECHAT_SUBSCRIPTION_UNAVAILABLE: 订阅模板或授权` |  |
| SMOKE-386 | `POST /api/club/notices/send` | club(9003) | 200/501 | EXPECTED | WeChat subscription templates not configured: honest 501 — `WECHAT_SUBSCRIPTION_UNAVAILABLE: 订阅模板或授权` |  |
| SMOKE-387 | `GET /api/im/video/message/{}/authorization` | player | 200/200 | HARNESS | shape_validator false positive — `{"msg":"操作成功","code":200,"data":{"source` | R5 validator fix |
