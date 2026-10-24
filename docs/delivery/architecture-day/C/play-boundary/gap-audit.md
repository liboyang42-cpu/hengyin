# C：拆分后应用边界遗漏收口

代码已完成两项独立窄修复，Java/DB/CI均未运行，不能据此声称真实回滚或并发验收。独立分支 `codex/architecture-day-c-play-gap-audit`；实际公共基线 `cc91b0845e3abd4a2060d54a857c8c179bd22d4f`（取回时09555尚未公开）。仅交增量供root组合，不以本分支整树覆盖最新主线。此前ab8a GET静态合同候选、Entry/Query/Photo、008/e99均不重做。

## 已修代码及最小失败机制

1. `78dbd2c4`：`PlayBasicCommandApplication.sensorResult` 已在actor、node、活动/主题报名归属成功后，将原lead检查前移到routeAccessError之前。原routeAccessError会进入TopicRouteRuntime.canPlay，真实路径可ensureBranchSession/ensureJourneySession。集合状态原来返回正常错误Ajax，外层REQUIRED事务因没有异常可以提交先建的局。本改动让拒绝不进入该可写上下文；既有503/NOT_RUN和403异常继续原样传播。合法nonlead仍使用原actor/scope/actionId调用原canPlay及传感器验证；同时多闸拒绝时lead错误现在优先，其他合法/收费/奖励规则不变。
2. `PlayNodeCompletionApplication.logAttempt/refreshBingo`：宽泛catch原来吞掉TransientDataAccessException。MySQL1213可以已撤销数据库事务，吞异常继续完成或终态回执会截断原外层回滚/重试合同。两处现先透传**原异常对象**；普通非瞬态旁路失败沿用原best-effort。1213/1205仍仅由既有外层DeadlockRetry重试，其他瞬态错误只外抛回滚，不新增重试器/事务/依赖，不改奖励金额、SQL锁序或回执身份。

## 全25映射源码核对

全部25真实映射均把`currentMemberId()`作为实际应用第一个actor参数；HTTP、限流、六个外层DeadlockRetry和38公开Java重载未改。

| Owner | HTTP映射 | 真实边界与本次检查 |
|---|---|---|
| Entry | nodes、route-state（2） | 保留原兼容GET入场命令/原state和角色时点，不冒称纯查询 |
| HistoryQuery | ending、my-completed（2） | 本人历史/完成事实；公共引用过滤仍交原service，未补写进度或权益 |
| PlayerQuery | companionLine、leaderboard、growth、achievements（4） | 本人actor、原活动/自玩scope；getGrowth只select或构造内存默认值，不ensure |
| BasicIntent | action-context、action-receipt（2） | context沿008/e99可写同事务刷新后锁读；receipt只读且先原报名归属再原回执 |
| BasicCommand | checkin、scan-entry、answer、sensor-result、arrive（5） | SCAN/ANSWER/GPS原receipt优先和原hash未动；4写入口REQUIRED/READ_COMMITTED，scan-entry只resolve不记进度；仅修sensor先lead再可写route |
| PhotoCommand | photo（1） | 原REQUIRED/READ_COMMITTED与外层retry；上传事实I/O仍在原窗口，完成链锁错误现在向外传播 |
| PuzzleCommand | puzzle/reveal、puzzle/hint、hint/unlock（3） | reveal/hint原应用事务；收费hint复用原PuzzlePlay owner，event14/ledger→member顺序未动 |
| Preference | preference、preference/submit、tag/confirm、tag/revoke、tag/correct、os（6） | 原actor与tag归属；原B1先lead；submit事务/重试仍在原PreferencePlayService；GET兼容canPlay入场语义保留 |

三Query owner源码均无state/ensure/assignOrGet/nodeRuntimeConfig/sharedRouteSnapshot/refresh调用；NodeQuery.tags实际为readOnly当前局投影。回执的授权→原回执→聚合锁→再次回执→版本→claim→效果→终态算法逐字未动。未改旧回调cleanup或当前小程序生产源。

## 对应测试、实际检查与未验证

- `PlayLeadAdmissionFailureTest`：原16case保留；原sensor故障case补`verifyNoInteractions(routes)`；新增集合拒绝零route/验证/经济效果、恢复后原actor/scope/action/input及canPlay先后，共18case。
- 新 `PlayCompletionLockFailureTest` 六case：两helper原样透传1213/1205/QueryTimeout、非瞬态best-effort保留、正常bingo两mask投影、真实Photo应用+Spring事务/外层retry代理下audit首败rollback→close→新事务、连续失败零后续进度奖励叶子。JDBC与进度奖励叶子为mock，绝不充作真库/奖励算法验证。
- 原`LogAttemptFailureTest`六case、`PlayCommandIntentTest`原响应丢失/相同意图重放/同键异输入/错误答案次数及UNKNOWN场景均未修改，随root原批次回归。
- 实际仅Python源码检查：25/25 actor入口、sensor scope/lead/route/proof顺序、两异常catch顺序、三Query禁写口、旧16case保留且仅一断言增强；`git diff --check`通过。提交前限定差异秘密扫描零命中。
- 未运行Java编译、上述Java测试、MySQL/HTTP/Redis/CI或Spring启动；不借用任何旧source的绿结果。root统一编译与组合验收。

无schema/数据迁移，无生产DDL。可分别revert两项提交；不回退先前冻结候选，回退会恢复原失败机制。

## 所有权外的精确触点（未修改、待root分派）

- `chengyinhub-system/.../impl/PlayBingoServiceImpl.java`：`onNodeDone→topicRule`的mapper异常被内部catch返回null；`onNodeDone→markRewarded→grantCellRewards`的发券异常也被catch。把这两处实际mapper/发券调用替换为1213异常时，异常可能在到达C.refreshBingo前已被吞，本次C传播修复不能覆盖它。root需交实际owner核验事务/经济合同，不能误以为本候选完成所有宾果闭环。
- `chengyinhub-admin/.../web/service/PreferencePlayService.java:submit`：固定key是`preference:member:topic:act:node`；已有row分支直接existingData，未比较原routeActionId/expected/choices。最小源码反例是同scope先保存choicesA后以相同actionId但choicesB/新expected调用，直接返A；新actionId也仍命中该row。existingData还按当前template重新生成disclosure。本应用壳不擅自改其“一节点一结果”业务或新增另一份receipt；需root确定由原service收口原命令fingerprint/新attempt和历史结果规则。不是把业务未知当NOT_EXECUTED或客户端换key掩盖服务端问题。
