# W7 x-city-monopoly — backend receipt (hengyin)

Branch `feature/r-x-city-monopoly` from `origin/roadmap/be-integ` (`cbbf7e3d0`). App side: chengyin-app `cloud/r-x-city-monopoly`.
Currency: 积分 only (owner decision 2026-11-16). No money route, no production switch, no visual restyle.

## Issues

| ID | State | Notes |
|---|---|---|
| CITY-1 | fixed | `resolveTarget` accepts `propertyId` (board property) and `territoryId`; server photo evidence (`evidenceId` → `ClaimEvidenceService`) replaces client photo facts; nearby items carry `claimFlow`/`rulesKind`/`tier`/`upgradeLevel`/`decayState`/`districtId`. Also fixed a real NPE: a board point without a POI crashed `resolveTarget` (long/Long ternary unboxing). |
| CITY-2 | fixed (owner choice open) | `AppAttestCityVerifier` on the enrolled native-steps key (signature + strictly increasing counter, no Apple call); `GET /api/city-game/territory/capabilities`; attest-only refusals no longer count toward `PROPERTY_FAILED_LIMIT`; `city.monopoly.claim.attest_mode` = `REQUIRED` (default) / `SIGNAL`. |
| CITY-3 | fixed | map accepts `bbox=` (or the four bounds) and answers `BBOX_REQUIRED`; upgrade/challenge/scout/shield read `territoryId` with the `propertyId` alias; property card accepts a board property id and adds `deedPhotoUrl`, `weeklyVisits`, `estimatedYield`; season adds `dayIndex`, `holdingCount`, `holdingLimit`; revenue adds `eventId`, `createdAt`, `nextCursor`. |
| CITY-4 | fixed | Monopoly check-in answer carries `cooldownActive:false`, `ownerMemberId`, `rentState`, `ineligibleReason`, `visitorPoints`; a replay rebuilds the same camelCase shape (no snake_case row). |
| CITY-5 | fixed (device key open) | `/api/roam/checkin` uses the request's `locAccuracy` when `hAcc` is absent (`LocationSignals.accuracy`); mini-program sends the real fix and `hAcc`, never the map centre or the place. Hashed `deviceKey` not added: owner/privacy decision. |
| CITY-6 | fixed | `cityMonopolyTask` bean + POST_DEPLOY sys_jobs: settle 03:03:44, nightly 03:23:50, season settle 09:07:56 daily (idempotent, settles the previous season once); `revenue.nextSettleAt`; console tab 城市大富翁运营 (re-run buttons with confirm, rule diff, abuse queue). |
| CITY-9 | fixed (convert) | held LEGACY places on City Monopoly boards become City Monopoly properties (nightly, or on first claim/challenge contact); switch `city.monopoly.legacy.convert` (default true); LEGACY rows pay no City Monopoly rent (`LEGACY_TERRITORY`). |
| CITY-10 | fixed | LIVE City Monopoly boards refuse team `ACQUIRE_PROPERTY`/`START_CHALLENGE` (`PROPERTY_IS_CITY_MONOPOLY`, quote and command); the board row is a projection that never overwrites another team's holding (`BOARD_HELD_BY_TEAM` log); a takeover re-projects the new holder. |

## Changes, file by file

