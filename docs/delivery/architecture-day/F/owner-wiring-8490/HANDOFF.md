# 8490必要owner接线（未运行验证）

Parent `8490765f0572dae8ec3468f4e75d0ff6f4760b12`；独立 `codex/architecture-day-f-owner-wiring-8490`，只改架构policy/原checker精确binary身份校验/配套控制源码和本目录。未改生产、workflow或registry。

- G `54c524ca99204804b6b84af517359eabc8c6ee8d`：仅新增 producer、allocation、manual cases（含实际匿名rollback callback `$1`）、manual Mapper四项窄职责；producer→原ledger/原B事实/分配/人工事实，manual→原B事实DTO/唯一存储。禁止反调原God/支付退款SDK；原ledger不反调producer。原有方向保留。
- 新表 `coop_settlement_manual_case` 唯一纯writer `CoopSettlementManualCaseMapper`，公开合同只 `CoopSettlementManualCases.requireNoOpenCase/afterRollback`；控制事实不是扣款/债务/退款审批。没有给G批准其它资金表/namespace写权。
- `$1` 是源码中真实唯一 TransactionSynchronization 匿名实现，需root实际class/JAR回读。members/allowed_consumers只额外接受本次明确登记的这一精确binary名字，不按outer前缀授权；其索引或raw缺失仍UNKNOWN。原三private Mapper端口的exact consumer合同未改。原负控保留，新增 `$2/$1$Nested/外来类` 禁止源码。
- D `379d5615608663c47302598ba238447b264c2bba`：新增不可变 `NpcAsrQuotaHandoff` 精确技术类型；E quota owner仅向handoff/原ASR Mapper，ASR唯一writer namespace不变。登记原 `requireNpcAsrHandoff/refundNpcAsrNotSent/runDue` 原proof/pending合同及 `NpcChatWriteService.admitShop`，不授ASR service直接写quota Mapper。新Mapper方法仍受原scope/cache/owner规则。
- C `f61b8ea8c829d0b4e050a8b61ac592ae819a8a09`：无新增production边界，沿原voice owner/Mapper，命令补history；原enroll/status重载不重复登记。SQL身份详见sql-identities.json，仅源身份，不是baseline/豁免。
- B“三文件”未获精确路径/最终公开body：没有猜测放行或修改原两个恢复SQL绑定。请root给出三文件路径/公开SHA；若恢复XML变动，需其最终blob以精确同步原指纹。

保留原baseline/locking proposal/producer、3 private Mappers及原两个B恢复语句绑定。六可信文件SHA见trusted-files.json。只新增7项配套正负控制源码并同步已批准fact汇总断言，未删旧场景；按本轮指令，Python测试/Node测试/编译/Java/DB/jdeps/source gate/CI均NOT_RUN，无验收通过声明。root最终串行应用第二提交的工具pin/双ref review.patch，统一审查与回归。
