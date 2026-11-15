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

## Wave 4 (merged `feature/ingame-channels`, `feature/extgame-money`, `feature/city-numbers-attr`)

Continues from `570fe33e5` in the same worktree. Before each merge the branch was checked with `git merge-base --is-ancestor <branch> HEAD`.

`feature/city-numbers-attr` was not on `origin` when the wave started, so the first two branches were merged, tested and deployed on their own (`d6221eb64`). The branch was pushed at 12:58 UTC, during that deploy, and was then merged, tested and deployed as a second step (`c5a2791d9`). Receipts for both steps are below; the final state of TEST is the second one.

| Branch | Source SHA | Merge Commit | Conflicts / Notes | DB Migrations (`cy_e2e_r2`) |
|---|---|---|---|---|
| `origin/feature/ingame-channels` | `280fcf241` | `13455a161` | Conflict in `PlayBasicCommandApplication.java` (two scan check-in return paths). Kept both sides: the integration side's `attachDispatchedTask(...)` and the branch side's `inGameChannelService.postPlayEvent(EVENT_CHECKIN, ...)` | None (uses the existing `im_conversation.remark` column, present on TEST) |
| `origin/feature/extgame-money` | `c71445b6b` | `d6221eb64` | Conflict in `PlayNodeQueryApplication.java` (two optional `@Autowired` fields added at the same place). Kept both `topicWeatherEventService` and `externalGameMonetization` | `migration_20261115_external_game_monetization.sql` (6 new tables) and `migration_20261115_external_game_monetization_menus.sql` (1 `sys_menu` row) applied. `rollback_20261115_external_game_monetization.sql` is the rollback script and was NOT run |
| `origin/feature/city-numbers-attr` | `f8e02dde7` | `c5a2791d9` | Conflicts in `RoamTerritoryService.java` (adjacent fields; kept both `inGameChannelService` and `monopoly`) and `ci/migration-dependencies.json` (see "Ledger file" below) | `migration_20261124_city_numbers_attr.sql` applied: 16 new tables, guarded `ADD COLUMN` on `city_game_board` (+6), `city_game_point` (+3), `roam_territory` (+18), `cms_topic` (+1), and 284 `sys_config` rows (`city.monopoly.*`, `attributes.v2.*`) |
| `origin/feature/game-types` | `f9e107746` | — | Already contained (merged in Wave 3 as `ba7d69fbc`); nothing to do | None |

`cy_e2e_r2` after this wave: tables `669 -> 675 -> 691`; `sys_menu` rows `345 -> 346` (menu `external-game-monetization` under `audit-center`); `sys_config` rows `36 -> 320`. `cy_migration_history` stayed at 0 rows (SQL applied directly with the mysql client, as in earlier waves). All three migrations are additive (`CREATE TABLE IF NOT EXISTS`, guarded `ADD COLUMN`, insert-if-absent seeds); none alters a points or financial ledger table. One of them again carries a future date in its name (`20261124`).

### One-currency rule (积分 only)

- After the first two merges, `git grep -i` for `CITY_COIN`, `city_coin`, `citycoin` and `城币` over the whole tree returned 0 files.
- `feature/city-numbers-attr` already implements the ruling (its commit is titled "统一积分"): amounts are 积分 on `ums_member.point` + `ums_member_points_detail`, there is no coin account, wallet or ledger table, and `city_game_board.currency` defaults to `POINTS`. On TEST, `sys_config` `city.monopoly.currency` is `POINTS`, and `/api/city-game/territory/points`, `/territory/revenue` and `/territory/map` all return `currency: POINTS`.
- The words `CITY_COIN` / `城币` still appear in 5 files of that branch, and were left as they are because each occurrence states or enforces the rule: comments recording the ruling (the migration header, `CityMonopolyConfig.java`, `city-monopoly-defaults.properties`, `CITY-NUMBERS-ATTR.md`) and `CityMonopolyConfigDefaultsTest`, which asserts that the migration creates no `city_coin` object and that a `city.monopoly.currency=CITY_COIN` override is rejected and stays `POINTS`. Renaming the literal in that test would remove the check.
- For the owner's attention, not changed here: `feature/extgame-money` also carries real-money amounts in fen (`currency = 'CNY'`, paid game orders, and a `TEST_MONEY` traffic-reward mode next to `POINTS`). These are cash, not a second virtual currency, and they stay closed unless `chengyin.release.money-payments` / payout is switched on (both are off on TEST: `moneyGateOpen` and `payoutGateOpen` are false in `/api/external-game/creator/summary`).

### Wave 3 full-suite failure

The single Wave 3 failure (`CreatorIncomeDetailNoWritePathTest.idempotencyUniqueKeyMigrationIsPresentAndRegistered`, caused by the `ci/migration-dependencies.json` re-serialization in `feature/sys-citygame`) was fixed by `5fd593b5b`. Wave 3 only re-ran the affected classes after that fix; this wave's full-suite runs are the first complete runs that include it, and both pass (`CreatorIncomeDetailNoWritePathTest`: 4 run, 0 failures).

