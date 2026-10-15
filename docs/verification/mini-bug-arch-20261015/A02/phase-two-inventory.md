# A02 后半只读准备清单

状态：待 bug 批次屏障；以下仅源码调查和后续申请清单，无后半代码改动。

## 当前链与已知缺口

- AiGatewayServiceImpl：已有 reserve/refund Lua，以原日 counter、generation、nonce 收据为补偿身份；调用 catch RuntimeException 全退款，包含结果未知超时；模型响应只有 String，没有真实 usage。INPUT_REVIEW_STARTED、MODEL_STARTED 及 RETAINED 原因可关联原额度回执。
- AiQuotaRecoveryService：独立事务写 PREPARED/RESERVED/REFUND_DUE；runDue 对 MODEL_STARTED 或审核中恢复归 UNCERTAIN / PROVIDER_OUTCOME_UNKNOWN，不盲退；该策略与网关即时 catch 全退款不一致，须先收口。
- AiQuotaReservationMapper.java / mapper/business/AiQuotaReservationMapper.xml / AiQuotaReservationRecord.java / AiQuotaRecoveryTask.java：现存持久化与恢复合同，后续只读优先；不新增 DDL/调度系统。
- OpenAiCompatibleLlmClient / LlmClient / AiGatewayResult / AiGenerationLog：client 只返回 message.content，网关 tokenIn/tokenOut/tokenEst 都由字符串长度估算。价格/留存不能靠新增默认数值推断。共享接口/DTO、client 若需编辑须先申领。
- NpcChatService：客户端请求身份、请求 lease、晚到 finish、chat log 写入；NpcChatRequestMapper、NpcChatLogMapper 及 XML 读取删除和防迟到重建能力待核。
- NpcChatDataService.deleteForUser 当前直接 deleteNpcChatLogsByUserId；ApiAiNpcController POST /chat/data/delete 使用 getAppUserId 删除本人全 NPC 数据。删除服务、mapper/controller 不在 A02 原 owned_files，任何修改须先申领；不恢复新 UI、不改留存政策。
- ApiAiNpcController / DashScopeVoiceClient / SpeechToTextService / 语音配额链：文本 npc/gen/photo 已有池；语音实际用量/额度分池待完整追踪，不擅自采用新价格。

## 既有相关回归（本轮均未执行）

- AiQuotaRecoveryServiceTest：staleModelOutcomeIsUncertainAndNeverRefunded；expiredReceiptWithActiveCounterNeverClaimsRefundOrNoActive；originalDayGoneResolvesWithoutCurrentDayAllowance；lostClaimCannotRunRedisOrFinalize；outageRemainsDueAndDoesNotStarveNextRow；completedNonceReplyFinalizesUnderCurrentFence。
- AiQuotaReservationRedisIT、AiQuotaRecoveryMySqlRedisIT：真实 Redis/MySQL 收据、补偿幂等及恢复；须专用环境串行。
- NpcChatServiceTest、NpcChatRespContractTest：请求复用、晚到响应、状态合同；ApiAiNpcChatDataTest：本人导出/删除与开关边界。
- AiGatewayServiceImplTest、PhotoCheckQuotaPoolTest：额度池与网关失败边界，不能替代数据库独立读回。

关闭后半前需要独立业务事实：同一收据补偿只一次、原日/原代次不串池、provider 结果未知不退款重发、估算不显示成真实 usage、全 NPC 删除与晚到旧请求语义。若需要授权/留存/资金政策或新字段，先给具体方案，维持未完成层。
