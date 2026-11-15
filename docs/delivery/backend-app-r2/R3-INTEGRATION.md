# integration/backend-app-r3 — Integration & TEST 8120 Receipts

Base branch: `origin/integration/backend-app-r2` (`c879749c8`)
Target environment: **TEST only** (`http://127.0.0.1:8120`, `/data/workspace/be-int/app`, DB `cy_e2e_r2`). Production untouched; shared migration ledger untouched.

## Wave 1 + Wave 2 (merged `fix/r5-be-infra-stubs`, `fix/play-nodes-500`, `fix/api-smoke-4xx`, `feature/sys-weather`)

| Branch | Source SHA | Merge Commit | Conflicts / Notes | DB Migrations (`cy_e2e_r2`) |
|---|---|---|---|---|
| `origin/fix/r5-be-infra-stubs` | `32b425c33` | `18502bed3` | Clean `--no-ff` | None |
| `origin/fix/play-nodes-500` | `c1c1a1a44` | `af6fe3f82` | Clean `--no-ff` | None |
| `origin/fix/api-smoke-4xx` | `a67acd4c2` | `28deddc26` | Resolved `ApiCartController.java` (`getAppUserId`/`getAppUserName`), combined `ApiCartControllerAppAuthTest.java` (MockMvc 6 routes + 401 check), preserved sections 5 & 6 in `APP-CONTRACT-FIXES.md` | None |
| `origin/feature/sys-weather` | `a11eb2794` | `6bddcd5d9` | Clean `--no-ff` | `migration_20261114_weather_event_rule.sql` applied (`659 -> 660` tables; no ledger changes) |
| `origin/feature/sys-weather` (docs) | `80df276d1` | `b71b3911c` | Clean `--no-ff` (`SYS-weather.md` only; 0 code/SQL diffs vs `a11eb2794`) | No additional SQL; note CI migration gates flag `rollback_20261114_weather_event_rule.sql` (`INVALID_EXECUTABLE_PREFIX`) and missing `20261114_weather_event_rule` entry in `ci/migration-dependencies.json` (kept untouched per no-ledger-changes rule) |

### Verification Receipts (`6bddcd5d9`)

- **Targeted Maven tests (17 classes, `/usr/bin/flock /data/workspace/.mvn.lock`, `CHENGYIN_WEATHER_MYSQL_IT=1 CHENGYIN_MYBATIS_IT_PORT=3330`, `-Duser.timezone=Asia/Shanghai`)**:
  - `ImMediaServiceTest` (10), `MerchantCrmBroadcastServiceImplTest` (14), `PlayerWxacodeServiceImplTest` (8), `E2eTestProvidersProfileTest` (3), `ApiCartControllerAppAuthTest` (2), `ApiCartControllerSettlementTest` (30), `TopicRouteNarrativeProgressTest` (4), `PlayEntryNodesSnapshotRegressionTest` (5), `ChapterFlowWriteServiceTest` (44), `OWN_C06_22Test` (3), `SpoilerIsNotAnswerGateTest` (5), `PlayFrozenContentProjectionTest` (8), `TencentTopicWeatherProviderTest` (3), `TopicWeatherEventServiceTest` (4), `ApiPlayEnvironmentControllerTest` (1), `JourneyRpgP1Test` (10), `WeatherEventRuleMySqlIT` (1).
  - Total: **155 run, 0 failures, 0 errors, 0 skipped** (`BUILD SUCCESS`).
- **Package & cleanup**: `mvn -B -o -T 1 package -DskipTests` (`BUILD SUCCESS`), deployed to `/data/workspace/be-int/app/app.jar`, deleted `target/` (`df -h /data`: `4.6G` available).
- **TEST `8120` live checks**:
  - `GET /api/play/nodes?activityId=992034` -> HTTP `200`, `code: 200`.
  - `GET /api/play/environment?topicId=992001` -> HTTP `200`, `code: 200`.
- **`api-smoke` (`/data/workspace/api-smoke/smoke_runner.py`, 1231 catalog calls)**:
  - Immediate post-deploy pass: `total: 1231, OK: 740, BUSINESS-REFUSAL-EXPECTED: 248, BACKEND-ERROR: 238, SHAPE-MISMATCH: 0, APP-CONTRACT-BUG: 5`.
  - After `seed_4xx_fixtures.py`: `total: 1231, OK: 760, BUSINESS-REFUSAL-EXPECTED: 242, BACKEND-ERROR: 224, SHAPE-MISMATCH: 0, APP-CONTRACT-BUG: 5`.

## Wave 3 (merged `feature/sys-citygame`, `feature/sys-random`, `feature/sys-ainpc`, `fix/api-smoke-500-r2`, `feature/game-types`)

Handover note: the first three merges were made by the previous integrator (Gemini), whose run stopped before testing finished. The remaining two merges, all verification, the deploy and this section were done by the follow-up integrator (Claude) from the same worktree.

