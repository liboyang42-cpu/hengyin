# SYS-weather Delivery Report

- **Backend & Admin Branch (`hengyin`)**: `feature/sys-weather` (based on `fix/r5-be-infra-stubs`, code commit `a11eb2794bb051f40b6a3be88771de90306530e7`)
- **App Branch (`DES-ag7`)**: `ui/h-sys-weather` (based on `origin/ui/h-r5-build`, commit `0a07995a642bf4aaba9f5a730e4ef4e81eef3308`)
- **Target Scope**: `SYS-weather` (`/Users/fanning/chengyin-refs/tasks/E/cloud-systems-audit.md` §2a, §2b, P2). Per coordinator instruction (`msg_ef544edca206`), individual deploys to TEST `8120` are omitted; `feature/sys-weather` is pushed ready for the backend integrator (`integration/backend-app-r3`) combined deploy and live smoke.

## 1. Backend Changes (`hengyin`, `feature/sys-weather`)

1. **Weather Providers & Environment Snapshot**
   - `chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/rpg/TencentTopicWeatherProvider.java`: `@Component @Primary @Profile("!e2e-test") @ConditionalOnProperty(name = "tencent.map.enabled", havingValue = "true")` reusing `TencentMapClient`, resolving city coordinates (24h TTL) + real-time weather (`/ws/weather/v1/?type=now`, 12m success / 3m failure TTL) via in-memory + optional `RedisCache`, and mapping Chinese weather text to `TopicEnvironmentService.WEATHER_VALUES` (`clear`, `cloudy`, `rain`, `snow`, `fog`, `wind`, `storm`).
   - `chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/rpg/E2eTestTopicWeatherProvider.java`: `@Component @Primary @Profile("e2e-test") @ConditionalOnProperty(name = "chengyin.e2e.test-providers", havingValue = "true")` providing offline deterministic weather lookup for TEST.
   - `chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/rpg/TopicEnvironmentService.java`: added read-only `resolveSnapshot(CmsTopic topic, List<String> keys)` shared by `refresh(...)` and weather event evaluation.

2. **Weather Event Rules (`weather_event_rule`) & APIs (Zero Ledger / Money Changes)**
   - **Additive & Reversible SQL**:
     - `chengyinhub-admin/sql/migration_20261114_weather_event_rule.sql`: `CREATE TABLE IF NOT EXISTS weather_event_rule (...)` (`id, topic_id, rule_name, condition_key, condition_values, unlock_node_id, task_label, window_start, window_end, enabled, del_flag, create_time, update_time`).
     - `chengyinhub-admin/sql/rollback_20261114_weather_event_rule.sql`: `DROP TABLE IF EXISTS weather_event_rule;`.
   - **Domain / Mapper / Service**:
     - `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/WeatherEventRule.java`
     - `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/WeatherEventRuleMapper.java` + `chengyinhub-system/src/main/resources/mapper/business/WeatherEventRuleMapper.xml`
     - `chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/rpg/TopicWeatherEventService.java`: `playerEnvironmentView`, `weatherLockedNodes`, `adminGetConfig`, `adminSaveConfig` (syncs `journeyRules.rpg.environment` + atomic rule replacement, supports overnight HH:mm windows and localized lock reasons).
   - **Endpoints**:
     - **Player**: `GET /api/play/environment?topicId={id}` (`chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiPlayEnvironmentController.java`) — authenticated read-only endpoint returning `{ topicId, city, enabled, keys, environment, sourceStatus, fallbackReason?, events }`.
     - **Admin**: `GET /business/topic/{id}/environment` (`business:topic:query`) and `PUT /business/topic/{id}/environment` (`business:topic:edit`) in `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/CmsTopicController.java`, guarded by `topicOperationsScopeAuthorizer`.
     - **Node List Gating**: `chengyinhub-admin/src/main/java/com/chengyinhub/web/service/play/PlayNodeQueryApplication.java` (`finishNodes`) annotates unmatched weather-gated nodes with `weatherLocked: true`, `locked: true`, and `lockReason`.

3. **Tests**
   - `chengyinhub-admin/src/test/java/com/chengyinhub/web/e2e/E2eTestProvidersProfileTest.java` (registered `E2eTestTopicWeatherProvider.class`).
   - `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/support/rpg/TencentTopicWeatherProviderTest.java`
   - `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/support/rpg/TopicWeatherEventServiceTest.java`
   - `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayEnvironmentControllerTest.java`
   - `chengyinhub-admin/src/test/java/com/chengyinhub/business/it/WeatherEventRuleMySqlIT.java`

## 2. Admin UI Changes (`chengyinhub-ui`, `feature/sys-weather`)