### Ledger file (`ci/migration-dependencies.json`)

- `feature/ingame-channels` and `feature/extgame-money` do not touch the file.
- `feature/city-numbers-attr` is based on `feature/sys-citygame`, so it carries that branch's multi-line re-serialization and conflicted with the one-entry-per-line layout restored in Wave 3. Resolution: this branch's layout is kept byte-for-byte and only the entry authored by the feature branch is added, as one line: `"20261124_city_numbers_attr": ["20261123_sys_citygame"]`. A script check confirmed that the parsed JSON of the result equals the feature branch's version (keys, key order, values) and differs from the pre-merge version by exactly that one key. Nothing else in the file was edited.
- Net difference of this branch against `integration/backend-app-r2` for the file is now two added lines, both authored by feature branches (`20261123_sys_citygame`, `20261124_city_numbers_attr`). Same owner decision as in Wave 3: if "no ledger changes" means byte-identical to the base, drop those lines.
- CI migration gates, run read-only on `c5a2791d9`, nothing changed. Both still fail, as in Wave 3, and this wave adds to the same lists. `ci/migration-safety-check.sh`: no dependency declaration for `migration_20261115_external_game_monetization.sql` and `..._monetization_menus.sql` (plus the weather, ainpc and random files from Wave 3). `ci/migration-layout-check.sh`: `rollback_20261115_external_game_monetization.sql` has a non-allowed prefix (plus the weather rollback file). Fixing either means adding declarations to the shared ledger or renaming branch-authored SQL, which is outside this integration's rules. `origin/integration/backend-app-r3-cloud` (`0ce399b59`, not merged here) appears to carry such a fix for the Wave 3 files (`799e00d4e`).

### Verification Receipts

All Maven runs used `/usr/bin/flock /data/workspace/.mvn.lock mvn -B -o -T 1 ...` with `-DargLine=-Xmx1536m -Dspring.test.context.cache.maxSize=4 -Duser.timezone=Asia/Shanghai` and the TEST MySQL at `127.0.0.1:3330`. The targeted runs had these switches on: `CHENGYIN_CITY_GAME_MYSQL_IT`, `CHENGYIN_SYS_AINPC_MYSQL_IT`, `CHENGYIN_WEATHER_MYSQL_IT`, `CHENGYIN_R2_MYSQL_IT`, `CHENGYIN_IM_FOUNDATION_B_MYSQL_IT`, `CHENGYIN_IM_SEND_MYSQL_IT` (all `=1`) and `VALIDATED_SOURCE_SHA=<HEAD>`. The full-suite runs used the same environment as the Wave 3 full run (only `CHENGYIN_WEATHER_MYSQL_IT=1`) so the counts are comparable.

| Step | Commit | Targeted tests (run / failures / errors / skipped) | Full suite (run / failures / errors / skipped) |
|---|---|---|---|
| Wave 3, for reference | `ba7d69fbc` | 213 / 0 / 0 / 7 | 19732 / 1 / 0 / 710 |
| Wave 4, first two branches | `d6221eb64` | 606 / 0 / 0 / 0 (57 classes) | 19741 / 0 / 0 / 713 |
| Wave 4, final (with city-numbers-attr) | `c5a2791d9` | 684 / 0 / 0 / 0 (63 classes) | **19811 / 0 / 0 / 713** |

- The full suite was planned once for the wave; it was run a second time because a third branch arrived after the first run, and the final tip should not go out on a partial check.
- Targeted list: the new tests of the three branches (`InGameChannelMySqlIT`, `InGameChannelServiceTest`, `ExternalGameMonetizationMysqlIntegrationTest`, `ExternalGameMonetizationServiceTest`, `AttributeRulesTest`, `CityMonopolyConfigDefaultsTest`, `CityMonopolyRulesTest`, `CityMonopolySeasonSimulationTest`, `CityMonopolyServiceUnitTest`), the IM / roam / city game / play command / external game / registration areas they touch, and the 10 classes that read `ci/migration-dependencies.json`.
- A first attempt of the targeted list on `d6221eb64` reported `606 run, 1 failure`: `ImConversationQueryScopeMySqlIT.explainPlansAreRecordedWithActualSourceIdentityWithoutInventedCostThresholds` asserts that the environment variable `VALIDATED_SOURCE_SHA` equals the checkout's `HEAD`, and the variable was not set. With it set the class passes (7 run). This is a test-environment requirement of an older test, not a regression from the merges.
- The 3 extra skips in the full suite against Wave 3 are the env-gated `ExternalGameMonetizationMysqlIntegrationTest`, which ran in the targeted pass. Classes named `*IT` are not picked up by the default surefire pattern, so `InGameChannelMySqlIT` only runs when named, as it was in the targeted passes.
- **Package & cleanup**: `mvn -B -o -T 1 package -DskipTests` on `c5a2791d9` (`BUILD SUCCESS`, `chengyinhub-admin.jar` 123,465,813 bytes). All `target/` directories deleted after each step (`df -h /data`: `1.8G` available at the end; lowest point during a build `1.4G`).

