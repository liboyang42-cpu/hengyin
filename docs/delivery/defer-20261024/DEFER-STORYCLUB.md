# DEFER-STORYCLUB · 非阻塞问题按优先级修复（剧情引擎 / 模板 / 俱乐部 / 活动）

日期：2026-10-24 · 分支 `chore/defer-storyclub-20261024` · PR #588（draft）

上一轮把这些条目判为 DEFER（理由见 `docs/delivery/bugfix-20261023/BUG-STORY.md`、`BUG-CLUB.md`）。本轮按优先级从上到下逐项处理，每项一个提交。

计数（共 18）：FIXED 9 · PARTIAL 5 · ALREADY_FIXED 0 · NEEDS_OWNER 4

| id | 优先级 | 状态 | 文件 | 测试 | 说明 |
|---|---|---|---|---|---|
| B:P1-028 | P1 | FIXED | `CmsRegistrationMapper.xml` | `ActivitySoldTicketEditLockTest#openSaleTicketSetQuery_countsPendingAndPayingOrders`（加断言） | 已售票 id 查询补 `order by ticket_id asc`，结果顺序确定。两个调用方本来就当集合用，行为不变。 |
| B:P1-032 | P1 | FIXED | `TemplateAuditSnapshot.java` | `TemplateAuditSnapshotFieldsTest`（新） | 字段清单从空格拼接字符串 `.split(" ")` 改为显式 `List.of(...)`；反射测试断言每个字段至少在 `CmsMemberTemplate` / `CmsTemplateLibrary` 之一存在，字段不重复、数量冻结为 48，快照输出顺序与清单一致。 |
| B:P1-035 | P1 | FIXED | 同 P1-032 | 同 P1-032 | 申诉功能 master 已有；剩下的快照常量写法与字段断言随 P1-032 一起补上。 |
| B:P1-073 | P1 | PARTIAL | `ClubEventOpsServiceImpl.java` | `ClubEventZoneConstantTest`（新） | 6 处 `ZoneId.of("Asia/Shanghai")` 和 timezone 列的写入值统一为 `CLUB_ZONE` / `CLUB_ZONE_ID` 常量，并注明该列只写入和回显、计算不读它。还没做的：让排期计算真正读这一列（多时区）或删掉这一列，属于产品决定。 |
| C:U-013 | P1 | FIXED | `ApiClubRoleController.java`、`pages/club/roles/index.js`、`scripts/uiaudit/action-ledger.json` | `ApiClubRoleGovernanceProjectionTest#roleCatalogMarksEveryRowAssignableAndMatchesServerSet`（新）、`club-role-ui-contract.test.js`（新增 1 条） | `/roles/list` 目录每行带 `assignable`；角色页删掉 `CLUB_ROLE_CODES`/`EVENT_ROLE_CODES`，改按目录的 `assignable` + `scopeType` 筛选（字段缺失时按可分配处理，兼容旧后端）。ledger 只有行号漂移，已重新生成，控件数 5103 不变。 |
| C:U-046 | P1 | FIXED | `TemplateMarketState.java`（新）、`MemberTemplateVO.java`、`utils/template-market.js`、`tests/fixtures/template-market-state-cases.json`（新） | `TemplateMarketStateTest`（新）、`template-market-state-contract.test.js`（新） | `/api/template/my-list` 每行下发 `auditState` / `marketState` 枚举（服务端唯一推导）；`marketView` 只按枚举渲染，旧后端没有枚举时才用同规则推导兜底。两端共读同一份 96 条用例；新旧 `marketView` 在全部用例（数字和字符串两种形态）上输出逐字段一致。 |
| C:U-057 | P1 | FIXED | `JourneyStoryValidator.java`、`GameplayRuntimeConfig.java` | `StoryEndingLimitContractTest`（新） | 16 和 100 分别抽成 `MAX_ITEMS` / `MAX_STORY_ENDINGS` 命名常量，两处注释互相引用，说明分属两条独立配置链；上限数值不变。 |
| C:U-065 | P1 | PARTIAL | `utils/access-gate.js`、`pages/club/{badges,detail,event-ops,governance,group-code,notify,roles,settlement,topic-detail}/index.js`；3 个测试桩 | `club-bizok-single-source.test.js`（新） | 俱乐部页和 access-gate 里所有手写的 `code === 200 \|\| '200'` 改为 `response-shape#isBizOk`（共用一份实现）；守卫测试禁止 `pages/club/**` 和 access-gate 再写这种判断。3 个测试的 response-shape 桩补上真 `isBizOk`。没做的：俱乐部以外的页面（activity/merchant/…，属于其他 lane）和全仓 lint 规则。 |
| C:U-070 | P1 | PARTIAL | — | `PlaykitSegmentRegistryContractTest`（新） | 服务端已有段名表 `GameplaySupportMatrix`，新增跨端契约：`KIT_PRIORITY` 必须与矩阵 section 逐项相等，差异只允许显式例外（`check/timer/leaderboard/multiplayer` 不走 cy-playkit；`bingo` 以 key 计）。负控：删掉 `bingo` 后用例变红。没做的：服务端运行时下发段名集合，客户端不再自带列表（接口+页面重构）。 |
| C:U-079 | P1 | PARTIAL | 同 U-070 | 同 U-070 | 同 U-070。目录数与表项数不同是因为辅助组件和别名，不是缺玩法。 |
| C:U-152 | P1 | NEEDS_OWNER | — | — | 要做成可查询的列，得在高频表 `topic_route_session` 加 `terminal_reason` 列（必须先于新 jar 执行，并进入发布迁移包）。主题终局原因已经写在 state_json 里。 |
| C:U-168 | P1 | NEEDS_OWNER | — | — | 改成服务端下发报名判定对象后，置灰依据会从手机时间变成服务器时间，报名/付款页的可见行为会变。建单本来就以服务端 gate 为准。 |
| C:U-169 | P1 | PARTIAL | `EndingFallbackRule.java`（新）、`ChapterFlowCompiler.java`、`GameplayRuntimeConfig.java`、`ThemeRuntimeEngine.java` | `EndingFallbackRuleTest`（新）；`*ChapterFlow*`、`GameplayRuntimeConfigTest`、`*ThemeRuntime*`、`*Ending*` 回归 182 条全绿 | 保存期「声明了结局就必须恰好一个兜底」和运行期「都不中就落兜底」收口到一个 helper，三处共用，判定口径不变（各自的「是否声明」条件原样保留）。没做的：各链路挑选非兜底结局的方式（优先级/顺序）合并成一个 EndingSelector。 |
| C:U-175 | P1 | NEEDS_OWNER | — | — | 统一成数组写法需要迁移已存章节正文块的 `when` 数据，并改编辑器的写入格式。两种写法现在都有保存期校验。 |
| B:P2-023 | P2 | PARTIAL | — | `PlayerNodeProgressSchemaDriftTest`（新） | 静态测试：`PlayerNodeProgressMapper` 的列必须能由 `base_schema` + 迁移的 `ADD COLUMN` 建出来，并钉住基线缺的 3 列（play_instance_id/puzzle_score/completion_mode）都有迁移补。没做的：重新生成基线、启动期对照 information_schema、全库漂移扫描。 |
| B:P2-024 | P2 | NEEDS_OWNER | — | — | 统一边界文案会改 AI 回答：共享的 `BOUNDARY` 第 4 条禁止说地址，但节点店铺对话的事实里本来就有地址，直接复用会让 NPC 拒答地址。 |
| C:U-017 | P2 | FIXED | 同 B:P1-073 | 同 B:P1-073 | 这项的缺口（没有 CLUB_ZONE 常量、没有说明注释）已补上。 |
| C:U-166 | P2 | FIXED | `StateKeyRefs.java`、`TopicStateKeyRefService.java`、`TopicPublishCheckService.java` | `TopicPublishCheckServiceTest#chapterTextConditionOnUndefinedThoughtWarns`（新） | 发布试检查收集普通正文/内心声音块上的 `when HAS_TAG thought.<key>.done`，念头没在思维阁声明时给 warn `THOUGHT_CONDITION_UNDEFINED`（不拦发布）。beat 块仍走原来的状态键 error，不重复报。 |

