# WP14：既有调度锁复用清单及真实接管交错候选

结论：既有真库三例可直接复用；新增 `SchedulerHandoffBoundaryMySqlIT` 三例，仅补真实 MySQL 下 in-flight 任务互斥、旧 epoch 回调和任务锁取得后的领导权复查。**新例未编译/未执行，0 个实际业务结果、无 CLEAN 回执；既有用例的源码覆盖不能冒充其本轮执行通过。** 生产协调器、锁、G 准入、ScheduleUtils、所有既有测试和 IM/Photo 均未修改。

## 基线、文件及候选关系

- 仅公共仓库 `liboyang42-cpu/hengyin`。本地/公开 parent 均为 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`。
- 实际协调源码核对到 root 同源候选 `66e9e1798bf0b3e1c2b7e6cb2b6ab13a3b3e1329`：协调器、锁、三个生产调用者/配置、CanonicalJobTarget 和下列既有测试均与公共基线 Git blob 相同，因此本任务从公共基线建立独立测试分支，没有复制其他域改动。
- 工作树 `/workspace/hengyin-e-scheduler`，分支 `codex/architecture-day-e-wp14-scheduler`；唯一新增 Java `chengyinhub-admin/src/test/java/com/chengyinhub/quartz/util/SchedulerHandoffBoundaryMySqlIT.java`，专属 fixture 同类内；另一新增文件为本报告。候选 SHA/tree/ref 在交付消息单独给出，不能自引用提交 SHA。
- 不改根 pom、CI、IT 注册表或 migrations，不修改 G/E87/IM/Photo，不 dispatch。root 集成时只纳本两文件 delta。

核对的主要原文件 SHA256（这些不是本轮执行回执）：

| 文件 | SHA256 |
| --- | --- |
| chengyinhub-quartz/src/main/java/com/chengyinhub/quartz/service/MySqlSchedulerCoordinator.java | a419e344dd6513ed7cbc562dff608172e5a1989abd298e4d403c9f5b61d3b6d1 |
| chengyinhub-quartz/src/main/java/com/chengyinhub/quartz/util/MySqlSessionLock.java | 4ed674d724d3ff35491eaba04f736fe52989cc08cf14f1d8b734bbdcdb970085 |
| chengyinhub-admin/src/test/java/com/chengyinhub/quartz/util/SchedulerSessionLockMySqlIT.java | 7b6669cd0ab85083937d1c46d962708db3ed8cef6b76e5c38574f5a86f9ab6c8 |

## 真实调用链和既有边界

搜索所有 Java 调用者后，生产入口如下（路径均只读）：

1. `chengyinhub-admin/.../web/job/LeaderScheduledJobsConfig` 注册四项已有内置计划：注销 cron、商家 CRM 导出轮询、导出清理 cron、状态 heartbeat 轮询；协调器生成的 callback 捕获当时 generation。
2. `chengyinhub-quartz/.../util/AbstractQuartzJob.execute` 将 invokeTarget、scheduledFireTime 和原任务 runnable 交给协调器；通过协调器后，调用 `SysJobServiceImpl.isCurrentInvocation` 检查暂停/删除/目标变化与手动调用标志，再真正执行及写 job log。本任务不启动这些任务、Quartz 或外部动作。
3. `chengyinhub-quartz/.../service/impl/SysJobServiceImpl`：init 校验真实持久定义并 registerQuartz；refresh 重新读定义后控制既有 RAM Quartz；配置变更通过 definitionLock 串行至事务完成，调度应用在 afterCommit 再验 leader；manual run 也要求当前 leader，原暂停/任务准入由其 owner 管理，保持冻结。
4. `LeaderJobsQuartzConfig` 关闭 Quartz 自动启动，由已验证 leader 控制；`SmartLifecycle.start` 的私有 control 线程周期 maintainLeadership。新 IT 不启动 lifecycle 控制线程，采用既有测试同样的手动 maintainLeadership 入口和 running fixture。

协调器合同：leader named lock 绑定独立物理会话；任务另取按 `schema + canonical invocation` 派生的 named lock，首次领导权/epoch 检查与取任务锁后的复查都必须满足。loseLeadership 增 generation 并 cancel(false) 已注册 callback；generation/lastFire 是进程内事实，activatedAt 阻止 successor 补执行它激活前的原 fireTime。任务已入 action 后不会被强杀；任务自己的业务回执/外部意图必须提供领域幂等与恢复。

`MySqlSessionLock` 使用真实 `DATABASE()/GET_LOCK/IS_USED_LOCK/RELEASE_LOCK`，Lease 持有连接，关闭时释放并 abort 物理会话；definitionLock 可延迟至事务完成。锁不是事务行锁，也不是持久业务收据或外部 exactly-once 证明。

## 可直接复用的现有清单（不重复造例）

建议 root 点名 `SchedulerSessionLockMySqlIT`，其三例使用真实 MySQL + Druid strict WallFilter，已在现有 `ci/backend-test.sh` 列出：

| 现有方法 | 真实覆盖 | 未覆盖/限制 |
| --- | --- | --- |
| separateSessionsExcludeEachOtherCommitDoesNotReleaseAndCloseDoes | 两个物理连接互斥，commit 不放锁，Lease close 后可再取 | 无执行中任务/epoch 交错 |
| terminatedLeaderStopsAdmissionAndSuccessorReadsConfigButCannotRepeatOldFire | 两个协调器对象、KILL leader 会话、旧 leader 拒绝、新 leader 接管、同 fireTime 拒绝 | 接管过程为顺序调用；readConfig callback 只计数，不代表真实业务恢复 |
| concurrentDefinitionsSerializeThroughCommitAndHistoricalPausedAliasesRemainVisible | 真 sys_job mapper、定义锁至提交、第二事务冲突、历史 paused alias 重复不删除/恢复，Quartz mock 零调用 | 不测试 in-flight 任务执行，G 准入语义保持原样 |

现有单测/装配安全网可直接点名，不重写：

- `MySqlSchedulerCoordinatorTest` 五例：leader/fireTime、captured epoch、in-flight 锁/异常、任务会话丢失不报成功、disabled 不取连接；使用 **SchedulerJdbcFixture mock JDBC**，不能当真库接管回执。
- `MySqlSessionLockTest` 三例：同会话释放/abort、schema key、事务完成释放、abort 未知不回池；**mock JDBC**。
- `CanonicalJobTargetTest` 一例：alias/空白/解析值同身份、参数类型/字节不同身份；不涉及真实 SQL。
- `SysJobServiceImplSchedulingTest`：paused alias/builtin 冲突、queued invocation 复核、非法 cron、resume 等；协调器/mapper/Quartz **mock**。现有 G 准入工作不得由本任务改变。
- `AbstractQuartzJobFailureTest`：失败/成功日志、日志失败、nonleader/committed-pause 不伪造成功日志；协调器 **mock**。
- `BackgroundJobsDisabledTest`：默认/禁用、残留或 manual 触发保护及调度装配；包含受控调度 fixture，不能代替多会话真库故障。

本报告的“既有覆盖”指实际源码中的测试内容；E 未下载这些 suite 的本轮 Surefire XML，运行状态应由 root 同源 receipt 核实，不能宣布全部已绿。

## 新增三个确定交错

| 新方法 | barrier 与真库断言 |
| --- | --- |
| inFlightTaskSessionSurvivesLeaderDisconnectAndBlocksSameCanonicalSuccessor | old action entered 后阻塞；SQL 确认 leader 与 task 是不同物理连接；仅 KILL leader；successor 接管时旧本地 leader 标记仍 true；同 canonical（字符串单/双引号解析相同）执行返回 false，action 零次；不同参数身份可执行；旧 leader 新请求拒绝。释放旧 action 后确认原任务完成一次、task lock 释放，新的显式调用才可执行。 |
| dequeuedOldEpochCallbackCannotStartAfterTheSameCoordinatorReacquiresLeadership | 捕获协调器实际创建 callback，先证明可执行；KILL/maintain 失去领导权并取消 future；已出队旧 callback 在失权后、同对象重新取得 leader 后均零新增 action；新 epoch callback 确实执行。尤其后一次 isLeader=true，不能仅靠 disabled/失权让断言假绿。 |
| oldLeaderPausedAfterFirstOwnershipCheckCannotRunAfterLateTaskLockAcquisition | 用真实 DataSource 外包装，仅在下次 getConnection 前暂停：此时已通过 execute 首检，尚未取得任务连接；KILL 原 leader，successor 取得真实 leader 并执行同身份；不先刷新旧对象本地标记，恢复旧线程后，真实任务锁取得后的复查必须拒绝、旧 action=0，并放回任务锁。 |

新例不替换 JDBC/协调器/锁 SQL：Druid + strict WallFilter、真实 MySQL named lock、真实 coordinator/CanonicalJobTarget。PausingDataSource 仅暂停一次取连接，不拦截/更改 SQL 或结果。CapturingTimer 是标准库 ScheduledThreadPoolExecutor 的受控时间入口，保存 production callback 和 cancellation future，不启动后台执行；显式调用捕获 runnable 模拟已出队的迟到 callback，不能调用已 cancel 的 FutureTask.run 来制造无执行假绿。

并发使用 CountDownLatch 和 Future.get 有界等待，无 Thread.sleep、轮询或重试到绿。KILL 仅允许来自本随机库 leader key 的实查 connection ID；与既有 IT 一样，使用 loopback 直连控制连接执行 KILL（Wall 本来禁止 KILL），其他协调与观察均走 Druid/Wall。KILL 返回不保证服务端清理已结束，因此用**一次有界 GET_LOCK** 取得再释放原 leader key，作为服务端释放 barrier；失败直接红，不假定本地 leader=false 就等于服务端释放。

任务仅 AtomicInteger/空 action，无资金、通知、provider、系统任务、真实外部请求或任务准入变更。只验证调度互斥和 admission 边界，不宣称领域效果已幂等。

## 连接、清理与实际验证状态

- 只认显式 `CHENGYIN_MYBATIS_IT_PORT`（合法端口）、`CHENGYIN_MYBATIS_IT_USER`（非空）、`CHENGYIN_MYBATIS_IT_PASSWORD`（必须存在、可显式空）；固定 loopback；MySQL >=8；无额外 gate/skip/H2/默认 root 或端口。
- 随机独占 schema `cy_sched_boundary_<32hex>`；不建业务表、不执行生产 migration，无新增 DDL 依赖。Druid maxActive=8、maxWait=3000ms，无新增机器/依赖。
- 每例 finally 释放 barrier、结束 worker、stop 两对象、关闭 timer，并由 SQL 检查 leader/task locks 已释放；AfterAll 关闭 Druid，仅删除本类创建的随机 schema，information_schema 验证库不存在。root 应记录三例实际执行数与 CLEAN；初始化失败须记业务 0/3。
- E 云 VM 仅编辑和轻量静态检查，不编译/运行 Java/MySQL、不 dispatch。实际编译、三个新 IT、上述复用用例及同源 CI 结果均待 root；不能用先前 IM 新两例 run40036842511/source66e9 的通过回执给本次新例盖章。

实际已执行：`git diff --cached --check` 无输出 exit 0；`python ci/secret-file-gate.py` 输出 `SECRET-FILE-GATE PASS`；三例/真实 JDBC 与 one-shot gate/无 mock、sleep、retry、skip、leader/epoch 覆写的静态夹具检查输出 `STATIC FIXTURE PASS`；新增两文件 token/私钥有限扫描输出 `LIMITED DIFF SECRET PATTERNS PASS`；生产模块、migration 与原 SchedulerSessionLockMySqlIT 相对公共 parent 的零 diff 检查 exit 0。静态括号检查不是 Java 编译。

公开身份在提交交付时给出。当前无安装的 gitleaks，既有固定版本下载曾被 proxy:8080 阻断；不改代理/凭据/网络，不新增依赖，有限扫描需 root 固定版本复扫。

## 规范边界、UNKNOWN 与回退

规范来源：同源 `docs/delivery/architecture-day/COORDINATOR/governance-scope.md` 的 Q3/Q8、WP14、T13/T19/T20。Q3 要原身份可定位恢复；Q8 要扩实例前证明接管和业务幂等；T13/T19 要旧 worker 被栅栏拒绝、故障不扩散且无重复经济效果。本轮只为其中**调度领导会话/任务锁/进程内 epoch 的窄机制**补证，不是完整 Q3/Q8/T13/T19。

未知/未验：两独立 JVM 进程崩溃、网络分区和连接半开；任务自己的 lock session 在 action 中丢失时的领域恢复/经济幂等；真实任务事务与持久 intent/receipt/fence；外部动作；持续负载/容量/积压隔离；业务进程重启原意图恢复；全部 T20 备份后本地意图丢失/外部已成功/原编号与 ID 高水位/RPO/RTO 对账。

特别是既有 `workSessionLossDoesNotReportSuccessfulExecution` 仅证明 mock 场景不伪报成功，不代表能撤销已在 action 中的效果。新例中的旧 in-flight action 在自己的任务锁仍持有时允许完成；release 后的新显式调用不是原意图重试。不得把进程内 generation、canonical 互斥或同 fireTime 拒绝说成持久业务唯一性，也不设计统一重投/重派发策略。

未实际执行前没有真库反例结论。若新例红，保留确切交错/SQL/失败断言交 root 分配生产 owner；不能删断言、改变旧 fireTime/epoch、弱化锁或修改 G 的 paused admission 配合 fixture。回退仅撤销新增测试/报告，不撤回任何生产修复或别人的改动。
