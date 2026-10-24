# WP04 原订单分项与成功冲回：源码候选，未验证

Parent: `e4e9f6042ba34fb571c3bcfbfe59e248000ef637`。独立分支 `codex/architecture-day-g-original-refund-20261024`；不修改 A/B/C、workflow 或 registry。本批不是可部署闭环。

## 本批实现

- `CoopSettlementOriginalLedger.freezeOriginals`：MANDATORY 原事务、topic→排序 head；接收调用方已经批准的原计划，只校验并冻结，不选择分币算法。校验真实 B 原支付引用、原 payer、自身完整现金头集合、逐订单/逐头/全 close 守恒及不可变指纹。支持保存负毛利事实，不自动决定谁承担亏损。
- 三张新增表：原分项、退款回执、负向调整。保留旧 `claim_id NOT NULL` 和旧唯一键；平台为 ACCOUNTING/null member，商家仍引用真实 claim/detail。组件、回执和调整通过同 close/payer/head 复合 FK 关联，WX 引用 B 的原事实 FK；人工双审使用既有人工终态独立命名空间，不伪装 WX。
- `reverseCompletedRegistrationRefund(refundId)` 对齐 A ae160684 的终态 hook。只消费真实原 SUCCESS 的 B inbox→fact 引用或现有人工双审回执。验证原 mchid/appid/attempt/transaction/currency/total/refund amount，去重退款 ID 与原事实；同事务追加回执/全部原头调整/余额流水/已有 debt，任何失败须回滚调用方 SUCCESS CAS 与本地效果。无新事务、SDK 调用或自写 VERIFIED。
- 已打款仅在原 event=5/head ID/真实收款流水证据存在时追收；余额不足复用 `SettlementDebtLedger`，来源为原调整 ID。未收款人及 ACCOUNTING 不扣余额、不造债。原头金额和原 detail 不覆盖。
- payout 在 topic fence 内锁读全部头和当前执行调整；新负调整参与净出款。可识别负头阻断该 topic 出款，不置已付、不转嫁其他会员。原余额→流水→paid→debt offset 的后缀保留。
- 原分项归属读入口 `hasFrozenOriginalRefundComponents`：缺失新原证据不降级；完全没有本原 close 的历史订单仍由旧 owner 处理，不凭旧聚合头补造分项。

## 必须组合的真实接线

1. **B** 已实际 fetch `3893bc66d097ff4980ea0b76f3285197f0a3fbe3` 与 `041534c7681e480f80eb8afc911f65bf1ed13eda`；与原 `6d641fa802cdf1277a158ce0b7633dcf126b5e10` 比对，以下域内类型/service/XML/schema 字节未变。须组合其域内增量：`WechatVerifiedPaymentFact` / `Reference`、`WechatVerifiedPaymentFactService.requireVerified`、fact 与 callback link 表。此 parent 尚无这些类型；本候选不能单独编译，不复制 B 的源码或另造事实。官方验证仅 B 负责。
2. **A** `ae1606843cca6ed847d84059640b1bab6e1decdc` 的 `g-terminal-hook.review.patch` 中 RefundService hunks：SUCCESS CAS/同终态重放/人工双审调用本 hook，与 inbox DONE 同事务。接口 hunk 本候选已提供，勿重复加。
3. **A 审核阶段**还须应用本目录 `a-review-route.review.patch`（仅 review.patch，未改 A 文件）：在原审核 CAS 前确定原分项 owner；新原分项订单不再走批准阶段旧 `settlement_detail` 资金追收。保留原资格、原实际退款额、报名变更和 pending 明细作废；防止批准追收一次、SUCCESS 又追收一次。该 patch 依赖第2项注入字段，不应孤立应用。
4. 旧 5 条 topic-fence IT 装配用 `frozen-topic-fence-fixture.review.patch`，保留 root gate/断言/SQL。该旧 fixture 没有本新分项协议，仅注入空新来源；不能当成新退款链的验证。

## 明确未完成 / 禁止启用

