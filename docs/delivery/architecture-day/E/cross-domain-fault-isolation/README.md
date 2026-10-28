# E T19 跨域故障隔离组合源码

状态：SOURCE_COMPLETE / NOT_RUN。父 fa5c263440573992701263d6bee60830844a49b8，公共仓库 liboyang42-cpu/hengyin。生产源码、旧测试、SQL、CI/registry 均未修改；没有新依赖或 runner。

## 未来唯一入口（root 登记与派发）

显式 Surefire 点名 CrossDomainFaultIsolationMySqlIT；只有 1 个 @Test：
sameWindowRetainsOriginalEconomicIdentitiesAndRequiresUnpoisonedNotificationProgress。
同包 CrossDomainStorageFixture / CrossDomainTransportStage，以及 CrossDomainSubscriptionProvider / CrossDomainSchedulerStage 均为 test classpath helper，不是额外 suite/生产入口。

不含 @Disabled、Assumptions 或条件跳过。需要：
- RUNNER_ENVIRONMENT=github-hosted；
- CHENGYIN_MYBATIS_IT_HOST=127.0.0.1 或 localhost，显式 PORT/USER/PASSWORD（允许明确空密码）；
- B_PAYMENT_FACT_DDL_APPROVED=1，沿用现有 canonical fact migration scratch 授权；
- CHENGYIN_AI_REDIS_IT_PORT / CHENGYIN_AI_REDIS_IT_DATABASE，loopback、DB 0..15，密码沿原可选 CHENGYIN_AI_REDIS_IT_PASSWORD。

新 suite 不读 AI_MYSQL 另一套连接，所有父进程业务 SQL 共用上述一库一池。root 需按现有 real-storage registry 规则登记这个显式入口/1例/原环境 gate、迁移及清理凭据；本包没有编辑共享入口或伪造已登记。

原公共执行参考：AiHttpAdmissionTest（真实 admission/HTTP 及 worker-exit 故障缝）；TencentMapClientBudgetTest、MapGeocodeBudgetTest；OfficialBroadcastDeliveryMySqlIT；ClmCommercePaymentProofMySqlIT；AiNotSentQuotaRecoveryMySqlRedisIT；SchedulerWorkFenceTwoProcessMySqlIT / SchedulerFenceProcessProbe。现有这些 suite 没有一个同时消费本组合的原支付、玩法、通知和调度身份，因此未重复调用它们冒充组合见证。

## 真实调用链与共同资源

1. PhotoCheckIntentService Spring transaction advice → PhotoCheckVisionService → 实际 Gateway + AiQuotaRecoveryService + 实际 MyBatis/Redis Lua → OpenAiCompatibleLlmClient → 原 AiHttpAdmission / common HttpUtils。两个受控 loopback origin 各 2 个 POST；正常 RECEIVED、busy NOT_SENT（只退自身原 nonce）、已 dispatch 超时 UNKNOWN 分开。完整原意图、原日/nonce/reservation/hash 保留，恢复后 read/execute 同号不重发。
2. MapGeocodeServiceImpl 两个实例 → 同一真实 TencentMapClient 原进程级 4-slot semaphore → 受控 HttpURLConnection。原 origin/key/状态/响应 JSON 校验保持；同 key 与多不同 key/不同 cache 实例不能加外呼。读取与 disconnect 分别 barrier；过期响应不可成 city/缓存，实际物理退出后原坐标显式读可恢复。
3. 实际 OfficialBroadcastServiceImpl.runDueScheduled → 持久逐通道意图 → 真实 WxSubscribeMsgServiceImpl DTO/openid/模板/errcode/catch → 既有 package-private post 缝仅改送受控 loopback 地址 → 原 common HttpUtils 超时。不是 mock 整个订阅服务、并行替代调度队列或永久拒绝 stub。先发 subscribe 的 provider 不返回；同一 producer queue 在原 HTTP timeout 后必须推进后续 inapp，真实 IM message/receipt 同事务提交。首通道 UNKNOWN 的原行不自动重投，恢复的新广播独立 ID 只发送一次。
4. 实际 verified fact/原订单 intent → WechatPaymentCallbackRouter → MerchantCommerceService 原行锁/CAS/proof/requireConsumablePayment/权益授予事务 → 真实 fact、oms_order、merchant_subscription 回读。原金额 1990/CNY/商户/app/交易号/hash/Reference 固定，错误 amount 拒绝；故障/接管/恢复三个时点重放不二次授予。这里是可信 typed provider 观察到 callback/domain 的链，未模拟微信 SDK 密码学签名或真实 HTTP 回调。
5. 实际 TopicRouteRuntimeServiceImpl.queryState → 真实 CmsTopic/CmsRegistration/TopicRouteSession mapper，保留原已付报名范围；读取既有 sessionId/version=7/ACTIVE，其他 actor 拒绝；state_json、会话数量和 action 数量不变。没有 ensure/写入伪状态或 HTTP 200 空见证。
6. 复用原 SchedulerFenceProcessProbe 主类，不复制 coordinator/runner。原 READY PID+canonical+实际 coordinator class hash 核验后才发原命令。原 leader 连接失联但旧 task session 活着时，successor 不能进 body；任务连接再失联后原迟到 worker UNKNOWN，由原 receipt 保证 entries=2/effects=1。随后只终止该已核验、由父进程创建的 successor JVM，第三个 owned JVM 接管同 canonical/原业务 ID；entries=3/effects=1。后者是 test-only 无经济 probe，不冒充真实领域资金幂等证明。

