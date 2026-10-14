package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.support.TopicRouteSessionScope;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
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
import com.chengyinhub.business.service.support.ChapterNarrativeBlocks;
import com.chengyinhub.business.domain.JourneyStory;
import com.chengyinhub.business.util.ChapterBlockReadSupport;
import com.chengyinhub.business.service.support.JourneyCheckActionId;
import com.chengyinhub.business.service.support.NodeAccessPolicy;
import com.chengyinhub.business.service.support.NodeJourneySnapshot;
import com.chengyinhub.business.service.support.RouteReachabilityProvider;
import com.chengyinhub.business.service.support.JourneyInitialState;
import com.chengyinhub.business.service.support.TopicCompletionPolicy;
import com.chengyinhub.business.service.support.TopicCompletionRulePolicy;
import com.chengyinhub.business.service.support.SensorChallengePolicy;
import com.chengyinhub.common.constant.RewardPolicy;
import com.chengyinhub.business.service.support.TopicRouteCandidatePolicy;
import com.chengyinhub.business.service.support.TopicRouteConfigValidator;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.chengyinhub.business.service.support.ThemeRuntimeConfig;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
    /** 检定运行时系统键:不进键注册表,只由服务端写本局 state(共享契约 v2/v3)。 */
    private static final String SYS_HP = "sys.hp";
    private static final String SYS_LUCK = "sys.luck";
    /** 思维阁抬高后的幸运上限;没有这个变量时以配置值为准。 */
    private static final String SYS_LUCK_MAX = "sys.luck.max";
    /** 念头进度变量前缀;想通了再往 tags 里写 {@code thought.<key>.done}。 */
    private static final String THOUGHT_PREFIX = "thought.";
    /** 上一次同步读到的微信运动今日步数,用来算这一段走了多少。 */
    private static final String SYS_STEPS_LAST = "sys.steps.last";
    private static final String SYS_STEPS_DAY = "sys.steps.day";
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
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public TopicRouteState state(Long memberId, Long topicId, Long activityId) {
        Scope scope = requireScope(memberId, topicId, activityId);
        if (!TopicRouteState.BRANCH_GRAPH.equals(routeMode(scope))) {
            // 老线性主题照旧无局;配了旅程规则/块状态的线性主题建局,与分支共用 state_json。
            List<CmsTopicNode> liveNodes = nodes(topicId);
            JourneyTemplateProbe probe = new JourneyTemplateProbe(liveNodes);
            if (!journeyConfigured(scope, probe)) return linearState(scope, liveNodes);
            return toLinearView(ensureJourneySession(scope, probe), scope, liveNodes);
        }
        TopicRouteSession session = ensureBranchSession(scope);
        return toAvailableView(session, nodes(topicId));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public TopicRouteState completeOpening(Long memberId, Long topicId, Long activityId, Long chapterId,
                                          Integer expectedVersion) {
        Scope scope = requireScope(memberId, topicId, activityId,true);
        TopicRouteSession session = routeMapper.selectByOwner(scope.activityId, topicId, memberId, DEFAULT_INSTANCE);
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
            if (routeMapper.updateStateCas(session) != 1) {
                throw new ServiceException("路线状态已更新，请刷新后重试", HttpStatus.CONFLICT);
            }
            session.setVersion(session.getVersion() + 1);
        }
        TopicRouteState view = TopicRouteState.BRANCH_GRAPH.equals(routeMode(scope))
                ? toAvailableView(session, nodes(topicId)) : toLinearView(session, scope, nodes(topicId));
        grantOpeningAward(memberId, topicId, scope.activityId, view);
        return view;
    }

    /**
     * 开局完成时整部主题也通关了就发奖。发奖住在这里而不是 controller:一是任何
     * completeOpening 的调用方都该拿到奖励,二是奖励异常要穿出本方法的事务一起回滚
     * (controller 不能自己开事务再捕获业务异常,否则提交期会退化成 UnexpectedRollbackException)。
     */
    private void grantOpeningAward(Long memberId, Long topicId, Long activityId, TopicRouteState view) {
        Map<String, Object> opening = view.getOpening();
        if (topicCompletionAwardService == null || opening == null
                || !Boolean.TRUE.equals(opening.get("themeComplete"))) {
            return;
        }
        if (RewardPolicy.XP_BADGE_ONLY.name().equals(opening.get("rewardPolicy"))) {
            topicCompletionAwardService.grantOnce(memberId, topicId, activityId, null, RewardPolicy.XP_BADGE_ONLY);
        } else {
            topicCompletionAwardService.grantOnce(memberId, topicId, activityId, null);
        }
    }

    private JsonNode openingSnapshot(TopicRouteSession session) {
        if (session == null) return null;
        try { return JSON.readTree(session.getConfigSnapshotJson()).get("openingChapter"); }
        catch (Exception e) { throw new IllegalStateException("开局快照不可用", e); }
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void requireOpeningAccess(Long memberId, Long topicId, Long activityId, Long nodeId) {
        TopicRouteSession session = routeMapper.selectByOwner(activityId == null ? 0L : activityId,
                topicId, memberId, DEFAULT_INSTANCE);
        if (session == null) {
            CmsTopic topic = topicMapper.selectCmsTopicById(topicId);
            if (topic == null || JourneyStory.openingChapterId(topic.getJourneyStoryJson()) == null) return;
            state(memberId, topicId, activityId);
            session = routeMapper.selectByOwner(activityId == null ? 0L : activityId, topicId, memberId, DEFAULT_INSTANCE);
            // 查不到本局 != 这个主题没有开局。建完还读不到就拒,别把读失败当成放行理由。
            if (session == null) throw new IllegalStateException("请先完成开局流程");
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

        // 得到念头 = 在故事里读到了那个 thought 块。没得到的不替玩家开始累积。
        Set<String> known = new LinkedHashSet<String>();
        for (JsonNode row : rules.getThoughts()) known.add(row.path("key").asText());
        if (claim != null && !claim.isEmpty()) {
            Set<String> available = claimableThoughts(session, state);
            for (String key : claim) {
                String var = THOUGHT_PREFIX + key;
                if (!known.contains(key)) throw new IllegalArgumentException("念头不存在");
                if (!state.getVariables().containsKey(var) && !state.getTags().contains(var + ".done")) {
                    if (!available.contains(key)) throw new IllegalArgumentException("尚未读到这个念头");
                    state.getVariables().put(var, Integer.valueOf(0));
                }
            }
        }

        if (todaySteps >= 0) {
            Number last=state.getVariables().get(SYS_STEPS_LAST),day=state.getVariables().get(SYS_STEPS_DAY);
            int delta=day!=null && day.longValue()==readingDay && last!=null?Math.max(0,todaySteps-last.intValue()):0;
            themeEngine.chapterSteps(state,delta);
            accumulateThoughtSteps(state, rules, todaySteps, readingDay);
        }

        session.setStateJson(writeSettled(session, state));
        session.setUpdateTime(new Date());
        if (routeMapper.updateStateCas(session) != 1) {
            throw new ServiceException("路线状态已更新，请刷新后重试", HttpStatus.CONFLICT);
        }
        session.setVersion(session.getVersion() + 1);
        return routeView(session, scope);
    }

    /** 只认建局冻结的章节、节点顺序和条件；旧局缺证据时不接受客户端认领。 */
    private Set<String> claimableThoughts(TopicRouteSession session, TopicRouteEngine.State state) {
        Set<String> keys = new LinkedHashSet<String>();
        final JsonNode root;
        try { root = JSON.readTree(session.getConfigSnapshotJson()); }
        catch (Exception invalid) { throw new IllegalStateException("故事快照不可用", invalid); }
        JsonNode chapters = root == null ? null : root.get("thoughtChapters");
        if (chapters == null || !chapters.isArray()) return keys;
        boolean openingPending = root.has("openingChapter") && !state.getTags().contains("sys.opening.done");
        for (JsonNode chapter : chapters) {
            if (openingPending && !chapter.path("opening").asBoolean(false)) continue;
            boolean reached = chapter.path("opening").asBoolean(false);
            for (JsonNode id : chapter.path("nodeIds")) {
                if (Long.valueOf(id.asLong()).equals(state.getCurrentNodeId())
                        || state.getCompletedNodeIds().contains(id.asLong())) reached = true;
            }
            // 无节点的过场依靠此前必经节点的完成事实证明已到达。
            if (!reached && chapter.path("nodeIds").size() == 0) {
                reached = true;
                for (JsonNode id : chapter.path("requiredBefore")) {
                    if (!state.getCompletedNodeIds().contains(id.asLong())) reached = false;
                }
            }
            if (!reached) continue;
            List<Object> blocks = JSON.convertValue(chapter.path("blocks"), new TypeReference<List<Object>>() { });
            for (Object item : ChapterBlockReadSupport.projectForPlayer(blocks,
                    state.getCompletedNodeIds(), state.getTags())) {
                if (!(item instanceof Map)) continue;
                Map<?, ?> block = (Map<?, ?>) item;
                if ("thought".equals(block.get("type")) && block.get("thoughtKey") instanceof String) {
                    keys.add((String) block.get("thoughtKey"));
                }
            }
        }
        return keys;
    }

    /** 只累计当日单调增长的读数；日期推进才重置，旧局首次记录日期仅建立基线。 */
    private void accumulateThoughtSteps(TopicRouteEngine.State state, JourneyRules rules, int todaySteps, long day) {
        Number previousDay = state.getVariables().get(SYS_STEPS_DAY);
        Number last = state.getVariables().get(SYS_STEPS_LAST);
        if (previousDay != null && day < previousDay.longValue()) return;
        boolean sameDay = previousDay != null && day == previousDay.longValue();
        int delta = sameDay && last != null ? Math.max(0, todaySteps - last.intValue()) : 0;
        state.getVariables().put(SYS_STEPS_DAY, Long.valueOf(day));
        state.getVariables().put(SYS_STEPS_LAST,
                Integer.valueOf(sameDay && last != null ? Math.max(todaySteps, last.intValue()) : todaySteps));
        if (delta <= 0) return;
        for (JsonNode row : rules.getThoughts()) {
            if (!row.hasNonNull("need")) continue;              // 只靠选项想通的念头不走步数
            String var = THOUGHT_PREFIX + row.path("key").asText();
            if (state.getTags().contains(var + ".done")) continue;
            Number progress = state.getVariables().get(var);
            if (progress == null) continue;
            int need = row.path("need").asInt();
            int next = Math.min(need, progress.intValue() + delta);
            state.getVariables().put(var, Integer.valueOf(next));
            if (next >= need) markThoughtDone(state, rules, var);
        }
    }

    /**
     * 伏笔的条件结算(2026-09-22 用户裁决「伏笔不能只是微信步数,回答出特定选项和条件时也有」):
     * when 满足即得到、doneWhen 满足即想通(条件间任一满足)。没得到的不会被想通 —— 伏笔没埋就应验
     * 是剧情错乱。幂等:已得到的不重置进度,已想通的不重复加成。
     */
    private void settleThoughts(TopicRouteEngine.State state, JourneyRules rules) {
        for (JsonNode row : rules.getThoughts()) {
            String var = THOUGHT_PREFIX + row.path("key").asText();
            if (state.getTags().contains(var + ".done")) continue;
            boolean obtained = state.getVariables().containsKey(var);
            if (!obtained && row.has("when") && conditionPasses(row.get("when"), state)) {
                state.getVariables().put(var, Integer.valueOf(0));
                obtained = true;
            }
            if (obtained && row.has("doneWhen") && conditionPasses(row.get("doneWhen"), state)) {
                markThoughtDone(state, rules, var);
            }
        }
    }

    /** 想通了:打标签,幸运上限 +1。运行时上限可以超过配置值(2026-09-22 用户拍板)。 */
    private void markThoughtDone(TopicRouteEngine.State state, JourneyRules rules, String var) {
        if (!state.getTags().add(var + ".done")) return;
        state.getVariables().put(SYS_LUCK_MAX, Integer.valueOf(luckMaxOf(state, rules) + 1));
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
        for (JsonNode block : opening.path("blocks")) {
            if ("node".equals(block.path("type").asText())
                    && !state.getCompletedNodeIds().contains(block.path("nodeId").asLong())) {
                return block.path("nodeId").asLong();
            }
        }
        return null;
    }

    private boolean openingAllows(TopicRouteSession session, Long nodeId) {
        JsonNode opening = openingSnapshot(session);
        if (opening == null) return true;
        TopicRouteEngine.State state = readEngineState(session.getStateJson());
        // nodeId 为空 = 没带节点上下文,开局没做完就不是开局里那一个,按拒绝算。
        return state.getTags().contains("sys.opening.done")
                || (nodeId != null && nodeId.equals(nextOpeningNode(opening, state)));
    }

    private int statusOf(TopicRouteEngine.State state) { return "ENDED".equals(state.getStatus()) ? TopicRouteSession.STATUS_ENDED : "COMPLETED".equals(state.getStatus()) ? TopicRouteSession.STATUS_COMPLETED : TopicRouteSession.STATUS_RUNNING; }

    private void keepOpeningRunning(TopicRouteSession session, TopicRouteEngine.State state) {
        if (!state.getThemeRuntime().has("result") && openingSnapshot(session) != null && !state.getTags().contains("sys.opening.done")) {
            session.setStatus(TopicRouteSession.STATUS_RUNNING);
            session.setCompletedAt(null);
        }
    }

    /**
     * 思维阁:把玩家**已经得到**的念头连同进度挂到视图上。
     *
     * <p>没得到的念头一个都不下发 —— 玩家还没在故事里读到那个 thought 块,提前看见名字就是剧透
     * (与未完成节点之后的正文一样的口径)。</p>
     */
    private void appendThoughts(TopicRouteState view, TopicRouteSession session, TopicRouteEngine.State state) {
        JourneyRules rules = sessionRules(session);
        view.setThoughtCount(rules.getThoughts().size());
        if (rules.getThoughts().size() == 0) return;
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (JsonNode row : rules.getThoughts()) {
            String key = row.path("key").asText();
            String var = THOUGHT_PREFIX + key;
            boolean done = state.getTags().contains(var + ".done");
            Number progress = state.getVariables().get(var);
            if (!done && progress == null) continue;          // 还没得到
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("key", key);
            item.put("name", row.path("name").asText());
            if (row.hasNonNull("desc")) item.put("desc", row.path("desc").asText());
            int need = row.path("need").asInt(0);           // 只靠选项想通的念头 need=0,菜单不显示步数
            item.put("need", need);
            item.put("progress", done ? need : (progress == null ? 0 : progress.intValue()));
            item.put("done", done);
            rows.add(item);
        }
        if (!rows.isEmpty()) view.setThoughts(rows);
    }

    private void appendOpening(TopicRouteState view, TopicRouteSession session, TopicRouteEngine.State state) {
        JsonNode frozen = openingSnapshot(session);
        if (frozen == null) return;
        Map<String, Object> opening = JSON.convertValue(frozen, new TypeReference<Map<String, Object>>() { });
        boolean complete = state.getTags().contains("sys.opening.done");
        opening.put("complete", complete);
        opening.put("ready", nextOpeningNode(frozen, state) == null);
        boolean themeComplete = TopicRouteState.COMPLETED.equals(view.getStatus());
        if (complete && !TopicRouteState.BRANCH_GRAPH.equals(view.getRouteMode())) {
            try {
                JsonNode snapshot = JSON.readTree(session.getConfigSnapshotJson());
                JsonNode nodes = snapshot.path(NodeJourneySnapshot.NODE_ANSWERS_KEY);
                int done = 0;
                for (Long id : state.getCompletedNodeIds()) if (nodes.has(String.valueOf(id))) done++;
                themeComplete = completionRulePolicy.isCompleted(
                        snapshot.path("topicCompleteRule").asText(null), nodes.size(), done);
            } catch (Exception e) { throw new IllegalStateException("通关规则不可用", e); }
        }
        opening.put("themeComplete", complete && themeComplete);
        JsonNode lastGame = null;
        for (JsonNode block : frozen.path("blocks")) {
            if ("node".equals(block.path("type").asText())) {
                lastGame = NodeJourneySnapshot.answer(session.getConfigSnapshotJson(), block.path("nodeId").asLong());
            }
        }
        opening.put("rewardPolicy", lastGame != null && SensorChallengePolicy.isPhotoFilter(
                lastGame.path("validationMethod").asInt(), lastGame.path("sensorType").asText())
                ? RewardPolicy.XP_BADGE_ONLY.name() : RewardPolicy.FULL.name());
        List<Object> blocks = JSON.convertValue(frozen.path("blocks"), new TypeReference<List<Object>>() { });
        opening.put("blocks", ChapterBlockReadSupport.projectForPlayer(blocks, state.getCompletedNodeIds(), state.getTags()));
        view.setOpening(opening);
        if (complete) return;
        Long next = nextOpeningNode(frozen, state);
        view.setStatus("ACTIVE");
        view.setCurrentNodeId(next); view.setRecommendedNodeId(next);
        for (Map.Entry<Long, String> node : view.getNodeStates().entrySet()) {
            if (!TopicRouteState.COMPLETED.equals(node.getValue())) {
                node.setValue(node.getKey().equals(next) ? TopicRouteState.PLAYABLE : TopicRouteState.HIDDEN);
            }
        }
        if (next != null) view.getNodeStates().put(next, TopicRouteState.PLAYABLE);
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
        Scope scope = requireScope(memberId, topicId, activityId,true);
        if (!TopicRouteState.BRANCH_GRAPH.equals(routeMode(scope))) {
            List<CmsTopicNode> liveNodes = nodes(topicId);
            JourneyTemplateProbe probe = new JourneyTemplateProbe(liveNodes);
            if (!journeyConfigured(scope, probe)) return linearState(scope, liveNodes);
            return advanceLinear(scope, ensureJourneySession(scope, probe),
                    expectedVersion, routeActionId, outcome);
        }
        TopicRouteSession session = ensureBranchSession(scope);
        JsonNode opening = openingSnapshot(session);
        TopicRouteEngine.State before = readEngineState(session.getStateJson());
        if (opening != null && !before.getTags().contains("sys.opening.done")
                && outcome.getNodeId().equals(nextOpeningNode(opening, before))
                && !outcome.getNodeId().equals(before.getCurrentNodeId())) {
            return advanceLinear(scope, session, expectedVersion, routeActionId, outcome);
        }
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
        syncTheme(session, next);
        themeEngine.signal(themeConfig(session), next, rules, "NODE", outcome.getNodeId(), "", "", 0, "node:" + outcome.getNodeId());
        session.setStateJson(writeSettled(session, next));
        session.setStatus(statusOf(next));
        keepOpeningRunning(session, next);
        session.setCurrentNodeId(next.getCurrentNodeId());
        session.setRecommendedNodeId(next.getRecommendedNodeId());
        if (session.getStatus() == TopicRouteSession.STATUS_COMPLETED) session.setCompletedAt(new Date());
        session.setUpdateTime(new Date());
        int oldVersion = session.getVersion();
        if (routeMapper.updateStateCas(session) != 1) throw new ServiceException("路线状态已更新，请刷新后重试", HttpStatus.CONFLICT);
        session.setVersion(oldVersion + 1);
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
            JourneyTemplateProbe probe = new JourneyTemplateProbe(nodes);
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
            if (!openingAllows(journeySession, nodeId)) return false;
            JsonNode blockConfig = nodeBlockConfig(journeySession, requested);
            JsonNode requires = blockConfig == null ? null : blockConfig.get("requires");
            if (requires != null && requires.isArray() && requires.size() > 0
                    && !engine.conditionsPass(requires, readEngineState(journeySession.getStateJson()))) {
                return false;
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
        String actionId = checkActionId(nodeId, checkId, STEP_ROLL);
        TopicRouteSession session = requireCheckSession(memberId, topicId, activityId);
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
        String actionId = checkActionId(nodeId, checkId, STEP_REROLL);
        TopicRouteSession session = requireCheckSession(memberId, topicId, activityId);
        PlayCheckReceipt replayed = replayCheck(session, memberId, actionId);
        if (replayed != null) return replayed;
        CheckContext context = checkContext(memberId, topicId, session, nodeId, checkId);
        if (findCheckAction(session, memberId, nodeId, checkId, STEP_ROLL) == null) {
            throw new IllegalStateException("请先掷骰");
        }
        if (findCheckAction(session, memberId, nodeId, checkId, JourneyCheckActionId.STEP_SETTLE) != null) {
            throw new IllegalStateException("该检定已结算");
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
        String actionId = checkActionId(nodeId, checkId, JourneyCheckActionId.STEP_SETTLE);
        TopicRouteSession session = requireCheckSession(memberId, topicId, activityId);
        PlayCheckReceipt replayed = replayCheck(session, memberId, actionId);
        if (replayed != null) return replayed;
        CheckContext context = checkContext(memberId, topicId, session, nodeId, checkId);
        TopicRouteAction rollAction = findCheckAction(session, memberId, nodeId, checkId, STEP_ROLL);
        if (rollAction == null) throw new IllegalStateException("请先掷骰");
        TopicRouteAction rerollAction = findCheckAction(session, memberId, nodeId, checkId, STEP_REROLL);
        PlayCheckReceipt last = readReceipt(rerollAction != null ? rerollAction : rollAction);
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
                failCostLabel = failCostTagLabel(topicId, failCostTag);
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
        TopicRouteSession session = TopicRouteSessionScope.find(routeMapper, memberId, topicId, activityId);
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
        TopicRouteSession session = TopicRouteSessionScope.find(routeMapper, memberId, topicId, activityId);
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
        TopicRouteSession session = TopicRouteSessionScope.find(routeMapper, memberId, topicId, activityId);
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
        String frozen = NodeJourneySnapshot.journeyRules(
                session == null ? null : session.getConfigSnapshotJson());
        if (frozen != null) return JourneyRules.parse(frozen);
        return journeyRules(session == null ? null : topicMapper.selectCmsTopicById(session.getTopicId()));
    }

    /**
     * 代价标签的展示名:注册表里该 tag 的 label。未注册 / 没填 label / 注册表未注入一律回落
     * 空串,不抛错 —— 回执只是展示,不能因为展示名缺失把一次已经成立的结算打回。
     */
    private String failCostTagLabel(Long topicId, String tag) {
        if (themeStateKeyMapper == null) return "";
        List<ThemeStateKey> rows = themeStateKeyMapper.selectByTopicId(topicId);
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
        if (memberId == null || memberId <= 0L || topicId == null || topicId <= 0L
                || nodeId == null || nodeId <= 0L) {
            return null;
        }
        TopicRouteSession session = TopicRouteSessionScope.find(routeMapper, memberId, topicId, activityId);
        if (session == null || !memberId.equals(session.getMemberId())) return null;
        return NodeJourneySnapshot.answer(session.getConfigSnapshotJson(), nodeId);
    }

    private TopicRouteSession ensureBranchSession(Scope scope) {
        TopicRouteSession existing = currentSession(scope.memberId, scope.topic.getId(), scope.activityId);
        if (existing != null) return existing;
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
                validator.validateAndNormalize(scope.topic.getPublishedRouteGraphJson(), nodeIds),
                scope.topic, nodes);
        TopicRouteEngine.State initial = engine.initial(snapshot);
        applyJourneyInitials(scope.topic, initial);
        // 进入第一个节点 = 进入首个章节:阶段休整同样适用;action 行等 session 落库后再登记。
        CmsTopicNode firstNode = findNode(nodes, initial.getCurrentNodeId());
        Map<String, Object> stageRest = applyStageRest(null, scope.memberId, initial,
                journeyRules(scope.topic), firstNode, false);
        Date now = new Date();
        TopicRouteSession session = new TopicRouteSession();
        session.setActivityId(scope.activityId); session.setTopicId(scope.topic.getId());
        session.setMemberId(scope.memberId); session.setPlayInstanceId(DEFAULT_INSTANCE);
        session.setConfigVersion(scope.topic.getPublishedConfigVersion());
        session.setConfigSnapshotJson(snapshot); themeEngine.initialize(themeConfig(session), initial);
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
     * 一次请求内的节点模板配置备忘:无本局时的建局判定与建局冻结共用同一次模板查询。
     * 只随调用链传递、不落字段 —— 不做跨请求缓存,创作者改模板对下一次请求立即生效。
     */
    private final class JourneyTemplateProbe {
        private final List<CmsTopicNode> liveNodes;
        private Map<Long, CmsMemberTemplate> templates;
        private Map<Long, String> configByTemplate;

        private JourneyTemplateProbe(List<CmsTopicNode> liveNodes) {
            this.liveNodes = liveNodes;
        }

        private Map<Long, String> configs() {
            if (configByTemplate == null) {
                configByTemplate = new LinkedHashMap<Long, String>();
                for (Map.Entry<Long, CmsMemberTemplate> row : templatesById().entrySet()) {
                    configByTemplate.put(row.getKey(), row.getValue().getAdvancedConfigJson());
                }
            }
            return configByTemplate;
        }

        /** 同一次模板查询的实体表:建局冻结答案判定字段与块配置共用,别对同一请求再查一次。 */
        private Map<Long, CmsMemberTemplate> templatesById() {
            if (templates == null) templates = templateEntities(liveNodes);
            return templates;
        }
    }

    /** 主题下节点绑定模板的 advancedConfigJson(按模板 id);templateMapper 缺失/无绑定返回空表。 */
    private Map<Long, String> templateConfigs(List<CmsTopicNode> liveNodes) {
        Map<Long, String> configByTemplate = new LinkedHashMap<Long, String>();
        for (Map.Entry<Long, CmsMemberTemplate> row : templateEntities(liveNodes).entrySet()) {
            configByTemplate.put(row.getKey(), row.getValue().getAdvancedConfigJson());
        }
        return configByTemplate;
    }

    /** 节点绑定模板的实体(按模板 id);templateMapper 缺失 / 无绑定返回空表。 */
    private Map<Long, CmsMemberTemplate> templateEntities(List<CmsTopicNode> liveNodes) {
        Map<Long, CmsMemberTemplate> byId = new LinkedHashMap<Long, CmsMemberTemplate>();
        if (templateMapper == null) return byId;
        Set<Long> templateIds = new LinkedHashSet<Long>();
        for (CmsTopicNode node : liveNodes) {
            if (node.getTemplateId() != null && node.getTemplateId() > 0L) templateIds.add(node.getTemplateId());
        }
        if (templateIds.isEmpty()) return byId;
        List<CmsMemberTemplate> templates =
                templateMapper.selectCmsMemberTemplateByIds(new ArrayList<Long>(templateIds));
        if (templates == null) return byId;
        for (CmsMemberTemplate template : templates) {
            if (template != null && template.getId() != null) byId.put(template.getId(), template);
        }
        return byId;
    }

    /**
     * 建局冻结(B8):每个挂玩法模板的节点写一条 nodeJourney(空对象也写,封死老快照回退),
     * 玩法段(requires / effects / check)从模板拷;叙事段(R18)从章节故事流按站点组装后
     * 写进同一个快照块(七段形状与旧契约一字不差,运行期读侧零改动)。
     * 主题 journeyRules 一并冻结,缺省冻结成默认规则。
     * R11-A:主题剧情 journey_story_json 同样冻结(缺省冻结成空串 = 建局时没有,开局后补配置
     * 不穿透进行中的局;键在而值为空时不再回退实时)。
     */
    private String freezeJourney(String snapshot, CmsTopic topic, List<CmsTopicNode> liveNodes) {
        return freezeJourney(snapshot, topic, liveNodes, new JourneyTemplateProbe(liveNodes));
    }

    /**
     * R18:主题各章节 blocks 里的叙事块按站点组装成旧契约形状;章节 mapper 缺失
     * (手工 new 的单测)或章节没有物化 blocks 都按空处理,坏块只跳过不抛 ——
     * 冻结不能因为一段脏数据把整局建不出来。
     */
    private Map<Long, ObjectNode> storyNarrativeByNode(CmsTopic topic) {
        Map<Long, ObjectNode> byNode = new LinkedHashMap<Long, ObjectNode>();
        if (cmsTopicChapterMapper == null || topic == null || topic.getId() == null) return byNode;
        CmsTopicChapter probe = new CmsTopicChapter();
        probe.setTopicId(topic.getId());
        List<CmsTopicChapter> chapters = cmsTopicChapterMapper.selectCmsTopicChapterList(probe);
        if (chapters == null) return byNode;
        for (CmsTopicChapter chapter : chapters) {
            if (chapter == null || chapter.getBlocksJson() == null || chapter.getBlocksJson().trim().isEmpty()) {
                continue;
            }
            byNode.putAll(ChapterNarrativeBlocks.assembleByNode(chapter.getBlocksJson()));
        }
        return byNode;
    }

    /** 建局冻结:模板探测由调用方传入,建局判定 / 块配置 / 判定字段冻结共用同一次模板查询。 */
    private String freezeJourney(String snapshot, CmsTopic topic, List<CmsTopicNode> liveNodes,
                                 JourneyTemplateProbe probe) {
        return freezeJourney(snapshot, topic, liveNodes, probe.configs(), probe.templatesById());
    }

    /** 建局冻结:模板配置与实体由调用方传入(同一次查询),块配置与判定字段一起冻结。 */
    private String freezeJourney(String snapshot, CmsTopic topic, List<CmsTopicNode> liveNodes,
                                 Map<Long, String> configByTemplate,
                                 Map<Long, CmsMemberTemplate> templatesById) {
        return freezeJourney(snapshot,topic,liveNodes,configByTemplate,templatesById,0);
    }
    private String freezeJourney(String snapshot,CmsTopic topic,List<CmsTopicNode> liveNodes,
                                 Map<Long,String> configByTemplate,Map<Long,CmsMemberTemplate> templatesById,int sceneIndex) {
        final JsonNode parsed;
        try {
            parsed = JSON.readTree(snapshot);
        } catch (Exception unusableSnapshot) {
            throw new IllegalStateException("路线快照不可用", unusableSnapshot);
        }
        if (parsed == null || !parsed.isObject()) throw new IllegalStateException("路线快照不可用");
        ObjectNode root = (ObjectNode) parsed;
        ObjectNode nodeJourney = root.putObject(NodeJourneySnapshot.NODE_JOURNEY_KEY);
        ObjectNode nodeConfigs = root.putObject("nodeConfigs");
        Map<Long, ObjectNode> storyByNode = storyNarrativeByNode(topic);
        for (CmsTopicNode node : liveNodes) {
            if (node.getId() == null || node.getTemplateId() == null || node.getTemplateId() <= 0L) continue;
            ObjectNode block = nodeJourney.putObject(String.valueOf(node.getId()));
            JsonNode blockConfig = readBlockConfig(configByTemplate.get(node.getTemplateId()));
            nodeConfigs.set(String.valueOf(node.getId()), blockConfig == null ? JSON.createObjectNode() : blockConfig.deepCopy());
            NodeJourneySnapshot.copyMechanicSegments(blockConfig, block);
            ObjectNode story = storyByNode.get(node.getId());
            if (story != null) {
                java.util.Iterator<String> segments = story.fieldNames();
                while (segments.hasNext()) {
                    String segment = segments.next();
                    block.set(segment, story.get(segment));
                }
            }
        }
        try {
            root.set(NodeJourneySnapshot.JOURNEY_RULES_KEY,
                    JSON.valueToTree(journeyRules(topic)));
            JourneyInitialState.freezeKeys(root, themeStateKeyMapper == null ? null
                    : themeStateKeyMapper.selectByTopicId(topic.getId()), journeyRules(topic));
        } catch (IllegalArgumentException unusableRules) {
            throw new IllegalStateException("旅程数值规则不可用", unusableRules);
        }
        String story = topic == null ? null : topic.getJourneyStoryJson();
        root.put(NodeJourneySnapshot.JOURNEY_STORY_KEY, story == null ? "" : story.trim());
        root.put("topicCompleteRule", topic == null ? null : topic.getCompleteRuleJson());
        Long openingId = JourneyStory.openingChapterId(story);
        freezeThoughtChapters(root, topic, liveNodes, openingId);
        if (openingId != null) {
            CmsTopicChapter chapterProbe = new CmsTopicChapter(); chapterProbe.setTopicId(topic.getId());
            List<CmsTopicChapter> chapters = cmsTopicChapterMapper == null ? null
                    : cmsTopicChapterMapper.selectCmsTopicChapterList(chapterProbe);
            CmsTopicChapter opening = null;
            if (chapters != null) for (CmsTopicChapter chapter : chapters) {
                if (openingId.equals(chapter.getId())) { opening = chapter; break; }
            }
            if (opening == null) throw new IllegalStateException("开局流程不可用");
            ObjectNode frozen = root.putObject("openingChapter");
            frozen.put("chapterId", openingId); frozen.put("opening", true);
            frozen.put("name", opening.getName()); frozen.put("idxLabel", "序章");
            frozen.put("imgArr", opening.getImgArr()); frozen.put("audioUrl", opening.getAudioUrl());
            frozen.put("atmospherePreset", opening.getAtmospherePreset());
            frozen.set("blocks", JSON.valueToTree(ChapterBlockReadSupport.resolveFromTopicNodes(opening, liveNodes)));
        }
        freezeNodeAnswers(root.putObject(NodeJourneySnapshot.NODE_ANSWERS_KEY), liveNodes, templatesById);
        com.fasterxml.jackson.databind.node.ObjectNode themeConfig = com.chengyinhub.business.service.support.ThemeRuntimeConfig.parse(topic.getPublishedThemeRuntimeConfigJson());
        if (themeConfig != null) {
            ObjectNode frozen = com.chengyinhub.business.service.support.ThemeRuntimeConfig.freeze(themeConfig, sceneIndex, clock.millis());
            for(JsonNode ch:frozen.path("chapters")) {
                int index=ch.path("index").asInt();JsonNode binding=JSON.createObjectNode().path("missing");
                for(JsonNode candidate:root.path("thoughtChapters"))if(candidate.path("authorIndex").asInt(-1)==index)binding=candidate;
                if(!binding.isObject())throw new IllegalArgumentException("背包配置引用未知章节");
                ((ObjectNode)ch).set("binding",binding.deepCopy());
            }
            ArrayNode legacy=frozen.putArray("legacyMedals");
            if(topic.getFinishMedalImg()!=null && !topic.getFinishMedalImg().trim().isEmpty()){ObjectNode medal=legacy.addObject();medal.put("id","legacy_finish");medal.put("kind","MEDAL");medal.put("name",topic.getFinishMedalName()==null?"通关勋章":topic.getFinishMedalName());medal.put("image",topic.getFinishMedalImg());medal.put("legacySource","FINISH");}
            for(CmsTopicNode node:liveNodes){CmsMemberTemplate tpl=templatesById.get(node.getTemplateId());if(tpl==null || tpl.getMedalImg()==null || tpl.getMedalImg().trim().isEmpty())continue;ObjectNode medal=legacy.addObject();medal.put("id","legacy_node_"+node.getId());medal.put("kind","MEDAL");medal.put("name",tpl.getMedalName()==null?"节点奖牌":tpl.getMedalName());medal.put("image",tpl.getMedalImg());medal.put("legacySource","NODE_RANK");medal.put("nodeId",node.getId());}
            root.set("themeConfig", frozen);applySceneNodes(root,frozen);
            if (frozen.has("journeyStory")) root.put(NodeJourneySnapshot.JOURNEY_STORY_KEY, frozen.get("journeyStory").toString());
        }
        return write(root);
    }

    private void freezeThoughtChapters(ObjectNode root, CmsTopic topic, List<CmsTopicNode> nodes, Long openingId) {
        com.fasterxml.jackson.databind.node.ArrayNode frozen = root.putArray("thoughtChapters");
        if (cmsTopicChapterMapper == null) return;
        CmsTopicChapter probe = new CmsTopicChapter(); probe.setTopicId(topic.getId());
        List<CmsTopicChapter> rows = cmsTopicChapterMapper.selectCmsTopicChapterList(probe);
        if (rows == null) return;
        List<CmsTopicChapter> chapters = new ArrayList<CmsTopicChapter>(rows);
        chapters.sort(Comparator.comparing(CmsTopicChapter::getSortId, Comparator.nullsLast(Long::compareTo))
                .thenComparing(CmsTopicChapter::getId, Comparator.nullsLast(Long::compareTo)));
        Set<Long> endingIds = JourneyStory.endingChapterIds(topic.getJourneyStoryJson());
        Set<Long> requiredBefore = new LinkedHashSet<Long>();
        int authorIndex=-1;
        for (CmsTopicChapter chapter : chapters) {
            authorIndex++;
            if (chapter.getId() == null || endingIds.contains(chapter.getId())) continue;
            Set<Long> nodeIds = new LinkedHashSet<Long>();
            for (CmsTopicNode node : nodes) if (chapter.getId().equals(node.getChapterId())) nodeIds.add(node.getId());
            ObjectNode row = frozen.addObject();
            row.put("chapterId", chapter.getId());
            row.put("authorIndex",authorIndex);
            row.put("opening", chapter.getId().equals(openingId));
            row.put("requiresOpening",openingId!=null);
            row.set("nodeIds", JSON.valueToTree(nodeIds));
            row.set("requiredBefore", JSON.valueToTree(requiredBefore));
            row.set("blocks", JSON.valueToTree(ChapterBlockReadSupport.resolveFromTopicNodes(chapter, nodes)));
            if (ChapterBlockReadSupport.isRequiredChapter(chapter)) requiredBefore.addAll(nodeIds);
        }
    }

    /**
     * 建局冻结模板判定字段(R18-FREEZE):validationMethod 与各验证方式的答案 / 揭示 / 偏好 /
     * 传感器配置。每个在架节点写一条(没绑定模板也写空对象,封死新局回退);模板行取不到时
     * 只留空对象。奖励字段(券 / 探索值 / 条件奖励规则)不在此列,仍读实时。
     */
    private void freezeNodeAnswers(ObjectNode nodeAnswers, List<CmsTopicNode> liveNodes,
                                   Map<Long, CmsMemberTemplate> templates) {
        Map<Long, CmsMemberTemplate> byId = templates == null
                ? Collections.<Long, CmsMemberTemplate>emptyMap() : templates;
        for (CmsTopicNode node : liveNodes) {
            if (node.getId() == null) continue;
            ObjectNode target = nodeAnswers.putObject(String.valueOf(node.getId()));
            if (node.getTemplateId() == null || node.getTemplateId() <= 0L) continue;
            CmsMemberTemplate template = byId.get(node.getTemplateId());
            if (template == null) continue;
            if (template.getValidationMethod() != null) {
                target.put("validationMethod", template.getValidationMethod().intValue());
            }
            if (template.getPhotoReview() != null) {
                target.put("photoReview", template.getPhotoReview().intValue());
            }
            putAnswerText(target, "questionAnswer", template.getQuestionAnswer());
            putAnswerText(target, "correctAnswer", template.getCorrectAnswer());
            putAnswerText(target, "answerReveal", template.getAnswerReveal());
            putAnswerText(target, "questionA", template.getQuestionA());
            putAnswerText(target, "questionB", template.getQuestionB());
            putAnswerText(target, "questionC", template.getQuestionC());
            putAnswerText(target, "questionD", template.getQuestionD());
            putAnswerText(target, "preferenceJson", template.getPreferenceJson());
            putAnswerText(target, "sensorType", template.getSensorType());
            putAnswerText(target, "sensorConfig", template.getSensorConfig());
        }
    }

    /** 冻结字段拷贝:null 不写(建局时没有这个字段 = 冻结成「没有」)。 */
    private static void putAnswerText(ObjectNode target, String field, String value) {
        if (value != null) target.put(field, value);
    }

    /** 冻结用块配置解析:坏 JSON / 非对象一律当成没配(发布门禁负责拦,建局不因坏块失败)。 */
    private JsonNode readBlockConfig(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            JsonNode config = JSON.readTree(raw);
            return config != null && config.isObject() ? config : null;
        } catch (Exception unusableBlockConfig) {
            return null;
        }
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
        return JourneyRules.parse(topic == null ? null : topic.getJourneyRulesJson());
    }

    /** 本局适用的数值规则:优先读建局冻结值;老局快照没有该键时回退实时主题配置。 */
    private JourneyRules journeyRules(TopicRouteSession session, CmsTopic topic) {
        String frozen = NodeJourneySnapshot.journeyRules(
                session == null ? null : session.getConfigSnapshotJson());
        return frozen == null ? journeyRules(topic) : JourneyRules.parse(frozen);
    }

    /**
     * 建局时的主题级数值规则初始化:sys.* 是服务端保留变量,玩家侧只读;
     * hp/luck 各自 enabled 时才写各自的 init 值。
     */
    private void applyJourneyInitials(CmsTopic topic, TopicRouteEngine.State state) {
        JourneyRules rules = journeyRules(topic);
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
        com.chengyinhub.business.service.support.CurrentPlayRegistration.require(activityMapper,registrationMapper,
                scope.memberId,scope.activityId>0 ? 2L : 1L,scope.activityId>0 ? scope.activityId : scope.topic.getId(),null,new Date());
        if (scope.topic.getPublishedConfigVersion() == null || scope.topic.getPublishedConfigVersion() <= 0L) {
            throw new IllegalStateException("路线尚未发布");
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
        applyJourneyInitials(scope.topic, initial);
        // 建局时所在节点也可能是某章节的第一个节点:同样适用阶段休整。
        CmsTopicNode firstNode = findNode(liveNodes, current);
        Map<String, Object> stageRest = applyStageRest(null, scope.memberId, initial,
                journeyRules(scope.topic), firstNode, false);
        Date now = new Date();
        TopicRouteSession session = new TopicRouteSession();
        session.setActivityId(scope.activityId); session.setTopicId(scope.topic.getId());
        session.setMemberId(scope.memberId); session.setPlayInstanceId(DEFAULT_INSTANCE);
        session.setConfigVersion(scope.topic.getPublishedConfigVersion());
        session.setConfigSnapshotJson(freezeJourney(linearSnapshot(liveNodes), scope.topic, liveNodes, probe));
        themeEngine.initialize(themeConfig(session), initial);
        session.setStateJson(write(initial));
        session.setStatus(current == null ? TopicRouteSession.STATUS_COMPLETED : TopicRouteSession.STATUS_RUNNING);
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
        ObjectNode snapshot = JSON.createObjectNode();
        snapshot.put("schemaVersion", 1);
        if (!liveNodes.isEmpty()) snapshot.put("startNodeId", liveNodes.get(0).getId().longValue());
        ArrayNode terminals = snapshot.putArray("terminalNodeIds");
        if (!liveNodes.isEmpty()) terminals.add(liveNodes.get(liveNodes.size() - 1).getId().longValue());
        snapshot.putObject("variables");
        return write(snapshot);
    }

    /**
     * 线性主题的节点推进:与分支共用 state_json 与 routeActionId 幂等、version CAS,
     * 但不走 engine.advance(线性快照没有边),只做 requires 判断与 effects 写入。
     */
    private TopicRouteState advanceLinear(Scope scope, TopicRouteSession session, Integer expectedVersion,
                                          String routeActionId, NodeOutcome outcome) {
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
        syncTheme(session, state);
        themeEngine.signal(themeConfig(session), state, rules, "NODE", outcome.getNodeId(), "", "", 0, "node:" + outcome.getNodeId());
        session.setStateJson(writeSettled(session, state));
        session.setStatus(statusOf(state));
        keepOpeningRunning(session, state);
        session.setCurrentNodeId(state.getCurrentNodeId()); session.setRecommendedNodeId(state.getRecommendedNodeId());
        if (session.getStatus() == TopicRouteSession.STATUS_COMPLETED) session.setCompletedAt(new Date());
        session.setUpdateTime(new Date());
        int oldVersion = session.getVersion();
        if (routeMapper.updateStateCas(session) != 1) throw new ServiceException("路线状态已更新，请刷新后重试", HttpStatus.CONFLICT);
        session.setVersion(oldVersion + 1);
        TopicRouteState view = branchOpening ? toAvailableView(session, liveNodes) : toLinearView(session, scope, liveNodes);
        putVitals(view, session, state);
        appendEncounter(view, session);
        if (routeMapper.updateActionResponse(action.getId(), write(view)) != 1) {
            throw new IllegalStateException("路线动作回执保存失败");
        }
        return view;
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
        TopicRouteState view = linearState(scope, liveNodes);
        if (themeConfig(session).isObject()) {
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
        probe.setPlayInstanceId(playInstance(scope.memberId,scope.topic.getId(),scope.activityId)); return probe;
    }

    private List<CmsTopicNode> nodes(Long topicId) {
        CmsTopicNode probe = new CmsTopicNode(); probe.setTopicId(topicId); probe.setStatus(1L);
        List<CmsTopicNode> result = nodeMapper.selectCmsTopicNodeList(probe);
        if (result == null) return Collections.emptyList();
        result = new ArrayList<CmsTopicNode>(result);
        result.sort(Comparator.comparingLong(n -> n.getSortId() == null ? 0L : n.getSortId())); return result;
    }

    private Scope requireScope(Long memberId, Long topicId, Long activityId) {
        return requireScope(memberId,topicId,activityId,false);
    }
    private Scope requireScope(Long memberId, Long topicId, Long activityId,boolean newUse) {
        if (memberId == null || memberId <= 0L || topicId == null || topicId <= 0L) throw new IllegalArgumentException("参数有误");
        CmsTopic topic = topicMapper.selectCmsTopicById(topicId);
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())) throw new IllegalArgumentException("主题不存在");
        long actId = activityId == null ? 0L : activityId;
        List<CmsRegistration> current=null;
        // 新使用只认当前锁读仍有效的原报名；保留全部有效报名，原「任一成功报名可授权」候选策略不收窄为第一张。
        if(newUse) current=runningSession(memberId,topicId,actId)!=null
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
            if (currentRegistrations.isEmpty()) throw new IllegalStateException("自玩通行证已过期");
        } else currentRegistrations.addAll(rows);
        if(newUse) { currentRegistrations.clear();currentRegistrations.addAll(current); }
        boolean hasPublishedRoute = topic.getPublishedConfigVersion() != null
                && topic.getPublishedConfigVersion() > 0L;
        if (!hasPublishedRoute
                && routeMapper.selectByOwner(actId, topicId, memberId, DEFAULT_INSTANCE) == null) {
            throw new IllegalStateException("路线尚未发布");
        }
        return new Scope(memberId, actId, topic, currentRegistrations);
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
        try { return JSON.readValue(json, TopicRouteEngine.State.class); }
        catch (Exception e) { throw new IllegalStateException("路线局状态不可用", e); }
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
        if (effects == null || !effects.isArray() || effects.size() == 0) return;
        boolean hasSysLuck = false;
        for (JsonNode effect : effects) {
            if (isSysLuckInc(effect)) { hasSysLuck = true; break; }
        }
        if (!hasSysLuck) {
            engine.applyEffects(effects, state);
            JourneyInitialState.clamp(rules, state);
            return;
        }
        ArrayNode others = JSON.createArrayNode();
        boolean applyLuck = false;
        int luckDelta = 0;
        for (JsonNode effect : effects) {
            if (isSysLuckInc(effect)) {
                if (rules.getLuck() != null && rules.getLuck().isEnabled()) {
                    luckDelta += effect.path("value").asInt(0);
                    applyLuck = true;
                }
                continue;
            }
            others.add(effect);
        }
        engine.applyEffects(others, state);
        JourneyInitialState.clamp(rules, state);
        if (!applyLuck) return;
        state.getVariables().put(SYS_LUCK, Math.min(luckMaxOf(state, rules), luckOf(state, rules) + luckDelta));
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
        if (memberId == null || memberId <= 0L || topicId == null || topicId <= 0L
                || nodeId == null || nodeId <= 0L) return;
        TopicRouteSession session = TopicRouteSessionScope.find(routeMapper, memberId, topicId, activityId);
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
        Map<String, Object> receipt = new LinkedHashMap<String, Object>();
        receipt.put("nodeId", nodeId); receipt.put("passed", passed);
        recordFact(memberId, topicId, activityId, nodeId, actionId, "passed:", "PASSED",
                SYS_PASSED_PREFIX, Integer.valueOf(passed ? 1 : 0), receipt);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void recordOutcome(Long memberId, Long topicId, Long activityId, Long nodeId, String actionId, int outcome) {
        if (outcome <= 0) return;   // 序号从 1 起;0 / 负数 = 调用方没算出结果,不写
        Map<String, Object> receipt = new LinkedHashMap<String, Object>();
        receipt.put("nodeId", nodeId); receipt.put("outcome", outcome);
        recordFact(memberId, topicId, activityId, nodeId, actionId, "outcome:", "OUTCOME",
                SYS_OUTCOME_PREFIX, Integer.valueOf(outcome), receipt);
    }

    /**
     * 玩法事实写本局(recordPassed / recordOutcome 共用):gate、外来节点、幂等、CAS 一处实现。
     * 两者只差写哪个 sys 键、动作 id 前缀和回执 —— 各抄一份的话,改一边的闸另一边就漏了。
     */
    private void recordFact(Long memberId, Long topicId, Long activityId, Long nodeId, String actionId, String actionPrefix,
                            String outcomeCode, String varPrefix, Integer value, Map<String, Object> receipt) {
        if (memberId == null || memberId <= 0L || topicId == null || topicId <= 0L
                || nodeId == null || nodeId <= 0L) return;
        String key = actionId == null ? "" : actionId.trim();
        if (key.isEmpty()) return;
        TopicRouteSession session = TopicRouteSessionScope.find(routeMapper, memberId, topicId, activityId);
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
        if (memberId == null || memberId <= 0L || topicId == null || topicId <= 0L
                || nodeId == null || nodeId <= 0L) return;
        // stepOrKit 是分支步骤 id 或玩法段名,两种都由下面的配置查找定夺,这里不预筛。
        String kit = stepOrKit == null ? "" : stepOrKit.trim();
        if (kit.isEmpty()) return;
        TopicRouteSession session = TopicRouteSessionScope.find(routeMapper, memberId, topicId, activityId);
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
        CmsTopicNode node = nodeMapper.selectCmsTopicNodeById(nodeId);
        if (node == null || !topicId.equals(node.getTopicId())) throw new IllegalStateException("节点不存在");
        if (shopDayProofService == null
                || !shopDayProofService.hasArrived(memberId, session.getActivityId(), nodeId)) {
            throw new IllegalStateException("请先扫码确认到店");
        }
        JsonNode question = findQuestion(nodeBlockConfig(session, node), qid);
        if (question == null) throw new IllegalStateException("这一站没有这个问题");
        String actionId = "ask:" + nodeId + ":" + qid;
        if (actionId.length() > 96) throw new IllegalArgumentException("questionId 过长");
        Map<String, Object> replayed = replayJourneyAction(session, memberId, actionId);
        if (replayed != null) return replayed;
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
        syncTheme(session, state);
        session.setStateJson(writeSettled(session, state));
        if (session.getStatus() == null) session.setStatus(TopicRouteSession.STATUS_RUNNING);
        session.setCurrentNodeId(state.getCurrentNodeId());
        session.setRecommendedNodeId(state.getRecommendedNodeId());
        session.setUpdateTime(new Date());
        if (routeMapper.updateStateCas(session) != 1) {
            throw new ServiceException("路线状态已更新，请刷新后重试", HttpStatus.CONFLICT);
        }
        Map<String, Object> encounter = encounterView(session, nodeId);
        if (encounter != null) {
            receipt.put("encounter", encounter);
            if (routeMapper.updateActionResponse(action.getId(), write(receipt)) != 1) {
                throw new IllegalStateException("旅程动作回执保存失败");
            }
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
        if (memberId == null || memberId <= 0L || topicId == null || topicId <= 0L) {
            throw new IllegalArgumentException("参数有误");
        }
        TopicRouteSession session = TopicRouteSessionScope.find(routeMapper, memberId, topicId, activityId);
        if (session == null) throw new IllegalStateException("本局尚未开始");
        requireScope(memberId, topicId, session.getActivityId(),true);
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
        return action == null ? null : readReceipt(action);
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
        JsonNode check = context.check;
        String tierName = check.path("tier").asText("");
        JourneyRules.Tier tier = tierOf(context.rules, tierName);
        if (tier == null) throw new IllegalStateException("检定难度档位无效");
        boolean advantage = anyConditionPasses(check.get("advantageIf"), context.state);
        boolean disadvantage = anyConditionPasses(check.get("disadvantageIf"), context.state);
        if (advantage && disadvantage) { advantage = false; disadvantage = false; }

        List<Integer> dice = new ArrayList<Integer>();
        int kept;
        if (advantage || disadvantage) {
            int first = rollDie();
            int second = rollDie();
            dice.add(first); dice.add(second);
            kept = advantage ? Math.max(first, second) : Math.min(first, second);
        } else {
            kept = rollDie();
            dice.add(kept);
        }

        int modSum = 0;
        List<PlayCheckReceipt.Mod> mods = new ArrayList<PlayCheckReceipt.Mod>();
        JsonNode configuredMods = check.get("mods");
        if (configuredMods != null && configuredMods.isArray()) {
            for (JsonNode mod : configuredMods) {
                PlayCheckReceipt.Mod item = new PlayCheckReceipt.Mod();
                item.setLabel(textField(mod, "label"));
                item.setValue(mod.path("value").asInt(0));
                item.setApplied(conditionPasses(mod.get("when"), context.state));
                mods.add(item);
                if (item.isApplied()) modSum += item.getValue();
            }
        }
        int mod = Math.min(modSum, context.rules.getModCap());
        String nat = kept == 20 ? PlayCheckReceipt.NAT_CRIT
                : kept == 1 ? PlayCheckReceipt.NAT_FUMBLE : null;
        boolean success = PlayCheckReceipt.NAT_CRIT.equals(nat)
                || (!PlayCheckReceipt.NAT_FUMBLE.equals(nat) && kept + mod >= tier.getDc());

        PlayCheckReceipt receipt = new PlayCheckReceipt();
        receipt.setCheckId(check.path("checkId").asText(""));
        receipt.setTier(tierName);
        receipt.setDc(tier.getDc());
        receipt.setMods(mods);
        receipt.setAdvantage(advantage);
        receipt.setDisadvantage(disadvantage);
        receipt.setDice(dice);
        receipt.setKept(kept);
        receipt.setTotal(kept + mod);
        receipt.setSuccess(success);
        receipt.setNat(nat);
        receipt.setRerolled(rerolled);
        receipt.setSettled(false);
        return receipt;
    }

    /** 回执写入与状态 CAS 一次完成;action 行先插入,丢失 CAS 时整个事务回滚。 */
    private PlayCheckReceipt commitCheck(TopicRouteSession session, Long nodeId, String actionId,
                                         String outcomeCode, PlayCheckReceipt receipt,
                                         TopicRouteEngine.State state) {
        int nextVersion = (session.getVersion() == null ? 0 : session.getVersion()) + 1;
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
        syncTheme(session, state);
        session.setStateJson(writeSettled(session, state));
        if (session.getStatus() == null) session.setStatus(TopicRouteSession.STATUS_RUNNING);
        session.setCurrentNodeId(state.getCurrentNodeId());
        session.setRecommendedNodeId(state.getRecommendedNodeId());
        session.setUpdateTime(new Date());
        if (routeMapper.updateStateCas(session) != 1) {
            throw new ServiceException("路线状态已更新，请刷新后重试", HttpStatus.CONFLICT);
        }
        session.setVersion(nextVersion);
        Map<String, Object> encounter = encounterView(session, nodeId);
        if (encounter != null) {
            receipt.setEncounter(encounter);
            if (routeMapper.updateActionResponse(action.getId(), write(receipt)) != 1) {
                throw new IllegalStateException("检定回执保存失败");
            }
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
        if (conditions == null || !conditions.isArray() || conditions.size() == 0) return false;
        ArrayNode single = JSON.createArrayNode();
        for (JsonNode condition : conditions) {
            single.removeAll();
            single.add(condition);
            if (engine.conditionsPass(single, state)) return true;
        }
        return false;
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
        TopicRouteSession session = TopicRouteSessionScope.find(routeMapper, memberId, topicId, activityId);
        if (session == null
                || !Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus())) return false;
        return conditionPasses(condition, readEngineState(session.getStateJson()));
    }

    private boolean conditionPasses(JsonNode condition, TopicRouteEngine.State state) {
        if (condition == null || condition.isNull()) return true;
        if (condition.isArray()) return anyConditionPasses(condition, state);
        ArrayNode single = JSON.createArrayNode();
        single.add(condition);
        return engine.conditionsPass(single, state);
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
        int configured = rules.getLuck().getMax();
        Number raised = state.getVariables().get(SYS_LUCK_MAX);
        return raised == null ? configured : Math.max(configured, raised.intValue());
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
        Map<String,Object> story=themeStoryService.freezeEnding(session.getTopicId(),raw,state);
        ObjectNode result=(ObjectNode)state.getThemeRuntime().get("result");
        result.set("story",JSON.valueToTree(story));
        if(!themeConfig(session).path("endings").isArray() || themeConfig(session).path("endings").isEmpty()) {
            if(story.get("code")!=null)result.put("endingId",String.valueOf(story.get("code")));
            if(story.get("title")!=null)result.put("title",String.valueOf(story.get("title")));
            if(story.get("summary")!=null)result.put("summary",String.valueOf(story.get("summary")));
        }
    }
    @Override
    @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public void recordNodeResult(Long memberId,Long topicId,Long activityId,Long nodeId,String instance,String actionId,Boolean passed,boolean mistake,int outcome,List<String[]> choices) {
        TopicRouteSession session=currentSession(memberId,topicId,activityId);
        if(session==null || !instance.equals(session.getPlayInstanceId()))throw new ServiceException("本局已更新，请刷新",409);
        if(mistake) recordMistake(memberId,topicId,activityId,nodeId);
        if(passed!=null) recordPassed(memberId,topicId,activityId,nodeId,actionId,passed);
        if(outcome>0) recordOutcome(memberId,topicId,activityId,nodeId,actionId,outcome);
        for(String[] choice:choices) recordChoice(memberId,topicId,activityId,nodeId,choice[0],choice[1]);
    }
    @Override
    public JsonNode nodeRuntimeConfig(Long memberId,Long topicId,Long nodeId,Long activityId) {
        TopicRouteSession session=currentSession(memberId,topicId,activityId);
        if(session==null || !themeConfig(session).isObject())return null;
        return validator.readTree(session.getConfigSnapshotJson()).path("nodeConfigs").get(String.valueOf(nodeId));
    }

    private void applyThemeRescue(TopicRouteSession session,TopicRouteEngine.State state,JourneyRules rules) {
        JsonNode config=themeConfig(session);int count=state.getThemeRuntime().path("rescues").asInt();
        if(!state.getThemeRuntime().path("awaitingRescue").asBoolean() || count>=config.path("rescueLimit").asInt())throw new ServiceException("本局没有可用救援");
        state.getThemeRuntime().put("rescues",count+1);state.getThemeRuntime().put("awaitingRescue",false);state.getVariables().put(SYS_HP,rules.getRecovery().getExhaustRestHp());state.getVariables().remove(SYS_EXHAUSTED);
        if(state.getThemeRuntime().path("finishAfterRescue").asBoolean()){state.getThemeRuntime().remove("finishAfterRescue");state.setStatus("COMPLETED");}
        String tag=rules.getRecovery().getExhaustTag();if(tag!=null && !tag.trim().isEmpty())state.getTags().add(tag);
    }
    private void applySceneNodes(ObjectNode root,JsonNode frozen) {
        frozen.path("nodeConfigs").fields().forEachRemaining(row->{
            root.with("nodeConfigs").set(row.getKey(),row.getValue().deepCopy());
            ObjectNode block=root.with(NodeJourneySnapshot.NODE_JOURNEY_KEY).with(row.getKey());
            block.remove(java.util.Arrays.asList("requires","effects","check","mistakeTier",NodeJourneySnapshot.CHOICE_EFFECTS_KEY,NodeJourneySnapshot.PLAYABLES_KEY,NodeJourneySnapshot.BRANCH_OUTCOMES_KEY));
            NodeJourneySnapshot.copyMechanicSegments(row.getValue(),block);
        });
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

    @Override
    public boolean hasRunningSession(Long memberId, Long topicId, Long activityId) {
        return memberId != null && topicId != null && runningSession(memberId, topicId, activityId == null ? 0L : activityId) != null;
    }

    private TopicRouteSession runningSession(Long member, Long topic, long activity) {
        TopicRouteSession session = currentSession(member, topic, activity);
        if (session == null || !member.equals(session.getMemberId())) return null;
        return session.getStatus() == null || Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus())
                ? session : null;
    }

    private TopicRouteSession currentSession(Long member, Long topic, Long activity) {
        TopicRouteSession current = routeMapper.selectCurrentByOwner(activity == null ? 0L : activity, topic, member);
        return current == null ? routeMapper.selectByOwner(activity == null ? 0L : activity, topic, member, DEFAULT_INSTANCE) : current;
    }
    private JsonNode themeConfig(TopicRouteSession session) {
        if (session == null) return JSON.createObjectNode().path("missing");
        return validator.readTree(session.getConfigSnapshotJson()).path("themeConfig");
    }
    private boolean containsNode(JsonNode ids,Long id) {if(id==null)return false;for(JsonNode value:ids)if(value.asLong()==id)return true;return false;}
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
        JsonNode config = themeConfig(session);
        if (!config.isObject() || state.getThemeRuntime().has("result")) return;
        JourneyRules rules = journeyRules(session, topicMapper.selectCmsTopicById(session.getTopicId()));
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
                && (!binding.path("opening").asBoolean() || state.getTags().contains("sys.opening.done")))themeEngine.chapterEnd(config,state,rules,ch,clock.millis());
        }
        themeEngine.chapterTick(config,state,rules,clock.millis());
        themeEngine.reconcileHeld(config,state,rules); themeEngine.events(config,state,rules);
        if(themeBroadcastService!=null)themeBroadcastService.enqueue(session,state);
        String reason = "COMPLETED".equals(state.getStatus()) ? "COMPLETED" : null;
        if(reason==null && config.has("actionLimit") && state.getVariables().get("sys.actions").intValue()>=config.path("actionLimit").asInt())reason="BUDGET";
        if (rules.isCheckEnabled() && rules.getHp().isEnabled() && hpOf(state,rules) <= 0) {
            if(!"BUDGET".equals(reason) && state.getThemeRuntime().path("rescues").asInt()<config.path("rescueLimit").asInt()) {
                state.getThemeRuntime().put("awaitingRescue",true);state.getVariables().put(SYS_EXHAUSTED,1);
                if("COMPLETED".equals(reason)){state.getThemeRuntime().put("finishAfterRescue",true);state.setStatus("ACTIVE");}
                reason=null;
            } else reason="EXHAUSTED";
        }
        if (reason != null) {
            themeEngine.terminal(config,state,rules,reason,clock.millis());freezeThemeStory(session,state);
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
    public String playInstance(Long memberId, Long topicId, Long activityId) {
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
        if(Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus())) {
            TopicRouteEngine.State live=readEngineState(session.getStateJson());String before=write(live);syncTheme(session,live);
            String after=write(live);if(!before.equals(after)) {
                session.setStateJson(after);session.setCurrentNodeId(live.getCurrentNodeId());session.setRecommendedNodeId(live.getRecommendedNodeId());session.setUpdateTime(new Date(clock.millis()));
                if(routeMapper.updateStateCas(session)!=1)throw new ServiceException("本局已更新，请刷新",409);session.setVersion(session.getVersion()+1);
            }
        }
        Map<String,Object> view=themeViewOf(session);
        if(themeBroadcastService!=null)view.put("broadcasts",themeBroadcastService.deliver(session.getId(),memberId));
        return view;
    }
    private Map<String,Object> themeViewOf(TopicRouteSession session) {
        TopicRouteEngine.State state=readEngineState(session.getStateJson());
        Map<String,Object> view=JSON.convertValue(themeEngine.view(themeConfig(session),state), new TypeReference<Map<String,Object>>(){});
        view.put("topicId",session.getTopicId());view.put("activityId",session.getActivityId());view.put("sceneId",themeConfig(session).path("sceneId").asText());view.put("sceneName",themeConfig(session).path("sceneName").asText());
        view.put("inventoryEnabled",themeConfig(session).isObject());
        view.put("sessionId",session.getId());view.put("playInstanceId",session.getPlayInstanceId());view.put("version",session.getVersion());view.put("status",state.getStatus());
        JourneyRules rules=sessionRules(session);
        view.put("hp",hpOf(state,rules));view.put("luck",luckOf(state,rules));
        view.put("hpEnabled",rules.isCheckEnabled() && rules.getHp().isEnabled());view.put("hpMax",rules.getHp().getMax());
        view.put("luckEnabled",rules.isCheckEnabled() && rules.getLuck().isEnabled());view.put("luckMax",luckMaxOf(state,rules));view.put("stepEggs",hasStepEggs(themeConfig(session)));
        List<Integer> chapterIndexes=new ArrayList<>();List<Map<String,Object>> chapterBindings=new ArrayList<>();boolean timeEggs=false;
        for(JsonNode ch:themeConfig(session).path("chapters")){chapterIndexes.add(ch.path("index").asInt());Map<String,Object> binding=new LinkedHashMap<>();binding.put("index",ch.path("index").asInt());binding.put("chapterId",ch.path("binding").path("chapterId").asLong());chapterBindings.add(binding);for(JsonNode egg:ch.path("eggs"))if("TIME".equals(egg.path("trigger").asText()))timeEggs=true;}
        view.put("chapterIndexes",chapterIndexes);view.put("chapterBindings",chapterBindings);view.put("timeEggs",timeEggs);
        return view;
    }
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Map<String,Object> themeAction(Long memberId,Long sessionId,Integer version,String actionId,String operation,String contentId) {
        if(memberId==null || memberId<=0 || sessionId==null || actionId==null || !actionId.matches("[A-Za-z0-9:_-]{1,80}"))throw new IllegalArgumentException("动作参数不正确");
        TopicRouteSession session=routeMapper.selectAccessibleById(sessionId,memberId);
        if(session==null)throw new ServiceException("本局不存在或无权操作",403);
        if (operation == null || !java.util.Arrays.asList("USE","DISCARD","RECEIVE","END","RESCUE","CHAPTER_START","CHAPTER_END").contains(operation) || (!"END".equals(operation) && !"RESCUE".equals(operation) && (contentId == null || contentId.length() > 96))) throw new IllegalArgumentException("动作参数不正确");
        Long actionNodeId=session.getCurrentNodeId();
        String key="theme:"+actionId;
        TopicRouteAction replay=routeMapper.selectActionByAction(sessionId,memberId,key);
        if(replay!=null){
            if(!operation.equals(replay.getOutcomeCode()) || !("theme-receipt:"+shortHash(key+":"+operation+":"+contentId)).equals(replay.getReceiptId())) throw new ServiceException("动作标识已用于其他操作",409);
            return JSON.convertValue(validator.readTree(replay.getResponseJson()),new TypeReference<Map<String,Object>>(){});
        }
        if ("END".equals(operation) && readEngineState(session.getStateJson()).getThemeRuntime().has("result")) return themeViewOf(session);
        Scope scope=requireScope(memberId,session.getTopicId(),session.getActivityId(),true);
        TopicRouteSession current=currentSession(memberId,session.getTopicId(),session.getActivityId());
        if(current==null || !sessionId.equals(current.getId()) || !Integer.valueOf(0).equals(session.getStatus()))throw new ServiceException("本局已结束");
        if(version==null || !version.equals(session.getVersion()))throw new ServiceException("本局已更新，请刷新",409);
        JsonNode config=themeConfig(session);if(!config.isObject())throw new ServiceException("本局未启用主题配置");
        TopicRouteEngine.State state=readEngineState(session.getStateJson()); JourneyRules rules=journeyRules(session,scope.topic);
        if(state.getThemeRuntime().path("awaitingRescue").asBoolean() && !"RESCUE".equals(operation) && !"END".equals(operation))throw new ServiceException("请先选择救援或结束本局");
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
        else if("RECEIVE".equals(operation)){themeEngine.receive(config,state,rules,contentId,true);themeEngine.events(config,state,rules);}
        else if("END".equals(operation)){themeEngine.terminal(config,state,rules,"ABANDONED",clock.millis());freezeThemeStory(session,state);}
        else throw new IllegalArgumentException("不支持此操作");
        syncTheme(session,state);
        session.setStateJson(write(state));session.setCurrentNodeId(state.getCurrentNodeId());session.setRecommendedNodeId(state.getRecommendedNodeId());
        if("ENDED".equals(state.getStatus())){session.setStatus(TopicRouteSession.STATUS_ENDED);session.setCompletedAt(new Date(clock.millis()));}
        session.setUpdateTime(new Date(clock.millis()));
        TopicRouteAction action=new TopicRouteAction();action.setSessionId(sessionId);action.setMemberId(memberId);action.setNodeId(actionNodeId == null ? 0L : actionNodeId);action.setActionId(key);action.setReceiptId("theme-receipt:"+shortHash(key+":"+operation+":"+contentId));action.setOutcomeCode(operation);action.setResponseJson("{}");action.setCreateTime(new Date(clock.millis()));
        try { if(routeMapper.insertAction(action)!=1)throw new IllegalStateException("动作回执创建失败"); }
        catch(DuplicateKeyException duplicate) {
            TopicRouteAction winner=routeMapper.selectActionByAction(sessionId,memberId,key);
            if(winner==null || !operation.equals(winner.getOutcomeCode()) || !action.getReceiptId().equals(winner.getReceiptId()))throw new ServiceException("动作标识已用于其他操作",409);
            return JSON.convertValue(validator.readTree(winner.getResponseJson()),new TypeReference<Map<String,Object>>(){});
        }
        if(routeMapper.updateStateCas(session)!=1)throw new ServiceException("本局已更新，请刷新",409);
        session.setVersion(version+1);Map<String,Object> result=themeViewOf(session);
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
        if(Integer.valueOf(0).equals(previous.getStatus()))throw new ServiceException("请先结束当前局");
        Scope scope=requireScope(memberId,previous.getTopicId(),previous.getActivityId(),true);
        ObjectNode live=com.chengyinhub.business.service.support.ThemeRuntimeConfig.parse(scope.topic.getPublishedThemeRuntimeConfigJson());
        if(live==null || !live.path("replayEnabled").asBoolean())throw new ServiceException("此主题未开放重玩");
        String instance="replay:"+sessionId;
        TopicRouteSession child=routeMapper.selectByOwner(previous.getActivityId(),previous.getTopicId(),memberId,instance);
        if(child!=null)return themeViewOf(child);
        TopicRouteSession current=currentSession(memberId,previous.getTopicId(),previous.getActivityId());
        if(current!=null && !sessionId.equals(current.getId()))throw new ServiceException("请继续或查看最新一局");
        List<CmsTopicNode> liveNodes=nodes(previous.getTopicId());if(liveNodes.isEmpty())throw new ServiceException("主题没有可玩节点");
        boolean branch = TopicRouteState.BRANCH_GRAPH.equals(scope.topic.getRouteMode());
        Set<Long> replayNodes = new LinkedHashSet<Long>(); for(CmsTopicNode n:liveNodes)replayNodes.add(n.getId());
        int index=routeMapper.countRuns(previous.getActivityId(),previous.getTopicId(),memberId);
        JourneyTemplateProbe replayProbe=new JourneyTemplateProbe(liveNodes);
        String snapshot=freezeJourney(branch ? validator.validateAndNormalize(scope.topic.getPublishedRouteGraphJson(),replayNodes) : linearSnapshot(liveNodes),scope.topic,liveNodes,replayProbe.configs(),replayProbe.templatesById(),index);
        ObjectNode root=(ObjectNode)validator.readTree(snapshot);
        ObjectNode frozen=(ObjectNode)root.get("themeConfig");
        TopicRouteEngine.State initial=branch ? engine.initial(write(root)) : new TopicRouteEngine.State();initial.setStatus("ACTIVE");
        if(!branch){initial.setCurrentNodeId(liveNodes.get(0).getId());initial.setRecommendedNodeId(liveNodes.get(0).getId());}
        if(!branch)for(CmsTopicNode node:liveNodes)initial.getNodeStates().put(node.getId(),node.getId().equals(initial.getCurrentNodeId())?TopicRouteState.PLAYABLE:TopicRouteState.DISCOVERED_LOCKED);
        initial.getVisitedNodeIds().add(initial.getCurrentNodeId());applyJourneyInitials(scope.topic,initial);themeEngine.initialize(frozen,initial);
        CmsTopicNode firstNode=findNode(liveNodes,initial.getCurrentNodeId());
        Map<String,Object> stageRest=applyStageRest(null,memberId,initial,journeyRules(scope.topic),firstNode,false);
        child=new TopicRouteSession();child.setMemberId(memberId);child.setTopicId(previous.getTopicId());child.setActivityId(previous.getActivityId());child.setPlayInstanceId(instance);
        child.setConfigSnapshotJson(write(root));child.setStateJson(write(initial));child.setConfigVersion(scope.topic.getPublishedConfigVersion());child.setStatus(0);child.setVersion(0);child.setSeed(seedSupplier.getAsLong());
        child.setCurrentNodeId(initial.getCurrentNodeId());child.setRecommendedNodeId(initial.getRecommendedNodeId());child.setCreateTime(new Date(clock.millis()));child.setUpdateTime(child.getCreateTime());
        syncTheme(child,initial);child.setStateJson(write(initial));child.setCurrentNodeId(initial.getCurrentNodeId());child.setRecommendedNodeId(initial.getRecommendedNodeId());
        try {if(routeMapper.insertSession(child)!=1)throw new IllegalStateException("重玩创建失败");}
        catch(DuplicateKeyException duplicate){child=routeMapper.selectByOwner(previous.getActivityId(),previous.getTopicId(),memberId,instance);if(child==null)throw duplicate;}
        if(themeBroadcastService!=null)themeBroadcastService.enqueue(child,readEngineState(child.getStateJson()));
        if(stageRest!=null)recordStageRestAction(child,memberId,firstNode,stageRest);
        return themeViewOf(child);
    }

    private static final class Scope {
        private final Long memberId; private final Long activityId; private final CmsTopic topic;
        private final List<CmsRegistration> registrations;
        private Scope(Long memberId, Long activityId, CmsTopic topic, List<CmsRegistration> registrations) {
            this.memberId = memberId; this.activityId = activityId; this.topic = topic;
            this.registrations = registrations;
        }
    }
}
