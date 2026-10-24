# E 两域复用原 Reference 可消费守卫

Parent：`def951bef36a94417d90259906f6bda6abe6efb3`，分支 `codex/architecture-day-e-payment-success-fence-def9`，工作目录 `/workspace/hengyin-e-payment-success-fence-def9`。本增量仅接 9f0 之后的缺口；def9 已包含此前 API/caller/fixture 接线。

必须组合 root 的 `ce4cd7d8baddcb62aad85b212fd0d89b9fd4aec1`：`public WechatVerifiedPaymentFactService.Stored requireConsumablePayment(Reference reference)`。已读取其真实 MANDATORY/activeTx、原业务行与 latest intent current lock、not-CLOSED/原 merchant/app/attempt/金额/hash 判定源码；E 不复制这些规则，不修改 root/B/G 源码。单独 checkout 本提交尚缺共同方法，不能宣称独立可编译。

## 精确变化

- CLM 的 club→member→invoice 锁序、Commerce 原 order 锁先取得；fresh 成功在原 PAID CAS/权益前用共同方法替代旧环境 mchid 的 bool guard。
- `completeInvoiceProof` / `completeCommerceProof` 先核原 PAID+fact id/hash/金额/交易/scope，再用同一共同守卫，最后才 complete recovery。原 PAID+NULL/冲突 proof 仍 false；开放原 attempt 的精确已消费重放仍成功、无再次 grant；CLOSED 或被新 attempt 替代时保留原 fact/proof/票4，不清票、不重开/退款。
- 只将共同守卫的 `ConflictException` 转为原 boolean 拒绝；DB/未知异常或返回其他 Stored 继续抛出。首次写入后的 proof 读回若再次被拒，原外层 readback 检查会抛异常并回滚 PAID/权益/proof，不能提交部分写入再返回拒绝。B.completeRecovery 的异常不被该 adapter 捕获。
- fresh 通常两次共同核验（CAS 前、proof 完成前），精确 PAID 重放一次；均在原业务事务/锁内，无额外 SDK。未声称耗时/RPS。
- 全局 E unit fixture 只接受显式已签发的 Stored，新增共同端口转交同一 issued reader；未知 Reference 仍抛错。因该 fixture 已被 def9 的 H2/caller 使用，不需要再改共享 H2/Router/Controller/factory 文件。原真实 CloseAttempt fixture 的 owner/intent JDBC 数据继续使用；未加假 mapper 放宽生产。

## 回归源码及实际检查

`VerifiedDomainPaymentProofTest` 原9→11：原 current-denial case 仅把旧 bool stub 换为共同 typed Conflict，原断言保持；另外两例为 bound proof 拒绝/DB current-read 异常原样抛出。原 CLM78、Commerce19 整个文件不变。

`ClmCommercePaymentProofMySqlIT` 原6→8（原6及 setup/cleanup/helpers 逐字保留）：

1. `latestClosedOriginalSuccessRetainsItsFactAndManualTicketWithoutLocalConsumption`：真实原 attempt 标记 CLOSED 后，真实 B recordVerified 自动留下原 fact/票4；真实域+mapper/SQL拒绝，不改变原状态/权益/audit/proof。再插入下一原 attempt，对旧 Reference 仍拒绝。9f0 的旧 bool 会错误消费这条当前 CLOSED 行，该例应红。
2. `boundPaidReplayCannotEraseALaterClosedAttemptManualHandoff`：先真实成功消费，再用明确对抗夹具模拟 CLOSED 标记；同 fact 再次观察保留原身份，真实域重放不得擦除票4或变更原权益/proof。对抗夹具不代表生产新增关单/另起收费政策。

实际运行：`git diff --check`、`python docs/delivery/architecture-day/E/PAYMENT-PROOF-CLOSED-ATTEMPT/verify-static.py` PASS。仅核 E 自身方法/场景等价，不再用“共享 owner 必须未改”断言检查正式组合；9f0 原脚本不改、不伪称其完整在组合通过。

**Java、JUnit、MySQL、CI、真实 provider：NOT_RUN。** root 已登记原6；请将本 IT 精确注册为8并组合 ce4c 后统一执行/核验 cleanup。无 schema/DDL/依赖/资金规则/新外呼；未派 CI。文件 gate 与六类有限文本扫描通过；本机无 gitleaks，完整固定版本复扫由 root 完成。

此前未发布的域内 SQL 草稿保留在另一独占工作树 `/workspace/hengyin-e-payment-success-fence`，不进入此候选，不修改或覆盖 def9 的 mapper/XML/共同 guard。
