## 最新bde候选普通28项通过

新源码`bde356f3f0944f4c1a00726fbf821de2267a3eea`两普通类各14/合计28实际PASS，0失败/错误/跳过、fresh精确2XML、guard0及精确清理已核读[公开证据](https://github.com/liboyang42-cpu/hengyin/blob/cae72e05ee668271d79ea9c95e505d7e8971d3a5/docs/verification/mini-bug-arch-20261015/VPS_FORWARD_bde356f3/README.md)。11真MySQL仍NOT_RUN，未合并部署；旧2bab24PASS不迁移。本轮实际Java验证已结束，后续新源码另报。

### 以下为旧候选历史

## 第三队当前普通回归签收：24PASS／真MySQL11未运行

第三队PR72冻结源码 `2babbe283ed2bfd2bf94859e0f5a589f12dcf9bd` 正常准入后，由本地专用VPS212完成七模块编译与两普通类24/24实跑PASS（各12项，0失败/错误/跳过），fresh XML精确类集、guard0/completed和容器清理已确认。C00已完整读回[PR24公开README及summary](https://github.com/liboyang42-cpu/hengyin/blob/fbcf8e0ed40e226552c3c90eb068b2b34a76bc0f/docs/verification/mini-bug-arch-20261015/VPS_FORWARD_2babbe28/README.md)，证据提交 `fbcf8e0ed40e226552c3c90eb068b2b34a76bc0f`，逐类XML、源码与日志哈希已入总控索引。

早先exit75/another_build_holds_lock/containerId=null/执行0的准入拒绝作为历史保留，不再是当前普通回归状态。11项真实MySQL仍NOT_RUN：212离线network none不能替代真库，现有资金CI固定类清单不覆盖这11项，尚无可核批准入口，不擅改CI或绕过资源。真实数据库/Redis、运行时HTTP、设备、业务、合并部署仍未验收；普通PASS不证明并发问题已验收。

EXTRA下一本地串行候选 `ed598258c0f614385940f326099b9626531fab02` 单独登记：两新修复及受影响原类共11类，75仅源码声明，尚未回执行结果，不由排程推断已开跑，不继承e72两类56PASS。C00只更新全局账，第三队自行补自己交付账链接；Cloud不补跑。第三队两个当前域EXHAUSTED/writer0，不凭空扩任务。

### 以下包含早先排队与拒绝准入历史，以本节当前结果为准

## 第三队验证准入纠正：NOT_RUN，等待共享锁

第三队PR72冻结源码 `2babbe283ed2bfd2bf94859e0f5a589f12dcf9bd` 尚未编译/测试：212守卫因 `another_build_holds_lock` 返回75、containerId=null，本次执行0项，保持 SOURCE_DELIVERED_UNVERIFIED / NOT_RUN。两普通类24与真MySQL11均只是源码声明；资源排队不算源码失败或通过。本地等待正常准入，Cloud不替代验证、不绕锁、不干预其他任务。第三队当前两域EXHAUSTED、实际writer=0，不凑6线或扩产品。

C00已完整读回PR24@8cfa51c099de362540d67cff71225e1ef778d6a4的[README](https://github.com/liboyang42-cpu/hengyin/blob/8cfa51c099de362540d67cff71225e1ef778d6a4/docs/verification/mini-bug-arch-20261015/VPS_FORWARD_2babbe28/README.md)和admission-refused.json，已替换先前“执行中”记录。CORE157、EXTRA56针对性通过及各自旧失败历史维持独立，不转给第三候选。

# 第三Cloud两条独立开发线（精确授权）

ANALYTICS第三队真实thread和两child已收到启动ACK，实际归属如下。用户由本地协调者给独立启动说明；C00独占全局源账，本地协调者总控新队源码调度与VPS。公共基线`0d63043ad06775bc3dc52fb925620ef36d1081a7`。新队在PR16回真实thread/child/branch/base和逐路径ACK后，清单内授权立即生效，无需再次general批准；原两队不得编辑这些保留路径。两条真实源码开发线，非测试/复核工作，不凑6/50。

- **AN01**：后台`AnalyticsController.java`提供日期的严格日历/格式/双边顺序校验。唯一既有测试`web/controller/business/AnalyticsControllerTest.java`保留两pilot用例并追加funnel用例，不另开早先提到的FunnelDateValidationTest。保留无日期/空白/单边/完整rows响应和原错误形态，不自定统计窗口/62天/分页、不改Vue/Mapper/埋点。
- **AN02**：玩家成长聚合跨主题并发丢更新。独占`PlayerProgressionServiceImpl.java`、`PlayerGrowthMapper.java/XML`及唯一既有PlayerProgressionServiceImplTest.java；真实探索通关链先成长后积分，独立topic锁不保护共同成长行，普通读+整行绝对值更新可覆盖累计里程/打卡。统一该成长聚合的事务当前读或原子协议及初始化，保持等级/连续天数/阈值/同主题幂等。不动积分、共享会员mapper、上层caller或DDL。

精确源码/测试/证据路径、调用链、反例与验收见同名JSON。已核64个OPEN PR所有分页文件清单，实际新包源和既有两测试没有触达；公共master cbcc4426对应4生产blob与当前基线完全相同，未另修。PR69growthcenter前端不等于PlayerGrowth后端冲突。旧P0-48总表ID由用户引用，Cloud未读原表，不夸大为全P048收口。

额外ApiAnalyticsController/AnalyticsEventService/AnalyticsAggregationTask和BizFunnelDailyMapper.xml在旧PR7/11/18、PR4及foundation B有原锁，当前只读，先取得原作者停止/交接再追加。Task实际在admin/web/task，不是system路径。Mapper.java即使无现锁，AN01不需改也不泛授写权；既有埋点专属测试亦保原写者。

Cloud只开发、完成即push/NOT_RUN；实际后端/数据库验证交本地VPS协调者，不等CI/测试补位，不新增手动测试/CI/环境，不合并部署。每个文件只一个child写；失败只占一条修复线，其余真正独立任务继续，无任务如实耗尽。

最高优先范围口径：6个既有文件之外，PR70 TASKS还明确授权两份新回归，见下文纠正及JSON；未转交更大analytics目录或其他源码，既有owner保持。

独立启动包PR70@9f96f7a858866dd2a09cd244f90c6f0ecbac5e88已完整读取；STAT/AN01、GROW/AN02真实child回执已收。两份new_regression_file为原已授权范围，已补入全局新测试owner。

## 实际第三队接入回执

第三队真实聊天 `01a140c1-6379-71fd-8711-f0b55889c01b` / durable（修复日期边界与成长并发一致性）已创建；C00主动读取[PR16实际启动ACK](https://github.com/liboyang42-cpu/hengyin/pull/16#issuecomment-6199048354)，据实登记 `/root/stat`（AN01/F3-STAT-01）与 `/root/grow`（AN02/F3-GROW-01）两名child及其六条既有文件，各一写者，基线0d63043ad06775bc3dc52fb925620ef36d1081a7、源码分支codex/mini-forward-source-20261015。启动运行数2为历史ACK快照；最新已交付冻结、实际writer=0。清单内授权已生效，不需再次一般批准；原CORE/EXTRA/Claude继续只读这些文件。本地总控三队调度与VPS，C00唯一维护全局账。全部源码/回归NOT_RUN，未合并部署。

## 原启动包新回归授权补登记（覆盖旧限制）

最新授权口径纠正：PR70 TASKS原本明确授予两份专属新回归，STAT `/root/stat` 独占 `business/forward20261015/AnalyticsDateContractTest.java`（尚未创建，保留名字即可）；GROW `/root/grow` 独占 `business/forward20261015/PlayerGrowthConcurrencyMySqlIT.java`（已在PR72交付）。完整路径均位于 `chengyinhub-admin/src/test/java/com/chengyinhub/`。六条统计仅指既有源码/旧测试，另有这两条新增回归，共8条精确路径。此前C00“尚未授写/待扩权”的记账限制已撤回，这是登记遗漏纠正，不是第三队擅自扩权，不需再批准或为凑文件重写测试。无其他Mapper/DDL/鉴权范围扩展。

已主动完整读取PR72@7bf99128e0f136f7bc3d13b25f62e4099a073078及QUEUE/CLAIMS/DELIVERY：STAT add1534cd63c6ffd95467795126246c8d3581594、GROW 2babbe283ed2bfd2bf94859e0f5a589f12dcf9bd，冻结源码候选为后者；后续head为交付文档，不改变验证源码。两作者均冻结，实际writer=0、READY=0、范围EXHAUSTED；启动两writer为历史ACK，不作持续运行声明。普通24声明+真MySQL11声明=35只是源码计划，全为SOURCE_DELIVERED_UNVERIFIED/NOT_RUN，不能当执行成绩。实际VPS/数据库验证仍本地总控负责，Cloud不测试、CI重跑或连接VPS。
