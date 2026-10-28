# Mixed observer → capacity category合同修补（SOURCE / NOT_RUN）

结论：shared consumer只接受原固定8类；gameplay observer生产的三种字符串均不在其中。这是确定的源码接线不一致，不代表实际24个请求都成功或失败。本片只把producer映射到已有SUCCESS/LEGAL_AUTH_REJECTION/UNKNOWN，consumer白名单、预算、并发、请求/角色/SQL/回执/UNKNOWN围栏全部不变。

- parent：`e82229ef85e58159078a42956560356074192541`；独立分支 `codex/architecture-day-f-mixed-category-contract`。
- 原真实run40228818909/job121275951294、artifact12612168854：gameplay.run→observe_core_workload→run_curve→distribution拒绝unknown observation category；finalize的原非零检查是次因。协调方已核原首因与3容器精确移除。当前没有实际record.category字节制品，F不声称具体走了哪一个分支、不推定业务通过，也未下载重复造证据。

## 原调用与最小改动

| 生产者/消费者/调用者 | 本片 |
|---|---|
| `gameplay_process_recovery_probe.observe_core_workload.request` | 原command/replay/receipt的精确返回正文检查通过后，accepted改原SUCCESS；原foreign拒绝/无泄露检查通过后，authorized-rejection改原LEGAL_AUTH_REJECTION。成功仍是SQL验证之前的观测标签，原后续CAS/完整回执/重放/外来者零变检查不删 |
| 同request异常分支 | UNKNOWN_OR_CONTRACT_FAILURE改原UNKNOWN；保原failures记录，因此curve虽能统计UNKNOWN，原not-failures/wave guard仍失败。只新增固定reasonCode和固定stage枚举，不抄body、账号、token、SQL、cause，不推断NOT_SENT，不换原command identity或重试 |
| `capacity_mixed_probe.run_curve→distribution` | 执行源码**未修改**；原CATEGORIES八项逐字保留，拒绝任何旧alias/新任意类别，24请求、1/3/6并发、45秒、有限futures/stop/原UNKNOWN处理不变 |
| `gameplay.run` mixed调用 | 原load shared capacity→observe_core_workload(...capacity.run_curve...)不变；这是唯一业务adapter调用者 |
| 普通`capacity_mixed_probe.Probe.observe→run_curve(self.request)` | 原self.request/classify本来生产大写固定类别，不需修改；不扩扫/重跑默认旧容量项目 |

实际类型映射不增加权限/业务类别；UNKNOWN不被并进SUCCESS或LEGAL_AUTH_REJECTION。原非现金财务前后hash、72独立intent/144请求、无额外等待队列、真实SQL读取/预算/精确cleanup仍原逻辑。B host、C fullrestore、原Java/环境/CI/DAG均未修改。

## 直接控制SOURCE

保留所有原case与断言，新增四个方法：

1. consumer固定八类及旧alias/任意值负控，防为绿扩大白名单。
2. 真实gameplay observer调用真实shared run_curve/distribution；仅HTTP/SQL为明确内存protocol doubles，三wave/144调用/72原身份/正确SUCCESS和LEGAL_AUTH_REJECTION统计正控。不是MySQL/HTTP或业务验收证明。
3. command响应正文错误即使fixture已落原效果/回执，也只产生UNKNOWN并使原guard失败；24原身份保留，不自动重试到144，不输出canary/token/body。
4. foreign错误放行不计合法拒绝，统计UNKNOWN后仍失败；原replay/receipt成功与拒绝分别计数。

未知实际失败诊断仅新增 `reasonCode=GAMEPLAY_OBSERVATION_UNVERIFIED` 和 `stage=COMMAND/REPLAY/RECEIPT/FOREIGN/UNDECLARED_STAGE` 固定值。原kind/固定fixtureKey沿原合同保留，不追加原异常文本。没有把新增控制的模拟数据冒充原run观测。

完整3文件diff以review.patch.b64运输，decoded摘要在PATCH.sha256，全部作者后像在POSTIMAGES.sha256。root唯一实际应用和最终同源验证。本片仅Git diff/apply检查、摘要、有限新增行秘密模式检查；没有parse、测试、Node/Python控制执行、Java/DB/编译/外呼/CI，全部 **NOT_RUN**。不重试原失败run，不复跑已通过client/Clm等无影响项目。
