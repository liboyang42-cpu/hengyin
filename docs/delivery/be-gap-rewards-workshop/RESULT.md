# feature/be-gap-rewards-workshop — result (2026-11-14)

Package **REWARDS + WORKSHOP** from the true-missing list (app `origin/docs/backend-gaps` `9e40fe69a`,
`docs/release/backend-true-missing-85.tsv`): the 17 `rewards` rows (10 routes + 7 prefix families) and the 8 `workshop/mods` rows.

* Branch cut from `origin/integration/backend-app-r2` (`2e6f6651c`). `origin/feature/game-import-review` (`e10a8e98f`) was merged first
  (fast-forward) and its leftovers are closed in `docs/delivery/game-import-review/RESULT.md`.
* Worktree `/data/workspace/be-gap-rw`, TEST app on **8125** (stopped), DB **`cy_gap_rw`** (a copy of `cy_e2e_r2` plus this branch's
  migrations). 8120 was not touched. Production was not touched. hengyin `master` was not pushed.
* Before starting: no `feature/be-gap-*` branch on hengyin covered this package (only `feature/be-gap-im`). There **was** an unmerged
  Cursor branch `cursor/w17-i-reward-pledge` (`0052c106e`, 2026-10-21, one commit), which is the backend that the app's
  `Core/RewardPledge.swift` names (`ApiRewardPledgeController`, `ApiNonCashRewardController`). It is 2,590 commits behind r2, so it was
  not merged. Its code is carried into this branch and extended to the full app contract. The tables keep its `w17_` prefix.

## 1. Rewards (17 rows)

Rewards are coupon-backed rewards with no cash value. A merchant pledges a number of units of one of its own published coupons.
A reviewer freezes the pledge under an approved rule version. A player who completed a topic (THEME fact from `topic_route_session`)
claims one unit, which is granted through the existing `grantCouponWithReceipt`. The store redeems it through the existing
`redeemCoupon`. Every gated action reads a `w17_reward_policy` row. There are **no seed rows**, so everything is closed until an owner
opens it. No points, wallet or SKU stock is touched (`pms_sku` is unchanged in the tests).

### Routes (all JSON; signed out → `code 401`; refusals keep the contract code as `msg`, plus `errorCode`)

