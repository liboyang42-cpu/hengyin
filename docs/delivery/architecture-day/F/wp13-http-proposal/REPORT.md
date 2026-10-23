# F / WP13：LLM 同步 HTTP 最小契约提案（仅文档）

结论：真实 LLM 出口尚未复用 common 的有界 HTTP 能力。可先收口 `OpenAiCompatibleLlmClient.post`，保留协议、路由、quota/Photo 原身份；必须同时由原 owner 接好可信 NOT_SENT 分类。此候选只有调查、接口提案与测试计划，生产实现、Java、真库、故障隔离演练均未执行。它不代表 WP13/T13/T19 完成。

## 身份与范围

- 公共观察/候选 parent：`addeb6edc912223ce2a5d3d9d89d87b2ebb84adf`，实际独占分支 `codex/architecture-day-f-wp13-http-proposal`。原观察 `00fc58c50febc3366f78af600c8f12636cd13de5` 到该 SHA 的相关 common HTTP、AI 出口与本文 quota caller 源码无变化。
- 逐 blob 复核清单内 39 个生产 Java/XML 文件与 `00fc`、`66e9e1798bf0b3e1c2b7e6cb2b6ab13a3b3e1329`、`9b92fb5738244c677fea7470232f67d3b97b7a98` 全部相同（各 39/39），可等源引用本文观察；不推定其他文件或测试结果等同。
- 仅新增本目录文档。不修改冻结 `155c88ef52f7f75023de464c8a886c29d017cdba` checker 或 `685743902d32252adb2cb320a5f5e59b4af2f866` producer，不改 workflow、registry、业务 XML、额度/资金/Photo 意图和 HTTP 安全实现。
- 规范依据：[完整 WP13](../../COORDINATOR/governance-scope.md)。该包还要求业务关联、全部外部通道预算、支付与状态读回隔离；这里仅提出第一条 LLM 出口的最小施工切口。完整源文件指纹、精确锚点见 [evidence.json](evidence.json)，调用者见 [CALLERS.md](CALLERS.md)。该清单是源码证据，不是调用图或运行时证明。

## 失败机制与可复用能力

`OpenAiCompatibleLlmClient.post` 使用 Hutool `.timeout(timeoutMs).execute()`，随后 `.body()`。text/vision 共用该出口；同步执行已可能先缓存整个响应，事后截字符串不能限制峰值内存。这里没有单调时钟总 deadline、响应字节上限或 provider 并发准入。socket read timeout 不能阻止每次都在 read timeout 内滴一字节的无限响应。源码未显式禁止传输层 retry/redirect，不能仅凭没有 Java 重试循环证明不会重发。

common `HttpUtils` 已具备：8 核心/32 最大线程、队列 128、AbortPolicy；1 MiB 响应上限（声明长度预拒绝及流式累计）；默认 connect/read/total 3000/5000/10000 ms；总预算含排队；安全 Apache client 禁自动 retry/redirect；超时 abort 使用已有有界 cleanup（1..4、队列 64）。这些能力已有 `HttpUtilsSecurityTest`，本轮只读未运行。全局属性可改变有效值，以上是源码默认值，不是生产配置测量。

现有公开 `sendPost` 不支持 Authorization，也不提供 NOT_SENT 证据；`HttpRequestException.statusCode=0` 和 `retryable=false` 都不是未发送证明。`sendSSLPost` 是 legacy trust-any 通道，不能拿来适配 AI。仅把 Hutool 换成现有 `sendPost` 无法正确闭合此任务。

## 最小后续改动契约（需 root 分配生产范围）

采用已有 executor/client/cleanup/readLimited，不另建线程池、依赖或韧性平台；`LlmClient.chat/chatVision` 的 String 返回和消息 JSON 不变。

