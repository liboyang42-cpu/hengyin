# inline 原支付消费事实：冻结调用链/存储合同

观察父 `e6dc689acd16f8354096fa6017cec85b36171c4e`。此文件在146f956先行冻结为可审调用链；后续本分支已写源码，实际未运行状态见README。后续同分支源码不改fd350兄弟片、366席位guard、e6终态hook。未运行Java/DB/CI或生产DDL。

生产唯一inline caller：`TeamGroupServiceImpl.settlePayingRegistrationsBeforeCancel` 在原取消事务/topic→activity锁内查原PAYING清单，微信SUCCESS走 `ICmsRegistrationService.applyConfirmedWechatPaymentWithinTransaction`（MANDATORY）→ `applyWechatPayCallback`（原topic/父从单当前锁、真实B Stored/Reference核验）→ `completePaymentSuccess`（CAS/历史半态校验、全部同单支付效果）。之后同一取消事务可能继续走原退款；任何失败整体回滚。普通入口是B callback router与OrderCloseTask调 `handleWechatPayCallback`，先绑定verified inbox再进入同一个付款私有入口。

现有存储边界：B `wx_pay_order_intent`主键mchid/out_trade_no，冻结原SDK请求；closed_confirmed_at只表达渠道已关闭，没有“报名业务消费原fact”的终态，不能复用成支付成功。B fact不可变，证明渠道成功；B sidecar关联具体inbox，当前claim独立事务不能加入本未提交业务事务。通用审计不是付款回执，不能凭可空JSON或相同单号的任意PAYMENT代替确定引用。

A最小候选：新增窄表 `cms_registration_payment_fact`，原registration_id为唯一业务身份，payment_fact_id唯一绑定B原事实；保存原registration_no/transaction_id/fact_hash，FK指向原报名和B fact，禁止UPDATE/DELETE、不设TTL、不回填历史。不是第二inbox/lease协议，没有WAITING状态、SDK或外部动作。只在原 `completePaymentSuccess` 返回SUCCESS后，同事务当前读回原报名2/2及原TX/精确金额，再INSERT...SELECT真实B PAY事实并严格读回；冲突/写失败抛错回滚所有本地效果。标准回调也写同一关联，避免两套消费身份；只存payer，原父/从归属和原副作用次序保持。

G读合同（仅review.patch）：原分项冻结只认明确传入Reference。若本表已有此原报名的关联，必须精确匹配同id/hash/no/TX，不能退回别的B inbox；没有新关联的旧已完成普通回调，才保留原PAYMENT/DONE sidecar严格验证。inline不伪造DONE。B后续退款查询可按此确切Reference调用已有queryRefundFacts(originalPaymentRef,originalRefundNo)，不能挑同单最新alias/fact或使用当前配置。

必要schema仍仅source候选：受控MySQL须核对B新fact表、cms_registration的signed BIGINT、现有constraint/trigger同名冲突、准确FK与不可变负控/回滚/重放。未经批准不执行；生产回退只停新writer/reader，保留全部关联/原fact/资金，不DROP/DELETE或复用ID。D已有通知fixture的consumer接线仅按root所有权合成，不覆盖其源。
