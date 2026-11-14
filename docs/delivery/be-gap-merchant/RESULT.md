# feature/be-gap-merchant — result (2026-11-14)

The 29 `merchant` rows of the app's true-missing list (`docs/release/backend-true-missing-85.tsv` at app `9e40fe69a`: 26 routes + 3 prefix families) now have a backend.
Branch `feature/be-gap-merchant`, cut from `origin/integration/backend-app-r2` `f89005866`. Production was not touched, hengyin `master`/`main` was not pushed, and TEST 8120 was not touched.
Checked on VPS `work`: worktree `/data/workspace/be-gap-merchant`, port **8127** (stopped after the checks), DB **`cy_gap_merchant`**, Redis `be-int-redis` db 7. Logs and scripts are in `/data/workspace/be-gap-merchant-out/`.

## 0. Starting point

* `origin/feature/be-gap-merchant` did not exist (`git fetch`; no Cursor push of this package under `feature/be-gap-*`).
* The app's authoring client names `ApiMerchantAuthoringController` on `cursor/w05-j-merchant-authoring` (`329ffb046`, 2026-10-21, never merged). This branch **continues from it**: merge commit `e7bb1d60d` (two keep-both conflicts in `ApiAiNpcController` / `NpcProfileService`, field additions only). Its `migration_merchant_authoring_w05_candidate.sql` is replaced by a ledger migration.
* **Money:** none of the 29 rows moves money (no payout, withdrawal of funds, settlement payment or paid listing among them). `offer-withdrawal` is the *supply exit* of an ordinary chapter offer: sold orders, receivables and redemptions are unchanged. No row reads the money switches; the TEST overlay still sets `chengyin.release.money-payments/payout=true` like the other gap packages. Every non-money write is implemented fully.

## 1. Rows → route → test → curl (8127)

