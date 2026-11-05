# ROUND14 ACCESS — former principal historical settlement

Base: `4aff0697d` (`origin/integ/launch-test-20261103`). Own branch: `feature/round14-access-20261105`. Frontend-only commit: `d13a72f68` (`[skip ci]`); backend source is retained uncommitted for the coordinator's explicitly requested joint Java commit, avoiding repeated builds.
Scope: ROUND13 owner decision 13, approved 2026-11-05. Former principals retain access to their own historically frozen receivables and actual income, without current club management permissions or another principal's account. No production, real transfers, deployment, WeChat DevTools, shared rig restart, schema change, dependency or auth/JWT/Redis configuration change.

## Changes

- `POST /api/club/settlement/mine` lists only clubs backed by the authenticated member's frozen settlement or actual income ledger. Caller member/payee IDs are never used.
- `/summary` keeps the existing FINANCE_READ club view for current authorized staff. Without that permission it falls back to a strictly personal projection; `personal:true` can additionally narrow an authorized user's view. It never expands access. A user with no historical earnings/receivable rows gets an explicit refusal.
- Personal topic rows require the frozen `coop_settlement.merchant_id` to equal the authenticated member. CLUB beneficiary rows also require the exact club. Club self-play INITIATOR_MARGIN/INITIATOR_GUARANTEE rows, which the real producer writes without CLUB beneficiary fields, are scoped through their topic's club and frozen member recipient. Unrelated merchant lines are excluded.
- Personal session rows require the authenticated ledger `member_id`, income `change_type=1`, event type 3, live ledger row, and exact activity club. For a session credited to different principals in separate postings, only this person's postings are shown. Executed refund adjustments also require this person's original `receiver_id`; another principal's adjustment is excluded.
- Personal session responses contain only own net income and source/date; they omit whole-session sales, platform fees, other payees and merchant fees. Personal pages do not mount the club direct receivables ledger.
- Account earnings → 更多 → 本人俱乐部结算 opens the authenticated historical club list, then the existing summary page in personal mode. Topic detail and its return link preserve that mode and row ID. Revoked/missing history clears stale detail amounts. Hidden/unloaded/changed-account callbacks remain rejected.
- The settlement page uses the existing login guard because current club membership is no longer a prerequisite for personal historical income. Server-side summary authorization remains mandatory; other club management pages keep their current club guards. Owner-only global wallet stages now also appear in a verified personal historical projection; `/api/wallet/stages` still reads only the authenticated person's own account.
- Customer-service withdrawal remains the only reachable UI flow. Former principals' direct `/withdraw` requests direct them to customer service, even if the dormant fast-withdraw flag is true. For a current principal's dormant automatic path, the amount source is now only that authenticated principal's verified paid rows, excluding any former principal's money. The flag, underlying withdrawal service, end+14-day hold, amounts and recipient writes are unchanged.

## Producer and ownership evidence

These are current source traces, not fixture assumptions:

1. `CmsTopicServiceImpl.java:992` resolves each club cooperation order with `resolveOrderPayee`; `:1423` reads `Club.memberId`, not a merchant entity ID or the club ID. `newSettlementLine` at `:1401` writes that member ID into `CoopSettlement.merchantId`. `CoopSettlementClaimServiceImpl.java:296` applies frozen beneficiary attribution without replacing merchantId. `CmsTopicServiceImpl.java:1549` locks that member; `:1560` credits that member; `:1567` records the same member in the payout ledger. A later ownership transfer does not rewrite that persisted recipient.
2. Club self-play uses `clubPrincipalPayee.forTopic` at `CmsTopicServiceImpl.java:823`, then creates the principal's `INITIATOR_MARGIN`/`initiator` line at `:835`. This path does not set CLUB beneficiary fields, which is why the extra personal projection predicate is needed. Payout uses the same frozen merchantId chain.
3. Activity settlement selects the principal through `CmsActivityServiceImpl.activityPayee` (`:495`) and `ClubPrincipalPayee.forActivity`. `settleActivity` (`:345`) credits that selected person. `CmsActivityServiceImpl.java:694-702` persists actual memberId, event type, activity ID, income direction and amount in the ledger. Historical access reads those recorded member IDs, never `activity.member_id` or today's club owner.
4. `RefundServiceImpl.java:1187-1195` copies receiver type/id from the original settled detail into the executed negative adjustment. The personal session read reuses the existing registration/refund linkage and additionally requires that original recipient. No refund arithmetic or settlement producer was edited.
5. `ClubGovernanceServiceImpl.java:221-235` revokes target/former owner assignments, demotes the former owner to ordinary membership and CAS-transfers club ownership. This existing management boundary is preserved. Personal history grants no management permission.

