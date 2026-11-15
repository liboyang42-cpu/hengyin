# R12 creator-earnings — backend receipt (hengyin)

Branch `feature/r-creator-earnings`, based on `origin/roadmap/be-integ` (5eb0ae1d7).

## Findings

| ID | State | Notes |
|---|---|---|
| GAM-10 (P1) workshop price recorded no seller earning | **Closed** | Paid workshop orders now append a `SALE` ledger row with the intended creator share; refunds append `SALE_REVERSAL`. Uses and verified plays earn points. |
| MON-06 (P1) `creator_income_detail` had 0 writers; `/api/mod-settlement/**` answered 503 | **Closed (reads)** | The ledger is projected into `creator_income_detail` (insert-only, keyed). `GET /api/mod-settlement/mine`, `/qualification`, `/income`, `/income/{orderKey}`, `/income/{orderKey}/revisions`, `/{ms_id}` now answer. Any other method or path under `/api/mod-settlement/**` (payout writes) still answers 503. |

## Design

- **Append-only ledger** `creator_earning_ledger`: one row per business event, unique `event_key`, inserted with `insert ignore`. No update or delete statement exists.
- **Sources** (facts other features already record; nothing in their write paths changed):
  - `ADOPT` — `template_adoption_receipt` COMPLETED for a member-authored library template (`cms_template_library.member_id`). Once per (template, adopter), ever.
  - `INSTALL` — `workshop_install` of a purchased version. Once per (version, buyer).
  - `PLAY` — a verified play is a server-recorded node check-in (`player_node_progress.status=1`) on a node whose template comes from the creator's library template, or from a workshop-installed/inherited draft. One row per check-in.
  - `SALE` / `SALE_REVERSAL` — `workshop_order` paid / refunded. Apple orders use milliunits ÷ 10.
