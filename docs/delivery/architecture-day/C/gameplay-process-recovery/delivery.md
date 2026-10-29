# C：真实章节 HTTP 命令的独立进程终止/原身份恢复 SOURCE

结论：新增可执行入口、身份/控制夹具和配套控制测试源码已交付，**Node/Python/Java/DB/HTTP/CI/编译/故障注入全部 NOT_RUN**。没有部署或调用供应商。这不是全 T13、Q1–Q10 或整个 remaining_acceptance_groups 验收完成。

## 基线、已读规范与已有能力

- 父：`fa5c263440573992701263d6bee60830844a49b8`；独立分支 `codex/architecture-day-c-gameplay-process-recovery`，工作树 `/workspace/hengyin-c-gameplay-process-recovery`。
- 已读 `ceb4e5f923091dc0fc5e5b6b7670d60c27bff85f` 的 `COORDINATOR/governance-scope.md` 与 `ACTIVE-WORK.json`：Q1–Q10、WP13–WP17、T13/T17/T19/T21及完整 remaining_acceptance_groups。后三组未关闭不代表前面全部目标已完成。
- 原 `GenericGameplayHttpE2eIT` / Freeze / Reuse 的26/8/2及所有源未动。它们已有真实 HTTP、成员/匿名/领队负例和原 AppLoginService fixture；本片不重写发布/采用/玩法测试。
- 原 `ci/authenticated_redis_recovery_probe.py` / shell 已有 canonical fresh schema、server UUID、JAR git.properties、内部网络、精确容器标签/CID、凭据私有文件、网络观测和 only-owned 清理。本片直接 import/reuse，不复制 MySQL/Redis创建器、迁移器、清理器或原管理员租约业务探针。
- 未发现原入口已有同一业务命令在独立物理进程被终止前后以原 actor/token/input/commandId 查回和重放的这条闭环；不能把同 JVM 关 context 或 Redis租约实验冒称该证据。

## 精确文件与宿主接口（给 root/F 串行装配）

