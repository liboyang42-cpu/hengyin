# A 对 59ee / G5e280 现金结构与接线的只读复核

结论：**G5e280 仍是未执行的结构提案，不能据此宣布跨域 P1 闭合。** 发现一项追收 effect 自增身份/回执创建顺序缺口，以及可精确构造的跨 close/孤立 head 结构反例；另有零现金收口后事实归属须补明确恢复入口。当前外层共同锁接线仍未落地，属于 59ee 已登记的协调余项，不冒充本次新回归。未修改生产/测试/SQL migration，没有运行 Java/MySQL/DDL 或 dispatch CI；金额算法及多人固定费继续待用户。

## 身份与核验范围

- 公共仓库 `liboyang42-cpu/hengyin`，只读实际源码 `66e9e1798bf0b3e1c2b7e6cb2b6ab13a3b3e1329`。
- 独立工作树 `/workspace/hengyin-a-cash-protocol-review`，分支 `codex/architecture-day-a-cash-protocol-review`，创建时干净。仅新增本 A 目录，A de31 及其 IT 均冻结，未覆盖 root 正在修的数值断言。
- 全文读 A `59eeae487a22803cf5e930ed5ece42c7c7525c1c` 的 PROTOCOL，以及 G `5e2803424f4954d48efa07a7176176a289476d30` 的 `cash-origin-proposal/{README.md,schema-candidate.sql,allocation-option-a.sql,money-evidence.md,verify_static.py}`。G parent 为 `ee71b74b629539e9a257c308491f8f9d94fe193e`。
- 下文候选 DDL 行号针对 G5e280；当前 Java/XML 行号针对 root66e9。精确源码/外部文档 SHA256、11 条 FK 原始边见 `evidence.json`。不是数据库执行结果。

## A-G-S01：已付负调整的 effect.id / 执行回执创建顺序尚未定义

G README 要求新追收流水 `event_id=effect.id`，债务 `source_type=coop_cash_adjustment, source_id=effect.id`；原 adjustment 的 id 在 canonical `migration_settlement_claim_20260811.sql:40` 是 AUTO_INCREMENT，候选没有改这一点。另一方面候选 `schema-candidate.sql:153–168` 要求新 effect 一插入即 `status=EXECUTED`，实扣/债务正额必须已有 `balance_flow_id/debt_id`；`:143–145` 的 FK 又立即要求对应流水/债务行存在。

精确受影响路径：**原头已实付，负 delta 不能全部抵掉未付补充头，需实际扣余额或记债。** 全部抵未付应付的 RECOVERY 不需要这两类新回执，不属于本项反例。SUPPLEMENTAL 正额先插 effect 再插交付头也不是这个时序问题。

按提案目前文字，首次合法追收无法直接照搬“先插 effect 取得自增 ID → 写扣款流水/债务 → 填回执”的实现：第一条新现金 effect 的空回执 INSERT 已被 CHECK 拒绝；用 PENDING effect 插入也被 `status='EXECUTED'` 拒绝。反过来先写流水/债务，则尚没有该 effect 的真实自增 ID；不能用 refund.id、cash_event.id、MAX(id)+1 或假 claim 代替。

这不是声称任何显式主键方案都不可能，也不是已观察到 MySQL 报错。它是**原子事务创建协议欠缺**，应在编码前选择并写清：

- 可选窄方案：同事务先登记不可变原 effect 身份/原额的 PLANNED 意图，取得真实 id，再写本地余额/流水/债务，最后一次性写执行回执并转 EXECUTED、事件 APPLIED；原身份/金额不可重写。CHECK 需允许这段合法中间态，payout 不能消费未完成 effect，任何异常整事务回滚。不能只把 CHECK 放松为任意缺回执 EXECUTED。
- 若 G 选择显式分配 BIGINT effect 身份消环，则须给出实际既有 allocator、唯一域和并发/失败/原号重放合同；本轮未核实存在可直接复用的分配器，不提新平台抽象，也不自行选一个。

必要负控见 `negative-controls.sql` 的 S01：空扣款回执及 PENDING effect；另需真实 MySQL 服务正控验证余额不足记债、同会员多头、插入回执失败及提交回包丢失后的同号读回。**这些用例均未运行。**

## A-G-S02：单列 FK 没有证明同 close，原 effect 甚至没有 head 存在约束

静态抽取候选共 11 条 FK，全部单列。canonical 原 adjustment DDL 没有 `settlement_id` 的 head FK，候选 ALTER 也没有补。因此以下图在候选的关系约束中没有被排除：

