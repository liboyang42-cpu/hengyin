# 金额证据、待答范围与验收夹具

结论：冻结费率、原商家履约金额和原收口公式已可确定；**“挂账后退款逐原订单冲回”还是“净销售重算全期”不能由这些代码独自裁决**。root 已向用户提出此问题及多人固定费问题，本批不重复发问、不默认答案、不实施金额。以下只给精确证据和条件结果。

## 已确定的原始合同

| 证据 | 可确定 | 不能推成 |
| --- | --- | --- |
| CmsTopicServiceImpl.settleCoopTopicFromCashBasis / assertSettlementConservation | 原收口 P=round(S*p,2,HALF_UP)，G=round(S*(base+step*min(n,max)),2,HALF_UP)，M=S-P-G-Σmerchant；各分项非负且守恒 | 同一公式当然已批准用于“退款后全期重算” |
| PricingServiceImpl.platformRateOf / initiatorGuaranteeRateOf / guaranteeRate；Frozen reader 对4字段完整性检查 | 原 topic 冻结 p/base/step/max，n 按原真实有正应收商家计数，不能回 live | 退款是否改变 n/其他原订单的尾差由当前 live 数据决定 |
| CoopSettlementContract.originLines / allocateLargestRemainder | merchant 原额由真实冻结条款及 origin 注册集确定；百分比对该合同选集最大余数分配；FIXED目前只qty1 | 将该最大余数规则自动套给此前未冻结的 PLATFORM/发起人逐订单分配 |
| 原 cash SUM/current basis：paid=2/reg=2；RefundServiceImpl.markRegistrationRefunded | 原收口时已退报名不进入 S；取消/退款态确会改变当前筛选 | 部分退款后整单 reg=3/pay=4 就证明剩余未退钱该归谁，或原头可以直接覆盖 |
| RefundServiceImpl.refundRatio / createSettlementAdjustment | 旧 DIRECT：refund/paid，6位 HALF_UP，然后各行金额2位 HALF_UP；代码存在缺分母fallback | 该 fallback 是新 coop 的冻结经济政策；新路径仍必须完整原始证据和总额守恒 |
| RefundPolicy.verifiedVerdict / ensureRefundable | 已核销GUIDED硬拒；未核销票可在其原审核合同内退款；EXPLORE人工例外不适用于GUIDED | 为方便结算拒绝原合法退款，或假设merchant已核销固定费必然可按退款比例退 |
| SettlementDebtLedger / 原退款扣款 | 已批准“收款方后续结算抵债、差额30天补足、平台不垫付”；不足余额可记债 | 债务=用户已收退款，或新自动动用保证金/平台会员余额 |
| 2026-07-12 coop spec/plan | 分项行、T+7、原保底与守恒；平台对账不放款 | 其“总核销销售额”旧文字可代替当前全已付S，或其客诉垫付文字可覆盖后来的普通退款不垫付约束 |

## H 反例的条件金额（不是选择算法）

原 R1=10，已核销固定费2；R2=90，未核销；冻结 p=10%、n=1对应g=7%。原 S=100，P=10，G=7，merchant=2，M=81。

若 R2 全额退90、无积分混付、merchant履约和n不变：假设“原逐订单”曾真实冻结 R2 的 P=9/G=6.30/M=74.70，则逐原订单冲回留下 P=1/G=0.70/M=6.30/merchant=2；假设选择全期重算且上述事实不变，也得同数。**两条路线在此例相同不能证明已批准任何一条；原全局代码没有记录那份逐订单冻结分配。** root 未答前只能用作身份/事务夹具，不能拿算术相同绕过政策选择。

存在舍入/履约激活变化/部分父单时两路线不再等价。若选逐订单，须在新收口时将明确规则算出的真实原 PLATFORM/G/M 分配持久冻结；见 allocation-option-a.sql。不得事后从全期金额猜每单原分配。既有头没有记录该原分配时，新表不会补出历史事实；若历史原始输入确可还原，仍需root确认一次性重建/分钱规则及证据，不能把新算法算出的数标成早已冻结。若选全期重算，共享core保存的完整贡献与冻结公式足够作为输入，仍须把每次新目标与已执行原头/调整相减来追加delta，不覆盖原账。

root 已待答项：

1. **挂账后合法退款：逐原订单冻结分账、其他订单不变 / 净销售重算全期。** 精确消费者是 G 退款归一服务（拟）由 A reviewRefund 调用，以及事件消费与 payout；不要用前台退款按钮或当前费率填答案。
2. **多人票固定费：qty>1 的服务计量。** 当前qty1原合同不变；qty不明/多人继续原拒结，不能把本方案一行贡献当一人扫码。

部分退款但整张报名取消的未退余额，以及原逐单平台/发起人分钱尾差，需要随第1项所选方案核对：选择本身若已有批准合同能给出规则就复用；若仍没有，向root提交具体“R2 paid90/refund45/其余票不变”的缺字段/差异，不能自行默认全部剩余45都继续参与分成。现有代码只给退款上限，不提供全套分项归属。

实付后追收不足的资金缺口单列为恢复/资金来源依赖：不重问已定的平台不垫付。root/B 必须核对原渠道可退款余额、责任方补款/债务履行的既有入口与完成回执；没有资金时保留原合法退款负债和原号可恢复状态、明确责任人/期限。不能写“记债了所以退款已完成”、改退款为拒绝或永久冻结所有头作为闭环。需要新增经济授权（例如动用其他保证金）再由root提出具体问题，本方案未授权。

## 夹具设计（均未运行、未写成已验收用例）

- 原关联：R1已核销10/R2未核销90，3个全局头+merchant头同close；2贡献均可追到原payer；只有R1有原merchant claim/detail，R2没有伪造claim。初始S和全部原头读回；同父单主票付100、两从票0，不重复wechat聚合。
- 事件：同refund.id重复/提交回包丢失只有一个event；同键金额变化冲突；原事件TTL/租约变化不释放身份；0delta头在plan中，非零effect数量/指纹等于APPLIED回执。
- 资金：退款先/放款先/并发；同会员拥有保底和毛利两头；余额只够一部分，逐effect debit+debt守恒，两条债务不同source_id不互吞。外部退款UNKNOWN保原号，已确认paid不能仅凭status；零元paid无正向flow不能当已入账。
- 原子性：在event、effect、扣款、flow、debt、补充交付头、APPLIED各点注入失败；审核/持久意图与所有本地效果按选定事务路径原子，重试原身份恢复，无双扣或重复债务。
- 正补款：原头已paid后收到同号真实late SUCCESS，保持原paid/原amount；同effect仅一张ADJUSTMENT_PAYOUT交付头，原head.id与新head.id各自流水去重；经济聚合只计原头+delta，不能把交付头再计一次。缺完整外部SUCCESS事实不得fake ACK或复活已关闭权益。
- schema：真实base DDL+候选后真实helper插正额PLATFORM和DEPOSIT_DEDUCTION读回null/status/amount；其他现金头null/0拒绝；claim-only旧事件仍可读；新现金effect缺event/key/回执失败；全部CHECK对NULL分支实际拒绝。
- 政策分支：root回答后分别补所选金额算法的真MySQL服务链；多次部分退款累计舍入、舍入尾差其他订单变化、parent多票、merchant费和n激活边界均以获准合同断言。不得用合成Python算术代替这些验证。
