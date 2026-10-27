package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.domain.PlayNote;
import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.domain.PlayTeamMember;
import com.chengyinhub.business.domain.PlayerObjectCard;
import com.chengyinhub.business.domain.vo.PlayerObjectCardVO;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayNoteMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.IWeChatMiniSessionService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.ai.PhotoCheckVisionResult;
import com.chengyinhub.business.service.ai.PhotoCheckVisionService;
import com.chengyinhub.business.service.support.WeRunDecryptor;
import com.chengyinhub.business.service.IAdvancedGameRuntimeService;
import com.chengyinhub.business.service.IPlayBingoService;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.AdvancedGameConfigValidator;
import com.chengyinhub.business.service.support.AdvancedGameRandom;
import com.chengyinhub.business.util.AdvancedGamePublicProjection;
import com.chengyinhub.business.util.StoryVars;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chengyinhub.common.constant.RewardPolicy;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 计时、随机、分支、排行与多人轮次都收口在这一深模块。 */
@Service
public class AdvancedGameRuntimeServiceImpl implements IAdvancedGameRuntimeService {

    private static final Logger logger = LoggerFactory.getLogger(AdvancedGameRuntimeServiceImpl.class);

    private static final int ATTEMPT_TYPE_ADVANCED = 8;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    /* 五个「设备报数」玩法的反作弊常量。客户端报的毫秒数是设备时钟算的,而设备时钟玩家改得动,
       所以每一条成绩都要拿 START_CHALLENGE 记的**服务器时间**复核一遍。 */
    /** 人的反应极限。比这还快的只能是提前按下去的 */
    private static final long MIN_HUMAN_REACTION_MS = 120L;
    /** 摇一摇每秒最多能撞几下。再快就不是手在摇了 */
    private static final long MAX_SHAKE_HITS_PER_SECOND = 12L;
    /** 网络往返与两端时钟的宽容量。给太小会把正常玩家judged成作弊 */
    private static final long CLOCK_SLACK_MS = 1500L;
    /** C1:一次挑战身份的有效期 = 配置时长 + 这段余量。过期的成绩不结算,只能重新开始 */
    private static final long ATTEMPT_TTL_SLACK_MS = 10L * 60L * 1000L;
    /** C1:挑战身份落在 JS 安全整数内,小程序按 Number 读不丢精度 */
    private static final long JS_SAFE_INTEGER_MASK = (1L << 53) - 1L;
    /** C1:带服务端挑战身份(attemptId)的提交动作 → 对应玩法段 */
    private static final Map<String, String> ATTEMPT_BOUND_SUBMITS;
    static {
        Map<String, String> submits = new HashMap<>();
        submits.put("SUBMIT_REACTION", "reaction");
        submits.put("SUBMIT_BALL_SHAKE", "ballShake");
        submits.put("SUBMIT_QUIET_HOLD", "quietHold");
        submits.put("SUBMIT_SHOUT", "shout");
        submits.put("SUBMIT_STOPWATCH", "stopwatch");
        submits.put("SUBMIT_COMPASS", "compass");
        ATTEMPT_BOUND_SUBMITS = Collections.unmodifiableMap(submits);
    }
    /**
     * C1 传感器小游戏的可信边界。这六段的成绩全由本机传感器/时钟产出,服务端只能复核
     * 「没超过真实用时」「方位在容差内」—— 证明不了真做到了,改客户端就能伪造。
     * ⇒ 只算娱乐成绩:可以过关、发探索值与徽章,不许触发积分、券、掉落等资金权益(C1 选项 A)。
     */
    static final List<String> SELF_REPORTED_SECTIONS = Collections.unmodifiableList(
            Arrays.asList("reaction", "ballShake", "quietHold", "shout", "stopwatch", "compass"));
    /** 订阅消息模板 id 的长度上限,与 AdvancedGameConfigValidator 里 subscribeTmplId 同一条口径 */
    private static final int MAX_SUBSCRIBE_TMPL_ID = 64;
    /** 拍照审核·取景轮廓不透明度:缺省 40，兜住 0–100(与 AdvancedGameConfigValidator 同一口径) */
    private static final int DEFAULT_FRAME_OPACITY = 40;
    private static final int MAX_FRAME_OPACITY = 100;

    @Autowired(required = false)
    private com.chengyinhub.business.service.IGameSessionRuntimeService gameSessionRuntimeService;

    private java.util.function.BiConsumer<String,Long> photoEvidenceVerifier = com.chengyinhub.common.utils.file.FileOssUploadUtils::verifyUploadedPhoto;
    @Autowired
    private com.chengyinhub.business.service.support.AppMediaReferenceGuard mediaReferences;

    @Autowired
    private GameRuntimeSessionMapper sessionMapper;
    @Autowired
    private IWeChatMiniSessionService weChatMiniSessionService;
    @Autowired
    private com.chengyinhub.business.service.support.VerifiedWeRunService verifiedWeRunService;
    @Autowired
    private IWxContentSecurityService wxContentSecurityService;
    @Autowired
    private CmsTopicNodeMapper nodeMapper;
    @Autowired
    private CmsMemberTemplateMapper templateMapper;
    @Autowired
    private CmsActivityMapper activityMapper;
    @Autowired
    private CmsRegistrationMapper registrationMapper;
    @Autowired
    private PlayTeamMapper teamMapper;
    @Autowired
    private PlayAttemptLogMapper attemptMapper;
    /** 预制人生 note 留言的跨玩家存储;测试排空时允许为 null(note 段不启用就不会用到)。 */
    @Autowired(required = false)
    private PlayNoteMapper playNoteMapper;

    /**
     * 主题级九宫格。★ 只为玩家呈现进度,**不参与任何通关判定**。
     * 与节点完成那条链路同一条纪律:它读挂了(或没接上)只是没有这一段。
     */
    @Autowired(required = false)
    private IPlayBingoService playBingoService;

    @Autowired
    private com.chengyinhub.business.mapper.PlayPredictMapper predictMapper;

    /** DIY 作品名的 openid 来源(msgSecCheck v2 必填);测试排空时允许为 null,不拿掉 fail-close。 */
    @Autowired(required = false)
    private com.chengyinhub.business.mapper.UmsMemberMapper umsMemberMapper;

    /**
     * 判错扣血的落点(R9-ACTIONS):玩法判错要写本局 state_json,由旅程运行时负责
     * 幂等与 CAS;非旅程主题在那边直接 no-op。测试排空时允许为 null。
     */
    @Autowired(required = false)
    private com.chengyinhub.business.service.ITopicRouteRuntimeService topicRouteRuntimeService;

    /**
     * M1 回执自带新世界:玩法动作回包(含判错扣血后的世界)追加本局 stateVersion 与 Encounter。
     * 组装一律复用只读解析;测试排空/缺装配时为 null,既有行为不变。
     */
    @Autowired(required = false)
    private com.chengyinhub.business.service.IPlayEncounterService playEncounterService;

    /**
     * 预制人生 photoCheck 的服务端视觉判定。测试排空时允许为 null ——
     * 排空 = 拿不到结论,一律按降级口径走,绝不假装通过。
     */
    @Autowired(required = false)
    private PhotoCheckVisionService photoCheckVisionService;

    /**
     * 拍物成卡:photoCheck 真判过之后铸一张立体藏品卡(契约 §4.2)。
     * 测试排空 / 灰度没开时为 null —— 铸不出卡只影响藏品库,
     * 判定与得分必须原样成立,所以整段挂在事务提交之后且吞异常。
     */
    @Autowired(required = false)
    private com.chengyinhub.business.service.ObjectCardService objectCardService;

    /** 铸卡失败要出声:玩家看得见的那张卡凭空没来,只落 log.error 等于没人知道。 */
    @Autowired(required = false)
    private AlarmService cardMintAlarmService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AdvancedGameConfigValidator configValidator = new AdvancedGameConfigValidator();
    private AdvancedGameClock clock = Date::new;
    private AdvancedGameRandom random = new SecureRandom()::nextInt;
    /** C1 挑战身份的随机源。测试可替换 */
    private java.util.function.LongSupplier attemptIdSource = new SecureRandom()::nextLong;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> start(Long memberId, Long activityId, Long topicId, Long nodeId, Long teamId) {
        return start(memberId, activityId, topicId, nodeId, teamId, null);
    }

    private Map<String, Object> start(Long memberId, Long activityId, Long topicId, Long nodeId, Long teamId,
            GameRuntimeSession retrySource) {
        GameInput input = requireInput(memberId, activityId, topicId, nodeId);
        if (retrySource != null) input = new GameInput(input.activityId, retrySource.getTemplateId(), retrySource.getConfigSnapshotJson(), null);
        if (settledReadOnly(input.existing)) return state(memberId, input.existing.getId());
        requireRouteNodeAccess(memberId, activityId, topicId, nodeId);
        JsonNode config = readJson(input.configJson, "高级玩法配置不可用");
        JsonNode shared = gameSessionRuntimeService == null ? null : gameSessionRuntimeService.sharedRouteSnapshot(memberId, topicId, activityId, nodeId);
        JsonNode zeroChallenge = shared != null ? shared.get("challenge") : topicRouteRuntimeService == null ? null : topicRouteRuntimeService.zeroChallenge(memberId, topicId, input.activityId, nodeId);
        boolean personalGameplay=shared==null && topicRouteRuntimeService!=null && topicRouteRuntimeService.genericPlayer(memberId,topicId,input.activityId);
        boolean multiplayer = shared != null || !personalGameplay && enabled(config.get("multiplayer"));
        if (shared != null) {
            gameSessionRuntimeService.assertSharedRouteWritable(memberId, topicId, activityId, nodeId, input.existing == null ? Integer.valueOf(shared.path("authorityEpoch").asInt()) : runtimeEpoch(input.existing));
            Long actualTeam = shared.path("teamId").asLong();
            if (teamId != null && !actualTeam.equals(teamId)) throw new ServiceException("队伍与本场归属不匹配", 403);
            teamId = actualTeam;
        }
        if (input.existing == null && multiplayer && (teamId == null || teamId <= 0)) {
            teamId = inferTeamId(memberId, input.activityId);
        }

        int ownerType = input.existing != null ? input.existing.getOwnerType()
                : (multiplayer && teamId != null && teamId > 0
                    ? GameRuntimeSession.OWNER_TEAM : GameRuntimeSession.OWNER_PLAYER);
        long ownerId = input.existing != null ? input.existing.getOwnerId()
                : (ownerType == GameRuntimeSession.OWNER_TEAM ? teamId : memberId);
        List<PlayTeamMember> teamMembers = Collections.emptyList();
        PlayTeam team = null;
        if (ownerType == GameRuntimeSession.OWNER_TEAM) {
            if (activityId == null || activityId <= 0) throw new IllegalArgumentException("队伍玩法仅支持活动场次");
            team = requireTeam(ownerId, memberId, activityId);
            teamMembers = teamMapper.selectActiveMembers(ownerId);
            if (enabled(config.get("multiplayer"))) validateTeamSize(config.get("multiplayer"), teamMembers);
        }

        GameRuntimeSession existing = input.existing != null ? input.existing
                : shared != null ? sessionMapper.selectOwnerByInstance(input.activityId, topicId, nodeId, ownerType, ownerId, sharedInstance(shared))
                : personalGameplay ? sessionMapper.selectOwnerByInstance(input.activityId,topicId,nodeId,ownerType,ownerId,topicRouteRuntimeService.gameplayInstance(memberId,topicId,input.activityId,nodeId))
                : sessionMapper.selectByOwner(input.activityId, topicId, nodeId, ownerType, ownerId);
        if (existing != null) {
            if (shared != null) {
                if (runtimeEpoch(existing)==null) throw new ServiceException("共享玩法权威版本缺失，请重新建立玩法实例",409);
                gameSessionRuntimeService.assertSharedRouteWritable(memberId,topicId,activityId,nodeId,runtimeEpoch(existing));
            }
            if (objectState(existing.getStateJson()).has("zeroChallengeInstance")
                    && !Integer.valueOf(GameRuntimeSession.STATUS_RUNNING).equals(existing.getStatus())) return toView(existing, memberId);
            if (zeroChallenge != null && Integer.valueOf(GameRuntimeSession.STATUS_RUNNING).equals(existing.getStatus())
                    && existing.getDeadlineAt() != null && clock.now().after(existing.getDeadlineAt())) {
                String timeoutId = "timeout:" + existing.getId();
                JsonNode receipt = shared != null
                        ? gameSessionRuntimeService.recordSharedZeroChallengeResult(memberId, topicId, input.activityId, nodeId, existing.getPlayInstanceId(), timeoutId, false, null, true, runtimeEpoch(existing))
                        : topicRouteRuntimeService.recordZeroChallengeResult(memberId, topicId, input.activityId, nodeId, existing.getPlayInstanceId(), timeoutId, false, null, true);
                ObjectNode failed = objectState(existing.getStateJson());
                failed.set("challenge", receipt.deepCopy());
                int oldVersion = existing.getVersion();
                existing.setStateJson(writeJson(failed)); existing.setStatus(GameRuntimeSession.STATUS_FAILED);
                existing.setCompletedAt(clock.now()); existing.setUpdateTime(clock.now());
                if (sessionMapper.updateStateCas(existing) != 1) throw versionConflict(requireAccessible(existing.getId(), memberId), memberId);
                existing.setVersion(oldVersion + 1);
                return toView(existing, memberId);
            }
            boolean wasRunning=Integer.valueOf(GameRuntimeSession.STATUS_RUNNING).equals(existing.getStatus());
            if (zeroChallenge == null) existing = expireIfNeeded(existing,memberId);
            if(wasRunning && !Integer.valueOf(GameRuntimeSession.STATUS_RUNNING).equals(existing.getStatus()) && config.has("outcomeRules"))return toView(existing,memberId);
            return toView(existing, memberId);
        }

        if(config.has("outcomeRules") && zeroChallenge==null) {
            if(shared!=null)gameSessionRuntimeService.assertSharedGameplayOutcomeStart(memberId,topicId,input.activityId,nodeId,sharedInstance(shared),shared.path("authorityEpoch").asInt());
            else if(personalGameplay)topicRouteRuntimeService.assertGameplayOutcomeStart(memberId,topicId,input.activityId,nodeId,topicRouteRuntimeService.gameplayInstance(memberId,topicId,input.activityId,nodeId));
            else throw new IllegalStateException("Outcome settlement requires a frozen theme play-through");
        }
        // 时段只限制新尝试；恢复已有会话仍按原 deadline 过期，不能被作者的新时段挡住。
        requireWithinTimeWindow(config.get("timeWindow"));
        Date now = clock.now();
        ObjectNode state = initialState(config, team, teamMembers);
        if (config.has("outcomeRules")) state.put("attemptOrdinal", 1);
        if (shared != null) {state.put("runtimeAuthorityEpoch", shared.path("authorityEpoch").asInt());state.put("originalGameSessionId",shared.path("gameSessionId").asLong());}
        else if(zeroChallenge != null || config.has("outcomeRules") && (enabled(config.path("branch")) || enabled(config.path("predict"))))state.put("originalRouteSessionId",topicRouteRuntimeService.gameplaySessionId(memberId,topicId,input.activityId));
        if (zeroChallenge != null) {state.put("zeroChallengeInstance", zeroChallenge.path("instanceId").asText()); state.set("challenge", zeroChallenge.deepCopy());}
        GameRuntimeSession session = new GameRuntimeSession();
        session.setActivityId(input.activityId);
        session.setPlayInstanceId(shared != null ? sharedInstance(shared) : topicRouteRuntimeService == null ? "default" : topicRouteRuntimeService.genericPlayer(memberId,topicId,input.activityId) ? topicRouteRuntimeService.gameplayInstance(memberId,topicId,input.activityId,nodeId) : topicRouteRuntimeService.playInstance(memberId,topicId,input.activityId));
        session.setTopicId(topicId);
        session.setNodeId(nodeId);
        session.setTemplateId(input.templateId);
        session.setOwnerType(ownerType);
        session.setOwnerId(ownerId);
        session.setConfigSnapshotJson(input.configJson);
        session.setStateJson(writeJson(state));
        session.setStatus(GameRuntimeSession.STATUS_RUNNING);
        session.setScore(0);
        session.setCompletedUnits(0);
        session.setVersion(0);
        session.setStartedAt(now);
        session.setDeadlineAt(deadline(config, now));
        session.setCreateTime(now);
        session.setUpdateTime(now);
        try {
            if (sessionMapper.insert(session) != 1) throw new IllegalStateException("高级玩法启动失败");
        } catch (DuplicateKeyException e) {
            GameRuntimeSession raced = sessionMapper.selectOwnerByInstance(input.activityId, topicId, nodeId, ownerType, ownerId, session.getPlayInstanceId());
            if (raced == null) throw e;
            return toView(expireIfNeeded(raced,memberId), memberId);
        }
        return toView(session, memberId);
    }

