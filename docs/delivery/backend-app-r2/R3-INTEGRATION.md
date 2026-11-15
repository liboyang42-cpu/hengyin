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

### Pending on `origin` (not yet pushed)

- `fix/api-smoke-500-r2`
- `feature/sys-citygame`
- `feature/sys-random`
- `feature/sys-ainpc`
