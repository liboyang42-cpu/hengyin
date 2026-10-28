# A：两条回滚测试的 Mockito 注入修复

结论：仅修复 `CashWithdrawFundEvidenceMySqlIT` 两处已有 answer 的覆盖方式，`when(...).thenAnswer(...)` 改为 `doAnswer(...).when(...)`。不改生产代码、金额校验、SQL 写后异常或回滚断言，不删测、不跳过。

- 仓库：`https://github.com/liboyang42-cpu/hengyin`。
- 直接基线/parent：`88fae34ca16597b69f55271078d028658e438ffb`（H-A01/A-P2 恢复候选）。
- 独立目录/分支：`/workspace/hengyin-a-rollback-fixture` / `codex/architecture-day-a-rollback-fixture`。冻结 A、WP12、A-P2 不变。
- 沿用已授权的隔离 CI 候选 commit/push 例外；本批未运行 Java/MySQL、未派发 CI、未集成/部署/执行 DDL。

协调者提供的最新状态为：combined `089790aaf`、run `40016650848` 实际执行19条，17通过、2 error、cleanup CLEAN。本会话未独立读取该 CI 日志；下述修复依据其调用栈及本地源码核对，不宣称两条已经复跑通过。

`service()` 原先为 `members.addBalance` 装配 answer，委托真实 `UmsMemberServiceImpl.addBalance`。随后两个测试使用 `when(members.addBalance(anyLong(), any()))` 覆盖它时，会先执行该旧 answer；Mockito 匹配器的占位参数为 `0L/null`，`null` 金额触发真实写侧“变动余额需大于0”校验。不是业务调用传入了真实零金额，也尚未执行到新注入的 SQL 写后异常。`doAnswer` 注册覆盖时不会调用旧 answer；实际业务随后仍以真实原 member/金额进入注入的 SQL 写及异常。

仅修改两个方法：

1. `manualCreditThenExceptionRollsBackWholeFinancialTransaction`：仍要求 SQL 写后异常穿出事务，status保持4、无 refund pointer/REFUND事实、余额70。
2. `automaticCreditThenExceptionRollsBackWholeFinancialTransaction`：仍要求自动回补 SQL 写后异常整笔回滚，status保持3、无 refund pointer/REFUND事实、余额70。

根的 `displaySnapshot` 先计算再 thenReturn 修复保留；原故障注入 body 与两方法其余源码逐字不变（静态归一化核对）。本分支基于 A-P2 的23项 IT，但这个独立 delta 只改上述2方法，在尚未集成 A-P2 的 combined 上也可按方法应用这4行替换；不要整文件覆盖根的联合编译/夹具修复。

实际通过：Python 源码差异/原断言保留/保护字段检查、秘密文件门禁、`git diff --check`，输出见 `verification.txt`。未执行 Java/MySQL；不能把静态检查当作17+2全部通过。

没有迁移影响；无需回退数据或资金记录。代码回退仅恢复错误的测试注入，不涉及生产余额或证据合同。候选每文件 SHA256 在 `candidate-files.json`，最终 commit/tree 随交付消息提供。根统一安排真实回归，本会话不抢 CI。
