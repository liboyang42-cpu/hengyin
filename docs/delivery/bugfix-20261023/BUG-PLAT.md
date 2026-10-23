# BUG-PLAT 上线前 bug 修复（platform / API contracts / shared helpers）

分支 `fix/bug-plat-20261023`，基于 2026-10-23 master（b84ed673）。每个 ID 都在当前 master 上重新读代码后分类。

| id | triage | what changed (file) | test | notes |
|---|---|---|---|---|
| A:P0-17 | OWNER | 不改表；新增写入闸 | `CreatorIncomeDetailWriteGuardTest` | 当前没有任何写入 creator_income_detail 的代码路径（Mapper 只有 select），所以今天不会重复入账。闸：一旦出现 insert/replace 写入而迁移里没有 `idempotency_key` 唯一键，测试变红。**需决策**：创作者收益入账的幂等键口径（按来源单号？退款冲正是否另起一行）与 entry_type 方向，属于资金规则。 |
| A:P0-86 | FIX | `CreatorCenterServiceImpl.apply`、`CreatorProfileMapper.java/.xml`（新增 `resubmitRejectedCreatorProfile`） | `CreatorReapplyAfterRejectTest` | 被驳回（apply_status=2）的用户现在可以改资料重新提交，回到待审；待审/已通过仍不重复受理。SQL 只改 `id + member_id + apply_status=2` 的行。iOS 端开关不在本仓。 |
| B:P1-003 | DEFER | — | — | 两个控制器共用 `/api/gift` 前缀与 Tag 名，但子路径 `/list` 与 `/give` 不冲突；只影响 Swagger 分组，改路径会破坏已发版客户端。 |
| B:P1-034 | DEFER | — | — | `CommunityPostLifecyclePolicy.canView` 在生产代码里没有调用方（只有单测）；负反馈（不感兴趣/少看作者/少看话题）是信息流过滤，不是访问控制，详情页按设计不受它影响。属于注释/口径整理。 |
| B:P1-076 | DEFER | — | — | 手写的成功判断都同时接受数字和字符串 200（`==` 或 `||`），没有找到判错的实例；统一改用 `isBizOk` 是 70 个文件的重构，与其他 lane 冲突面大。 |
| B:P1-078 | FIX | `PlayerNodeProgressMapper.xml`（个人最佳 3 处）、`GameSessionRuntimeMapper.xml`（`insertStationsFromTopic`） | `NodePublicVisibilityPredicateContractTest`（master 上红、本分支绿）；`CityDirectorConsoleMySqlIT` 新增「商家已退出节点不建站」负控；`GameSessionLedgerMySqlFixture` 补退出理由列 | 6 份判据里只有列表/计数 2 份排除了「商家已退出本章供给」的节点。缺的 4 份导致：玩家看不到的那站仍算进「全部完成」分母（个人最佳永远出不来），开局 PREPARE 还会给已退出商家建站。现在 4 处补齐同一排除条件，契约测试要求以后每处判据都带它。没有抽成跨文件 `<sql>` 片段：多个 IT 只单独加载一个 Mapper XML，跨 namespace include 会让它们加载失败。 |
| B:P2-012 | NOT_REPRODUCIBLE | — | — | 小程序的 `/api/comment/list` 已在 `utils/community-post-api.js:215-228` 转到社区帖文 `/posts/{id}/comments`；`replyCommentCount` 没有任何客户端读取。 |
| B:P2-025 | FIX | `ThemeStateKeyService`（作用域白名单只留 run） | `ThemeStateKeyServiceTest.unimplementedScopesAreRejectedAtRegistration` | party/deployment 运行期没有实现，之前收下后静默按 run 跑（作者以为跨队共享）。现在注册/试校验直接拒绝并说明。已注册的旧键不受影响（`updateDefinition` 不允许改作用域，沿用原值）；小程序与内部自动注册都只发 run。 |
| B:P2-032 | NOT_REPRODUCIBLE | 新增跨端闸 | `chengyinhub-xcx/tests/unit/compliance-scene-crossstack.test.js` | 当前小程序所有 docType/scene 组合都在后端白名单内。新增 node 测试解析后端 `DOCUMENT_SCENES`，逐一核对小程序的上报组合（带负控）；以后一边改名另一边没跟，CI 会变红，而不是提现/报名入口静默报「场景不合法」。 |
| B:P2-043 | NOT_REPRODUCIBLE | — | — | 未知 perspective 在 `GameSessionRuntimeServiceImpl.view` 末尾统一抛 `GAME_FORBIDDEN_SCOPE`（fail-closed），其余动作都按 `"PLAYER"/"CLUB"/"MERCHANT".equals` 显式判断；小程序 `utils/game-session-client.js` 总是带合法视角。 |
| C:U-016 | DEFER | — | — | 只是 `asBoolean(true)` 缺常量名/注释，行为本身是 fail-closed。 |
| C:U-039 | DEFER | — | — | 同 U-148。 |
| C:U-062 | DEFER | — | — | 异常码透传到 AjaxResult 业务码，文案照常下发；加工厂白名单/ArchUnit 闸是全仓重构，未找到用户可见的错误流程。 |
| C:U-063 | DEFER | — | — | 列注释过时；「想去」端点尚未存在，新增时不照抄节点开放校验即可。 |
| C:U-078 | DEFER | — | — | 只是列注释写错，类型统一属于重构。 |
| C:U-084 | NOT_REPRODUCIBLE | — | — | 距离计算先 `cast(... as decimal(12,7))` 再算（如 `CmsTopicMapper.xml` 的 `selectNearbyTopics`），varchar 坐标能正常参与计算。 |
| C:U-134 | DEFER | — | — | 无业务码的 ServiceException 在 IM 投票接口回 code=500 + 原文案，客户端照常显示失败原因；属于错误码口径整理。 |
| C:U-148 | DEFER | — | — | 非法 pack_type 按「不过滤」处理是刻意设计（`TemplatePackTypeTest` 锁定）：宁可多给也不给错形态，且兼容将来新增形态的客户端先发版。 |
| C:U-161 | OWNER | — | — | 两道闸已经 fail-closed：写入侧非 0 creatorRate 拒存，入账侧非 0 拒入账；列是 `NOT NULL DEFAULT 0`，NULL 分支实际不可达。**需决策**：是否删掉 creator_rate 列、后台是否隐藏该输入，属于分润规则。 |
| C:U-180 | DEFER | — | — | 枚举适配层是前端重构，没有已知的线上改名。 |

