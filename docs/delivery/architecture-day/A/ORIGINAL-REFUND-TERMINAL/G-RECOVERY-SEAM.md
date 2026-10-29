# 给 G/root：已关账迟到成功的实际接缝

已确认代码链：B041 `WechatPaymentCallbackRouter`在SDK事实成立后传原ref；A `handleWechatPayCallback`先 `requireVerified`、`registerVerified`，随后原 `processVerified` → `WxPaymentCallbackMutationService.apply`（拥有inbox、topic、原订单锁）→ `applyWechatPayCallback`。本片在SUCCESS分支的 `completePaymentSuccess` 前拒绝“已关账+父/从单存在未完整2/2”，因此不会进入 `repairHistoricalPaidHalfState`、G入账、通知或DONE。

恢复身份是原 registration.id/no、原 `PAY:<transactionId>:SUCCESS`/notify_hash、原 wx_payment_callback_fact_link.fact_id/hash、不可变fact中的mchid/appid/原attempt/tx/分金额/intentHash；标准回调失败后inbox原行STATE_RETRYABLE=3，未删除、换号、标IGNORED_STALE或重造事实。再次投递同一ref仍命中原行，G最终恢复必须回到这套身份，不能让provider重付。

G关账当前读阻断的精确前置：在同一topic锁下，原父/从报名中payment_status=2且registration_status=1必须阻断cash close；A拒绝补半态与该读闸串行。A `reviewRefund`在同锁下先选择hasFrozenOriginalRefundComponents再审核CAS/旧追收；G新close/producer须当前读阻断已批准旧退款，不能A旧owner已追收后再生成新分项让SUCCESS追收第二次。A没有修改G证据/producer源，复用G已认领读闸，未另提同一查询的重复patch。

现有恢复能力边界：源码中 `OrderCloseTask`只扫过期payment=0或1，不扫此paid-half=2；wx callback outbox已有存储fence，但全生产Java未找到claimForRetry调用者，不能称已存在自动drainer。标准provider重投可再次调用原入口；原fact与原inbox可供受控恢复读取，但**最终已关账贡献补入/原分项/放款政策与可执行恢复入口仍缺，不能永久冻结当最终方案**。不得把topic.is_settlement改回0来“恢复”，不得绕guard补状态而不补原现金头。

inline：`TeamGroupServiceImpl.settlePayingRegistrationsBeforeCancel` → `applyConfirmedWechatPaymentWithinTransaction`也经过此guard，失败会令其原取消事务回滚；B已独立成立事实保留。但inline原fact ref与原业务的持久绑定仍是前批明确缺口，没有普通回调inbox时不能假造已有sidecar。G freezeOriginals 的原PAYMENT/DONE证据要求不能被此Map或原transactionId替代，需B/root裁决同事务绑定窄端口后接线。

给G的下一实现接点：在持原topic/订单锁、实际B原引用已核验的SUCCESS阶段，先用原cash-close/原contribution证明该付款是否已完整计入；对“旧close遗漏但真实成功”提供有原身份、幂等receipt、原头守恒的补入/恢复结果，A才能在同mutation提交2/2与DONE。该实际producer/API尚未冻结，本片没有发明占位方法、任意分币或默认成功。非本owner文件仅按后续真实接口给review.patch。