| 精确结构反例 | 当前约束为何不拦 | 最终必须拒绝的关系 |
| --- | --- | --- |
| event E_A.close=A；ACCOUNTING effect 的 event=E_A，settlement_id=根本不存在的 H_missing，claim=NULL、effect_key=CASH_HEAD、非零额且执行数字0 | FK只验 E_A 存在；CHECK只验 effect 本行形状；没有到 head 的 FK | 每个 effect 必有真实原/交付头，且属于 E_A 的 close |
| E_A.close=A；effect 指向真实 PLATFORM H_B.close=B，其他 ACCOUNTING 字段均合法 | E_A 存在、H_B即使存在也未比较 close；唯一键只防同三元重复，不证明合法归属 | event.close = target head.close，不能让 A 的退款改 B 的经济头 |
| 补充交付头 H_A.close=A/topic=A，source_cash_adjustment_id 指合法 SUPPLEMENTAL effect S_B（属于 close B） | head.close FK和source effect FK各自成功；delivery CHECK只验头本行 line_type/member/amount | delivery.close = source effect.close = 原经济头.close；原真实受款人及执行种类须一致 |
| contribution C_A.close=A/source_topic=A，但 admission_event_id=E_B（close B） | 两个独立 FK都成功；CLASSIC/ACTIVITY CHECK不连接 close/event | contribution.close = admission event.close；其 source topic 也须和 close.topic一致 |
| DELIVERY_NET child E_A 下，parent_cash_adjustment_id 指 close B 的 RECOVERY | parent FK只验 id 存在；effect_key 的文本相等不证明同事件/close或 parent 执行种类 | child.event/close与parent一致，parent须为同一原经济负调整，target是真未付交付头 |
| 条件 allocation：contribution属于 A，settlement属于 B | 两个单列 FK只验各自存在 | 分配 source/head同 close、只允许原全局头；不是 merchant 或 delivery |

这里区分两件事：G 文档**已经要求**若干关系由 application 核验（尤其 allocation 和 plan 中 head/claim），本审查不把“未写复合 FK”直接等同于已实现服务有绕过漏洞。但提案尚无 writer/consumer 实现，当前 FK/唯一键本身不足以作为这些合同的证明。root 批准结构前应选定确切执行层；每个上述反例必须有真实 MySQL 命令负控，证明没有事件 APPLIED、净应付、余额/债务或交付头效果。

技术建议：给新现金 effect 一个真实 cash_close_id，并用到 event 与 head 的复合引用，父 event/head需有相应唯一索引；contribution→admission event、delivery→source effect及条件 allocation同理。parent execution_kind、受款人、原 claim/detail归属及完整效果集合仍须在共同 fence下 current读回并复核，FK不能代替金额算法或 receipt覆盖证明。若保留 application-only 的某条关系，明确由哪个唯一窄入口核验、所有恢复/重放是否也进入，不能只在首次 HTTP调用验一次。

**不要机械地把 close.platform_head 和 head.cash_close 两端都改成互相要求立即非空的复合 FK。** G当前“平台头先插NULL close→插close→回绑头”的同事务引导顺序是明确的；双向立即 FK会破坏这个顺序。保留可建立的单向约束，并在不可提交的中间态之外做完整绑定终态检查，不造假平台头/会员。

`negative-controls.sql` 给出孤立 head、跨 close accounting effect、跨 close supplemental delivery 三个独立负控的精确 INSERT，需 root/G 在正式批准后的隔离库 fixture上执行。这里只准备，没有运行，也不把静态推导“预期可写”称为真库已证。

## A-G-S03：零现金终结后的迟到事实，不能被正额 close 前置条件卡成无归属

当前 `CmsTopicServiceImpl.settleTopic:1259` 在 S=0、合同为真无义务/NONE或没有有效合同时可合法终结，无 PLATFORM 原现金头；`TopicSettlementCashBasis.fromLockedRows:24–27` 已明确拒绝 pay1/PAYING，**因此正常 PAYING 正在途的反例不成立，不能据此报告当前资损或绕过**。也不以单次 NOT_FOUND、租约到期证明此前未提交。

结构上的待补是：G cash_close仅表示原**正额**终结，DDL `:44–45` 要 S>0、contribution_count>0，platform_head_id必填；PAYMENT_CONFIRMED event又必须 FK到已有 close。若 root/B 按 59ee要求保留“原单已关闭/其他冲突本地态，之后取得完整原号外部 SUCCESS”的事实，而该 topic曾合法零现金终结，则该新事实没有现成close可绑定。不能伪造原S、初始贡献或原平台头，也不能先假 ACK、把topic重新置0当恢复。

