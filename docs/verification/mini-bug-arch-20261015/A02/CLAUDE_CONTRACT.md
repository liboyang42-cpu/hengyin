# Claude 合同：A02 前半

版本：共同基线 d551c6babd018d41aec90dad2c0acaf1c534644d 加 A02 工作区改动；源码合同，尚未经 Java/服务运行验收。现有 HTTP 方法、DTO、权限、分页、缓存、requestId/version 均没有改动。

- 现有 AI 创作接口为 POST `/api/ai/theme/merchant-plan`、`/theme/draft`、`/theme/draft/quota`、`/node/generate`、`/template/fill`、`/chapter/overview`、`/safety/precheck`、`/merchant/task-suggest`、`/merchant/insight`、`/club/design`（后九项同 `/api/ai` 前缀）。依旧由 ApiAiController 现有方法包装 AjaxResult，网关异常 reason 并非自动新增 HTTP 字段。
- memberId 来自登录态；主题草稿允许可解析角色的 player，其他创作沿现有 club/merchant 门禁，merchant insight 维持商家归属。没有扩权限。主题草稿请求仍为 `{idea,productType,existingNodeNames}`；示例测试 fixture：`{"idea":"夜间步行","productType":1,"existingNodeNames":[]}`。字段约束由原业务层校验，未增加新范围。
- 内部 generate(memberId,role,bizType,systemPrompt,userPrompt) 成功仍为 AiGatewayResult `{traceId,text,model,safetyFlag:0}`；未知/未配置场景或 mock provider 现在明确抛 UPSTREAM_UNAVAILABLE，用户不能拿到假文案成功。测试 fixture memberId=1001、provider 名 deepseek 仅由 Mockito LlmClient 替身提供，不是真实模型调用。
- 输入全段审核拒绝时模型调用次数必须为 0；微信明确违规文案沿 INPUT_BLOCKED，人工复核沿 UNKNOWN。输入审核不可用沿 UPSTREAM_UNAVAILABLE，并补偿本次预占；玩家 theme_draft 原有独立计费不变。
- 输出审核不可用/异常为 UPSTREAM_UNAVAILABLE，结果不返回；provider 已经产生输出，保持已用额度。明确输出拒绝继续沿原 UNKNOWN 行为，未擅自改成新 HTTP 原因码。客户端不能把审核不可用显示成“用户违规”。
- 本次 generate 不提供客户端 requestId、schema version 或结果查询新增合同；traceId 是本次服务调用关联号，不能当客户端幂等键。结果未知、真实 usage、费用与语音分池合同待后半，不应把 tokenEst 或 tokenIn/tokenOut 估算当真实计费，未知费用也不能展示为真实 0 成本。
- NPC `/api/ai/npc/chat` 等既有 requestId 合同继续由 NpcChatService 维护；其 applyGatewayFailure 将上游不可用归为 FAILED / AI_UNAVAILABLE。没有改该服务或会话/UI。
- POST `/api/ai/npc/chat/data/delete` 已存在，登录态用户删除本人全部 NPC 日志，返回 AjaxResult.data `{deleted:n}`；本单未改删除实现。晚到请求再写入仍待后半取证，不承诺删除后永久不会再出现旧请求日志。

验收 fixture：正常真实命名的测试替身返回 `{\"result\":\"ok\"}`；未知场景/缺 key/disabled/mock 明确失败；安全前缀超过 2000 后接 UNSAFE_SUFFIX，审核替身命中后 provider 永不调用；输出超过 2000 后接 UNSAFE_OUTPUT，结果不返回；输出审核服务繁忙不是成功。上述 Java 用例已写未运行，业务/设备验收待协调者。
