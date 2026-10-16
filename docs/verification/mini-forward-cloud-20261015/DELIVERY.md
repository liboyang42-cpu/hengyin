# CY-FORWARD3-20261015 交付与恢复

真实任务：`01a140c1-6379-71fd-8711-f0b55889c01b`。工作分支：`codex/mini-forward-source-20261015`。
业务基线：`0d63043ad06775bc3dc52fb925620ef36d1081a7`；计划父：`9f96f7a858866dd2a09cd244f90c6f0ecbac5e88`，仅四计划文档差异。

启动已实际读取四计划文档、根 AGENTS 与最新 LONGRUN_CURRENT / LONGRUN_ROLLING / LONGRUN_SOURCE_OWNERS。
控制分支读回 `a77eaf5edf3ed19a6271bf2b50101817845b6462`，六条既有路径保留给第三队；刷新 56 个开放 PR 全分页文件列表，无交集。原工作树干净。
[PR16 启动 ACK](https://github.com/liboyang42-cpu/hengyin/pull/16#issuecomment-6199048354) 已包含真实 child 与精确 claim。

STAT 唯一写者 `/root/stat`；GROW 唯一写者 `/root/grow`。父协调者唯一维护本目录三文档。
只在现有授权内源码修复、回归编写和 Git 交付。Cloud执行始终 `NOT_RUN`；本地外部验证见最新候选段。Cloud 不编译、测试、lint、业务 HTTP、数据库、设备、手动 CI、VPS、合并或部署，不改门禁/hook。用户当前分工覆盖根 AGENTS 的默认测试及 master 分支要求。

## 本轮重新检查与回归源码修正（覆盖旧EXHAUSTED摘要）

本轮由原作者 `/root/stat`、`/root/grow` 及父协调者重新追当前UI/调用者、事务、Mapper缓存和成长各分支；不是复用旧EXHAUSTED。最新控制源表blob `88fe475c8fcbb13208510fd028b54c71322f54fc` 保留两原writer与两精确回归路径。本轮未发现可在白名单内确认的新生产缺陷，生产新增bug计0；只修一项确定的既有测试夹具时点问题，不凑任务。

**完整回归源码候选：`d592334abee1a379963e7b72a58eec5e0f7bd5ad`；生产源码仍为`bde356f3f0944f4c1a00726fbf821de2267a3eea`。** 该新提交仅修改 `chengyinhub-admin/src/test/java/com/chengyinhub/business/forward20261015/PlayerGrowthConcurrencyMySqlIT.java`，分类F3-GROW-03 / REGRESSION_SOURCE_CORRECTION，原GROW唯一作者已冻结。节点并发用例#1/2/3/4/6原先seed(LocalDate.now())与实际调用取时若跨午夜，streak合法延续却偏离固定预期；现在测试内CHECKIN_DAY固定2030-01-15、service子类仅覆盖既有currentCheckinTime取该日默认时区12:00、seed同一日。所有11方法、断言、锁屏障、真实事务/Mapper与环境gate保留，无sleep、配置、依赖或生产规则变化。

该测试源码依赖bde已存在的取时方法，未编译/执行，NOT_RUN；不把bde的普通28PASS迁为d592成绩。普通两类与全部生产文件未改，未新增普通受影响类；root若需要核新Java测试源码编译，仅经现有212取用 `mvn -o -B -ntp -pl chengyinhub-admin -am -Dmaven.test.skip=false test-compile`（这里只是命令文本，Cloud未执行），编译通过不等于11项运行。无理由重跑旧普通PASS。真MySQL11仍为现有入口覆盖缺口，关闭的新入口方案不恢复。

### 本地编译请求回执（结果待守卫）

本地总控已签收冻结源码 `d592334abee1a379963e7b72a58eec5e0f7bd5ad`，提交现有212的 **test-compile only** 请求：task `cy-vps-forward-d592334a-20261016`，源码包SHA256 `69bcf8ee5c2347030bf81c7f780556b914b94a3225c59b669f65134813ac6a38`，source/cache独占。只核新测试和匿名时钟类编译，不重跑旧28，不执行11项MySQL。初始准入观测共享锁busy，守卫结果尚未收到，状态 `PENDING_GUARD_RESULT`；不推断已开始、已拒绝或已通过，不绕锁。此为总控消息签收，Cloud未访问VPS、编译或测试。身份/券业务决策由原负责队等待用户答复，与已关闭的新验证入口方案分开，不领取到F3。

### 本轮生产分支结论与只读packet

STAT实际UI初始双null/可清空→GET参数→String绑定→Controller→Mapper已重读：funnel双空/blank不建条件、单边独立解析，非blank严格日历/正四位年份，完整反向在Mapper前拒；DATE列用>=/<=无next-day转换。pilot必填、严格解析/正四位、inclusive1–62及原五字段均未见新Controller遗漏。

唯一SQL边界候选：需analytics:list的直接GET pilot `startDate=endDate=9999-12-31` 合法1天，但当前只读 `chengyinhub-system/src/main/resources/mapper/business/BizFunnelDailyMapper.xml:143` 对end做DATE_ADD1day。须实MySQL观察是否越界为NULL/warning并影响指标；尚无HTTP/DB复现，不计确认bug、不改Mapper、不在Controller拒合法日期、截断或拼跨窗指标。真实UI当前只调用funnel，不冒充UI已触发。若获真实证据，由C00路由原聚合Mapper owner；F3写权不扩展。

GROW重读确认写成长表的真实调用仍只有两入口；均REQUIRED→唯一键upsert→FOR UPDATE当前读，upsert/update清会话缓存、锁读flushCache/useCache保护同事务node→topic，未见绕写。node不回写里程，topic不回写节点/等级/streak/日期。普通读缺行仅默认对象，不建行、不加写锁；一次采样/同日/昨日/断日和checkins/5+1不变。

上游两种顺序仍有边界：FULL节点原报名/活动→条件性积分会员锁→成长→徽章；主题/核销原报名/活动等→TOPIC_FINISH→成长→后续奖励可能再锁会员。完整跨域锁序不是F3本轮验收结论，不增加共享会员锁。

RR重复确认packet严格降为conditional / NEEDS_REACHABILITY / confirmed_new_production_bug=false：`TopicCompletionAwardServiceImpl:67` 的DuplicateKey恢复普通 `PlayerBadgeMapper.xml:28` 读若遇既有RR旧视图，理论上可看不到新提交TOPIC_FINISH再抛。直接caller仍只由firstCompletion调成长；上游ApiPlayProgress及TopicRouteRuntime.completeOpening显式RC，CmsRegistration.redeemExploreChapterAndRecord:2990/2999为DEFAULT，grantOnce:44真实topic普通SELECT可建读视图。仓库未见全局强制RC，但实际DB/外部默认未获证，不能将DEFAULT等同RR。

关键正常重叠反证：CmsRegistrationServiceImpl:421/694/799正常购票/自玩都调ExploreAccountTicketGate:17–42，锁同源topic并检查跨activity与ownerType1票；TopicSaleCanonicalV1:144–150限购1，故原先两正常Explore票/自玩+Explore并存向量未成立；同票又有activity→registration锁。历史/人工/过期票例外未证明，停止扩查。现IT#8 mock了topic查询且第二事务无预建真实topic RR快照，不能证明该条件路径已覆盖。只交C00/caller owner判断，不抢写caller或BadgeMapper，不以mock PASS叫实库通过。

### 逐项未验收条件及现有入口

下面11项全部是原 `com.chengyinhub.business.forward20261015.PlayerGrowthConcurrencyMySqlIT`，源码声明仍11、测试数未增加。每项都为 **NOT_RUN_EXISTING_ENTRY_COVERAGE_GAP**：212固定network none；普通Maven默认不选择*IT，backend/funds现有固定IT清单未登记该类；不新增入口、不改CI、不手动重复触发或催批旧方案。

| 既有方法 | 真实MySQL必须观察的验收条件 |
|---|---|
| `twoFirstNodeCallsCreateOneRowAndCountBoth` | 第二写者等待首事务；单成长行，checkins=2、mileage=0、streak=1，FIRST_STEP仅一份。 |
| `existingNodeCallsAccumulateAcrossLevelBoundary` | 初始checkins4/mileage8.50/streak2，结束checkins6/level2，里程和streak不丢。 |
| `nodeAndTopicKeepBothIndependentCounters` | node先取得锁后topic：checkins5、mileage10.75、level2，MILE_10和TOPIC_CLEAR11事实正确。 |
| `topicThenNodeKeepsMileageAndExistingStreak` | topic先取得锁后node：checkins10、mileage10.75、level3，原streak6保留。 |
| `differentFirstTopicsAccumulateMileageWithoutDuplicateGrowthRow` | 首次不同主题并发：单成长行，mileage6.00，TOPIC_CLEAR11/22各一份。 |
| `repeatableReadSnapshotCannotLoseLaterNodeIncrement` | 两侧显式RR；第二事务先普通读旧checkins4再等成长锁，最终checkins6，不按旧快照覆盖。 |
| `differentOuterTopicMarkersDoNotIntroduceGrowthBadgeGapLockCycle` | 两侧显式RR、真实外层marker-first，FINISH22/11并发；mileage14.50，两TOPIC_CLEAR，不新增缺失badge锁环。 |
| `sameTopicOuterUniqueMarkerCallsGrowthOnlyOnce` | 同topic外层唯一marker：一次first=true、一次false，仅一次成长读写，mileage10.75/TOPIC_CLEAR一份；不覆盖第二事务预建真实topic RR快照。 |
| `existingTopicClearLeavesAllGrowthFieldsUntouched` | 已有TOPIC_CLEAR：里程、节点/等级/streak/last日期和原create/update时间均不变。 |
| `badgeFailureRollsBackFirstRowAndCounter` | FIRST_STEP配置读取失败，首次建行与计数整体回滚，无遗留成长行或徽章。 |
| `ordinaryGetGrowthDoesNotWaitForWriterOrCreateMissingRows` | 持写锁时普通getGrowth在有界时间返回；缺行会员999只返回默认对象，不建行。 |

共同必要前提保留但不构成执行许可：MySQL8+非H2/MariaDB、CHENGYIN_FUNDS_MYSQL_IT=1、六CHENGYIN_TEST_MYSQL_{HOST,PORT,DATABASE_BASE,DATABASE_SUFFIX,USER,PASSWORD}显式绑定（password可空但key存在，suffix非空），配置库名前缀≤47+16hexUUID独占schema、批准的建删/DML、基线两表唯一键、真实Spring事务代理。还需实际记录数据库身份/隔离、fresh exact11 tests/0skip/0error/0failure、线程终止和仅自有schema清理。本套没有真实Redis联验，固定测试日也不验证生产JVM/DB日界。

其他精确未验条件：

| 验收项 | 条件/现有入口覆盖 |
|---|---|
| HTTP输入与权限 | 真实authorized GET/null/blank/单边/拒绝格式/旧envelope；普通Java调用PASS不等于live HTTP，当前无本包登记的运行服务入口，不在Cloud启动或部署。 |
| 普通SQL日界 | 真实XML放前日末秒、当日00:00与23:59:59、次日00:00，单日只计当日两条，保dedup/coverage/min/max；缺适用现有MySQL入口，NOT_RUN。 |
| 最大合法日SQL候选 | 对真实MySQL记录version/sql_mode、DATE_ADD返回及warning；不同user-post放9999-12-30/31，两个单日各1、9999-10-31..12-31(62天)应2并保min/max/dedup/coverage；现有mock/BoundSql/H2 Java DATE_ADD别名不能签收，NOT_RUN/只读候选。 |
| JVM/DB日期映射 | 实际观测JVM与DB时区、Date→DATE持久化及午夜两次有效打卡streak；现有普通受控时钟和固定并发测试日不代替，NOT_RUN/覆盖缺口。 |

当前实际写者/活动检查者均0，READY0，新增确认生产bug0，已交回归修正1，条件性只读packet2。C00只登记这些精确事实与原作者锁，本队不改全局账。本轮完成后为EXHAUSTED_AFTER_CURRENT_CALLER_RECHECK；后续真实可修项在原白名单继续，实际失败只开原作者一条修复线。Cloud全程无编译、测试、HTTP、DB、lint、CI或VPS操作。

## 当前有效约束：保持现有入口，不新增

用户已明确决定“保持现有入口，不新增”，由本地总控直接回传。此决定覆盖本文件和先前公共回执中的“新真库入口待批准”：新增隔离MySQL/Redis集成入口方案已关闭，当前没有该方案的待批准动作，也不再催用户批准相同方案。

保留现有212与CI配置；自动CI自然运行，不手动重复触发。普通回归仅在真实新源码或未解失败出现时，由本地按现有212执行。没有适用现有入口覆盖的真MySQL/Redis项，继续标NOT_RUN/现有入口覆盖缺口，不把计划关闭或普通PASS改称真库通过。

这只是约束更新，没有新增开发、服务、入口或补跑任务。冻结源码 `bde356f3f0944f4c1a00726fbf821de2267a3eea`、本地普通28PASS证据及旧候选独立历史全部保留；真MySQL11未执行、真实Redis/运行HTTP/设备业务/集成部署未验边界不变。EXHAUSTED、writer0/READY0/源码阻塞0，Cloud执行NOT_RUN。C00唯一编辑全局账，本队只维护自己的QUEUE/CLAIMS/DELIVERY与本队PR说明。

## 既有生产候选：bde本地普通28实际通过，真MySQL11仍NOT_RUN

最新冻结源码为 `bde356f3f0944f4c1a00726fbf821de2267a3eea`，分支与草稿PR72不变；前一项独立提交 `009664d74bc9a330b14a10e0696dba74e91ea291` 已包含其中。旧候选 `2babbe283ed2bfd2bf94859e0f5a589f12dcf9bd` 的普通24PASS仅保留历史，不能覆盖新候选。本地已对这个新冻结源码完成编译及普通28实际测试，28pass/0failure/0error/0skip；真MySQL11仍NOT_RUN，整体保持SOURCE_DELIVERED_UNVERIFIED，Cloud未运行验证。

### 本次新源码的独立外部证据

已只读签收PR24完整提交 `cae72e05ee668271d79ea9c95e505d7e8971d3a5` 的 [F3 README](https://github.com/liboyang42-cpu/hengyin/blob/cae72e05ee668271d79ea9c95e505d7e8971d3a5/docs/verification/mini-bug-arch-20261015/VPS_FORWARD_bde356f3/README.md)、[summary.json](https://github.com/liboyang42-cpu/hengyin/blob/cae72e05ee668271d79ea9c95e505d7e8971d3a5/docs/verification/mini-bug-arch-20261015/VPS_FORWARD_bde356f3/summary.json) 与 [真库入口缺口 README](https://github.com/liboyang42-cpu/hengyin/blob/cae72e05ee668271d79ea9c95e505d7e8971d3a5/docs/verification/mini-bug-arch-20261015/VPS_DB_ENTRY_GAP_20261016/README.md)。两普通类各14项、总28实际全部通过，2份fresh XML精确类集，212 guard0/completed；原始回执时间字段 `2026-10-16T03:47:03.567744Z`—`2026-10-16T03:49:49.990667Z`。summary记录精确容器 `d8a22f9badbdb11ce65e3b6ef17f8cbc619053b3fdfd2c1e68ac55210c312c0b` 经显式chengyin-aws过滤确认已清理。

Cloud仅核公开README/summary，未拉取或独立解析VPS原始XML/日志，未连接VPS；这是本地唯一验证者的新SHA结果，不是继承旧24PASS。CORE/EXTRA各自结果不计入本队，也不更新他们或C00的账本。

F3真MySQL类11项未登记现有资金CI固定清单，当前NOT_RUN；212固定network none无凭据透传，不能充当真库入口。有Redis mock不能当真实Redis联验。用户已关闭新增隔离入口方案，现有212与CI保持原样，现有入口覆盖缺口继续NOT_RUN；Cloud不创建服务/入口或补跑替代，也不再催批同方案。真库/Redis、运行HTTP、设备业务、候选集成、合并部署均未签收。总体SOURCE_DELIVERED_UNVERIFIED、EXHAUSTED（writer0/READY0/源码阻塞0）不变。

本次只追加原白名单四条既有路径：Controller/Test 与 ProgressionService/Test，不新增文件。两位原作者均再次冻结，实际writer=0、READY=0；当前没有已确认下一具体项，恢复EXHAUSTED。下文恢复开发及旧验证段落均为历史，当前状态以本节和QUEUE为准。

### F3-STAT-02

原作者 `/root/stat`；完整源码提交 `009664d74bc9a330b14a10e0696dba74e91ea291`，仅 `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/AnalyticsController.java`、`chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/business/AnalyticsControllerTest.java`。

触发：pilot的LocalDate.parse接受零年、负年、符号扩展年份，完整1天窗口仍把原串传SQL DATE cast。仅解析后/窗口计算前追加正四位年份检查，不重构funnel、不改Mapper/UI；合法年份、必填、1–62inclusive及start/end/dedup/coverage/metric五字段保留。没有实测HTTP或SQL返回，不把预期转换问题称生产已复现。

既有12个测试完整保留，新增2个@Test，共14声明：12个非法年份短窗，均错误码500/固定日期格式消息且Mapper零交互；3个合法0001/0999/9999短窗，完整核原参数与五字段。案例组合不是执行数。本地本候选对应普通类14实际通过，Cloud NOT_RUN；无新依赖/窗口政策。真实SQL转换及接口仍由总控验证。

### F3-GROW-02

原作者 `/root/grow`；完整源码提交 `bde356f3f0944f4c1a00726fbf821de2267a3eea`，仅 `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/PlayerProgressionServiceImpl.java`、`chengyinhub-admin/src/test/java/com/chengyinhub/business/service/PlayerProgressionServiceImplTest.java`。

触发：锁读后先LocalDate.now算today，后new Date写lastCheckinDate，在午夜间线程停顿可用15日计算streak却存16日，16日下一有效调用被当同日，少延一天。现在锁读后一次采样checkinTime，toLocalDate沿原ZoneId.systemDefault算日期；lastCheckinDate/updateTime共用采样。取时方法为最小protected生产new Date，便于测试受控时间；没有新配置/依赖，未动成长锁、计次、等级、主题里程、徽章时间或阈值政策。

既有12个测试完整保留，新增2个确定性@Test，共14声明。子类给定午夜前/后队列并模拟Mapper持久化，断言单次streak7与存储日期一致，以及两次有效调用streak6→7→8、checkins9→10→11/level3，无sleep或源码字符串断言。本地本候选对应普通类14实际通过，Cloud NOT_RUN。Date→DATE映射及JVM/DB时区集成仍未验，未选择新时区或改事件身份。

### 新候选的验证交接

当前AnalyticsControllerTest14 + PlayerProgressionServiceImplTest14 + PlayerGrowthConcurrencyMySqlIT11 =39源码声明（本次净增4，公共基线起净增34），本地本候选普通28项已实际通过，0失败/错误/跳过；真MySQL11项仍NOT_RUN。原MySQL IT文件未改，11项始终未执行。仍需要本地独占入口及原两唯一键/事务代理前提，资源与真实MySQL执行门禁不变。

普通用例选择示例（只写文本，Cloud未执行）：

```bash
mvn -o -pl chengyinhub-admin -am -Dmaven.test.skip=false -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -DforkCount=1 -Dtest=com.chengyinhub.web.controller.business.AnalyticsControllerTest,com.chengyinhub.business.service.PlayerProgressionServiceImplTest test
```

普通28已由本地按实际28签收，非声明推算或旧成绩转移；真实MySQL11因现有入口覆盖缺口保持NOT_RUN，不再等待新增入口批准，不能以普通PASS或声明数签收；不再以文档head替代源码冻结SHA。日期全历史性能、共享会员锁序与完整奖励并发仍是原交接边界，不新增共享表或目录写权。未来真实失败仅对应原作者一条修复线；其余独立授权项可继续。

## 历史恢复开发记录：原两域两项具体续领

用户再次要求继续。原分支父 `6f0e2b076d029dd527e1b873bfea9d10ea4e3dc0`，业务起点和计划父均未换。刷新201开放PR（三页100+100+1）及各自完整文件列表，六既有白名单交集仅本队PR72；最新SOURCE_OWNERS blob `8508fc7aedb13a393f7eeea1914a9926f4f0465a` 仍给原两写者，无新写者冲突。

- F3-STAT-02：`/root/stat` 只改原 Controller/Test。pilot 日期解析仍接受零年/负年/符号扩展年份，1天窗口可传SQL DATE cast；仅拒非法年份，保留合法日期、必填、1–62inclusive和原响应，不改funnel/Mapper/UI。
- F3-GROW-02：`/root/grow` 只改原 Service/Test。分别取today与lastCheckinDate跨午夜时，可将15日计算的streak存为16日，次日被当同日而少延一天；锁读后统一采样时点，沿原默认时区与连续打卡/等级/奖励规则，补确定性受控时间回归。

均为源码级确认触发，未运行HTTP/DB/测试；新增回归NOT_RUN，不把旧候选普通24PASS继承给新补丁。当前实际源码writer=2，仍仅原两域，不新增路径或模块，不凑六条。完成一项即独立提交push，不等验证；父协调者只维护本目录三文档。下文EXHAUSTED为此前范围完成时的历史状态，本节覆盖当前活动状态。

## 历史外部验证：旧2babbe28普通24项通过

本节覆盖下文源码首次交付时的“全部 NOT_RUN”历史表述；Cloud 自己仍未运行任何编译或验证。冻结业务源码继续为 `2babbe283ed2bfd2bf94859e0f5a589f12dcf9bd`，当前仍 `EXHAUSTED`（writer=0、READY=0），总体保留 `SOURCE_DELIVERED_UNVERIFIED`，不作完整业务验收。

本地总控实际 VPS212 完成编译及精确两个普通类：`com.chengyinhub.web.controller.business.AnalyticsControllerTest` 与 `com.chengyinhub.business.service.PlayerProgressionServiceImplTest` 各12项，合计24实际测试=24pass/0failure/0error/0skip；2份fresh XML类集合与选择精确相等，guard exit0/completed。原始结束时间字段为 `2026-10-15T20:47:21.589299Z`。

已只读核对公共证据提交 `fbcf8e0ed40e226552c3c90eb068b2b34a76bc0f` 的 [README](https://github.com/liboyang42-cpu/hengyin/blob/fbcf8e0ed40e226552c3c90eb068b2b34a76bc0f/docs/verification/mini-bug-arch-20261015/VPS_FORWARD_2babbe28/README.md)、[summary.json](https://github.com/liboyang42-cpu/hengyin/blob/fbcf8e0ed40e226552c3c90eb068b2b34a76bc0f/docs/verification/mini-bug-arch-20261015/VPS_FORWARD_2babbe28/summary.json) 与 [早先准入拒绝](https://github.com/liboyang42-cpu/hengyin/blob/fbcf8e0ed40e226552c3c90eb068b2b34a76bc0f/docs/verification/mini-bug-arch-20261015/VPS_FORWARD_2babbe28/admission-refused.json)，其中包含实际212命令、源包/日志/XML哈希和精确类结果。Cloud 未直接取得或独立重解析 VPS 原始XML/日志，不把公开摘要读取当成再次执行。早先 another_build_holds_lock（exit75、containerId null、执行0项）是资源准入事件而非源码失败；正常释放后才完成本次执行，历史证据保留。

`com.chengyinhub.business.forward20261015.PlayerGrowthConcurrencyMySqlIT` 的11项仍 NOT_RUN。212离线入口network none；既有资金CI固定类清单也不覆盖本套，此为历史入口缺口；现行决定为保持现有入口、不新增，未覆盖项继续NOT_RUN。下文MySQL命令仅为测试选择示例，不能视为入口已获批准或已执行。真实并发、运行中后端HTTP、真实DB/Redis、设备/业务验收、合并部署均未签收。Cloud不补跑替代，未来真实失败仅恢复对应原作者一条修复线。

## 当前进度（源码交付记录）

- F3-STAT-01：已公共交付并冻结，`add1534cd63c6ffd95467795126246c8d3581594`，SOURCE_DELIVERED_UNVERIFIED / NOT_RUN。
- F3-GROW-01：已公共交付并冻结，`2babbe283ed2bfd2bf94859e0f5a589f12dcf9bd`，SOURCE_DELIVERED_UNVERIFIED / NOT_RUN。
- 公共源码交付 SHA：STAT `add1534cd63c6ffd95467795126246c8d3581594`；[草稿 PR72](https://github.com/liboyang42-cpu/hengyin/pull/72)，base 为计划分支。GROW 完整提交 `2babbe283ed2bfd2bf94859e0f5a589f12dcf9bd`；该 SHA 是两项合并后的冻结源码候选，后续仅交付账本更新不需要按新源码重复运行。

## 恢复

先读本目录 QUEUE/CLAIMS/DELIVERY 与 PR16 最新反馈，核远端分支和工作树，不能覆盖 WIP。保留两 lane 单写者；本地失败仅一条原作者修复线。范围完成后交接候选，不能自行扩域。公共源码交付均标 `SOURCE_DELIVERED_UNVERIFIED`，待本地总控集中验证。

## F3-STAT-01 源码交付

原因：funnel 原样转发任意非 blank 日期且未拒反向区间。只改 AnalyticsController.java 与 AnalyticsControllerTest.java，Mapper/UI保持只读。补丁在 Mapper 前解析合法日历、正四位年份并拒反向；保留 null/清空/blank/单边、原响应、完整 rows，无新增最大跨度或分页。pilot 生产逻辑不变。

测试类 `com.chengyinhub.web.controller.business.AnalyticsControllerTest`：原 2 声明保留，新增 10，共 12 个 `@Test` 源码声明；非法日期 15 值×4端组合共60个断言组合，并非实际执行数。覆盖闰日/同日、0001–9999四位年、长区间150行同对象、空值/单边和pilot 1/62/63天边界。全部 NOT_RUN。无新依赖、无跨lane依赖；全历史查询规模保护范围外，仍待本地编译和真实执行。

## F3-GROW-01 源码交付

唯一原作者 `/root/grow`，冻结提交 `2babbe283ed2bfd2bf94859e0f5a589f12dcf9bd`，SOURCE_DELIVERED_UNVERIFIED / NOT_RUN。本项与 STAT 无直接源码依赖，公共父链保留两项独立小提交。

精确五路径：

- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/PlayerProgressionServiceImpl.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/PlayerGrowthMapper.java`
- `chengyinhub-system/src/main/resources/mapper/business/PlayerGrowthMapper.xml`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/PlayerProgressionServiceImplTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/forward20261015/PlayerGrowthConcurrencyMySqlIT.java`（TASKS 指定 new_regression_file；未另扩目录授权）

### 调用者与约束反证

`ApiPlayProgressController.java:2999` 调节点成长；外层 READ_COMMITTED 事务按报名/活动/节点保护，跨主题不能证明整个玩家串行。积分锁仅在实际发正积分且未发过时成立，策略跳过时不能充当共同锁。`TopicCompletionAwardServiceImpl.java:91` 是主题成长唯一实际调用者：先插 `TOPIC_FINISH_<topic>`，既有 `uk_member_badge(member_id,badge_code,source_id)` 裁定首次事实，只有 firstCompletion 才调用成长，故同主题本已有外层去重。

`sql/base_schema_20260818.sql` 的 player_growth 为 InnoDB，已有 `uk_member(member_id)`。未新增表/索引/迁移。成长 Mapper 的实际累计写者仅这两个服务入口；只靠原普通读后更新，跨主题或节点与主题交错仍会覆盖累计与无关字段，首次普通查后插也会争抢唯一键。

### 最小修复与兼容

两入口采用 REQUIRED 事务延续调用者边界；`ensureGrowthRow` 使用现有唯一键 upsert，重复时只作 member_id 自赋值，不刷新旧累计或时间。随后 `selectByMemberForUpdate` 当前读，同玩家写入串行，且绕开 RR 旧成长快照；锁查询 useCache=false/flushCache=true。先 upsert 再锁读已有行，避免缺失成长行的 gap-lock 后争插。异常向外抛出，建行后读不到行明确失败，不返回未持久化默认行。

节点 sparse 更新只含打卡数、等级、连续天数、打卡时间和更新时间；主题只含里程及更新时间，均不回写无关旧字段。保留每次有效节点调用计次、checkins/5+1、现有 LocalDate/default timezone、章节里程及配置驱动徽章规则。普通 getGrowth/批量等级查询不加锁、不建行。

锁序：原上游报名/积分会员或 TOPIC_FINISH 事实锁 → 成长行 → 成长徽章写入，持有至外层提交。保留 TOPIC_CLEAR 普通 guard；没有对缺失徽章新增 FOR UPDATE，避免 RR next-key/gap 锁与外层先写不同主题事实造成额外锁环。同主题实际调用仍由外层唯一事实负责。

### 待执行回归

- `com.chengyinhub.business.service.PlayerProgressionServiceImplTest`：原 3 项断言保留，必要 fixture 对齐锁读；新增 9，共 12 个 @Test 声明。
- `com.chengyinhub.business.forward20261015.PlayerGrowthConcurrencyMySqlIT`：新增 11 个 @Test 声明，真实 MyBatis XML、Spring 服务事务、从既有 base_schema 提取 player_growth/player_badge 两表及真实唯一键。

GROW 合计 23 声明/净增 20；与 STAT 合计 35 声明/净增 30。全部 NOT_RUN，执行数未知，不称通过。IT 覆盖首次竞争、node/node、双向 node/topic、跨主题累计、显式 RR 旧快照、真实 TopicCompletionAwardServiceImpl 外层不同 marker 锁序及同 topic 唯一 marker、既有徽章不改字段、失败整体回滚、普通读不等待写锁；非成长奖励协作者 mock，不声称全奖励集成已验。

IT 必须由本地在批准的临时 MySQL 8+ 环境执行：`CHENGYIN_FUNDS_MYSQL_IT=1`；显式绑定 `CHENGYIN_TEST_MYSQL_HOST/PORT/DATABASE_BASE/DATABASE_SUFFIX/USER/PASSWORD` 六变量（密码可空但绑定不可缺，suffix 非空）。配置生成的库名前缀≤47字符，追加16位 UUID 唯一后缀，需要临时建删库权限；只建自己的独占 schema，从未在 Cloud 连接数据库。线程异常通过 Future 返回，屏障/等待有界，未终止线程时禁止删 schema 并保留供总控排查。缺 opt-in 的条件跳过不是通过。

普通用例仅按现有批准入口选择；`PlayerGrowthConcurrencyMySqlIT` 选择文本不代表现有入口适用，当前仍为NOT_RUN/覆盖缺口且不新增入口；本文件未执行任何命令，不能以源码声明数代替新鲜 XML 报告。

### 依赖与剩余风险

无新库依赖，无 DDL、共享会员/积分/资金 Mapper、DTO/接口、CI/pom/hook 或 UI 修改。依赖原数据库两唯一键存在，并通过 Spring 代理调用事务入口。

缺 TOPIC_CLEAR 配置时不伪造幂等徽章；人为绕过唯一实际 caller，在外层预建 RR 旧快照后裸调用同主题重放，不新增幂等保证。现有节点/主题上游会员与成长的锁顺序差异仍可能要求整事务回滚重试，本补丁不声称消除全部奖励事务死锁或完成数据库验收。

## 范围收口与总控下一步

两项已交付，两个 child 均冻结，实际源码 writer 0，READY 0。没有新增授权范围，未凑六条或额外任务。总控按冻结源码候选 `2babbe283ed2bfd2bf94859e0f5a589f12dcf9bd` 验证；后续仅账本文档提交不替换该源码 SHA。

后续可读线索仅作为总控待裁定事项，不领取新路径：全历史漏斗规模保护需要产品/API合同；`ApiPlayProgressController.java` 与 `TopicCompletionAwardServiceImpl.java` 的既有会员/成长锁顺序需要完整奖励路径证据及原所有者精确移交。没有把这些线索命名成已复现缺陷。验证失败只占对应原作者一条修复线，其他已授权独立任务可继续。PR 不合并、不部署，所有执行由本地总控统一完成。

## 总控接收补充：EXHAUSTED 与待执行命令

总控已实际读取 PR72、确认源码冻结 `2babbe283ed2bfd2bf94859e0f5a589f12dcf9bd` 与此前文档 head `8ea63a07498d3d7fa5d389211ca9c918dc93c3a8` 的区别，并已将 PR 补挂本地任务。本次仅更新本队 QUEUE/DELIVERY，业务源码仍完全冻结，不产生新的验证候选。

当前授权范围为 `EXHAUSTED`：两项已交付，实际 writer=0、READY=0，没有已核实的下一具体独占项。保留原作者 `/root/stat`、`/root/grow` 的恢复身份与精确 claim；不主动验证，不擅扩模块或凑50。总控反馈真实失败时仅唤醒对应原作者一条修复线，其他获得独立授权的工作可继续。

下列只是供本地总控按已批准入口取用的命令文本；Cloud 从未执行。普通套件预期24个静态声明，真库套件11个静态声明，合计35；实际执行/跳过/错误必须以冻结源码的新鲜报告确认，不能用声明数量签收。

普通回归（从仓库根目录；离线依赖与资源限制仍由本地批准入口统一管理）：

```bash
mvn -o -pl chengyinhub-admin -am -Dmaven.test.skip=false -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -DforkCount=1 -Dtest=AnalyticsControllerTest,PlayerProgressionServiceImplTest test
```

历史真实 MySQL selector示例（当前现有入口覆盖缺口、NOT_RUN，不代表新的执行许可；只有适用既有授权入口才可取用；先显式提供 `CHENGYIN_FUNDS_MYSQL_IT=1` 与上述六个测试连接变量，不在文档填写凭据，不指向生产）：

```bash
mvn -o -pl chengyinhub-admin -am -Dmaven.test.skip=false -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -DforkCount=1 -Dtest=PlayerGrowthConcurrencyMySqlIT test
```

真实 MySQL 门禁前提、独占建删库及线程清理要求见上节。条件跳过不是通过；普通24与真库11只计算各自声明，不把60个非法日期断言组合计作额外测试。未运行编译、测试、业务 HTTP、DB、lint 或手动 CI，状态继续 `SOURCE_DELIVERED_UNVERIFIED / NOT_RUN`。