    private Map<String, Object> restart(GameRuntimeSession session,
            PlayTeam team, List<PlayTeamMember> teamMembers, Long memberId) {
        JsonNode config = readJson(session.getConfigSnapshotJson(), "高级玩法配置不可用");
        if(config.has("outcomeRules") && enabled(config.path("predict")) && objectState(session.getStateJson()).path("predict").has("betId")) {
            ObjectNode previous=objectState(session.getStateJson());java.sql.Date day=java.sql.Date.valueOf(previous.path("predict").path("playDay").asText());
            com.chengyinhub.business.domain.PlayPredictResult round=predictMapper.selectResult(session.getNodeId(),day);
            if(!predictRevealDue(config,day) || round==null || Integer.valueOf(0).equals(round.getSettleStatus()))throw new IllegalStateException("这一注还在等待揭晓，不能重开同一尝试");
        }
        requireWithinTimeWindow(config.get("timeWindow"));
        if(session.getOwnerType()!=GameRuntimeSession.OWNER_PLAYER || topicRouteRuntimeService==null || !topicRouteRuntimeService.genericPlayer(memberId,session.getTopicId(),session.getActivityId()))validateTeamSize(config.get("multiplayer"), teamMembers);
        Date now = clock.now();
        int oldVersion = session.getVersion();
        // 重试是同一局的新尝试，规则和模板版本保持首次开局时的快照。
        if(config.has("outcomeRules") && !readJson(session.getStateJson(),"Invalid attempt state").has("zeroChallengeInstance")) {
            if(runtimeEpoch(session)!=null)gameSessionRuntimeService.assertSharedGameplayOutcomeStart(memberId,session.getTopicId(),session.getActivityId(),session.getNodeId(),session.getPlayInstanceId(),runtimeEpoch(session));
            else topicRouteRuntimeService.assertGameplayOutcomeStart(memberId,session.getTopicId(),session.getActivityId(),session.getNodeId(),session.getPlayInstanceId());
        }
        ObjectNode restarted = initialState(config, team, teamMembers);
        if (config.has("outcomeRules")) restarted.put("attemptOrdinal", readJson(session.getStateJson(), "Invalid attempt state").path("attemptOrdinal").asLong(1) + 1);
        for(String origin:Arrays.asList("originalRouteSessionId","originalGameSessionId"))if(objectState(session.getStateJson()).has(origin))restarted.set(origin,objectState(session.getStateJson()).get(origin).deepCopy());
        Integer epoch = runtimeEpoch(session);
        if (epoch != null) {
            gameSessionRuntimeService.assertSharedRouteWritable(memberId, session.getTopicId(), session.getActivityId(), session.getNodeId(), epoch);
            restarted.put("runtimeAuthorityEpoch", epoch);
        }
        session.setStateJson(writeJson(restarted));
        session.setStatus(GameRuntimeSession.STATUS_RUNNING);
        session.setScore(0);
        session.setCompletedUnits(0);
        session.setStartedAt(now);
        session.setDeadlineAt(deadline(config, now));
        session.setCompletedAt(null);
        session.setUpdateTime(now);
        if (sessionMapper.restartTerminalCas(session) != 1) {
            GameRuntimeSession raced = sessionMapper.selectByOwner(session.getActivityId(), session.getTopicId(),
                    session.getNodeId(), session.getOwnerType(), session.getOwnerId());
            if (raced == null) throw new IllegalStateException("高级玩法重开失败");
            throw versionConflict(raced, memberId);
        }
        session.setVersion(oldVersion + 1);
        return toView(session, memberId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> act(Long memberId, Long sessionId, Integer expectedVersion,
            String idempotencyKey, String action, Map<String, Object> payload) {
        if (memberId == null || memberId <= 0 || sessionId == null || sessionId <= 0) {
            throw new IllegalArgumentException("参数有误");
        }
        if (StringUtils.isBlank(idempotencyKey) || idempotencyKey.length() > 64) {
            throw new IllegalArgumentException("幂等键不能为空且最多 64 位");
        }
        GameRuntimeSession session = requireAccessible(sessionId, memberId);
        boolean zeroChallengeSession = objectState(session.getStateJson()).has("zeroChallengeInstance");
        if (zeroChallengeSession) idempotencyKey = themeActionKey(sessionId, memberId + ":" + idempotencyKey);
        else if (themeScoped(memberId, session)) idempotencyKey = themeActionKey(sessionId, idempotencyKey);
        PlayAttemptLog replay = attemptMapper.selectByIdempotencyKey(idempotencyKey);
        if (replay != null) return replay(replay, memberId, sessionId);


        if("CHOOSE".equalsIgnoreCase(action) && readJson(session.getConfigSnapshotJson(),"玩法配置不可用").has("outcomeRules")) {
            if(expectedVersion==null || !expectedVersion.equals(session.getVersion()))throw versionConflict(session,memberId);
            ObjectNode state=objectState(session.getStateJson());JsonNode branch=outcomeFeedback(memberId,session,state,text(payload==null?Collections.emptyMap():payload,"optionId"));
            if(branch==null)throw new IllegalStateException("结果尚未结算，不能进入下一段");state.set("branch",branch.deepCopy());int old=session.getVersion();session.setStateJson(writeJson(state));session.setUpdateTime(clock.now());if(sessionMapper.updateFeedbackStateCas(session)!=1)throw new IllegalStateException("状态已更新，请刷新后重试");session.setVersion(old+1);Map<String,Object> result=toView(session,memberId);appendJourneyWorld(result,memberId,session);logAction(session,memberId,idempotencyKey,result);return result;
        }
        session=synchronizePredictSession(session,memberId);
        if(objectState(session.getStateJson()).path("predict").path("revealRecorded").asBoolean())return toView(session,memberId);
        // 已开局：自玩通行证在开局时有效即可打完本局；活动场次/探索票仍按活动结束等原规则。
        com.chengyinhub.business.service.support.CurrentPlayRegistration.requireToContinue(activityMapper,registrationMapper,
                memberId,session.getActivityId()!=null && session.getActivityId()>0 ? 2L : 1L,
                session.getActivityId()!=null && session.getActivityId()>0 ? session.getActivityId() : session.getTopicId(),null,clock.now());
        requireActivityWindow(session.getActivityId(), session.getTopicId());
        if (gameSessionRuntimeService != null) gameSessionRuntimeService.assertRoutePlayable(session.getActivityId(), session.getNodeId());
        requireRouteNodeAccess(memberId, session.getActivityId(), session.getTopicId(), session.getNodeId());
        JsonNode sharedRun = gameSessionRuntimeService == null ? null : gameSessionRuntimeService.sharedRouteSnapshot(memberId, session.getTopicId(), session.getActivityId(), session.getNodeId());
        if (zeroChallengeSession && "RETRY".equalsIgnoreCase(StringUtils.defaultString(action).trim())) {
            return retryZeroChallenge(session, memberId, expectedVersion, idempotencyKey, sharedRun);
        }
        if (sharedRun != null && (!session.getPlayInstanceId().equals(sharedInstance(sharedRun))
                || !Long.valueOf(sharedRun.path("teamId").asLong()).equals(session.getOwnerId()) || session.getOwnerType() != GameRuntimeSession.OWNER_TEAM)) {
            throw new ServiceException("该玩法不属于当前共享局", 409);
        }
        if(sharedRun==null && topicRouteRuntimeService!=null && topicRouteRuntimeService.genericPlayer(memberId,session.getTopicId(),session.getActivityId()) && !session.getPlayInstanceId().equals(topicRouteRuntimeService.gameplayInstance(memberId,session.getTopicId(),session.getActivityId(),session.getNodeId())))throw new ServiceException("该玩法不属于当前任务实例",409);
        if (sharedRun != null && runtimeEpoch(session)==null) throw new ServiceException("共享玩法权威版本缺失",409);
        if (sharedRun != null) gameSessionRuntimeService.assertSharedRouteWritable(memberId, session.getTopicId(), session.getActivityId(), session.getNodeId(), runtimeEpoch(session));
        if (!zeroChallengeSession) session = expireIfNeeded(session,memberId);
        if ("RETRY".equalsIgnoreCase(StringUtils.defaultString(action).trim())) {
            if (expectedVersion == null || !expectedVersion.equals(session.getVersion())) throw versionConflict(session, memberId);
            if (!canRetry(session, memberId)) throw new ServiceException("只有本人或本队队长可重试失败或超时的玩法", 403);
            if (topicRouteRuntimeService != null) topicRouteRuntimeService.requireOpeningAccess(memberId,
                    session.getTopicId(), session.getActivityId(), session.getNodeId());
            PlayTeam retryTeam = null;
            List<PlayTeamMember> retryMembers = Collections.emptyList();
            if (session.getOwnerType() == GameRuntimeSession.OWNER_TEAM) {
                retryTeam = requireTeam(session.getOwnerId(), memberId, session.getActivityId(), true);
                if (!memberId.equals(retryTeam.getLeaderMemberId())) throw new ServiceException("只有本队队长可重试", 403);
                retryMembers = teamMapper.selectActiveMembers(session.getOwnerId());
            }
            Map<String, Object> restarted = restart(session, retryTeam, retryMembers, memberId);
            logAction(session, memberId, idempotencyKey, restarted);
            return restarted;
        }
        if (!Integer.valueOf(GameRuntimeSession.STATUS_RUNNING).equals(session.getStatus())) {
            if(readJson(session.getConfigSnapshotJson(),"玩法配置不可用").has("outcomeRules") && objectState(session.getStateJson()).has("gameplayDecision")){Map<String,Object> completed=toView(session,memberId);appendJourneyWorld(completed,memberId,session);return completed;}
            throw new IllegalStateException("高级玩法已结束");
        }
        if (expectedVersion == null || !expectedVersion.equals(session.getVersion())) {
            throw versionConflict(session, memberId);
        }
        if (topicRouteRuntimeService != null) topicRouteRuntimeService.requireOpeningAccess(memberId,
                session.getTopicId(), session.getActivityId(), session.getNodeId());
        JsonNode config = readJson(session.getConfigSnapshotJson(), "高级玩法配置不可用");
        ObjectNode state = objectState(session.getStateJson());
        Map<String, Object> safePayload = payload == null ? Collections.emptyMap() : payload;
        String normalizedAction = StringUtils.defaultString(action).trim().toUpperCase();
        String selectedChallenge=zeroChallengeSession && state.path("challenge").path("nodeId").asLong()==session.getNodeId()?state.path("challenge").path("gameplayKey").asText():"";
        String selectedAction="coinFlip".equals(selectedChallenge)?"FLIP_COIN":"diceRoll".equals(selectedChallenge)?"ROLL_DICE":"random".equals(selectedChallenge)?"DRAW":"SUBMIT_"+selectedChallenge.replaceAll("([a-z])([A-Z])","$1_$2").toUpperCase();
        if(!selectedChallenge.isEmpty() && (normalizedAction.startsWith("SUBMIT_") || Arrays.asList("FLIP_COIN","ROLL_DICE","DRAW").contains(normalizedAction)) && !normalizedAction.equals(selectedAction))throw new IllegalArgumentException("恢复挑战只能提交选定玩法");
        if(!selectedChallenge.isEmpty() && "START_CHALLENGE".equals(normalizedAction) && !selectedChallenge.equals(safePayload.get("game")))throw new IllegalArgumentException("恢复挑战只能开始选定玩法");
        if(zeroChallengeSession && "REROLL_CHECK".equals(normalizedAction))throw new IllegalArgumentException("恢复挑战不消费幸运重掷");
        // R14:本次提交判定出的「选择」——分支步骤/选项、问答选项、玩法通过;判完当场写旅程本局状态。
        List<ChoiceRef> choices = new ArrayList<>();
        // 拍物成卡:这一趟真判过且节点是卡片玩法时非空。铸卡推迟到事务提交之后 ——
        // 判定回滚就不能留下卡,铸卡失败也不能反过来回滚判定。
        CardMintRequest pendingCardMint = null;
        boolean challengeTimedOut = zeroChallengeSession && session.getDeadlineAt() != null && clock.now().after(session.getDeadlineAt());
        String challengeGame = normalizedAction.startsWith("SUBMIT_") ? challengeGame(normalizedAction) : null;
        boolean challengeObjectiveSubmission = zeroChallengeSession && Arrays.asList("SUBMIT_QA", "SUBMIT_SORT", "SUBMIT_MATCH", "SUBMIT_CLASSIFY").contains(normalizedAction);
        if (challengeObjectiveSubmission) for (String kind : Arrays.asList("qa", "sort", "match", "classify")) {
            JsonNode owned = state.path("zeroChallengeObjectives").path(String.valueOf(memberId)).get(kind);
            if (owned == null) state.remove(kind); else state.set(kind, owned.deepCopy());
        }
        if (zeroChallengeSession && challengeGame != null) {
            ObjectNode mechanic = state.with(challengeGame);
            JsonNode start = state.path("zeroChallengeStarts").path(String.valueOf(memberId)).path(challengeGame);
            if (start.isNumber()) mechanic.put("startedAtMs", start.asLong()); else mechanic.remove("startedAtMs");
            mechanic.put("submitted", false);
            // 归零挑战按成员各自开表:挑战身份也按成员各存一份,共享段上只摆本人那份
            JsonNode ownAttempt = state.path("zeroChallengeAttempts").path(String.valueOf(memberId)).path(challengeGame);
            if (ownAttempt.isObject()) mechanic.set("attempt", ownAttempt.deepCopy()); else mechanic.remove("attempt");
            if ("ballShake".equals(challengeGame) && config.path(challengeGame).path("timed").asBoolean() && start.isNumber()) {
                long limit = config.path(challengeGame).path("seconds").asLong() * 1000L;
                challengeTimedOut |= limit > 0 && clock.now().getTime() - start.asLong() > limit + CLOCK_SLACK_MS;
            }
        }
        // C1:提交带了 attemptId 就必须是本人当前这一次开表(没用过、没过期)
        String attemptGame = ATTEMPT_BOUND_SUBMITS.get(normalizedAction);
        // 队伍共享局:各成员各自开表,开表时间与挑战身份按成员各存一份,提交时共享段上只摆本人那份。
        // 队友重开不再作废本人手里的号(W48R SHOULD-FIX 2);次数/交过/通过仍是全队共享口径。
        boolean teamAttemptSlots = !zeroChallengeSession && Integer.valueOf(GameRuntimeSession.OWNER_TEAM).equals(session.getOwnerType());
        boolean ownTeamSlot = teamAttemptSlots && attemptGame != null && restoreTeamMemberChallenge(state, attemptGame, memberId);
        if (attemptGame != null && !challengeTimedOut) requireCurrentAttempt(state.path(attemptGame), safePayload, memberId);
        if (!challengeTimedOut) switch (normalizedAction) {
            case "DRAW":
                draw(config, state);
                break;
            case "CHOOSE":
                choices.add(choose(config, state, safePayload, session));
                break;
            case "ASSIGN_ROLE":
                assignRole(config, state, safePayload, session, memberId);
                break;
            case "COMPLETE_UNIT":
                completeUnit(config, state, safePayload, session, memberId);
                break;
            // ===== Figma 组件库 v5.1 新玩法(node 45:248)=====
            case "SUBMIT_BLIND_TASTE":
                submitBlindTaste(config, state, safePayload, session);
                break;
            case "SUBMIT_DIY_NAME":
                submitDiyName(config, state, safePayload, memberId);
                break;
            case "SUBMIT_STEPS":
                submitSteps(config, state, safePayload, session, memberId);
                break;
            case "CLAIM_DAILY_SIGN":
                claimDailySign(config, state, session);
                break;
            case "SUBSCRIBE_TIME_WINDOW":
                subscribeTimeWindow(config, state, safePayload);
                break;
            case "START_SLOW_TASK":
                startSlowTask(config, state);
                break;
            case "CLAIM_SLOW_TASK":
                claimSlowTask(config, state, session);
                break;
            case "SUBMIT_ESTIMATE":
                submitEstimate(config, state, safePayload, session);
                break;
            case "SUBMIT_PRICE_PAIR":
                addIfPresent(choices, submitPricePair(config, state, safePayload, session));
                break;
            case "SUBMIT_HIDDEN_OBJECT":
                addIfPresent(choices, submitHiddenObject(config, state, safePayload, session));
                break;
            case "SUBMIT_PREDICT":
                submitPredict(config, state, safePayload, session, memberId);
                break;
            case "FLIP_COIN":
                flipCoin(config, state);
                break;
            case "ROLL_CHECK":
            case "REROLL_CHECK":
                if(!config.has("outcomeRules") || !enabled(config.get("check")))throw new IllegalStateException("本局未启用结果检定");
                if("ROLL_CHECK".equals(normalizedAction) && state.path("check").path("rerolled").asBoolean())throw new IllegalStateException("已重掷，不能退回初次检定结果");
                String checkAttempt="advanced:"+session.getId()+":"+state.path("attemptOrdinal").asLong(1);
                com.chengyinhub.business.domain.route.PlayCheckReceipt checkReceipt=sharedRun!=null
                        ?gameSessionRuntimeService.rollSharedGameplayCheck(memberId,session.getTopicId(),session.getActivityId(),session.getNodeId(),session.getPlayInstanceId(),checkAttempt,"REROLL_CHECK".equals(normalizedAction),runtimeEpoch(session))
                        :topicRouteRuntimeService.rollGameplayCheck(memberId,session.getTopicId(),session.getActivityId(),session.getNodeId(),session.getPlayInstanceId(),checkAttempt,"REROLL_CHECK".equals(normalizedAction));
                state.set("check",objectMapper.valueToTree(checkReceipt));
                break;
            case "SUBMIT_CHECK":
                if(!config.has("outcomeRules") || !state.path("check").has("dice"))throw new IllegalStateException("请先掷骰");
                state.with("check").put("submitted",true);
                break;
            case "ROLL_DICE":
                rollDice(config, state);
                break;
            case "START_CHALLENGE":
                startChallenge(config, state, safePayload, memberId);
                if (zeroChallengeSession) {
                    String startedGame = challengeKey(safePayload);
                    state.with("zeroChallengeStarts").with(String.valueOf(memberId)).put(startedGame, clock.now().getTime());
                    JsonNode issued = state.path(startedGame).path("attempt");
                    if (issued.isObject()) state.with("zeroChallengeAttempts").with(String.valueOf(memberId)).set(startedGame, issued.deepCopy());
                }
                if (teamAttemptSlots && ATTEMPT_BOUND_SUBMITS.containsValue(challengeKey(safePayload))) saveTeamMemberChallenge(state, challengeKey(safePayload), memberId);
                break;
            case "SUBMIT_REACTION":
                submitReaction(config, state, safePayload);
                break;
            case "SUBMIT_BALL_SHAKE":
                submitBallShake(config, state, safePayload);
                break;
            case "SUBMIT_QUIET_HOLD":
                submitQuietHold(config, state, safePayload);
                break;
            case "SUBMIT_COMPASS":
                submitCompass(config, state, safePayload, session);
                break;
            case "SUBMIT_SHOUT":
                submitShout(config, state, safePayload);
                break;
            case "SUBMIT_COUNTDOWN":
                submitCountdown(config, state);
                break;
            case "SUBMIT_STOPWATCH":
                submitStopwatch(config, state, safePayload);
                break;
            case "SUBMIT_QA":
                choices.addAll(submitQa(config, state, safePayload, session, memberId));
                break;
            case "SUBMIT_SCAN":
                submitScan(config, state, safePayload, session);
                break;
            // ===== R3 三个新玩法:排序 / 连线 / 分类 =====
            case "SUBMIT_SORT":
                addIfPresent(choices, submitSort(config, state, safePayload, session));
                break;
            case "SUBMIT_MATCH":
                addIfPresent(choices, submitMatch(config, state, safePayload, session));
                break;
            case "SUBMIT_CLASSIFY":
                addIfPresent(choices, submitClassify(config, state, safePayload, session));
                break;
            // ===== R16 时间轴对照:两个版本逐条并排,标出缺失 / 被改动的条目 =====
            case "SUBMIT_COMPARE":
                submitCompare(config, state, safePayload, session);
                break;
            // ===== 预制人生:建档 / 拍照审核 / 留言 / 限时打字 =====
            case "SUBMIT_PROFILE":
                choices.addAll(submitProfile(config, state, safePayload, session, memberId));
                break;
            case "SUBMIT_ALBUM":
                submitAlbum(config,state,safePayload,memberId);
                break;
            case "SUBMIT_PHOTO_CHECK":
                pendingCardMint = submitPhotoCheck(config, state, safePayload, session, memberId);
                break;
            case "SUBMIT_NOTE":
                submitNote(config, state, safePayload, session, memberId);
                break;
            case "SUBMIT_TYPE_IN":
                submitTypeIn(config, state, safePayload, session);
                break;
            default:
                throw new IllegalArgumentException("高级玩法动作不支持");
        }
        // 交过一次,这个挑战身份就作废(单次消费);还有次数的停表要重新开始拿新的
        if (attemptGame != null && !challengeTimedOut) consumeAttempt(state.path(attemptGame));
        if (ownTeamSlot && !challengeTimedOut) saveTeamMemberChallenge(state, attemptGame, memberId);

        if (challengeObjectiveSubmission) for (String kind : Arrays.asList("qa", "sort", "match", "classify"))
            if (state.has(kind)) state.with("zeroChallengeObjectives").with(String.valueOf(memberId)).set(kind, state.get(kind).deepCopy());
        ObjectNode chosenDecision=zeroChallengeSession && !selectedChallenge.isEmpty()?serverGameplayDecision(normalizedAction,config,state):null;
        if (zeroChallengeSession && (challengeTimedOut || normalizedAction.startsWith("SUBMIT_") || chosenDecision!=null)) {
            Boolean judged = judgedOutcome(normalizedAction, state);
            Boolean sensor = passedFact(normalizedAction, state);
            boolean objective = Arrays.asList("SUBMIT_QA", "SUBMIT_SORT", "SUBMIT_MATCH", "SUBMIT_CLASSIFY").contains(normalizedAction);
            boolean passed = chosenDecision!=null?"PASS".equals(chosenDecision.path("outcome").asText()):objective ? challengeObjectivesPassed(config, state,selectedChallenge) : Boolean.TRUE.equals(sensor);
            boolean retry = challengeTimedOut || !"SUBMIT_BALL_SHAKE".equals(normalizedAction) && (chosenDecision!=null?"FAIL".equals(chosenDecision.path("outcome").asText()):(Boolean.FALSE.equals(judged) || Boolean.FALSE.equals(sensor)));
            Long hits = !challengeTimedOut && "SUBMIT_BALL_SHAKE".equals(normalizedAction) ? Long.valueOf(state.path("ballShake").path("hits").asLong()) : null;
            JsonNode receipt = sharedRun != null
                    ? gameSessionRuntimeService.recordSharedZeroChallengeResult(memberId, session.getTopicId(), session.getActivityId(), session.getNodeId(), session.getPlayInstanceId(), idempotencyKey, passed, hits, retry, runtimeEpoch(session))
                    : topicRouteRuntimeService.recordZeroChallengeResult(memberId, session.getTopicId(), session.getActivityId(), session.getNodeId(), session.getPlayInstanceId(), idempotencyKey, passed, hits, retry);
            state.set("challenge", receipt.deepCopy());
            if (challengeGame != null) state.with("zeroChallengeStarts").with(String.valueOf(memberId)).remove(challengeGame);
            if (challengeGame != null) state.with("zeroChallengeAttempts").with(String.valueOf(memberId)).remove(challengeGame);
            if (hits != null) state.with("ballShake").put("hits", receipt.path("progress").asLong()).put("submitted", receipt.path("success").asBoolean()).put("passed", receipt.path("success").asBoolean());
            if (receipt.path("success").asBoolean() || receipt.has("requiredCompletions") && receipt.path("gameCompleted").asBoolean()) session.setStatus(GameRuntimeSession.STATUS_COMPLETED);
            else if (retry) session.setStatus(GameRuntimeSession.STATUS_FAILED);
        }
        ObjectNode gameplayDecision = zeroChallengeSession || !config.has("outcomeRules") ? null : serverGameplayDecision(normalizedAction, config, state);
        if (gameplayDecision != null) {
            gameplayDecision.put("nodeId", session.getNodeId()); state.set("gameplayDecision", gameplayDecision.deepCopy());
            session.setStatus("PASS".equals(gameplayDecision.path("outcome").asText()) ? GameRuntimeSession.STATUS_COMPLETED : GameRuntimeSession.STATUS_FAILED);
        }
        int oldVersion = session.getVersion();
        Date now = clock.now();
        session.setStateJson(writeJson(state));
        session.setUpdateTime(now);
        if (sessionMapper.updateStateCas(session) != 1) {
            throw new IllegalStateException("状态已更新，请刷新后重试");
        }
        session.setVersion(oldVersion + 1);
        // 玩法判错在本事务内落进旅程本局:扣血/计数与本次玩法状态更新同生共死;
        // 非旅程主题在旅程运行时里 no-op,行为零变化。
        boolean scopedTheme=topicRouteRuntimeService!=null && topicRouteRuntimeService.nodeRuntimeConfig(memberId,session.getTopicId(),session.getNodeId(),session.getActivityId())!=null;
        if (zeroChallengeSession) {
            // 挑战只写恢复回执，不产生普通任务完成事实。
        } else if (config.has("outcomeRules")) {
            if (gameplayDecision != null) {
                String attemptId = "advanced:" + session.getId() + ":" + state.path("attemptOrdinal").asLong(1);
                List<String[]> refs = new ArrayList<>(); for (ChoiceRef choice : choices) if (choice != null) refs.add(new String[]{choice.stepOrKit, choice.optionId});
                if (sharedRun != null) gameSessionRuntimeService.recordSharedGameplayOutcome(memberId, session.getTopicId(), session.getActivityId(), session.getNodeId(), session.getPlayInstanceId(), attemptId, gameplayDecision, refs, runtimeEpoch(session));
                else if (scopedTheme) topicRouteRuntimeService.recordGameplayOutcome(memberId, session.getTopicId(), session.getActivityId(), session.getNodeId(), session.getPlayInstanceId(), attemptId, gameplayDecision, refs);
                else throw new IllegalStateException("Outcome settlement requires a frozen theme play-through");
            }
        } else if (sharedRun != null) {
            List<String[]> refs = new ArrayList<>();
            for (ChoiceRef choice : choices) if (choice != null) refs.add(new String[]{choice.stepOrKit, choice.optionId});
            gameSessionRuntimeService.recordSharedRouteResult(memberId, session.getTopicId(), session.getActivityId(), session.getNodeId(),
                    session.getPlayInstanceId(), idempotencyKey, sharedMechanicsReady(config, state), refs, runtimeEpoch(session));
        } else if(scopedTheme) {
            Boolean judged=judgedOutcome(normalizedAction,state);Boolean fact=topicRouteRuntimeService.genericPlayer(memberId,session.getTopicId(),session.getActivityId())?Boolean.valueOf(sharedMechanicsReady(config,state)):(judged!=null?judged:passedFact(normalizedAction,state));
            List<String[]> refs=new ArrayList<>();for(ChoiceRef choice:choices)if(choice!=null)refs.add(new String[]{choice.stepOrKit,choice.optionId});
            if(topicRouteRuntimeService.genericPlayer(memberId,session.getTopicId(),session.getActivityId()) && Boolean.TRUE.equals(fact)) {
                refs.clear();
                for(String kind:Arrays.asList("qa","sort","match","classify"))if(enabled(config.get(kind))) {
                    if("qa".equals(kind) && "PICK".equals(config.path(kind).path("mode").asText())) {
                        if(config.path(kind).path("multi").asBoolean())for(JsonNode id:state.path(kind).path("lastOptionIds"))refs.add(new String[]{kind,id.asText()});
                        else refs.add(new String[]{kind,state.path(kind).path("lastOptionId").asText()});
                    } else refs.add(new String[]{kind,null});
                }
            }
            topicRouteRuntimeService.recordNodeResult(memberId,session.getTopicId(),session.getActivityId(),session.getNodeId(),session.getPlayInstanceId(),idempotencyKey,fact,Boolean.FALSE.equals(judged),randomOutcome(normalizedAction,config,state),refs);
        } else {
        Boolean passed = judgedOutcome(normalizedAction, state);   // null = 非判定型动作
        if (passed != null && !passed && topicRouteRuntimeService != null) {
            topicRouteRuntimeService.recordMistake(memberId, session.getTopicId(), session.getActivityId(), session.getNodeId());
        }
        // 阶段 1(链路 D):成败落成可引用事实 sys.passed.{nodeId},以本次动作 idempotencyKey 幂等,
        // 与玩法状态更新同一事务;非判定动作一律不写。扣血清单(judgedOutcome)之外,
        // 挑战 / 体感 / 打字 / 拍照 / 猜数字 / 计步也有服务端权威成败 —— 只记事实、不扣血。
        Boolean fact = passed != null ? passed : passedFact(normalizedAction, state);
        if (fact != null && topicRouteRuntimeService != null) {
            topicRouteRuntimeService.recordPassed(memberId, session.getTopicId(), session.getActivityId(), session.getNodeId(),
                    idempotencyKey, fact);
        }
        // 随机类结果(抛硬币 / 掷骰子 / 抽卡)写 sys.outcome.{nodeId},供后面的剧情按结果分支。
        int outcome = randomOutcome(normalizedAction, config, state);
        if (outcome > 0 && topicRouteRuntimeService != null) {
            topicRouteRuntimeService.recordOutcome(memberId, session.getTopicId(), session.getActivityId(), session.getNodeId(),
                    idempotencyKey, outcome);
        }
        // R14 选择结果当场写旅程本局状态:同一次事务,与本次玩法状态更新同生共死;
        // 同 id 重放由旅程运行时的 routeActionId 幂等兜住,不二次写。
        if (topicRouteRuntimeService != null) {
            for (ChoiceRef choice : choices) {
                if (choice != null) {
                    topicRouteRuntimeService.recordChoice(memberId, session.getTopicId(), session.getActivityId(), session.getNodeId(),
                            choice.stepOrKit, choice.optionId);
                }
            }
        }
        }
        Map<String, Object> view = toView(session, memberId);
        appendJourneyWorld(view, memberId, session);
        logAction(session, memberId, idempotencyKey, view);
        // 待铸卡挂在 logAction 之后:幂等回放存的是上面那一刻的回包,不带这个内部标记,
        // 同一个 idempotencyKey 重放不会再铸一次。
        if (pendingCardMint != null) {
            view.put(PENDING_CARD_MINT, pendingCardMint.bind(memberId, session.getNodeId()));
        }
        return view;
    }

    /** 回包上的内部键:只在 act 与 finishPendingCardMint 之间存在,收尾时一定被拿走,不会序列化出去。 */
    static final String PENDING_CARD_MINT = "_pendingCardMint";

    /** 铸卡请求:判过这一刻手上有的东西(照片、卡面配置、谁、哪个节点),够事务之外复现这张卡。 */
    private static final class CardMintRequest {
        private final String imageUrl;
        private final JsonNode photoCheck;
        private Long memberId;
        private Long nodeId;

        private CardMintRequest(String imageUrl, JsonNode photoCheck) {
            this.imageUrl = imageUrl;
            this.photoCheck = photoCheck;
        }

        private CardMintRequest bind(Long memberId, Long nodeId) {
            this.memberId = memberId;
            this.nodeId = nodeId;
            return this;
        }
    }

    /**
     * 事务之外的收尾(监工复审必须改 2):铸卡要并行等抠图与认物最多 4 秒,以前挂在 afterCommit 里,
     * 而 Spring 要等提交回调跑完才在 cleanupAfterCompletion 归还连接 —— 每次判过多占一个库连接 4 秒,
     * Druid 只有 20 个。现在 act 只判定,控制器在 act 返回(已提交、已还连接)之后调这里。
     * 三条原约束不变:判定回滚 → act 抛出,走不到这里;铸卡失败 → 判定早已提交;回包带卡是一次性的。
     *
     * <p>★ 不许加 {@code @Transactional}(测试钉住):加了就又回到占着连接等。
     */
    @Override
    public Map<String, Object> finishPendingCardMint(Map<String, Object> view) {
        if (view == null) {
            return null;
        }
        Object pending = view.remove(PENDING_CARD_MINT);
        if (pending instanceof CardMintRequest) {
            CardMintRequest mint = (CardMintRequest) pending;
            PlayerObjectCard minted = mintQuietly(mint.memberId, mint.nodeId, mint);
            if (minted != null) {
                try {
                    view.put("objectCard", PlayerObjectCardVO.from(minted, reference -> mediaReferences.visibleUrl(reference, mint.memberId)));
                } catch (RuntimeException unavailable) {
                    logger.error("Minted card media projection unavailable type={}", unavailable.getClass().getSimpleName());
                }
            }
        }
        return view;
    }

    /**
     * 铸卡整段吞异常:玩家这一关已经过了,不能因为藏品库写不进去就把通关改成失败。
     * 返回 null = 这一趟压根没有卡(没装配 / 没有来源节点 / 铸炸了),调用方因此也不许回一张卡。
     */
    private PlayerObjectCard mintQuietly(Long memberId, Long nodeId, CardMintRequest mint) {
        if (objectCardService == null || nodeId == null || nodeId <= 0) {
            return null;
        }
        try {
            return objectCardService.mint(memberId, mint.imageUrl, mint.photoCheck,
                    com.chengyinhub.business.service.ObjectCardService.ORIGIN_PLAY, String.valueOf(nodeId));
        } catch (Exception e) {
            logger.error("藏品卡铸造失败 member={} node={},通关判定不受影响", memberId, nodeId, e);
            if (cardMintAlarmService != null) {
                cardMintAlarmService.alarm("藏品卡铸造失败",
                        "member=" + memberId + " node=" + nodeId + " 玩家已过这一关但卡没铸出来,异常类型="
                                + e.getClass().getSimpleName());
            }
            return null;
        }
    }

    /**
     * M1 回执自带新世界:解析这一站的 Encounter(组装全部在 {@link com.chengyinhub.business.service.IPlayEncounterService})。
     * 解析不可用(非旅程主题/节点没配玩法)时返回 null,回包缺省该字段。
     */
    private Map<String, Object> encounterView(Long memberId, Long topicId, Long activityId, Long nodeId) {
        if (playEncounterService == null || topicId == null || nodeId == null || nodeId <= 0L) return null;
        try {
            return playEncounterService.resolve(memberId, topicId, activityId, nodeId);
        } catch (ServiceException unusable) {
            return null;
        }
    }

    /** 玩法回包追加本局 stateVersion 与 encounter;判错扣血后的新血值随 Encounter 一并反映。 */
    private void appendJourneyWorld(Map<String, Object> view, Long memberId, GameRuntimeSession session) {
        if (view == null || session == null) return;
        Map<String, Integer> vitals = journeyVitals(memberId, session.getTopicId(), session.getActivityId());
        if (vitals != null) {
            view.put("hp", vitals.get("hp"));
            view.put("luck", vitals.get("luck"));
        }
        Map<String, Object> encounter = encounterView(memberId, session.getTopicId(), session.getActivityId(), session.getNodeId());
        if (encounter == null) return;
        view.put("encounter", encounter);
        Object stateVersion = encounter.get("stateVersion");
        if (stateVersion != null) view.put("stateVersion", stateVersion);
    }

    /** R18-RECEIPT 收口:回包顶层带本局 hp / luck 当前值;缺装配 / 无本局时缺省。 */
    private Map<String, Integer> journeyVitals(Long memberId, Long topicId, Long activityId) {
        if (topicRouteRuntimeService == null || topicId == null || topicId <= 0L) return null;
        try {
            return topicRouteRuntimeService.vitals(memberId, topicId, activityId);
        } catch (ServiceException unusableVitals) {
            return null;
        }
    }

    /** 旧 version 被拒:data 自带当前玩法视图(含本局 stateVersion 与 encounter),客户端免再拉一次。 */
    private ServiceException versionConflict(GameRuntimeSession session, Long memberId) {
        Map<String, Object> current = toView(session, memberId);
        appendJourneyWorld(current, memberId, session);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("view", current);
        return new ServiceException("状态已更新，请刷新后重试", HttpStatus.CONFLICT).setData(data);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> state(Long memberId, Long sessionId) {
        GameRuntimeSession session=requireAccessible(sessionId,memberId);
        session=runtimeEpoch(session)==null || readJson(session.getConfigSnapshotJson(),"玩法配置不可用").has("outcomeRules")?expireIfNeeded(session,memberId):session;
        return toView(synchronizePredictSession(session,memberId),memberId);
    }

    @Override
    public List<Map<String, Object>> leaderboard(Long memberId, Long activityId, Long topicId, Long nodeId) {
        GameInput input = requireInput(memberId, activityId, topicId, nodeId,false);
        JsonNode config = readJson(input.configJson, "高级玩法配置不可用");
        JsonNode board = config.get("leaderboard");
        if (!enabled(board)) throw new IllegalStateException("该玩法未开启排行榜");
        String metric = board.path("metric").asText();
        String scope = board.path("scope").asText();
        int limit = board.path("limit").asInt(50);
        Long scopedActivity = "ACTIVITY".equals(scope) ? input.activityId : null;
        List<Map<String, Object>> rows = sessionMapper.selectLeaderboard(
                scopedActivity, topicId, nodeId, metric, limit);
        if (rows == null) return new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) rows.get(i).put("rank", i + 1);
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String completeGate(Long memberId, Long activityId, Long topicId, Long nodeId) {
        CmsTopicNode node = nodeMapper.selectCmsTopicNodeById(nodeId);
        if (node == null || !topicId.equals(node.getTopicId())) return null;
        long actId = activityId == null ? 0L : activityId;
        // 已有本局或本路线已开局 = 继续打完(自玩通行证中途到期不打断)；尚未开局按新使用校验。
        if (existingSession(memberId, actId, topicId, nodeId) != null || routeStarted(memberId, actId, topicId)) {
            com.chengyinhub.business.service.support.CurrentPlayRegistration.requireToContinue(activityMapper,registrationMapper,
                    memberId,actId>0 ? 2L : 1L,actId>0 ? actId : topicId,null,clock.now());
        } else {
            com.chengyinhub.business.service.support.CurrentPlayRegistration.require(activityMapper,registrationMapper,
                    memberId,actId>0 ? 2L : 1L,actId>0 ? actId : topicId,null,clock.now());
        }
        requireActivityWindow(actId, topicId);
        if (gameSessionRuntimeService != null) gameSessionRuntimeService.assertRoutePlayable(actId, nodeId);
        requireRouteNodeAccess(memberId, actId, topicId, nodeId);
        if (gameSessionRuntimeService != null && gameSessionRuntimeService.sharedRouteSnapshot(memberId, topicId, actId, null) != null) {
            throw new ServiceException("队伍共享任务请使用本场任务结算，不能重复领取个人节点奖励", 409);
        }
        if (topicRouteRuntimeService!=null && topicRouteRuntimeService.genericPlayer(memberId,topicId,actId)) throw new ServiceException("通用个人任务请使用任务结算，不能领取旧节点奖励",409);
        GameRuntimeSession session = existingSession(memberId, actId, topicId, nodeId);
        if (session == null) {
            // 尚未开局才读取当前模板。已开局时，移除模板不能绕过本局的完成条件。
            if (node.getTemplateId() == null) return null;
            CmsMemberTemplate template = templateMapper.selectCmsMemberTemplateById(node.getTemplateId());
            JsonNode frozen=topicRouteRuntimeService==null?null:topicRouteRuntimeService.nodeRuntimeConfig(memberId,topicId,nodeId,activityId);
            if (frozen==null && (template == null || StringUtils.isBlank(template.getAdvancedConfigJson()))) return null;
            String configJson = configValidator.validateAndNormalize(frozen==null?template.getAdvancedConfigJson():frozen.toString());
            if (!configValidator.hasEnabledMechanic(configJson)) return null;
            throw new ServiceException("请先完成高级玩法");
        }
        session = expireIfNeeded(session,memberId);
        if (Integer.valueOf(GameRuntimeSession.STATUS_COMPLETED).equals(session.getStatus())) {
            return terminalOutcome(readJson(session.getConfigSnapshotJson(), "高级玩法配置不可用"),
                    objectState(session.getStateJson()));
        }
        if (!Integer.valueOf(GameRuntimeSession.STATUS_RUNNING).equals(session.getStatus())) {
            throw new ServiceException("高级玩法已结束，请重新进入节点");
        }
        JsonNode snapshot = readJson(session.getConfigSnapshotJson(), "高级玩法配置不可用");
        ObjectNode state = objectState(session.getStateJson());
        if (!mechanicsReady(snapshot, state)) throw new ServiceException("高级玩法尚未完成");

        int oldVersion = session.getVersion();
        Date now = clock.now();
        session.setStatus(GameRuntimeSession.STATUS_COMPLETED);
        session.setCompletedAt(now);
        session.setUpdateTime(now);
        if (sessionMapper.updateStateCas(session) != 1) {
            throw new ServiceException("高级玩法状态已更新，请重试");
        }
        session.setVersion(oldVersion + 1);
        return terminalOutcome(snapshot, state);
    }

    /**
     * 本局完成证据里不可信的那几条(空 = 都可信)。三类:
     * 设备自报成绩;拍照没拿到视觉模型当场真判 PASS(降级/次数用尽兜底放行);
     * 扫码玩法比对的是节点静态码 —— 只证明「码对得上」,不是有时效、一次消费的到场凭证。
     */
    static List<String> untrustedCompletionEvidence(JsonNode config, JsonNode state) {
        List<String> reasons = new ArrayList<>();
        if (config == null) return reasons;
        for (String section : SELF_REPORTED_SECTIONS) {
            if (config.path(section).path("enabled").asBoolean(false)) reasons.add(section + ":SELF_REPORTED");
        }
        if (config.path("photoCheck").path("enabled").asBoolean(false) && !photoVerified(state == null ? null : state.path("photoCheck"))) {
            reasons.add("photoCheck:FALLBACK");
        }
        if (config.path("scan").path("enabled").asBoolean(false)) reasons.add("scan:TASK_CODE_MATCH");
        return reasons;
    }

    /** 服务端 photoVerified:只有视觉模型当场真判 PASS 才算;降级与兜底放行都不算 */
    private static boolean photoVerified(JsonNode photoState) {
        return photoState != null && "PASS".equals(photoState.path("verdict").asText(""));
    }

    @Override
    public boolean rewardCappedToXpBadge(Long memberId, Long activityId, Long topicId, Long nodeId) {
        if (memberId == null || topicId == null || nodeId == null) return false;
        CmsTopicNode node = nodeMapper.selectCmsTopicNodeById(nodeId);
        if (node == null || !topicId.equals(node.getTopicId())) return false;
        GameRuntimeSession session = existingSession(memberId, activityId == null ? 0L : activityId, topicId, nodeId);
        if (session == null) return false;
        JsonNode config = readJson(session.getConfigSnapshotJson(), "高级玩法配置不可用");
        return !untrustedCompletionEvidence(config, objectState(session.getStateJson())).isEmpty();
    }

    /**
     * 本次完成的结果码。它有两个下游:路线推进(下一站去哪)与按档发奖。
     *
     * <p>分支剧情优先 —— 它是商家显式写下的结局,语义最强。没开分支时,
     * 由本次玩法自己的档位当结果码,好让「命中才发券」「三对全中才发券」
     * 这类奖励规则有东西可挂。都没有就返回 null,走无条件奖励。
     */
    private String terminalOutcome(JsonNode config, ObjectNode state) {
        if(config.has("outcomeRules"))return state.path("gameplayDecision").path("outcome").asText();
        JsonNode branch = config.path("branch");
        if (enabled(branch)) {
            String current = state.path("branch").path("currentStepId").asText("");
            for (JsonNode step : branch.path("steps")) {
                if (current.equals(step.path("id").asText()) && step.path("terminal").asBoolean(false)) {
                    String code = AdvancedGameConfigValidator.resolveBranchOutcomeCode(branch, step);
                    if (!code.isEmpty()) return code;
                }
            }
            throw new ServiceException("高级玩法终点缺少稳定 outcomeCode");
        }
        if (enabled(config.path("diceRoll")) && "d20".equals(config.path("diceRoll").path("mode").asText())
                && state.path("diceRoll").path("rolled").asBoolean(false)) {
            return state.path("diceRoll").path("success").asBoolean(false) ? "D20_SUCCESS" : "D20_FAILURE";
        }
        if (enabled(config.path("estimate"))) {
            String tier = state.path("estimate").path("tier").asText("");
            // 与 submitEstimate 的三档同名:HIT / CLOSE / MISS
            if (!tier.isEmpty()) return "ESTIMATE_" + tier;
        }
        if (enabled(config.path("hiddenObject"))) {
            int need = config.path("hiddenObject").path("hotspots").size();
            if (state.path("hiddenObject").path("foundIds").size() >= need) return "HIDDEN_ALL_FOUND";
        }
        if (enabled(config.path("pricePair"))) {
            JsonNode kit = state.path("pricePair");
            // 猜图只有两种结果:挑对了、次数用完还没挑对
            if (kit.path("finished").asBoolean(false)) {
                return kit.path("passed").asBoolean(false) ? "GUESS_HIT" : "GUESS_MISS";
            }
        }
        return null;
    }

    /**
     * 按本局状态放宽本节点玩法的**容错面**(阶段 4)。
     *
     * <p>四条硬约束里运行期负责两条:开局求值一次(见调用点注释)、求值在下发白名单之前。
     * 另两条(单向只放宽、只动容错面)由发布期 {@code validateVariants} 挡住 —— 所以这里
     * 不重复判方向,只照着已经过闸的配置执行。
     *
     * <p>**首条命中为准,不叠加**:多条都成立时只用第一条。叠加会让「放宽了多少」变得
     * 不可预测,创作者也说不清自己配出了什么。
     *
     * <p>**对玩家隐藏,对后台留痕**:放宽结果直接落进配置,回包不带「你被放水了」;
     * 命中了哪一条写进日志,供排查。隐藏是体验要求,留痕是「回执 ≠ 观测」的要求。
     *
     * <p>没有旅程本局时 {@code conditionPasses} 一律 false ⇒ 原样返回,非旅程主题零变化。
     */
    private String applyRelax(String configJson, Long memberId, Long topicId, Long activityId) {
        if (topicRouteRuntimeService == null) return configJson;
        JsonNode root = readJson(configJson, "高级玩法配置不可用");
        JsonNode variants = root.get("variants");
        if (variants == null || !variants.isArray() || variants.size() == 0) return configJson;
        for (JsonNode variant : variants) {
            if (!topicRouteRuntimeService.conditionPasses(memberId, topicId, activityId, variant.get("when"))) continue;
            ObjectNode relaxed = (ObjectNode) root.deepCopy();
            JsonNode relax = variant.get("relax");
            Iterator<String> paths = relax.fieldNames();
            while (paths.hasNext()) {
                String path = paths.next();
                applyOneRelax(relaxed, path, relax.get(path).asText(""));
            }
            logger.info("阶段4 自适应难度命中: member={} topic={} when={} relax={}",
                    memberId, topicId, variant.get("when"), relax);
            return writeJson(relaxed);
        }
        return configJson;
    }

    /** {@code +N} 加并封顶、{@code =0} 改成不限。形状已由发布期闸保证,这里不再判方向。 */
    private void applyOneRelax(ObjectNode root, String path, String raw) {
        int dot = path.indexOf('.');
        if (dot <= 0) return;
        JsonNode section = root.get(path.substring(0, dot));
        if (section == null || !section.isObject()) return;       // 这一段没配 = 没什么可放宽
        // 不夹值:发布期 requireRelaxedStillValid 已经保证「放宽后仍是一份合法配置」。
        // 在这里 Math.min 夹一下看着更安全,实则是把越界悄悄抹平 —— 作者写的数字没生效、
        // 而两头都不报错。越界该在发布时就被拒。算法与发布期复核共用一处。
        AdvancedGameConfigValidator.applyRelaxValue((ObjectNode) section, path.substring(dot + 1), raw);
    }

    private GameInput requireInput(Long memberId, Long activityId, Long topicId, Long nodeId) {
        return requireInput(memberId,activityId,topicId,nodeId,true);
    }
    private GameInput requireInput(Long memberId, Long activityId, Long topicId, Long nodeId,boolean newUse) {
        if (memberId == null || memberId <= 0 || topicId == null || topicId <= 0
                || nodeId == null || nodeId <= 0) throw new IllegalArgumentException("参数有误");
        CmsTopicNode node = nodeMapper.selectCmsTopicNodeById(nodeId);
        if (node == null || !topicId.equals(node.getTopicId())) throw new IllegalArgumentException("节点不属于该主题");
        long actId = activityId == null ? 0L : activityId;
        if (actId > 0) {
            if(teamMapper.hasPaidRegistration(memberId,2,actId)<=0) throw new IllegalStateException("请先报名并完成支付");
            if(newUse) com.chengyinhub.business.service.support.CurrentPlayRegistration.require(
                    activityMapper,registrationMapper,memberId,2L,actId,null,clock.now(),true);
            requireActivityWindow(actId, topicId);
        } else {
            // 本路线已开局：通行证开局时有效即可打完，站内玩法同样不因中途到期被拦。
            boolean started = routeStarted(memberId, 0L, topicId);
            if(newUse && started) com.chengyinhub.business.service.support.CurrentPlayRegistration.requireToContinue(
                    activityMapper,registrationMapper,memberId,1L,topicId,null,clock.now());
            else if(newUse) com.chengyinhub.business.service.support.CurrentPlayRegistration.require(
                    activityMapper,registrationMapper,memberId,1L,topicId,null,clock.now());
            else if(!started && !hasValidSelfPlayPass(memberId,topicId)) throw new IllegalStateException("请先购买自玩通行证");
        }
        GameRuntimeSession existing = existingSession(memberId, actId, topicId, nodeId);
        // 已结算 R5 只恢复权威视图；当前任务闸仅约束启动和重试。
        if (!settledReadOnly(existing) && topicRouteRuntimeService != null) topicRouteRuntimeService.requireOpeningAccess(memberId, topicId, actId, nodeId);
        if (existing != null) {
            return new GameInput(actId, existing.getTemplateId(), existing.getConfigSnapshotJson(), existing);
        }
        if (node.getTemplateId() == null) throw new IllegalStateException("该节点没有玩法模板");
        CmsMemberTemplate template = templateMapper.selectCmsMemberTemplateById(node.getTemplateId());
        if (template == null) throw new IllegalStateException("玩法模板不存在");
        JsonNode frozenConfig=topicRouteRuntimeService==null?null:topicRouteRuntimeService.nodeRuntimeConfig(memberId,topicId,nodeId,activityId);
        JsonNode publishedR5=topicRouteRuntimeService==null?null:topicRouteRuntimeService.publishedR5NodeConfig(memberId,topicId,nodeId,activityId);
        String configJson = publishedR5!=null?publishedR5.toString():configValidator.validateAndNormalize(frozenConfig==null?template.getAdvancedConfigJson():frozenConfig.toString());
        if (publishedR5==null ? !configValidator.hasEnabledMechanic(configJson) : !com.chengyinhub.business.service.support.GameplaySupportMatrix.r5CompletionProof(publishedR5)) throw new IllegalStateException("该模板未配置高级玩法");
        // 阶段 4 自适应难度:**开局求值一次**,放宽后的配置写进本局快照。
        // 只在新建局时求值(已有局走上面的快照分支)—— 局内不可变,重放 / CAS / 幂等一行都不用改;
        // 在 AdvancedGamePublicProjection 之前,下发白名单仍是唯一出口,放宽绕不过它。
        configJson = applyRelax(configJson, memberId, topicId, actId);
        return new GameInput(actId, template.getId(), configJson, null);
    }

    /** 选中选项的“选中后的反馈”图片(每个选项最多 3 张,多选按选择顺序),写进本局状态供重入回读。 */
    private static void putFeedbackImages(ObjectNode node, List<JsonNode> picked) {
        ArrayNode images = node.putArray("lastFeedbackImages");
        for (JsonNode option : picked) {
            int count = 0;
            for (JsonNode image : option.path("fbImgs")) {
                if (count++ >= 3) break;
                if (image.isTextual() && !image.asText().trim().isEmpty()) images.add(image.asText().trim());
            }
        }
    }

    /** 已完成且通过的选项问答:重新进入只恢复原状态(只读),不重开、不扣费、不重复效果;旧配置答错重试的行为不变。 */
    private boolean settledReadOnly(GameRuntimeSession session) {
        if (session != null && objectState(session.getStateJson()).has("zeroChallengeInstance")) return false;
        if (settledR5(session)) return true;
        if (session == null || session.getStatus() == null || Integer.valueOf(GameRuntimeSession.STATUS_RUNNING).equals(session.getStatus())) return false;
        JsonNode config = readJson(session.getConfigSnapshotJson(), "玩法配置不可用");
        if (!enabled(config.path("qa"))) return false;
        JsonNode qa = objectState(session.getStateJson()).path("qa");
        return qa.path("finished").asBoolean(false) && qa.path("passed").asBoolean(false);
    }

    private boolean settledR5(GameRuntimeSession session) {
        return session != null && session.getStatus() != null
                && !Integer.valueOf(GameRuntimeSession.STATUS_RUNNING).equals(session.getStatus())
                && readJson(session.getConfigSnapshotJson(), "玩法配置不可用").path("outcomeRules").path("version").asInt() == 1;
    }

    private GameRuntimeSession existingSession(Long memberId, Long activityId, Long topicId, Long nodeId) {
        JsonNode shared = gameSessionRuntimeService == null ? null : gameSessionRuntimeService.sharedRouteSnapshot(memberId, topicId, activityId, nodeId);
        if (shared != null) {
            GameRuntimeSession current = sessionMapper.selectOwnerByInstance(activityId, topicId, nodeId, GameRuntimeSession.OWNER_TEAM,
                    shared.path("teamId").asLong(), sharedInstance(shared));
            if (current != null || !shared.has("challenge")) return current;
            return sessionMapper.selectLatestZeroChallenge(activityId, topicId, nodeId, GameRuntimeSession.OWNER_TEAM,
                    shared.path("teamId").asLong(), shared.path("gameSessionId").asLong(), null, shared.path("authorityEpoch").asInt());
        }
        if (topicRouteRuntimeService != null && topicRouteRuntimeService.genericPlayer(memberId, topicId, activityId)) {
            Long routeId = topicRouteRuntimeService.gameplaySessionId(memberId, topicId, activityId);
            JsonNode challenge = topicRouteRuntimeService.zeroChallenge(memberId, topicId, activityId, nodeId);
            if (challenge != null) {
                GameRuntimeSession current = sessionMapper.selectOwnerByInstance(activityId, topicId, nodeId, GameRuntimeSession.OWNER_PLAYER,
                        memberId, topicRouteRuntimeService.gameplayInstance(memberId, topicId, activityId, nodeId));
                if (current != null) return current;
                return sessionMapper.selectLatestZeroChallenge(activityId, topicId, nodeId, GameRuntimeSession.OWNER_PLAYER,
                        memberId, null, routeId, null);
            }
            List<GameRuntimeSession> sessions = sessionMapper.selectPlayerSessionsByTopic(activityId, topicId, memberId);
            for (int i = sessions.size() - 1; i >= 0; i--) {
                GameRuntimeSession candidate = sessions.get(i);
                if (!nodeId.equals(candidate.getNodeId()) || !settledR5(candidate)) continue;
                ObjectNode state = objectState(candidate.getStateJson());
                if (!Objects.equals(routeId, longValue(state.get("originalRouteSessionId")))) continue;
                JsonNode feedback = outcomeFeedback(memberId, candidate, state, null);
                if (feedback != null && (!feedback.path("ended").asBoolean() || !feedback.path("writable").asBoolean())) return candidate;
            }
            return sessionMapper.selectOwnerByInstance(activityId, topicId, nodeId, GameRuntimeSession.OWNER_PLAYER, memberId,
                    topicRouteRuntimeService.gameplayInstance(memberId, topicId, activityId, nodeId));
        }
        // 恢复与完成必须选同一局。历史个人/团局并存时延续个人局，否则延续可访问的最新团局。
        // start 的 teamId 只用于首次建局，已有局的归属不会因新传参数或模板改版而切换。
        GameRuntimeSession session = sessionMapper.selectPlayerByOwner(activityId, topicId, nodeId, memberId);
        if (session == null && activityId > 0) {
            session = sessionMapper.selectTeamByMember(activityId, topicId, nodeId, memberId);
        }
        return session;
    }

    private boolean routeStarted(Long memberId, long actId, Long topicId) {
        return topicRouteRuntimeService != null && topicRouteRuntimeService.hasRunningSession(memberId, topicId, actId);
    }

    private boolean hasValidSelfPlayPass(Long memberId, Long topicId) {
        CmsRegistration probe = new CmsRegistration();
        probe.setMemberId(memberId);
        probe.setOwnerType(1L);
        probe.setOwnerId(topicId);
        probe.setRegistrationStatus(2);
        List<CmsRegistration> rows = registrationMapper.selectCmsRegistrationList(probe);
        if (rows == null) return false;
        Date now = clock.now();
        for (CmsRegistration row : rows) {
            if (row.getExpiresAt() == null || row.getExpiresAt().after(now)) return true;
        }
        return false;
    }

    private PlayTeam requireTeam(Long teamId, Long memberId, Long activityId) {
        return requireTeam(teamId, memberId, activityId, false);
    }

    private PlayTeam requireTeam(Long teamId, Long memberId, Long activityId, boolean lock) {
        PlayTeam team = lock ? teamMapper.selectPlayTeamByIdForUpdate(teamId) : teamMapper.selectPlayTeamById(teamId);
        if (team == null || !Integer.valueOf(2).equals(team.getOwnerType())
                || !activityId.equals(team.getOwnerId()) || team.getStatus() == null || team.getStatus() > 2) {
            throw new IllegalStateException("队伍不可用于本场玩法");
        }
        if (teamMapper.selectActiveMember(teamId, memberId) == null) throw new IllegalStateException("你不是该队伍成员");
        return team;
    }

    private Long inferTeamId(Long memberId, Long activityId) {
        if (activityId == null || activityId <= 0) throw new IllegalArgumentException("队伍玩法仅支持活动场次");
        List<PlayTeam> teams = teamMapper.selectMyTeams(memberId);
        Long matched = null;
        if (teams != null) {
            for (PlayTeam team : teams) {
                if (Integer.valueOf(2).equals(team.getOwnerType()) && activityId.equals(team.getOwnerId())
                        && team.getStatus() != null && team.getStatus() <= PlayTeam.STATUS_STARTED) {
                    if (matched != null && !matched.equals(team.getId())) {
                        throw new IllegalStateException("当前场次存在多个队伍，请先在报名页选择队伍");
                    }
                    matched = team.getId();
                }
            }
        }
        if (matched == null) throw new IllegalStateException("请先加入本场队伍");
        return matched;
    }

    private void validateTeamSize(JsonNode multiplayer, List<PlayTeamMember> members) {
        if (!enabled(multiplayer)) return;
        int size = members == null ? 0 : members.size();
        if (size < multiplayer.path("minPlayers").asInt() || size > multiplayer.path("maxPlayers").asInt()) {
            throw new IllegalStateException("队伍人数不符合玩法配置");
        }
    }

    private ObjectNode initialState(JsonNode config, PlayTeam team, List<PlayTeamMember> members) {
        ObjectNode state = objectMapper.createObjectNode();
        if (enabled(config.get("random"))) state.putArray("draws");
        if (enabled(config.get("branch")) && !config.has("outcomeRules")) {
            ObjectNode branch = state.putObject("branch");
            String start = config.path("branch").path("startStepId").asText();
            branch.put("currentStepId", start);
            branch.putArray("visitedStepIds").add(start);
        }
        if (enabled(config.get("multiplayer"))) {
            ObjectNode multi = state.putObject("multiplayer");
            multi.putObject("roles");
            multi.putArray("completedUnitIds");
            ArrayNode memberRows = multi.putArray("members");
            if (members != null) {
                for (PlayTeamMember member : members) {
                    ObjectNode row = memberRows.addObject();
                    row.put("memberId", member.getMemberId());
                    row.put("name", StringUtils.defaultIfBlank(member.getMemberName(), "队员"));
                    row.put("avatar", StringUtils.defaultString(member.getMemberAvatar()));
                }
            }
            multi.put("turnIndex", 0);
            if ("AUTO".equals(config.path("multiplayer").path("assignment").asText("AUTO"))) {
                assignAutomaticRoles(config.path("multiplayer"), multi, members);
            }
            if (team != null) multi.put("leaderMemberId", team.getLeaderMemberId());
        }
        if (enabled(config.get("pricePair"))) {
            state.set("pricePair", initialPricePair(config.path("pricePair")));
        }
        return state;
    }

    /** 猜图开局:一张也没猜。图的顺序按配置来,不打乱 ——
     *  打乱等于商家每次进来看到的题面都不一样,没法照着自己配的顺序核对。 */
    private ObjectNode initialPricePair(JsonNode cfg) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("attempts", 0);
        node.put("finished", false);
        return node;
    }

    private void assignAutomaticRoles(JsonNode config, ObjectNode multi, List<PlayTeamMember> members) {
        ArrayNode order = (ArrayNode) config.path("turnOrder");
        ObjectNode roles = (ObjectNode) multi.path("roles");
        for (int i = 0; members != null && i < members.size(); i++) {
            roles.put(String.valueOf(members.get(i).getMemberId()), order.get(i % order.size()).asText());
        }
    }

    private Date deadline(JsonNode config, Date now) {
        JsonNode timer = config.get("timer");
        if (!enabled(timer)) return null;
        return new Date(now.getTime() + timer.path("durationSeconds").asLong() * 1000L);
    }

    private void draw(JsonNode config, ObjectNode state) {
        JsonNode randomConfig = config.get("random");
        if (!enabled(randomConfig)) throw new IllegalArgumentException("该玩法未开启盲盒");
        ArrayNode draws = state.withArray("draws");
        int drawCount = randomConfig.path("drawCount").asInt();
        if (draws.size() >= drawCount) throw new IllegalStateException("盲盒已经抽完");
        List<JsonNode> remaining = new ArrayList<>();
        int totalWeight = 0;
        for (JsonNode item : randomConfig.path("items")) {
            if (!containsId(draws, item.path("id").asText())) {
                remaining.add(item);
                totalWeight += item.path("weight").asInt();
            }
        }
        int hit = random.nextInt(totalWeight);
        JsonNode selected = remaining.get(remaining.size() - 1);
        int cursor = 0;
        for (JsonNode item : remaining) {
            cursor += item.path("weight").asInt();
            if (hit < cursor) { selected = item; break; }
        }
        draws.add(selected.deepCopy());
    }

    private ChoiceRef choose(JsonNode config, ObjectNode state, Map<String, Object> payload,
            GameRuntimeSession session) {
        JsonNode branchConfig = config.get("branch");
        if (!enabled(branchConfig)) throw new IllegalArgumentException("该玩法未开启分支剧情");
        ObjectNode branch = state.with("branch");
        String current = branch.path("currentStepId").asText();
        String optionId = String.valueOf(payload.getOrDefault("optionId", ""));
        JsonNode step = findById(branchConfig.path("steps"), current);
        JsonNode option = step == null ? null : findById(step.path("options"), optionId);
        if (option == null) throw new IllegalArgumentException("当前步骤没有这个选项");
        String next = option.path("nextStepId").asText();
        branch.put("currentStepId", next);
        branch.withArray("visitedStepIds").add(next);
        session.setScore(value(session.getScore()) + option.path("score").asInt(0));
        // R14:分支结果由步骤 + 选项定位,序号由旅程运行时按选项位置算(1 起)。
        return new ChoiceRef(current, optionId);
    }

    private void assignRole(JsonNode config, ObjectNode state, Map<String, Object> payload,
            GameRuntimeSession session, Long actorId) {
        JsonNode multiConfig = config.get("multiplayer");
        if (!enabled(multiConfig) || session.getOwnerType() != GameRuntimeSession.OWNER_TEAM) {
            throw new IllegalArgumentException("该玩法未开启多人角色");
        }
        if (!"LEADER".equals(multiConfig.path("assignment").asText())) {
            throw new IllegalStateException("该玩法由系统自动分配角色");
        }
        PlayTeam team = teamMapper.selectPlayTeamById(session.getOwnerId());
        if (team == null || !actorId.equals(team.getLeaderMemberId())) throw new IllegalStateException("只有队长能分配角色");
        long targetId = longValue(payload.get("memberId"));
        String roleId = String.valueOf(payload.getOrDefault("roleId", ""));
        if (teamMapper.selectActiveMember(session.getOwnerId(), targetId) == null) throw new IllegalStateException("目标玩家不在队伍中");
        JsonNode role = findById(multiConfig.path("roles"), roleId);
        if (role == null) throw new IllegalArgumentException("角色不存在");
        ObjectNode roles = state.with("multiplayer").with("roles");
        int used = 0;
        java.util.Iterator<JsonNode> values = roles.elements();
        while (values.hasNext()) if (roleId.equals(values.next().asText())) used++;
        String previous = roles.path(String.valueOf(targetId)).asText("");
        if (!roleId.equals(previous) && used >= role.path("max").asInt()) throw new IllegalStateException("该角色名额已满");
        roles.put(String.valueOf(targetId), roleId);
    }

    private void completeUnit(JsonNode config, ObjectNode state, Map<String, Object> payload,
            GameRuntimeSession session, Long actorId) {
        JsonNode multiConfig = config.get("multiplayer");
        if (!enabled(multiConfig) || session.getOwnerType() != GameRuntimeSession.OWNER_TEAM) {
            throw new IllegalArgumentException("该玩法未开启多人轮次");
        }
        ObjectNode multi = state.with("multiplayer");
        String role = multi.path("roles").path(String.valueOf(actorId)).asText("");
        if (role.isEmpty()) throw new IllegalStateException("你还没有游戏角色");
        ArrayNode turnOrder = (ArrayNode) multiConfig.path("turnOrder");
        int turnIndex = multi.path("turnIndex").asInt(0);
        String expectedRole = turnOrder.get(turnIndex % turnOrder.size()).asText();
        if (!expectedRole.equals(role)) throw new IllegalStateException("还没轮到你的角色");
        String unitId = String.valueOf(payload.getOrDefault("unitId", "")).trim();
        if (unitId.isEmpty() || unitId.length() > 64) throw new IllegalArgumentException("完成单元 ID 不正确");
        ArrayNode units = multi.withArray("completedUnitIds");
        if (containsText(units, unitId)) throw new IllegalStateException("这个单元已经完成");
        units.add(unitId);
        multi.put("turnIndex", turnIndex + 1);
        session.setCompletedUnits(units.size());
        session.setScore(value(session.getScore()) + multiConfig.path("unitScore").asInt(0));
    }

    /** 引导/终局题组复用真实判题，只返回事实，不写余额、奖励或尝试记录。 */
    public static boolean verifyObjectiveAnswers(JsonNode config, JsonNode answers) {
        if (config == null || !config.isObject() || answers == null || !answers.isObject()) throw new IllegalArgumentException("请提交完整题组答案");
        AdvancedGameRuntimeServiceImpl judge = new AdvancedGameRuntimeServiceImpl();
        ObjectNode state = judge.objectMapper.createObjectNode();
        GameRuntimeSession session = new GameRuntimeSession(); session.setScore(0);
        boolean found = false;
        Iterator<Map.Entry<String, JsonNode>> fields = config.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!judge.enabled(field.getValue())) continue;
            String kind = field.getKey();
            if (Arrays.asList("timer", "leaderboard", "multiplayer", "timeWindow", "blindTaste", "silentOrder", "diyName", "musicCorner", "dailySign", "slowTask", "random", "branch").contains(kind)) continue;
            if (!Arrays.asList("qa", "sort", "match", "classify").contains(kind)) throw new IllegalArgumentException("引导只支持客观题组");
            JsonNode answer = answers.get(kind);
            if (answer == null || !answer.isObject() || answer.has("passed")) throw new IllegalArgumentException("请提交完整题组答案，不接受客户端通过标记");
            Map<String, Object> payload = judge.objectMapper.convertValue(answer, new TypeReference<Map<String,Object>>(){});
            if ("qa".equals(kind)) {
                if (!Arrays.asList("TYPE", "PICK").contains(field.getValue().path("mode").asText())) throw new IllegalArgumentException("引导问答须有服务端答案");
                judge.submitQa(config, state, payload, session);
            } else if ("sort".equals(kind)) judge.submitSort(config, state, payload, session);
            else if ("match".equals(kind)) judge.submitMatch(config, state, payload, session);
            else judge.submitClassify(config, state, payload, session);
            found = true;
        }
        if (answers.has("passed")) throw new IllegalArgumentException("不接受客户端通过标记");
        if (!found) throw new IllegalArgumentException("节点没有客观题组");
        for (String kind : Arrays.asList("qa", "sort", "match", "classify")) if (judge.enabled(config.get(kind)) && !state.path(kind).path("passed").asBoolean()) return false;
        return true;
    }

