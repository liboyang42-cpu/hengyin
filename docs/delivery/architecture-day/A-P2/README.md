# A-P2：DEBIT 已提交、SUBMIT_INTENT 未落盘窗口的恢复候选

结论：修复 H 指出的健康原单永久失去回补资格问题。`syncOne` 现在先用短事务锁原单并核对不可变证据；只有 DEBIT 的原单从处理中转入既有待人工流程，不查询渠道、不追加 UNKNOWN、不自动加余额。按原 ID/CW/CD 经办与异人复核后合法首次回补一次。存在 SUBMIT_INTENT 或旧 UNKNOWN 的原单仍按原号查证，不能凭一次 NOT_FOUND、本地新拒绝或租约过期放行。

**代码及回归已编写；本批没有执行 Java 编译、单元测试或 MySQLIT，也没有启动/派发重复 CI。** 本地实际通过的仅为轻量 Python 源码检查、负控、旧断言保留检查、项目秘密文件门禁及 diff 检查。真实锁、事务及并发结论等待协调者受控 CI 验证。

## 基线、隔离和审查来源

- 仓库仅 `https://github.com/liboyang42-cpu/hengyin`。
- 开工 HEAD/直接 parent：`3bc4bb08caaba4cc300f2df25f90c4a070033bfc`，来自已冻结的公共 A 候选；相对公共参考 `2ec68899099a1e250df0949a8383bf85b5d3ffe0` 已有 A 原补丁，本批不重做。
- 独立目录 `/workspace/hengyin-a-p2`，分支 `codex/architecture-day-a-p2-recovery`；冻结 A 与 WP12 各自分支/工作树不变，不包含 WP12 改动。
- 用户补发 H 的 A-P2 失败机制，并给出 `docs/delivery/architecture-day/H/a-d-candidates/REVIEW.md`、短身份 `4f1096a2`。本机没有该文件；公共远端未找到可取的该短引用，未宣称读过 H 原报告或执行其回归。依据补发正文与冻结 A 源码独立核对本问题。
- 沿用用户批准的隔离 CI 候选 commit/push 例外；Java 未验证不代表通过 AGENTS 编译门禁，不正式集成/合并/部署。

协调者最近提供的外部执行状态：run `40015838344` 已通过生产/测试 Java 编译，A19 实际被选中，但 19 项均在 `@BeforeEach` 因嵌套 Mockito stubbing 抛出 `UnfinishedStubbingException`，**尚未执行业务断言，不能记为 19 项通过，也不是 19 项业务断言失败**。根正在修复夹具并续跑。本批未重新开启 CI；新候选还需后续受控增量验证。该外部状态来自协调者补发，并非本会话亲自读取的 CI 日志。

## 失败机制、调用者和最小合同

`withdraw` 独立提交原单后调用 `WithdrawalBalanceAdmissionService.debitCash`，后者 REQUIRES_NEW 将余额扣减、DEBIT 与 balance_deduct_id 绑定原子提交。它返回后、`recordSubmissionIntent` 提交前进程退出，会留下金额已扣、status=0、仅 DEBIT 的健康原单。

原 `syncOne` 的第一个业务操作就是 `queryTransferResult`。本来未提交的原号查得 UNKNOWN/查无后追加 UNKNOWN；`requireRefundEvidence` 严格要求 neverSubmitted 是“无 SUBMIT_INTENT 且 facts.size()==1”，于是它永久失去合法未提交回补资格。反复查号只会保留未知，不能凭 NOT_FOUND 修复该证据污染。

已搜索的调用者/写侧：

| 链路 | 本批处理 |
| --- | --- |
| `WithdrawTransferQueryTask` → `syncPendingWithdrawals` → `syncOne` | 与后台重查共同先进入新短事务，不依赖队列旧快照 |
| `CashWithdrawAdminController.requery` → `requery` → `syncOne` | 原权限、入口冻结检查不改；3/4 中合法未提交原单保留现有人工进度 |
| `withdraw` → `recordSubmissionIntent` → B SDK | 原先“先意图后 SDK”合同不变；该方法和恢复 helper 都锁同一原单 |
| `manualRefund` → `requireRefundEvidence` → 原人工 CAS/REFUND/余额事务 | 证据门槛与双人复核不改；未提交恢复仅让它重新可达 |
| `refundFailedClaim` / `recordChannelFact` | 原自动回补原子性、原号 FINAL_FAIL/SUCCESS/UNKNOWN 门槛不改；无重新发送、改号、删除旧事实 |