## Caller trace

| Entry/caller | Data / permission |
| --- | --- |
| Existing club detail `goClubFinance`, club topic detail `goSettlement`, club-edit scene | Existing club summary; current staff FINANCE_READ behavior remains |
| Club detail `_loadFinance` | Same server summary; no new management capability |
| Account earnings scene `openClubSettlement` | New own-history list; no club membership dependency |
| Settlement list `openClubSettlement` | Only a club from the current account's server-returned list, with `personal=1` |
| Settlement `goTopicSettlement` | Existing `source=club`, exact row ID/topic ID/club ID; personal mode propagated |
| Coop settlement detail `load` / fallback return | Same scoped server summary, then exact row matching; personal mode retained |
| Settlement `goWithdraw` | Existing customer-service popup after the authenticated wallet stages report available funds |
| Direct `/withdraw` | Exact club plus authenticated frozen payee query; former → customer service, current dormant path → own verified paid rows only |

## Validation

- Local focused Node: **155/155 PASS**, zero failed/skipped. Exact suites:
  - `club-settlement-page-contract.test.js`
  - `coop-settlement-detail-state-contract.test.js`
  - `funds-stages-owner-only-contract.test.js`
  - `scene-asset-earnings-contract.test.js`
  - `access-gate-contract.test.js`
  - `withdraw-cs-popup-contract.test.js`
  - `k5-club-session-merchant-fee.test.js`
  - `r10-f3-guest-login-gate.test.js`
  - `funds-stages-view-model.test.js`
- `node chengyinhub-xcx/tests/lint/ui-integrity.js`: **UI-GATE-0:PASS**; no allowance/baseline change.
- `node chengyinhub-xcx/scripts/uiaudit/build-action-ledger.js --check`: **FAIL — ledger missing/drift**. Coordinator owns the shared ledger refresh with all lanes; no count/evidence gate was weakened. The two new real taps are the earnings-menu entry and the own-club-list entry.
- `git diff --check`: PASS. Gitleaks public backend/frontend/report patch: PASS; frontend commit normal pre-commit JS/JSON checks: PASS.
- Final backend focused compilation/tests: pending coordinator VPS receipt. No local Java/Maven/Docker/MySQL/Redis process was started.
- New `ClubHistoricalSettlementAccessH2Test` has 7 tests and executes production MyBatis SQL plus Druid wall checks: former own row/shared-session slice, new principal isolation, strangers denied, wrong club/missing/negative/anonymous requests, transfer twice without amount/payee mutation, original-recipient refund filtering, club self-play income, and current principal request excluding former paid amounts. Withdrawal service is mocked, so no transfer occurs.
- Exact focused suites for coordinator: `ClubHistoricalSettlementAccessH2Test,ApiClubSettlementControllerTest,ClubSessionSettlementH2Test,ClubSettlementTopicNameH2Test` with `-pl chengyinhub-admin -am -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false` on the approved VPS budget helper. Existing `ClubSettlementSummaryMybatisIntegrationTest` remains unchanged and belongs to unified real-MySQL CI. No gated suite test count changed.

## Exact real-MySQL follow-up

`ClubHistoricalSettlementAccessMySqlIT` reuses the same seven production-mapper behavior tests. Seven explicit override declarations keep the existing registry source-count check strict without duplicating assertions. It requires real MySQL 8+, creates a unique validated task-owned schema per test, and drops only that exact schema after its own CREATE succeeds. There is no shared/business database import and no real transfer. `MysqlIntegrationTestConfig` supplies the existing protected environment; no credentials are added to source or the command.

Run only inside the coordinator's approved VPS budget runner (not on the Mac), after verifying its MySQL target and the six frozen file hashes from `/tmp/round14-access-backend-manifest.json`:

```sh
CHENGYIN_FUNDS_MYSQL_IT=1 mvn -q -pl chengyinhub-admin -am \
  -Dtest=ClubHistoricalSettlementAccessMySqlIT \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test
```

Proposed `ci/real-storage-it-registry.json` class entry (coordinator owns the shared registry and batch registration):

