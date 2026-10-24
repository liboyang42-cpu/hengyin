# E：开局投影保留持久终态

基线/parent：`1b743737f374e6b4c251e2819062c510b11488f5`。独立工作树 `/workspace/hengyin-e-route-opening-terminal`，分支 `codex/architecture-day-e-route-opening-terminal`。不混入 `788fac20` 两个 leaf 调用，不改 A/C/G/root 源码。

原链：`themeAction(END)` 使用 `ThemeRuntimeEngine.terminal(...,"ABANDONED",...)` 保存 `state.status=ENDED`、清空当前/推荐节点，并提交 session `STATUS_ENDED`；随后 `toView/toLinearView→appendOpening` 在未出现 `sys.opening.done` 时无条件投影 ACTIVE、恢复开局 PLAYABLE。修复共同 `TopicRouteNarrativeProgress.appendOpening`，所有原调用者一并受益，Query 入口无补丁。

真实状态定义：session RUNNING=0、COMPLETED=1、ENDED=2；实际 terminal producer 生成 COMPLETED 或 ENDED。不能只凭 state.COMPLETED 判 session 已终结：`keepOpeningRunning` 保留“玩法已完成但待确认出发”的 RUNNING 会话，其原 ACTIVE 开局交互必须继续。

仅在原未确认开局投影处识别 session 两个已知持久终态，保留 state 的原 status/current/recommended（包括旧线性投影先派生了当前节点的路径），不产生 ACTIVE/PLAYABLE。完成节点标记保留，其他节点仍按原开局闸隐藏；不提前暴露后续站点。开局正文始终走原 `ChapterBlockReadSupport.projectForPlayer` 的完成事实/标签过滤，chapterId/name/complete/ready/themeComplete/rewardPolicy 等字段和原计算顺序不变。已确认开局分支不变。未修改 END 准入、奖励规则、scope、锁/CAS、事务或 receipt。

新增 `TopicRouteOpeningTerminalProjectionTest`：3 个方法、4 个待执行 JUnit invocation。两个参数场景使用实际 `ThemeRuntimeEngine.terminal` 产生 ENDED/COMPLETED，断言共同投影不复活、不新增 PLAYABLE、原正文可见性/字段/身份版本及输入 state/session 保持；旧算法会在终态 status 断言失败。两个非终态场景锁定 RUNNING+ACTIVE 的下一开局节点，以及 RUNNING+COMPLETED 尚待确认出发的原 ACTIVE 展示。测试只用真实内存规则对象；唯一 mapper double 必须零调用，不冒充完整 END/HTTP/数据库验收。

实际执行仅 `git diff --check`、Python 源码闭包/唯一方法差异检查、候选三个文件 secret-file-gate/有限内容扫描。未安装固定 gitleaks，完整扫描仍由 root 执行。Java 编译、JUnit、MySQL/Redis、CI 均未执行；无新依赖、schema、DDL、生产操作或专项工作流。

生产变更仅新共同 owner 的此方法；原 Runtime、publication compiler、root projection leaf/facade 字节未变。无需数据迁移；回退该增量会重新暴露已记录的终态复活缺陷，不能作为安全修复。
