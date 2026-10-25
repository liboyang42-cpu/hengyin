# T20 固定分域 SOURCE 增量（parent a7eee8e5）

本片保留原 `a7eee8e5b790938a89bc19276c6edf68e9bdfeff` 的报名五时间线、37 个 Python 控制及 1 个 fresh SDK Java guard 测试，原文件正文/断言不改。原 canonical 工具/测试、5b54/后台、生产/迁移、B/F/A 文件、共享 CI/registry 均未改。旧 delivery.md/runner-contract.md/postimages.sha256 是 a7eee 的历史交付；以下为新增能力与最新后像，不把历史 partial 报告当当前组合验收。

**全部 SOURCE / NOT_RUN。** 本轮没有任何测试、编译、DB、HTTP、容器、恢复、故障、CI 或 provider 执行。文件数和控制声明不是 PASS。C 仅交可组合源码，root 同源集合后统一执行。

## 真实依赖（不复制其引擎/生产文件）

- F common 已只读获取：`f874583cab3a05026078ef51a5609644587bd2d9`。`RestoreDispatchGuard.Blocked` 是真实类型，固定 `RECOVERY_DISPATCH_BLOCKED`，不是 NotSent；类加载读取一次环境。C 不改 common、不调用 requireDispatchAllowed 来伪造实际 owner 拒绝。
- B durable 原 turn `01a172d1-4cbd-764f-968d-1b7524fb35f2` 的 8 个 assistant diff，重建原 patch SHA256 **fc9999651e8b7602728af56c67fa599b261e47db3c3cd2351fc24ca7eef24d9b**。仅在 /tmp 只读参考，不放入 C 分支/patch。实际复用 `FundsRecoveryProcessProbe.Store`、GateObserver(NONE)、terminal/wireHash；B 源 postimage：Probe `f136788267fe3b4de607277445557105417a327058170430a46206d53e35daf8`，IT `2e6933723c9e3210f4886ba141ff5757042f619a80ef5aa25c361878ff4ff4e7`。没有执行 B14，也没借其通过。
- 实际消费者已只读获取：F `5d071d3441bd736703608ce0b3a6db4f52fc030b`、A `197ac0ca8f3f98970b4d2701097a3e8da839fdcc` 及各自 README/NOTICE-HANDOFF/共享 review.patch。C 没有应用/复制其生产文件。NpcVoice 的 guard 在 enroll 顶部，恢复时因此只读 status；正常新 boot 才 enroll 原请求。A 的退款 restore callback 在原 registerVerified 后返回 false，因此 fixture 明确断言“暂停且原行不变”，不误断 ACK 成功。
- 此增量 parent 仍为 a7eee，不重叠收录 B/common/A/F。**必须由 root 将上述真实源及已分配共享 hunk 一起组合后构建**；不声称此 donor 单独已可编译。生产调用使用已读真实既有方法签名，未发明构造器或返回 DTO。

## 固定 caller / owner / 分域初态

| 新 SOURCE | 原服务和责任 |
|---|---|
| RestoreWithdrawalReconciliationFixture（impl） | 直接 `new B.Store(source)` / Store.service / 真实 admission、MemberCashWithdrawMapper、两 V1 DTO；只将 provider port 换为外部持久 journal。C 不复制资金引擎。新资金来源真实 withdraw→DEBIT/SUBMIT→ACK→requery SUCCESS；master-only 明示原 TransferIntent9/B 历史 fixture，用真实余额 SQL + 原 snapshot mapper 同事务建立，fund_fact 必须零，不猜成当前新款 |
| RestoreRefundReconciliationFixture（impl） | 沿 RefundRetryAndRemainderMySqlIT 的实际依赖装配，仅调用其既有 paid/save/facts helper，不调用 setup/clear/cleanup 破坏恢复库；真实 saveBackOfficeRefund/review、RefundPayoutService、真实 verified reader/inbox claim+mutation/DONE、原 payment consumption。前置已付订单是该原 helper 的**明示历史 fixture**，不称为新的真实微信付款；原报名五时间线另有真实付款提交。恢复重放只读已保存 refund fact，不再调用 recordVerified 重造事实 |
| RestoreVoiceReconciliationFixture（ai） | 使用原 NpcVoiceEnrollmentMySqlIT 的实际 profile DDL/两原迁移及真实 mapper/service/transaction；按 actor+scope+NPC+requestId+sample SHA+profile version/原 voiceId 关联。已知 voiceId 只查自身持久结果；无 voiceId 的 UNKNOWN 不查 prefix、不构造 provider 查询或成功 |
| RestoreProviderJournal（test only） | 三个固定域的 test provider transport；备份域外 append/fsync/hash 链，真实参数/两种 V1 hash/原退款事实引用/声音原请求按各自合同保存。不是第二账本，不替代真实渠道验签/账单；无任意脚本/URL/profile入口 |
| ci/restore_business_domain_facts.py | 固定六 cohort 的独立 typed rows/原引用/账本/高水位/provider 对照与实际 owner 读回校验；可由 root 调用 summarize_completed_cohorts 汇总三个完备 cohort 的 source/JAR/run/attempt、四独立 boot 与 exact-cleanup 回执。其覆盖字段只针对**列明的 C 方法集**，全域生产派发仍需 F/A 其它消费者证据 |
| 原 ci/restore_business_reconciliation_probe.py 增量 | 不另造启动器。增加固定 child 选择、target owner boot/SQL+provider前后证明、仅完备 cohort 的正常配置新 boot；原备份/恢复调用与原UUID/版本/hash/定义/非空/清理门禁不变 |

