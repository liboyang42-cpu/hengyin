# G：原现金贡献、逐头事件回执及空受款 DDL 方案

结论：建议 **共享核心3张窄证据表 + 扩展现有head/adjustment**；若选择逐原订单冻结冲回，另需1张窄原分配表；继续复用原 merchant claim/detail、原 payout 的 head.id 业务键及既有债务账，不新增分账平台。本批仅是供 root 确认的具体方案，**0 生产/测试代码修改、0 DDL/Java/MySQL 执行**。退款金额选择及多人固定费仍待用户回答，任何字段/示例均不授权实施其中一种金额算法。

## 身份、文件与依据

- 公共仓库 `https://github.com/liboyang42-cpu/hengyin.git`；参考基线 2ec68899099a1e250df0949a8383bf85b5d3ffe0。
- 独占工作树 `/workspace/hengyin-g-cash-origin-plan`，分支 `codex/architecture-day-g-cash-origin-plan`，开工干净，HEAD/parent `ee71b74b629539e9a257c308491f8f9d94fe193e`。此前 ee71/a421/27a 均冻结，不改源码。
- 全文读 A `59eeae487a22803cf5e930ed5ece42c7c7525c1c:docs/delivery/architecture-day/A-funds-fence/PROTOCOL.md`；H `9de37f77.../g-wp04/REVIEW.md`；实际当前 schema、Claim/Detail/Adjustment、Pricing/Refund/Debt 与旧 2026-07-12 spec/plan。精确源码 hash 和方法位置见 source-identity.json。
- 独占文件均在本目录：schema-candidate.sql（完整候选结构）、preflight.sql（只读预检）、allocation-option-a.sql（仅逐单路线需要的第4表）、本报告、money-evidence.md、验证脚本/输出/指纹。不改根 schema、旧 migration、CI 或别人的交付目录。

## 为什么既有表不足，哪些继续复用

| 现有能力 | 保留用途 | 缺口 |
| --- | --- | --- |
| settlement_claim 七列经济键 | 原 MERCHANT_PARTICIPATION 等真实履约归属，跨 route 唯一 | 未核销 R2 是销售资金贡献，不是扫码履约；不得为关联销售伪造 merchant/REGISTRATION_SCAN claim |
| coop_settlement_detail 原始明细 | merchant 原额、真实 registration→claim→head | 没有 PLATFORM/保底/毛利的现金全集；detail.claim_id NOT NULL/UNIQUE，不可借其他头 claim |
| coop_settlement 分项行 | 原 PLATFORM/发起人/merchant 经济头、T+7、head.id payout 去重 | 缺原销售全集、冻结总公式输入与事件归属；verified_reg_ids 只含核销报名 |
| coop_settlement_adjustment | 所有有符号 delta，原始 head.amount 不覆盖 | claim_id NOT NULL 排除非履约现金头；EXECUTED 不证明已付后扣余额/债务已执行；void API 无首次执行回执 |
| refund_application | 原退款身份/原号/审核/外部退款终态 | adjustment_created 一个位不能证明多头金额/扣回/债务；不替代本方案事件 |
| 旧 DIRECT settlement_detail | 继续处理自己的原合同 | 不新写来“补”coop现金来源，避免另一出款路和双扣 |
| SettlementDebtLedger | 同事务记差额、后续入账抵债、30天到期限制发布 | 当前 record 返回 void/insert-ignore，须补严格同额读回和 debt.id；不能用同 refund/member 的旧键吞第二头 |

旧 spec 的“不新建主子表”针对替换分项账模型。本方案保留 coop_settlement 每分项一行，新增的是完整资金输入、原事件证据，不重造资金主账。当前用户明确要求先提窄新表方案；未把旧设计口号当成能伪造履约语义的授权。

## 具体关联与唯一身份

