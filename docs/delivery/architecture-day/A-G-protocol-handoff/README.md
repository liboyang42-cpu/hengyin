# A → G：共同现金协议修正交接（只读）

结论：**pay1 已有当前锁读后的拒结保护，普通 PAYING 绕过零额收口的说法撤回。** 同 close 跨引用目前只有候选结构反例，没有真库准入/服务绕过结论；零额原收口后迟到外部 SUCCESS 目前只有协议待证条件，没有可达供应商时序或资金损失结论。G5e280 是结构提案，不能据此宣布共同 fence 或跨域 P1 已闭合。

## 固定身份与边界

- 公共仓库 `liboyang42-cpu/hengyin`；本批基线/parent `deb2f19ffd1f60b061234a5366038ead5c7dafba`。
- 独立工作树 `/workspace/hengyin-a-g-protocol-handoff`，分支 `codex/architecture-day-a-g-protocol-handoff`；开工干净。仅写本 A 目录，不改生产、测试、schema、CI 或 G 目录。
- G 提案固定为 `5e2803424f4954d48efa07a7176176a289476d30:docs/delivery/architecture-day/G/cash-origin-proposal/`。不把它等同于当前已实施结构。
- 前序 A 协议 `59eeae487a22803cf5e930ed5ece42c7c7525c1c`；详细结构审查及五段未执行 SQL 为 `e4830458359e2079a9e40312682a6595dc496184:docs/delivery/architecture-day/A-G-cash-protocol-review/{REVIEW.md,negative-controls.sql}`，保持冻结。下文收窄证据等级，不重写旧候选。
- `evidence.json` 保存固定 Git 对象、文件 SHA256、精确行锚及每项证据等级；`verify_static.py` 只检查文本/身份，不执行 SQL、Java 或数据库。

## P01：已证存在的 pay1 保护

当前 `TopicSettlementCashBasis.fromLockedRows` 在累加金额前，对任一 `paymentStatus=1` 抛 `PAYMENT_CONFIRMATION_UNRESOLVED`。`lockTopicCashBasis` 先取主题及实际父活动报名的当前锁读，再调用该 helper；两条查询不按核销、删除标志或付款状态过滤报名，且禁缓存。主题零额分支在这个 helper 之后。

因此，将一张被当前查询选中的 pay1 报名与零额结算并发，并不能直接构造“结算当零额成功”。也不能以单次 NOT_FOUND、租约到期或本地超时证明渠道从未受理。

现有源码断言：`TopicSettlementCashBasisTest.paidUnverifiedActivityAndUnknownPaymentsCannotCloseAsZero` 覆盖活动 pay1 拒绝；`Wp04TopicSettlementEntryGuardsTest` 有真正空主题、合法 NONE、正义务拒绝及退款阻塞的零额分支用例。它们证明测试源码存在；**本批未运行这些 Java 测试，也不推断完整并发链已验证。**

## P02：同 close 跨引用的精确结构反例，仍未实跑

先决 fixture：A/B 是独立真实 close/topic；E_A 是 A 的合法 PENDING 原事件；H_B 是 B 的真实 PLATFORM 原头；S_B 是 B 的合法正额 SUPPLEMENTAL_PAYABLE effect；所有待写唯一键空闲。每个反例独立事务并回滚，不能用生产 ID 或假会员。

| 反例行 | 候选本行为何未证明归属 | 必须证明的合同 |
| --- | --- | --- |
| ACCOUNTING effect：`cash_event_id=E_A`、`settlement_id=H_missing`、`claim_id=NULL`、`effect_key=CASH_HEAD`、`amount=-0.01`、`status=EXECUTED`、执行数字全0 | 原 adjustment 及候选没有 effect→head 的 FK；候选 event FK 只验 E_A 存在 | 目标头存在，属于事件 close |
| 同形 ACCOUNTING effect：目标换成真实 `H_B` | 独立 event/head 身份没有跨行 close 等式；本行 CHECK 只验形状 | `event.close = target_head.close`，原头类型/受款语义一致 |
| `ADJUSTMENT_PAYOUT` 交付头：`topic=A`、`cash_close_id=A`、`source_cash_adjustment_id=S_B`、真实会员、正额 | head→close 和 head→source effect 各自是单列 FK，未比较两者 close | 交付头、来源 effect、原经济头同 close，原受益人及执行种类匹配 |

