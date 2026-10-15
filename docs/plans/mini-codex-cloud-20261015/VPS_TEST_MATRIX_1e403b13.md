# 本轮专用VPS最小测试矩阵：1e403b13

CY-HANDOFF: CODEX；CY-COORD-20261015-v1。源码候选固定 **1e403b135206844a34219753a36e2dee39c7dd59**，公共分支codex/mini-bug-arch-integration-20261015。本地验证协调者只运行此公共提交并发布自己VPS_EXTERNAL_*独立证据；C00独占源码/控制账。

以下231为本轮**最小选定的测试方法数，不是已通过数，也不等于全部业务/设备验收完成**。源码只读盘点无参数化/重复/动态测试注解；方法内部循环和并发线程不另算case。不把其余530项旧跳过套件全部扩大成本轮任务。

## 最新冻结候选签收：231/231自动化矩阵闭环

公共源码 **1e403b135206844a34219753a36e2dee39c7dd59**；独立证据[PR24@43ca532](https://github.com/liboyang42-cpu/hengyin/blob/43ca53274b345bf344037e1fe60afa49b8e53dd1/docs/verification/mini-bug-arch-20261015/VPS_EXTERNAL_1e403b13/SUMMARY.md)。C00已完整读取SUMMARY、B03 evidence、regression、ai-storage、resources、cleanup：B03真MySQL7＋普通/H2回归202＋AI真实MySQL/Redis22，实际**231/231，失败0/错误0/跳过0**。逐类数量/方法、XML新鲜度、同一源码无补丁、退出0/无OOM均核；三个Maven容器区间不重叠，任务标签核清理5容器和独占网络、拆除前非系统schema=0及Redis DB0键=0，原日志/报告/源码/依赖缓存保留。

同SHA GitHub CI run38733056178、Funds run38733056167全部SUCCESS（10+2 jobs）；当前签收见[VALIDATION_1e403b13.json](VALIDATION_1e403b13.json)。原69c281af setup失败作为历史保留（7业务未运行），未抹除或混入当前通过计数。此前“202+22待报告”仅是历史进度，已由本签收覆盖。

**本轮冻结候选VPS自动化矩阵已闭环；不等于全轮任务/产品完成。TEST_DEPLOYED=NO，运行时HTTP/真实身份业务读回、应用包SHA/数据库绑定、微信/iOS、合并/业务迁移/部署/上传均未验收。** 后续已确认bug/架构缺口另分支、新SHA和聚焦套件，不沿用231签收，不包括Claude暂缓新产品。

## 顺序与资源

初始顺序为B03夹具复测7项 → 普通回归202项 → AI真实Redis/MySQL22项。三组现已由PR24@43ca532完整证据签收，以下顺序/命令是已执行记录，不要求重跑。串行单个Maven测试容器，Maven堆640MB/测试堆384MB沿原验证驱动；独占任务数据库/Redis、限额、无宿主发布端口。失败就回传真实报告/C00修复新SHA，不在VPS改源码，不重复全量Maven，不让worker争用VPS。

每组从完整仓库根按reactor -am运行，使用当前上游源码、不拿缓存旧项目jar；Java21/Maven3.9.11，MySQL8.0.45及实际Redis版本独立记录。-o仅复用授权环境已备依赖；依赖缺失如实报告，不伪造测试通过。

## 组1：B03真实MySQL，7方法

命令、环境和7/0/0/0 Surefire回读见[VPS_RETEST_1e403b13.md](VPS_RETEST_1e403b13.md)。B03使用CHENGYIN_TEST_MYSQL_*变量：显式HOST/PORT/USER/PASSWORD、DATABASE_BASE和非空唯一DATABASE_SUFFIX，开关CHENGYIN_FUNDS_MYSQL_IT=1。真实库名为 `<base>_b03_assets_it<suffix>`（全长<=64，仅字母数字下划线）。setup先DROP同名库、CREATE，cleanup再DROP，故必须独占随机后缀且仅测试实例；数据库权限含该隔离环境建删库/表及读写。不要把AI的变量名混用给B03。

## 组2：普通源码回归，202方法

| 类 | 期望方法数 | 依赖 |
|---|---:|---|
| TopicDraftLegacyFieldPreservationTest | 5 | Mockito |
| B03PointsAwardServiceTest | 4 | Mockito |
| B04ClubEventScheduleReadbackTest | 6 | Mockito，不是真实排期SQL读回 |
| CommunityPostMapperMyBatisTest | 14 | 自建内存H2，既有测试依赖 |
| TopicRouteRuntimeServiceImplTest | 79 | Mockito/内存逻辑 |
| AiSceneRouterContractTest | 2 | 纯逻辑 |
| AiGatewayServiceImplTest | 50 | Mockito，无真实收费provider |
| NpcChatServiceTest | 21 | Mockito |
| A03EndingWriteCompatibilityTest | 2 | 纯逻辑 |
| A03RuntimeEndingCompatibilityTest | 5 | 纯逻辑 |
| A03EndingSnapshotReadTest | 3 | 快照/Mockito |
| AiQuotaRecoveryServiceTest | 11 | Mockito |
| 合计 | 202 | 无外部MySQL/Redis |

```sh
mvn -B -o -pl chengyinhub-admin -am test \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=TopicDraftLegacyFieldPreservationTest,B03PointsAwardServiceTest,B04ClubEventScheduleReadbackTest,CommunityPostMapperMyBatisTest,TopicRouteRuntimeServiceImplTest,AiSceneRouterContractTest,AiGatewayServiceImplTest,NpcChatServiceTest,A03EndingWriteCompatibilityTest,A03RuntimeEndingCompatibilityTest,A03EndingSnapshotReadTest,AiQuotaRecoveryServiceTest \
  -DargLine=-Xmx384m
```

须逐类从新Surefire XML回读上表数量、failures=errors=skipped=0；不累计其他组留下的旧XML，若测试开关/发现变化造成缺项如实失败。202通过只代表源码测试，不把H2当真实MySQL权限读回。

## 组3：AI真实Redis/MySQL，22方法

- AiQuotaReservationRedisIT：15方法，CHENGYIN_AI_RESERVATION_REDIS_IT=1。
- AiQuotaRecoveryMySqlRedisIT：7方法，CHENGYIN_AI_RECOVERY_IT=1。

两类均**不会自动起Testcontainers**，连接测试JVM所在namespace回环：

- Reservation必须CHENGYIN_AI_REDIS_IT_HOST=127.0.0.1（代码严格校验）、CHENGYIN_AI_REDIS_IT_PORT、CHENGYIN_AI_REDIS_IT_DATABASE（0..15），可选CHENGYIN_AI_REDIS_IT_PASSWORD。
- Recovery MySQL host硬编码127.0.0.1，必须CHENGYIN_AI_MYSQL_IT_PORT/USER/PASSWORD（PASSWORD变量必须存在，可显式空）。它自己CREATE/DROP `chengyin_ai_recovery_<UUID>`，不接受database suffix参数，账户限专用测试实例并具建删库/表/读写权限；读取仓库既有迁移仅作独立fixture，不对业务库执行迁移。
- Recovery Redis host也硬编码127.0.0.1，读取相同Redis PORT/DATABASE/可选PASSWORD，不读取Redis HOST。给独占测试Redis/逻辑库；源码按登记精确键清理，不调用FLUSHDB。
- 单纯把Maven/MySQL/Redis放一个Docker bridge不能满足127.0.0.1。可让Maven及Redis共享**任务专属MySQL容器的网络namespace**（如--network container:<本轮独占容器>），保持无宿主发布端口；验证者按自己资源驱动落实，不修改共享宿主/SSH/安全组，不把Cloud localhost当VPS。若该隔离方式不可用，报告环境前提缺失，不改源码绕过host校验。

只在上述环境全部明确后运行：

```sh
mvn -B -o -pl chengyinhub-admin -am test \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=AiQuotaReservationRedisIT,AiQuotaRecoveryMySqlRedisIT \
  -DargLine=-Xmx384m
```

`*IT`必须点名，默认mvn test不会自动发现；环境开关未设而跳过不能通过。逐类Surefire应15和7、各零失败/错误/跳过，保留报告、版本、退出/OOM观测及源码SHA。该组仅额度预占/补偿/未知恢复存储，不是真实provider调用/最终答案恢复测试。

## 分层保留与未验证范围

- 已有69c281af两条GitHub CI证据保留，新1e403b13测试结果独立记录，不沿用旧SHA结论。原PR24首轮setup失败不删、不写成7项业务失败或通过。
- MySQL/Redis真实IT与源码单测分别签收；即使选定231项全过，也不等于应用已部署/TEST_DEPLOYED或业务READY。验证者不编辑业务/测试源码、控制账和别人的证据目录；失败交C00，由原写者修复后交新公共SHA。
- 运行时业务仍缺：B02真实保存重开/发布读回、B04真实排期/候补SQL及并发读回、B05真实MySQL两身份/旧内容媒体、A01跨设备/丢响应恢复、真实微信审核与provider usage、B01账号切换/退款订阅等。须具已授权测试服务、运行包SHA/数据库目标/测试身份的确证，当前未部署，不擅自启动/发布应用来造证据。
- 已知能力限制仍保留：首次create无持久requestId不能保证丢响应去重；历史缺快照正文不可重建；NPC UNKNOWN不能恢复上游答案；删除屏障/留存和ASR/TTS日额度政策DRAFT。不能拿上述IT通过关闭这些事项。
- 微信开发者工具/真机、原生iOS设备及其代码适配、合并、业务迁移、测试/生产部署与小程序上传均另列。Claude已暂缓G1等新功能不纳入这轮验证；CL2/3/4独立源代码未合入此SHA也不混入。
