# G：现金结构检查点（零销售 / late SUCCESS 合同尚未统一）

结论：这是**未验证、不得直接应用或启用的结构检查点**，不是 H-G01/H-G03 闭环交付。新增三表、现有 head/adjustment 扩展与 25 条真实 MySQL 测试源码；Java 编译及 MySQL **执行均为 0**。只读核对 root `00fc58c5` 后补入零销售 close 与全状态原订单快照；late SUCCESS 完整来源接口仍未统一，不能把本候选当作可启用的现金结构终稿。

## 基线、隔离与范围

- 公共仓库：`https://github.com/liboyang42-cpu/hengyin`；公共参考 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`。
- 本批实际父提交：`5e2803425f4954d48efa07a7176176a289476d30`；独立工作树 `/workspace/hengyin-g-cash-evidence-expand`，分支 `codex/architecture-day-g-cash-evidence-expand`。从原方案创建时干净，没有切换/回退共享树。
- 只新增本批 migration/precheck/rollback、三 DTO、一 Mapper Java/XML、专属 IT 和本目录。既有生产 Java/XML、A/B/钱包/债务、CMS 方法、原 051/657/ee71/27a、根 CI/迁移登记均未改。
- root 已修的 WP08 Matcher 和旧 SQL IT env/UUID cleanup 不在本批 diff 内。
- 遵守根 AGENTS；用户明确批准隔离未验证候选提交/推送，Java/MySQL 统一由 root 受控 CI 执行。本机不跑 Java/数据库，不派 CI。

## 当前 close 身份与零销售修正

1. close 唯一业务身份是实际 `cms_topic.id`，`commercial_context_type=TOPIC_EDITION` 且 context_id=topic_id；ZERO/POSITIVE 共用 `uk_g_cash_close_topic`，不因迟到款重新开 close，不因 HTTP 响应 TTL 释放身份。
2. 明确 `close_kind=ZERO_SALES|POSITIVE_SALES`。ZERO 允许 S=0、原观察订单数=0 或更多，不要求任何 head；无来源的 initiator/费率允许按真实 topic 保留 NULL，不能写假会员/假费率。POSITIVE 保留真实 initiator、完整四费率、S>0、观察行数>0。`CASE ... ELSE 0` 令 SQL NULL 不能绕过 CHECK。
3. contribution 保留**关账时**原订单的 payment/registration/verification 状态、原 payable 与本次销售贡献。非 paid=2 且 reg=2 的原行贡献为 0，原 payable 仍保留。例如待付订单应付20，原基数0，并非填成零价票。
4. 这些行只存原观察事实，不把待付单当已付，不增发权益/奖励，不写余额。状态以后变成 SUCCESS，不覆盖 close 的原 S 或原贡献快照；同原身份不同载荷冲突。
5. 结构不证明关账集合完整、冻结合同允许零销售收口或本次费率可计算。未来生产事务必须先共同 topic fence、全状态 current 读回、核对活动真实父关系/全部原订单与已验证外部事实，再验证完整集合/合计并同事务提交。三个独立 insert 不能冒充这项证明。
6. 本批不修改 `00fc58c5:CmsTopicServiceImpl.settleTopic` 的零销售收口行为，不宣称已解决 A 发现的原 SUCCESS 晚于收口问题。

## same-close 约束与实际强断言边界

- contribution `(cash_close_id,source_topic_id)` → close `(id,topic_id)`；payer 用 `(close,payerRegistration,payerOrderNo)` → 原贡献同 close 的真实 payer。父单 payer 不能取最小 registration ID。
- event `(cash_close_id,source_registration_id)` → contribution `(close,registration_id)`；head `(cash_close_id,topic_id)` → close `(id,topic_id)`；发起人头 `(close,member)` → close `(id,initiator)`。
- adjustment 同时以 `(event_id,close)` 和 `(head_id,close)` 引用 event/head，不能跨 close 或只填一半新链接。
- **没有声称“独立外键即可证明全部业务身份”。** refund source_id 与其真实 registration 的关系由 `insertEvent` 的 `INSERT ... SELECT refund_application` 精确匹配 source_type/source_id/status/del/amount，并要求事务中 affected=1；重复先锁原自然键，读回完整原载荷，异载荷回滚。任意 SQL 客户端直接写表不受 Java 断言保护。
- 同理，源 topic/member/四冻结费率以及 registration 当前状态/金额/原号均须 `INSERT ... SELECT` 精确匹配；这些 Mapper 是未来内部存储原语，不是外部可信认证接口。
- 待 A 给出实际反例后，root/G 仍须对照具体 insert/事务，而非只看 FK 名称。未执行真实 MySQL，不声称上述约束已在引擎上验过。

## 尚未统一的 late SUCCESS（技术接口阻塞，不是新的金额政策）

实际核对 `00fc58c5`：

- `WxPaymentCallbackInboxServiceImpl.register` / migration `migration_wx_payment_callback_inbox_outbox_20260725.sql` 仅持久 notify_hash / bizType / bizId / eventType / 租约，不含原支付完整金额、币种、交易号及确认来源。
- `WechatPayServiceImpl.handlePayCallback` 已验签后向报名层传 out_trade_no / transaction_id / trade_state / total；该路径 map **没有 currency**。DEP 分支另做 CNY/mchid 检查，不能把 DEP 的检查当作报名路径检查。
- `queryOrder` 返回 total/currency/transaction_id/out_trade_no/trade_state，但不是不可变原成功回执；`completePaymentSuccess` 的 registration 状态与 transaction_id 也不能代替已关闭原单的完整 SUCCESS 原事实。
- 当前新 event **仅实现 REFUND_APPROVED 来源**。它要求已有原 contribution，故即使 ZERO close 已可建立，也还不能保存“空 ZERO close 后才发现原支付”或“已关闭原单但外部已成功”的完整事件。此缺口明确未实现，不用退款行、假贡献、假正 S、假 head 或 STALE ACK 代替。

建议 root/A/B 先统一的最小接口：

1. 保持 close 原 topic 唯一键不变；已验证 payment SUCCESS 用原 payer registration / out_trade_no / transaction_id 的确定身份追加，close 原 S 和原贡献不更新；来源明确 CALLBACK 或 QUERY、确认时间、币种、实付分额、原 sourceRegistrationId，且金额/商户身份经过原验证链核对。
2. 新三表内可选择让 cash_event 自身作为完整已验证事实的不可变回执，或精确引用 root/B **已存在且确实含完整事实**的回执；需要 root/B 给出实际表/唯一键/字段/候选 SHA，不能把 hash-only inbox 当成它。G 不额外建支付平台/第四金额分摊表。
3. 事件必须允许 ZERO close 原贡献集合外的真实晚到源；用真实 topic 关联和强事务断言绑定同一 close。不能继续以“必须在原 contribution 内”为保存完整 latefact 的先决条件；原观察集合之外只能标明新事实，不能补入原销售合计伪装当时已知。
4. 原正常付款/父单从单/通知 winner、关闭单的权益处置、回执与支付业务效果的事务边界由 root/A/B 共同确认；G 不改其代码。是否已完成现金归一须与支付 DONE 分开，dispatch 保持关闭。

在此接口统一前，不整批铺生产逻辑，也不将本迁移标成可上线终稿。这里尚无退款净额或多人固定费的任何算法。

## PLATFORM / head / adjustment 的窄扩展

- 旧 `uk_settle_order`、`uk_coop_adjustment_event`、claim_id NOT NULL 保留。
- head merchant_id 只在显式 `cash_head_kind=ACCOUNTING`、精确 platform / PLATFORM 或 DEPOSIT_DEDUCTION、status 已入账/已付时允许 NULL。默认 MEMBER 保持旧缺字段写侧拒绝；非平台仍必须真实 ums_member，不造会员。
- 真实会员原列 signed 与 ums_member.id unsigned 的差异以正 ID 的 STORED unsigned 引用列处理，不修改原会员 ID 类型。增加真实会员 FK；旧脏行 preflight 阻断，不修补/删除原账。
- 新 head 可空 cash_close，便于保留旧头；新全局头在 close 内各 line 唯一。adjustment 只增加事件/close 引用，仍不能把无 claim 的 PLATFORM/发起人调整硬塞进旧 merchant claim。未核销票不造 REGISTRATION_SCAN claim。
- 现有生产写侧没有 cash_head_kind DTO/SQL 接线，本候选不会让现有 null 平台写侧悄悄成功。

## 预检、事务与回退

- `_precheck_...sql` 只读；迁移首句相同硬断言在第一条 DDL 前验证 MySQL8>=8.0.16、strict/FK、真实旧表/字段/唯一键、候选结构不存在及旧真实会员约束。失败不覆盖残留候选表，也不清数据。
- MySQL DDL 自动提交，不能声称失败整批事务回滚。完整迁移与空表回退实际行为待 root MySQL 验证。
- `_deploy_...empty_rollback...sql` 只在新三表全空、无 cash 链接/ACCOUNTING/null 会员头时才 drop 新结构；保留全部旧账。已有任何新证据后拒绝该回退，不清资金记录/事件/审计。
- Mapper record 方法要求真实可写 Spring 事务，精确自然键 FOR UPDATE 全部 cache=false / flush=true；同身份逐字段+规范化 JSON 读回比较，不能仅比较指纹。无 upsert/ignore/通用重试框架，无生产消费者。

## 实际验证与未跑项

已实际执行：Python SQL gate 对三个新 SQL 均返回 []；XML 可解析；三条 FOR UPDATE/cache flag 静态检查通过；源码计数为 25 个 `@Test`。这些是静态结果，不是 SQL/结算运行。

未执行：Java 编译、25 项 MySQL、真实 forward migration/preflight/rollback、真实并发、B 验签/关单/原号恢复、真实结算非零金额/余额/归一/退款/债务/出款。没有 Mock/H2/Python 合成业务数冒充金融验证；root 00fc CI 通过不覆盖本批新增源码。

25 项 IT 计划包括：旧行/旧 unique 保留、显式平台 NULL/SQL NULL 绕过拒绝、原 JSON/type/current scope、父单真实 payer、contribution/event/head/adjustment 跨 close、全局头唯一、异载荷冲突、实际事务失败回滚、脏旧账 preflight、残缺结构拒绝覆盖、空回退/非空拒绝、事务要求；新增五项专测合法空 ZERO、待付原额与原基数分离/状态后变不覆盖、ZERO/POSITIVE/NULL 类型错配、全状态原观察、已付不能伪造零贡献。测试只证明所实际运行的结构/原证据范围，不计算/发放资金。

另补独立外键反例的行为源码：refund502 属于 registration201，而 close11 已有 registration101；两个独立源 ID 都存在时，recordEvent 仍必须由真实 INSERT...SELECT 的来源断言拒绝，原501正控可记录。此为新增测试设计，尚未执行；不冒充 A 尚未发出的完整反例报告。
