# H：RestoreMoneyDispatchTest 构造接线修补

状态：SOURCE_ONLY。编译、测试（含 child JVM）、解析控制、CI、Java/DB/外呼均 NOT_RUN；不把修补源码当成通过。

parent：fb871607e4c4a5a038776201ff0b785f999f9ab1
parent tree：b859b07a802ab3c6303e167aef4a8a21c094ef56
唯一 owned 文件：chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/RestoreMoneyDispatchTest.java
原 blob：778e1c5e2c815cf19a212e822ff612fde63297ad

实际失败：run40219238315 / SBOM job121247109763，testCompile 原167行调用不存在的 ClubEventOpsServiceImpl()。生产类只有七参/八参构造器；不为该 fixture 新增生产无参构造或 noop 默认。

源码核对：
- 已 rg 现存构造调用，并读 parent 的 ClubEventOpsServiceImpl（blob ce20e3511dcdb43376333c8218743b83c06198cd）、ClubEventOpsServiceImplTest 和 ClubOccurrenceCancellationTransactionBoundaryTest 的真实装配。
- 原取消 fixture 使用八参 owner 构造：access + 真实 ClubEventScheduleMaterializer + session service + ClubEventOpsMapper + ClubMemberMapper + ClubLeadMapper + role service + ITeamGroupService。此次复用该实际签名，不反射绕构造依赖。
- 生产 cancelOccurrence:437–450 的首句是 RestoreDispatchGuard.requireDispatchAllowed()，在 requireCancellationOutsideTransaction、请求冻结、topic/registration 锁读、capture/observe/finalphase 之前。该用例要证明恢复模式先拒绝；不是要进入取消事务。因此不虚构 reader/transaction 字段或事实，也不改真实生产 owner。

完整最小 delta：
1. import 真实 IClubAccessService。
2. adjacent() 原 ClubEvent owner 改成完整八参构造，真实 materializer + 显式 typed mocks。
3. 保留原 blocked(() -> club.cancelOccurrence(9L, "actor", null)) 和精确 Blocked/message 断言；补 verifyNoInteractions 验证该 owner 拒绝前没有 access/session/Mapper/role/team effects。

原16个 @Test、固定 child 环境、所有 guard/金额/原号/UNKNOWN/迟到SUCCESS/退款回执/调度器/恢复查询断言、超时及 child 清理代码均保留；没有删除或修改原期待值。没有改生产、其他 owner 测试或共享 fixture。此 unit 仍明确使用 Mapper/SDK doubles，不冒充 MySQL、供应商验签或回滚证据。

review.patch.b64 解码为完整源码 delta；PATCHES.sha256 是解码补丁摘要；POSTIMAGE.sha256 是唯一测试 Java 后像。源工作目录 /workspace/hengyin-h-restore-constructor-fb871 为独立公开源码补丁目录；原 H checkout 及其未交目录未回退。root 唯一集成并按影响集中验证。
