# 结算核对：保留保护，先统一经济身份

结论：经典正金额非合作主题被主动拒结；正常注入依赖的合作主题在快照 reader 处被拒结；FREE_EXPLORE 禁走经典整单结算。以下“代码可表达”不等于“当前线上可结算”。证据 E01–E12 见 `source-evidence.json`，完整调用搜索见 `caller-inventory.txt`。

## 当前调用链与已有能力

| 链路 | 实际行为和边界 |
| --- | --- |
| `SettlementTask → executeTopicSettlement → settleTopic` | 查询到期主题、验证规则；每主题 TransactionTemplate；CAS 领取 `is_settlement`；失败越过事务边界回滚，批次末抛出点名失败。不是旧的整批吞错事务（E01、E02）。 |
| 无销售主题 | 除 FREE_EXPLORE 先拒外，`totalSales<=0` 可收口并请求退保证金；NULL productType 仅无销售可收口；这是代码分支，未证明外部退款原子或成功。 |
| 正金额非合作主题 | 抛 `NON_COOP_TOPIC_CASH_SETTLEMENT_UNPROVEN`；不把 calculateCommissions 的 DTO 当作现金成功，不发作品结算积分。 |
| 合作主题 | `settleCoopTopic → prepare → snapshotReader.read`；当前唯一 reader 返回 null，抛 `MANUAL_REPAIR`。`coopSettlementClaimService==null` 的旧算法分支存在，但不能拿手工 new 的测试对象模拟正常 Spring 接线成功。 |
| `CoopInviteServiceImpl` 接受/建合作单 | `freezeOrderContractSnapshot` 已冻结角色、TOPIC/NODE scope、GUIDED_TICKET 选集、证据规则、报酬及 terms JSON（E03）；不是“所有冻结列均不存在”。 |
| `TopicSaleFinalizationServiceImpl` | 锁主题，PRICING→SELLING，冻结阵容与商业事实；FREE_EXPLORE 还审查 canonical/活动票投影，特定四店合同冻结对公收款人、执行俱乐部和补偿条款。开售冻结与经典 CoopSettlementSnapshot reader 是不同能力，不能互相代替（E07）。 |
| `SettlementEntryServiceImpl` | 支付回调、商家整单核销和领队核销共用；主题支付不整单入账；探索票支付不入账且底层禁止整单入账；主题有效合作单绕开 DIRECT 分账，防双付（E08）。 |
| `CoopSettlementClaimServiceImpl.persist` | NONE 占经济 claim 但不挂现金头；现金路 claim→原始明细→从明细刷新头；调整以 claim/event append-only。`appendExecutedAdjustment` 在 main 源码只有定义，未发现实际退款调用者（E06、E09），不能宣称退款已完整接入该新账本。 |
| `payoutDueCoopSettlements` | 每行 REQUIRES_NEW；锁待付行、受款 member、余额及流水、置 paid、债务抵扣；客诉阻塞暂缓。这里的“打款”是站内余额入账，不等于微信转账；通知在事务后 best-effort。 |

当前调用者包括 Quartz 任务、支付成功回调及同订单 sibling、ApiRegistrationController 商家核销、ApiClubLeadController 团核销、审核工作台和 AuditTaskService 的 finalize；还存在报名优惠券核销/章节 payable 的独立路径。没有因为“小程序尚无用户”而忽略这些后端调用者或删历史账。

## 合法订单类型与支持矩阵

