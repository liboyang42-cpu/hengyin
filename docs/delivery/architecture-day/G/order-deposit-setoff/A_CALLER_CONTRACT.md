# A unit 移交：已写 donor 与当前生产合同

公共 parent `7b1412f4cd37d293576499e92c7d44b36ee7c766`。单位文件随后已由 root 明确移交 A；本目录 unit-donor.review.patch 保存 G 停笔时的完整单文件增量（未运行），不是要求 A 整文件覆盖。原文件已从 G 生产候选中恢复到 parent，以后不再编辑。

共享签名不变：`appendExecutedAdjustment(Long claimId, String eventKey, BigDecimal signedAmount)`；无新接口/DTO/账本。

当前生产顺序：原 pending 毛额头/claim/detail persist → applyUnpaidDepositSetoffs → 原净额+保证金重分类守恒 → freezeOriginals → 同 Tx insert DEPOSIT_DEDUCTION 正行。原头与原分项金额不改。

`applyUnpaidDepositSetoffs` 私有签名不变；只处理已持久化且同主题/同应缴人的 COOP_ORDER 正净额头，按 invite ID、coopOrderId/headId、registrationId/claimId 顺序；eventKey 为 `UNPAID_DEPOSIT:<topic>:<invite>:<order>:<head>`（claim 为原 unique key 的独立维度）。

receipt 来自真实 append 方法和原 mapper 当前读回；负调整 ID 是平台 DEPOSIT_DEDUCTION 的 source_contract_id，source_contract_type/settlement_route=DEPOSIT_SETOFF。无新 schema。

fixture 必需：真实 persist/append owner、heads.selectByClaimId、原 details 当前读、executed adjustment 当前读、原 refund adjustment 当前读（新关闭前显式空历史）、topic/invite 锁和 CAS；正抵扣须 actual Spring transaction active。donor 使用真实 owner+Mapper doubles，不能声称真实 SQL 回滚；G 另补真实 MySQL 源码。

两个原50元场景已在 donor 恢复原名字/金额含义，原头50/负调整-50/净0/收保证金50。原未知支付拒结测试仍保留。E 的关单查询在外层 Tx 外执行，随后短 Tx 确认原 CLOSED，单位场景复用实际 reconcile 私有入口，不把 close ack 当授权。

NOT_RUN：donor 未执行 Java/DB/CI。G 生产工作仍在进行，这次 docs-only 提交不代表生产片已完成或可验收。
