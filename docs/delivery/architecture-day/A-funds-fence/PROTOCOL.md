# A：H-G01 / H-G03 跨域资金 fence 设计（待 root/G 协调，尚未编码）

结论：可复用 `cms_topic.id` 的现有 `SELECT … FOR UPDATE` 作为共同数据库 fence，无须为锁新增表。但**仅在两个 A 方法里加锁不能交付**：inbox 已先锁报名，活动取消及其俱乐部包装已持有其他行锁；必须在最外层事务进入这些锁之前接线。现有账本还缺未核销销售对 PLATFORM/发起人头的原始贡献关联，以及实付后调整的执行回执；topic 锁不能补出这些事实。以下是精确接线和原身份协议，不能宣称 H-G01/G03 已修复。

## 1. 来源与隔离

- 公共仓库 `liboyang42-cpu/hengyin`，参考基线 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`。
- 实际只读源码 HEAD `65780b036d9e44e3ab8705bb8c244558a09213af`（G），tree `ca907c001d9ded38ef9de3dbad0fc02cdd19d171`；独立目录 `/workspace/hengyin-a-fence-design`，分支 `codex/architecture-day-a-fence-design`，创建时干净。
- H 报告 `9de37f77b4d19389f7dadf6c6a88da4eece09e1b:docs/delivery/architecture-day/H/g-wp04/REVIEW.md`。本报告行号只针对 G657，不把 root 后续联合候选当作已逐 blob 审核。
- 另读 D `b6267613693d0c067040002c3df431ac39b35feb` 与 G 的报名服务 diff：新增依赖、`freshPaymentClaimed`、通知接线是该文件全部差异。后续必须保留 CAS 后立即保存 fresh 标志、原付款主体为卡片首 ID、历史修复不新造通知的合同。
- 仅新增 A 交付文档/源码指纹与调用者证据；生产、测试、Mapper、schema、B 网关、D 通知、G 实现均未改。旧 A 候选冻结。

## 2. 已追到的真实入口、事务和现有锁序

这里的箭头是持锁的先后；普通定位读不算取得 fence。所有查询结果都需锁后复核。

| 入口 → 实际方法 | 当前事务/顺序 | 协议必须前移的位置 / owner 协调 |
| --- | --- | --- |
| `RefundController.review` → `RefundServiceImpl.reviewRefund:582` | REQUIRED；普通读 refund → `loadRegistration` → `lockRefundRegistration:685` 锁单票或父订单 → 按 id 锁退款历史 → pending CAS → 报名退款 CAS → 旧明细调整/余额；controller 提交后才派原退款号 | 不能在 pending CAS 后才加锁。后台入口在 `loadRegistration` **之前**定位 topic 并取 fence；公共锁 helper 也供建单/同单限额使用。A 接线，G 归一调整同事务调用点在审核 CAS 成功后、方法返回前 |
| `ApiRegistrationController.cancel` / `cancelByOwner` → `TeamGroupServiceImpl.cancelPaidRegistration:135` / `cancelPaidRegistrationByOwner:304` → `refundResolvingOrder` | 外层 REQUIRED；部分核销工单可先锁报名/权益；`refundWholeOrder:1228` 按 id 锁父订单，先 claim 从单、恢复库存，再 `refundOneAfterInventoryRestored:1455` → saveRefund/reviewRefund；原退款号提交后派发 | 必须在外层两个 public 方法**首个报名/权益/库存写锁前**持 topic；不能只改 reviewRefund。原 owner/RBAC、窗口及双人例外不变 |
| 独立/商家活动取消 → `cancelActivityByOwner:368` / `cancelActivityByMerchantHost:385` → `cancelActivity:551` | 当前 activity → 报名集合/父订单 → refund；同事务先收口 PAYING；可调用 MANDATORY 补记付款 | 定位 activity 的真实 topic，先 topic，再 activity；不能在已经锁 activity 的 `reviewRefund`/`completePaymentSuccess` 里补取 topic |
| `ApiClubEventOpsController` → `ClubEventOpsServiceImpl.cancelOccurrence:431` → `cancelActivityByClubOperator` | 外层 RC REQUIRED；series 或独立 activity → club → occurrence/角色 → TeamGroup → 报名/refund | root 须协调包装 owner：topic 必须前移到 series/activity/club 首锁前。保留锁后权限复核；不改岗位政策 |
| API 活动取消 / 主题取消 → `cancelActivityByOwnerWithClubWrapUp:558` / `cancelActivityForAuthorizedTopicCancellation:565` → `cancelActivityWithClubWrapUp:571` | 包装已锁 series/occurrence/club，独立分支还先锁 activity，再入 TeamGroup | 同上；私有内部方法不能补救外层已经反序持锁。主题取消重入相同 topic 不另取不同 topic |
| `ApiTopicController` → `TeamGroup.cancelTopicByHost:417` | 当前 topic → topic 票退款 → 逐 activity/club 包装退款 | 已有共同 topic 起点，但其子调用的 series/club/activity 顺序仍须与单活动入口一致。`ClubTopicSettingServiceImpl.endTopic:84` 自身无事务，逐场独立事务从上述入口取 fence |
| B 验签/解析后 `WechatPayServiceImpl:750`，或 `OrderCloseTask:182` → `handleWechatPayCallback:2090` | 外层禁止事务；inbox register 独立提交 → claim 独立提交 → `WxPaymentCallbackMutationService.apply:23` REQUIRED：inbox 行 → `lockBusinessTarget` **先锁报名** → A mutation → inbox DONE | root/B/共享 inbox owner 必须在 PAYMENT 分支的 `lockBusinessTarget` **之前**取得 topic fence及父单有序锁。不能只在 A complete 方法加锁；不修改 B 网关。G 不得反向锁 inbox，保持私有 inbox → topic 的单向关系 |
| 活动取消 `settlePayingRegistrationsBeforeCancel:814` → `applyConfirmedWechatPaymentWithinTransaction:2164` → `applyWechatPayCallback` → complete | MANDATORY，沿用外层锁；跳过 inbox，支付 CAS 是业务 winner | 外层先拿 topic；本方法核验锁后原号/金额/交易号，不能开 REQUIRES_NEW 躲过外层锁。已确认外部 SUCCESS 不因结算位而丢弃 |
| `RegistrationCheckoutServiceImpl.finishOrder:108` → `completeZeroAmountPayment:2276` → complete | 独立 REQUIRED；按原号定位，金额双零闸 → 支付 CAS → 从单 CAS/履约、积分、D intent | 零元入口在首个支付 CAS 前先 topic、activity（如有）、父订单；已完成同号重放只读回原结果；未发生的零元领取可在收口闸前拒绝，不能消耗合同义务假装无销售 |
| `SettlementTask:49` → `executeTopicSettlement:1096` → `settleTopic:1226` | 每主题 TransactionTemplate；topic CAS（已隐式锁 topic）→ **普通 SUM/COUNT** → reader/current candidates → refund 锁 → claim/detail/head → 积分 | G 先显式锁并读回 topic，使用同批 current 资金事实取代普通 SUM/COUNT；原先任务列表中的 topic 对象不能当冻结真源 |
| `CoopSettlementPayoutTask:29` → `payoutDueCoopSettlements:1690` → `payoutOneDueCoopSettlement:1730` | 每头 REQUIRES_NEW；head → 客诉普通读 → adjustment 普通 SUM → member → 余额/流水/paid/债务 | G 先普通定位 head.topicId，再 topic fence，再 current head读回、当前退款/贡献/调整核验，最后 member。不能保留 head → topic |
| `CoopSettlementClaimServiceImpl.appendExecutedAdjustment:54` | REQUIRED；detail → adjustment 唯一键；当前 main 只有接口/定义，无生产消费者 | G 接消费者时不能继续 detail → topic/head；先 topic、原报名/退款、head，再原 claim/detail/adjustment；插入和已付扣回/债务必须同事务 |

调用搜索正文见 `callers.txt`；`source-identity.json` 给出读过的精确文件 hash。`saveBackOfficeRefund` / `saveRefund` 也经 `lockRefundRegistration`，会新增影响 payout 的待审事实，不能漏掉建单与 payout 的竞争。`supersedePendingRegistrationRefunds` 也须沿用同一外层 fence。

## 3. 最小锁协议（需成组接线，不能只合并一端）

### 3.1 共同 fence 与原身份读回

1. 普通读只定位：refund.source_type/source_id → registration；报名 owner_type=1 时 `owner_id` 就是本次结算 topic；owner_type=2 时按**真实** `cms_activity.id=owner_id` 解析 `topic_id`。不拿活动 id、模板/source_topic_id、contract context id 冒充 topic，不过滤已软删壳来消除资金事实。没有父主题的独立活动不伪造 topic；保持其现有活动资金协议，不能混入经典 TOPIC 结算。
2. 在最外层资金事务、任何共享领域行写锁之前，调用现有 `CmsTopicMapper.selectCmsTopicByIdForUpdate(topicId)`。topic 缺失/归属损坏不能 fallback；资金调查继续保留原单。定位只读允许先执行，但不能把定位对象作为后续资金证据。
3. 锁后 current 读回 activity→topic 绑定、报名 id/member/owner/parent/orderNo、原金额/状态；若 topic/owner/父单身份改变，整笔回滚后重新定位，**不能持一批旧行锁再补另一 topic 锁**。缺字段不填默认。跨 topic 的父单属损坏事实，拒绝经济动作并调查。
4. 父订单成员按 registration.id 升序锁，复用 `selectByParentOrderNoForUpdate` + `OrderRefundCash.assertOrderScope`。G657 的 XML 只按 parent 过滤，并没有 owner 条件，不能把 Java 传了 owner 当成 SQL 已证明隔离。必须锁后逐成员核对 scope/member、唯一实际 payer/首单及现金快照；不能选最小 ID 当付款主体。原现金首单 registrationNo 是唯一 out_trade_no，parentOrderNo 仅作集合键；从单不发外部支付/退款。
5. 锁定行返回的值直接构造本轮事实集合。所有资金决定用 current rows，不再在较早 RR read view 上普通 SUM/COUNT；涉及重复调用的 MyBatis SELECT 须精确禁用缓存/清理该查询缓存。`flushCache` 不能单独修 RR，首选直接计算已锁记录。

G 的 cash-basis 锁查询宜只锁所需报名基础行，再按既有证据接口校验履约；不要把 `r LEFT JOIN ticket … FOR UPDATE` 的隐式票锁或无顺序扫描当成已证明的 registration 升序锁。活动父关系查询同样必须 current；全活动 id 清单不能继续用 `selectActiveActivityIdsByTopicId` 的普通 RR/软删过滤结果当资金全集。此处需精确 Mapper 接口，由 root/G 认领，不新增通用锁平台。

### 3.2 必须满足的锁图

`私有 inbox(如有) → topic（升序） → 包装的 series/club/occurrence/activity → registration（升序） → refund history（升序） → settlement head（升序） → claim/detail/adjustment（确定顺序） → member（升序） → balance flow/debt/其他原子结果`。

这是**拟统一合同，不是当前代码已满足的结论**。series/club/occurrence/activity 的内部次序还要 root 与其 owner 选定一条并成组调整：当前 standalone cancellation 是 activity→club，series 分支又有 series→club→occurrence / series→occurrence→club 差异。topic 取消不得先做 topic 票的余额扣回、再去取场次/俱乐部锁；要在首次报名/余额副作用前按选定顺序预锁本次需要的全部包装资源及全部报名，或由 root 协调等价的事务拆分和独立持久意图，不能随意拆掉全主题原子取消。

member 最后不是一句足够的保证：父订单退款当前先恢复库存再批 refund；complete 付款当前会按调用主体先 CAS，再逐从单 CAS，并依次锁多个积分收款人。接线前须先锁完整父订单集合，并预收集本事务所有会锁的会员（买家/邀请人/赏金追扣人/实际收款人），按 id 一致顺序锁定，后续只重入已有锁。若还有 entitlement/ticket/role/通知 intent 交叉依赖，需把参与的原有写侧纳入锁图复核；不宣称本次只读分析证明全仓无死锁。

可见的反序不能靠重试、超时或 `REQUIRES_NEW` 隐藏。特别是付款 callback 原 `inbox→reg→topic` 与结算 `topic→reg`，以及 club 包装的 `club/activity→topic` 与主题取消 `topic→club/activity`，必须在启用 fence 前消除。G 不取 inbox 锁，避免 topic→inbox 回边。保留所有原授权判定及锁后复核，不改 RBAC。

## 4. H-G03：同一资金集合与迟到 SUCCESS

### 收口时

- topic fence 后锁定本主题当前全部资金相关报名，既包含 TOPIC 票，也包含真实父活动票；活动发现/阻断不丢弃软删壳。**未核销已付票也进入 cash basis**，不能用 merchant origin 的履约筛选代替总销售集合。
- 从同批当前行计算 TOPIC 的原现金基数、当前 ACTIVITY 已付/待决阻断和履约候选；父单首单汇总额只计一次，从单不能再累加其 wechat 字段。已批准退款、未决退款及外部支付未决事实用 current 关联源身份核对。
- 已付活动票仍按 G 冻结合同支持范围拒结经典 TOPIC，不在此设计中偷偷支持 ACTIVITY scope。售票窗结束不证明所有外部支付都没发生。PAYING/在途/UNKNOWN 先留原号调查；渠道查证在短事务外，回来重拿 fence 后读回原状态，再决定是否具备收口事实。
- `create`（报名创建事务）、`beginPaymentAttempt`（外部预下单前）和必要的活动父关系创建/改绑入口也必须在首个相关锁前参加 fence；否则收口后仍可启动新支付。`create` 当前还先进入 waitlist bridge，不能在已经持 offer/club/ticket 锁后补 topic。B Checkout 由 root/B 接线，A 不越权修改。普通活动列表快照不能证明没有新壳；付款入口必须锁后验证真实父关系。
- 启动支付拒绝闸必须发生在本地扣积分/库存/权益或外部建单前；不得为了 fence 增加“拒绝但先改钱”的新路径。既有过期关单副作用合同本轮不擅改。

### 收口之后收到原支付 SUCCESS

- `is_settlement=1` 只代表原结算批已建立，**不是外部原交易没成功**。外部 SUCCESS 不能走 CLOSED/STALE、不 ACK 后丢事实、不换单号、不把整个原主题重开为未结算。
- 仍为原待付状态且身份金额相符：A 原支付 CAS、父单从单、原付款事实应按原号提交，保留 D 的 fresh winner/通知合同。把该资金贡献标为“尚未进入原结算或调整”的可持久发现事实；G 后续 payout 在 fence 下必须证明所有已确认现金都已归一，否则暂停受影响头，不能按旧总额付款。支付业务回执 DONE 和结算调整是否完成是两个不同事实，不能假称一个 DONE 已完成两者。
- 为此 G 必须持久化原结算完整现金贡献集合/金额，不能只存 verifiedRegIds；新 paid 行不在原集合或原额改变可被原 ID 对账发现。迟到款追加新原身份贡献/调整，不覆盖原头金额、不释放原 claim。不支持的活动现金仍保留付款成功事实并阻止错误归入经典主题；其后续结算/退款处置由真实 scope owner 接。
- 如果原报名已本地关闭/退款/损坏，现有 `claimPaymentSuccess` 不会成功；不能为“成功回执”无条件复活权益。现有 inbox 只有 hash/bizId/eventType，没有 transactionId/金额完整载荷，不能冒充不可变支付资金证据。**完整 late SUCCESS 接线须有持久已验证原交易事实**（原 out_trade_no、transaction_id、币种/分额、sourceRegistrationId、来源/确认时间、状态及唯一键）及原号恢复入口；优先核对 root/B 新候选是否已有这类事实，若仍无，提交窄 schema 提案后再编码。当前仅重试 inbox 不能证明永久留存了完整外部成功事实。
- 零元首次领取没有外部既成 SUCCESS，可以在合法收口闸前拒绝而不造钱/权益；已完成原号重放仍读回原结果。不能把零元履约票跳过有效合作合同正固定义务（H-G02 仍是 G 独立修项）。

## 5. H-G01：退款 ↔ payout 原身份协议

### 5.1 A/G 接口触点（以下为提议，仓库尚不存在这些新方法）

- A `reviewRefund`：**前置 fence** 在 `loadRegistration` 前；锁后审核/原额/父单/双人校验与 CAS 不变。pending CAS 真实成功后，将“原 refund.id + source registration.id + topic.id”交给 G 的窄归一入口，要求 **MANDATORY、同一个实际事务**，在本方法返回前完成。不要让普通回执/日志替代归一证明。
- G 归一入口接收上述原身份，不接受 A 传入猜测的分账金额或当前 live rate；在锁内读取不可变原贡献、所有受影响头/已有调整/实际 paid 证据，核对原额后一次处理全部分项。A 不接管 G 的金额算法/Mapper。
- 若选择先接受退款、异步归一，必须与批准在同事务落**可恢复且唯一的归一原意图**，并由 payout 同 fence 当前读阻断到归一执行终态；现有 `refund_application.adjustment_created` 字段在 G657 没有生产写入/消费，不能当成现成回执。不得把“批准退款后永久冻款且无人消费”称闭环。
- G `payoutOneDueCoopSettlement`：只用普通 head 读定位 topic，先 topic，再锁当前 head；current 读回本主题所有相关原贡献及退款/调整归一回执，验证覆盖后才取 member。只调用一次普通 `sumExecutedBySettlement` 或只查投诉不满足这个证明。
- G `appendExecutedAdjustment`：从 `claimId` 只读定位 head/topic 后，按共同顺序取锁再读回原 detail；需要给消费者返回“原事件是否本次首次执行/已执行且同额”的确定回执。当前 void API 的 insert-ignore 去重不能独自指导实付后扣款。

### 5.2 身份与经济效果

- 退款外部身份沿用原 `refund_application.id/refundNo` 与唯一原 payer 的 out_trade_no；不要按从单新退款、按重试次数造事件。资金归一身份可采用 `refund:<refundId>`，与原 head/原贡献/claim 一起构成不可变幂等键；同键金额、来源或受款人不一致必须回滚调查。
- 原现金贡献集合要覆盖**全部已计入销售的报名/父订单**。未核销 R2 没有 merchant claim 不代表对发起人/平台金额贡献为零。PLATFORM、INITIATOR_GUARANTEE、INITIATOR_MARGIN 当前直接 insert head，没有相应逐报名 detail。现有 `coop_settlement_adjustment.claim_id NOT NULL` 又只由 claim 找 merchant origin；不能伪造一个 merchant claim 替代 R2 的全主题贡献，也不能凭空造平台会员。
- 最小账本接口须能将原资金贡献绑定所有受影响头，再给原退款事件追加 signed delta。可评审方向：为真实**现金贡献**增窄关联证据/归一事件回执，或经 G 证明扩展既有 claim/detail 语义足够。不得不经论证复用 `REGISTRATION_SCAN` 表示未核销现金贡献，或把 template/clubId 填进平台 beneficiary。具体 schema 由 root/G 决定，未提交/执行 DDL。
- 只对金额及归属**有冻结证据**的分项执行；退款净额/取整须由同一冻结合同计算、按原经济身份读回。不能自动用旧 `refundRatio` 的缺分母 fallback，不能假设多人固定费、部分父单票价或选择比例扣回与重新分摊规则。规则/历史归属不足时保留退款原意图与待调查状态，并明确未完成归一；不是拒绝合法退款作为解决方案。
- **refund 先于 payout**：批准与所有归一原意图/负调整原子提交。payout 后取得 fence，只按原头+已执行净调整出款，负/零额不造正余额流水；失败整笔不置 paid。原头永不覆盖。待审退款先于 payout 时也阻断或按获准规则归一，不凭“还没批准”放款。
- **payout 先于 refund**：退款后取 fence，逐原 head 读真实已付证据（包含原 event_type=5/event_id=settlementId 余额流水），不能仅凭 `status=1` 或 topic 已结算判已收钱；零额置 paid 无正向付款。对已支付部分执行原身份扣回，余额不足按既有 `SettlementDebtLedger` 记差额债务，对平台只对账。调整插入、实际扣款、流水、债务与执行回执同事务提交。不能把一个 EXECUTED 负调整写下就认为已追回余额。
- 同会员同时有保底、毛利、merchant 多头时先确定全部调整再按 memberId 升序锁；债务键须能区分本次原调整，或先聚合本 refund/member 的应扣额再写一次，防现有 `refund:<receiverType>, refundId, memberId` 把第二头 insert-ignore 吃掉；不得在旧 DIRECT 与新 coop 各扣一遍。
- **重复/失败/丢回包**：读回原 refund + 原贡献/头 + eventKey 的确定终态；同额重复零新增经济效果，不能由“没有拿到回复”再扣一次。余额/流水/债务/归一写任一失败回滚审核 CAS 和本地经济效果；若已选择先接受退款异步归一，失败保留持久原意图并阻断该头，不重新生成退款号。
- 外部退款 UNKNOWN 保留原负债/原号查证，租约到期、一次 NOT_FOUND 不撤销原调整。原退款最终未成功时，不能自动把用户权益复活或把应付加回；若业务允许撤销批准，需要原身份的显式补偿协议，当前没有已核实的现成规则。不能把持续 MANUAL_REQUIRED 当已退款成功。
- 债务只证明待追偿金额，不代表用户已经收到退款，也不自动提供外部退款资金。既有“平台不垫付”约束下，欠款未补时如何完成真实退款所需的可用资金及恢复入口仍须由 root/G 绑定当前付款合同；不能由 A 猜测平台先付、也不能用余额不足永久拒绝合法退款冒充闭环。原退款派发/回调的真实 SUCCESS 与原号终态仍要独立验证。

## 6. root/G 编码前须锁定的最小协作项

1. root 认领共享 inbox 的 PAYMENT **前置锁**接线，与 B 协调；G 不改 B 网关。A 的 complete 方法只接已具备 fence 的路径，不能自行掩盖外层 reg 锁。
2. root 协调 TeamGroup/ClubEventOps（及必要的创建/待补报名入口）最外层的 topic 前置和包装资源顺序。G 的 payout 也由 head-first 改为 topic-first。统一顺序之前不要只合并一侧。
3. G 给出全现金贡献集合（含未核销、父活动、父订单）与三类非 merchant 头的**真实关联**，以及退款归一/实付追回回执。是否需窄迁移待具体提案；不能靠当前 merchant-only append API 宣称完成。
4. root/B 核实已有 verified payment durable fact 是否足以恢复**已关闭原单的迟到 SUCCESS**；若没有，先给窄字段/唯一键/状态/原号查询及迁移预检方案。禁止 fake ACK 或在根本无法保存事实时吞异常。
5. 金额规则缺口只列阻塞：父单部分退款无法拆票、固定费多人、历史原贡献、退款后平台/保底/毛利净额算法及取整。能够从原冻结合同直接证明的简单路径可先做；不凭本报告示例新增规则。

## 7. 受控验证与当前证据等级

本批只执行 Git/rg/Python 的调用搜索、文件 hash 和 doc-only 差异核验；**0 项 Java/MySQL 执行，未新增测试源码，未 dispatch CI，未执行 DDL**。H 报告的 7 条 SQL 已通过只覆盖其原声明范围；不扩成跨域通过。root 通知的 run40029223893 是既有 Photo10/Recovery4/Fresh3/Signup18 通过，取消 8 业务用例在该次尚未执行。后续 root 已逐 XML 验证 source `1dc32d08095f5a621e6d665c75345b15a74e7c21` / run40029966352 的取消 8 项通过、0 failure/error/skip、CLEAN；旧初始化 1 error 单独保留，不能相加宣称 dde91 整批同源通过。以上均不计为本协议验证，也不宣称 RegistrationFunds 的 92 通过。最新 source af712 / run40030174071 仍在运行，root 已报告 fast gate/migration safety 红，不能写成全门禁通过。

建议 root/G 实现后复用现有真实 MySQL 测试设施，至少覆盖以下独立场景（此处是计划，不是已新增/已通过用例）：

| 组 | 必须实际观测的交错/恢复 | 核对 |
| --- | --- | --- |
| RR 与活动父关系 | 先建立旧 RR view，另一线程提交未核销 ACTIVITY 付款；已删壳；同 topic 下新壳+付款；原 parent 改绑检测 | 同批 current cash guard 与付款事实一致；不错误进入 TOPIC cash；不靠清缓存绿 |
| 总销售 / 履约分离 | H 的 R1已核销10、R2未核销90完整挂账；退款 R2；父单一次现金多从单 | 未核销贡献可回溯，原现金只计一次，merchant claim 不虚构 R2，逐头原额与调整可读回 |
| 两种先后 / 并发 | refund批准先 / payout先；两个线程争 fence；先建待审退款；payout失败后重试 | 本地余额、负债、原头、负调整、paid、退款原意图均一致；合法首次退款确实可完成 |
| 至多一次 | 重复退款审核/重复归一事件/重复payout；提交回包丢失后读回；同会员多头+不足余额 | 原 event 身份固定；每头净调整一次；实付扣回/债务不重复、不漏第二头 |
| 回滚 | 批准 CAS 后调整失败；加/扣余额后流水失败；paid失败；债务/归一回执失败 | 整个实际事务回滚，原号重试能够完成；原始账不覆盖 |
| 迟到 SUCCESS | 正常付款先 / 收口先；原待付已收口；本地已关原单；重复回调；付款提交回包丢失 | 真 SUCCESS 的原号/交易号/金额持久可恢复；不 STALE ACK、不重开整主题、不丢权益处置责任；payout 不按旧额漏归一 |
| 锁序 | inbox付款 vs topic结算；父订单不同成员并发；club/独立活动取消 vs topic取消/付款；相同会员不同主题 | 受控 barrier 强制交错，短超时仅辅助发现问题；不能把 deadlock重试成功当统一锁序证明 |
| 零额与拒绝 | 合法零额首次/重放；正固定义务零销售；身份/金额/owner不符拒绝 | 合法首次效果一次；非法请求经济/权益/外部动作零变化；D原通知 winner合同不变 |

可复用的现有 suite：`PaymentCallbackFenceEntryTest`（mock inbox，非真实锁竞争）、`RegistrationPaymentAtomicityContractTest`（源码/事务合同）、`RegistrationFundsMybatisIntegrationTest`（保留原资金断言）、`RefundMultiPartyClawbackTest` / `RefundSingleSidedDebitTopicGuardTest`（旧明细路径，不能替代新 coop 正控）、`RefundPayoutMybatisIntegrationTest`（原退款派发）、`CoopSettlementPayoutTransactionTest`（既有 fixture，不能冒充本次真实 MySQL全链）、G evidence MySQL IT 与 H 两个未运行 review patch。必须新增完整服务链的真实 MySQL 证据，不能只 mock 一个负 adjustment。

本批无迁移、无业务效果，回退仅撤本目录文档。未来若落地，已经确认的原支付/退款、claim/detail/head、调整、流水、债务、intent、inbox 身份必须保留；应用回退不能删账、清库或释放原唯一身份。
