# SYS-ainpc Delivery Report

## 1. Scope & Branches
- **Backend / Admin / SQL Repo (`hengyin`)**: branch `feature/sys-ainpc` (based on `32b425c33`), pushed to `origin/feature/sys-ainpc`.
- **iOS App Repo (`chengyin-app`)**: branch `ui/h-sys-ainpc` (based on `origin/ui/h-r5-build` `52d2e8f10`), local commit `85937f803`.
- **Deploy Rule Note (`msg_889457ff9093`, `msg_a831edfa64e6`)**: Per coordinator directive, no individual TEST `8120` deploy was performed; `feature/sys-ainpc` is ready for the backend integrator (`ctx_9e33081df83e`) to merge into `integration/backend-app-r3`, run `chengyinhub-admin/sql/migration_20261115_sys_ainpc.sql`, and smoke against combined `8120`.

## 2. Backend & Admin Deliverables (`hengyin`)
### 2.1 Player AI NPC Dialogue (`POST /api/ai/npc/dialogue`)
- **Controller / DTO**: `ApiAiNpcController`, `NpcDialogueReq` (`runId`, `topicId`, `activityId`, `npcId`, `utterance`, optional `routeActionId`).
- **Gateway Routing & Rate Pool**: `AiSceneRouter` (`npc_dialogue -> deepseek`), `AiGatewayServiceImpl` (`isDialogue` + `RATE_GROUP_NPC`).
- **TEST-only Stand-in**: `E2eTestNpcDialogueResponder` gated by `@Profile("e2e-test")` + `@ConditionalOnProperty(name = "chengyin.e2e.test-providers", havingValue = "true")`.
- **Server-Side Authority & State Commit**: `NpcDialogueServiceImpl` + `TopicRouteRuntimeServiceImpl` (`requireNpcDialogueSession`, `findNpcDialogueReplay`, `allowedNpcDialogueKeys`, `commitNpcDialogue`).
  - Validates session ownership/status and active topic NPC scope (`scope_type = 1`).
  - Exposes only registered `relation.*` / `tag.*` keys from `theme_state_key` / frozen snapshot to the model prompt.
  - Re-validates candidate `INC` (`relation.*`, delta clamped to `[-2, 2]`, value bounded by `min_value`/`max_value`, default `[-10, 10]`) and `ADD_TAG` (`tag.*`); ignores/rejects rewards (`reward.*`, items, coupons, badges, xp) and clue/answer leakage keys.
  - Persists atomically via `topic_route_action` (`NPC_DIALOGUE`, idempotent on `routeActionId`) + `topic_route_session` version CAS, returning `{runId, topicId, npcId, routeActionId, reply, effects, relation, stateVersion}`.

### 2.2 E1 Admin Backend & Console (`chengyinhub-ui`)
- **Controller / Service / Mapper**: `CmsThemeNpcController` (`/business/themeNpc/*`), `ThemeNpcAdminService`, `NpcProfile`, `NpcProfileMapper`, `NpcProfileMapper.xml`.
  - Theme NPC CRUD (`scope_type = 1`, `relation_key` binding, `initialRelation` upsert in `theme_state_key`), isolated from `scope_type = 2`.
  - Avatar job review (`/avatarJobs`, `/avatarJobs/{id}/review` supporting `APPROVED` / `REJECTED` with required reason).
  - Voice enrollment consent audit (`/voiceAudits`, `/voiceAudits/{id}/revoke` with CAS on `voice_version`).
  - Read-only provider key status (`/providers`): reports configuration booleans, model, and host only; never returns secret values.
- **Vue UI**: `chengyinhub-ui/src/api/business/themeNpc.js`, `chengyinhub-ui/src/views/business/themenpc/index.vue`.

### 2.3 Database Migration
- **Script**: `chengyinhub-admin/sql/migration_20261115_sys_ainpc.sql` (additive & idempotent; reversible rollback SQL in file header).
  - Adds `npc_profile.relation_key`, `merchant_npc_avatar_job.review_status`, `review_reason`, `review_time`.
  - Seeds `sys_menu` (`business/themenpc/index`, `business:themeNpc:{list,query,add,edit,remove}`) under `供给与发布` / `业务管理` and assigns to role `1`.

## 3. Environment-Backed Provider Configuration (Zero Secrets in Git)
`GET /business/themeNpc/providers` reflects runtime configuration sourced strictly from environment variables / Spring config:
- `CHENGYIN_AI_API_KEY` / `CHENGYIN_DEEPSEEK_API_KEY` (default LLM & vision)
- `CHENGYIN_MINIMAX_API_KEY` (MiniMax provider)
- `DASHSCOPE_API_KEY` / `AI_VOICE_API_KEY` (voice enrollment / STT)
- `MERCHANT_NPC_AVATAR_API_KEY` (merchant 3D avatar provider)

## 4. iOS App Deliverables (`chengyin-app`, `ui/h-sys-ainpc`, commit `85937f803`)
- `Core/JourneyNarrativeContracts.swift`, `Core/JourneyNarrativeService.swift`, `Core/JourneyNarrativeCoordinator.swift`, `App/JourneyNarrativeView.swift`: added fail-closed `JourneyDialogueEffect` validation, `POST api/ai/npc/dialogue` client call, relation `CYStatusChip` badges, and dialogue effect receipts.
- `Core/ShopNPCContracts.swift`, `Core/ShopNPCHTTP.swift`, `Core/ShopNPCCoordinator.swift`, `App/ShopNPCView.swift`: added optional `relation` / `effects` decoding, dialogue call helper, header relation `CYStatusChip` badge, and per-reply effect chips.
- `Resources/Localizable.xcstrings`, `Tests/CoreTests/JourneyNarrativeTests.swift`.

## 5. Verification Results
- **Maven Unit + Real MySQL IT (`127.0.0.1:3330`, `CHENGYIN_SYS_AINPC_MYSQL_IT=1`)**:
  - Command: `mvn -B -o -pl chengyinhub-admin -am -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.useFile=false -Dtest=NpcDialogueServiceTest,ThemeNpcAdminServiceTest,E2eTestProvidersProfileTest,SysAinpcMySqlIT test`
  - Result: **Tests run: 12, Failures: 0, Errors: 0, Skipped: 0** (`E2eTestProvidersProfileTest`: 3, `NpcDialogueServiceTest`: 3, `ThemeNpcAdminServiceTest`: 3, `SysAinpcMySqlIT`: 3). All `target/` directories deleted after run.
- **Vue Admin UI Compilation**:
  - `vue-template-compiler` + `@babel/core` transform on `chengyinhub-ui/src/views/business/themenpc/index.vue` and `chengyinhub-ui/src/api/business/themeNpc.js`: `VUE_CHECK_PASSED: template+script+api OK`.
- **Swift App Checks (`ui/h-sys-ainpc`)**:
  - `swiftc -parse` on modified Swift/test files: exit `0`.
  - `python3 Tests/ContractChecks/test_journey_narrative_workflows.py`: 7 tests ran, OK (1 skipped).
  - `python3 Tests/ContractChecks/check_npc_send_guard.py`: 35 checks PASSED.
  - Inline ShopNPC contract checks: PASS.
  - Note: full `swift test` in `DES-ag10` is blocked by an unrelated pre-existing compile error in `Core/PlayDirectorPlanFallback.swift:92`.
