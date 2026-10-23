# G：WP04 当前现金事实与零销售闸门候选

结论：本批实现 H-G02 零销售冻结合同/退款绕过的收口，以及 H-G03 中普通 RR 聚合、活动资金准入与当前报名证据混读的局部修复；同时修复 F 指出的锁读一级缓存问题。**未完成完整金融验收，H-G01 结后退款和 H-G03 迟到付款共同收口仍阻断；H-G04 平台行真实 DDL 不相容也未解除。** 没有把这三个阻断改为新业务问题，也没有猜退款净额、收款人或多人固定费。

## 候选身份与边界

- 公共仓库 `https://github.com/liboyang42-cpu/hengyin.git`，参考基线 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`。
- 开工金融工作树 `/workspace/hengyin-g-finance-p1`，分支 `codex/architecture-day-g-finance-p1`，开工干净，HEAD 为 `65780b036d9e44e3ab8705bb8c244558a09213af`。
- 编码前仅在该独占工作树快进到已独立发布的 fixture 候选 `a42172dc6e1565fa06b81c294de137c74f5fd979`，本批 commit 的 parent 为 a421。**应用本批增量，不整树覆盖 root 集成树。** a421 与调度候选 `27a1856019bf698196c12d9f40c6a1c4dc8a990e` 保持独立冻结；未混入本批未完成风险修复。
- 实际读取 H 完整公开审查 `9de37f77b4d19389f7dadf6c6a88da4eece09e1b` 的 `docs/delivery/architecture-day/H/g-wp04/REVIEW.md` 和两份回归 patch。没有把旧 86242095 未发布身份当依据。
- 生产范围仅 `CmsTopicServiceImpl` 结算入口/组合及新增 helper、`CoopSettlementEvidenceMapper.java/.xml` 和新增窄 DTO；源码/测试完整清单见 source-hashes.json。`payoutOneDueCoopSettlement`、Claim persist/adjustment、A 的退款/付款代码均未修改。
- B 的 `resolvePendingDepositPaymentForSettlement` 方法原文保持 parent 字节；051c 首批 7 文件冻结。root 的旧 SQL IT env/UUID 清理修复不在本地旧 parent，但本批没有修改该 IT，cherry-pick 本 delta 不会覆盖它。

## 失败机制与最小反例（新增测试尚未运行）

1. H-G03：旧 `settleTopic` 普通 SUM、活动 COUNT 可以来自早先 RR/read view 或 MyBatis cache；之后 `prepare` 的 FOR UPDATE 会看到另一组已提交状态。未核销活动票在 eligible 前即被筛掉，不能依靠 context guard 补救现金准入。先写回归：旧参数 999、锁读集合 10，所有应付仍须守恒于 10；另一个事务提交活动已付但未核销，当前事实必须拒结。
2. H-G02：旧 totalSales=0 时直接退保证金并成功终结，绕开冻结合同、窗与退款证据。先写零现金缺条款及固定费正义务负控；保留真无合同零销售与合法 NONE 的正控。
3. F：FOR UPDATE 本身不保证同 SqlSession 重复调用真正执行 SQL。新测试在同事务 JDBC 写入后重复调用 Mapper，要求报名状态与退款名单实际刷新。

## 本批修复合同

- 新 `selectTopicCashRowsForUpdate` 锁 owner_type=1/owner_id=topic 的全部报名；`selectTopicActivityCashRowsForUpdate` 通过 `cms_activity.id=registration.owner_id AND activity.topic_id=topic` 当前锁读活动及报名，**包含已删壳**。分别查询避免把 topic/activity 数字 ID 当关联，也避免合并 OR 的宽扫描形式；真实执行计划和锁范围仍需 MySQL 检查。
- 同事务持锁集合经 `TopicSettlementCashBasis.fromLockedRows` 一次归一：现金保留旧 paid=2/registration=2 SUM；审计保留旧 paid=2/verification=1 SUM、distinct member 与有序报名 ID。不把审计核销提升为商家履约资格；商家原额仍来自真实冻结 prepare/origin。
- 未核销的已付活动票同样拒结；payment_status=1 的未决确认拒结；null/重复/跨归属行或应计金额缺失/负额拒结。不改比例、固定费或人数计量。没有将退款已批准当原始票款可继续分配。
- 旧普通 SUM 仅留在缺产品类型/正金额非 coop 的**早拒绝**，不决定任何应付计算或零销售成功。合法现金与零销售终态均重新读当前资金集合。包内组合测试的旧金额参数已不决定金额。
- 锁报名后当前读 pending/approved 退款；零销售也执行。退款历史净额不明仍报 `REFUND_NET_BASIS_UNPROVEN`。
- 零现金有有效合作单时，逐单实际 prepare，检查 topic/initiator 冻结身份；只让合法 NONE 计划持久其原身份后请求退保证金。非 NONE 的义务继续 `ZERO_CASH_COMPENSATION_UNPROVEN`，不能悄悄消费合作单。真无合同零资金主题保留收口。
- 5 个 FOR UPDATE 语句显式 `useCache=false/flushCache=true`（两项原履约选集、两项新现金集合、退款阻断）。旧普通活动 COUNT 仅作展示/历史测试，不再参与结算准入。
- 拒绝路径发生在头/claim/积分/退保证金意图之前；CAS 仍在原每主题事务，真实回滚须由 CI 证明。没有新 HTTP 接口、权限变动、依赖、生产迁移或资金写入。

## 调用者、夹具与验证范围

生产调用链仍为 Quartz → executeTopicSettlement 每主题事务 → settleTopic CAS → 当前现金/退款 → 冻结计划与守恒 → 原持久账/积分/保证金退款意图。`settleCoopTopic(CmsTopic,BigDecimal)` 的全仓其余调用者仅为包内组合测试；生产调用新 private helper 并传已经锁定的同一 basis。

原组合测试保持全部测试名和经济断言，只把显式金额输入送入 Mapper 夹具。`PreparedCoopPlanFixture` 仍是组合测试的计划/数据替身：其审计行只重建旧 stub 的 SUM/COUNT，不声称 CLUB/团或该夹具是实际合法订单链。a421 的两个正向 fixture 保留非零金额、仅 coop ledger、canonical claim、重复零效果、原 activity 幂等对象断言；补入同一原报名的现金投影与新增查询次数。

本批新增 10 unit 方法（CashBasis 4，入口保护新增 6）；新独立 `Wp04CurrentCashBasisMySqlIT` 4 方法：RR 旧总额/当前金额、含删除壳的活动付款、同 SqlSession 两种缓存刷新、报名状态写入锁竞争。使用真实仓库 DDL、MyBatis 和 MySQL 8；必须显式 HOST/PORT/USER/PASSWORD/DB_SUFFIX、只许回环、随机 UUID 库，AfterAll 仅删除已记录且 catalog 匹配的自身库并读回清理结果。DriverManagerDataSource 无池，不会清理他人库。

**实际执行**仅轻量静态检查，见 validation-output.txt：scope、5 锁读 flag/XML、B 方法、051 冻结、旧 SQL IT 未改、原测试数量与断言保护、源指纹、diff 与 secret-file gate。没有执行 Maven、Java、MySQL、Redis、H2、真实付款/退款、CI dispatch 或生产操作。新 10 unit/4 SQL、原相关回归、真实服务现金入账和锁序均 NOT_RUN。

root 已报告旧 G657/root8304 原 7 证据 SQL 通过，另有后续 unit 对应范围；这些结果不覆盖本批新源。本批的任何静态 PASS 不能替代真实金融运行。

## 尚未关闭的阻断及回退

详见 compensation-wiring.md：需 A 协调付款确认/退款审核的共同 fence、完整原始归属与付后追收；本批锁读只能覆盖当前已有报名，不会为结算提交后的迟到 SUCCESS 自动造补偿。payout 仍缺退款/调整同步，因此不能正式集成/部署。平台行 schema 候选仅写成 schema-plan.md，不执行 DDL，不填假会员、不关 strict mode；不能先修 schema 放行后忽略退款缺口。

唯一用户待答产品问题仍是多人票固定费计量；qty!=1 继续拒结。其余列为实现/验证阻断，不请求用户从抽象架构中重选既有经济身份。

无资金落库前可回退本批增量至 a421；一旦有实际头、claim、余额、退款意图，不得删账、回写原额或释放 canonical key。schema 的 NOT NULL 回退在存在合法空受款对账行时也不可执行。