| Branch | Source SHA | Merge Commit | Conflicts / Notes | DB Migrations (`cy_e2e_r2`) |
|---|---|---|---|---|
| `origin/feature/sys-citygame` | `eb610901a` | `9aee8361a` | Clean `--no-ff` | `migration_20261123_sys_citygame.sql` applied (5 new tables, 1 `ALTER TABLE city_game_roll` add-column, 2 `sys_menu` rows) |
| `origin/feature/sys-random` | `57027c274` | `6203ff0b1` | Resolved `CityGameActionService.java`, `TopicRouteRuntimeServiceImpl.java` | `migration_20261123_sys_random.sql` applied (4 new tables, `sys_menu` / `sys_config` rows). `bak_sys_random_20261123_rollback.sql` is the rollback script and was NOT run |
| `origin/feature/sys-ainpc` | `36a0a7e85` | `cd7c077fa` | Resolved `E2eTestProvidersProfileTest.java`, `TopicRouteRuntimeServiceImpl.java` | `migration_20261115_sys_ainpc.sql` applied (add-column on `npc_profile` and `merchant_npc_avatar_job`, `sys_menu` / `sys_role_menu` rows) |
| `origin/fix/api-smoke-500-r2` | `83d5cc6f4` | `e18476e40` | Conflicts in `ApiCartController.java` (`orderSettlement` user-name local) and `ApiCartControllerAppAuthTest.java` (controller field + `unauthenticatedCallerThrows401`). Kept the integration side, which is a superset of the branch side; the branch's other changes merged cleanly | None |
| `origin/feature/game-types` | `f9e107746` | `ba7d69fbc` | Clean `--no-ff` (auto-merged `AdvancedGameRuntimeServiceImpl.java`) | None |
| integrator fix | — | `5fd593b5b` | `ci/migration-dependencies.json` layout restored, see "Ledger file" below | None |

Table count in `cy_e2e_r2`: `660 -> 669`. `cy_migration_history` stayed at 0 rows (SQL applied directly with the mysql client, as in Wave 2). All three migrations are additive (`CREATE TABLE IF NOT EXISTS`, guarded `ADD COLUMN`, idempotent menu/config inserts); none touches a financial ledger table.

### Ledger file (`ci/migration-dependencies.json`) — needs an owner decision

- `feature/sys-citygame` added its own entry `20261123_sys_citygame` and, in the same commit, re-serialized large parts of the file (one-line arrays became multi-line; `138 insertions, 492 deletions` of pure layout).
- That layout change broke `CreatorIncomeDetailNoWritePathTest.idempotencyUniqueKeyMigrationIsPresentAndRegistered`, which string-matches the one-entry-per-line layout. It was the only failure in the full suite and it does not fail on any earlier baseline.
- Commit `5fd593b5b` restores the file byte-for-byte to the `integration/backend-app-r2` layout plus the single citygame line. The parsed JSON (keys, key order, values) is identical to what the merge produced, so no declaration was added, removed or changed by the integrator.
- Net difference of this branch against `integration/backend-app-r2` for the file: **one added line** (the citygame entry authored by the feature branch). If the "no ledger changes" rule means the file must be byte-identical to the base, drop that line; the Java suite does not depend on it.
- Not changed, recorded only (both CI gates were run read-only on `5fd593b5b`):
  - `bash ci/migration-safety-check.sh` fails: no dependency declaration for `migration_20261114_weather_event_rule.sql`, `migration_20261115_sys_ainpc.sql`, `migration_20261123_sys_random.sql`.
  - `bash ci/migration-layout-check.sh` fails: `chengyinhub-admin/sql/rollback_20261114_weather_event_rule.sql` has a non-allowed prefix (allowed for helper files: `_precheck_`, `_deploy_`, `bak_`).
  - Two of the new migrations carry a future date in their name (`20261123`; integration date is 2026-11-15).

### Verification Receipts

All Maven runs used `/usr/bin/flock /data/workspace/.mvn.lock mvn -B -o -T 1 ...` with `-DargLine=-Xmx1536m -Dspring.test.context.cache.maxSize=4 -Duser.timezone=Asia/Shanghai` and the TEST MySQL at `127.0.0.1:3330` (`CHENGYIN_MYBATIS_IT_*`, `CHENGYIN_TEST_MYSQL_*`).