## 七个固定 cohort，不能裁剪成空场景

resources.json 仅新增可选 `cohort`。不填仍走原 `payment-gap` 五时间线。其余仅六个常量可选，不能传任意类名/表/SQL/命令：

| cohort | 备份前 / 故障点与恢复差额 | 允许正常 boot？ |
|---|---|---|
| withdrawal-complete | ledger_success：真实原扣款+两独立 V1+原 SUCCESS；master_success：明确历史 master-only 原 SUCCESS，无 fund_fact。其它 actor/赏金/冷静期原行保留；备份后不制造新资金 | 仅恢复 true/非法/空配置三 boot 均真实阻断+独立对账通过后允许 |
| withdrawal-gap | 上述两成功外加 ledger_unknown；备份后真实 lost_ledger_success 的整单/DEBIT/SUBMIT/快照不在恢复库，外部成功与原编号仍在 | 永不自动正常 boot；UNKNOWN/丢失原单/可能 CW/CD 复用始终 BLOCKED |
| refund-complete | 历史已付原 REG99、原 payment consumption、真实审核20.00/68.00退款；受控 store 原 SUCCESS→真实 verified callback/DONE已提交 | 同上，正常 boot 只原号读回/合法已执行回调重放 |
| refund-gap | 原退款已审核/尝试外派，provider UNKNOWN；备份后原同号 SUCCESS，恢复本地仍无 verified refund 终态 | BLOCKED，只保原差额，不自动应用晚成功、不重新 submit |
| voice-complete | 原 NPC555 请求、原样本摘要/owner、首次 provider 原 id 与 READY 均持久 | 同上，原 status/enroll replay不创建新任务 |
| voice-gap | 增加 NPC556 同原请求的 UNKNOWN，原 voiceId 不存在、不可唯一查证 | BLOCKED，不借共享 prefix 找其它声音，不新建/释放占用 |

每个 cohort 独立创建同固定命名规则的 schema；原两 UUID、同版本、空 target、manifest v3、定义/trigger/AUTO_INCREMENT、全量 checksum 校验全部复用。独立 typed capture 保留所选表所有列/NULL/时间/金额/身份；BINARY/VARBINARY/BLOB/BIT 以 HEX 保留原字节，不作 UTF8 替换字符解码；额外读 payment/refund/inbox/提现 fact 原 ID 引用与所有登记 allocator，无法对应即拒绝。不按当前配置回填旧 hash/merchant/app/金额。退款原支付 fixture和提现历史来源在源码/外部记录中分别声明，不混为真实当前 producer。声音外部 journal 留原 admission profile_version，原 finish(PENDING)与finish(READY)确实分别推进本地 projection 一次；读回验证原版本+2及同 profile/voiceId，UNKNOWN 则不推进，绝不把这两个不同阶段版本强作同一数或忽略掉。

## 真实恢复 owner、四次独立 boot

源阶段显式 `CHENGYIN_RESTORE_MODE=false`，正常源 child 退出后才恢复目标。新 cohort 在目标按固定顺序各启动新 JVM：

1. RESTORED_OWNER：`true`。
2. RESTORED_INVALID_OWNER：`invalid-fixture-value`。
3. RESTORED_EMPTY_OWNER：显式空串（按 F 实际合同亦 hold）。
4. 仅完整事实/账本/引用/ID 高水位、前三实际 owner 证据全部通过，才 NORMAL_READBACK：显式 `false`。

不修改运行中 flag、不提供 setter/恢复审批 API；每次实际 PID/startInstant/随机 boot 由 child 返回并与宿主 Process 核对。实际阻断点是：