| 文件/方法（相对 Java 源根，完整路径见证据） | 拟改接口/行为 | 实施权限与限制 |
| --- | --- | --- |
| common `utils/http/HttpUtils`：增加一个认证 JSON POST 入口；复用 `executeAttempt/executeBlocking/createRequest/readLimited` | 拟形状 `sendJsonPost(url, json, bearer, deadlineNanos, attemptLease)`；不接受任意 header map；仅 Authorization/JSON，禁 CR/LF，禁止凭据进入 URL；安全 TLS、POST 恰一次、无 redirect | root 指定 HTTP owner；不改变老入口策略或全局属性，不导出凭据 getter/toString |
| common `HttpRequestException` | 增加只读 `SendDisposition { NOT_SENT, MAY_HAVE_BEEN_SENT }`；旧构造器默认 MAY_HAVE_BEEN_SENT；固定错误码/消息、无原始 cause/suppressed | 只有 transport 内部具备下面 CAS 证据时创建 NOT_SENT；enum 不是外部请求可提供的证明 |
| system `OpenAiCompatibleLlmClient.post` | 使用认证入口、仍严格解析 choices/message/content；固定阶段错误映射；将可信 NOT_SENT 转成专用且无 cause 的 `AiProviderNotSentException`；其他异常维持 UNKNOWN 保守映射 | 不根据 HTTP status、异常类、空 body 或 retryable 推断退款 |
| system `LlmClientConfig` + 一个小型 `AiHttpAdmission` helper | 启动时由现有四个 provider 配置装配有限组的 Semaphore；全局 AI 与每 origin 同时准入，fail-fast、无额外等待队列；lease 交 transport | 不是 Spring 全局 executor/动态注册平台；不按 member、请求 URL、key 建无限 map |
| `AiGatewayServiceImpl.generate`、`PhotoCheckVisionService.reviewOnce` | 在现有 generic UNKNOWN catch 前加可信 NOT_SENT 分支；通过原 member/pool/day/nonce/receipt 记录原额度补偿；Photo 原意图保存 NOT_SENT | 只由其原经济/Photo owner 实施并真库验证；F 不先改。generic UNKNOWN 路径及原意图不动 |
| `AiCreatorServiceImpl.themeDraft` | root 已确认窄修复：typed AiProviderOutcomeUnknownException 跳过 release/retain，原 receipt 交原 worker 进 UNCERTAIN；generic 输入/配置失败仍沿用原 refund | 独立 root 生产所有权；保留仅完整有效 draft 计费及既有输入审核 UNKNOWN 可退政策，不将所有失败改成扣费 |

接口参数是可审阅的施工形状，不要求引入通用 request/result 框架。`attemptLease` 只负责此次 worker 生命周期及一次性释放，不允许业务回调在 HTTP 线程做 DB/Redis 操作。若现有 common 实现无法用一个窄 lease/state 扩展准确表达，先交具体 diff 复核，不能以释放许可的竞态换取简化。

建议首次评审取值：全局 AI 上限 4、每 canonical origin 上限 2、额外 AI 队列 0；总 deadline 为 `min(有效 common total, 有效 provider timeout, 10000ms)`，connect/read 还分别不超过 3000/5000ms 与剩余总预算；无效 provider timeout 在本地拒绝。响应解码流累计最多 1 MiB，gzip 解码后也须受限。这些是待 root 审定的限值，不是测得的容量/SLO。不得修改进程级 HTTP 属性给其他调用者降预算。

provider 分组按启动配置的 scheme/host/有效 port 规范化，禁止 user-info，不能以 bean 名区分同一 origin：qwen/deepseek 当前由同一 `AiModelConfig` 构造，vision 也可能同源。不同 host 是否同一供应商/共享计费配额，静态源码不能证明；由 root 确认是否需要配置别名同组。不得根据运行时请求增建无限 group，不记录 key/签名/原 URL。URL/TLS/SSRF 安全标准由原 HTTP owner 审定，不能借此放宽。

全局 AI=4 小于 common core=8，最多让 AI 占用四个 common worker/排队位置，剩余由现有通道竞争；这只给出占用上界，不是支付/地图/数据库/CPU 隔离证明。图片预处理仍在此 admission 之前，必须在完整 WP13 后续另列内存、任务与通道预算。

## NOT_SENT 的必要证明与并发许可寿命

