# DEFER-COMM（2026-10-24）

范围：社区帖文 / 点赞 / 分享 / IM 域此前 DEFER 的非阻塞项（来源 `docs/delivery/bugfix-20261023/BUG-COMM.md` DEFER 行），按优先级逐项处理，每项一个提交。
分支 `chore/defer-comm-20261024`，PR #593（draft）。无 wxml / wxss 改动。

| id | priority | status | files | test | notes |
|---|---|---|---|---|---|
| B:P1-067 | P1 | FIXED | `chengyinhub-common/.../constant/ValidationMethod.java`、`ApiPlayProgressController.java`、`chengyinhub-xcx/utils/validation-method-labels.js`、`chengyinhub-xcx/pages/play/index.js` | `tests/unit/validation-method-codes-contract.test.js`（新）、`play-mechanisms-contract.test.js`（正则跟随常量）、`ValidationMethodContractTest` | 后端新增 `ValidationMethod.GPS=5`；小程序导出冻结的 `VM` 码值表，play 页 `vm === 0/5/8/9/10` 改用 `VM.*`；契约测试逐个对 Java 常量同名同值。`label()` 中 `case 5` 字面量保留（被既有测试钉住，注释标明 = GPS）。动作台账重建，控件数 5103 不变 |
| C:U-027 | P1 | FIXED | `chengyinhub-xcx/utils/activity-lifecycle-state.js`（新）、`utils/order-status.js`、`subpackageMember/components/cy/scene-play-activity-detail/index.js` | `tests/unit/state-key-vocabulary-contract.test.js`（新）；两份活动详情沙箱测试登记新依赖 | 两套 key 属不同领域（活动档期 camelCase / 订单读模型 snake_case），不改值、不改名（改名会触及 C00 保留的 scene-member-order-detail）。各自给唯一导出 `ACTIVITY_STATE` / `ORDER_STATE_KEY`，契约对后端 `ActivityLifecycle` 与 order-status 实际产出、并断言两套不串味 |
| C:U-042 | P1 | FIXED | `chengyinhub-system/.../domain/community/CommunityReferenceType.java`（新）、`CommunityPostServiceImpl.java` | `CommunityReferenceTypeConsistencyTest`（新）、`CommunityReferenceOptionsWhitelistTest` | 取值差异已由 P0-24（d80aa60a）修掉；本次补唯一真源枚举 + 一致性测试：枚举 = DDL `chk_community_post_reference_type` = Mapper `countReferenceTarget` / `selectReferenceSnapshot` / `selectReferenceOptions` 分支 |
| C:U-074 | P1 | NEEDS_OWNER | — | — | 回填 `sql/base_schema_20260818.sql` 需从生产重新 dump（无生产权限），且与文件头「不要直接改这个文件」约定冲突。「base + 全部迁移」从零建库已有 CI：`.github/workflows/release-migration-rehearsal.yml` + `ci/release_migration_rehearsal.py` |
| B:P2-014 | P2 | FIXED | `chengyinhub-xcx/pages/square/utils/publish/pro-editor-draft.js`（master 已从 utils/publish 迁入广场分包）、`pages/square/components/cy/post-compose/index.js` | `tests/unit/pro-editor-draft.test.js`（新增 2 例）；两份发帖组件测试桩补纯函数 | `draftKeyFor` 新增一等命名空间参数 `{scope, scopeId}`，登记表 `DRAFT_SCOPES`（未登记即抛）；生成的 key 与历史 `pro_editor_draft_topic_square_post_<memberId>` 逐字节一致，已存草稿照常恢复 |
| B:P2-041 | P2 | FIXED | `chengyinhub-system/.../support/AiJsonResponses.java`（新）、`AiCreatorServiceImpl.java`、`AiTopicPlanServiceImpl.java` | `AiJsonResponsesTest`（新）、`AiCreatorServiceImplTest`、`AiTopicPlanServiceImplTest`、`TopicAiPrecheckServiceTest` | 两份 `stripJsonFence` / `parseAiJson` / `PARSE_ERROR_MSG` 合并为一份，行为与文案不变（全仓只有这两份实现） |
| C:U-037 | P2 | PARTIAL | `chengyinhub-system/.../domain/community/CommunityCommandType.java`（新）、`CommunityPostServiceImpl.java` | `CommunityCommandTypeRegistryTest`（新）；`Community*Test` 274 例全绿 | 写回执前用登记表把关（固定命令 / `POST_(UN)ACTION_<类型>` / `<前缀>:<32位hex>` 指纹命令），未登记拒写；现有字面量全部已登记，行为不变。**未做**：DDL CHECK —— 生产已有行的取值无法只读核对，加 CHECK 的迁移可能在上线时失败，需先走迁移只读预检 |
| C:U-047 | P2 | ALREADY_FIXED | — | — | 6 个列（reference_type、post/comment action_type、search document_type / visibility_state、report actor_type）在 Java 侧全部按 String 读写，无 `Enum.valueOf` 或读路径 switch-抛错，不存在「新值反序列化失败」；写入未知值按 400 拒绝属输入校验。小程序不消费 document_type |
| C:U-051 | P2 | NEEDS_OWNER | — | — | 收藏 source 只用于统计归因；允许透传哪些来源值（取值清单、旧接口是否放开）会改变统计口径，需产品定词表 |
| C:U-162 | P2 | NEEDS_OWNER | — | — | 章节 `ending.when` 运行时由 `TopicRouteEngine` 判定（支持 NODE_COMPLETED），而 `GameSessionRuntimeServiceImpl.validateStoryConditions` 校验的是另一套 `story.endings[].conditions` 且拒绝 NODE_COMPLETED，不能直接共享；且 CFC 保存期加严会让已有草稿无法保存（创作者可见），需定词表与存量处理方式 |
| C:U-172 | P2 | PARTIAL | — | `tests/unit/subscribe-template-reconcile-contract.test.js`（新） | 新增前端 `TEMPLATES` ↔ `application.yml subscribe.tpl*` 全量逐项对账（此前只对了合作三类）。发现 `refundRejected` 前端申请授权但后端无对应配置、从不下发，显式列为 FRONT_ONLY 待拍板。运行期 `/api/config/notify-templates` 属新接口，未做 |

## 统计

FIXED 6 · PARTIAL 2 · ALREADY_FIXED 1 · NEEDS_OWNER 3

## 验证

- 小程序全量单测本地：13901 pass / 0 fail（P1-067、U-027 之后）；后续各项跑了相关测试文件 + `lint:scope` + `audit:actions`
- 后端：`mvn -pl chengyinhub-admin test -Dtest=...` 覆盖上表所列测试类
- CI run：见下方

## CI run IDs

全绿 head `938f8acd`（2026-10-24）：

- GitHub CI：40066478989（success）
- Mini followup focused regression：40066478986（success）
- Funds MySQL IT：40066479017（success）
- Isolated authenticated Redis recovery：40066479009（success）

过程中的红灯：40063725286 / 40064176335 的「Mini program static gates」—— UI-GATE-0 的 silentError / 动态 setData 登记按行号锁定（基线文件 C00 保留不可改），本 PR 在 play 页与发帖组件各加一行导致位移；a1e587d6 改为不增行修复，未动基线。