1. **cash_close**：每 topic 一条原正额现金收口证据，context 固定 TOPIC_EDITION/topic（既有 canonical 合同）。平台原头作锚，冻结发起人、S、完整输入行数、p/base/step/max、原 n、计算实现版本、全部有效订单冻结合同快照/所用原 detail/窗口/身份及内容指纹。不再读取 live rate 重建历史。主键之外 UNIQUE(topic_id)、UNIQUE(platform_head_id)。记录不因 HTTP TTL 失效。
2. **cash_contribution**：UNIQUE(close_id,registration_id)，每张原已付报名都记录，包括未核销 R2、payable=0 的同父单从单。每行冻结自身 owner/真实 source topic、原付款主体 registration.id/registrationNo、parentOrderNo（仅集合键）、原 payable、完整支付/积分分额及状态、票种/qty/退款规则等 JSON。`payment_evidence_type/id` 明确区分本地已付记录和 B 的完整已验证付款事实；本地已付状态不冒充渠道原始 SUCCESS。
3. **head.cash_close_id**：PLATFORM、保底、毛利及所有 merchant 原头绑定同一 close；全局头通过生成列 UNIQUE(close_id,global_line_type) 各只有一条。merchant 的**直接履约归属**仍只经现有 detail/claim→registration→contribution；close 组关系只表达计算依赖，不声称所有销售都被每商家接待。平台/发起人对 S 的依赖由完整贡献全集和原公式证明，**不预先捏造逐报名应收分配或分钱尾差**。选定退款算法后才产生具体delta；若选择逐订单冻结，必须在初次新收口按获准规则将每个contribution到3类全局头的真实原额写入allocation-option-a.sql所述表，UNIQUE(contribution_id,settlement_id)，不得缺失当0。该条件第4表不用于merchant，原merchant仍复用原detail。全期路线不需该表。
4. **cash_event**：UNIQUE(close_id,event_type,source_id)。退款 source_id=原 refund.id；迟到款 source_id 必须是 root/B 确认的不可变已验证付款事实 ID，不是 HTTP 幂等号、租约或本地计数器。保存来源金额/身份的不可变快照及指纹；同键不同来源/金额必须冲突。PENDING 是原意图，APPLIED 只表示本地归一完成，不代表外部退款 SUCCESS 或补充应付已到账。
5. **adjustment**：保留旧 UNIQUE(claim_id,entry_kind,event_key)；新增 cash_event_id/effect_key 并 UNIQUE(event_id,head_id,effect_key)。真实 merchant原经济effect_key=`CLAIM:<原claimId>`；非履约原经济头用 `CASH_HEAD` 且 claim_id=NULL（不是假 0）；事件本身指向完整贡献和头，不与 claim 竞争履约身份。零差额头存在事件 plan_snapshot 中，不造零额流水/调整。

原现金范围：CLASSIC_SALES 初始只能 owner_type=1/topic。原已付活动壳存在就按当前允许范围拒绝经典收口。收口后发现的真实 ACTIVITY 付款可作为 ACTIVITY_OBSERVED、绑定原事件和真实父关系留证，**不计入经典 S、不造 merchant claim、不将已删壳漏掉**；其后由真实 scope owner 处理合法结算/退款。原现金缺证时不自动按当前表、CSV 或 merchant detail 回填完整历史。

同父单的 payer 必须沿原实际付款身份/金额验证，不能选最小 ID；每行 sales_basis_amount 沿原 payable，只对 payer 计其原现金，其余零行保留权利/履约来源。不能把同一聚合 wechatPaymentAmount 在从单重复累加。重复识别已存原贡献只读回，不能更新原金额来“适配”退款。

## JSON证据字段合同（不是金额规则）

- `cash_close.contract_snapshot`：schemaVersion、原有效coop订单按ID排序的完整termsVersion/termsSnapshot/role/scope/from/to/serviceUnit、窗口起止、原真实merchant claim/detail IDs与原额、原n的商家集合、当次跳过的重复payee及原因；对完整键/十进制/时间/排序做canonical指纹。原公式4参数及S/n已另列强类型字段，必须逐项相等。
- `contribution.registration_snapshot`：schemaVersion、ticketId、purchaseKind、orderQuantity、paymentStatus、registrationStatus、verificationStatus、payableAmount、wechatPaymentAmount、pointPaymentAmount、transactionId、refundPolicyCode/version/deadline、原实际payer身份验证依据。每个键均须存在，金额用原精度十进制字符串；原字段确实未知可显式JSON null但不能补0或称已经外部核验，依赖该字段的经济动作继续标缺证。CLASSIC初始记录要求paid2/reg2，payable等于强类型sales_basis_amount，原正cash payer和0从单分额校验。
- `cash_event.source_snapshot` REFUND：原refund.id/refundNo、sourceType/sourceId、实际payer/原订单号、原实付分额、该次批准额及前序批准退款ID/金额、有权审核的状态事实；PAYMENT：root/B确认的verified-fact表版本/ID、原out_trade_no、transaction_id、币种/分额、sourceRegistrationId、已验证SUCCESS来源与确认时间、原本地业务状态。金额不得从HTTP请求正文获得，不复制证书/秘密/完整敏感载荷。
- `cash_event.plan_snapshot`：选定策略和实现版本、所有原contribution/head/claim、旧已执行事件列表、每个新目标/差额、0差额头、实付证明ID、effect key和执行方式；所有head必须同close、所有claim必须是该原head.detail。APPLIED前按计划核对非零effect数、金额、回执与补充交付头，不能只比hash。源快照不更新，计划首次确定后不换算法。

