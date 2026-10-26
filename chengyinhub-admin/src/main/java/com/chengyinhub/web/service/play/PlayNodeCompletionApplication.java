package com.chengyinhub.web.service.play;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.UncategorizedDataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.common.constant.PlayMode;
import com.chengyinhub.common.constant.RewardPolicy;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IPlayerProgressionService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.ITopicCompletionAwardService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.IAdvancedGameRuntimeService;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.web.service.TopicRouteCompletionCoordinator;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.chengyinhub.common.core.domain.AjaxResult.error;

/** Single node-completion path and its ordered local reward effects. */
@Service
public class PlayNodeCompletionApplication {
    private static final Logger logger = LoggerFactory.getLogger(PlayNodeCompletionApplication.class);

    @Autowired
    private IAdvancedGameRuntimeService advancedGameRuntimeService;
    @Autowired
    private com.chengyinhub.business.mapper.PlayAttemptLogMapper playAttemptLogMapper;
    @Autowired
    private com.chengyinhub.business.service.IRewardDropService rewardDropService;
    @Autowired
    private com.chengyinhub.business.service.IXpBudgetService xpBudgetService;
    @Autowired
    private com.chengyinhub.business.mapper.BusinessAuditEventMapper businessAuditEventMapper;
    @Autowired
    private com.chengyinhub.business.service.IGrowthService growthService;
    @Autowired(required = false)
    private com.chengyinhub.business.service.IPlayBingoService playBingoService;
    @Autowired
    private com.chengyinhub.business.service.ICmsTopicService cmsTopicService;
    @Autowired
    private ICmsTopicNodeService cmsTopicNodeService;
    @Autowired
    private ICmsMemberTemplateService cmsMemberTemplateService;
    @Autowired
    private IPlayerNodeProgressService playerNodeProgressService;
    @Autowired(required = false)
    private ITopicRouteRuntimeService topicRouteRuntimeService;
    @Autowired(required = false)
    private TopicRouteCompletionCoordinator topicRouteCompletionCoordinator;
    @Autowired
    private IPlayerProgressionService playerProgressionService;
    @Autowired
    private IPointsAwardService pointsAwardService;
    @Autowired
    private com.chengyinhub.business.service.IOrientScoringService orientScoringService;
    @Autowired
    private ISmsCouponHistoryService smsCouponHistoryService;
    @Autowired
    private ITopicCompletionAwardService topicCompletionAwardService;
    @Autowired
    private IShopDayProofService shopDayProofService;
    @Autowired
    private com.chengyinhub.business.mapper.MemberCollectibleMapper memberCollectibleMapper;
    @Autowired
    private com.chengyinhub.business.mapper.NodeMedalClaimMapper nodeMedalClaimMapper;
    @Autowired
    private com.chengyinhub.business.service.INodeMerchantOverrideService nodeMerchantOverrideService;
    @Autowired
    private PlayCompletionFacts playCompletionFacts;
    @Autowired
    private PlayNodeContext playNodeContext;

    private final com.chengyinhub.business.service.support.RewardRulesValidator rewardRulesValidator
            = new com.chengyinhub.business.service.support.RewardRulesValidator();

    public void collect(Long memberId, int type, Long topicId, Long refId, String content) {
        if (memberId == null || memberId <= 0 || refId == null) return;
        if (memberCollectibleMapper.countOne(memberId, type, refId) > 0) return;
        com.chengyinhub.business.domain.MemberCollectible c = new com.chengyinhub.business.domain.MemberCollectible();
        c.setMemberId(memberId);
        c.setCollType(type);
        c.setTopicId(topicId);
        c.setRefId(refId);
        c.setContent(content);
        c.setCreateTime(new Date());
        try { memberCollectibleMapper.insertMemberCollectible(c); } catch (DuplicateKeyException ignore) {}
    }

    /** M9 埋点:记录一次节点尝试。普通旁路失败保持 best-effort；瞬态数据库失败必须交外层回滚。
     * attemptType 1答题2扫码3GPS4拍照;result 1过0错2放弃 */
    public void logAttempt(Long memberId, CmsTopicNode node, Long actId, int attemptType, int result, String wrongAnswer) {
        try {
            com.chengyinhub.business.domain.PlayAttemptLog log = new com.chengyinhub.business.domain.PlayAttemptLog();
            log.setMemberId(memberId);
            log.setTopicId(node != null ? node.getTopicId() : null);
            log.setNodeId(node != null ? node.getId() : null);
            log.setActivityId(actId);
            log.setAttemptType(attemptType);
            log.setResult(result);
            if (wrongAnswer != null && wrongAnswer.length() > 120) wrongAnswer = wrongAnswer.substring(0, 120);
            log.setWrongAnswer(wrongAnswer);
            log.setCreatedAt(new java.util.Date());
            playAttemptLogMapper.insertPlayAttemptLog(log);
        } catch (TransientDataAccessException failure) {
            // A lock failure can abort the database transaction; let the outer owner roll back/retry.
            throw failure;
        } catch (Exception e) {
            logger.warn("埋点写入失败 member={} activity={} topic={} node={} attemptType={} result={} errorType={}",
                    memberId, actId,
                    node != null ? node.getTopicId() : null,
                    node != null ? node.getId() : null,
                    attemptType, result,
                    e.getClass().getSimpleName(), e);
        }
    }

