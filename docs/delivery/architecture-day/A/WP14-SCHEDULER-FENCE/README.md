# WP14：任务会话在业务准入前失联

代码候选补齐了已取得任务锁、尚未进入业务的所有权复验；另提供 3 个固定 `@Test`、真实两个子 JVM 的 MySQL 夹具源码。**Java / MySQL / 两进程均未运行，不是 WP14 完整验收。**

基线：公共 `cc91b0845e3abd4a2060d54a857c8c179bd22d4f`；独立工作区 `/workspace/hengyin-a-wp14-scheduler-fence`，分支 `codex/architecture-day-a-wp14-scheduler-fence`。不包含 A 冻结提现、玩法拆分或 B/F 源码；没有生产 DDL、依赖、CI/registry 修改。

## 修复的具体窗口

`AbstractQuartzJob.execute` / 内置调度回调 → `MySqlSchedulerCoordinator.execute` → canonical target → 独立 task `GET_LOCK` → 第二次领导会话复验 → `lastFire.put` → `action.run`。

原第二次复验只检查 leader/epoch/领导连接。任务连接可在 `GET_LOCK` 成功后被 MySQL 终止，而领导连接仍正常；原实现仍写 `lastFire`、进入业务，直到动作完成才发现 task 锁已经失效。本补丁仅在第二次复验后、`lastFire` 前添加 `work.isOwned()`，失效抛明确“任务未调用”异常；该 fire 未被消耗。父 Quartz 的异常、日志、MDC 接线完全保留。

此检查不承诺检查完成后连接永不失联。动作已进入后，task 会话失联可让后继 JVM 获锁；旧进程仍可能继续运行。原有末尾检查继续报告需核实，不能把它当业务资金 fence。

## 已有证据与本次新增源码

已有证据只读复用，没有重写原测试：

- `COORDINATOR/hint10-handoff3-actual.json`：run40039899867 / source `ad7e59fbf6a20e85e741318afe654c5411bf1101`，handoff3 实际 3/0fail/error/skip、CLEAN：旧 epoch、取得 task 锁后领导失效、领导断连但在途 task 连接健康。它们是同 JVM 两 coordinator，不是两进程证据。
- 同 run 的 session3 曾 LEFTOVER，见 `scheduler-original-cleanup-first-run.json`，不能计为清理验收通过。后续 `full-ci-50f-40043150702.json` / source `50f3c4d3c67383b8ea2e3e230d7f16f2e73f3eb6` 记录清理修复后的 session3 实际 3/0fail/error/skip；不将旧源码结果移作本候选结果。

新增 `SchedulerWorkFenceTwoProcessMySqlIT`（3 普通 `@Test`）和 `SchedulerFenceProcessProbe`（子进程 main，非额外测试类）：

| 新用例 | 物理屏障和断言 |
| --- | --- |
| task 连接在准入前失联 | 两 JVM/不同 bean alias 与引号归同 canonical；第二次 leader 查询前只 KILL 本 schema 的 task 连接；服务端重新取锁证明释放；零业务 effects；同一 fire 再次合法执行一次。旧源码应因 effects=1 失败，此负控尚未运行。 |
| 领导断连、task 存活 | 旧 JVM 在动作内阻塞，KILL 其领导连接；新 JVM 成为 leader，同 canonical 被拒；释放旧动作，只产生一个 effect。 |
| 已进入动作后两个连接均断连 | 后继 JVM先完成，旧 JVM 迟到为 UNKNOWN；fixture 观测两个 attempts，但同原身份、同事务 receipt 只允许一个 effect。该 receipt 是测试表，不是实际经济业务验证。 |

每个子 JVM 读回实际 PID 和 coordinator `.class` SHA256，与父进程及另一个子进程核对。使用真实 Druid/strict-wall、原 `MySqlSessionLock`、真实 `KILL CONNECTION`；JDBC proxy 仅设置确定断连窗口，不伪造 SQL 返回。断连前检查 named-lock owner 及 processlist DB；不清理别人的连接。

夹具只在 `RUNNER_ENVIRONMENT=github-hosted` 且显式 loopback `CHENGYIN_MYBATIS_IT_HOST/PORT/USER/PASSWORD` 下运行。密码可显式为空；子进程环境清空后仅传本 fixture 必要变量，命令参数没有密码。使用现有 Maven/Surefire classpath，未启动应用。随机独占 `cy_wp14_<UUID>` 库，逐子进程 STOP/必要时仅强杀自有 PID、等待物理退出，读回锁释放；最后仅 DROP 自建库并确认不存在。强制退出、初始化、断言、清理失败都报错，不 skip/retry。

root 后续仅需将上述 FQCN 登记为 tests=3/storage=mysql/gates={}，按现有受控 runner 统一运行；本候选没有修改登记表或 dispatch。测试将 admission fire 固定为 2 并将 fixture activatedAt 置 0，不证明真实时钟偏移、misfire 或 Quartz HTTP 手动入口。

## 业务原身份与未闭合范围

| 实际 caller | 已有业务归属/恢复依据（源码观察，非本次实跑） |
| --- | --- |
| `WithdrawTransferQueryTask.run` → `syncPendingWithdrawals` | 原提现 ID/CW/CD、原单锁、DEBIT/回补事实；本片不替代提现 UNKNOWN/双审闭包。 |
| `RefundReconcileTask.reconcile` → `RefundPayoutServiceImpl.payoutAfterReview` | 原 refund ID/RF，`claimPayoutDispatch` 的 leaseToken；回写 CAS 仍校验原 token。租约结束不换 RF；尚未逐一实跑迟到渠道回执。 |
| `StoreVerifyGrowthTask.run` → `GrowthServiceImpl.deliverStoreVerification` | 原 registration ID 待办 `selectForUpdate`，奖励与 markProcessed 同事务。 |
| `RoamRewardDeliveryTask.run` → `RoamRewardDeliveryService` | 原 dropLogId、XP intent 行锁；coupon `grantCouponBySource(...,"reward_drop",dropLogId)` 原来源回执。badges finally 分支未在本次验证。 |

同 canonical 别名的锁身份已统一；不同方法即使处理同一经济事实也不共享 task 锁，必须继续使用业务原身份，不能从不同 target 推断应允许或拒绝。同一经济任务所有实现/生产启用的 `sys_job` 列表、内置注销/CRM/状态任务的完整副作用不在本次证明范围。

`SysJobServiceImpl.run` 的成功仅表示 afterCommit `scheduler.triggerJob` 已排队；现有源码没有独立持久化人工触发原意图。进程可在排队后、动作前退出；后继重建 cron 定义不等于恢复该次人工触发。是否需要对人工命令承诺持久执行须由 root 明确；不能凭本片或切换 Quartz store 宣称闭合。`lastFire`/epoch 是本地内存，重选时清空，activatedAt 拒绝旧 fire，不能代替持久完成回执。

若进一步要求跨失联/恢复的经济“至多一次”，最小合同应由各业务 owner 接受：以现有不可释放业务 ID 锁定并读取原事实；本地效果和原终态回执原子提交；外部动作先提交原意图，复用原号，未知和迟到回执按原意图查证。协调器 epoch/租约不授权重新创建业务身份。本候选未擅加跨域 token/schema 或修改 money 写侧。

静态验证：`verify_static.py` 复用冻结 gate 的 `scan_java`，核对唯一生产增量、原测试完整、3 固定入口和文件 hash；`git diff --check`。实际输出见 `static-result.json`。这些只证明源码形状，不是编译、执行、清理或经济幂等通过证据。
