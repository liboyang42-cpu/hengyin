# PKG-SYS-CITYGAME Delivery & Spec of Record (`feature/sys-citygame`)

- **Hengyin branch**: `feature/sys-citygame` (based on `origin/fix/r5-be-infra-stubs`, `32b425c33`)
- **App (`DES-ag4`) branch**: `ui/h-sys-citygame` (`336137aa8`, based on `origin/ui/h-r5-build`, `52d2e8f10`)
- **Database**: MySQL TEST `127.0.0.1:3330` (`cy_e2e_r2`), migration `chengyinhub-admin/sql/migration_20261123_sys_citygame.sql`
- **Deploy handoff**: Per coordinator direction (`msg_d5a6a9c384d9`), this branch was **not** deployed individually to TEST `:8120`; pushed to `origin/feature/sys-citygame` for `ctx_9e33081df83e` to merge into `integration/backend-app-r3` and deploy the combined build.

---

## 1. Owner Decision & Architecture Convergence (`msg_6de66e0ac5cb`)

Territory gameplay is merged **into** the CITY board (`city_game_board` / `city_game_point` / `city_game_property` / `roam_poi` / `roam_territory`):

1. **Unified map & spaces (`roam_poi <-> city_game_point <-> city_game_property`)**: Board tiles correspond to real Roam POIs via `city_game_point.place_id` and `roam_territory.poi_id` / `board_id` / `property_id` / `point_id`. Fog-of-war exploration and territory sit on the same underlying map layer.
2. **Proof of claim (photo + GPS geofence)**: Players claim an unclaimed/unprotected mapped space using a geotagged photo (`photoUrl`) plus GCJ-02 GPS check inside the space radius (`RoamTerritoryService.claim`), syncing `city_game_property` (`PROTECTED` + `SLOT` + `TERRITORY_CLAIMED_PHOTO`). Staff-code / arrival flow remains intact in `CityArrivalService`.
3. **In-game POINTS revenue only (`revenue_mode = 'POINTS'`)**: Visitor check-ins via `/api/roam/territory/checkin`, `/api/roam/checkin`, or `CityArrivalService` (`CONFIRM`) settle owner revenue exclusively in in-game `ums_member.point` + `ums_points_detail`. Zero financial ledger changes, wallets, or money payouts.
4. **Protection windows, seasons, anti-cheat & leaderboard**: Timed `protected_until` enforcement, 24h visitor check-in cooldown, GPS plausibility anti-cheat (`PLAUSIBLE` / `FLAGGED` / `BLOCKED` audited independently of `@Transactional` rollbacks), and season leaderboards (`/api/roam/territory/leaderboard`, `/business/city-game/territories/leaderboard`).

---

## 2. Backend Changes (`hengyin`)

### 2.1 Database Migration (`chengyinhub-admin/sql/migration_20261123_sys_citygame.sql`)
- Registered in `ci/migration-dependencies.json` under `"20261123_sys_citygame"` depending on `["20261113_gaps_a_city_game", "20261114_gaps_a_city_r491", "20261121_admin_review_queue_menus"]`.
- **Additive tables & guarded columns**:
  - `city_game_board_draft`: mutable admin board draft (points/tiles, properties, tolls, challenges, rounds, dice faces/weights, published version reference).
  - `city_game_board_audit`: immutable admin & runtime audit log (`BOARD_DRAFT_SAVED`, `BOARD_PUBLISHED`, `BOARD_FROZEN`, `UNIFIED_DICE_ROLL`, `MERCHANT_OPS_REVIEW`).
  - `city_game_roll` (guarded `ALTER TABLE` columns): `source_path`, `random_ticket`, `total_weight`, `previous_position`, `new_position`, `landed_property_id`, `before_units`, `after_units`, `board_version`.
  - `roam_territory`, `roam_territory_checkin`, `roam_territory_audit`: merged territory ownership, 24h cooldown check-ins, and out-of-transaction anti-cheat/moderation audit log.
  - Idempotent `sys_menu` entries under `path=roam` (`component=business/citygame/index`, perms `business:citygame:list`, `business:citygame:edit`).
- Verified zero financial ledger table edits.

