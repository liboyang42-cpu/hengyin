# H-G01/H-G03 的精确接线与最小保护方案（未实施）

结论：现有接口不能仅靠“调用 appendExecutedAdjustment”闭环退款。此文给出可落地触点和阻断条件，不声称已经接通，不改变既有退款资格、净额分摊或固定费政策。

## 已有接口与实际断链

- `RefundServiceImpl.reviewRefund`：`loadRegistration → lockRefundRegistration` 当前锁报名，pending→approved CAS、markRegistrationRefunded，再只查 `SettlementDetailMapper.selectSettledDetails`。`createSettlementAdjustment` 只写旧 DIRECT 明细；`debitReceiverBalance` 已具备按实际余额扣回和 `SettlementDebtLedger` 记欠款能力。
- `CoopSettlementClaimServiceImpl.appendExecutedAdjustment`：只能由 original `CoopSettlementDetailMapper.selectByClaimIdForUpdate` 定位唯一 settlement；事件 key 幂等写 `coop_settlement_adjustment`。全 main 搜索没有生产调用者。
- `payoutOneDueCoopSettlement`：当前 head FOR UPDATE → 普通 SUM executed adjustment → member lock → balance/detail → paid → offset debt。没有 registration/refund fence；普通 SUM 在旧 RR 下也可能不含另一次已提交调整。
- merchant claim/detail 只覆盖其真实履约报名；PLATFORM、INITIATOR_GUARANTEE/MARGIN 无逐报名 original 分配。H 的未核销 R2=90 只进入全主题 S，不会生成商家原始 claim。不能借 R1 claim 去调整发起人头，也不能因旧 settlement_detail 为空当作无需补偿。
- `RefundPolicy.verifiedVerdict` 对已核销 GUIDED 硬拒；已核销 EXPLORE 的人工处理规则不能套入本批允许路径。H 的 R2 反例未核销，因此现有审核允许。

## 请求 root 协调 A 的具体修改区段

1. `RefundServiceImpl.reviewRefund` 在批准前、`loadRegistration` 取任何报名锁之前：先解析不可变经济父身份，再与结算/payout 使用同一 topic fence；锁内重读报名与退款历史。不能在现有 registration 锁之后临时加 topic 锁而形成反序。`lockRefundRegistration` 父单批量锁仍需保持确定顺序和真实 owner 范围。
2. `CmsRegistrationServiceImpl.completePaymentSuccess` 的 `claimPaymentSuccess` **之前**，包括历史半成品修复及同父单后续报名；`completeZeroAmountPayment` 汇入此路径。实际回调还有 `handleWechatPayNotify` 的 REFUND 状态分支，A 必须核查已持有报名锁的外层入口，不能只在私有方法内部追加相反锁序。
3. 与 G 的 `settleTopic`（CAS 已锁 topic）以及后续 `payoutOneDueCoopSettlement` 对齐：topic fence → 同父单/相关报名（固定顺序）→ refund/original/heads（固定顺序）→ member。活动使用真实活动父关系，不取相等数字。
4. 迟到 SUCCESS 已在外部成立时，保留原支付号、attempt/回执与 UNKNOWN 查证身份；不能丢掉事实、换号重付，亦不能在资金已终结后直接追加报名而不调整对应原头。若尚无可证补偿，必须留为明确可查证的阻断，而非返回虚假成功。

本批没有修改这些 A 文件。给出的共同锁序是需要双方实现并真实验证的接线合同，不是已验证的锁证明。

## 可以先落地的最小保护（需 A/G 同批，不等于完整补偿）

- 挂账/放款持同一 fence，当前锁读相关报名与 pending/approved refund；任何没有逐原头处理凭证的退款先阻断 payout 的 balance/detail/paid，不能把 complaint gate 当退款 gate。payout 应先从只读候选解析 topic，再锁 topic/报名，再锁 head 并重验 ID/归属/到期态，禁止 head→registration 与审核 registration→head 反序。
- 在审核即将批准、发现已挂账 coop 资金但缺原始归属/调整闭环时，由 A 在状态 CAS/报名退款态/外部退款意图之前返回可审计 MANUAL_REPAIR；不能先批准后发现无账可冲。此保护只暂存技术阻断，不能写成更改用户退款政策或完成退款功能。
- 已先实际打款的头，必须由实际 event_type=5/event_id=head.id 的流水证明实付，再由原负向事件调用既有 balance/debt 能力；不能仅凭 PLATFORM 的 status=1 推断会员收款。重复退款事件不得重复扣回；不足余额记录欠款而非凭空补足。

## 完整补偿前必须具备的最小原始证据

对每张原头持久保存其确切资金来源报名/支付身份、冻结合同/版本、受益方、original amount 和分配事实。商家继续使用已有七列 canonical claim + original detail；平台/发起人须先完成相应原始归属建模，不能把 HTTP 去重或任意 claim 充当经济身份。当前 schema 的 adjustment.claim_id NOT NULL 且由 detail 绑定 settlement，原事实缺失不能用默认 0、其他头 claim 或修改 original amount 修补。

退款事件由原退款 ID/原退款号确定，锁原头后一次性追加所有受影响负向事件。原头金额不覆盖；payout 在同一 head fence 下读取当前已执行调整。A 已有 DIRECT `refundRatio` 只适用于其现有合同，不能未经归一直接套用于固定履约费、平台和发起人。此处不提出新的比例或净额政策；技术实现需先从冻结合同和原分配事实证明结果，不能证明即阻断。

## 必须实际运行的最小验收集合

H 的 R1=10 已核销固定费2、R2=90 未核销原场景；合法冻结条款与真实现金头落库后，执行真实 reviewRefund 和 payout。两种先后顺序、并发、重复退款/放款、adjustment 与 payout 同时写、余额不足、每个事务写点失败/恢复，逐原 head/claim/detail/adjustment、退款意图、balance/detail、paid、debt 回读。迟到 TOPIC/ACTIVITY SUCCESS 和零元付款还需原支付身份及收口后状态核对。

这些测试本批尚未具备完整服务接线，亦未运行；不能以新 4 条 Mapper SQL 或 mock 负调整替代。
