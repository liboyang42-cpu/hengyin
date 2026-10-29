# E：可信 AI NOT_SENT 的原配额与 Photo/NPC 接线候选

结论：实现两处精确类型 catch；新增 11 个专属普通单测、4 个真实 MySQL/Redis IT 及一个专属测试夹具。**Java 编译、11 unit、4 IT、MySQL/Redis CLEAN 均未执行**，由 root 联合 F 候选后验收。不是 transport CAS、真实模型、完整 AI 经济链或 T19 的通过证明。

## 身份、所有权与依赖

- 仅公共仓库 `liboyang42-cpu/hengyin`。parent/root 基线 `26f3764ce0d81594be02c0eeace04aacdd483e58`，tree `0ef0b436292cb36a81088285a69a4da0dab9a97b`。
- 独立工作树 `/workspace/hengyin-e-ai-not-sent`，分支 `codex/architecture-day-e-ai-not-sent`。采用公共对象及稀疏 checkout，未切换/回退其他工作树；未迁入私有历史或凭据。
- F 必需依赖：`663c2c634e4cff53c876630fe4e166d3fcd29d20`（parent `05f4aa69e61752dcafb0bec73ca9ae9b2ff7a7f5`）。其 `public final AiProviderNotSentException extends IllegalStateException` 只有无参构造、固定消息、无 cause，没有 getReason。精确 blob `9d36567ac74ca13c6f8ed4e733b7b8e0c846ba08`；本地只复制此原文件作编译依赖，**不纳入 E 提交/树**。
- E delta 不能单独在原 root26f 编译，root 须先联合 F 最终实现再编译/测试。F 的 HttpUtils/HttpRequestException、AiHttpAdmission、OpenAiCompatibleLlmClient、LlmClientConfig、全部 F 测试/文档均不在 E delta；不重建这些接口。
- root 独占 `AiCreatorServiceImpl` typed-providerUnknown 修复，E 不修改。NpcChat、PhotoIntent service/mapper/schema、准入、CI/注册表及既有 tests 保持原字节。旧 E cache/IM/Photo/Scheduler 候选均冻结。

## 文件清单

生产仅两文件各一处：

1. `chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/impl/AiGatewayServiceImpl.java` 的 generate 模型调用 catch。
2. `chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/PhotoCheckVisionService.java` 的 reviewOnce 模型调用 catch。

新增五个 Java 文件均在 admin test 根：

- `business/service/ai/AiNotSentGatewayFixture.java`：专属真实 Gateway + unit mock 夹具；IT 用真实 Redis/recovery 替换配额双桩。
- `business/service/ai/impl/AiGatewayNotSentQuotaTest.java`：4 例。
- `business/service/ai/PhotoCheckVisionNotSentQuotaTest.java`：5 例。
- `business/service/impl/NpcChatGatewayNotSentContractTest.java`：2 例，复用旧 NpcChatServiceTest 的初始化，原类/业务用例不改。
- `business/service/ai/AiNotSentQuotaRecoveryMySqlRedisIT.java`：4 例。

另一新增文件仅为本报告。候选 SHA/tree/ref 及本地/公开映射在交付消息另给，不把 API 不同 SHA 伪称相同提交。

## 失败机制、调用者与最小合同

修复前：Gateway 和 Vision 都先写 MODEL_STARTED，再调用 LlmClient；即便 F 在 dispatch 前以专用类型明确拒绝，旧 generic RuntimeException catch 仍会将其保留为 UNKNOWN。这是源码可达失败机制；未在 VM 执行 Java 红例。先写专属回归源，再加生产 catch；未声称红/绿实跑。

Gateway 实际调用者：AiCreator 的 theme_draft/node_generate/template_fill/chapter_overview/safety_precheck/merchant_task_suggest/club_design/merchant_insight；AiTopicPlan 的 merchant_plan；NpcChat 的 npc_chat、npc_shop_chat、merchant_npc_chat。ObjectNamer 直接调 vision client，没有本 Gateway 配额，归 F 出口同源 admission，本 E 不改。原 Vision review/reviewPrepared 均汇入 reviewOnce；root/C 当前编排通过 PhotoIntent executeOnce 保存首个结果。