- **Never self**: a creator earns nothing from their own adoption, install, play or purchase.
- **Daily caps** (config): rewarded plays per creator per day (default 50) and per (creator, player, item) per day (default 1). Plays over the cap are still recorded (`capped=1`, 0 points), so the counts stay true.
- **Points** are credited in the same transaction as the ledger row (creator's `ums_member` row locked first, which also serialises cap counting), with a `ums_member_points_detail` row: `event_type=96`, `reward_business_key=creator-earning:<event_key>` (the existing unique key there rejects a replay a second time).
- **Money**: a sale records `gross_minor`, `creator_share_bps` (default 7000, same default as the coordinator's external-game config) and `creator_minor`. `money_state` is `HELD_MONEY_OFF` while `chengyin.release.money-payments` is false, `PENDING_PAYOUT` after it is on. **No code path pays money**; there is no payout here.
- **Accrual job** `CreatorEarningsJob`: hourly (`0 7 * * * ?`, Asia/Shanghai) for yesterday and today. Idempotent. Admin can re-run one day.
- **`creator_income_detail` mirror**: `creator_id` is `creator_profile.id`, so only approved creators (`apply_status=1`) get rows; a creator approved later is caught up on the next run. `amount` = intended share in yuan (0 for points-only rows), new `points` column carries points, `income_status` 1 for points, 0 for held money, 2 for reversal, `idempotency_key='CEL:<ledger id>'`.
- **Reuse of `feature/extgame-money`**: that branch is not in this base, so nothing was copied. The points credit mirrors its `SpringExternalGameWalletBridge.creditPoints` pattern and its default 70% share. After both merge, the external-game daily settlement could append into this ledger (event type e.g. `EXTGAME_TRAFFIC`) instead of keeping a second creator view — left for the coordinator.

## Files

| File | Change |
|---|---|
| `chengyinhub-admin/sql/migration_20261115_creator_earnings.sql` | New table, guarded `ALTER` adding `creator_income_detail.points`, menu 「创作者收益」 under the 创作者准入 parent (perm `business:creator:list`). Rollback note at top. |
| `ci/migration-dependencies.json` | Registers the migration after `20261111_creator_income_detail_idempotency` and `p2_4_admin_ia_menu`. |
| `chengyinhub-system/.../creatorearnings/CreatorEarningsPolicy.java` | Pure rules: keys, self-exclusion, points, caps, share, money state, app order key (`MOD`+29 hex). |
| `chengyinhub-system/.../creatorearnings/CreatorEarningsService.java` | Accrual, mirror, creator reads, MOD income facts/revisions in the app's `mod-creator-income-*-v1` shape, admin report. |
| `chengyinhub-system/.../creatorearnings/CreatorEarningsJob.java` | Hourly job; `chengyin.creator-earnings.job-enabled` (default true). |
| `chengyinhub-system/.../mapper/CreatorEarningMapper.java` + `resources/mapper/business/CreatorEarningMapper.xml` | Candidate selects, ledger insert, mirror insert, reads. Inserts and selects only. |
| `chengyinhub-system/.../domain/CreatorIncomeDetail.java`, `CreatorIncomeDetailMapper.xml` | Read the new `points` column. |
| `chengyinhub-admin/.../api/ApiCreatorEarningsController.java` | `GET /api/creator-earnings/mine`, `/mine/ledger`, and the `/api/mod-settlement/mine…` reads. Own member only; 401 when signed out. |
| `chengyinhub-admin/.../business/CreatorEarningsAdminController.java` | `/business/creator-earnings/report`, `/ledger`, `/rules` (`business:creator:list`), `POST /accrue?day=` (`business:creator:review`). |
| `chengyinhub-ui/src/api/business/creatorEarnings.js`, `src/views/business/creator/earnings.vue` | Admin report page: per-creator totals, ledger dialog, one-day re-accrual. Existing `Cy*` components. |
| `chengyinhub-admin/src/test/.../CreatorIncomeDetailNoWritePathTest.java` | The guard said a writer needs an owner decision and a test change; it now allows exactly `CreatorEarningMapper.xml` and asserts it is `insert ignore` with `idempotency_key`, single statement, no update/delete/replace. |
| Tests | `CreatorEarningsPolicyTest`, `CreatorEarningsAccrualMyBatisTest` (H2 MySQL mode, real XML, real service), `ApiCreatorEarningsControllerTest`. |

Configuration (all optional, defaults shown): `chengyin.creator-earnings.adopt-points=5`, `install-points=5`, `play-points=1`, `daily-play-cap=50`, `daily-play-per-player=1`, `sale-share-bps=7000`, `job-enabled=true`, `cron=0 7 * * * ?`.

## Verification

```
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile                      # OK
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -pl chengyinhub-admin -am test \
  -Dtest='*CreatorEarnings*Test,CreatorIncomeDetail*Test,ApiAppGaps*Test,ApiCreatorCenter*Test,CreatorCenter*Test,CreatorReapply*Test,RefundSingleSided*Test,Workshop*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false
# 21 suites, 0 failures, 0 errors (new: Policy 6, Accrual 3, Controller 5; guard 5+2)
bash ci/migration-layout-check.sh                                               # pass
bash ci/migration-safety-check.sh   # fails only on 6 other 20261115 migrations already unregistered in the base; this one is registered
node ci/api-existence-gate.js                                                   # rc=0
node ci/silent-error-gate.js        # fails identically on the base; no change from this branch
python3 -m pytest ci/test_role_permission_contract.py ci/test_migration_gates.py ci/test_migration_ledger.py
# 5 failed / 225 passed — identical 5 failures on origin/roadmap/be-integ
```

The H2 test covers: every source, self-exclusion, official templates skipped, one adoption per member, per-player and per-creator caps, held money, refund reversal, mirror only for approved creators, a replay writing nothing (no second points credit), money on → `PENDING_PAYOUT` with no balance call, and the exact field set of the app's income fact.

## Not verified

- Real MySQL: `insert ignore … select … limit`, `json_extract` on `workshop_version.content_json`, collations of the `concat()` key comparisons. H2 cannot prove these; run the migration and one `POST /business/creator-earnings/accrue?day=` on TEST.
- The Vue page was not built (no `node_modules` in this environment).
- Date boundaries assume the database stores Asia/Shanghai wall-clock times like the rest of the backend.

## Owner actions and questions

1. **Point values and caps** are defaults I chose (5 / 5 / 1, 50 per creator per day, 1 per player per item per day). Please confirm or set them in configuration.
2. **Sale share** default 70% mirrors the external-game default. Confirm the workshop share before "money on".
3. **"Money on"** only changes the recorded state to `PENDING_PAYOUT`; a payout path (TEST-only, behind `ReviewedAreaSwitch.payout`) is still to be built and is out of scope here.
4. **Verified play** = server-recorded node check-in. If a stricter signal is wanted (e.g. only runtime sessions with status completed), say so.
5. Coordinator: after `feature/extgame-money` merges, decide whether its traffic settlement should append into `creator_earning_ledger` so creators see one total.
6. No hard-coded secrets were found in the files touched.