## 计数

- FIX 3：A:P0-86、B:P1-078、B:P2-025
- OWNER 2：A:P0-17、C:U-161
- NOT_REPRODUCIBLE 4：B:P2-012、B:P2-032（加了跨端闸）、B:P2-043、C:U-084
- DEFER 11：B:P1-003、B:P1-034、B:P1-076、C:U-016、C:U-039、C:U-062、C:U-063、C:U-078、C:U-134、C:U-148、C:U-180

## 验证

- 本地：`mvn -pl chengyinhub-admin test -Dtest=CreatorReapplyAfterRejectTest,ApiCreatorCenterControllerTest,CreatorCenterControllerSafetyTest,ThemeStateKeyServiceTest,NodePublicVisibilityPredicateContractTest,CreatorIncomeDetailWriteGuardTest,GameSessionRuntimeMapperSqlTest,PuzzlePersonalBestMapperTest` → 56 个用例全绿。
- `NodePublicVisibilityPredicateContractTest` 在 master 版 Mapper 上红（报出 4 处缺排除），本分支绿。
- `node --test tests/unit/compliance-scene-crossstack.test.js` → 3/3 绿。
- `CityDirectorConsoleMySqlIT` 需要 MySQL，本机无 Docker，交给 CI。
- 没有新迁移、没有新依赖、没有改 C00 保留文件、没有提交重新生成的台账。

## CI

见 PR 检查（run ID 在下方补充）。
