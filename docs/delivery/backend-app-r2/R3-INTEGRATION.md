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

## Wave 4 follow-up (CI migration gates, smoke re-check)

Two follow-ups requested by the coordinator after Wave 4. Code change: `e8c893b73` only (CI declarations, two file renames, one test path, one doc path). No backend source changed.

### CI migration gates: both pass

| Gate | Before (`c5a2791d9`) | After (`e8c893b73`) |
|---|---|---|
| `bash ci/migration-layout-check.sh` | exit 1: two `rollback_*.sql` files with a non-allowed prefix | **exit 0**: "689 个 migration_*.sql 全在 chengyinhub-admin/sql/ 第一层", "新增/修改的 SQL 前缀均合规" |
| `bash ci/migration-safety-check.sh` | exit 1: 5 migrations without a dependency declaration, and 4 errors in `ci/test_migration_ledger.py` ("SQL inventory is unsafe", caused by the same two `rollback_*.sql` files) | **exit 0**: unit tests `146 OK` + `57 OK`, "38 个变动的迁移文件均可被正确分句", "本批新增 38 个迁移均被共享台账覆盖,全部依赖边顺序正确;schema 执行器使用同一拓扑序" |

What was changed to get there:

- `ci/migration-dependencies.json`, five added lines, one entry each, existing entries and layout untouched (a script check confirmed that every existing key, value and key order is unchanged and that every named dependency is an existing `migration_*.sql` file):
  - `20261114_weather_event_rule`: `[]` (creates one new table only)
  - `20261115_external_game_monetization`: `[]` (six new tables, no foreign keys, alters nothing)
  - `20261115_external_game_monetization_menus`: `20261115_external_game_monetization`, `20261012_missing_business_menu` (inserts under the `audit-center` menu)
  - `20261115_sys_ainpc`: `npc_profile`, `20261119_merchant_gap`, `20261012_missing_business_menu` (adds columns to `npc_profile` and `merchant_npc_avatar_job`, inserts menus)
  - `20261123_sys_random`: `20261012_missing_business_menu` (new tables plus menus under `supply`)
  - These are the same declarations as on `origin/integration/backend-app-r3-cloud` (`799e00d4e`), so the two integration branches agree. The `20261124_city_numbers_attr` entry was already present.
- Rollback scripts renamed to the allowed helper prefix, contents unchanged, same names as r3-cloud: `rollback_20261114_weather_event_rule.sql` -> `bak_weather_event_rule_20261114_rollback.sql`, `rollback_20261115_external_game_monetization.sql` -> `bak_external_game_monetization_20261115_rollback.sql`. References updated in `WeatherEventRuleMySqlIT` and `SYS-weather.md`. The comment inside `migration_20261114_weather_event_rule.sql` still names the old file; it was left alone so the content of an applied migration does not change.
- Not touched: `ci/migration_ledger.py`, `ci/migration-applied-baseline.txt`, `ci/migration-assertion-supersessions.json`, any migration SQL content, any gitleaks file. Gate step [4/5] (PR declaration) is skipped outside a PR event; a PR for this branch must declare its migrations in the title or body.

### Tests on `e8c893b73`

- Targeted (same 63 classes and switches as the Wave 4 final run, includes `WeatherEventRuleMySqlIT` and the 10 classes that read `ci/migration-dependencies.json`): `Tests run: 684, Failures: 0, Errors: 0, Skipped: 0`.
- Full suite: `Tests run: 19811, Failures: 1, Errors: 0, Skipped: 713`. The failure is `ClubEventNotificationTransactionBoundaryTest.failedLaterClaimReceiptRollsBackEarlierOutboxLease` ("Expected IllegalStateException to be thrown, but nothing was thrown"). It is a timing-dependent test, not an effect of this change: the class passed in the three earlier full runs of this round, the test and `ClubEventNotificationTransactionService` are untouched by Waves 3 and 4, and six immediate re-runs of the class on the same tree all passed (6 run, 0 failures each). Cause: the test inserts an outbox row at the H2 database's `current_timestamp` (sub-millisecond precision) and the service then selects rows due at a Java `new Date()` (millisecond precision); when both fall in the same millisecond the row is "not yet due" and the expected exception is never raised. Left for its owner; a fix would be to insert the row a little in the past.
- Package: `BUILD SUCCESS`, `chengyinhub-admin.jar` 123,465,812 bytes; `target/` deleted afterwards (`1.8G` free).