## NEEDS_OWNER 待确认

- C:U-152：要不要为运维观测在 `topic_route_session` 加 `terminal_reason` 列（先执行迁移再上 jar）？还是继续从 state_json 里读？
- C:U-168：报名页置灰是否改为服务端下发判定（以服务器时间为准，可见行为会变）？
- C:U-175：是否接受一次章节正文 `when` 数据迁移，把对象写法统一成数组？
- B:P2-024：节点店铺对话是否改用统一的 `BOUNDARY`？如果改，第 4 条「不透露地址」和店铺事实里的地址冲突，以哪条为准？

## 验证

- 后端：每项跑了相关测试类（见上表）。本地全量 `mvn test`（排除需要 MySQL 的 `*IT`）结果见下方。
- 小程序：跑了俱乐部/access-gate/response-shape 相关的 203 个测试文件，只有 `sc-ui-date-count-probe.js` 失败，在 master 上同样失败，不是本 PR 引起的；`lint:scope` PASS；`audit:actions` PASS（5103 controls）。
- 没有 wxml / wxss 改动；C00 保留文件只按允许的例外重新生成了 `action-ledger.json`（`action-ledger.test.js` 里的冻结计数没变，没改）。

## CI

- GitHub CI 绿：run 40062698542（b4e72cf7）。最终 head 的 run ID 见 PR #588 的 checks。
