# M1：旧55份/41份直接构造测试接线清单（只读施工合同）

精确源码基线 `9a2dcf21aef4d7aec7a14065a2601bd86aaad805`。d359 的55个引用文件、41个显式 `new ApiPlayProgressController` 文件均仍存在；当前59/45，多出的4份单列。它们包含注释/源码契约/辅助fixture，并非55个JUnit测试，也不是实际执行计数。此文件只规划未来最小fixture差异；不修改当前测试。

## 接线操作定义

- **A**：测试显式注入同一真实 `PlayNodeAccessService`。把原 lead/tickets 桩改接新owner；其余仍被Controller使用的依赖保持同对象。普通合法夹具显式no-lead，故障夹具保留原null/throwing依赖；不能用“缺依赖就放行”的生产分支代替。
- **P**：nodes的 `hintUnlockCost` 通过既有Puzzle真定价。保留现有IPuzzle mock的其他方法时，仅新增精确委托到全依赖真实Puzzle的定价方法；不在mock里复制价格算法。原settings接新Puzzle，ledger在nodes仍是原实例。
- **H**：unlockHint改接全依赖真实Puzzle应用；不能mock成功结果让收费/回滚断言失去目标。Service专属测试使用真实Spring代理；mock纯业务单测仍注明不能证明事务。
- **R**：保留原Controller private签名作为纯投影/委托桥，反射断言仍到真实共享实现；可增加直接service等价断言，不能删原负控。
- **L**：test方法或setup末段替换了依赖实例，必须在替换之后重新wire；重stub同一mock不要求重建。源JSON逐条列实际setField行号。不得用生产Supplier或service反调Controller动态取新字段。
- **S/0**：语义回归、源码/反射或仅注释引用；不为凑接线数修改。所有原断言继续参与根受控回归。
- **F**：有限测试装配工具，只在setup显式构造真实对象；保留BasicPlayCommandFixtures原身份，禁止在业务执行期间回读Controller。ROOT/G为需原owner单一落地的部分。

装配顺序固定为：原测试全部node/topic/registration/activity/runtime/progress桩 → 原当前资格锁fixture → 真实access → 若需要P/H则全依赖Puzzle应用 → Controller新字段。移走的四个Controller字段（clubLeadMapper、omsTicketService、cmsPointsSettingService、umsMemberService）不得继续Reflection.setField到Controller；改传对应新owner。其他Controller仍消费的依赖不删除、不另造实例。无需P/H的测试不装扣分应用。

## d359原清单（完整55份）

下表文件名链接到仓库内完整路径；N是d359的41个直接构造文件，R是其余14份引用。多个构造点与依赖写入位置见source inventory。