前三段精确 INSERT 已保存在上述 e483 的 `negative-controls.sql`，可 `git show e4830458359e2079a9e40312682a6595dc496184:docs/delivery/architecture-day/A-G-cash-protocol-review/negative-controls.sql` 取回。静态抽取 G 主候选为 **11 条单列 FK**；这只说明这些 FK 本身没有证明同 close，**不证明 MySQL 实际接受这些完整 fixture，更不证明尚未实现的 Service 可被利用**。G 文档已经要求 plan 的所有 head 同 close；本批不把该要求写成缺失。

G 需给 root 的最小回答：逐关系指定复合引用或唯一窄 writer 的当前读复验位置；恢复、重放、payout 也必须进入同一校验。申请隔离 MySQL 后分别运行上述负控及合法首次正控，断言无事件 APPLIED、无净应付/余额/债务/交付效果；失败注入须整笔回滚。同 ID 不同 close/原额冲突不得当重复成功。若用复合 FK，保留原“头暂未绑定 close→建立 close→同事务回绑”的可建立顺序，不能造两端立即非空引用循环。

## P03：零额原 close 与迟到 SUCCESS——可达性待 B、归属协议待 G/root

当前真正空主题或合法 NONE 的零额结算可以正常结束；并非所有零额结算都缺证。G 候选却只表示正额原 close：`original_sales_basis>0`、贡献数>0、平台原头必填；cash event 又要求非空 close 外键。

以下是**条件化协议反例，尚不是运行重现**：

1. T 已依当前合法零额条件收口，无正额原平台头/正额 close；当时所有被锁读报名均不是 pay1。
2. 此后系统取得原订单号的完整、可信、不可变外部 SUCCESS 事实 F，需按原身份保全；本地业务可能已关闭或冲突。
3. 若 F 确实属于 T，且没有既存正额 close，则不能直接写目前要求 close 外键的 PAYMENT_CONFIRMED event；不能伪造原 S、贡献、平台头或重开已关闭主题来填字段。

**尚缺的关键证明就是第1→2步是否在真实入口/渠道合同下可达。** B 须给出原发送意图→原订单本地状态→渠道最终事实→回调/查证入口的完整时序，或证明它在现有合同下不可达。不要把普通 pay1 仍在途当该证明；未有此证据前，保持“协议未覆盖/可达未证”，不报已发生丢款。

若可达，G/root/B 必须明确一个原事实保全出口：可表示真实零额原收口的证据，或已有 B verified-fact 原号责任记录先持久保全、再归一。出口不能先 ACK 丢事实，也不能靠永久调查状态冒充完成。若暂未绑定 close，非 NULL 的原 topic/源事实业务唯一身份仍须独立存在；不能让 `UNIQUE(nullable_close,type,source)` 的 NULL 重复规则承担去重。尚未完整归一的事实不能标 APPLIED 或参与 payout。

这不授权选择退款分账、多票固定费、代付/赠送或补录金额，也不授权以拒绝合法退款代替闭环。

## 交接验收与执行状态

| 项目 | 本批状态 | 下一证据 owner |
| --- | --- | --- |
| pay1 现有拒结、当前锁读、零额合法路径 | 固定源码已核对；Java未运行 | G/root 安排已有相关用例真实验证 |
| 同 close / 孤立头三个结构反例 | 文本/约束已核对；SQL/MySQL未运行 | G 给约束/窄入口方案，root统一真实负控+正控 |
| 零额收口→迟到原号 SUCCESS | 条件化协议反例；真实可达性未证 | B 给完整原号时序，G/root 定事实归属与恢复合同 |
| 外层共同 fence 接线及锁图 | 59ee 的既有余项；本批未复跑/未修 | root协调 A/B/G 外层首锁，不能局部加 topic 锁宣称闭合 |
| 已付 RECOVERY effect 自增身份/回执创建顺序 | e483 S01 仍待 G 回答，本批没有新运行证据 | G 指定真实原 ID 创建与同事务终态协议 |

仅运行 Git/Python 文本与身份检查、秘密文件/有限格式扫描及 diff 检查；Java/MySQL/DDL/支付/CI dispatch 均0。无 schema 或迁移影响，撤本目录可回退文档，不回退业务或删除账本。

A95另属独立负控分支：root通知 source `2a2f37e8a7e7c357be390b586d889ec7535a1937`、run40040570934 运行 funds95；尚未向本批提供最终结果。本批不从“已启动/预期红”推断95通过或失败数。主 integration deb2 保持94，生产 Controller.add 冻结，用户产品选择仍待答。