| 分支 | 本次行为与不变式 |
| --- | --- |
| 仅 LlmClient 调用直接抛 F 专用 AiProviderNotSentException | Gateway 非玩家草稿经 refundQuota 原 token 补偿，转为 AiServiceException.UPSTREAM_UNAVAILABLE；不得包装成 providerUnknown。玩家草稿无普通 Gateway reservation，由原 Creator 独立池处理，Gateway 不双退。 |
| Vision chatVision 直接抛专用类型 | 原 photo token 补偿；返回 degraded 首结果、providerState=NOT_SENT、reasonCode=PROVIDER_NOT_SENT。精确 catch 只包 chatVision；retainQuota 不在其内。 |
| 普通 RuntimeException/IOException wrapper/HTTP status/timeout/cancel/消息写着 NOT_SENT/原因链中藏专用类型 | 不拆原因链、不看文字/status/retryable/业务 stage；仍走原 UNKNOWN，不补偿、不新 nonce、不重发。 |
| 已收到结果后 retain/解析/审核 | 收费政策及原 outcome 不改。即使 retain collaborator 抛出同一专用类，它也不是模型调用未发送证据。Gateway 保持 ResultReceived 异常；Vision 保留原留账失败 UNKNOWN 行为，不补偿。 |
| Photo 首个 NOT_SENT 结果 | 复用现有 mapper：state=RESULT_READY、provider_state=NOT_SENT、完整原结果字段。不是新 state=NOT_SENT 状态机；read/executeOnce 重放原结果，不重新执行。 |
| NpcChat 收到 Gateway UPSTREAM_UNAVAILABLE | 沿原 FAILED/NOT_RUN/AI_UNAVAILABLE；原 requestId 终态回读/重放不再 reserve/chat。普通 providerUnknown 仍 PROCESSING/UNKNOWN/REQUEST_OUTCOME_UNKNOWN、不可自动重试。 |

不在业务层重判 F 的 worker CAS/许可/dispatch 状态；F 独占物理发送证明、4 global/2 origin/0 队列、真实 worker 退出才释放许可。E 的 typed LlmClient 双桩仅验证可信结果到经济边界的接线，不能证明 F 实现正确。

配额复用现有 refundQuota→cancelQuota→AiQuotaRecovery.requireRefund：原 receipt 绑定 member/biz/pool/day/nonce/generation，REFUND_DUE 在独立事务先落库，Redis 补偿 Lua/nonce 墓碑与 DB fence 控制最多一次经济回补。不得从当前日期或当前 generation 重造原身份。

原 lease 已过或 outcome CAS 失效时，不能绕过 fence 或直接走 Redis 退款；补偿决定未落库就保留原占位待核。只有进程内 NOT_SENT 异常而持久决定尚未写成时崩溃，现有恢复仍保守 UNCERTAIN。这不是自动新发授权，也不把补偿 helper 返回当已退款证明。

## 测试矩阵（全部 Java NOT_RUN）

普通 unit 11 例：

- Gateway4：MODEL_STARTED 后可信 NOT_SENT 只补偿原收据并映射 unavailable；普通异常/真实 HTTP exception status/cancel/包装 marker 的 UNKNOWN 负控；玩家独立草稿池不由 Gateway 新占或双退；收到答案后留账异常不能当未发送。
- Vision5：原 planned day/token、snapshot 和 stage 后专用未发送补偿；普通/包装异常 UNKNOWN；收到答案后留账抛 marker 不补偿；有效收到结果仍 retain；直接 review 仍沿同一 catch，不额外 reserve。
- Npc2：真实 NpcChat+真实 Gateway 接线（mapper/Redis/recovery 为 unit 双桩），分别证明可信未发送落 NOT_RUN、普通 provider 故障落 UNKNOWN；同原 requestId 重放/只读时模型只调用一次、只一次 reserve，UNKNOWN 无退款。不能当 NPC 真库验收。

专属真实存储 IT4：

1. 真实 Gateway reserve/stage，LlmClient 抛专用类型前受控注入 Redis 调用故障；独立 SQL 读到 REFUND_DUE、raw Redis counter 仍 1；新建真实 recovery 对象（模拟恢复 worker 重建）处理原 receipt，counter/receipt=0，重复 runDue/refund 仍 0、只有原台账一行、无再次模型调用。
2. 普通模型 timeout：真实原台账 RESERVED/MODEL_STARTED；仅将本行到期时间推进到可扫描，恢复为 UNCERTAIN，counter 仍 1，不变号。
3. 模型返回可信 NOT_SENT 前用独占测试 SQL 令本收据 request lease 过期；原 REFUND_DUE CAS 必须拒绝，counter 仍 1；恢复到 UNCERTAIN。此故障夹具用于证明不放宽 fence，绝不改生产 guard 配合测试。
4. 真实 PhotoIntent Spring事务代理 + Vision + Gateway + recovery + MySQL mapper/Redis Lua：预置上一北京时间日、完整原上下文/原 nonce 的 PREPARED 意图；发送前 snapshot 真正落库后收到专用拒绝，保存 RESULT_READY/NOT_SENT 并原日补偿。今日另一 counter 哨兵值 9 保持，原意图 id/key/hash/nonce/token 和结果值回读一致，全行快照重放不改；模型一次。前一日 PREPARED 为持久恢复起点夹具，不冒充当前 prepare/CAS 全玩法链已验。

