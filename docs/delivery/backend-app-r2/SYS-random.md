# PKG-SYS-RANDOM (Random System) Delivery Report

- **Backend / Admin Branch**: `feature/sys-random` (based on `32b425c33` / `origin/fix/r5-be-infra-stubs`, atop `origin/integration/backend-app-r2`)
- **App Branch**: `ui/h-sys-random` (based on `52d2e8f10` / `origin/ui/h-r5-build`)
- **Deployment Scope**: Verified on VPS `work` against isolated TEST MySQL (`127.0.0.1:3330`, container `be-int-mysql`) with **no production changes** and **no secrets committed**. Per coordinator instructions (`msg_2273f2fa82b5`, `msg_557467636bc3`, `msg_cbc6b72a982e`), individual feature branches do not overwrite TEST `8120` (`/data/workspace/be-int/app`); `feature/sys-random` is pushed for combined merge & TEST `8120` deployment/live smoke by the backend integrator (`ctx_9e33081df83e`).

---

## 1. Schema & Migrations (Additive, Reversible, No Ledger Changes)

- `chengyinhub-admin/sql/migration_20261123_sys_random.sql`
  - `game_random_audit`: auditable randomness receipts (`domain`, `receipt_id`, `seed_digest`, `ticket`, `total`, `member_id`, `ref_type`, `ref_id`, `outcome_json`) with unique `(domain, receipt_id)`.
  - `node_task_pool`: M3 mystery-box entry pool (`entry_node_id`, `pool_name`, `daily_limit`, `status`).
  - `node_task_pool_item`: weighted candidate tasks (`pool_id`, `node_id`, `weight`, `status`).
  - `node_task_dispatch`: per-member daily dispatch assignment (`pool_id`, `entry_node_id`, `member_id`, `node_id`, `dispatch_date`, `random_receipt_id`, `status`) with unique `(member_id, pool_id, dispatch_date)`.
  - Idempotent seed into `sys_config` (`reward.drop.base.prob.bp = 1500`) and `sys_menu` (`'business/rewarddrop/index'`, `'business/nodetaskpool/index'` under `path='supply'`, `perms=''`).
- `chengyinhub-admin/sql/bak_sys_random_20261123_rollback.sql`: drops the 4 new tables and removes the seeded config/menu rows.
- `ci/migration_ledger.py audit`: `problems: []` (historical migration ledger untouched).

---

## 2. Backend & Endpoints

### 2.1 Shared Randomness Service (`IGameRandomService` / `GameRandom`)
- `chengyinhub-system/.../service/IGameRandomService.java`
- `chengyinhub-system/.../service/impl/GameRandom.java` (`@Service("gameRandom")`, implements `IGameRandomService` & `AdvancedGameRandom`; `SecureRandom` default + deterministic seeded constructor for tests; idempotent receipt persistence in `game_random_audit`).
- Wired into all 6 runtime consumers:
  1. `RewardDropServiceImpl` (`REWARD_DROP`)
  2. `AdvancedGameRuntimeServiceImpl` (`PLAYKIT_COIN`, `PLAYKIT_DICE`, `PLAYKIT_D20`, `PLAYKIT_RANDOM`)
  3. `TopicRouteRuntimeServiceImpl` (`JOURNEY_CHECK`)
  4. `CityGameActionService` (`CITY_DICE`)
  5. `RandomDispatchService` (`MYSTERY_TASK_DISPATCH`)

### 2.2 M3 Random Task Dispatch (`IRandomDispatchService` / `RandomDispatchService`) & App APIs
- `PlayBasicCommandApplication`: attaches `dispatchedTask` during entry/check-in (`checkinOnce`, `scanEntry` when `action=play`) when the entry node has an active `node_task_pool`, keeping daily idempotency in `Asia/Shanghai` timezone and avoiding the previous visit's `node_id` when 2+ active pool items exist.
- `ApiPlayProgressController`:
  - `GET /api/play/random-dispatch/today?nodeId={nodeId}`

### 2.3 Admin APIs (`RewardDropAdminController` & `NodeTaskPoolController`)
All admin endpoints reuse existing `business:topicnode:*` permissions in `@PreAuthorize` and pass `ci/role_permission_contract.py`.

**`RewardDropAdminController` (`/business/reward-drop`):**
- `GET /business/reward-drop/rules/list` (`business:topicnode:list`)
- `GET /business/reward-drop/rules/{id}` (`business:topicnode:query`)
- `POST /business/reward-drop/rules` (`business:topicnode:add`)
- `PUT /business/reward-drop/rules` (`business:topicnode:edit`)
- `DELETE /business/reward-drop/rules/{id}` (`business:topicnode:remove`)
- `GET /business/reward-drop/base-prob` (`business:topicnode:query`)
- `PUT /business/reward-drop/base-prob` (`business:topicnode:edit`)
- `GET /business/reward-drop/preview` (`business:topicnode:query`)
- `GET /business/reward-drop/audit/list` (`business:topicnode:list`)

**`NodeTaskPoolController` (`/business/node-task-pool`):**
- `GET /business/node-task-pool/list` (`business:topicnode:list`)
- `GET /business/node-task-pool/{id}` (`business:topicnode:query`)
- `POST /business/node-task-pool` (`business:topicnode:add`)
- `PUT /business/node-task-pool` (`business:topicnode:edit`)
- `DELETE /business/node-task-pool/{id}` (`business:topicnode:remove`)
- `GET /business/node-task-pool/{poolId}/items` (`business:topicnode:list`)
- `POST /business/node-task-pool/items` (`business:topicnode:edit`)
- `DELETE /business/node-task-pool/items/{id}` (`business:topicnode:remove`)
- `GET /business/node-task-pool/dispatch/list` (`business:topicnode:list`)

