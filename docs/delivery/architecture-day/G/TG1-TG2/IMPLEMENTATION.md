# TG1/TG2：审核、发布与原局内容绑定（db2历史片）

本文件保留 db2 的交付历史；当前完整 producer/共享接线和最终字段以 [PRODUCER-CLOSURE.md](PRODUCER-CLOSURE.md) 为准。

结论：该历史片完成 G 发布源码的 RR 当前读接线及配套测试源码；不是完整游戏库或最终 TG1/TG2 验收。新增 14 个测试声明均 NOT_RUN。没有执行 Node/Python/Java/JUnit/MySQL/HTTP 测试或 CI。只执行源码读取、Git 差分及文件摘要。

- 公共父提交：`fa5c263440573992701263d6bee60830844a49b8`。
- 独立分支：`codex/architecture-day-g-tg1-tg2-binding`。
- 工作树：`/workspace/hengyin-g-tg1-tg2-binding`；不覆盖其他 owner 工作树。
- 已读协调方案：`ceb4e5f923091dc0fc5e5b6b7670d60c27bff85f:docs/delivery/architecture-day/COORDINATOR/全游戏资源库与主题复用完整施工方案-20261025.md`。
- 原资金、scope、UNKNOWN、CAS、回执、奖励次序不变；无新表、依赖、权限、版本服务或引擎。

## 1. 源码证明与实际修复边界

`Deepseek07` 的“发布只查 status，因此审核 A 发布 A′”不能直接移作当前漏洞结论。当前 `AuditTaskServiceImpl.review` 已是完整事务，并调用 `TopicBundleReviewService.applyDecision` 校验提交快照自 hash、topic/configVersion、全量章节/节点和逐模板 hash；topic→chapter→merchant proof→node→template 的顺序原已存在。本补丁保留该合同。

实际接缝有两部分：

1. 提交 `freezeForSubmission` 原先对非商家模板使用普通 SELECT；审核虽读锁定的当前版本，publication compiler 后续又普通 SELECT 重读章/节点/模板。在 MySQL RR 已建立旧视图的事务里，这两类 SELECT 的版本可以不同。单靠重复检查 status 或重算一个普通 SELECT hash 不解决它。
2. `CmsTopic.themeRuntimeConfigJson` 被 API 实体的 `@JsonIgnore` 隐藏，原 `valueToTree(topic)` 没将作者运行配置放入审核内容 hash。本候选在原 topic content 中显式加入解析后的 `themeRuntimeConfig`，继续使用原稳定序列化/hash，不加版本表。

具体交错反例源码使用已批准的非草稿模板：提交时 A → 事务普通读看见 B 建立 RR 视图 → 另一连接恢复为已批准 A → bundle FOR UPDATE 校验 A → 编译必须读 A。非草稿路径没有 `approveBundledDraft` UPDATE 帮助刷新旧视图。这里只提供真实 MySQL 测试源码，未运行，不称已复现。

## 2. 生产链与编辑/恢复分支

| 环节 | 当前真实入口与约束 | 本候选 |
| --- | --- | --- |
| 专业主题送审 | `TopicPublicationService.submitTopicBundle` → `freezeForSubmission` → 原 `submitOrRefreshTopicBundle` → `publishBeforeReview`，位于 `CmsTopicServiceImpl` 原写事务 | 写事务冻结时先锁 topic，再原章节/证明/节点/模板；预览保留原非写事务行为 |
| 审核 | `AuditTaskServiceImpl.review:783` → `applyModeration:1769` → `TopicBundleReviewService.applyDecision` | 原 CAS、审核回执、自 hash、当前 hash、模板资格和草稿批准全部保留 |
| 已先发布后复核 | `alreadyPublished` 判定后跳过重编译（`AuditTaskServiceImpl:1808/1837`） | 不把此分支说成“每次审核重编译”；送审与先发布同事务已持有相同内容锁 |
| 首次批准/首次安全留置释放 | 原 `publishApprovedRouteSnapshot` → `publishValidatedRouteSnapshot` → E 的 content/route guard → 原 compiler | 用当前 topic；编译每次捕获一个锁定的父/章/节点/模板投影，并在该次内部规则/叙事/答案冻结间复用 |
| 已发布主题安全留置/重新发布 | `publishBeforeReview:247` 保留原 held/配额/上架/复核路径；已发布 held 分支原可刷新快照 | 无新增绕过；同一 compiler 入口使用当前投影 |
| 简易首次发布 | `publishDraftInternal` 先规范化/冻结，后原 `persistRouteSnapshot` | façade 原事务内，compiler 同样先 topic 再子表当前读 |
| 主题新建直接快照 | `CmsTopicServiceImpl:2554/2559` 原 `normalizedBranchRouteForPublish` → `persistRouteSnapshot` | 复用相同 compiler；不另造发布 producer |
| 纯预检查 | `TopicPublishCheckService` → `freezeForSubmission`；编译的非写事务调用 | 保留只读预览，不假称这些调用持有原子发布锁 |