| # | 文件 | 原构造 | 操作 | 精确处理/保持 |
| --- | --- | --- | --- | --- |
| 1 | `chengyinhub-admin/src/test/java/com/chengyinhub/business/it/ComplaintPickerOwnerType3RealDbIT.java` | R | 0 | 仅注释引用；不改投诉/退款真库夹具或其owner。 |
| 2 | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ShopDayGameThenPhotoH2Test.java` | R | 0 | 仅注释引用shopDayProofData；不以H2作为本批MySQL证据。 |
| 3 | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/SpoilerIsNotAnswerGateTest.java` | R | S | 原答案/防剧透源码断言保留；所查正文不在本次移动范围。 |
| 4 | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/ChapterFlowWriteServiceTest.java` | N | A/R | 仅末段isPrevDone私有桥调用需注入真实access，使用同一nodeRead/progressRead；出版/拷贝部分不动。 |
| 5 | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/PlayKitBingoRuntimeTest.java` | R | 0 | 仅注释引用refreshBingo；宾果事实/奖励测试不变。 |
| 6 | `chengyinhub-admin/src/test/java/com/chengyinhub/business/util/AdvancedGamePublicProjectionTest.java` | R | S | 保留Controller公共投影负控；不修改高级玩法实现。 |
| 7 | `chengyinhub-admin/src/test/java/com/chengyinhub/business/util/ChapterAtmospherePresetTest.java` | R | S | 保留Controller氛围投影源码断言；没有准入接线。 |
| 8 | `chengyinhub-admin/src/test/java/com/chengyinhub/common/utils/GeoDistanceUtilsTest.java` | R | 0 | 仅历史haversine注释；地理算法不改。 |
| 9 | `chengyinhub-admin/src/test/java/com/chengyinhub/framework/aspectj/DeadlockRetryEntryPointContractTest.java` | R | S | 保留12个原retry入口和既有helper可达分析；unlockHint不加入retry；不得放宽成任意新service调用均算已保护。 |
| 10 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayBranchNodeVisibilityTest.java` | N | A/P | nodes使用同一topic/routes做mode；定价转既有Puzzle真方法，原selectHintLevels mock保留。 |
| 11 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayCheckinDuplicateCodeTest.java` | N | A | 原报名/活动/命令fixture后接真实access；保持重复静态码与目标节点断言。 |
| 12 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayCheckinLiveCodeTest.java` | N | A/L | 将现有install移至setup末尾；原自定义registrationMapper仍优先；后续替换access直接依赖后重接，动态registration service委托仍用原mapper。 |
| 13 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayCompletedRouteSourcesTest.java` | N | 0 | myCompleted当前不经这14个helper；保持CompletedRouteFactReader来源/只读断言，不新增access调用。 |
| 14 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayCurrentUseBoundaryTest.java` | N | A | 显式当前/旧报名mapper与no-lead对象传给access；保留真正旧票/取消/过期的拒绝，不套默认资格fixture。 |
| 15 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayDelistedCompletedRetryContractTest.java` | N | A/L | 移交lead/tickets；现有route/progress后置替换后重接access；完成status=1与status=0对照及原key重放不动。 |
| 16 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayEndingCompanionFactTest.java` | N | 0 | ending/companionLine读取路径不迁移；原事实授权断言不改。 |
| 17 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayGpsGeofenceValidationTest.java` | N | A | 原act/registration/lead/坐标夹具接access；GPS来源、精度和围栏负控继续到原分支。 |
| 18 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayHintUnlockTest.java` | N | A/P/H | 15个原场景不删；用真实Puzzle应用替换只返回默认值的mock；settings/member/ledger同对象，节点回包定价同源；单参重载、并发/重复/收费断言保留。 |
| 19 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayHistoryFulfillmentAccessTest.java` | N | 0 | 历史完成/片段读取未迁移；不将历史读改成新使用资格。 |
| 20 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayHistoryNodesContractTest.java` | N | A/P | nodes定价与mode、完成入口access接线；保留过期历史只读/拒绝新完成的对照。 |
| 21 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayHistoryNodesOldEntryContractTest.java` | N | A/P | 同上，保留旧入口明确当前runtime/action/version fixture，不退随机key或缺字段兼容。 |
| 22 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayLeadSpectatorGateTest.java` | N | A/P | nodes的mode/定价接线；clubEventLeadResolver观战权限原依赖不迁移、不复制。 |
| 23 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayLeaderboardTest.java` | N | 0 | leaderboard不调用resolveMode共享实现；原报名/隐藏榜单断言不改。 |
| 24 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayMyCompletedSelfPlayContractTest.java` | N | 0 | 当前myCompleted不经14helpers；自玩完成/原公开合同不改。 |
| 25 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayNodeNpcPreferenceTest.java` | N | A/P | nodes使用真实mode与定价；NPC私密缓存边界保持。 |
| 26 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayPhotoRetryAndFailureContractTest.java` | N | A/L | progress和cmsTopicService在个别case换对象后重接access；ProgressFact必须带id/status/picUrl，保留照片失败/重试经济断言。 |
| 27 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayProgressCompletionTruthTest.java` | N | A/L | case内runtime/progress/topic后置替换后重接access；保留合法原命令/verified origin/原版本与真实完成来源Wanted，不改成any。 |
| 28 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayProgressCouponGrantFailureTest.java` | N | 0 | 只反射grantCouponById；不迁移发券helper，无需装access。 |
| 29 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayProgressIdempotencyTest.java` | N | A | advanceProgress内部resolveMode接access；保留rollback/reward断言；若存在已移走且原不被此路径消费的member字段setField，只删无用接线，不新增扣分。 |
| 30 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayPuzzleHintTest.java` | N | A | 完整access接线；原真实Puzzle四参构造改九参，已有requestHint/reveal/score保持；新unlock方法不借用lockMember。 |
| 31 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayRouteConflictHttpContractTest.java` | R | A | TestController子类也要注入access；MockMvc/global handler 409与先于进度写断言保留，runtime使用同一对象。 |
| 32 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayRouteContractTest.java` | R | S | 保留各完成端点原参数/CAS/DTO反射断言；新hint应用不增加客户端actor/outcome字段。 |
| 33 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlaySelfPlayPassGateTest.java` | N | A/P/L | nodes的mode/定价接线；expiredPass用例替换原mapper/runtime后更新access捕获，延续原已开局资格语义。 |
| 34 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlaySensorResultTest.java` | N | A/L | lead/progress/sensor各原对象；替换progress后重接，传感器校验/原动作/version及零奖励负控不变。 |
| 35 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayUnlockGateTest.java` | N | A/R | 保留unlockGateError私有桥反射签名，真实access使用原progress/node mock；原前置节点文案完全保留。 |
| 36 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayVitalsOnTest.java` | R | 0 | 静态vitalsOn不迁移，无需准入接线。 |
| 37 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/BasicPlayCommandFixtures.java` | R | 0 | 保留原command/expected/session封装；不向hint造新ID，不重写任何pending/receipt约定。 |
| 38 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/CityStationOrderAcceptanceTest.java` | N | A/R | isPrevDone私有桥用同一node/progress事实；章节顺序和审核status=0拒绝保留。 |
| 39 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ClubLeadGateTest.java` | N | A/R/L | lead mapper/runtime/chapter直接装access；故障case重建明确null或throwing依赖，原403同对象、503/NOT_RUN和自玩零依赖负控保留。 |
| 40 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/CollectIdempotencyTest.java` | N | 0 | 只反射collect，helper不迁移；不新增应用/准入构造。 |
| 41 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/CurrentPlayControllerFixtures.java` | R | F | 移除向Controller写已移走lead字段；保留activity→registration原委托；新增有限真实access/Puzzle装配入口，capture发生在setup结束，不在业务调用时回读Controller。 |
| 42 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/FreeOrderCancelEntryGateAcceptanceTest.java` | N | 0 | 仅isRegistered旧活动资格反射，未迁移；取消/收费逻辑和owner不改。 |
| 43 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/GameplayControllerProtectionTest.java` | R | S | 保留22 POST预算/3 GET预算等原保护数量；不挪RateLimiter或给Controller类加事务。 |
| 44 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/JourneyPublishFreezeTest.java` | N | A/L | 先装所有runtime/catalogue/lead，再wire；真实Puzzle构造九参；冻结答案/偏好/原照片供应商夹具不变，不改root Photo服务。 |
| 45 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/LogAttemptFailureTest.java` | N | 0 | 只反射logAttempt；best-effort尝试日志不在此次用例迁移范围。 |
| 46 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/NodeMedalClaimTest.java` | N | 0 | 所测勋章/碎片与反射enrich不在14helpers；不增加准入。 |
| 47 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/O08PrivateLocationReadPathTest.java` | N | A/P | 仅其玩家nodes夹具接mode/定价；其他俱乐部/私密位置控制器不动。 |
| 48 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/PhotoOwnerBindingContractTest.java` | N | A/L | 原照片source校验器和progress事实；用id/status/picUrl投影，不移动上传验证/授权；后置progress更新后重接。 |
| 49 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/PhotoReviewRewardContractTest.java` | N | A/P/L | nodes定价+photo准入共享；原machineApproveProgressId与CAS奖励一次性断言保留，按后置progress装配新access。 |
| 50 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/PlayCommandIntentTest.java` | N | 0 | basic action context/receipt读取鉴权不迁移，原hash/回执/版本冲突断言不变。 |
| 51 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/PlayProgressVarsTest.java` | N | A/P | nodes mode/定价接真实新owner；其existing selectHintLevels/vars mock继续有效。 |
| 52 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/PlayResolveModeTest.java` | N | A/R | 保留private resolveMode纯桥或直接新增access用例；topic产品真源/旧ticket分支同对象和verify(never)断言不变。 |
| 53 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/PlaySelfPlayCompletionTest.java` | N | A | advanceProgress→isTopicCompleted→resolveMode接access；actor/topic作用域、原完成/奖励断言不变。 |
| 54 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ShopDayExperienceLayerContractTest.java` | N | A/P/L | nodes定价、preference/checkin/arrive准入接线；原registrationMapper/PK3核销与到店链不改；settings转Puzzle，ledger在nodes仍同一实例。 |
| 55 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/TopicRouteFrozenSessionReadSideTest.java` | N | 0 | 所测myCompleted/stats不经14helpers，保留冻结完成读侧/查询来源，不自动建局。 |