- `citygame/CityMonopolyService.java`: `aliasTarget`, `territoryIdOf`, `serverPhoto`, attest helpers (`attestSignalMode`, `attestAvailable`), `adoptLegacy`/`adoptLegacyTerritories`, `capabilities`, `settleDueSeason`, `nextSettleAt`, `liveBoard`; claim command uses server photo facts and consumes the evidence (`consumeForReviewedClaim`) in the claim transaction; `failedHere` excludes attest-only rows; `noRollbackFor = CityGameException` on claim/challenge quotes so an on-contact legacy conversion survives the 409; contract fields listed above; NPE fix.
- `citygame/AppAttestCityVerifier.java` (new), `citygame/CityAttestVerifier.java` (`ready()` default).
- `service/NativeAppAttestService.java`: `verifyCityAssertion` (same key store and counter CAS; deliberately not a proxied nested transaction so a refused assertion does not mark the claim transaction rollback-only).
- `citygame/RoamTerritoryService.java`: nearby fields; check-in answer and `checkinReplay`; `projectBoardHolder`.
- `citygame/CityGameActionService.java`: CITY-10 refusal (quote and command).
- `citygame/CityMonopolyConfig.java` + `citygame/city-monopoly-defaults.properties`: `city.monopoly.claim.attest_mode`, `city.monopoly.claim.photo.server_evidence_required`, `city.monopoly.legacy.convert`.
- `locationtrust/ClaimEvidenceService.java`, `mapper/LocationTrustMapper.java` + XML: `readRow`, `consumeForReviewedClaim` / `consumeEvidenceForReview` (ACCEPT or REVIEW, one use).
- `locationtrust/LocationSignals.java`: `accuracy(fallback)`.
- `web/controller/api/ApiCityMonopolyController.java`: map bbox, `/territory/capabilities`.
- `web/controller/api/ApiRoamController.java`: hAcc fallback.
- `web/task/CityMonopolyTask.java` (new).
- SQL: `migration_20261127_city_monopoly_claim_flow.sql` (3 config seeds), `migration_20261127_city_monopoly_jobs.sql` (3 sys_job, POST_DEPLOY), `migration_20261127_stagger_city_monopoly_jobs.sql` (plan rows); `ci/migration-dependencies.json`; `ci/migration_ledger.py` POST_DEPLOY.
- Console: `chengyinhub-ui/src/views/business/citygame/monopoly.vue` (new), tab in `index.vue`, API functions in `api/business/citygame.js` (existing `business:citygame:list/edit` permissions; no new menu).
- Mini-program: `pages/roam/index.js` `onPoiTap` (real fix + `hAcc`); `tests/unit/roam-native-poi-first-visit-toast.test.js` fixture now gives a real fix, plus two CITY-5 cases.
- Tests: `CityMonopolyEndToEndFixTest` (20 tests: `resolveTargetAcceptsBoardPropertyId`, `resolveTargetAcceptsTerritoryId`, `claimWithAcceptedEvidenceUsesServerPhotoFacts`, `claimWithReviewEvidenceKeepsTheReviewVerdict`, `clientPhotoFieldsIgnoredWhenServerEvidenceIsMissingOrElsewhere`, capabilities, verifier, `legacyRowPaysNoRent`, `legacyTerritoryIsConvertedToAChallengeableProperty`, `checkinReplayAnswersInTheFirstAnswersShape`, `soloClaimDoesNotOverwriteTeamHolding`, `takeoverUpdatesBoardProjection`, `liveBoardRefusesTeamAcquire`, …); `CityMonopolyConfigDefaultsTest` reads the later seed migration too.

## Verification

- `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile` → OK.
- `mvn -B -q -pl chengyinhub-admin -am test -Dtest='CityMonopolyEndToEndFixTest,CityMonopolyServiceUnitTest,CityMonopolyConfigDefaultsTest,CityMonopolyRulesTest,SysJobStaggerPlanTest,TwoNodeSchedulingTest,CityMonopolySeasonSimulationTest'` → 85 run, 0 failures.
- Sweep `-Dtest='*City*,*Roam*,*Territory*,*LocationTrust*,*ClaimEvidence*,*AppAttest*,*NativeStep*,PlayerMessagePunctuationTest,MemberPrivacyErasureServiceTest,SysJob*,TwoNode*,E2eTestProvidersProfileTest'` → 723 run, 0 failures, 11 errors, 74 skipped; all 11 errors are MySQL integration tests (`*MySqlIT`, `*MyBatisIT`) failing with `CommunicationsException` (no database in this container).
- `bash ci/migration-safety-check.sh` → 迁移安全门禁:通过; `bash ci/migration-layout-check.sh` → 迁移布局门禁:通过.
- Mini-program: `node ../ci/prepare-mini-artifacts.js`; `node --test "tests/unit/**/*.test.js"` base 30 failures; branch: see the final line below. `node tests/lint/ui-integrity.js --warn-as-error` exits 1 on base and branch with identical findings (only the backend mapping count differs, +1 for `/territory/capabilities`).

## Not verified / needs the owner or a device

- No live TEST (8120) run: a real phone claim at a lit place (CLAIMED, then `ALREADY_HELD` in plain words) is still owed.
- CITY-2: claims stay closed until App Attest is configured (`NativeAppAttestConfiguration` ready) and the player has an enrolled key, or the owner sets `city.monopoly.claim.attest_mode=SIGNAL` (claims then always go to a person). Owner decision.
- CITY-5: hashed device key (`SHA256(IDFV + "cy-city-v1")`) needs owner and privacy approval; not added.
- CITY-9: converted holders get no inbox notice yet (waits for CITY-11's notifier, package 3). Deviation from the review: the visit clock starts at conversion (`now`), not `COALESCE(last_checkin_at, claimed_at)`, which would have released old holdings at once; conversion runs in Java (nightly / on contact) instead of a SQL migration because the season id is computed from config.
- CITY-10: owner to confirm the team game becomes the weekly dice event only.
- Chance tasks still answer `TASK_VERIFIER_UNAVAILABLE` (no task verifier); `/territory/capabilities` says so.
- sys_job seconds 44/50/56 and migration ids `20261127_*`: the coordinator should check no other W7 package took the same seconds.

## Related, not fixed here

- CITY-7 (package 2): `RoamTerritoryService.REVENUE_MODE` still says `CITY_COIN` on nearby/leaderboard; City Monopoly credits still skip points governance.

## Overlap with the UI agent

- `fix/registration-list-r3`: none of its files touched.
