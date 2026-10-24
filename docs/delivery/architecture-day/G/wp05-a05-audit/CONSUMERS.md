# source → statement → caller → API/页面

被审源统一为91962c94。SQL路径前缀 `chengyinhub-system/src/main/resources/mapper/business/`；Service通常在system/business/service/impl，Controller在admin/web/controller/api。完整路径、源码行号、72处点调用与文本见JSON/附录。下表覆盖28条SELECT；不把wrapper当额外SQL，不把字面搜索当运行调用图。页面字面量及共享client见`client-call-sites.json`；页面列是该接口可影响的显示范围，不证明本次实际打开/请求过。

## 1. 全部直接PG读取语句

| XML:行 / statement | 实际集合/时间/隔离 | Java消费者→接口/显示范围；裁决 |
| --- | --- | --- |
| PlayerNodeProgress:28 selectPlayerNodeProgressList | del0；按传入member/activity/topic/node/显式instance收窄；无显式instance且member+activity齐全时按每行topic的latest route instance/default过滤；**probe.status不参与SQL** | PlayerNodeProgressService38封装；28文件中的大量读用途见第二节，消费者必须自行明确status及activity；ShopDay直接Mapper也受该默认当前局过滤 |
| PlayerNodeProgress:43 countPlayersByTopicId | topic下任意del0行distinct member；含到达0，不限instance | PlayerNodeProgressService50→ApiTopic1806 `/api/topic/info-to-user`玩家人数。不能解释为完成者 |
| PlayerNodeProgress:50 selectPlayerNodeProgressById | 仅id，不自带del/status/owner | Service44 wrapper；未找到其外部生产点调用。未来不能作为带权限的事实端口 |
| PlayerNodeProgress:127 selectPuzzlePersonalBest | status1，按activity/instance等规则聚合有效公开节点的puzzle score，个人best | Service70→Play.nodes925/advance3304；玩家表现/榜。不是核销/奖励事实 |
| PlayerNodeProgress:184 selectActivityLeaderboard | 当前instance PG1，按活动/topic条件聚合成绩、完成数；finishTime为MAX(checkin_time) | Service76→Play.leaderboard3544、Game.clubView3968；普通ROUTE合法旧榜，新Generic完成来源不同，不能从0分推断未玩 |
| PlayerNodeProgress:221 selectRouteLeaderboard | latest route session；reachedEnding来自session.status1；elapsed=completed-create；PG1同instance算score | Service82→Play.leaderboard3543；session终局可已真完成而PG score0，这两个字段不同 |
| PlayerNodeProgress:231 selectRouteLeaderboardRow | 同上，当前member排名行 | Service88→Play.leaderboard3554；保留全榜与本人一致 |
| CmsRegistration:1092 selectPureContentExploreCompletion | paid2/reg2/purchaseKind3、真实activity.topic；每member任一合格报名的必需chapter游戏节点全部存在PG1；不分PG instance；另要求确有PG1 | PureContentExploreService59→PerkLedger.exploreCompletion→ApiStats.club；跨局拼全与单票Java当前局差异G05-03，当前不是发奖入口 |
| ViewCreativeSquare:218 selectProgressCountsByMemberActivities | member/activity pairs的del0 PG行数，含0/1和历史instance | ViewCreativeSquareService143→CreativeSquare296 batch enrich；展示进度不是完成事实；后续route/explore覆盖分支不能漏读 |
| ViewCreativeSquare:234 selectProgressCountsByMemberTopics | member/topic pairs同类行数 | ViewCreativeSquareService148→CreativeSquare298；同上，不得宣称其全部按旧行数判通关 |
| OrientScoring:95 selectClearedProgress | member/activity/topic/显式instance，status1+review_status1，原score/performance | OrientScoring249 syncSession→Orient scoring API/后台；可能影响原计分/结算，不能无批准把任何新outcome映射为同级合格成绩 |
| Orient:206 countLegacyNodeDone | member/activity/node/instance/status1，topic可选 | OrientTask338 completedVia→Orient task/state/submit等；旧任务证据的一支，不能把PG0当验证 |
| Orient:246 selectCompletionFacts | orient_task_completion UNION PG1，同activity/topic；PG支无instance过滤，按member/task earliest time | OrientTeamBoard237 facts→`/api/orient/team` lobby/detail/ranking；历史/当前混合需真实数据评估，不等于奖励已经重复 |
| MerchantInsight:51 selectVisitFacts | paid探索票/实际商家有效offer/entitlement与PG到达或ACTIVE redemption，reg/chapter粒度 | MerchantInsight114 insight、165 dashboard→`/api/ai/merchant/insight`、`/api/merchant/dashboard`；到店/核销metrics，不是解题完成 |
| MerchantInsight:61 selectCrowdFacts | 同真实票/商家/权益scope的客群 | MerchantInsight115→AI insight；不把Game完成扩为商家客群 |
| MerchantInsight:91 selectVisitorEvents | 同域到店/核销事件/时间 | MerchantInsight118→AI insight；最早arrival与核销时间不可互换 |
| ClubEventOps:564 selectPaidRoster | paid名单；COALESCE领队checkin/报名verified/PG1导出base arrived，再叠最新attendance correction；PG不分instance | ClubEventAttendance49 roster→ApiClubEventOps206 `/api/club/event-ops/roster`与ClubLead418→领队team-progress；这是运营出勤口径，不擅改为任何PG0就签到 |
| ClubEventOps:607 selectAttendanceTargetForUpdate | 同签到目标，锁原registration，校正有原对象/审计 | ClubEventAttendance102 correct→ApiClubEventOps218 `/api/club/event-ops/attendance/correct`；读取并非纯报表，替换需保护锁/权限与人工校正优先级 |
| OrientSession:46 selectVerifiedCompletion | member/activity/topic/node/instance/status1/del0/checkin>=since | NodeProgressOrientTaskEvidenceVerifier22→Orient task可信证据核验；ShopDay checkin时间早于完成的G05-04会影响since比较，须夹具验证实际允许模式 |
| TopicOnsiteMetrics:33 selectTicketFacts | paid/refund探索票facts；arrival有PG checkin并有Java核销fallback；completed为ACTIVE redemption章数 | TopicOnsiteMetrics26→`/business/topicFunnel/{topicId}`；主题到场/服务完成漏斗，不能重定义为Generic解题 |
| TopicOnsiteMetrics:87 selectWaitFacts | 原reg/chapter最早PG到达→redemption服务时间，不分instance | TopicOnsiteMetrics63/71→同漏斗等待与chapterWaits；历史重复场次需DB，不能将PG checkin一律称任务完成时间 |
| TopicOnsiteMetrics:126 selectInStoreLoads | 当前paid/entitlement0未核销、PG arrival，按chapter distinct registration | ApiPlayProgress.nodes518→节点详情现场人数；不是任务完成榜，保持票/权益维度 |
| GameSessionRuntime:263 selectClubRouteTeamProgressProjection | 当前paid队伍成员、session station关联，PG1合并队员完成节点；无PG instance过滤，以raw station数判团队进度 | GameRuntime.clubView3959→Game.view CLUB teams；普通ROUTE有来源；Generic TEAM owner→PG缺镜像且ANY_N不等于raw station，G05-02候选真链优先验证 |
| GameSessionRuntime:331 selectMerchantStationRecaps | 本session实际站点/当前paid成员；arrival PG；ROUTE完成PG1，非ROUTE完成outcome | GameRuntime3843→Game.view MERCHANT站点复盘；已有分源分支，Generic TEAM ROUTE为特定缺口，不称Game全漏 |
| GameSessionRuntime:628 selectClubRecapAggregate | paid/arrival/submission等分别算；ROUTE正常完成PG1，非ROUTE正常/兜底outcome；raw stations判finishedTeams | GameRuntime3989→club recap/view/export；正常与兜底distinct member集合可相交，不能加人数冒充总人数；FINISHED可读已冻结recap_json，历史更新不是重跑SQL就全变 |
| GameSessionRuntime:711 selectClubStationRecaps | 同源按station拆正常/兜底/到达 | GameRuntime3990→club recap/view/export；需同aggregate一致，保留现有非ROUTE正控 |
| ClubMember:167 selectClubContribution | clearCount/mileage由TOPIC_CLEAR/TOPIC_FINISH player_badge按topic去重；duration仅badge-qualified topic的PG MIN/MAX，group member/topic/activity不分instance；hostCount另scope | ClubMember559→`/api/club/leaderboard`；完成/里程已由award事实决定，不能换任意PG或TEAM局内章。duration的跨局解释技术债 |
| ShopDayMerchantRoster:31 selectRoster | 原paid探索票、商家/offer/entitlement，PG到达或ACTIVE redemption，reg/chapter分组 | ShopDayRoster48→ProjectPlayerRead117-120→`/api/project/players`；现场名单/服务进度，保留手机号consent/归属gate，不从新完成state绕过 |