    public static List<String[]> verifiedObjectiveChoices(JsonNode config,JsonNode answers) {
        if(!verifyObjectiveAnswers(config,answers))throw new IllegalArgumentException("题组尚未通过可信判定");
        AdvancedGameRuntimeServiceImpl judge=new AdvancedGameRuntimeServiceImpl();List<String[]> choices=new ArrayList<>();
        for(String kind:Arrays.asList("qa","sort","match","classify"))if(judge.enabled(config.get(kind))) {
            if("qa".equals(kind)) {ObjectNode state=judge.objectMapper.createObjectNode();GameRuntimeSession session=new GameRuntimeSession();session.setScore(0);
                for(ChoiceRef ref:judge.submitQa(config,state,judge.objectMapper.convertValue(answers.path("qa"),new TypeReference<Map<String,Object>>(){}),session))if(ref!=null)choices.add(new String[]{ref.stepOrKit,ref.optionId});
            } else choices.add(new String[]{kind,null});
        }
        return choices;
    }

    private String challengeGame(String action) {
        switch (action) {
            case "SUBMIT_BALL_SHAKE": return "ballShake";
            case "SUBMIT_REACTION": return "reaction";
            case "SUBMIT_QUIET_HOLD": return "quietHold";
            case "SUBMIT_SHOUT": return "shout";
            case "SUBMIT_COUNTDOWN": return "countdown";
            case "SUBMIT_STOPWATCH": return "stopwatch";
            default: return null;
        }
    }

    private boolean challengeObjectivesPassed(JsonNode config, ObjectNode state,String selected) {
        boolean found = false;
        for (String kind : Arrays.asList("qa", "sort", "match", "classify")) if (enabled(config.get(kind)) && (selected.isEmpty() || selected.equals(kind))) {
            found = true; if (!state.path(kind).path("passed").asBoolean()) return false;
        }
        return found;
    }

    private String sharedInstance(JsonNode snapshot) { return snapshot.path("runtimeInstance").asText("game:"+snapshot.path("gameSessionId").asLong()); }

    private Integer runtimeEpoch(GameRuntimeSession session) {
        JsonNode epoch = objectState(session.getStateJson()).get("runtimeAuthorityEpoch");
        return epoch == null ? null : epoch.asInt();
    }

    private boolean sharedMechanicsReady(JsonNode config, ObjectNode state) {
        boolean objective=false;
        for (String kind : Arrays.asList("qa", "sort", "match", "classify")) if (enabled(config.get(kind))) {
            objective=true;
            if (!state.path(kind).path("passed").asBoolean()) return false;
        }
        if (objective) return true;
        if (enabled(config.get("scan"))) return state.path("scan").path("scanned").asBoolean();
        return mechanicsReady(config, state);
    }

    private boolean mechanicsReady(JsonNode config, ObjectNode state) {
        if (enabled(config.get("random"))
                && state.path("draws").size() < config.path("random").path("drawCount").asInt()) return false;
        if (enabled(config.get("branch"))) {
            JsonNode step = findById(config.path("branch").path("steps"),
                    state.path("branch").path("currentStepId").asText());
            if (step == null || !step.path("terminal").asBoolean(false)) return false;
        }
        if (enabled(config.get("multiplayer"))) {
            JsonNode multi = state.path("multiplayer");
            if (!rolesMeetMinimum(config.path("multiplayer"), multi.path("roles"))) return false;
            if (multi.path("roles").size() != multi.path("members").size()) return false;
            int requiredTurns = config.path("multiplayer").path("requiredTurns").asInt(1);
            if (multi.path("completedUnitIds").size() < requiredTurns) return false;
        }
        // ===== Figma v5.1 新玩法 =====
        // 少查一个 = 纯该玩法的模板开局即可通关(打卡前置的 completeGate 恒放行)。
        // musicCorner / silentOrder 按 #857 拍板不判定通关;timeWindow 的闸在 start() 已把守。
        if (enabled(config.get("blindTaste"))
                && !state.path("blindTaste").path("solved").asBoolean(false)) return false;
        if (enabled(config.get("diyName"))
                && state.path("diyName").path("name").asText("").isEmpty()) return false;
        if (enabled(config.get("steps"))
                && !state.path("steps").path("reached").asBoolean(false)) return false;
        if (enabled(config.get("dailySign"))
                && state.path("dailySign").path("claimedDate").asText("").isEmpty()) return false;
        if (enabled(config.get("slowTask"))
                && !state.path("slowTask").path("claimed").asBoolean(false)) return false;
        // 估数看的是"答过了"而不是"答对了":猜不中就卡住,玩家会退出而不是重猜(施工文档 §1.3)
        if (enabled(config.get("estimate"))
                && !state.path("estimate").path("submitted").asBoolean(false)) return false;
        // 比价同理:看的是"这一局走完了",答错收场也算走完
        if (enabled(config.get("pricePair"))
                && !state.path("pricePair").path("finished").asBoolean(false)) return false;
        // 竞猜:押了就算走完这一站。答案要等商家次日才给,不这样玩家会卡在一个
        // 当天根本无法判定的节点上,整条路线断在这里(施工文档 §1.3)。
        if (enabled(config.get("predict"))
                && state.path("predict").path("optionKey").asText("").isEmpty()) return false;
        // 找东西反过来:它有确定答案,必须全找齐才算过,可以无限点
        if (enabled(config.get("hiddenObject"))) {
            int need = config.path("hiddenObject").path("hotspots").size();
            if (state.path("hiddenObject").path("foundIds").size() < need) return false;
        }
        // 问答:答完就算走完(答错次数用完也算),与猜数字同一条口径
        if (enabled(config.get("qa"))
                && !state.path("qa").path("finished").asBoolean(false)) return false;
        // 扫码:扫了才算。这一条就是「进店」本身,没有别的判据
        if (enabled(config.get("scan"))
                && !state.path("scan").path("scanned").asBoolean(false)) return false;
        // R3 三个新玩法。排序有次数上限,排完(或次数用尽)就算走完,与猜图同口径;
        // 连线与分类有确定答案且可无限重试,必须全对才算过,与找东西同口径 ——
        // 没全对就放行等于「随便连一下就能打卡」,玩法的判定当场作废。
        if (enabled(config.get("sort"))
                && !state.path("sort").path("finished").asBoolean(false)) return false;
        if (enabled(config.get("match"))
                && !state.path("match").path("passed").asBoolean(false)) return false;
        if (enabled(config.get("classify"))
                && !state.path("classify").path("passed").asBoolean(false)) return false;
        // R16 对照题与排序同口径:有次数上限时交完(对或错)就算走完;
        // 没配上限时就得全标对才算过 —— 不这样,「随便标一下」的玩家会被永久卡在打卡闸前。
        if (enabled(config.get("compare"))
                && !state.path("compare").path("finished").asBoolean(false)) return false;
        /* 预制人生四个新玩法。建档 / 留言是「交了就完」;拍照审核看的是 passed
           (兜底 pass 时也在提交里落成 passed=true,所以这一条就是唯一判据);
           打字与挑战类同口径,看 submitted 而不是 passed(没过也放行,理由同上)。 */
        if (enabled(config.get("profile"))
                && !state.path("profile").path("done").asBoolean(false)) return false;
        if (enabled(config.get("photoCheck"))
                && !state.path("photoCheck").path("passed").asBoolean(false)) return false;
        if (enabled(config.get("note"))
                && !state.path("note").path("done").asBoolean(false)) return false;
        if (enabled(config.get("typeIn"))
                && !state.path("typeIn").path("submitted").asBoolean(false)) return false;
        /* 决定类两个:抛/掷过就算走完这一站 —— 它们没有对错,结果本身就是任务。 */
        if (enabled(config.get("coinFlip"))
                && !state.path("coinFlip").path("flipped").asBoolean(false)) return false;
        if (enabled(config.get("diceRoll"))
                && !state.path("diceRoll").path("rolled").asBoolean(false)) return false;
        /* 挑战类六个(2026-09-22 现场感契约加了喊一嗓子):看的是「这一局交了」而不是「过了」。没过还卡着不让走,
           玩家只会退出而不是重来 —— 与猜数字同一条口径(施工文档 §1.3)。 */
        for (String game : new String[] { "reaction", "ballShake", "quietHold", "shout", "countdown", "stopwatch" }) {
            if (enabled(config.get(game))
                    && !state.path(game).path("submitted").asBoolean(false)) return false;
        }
        // 罗盘:对准过一次就算走完 —— 与估数同口径,没有对错、可以一直转(现场感契约 §3.4)
        if (enabled(config.get("compass"))
                && !state.path("compass").path("done").asBoolean(false)) return false;
        return true;
    }

    private boolean rolesMeetMinimum(JsonNode config, JsonNode assigned) {
        for (JsonNode role : config.path("roles")) {
            int count = 0;
            java.util.Iterator<JsonNode> values = assigned.elements();
            while (values.hasNext()) if (role.path("id").asText().equals(values.next().asText())) count++;
            if (count < role.path("min").asInt()) return false;
        }
        return true;
    }