    /**
     * 节点名次奖牌:全平台头三个完成该节点的人拿金/银/铜。返回夺得或本已持有的名次,三档满返回 null。
     *
     * 并发裁判是 node_medal_claim 的 PRIMARY KEY (node_id, medal_rank),不是应用层。
     * ⚠️ 不许改成「先查最大名次再写下一名」:本方法在 READ_COMMITTED 里,两个同秒事务会读到
     *    同一快照、双发金牌;而 checkin_time 是 DATETIME(秒精度),事后也判不出谁先。
     * ⚠️ 必须留在外层 @Transactional 内(不要 REQUIRES_NEW):拆出去 = progress 提交了、claim 没写成,
     *    而档位已被后来者占走 —— 这个洞不可修复。宁可承受排队。
     * ⚠️ 依赖 MySQL/InnoDB:失败的 INSERT 不毒化当前事务,循环里可以接着发下一条(Postgres 需 SAVEPOINT)。
     * ⚠️ 依赖 READ_COMMITTED:撞 1062 时冲突行必然已提交(未提交只会阻塞),所以回读一定看得见它。
     *    改成 REPEATABLE_READ 会把「我其实持牌」误报成「无牌」(uk_node_member 仍兜住不双持,只是显示错)。
     *
     * 【行锁持有多久 —— 没解决,只是量过了,结论是今天不用管】
     * 抢占 INSERT 的 PK 行锁从这里一直持有到【外层事务提交】,后面还有 total 查询、done 查询、
     * topicRouteCompletionCoordinator.advance(有写)、topicCompletionAwardService.grantOnce(有写+发徽章)。
     * 「放最后一行所以锁得短」只是相对链首短,不是解决。真正让它不痛的是【争抢面极窄】:
     *   · 只有【冲突行尚未提交】才会阻塞。某 node 的三个档位一旦被提交占满,后来者撞的是已提交行,
     *     1062 立即返回、一微秒都不等 ⇒ 一个 node 一生只有【头三次完成】可能排队,不随表长大而变多。
     *   · 排队上界 = 前面赢家的事务尾时长(2 条查询 + 2 处写),几十毫秒量级;
     *     innodb_lock_wait_timeout 默认 50s,差三个数量级。
     * 触发阈值(到了才需要动手):【同一个 node 的前三次完成落在同一个事务尾窗口(~50ms)内】,
     * 现实形态 = 开场时几十上百人同时扫同一个打卡点。今天生产 player_node_progress 全表 1 行
     * (1 玩家 1 节点),不存在这种场景。
     * 真到那天的改法【不是】挪去 REQUIRES_NEW(见上面第二条 ⚠️,那会造出不可修复的洞),
     * 而是把事务尾那两处写挪到抢占【之前】:抢占仍在同一事务内,但行锁只持有到 commit 那一小段。
     */
    public Integer claimNodeMedal(Long nodeId, Long memberId, Long activityId, Long topicId, Long progressId){
        // Spring 运行态该依赖是 required;null 仅兼容直接 new Controller 的存量纯单测。
        if (nodeMedalClaimMapper == null || nodeId == null || memberId == null) return null;
        for (int rank = 1; rank <= 3; rank++) {
            try {
                if (nodeMedalClaimMapper.insertClaim(nodeId, rank, memberId,
                        activityId == null ? 0L : activityId,
                        topicId == null ? 0L : topicId,
                        progressId == null ? 0L : progressId) == 1) {
                    return rank;
                }
            } catch (DuplicateKeyException dup) {
                // 撞的是 PK(该档被别人占)还是 uk_node_member(自己早已持牌)?
                // 不解析错误文本 —— 回读一次点查,让库自己说。
                Integer mine = nodeMedalClaimMapper.selectRankByNodeAndMember(nodeId, memberId);
                if (mine != null) return mine;   // 换局重玩/重放:幂等返回原名次,不再往下抢
                // 否则该档被别人占了,试下一档
            } catch (DeadlockLoserDataAccessException deadlock) {
                // 1213 死锁:MySQL 回滚的是【整个事务】,不是这一条语句。吞掉它 = 后面的路线推进 /
                // 通关发奖会落在一个新的隐式事务里提交,而打卡那条 INSERT 已经没了 —— 静默丢打卡
                // 比报错严重得多。必须往外抛,让外层 rollbackFor 整单回滚、由前端重试。
                throw deadlock;
            } catch (TransientDataAccessException transientFailure) {
                // 1205 锁等待超时(CannotAcquireLockException)这类【短暂性】写失败:
                // innodb_rollback_on_timeout 默认 OFF ⇒ 只回滚这一条 INSERT,外层事务仍可用,
                // 所以咽下去当「没抢到牌」收场是安全的。⚠️ 哪天把该参数改成 ON,这个前提就没了。
                // 为什么切在 TransientDataAccessException 而不是 DataAccessException / Exception:
                // 编程错(NPE)、列截断(DataIntegrityViolationException)是 NonTransient,照旧穿透。
                // ⚠️ 表不存在 / SQL 语法错(BadSqlGrammarException)同样是 NonTransient、这支也接不住,
                //    它由下面那支【部署窗口兜底】单独接管(2026-09-02 补),别再照这句话推断它会穿透。
                // 夺牌是锦上添花,不该有「把玩家打卡完成一起回滚」的权力;但也不该把真 bug 藏起来。
                logger.warn("claimNodeMedal transient failure nodeId={} memberId={} rank={}: {}",
                        nodeId, memberId, rank, transientFailure.getMessage());
                return null;
            } catch (BadSqlGrammarException | UncategorizedDataAccessException notReady) {
                // 【部署窗口兜底】jar 先于 migration_20260902_node_medal_claim.sql 上线时,这里必然抛
                // BadSqlGrammarException(1146 Table 'node_medal_claim' doesn't exist)。它是 NonTransient,
                // 上面那支 catch 接不住 ⇒ 穿透到外层 rollbackFor = Exception.class ⇒ 每次节点完成整单回滚 500,
                // 整条游玩链断在「奖牌」这个锦上添花的功能上。读取侧 ApiCreativeSquareController
                // .enrichSportFeedBatch 已经为同一个窗口降级成 0 枚,写侧不能继续裸奔。
                //
                // 吞这两类:
                //   · BadSqlGrammarException —— 表/列/语法不成立(1146/1054/1064)。整条语句永远不会成功,
                //     所以直接 return 而不是接着试下一档(否则一次打卡刷三行同样的 warn)。
                //   · UncategorizedDataAccessException —— mapper 语句没被加载时 MyBatis 抛 BindingException
                //     (Invalid bound statement),mybatis-spring 翻成 MyBatisSystemException,正是它的子类。
                // 放行这三类(刻意不接):
                //   · DataIntegrityViolationException(列截断/约束冲突)= 数据本身不合法,是真 bug,
                //     必须整单回滚暴露出来(NodeMedalClaimTest#nonDuplicateFailure_propagates 钉着这条)。
                //   · DeadlockLoserDataAccessException(1213)= MySQL 已经回滚了整个事务,吞掉 = 静默丢打卡。
                //   · NPE / 参数错等非 DataAccessException —— 压根不在本 catch 的类型范围内,照旧穿透。
                // 这一刀切的是「功能还不存在」与「功能被用错了」:前者是部署顺序的必然状态,
                // 拿它拖垮打卡不可接受;后者是 bug,藏起来更贵。
                //
                // 到期条件:迁移在生产落库后,本支 catch 就只剩防御意义(回滚 / 建新环境忘跑迁移)。
                // 可以留着,但别指望它报警——它只打 warn。表在不在只有迁移对账能回答,不是这行日志。
                logger.warn("claimNodeMedal skipped, node_medal_claim not ready nodeId={} memberId={}: {}",
                        nodeId, memberId, notReady.getMessage());
                return null;
            }
        }
        return null;   // 头三名已满 = 第四位起无牌
    }

