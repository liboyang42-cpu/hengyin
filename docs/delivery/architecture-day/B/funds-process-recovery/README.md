# B：资金物理子进程恢复 SOURCE 包

Parent：`fa5c263440573992701263d6bee60830844a49b8`。本片只新增两个 test-classpath Java 文件及本说明；旧资金、内容采用、生产服务/Mapper/SQL、C/G/H/E、CI/registry/DAG 全部不改。

状态：**SOURCE_ONLY / ALL_EXECUTION_NOT_RUN / NOT_ACCEPTED**。没有编译、Java/JUnit、MySQL/Redis、HTTP、Node/Python、CI或真实 provider 执行。方法数不是通过数。

## 固定入口与交给 root 的装配

- JUnit：`com.chengyinhub.business.service.impl.FundsProcessRecoveryMySqlIT`，**14 条 @Test**，另有生命周期方法；不加条件跳过。
- 子 JVM：`com.chengyinhub.business.service.impl.FundsRecoveryProcessProbe.main(String[])`，固定无 argv。不是 Spring profile/fixture Controller/任意命令入口；只在 test classpath。
- 沿现有 SchedulerWorkFenceTwoProcessMySqlIT 的 Java-home/Surefire-classpath/ProcessBuilder 模式启动。它的通用调度/锁探针不证明资金；本片不复制 scheduler，也不改变其原 3 例。
- C5b54677 的 GameplayProcessRecoveryChild 是真实 MVC 章节链，不是资金入口。这里复用“子进程及同源字节身份必须绑定”的纪律，不扩写它的 CI/HTTP脚本。
- 必需环境：Linux/JDK21、`RUNNER_ENVIRONMENT=github-hosted`、`B_PAYMENT_INTENT_DDL_APPROVED=1`、新增的显式 `B_FUNDS_PROCESS_RECOVERY_APPROVED=1`；`CHENGYIN_MYBATIS_IT_HOST=127.0.0.1`、PORT/USER/显式 PASSWORD；`GITHUB_SHA` 完整40位。缺失先失败，不连其他地址、不使用默认凭据。14例固定最多62次child启动、15个计划SIGKILL边界，单例120秒上限、同类串行；无自动重试。此为root将来安排运行的明确资源预算，不是本轮执行。
- 原 repo 定位 `architecture.a.repo`（与原资金 IT 一致，默认模块目录的 ..）。不允许传任意 JAR/profile/SQL/handler。
- 父创建每例随机 `cy_bfunds_<32hex>`，无 IF EXISTS/先删旧库；精确 CREATE 成功后才标 owned。原 fixture + 原 fund_fact、canonical transfer_intent、compat8列三条 migration 按原顺序应用。只在新 schema 建 4 张 b_funds_* 控制/provider 表，非生产 schema 提案，不新增 migration。
- 子进程收到父传的专用 schema/server UUID/nonce/boot/source/hash 等固定测试绑定。DB凭据只在受控 child env；不进入命令行、公共 JSON 或摘要。child env 清空后只放这些明确项。root 独占未来 CI/批准门/登记/预算，本片没有新运行或 workflow。

## 真实 owner、事务与控制边界

当前 API 原样使用：`withdraw(Long,BigDecimal)`、`requery(Long)`、`manualRefund(Long,String,String,Integer)`。无 commandId/额外 expected 参数，不给生产接口发明协议。只初始调用一次 withdraw；后续新进程用原服务器 withdrawalId/CW/CD 查证/人工恢复，从不靠新 withdraw/new identity 掩盖旧单。

Store 使用原 5 个 XML 和真实 Mapper、DataSourceTransactionManager/事务代理、WithdrawalBalanceAdmissionService、WithdrawalFrozenAmountResolver锁内来源、CashWithdrawServiceImpl、UmsMemberServiceImpl余额写入。profile 只读适配器及短事务阶段 wrapper 是 test-only Mockito；wrapper 调用真正 admission，既不伪造 Admission 结果也不替代 SQL/事务。故障装饰器在原 mapper 的**真实 INSERT 后**阻塞；进程死亡后由 MySQL 回滚，非 throw 模拟死亡。原 64/9 case 不提取、不删改。

阶段 gate：BEFORE_DEBIT、DURING_DEBIT_TX、DEBIT_COMMITTED、SUBMIT_COMMITTED、PROVIDER_ACCEPTED、ACKED、NOT_SENT_COMMITTED、QUERY_CAPTURED、DURING_REFUND_TX。事务内 gate 仅用于真实本地事务物理中断；控制记录走独立 control 连接，不再取得任何 business X锁。provider 入口明确断言不存在资金事务；没有持业务锁做 SDK。

