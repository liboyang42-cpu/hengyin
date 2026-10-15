# 第三Cloud两条独立开发线（精确授权）

ANALYTICS第三队当前pending_thread，尚未创建/运行。用户由本地协调者给独立启动说明；C00独占全局源账，本地协调者总控新队源码调度与VPS。公共基线`0d63043ad06775bc3dc52fb925620ef36d1081a7`。新队在PR16回真实thread/child/branch/base和逐路径ACK后，清单内授权立即生效，无需再次general批准；原两队不得编辑这些保留路径。两条真实源码开发线，非测试/复核工作，不凑6/50。

- **AN01**：后台`AnalyticsController.java`提供日期的严格日历/格式/双边顺序校验。唯一既有测试`web/controller/business/AnalyticsControllerTest.java`保留两pilot用例并追加funnel用例，不另开早先提到的FunnelDateValidationTest。保留无日期/空白/单边/完整rows响应和原错误形态，不自定统计窗口/62天/分页、不改Vue/Mapper/埋点。
- **AN02**：玩家成长聚合跨主题并发丢更新。独占`PlayerProgressionServiceImpl.java`、`PlayerGrowthMapper.java/XML`及唯一既有PlayerProgressionServiceImplTest.java；真实探索通关链先成长后积分，独立topic锁不保护共同成长行，普通读+整行绝对值更新可覆盖累计里程/打卡。统一该成长聚合的事务当前读或原子协议及初始化，保持等级/连续天数/阈值/同主题幂等。不动积分、共享会员mapper、上层caller或DDL。

精确源码/测试/证据路径、调用链、反例与验收见同名JSON。已核64个OPEN PR所有分页文件清单，实际新包源和既有两测试没有触达；公共master cbcc4426对应4生产blob与当前基线完全相同，未另修。PR69growthcenter前端不等于PlayerGrowth后端冲突。旧P0-48总表ID由用户引用，Cloud未读原表，不夸大为全P048收口。

额外ApiAnalyticsController/AnalyticsEventService/AnalyticsAggregationTask和BizFunnelDailyMapper.xml在旧PR7/11/18、PR4及foundation B有原锁，当前只读，先取得原作者停止/交接再追加。Task实际在admin/web/task，不是system路径。Mapper.java即使无现锁，AN01不需改也不泛授写权；既有埋点专属测试亦保原写者。

Cloud只开发、完成即push/NOT_RUN；实际后端/数据库验证交本地VPS协调者，不等CI/测试补位，不新增手动测试/CI/环境，不合并部署。每个文件只一个child写；失败只占一条修复线，其余真正独立任务继续，无任务如实耗尽。

最高优先最新收口：初始只授权6个源码/既有测试文件（AN01两项、AN02四项），撤销先前提出的两个新增GrowthConcurrency测试路径，尚无实际third writer故未发生并写。新队沿真实外层锁先核候选，反证不改；额外路径只读另逐项申领，不需重复一般开工批准。启动说明由本地在docs/plans/mini-forward-cloud-20261015/独占发布，C00不改其文件。其他两队不得写这6路径，第三队pending_thread无实际运行声明。

独立启动包已由本地实际发布PR70@9f96f7a858866dd2a09cd244f90c6f0ecbac5e88，标记CY-FORWARD3-20261015，4文档已完整读取；F3-STAT-01/STAT对应AN01，F3-GROW-01/GROW对应AN02。仍pending_thread。TASKS中两个new_regression_file字段与最新严格6路径范围的差异已具体回PR70；新文件未授写，既有2测试可直接补回归，不妨碍2源线按6范围启动。
