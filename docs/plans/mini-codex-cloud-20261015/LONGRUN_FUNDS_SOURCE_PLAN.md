# 既有资金读取不变量：先方案后源码

CY-HANDOFF CODEX；C00独占协调。公共基线05d307ec0466988f212187666d7f1e5c3274f434。所有发现来自源码调用链/交错推导，NOT_RUN，不是现场复现；不改收费、冻结小时、权限、审核、退款比例/渠道能力或生产。

## 可以独立交付的三项最小既有合同修复

- CORE/C02-27：RefundController.add→saveBackOfficeRefund→saveRefund正数后入DECIMAL(18,2)，0.001会舍到0.00，批准后Payout对非正额直接return。仅入库前沿现有provider UNNECESSARY整数分精度拒绝，不round，不改审核/幂等/上限。专属unit证明service到mapper边界，不冒称完整controller/真库。
- CORE/C06-30：真实余额统计API调用selectWeekBalanceIncome，周日DAYOFWEEK-2为-1，窗口跳未来周一。两处偏移换WEEKDAY，保持原周一周界及scope/type/del过滤；新真MySQL时间fixture只写不执行，不改主机时间。
- CORE/C01-51：BankCommand外层事务先普通consent查询可建立RR旧快照；同请求consume CAS输家普通select读旧state1，错过原CONSUMED幂等回放。仅失败分支新增id+member FOR UPDATE，原owner/action/request/fingerprint/expiry及同意顺序不改，confirm/reject读不顺手扩。真实RR用例需隔离任务schema，gate开但缺配置应失败，禁止assume静默当成功，不接任意既有库。

精确source和唯一测试归属已在SOURCE_OWNERS；原C00写者停止，三worker可写小提交/不可变快照，SOURCE_DELIVERED_UNVERIFIED/NOT_RUN，不等验证。

## 两项共用架构DRAFT（当前不授源写权）

C05-24：card/cash提前读balance与frozen常量后，并发合法补偿/赏金入账再扣款，可能消耗新冻结资金，ledger前后值也可能失真。先做统一事务/锁序/current-read方案，不直接在resolver装锁。bank需保intent/提现行→member，不能把member提前造成withdrawal行反序；cash provider必须在短财务事务以外。ReferralBounty可能bounty行→member，与member→冻结行有反序；须追真实producer事务，解决完整锁序。RR外层旧readview不因锁member而刷新普通SUM。没有安全最小方案先保持DRAFT，不猜改资金时间政策。

C05-25：rewrite冷却时长从Redis读before，两个并发管理员可能均记48→不同新值；现承诺准确前/后审计。专用配置出口若提交前写cache，还会露未提交值。拟仅专用配置行锁当前before、同事务审计+更新、提交后cache；需先给精确SysConfig出口/mapper和旧fixture影响，不改变一般配置、权限或任何时长值。

两DRAFT原source仍C00，C05/C04仅源码阅读给方案，不写、不测试。后续冻结合同和精确转交另发，不把DRAFT称SOURCE_READY或TEST_DEPLOYED。两项不可被三个小修的交付自动关闭。

C01-51静态盘点仅需旧 FundActionPreflightServiceImplTest.java 的 exactConsumedIntentCanReplayButChangedPayloadCannotReuseIt 单stub接新增owned当前读；原等价成功/不同intent BINDING_MISMATCH断言保留。已精确转交，其他旧fixture不动，无手写Mapper实现需适配。
