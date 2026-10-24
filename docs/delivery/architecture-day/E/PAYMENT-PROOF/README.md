# E CLM / Commerce 原成功消费 proof（候选源码，未验收）

基线/唯一 parent：`e4bdaceb8c1eb800b38c93601bbbd82ca15b26e5`；独立分支 `codex/architecture-day-e-payment-proof-e4bd`。仅交已合 E5f6 之后的增量。B9d/e1、A/G 原修改均来自此公共基线；不重复覆盖。Java、JUnit、MySQL、DDL、SDK/provider、CI：**NOT_RUN**。

## 修复与真实调用

原 CLM invoice 的交易号/PAID 与 term 的 source_invoice_id，原 Commerce PAID 与聚合 subscription，均不能证明消费了哪一条 B Reference。仅凭 PAID 接受回放会把历史 NULL 变成新消费事实；原 raw 参数入口也允许绕过 proof。

新公开签名仅保留 `(outTradeNo, paid, amountFen, transactionId, Reference)`。先 `B.requireVerified` 读不可变 fact/hash，核 PAYMENT/CNY/业务单号/原金额/原交易；原 merchant/app/channel/intent hash 来自 B 已验证持久事实，不选择当前配置补缺。新消费在原业务锁下用 B 本地 `lockPaymentAttemptForUpdate` 复核原 attempt，绝不再次 SDK 查证。

CLM 仍是 preview → club → member → invoice current-lock → 原 PAID CAS → 原 term/review/audit → proof 条件写/当前读回 → B.completePaymentRecovery。Commerce 是原 order current-lock → 原 PAID CAS → 原 grant → proof 条件写/当前读回 → complete。应用边界 REQUIRED，加入原 callback/DONE；程序式边界也覆盖自调用。proof 绑定或回读/complete 失败抛异常，随原业务事务回滚，不把部分写入当 false 成功。

精确 PAID+原 fact id/hash/交易/金额重放只完成同一恢复凭据，不再 grant。历史 PAID+NULL、不同 proof、CLOSED 一律不自动绑定/4→2/重开/退款。原成员退出后 PAYING 回调的 CANCELLED term/manual review 政策保留，未更改。

生产调用：SDK/facade 已验证原成功 map → Router 两域分支 → typed owner；CLM resume/close 查证成功及 Commerce reconcile 在 SDK 锁外观察后转交同一 Reference。原 5+3 SDK 调用位置/数量保留，没有新增 SDK 或 NOT_SUPPORTED 悬挂。e4bd facade 已无 raw paid 调用。默认分支符号搜索命中已逐个映射并取回 e4bd 精确 blob；该搜索仅覆盖默认分支，不冒充对 e4bd 全量源码的最终 census。

## 必需共享接线（root 串行应用）

1. `shared-api.review.patch`：IClubMembershipService / IMerchantCommerceService 删除旧 raw 声明；Router 两处转交必需 Reference。G 已合 typed 分支逐字保留。
2. `caller-fixtures.review.patch`：ExitCallbackTransactionBoundary、ClubMembershipRouteContract、Router、ForgedSignature、E2eStubContract 与实际 scripts/e2e stub 的精确 hunk。既有 SDK 正控用真实原 fact reader 校验转发 Reference；e2e stub 只读显式已有 Reference，不能从明文生成 proof。

上述共享文件在本提交不直接修改。**只 cherry-pick 本提交尚不能单独编译**，必须串行接入这两份 patch 后统一 compile。e4bd 的 G raw deposit fixtures 另归 G/root 闭合，本 patch 不接管；最终组合需全仓 rg 两个 E paid 符号和 G typed 调用再 compile，不能宣称本片解决其他 owner 的旧工厂/调用者。

## 源码验证与用例矩阵

- 实际执行：`python docs/delivery/architecture-day/E/PAYMENT-PROOF/verify-static.py`：PASS。CLM 73 / Commerce 18 个未触及方法体逐字保留；原 mapper 写语句体逐字保留；3 个 proof current SELECT 双 cache flag；两 patch 在临时目录真实 git apply；已知 E 调用/声明均 5 参数。
- 原 CLM78 / Commerce19 用例名、场景和原断言保留。fixture 显式注入原 Reference/已完成 proof，错误金额和 grant 失败保留原触发条件。原成功恢复后再测 UNKNOWN 改用另一原 pending order，未把 PAID 回滚成 pending。原 CloseAttempt SDK fixture 复用其原 owner/attempt JDBC 数据，未放宽生产校验。
- 共享 patch 保留原 1+1+2+33+9 个测试场景；仅必要签名/Reference fixture 接线，RouteContract 的同一断言加强为强制转交 Reference。原 H2 并发锁序用例继续仅为 H2，不是 MySQL 证据。
- 新 `VerifiedDomainPaymentProofTest` 9 条源码（每条 CLM/Commerce 两域）：缺/错/跨单 Reference、历史 NULL、原 attempt 拒绝、精确一次 grant/重放、proof CAS0/回读冲突、complete 失败传播。资源空事务计数不冒充 InnoDB 回滚。
- 新 `ClmCommercePaymentProofMySqlIT` 6 条源码：真实 canonical XML/领域/B fact+recovery；原 mutation/proof/recovery 与外层 callback-DONE 失败一起回滚；原号幂等；NULL/CLOSED；跨单/金额/hash 拒绝；两域真实 CHECK/FK/UK 错误码。只观察受控事实，没有 SDK 调用。原 fact 独立提交后保留。明确 env/loopback/MySQL8，随机独占库用 CREATE（无 IF / 预 DROP），取得所有权后才 cleanup 并 information_schema 确认 0。
- 尚未执行上述任何 Java 测试；无 pass/skip/cleanup 实证。root 需注册 6 条新 IT 的 migration/环境/清理证据后同源运行。

本机无已安装 gitleaks；仅文件 gate 与六类有限文本扫描，不能称完整 gitleaks0。发布前 root 必须按项目固定版本复扫完整差异与 patch。

## 候选迁移与回退限制

唯一新增 `chengyinhub-admin/sql/migration_20261024_clm_commerce_payment_proof.sql`。两原业务表各加 nullable unsigned fact FK + VARBINARY(64) hash（CHECK 精确长度/小写 hex/成对 NULL），各唯一 fact-id 绑定；无历史回填。读 API 不暴露 proof 字段，普通 insert/update 不写 proof。

全 schema FK/CHECK 名、目标列、目标 UK、原 fact id 类型/引擎/版本/strict mode 在任一 ALTER 前预检。每表一条完整 ALTER；两表 DDL 仍非原子。第一条提交后第二条失败必须保留现场，按 SHOW CREATE/列/索引/约束精确盘点，仅经复核恢复未完成语句；不能整文件重跑、IF EXISTS 猜绿或 DROP 回退。保留全部原资金/审计/fact/proof 数据。

root 负责 canonical 顺序登记、统一 CI 与共享接线。代码回退不得恢复 raw writer 后继续受理回调而丢失新消费关系；应暂停相关写入并保留只读/人工恢复入口。未获生产 DDL 或自动退款/重开授权，本候选未执行这些动作。