需 root/G/B定下一个**不依赖待答分账算法**的事实保全出口：例如统一能表示真实零现金终结的原收口证据；或 B 原 verified-fact 已有按原 topic/源事实唯一身份的待归一责任记录，允许未有正额 close 时先进入明确的调查/恢复路径，之后才绑定合法经济上下文。若选择暂未绑定 close 的 PENDING事件，不能只放开 nullable FK：MySQL含NULL的 UNIQUE(close,eventType,sourceId)不能承担该阶段业务去重，必须另有确定的非NULL原topic/源事实唯一身份，并限制其不可标APPLIED/不可产生经济效果。

本项是**协议覆盖缺口**，不是已经证明普通当前付款可以越过pay1拒结。闭单/原号SUCCESS事实的具体可达供应商时序由 B 核实；原支付证据缺失不能猜。零额本地首次领取与外部既成成功要分开，不能为了排除本项拒绝已经发生的外部事实。

## 当前锁序接线核对：尚未成组接入，不是 G5e280 已修项

| 当前root66e9精确入口 | 仍有的实际首锁/路径 | 编码时的具体要求 |
| --- | --- | --- |
| `WxPaymentCallbackMutationService.apply:25–35` | inbox后 `lockBusinessTarget` 仍先锁 registration；A completePaymentSuccess:2308才CAS | topic与父单有序锁必须在这个目标锁之前接，不能在complete内部补topic而形成reg→topic回边；不反向锁inbox |
| `RefundServiceImpl.reviewRefund` → `loadRegistration/lockRefundRegistration` | 报名/父单先锁，外层 TeamGroup还可能持库存/权益/活动锁 | 审核/持久退款归一意图要同事务，最外层先fence；不能新增head/topic锁藏在已有reg锁之后 |
| `TeamGroup.cancelTopicByHost:429–442` | 先做topic票退款（可能已锁member），后进入club/series/activity包装 | 59ee已要求在任何报名/member效果前确定并预锁全部包装资源；不能只让G文字引用“既定顺序”而忽略这里实际分两段取锁 |
| `ClubEventOps.cancelOccurrence:446–464` / `cancelActivityWithClubWrapUp:580起` | series或standalone activity→club→occurrence，再入TeamGroup；包装分支内部顺序还不同 | 共同topic fence前移到最外层首锁之前；统一包装顺序且保留原权限复验。未验证完整锁图无环，不能把一条回滚重试当无死锁证明 |
| `CmsTopicServiceImpl.payoutOneDueCoopSettlement:1789` | 仍head首锁，`:1798`普通SUM adjustment | G未来补topic时必须移到head首锁前，且归一覆盖/effect集用current证据；不能只是head后补topic |
| `CoopSettlementClaimServiceImpl.appendExecutedAdjustment:59` | 仍原detail首锁，void回执 | 新归一/恢复消费者需先topic/源报名/退款/头，再detail，返回确定首次执行/同额已执行回执 |

G新增当前现金查询确实对报名基础行排序并禁缓存、包含已删活动壳，pay1拒结也存在；这些有益改动不等同于上述外层已接共同fence。activity现金查询使用 `a JOIN r … FOR UPDATE`，具体锁取得顺序还受真实执行计划影响，不把 SQL 的 `ORDER BY r.id` 声称成所有表锁的统一全局顺序。需用真实 EXPLAIN/双连接barrier回归检验，不凭VM静态分析猜MySQL先锁哪张表。

## 验证、CI状态与余项

- 实际只运行 Git/Python/rg：源身份核对、候选FK边抽取（11个单列、0个same-close复合、原/candidate无effect→head FK）、diff/交付扫描。输出 `evidence.json`。没有执行候选SQL、DDL、Java/MySQL或新CI。
- root回读通知单列：66e9 merchant15为14pass/1failure/0error/skip、CLEAN，唯一失败为新RR用例断言Long/Integer类型；root认领1L→1最小修正，本A未动IT。funds94尚在运行，本报告没有验证/宣称其通过。原66e9失败回执保留，不拼接异源结果。
- de31 verify_static的最终protected整文件断言绑定6788；root集成G后不再满足这个前提。root已独立核对其实际父及A9文件原blob；A未改脚本压错，也不把该失败改写成所有业务测试失败。
- 仍需root/G确认：S01创建时序、S02关系约束/唯一writer负控、S03零收口事实保全入口及完整外层锁序。均不选择退款分账算法/多人固定费，不创建新资金来源，不以拒绝合法退款代替恢复闭环。
- 本批是独立审查交付，无生产修改/数据迁移；回退只撤本A目录，不删任何业务、资金或原回执。正式schema和Java实现由root/G/B/A按既定所有权协调后另立候选。
