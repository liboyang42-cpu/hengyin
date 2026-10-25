# A：恢复模式资金准入与派发源码

本片是待组合、待验证的 SOURCE 候选，不是 T20 验收通过或恢复环境可投产证明。

- 公共父提交：`fa5c263440573992701263d6bee60830844a49b8`。
- 独立分支：`codex/architecture-day-a-restore-money-dispatch`。
- 独立工作树：`/workspace/hengyin-a-restore-money-dispatch`；未切换、重置或清理其它工作树。
- 必须组合 F 的 `f874583cab3a05026078ef51a5609644587bd2d9` 公共 guard；本片没有复制或修改该文件。
- 已只读核对其实际实现：类加载时捕获 `CHENGYIN_RESTORE_MODE`，缺省/精确 `false` 正常，其它值（包括空串）暂停；`Blocked` 固定消息 `RECOVERY_DISPATCH_BLOCKED`，不是 NotSent 类型。

## 已写入的主调用链

| 实际入口/调用者 | 本片接线 | 恢复模式边界 |
|---|---|---|
| registration checkout → order service → registration create | `RegistrationOrderServiceImpl`、`RegistrationCheckoutServiceImpl`、`CmsRegistrationServiceImpl` | 原持久 fingerprint 的精确重放仍可读；禁止新单、父/子报名号、旧单 fingerprint 自动补绑、支付尝试、零额确认、取消和积分/库存释放。创建内部方法和最终编号方法均有围栏。 |
| checkout JSAPI/App → SDK gateway → intent store | `WechatPaymentSdkGateway`、`WechatPaymentIntentStore` | 在冻结新支付意图/别名之前拒绝，实际 prepay/close 口再次保护；原查单、验签/事实读取不加总开关。 |
| cash withdraw → balance admission → immutable debit/submit → gateway | `CashWithdrawServiceImpl`、`WithdrawalBalanceAdmissionService` | 建单/自增 ID/扣款/提交意图之前拒绝；原号 requery/sync 可以查并保留结果，自动回补和人工 maker/checker 新经济效果暂停。 |
| bank request/review/revoke/confirm → balance admission/refund | `UmsMemberWithdrawalServiceImpl`、`WithdrawalBalanceAdmissionService` | 提现创建、扣款、撤回/驳回返还、人工出款记录前拒绝；银行提现与现金提现原有金额/权限规则不改。 |
| refund save/review/manual → payout → typed original payment → SDK refund | `RefundServiceImpl`、`RefundPayoutServiceImpl` | 新退款、审核/手工经济推进、claim/lease/退款编号和真实 create 前暂停；有原退款号且有确切消费凭据时只查原号，缺号不补 `RF+id`。 |
| registration/refund verified callback → original inbox → mutation/DONE | `CmsRegistrationServiceImpl`、`RefundServiceImpl` | 原验证与 inbox 登记保留；在经济 mutation 前返回未消费结果，不伪装 DONE/渠道失败。付款事实与退款事实仍由原 typed owner 验证和保存。 |
| refund reconcile / order close background jobs | `RefundReconcileTask`、`OrderCloseTask` | 继续原号查询及积压观测；缺退款号不分配，支付 SUCCESS 仍交原回调留事实；暂停关闭/释放，不把暂停计作渠道未知或补记成功。 |
| TeamGroup 原报名/活动/主题取消及退款内层 | `TeamGroupServiceImpl` | 取消命令和经济 apply 前拒绝；独立观察函数仍可读，close 口暂停异常不转成 provider-close-unknown。 |

共 **13 个生产文件**。精确路径/行见 `guarded-symbols.tsv`，现有 SDK 调用点见 `sdk-callers.tsv`。这些清单是源码证据，不是覆盖率或运行证据。

## 原证据和恢复语义

- 不修改两套 V1 DTO、CREATE/additive migration 或 hash 算法，不补造历史 DEBIT/SUBMIT、旧 request hash 或付款消费事实。
- FUND_FACT 原 FINAL_FAIL 仍可记录，但恢复模式不领取自动退款；TRANSFER_INTENT 原 FAIL 仅保留原回执观测，不制造另一套账本或返款。
- 原 UNKNOWN/PROCESSING、一次 NOT_FOUND、迟到 SUCCESS、原未发送证明仍按原身份判断；暂停不生成 `notSent`、`NOT_ACCEPTED`、`PROVEN_REJECTED` 或退款资格。
- 已收回调只登记事实/inbox，恢复后仍走原消费身份；没有新增队列、恢复平台、自动退款或重开容量。
- 启动模式不能取消备份以后已发生的外部动作，也不证明在途调用未执行；不能通过把配置改回正常来替代调查或批准恢复。

## 跨 owner 必须组合的窄补丁

