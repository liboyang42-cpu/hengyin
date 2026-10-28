基线/parent：6f321273f069e14c7cabb72a069ff73d6f9dec23。仅协议增量；不重复ASR主片、不改迁移/额度池/扣除时机。

PROCESSING + REQUEST_OUTCOME_UNKNOWN：ASR UNKNOWN、读库不明、已handoff但文本回执缺失。
PROCESSING + REQUEST_IN_PROGRESS：IN_FLIGHT、非空RECEIVED未handoff；后者asrResumable=true，仅提示显式POST原rid可继续既有转写，不授权重新ASR。
NOT_SENT、空文本、正文不可恢复仍可明确FAILED终态；永久身份不变。asrState/asrReason独立保留原阶段/原因；通用NPC恢复字段不再承载新的ASR错误码。
GET仅投影原事实，不清正文/改状态/启handoff/外呼。既有SELECT隐藏过期正文；原retention任务及显式POST写边界执行原正文清理，无新任务。

root必须串行应用本目录两份增量review.patch：
- controller-envelope.review.patch：相对已应用6f Controller的两处信封修正；UNKNOWN/读库不明返回code=200承载PROCESSING，避免客户端提前清pending。NOT_SENT明确终态保留原错误信封。
- client-readback.review.patch：仅一行，只对带asrReason的CHECKING切原readback模式，令原定时/手动重试走GET，不再自动POST。客户端正文未由D修改；须root读小程序skill后审接。
不得重放6f旧整份patch，也不得把asrResumable解释成新requestId/自动外呼许可。

实际执行：两patch git apply --check通过；静态核对原22 ASR case名、原16 formatter case名保留；原claim/provider/save/token/额度逻辑无改；真实组件Node协议9/9 PASS(0skip)，同用例对未改客户端负控4pass/5fail。Node命令：NPC_ASR_CLIENT_SOURCE=<审过补丁的真实index.js> node docs/delivery/architecture-day/D/npc-asr-projection/client-contract.test.cjs。
Java JSON序列化共用contract-cases.json的9行明确合同；该Java测试及新增read-only/Controller测试、原MySQL等均NOT_RUN。原22 ASR用例保留，新增4条Java case(总26)；原formatter失败场景仅将错误code500→待确认code200并增加原状态/原因断言，原无模型调用/错误文案断言保留。不是改断言假装验收：Controller信封、VO字段、服务投影与客户端回查模式均有实际必要增量。