### Smoke re-check: no backend regression

The question was whether smoke got worse (776 OK / 188 errors -> 755 / 201). Those two rows are different steps of the same procedure, not a before and an after: each smoke round is "pass, then `seed_4xx_fixtures.py`, then pass", and 755 / 201 is the pass before the seed step. Wave 3 gave 754 / 202 at that step. App warm-up plays no part.

All passes below are 1231 calls, columns OK / BUSINESS-REFUSAL-EXPECTED / BACKEND-ERROR / SHAPE-MISMATCH / APP-CONTRACT-BUG.

| Run | Before seed | After seed |
|---|---|---|
| Wave 3 jar `5fd593b5b` (reference) | 754 / 270 / 202 / 0 / 5 | 776 / 262 / 188 / 0 / 5 |
| Wave 4 jar `c5a2791d9`, right after deploy | 755 / 270 / 201 / 0 / 5 | 776 / 262 / 188 / 0 / 5 |
| Wave 4 jar `c5a2791d9`, re-run 10 minutes later (warmed up) | 752 / 270 / 204 / 0 / 5 | 774 / 262 / 190 / 0 / 5 |
| Follow-up jar `e8c893b73`, shared runner | 753 / 270 / 203 / 0 / 5 | 773 / 262 / 191 / 0 / 5 |
| Follow-up jar `e8c893b73`, runner copy that follows paging | — | 779 / 261 / 186 / 0 / 5 |

Per-endpoint diff of the re-runs against Wave 3:

- `POST /api/workshop/purchased/professional-operations/submit` and `/cancel` (club 9003), `OK -> BACKEND-ERROR`, HTTP 400 "hash invalid". Cause is in the smoke runner, not the backend. To build this command the runner reads the fixture draft's revision and payload hash from `POST /api/workshop/v2/purchased/install/targets`, which returns 50 targets per page, newest first, with `hasMore` / `nextBeforeDraftId`. The runner reads only the first page. Every smoke pass creates more drafts for club 9003; there are now 54, and the fixture draft `970301` ("RW TEST installed draft") moved to page 2 between two runs on the same jar. The runner then sends an empty hash and the backend correctly answers 400. `WorkshopService` and `ApiWorkshopController` are unchanged since Wave 3. With a runner copy that follows `nextBeforeDraftId` both routes are `OK` again.
- `POST /api/roam/session/records` flips between `OK` and "采集时间不属于原行程" from pass to pass (already described in Wave 4).
- The paged-copy pass ran after a second seed without a pass in between, so a few state-dependent routes read differently from the reference: five are better (`/api/im/route-share-source`, `/api/public-catalog/info`, `/api/registration/quote`, `/api/topic/info-to-user`, `/api/topic/report`: the seeded topic is still on the shelf), `/api/club/event-ops/series/create` is refused with a different message, and `/api/publisher/identity` hit the rate limiter ("访问过于频繁") because three passes ran back to back.
- None of the changed routes belongs to code merged in Wave 4 (in-game channels, external game monetization, city numbers and attributes), so there was no regression to fix in the backend.

The shared runner in `/data/workspace/api-smoke` belongs to the api-smoke worker and was not edited. The paging change is 7 lines and is saved as `/data/workspace/be-int/r3/w4/smoke_runner-paging.patch`. Until it is applied there (or the smoke-created drafts of club 9003 are cleaned up), the shared runner will keep reporting these two routes as errors.

### TEST `8120` deploy (`e8c893b73`)

