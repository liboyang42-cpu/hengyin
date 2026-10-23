# 待实施验证合同（本轮全部 NOT_RUN）

只在已批准 GitHub-hosted JDK21 路径或现有真实存储 harness 执行。无真实 provider、付款、微信/语音收费生成或生产访问。HTTP 由测试进程内可控 loopback fixture 提供；本轮没有启动该 fixture/Java。

## 先写失败测试，再补根因

| 场景/有意义负控 | 可观察断言 | 拟落点 |
| --- | --- | --- |
| 正常文本与 vision；fake Bearer 与 messages 精确协议 | 返回原 strict String；vision content 数组/图片不变；捕获请求只在测试内断言；无 redirect | 扩展 OpenAiCompatibleLlmClientVisionTest（普通 Test） |
| slow drip，每次间隔短于 read timeout；另测 delayed headers | 总 wall clock 在配置 total + 有限调度容差内失败；server 仅一次；worker 最终归还；移除 total 的负控应失败 | common HttpUtilsSecurityTest 已有 totalTimeoutStopsAResponseThatKeepsDrippingBytes，可复用 fixture，新增认证 POST/AI 入口断言 |
| known ContentLength >1 MiB、无长度 chunked、gzip 解码后 >1 MiB；恰好上限 | 声明超限不等待 body；stream 在上限+至多一个固定读 buffer 拒绝；压缩不能绕过；恰好上限且协议正确可成功；服务端计数1 | common 现有 knownOversizedContentLength/streamedResponseBodyLimit 扩展，新 AI 入口回归；不只断言事后 substring |
| POST 5xx、收到请求后丢 ACK、302 | server 原请求计数1，redirect target0；无自动 transport retry，MAY_HAVE_BEEN_SENT；移除 disableAutomaticRetries 或开启 follow 的负控红 | 复用 defaultGetAndPostNeverRetry/postIsNotAutomaticallyReplayedByTransport/transportDoesNotFollowRedirectsForAnyEntryPoint |
| 同 provider 两条占满；第三条；qwen/deepseek alias | 2 个物理任务上限，第三条立即 typed NOT_SENT、server0；不同 bean 同 origin 不绕过；group map 不随请求增长 | 小型 AiHttpAdmissionTest + OpenAi 真实 fixture，不用 sleep 盲等，latch 精确持有 |
| 不同 origin 占满全局4；第五条 | provider 各2且总4，fail-fast无新队列/CallerRuns；释放真实 worker 后可恢复 | 同上；4/2/0 为批准后的测试参数，不写成容量结论 |
| common 8..32/128 提交饱和 | 只占用专用 test fixture 控制的任务；被拒绝 AI server0、NOT_SENT、原 permit 一次归还；不全局 purge；测试 finally 释放所有 latch | common package seam/现有 executor fixture；不把生产池永久塞满 |
| QUEUED deadline 先赢/worker dispatch 先赢两个竞态 | 前者 CAS 禁 execute，server0、NOT_SENT；后者 UNKNOWN、不可补偿；只 cancel Future 但未阻止 dispatch 的负控红 | common 单次 attempt 状态测试，确定性 barrier，不凭异常名字判断 |
| worker 还卡住时调用者 deadline 返回，cleanup 队列也饱和 | permit 仍占用，新 AI NOT_SENT；真实 worker finally 后恰一次 release；重复 cancel/中断不能双释放；提前在外层 finally 释放的负控红 | 共用现有 disconnectQueueSaturationRejectsWithoutRunningInCaller fixture；无需新增 executor |
| queued cancel 被留在队列、worker 启动与取消竞争 | 只移除当前 task/最终出队，无僵尸累计与双释放；Future.done 先于物理退出不能作为证据 | common 测试 seam，不全局 purge 或清他人资源 |
| local URL/header/config invalid 与 interrupt 前/后 dispatch | 本地可证明无 execute 才 NOT_SENT；进入 execute 一律保守；CR/LF 拒绝、TLS/redirect 不降级；interrupt 标志保留 | HttpUtilsSecurityTest + OpenAi 普通 Test |
| 日志/exception tree 毒数据（fake auth、签名 URL、prompt、base64、response、parser cause/suppressed） | 日志与抛出消息/cause/suppressed 不出现毒标记，只固定 code/provider/phase/status；新 NOT_SENT 类型亦无 cause | 扩展 OpenAiCompatibleLlmClientLogRedactionTest/common 现有 redaction；所有标记为人工合成不使用真实密钥 |
| AI 4 个物理 HTTP 挂起时非 AI common GET/POST | 非 AI fixture 在局部限定预算完成，原 header/返回/无 retry 不变；同时 AI 不超过4；不能只检查线程数 | common 并发 fixture；证明共享池局部 headroom，不代替支付 T19 |
| player/themeDraft typed providerUnknown | typed providerUnknown 跳过 release/retain，原 receipt 待原 worker UNCERTAIN；generic 输入审核 Reason.UNKNOWN 仍退款正控；不新 nonce/重发；当前 typed finally 释放负控红 | 经济 owner 扩展 AiQuotaRefundRulesD08Test/AiGatewayServiceImplTest，并补原 receipt 真库场景；收费语义待审 |
| 非玩家与 Photo admission NOT_SENT | 原 receipt member/pool/day/nonce 一致，REFUND_DUE 持久化一次；Redis 暂不可用后只原号恢复补偿；Photo 原意图终态 NOT_SENT，回读无外呼 | quota/Photo 原 owner：扩展 AiQuotaRecoveryMySqlRedisIT、PhotoCheckIntentMySqlIT；F 不编辑 |
| 已发 timeout/响应超限/坏 JSON、crash 在 NOT_SENT 决策持久化前 | 原 quota/意图 UNKNOWN 可查；不退款、不新 nonce、不重发；租约/TTL 过期仍不推断未执行；NPC 保留原 UNKNOWN | 同上 + AiQuotaReservationRedisIT；不得用 mock/H2 替真 MySQL/Redis |
| ObjectNamer 外层 taskBudget 到点/中断 | 原 null 降级及卡名审核不变；内部 permit 到真实 HTTP worker 退出才释放；不占 Photo quota；共享 vision group 正确 | ObjectNamerTest + ObjectCard 现有测试由其 owner 扩展 |

