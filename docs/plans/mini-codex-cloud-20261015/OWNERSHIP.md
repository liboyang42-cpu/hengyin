# 文件归属与候选登记

协议：`CY-COORD-20261015-v1`。唯一维护者：本聊天 Codex C00/C99。控制文档使用独立checkout `/workspace/hengyin-coordination`，业务候选在另一checkout；不在worker共享目录切换分支。

## 当前事实与状态

- 控制仓库/分支：`liboyang42-cpu/hengyin@codex/mini-bugs-backend-cloud-plan-20261015`，复用文档PR16。
- 业务候选：`codex/mini-bug-arch-integration-20261015@c236f131b19111138d5d6443fa18441f1adb68de`，[PR18](https://github.com/liboyang42-cpu/hengyin/pull/18)，未合并。共同worker基线 `d551c6babd018d41aec90dad2c0acaf1c534644d`。
- 后端整体仅 CONTRACT_FROZEN：Java CI仍有两个补偿用例触发条件不一致待修，不能标SOURCE_READY或TEST_DEPLOYED；任何测试服务/数据库/设备均无部署证据。
- worker最多4路；用户指定模型后，后续worker均显式 `gpt-6.1-sol`。历史所有者已冻结并交补丁后才交接，非因超时释放。
- 唯一控制写者C00独占本目录全部控制文档，尤其OWNERSHIP.md、BACKEND_CHANGES.md、tasks.json；其他人只能在自己的PR/交付目录交请求。

## 主动交付读取记录

已查询两个公共仓库的CY-HANDOFF PR搜索及默认分支代码搜索，结果均为空；同时用公开pulls列表逐条核开放PR正文（避免仅依赖搜索索引）。此结果不等于其他agent未启动。

| 角色/来源 | 实际公共分支与SHA | 处置 |
|---|---|---|
| CODEX | 候选c236f131b19111138d5d6443fa18441f1adb68de，PR18 | 本协调者实际已启动；将维护CY-HANDOFF: CODEX正文 |
| CLAUDE | `claude/mini-ui-features-20261015@d9dc65ad4548f75562cebcd318265114c3218b20`，PR21 | 已读正文，文档CL1–CL5请求正在核精确路径；尚无CY标记，不声称已通知或接收。认领材料合同保持DRAFT |
| 额外新功能PR20 | `feature/roam-launch-20261015@49182b74e5fd515f30a09442338b8f5ed4c9b91e` | 含两条新DDL及订单/漫游文件重叠，非本轮自动集成；原所有者未明确停止，不能抢写 |
| 额外隐私PR17 | `feature/prelaunch-acceptance-20261015@b1f66dbf95dcb172f4c1255e079ba19f35a988e5` | 已读正文；父链不同，作者测试/部署声明未在本候选独立复核，不合并、不夺其文件 |
| IOS | 当前没有开放PR/带标记交付；远端main=`a8d387e`、migration/native-ios=`f0df085`，另存在work/native-73e-file-transfer=`03facd8`等分支 | 实际活动分支/写入者/未提交工作 UNKNOWN；保留既有iOS所有权，不选择分支、不改源码、不将静态快照当已领取 |

## 精确文件锁

状态INTEGRATED仅表示补丁已收入候选；原所有者已明确停止/冻结且由C00串行提交。锁仍保留，未标RELEASED，Claude/iOS不得直接编辑。ASSIGNED表示当前唯一允许写入者；提交由协调者统一进行。相同文件需要新功能变更时，交此所有者或先完整履行转交协议。

| 仓库 | 精确路径 | 所有者/原实现者 | 状态 | 基线 | 证据/限制 |
|---|---|---|---|---|---|
| hengyin | `chengyinhub-admin/src/test/java/com/chengyinhub/business/community/CommunityPostMapperMyBatisTest.java` | C00（B05已获用户批准） | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-admin/src/test/java/com/chengyinhub/business/it/RedemptionRewardClawbackMyBatisTest.java` | B03_sol | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/B03AssetMybatisIntegrationTest.java` | B03_sol | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/B03PointsAwardServiceTest.java` | B03_sol | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/B04ClubEventScheduleReadbackTest.java` | B04_sol | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/PublisherSettlementPointsAwardServiceTest.java` | B03_sol | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/TopicDraftLegacyFieldPreservationTest.java` | B02（已交接冻结） | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/AiSceneRouterContractTest.java` | A02_sol | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/impl/AiGatewayServiceImplTest.java` | A02_sol | ASSIGNED | `c236f13` | 仅修两个补偿用例的失败触发；原断言不减；其他人不写 |
| hengyin | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/A03EndingSnapshotReadTest.java` | A03_sol | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/A03EndingWriteCompatibilityTest.java` | A03_sol | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/NpcChatServiceTest.java` | A02_sol | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/TopicRouteRuntimeServiceImplTest.java` | A01_sol | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/support/A03RuntimeEndingCompatibilityTest.java` | A03_sol | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/dto/TopicCreateDTO.java` | B02（已交接冻结） | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/vo/ClubEventSeriesView.java` | B04_sol | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/UmsMemberMapper.java` | B03_sol | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/AiProviderOutcomeUnknownException.java` | A02_sol | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/AiSceneRouter.java` | A02_sol | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/OpenAiCompatibleLlmClient.java` | A02_sol | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/impl/AiGatewayServiceImpl.java` | A02_sol | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ChapterFlowCompiler.java` | A03_sol | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ClubEventOpsServiceImpl.java` | B04_sol | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsTopicServiceImpl.java` | B02（已交接冻结） | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/NpcChatService.java` | A02_sol | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/PlayJourneyStoryServiceImpl.java` | A03_sol | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/PointsAwardServiceImpl.java` | B03_sol | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicRouteRuntimeServiceImpl.java` | A01_sol | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/resources/mapper/business/CommunityPostMapper.xml` | C00（B05已获用户批准） | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/resources/mapper/business/MerchantPayableBatchMapper.xml` | B03_sol | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-system/src/main/resources/mapper/business/UmsMemberMapper.xml` | B03_sol | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-xcx/pages/club/event-ops/index.js` | B01_sol（此前B01已交接） | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-xcx/pages/mylike/mylike.js` | B01_sol（此前B01已交接） | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-xcx/pages/play/index.js` | B01_sol（此前B01已交接） | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-xcx/scripts/uiaudit/action-ledger.json` | C00/C99 | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-xcx/subpackageMember/components/cy/scene-member-order-detail/index.js` | B01_sol（此前B01已交接） | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-xcx/subpackageMember/components/cy/scene-member-order-history/index.js` | B01_sol（此前B01已交接） | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-xcx/subpackageMember/components/scene-member-participation-detail/index.js` | B01_sol（此前B01已交接） | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-xcx/tests/lint/ui-integrity-baseline.js` | C00/C99 | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-xcx/tests/unit/action-ledger.test.js` | C00/C99 | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-xcx/tests/unit/b01-lifecycle-account.test.js` | B01_sol（此前B01已交接） | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-xcx/tests/unit/b01-series-start-time.test.js` | B01_sol（此前B01已交接） | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `chengyinhub-xcx/utils/audio-session.js` | B01_sol（此前B01已交接） | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |
| hengyin | `ci/backend-test.sh` | C00/C99 | INTEGRATED | `c236f13` | 已收到原所有者停止/冻结及补丁交付；候选PR18，未释放给Claude/iOS |

## 专属文档、共享文件与转交

- `hengyin:docs/plans/mini-codex-cloud-20261015/`（仅本控制目录新文件/既有文件）：C00 ASSIGNED；根/小程序AGENTS、app.json、公共请求封装/DTO、workflow/pom/lockfile、迁移索引及共享fixture仍由C00统筹，默认只读，不因本表授权修改政策或DDL。
- `hengyin:docs/verification/mini-bug-arch-20261015/COORDINATION/CLAUDE_AUDIT.md`：claude_coord_sol ASSIGNED，只读取证写该专属文档，不改控制表。
- 其余原tasks.json预留但本轮未改的文件只是REQUESTED/只读，不把规划当已认领；任何新增/冲突文件须由C00登记精确路径。
- Claude/iOS的专属源码由其实际分支现有所有者保留，待其CY-HANDOFF给出精确路径后登记；空表或未响应不收回其原任务。
- B01、B02、B03、B04、A01、A02、A03均已在本聊天提交停止/冻结说明及精确补丁；C00按路径提交到PR18。B05最小授权修复由用户明确批准后提交`e437a52`。
- 后续交接必须写原所有者停止证据、最后补丁/commit、新基线、新写者；当前没有把任何后端核心或已锁文件转交Claude/iOS。