```json
"com.chengyinhub.business.service.ClubHistoricalSettlementAccessMySqlIT": {
  "tests": 7,
  "storage": ["mysql"],
  "gates": {"CHENGYIN_FUNDS_MYSQL_IT": "1"},
  "database_base": "cyit",
  "nested": [],
  "note": "R14 historical club payee isolation; same 7 H2 behavior assertions run against production MyBatis SQL on real MySQL; unique task-owned inline schema per method, no migrations or shared data."
}
```

Connection variables: existing `CHENGYIN_TEST_MYSQL_HOST`, `CHENGYIN_TEST_MYSQL_PORT`, `CHENGYIN_TEST_MYSQL_USER`, `CHENGYIN_TEST_MYSQL_PASSWORD`, `CHENGYIN_TEST_MYSQL_DATABASE_BASE`, `CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX`; all via existing protected runner environment. The suite creates/drops only `${base}_r14_access_${8hex}${suffix}`, with create/drop privileges confined to task scratch schemas. It creates minimal scratch tables matching the mapper's required columns; it does not execute production migrations. Expected real test count is 7, not 14; overrides replace inherited discovery.

Local read-only `ci.real_storage_it.precheck` with the proposed in-memory registry entry: PASS, declared_tests=7, source gate CHENGYIN_FUNDS_MYSQL_IT=1, Java/JDK21, no problems. The shared registry was not edited.

Required receipt: 7 tests, zero failure/error/skip, plus exact source hashes. A skipped conditional suite is not PASS. This new class has not been silently added to or counted as part of the existing Funds gate; coordinator registration/unified execution remains pending, and MONEY owns shared counts.

After the final candidate is installed by its sole runtime owner, the remaining isolated walkthrough is: use independently authenticated former A/current B/stranger accounts, create earnings via normal test business flows, transfer the same club twice through normal governance, and read each account's account-earnings entry → personal club list → summary → exact topic row/detail. Confirm A gets only frozen A rows and ledger slices, B's personal projection excludes A, stranger/other-club/negative-ID reads refuse, and A cannot reach current club account/direct-ledger or manage the club. Read back unchanged payee IDs/amounts and original-recipient refund adjustments independently. Keep all real transfer paths off; contacting customer service proves only the entry, not a completed payment or customer-service case.

## Integration triage and independent POLICY review

Coordinator full-suite triage requested X08 missing-club handling, stale business500 wording and M-32 account earnings. Serial reproduction in ACCESS found two stale assertions: X08 expected removed duplicate helper text even though the existing missing-club title and return action remain; M-32 expected the club-wide direct ledger to mount solely on clubId, which conflicts with the approved historical personal projection. Tests now require the visible title/back action and the exact non-personal ledger guard. The business500 detail-clear test already passes with the earlier own-history refusal wording fix.

Final serial local Node union: **184/184 PASS**, zero failure/error/skip, from the original nine listed suites plus `club-ui-true-defects-contract.test.js` and `dw29-l6-merchant-r2.test.js`. The three triage suites alone pass 65/65. No product behavior or shared baseline/ledger changed in this follow-up.

Root additionally assigned read-only POLICY review, then authorized a correction only in `JourneyReviewTexts.java` and `BoundTemplateReviewTextsTest.java`. One concrete P1 text-loss defect was found: JSON-looking raw story text could lose visible suffixes/keys during recursive decode. The prepared overlay retains raw text plus deduplicated decoded leaves, leaves the general parser untouched, and adds two tests (suite total4). Exact overlay hashes and full independent review are in `ROUND14-POLICY-INDEPENDENT-REVIEW.md` and `/tmp/round14-policy-review-overlay-manifest.json`; root owns Java overlay/testing/commit. No local Java execution or POLICY-source commit occurred.

## Unverified / release boundary

- No authenticated HTTP/UI walkthrough on the final JAR, no new screenshots, no real multi-account ownership-transfer flow, no customer-service case processed to final payment, no native device acceptance. Unit/SQL tests are not a real business walkthrough.
- Full CI, real-MySQL funds suite, exact backend artifact/runtime identity and final merged ledger are coordinator-owned and pending. No green/release claim.
- Keep existing end+14-day hold and production payout method; do not infer this read-only projection authorizes a new automatic withdrawal product or any old liability reassignment.

STATUS: PARTIAL — source and frontend focused checks prepared; frontend commit delivered; final VPS receipt, coordinator joint Java commit, real-MySQL execution, unified ledger/CI and runtime/device acceptance pending.
