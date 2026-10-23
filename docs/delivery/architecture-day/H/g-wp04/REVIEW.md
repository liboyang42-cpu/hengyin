# H：G WP04 金融写侧独立审查

结论：**不能通过完整金融写侧验收**。确认退款调整闭环的既有 P1 仍未闭（H-G01，现已给出当前允许路径的具体反例）；另有现金基数跨 RR/当前读的 P1（H-G03）、零销售绕过冻结合同的 P2（H-G02）、正向 PLATFORM 行与真实 DDL 不相容的 P2（H-G04）。均为源码机制裁决，尚无 H Java/MySQL 动态复现，不把预期失败写成已跑红测。

既有 CI `40023300267` 在审查期间已完成。H 读回原始 artifact、JUnit XML、Maven 日志和运行回执：**7 SQL 用例实际通过，failure/error/skip 均为 0**。它们只运行证据 Mapper，没有运行 reader→prepare→persist→主题五分项→T+7→余额的正向链，不能据此宣布金融功能完成。新增 21+6=27 个单测本轮仍未获得执行证据。

## 身份、范围与隔离

- 仅公共仓库 `liboyang42-cpu/hengyin`，参考基线 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`。
- G 候选 `65780b036d9e44e3ab8705bb8c244558a09213af`；tree `ca907c001d9ded38ef9de3dbad0fc02cdd19d171`；parent `051c1c108653eeb1cbc4f3b69f8ae25728c99e34`；分支 `codex/architecture-day-g-fact-contracts`。
- 相对公共基线 49 文件，相对 parent 28 文件；从公共基线和公共 diff/缺失大文件原文重建，49/49 Git blob 及完整 tree 严格匹配。WP04 manifest 的 22 源/测试文件 SHA256 另行核对。
- H 开工 HEAD `5b416fbcb738c913b774d91dc399fa0e0cdf2fee`，分支 `codex/architecture-day-h-review`，工作树 `/workspace/hengyin-h-review`，开工干净。源码只读重建目录 `/tmp/h-g-review/tree`，独占交付仅本目录。没有修改 G 实现、共享 Mapper/schema/CI 或别人的交付目录，也没有新增 Agent。
- root CI source 为 `8304e74de6393bd3d650d04e290e47aef2e8ff5d`。13/13 G 生产文件（其中 WP04 的 10 文件）逐 blob 相同；49 文件中 48 个相同，唯一差异是 SQL IT 的显式 env 校验与自身随机库清理，7 个业务用例正文不变。完整清单/绑定见 `candidate-binding.json` 与 `source-identity.json`。
- B 的 `resolvePendingDepositPaymentForSettlement` 与公共基线逐方法原文及 G 指纹一致；本轮未重审旧 B218，也未擅自改该方法。退款服务、报名付款回调、DIRECT 入口、结算/claim/detail Mapper、base_schema 与 MyBatis 配置在 G 和 root 中均保持公共基线，不能假设 root 已补下列缺口。

下文行号均针对 **G657 / root8304 的同字节源码**。主要文件全路径为 `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/` 下相应类；Mapper XML 在 `chengyinhub-system/src/main/resources/mapper/business/`。未套用旧报告行号。

## H-G04 / P2：正向 PLATFORM 头不能写入公共真实 schema

位置：`CmsTopicServiceImpl.java:1451` 固定把 PLATFORM 行的 payee 设为 null；`:1620–1624` 的真实 `newSettlementLine` 将其放入 merchantId；`CoopSettlementMapper.xml:56–69` 在 merchantId 为 null 时省略 merchant_id 列。`sql/base_schema_20260818.sql:1988` 则为 `merchant_id bigint NOT NULL`，没有 DEFAULT。原 `migration_coop_settlement.sql` 同样要求 NOT NULL；`migration_coop_settlement_line.sql:10` 只放宽 coop_order_id，没有放宽 merchant_id。全仓迁移搜索未找到放宽该列的既有方案。

触发：冻结 reader/prepare 正控都合法、五分项守恒通过之后，第二遍先插 PLATFORM 行（`:1475` 附近），严格 MySQL 模式会因省略无默认值的必填列而失败。当前受控 CI 的 sql_mode 含 STRICT_TRANS_TABLES；按公共 DDL 推导为错误 1364，而不是成功挂账。只要仍处于既定每主题事务，异常应回滚 CAS 和全部写入；该回滚本轮没有真实验证。

这是存量平台行/schema 不匹配在恢复真实 reader 后暴露的正向阻断，不是已观察到的资金双付。G 报告“无新增 schema”不能代替这个读写相容性证明。现有 7 SQL 夹具只抽 8 张证据表，根本没有 coop_settlement，旧组合单测又 mock insert 返回 1，因此均覆盖不到这里。

最小处理：先明确平台对账行在现有数据模型中的非个人受款表示，完成 service/Mapper/schema 的最小一致方案。若需列可空，提交仅适用平台行的预检、约束、迁移和回退方案；不得为了变绿填默认会员、伪造平台用户、放松 strict mode 或直接改基线 DDL。H 未执行 DDL。

回归补丁 `sql-cash-guard-and-platform.review.patch` 中 `realPlatformSettlementLineMustInsertAndReadBackAgainstRepositorySchema` 调用真实 helper、真实 Mapper、抽取真实 DDL，要求正额平台头插入后实际读回 type/status/amount；不 mock 插入、不改 DDL。仅覆盖该头，不冒充完整现金链。**已检查补丁可应用，未编译/运行。**

## H-G01 / P1：挂账后的退款未进入新账本，T+7 仍可按原基数入余额

位置：`CoopSettlementClaimServiceImpl.java:100–102` 只在 prepare 时调用退款阻断；`TopicSettlementEvidenceService.java:84` 附近只查当前未决/已批准退款。持久后，`CmsTopicServiceImpl.java:1731–1742` 的 payout 只锁待付头、检查客诉、加已执行调整；`:1759` 加余额，`:1776–1777` 写流水和 paid。没有再次查该主题/原报名的退款状态，也没有证明相应调整已经落地。

逆向调用核对很关键：**已核销 GUIDED_TICKET 不能作为正常退款反例**。`RefundPolicy.verifiedVerdict:132–139` 和 `RefundServiceImpl.ensureRefundable:858–875` 对它硬拒，不允许把 EXPLORE_PASS 的人工放行规则套过来。

当前真实允许的反例使用计入全主题总销售额的另一张**未核销**票：

1. 同主题有 R1（GUIDED，已付10、已核销且商家扫码，固定费2）和 R2（GUIDED，已付90、未核销）。票窗均已结束，主题及冻结条款合法。旧总销售额 SQL `CmsRegistrationMapper.xml:669` 计入两者100，而 merchant origin 只含 R1。
2. 以示例冻结平台率10%、保底7%计，merchant=2，平台=10，发起人保底7+毛利81=88，挂到待付头；这些数只是展示原额如何形成，不制定退款分摊政策。
3. 挂账后，对未核销 R2 建后台退款单并由另一审核人批准。`reviewRefund:627` 的 ensureRefundable 对未核销返回允许；`:642` 改报名退款态；`:646–670` 只找/冲销旧 settlement_detail。TOPIC 支付入口 `SettlementEntryServiceImpl:60–67` 本来就不记 DIRECT，R2 又未核销，故该旧明细可以为空。新 coop_settlement_detail/adjustment 不受这条 voidPendingBySource 影响。
4. 全仓 main 中 `appendExecutedAdjustment` 仅有接口和定义，零生产调用者；没有原退款事件到新 coop 头/发起人现金基数的调整消费者。到期无客诉、调整和仍为0时，原发起人88仍可进入余额，而R2的90已经成为退款负债/可能实际退付。仅针对 R1 的 merchant claim 追加事件，也补不上 R2 对发起人/平台原总额的影响。

G 报告已把“退款调整消费者未接通”列为技术阻断；本项是其当前路径的具体化，不冒充新发现的第二个独立资损问题。H-G04 会先挡住公共 DDL 下的完整新挂账；**修通 H-G04、实际 schema 已允许平台行或已有待付头时，本项依然存在**，不能因一个 schema 错误暂时挡路就验收退款闭环。

最小处理：在产生和支付新现金头之前保持受影响路径 fail-closed；打款前须在兼容退款写入的锁/事务 fence 下证明无未归一退款，或证明原退款事件已一次性反映到所有相关头的净额。接通未核销销售基数、merchant origin、发起人/平台头的原身份调整与实付后扣回/债务，不覆盖原始头、释放 claim 或用日志假装完成。`appendExecutedAdjustment` 目前只锁原 detail，而 payout 锁 head；即使接上调用者，也须统一锁序，不能让调整在 payout 已建立的 RR 快照之外并发提交后仍按旧额发放。

必须受控回归：上述 R1/R2 完整挂账→真实退款审核→payout；核对每个原头/调整、退款意图、balance、balance_detail、paid、债务。加入退款与 payout 两种先后顺序及并发、重复事件、不足余额、失败注入与恢复。不得只写“mock 调整额为负”的正控。H 未编造或运行此完整链路。

## H-G03 / P1：普通 RR 现金基数与 FOR UPDATE 当前证据不属于同一事实集合

位置：`CmsTopicServiceImpl.java:1238/1244` 先普通读销售总额/活动资金；`:1499` 的 `countPaidActivityRegistrations` 调用的是 `CoopSettlementEvidenceMapper.xml:89–93` 普通 SELECT。随后 `prepare→lockCandidates` 使用同 XML `:75–87` 的 FOR UPDATE 当前读。`CoopSettlementContract.eligible` 先跳过 verificationStatus!=1，再检查经济 context，所以新变为已付但未核销的活动票不会触发 ACTIVITY context 拒结。

可调度机制（本轮 CI 回执明确是 REPEATABLE-READ）：T1 在结算事务读活动已付数0并建立 RR read view；T2 将该主题活动壳中现存待付报名提交为已付；T1 的 FOR UPDATE 读到 paymentStatus=2、verificationStatus=0，但后续普通计数仍可能读0（同 SqlSession 还可能命中首次0的本地缓存）。验证活动父关系本身正确也无法修复这个差异。于是已经存在的“活动有已付资金须拒绝经典主题收口”事实被漏掉。

相关付款调用没有共同 topic CAS fence：`CmsRegistrationServiceImpl.completePaymentSuccess:2303–2328` 先 CAS 报名付款态，调用 recordOnPayment；TOPIC 路直接返回，活动路也没有先和结算领取同一个主题锁来冻结最终选集。主题总销售额的普通读与后续报名当前读同样可能跨付款提交；未核销的新 TOPIC 付款可被 origin 筛掉，同时旧总额被当成最终现金基数。现有主题 is_settlement CAS 只防两个结算者，不约束付款事实写入。

最小处理：将现金总额、含已删壳的活动资金阻断及履约选集建立在同一组锁定的当前资金事实，并与付款确认/迟到 SUCCESS 使用同一收口协议。仅加 `flushCache=true` 不能消除 RR read view；仅重复 COUNT 也不够。外部付款已成功的事实不能因本地已结算被丢弃或换号，必须由原身份补记/调整或在最终收口前查证未决付款。具体接线需和付款 owner 协调，不由 H 修改其保留文件。

`sql-cash-guard-and-platform.review.patch` 中 `cashBasisGuardCannotRemainEmptyAfterCurrentEvidenceSeesCommittedActivityCash` 用真实 Mapper/事务和另一线程独立提交：先观测0，后当前锁读已付票，要求 guard 与当前事实一致。它隔离当前缺口，**不等同于完整付款回调或结算并发验收；未编译/运行**。还必须补完整服务/真实余额回读，不能把该 Mapper 用例一绿当作跨域锁协议完成。

## H-G02 / P2：零销售快捷收口绕过冻结合同和退款拒结闸门

位置：`CmsTopicServiceImpl.java:1243–1250` 已取得 effectiveOrders，但 totalSales 为 null/0/负时只检查活动已付数，就调用退保证金并返回 true，CAS 的 is_settlement=1 将随事务提交。未走真实 reader、prepare、退款阻断或 NONE/正现金合同区分。

具体反例：有效的 TOPIC/SUPPLY 固定费2合同，存在已成功零元付款且已扫码的 qty=1 GUIDED 票（`completeZeroAmountPayment` 是真实入口），主题总额仍为0；或者合同/退款事实尚待修复且旧总额为0。新 prepare 若执行会核对原义务/缺证并拒结或触发不守恒，快捷分支却把“缺现金基数/未证明义务”收成成功并请求退保证金。这里不决定零价票应由谁补贴，也不主张直接支付固定费，要求的是不能悄悄消耗未处理义务的终态身份。

最小处理：保留真正无合同、无资金/义务的原无销售收口；有效合作合同必须先证明为可零额终结的 NONE/零义务且无阻断退款，否则拒结并回滚 CAS，不退保证金。不能把全部零销售主题一律改规则。

`zero-sales-frozen-obligation.review.patch` 是现有真实 service 分支的最小负控，要求未解决正固定义务不被快捷分支吞掉、零应付/退款请求；仅 mock 数据边界，不声称证明 CAS 回滚。**可应用检查通过，Java 未运行。**

## 已核对的正确合同和写入边界

| 环节 | 源码结论 | 验证级别 |
| --- | --- | --- |
| reader 的冻结归属 | 只有一个运行时 reader bean；Unavailable 不再注册。要求有效未删 v1 order、完整 lineup，无 repairReasons；toId/toType/role/scope/fromId 对上原条目，fromId 再对主题发起人；JSON mode/rate/fee 与标量相等，无 live shareMode fallback | 源码；27 单测未执行 |
| 范围与票窗 | 只开放 ORIENTEERING + TOPIC/TOPIC_EDITION + MERCHANT_PARTICIPATION + GUIDED + REGISTRATION_SCAN。NODE/ACTIVITY/CLUB、缺开售标记/费参数/票窗继续拒结；票需真正同 owner/type/subtype，start<=evidence<end，整目录窗结束 | reader/contract 源码；部分实际 SQL 见下 |
| 计量与金额 | FIXED 只 qty==1；null/0/2/3 不填默认。比例沿用实际 payable snapshot 和最大余数分配；distinct registration，不乘人数猜固定费。缺/负金额拒结 | 源码；多人固定费业务 pending 不替用户决定 |
| SQL 归属 | activity IDs 从实际父外键解析；NODE 真父关系；扫描 type=1/status!=2、商家和时间窗；团证据同 topic/activity/order/merchant、两段时间匹配；退款按 source_id→报名而非冗余 owner | 7 真实 MySQL 用例通过；不覆盖跨事务混读/收口 |
| claim 与原始账 | 七列经济 key 不含 route/source，跨 route/source 冲突；persist 在同事务 claim→detail→由明细刷新 header。NONE 不造现金头；固定 claim/detail 业务身份不因 TTL 释放。重复头与 claim/detail 失败都抛出 | 源码；没有本轮真实并发/回滚/正向资金读回 |
| 主题/付款原子性 | task→每主题 TransactionTemplate→CAS→冻结计划→守恒→挂账→积分；异常越过事务边界。payout 每行 REQUIRES_NEW，head 锁→member 锁→余额→流水→paid→债务，通知在事务后 | 结构保留；本轮没有真实余额/失败注入证据。H-G01/G03/G04 不能被此结构抵消 |
| 保证金 | depositStatus=1 拒结，未在结算事务内调用 B 查单关单；claim 原额抵扣前拒绝，原金额不被覆盖。退款请求持久原意图，事务后分派，UNKNOWN 沿原号查证 | 本轮只核对结算调用边界；未重验 B 支付/供应商闭环 |

调用搜索覆盖 `SettlementTask:49`、`CoopSettlementPayoutTask:29`、CmsTopic 唯一生产 prepare 调用、真实 reader/lineup、Claim/Detail/Adjustment、付款成功回调、DIRECT 入口、退款审核与反向策略。旧五分项组合测试改用 `PreparedCoopPlanFixture` 的显式计划替身；其 CLUB/团/金额 mock 正控不能证明实际运行时已支持这些路径。H 未修改它们，也未把注释或 mock insert=1 当现金成功。

## 真实 CI、H 实际检查及未验证项

[run40023300267 / attempt1](https://github.com/liboyang42-cpu/hengyin/actions/runs/40023300267)，job `120653288074`，artifact `12530933640`。H 下载并解析原始 XML 的7个 testcase，回执 source/workflow/precheck 均为8304，MySQL `8.0.45`、RR、strict mode，cleanup=CLEAN；没有 H2。详见 `ci-readback.json` 的 zip/每文件 SHA256、逐测试名、版本/隔离/运行身份。登记 source 为 `be00cb35689ef76bf7c06489bf23de10391f032e` 且 registered_source_match=false；本轮以独立 blob 对照绑定实际8304，不用 PASS 抹去登记差异。

实际执行的7例分别验证：活动真父关系、null/空活动集合、节点真父、活动 scope、票归属/扫码时间/撤销、团证据匹配、结算前待审/已批准退款阻断。它们没有 reader bean、claim/header/detail 或余额表，不能覆盖本报告四项机制。

H VM 只执行轻量 Python/Git：49 blob/完整 tree、22 源指纹、XML/include、B 方法原文检查，3 个新增审查用例的 patch 可应用检查；输出见 `executed-static-checks.json`。作者 verify_static 完整入口依赖本地 parent051c Git 对象，未完整运行；只复用其无副作用解析 helper 并独立核验 manifest，没有伪造 parent/HEAD。没有在云 VM 运行 Java、MySQL、Redis、后端或 DDL。

两个交付 patch 合计新增 **1 unit + 2 SQL** 测试，仅存 H 目录、未应用到实现树。若 owner 采纳，新增 unit 集合应由27增至28，证据 SQL IT 由7增至9；不得继续沿用原7例回执作为新增测试通过证据。补丁已分别核对 G657/root8304 可应用；完整源码仍须真实编译。没有编写 H2/Mock 结果冒充 MySQL。

## 同一候选裁决矩阵

| 项目 | 通过 / 失败 / 未知 |
| --- | --- |
| G657 身份、49 文件完整 tree、root生产一致 | 通过：实际重建/逐 blob |
| 7 个证据 SQL 的声明范围 | 通过：原始 XML 7/7，0 failure/error/skip |
| 冻结归属/数量边界和冻结比例源码合同 | 源码具备；27 单测未知，固定费多人业务 pending |
| 全部正向现金头→claim/detail→余额读回 | 未知且有 H-G04 静态不相容阻断；7 SQL 不构成验证 |
| 挂账后退款/调整/原额与实付债务 | 失败（源码安全合同）：H-G01既有 P1具体化；真实动态未跑 |
| 同一现金事实集合/付款并发收口 | 失败（源码安全合同）：H-G03 P1；SQL并发反例未跑 |
| 零销售正固定义务不被消费 | 失败（源码分支）：H-G02 P2；审查单测未跑 |
| PLATFORM 真实 DDL 相容 | 失败（源码/DDL）：H-G04 P2；MySQL写入反例未跑 |
| 事务回滚、双结算、claim冲突、payout失败恢复 | 未知：需真实 MySQL，不能用原 H2/组合替身代替 |
| HTTP真实身份/真机/供应商/生产 | 未验证或本轮不执行；无正式集成、合并、部署授权 |

## 集成、迁移与回退限制

G 本批未新增生产迁移，但 H-G04 表明不能把“无新 DDL”当作 schema 已适配；若选择 schema 修正，先交具体预检/约束/迁移/回退方案，由授权流程安排，H 未执行。H 的审查 patch 中 DDL 仅复用隔离 IT 自身库的真实建表片段，不触及生产或持久数据库。

当前只可继续隔离候选修正与受控验证。未真实写账前可成组回退 reader/contract/evidence/entry 到原明确拒结；已产生的 claim/detail/header、余额流水、退款意图和审计必须保留，不能因回退删账、清库、改原金额或释放唯一身份。多票固定费仍为用户待决问题，不补默认；其他上述缺口是技术修复/验证责任，不以新业务猜测放行。