- `chengyinhub-ui/src/api/business/topic.js`: added `getTopicEnvironment(id)` and `saveTopicEnvironment(id, data)`.
- `chengyinhub-ui/src/views/business/topic/components/TopicEnvironmentRulesPanel.vue`: environment toggle (`journeyRules.rpg.environment.enabled`), state key selection (`weather`/`daypart`/`weekpart`/`season`), live city preview tags, and `WHEN ... THEN ...` weather/time-window event rule builder.
- Mounted `TopicEnvironmentRulesPanel` in:
  - `chengyinhub-ui/src/views/business/topic/workspace.vue` (`环境条件` tab)
  - `chengyinhub-ui/src/views/business/topic/detail.vue` (`环境条件与天气触发规则` card)

## 3. App Changes (`DES-ag7`, `ui/h-sys-weather`)

- `Core/PlayContracts.swift`: decoded optional `weatherLocked: Bool?` on `PlayNode` (`PlaySessionSnapshot.isLocked` & `lockReason` already surface node lock states).
- `App/CharacterSheetDetailView.swift`: rendered `card.environment` chips using `CYSectionHeader`, `CYCard`, `CYStatusChip`, and `CoreLoopCopy`, with world-voice fallback when weather is `.unknown` (`cloud.slash`).
- `App/PlayNodeDetailView.swift` & `App/PlaySessionView.swift`: added weather-gated node badge/icon and lock reason presentation when `snapshot.isLocked(node)` and `node.weatherLocked == true`.
- `Tests/CoreTests/PlayerCapabilityRoutesTests.swift`: added `testWeatherLockedNodeAndClearWeatherFallback` covering `weatherLocked` decode and `/api/map/weather` `clear` -> `nil` fallback.

## 4. Verification Results

- **VPS `work` Targeted Maven + Real MySQL IT (`127.0.0.1:3330`, under `/usr/bin/flock /data/workspace/.mvn.lock`)**:
  - Command: `CHENGYIN_WEATHER_MYSQL_IT=1 CHENGYIN_MYBATIS_IT_HOST=127.0.0.1 CHENGYIN_MYBATIS_IT_PORT=3330 CHENGYIN_MYBATIS_IT_USER=root mvn -o -B -T 1 -pl chengyinhub-admin -am -DargLine="-Xmx1024m -Duser.timezone=Asia/Shanghai" -Dtest="TencentTopicWeatherProviderTest,TopicWeatherEventServiceTest,ApiPlayEnvironmentControllerTest,E2eTestProvidersProfileTest,JourneyRpgP1Test,WeatherEventRuleMySqlIT" -Dsurefire.failIfNoSpecifiedTests=false test`
  - Result: `Tests run: 22, Failures: 0, Errors: 0, Skipped: 0` (`BUILD SUCCESS`, `43.856 s`), including double-run migration idempotency and rollback verification in `WeatherEventRuleMySqlIT` (`1/1`). Scratch worktree & `target/` cleaned immediately (`4.7G` free on `/data`).
- **Local PlayNode Regression Run (`*PlayNode*,*Nodes*`, JDK 21)**:
  - Result: `Tests run: 33, Failures: 0, Errors: 0, Skipped: 0` (`BUILD SUCCESS`, `02:23 min`).
- **iOS Swift & Contract Verification (`DES-ag7`, `0a07995a642bf4aaba9f5a730e4ef4e81eef3308`)**:
  - `xcrun swiftc -parse Core/PlayContracts.swift App/CharacterSheetDetailView.swift App/PlayNodeDetailView.swift App/PlaySessionView.swift Tests/CoreTests/PlayerCapabilityRoutesTests.swift`: passed (`SWIFTC_PARSE_OK`).
  - `python3 -m unittest Tests/ContractChecks/test_c02_world_party_character.py Tests/ContractChecks/test_view_literal_localization.py Tests/ContractChecks/test_play_answer_review_journey.py`: `Ran 19 tests in 0.723s - OK`.
  - Pre-existing baseline failures noted outside our touched files: `swift test` fails on untouched `Core/PlayDirectorPlanFallback.swift:92`, and `test_category_roam_registration.py` fails on pre-existing baseline file hashes (`.github/workflows/native-ios.yml`, `Resources/Localizable.xcstrings`, `App/GlobalSearchView.swift`).
- **Admin JS Syntax Check**: `node --check chengyinhub-ui/src/api/business/topic.js` passed (`chengyinhub-ui` lacks local `node_modules` for full SFC build check locally).
- **TEST `8120` Deploy / Live Smoke**: Deferred per `msg_ef544edca206` to the combined backend integrator (`integration/backend-app-r3`) deploy (`chengyinhub-admin/sql/migration_20261114_weather_event_rule.sql` included for additive migration).
