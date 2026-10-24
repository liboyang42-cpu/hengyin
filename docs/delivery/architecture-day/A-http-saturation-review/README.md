# A：真实清理线程池饱和夹具复核

结论：提交可单独应用的夹具修正提案；本候选只新增本目录文件，没有修改任何 Java、生产配置或共享测试。未运行 Java/JUnit、HTTP、数据库、Redis 或 CI；负控尚未验收。

观察源/分支：公共仓库 `ef5331db7a4424acca5dfde5325e83149ff3e472`（parent `7d9a8702f5651b7ecce5f1f06fa83ab27b47d541`），独立工作树 `/workspace/hengyin-a-http-saturation-review`，分支 `codex/architecture-day-a-http-saturation-review`。此前 A/E 候选保持冻结。

## 失败机制与调用链

- 协调者提供的历史回执：run40054582158 在 Build 中本例提交 blocker 时出现 RejectedExecution（pool=4、active=3、queue=64）；first fix ef533 后 run40055275716 在本例约276行等待四个 blocker 进入超时。此处没有重新下载或实跑核验这些回执。
- `HttpUtils.java:74` 是同一个静态生产 executor：core=1/max=4/keepAlive=30s/queue=64/AbortPolicy。`ThreadPoolExecutor.execute` 在 workerCount 达到 core 后先尝试入队，仅队列满才尝试新增非核心线程。冷池只提交四个 blocker 时，通常是一运行、三排队，原 `cleanupEntered(4)` 无法归零。热池已有四个存活线程时，快速固定提交68次又可能先填满队列，而某个空闲 worker 尚未取任务，导致提交提前拒绝；此前 SecurityTest 已解释同类调度边界。
- `OpenAiCompatibleLlmClient.post:166` 取得原 admission lease，调用 `HttpUtils.sendJsonPost:138`；`executeAttempt:294` 提交物理 HTTP worker。超时进入 `cancelAttempt:340`：取消 Future，尝试 `abortAsync:516`。清理 executor 拒绝时仅记录拒绝，不释放 admission。
- 物理 worker 的 callable finally `HttpUtils.java:303` 调用 `AttemptState.finishWorker:794`，才释放已发送请求的 lease；`executeBlocking:456` 的 finally 还会终止实际 request。`AttemptLease.close` 在所有权已转交 transport 后不会提前释放。夹具必须让物理请求保持阻塞，不能以 caller Future 已结束作为释放证据。

## 提案边界与确定顺序

`review.patch` 只针对 `AiHttpAdmissionTest.realAdmissionStaysHeldAfterCallerTimeoutAndCleanupRejectionUntilWorkersExit`：

1. 保留原先等待 HTTP/cleanup 双池空闲的入口，记录 cleanup 原 core。
2. 在真实生产 executor 上临时将 core 提到其既有 max=4，提交四个自有 blocker；等待四个都实际进入。该阶段最多只有四个新任务，原64容量队列不会被本夹具填满，兼容冷池与已有1至4个空闲线程的热池。
3. 在嵌套 finally 立即恢复原 core，再填原64个队列槽。四个 worker 已在执行自有 blocker，降低 core 只会中断空闲 worker，不会打断这些持有 worker 执行锁的任务。blocker 不释放则不会取走队列任务。新增断言明确观察4 running +64 queued，且 core 已恢复；随后才调用被测 client。
4. 不改 max、队列、拒绝策略、keepAlive、生产类、deadline、worker/lease 状态机或 HTTP 发送路径。此时4个存活 busy worker 是原配置在压力下可达的状态；没有替换 executor。被测超时/拒绝/UNKNOWN/原 origin 与全局槽位断言全部保留。
5. 最外层 finally 无条件打开 body/cleanup 两个闸门，再恢复原 core。使用 `assertAll` 逐一 join 全部已成功提交的自有 cleanup futures 及 caller replies：其中一个 join 报错也会尝试其余任务；嵌套 finally 仍执行双池空闲检查。原每项5秒 join、3秒进入、4秒reply、8秒退出等预算不变，没有重试或延长超时。最后原“物理 worker 已退出后同 origin 两槽恢复”断言保留。

异常清理：临时升 core 或提交/await 任一步失败，会先经过内层 core 恢复，再经过外层双闸门释放；队列填充、provider进入、reply或业务断言失败也经过外层清理。只 join 本夹具持有的 futures；不取消/删除别人任务，不清空队列、不 shutdown 生产 executor。join 的异常通过 JUnit assertAll 报出，不吞掉失败。正常恢复需同时看到 HTTP 与 cleanup 的 active=0/queue=0；没有把 Future.done 当物理退出。

## 其它 fixture 与未验证项

全仓 Java 调用搜索：生产清理 executor 写入口只有 `abortAsync` 和 `disconnectAsync`；`AiHttpAdmissionTest`、`HttpUtilsJsonPostTest.blockedPhysicalPostRetainsLeaseWhenDeadlineAndCleanupRejectionReturnToCaller` 通过反射访问同池，`HttpUtilsSecurityTest.saturateDisconnectExecutor` 走真实 disconnectAsync。未找到其它测试修改该池 core/max/keepAlive 的调用。本 patch 不改这些其它 fixtures；成功/异常路径均恢复原 core，且等待自有任务及双池空闲。

仓库未发现启用 JUnit 并行执行的配置；沿用这些共享静态池夹具的既有顺序执行前提。初始 idle 检查不构成跨测试互斥，本提案不声称支持同时运行共享池饱和用例。其它 fixtures 的独立调度问题也没有在本提案中修复或验收。

静态检查只能证明补丁可应用、范围与断言保持；不能证明四个 blocker 在真实 runner 的5秒预算内必然被调度，也不能证明负控业务断言通过。以上仍由 root 原路径统一真实编译/运行，尤其需看 saturation 后超时返回、物理阻塞仍持有槽位、释放后重新准入，而非仅 Build 通过。

## 复核与应用

在本候选工作树运行 `python3 docs/delivery/architecture-day/A-http-saturation-review/verify_static.py`。脚本只用 Git/Python，临时独立 index 验证 patch，无工作树源码写入。输出见 `verification.json`，包含原/提案 SHA256 与原断言数量。

root 在以 ef533 为测试原 blob 的独立候选中可执行：

```sh
git apply --check docs/delivery/architecture-day/A-http-saturation-review/review.patch
git apply docs/delivery/architecture-day/A-http-saturation-review/review.patch
```

如果集成源不同，先核对原测试 SHA256；不得覆盖并行变更。本候选不自动应用补丁、不 dispatch CI、不部署。无需 schema/迁移；回退仅移除本夹具 delta。