Deployed under `/usr/bin/flock /data/workspace/.deploy.lock` at 13:37 UTC: `Started RuoYiApplication in 21.476 seconds`, `GET /` -> HTTP `200`. No migration in this step. The live checks listed for Wave 4 were repeated with the same results (20 routes `code: 200`, plus the expected 403 and 404). Rollback jar is still `app/app.jar.prev-5fd593b5b` (Wave 3).

## Wave 5 (security, acceptance B1–B8, smoke-500 r4)

Merged into `integration/backend-app-r3` on the `work` VPS. Each branch was checked with `git merge-base --is-ancestor` first; none was already contained, and all three were cut from `81db27155`.

| Branch | Tip | Merge commit | Notes |
|---|---|---|---|
| `origin/fix/security-r3` | `2f50c7fe0` | `b09bd87d1` | Clean. 2 Critical + 9 High security fixes and 2 admin fixes. One migration, `migration_20261125_admin_r3_perms.sql` (button permissions under the reward-drop and task-pool pages), declared in `ci/migration-dependencies.json` by the branch (one line, `["20261123_sys_random"]`), with its rollback named `bak_admin_r3_perms_20261125_rollback.sql`. |
| `origin/fix/acceptance-r3` | `fdf15c0ff` | `da7a2e0a5` | Clean. B1–B8 from `qa/acceptance-r3` REPORT.md. No migration. |
| `origin/fix/smoke-500-r4` | `d58cdc2d1` | `82f0bab79` | Two conflicts, both from B3 meeting SMOKE-500-R4 in the roam archive check. Both behaviours kept: `RoamSessionWriteService.validateArchive` keeps the acceptance clock-skew tolerance and clamping, and its "采集时间不属于原行程" refusal now carries code 400. `RoamSessionArchiveTest` keeps both acceptance tests and the smoke `outOfSessionCollectedAtIsA400` test. |
| (integration) | — | `785d70908` | Test only. SMOKE-500-R4 changed the gone/malformed/corrupt replies of `GET /api/roam/share/snapshot` from code 500 to 404 and updated `ApiRoamShareControllerTest`, but the long-run pin `E06_17Test` still expected 500 (2 failures in the full suite). Its three GONE assertions now expect 404; the 503-unavailable and generic-error paths still expect 500. |

### CI migration gates (on `82f0bab79`): both pass

- `bash ci/migration-layout-check.sh`: exit 0 ("690 个 migration_*.sql 全在 chengyinhub-admin/sql/ 第一层", prefixes compliant).
- `bash ci/migration-safety-check.sh`: exit 0 (unit tests 146 OK + 57 OK; "39 个变动的迁移文件均可被正确分句"; "本批新增 39 个迁移均被共享台账覆盖,全部依赖边顺序正确"). Nothing in `ci/`, the ledger or gitleaks files was changed by this integration.

### Tests

All Maven calls ran under `/usr/bin/flock /data/workspace/.mvn.lock` with `-Xmx1536m -Dspring.test.context.cache.maxSize=4`.

- **Targeted (`82f0bab79`)**: 70 classes, the test classes changed by the three branches plus the classes that read `ci/migration-dependencies.json`, and the roam/deregistration neighbours, with the MySQL IT switches of Wave 4: `Tests run: 845, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`. This includes `PrivacyVisibilityExportMySqlIT` (6), `AccountDeregistrationR3ErasureMyBatisTest` (1) and `ExternalGameMonetizationMysqlIntegrationTest` (3) against MySQL.
- **Full suite (`82f0bab79`), incomplete**: `Tests run: 18234, Failures: 2, Errors: 0, Skipped: 686`. The surefire fork was killed with exit 137 (out of memory) in `ClubApplyStateMapperSqlTest` while the VPS was overloaded and unreachable, so about 1.5k tests never ran (Wave 4 ran 19811). The 2 failures were the `E06_17Test` pins fixed in `785d70908`. On the coordinator's instruction the full suite is not re-run on the VPS: `785d70908` was pushed as `integration/backend-app-r3-wave5-candidate` for a cloud full-suite run.
- **Re-check (`785d70908`)**: `E06_17Test`, `ApiRoamShareControllerTest`, `RoamSessionArchiveTest`, `GlobalExceptionHandlerErrorMappingTest`: `Tests run: 44, Failures: 0, Errors: 0, Skipped: 0`.
- Package: `chengyinhub-admin.jar` 123,483,273 bytes from `82f0bab79` (main code identical to `785d70908`). All `target/` directories deleted afterwards (1.6 GB free on /data).