| 文件 | 职责 |
|---|---|
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/it/GameplayProcessRecoveryChild.java` | 独立 JVM main，仅 test-classes；完整 RuoYiApplication、安全 filter、真实 Controller/Service/事务/Mapper；AppLoginService 创建真实 fixture JWT；test-only MVC观察提交前/真实事务返回后，绝不生成成功业务响应。 |
| `ci/gameplay_process_recovery_probe.py` | 原精确 owned fixture 的 app-only adapter；3边界物理SIGKILL/重启、原JWT原命令读回、独立SQL/脱敏报告及给F的真实业务曲线adapter。无建库/迁移/删除库、网络或Redis操作；不复制F统计。 |
| `ci/test_gameplay_process_recovery_probe.py` | 11个同域控制测试源码，拒绝错误宿主、伪物理退出、存储重启、回执身份/终态漂移、字段丢失、72意图复用及未清理就签通过；不代替真实HTTP/DB进程实验。 |
| 本目录 `authenticated-launch.review.patch` | 仅给 root 的原 shell 两处共10行接线；未直接修改共享脚本。唯一 app 启动前选择本入口，原 EXIT trap 完整结束后调用scoped finalize；默认旧路径不变。 |

精确调用：`python3 -I ci/gameplay_process_recovery_probe.py run NAME SCHEMA OUT PRIVATE_CREDENTIALS`。这里只是接口说明，本轮没有执行。

- `NAME=cy-auth-<32hex>`；`SCHEMA=cy_e2e_auth_<同32hex>`；原 private `credentials.json` 必须0600、schema/serverUUID一致，目录不可为符号链接。
- 保持原 workflow/job/repository/event/hosted/Docker socket gate，额外显式 `CHENGYIN_CI_GAMEPLAY_PROCESS_RECOVERY=true`。原 auth gate 标记同样必须由批准的 hosted owner 提供。不能在Mac/任务VM/VPS用环境伪装运行；不新增workflow。
- root仅在需要该模式的同源hosted入口应用 review.patch 并设置显式标记。默认旧 auth 模式完全不变；不得将新模式标成旧退款租约已通过。
- 先由 root 同候选受控构建正常 JAR 与 `target/test-classes`。host拒绝正常 JAR及嵌套依赖携带child，校验嵌入完整gitSHA/原schema-summary的JAR摘要，记录主child/它的编译嵌套class树/source摘要。只逐字节复制固定child及其`$`嵌套class到out/gameplay-child-classes，额外挂载给Boot原PropertiesLauncher，**不将全体测试类加入扫描，不新增测试HTTP端点、生产开关或发布依赖**。每次重启再核JAR与child树不变。F/root仍需将class树摘要绑定同一编译产物回执，文件摘要本身不是编译来源证明。
- 初始 app 名必须不存在；不替换其他进程。只创建与旧启动器同名/标签的唯一 app，固定原JDK image、1CPU/1GiB/512MiB heap、只读文件系统/256MiB tmp、内部网络/无端口发布。沿原 trace 与网络 verifier。总墙钟900秒、单HTTP上限100秒、启动等待150秒/次，所有恢复使用同一CID/同一JAR/配置/namespace。
- 新入口只做原启动器的 app 分支，不另外创建/重启/删除MySQL或Redis，也不拥有外层资源清理。原 shell 仍是唯一宿主/清理owner。
- `finalize NAME SCHEMA OUT ORIGINAL_EXIT` 在原清理和private目录删除后调用；原退出非0、三边界缺项/伪退出、不同CID、日志缺失或独立NoSuch缺失均不能签PASS。运行主体900秒软预算，shared hunk外加920秒TERM/20秒KILL硬边界，退出后原清理仍执行。

## 真实链与唯一效果

选定既有 `ApiClubLeadController.unlockChapter`：POST `/api/club/lead/unlock-chapter`，原 actor由真实 app token取得；form参数为 `activityId/commandId/expectedChapterId/targetChapterId`。原 Controller事务原子提交 `ClubLeadMapper.unlockChapterCas` 与 `GameSessionRuntimeMapper` 的 `LEAD_UNLOCK_CHAPTER` APPLIED回执。读回为 GET `/api/club/lead/unlock-chapter-receipt`，授权仍先于回执读取。

fixture只准备成员/俱乐部/章节/活动/行进状态这组前提，不宣称发布、集合或收费链已通过。A为原合法俱乐部owner/当前领队，B为独立合法成员；通过原 UmsMemberMapper + AppLoginService 生成 JWT/Redis会话，没有直接设置 principal、删权限或构造JWT。原token只在私有0600文件中；重启不再登录、不换epoch、不重建seed。首次seed中断不清数据自动重来，保留失败。

| 边界 | 实际控制/证明 | 重启后的合同 |
|---|---|---|
| BEFORE_TX | 完整安全过滤后的仅test MVC interceptor，在真实Controller事务前停住原命令；独立SQL必须显示原完整state行未变且无原回执。 | 原读回仍520，**520本身仍UNKNOWN**；只有受控事务前边界+SQL共同证明未执行，fixture才用完全相同actor/token/ID/input执行一次。 |
| COMMITTED_NO_REPLY | 仅test ResponseBodyAdvice观察原 Controller原方法返回；强制当前无活动事务；SQL必须已有一次真实CAS和原APPLIED回执，尚未发送业务响应。 | 原token/原ID直接读回原已提交结果；原命令重放不改变任何state/原回执行。 |
| ACKED | 首次真实HTTP响应已完整收到，且与原DB回执相等，然后物理终止。 | 原身份读回/重放仍是首次结果，不再前进。 |

每次只对已核CID/标签/网络的 app 发SIGKILL；核 /proc 中确切JVM命令摘要、Docker wait137、State.ExitCode137、非OOM和物理停止事实，再restart同一容器。新ready必须具有不同bootUUID及原source/JAR/class/config摘要。MySQL/Redis PID、StartedAt、CID及network ID必须全程不变；不以PID存在、线程慢或Context关闭当物理退出证据。

每个边界恢复后都用B/匿名读取和重放A原键，必须拒绝且无旧data泄露；同键改变target或原expected必须409；改变activity也拒绝。独立SQL比较全部club_lead_state/game_command_receipt/business_audit_event，避免只查A原行而漏掉B或别场次写入。原资金事实表保持全行一致。之后用独立合法新命令推进第三章，再读回/重放第一原命令，仍保留原结果而不跳章。

原receipt scope、actor、request_hash、action/perspective/outcome、完整result_json与行数均核对。输入指纹按真实服务 `activity:actor:expected:target` 计算；原request ID为 `lead:sha256(commandId)[0:48]`。独立SQL无MyBatis缓存；不放宽金额、时间、字段或丢失行。哈希保留Decimal scale/类型、日期类型和bytes；报告只存摘要，不存原SQL行/输入/secret/JWT。

## 证据、清理与NOT_RUN

未来执行生成 `gameplay-process-recovery.json`：完整source/JAR/test-source/test-class-tree/config摘要，原业务ID/actor和token摘要、每边界old/new boot、进程命令摘要/退出码/恢复时间，原receipt ID/摘要、完整事实摘要及资金零变化。失败标 `FAIL_OR_UNKNOWN_NO_RETRY`，不假报APPLIED。

运行主体终态最多是 `BUSINESS_ASSERTIONS_PASSED_CLEANUP_PENDING`；finalize只有核对原外层清理及退出后才能置 `PASS_SCOPED_GAMEPLAY_PROCESS_RECOVERY`，保留原businessResultBeforeCleanup。**root必须同源读取原外层 `authenticated-redis-recovery.json.resourceCleanup` 和applicationLogPreserved，精确核对 app/MySQL/Redis/network原ID的only-owned清理；CLEAN缺失/未知即整体验收不成立。**旧命名报告在此mode只提供外层清理，不能当旧auth业务或新业务成功证明。失败回执保留；private字段仅按五个确切自有文件删除，未知.new/多余文件使清理失败保留，不递归删除。外层原cleanup负责日志脱敏、精确资源删除及独立NoSuch回读，无全局prune/通配kill/flush。

配套11个控制测试方法见源文件；正向未来恢复case恰为上述3个边界，另含各自权限/同键异语义/跨目标/实体后续变化负控。**所有测试源码本轮未运行；没有Java编译/容器/HTTP/数据库/故障注入执行，不继承原36或旧auth/pool绿色。**

## 同资源真实业务 workload 给 F/root

root新提供的 F `ci/capacity_mixed_probe.py` 尚不在本父源；本片不整份复制它，也不假设其已通过。C提供 `observe_core_workload(identities, http, rows, run_curve, stop, evidence=None)`，其中 `http` 是现有child内curl真实HTTP、`rows`是原mysql_relay上独立autocommit SQL，`run_curve`直接传F原函数。依root随后核实的真实签名调用：`run_curve(items, request, concurrency, stop, seconds=45)`；stop是物理终止而非采样，未把F的Probe套成通用业务fixture。

- 只有显式 `CHENGYIN_CI_GAMEPLAY_CAPACITY=true` 才要求root已组合F源码并启用本adapter；否则清楚记 `NOT_REQUESTED_EXPLICIT_MODE_REQUIRED`，不暗示容量已测。
- child仅在该模式准备固定72个独立场次/原命令；原3恢复场次不复用。既有完整schema/同app/同Redis/同source/JAR/config保持，最多75场/225章/75主题、2个fixture成员、1个club，无新库或启动器。
- F `run_curve` 每次恰24条 `(kind, fixtureKey)` tuple，调用 `request(*item)`返回category/elapsedSeconds记录；仅原并发1/3/6。每级先24个独立真实首次章节命令，再24个读回/重放/拒绝（各8）；共72首次+72后续=144真实受保护HTTP。
- 每批独立SQL核初态、原一次CAS、原APPLIED唯一回执和原指纹；后24请求不能改任何游戏state/receipt/event，资金原事实不变。`run_curve` 即使吞异常/漏调用也会被本adapter的计数及SQL条件拒绝。固定源不以返回“成功”或统计样本数代替业务效果。
- 45秒阶段deadline的stop回调只终止已核CID/标签/network的同一app，核wait137/非OOM；阶段token和guard阻止迟到旧stop清理新阶段。超时另外记 `capacityDeadlineStop`，绝不计入原三恢复边界。SQL只将真实原APPLIED标已执行，缺回执/未读到仍UNKNOWN且保留原身份，绝不推成NOT_SENT或重启自动重发；部分事实及首因保留，SQL查证失败不能覆盖先前F/stop故障。
- 未另写采样或统计；延迟明确包含Docker exec和owner guards，不能冒充server-only latency或全部容量曲线。F原Probe的pool/Redis/lease采样保持独立owner/scoped。
- adapter后沿root给定签名调用F `observe(name,schema,out,credentials,relay_port)`；原外层清理后沿给定签名调用F `finalize(name,schema,out,original_exit)`，不新建resources。F的actor/dict216请求不是本业务容量证据，二者报告不能互签。
- C容量模式已各调用F一次；root串行接shared shell时不得再落入默认F的重复observe/第二次finalize。原exit原样交F，任何非0保持失败；主业务首因与cleanup首因分栏保留。
- root已核F实际源并明确：observe先合法login，原FINANCIAL_TABLES空表闸保留；mixed自己要求refund表空，自己只建原无cash/manual/never-submitted id20并跑原CLAIM/Redis暂停/RELEASE及整行证明。C不再seed该行，也不重跑旧auth/lease；调用F前独立核refund表仍空、真实原receipt可读/app健康。同CID/JAR绑定，F observe前后只核其自身Pid/StartedAt窗口相同；不把前三次物理重启说成F窗口sameprocess。前置不成立直接失败/NOT_RUN并保留首因，不能删C演员/改资金行迎合闸门。

## 必须保留的局限

- 本条命令无runtime epoch入参；验证的是原 `expectedChapterId` 和activity/member scope，报告明确 `runtimeEpoch=NOT_APPLICABLE_NO_SUCH_INPUT_ON_THIS_COMMAND`，不能把多余epoch字段被忽略说成拒绝旧runtime。高级玩法/个人局/共享局epoch恢复需各自原合同入口另验，本片不扩Controller。
- 这是章节玩法状态一次效果，无真钱、积分/库存授予、AI/地图/通知供应商动作。禁用profile+内部网/网络观测确保无真实外呼；不能替代provider UNKNOWN、真实收费、混合故障Q4、容量曲线Q8、灾备RPO/RTO或全部T13/T17/T19/T21。
- 使用同源当前JAR重启，不声称旧版本JAR回退兼容，也不执行生产DDL/迁移回退；原canonical builder/迁移验收仍由原owner负责。
- 新fault观察仅位于test classpath，正常JAR不带它；若将来确需生产不可控边界，不应把本fixture改为默认生产开关。
- 完整 owned patch由候选commit提供；shared review.patch必须root单独串行应用。逐文件后像在postimages.sha256，摘要文件不自哈希。源码/摘要和模式可执行性不是运行签收。
