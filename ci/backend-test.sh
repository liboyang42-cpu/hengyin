#!/usr/bin/env bash
# 后端门:编译 + 单元测试。CI(云效 Flow)与本地共用。
# 用法:bash ci/backend-test.sh   (从任何目录执行皆可,脚本自己回到仓库根;CI 环境有网,自动拉依赖)
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1   # 路径均为仓库根相对,见 ci/test_cwd_independence.sh

python3 -m unittest ci/test_role_permission_contract.py
GATE_SELFTEST=1 GATE="$PWD/ci/assert_it_ran.py" python3 -m unittest ci/test_assert_it_ran.py

# 全量编译 + 跑测试。首次接入若命中历史遗留失败用例,
# 先在此收敛或用 -pl 缩到改动模块,再逐步扩全。
mvn -B clean test

# ---- 真库集成测试(*IT):必须点名跑 ----
# ★ 为什么要单独一段:类名以 IT 结尾,而本仓**没有任何 pom 配 surefire/failsafe**,
#   Maven 默认 include 只收 *Test/*Tests/Test*/*TestCase ⇒ 上面那句 `mvn test` 一个都不会跑。
#   2026-08-14 实测:这批里 9 套从来没被执行过,而其中 6 条真跑起来是**红**的
#   (库 schema 漂了、服务长出新依赖没注入),假绿掩盖了多久无从考证。
#   它们内部原本还有 assumeTrue(false):库连不上就整套 skip,而 skip 算绿 —— 已一并改成硬失败。
#
# ★ 2026-08-19 起这批改成**一类一库**:每个类连 chengyin_mybatis_<suite>,由 MybatisItDatabase
#   在本 JVM 首次取用时 DROP+CREATE(不是 IF NOT EXISTS —— 那等于把上次崩掉的现场当成合法初始态)。
#   建库要的权限 ituser 没有(service 只把 chengyin_mybatis_it 授给它)⇒ 默认走 root,
#   密码由 .github/workflows/ci.yml 的 backend 步注入(本机 root 空口令,service 是 root/root)。
# 这几套连的是 CI 的 backend job 用 services 起的那个空 MySQL8,
# 各自建表或加载 test/resources/it-schema 里【生产导出的真实 DDL】,跑完即弃。
# ⚠️ 不在此清单里的三套 Explore*RealDbIT 连的是 chengyin_explore_it,
#   由自托管的 ci/funds-mysql-it-gate.sh 负责;CoopServiceRealDbIT 已烂透,见其类注释。
MYBATIS_ITS=(
  WechatMemberRegistrationMySqlIT
  # 合成账号/内容：真实HTTP角色幂等与撤权、公开节点计数一致性及分页SQL预算。
  ClubRoleHttpMySqlIT
  PublicTopicStationCountMySqlIT
  PublicMemberFriendCountMySqlIT
  CommunityActivityReferenceMySqlIT
  CommunityMediaEditMySqlIT
  LegacyReplyCountMySqlIT
  AppMediaAssetMySqlIT
  # 2026-08-19:类间隔离契约本身也点名跑 —— 它有三条负控(共用必撞 / 真查 information_schema /
  # ★A 的遗留数据不许喂饱 B 的 fail-closed 前置)。不点名 = 没人看着隔离,那这批又会慢慢滑回共用。
  MybatisItSchemaIsolationIT
  ClubLeaderboardContributionMyBatisIT
  ComplaintPickerOwnerType3RealDbIT
  CoopMapperMyBatisIT
  CoopInviteOriginalRequestMySqlIT
  ClubPostLikeAuthorizationMySqlIT
  MemberPrivacyMyBatisIT
  MerchantGeoSortMyBatisIT
  NpcChatRetentionMyBatisIT
  NpcChatUserDataMyBatisIT
  NpcGeneratedAudioMySqlIT
  # 2026-10-20:当前NPC资格的过期/续期与真实账单DDL、幂等、事务回滚必须在MySQL执行。
  NpcCurrentRegistrationMySqlIT
  WxBillIngestionPersistenceTest
  NpcGovernanceMapperMyBatisIT
  # 2026-09-15 CR-927 节点NPC:npc_profile scope_type=4 的读闸(未审/停用不下发)、
  #   一节点一条的 uk_active_scope 唯一键、以及三条写语句的 scope 隔离 —— 全要真库才验得动。
  NodeNpcMapperMyBatisIT
  PendingRecruitTerminationMyBatisIT
  RoamExploreDayCandidateMyBatisIT
  # 2026-09-17 C-13/C-17:漫游地图图层的 found 按账号投影(roam_poi_found + roam_poi_reward_log)、
  #   待审/驳回投放不进玩家图层、玩家侧按 id 查询与后台全量查询不许混用。删任一条闸即红。
  RoamPoiPlayerVisibilityMyBatisIT
  TopicEditionReopenBlocksColumnMyBatisIT
  ShopDayMerchantDataBoundaryMyBatisIT
  TopicInsertColumnCoverageMyBatisIT
  CircleThemeSeedMySqlIT
  TemplatePreferenceJsonMySqlIT
  TemplateMarketLifecycleMySqlIT
  AdvancedGameRuntimeMySqlIT
  ThemeRuntimeMySqlIT
  GenericRouteRuntimeMySqlIT
  GameSessionIndependentRevisionMySqlIT
  # 2026-10-19 SC-SCAN-LEGAL / SC-CITY-END:商家核验只完成本站且玩家投影/订单/接待记录一致;
  #   FINISH 后复盘=结果行、订单/库存/权益/奖励=实际履约、二次 FINISH 不改任何行。真库真 mapper 才验得动。
  GameSessionScanLegalMySqlIT
  GameSessionCityEndMySqlIT
  # 2026-10-22 发布验收 P1(导演台/商家城市节点/玩家城市局):准备→商家就绪→名单→开局、并发准备、
  #   全员/定向广播与存活范围、同 requestId 只补发失败投递、单站暂停/恢复与玩家读回、队伍/账号隔离。
  CityDirectorConsoleMySqlIT
  HangoutAuditLockMyBatisIT
  ImSendIdempotencyMybatisIntegrationTest
  # R9-02 迟到下注竞态:两个真事务交错(结算等锁定读、锁定读拒已结算),Mock 单测测不到
  PredictLateBetRaceMySqlIT
  # 结算事务兜底:结算中途中断必须整轮回滚(走 @Transactional 真代理),负控=去掉事务边界留孤儿注
  PredictSettleAtomicityMySqlIT
  # 2026-09-15 F15 联系号码接口:归属/负责/同意/日限与审计在真库上的边界(越权拒、撤回同意拒、
  # 俱乐部带团拒、昨日不占今日额度、第 51 次拒且不写审计)。不点名 = 没人看着这条链。
  MerchantCrmContactRevealMySqlIT
  # 2026-09-15 R9-41 假绿修复后登记:待审/驳回/下线的 *在线* 投放(status=1)只有
  # RoamMapper.xml 的 cityNodeContentApproved 能挡,删闸即红。不点名 = CI 永远跑不到这条边界。
  CityNodePendingVisibilityMySqlIT
  CityNodeCompletionIdempotencyMySqlIT
  # MySQL leader session, failover fence, strict Druid wall and transactional duplicate definitions.
  SchedulerSessionLockMySqlIT
  # 2026-10-22 发布验收 P1 俱乐部:入会通过/驳回、封禁/解封、举报工单在真库上的归属与幂等(真服务+真 mapper)。
  ClubJoinGovernanceMySqlIT
  # 2026-10-22 发布验收 P1 商家自由探索:节点编辑只改本行且审核痕迹真重置、非法提交零写入、
  #   入驻服务端定字段+唯一键挡重复、撤回只动自己的供给、邀约收发两侧条款一致。
  FreeExploreNodeSupplyMySqlIT
  ClubGatherArrivalMySqlIT
  ClubAttendanceTimeMappingMyBatisIT
  # 2026-10-22 W3 #334 F7:领队改集合时间/地点=单条「未售出才生效」条件 UPDATE;在途下单/修改在先两种交错都要真库。
  ClubLeadOpsSoldLockMySqlIT
  VerificationRiskQueueMySqlIT
  AccountDeregistrationRaceMySqlIT
  # R12-E2E 旅程全链路:建局→到店→问问题→判错→检定→推进→阶段结算→结局全走真库真 mapper;
  #   并发 settle 幂等另有一条负控(测试内临时摘掉动作/回执两把唯一键,断言并发重放消失后再还原)。
  JourneyFullFlowMySqlIT
  # Lane W10b:资料版本号递增 / 头像回执读 inbox / 分享版本 CAS 列与按令牌撤销,真迁移跑两遍证幂等。
  LaneW10bReadbackMySqlIT
  # 2026-10-22 AC1 发布验收 P0 链(docs/delivery/release-closeout-20261022/acceptance-p0-backend.md):
  #   核销(扫码/团码/撤销/双店员竞态)、跨账号、城市跑恢复竞态与冻结、自由探索供给闸、注销清理范围。
  P0RedemptionChainMySqlIT
  P0CrossAccountMySqlIT
  P0CityRunChainMySqlIT
  P0FreeExploreSupplyGateMySqlIT
  P0DeregistrationMySqlIT
  # 2026-10-22 发布验收 P1 玩家念头(领取/步数基线/达成加运气上限仅一次)与隐私(按用途撤回、到访头像窗口、
  #   导出不跨账号且外人游标被拒、注销申请幂等与仅本人可撤)。
  JourneyThoughtProgressMySqlIT
  PrivacyVisibilityExportMySqlIT
  # Lane DL1:赏金 NESTED savepoint 内真 1213 死锁必须抛给外层 @DeadlockRetry 整笔重试;两笔并发核销赏金只入账一次。
  MerchantReferralBountyDeadlockRetryMySqlIT
  # 2026-10-22 F2 RPG P0:属性定义老注册 upsert 保留扩展列、改 label 不改键名;角色卡台账只随更新的来源前移。
  ThemeCharacterMapperMyBatisIT
  # 2026-10-28 文档二 L8:属性标记迁移(老自定义整数值登记为属性)可重跑、开局设置整行覆盖、我的申诉并表与有效封禁。
  Doc2L8MapperMyBatisIT
  # 2026-10-22 DOC2 F3 Phase 1:套票账本迁移可重跑 + 形状负控、Phase 1 不动 settlement_claim(7 列唯一键原样)、
  #   A/B 同买一套票余额互不影响、快照冻结、同单位唯一在约、月度豁免唯一 —— 真 DDL + 真 mapper 才验得动。
  FreeExploreBundleLedgerMySqlIT
  # 2026-10-23 DOC2 FXE(O-07/F3 Phase 2 只设计):只钉 master 资金链现状 —— claim 先于自营闸、应付表写不进非核销事件、
  #   撤销后 claim 仍 ACTIVE、退款不触达应付、负额批次卡死不结转、倒扣改冻结额;并逐条在真 DDL 上执行只读对账 SQL。
  FundsChainO07PinMySqlIT
  # 2026-10-22 O-14 city_code 回填(RV3 B-519-1):南京西路(上海)/北京路(广州)反例、人工值不覆盖、重跑零变更、
  #   修正重跑只改本回填写过的行、只读预览与真实执行逐行一致 —— 真迁移 + 真 MySQL 才验得动。
  O14CityCodeBackfillMySqlIT
  # DOC2 O5c-(a):统一留痕只读投影(五张留痕表分查 + 合并)。
  AuditTrailProjectionMySqlIT
  # 2026-10-23 DOC2 O-06 part 1 券库存守恒:N 个并发领取打在库存 K 上恰好 K 个成功(按 id 发券 / 按来源发券且重放不占库存 /
  #   后台补录),负控=旧的无条件 +1 在同样并发下真超发。行锁串行化只有真 InnoDB 验得动。
  CouponStockConservationMySqlIT
  # DOC2 FXA(#478-3):主题通关奖封顶判据读真 game_runtime_session JSON 快照 —— 任一已完成站自报/兜底 ⇒ 封顶,
  #   别人的局、别的场次不算,视觉复核改判 PASS 后解除。
  ThemeCompletionRewardCapMySqlIT
  # Lane BF4(W48R SHOULD-FIX 1):同意记录 requestId 去重 —— 并发同号重放只落一行、不一致重放 409、
  #   RR 旧快照下必须当前读到原记录。类上门控 CHENGYIN_CONSENT_IDEMPOTENCY_MYSQL_IT=1,下面 mvn 行显式打开。
  ConsentRecordIdempotencyMySqlIT
)
IFS=, ; IT_LIST="${MYBATIS_ITS[*]}" ; unset IFS
# ⚠️ 必须带 -am:上面那句 `mvn clean test` 不 install 兄弟模块,
#   只写 -pl chengyinhub-admin 会去远程仓库找 chengyinhub-framework:3.9.0 而找不到
#   (2026-08-14 实测 CI 红;本地不红只因为本地仓库里躺着先前 install 的产物 —— 典型的
#   「我这儿能跑」)。
# ⚠️ 带了 -am 就必须 -DfailIfNoTests=false:上游模块没有匹配 -Dtest 的用例,
#   true 会让它们直接以「No tests to run」失败。
#   surefire 3.x 还要 -Dsurefire.failIfNoSpecifiedTests=false(与 -DfailIfNoTests 是两个开关,
#   少一个上游模块照样以「No tests matching pattern」失败)。
#   「到底跑没跑」的保证**不靠这两个开关**,靠下面 assert_it_ran.py 的回读。
CHENGYIN_IM_SEND_MYSQL_IT=1 CHENGYIN_FUNDS_MYSQL_IT=1 CHENGYIN_CONSENT_IDEMPOTENCY_MYSQL_IT=1 \
  mvn -B -pl chengyinhub-admin -am test -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false -Dchengyin.npc.expiry.mysql.it=true -Dtest="$IT_LIST"

# 回读观测:surefire 绿不够。skipped != 0 说明又有人把「连不上就跳过」放回来了,
# 那等于这段白跑 —— 必须红。(回执 ≠ 观测)
python3 ci/assert_it_ran.py chengyinhub-admin/target/surefire-reports "${MYBATIS_ITS[@]}"
