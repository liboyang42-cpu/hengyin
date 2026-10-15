# A02 后半：未知结果与原额度身份

2026-10-15；前半 f72ddb1 不重做；本后半工作区修改待集成人提交。仅源码交付，不能标业务 READY。Java 编译/单测、MySQL/Redis 独立读回、微信审核、设备与真实业务均未运行；Cloud 未启动任何 Java/DB 或真实付费 provider。

## 确认缺陷与改动

原网关在 `MODEL_STARTED` 后对所有 `client.chat` 异常退款，既有恢复器却将该阶段转 `UNCERTAIN / PROVIDER_OUTCOME_UNKNOWN`。超时、解析异常、空回复都不能证明 provider 未执行。当前删除此即时退款，保留原 reservation 的 day/pool/generation/nonce；不创建新额度身份，不自动重发。不把未知收据终结为 RETAINED；仍由现有租约到期恢复器处理。

明确未发送的配置失败继续补偿：已授权追加 OpenAiCompatibleLlmClient.requireReady 的公开只读本地检查，在网关选 client 后、MODEL_STARTED 前执行；原 chat/chatVision 内检查仍保留。网关不再按异常消息包含 apiKey/enabled 字样决定退款。自定义 LlmClient 无结构化发送证据，其 chat 异常保守未知；没有新增 provider HTTP 行为。

新增 AiProviderOutcomeUnknownException，继承既有 AiServiceException 并沿 Reason.UNKNOWN。独立类型避免把原人工复核/输出审核的 UNKNOWN 误当已发送 provider 结果。NPC applyGatewayFailure 仅该类型进入既有 PROCESSING/UNKNOWN/REQUEST_OUTCOME_UNKNOWN/retryable=false；保留原客户端 requestId。同 ID 重放从现有回执读取，不再调模型。原 finishIfLeaseOwned 写入清理 lease/activeSlot，未知回执仍保留；不声称 provider 最终结果可查询，现有接口并无上游对账能力。

## 精确文件所有权

- 原 owned：chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/impl/AiGatewayServiceImpl.java
- 追加授权：同 service/ai/OpenAiCompatibleLlmClient.java；同 service/ai/AiProviderOutcomeUnknownException.java（新增）
- 追加授权：chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/NpcChatService.java，仅 applyGatewayFailure 窄分支
- 回归：chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/impl/AiGatewayServiceImplTest.java
- 回归：chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/NpcChatServiceTest.java
- 本文及 phase-two-source-check.txt。没有改 Mapper、DTO、Controller、DDL、依赖、价格、配置、授权或留存。

## 用量合同与待 UI 接线

LlmClient.chat 仍只返回 String；OpenAiCompatibleLlmClient 只读 choices[0].message.content，没有 usage。所有成功 tokenIn/tokenOut/tokenEst 均为长度估算，不能当真实计费。无价格/费用字段，不能导出真实成本 0。

已发送异常日志：tokenIn 仍是输入长度估算；tokenOut=null、tokenEst=null，表示输出/合计未知。AiGenerationLog 使用 Integer，Mapper 原样写值；sql/base_schema_20260818.sql 的三个 token 列均可空（DEFAULT 0 并非 NOT NULL），没有 DDL。实际专用数据库列定义及 JDBC 插入仍待集中验证。

发现后台 chengyinhub-ui/src/views/business/ailog/index.vue:83-84 使用 tokenEst || 0、tokenOut || 0，会把未知渲染成 0，必须由协调者安排 Claude 显示未知并标估算；A02不改UI，不声称展示问题已解决。

## 文本/语音池真实现状

rateGroupOf 当前仅 npc_chat→npc、photo_check→photo，其余→gen；npc_shop_chat 与 merchant_npc_chat 都落 gen。voice-chat ASR 成功后同样调用 chatWithShop，所以后续文字模型也落 gen；独立接口 RateLimiter 是请求频控，不能冒充每日语音配额。DashScopeSpeechToTextService.transcribe 与 NPC attachVoice/VoiceCloneClient 合成未见独立 consumeQuota/usage 收据。

