# G 原分项实际 producer，parent 8818837b

本片不是全体结算/退款/late SUCCESS 验收。Java/MySQL/CI 均 NOT_RUN；未执行 DDL。并行开发，只改 G 资金符号，A/B/C/D 与 CI 未改。

实际链：`CmsTopicServiceImpl.settleCoopTopicFromCashBasis` 原 head/claim/detail 全部持久化 → `ICoopSettlementClaimService.freezeSingleOrderOriginals` → 真实 `CoopSettlementOriginalProducer` → 既有 `CoopSettlementOriginalLedger.freezeOriginals` → 原 close/contribution/component → A 终态 hook（已有 review.patch）→ 同一真实 `reverseCompletedRegistrationRefund` 消费这些原件。新增调用在原积分/保证金退款之前，同原 topic 资金事务；冻结失败须整单回滚。topic 首锁在 claim bridge、producer 和 ledger 重验，先 topic 后报名/head。

可执行部分：恰好一个独立/单席位/全微信实付报名；完整实际冻结费率；B PAYMENT/DONE inbox 与原 transaction/金额引用；每个商家/领队 head 恰好有一个属于该报名的真实 claim/detail。平台/发起人直接复制原已落库金额，商家复制原 detail 金额；零重新分币、零费率重算。金额/收款人/旧 claim/原金额均不修改。

不支持时返回具体 `NOT_ENABLED_*`，不创建假 close/claim。现有 head 的 cash_close_id 保持 NULL（原件未绑定），日志明确 topic/reason；不能把这些头当作已接新退款 owner。包括：多订单、父单/多席位、混合实付、缺冻结字段/原付款回执、保证金抵扣、负头、需要跨报名分摊的 detail。原有基数/退款/qty guard 未解除，也没有从异常路径选择三个待决算法。原入口空 owner 结果明确抛错，不默认成功。

测试源码：新 producer 6 普通 unit，调用真实 producer→真实 ledger，复用原 G mapper doubles，不 mock 新 owner 成功。旧 PreparedCoopPlanFixture 仅明确声明 `NOT_ENABLED_LEGACY_AGGREGATE_FIXTURE`，全部旧断言不改；其组合测试不证明原件冻结。原10条 MySQL case/断言保留，新增1条 `realClaimProducerBridgeFreezesOriginalsThenSameRefundHookConsumesThoseExactRows`（共11声明）：实际 claim bridge→producer→真 XML/存储→原退款 hook；仍不冒充完整 CmsTopic/A SDK 流程，root统一真验。

尚缺：多订单分配 producer / 部分退款累计舍入 / 负毛利责任三题、实际 C 席位计费合同接线、late SUCCESS 收口外归属。A 的真实终态接线须与原审核分流两个 review.patch 同批，防旧审核追收+新SUCCESS双扣；本片不修改 A 文件。原公共父提交中的 root/D/B/A 改动不整文件覆盖。