### TEST migration and `8120` deploy

- `migration_20261125_admin_r3_perms` applied to `cy_e2e_r2`: `sys_menu` 346 → 356 (5 `business:rewarddrop:*` + 5 `business:taskpool:*` buttons under menus 2583 and 2584), 691 tables unchanged, `sys_role_menu` unchanged (no TEST role holds the two parent pages), `cy_migration_history` still 0. Applying it a second time changed nothing.
- Deployed under `/usr/bin/flock /data/workspace/.deploy.lock` at 21:43 UTC: `Started RuoYiApplication in 34.201 seconds`, `GET /` → 200 after 36 s. Rollback jar `app/app.jar.prev-e8c893b73` (Wave 4 follow-up). Live spot checks: `login/code` with an `e2e-code-*` code returns a token (B4); multipart `club/list` → 415 `UNSUPPORTED_MEDIA_TYPE` (B8).

### api-smoke (`/data/workspace/api-smoke`, 1231 calls per pass)

Columns OK / BUSINESS-REFUSAL-EXPECTED / BACKEND-ERROR / SHAPE-MISMATCH / APP-CONTRACT-BUG, plus (in brackets) the number of calls answered with business code 500 where the results file of that pass was kept.

| Run | Before seed | After seed | Paged runner copy (after a second seed) |
|---|---|---|---|
| Wave 4 follow-up jar `e8c893b73` | 753 / 270 / 203 / 0 / 5 | 773 / 262 / 191 / 0 / 5 | 779 / 261 / 186 / 0 / 5 (code 500: 96) |
| Wave 5 jar `82f0bab79` | 758 / 296 / 172 / 0 / 5 (code 500: 46) | 770 / 290 / 166 / 0 / 5 (37) | **777 / 289 / 160 / 0 / 5 (37)** |

- Paged pass against Wave 4: 25 routes moved from BACKEND-ERROR to BUSINESS-REFUSAL-EXPECTED and 2 to OK (the SMOKE-500-R4 effect); business code 500 fell from 96 to 37 calls.
- Four routes went from OK to a refusal, all because of TEST state or the runner's own data, not the code: `im/channel/checkin` (the runner sends `checkinDate 2026-11-15`, it is now 11-16 in Asia/Shanghai), and `registration/pay`, `registration/pay/app` and `verify/dyncode/issue` on registration 75, which the `auto-refund` job refunded at 2026-11-16 01:03 (`registration_status 3`, `payment_status 4`). The code behind those refusals is unchanged in Wave 5.
- The pre-seed passes also show the known state-dependent routes (`activity/delete` and `club/dissolve` on already-deleted fixtures, the two `professional-operations` "hash invalid" routes on the shared runner, see Wave 4 follow-up).

### Acceptance (18 flows, `qa/acceptance-r3` `00054f5b0`)

- Full run: 3 of 18 flows pass every step (F01, F08, F17; 0 of 18 on `81db27155`), and F18 passes in its re-run. B1–B8 all behave as fixed on TEST; no new backend defect. Details, per-flow causes and the B1–B8 evidence are in `qa/acceptance/REPORT.md` on that branch.
- Found while running: each api-smoke pass toggles topic 992001 on or off the shelf (`POST /api/topic/update_user_status`) and leaves `im_block` 9004 → 9009. Run acceptance before smoke or restore those two fixtures first; both were restored for this run.

## Wave 7 (F-19 registration list, B10 challenge NPE)

Merged into `integration/backend-app-r3` on the `work` VPS with `--no-ff`. Both branches were cut from `6c1389098` and neither was already contained.