另外4条写statement（55 insert、99 photo、109 photo machine CAS、117 ShopDay game CAS）与原A748事实对应；逐条调用、受外层事务与时间语义见RUNTIMES。没有授权迁移它们。

## 2. PG通用列表的每类Java用途（全部直接文件）

下列与上表其余专用statement一起覆盖JSON中28个Java文件。Service/Controller重载名可能多处；逐次行号在附录。源码最近方法名只是定位，手工确认receiver和调用链，不主张生成了javac完整调用图。

| 文件 / 关键行 | 用途与上游调用范围 |
| --- | --- |
| ApiStatsController103 | `/api/stats/club`：topic PG任意行participants/漏斗、Java status1 completedNodes；另已union route participants/completed与FREE_EXPLORE PerkLedger。route Mapper distinct-member SQL读历史任一session，不等同my-completed最新。页面club/detail，不得报“全部只看PG” |
| ApiCreativeSquareController296/298/1192 | `/api/creativesquare/list`、info enrich；summary。PG旧进度行次/参与，route status可覆盖主题完成、探索服务另覆盖。summary候选PG参与activity∪paid explore再查route status，不等于从route独立发现所有候选；activity0自玩不是真实活动。候选覆盖需接口夹具，不虚构漏算比例 |
| ApiMedalController59 | `/api/medal/wall`模板章status1、成就player_badge；subP3/badge-wall与play结束弹层；与局内MEDAL分域 |
| ApiPlayProgressController429/1124/1296/3121/3228/3340/3574 | nodes/ending/companionLine、advance幂等/当前完成计数、my-completed、leaderboard fallback；status1与topic/activity scope在Java。my-completed已有CompletedRouteFactReader批量最新事实接线；不建议回退成仅PG。基础checkin/answer/arrive/photo与Generic分流见RUNTIMES |
| ApiClubLeadController448 | `/api/club/lead/team-progress` paid名单后按activity读PG，Java过滤1；probe没有member时Mapper默认latest条件不生效，显示成员节点行次/队伍进度；是否需要当前局是明确消费口径问题 |
| PureContentExploreService79 | completedChapters对当前member/activity/topic的PG1检查每个required chapter；与batch SQL不分instance有集合差异，不等于所有探索完成都该用PG（有服务核销分支） |
| PlayNodeAccessService128/143/182 | 读取现有节点行、前驱完成、unlockGateError；上游基础play操作，属于解锁/资格gate，不宜把报表投影回灌后放宽前驱 |
| PlayJourneyReadServiceImpl536 | `/api/play/journey/casebook`、backpack（只读投影）；原PG1/current instance与已有主题来源聚合，doneAt取checkin/create fallback。缺activity时不据此扩大读取 |
| GameSessionRuntimeService4263 | hasArrived判checkin非空→Game当前验证/站点动作；到达不是完成；同文件专用运营SQL见上表 |
| NpcMemoryBuilder45 | 旧build里status>=2漂移；当前NpcChatService165只调用buildRoam，未观察到build生产点调用。记录潜在风险，不说真实NPC少算 |
| SensorChallengeService54 | alreadyCompleted幂等检查，基础sensor入口；改成宽泛“任何来源完成”可能误放奖励/重复，不授权 |
| PlayerNodeProgressService38 | 绑定当前instance的读wrapper；其余6个专用读wrapper见上表。必须区别直接Mapper调用是否经过instance绑定 |
| PlayBingoService300 | loadProgress member/topic，跨activity/instance历史；虽然probe.status=1但Mapper不消费，Java已有显式status1保护。由AdvancedGameRuntime4654 playKitView→`/api/play/advanced`，另PlayProgress3695 onNodeDone。关联格子/奖励，不能改历史集合顺带发奖 |
| TopicRouteRuntime2461/2468/3901/3965 | Legacy hasCompleted/completed/routeStarted/syncTheme medals；Generic分支不依赖PG1推进。API `/api/play/theme`与原route coordinator，不能把这些读调用当已镜像Generic完成 |
| ShopDayProof44/66/103/145/157/180 | 到达exist/并发回读/兄弟节点exist/hasArrived/photo与不可覆盖回读，属行为链而非纯metrics；G05-01须修原实例写入，不靠报表union掩盖 |
| PuzzlePlay205 | recordReveal查当前原progress：出答案与原完成表现/重放边界；不能将REVEALED当正常验证 |
| PlayEncounter268 | revisit历史原节点/完成→`/api/play/encounter`；按actor和实际scope保留，不能凭TEAM共享所有人的个人记忆 |