- `CashWithdrawServiceImpl.withdraw/manualRefund`，以及真实 `WechatPaymentSdkGateway.transferToBalance(original typed intent)`；暂停新扣款和原单返款，不把暂停变成退款资格。
- `RefundServiceImpl.saveBackOfficeRefund`，以及真实 `WechatPaymentSdkGateway.submitRefund(original payment reference, original refundNo/amount)`。
- `NpcVoiceEnrollmentService.enroll`（新 NPC557、合法原 scope/actor）。

每个调用必须原样抛真实 `RestoreDispatchGuard.Blocked` 与原错误码；返回 false/UNKNOWN/NOT_SENT 或其它异常一律失败。直接 SDK facade 仍没有真实凭据/client，因此即使 consumer 漏 guard 也不会付费外呼；但这种返回**不能**冒充成功阻断。恢复入口不是只 fresh WxPayConfig 或只 helper 布尔。

随后实际 owner 读已终态提现、退款原 mapper/status/已核 DONE 回调、声音原 status；恢复 mode 下退款 callback 必须 false 且不抹掉原已核事实/inbox，正常新 boot 必须合法原 DONE replay 为 true。声音恢复 mode 不调用被 F 明确禁止的 enroll，正常 boot 再做原请求重放。host 独立比较完整金融/任务行、原 ID与allocator、外部 journal 原参数/创建次数，拒绝新扣、退、占用变化或重发。查询日志可追加，SUBMIT与原 identity 不许变化。没有通过就没有正常 boot。

refund-gap 还实际走 A 的 `RefundPayoutServiceImpl.observeOriginalDuringRestore`：原同号 late SUCCESS 可经真实 verified service/inbox 登记，但**不得**推进原 refund_application、报名/消费记录、余额/积分或 inbox DONE。reader 只允许精确同退款号/商户/app/币种/原消费引用/原金额的一个 REFUND fact + 一个 WAITING inbox + 原 fact_link 追加；旧事实/所有其它完整行/表类型不得变化，原 binary notify hash 与 link/hash/IDs 逐项核对，新证据ID与各原 next也读回。这个受控证据追加始终保持 gap/BLOCKED，不能进入 normal boot；不是放行任意“新增行”。每阶段完整 before/after 私有行与 provider head/digest均保存，清理后仍可核来源。

退款已核 DONE replay 的原 `registerVerified→INSERT IGNORE` 可能只预留技术 inbox AUTO_INCREMENT。新控制**逐次保存前后值**，仅这张技术表允许 next 向前、MAX及所有原行必须不变；订单/退款/提现/fact 等经济 allocator 不可改。不是忽略编号差异、更不是释放/复用号码。所有原 canonical 恢复校验发生在 owner 调用前，仍要求备份定义完全等价。

每份结果给 `actualOwnerObservations/blockedMethods` 及 `allDeclaredDispatchMethodsFenced`（真实方法集合与所列集合比较）；不硬编码全域 true。`allDispatchDomainsFenced/globalProductionFenceVerified` 不替 F/A 其它非资金/额度/自动恢复 owner 的实际验收签字。完备分域可得到 TEST_OWNED_RECONCILED 与原身份正常读回；gap/UNKNOWN 仍 BLOCKED。两个结果不混淆，不把 C 的6个方法说成全站所有派发消费者。

## 宿主/资源接线差异（root 单写 CI）

复用旧 runner-contract 的两 MySQL/namespace/外部文件夹/默认行为。新的6 cohort 每次 source child + 3 hold boots，完备时再加1 normal boot；每 cohort 最大5 child，串行、固定 heap512m，无 Redis/新依赖/provider HTTP。新 cohort drill deadline900秒（旧报名仍600），outer建议1200秒含精确清理；不同时跑同 pair/scope。root 可同 pair **逐个完成schema清理后串行**执行7 cohort，或按其预算分配独立 pair；不得并发共用同schema，也不能因部分成功跳过失败 cohort。

`run/finalize` CLI、原 `scope/sourceSha/schema/testClasspathFile/source-target cid/uuid/port/defaultsFile` 不变。新增可选 cohort 的值正是上表六个常量，另旧 payment-gap；不含其它可执行参数。公开 JSON 由 root 只收 OUT，private 永不 artifact。run 的完备结果 `businessResult=SCOPED_COMPLETE_FACTS_NORMAL_BOOT_READBACK_VERIFIED`，原缺口仍 `SCOPED_DIFFERENCES_VERIFIED_RELEASE_BLOCKED`；outer exact cleanup 后 finalize 才可出 `result=TEST_COHORT_NORMAL_READBACK_COMPLETE_NO_PRODUCTION_RELEASE,candidateState=TEST_OWNED_RECONCILED`。任一业务/清理/原exit失败仍 FAIL，生产 release 恒false。root 如汇总三完备 cohort，应调用 `summarize_completed_cohorts` 校三份source/JAR/tool/run/attempt与4独立boot及各自finalize，不允许缺consumer、gap或不同source混验。

