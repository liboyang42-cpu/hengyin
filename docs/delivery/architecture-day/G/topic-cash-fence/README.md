# G topic现金锁序候选

Parent: cc91b0845e3abd4a2060d54a857c8c179bd22d4f。仅源码候选；Java/MySQL/Redis/CI全部NOT_RUN。

## 已核对与本片修改

- cc91已包含G45结构保护、正金额和新入口guard；`TopicSettlementCashBasis`已拒绝PAYING，未重做。961保证金私有方法不在本片diff。
- `CmsTopicServiceImpl.executeTopicSettlement → settleTopic`：每topic REQUIRES_NEW，先当前锁读topic，再用该行选择原类型/俱乐部规则与CAS，继续原当前现金基数、合同、金额、回执与积分顺序。
- `payoutDueCoopSettlements → payoutOneDueCoopSettlement`：扫描只携带定位topicId；topic→待付head当前锁读，复核head归属后才执行原调整合计、余额、流水、paid CAS、债务抵扣。
- `CoopSettlementClaimServiceImpl.prepare/persist`：MANDATORY加入原结算事务；实际生产调用均在CmsTopic结算事务内，两阶段不再有各自提交/释放证据锁的合法入口。
- `appendExecutedAdjustment`：无锁定位原head→topic→head当前锁读→原detail→原event。定位只提供锁地址，锁后复核topic/head/claim归属。新增两个现有Mapper窄查询，无schema变化。
- `TopicEditionFinalizationServiceImpl`：所有现有终结、重计提、红冲、建批及批次命令先topic再原head/事实/行；纯查询editionLedgerView不写入或加命令锁。`TopicSaleFinalizationServiceImpl.finalizeForSale`本来已先锁topic，未改。
- 原正金额/原guard测试只补原topic和必需bean装配；原金额、非零、单账本、重复零效果等断言保留。未使用ae7旧保证金测试覆盖cc91。

## 最小交错与新增场景（未执行）

1. T1持topic，T2执行真实payout或claim调整。T2必须在topic等待；T1仍能NOWAIT锁head。旧head/detail优先路径不能满足该断言。
2. T1持topic写原调整-2并等待，T2发起30元head打款；T1提交后T2实际入账28，原head/detail仍30，重复打款零效果。不引入退款分配算法。
3. 同原调整event重放一次、异载荷冲突，外层失败全部回滚并能以原event重试。
4. 扫描经典但当前为探索主题不得CAS；规则必须来自当前锁定club；扫描head跨topic不得写余额，缺topic连零额head也不得消费；prepare/persist无外层事务由真实Spring代理拒绝。
5. FINAL原件重放仍先topic；缺topic不得生成FINAL或批次。

`Wp04TopicCashFenceMySqlIT`：5个普通@Test；真实MySQL原DDL/head-detail-adjustment XML、真实G owner与事务。topic/member使用实际JDBC锁/余额SQL适配，通知/债务为替身；不冒充生产CmsTopicMapper全投影、真实债务链或A/B回调验收。独立UUID schema、显式连接环境、仅清理自身实际创建的库。
`CoopSettlementTopicFenceTest`：6个普通@Test；既有`TopicEditionFinalizationServiceTest`追加2个。无参数测试计数替换。

## 未闭接缝与root共享触点

- `WxPaymentCallbackMutationService.apply`目前先锁inbox及registration target；A须在任何业务锁之前解析真实topic并取得同一topic锁。`CmsRegistrationServiceImpl.handleWechatPayCallback/applyConfirmedWechatPaymentWithinTransaction/completePaymentSuccess`及begin/create、TeamGroup/Club包装首锁要成组对齐。只在complete内部补topic会造成registration→topic反序。不能拒绝或丢失合法外部SUCCESS；完整原身份/渠道/金额/交易号/原attempt的持久已核验事实仍需B/A真实交付，现inbox hash/biz/event不是完整事实。
- `RefundServiceImpl.reviewRefund → loadRegistration → lockRefundRegistration`目前先registration；A须先topic并锁内重读。既有审核路会先做分账冲销；新批准合同要求以真实退款SUCCESS事实为执行依据，不能把批准当到账。
- `RefundServiceImpl.handleRefundNotify → updatePayoutTerminal`：SUCCESS CAS与原分项冲回、原事件回执、余额追收/债务须同事务，任一失败一起回滚；重复终态须校验同一原退款事实/金额，不得只因status相等假成功。root负责A/B接线，本片未改这些文件。
- 即使G侧锁序通过，A不遵守topic锁时仍可在结算后提交新现金/退款事实；本片不宣称最外层共同fence、late SUCCESS或退款经济闭环已完成。

## 新批准退款政策所需的最小持久归属

已批准：该订单原冻结分项逐项冲回，其他订单不重算；原退款资格/实际退款金额不变。多人固定费qty!=1继续拒绝未证计量，不做quantity×fee。

cc91的cash_close/contribution/event仍是未接生产写者的结构候选；contribution只有原现金基数，merchant detail有原claim金额，而PLATFORM/保底/毛利head只有聚合额。不能从聚合额或当前比例反推“该订单原金额”。adjustment.claim_id仍NOT NULL，禁止造扫码claim或平台会员。

建议root审批并接公共schema的最窄归属：每个真实`(cash_close, registration, settlement_head)`一份不可变原分项金额/原受款kind与身份/计算及舍入版本/指纹；唯一(close,registration,head)，并以同close复合FK约束head及contribution。成功冲回唯一(原分项,原退款成功fact)，允许明确ACCOUNTING平台分项但不削弱旧member/claim约束。若现有存储另有同等事实，优先复用；G未创建第四表、未放宽claim列、未执行DDL或编造支付/退款VERIFIED。

原订单部分退款已有HF-04按原实付比例、六位HALF_UP再逐项两位舍入的代码，但它没有全头原分项及累计尾差守恒证据；不能将“原订单逐项冲回”批准解释为全期重算、重分配其他订单或凭空选择分币尾差。原分项冻结/累计退款尾差与原SUCCESS事实存储由root确认接缝后继续编码。已付头的追收复用`SettlementDebtLedger`，其实际钱包/债务写侧由A/root协调，不能只加负调整却不追回已付钱。