root 确认现有玩家池是仅完整有效稿计费、普通输入审核 UNKNOWN 可退；此次窄修复仅 typed providerUnknown 不释放，不改变无效稿政策。quota owner 还需回归“已收输出但被内容审核/业务 JSON 拒绝”的既有收费规则。本提案不替其选择退款/扣费。测试预期必须以审定合同为准，不能为了全绿将 UNKNOWN 当成 NOT_SENT。

## 执行与报告真实性

普通测试保留 `*Test` 命名，放已有 admin test 根，与 common/system 生产类现有测试位置一致。不得新增依赖，直接复用 JDK HttpServer、latch、已有 Mockito/JUnit。不要通过全局 system property 改别的并行测试预算；如沿用现有测试属性 seam，必须串行隔离并 finally 还原。

实现后由 root 使用现有 ci.yml workflow_dispatch 的 Backend tests；报告 source SHA、run/attempt、实际测试名称及数目、每份原始 Surefire XML sha256。复用 `ci/assert_it_ran.py` 和现有 identity upload，不新增 job/并行。新增 MySQL/Redis 测试由 root 更新现有 real-storage 精确 suite 登记和 expected count；必须检查 excluded `*IT` 没漏跑、不 skip。仅 workflow 总绿、只编译、只拿 Jar 或 source gate 通过均不计 Java/真库执行证据。

完整 T19 还需受控应用测试：慢 AI/地图、毒任务/领导者失联同时施压，实际支付回调与状态读回仍有预算，积压指标可见，恢复无第二次经济效果；只用已有批准 fixture 和环境，不调用真实付款/收费生成。本轮无这一证据，也无 compiled jdeps 图、capacity/SLO 或端到端可观测性验收。
