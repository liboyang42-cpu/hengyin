# E：公共 master 的主题冲突集成

- 候选 parent：`ff2b0ea1cf0ca3f097de446b5eda85beb3fad1a0`。
- 被保留的 master：`892efe6f765a5748065569fd886ee9b00ef71d4b`（支付 PR598 已合并）。
- 真实 merge-base：`e1083c6c5f4dd5fa992a43015d2ae8c764626d52`，来自 GitHub compare 的 `merge_base_commit`。
- 隔离目录：`/workspace/hengyin-e-public-master-topic`；分支：`codex/architecture-day-e-public-master-topic-20261024`。
- 起初 shell 返回 `No usable execution environment is available`；先通过 GitHub 公共源码继续，执行器恢复后创建了上述新工作树。未重置旧 E 或 root 工作区。

## 四个实际冲突块

用三份精确公共源码运行 `git merge-file --diff3 -p`，只在本文件保证金查证闭环产生四组冲突。master 相对真实 merge-base 对本文件的全部变化均落在这些块中；移除冲突标记后，块外源码与候选逐字一致。

| 冲突块 | 最终保留 |
| --- | --- |
| 原查证入口/原业务身份 | 候选的主题范围枚举、事务外显式拒绝、原 invite/topic/business/amount 快照；master 的原业务单与原微信 attempt 身份要求 |
| 关单与回读 | master 的 NOTPAY/USERPAYING/PAYERROR/REVOKED 原 attempt 关单，再按该 attempt 回读；close=true/false/异常都不能当 CLOSED。回读仅接受同 business+attempt 的 SUCCESS/CLOSED，否则业务零 CAS |
| SUCCESS | 候选的短事务、topic→invite 当前锁、typed Reference、当前 attempt 守卫、金额币种及原 transactionId 终态回读；不恢复 master 的旧公开三参成功入口 |
| CLOSED | master 的 CLOSED-only 与当前 attempt 要求，加上候选更严格的 `lockClosablePaymentAttemptForUpdate`、原金额/状态 CAS 和提交后内存更新 |

生产实际增量只在 `resolvePendingDepositPaymentForSettlement`：删除伪造 CLOSED；增加一次原 attempt 回读，随后把私有观察副本交给原短事务。`applyObservedDepositPaymentForSettlement` 逐字保留，无 SDK 移入锁内；不新增重试循环。

TopicPublication/Query/ContentGraph 领域 owner 的 DI、facade、发布校验/快照调用均逐字保留。未恢复旧 GodService，未改权限、费率、返还或奖励政策。其余 merge 冲突由 root/B 处理，本提交不是全仓合并提交。

## 直接必要的测试接线

- 候选 `CoopDepositSettlementAttemptTest`：保留原全部场景/断言，只给两个原关单成功 fixture 补原 attempt CLOSED 回读；原事务断言也检查新增查询在事务外。
- 候选 `CoopDepositSettlementAttemptMySqlIT`：只加原号回读的有序调用断言；原真实 SDK double/SQL/fact/rollback/清理代码不改。
- master 新增 `CoopDepositPaymentSettlementTest`：保留全部 8 个方法、ValueSource 18 个展开场景和原零抵扣/未知/迟到成功/错原号/金额断言。补候选所需 topic/current-invite/短事务 fixture，三参改 typed 四参且引用 hash 绑定原 attempt。CLOSED 锁断言迁至更严格 closable；SUCCESS 从旧“不取通用锁”改为必须取原 attempt 成功锁且不取 closable；当前范围+终态回读准确要求两次 invite 锁读。此 fixture 仅模拟 unit 事实，不冒充真实查证。

`owned.patch` 是四个代码文件相对候选的完整补丁。master 新增测试在候选中原本不存在；root 若已自动合入该文件，可使用 `master-test-adaptation.patch` 原位迁移，或采用本提交该文件的完整 blob；不要在两套 fixture 间重复应用。没有删除/跳过原测试。

共同依赖仍由 B/root 集成：`IWechatPayService.queryOrderStatus/closePaymentAttempt/lockPaymentAttemptForUpdate/lockClosablePaymentAttemptForUpdate`，以及 `ICoopInviteService.markDepositPaid(..., Reference)`；本片不修改这些接口或资金实现。

## 验证边界

执行：三方文本冲突重现、块外/原锁内 apply 字节一致性、原测试名与参数集合保留、`git diff --check`、新增行有限秘密模式检查。完整 gitleaks 未运行，交 root 统一扫描。

Java 编译/JUnit/MySQL/Redis/真实 SDK/HTTP/CI：全部 **NOT_RUN**。未触发 Generic26 或任何既有 CI 重跑。最终两 public 分支的组合编译与验收由 root 统一执行；此处不宣称全仓合并、真实事务或供应商验收完成。