    public Map<String, Object> advanceProgress(Long memberId, CmsTopicNode node, Long actId)
    {
        return advanceProgress(memberId, node, actId, null);
    }

    /**
     * 推进单节点进度（幂等）+ 节点积分 + 通关判定。带 picUrl 时(拍照打卡)首次写入回填照片与审核位。
     */
    public Map<String, Object> advanceProgress(Long memberId, CmsTopicNode node, Long actId, String picUrl)
    {
        return advanceProgress(memberId, node, actId, picUrl, null, null);
    }

    /** 谜题完成字段只描述表现，不参与现有 XP/积分发奖。 */
    public Map<String, Object> advanceProgress(Long memberId, CmsTopicNode node, Long actId, String picUrl,
            Integer puzzleScore, String completionMode)
    {
        return advanceProgress(memberId, node, actId, picUrl, puzzleScore, completionMode, RewardPolicy.FULL);
    }

    public Map<String, Object> advanceProgress(Long memberId, CmsTopicNode node, Long actId, String picUrl,
            Integer puzzleScore, String completionMode, RewardPolicy rewardPolicy)
    {
        return advanceProgress(memberId, node, actId, picUrl, puzzleScore, completionMode, rewardPolicy,
                null, null, null, null);
    }

    public Map<String, Object> advanceProgress(Long memberId, CmsTopicNode node, Long actId, String picUrl,
            Integer puzzleScore, String completionMode, RewardPolicy rewardPolicy,
            String routeActionId, Integer expectedRouteVersion, String trustedOutcomeCode)
    {
        return advanceProgress(memberId, node, actId, picUrl, puzzleScore, completionMode, rewardPolicy,
                routeActionId, expectedRouteVersion, trustedOutcomeCode, null);
    }

    /**
     * @param approvedProgressId 拍照人工审核通过时传入待审进度行 id(null=普通完成)。
     *        非 null 时只有该行 CAS 0→1 命中才算 firstTime,发奖/通关从此唯一一次成立,
     *        重复通过/并发通过拿不到 rows⇒firstTime=false,不重发奖。
     */
    public Map<String, Object> advanceProgress(Long memberId, CmsTopicNode node, Long actId, String picUrl,
            Integer puzzleScore, String completionMode, RewardPolicy rewardPolicy,
            String routeActionId, Integer expectedRouteVersion, String trustedOutcomeCode,
            Long approvedProgressId)
    {
        return advanceProgress(memberId, node, actId, picUrl, puzzleScore, completionMode, rewardPolicy,
                routeActionId, expectedRouteVersion, trustedOutcomeCode, approvedProgressId, null);
    }

