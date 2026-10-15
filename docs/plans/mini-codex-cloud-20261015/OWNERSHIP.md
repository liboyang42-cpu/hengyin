# 文件归属与候选登记

协议：`CY-COORD-20261015-v1`。唯一维护者：本聊天 Codex C00/C99。控制文档使用独立checkout `/workspace/hengyin-coordination`，业务候选在另一checkout；不在worker共享目录切换分支。

## 最新冻结候选签收：231/231自动化矩阵闭环

公共源码 **1e403b135206844a34219753a36e2dee39c7dd59**；独立证据[PR24@43ca532](https://github.com/liboyang42-cpu/hengyin/blob/43ca53274b345bf344037e1fe60afa49b8e53dd1/docs/verification/mini-bug-arch-20261015/VPS_EXTERNAL_1e403b13/SUMMARY.md)。C00已完整读取SUMMARY、B03 evidence、regression、ai-storage、resources、cleanup：B03真MySQL7＋普通/H2回归202＋AI真实MySQL/Redis22，实际**231/231，失败0/错误0/跳过0**。逐类数量/方法、XML新鲜度、同一源码无补丁、退出0/无OOM均核；三个Maven容器区间不重叠，任务标签核清理5容器和独占网络、拆除前非系统schema=0及Redis DB0键=0，原日志/报告/源码/依赖缓存保留。

同SHA GitHub CI run38733056178、Funds run38733056167全部SUCCESS（10+2 jobs）；当前签收见[VALIDATION_1e403b13.json](VALIDATION_1e403b13.json)。原69c281af setup失败作为历史保留（7业务未运行），未抹除或混入当前通过计数。此前“202+22待报告”仅是历史进度，已由本签收覆盖。

**本轮冻结候选VPS自动化矩阵已闭环；不等于全轮任务/产品完成。TEST_DEPLOYED=NO，运行时HTTP/真实身份业务读回、应用包SHA/数据库绑定、微信/iOS、合并/业务迁移/部署/上传均未验收。** 后续已确认bug/架构缺口另分支、新SHA和聚焦套件，不沿用231签收，不包括Claude暂缓新产品。

## 当前事实与状态

- 控制仓库/分支：`liboyang42-cpu/hengyin@codex/mini-bugs-backend-cloud-plan-20261015`，复用文档PR16。
- 业务候选：`codex/mini-bug-arch-integration-20261015@1e403b135206844a34219753a36e2dee39c7dd59`，[PR18](https://github.com/liboyang42-cpu/hengyin/pull/18)，未合并。共同worker基线 `d551c6babd018d41aec90dad2c0acaf1c534644d`。
- 已测修复在69c281af达到SOURCE_READY：GitHub CI run38730430052与Funds run38730430072均成功，跳过/未覆盖项见VALIDATION_69c281af.json。任何测试服务/数据库/设备均无部署证据，不能标TEST_DEPLOYED；ASSET新增真库竞态仍待点名。
- worker最多4路；用户指定模型后，后续worker均显式 `gpt-6.1-sol`。历史所有者已冻结并交补丁后才交接，非因超时释放。
- 唯一控制写者C00独占本目录全部控制文档，尤其OWNERSHIP.md、BACKEND_CHANGES.md、tasks.json；其他人只能在自己的PR/交付目录交请求。

## 主动交付读取记录

初查两个公共仓库CY-HANDOFF为空；后续公开pulls逐条复核已发现PR21的CLAUDE标记和PR22新交付，不能依赖旧搜索快照。iOS仍暂无开放PR，此结果不等于其他agent未启动。

| 角色/来源 | 实际公共分支与SHA | 处置 |
|---|---|---|
| CODEX | 候选69c281af1ecc3493db5614b74e494b91c7bb0656，PR18 | 本协调者实际已启动；将维护CY-HANDOFF: CODEX正文 |
| CLAUDE | `claude/mini-ui-features-20261015@fe00f5c6359a641c7cbee42c3dc08daae76ffe41`，PR21 | 已主动读到CY-HANDOFF: CLAUDE更新及两份文档，CL1–5实际申领见下表/CL1审阅；对本次分配的接收回执仍PENDING。认领材料合同DRAFT |
| 额外新功能PR20 | `feature/roam-launch-20261015@49182b74e5fd515f30a09442338b8f5ed4c9b91e` | 含两条新DDL及订单/漫游文件重叠，非本轮自动集成；原所有者未明确停止，不能抢写 |
| 额外隐私PR17 | `feature/prelaunch-acceptance-20261015@b1f66dbf95dcb172f4c1255e079ba19f35a988e5` | 已读正文；父链不同，作者测试/部署声明未在本候选独立复核，不合并、不夺其文件 |
| IOS | 当前没有开放PR/带标记交付；远端main=`a8d387e`、migration/native-ios=`f0df085`，另存在work/native-73e-file-transfer=`03facd8`等分支 | 实际活动分支/写入者/未提交工作 UNKNOWN；保留既有iOS所有权，不选择分支、不改源码、不将静态快照当已领取 |

## 精确文件锁

状态INTEGRATED仅表示补丁已收入候选；原所有者已明确停止/冻结且由C00串行提交。锁仍保留，未标RELEASED，Claude/iOS不得直接编辑。ASSIGNED表示当前唯一允许写入者；提交由协调者统一进行。相同文件需要新功能变更时，交此所有者或先完整履行转交协议。

| 仓库 | 精确路径 | 所有者/原实现者 | 状态 | 基线 | 证据/限制 |
|---|---|---|---|---|---|
| hengyin | `chengyinhub-admin/src/test/java/com/chengyinhub/business/community/CommunityPostMapperMyBatisTest.java` | C00（B05已获用户批准） | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-admin/src/test/java/com/chengyinhub/business/it/RedemptionRewardClawbackMyBatisTest.java` | B03_sol | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/B03AssetMybatisIntegrationTest.java` | B03_sol（原写者接续） | INTEGRATED | `1e403b135206844a34219753a36e2dee39c7dd59` | 原写者修复后已明确停止，C00单路径提交；7业务方法未变；VPS复测待验，锁未释放 |
| hengyin | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/B03PointsAwardServiceTest.java` | B03_sol | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/B04ClubEventScheduleReadbackTest.java` | B04_sol | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/PublisherSettlementPointsAwardServiceTest.java` | B03_sol | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/TopicDraftLegacyFieldPreservationTest.java` | B02（已交接冻结） | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/AiSceneRouterContractTest.java` | A02_sol | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/impl/AiGatewayServiceImplTest.java` | A02_sol | INTEGRATED | `c24202f` | c24202f6已交付并停止；仅修两项调用前触发，断言保留，CI待验 |
| hengyin | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/A03EndingSnapshotReadTest.java` | A03_sol | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/A03EndingWriteCompatibilityTest.java` | A03_sol | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/NpcChatServiceTest.java` | A02_sol | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/TopicRouteRuntimeServiceImplTest.java` | A01_sol | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/support/A03RuntimeEndingCompatibilityTest.java` | A03_sol | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/dto/TopicCreateDTO.java` | B02（已交接冻结） | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/vo/ClubEventSeriesView.java` | B04_sol | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/UmsMemberMapper.java` | B03_sol | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/AiProviderOutcomeUnknownException.java` | A02_sol | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/AiSceneRouter.java` | A02_sol | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/OpenAiCompatibleLlmClient.java` | A02_sol | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/impl/AiGatewayServiceImpl.java` | A02_sol | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ChapterFlowCompiler.java` | A03_sol | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ClubEventOpsServiceImpl.java` | B04_sol | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsTopicServiceImpl.java` | B02（已交接冻结） | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/NpcChatService.java` | A02_sol | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/PlayJourneyStoryServiceImpl.java` | A03_sol | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/PointsAwardServiceImpl.java` | B03_sol | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicRouteRuntimeServiceImpl.java` | A01_sol | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/resources/mapper/business/CommunityPostMapper.xml` | C00（B05已获用户批准） | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/resources/mapper/business/MerchantPayableBatchMapper.xml` | B03_sol | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/resources/mapper/business/UmsMemberMapper.xml` | B03_sol | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-xcx/pages/club/event-ops/index.js` | B01_sol（此前B01已交接） | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-xcx/pages/mylike/mylike.js` | B01_sol（此前B01已交接） | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-xcx/pages/play/index.js` | B01_sol（此前B01已交接） | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-xcx/scripts/uiaudit/action-ledger.json` | C00/C99 | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-xcx/subpackageMember/components/cy/scene-member-order-detail/index.js` | B01_sol（此前B01已交接） | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-xcx/subpackageMember/components/cy/scene-member-order-history/index.js` | B01_sol（此前B01已交接） | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-xcx/subpackageMember/components/scene-member-participation-detail/index.js` | B01_sol（此前B01已交接） | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-xcx/tests/lint/ui-integrity-baseline.js` | C00/C99 | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-xcx/tests/unit/action-ledger.test.js` | C00/C99 | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-xcx/tests/unit/b01-lifecycle-account.test.js` | B01_sol（此前B01已交接） | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-xcx/tests/unit/b01-series-start-time.test.js` | B01_sol（此前B01已交接） | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-xcx/utils/audio-session.js` | B01_sol（此前B01已交接） | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `ci/backend-test.sh` | C00/C99 | INTEGRATED | `c24202f` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |

## 专属文档、共享文件与转交

- `hengyin:docs/plans/mini-codex-cloud-20261015/`（仅本控制目录新文件/既有文件）：C00 ASSIGNED；根/小程序AGENTS、app.json、公共请求封装/DTO、workflow/pom/lockfile、迁移索引及共享fixture仍由C00统筹，默认只读，不因本表授权修改政策或DDL。
- `hengyin:docs/verification/mini-bug-arch-20261015/COORDINATION/CLAUDE_AUDIT.md`：C00 INTEGRATED；claude_coord_sol已停止并交只读审计，文档由C00串行收入候选，不改控制表。
- 其余原tasks.json预留但本轮未改的文件只是REQUESTED/只读，不把规划当已认领；任何新增/冲突文件须由C00登记精确路径。
- Claude/iOS的专属源码由其实际分支现有所有者保留，待其CY-HANDOFF给出精确路径后登记；空表或未响应不收回其原任务。
- B01、B02、B03、B04、A01、A02、A03均已在本聊天提交停止/冻结说明及精确补丁；C00按路径提交到PR18。B05最小授权修复由用户明确批准后提交`e437a52`。
- 后续交接必须写原所有者停止证据、最后补丁/commit、新基线、新写者；当前没有把任何后端核心或已锁文件转交Claude/iOS。

## PR21逐项申领处置（协调者发布，尚待Claude读取确认）

已核PR21 `d9dc65a` 文档申请、PR17/20精确差异与本候选锁。以下ASSIGNED仅授予Claude在其 `claude/mini-ui-features-20261015` 分支的唯一文件写权，允许既有UI设计/布局/fixture适配；不代表已开始、已通知或已签收，也不代表新接口获批或部署。事件名/参数和既有请求保持，接入未核合同的写能力继续OFF；新增测试文件先列精确路径。无其他agent与这些文件并写。

| 任务 | 仓库及精确路径 | 唯一写者 | 状态/基线 | 依赖及处置 |
|---|---|---|---|---|
| CL1 | `hengyin:chengyinhub-xcx/subpackageRoam/official-route/index.js` | Claude / PR21 | WITHDRAWAL_PENDING；接收回执PENDING；基线 `c24202f` | 按fe00f5c撤G1；原写者交最终补丁/停止证据前保留锁 |
| CL1 | `hengyin:chengyinhub-xcx/subpackageRoam/official-route/index.json` | Claude / PR21 | WITHDRAWAL_PENDING；接收回执PENDING；基线 `c24202f` | 按fe00f5c撤G1；原写者交最终补丁/停止证据前保留锁 |
| CL1 | `hengyin:chengyinhub-xcx/subpackageRoam/official-route/index.wxml` | Claude / PR21 | WITHDRAWAL_PENDING；接收回执PENDING；基线 `c24202f` | 按fe00f5c撤G1；原写者交最终补丁/停止证据前保留锁 |
| CL1 | `hengyin:chengyinhub-xcx/subpackageRoam/official-route/index.wxss` | Claude / PR21 | WITHDRAWAL_PENDING；接收回执PENDING；基线 `c24202f` | 按fe00f5c撤G1；原写者交最终补丁/停止证据前保留锁 |
| CL1 | `hengyin:chengyinhub-xcx/subpackageRoam/official-routes/index.js` | Claude / PR21 | ASSIGNED；接收回执PENDING；基线 `c24202f` | 现有只读/布局可独立；新增协议另行冻结，禁止公共文件连带修改 |
| CL1 | `hengyin:chengyinhub-xcx/subpackageRoam/official-routes/index.json` | Claude / PR21 | ASSIGNED；接收回执PENDING；基线 `c24202f` | 现有只读/布局可独立；新增协议另行冻结，禁止公共文件连带修改 |
| CL1 | `hengyin:chengyinhub-xcx/subpackageRoam/official-routes/index.wxml` | Claude / PR21 | ASSIGNED；接收回执PENDING；基线 `c24202f` | 现有只读/布局可独立；新增协议另行冻结，禁止公共文件连带修改 |
| CL1 | `hengyin:chengyinhub-xcx/subpackageRoam/official-routes/index.wxss` | Claude / PR21 | ASSIGNED；接收回执PENDING；基线 `c24202f` | 现有只读/布局可独立；新增协议另行冻结，禁止公共文件连带修改 |
| CL2 | `hengyin:chengyinhub-xcx/pages/team/report-sheet/index.js` | Claude / PR21 | ASSIGNED；接收回执PENDING；基线 `c24202f` | 现有只读/布局可独立；新增协议另行冻结，禁止公共文件连带修改 |
| CL2 | `hengyin:chengyinhub-xcx/pages/team/report-sheet/index.json` | Claude / PR21 | ASSIGNED；接收回执PENDING；基线 `c24202f` | 现有只读/布局可独立；新增协议另行冻结，禁止公共文件连带修改 |
| CL2 | `hengyin:chengyinhub-xcx/pages/team/report-sheet/index.wxml` | Claude / PR21 | ASSIGNED；接收回执PENDING；基线 `c24202f` | 现有只读/布局可独立；新增协议另行冻结，禁止公共文件连带修改 |
| CL4 | `hengyin:chengyinhub-xcx/components/cy/npc-chat/index.json` | Claude / PR21 | ASSIGNED；接收回执PENDING；基线 `c24202f` | 现有只读/布局可独立；新增协议另行冻结，禁止公共文件连带修改 |
| CL4 | `hengyin:chengyinhub-xcx/components/cy/npc-chat/index.wxss` | Claude / PR21 | ASSIGNED；接收回执PENDING；基线 `c24202f` | 现有只读/布局可独立；新增协议另行冻结，禁止公共文件连带修改 |
| CL4 | `hengyin:chengyinhub-xcx/pages/activity/official-detail/index.json` | Claude / PR21 | ASSIGNED；接收回执PENDING；基线 `c24202f` | 现有只读/布局可独立；新增协议另行冻结，禁止公共文件连带修改 |
| CL4 | `hengyin:chengyinhub-xcx/pages/activity/official-detail/index.wxss` | Claude / PR21 | ASSIGNED；接收回执PENDING；基线 `c24202f` | 现有只读/布局可独立；新增协议另行冻结，禁止公共文件连带修改 |
| CL4 | `hengyin:chengyinhub-xcx/pages/activity/official-inbox/index.js` | Claude / PR21 | ASSIGNED；接收回执PENDING；基线 `c24202f` | 现有只读/布局可独立；新增协议另行冻结，禁止公共文件连带修改 |
| CL4 | `hengyin:chengyinhub-xcx/pages/activity/official-inbox/index.json` | Claude / PR21 | ASSIGNED；接收回执PENDING；基线 `c24202f` | 现有只读/布局可独立；新增协议另行冻结，禁止公共文件连带修改 |
| CL4 | `hengyin:chengyinhub-xcx/pages/activity/official-inbox/index.wxss` | Claude / PR21 | ASSIGNED；接收回执PENDING；基线 `c24202f` | 现有只读/布局可独立；新增协议另行冻结，禁止公共文件连带修改 |
| CL4 | `hengyin:chengyinhub-xcx/pages/activity/official-mine/index.js` | Claude / PR21 | ASSIGNED；接收回执PENDING；基线 `c24202f` | 现有只读/布局可独立；新增协议另行冻结，禁止公共文件连带修改 |
| CL4 | `hengyin:chengyinhub-xcx/pages/activity/official-mine/index.json` | Claude / PR21 | ASSIGNED；接收回执PENDING；基线 `c24202f` | 现有只读/布局可独立；新增协议另行冻结，禁止公共文件连带修改 |
| CL4 | `hengyin:chengyinhub-xcx/pages/activity/official-mine/index.wxss` | Claude / PR21 | ASSIGNED；接收回执PENDING；基线 `c24202f` | 现有只读/布局可独立；新增协议另行冻结，禁止公共文件连带修改 |
| CL4 | `hengyin:chengyinhub-xcx/subpackageMember/components/cy/profile/index.json` | Claude / PR21 | ASSIGNED；接收回执PENDING；基线 `c24202f` | 现有只读/布局可独立；新增协议另行冻结，禁止公共文件连带修改 |
| CL4 | `hengyin:chengyinhub-xcx/subpackageMember/components/cy/profile/index.wxss` | Claude / PR21 | ASSIGNED；接收回执PENDING；基线 `c24202f` | 现有只读/布局可独立；新增协议另行冻结，禁止公共文件连带修改 |

- CL1全roam与roam-*通配：REQUESTED，不整批批准。PR20已占用pages/roam/index.{js,wxml,wxss}、history/index.{js,wxml}、nearby/index.{js,wxml,wxss}、my-teams四文件，保留该PR原写者，须停止/补丁/基线记录后才可转交；其他实际需要路径请在CY-HANDOFF精确申请。
- CL2新report-*页、CL3新徽章页及其测试：REQUESTED，未给精确命名，不凭猜测登记新目录。昵称资料页两套入口请给实际调用及拟改的一套；初始保守保留，后按真实base三点diff已确认PR23 gerenziliao资料文件无PR17增量冲突，以下CL3精确登记优先。
- CL3 pages/club/**：REQUESTED，明确排除已锁 `chengyinhub-xcx/pages/club/event-ops/index.js`；需其变更交B01/B04原所有者，WXML/WXSS另列精确事件合同后领取。
- CL4 `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/NpcChatService.java`：A02保留锁；UI领取不含后端核心或后台全目录。profile若与PR17重叠未授予。未知usage不能用0显示，具体后台Vue页面请精确申领。
- CL5 claim-sheet四文件与utils/merchant-claim.js：REQUESTED，材料合同DRAFT；不得先固定PHOTO、截URL猜assetId或把缺归属校验当业务批准。认领专属后端可在合同/精确文件确认后委派，当前无新增后端写授权。
- 公共app.json、请求封装、DTO、动作账、门禁基线、共享fixture、workflow/pom/迁移仍C00统筹；PR20原订单JS和本候选B01重叠由原所有者处理，禁止覆盖整包。
- 后续读取点：各方开始依赖接线/更新基线/联调前读取本控制分支；本表发布只证明协调者已处理申请，不能代替Claude/iOS的CY-HANDOFF接收回执。独立已领文件继续，不等待CL5。

## 后续交付读取：PR21 c6bc220 / PR22 64b485a

详见[CL1交付审阅](HANDOFF_REVIEW_CL1.md)。PR22只有18文件三点diff，不整包集成；原Claude CL1保留实现责任，七项实际问题/降级与测试向量已发布。CL1详情页四文件原分配不换写者；新增实际路径登记如下。接收回执PENDING，不能假定对方已读。

| 仓库 | 精确路径 | 唯一写者 | 状态/基线 | 限制 |
|---|---|---|---|---|
| hengyin | `chengyinhub-xcx/pages/roam/components/follow-route-hud/index.js` | Claude CL1 / PR22原写者 | WITHDRAWAL_PENDING，原交付64b485a；更新候选69c281af需保留现有锁 | G1已暂缓，仅原写者撤回；不得继续加功能或扩写父目录 |
| hengyin | `chengyinhub-xcx/pages/roam/components/follow-route-hud/index.json` | Claude CL1 / PR22原写者 | WITHDRAWAL_PENDING，原交付64b485a；更新候选69c281af需保留现有锁 | G1已暂缓，仅原写者撤回；不得继续加功能或扩写父目录 |
| hengyin | `chengyinhub-xcx/pages/roam/components/follow-route-hud/index.wxml` | Claude CL1 / PR22原写者 | WITHDRAWAL_PENDING，原交付64b485a；更新候选69c281af需保留现有锁 | G1已暂缓，仅原写者撤回；不得继续加功能或扩写父目录 |
| hengyin | `chengyinhub-xcx/pages/roam/components/follow-route-hud/index.wxss` | Claude CL1 / PR22原写者 | WITHDRAWAL_PENDING，原交付64b485a；更新候选69c281af需保留现有锁 | G1已暂缓，仅原写者撤回；不得继续加功能或扩写父目录 |
| hengyin | `chengyinhub-xcx/pages/roam/follow-route.js` | Claude CL1 / PR22原写者 | WITHDRAWAL_PENDING，原交付64b485a；更新候选69c281af需保留现有锁 | G1已暂缓，仅原写者撤回；不得继续加功能或扩写父目录 |
| hengyin | `chengyinhub-xcx/utils/roam-follow-route.js` | Claude CL1 / PR22原写者 | WITHDRAWAL_PENDING，原交付64b485a；更新候选69c281af需保留现有锁 | G1已暂缓，仅原写者撤回；不得继续加功能或扩写父目录 |
| hengyin | `chengyinhub-xcx/tests/unit/roam-follow-route.test.js` | Claude CL1 / PR22原写者 | WITHDRAWAL_PENDING，原交付64b485a；更新候选69c281af需保留现有锁 | G1已暂缓，仅原写者撤回；不得继续加功能或扩写父目录 |

PR22中的roam入口JS/WXML为CONFLICT_HELD（PR20原写者未停止）；index.json及四项全局测试/账本为PATCH_PROPOSED给C00串行，均未转交写权。具体名单见审阅文档。PR21新增CL2/3/4仍REQUESTED精确名；CL5保持DRAFT。

## 集中测试与VPS连接复核

当前精确候选69c281af的两条既有GitHub工作流均SUCCESS，详见[脱敏测试证据](VALIDATION_69c281af.json)。本轮没有合并/部署。用户要求由协调者接VPS集中测试：已校验用户提供的临时身份可加载，标准SSH在认证前Connection refused；Cloud当前TCP允许目标为空、无VPN/外联身份，连接问题已向用户报告，等待正确端口/网络配置。身份仅存权限受限临时文件，绝不进入控制文档/Git/CI。已有CI不是VPS验收。

## CL3实际交付PR23 a7246947

详见[CL3审阅](HANDOFF_REVIEW_CL3.md)。以下15项实际业务/专属测试给原Claude CL3 / PR23独占；不是整页目录通配，新页只限badges/四文件。原作者持续写入、未转交Codex；接收本控制分配回执PENDING。主候选仍69c281af，PR23未集成。

| 仓库 | 精确路径 | 唯一写者 | 状态/基线 | 限制 |
|---|---|---|---|---|
| hengyin | `chengyinhub-xcx/pages/club/badges/index.js` | Claude CL3 / PR23 | ASSIGNED；交付a7246947，候选69c281af | 原写者按审阅修复；未授权改后端/共享文件 |
| hengyin | `chengyinhub-xcx/pages/club/badges/index.json` | Claude CL3 / PR23 | ASSIGNED；交付a7246947，候选69c281af | 原写者按审阅修复；未授权改后端/共享文件 |
| hengyin | `chengyinhub-xcx/pages/club/badges/index.wxml` | Claude CL3 / PR23 | ASSIGNED；交付a7246947，候选69c281af | 原写者按审阅修复；未授权改后端/共享文件 |
| hengyin | `chengyinhub-xcx/pages/club/badges/index.wxss` | Claude CL3 / PR23 | ASSIGNED；交付a7246947，候选69c281af | 原写者按审阅修复；未授权改后端/共享文件 |
| hengyin | `chengyinhub-xcx/pages/club/utils/club-badge.js` | Claude CL3 / PR23 | ASSIGNED；交付a7246947，候选69c281af | 原写者按审阅修复；未授权改后端/共享文件 |
| hengyin | `chengyinhub-xcx/utils/nickname-check.js` | Claude CL3 / PR23 | ASSIGNED；交付a7246947，候选69c281af | 原写者按审阅修复；未授权改后端/共享文件 |
| hengyin | `chengyinhub-xcx/tests/unit/club-badge-manage.test.js` | Claude CL3 / PR23 | ASSIGNED；交付a7246947，候选69c281af | 原写者按审阅修复；未授权改后端/共享文件 |
| hengyin | `chengyinhub-xcx/tests/unit/profile-nickname-check.test.js` | Claude CL3 / PR23 | ASSIGNED；交付a7246947，候选69c281af | 原写者按审阅修复；未授权改后端/共享文件 |
| hengyin | `chengyinhub-xcx/pages/club/detail/index.js` | Claude CL3 / PR23 | ASSIGNED；交付a7246947，候选69c281af | 原写者按审阅修复；未授权改后端/共享文件 |
| hengyin | `chengyinhub-xcx/pages/club/detail/index.wxml` | Claude CL3 / PR23 | ASSIGNED；交付a7246947，候选69c281af | 原写者按审阅修复；未授权改后端/共享文件 |
| hengyin | `chengyinhub-xcx/pages/club/detail/index.wxss` | Claude CL3 / PR23 | ASSIGNED；交付a7246947，候选69c281af | 原写者按审阅修复；未授权改后端/共享文件 |
| hengyin | `chengyinhub-xcx/pages/gerenziliao/gerenziliao.js` | Claude CL3 / PR23 | ASSIGNED；交付a7246947，候选69c281af | 原写者按审阅修复；未授权改后端/共享文件 |
| hengyin | `chengyinhub-xcx/pages/gerenziliao/gerenziliao.wxml` | Claude CL3 / PR23 | ASSIGNED；交付a7246947，候选69c281af | 原写者按审阅修复；未授权改后端/共享文件 |
| hengyin | `chengyinhub-xcx/pages/gerenziliao/gerenziliao.wxss` | Claude CL3 / PR23 | ASSIGNED；交付a7246947，候选69c281af | 原写者按审阅修复；未授权改后端/共享文件 |
| hengyin | `chengyinhub-xcx/tests/unit/profile-edit-state-contract.test.js` | Claude CL3 / PR23 | ASSIGNED；交付a7246947，候选69c281af | 原写者按审阅修复；未授权改后端/共享文件 |

PR23共享PATCH_PROPOSED给C00（尚未合入）：`chengyinhub-xcx/app.json`、`scripts/uiaudit/action-ledger.json`、`scripts/uiaudit/route-matrix.json`、`tests/unit/action-ledger.test.js`、`tests/unit/nav-page-title-spacing-contract.test.js`、`tests/unit/route-matrix-freshness.test.js`、`tests/unit/title-system-owner-contract.test.js`、`utils/danger-actions.js`（后七同chengyinhub-xcx前缀）。它们与PR20真实增量交叉，不由Claude覆盖候选，不按旧1796计数覆盖本候选1792退款范围。原所有者停止/交付记录未取得，未转交。

## 后续范围优先级

[PR21 fe00f5c范围收窄](HANDOFF_SCOPE_fe00f5c.md)覆盖此前冲突描述：G1撤回锁WITHDRAWAL_PENDING非释放；CL3不改图标、推荐点选；CL2仅系统消息进入文字申诉，无被举报方视图；CL4不邀请/转交，新增协办方DRAFT待专属合同/文件。原Claude写者负责撤回/修正，C00不创建第二套UI实现。PR21只读到旧a886快照，不等于接收本轮新控制账。

## B03专用VPS夹具阻塞修复（本轮执行记录）

用户授权本地VPS验证协调者直接协作，已确认本机SSH22认证成功；本Cloud可达性独立未验证，不再等待Cloud网络，也不修改VPS SSH/安全组。验证协调者不写业务/测试源码或控制表，C00/C99仍唯一全局协调者。原写者B03_sol已确认接续并停止在只读状态，以上单文件重新ASSIGNED，其余原锁不变。

69c281af授权VPS首轮：主源码及admin 1313个测试源编译成功；显式B03AssetMybatisIntegrationTest setup失败，Surefire tests=1/failures=0/errors=1/skipped=0，退出1、无OOM，7个业务方法均未执行。原始独立脱敏证据待验证者发布，本轮不将口头/委派报告升级为7通过或TEST_DEPLOYED。根因是base_schema_20260818.sql商家应付表COMMENT内分号被首分号substring截断。单文件修复复用已有可靠断句方式，不改base schema/迁移/业务断言/门禁清单，不关闭套件或静默跳过。

交接只给公共分支、完整SHA、套件/命令与隔离数据库前提；由本地验证协调者按SHA复测并回报。身份/密钥不进GitHub，VPS测试与部署/设备/业务验收继续分层。

B03修复已推送1e403b135206844a34219753a36e2dee39c7dd59，详见[VPS复测交付](VPS_RETEST_1e403b13.md)。已完整读独立PR24 ccbcf93236e386aaf37a1c7ab0d45b6df5fb5ba6，两份文档仅为69c281af首轮setup失败证据，不移植到新SHA当通过。原写者停止/补丁/提交证据齐，仍原锁不转交验证者；本地只读公共候选执行。Cloud临时SSH身份已清理，本地连接独立保留，无需改网络。

B03 1e403b13真实VPS套件已读PR24 f30057dd的独立JSON/README签收7/0/0/0，见[证据](https://github.com/liboyang42-cpu/hengyin/blob/f30057ddc3e7812849121ab0d3d400bc68b0cb0e/docs/verification/mini-bug-arch-20261015/VPS_EXTERNAL_1e403b13/README.md)。原代码锁仍保留，无文件转交或部署；后续202+22现已签收（见顶部最新记录），C00不占VPS资源。

## 用户扩展并行：只补完未完成bug/后端

实际10路逻辑拆分、5源码＋1回归槽及精确路径见[FOLLOWUP_BUG_LANES.md](FOLLOWUP_BUG_LANES.md)，该文件的最新ASSIGNED覆盖原INTEGRATED旧基线锁，原写者/交接证据已列。新工作目录hengyin-followups；冻结hengyin@1e403b13保持供证据复核。N01/N02只读新功能调查已停止，无实现；不纳入用户当前bug/架构范围。

L03额外直接构造测试NpcChatUserDataMyBatisIT.java已追加给原A02_sol，详见线路补充；不动其余owner。L05可选本地publishAttempt最小状态合同已在同文冻结，无新HTTP/数据库字段或敏感存储。

## 六路外部Cloud精确委派

以[EXTERNAL_SIX_HANDOFF.md](EXTERNAL_SIX_HANDOFF.md)及tasks.external_followup_lanes为唯一精确新文件白名单，E01–E04承接原L06–09；E05/E06只定向回归。全部PENDING_CLOUD_CREATION_AND_ACK，现有业务源码及L01–05/L10不释放。L05追加原fabu-edit-existing-topic.test.js夹具写权给publish_unknown_sol，保留断言。

## 新Cloud回执已核，授权六路启动

C00已实际读取PR16 comment6192515205；新Cloud thread01a13f9c-e8ba-753e-9c39-03840bb4900b/host durable。12路径与EXTERNAL_SIX_HANDOFF逐字一致，和当前源码锁无交集；原6 worktree基线均1e403b13。E01–E06状态ACK_RECEIVED_START_AUTHORIZED，按原白名单现在启动，不夺L01–05/L10/Claude/iOS/公共文件。实际启动数尚未回报。

L01–05源码已按精确路径串行提交后续候选eab62274a19f549623ad1818088ba51f23b6ca8e，原写者已停止；不是新已验证候选。L05三份旧测试fabu-route-editor、publish-step3-switches、publish-ticket-sale-fields-wire-contract已精确追加给publish_unknown_sol，仅补真实身份/UUID/存储夹具，未降业务断言；其补丁86524454。

## 后续PR28实际Node签收/后端CI进行中

当前公共完整候选4a943501262cb533158428d9748d8ac49aaa8950，[PR28](https://github.com/liboyang42-cpu/hengyin/pull/28)。全量Node8361/8359通过/0失败/2原有跳过，UI-GATE-0 PASS；后台formatter+原相关页面22/22、发布相关97/97、回执目标65/65（重叠不相加）。Node运行代码树d8a0cf8b到当前候选仅文档/CI变动，业务/测试/门禁源码无差异。4480控件ID/分类/1792 local tap未变，459条evidence摘要刷新；25基线行号等量迁移、理由/棘轮未加。详见VALIDATION_FOLLOWUP_4a943501.json。

聚焦GitHub-hosted CI run38738836009已实际启动，预期39项尚无结果；首轮workflow上下文验证失败38738526470保留（无job/无测试），已修step env，不冒充业务失败或通过。L02/BK-AUTHOR聚合读和L03/BK-AI删除屏障须新真库编译/执行证据后再升SOURCE_READY。VPS新集成通道未核，不用离线build替代；TEST_DEPLOYED/运行时HTTP/设备/迁移/部署/合并仍NO。

## 用户离线长期滚动源码（最新指令）

[LONGRUN_ROLLING](LONGRUN_ROLLING.md)覆盖等待验证时序。两队各50具体任务已落地，6.1-sol各最多6线，源码精确写权见LONGRUN_SOURCE_OWNERS.json，正式原写者停止/候选证据及跨队契约在协议中。EXTRA6不再只测试文件：已授排期/社区/叙事读/券/订阅收藏/平台工具独占源码；history和participation两退款JS可转，PR20重叠detail仍保留锁。仅静态源码/写必要回归，不手动运行Node/Java/Maven/DB/lint/DevTools/业务验证；原AGENTS编译测试由用户本次明确推迟。现有自动CI保留自然结束、不手动重跑、不等结果派工。新交付SOURCE_DELIVERED_UNVERIFIED/NOT_RUN，合并/迁移/部署/设备仍NO。


## 长期源码最新checkpoint（覆盖旧状态文字）

[当前恢复入口](LONGRUN_CURRENT.md)与CORE池记录公共05d307ec0466988f212187666d7f1e5c3274f434 / PR44；后续一律SOURCE_DELIVERED_UNVERIFIED、NOT_RUN。两队真实计数/文件单写者/跨队合同/未决政策和本地恢复规则均在入口，不以历史通过套用新源码。E04额外接口及三测试、C06 runtime mapper独立当前读、C04暂停投影SQL精确写权已在源表登记；其他原锁不变。
