# 三 runtime 原事实、写入口与奖励边界

以下路径/行号均绑定 `91962c94af463cbfe9b4d4406f9d88d269b097e4`；不是运行日志。Java简称的完整路径见 `source-identity.json`，SQL逐条见 `progress-statements.json`。actor/team、activity/topic、instance/session是不同维度，数字相等不能作为关联。

## 1. 原写链与镜像方向

| 来源 | 入口→事务/回执→事实 | 范围、冻结与时间 | 现有镜像/明确未镜像 |
| --- | --- | --- | --- |
| Legacy/basic | `ApiPlayProgressController` `/api/play/checkin`(1504)、`answer`(1679)、`sensor`(2113)、`arrive`(2169)、`photo`(2382)、`reveal`(1812)；`executeBasicIntent`1431锁game session/current route、原actionId与请求身份去重、PENDING→APPLIED/FAILED结果；相关写端点RC事务，回执完成失败抛出。`advanceProgress`3072起insert progress，重复按原scope读回；photo CAS0→1/ShopDay CAS0→1才是本次首次 | 当前认证member；CurrentPlayRegistration验证真实报名/activity/topic；自玩activity0仍必须topic。`PlayerNodeProgressServiceImpl.instance`28/insert54在member+activity+topic齐全时绑定当前route playInstance，初局default。历史progress本身没有可靠独立冻结版本，不能据当前模板反造当时配置。新progress.checkin_time=now；ShopDay完成保留到达时间；机审转换更新时间 | Legacy progress1→route completion coordinator/route state是已有镜像。status0到达和status1完成不能混算。不是所有action都调用advanceProgress：Generic入口明确分流，拒绝旧奖励旁路 |
| ShopDay/到达 | `ShopDayProofServiceImpl.markArrived`42→直接Mapper.insert60；同章节同地点兄弟节点insert95；`submitPhoto`156→update；`markGameDone`151→条件0→1。到达由外层基础动作事务覆盖，service自身不是独立完成事务owner | 只共享相同member/activity/topic/chapter、同merchant或地址+30m范围的到达；不共享照片/完成/奖励。probe206只有member/activity/node；插入没有playInstance，落`chengyinhub-admin/sql/migration_theme_runtime_20261004.sql:11-15`指定default与instance唯一键。源码在replay非default时与当前实例读取不一致（G05-01），未运行反例见patch | Generic基础动作也会写这些status0到达，商家到店视图有原事实来源。因此“Generic完全没有progress行”不成立。到达不等于任务验证、节点完成或核销 |
| Game非ROUTE | `ApiGameSessionController.command`91→`GameSessionRuntimeServiceImpl.command`2599 RC事务：锁原session、当前权限/资格、原receipt、dispatch、CAS revision与APPLIED一起提交；失败审计2679 REQUIRES_NEW，不能视作完成。`outcomeForSubmission`5041与`ensureOutcome`5074：按session/member/node首个终态，原submission/receipt、station/team与fallback plan可追溯 | `PENDING/REJECTED`不是完成；RECORDED只对EVIDENCE_ONLY，APPROVED按AUTO_HASH/MERCHANT_APPROVAL形成COMPLETED；ANSWER_REVEAL(3315)/批准FALLBACK_PLAN(5123)是独立兜底来源。completed_at为服务端事实时间。session冻结config/原module快照与TEAM/WORLD owner范围，不能拿schemaVersion代替发布配置版本 | 新outcome没有自动写progress1，但`GameSessionRuntimeMapper`商家recap/俱乐部recap已在非ROUTE分支读取outcome；正常/兜底分源并非全漏算。STORY ENDING_RESOLVED不是所有成员个人主题通关，不自动发旧平台奖励 |
| Generic PLAYER | `ApiThemeRuntimeController`14 `/api/play/theme`：state22、action27、gameplay-preview36、gameplay-action44、replay56、card58、history60；`TopicRouteRuntimeServiceImpl.gameplayAction`4440附近事务→4458原request/action回执、owner/scope/version/RUNNING校验→QUEST_START/RESOLVE→原可信验证与成本→保存session+topic_route_action | 真实member/activity/topic/current session及playInstance；published route/theme/node snapshot、原session config_version与configSnapshotJson；`GameplayTaskEngine`155完成本局node slot/COMPLETED与completedNodeIds，首个slot含原eventId与结果。`syncTheme`3943结算并冻结story/result，设置completedAt。运行终态COMPLETED与失败ENDED不同 | Generic局内node/quest是owner状态，不反写Legacy progress1；基础到达会经markArrived写0。route session原终态已供my-completed/route榜/Stats部分读；不是无完成查询。虚拟物资/MEDAL不等于player_badge/券 |
| Generic TEAM/ROUTE | `ApiGameSessionController.command`91→同Game事务owner；`routeQuestAction`1540起验证当前角色/队长/paid、authorityEpoch、原owner与原command；同GameplayTaskEngine/owner quest settlement；`saveRouteOwner`522→`saveOwnerWithoutSurvival`528序列化game_story_state vars_json/CAS，原receipt终态同事务 | session与TEAM owner唯一范围；普通成员可提交可信凭证不意味着可消费团队物资；冻结配置/实际队伍、epoch、原node slot/event继续保留；ANY_N.quantity/排他组由`GameplayTaskEngine.actComplete`285决定，不能换成全部raw station数 | 不写progress1；clubView3937对全部ROUTE仍选progress团队投影3959/榜3968；clubTeams4847只合并角色不融合owner completedNodeIds。recap3989同SQL ROUTE分支。此处是具体兼容读差异G05-02；不能顺势删除普通ROUTE的合法旧PG读取 |

