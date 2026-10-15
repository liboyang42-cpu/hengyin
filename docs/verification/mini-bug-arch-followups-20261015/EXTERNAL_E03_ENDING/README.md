# E03 结局正文与历史快照真实 MySQL 回归交付

CY-HANDOFF: CODEX / E03

状态：**SOURCE_DELIVERED_UNVERIFIED**。Java/javac/Maven/MySQL/Redis/服务/业务验证均 **NOT_RUN**；未运行或等待 CI。只完成源码阅读和静态审阅，不将历史 231 项签收移植到本次新增文件。

- 公共基线：`1e403b135206844a34219753a36e2dee39c7dd59`。
- 工作树：`/workspace/hengyin-extra-e03`；分支：`codex/extra-e03-regression-20261015`。本 worker 不暂存、提交、push 或切分支。
- 控制参考：`CY-COORD-20261015-v1`，交接控制快照 `b8541ad2407d0a360588ab77ad32fe3d1c9eb71e`；已读根 AGENTS 及 EXTERNAL_SIX_HANDOFF、OWNERSHIP、FOLLOWUP_BUG_LANES、BACKEND_CHANGES、COORDINATION。
- 仅新增本 README 与 `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/A03EndingSnapshotMySqlIT.java`。
- **NO_API_CHANGE；TEST_DEPLOYED=NO**。无业务源码、旧测试、共享工具、依赖、迁移、CI、UI 或客户端改动。应用部署、设备、HTTP、真实身份业务读回未验。

## 已有覆盖与本次缺口

| 已有文件（均为 admin 测试源目录下路径） | 已有证据边界 | 本次处理 |
| --- | --- | --- |
| `business/it/JourneyFullFlowMySqlIT.java` | 真 MySQL、真实 mapper、旅程建局/推进/阶段/结局；`wireRuntime` 未注入 `themeStoryService`，fixture 无结局 chapterId 正文作者编辑场景 | 补正文冻结、数据库持久化读回和真实终态归档调用链 |
| `business/it/AdvancedGameRuntimeMySqlIT.java` | JSON 列、owner 唯一键、重启 CAS 不改配置 | 保留；不复制通用数据库约束用例 |
| `business/service/impl/A03EndingSnapshotReadTest.java` | mock chapterMapper；选中冻结章投影、空映射不回退、旧局无键回退 | 把缺口补为真实权威 session 行、真实章 mapper 与公开 ending 服务调用 |
| `business/service/impl/A03EndingWriteCompatibilityTest.java` | `booleanFallbackStillCompiles`；`malformedFallbackCannotBecomeAnUnconditionalEnding` 拒绝字符串、1、null、对象 | 已覆盖 compiler 的 JSON 类型边界，不复制此单测，不扩大内容政策 |
| `business/service/support/A03RuntimeEndingCompatibilityTest.java` | 无 priority 末命中；显式 priority 最大、同分末命中；兜底、冻结配置和未知条件 | 已覆盖纯选择算法；不复制 Theme 算法套件 |

新增正文 fixture 有两个同时命中的 JourneyStory 候选，公开 ending 与终态归档均应保留首命中。Theme 末命中及 priority 仍以原测试为准；没有调整算法、按下标重排或把 configVersion 当 schemaVersion。新增测试不建立第二份会话真源；历史兼容输入直接修改本类 scratch 内既有 `topic_route_session.config_snapshot_json`。

## 新方法与预期

共 **3 个 `@Test` 方法**；显式启用且前提齐全时应执行 3，跳过 0。以下为源码预期，不是运行结果。

| 方法 | 行为与断言 |
| --- | --- |
| `newSessionFreezesBodyAndPublicEndingProjectsOnlyFirstChosenChapter` | 真实 runtime.state 建局；真表确认 88/89 均冻结；作者 SQL 编辑 88 后真实 advance 完成；公开 ending 首命中 88，仍 old chapter/old body，仅 1 个可见块，不含 private/unselected；冻结 JSON 未被完成写回改动，现行章已为 new |
| `persistedMissingChapterDoesNotRefillButLegacyMissingKeyKeepsLiveFallback` | 完成后权威 session JSON 保留 endingChapters 但移除选中 88（89 仍在）或映射为空，公开 ending 章均 null；整键删除才沿旧路径读 new；再删除现行 88 返回 null，不伪造历史正文 |
| `terminalThemeStoryMustUseSessionFrozenBodyAfterAuthorEdit` | 真实 themeStoryService 注入 runtime，启用最小已发布 Theme 配置；建局冻结 old，作者改 new，真实 advance 触发终态；独立 mapper 读持久化 result.story.chapter 应仍 old 且正确投影；公开 ending 读回同一归档 |