新私有 helper `prepareChannelQuery` 的分类在持原单锁的短事务中完成：

1. 重新读取原单，冻结/不存在/非可处理状态返回，不动钱。
2. `checkedFacts` 校验原 ID/member/amount/CW/CD/del_flag。**只含本地 DEBIT（或已完成本地 REFUND）的证据**不查询渠道。其 DEBIT 必须匹配持久 balance_deduct_id 与扣前/扣后差额；处理中原单还须通过原 `requireRefundEvidence`，因此 REFUND 指针/事实冲突不能转成合法未提交恢复。
3. 处理中纯 DEBIT 原单 CAS 0→3，写明未提交、保留 CW/CD、待双人复核；不写假查询时间、SUBMIT_INTENT、UNKNOWN 或 REFUND。3/4 原单保留进度；纯本地已回补的2保持终态且不做无对象的渠道查询。
4. 有 SUBMIT_INTENT、UNKNOWN、ACCEPTED 或任何渠道证据就返回当前原单，事务结束后再查询；旧 UNKNOWN 不会因为缺 intent 被清除或重新解释成从未提交。查询后的最终 FAIL 等合法证据仍走原合同。

同号并发的安全边界：若恢复先拿到原单锁，0→3 提交后原发送方 `recordSubmissionIntent` 读到3返回 null，不会调用 SDK；若发送方先提交 SUBMIT_INTENT，恢复读到意图后沿原号查证，UNKNOWN/NOT_FOUND 不给回补资格。不是根据时间、异常类型或一次查无推断外部未执行。正常请求若恰好在两次短事务之间与恢复竞争并由恢复方获胜，会进入人工复核而不继续发钱；这是现有单号的保守恢复结果，没有重新建单或自动发起第二笔。

## 实际修改方法及回归

| 文件 | 实际修改方法/理由 |
| --- | --- |
| `CashWithdrawServiceImpl` | `syncOne` 在查询前调用 `prepareChannelQuery`；新增私有 helper。其余回补/原意图/渠道事实门槛保持不变 |
| `CashWithdrawFundEvidenceTest` | setup 增加现有状态 CAS 的内存适配；新增 `debitOnlyCrashRecoveryNeverQueriesOrPoisonsTwoPersonRefundEvidence`、`oldUnknownWithoutIntentIsNotReclassifiedAsNeverSubmitted`、`frozenDebitOnlySnapshotIsNotRecoveredOrQueried`。只证明 Mock 合同，不声称真锁或 rollback |
| `CashWithdrawFundEvidenceMySqlIT` | 原 `service` 的装配段抽为 `installService`，可替换进程重启后的新实例；保留根的 `Frozen displaySnapshot` 先计算、再 thenReturn 修复；新增以下4项真实 MySQL fixture 回归；原已提交 REFUND 丢回包测试的注入序号2→3，因为新增准备事务在 FINAL_FAIL/REFUND 前提交，仍精确模拟 REFUND 已提交后回包丢失 |
| `CashWithdrawEvidenceFixture` | 新增显式 `seedSubmissionIntent`，复用原 seedDebit；不把所有 DEBIT 自动视作已提交 |
| `CashWithdrawStateMachineTest` | 只适配9个渠道查询场景的原 intent 夹具；超时场景让锁下重读看到同一持久旧时间；全部原业务断言与人工纯 DEBIT 夹具不变 |

新增 MySQL 回归（**已写，NOT_RUN**）：