其余11文件为：ViewCreativeSquareService143/148；ClubMemberService559；MerchantInsight114/115/118/165；TopicOnsiteMetrics26/63/71；NodeProgressOrientTaskEvidenceVerifier22；OrientTeamBoard237；ClubEventAttendance49/102；OrientScoring249；OrientTask338；ShopDayMerchantRoster48；ApiTopic1806。专用statement链均在第一表。共28文件，以JSON去重计数为准（这里按用途重复提到wrapper不再计文件）。

## 3. 用户可见范围与不能跨越的边界

- `utils/game-session-client.js`集中访问session view/command/receipt；play页面、俱乐部导演adapter、商家game-node和入口复用。前端会读后端teams/recap/榜并不证明数据差异已经发生，需原角色真链负控。
- `/api/stats/club`、俱乐部leaderboard、主题到场funnel、商家dashboard/AI insight、商家现场名单是不同人群/粒度/时间定义；不要提供一个模糊`completedCount`直接替换全部。
- 影响奖励/核销/计分/解锁的读：advance首完、前驱gate、sensor/揭晓、Bingo、Orient验证/计分、探索/核销资格。它们不能只因“兼容新runtime”扩大合法集合。到店/票/核销metrics和纯展示兼容端口可以先按原scope迁移，但每个consumer要有原样正控。
- 数据库历史异常率、运行中的漏算人数、冻结旧recap的覆盖、真实商家或团队资格人口均未取样验证；源码没有字段/没有测试不等于生产已错。