### 2.2 Unified Server-Authoritative Dice Path & Fairness
- `CityGameRules.java`: added weighted dice helpers (`totalWeight`, `faceCount`, `weight`, `indexForTicket`, `probabilityPreview`).
- `CityGameActionService.java` & `CityActionBudgetService.java`: merged `ROLL_ROUND`, `budgetRoll`, and the direct unified endpoint into `CityGameActionService.executeUnifiedDiceRoll(...)`, advancing `city_game_team.track_position`, inserting `city_game_roll` + `city_game_board_audit`, emitting `TEAM_MOVED`, updating action budget, and aligning `resultPolicy = "DICE_UNIFIED_TRACK_ACQUISITION_EXPLICIT_CONFIRM"`, `movementStatus = "AVAILABLE"`, `effectsStatus = "APPLIED"`.
- `CityMerchantValueService.java`: computes `runtimeApplied = "APPLIED".equals(row.get("status"))`.
- `CityFairnessService.java` & `CityGameMapper.xml`: derives `merchantVerifications` / `merchantVerificationMillis` from `city_arrival_evidence` + `city_arrival_challenge` and trims residual `coverageGaps` to `["challengeCompensationExits", "gpsFalseRejections", "safetyTickets"]`.

### 2.3 Endpoints Added / Updated

| Layer | Method & Path | Controller / Service | Purpose |
| :--- | :--- | :--- | :--- |
| Player CITY | `POST /api/city-game/dice/roll` | `ApiCityGameController` / `CityGameActionService` | Unified server-authoritative dice roll with idempotency & board track movement |
| Player CITY | `GET /api/city-game/dice/audit` | `ApiCityGameController` / `CityGameActionService` | Team dice roll audit history |
| Player Territory | `GET /api/roam/territory/nearby` | `ApiRoamTerritoryController` / `RoamTerritoryService` | Unified POI + CITY board space + territory query |
| Player Territory | `POST /api/roam/territory/claim` | `ApiRoamTerritoryController` / `RoamTerritoryService` | Photo + GCJ-02 geofence claim synced to `city_game_property` |
| Player Territory | `POST /api/roam/territory/checkin` | `ApiRoamTerritoryController` / `RoamTerritoryService` | Visitor check-in crediting owner in-game `POINTS` only |
| Player Territory | `GET /api/roam/territory/leaderboard` | `ApiRoamTerritoryController` / `RoamTerritoryService` | Season points leaders & top territories |
| Player Territory | `GET /api/roam/territory/my` | `ApiRoamTerritoryController` / `RoamTerritoryService` | Viewer claimed territories |
| Check-in Hooks | `POST /api/roam/checkin`, `CityArrivalService` (`CONFIRM`) | `ApiRoamController`, `CityArrivalService` | Credits territory owner `POINTS` when checking in on a mapped space |
| Admin Console | `GET /business/city-game/boards` | `CityBoardAdminController` / `CityBoardAdminService` | List CITY boards |
| Admin Console | `GET /business/city-game/boards/{boardId}` | `CityBoardAdminController` / `CityBoardAdminService` | Board detail + draft + points/properties/challenges/rounds/POIs |
| Admin Console | `POST /business/city-game/boards/dice/preview` | `CityBoardAdminController` / `CityBoardAdminService` | Dice weight probability preview (`CityGameRules.dice`) |
| Admin Console | `POST /business/city-game/boards/draft` | `CityBoardAdminController` / `CityBoardAdminService` | Save draft tracks/tiles/properties/tolls/challenges/rounds/dice |
| Admin Console | `POST /business/city-game/boards/{boardId}/publish` | `CityBoardAdminController` / `CityBoardAdminService` | Validate and publish draft (`BOARD_PUBLISHED` audit) |
| Admin Console | `POST /business/city-game/boards/{boardId}/freeze` | `CityBoardAdminController` / `CityBoardAdminService` | Freeze board (`BOARD_FROZEN` audit) |
| Admin Console | `GET /business/city-game/boards/{boardId}/audit` | `CityBoardAdminController` / `CityBoardAdminService` | Admin & dice audit feed |
| Admin Console | `GET /business/city-game/merchant-ops/queue` | `CityBoardAdminController` / `CityBoardAdminService` | Pending merchant-ops request queue |
| Admin Console | `POST /business/city-game/merchant-ops/review` | `CityBoardAdminController` / `CityBoardAdminService` | Approve/reject merchant-ops item |
| Admin Console | `GET /business/city-game/fairness/report` | `CityBoardAdminController` / `CityBoardAdminService` | Fairness summary + latest rolls |
| Admin Console | `GET /business/city-game/territories` | `CityBoardAdminController` / `RoamTerritoryService` | Territory moderation list |
| Admin Console | `POST /business/city-game/territories/{territoryId}/status` | `CityBoardAdminController` / `RoamTerritoryService` | Update territory status / tolls / protection |
| Admin Console | `GET /business/city-game/territories/audit` | `CityBoardAdminController` / `RoamTerritoryService` | Territory anti-cheat & admin audit log |
| Admin Console | `GET /business/city-game/territories/leaderboard` | `CityBoardAdminController` / `RoamTerritoryService` | Admin view of season territory leaderboard |

---

## 3. Admin UI (`chengyinhub-ui`)