| App row(s) | Route | Notes |
|---|---|---|
| `/api/rewards/noncash` (prefix) | `GET /api/rewards/noncash` | **Two app clients.** No query → array of `RewardPledgeAwardRow` (`awardId, versionId, ruleVersion, rewardScope, factRef, state, requestId, historyId`). With `limit` (1–50) and `cursor` → the `NonCashRewardService` page `{items, nextCursor, asOf}`; items are the `RewardWire` (`rewardKind PHYSICAL`, `quantity 1`, ms times, `validityStatus` derived from `asOf`). Award ids are 64 lowercase hex, which both decoders accept. |
| | `GET /api/rewards/noncash/{awardId}` | No query → the award view (terms, windows, `useStatus`, `guaranteed:false`). With `contextType/contextId/releaseId/instanceId` → the `RewardWire`, and **404** unless the frozen reference matches. Owner only. |
| `/api/rewards/noncash/claims` | `POST …/claims` `{requestId, versionId, branch:"THEME", subjectId}` | w17 claim: the intent is committed first as `UNKNOWN`, then one transaction reserves a unit, writes the award, grants the coupon once, writes the receipt and outbox, and settles the unit. Same request id replays. A new request id for the same business key links to the same award. **New:** the pledge's merchant must have an active `merchant_chapter_offer` on the topic, otherwise `REFUSED SUBJECT_NOT_OFFERED`. |
| `/api/rewards/noncash/claims/recover` | `POST …/claims/recover` `{requestId}` | Original owner and request id only. |
| `/api/rewards/noncash/subjects` (prefix) | `GET …/subjects/{topicId}` | **New.** The newest frozen version offered for the topic: `subject, versionId, ruleVersion, termsHash, terms, coupon, store, available, redeemStart/End, guaranteed:false, blocked:W06_RESERVATION_ABSENT`, and `errorCode` (`POLICY_CLOSED`, `STOCK_EXHAUSTED`) when the best one cannot be claimed. 404 when no store offers the topic. |
| `/api/rewards/noncash/versions` (prefix) | `GET …/versions/{versionId}?branch=THEME&subjectId=` | **New.** The offer for the caller. Refusals are data (`errorCode` `PLEDGE_NOT_FROZEN`, `POLICY_CLOSED`, `STOCK_EXHAUSTED`, `SUBJECT_NOT_OFFERED`, `NO_AUTHORITATIVE_FACT`), as the app reads them. `factRef` is the caller's own completed session. |
| `/api/rewards/noncash/cases` | `POST …/cases` `{awardId, versionId, reason}`, `GET …/cases/{caseId}` | Own award only (w17 accepted any award id). `GET` is **new**. A `CASH_REFUND` case is behind the money switch (below). |
| `/api/reward-pledge/drafts` | `POST /api/reward-pledge/drafts` | Store from the merchant access context (`merchant:coupon:manage`). **`requestId` is the pledge id** (app `Supply.requestID`), so a lost response replays. `rewardKind` other than `NON_CASH` → `CASH_CLOSED`/`FEATURE_CLOSED` when the switch is off, `CASH_REWARD_UNSUPPORTED` when it is on (this package has no cash ledger). |
| `/api/reward-pledge/versions` (prefix) | `POST …/versions/{v}/hold`, `…/withdraw` | `hold` needs the `STORE_HOLD` policy. |
| `/api/reward-pledge/awards` (prefix) | `POST …/awards/{a}/return-to-pool` | Needs the `RETURN_TO_POOL` policy. |
| `/api/reward-pledge/ops` (prefix) | `GET …/ops/{op}/{key}` | **New.** The store's write receipt (below). 404 until one exists. |
| `/api/reward-pledge/pledges` | `GET …/pledges` | **New.** `{store, pledges:[{pledge, versions[]}]}` for the caller's store. |
| `/api/reward-pledge/review` (prefix) | `GET …/review`, `GET …/review/{versionId}` | **New.** For the `REVIEWER` policy actor only: merchant-confirmed versions (never the reviewer's own), and one version read back after a freeze. |
| `/api/reward-pledge/fulfilment/preview` | `POST …/fulfilment/preview` `{awardId}` | `merchant:verify`, own store only. Adds `serviceStart/End` to the w17 view (the app compares service and redeem windows). |
| `/api/reward-pledge/fulfilment/redeem` | `POST …/fulfilment/redeem` `{awardId, confirm:"true"}` | Existing `redeemCoupon`. Returns `redemption` (`REDEEMED` / `ALREADY_USED` / `EXPIRED`) and `stock`. |
| `/api/reward-pledge/fulfilment/expire` | `POST …/fulfilment/expire` `{awardId}` | Same CAS as redeem. **New:** own store only (w17 had no store check). |
| `/api/reward-pledge/fulfilment/resolve` | `POST …/fulfilment/resolve` `{code}` | **New.** The coupon's existing dynamic code (`/api/coupon/qr-token`) is resolved to this store's award. Read-only. |
| `/api/reward-pledge/fulfilment/awards` | `GET …/fulfilment/awards` | **New.** This store's awards as `RewardPledgeAwardRow`s. |

The w17 routes `/{pledgeId}`, `/{pledgeId}/submit|confirm|revise|freeze|stats` and `/compensations` keep their w17 semantics.
Ownership is now checked against the store, so staff with `merchant:coupon:manage` can act. The confirming person is recorded, and
the reviewer who freezes must be someone else.

### Write receipts (`w17_reward_op_receipt`)

The app recomputes one digest per write (`RewardPledgeRequests.digest`) and only clears its local lock when a receipt with
`state, op, key, payloadHash` (+ `pledgeId` / `versionId` / `awardId`) matches. Every store write (`draft, submit, confirm, hold,
withdraw, expire, return`) now stores exactly that, per `(store, op, key)`:

* draft: key = pledge id, digest = `sha256(couponId|quantity|serviceStart|serviceEnd|redeemStart|redeemEnd|terms|minor|supplyRef)`
  (= w17's payload hash). A unit test checks it against the app's string.
* submit / confirm: key = the version the merchant saw, digest = `sha256(op|pledgeId|key)`. hold / withdraw: `sha256(op|versionId)`.
  expire / return: `sha256(op|awardId)`.
* APPLIED with the same digest replays without a second write. A different digest is 409 `CONFLICT`. A refused write stores
  `REJECTED` + `errorCode` in its own transaction, and a later retry replaces it.

### Money switch

`CashPayoutSwitch` is the server side of the app's `ReviewedAreaSwitch.moneyPayments && ReviewedAreaSwitch.payout`:
`chengyin.release.money-payments` and `chengyin.release.payout`, both **default false**. When either is off, anything here that would
pay out cash is refused with **`code 403`, `msg CASH_CLOSED` (the string the app matches), `errorCode FEATURE_CLOSED`**. That covers a
`CASH_REFUND` case and a non-`NON_CASH` draft. On TEST (8125 overlay) both are on: a `CASH_REFUND` case then opens as an `OPEN`
support case for the existing refund desk (nothing here moves money), and a cash draft is still refused as `CASH_REWARD_UNSUPPORTED`.

## 2. Workshop (8 rows): W18 professional materialization

App: `Core/WorkshopPaidProfessionalService.swift`, `…Contracts.swift`, `…Operation.swift`. All routes are `POST` + JSON under
`/api/workshop/purchased/`. Signed out → HTTP 401. Refusals keep their HTTP status (the app reads any non-200 as unavailable).

| App row | Route | Response |
|---|---|---|
| `professional-targets` (prefix), `/list` | `{licenseId, beforeTopicId?}` | `w18-owned-professional-topic-targets-v1`: the caller's own topics, id descending, 50 per page, `hasMore` / `nextBeforeTopicId` (always present). Each item has exactly the 8 keys the app decodes. `mode` comes from `cms_topic.product_type` (1 CITY_ORIENTATION, 2 FREE_EXPLORATION, else UNRESOLVED). `ownerModeFingerprint` is a framed SHA-256 under profile `W18_CONTENT_OWNER_MODE_V1`. |
| `/detail` | `{licenseId, topicId}` | One of those items. Anyone else's topic → 404. |
| `professional-operations` (prefix), `/submit` | the app's 15-key command | `w18-paid-professional-operation-v1`. Same request id + same command replays; a different command is 409. |
| `/status` | `{requestId}` | The operation, or the 5-key `NOT_FOUND` shape (also for another member's id). |
| `/cancel` | the command | Open → `CANCELLED`. Never seen → recorded as `CANCELLED`, so a late submit cannot create anything. Terminal → returned unchanged. |
| `/history` | `{licenseId, beforeOperationKey?}` | `w18-paid-professional-operation-history-v1`: the owner's intents on that license by `operationKey` descending, 50 per page, each with its `command`. |

* **License ids.** The app's `w18-paid-{N}` is `workshop_order.id = N`. The license must be the caller's, paid and `ACTIVE`
  (404 / 403 otherwise).
* **`commandHash`** is the app's construction: UTF-8 fields with 4-byte big-endian length prefixes in the fixed order. A unit test
  checks a vector computed independently from the Swift description.
* **Server checks (inside the submit transaction):** through `WorkshopService.professionalSource`, the license and its exact purchased
  version (module, content and terms hashes), and the install receipt `installationRequestId` whose private draft is `ownedDraftId`
  with the current revision and payload hash (the same fingerprint `/v2/purchased/install/targets` reports). The topic must be the
  caller's, and the mode and fingerprint must match. `componentHash` has no server counterpart, so it is only format-checked and bound
  into the command hash.
* **On success:** one **guarded private template** (`WorkshopService.createGuardedTemplate` → the existing
  `installWorkshopSnapshot`). It is unpublished, not attached to a node and not executable. `players`, `duration` and `category` are
  cleared and listed as missing professional inputs, together with `description` / `questionName` / `questionAnswer` when blank.
  The response carries `creation` (`CREATION_COMMITTED`, `currentTemplateState NOT_CHECKED`, `currentUseAuthority NOT_GRANTED_BY_HISTORY`).
* **On a failed check:** `REJECTED` with `rejectionCode CURRENT_SOURCE_OR_TARGET_UNAVAILABLE`, the only non-field code the app accepts.

### Review state machine (reused from game-import-review)

The transitions moved out of `ExternalGameReviewService` into `business.review.ReviewStateMachine`. Both external games and workshop
operations use them now. A workshop operation stores `review_status` and appends `workshop_professional_event` rows
(AUTHOR / ADMIN / SYSTEM). The app sees PENDING / ESCALATED as `PREPARED`, APPROVED as `CREATED`, and REJECTED / CANCELLED as
themselves.

* `chengyin.workshop.professional.review=AUTO` (default): the server is the reviewer (SYSTEM) inside the submit transaction, so the
  app gets the terminal state it requires from `submit`.
* `MANUAL`: submit returns `PREPARED`, and `/business/workshop-professional/review/list | {id}/approve | reject | escalate` decide it
  (`business:audit:list` / `business:audit:review`, as for external games). Approve re-runs every check and creates the template, or
  rejects. Unit-tested; TEST runs AUTO.

### What the app can reach today

The app's purchased-library routes (`/api/workshop/purchased/list|detail|install…`) are answered by r2 with 409
`WORKSHOP_CONTRACT_UPGRADE_REQUIRED` (the backend serves `/v2/…`). So an unmodified app has no installed-text reference to build a
command from. These 6 routes are served to the contract, but the end-to-end flow needs the app to move to the `/v2` purchased routes
(or a mapping of them).

## 3. Migrations (own ledger entries only)

| Migration | Ledger entry | Content |
|---|---|---|
| `migration_20261116_external_game_review.sql` | already in game-import-review | applied to `cy_gap_rw` first (ledger order) |
| `migration_20261117_reward_pledge.sql` | `"20261117_reward_pledge": []` | w17's 9 tables (from its candidate SQL, now `IF NOT EXISTS`, plus indexes for the new reads) + `w17_reward_op_receipt`. New tables only; no seed or policy rows. |
| `migration_20261117_workshop_professional.sql` | `"20261117_workshop_professional": ["20261116_external_game_review"]` | `workshop_professional_operation` (CHECK on the 5 review statuses, UNIQUE request id and operation key) + `workshop_professional_event`. New tables only. |

w17's `migration_reward_pledge_candidate.sql` is not carried (its tables are in the dated migration). Gates on this branch:
`migration_ledger.py audit` problems `[]`, `migration-layout-check.sh` pass, `migration-safety-check.sh` pass (all gates, 20 changed
migrations in ledger order), `role_permission_contract.py` pass, `dup_create_table_gate.py` pass. The TEST fixture SQL is stored as
`*.sql.txt`, because the ledger refuses unclassified `.sql` files outside the managed directory.

## 4. Tests (VPS, `flock /data/workspace/.mvn.lock`, `-T 1`, `MAVEN_OPTS=-Xmx1536m`)

`mvn package -DskipTests`: BUILD SUCCESS. Then `-pl chengyinhub-admin -am -Dtest="RewardPledge*,Workshop*,ExternalGame*,RewardsWorkshop*,ApiAppGapsWorkshop*"`
with the real-MySQL ITs on be-int-mysql 3330 (`CHENGYIN_R2_MYSQL_IT=1`, `CHENGYIN_MYBATIS_IT_PORT=3330`, scratch DBs `cy_gaprw_*` / `*_rw`):
**105 run, 0 failures, 0 errors.**

* `RewardPledgeFlowTest` **16/16** (H2, real mapper XML). These are the 8 w17 cases, adapted to store-scoped receipts, plus 8 new cases:
  the draft receipt and app digest, submit / confirm keys, rejected receipt then retry, list / page / detail for both decoders,
  catalog / offer refusals as data, store awards / resolve / cases / cash switch, and controllers (401, 403 `FEATURE_CLOSED`, 403 without
  merchant access).
* `WorkshopProfessionalServiceTest` **6/6** (H2 + mocked `WorkshopService`): the commandHash vector, targets, AUTO create + replay +
  409, failed checks → REJECTED, MANUAL review on the shared machine, and cancel-before-submit + history paging.
* **`RewardsWorkshopMysqlIntegrationTest` 3/3** (new tables on real MySQL 8, each migration applied twice): receipt PK per store,
  APPLIED never deleted, award business key unique, `pageAwards` / `findAwardByHistory`, `subjectVersions` needs an active
  (non-expired) chapter offer, the workshop review flow on MySQL, the status CHECK, and the request-id UNIQUE.
* Regression for the touched code: `ExternalGameReviewMysqlIntegrationTest` 7/7, `ExternalGamePackageValidatorTest` 9/9,
  `WorkshopPurchaseMyBatisIT` 2/2 and `WorkshopAppleIapMyBatisIT` 4/4 (the `WorkshopMapper.xml` change), the Workshop service / caller
  tests (13 + 16 + 16 + 5 + 3 + 2 + 2), and `ApiAppGapsWorkshopControllerTest` 1/1.

The full unit suite was not re-run here (the r3 integrator runs it). Logs: VPS `/data/workspace/be-gap-rw-out/` (`build.log`,
`test.log` first run, `test2.log` final, `smoke-8121.txt`, `smoke-8125.txt`).

## 5. curl checks on 8125 (loopback, `cy_gap_rw`, Redis db 13): **63/63 OK** (`curl.txt`)

TEST-only fixture (`fixture_8125.sql.txt`, undone by `reset_8125.sql.txt`):

* coupon 970001 of merchant 9002 (store 8001);
* a TRAFFIC chapter offer of 9002 on topic 992001;
* policies `REVIEWER`(9004), `RULE` / `CLAIM` `rw-test-1`, and `STORE_HOLD`;
* for 9003, one paid ACTIVE workshop license (order line 970201), its version, and its install into private draft 970301.

Player 9009 had already completed 992001 in r2's seed. The overlay turns the money switches on and leaves review on AUTO.

Covered, in order:

* signed out 401, and a player without merchant access 403;
* draft, replay, conflict, cash refusal, the ops read-back, submit, confirm and owned pledges;
* the review queue refused for a non-reviewer and served to the reviewer, then freeze and read-back;
* catalog, offer, offer without a completion, claim, replay and recover;
* list, page, both details, a wrong reference (404) and someone else's award (404);
* store awards, preview, resolve of a real `/api/coupon/qr-token` token, redeem without confirm (400), redeem, redeem again, expire
  after redeem, the ops read-back, return-to-pool closed, stats (conserved), hold, withdraw, and the ops read-back by another identity
  (403);
* cases, including `CASH_REFUND` opening with the switch on;
* workshop: targets signed out 401, list, detail, someone else's license 404, submit **CREATED** (template 992107: member 9003, private,
  unpublished), replay, status, someone else's status NOT_FOUND, cancel of a CREATED one, a stale hash REJECTED with the app's code,
  cancel before submit, a late submit of that id, history, and a malformed command 400. The events are
  `AUTHOR:PENDING, SYSTEM:APPROVED, AUTHOR:PENDING, SYSTEM:REJECTED, AUTHOR:CANCELLED`.

8125 was stopped after the run. `cy_gap_rw` is kept for the integrator; it holds only fixture and check rows.

## 6. Left / for the owner

* No policy row is seeded anywhere. Opening rewards on a real environment means an owner inserts `w17_reward_policy` rows
  (`REVIEWER` with the reviewer's member id, `RULE` / `CLAIM` with the approved rule version, and optionally `STORE_HOLD`,
  `RETURN_TO_POOL`, `COMPENSATION`, `REVERSE`, `MINOR`).
* The topic ↔ store binding for offers is `merchant_chapter_offer` (active, not expired, `merchant_id` = the pledge merchant's
  member id). Say so if a different binding is wanted.
* Workshop: the app needs the `/v2` purchased routes before it can build a professional command (§2). Timeouts (PENDING → ESCALATED)
  are not swept for workshop operations; AUTO never leaves one pending.
