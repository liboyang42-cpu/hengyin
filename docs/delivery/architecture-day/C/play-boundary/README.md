# C 玩法应用边界候选

已实施机械拆分：Controller 3848→318 行，58→8 个业务注入；25 条实际 HTTP 映射（任务估数24）、38个既有公开 Java 入口重载保留。12 个 owner 按查询、scope/冻结输入、完成事实、回执、命令、奖励组织，详见 owners.json（涵盖全部110个移出的方法体）。没有复制 runtime 或奖励算法；Controller 不传业务 lambda，应用不回调 Controller。

基线 d49c12b820419308236010e0710f96d7bf48c4c9；独立 codex/architecture-day-c-play-boundary。只变更 C 控制器、新应用类、随真实 owner 迁移的测试接线。三个 runtime、生产表、金额规则、JWT/RBAC、CI、台账均未改。读回公共 master44699155：#567/#585/#586 三个 merge 对此 Controller 无路径差异；本候选不整树覆盖 master，最终由 root 保留其余主线规则。

原 HTTP DeadlockRetry/Transactional/限流/参数/错误码保留。应用命令使用同选项的 REQUIRED 事务加入原边界；没有第二重试环。偏好提交仍由已有 PreferencePlayService 的事务/重试包裹原完成回调。回执授权→原回执→聚合锁→再次回执→版本→claim→效果→终态顺序保留；原照片上传事实/审核ID/奖励封顶和 Hint event14及ledger→member锁序未改。

测试仅改装配/反射观察 owner；现有用例名和业务断言保留。PlayNodeAccessTestFixtures 在每次显式替换后重建真实应用对象图，后置桩仍生效；测试 no-lead 替身保留在测试侧。根独占 hint 真库类未改，PlayHintUnlockMySqlIT.review.patch 仅替换两条旧 Controller 字段注入；root 需应用后统一回归。ChapterFlowWriteServiceTest 仅两处调用已经移出的 isPrevDone 改观察 owner，其他 D 装配不动。

实际检查：Python 静态逐体核对110、HTTP路径/顺序与公开签名、应用依赖DAG、旧测试用例名；git diff --check 均PASS。未运行Java编译、任何Java/MySQL/HTTP测试、Spring启动或CI；不借用旧源码结果。统一回归还需 root 的相关单元/真库/启动与 action-ledger 真实owner适配。

## 尚未闭合的纯读端口（禁止当已完成）

机械拆分保留原行为，以下旧写口没有藏成“已纯读”：

- PlayNodeQueryApplication.nodes / routeState 调用 ITopicRouteRuntimeService.state，后者可能 ensureBranchSession/ensureJourneySession；现端口没有无写的同授权/冻结投影。需要对应 runtime owner 提供真实 queryState，再由 C 接线，不复制其规则、不以空状态降权、也不把readOnly注解当防写实现。
- nodes 的 playerRoleService.assignOrGet 会分配角色；现有 PlayerActivityRoleMapper.selectByActivityAndMember 可读已分配角色，但缺少“首次角色在哪个既有显式命令分配”的授权业务入口，机械批不擅自改变玩家首次拿角色的行为。
- GET preference 原 routeAccessError→canPlay 可能建局；共用既有 PlayNodeAccessService 的 B1身份/lead/unlock/route次序全部保留，需要同一 runtime只读准入端口才能改纯读。GET 未加大事务。

本批可审查/可集成源码拆分，不代表纯读目标或统一回归已验收。回退应整体回退本批应用+Controller+fixture接线，不单独删 owner；无DDL/数据迁移。