因此现有文字/图片池可复用，语音 ASR/TTS 日池并不存在，不能宣称已经分池。新增语音额度上限、扣次/退款时点、是否与文字重复扣次涉及产品/付费规则，需要具体批准；本轮未擅自设池、设默认价或填 0 使用量。

## 本人全 NPC 删除：已证实但未擅改的缺口

现有 POST /api/ai/npc/chat/data/delete 由登录态 getAppUserId 调用 NpcChatDataService.deleteForUser；Mapper 删除 npc_chat_log where user_id，覆盖本人所有 session，不能跨用户。返回 data.deleted 仅为这一张日志表删除行数。

缺口一：npc_chat_request.safe_text 仍保留旧模型答案，同 requestId 可重放；删除未清理/脱敏回执。缺口二：NpcChatService 在 finish 后无删除屏障调用 saveLog，正在执行的旧请求可在 delete 返回后重新插入 npc_chat_log；单给 delete 方法加事务无法阻止此交错。缺口三：AiGenerationLog 还有 NPC 相关 input_brief/output_brief，是否属于此用户删除范围须明确，不能把网关审计留存擅自一起删。

最小可审阅方案（未应用）：保留原请求身份与不可重发回执，在本人删除事务中撤销进行中回执写入资格、清除该用户旧回执 safe_text，并让晚到日志写入与删除按同一个持久化用户屏障串行；不能直接删除 request 行，否则同 ID 会重新执行收费调用。若用现存稳定用户行锁，需要所有 NPC 请求准入/写日志均参与，须申领对应服务/Mapper并补 MySQL 并发交错测试；若现有表无法提供从准入到删除的严格边界则需另批字段/DDL。本批没有新留存策略、迁移或替代基础设施。协调者需明确删除是否涵盖回执/网关审计和晚到调用的返回文案。此项保持未修复、不能写“全 NPC 数据已永久清除”。

## 可复用 fixture

- 配置失败（测试 memberId=1001，provider deepseek，空 key）：UPSTREAM_UNAVAILABLE，原预占补偿一次；没有 HTTP。
- 已发送超时（Mockito LlmClient，仅一次 chat）：AiProviderOutcomeUnknownException / Reason.UNKNOWN，日志 tokenOut/tokenEst=null，原收据停 MODEL_STARTED，恢复到 UNCERTAIN，不退款/重发。
- NPC 原请求未知（测试 userId=2002、sessionId=99、UUID requestId）：{requestId:原值,outcomeStatus:"PROCESSING",safetyDecision:"UNKNOWN",errorCode:"REQUEST_OUTCOME_UNKNOWN",retryable:false}；原 ID 读取同态，不再次生成。
- 普通 UNKNOWN 审核异常保持原失败合同；本修复不扩大 enum 或改变原审核政策。

## 验证分层

实际执行：原 check_source.py 6/6 通过（源码 guard，非 Java 行为）；本后半 diff --check 退出0；phase-two-source-check.txt 记录最小源码结构检查。

Java 用例已写未运行：配置错误两条 provider 选择路径均无网络、原预占补偿；模型错误不退；超时单次调用；空响应未知；错误消息伪装配置缺失仍未知；原 receipt MODEL_STARTED 且无 refund/retain；未知日志输出/合计 null；NPC 同 ID 未知回执复用；保留旧普通 UNKNOWN review 回归。恢复器现有 staleModelOutcomeIsUncertainAndNeverRefunded 及原日/代次/nonce 幂等用例需集成人在既有 CI/专用 VPS 集中执行。

没有 Java 通过声明、真实数据库读回、真实 provider/微信审核或设备验收。冻结交付时不能把源码 guard 当业务通过，也不能忽略语音池、删除和后台未知展示尚未收口。