- `chengyinhub-ui/src/api/business/citygame.js`: Axios API client wrappers for all `/business/city-game/*` endpoints.
- `chengyinhub-ui/src/views/business/citygame/index.vue`: Vue 2 + Element UI board workbench featuring:
  - 棋盘编排与发布 (`roam_poi` picker, tile/property/toll/challenge/round editor, weighted dice preview, draft save/publish/freeze),
  - 商家运营审批 (merchant-ops review queue),
  - 公平性与骰子审计 (fairness metrics + unified dice audit table),
  - 领地与赛季排行 (territory moderation, anti-cheat log, season points leaderboard).

---

## 4. iOS App Functional Wiring (`DES-ag4`, `ui/h-sys-citygame`)

- `Core/CityActionBudgetContract.swift` & `Core/CityPlayerRulesContracts.swift`: tolerate `"AVAILABLE"` `movementStatus`, `"APPLIED"`/`"COMMITTED"` `effectsStatus`, `"DICE_UNIFIED_TRACK_ACQUISITION_EXPLICIT_CONFIRM"`, and optional track fields (`previousPosition`, `newPosition`, `landedPropertyId`) on `CityActionBudgetResult`.
- `Core/CityGameHTTPService.swift`: registered the 7 player endpoints in `CityGameProductionLease.paths` (admitted automatically by `ChinaReviewedRouteCapability`) and added `rollUnifiedDice(...)` and `diceAudit(...)`.
- `Core/RoamTerritoryContracts.swift` & `Core/RoamTerritoryService.swift`: typed request/response contracts and live HTTP client (`nearby`, `claim`, `checkin`, `leaderboard`, `myTerritories`).
- `App/RoamTerritoryView.swift`, `App/AppSession.swift`, `App/ReviewedCityGameLease.swift`, `App/RoamBrowserView.swift`, `App/QuestifyApp.swift`, `App/MPReviewedPageScreen.swift`: functional wiring using existing `CY*` design system components, `RetainedImageSelectionView`, and `RoamDeviceFixGCJ` (no visual restyling).
- `Tests/CoreTests/RoamTerritoryTests.swift` & `Questify.xcodeproj/project.pbxproj`: unit coverage and target registration.

---

## 5. Verification Results

- **SQL & Migration Gates** (`work`):
  - `python3 ci/migration_gates.py sql chengyinhub-admin/sql/migration_20261123_sys_citygame.sql` -> exit `0`
  - `bash ci/migration-safety-check.sh` -> exit `0` (`Ran 146 tests ... OK`, `Ran 57 tests ... OK`)
- **Backend Unit + MySQL Integration Tests** (`work`, MySQL TEST port `3330`, `mvn -o -T 1 -pl chengyinhub-admin -am test -Dsurefire.failIfNoSpecifiedTests=false` under `/usr/bin/flock /data/workspace/.mvn.lock`):
  - `CityGameRulesTest`: `9` run, `0` failed, `0` errors, `0` skipped
  - `CityBoardAndTerritoryUnitTest`: `2` run, `0` failed, `0` errors, `0` skipped
  - `CityR491MysqlIntegrationTest`: `3` run, `0` failed, `0` errors, `0` skipped
  - `SysCityGameMysqlIntegrationTest`: `1` run, `0` failed, `0` errors, `0` skipped
  - `CityGameMysqlIntegrationTest`: `3` run, `0` failed, `0` errors, `0` skipped
  - `MerchantCityValueReportServiceTest`: `8` run, `0` failed, `0` errors, `0` skipped
  - **Total**: `Tests run: 26, Failures: 0, Errors: 0, Skipped: 0` (`BUILD SUCCESS`; `target/` removed afterwards, `4811 MB` free on `/data`).
- **Admin UI Syntax Check** (`work`): `node` verified `chengyinhub-ui/src/views/business/citygame/index.vue` `<script>` block and `chengyinhub-ui/src/api/business/citygame.js` (`ADMIN_UI_SYNTAX_OK`; full Vue build deferred to UI/integration build since `/data` has no `node_modules`).
- **iOS (`DES-ag4`) Verification**:
  - Mac `swiftc -parse` across all 12 modified/added `.swift` files -> `SWIFTC_PARSE_OK`
  - `plutil -lint Questify.xcodeproj/project.pbxproj` -> `OK`
  - Python `Tests/ContractChecks` (11 CITY/roam/gating/localization/runtime-wiring check files) -> `Ran 61 tests in 99.923s - OK`
  - Note: VPS (`work`) does not have a `swift` toolchain installed, so Linux `swift test` was not run on VPS; live TEST `:8120` API smoke for the newly added routes awaits `ctx_9e33081df83e` deploying `integration/backend-app-r3` per `msg_d5a6a9c384d9`.
