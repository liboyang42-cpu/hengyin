# A 提现资金事实：隔离 CI 候选交付

结论：提现代码、既有调用者夹具、专属测试及管理端重查范围已完成候选修改。**仅供隔离 GitHub-hosted CI 验证，未达到正式集成/部署验收。Java/H2/MySQL 未执行。** 用户已明确授权本候选的 commit/push 例外；这不代表资金验证已通过。生产 DDL 未执行。

## 基线、隔离与旧候选

- 公共仓库仅 `https://github.com/liboyang42-cpu/hengyin.git`。
- 开工实际 HEAD 与参考基线均为 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`。
- 原工作树 `/workspace/hengyin`、分支 `work` 保留；独立工作树 `/workspace/hengyin-a`、分支 `codex/architecture-day-a-withdraw`。
- 首版完整候选 `/workspace/scratch/architecture-day-a.patch` 保留，SHA-256 `d7c369712355d2b92bdcced7c904e094ef7ba898cdbb136c92b863a807f0a620`；没有覆盖或回退原工作区。
- 最终 commit/tree 由协调者接收完整 SHA；每个源码/迁移/测试文件的 SHA-256 见 `candidate-files.json`。静态源码摘要见 `static-check-output.txt`。
- 没有创建额外 agent/会话、修改 B 网关、修改共享余额公共写侧、改权限、根 pom 或 CI；没有导入私有仓库历史/凭据，没有 merge/master push、部署、微信上传或真实付款。

## 调用链与根因

`ApiWalletController.withdraw` / `ApiClubSettlementController.withdraw` → `CashWithdrawServiceImpl.withdraw` → `WithdrawalBalanceAdmissionService.debitCash(REQUIRES_NEW)` → 事务外 `IWechatPayService.transferToBalance`。搜索生产代码后，transferToBalance 的业务调用者仅该提现实现；两个 Controller 目前无外层事务。

`WithdrawTransferQueryTask` → `syncPendingWithdrawals/syncOne`；`CashWithdrawAdminController` → `requery/manualRefund/freeze`；后台 `cashwithdraw` view 调这些原有 API。`AccountDeregistrationServiceImpl` 继续通过 status 0/3/4 在途检查拦注销。小程序俱乐部结算页当前已停用直接提现入口，没有按“无旧用户”删除其他调用者或历史资金记录。

基线扣款没有真实写入 balance_deduct_id/refund_id 关联证据，人工仅凭 status3/4+双人复核；未知受理也可能被当成可回补。人工余额异常被吞掉后还有状态写回，不能证明整笔回滚。编码前失败反例见 `failure-mechanisms.md`。

## 最终修复合同

1. 扣款、追加不可变 DEBIT（原单/会员/金额/CW/CD/前后余额）、绑定 balance_deduct_id 同事务提交。0 行、缺生成 ID 或绑定失败立即抛出回滚；原单已扣款不能再扣。
2. 外部调用前单独提交唯一 SUBMIT_INTENT。只有新 DEBIT 且尚无其他事实才能领取该意图；返回其持久 ID，原号绝不新建第二次意图/重发新号。意图提交回包丢失时不继续调用，保留调查。
3. B 保持 ok/ambiguous 形状。仅 `ok=false, ambiguous=false` 的发送前本地拒绝进入 NOT_ACCEPTED 尝试；证据必须绑定本次返回的 SUBMIT_INTENT ID，原号不能有先前 ACCEPTED/UNKNOWN 或其他在途观察。旧原号的未知提交绝不因新一次本地拒绝获得回补资格。
4. SDK 后异常、空/不匹配响应、缺确定性标记、UNKNOWN/PROCESSING/NOT_FOUND 都保留原号并追加 UNKNOWN；不按异常类型或文案判未受理。ok=true 持久化 ACCEPTED，仍不是到账终态。
5. **CLOSED 单独出现只按 UNKNOWN。** 只有 `result=FAIL` 且 `detailStatus=FAIL` 才能写 FINAL_FAIL；回补门也再次校验该明细状态，schema 候选同时约束。没有采用未经官方证实的整批 CLOSED 语义。
6. 回补必须有完全匹配原身份/金额及余额差的绑定 DEBIT，未有 REFUND/SUCCESS，未冻结；再满足“新扣款后从未生成提交意图”、本次首次意图绑定且无先前 UNKNOWN/ACCEPTED 的 NOT_ACCEPTED，或确证明细最终失败。历史缺证据一律保留调查，不补造流水。
7. 保留自动回补既有 TransactionTemplate；人工第一人只3→4、第二人必须不同账号且重新查证。原单锁、REFUND 唯一事实、余额回补和终态2同事务，任何余额 false/异常都整笔回滚。自动/人工/两个复核人竞争最多一次经济效果。
8. 调查3/待复核4允许原号重查。迟到 SUCCESS 与终态1原子保存，阻止回补；已回补后再观察到 SUCCESS，只追加冲突证据并报错调查，不自动扣回余额。冻结与既有权限仍由服务端执行。
9. withdraw/sync/requery 显式挂起外层事务，防止网络调用被上游事务包住。事实无 TTL、无删除/更新 API；触发器禁止修改删除，原证据指针不可经通用写侧替换。

## 逐方法适配清单（未重写共享整文件）

| 文件 | 实际修改的方法/夹具 |
| --- | --- |
| OWN_C04_21Test | cashTerminalOrFrozenOriginalCannotReachMemberDebit；cashAdmissionLocksOriginalBeforeMemberAndFreezeSources；cashCallerDoesNotSubmitOrOverwriteAnIneligibleOriginalReceipt；cashFixture |
| OWN_C04_21MySqlIT | database；reset；cashRequiresNewCommitsAndReleasesFinancialRowsBeforeCallerContinues；insufficientCashAdmissionFinalizesOriginalRowAndCannotDebitAfterLaterCredit；新增 cashId 字段，保留不可变事实并给现金用例不同原单 ID |
| OWN_C04_22Test | row(int)；Fixture 构造器。保留迟到受理/未知不能覆盖原回执的原断言 |
| BothWithdrawalLegsRejectPlayerCooldownTest | setUp；bothLegsHandTheCombinedFrozenAmountToTheAtomicDeduction。银行断言保留 |
| FundIdempotencyConcurrencyTest | 仅 deterministicTransferFailureAndReconcileCompeteForSameClaimBeforeBalanceRefund；保留原单竞争/余额一次断言，迟到本地拒绝不能重新领取；积分方法正文未改 |
| CashWithdrawServiceTest | setUp；transferResult；3个扣款/确定失败场景的 processing CAS 适配 |
| CashWithdrawStateMachineTest | setUp；pendingRecord；qr；同步成功/失败/未知/超时的证据夹具；确定本地拒绝与提交回包丢失；双人回补/余额异常用例，改为异常逃逸而不是手动回写状态 |
| CashWithdrawFrozenRequeryH2Test | setUp；insertProcessing；failResponse。仅旧冻结回归 fixture，不能充当 MySQL 证据 |
| cashwithdraw view | canRequery（新增）；handleRequery；statusText；manualImpact 和状态/说明模板。重查仅0/3/4，manage 权限表达式未改 |
| cashwithdraw-migration-contract.test.js | loadCashwithdraw、原状态边界断言、新增状态集合和仅调用查询API的2个用例 |

生产修改：CashWithdrawServiceImpl、WithdrawalBalanceAdmissionService、MemberCashWithdrawMapper(Java/XML)、CashWithdrawAdminController；新增 CashWithdrawFundFact。其他新增：CashWithdrawEvidenceFixture、CashWithdrawFundEvidenceTest、CashWithdrawFundEvidenceMySqlIT、architecture-day-a-fixture.sql、候选 migration。

静态检查比对7个银行/冻结/赏金/积分方法正文与公共基线逐字一致，未降低其断言。完整路径/摘要见清单。

## 实际验证

- 实际执行 `node tests/unit/cashwithdraw-migration-contract.test.js`：**5 tests / 5 pass / 0 fail / 0 skipped**。
- 实际执行 `node --test` 点名 cashwithdraw-migration、cy-confirm-action、cy-page-header 三个文件：3 files pass，0 fail/skip。文件级回执不当成额外 Java/浏览器证据。
- 实际执行 Python/XML/Git 静态检查：Mapper 25个语句匹配接口，字段映射和保护范围检查通过，7个非现金职责方法逐字一致，diff 空白检查通过。输出在本目录。
- 提交前执行项目 secret-file-gate 和新增内容敏感模式扫描；具体回执见 scan-output.txt。没有通过复制凭据修复网络或认证。

**未执行：Java 编译、Java 单测、H2、MySQL、Redis、后端启动、真实微信链路。** 新专属单测21个、新MySQL IT19个均是源码，不是测试通过声明。新IT无条件跳过或H2降级；已有 OWN_C04_21MySqlIT 的原环境门控不变，受控执行时必须显式打开。

19个MySQL场景含合法首次回补、无扣款、扣款提交回包丢失、意图提交回包丢失、渠道异常/未知/NOT_FOUND、CLOSED不确定、先UNKNOWN后本地拒绝、最终明细失败、迟到SUCCESS、外层事务挂起、两个复核人/自动人工并发、自动/人工余额写后抛错整笔回滚、回补提交回包丢失、事实不可修改删除。渠道为桩，真实 Mapper/事务/数据库行为仍待受控执行，不代表微信实际验证。

## CI 与 schema 前置

- 本次用户授权仅为隔离 CI 候选的 commit/push 例外，未通过不得正式集成/合并/部署。
- `chengyinhub-admin/sql/migration_20261023_cash_withdraw_fund_fact.sql` 仅编写候选，未执行生产或任何本地 DDL。docs 中 schema-candidate.sql 为同内容审阅副本，schema-preflight.sql 为只读预检方案。
- 新表为 InnoDB，原单外键、提交意图自引用、(withdrawal_id,kind)唯一、金额/DEBIT差额/NOT_ACCEPTED意图/FINAL_FAIL明细约束，以及禁止UPDATE/DELETE触发器。历史零回填，不清空资金或审计记录。
- 先审查/核验 schema，再启用代码；缺表会 fail closed。旧代码回退会恢复危险的status-only回补，不能带着新在途单直接回退。应暂停新增提现、隔离在途，保留全部证据，审阅前向修复。
- 现有 funds-mysql-it-gate.sh 使用固定测试类列表，**不会自动包含本次新IT**。需协调者在已批准的GitHub-hosted验证路径显式执行下列选择，不能凭旧门禁绿灯认定这些用例已跑。

受控环境建议（不是已执行命令）：

```sh
mvn -q -pl chengyinhub-admin -am -Dtest=CashWithdrawFundEvidenceTest,CashWithdrawServiceTest,CashWithdrawStateMachineTest,CashWithdrawFrozenRequeryH2Test,CashWithdrawAdminControllerSafetyTest,OWN_C04_21Test,OWN_C04_22Test,BothWithdrawalLegsRejectPlayerCooldownTest,FundIdempotencyConcurrencyTest -Dsurefire.failIfNoSpecifiedTests=false test
CHENGYIN_FUNDS_MYSQL_IT=1 mvn -q -pl chengyinhub-admin -am -Dtest=CashWithdrawFundEvidenceMySqlIT,OWN_C04_21MySqlIT -Darchitecture.a.repo="$PWD" -Dsurefire.failIfNoSpecifiedTests=false test
```

MySQL使用既有显式 CHENGYIN_TEST_MYSQL_* 受控配置，只建/删本套随机测试schema，不申请或复制VPS/生产凭据。剩余验收阻塞：受控Java/MySQL真实执行、新schema审核、B正式候选与本合同的集成确认。本地环境可用；首次Git网络检查受默认沙箱限制，获准的network权限下读取成功，未重建或覆盖工作树。