第三方法针对下述静态缺陷保留严格断言，**基线预计在 old chapter 断言失败**，未经 Java/数据库运行确认；不放宽为 new、不禁用方法、不以缺少 Spring 装配回避调用链。其余两项亦未执行，不声明通过。

## 已立即报告 C00 的源码线索

触发：存在已发布 Theme runtime 配置及 JourneyStory 结局章；先建局，作者更新该章，随后玩家完成最后节点。

1. `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicRouteRuntimeServiceImpl.java:2881` 的 `syncTheme` 终态分支调用 `freezeThemeStory(session,state)`。
2. 同文件 `:2746` 仅传 topicId/raw/state 给 `themeStoryService.freezeEnding`。
3. `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/PlayJourneyStoryServiceImpl.java:184` 把 session 置 null 调 `evaluateEnding`。
4. 同文件 `:201` → `:230` → `:240` 因没有 session 回退实时 chapterMapper，而不是读取 `config_snapshot_json.endingChapters`。`ending` 在 `:158-162` 优先返回归档 result，后续读取不会纠正错误正文。

失败断言：终态持久化 `themeRuntime.result.story.chapter.name == "old chapter"`，正文必须 `old body`；静态推演会得到作者更新后的 `new chapter/new body`。

最小方案候选（**未实现、待 C00/原写者批准转交**）：内部终态 freezeEnding 传递当前已有 TopicRouteSession，让同一 endingChapter 快照读取逻辑决定正文及历史缺键回退；保留原首命中和公开 API。精确潜在源码范围仅上述两个 impl 文件。不要另建会话/结果真源或扩写内容政策。

这是本域有明确调用链证据的后续候选；尚无第二个经证据支持的独立源码问题，不凑候选数。

## 授权验证方未来命令（当前禁止执行）

用户最新指令为全部不验证，因此以下只供未来获得明确运行授权后的本地验证方使用；Cloud 不执行，当前 budget212 离线入口不支持真库，不套旧驱动、不直连 VPS、不重跑或等待 CI。

环境：源码采用 C00 最终交付完整 SHA，Maven reactor 根目录；独占 MySQL **8.0.x** scratch 实例与 `127.0.0.1` 回环访问，账号有 scratch 建删库权限；同一时间仅一个获准 Maven 容器。Java 21/Maven 3.9.11 可沿既有获准验证环境，项目编译目标仍 Java 8；需要依赖缓存和真实 MySQL JDBC 驱动（仓库既有依赖），无 Redis/provider/HTTP 前提。

必须设置：

- `CHENGYIN_ENDING_MYSQL_IT=1`：唯一 opt-in；不设置会由 JUnit 条件禁用，禁用不当通过。
- `CHENGYIN_MYBATIS_IT_HOST=127.0.0.1`：显式要求，拒绝远程/生产目标。
- `CHENGYIN_MYBATIS_IT_PORT`：该获准隔离实例的实际端口。
- `CHENGYIN_MYBATIS_IT_USER`、`CHENGYIN_MYBATIS_IT_PASSWORD`：显式提供授权测试凭据；密码可显式为空，不进入版本库。
- `CHENGYIN_MYBATIS_IT_DB_SUFFIX`：每次独占标识，匹配 `_[a-z0-9_]{1,24}`，例如 `_e03_20261015_run1`。

未来点名命令（仓库根；环境先由授权通道提供）：

```bash
CHENGYIN_ENDING_MYSQL_IT=1 mvn -B -pl chengyinhub-admin -am \
  -Dtest=A03EndingSnapshotMySqlIT \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

启用后 host/suffix/凭据缺失、非 MySQL 8.0、mapper/DDL 不在本地、连库/建库失败均直接失败，无 assumption 或 catch 后通过。仅复用 `MybatisItDatabase` 的安全命名与环境读取，不调用其会 DROP/CREATE 的 `url` 方法；复用 `ItSchema` 真实既有夹具/迁移解析及 Spring 事务代理，不改共享工具。scratch 名为 `chengyin_mybatis_a03_ending_snapshot${CHENGYIN_MYBATIS_IT_DB_SUFFIX}`；setup 使用无 `IF NOT EXISTS` 的 `CREATE DATABASE`，库名冲突直接失败，绝不先 DROP 或接管已有库；创建成功后才标记 created。AfterAll 仅在本次创建成功时 DROP 此精确前缀和合法后缀的库，并回读 information_schema 确认 0；冲突失败不会删除已有库。DDL 仅运行时写获准 scratch，不是新增业务迁移。

验收须逐方法读取新生成 Surefire XML；3 方法执行且 skipped=0 才说明启用生效，0 测试/禁用不计通过。当前已知第三方法预计揭示失败，不能预填 3/0/0/0。编译、夹具前提、数据库调用链与实际断言全部未验证。