短事务提交与 SUBMIT_INTENT 之间是原真实本地恢复窗口；SUBMIT_INTENT 已提交即使尚未进入 provider，也不能从 fixture 的“未调用”反推生产 NOT_SENT。该阶段重启只查原单，NOT_FOUND保持 UNKNOWN。ACK 只表受理，不是到账。

受控 provider 接收原 typed DTO，requireValid/requireMatches，验证真实原 SUBMIT_INTENT或原 master-only snapshot、两个独立 V1 hash及同 wire。test-only持久请求记录有原source/hash/wire_hash和每次原调用，不默认 noop/success。SUCCESS/FAIL查询只有已有同原wire的持久 controlled acceptance才返回完整merchant/app/batch/detail/fen/openid/batchStatus/detailStatus事实；NOT_FOUND/UNKNOWN不伪装终态。真实 NOT_SENT 用原 WechatPayServiceImpl/SDK gateway 缺 client binding 路径，原返回hash/notSent/ambiguous逐项核对，SDK mock必须零交互，不能让 provider stub 自称“肯定未发”。

## 14 条源码场景

| 方法 | 真实断点与原断言 |
|---|---|
| killBeforeAdmissionHasNoDebitAndNeverCreatesAReplacementEconomicIntent | 原单/CW/CD已持久，尚未 admission；kill/reboot后余额100、无DEBIT/intent/退款/派发，原ID调查，不新建 |
| killInsideRealDebitTransactionRollsBackBalanceFactBindingAndSnapshotTogether | 原余额UPDATE+DEBIT绑定+snapshot真实INSERT后尚未commit；kill后通过原行/余额锁当前读屏障确认回滚；无部分持久化 |
| committedDebitBeforeSubmitRestartsIntoOriginalTwoPersonRefundExactlyOnce | 真实 admission REQUIRES_NEW提交，未SUBMIT；新boot原号转双人3→4→2，同人拒绝、重复无二补 |
| committedSubmitBeforeProviderStillRemainsUnknownAfterPhysicalRestart | SUBMIT和snapshot.submitted提交后尚未provider；重启NOT_FOUND不回补、不重发、不生成NOT_ACCEPTED |
| providerAcceptedBeforeLocalAckSurvivesNotFoundAndLateSuccessWithoutResubmitOrRefund | controlled acceptance已独立提交、本地ACK前kill；NOT_FOUND→UNKNOWN→迟到完整SUCCESS；余额70、一次提交、无退款 |
| acknowledgedOriginalIsReadBackAfterKillAndRebootWithoutASecondDispatch | 原ACCEPTED回执与batch已落，ACK gate kill；新boot确认SUCCESS同号同hash |
| acceptedOriginalFinalDetailFailureRefundsOnceAfterPhysicalRestartAndKeepsBothV1Hashes | 已受理ACK前kill，原明细完整FAIL；新boot自动回补一次，重复不改原receipt，不当NOT_SENT |
| realPreSdkNotSentProofSurvivesKillAndAuthorizesOnlyOriginalTwoPersonRecovery | 原SDK前拒绝证明/NOT_ACCEPTED同原hash已提交，自动claim前kill；新boot仅双人恢复一次，无provider query/submit |
| killInsideActualRefundTransactionRollsBackCreditFactAndTerminalCasBeforeNewChecker | 真实REFUND fact+终态CAS+余额credit后、commit前kill；独立读仍4/70，死亡后完整回滚，新checker只补一次 |
| originalAutomaticNoSendRefundSurvivesAckKillAndCannotBeRepeatedByManualRecovery | 自动回补已commit后的ACK kill；新boot重查/人工不得二补 |
| staleOriginalAutomaticPathCannotOverwriteANewProcessesManualRefund | 原自动线程在NOT_SENT commit后持有真实进程；独立boot先恢复/双人回补；旧线程放行不得二补/覆盖receipt |
| oldUnknownQueryCannotReverseSuccessCommittedByASeparateBoot | producer先物理kill；旧query子JVM捕获UNKNOWN，另一boot写SUCCESS，旧query放行后全行终态不变 |
| preIntegrationMasterOnlySnapshotSurvivesPhysicalQueryLossWithoutInventingLedgerProvenance | 明示历史 master-only fixture；真实余额SQL和原snapshot Mapper同事务，沿原V1及历史merchant/app/remark，绝不造fund_fact或关联；query gate kill后原snapshot恢复 |
| independentlyValidV1SourcesWithContradictoryDebitSnapshotsBlockRecoveryWithoutChoosingAWinner | test-only producer装饰器将另一组仍合法的101→71 snapshot送原真实INSERT；真实DEBIT仍100→70。kill/reboot必须精确ORIGINAL_SOURCE_CONFLICT、全行不变，不按currentconfig选赢家/回补 |