| # | App row (tsv) | Route(s) served | Unit test | curl on 8127 |
|---|---|---|---|---|
| 1 | `/api/merchant/authoring/draft-selection/list` | `POST {beforeSourceId}` — owner-only W05 sources → own CMS member templates, 20 per page, id-descending keyset, READY/UNAVAILABLE rows, the app's 9 fixed envelope keys | `MerchantDraftSelectionServiceTest` | owner 200 `[(1,READY)]`; player 403 |
| 2 | `…/draft-selection/resolve` | `POST {source, memberTemplateId, templateContentHash}` — rechecks the current template hash (`sha256(TemplateAuditSnapshot.personal)`) | same | 200 title; changed hash 409 `SOURCE_CHANGED` |
| 3 | `…/authoring/drafts` | `GET` list, `POST` create (idempotent by `requestId`) | `MerchantAuthoringGapTest`, W05 `MerchantAuthoringFlowTest` | list 200; create 200, replay same id |
| 4 | `…/authoring/drafts/{}` (prefix) | `GET/POST drafts/{id}` (save, `expectedVersion` CAS), `POST …/facts`, `POST …/content`, `GET …/review` | W05 flow test | save v0→1 200; stale 409; facts APPROVED; review APPROVED |
| 5 | `…/authoring/environment` | `GET ?releaseId` | W05 flow test | 200 `branch=UNAVAILABLE action=EXIT` (no store hours on the fixture) |
| 6 | `…/authoring/jobs` | `POST` generate (job CLAIMED first, then `AiGatewayService`), `GET ?requestId` | W05 flow test | 400 `FAILED UPSTREAM_UNAVAILABLE` (no model provider on TEST), read back the same |
| 7 | `…/authoring/jobs/cancel` | `POST {requestId}` | W05 flow test | 200/400 with the job view (`publishOutcome=NONE`) |
| 8 | `…/authoring/main-line` | `GET ?merchantId&themeId` | W05 flow test | 200 `NONE` (no THEME release) |
| 9 | `…/authoring/player` (prefix) | children 10 and 11 | — | — |
| 10 | `…/authoring/player/{}/game` | `GET` — the store's own STORE-bound release: `NONE` / `PUBLISHED` (+`releaseId`, `revisionVersion`, public `facts`) / `WITHDRAWN` | `MerchantAuthoringGapTest` | PUBLISHED with 6 public facts; after withdraw WITHDRAWN |
| 11 | `…/authoring/player/{}/requests/{}` | `GET` — the original store-chat request (`NpcChatService.readOriginalRequest("merchant", …)`) as `{merchantId, requestId, outcomeStatus, safetyDecision, safeText, errorCode, retryable, ai}`; unknown → 404 | `ApiMerchantGapControllersTest` | unknown id 404 `REQUEST_NOT_FOUND` |
| 12 | `…/authoring/public` | `GET ?merchantId&binding&…` | W05 flow test | player 200 `PUBLISHED` |
| 13 | `…/authoring/releases` | `POST` publish (requestId), `GET ?requestId` | W05 flow test | PUBLISHED `releaseId=1`; read back PUBLISHED |
| 14 | `…/authoring/releases/{}/withdraw` | `POST {requestId}` (status only; snapshot unchanged) | W05 mapper + flow tests | 200 WITHDRAWN |
| 15 | `/api/merchant/city-value/event/{}/benefit` | `GET` scope+window+`snapshotId` | `MerchantCityValueReportServiceTest` | 200 `BENEFIT_RECORD evidence=SOURCE_UNAVAILABLE` |
| 16 | `…/city-value/events` | `GET` scope+window+`cursor` — frozen snapshot, 50 per page, `v2.<snapshot>.<page>.<hmac>` cursors bound to actor/scope | same | 200 snapshot, 8 facts |
| 17 | `…/city-value/report` | `GET` scope+window+`requestId` — stored by request id (replay; other payload 409 `DUPLICATE_REQUEST`) | same | 200 `entry 3, serviceSuccess 1/3, mean 90/1`; reuse 409; player 403 |
| 18 | `…/city-value/result/{}` | `GET ?regionId&rulesReleaseId` (other scope 409 `SCOPE_MISMATCH`) | same | 200 identical body |
| 19 | `/api/merchant/crm/marketing-consents/result` | `GET ?merchantRowId&merchantOwnerMemberId&channel&requestId` → `RECORDED` (consentId, eventType, recordedAt) / `NOT_OBSERVED` | `MarketingConsentResultServiceTest` | opt-in then 200 `RECORDED OPT_IN`; unseen `NOT_OBSERVED` |
| 20 | `/api/merchant/insight` | forward → `POST /api/ai/merchant/insight` | `ApiMerchantGapControllersTest` | 200 insight body |
| 21 | `/api/merchant/npc/avatar/generate` | `POST {imageUrl(https), style}` → job `{jobId, status, style, modelUrl, thumbUrl, failReason}` | `MerchantNpcGapServiceTest` | 200 `SUCCEEDED` (TEST passthrough provider) |
| 22 | `/api/merchant/npc/avatar/status` | `POST {}` → `{available, styles, job}` | same | 200 `available=true` |
| 23 | `/api/merchant/npc/knowledge-review` | `POST {requestId, name, greeting, persona, knowledge}` — stored once, platform `AuditTask` `MERCHANT_NPC_KNOWLEDGE`; same id + other body 409 | same | 200 `PENDING_REVIEW memberId=9002`; conflict 409 |
| 24 | `…/npc/knowledge-review/request` | `GET ?requestId` → the same four fields + `requestId`, `memberId` | same | 200, four fields equal |
| 25 | `/api/merchant/npc/voice/revoke` | `POST {requestId}` — clears the cloned voice (same as `voice/reset`, idempotent) | same | 200 `changed=false` (no voice on the fixture) |
| 26 | `/api/merchant/npc/voice/script` | `POST {}` → `{available, script[5], consentIndex 0}` | same | 200 5 lines; player 403; signed out 401 |
| 27 | `/api/merchant/offer-withdrawal/request` (prefix) | `GET current?merchantId&offerId` (snapshot + `snapshotSHA256`), `POST withdraw` (the app's 7 keys; intent = SHA-256 of Foundation sorted-key JSON of `{expectedSHA256, offerId, reason, reasonSHA256}`), `GET result?…&requestId`; outcomes `WITHDRAWN`, `REJECTED_CHANGED`, `REJECTED_STATE`, `REJECTED_INPUT`, unseen `UNKNOWN` | `MerchantOfferWithdrawalRequestServiceTest` | CITY offer `canWithdraw=false`; ordinary offer WITHDRAWN `statusAtWrite=0`, result WITHDRAWN; club user 403 |
| 28 | `/api/merchant/poi/detail` | forward → `POST /api/merchant/public-detail` (anonymous, like the target; `SecurityConfig` admits the exact path) | `ApiMerchantGapControllersTest` | anonymous 200 `id=8001` |
| 29 | `/api/merchant/shop/apply` | forward → `POST /api/merchant/merchant_registration` | same | 200 envelope from the target (`请先登记实名信息…` for the player fixture) |

All refusals of the new routes answer **HTTP status == `code`** with `errorCode`; W05 routes now do the same (202 = outcome unknown, 409 = version conflict), which is what the app's `MerchantAuthoringService` branches on.

## 2. Design notes

* **W05 adaptations for the app client** (`MerchantAuthoringService`): `factVersion` is optional (server derives `STORE_ROW_V1:<store>:<updateTime>`), `productId` comes from `skuId`, a generate on an existing draft inherits its product/theme/scene, and `POST drafts` is idempotent by `requestId` (draft id = `q` + 32 hex of `sha256(member:requestId)`, 33 chars, so the audit review request id `draftId:version:kind` stays ≤ 64). Policy bean `MerchantAuthoringPolicyConfiguration` reads `chengyin.merchant.authoring.*` and exists only with `enabled=true` (**off by default**: every action needing policy stays refused with its `CONFIG_*` code). TEST turns it on with fixture values; TEST has no model provider, so generation answers `FAILED UPSTREAM_UNAVAILABLE`.
* **Bean cycle fixed** (`9fe01e4db`): the W05 port made startup fail (`NpcProfileService` → `PublicReleaseFacts` = `MerchantAuthoringService` → topic services → `NpcProfileService`). The injection in `NpcProfileService` is now `@Lazy`.
* **CITY value** is computed from the only behaviour source on this backend, the player value visits (`city_value_visit`, app-gaps-a) at the store's own points of the region's current board. A rated visit is the service success. Goals, cohort and first-choice timing have no emitter or definition, and net points / budget have no unit: they are `MISSING_*` with no numbers (never 0). No coupon redemption is linked to a visit, so a benefit record is `SOURCE_UNAVAILABLE` and never claims a grant. A visit has no separate feedback time, so its feedback facts use `entered_at`.
* **Draft selection** reads `merchant_authoring_source`, the W05 source namespace (`MERCHANT_AI_TEMPLATE_SOURCE_V1`) whose schema comes from the stranded W05 port (`0127e3dc1`). The producer of those rows (`MerchantAuthoringRecordService.record/confirm`, AI candidate → personal template) is **not** in this backend and was not ported; on TEST one fixture row was inserted for the curl check.
* **Avatar**: `MerchantAvatarProvider` SPI. There is no production bean, so `status.available=false` and `generate` answers 503 `AVATAR_PROVIDER_NOT_CONFIGURED`. `E2eTestMerchantAvatarProvider` (profile `e2e-test` + `chengyin.e2e.test-providers=true`) returns the approved image as the model.
* **Aliases** (`insight`, `poi/detail`, `shop/apply`) are servlet forwards, so the target keeps its own auth, validation and envelope.

## 3. Migration

`migration_20261119_merchant_gap.sql`, ledger entry `"20261119_merchant_gap": []` (new tables only, no FKs, rerunnable `CREATE TABLE IF NOT EXISTS`): the six W05 tables, `merchant_authoring_source`, `merchant_npc_knowledge_review`, `merchant_npc_avatar_job`, `merchant_offer_withdrawal_request`, `merchant_city_value_report`, `merchant_city_value_snapshot`, `merchant_city_value_fact`. The W05 candidate SQL is deleted. Gates: `migration_ledger.py audit` problems=[], `migration-layout-check.sh` and `migration-safety-check.sh` pass, `dup_create_table_gate.py` passes.

## 4. Tests (VPS, `flock`, `-T 1`, `-Xmx1536m`)

* **Unit:** 35/35 — `MerchantNpcGapServiceTest` 7, `MerchantOfferWithdrawalRequestServiceTest` 6, `MarketingConsentResultServiceTest` 1, `MerchantCityValueReportServiceTest` 5, `MerchantDraftSelectionServiceTest` 2, `MerchantAuthoringGapTest` 2, `ApiMerchantGapControllersTest` 3, plus W05 `MerchantAuthoringFlowTest` 8 and `MerchantAuthoringMapperTest` 1 (now loading the ledger migration via `MerchantGapH2`). **Rerun on the final code SHA `d3697cc65`: 40/40** (the list above + the existing `NpcProfileServiceTest` 5/5, which covers the `@Lazy` change).
* **Real MySQL IT:** `MerchantGapMySqlIT` **3/3** on be-int-mysql (3330, scratch schema `cy_merchant_gap_it`): migration applied twice, all 13 tables and 9 unique keys present, knowledge review / avatar job / supply-exit receipt on real rows (`LAST_INSERT_ID` in one transaction, duplicate replay, 4-byte UTF-8). Run with `CHENGYIN_MERCHANT_GAP_MYSQL_IT=1 CHENGYIN_MYBATIS_IT_PORT=3330`.
* The full `-pl framework,system,admin` suite was not rerun for this package.

## 5. TEST check on 8127 (stopped afterwards)

* DB `cy_gap_merchant` = a copy of the seeded `cy_e2e_r2` (ledger-built at this branch's base; read-only `mysqldump`, 8120 untouched) + `migration_20261119_merchant_gap.sql` → 647 tables. `build_schema.py` refuses non-`cy_e2e_*` names, so the copy was used instead.
* TEST-only fixture `fixture-merchant.sql.txt` (in this folder): points `pt-a/pt-b` of `gapsa-board-1` belong to store 8001 (`pt-c` to 8002) with four player visits; an ordinary FREE_EXPLORE copy of topic 992001 / chapter 992011 / offer 1 as 992901 / 992911 / 992991; one W05 source row → template 6001; product 992951 of store 8001.
* Overlay `application-merchant.properties` (port 8127, `cy_gap_merchant`, Redis db 7, `chengyin.e2e.test-providers=true`, money switches on, W05 policy fixture values). `start.sh` is the be-gap-rw one (stable TEST PII key).
* `smoke_merchant.py` → `smoke-8127.txt` (in this folder): every row in §1 as listed, logins 9002 merchant / 9004 player / 9003 club.

## 6. Commits

| SHA | What |
|---|---|
| `e7bb1d60d` | merge `cursor/w05-j-merchant-authoring` |
| `3b7ed0e3a` | the package (routes, services, migration, ledger, tests, IT) |
| `9fe01e4db` | bean-cycle fix (`@Lazy`) |
| `d3697cc65` | 33-char requestId draft ids |
| (this doc) | `docs/delivery/be-gap-merchant/` |

## 7. Open items

1. The W05 source producer (`MerchantAuthoringRecordService`, stranded `0127e3dc1`) is not ported, so `draft-selection/list` is empty on a real DB until it is.
2. No model provider and no avatar provider exist outside TEST; generation and avatar stay refused/failed there by design (policy and provider are owner decisions).
3. CITY: no redemption audit is linked to a visit, goals/cohort have no emitter, and points/budget have no unit; those parts stay `MISSING_*`/`SOURCE_UNAVAILABLE` until the owner defines them.
4. `authoring/player/{}/requests/{}` was curl-checked only for the not-found path (store chat is switched off on TEST); the success mapping is unit-tested.
5. Merge into `integration/backend-app-r2` and an 8120 redeploy are not part of this task.

---

# R3 (2026-11-14) — source producer, CITY goals/points/redemption, providers

Code commit **`3413e8c29`** on `feature/be-gap-merchant` (on top of `7121d9c32`). Same worktree, port 8127 (stopped after the checks), DB `cy_gap_merchant`. Logs/scripts: `/data/workspace/be-gap-merchant-out/` (`r3-tests.log`, `r3-package.log`, `migrate-r3.log`, `smoke-r3-8127.txt`, `smoke-8127-r3rerun.txt`, `fixture-merchant-r3.sql.txt`). Production, hengyin `master`/`main` and TEST 8120 were not touched.

## R3.1 W05 source producer (open item 1 closed)

* Ported from the stranded `0127e3dc1` (`git log --all -S MerchantAuthoringRecordService`): `MerchantAuthoringRecordService` (`record` = candidate after the model returns; `confirm` = ordinary MERCHANT draft via `ITemplatePublishService.saveOrUpdateDraft` + immutable `merchant_authoring_source`, one transaction, locked authority/facts rows, replay by confirmation hash), `MerchantAuthoringFactsService`, `MerchantBoundTemplateDraftService`, `MerchantAuthoringRecordMapper(.xml)`, `MerchantAuthoringCandidate/Source`, the three `dto.ai` request types. The `w05-merchant-authoring` profile gate is removed: the producer is live.
* Routes: store-bound `POST /api/ai/template/fill` (`merchantFacts` + `merchantConstraints`; legacy fill unchanged; 503 if the service bean is absent), `POST /api/merchant/authoring/catalog | product | facts | draft` (HTTP status == `code`).
* Migration `migration_20261120_merchant_authoring_candidate.sql` (new table only), ledger `"20261120_merchant_authoring_candidate": ["20261119_merchant_gap"]`.
* Draft-selection now lists real producer rows: `MerchantAuthoringRecordServiceTest` runs record → confirm → `MerchantDraftSelectionService.list/resolve` on H2 (READY row, same `sourceId`/`contentHash`, recomputed template hash == receipt), replay/409/403, changed store facts 409, no transaction 403.

## R3.2 CITY goals / points / redemption

Owner docs (`origin/ui/owner-handover`, `app-ai-pack-20261108`) only fix "points = existing member points, separate from game score" and "redemption is server-decided"; the mini-program's only merchant city behaviour is the city-node redemption (`/api/verify/citynode/redeem` → `CITY_NODE_REDEEM` audit). Decisions D1–D7 are in **`DECISIONS.md`**:

| Field | Now | Source |
|---|---|---|
| `publicGoal` | `MEASURED count` | NEUTRAL-property task attempts at the store's points |
| `cooperativeGoal` | `MEASURED count` | same, team had ≥2 confirmed members before it |
| `timeToFirstEffectiveChoice` | `MEASURED {visitCount, medianMillis}` | new `city_value_visit.feedback_at` (`migration_20261120_city_value_visit_feedback_at.sql`, guarded ALTER; ledger dep `20261114_gaps_a_city_r491`), written by the feedback command |
| `visitCost.netPoints` | `MEASURED {sum, count, unit: MEMBER_POINTS}` | APPLIED quoted actions + self-paid acquisitions on the store's points |
| `visitCost.budget` | `MEASURED {sum, count, unit: CITY_ACTION_UNITS}` | their action units + `taskCost` per accepted task |
| `event/{}/benefit` | `VERIFIED` (`BUSINESS_AUDIT_CITY_NODE_REDEEM`, `COUPON_GRANTED_AT_REDEMPTION`, audit id, coupon id) | the visit's own `CITY_NODE_REDEEM` audit; otherwise `SOURCE_UNAVAILABLE` + `evidenceReason` |
| `byCohort` | `MISSING_DEFINITION` (written reason D7) | — |

A metric with no source row in scope is `MISSING_COVERAGE` (no numbers); `MISSING_UNIT` is no longer returned. Nothing is `SOURCE_UNAVAILABLE` without a reason code.

## R3.3 Providers

`HttpMerchantAvatarProvider` (`chengyin.merchant.avatar.provider=http`, HTTPS endpoint, key from env) behind the existing `MerchantAvatarProvider`; the TEST stub `E2eTestMerchantAvatarProvider` is kept. Avatar and model production keys (no secrets): **`PROVIDERS.md`**.

## R3.4 Tests (VPS, `flock`, `-T 1`, `-Xmx1536m`)

`r3-tests.log`: **126/126**, EXIT 0 — the R2 set (`MerchantNpcGapServiceTest` 7, `MerchantOfferWithdrawalRequestServiceTest` 6, `MarketingConsentResultServiceTest` 1, `MerchantCityValueReportServiceTest` **8** (+3 R3), `MerchantDraftSelectionServiceTest` 2, `MerchantAuthoringGapTest` 2, `ApiMerchantGapControllersTest` 3, `MerchantAuthoringFlowTest` 8, `MerchantAuthoringMapperTest` 1, `NpcProfileServiceTest` 5) + new `MerchantAuthoringRecordServiceTest` 3, `HttpMerchantAvatarProviderTest` 2 + `ApiAiControllerTest` 23, `ApiAiThemeDraftHttpSecurityTest` 35, `ApiAiThemeDraftRequestTest` 12 + real MySQL (be-int-mysql 3330): **`MerchantGapMySqlIT` 5/5** (both new migrations twice; `feedback_at` present; the mapper's candidate/source INSERTs verbatim with 4-byte UTF-8 and the unique key; the CITY report goals/cost/first-choice/VERIFIED benefit on the real CITY tables) and **`CityR491MysqlIntegrationTest` 3/3** (feedback writes `feedback_at`). Gates: `migration_ledger.py audit` problems=[], layout and safety checks pass, dup-create gate passes.

## R3.5 TEST on 8127 (stopped afterwards)

`cy_gap_merchant` + both new migrations (EXIT 0, 648 tables) + TEST-only `fixture-merchant-r3.sql.txt` (SKU 992952; a NEUTRAL goal by a 2-member team on `prop-a`; one paid service visit on `prop-b`; `feedback_at` on the two rated/skipped fixture visits; one `CITY_NODE_REDEEM` audit for player 9004 / POI 1 after visit 1). `smoke-r3-8127.txt`:

* `catalog` 200 `[992951]`, `product` 200 sku `[992952]`, `facts` 200 (player 403).
* Store-bound `ai/template/fill`: `code=500 AI 服务配置缺失` (TEST has no model key, by design), so the candidate row that `record()` writes was inserted as a TEST fixture from the facts just read; then **`authoring/draft` 200 → templateId 992108, sourceId 3**, replay identical, other text 409, player 403; **`draft-selection/list` → `(3, READY)` with title `店内寻宝R3`**, `resolve` 200 with the receipt's template hash.
* `city-value/report`: `publicGoal {MEASURED, count 1}`, `cooperativeGoal {MEASURED, count 1}`, `timeToFirstEffectiveChoice {MEASURED, 2 visits, median 60000 ms}`, `netPoints {MEASURED, 30, MEMBER_POINTS}`, `budget {MEASURED, 2, CITY_ACTION_UNITS}`, `byCohort MISSING_DEFINITION`; benefit of visit 1 **`VERIFIED`** (audit 7, coupon 88).
* `npc/avatar/status` 200 `available=true` (TEST stub kept).
* The R2 smoke rerun (`smoke-8127-r3rerun.txt`) is unchanged except state the R2 run left behind (the ordinary offer was already withdrawn → `REJECTED_STATE`).

## R3.6 Open items (replace §7 items 1–3)

1. The store-bound template fill was not exercised end to end on TEST (no model key); `record()` is covered by the unit test and its INSERT by the MySQL IT.
2. Production model/avatar enablement needs the owner's keys/endpoints (`PROVIDERS.md`); defaults stay off.
3. `byCohort` stays `MISSING_DEFINITION` until the owner defines a merchant cohort key and the app contract can carry a breakdown.
4. Reports stored before R3 replay their old body.
