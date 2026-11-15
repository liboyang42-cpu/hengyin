# R7 points-governance — backend receipt (hengyin `feature/r-points-governance`)

Base: `origin/roadmap/be-integ` @ `5eb0ae1d7`. App: chengyin-app `cloud/r-points-governance` (see its `docs/roadmap/points-governance/RESULT.md`).

## Findings

| ID | Status | How |
|---|---|---|
| MON-09 (P1) | **Closed for the generic award path and the review reward; other direct credit sites left open (below)** | Per-member daily cap per award source + monthly budget per programme, checked under the member row lock inside the award transaction. Admin console page with issued / redeemed / outstanding liability in yuan. |
| MKT-12 (P1) | **Closed** | Territory yield gets its own city coin: append-only ledger, credit-only service, read-only route. A JUnit guard fails the build if any route, job, admin action, UI or mini-program file starts touching city coin outside the reviewed files, or if the city coin code reaches points, cash, coupons or orders. |
| MON-10 (P2) | **Service ready; CITY wiring left to the coordinator** (brief: do not change the CITY holder model or flows) | `CityCoinService.creditHolder(memberId, placeId, checkInRef)` with idempotency and its own daily cap. Exact wiring below. |

## What changed

- `chengyinhub-admin/sql/migration_20261115_points_governance.sql` (new, idempotent, rollback note at the top): tables `points_award_cap`, `points_programme_budget`, `points_budget_usage`, `city_coin_account`, `city_coin_ledger` (unique `ref_key`); seeds programmes PLAY / VISIT / COMMUNITY (budgets **unset**), daily caps of 500 points (= ¥10) for sources 4, 10, 11, 12, 13; config `points.governance.mode` = ENFORCE, `city.coin.holder_yield` = 10, `city.coin.daily_cap` = 100; menu 积分治理 under 运营规则 with `business:pointsgov:list` / `business:pointsgov:edit` (not granted to any role).
- `chengyinhub-system/.../pointsgov/PointsGovernanceService.java` (new): `admit(memberId, eventType, points)` → ADMITTED / UNGOVERNED / DAILY_CAP / MONTHLY_BUDGET. Daily sum from `ums_member_points_detail` since local midnight (Asia/Shanghai); monthly budget advanced with a conditional `UPDATE … WHERE issued + n <= budget` in the same transaction (no overshoot, released on rollback). Modes ENFORCE / SHADOW (log only) / OFF. Missing tables fail open with an error log, so deploy order does not matter. Also `dashboard(period)`, `updateCap`, `updateBudget` (validated).
- `chengyinhub-system/.../mapper/PointsGovernanceMapper.java` + `mapper/business/PointsGovernanceMapper.xml` (new).
- `chengyinhub-system/.../service/impl/PointsAwardServiceImpl.java`: `award()` asks governance right after `selectPointsForUpdate` (member lock held); refused → returns 0, writes nothing. `awardPublisherSettlement` is untouched (it fails closed by design and is settlement-bound).
- `chengyinhub-system/.../service/CommentPublicationService.java`: first-review reward (event 4) asks governance before crediting; the review is still published when refused. Optional field injection, so existing constructor wiring and tests are unchanged.
- `chengyinhub-system/.../citycoin/CityCoinService.java` (new): `creditHolder` (own `REQUIRES_NEW` transaction, never throws, outcome CREDITED / DUPLICATE / CAPPED / INVALID / UNAVAILABLE), `creditHolderAfterCommit` (waits for the caller's commit), `summary(memberId)`, `dashboard(from, to)`. No spend, exchange, transfer or withdraw operation exists.
- `chengyinhub-system/.../mapper/CityCoinMapper.java` + `mapper/business/CityCoinMapper.xml` (new): credit and read only; the single UPDATE is `balance = balance + #{amount} … AND #{amount} > 0`.
- `chengyinhub-admin/.../controller/api/ApiCityCoinController.java` (new): `GET /api/city-coin/me` → `{balance, todayEarned, dailyCap, nonCashable: true, history[≤50]}` for the signed-in member only.
- `chengyinhub-admin/.../controller/business/PointsGovernanceController.java` (new): `GET /business/pointsgov/dashboard?period=yyyy-MM`, `PUT /business/pointsgov/cap`, `PUT /business/pointsgov/budget` (`@PreAuthorize`, `@Log`).
- `chengyinhub-ui/src/api/business/pointsgov.js`, `chengyinhub-ui/src/views/business/pointsgov/index.vue` (new): month picker; four `CyMetricCard`s (issued, redeemed/spent, outstanding liability in yuan, city coin with ¥0 liability); budgets table (unset budgets flagged "未设预算"); daily caps table; edit dialogs. Existing `Cy*` components and tokens only.
- Tests (new, `chengyinhub-admin/src/test/java/com/chengyinhub/business/pointsgov/`): `PointsGovernanceMyBatisTest` (H2 MySQL mode through the real services: caps, shadow/off, budget all-or-nothing, dashboard yuan maths, admin validation, city coin idempotency and daily cap), `CityCoinNoConversionGuardTest`, `PointsAwardGovernanceWiringTest`, `CommentRewardGovernanceWiringTest`.

## How the CITY yield should call the city coin service (for the coordinator)

Inject `com.chengyinhub.business.citycoin.CityCoinService` and, where a holder's yield is settled, call:

```java
cityCoinService.creditHolderAfterCommit(holderMemberId, placeId, checkInRef);
```

- Today the yield is a team score: `CityGameActionService.settleRound` (≈ line 433) does `mapper.addTeamScore(teamId, CityGameRules.settlementScore(countHeld, holdingYield))`. Per the owner decision (individuals hold territory, clubs add up member scores), credit **each held property's individual holder** there (or at whichever check-in settles yield in the reworked flow), keep the team score as the sum.
- `placeId`: the property/point id, ≤ 64 chars (e.g. `boardId + ":" + pointId`).
- `checkInRef`: unique per credit, ≤ 80 chars, stable across retries, e.g. `"round:" + boardId + ":" + teamId + ":" + seq + ":" + propertyId`. The ledger key is `holder:<placeId>:<checkInRef>` (unique), so a retried settlement never pays twice.
- Use `creditHolderAfterCommit` from inside the board-lock transaction: the credit runs only after the game transaction commits, in its own transaction, and can never roll back or block the game. Use `creditHolder` when you need the `CreditResult` (outside a transaction).
- Amount per credit = `city.coin.holder_yield` (10), clipped to `city.coin.daily_cap` (100/member/day). The board's own `holdingYield` stays a score input; say if the coin amount should follow it instead.
- Do not add any other caller without updating `CityCoinNoConversionGuardTest`'s allowlist; that test is the review point.

## Verification

```
cd /home/user/hengyin
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile                      → OK
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -pl chengyinhub-admin -am test \
  -Dtest='PointsGovernanceMyBatisTest,CityCoinNoConversionGuardTest,PointsAwardGovernanceWiringTest,CommentRewardGovernanceWiringTest' \
  -Dsurefire.failIfNoSpecifiedTests=false                                        → 6 + 4 + 2 + 2 passed, 0 failed
Neighbouring suites (same command): B03PointsAwardServiceTest 4, TopicCompletionAwardContextTest 2, GameSloRewardIdempotencyTest 4,
  CommentPublicationServicePoiTest 4, PublisherSettlementPointsDetailMutationGuardTest 5, CreatorIncomeDetail* 6,
  DruidWallForUpdateOfContractTest 3, DruidWallSqlCommentContractTest 2, DeadlockRetryEntryPointContractTest 6,
  AuditStatusTruthConsistencyTest 5, OrganizerActorMutationWiringTest 3, NodePublicVisibilityPredicateContractTest 2 → all passed
  (ContentInteractionMybatisIntegrationTest: 21 skipped, needs MySQL, same as base)
Guard mutation check: a temporary file mentioning city_coin_account in chengyinhub-system made
  CityCoinNoConversionGuardTest.onlyReviewedFilesMentionCityCoin fail; file removed.
python3 -m pytest -q ci --ignore-glob='*mysql_integration*' --ignore=ci/test_assert_it_ran.py
  base be-integ: 29 failed, 1759 passed, 7 errors; branch: identical failure list (diff empty)
cd chengyinhub-ui && node --test "tests/unit/**/*.test.js"   → 528 pass / 30 fail on base and branch, identical failing set
                     node --test tests/e2e/contracts/*.test.js → 36 pass, 0 fail
```

Not verified here: the migration on real MySQL (H2 MySQL mode covers the mapper SQL, not the DDL/menu inserts); the admin page in a browser (no `node_modules`, no backend); the `REQUIRES_NEW` behaviour under a real transaction manager (unit test runs without one).

## Left open

- Direct point credits that bypass `PointsAwardServiceImpl.award` are not yet governed: `CmsRegistrationServiceImpl` (`umsMemberMapper.addPoints` at ≈3455, 3581, 3628, 3805), `OrientScoringServiceImpl` ≈539, `TeamGroupServiceImpl` ≈1990, `MmsMerchantServiceImpl` ≈237 (invite), `ApiBetaGameController` ≈189, and `awardPublisherSettlement` (settlement-bound, fails closed). Each has its own idempotency / funds-recovery rules guarded by other owners; wiring is one line each (`pointsGovernanceService.admit(memberId, eventType, points)` under the member lock, after the source gets a `points_award_cap` row). They still show in the dashboard's issued/liability totals.
- The dashboard's "redeemed" figure is all expense rows (mall, registration deduction, clawbacks), labelled 兑换/扣减.

## Owner actions and questions

1. Run `migration_20261115_points_governance.sql` (read-only preflight first) and grant `business:pointsgov:*` to the operations role.
2. **Set monthly budgets** for PLAY / VISIT / COMMUNITY. They start unset (tracked, not enforced) because no volume data was available; the page flags them.
3. Confirm the 500-points/day per source caps, the city coin yield (10) and its daily cap (100).
4. Mode defaults to ENFORCE; switch `points.governance.mode` to SHADOW first if you want a week of logs before enforcing.
5. No hard-coded secrets found in the touched files.