## 当前新增4份（不把55/41当现状）

| 文件 | 操作 | 处理/owner |
| --- | --- | --- |
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/it/PlayHintUnlockMySqlIT.java` | A/H/ROOT | root所有。授写时先交精确夹具delta：10个普通@Test正文/清理不变；actor查身份移到事务外，实际可写事务/真实CONNECTION_ID/latch移到应用首次node读测试桩、原资格锁之前记录，禁止删除事务/并发证明。 |
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/it/PlayLeadAdmissionFailureMySqlIT.java` | A/H | 原4个@Test/精确schema清理保留；hint完整应用代理移TX，资格/claim断言仍实际可写TX；preference GET/POST仍无外层事务、真实route实例零进入及恢复SQL不变。 |
| `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ArchitectureDayGCompletionFallbackTest.java` | 0/G | 当前myCompleted只读不经14helpers；不改G的合法paid pure-content/无票FREE_EXPLORE负控。 |
| `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/PlayLeadAdmissionFailureTest.java` | A/H/L | fault lead对象装入access；hint调用真实Puzzle应用，其他puzzle方法继续受零业务交互断言；原16项/服务故障和403/顺序负控不删，不以进入准入应用入口本身当奖励副作用。 |

## 既有Puzzle直接构造与其他生产caller