父进程所有业务/通知/支付/玩法/AI ledger Mapper 使用同一 Druid（maxActive=12/maxWait=2000ms）。原 probe 每子 JVM 仍有其原 maxActive=8 池，最多 2 个同时活着；真实共享的是同一 MySQL/schema，不能称与父进程共享 Java pool。common HTTP 原 8/32 workers+128 queue、disconnect 原 1/4+64、AI 4 global/2 origin/0 admission queue、map 4 均不重写。复用原 admission lifetime fixture 对 disconnect 的 4 workers+64 queue 注入 owned blockers，暂改 core 到既有 max 以启动原 4 worker 后立即恢复，不增 max/queue。

## schema 来源及精确清理

独占 schema=cy_wp14_<UUID32>，CREATE 不带 IF EXISTS，成功后才记 owned。名称兼容原 probe 严格协议；独立 information_schema absence 回读。仅此库删除，无全局 prune/DDL/生产连接。

- P0AcceptanceSchema.baseTables 原 base_schema：sys_config/ums_member/club/club_member/oms_order/merchant_subscription/cms_registration。
- 同 ClmCommercePaymentProofMySqlIT 原 canonical 迁移序列：club open setting/role membership/resume fence、commerce idempotency、business audit、wx pay original intent、callback inbox/outbox、verified fact、CLM/Commerce immutable proof。
- 原 migration_20260930_registration_first_successful_scan.sql。
- 原 it-schema/cms-topic-it-schema.sql；原 topic_route_graph 的 session/action CREATE 原文。
- 原 ai_quota_reservation_recovery / photo_check_intent 首 CREATE（原其他 fixture 同方式，不新建 play/经济表）。
- 原 it-schema/official-broadcast-delivery 的 broadcast/log 两个 CREATE；ums_member 使用上面完整基线，随后原 broadcast delivery migration 和 IM/idempotency/media visibility migrations。
- 只新增原 probe 同款 wp14_probe / wp14_probe_receipt 两个 owned scratch 计数表；不是生产 schema 或资金账本。

Redis 只清理由原 SQL reservation 持有且校验 MEMBER/counter/receipt 前缀的 exact counter、generation、receipt key；随机 fixture actor 首次确认无已有 counter/generation，清后逐 key absence。无 FLUSHDB/全 Redis 扫描。收尾释放所有 owned gate、等待 business/provider/HTTP/cleanup worker、按原协议停止 owned child，再 close pool、DROP 精确 schema 并独立确认 absence。try-with-resources 与 suppressed errors 保留首因；部分阶段观测保留 T19_PARTIAL_OBSERVATIONS，成功阶段 T19_OBSERVATIONS 只输出分类/计数/fixture elapsed，不输出原输入/凭据。

## 限制及待统一验收

- 所有 Java/编译/测试/Node/Python 测试/MySQL/Redis/HTTP/进程/容器/故障/CI：NOT_RUN。没有 RPS、生产 SLO、全 Q4/T19 完成或通过声明。git diff 格式检查不是测试。
- 所有供应商为 test classpath 受控边界。AI 使用本地真实 HTTP；地图用受控连接和单调时钟，不是腾讯真实时延；微信只在原 post 缝映射地址。原媒体/consent、固定广播 audience、获取 access token 是 doubles；真实 member/openid 读、业务事务/锁/账本/额度/回执并未替换。不是 HTTP/JWT、GPS/位置授权或供应商收费验收。
- 16MiB synthetic encoded image 仅复用原 transport blocked-write lifetime 故障条件，并不证明真实上传/编码允许该尺寸；编码器 double 不纳入该见证。原客户端未加重试。fixture 5/15 秒观察界限不等于生产 SLO；soft deadline 不等于 DNS/TLS 硬终止。
- 先前从 broadcast 循环发现的同步等待是条件接缝，继续读实际 WxSubscribeMsgServiceImpl 后已确认原 common HTTP timeout 可作真实推进机制，故没有把“接口永久不返回”冒称生产 BUG。最终严格要求实际同一队列在 controlled provider 仍被持住时处理合法后项；若真实运行不满足，保留失败、由 root 分配生产 owner，不改断言或开平行 dispatcher。
- probe 的原15秒 task barrier、子 JVM startup 与 cleanup 上限全部保留。没有 JVM 集群生产运行、真钱退款/打款、备份丢失灾备 T20 或全部 runtime 并发验收。
- root 最后同源 compile、零 skip XML、各阶段原身份/观测、原错误与独立 CLEAN 仍是必要证据。本候选仅源码供体，不替代原已过 suite，也不继承 fa5c 绿色。
