# F WP13：请求、异步与原撤回审计的日志关联

Parent `cc91b0845e3abd4a2060d54a857c8c179bd22d4f`；独立分支 `codex/architecture-day-f-request-correlation`。仅公共历史。7b3 verifier/owners/baseline、CI/registry/schema/权限不改；Roam仅增加日志边界，业务正文不变。

**源码完成；Java/JUnit/真实Quartz/MySQL/Redis/CI/运行日志读回全部 NOT_RUN。** 本片不代表全系统Q7/WP13完成。

## 已接入源码

- `common.utils.LogCorrelation`：8个固定诊断键的有界ASCII值白名单（requestId/commandId/entityId/receiptId/jobId/jobRunId/event/fence），不传播其它MDC字段；fence只接受数字，不采样lease owner token。Scope在实际执行线程保存/替换/最终恢复原MDC，嵌套/CallerRuns/异常均保留调用者上下文。仅日志，不参与授权、claim、epoch或幂等。
- `RequestIdFilter`：保留原安全X-Request-Id合同；整个header/chain进入作用域，连setHeader失败也恢复原状态。客户端requestId不是服务端业务commandId。
- `AsyncManager.execute`：原10ms/原scheduler/原拒绝策略仅加提交时快照包装；失败在原快照作用域中记录固定事件/安全IDs/异常类型，再原样抛出。`Threads.printException`保留Future观察/中断行为，只去掉异常消息/stack/cause输出；Future原异常不变。
- `RoamPresenceRevokeAuditService`：仅给原deliver加commandId/event作用域及尝试/结果日志；原函数正文逐字迁至private deliverAudit，事务、claim/fail/complete、owner、退避和retryDue不变。false只记NOT_CONFIRMED，不认作FAILED/NOT_SENT。原告警使用安全commandId/jobRunId，无正文或lease token。
- `QuartzJobCorrelation`是日志支持，不领取工作；runId只存本次JobExecutionContext瞬态属性，绝不进持久JobDataMap。`QuartzFailureAlarmConfig`读取该ID并在告警回调内try/finally恢复MDC；原固定事件/限频/异常解包不变，无ID不伪造，控制符任务名不回显。

## A/root串行接线：尚未应用

**AbstractQuartzJob、MySqlSchedulerCoordinator及其既有配套测试在本候选逐blob等于parent，未直接修改。**

[AbstractQuartzJob.review.patch](AbstractQuartzJob.review.patch)只供root/A审阅串行合并：

1. 原execute外层一次 `try (LogCorrelation.Scope ignored = QuartzJobCorrelation.open(context))`，原方法体保留为private executeCorrelated；finally清原threadLocal。BeanUtils原读取后只给MDC放原jobId，不新增数据读取/调度动作。
2. 两处原错误日志及sys_job_log.exception_info只写异常类型；原JobExecutionException/cause/suppressed继续原样传播。原job_message追加jobId/jobRunId。表结构、成功/失败、存储调用次数和所有原JobDataMap/invoke_target字段不改。
3. 配套既有4场景全保留；“原异常消息写入日志”的断言改为类型和原传播消息分别断言，追加失败/skip清理两例。

已对cc91执行 `git apply --unidiff-zero --check`；A在并行改共享源码，不能覆盖其新版文件，须逐hunk适配。**应用前，生产Quartz入口尚不生成runId，告警只保留原标识；不能宣称已闭环。** 本候选新增Quartz支持测试可以独立编译；依赖共享接线的原执行器测试增量仅在review.patch内，避免让未接线候选假绿或无故红。

## 一条实际生产恢复链及最小断言

`ApiRoamController.changePresenceVisibility` 在原关闭事务中生成 `presence-UUID`，原 `remember` 落 `roam_presence_revoke_intent.request_id` → 原afterCommit → `enqueuePresenceAudit` → **实际AsyncManager** → `deliver(originalId)`。若原consent写失败，原fail/告警仍走原ID；scope日志把原HTTP requestId与原commandId相连。之后原 `roamPresenceRevokeAuditTask.run()` → `retryDue/selectDue(50)` → **从原表取同一commandId** → 同一deliver。共享补丁应用后，恢复日志再把同一commandId与新jobRunId相连，不伪造原HTTP ID、不换原业务ID、不输出两次claim的owner token。

该任务原登记见 `chengyinhub-admin/sql/migration_20261012_roam_presence_revoke_job.sql`；本轮未执行迁移、未确认实际注册/运行或启用任务。

新增12个普通 `*Test` 源码用例（不是IT、不加默认exclude），覆盖单worker复用、提交时冻结、任务自行污染MDC、RuntimeException/Error/inline清理、异常日志无body/cause、filter链/header异常、Quartz瞬态ID/旧JobDataMap保留、告警失败恢复及非法label。`LogAspectAuditTest`追加例真实调用AsyncManager；`RoamAuditLogCorrelationTest`真实加载Filter/Scope/Service/Task，仅替身存储/事务/告警，模拟原失败→新service恢复，断言原claim/fail/complete ID/owner顺序、两个scope关联以及无lease/body日志。**这些Java用例均未执行，Mock不是MySQL/真实Quartz证据。**

实际只做：git diff/patch检查；复用仓库既有有限Java mask做括号配对（不是Java语法/类型检查）；原Roam函数body/remember/retryDue逐字核对；旧LogAspect/Quartz告警测试方法正文不变；原配置secret-file/gitleaks扫描。根可在原批准路径统一运行上述6个测试类，应用共享补丁后加AbstractQuartzJobFailureTest，并读回同源原XML和真实日志。

剩余：协调器内建计划/其它直接scheduler或独立pool（如CommittedBusinessNotice、NpcChat等）本片未统一接线，Quartz之外入口没有证据不能冒充覆盖；receipt/entity/fence仅在原调用者已有可信ID时传递，不从body/错误文本猜取。MDC不会跨进程持久化，全系统Q7仍需root实日志与业务原账读回。无新库/依赖/线程池/数据库；回退为本片源码与串行接线整体撤回，不删任何持久意图或审计事实。

补丁格式已改零上下文，避免补丁自身的空白上下文行被diff空白检查拒绝；临时Git从cc91逐字验证应用结果等同原提案。该格式修正不修改任何执行/测试源码，A的新源必须逐hunk审阅，不能只按行号盲套。
