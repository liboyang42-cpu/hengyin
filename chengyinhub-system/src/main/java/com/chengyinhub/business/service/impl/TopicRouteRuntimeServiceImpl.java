package com.chengyinhub.business.service.impl;

import com.chengyinhub.common.annotation.DeadlockRetry;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.business.service.support.TopicRouteSessionScope;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.PlayerActivityRole;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.route.NodeOutcome;
import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.PlayerActivityRoleMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.IPlayEncounterService;
import com.chengyinhub.business.service.ITopicCompletionAwardService;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.impl.TopicRoutePublicationCompiler.JourneyTemplateProbe;
import com.chengyinhub.business.domain.JourneyStory;
import com.chengyinhub.business.service.support.JourneyCheckActionId;
import com.chengyinhub.business.service.support.NodeAccessPolicy;
import com.chengyinhub.business.service.support.NodeJourneySnapshot;
import com.chengyinhub.business.service.support.TrustedNodeEffects;
import com.chengyinhub.business.service.support.RouteReachabilityProvider;
import com.chengyinhub.business.service.support.JourneyInitialState;
import com.chengyinhub.business.service.support.FrozenAttributeDefinitions;
import com.chengyinhub.business.service.support.JourneyRpg;
import com.chengyinhub.business.service.support.TopicCompletionPolicy;
import com.chengyinhub.business.service.support.TopicCompletionRulePolicy;
import com.chengyinhub.business.service.support.SensorChallengePolicy;
import com.chengyinhub.business.service.support.StoryVocabulary;
import com.chengyinhub.common.constant.RewardPolicy;
import com.chengyinhub.business.service.support.TopicRouteCandidatePolicy;
import com.chengyinhub.business.service.support.TopicRouteConfigValidator;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.chengyinhub.business.service.support.ThemeRuntimeConfig;
import com.chengyinhub.business.service.support.ThemeRuntimeEngine;
import com.chengyinhub.business.service.support.GameplaySurvivalEngine;
import com.chengyinhub.business.service.support.GameplayToolState;
import com.chengyinhub.business.service.support.GameplayCooperationEngine;
import com.chengyinhub.business.service.support.GameplayToolState.Reason;
import com.chengyinhub.business.service.support.GameplayActionProjection;
import com.chengyinhub.business.service.support.GameplaySupportMatrix;
import com.chengyinhub.business.service.support.JourneyStatLinks;
import com.chengyinhub.business.service.support.TopicRouteModePolicy;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** 主题地点路线深模块：快照、权限、幂等、CAS 与状态投影集中在此。 */
@Service
public class TopicRouteRuntimeServiceImpl implements ITopicRouteRuntimeService {
    private static final String DEFAULT_INSTANCE = "default";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final com.chengyinhub.business.service.support.GameplayTaskEngine gameplayTasks = new com.chengyinhub.business.service.support.GameplayTaskEngine();
    @Autowired private com.chengyinhub.business.service.support.VerifiedWeRunService verifiedWeRunService;
    private final com.chengyinhub.business.service.support.GameplaySurvivalEngine gameplaySurvival = new com.chengyinhub.business.service.support.GameplaySurvivalEngine();
    private final com.chengyinhub.business.service.support.GameplayCooperationEngine gameplayCooperation = new com.chengyinhub.business.service.support.GameplayCooperationEngine();
    /** 检定运行时系统键:不进键注册表,只由服务端写本局 state(共享契约 v2/v3)。 */
    private static final String SYS_HP = "sys.hp";
    private static final String SYS_LUCK = "sys.luck";
    /** 今日步数的合理上限:超过这个数的只可能是伪造或解密错了。 */
    private static final int MAX_TODAY_STEPS = 200000;
    private static final String SYS_EXHAUSTED = "sys.exhausted";
    /** 问问题:R9-ACTIONS 保留变量 sys.asked.{nodeId}.{questionId}。 */
    private static final String SYS_ASKED_PREFIX = "sys.asked.";
    /** 开场通话:选了第几项(1 起)。 */
    private static final String SYS_CALL = "sys.call";
    /** 判错计数 sys.mistakes.{nodeId};首错免罚,之后每次扣 1 血。 */
    private static final String SYS_MISTAKES_PREFIX = "sys.mistakes.";
    /**
     * 本节点实际扣血累计 sys.damage.{nodeId}:站末恢复只认这个数,
     * 判定用,不面向玩家;恢复用掉后清除,避免合法 loop 二次访问误判。
     */
    private static final String SYS_DAMAGE_PREFIX = "sys.damage.";
    /** R14 分支结果:sys.outcome.{nodeId} = 选项序号(1 起),供结局 disposition 引用。 */
    private static final String SYS_OUTCOME_PREFIX = "sys.outcome.";
    /** 阶段 1(链路 D)判定型成败:sys.passed.{nodeId} = 1 通过 / 0 失败,只读不可由模板写。 */
    private static final String SYS_PASSED_PREFIX = "sys.passed.";
    private static final String STEP_ROLL = "roll";
    /** 节点叙事配置的 id 形状(契约 v4:enter.questions / call.options 的 id)。 */
    private static final Pattern NARRATIVE_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final String STEP_REROLL = "reroll";
    /** F6 T09 魔鬼交易:掷骰前接受,加值进本次检定、代价当场写进本局(步骤名 ≤6 字,保持动作 id 上限 97)。 */
    private static final String STEP_BARGAIN = "deal";
    /** F6 T10 动量:掷骰后、结算前花动量补足差距。 */
    private static final String STEP_MOMENTUM = "push";

    private final TopicRouteSessionMapper routeMapper;
    private final CmsTopicMapper topicMapper;
    private final CmsTopicNodeMapper nodeMapper;
    private final CmsMemberTemplateMapper templateMapper;
    private final PlayerNodeProgressMapper progressMapper;
    private final CmsActivityMapper activityMapper;
    private final CmsRegistrationMapper registrationMapper;
    private final PlayerActivityRoleMapper playerRoleMapper;
    private final IShopDayProofService shopDayProofService;
    /** 主题状态键注册表:结算失败回执取代价标签展示名;手工 new 的单测不注入 = 回落空。 */
    private ThemeStateKeyMapper themeStateKeyMapper;
    private final LongSupplier seedSupplier;
    /** 检定骰点(1–20):生产用 SecureRandom,测试构造注入固定序列。 */
    private final IntSupplier d20;
    private final Clock clock;
    private final RouteReachabilityProvider reachabilityProvider;
    private final TopicRouteConfigValidator validator = new TopicRouteConfigValidator();
    private final TopicRouteEngine engine = new TopicRouteEngine();
    private final com.chengyinhub.business.service.support.ThemeRuntimeEngine themeEngine = new com.chengyinhub.business.service.support.ThemeRuntimeEngine();
    private final NodeAccessPolicy accessPolicy = new NodeAccessPolicy();
    private final TopicCompletionPolicy completionPolicy = new TopicCompletionPolicy();
    private final TopicCompletionRulePolicy completionRulePolicy = new TopicCompletionRulePolicy();
    private final TopicRouteNarrativeProgress narrativeProgress;

    /**
     * M1 回执自带新世界:写接口回包追加这一站刷新后的 Encounter。
     * 可选注入:直接 new 的单测/缺装配时不追加,既有行为不变;组装一律复用 {@link IPlayEncounterService},
     * 不在本类复制任何 Encounter 逻辑。
     */
    @Autowired(required = false)
    private com.chengyinhub.business.service.IGameSessionRuntimeService gameSessionRuntimeService;

    @Autowired(required = false)
    private IPlayEncounterService playEncounterService;

    @Autowired(required = false)
    private ITopicCompletionAwardService topicCompletionAwardService;

    /**
     * #478-3 开局通关奖封顶的判据来源。高级玩法运行时反向依赖本类,故 @Lazy 打破循环;
     * 手工 new 的单测不注入 = 不按节点封顶(与老行为一致)。
     */
    @Autowired(required = false)
    @Lazy
    private com.chengyinhub.business.service.IAdvancedGameRuntimeService advancedGameRuntimeService;

    @Autowired
    @Lazy
    private com.chengyinhub.business.service.support.OrientRouteSettlement orientRouteSettlement;

    /** F2 T02 跨局角色属性;手工 new 的单测不注入 = 不结转(与老行为一致)。 */
    @Autowired(required = false)
    private com.chengyinhub.business.service.support.ThemeCharacterService themeCharacterService;

    /** 文档二 L8 可玩时段:只在新建自由探索局时执行;手工 new 的单测不注入 = 不限时段(与老行为一致)。 */
    @Autowired(required = false)
    private com.chengyinhub.business.service.support.TopicPlaySetupService topicPlaySetupService;

    /** F6 T06 环境键;缺省(单测 / 未装配)= 不写环境快照,ENV_IN 一律按 unknown 判。 */
    @Autowired(required = false)
    private com.chengyinhub.business.service.support.rpg.TopicEnvironmentService topicEnvironmentService;

    /**
     * R18:故事流章节读侧。节点叙事段已迁往章节 blocks,建局冻结从这里按站点取。
     * 手工 new 的单测不注入时按「本主题没有故事流」处理(生产由 Spring 必注入,
     * 注入不上会启动失败,不存在静默回退模板叙事的生产路径)。
     */
    @Autowired
    private CmsTopicChapterMapper cmsTopicChapterMapper;

    @Autowired
    public TopicRouteRuntimeServiceImpl(TopicRouteSessionMapper routeMapper, CmsTopicMapper topicMapper,
            CmsTopicNodeMapper nodeMapper, CmsMemberTemplateMapper templateMapper,
            PlayerNodeProgressMapper progressMapper,
            CmsActivityMapper activityMapper, CmsRegistrationMapper registrationMapper,
            PlayerActivityRoleMapper playerRoleMapper, IShopDayProofService shopDayProofService,
            ObjectProvider<RouteReachabilityProvider> reachabilityProviders,
            ThemeStateKeyMapper themeStateKeyMapper) {
        this(routeMapper, topicMapper, nodeMapper, templateMapper, progressMapper, activityMapper,
                registrationMapper, playerRoleMapper, shopDayProofService, new SecureRandom()::nextLong,
                secureD20(), Clock.system(ZoneId.of("Asia/Shanghai")),
                firstReachabilityProvider(reachabilityProviders));
        this.themeStateKeyMapper = themeStateKeyMapper;
    }

    private static IntSupplier secureD20() {
        SecureRandom random = new SecureRandom();
        return () -> 1 + random.nextInt(20);
    }

    public TopicRouteRuntimeServiceImpl(TopicRouteSessionMapper routeMapper, CmsTopicMapper topicMapper,
            CmsTopicNodeMapper nodeMapper, PlayerNodeProgressMapper progressMapper,
            CmsActivityMapper activityMapper, CmsRegistrationMapper registrationMapper) {
        this(routeMapper, topicMapper, nodeMapper, null, progressMapper, activityMapper, registrationMapper,
                null, new SecureRandom()::nextLong, Clock.system(ZoneId.of("Asia/Shanghai")),
                RouteReachabilityProvider.UNKNOWN);
    }

    public TopicRouteRuntimeServiceImpl(TopicRouteSessionMapper routeMapper, CmsTopicMapper topicMapper,
            CmsTopicNodeMapper nodeMapper, PlayerNodeProgressMapper progressMapper,
            CmsActivityMapper activityMapper, CmsRegistrationMapper registrationMapper, LongSupplier seedSupplier) {
        this(routeMapper, topicMapper, nodeMapper, null, progressMapper, activityMapper, registrationMapper,
                null, seedSupplier, Clock.system(ZoneId.of("Asia/Shanghai")), RouteReachabilityProvider.UNKNOWN);
    }

    public TopicRouteRuntimeServiceImpl(TopicRouteSessionMapper routeMapper, CmsTopicMapper topicMapper,
            CmsTopicNodeMapper nodeMapper, CmsMemberTemplateMapper templateMapper,
            PlayerNodeProgressMapper progressMapper,
            CmsActivityMapper activityMapper, CmsRegistrationMapper registrationMapper,
            PlayerActivityRoleMapper playerRoleMapper, LongSupplier seedSupplier, Clock clock,
            RouteReachabilityProvider reachabilityProvider) {
        this(routeMapper, topicMapper, nodeMapper, templateMapper, progressMapper, activityMapper,
                registrationMapper, playerRoleMapper, null, seedSupplier, secureD20(), clock,
                reachabilityProvider);
    }

    public TopicRouteRuntimeServiceImpl(TopicRouteSessionMapper routeMapper, CmsTopicMapper topicMapper,
            CmsTopicNodeMapper nodeMapper, CmsMemberTemplateMapper templateMapper,
            PlayerNodeProgressMapper progressMapper,
            CmsActivityMapper activityMapper, CmsRegistrationMapper registrationMapper,
            PlayerActivityRoleMapper playerRoleMapper, IShopDayProofService shopDayProofService,
            LongSupplier seedSupplier, IntSupplier d20, Clock clock,
            RouteReachabilityProvider reachabilityProvider) {
        this.routeMapper = routeMapper; this.topicMapper = topicMapper; this.nodeMapper = nodeMapper;
        this.templateMapper = templateMapper;
        this.progressMapper = progressMapper; this.activityMapper = activityMapper;
        this.registrationMapper = registrationMapper; this.playerRoleMapper = playerRoleMapper;
        this.shopDayProofService = shopDayProofService;
        this.seedSupplier = seedSupplier;
        this.d20 = d20 == null ? secureD20() : d20;
        this.clock = clock == null ? Clock.system(ZoneId.of("Asia/Shanghai")) : clock;
        this.reachabilityProvider = reachabilityProvider;
        this.narrativeProgress = new TopicRouteNarrativeProgress(topicMapper, JSON, engine, completionRulePolicy, themeEngine);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public TopicRouteState state(Long memberId, Long topicId, Long activityId) {
        if (gameSessionRuntimeService != null) {
            TopicRouteState shared = gameSessionRuntimeService.sharedRouteState(memberId, topicId, activityId);
            if (shared != null) return shared;
        }
        Scope scope = requireScope(memberId, topicId, activityId);
        if (!TopicRouteState.BRANCH_GRAPH.equals(routeMode(scope))) {
            // 老线性主题照旧无局;配了旅程规则/块状态的线性主题建局,与分支共用 state_json。
            List<CmsTopicNode> liveNodes = nodes(topicId);
            JourneyTemplateProbe probe = publicationCompiler().probe(liveNodes);
            if (!journeyConfigured(scope, probe)) return linearState(scope, liveNodes);
            return toLinearView(ensureJourneySession(scope, probe), scope, liveNodes);
        }
        TopicRouteSession session = ensureBranchSession(scope);
        return toAvailableView(session, nodes(topicId));
    }

    /** Read the original current run; entry commands retain all initialization and role assignment. */
    @Override
    @Transactional(readOnly = true)
    public TopicRouteState queryState(Long memberId, Long topicId, Long activityId) {
        if (gameSessionRuntimeService != null) {
            TopicRouteState shared = gameSessionRuntimeService.querySharedRouteState(memberId, topicId, activityId);
            if (shared != null) return shared;
        }
        Scope scope = currentScope(memberId, topicId, activityId, false);
        TopicRouteSession session = currentSession(memberId, topicId, scope.activityId);
        if (session == null) return null;
        TopicRouteState view = toView(session);
        view.setRouteMode(sessionRouteMode(session));
        return view;
    }

    @Override
    @DeadlockRetry
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public TopicRouteState completeOpening(Long memberId, Long topicId, Long activityId, Long chapterId,
                                          Integer expectedVersion) {
        Scope scope = requireScope(memberId, topicId, activityId,true);
        TopicRouteSession session = currentSession(memberId, topicId, scope.activityId);
        boolean scoreActive = orientRouteSettlement != null && orientRouteSettlement.active(scope.topic);
        if (scoreActive) session = orientRouteSettlement.lock(session);
        JsonNode opening = openingSnapshot(session);
        if (opening == null || chapterId == null || chapterId != opening.path("chapterId").asLong()) {
            throw new IllegalArgumentException("开局流程不匹配");
        }
        TopicRouteEngine.State state = readEngineState(session.getStateJson());
        if (!state.getTags().contains("sys.opening.done")) {
            if (expectedVersion == null || !expectedVersion.equals(session.getVersion())) {
                throw new ServiceException("路线状态已更新，请刷新后重试", HttpStatus.CONFLICT);
            }
            if (nextOpeningNode(opening, state) != null) throw new IllegalStateException("请先完成开局玩法");
            state.getTags().add("sys.opening.done");
            session.setStateJson(writeSettled(session,state));
            session.setStatus(statusOf(state));
            session.setCompletedAt(session.getStatus() == TopicRouteSession.STATUS_COMPLETED ? new Date() : null);
            session.setUpdateTime(new Date());
            if (scoreActive) orientRouteSettlement.beforePersist(session, state);
            if (routeMapper.updateStateCas(session) != 1) {
                throw new ServiceException("路线状态已更新，请刷新后重试", HttpStatus.CONFLICT);
            }
            session.setVersion(session.getVersion() + 1);
            if (scoreActive) orientRouteSettlement.complete(session, state);
        }
        TopicRouteState view = TopicRouteState.BRANCH_GRAPH.equals(routeMode(scope))
                ? toAvailableView(session, nodes(topicId)) : toLinearView(session, scope, nodes(topicId));
        grantOpeningAward(memberId, topicId, scope.activityId, view, state.getCompletedNodeIds());
        return view;
    }

    /**
     * 开局完成时整部主题也通关了就发奖。发奖住在这里而不是 controller:一是任何
     * completeOpening 的调用方都该拿到奖励,二是奖励异常要穿出本方法的事务一起回滚
     * (controller 不能自己开事务再捕获业务异常,否则提交期会退化成 UnexpectedRollbackException)。
     */
    private void grantOpeningAward(Long memberId, Long topicId, Long activityId, TopicRouteState view,
                                   java.util.Collection<Long> completedNodeIds) {
        Map<String, Object> opening = view.getOpening();
        if (topicCompletionAwardService == null || opening == null
                || !Boolean.TRUE.equals(opening.get("themeComplete"))) {
            return;
        }
        // #478-3:与节点完成那条通关链同口径 —— 任一已完成节点靠自报/兜底/静态码完成 ⇒ 通关奖只发探索值与徽章。
        boolean nodeCapped = advancedGameRuntimeService != null
                && advancedGameRuntimeService.anyNodeRewardCappedToXpBadge(memberId, activityId, topicId, completedNodeIds);
        if (nodeCapped || RewardPolicy.XP_BADGE_ONLY.name().equals(opening.get("rewardPolicy"))) {
            topicCompletionAwardService.grantOnce(memberId, topicId, activityId, null, RewardPolicy.XP_BADGE_ONLY);
        } else {
            topicCompletionAwardService.grantOnce(memberId, topicId, activityId, null);
        }
    }

    private JsonNode openingSnapshot(TopicRouteSession session) {
        return narrativeProgress.openingSnapshot(session);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void requireOpeningAccess(Long memberId, Long topicId, Long activityId, Long nodeId) {
        if (sharedSnapshot(memberId, topicId, activityId, nodeId) != null) return;
        TopicRouteSession session = currentSession(memberId,topicId,activityId);
        if (session == null) {
            CmsTopic topic = topicMapper.selectCmsTopicById(topicId);
            if (topic == null) return;
            boolean generic=genericConfig(ThemeRuntimeConfig.parse(topic.getPublishedThemeRuntimeConfigJson())) || (topic.getPublishedRouteGraphJson()!=null && genericConfig(validator.readTree(topic.getPublishedRouteGraphJson()).path("themeConfig")));
            if (!generic && JourneyStory.openingChapterId(topic.getJourneyStoryJson()) == null) return;
            state(memberId, topicId, activityId);
            session = currentSession(memberId,topicId,activityId);
            // 查不到本局 != 这个主题没有开局。建完还读不到就拒,别把读失败当成放行理由。
            if (session == null) throw new IllegalStateException("请先完成开局流程");
        }
        if (genericPlayer(session)) {
            TopicRouteEngine.State live=readEngineState(session.getStateJson());
            if(zeroChallenge(memberId,topicId,activityId,nodeId)!=null)return;
            String active=live.getThemeRuntime().path("gameplay").path("activeQuest").asText();
            JsonNode operation=live.getThemeRuntime().path("gameplay").path("activeOperation");
            boolean operationNode=operation.isObject() && operation.path("nodeId").asLong()==nodeId;
            if (!"ACTIVE".equals(live.getStatus()) || (!operationNode && (active.isEmpty() || gameplayTasks.quest(themeConfig(session),active).path("nodeId").asLong()!=nodeId))) throw new ServiceException("请先开始当前允许的任务或设施操作",409);
            return;
        }
        if (!openingAllows(session, nodeId)) throw new IllegalStateException("请先完成开局流程");
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public TopicRouteState syncThoughts(Long memberId, Long topicId, Long activityId,
                                        List<String> claim, int todaySteps) {
        return syncThoughts(memberId, topicId, activityId, claim, todaySteps,
                java.time.LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))).toEpochDay());
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public TopicRouteState syncThoughts(Long memberId, Long topicId, Long activityId,
                                        List<String> claim, int todaySteps, long readingDay) {
        Scope scope = requireScope(memberId, topicId, activityId,true);
        TopicRouteSession session = currentSession(memberId,topicId,scope.activityId);
        if (session == null) throw new IllegalStateException("本局还没开始");
        if (!Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus()))
            throw new IllegalStateException("本局已结束，不能再推进念头");
        JourneyRules rules = sessionRules(session);
        if (rules.getThoughts().size() == 0 && !hasStepEggs(themeConfig(session))) return routeView(session, scope);
        // todaySteps < 0 = 这次没有微信运动读数(没授权 / 只来认领):只处理认领与条件,不碰步数基线。
        if (todaySteps > MAX_TODAY_STEPS) {
            throw new IllegalArgumentException("步数不合理");
        }
        TopicRouteEngine.State state = readEngineState(session.getStateJson());

        narrativeProgress.progressThoughts(session, state, rules, claim, todaySteps, readingDay);

        session.setStateJson(writeSettled(session, state));
        session.setUpdateTime(new Date());
        if (routeMapper.updateStateCas(session) != 1) {
            throw new ServiceException("路线状态已更新，请刷新后重试", HttpStatus.CONFLICT);
        }
        session.setVersion(session.getVersion() + 1);
        return routeView(session, scope);
    }

    /**
     * 伏笔的条件结算(2026-09-22 用户裁决「伏笔不能只是微信步数,回答出特定选项和条件时也有」):
     * when 满足即得到、doneWhen 满足即想通(条件间任一满足)。没得到的不会被想通 —— 伏笔没埋就应验
     * 是剧情错乱。幂等:已得到的不重置进度,已想通的不重复加成。
     */
    private void settleThoughts(TopicRouteEngine.State state, JourneyRules rules) {
        narrativeProgress.settleThoughts(state, rules);
    }

    /** 本局状态写回前先结算伏笔:选项标签、判定成败、到站都可能刚让某个伏笔满足条件。 */
    private String writeSettled(TopicRouteSession session, TopicRouteEngine.State state) {
        JourneyRules rules = sessionRules(session);
        if (rules.getThoughts().size() > 0) settleThoughts(state, rules);
        syncTheme(session, state);
        return write(state);
    }

    /** 线性 / 分支各自的玩家视图 —— 本类里多处要这同一句选择。 */
    private TopicRouteState routeView(TopicRouteSession session, Scope scope) {
        return TopicRouteState.BRANCH_GRAPH.equals(routeMode(scope))
                ? toAvailableView(session, nodes(scope.topic.getId()))
                : toLinearView(session, scope, nodes(scope.topic.getId()));
    }

    private Long nextOpeningNode(JsonNode opening, TopicRouteEngine.State state) {
        return narrativeProgress.nextOpeningNode(opening, state);
    }

    private boolean openingAllows(TopicRouteSession session, Long nodeId) {
        return narrativeProgress.openingAllows(session, nodeId);
    }

    private int statusOf(TopicRouteEngine.State state) { return "ENDED".equals(state.getStatus()) ? TopicRouteSession.STATUS_ENDED : "COMPLETED".equals(state.getStatus()) ? TopicRouteSession.STATUS_COMPLETED : TopicRouteSession.STATUS_RUNNING; }

    private void keepOpeningRunning(TopicRouteSession session, TopicRouteEngine.State state) {
        narrativeProgress.keepOpeningRunning(session, state);
    }

    /**
     * 思维阁:把玩家**已经得到**的念头连同进度挂到视图上。
     *
     * <p>没得到的念头一个都不下发 —— 玩家还没在故事里读到那个 thought 块,提前看见名字就是剧透
     * (与未完成节点之后的正文一样的口径)。</p>
     */
    private void appendThoughts(TopicRouteState view, TopicRouteSession session, TopicRouteEngine.State state) {
        narrativeProgress.appendThoughts(view, session, state);
    }

