# CY-FORWARD3-20261015 交付与恢复

真实任务：`01a140c1-6379-71fd-8711-f0b55889c01b`。工作分支：`codex/mini-forward-source-20261015`。
业务基线：`0d63043ad06775bc3dc52fb925620ef36d1081a7`；计划父：`9f96f7a858866dd2a09cd244f90c6f0ecbac5e88`，仅四计划文档差异。

启动已实际读取四计划文档、根 AGENTS 与最新 LONGRUN_CURRENT / LONGRUN_ROLLING / LONGRUN_SOURCE_OWNERS。
控制分支读回 `a77eaf5edf3ed19a6271bf2b50101817845b6462`，六条既有路径保留给第三队；刷新 56 个开放 PR 全分页文件列表，无交集。原工作树干净。
[PR16 启动 ACK](https://github.com/liboyang42-cpu/hengyin/pull/16#issuecomment-6199048354) 已包含真实 child 与精确 claim。

STAT 唯一写者 `/root/stat`；GROW 唯一写者 `/root/grow`。父协调者唯一维护本目录三文档。
只在现有授权内源码修复、回归编写和 Git 交付。验证全部 `NOT_RUN`；不编译、测试、lint、业务 HTTP、数据库、设备、手动 CI、VPS、合并或部署，不改门禁/hook。用户当前分工覆盖根 AGENTS 的默认测试及 master 分支要求。

## 最新源码工作：原两域两项具体续领

用户再次要求继续。原分支父 `6f0e2b076d029dd527e1b873bfea9d10ea4e3dc0`，业务起点和计划父均未换。刷新201开放PR（三页100+100+1）及各自完整文件列表，六既有白名单交集仅本队PR72；最新SOURCE_OWNERS blob `8508fc7aedb13a393f7eeea1914a9926f4f0465a` 仍给原两写者，无新写者冲突。

- F3-STAT-02：`/root/stat` 只改原 Controller/Test。pilot 日期解析仍接受零年/负年/符号扩展年份，1天窗口可传SQL DATE cast；仅拒非法年份，保留合法日期、必填、1–62inclusive和原响应，不改funnel/Mapper/UI。
- F3-GROW-02：`/root/grow` 只改原 Service/Test。分别取today与lastCheckinDate跨午夜时，可将15日计算的streak存为16日，次日被当同日而少延一天；锁读后统一采样时点，沿原默认时区与连续打卡/等级/奖励规则，补确定性受控时间回归。

均为源码级确认触发，未运行HTTP/DB/测试；新增回归NOT_RUN，不把旧候选普通24PASS继承给新补丁。当前实际源码writer=2，仍仅原两域，不新增路径或模块，不凑六条。完成一项即独立提交push，不等验证；父协调者只维护本目录三文档。下文EXHAUSTED为此前范围完成时的历史状态，本节覆盖当前活动状态。

## 最新外部验证：普通24项通过，真实MySQL仍未运行

本节覆盖下文源码首次交付时的“全部 NOT_RUN”历史表述；Cloud 自己仍未运行任何编译或验证。冻结业务源码继续为 `2babbe283ed2bfd2bf94859e0f5a589f12dcf9bd`，当前仍 `EXHAUSTED`（writer=0、READY=0），总体保留 `SOURCE_DELIVERED_UNVERIFIED`，不作完整业务验收。

本地总控实际 VPS212 完成编译及精确两个普通类：`com.chengyinhub.web.controller.business.AnalyticsControllerTest` 与 `com.chengyinhub.business.service.PlayerProgressionServiceImplTest` 各12项，合计24实际测试=24pass/0failure/0error/0skip；2份fresh XML类集合与选择精确相等，guard exit0/completed。原始结束时间字段为 `2026-10-15T20:47:21.589299Z`。

已只读核对公共证据提交 `fbcf8e0ed40e226552c3c90eb068b2b34a76bc0f` 的 [README](https://github.com/liboyang42-cpu/hengyin/blob/fbcf8e0ed40e226552c3c90eb068b2b34a76bc0f/docs/verification/mini-bug-arch-20261015/VPS_FORWARD_2babbe28/README.md)、[summary.json](https://github.com/liboyang42-cpu/hengyin/blob/fbcf8e0ed40e226552c3c90eb068b2b34a76bc0f/docs/verification/mini-bug-arch-20261015/VPS_FORWARD_2babbe28/summary.json) 与 [早先准入拒绝](https://github.com/liboyang42-cpu/hengyin/blob/fbcf8e0ed40e226552c3c90eb068b2b34a76bc0f/docs/verification/mini-bug-arch-20261015/VPS_FORWARD_2babbe28/admission-refused.json)，其中包含实际212命令、源包/日志/XML哈希和精确类结果。Cloud 未直接取得或独立重解析 VPS 原始XML/日志，不把公开摘要读取当成再次执行。早先 another_build_holds_lock（exit75、containerId null、执行0项）是资源准入事件而非源码失败；正常释放后才完成本次执行，历史证据保留。

`com.chengyinhub.business.forward20261015.PlayerGrowthConcurrencyMySqlIT` 的11项仍 NOT_RUN。212离线入口network none；既有资金CI固定类清单也不覆盖本套，真实MySQL的批准执行入口由本地另确认。下文MySQL命令仅为测试选择示例，不能视为入口已获批准或已执行。真实并发、运行中后端HTTP、真实DB/Redis、设备/业务验收、合并部署均未签收。Cloud不补跑替代，未来真实失败仅恢复对应原作者一条修复线。

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

本地可按已批准 Maven 入口选择 `AnalyticsControllerTest,PlayerProgressionServiceImplTest` 普通用例与显式 `PlayerGrowthConcurrencyMySqlIT` 真库用例；本文件未执行任何命令，不能以源码声明数代替新鲜 XML 报告。

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

真实 MySQL 回归（仍由本地批准入口；先显式提供 `CHENGYIN_FUNDS_MYSQL_IT=1` 与上述六个测试连接变量，不在文档填写凭据，不指向生产）：

```bash
mvn -o -pl chengyinhub-admin -am -Dmaven.test.skip=false -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -DforkCount=1 -Dtest=PlayerGrowthConcurrencyMySqlIT test
```

真实 MySQL 门禁前提、独占建删库及线程清理要求见上节。条件跳过不是通过；普通24与真库11只计算各自声明，不把60个非法日期断言组合计作额外测试。未运行编译、测试、业务 HTTP、DB、lint 或手动 CI，状态继续 `SOURCE_DELIVERED_UNVERIFIED / NOT_RUN`。
