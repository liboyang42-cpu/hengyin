# H：B v8 支付原尝试栅栏独立复审

结论：**原 H-B01 的两个可触发源码路径已有对应修复；本轮未发现新增可证资金 P1/P2。运行裁决仍 UNKNOWN，不能据源码称 H-B01 已完成运行验收或四域可上线。** 四域冻结现在共用真实 business 行锁及 payable 检查，商家/报名/CLM 本地关闭再核对原观察号；DEP 结算靠既有外层 invite 行锁串行。CLM 关单后的二次动态查询、被替换的别名重放断言、真实 inbox/四域组合验证等仍需点名回归，不把 SDK doubles 当真实微信或 MyBatis 全链路。

## 身份、隔离与历史

- 唯一公共参考基线：2ec68899099a1e250df0949a8383bf85b5d3ffe0。
- 候选 **254744aa21139b44374a151b7922f7575086cdd8**，parent **218ecae05a78185e689ad3bc115bea2e51aa752c**，tree **560d59f93a57b7821de3304b7cd6c6657b7dfef5**；公开恢复分支 codex/architecture-day-b-v8-root-recovered-20261024。相对 parent 的30文件 delta 已按实际公共 commit 列出。
- root 通报30blob等于原失败 create_tree输入；H核对本次公开候选身份及实际读取文件，不冒称拥有/重新比对原失败请求。
- H目录 /workspace/hengyin-h-review，分支 codex/architecture-day-h-review；开工 HEAD 2534a32006f05e480ee0ff190cee1057018f9d47，clean。H仅新增本目录报告/静态证据，不修改B/root/支付实现或fixture，不运行Java/DB/CI/微信，不部署/合并。
- 旧H报告 b-v4/REVIEW.md、原218及其失败记录保留。协调者通报218四CI失败，本轮没有读其原XML或重新运行，不能将下方源级修复判为运行转绿。

## H-B01：两条原交错如何收口

**真实业务串行点**：WechatPayServiceImpl.lockPaymentBusiness:1261–1284：

| 域 | 原业务行及可支付条件 | 与原mapper/准备链的对应 |
|---|---|---|
| CLM | invoice.out_trade_no；status=PAYING；amount_fen | 同invoice主键行；原金额以分exact比较 |
| DEP | invite.deposit_out_trade_no；deposit_status=1/status=1/del_flag=0；deposit_amount | prepareDeposit真实要求已接受合作并提交状态1；与G锁的同invite行 |
| COMMERCE | oms_order.order_sn；status=0；pay_amount | 原到账/关闭CAS仅status0，本轮冻结不再只读id/amount |
| REGISTRATION | cms_registration.registration_no；payment_status=1/registration_status=1；wechat_payment_amount | beginPaymentAttempt先提交原PAYING；与报名目标行/inbox mutation一致 |

freezePaymentIntent:1080–1090 在实际外层事务存在时先拒绝，不挂起持锁调用方偷偷SDK；:1121–1173 的独立短事务内先锁business、检查payable和金额，再锁active/latest意图、核对原商户/业务身份/指纹并提交。SDK入口在freeze返回后调用。原请求参数、渠道、nonce及确定WXP别名规则不减。

原交错一（W1 CLOSED已确认、领域关闭尚未发生、W2创建）：queryOrderStatus:1006–1012 将 W1事实传给 recordConfirmedClosedAttempt:1229–1239，后者只UPDATE观察到的mchid/out_trade_no；领域关闭事务随后用 lockPaymentAttemptForUpdate:1242–1258 先持同business锁，再取latest FOR UPDATE。若latest=W2、observed=W1，返回false，不做关闭CAS。没有把旧W1观察重新解析为W2后直接关闭。

原交错二（准备层旧payable快照带出，业务先关闭，之后才freeze）：新freeze锁到实际business后发现不可支付，拒绝提交/SDK，不再靠旧快照创建W2。源码已修这两项根因；需要同候选真实并发读回证明，而不是以helper存在签通过。

lockPaymentAttemptForUpdate 要求已在实际事务内；不是独立临时核对后释放锁。商户/实际号/稳定业务号都必须匹配latest；历史无本表行只能接受业务原号=观察号，前提仍是调用方已获得真实渠道事实，不能拿此fallback创造关闭事实。