    private void appendOpening(TopicRouteState view, TopicRouteSession session, TopicRouteEngine.State state) {
        narrativeProgress.appendOpening(view, session, state);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public TopicRouteState advance(Long memberId, Long topicId, Long activityId, Integer expectedVersion,
                                   NodeOutcome outcome) {
        return advance(memberId, topicId, activityId, expectedVersion, outcome.getReceiptId(), "CHOICE", outcome);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public TopicRouteState advance(Long memberId, Long topicId, Long activityId, Integer expectedVersion,
                                   String trustedTriggerType, NodeOutcome outcome) {
        return advance(memberId, topicId, activityId, expectedVersion, outcome.getReceiptId(), trustedTriggerType, outcome);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public TopicRouteState advance(Long memberId, Long topicId, Long activityId, Integer expectedVersion,
                                   String routeActionId, String trustedTriggerType, NodeOutcome outcome) {
        return advanceOriginal(memberId, topicId, activityId, null, expectedVersion,
                routeActionId, trustedTriggerType, outcome);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public TopicRouteState advanceOriginal(Long memberId, Long topicId, Long activityId, Long sessionId,
            Integer expectedVersion, String routeActionId, String trustedTriggerType, NodeOutcome outcome) {
        Scope scope = requireScope(memberId, topicId, activityId,true);
        TopicRouteSession original = null;
        if (sessionId != null) {
            original = routeMapper.selectAccessibleByIdForUpdate(sessionId, memberId);
            if (original == null || !memberId.equals(original.getMemberId())
                    || !topicId.equals(original.getTopicId())
                    || !java.util.Objects.equals(scope.activityId, original.getActivityId())
                    || StringUtils.isBlank(original.getPlayInstanceId()))
                throw new ServiceException("原局身份不匹配", 409);
            scope.originalPlayInstance = original.getPlayInstanceId();
            scope.originalSession = original;
        }
        if (original == null ? genericPlayer(memberId,topicId,activityId) : genericPlayer(original)) throw new ServiceException("通用个人局请使用任务结算",409);
        if (!TopicRouteState.BRANCH_GRAPH.equals(routeMode(scope))) {
            List<CmsTopicNode> liveNodes = nodes(topicId);
            JourneyTemplateProbe probe = publicationCompiler().probe(liveNodes);
            if (original == null && !journeyConfigured(scope, probe)) return linearState(scope, liveNodes);
            return advanceLinear(scope, original == null ? ensureJourneySession(scope, probe) : original,
                    expectedVersion, routeActionId, outcome);
        }
        TopicRouteSession session = original == null ? ensureBranchSession(scope) : original;
        JsonNode opening = openingSnapshot(session);
        TopicRouteEngine.State before = readEngineState(session.getStateJson());
        if (opening != null && !before.getTags().contains("sys.opening.done")
                && outcome.getNodeId().equals(nextOpeningNode(opening, before))
                && !outcome.getNodeId().equals(before.getCurrentNodeId())) {
            return advanceLinear(scope, session, expectedVersion, routeActionId, outcome);
        }
        boolean scoreActive = orientRouteSettlement != null && orientRouteSettlement.active(scope.topic);
        if (scoreActive) session = orientRouteSettlement.lock(session);
        if (routeActionId == null || routeActionId.trim().isEmpty() || routeActionId.length() > 96) {
            throw new IllegalArgumentException("routeActionId 无效");
        }
        TopicRouteAction replay = routeMapper.selectActionByAction(session.getId(), memberId, routeActionId);
        if (replay != null) return replay(replay, memberId, session.getId());
        if (expectedVersion == null) throw new IllegalArgumentException("expectedVersion 不能为空");
        String boundReceiptId = bindReceiptToVersion(outcome.getReceiptId(), expectedVersion);
        replay = routeMapper.selectActionByReceipt(session.getId(), boundReceiptId);
        if (replay != null) return replay(replay, memberId, session.getId());
        if (!expectedVersion.equals(session.getVersion())) {
            throw routeConflict(toView(session, readEngineState(session.getStateJson())), session);
        }
        List<CmsTopicNode> liveNodes = nodes(topicId);
        Set<Long> liveNodeIds = new LinkedHashSet<Long>();
        for (CmsTopicNode liveNode : liveNodes) liveNodeIds.add(liveNode.getId());
        if (!liveNodeIds.contains(outcome.getNodeId())) throw new IllegalStateException("节点当前不可用");
        if (!hasCompleted(scope, outcome.getNodeId())) throw new IllegalStateException("节点完成回执不存在");
        if (!openingAllows(session, outcome.getNodeId())) throw new IllegalStateException("请先完成开局流程");

        CmsTopicNode completedNode = null;
        for (CmsTopicNode liveNode : liveNodes) {
            if (outcome.getNodeId().equals(liveNode.getId())) { completedNode = liveNode; break; }
        }
        // 节点玩法模板的 requires/effects 是本站规则:requires 不过当场拒绝,
        // 且在 action 行插入**之前** —— 拒绝不能留下任何可重放的回执。
        JsonNode blockConfig = nodeBlockConfig(session, completedNode);
        TopicRouteEngine.State state = readEngineState(session.getStateJson());
        bindStoryContext(session, scope, blockConfig, outcome.getNodeId(), state);
        JsonNode requires = blockConfig == null ? null : blockConfig.get("requires");
        if (requires != null && requires.isArray() && requires.size() > 0
                && !engine.conditionsPass(requires, state)) {
            throw new IllegalStateException("本站前置条件未满足");
        }

        TopicRouteAction action = new TopicRouteAction();
        action.setSessionId(session.getId()); action.setMemberId(memberId); action.setNodeId(outcome.getNodeId());
        action.setActionId(routeActionId); action.setReceiptId(boundReceiptId);
        action.setOutcomeCode(outcome.getOutcomeCode());
        action.setResponseJson("{}"); action.setCreateTime(new Date());
        try {
            if (routeMapper.insertAction(action) != 1) throw new IllegalStateException("路线动作登记失败");
        } catch (DuplicateKeyException duplicate) {
            TopicRouteAction raced = routeMapper.selectActionByAction(session.getId(), memberId, routeActionId);
            if (raced == null) raced = routeMapper.selectActionByReceipt(session.getId(), boundReceiptId);
            if (raced == null) throw duplicate;
            return replay(raced, memberId, session.getId());
        }

        Set<Long> unavailableNodeIds = new LinkedHashSet<Long>(engine.initial(session.getConfigSnapshotJson())
                .getNodeStates().keySet());
        unavailableNodeIds.removeAll(liveNodeIds);
        JsonNode nodeRequirements = validator.readTree(session.getConfigSnapshotJson()).path("nodeRequirements");
        if (nodeRequirements.isArray() && nodeRequirements.size() > 0) {
            TopicRouteCandidatePolicy.Evaluation eligibility = new TopicRouteCandidatePolicy(clock, reachabilityProvider)
                    .evaluate(session.getConfigSnapshotJson(), policyFacts(scope), scope.activityId,
                            completedNode, liveNodes);
            unavailableNodeIds.addAll(eligibility.getUnavailableNodeIds());
        }
        // 本站效果先写进同一个 state:与 engine.advance 一起走同一次 updateStateCas,
        // 同一个版本、同一个 routeActionId;重放走上面的回执分支,不会二次写入。
        JourneyRules rules = journeyRules(session, scope.topic);
        refreshEnvironment(scope.topic, rules, state);
        JsonNode effects = blockConfig == null ? null : blockConfig.get("effects");
        applyJourneyEffects(effects, rules, state);
        // 站末恢复也先落进同一个 state:本节点真扣过血才回,封顶由数值规则管。
        applyStationEndRecovery(state, rules, completedNode);
        TopicRouteEngine.Decision decision = engine.advance(session.getConfigSnapshotJson(), state,
                trustedTriggerType, outcome, session.getSeed(), unavailableNodeIds, new Date());
        TopicRouteEngine.State next = decision.getState();
        JourneyInitialState.clamp(rules, next);
        // 阶段休整:进入新章节的第一个节点时抬下限;stage-rest:{chapterId} 一局一章只触发一次。
        applyStageRest(session, memberId, next, rules, findNode(liveNodes, next.getCurrentNodeId()), true);
        syncTheme(session, next, "node:" + routeActionId, JourneyStatLinks.Reason.TEMPLATE_EFFECT);
        themeEngine.signal(themeConfig(session), next, rules, "NODE", outcome.getNodeId(), "", "", 0, "node:" + outcome.getNodeId());
        session.setStateJson(writeSettled(session, next));
        session.setStatus(statusOf(next));
        keepOpeningRunning(session, next);
        session.setCurrentNodeId(next.getCurrentNodeId());
        session.setRecommendedNodeId(next.getRecommendedNodeId());
        if (session.getStatus() == TopicRouteSession.STATUS_COMPLETED) session.setCompletedAt(new Date());
        session.setUpdateTime(new Date());
        int oldVersion = session.getVersion();
        if (scoreActive) orientRouteSettlement.beforePersist(session, next);
        if (routeMapper.updateStateCas(session) != 1) throw new ServiceException("路线状态已更新，请刷新后重试", HttpStatus.CONFLICT);
        session.setVersion(oldVersion + 1);
        if (scoreActive) orientRouteSettlement.complete(session, next);
        TopicRouteState view = toView(session, next);
        putVitals(view, session, next);
        appendEncounter(view, session);
        if (routeMapper.updateActionResponse(action.getId(), write(view)) != 1) {
            throw new IllegalStateException("路线动作回执保存失败");
        }
        return view;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public boolean canPlay(Long memberId, Long topicId, Long activityId, Long nodeId) {
        return canPlay(memberId, topicId, activityId, nodeId, null);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public boolean canPlay(Long memberId, Long topicId, Long activityId, Long nodeId, String routeActionId) {
        if (sharedSnapshot(memberId, topicId, activityId, nodeId) != null) return true;
        if (gameSessionRuntimeService != null) gameSessionRuntimeService.assertRoutePlayable(activityId, nodeId);
        Scope scope = requireScope(memberId, topicId, activityId,true);
        CmsTopicNode requested = nodeMapper.selectCmsTopicNodeById(nodeId);
        if (requested == null || !topicId.equals(requested.getTopicId())) return false;
        List<CmsTopicNode> nodes = nodes(topicId);
        Set<Long> completed = completed(scope);
        String mode = routeMode(scope);
        if (TopicRouteState.LINEAR.equals(mode) && gameSessionRuntimeService != null) {
            gameSessionRuntimeService.assertRouteReception(activityId, nodeId);
        }
        TopicRouteState routeState = null;
        TopicRouteSession journeySession = null;
        if (TopicRouteState.BRANCH_GRAPH.equals(mode)) {
            journeySession = ensureBranchSession(scope);
            routeState = toAvailableView(journeySession, nodes);
        } else {
            JourneyTemplateProbe probe = publicationCompiler().probe(nodes);
            if (journeyConfigured(scope, probe)) {
                // 线性主题建局后 has requires/effects 的块同样受本局状态约束。
                journeySession = ensureJourneySession(scope, probe);
                routeState = toLinearView(journeySession, scope, nodes);
            }
        }
        if (routeState != null && routeActionId != null && !routeActionId.trim().isEmpty()) {
            TopicRouteAction replay = routeMapper.selectActionByAction(routeState.getSessionId(), memberId, routeActionId);
            if (replay != null && nodeId.equals(replay.getNodeId())) return true;
        }
        // 本站前置条件不满足就不给玩;没有本局状态的老线性主题照旧不拦。
        if (journeySession != null) {
            if (genericPlayer(journeySession)) {
                TopicRouteEngine.State state = readEngineState(journeySession.getStateJson());
                if(zeroChallenge(memberId,topicId,activityId,nodeId)!=null)return true;
                if(!"ACTIVE".equals(state.getStatus()))return false;
                for(JsonNode quest:themeConfig(journeySession).path("gameplayRuntime").path("quests"))if(quest.path("nodeId").asLong()==nodeId) {
                    try {gameplayTasks.eligible(themeConfig(journeySession),state,quest);return true;}
                    catch(IllegalStateException unavailable){ }
                }
                for(JsonNode facility:themeConfig(journeySession).path("gameplayRuntime").path("facilities"))if(facility.path("operationNodeId").asLong()==nodeId) {
                    try {previewPlayerFacility(themeConfig(journeySession),sessionRules(journeySession),state,journeySession.getMemberId(),facility);return true;}catch(IllegalArgumentException|IllegalStateException unavailable){return false;}
                }
                return false;
            }
            if (!openingAllows(journeySession, nodeId)) return false;
            JsonNode blockConfig = nodeBlockConfig(journeySession, requested);
            JsonNode requires = blockConfig == null ? null : blockConfig.get("requires");
            if (requires != null && requires.isArray() && requires.size() > 0
                    && !engine.conditionsPass(requires, readEngineState(journeySession.getStateJson()))) {
                return false;
            }
        }
        if (TopicRouteState.LINEAR.equals(mode) && journeySession != null) {
            Map<Long, String> frozen = readEngineState(journeySession.getStateJson()).getNodeStates();
            if (frozen != null && !frozen.isEmpty()) {
                boolean belongs = false;
                for (CmsTopicNode node : linearSessionNodes(journeySession, nodes)) {
                    if (nodeId.equals(node.getId())) { belongs = true; break; }
                    if (!completed.contains(node.getId())) return false;
                }
                if (!belongs) return false;
                Long prerequisite = requested.getUnlockAfterNodeId();
                return prerequisite == null || prerequisite <= 0 || completed.contains(prerequisite);
            }
        }
        return accessPolicy.evaluate(mode, requested, nodes, completed, routeState).isAllowed();
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public boolean isCompleted(Long memberId, Long topicId, Long activityId, int total, int done) {
        Scope scope = requireScope(memberId, topicId, activityId);
        String mode = routeMode(scope);
        TopicRouteSession session = currentSession(memberId,topicId,scope.activityId);
        if(session!=null && themeConfig(session).isObject() && !Integer.valueOf(TopicRouteSession.STATUS_COMPLETED).equals(session.getStatus()))return false;
        if (openingSnapshot(session) != null
                && !readEngineState(session.getStateJson()).getTags().contains("sys.opening.done")) return false;
        TopicRouteState routeState = TopicRouteState.BRANCH_GRAPH.equals(mode) ? state(memberId, topicId, activityId) : null;
        return completionPolicy.isCompleted(mode, total, done, routeState);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Integer> sessionStatuses(List<TopicRouteSession> scopes) {
        if (scopes == null || scopes.isEmpty()) return Collections.emptyMap();
        List<TopicRouteSession> sessions = routeMapper.selectByOwners(scopes);
        if (sessions == null || sessions.isEmpty()) return Collections.emptyMap();
        Map<String, Integer> statuses = new LinkedHashMap<>();
        for (TopicRouteSession session : sessions) {
            statuses.put(ITopicRouteRuntimeService.sessionKey(session.getMemberId(), session.getTopicId(),
                    session.getActivityId()), session.getStatus());
        }
        return statuses;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> participantMemberIds(Long topicId) {
        if (topicId == null) return Collections.emptySet();
        return new LinkedHashSet<>(routeMapper.selectDistinctMemberIdsByTopic(topicId, null));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> completedMemberIds(Long topicId) {
        if (topicId == null) return Collections.emptySet();
        return new LinkedHashSet<>(routeMapper.selectDistinctMemberIdsByTopic(
                topicId, TopicRouteSession.STATUS_COMPLETED));
    }

    @Override
    @Transactional(readOnly = true)
    public int countParticipants(Long topicId) {
        return topicId == null ? 0 : routeMapper.countDistinctMembersByTopic(topicId, null);
    }

    @Override
    @Transactional(readOnly = true)
    public int countCompletedParticipants(Long topicId) {
        return topicId == null ? 0 : routeMapper.countDistinctMembersByTopic(topicId, TopicRouteSession.STATUS_COMPLETED);
    }

    /**
     * 掷骰:动作 id 由服务端生成,同 id 重放原样返回上次结果 —— 不重掷、不改状态。
     * 未结算前的回执绝不携带 successText / failText / 任何 effects。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public PlayCheckReceipt roll(Long memberId, Long topicId, Long activityId, Long nodeId, String checkId) {
        return roll(memberId, topicId, activityId, nodeId, checkId, null);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public PlayCheckReceipt roll(Long memberId, Long topicId, Long activityId, Long nodeId, String checkId, Long sessionId) {
        String actionId = checkActionId(nodeId, checkId, STEP_ROLL);
        TopicRouteSession session = requireCheckSession(memberId, topicId, activityId, sessionId);
        PlayCheckReceipt replayed = replayCheck(session, memberId, actionId);
        if (replayed != null) return replayed;
        CheckContext context = checkContext(memberId, topicId, session, nodeId, checkId);
        if (findCheckAction(session, memberId, nodeId, checkId, JourneyCheckActionId.STEP_SETTLE) != null) {
            throw new IllegalStateException("该检定已结算");
        }
        if (shopDayProofService == null
                || !shopDayProofService.hasArrived(memberId, session.getActivityId(), nodeId)) {
            throw new IllegalStateException("请先扫码确认到店");
        }
        // 阶段 3:力竭要有后果。在此之前 HP 归零只写一个标记 —— 游戏照玩、节点照推进、检定照掷,
        // 「余量」没有牙齿,模式 1(持久资源)就不成立。拦的是「再开一次」:已掷未结算的那次
        // 仍可 settle(见 exhaustionDoesNotStrandAnAlreadyRolledCheck),不把玩家卡在中间态。
        if (isExhausted(context.state)) {
            throw new IllegalStateException("力竭了，先找地方休整");
        }
        JsonNode requires = context.blockConfig == null ? null : context.blockConfig.get("requires");
        if (requires != null && requires.isArray() && requires.size() > 0
                && !engine.conditionsPass(requires, context.state)) {
            throw new IllegalStateException("本站前置条件未满足");
        }
        PlayCheckReceipt receipt = rollDice(context, false);
        applyVitals(context.state, context.rules, receipt);
        return commitCheck(context.session, nodeId, actionId, "CHECK_ROLL", receipt, context.state);
    }

    /**
     * 花幸运重掷:只在「已掷、未结算、未重掷」且主题启用幸运、剩余幸运大于 0 时成立。
     * 扣幸运与结果登记走同一次 CAS;同 id 重放返回同一结果(不二次扣幸运)。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public PlayCheckReceipt reroll(Long memberId, Long topicId, Long activityId, Long nodeId, String checkId) {
        return reroll(memberId, topicId, activityId, nodeId, checkId, null);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public PlayCheckReceipt reroll(Long memberId, Long topicId, Long activityId, Long nodeId, String checkId, Long sessionId) {
        String actionId = checkActionId(nodeId, checkId, STEP_REROLL);
        TopicRouteSession session = requireCheckSession(memberId, topicId, activityId, sessionId);
        PlayCheckReceipt replayed = replayCheck(session, memberId, actionId);
        if (replayed != null) return replayed;
        CheckContext context = checkContext(memberId, topicId, session, nodeId, checkId);
        if (findCheckAction(session, memberId, nodeId, checkId, STEP_ROLL) == null) {
            throw new IllegalStateException("请先掷骰");
        }
        if (findCheckAction(session, memberId, nodeId, checkId, JourneyCheckActionId.STEP_SETTLE) != null) {
            throw new IllegalStateException("该检定已结算");
        }
        if (JourneyRpg.momentumEnabled(context.rules)
                && findCheckAction(session, memberId, nodeId, checkId, STEP_MOMENTUM) != null) {
            throw rpgRefusal("已用动量补足，不能再重掷", "RPG_MOMENTUM_USED");
        }
        if (!context.rules.getLuck().isEnabled()) throw new IllegalStateException("本主题未启用幸运重掷");
        int luck = luckOf(context.state, context.rules);
        if (luck <= 0) throw new IllegalStateException("幸运值不足");
        PlayCheckReceipt receipt = rollDice(context, true);
        context.state.getVariables().put(SYS_LUCK, luck - 1);
        applyVitals(context.state, context.rules, receipt);
        return commitCheck(context.session, nodeId, actionId, "CHECK_REROLL", receipt, context.state);
    }

    /**
     * 结算:以最后一次结果(重掷过就用重掷结果)为准,效果、失败代价、扣血与力竭标记
     * 全部写进同一次 CAS;重复调用返回同一结算,不二次扣血。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public PlayCheckReceipt settle(Long memberId, Long topicId, Long activityId, Long nodeId, String checkId) {
        return settle(memberId, topicId, activityId, nodeId, checkId, null);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public PlayCheckReceipt settle(Long memberId, Long topicId, Long activityId, Long nodeId, String checkId, Long sessionId) {
        String actionId = checkActionId(nodeId, checkId, JourneyCheckActionId.STEP_SETTLE);
        TopicRouteSession session = requireCheckSession(memberId, topicId, activityId, sessionId);
        PlayCheckReceipt replayed = replayCheck(session, memberId, actionId);
        if (replayed != null) return replayed;
        CheckContext context = checkContext(memberId, topicId, session, nodeId, checkId);
        TopicRouteAction rollAction = findCheckAction(session, memberId, nodeId, checkId, STEP_ROLL);
        if (rollAction == null) throw new IllegalStateException("请先掷骰");
        TopicRouteAction rerollAction = findCheckAction(session, memberId, nodeId, checkId, STEP_REROLL);
        // F6 T10:花过动量就以补足后的那份为准;没开动量的主题不多查这一次。
        TopicRouteAction momentumAction = JourneyRpg.momentumEnabled(context.rules)
                ? findCheckAction(session, memberId, nodeId, checkId, STEP_MOMENTUM) : null;
        PlayCheckReceipt last = readReceipt(momentumAction != null ? momentumAction
                : rerollAction != null ? rerollAction : rollAction);
        boolean success = Boolean.TRUE.equals(last.getSuccess());
        // 检定也是判定型玩法:成败同样落成可引用事实 sys.passed.{nodeId},与结算同一次 CAS。
        context.state.getVariables().put(SYS_PASSED_PREFIX + nodeId, Integer.valueOf(success ? 1 : 0));
        String nat = last.getNat();
        String failCostLabel = null;
        JourneyRules.Tier tier = tierOf(context.rules, last.getTier() == null
                ? context.check.path("tier").asText("") : last.getTier());
        if (success) {
            applyJourneyEffects(context.check.path("successEffects"), context.rules, context.state);
            // R14 大成功:nat 20 在 successEffects 之后追加;自然 20 必成,不会走到失败支。
            if (PlayCheckReceipt.NAT_CRIT.equals(nat)) {
                applyJourneyEffects(context.check.path("critEffects"), context.rules, context.state);
            }
        } else {
            applyJourneyEffects(context.check.path("failEffects"), context.rules, context.state);
            // R14 大失败:nat 1 在 failEffects 之后追加。
            if (PlayCheckReceipt.NAT_FUMBLE.equals(nat)) {
                applyJourneyEffects(context.check.path("fumbleEffects"), context.rules, context.state);
            }
            String failCostTag = textField(context.check, "failCostTag");
            if (failCostTag != null && !failCostTag.isEmpty()) {
                context.state.getTags().add(failCostTag);
                failCostLabel = failCostTagLabel(context.session, failCostTag);
            }
            if (context.rules.getHp().isEnabled()) {
                int damage = (tier == null ? 0 : tier.getDamage())
                        + (PlayCheckReceipt.NAT_FUMBLE.equals(nat) ? context.rules.getFumbleExtraDamage() : 0);
                int before = hpOf(context.state, context.rules);
                int hp = Math.max(0, before - themeEngine.reduceHpCost(themeConfig(context.session),context.state,damage));
                context.state.getVariables().put(SYS_HP, hp);
                // 站末恢复只认「真扣过」的血:记录本节点实际扣血量。
                addDamage(context.state, nodeId, before - hp);
                if (hp == 0) context.state.getVariables().put(SYS_EXHAUSTED, 1);
            }
        }
        PlayCheckReceipt receipt = new PlayCheckReceipt();
        receipt.setCheckId(last.getCheckId());
        receipt.setTier(last.getTier());
        receipt.setDc(last.getDc());
        receipt.setMods(last.getMods());
        receipt.setAdvantage(last.isAdvantage());
        receipt.setDisadvantage(last.isDisadvantage());
        receipt.setDice(last.getDice());
        receipt.setKept(last.getKept());
        receipt.setTotal(last.getTotal());
        receipt.setSuccess(success);
        receipt.setNat(nat);
        receipt.setRerolled(last.isRerolled());
        receipt.setSettled(true);
        receipt.setText(success ? textField(context.check, "successText") : textField(context.check, "failText"));
        receipt.setFailCostLabel(failCostLabel);
        if (JourneyRpg.momentumEnabled(context.rules)) {
            // F6 T10:失败也有收获(fail forward)—— 结算时按成败攒动量,封顶 max;与结算同一次 CAS。
            JsonNode momentumRules = JourneyRpg.momentum(context.rules);
            int gain = success ? momentumRules.path("gainOnSuccess").asInt(0) : momentumRules.path("gainOnFail").asInt(0);
            int before = JourneyRpg.momentumOf(context.state);
            int after = JourneyRpg.setMomentum(context.rules, context.state, before + gain);
            receipt.setMomentum(momentumView(context.rules, after, "gained", after - before));
        }
        applyVitals(context.state, context.rules, receipt);
        return commitCheck(context.session, nodeId, actionId, "CHECK_SETTLE", receipt, context.state);
    }

    /** 力竭休整:只对已力竭的本局成立;回血、清标记、挂休整 tag 走同一次 CAS。 */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public PlayCheckReceipt rest(Long memberId, Long topicId, Long activityId, Long nodeId) {
        if (nodeId == null || nodeId <= 0L) throw new IllegalArgumentException("nodeId 无效");
        TopicRouteSession session = requireCheckSession(memberId, topicId, activityId);
        int version = session.getVersion() == null ? 0 : session.getVersion();
        String actionId = "rest:" + nodeId + ":" + version;
        PlayCheckReceipt replayed = replayCheck(session, memberId, actionId);
        if (replayed != null) return replayed;
        Scope scope = requireScope(memberId, topicId, session.getActivityId(),true);
        CmsTopicNode node = nodeMapper.selectCmsTopicNodeById(nodeId);
        if (node == null || !topicId.equals(node.getTopicId())) throw new IllegalStateException("节点不存在");
        JourneyRules rules = journeyRules(session, scope.topic);
        TopicRouteEngine.State state = readEngineState(session.getStateJson());
        if (!isExhausted(state)) throw new IllegalStateException("尚未力竭，无需休整");
        if(themeConfig(session).isObject())applyThemeRescue(session,state,rules);
        state.getVariables().put(SYS_HP, rules.getRecovery().getExhaustRestHp());
        state.getVariables().remove(SYS_EXHAUSTED);
        String exhaustTag = rules.getRecovery().getExhaustTag();
        if (exhaustTag != null && !exhaustTag.trim().isEmpty()) state.getTags().add(exhaustTag.trim());
        PlayCheckReceipt receipt = new PlayCheckReceipt();
        applyVitals(state, rules, receipt);
        return commitCheck(session, nodeId, actionId, "CHECK_REST", receipt, state);
    }

    /**
     * R18-RECEIPT 收口:本局当前 hp / luck(当前值,数值规则冻结优先,老局回退实时主题规则)。
     * 没有本局 / 读不到状态时返回 null —— 回执只是展示,不能把已成立的动作打回。
     */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Integer> vitals(Long memberId, Long topicId, Long activityId) {
        if (memberId == null || memberId <= 0L || topicId == null || topicId <= 0L) return null;
        TopicRouteSession session = currentScopedSession(memberId, topicId, activityId);
        if (session == null || !memberId.equals(session.getMemberId())) return null;
        final TopicRouteEngine.State state;
        try {
            state = readEngineState(session.getStateJson());
        } catch (RuntimeException unusableState) {
            return null;
        }
        return vitalsView(session, state);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean conditionsPass(Long memberId, Long topicId, Long activityId, JsonNode conditions) {
        if (memberId == null || memberId <= 0L || topicId == null || topicId <= 0L
                || conditions == null || !conditions.isArray() || conditions.size() == 0) return false;
        TopicRouteSession session = currentScopedSession(memberId, topicId, activityId);
        if (session == null || !memberId.equals(session.getMemberId())) return false;
        try {
            return engine.conditionsPass(conditions, readEngineState(session.getStateJson()));
        } catch (RuntimeException unusableState) {
            return false;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Set<String> tags(Long memberId, Long topicId, Long activityId) {
        if (memberId == null || memberId <= 0L || topicId == null || topicId <= 0L) return java.util.Collections.emptySet();
        TopicRouteSession session = currentScopedSession(memberId, topicId, activityId);
        if (session == null || !memberId.equals(session.getMemberId())) return java.util.Collections.emptySet();
        try {
            return new java.util.LinkedHashSet<String>(readEngineState(session.getStateJson()).getTags());
        } catch (RuntimeException unusableState) {
            return java.util.Collections.emptySet();
        }
    }

    /** hp / luck 当前值(与检定回执 applyVitals 同一份读数口径)。 */
    private Map<String, Integer> vitalsView(TopicRouteSession session, TopicRouteEngine.State state) {
        JourneyRules rules = sessionRules(session);
        Map<String, Integer> vitals = new LinkedHashMap<String, Integer>();
        vitals.put("hp", Integer.valueOf(hpOf(state, rules)));
        vitals.put("luck", Integer.valueOf(luckOf(state, rules)));
        return vitals;
    }

    /** 旅程动作回执顶层补 hp / luck 当前值(R18-RECEIPT 收口)。 */
    private void putVitals(Map<String, Object> receipt, TopicRouteSession session, TopicRouteEngine.State state) {
        receipt.putAll(vitalsView(session, state));
        receipt.put("attributes", JourneyInitialState.playerView(sessionRules(session), state));
    }

    /**
     * R22-A7:推进回执(TopicRouteState)顶层补 hp / luck 当前值 —— 站末恢复与阶段休整只在
     * advance / advanceLinear 里发生,与 ask / call / 检定 / advanced action 同一口径,
     * 客户端不用再拉一次 vitals。只读的 state() 不走这里(两位保持 null 不下发)。
     */
    private void putVitals(TopicRouteState view, TopicRouteSession session, TopicRouteEngine.State state) {
        if (view == null) return;
        Map<String, Integer> vitals = vitalsView(session, state);
        view.setHp(vitals.get("hp"));
        view.setLuck(vitals.get("luck"));
        view.setAttributes(JourneyInitialState.playerView(sessionRules(session), state));
    }

    /** 本局适用的数值规则:冻结快照优先;老局快照没有该键时才查实时主题列。 */
    private JourneyRules sessionRules(TopicRouteSession session) {
        JsonNode snapshot = null;
        if (session != null && session.getConfigSnapshotJson() != null) {
            try { snapshot = JSON.readTree(session.getConfigSnapshotJson()); }
            catch (Exception unusableSnapshot) { /* Preserve legacy whole-snapshot fallback. */ }
        }
        if (snapshot != null && snapshot.has(NodeJourneySnapshot.JOURNEY_RULES_KEY)
                && snapshot.get(NodeJourneySnapshot.JOURNEY_RULES_KEY).isNull()) {
            throw new IllegalStateException("旅程数值规则不可用");
        }
        String frozen = NodeJourneySnapshot.journeyRules(
                session == null ? null : session.getConfigSnapshotJson());
        if (frozen != null) return FrozenAttributeDefinitions.rules(snapshot, session.getConfigVersion());
        return journeyRules(session == null ? null : topicMapper.selectCmsTopicById(session.getTopicId()));
    }

    /**
     * 代价标签的展示名:注册表里该 tag 的 label。未注册 / 没填 label / 注册表未注入一律回落
     * 空串,不抛错 —— 回执只是展示,不能因为展示名缺失把一次已经成立的结算打回。
     */
    private String failCostTagLabel(TopicRouteSession session, String tag) {
        List<ThemeStateKey> rows = JourneyInitialState.frozenDefinitions(session.getConfigSnapshotJson());
        if (rows == null) rows = themeStateKeyMapper == null ? null : themeStateKeyMapper.selectByTopicId(session.getTopicId());
        if (rows == null) return "";
        for (ThemeStateKey row : rows) {
            if (row != null && tag.equals(row.getStateKey())) {
                return row.getLabel() == null ? "" : row.getLabel();
            }
        }
        return "";
    }

    /**
     * 本局开局的模板判定字段快照(R18-FREEZE):validationMethod 与各验证方式的答案 / 揭示 /
     * 偏好 / 传感器配置。没有本局(非旅程主题)或老快照没有 nodeAnswers 键时返回 null,
     * 由调用方回退实时模板(兼容,老局行为不变)。
     */
    @Override
    @Transactional(readOnly = true)
    public JsonNode nodeAnswers(Long memberId, Long topicId, Long activityId, Long nodeId) {
        if (nodeId == null || nodeId <= 0L) return null;
        JsonNode snapshot = queryNodeContentSnapshot(memberId, topicId, activityId, nodeId);
        JsonNode answer = NodeJourneySnapshot.answerFromSnapshot(snapshot, nodeId);
        if (NodeJourneySnapshot.hasContent(answer)
                && answer.path("nodePresentation").path("topicId").asLong() != topicId)
            throw new IllegalStateException("本局内容归属不可用");
        return answer;
    }

    @Override
    @Transactional(readOnly = true)
    public JsonNode queryNodeContentSnapshot(Long memberId, Long topicId, Long activityId, Long nodeId) {
        if (memberId == null || memberId <= 0L || topicId == null || topicId <= 0L
                || nodeId != null && nodeId <= 0L) return null;
        JsonNode shared = gameSessionRuntimeService == null ? null
                : gameSessionRuntimeService.querySharedRouteSnapshot(memberId, topicId, activityId, nodeId);
        if (shared != null) return shared;
        TopicRouteSession session = currentScopedSession(memberId, topicId, activityId);
        if (session != null) {
            if (!memberId.equals(session.getMemberId())) throw new IllegalStateException("本局内容归属不可用");
            // An old existing run is never filled from a newer publication.
            JsonNode snapshot = session.getConfigSnapshotJson() == null ? JSON.createObjectNode()
                    : validator.readTree(session.getConfigSnapshotJson()).deepCopy();
            NodeJourneySnapshot.requireContentSnapshot(snapshot);
            if (snapshot.isObject()) {
                ((ObjectNode) snapshot).remove(java.util.Arrays.asList("gameSessionId", "teamId", "authorityEpoch", "runtimeInstance"));
                ((ObjectNode) snapshot).put("contentRouteSessionId", session.getId());
                if (NodeJourneySnapshot.hasContentSnapshots(snapshot))
                    ((ObjectNode) snapshot).set("contentTags", JSON.valueToTree(readEngineState(session.getStateJson()).getTags()));
            }
            if (nodeId != null) {
                JsonNode answer = NodeJourneySnapshot.answerFromSnapshot(snapshot, nodeId);
                if (NodeJourneySnapshot.hasContent(answer)
                        && (answer.path("nodePresentation").path("id").asLong() != nodeId
                        || answer.path("nodePresentation").path("topicId").asLong() != topicId))
                    throw new IllegalStateException("本局内容归属不可用");
            }
            return snapshot;
        }
        JsonNode publication=publishedContentSnapshot(topicId);
        JsonNode games=advancedGameRuntimeService == null ? null
                : advancedGameRuntimeService.queryDefaultNodeContents(memberId,activityId,topicId);
        if (games == null || games.isEmpty()) return publication;
        ObjectNode content=publication == null ? JSON.createObjectNode() : (ObjectNode) publication.deepCopy();
        content.set("originalNodeGames",games.deepCopy());
        return content;
    }

    /** Caller admission remains at the original command/query boundary; no session is created here. */
    private JsonNode publishedContentSnapshot(Long topicId) {
        CmsTopic topic = topicMapper.selectCmsTopicById(topicId);
        if (topic == null || topic.getPublishedConfigVersion() == null || topic.getPublishedConfigVersion() <= 0L
                || topic.getPublishedRouteGraphJson() == null || topic.getPublishedRouteGraphJson().trim().isEmpty()) return null;
        JsonNode snapshot = validator.readTree(topic.getPublishedRouteGraphJson());
        if (!NodeJourneySnapshot.hasContentSnapshots(snapshot)) return null;
        NodeJourneySnapshot.requireContentSnapshot(snapshot);
        ObjectNode copy = (ObjectNode) snapshot.deepCopy();
        copy.remove(java.util.Arrays.asList("contentRouteSessionId", "contentTags", "gameSessionId", "teamId", "authorityEpoch", "runtimeInstance"));
        return copy;
    }

    private TopicRouteSession ensureBranchSession(Scope scope) {
        TopicRouteSession existing = currentSession(scope.memberId, scope.topic.getId(), scope.activityId);
        if (existing != null) return existing;
        if (topicPlaySetupService != null) topicPlaySetupService.requireOpen(scope.topic, scope.activityId);
        com.chengyinhub.business.service.support.CurrentPlayRegistration.require(activityMapper,registrationMapper,
                scope.memberId,scope.activityId>0 ? 2L : 1L,scope.activityId>0 ? scope.activityId : scope.topic.getId(),null,new Date());
        if (scope.topic.getPublishedRouteGraphJson() == null
                || scope.topic.getPublishedRouteGraphJson().trim().isEmpty()
                || scope.topic.getPublishedConfigVersion() == null
                || scope.topic.getPublishedConfigVersion() <= 0L) {
            throw new IllegalStateException("路线尚未发布");
        }
        List<CmsTopicNode> nodes = nodes(scope.topic.getId());
        Set<Long> nodeIds = new LinkedHashSet<Long>();
        for (CmsTopicNode node : nodes) nodeIds.add(node.getId());
        String snapshot = freezeJourney(
                validator.readTree(scope.topic.getPublishedRouteGraphJson()).path("runtimeSnapshotVersion").asInt() == 1
                        ? scope.topic.getPublishedRouteGraphJson() : validator.validateAndNormalize(scope.topic.getPublishedRouteGraphJson(), nodeIds),
                scope.topic, nodes);
        TopicRouteEngine.State initial = engine.initial(snapshot);
        JourneyRules branchRules = FrozenAttributeDefinitions.rules(validator.readTree(snapshot), scope.topic.getPublishedConfigVersion());
        applyJourneyInitials(branchRules, initial);
        seedCharacter(scope.memberId, scope.topic.getId(), branchRules, initial, snapshot);
        // 进入第一个节点 = 进入首个章节:阶段休整同样适用;action 行等 session 落库后再登记。
        CmsTopicNode firstNode = findNode(nodes, initial.getCurrentNodeId());
        Map<String, Object> stageRest = genericConfig(validator.readTree(snapshot).path("themeConfig")) ? null : applyStageRest(null, scope.memberId, initial,
                branchRules, firstNode, false);
        Date now = new Date();
        TopicRouteSession session = new TopicRouteSession();
        session.setActivityId(scope.activityId); session.setTopicId(scope.topic.getId());
        session.setMemberId(scope.memberId); session.setPlayInstanceId(DEFAULT_INSTANCE);
        session.setConfigVersion(scope.topic.getPublishedConfigVersion());
        session.setConfigSnapshotJson(snapshot); initializeTheme(session, initial);
        session.setStateJson(write(initial));
        session.setStatus(TopicRouteSession.STATUS_RUNNING); session.setCurrentNodeId(initial.getCurrentNodeId());
        session.setRecommendedNodeId(initial.getRecommendedNodeId()); session.setSeed(seedSupplier.getAsLong());
        session.setVersion(0); session.setCreateTime(now); session.setUpdateTime(now);
        syncTheme(session,initial); session.setStateJson(write(initial)); session.setCurrentNodeId(initial.getCurrentNodeId()); session.setRecommendedNodeId(initial.getRecommendedNodeId());
        try {
            if (routeMapper.insertSession(session) != 1) throw new IllegalStateException("路线局创建失败");
        } catch (DuplicateKeyException duplicate) {
            TopicRouteSession raced = routeMapper.selectByOwner(scope.activityId, scope.topic.getId(),
                    scope.memberId, DEFAULT_INSTANCE);
            if (raced == null) throw duplicate;
            return raced;
        }
        if(themeBroadcastService!=null)themeBroadcastService.enqueue(session,initial);
        if (stageRest != null) recordStageRestAction(session, scope.memberId, firstNode, stageRest);
        return session;
    }

    /**
     * 线性主题是否需要一局权威状态:主题级数值规则启用,或任一节点玩法块配了
     * requires / effects / 启用的 check。都不满足 = 老线性主题,不建局、行为一字不变。
     */
    private boolean journeyConfigured(Scope scope, JourneyTemplateProbe probe) {
        // 已有本局:旅程是否启用以建局快照为准 —— 创作者之后清空模板/主题规则也不退回无局线性读模型。
        if (currentSession(scope.memberId, scope.topic.getId(), scope.activityId) != null) return true;
        if (scope.topic.getPublishedRouteGraphJson() != null) {
            JsonNode published = validator.readTree(scope.topic.getPublishedRouteGraphJson());
            if (published.path("runtimeSnapshotVersion").asInt() == 1) {
                if (!NodeJourneySnapshot.hasContentSnapshots(published)) return true; // original V1 contract
                NodeJourneySnapshot.requireContentSnapshot(published);
                // ROOT_FINAL_CONTRACT_V3: original helpers consume frozen facts, never future live author rows.
                String frozenRules = NodeJourneySnapshot.journeyRules(published.toString());
                if (frozenRules == null) throw new IllegalStateException("本局数值规则快照不可用");
                JsonNode ruleFields = validator.readTree(frozenRules);
                if (JourneyRules.parse(frozenRules).hasRuntimeState() || ruleFields.path("attributes").size() > 0
                        || ruleFields.path("statLinks").size() > 0 || published.path("themeConfig").isObject()
                        || JourneyStory.openingChapterId(NodeJourneySnapshot.journeyStory(published.toString())) != null) return true;
                for (JsonNode block : published.path("nodeConfigs"))
                    if (blockNeedsJourneyState(block.toString())) return true;
                return false;
            }
        }
        List<CmsTopicNode> liveNodes = probe.liveNodes;
        if (JourneyStory.openingChapterId(scope.topic.getJourneyStoryJson()) != null) return true;
        if (liveNodes.isEmpty()) return false;
        if (scope.topic.getPublishedThemeRuntimeConfigJson() != null) return true;
        if (journeyRules(scope.topic).hasRuntimeState()) return true;
        Map<Long, String> configByTemplate = probe.configs();
        for (CmsTopicNode node : liveNodes) {
            if (blockNeedsJourneyState(configByTemplate.get(node.getTemplateId()))) return true;
        }
        return false;
    }

    /**
     * 建局冻结(B8):每个挂玩法模板的节点写一条 nodeJourney(空对象也写,封死老快照回退),
     * 玩法段(requires / effects / check)从模板拷;叙事段(R18)从章节故事流按站点组装后
     * 写进同一个快照块(七段形状与旧契约一字不差,运行期读侧零改动)。
     * 主题 journeyRules 一并冻结,缺省冻结成默认规则。
     * R11-A:主题剧情 journey_story_json 同样冻结(缺省冻结成空串 = 建局时没有,开局后补配置
     * 不穿透进行中的局;键在而值为空时不再回退实时)。
     */
    @Override
    public JsonNode trustedGuidedRouteEffects(JsonNode frozen,Long nodeId,JsonNode verifiedAnswers) {
        JsonNode config=frozen.path("nodeConfigs").path(String.valueOf(nodeId));ArrayNode effects=TrustedNodeEffects.rootEffects(frozen,nodeId);
        if("ANSWER".equals(basicCompletionKind(frozen,nodeId))) {if(!verifyGuidedRouteAnswer(frozen,nodeId,verifiedAnswers))throw new IllegalArgumentException("答案未通过服务端判定");return effects;}
        for(String[] choice:AdvancedActionJudgement.verifiedObjectiveChoices(config,verifiedAnswers))for(JsonNode effect:trustedChoiceEffects(frozen,nodeId,choice[0],choice[1]))effects.add(effect.deepCopy());
        return effects;
    }

    @Override
    public JsonNode trustedChoiceEffects(JsonNode snapshot, Long nodeId, String kit, String option) {
        JsonNode block = snapshot.path(NodeJourneySnapshot.NODE_JOURNEY_KEY).path(String.valueOf(nodeId));
        if (block.has(NodeJourneySnapshot.CHOICE_EFFECTS_KEY)) block = block.path(NodeJourneySnapshot.CHOICE_EFFECTS_KEY);
        ChoiceEffects choice = branchChoice(block, kit, (option == null ? "" : option));
        if (choice == null && NodeJourneySnapshot.CHOICE_KITS.contains(kit)) choice = kitChoice(block, kit, (option == null ? "" : option));
        return choice == null || choice.effects == null ? JSON.createArrayNode() : choice.effects;
    }

    /** A publication operation owns its compiler inputs; it never captures this runtime or changes transactions. */
    private TopicRoutePublicationCompiler publicationCompiler() {
        return new TopicRoutePublicationCompiler(nodeMapper, templateMapper, cmsTopicChapterMapper,
                themeStateKeyMapper, clock, JSON, validator, topicMapper);
    }

    @Override
    public String compileTemplateRules(CmsTopic topic) {
        return publicationCompiler().compileTemplateRules(topic);
    }

    @Override
    public String freezePublication(CmsTopic topic, String routeSnapshot) {
        return publicationCompiler().freezePublication(topic, routeSnapshot);
    }

    @Override
    public void deriveTemplateCluesForPublication(CmsTopic topic) {
        publicationCompiler().deriveTemplateCluesForPublication(topic);
    }

    @Override
    public String freezePublication(CmsTopic topic, String routeSnapshot, List<ThemeStateKey> declarations) {
        return publicationCompiler().freezePublication(topic, routeSnapshot, declarations);
    }

    @Override
    public String freezePublishedJourney(CmsTopic topic) {
        return publicationCompiler().freezePublishedJourney(topic);
    }

    @Override
    public boolean verifyGuidedRouteAnswer(JsonNode frozen,Long nodeId,JsonNode answers) {
        if (frozen==null || nodeId==null || nodeId<=0) throw new IllegalArgumentException("题组节点参数不正确");
        if("ANSWER".equals(basicCompletionKind(frozen,nodeId))) {
            JsonNode answer=frozen.path(NodeJourneySnapshot.NODE_ANSWERS_KEY).path(String.valueOf(nodeId));String submitted=answers.path("answer").asText("").trim();
            if(answer.path("validationMethod").asInt()==3)return !answer.path("correctAnswer").asText().trim().isEmpty() && answer.path("correctAnswer").asText().trim().equalsIgnoreCase(submitted);
            String expected=answer.path("questionAnswer").asText();return !expected.trim().isEmpty() && normalizeBasicAnswer(expected).equalsIgnoreCase(normalizeBasicAnswer(submitted));
        }
        return AdvancedActionJudgement.verifyObjectiveAnswers(frozen.path("nodeConfigs").path(String.valueOf(nodeId)),answers);
    }

    private String normalizeBasicAnswer(String value) {
        String trimmed=value.trim().replaceAll("\\s+", ""); StringBuilder normalized=new StringBuilder();
        for(char c:trimmed.toCharArray()) {if(c>='！' && c<='～')normalized.append((char)(c-0xFEE0));else if(c=='　')normalized.append(' ');else normalized.append(c);}
        return normalized.toString();
    }

    private String basicCompletionKind(JsonNode frozen,Long nodeId) {
        return TopicRoutePublicationCompiler.basicCompletionKind(frozen, nodeId);
    }

    static boolean gameplayAddon(String field) {
        return GameplaySupportMatrix.addon(field);
    }

    private String freezeJourney(String snapshot, CmsTopic topic, List<CmsTopicNode> liveNodes) {
        return publicationCompiler().freezeJourney(snapshot, topic, liveNodes);
    }

    /** 建局冻结:模板探测由调用方传入,建局判定 / 块配置 / 判定字段冻结共用同一次模板查询。 */
    private String freezeJourney(String snapshot, CmsTopic topic, List<CmsTopicNode> liveNodes,
                                 JourneyTemplateProbe probe) {
        return publicationCompiler().freezeJourney(snapshot, topic, liveNodes, probe);
    }


    private String freezeJourney(String snapshot,CmsTopic topic,List<CmsTopicNode> liveNodes,
                                 Map<Long,String> configByTemplate,Map<Long,CmsMemberTemplate> templatesById,int sceneIndex) {
        return publicationCompiler().freezeJourney(snapshot, topic, liveNodes, configByTemplate, templatesById, sceneIndex);
    }

    /**
     * 块配置是否有需要本局状态的规则 —— R14 起与发布前试检查共用
     * {@link NodeJourneySnapshot#needsJourneyState}(口径只有一份)。坏 JSON 一律当成没配,
     * 发布门禁负责拦,运行期不打死老主题。
     */
    private boolean blockNeedsJourneyState(String advancedConfigJson) {
        return NodeJourneySnapshot.needsJourneyState(advancedConfigJson);
    }

    private JourneyRules journeyRules(CmsTopic topic) {
        return TopicRoutePublicationCompiler.journeyRules(topic);
    }

    /** 本局适用的数值规则:优先读建局冻结值;老局快照没有该键时回退实时主题配置。 */
    private JourneyRules journeyRules(TopicRouteSession session, CmsTopic topic) {
        JsonNode snapshot = null;
        if (session != null && session.getConfigSnapshotJson() != null) {
            try { snapshot = JSON.readTree(session.getConfigSnapshotJson()); }
            catch (Exception unusableSnapshot) { /* Preserve legacy whole-snapshot fallback. */ }
        }
        if (snapshot != null && snapshot.has(NodeJourneySnapshot.JOURNEY_RULES_KEY)
                && snapshot.get(NodeJourneySnapshot.JOURNEY_RULES_KEY).isNull()) {
            throw new IllegalStateException("旅程数值规则不可用");
        }
        String frozen = NodeJourneySnapshot.journeyRules(
                session == null ? null : session.getConfigSnapshotJson());
        return frozen == null ? journeyRules(topic) : FrozenAttributeDefinitions.rules(snapshot, session.getConfigVersion());
    }

    /**
     * 建局时的主题级数值规则初始化:sys.* 是服务端保留变量,玩家侧只读;
     * hp/luck 各自 enabled 时才写各自的 init 值。
     */
    private void applyJourneyInitials(CmsTopic topic, TopicRouteEngine.State state) {
        applyJourneyInitials(journeyRules(topic), state);
    }

    /**
     * F2 T02:persist_scope=topic 的属性跨局保留 —— 建新一局时先结转上一局、再灌回新局。
     * 只动已由 journeyRules 初始化的变量;主题没有 topic 作用域键时什么都不改(老主题行为不变)。
     */
    private void seedCharacter(Long memberId, Long topicId, JourneyRules rules, TopicRouteEngine.State state, String frozenSnapshot) {
        if (!rules.hasRuntimeState()) return;
        // F6:动量初值 / 常驻侧面。没开 rpg 的主题什么都不写。
        JourneyRpg.seedRun(rules, state);
        if (themeCharacterService == null) return;
        themeCharacterService.seedRun(memberId, topicId, rules, state, frozenSnapshot);
    }

    private void applyJourneyInitials(JourneyRules rules, TopicRouteEngine.State state) {
        if (!rules.hasRuntimeState()) return;
        JourneyInitialState.initialize(rules, state);
        if (rules.getHp() != null && rules.getHp().isEnabled()) {
            state.getVariables().put("sys.hp", Integer.valueOf(rules.getHp().getInit()));
        }
        if (rules.getLuck() != null && rules.getLuck().isEnabled()) {
            state.getVariables().put("sys.luck", Integer.valueOf(rules.getLuck().getInit()));
        }
    }

    /**
     * 线性主题的旅程局:合成一份没有边的快照(engine.advance 不参与线性推进),
     * completedNodeIds 用既有完成事实播种,免得建局前已通关的玩家从通关列表里消失。
     */
    private TopicRouteSession ensureJourneySession(Scope scope, JourneyTemplateProbe probe) {
        List<CmsTopicNode> liveNodes = probe.liveNodes;
        TopicRouteSession existing = currentSession(scope.memberId, scope.topic.getId(), scope.activityId);
        if (existing != null) return existing;
        if (topicPlaySetupService != null) topicPlaySetupService.requireOpen(scope.topic, scope.activityId);
        com.chengyinhub.business.service.support.CurrentPlayRegistration.require(activityMapper,registrationMapper,
                scope.memberId,scope.activityId>0 ? 2L : 1L,scope.activityId>0 ? scope.activityId : scope.topic.getId(),null,new Date());
        if (scope.topic.getPublishedConfigVersion() == null || scope.topic.getPublishedConfigVersion() <= 0L) {
            throw new IllegalStateException("路线尚未发布");
        }
        String frozenSnapshot = scope.topic.getPublishedRouteGraphJson() != null
                && validator.readTree(scope.topic.getPublishedRouteGraphJson()).path("runtimeSnapshotVersion").asInt() == 1
                ? freezePublishedJourney(scope.topic) : freezeJourney(linearSnapshot(liveNodes), scope.topic, liveNodes, probe);
        JsonNode publishedStations = validator.readTree(frozenSnapshot).path("routeStations");
        if (publishedStations.isArray()) {
            liveNodes = new ArrayList<>();
            for (JsonNode row : publishedStations) {
                CmsTopicNode node = new CmsTopicNode(); node.setId(row.path("nodeId").asLong());
                node.setName(row.path("nodeName").asText());
                if (row.has("chapterId")) node.setChapterId(row.path("chapterId").asLong());
                liveNodes.add(node);
            }
        }
        Set<Long> completed = completed(scope);
        TopicRouteEngine.State initial = new TopicRouteEngine.State();
        Long current = null;
        for (CmsTopicNode node : liveNodes) {
            if (completed.contains(node.getId())) {
                initial.getCompletedNodeIds().add(node.getId());
                initial.getNodeStates().put(node.getId(), TopicRouteState.COMPLETED);
            } else if (current == null) {
                current = node.getId();
                initial.getNodeStates().put(node.getId(), TopicRouteState.PLAYABLE);
                initial.getVisitedNodeIds().add(node.getId());
            } else {
                initial.getNodeStates().put(node.getId(), TopicRouteState.DISCOVERED_LOCKED);
            }
        }
        initial.setCurrentNodeId(current); initial.setRecommendedNodeId(current);
        initial.setStatus(current == null ? TopicRouteState.COMPLETED : "ACTIVE");
        JourneyRules initialRules = FrozenAttributeDefinitions.rules(validator.readTree(frozenSnapshot), scope.topic.getPublishedConfigVersion());
        applyJourneyInitials(initialRules, initial);
        seedCharacter(scope.memberId, scope.topic.getId(), initialRules, initial, frozenSnapshot);
        // 建局时所在节点也可能是某章节的第一个节点:同样适用阶段休整。
        CmsTopicNode firstNode = findNode(liveNodes, current);
        Map<String, Object> stageRest = genericConfig(validator.readTree(frozenSnapshot).path("themeConfig")) ? null : applyStageRest(null, scope.memberId, initial,
                initialRules, firstNode, false);
        Date now = new Date();
        TopicRouteSession session = new TopicRouteSession();
        session.setActivityId(scope.activityId); session.setTopicId(scope.topic.getId());
        session.setMemberId(scope.memberId); session.setPlayInstanceId(DEFAULT_INSTANCE);
        session.setConfigVersion(scope.topic.getPublishedConfigVersion());
        session.setConfigSnapshotJson(frozenSnapshot);
        initializeTheme(session, initial);
        session.setStateJson(write(initial));
        session.setStatus(genericPlayer(session) || current != null ? TopicRouteSession.STATUS_RUNNING : TopicRouteSession.STATUS_COMPLETED);
        keepOpeningRunning(session, initial);
        session.setCurrentNodeId(current); session.setRecommendedNodeId(current);
        session.setSeed(seedSupplier.getAsLong());
        session.setVersion(0); session.setCreateTime(now); session.setUpdateTime(now);
        syncTheme(session,initial); session.setStateJson(write(initial)); session.setCurrentNodeId(initial.getCurrentNodeId()); session.setRecommendedNodeId(initial.getRecommendedNodeId());
        if (session.getStatus() == TopicRouteSession.STATUS_COMPLETED) session.setCompletedAt(now);
        try {
            if (routeMapper.insertSession(session) != 1) throw new IllegalStateException("路线局创建失败");
        } catch (DuplicateKeyException duplicate) {
            TopicRouteSession raced = routeMapper.selectByOwner(scope.activityId, scope.topic.getId(),
                    scope.memberId, DEFAULT_INSTANCE);
            if (raced == null) throw duplicate;
            return raced;
        }
        if(themeBroadcastService!=null)themeBroadcastService.enqueue(session,initial);
        if (stageRest != null) recordStageRestAction(session, scope.memberId, firstNode, stageRest);
        return session;
    }

    /** 线性局快照:没有 edges(正是它区别于分支快照的判别依据),只留起终点与空变量表。 */
    private String linearSnapshot(List<CmsTopicNode> liveNodes) {
        return publicationCompiler().linearSnapshot(liveNodes);
    }

    /**
     * 线性主题的节点推进:与分支共用 state_json 与 routeActionId 幂等、version CAS,
     * 但不走 engine.advance(线性快照没有边),只做 requires 判断与 effects 写入。
     */
    private TopicRouteState advanceLinear(Scope scope, TopicRouteSession session, Integer expectedVersion,
                                          String routeActionId, NodeOutcome outcome) {
        boolean scoreActive = orientRouteSettlement != null && orientRouteSettlement.active(scope.topic);
        if (scoreActive) session = orientRouteSettlement.lock(session);
        boolean branchOpening = TopicRouteState.BRANCH_GRAPH.equals(routeMode(scope));
        if (routeActionId == null || routeActionId.trim().isEmpty() || routeActionId.length() > 96) {
            throw new IllegalArgumentException("routeActionId 无效");
        }
        TopicRouteAction replay = routeMapper.selectActionByAction(session.getId(), scope.memberId, routeActionId);
        if (replay != null) return replay(replay, scope.memberId, session.getId());
        if (expectedVersion == null) throw new IllegalArgumentException("expectedVersion 不能为空");
        String boundReceiptId = bindReceiptToVersion(outcome.getReceiptId(), expectedVersion);
        replay = routeMapper.selectActionByReceipt(session.getId(), boundReceiptId);
        if (replay != null) return replay(replay, scope.memberId, session.getId());
        if (!expectedVersion.equals(session.getVersion())) {
            List<CmsTopicNode> liveNodes = nodes(scope.topic.getId());
            throw routeConflict(branchOpening ? toAvailableView(session, liveNodes)
                    : toLinearView(session, scope, liveNodes), session);
        }
        List<CmsTopicNode> liveNodes = nodes(scope.topic.getId());
        if (!branchOpening) liveNodes = linearSessionNodes(session, liveNodes);
        Set<Long> liveNodeIds = new LinkedHashSet<Long>();
        for (CmsTopicNode liveNode : liveNodes) liveNodeIds.add(liveNode.getId());
        if (!liveNodeIds.contains(outcome.getNodeId())) throw new IllegalStateException("节点当前不可用");
        if (!hasCompleted(scope, outcome.getNodeId())) throw new IllegalStateException("节点完成回执不存在");
        if (!openingAllows(session, outcome.getNodeId())) throw new IllegalStateException("请先完成开局流程");

        CmsTopicNode completedNode = null;
        for (CmsTopicNode liveNode : liveNodes) {
            if (outcome.getNodeId().equals(liveNode.getId())) { completedNode = liveNode; break; }
        }
        JsonNode blockConfig = nodeBlockConfig(session, completedNode);
        TopicRouteEngine.State state = readEngineState(session.getStateJson());
        if (!branchOpening && (state.getNodeStates() == null || state.getNodeStates().isEmpty())) {
            Map<Long, String> fallbackStates = new LinkedHashMap<Long, String>();
            for (CmsTopicNode node : liveNodes) {
                String status = state.getCompletedNodeIds().contains(node.getId()) ? TopicRouteState.COMPLETED
                        : node.getId().equals(state.getCurrentNodeId()) ? TopicRouteState.PLAYABLE
                        : TopicRouteState.DISCOVERED_LOCKED;
                fallbackStates.put(node.getId(), status);
            }
            state.setNodeStates(fallbackStates);
        }
        JsonNode requires = blockConfig == null ? null : blockConfig.get("requires");
        if (requires != null && requires.isArray() && requires.size() > 0
                && !engine.conditionsPass(requires, state)) {
            throw new IllegalStateException("本站前置条件未满足");
        }

        TopicRouteAction action = new TopicRouteAction();
        action.setSessionId(session.getId()); action.setMemberId(scope.memberId); action.setNodeId(outcome.getNodeId());
        action.setActionId(routeActionId); action.setReceiptId(boundReceiptId);
        action.setOutcomeCode(outcome.getOutcomeCode());
        action.setResponseJson("{}"); action.setCreateTime(new Date());
        try {
            if (routeMapper.insertAction(action) != 1) throw new IllegalStateException("路线动作登记失败");
        } catch (DuplicateKeyException duplicate) {
            TopicRouteAction raced = routeMapper.selectActionByAction(session.getId(), scope.memberId, routeActionId);
            if (raced == null) raced = routeMapper.selectActionByReceipt(session.getId(), boundReceiptId);
            if (raced == null) throw duplicate;
            return replay(raced, scope.memberId, session.getId());
        }

        JourneyRules rules = journeyRules(session, scope.topic);
        refreshEnvironment(scope.topic, rules, state);
        JsonNode effects = blockConfig == null ? null : blockConfig.get("effects");
        applyJourneyEffects(effects, rules, state);
        // 站末恢复:本节点真扣过血才回;线性局同样在同一次 CAS 内落 state。
        applyStationEndRecovery(state, rules, completedNode);
        state.getCompletedNodeIds().add(outcome.getNodeId());
        state.getNodeStates().put(outcome.getNodeId(), TopicRouteState.COMPLETED);
        Long current = state.getCurrentNodeId();
        if (!branchOpening) {
            current = null;
            for (CmsTopicNode liveNode : liveNodes) {
                if (!state.getCompletedNodeIds().contains(liveNode.getId())) { current = liveNode.getId(); break; }
            }
            state.setCurrentNodeId(current); state.setRecommendedNodeId(current);
            applyStageRest(session, scope.memberId, state, rules, findNode(liveNodes, current), true);
            state.setStatus(current == null ? TopicRouteState.COMPLETED : "ACTIVE");
        }
        syncTheme(session, state, "node:" + routeActionId, JourneyStatLinks.Reason.TEMPLATE_EFFECT);
        themeEngine.signal(themeConfig(session), state, rules, "NODE", outcome.getNodeId(), "", "", 0, "node:" + outcome.getNodeId());
        String settledState = writeSettled(session, state);
        if (!branchOpening && "ACTIVE".equals(state.getStatus()) && state.getCurrentNodeId() != null) {
            state.getNodeStates().put(state.getCurrentNodeId(), TopicRouteState.PLAYABLE);
            settledState = write(state);
        }
        session.setStateJson(settledState);
        session.setStatus(statusOf(state));
        keepOpeningRunning(session, state);
        session.setCurrentNodeId(state.getCurrentNodeId()); session.setRecommendedNodeId(state.getRecommendedNodeId());
        if (session.getStatus() == TopicRouteSession.STATUS_COMPLETED) session.setCompletedAt(new Date());
        session.setUpdateTime(new Date());
        int oldVersion = session.getVersion();
        if (scoreActive) orientRouteSettlement.beforePersist(session, state);
        if (routeMapper.updateStateCas(session) != 1) throw new ServiceException("路线状态已更新，请刷新后重试", HttpStatus.CONFLICT);
        session.setVersion(oldVersion + 1);
        if (scoreActive) orientRouteSettlement.complete(session, state);
        TopicRouteState view = branchOpening ? toAvailableView(session, liveNodes) : toLinearView(session, scope, liveNodes);
        putVitals(view, session, state);
        appendEncounter(view, session);
        if (routeMapper.updateActionResponse(action.getId(), write(view)) != 1) {
            throw new IllegalStateException("路线动作回执保存失败");
        }
        return view;
    }

    /** Existing linear state already records the initial node universe and insertion order. */
    private List<CmsTopicNode> linearSessionNodes(TopicRouteSession session, List<CmsTopicNode> liveNodes) {
        Map<Long, String> frozen = readEngineState(session.getStateJson()).getNodeStates();
        if (frozen == null || frozen.isEmpty()) return liveNodes; // Pre-snapshot compatibility.
        Map<Long, CmsTopicNode> available = new LinkedHashMap<Long, CmsTopicNode>();
        for (CmsTopicNode node : liveNodes) available.put(node.getId(), node);
        List<CmsTopicNode> ordered = new ArrayList<CmsTopicNode>();
        for (Long id : frozen.keySet()) if (available.containsKey(id)) ordered.add(available.get(id));
        return ordered;
    }

    private TopicRouteState linearState(Scope scope) {
        return linearState(scope, nodes(scope.topic.getId()));
    }

    private TopicRouteState linearState(Scope scope, List<CmsTopicNode> nodes) {
        Set<Long> completed = completed(scope);
        TopicRouteState view = new TopicRouteState();
        view.setRouteMode(TopicRouteState.LINEAR); view.setVersion(0);
        Long current = null;
        Map<Long, String> states = new LinkedHashMap<Long, String>();
        for (CmsTopicNode node : nodes) {
            if (completed.contains(node.getId())) states.put(node.getId(), TopicRouteState.COMPLETED);
            else if (current == null) { current = node.getId(); states.put(node.getId(), TopicRouteState.PLAYABLE); }
            else states.put(node.getId(), TopicRouteState.DISCOVERED_LOCKED);
        }
        view.setCurrentNodeId(current); view.setRecommendedNodeId(current); view.setNodeStates(states);
        view.setStatus(current == null && !nodes.isEmpty() ? TopicRouteState.COMPLETED : "ACTIVE");
        return view;
    }

    /**
     * 线性主题的旅程局:节点推进仍以完成事实为准,只额外挂上本局的权威 version 与 sessionId,
     * 供 requires/effects 的幂等写入走同一套 CAS。
     */
    private TopicRouteState toLinearView(TopicRouteSession session, Scope scope, List<CmsTopicNode> liveNodes) {
        TopicRouteState view = linearState(scope, linearSessionNodes(session, liveNodes));
        if (themeConfig(session).isObject() || validator.readTree(session.getConfigSnapshotJson()).path("runtimeSnapshotVersion").asInt() == 1) {
            TopicRouteEngine.State stored = readEngineState(session.getStateJson());
            view.setStatus(stored.getStatus()); view.setCurrentNodeId(stored.getCurrentNodeId()); view.setRecommendedNodeId(stored.getRecommendedNodeId());
            view.setNodeStates(new LinkedHashMap<Long,String>(stored.getNodeStates()));
        }
        view.setSessionId(session.getId());
        view.setVersion(session.getVersion());
        view.setAttributes(JourneyInitialState.playerView(sessionRules(session), readEngineState(session.getStateJson())));
        appendOpening(view, session, readEngineState(session.getStateJson()));
        appendThoughts(view, session, readEngineState(session.getStateJson()));
        return view;
    }

    private TopicRouteState toView(TopicRouteSession session) { return toView(session, readEngineState(session.getStateJson())); }
    private TopicRouteState toView(TopicRouteSession session, TopicRouteEngine.State state) {
        TopicRouteState view = new TopicRouteState(); view.setRouteMode(TopicRouteState.BRANCH_GRAPH);
        view.setSessionId(session.getId()); view.setStatus(state.getStatus());
        view.setCurrentNodeId(state.getCurrentNodeId()); view.setRecommendedNodeId(state.getRecommendedNodeId());
        view.setVersion(session.getVersion()); view.setNodeStates(new LinkedHashMap<Long, String>(state.getNodeStates()));
        view.setDecisionLog(new ArrayList<Map<String, Object>>(state.getDecisionLog()));
        view.setAttributes(JourneyInitialState.playerView(sessionRules(session), state));
        appendOpening(view, session, state);
        appendThoughts(view, session, state);
        return view;
    }

    private TopicRouteState toAvailableView(TopicRouteSession session, List<CmsTopicNode> liveNodes) {
        TopicRouteState view = toView(session);
        if (!"ACTIVE".equals(view.getStatus()) || view.getCurrentNodeId() == null) return view;
        boolean currentLive = false;
        for (CmsTopicNode node : liveNodes) {
            if (view.getCurrentNodeId().equals(node.getId())) { currentLive = true; break; }
        }
        if (currentLive) return view;
        view.setStatus("UNAVAILABLE");
        view.setRecommendedNodeId(null);
        for (Map.Entry<Long, String> entry : view.getNodeStates().entrySet()) {
            if (TopicRouteState.PLAYABLE.equals(entry.getValue())) {
                entry.setValue(TopicRouteState.DISCOVERED_LOCKED);
            }
        }
        return view;
    }

    /** 同一完成事实可在合法 loop 的不同访问轮次复用；只能在同一权威版本内判为同一回执。 */
    private String bindReceiptToVersion(String serverReceiptId, Integer expectedVersion) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((serverReceiptId + "|route-version:" + expectedVersion)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) hex.append(String.format("%02x", value & 0xff));
            return "v" + expectedVersion + ":" + hex;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("路线回执绑定算法不可用", impossible);
        }
    }

    private TopicRouteState replay(TopicRouteAction action, Long memberId, Long sessionId) {
        if (!memberId.equals(action.getMemberId()) || !sessionId.equals(action.getSessionId())) {
            throw new IllegalStateException("路线动作不属于当前玩家或本局");
        }
        try { return JSON.readValue(action.getResponseJson(), TopicRouteState.class); }
        catch (Exception e) { throw new IllegalStateException("路线动作回执不可用", e); }
    }

    /** 本局适用的节点块配置:优先读建局冻结块;老局快照没有该键时回退实时模板配置。 */
    private JsonNode nodeBlockConfig(TopicRouteSession session, CmsTopicNode node) {
        JsonNode snapshot = null;
        if (session != null && session.getConfigSnapshotJson() != null) {
            try { snapshot = JSON.readTree(session.getConfigSnapshotJson()); }
            catch (Exception unusableSnapshot) { /* Preserve legacy whole-snapshot fallback. */ }
        }
        if (snapshot != null && snapshot.has(NodeJourneySnapshot.NODE_JOURNEY_KEY)) {
            JsonNode blocks = snapshot.get(NodeJourneySnapshot.NODE_JOURNEY_KEY);
            if (!blocks.isObject()) throw new IllegalStateException("节点玩法配置不可用");
            JsonNode block = node == null || node.getId() == null ? null : blocks.get(String.valueOf(node.getId()));
            if (block != null && !block.isObject()) throw new IllegalStateException("节点玩法配置不可用");
        }
        JsonNode frozen = NodeJourneySnapshot.block(
                session == null ? null : session.getConfigSnapshotJson(),
                node == null ? null : node.getId());
        return frozen == null ? nodeBlockConfig(node) : frozen;
    }

    /** 节点绑定的玩法模板 advancedConfigJson;没有绑定 / 模板没配置时返回 null。 */
    private JsonNode nodeBlockConfig(CmsTopicNode node) {
        if (templateMapper == null || node == null
                || node.getTemplateId() == null || node.getTemplateId() <= 0L) {
            return null;
        }
        CmsMemberTemplate template = templateMapper.selectCmsMemberTemplateById(node.getTemplateId());
        if (template == null || template.getAdvancedConfigJson() == null
                || template.getAdvancedConfigJson().trim().isEmpty()) {
            return null;
        }
        try {
            return JSON.readTree(template.getAdvancedConfigJson());
        } catch (Exception e) {
            throw new IllegalStateException("节点玩法配置不可用", e);
        }
    }

    private boolean hasCompleted(Scope scope, Long nodeId) {
        PlayerNodeProgress probe = progressProbe(scope); probe.setNodeId(nodeId);
        List<PlayerNodeProgress> rows = progressMapper.selectPlayerNodeProgressList(probe);
        if (rows != null) for (PlayerNodeProgress row : rows) if (Integer.valueOf(1).equals(row.getStatus())) return true;
        return false;
    }

    private Set<Long> completed(Scope scope) {
        Set<Long> ids = new LinkedHashSet<Long>();
        List<PlayerNodeProgress> rows = progressMapper.selectPlayerNodeProgressList(progressProbe(scope));
        if (rows != null) for (PlayerNodeProgress row : rows) if (Integer.valueOf(1).equals(row.getStatus())) ids.add(row.getNodeId());
        return ids;
    }

    private PlayerNodeProgress progressProbe(Scope scope) {
        PlayerNodeProgress probe = new PlayerNodeProgress(); probe.setMemberId(scope.memberId);
        probe.setActivityId(scope.activityId); probe.setTopicId(scope.topic.getId());
        probe.setPlayInstanceId(scope.originalPlayInstance == null
                ? playInstance(scope.memberId,scope.topic.getId(),scope.activityId) : scope.originalPlayInstance); return probe;
    }

    private List<CmsTopicNode> nodes(Long topicId) {
        return publicationCompiler().nodes(topicId);
    }

    private JsonNode sharedSnapshot(Long memberId, Long topicId, Long activityId, Long nodeId) {
        return gameSessionRuntimeService == null ? null : gameSessionRuntimeService.sharedRouteSnapshot(memberId, topicId, activityId, nodeId);
    }

    private Scope requireScope(Long memberId, Long topicId, Long activityId) {
        return requireScope(memberId,topicId,activityId,false);
    }
    private Scope requireScope(Long memberId, Long topicId, Long activityId,boolean newUse) {
        if (sharedSnapshot(memberId, topicId, activityId, null) != null) throw new ServiceException("队伍共享局请使用本场共享命令", 409);
        return currentScope(memberId, topicId, activityId, newUse);
    }

    /** The original scope/registration facts, without invoking the legacy shared snapshot refresh path. */
    private Scope currentScope(Long memberId, Long topicId, Long activityId, boolean newUse) {
        if (memberId == null || memberId <= 0L || topicId == null || topicId <= 0L) throw new IllegalArgumentException("参数有误");
        CmsTopic topic = topicMapper.selectCmsTopicById(topicId);
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())) throw new IllegalArgumentException("主题不存在");
        long actId = activityId == null ? 0L : activityId;
        List<CmsRegistration> current=null;
        boolean started = newUse && routeStarted(memberId,topicId,actId);
        // 新使用只认当前锁读仍有效的原报名；保留全部有效报名，原「任一成功报名可授权」候选策略不收窄为第一张。
        if(newUse) current=started
                // 已开局的路线：自玩通行证开局时有效即可打完本局；活动场次/探索票仍按活动结束等原规则。
                ? com.chengyinhub.business.service.support.CurrentPlayRegistration.requireAllToContinue(
                        activityMapper,registrationMapper,memberId,actId>0 ? 2L : 1L,actId>0 ? actId : topicId,new Date())
                : com.chengyinhub.business.service.support.CurrentPlayRegistration.requireAll(
                        activityMapper,registrationMapper,memberId,actId>0 ? 2L : 1L,actId>0 ? actId : topicId,new Date());
        CmsRegistration probe = new CmsRegistration(); probe.setMemberId(memberId); probe.setRegistrationStatus(2);
        if (actId > 0L) {
            CmsActivity activity = activityMapper.selectCmsActivityById(actId);
            if (activity == null || !topicId.equals(activity.getTopicId())) throw new IllegalArgumentException("活动与主题不匹配");
            if (activity.getCancelTime() != null) throw new IllegalStateException("活动已取消");
            if (!Long.valueOf(1L).equals(activity.getStatus())
                    || !Integer.valueOf(0).equals(activity.getDelFlag())) {
                throw new IllegalStateException("活动当前不可用");
            }
            // publishStatus 是公开目录/新购可见性；已付玩家的存量游玩权不以它为闸。
            probe.setOwnerType(2L); probe.setOwnerId(actId);
        } else { probe.setOwnerType(1L); probe.setOwnerId(topicId); }
        List<CmsRegistration> rows = registrationMapper.selectCmsRegistrationList(probe);
        if (rows == null || rows.isEmpty()) throw new IllegalStateException(actId > 0L ? "请先报名并完成支付" : "请先购买自玩通行证");
        List<CmsRegistration> currentRegistrations = new ArrayList<CmsRegistration>();
        if (actId == 0L) {
            Date now = new Date();
            for (CmsRegistration row : rows) {
                if (row.getExpiresAt() == null || row.getExpiresAt().after(now)) currentRegistrations.add(row);
            }
            if (currentRegistrations.isEmpty()) {
                // 已开局的本局(含只读状态)：开局时有效即可打完，沿用原报名作事实；未开局仍按到期拒。
                if (!started && !(!newUse && routeStarted(memberId,topicId,actId))) throw new IllegalStateException("自玩通行证已过期");
                currentRegistrations.addAll(rows);
            }
        } else currentRegistrations.addAll(rows);
        if(newUse) {
            currentRegistrations.clear();
            // 继续本局时仍以当前未到期的原报名作候选授权；仅当全部已到期才沿用开局时的通行证把本局打完，
            // 不让已到期通行证上的票种复活受限候选。
            Date nowForFacts = new Date();
            for (CmsRegistration row : current)
                if (row.getExpiresAt() == null || row.getExpiresAt().after(nowForFacts)) currentRegistrations.add(row);
            if (currentRegistrations.isEmpty()) currentRegistrations.addAll(current);
        }
        boolean hasPublishedRoute = topic.getPublishedConfigVersion() != null
                && topic.getPublishedConfigVersion() > 0L;
        if (!hasPublishedRoute
                && routeMapper.selectByOwner(actId, topicId, memberId, DEFAULT_INSTANCE) == null) {
            throw new IllegalStateException("路线尚未发布");
        }
        return new Scope(memberId, actId, topic, currentRegistrations);
    }

    /**
     * F5:把只在内存里成立的两条本局上下文挂到 state 上(不落库,JsonIgnore):
     * ROLE_IN 读的本场角色 —— 配置里没写 ROLE_IN 就不查角色表,存量主题零额外查询;
     * 奖励上限 —— 本站是自报传感 / 滤镜完成时,故事效果只放行 XP 与徽章(与开局通关发奖同一口径)。
     */
    private void bindStoryContext(TopicRouteSession session, Scope scope, JsonNode blockConfig, Long nodeId,
                                  TopicRouteEngine.State state) {
        String snapshot = session.getConfigSnapshotJson();
        String block = blockConfig == null ? "" : blockConfig.toString();
        if (usesOp(snapshot, block, StoryVocabulary.ROLE_IN) && scope.activityId > 0L && playerRoleMapper != null) {
            try {
                PlayerActivityRole role = playerRoleMapper.selectByActivityAndMember(scope.activityId, scope.memberId);
                state.setActorRoleCode(role == null ? null : role.getRoleCode());
            } catch (RuntimeException unavailableRoleSource) {
                state.setActorRoleCode(null);   // 角色源不可用 = ROLE_IN 不成立(fail-closed)
            }
        }
        // 只有会发物资的配置才需要上限;不写 ADD_ITEM 的主题不多解析一次快照。
        JsonNode answer = usesOp(snapshot, block, StoryVocabulary.ADD_ITEM) ? NodeJourneySnapshot.answer(snapshot, nodeId) : null;
        if (answer != null) {
            int method = answer.path("validationMethod").asInt();
            state.setRewardCeilingXpBadgeOnly(SensorChallengePolicy.isAppSensor(method)
                    || SensorChallengePolicy.isPhotoFilter(method, answer.path("sensorType").asText()));
        }
    }

    private static boolean usesOp(String snapshot, String block, String op) {
        String marker = "\"" + op + "\"";
        return (snapshot != null && snapshot.contains(marker)) || block.contains(marker);
    }

    private TopicRouteCandidatePolicy.Facts policyFacts(Scope scope) {
        List<Long> ticketIds = new ArrayList<Long>();
        List<Integer> purchaseKinds = new ArrayList<Integer>();
        for (CmsRegistration registration : scope.registrations) {
            ticketIds.add(registration.getTicketId());
            purchaseKinds.add(registration.getPurchaseKind());
        }
        boolean roleKnown = false; String roleCode = null;
        if (scope.activityId > 0L && playerRoleMapper != null) {
            try {
                PlayerActivityRole role = playerRoleMapper.selectByActivityAndMember(scope.activityId, scope.memberId);
                roleKnown = true; roleCode = role == null ? null : role.getRoleCode();
            } catch (RuntimeException unavailableRoleSource) {
                roleKnown = false;
            }
        }
        return new TopicRouteCandidatePolicy.Facts(ticketIds, purchaseKinds, roleKnown, roleCode);
    }

    private static RouteReachabilityProvider firstReachabilityProvider(
            ObjectProvider<RouteReachabilityProvider> providers) {
        if (providers == null) return RouteReachabilityProvider.UNKNOWN;
        return providers.orderedStream().findFirst().orElse(RouteReachabilityProvider.UNKNOWN);
    }

    private String routeMode(Scope scope) {
        if (scope.originalSession != null) return sessionRouteMode(scope.originalSession);
        TopicRouteSession existing = currentSession(scope.memberId, scope.topic.getId(), scope.activityId);
        if (existing != null) return sessionRouteMode(existing);
        return TopicRouteModePolicy.publishedMode(scope.topic);
    }

    /** 分支局快照必定带 edges(发布门禁的产物);没有边的就是线性旅程局。 */
    private String sessionRouteMode(TopicRouteSession session) {
        final JsonNode snapshot;
        try { snapshot = JSON.readTree(session.getConfigSnapshotJson()); }
        catch (Exception unusableSnapshot) { return TopicRouteState.BRANCH_GRAPH; }
        return snapshot != null && snapshot.isObject() && !snapshot.has("edges")
                ? TopicRouteState.LINEAR : TopicRouteState.BRANCH_GRAPH;
    }
    private TopicRouteEngine.State readEngineState(String json) {
        return narrativeProgress.readEngineState(json);
    }
    private String write(Object value) { try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("路线状态序列化失败", e); } }

    // ==================== R9-ACTIONS 旅程运行期动作 ====================

    /**
     * 块配置效果的统一执行入口:sys.luck 是服务端保留变量,创作者只能用
     * {@code {"op":"INC","var":"sys.luck","value":1..3}} 加幸运;执行时封顶 luck.max,
     * luck.enabled=false 时整条忽略。其余效果原样交给引擎。
     */
    private void applyJourneyEffects(JsonNode effects, JourneyRules rules, TopicRouteEngine.State state) {
        if (effects == null || !effects.isArray()) return;
        com.chengyinhub.business.service.support.TemplateEffects.apply(effects, rules, state);
        JourneyInitialState.clamp(rules, state);
    }

    private boolean isSysLuckInc(JsonNode effect) {
        return effect != null && effect.isObject()
                && "INC".equals(effect.path("op").asText(""))
                && SYS_LUCK.equals(effect.path("var").asText(""));
    }

    /**
     * 站末恢复:节点完成推进时,只在本节点真扣过血(sys.damage.{nodeId} > 0)才回
     * recovery.stationEndIfHurt,封顶 hp.max;与推进同一次 CAS。标记用掉即清,
     * 免得合法 loop 二次访问同一个节点时被上一次的血量误判。
     */
    private void applyStationEndRecovery(TopicRouteEngine.State state, JourneyRules rules, CmsTopicNode node) {
        if (node == null || node.getId() == null) return;
        if (!rules.isCheckEnabled() || rules.getHp() == null || !rules.getHp().isEnabled()) return;
        int damage = damageOf(state, node.getId());
        if (damage <= 0) return;
        state.getVariables().put(SYS_HP, Math.min(rules.getHp().getMax(),
                hpOf(state, rules) + rules.getRecovery().getStationEndIfHurt()));
        state.getVariables().remove(SYS_DAMAGE_PREFIX + node.getId());
    }

    private int damageOf(TopicRouteEngine.State state, Long nodeId) {
        Number damage = state.getVariables().get(SYS_DAMAGE_PREFIX + nodeId);
        return damage == null ? 0 : damage.intValue();
    }

    private void addDamage(TopicRouteEngine.State state, Long nodeId, int damage) {
        if (damage <= 0) return;
        String key = SYS_DAMAGE_PREFIX + nodeId;
        Number current = state.getVariables().get(key);
        state.getVariables().put(key, (current == null ? 0 : current.intValue()) + damage);
    }

    /**
     * 阶段休整:进入某个章节的第一个节点时,把 hp / luck 抬到恢复下限。
     * 幂等键 stage-rest:{chapterId} 保证一个章节在一局里只触发一次(合法 loop 回来也不重复)。
     * 建局时传 session=null:只改 state,action 行由调用方在 insertSession 之后登记。
     */
    private Map<String, Object> applyStageRest(TopicRouteSession session, Long memberId,
                                               TopicRouteEngine.State state, JourneyRules rules,
                                               CmsTopicNode node, boolean checkExisting) {
        if (state.getThemeRuntime().has("gameplay")) return null;
        if (node == null || node.getChapterId() == null || node.getChapterId() <= 0L) return null;
        if (!rules.isCheckEnabled()) return null;
        boolean persisted = session != null && session.getId() != null;
        String actionId = "stage-rest:" + node.getChapterId();
        if (checkExisting && persisted
                && routeMapper.selectActionByAction(session.getId(), memberId, actionId) != null) return null;
        boolean touched = false;
        if (rules.getHp() != null && rules.getHp().isEnabled()) {
            state.getVariables().put(SYS_HP,
                    Math.max(hpOf(state, rules), rules.getRecovery().getStageRestHpFloor()));
            touched = true;
        }
        if (rules.getLuck() != null && rules.getLuck().isEnabled()) {
            state.getVariables().put(SYS_LUCK,
                    Math.max(luckOf(state, rules), rules.getRecovery().getStageRestLuckFloor()));
            touched = true;
        }
        if (!touched) return null;
        Map<String, Object> receipt = new LinkedHashMap<String, Object>();
        receipt.put("chapterId", node.getChapterId());
        receipt.put("hp", hpOf(state, rules));
        receipt.put("luck", luckOf(state, rules));
        if (persisted) recordStageRestAction(session, memberId, node, receipt);
        return receipt;
    }

    private void recordStageRestAction(TopicRouteSession session, Long memberId, CmsTopicNode node,
                                       Map<String, Object> receipt) {
        TopicRouteAction action = new TopicRouteAction();
        action.setSessionId(session.getId()); action.setMemberId(memberId); action.setNodeId(node.getId());
        action.setActionId("stage-rest:" + node.getChapterId());
        action.setReceiptId(action.getActionId());
        action.setOutcomeCode("STAGE_REST");
        action.setResponseJson(write(receipt)); action.setCreateTime(new Date());
        try {
            if (routeMapper.insertAction(action) != 1) throw new IllegalStateException("阶段休整登记失败");
        } catch (DuplicateKeyException duplicate) {
            // R22-SEC S9:并发进入同一章节时两边都会生成 stage-rest:{chapterId},唯一键只能成一条。
            // 与 recordMistake 同口径:确认赢家已落库就吞掉,不把原始重复键异常抛给玩家;
            // 状态值取的是恢复下限(幂等),不重复写动作行。
            if (routeMapper.selectActionByAction(session.getId(), memberId, action.getActionId()) == null) {
                throw duplicate;
            }
        }
    }

    /**
     * 玩法判错:sys.mistakes.{nodeId} +1;每个节点第一次免罚,之后每次按玩法块的
     * {@code mistakeTier} 扣对应难度档的血(下限 0,到 0 标记力竭)。档位缺省 easy,
     * easy 的 damage 默认 1 —— 与并入难度表前的固定 -1 逐字等价。
     * 动作 id mistake:{nodeId}:{该节点判错序号},同 id 重放不改状态。
     * 非旅程主题(没有本局 / 检定未启用 / hp 未启用)零变化。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void recordMistake(Long memberId, Long topicId, Long activityId, Long nodeId) {
        recordMistake(memberId, topicId, activityId, nodeId, null);
    }

    private void recordMistake(Long memberId, Long topicId, Long activityId, Long nodeId, TopicRouteSession targetSession) {
        if (memberId == null || memberId <= 0L || topicId == null || topicId <= 0L
                || nodeId == null || nodeId <= 0L) return;
        TopicRouteSession session = factSession(memberId, topicId, activityId, targetSession);
        if (genericPlayer(session)) throw new ServiceException("通用个人局请使用任务事实桥",409);
        if (session == null
                || !Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus())) return;
        // R22-SEC S11:只认本主题节点(与 recordChoice 同口径),否则能往任意 topic 的局里
        // 写 sys.mistakes.{外来 nodeId},甚至把别的主题的节点算进本局力竭。
        CmsTopicNode node = nodeMapper.selectCmsTopicNodeById(nodeId);
        if (node == null || !topicId.equals(node.getTopicId())) return;
        JourneyRules rules = journeyRules(session, topicMapper.selectCmsTopicById(topicId));
        if (!rules.isCheckEnabled() || rules.getHp() == null || !rules.getHp().isEnabled()) return;
        String mistakeTier = mistakeTierOf(session, node, rules);
        TopicRouteEngine.State state = readEngineState(session.getStateJson());
        int next = mistakesOf(state, nodeId) + 1;
        String actionId = "mistake:" + nodeId + ":" + next;
        if (routeMapper.selectActionByAction(session.getId(), memberId, actionId) != null) return;   // 同 id 重放
        int damage = 0;
        if (next > 1) {
            int before = hpOf(state, rules);
            int hp = Math.max(0, before - themeEngine.reduceHpCost(themeConfig(session),state,tierOf(rules, mistakeTier).getDamage()));
            state.getVariables().put(SYS_HP, hp);
            damage = before - hp;
            addDamage(state, nodeId, damage);
            if (hp == 0) state.getVariables().put(SYS_EXHAUSTED, 1);
        }
        state.getVariables().put(SYS_MISTAKES_PREFIX + nodeId, next);
        Map<String, Object> receipt = new LinkedHashMap<String, Object>();
        receipt.put("nodeId", nodeId); receipt.put("mistakes", next); receipt.put("damage", damage);
        receipt.put("tier", mistakeTier);
        receipt.put("hp", hpOf(state, rules)); receipt.put("exhausted", isExhausted(state));
        TopicRouteAction action = new TopicRouteAction();
        action.setSessionId(session.getId()); action.setMemberId(memberId); action.setNodeId(nodeId);
        action.setActionId(actionId); action.setReceiptId(actionId); action.setOutcomeCode("MISTAKE");
        action.setResponseJson(write(receipt)); action.setCreateTime(new Date());
        try {
            if (routeMapper.insertAction(action) != 1) throw new IllegalStateException("判错动作登记失败");
        } catch (DuplicateKeyException duplicate) {
            if (routeMapper.selectActionByAction(session.getId(), memberId, actionId) == null) throw duplicate;
            return;             // 并发同序号已被别人登记:不二次扣血,由版本 CAS 决定谁生效
        }
        syncTheme(session, state);
        session.setStateJson(writeSettled(session, state));
        session.setUpdateTime(new Date());
        if (routeMapper.updateStateCas(session) != 1) {
            throw new ServiceException("路线状态已更新，请刷新后重试", HttpStatus.CONFLICT);
        }
    }

    private int mistakesOf(TopicRouteEngine.State state, Long nodeId) {
        Number count = state.getVariables().get(SYS_MISTAKES_PREFIX + nodeId);
        return count == null ? 0 : count.intValue();
    }

    /**
     * 判定型玩法成败写本局状态(阶段 1 / 链路 D):sys.passed.{nodeId} = 1 通过 / 0 失败。
     * 动作 id {@code passed:{actionId}} 同 id 重放不二次写,换 actionId 重答则覆盖旧值(最后一次为准)。
     * 非旅程主题(无本局 / 已结束)、外来 nodeId 一律 no-op。
     *
     * <p>⚠️ gate 在 checkEnabled 上(与 recordMistake / applyStationEndRecovery 同口径),
     * 这是 hp.enabled 之外的那一半:成败是叙事事实、不吃 hp,但主题没开数值规则时整条链路 D
     * 没有消费方(requires / mods / 叙事 when 都属检定体系)。不 gate 的代价不是多写一个变量,
     * 而是给这类主题的每次判定型提交**新增一条写路线局的路径**:多一行 action、多一轮 CAS,
     * 撞上 CAS 就 {@code CONFLICT},而 act() 是 @Transactional —— 玩家这次答题会整体回滚成 409。
     * 存量主题多数没配 journeyRules(即 checkEnabled=false),所以这一闸就是「零行为变化」本身。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void recordPassed(Long memberId, Long topicId, Long activityId, Long nodeId, String actionId, boolean passed) {
        recordPassed(memberId, topicId, activityId, nodeId, actionId, passed, null);
    }

    private void recordPassed(Long memberId, Long topicId, Long activityId, Long nodeId, String actionId, boolean passed, TopicRouteSession targetSession) {
        Map<String, Object> receipt = new LinkedHashMap<String, Object>();
        receipt.put("nodeId", nodeId); receipt.put("passed", passed);
        recordFact(memberId, topicId, activityId, nodeId, actionId, "passed:", "PASSED",
                SYS_PASSED_PREFIX, Integer.valueOf(passed ? 1 : 0), receipt, targetSession);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void recordOutcome(Long memberId, Long topicId, Long activityId, Long nodeId, String actionId, int outcome) {
        recordOutcome(memberId, topicId, activityId, nodeId, actionId, outcome, null);
    }

    private void recordOutcome(Long memberId, Long topicId, Long activityId, Long nodeId, String actionId, int outcome, TopicRouteSession targetSession) {
        if (outcome <= 0) return;   // 序号从 1 起;0 / 负数 = 调用方没算出结果,不写
        Map<String, Object> receipt = new LinkedHashMap<String, Object>();
        receipt.put("nodeId", nodeId); receipt.put("outcome", outcome);
        recordFact(memberId, topicId, activityId, nodeId, actionId, "outcome:", "OUTCOME",
                SYS_OUTCOME_PREFIX, Integer.valueOf(outcome), receipt, targetSession);
    }

    /**
     * 玩法事实写本局(recordPassed / recordOutcome 共用):gate、外来节点、幂等、CAS 一处实现。
     * 两者只差写哪个 sys 键、动作 id 前缀和回执 —— 各抄一份的话,改一边的闸另一边就漏了。
     */
    /** Internal composite results retain the verified instance; legacy public fact methods keep their scope. */
    private TopicRouteSession factSession(Long memberId, Long topicId, Long activityId,
                                          TopicRouteSession targetSession) {
        if (targetSession == null) return TopicRouteSessionScope.find(routeMapper, memberId, topicId, activityId);
        TopicRouteSession session = routeMapper.selectAccessibleById(targetSession.getId(), memberId);
        if (session == null || !memberId.equals(session.getMemberId()) || !topicId.equals(session.getTopicId())
                || !java.util.Objects.equals(targetSession.getActivityId(), session.getActivityId())
                || !java.util.Objects.equals(targetSession.getPlayInstanceId(), session.getPlayInstanceId())) {
            throw new ServiceException("本局已更新，请刷新", 409);
        }
        return session;
    }

    private void recordFact(Long memberId, Long topicId, Long activityId, Long nodeId, String actionId, String actionPrefix,
                            String outcomeCode, String varPrefix, Integer value, Map<String, Object> receipt,
                            TopicRouteSession targetSession) {
        if (memberId == null || memberId <= 0L || topicId == null || topicId <= 0L
                || nodeId == null || nodeId <= 0L) return;
        String key = actionId == null ? "" : actionId.trim();
        if (key.isEmpty()) return;
        TopicRouteSession session = factSession(memberId, topicId, activityId, targetSession);
        if (genericPlayer(session)) throw new ServiceException("通用个人局请使用任务事实桥",409);
        if (session == null
                || !Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus())) return;
        // 只认本主题节点(与 recordMistake / recordChoice 同口径),否则能往别的主题的局里写成败。
        CmsTopicNode node = nodeMapper.selectCmsTopicNodeById(nodeId);
        if (node == null || !topicId.equals(node.getTopicId())) return;
        // 主题没开检定 = 链路 D 没有消费方:一个字都不写,别给这局新增一条 CAS 路径(见方法注释)。
        if (!journeyRules(session, topicMapper.selectCmsTopicById(topicId)).isCheckEnabled() && !themeConfig(session).isObject()) return;
        String routeActionId = actionPrefix + key;
        if (routeMapper.selectActionByAction(session.getId(), memberId, routeActionId) != null) return;   // 同 id 重放

        TopicRouteEngine.State state = readEngineState(session.getStateJson());
        state.getVariables().put(varPrefix + nodeId, value);
        if ("OUTCOME".equals(outcomeCode)) themeEngine.signal(themeConfig(session), state, journeyRules(session, topicMapper.selectCmsTopicById(topicId)), "DRAW", nodeId, "", "", value, routeActionId);
        TopicRouteAction action = new TopicRouteAction();
        action.setSessionId(session.getId()); action.setMemberId(memberId); action.setNodeId(nodeId);
        action.setActionId(routeActionId); action.setReceiptId(routeActionId); action.setOutcomeCode(outcomeCode);
        action.setResponseJson(write(receipt)); action.setCreateTime(new Date());
        try {
            if (routeMapper.insertAction(action) != 1) throw new IllegalStateException("玩法事实登记失败");
        } catch (DuplicateKeyException duplicate) {
            if (routeMapper.selectActionByAction(session.getId(), memberId, routeActionId) == null) throw duplicate;
            return;             // 并发同 id 已被别人登记:不二次写,由版本 CAS 决定谁生效
        }
        syncTheme(session, state);
        session.setStateJson(writeSettled(session, state));
        session.setUpdateTime(new Date());
        if (routeMapper.updateStateCas(session) != 1) {
            throw new ServiceException("路线状态已更新，请刷新后重试", HttpStatus.CONFLICT);
        }
    }

    /**
     * 本局这一节点的判错档位:读玩法块根层的 {@code mistakeTier}(建局冻结优先,老局回退实时模板)。
     * 没配 / 非三档字面量一律落回 easy —— 发布校验已挡住坏值,这里只兜存量老配置的底。
     */
    private String mistakeTierOf(TopicRouteSession session, CmsTopicNode node, JourneyRules rules) {
        String tier = textField(nodeBlockConfig(session, node), "mistakeTier");
        return tierOf(rules, tier) == null ? "easy" : tier.trim();
    }

    /**
     * R14 选择结果当场写状态:分支选项 / 选项问答选项 / 五个通过类玩法(排序·连线·分类·找东西·猜图)。
     *
     * <p>effects 从**本局建局冻结的节点块配置**里取(与节点完成 effects 同一份口径),不信任调用方
     * 传进来的 JSON;分支玩法额外写 sys.outcome.{nodeId}=选项序号(1 起),供结局 dispositionNodeId
     * 引用。动作 id 由服务端生成,登记后同 id 重放直接返回 —— 重放不重复写。
     *
     * <p>非旅程主题(没有本局 / 本局已结束)、没配 effects 与序号的块一律 no-op,行为零变化。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void recordChoice(Long memberId, Long topicId, Long activityId, Long nodeId, String stepOrKit, String optionId) {
        recordChoice(memberId, topicId, activityId, nodeId, stepOrKit, optionId, null);
    }

    private void recordChoice(Long memberId, Long topicId, Long activityId, Long nodeId, String stepOrKit, String optionId, TopicRouteSession targetSession) {
        if (memberId == null || memberId <= 0L || topicId == null || topicId <= 0L
                || nodeId == null || nodeId <= 0L) return;
        // stepOrKit 是分支步骤 id 或玩法段名,两种都由下面的配置查找定夺,这里不预筛。
        String kit = stepOrKit == null ? "" : stepOrKit.trim();
        if (kit.isEmpty()) return;
        TopicRouteSession session = factSession(memberId, topicId, activityId, targetSession);
        if (genericPlayer(session)) throw new ServiceException("通用个人局请使用任务事实桥",409);
        if (session == null
                || !Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus())) return;
        CmsTopicNode node = nodeMapper.selectCmsTopicNodeById(nodeId);
        if (node == null || !topicId.equals(node.getTopicId())) return;
        JsonNode blockConfig = choiceBlockConfig(session, node);
        String chosen = optionId == null ? "" : optionId.trim();
        ChoiceEffects choice = branchChoice(blockConfig, kit, chosen);
        if (choice == null && NodeJourneySnapshot.CHOICE_KITS.contains(kit)) {
            choice = kitChoice(blockConfig, kit, chosen);
        }
        if(choice==null && themeConfig(session).isObject() && blockConfig!=null) {
            JsonNode section=blockConfig.path(kit);
            boolean valid="qa".equals(kit)?findRowById(section.path("options"),chosen,null)!=null:
                    (!"profile".equals(kit) && section.isObject());
            if("profile".equals(kit))for(JsonNode question:section.path("questions"))if(findRowById(question.path("options"),chosen,null)!=null)valid=true;
            if(valid)choice=new ChoiceEffects(null,0);
        }
        if (choice == null) return;
        String actionId = choiceActionId(nodeId, kit, chosen);
        if (routeMapper.selectActionByAction(session.getId(), memberId, actionId) != null) return;   // 同 id 重放

        TopicRouteEngine.State state = readEngineState(session.getStateJson());
        applyJourneyEffects(choice.effects, journeyRules(session, topicMapper.selectCmsTopicById(topicId)), state);
        Map<String, Object> receipt = new LinkedHashMap<String, Object>();
        receipt.put("nodeId", nodeId);
        receipt.put("kit", kit);
        if (!chosen.isEmpty()) receipt.put("optionId", chosen);
        if (choice.ordinal > 0) {
            state.getVariables().put(SYS_OUTCOME_PREFIX + nodeId, Integer.valueOf(choice.ordinal));
            receipt.put("index", Integer.valueOf(choice.ordinal));
        }
        themeEngine.signal(themeConfig(session), state, journeyRules(session, topicMapper.selectCmsTopicById(topicId)), "CHOICE", nodeId, kit, chosen, 0, actionId);
        commitJourneyAction(session, nodeId, actionId, "CHOICE", receipt, state);
    }

    /**
     * 选择动作 id:契约格式 {@code choice:{nodeId}:{stepOrKit}:{optionId|pass}};step / option id
     * 各可到 64 字,拼起来可能超 topic_route_action.action_id 的 96 位 —— 超了就用整串的稳定
     * 摘要收尾(同一输入恒定同一 id,重放幂等不受影响)。
     */
    private String choiceActionId(Long nodeId, String kit, String chosen) {
        String raw = "choice:" + nodeId + ":" + kit + ":" + (chosen.isEmpty() ? "pass" : chosen);
        if (raw.length() <= 96) return raw;
        return "choice:" + nodeId + ":" + shortHash(raw);
    }

    /** 稳定短摘要:整串 SHA-256 前 16 字节的十六进制(32 位)。 */
    private String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 16; i++) hex.append(String.format("%02x", hash[i] & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("选择动作摘要算法不可用", impossible);
        }
    }

    /**
     * R14 选择结果读的块配置:建局冻结块里的 choiceEffects 投影(与 requires / effects 同一份
     * 冻结口径,创作者之后改模板不影响进行中的局)。老快照没有这个键 = 这一局没有当场写状态
     * 的配置,返回 null(不回退实时配置,免得开局后的改动穿透进行中的局)。
     */
    private JsonNode choiceBlockConfig(TopicRouteSession session, CmsTopicNode node) {
        JsonNode frozen = NodeJourneySnapshot.block(session.getConfigSnapshotJson(), node.getId());
        JsonNode view = frozen == null ? null : frozen.get(NodeJourneySnapshot.CHOICE_EFFECTS_KEY);
        return view != null && view.isObject() ? view : null;
    }

    /**
     * 分支选择:stepOrKit 是步骤 id,optionId 是选项 id;序号 = 选项在 steps[].options 里的位置(1 起)。
     * 找不到步骤 / 选项(不是分支动作)返回 null。
     */
    private ChoiceEffects branchChoice(JsonNode blockConfig, String stepId, String optionId) {
        if (optionId.isEmpty() || blockConfig == null || !blockConfig.isObject()) return null;
        JsonNode options = findRowById(blockConfig.path("branch").path("steps"), stepId, "options");
        if (options == null || !options.isArray()) return null;
        for (int i = 0; i < options.size(); i++) {
            JsonNode option = options.get(i);
            if (option != null && option.isObject() && optionId.equals(option.path("id").asText(""))) {
                return new ChoiceEffects(option.get("effects"), i + 1);
            }
        }
        return null;
    }

    /**
     * 玩法选择:qa 与 profile 的选项级 effects(必须有 optionId),其余玩法段级 effects(通过时执行)。
     * 没配 effects 一律返回 null —— 不产生动作行、不顶版本,与「没配 = no-op」的口径一致。
     */
    private ChoiceEffects kitChoice(JsonNode blockConfig, String kit, String optionId) {
        if (blockConfig == null || !blockConfig.isObject()) return null;
        if ("qa".equals(kit)) {
            if (optionId.isEmpty()) return null;
            JsonNode option = findRowById(blockConfig.path("qa").path("options"), optionId, null);
            if (option == null) return null;
            JsonNode effects = effectiveEffects(option.get("effects"));
            return effects == null ? null : new ChoiceEffects(effects, 0);
        }
        if ("profile".equals(kit)) {
            // 建档:选项在 questions[].options[] 第二层,冻结投影里选项 id 已按 key 表达
            if (optionId.isEmpty()) return null;
            for (JsonNode question : blockConfig.path("profile").path("questions")) {
                JsonNode option = findRowById(question.path("options"), optionId, null);
                if (option == null) continue;
                JsonNode effects = effectiveEffects(option.get("effects"));
                return effects == null ? null : new ChoiceEffects(effects, 0);
            }
            return null;
        }
        JsonNode section = blockConfig.get(kit);
        if (section == null || !section.isObject()) return null;
        JsonNode effects = effectiveEffects(section.get("effects"));
        return effects == null ? null : new ChoiceEffects(effects, 0);
    }

    /** 非空 effects 数组才值得写;空数组 / null 一律按「没配」算。 */
    private static JsonNode effectiveEffects(JsonNode effects) {
        return effects != null && effects.isArray() && effects.size() > 0 ? effects : null;
    }

    /** 在 rows 里按 id 找一行;field 非空时返回该行里的这个字段(找不到返回 null)。 */
    private JsonNode findRowById(JsonNode rows, String id, String field) {
        if (rows == null || !rows.isArray() || id == null) return null;
        String wanted = id.trim();
        for (JsonNode row : rows) {
            if (row != null && row.isObject() && wanted.equals(row.path("id").asText(""))) {
                return field == null ? row : row.get(field);
            }
        }
        return null;
    }

    /** 一次选择对应的可写内容:effects 可空(分支只写序号时),序号 0 = 不写 sys.outcome。 */
    private static final class ChoiceEffects {
        private final JsonNode effects;
        private final int ordinal;

        private ChoiceEffects(JsonNode effects, int ordinal) {
            this.effects = effects;
            this.ordinal = ordinal;
        }
    }

    /** 问问题:须已到店、问题在该节点 enter.questions 内;写 sys.asked.{nodeId}.{questionId}=1,回执带 a。 */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Map<String, Object> ask(Long memberId, Long topicId, Long activityId, Long nodeId, String questionId) {
        if (nodeId == null || nodeId <= 0L) throw new IllegalArgumentException("nodeId 无效");
        String qid = questionId == null ? "" : questionId.trim();
        if (!NARRATIVE_ID.matcher(qid).matches()) throw new IllegalArgumentException("questionId 无效");
        TopicRouteSession session = requireCheckSession(memberId, topicId, activityId);
        String actionId = "ask:" + nodeId + ":" + qid;
        if (actionId.length() > 96) throw new IllegalArgumentException("questionId 过长");
        Map<String, Object> replayed = replayJourneyAction(session, memberId, actionId);
        if (replayed != null) return replayed;
        CmsTopicNode node = nodeMapper.selectCmsTopicNodeById(nodeId);
        if (node == null || !topicId.equals(node.getTopicId())) throw new IllegalStateException("节点不存在");
        if (shopDayProofService == null
                || !shopDayProofService.hasArrived(memberId, session.getActivityId(), nodeId)) {
            throw new IllegalStateException("请先扫码确认到店");
        }
        JsonNode question = findQuestion(nodeBlockConfig(session, node), qid);
        if (question == null) throw new IllegalStateException("这一站没有这个问题");
        TopicRouteEngine.State state = readEngineState(session.getStateJson());
        state.getVariables().put(SYS_ASKED_PREFIX + nodeId + "." + qid, 1);
        Map<String, Object> receipt = new LinkedHashMap<String, Object>();
        receipt.put("nodeId", nodeId); receipt.put("questionId", qid);
        receipt.put("q", textField(question, "q")); receipt.put("a", textField(question, "a"));
        putVitals(receipt, session, state);
        return commitJourneyAction(session, nodeId, actionId, "ASK", receipt, state);
    }

    /** 回应开场通话:选项须在 journey_story_json.call.options 内;写 sys.call=选项序号;只允许一次。 */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Map<String, Object> call(Long memberId, Long topicId, Long activityId, String optionId) {
        String option = optionId == null ? "" : optionId.trim();
        if (!NARRATIVE_ID.matcher(option).matches()) throw new IllegalArgumentException("optionId 无效");
        TopicRouteSession session = requireCheckSession(memberId, topicId, activityId);
        Map<String, Object> replayed = replayJourneyAction(session, memberId, "call");
        if (replayed != null) return replayed;
        JsonNode story = journeyStory(session, topicId);
        JsonNode callConfig = story == null ? null : story.get("call");
        JsonNode options = callConfig == null ? null : callConfig.path("options");
        JsonNode picked = null;
        int index = 0;
        if (options != null && options.isArray()) {
            for (int i = 0; i < options.size(); i++) {
                if (option.equals(options.get(i).path("id").asText(""))) {
                    picked = options.get(i); index = i + 1; break;
                }
            }
        }
        if (picked == null) throw new IllegalStateException("开场通话没有这个选项");
        TopicRouteEngine.State state = readEngineState(session.getStateJson());
        state.getVariables().put(SYS_CALL, index);
        Map<String, Object> receipt = new LinkedHashMap<String, Object>();
        receipt.put("optionId", option); receipt.put("index", index);
        receipt.put("line", textField(picked, "line"));
        receipt.put("reply", textField(picked, "reply"));
        receipt.put("brief", textField(callConfig, "brief"));
        putVitals(receipt, session, state);
        Long nodeId = session.getCurrentNodeId() == null ? 0L : session.getCurrentNodeId();
        return commitJourneyAction(session, nodeId, "call", "CALL", receipt, state);
    }

    /**
     * 开场通话读主题级 journey_story_json:优先建局冻结值(R11-A),老快照没有冻结键时
     * 才窄读实时列(不把新列扩散进全部主题查询)。
     */
    private JsonNode journeyStory(TopicRouteSession session, Long topicId) {
        String frozen = NodeJourneySnapshot.journeyStory(
                session == null ? null : session.getConfigSnapshotJson());
        String raw = frozen != null ? frozen : topicMapper.selectJourneyStoryJsonById(topicId);
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return JSON.readTree(raw);
        } catch (Exception unusableStory) {
            return null;
        }
    }

    private JsonNode findQuestion(JsonNode blockConfig, String questionId) {
        JsonNode questions = blockConfig == null ? null : blockConfig.path("enter").path("questions");
        if (questions == null || !questions.isArray()) return null;
        for (JsonNode question : questions) {
            if (questionId.equals(question.path("id").asText(""))) return question;
        }
        return null;
    }

    private CmsTopicNode findNode(List<CmsTopicNode> nodes, Long nodeId) {
        if (nodeId == null) return null;
        for (CmsTopicNode node : nodes) if (nodeId.equals(node.getId())) return node;
        return null;
    }

    /** 旅程动作的版本 CAS 与回执登记同一次完成;同 actionId 重放原回执,不二次写状态。 */
    private Map<String, Object> commitJourneyAction(TopicRouteSession session, Long nodeId, String actionId,
            String outcomeCode, Map<String, Object> receipt, TopicRouteEngine.State state) {
        boolean scoreActive = orientRouteSettlement != null
                && orientRouteSettlement.active(topicMapper.selectCmsTopicById(session.getTopicId()));
        if (scoreActive) {
            TopicRouteSession locked = orientRouteSettlement.lock(session);
            if (!java.util.Objects.equals(locked.getVersion(), session.getVersion())) {
                Map<String, Object> raced = replayJourneyAction(session, session.getMemberId(), actionId);
                if (raced != null) return raced;
                throw new ServiceException("路线状态已更新，请刷新后重试", HttpStatus.CONFLICT);
            }
        }
        receipt.put("stateVersion", (session.getVersion() == null ? 0 : session.getVersion()) + 1);
        TopicRouteAction action = new TopicRouteAction();
        action.setSessionId(session.getId()); action.setMemberId(session.getMemberId()); action.setNodeId(nodeId);
        action.setActionId(actionId); action.setReceiptId(actionId); action.setOutcomeCode(outcomeCode);
        action.setResponseJson(write(receipt)); action.setCreateTime(new Date());
        try {
            if (routeMapper.insertAction(action) != 1) throw new IllegalStateException("旅程动作登记失败");
        } catch (DuplicateKeyException duplicate) {
            Map<String, Object> raced = replayJourneyAction(session, session.getMemberId(), actionId);
            if (raced == null) throw duplicate;
            return raced;
        }
        syncTheme(session, state, "journey:" + actionId, "CHOICE".equals(outcomeCode) ? JourneyStatLinks.Reason.TEMPLATE_EFFECT : null);
        session.setStateJson(writeSettled(session, state));
        if (session.getStatus() == null) session.setStatus(TopicRouteSession.STATUS_RUNNING);
        session.setCurrentNodeId(state.getCurrentNodeId());
        session.setRecommendedNodeId(state.getRecommendedNodeId());
        session.setUpdateTime(new Date());
        if (scoreActive) orientRouteSettlement.beforePersist(session, state);
        if (routeMapper.updateStateCas(session) != 1) {
            throw new ServiceException("路线状态已更新，请刷新后重试", HttpStatus.CONFLICT);
        }
        if (scoreActive) orientRouteSettlement.complete(session, state);
        // 结算可能授予数值道具；首次返回与原 actionId 重放都使用最终已写状态。
        putVitals(receipt, session, state);
        if (!sessionRules(session).getStatLinks().isEmpty()) {
            receipt.put("statLinkEvents", themeEngine.view(themeConfig(session),state,sessionRules(session)).path("statLinkEvents"));
        }
        Map<String, Object> encounter = encounterView(session, nodeId);
        if (encounter != null) receipt.put("encounter", encounter);
        if (routeMapper.updateActionResponse(action.getId(), write(receipt)) != 1) {
            throw new IllegalStateException("旅程动作回执保存失败");
        }
        return receipt;
    }

    /**
     * M1 回执自带新世界:解析这一站刷新后的 Encounter(组装全部在 {@link IPlayEncounterService})。
     * 可选依赖缺失或解析不可用(节点没配旅程块/局已收尾)时返回 null —— 动作本身已成立,回执缺省该字段。
     */
    private Map<String, Object> encounterView(TopicRouteSession session, Long nodeId) {
        if (playEncounterService == null || session == null || nodeId == null || nodeId <= 0L) return null;
        try {
            return playEncounterService.resolve(session.getMemberId(), session.getTopicId(), session.getActivityId(), nodeId);
        } catch (ServiceException unusable) {
            return null;
        }
    }

    /** 推进回执的 Encounter:优先推荐节点,没有再取当前节点;没有可解析的节点就缺省。 */
    private void appendEncounter(TopicRouteState view, TopicRouteSession session) {
        if (view == null) return;
        Long nodeId = view.getRecommendedNodeId() != null ? view.getRecommendedNodeId() : view.getCurrentNodeId();
        Map<String, Object> encounter = encounterView(session, nodeId);
        if (encounter != null) view.setEncounter(encounter);
    }

    /** 旧 expectedVersion 被拒时,data 自带当前 routeState(含本局 version 与 encounter),免客户端再拉一次。 */
    private ServiceException routeConflict(TopicRouteState current, TopicRouteSession session) {
        appendEncounter(current, session);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("routeState", current);
        return new ServiceException("路线状态已更新，请刷新后重试", HttpStatus.CONFLICT).setData(data);
    }

    private Map<String, Object> replayJourneyAction(TopicRouteSession session, Long memberId, String actionId) {
        TopicRouteAction action = routeMapper.selectActionByAction(session.getId(), memberId, actionId);
        if (action == null) return null;
        try {
            return JSON.readValue(action.getResponseJson(), new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("旅程动作回执不可用", e);
        }
    }

    // ==================== B5 检定:本局定位、回执与随机 ====================

    /** 检定与动作按会员、主题和活动精确定位本局。 */
    private TopicRouteSession requireCheckSession(Long memberId, Long topicId, Long activityId) {
        return requireCheckSession(memberId, topicId, activityId, null);
    }

    private TopicRouteSession requireCheckSession(Long memberId, Long topicId, Long activityId, Long expectedSessionId) {
        if (memberId == null || memberId <= 0L || topicId == null || topicId <= 0L) {
            throw new IllegalArgumentException("参数有误");
        }
        TopicRouteSession session = expectedSessionId == null ? currentScopedSession(memberId, topicId, activityId)
                : routeMapper.selectAccessibleById(expectedSessionId, memberId);
        if (session != null && (!topicId.equals(session.getTopicId())
                || activityId != null && !activityId.equals(session.getActivityId()))) {
            throw new IllegalArgumentException("原局作用域不匹配");
        }
        if (session == null) throw new IllegalStateException("本局尚未开始");
        requireScope(memberId, topicId, session.getActivityId(),true);
        if (genericPlayer(session)) throw new ServiceException("通用个人局请使用任务动作", 409);
        if (session.getStatus() != null
                && !Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus())) {
            throw new IllegalStateException("本局已结束");
        }
        return session;
    }

    /** 检定前置与配置解析:节点启用 check、checkId 对齐、主题启用检定、档位可识别。 */
    private CheckContext checkContext(Long memberId, Long topicId, TopicRouteSession session, Long nodeId,
                                      String checkId) {
        Scope scope = requireScope(memberId, topicId, session.getActivityId(),true);
        CmsTopicNode node = nodeMapper.selectCmsTopicNodeById(nodeId);
        if (node == null || !topicId.equals(node.getTopicId())) throw new IllegalStateException("节点不存在");
        JsonNode blockConfig = nodeBlockConfig(session, node);
        JsonNode check = blockConfig == null ? null : blockConfig.get("check");
        if (check == null || !check.isObject() || !check.path("enabled").asBoolean(false)) {
            throw new IllegalStateException("该节点未启用检定");
        }
        if (!checkId.trim().equals(check.path("checkId").asText(""))) {
            throw new IllegalStateException("检定标识不匹配");
        }
        JourneyRules rules = journeyRules(session, scope.topic);
        if (!rules.isCheckEnabled()) throw new IllegalStateException("本主题未启用检定");
        CheckContext context = new CheckContext();
        context.session = session; context.node = node; context.blockConfig = blockConfig;
        context.check = check; context.rules = rules;
        context.state = readEngineState(session.getStateJson());
        refreshEnvironment(scope.topic, rules, context.state);
        return context;
    }

    private String checkActionId(Long nodeId, String checkId, String step) {
        return JourneyCheckActionId.of(nodeId, checkId, step);
    }

    private TopicRouteAction findCheckAction(TopicRouteSession session, Long memberId, Long nodeId,
                                             String checkId, String step) {
        return routeMapper.selectActionByAction(session.getId(), memberId,
                checkActionId(nodeId, checkId, step));
    }

    private PlayCheckReceipt replayCheck(TopicRouteSession session, Long memberId, String actionId) {
        TopicRouteAction action = routeMapper.selectActionByAction(session.getId(), memberId, actionId);
        if (action == null) return null;
        PlayCheckReceipt receipt = readReceipt(action);
        receipt.setSessionId(String.valueOf(session.getId()));
        receipt.setNodeId(String.valueOf(action.getNodeId()));
        receipt.setActionId(actionId);
        return receipt;
    }

    @Override
    @Transactional(readOnly = true)
    public PlayCheckReceipt checkReceipt(Long memberId, Long sessionId, Long nodeId, String checkId, String step) {
        if (memberId == null || memberId <= 0 || sessionId == null || sessionId <= 0
                || !java.util.Set.of("roll", "reroll", "settle").contains(step == null ? "" : step)) {
            throw new IllegalArgumentException("原检定参数有误");
        }
        String actionId = JourneyCheckActionId.of(nodeId, checkId, step);
        TopicRouteSession session = routeMapper.selectAccessibleById(sessionId, memberId);
        if (session == null) throw new ServiceException("原局不存在或无权读取", 404);
        PlayCheckReceipt receipt = replayCheck(session, memberId, actionId);
        if (receipt == null) throw new ServiceException("尚未查到原检定回执", 404);
        return receipt;
    }

    private PlayCheckReceipt readReceipt(TopicRouteAction action) {
        try { return JSON.readValue(action.getResponseJson(), PlayCheckReceipt.class); }
        catch (Exception e) { throw new IllegalStateException("检定回执不可用", e); }
    }

    /**
     * 掷骰取值:优势双骰取高、劣势双骰取低、两者都成立则抵消成单骰;
     * nat 20 必成、nat 1 必败,其余比 total >= dc。mod 之和封顶 modCap。
     */
    private PlayCheckReceipt rollDice(CheckContext context, boolean rerolled) {
        return com.chengyinhub.business.service.support.GameplayCheckEngine.roll(context.check,context.rules,context.state,d20,rerolled,
                bargainBonus(context));
    }

    /**
     * F6 T09:本次检定已接受的魔鬼交易加值。没开开关 / 检定没配 bargain → 0,且不多查一次库(老主题零变化)。
     */
    private int bargainBonus(CheckContext context) {
        JsonNode offer = context.check.get("bargain");
        if (!JourneyRpg.bargainEnabled(context.rules) || offer == null || !offer.isObject()) return 0;
        TopicRouteAction accepted = findCheckAction(context.session, context.session.getMemberId(), context.node.getId(),
                context.check.path("checkId").asText(""), STEP_BARGAIN);
        return accepted == null ? 0 : Math.max(0, offer.path("bonus").asInt(0));
    }

    private void refreshEnvironment(CmsTopic topic, JourneyRules rules, TopicRouteEngine.State state) {
        if (topicEnvironmentService != null) topicEnvironmentService.refresh(topic, rules, state);
    }

    private static ServiceException rpgRefusal(String message, String code) {
        return new ServiceException(message, HttpStatus.CONFLICT).setDetailMessage(code);
    }

    /**
     * F6 T09 魔鬼交易:掷骰前接受「代价换加值」。代价(bargain.effects)当场写进本局,与动作登记同一次 CAS;
     * 加值由随后的掷骰 / 重掷读取。同 id 重放返回同一回执,不二次付代价。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public PlayCheckReceipt bargain(Long memberId, Long topicId, Long activityId, Long nodeId, String checkId) {
        String actionId = checkActionId(nodeId, checkId, STEP_BARGAIN);
        TopicRouteSession session = requireCheckSession(memberId, topicId, activityId);
        PlayCheckReceipt replayed = replayCheck(session, memberId, actionId);
        if (replayed != null) return replayed;
        CheckContext context = checkContext(memberId, topicId, session, nodeId, checkId);
        if (!JourneyRpg.bargainEnabled(context.rules)) throw rpgRefusal("本主题未开启魔鬼交易", "RPG_BARGAIN_DISABLED");
        JsonNode offer = context.check.get("bargain");
        if (offer == null || !offer.isObject()) throw rpgRefusal("该检定没有可接受的交易", "RPG_BARGAIN_NOT_OFFERED");
        if (findCheckAction(session, memberId, nodeId, checkId, JourneyCheckActionId.STEP_SETTLE) != null) {
            throw new IllegalStateException("该检定已结算");
        }
        if (findCheckAction(session, memberId, nodeId, checkId, STEP_ROLL) != null) {
            throw rpgRefusal("已经掷过骰，不能再接受交易", "RPG_BARGAIN_AFTER_ROLL");
        }
        if (shopDayProofService == null
                || !shopDayProofService.hasArrived(memberId, session.getActivityId(), nodeId)) {
            throw new IllegalStateException("请先扫码确认到店");
        }
        if (isExhausted(context.state)) throw new IllegalStateException("力竭了，先找地方休整");
        // RV4 S533-1:与 roll() 同一前置条件校验,且必须在付代价之前 —— 前置不满足时什么都不扣、不写。
        JsonNode requires = context.blockConfig == null ? null : context.blockConfig.get("requires");
        if (requires != null && requires.isArray() && requires.size() > 0
                && !engine.conditionsPass(requires, context.state)) {
            throw new IllegalStateException("本站前置条件未满足");
        }
        applyJourneyEffects(offer.path("effects"), context.rules, context.state);
        PlayCheckReceipt receipt = new PlayCheckReceipt();
        receipt.setCheckId(context.check.path("checkId").asText(""));
        receipt.setTier(context.check.path("tier").asText(""));
        Map<String, Object> deal = new LinkedHashMap<String, Object>();
        deal.put("label", offer.path("label").asText(""));
        deal.put("bonus", Integer.valueOf(offer.path("bonus").asInt(0)));
        deal.put("accepted", Boolean.TRUE);
        receipt.setBargain(deal);
        applyVitals(context.state, context.rules, receipt);
        return commitCheck(session, nodeId, actionId, "CHECK_BARGAIN", receipt, context.state);
    }

    /**
     * F6 T10 动量:掷骰后、结算前,花动量把失败补成成功(只补差距,单次不超过 maxSpend)。
     * 大失败(natAuto 下的自然 1)不能挽回;补过之后不能再重掷。同 id 重放不二次扣动量。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public PlayCheckReceipt spendMomentum(Long memberId, Long topicId, Long activityId, Long nodeId, String checkId) {
        String actionId = checkActionId(nodeId, checkId, STEP_MOMENTUM);
        TopicRouteSession session = requireCheckSession(memberId, topicId, activityId);
        PlayCheckReceipt replayed = replayCheck(session, memberId, actionId);
        if (replayed != null) return replayed;
        CheckContext context = checkContext(memberId, topicId, session, nodeId, checkId);
        if (!JourneyRpg.momentumEnabled(context.rules)) throw rpgRefusal("本主题未开启动量", "RPG_MOMENTUM_DISABLED");
        TopicRouteAction rollAction = findCheckAction(session, memberId, nodeId, checkId, STEP_ROLL);
        if (rollAction == null) throw new IllegalStateException("请先掷骰");
        if (findCheckAction(session, memberId, nodeId, checkId, JourneyCheckActionId.STEP_SETTLE) != null) {
            throw new IllegalStateException("该检定已结算");
        }
        TopicRouteAction rerollAction = findCheckAction(session, memberId, nodeId, checkId, STEP_REROLL);
        PlayCheckReceipt last = readReceipt(rerollAction != null ? rerollAction : rollAction);
        if (Boolean.TRUE.equals(last.getSuccess())) throw rpgRefusal("本次检定已成功，无需动量", "RPG_MOMENTUM_NOT_NEEDED");
        if (PlayCheckReceipt.NAT_FUMBLE.equals(last.getNat())) throw rpgRefusal("大失败不能用动量挽回", "RPG_MOMENTUM_FUMBLE");
        int dc = last.getDc() == null ? 0 : last.getDc();
        int total = last.getTotal() == null ? 0 : last.getTotal();
        int need = Math.max(1, dc - total);
        JsonNode momentumRules = JourneyRpg.momentum(context.rules);
        if (need > momentumRules.path("maxSpend").asInt(0)) {
            throw rpgRefusal("差距超过单次可用动量", "RPG_MOMENTUM_GAP_TOO_LARGE");
        }
        int have = JourneyRpg.momentumOf(context.state);
        if (have < need) throw rpgRefusal("动量不足", "RPG_MOMENTUM_INSUFFICIENT");
        int after = JourneyRpg.setMomentum(context.rules, context.state, have - need);
        PlayCheckReceipt receipt = JSON.convertValue(JSON.valueToTree(last), PlayCheckReceipt.class);
        receipt.setEncounter(null);
        receipt.setSettled(false);
        receipt.setTotal(total + need);
        receipt.setSuccess(Boolean.TRUE);
        Map<String, Object> breakdown = receipt.getBreakdown() == null ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(receipt.getBreakdown());
        breakdown.put("momentum", Integer.valueOf(need));
        breakdown.put("total", Integer.valueOf(total + need));
        receipt.setBreakdown(breakdown);
        receipt.setMomentum(momentumView(context.rules, after, "spent", need));
        applyVitals(context.state, context.rules, receipt);
        return commitCheck(session, nodeId, actionId, "CHECK_MOMENTUM", receipt, context.state);
    }

    private static Map<String, Object> momentumView(JourneyRules rules, int value, String deltaKey, int delta) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put(deltaKey, Integer.valueOf(delta));
        view.put("value", Integer.valueOf(value));
        view.put("max", Integer.valueOf(JourneyRpg.momentum(rules).path("max").asInt(0)));
        return view;
    }

    /** RV4 S533-3:与 bargain() 同一规则来源(本局冻结优先,老局回退实时主题);无局 / 规则不可用 → false。 */
    @Override
    @Transactional(readOnly = true)
    public boolean bargainOffered(Long memberId, Long topicId, Long activityId) {
        if (memberId == null || memberId <= 0L || topicId == null || topicId <= 0L) return false;
        TopicRouteSession session = currentScopedSession(memberId, topicId, activityId);
        if (session == null || !memberId.equals(session.getMemberId())) return false;
        try {
            return JourneyRpg.bargainEnabled(journeyRules(session, topicMapper.selectCmsTopicById(topicId)));
        } catch (RuntimeException unusableRules) {
            return false;
        }
    }

    /** F6 T13 状态栏:本人本局的 hp / luck + P1 视图(动量 / 进度钟 / 特质 / 环境)。只读,不写库。 */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> rpgStatus(Long memberId, Long topicId, Long activityId) {
        if (memberId == null || memberId <= 0L || topicId == null || topicId <= 0L) {
            throw new IllegalArgumentException("参数有误");
        }
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("topicId", topicId);
        TopicRouteSession session = currentScopedSession(memberId, topicId, activityId);
        boolean running = session != null && memberId.equals(session.getMemberId())
                && (session.getStatus() == null || Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus()));
        view.put("running", Boolean.valueOf(running));
        if (!running) return view;
        TopicRouteEngine.State state = readEngineState(session.getStateJson());
        JourneyRules rules = sessionRules(session);
        // 环境键在只读副本上现算,让状态栏显示「此刻」;副本不落库。
        TopicRouteEngine.State preview = state.copy();
        refreshEnvironment(topicMapper.selectCmsTopicById(topicId), rules, preview);
        view.put("stateVersion", session.getVersion());
        view.putAll(vitalsView(session, state));
        view.put("attributes", JourneyInitialState.playerView(rules, state));
        view.put("rpg", JourneyRpg.view(rules, preview));
        return view;
    }


    /** 回执写入与状态 CAS 一次完成;action 行先插入,丢失 CAS 时整个事务回滚。 */
    private PlayCheckReceipt commitCheck(TopicRouteSession session, Long nodeId, String actionId,
                                         String outcomeCode, PlayCheckReceipt receipt,
                                         TopicRouteEngine.State state) {
        boolean scoreActive = orientRouteSettlement != null
                && orientRouteSettlement.active(topicMapper.selectCmsTopicById(session.getTopicId()));
        if (scoreActive) {
            TopicRouteSession locked = orientRouteSettlement.lock(session);
            if (!java.util.Objects.equals(locked.getVersion(), session.getVersion())) {
                PlayCheckReceipt raced = replayCheck(session, session.getMemberId(), actionId);
                if (raced != null) return raced;
                throw new ServiceException("路线状态已更新，请刷新后重试", HttpStatus.CONFLICT);
            }
        }
        int nextVersion = (session.getVersion() == null ? 0 : session.getVersion()) + 1;
        receipt.setSessionId(String.valueOf(session.getId()));
        receipt.setNodeId(String.valueOf(nodeId));
        receipt.setActionId(actionId);
        receipt.setStateVersion(nextVersion);
        TopicRouteAction action = new TopicRouteAction();
        action.setSessionId(session.getId()); action.setMemberId(session.getMemberId()); action.setNodeId(nodeId);
        action.setActionId(actionId); action.setReceiptId(actionId); action.setOutcomeCode(outcomeCode);
        action.setResponseJson(write(receipt)); action.setCreateTime(new Date());
        try {
            if (routeMapper.insertAction(action) != 1) throw new IllegalStateException("检定动作登记失败");
        } catch (DuplicateKeyException duplicate) {
            PlayCheckReceipt raced = replayCheck(session, session.getMemberId(), actionId);
            if (raced == null) throw duplicate;
            return raced;
        }
        syncTheme(session, state, "check:" + actionId, "CHECK_SETTLE".equals(outcomeCode) ? JourneyStatLinks.Reason.TEMPLATE_EFFECT : null);
        session.setStateJson(writeSettled(session, state));
        if (session.getStatus() == null) session.setStatus(TopicRouteSession.STATUS_RUNNING);
        session.setCurrentNodeId(state.getCurrentNodeId());
        session.setRecommendedNodeId(state.getRecommendedNodeId());
        session.setUpdateTime(new Date());
        if (scoreActive) orientRouteSettlement.beforePersist(session, state);
        if (routeMapper.updateStateCas(session) != 1) {
            throw new ServiceException("路线状态已更新，请刷新后重试", HttpStatus.CONFLICT);
        }
        if (scoreActive) orientRouteSettlement.complete(session, state);
        session.setVersion(nextVersion);
        // 只读最终结算值，避免回填回执时再次修改已经持久化的状态。
        JourneyRules settledRules = sessionRules(session);
        receipt.setHp(hpOf(state, settledRules));
        receipt.setLuck(luckOf(state, settledRules));
        receipt.setExhausted(isExhausted(state));
        Map<String, Object> encounter = encounterView(session, nodeId);
        if (encounter != null) receipt.setEncounter(encounter);
        if (routeMapper.updateActionResponse(action.getId(), write(receipt)) != 1) {
            throw new IllegalStateException("检定回执保存失败");
        }
        return receipt;
    }

    private int rollDie() {
        int value = d20.getAsInt();
        if (value < 1 || value > 20) throw new IllegalStateException("骰点值非法");
        return value;
    }

    /**
     * advantageIf / disadvantageIf 是「任一通过」的 OR 语义(与 requires 的
     * 全通过不同);空数组 = 不成立,不是无条件成立。
     */
    private boolean anyConditionPasses(JsonNode conditions, TopicRouteEngine.State state) {
        return narrativeProgress.anyConditionPasses(conditions, state);
    }

    /** mods[].when 按契约 v3 是单条 cond,路线图那套 conditionsPass 只吃数组,这里包一层。 */
    /**
     * 阶段 4:按本局状态求值一条条件。fail-closed —— 没有本局 / 本局不在跑一律 false,
     * 「判不了」不等于「放宽」。规则与状态都走本局冻结口径,与 requires / mods 同一个求值器。
     */
    @Override
    @Transactional(readOnly = true)
    public boolean conditionPasses(Long memberId, Long topicId, Long activityId, JsonNode condition) {
        if (condition == null || condition.isNull()) return false;
        if (memberId == null || memberId <= 0L || topicId == null || topicId <= 0L) return false;
        TopicRouteSession session = currentScopedSession(memberId, topicId, activityId);
        if (session == null
                || !Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus())) return false;
        return conditionPasses(condition, readEngineState(session.getStateJson()));
    }

    private boolean conditionPasses(JsonNode condition, TopicRouteEngine.State state) {
        return narrativeProgress.conditionPasses(condition, state);
    }

    private JourneyRules.Tier tierOf(JourneyRules rules, String tier) {
        if ("easy".equals(tier)) return rules.getTiers().getEasy();
        if ("medium".equals(tier)) return rules.getTiers().getMedium();
        if ("hard".equals(tier)) return rules.getTiers().getHard();
        return null;
    }

    private static String textField(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private int hpOf(TopicRouteEngine.State state, JourneyRules rules) {
        Number hp = state.getVariables().get(SYS_HP);
        return hp == null ? rules.getHp().getInit() : hp.intValue();
    }

    private int luckOf(TopicRouteEngine.State state, JourneyRules rules) {
        Number luck = state.getVariables().get(SYS_LUCK);
        return luck == null ? rules.getLuck().getInit() : luck.intValue();
    }

    /**
     * 运行时幸运上限。配置值是**起点**,思维阁里想通的念头可以把它抬高
     * (2026-09-22 用户拍板:运行时上限可以超过配置值)—— 否则 luck.max 已经配满的
     * 主题里,思维阁的奖励等于不存在。只抬不降:取两者较大值。
     */
    private int luckMaxOf(TopicRouteEngine.State state, JourneyRules rules) {
        return narrativeProgress.luckMaxOf(state, rules);
    }

    /** 判据的真源在 {@link TopicRouteEngine.State#isExhausted()} —— Encounter 也用同一个。 */
    private boolean isExhausted(TopicRouteEngine.State state) {
        return state.isExhausted();
    }

    /** 把运行时系统键落进本局状态并回填回执读数。 */
    private void applyVitals(TopicRouteEngine.State state, JourneyRules rules, PlayCheckReceipt receipt) {
        // R22-SEC S10:hp / luck 未启用时不写系统键 —— 否则 requires 里的 sys.hp / sys.luck
        // 会被一个凭空的初始值「证明」通过;回执读数照给(只读展示,与 vitals() 同一口径)。
        if (rules.getHp() != null && rules.getHp().isEnabled()) {
            state.getVariables().put(SYS_HP, hpOf(state, rules));
        }
        if (rules.getLuck() != null && rules.getLuck().isEnabled()) {
            state.getVariables().put(SYS_LUCK, luckOf(state, rules));
        }
        receipt.setHp(hpOf(state, rules));
        receipt.setLuck(luckOf(state, rules));
        receipt.setExhausted(isExhausted(state));
    }

    private static final class CheckContext {
        private TopicRouteSession session;
        private CmsTopicNode node;
        private JsonNode blockConfig;
        private JsonNode check;
        private JourneyRules rules;
        private TopicRouteEngine.State state;
    }

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private PlayJourneyStoryServiceImpl themeStoryService;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.chengyinhub.business.mapper.NodeMedalClaimMapper themeNodeMedals;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.chengyinhub.business.service.support.ThemeBroadcastService themeBroadcastService;

    private void freezeThemeStory(TopicRouteSession session,TopicRouteEngine.State state) {
        if(themeStoryService==null || !state.getThemeRuntime().has("result"))return;
        String raw=NodeJourneySnapshot.journeyStory(session.getConfigSnapshotJson());
        if(raw==null || raw.trim().isEmpty())return;
        Map<String,Object> story=themeStoryService.freezeEnding(session.getTopicId(),raw,state,session);
        ObjectNode result=(ObjectNode)state.getThemeRuntime().get("result");
        result.set("story",JSON.valueToTree(story));
        if(!themeConfig(session).path("endings").isArray() || themeConfig(session).path("endings").isEmpty()) {
            if(story.get("code")!=null)result.put("endingId",String.valueOf(story.get("code")));
            if(story.get("title")!=null)result.put("title",String.valueOf(story.get("title")));
            if(story.get("summary")!=null)result.put("summary",String.valueOf(story.get("summary")));
        }
    }
    private void cachePlayerEffects(TopicRouteSession session,Long nodeId,String instance,List<String[]> choices,ObjectNode pending) {
        ArrayNode effects=pending.withArray("effects");ObjectNode sources=pending.with("sources");
        TrustedNodeEffects.appendRootOnce(validator.readTree(session.getConfigSnapshotJson()),nodeId,instance,effects,sources);
        for(String[] choice:choices) {String slot=instance+":"+choiceActionId(nodeId,choice[0],choice[1]==null?"":choice[1]);if(sources.has(slot))continue;
            for(JsonNode effect:trustedChoiceEffects(validator.readTree(session.getConfigSnapshotJson()),nodeId,choice[0],choice[1]))effects.add(effect.deepCopy());sources.put(slot,true);}
    }
    @Override
    public JsonNode zeroChallenge(Long memberId,Long topicId,Long activityId,Long nodeId) {
        TopicRouteSession session=currentSession(memberId,topicId,activityId);
        if(!genericPlayer(session) || !Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus()) || !GameplaySurvivalEngine.challengeNode(themeConfig(session),nodeId))return null;
        TopicRouteEngine.State state=readEngineState(session.getStateJson());
        if(!state.getThemeRuntime().path("awaitingChallenge").asBoolean() || state.getThemeRuntime().path("challenge").has("disabledReason"))return null;
        return gameplaySurvival.challengeView(themeConfig(session),state);
    }
    @Override
    @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public JsonNode recordZeroChallengeResult(Long memberId,Long topicId,Long activityId,Long nodeId,String instance,String submissionId,boolean passed,Long hits,boolean retry) {
        requireScope(memberId,topicId,activityId);TopicRouteSession session=currentSession(memberId,topicId,activityId);
        if(!genericPlayer(session))throw new ServiceException("挑战不属于本人当前局",403);
        String action="challenge:"+shortHash(instance+":"+memberId+":"+submissionId);
        String hash=shortHash(nodeId+":"+passed+":"+hits+":"+retry);
        Map<String,Object> previous=replayJourneyAction(session,memberId,action);
        if(previous!=null) {if(!hash.equals(previous.get("submissionHash")))throw new ServiceException("挑战提交不能更改成绩",409);return JSON.valueToTree(previous);}
        if(!Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus()))throw new ServiceException("本局已结束",409);
        TopicRouteEngine.State state=readEngineState(session.getStateJson());
        if(!instance.equals("challenge:"+state.getThemeRuntime().path("challenge").path("instanceId").asText()))throw new ServiceException("挑战实例已更新",409);
        ObjectNode receipt=gameplaySurvival.submitChallenge(validator.readTree(session.getConfigSnapshotJson()),sessionRules(session),state,memberId,state.getThemeRuntime().path("challenge").path("instanceId").asText(),nodeId,submissionId,passed,hits,retry,clock.millis());
        receipt.put("submissionHash",hash);
        return JSON.valueToTree(commitJourneyAction(session,nodeId,action,"CHALLENGE",JSON.convertValue(receipt,new TypeReference<Map<String,Object>>(){}),state));
    }
    @Override
    @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public void recordNodeResult(Long memberId,Long topicId,Long activityId,Long nodeId,String instance,String actionId,Boolean passed,boolean mistake,int outcome,List<String[]> choices) {
        recordNodeResult(memberId,topicId,activityId,nodeId,instance,actionId,passed,mistake,outcome,choices,null,null);
    }
    @Override
    @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public void assertGameplayOutcomeStart(Long memberId,Long topicId,Long activityId,Long nodeId,String instance) {
        requireScope(memberId,topicId,activityId);TopicRouteSession session=currentSession(memberId,topicId,activityId);
        if(!genericPlayer(session) || !Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus()) || !java.util.Objects.equals(instance,gameplayInstance(memberId,topicId,activityId,nodeId)))throw new ServiceException("Attempt no longer belongs to the current play-through",409);
        JsonNode snapshot=validator.readTree(session.getConfigSnapshotJson());
        new com.chengyinhub.business.service.support.GameplayOutcomeEngine().assertPassAffordable(snapshot.path("nodeConfigs").path(String.valueOf(nodeId)),sessionRules(session),readEngineState(session.getStateJson()),snapshot.path("themeConfig"));
    }
    @Override
    @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public PlayCheckReceipt rollGameplayCheck(Long memberId,Long topicId,Long activityId,Long nodeId,String instance,String attemptId,boolean reroll) {
        requireScope(memberId,topicId,activityId);TopicRouteSession session=currentSession(memberId,topicId,activityId);
        if(!genericPlayer(session) || !Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus()) || !java.util.Objects.equals(instance,gameplayInstance(memberId,topicId,activityId,nodeId)))throw new ServiceException("检定不属于当前任务实例",409);
        TopicRouteEngine.State state=readEngineState(session.getStateJson());
        String action="r5-check:"+shortHash(attemptId+":"+reroll);Map<String,Object> previous=replayJourneyAction(session,memberId,action);
        if(previous!=null)return JSON.convertValue(previous,PlayCheckReceipt.class);
        JsonNode block=validator.readTree(session.getConfigSnapshotJson()).path("nodeConfigs").path(String.valueOf(nodeId));
        if(!block.has("outcomeRules"))throw new IllegalStateException("检定未启用结果规则");
        PlayCheckReceipt receipt=com.chengyinhub.business.service.support.GameplayCheckEngine.rollAttempt(block.path("check"),sessionRules(session),state,attemptId,reroll,d20);
        commitJourneyAction(session,nodeId,action,"GAMEPLAY_CHECK",JSON.convertValue(receipt,new TypeReference<Map<String,Object>>(){}),state);
        return receipt;
    }
    @Override @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public ObjectNode gameplayCheckActions(Long memberId,Long topicId,Long activityId,Long nodeId,String instance,String attemptId) {
        requireScope(memberId,topicId,activityId);TopicRouteSession session=currentSession(memberId,topicId,activityId);
        if(!genericPlayer(session) || !Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus()) || !java.util.Objects.equals(instance,gameplayInstance(memberId,topicId,activityId,nodeId)))throw new ServiceException("检定不属于当前任务实例",409);
        JsonNode block=validator.readTree(session.getConfigSnapshotJson()).path("nodeConfigs").path(String.valueOf(nodeId));
        return com.chengyinhub.business.service.support.GameplayCheckEngine.actions(block.path("check"),sessionRules(session),readEngineState(session.getStateJson()),attemptId);
    }
    @Override @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public boolean recordOriginalGameplayOutcome(Long memberId,Long originalSessionId,Long nodeId,String instance,String attemptId,JsonNode decision,List<String[]> choices) {
        TopicRouteSession original=routeMapper.selectAccessibleByIdForUpdate(originalSessionId,memberId);
        if(original==null || !genericPlayer(original) || !Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(original.getStatus()))return false;
        TopicRouteSession current=currentSession(memberId,original.getTopicId(),original.getActivityId());
        if(current==null || !original.getId().equals(current.getId()) || readEngineState(original.getStateJson()).getThemeRuntime().has("result"))return false;
        try{requireScope(memberId,original.getTopicId(),original.getActivityId());}catch(ServiceException | IllegalStateException closed){return false;}
        try{if(!instance.equals(gameplayInstance(memberId,original.getTopicId(),original.getActivityId(),nodeId)))return false;}catch(ServiceException gone){if(Integer.valueOf(409).equals(gone.getCode()))return false;throw gone;}
        recordGameplayOutcome(memberId,original.getTopicId(),original.getActivityId(),nodeId,instance,attemptId,decision,choices);return true;
    }
    @Override public Long gameplaySessionId(Long memberId,Long topicId,Long activityId){TopicRouteSession session=currentSession(memberId,topicId,activityId);if(session==null || !genericPlayer(session))throw new IllegalStateException("缺少个人原局");return session.getId();}
    @Override @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public JsonNode gameplayFeedback(Long memberId,Long sessionId,String attemptId,String optionId) {
        TopicRouteSession session=routeMapper.selectAccessibleById(sessionId,memberId);if(session==null || !genericPlayer(session))throw new ServiceException("反馈不属于当前会员",403);
        TopicRouteEngine.State state=readEngineState(session.getStateJson());com.chengyinhub.business.service.support.GameplayOutcomeEngine outcomes=new com.chengyinhub.business.service.support.GameplayOutcomeEngine();JsonNode frozen=validator.readTree(session.getConfigSnapshotJson());ObjectNode feedback=outcomes.feedback(frozen,state,attemptId);if(feedback==null)return null;
        boolean writable=Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus()) && currentSession(memberId,session.getTopicId(),session.getActivityId())!=null && session.getId().equals(currentSession(memberId,session.getTopicId(),session.getActivityId()).getId());
        try{requireScope(memberId,session.getTopicId(),session.getActivityId());}catch(ServiceException unavailable){writable=false;}
        if(optionId!=null){if(!writable)throw new ServiceException("原主题已结束，反馈仅可阅读",409);TopicRouteEngine.State before=state.copy();String step=feedback.path("currentStepId").asText();feedback=outcomes.chooseFeedback(frozen,sessionRules(session),state,attemptId,optionId);JourneyInitialState.clamp(sessionRules(session),state);themeEngine.events(themeConfig(session),state,sessionRules(session));JourneyStatLinks.apply(sessionRules(session),before,state,"MEMBER:"+memberId,"feedback:"+attemptId+":"+step,JourneyStatLinks.Reason.TEMPLATE_EFFECT);long node=state.getThemeRuntime().path("outcomeAttempts").path(attemptId).path("decision").path("nodeId").asLong();commitJourneyAction(session,node,"feedback:"+shortHash(attemptId+":"+step+":"+feedback.path("actions").asInt()),"OUTCOME_FEEDBACK",JSON.convertValue(feedback,Map.class),state);}
        feedback.put("writable",writable);return feedback;
    }
    @Override
    @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public void recordGameplayOutcome(Long memberId,Long topicId,Long activityId,Long nodeId,String instance,String attemptId,JsonNode decision,List<String[]> choices) {
        if (decision == null || decision.path("nodeId").asLong() != nodeId || !Arrays.asList("PASS","FAIL").contains(decision.path("outcome").asText())) throw new IllegalArgumentException("Invalid trusted outcome");
        recordNodeResult(memberId,topicId,activityId,nodeId,instance,attemptId,"PASS".equals(decision.path("outcome").asText()),false,0,choices,decision,attemptId);
    }
    private void recordNodeResult(Long memberId,Long topicId,Long activityId,Long nodeId,String instance,String actionId,Boolean passed,boolean mistake,int outcome,List<String[]> choices,JsonNode decision,String attemptId) {
        TopicRouteSession session=currentSession(memberId,topicId,activityId);
        if(session==null || instance==null || !instance.equals(genericPlayer(session)?gameplayInstance(memberId,topicId,activityId,nodeId):session.getPlayInstanceId()))throw new ServiceException("本局已更新，请刷新",409);
        JourneyRules rules = sessionRules(session);
        if (decision!=null && !genericPlayer(session))throw new ServiceException("Outcome settlement requires a generic player session",409);
        if (genericPlayer(session)) {
            requireScope(memberId,topicId,activityId);
            if (!Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus())) throw new ServiceException("本局已结束",409);
            String event="advanced:"+shortHash(actionId);
            if (replayJourneyAction(session,memberId,event)!=null) {
                if(decision!=null)new com.chengyinhub.business.service.support.GameplayOutcomeEngine().remember(validator.readTree(session.getConfigSnapshotJson()),readEngineState(session.getStateJson()),attemptId,decision);
                return;
            }
            TopicRouteEngine.State state=readEngineState(session.getStateJson());
            JsonNode operation=state.getThemeRuntime().path("gameplay").path("activeOperation");
            if (decision != null) {
                String active=state.getThemeRuntime().path("gameplay").path("activeQuest").asText();
                if (operation.isObject() ? operation.path("nodeId").asLong()!=nodeId : active.isEmpty() || gameplayTasks.quest(themeConfig(session),active).path("nodeId").asLong()!=nodeId) throw new ServiceException("Outcome does not belong to the current attempt",409);
                com.chengyinhub.business.service.support.GameplayOutcomeEngine outcomes=new com.chengyinhub.business.service.support.GameplayOutcomeEngine();
                outcomes.remember(validator.readTree(session.getConfigSnapshotJson()),state,attemptId,decision);
                if (!Boolean.TRUE.equals(passed)) {
                    TopicRouteEngine.State before=state.copy();
                    ObjectNode receipt=outcomes.settleRemembered(themeConfig(session),rules,state,Collections.singletonMap(memberId,state),attemptId,clock.millis());
                    ArrayNode legacy=JSON.createArrayNode();
                    for(String[] choice:choices) {
                        String source="r5-choice:"+instance+":"+choiceActionId(nodeId,choice[0],choice[1]==null?"":choice[1]);
                        if(state.getThemeRuntime().with("sources").has(source))continue;
                        for(JsonNode effect:trustedChoiceEffects(validator.readTree(session.getConfigSnapshotJson()),nodeId,choice[0],choice[1]))legacy.add(effect);
                        state.getThemeRuntime().with("sources").put(source,true);
                    }
                    com.chengyinhub.business.service.support.TemplateEffects.apply(legacy,rules,state);JourneyInitialState.clamp(rules,state);
                    state.getVariables().put(SYS_PASSED_PREFIX+nodeId,0);
                    JourneyStatLinks.apply(rules,before,state,"MEMBER:"+memberId,event,new LinkedHashSet<>(Arrays.asList(JourneyStatLinks.Reason.TASK_COST,JourneyStatLinks.Reason.TEMPLATE_EFFECT)));
                    commitJourneyAction(session,nodeId,event,"GAMEPLAY_FAIL",JSON.convertValue(receipt,new TypeReference<Map<String,Object>>(){}),state);return;
                }
            }
            if(operation.isObject()) {
                if(operation.path("nodeId").asLong()!=nodeId)throw new ServiceException("该题组不属于当前设施操作",409);
                if(Boolean.TRUE.equals(passed)) {ObjectNode pending=(ObjectNode)operation;pending.put("verified",true);cachePlayerEffects(session,nodeId,instance,choices,pending);if(decision!=null)pending.put("outcomeAttemptId",attemptId);}
                Map<String,Object> receipt=new LinkedHashMap<>();receipt.put("nodeId",nodeId);receipt.put("passed",Boolean.TRUE.equals(passed));commitJourneyAction(session,nodeId,event,"OPERATION_FACT",receipt,state);return;
            }
            String active=state.getThemeRuntime().path("gameplay").path("activeQuest").asText();
            if (active.isEmpty() || gameplayTasks.quest(themeConfig(session),active).path("nodeId").asLong()!=nodeId) throw new ServiceException("请先开始当前任务",409);
            if (Boolean.TRUE.equals(passed)) {
                state.getThemeRuntime().with("verifiedNodes").put(String.valueOf(nodeId),clock.millis());
                state.getVariables().put(SYS_PASSED_PREFIX+nodeId,1);
                ObjectNode pending=state.getThemeRuntime().with("gameplay").with("pendingTaskEffects").with(active);
                cachePlayerEffects(session,nodeId,instance,choices,pending);if(decision!=null)pending.put("outcomeAttemptId",attemptId);
            }
            Map<String,Object> receipt=new LinkedHashMap<>();receipt.put("nodeId",nodeId);receipt.put("passed",Boolean.TRUE.equals(passed));
            commitJourneyAction(session,nodeId,event,"TASK_FACT",receipt,state);
            return;
        }
        if (!rules.getStatLinks().isEmpty()) {
            String event = "advanced:" + shortHash(actionId);
            if (replayJourneyAction(session, memberId, event) != null) return;
            CmsTopicNode node = nodeMapper.selectCmsTopicNodeById(nodeId);
            if (node == null || !topicId.equals(node.getTopicId())) throw new ServiceException("节点不属于本主题", 403);
            TopicRouteEngine.State state = readEngineState(session.getStateJson());
            if (mistake && rules.isCheckEnabled() && rules.getHp().isEnabled()) {
                int next = mistakesOf(state, nodeId) + 1;
                if (next > 1) {
                    int cost = themeEngine.reduceHpCost(themeConfig(session), state, tierOf(rules, mistakeTierOf(session, node, rules)).getDamage());
                    int actual = -themeEngine.changeStat(state, rules, SYS_HP, -cost);
                    addDamage(state, nodeId, actual);
                }
                state.getVariables().put(SYS_MISTAKES_PREFIX + nodeId, next);
            }
            if (passed != null) state.getVariables().put(SYS_PASSED_PREFIX + nodeId, passed ? 1 : 0);
            if (outcome > 0) state.getVariables().put(SYS_OUTCOME_PREFIX + nodeId, outcome);
            ArrayNode effects = JSON.createArrayNode();
            for (String[] choice : choices) {
                String slot = choiceActionId(nodeId, choice[0], choice[1]);
                if (state.getThemeRuntime().with("sources").has(slot)) continue;
                for (JsonNode effect : trustedChoiceEffects(validator.readTree(session.getConfigSnapshotJson()), nodeId, choice[0], choice[1])) effects.add(effect);
                state.getThemeRuntime().with("sources").put(slot, true);
                themeEngine.signal(themeConfig(session), state, rules, "CHOICE", nodeId, choice[0], choice[1], 0, slot);
            }
            applyJourneyEffects(effects, rules, state);
            Map<String, Object> receipt = new LinkedHashMap<>(); receipt.put("nodeId", nodeId);
            putVitals(receipt, session, state);
            commitJourneyAction(session, nodeId, event, "CHOICE", receipt, state);
            return;
        }
        if(mistake) recordMistake(memberId,topicId,activityId,nodeId,session);
        if(passed!=null) recordPassed(memberId,topicId,activityId,nodeId,actionId,passed,session);
        if(outcome>0) recordOutcome(memberId,topicId,activityId,nodeId,actionId,outcome,session);
        for(String[] choice:choices) recordChoice(memberId,topicId,activityId,nodeId,choice[0],choice[1],session);
    }
    @Override public JsonNode publishedR5NodeConfig(Long memberId,Long topicId,Long nodeId,Long activityId) {
        JsonNode frozen=sharedSnapshot(memberId,topicId,activityId,nodeId);
        if(frozen==null){TopicRouteSession session=currentSession(memberId,topicId,activityId);if(session==null)return null;frozen=validator.readTree(session.getConfigSnapshotJson());}
        if(frozen.path("runtimeSnapshotVersion").asInt()!=1 || frozen.path("r5AuthorValidationVersion").asInt()!=1)return null;
        JsonNode block=frozen.path("nodeConfigs").get(String.valueOf(nodeId));return block!=null && block.path("outcomeRules").path("version").asInt()==1?block.deepCopy():null;
    }
    @Override
    public JsonNode nodeRuntimeConfig(Long memberId,Long topicId,Long nodeId,Long activityId) {
        JsonNode shared = sharedSnapshot(memberId, topicId, activityId, nodeId);
        if (shared != null) { JsonNode config=shared.path("nodeConfigs").get(String.valueOf(nodeId)); return config==null?null:config.deepCopy(); }
        TopicRouteSession session=currentSession(memberId,topicId,activityId);
        if(session==null) {
            JsonNode publication=queryNodeContentSnapshot(memberId,topicId,activityId,nodeId);
            if(publication==null)return null;
            NodeJourneySnapshot.answerFromSnapshot(publication,nodeId);
            publication=NodeJourneySnapshot.nodeContentSource(publication,nodeId);
            JsonNode config=publication.path("nodeConfigs").get(String.valueOf(nodeId));
            return config==null?null:config.deepCopy();
        }
        JsonNode snapshot=validator.readTree(session.getConfigSnapshotJson());
        // New original content also exists for non-generic legacy mechanics, without themeConfig.
        if(!snapshot.path("themeConfig").isObject() && !NodeJourneySnapshot.hasContent(NodeJourneySnapshot.answerFromSnapshot(snapshot,nodeId)))return null;
        JsonNode config=snapshot.path("nodeConfigs").get(String.valueOf(nodeId));
        return config==null?null:config.deepCopy();
    }

    private void applyThemeRescue(TopicRouteSession session,TopicRouteEngine.State state,JourneyRules rules) {
        JsonNode config=themeConfig(session);int count=state.getThemeRuntime().path("rescues").asInt();
        if(!state.getThemeRuntime().path("awaitingRescue").asBoolean() || count>=config.path("rescueLimit").asInt())throw new ServiceException("本局没有可用救援");
        state.getThemeRuntime().put("rescues",count+1);state.getThemeRuntime().put("awaitingRescue",false);state.getVariables().put(SYS_HP,rules.getRecovery().getExhaustRestHp());state.getVariables().remove(SYS_EXHAUSTED);
        if(state.getThemeRuntime().path("finishAfterRescue").asBoolean()){state.getThemeRuntime().remove("finishAfterRescue");state.setStatus("COMPLETED");}
        String tag=rules.getRecovery().getExhaustTag();if(tag!=null && !tag.trim().isEmpty())state.getTags().add(tag);
    }

    @Override
    public Map<String,Object> themeCard(Long memberId,Long sessionId) {
        if(memberId==null || memberId<=0 || sessionId==null)throw new IllegalArgumentException("参数不正确");
        TopicRouteSession session=routeMapper.selectAccessibleById(sessionId,memberId);
        if(session==null)throw new ServiceException("本局不存在或无权查看",403);
        JsonNode result=readEngineState(session.getStateJson()).getThemeRuntime().get("result");
        if(result==null)throw new ServiceException("本局尚未结束");
        ObjectNode card=themeEngine.card(result);
        if(result.path("card").path("showUnlocks").asBoolean()) {
            ObjectNode unlocks=card.putObject("unlocks");unlocks.put("total",result.path("endingCount").asInt());ArrayNode known=unlocks.putArray("known");Set<String> seen=new LinkedHashSet<>();
            for(String raw:routeMapper.selectEndingResults(memberId,session.getTopicId())) {JsonNode r=validator.readTree(raw);String id=r.path("endingId").asText();if(!id.isEmpty() && seen.add(id)){ObjectNode e=known.addObject();e.put("id",id);e.put("title",r.path("title").asText());}}
        }
        CmsTopic publishedTopic=topicMapper.selectCmsTopicById(session.getTopicId());
        ObjectNode published=publishedTopic==null?null:ThemeRuntimeConfig.parse(publishedTopic.getPublishedThemeRuntimeConfigJson());
        if(published!=null && published.path("scenes").size()>0 && published.path("replayEnabled").asBoolean()) {
            ObjectNode next=ThemeRuntimeConfig.freeze(published,routeMapper.countRuns(session.getActivityId(),session.getTopicId(),memberId),clock.millis());
            ObjectNode arrangement=card.putObject("nextScene");
            arrangement.put("name",next.path("sceneName").asText());
            arrangement.put("rotation",published.path("rotation").asText("FIXED"));
            arrangement.put("asOf",clock.millis());
        }
        return JSON.convertValue(card,new TypeReference<Map<String,Object>>(){});
    }

    private boolean genericConfig(JsonNode config) {
        if(config==null)return false;
        JsonNode game=config.path("gameplayRuntime");
        return "PLAYER".equals(game.path("runtimeScope").asText("PLAYER")) && game.path("quests").size()>0;
    }
    private boolean genericPlayer(TopicRouteSession session) { return session!=null && genericConfig(themeConfig(session)); }
    @Override public boolean genericPlayer(Long memberId,Long topicId,Long activityId) { return genericPlayer(currentSession(memberId,topicId,activityId)); }
    private void initializeTheme(TopicRouteSession session,TopicRouteEngine.State state) {
        themeEngine.initialize(themeConfig(session),state);
        if (genericPlayer(session)) {
            state.getCompletedNodeIds().clear();state.setStatus("ACTIVE");
            gameplayTasks.initialize(themeConfig(session),sessionRules(session),state,clock.millis());
            gameplaySurvival.syncChallenge(validator.readTree(session.getConfigSnapshotJson()),sessionRules(session),state,clock.millis());
        }
    }

    @Override
    public boolean hasRunningSession(Long memberId, Long topicId, Long activityId) {
        return memberId != null && topicId != null && routeStarted(memberId, topicId, activityId == null ? 0L : activityId);
    }

    // 已开局：有进行中的路线局；普通线性路线不建局，以本人默认游玩实例下已记的节点进度为准(记进度时已校验当时有效)。
    private boolean routeStarted(Long member, Long topic, long activity) {
        if (runningSession(member, topic, activity) != null) return true;
        if (currentSession(member, topic, activity) != null) return false; // 已结束的局不算在玩
        PlayerNodeProgress probe = new PlayerNodeProgress(); probe.setMemberId(member);
        probe.setActivityId(activity); probe.setTopicId(topic); probe.setPlayInstanceId(DEFAULT_INSTANCE);
        List<PlayerNodeProgress> rows = progressMapper.selectPlayerNodeProgressList(probe);
        return rows != null && !rows.isEmpty();
    }

    private TopicRouteSession runningSession(Long member, Long topic, long activity) {
        TopicRouteSession session = currentSession(member, topic, activity);
        if (session == null || !member.equals(session.getMemberId())) return null;
        return session.getStatus() == null || Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus())
                ? session : null;
    }

    /** Resolve old omitted-activity requests by their original unique scope, then select that scope's current run. */
    private TopicRouteSession currentScopedSession(Long memberId, Long topicId, Long activityId) {
        return TopicRouteSessionScope.findCurrent(routeMapper, memberId, topicId, activityId);
    }

    private TopicRouteSession currentSession(Long member, Long topic, Long activity) {
        TopicRouteSession current = routeMapper.selectCurrentByOwner(activity == null ? 0L : activity, topic, member);
        return current == null ? routeMapper.selectByOwner(activity == null ? 0L : activity, topic, member, DEFAULT_INSTANCE) : current;
    }
    private JsonNode themeConfig(TopicRouteSession session) {
        if (session == null) return JSON.createObjectNode().path("missing");
        return validator.readTree(session.getConfigSnapshotJson()).path("themeConfig");
    }
    private boolean containsNode(JsonNode ids,Long id) { return TopicRoutePublicationCompiler.containsNode(ids, id); }
    private boolean chapterNodesDone(JsonNode binding,TopicRouteEngine.State state) {for(JsonNode id:binding.path("nodeIds"))if(!state.getCompletedNodeIds().contains(id.asLong()))return false;return true;}
    private boolean chapterReached(JsonNode binding,TopicRouteEngine.State state) {
        if(!binding.isObject() || binding.path("ending").asBoolean())return false;
        if(binding.path("opening").asBoolean())return true;
        if(binding.path("requiresOpening").asBoolean() && !state.getTags().contains("sys.opening.done"))return false;
        for(JsonNode id:binding.path("requiredBefore"))if(!state.getCompletedNodeIds().contains(id.asLong()))return false;
        if(binding.path("nodeIds").size()>0){for(JsonNode id:binding.path("nodeIds"))if(Long.valueOf(id.asLong()).equals(state.getCurrentNodeId()) || state.getCompletedNodeIds().contains(id.asLong()))return true;return false;}
        return true;
    }
    private boolean hasStepEggs(JsonNode config) {for(JsonNode ch:config.path("chapters"))for(JsonNode egg:ch.path("eggs"))if("STEPS".equals(egg.path("trigger").asText()))return true;return false;}
    private void syncTheme(TopicRouteSession session, TopicRouteEngine.State state) {
        syncTheme(session, state, null, null);
    }
    private void syncTheme(TopicRouteSession session, TopicRouteEngine.State state, String eventId, JourneyStatLinks.Reason reason) {
        JsonNode config = themeConfig(session);
        JourneyRules rules = sessionRules(session);
        TopicRouteEngine.State actionBefore = eventId == null ? null : readEngineState(session.getStateJson());
        Map<String, Set<JourneyStatLinks.Reason>> reasons = new LinkedHashMap<>();
        if (genericPlayer(session)) {
            themeEngine.reconcileHeld(config,state,rules);
            JourneyInitialState.clamp(rules,state);
            if (actionBefore != null && reason != null) JourneyStatLinks.apply(rules,actionBefore,state,"MEMBER:"+session.getMemberId(),eventId,reason);
            int beforeRecovery=JourneyInitialState.numericValue(state,SYS_HP);
            gameplaySurvival.syncChallenge(validator.readTree(session.getConfigSnapshotJson()),rules,state,clock.millis());
            if(beforeRecovery!=JourneyInitialState.numericValue(state,SYS_HP)){themeEngine.reconcileHeld(config,state,rules);JourneyInitialState.clamp(rules,state);}
            if (state.getThemeRuntime().has("result")) {
                freezeThemeStory(session,state); session.setStatus(statusOf(state)); session.setCompletedAt(new Date(clock.millis()));
            }
            return;
        }
        if (actionBefore != null && reason != null) JourneyStatLinks.recordDeclines(rules, actionBefore, state, reason, reasons);
        if (!config.isObject() || state.getThemeRuntime().has("result")) {
            if (actionBefore != null) JourneyStatLinks.apply(rules, actionBefore, state, "MEMBER:" + session.getMemberId(), eventId, reasons);
            return;
        }
        if(themeNodeMedals!=null)for(JsonNode medal:config.path("legacyMedals")) {
            if(!"NODE_RANK".equals(medal.path("legacySource").asText()))continue;
            Long nodeId=medal.path("nodeId").asLong();if(!state.getCompletedNodeIds().contains(nodeId))continue;
            PlayerNodeProgress probe=new PlayerNodeProgress();probe.setMemberId(session.getMemberId());probe.setTopicId(session.getTopicId());probe.setActivityId(session.getActivityId());probe.setNodeId(nodeId);probe.setPlayInstanceId(session.getPlayInstanceId());
            for(PlayerNodeProgress progress:progressMapper.selectPlayerNodeProgressList(probe))if(Integer.valueOf(1).equals(progress.getStatus()) && themeNodeMedals.selectRankByProgress(nodeId,session.getMemberId(),progress.getId())!=null)themeEngine.legacyMedal(config,state,rules,medal.path("id").asText());
        }
        for(JsonNode ch:config.path("chapters")) {
            JsonNode binding=ch.path("binding");
            if(chapterReached(binding,state) && (binding.path("opening").asBoolean()? !state.getTags().contains("sys.opening.done"):containsNode(binding.path("nodeIds"),state.getCurrentNodeId())))themeEngine.chapterStart(config,state,rules,ch,clock.millis());
            if(state.getThemeRuntime().path("chapters").has(String.valueOf(ch.path("index").asInt())) && (binding.path("nodeIds").size()>0 || binding.path("opening").asBoolean()) && chapterNodesDone(binding,state)
                && (!binding.path("opening").asBoolean() || state.getTags().contains("sys.opening.done"))) {
                TopicRouteEngine.State chapterBefore = state.copy();
                themeEngine.chapterEnd(config,state,rules,ch,clock.millis());
                JourneyStatLinks.recordDeclines(rules, chapterBefore, state, JourneyStatLinks.Reason.CHAPTER_COST, reasons);
            }
        }
        themeEngine.chapterTick(config,state,rules,clock.millis());
        themeEngine.reconcileHeld(config,state,rules);
        TopicRouteEngine.State eventsBefore = state.copy();
        themeEngine.events(config,state,rules);
        JourneyInitialState.clamp(rules, state);
        JourneyStatLinks.recordDeclines(rules, eventsBefore, state, JourneyStatLinks.Reason.TEMPLATE_EFFECT, reasons);
        if (actionBefore != null) JourneyStatLinks.apply(rules, actionBefore, state, "MEMBER:" + session.getMemberId(), eventId, reasons);
        if(themeBroadcastService!=null)themeBroadcastService.enqueue(session,state);
        String terminalReason = "COMPLETED".equals(state.getStatus()) ? "COMPLETED" : null;
        if(terminalReason==null && config.has("actionLimit") && state.getVariables().get("sys.actions").intValue()>=config.path("actionLimit").asInt())terminalReason="BUDGET";
        if (rules.isCheckEnabled() && rules.getHp().isEnabled() && hpOf(state,rules) <= 0) {
            if(!"BUDGET".equals(terminalReason) && state.getThemeRuntime().path("rescues").asInt()<config.path("rescueLimit").asInt()) {
                state.getThemeRuntime().put("awaitingRescue",true);state.getVariables().put(SYS_EXHAUSTED,1);
                if("COMPLETED".equals(terminalReason)){state.getThemeRuntime().put("finishAfterRescue",true);state.setStatus("ACTIVE");}
                terminalReason=null;
            } else terminalReason="EXHAUSTED";
        }
        if (terminalReason != null) {
            themeEngine.terminal(config,state,rules,terminalReason,clock.millis());freezeThemeStory(session,state);
            session.setStatus("COMPLETED".equals(state.getStatus()) ? TopicRouteSession.STATUS_COMPLETED : TopicRouteSession.STATUS_ENDED);
            session.setCurrentNodeId(null); session.setRecommendedNodeId(null); session.setCompletedAt(new Date(clock.millis()));
        }
    }
    @Override
    public JsonNode nodeRuntimeConfig(Long memberId,Long topicId,Long nodeId) {
        TopicRouteSession session=currentSession(memberId,topicId,null);
        if(session==null || !themeConfig(session).isObject())return null;
        return validator.readTree(session.getConfigSnapshotJson()).path("nodeConfigs").get(String.valueOf(nodeId));
    }
    @Override
    public String gameplayInstance(Long memberId,Long topicId,Long activityId,Long nodeId) {
        TopicRouteSession session=currentSession(memberId,topicId,activityId);
        if(!genericPlayer(session))return playInstance(memberId,topicId,activityId);
        JsonNode challenge=zeroChallenge(memberId,topicId,activityId,nodeId);
        if(challenge!=null)return "challenge:"+challenge.path("instanceId").asText();
        JsonNode game=readEngineState(session.getStateJson()).getThemeRuntime().path("gameplay");
        JsonNode operation=game.path("activeOperation");
        if(operation.isObject() && operation.path("nodeId").asLong()==nodeId) {String receipt=operation.path("startedActionId").asText();if(receipt.isEmpty())throw new ServiceException("操作实例凭证缺失",409);return "operation:"+shortHash(session.getId()+":"+receipt);}
        String active=game.path("activeQuest").asText();
        if(active.isEmpty() || gameplayTasks.quest(themeConfig(session),active).path("nodeId").asLong()!=nodeId)throw new ServiceException("请先开始当前任务",409);
        String receipt=game.path("activeQuestReceiptId").asText();
        if(receipt.isEmpty())throw new ServiceException("任务实例凭证缺失",409);
        return "quest:"+shortHash(session.getId()+":"+receipt);
    }
    @Override
    public String playInstance(Long memberId, Long topicId, Long activityId) {
        JsonNode shared = sharedSnapshot(memberId, topicId, activityId, null);
        if (shared != null) return "game:" + shared.path("gameSessionId").asLong();
        TopicRouteSession s = currentSession(memberId,topicId,activityId);
        return s == null ? DEFAULT_INSTANCE : s.getPlayInstanceId();
    }
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Map<String,Object> themeView(Long memberId,Long topicId,Long activityId,Long sessionId) {
        TopicRouteSession session;
        if (topicId == null || topicId <= 0 || memberId == null || memberId <= 0)throw new IllegalArgumentException("参数不正确");
        if (sessionId == null) { state(memberId,topicId,activityId); session=currentSession(memberId,topicId,activityId); }
        else { session=routeMapper.selectAccessibleById(sessionId,memberId); if(session==null || !topicId.equals(session.getTopicId()))throw new ServiceException("本局不存在或无权查看",403); }
        if(session==null)throw new ServiceException("本局还没开始");
        boolean scoreActive = orientRouteSettlement != null
                && orientRouteSettlement.active(topicMapper.selectCmsTopicById(session.getTopicId()));
        if (scoreActive) session = orientRouteSettlement.lock(session);
        if(Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus())) {
            TopicRouteEngine.State live=readEngineState(session.getStateJson());String before=write(live);syncTheme(session,live);
            String after=write(live);if(!before.equals(after)) {
                session.setStateJson(after);session.setCurrentNodeId(live.getCurrentNodeId());session.setRecommendedNodeId(live.getRecommendedNodeId());session.setUpdateTime(new Date(clock.millis()));
                if (scoreActive) orientRouteSettlement.beforePersist(session, live);
                if(routeMapper.updateStateCas(session)!=1)throw new ServiceException("本局已更新，请刷新",409);session.setVersion(session.getVersion()+1);
                if (scoreActive) orientRouteSettlement.complete(session, live);
            }
        }
        Map<String,Object> view=themeViewOf(session);
        if(themeBroadcastService!=null)view.put("broadcasts",themeBroadcastService.deliver(session.getId(),memberId));
        return view;
    }
    private ObjectNode playerZeroHpView(TopicRouteSession session,TopicRouteEngine.State state) {
        Long recovery=null;String name="";String unavailable="";
        if("STEPS".equals(state.getThemeRuntime().path("zeroHp").path("policy").asText())) {
            long bound=state.getThemeRuntime().path("zeroHp").path("recoveryMemberId").asLong();recovery=bound>0?bound:session.getMemberId();
            name=routeMapper.selectMemberNickname(recovery);
        }
        try {
            requireScope(session.getMemberId(),session.getTopicId(),session.getActivityId());
            TopicRouteSession current=currentSession(session.getMemberId(),session.getTopicId(),session.getActivityId());
            if(!Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus()) || current==null || !session.getId().equals(current.getId()))unavailable="本局已结束";
        } catch(ServiceException | IllegalArgumentException | IllegalStateException closed) {unavailable=closed.getMessage();}
        return GameplaySurvivalEngine.zeroView(themeConfig(session),state,clock.millis(),session.getMemberId(),recovery,name,unavailable);
    }

    private Map<String,Object> themeViewOf(TopicRouteSession session) {
        TopicRouteEngine.State state=readEngineState(session.getStateJson());
        ObjectNode projection = themeEngine.view(themeConfig(session),state,sessionRules(session));
        if (projection.has("zeroHp")) projection.set("zeroHp", playerZeroHpView(session,state));
        Map<String,Object> view=JSON.convertValue(projection, new TypeReference<Map<String,Object>>(){});
        view.put("topicId",session.getTopicId());view.put("activityId",session.getActivityId());view.put("sceneId",themeConfig(session).path("sceneId").asText());view.put("sceneName",themeConfig(session).path("sceneName").asText());
        view.put("inventoryEnabled",themeConfig(session).isObject());
        view.put("itemCatalog",GameplayCooperationEngine.itemCatalog(themeConfig(session)));
        view.put("sessionId",session.getId());view.put("playInstanceId",session.getPlayInstanceId());view.put("version",session.getVersion());view.put("status",state.getStatus());
        boolean replayAllowed=false;
        if(themeConfig(session).isObject())try { requireReplayScope(session);replayAllowed=true; }
        catch(ServiceException | IllegalArgumentException | IllegalStateException unavailable) { }
        view.put("replayAllowed",replayAllowed);
        JourneyRules rules=sessionRules(session);
        view.put("hp",hpOf(state,rules));view.put("luck",luckOf(state,rules));
        view.put("hpEnabled",(genericPlayer(session)?rules.hasRuntimeState():rules.isCheckEnabled()) && rules.getHp().isEnabled());view.put("hpMax",rules.getHp().getMax());
        view.put("luckEnabled",(genericPlayer(session)?rules.hasRuntimeState():rules.isCheckEnabled()) && rules.getLuck().isEnabled());view.put("luckMax",luckMaxOf(state,rules));view.put("stepEggs",hasStepEggs(themeConfig(session)));
        List<Integer> chapterIndexes=new ArrayList<>();List<Map<String,Object>> chapterBindings=new ArrayList<>();boolean timeEggs=false;
        for(JsonNode ch:themeConfig(session).path("chapters")){chapterIndexes.add(ch.path("index").asInt());Map<String,Object> binding=new LinkedHashMap<>();binding.put("index",ch.path("index").asInt());binding.put("chapterId",ch.path("binding").path("chapterId").asLong());chapterBindings.add(binding);for(JsonNode egg:ch.path("eggs"))if("TIME".equals(egg.path("trigger").asText()))timeEggs=true;}
        view.put("chapterIndexes",chapterIndexes);view.put("chapterBindings",chapterBindings);view.put("timeEggs",timeEggs);
        if(genericPlayer(session)) {
            List<Map<String,Object>> actions=new ArrayList<>();JsonNode config=themeConfig(session),game=config.path("gameplayRuntime");
            view.put("runtimeScope","PLAYER");view.put("actIndex",state.getThemeRuntime().path("gameplay").path("actIndex").asInt());
            view.put("awaitingChallenge",state.getThemeRuntime().path("awaitingChallenge").asBoolean());
            if(state.getThemeRuntime().has("challenge")) {
                ObjectNode challenge=gameplayChallengeSummary(config,state);
                if(!Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus()))GameplayToolState.availability(challenge,Reason.SESSION_ENDED,challenge.path("trustedPass").asBoolean());
                view.put("challenge",challenge);
            }
            ArrayNode tools=JSON.createArrayNode(),quests=JSON.createArrayNode();
            String act=game.path("acts").path(Math.min(state.getThemeRuntime().path("gameplay").path("actIndex").asInt(),Math.max(0,game.path("acts").size()-1))).path("actKey").asText();
            for(JsonNode facility:game.path("facilities"))if(act.equals(facility.path("actKey").asText())) {
                ObjectNode tool=personalToolSummary(session,state,rules,"FACILITY_USE",facility.path("facilityKey").asText());
                tool.put("kind","FACILITY").put("operationKey",facility.path("facilityKey").asText());
                for(String field:Arrays.asList("facilityKey","title","operationNodeId"))if(facility.has(field))tool.set(field,facility.get(field).deepCopy());tools.add(tool);
            }
            for(JsonNode quest:game.path("quests")) {
                try {gameplayTasks.eligible(config,state,quest);}
                catch(IllegalStateException unavailable) {
                    if(!act.equals(quest.path("actKey").asText()) || !state.getThemeRuntime().path("gameplay").path("slots").has(quest.path("settlementSlot").asText()))continue;
                }
                ObjectNode entry=personalToolSummary(session,state,rules,"QUEST_RESOLVE",quest.path("questKey").asText());
                entry.put("kind","QUEST").put("operationKey",quest.path("questKey").asText());
                for(String field:Arrays.asList("questKey","nodeId","locationKey"))if(quest.has(field))entry.set(field,quest.get(field).deepCopy());
                entry.set("actionSummary",entry.deepCopy());quests.add(entry);
            }
            ArrayNode recoveries=JSON.createArrayNode();
            for(JsonNode recovery:game.path("survival").path("recoveries")) {
                ObjectNode entry=personalToolSummary(session,state,rules,"RECOVER",recovery.path("recoveryKey").asText());
                entry.put("kind","RECOVERY").put("operationKey",recovery.path("recoveryKey").asText());
                for(String field:Arrays.asList("recoveryKey","title","hpDelta","luckDelta"))if(recovery.has(field))entry.set(field,recovery.get(field).deepCopy());
                recoveries.add(entry);
            }
            view.put("tools",tools);view.put("interactionTools",tools);view.put("quests",quests);view.put("recoveries",recoveries);
            view.put("activeQuest",state.getThemeRuntime().path("gameplay").path("activeQuest").asText(null));
            if(state.getThemeRuntime().path("gameplay").has("activeQuestCostOption"))view.put("activeQuestCostOption",state.getThemeRuntime().path("gameplay").path("activeQuestCostOption").asInt());
            JsonNode activeOperation=state.getThemeRuntime().path("gameplay").path("activeOperation");
            if(activeOperation.isObject()){Map<String,Object> operationView=new LinkedHashMap<>();for(String field:Arrays.asList("operationKind","operationKey","nodeId","verified","costOption"))operationView.put(field,JSON.convertValue(activeOperation.path(field),Object.class));view.put("activeOperation",operationView);}else view.put("activeOperation",null);
            if(Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus())) {
                if(!activeOperation.isObject())for(JsonNode quest:game.path("quests")) {
                    String key=quest.path("questKey").asText();boolean active=key.equals(state.getThemeRuntime().path("gameplay").path("activeQuest").asText());
                    List<String> operations=active?Arrays.asList("QUEST_CANCEL","QUEST_RESOLVE"):Collections.singletonList("QUEST_START");
                    for(String operation:operations) {
                        List<String> choices=new ArrayList<>();if("QUEST_RESOLVE".equals(operation) && quest.path("choices").size()>0)for(JsonNode choice:quest.path("choices"))choices.add(choice.path("choiceKey").asText());else choices.add(null);
                        for(String choice:choices)try {
                            if(!"QUEST_CANCEL".equals(operation) && (!playerArrived(state,quest.path("nodeId").asLong()) || basicCompletionKind(validator.readTree(session.getConfigSnapshotJson()),quest.path("nodeId").asLong())!=null && freshBasicArrival(session,state,quest.path("nodeId").asLong())==null))continue;
                            previewPlayerQuest(config,rules,state,session.getMemberId(),quest,operation,choice);
                            Map<String,Object> action=new LinkedHashMap<>();action.put("operation",operation);action.put("contentId",key);action.put("nodeId",quest.path("nodeId").asLong());if(choice!=null)action.put("choiceKey",choice);actions.add(action);
                        } catch(IllegalArgumentException|IllegalStateException unavailable) { }
                    }
                    if(active && state.getThemeRuntime().path("verifiedNodes").path(quest.path("nodeId").asText()).asLong()>0) {
                        List<String> choices=new ArrayList<>();for(JsonNode choice:quest.path("choices"))choices.add(choice.path("choiceKey").asText());if(choices.isEmpty())choices.add(null);
                        for(String choice:choices)try {ObjectNode pack=gameplayTasks.preview(config,rules,state,key,choice,null,"MEMBER:"+session.getMemberId());Map<String,Object> preview=new LinkedHashMap<>();preview.put("operation","GAMEPLAY_PREVIEW");preview.put("contentId",key);if(choice!=null)preview.put("choiceKey",choice);preview.put("resolvesOperation","QUEST_RESOLVE");preview.put("previewRequired",true);preview.put("overflow",pack.path("overflow").asBoolean());actions.add(preview);}catch(IllegalArgumentException|IllegalStateException unavailable) { }
                    }
                }
                if(state.getThemeRuntime().path("gameplay").path("activeQuest").asText().isEmpty())for(JsonNode facility:game.path("facilities")) {
                    String facilityKey=facility.path("facilityKey").asText();
                    if(activeOperation.isObject()) {
                        if(!facilityKey.equals(activeOperation.path("operationKey").asText()))continue;
                        Map<String,Object> cancel=new LinkedHashMap<>();cancel.put("operation","OPERATION_CANCEL");cancel.put("contentId",facilityKey);actions.add(cancel);
                        if(!activeOperation.path("verified").asBoolean())continue;
                    }
                    try {
                        previewPlayerFacility(config,rules,state,session.getMemberId(),facility);
                        String operation=facility.has("operationNodeId") && !activeOperation.isObject()?"OPERATION_START":"FACILITY_USE";
                        if("OPERATION_START".equals(operation) && (!playerArrived(state,facility.path("operationNodeId").asLong()) || basicCompletionKind(validator.readTree(session.getConfigSnapshotJson()),facility.path("operationNodeId").asLong())!=null && freshBasicArrival(session,state,facility.path("operationNodeId").asLong())==null))continue;
                        Map<String,Object> action=new LinkedHashMap<>();action.put("operation",operation);action.put("contentId",facilityKey);if(facility.has("operationNodeId"))action.put("nodeId",facility.path("operationNodeId").asLong());actions.add(action);
                        if("FACILITY_USE".equals(operation)){Map<String,Object> preview=new LinkedHashMap<>();preview.put("operation","GAMEPLAY_PREVIEW");preview.put("contentId",facilityKey);preview.put("resolvesOperation","FACILITY_USE");preview.put("previewRequired",true);actions.add(preview);}
                    } catch(IllegalArgumentException|IllegalStateException unavailable) { }
                }
                if(state.getThemeRuntime().path("gameplay").path("activeQuest").asText().isEmpty() && !activeOperation.isObject())for(JsonNode recovery:game.path("survival").path("recoveries"))try {
                    gameplaySurvival.recover(config,rules,state.copy(),recovery.path("recoveryKey").asText(),"preview");
                    Map<String,Object> action=new LinkedHashMap<>();action.put("operation","RECOVER");action.put("contentId",recovery.path("recoveryKey").asText());actions.add(action);
                } catch(IllegalArgumentException|IllegalStateException unavailable) { }
            }
            for(Map<String,Object> action:actions) {
                ObjectNode summary=personalToolSummary(session,state,rules,String.valueOf(action.get("operation")),String.valueOf(action.get("contentId")));
                summary.remove("operation");action.putAll(JSON.convertValue(summary,new TypeReference<Map<String,Object>>(){}));
            }
            if(Integer.valueOf(TopicRouteSession.STATUS_COMPLETED).equals(session.getStatus()))for(Iterator<String> sources=state.getThemeRuntime().path("pending").fieldNames();sources.hasNext();) {
                String source=sources.next();if(!state.getThemeRuntime().path("sources").path(source).asBoolean())continue;
                Map<String,Object> claim=new LinkedHashMap<>();claim.put("operation","RECEIVE");claim.put("contentId",source);claim.put("claimOnly",true);actions.add(claim);
            }
            view.put("allowedActions",actions);
        }
        return view;
    }
    private ObjectNode personalToolSummary(TopicRouteSession session,TopicRouteEngine.State state,JourneyRules rules,String operation,String key) {
        JsonNode config=themeConfig(session),game=config.path("gameplayRuntime");
        ObjectNode summary=gameplayActionSummary(config,rules,state,null,operation,key);
        Reason reason=summary.path("disabledReason").isTextual()?Reason.valueOf(summary.path("disabledReason").asText()):null;
        JsonNode content=null;boolean quest=operation.startsWith("QUEST_") || "GAMEPLAY_PREVIEW".equals(operation);
        for(JsonNode entry:quest?game.path("quests"):"RECOVER".equals(operation)?game.path("survival").path("recoveries"):game.path("facilities")) {
            if(key.equals(entry.path(quest?"questKey":"RECOVER".equals(operation)?"recoveryKey":"facilityKey").asText()))content=entry;
        }
        if(content==null)return summary;
        JsonNode progress=state.getThemeRuntime().path("gameplay"),active=progress.path("activeOperation");
        boolean current=quest?key.equals(progress.path("activeQuest").asText()):!"RECOVER".equals(operation) && key.equals(active.path("operationKey").asText());
        long node=content.path(quest?"nodeId":"operationNodeId").asLong();
        JsonNode proof=state.getThemeRuntime().path("verifiedNodes").path(String.valueOf(node));
        boolean passed=current && (quest?(proof.isIntegralNumber() && proof.asLong()>0):active.path("verified").asBoolean() && !active.path("startedActionId").asText().isEmpty());
        summary.put("operation",quest?(current?"QUEST_RESOLVE":"QUEST_START"):"RECOVER".equals(operation)?"RECOVER":content.has("operationNodeId") && !current?"OPERATION_START":"FACILITY_USE");
        JsonNode selected=quest?progress.path("activeQuestCostOption"):active.path("costOption");
        if(current && selected.isInt() && summary.path("costOptions").isArray() && !summary.path("costOptions").path(selected.asInt()).path("affordable").asBoolean())reason=Reason.INSUFFICIENT_RESOURCES;
        if(!Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus()) || state.getThemeRuntime().has("result"))reason=Reason.SESSION_ENDED;
        else if("COMPLETED".equals(state.getStatus()) && !state.getThemeRuntime().path("awaitingChallenge").asBoolean())reason=Reason.TEAM_COMPLETED;
        else if(summary.path("remainingCount").asInt()==0 || summary.has("facility") && summary.path("facility").path("remainingCount").asInt()==0)reason=Reason.LIMIT_REACHED;
        else if((active.isObject() && !(current && !quest)) || (!progress.path("activeQuest").asText().isEmpty() && !(current && quest)))reason=Reason.TASK_ACTIVE;
        else if(node>0 && (!playerArrived(state,node) || basicCompletionKind(validator.readTree(session.getConfigSnapshotJson()),node)!=null && freshBasicArrival(session,state,node)==null))reason=Reason.NOT_ARRIVED;
        else if(current && node>0 && !passed && !operation.endsWith("CANCEL"))reason=Reason.PROOF_PENDING;
        else try {
            if(quest && !operation.endsWith("CANCEL"))gameplayTasks.eligible(config,state,content);
            else if("RECOVER".equals(operation))gameplaySurvival.recover(config,rules,state.copy(),key,"view");
            else if(!quest && !"RECOVER".equals(operation) && !themeEngine.conditions(content.path("when"),state))reason=Reason.RULE_BLOCKED;
        } catch(IllegalArgumentException|IllegalStateException unavailable) {reason=Reason.RULE_BLOCKED;}
        if(summary.path("facility").isObject())GameplayToolState.availability((ObjectNode)summary.path("facility"),reason,passed);
        return GameplayToolState.availability(summary,reason,passed);
    }

    public static ObjectNode gameplayChallengeSummary(JsonNode theme,TopicRouteEngine.State state) {
        return GameplayActionProjection.gameplayChallengeSummary(theme, state);
    }
    /** 供个人与共享局复用的动作摘要；归属和可用性须由调用方先按真实当前局判断。 */
    public static ObjectNode gameplayActionSummary(JsonNode theme,JourneyRules rules,TopicRouteEngine.State ownerState,
                                                   TopicRouteEngine.State worldState,String operation,String contentId) {
        return GameplayActionProjection.gameplayActionSummary(theme, rules, ownerState, worldState, operation, contentId);
    }
    public static ArrayNode gameplayCostShortfall(JsonNode theme,JourneyRules rules,TopicRouteEngine.State state,JsonNode costs) {
        return GameplayActionProjection.gameplayCostShortfall(theme, rules, state, costs);
    }

    @Override
    public JsonNode nodeArrivalConfig(Long memberId,Long topicId,Long activityId,Long nodeId) {
        JsonNode frozen=sharedSnapshot(memberId,topicId,activityId,nodeId);if(frozen==null){TopicRouteSession session=currentSession(memberId,topicId,activityId);if(session==null)return null;frozen=validator.readTree(session.getConfigSnapshotJson());}
        for(JsonNode station:frozen.path("routeStations"))if(station.path("nodeId").asLong()==nodeId)return station.deepCopy();return null;
    }
    private JsonNode freshBasicArrival(TopicRouteSession session,TopicRouteEngine.State state,Long nodeId) {
        String kind=basicCompletionKind(validator.readTree(session.getConfigSnapshotJson()),nodeId);if(kind==null)return null;
        JsonNode proof=state.getThemeRuntime().path("gameplay").path("arrivalFacts").path(String.valueOf(nodeId));
        JsonNode game=state.getThemeRuntime().path("gameplay");String current=game.path("activeOperation").isObject()?game.path("activeOperation").path("startedActionId").asText():game.path("activeQuestReceiptId").asText();
        return kind.equals(proof.path("kind").asText()) && (!proof.has("consumedBy") || !current.isEmpty() && current.equals(proof.path("consumedBy").asText()))?proof:null;
    }
    private void bindBasicCompletion(TopicRouteSession session,TopicRouteEngine.State state,Long nodeId) {
        JsonNode proof=freshBasicArrival(session,state,nodeId);if(proof==null)return;
        ObjectNode game=state.getThemeRuntime().with("gameplay");JsonNode operation=game.path("activeOperation");
        if(operation.isObject() && operation.path("nodeId").asLong()==nodeId) {ObjectNode op=(ObjectNode)operation;op.put("verified",true).put("arrivalReceiptId",proof.path("receiptId").asText());cachePlayerEffects(session,nodeId,"operation:"+shortHash(session.getId()+":"+op.path("startedActionId").asText()),Collections.emptyList(),op);consumeBasicArrival(state,nodeId,proof.path("receiptId").asText(),op.path("startedActionId").asText());return;}
        String quest=game.path("activeQuest").asText();if(quest.isEmpty() || gameplayTasks.quest(themeConfig(session),quest).path("nodeId").asLong()!=nodeId)return;
        state.getThemeRuntime().with("verifiedNodes").put(String.valueOf(nodeId),clock.millis());state.getVariables().put(SYS_PASSED_PREFIX+nodeId,1);game.put("activeQuestArrivalReceiptId",proof.path("receiptId").asText());
        cachePlayerEffects(session,nodeId,"quest:"+shortHash(session.getId()+":"+game.path("activeQuestReceiptId").asText()),Collections.emptyList(),game.with("pendingTaskEffects").with(quest));consumeBasicArrival(state,nodeId,proof.path("receiptId").asText(),game.path("activeQuestReceiptId").asText());
    }
    private void consumeBasicArrival(TopicRouteEngine.State state,Long nodeId,String receiptId,String consumedBy) {
        JsonNode proof=state.getThemeRuntime().path("gameplay").path("arrivalFacts").path(String.valueOf(nodeId));if(proof.isObject() && receiptId.equals(proof.path("receiptId").asText()))((ObjectNode)proof).put("consumedBy",consumedBy);
    }
    private JsonNode playerFacility(JsonNode config,String facilityKey) {
        for(JsonNode facility:config.path("gameplayRuntime").path("facilities"))if(facilityKey.equals(facility.path("facilityKey").asText()))return facility;
        throw new IllegalArgumentException("设施不存在");
    }
    private void previewPlayerQuest(JsonNode config,JourneyRules rules,TopicRouteEngine.State state,Long member,JsonNode quest,String operation,String choice) {
        int count=quest.path("costOptions").size();
        if(quest.has("facilityKey"))count=Math.max(count,playerFacility(config,quest.path("facilityKey").asText()).path("costOptions").size());
        if(count>0 && "QUEST_START".equals(operation) && !state.getThemeRuntime().path("gameplay").has("activeQuestCostOption")) {
            for(int index=0;index<count;index++)try {gameplayCooperation.ownerQuest(config,rules,state.copy(),member,quest.path("questKey").asText(),operation,choice,"preview","MEMBER:"+member,clock.millis(),null,null,index);return;}catch(IllegalStateException unavailable){ }
            throw new IllegalStateException("所有费用组均不足");
        }
        gameplayCooperation.ownerQuest(config,rules,state.copy(),member,quest.path("questKey").asText(),operation,choice,"preview","MEMBER:"+member,clock.millis(),null,null);
    }
    private void previewPlayerFacility(JsonNode config,JourneyRules rules,TopicRouteEngine.State state,Long memberId,JsonNode facility) {
        if(facility.has("costOptions")) {
            for(int index=0;index<facility.path("costOptions").size();index++)try {previewPlayerFacility(config,rules,state,memberId,facility,index);return;}catch(IllegalStateException unavailable){ }
            throw new IllegalStateException("所有费用组均不足");
        }
        previewPlayerFacility(config,rules,state,memberId,facility,null);
    }
    private void previewPlayerFacility(JsonNode config,JourneyRules rules,TopicRouteEngine.State state,Long memberId,JsonNode facility,Integer costOption) {
        if(state.getThemeRuntime().path("cooperation").path("facilities").path(facility.path("settlementSlot").asText()).path("count").asInt()>=facility.path("limit").asInt())throw new IllegalStateException("设施次数已用尽");
        TopicRouteEngine.State probe=state.copy();if(facility.has("operationNodeId"))probe.getThemeRuntime().with("verifiedNodes").put(facility.path("operationNodeId").asText(),true);
        ObjectNode payload=JSON.createObjectNode().put("facilityKey",facility.path("facilityKey").asText());if(costOption!=null)payload.put("costOption",costOption);
        gameplayCooperation.previewFacility(config,rules,new TopicRouteEngine.State(),Collections.singletonMap(memberId,probe),Collections.singletonMap(memberId,memberId),memberId,memberId,payload,clock.millis());
    }
    private boolean playerArrived(TopicRouteEngine.State state,Long nodeId) {
        return state.getThemeRuntime().path("gameplay").path("arrivals").path(String.valueOf(nodeId)).asBoolean(false);
    }

    private String routeIntentFingerprint(Object fields) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(write(fields).getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("动作摘要算法不可用",impossible); }
    }

    @Override
    @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public Map<String,Object> recordPlayerArrival(Long memberId,Long topicId,Long activityId,Long nodeId,String actionId,Integer expectedVersion) {return recordPlayerArrival(memberId,topicId,activityId,nodeId,actionId,expectedVersion,"SCAN");}
    @Override
    @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public Map<String,Object> recordPlayerArrival(Long memberId,Long topicId,Long activityId,Long nodeId,String actionId,Integer expectedVersion,String kind) {
        if(!Arrays.asList("SCAN","GPS","ANSWER").contains(kind))throw new IllegalArgumentException("到达来源不支持");
        if(actionId==null || !actionId.matches("[A-Za-z0-9:_-]{1,80}") || expectedVersion==null || expectedVersion<0 || nodeId==null || nodeId<=0)throw new IllegalArgumentException("必须提供到达动作标识、目标和期望版本");
        requireScope(memberId,topicId,activityId);
        TopicRouteSession session=currentSession(memberId,topicId,activityId);
        if(!genericPlayer(session))throw new ServiceException("本局不是通用个人局",409);
        String key="arrival:"+actionId;
        String receiptId="arrival-receipt:"+routeIntentFingerprint(Arrays.asList("arrival:v2",key,kind,nodeId,expectedVersion));
        TopicRouteAction replay=routeMapper.selectActionByAction(session.getId(),memberId,key);
        if(replay!=null){if(!receiptId.equals(replay.getReceiptId()))throw new ServiceException("动作标识已用于其他输入",409);return JSON.convertValue(validator.readTree(replay.getResponseJson()),new TypeReference<Map<String,Object>>(){});}
        for(String legacyKind:Arrays.asList("SCAN","GPS","ANSWER")) {
            if(routeMapper.selectActionByAction(session.getId(),memberId,"arrival:"+legacyKind+":"+actionId)!=null)throw new ServiceException("历史到达身份缺少原版本指纹，请核对原回执",409);
        }
        if(!Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus()))throw new ServiceException("本局已结束",409);
        if(!expectedVersion.equals(session.getVersion()))throw new ServiceException("本局已更新，请刷新",409);
        if(!canPlay(memberId,topicId,activityId,nodeId,actionId))throw new ServiceException("该站当前不可到达",409);
        TopicRouteEngine.State state=readEngineState(session.getStateJson());state.getThemeRuntime().with("gameplay").with("arrivals").put(String.valueOf(nodeId),true);
        state.getThemeRuntime().with("gameplay").with("arrivalFacts").putObject(String.valueOf(nodeId)).put("kind",kind).put("receiptId",key).put("arrivedAt",clock.millis());bindBasicCompletion(session,state,nodeId);
        TopicRouteAction action=new TopicRouteAction();action.setSessionId(session.getId());action.setMemberId(memberId);action.setNodeId(nodeId);action.setActionId(key);action.setReceiptId(receiptId);action.setOutcomeCode("TASK_ARRIVAL");action.setResponseJson("{}");action.setCreateTime(new Date(clock.millis()));
        try {if(routeMapper.insertAction(action)!=1)throw new IllegalStateException("扫码回执登记失败");}
        catch(DuplicateKeyException duplicate){TopicRouteAction winner=routeMapper.selectActionByAction(session.getId(),memberId,key);if(winner==null || !receiptId.equals(winner.getReceiptId()))throw new ServiceException("动作标识已用于其他输入",409);return JSON.convertValue(validator.readTree(winner.getResponseJson()),new TypeReference<Map<String,Object>>(){});}
        Integer version=session.getVersion();session.setStateJson(write(state));session.setUpdateTime(new Date(clock.millis()));
        if(routeMapper.updateStateCas(session)!=1)throw new ServiceException("本局已更新，请刷新",409);
        session.setVersion(version+1);Map<String,Object> view=themeViewOf(session);
        String savedResponse=write(view);
        if(routeMapper.updateActionResponse(action.getId(),savedResponse)!=1)throw new IllegalStateException("扫码回执保存失败");
        return JSON.convertValue(validator.readTree(savedResponse),new TypeReference<Map<String,Object>>(){});
    }

    @Override
    @Transactional(readOnly=true)
    public Map<String,Object> gameplayPreview(Long memberId,Long sessionId,Integer version,String questKey,String choiceKey,JsonNode discardRewards) {
        return gameplayPreview(memberId,sessionId,version,questKey,choiceKey,discardRewards,null);
    }
    @Override
    @Transactional(readOnly=true)
    public Map<String,Object> gameplayPreview(Long memberId,Long sessionId,Integer version,String questKey,String choiceKey,JsonNode discardRewards,Integer costOption) {
        return gameplayPreview(memberId,sessionId,version,questKey,choiceKey,discardRewards,costOption,"QUEST_RESOLVE");
    }
    @Override
    @Transactional(readOnly=true)
    public Map<String,Object> gameplayPreview(Long memberId,Long sessionId,Integer version,String questKey,String choiceKey,JsonNode discardRewards,Integer costOption,String operation) {
        if(memberId==null || memberId<=0 || sessionId==null || questKey==null || questKey.length()>96 || (choiceKey!=null && choiceKey.length()>96))throw new IllegalArgumentException("预览参数不正确");
        TopicRouteSession session=routeMapper.selectAccessibleById(sessionId,memberId);
        if(session==null)throw new ServiceException("本局不存在或无权查看",403);
        requireScope(memberId,session.getTopicId(),session.getActivityId());
        TopicRouteSession current=currentSession(memberId,session.getTopicId(),session.getActivityId());
        if(!genericPlayer(session) || current==null || !sessionId.equals(current.getId()) || !Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus()))throw new ServiceException("本局不是运行中的通用个人局",409);
        if(version==null || !version.equals(session.getVersion()))throw new ServiceException("本局已更新，请刷新",409);
        JourneyRules rules=sessionRules(session);TopicRouteEngine.State state=readEngineState(session.getStateJson());
        ObjectNode preview;
        try {
            if("FACILITY_USE".equals(operation)) {
                if(choiceKey!=null)throw new IllegalArgumentException("设施不接受任务选项");
                JsonNode facility=playerFacility(themeConfig(session),questKey);JsonNode active=state.getThemeRuntime().path("gameplay").path("activeOperation");
                if(facility.has("operationNodeId")) {
                    if(!questKey.equals(active.path("operationKey").asText()) || !active.path("verified").asBoolean())throw new IllegalStateException("设施操作题组尚未通过");
                    state.getThemeRuntime().with("verifiedNodes").put(facility.path("operationNodeId").asText(),true);state.getThemeRuntime().with("operationFacts").set("FACILITY:"+questKey,active.deepCopy());
                }
                Integer selected=costOption!=null?costOption:active.path("costOption").isInt()?Integer.valueOf(active.path("costOption").asInt()):null;
                if(active.path("costOption").isInt() && costOption!=null && costOption!=active.path("costOption").asInt())throw new IllegalStateException("设施操作实例不能更换费用组");
                ObjectNode payload=JSON.createObjectNode().put("facilityKey",questKey);if(selected!=null)payload.put("costOption",selected);if(discardRewards!=null)payload.set("discardRewards",discardRewards);
                preview=gameplayCooperation.previewFacility(themeConfig(session),rules,new TopicRouteEngine.State(),Collections.singletonMap(memberId,state),Collections.singletonMap(memberId,memberId),memberId,memberId,payload,clock.millis());
            } else {if(operation!=null && !"QUEST_RESOLVE".equals(operation))throw new IllegalArgumentException("此动作不提供装载预览");preview=gameplayCooperation.previewQuest(themeConfig(session),rules,new TopicRouteEngine.State(),Collections.singletonMap(memberId,state),Collections.singletonMap(memberId,memberId),memberId,memberId,questKey,choiceKey,discardRewards,costOption,"MEMBER:"+memberId,clock.millis());}
        }
        catch(IllegalStateException invalid){throw new ServiceException(invalid.getMessage(),409);}
        JourneyInitialState.redactHidden(rules,preview);preview.put("sessionId",sessionId).put("version",session.getVersion());
        return JSON.convertValue(preview,new TypeReference<Map<String,Object>>(){});
    }

    @Override
    @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public Map<String,Object> gameplayAction(Long memberId,Long sessionId,Integer version,String actionId,String operation,String contentId,String choiceKey) {
        return gameplayAction(memberId,sessionId,version,actionId,operation,contentId,choiceKey,null,null);
    }
    @Override
    @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public Map<String,Object> gameplayAction(Long memberId,Long sessionId,Integer version,String actionId,String operation,String contentId,String choiceKey,JsonNode discardRewards,String previewVersion) {
        return gameplayAction(memberId,sessionId,version,actionId,operation,contentId,choiceKey,discardRewards,previewVersion,null);
    }
    @Override
    @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public Map<String,Object> gameplayAction(Long memberId,Long sessionId,Integer version,String actionId,String operation,String contentId,String choiceKey,JsonNode discardRewards,String previewVersion,Integer costOption) {
        return gameplayActionInternal(memberId,sessionId,version,actionId,operation,contentId,choiceKey,discardRewards,previewVersion,costOption,null);
    }
    @Override
    @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public Map<String,Object> syncZeroHpSteps(Long memberId,Long sessionId,Integer version,String actionId,JsonNode weRun) {
        if(weRun==null || !weRun.path("episodeId").isTextual() || weRun.path("episodeId").asText().isEmpty() || weRun.path("episodeId").asText().length()>128)throw new IllegalArgumentException("请指定当前归零实例");
        return gameplayActionInternal(memberId,sessionId,version,actionId,"ZERO_HP_STEPS",weRun.path("episodeId").asText(),null,null,null,null,weRun);
    }
    private Map<String,Object> gameplayActionInternal(Long memberId,Long sessionId,Integer version,String actionId,String operation,String contentId,String choiceKey,JsonNode discardRewards,String previewVersion,Integer costOption,JsonNode weRun) {
        if("ZERO_HP_SYNC".equals(operation) && contentId==null)contentId="";
        if ("PLAN_FALLBACK".equals(operation)) {
            if (memberId == null || memberId <= 0 || sessionId == null || routeMapper.selectAccessibleById(sessionId, memberId) == null) throw new ServiceException("本局不存在或无权操作", 403);
            throw new ServiceException("该模式不支持投票", 409);
        }
        if(memberId==null || memberId<=0 || sessionId==null || actionId==null || !actionId.matches("[A-Za-z0-9:_-]{1,80}") || !Arrays.asList("QUEST_START","QUEST_CANCEL","QUEST_RESOLVE","RECOVER","FACILITY_USE","OPERATION_START","OPERATION_CANCEL","ZERO_HP_SYNC","ZERO_HP_STEPS").contains(operation) || contentId==null || contentId.length()>96 || (choiceKey!=null && choiceKey.length()>96))throw new IllegalArgumentException("动作参数不正确");
        TopicRouteSession session=routeMapper.selectAccessibleById(sessionId,memberId);
        if(session==null)throw new ServiceException("本局不存在或无权操作",403);
        if(!genericPlayer(session))throw new ServiceException("本局不是通用个人局",409);
        if(version==null || version<0)throw new IllegalArgumentException("必须提供期望版本");
        String key="gameplay:"+actionId,receiptId="gameplay-receipt:"+routeIntentFingerprint(Arrays.asList("gameplay:v2",key,version,operation,contentId,choiceKey,discardRewards,previewVersion,costOption,weRun));
        TopicRouteAction replay=routeMapper.selectActionByAction(sessionId,memberId,key);
        if(replay!=null){if(!receiptId.equals(replay.getReceiptId()))throw new ServiceException("动作标识已用于其他操作",409);return JSON.convertValue(validator.readTree(replay.getResponseJson()),new TypeReference<Map<String,Object>>(){});}
        requireScope(memberId,session.getTopicId(),session.getActivityId());
        boolean scoreActive = orientRouteSettlement != null
                && orientRouteSettlement.active(topicMapper.selectCmsTopicById(session.getTopicId()));
        if (scoreActive) {
            session = orientRouteSettlement.lock(session);
            TopicRouteAction winner = routeMapper.selectActionByAction(sessionId, memberId, key);
            if (winner != null) {
                if (!receiptId.equals(winner.getReceiptId())) throw new ServiceException("动作标识已用于其他操作",409);
                return JSON.convertValue(validator.readTree(winner.getResponseJson()),new TypeReference<Map<String,Object>>(){});
            }
        }
        TopicRouteSession current=currentSession(memberId,session.getTopicId(),session.getActivityId());
        if(current==null || !sessionId.equals(current.getId()) || !Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus()))throw new ServiceException("本局已结束",409);
        if(version==null || !version.equals(session.getVersion()))throw new ServiceException("本局已更新，请刷新",409);
        TopicRouteEngine.State state=readEngineState(session.getStateJson());JourneyRules rules=sessionRules(session);JsonNode config=themeConfig(session);
        String previousActive=state.getThemeRuntime().path("gameplay").path("activeQuest").asText();
        JsonNode activeOperation=state.getThemeRuntime().path("gameplay").path("activeOperation");
        if(activeOperation.isObject() && Arrays.asList("QUEST_START","RECOVER").contains(operation))throw new ServiceException("当前设施操作已预留余额，请先完成或取消操作",409);
        if(!previousActive.isEmpty() && Arrays.asList("RECOVER","FACILITY_USE","OPERATION_START").contains(operation))throw new ServiceException("当前任务已预留余额，请先完成或取消任务",409);
        ObjectNode result;
        if(!Arrays.asList("QUEST_RESOLVE","FACILITY_USE").contains(operation) && (discardRewards!=null || previewVersion!=null))throw new IllegalArgumentException("仅结算支持配包预览和放弃奖励");
        try {
            if("ZERO_HP_SYNC".equals(operation) || "ZERO_HP_STEPS".equals(operation)) {
                if(choiceKey!=null || costOption!=null)throw new IllegalArgumentException("归零同步不接受费用或任务选项");
                JsonNode snapshot=validator.readTree(session.getConfigSnapshotJson());
                if("ZERO_HP_STEPS".equals(operation)) {
                    if(weRun==null)throw new IllegalArgumentException("步数恢复须同步认证微信运动数据");
                    gameplaySurvival.syncZeroHpSteps(snapshot,rules,state,contentId,memberId,verifiedWeRunService.verify(memberId,weRun,clock.millis()),true,true,clock.millis());
                } else gameplaySurvival.syncZeroHp(snapshot,rules,state,clock.millis());
                result=playerZeroHpView(session,state);
            } else if("OPERATION_START".equals(operation) || "OPERATION_CANCEL".equals(operation)) {
                if(choiceKey!=null)throw new IllegalArgumentException("设施操作不接受配包选项");
                JsonNode facility=playerFacility(config,contentId);if(!facility.has("operationNodeId"))throw new IllegalArgumentException("该设施无需操作题组");
                Integer selected=costOption!=null?costOption:activeOperation.path("costOption").isInt()?Integer.valueOf(activeOperation.path("costOption").asInt()):null;
                if(activeOperation.path("costOption").isInt() && costOption!=null && costOption!=activeOperation.path("costOption").asInt())throw new IllegalStateException("设施操作实例不能更换费用组");
                if("OPERATION_START".equals(operation) || costOption!=null)gameplayTasks.costs(facility,selected);
                if("OPERATION_CANCEL".equals(operation)) {if(!activeOperation.isObject() || !contentId.equals(activeOperation.path("operationKey").asText()))throw new IllegalStateException("该设施操作尚未开始");state.getThemeRuntime().with("gameplay").remove("activeOperation");state.getThemeRuntime().with("operationFacts").remove("FACILITY:"+contentId);state.getThemeRuntime().with("verifiedNodes").remove(facility.path("operationNodeId").asText());result=JSON.createObjectNode().put("cancelledOperation",contentId);}
                else {
                    if(activeOperation.isObject() && !contentId.equals(activeOperation.path("operationKey").asText()))throw new IllegalStateException("请先完成或取消当前设施操作");
                    if(!playerArrived(state,facility.path("operationNodeId").asLong()))throw new IllegalStateException("请先扫码确认设施操作到达");
                    if(basicCompletionKind(validator.readTree(session.getConfigSnapshotJson()),facility.path("operationNodeId").asLong())!=null && freshBasicArrival(session,state,facility.path("operationNodeId").asLong())==null)throw new IllegalStateException("请重新完成本次设施操作的扫码或GPS玩法");
                    previewPlayerFacility(config,rules,state,memberId,facility,selected);
                    if(!activeOperation.isObject()) {ObjectNode started=state.getThemeRuntime().with("gameplay").putObject("activeOperation");started.put("operationKind","FACILITY").put("operationKey",contentId).put("nodeId",facility.path("operationNodeId").asLong()).put("startedActionId",key).put("verified",false);if(selected!=null)started.put("costOption",selected);started.putArray("effects");started.putObject("sources");state.getThemeRuntime().with("verifiedNodes").remove(facility.path("operationNodeId").asText());state.getThemeRuntime().with("operationFacts").remove("FACILITY:"+contentId);}
                    bindBasicCompletion(session,state,facility.path("operationNodeId").asLong());result=JSON.createObjectNode().put("activeOperation",contentId);
                }
            } else if("FACILITY_USE".equals(operation)) {
                if(choiceKey!=null)throw new IllegalArgumentException("设施不接受配包选项");
                JsonNode facility=playerFacility(config,contentId);
                Integer selected=costOption!=null?costOption:activeOperation.path("costOption").isInt()?Integer.valueOf(activeOperation.path("costOption").asInt()):null;
                if(activeOperation.path("costOption").isInt() && costOption!=null && costOption!=activeOperation.path("costOption").asInt())throw new IllegalStateException("设施操作实例不能更换费用组");
                gameplayTasks.costs(facility,selected);
                if(facility.has("operationNodeId")) {if(!activeOperation.isObject() || !contentId.equals(activeOperation.path("operationKey").asText()) || activeOperation.path("nodeId").asLong()!=facility.path("operationNodeId").asLong() || !activeOperation.path("verified").asBoolean())throw new IllegalStateException("设施操作题组尚未通过当前实例判定");state.getThemeRuntime().with("verifiedNodes").put(facility.path("operationNodeId").asText(),true);state.getThemeRuntime().with("operationFacts").set("FACILITY:"+contentId,activeOperation.deepCopy());}
                else if(activeOperation.isObject())throw new IllegalStateException("请先完成或取消当前设施操作");
                ObjectNode payload=JSON.createObjectNode().put("facilityKey",contentId);if(selected!=null)payload.put("costOption",selected);
                if(discardRewards!=null)payload.set("discardRewards",discardRewards);if(previewVersion!=null)payload.put("previewVersion",previewVersion);
                Map<Long,TopicRouteEngine.State> owners=new LinkedHashMap<>();owners.put(memberId,state);
                result=gameplayCooperation.settleFacility(config,rules,new TopicRouteEngine.State(),owners,Collections.singletonMap(memberId,memberId),memberId,memberId,payload,key,clock.millis());state=owners.get(memberId);
                if(facility.has("operationNodeId") && !"BLOCKED_COST".equals(result.path("status").asText())) {if(activeOperation.has("arrivalReceiptId"))consumeBasicArrival(state,facility.path("operationNodeId").asLong(),activeOperation.path("arrivalReceiptId").asText(),activeOperation.path("startedActionId").asText());state.getThemeRuntime().with("gameplay").remove("activeOperation");state.getThemeRuntime().with("operationFacts").remove("FACILITY:"+contentId);state.getThemeRuntime().with("verifiedNodes").remove(facility.path("operationNodeId").asText());}
            } else if("RECOVER".equals(operation)) {
                if(choiceKey!=null)throw new IllegalArgumentException("恢复不接受配包选项");
                if(costOption!=null)throw new IllegalArgumentException("恢复不接受任选费用组");
                result=gameplaySurvival.recover(config,rules,state,contentId,key);
            } else {
                if("QUEST_START".equals(operation) || "QUEST_RESOLVE".equals(operation)) {
                    JsonNode quest=gameplayTasks.quest(config,contentId);
                    if(!state.getThemeRuntime().path("gameplay").path("slots").has(quest.path("settlementSlot").asText()) && (!playerArrived(state,quest.path("nodeId").asLong()) || basicCompletionKind(validator.readTree(session.getConfigSnapshotJson()),quest.path("nodeId").asLong())!=null && freshBasicArrival(session,state,quest.path("nodeId").asLong())==null))throw new ServiceException("请先完成本局对应扫码或GPS到达",409);
                }
                result=gameplayCooperation.ownerQuest(config,rules,state,memberId,contentId,operation,choiceKey,key,"MEMBER:"+memberId,clock.millis(),discardRewards,previewVersion,costOption);
            }
        } catch(IllegalStateException invalid){throw new ServiceException(invalid.getMessage(),409);}
        if("QUEST_START".equals(operation) && previousActive.isEmpty() && contentId.equals(state.getThemeRuntime().path("gameplay").path("activeQuest").asText())) {state.getThemeRuntime().with("gameplay").put("activeQuestReceiptId",key);bindBasicCompletion(session,state,gameplayTasks.quest(config,contentId).path("nodeId").asLong());}
        if("QUEST_RESOLVE".equals(operation) && !"BLOCKED_COST".equals(result.path("status").asText()) && state.getThemeRuntime().path("gameplay").has("activeQuestArrivalReceiptId"))consumeBasicArrival(state,gameplayTasks.quest(config,contentId).path("nodeId").asLong(),state.getThemeRuntime().path("gameplay").path("activeQuestArrivalReceiptId").asText(),key);
        if("QUEST_CANCEL".equals(operation) || "QUEST_RESOLVE".equals(operation) && !"BLOCKED_COST".equals(result.path("status").asText()))state.getThemeRuntime().with("gameplay").remove(Arrays.asList("activeQuestReceiptId","activeQuestArrivalReceiptId"));
        syncTheme(session,state);
        session.setStateJson(write(state));session.setCurrentNodeId(state.getCurrentNodeId());session.setRecommendedNodeId(state.getRecommendedNodeId());session.setUpdateTime(new Date(clock.millis()));
        TopicRouteAction action=new TopicRouteAction();action.setSessionId(sessionId);action.setMemberId(memberId);action.setNodeId(0L);action.setActionId(key);action.setReceiptId(receiptId);action.setOutcomeCode(operation);action.setResponseJson("{}");action.setCreateTime(new Date(clock.millis()));
        try{if(routeMapper.insertAction(action)!=1)throw new IllegalStateException("动作回执创建失败");}
        catch(DuplicateKeyException duplicate){TopicRouteAction winner=routeMapper.selectActionByAction(sessionId,memberId,key);if(winner==null || !receiptId.equals(winner.getReceiptId()))throw new ServiceException("动作标识已用于其他操作",409);return JSON.convertValue(validator.readTree(winner.getResponseJson()),new TypeReference<Map<String,Object>>(){});}
        if (scoreActive) orientRouteSettlement.beforePersist(session, state);
        if(routeMapper.updateStateCas(session)!=1)throw new ServiceException("本局已更新，请刷新",409);
        if (scoreActive) orientRouteSettlement.complete(session, state);
        session.setVersion(version+1);Map<String,Object> view=themeViewOf(session);
        JourneyInitialState.redactHidden(rules,result);view.put("settlement",JSON.convertValue(result,new TypeReference<Map<String,Object>>(){}));
        String savedResponse=write(view);
        if(routeMapper.updateActionResponse(action.getId(),savedResponse)!=1)throw new IllegalStateException("动作回执保存失败");
        return JSON.convertValue(validator.readTree(savedResponse),new TypeReference<Map<String,Object>>(){});
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Map<String,Object> themeAction(Long memberId,Long sessionId,Integer version,String actionId,String operation,String contentId) {
        return themeAction(memberId,sessionId,version,actionId,operation,contentId,null);
    }
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Map<String,Object> themeAction(Long memberId,Long sessionId,Integer version,String actionId,String operation,String contentId,JsonNode discardOwned) {
        if(memberId==null || memberId<=0 || sessionId==null || actionId==null || !actionId.matches("[A-Za-z0-9:_-]{1,80}"))throw new IllegalArgumentException("动作参数不正确");
        TopicRouteSession session=routeMapper.selectAccessibleById(sessionId,memberId);
        if(session==null)throw new ServiceException("本局不存在或无权操作",403);
        if (operation == null || !java.util.Arrays.asList("USE","DISCARD","RECEIVE","END","RESCUE","CHAPTER_START","CHAPTER_END").contains(operation) || (!"END".equals(operation) && !"RESCUE".equals(operation) && (contentId == null || contentId.length() > 96))) throw new IllegalArgumentException("动作参数不正确");
        if(version==null || version<0)throw new IllegalArgumentException("必须提供期望版本");
        String key="theme:"+actionId;
        String receiptId="theme-receipt:"+routeIntentFingerprint(Arrays.asList("theme:v2",key,version,operation,contentId,discardOwned));
        TopicRouteAction replay=routeMapper.selectActionByAction(sessionId,memberId,key);
        if(replay!=null){
            if(!operation.equals(replay.getOutcomeCode()) || !receiptId.equals(replay.getReceiptId())) throw new ServiceException("动作标识已用于其他操作",409);
            return JSON.convertValue(validator.readTree(replay.getResponseJson()),new TypeReference<Map<String,Object>>(){});
        }
        if ("END".equals(operation) && readEngineState(session.getStateJson()).getThemeRuntime().has("result")) return themeViewOf(session);
        Scope scope=requireScope(memberId,session.getTopicId(),session.getActivityId(),true);
        boolean scoreActive = orientRouteSettlement != null && orientRouteSettlement.active(scope.topic);
        if (scoreActive) session = orientRouteSettlement.lock(session);
        Long actionNodeId=session.getCurrentNodeId();
        TopicRouteSession current=currentSession(memberId,session.getTopicId(),session.getActivityId());
        boolean restricted="RECEIVE".equals(operation) && Integer.valueOf(TopicRouteSession.STATUS_COMPLETED).equals(session.getStatus());
        if(!restricted && (current==null || !sessionId.equals(current.getId()) || !Integer.valueOf(0).equals(session.getStatus())))throw new ServiceException("本局已结束");
        if(discardOwned!=null && !restricted)throw new IllegalArgumentException("只能在已完成局的待领取结算中丢弃持有物资");
        if(version==null || !version.equals(session.getVersion()))throw new ServiceException("本局已更新，请刷新",409);
        if (genericPlayer(session) && Arrays.asList("CHAPTER_START","CHAPTER_END","RESCUE").contains(operation)) throw new ServiceException("通用个人局请使用任务或恢复动作",409);
        JsonNode config=themeConfig(session);if(!config.isObject())throw new ServiceException("本局未启用主题配置");
        if(genericPlayer(session) && (!readEngineState(session.getStateJson()).getThemeRuntime().path("gameplay").path("activeQuest").asText().isEmpty() || readEngineState(session.getStateJson()).getThemeRuntime().path("gameplay").path("activeOperation").isObject()) && Arrays.asList("USE","DISCARD").contains(operation))throw new ServiceException("当前任务已预留物资，请先完成或取消任务",409);
        TopicRouteEngine.State state=readEngineState(session.getStateJson()); JourneyRules rules=journeyRules(session,scope.topic);
        if(!restricted && state.getThemeRuntime().path("awaitingRescue").asBoolean() && !"RESCUE".equals(operation) && !"END".equals(operation))throw new ServiceException("请先选择救援或结束本局");
        if("CHAPTER_START".equals(operation) || "CHAPTER_END".equals(operation)) {
            JsonNode ch=null;for(JsonNode row:config.path("chapters"))if(String.valueOf(row.path("index").asInt()).equals(contentId))ch=row;
            if(ch==null || !chapterReached(ch.path("binding"),state))throw new ServiceException("尚未到达这个章节");
            if("CHAPTER_END".equals(operation)) {
                if(!chapterNodesDone(ch.path("binding"),state) || (ch.path("binding").path("opening").asBoolean() && !state.getTags().contains("sys.opening.done")))throw new ServiceException("请先完成本章玩法");
                themeEngine.chapterEnd(config,state,rules,ch,clock.millis());
            } else themeEngine.chapterStart(config,state,rules,ch,clock.millis());
        }
        else if("RESCUE".equals(operation))applyThemeRescue(session,state,rules);
        else if("USE".equals(operation))themeEngine.use(config,state,rules,contentId);
        else if("DISCARD".equals(operation))themeEngine.discard(config,state,rules,contentId);
        else if("RECEIVE".equals(operation)){
            if(restricted){if(state.getThemeRuntime().path("gameplay").has("activeQuest") || state.getThemeRuntime().path("gameplay").has("activeOperation"))throw new ServiceException("持有物资仍有未释放的预留");themeEngine.claimPending(config,state,contentId,discardOwned);}
            else {themeEngine.receive(config,state,rules,contentId,true);themeEngine.events(config,state,rules);}
        }
        else if("END".equals(operation)){themeEngine.terminal(config,state,rules,"ABANDONED",clock.millis());freezeThemeStory(session,state);}
        else throw new IllegalArgumentException("不支持此操作");
        if(!restricted)syncTheme(session,state, key, "USE".equals(operation) ? JourneyStatLinks.Reason.ITEM_USE
                : "CHAPTER_END".equals(operation) ? JourneyStatLinks.Reason.CHAPTER_COST : null);
        session.setStateJson(write(state));session.setCurrentNodeId(state.getCurrentNodeId());session.setRecommendedNodeId(state.getRecommendedNodeId());
        if("ENDED".equals(state.getStatus())){session.setStatus(TopicRouteSession.STATUS_ENDED);session.setCompletedAt(new Date(clock.millis()));}
        session.setUpdateTime(new Date(clock.millis()));
        TopicRouteAction action=new TopicRouteAction();action.setSessionId(sessionId);action.setMemberId(memberId);action.setNodeId(actionNodeId == null ? 0L : actionNodeId);action.setActionId(key);action.setReceiptId(receiptId);action.setOutcomeCode(operation);action.setResponseJson("{}");action.setCreateTime(new Date(clock.millis()));
        try { if(routeMapper.insertAction(action)!=1)throw new IllegalStateException("动作回执创建失败"); }
        catch(DuplicateKeyException duplicate) {
            TopicRouteAction winner=routeMapper.selectActionByAction(sessionId,memberId,key);
            if(winner==null || !operation.equals(winner.getOutcomeCode()) || !action.getReceiptId().equals(winner.getReceiptId()))throw new ServiceException("动作标识已用于其他操作",409);
            return JSON.convertValue(validator.readTree(winner.getResponseJson()),new TypeReference<Map<String,Object>>(){});
        }
        if (scoreActive) orientRouteSettlement.beforePersist(session, state);
        if(routeMapper.updateStateCas(session)!=1)throw new ServiceException("本局已更新，请刷新",409);
        session.setVersion(version+1);
        if (scoreActive) orientRouteSettlement.complete(session, state);
        Map<String,Object> result=themeViewOf(session);
        if(routeMapper.updateActionResponse(action.getId(),write(result))!=1)throw new IllegalStateException("动作回执保存失败");return result;
    }
    @Override
    public List<Map<String,Object>> themeHistory(Long memberId,Long topicId) {
        return themeHistory(memberId,topicId,null,100);
    }
    @Override
    @Transactional(readOnly=true)
    public List<Map<String,Object>> themeHistory(Long memberId,Long topicId,Long beforeSessionId,Integer limit) {
        if(memberId==null || memberId<=0)throw new ServiceException("请先登录",403);
        int size=limit==null?100:limit;
        if(topicId==null || topicId<=0 || size<1 || size>100 || (beforeSessionId!=null && beforeSessionId<=0))throw new IllegalArgumentException("历史参数不正确");
        List<Map<String,Object>> history=new ArrayList<>();
        for(TopicRouteSession session:routeMapper.selectHistoryPage(memberId,topicId,beforeSessionId,size))if(themeConfig(session).isObject())history.add(themeViewOf(session));
        return history;
    }
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Map<String,Object> replayTheme(Long memberId,Long sessionId) {
        if(memberId==null || memberId<=0 || sessionId==null || sessionId<=0)throw new IllegalArgumentException("参数不正确");
        TopicRouteSession previous=routeMapper.selectAccessibleById(sessionId,memberId);
        if(previous==null)throw new ServiceException("本局不存在或无权操作",403);
        Scope scope=requireReplayScope(previous,true);
        String instance="replay:"+sessionId;
        TopicRouteSession child=routeMapper.selectByOwner(previous.getActivityId(),previous.getTopicId(),memberId,instance);
        if(child!=null)return themeViewOf(child);
        List<CmsTopicNode> liveNodes=nodes(previous.getTopicId());if(liveNodes.isEmpty())throw new ServiceException("主题没有可玩节点");
        boolean branch = TopicRouteState.BRANCH_GRAPH.equals(routeMode(scope));
        Set<Long> replayNodes = new LinkedHashSet<Long>(); for(CmsTopicNode n:liveNodes)replayNodes.add(n.getId());
        int index=routeMapper.countRuns(previous.getActivityId(),previous.getTopicId(),memberId);
        JourneyTemplateProbe replayProbe=publicationCompiler().probe(liveNodes);
        JsonNode published = scope.topic.getPublishedRouteGraphJson() == null ? null : validator.readTree(scope.topic.getPublishedRouteGraphJson());
        String snapshot = published != null && published.path("runtimeSnapshotVersion").asInt() == 1
                ? freezeJourney(published.toString(), scope.topic, liveNodes, replayProbe.configs(), replayProbe.templatesById(), index)
                : freezeJourney(branch ? validator.validateAndNormalize(scope.topic.getPublishedRouteGraphJson(),replayNodes) : linearSnapshot(liveNodes),scope.topic,liveNodes,replayProbe.configs(),replayProbe.templatesById(),index);
        ObjectNode root=(ObjectNode)validator.readTree(snapshot);
        ObjectNode frozen=(ObjectNode)root.get("themeConfig");
        TopicRouteEngine.State initial=branch ? engine.initial(write(root)) : new TopicRouteEngine.State();initial.setStatus("ACTIVE");
        if(!branch){initial.setCurrentNodeId(liveNodes.get(0).getId());initial.setRecommendedNodeId(liveNodes.get(0).getId());}
        if(!branch)for(CmsTopicNode node:liveNodes)initial.getNodeStates().put(node.getId(),node.getId().equals(initial.getCurrentNodeId())?TopicRouteState.PLAYABLE:TopicRouteState.DISCOVERED_LOCKED);
        initial.getVisitedNodeIds().add(initial.getCurrentNodeId());JourneyRules replayRules = FrozenAttributeDefinitions.rules(root, scope.topic.getPublishedConfigVersion());applyJourneyInitials(replayRules,initial);seedCharacter(memberId,previous.getTopicId(),replayRules,initial,root.toString());themeEngine.initialize(frozen,initial);
        CmsTopicNode firstNode=findNode(liveNodes,initial.getCurrentNodeId());
        Map<String,Object> stageRest=genericConfig(frozen)?null:applyStageRest(null,memberId,initial,replayRules,firstNode,false);
        child=new TopicRouteSession();child.setMemberId(memberId);child.setTopicId(previous.getTopicId());child.setActivityId(previous.getActivityId());child.setPlayInstanceId(instance);
        child.setConfigSnapshotJson(write(root));child.setStateJson(write(initial));child.setConfigVersion(scope.topic.getPublishedConfigVersion());child.setStatus(0);child.setVersion(0);child.setSeed(seedSupplier.getAsLong());
        child.setCurrentNodeId(initial.getCurrentNodeId());child.setRecommendedNodeId(initial.getRecommendedNodeId());child.setCreateTime(new Date(clock.millis()));child.setUpdateTime(child.getCreateTime());
        if(genericPlayer(child))initializeTheme(child,initial);
        syncTheme(child,initial);child.setStateJson(write(initial));child.setCurrentNodeId(initial.getCurrentNodeId());child.setRecommendedNodeId(initial.getRecommendedNodeId());
        try {if(routeMapper.insertSession(child)!=1)throw new IllegalStateException("重玩创建失败");}
        catch(DuplicateKeyException duplicate){child=routeMapper.selectByOwner(previous.getActivityId(),previous.getTopicId(),memberId,instance);if(child==null)throw duplicate;}
        if(themeBroadcastService!=null)themeBroadcastService.enqueue(child,readEngineState(child.getStateJson()));
        if(stageRest!=null)recordStageRestAction(child,memberId,firstNode,stageRest);
        return themeViewOf(child);
    }

    private Scope requireReplayScope(TopicRouteSession previous) {
        return requireReplayScope(previous,false);
    }
    /** newUse=true 仅用于真正开重玩局：锁读当前报名；视图里的 replayAllowed 只读判断不加锁。 */
    private Scope requireReplayScope(TopicRouteSession previous,boolean newUse) {
        if(Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(previous.getStatus()))throw new ServiceException("请先结束当前局");
        Scope scope=requireScope(previous.getMemberId(),previous.getTopicId(),previous.getActivityId(),newUse);
        ObjectNode live=ThemeRuntimeConfig.parse(scope.topic.getPublishedThemeRuntimeConfigJson());
        if(live==null || !live.path("replayEnabled").asBoolean())throw new ServiceException("此主题未开放重玩");
        if(routeMapper.selectByOwner(previous.getActivityId(),previous.getTopicId(),previous.getMemberId(),"replay:"+previous.getId())==null) {
            TopicRouteSession current=currentSession(previous.getMemberId(),previous.getTopicId(),previous.getActivityId());
            if(current!=null && !previous.getId().equals(current.getId()))throw new ServiceException("请继续或查看最新一局");
        }
        return scope;
    }

    private static final class Scope {
        private final Long memberId; private final Long activityId; private final CmsTopic topic;
        private final List<CmsRegistration> registrations;
        private String originalPlayInstance;
        TopicRouteSession originalSession;
        private Scope(Long memberId, Long activityId, CmsTopic topic, List<CmsRegistration> registrations) {
            this.memberId = memberId; this.activityId = activityId; this.topic = topic;
            this.registrations = registrations;
        }
    }

}