- 人类仍未确定：累计部分退款舍入、原聚合头逐订单分币、负毛利承担方。代码仅支持首次精确全额冲回；部分退款报 `PARTIAL_REFUND_ROUNDING_DECISION_PENDING`，负原分项报 `NEGATIVE_ORDER_MARGIN_POLICY_REQUIRED`。这是未完成边界，不能作为拒绝合法退款的最终方案或发布验收。
- `freezeOriginals` 尚无生产分配 producer：不能用任选比例生成原件，不能把旧聚合头回填成原分项。因此终态接线不能在缺原始计划时先启用。
- 已实际 fetch C `f1cfe74d58b766e385e639d40385e208790644a8`，读取 `service.RegistrationSeatService#verifiedSeatsForGroup(topicId,activityId,cutoff)` 及其 XML：返回真实 `RegistrationSeat` 行，按 registration/seat 排序，要求 status=1、verified_at<cutoff，且区分主题 GM 证据或真实活动父关系。当前报名完整锁读已在源码改正；Java/DB 未验证。这个 group/activity 范围仍不能自动充当某商家的 REGISTRATION_SCAN/特定合同事实；本批存储 owner 不计履约费，未解除 qty≠1 guard，也未发明替身接口。
- topic 共同 fence 之外的 late SUCCESS 归属/新原贡献接入仍 P1；本批不声明付款/退款全链路完成。
- 新 SQL 仅候选，要求旧 G cash expand 和 B fact 迁移。三表必须在接入前完成真实 MySQL 约束/旧数据 preflight/中断阶段恢复验证。DDL 自动提交；后续增量已提供 `Wp04OriginalRefundMySqlIT#recoverEmptyPrefix` 的 CI UUID 专用源码：同 MySQL 建独占 reference，逐列/索引/约束/FK/CHECK/入向引用/trigger 比对精确 0–3 CREATE 前缀，全部空才逆序清理；不提供通用生产恢复入口。该程序尚未实际验证。不得应用生产；无旧数据回填。回滚停新读写，非空证据/资金/旧唯一键全部保留，不 DROP 或 DELETE 资金行。

## 验证

- 新 `CoopSettlementOriginalLedgerTest` **14 个普通 @Test 声明**：原全额冲回/重放、已付余额不足记债、跨 App 拒绝、缺 B 链接拒绝、人工双审、缺事务、负头/其他负头阻断、缺真实打款证据、冻结真实 B 引用、原载荷冲突、owner 路由/旧路径保留。使用 mapper doubles 和事务标记，**不能证明真实事务回滚、MySQL、性能或完整财务闭环**。
- 三类旧测试仅新增必要 mapper 注入，旧场景与断言保留。
- Java 编译/测试、MySQL/Redis、CI dispatch 均 `NOT_RUN`。新 `Wp04OriginalRefundMySqlIT` 已提供 **10 个普通 @Test 声明**（固定计数，不含参数展开）：真实 binary inbox/hex link、原付款未绑定拒绝/冻结事务回滚、全退重放、已付钱包+债务、写钱包流水后故障回滚、跨 App 拒绝、并发同事实、跨 close 复合 FK、DDL 前同名 constraint 拒绝、空阶段恢复，以及变形/非空拒绝恢复（恢复三个前缀在单条测试内循环）。其中付款/退款使用 B 真实 typed fixture 持久化，topic/member 是真实 JDBC 锁/更新 adapter，终态 CAS/inbox DONE 是显式 SQL 夹具；不声称 A 真实 Service 或 SDK 验证已通过。root 统一执行，当前 **0 实跑**。
- 实际静态检查和逐文件 SHA-256 见 `static-checks.txt`、`source-sha256.txt`，不与业务通过混报。

## 第二增量修正（首批 1f781f41 不单独验收）

- 真实原 inbox 是 BINARY(32)，B sidecar 为 CHAR(64)：两处 G 查询改为 `UNHEX(SHA2(...))` 与 `LOWER(HEX(inbox.notify_hash))` 的明确转换。原付款还须绑定真实 PAYMENT/DONE inbox、B 同一事实引用，以及报名原 transaction_id/wechat_payment_amount；不能从同订单号的任意成功事实选一个。
- 新迁移补严格 FK/sql_mode/MySQL 版本前检，并修正 preflight 的 CAST 语法闭合。所有迁移仍未实际执行；首批未验证的 SQL 缺陷保留历史，不重写旧提交。
- 第二增量没有修改首批 Java 生产 owner、A/B/C 或 frozen topic-fence IT。`queryRefundFacts(originalPaymentRef,originalRefundNo)` 已在 B 041534c7 确认存在；G 本 owner 不在事务内查 SDK，恢复 producer 应由 A/B 在事务外用原身份调用后再进入原终态 hook。