    public Map<String, Object> advanceProgress(Long memberId, CmsTopicNode node, Long actId, String picUrl,
            Integer puzzleScore, String completionMode, RewardPolicy rewardPolicy,
            String routeActionId, Integer expectedRouteVersion, String trustedOutcomeCode,
            Long approvedProgressId, PlayOriginalRun originalRun)
    {
        com.chengyinhub.business.domain.TopicRouteSession originalSession = originalRun == null ? null : originalRun.session();
        String progressInstance = originalRun == null ? "default" : originalRun.progressInstance();
        // 共享局的任务结算由其权威引擎处理，不能借普通节点完成创建或推进个人局。
        if (originalRun != null && originalRun.runtimeInstance() != null)
            throw new ServiceException("共享局任务请使用本队任务结算入口", 409);
        // 高级玩法是节点原验证方式的前置闸。只有这里通过后才进入既有幂等、发奖和通关链，
        // 避免六类玩法各自复制一份积分/券/勋章逻辑。
        // Spring 运行态该依赖是 required；null 仅兼容直接 new Controller 的存量纯单测。
        if(topicRouteRuntimeService!=null && topicRouteRuntimeService.genericPlayer(memberId,node.getTopicId(),actId))throw new ServiceException("通用个人任务请使用任务结算，不能领取旧节点奖励",409);
        String advancedOutcomeCode = advancedGameRuntimeService == null ? null
                : advancedGameRuntimeService.completeGate(memberId, actId, node.getTopicId(), node.getId());
        // C1 传感器可信边界(选项 A):设备自报成绩(反应毫秒/撞击数/heldMs/停表/方位)、拍照兜底放行、
        // 静态节点码匹配都证明不了「真做到了」⇒ 本节点只发探索值与徽章,不发积分/券/掉落。
        // 碎片文不在这一刀里(仍按调用方原口径);主题通关奖在下面通关处按全部已完成节点再判一次(#478-3)。
        RewardPolicy topicRewardPolicy = rewardPolicy;
        boolean rewardCapped = rewardPolicy == RewardPolicy.FULL && advancedGameRuntimeService != null
                && advancedGameRuntimeService.rewardCappedToXpBadge(memberId, actId, node.getTopicId(), node.getId());
        if (rewardCapped) rewardPolicy = RewardPolicy.XP_BADGE_ONLY;
        Map<String, Object> conditionalReward = null;
        // ③ 与 ①② 在本方法里有两处分歧(首完判据 + 发奖范围),求一次共用,免得两处各判各的再漂开。
        boolean shopDay = playNodeContext.resolveMode(actId, node.getTopicId()) == PlayMode.FREE_COLLECT;
        // 幂等：原子 insert + DuplicateKeyException 精确捕获并发冲突；
        // 捕获后按 uk_progress 四列回查确认确为重复，非重复异常必须 rethrow。
        PlayerNodeProgress p = new PlayerNodeProgress();
        p.setMemberId(memberId);
        p.setPlayInstanceId(progressInstance);
        p.setActivityId(actId);
        p.setTopicId(node.getTopicId());
        p.setNodeId(node.getId());
        p.setStatus(1);
        p.setReviewStatus(1);
        p.setCheckinTime(new Date());
        p.setPuzzleScore(puzzleScore);
        p.setCompletionMode(completionMode);
        if (StringUtils.isNotBlank(picUrl)) {
            // 本入口沿既有机审提交即完成合同；定向替代任务待审仍由其独立审核事实阻断结算。
            p.setPicUrl(picUrl);
        }
        p.setDelFlag(0);
        p.setCreateTime(new Date());
        int rows;
        Integer persistedPuzzleScore = puzzleScore;
        String persistedCompletionMode = completionMode;
        try {
            rows = playerNodeProgressService.insertPlayerNodeProgress(p);
        } catch (DuplicateKeyException e) {
            PlayerNodeProgress probe = new PlayerNodeProgress();
            probe.setMemberId(memberId);
            probe.setPlayInstanceId(progressInstance);
            probe.setActivityId(actId);
            probe.setTopicId(node.getTopicId());
            probe.setNodeId(node.getId());
            List<PlayerNodeProgress> found = playerNodeProgressService.selectPlayerNodeProgressList(probe);
            if (found == null || found.isEmpty()) {
                throw e; // 不是 uk_progress 冲突，不伪装成已完成
            }
            PlayerNodeProgress existing = found.get(0);
            p.setId(existing.getId());
            if (existing.getPuzzleScore() != null) persistedPuzzleScore = existing.getPuzzleScore();
            if (StringUtils.isNotBlank(existing.getCompletionMode())) {
                persistedCompletionMode = existing.getCompletionMode();
            }
            rows = 0;
            // ★拍照一律机审(2026-09-17 用户拍板):存量待审行(status=0,人工审核时代落下的)
            //   在这里 0→1 条件更新转正 —— CAS 命中 = 完成事实自此刻成立,继续走下面的发奖/通关链;
            //   重复/并发提交恒 0 行,落回 firstTime=false 的幂等路径。
            if (approvedProgressId != null && approvedProgressId.equals(existing.getId())
                    && playerNodeProgressService.markPhotoMachineApproved(existing.getId()) > 0) {
                rows = 1;
            }
            // B5 收窄:mode2 的行由扫码进店先建(status=0)⇒ 答题推进在这里必撞重复键。
            // 游戏完成 = 条件更新 0→1,更新到即本次首次完成;已是 1 = 真重复。
            // mode1 的行恒 status=1,条件更新恒 0 行,不受影响。
            if (rows == 0 && shopDay && shopDayProofService.markGameDone(memberId, actId, node.getTopicId(), node.getId())) {
                rows = 1;
            }
        }
        boolean firstTime = (rows > 0);
        // 原局锁由命令入口持有；进度赢家先入账，再进入既有 member 奖励锁。
        if (firstTime && originalSession != null && orientScoringService != null
                && orientScoringService.isActiveFor(cmsTopicService.selectCmsTopicById(node.getTopicId()))) {
            if (p.getId() == null) throw new IllegalStateException("完成事实缺少持久身份");
            orientScoringService.syncSession(memberId, originalSession.getId(), "progress:" + p.getId());
        }

        TopicRouteState routeState = null;
        com.chengyinhub.business.domain.CmsTopic journeyTopic = cmsTopicService.selectCmsTopicById(node.getTopicId());
        boolean journeyEnabled = journeyTopic != null
                && com.chengyinhub.business.domain.JourneyRules.parse(journeyTopic.getJourneyRulesJson()).hasRuntimeState();
        if ((originalRun == null || originalRun.hasRuntime()) && (!shopDay || journeyEnabled) && topicRouteCompletionCoordinator != null) {
            String serverReceiptId = p.getId() == null ? null : "player-node-progress:" + p.getId();
            routeState = topicRouteCompletionCoordinator.advanceOriginal(memberId, node.getTopicId(), actId, node.getId(),
                    originalSession == null ? null : originalSession.getId(), routeActionId,
                    expectedRouteVersion, serverReceiptId, advancedOutcomeCode, trustedOutcomeCode);
        } else if (originalRun == null && (!shopDay || journeyEnabled) && topicRouteRuntimeService != null) {
            routeState = topicRouteRuntimeService.state(memberId, node.getTopicId(), actId);
        }

        List<PlayerBadge> newBadges = new ArrayList<>();
        Map<String, Object> dropResult = null;
        int earnedXp = 0;
        // 名次奖牌本次夺得的名次(1/2/3);没抢到(三档满 / 表未就绪 / 非首次)恒 null。
        // 前端原来只看节点配了 medalName 就弹「专属徽章」,第 4 名起弹的是假奖 —— 必须回传事实。
        Integer medalRank = null;
        if (firstTime && (p.getPlayInstanceId()==null || "default".equals(p.getPlayInstanceId()))) {
            // 重玩生成本局进度与主题内容，但不再次派发平台积分、券、成长值或排名奖牌。
            // ★ ③ 只发探索值与图鉴收集。B5(2026-08-15 修订)原文授权的是「玩完发**探索值**,纯体验」——
            //   抵扣积分 / 模板券 / 承接商家券 / 随机掉落券 / 经典节点徽章都不在授权里,不得靠
            //   「复用 advanceProgress」顺带继承过来。抵扣积分尤其不行:裁决表 D2 关掉 ③ 票的积分抵扣,
            //   理由是「不关则 G_cash = 69 × N_cash 在代码上是假的」;买票侧关了、玩法侧发,
            //   这些积分照样能花在 ①② 上,同一个经济口径被从另一头捅开。
            //   ①② 的形态一字不改 —— 由 classicOrienteeringStillGrantsTheFullNodeRewardSet 守着。
            if (!shopDay && rewardPolicy == RewardPolicy.FULL) {
                pointsAwardService.award(memberId, 12L, node.getId(), "节点打卡");
                // 任务触发发券：节点绑定模板配了 couponId 则给玩家发一张(仅首次完成，天然防重发)
                // C5 防薅券:拍照完成(picUrl!=null)凭任意图不足以证真,不自动发真金券;真金券走扫码核销(C2)/后台审核。非拍照完成(GPS到达/答题/扫码)照常发。
                if (StringUtils.isBlank(picUrl)) {
                    grantTemplateCoupon(memberId, node, actId);
                }
            }
            // 条件奖励:商家配的「达到某个结果才发这份」。守与上面无条件奖励**同一套闸**——
            // ③ 自由探索不发券(B5 只授权探索值)、拍照不发券(C5 防薅)、非 FULL 不发券。
            // 探索值不受这两条限制,它本来就是 ③ 唯一被授权的奖励。
            conditionalReward = grantConditionalReward(memberId, node,
                    advancedOutcomeCode != null ? advancedOutcomeCode : trustedOutcomeCode,
                    shopDay, rewardPolicy, picUrl);
            // FE-01:完成节点若有碎片文,持久化到 member 级收集(幂等,供成就大屏聚合)
            if (topicRewardPolicy == RewardPolicy.FULL && StringUtils.isNotBlank(node.getFragmentText())) {
                collect(memberId, com.chengyinhub.business.domain.MemberCollectible.TYPE_FRAGMENT,
                        node.getTopicId(), node.getId(), node.getFragmentText());
            }
            // P2 成长:节点完成累计+连续打卡+首枚/连续徽章
            if (!shopDay || rewardPolicy == RewardPolicy.XP_BADGE_ONLY) {
                newBadges.addAll(playerProgressionService.onNodeDone(memberId));
            }
            // M7 节点探索值入账(双轨:只进 member_growth,不碰抵扣积分;幂等=progress uk 首次)
            // 发奖与进度、路线推进共用外层 @Transactional 边界；失败必须向外抛出，
            // 让本次节点完成整体回滚后可安全重试，不能留下“路线已走、奖励待人工”的裂缝。
            earnedXp = xpBudgetService.resolveNodeXp(node);
            if (earnedXp <= 0) earnedXp = 12;
            growthService.addExp(memberId, earnedXp, "节点探索");
            // M6 随机掉落:首次完成掷骰(内部 uk 幂等,重放/并发不多掉);命中即入账(granting)
            // ③ 整块跳过:dropType=3 会再发一张真金券,dropType=1 的翻倍探索值也不在 B5 的「纯体验」里。
            if (shopDay || rewardPolicy == RewardPolicy.XP_BADGE_ONLY) dropResult = null;
            else dropResult = rewardDropService.rollDrop(memberId, node.getTopicId(), node.getId(), actId, earnedXp > 0 ? earnedXp : 12L);
            if (dropResult != null) {
                Integer dt = (Integer) dropResult.get("dropType");
                if (dt != null && dt == 1 && dropResult.get("extraXp") instanceof Number) {
                    int extra = ((Number) dropResult.get("extraXp")).intValue();
                    if (extra > 0) { growthService.addExp(memberId, extra, "掉落·探索值翻倍"); earnedXp += extra; }
                } else if (dt != null && dt == 3 && dropResult.get("couponTemplateId") instanceof Number) {
                    long cid = ((Number) dropResult.get("couponTemplateId")).longValue();
                    if (cid > 0) grantCouponById(memberId, cid);   // 彩蛋券真落袋(SmsCouponHistory)
                }
            }
            // 名次奖牌:放发奖链最后一行。抢不到(档位被占 / 三档满 / 短暂写失败 / 表尚未建)都是正常结果,不影响打卡;
            // 行锁到底持有多久、什么时候才需要改,见 claimNodeMedal 的方法注释(已量化 + 写了触发阈值)。
            medalRank = claimNodeMedal(node.getId(), memberId, actId, node.getTopicId(), p.getId());
        }

        // 进度统计 + 通关判定
        CmsTopicNode allProbe = new CmsTopicNode();
        allProbe.setTopicId(node.getTopicId());
        allProbe.setStatus(1L);
        int total = cmsTopicNodeService.selectCmsTopicNodeList(allProbe).size();

        PlayerNodeProgress doneProbe = new PlayerNodeProgress();
        doneProbe.setMemberId(memberId);
        doneProbe.setActivityId(actId);
        doneProbe.setPlayInstanceId(progressInstance);
        // 自玩会话 activity_id 全部=0,多主题共享同一探针会互相污染 done 计数,须按 topicId 再收窄
        // (与上面 total 的 topic 口径保持一致);活动会话 activityId 天然一对一绑定主题,无需再加。
        // ⚠️ 这与 nodes() 里 :400 的收窄是同一件事 —— 那处已修并写了注释,这处(通关判定)漏了:
        // done 被别的自玩主题的进度撑大 ⇒ 提前判 completed ⇒ 提前发通关积分/XP/通关勋章。
        if (actId == null || actId == 0L) {
            doneProbe.setTopicId(node.getTopicId());
        }
        int done = 0;
        List<Long> doneNodeIds = new ArrayList<>();
        for (PlayerNodeProgress doneRow : playerNodeProgressService.selectPlayerNodeProgressList(doneProbe)) {
            // F1:完成事实只认 status==1。mode2 自由收集的到店/凭证行 status=0(由 markGameDone 0→1),
            // 按行数全算会让「只到店没玩」的节点凑满 total ⇒ 提前判通关发奖。
            // 口径与 /nodes completedNodeIds、TopicRouteRuntimeServiceImpl.completed() 一致。
            if (doneRow.getStatus() != null && doneRow.getStatus() == 1) {
                done++;
                doneNodeIds.add(doneRow.getNodeId());
            }
        }

        // [P0-17] 通关口径:③ 认**核销**不认打卡。
        // ⚠️ 五个判定点必须同口径(本处 + /my-completed + 广场 enrichSportFeed + 广场 summary
        //    + ApiStatsController.club)—— 只切一处就会出现「打卡端说没通关、广场说通关了」的分叉。
        boolean completed = routeState != null && TopicRouteState.BRANCH_GRAPH.equals(routeState.getRouteMode())
                ? TopicRouteState.COMPLETED.equals(routeState.getStatus())
                : playCompletionFacts.isTopicCompleted(memberId, actId, node.getTopicId(), total, done);
        if (routeState != null && routeState.getOpening() != null
                && !Boolean.TRUE.equals(routeState.getOpening().get("complete"))) completed = false;
        boolean topicRewardCapped = false;
        if (completed) {
            // #478-3:任一已完成节点靠设备自报/拍照兜底/静态码完成 ⇒ 通关奖同样只发探索值、徽章与通关勋章,
            // 不发通关积分与通关券。③ 自由收集的通关认核销(商家扫码,服务端可证),不在此列,
            // 与 CmsRegistrationServiceImpl 核销侧发的通关奖同口径,免得谁最后到谁决定发多少。
            topicRewardCapped = topicRewardPolicy == RewardPolicy.FULL && !shopDay
                    && (rewardCapped || (advancedGameRuntimeService != null
                        && advancedGameRuntimeService.anyNodeRewardCappedToXpBadge(memberId, actId,
                                node.getTopicId(), doneNodeIds)));
            RewardPolicy completionPolicy = topicRewardCapped ? RewardPolicy.XP_BADGE_ONLY : topicRewardPolicy;
            com.chengyinhub.business.domain.vo.TopicCompletionAwardVO award = completionPolicy == RewardPolicy.FULL
                    ? topicCompletionAwardService.grantOnce(memberId, node.getTopicId(), actId, null)
                    : topicCompletionAwardService.grantOnce(memberId, node.getTopicId(), actId, null, completionPolicy);
            newBadges.addAll(award.getBadges());
            earnedXp += award.getEarnedXp();
        }

        List<Map<String, Object>> badgeView = new ArrayList<>();
        for (PlayerBadge b : newBadges) {
            Map<String, Object> bm = new LinkedHashMap<>();
            bm.put("code", b.getBadgeCode());
            bm.put("name", b.getBadgeName());
            bm.put("iconUrl", b.getIconUrl());
            badgeView.add(bm);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nodeId", node.getId());
        data.put("firstTime", firstTime);
        // 界面据此说清「这一站只记探索值与徽章」,不自行补发或换旧路径领奖
        if (rewardCapped) data.put("rewardCeiling", RewardPolicy.XP_BADGE_ONLY.name());
        if (topicRewardCapped) data.put("topicRewardCeiling", RewardPolicy.XP_BADGE_ONLY.name());
        data.put("done", done);
        data.put("total", total);
        data.put("completed", completed);
        if (routeState != null) data.put("routeState", routeState);
        data.put("newBadges", badgeView); // 本次新得徽章(前端弹"+徽章")
        refreshBingo(memberId, node.getTopicId(), data);
        data.put("xp", earnedXp);         // M7 本次入账探索值(前端飞字"+N 探索值")
        data.put("medalRank", medalRank); // 名次奖牌本次夺得的名次(null=没抢到,前端不得弹「专属徽章」)
        // 条件奖励:前端据此把「命中 · +50 探索值 · 得到一张券」这一屏渲染出来。
        // 没配或没命中就没有这个键,前端按不存在处理。
        if (conditionalReward != null) data.put("conditionalReward", conditionalReward);
        if (persistedPuzzleScore != null) data.put("puzzleScore", persistedPuzzleScore);
        if (StringUtils.isNotBlank(persistedCompletionMode)) data.put("completionMode", persistedCompletionMode);
        if (completed && !shopDay) {
            Integer personalBest = playerNodeProgressService.selectPuzzlePersonalBest(memberId, node.getTopicId());
            if (personalBest != null) data.put("puzzlePersonalBest", personalBest);
        }
        if (dropResult != null) {
            data.put("drop", dropResult); // M6 随机掉落(命中已入账:XP倍率→addExp / 彩蛋券→SmsCouponHistory)
        }
        return data;
    }

    /**
     * 刷新宾果九宫格。
     *
     * <p>⚠️ **整段包在 try-catch 里,失败只记 error**。宾果是主流程之外的一层:
     * 它出问题不能让节点完成失败,否则玩家会卡在这一站反复重试,而原因是一个
     * 跟他这次动作毫无关系的九宫格。少填一格是可修的(evaluate 返回的是「当前
     * 该是什么样」,下一次完成会把漏掉的补上),让整条游玩链路挂掉不可修。
     * 数据库瞬态失败例外：原事务可能已经中止，必须交外层回滚，不能继续完成/回执。
     */
    public void refreshBingo(Long memberId, Long topicId, Map<String, Object> data)
    {
        if (playBingoService == null) return;   // 纯单测直接 new Controller 时为空
        try {
            com.chengyinhub.business.domain.PlayBingoCard card =
                    playBingoService.onNodeDone(memberId, topicId);
            if (card != null) {
                Map<String, Object> bingo = new LinkedHashMap<>();
                bingo.put("filledMask", card.getFilledMask());
                bingo.put("rewardMask", card.getRewardMask());
                data.put("bingo", bingo);
            }
        } catch (TransientDataAccessException failure) {
            // A lock failure can abort the database transaction; let the outer owner roll back/retry.
            throw failure;
        } catch (Exception e) {
            logger.error("宾果刷新失败 member={} topic={},节点完成不受影响,下次完成会自愈",
                    memberId, topicId, e);
        }
    }

    /**
     * 条件奖励:按本次结果码发商家配的那一份。
     *
     * <p>{@code reward_rules_json} 是叠加在平铺奖励列之上的,平铺的仍表示「完成即发」。
     * 没有结果码、没配规则、或没匹配上,本方法什么也不做。
     *
     * @return 发出去的东西(给前端展示用);什么都没发返回 null
     */
    public Map<String, Object> grantConditionalReward(Long memberId, CmsTopicNode node,
            String outcomeCode, boolean shopDay, RewardPolicy rewardPolicy, String picUrl)
    {
        if (StringUtils.isBlank(outcomeCode) || node.getTemplateId() == null || node.getTemplateId() <= 0) {
            return null;
        }
        CmsMemberTemplate tpl = cmsMemberTemplateService.selectCmsMemberTemplateById(node.getTemplateId());
        if (tpl == null) return null;
        com.fasterxml.jackson.databind.JsonNode rule =
                rewardRulesValidator.ruleFor(tpl.getRewardRulesJson(), outcomeCode);
        if (rule == null) return null;

        Map<String, Object> granted = new LinkedHashMap<>();
        granted.put("outcomeCode", outcomeCode);

        int xp = rule.path("xp").asInt(0);
        if (xp > 0) {
            growthService.addExp(memberId, xp, "玩法结果奖励");
            granted.put("xp", xp);
        }
        String text = rule.path("feedbackText").asText("").trim();
        if (!text.isEmpty()) granted.put("feedbackText", text);

        long couponId = rule.path("couponId").asLong(0L);
        // 券的三道闸与无条件奖励逐条对齐,不另立一套口径
        boolean couponAllowed = couponId > 0
                && !shopDay
                && rewardPolicy == RewardPolicy.FULL
                && StringUtils.isBlank(picUrl);
        if (couponAllowed && grantCouponById(memberId, couponId)) {
            granted.put("couponId", couponId);
        }
        return granted.size() > 1 ? granted : null;
    }

    public void grantTemplateCoupon(Long memberId, CmsTopicNode node, Long actId)
    {
        // 到店券优先用承接商家自己的:玩家在他店里完成互动,成本也该由他承担。
        // 原来无论谁承接都发主办方模板上那张券 —— 商家出了场地和人力,却没有自己的权益出口。
        CmsMemberTemplate tpl = null;
        if (node.getTemplateId() != null && node.getTemplateId() > 0) {
            tpl = cmsMemberTemplateService.selectCmsMemberTemplateById(node.getTemplateId());
        }
        com.chengyinhub.business.domain.vo.NodeMerchantOverride override =
                nodeMerchantOverrideService.forNode(node.getId());
        Long merchantCouponId = override.couponIdOr(null);
        Long couponId = merchantCouponId != null ? merchantCouponId : (tpl == null ? null : tpl.getCouponId());
        if (couponId == null || couponId <= 0) return;
        // 第二轮拍板第 6 条:「商家出游戏码、玩家扫码答题、券发给玩家本人」的唯一发券路径就在这里
        // (商家侧 /api/registration/verify_answer 已停用)。核销审计:操作人=玩家,归属=出这张券的商家。
        Long ownerMemberId = merchantCouponId != null ? override.getMerchantMemberId()
                : (tpl == null ? null : tpl.getMemberId());
        if (ownerMemberId == null) ownerMemberId = node.getMerchantMemberId();
        if (ownerMemberId == null) {
            // 总控复查裁定:查不出出券商家 = 数据坏了。节点照常完成(不回滚玩家),这张券不发,留 error 供核查。
            logger.error("节点模板券缺归属商家,不发券 nodeId={} templateId={} memberId={} couponId={}",
                    node.getId(), node.getTemplateId(), memberId, couponId);
            return;
        }
        if (!grantCouponById(memberId, couponId)) return;
        // 有归属才发券 + 审计,与本请求同事务:审计写不进就整体回滚 —— 发了券却查不到发给谁、因为哪一站,等于没留痕。
        com.chengyinhub.business.domain.BusinessAuditEvent audit =
                new com.chengyinhub.business.domain.BusinessAuditEvent();
        audit.setDomainType("TOPIC_NODE");
        audit.setDomainId(node.getId());
        audit.setOwnerMemberId(ownerMemberId);
        audit.setActorType(com.chengyinhub.business.domain.BusinessAuditEvent.ACTOR_TYPE_MEMBER);
        audit.setActorMemberId(memberId);
        audit.setActorRoleCode("PLAYER");
        audit.setAction("NODE_COUPON_GRANT");
        audit.setTargetType("SMS_COUPON");
        audit.setTargetId(couponId);
        audit.setRelationId(actId);
        audit.setBeforeJson("{\"couponGranted\":false}");
        audit.setAfterJson("{\"couponGranted\":true}");
        audit.setRequestId("node-coupon-" + memberId + "-" + actId + "-" + node.getId());
        audit.setCreateTime(new Date());
        if (businessAuditEventMapper.insertBusinessAuditEvent(audit) != 1) {
            throw new IllegalStateException("节点发券审计写入失败");
        }
    }

    /**
     * 按券模板 ID 发一张到玩家券包(节点任务券 / M6 彩蛋券掉落共用)。
     * @return true=已发券;false=券不存在或库存耗尽(服务层返回 false),券没落袋
     */
    public boolean grantCouponById(Long memberId, Long couponId)
    {
        // BE-06 下沉:发券逻辑收口到 ISmsCouponHistoryService.grantCouponById(与扫码核销发券同一实现)
        // C-30:券已失效(3)/已停发(4) 不可发放 → 跳过发券,节点进度/掉落照常落袋,不再误导人工补发
        if (smsCouponHistoryService.isCouponNotIssuable(couponId)) {
            logger.warn("COUPON_DISABLED_SKIP caller=playProgress member={} coupon={}", memberId, couponId);
            return false;
        }
        boolean granted = smsCouponHistoryService.grantCouponById(memberId, couponId);
        if (!granted) {
            // C3:节点进度/掉落键已先落库且本方法非事务,发券失败不可静默(丢券零告警),
            // 记 error 供运营人工补发/核查券模板库存;仅券不存在/库存耗尽走到这里
            logger.error("节点发券失败(券不存在或库存耗尽) member={} coupon={},需人工补发/核查", memberId, couponId);
        }
        return granted;
    }
}