真实存储只双桩 consent/media 展示校验、图片编码、模型与 generation 审计/资料外围；核心 Gateway/Vision/Intent/recovery、Spring事务代理、两个 mapper XML、配额 SQL 与 Redis Lua 真实。Redis 故障是在受控 serializer 调用边界注入异常，并非关闭真实 Redis 实例；健康阶段由真实 Redis/raw client 独立读回。不联网模型/媒体，不声称完整媒体授权、HTTP admission 或 provider 计费已验。

可直接复用但未在本任务运行的既有安全网：AiQuotaRefundRulesD08Test、AiQuotaReservationRedisIT（原日/跨人/跨池/重复补偿）、AiQuotaRecoveryMySqlRedisIT（原状态/fence/代次/过期证据/并发 worker）、PhotoCheckVisionServiceTest、PhotoCheckIntentMySqlIT/Recovery/Fresh/LockRead、NpcChatServiceTest/readback，以及 root 独占 Creator 测试。没有删旧用例、改旧期望、放宽连接或增加 skip。

## 真库连接、清理与 CI 交接

- 新 IT 显式 `CHENGYIN_AI_MYSQL_IT_PORT/USER/PASSWORD`、`CHENGYIN_AI_REDIS_IT_PORT/DATABASE`，端口合法、用户名非空、MySQL 密码显式存在可空；Redis 密码沿原 fixture 允许显式可选。固定 loopback，MySQL >=8，不用 H2。无额外 enable gate/skip/default credentials。
- 独占随机库 `cy_ai_not_sent_<32hex>`，CREATE 无 IF EXISTS，成功后记 owned。只执行两份既有 canonical migration 的首个 table DDL（与现有 recovery fixture 相同），不执行任务注册或生产 DDL：migration_ai_quota_reservation_recovery_20261013.sql、migration_photo_check_intent_20261023.sql。无需新增 schema/dependency。
- 每例随机 member、原 nonce；触碰前检查已知 task Redis keys 不存在，清理只删本例登记键及本例 SQL 原收据所绑定的精确 counter/generation/receipt，不用 KEYS/FLUSH/全局 prune。跨午夜时从真实台账读取原 key，清理也不重算今天的 key。
- AfterEach 关闭本例 context、逐键删除并确认不存在；AfterAll 关 Redis factory、只 DROP 本类 CREATE 成功的随机库，再独立 information_schema 确认 0。root 须登记新 IT 4 条实际执行及 CLEAN，初始化失败应记业务 0/4，不能用旧 suite 回执代替。
- VM 仅编辑和轻量 Python/静态检查；没有 Java、MySQL、Redis、HTTP fixture、真实模型、CI dispatch 或生产动作。无新增依赖，不改 workflow/registry，由 root 联合 F 后统一 Java/真实存储执行。

## 静态验证、回退与未决项

实际已执行：`git diff --cached --check` 无输出 exit 0；仓库 `python ci/secret-file-gate.py` 输出 PASS；新增差异有限 token/私钥扫描 PASS；`STATIC SCOPE/FIXTURE PASS` 确认 8 个 E 文件、11 unit+4 IT、语法括号/import路径静态检查（不是 Java 编译）；`PRESERVATION PASS` 确认 Creator/NPC/PhotoIntent/mapper/旧 NPC test 原字节及 F class blob 精确一致且 F class 不在 index。候选身份/完整 tree 与 parent/ref 核对在交付消息给出。

11 个 unit 与 4 个 IT 仍未执行，Java 21 编译也未运行。当前无安装的 gitleaks；既有固定版本下载受 proxy:8080 阻断，不改网络/凭据，不安装依赖；有限扫描必须由 root 固定版本复扫。稀疏 checkout 的基线扫描不冒充完整 root tree 扫描。

回退仅 E 两处 catch 与专属新测试/文档；与 F typed 接口作联合候选核验，不复制/覆盖 F 修复。不得回退原配额 guard、删除原台账/Photo意图/NPC收据、清 Redis、重置 UNKNOWN 或换号。收到结果的收费口径、root Creator 修复、F 物理线程/许可证明保持各自 owner；无完整 T13/T19、容量/RPS/延迟或真实供应商计费结论。
