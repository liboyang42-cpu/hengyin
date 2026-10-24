# 保证金查证窗口：锁外SDK、锁内原身份CAS（未运行源码候选）

Parent `0cb8115ee34e4647a76c76bda608fae62bbd51e1`；独立工作树/分支 `codex/architecture-day-g-deposit-proof-window-20261024`。并非唯一开发者，保留root/G/A/B/D并行改动。`caller.review.patch` 只含两个生产文件的精确hunk；其余是既有测试必要装配。ledger、B SDK/接口、A回调、D内容/发布、CI/schema均未改。

## 实际调用链与改动

1. `SettlementTask.run` → `CmsTopicServiceImpl.executeTopicSettlement`。原来private `resolvePendingDepositPaymentForSettlement` 的SDK query/close在调用者持锁时执行，且当前主结算 `applyUnpaidDepositSetoffs` 已拒绝status1，缺正常恢复入口。现在每主题结算Tx之前扫描原accepted范围；仅status1非legacy保证金走锁外查单/原attempt关单。外层active Tx直接拒绝，不用NOT_SUPPORTED隐藏仍持有的锁。
2. private resolve固定原invite/topic/businessNo/amount/observedWxNo；SDK返回UNKNOWN/无身份/close未确认保持原号、无本地CAS。迟到SUCCESS先保留B原事实，当前轮close未确认则失败；下次原号查到SUCCESS沿实际mark/readback恢复，绝不按未缴抵扣。查证之后短 `REQUIRES_NEW` Tx：topic当前锁→invite当前锁→原scope/amount核对→原attempt fence→原CAS。SUCCESS仍经原三参mark并回读状态2和原TX；CLOSED仍1→5。没有SDK调用进入这个本地mutation方法。
3. 原每主题 `REQUIRES_NEW` 结算Tx、金额规则、claim/receipt/奖励顺序保留；仅入口多一次独立查证。`refundTopicDeposits` 在同一原结算Tx下当前锁读accepted；若查证间隙出现新status1则抛出，含零销售的整笔关账回滚，不把单次扫描当关闭资格。外部已确认事实不随该回滚清除。
4. `ApiCoopController.depositCreate`/APP创建入口 → `prepareDeposit` 提交本地意图后 → SDK prepay，当前controller无外层事务；原businessNo/store retry合同原样不动。`depositStatus` 的SDK query也在controller锁外，再调三参mark，原入口未改。
5. `CoopInviteServiceImpl.markDepositPaid(String)` 无生产直接调用者（仓库搜索仅接口、实现、既有测试），仍修真实内部兼容入口：NEVER+显式active拒绝→锁外SDK query→独立短Tx按topic（若真实存在）→原invite→原attempt复核→原三参mark。保留原三参mark的事务/API，不修改callback owner。SDK事实保存由B负责，不造第二种VERIFIED或DONE。

`REQUIRES_NEW`短Tx只在明确无actual外层Tx之后开启，也避免NEVER代理的空synchronization使内层实际Tx的active标记不正确。新增必需依赖：CoopInvite的 `depositPaymentTransactions`，无optional/default绕过。

## 源测试与原断言

- `CoopDepositSettlementAttemptTest` 9→14普通声明；原9场景保留。新增实际SDK调用点无active Tx、CAS有active Tx、外层Tx拒绝、scope/amount漂移零CAS、查证间隙pending阻止零销售退款、CoopInvite查询外层Tx拒绝（其中交易窗口用例合并query/close/CAS）。unit事务管理器只模拟Spring生命周期，不冒充DB锁。
- `CoopDepositSettlementAttemptMySqlIT` 8→8，保留旧PAYERROR、未知、迟到SUCCESS、换attempt等场景。fixture加入原base cms_topic并实际SELECT FOR UPDATE；provider mock在query/close断言caller无Tx，并给迟到SUCCESS fixture补原SDK必需successTime。旧回滚case改为SDK CLOSED先保存，再故障回滚本地CAS：全部invite bytes回滚断言保留，但原closed_confirmed_at为NULL的预期改成非NULL，这是明确采用“外部已证事实不随本地回滚丢失”的合同修正；不是减少业务断言。
- `CoopDepositRefundMybatisIntegrationTest` 原查询字段拒绝/成功断言保留，只接真实短Tx manager、补原wx attempt及fence double。四个老主题fixture仅补必需invite mapper，原case/断言未改。
- 没改skip、参数化测试、CI/registry。普通声明数仅源码统计，不是已执行case数。

## 实际验证与共享余项

已执行 `git diff --check`、精确生产方法范围比对、mutation无SDK调用/锁顺序静态核对、有限补丁秘密模式检查、逐文件SHA256。Java/MySQL/provider/CI均 **NOT_RUN**，不能宣称闭环或并发已验收。root须统一回归，B的新短writer源码需一起审阅/集成；本片不替代SDK原mchid/appid/hash/已成功证据验证。

需root串行协调的同类触点：`CoopDepositRefundService.afterCommit():55` 直接refresh→独立claim Tx结束→queryRefundFacts；恢复外层afterCommit资源后，actual-Tx标记仍可能为true，B新writer可能拒绝成功事实保存。原退款意图不会由本片删除，但需要真实afterCommit场景验收/调度边界处理；文件未获本次授权，未并改。`settleDepositRefunded`无当前生产调用者、方法本身无Tx，未扩写。人工费用/尾差/负毛利政策仍NOT_ENABLED。

无生产DDL/部署。回退本片不得删除B持久成功/CLOSED事实；回退caller会重新造成锁内SDK保存的风险，不能当数据回滚。两条此前候选a379/411保持独立，不覆盖root的消费leaf或原退款11场景。