复用现有请求状态，在同一次 attempt 内加入原子状态：`QUEUED -> DISPATCH_ENTERED` 或 `QUEUED -> NEVER_DISPATCHED`。worker 在调用 `client.execute(request)` **紧前** CAS 为 DISPATCH_ENTERED；拒绝、取消、排队超时与 worker 竞争这个状态。worker CAS 失败必须不调用 execute。即使 DNS/connect 最终失败，进入 execute 后一律保守 MAY_HAVE_BEEN_SENT。

| 事实 | 发送分类 | 原额度/业务行为 |
| --- | --- | --- |
| disabled/缺配置、准入拒绝、executor 拒绝，且此 attempt 无 execute；构造/序列化/URL 校验失败且未 dispatch | NOT_SENT | 原 receipt 持久记录补偿意图后由现有 quota recovery 执行；不能另造 nonce |
| deadline/interrupt 在 QUEUED 阶段 CAS 获胜，worker 已被状态禁止 execute | NOT_SENT | 同上；恢复 interrupt 标志 |
| worker 赢得 DISPATCH_ENTERED，任何 timeout/断连/5xx/4xx/超限/坏 JSON/未知竞争结果 | MAY_HAVE_BEEN_SENT，业务 UNKNOWN（收到可分类结果按原 owner 契约） | 不自动退款、不换 nonce/请求号重发；原号查证；HTTP POST 一次 |
| crash 发生在可信 NOT_SENT 尚未持久化前，只有 MODEL_STARTED | UNKNOWN | 不能根据超时、租约、线程退出或旧 receipt TTL 推成 NOT_SENT |

取消后的 `Future.isDone()`、`cancel(true)`、调用线程 finally 都不能释放仍在执行 HTTP 的 permit。permit 只在 worker 实际退出的 finally 释放；若从未启动且已 CAS 禁止 dispatch，则取消方可释放，必须 release-once。worker 启动、尚未 dispatch 时取消竞争也须防双释放。排队 Future 取消仍可能留在 ArrayBlockingQueue，需只对本次可证明取消的 task 做安全回收/最终出队验证，不能全局 purge 他人任务。cleanup 队列满时仍不允许 CallerRuns 或新线程；连接/worker 未退出即保留占用，后续 AI fail-closed。这保证长挂起不会通过一轮轮 timeout 重新获得 AI 并发。

deadline 在 `OpenAi.post` 开始、准入与 `toJSONString` 前用单调时钟计算；序列化后只传同一个绝对 deadline（不重新给一份 total），common 再取自身预算上界。覆盖准入及剩余预算检查、排队、连接、header/body；序列化/解析不能被宣称可抢占中断，编码前已发生的分配也不在此证明内。图片 encode、输入审核、业务总时长及默认 streamChat 的逐字回调不属于 HTTP 总 deadline。请求大 base64、图片解码/GC 仍须另外预算，不把响应 1 MiB 上限套在原图片 32 MiB 规则上。

## 当前 owner 阻塞与传播检查