| Branch | Tip | Merge commit | Notes |
|---|---|---|---|
| `origin/fix/registration-list-r3` | `96cad3d5c` | `dfabdb76c` | Clean. Test and receipt only (`qa/REGLIST-R3.md`). No production code: F-19 was not a code regression. The member still comes only from the session token (`getAppUserId()` → `member_id = #{memberId}`), so none of the Wave 5 owner scoping changed. `RegistrationListMemberScopeF19MyBatisTest` pins it. |
| `origin/fix/challenge-npe-r3` | `987026523` | `3f0cdb762` | Clean. B10: `CityMonopolyService.challengeCommand` put `roll == 20 ? 20 : roll == 1 ? 1 : null` into the reply. The mixed int/Integer ternary unboxed null for every roll from 2 to 19. It now uses `natural(roll)`, which is boxed on every branch; the wire shape is unchanged. Plus `CityChallengeNaturalRollTest` (rolls 1–20 and a source guard). No migration. |

### Tests (VPS, targeted only; full suites ran on the cloud agents, see the receipts)

All Maven calls ran under `/usr/bin/flock /data/workspace/.mvn.lock` with `LANG=C.UTF-8 LC_ALL=C.UTF-8` and `-Xmx1536m -Dspring.test.context.cache.maxSize=4`, offline, `-pl chengyinhub-admin -am`.

- Selection: every test class that references `ApiRegistrationController`, `RegistrationQueryApplication`, `CmsRegistrationMapper` or `CityMonopolyService`, plus `CityGameMysqlIntegrationTest` (`CHENGYIN_CITY_GAME_MYSQL_IT=1`).
- Run 1: `Tests run: 3650, Failures: 30, Errors: 5`. All of these came from 8 `business.it.*MySqlIT` classes that are pinned to the GitHub-hosted isolated MySQL runner. Each fails its own precondition in about 1 ms (`expected: <github-hosted>`, `Explicit isolated MySQL variable required: DB_SUFFIX`). They are not related to this merge, and Wave 5 did not select them: RegistrationCallbackIntent, RegistrationSeat, RegistrationTopicFence, PlayHintUnlock, PlayLeadAdmissionFailure, Wp04CoopOrderScope, Wp04PaidHalfState, RegistrationStationFulfillment.
- Run 2 (`3f0cdb762`, those 8 excluded): **`Tests run: 3615, Failures: 0, Errors: 0, Skipped: 328`, `BUILD SUCCESS`**. Included: `RegistrationListMemberScopeF19MyBatisTest` 4/4, `CityChallengeNaturalRollTest` 21/21, `CityMonopolyServiceUnitTest` 7/7, `CityMonopolyRulesTest` 43/43, `CityGameMysqlIntegrationTest` 3/3 against MySQL.
- Package: `chengyinhub-admin.jar` 123,483,311 bytes. `target/` directories deleted afterwards (1.5 GB free on /data).

### TEST `8120` deploy

- Under `/usr/bin/flock /data/workspace/.deploy.lock` at 14:21 UTC: `Started RuoYiApplication in 20.36 seconds`, `GET /` → 200 after 20 s. Rollback jar `app/app.jar.prev-82f0bab79` (Wave 5). The only `Caused by` in the log is the missing WeChat Pay `apiclient_key.pem`, the same as on the previous jar (TEST has no pay config).

### Acceptance (`qa/acceptance-r3`, wave 7)