### TEST `8120` deploy (final: `c5a2791d9`)

- Procedure, both steps: migrations applied, then under `/usr/bin/flock /data/workspace/.deploy.lock`: java process stopped by pid, new jar copied in, started through `app/start.sh`, health polled. The Wave 3 jar is kept as `app/app.jar.prev-5fd593b5b`; logs as `app/app.log.prev-r3w3-5fd593b5b` and `app/app.log.prev-r3w4-d6221eb64-131108`. The intermediate `d6221eb64` jar was not kept (disk). Rollback: stop the process, copy `app.jar.prev-5fd593b5b` back to `app.jar`, start again (the new tables, columns, menu row and config rows are additive and can stay).
- Health: `d6221eb64` at 12:55 UTC, `Started RuoYiApplication in 16.222 seconds`; `c5a2791d9` at 13:11 UTC, `Started RuoYiApplication in 16.829 seconds`; `GET /` -> HTTP `200` both times.
- Live checks on `c5a2791d9` as fixture player `13900000001` (SMS-code login):
  - Unchanged routes: `POST /api/userInfo`, `GET /api/play/nodes?activityId=992034`, `GET /api/play/environment?topicId=992001`, `GET /api/roam/territory/leaderboard`, `GET /api/roam/territory/my` -> HTTP `200`, `code: 200`.
  - In-game channels (new): `POST /api/im/ingame/resolve` with `scope=roam` and with `scope=topic&topic_id=992001` -> `code: 200` (returns `conversationId`, `bizKey`, `title`, `unread`, `memberCount`); `GET /api/im/ingame/summary` for `scope=roam` and `scope=activity&activity_id=992034` -> `code: 200`; `POST /api/im/ingame/resolve?scope=activity&activity_id=992034` -> `code: 403` "报名后即可加入活动频道" (expected: this player is not registered for that activity).
  - External game monetization (new): `GET /api/external-game/creator/summary`, `GET /api/external-game/entitlements/mine`, `GET /api/external-game/nodes/1/binding` -> `code: 200`; `GET /api/external-game/catalog/no.such.game/monetization` -> HTTP `404`, `code: 404`.
  - City monopoly numbers and attributes (new): `GET /api/city-game/territory/points`, `/territory/revenue`, `/territory/map?minLat=..&minLng=..&maxLat=..&maxLng=..`, `/season/current`, `/season/leaderboard`, `GET /api/member/attributes`, `/api/member/attributes/ledger` -> HTTP `200`, `code: 200`.
- **`api-smoke` (`/data/workspace/api-smoke/smoke_runner.py`, 1231 catalog calls)**:

| Pass | total | OK | BUSINESS-REFUSAL-EXPECTED | BACKEND-ERROR | SHAPE-MISMATCH | APP-CONTRACT-BUG |
|---|---|---|---|---|---|---|
| Wave 3, immediately after deploy (for reference) | 1231 | 754 | 270 | 202 | 0 | 5 |
| Wave 3, after `seed_4xx_fixtures.py` (for reference) | 1231 | 776 | 262 | 188 | 0 | 5 |
| Wave 4 `d6221eb64`, immediately after deploy | 1231 | 754 | 270 | 202 | 0 | 5 |
| Wave 4 `d6221eb64`, after `seed_4xx_fixtures.py` | 1231 | 775 | 262 | 189 | 0 | 5 |
| Wave 4 `c5a2791d9` (final), immediately after deploy | 1231 | 755 | 270 | 201 | 0 | 5 |
| **Wave 4 `c5a2791d9` (final), after `seed_4xx_fixtures.py`** | 1231 | 776 | 262 | 188 | 0 | 5 |

  - Per endpoint, the final after-seed pass is identical to the Wave 3 after-seed pass: 0 of 1231 routes changed classification.
  - The one-route difference in the `d6221eb64` after-seed pass was `POST /api/roam/session/records` (player), `biz_code=500` "采集时间不属于原行程". That route is unstable in the smoke runner, not changed by this wave: it returned the same error in the run before Wave 3 and in the first pass of Wave 3, passed in the second pass of Wave 3, failed in three of the four Wave 4 passes and passed in the last. `RoamSessionWriteService`, which raises it, is not touched by any merged branch; it rejects a `collectedAt` outside the session's start/finish window, and the runner sends a timestamp taken around the moment it finishes the session.
  - The new Wave 4 endpoints are not in the 1231-call catalog (it is generated from the app's call sites), so the smoke totals do not cover them; the live checks above do.

### Branch status

Every branch named for this wave is merged or already contained: `feature/ingame-channels` `280fcf241`, `feature/extgame-money` `c71445b6b`, `feature/city-numbers-attr` `f8e02dde7`, `feature/game-types` `f9e107746`. If the cloud agent pushes further commits to `feature/city-numbers-attr` after `f8e02dde7`, they are not included.