    private GameRuntimeSession expireIfNeeded(GameRuntimeSession session,Long memberId) {
        if (objectState(session.getStateJson()).has("zeroChallengeInstance")) return session;
        if (!Integer.valueOf(GameRuntimeSession.STATUS_RUNNING).equals(session.getStatus())
                || session.getDeadlineAt() == null || !clock.now().after(session.getDeadlineAt())) return session;
        JsonNode config = readJson(session.getConfigSnapshotJson(), "高级玩法配置不可用");
        int oldVersion = session.getVersion();
        String timeout = config.path("timer").path("timeoutResult").asText("EXPIRED");
        session.setStatus("FAILED".equals(timeout)
                ? GameRuntimeSession.STATUS_FAILED : GameRuntimeSession.STATUS_EXPIRED);
        session.setUpdateTime(clock.now());
        boolean r5=config.path("outcomeRules").path("version").asInt()==1;ObjectNode decision=null;
        if(r5){
            boolean completed="COMPLETED".equals(timeout);
            decision=objectMapper.createObjectNode().put("nodeId",session.getNodeId()).put("outcome",completed?"PASS":"FAIL").put("reason","TIMEOUT");
            ObjectNode state=objectState(session.getStateJson());state.set("gameplayDecision",decision.deepCopy());session.setStateJson(writeJson(state));
            session.setStatus(completed?GameRuntimeSession.STATUS_COMPLETED:GameRuntimeSession.STATUS_FAILED);
        }
        if (sessionMapper.updateStateCas(session) == 1) {
            session.setVersion(oldVersion + 1);
            if(r5){String attempt="advanced:"+session.getId()+":"+objectState(session.getStateJson()).path("attemptOrdinal").asLong(1);
                ObjectNode original=objectState(session.getStateJson());
                if(original.path("predict").has("betId") && original.has("originalGameSessionId"))gameSessionRuntimeService.recordOriginalSharedGameplayOutcome(memberId,session.getActivityId(),original.path("originalGameSessionId").asLong(),session.getOwnerId(),runtimeEpoch(session),session.getNodeId(),session.getPlayInstanceId(),attempt,decision,Collections.emptyList());
                else if(original.path("predict").has("betId") && original.has("originalRouteSessionId"))topicRouteRuntimeService.recordOriginalGameplayOutcome(memberId,original.path("originalRouteSessionId").asLong(),session.getNodeId(),session.getPlayInstanceId(),attempt,decision,Collections.emptyList());
                else if(runtimeEpoch(session)!=null)gameSessionRuntimeService.recordSharedGameplayOutcome(memberId,session.getTopicId(),session.getActivityId(),session.getNodeId(),session.getPlayInstanceId(),attempt,decision,Collections.emptyList(),runtimeEpoch(session));else topicRouteRuntimeService.recordGameplayOutcome(memberId,session.getTopicId(),session.getActivityId(),session.getNodeId(),session.getPlayInstanceId(),attempt,decision,Collections.emptyList());}
        } else if(r5)throw new IllegalStateException("本次限时结果正在结算，请刷新");
        return session;
    }

    private boolean themeScoped(Long memberId, GameRuntimeSession session) {
        return topicRouteRuntimeService != null && session.getOwnerType() == GameRuntimeSession.OWNER_PLAYER
                && ((session.getPlayInstanceId() != null && !"default".equals(session.getPlayInstanceId()))
                || topicRouteRuntimeService.nodeRuntimeConfig(memberId, session.getTopicId(), session.getNodeId(), session.getActivityId()) != null);
    }