JSON承载固定领域证据，不是任意脚本/公式平台；无需新增JSON库。若选逐单路线，真正原分配用条件第4表强类型金额和复合主键保存，不藏在不可查询的JSON里。

## 事件回执和实际经济效果

`cash_event.plan_snapshot` 保存已选定规则版本、全部原贡献/头/claim、目标 delta（含零项）、实付依据和按顺序的执行计划；plan_fingerprint 与 expected_effect_count 在一次 topic 锁下确定。政策未答时保留未选版本，不计算或执行金额。plan 不可因重试改写为另一个算法。

| execution_kind | 证明与同事务结果 |
| --- | --- |
| ACCOUNTING | 仅平台对账，空会员；signed adjustment，无会员流水/债务。PLATFORM status=1 不能当余额已付 |
| NET_PENDING_HEAD | 真未付头：追加 signed adjustment，原 head.amount 不动；扣余额/债务均0；payout 后续按当前净额出款 |
| RECOVERY | 原头已付后出现负delta，先减少该原头尚未付的补充交付应付，余额才由已付部分追收；unpaid_net_amount+实际扣回+欠款=-delta。paid_flow_id仅是原头或已付交付头的首个真实正额入账锚，plan保存全部真实flow及前序追收证明，不能把一个锚当完整实付总额。余额/欠款部分>0必须有实付依据；balance_flow_id/debt_id各唯一，不重复认领 |
| DELIVERY_NET | RECOVERY的交付子回执：target是未付交付头，parent_cash_adjustment_id指原经济RECOVERY，effect_key=DELIVERY:<该parent effect.id>，只减少该交付头未来净付。所有子回执负额合计恰等于父unpaid_net_amount；不计第二份经济冲销、不扣会员余额/造债 |
| SUPPLEMENTAL_PAYABLE | 原头已付后的正额 delta：不重置原 paid，不重复 claim。一次性新建 line_type=ADJUSTMENT_PAYOUT 的交付头，source_cash_adjustment_id UNIQUE 指回该调整；继续用新 head.id 的原 payout 去重/余额流水。平台正额仍只 ACCOUNTING |

每个RECOVERY的已付追收部分的债务 source_type 采用窄固定 `coop_cash_adjustment`、source_id=该 effect.id、memberId=原真实受款人，避免同会员两个头被旧 refund/member 键吞掉。现有 record helper 需新增严格读回同额及返回 ID 的窄方法，旧 DIRECT 债务键不改；不把旧 DIRECT 与新 coop 重复执行。新追收余额流水使用root登记的独立窄event_type、event_id=effect.id，并由effect.balance_flow_id引用；不能把adjustment.id冒充旧event_type=6的refund.id。具体常量由共享事件owner确认，DDL不猜数字。成员先按 ID 统一预锁，再逐原 head/effect 执行；单成员多 effect 的余额分配按固定 head/effect 顺序，所有 debt 和余额总额必须一致。

正补充交付头不再计作一份经济原额：聚合/对账以原经济头+全部事件 delta 为权益口径，交付头仅证明支付；实际现金用所有真实payout flow（含交付头）减实际追收flow，债务作为未收回差额单列。负delta同时遇到“已付原头+未付补充头”时必须先抵未付应付，再追已付部分，不能对尚未领到的钱直接造债。交付子回执的金额不参与经济原头delta总额，避免双冲。现有 selectSummaryByTopic/Beneficiary、selectByTopicId 等消费者须显式区分 source_cash_adjustment_id，避免原调整和交付头被算两次。不能直接加列就宣称原工作台已正确；root 需协调查询消费者。

原 margin 为0时当前代码不落头。方案要求未来新 close 在同事务保存 **0元原 margin 头及真实发起人**，仅为后续原身份锚；不发余额/不造扫码 claim。旧缺头场景只能由原证据确定补出0元身份，不能按 live owner 猜。schema 候选本身不执行此源码变更。

## 原子性与 A 接缝（不是已实现的锁保证）