`TemplatePublishServiceImpl` 的编辑/发布操作管理自己的模板状态与 TEMPLATE 审核，不保证取消所有引用它的 TOPIC 审核。因此 bundle 内容 hash 及锁定实际引用不可省略。审核后模板内容不等会拒绝；已发布、已开局的原快照不被更新为 live 模板。正常作者编辑失效、内容安全、发布资格和诊断仍由原 owner 实现，本候选未改 E 的 `TopicPublicationContentGuard/TopicRoutePublishGate` 或 A 的机制校验。

## 3. 实现与共享接缝

- `TopicBundleReviewService.freezeForSubmission`：写事务改用原当前读；商家证明原顺序及校验保留；模板集合排序锁定；不在缺行/重复身份时默认成功。
- `TopicRoutePublicationCompiler`：原叶子内新增短生命周期 `CurrentContent`，只保存一次操作已锁定的实体，不跨请求缓存。原 compiler 由 runtime 工厂每次新建。规则编译、章节叙事、thought/opening/ending、模板配置和答案读取复用该投影。公开方法签名不变。
- `TopicPublicationService`：批准入口与统一规范化入口读当前父行，不从原 RR 对象继续发布。
- `CmsTopicMapper.selectCmsTopicByIdForUpdate`：SQL 原文不变，显式 `useCache=false, flushCache=true`，避免同一事务的 MyBatis 本地缓存返回调用方已改过的对象或绕过物理当前读。
- 唯一 runtime 调用者修改是 `publicationCompiler()` 工厂的一行参数：原 private/package 叶子构造器末尾增加 `CmsTopicMapper topicMapper`。不改 runtime 的生命周期、动作、锁/CAS、一次结算或奖励顺序。
- 新增 managed type **0**、Mapper statement **0**、依赖 **0**。F 若登记调用者，新增消费者仍是原 `TopicRoutePublicationCompiler` 对 `CmsTopicMapper.selectCmsTopicByIdForUpdate`，以及已有 chapter/node/template FOR UPDATE statements；没有新技术 owner。

审核快照兼容边界：仍为原 schemaVersion=2；作者运行配置非空时新增参与 hash。旧待审快照若未绑定该作者配置，会按原 stale 拒绝并要求重新提交，不能补写 hash 代替重新确认。无运行配置的旧 hash 不增加空字段。已经发布的快照、已批准记录和运行实例不回填、不删除；派生 `publishedThemeRuntimeConfigJson` 仍不参加作者 hash。

## 4. 原发布版本→独立运行实例

| 原消费者 | 源码落点 | 实际已有身份/冻结行为 |
| --- | --- | --- |
| 个人/分支 TopicRoute | `ensureBranchSession:1016`、`ensureJourneySession:1230` | V1 `publishedRouteGraphJson` → 原 `freezePublishedJourney` → `TopicRouteSession.configVersion/configSnapshotJson`；已有 scope/current instance 与会话状态继续原 owner |
| 原局读判定 | `nodeAnswers:1005`、`nodeRuntimeConfig`、`NodeJourneySnapshot` | 从局的配置取答案、节点块、旅程/剧情/选项效果；V1 不重新编译 live 模板 |
| 高级小游戏 | `AdvancedGameRuntimeServiceImpl.requireInput:1228`、建局约295–298 | existing session 优先原 `configSnapshotJson`；新局调用原 `nodeRuntimeConfig/publishedR5NodeConfig`，再将实际配置写入自身局快照；不复制判题 |
| 共享 GameSession | `GameSessionRuntimeServiceImpl:2275`、`sessionConfig/sharedRouteSnapshot` | 复用 `freezePublishedJourney`，各队原状态/epoch/bindingVersion、事实/效果归属不变 |
| 重玩 | `TopicRouteRuntimeServiceImpl:3635–3648` | V1 发布内容进入新独立实例，冻结定义初始化新局时钟；不修改旧局配置/状态 |

本候选还移除 V1 `mergeFrozenTemplateRules` 分支的一个无效 live chapter 参数计算：该分支原来已经调用 `mergeFrozen` 且不使用 finalChapter；现在不为计算未使用参数访问 live 章节。未改变原旧快照 fallback、随机、时钟、共享队伍或奖励政策。

**仍需 root 分配的精确共享消费者边界（不伪称全链完成）：**