## 调用者与同一观察号

**商家**：MerchantCommerceService.reconcileOrder:161–203 为NOT_SUPPORTED；从observed带出wx_out_trade_no，SDK closePaymentAttempt固定它；closeObservedOrder:204–209 在同短事务内栅栏→markCommerceOrderClosed。旧观察返回false时没有本地状态/权益变化。该布尔值未向调用方声明“已经关闭”，后续只读原状态；不能把无异常当资金成功。

**报名定时任务**：OrderCloseTask:191、213、246–252 保留原实际号；SDK close固定原号，再在同事务内 attempt栅栏→settlePayingOne。NOT_FOUND无论过多久都保留原号/余额；REVOKED不再当可回补终态；PAYERROR必须先取得原号CLOSED证明。真实库存/积分/权益沿原settlePayingOne条件更新，未为通过测试清账。

**活动取消**：TeamGroupServiceImpl:813–916 的计划携带完整wxOrder；SUCCESS走applyConfirmedWechatPaymentWithinTransaction，不重进callback inbox；LOCAL_CLOSE和SDK关闭分支均保留计划中的wx_out_trade_no，closeLocally先栅栏再同事务settle。未知/NOT_FOUND拒绝取消。没有在已持目标行事务里调用领取独立inbox的入口。

**CLM**：closeInvoice:315–345 首次观察→SDK closePaymentAttempt固定原号；applyWechatInvoiceObservation:1074–1108 验业务原号/金额，closeObservedInvoice:1124–1145 同事务latest栅栏→version/status/PAYING CAS。旧W1 CLOSED而latest=W2时会拒绝，非只凭invoice版本猜原尝试。

但 closeInvoice:342–343 **SDK关W1后又调用reconcileWechatInvoice**，该方法:1064用业务号动态查询当前号。故不能声称这一条“从首次观察到最后回读全程固定同一个实际号”。目前二次结果是重新验证的事实且再次过latest栅栏：新W2 NOTPAY会拒绝收口，SUCCESS会应用其真实支付，CLOSED要按新W2事实过栅栏。这不是已证明旧观察覆盖新单的P1。最小验证建议：在W1 SDK close返回后/二次查询前插入W2，分别让其NOTPAY/SUCCESS/CLOSED，断言仅SDK关闭W1且三种后果准确。若合同要求严格只确认W1，应由owner改为原号回读/原观察返回；不能用business查询冒充固定原号回读。

**DEP**：CmsTopicServiceImpl.applyUnpaidDepositSetoffs:1544–1548 先FOR UPDATE锁invite，再query/close/本地updateDepositIfCurrent(:1594–1634)；freeze也必须同invite锁。recordConfirmedClosedAttempt现在加入已有事务，外侧完成状态5等变更后才释放锁，所以该已读调用路径不会在旧观察与本地关闭之间让新alias插入。API depositStatus无外层事务，仅SUCCESS调用既有金额/tx三参数markDepositPaid，未替UNKNOWN补记。

DEP并非已迁成同一closeObserved helper。其**既存**REVOKED/PAYERROR直接转待缴，以及结算/活动取消持事务做外部查询/关单仍存在，已在B报告列给root/G；本轮不把这些未改旧边界称为v8新增漏洞，也不据业务锁正确就宣称全部外部动作已移到事务外。

## 自锁与报名 inbox 锁序

- recordConfirmedClosedAttempt 使用默认REQUIRED，先business再attempt UPDATE；不再REQUIRES_NEW挂起已持business行的调用方。在本轮实际query调用者中未找到新增readOnly包装导致它被用于可写关闭的路径；真实Spring同DataSource/transactionManager装配仍未运行。
- 报名handleWechatPayCallback:2093–2159 明确拒绝ambient transaction；register先独立提交，claim独立领取；WxPaymentCallbackMutationService.apply:23–53 取owned inbox FOR UPDATE → lockBusinessTarget(registration) → applyCLOSED中的attempt锁 →业务变更/DONE同一事务。
- 新CLOSED栅栏在mutation已持registration锁之后进入，仍是 inbox→registration→attempt；freeze/closed事实路径是registration→attempt，未再取inbox，因此没有从新差异中构造出反向循环。租约过期不授权重发SDK；markDone与业务同commit，失败回滚后按owner/fence释放。
- Team活动取消的事务内SUCCESS补记不用inbox；定时任务SUCCESS从无ambient调用正常callback。不能把它们改回持registration锁再REQUIRES_NEW领取inbox，否则会重新出现自等/反序。
- 这只是实际调用链源码结论，**不是死锁压力或真实inbox MySQL运行证据**。RegistrationFundsMybatisIntegrationTest:155–161使用PaymentCallbackInboxStubs.passThrough(transactionManager)；新增attempt helper也多为mock true/false，因此该套单独绿也不证明真实inbox+真实gateway锁序。

