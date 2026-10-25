# H：两个自办活动发布 unit 的最终收口

状态：SOURCE_ONLY；测试、解析控制、导入/编译、Java/DB/CI/渠道全部 NOT_RUN。没有以源码替代实际回归，没有修改生产或共同 helper。

## 不可变身份与取用

- 最终提交 parent：D 已转出 WIP `2b68c327d0c6f247e76af108700ca626b6fb7197`，其 parent 为 helper `bc2b738fbd58dea6c8eabdb9d00eea22b21f364c`。
- 原失败与用例身份基线：`347bcd0226c3bed71616e38ea7c1391fc76efe5c`，run40220558637/job121251116626/artifact12609615426。
- 已逐字取得两 WIP blob（见 MANIFEST.json），从其增量继续；没有从零覆盖或回退 D 的装配。
- D 最终 helper `a119071a7d1dbe68d4db55876653db0a57eb2782` 与 bc2b 的 TopicPublicationTestWiring blob 均为 `98b13099fbea520e3c4c475b1662c5c493f19904`。
- 只拥有 TopicEditActivityShellTicketSyncChainTest、TopicPendingReviewSelfHostShellTest 两个 Java；不改 D helper、生产/权限/费用/表或其他owner测试。

WIP 中已写的 ThemeStateKeyService 实装、transactional(svc)、Pending 当前状态 AtomicReference 与期望状态断言均保留。依实际已交 helper 的 wire/set/get/owner/wireRelocatedCollaborators/transactional 工作，不猜接口、不另造事务框架。helper 用原 Spring DataSourceTransactionManager 与真实 interceptor，控制连接禁止 SQL；本包不冒充 MySQL 或内存行 rollback 证据。

## 补齐的真实前提

原 unit 只有票/壳和空的 chapter/node Mapper。真实 runtime/compiler 接入后，没有可发布节点会被拒绝，不能让旧 mock freeze 返回成功掩盖。此次补的是同一主题、同一作者的有效原内容：

- TicketSync：复用原 template Mapper（缺时显式建对应 double），放一张 ME 自有、已发布状态的普通 TYPE 问答模板，绑定原主题 chapter/node；其内容为“场地何时开放？/18:00”，有合法原 AdvancedGameConfigValidator 配置，不是空 theme 或假快照。chapter/node Mapper 按 topic/status 读当前模型，真实 insert 回填独立ID、delete 删除原行；旧DTO中的空章节改为这份实际内容，重建仍取得新节点/章节身份，原票 ID/库存模型完全沿原路径。
- PendingReview：原 TOPIC/OWNER 的独立 chapter/node/template 行提供普通/锁定读取的同源事实；helper 仍桥原 mapper，真实 TopicStateKeyRefService/TopicRouteRuntimeServiceImpl/compiler 处理内容。没有把 runtime/freeze/mock approve 改成默认成功。
- 状态/发布存储：TopicMapper 更新按原 SQL 非空列语义合并。保留 D 的“写前同一行、真正 UPDATE 后才变状态”，但不再用 after 对象替掉存储整行，避免丢弃先前已持久化的发布快照。publishRouteSnapshot 留存 compiler 真正文及递增版本，不只 return1。
- 三处原成功用例增加发布快照读回：原 runtimeSnapshotVersion/stateKeySnapshotVersion、真实模板/节点/topic身份和完整题答案字段，保证原票务/壳断言到达前实际产生了正式快照。

模板是此 unit 的自有已审元数据模型，无图片/券/收费配置，不伪造供应商审查、支付或完成证明，不增加玩法白名单。审核/配额/debt/coupon 服务仍沿原边界：原审核任务仍 PENDING，欠款拒绝、券归属、身份与未许可写口不被绕开。空声明仅对应该普通题确无状态键引用；不是放宽声明守卫或给坏配置补默认模板。

## 调用链及原保护

- 原 updateTopicByOwner → rebuildTopicContent → 原 graph authoring → 实际 current compiler → publishBeforeReview → refreshSelfHostActivity → 原 OmsTicketServiceImpl 同步；DTO/Mapper 内容完整，原 69.00、已售席位、余量、原票键与异主题/活动隔离路径保留。
- 原 publishDraft → prepareDeclarations/currentForPublication → 实际状态声明/正文校验 → quota/debt/原审核 owner → 持久快照 → 自办壳；simple/pro 两轨与原“先上后审”语义不变。
- 原所有负控：已售改价/删票、并发未命中、孤儿键、越topic/actor、成员可见范围、草稿/驳回/商家池/已有壳、逾期欠款/审核期间新欠款、债务解除后补壳，均保留原错误类型和断言。
- 原18+13个 @Test 方法、所有原 assert/verify 与清理保留；不新增skip/disable，不把异常改宽。CASE-IDENTITY.txt 给原347b名字与blob，文本diff没有删原assert/verify。新增的是正常内容/存储前提和成功快照断言，不是重写票务、发布、审核或库存算法。

## 交付与限制

delta-from-wip.review.patch.b64 仅两个 owned 文件的最终增量；complete-from-347b.review.patch.b64 是包含 D 已写装配的同两文件完整delta。POSTIMAGE.sha256 列完整作者后像，MANIFEST.json 同时绑定347b/WIP/final身份。root可直接取最终两blob，或根据当前集成状态选对应补丁；不要重复叠加两patch，也不要把WIP其它owner文件带进来。

未运行任何测试/解析器/编译/CI/数据库。helper真实AOP装配、这些原31case是否执行通过、MySQL并发/事务回滚/HTTP/生产渠道一律 UNKNOWN/NOT_RUN；不会借旧 client10767、HTTP36或其它64类绿签本片。原 H worktree及别人源未动，本包只在独立 /workspace/hengyin-h-shell-fixtures-347 源快照上编辑。