`TopicRouteRuntimeServiceImpl`2461/2468（hasCompleted/completed）、3901(routeStarted)、3965(syncTheme的Legacy分支)直接PG Mapper都为读取；GameSession的PG Service同样读取。当前没有从这些Generic完成路径向PG1写入的源码链。`TopicRouteRuntimeServiceImpl`1224/2175初局default，4634附近replay绑定`replay:<原sessionId>`；不是每次HTTP请求新经济身份。

## 2. 奖励、徽章与核销是独立事实

Legacy `advanceProgress`3154仅`firstTime && instance为default/null`进入原奖励链（service正常填default/current）；重玩仍写本局事实但不重发平台积分/券/成长/排名。源码原guard与策略继续有效，读投影不能重跑此函数。

| 事实/权益 | 原入口和门槛 | 兼容读取必须保留的区别 |
| --- | --- | --- |
| Legacy节点积分/券/XP/掉落 | Controller3154+：非ShopDay且FULL才pointsAward(member,12,node)；模板券还排除photo。XP_BADGE_ONLY限制传感器自报/静态码/照片兜底等；fragment、成长、掉落、排名章各有本身gate。ShopDay模式原政策只保留批准的探索值/收集，不扩经济权益 | status1不证明有积分/券资格；不能因“统计完成”补发。`PlayerProgressionServiceImpl.onNodeDone`104计累计/连续与徽章，只在原gate成功调用。API回传实际medalRank不是配置有medalName就中奖 |
| 主题完成奖励/章 | `TopicCompletionAwardServiceImpl`40/48事务+死锁重试；player_badge主题source/code原唯一身份，精确重复读回；TOPIC_CLEAR仅首次调用成长；FULL原主题积分/券，XP另有原事实。Controller3260附近分支路线用route状态、探索认核销；`TopicRouteRuntimeServiceImpl.grantOpeningAward`324只针对opening.themeComplete | 不能推断所有Generic终局都调用平台主题奖。完成事实、奖励资格、已到账、clawback是不同列；用旧award事实证明已发，不用“今天能过规则”补历史 |
| 模板章/成就墙 | `ApiMedalController.wall`51/59：member范围PG列表→Java status1→当前node/template medalImg，按模板去重/getTime=checkin；另查player_badge按code去重。`ClubMemberMapper.selectClubContribution`167的clearCount/mileage看TOPIC_CLEAR/TOPIC_FINISH，不是PG行数 | Generic局内MEDAL与平台成就是不同域；TEAM完成不自动复制给全部成员。旧墙用live模板有历史解释技术债，不凭空写冻结版本或认定漏发 |
| 履约核销 | `registration_entitlement`状态与ACTIVE redemption、真实原registration/chapter/member；商家roster/onsite/探索完成服务读取。PG到达/照片是服务证据之一，不能替代原核销回执 | ENTITLEMENT_REDEEMED与NODE_COMPLETED不互相推出。原报名scope不能用topicId/activityId相等猜；退款/撤销核销仍按源状态，不让历史投影改变经济资格 |

## 3. 时间与终态不可归一成一个字段

- ARRIVAL：PG.checkin_time可以是到达时间，共享兄弟节点同一时间不代表每个任务都验证过。
- TASK_VERIFIED：原submission/validation通过仅对当前attempt/任务；有成本、库存、scope或版本失败仍不能成为NODE_COMPLETED。
- NODE_COMPLETED：Legacy是status1；Game是明确正常/兜底outcome；Generic是原owner node slot完成。photo CAS改checkin_time，ShopDay CAS不改；不能用字段名直接比较跨来源速度。
- THEME_COMPLETED：route COMPLETED/result、Game原结束含义、纯内容探索/服务核销各自有范围；失败ENDED、任意章节或任一队员完成都不自动成为个人主题通关。
- ENTITLEMENT_REDEEMED：ACTIVE回执/原权益终态；奖励已获：独立award/badge/coupon/ledger事实。历史时间缺失应标未知，不以抓取时刻回填首次完成，不构造VERIFIED。

## 4. 现有夹具可复用范围（仅源码检查，本批均未运行）

- `business/it/ThemeRuntimeMySqlIT`700附近`ownReplayIsFreshAndKeepsOldResult`：真实session结束/重玩、保留旧结果；本批arrival patch只复用这些真实Mapper/迁移及合法操作，未合入测试树。
- `business/service/impl/GenericRouteRuntimeServiceTest`447/457/465：可信裁决、实际node推进、ANY_N排他组；是既有单测源码，不能称真MySQL/CLUB读端已验证。
- `business/service/impl/GameSessionRuntimeServiceImplTest`与`business/mapper/GameSessionRuntimeMapperSqlTest`：保留普通ROUTE PG分支/非ROUTE outcome正控，不能为了Generic新分支改旧预期。
- `web/controller/api/ApiPlayProgressIdempotencyTest`、`PhotoReviewRewardContractTest`、`ShopDayExperienceLayerContractTest`、`business/service/ShopDayProofServiceTest`、`business/it/ThemeCompletionRewardCapMySqlIT`：原首完/兜底cap/ShopDay/到达/重试范围；文件存在不等于当前source运行通过。
- `web/controller/api/ApiStatsClubCompletionFactTest`、`ArchitectureDayGCompletionFallbackTest`、`business/service/ExploreCompletionBatchTest`及`support/PureContentExploreServiceTest`：原完成读口和危险fallback必须继续保留。历史红/绿仅按root对应source/XML，不由本批重新裁决。
- `business/mapper/ShopDayMerchantDataBoundaryMyBatisIT`、`service/ShopDayOneTicketMySqlIT`、`it/JourneyFullFlowMySqlIT`、`service/impl/PlayJourneyReadServiceImplTest`：票/商家隔离、原故事读取可复用；H2/Mock结果绝不能冒充真实MySQL或全链验证。