- Full `run_all.py` run: **17 of 18 flows pass**, and F04's paid path is TEST-SKIP. F09 now passes: a challenge with d20 roll 15 resolved (`city_challenge` outcome WIN), and app.log has no NPE. F06 fails only on app defect A5 (the native client's self check-in sends no `nodeId`); this is not a backend change.
- F-19 re-check as 9004, before and after the deploy: `registration/list` totals match `cms_registration` (29 → 31 after the run's fixtures), all rows are 9004's, and `my-joined` returns 6 items including 992031. Not reproducible; no defect.
- No new defects. Details in `qa/acceptance/REPORT.md` (wave 7) on `qa/acceptance-r3`.

## Wave 8 (merchant onboarding identity)

Merged into `integration/backend-app-r3` on the `work` VPS with `--no-ff`. The branch was cut from `3149dfea5` (the Wave 7 tip) and was not already contained.

| Branch | Tip | Merge commit | Notes |
|---|---|---|---|
| `origin/fix/merchant-identity-r3` | `c18158761` | `5acc166a0` | Clean. `POST /api/merchant/merchant_registration` now takes `realName` + `idCardNumber` + `identityConsent`, and validation runs before anything is written (GB 11643 check digit, PIPL consent). Registration reuses `MemberIdentityService` (AES-256-GCM `v1:` columns, HMAC blind index, `merchant_apply` source) inside the merchant transaction. Read paths go only through `maskedView`: `/api/publisher/identity/status` and the admin `GET /business/merchant/{id}` `ownerIdentity`. No migration. Receipt: `qa/MERCHANT-IDENTITY-R3.md`. |

Review notes:
- No API returns the full number.
- `@Log(title = "商家入驻")` is attached to the private helper `registrationCategoryIds`, so AOP never applies it.
- The DTO has no Bean Validation annotations on the PII fields, and error messages never echo the input.
- No `@RepeatSubmit` is used and no aspect captures the request body.

### Tests (VPS, targeted only; full suites ran on the cloud agent, see the receipt)

All Maven calls held `/usr/bin/flock /data/workspace/.mvn.lock` and ran offline with `LANG=C.UTF-8 LC_ALL=C.UTF-8`, `-Xmx1536m -Dspring.test.context.cache.maxSize=4`, `-pl chengyinhub-admin -am`.

- **Selection:** every test class that references `MemberIdentityService`, `MemberIdentityRequest`, `MerchantRegistrationDTO`, `IdCardUtils`, `ApiMemberIdentityController`, `MmsMerchantController`, `merchant_registration` or `UmsMemberIdentity` (`*MySqlIT` excluded). Also added: the 14 `business.it.*MyBatisTest` classes the cloud container could not run (no MySQL there) and `MerchantIdentityR3Test`. That makes 57 classes.
- **Result: `Tests run: 350, Failures: 0, Errors: 1`.**
  - `MerchantIdentityR3Test` passed 13/13, and all 14 `*MyBatisTest` classes passed against be-int-mysql.
  - The single error is `GenericGameplayHttpE2eIT`. The grep picked it up, but it is a CI-only HTTP E2E IT whose initializer calls `System.setSecurityManager`, which this JDK rejects. It is unrelated to this merge.
- **Package:** `chengyinhub-admin.jar` is 123,485,525 bytes. The `target/` directories were deleted afterwards.

### TEST `8120` deploy

- Deployed under `/usr/bin/flock /data/workspace/.deploy.lock` at 20:25 UTC. `Started RuoYiApplication in 19.68 seconds`, and `GET /` returned 200 after 20 s. Rollback jar: `app/app.jar.prev-3f0cdb762` (Wave 7).
- `CHENGYIN_WITHDRAWAL_PII_KEY` is present in the process (checked for presence only).
- The only `Caused by` in the log is the missing WeChat Pay `apiclient_key.pem`, the same as on the previous jar.
- Live check (17/17):
  - A synthetic, checksum-valid number with area `999999` was submitted by fresh TEST member 990014. The response and the status read were masked (`999***********0016`, `测*`).
  - A bad check digit got `身份证号格式不正确,请核对后重填`.
  - The DB columns hold `v1:` ciphertext (67 and 63 chars).
  - `app.log` and `sys_oper_log` contain no plaintext.

### Acceptance (`qa/acceptance-r3`, wave 8)

Full `run_all.py` result: **17 of 18 flows pass**, the same as Wave 7. F04's paid path is TEST-SKIP. F06 fails only on app A5. No new defects. Details are in `qa/acceptance/REPORT.md` (wave 8).