| 订单/事实类型 | 已有合同/分流 | 当前是否可据此恢复 G 所核对的经典结算 |
| --- | --- | --- |
| TOPIC owner_type=1，GUIDED_TICKET，商家 REGISTRATION_SCAN | Contract 可表达 paid=2、registration=2、verified=1、purchaseKinds 命中、scope 覆盖、本人商家有效扫码且 time<cutoff | 否：reader 拒绝，TOPIC/NODE scope 关联不正确。 |
| TOPIC owner_type=1，GUIDED_TICKET，商家团证据 | 合同在 `organizerScopeCovered` 为真时要求团与订单匹配、团码及成员核验均早于截止；否则走扫码支路 | 否：scope/证据分支需统一；不得只靠活动 ID 数值相同。 |
| ORGANIZER_LEAD，GUIDED_TICKET | 需 organizer scope 与报名核验时间；冻角色 ORGANIZER 不能未经规范化直接当 beneficiaryRole | 否：冻结 beneficiary 与场次范围未接通。 |
| ACTIVITY owner_type=2，主题派生场次 GUIDED_TICKET | Evidence seam 会取该 topic 的 active activity IDs；classic cash totalSales 仍只读 owner_type=1 | 否：Contract 的 eligible 明确拒 owner_type!=1、ownerId!=contextId；不是仅 reader 问题。活动 DIRECT 分账虽有实现，必须证明与 COOP 同一经济身份互斥。 |
| SELF_PLAY_PASS | Contract DTO 允许选集，但邀约冻选集当前只 GUIDED_TICKET；经典正金额无 coop 拒结 | 未获选集和受款合同，不能默认支持。 |
| EXPLORE_PASS / FREE_EXPLORE | 商家供给型逐章 entitlement/redemption/payable；严格零商家纯内容型有独立玩法完成分支，不制造商家核销；经典 settleTopic 与 recordPaidRegistration 整单路均禁止 | 不支持经典整单；保留独立章节/纯内容合同，不扩成按总票款给单一 merchant。 |
| NONE / FIXED_PER_FULFILLMENT / SALES_PERCENT | 算法支持三类报酬；NONE 原意是无现金但 claim 占位，不是丢弃业务身份 | 只有接通且验真后才可执行；不能制造零元头把“无证据”永久标为已结。 |

## 冻结快照字段及缺口

| 字段组 | 必需事实 | 当前证据/不能猜测之处 |
| --- | --- | --- |
| 身份 | orderId、sourceContract=COOP_ORDER、commercialContextType/Id | claim 允许 TOPIC_EDITION/ACTIVITY；要决定派生场次的规范 context，不能造第三种名称绕唯一闸。 |
| 受益人 | beneficiaryRole/Id、serviceUnit；实际 payable 收款账户/主体快照 | order 有 roleSnapshot、toType/toId；经典 `resolveOrderPayee` live club→member，与 role 语义/冻结账户不能擅自等同；四店合同已有独立对公快照。 |
| 范围 | scopeType/Id，类型化的 topic/node/activity/edition 关系及版本 | 邀约冻 TOPIC/NODE；SQL 仅 TOPIC_EDITION 无条件 covered，否则把 scopeId 当 activityId。必须显式关联，数字 ID 相等不构成证据（E04、E05）。 |
| 选集/证据 | eligiblePurchaseKinds、fulfillmentEvidenceRule、registration 身份、订单绑定的扫码/团成员/团核验回执 | `validateSnapshot` 只检查证据规则非空，eligible 主要按 serviceUnit/采购类型分流，并未按该 rule 解释所有枚举。新规则不可只写字符串。 |
| 金额 | compensationMode、适用 fixedFee/shareRate、eligibleSalesSnapshot、币种/小数位约束、平台/保底冻结值 | payable_amount 充当选集销售基数；其与退款后净额、point/wechat split、实际人头数需核对，不能改用 live 价格。百分比 shareRate 算法除 100，平台 rate 是小数，不能混用。 |
| 时间 | 开售/接受合同版本、settlementSnapshotAt、履约 startInclusive/endExclusive、退款截止 | SnapshotFulfillmentWindowResolver 只返回 DTO endExclusive；support 同名 helper 可解票窗或上海时区主题日窗，但 seam 未连接。当前候选合同只检查证据 time<cutoff，未检查 startInclusive，需夹具覆盖窗前证据。 |

## 履约、退款与金额断言

