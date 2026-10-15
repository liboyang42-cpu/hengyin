# EXTRA6源码交付、单线修复与恢复入口

CY-HANDOFF: CODEX。真实Cloud任务 `01a13f9c-e8ba-753e-9c39-03840bb4900b` / durable，工作树 `/workspace/hengyin-extra-*`，六原worker gpt-6.1-sol。
已读控制8533507d637199e3143356509de3e384a74c57be。分支 `codex/extra-six-rolling-20261015` / [PR36](https://github.com/liboyang42-cpu/hengyin/pull/36)。本协议前完整源码候选 `ff7cddb9093951a5da68a87e033a67bb015cfe66`，待编译/回归，不是可编译通过声明。

历史106项：源码/回归交付55、反证40、阻塞7、其余委派/他队处理；当前READY0/模型执行0，目标剩余50缺50。恢复3项均已完成，额外两个真实caller新范围请求6198338004待C00精确转交，不计为已确认bug或已授权任务。

## 持久调度协议：1修复+5开发

用户最新规则：“他们继续做 做完push就好 你来验证后端 然后有问题告诉他们后让他们其中一条线修复 其他5条线继续往下做”。

每队最多6个gpt-6.1-sol，最多1条线处理本地验证失败，优先原文件作者；换人必须C00先正式移交精确文件。多个失败按优先级排同一条修复线，不开启第二修复worker。无失败时修复线可做普通独占任务。其余5条有实际独立任务就继续，只有真正共享依赖的项等待，不能因测试整队停工，也不能制造工作维持运行数。

完成即push完整SHA，不等测试/CI或本地签收。Cloud双方不新增手动编译/测试/CI触发重跑或专门验证配置，既有自动CI自然运行且门禁保留。实际后端/VPS/数据库由本地验证线程01a13eed集中执行，C00唯一源码/控制账维护者并汇总交付；旧“C00执行验证”讨论已被用户更正覆盖。

失败包必须带候选完整SHA、类/断言、命令/环境、复现、期望/实际、日志和路径/原作者。修复前冻结原开发WIP到独立基线，避免覆盖；修复后回填完整修复SHA、依赖、精确改动和针对性复测命令，由本地对新SHA复测。NOT_RUN就是未运行，source push不是PASS，不继承旧SHA结果。

当前失败队列为空，修复槽待命；实际独立开发任务也已交付完成，空闲如实记账，等待新精确边界不等Mac。

## 启动提示：恢复时先执行

1. 读控制分支最新LONGRUN_CURRENT/START_PROMPT/ROLLING/SOURCE_OWNERS/EXPANSION，再读本目录QUEUE/CLAIMS和PR16新回执；用户更新指令优先。
2. 核各公共完整HEAD、工作树WIP及原writer；已经APPLIED_AND_PUBLIC的prepared_patch仅历史，不能再应用或重做E03-17。共享helper依赖按公共SHA提取，不全文件覆盖CORE最新源。
3. 先取实际失败队列的一项交原作者占唯一修复线，同时派其他真实独占任务；无失败可全做开发。发现表外缺陷先C00原writer或正式精确转交。
4. 每项只改已领取路径，worker不Git/不运行验证；父协调者冻结路径串行小提交、GitHub非强推发布、汇总PR36。Git CLI凭据不可用时用授权Git Data接口并核同tree/parent，不读取凭据。
5. 更新完整SHA/变更集/依赖/类与预期数、NOT_RUN/本地实际结果分开；向PR16交付，由C00汇总给本地。完成即取下项，不因CI/验证待签收而阻塞无关开发。平台时长/额度停止则保留公开恢复状态，不承诺永久运行。

## 本次三项快速验证移交

|项|公共最小提交|新方法|旧方法保留|未来组预期|
|---|---|---|---|---|
|E03-16|`48c39663f63995a4e5365d2d615e40135bdc1ef1`|4|53|57|
|E05-17|`24525416a11f6c2fc24272edf3c13d19cbaa6b05`|5|67|72|
|E02-22|`a8e4c1f5de56c05da8114c9c53e38ec2a5b1adbc`|9|55|64|

三组共193方法（18新+175旧），全部NOT_RUN；这不是实际执行数、通过数或bug验收数。各组精确write_files见QUEUE，独占证据为docs/verification/mini-longrun-20261015/EXTRA6/<ID>.md。

E03-16未来命令文字：

```bash
mvn -B -pl chengyinhub-admin -am -Dtest=E03_16Test,PlayJourneyStoryServiceImplTest,PlayJourneyReadServiceImplTest,SeventhPierFixtureTest -Dsurefire.failIfNoSpecifiedTests=false test
```

E05-17未来命令文字：

```bash
mvn -B -pl chengyinhub-admin -am -Dtest=E05_17Test,ApiPlayEncounterControllerTest,ApiPlayEncounterRoleViewTest -Dsurefire.failIfNoSpecifiedTests=false test
```

E02-22未来命令文字：

```bash
CHENGYIN_E02_22_MYSQL_IT=1 mvn -B -pl chengyinhub-admin -am -Dtest=E02_22Test,CommunityPostServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test
```

E03/E05组无需数据库服务，需要既有JDK/Maven/JUnit/Mockito/Spring资源；共享findCurrent公共producer5162ef45bb42486575619871ff24b4b694941f09，EXTRA精确import42cd374d08676bf570db505a454264d5c4178775，E03分支import8be25aebd839c323a6847f80a2afb99f542fa0d2，保留archive实例隔离642380e194c4830f6350191e3b498679348b435a。

E02真库组须显式CHENGYIN_E02_22_MYSQL_IT=1、六个CHENGYIN_TEST_MYSQL_HOST/PORT/DATABASE_BASE/DATABASE_SUFFIX/USER/PASSWORD，独占非空suffix、MySQL8+非MariaDB、scratch CREATE/DROP/DML权限；读证据核环境前提，没开门禁而skip不能算通过。真实MySQL/Redis只能本地适用批准入口，离线212入口无网络不等于真库通道。原E02-20 nullable PATCH源和对应旧fixture保留，新receipt修复不改变它。

依次领取最小提交而非把EXTRA旧base全文件覆到CORE@2b1984或f7e0。E05证据职责更正独立f01b5c06657cfb654a0cdc86dc77159ac53b6940仅文档，可随包取用。旧冻结候选的结果不能混入本轮新增源；当前公共PR36包含三项及全部依赖，C00可整合到单独冻结的新候选后交本地。

## 队列与精确写权

[QUEUE.json](QUEUE.json)记录全部历史、最新SHA、回归和未来命令；[CLAIMS.json](CLAIMS.json)记录单writer精确文件及同一调度协议。原六路初批PR29–34属于历史，任何早期Node/231VPS成绩仅对应当时SHA，不覆盖本轮。

|ID|任务|状态|
|---|---|---|
|E01-01|排期日期边界归一|NO_CHANGE_SOURCE_REVIEW|
|E01-02|系列更新保留集合时刻|NO_CHANGE_SOURCE_REVIEW|
|E01-03|系列重发身份冲突|NO_CHANGE_SOURCE_REVIEW|
|E01-04|系列到场次作用域读取|NO_CHANGE_SOURCE_REVIEW|
|E01-05|有候补场次结构保护|NO_CHANGE_SOURCE_REVIEW|
|E01-06|候补取消与晋升竞争|SOURCE_DELIVERED_UNVERIFIED|
|E01-07|到期offer状态读回|SOURCE_DELIVERED_UNVERIFIED|
|E01-08|候补令牌归属校验|NO_CHANGE_SOURCE_REVIEW|
|E01-09|候补订单绑定终态|SOURCE_DELIVERED_UNVERIFIED|
|E02-01|帖子详情实时可见性|SOURCE_DELIVERED_UNVERIFIED|
|E02-02|信息流游标与权限过滤|NO_CHANGE_SOURCE_REVIEW|
|E02-03|引用聚合不复活撤回内容|BLOCKED_DECISION|
|E02-04|删除命令重复与版本冲突|NO_CHANGE_SOURCE_REVIEW|
|E02-05|撤回位置后多个读面一致|NO_CHANGE_SOURCE_REVIEW|
|E02-06|评论列表父帖隔离|SOURCE_DELIVERED_UNVERIFIED|
|E02-07|跨帖评论删除关联|NO_CHANGE_SOURCE_REVIEW|
|E02-08|互动重放不泄露不可见详情|NO_CHANGE_SOURCE_REVIEW|
|E02-09|社区媒体回执归属|NO_CHANGE_SOURCE_REVIEW|
|E03-01|已冻结结局优先返回|SOURCE_DELIVERED_UNVERIFIED|
|E03-02|未知旧快照不冒充空结局|SOURCE_DELIVERED_UNVERIFIED|
|E03-03|结局首命中及显式优先级保真|NO_CHANGE_SOURCE_REVIEW|
|E03-04|章节结束正文沿本局快照|BLOCKED_DECISION|
|E03-05|旅程读模型账号和场次关联|NO_CHANGE_SOURCE_REVIEW|
|E03-06|结局章引用重映射完整性|NO_CHANGE_SOURCE_REVIEW|
|E03-07|结局校验未知枚举拒绝|NO_CHANGE_SOURCE_REVIEW|
|E03-08|节点快照显式空与缺席区分|DELEGATED_SOURCE_FIX|
|E04-01|发券库存更新结果处理|NO_CHANGE_SOURCE_REVIEW|
|E04-02|每人限领历史范围|NO_CHANGE_SOURCE_REVIEW|
|E04-03|停发与失效区分|SOURCE_DELIVERED_UNVERIFIED|
|E04-04|奖励来源回执身份冲突|NO_CHANGE_SOURCE_REVIEW|
|E04-05|核销旧状态与重复提交|NO_CHANGE_SOURCE_REVIEW|
|E04-06|券核销撤销身份关联|SOURCE_DELIVERED_UNVERIFIED|
|E04-07|通关券冲正限定原奖励|BLOCKED_OWNERSHIP|
|E04-08|退款券奖励冲正回执重复|NO_CHANGE_SOURCE_REVIEW|
|E05-01|订阅模板去重与三模板上限|SOURCE_DELIVERED_UNVERIFIED|
|E05-02|订阅部分授权状态精确映射|NO_CHANGE_SOURCE_REVIEW|
|E05-03|订单历史退款确认与回包账号代次|SOURCE_DELIVERED_UNVERIFIED|
|E05-04|参与详情退款确认与回包账号代次|SOURCE_DELIVERED_UNVERIFIED|
|E05-05|收藏列表账号切换晚响应|NO_CHANGE_SOURCE_REVIEW|
|E05-06|收藏帖子与主题分页作用域|SOURCE_DELIVERED_UNVERIFIED|
|E05-07|取消收藏身份锁与刷新竞争|SOURCE_DELIVERED_UNVERIFIED|
|E05-08|收藏卸载与重新显示完成回调|NO_CHANGE_SOURCE_REVIEW|
|E06-01|音频重复注册保留中断身份|SOURCE_DELIVERED_UNVERIFIED|
|E06-02|音频手动暂停覆盖自动恢复|NO_CHANGE_SOURCE_REVIEW|
|E06-03|常亮异步失败可恢复|SOURCE_DELIVERED_UNVERIFIED|
|E06-04|poller停止重启在途世代|NO_CHANGE_SOURCE_REVIEW|
|E06-05|poller异常和退避配置边界|SOURCE_DELIVERED_UNVERIFIED|
|E06-06|最近定位异常值与存储失败|SOURCE_DELIVERED_UNVERIFIED|
|E06-07|选址取消拒权回调隔离|NO_CHANGE_SOURCE_REVIEW|
|E06-08|定位owner释放与启动竞态|SOURCE_DELIVERED_UNVERIFIED|
|E01-10|排期聚合读的父系列与场次快照|SOURCE_DELIVERED_UNVERIFIED|
|E01-11|候补维护畸形页的隔离边界|NO_CHANGE_SOURCE_REVIEW|
|E02-10|作者编辑历史父子读取同一快照|SOURCE_DELIVERED_UNVERIFIED|
|E02-11|信息流父行与hydrate子集合快照|SOURCE_DELIVERED_UNVERIFIED|
|E02-12|POI选项与保存状态条件一致性|BLOCKED_DECISION|
|E03-09|首批结局真库夹具的非兜底条件|SOURCE_DELIVERED_UNVERIFIED|
|E04-09|后台原始券历史新增事务原子性|SOURCE_DELIVERED_UNVERIFIED|
|E05-09|订单历史列表同账号卸载晚回包|SOURCE_DELIVERED_UNVERIFIED|
|E05-10|参与详情读取账号与组件实例归属|SOURCE_DELIVERED_UNVERIFIED|
|E06-09|GPS启动API同步异常收口|SOURCE_DELIVERED_UNVERIFIED|
|E06-10|共享定位消费者异常隔离|SOURCE_DELIVERED_UNVERIFIED|
|E06-11|失败通知批次中同key重获身份|SOURCE_DELIVERED_UNVERIFIED|
|E01-12|排期24:00归一与HH:mm既定输入|SOURCE_DELIVERED_UNVERIFIED|
|E01-13|候补加入与售卖活动状态合同|BLOCKED_DECISION|
|E02-13|迟到帖文审核回调的内容版本绑定|BLOCKED_DEPENDENCY|
|E02-14|社区草稿退群再发布的既定成员边界|SOURCE_DELIVERED_UNVERIFIED|
|E04-10|核销期间模板失效当前事实|SOURCE_DELIVERED_UNVERIFIED|
|E03-10|场景剧情结局冻结的跨队最小依赖交接|RESOLVED_BY_OTHER_TEAM|
|E03-11|节点ID重映射producer合同|SOURCE_DELIVERED_UNVERIFIED|
|E01-14|系列成功请求跨上海日重发|SOURCE_DELIVERED_UNVERIFIED|
|E01-15|独立场次取消的作用域和状态读回|NO_CHANGE_SOURCE_REVIEW|
|E01-16|场次取消收尾失败原子性|SOURCE_DELIVERED_UNVERIFIED|
|E01-17|取消通知受众快照不被收尾改写|SOURCE_DELIVERED_UNVERIFIED|
|E02-15|删除编排后置失败整体回滚|SOURCE_DELIVERED_UNVERIFIED|
|E02-16|通知读取的可见性与未读聚合|SOURCE_DELIVERED_UNVERIFIED|
|E02-17|媒体擦除法律保留与共享对象引用|NO_CHANGE_SOURCE_REVIEW|
|E02-18|社区outbox处理行数和失败回执|SOURCE_DELIVERED_UNVERIFIED|
|E03-12|NPC剧情映射保留未知字段与归属|NO_CHANGE_SOURCE_REVIEW|
|E03-13|案件册线索进度沿冻结剧情|NO_CHANGE_SOURCE_REVIEW|
|E03-14|案件册日志重放与初始状态差分|SOURCE_DELIVERED_UNVERIFIED|
|E04-11|批量过期与核销当前状态保护|SOURCE_DELIVERED_UNVERIFIED|
|E04-12|占库成功后券历史落库失败回滚|NO_CHANGE_SOURCE_REVIEW|
|E05-11|订单历史同实例分页请求归属|NO_CHANGE_SOURCE_REVIEW|
|E06-12|定位stop同步回调异常传播|SOURCE_DELIVERED_UNVERIFIED|
|E05-12|返回收藏页的刷新排队|SOURCE_DELIVERED_UNVERIFIED|
|E05-13|订阅模板未知key继承成员过滤|SOURCE_DELIVERED_UNVERIFIED|
|E06-13|选址坐标非法类型|SOURCE_DELIVERED_UNVERIFIED|
|E01-18|新OFFERED活动当前可用性与旧hold释放|SOURCE_DELIVERED_UNVERIFIED|
|E02-19|评论LIKE撤销后不可变重放身份审计|DELEGATED_SOURCE_FIX|
|E02-20|通知偏好不相交PATCH原子保留|SOURCE_DELIVERED_UNVERIFIED|
|E02-21|初次引用选项和快照权限反证|NO_CHANGE_SOURCE_REVIEW|
|E02-22|评论旧动作回执兼容精确转交方案|SOURCE_DELIVERED_UNVERIFIED|
|E03-15|新结局替换前旧引用映射的consumer来源|RESOLVED_BY_OTHER_TEAM|
|E03-16|当前旅程读面opt-in公共当前局选择|SOURCE_DELIVERED_UNVERIFIED|
|E04-13|奖励冲正积分当前锁读|SOURCE_DELIVERED_UNVERIFIED|
|E04-14|核销首读关联锁与后台编辑竞态|SOURCE_DELIVERED_UNVERIFIED|
|E04-15|冲正真实caller事务和锁序反证|NO_CHANGE_SOURCE_REVIEW|
|E05-14|冻结玩法键显式损坏禁止回live|SOURCE_DELIVERED_UNVERIFIED|
|E05-15|分享文本UTF16截断边界|SOURCE_DELIVERED_UNVERIFIED|
|E05-16|分享坐标白名单及读写归一化审计|NO_CHANGE_SOURCE_REVIEW|
|E05-17|Encounter当前局共享helper接入合同|SOURCE_DELIVERED_UNVERIFIED|
|E06-14|分享同足迹重放与50上限反证|NO_CHANGE_SOURCE_REVIEW|
|E06-15|分享撤销与发布交错反证|NO_CHANGE_SOURCE_REVIEW|
|E06-16|匿名分享读取GONE与隐私闸反证|NO_CHANGE_SOURCE_REVIEW|
|E03-17|同一账号旅程归档按实际局实例隔离|SOURCE_DELIVERED_UNVERIFIED|

## 新线索与合同澄清

E01-19已按CORE公共f7e0实际读取证实显式取消DELETE0仍返回失败，精确交原C03（PR16 comment6198507537），不依赖数据库唯一索引或猜重复插入；未改其源、未写新测试、未执行。仅真实新缺陷作为BLOCKED_DEPENDENCY入账，不当可行动项。原leaf范围请求已收窄为原Controller owner修或精确转交。ShopDay RR假设由真实checkin/arrive/photo READ_COMMITTED反证，不作为新任务或随意更改隔离。

E04-07消费源RedemptionRewardClawbackService本就归EXTRA/E04，阻塞现准确为BLOCKED_CONTRACT：发放侧没有持久原historyId、旧日志关联未知；需TopicCompletionCouponReward producer/receipt与旧数据边界，不是再申请消费源，不靠ASC/DESC猜原ID。E02-13同样依赖审核任务内容版本生产者，不能用当前post CAS冒充所审版本绑定。

## 本地474e失败修复接收（单线）

本地回报474e54aea6351856e7e4dd23ab4561932958522d在212/VPS实际34类305项=302通过/1失败/2错误/0跳过，编译通过，exit1 child_command_failed；实际305不同静态304。原结果保留，不套700e。唯一修复线external_e02，其他worker不并修。三个fixture漏stub无回执=null，Mockito Map默认emptyMap先触409；仅修两旧receipt方法与E02_14前提，保403与全部负控，生产不改。修后尚NOT_RUN，未来本地聚焦CommunityPostServiceImplTest/E02_14Test静态56方法计划。具体失败包、路径及状态见QUEUE.failure_repair_queue；不等本地结果才交新源码。
