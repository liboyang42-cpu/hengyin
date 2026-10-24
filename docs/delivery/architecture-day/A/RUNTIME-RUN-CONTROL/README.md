# A：共享 run 控制 / 人工账 owner 抽取候选

代码完成，静态核对通过；Java 编译、JUnit、MySQL、CI 均未执行。本次不增加专项 CI。

- 公开基线：`d49c12b820419308236010e0710f96d7bf48c4c9`；独立工作树 `/workspace/hengyin-a-runtime-run-control`、分支 `codex/architecture-day-a-runtime-run-control`。
- 父 runtime 实测 5944 → 5424 行（净减 520）；新同域 `GameSharedRunControl` 847 行。不是整体换名：父类仍持命令认证、总回执、事务入口、业务 dispatch，子类实际持共享控制状态迁移、人工账及相连资源释放。
- 增量依赖 root 叶子投影提交 `99627230e0f2b6d976d24e999f375aeb8c8e14df`，仅接其已有 API，未把 root/E 文件复制到候选。另已交 HTTP JWT 夹具修复 `c84cc557d5f0d36b37ef2c70f39ed73608678410` 是独立候选。

## 边界与调用链

`ApiGameSessionController` → 原 `IGameSessionRuntimeService.command` → 原认证/活动 scope/会话锁/回执 claim → 原 dispatch → `runControlAction` → 新 owner → 原 session CAS/result/总回执终态。`exportSharedRun`、`anchorSharedRun` 保留父类人员权限与原会话锁，再下沉高水位/锚点操作。

子 owner 持 RUN 初始化/读写 CAS、暂停/终止/恢复、队伍退出、人工接管/签署/重放元数据/复核/恢复、迁点/解幕/撤销到达及 fallback 清理。冻结计时平移、队伍与 WORLD 协作预占释放和原 survival/合作持久化随该闭合边界下沉。控制字段 `operationStatus/authorityMode/pausedAt` 不再由父类写；父类仍持到达凭证、业务题组证明和其他 TEAM 业务，不宣称全部 RUN JSON 字段都已归子 owner。

子类为包内普通协作对象，直接复用同一 mapper/clock/ObjectMapper/既有 engine 和原人工执行时间；无 Spring bean、事务传播、线程池、父类反调或回调集合。原 session 行锁、TEAM 锁顺序、CAS、预占释放及余额处理次序不变。人工导入仍在父类执行原在线回执 UNKNOWN/PENDING/指纹核对、receipt claim、dispatch 和完成回执；子类只在原前后时点读写人工账。已执行签署仍在任何重新授权/dispatch 前读回原结果。

runtime 原有 3 处 E 实现类静态依赖分别改用 `GameplaySupportMatrix.addon` 和 `GameplayActionProjection.gameplayActionSummary/gameplayChallengeSummary`，原参数与所在顺序逐字保留。

## 给 E 的只读端口

`TopicRouteState querySharedRouteState(Long memberId, Long topicId, Long activityId)` 已加入接口和唯一生产实现。

只读现有 session → topic scope → 可用/未取消活动 → 当前有效付费报名/活动队伍 → 已持久化 RUN → 当前队伍的冻结 TEAM 行 → 复用原纯 DTO 投影。非共享局/无 session/无 RUN 返回 `null`。不初始化、不读锁、不刷新计时、不写账、不建局、不求时钟；不调用旧 `sharedRouteState/sharedRouteSnapshot`。读取源为既有 XML 的 `selectSessionByActivity/selectActiveTeamId/selectStoryState`、`PlayTeamMapper.hasPaidRegistration` 和活动普通查询。

旧 `sharedRouteState` 仍在原时点调用带刷新行为的 `sharedRouteSnapshot`，只将末尾纯 DTO 映射共享；原 Entry 初始化/刷新行为保留。E 的 queryState 消费接线由 E/root 集成，此候选不修改它；不宣称已完成调用方组合验收。

## 验证和限制

实际执行：`python3 docs/delivery/architecture-day/A/RUNTIME-RUN-CONTROL/verify_static.py`、`git diff --check`。静态脚本核对 11 组原入口/回执/dispatch/刷新方法体、全部原事务与注入注解、60 组下沉或复用的原方法体（包括纯 selector）、生命周期 case、人工重放核心、纯投影和 3 处叶子调用的精确替换；输出源码 SHA256。该检查不是编译器或运行时证据。

新增 `GameSharedRouteQueryTest`：7 个普通用例 + 2 个参数实例 = 9 项已编写，0 项执行；真实 runtime 配 mapper mock，校验投影及 JSON/revision 不变、无时钟调用、所有交互仅既有普通读、缺 RUN 不初始化，以及 topic/报名/活动/当前队伍/冻结归属拒绝。不是 SQL 或 E query/Entry 链路实跑。

所有原测试文件未改。供 root 统一回归复用的既有 `GenericRouteRuntimeMySqlIT` 场景包括 `manualReplayRequiresActualSignerContinuousSequenceAndRejectsOldEpoch`、`lateReceiptHashConflictCannotResumeWithoutExplicitReviewAndNeverReplaysBalance`、`operationalInterruptionReleasesEscrowPreservesAssetsAndNeverCreatesGameFailureEnding`、`realOfflinePaperEventsUsePriorServerAnchorAndRejectUntrustedOrOldEpochTime`、`offlineHostArrivalReplaysOnlyStaffSignedPaperAndRetainsOnlineNonceRules`，本候选未运行，未改变任何场景/断言。

未新增 schema/依赖/经济或权限政策。公开 master `44699155cf4baa7ed0470d013112d1f650b58888` 已含 #567/#585/#586，其 runtime blob 与本次 d49 基线同为 `e53043d60ec2ab3e01d4141eda5b1c146350e22d`；集成只取本候选增量，不能整分支覆盖主线或他人新改动。回退须成组撤回本次接口/实现/协作类及 E 对新端口的接线；无持久化格式迁移。A95 裸 POST P1 不由本任务关闭。