最后两条是**显式历史来源/受控损坏输入**，不称为当前正常 producer 的合法双写结果。master-only路径借原9例里 submitted() 的真实事务/余额SQL/快照来源；不是直接插DEBIT/SUBMIT_INTENT，也不是冒称本轮production admission只写一侧。矛盾路径只在新test装饰器改变原INSERT入参，不更新/删除既有不可变事实，不修改生产方法。

## 物理身份、原 SQL 与清理证据

父只操作自己 ProcessBuilder 返回的 Process handle。记录pid/startInstant/parentPid/完整固定argv/boot/source；child独立回写pid、实际parent/start/hash，同进程身份不得重复。到达精确原单gate且child仍活着才允许destroyForcibly；waitFor及Linux exit137证明SIGKILL，不接受fixture异常退出代替。故障死亡后取得原withdraw行及原余额的实际FOR UPDATE锁，作为MySQL回滚/连接锁释放屏障。后继每次都是新的 Process/boot，不用AssertionError/closeContext冒充OS退出，不结束Surefire父进程、其他服务、MySQL或Redis。

identity.json记录实际源码/class/同包inner-class/XML/原DDL的SHA256、源码GITHUB_SHA与server UUID；实际class来自JAR则附该JAR hash，exploded classes就如实记录class字节，**不是原部署JAR的转签**。源码SHA/字节身份不能当测试通过。

门控及终态通过独立Jdbc连接读取原提现全行、intent全行、fund_fact全行、余额与manual maker/checker/audit列；拒绝前后比较全金融行，不只mock调用次数。原CW/CD/金额/owner和两套请求hash分别持久读回。其他actor余额和所有赏金/冷静期行保持原样。JSON公开层记录全行摘要、必要脱敏状态/原ID/hash；原SQL读取不是HTTP200/日志一句。

每例initial/gate/final JSON、原controlled provider调用记录、processes.json及cleanup.json放 `target/funds-process-recovery/<source>/<run-uuid>/`。stdout/stderr原件只作隔离运行诊断，root在公开前审脱敏；凭据env不输出。child保留原失败阶段/固定故障码/脱敏cause类型与栈行/SQLState/vendor code及原message摘要，不输出原SQLException message或配置；原JUnit失败仍由Surefire XML决定，cases的SQL_CAPTURED_NOT_JUNIT_VERDICT明确不是PASS。失败后仍尝试所有exact children和本任务真正CREATE成功的schema清理，即使schema初始化未完成/没有owner表；不能因报告缺失免除清理。先证所有owned children死亡、原server相同，后精确DROP自己随机schema，再用独立admin连接查information_schema不存在。任一失败保留首因/补充清理错误及CLEANUP_UNKNOWN，不把本地标记当不存在；未知旧schema不被下一例覆盖。

## 明确未覆盖/待统一执行

- 全部14例只有源码；编译、真实执行数、实际kill/rollback、独立SQL和CLEAN均 **NOT_RUN**。未来仅root批准的hosted环境同候选执行，缺验证不是生产BUG。
- provider是test-classpath持久double；不是微信协议验收、真钱/真实退款/渠道/UAT，不覆盖HTTP鉴权或完整前端人机流程。
- 只资金提现原owner与余额回补边界；不宣称原全部WP02/03/13/17或T01/02/03/05/13已通过，不把C章节进程恢复/通用调度3例当资金证据。
- 当前提现无独立lease/fence/commandId端口。本片核原身份、status/CAS/expectedStatus和旧观察者不能覆盖；不发明新epoch字段，不改生产调度/权限/金额/UNKNOWN政策。
- root待登记本类14条及必要门控/env/artifact收集；本片不碰任何共享CI、SQL/DAG或registry。预算和整批统一回归由root安排，不能继承旧fa5c/资金64/TransferIntent9的绿色。