## HTTP 204/500及通知事实

ApiRegistrationController.payCallback:1972–1992 将HTTP头变为case-insensitive Map；仅内部gateway ACK对象的精确code=SUCCESS返回204空体，FAIL/null/坏JSON/分发抛异常均固定500 JSON FAIL。未把未验证通知体的code作为ACK。业务方法经实际事务代理提交后才会返回，commit异常沿gateway catch变FAIL；但尚未跑真实事务commit-failure→HTTP的组合回归。

Gateway.handlePayCallback:754–859 仍先SDK验证通知，再matchesExpectedPayment:862–899查实际微信原单，mchid/appid/channel/CNY/total/transactionId/state/outTradeNo逐项一致，才分别路由四域。原单事实匹配的SUCCESS/CLOSED/REFUND可进入旧领域，不按错误日志成功ACK；NOTPAY/PAYERROR/REVOKED通知不放开。当前CLOSED/REFUND通知也要求非空transactionId，相关本地fixture构造了它；不能把SDK/parser double或本地签名测试当真实微信会发送这些事件/字段的证明，更不能擅放松缺字段。

同一已终态inbox重复成功不会再运行mutation；这是安全重复ACK，不是旧CLOSED再次覆盖新attempt。新HTTP5方法包含四路failure/throw500和重复success204，但其领域为doubles，尚不证明真实持久commit或平台通知链。

## 218四失败相关断言与转账

按用户通报保留218四失败历史，本轮只比源码，不新增运行结论：

- WechatPayCallbackForgedSignatureTest 中 `verifiedRegistrationRefundConflictAlertsOnceAndKeepsFail`、`verifiedRegistrationCloseConflictAndDisappearedOrderEachAlertOnceAndKeepFail`、`verifiedRegistrationAlreadyClosedAcknowledgesSuccessWithoutFailureAlarm` 三个方法正文与218逐字一致；分别保留退款冲突FAIL/关闭冲突与订单消失FAIL/已关闭重复SUCCESS。fixture仅注入新attempt接口，并新增旧attempt拒绝负例。上游不再一律以非SUCCESS挡掉它们。
- WechatPayReadinessTest.transferRejectsFractionalCentAndOverflowBeforeAdapter:137–151 保留1.999元、21474836.48元、ok=false、never SDK的断言；缺ambiguous字段的旧预期改为显式ambiguous=false，与明确发送前拒绝合同一致。未通过删金额/never断言造绿。
- transferToBalance:238–323 与218**逐字相同**。它在进入SDK try前调用toWechatPaymentCents(:641–647)：UNNECESSARY精确两位小数和intValueExact，Arithmetic/IllegalArgument返回ok=false/ambiguous=false；进入SDK后的异常仍ambiguous=true。故不要把v8对旧测试预期的更新说成又新实现了一次转账修复。
- WechatPayTransferClassificationTest 保留0.001发送前拒绝、原batch/detail/金额/openid/appid重试身份、进入SDK异常UNKNOWN、仅匹配终态明细FAIL的负控。没有真实转账。
- WechatPayCallbackFactsTest 的late-state负例改为“原查询仍SUCCESS、仅通知改CLOSED/REFUND等”，继续要求FAIL/无业务调用；这不再是原先“把通知及原查询同时改状态也一律拒绝”的fixture。其对应的同事实CLOSED/REFUND领域正负控在上面的三个原测试保留，不能只跑新的冲突通知负例就称恢复正确。

## 现有测试覆盖与必须补回项

