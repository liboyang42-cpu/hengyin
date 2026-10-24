# C 玩法应用边界候选

已实施机械拆分：Controller 3848→318 行，58→8 个业务注入；25 条实际 HTTP 映射（任务估数24）、38个既有公开 Java 入口重载保留。13 个 owner 按查询、scope/冻结输入、完成事实、回执、命令、奖励组织，详见 owners.json（涵盖全部110个移出的方法体）。没有复制 runtime 或奖励算法；Controller 不传业务 lambda，应用不回调 Controller。

基线 d49c12b820419308236010e0710f96d7bf48c4c9；独立 codex/architecture-day-c-play-boundary。只变更 C 控制器、新应用类、随真实 owner 迁移的测试接线。三个 runtime、生产表、金额规则、JWT/RBAC、CI、台账均未改。读回公共 master44699155：#567/#585/#586 三个 merge 对此 Controller 无路径差异；本候选不整树覆盖 master，最终由 root 保留其余主线规则。

原 HTTP DeadlockRetry/Transactional/限流/参数/错误码保留。应用命令使用同选项的 REQUIRED 事务加入原边界；没有第二重试环。偏好提交仍由已有 PreferencePlayService 的事务/重试包裹原完成回调。回执授权→原回执→聚合锁→再次回执→版本→claim→效果→终态顺序保留；原照片上传事实/审核ID/奖励封顶和 Hint event14及ledger→member锁序未改。

测试仅改装配/反射观察 owner；现有用例名和业务断言保留。PlayNodeAccessTestFixtures 在每次显式替换后重建真实应用对象图，后置桩仍生效；测试 no-lead 替身保留在测试侧。根独占 hint 真库类未改，PlayHintUnlockMySqlIT.review.patch 仅替换两条旧 Controller 字段注入；root 需应用后统一回归。ChapterFlowWriteServiceTest 仅两处调用已经移出的 isPrevDone 改观察 owner，其他 D 装配不动。

实际检查：Python 静态逐体核对110、HTTP路径/顺序与公开签名、应用依赖DAG、旧测试用例名；git diff --check 均PASS。未运行Java编译、任何Java/MySQL/HTTP测试、Spring启动或CI；不借用旧源码结果。统一回归还需 root 的相关单元/真库/启动与 action-ledger 真实owner适配。

## Entry 与 Query 的实际边界（ce04 后独立增量）

按 root 明确决定，本批保留兼容 GET 的原入场行为，不宣称 HTTP GET 全部纯读。PlayEntryApplication 接管 nodes/routeState：原资格与时效读取→原 state(可能建局/共享刷新)→Query准备→逐节点题面投影→原 nodeRuntimeConfig(可能共享刷新)→冻结模板投影→节点及列表收尾→原 assignOrGet→冻结角色投影→原 Ajax。没有把所有 runtime 刷新提前批量做；条件/时点/次数均沿用原代码。Query 投影只接收原 routeState、原逐节点配置和不可变角色 code/name，没有调用 Entry 或持有函数回调。

特别回读：nodeRuntimeConfig→sharedSnapshot→sharedRouteSnapshot.refreshZeroHpRead 有写，故也移入 Entry，而不因其方法名有 Config 就冒充纯读。NodeQuery 的实际 runtime 调用仅 tags(当前局既有状态读取)；其他 Query 中 sessionStatuses/isRouteLeaderboardHidden 也只查现有状态。Actor/scope与原冻结route引用在 request-owned投影数据中为 final；nodeList过滤和展示map只在本次投影内修改。

BasicIntentApplication.basicActionContext 仍是有共享状态刷新语义的兼容 GET 命令入口；PreferenceApplication.preference 仍是有原 canPlay 入场语义的兼容入口。两者已在源码明确标识，保留原授权/lead/unlock/回执/事务注解与调用顺序，不包装成 Query。普通节点完成命令、照片、Hint、奖励算法不在本增量改动。

root540d16 的 readAssigned 端口已交付；本兼容 Entry 使用原 assignOrGet 结果投影，不为展示增加额外查询或改变首次分配。A/E 的 querySharedRouteState/queryState 用于未来直接查询既有状态时，缺局必须 null；本批通过 Entry 传原冻结结果，不依赖尚未交付的端口、不复制 runtime实现，也不伪造ACTIVE或成功。

本增量实际执行 Python 静态逐阶段 token核对、原入场读序与逐模板写口位置、纯Query禁写口、HTTP适配token和词法括号检查，及 git diff --check；Java/DB/CI未运行。仍需 root统一编译、旧场景/并发回滚、真库及Spring启动验收。回退整体撤销 Entry+Query分段+Controller/fixture归属接线，无DDL或持久化变更。
