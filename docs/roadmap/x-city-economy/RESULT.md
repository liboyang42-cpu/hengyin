# W7 x-city-economy — backend and console receipt (hengyin)

Branch `feature/r-x-city-economy` from `origin/roadmap/be-integ` (`63bc29f9d`, which already contains `feature/r-x-city-monopoly`). App side: chengyin-app `cloud/r-x-city-economy`.
Owner decision 1 (2026-11-16): City Monopoly pays 积分 only. No money route, no production switch, no visual restyle.

## Issues

| ID | State | Notes |
|---|---|---|
| CITY-7 | fixed (backend part; app part on the app branch) | City Monopoly income now passes `PointsGovernanceService.admit`: rent 71, visitor reward 72, opening credit 73 (no longer paid in 积分), defence compensation 74, refund 75 (recorded, not capped: it returns the player's own fee), chance 76, programme `CITY` (budget unset = tracked). A refused credit keeps its `city_points_flow` row `PENDING` with `hold_reason='GOVERNANCE'`; the 03:03 settle retries held credits first, oldest first. Points detail rows now carry the source as `event_type` (before: 99, which is the **external games** cap source, so City Monopoly income was eating the external-game daily cap). `nearby` and `leaderboard` say `revenueMode: POINTS` on City Monopoly boards; classic territory keeps `CITY_COIN`. |
| CITY-8 | fixed (deviation) | `stipend()` writes `city_member_season.stipend_left` once per season and never touches `ums_member.point`. `debit()` spends it first for **CLAIM, UPGRADE, SHIELD, SCOUT**; `settleSeason` zeroes it; `points()` returns `stipendLeft`, `stipendAmount`, `stipendClaimable`. Endpoint: `@RateLimiter(10/min)`, location consent, and refused (`APP_ATTEST_REQUIRED`) while claims can't pass App Attest. Deviation: challenges do **not** spend it, because a challenge fee becomes the defender's real 积分 (a stipend → friend's 积分 path). No per-call assertion on the stipend itself: it can only be spent through commands that each carry their own assertion. |
| CITY-15 | fixed | New `city_member_debt` (backfilled once from open `city_member_season.debt`); `debitOrDebt` adds to it, `applyCredit` pays it first in any season; the season `debt` column stays as "debt incurred this season". `reviewSignal` claws back settled `YIELD`/`VISIT` of the last `city.monopoly.abuse.clawback_lookback_d` days (new key, default 60) across seasons, and voids rent held by governance. `points().debt` reads the member table. |
| CITY-19 | fixed (validation deviation) | `CityBoardAdminService.updatePointTier`, `upsertDistrict`, `assignDistrict`, `layout`; audit rows `POINT_TIER`, `DISTRICT_CREATE/UPDATE`, `POINT_DISTRICT`; `ownable_count` recount (OPERABLE places only); held places follow a district move; a republish keeps authored tier and district (the old sync rewrote `city_game_point` and lost them); draft payload carries `tier`, `districtId`, `districts`. Console tab 等级与街区. Default district `d1` (全城) backfilled for each board. Deviation: per-change hard limits are the maxima (≤ 10 districts, ≤ 10 places per district, tier 1–4); the minima and the neutral share are whole-board properties that can't hold while authoring step by step, so `layout().issues` reports them (`DISTRICTS_TOO_FEW`, `DISTRICT_TOO_SMALL:<id>`, `NEUTRAL_SHARE_OUT_OF_RANGE`, `PLACES_WITHOUT_DISTRICT`) instead of blocking publish. A tier change applies to new claims; a place already held keeps the tier it was bought at until released or season end. |

## Changes, file by file

- `chengyinhub-system/.../citygame/CityMonopolyService.java`
  - `GOVERNED_EVENT_TYPE`, `UNGOVERNED_KINDS`, `STIPEND_SINKS`, `HELD`, `eventTypeOf`; optional `setGovernance(PointsGovernanceService)`.
  - `applyCredit` (member debt first, member row lock, governance admit, hold on refusal, typed detail row); `creditNow`; `debit` (opening credit first, restored if the balance is short); `debitOrDebt` (member debt); `memberDebtForUpdate`, `addMemberDebt`, `stipendLeft*`.
  - `settle` retries held credits, reports `heldByGovernance` and `releasedFromHold`; `settleFlow` passes kind and ref.
  - `reviewSignal` lookback across seasons; `stipend` sink-bound; `points` adds `heldByLimit`, `debt` (member), `stipendLeft`, `stipendAmount`, `stipendClaimable`, `dayIndex`; claim quote adds `stipendLeft`; `settleSeason` zeroes the opening credit.
- `.../citygame/RoamTerritoryService.java`: `revenueModeFor(boardId)` on nearby and leaderboard.
- `.../citygame/CityBoardAdminService.java`: tier and district authoring, `layout`, `layoutIssues`, `recountOwnable`, draft patching; publish keeps tier and district; `normalizeAndValidatePayload` validates `tier`, `districtId`, `districts`.
- `.../citygame/city-monopoly-defaults.properties`: `city.monopoly.abuse.clawback_lookback_d=60`.
- `.../mapper/CityGameMapper.java` + `CityGameMapper.xml`: `insertPointsEarnDetailTyped` (event type parameter, `create_by='city-monopoly'`).
- `chengyinhub-admin/.../api/ApiCityMonopolyController.java`: stipend rate limit + consent.
- `chengyinhub-admin/.../business/CityBoardAdminController.java`: `GET /boards/{id}/layout`, `PUT /boards/{id}/points/{pointId}/tier`, `PUT /boards/{id}/districts/{districtId}`, `PUT /boards/{id}/points/{pointId}/district` (existing `business:citygame:list/edit` permissions; no new menu).
- `chengyinhub-admin/sql/migration_20261128_city_economy.sql` (new): `city_points_flow.hold_reason` + index, programme `CITY`, caps 71–76, `city_member_season.stipend_left`, `city_member_debt` + backfill, config seed, default district backfill. `ci/migration-dependencies.json` entry.
- Console: `views/business/citygame/layout.vue` (new), tab in `index.vue`, API functions in `api/business/citygame.js`; `monopoly.vue` abuse-confirm copy now says the 60-day cross-season clawback.
- Tests: `CityMonopolyEconomyTest` (19: `creditRefusedByGovernanceStaysPendingAndIsNotPaid`, `admittedCreditIsPaidUnderItsOwnSource`, `refundOfTheChallengeFeeIsNeverCapped`, `settleRetriesHeldCreditsFirst`, `cityMonopolyBoardsSayPoints`, `claimSpendsTheOpeningCreditFirst`, `challengeFeeNeverUsesTheOpeningCredit`, `shortBalanceGivesTheOpeningCreditBack`, `stipendNeverRaisesThePointBalance`, `stipendIsClosedAfterTheOpeningDays`, `stipendExpiresAtSeasonSettle`, `stipendIsRateLimitedAndBehindLocationConsent`, `debtCarriesAcrossSeasons`, `shortfallBecomesMemberDebt`, `clawbackReachesPreviousSeason`, `boardValidationRejectsOutOfRangeDistricts`, `layoutIssuesFollowTheApprovedRanges`, `rentUsesAuthoredTier`, …); `CityMonopolyConfigDefaultsTest` also reads the new seed migration.

## Verification

- `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile` → OK.
- `mvn -B -q -pl chengyinhub-admin -am test -Dtest='CityMonopolyEconomyTest,CityMonopolyEndToEndFixTest,CityMonopolyServiceUnitTest,CityMonopolyConfigDefaultsTest,CityMonopolyRulesTest,CityMonopolySeasonSimulationTest,CityBoardAndTerritoryUnitTest,CityCoinNoConversionGuardTest,RoamTerritoryClaimTargetTest,PlayerMessagePunctuationTest,MemberPrivacyErasureServiceTest,SysJobStaggerPlanTest,TwoNodeSchedulingTest,E2eTestProvidersProfileTest,*PointsGovernance*'` → 130 run, 0 failures, 0 errors.
- Sweep `-Dtest='*City*,*Roam*,*Territory*,*PointsGovernance*,*Points*,PlayerMessagePunctuationTest,MemberPrivacyErasureServiceTest,SysJob*,TwoNode*,E2eTestProvidersProfileTest'` → 729 run, 0 failures, 11 errors, 84 skipped. All 11 errors are MySQL integration tests (`*MySqlIT`, `*MyBatisIT`: `Communications link failure`, no database here), the same 11 as the x-city-monopoly baseline.
- `bash ci/migration-safety-check.sh` → 迁移安全门禁:通过 (after narrowing the district recount, which the gate first refused as an UPDATE without WHERE); `bash ci/migration-layout-check.sh` → 迁移布局门禁:通过.
- Mini-program: not touched.
- Console: not built here (no `node_modules`); the new component only uses existing Element UI parts, `request`, `checkPermi` and `$modal`.

## Not verified / needs the owner

- No live TEST run. Owed: a claim that spends the opening credit, a rent day over the 400 cap (held, then paid the next night), and a clawback across a season boundary.
- Daily caps per member (owner to confirm): rent 400, visitor reward 20, defence compensation 300, chance 30, refund not capped; programme `CITY` budget unset (tracked on the 积分治理 dashboard). Change them in the console 积分治理.
- CITY-8: owner to confirm the amount (300) and that challenges stay excluded.
- CITY-19: owner to tier the launch district; operators replace the backfilled 全城 district with 6–10 real ones. Until then `layout().issues` lists `DISTRICTS_TOO_FEW` and the set bonus effectively needs half the board.
- `city_member_debt` holds only `member_id` and a number, like `city_member_season` (a points ledger kept on account deletion); not added to the erasure. Privacy review may disagree.
- Migration id `20261128_city_economy`; the coordinator should check no other W7 package used it. No new sys_job.

## Related, not fixed here

- CITY-14 (package 3): raw codes, the toll / revenue rows ("访客过路积分 5 pts") and the dice panel in `RoamTerritoryView` are untouched.
- `insertPointsEarnDetail` (event type 99) is no longer called; kept for compatibility.

## Overlap with the UI agent

- `fix/registration-list-r3`: none of its files touched. No `ui/*` branch exists in this repo.