    private String themeActionKey(Long sessionId, String clientKey) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(clientKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder("theme:").append(sessionId).append(':');
            for (int i = 0; i < 16; i++) key.append(String.format("%02x", digest[i] & 0xff));
            return key.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private GameRuntimeSession requireAccessible(Long sessionId, Long memberId) {
        GameRuntimeSession session = sessionMapper.selectAccessibleById(sessionId, memberId);
        if (session == null) throw new IllegalStateException("玩法会话不存在或无权访问");
        return session;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> replay(PlayAttemptLog log, Long memberId, Long sessionId) {
        if (!memberId.equals(log.getMemberId()) || log.getAttemptType() == null
                || log.getAttemptType() != ATTEMPT_TYPE_ADVANCED) throw new IllegalStateException("幂等键已被占用");
        Map<String, Object> result = objectMapper.convertValue(readJson(log.getResultJson(), "幂等回执不可用"), Map.class);
        if (!sessionId.equals(longValue(result.get("sessionId")))
                && !sessionId.equals(longValue(result.get("retrySourceSessionId")))) throw new IllegalStateException("幂等键已被占用");
        return result;
    }

    /** R14:一次提交里判定的选择。branch 是「步骤 id + 选项 id」,其余是「玩法名 + 选项 id / null(通过)」。 */
    private static final class ChoiceRef {
        private final String stepOrKit;
        private final String optionId;

        private ChoiceRef(String stepOrKit, String optionId) {
            this.stepOrKit = stepOrKit;
            this.optionId = optionId;
        }
    }

    private static void addIfPresent(List<ChoiceRef> choices, ChoiceRef choice) {
        if (choice != null) choices.add(choice);
    }

    /**
     * 判定型玩法的成败(阶段 1 / 链路 D):返回 TRUE=判对、FALSE=判错、null=不属于判定型玩法
     * 或成败字段缺失。判据刻意不是「没判错=判对」—— 非判定动作(扫码 / 留言 / 建档)走同一个 act(),
     * 若按「非错即对」会凭空写 sys.passed=1。只有本清单内玩法且成败字段真有值时才写。
     *
     * <p>这也是判错扣血的汇合点(返回 FALSE 时 act() 调 recordMistake),字段缺失一律按「不写、不扣」处理,
     * 宁可不扣血也不误扣、更不给非判定动作凭空记成败。
     */
    private ObjectNode serverGameplayDecision(String action, JsonNode config, ObjectNode state) {
        Boolean passed = judgedOutcome(action, state);
        if (passed == null) passed = passedFact(action, state);
        String kit = null;
        if ("SUBMIT_QA".equals(action)) kit = "qa";
        else if ("SUBMIT_PRICE_PAIR".equals(action)) kit = "pricePair";
        else if ("SUBMIT_BLIND_TASTE".equals(action)) { kit = "blindTaste"; passed = state.path(kit).path("solved").asBoolean(); }
        else if ("FLIP_COIN".equals(action)) { kit = "coinFlip"; passed = state.path(kit).path("flipped").asBoolean() ? Boolean.TRUE : null; }
        else if ("ROLL_DICE".equals(action)) { kit = "diceRoll"; passed = !state.path(kit).path("rolled").asBoolean() ? null : "d20".equals(config.path(kit).path("mode").asText()) ? state.path(kit).path("success").asBoolean() : Boolean.TRUE; }
        else if ("DRAW".equals(action)) { kit = "random"; passed = state.path("draws").size() >= config.path(kit).path("drawCount").asInt() ? Boolean.TRUE : null; }
        if("SUBMIT_CHECK".equals(action)){kit="check";passed=state.path("check").path("success").asBoolean();}
        if ("SUBMIT_ALBUM".equals(action)) passed=state.path("album").path("done").asBoolean()?Boolean.TRUE:null;
        if ("SUBMIT_PHOTO_CHECK".equals(action))passed="PASS".equals(state.path("photoCheck").path("verdict").asText())?Boolean.TRUE:"REJECT".equals(state.path("photoCheck").path("verdict").asText())?Boolean.FALSE:null;
        if ("SUBMIT_PROFILE".equals(action)) passed=state.path("profile").path("done").asBoolean()?Boolean.TRUE:null;
        if ("SUBMIT_NOTE".equals(action)) passed=state.path("note").path("done").asBoolean()?Boolean.TRUE:null;
        if ("CLAIM_DAILY_SIGN".equals(action)) passed=state.path("dailySign").path("claimedDate").asText().isEmpty()?null:Boolean.TRUE;
        if ("SUBMIT_HIDDEN_OBJECT".equals(action)) {
            JsonNode hidden=state.path("hiddenObject");
            passed=hidden.path("foundIds").size()>=config.path("hiddenObject").path("hotspots").size()?Boolean.TRUE:hidden.path("exhausted").asBoolean()?Boolean.FALSE:null;
        }
        if (passed == null) return null;
        ObjectNode decision = objectMapper.createObjectNode().put("outcome", passed ? "PASS" : "FAIL");
        if (kit != null) {
            ObjectNode selection = decision.putObject("selection").put("kit", kit);
            if ("check".equals(kit))selection.put("nat",state.path("check").path("nat").asText());
            else if ("qa".equals(kit) && !config.path(kit).path("multi").asBoolean() && "PICK".equals(config.path(kit).path("mode").asText())) selection.put("optionId", state.path(kit).path("lastOptionId").asText());
            else if ("pricePair".equals(kit)) selection.put("optionId", state.path(kit).path("lastPickId").asText());
            else if ("blindTaste".equals(kit)) selection.put("optionId", state.path(kit).path("lastKey").asText());
            else if ("coinFlip".equals(kit)) selection.put("side", "HEADS".equals(state.path(kit).path("side").asText()) ? "heads" : "tails");
            else if ("diceRoll".equals(kit)) selection.put("face", state.path(kit).path("sum").asInt());
            else if ("random".equals(kit)) selection.put("optionId", state.path("draws").path(state.path("draws").size() - 1).path("id").asText());
        }
        return decision;
    }

    private Boolean judgedOutcome(String action, ObjectNode state) {
        switch (action) {
            case "SUBMIT_QA": return lastCorrect(state, "qa");
            case "SUBMIT_SORT": return lastCorrect(state, "sort");
            case "SUBMIT_MATCH": return lastCorrect(state, "match");
            case "SUBMIT_CLASSIFY": return lastCorrect(state, "classify");
            case "SUBMIT_COMPARE": return lastCorrect(state, "compare");
            case "SUBMIT_PRICE_PAIR": return lastCorrect(state, "pricePair");
            case "SUBMIT_HIDDEN_OBJECT":
                JsonNode hit = state.path("hiddenObject").path("lastHit");
                return hit.isBoolean() ? Boolean.valueOf(hit.asBoolean()) : null;
            default: return null;
        }
    }

    /**
     * 扣血清单之外、服务端同样有权威成败的玩法:只写 sys.passed 事实,**不扣血**(扣血口径见
     * judgedOutcome,9-21 拍板不动)。一律读「本局已定」的字段 —— 没到终局(还有次数 / 还在走)
     * 返回 null 不写,免得把「还没完」记成失败。
     */
    private static Boolean passedFact(String action, ObjectNode state) {
        switch (action) {
            case "SUBMIT_REACTION": return flag(state, "reaction", "passed");
            case "SUBMIT_BALL_SHAKE": return flag(state, "ballShake", "passed");
            case "SUBMIT_QUIET_HOLD": return flag(state, "quietHold", "passed");
            case "SUBMIT_SHOUT": return flag(state, "shout", "passed");
            case "SUBMIT_COUNTDOWN": return flag(state, "countdown", "passed");
            case "SUBMIT_STOPWATCH": return flag(state, "stopwatch", "passed");     // 次数没用完不写
            case "SUBMIT_TYPE_IN": return flag(state, "typeIn", "passed");          // 同上
            // 罗盘只有「对准了」这一个终局(没对准直接拒收),计步只有「达标」这一个终局。
            case "SUBMIT_COMPASS": return state.path("compass").path("done").asBoolean(false) ? Boolean.TRUE : null;
            case "SUBMIT_STEPS": return state.path("steps").path("reached").asBoolean(false) ? Boolean.TRUE : null;
            // 猜数字:猜中才算过;接近(CLOSE)有安慰分,但不是「猜中」。
            case "SUBMIT_ESTIMATE": {
                String tier = state.path("estimate").path("tier").asText("");
                return tier.isEmpty() ? null : Boolean.valueOf("HIT".equals(tier));
            }
            // 拍照审核:只认视觉模型当场真判的结论。通道降级不可信(9-21 口径);次数用完被「放过」算没过。
            case "SUBMIT_PHOTO_CHECK": {
                String verdict = state.path("photoCheck").path("verdict").asText("");
                if ("PASS".equals(verdict)) return Boolean.TRUE;
                if ("FALLBACK_PASS".equals(verdict)) return Boolean.FALSE;
                return null;
            }
            default: return null;
        }
    }

    private static Boolean flag(ObjectNode state, String kit, String field) {
        JsonNode value = state.path(kit).path(field);
        return value.isBoolean() ? Boolean.valueOf(value.asBoolean()) : null;
    }

    /** 随机类玩法本次动作的结果序号(1 起);不是随机动作或算不出来返回 0。 */
    private static int randomOutcome(String action, JsonNode config, ObjectNode state) {
        switch (action) {
            case "FLIP_COIN": {
                String side = state.path("coinFlip").path("side").asText("");
                return "HEADS".equals(side) ? 1 : "TAILS".equals(side) ? 2 : 0;
            }
            case "ROLL_DICE": return state.path("diceRoll").path("sum").asInt(0);
            case "DRAW": {
                JsonNode draws = state.path("draws");
                if (!draws.isArray() || draws.size() == 0) return 0;
                String id = draws.get(draws.size() - 1).path("id").asText("");
                int index = 0;
                for (JsonNode item : config.path("random").path("items")) {
                    index++;
                    if (id.equals(item.path("id").asText())) return index;
                }
                return 0;
            }
            default: return 0;
        }
    }

    private static Boolean lastCorrect(ObjectNode state, String kit) {
        JsonNode flag = state.path(kit).path("lastCorrect");
        return flag.isBoolean() ? Boolean.valueOf(flag.asBoolean()) : null;
    }

    private void logAction(GameRuntimeSession session, Long memberId, String idempotencyKey,
            Map<String, Object> view) {
        PlayAttemptLog log = new PlayAttemptLog();
        log.setMemberId(memberId);
        log.setTopicId(session.getTopicId());
        log.setNodeId(session.getNodeId());
        log.setActivityId(session.getActivityId());
        log.setAttemptType(ATTEMPT_TYPE_ADVANCED);
        log.setResult(1);
        log.setDurationS((int) Math.max(0, (clock.now().getTime() - session.getStartedAt().getTime()) / 1000L));
        log.setResultJson(writeJson(view));
        log.setIdempotencyKey(idempotencyKey);
        log.setCreatedAt(clock.now());
        try {
            int inserted = attemptMapper.insertPlayAttemptLog(log);
            if (view.containsKey("retrySourceSessionId") && inserted != 1) throw new IllegalStateException("恢复挑战重试回执未保存");
        } catch (DuplicateKeyException e) {
            PlayAttemptLog existing = attemptMapper.selectByIdempotencyKey(idempotencyKey);
            if (existing == null) throw e;
            replay(existing, memberId, session.getId());
        }
    }

    /**
     * 时段限定门禁(Figma v5.1 node 45:293)。
     * 跨夜(openFrom > openTo,如 23:00–01:00)是合法配置,午夜电台正是这种,
     * 所以判定要按"区间是否跨零点"分两支写,不能只用一个 from <= now <= to。
     */
    private void requireWithinTimeWindow(JsonNode timeWindow) {
        if (!enabled(timeWindow)) return;
        int from = minuteOfDay(timeWindow.path("openFrom").asText(""));
        int to = minuteOfDay(timeWindow.path("openTo").asText(""));
        if (from < 0 || to < 0 || from == to) {
            // fail-closed(#860 口径):种子 SQL 直写库可绕过 Java 校验器,「配置层已拦过」不成立。
            // 格式非法时放行 = 时段限定静默失效,商家配了等于没配。
            throw new IllegalStateException("时段限定配置无效，请商家修正开放时段后再试");
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(clock.now());
        int nowMinute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
        boolean open = from < to
                ? (nowMinute >= from && nowMinute < to)
                : (nowMinute >= from || nowMinute < to);   // 跨夜
        if (!open) {
            throw new IllegalStateException("这一站还没到开放时间(" 
                    + timeWindow.path("openFrom").asText("") + "–" + timeWindow.path("openTo").asText("") + ")");
        }
    }

    /** "HH:mm" → 当天第几分钟;格式不对返回 -1(由配置校验负责拦,这里只是不炸) */
    private int minuteOfDay(String clockText) {
        if (clockText == null || clockText.length() != 5 || clockText.charAt(2) != ':') return -1;
        try {
            int hour = Integer.parseInt(clockText.substring(0, 2));
            int minute = Integer.parseInt(clockText.substring(3));
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return -1;
            return hour * 60 + minute;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 记下「开启提醒」这一件事(时段限定)。小程序那头真的调成了
     * {@code wx.requestSubscribeMessage} 才发这个动作,服务端只记账、不代发订阅消息。
     *
     * <p>★ 幂等:订阅是个状态位,不是次数。玩家重进页面再点一次很正常,不该看见失败,
     * 也不该多出第二条痕迹 —— 与 CLAIM_DAILY_SIGN / START_SLOW_TASK 同一条口径。
     *
     * <p>★ 它**不判定通关**:时段限定的闸在 {@code start()} 已经把守,
     * mechanicsReady 从不读 timeWindow 这一段(见那里的注释)。点一下提醒就把这一站判成做完,
     * 是这条链路上最省事也最坏的刷分口子。
     *
     * <p>{@code tmplId} 只是备查(出问题时能对上"当时到底订的哪张模板"),
     * 判定与发送都不读它,所以客户端没给就什么也不写。
     */
    private void subscribeTimeWindow(JsonNode config, ObjectNode state, Map<String, Object> payload) {
        JsonNode cfg = config.get("timeWindow");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启时段限定");
        String tmplId = text(payload, "tmplId");
        if (tmplId.length() > MAX_SUBSCRIBE_TMPL_ID) {
            throw new IllegalArgumentException("订阅消息模板 id 不能超过 " + MAX_SUBSCRIBE_TMPL_ID + " 字");
        }
        ObjectNode node = state.with("timeWindow");
        node.put("subscribed", true);            // 幂等:再点一遍结果一样,不计数也不报错
        if (!tmplId.isEmpty()) node.put("tmplId", tmplId);
    }

    // ==================== Figma v5.1 新玩法动作 ====================

    /**
     * 盲品作答。2026-08-26 用户拍板:**可重答,答对才给分**。
     * 比对发生在服务端(answerKey 只在 config_snapshot 里,从不下发),所以重答不是漏洞。
     * 已答对后再提交直接拒绝 —— 否则同一题的 xp 能反复领。
     */
    private void submitBlindTaste(JsonNode config, ObjectNode state, Map<String, Object> payload,
            GameRuntimeSession session) {
        JsonNode cfg = config.get("blindTaste");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启盲品挑战");
        ObjectNode node = state.with("blindTaste");
        if (node.path("solved").asBoolean(false)) {
            throw new IllegalStateException("这一题已经答对了");
        }
        String key = text(payload, "key");
        if (findOptionByKey(cfg.path("options"), key) == null) {
            throw new IllegalArgumentException("没有这个选项");
        }
        node.put("attempts", node.path("attempts").asInt(0) + 1);
        node.put("lastKey", key);
        boolean correct = key.equals(cfg.path("answerKey").asText(""));
        node.put("solved", correct);
        if (correct) {
            session.setScore(value(session.getScore()) + cfg.path("xp").asInt(0));
        }
    }

    /** DIY 作品命名。玩家自由文本且会公开展示,先过微信内容安全再落 state。 */
    private void submitDiyName(JsonNode config, ObjectNode state, Map<String, Object> payload, Long memberId) {
        JsonNode cfg = config.get("diyName");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启作品命名");
        String name = text(payload, "name");
        int max = cfg.path("maxLength").asInt(16);
        if (name.isEmpty() || name.length() > max) {
            throw new IllegalArgumentException("作品名须为 1 至 " + max + " 字");
        }
        // msgSecCheck v2 的 openid 必填:传 null/空会被微信判 40003 并把所有玩家拦在门外(RUN-29)。
        // 玩家已鉴权,理论上会员行必在;拿不到 openid 时传空,由服务 fail-close 并给不误导的文案,
        // 返回非 null 即拦截文案,直接抛给玩家,不静默改写他起的名字
        com.chengyinhub.business.domain.UmsMember member =
                umsMemberMapper == null ? null : umsMemberMapper.selectUmsMemberById(memberId);
        String blocked = wxContentSecurityService.checkText(
                name, member == null ? null : member.getWxOpenId());
        if (StringUtils.isNotBlank(blocked)) {
            throw new IllegalArgumentException(blocked);
        }
        state.with("diyName").put("name", name);
    }

    /**
     * 计步提交。真实步数只能由服务端解密微信运动数据拿到 —— 客户端传上来的任何"步数"都不采信。
     * payload 要的是 { code, encryptedData, iv }:code 是**新鲜的** wx.login 结果,
     * 当场换 session_key 当场解密,不做存储(微信本来就要求先 login 再 getWeRunData)。
     */
    private void submitSteps(JsonNode config, ObjectNode state, Map<String, Object> payload,
            GameRuntimeSession session, Long memberId) {
        JsonNode cfg = config.get("steps");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启计步挑战");
        int todaySteps;long timestamp;
        if(config.path("outcomeRules").path("version").asInt()==1) {
            com.chengyinhub.business.service.support.WeRunDecryptor.VerifiedWeRunReading run=verifiedWeRunService.verify(memberId,objectMapper.valueToTree(payload),clock.now().getTime());
            todaySteps=run.getDays().get(java.time.Instant.ofEpochMilli(clock.now().getTime()).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate().toString());timestamp=run.getWatermarkTime()/1000;
        } else {
            IWeChatMiniSessionService.WeChatMiniSession wx=weChatMiniSessionService.exchange(text(payload,"code"));
            WeRunDecryptor.WeRunResult run=WeRunDecryptor.decrypt(wx.getSessionKey(),text(payload,"encryptedData"),text(payload,"iv"),weChatMiniSessionService.miniAppId());todaySteps=run.getTodaySteps();timestamp=run.getTimestamp();
            // 旧版计步：读数须是今天且不早于已同步记录，防旧密文重放刷分(严格版由 VerifiedWeRunService 水印校验)。
            Date now=clock.now();
            if(timestamp>now.getTime()/1000L || timestamp>Long.MAX_VALUE/1000L
                    || !dayKey(new Date(timestamp*1000L)).equals(dayKey(now))) {
                throw new IllegalArgumentException("微信运动数据不是今天的记录，请重新同步");
            }
            if(timestamp<state.path("steps").path("syncedAt").asLong(0L)) {
                throw new IllegalArgumentException("微信运动记录早于已同步记录，请重新同步");
            }
        }
        ObjectNode node=state.with("steps");node.put("todaySteps",todaySteps);node.put("syncedAt",timestamp);
        int goal = cfg.path("goal").asInt(0);
        // 达标只给一次分:步数会随着走路一直涨,不设这道闸就是每同步一次领一次
        if (todaySteps >= goal && !node.path("reached").asBoolean(false)) {
            node.put("reached", true);
            session.setScore(value(session.getScore()) + cfg.path("xp").asInt(0));
        }
    }

    /**
     * 领今日城市签。一天一条:同一天重复提交是幂等的(直接返回已领那条),不是报错 ——
     * 用户重进页面再点一次很正常,不该让他看见失败。
     * 签文由服务端按日期确定性地选,池子从不下发(下发等于把明天的签提前给了)。
     */
    private void claimDailySign(JsonNode config, ObjectNode state, GameRuntimeSession session) {
        JsonNode cfg = config.get("dailySign");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启今日城市签");
        JsonNode poems = cfg.path("poems");
        if (!poems.isArray() || poems.size() == 0) {
            throw new IllegalStateException("这一站还没有配签文");
        }
        String today = dayKey(clock.now());
        ObjectNode node = state.with("dailySign");
        if (today.equals(node.path("claimedDate").asText(""))) {
            return;   // 今天已领,幂等返回
        }
        // 按「日期 + 会话」确定性取模,不用随机:同一天同一局重算必须得到同一条,
        // 否则回读一次就换一条签,玩家会以为系统在骗他
        // floorMod 而不是 Math.abs(...)%:hashCode 恰为 Integer.MIN_VALUE 时 abs 仍是负数,负下标直接炸
        int index = Math.floorMod((today + ":" + session.getId()).hashCode(), poems.size());
        node.put("claimedDate", today);
        node.put("poemIndex", index);
        node.set("lines", poems.get(index).deepCopy());
    }

    /**
     * 开始一个跨日任务。低摩擦:不要求任何前置动作,点一下就开始 ——
     * 稿(Focus Friend)的原话是「开始 · 25 分钟轻承诺」,门槛越低越好。
     * 重复开始是幂等的:已经开始过就保持原来的日期,不能靠反复点重置等待期。
     */
    private void startSlowTask(JsonNode config, ObjectNode state) {
        JsonNode cfg = config.get("slowTask");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启跨日任务");
        ObjectNode node = state.with("slowTask");
        if (!node.path("startedDate").asText("").isEmpty()) return;   // 已开始,幂等
        node.put("startedDate", dayKey(clock.now()));
    }

    /**
     * 领取跨日任务的解锁内容。
     * ★ 判定用**服务端日期差**,不是「过了 24 小时」:稿说的是「隔天」,
     *   而玩家理解的隔天是「睡一觉起来」,不是「整整 24 小时之后」。
     *   晚上 23:00 开始、次日 8:00 来领,按小时算不够、按天算够 —— 后者才符合直觉。
     * ★ 也不用客户端时间:那样改手机日期就能立刻解锁。
     */
    private void claimSlowTask(JsonNode config, ObjectNode state, GameRuntimeSession session) {
        JsonNode cfg = config.get("slowTask");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启跨日任务");
        ObjectNode node = state.with("slowTask");
        String startedDate = node.path("startedDate").asText("");
        if (startedDate.isEmpty()) throw new IllegalStateException("这个任务还没开始");
        if (node.path("claimed").asBoolean(false)) throw new IllegalStateException("已经领过了");

        int waitDays = cfg.path("waitDays").asInt(1);
        long elapsed = daysBetween(startedDate, dayKey(clock.now()));
        if (elapsed < waitDays) {
            // 不报错成"失败",而是明确说还要等几天 —— 这是低压循环,催促和惩罚都不该出现
            throw new IllegalStateException("再等 " + (waitDays - elapsed) + " 天就可以来领了");
        }
        node.put("claimed", true);
        node.put("claimedDate", dayKey(clock.now()));
        session.setScore(value(session.getScore()) + cfg.path("xp").asInt(0));
    }

    /** 两个 yyyy-MM-dd 之间差几天;解析不了时返回 0(判成"还没到",不白送) */
    private long daysBetween(String from, String to) {
        try {
            long diff = ChronoUnit.DAYS.between(LocalDate.parse(from), LocalDate.parse(to));
            return Math.max(0, diff);
        } catch (Exception e) {
            return 0;
        }
    }

    private JsonNode findOptionByKey(JsonNode options, String key) {
        if (key == null || key.isEmpty() || options == null || !options.isArray()) return null;
        for (JsonNode option : options) {
            if (key.equals(option.path("key").asText(""))) return option;
        }
        return null;
    }

    private String text(Map<String, Object> payload, String field) {
        Object raw = payload.get(field);
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    /**
     * 估数作答。按偏差分三档:命中(≤容差)满分 · 接近(≤3 倍容差)半分 · 偏离 0 分。
     *
     * <p>三档都算通关,原因写在施工文档 §1.3:估数带运气,把人卡在猜不中的节点上
     * 他会退出而不是重试,整条路线断在这里。得分分档已经够提供动力。
     *
     * <p>**只能提交一次** —— 能重猜的估数题等于没有难度,试几次就滑到满分了。
     */
    private void submitEstimate(JsonNode config, ObjectNode state, Map<String, Object> payload,
            GameRuntimeSession session) {
        JsonNode cfg = config.get("estimate");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启估数题");
        ObjectNode node = state.with("estimate");
        if (node.path("submitted").asBoolean(false)) {
            throw new IllegalStateException("这一题已经答过了");
        }

        Object raw = payload.get("value");
        if (raw == null) throw new IllegalArgumentException("请先给出一个估数");
        double guess;
        try {
            guess = Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("估数必须是数字");
        }
        if(!Double.isFinite(guess))throw new IllegalArgumentException("估数必须是有限数字");
        double min = cfg.path("min").asDouble();
        double max = cfg.path("max").asDouble();
        if (guess < min || guess > max) {
            // 越界直接拒绝而不是夹取:夹取会把"手滑填了个天文数字"记成一次正经作答
            throw new IllegalArgumentException("估数必须落在 " + trimNumber(min) + " 到 " + trimNumber(max) + " 之间");
        }

        double answer = cfg.path("answer").asDouble();
        double tolerance = cfg.path("tolerance").asDouble();
        double deviation = Math.abs(guess - answer);
        int fullXp = cfg.path("xp").asInt(0);

        String tier;
        int awarded;
        if (deviation <= tolerance) {
            tier = "HIT";
            awarded = fullXp;
        } else if (deviation <= tolerance * 3) {
            tier = "CLOSE";
            awarded = fullXp / 2;
        } else {
            tier = "MISS";
            awarded = 0;
        }

        node.put("submitted", true);
        node.put("guess", guess);
        node.put("tier", tier);
        node.put("awardedXp", awarded);
        if (awarded > 0) {
            session.setScore(value(session.getScore()) + awarded);
        }
    }

    /**
     * 猜图:几张图里挑出正确的那一张。
     *
     * <p>★ 2026-09-10 改模型。这一段原来是**比价**(每件带价格、两两比谁贵、连对算 streak),
     * 而产品口径里「猜图」从来就是「几张图挑正确的那张」—— 两者不是同一个玩法。
     *
     * <p>哪一张是对的**只在服务端**:投影下发的 items 不带 correct,客户端手里只有图和名字。
     */
    private ChoiceRef submitPricePair(JsonNode config, ObjectNode state, Map<String, Object> payload,
            GameRuntimeSession session) {
        JsonNode cfg = config.get("pricePair");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启猜图");
        ObjectNode node = state.with("pricePair");
        if (node.path("finished").asBoolean(false)) {
            throw new IllegalStateException("这一局猜图已经结束了");
        }

        String pickId = text(payload, "pickId");
        boolean known = false;
        String answerId = "";
        for (JsonNode item : cfg.path("items")) {
            String id = item.path("id").asText();
            if (id.equals(pickId)) known = true;
            if (item.path("correct").asBoolean(false)) answerId = id;
        }
        if (!known) throw new IllegalArgumentException("请从这几张图里选一张");

        int attempts = node.path("attempts").asInt(0) + 1;
        node.put("attempts", attempts);
        node.put("lastPickId", pickId);

        boolean correct = pickId.equals(answerId);
        node.put("lastCorrect", correct);
        if (correct) {
            node.put("finished", true);
            node.put("passed", true);
            node.put("answerId", answerId);
            session.setScore(value(session.getScore()) + cfg.path("xp").asInt(0));
            return new ChoiceRef("pricePair", null);
        }
        int allowed = cfg.path("maxTries").asInt(0);
        if (allowed > 0 && attempts >= allowed) {
            node.put("finished", true);
            node.put("passed", false);
            // 次数用完了才揭晓答案:还有次数时给出来,这一关就没了
            node.put("answerId", answerId);
        }
        return null;
    }

    /**
     * 找东西作答:玩家在图上点一下,服务端判这一点落没落进某个热区。
     *
     * <p>坐标是归一化的 0–1,与玩家把图缩放到多大无关 —— 客户端把屏幕坐标反变换回
     * 原图比例再报上来。热区坐标只进不出(投影里没有它们),所以这里的比对是这个
     * 玩法**唯一**的判定点,没有第二道防线。
     *
     * <p>点空不报错,只累计 missCount:点空是玩法的一部分,把它当异常会让客户端
     * 每点空一次弹一个错误提示。
     */
    private ChoiceRef submitHiddenObject(JsonNode config, ObjectNode state, Map<String, Object> payload,
            GameRuntimeSession session) {
        JsonNode cfg = config.get("hiddenObject");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启找东西");
        ObjectNode node = state.with("hiddenObject");
        ArrayNode found = node.withArray("foundIds");
        JsonNode spots = cfg.path("hotspots");
        if (found.size() >= spots.size()) {
            throw new IllegalStateException("已经全部找到了");
        }
        if (node.path("exhausted").asBoolean(false)) {
            throw new IllegalStateException("这一关的次数已经用完了");
        }

        Object rawX = payload.get("x");
        Object rawY = payload.get("y");
        if (rawX == null || rawY == null) throw new IllegalArgumentException("请给出点击位置");
        double x;
        double y;
        try {
            x = Double.parseDouble(String.valueOf(rawX).trim());
            y = Double.parseDouble(String.valueOf(rawY).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("点击位置必须是数字");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || x > 1 || y < 0 || y > 1) {
            throw new IllegalArgumentException("点击位置必须是 0 到 1 之间的比例值");
        }

        String hitId = null;
        String hitLabel = null;
        for (JsonNode spot : spots) {
            String id = spot.path("id").asText("");
            if (containsText(found, id)) continue;          // 已经找到的不重复命中
            double dx = x - spot.path("x").asDouble();
            double dy = y - spot.path("y").asDouble();
            double r = spot.path("r").asDouble();
            if (dx * dx + dy * dy <= r * r) {
                hitId = id;
                hitLabel = spot.path("label").asText("");
                break;
            }
        }

        if (hitId == null) {
            int miss = node.path("missCount").asInt(0) + 1;
            node.put("missCount", miss);
            node.put("lastHit", false);
            node.putNull("lastLabel");
            /* 限次用完这一关就结束。★ 闸必须在服务端:客户端那圈点数只是画给人看的,
               不拦的话点完最后一次照样能接着点,「限定次数」就是句空话。 */
            int allowedMiss = cfg.path("maxTries").asInt(0);
            if (allowedMiss > 0 && miss >= allowedMiss) node.put("exhausted", true);
            return null;
        }
        found.add(hitId);
        node.put("lastHit", true);
        node.put("lastLabel", hitLabel);
        if (found.size() >= spots.size()) {
            // 分是找齐才给的:找到一半就发分,玩家没有找完的理由
            session.setScore(value(session.getScore()) + cfg.path("xp").asInt(0));
            return new ChoiceRef("hiddenObject", null);
        }
        return null;
    }

    /**
     * 竞猜下注:玩家押一个选项,答案要等商家次日才给。
     *
     * <p>两处落库:{@code state.predict} 供本局展示,{@code play_predict_bet} 供结算时
     * 按「这一轮押了某项的所有人」批量捞。后者是发奖的幂等键(uk_round_member),
     * 撞键即"已经押过了" —— 不先查后插,那中间有并发窗口。
     *
     * <p>下注即算通关,见 mechanicsReady 里那条注释。
     */
    private void submitPredict(JsonNode config, ObjectNode state, Map<String, Object> payload,
            GameRuntimeSession session, Long memberId) {
        JsonNode cfg = config.get("predict");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启竞猜");
        ObjectNode node = state.with("predict");
        if (!node.path("optionKey").asText("").isEmpty()) {
            throw new IllegalStateException("这一轮你已经押过了");
        }
        String key = text(payload, "optionKey");
        if (findOptionByKey(cfg.path("options"), key) == null) {
            throw new IllegalArgumentException("没有这个选项");
        }

        Date now = clock.now();
        ZonedDateTime local = Instant.ofEpochMilli(now.getTime()).atZone(BUSINESS_ZONE);
        int closeAtHour = cfg.path("closeAtHour").asInt(23);
        // 截止之后不再收注:否则玩家可以等商家结算完再回来押,那就不是猜了
        if (local.getHour() >= closeAtHour) {
            throw new IllegalStateException("今天这一轮已经截止,明天再来");
        }
        java.sql.Date playDay = java.sql.Date.valueOf(local.toLocalDate());

        // 结算行按需建:第一个下注的人顺手把这一轮开出来,商家侧的待办据此列出。
        // 撞 uk_round 说明别人先建了,不是错误 —— 下面的锁定读会把真实状态读回来。
        com.chengyinhub.business.domain.PlayPredictResult round =
                predictMapper.selectResult(session.getNodeId(), playDay);
        if (round == null) {
            com.chengyinhub.business.domain.PlayPredictResult created =
                    new com.chengyinhub.business.domain.PlayPredictResult();
            created.setNodeId(session.getNodeId());
            created.setPlayDay(playDay);
            created.setSettleStatus(com.chengyinhub.business.domain.PlayPredictResult.SETTLE_PENDING);
            created.setCreateTime(now);
            try {
                predictMapper.insertResult(created);
            } catch (org.springframework.dao.DuplicateKeyException ignored) {
                // 并发下第二个人也来建,uk_round 挡住即可,不是错误
            }
        }

        // 状态必须锁住轮次行之后判,不能拿上面那次快照读当结论:商家结算的 UPDATE
        // 可能正落在「读到未揭晓」与「写下这一注」之间 —— 那一注没人扫,reward_status
        // 永远停在 0。FOR UPDATE 读到最新已提交状态;反过来它也逼结算的 UPDATE
        // 等本事务提交,结算再扫时这一注就在结果里。
        com.chengyinhub.business.domain.PlayPredictResult locked =
                predictMapper.selectResultForUpdate(session.getNodeId(), playDay);
        if (locked == null || !Integer.valueOf(com.chengyinhub.business.domain.PlayPredictResult.SETTLE_PENDING)
                .equals(locked.getSettleStatus())) {
            throw new IllegalStateException("这一轮已经结束,不能再押了");
        }

        node.put("optionKey", key);
        node.put("playDay", playDay.toString());
        node.put("closeAtHour", closeAtHour);
        session.setScore(value(session.getScore()) + cfg.path("xp").asInt(0));

        com.chengyinhub.business.domain.PlayPredictBet bet =
                new com.chengyinhub.business.domain.PlayPredictBet();
        bet.setNodeId(session.getNodeId());
        bet.setTopicId(session.getTopicId());
        bet.setActivityId(session.getActivityId() == null ? 0L : session.getActivityId());
        bet.setPlayDay(playDay);
        bet.setMemberId(memberId);
        bet.setOptionKey(key);
        bet.setRewardStatus(com.chengyinhub.business.domain.PlayPredictBet.REWARD_PENDING);
        bet.setCreateTime(now);
        try {
            int inserted=predictMapper.insertBet(bet);
            if(config.has("outcomeRules")){if(inserted!=1)throw new IllegalStateException("竞猜下注未保存");if(bet.getId()==null)throw new IllegalStateException("竞猜下注凭证缺失");node.put("betId",bet.getId()).put("memberId",memberId);}
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // uk_round_member 撞了 = 这一轮已经押过。state 那边的判断可能因为
            // 会话重建而落空,以库为准。
            throw new IllegalStateException("这一轮你已经押过了");
        }
    }

    /** 数轴边界报错时把 480.0 写成 480,报错文案里不该出现小数点尾巴。 */
    private String trimNumber(double number) {
        if (number == Math.rint(number) && !Double.isInfinite(number)) {
            return String.valueOf((long) number);
        }
        return String.valueOf(number);
    }

    private String dayKey(Date now) {
        Date source = now == null ? new Date() : now;
        return Instant.ofEpochMilli(source.getTime()).atZone(BUSINESS_ZONE).toLocalDate().toString();
    }

    /**
     * v5.1 新玩法的运行期视图:公开配置 + 本局状态。
     * ★ 和 AdvancedGamePublicProjection 同规矩的白名单:blindTaste.answerKey 与 dailySign.poems
     *   在这里同样不出现 —— 它们只留在 config_snapshot 里供服务端比对。
     */
    /**
     * 问答三种模式。答案**只在服务端**:打字题的 answerText、选项题的 correct
     * 都不下发,客户端报的只是「我填了什么 / 我点了第几个」。
     */
    private List<ChoiceRef> submitQa(JsonNode config,ObjectNode state,Map<String,Object> payload,GameRuntimeSession session) {
        return submitQa(config,state,payload,session,null);
    }
    private List<ChoiceRef> submitQa(JsonNode config, ObjectNode state, Map<String, Object> payload,
            GameRuntimeSession session,Long memberId) {
        List<ChoiceRef> choices = new ArrayList<>();
        JsonNode cfg = config.get("qa");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启问答");
        ObjectNode node = state.with("qa");
        if (node.path("finished").asBoolean(false)) throw new IllegalStateException("这一题已经答完了");

        String mode = cfg.path("mode").asText("");
        int attempts = node.path("attempts").asInt(0) + 1;
        node.put("attempts", attempts);

        boolean correct;
        if ("TYPE".equals(mode)) {
            String input = text(payload, "input");
            if (input.isEmpty()) throw new IllegalArgumentException("请先写下你的答案");
            if (input.length() > 200) throw new IllegalArgumentException("答案不能超过 200 字");
            correct = matchesAnswer(input, cfg.path("answerText").asText(""));
            node.put("lastInput", input);
        } else if ("PICK".equals(mode)) {
            boolean judge = cfg.path("judge").asBoolean(true);
            if (!judge) {
                // 不判断对错:单选,任一有效选项即完成;正确答案 / 多选 / 次数等判断设置不参与
                if (payload != null && payload.containsKey("optionIds")) throw new IllegalArgumentException("这道题只能选一项");
                String optionId = text(payload, "optionId");
                JsonNode picked = null;
                for (JsonNode option : cfg.path("options")) {
                    if (optionId.equals(option.path("id").asText())) picked = option;
                }
                if (picked == null) throw new IllegalArgumentException("请从这几个选项里选一个");
                node.put("lastOptionId", optionId);
                node.put("lastFeedback", picked.path("fb").asText(""));
                putFeedbackImages(node, java.util.Collections.singletonList(picked));
                choices.add(new ChoiceRef("qa", optionId));
                node.put("lastCorrect", true);
                node.put("finished", true);
                node.put("passed", true);
                session.setScore(value(session.getScore()) + cfg.path("xp").asInt(0));
                return choices;
            }
            if (cfg.path("multi").asBoolean(false)) {
                // 多选:集合完全相等才过 —— 少选、多选、选错任何一个都不过
                correct = submitQaMulti(cfg, node, payload);
                // R14:多选里每个被选中的选项都是一个「选择」,各自的 effects 都写。
                for (JsonNode pickedId : node.path("lastOptionIds")) {
                    choices.add(new ChoiceRef("qa", pickedId.asText("")));
                }
            } else {
                String optionId = text(payload, "optionId");
                JsonNode picked = null;
                for (JsonNode option : cfg.path("options")) {
                    if (optionId.equals(option.path("id").asText())) picked = option;
                }
                if (picked == null) throw new IllegalArgumentException("请从这几个选项里选一个");
                correct = picked.path("correct").asBoolean(false);
                node.put("lastOptionId", optionId);
                // 这一条的反馈是商家写给「选了它的人」看的,判完才给
                node.put("lastFeedback", picked.path("fb").asText(""));
                putFeedbackImages(node, java.util.Collections.singletonList(picked));
                // R14:选了这个选项就写它的 effects(对错都算作者写下的结果)。
                choices.add(new ChoiceRef("qa", optionId));
            }
        } else {
            /* 拍照:判不了照片内容,交上来就算做到了。
               地址要留痕 —— 商家事后想看「玩家到底交了什么」时,这是唯一的证据。 */
            String imageUrl = text(payload, "imageUrl");
            if (imageUrl.isEmpty()) throw new IllegalArgumentException("请先拍一张");
            if(config.path("outcomeRules").path("version").asInt()==1)photoEvidenceVerifier.accept(imageUrl,memberId);
            node.put("lastImageUrl", imageUrl);
            correct = true;
        }

        node.put("lastCorrect", correct);
        if (correct) {
            node.put("finished", true);
            node.put("passed", true);
            revealQaAnswer(cfg, node);
            session.setScore(value(session.getScore()) + cfg.path("xp").asInt(0));
            return choices;
        }
        int allowed = cfg.path("maxTries").asInt(0);
        if (allowed > 0 && attempts >= allowed) {
            node.put("finished", true);
            node.put("passed", false);
            // 次数用完才揭晓,而且要商家开了「答错亮答案」才给 —— 一次性的题亮了就没下次
            if (cfg.path("reveal").asBoolean(false)) revealQaAnswer(cfg, node);
        }
        return choices;
    }

    /**
     * 多选作答:payload.optionIds 是一个 id 数组,提交的集合必须与正确答案集合**完全相等**。
     * 与单选一样,correct 标记只在服务端(config_snapshot),客户端手里只有选项文案。
     */
    private boolean submitQaMulti(JsonNode cfg, ObjectNode node, Map<String, Object> payload) {
        Object raw = payload.get("optionIds");
        if (!(raw instanceof List)) throw new IllegalArgumentException("请从这几个选项里选");
        List<?> picked = (List<?>) raw;
        if (picked.isEmpty()) throw new IllegalArgumentException("请从这几个选项里选");
        Set<String> known = new HashSet<>();
        for (JsonNode option : cfg.path("options")) known.add(option.path("id").asText(""));
        Set<String> chosen = new LinkedHashSet<>();
        for (Object one : picked) {
            String id = one == null ? "" : String.valueOf(one).trim();
            if (!known.contains(id)) throw new IllegalArgumentException("选了不存在的选项");
            if (!chosen.add(id)) throw new IllegalArgumentException("同一个选项不能重复选");
        }

        Set<String> answers = new HashSet<>(correctOptionIds(cfg));
        ArrayNode lastIds = node.putArray("lastOptionIds");
        StringBuilder feedback = new StringBuilder();
        List<JsonNode> pickedOptions = new ArrayList<>();
        for (JsonNode option : cfg.path("options")) {
            String id = option.path("id").asText("");
            if (!chosen.contains(id)) continue;
            lastIds.add(id);
            pickedOptions.add(option);
            // 反馈与单选同源:把选中的每个选项的 fb 拼起来,判完才给
            String fb = option.path("fb").asText("");
            if (fb.isEmpty()) continue;
            if (feedback.length() > 0) feedback.append(' ');
            feedback.append(fb);
        }
        node.put("lastFeedback", feedback.toString());
        putFeedbackImages(node, pickedOptions);
        return chosen.equals(answers);
    }

    /** 揭晓答案:单选给 answerId,多选给 answerIds —— 没开启的玩法一个字段都不加。 */
    private void revealQaAnswer(JsonNode cfg, ObjectNode node) {
        if (!"PICK".equals(cfg.path("mode").asText(""))) return;
        if (cfg.path("multi").asBoolean(false)) {
            ArrayNode ids = node.putArray("answerIds");
            for (String id : correctOptionIds(cfg)) ids.add(id);
        } else {
            node.put("answerId", answerOptionId(cfg));
        }
    }

    private List<String> correctOptionIds(JsonNode cfg) {
        List<String> ids = new ArrayList<>();
        for (JsonNode option : cfg.path("options")) {
            if (option.path("correct").asBoolean(false)) ids.add(option.path("id").asText(""));
        }
        return ids;
    }

    private String answerOptionId(JsonNode cfg) {
        for (JsonNode option : cfg.path("options")) {
            if (option.path("correct").asBoolean(false)) return option.path("id").asText("");
        }
        return "";
    }

    /** 打字题的比对:忽略大小写与首尾空格,多个答案用、分隔,命中任一即过。 */
    private boolean matchesAnswer(String input, String answerText) {
        String got = input.trim().toLowerCase();
        if (got.isEmpty()) return false;
        for (String one : answerText.split("[、,,]")) {
            if (one.trim().toLowerCase().equals(got)) return true;
        }
        return false;
    }

    /**
     * 扫码参与:扫完即完成,不出题也不判定。
     *
     * <p>★ 码不在玩法配置里,在**路线层**(节点的 checkin_code)。进店这件事只认扫码 ——
     * GPS 可以伪造,所以这里必须真比对一次,不能客户端说扫了就算。
     */
    private void submitScan(JsonNode config, ObjectNode state, Map<String, Object> payload,
            GameRuntimeSession session) {
        JsonNode cfg = config.get("scan");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启扫码");
        ObjectNode node = state.with("scan");
        if (node.path("scanned").asBoolean(false)) return;      // 扫过就算,重复扫不报错

        String code = text(payload, "code");
        if (code.isEmpty()) throw new IllegalArgumentException("没读到码");
        CmsTopicNode node0 = nodeMapper.selectCmsTopicNodeById(session.getNodeId());
        String expected = node0 == null ? "" : StringUtils.defaultString(node0.getCheckinCode()).trim();
        if (expected.isEmpty()) throw new IllegalStateException("这一站还没绑定二维码");
        if (!expected.equals(code.trim())) throw new IllegalArgumentException("这个码不是这一站的");

        node.put("scanned", true);
        session.setScore(value(session.getScore()) + cfg.path("xp").asInt(0));
    }

    /* ==================== R3 三个新玩法:排序 / 连线 / 分类 ====================
       三者的答案都只在 config_snapshot 里:sort.answerOrder / match.pairs / classify.answer。
       投影与运行期视图只下发题面,客户端手里没有答案 —— 判定就是服务的这一层做的。 */

    /**
     * 排序作答:提交玩家排好的 id 序列,与服务端冻结的 answerOrder 逐位比对。
     *
     * <p>回执只给整体对错(lastCorrect),不给逐位置对错 —— 逐位提示配合可重排
     * 等于把答案一格格试出来。这是有意的口径,前端的「哪几个位置错了」提示需要
     * 后端另开字段时再评估(见不确定清单)。
     *
     * <p>maxAttempts > 0 时,次数用尽即收摊(finished 但 passed=false),与猜图同口径:
     * 卡在一个排不出来的题上,玩家会退出而不是重排,整条路线断了。
     */
    private ChoiceRef submitSort(JsonNode config, ObjectNode state, Map<String, Object> payload,
            GameRuntimeSession session) {
        JsonNode cfg = config.get("sort");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启排序题");
        ObjectNode node = state.with("sort");
        if (node.path("finished").asBoolean(false)) {
            throw new IllegalStateException("这一局排序已经结束了");
        }

        Object raw = payload.get("order");
        if (!(raw instanceof List)) throw new IllegalArgumentException("请提交排好的顺序");
        List<?> submitted = (List<?>) raw;
        JsonNode items = cfg.path("items");
        if (submitted.size() != items.size()) {
            throw new IllegalArgumentException("排好的顺序必须包含全部待排项");
        }
        Set<String> known = new HashSet<>();
        for (JsonNode item : items) known.add(item.path("id").asText(""));
        List<String> order = new ArrayList<>();
        for (Object one : submitted) {
            String id = one == null ? "" : String.valueOf(one).trim();
            if (!known.contains(id)) throw new IllegalArgumentException("排好的顺序里有不存在的待排项");
            if (order.contains(id)) throw new IllegalArgumentException("排好的顺序不能重复引用同一项");
            order.add(id);
        }

        JsonNode answerOrder = cfg.path("answerOrder");
        boolean correct = true;
        for (int i = 0; i < answerOrder.size(); i++) {
            if (!answerOrder.get(i).asText("").equals(order.get(i))) {
                correct = false;
                break;
            }
        }

        int attempts = node.path("attempts").asInt(0) + 1;
        node.put("attempts", attempts);
        node.put("lastCorrect", correct);
        ArrayNode lastOrder = node.putArray("lastOrder");
        for (String id : order) lastOrder.add(id);
        if (correct) {
            node.put("finished", true);
            node.put("passed", true);
            // 排对才给分,与猜图/问答同口径;错了不给,重排也不会攒分
            session.setScore(value(session.getScore()) + cfg.path("xp").asInt(0));
            return new ChoiceRef("sort", null);
        }
        int allowed = cfg.path("maxAttempts").asInt(0);
        if (allowed > 0 && attempts >= allowed) {
            node.put("finished", true);
            node.put("passed", false);
        }
        return null;
    }

    /**
     * 连线作答:提交 [左 id, 右 id] 列表,与服务端冻结的 pairs 比对。
     *
     * <p>提交顺序无关紧要 —— 判的是每一条连线在不在答案里;由于两侧都不允许重复,
     * 「提交的每条都在答案里」等价于「两边一一对应且完全相等」。
     */
    private ChoiceRef submitMatch(JsonNode config, ObjectNode state, Map<String, Object> payload,
            GameRuntimeSession session) {
        JsonNode cfg = config.get("match");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启连线题");
        ObjectNode node = state.with("match");
        if (node.path("passed").asBoolean(false)) {
            throw new IllegalStateException("这一局连线已经结束了");
        }

        Object raw = payload.get("pairs");
        if (!(raw instanceof List)) throw new IllegalArgumentException("请提交连好的线");
        List<?> submitted = (List<?>) raw;
        JsonNode left = cfg.path("left");
        if (submitted.size() != left.size()) {
            throw new IllegalArgumentException("连线必须覆盖左侧全部条目");
        }
        Set<String> leftIds = new HashSet<>();
        for (JsonNode item : left) leftIds.add(item.path("id").asText(""));
        Set<String> rightIds = new HashSet<>();
        for (JsonNode item : cfg.path("right")) rightIds.add(item.path("id").asText(""));

        Set<String> seenLeft = new HashSet<>();
        Set<String> seenRight = new HashSet<>();
        boolean correct = true;
        for (Object one : submitted) {
            if (!(one instanceof List) || ((List<?>) one).size() != 2) {
                throw new IllegalArgumentException("每条连线必须是 [左 id, 右 id] 两个值");
            }
            List<?> pair = (List<?>) one;
            String leftId = pair.get(0) == null ? "" : String.valueOf(pair.get(0)).trim();
            String rightId = pair.get(1) == null ? "" : String.valueOf(pair.get(1)).trim();
            if (!leftIds.contains(leftId)) throw new IllegalArgumentException("连线里有不存在的左侧条目");
            if (!rightIds.contains(rightId)) throw new IllegalArgumentException("连线里有不存在的右侧条目");
            if (!seenLeft.add(leftId)) throw new IllegalArgumentException("左侧条目不能重复连线");
            if (!seenRight.add(rightId)) throw new IllegalArgumentException("右侧条目不能重复连线");
            if (!hasPair(cfg.path("pairs"), leftId, rightId)) correct = false;
        }

        node.put("attempts", node.path("attempts").asInt(0) + 1);
        node.put("lastCorrect", correct);
        if (correct) {
            node.put("finished", true);
            node.put("passed", true);
            // 全连对才给分;passed 一旦置位重复提交直接拒,不会重领
            session.setScore(value(session.getScore()) + cfg.path("xp").asInt(0));
            return new ChoiceRef("match", null);
        }
        return null;
    }

    private boolean hasPair(JsonNode pairs, String leftId, String rightId) {
        for (JsonNode pair : pairs) {
            if (leftId.equals(pair.path(0).asText("")) && rightId.equals(pair.path(1).asText(""))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 分类作答:提交 itemId → binId 的映射,与服务端冻结的 answer 比对。
     *
     * <p>提交的键必须恰好覆盖全部卡片(尺寸相等 + 逐张在册),多一张少一张都拒 ——
     * 少了那张永远分不对,多了说明客户端手里有已经删掉的卡片。
     */
    private ChoiceRef submitClassify(JsonNode config, ObjectNode state, Map<String, Object> payload,
            GameRuntimeSession session) {
        JsonNode cfg = config.get("classify");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启分类题");
        ObjectNode node = state.with("classify");
        if (node.path("passed").asBoolean(false)) {
            throw new IllegalStateException("这一局分类已经结束了");
        }

        Object raw = payload.get("placement");
        if (!(raw instanceof Map)) throw new IllegalArgumentException("请提交每张卡片放进哪个分类");
        Map<?, ?> placement = (Map<?, ?>) raw;
        JsonNode items = cfg.path("items");
        if (placement.size() != items.size()) {
            throw new IllegalArgumentException("每张卡片都要放进一个分类");
        }
        Set<String> binIds = new HashSet<>();
        for (JsonNode bin : cfg.path("bins")) binIds.add(bin.path("id").asText(""));

        JsonNode answer = cfg.path("answer");
        boolean correct = true;
        for (JsonNode item : items) {
            String itemId = item.path("id").asText("");
            if (!placement.containsKey(itemId)) {
                throw new IllegalArgumentException("每张卡片都要放进一个分类");
            }
            Object chosen = placement.get(itemId);
            String binId = chosen == null ? "" : String.valueOf(chosen).trim();
            if (!binIds.contains(binId)) throw new IllegalArgumentException("放进了不存在的分类");
            if (!binId.equals(answer.path(itemId).asText(""))) correct = false;
        }

        node.put("attempts", node.path("attempts").asInt(0) + 1);
        node.put("lastCorrect", correct);
        if (correct) {
            node.put("finished", true);
            node.put("passed", true);
            // 全分对才给分;passed 一旦置位重复提交直接拒,不会重领
            session.setScore(value(session.getScore()) + cfg.path("xp").asInt(0));
            return new ChoiceRef("classify", null);
        }
        return null;
    }

    /**
     * 对照作答:提交标出的条目 id 列表,与服务端冻结的 answer 做**集合相等**判定。
     *
     * <p>多标一条、少标一条都不过 —— 判的就是「缺失 / 被改动」的完整名单。
     * 提交顺序无关紧要,重复标记与不存在的 id 是坏输入直接拒,不算一次作答。
     *
     * <p>回执只给整体对错(lastCorrect)与次数,不给逐条对错 —— 逐条提示配合可重标
     * 等于把答案一条条试出来(排序题同一条口径)。
     *
     * <p>maxAttempts 配了才有上限(1 至 10):用尽即收摊(finished 但 passed=false),
     * 与排序题同口径;没配就是不限次,交对为止。
     */
    private void submitCompare(JsonNode config, ObjectNode state, Map<String, Object> payload,
            GameRuntimeSession session) {
        JsonNode cfg = config.get("compare");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启对照题");
        ObjectNode node = state.with("compare");
        if (node.path("finished").asBoolean(false)) {
            throw new IllegalStateException("这一局对照已经结束了");
        }

        Object raw = payload.get("marked");
        if (!(raw instanceof List)) throw new IllegalArgumentException("请提交标出的条目");
        List<?> submitted = (List<?>) raw;
        Set<String> known = new HashSet<>();
        for (JsonNode item : cfg.path("left").path("items")) known.add(item.path("id").asText(""));
        for (JsonNode item : cfg.path("right").path("items")) known.add(item.path("id").asText(""));
        // LinkedHashSet:集合判等用它,回显 lastMarked 时又保留玩家提交顺序
        Set<String> marked = new LinkedHashSet<>();
        for (Object one : submitted) {
            String id = one == null ? "" : String.valueOf(one).trim();
            if (!known.contains(id)) throw new IllegalArgumentException("标出的条目里有不存在的项");
            if (!marked.add(id)) throw new IllegalArgumentException("同一条目不能标两次");
        }

        Set<String> answer = new HashSet<>();
        for (JsonNode one : cfg.path("answer")) answer.add(one.asText(""));
        boolean correct = marked.equals(answer);

        int attempts = node.path("attempts").asInt(0) + 1;
        node.put("attempts", attempts);
        node.put("lastCorrect", correct);
        ArrayNode lastMarked = node.putArray("lastMarked");
        for (String id : marked) lastMarked.add(id);
        if (correct) {
            node.put("finished", true);
            node.put("passed", true);
            // 全标对才给分,与排序/连线/分类同口径;错了不给,重标也不会攒分
            session.setScore(value(session.getScore()) + cfg.path("xp").asInt(0));
            return;
        }
        int allowed = cfg.path("maxAttempts").asInt(0);
        if (allowed > 0 && attempts >= allowed) {
            node.put("finished", true);
            node.put("passed", false);
        }
    }

    /* ==================== 预制人生:建档 / 拍照审核 / 留言 / 限时打字 ====================
       判定一律在服务端,客户端只报原始输入。加成走现成的 effect 形状,
       由旅程本局状态承接(见 NodeJourneySnapshot.CHOICE_KITS 的 profile)。 */

    /**
     * 建档:一次提交整张表。答案落 state.profile.answers,**加成不进本局玩法状态** ——
     * 它们以 ChoiceRef("profile", 选项 key) 交给旅程运行时按作者变量写
     * (幸运用保留的 sys.luck,精力 / 念头 / 不稳定度是普通变量)。
     *
     * <p>重复提交拒(done 后不许改档):改档等于把已经生效的加成重算,而加成不退还。
     */
    private List<ChoiceRef> submitProfile(JsonNode config, ObjectNode state, Map<String, Object> payload,
            GameRuntimeSession session, Long memberId) {
        JsonNode cfg = config.get("profile");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启建档");
        ObjectNode node = state.with("profile");
        if (node.path("done").asBoolean(false)) {
            throw new IllegalStateException("档案已经建好了,不能再改");
        }
        Object rawAnswers = payload.get("answers");
        if (!(rawAnswers instanceof Map)) throw new IllegalArgumentException("请先填完这张表");
        Map<?, ?> answers = (Map<?, ?>) rawAnswers;

        List<ChoiceRef> choices = new ArrayList<>();
        ObjectNode stored = objectMapper.createObjectNode();
        for (JsonNode question : cfg.path("questions")) {
            String key = question.path("key").asText("");
            String label = question.path("label").asText("");
            Object raw = answers.get(key);
            if ("pick".equals(question.path("kind").asText(""))) {
                String picked = raw == null ? "" : String.valueOf(raw).trim();
                JsonNode pickedOption = findOptionByKey(question.path("options"), picked);
                if (pickedOption == null) {
                    throw new IllegalArgumentException("「" + label + "」还没有选");
                }
                // 咔哒(《预制人生》):本局状态够了,玩家选的被系统改写成作者定的那一项。
                // 条件问旅程本局(不稳定度等),判不了就不改写;改写后加成也按改写后的选项走。
                JsonNode override = question.get("override");
                JsonNode assigned = override == null ? null
                        : findOptionByKey(question.path("options"), override.path("option").asText(""));
                if (assigned != null && !picked.equals(assigned.path("key").asText())
                        && topicRouteRuntimeService != null
                        && topicRouteRuntimeService.conditionsPass(memberId, session.getTopicId(), session.getActivityId(), override.get("requires"))) {
                    ObjectNode hit = node.withArray("overrides").addObject();
                    hit.put("key", key);
                    hit.put("picked", pickedOption.path("label").asText(""));
                    hit.put("assigned", assigned.path("label").asText(""));
                    hit.put("voice", override.path("voice").asText(""));
                    picked = assigned.path("key").asText();
                }
                stored.put(key, picked);
                // 选项的加成由旅程运行时按 key 定位执行,不在本类里算
                choices.add(new ChoiceRef("profile", picked));
                continue;
            }
            String answered = raw == null ? "" : String.valueOf(raw).trim();
            if (answered.isEmpty()) {
                throw new IllegalArgumentException("「" + label + "」还没有填");
            }
            int maxLength = question.path("maxLength").asInt(12);
            if (answered.length() > maxLength) {
                throw new IllegalArgumentException("「" + label + "」不能超过 " + maxLength + " 字");
            }
            stored.put(key, answered);
        }
        // 客户端多报的键一律忽略:不在 questions 里的键没有变量语义,落库只会长脏数据

        String avatarUrl = text(payload, "avatarUrl");
        JsonNode avatar = cfg.get("avatar");
        boolean avatarOn = avatar != null && avatar.isObject() && avatar.path("enabled").asBoolean(false);
        if (avatarOn && avatar.path("required").asBoolean(false) && avatarUrl.isEmpty()) {
            throw new IllegalArgumentException("这张表还需要一张头像");
        }
        if (!avatarUrl.isEmpty()) {
            if (avatarUrl.length() > 512) throw new IllegalArgumentException("头像地址不能超过 512 字");
            if (!avatarUrl.startsWith("https://") && !avatarUrl.startsWith("/")) {
                throw new IllegalArgumentException("头像地址格式不正确");
            }
            node.put("avatarUrl", avatarUrl);
        }
        node.set("answers", stored);
        node.put("done", true);
        if (cfg.path("xp").asInt(0) > 0) {
            session.setScore(value(session.getScore()) + cfg.path("xp").asInt(0));
        }
        return choices;
    }

    /**
     * 拍照审核:判定全在服务端 —— 照片地址 + 作者那句 requirement 交给视觉模型,
     * 按回话的 pass + confidence 判过不过(契约 §2.2)。
     * 客户端上报的 score 一律忽略:payload 里带了也不读,不留任何影响判定的后门。
     *
     * <p>上传两步走(先传图拿地址,再连地址提交),与 qa 的拍照题同一条路 ——
     * 不要在客户端把它合成一步,组件手里那个临时路径出了手机就不存在。
     *
     * <p>降级口径(重要):key 未注入 / 超时 / 限流 / 模型回话解析失败时,**不判死也不假装通过** ——
     * 按该段 fallback 处理,state 标 flagged + degraded。理由:硬拦会让节点永久不可完成,
     * 把一条路线变成死路。flagged 是留给商家的粘性痕迹;degraded 只描述最近这一次是否降级。
     *
     * <p>fallback=pass 且次数用尽时放过并打 flagged(玩家没拍到达标,商家事后看得出来);
     * fallback=retake 则继续重拍,maxTries 只决定「什么时候提示重拍」,不作硬闸。
     *
     * <p>返回值是「这一趟该不该铸一张藏品卡」:只有**拿到真结论且判过**、并且节点配的是 CARD 玩法时非空。
     * 降级放行与次数用尽兜底放行都返回 null —— 前者没审、后者没拍到达标,铸出卡来就是把假结论落成实物。
     */
    private void submitAlbum(JsonNode config,ObjectNode state,Map<String,Object> payload,Long memberId) {
        if(config.path("outcomeRules").path("version").asInt()!=1 || !enabled(config.path("album")))throw new IllegalArgumentException("本局未开启相册提交");
        JsonNode images=objectMapper.valueToTree(payload.get("imageUrls"));
        if(!images.isArray() || images.size()<1 || images.size()>20)throw new IllegalArgumentException("相册需提交1至20张照片");
        for(JsonNode image:images){if(!image.isTextual() || image.asText().isEmpty() || image.asText().length()>1024)throw new IllegalArgumentException("相册照片地址不合法");photoEvidenceVerifier.accept(image.asText(),memberId);}
        state.with("album").set("images",images.deepCopy());state.with("album").put("done",true);
    }

    private CardMintRequest submitPhotoCheck(JsonNode config, ObjectNode state, Map<String, Object> payload,
            GameRuntimeSession session, Long memberId) {
        JsonNode cfg = config.get("photoCheck");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启拍照审核");
        ObjectNode node = state.with("photoCheck");
        if (node.path("passed").asBoolean(false)) throw new IllegalStateException("这一张已经过了");

        String imageUrl = text(payload, "imageUrl");
        if (imageUrl.isEmpty()) throw new IllegalArgumentException("请先拍一张");
        if (imageUrl.length() > 512) throw new IllegalArgumentException("照片地址不能超过 512 字");

        boolean r5=config.path("outcomeRules").path("version").asInt()==1;
        if(r5)photoEvidenceVerifier.accept(imageUrl,memberId);
        int tries = node.path("tries").asInt(0) + 1;
        node.put("tries", tries);
        node.put("lastUrl", imageUrl);

        PhotoCheckVisionResult review = photoCheckVisionService == null
                ? PhotoCheckVisionResult.degraded("视觉审核通道未装配")
                : photoCheckVisionService.review(memberId, imageUrl, cfg.path("requirement").asText(""));
        boolean fallbackPass = !r5 && "pass".equals(cfg.path("fallback").asText("retake"));

        if (review.isDegraded()) {
            node.put("degraded", true);
            node.put("verdict", "DEGRADED");
            node.put("flagged", true);
            node.put("lastReason", review.getReason());
            node.put("lastConfidence", 0);
            if (fallbackPass) {
                node.put("passed", true);
            }
            return null;
        }

        node.put("degraded", false);
        int minConfidence = cfg.path("minConfidence").asInt(60);
        boolean passed = review.isPass() && review.getConfidence() >= minConfidence;
        node.put("lastReason", review.getReason());
        node.put("lastConfidence", review.getConfidence());
        if (passed) {
            node.put("passed", true);
            node.put("verdict", "PASS");
            awardOnce(node);
            session.setScore(value(session.getScore()) + cfg.path("xp").asInt(0));
            return "CARD".equals(cfg.path("mode").asText("")) ? new CardMintRequest(imageUrl, cfg) : null;
        }
        if(r5){node.put("passed",false);node.put("verdict","REJECT");return null;}
        if (tries >= cfg.path("maxTries").asInt(1) && fallbackPass) {
            node.put("passed", true);
            node.put("flagged", true);
            node.put("verdict", "FALLBACK_PASS");
        }
        return null;
    }

    /**
     * 留言:一句留给下一个人。存 play_note(跨玩家、按节点),不在会话 state 里 ——
     * state 只记「我留过了」,读的人在投影里拿 previous。
     */
    private void submitNote(JsonNode config, ObjectNode state, Map<String, Object> payload,
            GameRuntimeSession session, Long memberId) {
        JsonNode cfg = config.get("note");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启留言");
        ObjectNode node = state.with("note");
        if (node.path("done").asBoolean(false)) throw new IllegalStateException("这一句已经留下了");
        String content = text(payload, "text");
        int maxLength = cfg.path("maxLength").asInt(40);
        if (content.isEmpty()) throw new IllegalArgumentException("先写一句再留下");
        if (content.length() > maxLength) {
            throw new IllegalArgumentException("留言不能超过 " + maxLength + " 字");
        }
        if (playNoteMapper == null) throw new IllegalStateException("留言通道不可用");
        // 审核本轮只做长度 + 预设短句两道,机审不接(契约 §2.4,待裁决)

        PlayNote note = new PlayNote();
        note.setTopicId(session.getTopicId());
        note.setNodeId(session.getNodeId());
        note.setMemberId(memberId);
        note.setContent(content);
        note.setDelFlag(0);
        note.setCreateTime(clock.now());
        try {
            if (playNoteMapper.insert(note) != 1) throw new IllegalStateException("留言保存失败");
        } catch (DuplicateKeyException alreadyLeft) {
            // uk_member_node 撞了 = 这一站这个人已经留过(可能是上一局留下的)
            throw new IllegalStateException("这一句已经留下了");
        }
        node.put("text", content);
        node.put("at", clock.now().getTime());
        node.put("done", true);
        if (cfg.path("xp").asInt(0) > 0) {
            session.setScore(value(session.getScore()) + cfg.path("xp").asInt(0));
        }
    }

    /**
     * 限时打字:必须先在 START_CHALLENGE 开表,服务端拿那一刻的服务器时间复核 ——
     * 设备时钟玩家改得动,不先开表的提交一律判「还没开始」。
     *
     * <p>判定 = 内容相等(按 caseSensitive)+ 用时 ≤ seconds。没过还有次数时把表清掉,
     * 下一次要重新开表(与精准停表同一条口径)。
     */
    private void submitTypeIn(JsonNode config, ObjectNode state, Map<String, Object> payload,
            GameRuntimeSession session) {
        JsonNode cfg = config.get("typeIn");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启限时打字");
        ObjectNode node = state.with("typeIn");
        if (node.path("submitted").asBoolean(false)) throw new IllegalStateException("这一局已经交过了");
        long serverElapsed = serverElapsedMs(node, "限时打字");

        long elapsedMs = requireNonNegativeLong(payload, "elapsedMs", "用时必须是非负整数");
        long limitMs = cfg.path("seconds").asLong(0L) * 1000L;
        if (config.path("outcomeRules").path("version").asInt()!=1 && elapsedMs > limitMs + CLOCK_SLACK_MS) throw new IllegalStateException("已经超时了");
        // 报的用时比服务器记的还长 —— 这两个数只能来自同一段真实经过的时间
        if (elapsedMs > serverElapsed + CLOCK_SLACK_MS) throw new IllegalArgumentException("用时与记录对不上");
        String typed = text(payload, "text");
        if (typed.isEmpty()) throw new IllegalArgumentException("请先打出这行字");
        if (typed.length() > 40) throw new IllegalArgumentException("提交内容不能超过 40 字");

        String target = cfg.path("target").asText("");
        boolean same = cfg.path("caseSensitive").asBoolean(false)
                ? typed.equals(target) : typed.equalsIgnoreCase(target);
        int attempts = node.path("attempts").asInt(0) + 1;
        node.put("attempts", attempts);
        node.remove("startedAtMs");     // 重来要重新开表,老表不留给下一次
        node.put("lastText", typed);
        if (same) {
            node.put("submitted", true);
            node.put("passed", true);
            awardOnce(node);
            session.setScore(value(session.getScore()) + cfg.path("xp").asInt(0));
            return;
        }
        int allowed = cfg.path("tries").asInt(0);
        if (allowed > 0 && attempts >= allowed) {
            node.put("submitted", true);
            node.put("passed", false);
        }
    }

    /* ==================== 决定类:抛硬币 / 掷骰子 ====================
       秘密不在配置里 —— 朝哪面、几个点都是**服务端现掷**的。客户端只负责把那一下演出来。 */

    /** 抛一次,认结果。已经抛过就原样返回:能抛到满意为止的话,这一屏就不是「认」而是「挑」。 */
    private void flipCoin(JsonNode config, ObjectNode state) {
        JsonNode cfg = config.get("coinFlip");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启抛硬币");
        ObjectNode node = state.with("coinFlip");
        if (node.path("flipped").asBoolean(false)) return;
        boolean heads = random.nextInt(2) == 0;
        JsonNode face = cfg.path(heads ? "heads" : "tails");
        node.put("flipped", true);
        node.put("side", heads ? "HEADS" : "TAILS");
        node.put("action", face.path("action").asText(""));
        awardOnce(node);
    }

    /** 掷一次。两颗只报点数和 —— 拿两颗的和去索引六个面本来就是这一屏的规则。 */
    private void rollDice(JsonNode config, ObjectNode state) {
        JsonNode cfg = config.get("diceRoll");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启掷骰子");
        ObjectNode node = state.with("diceRoll");
        if (node.path("rolled").asBoolean(false)) return;
        if ("d20".equals(cfg.path("mode").asText())) {
            String rollMode = cfg.path("rollMode").asText();
            int first = random.nextInt(20) + 1;
            ArrayNode dice = node.putArray("pips").add(first);
            int kept = first;
            if (!"normal".equals(rollMode)) {
                int second = random.nextInt(20) + 1;
                dice.add(second);
                kept = "advantage".equals(rollMode) ? Math.max(first, second) : Math.min(first, second);
            }
            int total = kept + cfg.path("modifier").asInt();
            boolean success = total >= cfg.path("dc").asInt();
            node.put("rolled", true);
            node.put("kept", kept);
            node.put("total", total);
            node.put("success", success);
            node.put("action", cfg.path(success ? "successText" : "failText").asText());
            awardOnce(node);
            return;
        }
        int count = cfg.path("diceCount").asInt(1) == 2 ? 2 : 1;
        ArrayNode pips = node.putArray("pips");
        int sum = 0;
        for (int i = 0; i < count; i++) {
            int pip = random.nextInt(6) + 1;
            pips.add(pip);
            sum += pip;
        }
        node.put("rolled", true);
        node.put("sum", sum);
        JsonNode faces = cfg.path("faces");
        node.put("action", sum >= 1 && sum <= faces.size() ? (faces.get(sum - 1).isObject()?faces.get(sum - 1).path("action").asText(""):faces.get(sum - 1).asText("")) : "");
        awardOnce(node);
    }

    /* ==================== 挑战类五个:开表 → 报成绩 → 服务端复核 ====================
       ★ 客户端报的毫秒数是**设备时钟**算的,而设备时钟玩家改得动。所以每一条成绩都要
       拿 START_CHALLENGE 记下的服务器时间复核一遍,对不上就拒。 */

    private void startChallenge(JsonNode config, ObjectNode state, Map<String, Object> payload, Long memberId) {
        String game = challengeKey(payload);
        if (!enabled(config.path(game))) throw new IllegalArgumentException("该玩法未开启这一项");
        ObjectNode node = state.with(game);
        if (node.path("submitted").asBoolean(false)) throw new IllegalStateException("这一局已经交过了");
        node.put("startedAtMs", clock.now().getTime());
        node.put("attempts", node.path("attempts").asInt(0));
        if (SELF_REPORTED_SECTIONS.contains(game)) issueAttempt(node, config.path(game), memberId);
    }

    /**
     * C1:每次开表发一个服务端挑战身份,绑本人 + 本局(state 就在本局会话里)+ 本节点,单次消费、会过期。
     * 重开就换新的 —— 后台切走/中断前那次的成绩不能拿来结算。
     */
    private void issueAttempt(ObjectNode node, JsonNode cfg, Long memberId) {
        long now = clock.now().getTime();
        long previous = node.path("attempt").path("id").asLong(0L);
        long id = attemptIdSource.getAsLong() & JS_SAFE_INTEGER_MASK;
        if (id == 0L || id == previous) id = previous % JS_SAFE_INTEGER_MASK + 1L;
        double configuredSeconds = Math.max(cfg.path("seconds").asDouble(0d),
                Math.max(cfg.path("targetSeconds").asDouble(0d), cfg.path("holdSeconds").asDouble(0d)));
        ObjectNode attempt = node.putObject("attempt");
        attempt.put("id", id);
        attempt.put("memberId", memberId == null ? 0L : memberId);
        attempt.put("issuedAtMs", now);
        attempt.put("expiresAtMs", now + (long) Math.ceil(configuredSeconds * 1000d) + ATTEMPT_TTL_SLACK_MS);
        attempt.put("used", false);
    }

    /**
     * 提交带了 attemptId 就必须是本人当前这一次开表、没交过、没过期;对不上一律不结算。
     * 没带(存量客户端)按原复核口径走 —— 奖励上限在完成关卡那一步统一封顶,不靠这里。
     */
    private void requireCurrentAttempt(JsonNode node, Map<String, Object> payload, Long memberId) {
        Object raw = payload.get("attemptId");
        if (raw == null || String.valueOf(raw).trim().isEmpty()) return;
        long claimed;
        try {
            claimed = Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("挑战身份不对，请重新开始");
        }
        JsonNode attempt = node.path("attempt");
        if (!attempt.isObject() || claimed != attempt.path("id").asLong(0L)
                || memberId == null || memberId.longValue() != attempt.path("memberId").asLong(0L)) {
            throw new IllegalStateException("这一次挑战已经作废，请重新开始");
        }
        if (attempt.path("used").asBoolean(false)) throw new IllegalStateException("这一次挑战已经交过了，请重新开始");
        if (clock.now().getTime() > attempt.path("expiresAtMs").asLong(0L)) throw new IllegalStateException("这一次挑战已经过期，请重新开始");
    }

    /** 队伍共享局按成员存的开表现场:{memberId: {game: {startedAtMs, attempt}}} */
    private static final String TEAM_MEMBER_CHALLENGES = "teamMemberChallenges";

    /** 把本人那份开表现场摆回共享段;本人没有(升级前开的表)就按原共享口径,返回 false */
    private boolean restoreTeamMemberChallenge(ObjectNode state, String game, Long memberId) {
        JsonNode own = state.path(TEAM_MEMBER_CHALLENGES).path(String.valueOf(memberId)).path(game);
        if (!own.isObject()) return false;
        ObjectNode mechanic = state.with(game);
        if (own.path("startedAtMs").isNumber()) mechanic.put("startedAtMs", own.path("startedAtMs").asLong()); else mechanic.remove("startedAtMs");
        if (own.path("attempt").isObject()) mechanic.set("attempt", own.path("attempt").deepCopy()); else mechanic.remove("attempt");
        return true;
    }

    /** 共享段上本人这次开表/提交后的现场存回本人名下;别人的挑战身份不会被存进来 */
    private void saveTeamMemberChallenge(ObjectNode state, String game, Long memberId) {
        JsonNode mechanic = state.path(game);
        ObjectNode own = state.with(TEAM_MEMBER_CHALLENGES).with(String.valueOf(memberId)).putObject(game);
        if (mechanic.path("startedAtMs").isNumber()) own.put("startedAtMs", mechanic.path("startedAtMs").asLong());
        JsonNode attempt = mechanic.path("attempt");
        if (attempt.isObject() && memberId != null && memberId.longValue() == attempt.path("memberId").asLong(0L)) own.set("attempt", attempt.deepCopy());
    }

    private void consumeAttempt(JsonNode node) {
        JsonNode attempt = node.path("attempt");
        if (attempt.isObject()) ((ObjectNode) attempt).put("used", true);
    }

    /**
     * 本人、未用、未过期的挑战身份才下发;别人的(队伍共享局)、交过的、过期的一律不给。
     * 按成员各存的那份(队伍共享局 / 归零挑战)优先,共享段上可能是队友刚开的那份。
     */
    private void putAttempt(Map<String, Object> value, JsonNode state, String game, Long actorMemberId) {
        String member = String.valueOf(actorMemberId);
        JsonNode attempt = state.path(TEAM_MEMBER_CHALLENGES).path(member).path(game).path("attempt");
        if (!attempt.isObject()) attempt = state.path("zeroChallengeAttempts").path(member).path(game);
        if (!attempt.isObject()) attempt = state.path(game).path("attempt");
        if (!attempt.isObject() || attempt.path("used").asBoolean(false) || actorMemberId == null
                || actorMemberId.longValue() != attempt.path("memberId").asLong(0L)
                || clock.now().getTime() > attempt.path("expiresAtMs").asLong(0L)) return;
        value.put("attemptId", attempt.path("id").asLong());
        value.put("attemptExpiresAtMs", attempt.path("expiresAtMs").asLong());
    }

    private String challengeKey(Map<String, Object> payload) {
        String game = StringUtils.defaultString(String.valueOf(payload.get("game"))).trim();
        switch (game) {
            case "reaction": case "ballShake": case "quietHold":
            // 喊一嗓子(现场感契约 §4):与 quietHold 同路,先开表再按服务器时间复核
            case "shout":
            case "countdown": case "stopwatch":
            // 罗盘没有计时,开表只为领 C1 挑战身份;不开表的存量客户端照旧能交
            case "compass":
            // 预制人生限时打字:和上面五个同路,先开表再提交
            case "typeIn":
                return game;
            default:
                throw new IllegalArgumentException("这一项不支持开始计时");
        }
    }

    /** 从 START_CHALLENGE 到现在,服务端这边真的过去了多少毫秒。没开始过就报错。 */
    private long serverElapsedMs(ObjectNode node, String label) {
        long startedAt = node.path("startedAtMs").asLong(0L);
        if (startedAt <= 0L) throw new IllegalStateException(label + "还没开始");
        long elapsed = clock.now().getTime() - startedAt;
        if (elapsed < 0L) throw new IllegalStateException("时间不对");
        return elapsed;
    }

    private void submitReaction(JsonNode config, ObjectNode state, Map<String, Object> payload) {
        JsonNode cfg = config.get("reaction");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启变色就点");
        ObjectNode node = state.with("reaction");
        if (node.path("submitted").asBoolean(false)) throw new IllegalStateException("这一局已经交过了");
        long elapsed = serverElapsedMs(node, "变色就点");

        Object raw = payload.get("roundsMs");
        if (!(raw instanceof List)) throw new IllegalArgumentException("请提交每一轮的反应毫秒");
        List<?> rounds = (List<?>) raw;
        if (rounds.isEmpty()) throw new IllegalArgumentException("请提交每一轮的反应毫秒");
        if (rounds.size() != cfg.path("rounds").asInt(0)) {
            throw new IllegalArgumentException("请完成配置的全部轮次后提交");
        }
        long sum = 0L;
        long best = Long.MAX_VALUE;
        for (Object one : rounds) {
            long ms;
            try {
                ms = Long.parseLong(String.valueOf(one).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("反应毫秒必须是整数");
            }
            // 比人的极限还快的那一轮不是反应,是提前按下去的
            if (ms < MIN_HUMAN_REACTION_MS) throw new IllegalArgumentException("有一轮抢跑了");
            sum += ms;
            best = Math.min(best, ms);
        }
        // 几轮加起来比这一局真实用时还长 —— 这些数不可能是这一局产生的
        if (sum > elapsed) throw new IllegalArgumentException("成绩与用时对不上");

        long average = Math.round((double) sum / rounds.size());
        node.put("averageMs", average);
        node.put("bestMs", best);
        node.put("submitted", true);
        boolean passed = best <= cfg.path("goalMs").asLong(Long.MAX_VALUE);
        node.put("passed", passed);
        if (passed) awardOnce(node);
    }

    private void submitBallShake(JsonNode config, ObjectNode state, Map<String, Object> payload) {
        JsonNode cfg = config.get("ballShake");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启弹球");
        ObjectNode node = state.with("ballShake");
        if (node.path("submitted").asBoolean(false)) throw new IllegalStateException("这一局已经交过了");
        long elapsed = serverElapsedMs(node, "弹球");

        long hits = requireNonNegativeLong(payload, "hits", "撞击次数必须是非负整数");
        long ceiling = Math.max(1L, elapsed * MAX_SHAKE_HITS_PER_SECOND / 1000L);
        if (hits > ceiling) throw new IllegalArgumentException("撞击次数与用时对不上");
        if (cfg.path("timed").asBoolean(false)) {
            long limitMs = cfg.path("seconds").asLong(0L) * 1000L;
            if (limitMs > 0L && elapsed > limitMs + CLOCK_SLACK_MS) {
                throw new IllegalStateException("已经超时了");
            }
        }
        boolean passed = hits >= cfg.path("goal").asLong(Long.MAX_VALUE);
        node.put("hits", hits);
        node.put("submitted", true);
        node.put("passed", passed);
        if (passed) awardOnce(node);
    }

    private void submitQuietHold(JsonNode config, ObjectNode state, Map<String, Object> payload) {
        JsonNode cfg = config.get("quietHold");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启安静挑战");
        ObjectNode node = state.with("quietHold");
        if (node.path("submitted").asBoolean(false)) throw new IllegalStateException("这一局已经交过了");
        long elapsed = serverElapsedMs(node, "安静挑战");

        long heldMs = requireNonNegativeLong(payload, "heldMs", "安静时长必须是非负整数");
        if (heldMs > elapsed + CLOCK_SLACK_MS) throw new IllegalArgumentException("安静时长与用时对不上");
        boolean passed = heldMs >= cfg.path("seconds").asLong(Long.MAX_VALUE) * 1000L;
        node.put("heldMs", heldMs);
        node.put("submitted", true);
        node.put("passed", passed);
        if (passed) awardOnce(node);
    }

    /**
     * 罗盘指向:客户端报它对准后保持住了,服务端只复核「报来的方位确实在容差内」这一条。
     *
     * <p>★ 这层是**防误发,不是防作弊**(现场感契约 §3.3):改客户端代码就能伪造方位角,
     * 伪造成本与计步同档 —— 所以这一段的奖励只许是 xp,禁止挂到店凭证或任何有金额的完成条件。
     * 没有次数上限、没有对错:对不准就一直转,这一屏的完成口径是「对准过一次」。
     */
    private void submitCompass(JsonNode config, ObjectNode state, Map<String, Object> payload,
            GameRuntimeSession session) {
        JsonNode cfg = config.get("compass");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启罗盘指向");
        ObjectNode node = state.with("compass");
        if (node.path("done").asBoolean(false)) return;   // 对准过一次就不再收,也不重复发分
        long bearing = requireNonNegativeLong(payload, "bearing", "方位角须为 0 至 359 的整数");
        if (bearing > 359) throw new IllegalArgumentException("方位角须为 0 至 359 的整数");
        int tolerance = cfg.path("tolerance").asInt(0);
        if (angleDiff(bearing, cfg.path("bearing").asInt(0)) > tolerance) {
            throw new IllegalArgumentException("还没对准方向,再转一转");
        }
        node.put("bearing", bearing);
        node.put("done", true);
        int xp = cfg.path("xp").asInt(0);
        if (xp > 0 && !node.path("awarded").asBoolean(false)) {
            node.put("awarded", true);
            session.setScore(value(session.getScore()) + xp);
        }
    }

    /** 两方位角的最小夹角:罗盘绕 360,359° 与 1° 相差 2° 而不是 358°(契约 §6.2 钉死的口径)。 */
    private static int angleDiff(long a, int b) {
        long raw = Math.abs(a - (long) b) % 360;
        return (int) Math.min(raw, 360 - raw);
    }

    /**
     * 喊一嗓子(现场感契约 §4):与安静挑战同一条复核线 —— 客户端只交持续毫秒数,
     * 服务端拿 START_CHALLENGE 记的服务器时间验它没超发,够不够长由配置秒数判。
     * 录音不上传、不落盘:这一屏要的只有每帧振幅的累计结果,不要内容。
     */
    private void submitShout(JsonNode config, ObjectNode state, Map<String, Object> payload) {
        JsonNode cfg = config.get("shout");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启喊一嗓子");
        ObjectNode node = state.with("shout");
        if (node.path("submitted").asBoolean(false)) throw new IllegalStateException("这一局已经交过了");
        long elapsed = serverElapsedMs(node, "喊一嗓子");

        long heldMs = requireNonNegativeLong(payload, "heldMs", "喊的时长必须是非负整数");
        if (heldMs > elapsed + CLOCK_SLACK_MS) throw new IllegalArgumentException("喊的时长与用时对不上");
        boolean passed = heldMs >= cfg.path("seconds").asLong(Long.MAX_VALUE) * 1000L;
        node.put("heldMs", heldMs);
        node.put("submitted", true);
        node.put("passed", passed);
        if (passed) awardOnce(node);
    }

    /** 倒计时不收客户端报的数:等没等够由服务端自己算 —— 报数这件事在这一屏上没有意义。 */
    private void submitCountdown(JsonNode config, ObjectNode state) {
        JsonNode cfg = config.get("countdown");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启倒计时");
        ObjectNode node = state.with("countdown");
        if (node.path("submitted").asBoolean(false)) throw new IllegalStateException("这一局已经交过了");
        long elapsed = serverElapsedMs(node, "倒计时");
        long needMs = cfg.path("seconds").asLong(0L) * 1000L;
        if (elapsed + CLOCK_SLACK_MS < needMs) throw new IllegalStateException("还没到点");
        node.put("submitted", true);
        node.put("passed", true);
        awardOnce(node);
    }

    private void submitStopwatch(JsonNode config, ObjectNode state, Map<String, Object> payload) {
        JsonNode cfg = config.get("stopwatch");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启精准停表");
        ObjectNode node = state.with("stopwatch");
        if (node.path("submitted").asBoolean(false)) throw new IllegalStateException("这一局已经交过了");
        long elapsed = serverElapsedMs(node, "精准停表");

        long stoppedMs = requireNonNegativeLong(payload, "stoppedMs", "停表毫秒必须是非负整数");
        if (stoppedMs > elapsed + CLOCK_SLACK_MS) throw new IllegalArgumentException("停表成绩与用时对不上");

        int attempts = node.path("attempts").asInt(0) + 1;
        node.put("attempts", attempts);
        long targetMs = Math.round(cfg.path("targetSeconds").asDouble(0d) * 1000d);
        long diff = Math.abs(stoppedMs - targetMs);
        boolean hit = diff <= cfg.path("toleranceMs").asLong(0L);
        node.put("lastDiffMs", diff);
        long best = node.path("bestDiffMs").asLong(Long.MAX_VALUE);
        if (diff < best) node.put("bestDiffMs", diff);

        int allowed = cfg.path("tries").asInt(0);
        if (hit) {
            node.put("submitted", true);
            node.put("passed", true);
            awardOnce(node);
        } else if (allowed > 0 && attempts >= allowed) {
            node.put("submitted", true);
            node.put("passed", false);
        } else {
            // 还有次数就不收摊,但重新开始要再报一次 START_CHALLENGE
            node.remove("startedAtMs");
        }
    }

    /** 一局只记一次「拿到了」。重复提交不重复发 —— 发奖的真动作在完成关卡那一步。 */
    private void awardOnce(ObjectNode node) {
        if (node.path("awarded").asBoolean(false)) return;
        node.put("awarded", true);
    }

    private long requireNonNegativeLong(Map<String, Object> payload, String key, String message) {
        Object raw = payload.get(key);
        long value;
        try {
            value = Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(message);
        }
        if (value < 0L) throw new IllegalArgumentException(message);
        return value;
    }

    /**
     * 五个「设备报数」玩法的公开投影。
     *
     * <p>它们的运行时状态长得一样(开没开始、交没交、过没过),所以走同一个投影;
     * 各自的两个配置字段用参数带进来,不为五个一样的形状写五遍。
     */
    private void putChallenge(Map<String, Object> kit, JsonNode config, ObjectNode state,
            String game, String fieldA, String fieldB, Long actorMemberId) {
        JsonNode cfg = config.path(game);
        if (!enabled(cfg)) return;
        JsonNode st = state.path(game);
        Map<String, Object> value = new LinkedHashMap<>();
        putText(value, "kicker", cfg);
        copyField(value, cfg, fieldA);
        copyField(value, cfg, fieldB);
        if (cfg.has("timed")) value.put("timed", cfg.path("timed").asBoolean(false));
        if (cfg.has("tries")) value.put("tries", cfg.path("tries").asInt(0));
        value.put("xp", cfg.path("xp").asInt(0));
        value.put("started", st.path("startedAtMs").asLong(0L) > 0L);
        value.put("submitted", st.path("submitted").asBoolean(false));
        value.put("passed", st.path("passed").asBoolean(false));
        value.put("awarded", st.path("awarded").asBoolean(false));
        if (st.has("attempts")) value.put("attempts", st.path("attempts").asInt(0));
        if (st.has("averageMs")) value.put("averageMs", st.path("averageMs").asLong(0L));
        if (st.has("bestMs")) value.put("bestMs", st.path("bestMs").asLong(0L));
        if (st.has("hits")) value.put("hits", st.path("hits").asLong(0L));
        if (st.has("heldMs")) value.put("heldMs", st.path("heldMs").asLong(0L));
        if (st.has("lastDiffMs")) value.put("lastDiffMs", st.path("lastDiffMs").asLong(0L));
        if (st.has("bestDiffMs")) value.put("bestDiffMs", st.path("bestDiffMs").asLong(0L));
        // C1:服务端挑战身份 + 证据口径。设备自报那几段只算娱乐成绩,奖励上限由这里说了算,界面照着显示
        putAttempt(value, state, game, actorMemberId);
        boolean selfReported = SELF_REPORTED_SECTIONS.contains(game);
        value.put("evidence", selfReported ? "SELF_REPORTED" : "SERVER_TIMED");
        value.put("rewardCeiling", (selfReported ? RewardPolicy.XP_BADGE_ONLY : RewardPolicy.FULL).name());
        kit.put(game, value);
    }

    private void copyField(Map<String, Object> value, JsonNode cfg, String field) {
        if (field == null || !cfg.has(field)) return;
        JsonNode node = cfg.get(field);
        if (node.isNumber()) value.put(field, node.numberValue());
        else if (node.isBoolean()) value.put(field, node.asBoolean());
        else value.put(field, node.asText(""));
    }

    /** 硬币两面各自「叫什么、那就做什么」。抛之前就该让人看见 —— 不知道赌注是什么就不算赌。 */
    private Map<String, Object> coinFace(JsonNode face, String fallbackLabel) {
        Map<String, Object> value = new LinkedHashMap<>();
        String label = face.path("label").asText("");
        value.put("label", label.isEmpty() ? fallbackLabel : label);
        value.put("action", face.path("action").asText(""));
        return value;
    }

    private Map<String, Object> playKitRuntime(JsonNode config, ObjectNode state,
            GameRuntimeSession session, Long actorMemberId) {
        Map<String, Object> kit = new LinkedHashMap<>();

        if(enabled(config.path("album"))){Map<String,Object> album=new LinkedHashMap<>();album.put("images",objectMapper.convertValue(state.path("album").has("images")?state.path("album").path("images"):config.path("album").path("images").isArray()?config.path("album").path("images"):objectMapper.createArrayNode(),List.class));album.put("done",state.path("album").path("done").asBoolean());kit.put("album",album);}
        if (enabled(config.path("timeWindow"))) {
            JsonNode cfg = config.path("timeWindow");
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("openFrom", cfg.path("openFrom").asText(""));
            value.put("openTo", cfg.path("openTo").asText(""));
            putText(value, "eyebrow", cfg);
            putText(value, "title", cfg);
            putText(value, "subscribeTmplId", cfg);
            // 订过没有要回带:没这一句,玩家每次重进这一屏都要被"开启提醒"再问一遍
            value.put("subscribed", state.path("timeWindow").path("subscribed").asBoolean(false));
            kit.put("timeWindow", value);
        }
        if (enabled(config.path("blindTaste"))) {
            JsonNode cfg = config.path("blindTaste");
            Map<String, Object> value = new LinkedHashMap<>();
            putText(value, "title", cfg);
            putText(value, "steps", cfg);
            putText(value, "hint", cfg);
            value.put("xp", cfg.path("xp").asInt(0));
            List<Map<String, Object>> options = new ArrayList<>();
            for (JsonNode option : cfg.path("options")) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", option.path("key").asText(""));
                item.put("label", option.path("label").asText(""));
                options.add(item);
            }
            value.put("options", options);
            value.put("attempts", state.path("blindTaste").path("attempts").asInt(0));
            value.put("solved", state.path("blindTaste").path("solved").asBoolean(false));
            value.put("lastKey", state.path("blindTaste").path("lastKey").asText(""));
            kit.put("blindTaste", value);
        }
        if (enabled(config.path("silentOrder"))) {
            JsonNode cfg = config.path("silentOrder");
            Map<String, Object> value = new LinkedHashMap<>();
            putText(value, "title", cfg);
            putText(value, "rule", cfg);
            value.put("limitSeconds", cfg.path("limitSeconds").asInt(0));
            kit.put("silentOrder", value);
        }
        if (enabled(config.path("diyName"))) {
            JsonNode cfg = config.path("diyName");
            Map<String, Object> value = new LinkedHashMap<>();
            putText(value, "title", cfg);
            value.put("maxLength", cfg.path("maxLength").asInt(16));
            List<String> suggestions = new ArrayList<>();
            for (JsonNode item : cfg.path("suggestions")) {
                if (item.isTextual()) suggestions.add(item.asText());
            }
            value.put("suggestions", suggestions);
            value.put("name", state.path("diyName").path("name").asText(""));
            kit.put("diyName", value);
        }
        if (enabled(config.path("musicCorner"))) {
            JsonNode cfg = config.path("musicCorner");
            Map<String, Object> value = new LinkedHashMap<>();
            putText(value, "title", cfg);
            putText(value, "trackName", cfg);
            putText(value, "audioUrl", cfg);
            value.put("durationSeconds", cfg.path("durationSeconds").asInt(0));
            kit.put("musicCorner", value);
        }
        if (config.path("backgroundMusic").path("audioUrl").isTextual()) {
            JsonNode cfg = config.path("backgroundMusic");
            Map<String, Object> value = new LinkedHashMap<>();
            putText(value, "title", cfg);
            putText(value, "audioUrl", cfg);
            value.put("loop", cfg.path("loop").asBoolean(true));
            kit.put("backgroundMusic", value);
        }
        if (enabled(config.path("steps"))) {
            JsonNode cfg = config.path("steps");
            Map<String, Object> value = new LinkedHashMap<>();
            putText(value, "eyebrow", cfg);
            value.put("goal", cfg.path("goal").asInt(0));
            value.put("xp", cfg.path("xp").asInt(0));
            value.put("todaySteps", state.path("steps").path("todaySteps").asInt(0));
            value.put("reached", state.path("steps").path("reached").asBoolean(false));
            kit.put("steps", value);
        }
        if (enabled(config.path("slowTask"))) {
            JsonNode cfg = config.path("slowTask");
            ObjectNode st = state.with("slowTask");
            Map<String, Object> value = new LinkedHashMap<>();
            putText(value, "title", cfg);
            putText(value, "startLabel", cfg);
            putText(value, "waitHint", cfg);
            putText(value, "unlockLabel", cfg);
            int waitDays = cfg.path("waitDays").asInt(1);
            value.put("waitDays", waitDays);
            String startedDate = st.path("startedDate").asText("");
            boolean claimed = st.path("claimed").asBoolean(false);
            value.put("started", !startedDate.isEmpty());
            value.put("claimed", claimed);
            long elapsed = startedDate.isEmpty() ? 0 : daysBetween(startedDate, dayKey(clock.now()));
            value.put("daysLeft", startedDate.isEmpty() ? waitDays : Math.max(0, waitDays - elapsed));
            // ★ unlockText 只在真的领了之后才下发 —— 提前给等于把"明天的发现"今天就送出去,
            //   那这个玩法的全部意义就没了
            if (claimed) putText(value, "unlockText", cfg);
            kit.put("slowTask", value);
        }
        if (enabled(config.path("dailySign"))) {
            JsonNode cfg = config.path("dailySign");
            Map<String, Object> value = new LinkedHashMap<>();
            putText(value, "signer", cfg);
            putText(value, "sealText", cfg);
            value.put("claimedDate", state.path("dailySign").path("claimedDate").asText(""));
            List<String> lines = new ArrayList<>();
            for (JsonNode line : state.path("dailySign").path("lines")) lines.add(line.asText(""));
            value.put("lines", lines);   // 只有已领的那一条,不是整池
            kit.put("dailySign", value);
        }
        if (enabled(config.path("coinFlip"))) {
            JsonNode cfg = config.path("coinFlip");
            JsonNode st = state.path("coinFlip");
            Map<String, Object> value = new LinkedHashMap<>();
            putText(value, "kicker", cfg);
            // 两面各是什么、要做什么,抛之前就该让人看见 —— 不知道赌注是什么就不算赌
            value.put("heads", coinFace(cfg.path("heads"), "正面"));
            value.put("tails", coinFace(cfg.path("tails"), "反面"));
            value.put("xp", cfg.path("xp").asInt(0));
            value.put("flipped", st.path("flipped").asBoolean(false));
            value.put("side", st.path("side").asText(""));
            value.put("action", st.path("action").asText(""));
            value.put("awarded", st.path("awarded").asBoolean(false));
            kit.put("coinFlip", value);
        }
        if (enabled(config.path("diceRoll"))) {
            JsonNode cfg = config.path("diceRoll");
            JsonNode st = state.path("diceRoll");
            Map<String, Object> value = new LinkedHashMap<>();
            putText(value, "kicker", cfg);
            value.put("diceCount", cfg.path("diceCount").asInt(1) == 2 ? 2 : 1);
            List<String> faces = new ArrayList<>();
            for (JsonNode face : cfg.path("faces")) faces.add(face.isObject()?face.path("action").asText(""):face.asText(""));
            value.put("faces", faces);
            value.put("xp", cfg.path("xp").asInt(0));
            value.put("rolled", st.path("rolled").asBoolean(false));
            List<Integer> pips = new ArrayList<>();
            for (JsonNode pip : st.path("pips")) pips.add(pip.asInt());
            value.put("pips", pips);
            value.put("sum", st.path("sum").asInt(0));
            value.put("action", st.path("action").asText(""));
            value.put("awarded", st.path("awarded").asBoolean(false));
            kit.put("diceRoll", value);
            if ("d20".equals(cfg.path("mode").asText())) {
                value.put("mode", "d20");
                value.put("dc", cfg.path("dc").asInt());
                value.put("modifier", cfg.path("modifier").asInt());
                value.put("rollMode", cfg.path("rollMode").asText());
                value.remove("faces");
                value.remove("sum");
                if (st.path("rolled").asBoolean(false)) {
                    value.put("kept", st.path("kept").asInt());
                    value.put("total", st.path("total").asInt());
                    value.put("success", st.path("success").asBoolean());
                }
            }
        }
        /* 猜数字:题面、单位、量程要给(不给量程滚筒转不出范围),**答案与容差不给** ——
           容差能反推出答案区间,给了等于把答案分两次告诉玩家。 */
        if (enabled(config.path("estimate"))) {
            JsonNode cfg = config.path("estimate");
            JsonNode st = state.path("estimate");
            Map<String, Object> value = new LinkedHashMap<>();
            putText(value, "question", cfg);
            putText(value, "unit", cfg);
            value.put("min", cfg.path("min").asDouble(0d));
            value.put("max", cfg.path("max").asDouble(0d));
            value.put("maxAttempts", cfg.path("maxAttempts").asInt(0));
            value.put("attempts", st.path("attempts").asInt(0));
            value.put("submitted", st.path("submitted").asBoolean(false));
            value.put("tier", st.path("tier").asText(""));
            kit.put("estimate", value);
        }
        /* 找东西:图要给,**热区坐标一个都不给** —— 坐标就是这个玩法唯一的防线。
           已经点中的那几个可以给,那是玩家自己找出来的。 */
        if (enabled(config.path("hiddenObject"))) {
            JsonNode cfg = config.path("hiddenObject");
            JsonNode st = state.path("hiddenObject");
            Map<String, Object> value = new LinkedHashMap<>();
            putText(value, "title", cfg);
            putText(value, "imageUrl", cfg);
            value.put("total", cfg.path("hotspots").size());
            List<String> found = new ArrayList<>();
            for (JsonNode one : st.path("foundIds")) found.add(one.asText(""));
            value.put("foundIds", found);
            List<Map<String, Object>> targets = new ArrayList<>();
            for (JsonNode spot : cfg.path("hotspots")) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", spot.path("id").asText(""));
                // 只给「要找什么」,不给「在哪儿」
                item.put("label", spot.path("label").asText(""));
                targets.add(item);
            }
            value.put("targets", targets);
            // 限次:总次数给客户端画点数,已错几次和有没有用完由服务端说了算
            value.put("maxTries", cfg.path("maxTries").asInt(0));
            value.put("missCount", st.path("missCount").asInt(0));
            value.put("exhausted", st.path("exhausted").asBoolean(false));
            kit.put("hiddenObject", value);
        }
        /* 猜图:几张图都要给(不给图没法挑),**哪一张是对的不给** ——
           下发 correct 等于把答案写在网络面板里。 */
        if (enabled(config.path("pricePair"))) {
            JsonNode cfg = config.path("pricePair");
            JsonNode st = state.path("pricePair");
            Map<String, Object> value = new LinkedHashMap<>();
            putText(value, "title", cfg);
            List<Map<String, Object>> items = new ArrayList<>();
            for (JsonNode item : cfg.path("items")) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("id", item.path("id").asText(""));
                one.put("name", item.path("name").asText(""));
                one.put("imageUrl", item.path("imageUrl").asText(""));
                items.add(one);
            }
            value.put("items", items);
            value.put("maxTries", cfg.path("maxTries").asInt(0));
            value.put("attempts", st.path("attempts").asInt(0));
            value.put("finished", st.path("finished").asBoolean(false));
            value.put("passed", st.path("passed").asBoolean(false));
            value.put("lastPickId", st.path("lastPickId").asText(""));
            // 答案只在判完之后给
            if (st.has("answerId")) value.put("answerId", st.path("answerId").asText(""));
            kit.put("pricePair", value);
        }
        /* 问答:题面、附件、选项文案要给,**答案不给** ——
           打字题的 answerText、选项题的 correct 一个字都不下发。 */
        if (enabled(config.path("qa"))) {
            JsonNode cfg = config.path("qa");
            JsonNode st = state.path("qa");
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("mode", cfg.path("mode").asText("TYPE"));
            // 多选标记只在开启时给:老单选配置的视图形状不变,客户端缺省按单选渲染
            if (cfg.path("multi").asBoolean(false)) value.put("multi", true);
            putText(value, "title", cfg);
            putText(value, "lead", cfg);
            // 拍照打卡的补充说明:提示词摆屏幕正中,这一句小字在它下面
            putText(value, "shotNote", cfg);
            putText(value, "imageUrl", cfg);
            putText(value, "audioUrl", cfg);
            List<Map<String, Object>> options = new ArrayList<>();
            for (JsonNode option : cfg.path("options")) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", option.path("id").asText(""));
                item.put("label", option.path("label").asText(""));
                options.add(item);
            }
            value.put("options", options);
            value.put("maxTries", cfg.path("maxTries").asInt(0));
            value.put("xp", cfg.path("xp").asInt(0));
            value.put("attempts", st.path("attempts").asInt(0));
            value.put("finished", st.path("finished").asBoolean(false));
            value.put("passed", st.path("passed").asBoolean(false));
            value.put("lastCorrect", st.path("lastCorrect").asBoolean(false));
            putText(value, "lastFeedback", st);
            // 判断答案对错只在关闭时下发(旧配置视图形状不变);作答后回读所选选项与它的反馈图片
            if (!cfg.path("judge").asBoolean(true)) value.put("judge", false);
            putText(value, "lastOptionId", st);
            if (st.has("lastFeedbackImages")) {
                List<String> images = new ArrayList<>();
                for (JsonNode one : st.path("lastFeedbackImages")) images.add(one.asText(""));
                value.put("lastFeedbackImages", images);
            }
            // 答案只在判完(且商家同意亮)之后才有这些字段;不判断对错时保留的答案设置一律不下发
            boolean judged = cfg.path("judge").asBoolean(true);
            if (judged && st.has("answerId")) value.put("answerId", st.path("answerId").asText(""));
            if (judged && st.has("answerIds")) {
                List<String> answerIds = new ArrayList<>();
                for (JsonNode one : st.path("answerIds")) answerIds.add(one.asText(""));
                value.put("answerIds", answerIds);
            }
            if (st.has("lastOptionIds")) {
                List<String> lastIds = new ArrayList<>();
                for (JsonNode one : st.path("lastOptionIds")) lastIds.add(one.asText(""));
                value.put("lastOptionIds", lastIds);
            }
            kit.put("qa", value);
        }
        /* 扫码:**回复内容扫完才给**。扫之前就下发,那句话在网络面板里就能看见,
           这一站的「到店」也就没有意义了。 */
        if (enabled(config.path("scan"))) {
            JsonNode cfg = config.path("scan");
            JsonNode st = state.path("scan");
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("kind", cfg.path("kind").asText("TEXT"));
            value.put("xp", cfg.path("xp").asInt(0));
            boolean scanned = st.path("scanned").asBoolean(false);
            value.put("scanned", scanned);
            // C1:比对的是节点静态码 —— 只证明「任务码匹配」,不是有时效、一次消费的到场凭证
            value.put("evidence", "TASK_CODE_MATCH");
            value.put("rewardCeiling", RewardPolicy.XP_BADGE_ONLY.name());
            if (scanned) {
                putText(value, "reply", cfg);
                putText(value, "audioUrl", cfg);
                putText(value, "imageUrl", cfg);
                // OVERLAY 的叠加图与 imageUrl 同一条口径:扫之前不下发,网络面板里搜不到。
                // 占比只在真有叠加图时跟着走 —— 老三档的回包形状一个字不变。
                putText(value, "overlayUrl", cfg);
                if (value.containsKey("overlayUrl")) {
                    value.put("overlayScale", cfg.path("overlayScale").asInt(60));
                    // AR 方式与识别图:跟叠加图同一条扫前保密口径。存量显形配置没有 arMode,明确回 NONE,
                    // 客户端照旧走屏幕叠加,不去试一个没配过的 AR。
                    String arMode = cfg.path("arMode").asText("");
                    value.put("arMode", arMode.isEmpty() ? "NONE" : arMode);
                    putText(value, "markerUrl", cfg);
                    putText(value, "modelUrl", cfg);   // 3D 模型:同一条扫前保密口径
                }
            }
            kit.put("scan", value);
        }
        /* R3 排序题:题面(打乱顺序的 items)要给,answerOrder 一个字不给 ——
           顺序一旦下发,这一屏就只剩照着摆。 */
        if (enabled(config.path("sort"))) {
            JsonNode cfg = config.path("sort");
            JsonNode st = state.path("sort");
            Map<String, Object> value = new LinkedHashMap<>();
            putText(value, "prompt", cfg);
            value.put("maxAttempts", cfg.path("maxAttempts").asInt(0));
            List<Map<String, Object>> items = new ArrayList<>();
            for (JsonNode item : AdvancedGamePublicProjection.displayOrder(cfg.path("items"))) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("id", item.path("id").asText(""));
                one.put("label", item.path("label").asText(""));
                putText(one, "img", item);
                items.add(one);
            }
            value.put("items", items);
            value.put("attempts", st.path("attempts").asInt(0));
            value.put("finished", st.path("finished").asBoolean(false));
            value.put("passed", st.path("passed").asBoolean(false));
            value.put("lastCorrect", st.path("lastCorrect").asBoolean(false));
            List<String> lastOrder = new ArrayList<>();
            for (JsonNode one : st.path("lastOrder")) lastOrder.add(one.asText(""));
            value.put("lastOrder", lastOrder);
            kit.put("sort", value);
        }
        /* R3 连线题:左右两组要给,右侧打乱;pairs 一个字不给 —— 哪条连哪条正是要连的。 */
        if (enabled(config.path("match"))) {
            JsonNode cfg = config.path("match");
            JsonNode st = state.path("match");
            Map<String, Object> value = new LinkedHashMap<>();
            putText(value, "prompt", cfg);
            List<Map<String, Object>> left = new ArrayList<>();
            for (JsonNode item : cfg.path("left")) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("id", item.path("id").asText(""));
                one.put("label", item.path("label").asText(""));
                left.add(one);
            }
            value.put("left", left);
            List<Map<String, Object>> right = new ArrayList<>();
            for (JsonNode item : AdvancedGamePublicProjection.displayOrder(cfg.path("right"))) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("id", item.path("id").asText(""));
                one.put("label", item.path("label").asText(""));
                right.add(one);
            }
            value.put("right", right);
            value.put("attempts", st.path("attempts").asInt(0));
            value.put("finished", st.path("finished").asBoolean(false));
            value.put("passed", st.path("passed").asBoolean(false));
            value.put("lastCorrect", st.path("lastCorrect").asBoolean(false));
            kit.put("match", value);
        }
        /* R3 分类题:分类箱与卡片要给,answer(哪张进哪个箱)一个字不给。 */
        if (enabled(config.path("classify"))) {
            JsonNode cfg = config.path("classify");
            JsonNode st = state.path("classify");
            Map<String, Object> value = new LinkedHashMap<>();
            putText(value, "prompt", cfg);
            List<Map<String, Object>> bins = new ArrayList<>();
            for (JsonNode bin : cfg.path("bins")) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("id", bin.path("id").asText(""));
                one.put("label", bin.path("label").asText(""));
                bins.add(one);
            }
            value.put("bins", bins);
            List<Map<String, Object>> items = new ArrayList<>();
            for (JsonNode item : cfg.path("items")) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("id", item.path("id").asText(""));
                one.put("label", item.path("label").asText(""));
                items.add(one);
            }
            value.put("items", items);
            value.put("attempts", st.path("attempts").asInt(0));
            value.put("finished", st.path("finished").asBoolean(false));
            value.put("passed", st.path("passed").asBoolean(false));
            value.put("lastCorrect", st.path("lastCorrect").asBoolean(false));
            kit.put("classify", value);
        }
        /* R16 对照题:两版题面原样要给(时间轴顺序就是题面),answer 一个字不给 ——
           标出哪几条正是这一屏要找的。回执只给整体对错与剩余次数,不给逐条对错。 */
        if (enabled(config.path("compare"))) {
            JsonNode cfg = config.path("compare");
            JsonNode st = state.path("compare");
            Map<String, Object> value = new LinkedHashMap<>();
            putText(value, "prompt", cfg);
            int maxAttempts = cfg.path("maxAttempts").asInt(0);
            value.put("maxAttempts", maxAttempts);
            value.put("left", compareSideView(cfg.path("left")));
            value.put("right", compareSideView(cfg.path("right")));
            int attempts = st.path("attempts").asInt(0);
            value.put("attempts", attempts);
            // 剩余次数只在上限真的存在时给(0 = 不限,与排序题同一口径)
            if (maxAttempts > 0) value.put("remainingAttempts", Math.max(0, maxAttempts - attempts));
            value.put("finished", st.path("finished").asBoolean(false));
            value.put("passed", st.path("passed").asBoolean(false));
            value.put("lastCorrect", st.path("lastCorrect").asBoolean(false));
            List<String> lastMarked = new ArrayList<>();
            for (JsonNode one : st.path("lastMarked")) lastMarked.add(one.asText(""));
            value.put("lastMarked", lastMarked);
            kit.put("compare", value);
        }
        /* 分支剧情:只给**当前这一步**。整棵树下发等于把所有结局提前剧透,
           而 nextStepId / score 更是「选了会去哪、值多少分」—— 那正是玩家要猜的东西。 */
        if (enabled(config.path("branch"))) {
            Map<String, Object> value = new LinkedHashMap<>();
            Map<String, Object> current = publicBranch(config.path("branch"), state.path("branch"));
            if (current != null && current.get("currentStep") != null) {
                value.put("currentStep", current.get("currentStep"));
            }
            kit.put("branch", value);
        }
        /* 盲盒:**一件都不下发**,只给「一共能抽几次」和「已经抽到的是什么」。
           把 items 发下去这个玩法就没了 —— 抽之前就能看见盒子里有什么。 */
        if (enabled(config.path("random"))) {
            JsonNode cfg = config.path("random");
            Map<String, Object> value = new LinkedHashMap<>();
            // 卡池名(原型「这副卡叫什么」)。不下发的话玩家那一屏顶上恒空
            value.put("deckName", cfg.path("deckName").asText(""));
            value.put("drawCount", cfg.path("drawCount").asInt(0));
            List<Map<String, Object>> drawn = new ArrayList<>();
            for (JsonNode draw : state.path("draws")) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", draw.path("id").asText(""));
                item.put("label", draw.path("label").asText(""));
                item.put("content", draw.path("content").asText(""));
                drawn.add(item);
            }
            value.put("drawn", drawn);
            kit.put("random", value);
        }
        /* 竞猜:题面和选项要给(不知道押什么就没法押),答案**此刻还不存在** ——
           它由商家事后给。押完之后的揭晓信息在 toView 里补,那儿才拿得到 nodeId。 */
        if (enabled(config.path("predict"))) {
            JsonNode cfg = config.path("predict");
            JsonNode st = state.path("predict");
            Map<String, Object> value = new LinkedHashMap<>();
            putText(value, "question", cfg);
            putText(value, "hint", cfg);
            List<Map<String, Object>> options = new ArrayList<>();
            for (JsonNode option : cfg.path("options")) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", option.path("key").asText(""));
                item.put("label", option.path("label").asText(""));
                options.add(item);
            }
            value.put("options", options);
            value.put("closeAtHour", cfg.path("closeAtHour").asInt(23));
            // 什么时候由商家公布答案。相对天数 + 整点,玩家押完那一屏要把它说出来
            value.put("revealDays", cfg.path("revealDays").asInt(1));
            value.put("revealHour", cfg.path("revealHour").asInt(20));
            value.put("xp", cfg.path("xp").asInt(0));
            value.put("myOptionKey", st.path("optionKey").asText(""));
            value.put("playDay", st.path("playDay").asText(""));
            kit.put("predict", value);
        }
        /* 预制人生·建档:问题与选项文案要给,★ options[].effects 一个都不给 ——
           数值加成是暗的,露出来就成了攻略表。 */
        if (enabled(config.path("profile"))) {
            JsonNode cfg = config.path("profile");
            JsonNode st = state.path("profile");
            Map<String, Object> value = new LinkedHashMap<>();
            putText(value, "title", cfg);
            putText(value, "lead", cfg);
            JsonNode avatar = cfg.get("avatar");
            if (avatar != null && avatar.isObject()) {
                Map<String, Object> avatarView = new LinkedHashMap<>();
                boolean avatarOn = avatar.path("enabled").asBoolean(false);
                avatarView.put("enabled", avatarOn);
                avatarView.put("required", avatarOn && avatar.path("required").asBoolean(false));
                value.put("avatar", avatarView);
            }
            List<Map<String, Object>> questions = new ArrayList<>();
            for (JsonNode question : cfg.path("questions")) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", question.path("key").asText(""));
                item.put("label", question.path("label").asText(""));
                String kind = question.path("kind").asText("");
                item.put("kind", kind);
                item.put("required", true);
                if ("text".equals(kind)) item.put("maxLength", question.path("maxLength").asInt(12));
                List<Map<String, Object>> options = new ArrayList<>();
                for (JsonNode option : question.path("options")) {
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("key", option.path("key").asText(""));
                    one.put("label", option.path("label").asText(""));
                    options.add(one);
                }
                if (!options.isEmpty()) item.put("options", options);
                questions.add(item);
            }
            value.put("questions", questions);
            Map<String, Object> answers = new LinkedHashMap<>();
            if (st.path("answers").isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = st.path("answers").fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    answers.put(entry.getKey(), entry.getValue().asText(""));
                }
            }
            value.put("answers", answers);
            putText(value, "avatarUrl", st);
            value.put("done", st.path("done").asBoolean(false));
            // 咔哒只在发生了之后下发(改写前的条件与改写目标不给,给了就是剧透)
            if (st.path("overrides").isArray() && st.path("overrides").size() > 0) {
                value.put("overrides", objectMapper.convertValue(st.path("overrides"), List.class));
            }
            kit.put("profile", value);
        }
        /* 预制人生·拍照审核:题面 + requirement(要拍到什么)要给,判定与计次在服务端。
           lastReason 必须下发 —— 没过的时候得告诉玩家为什么。
           flagged(被放过/降级的粘性痕迹)与 degraded(最近一次是否降级)都要给,客户端才演得对。 */
        if (enabled(config.path("photoCheck"))) {
            JsonNode cfg = config.path("photoCheck");
            JsonNode st = state.path("photoCheck");
            Map<String, Object> value = new LinkedHashMap<>();
            putText(value, "title", cfg);
            putText(value, "shotNote", cfg);
            putText(value, "requirement", cfg);
            /* 取景轮廓是题面不是答案:与 requirement 同档、开局就下发(玩家得先看见框才对得上)。
               ⚠️ 没配 frameUrl 时这两个键一个都不出现 —— 「商家没配 = 下发形状与今天逐字相同」是契约口径。 */
            String frameUrl = cfg.path("frameUrl").asText("").trim();
            if (!frameUrl.isEmpty()) {
                value.put("frameUrl", frameUrl);
                value.put("frameOpacity", normalizeFrameOpacity(cfg.path("frameOpacity").asInt(DEFAULT_FRAME_OPACITY)));
            }
            value.put("minConfidence", cfg.path("minConfidence").asInt(60));
            value.put("maxTries", cfg.path("maxTries").asInt(0));
            value.put("fallback", cfg.path("fallback").asText("retake"));
            /* 拍物成卡(立体藏品卡契约 §2.4):这三个键必须投影 —— 漏一个,组件收到空值、
               静默退回普通拍照审核,不报错也不打日志。putText 只写非空,所以老模板(mode 空)
               的回包与今天逐字相同。cardStyle 的默认值由客户端兜(foil),两边口径一致。 */
            putText(value, "mode", cfg);
            putText(value, "cardTitle", cfg);
            putText(value, "cardStyle", cfg);
            value.put("xp", cfg.path("xp").asInt(0));
            value.put("tries", st.path("tries").asInt(0));
            value.put("passed", st.path("passed").asBoolean(false));
            value.put("flagged", st.path("flagged").asBoolean(false));
            value.put("degraded", st.path("degraded").asBoolean(false));
            putText(value, "lastUrl", st);
            putText(value, "lastReason", st);
            value.put("lastConfidence", st.path("lastConfidence").asInt(0));
            // C1:只有视觉模型当场真判 PASS 才算「照片已核验」;降级与次数用尽兜底放行不算。
            // 没过之前不给 rewardCeiling —— 还没有结果,不承诺也不预判
            String verdict = st.path("verdict").asText("");
            if (!verdict.isEmpty()) value.put("verdict", verdict);
            boolean verified = photoVerified(st);
            value.put("photoVerified", verified);
            if (st.path("passed").asBoolean(false)) {
                value.put("rewardCeiling", (verified ? RewardPolicy.FULL : RewardPolicy.XP_BADGE_ONLY).name());
            }
            kit.put("photoCheck", value);
        }
        /* 预制人生·留言:题面 + 后来的人留下的前几条(previous)+ 我自己那句(mine)。
           跨玩家存储不在会话里,这里现查 play_note。 */
        if (enabled(config.path("note"))) {
            JsonNode cfg = config.path("note");
            JsonNode st = state.path("note");
            Map<String, Object> value = new LinkedHashMap<>();
            putText(value, "title", cfg);
            putText(value, "prompt", cfg);
            value.put("maxLength", cfg.path("maxLength").asInt(40));
            List<String> presets = new ArrayList<>();
            for (JsonNode preset : cfg.path("presets")) {
                if (preset.isTextual()) presets.add(preset.asText());
            }
            value.put("presets", presets);
            int showPrevious = cfg.path("showPrevious").asInt(0);
            List<Map<String, Object>> previous = new ArrayList<>();
            Map<String, Object> mine = null;
            if (playNoteMapper != null && session != null && session.getNodeId() != null) {
                List<PlayNote> recent = playNoteMapper.selectRecentByNode(
                        session.getNodeId(), Math.max(20, showPrevious + 1));
                if (recent != null) {
                    for (PlayNote row : recent) {
                        boolean own = actorMemberId != null && actorMemberId.equals(row.getMemberId());
                        if (own) {
                            if (mine == null) mine = noteView(row);
                            continue;
                        }
                        if (previous.size() < showPrevious) previous.add(noteView(row));
                    }
                }
            }
            value.put("previous", previous);
            if (mine != null) value.put("mine", mine);
            value.put("done", st.path("done").asBoolean(false) || mine != null);
            kit.put("note", value);
        }
        /* 预制人生·限时打字:target 就是题面,必须给;判定(内容相等 + 用时)在服务端。 */
        if (enabled(config.path("typeIn"))) {
            JsonNode cfg = config.path("typeIn");
            JsonNode st = state.path("typeIn");
            Map<String, Object> value = new LinkedHashMap<>();
            putText(value, "title", cfg);
            value.put("target", cfg.path("target").asText(""));
            value.put("seconds", cfg.path("seconds").asInt(0));
            value.put("caseSensitive", cfg.path("caseSensitive").asBoolean(false));
            value.put("tries", cfg.path("tries").asInt(0));
            value.put("terminalSkin", cfg.path("terminalSkin").asBoolean(false));   // 终端样式,纯外观
            value.put("attempts", st.path("attempts").asInt(0));
            value.put("passed", st.path("passed").asBoolean(false));
            value.put("started", st.path("startedAtMs").asLong(0L) > 0L);
            value.put("submitted", st.path("submitted").asBoolean(false));
            kit.put("typeIn", value);
        }
        /* 罗盘指向:目标方位**就是题面**,开局即下发 —— 玩家得先知道往哪转才谈得上对准。
           这与扫码的显形图相反(那是答案,扫中之前一个字都不许出现在回包里)。
           ⚠️ 这一段只发 xp,永远不许挂到店凭证或有金额的完成条件(现场感契约 §3.3)。 */
        if (enabled(config.path("compass"))) {
            JsonNode cfg = config.path("compass");
            Map<String, Object> value = new LinkedHashMap<>();
            putText(value, "kicker", cfg);
            value.put("bearing", cfg.path("bearing").asInt(0));
            value.put("tolerance", cfg.path("tolerance").asInt(15));
            value.put("holdSeconds", cfg.path("holdSeconds").asInt(3));
            putText(value, "hint", cfg);
            value.put("xp", cfg.path("xp").asInt(0));
            value.put("done", state.path("compass").path("done").asBoolean(false));
            putAttempt(value, state, "compass", actorMemberId);
            value.put("evidence", "SELF_REPORTED");
            value.put("rewardCeiling", RewardPolicy.XP_BADGE_ONLY.name());
            kit.put("compass", value);
        }
        putChallenge(kit, config, state, "reaction", "goalMs", "rounds", actorMemberId);
        putChallenge(kit, config, state, "ballShake", "goal", "seconds", actorMemberId);
        putChallenge(kit, config, state, "quietHold", "seconds", null, actorMemberId);
        putChallenge(kit, config, state, "shout", "seconds", null, actorMemberId);
        putChallenge(kit, config, state, "countdown", "seconds", "doneText", actorMemberId);
        putChallenge(kit, config, state, "stopwatch", "targetSeconds", "toleranceMs", actorMemberId);
        return kit;
    }

    /** 留言一行:对玩家只露「哪句话、什么时候」,memberId 不出服务端。 */
    private Map<String, Object> noteView(PlayNote row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("text", row.getContent());
        item.put("at", row.getCreateTime() == null ? null : row.getCreateTime().getTime());
        return item;
    }

    /** 只有非空文本才进视图:空串会让前端把"没配"渲染成一行空白 */
    private void putText(Map<String, Object> target, String field, JsonNode source) {
        String value = source.path(field).asText("");
        if (!value.isEmpty()) target.put(field, value);
    }

    /** 取景轮廓不透明度：越界夹回 0–100。存量的历史配置没走过新校验，这里兜一层。 */
    private int normalizeFrameOpacity(int opacity) {
        if (opacity < 0) return 0;
        return Math.min(opacity, MAX_FRAME_OPACITY);
    }

    /** 对照题单侧的运行期视图:标题 + 时间轴条目,顺序原样。 */
    private Map<String, Object> compareSideView(JsonNode side) {
        Map<String, Object> view = new LinkedHashMap<>();
        putText(view, "label", side);
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode item : side.path("items")) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", item.path("id").asText(""));
            one.put("time", item.path("time").asText(""));
            one.put("text", item.path("text").asText(""));
            items.add(one);
        }
        view.put("items", items);
        return view;
    }

    private boolean zeroRetryAvailable(GameRuntimeSession session, Long memberId) {
        ObjectNode old = objectState(session.getStateJson());
        if (old.has("retrySessionId")) return false;
        JsonNode shared = gameSessionRuntimeService == null ? null : gameSessionRuntimeService.sharedRouteSnapshot(memberId,
                session.getTopicId(), session.getActivityId(), session.getNodeId());
        JsonNode challenge;
        if (session.getOwnerType() == GameRuntimeSession.OWNER_TEAM) {
            if (shared == null || !session.getOwnerId().equals(longValue(shared.get("teamId")))
                    || !(Objects.equals(longValue(old.get("originalGameSessionId")), longValue(shared.get("gameSessionId")))
                         || !old.has("originalGameSessionId") && session.getPlayInstanceId().startsWith("game:" + shared.path("gameSessionId").asLong() + ":challenge:"))
                    || !Objects.equals(runtimeEpoch(session), Integer.valueOf(shared.path("authorityEpoch").asInt()))) return false;
            challenge = shared.get("challenge");
        } else {
            if (shared != null || topicRouteRuntimeService == null) return false;
            Long currentRoute = topicRouteRuntimeService.gameplaySessionId(memberId, session.getTopicId(), session.getActivityId());
            if (currentRoute == null || currentRoute <= 0) return false;
            if (!Objects.equals(longValue(old.get("originalRouteSessionId")), currentRoute)) {
                if (old.has("originalRouteSessionId")) return false;
                GameRuntimeSession legacy = sessionMapper.selectLatestZeroChallenge(session.getActivityId(), session.getTopicId(),
                        session.getNodeId(), GameRuntimeSession.OWNER_PLAYER, memberId, null, currentRoute, null);
                if (legacy == null || !session.getId().equals(legacy.getId())) return false;
            }
            challenge = topicRouteRuntimeService.zeroChallenge(memberId, session.getTopicId(), session.getActivityId(), session.getNodeId());
        }
        return challenge != null && !challenge.path("instanceId").asText().isEmpty()
                && !old.path("zeroChallengeInstance").asText().equals(challenge.path("instanceId").asText())
                && challenge.path("disabledReason").asText().isEmpty() && !challenge.path("success").asBoolean()
                && (!challenge.has("deadlineAt") || clock.now().getTime() < challenge.path("deadlineAt").asLong());
    }

    private Map<String, Object> retryZeroChallenge(GameRuntimeSession source, Long memberId, Integer expectedVersion,
            String idempotencyKey, JsonNode shared) {
        if (source.getOwnerType() == GameRuntimeSession.OWNER_TEAM) {
            PlayTeam team = requireTeam(source.getOwnerId(), memberId, source.getActivityId(), true);
            if (!memberId.equals(team.getLeaderMemberId())) throw new ServiceException("只有本队队长可重试", 403);
        }
        Long sourceId = source.getId();
        source = sessionMapper.selectByIdForUpdate(sourceId);
        if (source == null) throw new IllegalStateException("恢复挑战会话不存在");
        if (expectedVersion == null || !expectedVersion.equals(source.getVersion())) {
            throw versionConflict(requireAccessible(sourceId, memberId), memberId);
        }
        if (!canRetry(source, memberId)) throw new ServiceException("本次恢复挑战不能重试", 409);
        if (shared != null) gameSessionRuntimeService.assertSharedRouteWritable(memberId, source.getTopicId(),
                source.getActivityId(), source.getNodeId(), runtimeEpoch(source));
        if (topicRouteRuntimeService != null) topicRouteRuntimeService.requireOpeningAccess(memberId,
                source.getTopicId(), source.getActivityId(), source.getNodeId());
        Map<String, Object> result = start(memberId, source.getActivityId(), source.getTopicId(), source.getNodeId(),
                source.getOwnerType() == GameRuntimeSession.OWNER_TEAM ? source.getOwnerId() : null, source);
        if (source.getId().equals(longValue(result.get("sessionId")))) throw new IllegalStateException("恢复挑战未建立新的尝试");
        ObjectNode old = objectState(source.getStateJson());
        old.put("retrySessionId", longValue(result.get("sessionId")));
        source.setStateJson(writeJson(old)); source.setUpdateTime(clock.now());
        int oldVersion = source.getVersion();
        if (sessionMapper.updateFeedbackStateCas(source) != 1) throw versionConflict(requireAccessible(source.getId(), memberId), memberId);
        source.setVersion(oldVersion + 1);
        result.put("retrySourceSessionId", source.getId());
        logAction(source, memberId, idempotencyKey, result);
        return result;
    }

    private boolean canRetry(GameRuntimeSession session, Long memberId) {
        boolean zeroContinuation = objectState(session.getStateJson()).has("zeroChallengeInstance")
                && Integer.valueOf(GameRuntimeSession.STATUS_COMPLETED).equals(session.getStatus())
                && objectState(session.getStateJson()).path("challenge").path("gameCompleted").asBoolean()
                && !objectState(session.getStateJson()).path("challenge").path("success").asBoolean();
        if (memberId == null || !(Integer.valueOf(GameRuntimeSession.STATUS_FAILED).equals(session.getStatus())
                || Integer.valueOf(GameRuntimeSession.STATUS_EXPIRED).equals(session.getStatus()) || zeroContinuation)
                || settledReadOnly(session)) return false;
        if (objectState(session.getStateJson()).has("zeroChallengeInstance")) {
            try { if (!zeroRetryAvailable(session, memberId)) return false; }
            catch (ServiceException | com.chengyinhub.business.service.GameSessionException refused) { return false; }
        }
        if (session.getOwnerType() == GameRuntimeSession.OWNER_PLAYER) return memberId.equals(session.getOwnerId());
        if (session.getOwnerType() != GameRuntimeSession.OWNER_TEAM) return false;
        PlayTeam team = teamMapper.selectPlayTeamById(session.getOwnerId());
        return team != null && Integer.valueOf(2).equals(team.getOwnerType())
                && session.getActivityId().equals(team.getOwnerId()) && team.getStatus() != null && team.getStatus() <= 2
                && memberId.equals(team.getLeaderMemberId()) && teamMapper.selectActiveMember(team.getId(), memberId) != null;
    }

    private Map<String, Object> toView(GameRuntimeSession session, Long actorMemberId) {
        ObjectNode state = objectState(session.getStateJson());

        JsonNode config = readJson(session.getConfigSnapshotJson(), "高级玩法配置不可用");
        if(config.has("outcomeRules") && enabled(config.path("branch"))){JsonNode feedback=outcomeFeedback(actorMemberId,session,state,null);if(feedback!=null)state.set("branch",feedback.deepCopy());else state.with("branch").put("writable",false);}
        Map<String, Object> view = new LinkedHashMap<>();
        if (state.path("draws").isArray()) {
            List<Map<String, Object>> draws = new ArrayList<>();
            for (JsonNode draw : state.path("draws")) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", draw.path("id").asText());
                item.put("label", draw.path("label").asText());
                item.put("content", draw.path("content").asText());
                draws.add(item);
            }
            view.put("draws", draws);
        }
        Map<String, Object> branch = publicBranch(config.path("branch"), state.path("branch"));
        if (branch != null) view.put("branch", branch);
        Map<String, Object> multiplayer = publicMultiplayer(config.path("multiplayer"),
                state.path("multiplayer"), actorMemberId);
        if (multiplayer != null) view.put("multiplayer", multiplayer);
        view.put("sessionId", session.getId());
        view.put("activityId", session.getActivityId());
        view.put("topicId", session.getTopicId());
        view.put("nodeId", session.getNodeId());
        view.put("ownerType", session.getOwnerType());
        view.put("ownerId", session.getOwnerId());
        view.put("status", statusName(session.getStatus()));
        view.put("canRetry", canRetry(session, actorMemberId));
        view.put("score", value(session.getScore()));
        view.put("completedUnits", value(session.getCompletedUnits()));
        view.put("version", value(session.getVersion()));
        view.put("startedAt", session.getStartedAt() == null ? null : session.getStartedAt().getTime());
        view.put("deadlineAt", session.getDeadlineAt() == null ? null : session.getDeadlineAt().getTime());
        view.put("completedAt", session.getCompletedAt() == null ? null : session.getCompletedAt().getTime());
        view.put("config", publicRuntimeConfig(config, state, actorMemberId));
        if (state.has("zeroChallengeInstance")) view.put("challenge", state.path("challenge").deepCopy());
        // 呈现方式(施工契约 §1.5):作者显式配的或默认表推出来的最终值。客户端只读它,
        // 不自己重推默认表(默认表只在上面的 resolvePresent 一处实现)。
        view.put("present", AdvancedGameConfigValidator.resolvePresent(config));
        Map<String, Object> playKit = playKitRuntime(config, state, session, actorMemberId);
        mergePredictResult(playKit, session, state);
        mergeBingo(playKit, session, actorMemberId);
        if (!playKit.isEmpty()) view.put("playKit", playKit);
        // 故事变量 {名字}(契约 §3.1):与 /api/play/nodes 的进度回包共用 StoryVars 同一个
        // 构造函数;没值且没兜底时由替换方保留花括号,不放空串
        Map<String, Object> vars = StoryVars.fromState(state);
        if (!vars.isEmpty()) view.put("vars", vars);
        if(config.has("outcomeRules") && enabled(config.get("check")))view.put("check",checkView(session,state,actorMemberId));
        view.put("readyForBase", state.has("zeroChallengeInstance") ? state.path("challenge").path("success").asBoolean()
                : Integer.valueOf(GameRuntimeSession.STATUS_COMPLETED).equals(session.getStatus())
                || (Integer.valueOf(GameRuntimeSession.STATUS_RUNNING).equals(session.getStatus())
                && (config.has("outcomeRules")?"PASS".equals(state.path("gameplayDecision").path("outcome").asText()):mechanicsReady(config, state))));
        return view;
    }

    private ObjectNode checkView(GameRuntimeSession session,ObjectNode state,Long memberId) {
        ObjectNode check=state.path("check").isObject()?((ObjectNode)state.path("check")).deepCopy():objectMapper.createObjectNode();
        String attemptId="advanced:"+session.getId()+":"+state.path("attemptOrdinal").asLong(1);
        check.put("attemptId",attemptId).put("disabledReason","").put("rerollRemaining",0);check.putArray("allowedActions");
        if(!Integer.valueOf(GameRuntimeSession.STATUS_RUNNING).equals(session.getStatus()) || check.path("submitted").asBoolean())return check.put("disabledReason","本次检定已提交或已结束");
        try {
            ObjectNode actions=session.getOwnerType()==GameRuntimeSession.OWNER_TEAM
                ?gameSessionRuntimeService.sharedGameplayCheckActions(memberId,session.getTopicId(),session.getActivityId(),session.getNodeId(),session.getPlayInstanceId(),attemptId,runtimeEpoch(session))
                :topicRouteRuntimeService.gameplayCheckActions(memberId,session.getTopicId(),session.getActivityId(),session.getNodeId(),session.getPlayInstanceId(),attemptId);
            if(actions!=null)check.setAll(actions);
            if(state.has("zeroChallengeInstance")) {
                ArrayNode allowed=(ArrayNode)check.get("allowedActions");
                for(int i=allowed.size()-1;i>=0;i--)if("REROLL_CHECK".equals(allowed.get(i).asText())){allowed.remove(i);check.put("disabledReason","恢复挑战不消费幸运重掷");}
            }
            for(JsonNode action:check.path("allowedActions"))if("REROLL_CHECK".equals(action.asText()))check.put("rerollRemaining",1);
        } catch(com.chengyinhub.business.service.GameSessionException | ServiceException | IllegalStateException refused) {check.put("disabledReason",refused.getMessage());}
        return check;
    }

    /**
     * 押完之后的揭晓信息。答案不在配置里也不在会话状态里 —— 它是商家事后写进
     * {@code play_predict_result} 的一行,所以只能在这儿(拿得到 nodeId)现查。
     *
     * <p>三种态都要给出去:待揭晓 / 已揭晓 / 已作废。少了「作废」那一档,商家超时没给答案的
     * 那一局在玩家屏上会永远停在「等揭晓」,而那一轮其实已经结束了、也不会发奖。
     */
    @SuppressWarnings("unchecked")
    private boolean predictRevealDue(JsonNode config,java.sql.Date day) {
        JsonNode predict=config.path("predict");long revealAt=day.toLocalDate().plusDays(predict.path("revealDays").asInt(1)).atTime(predict.path("revealHour").asInt(20),0).atZone(BUSINESS_ZONE).toInstant().toEpochMilli();
        return clock.now().getTime()>=revealAt;
    }
    private GameRuntimeSession synchronizePredictSession(GameRuntimeSession input,Long actor) {
        JsonNode config=readJson(input.getConfigSnapshotJson(),"竞猜冻结配置不可用");ObjectNode state=objectState(input.getStateJson());JsonNode stored=state.path("predict");
        if(!config.has("outcomeRules") || !enabled(config.path("predict")) || !stored.has("betId") || stored.path("revealRecorded").asBoolean())return input;
        java.sql.Date day=java.sql.Date.valueOf(stored.path("playDay").asText());if(!predictRevealDue(config,day))return input;
        com.chengyinhub.business.domain.PlayPredictResult round=predictMapper.selectResult(input.getNodeId(),day);
        if(round==null || round.getSettleStatus()==null || round.getSettleStatus()==0)return input;
        com.chengyinhub.business.domain.PlayPredictBet bet=predictMapper.selectBet(input.getNodeId(),day,stored.path("memberId").asLong());
        if(bet==null || !Objects.equals(bet.getId(),stored.path("betId").asLong()) || !Objects.equals(bet.getTopicId(),input.getTopicId()) || !Objects.equals(bet.getActivityId(),input.getActivityId()) || !Objects.equals(bet.getOptionKey(),stored.path("optionKey").asText()))throw new IllegalStateException("原竞猜下注凭证不匹配");
        if(bet.getRewardStatus()==null || bet.getRewardStatus()==0)return input;
        GameRuntimeSession session=sessionMapper.selectByIdForUpdate(input.getId());if(session==null)throw new IllegalStateException("原竞猜会话不存在");
        state=objectState(session.getStateJson());if(state.path("predict").path("revealRecorded").asBoolean())return session;
        if(state.path("predict").path("betId").asLong()!=bet.getId())return session;
        boolean running=Integer.valueOf(GameRuntimeSession.STATUS_RUNNING).equals(session.getStatus());
        ObjectNode predict=state.with("predict");predict.put("revealRecorded",true).put("settleStatus",round.getSettleStatus());
        if(round.getSettleStatus()==com.chengyinhub.business.domain.PlayPredictResult.SETTLE_VOID){predict.put("outcomeSettlement","VOID");if(running)session.setStatus(GameRuntimeSession.STATUS_EXPIRED);}
        else if(state.has("gameplayDecision"))predict.put("outcomeSettlement","FIXED_RESULT");
        else {
            String answer=round.getSettledOption();if(findOptionByKey(config.path("predict").path("options"),answer)==null)throw new IllegalStateException("真实竞猜答案不属于原冻结题目");
            ObjectNode decision=objectMapper.createObjectNode().put("nodeId",session.getNodeId()).put("outcome",answer.equals(bet.getOptionKey())?"PASS":"FAIL").put("reason","PREDICT_REVEAL");
            decision.putObject("selection").put("kit","predict").put("optionId",bet.getOptionKey());
            String attempt="advanced:"+session.getId()+":"+state.path("attemptOrdinal").asLong(1);List<String[]> choices=Collections.singletonList(new String[]{"predict",bet.getOptionKey()});boolean recorded=false;
            if(actor!=null && state.has("originalGameSessionId"))recorded=gameSessionRuntimeService.recordOriginalSharedGameplayOutcome(actor,session.getActivityId(),state.path("originalGameSessionId").asLong(),session.getOwnerId(),runtimeEpoch(session),session.getNodeId(),session.getPlayInstanceId(),attempt,decision,choices);
            else if(actor!=null && state.has("originalRouteSessionId"))recorded=topicRouteRuntimeService.recordOriginalGameplayOutcome(actor,state.path("originalRouteSessionId").asLong(),session.getNodeId(),session.getPlayInstanceId(),attempt,decision,choices);
            predict.put("outcomeSettlement",recorded?"RECORDED":"SKIPPED_CLOSED");
            if(recorded){state.set("gameplayDecision",decision);session.setStatus("PASS".equals(decision.path("outcome").asText())?GameRuntimeSession.STATUS_COMPLETED:GameRuntimeSession.STATUS_FAILED);}
            else session.setStatus(GameRuntimeSession.STATUS_EXPIRED);
        }
        int old=session.getVersion();session.setStateJson(writeJson(state));session.setUpdateTime(clock.now());
        if(running){if(sessionMapper.updateStateCas(session)!=1)throw new IllegalStateException("竞猜揭晓状态已更新，请刷新");}
        else if(sessionMapper.updateFeedbackStateCas(session)!=1)throw new IllegalStateException("竞猜揭晓状态已更新，请刷新");
        session.setVersion(old+1);return session;
    }

    private void mergePredictResult(Map<String, Object> kit, GameRuntimeSession session, ObjectNode state) {
        Object raw = kit.get("predict");
        if (!(raw instanceof Map)) return;
        Map<String, Object> predict = (Map<String, Object>) raw;
        String playDay = state.path("predict").path("playDay").asText("");
        if (playDay.isEmpty()) return;              // 还没押,就没有可揭晓的
        java.sql.Date day;
        try {
            day = java.sql.Date.valueOf(playDay);
        } catch (IllegalArgumentException e) {
            return;
        }
        com.chengyinhub.business.domain.PlayPredictResult round =
                predictMapper.selectResult(session.getNodeId(), day);
        if (round == null) return;
        JsonNode config=readJson(session.getConfigSnapshotJson(),"竞猜冻结配置不可用");
        if(config.has("outcomeRules") && (!predictRevealDue(config,day) || !state.path("predict").path("revealRecorded").asBoolean())){predict.put("settleStatus",0);return;}
        if(config.has("outcomeRules"))predict.put("outcomeSettlement",state.path("predict").path("outcomeSettlement").asText());
        int status = round.getSettleStatus() == null
                ? com.chengyinhub.business.domain.PlayPredictResult.SETTLE_PENDING
                : round.getSettleStatus();
        predict.put("settleStatus", status);
        if (status == com.chengyinhub.business.domain.PlayPredictResult.SETTLE_DONE) {
            String answer = round.getSettledOption();
            predict.put("settledOption", answer == null ? "" : answer);
            predict.put("won", answer != null && answer.equals(predict.get("myOptionKey")));
        }
    }

    /**
     * 主题级九宫格(Figma 原型里的「走到一处点亮一格,连成线换奖」)。
     *
     * <p>★ 这一段是**主题级进度的呈现**,不是这一站的任务:点亮发生在节点完成那条链路
     * (ApiPlayProgressController#refreshBingo),格子的定义冻在开卡那一刻的快照列里。
     * 它<b>绝不参与</b> {@code readyForBase} / {@code mechanicsReady} —— 通关判定只看
     * 本节点的玩法配置与会话状态,九格亮满也不该把任何一站判成做完。
     *
     * <p>★ 整段包在 try-catch 里,与节点完成那条链路同一条纪律:九宫格读挂了
     * 只是不下发这一段,不能把玩家的玩法整屏带走 —— 那才是真的把他卡在这一站。
     *
     * <p>会员维度用 {@code actorMemberId} 而不是会话 owner:卡是「一人一主题一张」,
     * 队伍会话里每个队员看九格,看的也是自己那张。
     */
    private void mergeBingo(Map<String, Object> kit, GameRuntimeSession session, Long actorMemberId) {
        if (playBingoService == null || actorMemberId == null || session.getTopicId() == null) return;
        try {
            Map<String, Object> bingo = playBingoService.playKitView(actorMemberId, session.getTopicId());
            if (bingo != null && !bingo.isEmpty()) kit.put("bingo", bingo);
        } catch (Exception e) {
            logger.error("宾果九宫格投影失败 member={} topic={},按不下发处理,玩法视图不受影响",
                    actorMemberId, session.getTopicId(), e);
        }
    }

    private JsonNode outcomeFeedback(Long memberId,GameRuntimeSession session,ObjectNode state,String optionId) {
        if(!state.has("gameplayDecision"))return null;String attempt="advanced:"+session.getId()+":"+state.path("attemptOrdinal").asLong(1);
        if(state.has("originalGameSessionId"))return gameSessionRuntimeService.sharedGameplayFeedback(memberId,session.getActivityId(),state.path("originalGameSessionId").asLong(),session.getOwnerId(),runtimeEpoch(session),attempt,optionId);
        if(state.has("originalRouteSessionId"))return topicRouteRuntimeService.gameplayFeedback(memberId,state.path("originalRouteSessionId").asLong(),attempt,optionId);return null;
    }
    private Map<String, Object> publicBranch(JsonNode config, JsonNode state) {
        if (!enabled(config)) return null;
        String currentId = state.path("currentStepId").asText();
        JsonNode current = findById(config.path("steps"), currentId);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("currentStepId", currentId);
        if(state.has("ended"))value.put("ended",state.path("ended").asBoolean());
        if(state.has("writable"))value.put("writable",state.path("writable").asBoolean());
        if (current != null) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("id", current.path("id").asText());
            step.put("title", current.path("title").asText());
            step.put("body", current.path("body").asText());
            step.put("terminal", current.path("terminal").asBoolean(false));
            List<Map<String, Object>> options = new ArrayList<>();
            for (JsonNode option : current.path("options")) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", option.path("id").asText());
                item.put("label", option.path("label").asText());
                options.add(item);
            }
            step.put("options", options);
            value.put("currentStep", step);
        }
        return value;
    }

    private Map<String, Object> publicMultiplayer(JsonNode config, JsonNode state, Long actorMemberId) {
        if (!enabled(config)) return null;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("members", objectMapper.convertValue(state.path("members"), List.class));
        value.put("completedUnitIds", objectMapper.convertValue(state.path("completedUnitIds"), List.class));
        value.put("turnIndex", state.path("turnIndex").asInt(0));
        value.put("leaderMemberId", state.path("leaderMemberId").isNumber()
                ? state.path("leaderMemberId").asLong() : null);
        value.put("myRole", state.path("roles").path(String.valueOf(actorMemberId)).asText(null));
        JsonNode myRole = findById(config.path("roles"), state.path("roles").path(String.valueOf(actorMemberId)).asText(""));
        value.put("myRoleLabel", myRole == null ? null : myRole.path("label").asText());
        if (actorMemberId != null && actorMemberId.equals(state.path("leaderMemberId").isNumber()
                ? state.path("leaderMemberId").asLong() : null)) {
            value.put("roleAssignments", objectMapper.convertValue(state.path("roles"), Map.class));
        }
        return value;
    }

    private Map<String, Object> publicRuntimeConfig(JsonNode config, ObjectNode state, Long actorMemberId) {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("schemaVersion", config.path("schemaVersion").asInt(1));
        if (config.has("outcomeRules")) safe.put("outcomeRulesVersion", config.path("outcomeRules").path("version").asInt());
        if(config.has("outcomeRules") && enabled(config.get("check")))safe.put("check",section("enabled",true));
        safe.put("timer", section("enabled", enabled(config.path("timer"))));
        Map<String, Object> random = section("enabled", enabled(config.path("random")));
        if (enabled(config.path("random"))) random.put("drawCount", config.path("random").path("drawCount").asInt());
        safe.put("random", random);
        Map<String, Object> branch = section("enabled", enabled(config.path("branch")));
        Map<String, Object> branchState = publicBranch(config.path("branch"), state.path("branch"));
        if (branchState != null && branchState.get("currentStep") != null) {
            branch.put("steps", Collections.singletonList(branchState.get("currentStep")));
        }
        safe.put("branch", branch);
        Map<String, Object> board = section("enabled", enabled(config.path("leaderboard")));
        if (enabled(config.path("leaderboard"))) board.put("metric", config.path("leaderboard").path("metric").asText("SCORE"));
        safe.put("leaderboard", board);
        Map<String, Object> multi = section("enabled", enabled(config.path("multiplayer")));
        if (enabled(config.path("multiplayer"))) {
            multi.put("assignment", config.path("multiplayer").path("assignment").asText("AUTO"));
            multi.put("requiredTurns", config.path("multiplayer").path("requiredTurns").asInt(1));
            Long leader = state.path("multiplayer").path("leaderMemberId").isNumber()
                    ? state.path("multiplayer").path("leaderMemberId").asLong() : null;
            if (actorMemberId != null && actorMemberId.equals(leader)) {
                List<Map<String, Object>> roles = new ArrayList<>();
                for (JsonNode role : config.path("multiplayer").path("roles")) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", role.path("id").asText());
                    item.put("label", role.path("label").asText());
                    item.put("min", role.path("min").asInt());
                    item.put("max", role.path("max").asInt());
                    roles.add(item);
                }
                multi.put("roles", roles);
            }
        }
        safe.put("multiplayer", multi);
        return safe;
    }

    private Map<String, Object> section(String key, Object value) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put(key, value);
        return section;
    }

    private String statusName(Integer status) {
        if (status == null) return "UNKNOWN";
        switch (status) {
            case GameRuntimeSession.STATUS_RUNNING: return "RUNNING";
            case GameRuntimeSession.STATUS_COMPLETED: return "COMPLETED";
            case GameRuntimeSession.STATUS_FAILED: return "FAILED";
            case GameRuntimeSession.STATUS_EXPIRED: return "EXPIRED";
            default: return "UNKNOWN";
        }
    }

    private boolean enabled(JsonNode node) { return node != null && node.path("enabled").asBoolean(false); }

    private JsonNode findById(JsonNode array, String id) {
        if (array == null || !array.isArray()) return null;
        for (JsonNode item : array) if (id.equals(item.path("id").asText())) return item;
        return null;
    }

    private boolean containsId(ArrayNode array, String id) {
        for (JsonNode item : array) if (id.equals(item.path("id").asText())) return true;
        return false;
    }

    private boolean containsText(ArrayNode array, String value) {
        for (JsonNode item : array) if (value.equals(item.asText())) return true;
        return false;
    }

    private ObjectNode objectState(String json) {
        JsonNode node = readJson(StringUtils.isBlank(json) ? "{}" : json, "玩法状态不可用");
        if (!node.isObject()) throw new IllegalStateException("玩法状态不可用");
        return (ObjectNode) node;
    }

    private JsonNode readJson(String json, String message) {
        try { return objectMapper.readTree(json); }
        catch (Exception e) { throw new IllegalStateException(message); }
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("玩法状态无法保存"); }
    }

    private int value(Integer number) { return number == null ? 0 : number; }

    private long longValue(Object number) {
        if (number instanceof Number) return ((Number) number).longValue();
        try { return Long.parseLong(String.valueOf(number)); }
        catch (Exception e) { return 0L; }
    }

    private void requireRouteNodeAccess(Long memberId, Long activityId, Long topicId, Long nodeId) {
        if (topicRouteRuntimeService == null || activityId == null || activityId <= 0) return;
        CmsActivity activity = activityMapper.selectCmsActivityById(activityId);
        if (activity != null && Integer.valueOf(com.chengyinhub.common.constant.ProductType.ORIENTEERING)
                .equals(activity.getProductType())
                && !topicRouteRuntimeService.canPlay(memberId, topicId, activityId, nodeId)) {
            throw new IllegalStateException("请先完成上一站");
        }
    }

    private void requireActivityWindow(Long activityId, Long topicId) {
        if (activityId == null || activityId <= 0) return;
        CmsActivity activity = activityMapper.selectCmsActivityById(activityId);
        if (activity == null || !topicId.equals(activity.getTopicId())) {
            throw new IllegalArgumentException("活动与主题不匹配");
        }
        Date now = clock.now();
        if (activity.getStartDate() != null && now.before(activity.getStartDate())) {
            throw new IllegalStateException("活动未开始");
        }
        if (activity.getEndDate() != null && !now.before(activity.getEndDate())) {
            throw new IllegalStateException("活动已结束");
        }
    }

    private static final class GameInput {
        private final long activityId;
        private final Long templateId;
        private final String configJson;
        private final GameRuntimeSession existing;

        private GameInput(long activityId, Long templateId, String configJson, GameRuntimeSession existing) {
            this.activityId = activityId;
            this.templateId = templateId;
            this.configJson = configJson;
            this.existing = existing;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public JsonNode queryDefaultNodeContents(Long memberId, Long activityId, Long topicId) {
        if (memberId == null || memberId <= 0 || topicId == null || topicId <= 0) return null;
        long actId = activityId == null ? 0L : activityId;
        List<GameRuntimeSession> rows = sessionMapper.selectDefaultMemberSessionsByTopic(actId, topicId, memberId);
        ObjectNode result = objectMapper.createObjectNode();
        if (rows == null) return result;
        Map<Long,GameRuntimeSession> selected = new LinkedHashMap<>();
        for (GameRuntimeSession row : rows) {
            if (row == null || !Long.valueOf(actId).equals(row.getActivityId()) || !topicId.equals(row.getTopicId())
                    || !"default".equals(row.getPlayInstanceId()) || row.getNodeId() == null || row.getNodeId() <= 0
                    || row.getOwnerType() == null
                    || row.getOwnerType() != GameRuntimeSession.OWNER_PLAYER && row.getOwnerType() != GameRuntimeSession.OWNER_TEAM
                    || row.getOwnerType() == GameRuntimeSession.OWNER_PLAYER && !memberId.equals(row.getOwnerId()))
                throw new IllegalStateException("原小游戏内容归属不可用");
            GameRuntimeSession previous=selected.get(row.getNodeId());
            // Original selector preference: own player row, otherwise latest accessible team row.
            if (previous == null || previous.getOwnerType() != GameRuntimeSession.OWNER_PLAYER)
                selected.put(row.getNodeId(),row);
        }
        for (GameRuntimeSession row : selected.values()) {
            String key=String.valueOf(row.getNodeId());
            ObjectNode source=objectMapper.createObjectNode();
            JsonNode answer=objectState(row.getStateJson()).get("originalNodeContent");
            if (answer != null) {
                if (!com.chengyinhub.business.service.support.NodeJourneySnapshot.hasContent(answer)
                        || answer.path("nodePresentation").path("id").asLong() != row.getNodeId()
                        || answer.path("nodePresentation").path("topicId").asLong() != topicId
                        // 非模板节点冻结 templateId=null；longValue(NullNode) 会读成 0L,不能用来比身份。
                        || !Objects.equals(answer.hasNonNull("templateId") ? Long.valueOf(answer.get("templateId").longValue()) : null,
                                row.getTemplateId()))
                    throw new IllegalStateException("原小游戏内容身份不可用");
                source.putObject("nodeAnswers").set(key,answer.deepCopy());
            } else {
                // A legacy game has no historical metadata; never backfill it from a later publication.
                source.put("legacyNodeContent",true);
            }
            source.putObject("nodeConfigs").set(key,readJson(row.getConfigSnapshotJson(),"原小游戏配置不可用").deepCopy());
            result.set(key,source);
        }
        return result;
    }

}