方法库存已独立按@Test方法计数：WechatPaymentIntentMySqlIT **4**，WechatPayCallbackHttpAckTest **5**，没有把@TestInstance算用例；这些都未运行。

1. 新IT真实MySQL/JdbcTemplate/TransactionInterceptor+MerchantCommerceService设计覆盖原H-B01 barrier：W1 CLOSED提交→暂停旧领域关闭→W2 APP别名→旧关闭拒绝→新SUCCESS重复两次→业务paid/交易号/一次权益落库，保留原号/新号/两appid。SDK为double，domain mapper是JDBC-backed Mockito adapter，权益是专用fixture表，不是完整MyBatis/真实微信。
2. 四域非payable拒绝增加在第一个方法里；仅用窄业务表状态列，未证明四域真实领域入口、所有关闭/支付竞争。
3. **H-BV8-V01：被替换的原别名重放断言要补回。** 原第三IT `verifiedClosedOriginalAllowsNewAliasAndPreservesOldIdentity` 有 `assertEquals(alias,freeze(...APP))`；新第三方法创建alias一次后直接走回调，移除了这条重复原参数仍同alias的真实MySQL断言。所读WechatPaymentIntentLockTest保留首次号重放与新alias创建，但也未覆盖新alias二次重放。不是发现生产重复扣额；属于精确防线/验证缺口。最小补充：在新IT创建alias后、业务仍status0且SUCCESS前，以完全相同APP原参数再freeze，断言同alias、同hash、仍2行；保留原barrier及成功后读回，不删或放松任何现有断言。
4. **H-BV8-V02：CLM二次动态观察与四域真实组合待补。** 上文W1close/W2三状态；报名真实inbox+gateway+CLOSED/回调并发；Team不同注册逐批失败整笔回滚且旧号不误关；DEP在持invite行锁时CLOSED事实不发生自锁且新freeze被阻塞、业务闭合后不能SDK。这里只给最小验证建议，不提交虚假红例或擅改ownerfixture。
5. SDK独立连接看到已提交intent、ambient事务拒绝、关闭/预占结果保存故障、同原号UNKNOWN保留都要同候选复跑。schema cleanup需原receipt；源码里的finally不等于实际CLEAN。

## 裁决矩阵与交付边界

| 合同 | 源码裁决 | 本轮运行 |
|---|---|---|
| H-B01旧CLOSED不得关闭W2 | 商家/报名/CLM新latest栅栏，DEP所读路径靠既有invite锁；原两条交错根因有修复 | UNKNOWN |
| 过期旧准备结果不得对终态业务新发SDK | 四域business行锁内payable/金额检查 | UNKNOWN |
| 原观察SDK close+confirm | gateway固定observedWxNo；Merchant/Task/Team贯穿；CLM末段二次动态查询需单列 | UNKNOWN |
| 报名inbox业务+DONE原子/无反序 | 源码锁序未见新增反向环；真实组合缺口如上 | UNKNOWN |
| callback204/500 | 明确失败不2xx；重复原终态可204 | UNKNOWN，5 authored方法 |
| 精确金额、转账UNKNOWN | 原发送前拒绝及进入SDK后的不确定分类保留 | UNKNOWN |
| 迁移/恢复/四域完整MyBatis/供应商 | 本片没新增DDL改动；不能据不改迁移签部署 | 未执行 |

H实际只做公共SHA/parent/tree/30delta身份、41条读取ref/blob、相关XML解析、测试方法计数、三个原回调方法与原transfer方法保存比较、H交付检查。**没有Java编译/JUnit/MySQL/Redis/CI派发或执行、生产DDL、微信SDK网络、支付/退款/转账/部署。**不以测试源码数量或model状态机冒充JUnit。

本目录仅报告；没有新negative patch，因为未找到新增可证生产触发缺陷，V01/V02是明确待回归事项。原H-B01保留为“源码修复已审、同源运行待验”，不宣布历史失败已转绿。回退/迁移仍须保留wx_pay_order_intent、原WXP映射、receipt/资金账/审计；有别名后不能切回不认识映射的旧jar或删表。G既有策略、A95、其它资金共同fence/政策未决均不因本轮审查关闭。