- **Targeted tests (`ba7d69fbc`, 38 classes for the merged areas)**: `Tests run: 213, Failures: 0, Errors: 0, Skipped: 7` (`BUILD SUCCESS`). The 7 skips were the env-gated citygame MySQL ITs; they are covered by the next item.
- **Env-gated MySQL ITs (`ba7d69fbc`, `CHENGYIN_CITY_GAME_MYSQL_IT=1 CHENGYIN_SYS_AINPC_MYSQL_IT=1 CHENGYIN_WEATHER_MYSQL_IT=1`)**: `CityGameMysqlIntegrationTest` (3), `SysCityGameMysqlIntegrationTest` (1), `CityR491MysqlIntegrationTest` (3), `SysAinpcMySqlIT` (3), `SysRandomMySqlIT` (1), `WeatherEventRuleMySqlIT` (1) — `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`.
- **Full suite, once for the round (`ba7d69fbc`, all modules)**: `Tests run: 19732, Failures: 1, Errors: 0, Skipped: 710`. The single failure is the layout-sensitive test described above.
- **After the fix (`5fd593b5b`)**: the 10 Java test classes that read `ci/migration-dependencies.json` were rerun — `Tests run: 42, Failures: 0, Errors: 0, Skipped: 0`, including `CreatorIncomeDetailNoWritePathTest` (4). The full suite was not run a second time; the fix commit changes only that JSON file's layout.
- **Package & cleanup**: `mvn -B -o -T 1 package -DskipTests` on `5fd593b5b` (`BUILD SUCCESS`, `chengyinhub-admin.jar` 123,262,965 bytes). All `target/` directories deleted afterwards (`df -h /data`: `1.9G` available).

### TEST `8120` deploy (`5fd593b5b`)

- Procedure: migrations applied, old jar kept as `app/app.jar.prev-6bddcd5d9`, old log as `app/app.log.prev-r3w2-6bddcd5d9`, java process stopped by pid, new jar started through `app/start.sh`. Rollback: stop the process, copy `app.jar.prev-6bddcd5d9` back to `app.jar`, start again (the new tables and columns are additive and can stay).
- Health: `Started RuoYiApplication in 21.671 seconds`; `GET /` -> HTTP `200`.
- Live checks as fixture player `13900000001` (SMS-code login):
  - `GET /api/play/nodes?activityId=992034` -> HTTP `200`, `code: 200`.
  - `GET /api/play/environment?topicId=992001` -> HTTP `200`, `code: 200`.
  - `GET /api/roam/territory/leaderboard`, `/api/roam/territory/my`, `/api/roam/territory/nearby` (new in sys-citygame) -> HTTP `200`, `code: 200`.
- **`api-smoke` (`/data/workspace/api-smoke/smoke_runner.py`, 1231 catalog calls)**:

| Pass | total | OK | BUSINESS-REFUSAL-EXPECTED | BACKEND-ERROR | SHAPE-MISMATCH | APP-CONTRACT-BUG |
|---|---|---|---|---|---|---|
| Wave 2, after seed (for reference) | 1231 | 760 | 242 | 224 | 0 | 5 |
| Wave 3, immediately after deploy | 1231 | 754 | 270 | 202 | 0 | 5 |
| Wave 3, after `seed_4xx_fixtures.py` | 1231 | 776 | 262 | 188 | 0 | 5 |

  - The runner and `param_resolver.py` were updated by the api-smoke worker between the Wave 2 and Wave 3 runs, so the Wave 2 row above is not like-for-like. The like-for-like reference is the latest run on the Wave 2 jar with the current runner (`results.json` of 2026-11-15 11:06 UTC): `total 1231, OK 776, BUSINESS-REFUSAL-EXPECTED 242, BACKEND-ERROR 208, SHAPE-MISMATCH 0, APP-CONTRACT-BUG 5`. Against it, Wave 3 after seeding changes `BACKEND-ERROR 208 -> 188` and `BUSINESS-REFUSAL-EXPECTED 242 -> 262`, with `OK` unchanged at 776. Per endpoint: 20 routes `BACKEND-ERROR -> BUSINESS-REFUSAL-EXPECTED`, 1 route `BACKEND-ERROR -> OK` (`POST /api/roam/session/records`), 1 route `OK -> BACKEND-ERROR`.
  - The one route that got worse is `POST /api/activity/publish` as club `9003`: `biz_code=500`, "已达到当前身份活动数量上限（3）". This is TEST data, not code: every smoke run publishes an activity and the fixture club has reached its quota of 3. It will stay red until those smoke-created activities are cleaned up or the quota fixture is raised.
  - Remaining `BACKEND-ERROR` rows (188) by area: play runtime 43, merchant 26, club 19, city game 16, topics/routes 15, social/friends 8, registration/tickets 8, creator/publishing 5, others 48. By status: HTTP 200 with `code` 500 (93), HTTP 200 with `code` 400 (31), HTTP 200 with `code` 404 (27), HTTP 404 (17), HTTP 400 (10), HTTP 200 with `code` 501 (3), others 7.

### Branch status

Every branch named for this round is merged. Nothing is pending on `origin` as of the Wave 3 deploy (`fix/api-smoke-500-r2` `83d5cc6f4`, `feature/sys-citygame` `eb610901a`, `feature/sys-random` `57027c274`, `feature/sys-ainpc` `36a0a7e85`, `feature/game-types` `f9e107746`).