1. 代码候选基本条件为 registrationStatus=2、paymentStatus=2、verificationStatus=1、采购类型及 scope 命中；商家有效 scan 使用 registration_type=1、status 非撤销。团证据必须绑定本 order/merchant/activity/member，不能用“全主题已核销”替代本单履约。
2. 当前主题选择器：主题结束至少 12 小时、未结算、非 FREE_EXPLORE；尚在退款期限的未核验 TOPIC 报名阻塞结算。现金 sales SQL 与 evidence SQL 范围刻意不同；后者包含活动壳，前者不能直接替换。选择器并非完整“无在途退款”证明；锁与退款并发、缺退款截止和部分退款必须受控验真。
3. 结算截止要求 `settlementSnapshotAt>=endExclusive`，证据恰好等于 endExclusive 不计入。起点、时区、按票还是按主题的聚合窗应明确；不能为了结算将截止改成 now 或制造零值。
4. 合作头原额 `H=sum(origin_detail)`；固定费 `H=n_distinct_registration×fixedFee`；比例费 `H=round_HALF_UP(sum(eligibleSales)×shareRate/100,2)`，明细按最大余数、registrationId 升序打破平局，明细和精确等头。n 是报名条数，不自动等于 distinct member 或 order_num 人数；多人票计量矛盾仅保留具体 Q1，不重选收款/context 模型。
5. 经典总盘 `sales=platform+initiatorGuarantee+merchant/leadTotal+initiatorMargin`，margin>=0；余额实际入账与借贷流水变化应一致。保证金抵扣不是票款收入，独立核对，不能混入票款守恒。
6. DIRECT 账本 `gross=platformShare+merchantShare`，creator_rate 对指定 H8 业务必须 0；探索整单和 coop 双付保护必须保留。金额合法性、分为最小单位、负数/超 100% rate/舍入边界均要拒绝或明确合同，不以守恒残差证明受款正确。
7. 原始明细与头不因退款覆盖；调整以 `(claim_id,entry_kind,event_key)` 追加。同事件重放金额/归属不一致应 LEDGER_CONFLICT。退款状态终态、调整已执行、付款前净额与付款后债务/冲正必须完整衔接；本轮未证明该 seam 已有消费者。

经济唯一身份为 `(commercial_context_type, commercial_context_id, fulfillment_type, fulfillment_id, beneficiary_role, beneficiary_id, service_unit)`。route/sourceContract 不在唯一键内，防不同路径重复索偿；相同经济 key 不同 route/source 必须冲突。HTTP requestId、响应正文 TTL、租约到期都不能释放 claim（E09）。未知外部结果保留原意图与原业务号查询；本地完成及业务回执终态需同事务提交。G 没有验证保证金外部调用符合这一合同，必须另行验收。

## 原 D1–D4 已复核，不再要求抽象架构重选

| ID | 既有合同结论 | 剩余处理 |
| --- | --- | --- |
| D1 经典正金额非合作 | 保持 NON_COOP 拒结，已有逐报名 DIRECT 账本，不恢复发布者现金 | 技术证明现有受益人/账本与收口完整性，不重新问禁售或恢复分润。 |
| D2 scope/context | 7列唯一身份、直接 owner 及原路径已规定；REGISTRATION 的 ACTIVITY owner 不能换成其 topic；章节 REDEMPTION 保留原 TOPIC_EDITION | 类型化 scope、逐行经济归属和跨route冲突为技术修复，不推给用户重选。 |
| D3 计量与收款 | 当前 FIXED_PER_FULFILLMENT 按 distinct registration；经典俱乐部实际 payout member 和四店对公合同各有原规则 | 不跨产品重选收款模型；仅多人票“固定费×人头”与报名级证据矛盾保留 Q1，见 approved-contract-review。 |
| D4 退款/证据窗 | T+7/客诉冻结及冻结原额后退款扣划/债务已有批准实现 | 保持时点，补缺失调整消费者和边界测试，不通过改政策消除阻塞。 |

完整批准来源与唯一具体问题 Q1 见 `approved-contract-review.md`。比例、保底参数、NONE 读取既有冻结合同；缺失/矛盾时人工修复，不猜默认值。