- `processExitBeforeIntentRecoversOriginalThroughTwoPeopleWithoutChannelQuery`：通过完整 `withdraw`，真实 REQUIRES_NEW 扣款提交后注入 Error 绕过 Exception catch，模拟进程在 intent 前退出；换新 Service 实例重查。断言原 status0→3→4→2、同人不许复核、原CW/CD保留、只一张原单/一次DEBIT/一次REFUND/一次余额回补，处理/复核/完成后重复查询均没有 UNKNOWN 和 SDK 调用。Error 注入是确定性进程窗口模拟，不是声称实际 kill 了 JVM。
- `concurrentNeverSubmittedRecoveryAndCheckersPreserveOneOriginalRefund`：两线程同原号恢复，再让两名不同复核人与重查竞争；断言合法首次回补发生且只有一个复核 winner，一笔原扣款、一笔原退款，无 intent/UNKNOWN/渠道调用。
- `recoveryRacingOriginalIntentClaimCannotBothAuthorizeRefundAndSubmission`：同原号恢复与 intent 写入竞争；恢复 winner 可走双人回补，intent winner 必须保持扣款/未知并拒绝回补。查询 stub 同时断言 SDK 查询发生在事务外、原意图已落盘。race 的单次运行可能只命中一个顺序；既有 intent/未知回归及新增纯 DEBIT 回归分别覆盖两个确定性分支，不能只用一场竞争宣称所有调度已穷尽。
- `oldUnknownWithoutIntentRemainsInvestigationAndCannotRefund`：原单有DEBIT+旧UNKNOWN而无intent，NOT_FOUND/UNKNOWN不得恢复成未提交，余额不变、无REFUND，保留调查。

9个只改夹具的已有方法为：`sync_success_marksSuccess`、`sync_success_capturesWechatDetailId`、`sync_fail_claimThenRefundOnce`、`sync_fail_capturesChannelErrorCode`、`sync_fail_lostClaim_noRefund`、`sync_fail_refundClaimLost_failsClosedAndAlarms`、`sync_fail_refundThrows_manualAndAlarm`、`sync_unknown_staysProcessing_noRefund`、`sync_stuckTooLong_escalatesToManual`。静态脚本去掉新增 intent 种子/旧时间重读适配后，逐方法比对冻结 A，确保原断言未变化。

原单元证据套件21→24项，MySQLIT19→23项；这是源码 @Test 计数，**不是通过数**。既有冻结、无扣款、金额/身份不符、SUBMIT_INTENT、旧UNKNOWN、迟到SUCCESS、明确detail FAIL、CLOSED仍未知、余额写失败回滚和提交回包丢失回归均保留。

## 验证、交付和限制

实际轻量输出见 [verification.txt](verification.txt)；可复跑本目录 `verify-static.py` 和原 A 的 `validate-static.py`。前者检查源码查询顺序/负控、门槛不变、9方法断言不变、fixture修复、修改范围和冻结工作树；**不是执行 Java 或模拟数据库替代品**。

本批候选仅修改5个上述 Java 文件及本目录报告/静态检查/输出/哈希清单。`candidate-files.json` 给出除清单自身以外每文件 SHA256；最终 commit/tree 随交付消息提供，供根 fetch 或 cherry-pick。提交前后均检查冻结 A、WP12 工作树不变。新候选未包含根联合分支上的其他编译修复；集成时保留那些独立修复，不能用本候选整文件覆盖联合工作树。

没有新增 schema、依赖、公共接口、RBAC、回执表/资金通用写侧、B 网关或 G 结算变更；没有执行 DDL、MySQL、Java、真实支付或部署。本批仍依赖 A 的资金事实 schema 候选，生产 DDL 未获执行授权；没有 schema 的环境不得把缺事实当作未提交。旧代码已经污染为 DEBIT+UNKNOWN 的历史单不自动“洗白”，需保留原证据调查；本修复只阻止新的健康 DEBIT-only 单被恢复动作污染。

代码回退会重新引入该恢复缺陷。已提交的人工回补及审计不能随代码回退清除或重放；继续沿原 ID/资金事实查证。B 的明确未发送凭证合同仍由 B 提供，本补丁没有借用或伪造该凭证，也没有放宽 NOT_ACCEPTED 门槛。
