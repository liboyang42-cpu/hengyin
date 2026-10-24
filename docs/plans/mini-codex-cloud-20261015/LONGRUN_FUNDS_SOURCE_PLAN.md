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

## C00冻结实施合同：源边界与并行作者

C05-24方案归一成唯一实现C04-21，不把同源余额/冻结/ledger问题重复计数。精确9生产路径转CORE/C04，旧13必要fixture如SOURCE_OWNERS和任务列表；C05不写这些源。原intent/提现行→member→bounty→cooldown，各赏金写者凡可能碰member余额均须在bounty操作前取inviter member锁（不只payBounty末端）。真实caller未能遵守时交精确阻塞，不给生产fail-open。原普通resolve给展示保留，只提现专用current读取参与锁序。可以选原谓词的逐行FOR UPDATE再Java求和，避免未经核实的聚合锁行为；NULL金额沿原SUM忽略，不改时间/负值政策。bank加入原TX，流水取锁后真实before/after；cash短财务TX必须结束后再provider，数据库未知仍原人工路径，不能自动退。撤销/驳回保提现行→member并锁读真实before。

C05-25精确5生产路径转CORE/C05，三旧fixture单写者C05，C04不得碰同PlayerWithdrawalCooldownServiceTest；如需适配先交原writer。只资金专用key当前before锁/审计/写、提交后删除cache防回调倒序；一般配置/API/权限不变。业务小时权威读取须避免失效失败造成永久Redis旧值，选择专用DB语义并如实区分普通RR读与锁定当前读；若采用锁读需追所有caller无config→member反序/锁跨provider。默认48与非法配置原规则保留，不改任何值政策。

两个实现均仅源码和必要回归NOT_RUN。C04/C05先静态盘点实际旧fixture，无必要不改；所有旧断言保留。确需表外路径向C00给准确理由再转交，不批量改旧fixture或为mock保留生产fallback。不执行测试/编译/DB/provider/VPS，交付未验证，不提前合并/部署。

C04-21新增精确MemberCashWithdrawMapper.java/XML current行读，先cash行→member避免终态/冻结后的迟到扣款；原外部回执/拒绝保留，不provider/不扣/不自动退/不markFailed覆盖。赏金用NESTED savepoint保持外层事实可见、原失败不连累核销；源码默认Boot/MyBatis JDBC支持，但实际生产manager/nested能力未验证，不允许REQUIRED/REQUIRES_NEW错误替代。C06-30积分相同周界兄弟投影补充不另算bug；C05-25另写必要真DB审计交错回归，全部NOT_RUN。

C04-22独立接续provider迟到观察：当前源accepted成功返回无条件status0可覆盖同步终态；ambiguous/查询非终态观察也可能覆盖冻结/终态渠道事实。只新增精确id/status0/非frozen的updateReceiptIfProcessing，允许既有五渠道字段，不写status/金额/人工字段；CAS0返回当前原事实，sync停止旧快照超时升级。原终态CAS/状态政策/provider不变，旧3fixture只必要接线保留never断言。三源均已C04独占，不另抢文件。必要回归NOT_RUN，21原快照不可变。