1. `PlayEncounterServiceImpl.resolve:115/presentation:224` 已用局 `nodeConfigs` 替换高级块，但 `title/storyText` 仍来自 live 个人模板；本 compiler 的 `nodeAnswers` 原冻结的是判定字段，并未冻结全部标题/题干/媒体。需要原展示 owner 与 G 约定在既有快照内扩展哪些实际消费字段，再接原查询/renderer；不能仅因为有 nodeConfigs 就称全内容展示不漂移。
2. `AdvancedGameRuntimeServiceImpl.requireInput:1254–1263` 新节点局先读 live node/template，再选冻结配置，`GameInput.templateId` 来源仍为 live template；已存在小游戏局则直接用自身快照。若要求新节点局也不依赖作者后续解绑/删除，需要运行 owner 沿冻结身份取原模板元数据；G 不擅改其写侧。
3. 原 `ThemeStateKeyMapper.selectByTopicId` 仍用于 compiler 的注册校验与 `JourneyInitialState.freezeKeys`；本批可写投影覆盖 topic/chapter/node/template，不应声称已经锁定或 hash 绑定独立 state-key 注册表。该表无既有 FOR UPDATE API，扩展须 root 协调其真实写/读 owner。

上述边界是当前完整目标的剩余接缝，不是本轮已运行漏洞计数；本候选不添加无人消费的全量 payload，不启用新版本服务。A/B/C/D 的内容发现、采用、完整编辑与 renderer 结果由 root 合成，G 不替其宣称完成。

## 5. 配套测试源码（全部 NOT_RUN）

新增 14 个普通 `@Test` 声明，无参数展开、无 skip：

- `TopicBundleReviewServiceTest` +4：作者运行配置进入 hash/变更拒绝；派生发布字段不误判作者变更；未绑定运行配置的旧提交要求重新提交；完整模板已有 `interactionType` 保留及变更拒绝。
- `TopicRoutePublicationCurrentReadTest` +4：单次当前投影及锁端口次序；预览不加锁；失败后不缓存上一投影；旧父对象不污染当前发布。它们是 Mapper-port unit，不是数据库锁证明。
- `TopicPublicationBindingMySqlIT` +6：已批准模板 A/B/A 的 RR 交错；提交也采用当前行；审核后编辑拒绝且不写版本；锁持有到冻结/提交后编辑才完成；发布后重复开局保持原配置；缺模板不发布部分包。复用原 `TopicContentGraphMySqlFixture` 隔离建库/真实 Mapper/事务及原 cleanup，不缩表、不假造审核或付款事实。这六例覆盖真实 bundle/compiler+发布 Mapper，不冒充整条 AuditTask/HTTP 审核验收。

保留原全部用例/业务断言。`TopicPublicationSizeEntryTest` 只补当前父查询装配/对应查询验证；公共 `TopicPublicationTestWiring` 只对纯 unit mock 的新当前读端口映射原 fixture 行，不替换真实 Mapper/spy，不提供 guard=true。

既有源码映射继续保留：`AuditTopicBundleModerationTest.approvalLocksFreshTaskAndVerifiesBundleBeforePublishingTopic`（顺序）；`AuditTopicBundleTransactionTest`（原 Spring/H2 事务失败回滚，非 MySQL）；`AuditGovernanceContractTest.successfulReviewReplayReturnsOriginalReceiptWithoutSecondCas`（原审核回放）；`GameplayTemplateSlotRuntimeTest.sceneOverridesAreResolvedAndRepublishUsesCurrentTemplateWithoutChangingOldSnapshot`（跨发布/原局）；`ThemeRuntimeMySqlIT`、`TopicRoutePublishedSnapshotContractTest`（现发布/运行/storage/SQL合同）。未在本次执行这些测试，不能移签旧绿色。

## 6. 集成和回退

root 按完整候选增量合入，保留 E91a3/A/D/B/C 同期修改。必要共享 hunk 只有 runtime 工厂一行与原通用 topic 锁查询的 cache 属性；原声明/业务 SQL及 registry/CI 不改。root 可按新增 14 声明登记最后统一验证，不能在当前源码交付阶段视作已验证。

无 DDL/数据迁移。回退源码不会改写既有发布/运行快照；不要删除审批回执或重算旧版本。新审核快照的显式运行配置字段由内容 hash 自校验保留，但回退至不绑定该字段的旧代码无法继续保证这批新审核的同一内容合同，需恢复安全版本后重新送审；不得改历史 hash 来制造通过。

## B 的完整采用字段接缝

本增量只补直接测试源码，不修改 B 文件或新增生产字段列表：`CmsMemberTemplate.interactionType` 已有普通 getter，原 `CmsMemberTemplateMapper.selectCmsMemberTemplateVo` 的列与 resultMap 已含 `interaction_type`，本次当前投影持有完整实体；`TopicBundleReviewService.templateContent` 对该实体整体序列化，只移除已有元数据/审核状态字段，因此该字段已进入送审内容与 hash。新增测试声明验证原字段保存且批准前变更会失败。上文的运行消费者闭合边界保持 OPEN：`interactionType` 目前的实际调用者是原 `TopicQueryService` 的主题模板发现投影，不凭标签推断新的运行协议；不将已有判定快照的字段子集冒充完整展示内容。B 的 DTO 修复由 B 原片提供，G 不覆盖。该新增测试亦 NOT_RUN。