---

## 3. Admin UI (`chengyinhub-ui`) & App (`DES-ag3`, `ui/h-sys-random`)

- **Vue Admin UI (`chengyinhub-ui`):**
  - `src/api/business/rewardDrop.js`, `src/views/business/rewarddrop/index.vue`
  - `src/api/business/taskPool.js`, `src/views/business/nodetaskpool/index.vue`
  - `tests/unit/sys-random-admin-contract.test.js`
- **Swift App (`ui/h-sys-random`, functional wiring only using existing components):**
  - `Core/PlayExperienceContracts.swift`: added `PlayRandomDispatchedTask` (`dispatchedTask`) and `PlayRewardDrop` (`reward.drop`) models/formatters.
  - `Core/DoorReferralContracts.swift`: updated entry response parsing (`dispatchedTask`).
  - `App/PlayExperienceView.swift` & `App/FreeExplorationViews.swift`: reveals dispatched task and drop bonus toast using existing feedback banners/surfaces without visual restyling.
  - `Tests/CoreTests/PlayRandomPresentationTests.swift`: unit tests covering dispatch decoding, wire parsing, and reward drop formatting.

---

## 4. Verification Results

### 4.1 Backend Unit Tests (`work`)
Command:
```sh
mvn -o -T 1 -Duser.timezone=Asia/Shanghai -pl chengyinhub-admin -am test \
  -Dtest=GameRandomTest,RandomDispatchServiceTest,RewardDropRuleServiceTest,RandomSystemControllerTest,SysRandomCallersWiringTest,PlayBasicCommandRandomDispatchTest,SysRandomMapperXmlTest,RewardDropServiceTest,RewardDropRuleMapperSqlTest,AdvancedActionJudgementTest,GameplayCheckEngineTest,CityGameRulesTest,PlayScanEntryServiceImplTest,PlayKitRuntimeBranchRandomPredictTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```
- **Result:** `Tests run: 67, Failures: 0, Errors: 0, Skipped: 0` across all 14 test classes (0 failures, 0 errors, 0 skipped).

### 4.2 Real MySQL Integration Test (`work`, TEST MySQL port `3330`)
Command:
```sh
CHENGYIN_MYBATIS_IT_HOST=127.0.0.1 CHENGYIN_MYBATIS_IT_PORT=3330 CHENGYIN_MYBATIS_IT_USER=root CHENGYIN_MYBATIS_IT_PASSWORD='' \
  mvn -o -T 1 -Duser.timezone=Asia/Shanghai -pl chengyinhub-admin -am test \
  -Dtest=SysRandomMySqlIT -Dsurefire.failIfNoSpecifiedTests=false
```
- **Result:** `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` (`SysRandomMySqlIT`, applying `migration_20261123_sys_random.sql` idempotently twice and verifying `game_random_audit`, `node_task_pool`, `node_task_dispatch`, and `reward_drop_rule`/`log` persistence).

### 4.3 Backend CI & Vue Admin Gates (`work`)
- `python3 ci/role_permission_contract.py`: PASS (`658` endpoints, E-3 three-layer contract consistent).
- `python3 ci/migration_ledger.py audit`: PASS (`problems: []`, `managedMigrationCount: 685`).
- `python3 ci/migration_gates.py sql chengyinhub-admin/sql/migration_20261123_sys_random.sql`: PASS.
- `python3 ci/dup_create_table_gate.py`, `python3 ci/forbidden_hosts_gate.py`, `python3 ci/secret-file-gate.py`: PASS.
- `bash ci/migration-safety-check.sh`: PASS (`146` + `57` tests OK).
- `node ci/api-existence-gate.js`: PASS (`A = 0`).
- `node --test tests/unit/sys-random-admin-contract.test.js tests/unit/dead-page-contract.test.js tests/unit/admin-sfc-template-compile-contract.test.js`: PASS (`7` tests, `0` failures).
- `npm run build:prod` (`chengyinhub-ui`): PASS (`exit 0`). (Pre-existing `npm run test:unit` full run had `599 pass, 25 fail` on unrelated specs `cy-data-table-contract`, `shop-day-admin-entry-contract`, `shop-day-node-and-risk-admin-contract`.)

### 4.4 iOS App (`ui/h-sys-random`)
- `swiftc -parse Core/PlayExperienceContracts.swift Core/DoorReferralContracts.swift App/PlayExperienceView.swift App/FreeExplorationViews.swift Tests/CoreTests/PlayRandomPresentationTests.swift`: PASS (`exit 0`).
- `python3 -B tools/check_scaffold.py`: PASS (`4834 Swift source files, 16670 bilingual keys, references, scheme, read-only expected project equivalence`).
- `python3 -B -m unittest Tests/ContractChecks/test_c03_six_mechanism_projections.py Tests/ContractChecks/test_account_marketing_entry_integration.py Tests/ContractChecks/test_app_composition_root.py Tests/ContractChecks/test_business_runtime_wiring.py Tests/ContractChecks/test_allowlist_routes_have_backend.py`: PASS (`40` tests OK).
- Scope note: `tools/current_ci.py check` is deferred to the downstream release manifest refresh (`ui/h-ci-manifest`), and `swift test` / `xcodebuild` was not run (`swiftc` is absent on the Linux VPS; Mac runs `swiftc -parse` only per dispatch constraints). Live HTTP API smoke for the new endpoints will run against the combined `integration/backend-app-r3` deploy on TEST `8120` via `ctx_9e33081df83e`.