1. **玩家 themeDraft 的 UNKNOWN 退款风险（静态可达，未实跑）**：`AiCreatorServiceImpl.themeDraft:122` 进入 gateway；gateway 对 player/theme_draft 不自行持有 reservation，`generate:206..224` 把 chat 异常转 `AiProviderOutcomeUnknownException(Reason.UNKNOWN)`。外层 catch 只设置 INPUT_BLOCKED，finally `:155..157` 在 `!accepted && !inputBlocked` 时释放玩家原预占，`releasePlayerThemeDraftQuota:631` 请求退款。精确原 charge/stage 与最小受控负例见 [QUOTA-COUNTEREXAMPLE.md](QUOTA-COUNTEREXAMPLE.md)，root 已认领独立生产修复。root 已确认仅 typed providerUnknown 不得被 finally 当普通 pre-send failure 退款，准备 typed flag 跳过 release/retain，由原恢复 worker 进入 UNCERTAIN。既有仅完整有效稿计费、普通输入审核 UNKNOWN/配置失败可退的政策保留；不要求新增 beforeModel callback 或给玩家池补 MODEL_STARTED 接口。收到结果但解析失败等不属于这个窄修复，不能默认计费。
2. gateway/Photo 都先持久化 MODEL_STARTED，再调用 client；新增 admission 的拒绝发生在这之后。因此单纯换 HTTP 出口会把明确本地拒绝留作 UNKNOWN。新增 typed NOT_SENT 只能让其原 owner 针对原 reservation 补偿，不能全局把 RuntimeException 视为可退。
3. `NpcChatService.applyGatewayFailure` 已对 typed UNKNOWN/RECEIVED 保持原 PROCESSING/UNKNOWN；新 NOT_SENT 经 gateway 映射后必须核对它的 NOT_RUN 分支与原 requestId 重读策略，不能在一层 UNKNOWN 到另一层变为新调用。Photo `executeOnce/read` 原意图复用、结果回执和原 nonce 保持。
4. `ObjectNamer` 共享 vision bean、无 quota 池，错误返回 null，铸卡外层已有 taskBudget/cancel。它也必须计入同一 origin/全局 admission；不能由于外层 cancel 提前归还仍在 HTTP 的 permit。外层 null 不证明外部未执行，是否需要 WP08 持久意图由其 owner 另审，F 不改铸卡收费或恢复。
5. OpenAi 出口已按 provider/phase/status/异常类脱敏，无 cause。必须保留，并审计跨层：AiCreator 的解析异常/部分模型文本日志、Voice 原始响应/异常、Encoder URL/cause、ImageSeg parse body 仍有泄露风险（见调用清单）。不能宣称整条 AI 链已安全。已有 generation 业务审计的 payload/保留策略另属业务治理，不擅自删除审计。

## 可观测性、验证与回退

复用现有 requestId/MDC/AlarmService；只增加固定 provider group、phase、errorCode、admission rejected、in-flight/queued 数量、deadline/response-limit 计数及耗时，绑定原 traceId/receipt/intentId 的工作由 owner 在业务层完成。不能用 member/nonce/URL 作无界 metric label。不得把 Authorization、签名、原 body/图片、parser/transport cause 或 suppressed exception 放日志；供应商 request-id 亦仅允许经过现有安全过滤的关联信息。无指标后端新增依赖，不用每次影子差异写主业务表。

[TEST-PLAN.md](TEST-PLAN.md) 是待实施的正常/违规与恢复场景，含慢滴流、队列饱和、超大响应和其他通道见证。必须在获准 JDK21 CI 实跑原始 Surefire XML+身份核对；MySQL/Redis 仅现有获准真实存储路径。普通测试使用 `*Test` 防默认 IT exclude 漏跑；新增真实存储场景由 root 更新现有精确登记/数量。日志、mock HTTP、静态查询均不能冒充真库/支付链路或 T19。

本轮实际只执行 doc-only scope、来源指纹/锚点、JSON/链接、diff、秘密扫描（结果见 verification.json）；未运行 Java/MySQL/Redis/jdeps、未 dispatch CI、未访问模型/生产、无生产补丁/DDL。root 已报告 07172 source gate findings=0/review=85，compiled jdeps 因 testCompile 失败仍 UNKNOWN；该历史不作为本候选 HTTP 功能验证，也未将 addeb 的 CI 推定为通过。root 后补报告 `66e9` 的 run `40036832794` Backend 最终 5 个 unit failure、source gate 0 findings/87 review；`9b92` 仅修一行测试类型及回执。该批 CI 未由本任务逐 artifact 回读，不能据其他 job 成功宣称 HTTP/compiled jdeps 已验证。

回退：文档可独立 revert，无 schema/迁移。未来若实现需回退，优先停用原有 AI 配置或令新入口 fail-closed（明确未发送），保留原 receipt/意图/UNKNOWN 和恢复任务；不能回退成无限 body、自动 POST retry、trust-any TLS，不能清库/重置 UNKNOWN 或新建 nonce。common/adapter/typed caller 作为一个已验证合同回退，不能只撤掉发送分类而保留自动退款分支。HTTP 在途许可只随实际退出释放；重启不是外部未执行证据。

root 已认领玩家草稿 typed-unknown 窄修复；下一步只需分配 common HTTP owner、AI quota/Photo 的新 NOT_SENT 接线 owner，并审定 origin 分组与初始 4/2/0 限值，再实施上述小切口；不需要新审批 JSON。业务已收到结果的收费语义仍是明确决策阻塞。