- 原收口：topic 当前锁→A/root 包装资源既定顺序→报名基础行升序→退款历史→头/claim/detail→统一 member；CAS、完整贡献/close、原头、业务回执/积分及原退款意图在原实际事务提交。平台头先插 cash_close=NULL 得原 ID，再插 close/贡献并回绑；任一步失败全部回滚，不允许提交半组。新 head 引用同一 close 复核。
- 审核：按 A 协议在最外层取 fence；批准 CAS 与原 `refund.id` 的 PENDING 归一意图原子提交。规则已获准且证据完整可 MANDATORY 同步归一；若暂需异步，批准/合法退款不能因尚未取得追收余额被永久拒绝，原退款原号流程独立保持可恢复。
- 消费：复用现有 CoopSettlementPayoutTask/service 增窄 pending-event 消费，不新增通用队列/租约平台。候选筛选必须包含`state=PENDING AND (next_attempt_at IS NULL OR next_attempt_at<=now)`，不能因NULL重试时间永远漏消费。topic→报名/退款→原头升序→原 detail/effect 确定顺序→member 升序。所有 effect、实际扣回/flow/debt、新交付应付、事件 APPLIED 一次事务提交。异常回滚原事件保持 PENDING，下轮按原 ID 恢复；REQUIRES_REVIEW 必须有原因、责任人/已有告警入口及恢复动作，不能当终态完成或无限无人消费的冻款。
- 重复：先读原 APPLIED 回执和原指纹，不再扣款/记债。首次执行 API 返回 APPLIED_NOW / ALREADY_APPLIED（来源/计划/实付回执）；void insert-ignore 不能指导“是否再扣一次”。同键不同金额/收款人直接冲突调查。
- payout：只读定位原 head 的 topic，先 topic、再 current head；检查所有当前原现金/已验证迟到事实均有归属、相关退款/事件已归一、当前 effect/实际付款覆盖，再锁 member 出款。绝不 head→topic、普通 SUM 漏新调整、回头锁 inbox。原 head status、claim 和源号不重开/释放。
- 外层必须按 A 全文接 inbox.lockBusinessTarget 前、TeamGroup/Club 包装首锁前、create/beginPaymentAttempt 等。G 内部方法加锁不能弥补旧入口反序。B 网关、D 通知 winner 合同和授权复核保留。

## schema 确认、预检、迁移和回退

schema-candidate.sql 为完整列/唯一键/CHECK/FK 提案，**未执行/未通过 MySQL 语法验证**。只放本交付目录，不是生产 migration。root 确认结构后才生成正式独立 migration/业务代码及受控测试。旧 base_schema/旧 migration 不改。

1. preflight.sql 全部只读：真实版本≥8.0.16/CHECK强制/strict mode、表形状及索引、候选命名未占用、空受款例外、孤立 member、重复全局头、原 detail/claim 错配、重复实付、部分退款与旧调整。每个异常逐 ID 对账，不补假会员或删账。
2. nullable payee 只允许 PLATFORM/DEPOSIT_DEDUCTION + payee_type=platform + status1或2；其他现金行继续真实正 member。两种平台行都只记账；CHECK 使用 CASE 把 NULL/UNKNOWN 转成明确失败，不放宽整个现金表。已有平台0/default-member 行需单独原证据裁决，不能迁移时自动洗掉。
3. 新表/CHECK/FK 与 head/adjustment 扩展分检查点执行，MySQL DDL会隐式提交，不能声称一个 BEGIN 可回滚全迁移。旧657/ee71未具备新回执消费，不能在其现金调度仍可执行时单独应用nullable DDL；新代码/调度在变更窗口由root依授权保持停用，逐步 SHOW CREATE 比对；ums_member_settlement_debt须先具备canonical `migration_complaint_period_funds_gate_20260917.sql` 的真实表形状（base_schema自身不含此表），不得为测试手造简化债务表；索引/表重建影响须在隔离库实际检查，不强制未经验证的 INSTANT/LOCK=NONE。
4. 无自动历史回填。旧头/旧调整保留原样但不虚构 cash_close/执行回执；需要原记录/渠道/冻结版本可证明的逐项归属。识别不充分不放行受影响 payout；这是待补事实清单，必须安排恢复责任/测试，不能称历史兼容已完成。
5. **空迁移回退**：只有新3表（如选A还含allocation表）均0行、无 head.cash_close_id/source_cash_adjustment_id、所有新 adjustment 字段为空，并确认没有任何空 merchant_id 时，才可按依赖逆序撤 FK/索引/生成列/新增列、新表并恢复 claim_id/merchant_id NOT NULL；正式回退脚本须在这些前置断言后才生成。已有新现金/事件/空受款对账行任一存在，即禁止 DROP/清空/补0/回写原额；保留结构与账本，停消费者后用兼容读/原事件恢复，不能机械降级。

需 root 确认的技术项：共享3表及逐单路线条件第4表、head/adjustment扩展及正补充交付头、A 的同事务/持久意图接点、B verified-payment-fact 的实际表/唯一ID合同、追收流水事件常量登记、查询消费者改动owner。金额选择见 money-evidence.md；本批不向用户重复提已待答问题。