JAR provenance 已按 admin pom 的真实 git-commit-id-maven-plugin 配置纠正：generateGitPropertiesFile=true，未覆盖输出名，includeOnlyProperties只 git.commit.id；唯一入口 `BOOT-INF/classes/git.properties`，其 git.commit.id须真实完整40SHA等于本次源。不会写新属性、读错 META-INF 后伪造 fallback、或接受短SHA。新增配套控制源码绑定该真实 pom 配置与读取路径。

host/child env 只允许任务身份、JDK路径/语言与明确测试DB凭据，不透传 host token/payment/AI凭据。真实 owner/child 不进正常发布JAR，B child/C journal 也检查不得打包。compiled dirs/dependency JAR 内容摘要、JAR git SHA、两CID/UUID、GitHub run+attempt/cohort均写公开脱敏结果；完整行/原样本引用/provider原记录只在private。

退款旧 POJO 构造时会校验一个未使用的长suite名，因此新 cohort 的环境 DATABASE_BASE 用短的 `cy_restore_<scope前12>`。其 setup/cleanup 从不调用；实际连接始终 C 显式固定完整 schema+port+UUID，reflection只给原 helper 的真实 datasource/reader/manager装配，绝不会连接该未使用suite库。旧报名环境名字保持原样。

成功运行才记录实际 restore point、正常原身份读回可用点和 fixture 耗时/零事实损失窗口；未运行时无 RPO/RTO 值，未来结果也不是生产 SLO。failure/cleanup UNKNOWN 保首因/原私有行；SIGALRM/TERM/INT/HUP 均进入仅 exact Popen children/实际成功创建schema清理，outer精确CID/network独立absence后才finalize。缺创建确认不擅自删schema、不把原非零exit改绿。

## 配套 SOURCE 与当前限制

- 新 `ci/test_restore_business_domain_facts.py`：35 个方法；含三域完备正向、UNKNOWN拒绝、两V1不合并、金额/recipient/引用、技术inbox与经济高水位区别、实际 owner+独立行/provider证明、缺consumer/混source/清理UNKNOWN/用gap替正向不得汇总、精确 late-success evidence可保存但不得消费/假DONE、真实pom/JAR路径、跨cohort/截断/重复provider输入。只声明，**NOT_RUN**。
- 原37 Python方法、旧1 Java方法、原报名5时间线未改；本片没有执行这些或 B14/原64+9。
- B exact source、F common/F实际消费者与A实际资金源码均已读取；须组合到同候选，尚未用运行证明这些调用都会抛期望异常。缺消费者会使测试失败，不fallback为true。root最终代码齐后统一验收。
- 本片声音使用真实 intent/service/Mapper但 provider port 为独立持久double；没有执行真实 AiGateway/VoiceCloneClient quota Redis/各自动恢复consumer。不能用这些结果冒充全部quota/通知/SDK/HTTP/真钱/生产灾备。F/A其它消费者证据仍须root并入完整目标；不造不存在的provider查询/额度证据。
- 依旧没有自动回填缺失原意图、提高allocator、换号、解除UNKNOWN或重发渠道。生产恢复、双人/真实渠道核对、实际预算与RPO/RTO要求均未授权执行，不因本SOURCE的完备正向取消这些门槛。

## 唯一生产共享建议（不在本分支修改生产文件）

`registration-notice-wake.review.patch` 仅给 root/A 串行加入 CmsRegistrationServiceImpl.registerPaymentAfterCommit：在原 signup intent 已校验/登记之后、创建/调度 Runnable 之前，恢复 mode 暂停 wake。没有改金额/权限/原 callback 状态，也不在 CommittedBusinessNotice 内部吞 Blocked 假投递。二维码持久来源是原已付报名与 VerificationQrcodeRepairTask.repairMissing→getPaidMissingQrcodeScanUpperBound/listPaidMissingQrcodeIdsAfter；signup 来源是原 system notification intent/worker。A 的 restore callback 本来在经济 mutation 前暂停，此 hunk 为原通知 helper 的精确防御接缝，不声称 restore 时新创建了经济/通知事实。没有已持久来源的 runnable 不被包装成可恢复。

F/A 文档所列原退款 best-effort WX 没独立 notice ID：本片保留原 refund.id 与已核事实、人工待核；不强造退款模板/outbox/新平台，不赋予 WX重投许可。共享 hunk 尚待 root 应用；C原工作树生产文件保持原样。F shared-phone 与 A adjacent-owners patch 已读但未复制/改写，仍归root与原owner串行合成。