既有四参构造计划改为单一九参构造，四个直接new文件全部显式传原四依赖及新增nodes/access/settings/memberService/detailService；不保留null参数兼容构造、不新增依赖可选fallback：

- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/PuzzlePlayServiceImplTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/PuzzleRevealArrivalFactTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayPuzzleHintTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/JourneyPublishFreezeTest.java`

其中PuzzlePlayServiceImplTest/PuzzleRevealArrivalFactTest只测原谜题方法时，新五依赖显式mock且不得交互；ApiPlayPuzzleHintTest/JourneyPublishFreezeTest使用真实access与原同一目录服务。新增独立NodeHintUnlockApplicationTest不构造Controller。

另一个生产注入者ApiClubController仅使用原selectPuzzleTemplate/revealText等API，不改其授权/实现；ClubTopicNodeAnswerAccessTest保留其mock与权限断言。接口仅追加两方法，禁止要求所有IPuzzle mock统一返回成功。

## 受控验证与根协调

此清单未运行Java/Mockito/MySQL。未来先在B1旧候选确认原断言，再同源跑全部受影响unit、原hint10+B1 IT4、direct application与启动/依赖门禁；真实计数回读XML而非本表行数。root所有hint10夹具须先对齐精确delta/用例数，保留其两普通入口和并发latch。D的topic copy授权hunk、G资金、Theme/APPLIED/root Photo均不在此计划写入范围。