`adjacent-owners.review.patch` 是可应用的独立 hunk；**其中生产文件没有直接修改在本分支**，由 root 与原 owner 串行合成。共 8 个生产路径：

1. `MerchantCommerceService.java`：商业订单编号、预下单、本地付款消费与关闭效果。
2. `ClubMembershipServiceImpl.java`：会费建账/付款消费/实际 prepay；原账单 query 保留，暂停不变 PREPAY_UNKNOWN/closeUnknown。
3. `UmsMemberCartServiceImpl.java`：购物车结算前。
4. `OmsOrderServiceImpl.java`：后台正常新订单 insert 前，避免只挡购物车而遗漏直接创建。
5. `CoopInviteServiceImpl.java`：保证金编号/扣退/原订单创建；只查原退款的旧兼容方法没有加入口总禁令。
6. `CmsTopicServiceImpl.java`：结算与实际余额派发前；不改 G 分项/费用算法。
7. `ClubEventOpsServiceImpl.java`：外层取消 campaign/包装和最终 apply 前。
8. `CoopDepositRefundService.java`：新退款意图/dispatch 前；原已有退款号只查证，不 claim、发号或 submit，真实终态消费暂停。沿现有 `queryRefundFacts(originalRefundNo)` 路由读取，未另选任意付款 fact；其实际供应商绑定仍由原 G/B owner 验证。

补丁最后附同一个 A 新测试文件的 1 个固定 `@Test`，与以上 owner 入口一并合入。先应用本片新测试文件，再应用 review.patch；没有新共享接口。未组合这些 hunk 与 F 非资金 caller 前，不能宣称全域恢复 fence 已完成。

## 配套测试源码，全部 NOT_RUN

旧测试文件、旧 case 与原断言全文未改。新增：

- `RestoreMoneyDispatchTest`：15 个固定 `@Test`，使用固定子 JVM 的真实启动环境变量，调用真实服务/网关；Mapper/SDK double 边界明确。覆盖 true/非法/空串、缺省/false 首次真实 SDK 边界、原 FUND_FACT 和 master TRANSFER_INTENT 两 V1 UNKNOWN/SUCCESS/FAIL、不补款/不新发、退款原号查询、已核退款回调留 fact/inbox、不 DONE、两个实际后台任务原号继续查而不关闭/发号。不是 MySQL 回滚或官方渠道验证证据。
- review.patch 的相邻 owner 测试另 1 条；需生产 hunk 同时应用，本片本身没有把未接线入口称作已覆盖。
- `RestoreMoneyDispatchMySqlIT`：1 个固定 `@Test`，复用原 `RegistrationFundsMybatisIntegrationTest` 的规范 schema/真实 Mapper/事务/事实工厂，拒绝前后对比真实资金行与 AUTO_INCREMENT；再保留原 typed SUCCESS 且金融行不动。原 fixture 的 inbox/通知/G 仍为明确测试边界，不冒充真实 inbox SQL 原子性/供应商验证。

新 SQL 用例只能由 root 登记后在获准环境执行。它的子 JVM 使用显式继承的 base/suffix，数据库名为 `<原base>_rdispatch_funds_core_it<原suffix>`。必须同时显式提供原两个事实 DDL 授权开关。创建前确认精确库名不存在；正常 finally 调原工厂清理并核 absent；初始化中途失败或子进程被杀标记 UNKNOWN，root harness 必须登记这个精确库名作兜底清理，不能把未运行或未清理当 CLEAN。没有生产 DDL。

## 实际执行与限制

已做源码读取、git 差异检查和跨 owner patch 的 `git apply --check`，仓库已有 `ci/secret-file-gate.py` 秘密文件扫描实际 PASS。首次暂存差异检查指出 review.patch 的空白 context 行，已规范化并重新确认可应用；没有忽略/放宽门禁。这些只是源文件/运输/公开边界检查，**不是测试**。扫描器检查被跟踪的密钥/证书扩展名和私钥正文；没有冒称执行未安装的额外秘密扫描器。

Java、编译、JUnit、MySQL、Redis、HTTP、真实供应商、故障注入与 CI **全部 NOT_RUN**。没有启动本地后端或数据库，没有外呼/DDL/新增依赖/费用/权限规则，没有修改 C 恢复 probe、B FundsProcessRecovery 或 root registry/CI。root 收齐后统一验证；当前不能给出 T20 完成结论。

候选只改变启动恢复进程的新经济准入。原意图整条丢失、原身份无法安全关联、备份后未知受理、原外部成功但本地业务缺失等仍须按原 UNKNOWN/人工关联合同处理；本片不自动补造事实或从余额猜结果。回退代码不能撤销外部已执行动作；恢复运行必须遵循原协调验收，不得删除事实/inbox/资金记录。
