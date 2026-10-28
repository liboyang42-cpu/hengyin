package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.support.NodeJourneySnapshot;

import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantPermissionPolicy;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.GameBroadcast;
import com.chengyinhub.business.domain.GameBroadcastDelivery;
import com.chengyinhub.business.domain.GameCommandReceipt;
import com.chengyinhub.business.domain.GamePlayerNodeOutcome;
import com.chengyinhub.business.domain.GameSession;
import com.chengyinhub.business.domain.GameStationRun;
import com.chengyinhub.business.domain.GameStoryState;
import com.chengyinhub.business.domain.GameTaskSubmission;
import com.chengyinhub.business.domain.GameTeamRoleAssignment;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.domain.PlayTeamMember;
import com.chengyinhub.business.domain.dto.GameActorContext;
import com.chengyinhub.business.domain.dto.GameCommandReceiptView;
import com.chengyinhub.business.domain.dto.GameMerchantEntryView;
import com.chengyinhub.business.domain.dto.GameSessionCommand;
import com.chengyinhub.business.domain.dto.GameSessionView;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.GameSessionRuntimeMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.GameSessionException;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IGameSessionRuntimeService;
import com.chengyinhub.business.service.impl.GameSharedRunControl.CooperationScope;
import com.chengyinhub.business.service.impl.GameSessionConfiguration.ReadyConfig;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.chengyinhub.business.service.support.TrustedNodeEffects;
import com.chengyinhub.business.util.AdvancedGamePublicProjection;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.service.support.JourneyInitialState;
import com.chengyinhub.business.service.support.NodeJourneySnapshot;
import com.chengyinhub.business.service.support.ThemeRuntimeEngine;
import com.chengyinhub.business.service.support.GameplayRuntimeConfig;
import com.chengyinhub.business.service.support.GameplaySupportMatrix;
import com.chengyinhub.business.service.support.GameplayActionProjection;
import com.chengyinhub.business.service.support.GameplayCooperationEngine;
import com.chengyinhub.business.service.support.GameplayToolState;
import com.chengyinhub.business.service.support.GameplayToolState.Reason;
import com.chengyinhub.business.service.support.GameplayTaskEngine;
import com.chengyinhub.business.service.support.GameplaySurvivalEngine;
import com.chengyinhub.business.service.support.JourneyStatLinks;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Isolation;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 活动级总局、三身份权限、状态与回执的唯一实现。 */
@Service
public class GameSessionRuntimeServiceImpl implements IGameSessionRuntimeService {

    // 2026-09-06 现场事件(Figma D8):俱乐部在现场需要能暂停一个出问题的站点、
    // 恢复它,以及把一条提交打回重交。
    // ★ 刻意**单开 CLUB_ 前缀的动作名**,而不是把 STATION_PAUSE / VERIFY_SUBMISSION
    //   直接加进本集合 —— 那三个词的实现里写死了 requireMerchantStation(站点必须属于
    //   当前商家),两个集合共用一个词会让 preauthorize 走进 CLUB 分支后再也跑不到
    //   商家那层校验,而实现层又必然抛。单开名字还让「谁能做什么」在 grep 里可分辨。
    private static final Set<String> CLUB_ACTIONS = new HashSet<>(Arrays.asList(
            "PREPARE", "START", "ASSIGN_ROLES", "UNLOCK_CHAPTER", "BROADCAST", "FINISH",
            "SET_LEADERBOARD_VISIBILITY", "TAKEOVER_ROLE",
            "CLUB_STATION_PAUSE", "CLUB_STATION_RESUME", "CLUB_REJECT_SUBMISSION"));
    private static final Set<String> MERCHANT_ACTIONS = new HashSet<>(Arrays.asList(
            "STATION_ACCEPT", "STATION_DECLINE", "STATION_READY", "STATION_PAUSE", "STATION_RESUME",
            "VERIFY_SUBMISSION"));
    /** These actions mutate a current member/node or TEAM story, not shared world/director state. */
    private static final Set<String> INDEPENDENT_PLAYER_ACTIONS = new HashSet<>(Arrays.asList(
            "PLAYER_HINT", "PLAYER_REVEAL", "PLAYER_SUBMIT", "PLAYER_CHOICE"));
    /**
     * F-L3-01:商家/俱乐部对「某一条提交」或「某一个站点」的写动作。并发控制下沉到对象本身 ——
     * 提交行 decideSubmissionCas(只认 PENDING)、站点行 updateStationStatusCas(只认原状态 + 站点 revision),
     * 且整条命令在 game_session 行锁内执行。全局 revision 只拦「比服务端还新」的伪造版本,
     * 不再因为别队玩家的提示/提交/选择把商家核验、站点暂停/恢复判成过期。
     * 导演台(UNLOCK_CHAPTER/START/ASSIGN_ROLES/FINISH...)、角色确认和就绪配置仍要求精确版本。
     */
    private static final Set<String> OBJECT_SCOPED_STAFF_ACTIONS = new HashSet<>(Arrays.asList(
            "VERIFY_SUBMISSION", "CLUB_REJECT_SUBMISSION",
            "STATION_PAUSE", "STATION_RESUME", "CLUB_STATION_PAUSE", "CLUB_STATION_RESUME"));
    private static final Set<String> PLAYER_ACTIONS = new HashSet<>(Arrays.asList(
            "CONFIRM_ROLE", "PLAYER_CHOICE", "PLAYER_SUBMIT", "PLAYER_HINT", "PLAYER_REVEAL"));
    private static final Set<String> RUN_CONTROL_ACTIONS = GameSharedRunControl.RUN_CONTROL_ACTIONS;
    private static final Set<String> SHARED_PLAYER_ACTIONS = GameSharedRunControl.SHARED_PLAYER_ACTIONS;
    private final ThreadLocal<Date> manualEvaluationTime = new ThreadLocal<>();
    private final SecureRandom nonceRandom = new SecureRandom();
    @Autowired
    private IClubAccessService clubAccessService;
    private static final Logger log = LoggerFactory.getLogger(GameSessionRuntimeServiceImpl.class);
    private static final Set<String> TASK_INPUT_TYPES = GameSessionConfiguration.TASK_INPUT_TYPES;
    /**
     * ADD_TAG 的 tag 集合寄存键。game_story_state 只有 variables_json 一个自由状态列(没有 tag 列),
     * 不改表结构的前提下只能借用它;用 __ 前缀的保留键,旧对象写法永远不读写这个键,行为零变化。
     */
    private static final String STORY_TAGS_KEY = GameSessionConfiguration.STORY_TAGS_KEY;

    @Autowired
    private GameSessionRuntimeMapper runtimeMapper;
    @Autowired
    private CmsActivityMapper activityMapper;
    @Autowired
    private CmsTopicMapper topicMapper;
    @Autowired
    private IClubMemberService clubMemberService;
    @Autowired
    private com.chengyinhub.business.service.ICapabilityService capabilityService;
    @Autowired
    private PlayTeamMapper playTeamMapper;

    @Autowired
    private com.chengyinhub.business.mapper.CmsRegistrationMapper currentPlayRegistrationMapper;
    @Autowired
    private IPlayerNodeProgressService playerNodeProgressService;
    @Autowired
    private IImService imService;

    // Shared route reads call this runtime too; resolve the reverse edge after both services initialize.
    @Autowired
    @org.springframework.context.annotation.Lazy
    private ITopicRouteRuntimeService topicRouteRuntimeService;
    private final GameplayTaskEngine taskEngine = new GameplayTaskEngine();
    @Autowired private com.chengyinhub.business.service.support.VerifiedWeRunService verifiedWeRunService;
    private final GameplaySurvivalEngine survivalEngine = new GameplaySurvivalEngine();
    private final GameplayCooperationEngine cooperationEngine = new GameplayCooperationEngine();
    private final ThemeRuntimeEngine themeEngine = new ThemeRuntimeEngine();
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 剧情规则的数组写法直接复用路线图求值内核,不另抄一份 conditionsPass / applyEffects。 */
    private final TopicRouteEngine topicRouteEngine = new TopicRouteEngine();
    private AdvancedGameClock clock = Date::new;
    /**
     * 照片证据上传事实缝(R9-39):生产复用 play/photo 同一校验——URL 来自现行上传链、对象真实存在、
     * 上传归属==当前玩家。单测可注入替身,但任何注入都不得降级为“跳过校验”。
     */
    private PhotoEvidenceVerifier photoEvidenceVerifier = FileOssUploadUtils::verifyUploadedPhoto;

    @FunctionalInterface
    interface PhotoEvidenceVerifier {
        void verify(String picUrl, Long memberId);
    }

    /** Shared ledger owner; construction is effect-free and never captures/calls this runtime. */
    private GameSessionConfiguration configuration() { return new GameSessionConfiguration(objectMapper); }

    private GameSharedRunControl sharedRunControl() {
        return new GameSharedRunControl(runtimeMapper, playTeamMapper, objectMapper, clock,
                manualEvaluationTime, cooperationEngine, survivalEngine, taskEngine);
    }

    @Override
    public boolean isGameModuleConfigEnabled(String configJson) { return configuration().isGameModuleConfigEnabled(configJson); }

    @Override
    public boolean isGameSessionEnabled(CmsTopic topic) {
        return topic != null && (isRouteTopic(topic)
                || isGameModuleConfigEnabled(topic.getGameModuleConfigJson()));
    }

    private boolean isRouteTopic(CmsTopic topic) {
        return topic != null && Integer.valueOf(com.chengyinhub.common.constant.ProductType.ORIENTEERING)
                .equals(topic.getProductType()) && StringUtils.isBlank(topic.getGameModuleConfigJson());
    }

    private boolean isRouteSession(GameSession session) {
        return session != null && "ROUTE".equals(sessionConfig(session).path("playMode").asText());
    }

    private JsonNode readRouteJson(String raw) { return configuration().readRouteJson(raw); }

    private JsonNode routeGameplay(GameSession session) {
        return sessionConfig(session).path("themeConfig").path("gameplayRuntime");
    }

    private boolean isSharedRoute(GameSession session) {
        return isRouteSession(session) && "TEAM".equals(GameplayRuntimeConfig.getRuntimeScope(sessionConfig(session).path("themeConfig")));
    }

    private boolean naturallyCompleted(GameSession session, TopicRouteEngine.State state) {
        return !GameplayCooperationEngine.isEffectiveTarget(sessionConfig(session).path("themeConfig"), state);
    }

    private void requireNaturalPlayOpen(GameSession session, TopicRouteEngine.State state) {
        if (naturallyCompleted(session, state)) throw error("GAME_ROUTE_FINISHED", "本队已完成最后章节，资产与回执可继续查看");
    }

    private JourneyRules routeRules(GameSession session) {
        return JourneyRules.parse(sessionConfig(session).path("journeyRules").toString());
    }

    private TopicRouteEngine.State routeOwnerState(GameStoryState owner) {
        try { return objectMapper.readValue(owner.getVariablesJson(), TopicRouteEngine.State.class); }
        catch (Exception invalid) { throw error("GAME_CONFIG_INVALID", "本队运行状态不可用"); }
    }

    private void requireRunStaff(GameActorContext actor, CmsActivity activity, String action) {
        String permission = Arrays.asList("ARRIVAL_CONFIRM", "ARRIVAL_ATTEST", "ARRIVAL_REVOKE", "QUEST_GUIDE").contains(action)
                ? ClubPermission.EVENT_CHECKIN : ClubPermission.EVENT_OPERATE;
        try {
            if (clubAccessService.require(actor.getMemberId(), activity.getClubId(), activity.getId(), permission) == null) {
                throw error("GAME_FORBIDDEN_SCOPE", "当前账号没有本场操作权限");
            }
        } catch (ServiceException denied) { throw error("GAME_FORBIDDEN_SCOPE", "当前账号没有本场操作权限"); }
    }

    private void initializeRunControl(GameSession session) { sharedRunControl().initializeRunControl(session); }

    private void initializeWorldOwner(GameSession session) { sharedRunControl().initializeWorldOwner(session); }

    private ObjectNode runControl(GameSession session) { return sharedRunControl().runControl(session); }

    private void saveRun(GameSession session, ObjectNode run) { sharedRunControl().saveRun(session, run); }

    private int runEpoch(GameSession session) { return sharedRunControl().runEpoch(session); }

    private void requireSharedWrite(GameSession session, GameSessionCommand command) { sharedRunControl().requireSharedWrite(session, command); }

    private void requireOnlineRun(JsonNode run) { sharedRunControl().requireOnlineRun(run); }

    private Map<String, JsonNode> routeBalances(GameSession session) { return sharedRunControl().routeBalances(session); }

    private void appendRunEvent(GameSession session, GameActorContext actor, GameSessionCommand command, Long receiptId,
                                Map<String, JsonNode> before) { sharedRunControl().appendRunEvent(session, actor, command, receiptId, before); }

    private Map<String, Object> publicRunControl(GameSession session) { return sharedRunControl().publicRunControl(session); }

    private ObjectNode playerManualView(ObjectNode run, Long memberId) { return sharedRunControl().playerManualView(run, memberId); }

    private void runPayload(GameSessionCommand command, String... allowed) { sharedRunControl().runPayload(command, allowed); }

    private String runReason(GameSessionCommand command) { return sharedRunControl().runReason(command); }

    private ActionResult runControlAction(GameActorContext actor, CmsActivity activity, GameSession session,
                                          GameSessionCommand command, Long receiptId) {
        if (!isSharedRoute(session)) throw error("GAME_ACTION_UNSUPPORTED", "此操作仅适用于共享运行局");
        ObjectNode run = runControl(session);
        Map<String, Object> data = new LinkedHashMap<>();
        String action = command.getAction();
        switch (action) {
            case "PLAN_FALLBACK": {
                Map<String, Object> applied = sharedRunControl().closeFallback(actor, activity, session, command, run, receiptId);
                bumpSession(session, session.getStatus(), session.getCurrentChapterId(), actor.getMemberId(), clock.now());
                return result(session, applied);
            }
            case "RUN_PAUSE": case "RUN_INTERRUPT": case "RUN_CANCEL": case "RUN_RESUME":
            case "TEAM_EXIT": case "STAFF_TEAM_EXIT": case "MANUAL_REVIEW": case "AUTHORITY_RESUME":
            case "ARRIVAL_REVOKE": case "ACT_UNLOCK": case "LOCATION_REBIND":
                sharedRunControl().applyLifecycle(actor, activity, session, command, run, receiptId);
                break;
            case "ARRIVAL_ISSUE": return issueArrival(actor, activity, session, command, run);
            case "ARRIVAL_CONFIRM": return confirmArrival(actor, activity, session, command, run);
            case "ARRIVAL_ATTEST": return attestManualArrival(actor, activity, session, command, run, receiptId);
            case "QUEST_GUIDE": return guideRouteQuest(actor, activity, session, command, run, receiptId);
            case "MANUAL_BEGIN": return beginManual(actor, activity, session, command, run);
            case "STAFF_MANUAL_SIGN":
            case "MANUAL_SIGN": return signManual(actor, activity, session, command, run, receiptId);
            case "MANUAL_IMPORT": return importManual(actor, activity, session, command, run, receiptId);
            default: throw error("GAME_ACTION_UNSUPPORTED", "运行操作不受支持");
        }
        saveRun(session, run);
        bumpSession(session, session.getStatus(), session.getCurrentChapterId(), actor.getMemberId(), clock.now());
        data.put("run", publicRunControl(session));
        return result(session, data);
    }

    private void saveRouteOwner(GameSession session, GameStoryState owner, TopicRouteEngine.State state) { sharedRunControl().saveRouteOwner(session, owner, state); }

    /** 运营取消/中止/退队只释放占用，不推进归零 HP 或挑战计时，因此不会顺带写入 HP 结局。 */
    private void saveOwnerWithoutSurvival(GameStoryState owner, TopicRouteEngine.State state) { sharedRunControl().saveOwnerWithoutSurvival(owner, state); }

    /** 在线暂停期间，归零 HP 与挑战计时停在暂停时刻。 */
    private long survivalTime(GameSession session) { return sharedRunControl().survivalTime(session); }

    /** 恢复时把未结的归零 HP / 挑战截止时间顺延暂停时长。 */

    private Long requireFrozenTeam(GameSession session, Object raw) { return sharedRunControl().requireFrozenTeam(session, raw); }

    private String digest(String value) { return sharedRunControl().digest(value); }

    private String arrivalKey(Long teamId, String location) { return teamId + ":" + location; }

    private JsonNode binding(GameSession session, JsonNode quest) { return runControl(session).path("bindings").path(quest.path("locationKey").asText()); }

    private long bindingVersion(GameSession session, JsonNode quest) { return binding(session, quest).path("version").asLong(1); }

    private long bindingNode(GameSession session, JsonNode quest) { return binding(session, quest).path("nodeId").asLong(quest.path("nodeId").asLong()); }

    private ActionResult issueArrival(GameActorContext actor, CmsActivity activity, GameSession session, GameSessionCommand command, ObjectNode run) {
        runPayload(command, "questKey", "operationKind", "operationKey"); requireOnlineRun(run); requireSessionStatus(session, GameSession.RUNNING);
        PlayerScope player = requirePlayer(actor, activity);
        JsonNode quest = arrivalTarget(session, command);
        TopicRouteEngine.State state = routeOwnerState(requireRouteOwner(session, player.teamId));
        if (quest.has("questKey")) requireEligibleQuest(session, state, quest);
        else if (quest.path("nodeId").asLong() != state.getThemeRuntime().path("activeOperation").path("nodeId").asLong(-1) || !quest.path("operationKey").asText().equals(state.getThemeRuntime().path("activeOperation").path("operationKey").asText(null))) throw error("GAME_PUBLIC_OPERATION_LOCKED", "请先开始该操作题组");
        GameStationRun station = requirePlayableStation(activity, session, bindingNode(session, quest));
        if (!"HOST_NODE".equals(station.getSourceType())) throw error("GAME_BAD_REQUEST", "商家节点继续使用原到店签到");
        if (run.path("nonces").size() >= 2000) throw error("GAME_ARRIVAL_LIMIT", "本局到达凭证达到支持容量");
        byte[] random = new byte[32]; nonceRandom.nextBytes(random); String nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        ObjectNode proof = run.with("nonces").putObject(digest(nonce));
        proof.put("sessionId", session.getId()).put("teamId", player.teamId).put("nodeId", station.getNodeId()).put("locationKey", quest.path("locationKey").asText());
        proof.put("version", bindingVersion(session, quest)).put("epoch", runEpoch(session)).put("issuedAt", clock.now().getTime()).put("expiresAt", Math.min(clock.now().getTime() + routeGameplay(session).path("arrivalTtlSeconds").asLong(300) * 1000L, activity.getEndDate().getTime())).put("used", false);
        saveRun(session, run); bumpSession(session, session.getStatus(), session.getCurrentChapterId(), actor.getMemberId(), clock.now());
        Map<String, Object> data = new LinkedHashMap<>(); data.put("nonce", nonce); data.put("expiresAt", proof.path("expiresAt").asLong()); data.put("teamId", player.teamId); data.put("locationKey", quest.path("locationKey").asText()); data.put("version", proof.path("version").asLong());
        return result(session, data);
    }

    private ActionResult confirmArrival(GameActorContext actor, CmsActivity activity, GameSession session, GameSessionCommand command, ObjectNode run) {
        runPayload(command, "teamId", "questKey", "operationKind", "operationKey", "nonce"); requireOnlineRun(run); requireSessionStatus(session, GameSession.RUNNING);
        Long teamId = requireFrozenTeam(session, command.getPayload().get("teamId"));
        JsonNode quest = arrivalTarget(session, command);
        GameStationRun station = requirePlayableStation(activity, session, bindingNode(session, quest));
        if (!"HOST_NODE".equals(station.getSourceType())) throw error("GAME_BAD_REQUEST", "该点不是工作人员站点");
        String nonce = text(command.getPayload().get("nonce"), 64);
        if (nonce == null || !nonce.matches("[A-Za-z0-9_-]{43}")) throw error("GAME_ARRIVAL_INVALID", "到达凭证无效");
        JsonNode proof = run.path("nonces").path(digest(nonce));
        if (!proof.isObject() || proof.path("sessionId").asLong() != session.getId() || proof.path("teamId").asLong() != teamId
                || proof.path("nodeId").asLong() != station.getNodeId() || !proof.path("locationKey").asText().equals(quest.path("locationKey").asText())
                || proof.path("version").asLong() != bindingVersion(session, quest) || proof.path("epoch").asInt() != runEpoch(session)
                || proof.path("issuedAt").asLong() > evaluationTime().getTime() || proof.path("expiresAt").asLong() <= evaluationTime().getTime()) throw error("GAME_ARRIVAL_INVALID", "到达凭证已过期或地点版本不匹配");
        if (proof.path("used").asBoolean()) throw error("GAME_ARRIVAL_REPLAY", "到达凭证已经核验");
        ((ObjectNode)proof).put("used", true).put("verifiedBy", actor.getMemberId()).put("verifiedAt", evaluationTime().getTime());
        run.with("arrivals").set(arrivalKey(teamId, quest.path("locationKey").asText()), proof.deepCopy());
        saveRun(session, run); bumpSession(session, session.getStatus(), session.getCurrentChapterId(), actor.getMemberId(), clock.now());
        return result(session, singleton("arrival", singleton("verified", true)));
    }

    private ActionResult attestManualArrival(GameActorContext actor, CmsActivity activity, GameSession session,
                                             GameSessionCommand command, ObjectNode run, Long receiptId) {
        runPayload(command, "teamId", "questKey", "operationKind", "operationKey", "reason");
        String reason = runReason(command);
        if (manualEvaluationTime.get() == null || !"MANUAL".equals(run.path("authorityMode").asText()) || !"CLUB".equals(actor.getPerspective())) {
            throw error("GAME_FORBIDDEN_SCOPE", "纸账到场核验只能重放实际工作人员的连续签署");
        }
        requireSessionStatus(session, GameSession.RUNNING);
        Long teamId = requireFrozenTeam(session, command.getPayload().get("teamId"));
        JsonNode target = arrivalTarget(session, command);
        GameStationRun station = requirePlayableStation(activity, session, bindingNode(session, target));
        if (!"HOST_NODE".equals(station.getSourceType())) throw error("GAME_BAD_REQUEST", "商家节点继续使用到店签到");
        TopicRouteEngine.State state = routeOwnerState(requireRouteOwner(session, teamId));
        if (target.has("questKey")) requireEligibleQuest(session, state, target);
        else if (!target.path("operationKey").asText().equals(state.getThemeRuntime().path("activeOperation").path("operationKey").asText(null))) throw error("GAME_PUBLIC_OPERATION_LOCKED", "请先开始该操作题组");
        ObjectNode proof = objectMapper.createObjectNode().put("sessionId", session.getId()).put("teamId", teamId)
                .put("nodeId", station.getNodeId()).put("locationKey", target.path("locationKey").asText())
                .put("version", bindingVersion(session, target)).put("epoch", runEpoch(session)).put("used", true)
                .put("verifiedBy", actor.getMemberId()).put("verifiedAt", evaluationTime().getTime())
                .put("expiresAt", Math.min(evaluationTime().getTime() + routeGameplay(session).path("arrivalTtlSeconds").asLong(300) * 1000L, activity.getEndDate().getTime()))
                .put("manualReceiptId", receiptId).put("reason", reason);
        run.with("arrivals").set(arrivalKey(teamId, target.path("locationKey").asText()), proof);
        saveRun(session, run); bumpSession(session, session.getStatus(), session.getCurrentChapterId(), actor.getMemberId(), clock.now());
        return result(session, singleton("arrival", singleton("verified", true)));
    }

    private ActionResult guideRouteQuest(GameActorContext actor, CmsActivity activity, GameSession session, GameSessionCommand command,
                                         ObjectNode run, Long receiptId) {
        runPayload(command, "teamId", "questKey", "answers", "reason"); requireOnlineRun(run); runReason(command);
        Long teamId = requireFrozenTeam(session, command.getPayload().get("teamId"));
        JsonNode quest = configuredQuest(session, text(command.getPayload().get("questKey"), 64));
        if (sessionConfig(session).path("nodeConfigs").path(quest.path("nodeId").asText()).path("outcomeRules").path("version").asInt() == 1)
            throw error("GAME_GUIDE_UNSUPPORTED", "结果规则任务暂不支持工作人员引导结算");
        JsonNode answers = objectMapper.valueToTree(command.getPayload().get("answers"));
        if (!topicRouteRuntimeService.verifyGuidedRouteAnswer(sessionConfig(session), quest.path("nodeId").asLong(), answers)) throw error("GAME_GUIDE_NOT_CONFIGURED", "引导答案尚未满足冻结题组与依据");
        GameStoryState owner = requireRouteOwner(session, teamId); TopicRouteEngine.State state = routeOwnerState(owner);
        requireEligibleQuest(session, state, quest);
        if (!quest.path("questKey").asText().equals(state.getThemeRuntime().path("gameplay").path("activeQuest").asText())) throw error("GAME_TASK_UNVERIFIED", "本队尚未开始该任务");
        GameStationRun station = requirePlayableStation(activity, session, bindingNode(session, quest));
        if ("HOST_NODE".equals(station.getSourceType())) requireHostArrival(session, teamId, quest, station);
        else {
            com.chengyinhub.business.domain.PlayTeam team = playTeamMapper.selectPlayTeamById(teamId);
            if (team == null) throw error("GAME_TEAM_REQUIRED", "队伍已不在本场");
            requireArrived(GameActorContext.player(team.getLeaderMemberId()), activity, station);
        }
        String instance = sharedInstance(session, state, bindingNode(session, quest));
        if (!state.getThemeRuntime().path("verifiedNodes").has(quest.path("nodeId").asText())) {
            JsonNode effects = topicRouteRuntimeService.trustedGuidedRouteEffects(sessionConfig(session), quest.path("nodeId").asLong(), answers);
            ObjectNode pending = state.getThemeRuntime().with("gameplay").with("pendingTaskEffects").putObject(quest.path("questKey").asText());
            if (effects == null || !effects.isArray()) throw error("GAME_CONFIG_INVALID", "受控引导效果不可用");
            pending.set("effects", effects.deepCopy());
            pending.putObject("sources").put("guide:" + instance, true);
        }
        state.getThemeRuntime().with("verifiedNodes").put(quest.path("nodeId").asText(), receiptId);
        recordQuestFacilityFact(session, state, quest, receiptId, actor.getMemberId());
        JsonNode operation = state.getThemeRuntime().path("activeOperation");
        if (operation.isObject() && operation.path("nodeId").asLong() == station.getNodeId() && jointQuestOperation(session, state, operation)) {
            ObjectNode proof = (ObjectNode)operation.deepCopy(); proof.put("verifiedReceiptId", receiptId); proof.set("effects", objectMapper.createArrayNode()); proof.putObject("sources").put("guide:" + instance, true);
            state.getThemeRuntime().with("operationFacts").set(operation.path("operationKind").asText() + ":" + operation.path("operationKey").asText(), proof);
        }
        state.getThemeRuntime().with("guidedNodes").putObject(quest.path("nodeId").asText()).put("assisted", true)
                .put("verifiedBy", actor.getMemberId()).put("receiptId", receiptId).put("reason", runReason(command)).put("instance", instance);
        saveRouteOwner(session, owner, state); bumpSession(session, session.getStatus(), session.getCurrentChapterId(), actor.getMemberId(), clock.now());
        return result(session, singleton("guidedQuest", quest.path("questKey").asText()));
    }

    private void recordQuestFacilityFact(GameSession session, TopicRouteEngine.State state, JsonNode quest, Long receiptId, Long memberId) {
        if (!quest.has("facilityKey")) return;
        JsonNode facility = configuredCooperation(session, "facilities", "facilityKey", quest.path("facilityKey").asText());
        if (facility.path("operationNodeId").asLong(-1) != quest.path("nodeId").asLong()) return;
        state.getThemeRuntime().with("operationFacts").putObject("FACILITY:" + facility.path("facilityKey").asText())
                .put("epoch", runEpoch(session)).put("receiptId", receiptId).put("startedBy", memberId).put("nodeId", bindingNode(session, quest))
                .put("sourceNodeId", quest.path("nodeId").asLong()).put("locationKey", quest.path("locationKey").asText()).put("bindingVersion", bindingVersion(session, quest));
    }

    private void requireHostArrival(GameSession session, Long teamId, JsonNode quest, GameStationRun station) {
        JsonNode proof = runControl(session).path("arrivals").path(arrivalKey(teamId, quest.path("locationKey").asText()));
        if (!proof.path("used").asBoolean() || proof.path("teamId").asLong() != teamId || proof.path("sessionId").asLong() != session.getId()
                || proof.path("nodeId").asLong() != station.getNodeId() || proof.path("version").asLong() != bindingVersion(session, quest)
                || proof.path("epoch").asInt() != runEpoch(session) || proof.path("verifiedAt").asLong() > evaluationTime().getTime() || proof.path("expiresAt").asLong() <= evaluationTime().getTime()) {
            throw error("GAME_STATION_ARRIVAL_REQUIRED", "请由本场工作人员核验当前地点的到达凭证");
        }
    }

    private Map<String, Object> sharedExport(GameSession session) { return sharedRunControl().sharedExport(session); }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> exportSharedRun(GameActorContext actor, Long activityId) {
        requireActor(actor); CmsActivity activity = requireActivity(activityId, false);
        if (!"CLUB".equals(actor.getPerspective())) throw error("GAME_FORBIDDEN_SCOPE", "仅本场运营人员可导出人工账");
        requireRunStaff(actor, activity, "RUN_PAUSE");
        GameSession session = runtimeMapper.selectSessionByActivityForUpdate(activityId);
        if (!isSharedRoute(session)) throw error("GAME_ACTION_UNSUPPORTED", "本局没有共享账");
        return sharedExport(session);
    }

    private ObjectNode latestOfflineAnchor(GameSession session) { return sharedRunControl().latestOfflineAnchor(session); }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> anchorSharedRun(GameActorContext actor, Long activityId) {
        requireActor(actor); CmsActivity activity = requireActivity(activityId, false);
        if (!"CLUB".equals(actor.getPerspective())) throw error("GAME_FORBIDDEN_SCOPE", "仅本场运营人员可保存纸账锚点");
        requireRunStaff(actor, activity, "RUN_PAUSE");
        GameSession session = runtimeMapper.selectSessionByActivityForUpdate(activityId);
        if (!isSharedRoute(session)) throw error("GAME_ACTION_UNSUPPORTED", "本局没有共享账");
        return sharedRunControl().anchorSharedRun(actor, activity, session);
    }

    private ActionResult beginManual(GameActorContext actor, CmsActivity activity, GameSession session, GameSessionCommand command, ObjectNode run) {
        Map<String, Object> data = sharedRunControl().beginManual(actor, activity, session, command, run);
        bumpSession(session, session.getStatus(), session.getCurrentChapterId(), actor.getMemberId(), clock.now());
        return result(session, data);
    }

    private ActionResult signManual(GameActorContext actor, CmsActivity activity, GameSession session, GameSessionCommand command, ObjectNode run, Long receiptId) {
        GameSharedRunControl.ManualCommand signed = sharedRunControl().prepareManualSign(actor, activity, session, command, run, receiptId);
        preauthorize(signed.signer(), activity, session, signed.command());
        Map<String, Object> data = sharedRunControl().completeManualSign(actor, activity, session, command, run, receiptId, signed);
        bumpSession(session, session.getStatus(), session.getCurrentChapterId(), actor.getMemberId(), clock.now());
        return result(session, data);
    }

    private ActionResult importManual(GameActorContext actor, CmsActivity activity, GameSession session, GameSessionCommand command, ObjectNode run, Long receiptId) {
        GameSharedRunControl.ManualReplay replay = sharedRunControl().prepareManualReplay(actor, activity, session, command, run, receiptId);
        if (replay.alreadyApplied()) return result(session, replay.resultData());
        JsonNode signature = replay.signature();
        GameActorContext signer = replay.signer();
        GameSessionCommand source = replay.command();
        preauthorize(signer, activity, session, source);
        GameCommandReceipt old = signature.has("originalRequestId") ? runtimeMapper.selectReceiptForUpdate(activity.getId(), signer.getMemberId(), signature.path("originalRequestId").asText()) : null;
        if (old != null && "PENDING".equals(old.getOutcome())) throw error("GAME_MANUAL_UNRESOLVED", "原在线命令仍未知，不能重复重放");
        Map<String, Object> applied;
        if (old != null) {
            GameSessionCommand original = new GameSessionCommand(); original.setActivityId(old.getActivityId()); original.setNodeId(source.getNodeId()); original.setAction(source.getAction());
            original.setPayload(source.getPayload()); original.setExpectedRevision(signature.path("originalExpectedRevision").asInt()); original.setAuthorityEpoch(signature.path("epoch").asInt());
            if (!old.getRequestHash().equals(requestHash(original))) throw error("GAME_MANUAL_UNRESOLVED", "原在线事件与人工动作不一致，需复核");
        }
        if (old != null && "APPLIED".equals(old.getOutcome())) applied = readMap(old.getResultJson());
        else {
            GameCommandReceipt claim = receiptClaim(signer, source, source.getRequestHash(), session);
            if (runtimeMapper.insertReceiptClaim(claim) != 1) throw error("GAME_MANUAL_UNRESOLVED", "人工事件回执冲突");
            Map<String, JsonNode> before = routeBalances(session);
            manualEvaluationTime.set(new Date(signature.path("occurredAt").asLong()));
            ActionResult replayed;
            try { replayed = dispatch(signer, activity, session, source, claim.getId()); }
            finally { manualEvaluationTime.remove(); }
            applied = replayed.result;
            if ("PLAYER".equals(signer.getPerspective())) applied.put("receiptOwnerTeamId", requirePlayer(signer, activity).teamId);
            claim.setOutcome("APPLIED"); claim.setRevision(session.getRevision()); claim.setResultJson(writeJson(applied)); claim.setUpdateTime(clock.now());
            if (runtimeMapper.completeReceipt(claim) != 1) throw error("GAME_STATE_CONFLICT", "人工事件回执保存失败");
            appendRunEvent(session, signer, source, claim.getId(), before);
        }
        Map<String, Object> data = sharedRunControl().completeManualImport(session, actor, receiptId, replay, applied);
        bumpSession(session, session.getStatus(), session.getCurrentChapterId(), actor.getMemberId(), clock.now());
        return result(session, data);
    }

    private Date evaluationTime() { Date imported = manualEvaluationTime.get(); return imported == null ? clock.now() : imported; }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assertSharedRouteWritable(Long memberId, Long topicId, Long activityId, Long nodeId, Integer authorityEpoch) {
        if (activityId == null || activityId <= 0) return;
        GameSession session = runtimeMapper.selectSessionByActivityForUpdate(activityId);
        if (!isSharedRoute(session)) return;
        if (!Objects.equals(topicId, session.getTopicId())) throw error("GAME_FORBIDDEN_SCOPE", "活动与主题不匹配");
        GameSessionCommand check = new GameSessionCommand(); check.setAction("TRUSTED_ROUTE_RESULT"); check.setAuthorityEpoch(authorityEpoch);
        requireSharedWrite(session, check); requireSessionStatus(session, GameSession.RUNNING);
        CmsActivity activity = requireActivity(activityId, true); PlayerScope player = requirePlayer(GameActorContext.player(memberId), activity);
        if (runControl(session).path("exitedTeams").has(String.valueOf(player.teamId))) throw error("GAME_TEAM_EXITED", "本队已退出本局");
        TopicRouteEngine.State state = routeOwnerState(requireRouteOwner(session, player.teamId));
        boolean challenge = activeChallenge(session, state, nodeId);
        requireNoCooperationSpend(session, player.teamId, state, objectMapper.createArrayNode(), !challenge && !isFinalOperation(state, nodeId));
        if (!challenge) {
            if (nodeId != null) requirePlayableStation(activity, session, nodeId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> sharedControlView(GameActorContext actor, Long activityId) {
        requireActor(actor); CmsActivity activity = requireActivity(activityId, false);
        if (!"CLUB".equals(actor.getPerspective())) throw error("GAME_FORBIDDEN_SCOPE", "仅本场运营人员可查看控制视图");
        requireRunStaff(actor, activity, "RUN_PAUSE");
        GameSession session = runtimeMapper.selectSessionByActivityForUpdate(activityId);
        if (!isSharedRoute(session)) throw error("GAME_ACTION_UNSUPPORTED", "本局没有共享运行控制");
        Map<String, Object> data = new LinkedHashMap<>(); data.put("run", publicRunControl(session)); data.put("revision", session.getRevision());
        List<Map<String, Object>> teams = new ArrayList<>();
        for (GameStoryState owner : safe(runtimeMapper.selectStoryStates(session.getId()))) if ("TEAM".equals(owner.getOwnerType())) {
            TopicRouteEngine.State state = routeOwnerState(owner); Map<String, Object> projection = new LinkedHashMap<>();
            projection.put("teamId", owner.getOwnerId()); projection.put("player", themeEngine.view(sessionConfig(session).path("themeConfig"), state, routeRules(session)));
            projection.put("ownerRevision", owner.getRevision()); projection.put("exited", runControl(session).path("exitedTeams").has(String.valueOf(owner.getOwnerId()))); teams.add(projection);
        }
        data.put("teams", teams);
        List<ObjectNode> guideTasks = new ArrayList<>();
        JsonNode frozen = sessionConfig(session);
        for (JsonNode quest : routeGameplay(session).path("quests")) {
            String nodeId = quest.path("nodeId").asText();
            ObjectNode questions = AdvancedGamePublicProjection.guideQuestions(
                    frozen.path("nodeConfigs").path(nodeId), frozen.path("nodeAnswers").path(nodeId));
            if (questions.size() == 0) continue;
            questions.put("questKey", quest.path("questKey").asText());
            questions.set("nodeId", quest.path("nodeId"));
            guideTasks.add(questions);
        }
        data.put("guideTasks", guideTasks);
        if ("WORLD_SHARED".equals(GameplayRuntimeConfig.getWorldMode(sessionConfig(session).path("themeConfig")))) {
            CooperationScope scope = cooperationScope(session, false);
            ObjectNode world = cooperationEngine.view(sessionConfig(session).path("themeConfig"), scope.world, scope.teams, null, scope.leaders);
            JsonNode control = runControl(session);
            if (!GameSession.RUNNING.equals(session.getStatus()) || Arrays.asList("CANCELLED", "INTERRUPTED").contains(control.path("operationStatus").asText()))
                world.put("fallbackAvailable", false).put("fallbackDisabledReason", "SESSION_ENDED");
            else if (!"RUNNING".equals(control.path("operationStatus").asText()) || !"ONLINE".equals(control.path("authorityMode").asText()))
                world.put("fallbackAvailable", false).put("fallbackDisabledReason", "SESSION_PAUSED");
            else if (!isWithinActivityServiceWindow(activity, evaluationTime())) world.put("fallbackAvailable", false).put("fallbackDisabledReason", "RULE_BLOCKED");
            data.put("world", world);
        }
        data.put("stations", safe(runtimeMapper.selectStations(session.getId())).stream().map(s -> stationView(s, sessionConfig(session))).collect(java.util.stream.Collectors.toList()));
        ObjectNode run = runControl(session); List<String> actions = new ArrayList<>();
        if (GameSession.RUNNING.equals(session.getStatus()) && !Arrays.asList("INTERRUPTED", "CANCELLED").contains(run.path("operationStatus").asText())) {
            actions.addAll(Arrays.asList("RUN_INTERRUPT", "RUN_CANCEL", "STAFF_TEAM_EXIT"));
            if ("MANUAL".equals(run.path("authorityMode").asText())) actions.addAll(Arrays.asList("STAFF_MANUAL_SIGN", "MANUAL_IMPORT", "MANUAL_REVIEW", "AUTHORITY_RESUME"));
            else if ("PAUSED".equals(run.path("operationStatus").asText())) actions.addAll(Arrays.asList("RUN_RESUME", "LOCATION_REBIND", "MANUAL_BEGIN", "ARRIVAL_REVOKE"));
            else actions.addAll(Arrays.asList("RUN_PAUSE", "ACT_UNLOCK", "QUEST_GUIDE", "ARRIVAL_CONFIRM", "ARRIVAL_REVOKE"));
        }
        data.put("availableActions", actions);
        data.put("offlineAnchor", latestOfflineAnchor(session));
        data.put("offlineAnchorAvailable", GameSession.RUNNING.equals(session.getStatus())
                && "ONLINE".equals(run.path("authorityMode").asText())
                && !Arrays.asList("INTERRUPTED", "CANCELLED").contains(run.path("operationStatus").asText())
                && isWithinActivityServiceWindow(activity, clock.now())
                && (run.path("exports").size() < 128 || run.path("exports").has(String.valueOf(sharedExport(session).get("highWaterHash")))));
        if (data.get("world") instanceof ObjectNode && ((ObjectNode) data.get("world")).path("fallbackAvailable").asBoolean()) actions.add("PLAN_FALLBACK");
        return data;
    }

    private List<String> cooperationPlayerActions(GameSession session, Long teamId) {
        if (!"WORLD_SHARED".equals(GameplayRuntimeConfig.getWorldMode(sessionConfig(session).path("themeConfig")))) {
            return routeGameplay(session).path("facilities").isEmpty() ? Collections.emptyList() : Arrays.asList("FACILITY_USE", "OPERATION_START", "OPERATION_CANCEL");
        }
        CooperationScope scope = cooperationScope(session, false); JsonNode cooperation = scope.world.getThemeRuntime().path("cooperation");
        if (cooperation.has("result") || !scope.teams.containsKey(teamId)) return Collections.emptyList();
        String stage = cooperation.path("stage").asText("DRAFT");
        if ("VOTING".equals(stage)) return Arrays.asList("VOTE_CAST", "PLAN_WITHDRAW", "PLAN_CHANGE");
        if ("ACCEPTING".equals(stage)) return Arrays.asList("ACCEPT_SET", "PLAN_CHANGE");
        if ("CONSENSUS".equals(stage)) return Arrays.asList("CONSENSUS_CAST", "PLAN_CHANGE");
        if ("SELECTED".equals(stage)) return Arrays.asList("PLAN_CONFIRM", "PLAN_EXECUTE", "PLAN_CHANGE", "OPERATION_START", "OPERATION_CANCEL");
        if ("TIED".equals(stage)) {
            List<String> tied = new ArrayList<>(Arrays.asList("VOTE_NEXT", "PLAN_CHANGE", "PLAN_WITHDRAW"));
            if ("MAJORITY_ACCEPTANCE".equals(routeGameplay(session).path("votePolicy").asText())) tied.add("ACCEPT_SET");
            return tied;
        }
        List<String> result = new ArrayList<>(Arrays.asList("TRADE_OPEN", "TRADE_CONFIRM", "TRADE_CANCEL", "FACILITY_USE", "PLAN_PROPOSE", "PLAN_INTENT", "PLAN_WITHDRAW", "VOTE_OPEN", "OPERATION_START", "OPERATION_CANCEL"));
        if (!"FALLBACK_NOT_CONFIGURED".equals(cooperationEngine.fallbackDisabledReason(sessionConfig(session).path("themeConfig"), scope.world, scope.teams, scope.leaders))) result.add("PLAN_REJECT_ALL");
        return result;
    }

    private JsonNode operationTarget(GameSession session, String kind, Object key) { return sharedRunControl().operationTarget(session, kind, key); }

    private JsonNode arrivalTarget(GameSession session, GameSessionCommand command) { return sharedRunControl().arrivalTarget(session, command); }

    private boolean activeChallenge(GameSession session, TopicRouteEngine.State state, Long nodeId) {
        return GameplaySurvivalEngine.challengeNode(sessionConfig(session).path("themeConfig"), nodeId)
                && state.getThemeRuntime().path("awaitingChallenge").asBoolean()
                && !state.getThemeRuntime().path("challenge").has("disabledReason");
    }

    private String sharedInstance(GameSession session, TopicRouteEngine.State state, Long nodeId) {
        if (activeChallenge(session, state, nodeId)) return "game:" + session.getId() + ":challenge:" + state.getThemeRuntime().path("challenge").path("instanceId").asText();
        JsonNode operation = state.getThemeRuntime().path("activeOperation");
        if (operation.isObject() && Objects.equals(nodeId, operation.path("nodeId").asLong())) return "game:" + session.getId() + ":operation:" + operation.path("receiptId").asLong();
        String active = state.getThemeRuntime().path("gameplay").path("activeQuest").asText();
        if (!active.isEmpty() && state.getThemeRuntime().path("gameplay").has("activeQuestReceiptId")
                && Objects.equals(nodeId, bindingNode(session, configuredQuest(session, active)))) return "game:" + session.getId() + ":quest:" + state.getThemeRuntime().path("gameplay").path("activeQuestReceiptId").asLong();
        return "game:" + session.getId();
    }

    private boolean isFinalOperation(TopicRouteEngine.State state, Long nodeId) {
        JsonNode active = state.getThemeRuntime().path("activeOperation");
        return "PLAN".equals(active.path("operationKind").asText()) && Objects.equals(nodeId, active.path("nodeId").asLong());
    }

    private ActionResult routeOperationAction(GameActorContext actor, CmsActivity activity, GameSession session, GameSessionCommand command, Long receiptId) {
        runPayload(command, "operationKind", "operationKey", "costOption"); requireSessionStatus(session, GameSession.RUNNING);
        PlayerScope player = requirePlayer(actor, activity); requireRouteLeader(actor.getMemberId(), activity.getId(), player.teamId);
        if (runControl(session).path("exitedTeams").has(String.valueOf(player.teamId))) throw error("GAME_TEAM_EXITED", "本队已退出");
        GameStoryState owner = requireRouteOwner(session, player.teamId); TopicRouteEngine.State state = routeOwnerState(owner);
        if ("OPERATION_CANCEL".equals(command.getAction())) {
            if (!command.getPayload().isEmpty()) throw error("GAME_BAD_REQUEST", "取消当前操作无需参数");
            JsonNode cancelled = state.getThemeRuntime().path("activeOperation");
            if (cancelled.isObject()) {
                // Joint operations own only the matching active quest cache; other node facts remain valid.
                if (jointQuestOperation(session, state, cancelled)) state.getThemeRuntime().with("gameplay").with("pendingTaskEffects").remove(state.getThemeRuntime().path("gameplay").path("activeQuest").asText());
                state.getThemeRuntime().with("operationFacts").remove(cancelled.path("operationKind").asText() + ":" + cancelled.path("operationKey").asText());
                state.getThemeRuntime().with("verifiedNodes").remove(Arrays.asList(cancelled.path("sourceNodeId").asText(cancelled.path("nodeId").asText()), cancelled.path("nodeId").asText()));
            }
            state.getThemeRuntime().remove("activeOperation");
        } else {
            JsonNode target = operationTarget(session, text(command.getPayload().get("operationKind"), 32), command.getPayload().get("operationKey"));
            requirePlayableStation(activity, session, target.path("nodeId").asLong());
            CooperationScope scope = "PLAN".equals(target.path("operationKind").asText()) || "WORLD_SHARED".equals(GameplayRuntimeConfig.getWorldMode(sessionConfig(session).path("themeConfig"))) ? cooperationScope(session, true) : null;
            if (state.getThemeRuntime().has("activeOperation")) throw error("GAME_PUBLIC_OPERATION_LOCKED", "请先完成或取消当前操作题组");
            if (state.getThemeRuntime().path("gameplay").has("activeQuest")) {
                if (!jointQuestOperation(session,state,target)) throw error("GAME_RESOURCE_RESERVED", "请先完成或取消当前任务");
            }
            if ("PLAN".equals(target.path("operationKind").asText())) {
                if(command.getPayload().containsKey("costOption"))throw error("GAME_BAD_REQUEST","方案操作不接受任选费用组");
                requirePlanNodeEligible(session, state, configuredCooperation(session, "plans", "endingKey", target.path("operationKey").asText()));
                JsonNode cooperation = scope.world.getThemeRuntime().path("cooperation");
                String selected = cooperation.path("selected").asText();
                if (!"SELECTED".equals(cooperation.path("stage").asText()) || !target.path("operationKey").asText().equals(cooperation.path("proposals").path(selected).path("endingKey").asText())) throw error("GAME_PUBLIC_OPERATION_LOCKED", "只能开始本轮已选方案的操作题");
                ((ObjectNode)target).put("round", cooperation.path("round").asInt());
            } else {
                JsonNode facility = configuredCooperation(session, "facilities", "facilityKey", target.path("operationKey").asText());
                Integer selected=commandCostOption(command.getPayload());
                try {ObjectNode fee=taskEngine.costs(facility,selected);themeEngine.settle(sessionConfig(session).path("themeConfig"),state.copy(),routeRules(session),"preview:operation",fee,objectMapper.createArrayNode());}
                catch(IllegalArgumentException|IllegalStateException unavailable){throw error("GAME_TASK_COST_UNAVAILABLE",unavailable.getMessage());}
                if(selected!=null)((ObjectNode)target).put("costOption",selected);
                String act = routeGameplay(session).path("acts").path(state.getThemeRuntime().path("gameplay").path("actIndex").asInt()).path("actKey").asText();
                if (!facility.path("actKey").asText().equals(act) && !GameplayCooperationEngine.openExtraFacility(sessionConfig(session).path("themeConfig"), state, facility)
                        || (scope != null && cooperationEngine.isVotingFrozen(scope.world))) throw error("GAME_PUBLIC_OPERATION_LOCKED", "设施不属于当前可操作阶段");
            }
            ((ObjectNode)target).put("epoch", runEpoch(session)).put("startedBy", actor.getMemberId()).put("receiptId", receiptId);
            state.getThemeRuntime().with("operationFacts").remove(target.path("operationKind").asText() + ":" + target.path("operationKey").asText());
            state.getThemeRuntime().with("verifiedNodes").remove(target.path("sourceNodeId").asText());
            state.getThemeRuntime().with("verifiedNodes").remove(target.path("nodeId").asText());
            state.getThemeRuntime().set("activeOperation", target);
            if (jointQuestOperation(session, state, target)) state.getThemeRuntime().with("gameplay").with("pendingTaskEffects").remove(state.getThemeRuntime().path("gameplay").path("activeQuest").asText());
            bindBasicArrivalProof(session, state, target, actor.getMemberId());
        }
        saveRouteOwner(session, owner, state); bumpSession(session, session.getStatus(), session.getCurrentChapterId(), actor.getMemberId(), clock.now());
        return result(session, singleton("operation", state.getThemeRuntime().path("activeOperation").deepCopy()));
    }

    private boolean cooperationAction(String action) { return cooperationEngine.supports(action); }

    private CooperationScope cooperationScope(GameSession session, boolean forUpdate) { return sharedRunControl().cooperationScope(session, forUpdate); }

    private void persistCooperation(CooperationScope scope) { sharedRunControl().persistCooperation(scope); }

    private void persistCooperation(CooperationScope scope, boolean survival) { sharedRunControl().persistCooperation(scope, survival); }

    private void requireNoCooperationSpend(GameSession session, Long teamId, TopicRouteEngine.State team, JsonNode resourceCosts, boolean changesBalance) {
        if (!"WORLD_SHARED".equals(GameplayRuntimeConfig.getWorldMode(sessionConfig(session).path("themeConfig")))) return;
        CooperationScope scope = cooperationScope(session, true);
        if (scope.world.getThemeRuntime().path("cooperation").has("result")) throw error("GAME_ROUTE_FINISHED", "公共结果已冻结，本局只读");
        try {
            if (changesBalance && cooperationEngine.isVotingFrozen(scope.world)) throw new IllegalStateException("表决及托管期间须先改案释放，再改变队伍余额");
            cooperationEngine.requireAvailable(scope.world, teamId, team, resourceCosts);
        } catch (IllegalArgumentException | IllegalStateException unavailable) { throw error("GAME_RESOURCE_RESERVED", unavailable.getMessage()); }
    }

    private boolean freeQuestAtNode(GameSession session, TopicRouteEngine.State state, JsonNode node, boolean requireVerified) {
        if (!node.isIntegralNumber()) return false;
        String active = state.getThemeRuntime().path("gameplay").path("activeQuest").asText("");
        if (active.isEmpty()) return false;
        JsonNode quest = configuredQuest(session, active);
        if (quest.path("nodeId").asLong() != node.asLong() || quest.has("facilityKey") || quest.has("costOptions")
                || quest.path("hpCost").asInt() > 0 || quest.path("luckCost").asInt() > 0 || quest.path("actionCost").asInt() > 0
                || quest.path("statCosts").size() > 0 || quest.path("resourceCosts").size() > 0 || quest.path("grants").size() > 0 || quest.path("choices").size() > 0) return false;
        JsonNode proof = state.getThemeRuntime().path("verifiedNodes").path(quest.path("nodeId").asText());
        return !requireVerified || proof.isIntegralNumber() && proof.asLong() > 0 || proof.isBoolean() && proof.asBoolean();
    }

    private boolean participantFreeQuest(GameSession session, TopicRouteEngine.State state, JsonNode facility, boolean requireVerified) {
        return "PARTICIPANTS".equals(facility.path("targetScope").asText()) && freeQuestAtNode(session,state,facility.path("operationNodeId"),requireVerified);
    }

    private boolean jointQuestOperation(GameSession session, TopicRouteEngine.State state, JsonNode operation) {
        String kind=operation.path("operationKind").asText();
        if ("FACILITY".equals(kind)) return participantFreeQuest(session,state,configuredCooperation(session,"facilities","facilityKey",operation.path("operationKey").asText()),false);
        if ("PLAN".equals(kind)) {
            JsonNode plan=configuredCooperation(session,"plans","endingKey",operation.path("operationKey").asText());
            return (!plan.path("terminal").asBoolean(true) || plan.has("storyEndingId")) && freeQuestAtNode(session,state,plan.path("operationNodeId"),false);
        }
        return false;
    }

    private boolean planFreeQuest(GameSession session, TopicRouteEngine.State state, JsonNode plan) {
        return (!plan.path("terminal").asBoolean(true) || plan.has("storyEndingId")) && freeQuestAtNode(session,state,plan.path("operationNodeId"),false);
    }

    private void consumeFacilityOperation(TopicRouteEngine.State state, String key) {
        JsonNode operation = state.getThemeRuntime().path("activeOperation");
        state.getThemeRuntime().with("operationFacts").remove("FACILITY:" + key);
        if ("FACILITY".equals(operation.path("operationKind").asText()) && key.equals(operation.path("operationKey").asText())) {
            state.getThemeRuntime().with("verifiedNodes").remove(Arrays.asList(operation.path("sourceNodeId").asText(), operation.path("nodeId").asText()));
            state.getThemeRuntime().remove("activeOperation");
        }
    }

    private ActionResult routeCooperationAction(GameActorContext actor, CmsActivity activity, GameSession session, GameSessionCommand command, Long receiptId) {
        requireSessionStatus(session, GameSession.RUNNING); requireOnlineRun(runControl(session)); requireActivityServiceWindow(activity, evaluationTime());
        PlayerScope player = requirePlayer(actor, activity); requireRouteLeader(actor.getMemberId(), activity.getId(), player.teamId);
        if ("PLAN_PROPOSE".equals(command.getAction())) requirePlanNodeEligible(session, routeOwnerState(requireRouteOwner(session, player.teamId)), configuredCooperation(session, "plans", "endingKey", command.getPayload().get("endingKey")));
        if ("FACILITY_USE".equals(command.getAction()) && !"WORLD_SHARED".equals(GameplayRuntimeConfig.getWorldMode(sessionConfig(session).path("themeConfig")))) {
            GameStoryState owner = requireRouteOwner(session, player.teamId); TopicRouteEngine.State state = routeOwnerState(owner);
            if (runControl(session).path("exitedTeams").has(String.valueOf(player.teamId))) throw error("GAME_TEAM_EXITED", "本队已退出");
            if (state.getThemeRuntime().path("gameplay").has("activeQuest")) throw error("GAME_RESOURCE_RESERVED", "请先完成或取消当前任务");
            JsonNode facility = configuredCooperation(session, "facilities", "facilityKey", command.getPayload().get("facilityKey"));
            if (facility.has("operationNodeId")) requireOperationFact(session, state, "FACILITY", facility.path("facilityKey").asText(), null);
            ObjectNode applied;
            Map<Long,TopicRouteEngine.State> rewardOwners=new LinkedHashMap<>();rewardOwners.put(player.teamId,state);
            try { applied = cooperationEngine.settleFacility(sessionConfig(session).path("themeConfig"), routeRules(session),new TopicRouteEngine.State(),rewardOwners,Collections.singletonMap(player.teamId,actor.getMemberId()),player.teamId,actor.getMemberId(),facilityPayload(state,command.getPayload()),"receipt:"+receiptId,evaluationTime().getTime());state=rewardOwners.get(player.teamId); }
            catch (IllegalArgumentException | IllegalStateException invalid) { throw error("GAME_COOPERATION_UNAVAILABLE", invalid.getMessage()); }
            if(!"BLOCKED_COST".equals(applied.path("status").asText()))consumeFacilityOperation(state, facility.path("facilityKey").asText());
            saveRouteOwner(session, owner, state); bumpSession(session, session.getStatus(), session.getCurrentChapterId(), actor.getMemberId(), clock.now());
            return result(session, publicRouteSettlement(session, applied));
        }
        CooperationScope scope = cooperationScope(session, true);
        if (!scope.teams.containsKey(player.teamId)) throw error("GAME_FORBIDDEN_SCOPE", "本队不在当前参与名单中");
        if (Arrays.asList("TRADE_OPEN", "TRADE_CONFIRM", "FACILITY_USE", "PLAN_CONFIRM", "PLAN_EXECUTE", "VOTE_OPEN").contains(command.getAction())) {
            JsonNode partFacility = "FACILITY_USE".equals(command.getAction()) ? configuredCooperation(session, "facilities", "facilityKey", command.getPayload().get("facilityKey")) : objectMapper.createObjectNode();
            JsonNode participants = objectMapper.valueToTree(command.getPayload().get("participants"));
            for (Map.Entry<Long, TopicRouteEngine.State> team : scope.teams.entrySet()) if (team.getValue().getThemeRuntime().path("gameplay").has("activeQuest")) {
                if (naturallyCompleted(session, team.getValue())) continue;
                boolean participates = false; for (JsonNode id : participants) if (id.isIntegralNumber() && id.asLong() == team.getKey()) participates = true;
                boolean allowed = participates && participantFreeQuest(session,team.getValue(),partFacility,true);
                if (Arrays.asList("PLAN_CONFIRM","PLAN_EXECUTE").contains(command.getAction())) {
                    JsonNode proposal=scope.world.getThemeRuntime().path("cooperation").path("proposals").path(String.valueOf(command.getPayload().get("proposalKey")));
                    allowed=planFreeQuest(session,team.getValue(),configuredCooperation(session,"plans","endingKey",proposal.path("endingKey").asText()));
                } else if ("VOTE_OPEN".equals(command.getAction())) {
                    for (JsonNode proposal:scope.world.getThemeRuntime().path("cooperation").path("proposals")) if ("OPEN".equals(proposal.path("status").asText()) && planFreeQuest(session,team.getValue(),configuredCooperation(session,"plans","endingKey",proposal.path("endingKey").asText()))) allowed=true;
                }
                if (!allowed) throw error("GAME_RESOURCE_RESERVED", "有任务预留尚未释放，请先完成或取消任务再签署资源结算");
            }
        }
        if ("PLAN_EXECUTE".equals(command.getAction())) {
            JsonNode proposal = scope.world.getThemeRuntime().path("cooperation").path("proposals").path(String.valueOf(command.getPayload().get("proposalKey")));
            JsonNode plan = configuredCooperation(session, "plans", "endingKey", proposal.path("endingKey").asText());
            if (plan.has("operationNodeId")) requireOperationFact(session, scope, player.teamId, "PLAN", proposal.path("endingKey").asText());
        }
        if ("FACILITY_USE".equals(command.getAction())) {
            JsonNode facility = configuredCooperation(session, "facilities", "facilityKey", command.getPayload().get("facilityKey"));
            if (facility.has("operationNodeId")) requireOperationFact(session, scope, player.teamId, "FACILITY", facility.path("facilityKey").asText());
        }
        Set<Long> planQuestOwners = new HashSet<>();
        Set<Long> effectiveTeams = new HashSet<>();
        scope.teams.forEach((id, state) -> { if (!naturallyCompleted(session, state)) effectiveTeams.add(id); });
        if ("PLAN_EXECUTE".equals(command.getAction())) {
            JsonNode proposal=scope.world.getThemeRuntime().path("cooperation").path("proposals").path(String.valueOf(command.getPayload().get("proposalKey")));
            JsonNode plan=configuredCooperation(session,"plans","endingKey",proposal.path("endingKey").asText());
            for(Map.Entry<Long,TopicRouteEngine.State> team:scope.teams.entrySet())if(effectiveTeams.contains(team.getKey()) && planFreeQuest(session,team.getValue(),plan))planQuestOwners.add(team.getKey());
        }
        ObjectNode applied;
        try {
            if("FACILITY_USE".equals(command.getAction()))applied=cooperationEngine.settleFacility(sessionConfig(session).path("themeConfig"),routeRules(session),scope.world,scope.teams,scope.leaders,player.teamId,actor.getMemberId(),facilityPayload(scope.teams.get(player.teamId),command.getPayload()),"receipt:"+receiptId,evaluationTime().getTime());
            else applied = cooperationEngine.execute(sessionConfig(session).path("themeConfig"), routeRules(session), scope.world, scope.teams,
                    scope.leaders, player.teamId, actor.getMemberId(), command.getAction(),objectMapper.valueToTree(command.getPayload()), "receipt:" + receiptId, evaluationTime().getTime(),sessionConfig(session));
        } catch (IllegalArgumentException | IllegalStateException unavailable) { throw error("GAME_COOPERATION_UNAVAILABLE", unavailable.getMessage()); }
        if ("FACILITY_USE".equals(command.getAction()) && !"BLOCKED_COST".equals(applied.path("status").asText())) {
            String facilityKey = String.valueOf(command.getPayload().get("facilityKey"));
            consumeFacilityOperation(scope.teams.get(player.teamId), facilityKey);
            for (JsonNode target : applied.path("targets")) {
                TopicRouteEngine.State targetState = scope.teams.get(target.asLong());
                if (targetState != null && !targetState.getThemeRuntime().path("gameplay").has("activeQuest")) {
                    targetState.getThemeRuntime().with("gameplay").remove("activeQuestReceiptId");
                    consumeFacilityOperation(targetState, facilityKey);
                }
            }
        }
        if ("PLAN_EXECUTE".equals(command.getAction())) {
            String endingKey = scope.world.getThemeRuntime().path("cooperation").path("proposals").path(String.valueOf(command.getPayload().get("proposalKey"))).path("endingKey").asText();
            for (Map.Entry<Long,TopicRouteEngine.State> teamEntry : scope.teams.entrySet()) {
                if (!effectiveTeams.contains(teamEntry.getKey())) continue;
                TopicRouteEngine.State teamState=teamEntry.getValue();
                if(planQuestOwners.contains(teamEntry.getKey()) && !teamState.getThemeRuntime().path("gameplay").has("activeQuest"))teamState.getThemeRuntime().with("gameplay").remove("activeQuestReceiptId");
                JsonNode operation = teamState.getThemeRuntime().path("activeOperation");
                teamState.getThemeRuntime().with("operationFacts").remove("PLAN:" + endingKey);
                if ("PLAN".equals(operation.path("operationKind").asText()) && endingKey.equals(operation.path("operationKey").asText())) {
                    teamState.getThemeRuntime().with("verifiedNodes").remove(Arrays.asList(operation.path("sourceNodeId").asText(), operation.path("nodeId").asText()));
                    teamState.getThemeRuntime().remove("activeOperation");
                }
            }
        }
        persistCooperation(scope); bumpSession(session, session.getStatus(), session.getCurrentChapterId(), actor.getMemberId(), clock.now());
        Map<String, Object> data = new LinkedHashMap<>(); data.put("result", publicRouteSettlement(session, applied));
        data.put("cooperation", cooperationEngine.view(sessionConfig(session).path("themeConfig"), scope.world, scope.teams, player.teamId, scope.leaders));
        return result(session, data);
    }

    private void requireOperationFact(GameSession session, CooperationScope scope, Long teamId, String kind, String key) {
        requireOperationFact(session, scope.teams.get(teamId), kind, key, "PLAN".equals(kind) ? scope.world.getThemeRuntime().path("cooperation").path("round").asInt() : null);
    }

    private void requireOperationFact(GameSession session, TopicRouteEngine.State state, String kind, String key, Integer round) {
        JsonNode proof = state.getThemeRuntime().path("operationFacts").path(kind + ":" + key);
        if (!proof.isObject() || proof.path("epoch").asInt() != runEpoch(session)
                || (round != null && proof.path("round").asInt(-1) != round)) {
            throw error("GAME_TASK_UNVERIFIED", "当前版本的操作题组尚未通过可信判定");
        }
    }

    private JsonNode configuredCooperation(GameSession session, String field, String idField, Object id) { return sharedRunControl().configuredCooperation(session, field, idField, id); }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> sharedPreview(GameActorContext actor, Long activityId, String action, Map<String, Object> payload) {
        requireActor(actor); CmsActivity activity = requireActivity(activityId, false);
        if (!"PLAYER".equals(actor.getPerspective())) throw error("GAME_FORBIDDEN_SCOPE", "请使用本队玩家预览");
        GameSession session = runtimeMapper.selectSessionByActivityForUpdate(activityId);
        if (!isSharedRoute(session)) throw error("GAME_ACTION_UNSUPPORTED", "本局没有共享任务");
        PlayerScope player = requirePlayer(actor, activity); requireRouteLeader(actor.getMemberId(), activityId, player.teamId);
        String normalized = upper(action); Map<String, Object> result = new LinkedHashMap<>();
        TopicRouteEngine.State state = routeOwnerState(requireRouteOwner(session, player.teamId));
        if ("FACILITY_USE".equals(normalized) && !"WORLD_SHARED".equals(GameplayRuntimeConfig.getWorldMode(sessionConfig(session).path("themeConfig")))) {
            JsonNode facility = configuredCooperation(session, "facilities", "facilityKey", payload.get("facilityKey"));
            if (facility.has("operationNodeId")) requireOperationFact(session, state, "FACILITY", facility.path("facilityKey").asText(), null);
            try { result.put("result", cooperationEngine.previewFacility(sessionConfig(session).path("themeConfig"),routeRules(session),new TopicRouteEngine.State(),Collections.singletonMap(player.teamId,state),Collections.singletonMap(player.teamId,actor.getMemberId()),player.teamId,actor.getMemberId(),facilityPayload(state,payload),evaluationTime().getTime())); }
            catch (IllegalArgumentException | IllegalStateException invalid) { throw error("GAME_COOPERATION_UNAVAILABLE", invalid.getMessage()); }
        } else if (cooperationAction(normalized)) {
            CooperationScope scope = cooperationScope(session, false);
            if ("FACILITY_USE".equals(normalized)) {
                JsonNode facility = configuredCooperation(session, "facilities", "facilityKey", payload.get("facilityKey"));
                if (facility.has("operationNodeId")) requireOperationFact(session, scope, player.teamId, "FACILITY", facility.path("facilityKey").asText());
            }
            if ("PLAN_EXECUTE".equals(normalized)) {
                JsonNode proposal = scope.world.getThemeRuntime().path("cooperation").path("proposals").path(String.valueOf(payload.get("proposalKey")));
                JsonNode plan = configuredCooperation(session, "plans", "endingKey", proposal.path("endingKey").asText());
                if (plan.has("operationNodeId")) requireOperationFact(session, scope, player.teamId, "PLAN", proposal.path("endingKey").asText());
            }
            try {
                if ("TRADE_OPEN".equals(normalized)) {
                    result.put("result", cooperationEngine.previewTrade(sessionConfig(session).path("themeConfig"), routeRules(session), scope.world, scope.teams, player.teamId, objectMapper.valueToTree(payload)));
                    result.put("player", themeEngine.view(sessionConfig(session).path("themeConfig"), state, routeRules(session)));
                    result.put("expectedRevision", session.getRevision()); result.put("authorityEpoch", runEpoch(session)); result.put("previewOnly", true); return publicRouteSettlement(session, objectMapper.valueToTree(result));
                }
                ObjectNode preview;
                if("FACILITY_USE".equals(normalized))preview=cooperationEngine.previewFacility(sessionConfig(session).path("themeConfig"),routeRules(session),scope.world,scope.teams,scope.leaders,player.teamId,actor.getMemberId(),facilityPayload(state,payload),evaluationTime().getTime());
                else preview = cooperationEngine.execute(sessionConfig(session).path("themeConfig"), routeRules(session), scope.world, scope.teams,
                        scope.leaders, player.teamId, actor.getMemberId(), normalized, objectMapper.valueToTree(payload), "preview",evaluationTime().getTime(),sessionConfig(session));
                result.put("cooperation", cooperationEngine.view(sessionConfig(session).path("themeConfig"), scope.world, scope.teams, player.teamId, scope.leaders));
                result.put("result", preview); state = scope.teams.get(player.teamId);
            } catch (IllegalArgumentException | IllegalStateException invalid) { throw error("GAME_COOPERATION_UNAVAILABLE", invalid.getMessage()); }
        } else if ("QUEST_RESOLVE".equals(normalized)) {
            GameSessionCommand shape = new GameSessionCommand(); shape.setPayload(payload); runPayload(shape, "questKey", "choiceKey", "discardRewards", "costOption");
            String questKey = text(payload.get("questKey"), 64), choiceKey = text(payload.get("choiceKey"), 64);
            JsonNode quest = configuredQuest(session, questKey); requireEligibleQuest(session, state, quest);
            if (routeFacilityOccupied(session, quest)) {
                JsonNode facility = configuredCooperation(session, "facilities", "facilityKey", quest.path("facilityKey").asText());
                CooperationScope scope = cooperationScope(session, false);
                ObjectNode lost = objectMapper.createObjectNode().put("questKey", questKey).put("competitionLost", true).put("settled", false);
                lost.set("facilityResult", scope.world.getThemeRuntime().path("cooperation").path("facilities").path(facility.path("settlementSlot").asText()).path("result").deepCopy());
                result.put("settlement", lost); result.put("expectedRevision", session.getRevision()); result.put("authorityEpoch", runEpoch(session)); result.put("previewOnly", true);
                return publicRouteSettlement(session, objectMapper.valueToTree(result));
            }
            requireRoutePaidCostAllowed(session, state, quest);
            try {
                JsonNode discard=payload.containsKey("discardRewards")?objectMapper.valueToTree(payload.get("discardRewards")):objectMapper.createArrayNode();
                if(quest.has("facilityKey")) {
                    CooperationScope scope="WORLD_SHARED".equals(GameplayRuntimeConfig.getWorldMode(sessionConfig(session).path("themeConfig")))?cooperationScope(session,false):null;
                    result.put("settlement",cooperationEngine.previewQuest(sessionConfig(session).path("themeConfig"),routeRules(session),scope==null?new TopicRouteEngine.State():scope.world,scope==null?Collections.singletonMap(player.teamId,state):scope.teams,scope==null?Collections.singletonMap(player.teamId,actor.getMemberId()):scope.leaders,player.teamId,actor.getMemberId(),questKey,choiceKey,discard,commandCostOption(payload),"TEAM:"+player.teamId,evaluationTime().getTime()));
                } else result.put("settlement",taskEngine.preview(sessionConfig(session).path("themeConfig"),routeRules(session),state,questKey,choiceKey,discard,"TEAM:"+player.teamId,commandCostOption(payload),evaluationTime().getTime()));
            }
            catch (IllegalArgumentException | IllegalStateException invalid) { throw error("GAME_TASK_COST_UNAVAILABLE", invalid.getMessage()); }
        } else throw error("GAME_ACTION_UNSUPPORTED", "该动作不提供结算预览");
        result.put("player", themeEngine.view(sessionConfig(session).path("themeConfig"), state, routeRules(session)));
        result.put("expectedRevision", session.getRevision()); result.put("authorityEpoch", runEpoch(session)); result.put("previewOnly", true);
        return publicRouteSettlement(session, objectMapper.valueToTree(result));
    }

    private void requireRouteLeader(Long memberId, Long activityId, Long teamId) {
        com.chengyinhub.business.domain.PlayTeam team = playTeamMapper.selectPlayTeamByIdForUpdate(teamId);
        if (team == null || !Integer.valueOf(2).equals(team.getOwnerType()) || !activityId.equals(team.getOwnerId())
                || !memberId.equals(team.getLeaderMemberId()) || team.getStatus() == null || team.getStatus() > 2) {
            throw error("GAME_FORBIDDEN_SCOPE", "仅本场现有队长可消耗队伍物资");
        }
    }

    private ActionResult routeItemAction(GameActorContext actor, CmsActivity activity, GameSession session,
                                         GameSessionCommand command, Long receiptId) {
        requireSessionStatus(session, GameSession.RUNNING);
        if (!(command.getPayload().get("contentId") instanceof String) || command.getPayload().size() > 2 || command.getPayload().size() == 2 && (!"ITEM_RECEIVE".equals(command.getAction()) || !command.getPayload().containsKey("discardOwned"))) {
            throw error("GAME_BAD_REQUEST", "物品动作只接受内容标识");
        }
        String id = (String) command.getPayload().get("contentId");
        if (StringUtils.isBlank(id) || id.length() > 96) throw error("GAME_BAD_REQUEST", "内容标识无效");
        PlayerScope player = requirePlayer(actor, activity);
        if (runControl(session).path("exitedTeams").has(String.valueOf(player.teamId))) throw error("GAME_TEAM_EXITED", "本队已退出本局");
        GameStoryState owner = requireRouteOwner(session, player.teamId);
        TopicRouteEngine.State state = routeOwnerState(owner), before = routeOwnerState(owner);
        boolean completed="COMPLETED".equals(state.getStatus()) || state.getThemeRuntime().has("result");
        boolean restricted=completed && "ITEM_RECEIVE".equals(command.getAction());
        if(completed && !restricted)throw error("GAME_ROUTE_FINISHED","本队已完成，只能结算已有待领奖励");
        if(command.getPayload().containsKey("discardOwned") && !restricted)throw error("GAME_BAD_REQUEST","仅完成局待领取可附带丢弃持有物资");
        if (command.getAuthorityEpoch() == null || command.getAuthorityEpoch() != runEpoch(session)) {
            throw error("GAME_AUTHORITY_CONFLICT", "本局权威版本已更新，请刷新");
        }
        JsonNode config = sessionConfig(session).path("themeConfig");
        JourneyRules rules = routeRules(session);
        if (!"ITEM_RECEIVE".equals(command.getAction()) && state.getThemeRuntime().path("gameplay").has("activeQuest")) {
            throw error("GAME_RESOURCE_RESERVED", "任务进行中，请先完成或取消任务再使用队伍物资");
        }
        ArrayNode spend = objectMapper.createArrayNode();
        if (!"ITEM_RECEIVE".equals(command.getAction())) spend.addObject().put("itemId", id).put("quantity", 1);
        if(restricted) {
            if(state.getThemeRuntime().path("gameplay").has("activeQuest") || state.getThemeRuntime().has("activeOperation"))throw error("GAME_RESOURCE_RESERVED","持有物资仍有未释放的预留");
            try {
                spend=themeEngine.rewardSelection(config,command.getPayload().containsKey("discardOwned")?objectMapper.valueToTree(command.getPayload().get("discardOwned")):null);
                if("WORLD_SHARED".equals(GameplayRuntimeConfig.getWorldMode(config)))cooperationEngine.requireAvailable(cooperationScope(session,true).world,player.teamId,state,spend);
            } catch(IllegalArgumentException | IllegalStateException invalid){throw error("GAME_RESOURCE_RESERVED",invalid.getMessage());}
        } else requireNoCooperationSpend(session, player.teamId, state, spend, true);
        try {
            if ("ITEM_USE".equals(command.getAction())) themeEngine.use(config, state, rules, id);
            else if ("ITEM_DISCARD".equals(command.getAction())) themeEngine.discard(config, state, rules, id);
            else if(restricted)themeEngine.claimPending(config,state,id,spend);
            else themeEngine.receive(config, state, rules, id, true);
        } catch (IllegalArgumentException | IllegalStateException unavailable) { throw error("GAME_ITEM_UNAVAILABLE", unavailable.getMessage()); }
        if(!restricted)JourneyInitialState.clamp(rules, state);
        if ("ITEM_USE".equals(command.getAction())) {
            JourneyStatLinks.apply(rules, before, state, "TEAM:" + player.teamId, "receipt:" + receiptId, JourneyStatLinks.Reason.ITEM_USE);
        }
        ObjectNode event = state.getThemeRuntime().withArray("journal").addObject();
        event.put("action", command.getAction()); event.put("receiptId", receiptId); event.put("actorMemberId", actor.getMemberId());
        event.put("owner", "TEAM:" + player.teamId); event.put("authorityEpoch", command.getAuthorityEpoch());
        owner.setVariablesJson(writeJson(state)); owner.setUpdateTime(clock.now());
        if (runtimeMapper.updateStoryStateCas(owner) != 1) throw error("GAME_STATE_CONFLICT", "本队状态已更新，请刷新");
        bumpSession(session, session.getStatus(), session.getCurrentChapterId(), actor.getMemberId(), clock.now());
        return result(session, sharedRoutePlayer(session, player.teamId,actor.getMemberId()));
    }

    private JsonNode configuredQuest(GameSession session, String key) { return sharedRunControl().configuredQuest(session, key); }

    private JsonNode currentRouteAct(GameSession session, TopicRouteEngine.State state) {
        JsonNode act = routeGameplay(session).path("acts").path(state.getThemeRuntime().path("gameplay").path("actIndex").asInt());
        if (!act.isObject()) throw error("GAME_ROUTE_FINISHED", "本队任务已完成");
        if (state.getThemeRuntime().path("gameplay").path("awaitingUnlock").asBoolean()) throw error("GAME_ROUTE_LOCKED", "等待主理人开放下一幕");
        return act;
    }

    private void requireEligibleQuest(GameSession session, TopicRouteEngine.State state, JsonNode quest) {
        try { taskEngine.eligible(sessionConfig(session).path("themeConfig"), state, quest); }
        catch (IllegalArgumentException | IllegalStateException locked) { throw error("GAME_ROUTE_LOCKED", locked.getMessage()); }
    }

    private void requirePlanNodeEligible(GameSession session, TopicRouteEngine.State state, JsonNode plan) {
        if (plan.path("terminal").asBoolean(true)) return;
        for (JsonNode quest : routeGameplay(session).path("quests")) if (quest.path("nodeId").asLong() == plan.path("operationNodeId").asLong()) {
            requireEligibleQuest(session, state, quest); return;
        }
        throw error("GAME_ROUTE_LOCKED", "投票工具尚未到达当前任务");
    }

    private void requireTemplateVoteSettled(GameSession session, JsonNode quest) {
        for (JsonNode source : routeGameplay(session).path("templateTools").path("nodes")) {
            if (source.path("nodeId").asLong() != quest.path("nodeId").asLong() || source.path("planKeys").isEmpty()) continue;
            JsonNode executed = cooperationScope(session, false).world.getThemeRuntime().path("cooperation").path("executedPlans");
            for (JsonNode key : source.path("planKeys")) if (executed.has(key.asText())) return;
            throw error("GAME_TEMPLATE_VOTE_REQUIRED", "本玩法须完成实际投票与方案执行后结算");
        }
    }

    private void requireRoutePaidCostAllowed(GameSession session, TopicRouteEngine.State entry, JsonNode costConfig) {
        JourneyRules rules = routeRules(session);
        String policy = routeGameplay(session).path("survival").path("zeroHpPolicy").asText();
        if (!rules.hasRuntimeState() || !rules.getHp().isEnabled() || entry.getVariables().getOrDefault("sys.hp", 0).intValue() > 0
                || !Arrays.asList("BLOCK_PAID", "BLOCK_HP_COST", "CHALLENGE", "WAIT", "STEPS", "THEME_FAIL").contains(policy)) return;
        if (!"BLOCK_PAID".equals(policy)) {
            if (themeEngine.reduceHpCost(sessionConfig(session).path("themeConfig"), entry, costConfig.path("hpCost").asInt()) > 0) throw error("GAME_HP_REQUIRED", "体力为零时不能承担体力费用");
            return;
        }
        boolean paid = costConfig.path("hpCost").asInt() > 0 || costConfig.path("luckCost").asInt() > 0 || costConfig.path("actionCost").asInt() > 0;
        for (JsonNode cost : costConfig.path("statCosts")) paid |= cost.path("amount").asInt() > 0;
        for (JsonNode cost : costConfig.path("resourceCosts")) paid |= cost.path("quantity").asInt() > 0;
        if (paid) throw error("GAME_HP_REQUIRED", "体力为零时不能开始或结算付费任务，可以恢复或参与免费任务");
    }

    private boolean routeFacilityOccupied(GameSession session, JsonNode quest) {
        if (!quest.has("facilityKey") || !"WORLD_SHARED".equals(GameplayRuntimeConfig.getWorldMode(sessionConfig(session).path("themeConfig")))) return false;
        JsonNode facility = configuredCooperation(session, "facilities", "facilityKey", quest.path("facilityKey").asText());
        if (!"WORLD".equals(facility.path("scope").asText())) return false;
        CooperationScope scope = cooperationScope(session, false);
        return scope.world.getThemeRuntime().path("cooperation").path("facilities").path(facility.path("settlementSlot").asText()).path("count").asInt() >= facility.path("limit").asInt();
    }

    private ActionResult routeQuestAction(GameActorContext actor, CmsActivity activity, GameSession session,
                                          GameSessionCommand command, Long receiptId) {
        requireSessionStatus(session, GameSession.RUNNING);
        runPayload(command, "questKey", "choiceKey", "discardRewards", "previewVersion", "costOption");
        if (!(command.getPayload().get("questKey") instanceof String)) throw error("GAME_BAD_REQUEST", "任务动作参数无效");
        JsonNode quest = configuredQuest(session, (String)command.getPayload().get("questKey"));
        PlayerScope player = requirePlayer(actor, activity);
        if (runControl(session).path("exitedTeams").has(String.valueOf(player.teamId))) throw error("GAME_TEAM_EXITED", "本队已退出本局");
        GameStoryState owner = requireRouteOwner(session, player.teamId); TopicRouteEngine.State state = routeOwnerState(owner);
        if (command.getAuthorityEpoch() == null || command.getAuthorityEpoch() != runEpoch(session)) throw error("GAME_AUTHORITY_CONFLICT", "本局权威版本已更新");
        String slot = quest.path("settlementSlot").asText(); JsonNode prior = state.getThemeRuntime().path("gameplay").path("slots").path(slot);
        String choice = command.getPayload().containsKey("choiceKey") ? text(command.getPayload().get("choiceKey"), 64) : null;
        if (command.getPayload().containsKey("choiceKey") && !(command.getPayload().get("choiceKey") instanceof String)) throw error("GAME_BAD_REQUEST", "配包选项无效");
        JsonNode discarded = command.getPayload().containsKey("discardRewards") ? objectMapper.valueToTree(command.getPayload().get("discardRewards")) : objectMapper.createArrayNode();
        if (command.getPayload().containsKey("previewVersion") && !(command.getPayload().get("previewVersion") instanceof String)) throw error("GAME_BAD_REQUEST", "装载预览版本无效");
        String previewVersion = text(command.getPayload().get("previewVersion"), 64);
        Integer costOption=commandCostOption(command.getPayload());
        Integer selected=costOption!=null?costOption:state.getThemeRuntime().path("gameplay").path("activeQuestCostOption").isInt()?Integer.valueOf(state.getThemeRuntime().path("gameplay").path("activeQuestCostOption").asInt()):prior.path("costOption").isInt()?Integer.valueOf(prior.path("costOption").asInt()):null;
        ObjectNode questCosts;
        try {questCosts=taskEngine.costs(quest,quest.has("costOptions")?selected:null);}
        catch(IllegalArgumentException invalid){throw error("GAME_BAD_REQUEST",invalid.getMessage());}
        if (prior.isObject()) {
            try { taskEngine.execute(sessionConfig(session).path("themeConfig"), routeRules(session), state, quest.path("questKey").asText(), command.getAction(), choice, "receipt:" + receiptId, "TEAM:" + player.teamId, evaluationTime().getTime(), discarded, previewVersion,costOption); }
            catch (IllegalArgumentException | IllegalStateException invalid) { throw error("GAME_SLOT_COMPLETED", invalid.getMessage()); }
            return result(session, publicRouteSettlement(session, prior));
        }
        requireEligibleQuest(session, state, quest);
        if ("QUEST_RESOLVE".equals(command.getAction())) requireTemplateVoteSettled(session, quest);
        if (state.getThemeRuntime().has("activeOperation")) throw error("GAME_PUBLIC_OPERATION_LOCKED", "请先取消或完成当前操作题组");
        if (!"QUEST_CANCEL".equals(command.getAction())) {
            boolean lostFacility = routeFacilityOccupied(session, quest);
            if (!lostFacility) requireRoutePaidCostAllowed(session, state, questCosts);
            requireNoCooperationSpend(session, player.teamId, state, lostFacility ? objectMapper.createArrayNode() : questCosts.path("resourceCosts"), true);
            GameStationRun station = requirePlayableStation(activity, session, bindingNode(session, quest));
            if ("HOST_NODE".equals(station.getSourceType())) requireHostArrival(session, player.teamId, quest, station);
            else requireArrived(actor, activity, station);
        }
        boolean freshStart = "QUEST_START".equals(command.getAction()) && !state.getThemeRuntime().path("gameplay").has("activeQuest");
        if (freshStart) {
            state.getThemeRuntime().with("verifiedNodes").remove(quest.path("nodeId").asText());
            state.getThemeRuntime().with("gameplay").with("pendingTaskEffects").remove(quest.path("questKey").asText());
        }
        ObjectNode applied;
        try {
            if (quest.has("facilityKey") && "WORLD_SHARED".equals(GameplayRuntimeConfig.getWorldMode(sessionConfig(session).path("themeConfig")))) {
                CooperationScope scope = cooperationScope(session, true);
                JsonNode facility = configuredCooperation(session, "facilities", "facilityKey", quest.path("facilityKey").asText());
                if ("QUEST_RESOLVE".equals(command.getAction()) && facility.has("operationNodeId")) requireOperationFact(session, scope, player.teamId, "FACILITY", facility.path("facilityKey").asText());
                // 本队状态替换为当前命令读取的状态，但保留范围内刚投影的已核验到达（要求到达的设施要看效果对象含本队）
                TopicRouteEngine.State projected = scope.teams.get(player.teamId);
                if (projected != null && projected.getThemeRuntime().has("hostArrivals")) state.getThemeRuntime().set("hostArrivals", projected.getThemeRuntime().get("hostArrivals").deepCopy());
                scope.teams.put(player.teamId, state);
                applied = cooperationEngine.executeQuest(sessionConfig(session).path("themeConfig"), routeRules(session), scope.world, scope.teams,
                        scope.leaders, player.teamId, actor.getMemberId(), quest.path("questKey").asText(), command.getAction(), choice,
                        "receipt:" + receiptId, "TEAM:" + player.teamId, evaluationTime().getTime(), discarded, previewVersion,costOption);
                state = scope.teams.get(player.teamId); persistCooperation(scope);
                owner = requireRouteOwner(session, player.teamId);
            } else if (quest.has("facilityKey")) {
                JsonNode facility = configuredCooperation(session, "facilities", "facilityKey", quest.path("facilityKey").asText());
                if ("QUEST_RESOLVE".equals(command.getAction()) && facility.has("operationNodeId")) requireOperationFact(session, state, "FACILITY", facility.path("facilityKey").asText(), null);
                applied = cooperationEngine.ownerQuest(sessionConfig(session).path("themeConfig"), routeRules(session), state, player.teamId, quest.path("questKey").asText(), command.getAction(), choice, "receipt:" + receiptId, "TEAM:" + player.teamId, evaluationTime().getTime(), discarded, previewVersion,costOption);
            } else applied = taskEngine.execute(sessionConfig(session).path("themeConfig"), routeRules(session), state, quest.path("questKey").asText(),
                    command.getAction(), choice, "receipt:" + receiptId, "TEAM:" + player.teamId, evaluationTime().getTime(), discarded, previewVersion,costOption);
        } catch (IllegalArgumentException | IllegalStateException invalid) { throw error("GAME_TASK_COST_UNAVAILABLE", invalid.getMessage()); }
        if (state.getThemeRuntime().path("gameplay").path("awaitingUnlock").asBoolean()) {
            String actKey = routeGameplay(session).path("acts").path(state.getThemeRuntime().path("gameplay").path("actIndex").asInt()).path("actKey").asText();
            if (runControl(session).path("openedActs").has(actKey)) { state.getThemeRuntime().with("gameplay").put("awaitingUnlock", false); refreshRouteQuestNodes(session, state); }
        }
        if (freshStart && state.getThemeRuntime().path("gameplay").has("activeQuest")) {
            state.getThemeRuntime().with("gameplay").put("activeQuestReceiptId", receiptId);
            bindBasicArrivalProof(session, state, quest, actor.getMemberId());
        }
        if (!state.getThemeRuntime().path("gameplay").has("activeQuest")) {
            state.getThemeRuntime().with("gameplay").remove("activeQuestReceiptId");
            state.getThemeRuntime().with("gameplay").with("pendingTaskEffects").remove(quest.path("questKey").asText());
        }
        ArrayNode validateLocks = objectMapper.createArrayNode();
        for (JsonNode item : sessionConfig(session).path("themeConfig").path("items")) validateLocks.addObject().put("itemId", item.path("id").asText()).put("quantity", 0);
        requireNoCooperationSpend(session, player.teamId, state, validateLocks, false);
        if ("QUEST_RESOLVE".equals(command.getAction()) && !applied.path("competitionLost").asBoolean() && !"BLOCKED_COST".equals(applied.path("status").asText())) {
            applied.put("receiptId", receiptId).put("actorMemberId", actor.getMemberId());
            state.getThemeRuntime().with("gameplay").with("slots").set(slot, applied.deepCopy());
            if (state.getThemeRuntime().has("result") && !"WORLD_SHARED".equals(GameplayRuntimeConfig.getWorldMode(sessionConfig(session).path("themeConfig")))) {
                owner.setEndingCode(state.getThemeRuntime().path("result").path("endingId").asText(null)); owner.setStatus("ENDING_RESOLVED");
            }
        }
        saveRouteOwner(session, owner, state); bumpSession(session, session.getStatus(), session.getCurrentChapterId(), actor.getMemberId(), clock.now());
        return result(session, publicRouteSettlement(session, applied));
    }

    private ActionResult routeZeroHpSync(GameActorContext actor,CmsActivity activity,GameSession session,GameSessionCommand command) {
        requireSessionStatus(session,GameSession.RUNNING);requireOnlineRun(runControl(session));
        PlayerScope player=requirePlayer(actor,activity);GameStoryState owner=requireRouteOwner(session,player.teamId);TopicRouteEngine.State state=routeOwnerState(owner);requireNaturalPlayOpen(session,state);
        if("ZERO_HP_STEPS".equals(command.getAction())) {
            runPayload(command,"weRun");JsonNode cipher=objectMapper.valueToTree(command.getPayload().get("weRun"));
            if(!cipher.path("episodeId").isTextual() || cipher.path("episodeId").asText().isEmpty() || cipher.path("episodeId").asText().length()>128)throw error("GAME_BAD_REQUEST","请指定当前归零实例");
            long bound=state.getThemeRuntime().path("zeroHp").path("recoveryMemberId").asLong();boolean present=bound>0 && playTeamMapper.selectActiveMember(player.teamId,bound)!=null;
            if(!present)requireRouteLeader(actor.getMemberId(),activity.getId(),player.teamId);
            if(present && bound!=actor.getMemberId().longValue())throw error("GAME_FORBIDDEN_SCOPE","请由建立基线的队员同步微信运动");
            survivalEngine.syncZeroHpSteps(sessionConfig(session),routeRules(session),state,cipher.path("episodeId").asText(),actor.getMemberId(),verifiedWeRunService.verify(actor.getMemberId(),cipher,clock.now().getTime()),!present,present,clock.now().getTime());
        } else {runPayload(command);survivalEngine.syncZeroHp(sessionConfig(session),routeRules(session),state,clock.now().getTime());}
        saveRouteOwner(session,owner,state);bumpSession(session,session.getStatus(),session.getCurrentChapterId(),actor.getMemberId(),clock.now());return result(session,singleton("zeroHp",sharedZeroHpView(session,player.teamId,actor.getMemberId(),state)));
    }

    private ActionResult routeRecover(GameActorContext actor, CmsActivity activity, GameSession session, GameSessionCommand command, Long receiptId) {
        runPayload(command, "recoveryKey"); requireSessionStatus(session, GameSession.RUNNING); requireActivityServiceWindow(activity, evaluationTime());
        PlayerScope player = requirePlayer(actor, activity); GameStoryState owner = requireRouteOwner(session, player.teamId); TopicRouteEngine.State state = routeOwnerState(owner);
        if (runControl(session).path("exitedTeams").has(String.valueOf(player.teamId))) throw error("GAME_TEAM_EXITED", "本队已退出");
        requireNoCooperationSpend(session, player.teamId, state, objectMapper.createArrayNode(), true);
        String key = text(command.getPayload().get("recoveryKey"), 64);
        ObjectNode recovered;
        try { recovered = survivalEngine.recover(sessionConfig(session).path("themeConfig"), routeRules(session), state, key, "receipt:" + receiptId); }
        catch (IllegalArgumentException | IllegalStateException unavailable) { throw error("GAME_RECOVERY_UNAVAILABLE", unavailable.getMessage()); }
        saveRouteOwner(session, owner, state); bumpSession(session, session.getStatus(), session.getCurrentChapterId(), actor.getMemberId(), clock.now());
        return result(session, publicRouteSettlement(session, recovered));
    }

    private void refreshRouteQuestNodes(GameSession session, TopicRouteEngine.State state) { sharedRunControl().refreshRouteQuestNodes(session, state); }

    private ObjectNode routeQuestCosts(JsonNode quest) { return taskEngine.costs(quest); }
    private Integer commandCostOption(Map<String,Object> payload) {
        if(!payload.containsKey("costOption"))return null;
        JsonNode value=objectMapper.valueToTree(payload.get("costOption"));
        if(!value.isIntegralNumber() || !value.canConvertToInt())throw error("GAME_BAD_REQUEST","任选费用组下标须为整数");
        return value.asInt();
    }
    private ObjectNode facilityPayload(TopicRouteEngine.State state,Map<String,Object> payload) {
        ObjectNode result=objectMapper.valueToTree(payload);Integer requested=commandCostOption(payload);
        JsonNode operation=state.getThemeRuntime().path("activeOperation");
        if("FACILITY".equals(operation.path("operationKind").asText()) && operation.path("operationKey").asText().equals(result.path("facilityKey").asText()) && operation.path("costOption").isInt()) {
            int frozen=operation.path("costOption").asInt();
            if(requested!=null && requested!=frozen)throw error("GAME_BAD_REQUEST","设施操作实例不能更换费用组");
            result.put("costOption",frozen);
        }
        return result;
    }

    private Map<String, Object> publicRouteSettlement(GameSession session, JsonNode receipt) {
        JsonNode view = GameplayCooperationEngine.publicResult(receipt);
        JourneyInitialState.redactHidden(routeRules(session), view);
        return objectMapper.convertValue(view, Map.class);
    }

    private void initializeRouteOwner(GameSession session, String type, Long id) {
        JsonNode snapshot = sessionConfig(session);
        JourneyRules rules = routeRules(session);
        TopicRouteEngine.State state = topicRouteEngine.initial(snapshot.toString());
        if (rules.hasRuntimeState()) {
            if (rules.getHp().isEnabled()) state.getVariables().put("sys.hp", rules.getHp().getInit());
            if (rules.getLuck().isEnabled()) state.getVariables().put("sys.luck", rules.getLuck().getInit());
        }
        JourneyInitialState.initialize(rules, state);
        JsonNode config = snapshot.path("themeConfig");
        themeEngine.initialize(config, state);
        themeEngine.events(config, state, rules);
        for (JsonNode chapter : config.path("chapters")) {
            for (JsonNode nodeId : chapter.path("binding").path("nodeIds")) {
                if (state.getCurrentNodeId().equals(nodeId.asLong())) {
                    themeEngine.chapterStart(config, state, rules, chapter, clock.now().getTime());
                    break;
                }
            }
        }
        if (routeGameplay(session).path("initialGrants").isArray()) {
            themeEngine.grantAll(config, state, rules, "initial", routeGameplay(session).path("initialGrants"));
        }
        state.getThemeRuntime().put("authorityEpoch", 1);
        taskEngine.initialize(config, rules, state, clock.now().getTime());
        if ("TEAM".equals(type)) survivalEngine.syncChallenge(snapshot, rules, state, clock.now().getTime());
        GameStoryState owner = new GameStoryState();
        owner.setGameSessionId(session.getId()); owner.setOwnerType(type); owner.setOwnerId(id);
        owner.setVariablesJson(writeJson(state)); owner.setAppliedChoicesJson("[]");
        owner.setStatus("INITIAL"); owner.setRevision(0);
        owner.setCreateTime(clock.now()); owner.setUpdateTime(clock.now());
        if (runtimeMapper.insertStoryState(owner) != 1) throw error("GAME_STATE_CONFLICT", "队伍状态初始化失败");
    }

    private GameStoryState requireRouteOwner(GameSession session, Long teamId) { return sharedRunControl().requireRouteOwner(session, teamId); }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public JsonNode sharedRouteSnapshot(Long memberId, Long topicId, Long activityId, Long nodeId) {
        if (activityId == null || activityId <= 0) return null;
        GameSession session = runtimeMapper.selectSessionByActivity(activityId);
        if (!isSharedRoute(session)) return null;
        if (!Objects.equals(topicId, session.getTopicId())) throw error("GAME_FORBIDDEN_SCOPE", "活动与主题不匹配");
        CmsActivity activity = requireActivity(activityId, true);
        PlayerScope player = requirePlayer(GameActorContext.player(memberId), activity);
        session=refreshZeroHpRead(session,player.teamId,memberId);
        GameStoryState owner = requireRouteOwner(session, player.teamId);
        if (nodeId != null) {
            requireSessionStatus(session, GameSession.RUNNING);
            TopicRouteEngine.State state = routeOwnerState(owner);
            if (!activeChallenge(session, state, nodeId)) requirePlayableStation(activity, session, nodeId);
            boolean configured = activeChallenge(session, state, nodeId) || state.getThemeRuntime().path("activeOperation").path("nodeId").asLong() == nodeId;
            for (JsonNode quest : routeGameplay(session).path("quests")) {
                if (configured) break;
                if (bindingNode(session, quest) == nodeId && (state.getThemeRuntime().path("gameplay").path("activeQuest").asText("").isEmpty() || quest.path("questKey").asText().equals(state.getThemeRuntime().path("gameplay").path("activeQuest").asText()))) {
                    requireEligibleQuest(session, state, quest); configured = true; break;
                }
            }
            if (!configured && (routeGameplay(session).path("quests").size() > 0 || !Objects.equals(nodeId, state.getCurrentNodeId()))) throw error("GAME_ROUTE_LOCKED", "尚未到达该任务");
        }
        ObjectNode snapshot = (ObjectNode) sessionConfig(session).deepCopy();
        snapshot.put("gameSessionId", session.getId());
        snapshot.put("teamId", player.teamId);
        snapshot.put("authorityEpoch", runEpoch(session));
        snapshot.put("runtimeInstance", sharedInstance(session, routeOwnerState(owner), nodeId));
        if (activeChallenge(session, routeOwnerState(owner), nodeId)) snapshot.set("challenge", survivalEngine.challengeView(snapshot.path("themeConfig"),routeOwnerState(owner)));
        Long selectedOriginal = null;
        if (nodeId != null && !activeChallenge(session, routeOwnerState(owner), nodeId) && (routeGameplay(session).path("quests").size() > 0 || routeOwnerState(owner).getThemeRuntime().path("activeOperation").isObject())) {
            JsonNode selected = basicArrivalTarget(session, routeOwnerState(owner), nodeId);
            selectedOriginal = selected.has("operationKind") ? selected.path("sourceNodeId").asLong() : selected.path("nodeId").asLong();
        }
        for (JsonNode quest : routeGameplay(session).path("quests")) {
            long actual = bindingNode(session, quest), original = quest.path("nodeId").asLong();
            if (actual != original && snapshot.path("nodeConfigs").has(String.valueOf(original))
                    && (nodeId == null || selectedOriginal != null && selectedOriginal == original)) {
                snapshot.with("nodeConfigs").set(String.valueOf(actual), snapshot.path("nodeConfigs").path(String.valueOf(original)).deepCopy());
                for (String frozenField : Arrays.asList("nodeAnswers", "nodeJourney")) if (snapshot.path(frozenField).has(String.valueOf(original))) snapshot.with(frozenField).set(String.valueOf(actual), snapshot.path(frozenField).path(String.valueOf(original)).deepCopy());
            }
        }
        JsonNode operation = routeOwnerState(owner).getThemeRuntime().path("activeOperation");
        if (operation.isObject() && operation.path("nodeId").asLong() != operation.path("sourceNodeId").asLong()
                && snapshot.path("nodeConfigs").has(operation.path("sourceNodeId").asText())) {
            snapshot.with("nodeConfigs").set(operation.path("nodeId").asText(), snapshot.path("nodeConfigs").path(operation.path("sourceNodeId").asText()).deepCopy());
            for (String frozenField : Arrays.asList("nodeAnswers", "nodeJourney")) if (snapshot.path(frozenField).has(operation.path("sourceNodeId").asText())) snapshot.with(frozenField).set(operation.path("nodeId").asText(), snapshot.path(frozenField).path(operation.path("sourceNodeId").asText()).deepCopy());
        }
        return snapshot;
    }

    private GameSession refreshZeroHpRead(GameSession session,Long teamId,Long memberId) {
        JsonNode survival=routeGameplay(session).path("survival");String policy=survival.path("zeroHpPolicy").asText();
        if(!GameSession.RUNNING.equals(session.getStatus()) || !(Arrays.asList("THEME_FAIL","WAIT","STEPS").contains(policy) || "CHALLENGE".equals(policy) && survival.path("zeroChallenge").path("version").asInt()==2))return session;
        if(runControl(session).path("exitedTeams").has(String.valueOf(teamId)))return session;
        if(Arrays.asList("CANCELLED","INTERRUPTED").contains(runControl(session).path("operationStatus").asText()))return session;
        GameSession locked=runtimeMapper.selectSessionByActivityForUpdate(session.getActivityId());
        if(locked==null || !Objects.equals(locked.getId(),session.getId()))throw error("GAME_STATE_CONFLICT","Play-through changed");
        GameStoryState owner=requireRouteOwner(locked,teamId);TopicRouteEngine.State state=routeOwnerState(owner);String before=writeJson(state);
        survivalEngine.syncChallenge(sessionConfig(locked),routeRules(locked),state,survivalTime(locked));
        if(!before.equals(writeJson(state))) {
            themeEngine.reconcileHeld(sessionConfig(locked).path("themeConfig"),state,routeRules(locked));JourneyInitialState.clamp(routeRules(locked),state);
            saveRouteOwner(locked,owner,state);bumpSession(locked,locked.getStatus(),locked.getCurrentChapterId(),memberId,clock.now());
        }
        return locked;
    }

    private JsonNode basicArrivalTarget(GameSession session, TopicRouteEngine.State state, Long nodeId) {
        JsonNode operation = state.getThemeRuntime().path("activeOperation");
        if (operation.isObject() && operation.path("nodeId").asLong() == nodeId) return operation;
        String active = state.getThemeRuntime().path("gameplay").path("activeQuest").asText("");
        for (JsonNode quest : routeGameplay(session).path("quests")) if (bindingNode(session, quest) == nodeId
                && (active.isEmpty() || active.equals(quest.path("questKey").asText()))) {
            requireEligibleQuest(session, state, quest); return quest;
        }
        throw error("GAME_ROUTE_LOCKED", "该基础到达不属于当前任务或操作");
    }

    private void bindBasicArrivalProof(GameSession session, TopicRouteEngine.State state, JsonNode target, Long memberId) {
        long actual = target.has("operationKind") ? target.path("nodeId").asLong() : bindingNode(session, target);
        long original = target.has("operationKind") ? target.path("sourceNodeId").asLong(actual) : target.path("nodeId").asLong();
        JsonNode fact = state.getThemeRuntime().path("gameplay").path("arrivalFacts").path(String.valueOf(actual));
        String instance = sharedInstance(session, state, actual);
        if (!fact.isObject() || fact.path("sourceNodeId").asLong(-1) != original || fact.path("epoch").asInt() != runEpoch(session) || fact.path("bindingVersion").asLong() != bindingVersion(session, target)
                || fact.path("expiresAt").asLong() <= evaluationTime().getTime() || fact.has("consumedBy")) return;
        JsonNode snapshot = sessionConfig(session), answer = snapshot.path("nodeAnswers").path(String.valueOf(original));
        int vm = answer.path("validationMethod").asInt(-1);
        if (!(vm == 4 && "SCAN".equals(fact.path("kind").asText()) || vm == 5 && "GPS".equals(fact.path("kind").asText()) || (vm == 1 || vm == 3) && "ANSWER".equals(fact.path("kind").asText()))) return;
        JsonNode config = snapshot.path("nodeConfigs").path(String.valueOf(original));
        if (!config.isObject()) return;
        Iterator<Map.Entry<String,JsonNode>> tools = config.fields();
        while (tools.hasNext()) {
            Map.Entry<String,JsonNode> tool=tools.next();
            if (tool.getValue().path("enabled").asBoolean() && !"scan".equals(tool.getKey()) && !GameplaySupportMatrix.addon(tool.getKey())) return;
        }
        if (!target.has("operationKind") && !target.path("questKey").asText().equals(state.getThemeRuntime().path("gameplay").path("activeQuest").asText())) return;
        ArrayNode effects = TrustedNodeEffects.rootEffects(snapshot, original);
        if (target.has("operationKind") && "PLAN".equals(target.path("operationKind").asText()) && effects.size() > 0 && !jointQuestOperation(session,state,target)) throw error("GAME_PUBLIC_OPERATION_EFFECT", "最终操作题只能输出可信判定");
        long receipt = fact.path("receiptId").asLong();
        state.getThemeRuntime().with("verifiedNodes").put(String.valueOf(original), receipt).put(String.valueOf(actual), receipt);
        if (target.has("operationKind")) {
            ObjectNode proof = (ObjectNode)target.deepCopy(); proof.put("verifiedReceiptId", receipt); proof.set("effects", effects); proof.putObject("sources").put("basic:" + instance, true);
            if (jointQuestOperation(session, state, target)) {
                ObjectNode pending = state.getThemeRuntime().with("gameplay").with("pendingTaskEffects").putObject(state.getThemeRuntime().path("gameplay").path("activeQuest").asText());
                pending.set("effects", effects.deepCopy()); pending.putObject("sources").put("basic:" + instance, true); proof.set("effects", objectMapper.createArrayNode());
            }
            state.getThemeRuntime().with("operationFacts").set(target.path("operationKind").asText() + ":" + target.path("operationKey").asText(), proof);
        } else {
            ObjectNode pending = state.getThemeRuntime().with("gameplay").with("pendingTaskEffects").putObject(target.path("questKey").asText());
            pending.set("effects", effects); pending.putObject("sources").put("basic:" + instance, true);
            recordQuestFacilityFact(session, state, target, receipt, memberId);
        }
        ((ObjectNode)fact).put("consumedBy", instance);
    }

    /** Only called after the existing live scan/GPS verifier has accepted the actual actor. */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> recordSharedBasicArrival(Long memberId, Long topicId, Long activityId, Long nodeId,
                                                       String actionId, String kind, String instance, Integer authorityEpoch) {
        GameSession session = runtimeMapper.selectSessionByActivityForUpdate(activityId);
        if (!isSharedRoute(session) || !Objects.equals(topicId, session.getTopicId())) throw error("GAME_FORBIDDEN_SCOPE", "基础到达不属于当前共享场次");
        requireSessionStatus(session, GameSession.RUNNING); requireOnlineRun(runControl(session));
        CmsActivity activity = requireActivity(activityId, true); PlayerScope player = requirePlayer(GameActorContext.player(memberId), activity);
        if (runControl(session).path("exitedTeams").has(String.valueOf(player.teamId))) throw error("GAME_TEAM_EXITED", "本队已退出");
        if (authorityEpoch == null || authorityEpoch != runEpoch(session)) throw error("GAME_AUTHORITY_CONFLICT", "到达来自旧权威版本");
        if (!Arrays.asList("SCAN", "GPS", "ANSWER").contains(kind) || actionId == null || !actionId.matches("[A-Za-z0-9:_-]{1,80}")) throw error("GAME_BAD_REQUEST", "基础到达标识无效");
        GameStoryState owner = requireRouteOwner(session, player.teamId); TopicRouteEngine.State state = routeOwnerState(owner);
        requireNaturalPlayOpen(session, state);
        if (!sharedInstance(session, state, nodeId).equals(instance)) throw error("GAME_STATE_CONFLICT", "到达不属于当前实例");
        JsonNode target = basicArrivalTarget(session, state, nodeId);
        long original = target.has("operationKind") ? target.path("sourceNodeId").asLong(nodeId) : target.path("nodeId").asLong();
        if (!sessionConfig(session).path("nodeAnswers").path(String.valueOf(original)).isObject()) throw error("GAME_CONFIG_INVALID", "基础验证方式没有冻结");
        GameStationRun station = requirePlayableStation(activity, session, nodeId);
        GameSessionCommand command = new GameSessionCommand(); command.setActivityId(activityId); command.setNodeId(nodeId); command.setAction("TRUSTED_BASIC_ARRIVAL"); command.setAuthorityEpoch(authorityEpoch);
        command.getPayload().put("sourceActionId", actionId); command.getPayload().put("kind", kind); command.getPayload().put("instance", instance);
        String hash = requestHash(command); command.setRequestId("basic:" + digest(memberId + ":" + actionId).substring(0,48)); command.setRequestHash(hash);
        GameCommandReceipt prior = runtimeMapper.selectReceiptForUpdate(activityId, memberId, command.getRequestId());
        if (prior != null) { authorizeSharedReceipt(GameActorContext.player(memberId), prior); return replay(prior, hash).getResult(); }
        GameCommandReceipt claim = receiptClaim(GameActorContext.player(memberId), command, hash, session);
        if (runtimeMapper.insertReceiptClaim(claim) != 1) throw error("GAME_STATE_CONFLICT", "到达正在提交，请重试");
        Map<String, JsonNode> balancesBefore = routeBalances(session);
        long at = clock.now().getTime(), expires = Math.min(at + routeGameplay(session).path("arrivalTtlSeconds").asLong(300) * 1000L, activity.getEndDate().getTime());
        state.getThemeRuntime().with("gameplay").with("arrivalFacts").putObject(String.valueOf(nodeId)).put("kind", kind).put("receiptId", claim.getId()).put("arrivedAt", at)
                .put("sourceNodeId", original).put("epoch", authorityEpoch).put("bindingVersion", bindingVersion(session,target)).put("expiresAt", expires);
        if (!"ANSWER".equals(kind)) {
            ObjectNode run = runControl(session);run.with("arrivals").putObject(arrivalKey(player.teamId,target.path("locationKey").asText())).put("used",true).put("sessionId",session.getId()).put("teamId",player.teamId)
                    .put("nodeId",nodeId).put("version",bindingVersion(session,target)).put("epoch",authorityEpoch).put("verifiedAt",at).put("expiresAt",expires).put("verifiedBy",memberId).put("basicReceiptId",claim.getId());saveRun(session,run);
        }
        bindBasicArrivalProof(session, state, target, memberId); saveRouteOwner(session, owner,state); bumpSession(session,session.getStatus(),session.getCurrentChapterId(),memberId,clock.now()); appendRunEvent(session,GameActorContext.player(memberId),command,claim.getId(),balancesBefore);
        Map<String,Object> result = new LinkedHashMap<>();result.put("teamId",player.teamId);result.put("receiptId",claim.getId());result.put("runtimeInstance",sharedInstance(session,state,nodeId));result.put("authorityEpoch",authorityEpoch);result.put("basicVerified",state.getThemeRuntime().path("gameplay").path("arrivalFacts").path(String.valueOf(nodeId)).has("consumedBy"));
        claim.setOutcome("APPLIED");claim.setRevision(session.getRevision());claim.setResultJson(writeJson(result));claim.setUpdateTime(clock.now());if(runtimeMapper.completeReceipt(claim)!=1)throw error("GAME_STATE_CONFLICT","基础到达回执保存失败");return result;
    }

    @Override
    public com.chengyinhub.business.domain.route.TopicRouteState querySharedRouteState(Long memberId, Long topicId, Long activityId) {
        if (activityId == null || activityId <= 0) return null;
        GameSession session = runtimeMapper.selectSessionByActivity(activityId);
        if (!isSharedRoute(session)) return null;
        if (!Objects.equals(topicId, session.getTopicId())) throw error("GAME_FORBIDDEN_SCOPE", "活动与主题不匹配");
        CmsActivity activity = requireActivity(activityId, true);
        PlayerScope player = requirePlayer(GameActorContext.player(memberId), activity);
        if (sharedRunControl().persistedRunControl(session) == null) throw error("GAME_CONFIG_INVALID", "本局共享运行控制账不可用");
        TopicRouteEngine.State state = routeOwnerState(requireRouteOwner(session, player.teamId));
        return projectSharedRouteState(session, sessionConfig(session), state);
    }

    @Override
    @Transactional(readOnly = true)
    public JsonNode querySharedRouteSnapshot(Long memberId, Long topicId, Long activityId, Long nodeId) {
        if (activityId == null || activityId <= 0) return null;
        GameSession session = runtimeMapper.selectSessionByActivity(activityId);
        if (!isSharedRoute(session)) return null;
        if (!Objects.equals(topicId, session.getTopicId())) throw error("GAME_FORBIDDEN_SCOPE", "活动与主题不匹配");
        CmsActivity activity = requireActivity(activityId, true);
        PlayerScope player = requirePlayer(GameActorContext.player(memberId), activity);
        ObjectNode control = sharedRunControl().persistedRunControl(session);
        if (control == null) throw error("GAME_CONFIG_INVALID", "本局共享运行控制账不可用");
        int epoch = control.path("authorityEpoch").asInt(1);
        TopicRouteEngine.State state = routeOwnerState(requireRouteOwner(session, player.teamId));
        ObjectNode original = (ObjectNode) sessionConfig(session).deepCopy();
        NodeJourneySnapshot.requireContentSnapshot(original);
        ObjectNode snapshot = original.deepCopy();
        snapshot.put("gameSessionId", session.getId()).put("teamId", player.teamId).put("authorityEpoch", epoch);
        snapshot.set("contentTags", objectMapper.valueToTree(state.getTags()));
        JsonNode operation = state.getThemeRuntime().path("activeOperation");
        java.util.Set<Long> targets = new java.util.LinkedHashSet<>();
        if (nodeId != null) {
            if (nodeId <= 0) throw error("GAME_BAD_REQUEST", "节点身份不可用");
            targets.add(nodeId);
        } else {
            // One request captures RUN, TEAM and the original content once, not one run per node.
            for (JsonNode quest : routeGameplay(session).path("quests"))
                targets.add(control.path("bindings").path(quest.path("locationKey").asText())
                        .path("nodeId").asLong(quest.path("nodeId").asLong()));
            if (operation.isObject()) targets.add(operation.path("nodeId").asLong());
        }
        for (Long target : targets) {
            if (activeChallenge(session, state, target)) continue;
            Long source = null;
            if (operation.isObject() && operation.path("nodeId").asLong() == target) {
                if (operation.has("epoch") && operation.path("epoch").asInt() != epoch)
                    throw error("GAME_STATE_CONFLICT", "原操作权威版本已变更");
                source = operation.path("sourceNodeId").asLong();
            } else {
                String active = state.getThemeRuntime().path("gameplay").path("activeQuest").asText("");
                java.util.Set<Long> candidates = new java.util.LinkedHashSet<>();
                for (JsonNode quest : routeGameplay(session).path("quests")) {
                    long actual = control.path("bindings").path(quest.path("locationKey").asText())
                            .path("nodeId").asLong(quest.path("nodeId").asLong());
                    if (actual != target) continue;
                    candidates.add(quest.path("nodeId").asLong());
                    if (quest.path("questKey").asText().equals(active)) source = quest.path("nodeId").asLong();
                }
                if (source == null && candidates.size() == 1) source = candidates.iterator().next();
                if (source == null && candidates.size() > 1) {
                    if (nodeId != null) throw error("GAME_ROUTE_LOCKED", "本站内容需要原任务身份");
                    // Whole-page read omits an ambiguous physical binding; it cannot choose a future source.
                    for (String field : Arrays.asList("nodeConfigs", "nodeAnswers", "nodeJourney"))
                        snapshot.with(field).remove(String.valueOf(target));
                    continue;
                }
            }
            if (source != null && !Objects.equals(source, target)) {
                if (source <= 0 || runtimeMapper.selectStation(session.getId(), target) == null)
                    throw error("GAME_FORBIDDEN_SCOPE", "内容映射不属于本场站点");
                for (String field : Arrays.asList("nodeConfigs", "nodeAnswers", "nodeJourney")) {
                    JsonNode block = original.path(field).get(String.valueOf(source));
                    if (block == null) snapshot.with(field).remove(String.valueOf(target));
                    else snapshot.with(field).set(String.valueOf(target), block.deepCopy());
                }
            }
            JsonNode content = snapshot.path("nodeAnswers").get(String.valueOf(target));
            if (NodeJourneySnapshot.hasContent(content)
                    && (content.path("nodePresentation").path("topicId").asLong() != topicId
                    || content.path("nodePresentation").path("id").asLong() != (source == null ? target : source)))
                throw error("GAME_CONFIG_INVALID", "本局内容归属不可用");
        }
        return snapshot;
    }

    @Override
    public com.chengyinhub.business.domain.route.TopicRouteState sharedRouteState(Long memberId, Long topicId, Long activityId) {
        JsonNode snapshot = sharedRouteSnapshot(memberId, topicId, activityId, null);
        if (snapshot == null) return null;
        GameSession session = runtimeMapper.selectSessionByActivity(activityId);
        PlayerScope player = requirePlayer(GameActorContext.player(memberId), requireActivity(activityId, false));
        TopicRouteEngine.State state = routeOwnerState(requireRouteOwner(session, player.teamId));
        return projectSharedRouteState(session, snapshot, state);
    }

    private com.chengyinhub.business.domain.route.TopicRouteState projectSharedRouteState(GameSession session,
            JsonNode snapshot, TopicRouteEngine.State state) {
        com.chengyinhub.business.domain.route.TopicRouteState view = new com.chengyinhub.business.domain.route.TopicRouteState();
        view.setSessionId(session.getId()); view.setVersion(session.getRevision());
        view.setRouteMode(snapshot.path("routeMode").asText("LINEAR")); view.setStatus(state.getStatus());
        view.setCurrentNodeId(state.getCurrentNodeId()); view.setRecommendedNodeId(state.getRecommendedNodeId());
        view.setNodeStates(state.getNodeStates()); view.setAttributes(JourneyInitialState.playerView(routeRules(session), state));
        return view;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordSharedRouteResult(Long memberId, Long topicId, Long activityId, Long nodeId, String instance,
                                        String actionId, Boolean passed, List<String[]> choices) {
        recordSharedRouteResult(memberId, topicId, activityId, nodeId, instance, actionId, passed, choices, 1);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordSharedRouteResult(Long memberId, Long topicId, Long activityId, Long nodeId, String instance,
                                        String actionId, Boolean passed, List<String[]> choices, Integer authorityEpoch) {
        recordSharedRouteResult(memberId,topicId,activityId,nodeId,instance,actionId,passed,choices,authorityEpoch,null,null);
    }
    @Override
    @Transactional(rollbackFor=Exception.class)
    public void assertSharedGameplayOutcomeStart(Long memberId,Long topicId,Long activityId,Long nodeId,String instance,Integer authorityEpoch) {
        assertSharedRouteWritable(memberId,topicId,activityId,nodeId,authorityEpoch);
        GameSession session=runtimeMapper.selectSessionByActivityForUpdate(activityId);JsonNode snapshot=sharedRouteSnapshot(memberId,topicId,activityId,nodeId);
        if(snapshot==null)throw error("GAME_FORBIDDEN_SCOPE","Outcome requires a frozen shared play-through");
        PlayerScope player=requirePlayer(GameActorContext.player(memberId),requireActivity(activityId,true));
        TopicRouteEngine.State state=routeOwnerState(requireRouteOwner(session,player.teamId));requireNaturalPlayOpen(session,state);
        if(!Objects.equals(instance,sharedInstance(session,state,nodeId)))throw error("GAME_STATE_CONFLICT","Attempt belongs to another instance");
        new com.chengyinhub.business.service.support.GameplayOutcomeEngine().assertPassAffordable(snapshot.path("nodeConfigs").path(String.valueOf(nodeId)),routeRules(session),state,snapshot.path("themeConfig"));
    }
    @Override
    @Transactional(rollbackFor=Exception.class)
    public com.chengyinhub.business.domain.route.PlayCheckReceipt rollSharedGameplayCheck(Long memberId,Long topicId,Long activityId,Long nodeId,String instance,String attemptId,boolean reroll,Integer authorityEpoch) {
        assertSharedRouteWritable(memberId,topicId,activityId,nodeId,authorityEpoch);
        GameSession session=runtimeMapper.selectSessionByActivityForUpdate(activityId);PlayerScope player=requirePlayer(GameActorContext.player(memberId),requireActivity(activityId,true));
        GameStoryState owner=requireRouteOwner(session,player.teamId);TopicRouteEngine.State state=routeOwnerState(owner);
        requireNaturalPlayOpen(session,state);if(!Objects.equals(instance,sharedInstance(session,state,nodeId)))throw error("GAME_STATE_CONFLICT","检定不属于当前实例");
        JsonNode snapshot=sharedRouteSnapshot(memberId,topicId,activityId,nodeId),block=snapshot.path("nodeConfigs").path(String.valueOf(nodeId));
        if(!block.has("outcomeRules"))throw error("GAME_CONFIG_INVALID","检定未启用结果规则");
        if (reroll) requireRouteLeader(memberId, activityId, player.teamId);
        com.chengyinhub.business.domain.route.PlayCheckReceipt receipt=com.chengyinhub.business.service.support.GameplayCheckEngine.rollAttempt(block.path("check"),routeRules(session),state,attemptId,reroll,()->java.util.concurrent.ThreadLocalRandom.current().nextInt(1,21));
        saveRouteOwner(session,owner,state);bumpSession(session,session.getStatus(),session.getCurrentChapterId(),memberId,clock.now());return receipt;
    }
    @Override @Transactional(rollbackFor=Exception.class)
    public ObjectNode sharedGameplayCheckActions(Long memberId,Long topicId,Long activityId,Long nodeId,String instance,String attemptId,Integer authorityEpoch) {
        assertSharedRouteWritable(memberId,topicId,activityId,nodeId,authorityEpoch);
        GameSession session=runtimeMapper.selectSessionByActivityForUpdate(activityId);PlayerScope player=requirePlayer(GameActorContext.player(memberId),requireActivity(activityId,true));
        TopicRouteEngine.State state=routeOwnerState(requireRouteOwner(session,player.teamId));
        requireNaturalPlayOpen(session,state);if(!Objects.equals(instance,sharedInstance(session,state,nodeId)))throw error("GAME_STATE_CONFLICT","检定不属于当前实例");
        JsonNode block=sharedRouteSnapshot(memberId,topicId,activityId,nodeId).path("nodeConfigs").path(String.valueOf(nodeId));
        ObjectNode view=com.chengyinhub.business.service.support.GameplayCheckEngine.actions(block.path("check"),routeRules(session),state,attemptId);
        ArrayNode allowed=(ArrayNode)view.get("allowedActions");
        for(int i=allowed.size()-1;i>=0;i--)if("REROLL_CHECK".equals(allowed.get(i).asText())) {
            try{requireRouteLeader(memberId,activityId,player.teamId);}catch(GameSessionException refused){allowed.remove(i);view.put("disabledReason",refused.getMessage());}
        }
        return view;
    }
    @Override @Transactional(rollbackFor=Exception.class)
    public boolean recordOriginalSharedGameplayOutcome(Long memberId,Long activityId,Long gameSessionId,Long teamId,Integer epoch,Long nodeId,String instance,String attemptId,JsonNode decision,List<String[]> choices) {
        GameSession session=runtimeMapper.selectSessionByActivityForUpdate(activityId);
        if(session==null || !Objects.equals(gameSessionId,session.getId()) || !isSharedRoute(session) || !GameSession.RUNNING.equals(session.getStatus()) || epoch==null || epoch!=runEpoch(session))return false;
        com.chengyinhub.business.domain.PlayTeam team=playTeamMapper.selectPlayTeamById(teamId);
        if(team==null || !Objects.equals(team.getOwnerId(),activityId) || playTeamMapper.selectActiveMember(teamId,memberId)==null)return false;
        TopicRouteEngine.State state=routeOwnerState(requireRouteOwner(session,teamId));
        if(naturallyCompleted(session,state) || state.getThemeRuntime().has("result") || !instance.equals(sharedInstance(session,state,nodeId)))return false;
        CmsActivity activity=activityMapper.selectCmsActivityById(activityId);
        if(activity==null || activity.getCancelTime()!=null || !Long.valueOf(1).equals(activity.getStatus()) || !Integer.valueOf(0).equals(activity.getDelFlag()))return false;
        recordSharedGameplayOutcome(memberId,session.getTopicId(),activityId,nodeId,instance,attemptId,decision,choices,epoch);return true;
    }
    @Override @Transactional(rollbackFor=Exception.class)
    public JsonNode sharedGameplayFeedback(Long memberId,Long activityId,Long gameSessionId,Long teamId,Integer epoch,String attemptId,String optionId) {
        GameSession session=optionId==null?runtimeMapper.selectSessionByActivity(activityId):runtimeMapper.selectSessionByActivityForUpdate(activityId);if(session==null || !Objects.equals(session.getId(),gameSessionId) || !isSharedRoute(session))return null;
        com.chengyinhub.business.domain.PlayTeam team=playTeamMapper.selectPlayTeamById(teamId);if(team==null || !Objects.equals(team.getOwnerId(),activityId) || playTeamMapper.selectActiveMember(teamId,memberId)==null)throw error("GAME_FORBIDDEN_SCOPE","反馈不属于当前队伍");
        GameStoryState owner=requireRouteOwner(session,teamId);TopicRouteEngine.State state=routeOwnerState(owner);com.chengyinhub.business.service.support.GameplayOutcomeEngine outcomes=new com.chengyinhub.business.service.support.GameplayOutcomeEngine();ObjectNode feedback=outcomes.feedback(sessionConfig(session),state,attemptId);if(feedback==null)return null;
        boolean writable=GameSession.RUNNING.equals(session.getStatus()) && epoch!=null && epoch==runEpoch(session) && !naturallyCompleted(session,state) && !state.getThemeRuntime().has("result");
        if(optionId!=null){if(!writable)throw error("GAME_ROUTE_FINISHED","原主题已结束，反馈仅可阅读");requireOnlineRun(runControl(session));PlayerScope player=requirePlayer(GameActorContext.player(memberId),requireActivity(activityId,true));if(!teamId.equals(player.teamId))throw error("GAME_FORBIDDEN_SCOPE","反馈归属已变更");TopicRouteEngine.State before=state.copy();String step=feedback.path("currentStepId").asText();feedback=outcomes.chooseFeedback(sessionConfig(session),routeRules(session),state,attemptId,optionId);JourneyInitialState.clamp(routeRules(session),state);themeEngine.events(sessionConfig(session).path("themeConfig"),state,routeRules(session));JourneyStatLinks.apply(routeRules(session),before,state,"TEAM:"+teamId,"feedback:"+attemptId+":"+step,JourneyStatLinks.Reason.TEMPLATE_EFFECT);saveRouteOwner(session,owner,state);bumpSession(session,session.getStatus(),session.getCurrentChapterId(),memberId,clock.now());}
        feedback.put("writable",writable);return feedback;
    }
    @Override
    @Transactional(rollbackFor=Exception.class)
    public void recordSharedGameplayOutcome(Long memberId,Long topicId,Long activityId,Long nodeId,String instance,String attemptId,JsonNode decision,List<String[]> choices,Integer authorityEpoch) {
        if(decision==null || decision.path("nodeId").asLong()!=nodeId || !Arrays.asList("PASS","FAIL").contains(decision.path("outcome").asText()))throw error("GAME_BAD_REQUEST","Invalid trusted outcome");
        recordSharedRouteResult(memberId,topicId,activityId,nodeId,instance,attemptId,"PASS".equals(decision.path("outcome").asText()),choices,authorityEpoch,decision,attemptId);
    }
    private void recordSharedRouteResult(Long memberId,Long topicId,Long activityId,Long nodeId,String instance,String actionId,Boolean passed,List<String[]> choices,Integer authorityEpoch,JsonNode decision,String attemptId) {
        GameSession session = runtimeMapper.selectSessionByActivityForUpdate(activityId);
        if (!isSharedRoute(session) || !Objects.equals(topicId, session.getTopicId())) {
            throw error("GAME_STATE_CONFLICT", "该结果不属于当前共享局");
        }
        requireSessionStatus(session, GameSession.RUNNING);
        requireOnlineRun(runControl(session));
        CmsActivity activity = requireActivity(activityId, true);
        PlayerScope player = requirePlayer(GameActorContext.player(memberId), activity);
        GameStationRun station = requirePlayableStation(activity, session, nodeId);
        GameStoryState owner = requireRouteOwner(session, player.teamId);
        TopicRouteEngine.State state = routeOwnerState(owner), before = routeOwnerState(owner);
        requireNaturalPlayOpen(session, state);
        if (!sharedInstance(session, state, nodeId).equals(instance)) throw error("GAME_STATE_CONFLICT", "该题组不属于当前任务或操作实例");
        if (authorityEpoch == null || authorityEpoch != runEpoch(session)) throw error("GAME_AUTHORITY_CONFLICT", "该可信题组来自旧权威版本");
        requireNoCooperationSpend(session, player.teamId, state, objectMapper.createArrayNode(), !isFinalOperation(state, nodeId));
        GameSessionCommand fact = new GameSessionCommand();
        fact.setActivityId(activityId); fact.setNodeId(nodeId); fact.setAction("TRUSTED_ROUTE_RESULT");
        fact.setAuthorityEpoch(authorityEpoch);
        fact.getPayload().put("instance", instance); fact.getPayload().put("sourceActionId", actionId);
        fact.getPayload().put("ready", Boolean.TRUE.equals(passed)); fact.getPayload().put("choices", choices);
        if(decision!=null){fact.getPayload().put("decision",decision);fact.getPayload().put("attemptId",attemptId);}
        String hash = requestHash(fact);
        fact.setRequestId("trusted:" + hash.substring(0, 48));
        GameCommandReceipt prior = runtimeMapper.selectReceiptForUpdate(activityId, memberId, fact.getRequestId());
        if (prior != null) { replay(prior, hash); return; }
        if (StringUtils.isBlank(actionId) || actionId.length() > 128 || choices == null) throw error("GAME_BAD_REQUEST", "可信动作标识无效");
        if (state.getThemeRuntime().has("result")) throw error("GAME_ROUTE_FINISHED", "本队结果已冻结");
        JsonNode operation = state.getThemeRuntime().path("activeOperation");
        boolean operationNode = operation.isObject() && operation.path("nodeId").asLong() == nodeId;
        boolean taskNode = operationNode;
        if (operationNode) {
            if (operation.path("epoch").asInt() != runEpoch(session)) throw error("GAME_AUTHORITY_CONFLICT", "操作题组属于旧权威版本");
            if ("HOST_NODE".equals(station.getSourceType())) requireHostArrival(session, player.teamId, operation, station);
            else requireArrived(GameActorContext.player(memberId), activity, station);
        }
        for (JsonNode quest : routeGameplay(session).path("quests")) if (!operationNode && bindingNode(session, quest) == nodeId && quest.path("questKey").asText().equals(state.getThemeRuntime().path("gameplay").path("activeQuest").asText())) {
            requireEligibleQuest(session, state, quest); taskNode = true;
            if (!quest.path("questKey").asText().equals(state.getThemeRuntime().path("gameplay").path("activeQuest").asText())) throw error("GAME_TASK_UNVERIFIED", "请先开始本队任务");
            if ("HOST_NODE".equals(station.getSourceType())) requireHostArrival(session, player.teamId, quest, station);
            else requireArrived(GameActorContext.player(memberId), activity, station);
            break;
        }
        if (!taskNode && !operationNode && routeGameplay(session).path("quests").size() > 0) throw error("GAME_TASK_UNVERIFIED", "请先开始当前任务实例");
        if (!taskNode && !Objects.equals(nodeId, state.getCurrentNodeId())) throw error("GAME_ROUTE_LOCKED", "尚未到达该任务");
        Map<String, JsonNode> balancesBefore = routeBalances(session);
        GameCommandReceipt claim = receiptClaim(GameActorContext.player(memberId), fact, hash, session);
        if (runtimeMapper.insertReceiptClaim(claim) != 1) throw error("GAME_STATE_CONFLICT", "可信结果正在提交，请重试");
        JsonNode snapshot = sessionConfig(session), config = snapshot.path("themeConfig");
        JourneyRules rules = routeRules(session);
        ArrayNode effects = objectMapper.createArrayNode();
        ObjectNode cached = null;
        boolean participantTaskScope = operationNode && jointQuestOperation(session, state, operation);
        if (participantTaskScope) cached = state.getThemeRuntime().with("gameplay").with("pendingTaskEffects").with(state.getThemeRuntime().path("gameplay").path("activeQuest").asText());
        else if (operationNode && ("FACILITY".equals(operation.path("operationKind").asText()) || decision!=null && "PLAN".equals(operation.path("operationKind").asText()))) cached = state.getThemeRuntime().with("operationFacts").with(operation.path("operationKind").asText()+":"+operation.path("operationKey").asText());
        else if (taskNode && !operationNode) cached = state.getThemeRuntime().with("gameplay").with("pendingTaskEffects").with(state.getThemeRuntime().path("gameplay").path("activeQuest").asText());
        if (cached != null) { cached.withArray("effects"); cached.with("sources"); }
        ObjectNode sources = cached == null ? state.getThemeRuntime().with("sources") : cached.with("sources");
        Long sourceNode = operationNode ? operation.path("sourceNodeId").asLong(nodeId) : nodeId;
        for (JsonNode quest : routeGameplay(session).path("quests")) if (!operationNode && bindingNode(session, quest) == nodeId
                && quest.path("questKey").asText().equals(state.getThemeRuntime().path("gameplay").path("activeQuest").asText())) sourceNode = quest.path("nodeId").asLong();
        CooperationScope failureScope=null;
        if(decision!=null) {
            ObjectNode boundDecision=(ObjectNode)decision.deepCopy();boundDecision.put("nodeId",sourceNode);
            new com.chengyinhub.business.service.support.GameplayOutcomeEngine().remember(snapshot,state,attemptId,boundDecision);
            if(Boolean.TRUE.equals(passed)) {
                if(cached==null)throw error("GAME_BAD_REQUEST","Outcome requires a pending task or facility");
                cached.put("outcomeAttemptId",attemptId);
            }
        }
        boolean alreadyVerified = sources.path("guide:" + instance).asBoolean() || sources.path("basic:" + instance).asBoolean();
        if (Boolean.TRUE.equals(passed) && !alreadyVerified) TrustedNodeEffects.appendRootOnce(snapshot, sourceNode, instance, effects, sources);
        for (String[] choice : choices) {
            if (choice == null || choice.length != 2 || choice[0] == null) throw error("GAME_BAD_REQUEST", "可信选择参数无效");
            String key = "choice:" + instance + ":" + nodeId + ":" + choice[0] + ":" + choice[1];
            if (sources.has(key) || alreadyVerified || (cached != null && !Boolean.TRUE.equals(passed) && decision==null)) continue;
            for (JsonNode effect : topicRouteRuntimeService.trustedChoiceEffects(snapshot, sourceNode, choice[0], choice[1])) effects.add(effect);
            sources.put(key, true);
        }
        if(decision!=null && !Boolean.TRUE.equals(passed)) {
            Map<Long,TopicRouteEngine.State> recipients=new LinkedHashMap<>();recipients.put(player.teamId,state);
            JsonNode facility=objectMapper.missingNode();
            if(operationNode && "FACILITY".equals(operation.path("operationKind").asText()))facility=configuredCooperation(session,"facilities","facilityKey",operation.path("operationKey").asText());
            else for(JsonNode quest:routeGameplay(session).path("quests"))if(quest.path("nodeId").asLong()==sourceNode && quest.has("facilityKey"))facility=configuredCooperation(session,"facilities","facilityKey",quest.path("facilityKey").asText());
            if("PARTICIPANTS".equals(facility.path("targetScope").asText()))throw error("GAME_CONFIG_INVALID","R5 failure recipients require a frozen participant selection");
            Map<Long,TopicRouteEngine.State> old=new LinkedHashMap<>();old.put(player.teamId,before);
            if("WORLD".equals(facility.path("targetScope").asText())) {
                failureScope=cooperationScope(session,true);failureScope.teams.put(player.teamId,state);
                for(Map.Entry<Long,TopicRouteEngine.State> target:failureScope.teams.entrySet())if(cooperationEngine.isEffectiveTarget(config,target.getValue())) {recipients.put(target.getKey(),target.getValue());old.putIfAbsent(target.getKey(),target.getValue().copy());}
            }
            new com.chengyinhub.business.service.support.GameplayOutcomeEngine().settleRemembered(config,rules,state,recipients,attemptId,clock.now().getTime());
            com.chengyinhub.business.service.support.TemplateEffects.apply(effects,rules,state);
            state.getVariables().put("sys.passed."+sourceNode,0);
            for(Map.Entry<Long,TopicRouteEngine.State> target:recipients.entrySet()) {
                JourneyInitialState.clamp(rules,target.getValue());themeEngine.events(config,target.getValue(),rules);
                JourneyStatLinks.apply(rules,old.get(target.getKey()),target.getValue(),"TEAM:"+target.getKey(),"advanced:"+claim.getId(),new java.util.LinkedHashSet<>(Arrays.asList(JourneyStatLinks.Reason.TASK_COST,JourneyStatLinks.Reason.TEMPLATE_EFFECT)));
            }
        } else if (isFinalOperation(state, nodeId) && !participantTaskScope && cached==null) {
            if (effects.size() > 0) throw error("GAME_PUBLIC_OPERATION_EFFECT", "最终操作题只能输出可信判定，不能绕过出资改余额");
        } else if (cached != null) {
            for (JsonNode effect : effects) cached.withArray("effects").add(effect.deepCopy());
        } else {
            com.chengyinhub.business.service.support.TemplateEffects.apply(effects, rules, state); JourneyInitialState.clamp(rules, state);
            themeEngine.events(config, state, rules); JourneyInitialState.clamp(rules, state);
            JourneyStatLinks.apply(rules, before, state, "TEAM:" + player.teamId, "advanced:" + claim.getId(), JourneyStatLinks.Reason.TEMPLATE_EFFECT);
        }
        if (operationNode && Boolean.TRUE.equals(passed)) {
            ObjectNode proof = (ObjectNode) operation.deepCopy(); proof.put("verifiedReceiptId", claim.getId());
            if (cached != null) { proof.set("effects", participantTaskScope ? objectMapper.createArrayNode() : cached.path("effects").deepCopy()); proof.set("sources", cached.path("sources").deepCopy()); }
            if(decision!=null && cached!=null)proof.set("outcomeAttemptId",cached.get("outcomeAttemptId"));
            state.getThemeRuntime().with("operationFacts").set(operation.path("operationKind").asText() + ":" + operation.path("operationKey").asText(), proof);
        }
        if (Boolean.TRUE.equals(passed)) {
            state.getThemeRuntime().with("verifiedNodes").put(String.valueOf(nodeId), claim.getId());
            if (operationNode) state.getThemeRuntime().with("verifiedNodes").put(operation.path("sourceNodeId").asText(String.valueOf(nodeId)), claim.getId());
            for (JsonNode quest : routeGameplay(session).path("quests")) if (!operationNode && bindingNode(session, quest) == nodeId
                    && quest.path("questKey").asText().equals(state.getThemeRuntime().path("gameplay").path("activeQuest").asText())) {
                state.getThemeRuntime().with("verifiedNodes").put(quest.path("nodeId").asText(), claim.getId());
                recordQuestFacilityFact(session, state, quest, claim.getId(), memberId);
            }
        }
        ObjectNode event = state.getThemeRuntime().withArray("journal").addObject();
        event.put("action", "TRUSTED_ROUTE_RESULT"); event.put("receiptId", claim.getId()); event.put("nodeId", station.getNodeId());
        event.put("actorMemberId", memberId); event.put("owner", "TEAM:" + player.teamId); event.put("ready", Boolean.TRUE.equals(passed));
        if(failureScope!=null)persistCooperation(failureScope);else saveRouteOwner(session, owner, state);
        bumpSession(session, session.getStatus(), session.getCurrentChapterId(), memberId, clock.now());
        claim.setOutcome("APPLIED"); claim.setRevision(session.getRevision());
        fact.setRequestHash(hash);
        appendRunEvent(session, GameActorContext.player(memberId), fact, claim.getId(), balancesBefore);
        claim.setResultJson(writeJson(sharedRoutePlayer(session, player.teamId,memberId))); claim.setUpdateTime(clock.now());
        if (runtimeMapper.completeReceipt(claim) != 1) throw error("GAME_STATE_CONFLICT", "可信结果回执保存失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public JsonNode recordSharedZeroChallengeResult(Long memberId, Long topicId, Long activityId, Long nodeId,
            String instance, String submissionId, boolean passed, Long hits, boolean retry, Integer authorityEpoch) {
        GameSession session = runtimeMapper.selectSessionByActivityForUpdate(activityId);
        if (!isSharedRoute(session) || !Objects.equals(topicId, session.getTopicId())) throw error("GAME_FORBIDDEN_SCOPE", "挑战不属于当前共享局");
        CmsActivity activity = requireActivity(activityId, true);
        PlayerScope player = requirePlayer(GameActorContext.player(memberId), activity);
        GameSessionCommand fact = new GameSessionCommand(); fact.setActivityId(activityId); fact.setNodeId(nodeId);
        fact.setAction("TRUSTED_ZERO_CHALLENGE"); fact.setAuthorityEpoch(authorityEpoch);
        if (StringUtils.isBlank(instance) || StringUtils.isBlank(submissionId) || submissionId.length() > 128) throw error("GAME_BAD_REQUEST", "挑战提交标识无效");
        fact.getPayload().put("instance", instance); fact.getPayload().put("submissionId", submissionId);
        fact.getPayload().put("passed", passed); fact.getPayload().put("hits", hits); fact.getPayload().put("retry", retry);
        GameSessionCommand identity = new GameSessionCommand(); identity.setActivityId(activityId); identity.setAction("TRUSTED_ZERO_CHALLENGE");
        identity.getPayload().put("instance", instance); identity.getPayload().put("submissionId", submissionId);
        fact.setRequestId("challenge:" + requestHash(identity).substring(0, 48));
        String hash = requestHash(fact);
        GameCommandReceipt previous = runtimeMapper.selectReceiptForUpdate(activityId, memberId, fact.getRequestId());
        if (previous != null) { replay(previous, hash); return objectMapper.valueToTree(readMap(previous.getResultJson())); }
        requireSharedWrite(session, fact); requireSessionStatus(session, GameSession.RUNNING);
        if (runControl(session).path("exitedTeams").has(String.valueOf(player.teamId))) throw error("GAME_TEAM_EXITED", "本队已退出本局");
        GameStoryState owner = requireRouteOwner(session, player.teamId); TopicRouteEngine.State state = routeOwnerState(owner);
        requireNoCooperationSpend(session, player.teamId, state, objectMapper.createArrayNode(), false);
        if (!activeChallenge(session, state, nodeId) || !instance.equals(sharedInstance(session, state, nodeId))) throw error("GAME_STATE_CONFLICT", "挑战实例已更新");
        if (state.getThemeRuntime().has("result")) throw error("GAME_ROUTE_FINISHED", "本队结果已冻结");
        GameCommandReceipt claim = receiptClaim(GameActorContext.player(memberId), fact, hash, session);
        if (runtimeMapper.insertReceiptClaim(claim) != 1) throw error("GAME_STATE_CONFLICT", "挑战正在提交");
        ObjectNode result = survivalEngine.submitChallenge(sessionConfig(session), routeRules(session), state, memberId,
                state.getThemeRuntime().path("challenge").path("instanceId").asText(), nodeId, submissionId, passed, hits, retry, clock.now().getTime());
        saveRouteOwner(session, owner, state); bumpSession(session, session.getStatus(), session.getCurrentChapterId(), memberId, clock.now());
        result.put("receiptId", claim.getId()); claim.setOutcome("APPLIED"); claim.setRevision(session.getRevision());
        claim.setResultJson(writeJson(result)); claim.setUpdateTime(clock.now());
        if (runtimeMapper.completeReceipt(claim) != 1) throw error("GAME_STATE_CONFLICT", "挑战回执保存失败");
        return result;
    }

    private ObjectNode routeActionSummary(GameSession session, JourneyRules rules, TopicRouteEngine.State state,
            TopicRouteEngine.State world, Long teamId, CooperationScope scope, String operation, String key) {
        ObjectNode summary=GameplayActionProjection.gameplayActionSummary(sessionConfig(session).path("themeConfig"),rules,state,world,operation,key);
        if(world!=null) for(JsonNode option:summary.path("costOptions")) {
            Map<String,Integer> needed=new LinkedHashMap<>();
            for(JsonNode costs:Arrays.asList(summary.path("costs").path("resourceCosts"),option.path("resourceCosts")))
                for(JsonNode row:costs)needed.put(row.path("itemId").asText(),Math.addExact(needed.getOrDefault(row.path("itemId").asText(),0),row.path("quantity").asInt()));
            ArrayNode resources=objectMapper.createArrayNode();needed.forEach((item,quantity)->resources.addObject().put("itemId",item).put("quantity",quantity));
            ArrayNode shortfall=(ArrayNode)option.path("shortfall");
            for(java.util.Iterator<JsonNode> rows=shortfall.iterator();rows.hasNext();)if("ITEM".equals(rows.next().path("type").asText()))rows.remove();
            shortfall.addAll(cooperationEngine.resourceShortfall(world,teamId,state,resources));
            if(!shortfall.isEmpty())((ObjectNode)option).put("affordable",false);
        }
        if(summary.isEmpty())return summary;
        JsonNode config=sessionConfig(session).path("themeConfig"),game=config.path("gameplayRuntime"),content=null;
        boolean quest="QUEST_RESOLVE".equals(operation);
        for(JsonNode entry:game.path(quest?"quests":"PLAN_EXECUTE".equals(operation)?"plans":"facilities"))
            if(key.equals(entry.path(quest?"questKey":"PLAN_EXECUTE".equals(operation)?"endingKey":"facilityKey").asText()))content=entry;
        if("RECOVER".equals(operation))for(JsonNode entry:game.path("survival").path("recoveries"))if(key.equals(entry.path("recoveryKey").asText()))content=entry;
        if(content==null)return summary;
        JsonNode progress=state.getThemeRuntime().path("gameplay"),active=state.getThemeRuntime().path("activeOperation");
        boolean current=quest?key.equals(progress.path("activeQuest").asText()):!"RECOVER".equals(operation) && key.equals(active.path("operationKey").asText());
        long node=content.path(quest?"nodeId":"operationNodeId").asLong();
        JsonNode related=null;for(JsonNode entry:game.path("quests"))if(node==entry.path("nodeId").asLong())related=entry;
        long actual=related==null?node:bindingNode(session,related);
        JsonNode verified=state.getThemeRuntime().path("verifiedNodes").path(String.valueOf(node));
        boolean passed=current && ((verified.isIntegralNumber() && verified.asLong()>0) || verified.isBoolean() && verified.asBoolean());
        if(quest)passed=passed && progress.path("activeQuestReceiptId").asLong()>0;
        else if(node>0) {
            JsonNode proof=state.getThemeRuntime().path("operationFacts").path(("PLAN_EXECUTE".equals(operation)?"PLAN:":"FACILITY:")+key);
            boolean bound=related!=null && related.path("questKey").asText().equals(progress.path("activeQuest").asText());
            passed=(current || bound) && ((verified.isIntegralNumber() && verified.asLong()>0) || verified.isBoolean() && verified.asBoolean()) && proof.isObject() && proof.path("epoch").asInt()==runEpoch(session);
            if(current)passed=passed && active.path("epoch").asInt()==runEpoch(session) && active.path("nodeId").asLong()==actual;
            if("PLAN_EXECUTE".equals(operation))passed=passed && world!=null && proof.path("round").asInt(-1)==world.getThemeRuntime().path("cooperation").path("round").asInt();
        }
        String next=quest?(current?"QUEST_RESOLVE":"QUEST_START"):"RECOVER".equals(operation)?"RECOVER":content.has("operationNodeId") && !current?"OPERATION_START":"FACILITY_USE";
        if("PLAN_EXECUTE".equals(operation)) {
            JsonNode cooperation=world==null?objectMapper.createObjectNode():world.getThemeRuntime().path("cooperation");
            boolean selected="SELECTED".equals(cooperation.path("stage").asText()) && key.equals(cooperation.path("proposals").path(cooperation.path("selected").asText()).path("endingKey").asText());
            next=!selected?"PLAN_PROPOSE":current?"PLAN_EXECUTE":"OPERATION_START";
        }
        summary.put("operation",next);
        Reason reason=summary.path("disabledReason").isTextual()?Reason.valueOf(summary.path("disabledReason").asText()):null;
        if(summary.has("costOptions")) {
            Integer frozen=quest && progress.path("activeQuestCostOption").isInt()?Integer.valueOf(progress.path("activeQuestCostOption").asInt()):current && active.path("costOption").isInt()?Integer.valueOf(active.path("costOption").asInt()):null;
            boolean affordable=false;int index=0;
            for(JsonNode option:summary.path("costOptions")) {if((frozen==null || frozen==index) && option.path("affordable").asBoolean())affordable=true;index++;}
            if(!affordable && reason==null)reason=Reason.INSUFFICIENT_RESOURCES;
        } else if(world!=null && !operation.startsWith("PLAN") && !cooperationEngine.resourceShortfall(world,teamId,state,summary.path("costs").path("resourceCosts")).isEmpty() && reason==null)reason=Reason.INSUFFICIENT_RESOURCES;
        if(reason==Reason.INSUFFICIENT_RESOURCES && "FACILITY_USE".equals(operation) && !"PARTICIPANTS".equals(content.path("targetScope").asText())
                && cooperationEngine.facilityRecoveryNoEffect(config,rules,state,content,"WORLD".equals(content.path("targetScope").asText())?cooperationScope(session,false).teams.values():Collections.singletonList(state)))reason=null;
        Reason gate=routeToolGate(session,state,world,teamId);
        if(gate!=null)reason=gate;
        else if(summary.path("remainingCount").asInt()==0 || summary.has("facility") && summary.path("facility").path("remainingCount").asInt()==0)reason=Reason.LIMIT_REACHED;
        else if(world!=null && GameplayCooperationEngine.publicFactsSettled("FACILITY_USE".equals(operation)?content:quest && content.has("facilityKey")?configuredCooperation(session,"facilities","facilityKey",content.path("facilityKey").asText()):objectMapper.createObjectNode(),world))reason=Reason.LIMIT_REACHED;
        else if((active.isObject() && !current) || !progress.path("activeQuest").asText().isEmpty() && !current && !(related!=null && participantFreeQuest(session,state,content,false)))reason=Reason.TASK_ACTIVE;
        else if(world!=null && (!operation.startsWith("PLAN") || "PLAN_PROPOSE".equals(next)) && cooperationEngine.isVotingFrozen(world))reason=Reason.WORLD_LOCKED;
        else if(node>0 && !"PLAN_PROPOSE".equals(next) && !routeViewArrived(session,teamId,related,actual,content))reason=Reason.NOT_ARRIVED;
        else if(current && node>0 && !passed)reason=Reason.PROOF_PENDING;
        else try {
            if(quest)requireEligibleQuest(session,state,content);
            else if("RECOVER".equals(operation))survivalEngine.recover(config,rules,state.copy(),key,"view");
            else if(!themeEngine.conditions(content.path("when"),operation.startsWith("PLAN") && world!=null?world:state))reason=Reason.RULE_BLOCKED;
        } catch(IllegalArgumentException|IllegalStateException|GameSessionException unavailable) {reason=Reason.RULE_BLOCKED;}
        if(operation.startsWith("PLAN") && world!=null && content.path("when").size()>0) {
            // 方案缺少的公开线索：只给线索名称（主题登记名），不给内容或他队私有状态
            Map<String,String> labels=clueLabels(session);ArrayNode missing=objectMapper.createArrayNode();
            for(JsonNode condition:content.path("when")) {
                String var=condition.path("var").asText("");
                if(!var.startsWith("clue.") || !"EQ".equals(condition.path("op").asText()) || condition.path("value").asInt()!=1)continue;
                Number value=world.getVariables().get(var);
                if(value==null || value.intValue()!=1)missing.add(labels.getOrDefault(var,"未公开的线索"));
            }
            if(missing.size()>0)summary.set("missingClues",missing);
        }
        if(summary.has("participantCatalog"))summary.set("participantCatalog",routeParticipantCatalog(session,content,scope));
        if(summary.path("facility").isObject())GameplayToolState.availability((ObjectNode)summary.path("facility"),reason,passed);
        return GameplayToolState.availability(summary,reason,passed);
    }

    private ArrayNode routeParticipantCatalog(GameSession session,JsonNode facility,CooperationScope scope) {
        ArrayNode catalog=objectMapper.createArrayNode();
        if(scope==null)return catalog;
        for(Map.Entry<Long,TopicRouteEngine.State> team:scope.teams.entrySet()) {
            TopicRouteEngine.State target=team.getValue();
            Reason reason=routeToolGate(session,target,scope.world,team.getKey());
            if(reason==null && facility.has("arrivalLocationKey") && !target.getThemeRuntime().path("hostArrivals").has(facility.path("arrivalLocationKey").asText()))reason=Reason.NOT_ARRIVED;
            if(reason==null && target.getThemeRuntime().path("gameplay").has("activeQuest")) {
                if(!participantFreeQuest(session,target,facility,false))reason=Reason.TASK_ACTIVE;
                else if(!participantFreeQuest(session,target,facility,true))reason=Reason.PROOF_PENDING;
                else try {taskEngine.eligible(sessionConfig(session).path("themeConfig"),target,configuredQuest(session,target.getThemeRuntime().path("gameplay").path("activeQuest").asText()));}
                catch(IllegalArgumentException|IllegalStateException|GameSessionException blocked){reason=Reason.RULE_BLOCKED;}
            }
            ObjectNode row=catalog.addObject().put("teamId",team.getKey()).put("teamName",scope.teamNames.get(team.getKey())).put("eligible",reason==null);
            if(reason==null)row.putNull("disabledReason");else row.put("disabledReason",reason.name());
        }
        return catalog;
    }

    private Reason routeToolGate(GameSession session,TopicRouteEngine.State state,TopicRouteEngine.State world,Long teamId) {
        JsonNode run=runControl(session);
        if(!GameSession.RUNNING.equals(session.getStatus()) || state.getThemeRuntime().has("result") || world!=null && world.getThemeRuntime().path("cooperation").has("result") || Arrays.asList("INTERRUPTED","CANCELLED").contains(run.path("operationStatus").asText()) || run.path("exitedTeams").has(String.valueOf(teamId)))return Reason.SESSION_ENDED;
        if(naturallyCompleted(session,state))return Reason.TEAM_COMPLETED;
        if(!"RUNNING".equals(run.path("operationStatus").asText("RUNNING")) || !"ONLINE".equals(run.path("authorityMode").asText("ONLINE")))return Reason.SESSION_PAUSED;
        return null;
    }

    private boolean routeViewArrived(GameSession session,Long teamId,JsonNode quest,long node,JsonNode tool) {
        GameStationRun station=runtimeMapper.selectStation(session.getId(),node);
        if(station==null)return false;
        if("HOST_NODE".equals(station.getSourceType())) {
            String location=tool.path("arrivalLocationKey").asText(quest==null?"":quest.path("locationKey").asText());
            JsonNode proof=runControl(session).path("arrivals").path(arrivalKey(teamId,location));
            return proof.path("used").asBoolean() && proof.path("sessionId").asLong()==session.getId() && proof.path("teamId").asLong()==teamId && proof.path("nodeId").asLong()==node
                    && proof.path("epoch").asInt()==runEpoch(session) && proof.path("expiresAt").asLong()>evaluationTime().getTime() && (quest==null || proof.path("version").asLong()==bindingVersion(session,quest));
        }
        com.chengyinhub.business.domain.PlayTeam team=playTeamMapper.selectPlayTeamById(teamId);
        return team!=null && team.getLeaderMemberId()!=null && playerNodeProgressService!=null && hasArrived(GameActorContext.player(team.getLeaderMemberId()),requireActivity(session.getActivityId(),false),station);
    }

    private ObjectNode sharedZeroHpView(GameSession session,Long teamId,Long memberId,TopicRouteEngine.State state) {
        Long recovery=null;String name="",unavailable="";
        if("STEPS".equals(state.getThemeRuntime().path("zeroHp").path("policy").asText())) {
            long bound=state.getThemeRuntime().path("zeroHp").path("recoveryMemberId").asLong();
            PlayTeamMember baseline=bound>0?playTeamMapper.selectActiveMember(teamId,bound):null;
            if(baseline!=null){recovery=bound;name=baseline.getMemberName();}
            else {
                PlayTeam team=playTeamMapper.selectPlayTeamById(teamId);
                if(team!=null && Integer.valueOf(2).equals(team.getOwnerType()) && session.getActivityId().equals(team.getOwnerId()) && team.getStatus()!=null && team.getStatus()<=2) {
                    recovery=team.getLeaderMemberId();PlayTeamMember captain=recovery==null?null:playTeamMapper.selectActiveMember(teamId,recovery);
                    name=captain==null?"":captain.getMemberName();
                }
            }
        }
        try {requireActivity(session.getActivityId(),true);requireSessionStatus(session,GameSession.RUNNING);requireOnlineRun(runControl(session));requireNaturalPlayOpen(session,state);}
        catch(GameSessionException closed){unavailable=closed.getMessage();}
        return GameplaySurvivalEngine.zeroView(sessionConfig(session).path("themeConfig"),state,clock.now().getTime(),memberId,recovery,name,unavailable);
    }

    private Map<String, Object> sharedRoutePlayer(GameSession session, Long teamId,Long memberId) {
        GameStoryState owner = requireRouteOwner(session, teamId);
        TopicRouteEngine.State state = routeOwnerState(owner);
        JourneyRules rules = routeRules(session);
        ObjectNode projection = themeEngine.view(sessionConfig(session).path("themeConfig"), state, rules);
        if(projection.has("zeroHp"))projection.set("zeroHp",sharedZeroHpView(session,teamId,memberId,state));
        if(state.getThemeRuntime().path("result").isObject())projection.set("resultCard",themeEngine.card(state.getThemeRuntime().path("result")));
        Map<String, Object> view = objectMapper.convertValue(projection, Map.class);
        view.put("teamId", teamId); view.put("scope", "TEAM"); view.put("status", state.getStatus()); view.put("naturallyCompleted", naturallyCompleted(session, state));
        view.put("worldMode", GameplayRuntimeConfig.getWorldMode(sessionConfig(session).path("themeConfig")));
        view.put("awaitingChallenge", state.getThemeRuntime().path("awaitingChallenge").asBoolean());
        if (state.getThemeRuntime().has("challenge")) {
            ObjectNode challenge=GameplayActionProjection.gameplayChallengeSummary(sessionConfig(session).path("themeConfig"),state);
            Reason gate=routeToolGate(session,state,null,teamId);
            if(gate!=null)GameplayToolState.availability(challenge,gate,challenge.path("trustedPass").asBoolean());
            view.put("challenge",challenge);
        }
        view.put("ownerRevision", owner.getRevision());
        view.put("authorityEpoch", runEpoch(session));
        view.put("run", publicRunControl(session));
        TopicRouteEngine.State worldState = null;
        CooperationScope cooperation = null;
        if ("WORLD_SHARED".equals(GameplayRuntimeConfig.getWorldMode(sessionConfig(session).path("themeConfig")))) {
            CooperationScope scope = cooperationScope(session, false);
            cooperation = scope; worldState = scope.world;
            ObjectNode worldView=cooperationEngine.view(sessionConfig(session).path("themeConfig"), scope.world, scope.teams, teamId, scope.leaders);
            if (worldView.path("fallbackAvailable").asBoolean()) worldView.put("fallbackAvailable", false).put("fallbackDisabledReason", "NOT_STAFF");
            Reason gate=routeToolGate(session,state,worldState,teamId);
            if (gate != null && view.get("challenge") instanceof ObjectNode) GameplayToolState.availability((ObjectNode) view.get("challenge"), gate, false);
            if(gate!=null)for(String field:Arrays.asList("trades","tradeTargets"))for(JsonNode row:worldView.path(field)) {
                GameplayToolState.availability((ObjectNode)row,gate,false);((ObjectNode)row).put("disabled",true);
            }
            view.put("world",worldView);
        }
        view.put("clues", clueView(session, state, worldState));
        view.put("hp", state.getVariables().get("sys.hp")); view.put("luck", state.getVariables().get("sys.luck"));
        view.put("attributes", JourneyInitialState.playerView(rules, state));
        view.put("currentNodeId", state.getCurrentNodeId());
        view.put("completedNodeIds", state.getCompletedNodeIds());
        ObjectNode progress = objectMapper.createObjectNode();
        for (String field : Arrays.asList("actIndex", "activeQuest", "activeQuestCostOption", "quests", "awaitingUnlock", "exclusiveGroups", "extraSlots")) {
            JsonNode value = state.getThemeRuntime().path("gameplay").get(field);
            if (value != null) progress.set(field, value.deepCopy());
        }
        view.put("gameplay", progress);
        ObjectNode activeView=objectMapper.createObjectNode();
        for(String field:Arrays.asList("operationKind","operationKey","nodeId","sourceNodeId","bindingVersion","epoch","costOption","round"))if(state.getThemeRuntime().path("activeOperation").has(field))activeView.set(field,state.getThemeRuntime().path("activeOperation").get(field).deepCopy());
        view.put("activeOperation",activeView);
        ArrayNode recoveries = objectMapper.createArrayNode();
        for (JsonNode recovery : routeGameplay(session).path("survival").path("recoveries")) {
            ObjectNode visible = routeActionSummary(session, rules, state, worldState, teamId, cooperation, "RECOVER", recovery.path("recoveryKey").asText());
            for (String field : Arrays.asList("recoveryKey", "kind", "title", "hpDelta", "luckDelta")) if (recovery.has(field)) visible.set(field, recovery.get(field));
            recoveries.add(visible);
        }
        JourneyInitialState.redactHidden(rules, recoveries); view.put("recoveries", recoveries);
        List<Map<String, Object>> quests = new ArrayList<>();
        for (JsonNode quest : routeGameplay(session).path("quests")) {
            try { requireEligibleQuest(session, state, quest); }
            catch (GameSessionException unavailable) {
                if(!state.getThemeRuntime().path("gameplay").path("slots").has(quest.path("settlementSlot").asText()))continue;
            }
            ObjectNode visible = objectMapper.createObjectNode();
            for (String field : Arrays.asList("questKey", "actKey", "nodeId", "locationKey", "facilityKey", "hpCost", "luckCost", "statCosts", "resourceCosts", "actionCost", "choices", "grants")) if (quest.has(field)) visible.set(field, quest.get(field));
            ObjectNode summary=routeActionSummary(session, rules, state, worldState, teamId, cooperation, "QUEST_RESOLVE", quest.path("questKey").asText());
            visible.set("actionSummary", summary);
            visible.setAll(summary);
            if(summary.has("costOptions"))visible.set("costOptions",summary.get("costOptions").deepCopy());
            JourneyInitialState.redactHidden(rules, visible);
            visible.put("nodeId", bindingNode(session, quest)).put("bindingVersion", bindingVersion(session, quest));
            quests.add(objectMapper.convertValue(visible, Map.class));
        }
        view.put("quests", quests);
        ArrayNode tools = objectMapper.createArrayNode();
        for (JsonNode facility : routeGameplay(session).path("facilities")) {
            JsonNode node = facility.get("operationNodeId");
            if (node == null) for (JsonNode quest : routeGameplay(session).path("quests")) if (facility.path("facilityKey").asText().equals(quest.path("facilityKey").asText())) { node = quest.get("nodeId"); break; }
            JsonNode quest = visibleToolQuest(session, state, node);
            JsonNode acts=routeGameplay(session).path("acts");
            String act=acts.path(Math.min(state.getThemeRuntime().path("gameplay").path("actIndex").asInt(),Math.max(0,acts.size()-1))).path("actKey").asText();
            if (node!=null && (quest == null || !facility.path("actKey").asText().equals(quest.path("actKey").asText())) || node==null && (!facility.path("actKey").asText().equals(act) || state.getThemeRuntime().path("gameplay").path("awaitingUnlock").asBoolean())) continue;
            ObjectNode tool = routeActionSummary(session, rules, state, worldState, teamId, cooperation, "FACILITY_USE", facility.path("facilityKey").asText());
            tool.put("kind", "FACILITY").put("operationKey", facility.path("facilityKey").asText());
            if(quest!=null)tool.put("nodeId",bindingNode(session,quest)).put("bindingVersion",bindingVersion(session,quest));
            if(facility.has("operationNodeId"))tool.set("operationNodeId",facility.get("operationNodeId").deepCopy());
            for (String field : Arrays.asList("title", "summary")) if (facility.has(field)) tool.set(field, facility.get(field));
            tools.add(tool);
        }
        for (JsonNode plan : routeGameplay(session).path("plans")) {
            JsonNode quest = visibleToolQuest(session, state, plan.get("operationNodeId"));
            if (quest == null) continue;
            ObjectNode tool = routeActionSummary(session, rules, state, worldState, teamId, cooperation, "PLAN_EXECUTE", plan.path("endingKey").asText());
            if (worldState != null) for (JsonNode source : routeGameplay(session).path("templateTools").path("nodes")) if (source.path("nodeId").asLong() == plan.path("operationNodeId").asLong()) {
                for (JsonNode key : source.path("planKeys")) if (worldState.getThemeRuntime().path("cooperation").path("executedPlans").has(key.asText())) {
                    GameplayToolState.usage(tool,1,1,"WORLD");
                    if(!Arrays.asList("SESSION_ENDED","TEAM_COMPLETED","SESSION_PAUSED").contains(tool.path("disabledReason").asText()))GameplayToolState.availability(tool,Reason.LIMIT_REACHED,tool.path("trustedPass").asBoolean());
                }
            }
            tool.put("kind", "PLAN").put("endingKey", plan.path("endingKey").asText()).put("operationKey", plan.path("endingKey").asText()).put("terminal", plan.path("terminal").asBoolean(true)).put("nodeId", bindingNode(session, quest)).put("bindingVersion", bindingVersion(session, quest));
            if(plan.has("operationNodeId"))tool.set("operationNodeId",plan.get("operationNodeId").deepCopy());
            for (String field : Arrays.asList("title", "summary", "materialCost")) if (plan.has(field)) tool.set(field, plan.get(field));
            tools.add(tool);
        }
        JourneyInitialState.redactHidden(rules, tools);
        for(JsonNode tool:tools)if(tool.path("operationKey").asText().equals(activeView.path("operationKey").asText()))activeView.put("verified",tool.path("trustedPass").asBoolean());
        view.put("interactionTools", tools);
        if (view.get("world") instanceof ObjectNode) {
            ObjectNode worldView = (ObjectNode) view.get("world");
            Reason gate = routeToolGate(session, state, worldState, teamId);
            for (JsonNode plan : worldView.path("plans")) {
                ObjectNode actions = (ObjectNode) plan.path("actions");
                ObjectNode summary = routeActionSummary(session, rules, state, worldState, teamId, cooperation, "PLAN_EXECUTE", plan.path("endingKey").asText());
                boolean executable = actions.path("canExecute").asBoolean() && summary.path("enabled").asBoolean()
                        && "PLAN_EXECUTE".equals(summary.path("operation").asText());
                actions.put("canExecute", executable);
                if (gate != null) {
                    for (String field : Arrays.asList("canFund", "canVote", "canAccept", "canExecute")) actions.put(field, false);
                    actions.put("disabledReason", gate.name());
                } else if (!actions.path("canFund").asBoolean() && !actions.path("canVote").asBoolean() && !actions.path("canAccept").asBoolean() && !executable
                        && summary.path("disabledReason").isTextual()) actions.set("disabledReason", summary.get("disabledReason"));
            }
        }
        view.put("playMode", "ROUTE");
        return view;
    }

    @Autowired(required = false) private com.chengyinhub.business.mapper.ThemeStateKeyMapper themeStateKeyMapper;
    /** 主题登记的线索名称（clue.* → label）。 */
    private Map<String,String> clueLabels(GameSession session) {
        Map<String,String> labels=new LinkedHashMap<>();
        List<com.chengyinhub.business.domain.ThemeStateKey> definitions = JourneyInitialState.frozenDefinitions(sessionConfig(session));
        if(definitions==null && (themeStateKeyMapper==null || session.getTopicId()==null))return labels;
        for(com.chengyinhub.business.domain.ThemeStateKey key:safe(definitions==null?themeStateKeyMapper.selectByTopicId(session.getTopicId()):definitions))
            if(key!=null && key.getStateKey()!=null && key.getStateKey().startsWith("clue."))labels.put(key.getStateKey(),key.getLabel()==null||key.getLabel().isEmpty()?"线索":key.getLabel());
        return labels;
    }
    /**
     * 本队已获得的线索（本队私有）与全场公开线索。范围与内容分开：内容只取发布时冻结的来源站点剧情段，
     * 且只取该归属实际结算过的来源（本队线索看本队结算，公开线索看全场结算），不同路线各自取得的证据不混用。
     */
    private ArrayNode clueView(GameSession session, TopicRouteEngine.State state, TopicRouteEngine.State world) {
        Map<String,String> labels=clueLabels(session);JsonNode snapshot=sessionConfig(session);ArrayNode out=objectMapper.createArrayNode();
        for(String scope:Arrays.asList("TEAM","PUBLIC")) {
            TopicRouteEngine.State owner="TEAM".equals(scope)?state:world;
            if(owner==null)continue;
            for(Map.Entry<String,Number> entry:owner.getVariables().entrySet()) {
                if(!entry.getKey().startsWith("clue.") || entry.getValue()==null || entry.getValue().doubleValue()<1 || !labels.containsKey(entry.getKey()))continue;
                ObjectNode row=out.addObject().put("id",entry.getKey()).put("name",labels.get(entry.getKey())).put("scope",scope);
                row.set("sources",clueSources(snapshot,entry.getKey(),scope,state,world));
            }
        }
        return out;
    }

    private ArrayNode clueSources(JsonNode snapshot,String key,String scope,TopicRouteEngine.State team,TopicRouteEngine.State world) {
        // 声明此线索的来源站点（本队线索看 effects，公开线索看 worldEffects）
        List<String> declared=new ArrayList<>(),settled=new ArrayList<>();
        Iterator<Map.Entry<String,JsonNode>> configs=snapshot.path("nodeConfigs").fields();
        while(configs.hasNext()) {
            Map.Entry<String,JsonNode> node=configs.next();
            if(!declaresClue(node.getValue().path("interactionRules").path("PUBLIC".equals(scope)?"worldEffects":"effects"),key))continue;
            declared.add(node.getKey());
            if(clueSourceSettled(snapshot,Long.parseLong(node.getKey()),team,world))settled.add(node.getKey());
        }
        // 已能确认结算来源时只取这些；否则本队线索仅在来源唯一时可确定，公开线索对全场可读、取全部公开来源
        List<String> chosen=!settled.isEmpty()?settled:"PUBLIC".equals(scope) || declared.size()==1?declared:Collections.<String>emptyList();
        ArrayNode sources=objectMapper.createArrayNode();
        for(String nodeId:chosen) {
            JsonNode content=snapshot.path("clueContent").path(nodeId);JsonNode blocks=content.path("clues").path(key);
            if(!blocks.isArray() || blocks.size()==0)continue;
            ObjectNode source=sources.addObject().put("nodeId",Long.parseLong(nodeId)).put("title",content.path("title").asText(""));
            source.set("blocks",blocks.deepCopy());
        }
        return sources;
    }

    private static boolean declaresClue(JsonNode effects,String key) {
        for(JsonNode effect:effects)if(key.equals(effect.path("var").asText()))return true;
        return false;
    }

    /** 来源站点是否已由该归属结算：本队任务已结算，或其设施在本队/全场已有成功次数。 */
    private boolean clueSourceSettled(JsonNode snapshot,long nodeId,TopicRouteEngine.State team,TopicRouteEngine.State world) {
        JsonNode game=snapshot.path("themeConfig").path("gameplayRuntime");
        for(JsonNode quest:game.path("quests"))if(quest.path("nodeId").asLong()==nodeId && team!=null && team.getThemeRuntime().path("gameplay").path("slots").has(quest.path("settlementSlot").asText()))return true;
        for(JsonNode facility:game.path("facilities")) {
            if(facility.path("operationNodeId").asLong(-1)!=nodeId)continue;
            TopicRouteEngine.State owner="WORLD".equals(facility.path("scope").asText())?world:team;
            if(owner!=null && owner.getThemeRuntime().path("cooperation").path("facilities").path(facility.path("settlementSlot").asText()).path("count").asInt()>0)return true;
        }
        return false;
    }

    private JsonNode visibleToolQuest(GameSession session, TopicRouteEngine.State state, JsonNode node) {
        if (node == null || !node.isIntegralNumber()) return null;
        for (JsonNode quest : routeGameplay(session).path("quests")) if (quest.path("nodeId").asLong() == node.asLong()) {
            try { requireEligibleQuest(session, state, quest); return quest; }
            catch (GameSessionException unavailable) {
                JsonNode progress = state.getThemeRuntime().path("gameplay");
                JsonNode acts=routeGameplay(session).path("acts");
                String currentAct = acts.path(Math.min(progress.path("actIndex").asInt(),Math.max(0,acts.size()-1))).path("actKey").asText();
                if (!progress.path("awaitingUnlock").asBoolean() && quest.path("actKey").asText().equals(currentAct) && progress.path("quests").has(quest.path("questKey").asText())) return quest;
                // 额外节点工具在所属幕之后仍可用（营地休整/救助/供暖等）
                if (quest.path("extraOnly").asBoolean() && GameplayCooperationEngine.actPassed(sessionConfig(session).path("themeConfig"), state, quest.path("actKey").asText())) return quest;
            }
        }
        return null;
    }

    @Override
    public String routeSessionStatus(Long activityId) {
        if (activityId == null || activityId <= 0) return null;
        GameSession session = runtimeMapper.selectSessionByActivity(activityId);
        return isRouteSession(session) ? session.getStatus() : null;
    }

    @Override
    public boolean isRouteLeaderboardHidden(Long activityId) {
        if (activityId == null || activityId <= 0) return false;
        GameSession session = runtimeMapper.selectSessionByActivity(activityId);
        return isRouteSession(session) && !Boolean.TRUE.equals(session.getLeaderboardVisible());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assertRoutePlayable(Long activityId, Long nodeId) {
        if (activityId == null || activityId <= 0) return;
        GameSession session = runtimeMapper.selectSessionByActivityForUpdate(activityId);
        if (!isRouteSession(session)) return;
        if (isSharedRoute(session)) requireOnlineRun(runControl(session));
        if (!GameSession.RUNNING.equals(session.getStatus())) {
            throw new IllegalStateException("本场带队尚未开局或已结束");
        }
        if (GameplaySurvivalEngine.challengeNode(sessionConfig(session).path("themeConfig"), nodeId)) return;
        GameStationRun station = runtimeMapper.selectStation(session.getId(), nodeId);
        if (station == null || !GameStationRun.ACTIVE.equals(station.getStatus())) {
            throw new IllegalStateException("本站尚未开放或已暂停，请联系带队主理人");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assertRouteReception(Long activityId, Long nodeId) {
        if (activityId == null || activityId <= 0) return;
        GameSession session = runtimeMapper.selectSessionByActivityForUpdate(activityId);
        if (!isRouteSession(session) || session.getClubId() == null || session.getClubId() <= 0) return;
        if (isSharedRoute(session)) requireOnlineRun(runControl(session));
        if (GameplaySurvivalEngine.challengeNode(sessionConfig(session).path("themeConfig"), nodeId)) return;
        if (runtimeMapper.countMissingRouteReception(session.getId(), nodeId) > 0) {
            throw new IllegalStateException("请先由商家扫描带队主理人的团码完成上一站接待");
        }
    }

    @Override
    public List<GameMerchantEntryView> merchantEntries(GameActorContext actor) {
        requireActor(actor);
        if (!"MERCHANT".equals(actor.getPerspective())) {
            throw error("GAME_FORBIDDEN_SCOPE", "仅当前商家可查看本站入口");
        }
        requireMerchantStationView(actor);
        List<GameMerchantEntryView> entries = new ArrayList<>();
        for (GameMerchantEntryView entry : safe(runtimeMapper.selectMerchantEntries(merchantOwnerMemberId(actor)))) {
            if (entry == null || entry.getActivityId() == null || entry.getActivityId() <= 0
                    || entry.getTopicId() == null || entry.getTopicId() <= 0
                    || entry.getStationCount() == null || entry.getStationCount() <= 0
                    || StringUtils.isBlank(entry.getActivityName())) {
                continue;
            }
            entries.add(entry);
        }
        return entries;
    }

    @Override
    @Transactional(rollbackFor=Exception.class, isolation = Isolation.REPEATABLE_READ)
    public GameSessionView view(GameActorContext actor, Long activityId) {
        requireActor(actor);
        CmsActivity activity = requireActivity(activityId, false);
        GameSession session = runtimeMapper.selectSessionByActivity(activityId);
        if (session == null) {
            if ("CLUB".equals(actor.getPerspective())
                    && clubMemberService.isTopicOwner(activity.getTopicId(), activity.getClubId(), actor.getMemberId())) {
                GameSessionView notPrepared = baseView(null, activityId, "CLUB", "NOT_PREPARED", 0, null);
                notPrepared.setAvailableActions(Collections.singletonList("PREPARE"));
                return notPrepared;
            }
            throw error("GAME_SESSION_NOT_PREPARED", "本场游戏尚未准备");
        }
        if ("CLUB".equals(actor.getPerspective())) return clubView(actor, activity, session);
        if ("MERCHANT".equals(actor.getPerspective())) return merchantView(actor, activity, session);
        if ("PLAYER".equals(actor.getPerspective())) {
            if(isSharedRoute(session)){PlayerScope player=requirePlayer(actor,activity);session=refreshZeroHpRead(session,player.teamId,actor.getMemberId());}
            return playerView(actor, activity, session);
        }
        throw error("GAME_FORBIDDEN_SCOPE", "无权查看该游戏视图");
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> exportRecap(GameActorContext actor, Long activityId) {
        requireActor(actor);
        if (!"CLUB".equals(actor.getPerspective())) {
            throw error("GAME_FORBIDDEN_SCOPE", "仅该场俱乐部主理人可导出复盘");
        }
        CmsActivity activity = requireActivity(activityId, false);
        requireClubOwner(actor, activity);
        GameSession session = runtimeMapper.selectSessionByActivity(activityId);
        if (session == null) throw error("GAME_SESSION_NOT_PREPARED", "本场游戏尚未准备");
        Map<String, Object> recap = buildClubRecap(session, activity);
        if (recap == null) throw error("GAME_RECAP_UNAVAILABLE", "复盘数据尚未生成");
        Map<String, Object> exported = new LinkedHashMap<>();
        exported.put("schemaVersion", "GAME_RECAP_EXPORT_V1");
        exported.put("generatedAt", recap.get("generatedAt"));
        exported.put("activityId", activity.getId());
        exported.put("sessionId", session.getId());
        exported.put("recap", recap);
        return exported;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GameCommandReceiptView command(GameActorContext actor, GameSessionCommand command) {
        requireActor(actor);
        validateCommand(command);
        command.setAction(command.getAction().trim().toUpperCase(Locale.ROOT));
        String requestHash = requestHash(command);
        command.setRequestHash(requestHash);

        // A nonlocking receipt/activity read before waiting would pin an old RR snapshot.
        // PREPARE retains its existing unique-create path; do not acquire an absent-session gap lock.
        boolean preparing = "PREPARE".equals(command.getAction());
        GameSession lockedSession = preparing ? null : runtimeMapper.selectSessionByActivityForUpdate(command.getActivityId());
        GameCommandReceipt existing = runtimeMapper.selectReceipt(command.getActivityId(), actor.getMemberId(),
                command.getRequestId());
        if (existing != null) {
            authorizeSharedReceipt(actor, existing);
            return replayOrResendFailedBroadcast(actor, command, existing, requestHash, lockedSession);
        }

        CmsActivity activity = requireActivity(command.getActivityId(), !RUN_CONTROL_ACTIONS.contains(command.getAction()) && !"MANUAL_SIGN".equals(command.getAction()));
        GameSession session = preparing ? runtimeMapper.selectSessionByActivity(command.getActivityId()) : lockedSession;
        if (isSharedRoute(session)) {
            session = runtimeMapper.selectSessionByActivityForUpdate(command.getActivityId());
            if (!isSharedRoute(session)) throw error("GAME_STATE_CONFLICT", "本局已更新");
            GameCommandReceipt lockedReceipt = runtimeMapper.selectReceiptForUpdate(command.getActivityId(), actor.getMemberId(), command.getRequestId());
            if (lockedReceipt != null) { authorizeSharedReceipt(actor, lockedReceipt); return replay(lockedReceipt, requestHash); }
        }
        preauthorize(actor, activity, session, command);
        if (isSharedRoute(session)) requireSharedWrite(session, command);
        if (!preparing) {
            boolean independent = INDEPENDENT_PLAYER_ACTIONS.contains(command.getAction())
                    || (!isSharedRoute(session) && OBJECT_SCOPED_STAFF_ACTIONS.contains(command.getAction()));
            if (session == null || session.getRevision() == null
                    || command.getExpectedRevision() > session.getRevision()
                    || (!independent && !command.getExpectedRevision().equals(session.getRevision()))) {
                throw error("GAME_STATE_CONFLICT", "客户端版本已过期，请刷新后重试");
            }
        }

        GameCommandReceipt claim = receiptClaim(actor, command, requestHash, session);
        if (runtimeMapper.insertReceiptClaim(claim) != 1) {
            GameCommandReceipt concurrent = runtimeMapper.selectReceiptForUpdate(command.getActivityId(), actor.getMemberId(),
                    command.getRequestId());
            if (concurrent == null) throw error("GAME_STATE_CONFLICT", "命令正在处理中，请稍后读取回执");
            authorizeSharedReceipt(actor, concurrent); return replay(concurrent, requestHash);
        }

        Map<String, JsonNode> balancesBefore = isSharedRoute(session) ? routeBalances(session) : Collections.emptyMap();
        ActionResult applied = dispatch(actor, activity, session, command, claim.getId());
        if (isSharedRoute(applied.session)) appendRunEvent(applied.session, actor, command, claim.getId(), balancesBefore);
        claim.setGameSessionId(applied.session.getId());
        claim.setOutcome("APPLIED");
        claim.setRevision(applied.revision);
        if (isSharedRoute(applied.session) && "PLAYER".equals(actor.getPerspective())) applied.result.put("receiptOwnerTeamId", requirePlayer(actor, activity).teamId);
        claim.setResultJson(writeJson(applied.result));
        claim.setUpdateTime(clock.now());
        if (runtimeMapper.completeReceipt(claim) != 1) {
            throw error("GAME_STATE_CONFLICT", "命令回执落库失败");
        }
        return toReceiptView(claim, false);
    }

    @Override
    public GameCommandReceiptView receipt(GameActorContext actor, Long activityId, String requestId) {
        requireActor(actor);
        if (activityId == null || activityId <= 0 || StringUtils.isBlank(requestId)) {
            throw error("GAME_BAD_REQUEST", "缺少活动或请求编号");
        }
        GameCommandReceipt receipt = runtimeMapper.selectReceipt(activityId, actor.getMemberId(), requestId);
        if (receipt != null) { authorizeSharedReceipt(actor, receipt); return toReceiptView(receipt, true); }

        GameCommandReceiptView pending = new GameCommandReceiptView();
        pending.setActivityId(activityId);
        pending.setRequestId(requestId);
        pending.setOutcome("PENDING");
        pending.setReplayed(false);
        return pending;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public GameCommandReceiptView recordFailure(GameActorContext actor, GameSessionCommand command, GameSessionException failure) {
        if (actor == null || actor.getMemberId() == null || actor.getMemberId() <= 0 || command == null
                || command.getActivityId() == null || command.getActivityId() <= 0
                || StringUtils.isBlank(command.getRequestId()) || StringUtils.isBlank(command.getRequestHash())
                || StringUtils.isBlank(command.getAction()) || failure == null) {
            return null;
        }
        GameCommandReceipt existing = runtimeMapper.selectReceipt(command.getActivityId(), actor.getMemberId(),
                command.getRequestId());
        if (existing != null) {
            authorizeSharedReceipt(actor, existing);
            return replay(existing, command.getRequestHash());
        }
        GameSession session = runtimeMapper.selectSessionByActivity(command.getActivityId());
        try {
            validateCommand(command);
            preauthorize(actor, requireActivity(command.getActivityId(), false), session, command);
        } catch (GameSessionException denied) {
            return null;
        }
        GameCommandReceipt failed = receiptClaim(actor, command, command.getRequestHash(), session);
        failed.setOutcome("FAILED");
        failed.setReasonCode(failure.getErrorCode());
        failed.setResultJson(writeJson(singleton("reason", failure.getMessage())));
        if (runtimeMapper.insertReceiptClaim(failed) != 1) return null;
        return toReceiptView(failed, false);
    }

    private ActionResult dispatch(GameActorContext actor, CmsActivity activity, GameSession session,
                                  GameSessionCommand command, Long receiptId) {
        if (cooperationAction(command.getAction())) return routeCooperationAction(actor, activity, session, command, receiptId);
        if (RUN_CONTROL_ACTIONS.contains(command.getAction()) || "ARRIVAL_ISSUE".equals(command.getAction()) || "MANUAL_SIGN".equals(command.getAction())) {
            return runControlAction(actor, activity, session, command, receiptId);
        }
        switch (command.getAction()) {
            case "QUEST_START":
            case "QUEST_CANCEL":
            case "QUEST_RESOLVE": return routeQuestAction(actor, activity, session, command, receiptId);
            case "RECOVER": return routeRecover(actor, activity, session, command, receiptId);
            case "ZERO_HP_STEPS":
            case "ZERO_HP_SYNC": return routeZeroHpSync(actor,activity,session,command);
            case "OPERATION_START":
            case "OPERATION_CANCEL": return routeOperationAction(actor, activity, session, command, receiptId);
            case "ITEM_USE":
            case "ITEM_DISCARD":
            case "ITEM_RECEIVE": return routeItemAction(actor, activity, session, command, receiptId);
            case "PREPARE": return prepare(actor, activity);
            case "START": return start(actor, activity, session);
            case "ASSIGN_ROLES": return assignRoles(actor, activity, session, command);
            case "TAKEOVER_ROLE": return takeoverRole(actor, activity, session, command);
            case "CONFIRM_ROLE": return confirmRole(actor, activity, session);
            case "STATION_ACCEPT": return stationAccept(actor, session, command);
            case "STATION_DECLINE": return stationDecline(actor, session, command);
            case "STATION_READY": return stationReady(actor, session, command);
            case "STATION_PAUSE": return stationPause(actor, session, command);
            case "CLUB_STATION_PAUSE": return clubStationPause(actor, session, command);
            case "CLUB_STATION_RESUME": return clubStationResume(actor, activity, session, command);
            case "CLUB_REJECT_SUBMISSION": return clubRejectSubmission(actor, session, command);
            case "STATION_RESUME": return stationResume(actor, activity, session, command);
            case "PLAYER_CHOICE": return playerChoice(actor, activity, session, command);
            case "PLAYER_SUBMIT": return playerSubmit(actor, activity, session, command, receiptId);
            case "PLAYER_HINT": return playerHint(actor, activity, session, command);
            case "PLAYER_REVEAL": return playerReveal(actor, activity, session, command, receiptId);
            case "VERIFY_SUBMISSION": return verifySubmission(actor, session, command, receiptId);
            case "UNLOCK_CHAPTER": return unlockChapter(actor, session, command);
            case "BROADCAST": return broadcast(actor, session, command);
            case "SET_LEADERBOARD_VISIBILITY": return setLeaderboardVisibility(actor, session, command);
            case "FINISH": return finish(actor, session);
            default: throw error("GAME_ACTION_UNSUPPORTED", "不支持的游戏动作");
        }
    }

    private ActionResult prepare(GameActorContext actor, CmsActivity activity) {
        // Serialize configuration and INSERT ... SELECT node capture with content replacement.
        CmsTopic topic = topicMapper.selectCmsTopicByIdForUpdate(activity.getTopicId());
        String configJson = topic == null ? null : topic.getGameModuleConfigJson();
        boolean route = isRouteTopic(topic);
        JsonNode config;
        if (route) {
            ObjectNode frozen = (ObjectNode) readRouteJson(topicRouteRuntimeService.freezePublishedJourney(topic));
            frozen.put("playMode", "ROUTE");
            frozen.put("publishedConfigVersion", topic.getPublishedConfigVersion());
            config = frozen;
        } else config = validateModuleConfig(configJson);

        GameSession session = new GameSession();
        Date now = clock.now();
        session.setActivityId(activity.getId());
        session.setTopicId(activity.getTopicId());
        session.setClubId(activity.getClubId());
        session.setModuleConfigSnapshotJson(writeJson(config));
        session.setStatus(GameSession.PREPARING);
        session.setCurrentChapterId(firstChapterId(config));
        session.setRevision(0);
        session.setPreparedBy(actor.getMemberId());
        session.setLeaderboardVisible(false);
        session.setCreateTime(now);
        session.setUpdateTime(now);
        if (runtimeMapper.insertSession(session) != 1) {
            GameSession concurrent = runtimeMapper.selectSessionByActivityForUpdate(activity.getId());
            if (concurrent == null) throw error("GAME_STATE_CONFLICT", "游戏准备失败");
            return result(concurrent, singleton("prepared", false));
        }
        if (isSharedRoute(session)) {
            if (config.path("runtimeSnapshotVersion").asInt() != 1) throw error("GAME_CONFIG_INVALID", "共享玩法需要重新审核发布完整规则");
            List<Long> teams = safe(runtimeMapper.selectActiveTeamIds(activity.getId()));
            if (teams.isEmpty()) throw error("GAME_TEAM_REQUIRED", "共享玩法需要本场有效队伍");
            for (Long teamId : teams) initializeRouteOwner(session, "TEAM", teamId);
            initializeRunControl(session);
            if ("WORLD_SHARED".equals(GameplayRuntimeConfig.getWorldMode(config.path("themeConfig")))) initializeWorldOwner(session);
        }
        runtimeMapper.insertStationsFromTopic(session.getId(), activity.getId(), activity.getTopicId(), now);
        List<GameStationRun> stations = safe(runtimeMapper.selectStations(session.getId()));
        if (stations.isEmpty()) throw error("GAME_CONFIG_INVALID", "主题没有可运行站点");
        if (route) {
            if (config.path("routeStations").isArray()) {
                Map<Long, JsonNode> published = new LinkedHashMap<>();
                for (JsonNode node : config.path("routeStations")) published.put(node.path("nodeId").asLong(), node);
                if (published.size() != stations.size()) throw error("GAME_CONFIG_INVALID", "站点清单已变更，请重新审核发布");
                for (GameStationRun station : stations) {
                    JsonNode frozen = published.get(station.getNodeId());
                    if (frozen == null || !Objects.equals(frozen.path("nodeName").asText(), station.getNodeName())
                            || (frozen.has("merchantMemberId") && !Objects.equals(frozen.path("merchantMemberId").asLong(), station.getMerchantMemberId()))) {
                        throw error("GAME_CONFIG_INVALID", "站点绑定已变更，请重新审核发布");
                    }
                }
            }
            if (activity.getStartDate() == null || activity.getEndDate() == null
                    || !activity.getEndDate().after(activity.getStartDate())) {
                throw error("GAME_CONFIG_INVALID", "请先配置本场开始和结束时间");
            }
            runtimeMapper.readyRouteCheckpoints(session.getId(), activity.getStartDate(), activity.getEndDate(), now);
        } else {
            applyStationCodes(session.getId(), stations, config.path("stations"));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("prepared", true);
        data.put("stationCount", stations.size());
        return result(session, data);
    }

    private ActionResult start(GameActorContext actor, CmsActivity activity, GameSession session) {
        if (isRouteSession(session)) requireSessionStatus(session, GameSession.PREPARING, GameSession.READY);
        else requireSessionStatus(session, GameSession.READY);
        List<String> blockers = readinessBlockers(session);
        if (!blockers.isEmpty()) {
            throw error("GAME_STATION_NOT_READY", "开局条件未满足：" + String.join("；", blockers));
        }
        Date now = clock.now();
        requireActivityServiceWindow(activity, now);
        List<GameStationRun> stations = safe(runtimeMapper.selectStations(session.getId()));
        List<String> stationBlockers = startStationBlockers(stations, now);
        if (!stationBlockers.isEmpty()) {
            throw error("GAME_STATION_NOT_READY", "开局条件未满足：" + String.join("；", stationBlockers));
        }
        int activated = runtimeMapper.activateReadyStations(session.getId(), now);
        if (activated != stations.size()) {
            throw error("GAME_STATE_CONFLICT", "站点状态已变化，未能完整启动，请刷新后重试");
        }
        bumpSession(session, GameSession.RUNNING, session.getCurrentChapterId(), actor.getMemberId(), now);
        return result(session, singleton("startedAt", formatDate(now)));
    }

    @SuppressWarnings("unchecked")
    private ActionResult assignRoles(GameActorContext actor, CmsActivity activity, GameSession session,
                                     GameSessionCommand command) {
        requireSessionStatus(session, GameSession.PREPARING, GameSession.READY);
        Long teamId = positiveLong(command.getPayload().get("teamId"));
        Object raw = command.getPayload().get("assignments");
        if (teamId == null || !(raw instanceof List) || ((List<?>) raw).isEmpty()) {
            throw error("GAME_BAD_REQUEST", "请选择队伍并分配角色");
        }
        Map<String, String> allowedRoles = configuredRoles(session);
        List<Map<String, Object>> appliedAssignments = new ArrayList<>();
        Set<Long> seenMembers = new HashSet<>();
        boolean changed = false;
        for (Object item : (List<?>) raw) {
            if (!(item instanceof Map)) throw error("GAME_BAD_REQUEST", "角色分配格式不正确");
            Map<String, Object> assignmentMap = (Map<String, Object>) item;
            Long memberId = positiveLong(assignmentMap.get("memberId"));
            String roleCode = upper(assignmentMap.get("roleCode"));
            if (memberId == null || !allowedRoles.containsKey(roleCode)
                    || runtimeMapper.isActiveTeamMember(activity.getId(), teamId, memberId) != 1) {
                throw error("GAME_FORBIDDEN_SCOPE", "角色成员不属于本场队伍或角色不可用");
            }
            if (!seenMembers.add(memberId)) throw error("GAME_BAD_REQUEST", "同一成员不能重复分配角色");
            GameTeamRoleAssignment active = runtimeMapper.selectActiveRoleForUpdate(session.getId(), memberId);
            boolean unchanged = active != null && teamId.equals(active.getTeamId())
                    && roleCode.equals(active.getRoleCode());
            Date now = clock.now();
            if (!unchanged && active != null
                    && runtimeMapper.supersedeActiveRole(session.getId(), memberId, actor.getMemberId(), now) != 1) {
                throw error("GAME_STATE_CONFLICT", "角色分配已变化，请刷新后重试");
            }
            GameTeamRoleAssignment assignment = new GameTeamRoleAssignment();
            assignment.setGameSessionId(session.getId());
            assignment.setTeamId(teamId);
            assignment.setMemberId(memberId);
            assignment.setRoleCode(roleCode);
            assignment.setRoleName(allowedRoles.get(roleCode));
            assignment.setStatus("ASSIGNED");
            assignment.setAssignedBy(actor.getMemberId());
            assignment.setRevision(0);
            assignment.setCreateTime(now);
            assignment.setUpdateTime(now);
            if (!unchanged && runtimeMapper.insertRole(assignment) != 1) {
                GameTeamRoleAssignment concurrent = runtimeMapper.selectActiveRoleForUpdate(session.getId(), memberId);
                if (concurrent == null || !teamId.equals(concurrent.getTeamId())
                        || !roleCode.equals(concurrent.getRoleCode())) {
                    throw error("GAME_STATE_CONFLICT", "角色分配失败");
                }
                unchanged = true;
            }
            changed |= !unchanged;
            Map<String, Object> applied = new LinkedHashMap<>();
            applied.put("memberId", memberId);
            applied.put("roleCode", roleCode);
            applied.put("changed", !unchanged);
            appliedAssignments.add(applied);
        }
        if (changed) {
            String next = readinessStatus(session);
            bumpSession(session, next, session.getCurrentChapterId(), actor.getMemberId(), clock.now());
        } else {
            GameSession current = runtimeMapper.selectSessionByActivityForUpdate(activity.getId());
            if (current != null) session = current;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("teamId", teamId);
        data.put("assignments", appliedAssignments);
        return result(session, data);
    }

    private ActionResult takeoverRole(GameActorContext actor, CmsActivity activity, GameSession session,
                                      GameSessionCommand command) {
        requireSessionStatus(session, GameSession.RUNNING);
        Long teamId = positiveLong(command.getPayload().get("teamId"));
        Long sourceMemberId = positiveLong(command.getPayload().get("sourceMemberId"));
        Long targetMemberId = positiveLong(command.getPayload().get("targetMemberId"));
        String reason = text(command.getPayload().get("reason"), 200);
        if (teamId == null || sourceMemberId == null || targetMemberId == null
                || sourceMemberId.equals(targetMemberId) || StringUtils.isBlank(reason)) {
            throw error("GAME_BAD_REQUEST", "请选择同队角色来源、接管成员并填写原因");
        }
        if (runtimeMapper.isActiveTeamMember(activity.getId(), teamId, sourceMemberId) != 1
                || runtimeMapper.isActiveTeamMember(activity.getId(), teamId, targetMemberId) != 1) {
            throw error("GAME_FORBIDDEN_SCOPE", "角色来源与接管成员必须属于本场同一队伍");
        }
        GameTeamRoleAssignment source = runtimeMapper.selectActiveRoleForUpdate(session.getId(), sourceMemberId);
        if (source == null || !teamId.equals(source.getTeamId()) || !"CONFIRMED".equals(source.getStatus())) {
            throw error("GAME_ROLE_NOT_ASSIGNED", "来源成员当前没有可接管的已确认角色");
        }
        if (runtimeMapper.selectActiveRoleForUpdate(session.getId(), targetMemberId) != null) {
            throw error("GAME_ROLE_ALREADY_ASSIGNED", "接管成员已经持有角色");
        }
        checkCommandText(actor.getMemberId(), reason, "接管原因未通过安全审核");
        Date now = clock.now();
        if (runtimeMapper.supersedeActiveRole(session.getId(), sourceMemberId, actor.getMemberId(), now) != 1) {
            throw error("GAME_STATE_CONFLICT", "来源角色已变化，请刷新后重试");
        }
        GameTeamRoleAssignment target = new GameTeamRoleAssignment();
        target.setGameSessionId(session.getId());
        target.setTeamId(teamId);
        target.setMemberId(targetMemberId);
        target.setRoleCode(source.getRoleCode());
        target.setRoleName(source.getRoleName());
        target.setStatus("CONFIRMED");
        target.setAssignedBy(actor.getMemberId());
        target.setRevision(0);
        target.setCreateTime(now);
        target.setUpdateTime(now);
        if (runtimeMapper.insertRole(target) != 1) {
            throw error("GAME_STATE_CONFLICT", "接管成员角色已变化，请刷新后重试");
        }
        bumpSession(session, session.getStatus(), session.getCurrentChapterId(), actor.getMemberId(), now);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("teamId", teamId);
        data.put("sourceMemberId", sourceMemberId);
        data.put("targetMemberId", targetMemberId);
        data.put("roleCode", source.getRoleCode());
        data.put("reason", reason);
        return result(session, data);
    }

    private ActionResult confirmRole(GameActorContext actor, CmsActivity activity, GameSession session) {
        requireSessionStatus(session, GameSession.PREPARING, GameSession.READY);
        requirePlayer(actor, activity);
        boolean changed = runtimeMapper.confirmRole(session.getId(), actor.getMemberId(),
                "ASSIGNED", "CONFIRMED", clock.now()) == 1;
        if (!changed) {
            GameTeamRoleAssignment existing = runtimeMapper.selectRole(session.getId(), actor.getMemberId());
            if (existing == null || !"CONFIRMED".equals(existing.getStatus())) {
                throw error("GAME_ROLE_NOT_ASSIGNED", "尚未分配可确认的角色");
            }
        }
        if (changed) {
            String next = readinessStatus(session);
            bumpSession(session, next, session.getCurrentChapterId(), actor.getMemberId(), clock.now());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("confirmed", true);
        data.put("changed", changed);
        return result(session, data);
    }

    private ActionResult stationReady(GameActorContext actor, GameSession session, GameSessionCommand command) {
        requireSessionStatus(session, GameSession.PREPARING, GameSession.READY);
        GameStationRun station = requireMerchantStation(actor, session, command.getNodeId());
        if (!GameStationRun.ACCEPTED.equals(station.getStatus())) {
            throw error("GAME_STATE_CONFLICT", "站点当前不能确认就绪");
        }
        ReadyConfig ready = readyConfig(command.getPayload(), session, station);
        station.setPreparationSnapshotJson(writeJson(ready.preparation));
        station.setCapacity(ready.capacity);
        station.setServiceStartAt(ready.startAt);
        station.setServiceEndAt(ready.endAt);
        if (runtimeMapper.updateStationStatusCas(station, merchantOwnerMemberId(actor), GameStationRun.ACCEPTED,
                GameStationRun.READY, station.getRevision(), clock.now()) != 1) {
            throw error("GAME_STATE_CONFLICT", "站点状态已变化，请刷新后重试");
        }
        station.setStatus(GameStationRun.READY);
        station.setRevision(station.getRevision() == null ? 1 : station.getRevision() + 1);
        String next = readinessStatus(session);
        bumpSession(session, next, session.getCurrentChapterId(), actor.getMemberId(), clock.now());
        return result(session, singleton("station", stationView(station, sessionConfig(session))));
    }

    private ActionResult stationAccept(GameActorContext actor, GameSession session, GameSessionCommand command) {
        requireSessionStatus(session, GameSession.PREPARING);
        GameStationRun station = requireMerchantStation(actor, session, command.getNodeId());
        if (!GameStationRun.INVITED.equals(station.getStatus())) {
            throw error("GAME_STATE_CONFLICT", "站点邀请已处理");
        }
        transitionStation(actor, session, station, GameStationRun.INVITED, GameStationRun.ACCEPTED);
        return result(session, singleton("station", stationView(station, sessionConfig(session))));
    }

    private ActionResult stationDecline(GameActorContext actor, GameSession session, GameSessionCommand command) {
        requireSessionStatus(session, GameSession.PREPARING);
        GameStationRun station = requireMerchantStation(actor, session, command.getNodeId());
        if (!GameStationRun.INVITED.equals(station.getStatus())) {
            throw error("GAME_STATE_CONFLICT", "站点邀请已处理");
        }
        String reasonCode = upper(command.getPayload().get("reasonCode"));
        String reason = text(command.getPayload().get("reason"), 200);
        if (StringUtils.isBlank(reasonCode) || StringUtils.isBlank(reason)) {
            throw error("GAME_BAD_REQUEST", "拒绝邀请必须填写结构化原因和说明");
        }
        station.setPauseReasonCode(reasonCode);
        station.setPauseReason(reason);
        transitionStationState(actor, station, GameStationRun.INVITED, GameStationRun.CLOSED);
        bumpSession(session, readinessStatus(session), session.getCurrentChapterId(), actor.getMemberId(), clock.now());
        return result(session, singleton("station", stationView(station, sessionConfig(session))));
    }

    private ActionResult stationPause(GameActorContext actor, GameSession session, GameSessionCommand command) {
        requireSessionStatus(session, GameSession.RUNNING);
        GameStationRun station = requireMerchantStation(actor, session, command.getNodeId());
        if (GameStationRun.PAUSED.equals(station.getStatus())) throw stationAlreadyPaused();
        if (!GameStationRun.ACTIVE.equals(station.getStatus())) throw error("GAME_STATE_CONFLICT", "站点当前不能暂停");
        String reasonCode = upper(command.getPayload().get("reasonCode"));
        if (StringUtils.isBlank(reasonCode)) throw error("GAME_BAD_REQUEST", "请选择暂停原因");
        station.setPauseReasonCode(reasonCode);
        station.setPauseReason(text(command.getPayload().get("reason"), 200));
        station.setResumeEta(DateUtils.parseDate(command.getPayload().get("resumeEta")));
        if (station.getResumeEta() == null) throw error("GAME_BAD_REQUEST", "请选择预计恢复时间");
        if (command.getPayload().containsKey("fallbackNodeId")) {
            throw error("GAME_BAD_REQUEST", "备用站点必须由已批准方案解析");
        }
        String planCode = upper(command.getPayload().get("fallbackPlanCode"));
        Integer planVersion = Convert.toInt(command.getPayload().get("fallbackPlanVersion"), 0);
        bindFallbackOnPause(station, resolvePauseFallback(session, station, merchantOwnerMemberId(actor), planCode, planVersion));
        transitionStation(actor, session, station, GameStationRun.ACTIVE, GameStationRun.PAUSED);
        return result(session, singleton("station", stationView(station, sessionConfig(session))));
    }

    /**
     * R9-38:没有已批准备用方案的现实停业(人员离岗/设备故障)也必须能停 —— 运营没配兜底路线
     * 不是"不可履约"的理由。给了方案就仍按已批准版本严格解析(见 resolveFallbackPlan),
     * 没给就不绑兜底:暂停期间 requirePlayableStation 拒绝一切新接待,恢复照原状态机走。
     * 不虚构兜底站点,也不碰任何已有订单。
     */
    private FallbackPlan resolvePauseFallback(GameSession session, GameStationRun station,
                                              Long sourceMerchantId, String planCode, Integer planVersion) {
        if (StringUtils.isBlank(planCode)) return null;
        return resolveFallbackPlan(session, station, sourceMerchantId, planCode, planVersion);
    }

    private void bindFallbackOnPause(GameStationRun station, FallbackPlan fallback) {
        station.setFallbackNodeId(fallback == null ? null : fallback.target.getNodeId());
        station.setFallbackPlanCode(fallback == null ? null : fallback.planCode);
        station.setFallbackPlanVersion(fallback == null ? null : fallback.version);
    }

    /* ——— Figma D8 现场事件:俱乐部侧的三个处理动作 ———
     *
     * 与商家那三个同形动作的**唯一**区别是站点归属判据:商家要求「站点属于我」,
     * 俱乐部要求「站点属于本场」。其余状态机、CAS、原因必填一律照抄商家那条,
     * 不另立一套 —— 两套状态机迟早会分叉。
     */

    /** 站点必须属于本场游戏。这是俱乐部版的 requireMerchantStation。 */
    private GameStationRun requireSessionStation(GameSession session, Long nodeId) {
        GameStationRun station = nodeId == null ? null : runtimeMapper.selectStation(session.getId(), nodeId);
        if (station == null || !session.getId().equals(station.getGameSessionId())) {
            throw error("GAME_FORBIDDEN_SCOPE", "站点不属于本场游戏");
        }
        return station;
    }

    private ActionResult clubStationPause(GameActorContext actor, GameSession session, GameSessionCommand command) {
        requireSessionStatus(session, GameSession.RUNNING);
        GameStationRun station = requireSessionStation(session, command.getNodeId());
        if (GameStationRun.PAUSED.equals(station.getStatus())) throw stationAlreadyPaused();
        if (!GameStationRun.ACTIVE.equals(station.getStatus())) throw error("GAME_STATE_CONFLICT", "站点当前不能暂停");
        String reasonCode = upper(command.getPayload().get("reasonCode"));
        if (StringUtils.isBlank(reasonCode)) throw error("GAME_BAD_REQUEST", "请选择暂停原因");
        station.setPauseReasonCode(reasonCode);
        station.setPauseReason(text(command.getPayload().get("reason"), 200));
        station.setResumeEta(DateUtils.parseDate(command.getPayload().get("resumeEta")));
        if (station.getResumeEta() == null) throw error("GAME_BAD_REQUEST", "请选择预计恢复时间");
        if (command.getPayload().containsKey("fallbackNodeId")) {
            throw error("GAME_BAD_REQUEST", "备用站点必须由已批准方案解析");
        }
        // ★ 兜底方案照样只能从**已批准**的方案里解析,俱乐部不能绕过这道。
        //   「兜底完成」不是这里点出来的动作:它是玩家走到兜底站点后由
        //   ensureFallbackSourceOutcomes 自动写的,前提正是这里绑定的这个方案。
        //   R9-38:但"没有方案"不再是"不能停"——没给方案就停本站,玩家看到暂停安排。
        String planCode = upper(command.getPayload().get("fallbackPlanCode"));
        Integer planVersion = Convert.toInt(command.getPayload().get("fallbackPlanVersion"), 0);
        bindFallbackOnPause(station, resolvePauseFallback(session, station, station.getMerchantMemberId(),
                planCode, planVersion));
        transitionStation(actor, session, station, GameStationRun.ACTIVE, GameStationRun.PAUSED);
        return result(session, singleton("station", stationView(station, sessionConfig(session))));
    }

    private ActionResult clubStationResume(GameActorContext actor, CmsActivity activity, GameSession session,
                                           GameSessionCommand command) {
        requireSessionStatus(session, GameSession.RUNNING);
        GameStationRun station = requireSessionStation(session, command.getNodeId());
        if (GameStationRun.ACTIVE.equals(station.getStatus())) {
            // 与暂停同口径:已被另一终端恢复时给明确的「无变化」,而不是让后到的一方看到「待核对/版本过期」
            throw stationAlreadyActive();
        }
        if (!GameStationRun.PAUSED.equals(station.getStatus())) throw error("GAME_STATE_CONFLICT", "站点当前不能恢复");
        Date now = evaluationTime();
        requireActivityServiceWindow(activity, now);
        requireStationServiceWindow(station, now);
        station.setPauseReasonCode(null);
        station.setPauseReason(null);
        station.setResumeEta(null);
        station.setFallbackNodeId(null);
        station.setFallbackPlanCode(null);
        station.setFallbackPlanVersion(null);
        transitionStation(actor, session, station, GameStationRun.PAUSED, GameStationRun.ACTIVE);
        return result(session, singleton("station", stationView(station, sessionConfig(session))));
    }

    /**
     * 驳回重交。**只能驳回,不能通过**:俱乐部拿不到 evidence_json(见
     * selectClubSubmissionProjection),看不到照片与答案就「通过」等于盖橡皮章,
     * 那是商家 VERIFY_SUBMISSION 的职责。
     */
    private ActionResult clubRejectSubmission(GameActorContext actor, GameSession session,
                                              GameSessionCommand command) {
        requireSessionStatus(session, GameSession.RUNNING);
        if (command.getPayload().containsKey("decision")
                && !"REJECT".equals(upper(command.getPayload().get("decision")))) {
            throw error("GAME_FORBIDDEN_SCOPE", "俱乐部只能驳回重交,通过与否由本站商家判定");
        }
        Long submissionId = positiveLong(command.getPayload().get("submissionId"));
        GameTaskSubmission submission = submissionId == null ? null : runtimeMapper.selectSubmission(submissionId);
        if (submission == null || !session.getId().equals(submission.getGameSessionId())) {
            throw error("GAME_SUBMISSION_NOT_PENDING", "待核验任务不存在");
        }
        if (!"PENDING".equals(submission.getStatus())) {
            throw error("GAME_SUBMISSION_NOT_PENDING", "该任务不在待核验队列");
        }
        GameStationRun station = requireSessionStation(session, submission.getNodeId());
        if (!station.getId().equals(submission.getStationRunId())) {
            throw error("GAME_FORBIDDEN_SCOPE", "任务不属于该站点");
        }
        String reasonCode = upper(command.getPayload().get("reasonCode"));
        // 和商家驳回同一条:驳回必须给原因,否则玩家不知道要改什么
        if (StringUtils.isBlank(reasonCode)) throw error("GAME_BAD_REQUEST", "驳回任务必须选择原因");
        submission.setStatus("REJECTED");
        submission.setDecidedBy(actor.getMemberId());
        submission.setDecisionReasonCode(reasonCode);
        submission.setDecisionReason(text(command.getPayload().get("reason"), 200));
        submission.setUpdateTime(clock.now());
        if (runtimeMapper.decideSubmissionCas(submission) != 1) {
            throw error("GAME_SUBMISSION_NOT_PENDING", "该任务已被处理");
        }
        bumpSession(session, session.getStatus(), session.getCurrentChapterId(), actor.getMemberId(), clock.now());
        return result(session, singleton("submissionId", submission.getId()));
    }

    private ActionResult stationResume(GameActorContext actor, CmsActivity activity, GameSession session,
                                       GameSessionCommand command) {
        requireSessionStatus(session, GameSession.RUNNING);
        GameStationRun station = requireMerchantStation(actor, session, command.getNodeId());
        if (GameStationRun.ACTIVE.equals(station.getStatus())) throw stationAlreadyActive();
        if (!GameStationRun.PAUSED.equals(station.getStatus())) throw error("GAME_STATE_CONFLICT", "站点当前不能恢复");
        Date now = evaluationTime();
        requireActivityServiceWindow(activity, now);
        requireStationServiceWindow(station, now);
        station.setPauseReasonCode(null);
        station.setPauseReason(null);
        station.setResumeEta(null);
        station.setFallbackNodeId(null);
        station.setFallbackPlanCode(null);
        station.setFallbackPlanVersion(null);
        transitionStation(actor, session, station, GameStationRun.PAUSED, GameStationRun.ACTIVE);
        return result(session, singleton("station", stationView(station, sessionConfig(session))));
    }

    /*
     * SC-CITY-RESUME-RACE:两台终端同时暂停/恢复时,后到的那次什么都没改。它不能落 APPLIED 回执 ——
     * 复盘的 pauseEventCount / merchantPauseEvents 按 APPLIED 计数,会把一次迁移记成两次事故。
     * 抛出后整条命令回滚,控制器按原因码记一张 FAILED 回执,且不再写第二条 sys_oper_log。
     */
    private GameSessionException stationAlreadyPaused() {
        return error(GameSessionException.STATION_ALREADY_PAUSED, "站点已暂停，无需重复操作");
    }

    private GameSessionException stationAlreadyActive() {
        return error(GameSessionException.STATION_ALREADY_ACTIVE, "站点已在接待，无需重复恢复");
    }

    private void transitionStation(GameActorContext actor, GameSession session, GameStationRun station,
                                   String fromStatus, String toStatus) {
        transitionStationState(actor, station, fromStatus, toStatus);
        bumpSession(session, session.getStatus(), session.getCurrentChapterId(), actor.getMemberId(), clock.now());
    }

    private void transitionStationState(GameActorContext actor, GameStationRun station,
                                        String fromStatus, String toStatus) {
        Long stationOwner = "CLUB".equals(actor.getPerspective())
                ? station.getMerchantMemberId() : merchantOwnerMemberId(actor);
        if (runtimeMapper.updateStationStatusCas(station, stationOwner, fromStatus, toStatus,
                station.getRevision(), clock.now()) != 1) {
            throw error("GAME_STATE_CONFLICT", "站点状态已变化，请刷新后重试");
        }
        station.setStatus(toStatus);
        station.setRevision(station.getRevision() == null ? 1 : station.getRevision() + 1);
    }

    private ActionResult playerChoice(GameActorContext actor, CmsActivity activity, GameSession session,
                                      GameSessionCommand command) {
        requireSessionStatus(session, GameSession.RUNNING);
        PlayerScope player = requirePlayer(actor, activity);
        GameStationRun station = requirePlayableStation(activity, session, command.getNodeId());
        requireArrived(actor, activity, station);
        GameTeamRoleAssignment role = runtimeMapper.selectRole(session.getId(), actor.getMemberId());
        if (role == null) throw error("GAME_ROLE_NOT_ASSIGNED", "尚未分配剧情角色");
        String choiceId = text(command.getPayload().get("choiceId"), 64);
        JsonNode config = sessionConfig(session);
        GamePlayerNodeOutcome outcome = runtimeMapper.selectPlayerNodeOutcome(
                session.getId(), actor.getMemberId(), station.getNodeId());
        if (outcome == null) {
            throw error("GAME_TASK_NOT_APPROVED", "本站任务核验通过后才能选择剧情行动");
        }
        JsonNode choice = findChoice(config, choiceId, station.getStationCode(), role.getRoleCode(),
                session.getCurrentChapterId());
        GameStoryState story = storyState(session, player.teamId, config);
        ArrayNode appliedChoices = readArray(story.getAppliedChoicesJson());
        if (containsText(appliedChoices, choiceId)) {
            return result(session, choiceResult(choiceId, true, findEnding(config, story.getEndingCode())));
        }
        if ("ENDING_RESOLVED".equals(story.getStatus())) {
            throw error("GAME_STORY_ALREADY_RESOLVED", "本队剧情结局已经确定");
        }
        if (choiceGroupAlreadyApplied(config, appliedChoices, choice)) {
            throw error("GAME_CHOICE_GROUP_RESOLVED", "本阶段剧情行动已经确定");
        }
        ObjectNode variables = readObject(story.getVariablesJson());
        JsonNode effects = choice.path("effects");
        if (effects.isArray()) applyRouteEffects(variables, effects);
        else applyEffects(variables, effects);
        appliedChoices.add(choiceId);
        story.setVariablesJson(writeJson(variables));
        story.setAppliedChoicesJson(writeJson(appliedChoices));
        JsonNode ending = allRequiredChoicesApplied(config, appliedChoices)
                ? resolveEnding(config.path("story").path("endings"), variables) : null;
        if (ending != null) {
            story.setEndingCode(ending.path("code").asText());
            story.setStatus("ENDING_RESOLVED");
        } else {
            story.setStatus("VARIABLES_UPDATED");
        }
        if (runtimeMapper.updateStoryStateCas(story) != 1) {
            GameStoryState concurrent = runtimeMapper.selectStoryStateForUpdate(session.getId(), "TEAM", player.teamId);
            if (concurrent != null && containsText(readArray(concurrent.getAppliedChoicesJson()), choiceId)) {
                GameSession current = runtimeMapper.selectSessionByActivityForUpdate(activity.getId());
                return result(current == null ? session : current,
                        choiceResult(choiceId, true, findEnding(config, concurrent.getEndingCode())));
            }
            throw error("GAME_STATE_CONFLICT", "剧情状态已变化");
        }
        bumpSession(session, session.getStatus(), session.getCurrentChapterId(), actor.getMemberId(), clock.now());
        return result(session, choiceResult(choiceId, false, ending));
    }

    private ActionResult playerHint(GameActorContext actor, CmsActivity activity, GameSession session,
                                    GameSessionCommand command) {
        requireSessionStatus(session, GameSession.RUNNING);
        requirePlayer(actor, activity);
        GameStationRun station = requirePlayableStation(activity, session, command.getNodeId());
        requireArrived(actor, activity, station);
        if (runtimeMapper.selectPlayerNodeOutcome(session.getId(), actor.getMemberId(), station.getNodeId()) != null) {
            throw error("GAME_TASK_ALREADY_COMPLETED", "本站任务已经完成");
        }
        JsonNode policy = requireHintPolicy(sessionConfig(session), station);
        int currentLevel = playerHintLevel(session.getId(), actor.getMemberId(), station.getNodeId());
        int requestedLevel = Convert.toInt(command.getPayload().get("level"), 0);
        if (requestedLevel != currentLevel + 1 || requestedLevel > 2) {
            throw error("GAME_HINT_LEVEL_INVALID", "提示必须按一级、二级顺序开启");
        }
        bumpSession(session, session.getStatus(), session.getCurrentChapterId(), actor.getMemberId(), clock.now());
        return result(session, singleton("hint", hintView(policy, requestedLevel, null)));
    }

    private ActionResult playerReveal(GameActorContext actor, CmsActivity activity, GameSession session,
                                      GameSessionCommand command, Long receiptId) {
        requireSessionStatus(session, GameSession.RUNNING);
        PlayerScope player = requirePlayer(actor, activity);
        GameStationRun station = requirePlayableStation(activity, session, command.getNodeId());
        requireArrived(actor, activity, station);
        GamePlayerNodeOutcome existing = runtimeMapper.selectPlayerNodeOutcome(
                session.getId(), actor.getMemberId(), station.getNodeId());
        if (existing != null) throw error("GAME_TASK_ALREADY_COMPLETED", "本站任务已经完成");
        JsonNode policy = requireHintPolicy(sessionConfig(session), station);
        if (playerHintLevel(session.getId(), actor.getMemberId(), station.getNodeId()) != 2) {
            throw error("GAME_HINT_LEVEL_INVALID", "开启二级提示后才能揭示答案");
        }
        GamePlayerNodeOutcome outcome = ensureOutcome(session, station, player.teamId, actor.getMemberId(),
                GamePlayerNodeOutcome.FALLBACK_COMPLETED, "ANSWER_REVEAL", null, receiptId, null, null);
        ensureFallbackSourceOutcomes(session, station, player.teamId, actor.getMemberId(), receiptId);
        bumpSession(session, session.getStatus(), session.getCurrentChapterId(), actor.getMemberId(), clock.now());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("revealText", policy.path("reveal").path("text").asText());
        addOutcomeResult(data, outcome);
        return result(session, data);
    }

    private ActionResult playerSubmit(GameActorContext actor, CmsActivity activity, GameSession session,
                                      GameSessionCommand command, Long receiptId) {
        requireSessionStatus(session, GameSession.RUNNING);
        PlayerScope player = requirePlayer(actor, activity);
        GameStationRun station = requirePlayableStation(activity, session, command.getNodeId());
        requireArrived(actor, activity, station);
        GameTaskSubmission submission = new GameTaskSubmission();
        submission.setGameSessionId(session.getId());
        submission.setActivityId(activity.getId());
        submission.setStationRunId(station.getId());
        submission.setNodeId(station.getNodeId());
        submission.setTeamId(player.teamId);
        submission.setMemberId(actor.getMemberId());
        String taskCode = text(command.getPayload().get("taskCode"), 64);
        JsonNode config = sessionConfig(session);
        JsonNode taskConfig = configuredTask(config, station);
        Map<String, Object> configuredTask = publicTask(config, station);
        Object evidenceUrls = command.getPayload().get("evidenceUrls");
        if (configuredTask == null || taskConfig == null) throw error("GAME_CONFIG_INVALID", "本站任务配置不可用");
        if (StringUtils.isBlank(taskCode) || !taskCode.equals(configuredTask.get("taskCode"))) {
            throw error("GAME_BAD_REQUEST", "任务编号与本站配置不一致");
        }
        String inputType = String.valueOf(configuredTask.get("inputType"));
        validateEvidence(inputType, evidenceUrls);
        submission.setTaskCode(taskCode);
        GameTaskSubmission active = runtimeMapper.selectActiveSubmission(session.getId(), actor.getMemberId(),
                station.getNodeId(), taskCode);
        if (active != null) {
            GamePlayerNodeOutcome outcome = recoverSubmissionOutcome(session, station, player.teamId,
                    actor.getMemberId(), active, taskConfig, receiptId);
            return result(session, submissionResult(active, outcome, true));
        }
        submission.setEvidenceJson(writeJson(evidenceUrls));
        boolean verificationRequired = Boolean.TRUE.equals(configuredTask.get("verificationRequired"));
        String completionPolicy = upper(taskConfig.path("completionPolicy").asText());
        if ("PHOTO".equals(inputType)) {
            if (verificationRequired || !"EVIDENCE_ONLY".equals(completionPolicy)) {
                throw error("GAME_CONFIG_INVALID", "照片任务仅支持明确的证据留档完成策略");
            }
            requirePhotoEvidenceUploaded((List<?>) evidenceUrls, actor.getMemberId());
            submission.setStatus("RECORDED");
        } else {
            if (!verificationRequired) verifyAutomaticEvidence(taskConfig, inputType, (List<?>) evidenceUrls);
            submission.setStatus(verificationRequired ? "PENDING" : "APPROVED");
        }
        submission.setRevision(0);
        submission.setCreateTime(clock.now());
        submission.setUpdateTime(clock.now());
        if (runtimeMapper.insertSubmission(submission) != 1) {
            active = runtimeMapper.selectActiveSubmissionForUpdate(session.getId(), actor.getMemberId(),
                    station.getNodeId(), taskCode);
            if (active != null) {
                GameSession current = runtimeMapper.selectSessionByActivity(activity.getId());
                GamePlayerNodeOutcome outcome = recoverSubmissionOutcome(session, station, player.teamId,
                        actor.getMemberId(), active, taskConfig, receiptId);
                return result(current == null ? session : current, submissionResult(active, outcome, true));
            }
            if (runtimeMapper.insertSubmission(submission) != 1) {
                throw error("GAME_STATE_CONFLICT", "任务提交已变化，请刷新后重试");
            }
        }
        GamePlayerNodeOutcome outcome = outcomeForSubmission(session, station, player.teamId,
                actor.getMemberId(), submission, taskConfig, receiptId);
        bumpSession(session, session.getStatus(), session.getCurrentChapterId(), actor.getMemberId(), clock.now());
        return result(session, submissionResult(submission, outcome, false));
    }

    private ActionResult verifySubmission(GameActorContext actor, GameSession session,
                                          GameSessionCommand command, Long receiptId) {
        requireSessionStatus(session, GameSession.RUNNING);
        Long submissionId = positiveLong(command.getPayload().get("submissionId"));
        GameTaskSubmission submission = submissionId == null ? null : runtimeMapper.selectSubmission(submissionId);
        if (submission == null || !session.getId().equals(submission.getGameSessionId())) {
            throw error("GAME_SUBMISSION_NOT_PENDING", "待核验任务不存在");
        }
        if (!"PENDING".equals(submission.getStatus())) {
            throw error("GAME_SUBMISSION_NOT_PENDING", "该任务不在商家待核验队列");
        }
        GameStationRun station = requireMerchantStation(actor, session, submission.getNodeId());
        if (GameStationRun.PAUSED.equals(station.getStatus())) throw error("GAME_STATION_PAUSED", "站点暂停中，不能核验");
        if (!GameStationRun.ACTIVE.equals(station.getStatus()) || !station.getId().equals(submission.getStationRunId())) {
            throw error("GAME_FORBIDDEN_SCOPE", "任务不属于当前商家站点");
        }
        String decision = upper(command.getPayload().get("decision"));
        if (!"APPROVE".equals(decision) && !"REJECT".equals(decision)) {
            throw error("GAME_BAD_REQUEST", "请选择通过或驳回");
        }
        submission.setStatus("APPROVE".equals(decision) ? "APPROVED" : "REJECTED");
        submission.setDecidedBy(actor.getMemberId());
        submission.setDecisionReasonCode(upper(command.getPayload().get("reasonCode")));
        submission.setDecisionReason(text(command.getPayload().get("reason"), 200));
        if ("REJECT".equals(decision) && StringUtils.isBlank(submission.getDecisionReasonCode())) {
            throw error("GAME_BAD_REQUEST", "驳回任务必须选择原因");
        }
        submission.setUpdateTime(clock.now());
        if (runtimeMapper.decideSubmissionCas(submission) != 1) {
            throw error("GAME_SUBMISSION_NOT_PENDING", "该任务已被处理");
        }
        GamePlayerNodeOutcome outcome = null;
        if ("APPROVED".equals(submission.getStatus())) {
            outcome = ensureOutcome(session, station, submission.getTeamId(), submission.getMemberId(),
                    GamePlayerNodeOutcome.COMPLETED, "MERCHANT_APPROVAL", submission.getId(), receiptId,
                    null, null);
            ensureFallbackSourceOutcomes(session, station, submission.getTeamId(), submission.getMemberId(), receiptId);
        }
        bumpSession(session, session.getStatus(), session.getCurrentChapterId(), actor.getMemberId(), clock.now());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("submissionId", submissionId);
        data.put("decision", submission.getStatus());
        addOutcomeResult(data, outcome);
        return result(session, data);
    }

    private ActionResult unlockChapter(GameActorContext actor, GameSession session, GameSessionCommand command) {
        requireSessionStatus(session, GameSession.RUNNING);
        Long chapterId = positiveLong(command.getPayload().get("chapterId"));
        String reason = text(command.getPayload().get("reason"), 200);
        if (chapterId == null || StringUtils.isBlank(reason)) throw error("GAME_BAD_REQUEST", "请选择章节并填写解锁原因");
        if (!configuredChapterIds(sessionConfig(session)).contains(chapterId)) {
            throw error("GAME_FORBIDDEN_SCOPE", "章节不属于本场游戏配置");
        }
        JsonNode config = sessionConfig(session);
        if (!isLaterChapter(config, session.getCurrentChapterId(), chapterId)) {
            throw error("GAME_STATE_CONFLICT", "只能解锁尚未开放的后续章节");
        }
        bumpSession(session, session.getStatus(), chapterId, actor.getMemberId(), clock.now());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("chapterId", chapterId);
        data.put("reason", reason);
        return result(session, data);
    }

    private ActionResult broadcast(GameActorContext actor, GameSession session, GameSessionCommand command) {
        requireSessionStatus(session, GameSession.READY, GameSession.RUNNING);
        String targetType = upper(command.getPayload().get("targetType"));
        String content = text(command.getPayload().get("content"), 200);
        if (!("ALL".equals(targetType) || "TEAM".equals(targetType) || "ROLE".equals(targetType))
                || StringUtils.isBlank(content)) throw error("GAME_BAD_REQUEST", "广播对象或内容不正确");
        Long targetId = positiveLong(command.getPayload().get("targetId"));
        String roleCode = upper(command.getPayload().get("roleCode"));
        if ("TEAM".equals(targetType)
                && (targetId == null || runtimeMapper.isActiveTeam(session.getActivityId(), targetId) != 1)) {
            throw error("GAME_FORBIDDEN_SCOPE", "广播队伍不属于本局");
        }
        if ("ROLE".equals(targetType)
                && (StringUtils.isBlank(roleCode) || runtimeMapper.countRole(session.getId(), roleCode) < 1)) {
            throw error("GAME_FORBIDDEN_SCOPE", "广播角色不属于本局");
        }
        checkCommandText(actor.getMemberId(), content, "广播内容未通过安全审核");
        GameBroadcast broadcast = new GameBroadcast();
        broadcast.setGameSessionId(session.getId());
        broadcast.setActivityId(session.getActivityId());
        broadcast.setSenderMemberId(actor.getMemberId());
        broadcast.setTargetType(targetType);
        broadcast.setTargetId(targetId);
        broadcast.setRoleCode(roleCode);
        broadcast.setContent(content);
        broadcast.setStatus("SENDING");
        broadcast.setCreateTime(clock.now());
        broadcast.setUpdateTime(clock.now());
        if (runtimeMapper.insertBroadcast(broadcast) != 1) throw error("GAME_STATE_CONFLICT", "广播创建失败");
        if (runtimeMapper.insertBroadcastDeliveries(broadcast.getId(), session.getId(), session.getActivityId(),
                targetType, broadcast.getTargetId(), broadcast.getRoleCode(), clock.now()) < 1) {
            throw error("GAME_BROADCAST_NO_RECIPIENT", "广播范围内没有可送达玩家");
        }
        int reached = dispatchBroadcast(broadcast);
        int failed = settleBroadcast(broadcast, reached);
        bumpSession(session, session.getStatus(), session.getCurrentChapterId(), actor.getMemberId(), clock.now());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("broadcastId", broadcast.getId());
        data.put("reachedCount", reached);
        data.put("failedCount", failed);
        return result(session, data);
    }

    /** 按投递行写广播终态(CAS 自当前状态),返回仍失败的投递数。 */
    private int settleBroadcast(GameBroadcast broadcast, int reached) {
        int failed = runtimeMapper.countBroadcastDeliveries(broadcast.getId(), "FAILED");
        int pending = runtimeMapper.countBroadcastDeliveries(broadcast.getId(), "PENDING");
        String finalStatus = pending > 0 || (reached > 0 && failed > 0) ? "PARTIAL"
                : failed > 0 ? "FAILED" : "SENT";
        if (runtimeMapper.updateBroadcastStatus(broadcast.getId(), broadcast.getStatus(), finalStatus, clock.now()) != 1) {
            throw error("GAME_STATE_CONFLICT", "广播终态记录失败");
        }
        broadcast.setStatus(finalStatus);
        return failed;
    }

    /**
     * SC-CITY-BROADCAST-TARGET:同一 requestId 重试导演台广播 = 只补发这条广播里 FAILED 的投递。
     * 已 REACHED 的成员不会再收到;回执改写为补发后的送达状态。没有失败投递、场次已不在
     * READY/RUNNING、或不是已生效的广播时,照旧只回放原回执。在 game_session 行锁内执行,
     * 投递行从 FAILED 置回 PENDING 再由 markDelivery 按 PENDING CAS 落定,同一行不会被并发补发两次。
     */
    private GameCommandReceiptView replayOrResendFailedBroadcast(GameActorContext actor, GameSessionCommand command,
                                                                 GameCommandReceipt existing, String requestHash,
                                                                 GameSession session) {
        GameCommandReceiptView replayed = replay(existing, requestHash);
        if (!"BROADCAST".equals(existing.getAction()) || !"APPLIED".equals(existing.getOutcome()) || session == null
                || !(GameSession.READY.equals(session.getStatus()) || GameSession.RUNNING.equals(session.getStatus()))) {
            return replayed;
        }
        Map<String, Object> data = readMap(existing.getResultJson());
        Long broadcastId = positiveLong(data.get("broadcastId"));
        GameBroadcast broadcast = broadcastId == null ? null : runtimeMapper.selectBroadcast(broadcastId);
        if (broadcast == null || !session.getId().equals(broadcast.getGameSessionId())
                || runtimeMapper.countBroadcastDeliveries(broadcastId, "FAILED") < 1) {
            return replayed;
        }
        preauthorize(actor, requireActivity(command.getActivityId(), true), session, command);
        runtimeMapper.resetFailedDeliveries(broadcastId, clock.now());
        dispatchBroadcast(broadcast);
        int reached = runtimeMapper.countBroadcastDeliveries(broadcastId, "REACHED");
        int failed = settleBroadcast(broadcast, reached);
        data.put("reachedCount", reached);
        data.put("failedCount", failed);
        data.put("broadcastStatus", broadcast.getStatus());
        existing.setResultJson(writeJson(data));
        existing.setUpdateTime(clock.now());
        if (runtimeMapper.updateAppliedReceiptResult(existing) != 1) {
            throw error("GAME_STATE_CONFLICT", "广播补发回执落库失败");
        }
        return toReceiptView(existing, false);
    }

    private int dispatchBroadcast(GameBroadcast broadcast) {
        int reached = 0;
        for (GameBroadcastDelivery delivery : safe(runtimeMapper.selectPendingDeliveries(broadcast.getId()))) {
            Long messageId;
            try {
                String extra = "{\"gameSessionId\":" + broadcast.getGameSessionId()
                        + ",\"broadcastId\":" + broadcast.getId() + "}";
                messageId = imService.sendSystemInNestedTransaction(delivery.getMemberId(), ImMessage.MSG_TEXT,
                        broadcast.getContent(), extra);
            } catch (RuntimeException ex) {
                com.chengyinhub.business.service.support.SavepointLockFailures.rethrowIfLockFailure(ex);
                markBroadcastDeliveryOrThrow(delivery.getId(), "FAILED", null, "IM_DELIVERY_FAILED");
                continue;
            }
            markBroadcastDeliveryOrThrow(delivery.getId(), "REACHED", messageId, null);
            reached++;
        }
        return reached;
    }

    private void markBroadcastDeliveryOrThrow(Long deliveryId, String status, Long messageId, String errorCode) {
        try {
            if (runtimeMapper.markDelivery(deliveryId, status, messageId, errorCode, clock.now()) != 1) {
                throw error("GAME_STATE_CONFLICT", "广播送达状态记录失败");
            }
        } catch (GameSessionException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw error("GAME_STATE_CONFLICT", "广播送达状态记录失败");
        }
    }

    private ActionResult setLeaderboardVisibility(GameActorContext actor, GameSession session,
                                                  GameSessionCommand command) {
        requireSessionStatus(session, GameSession.PREPARING, GameSession.READY, GameSession.RUNNING);
        Object visibleValue = command.getPayload().get("visible");
        if (!(visibleValue instanceof Boolean)) throw error("GAME_BAD_REQUEST", "请明确排行榜是否可见");
        boolean visible = (Boolean) visibleValue;
        Date now = clock.now();
        if (runtimeMapper.updateLeaderboardVisibilityCas(session.getId(), session.getRevision(), session.getStatus(),
                visible, actor.getMemberId(), now) != 1) throw error("GAME_STATE_CONFLICT", "排行榜状态已变化");
        session.setLeaderboardVisible(visible);
        session.setLeaderboardUpdatedBy(actor.getMemberId());
        session.setLeaderboardUpdatedAt(now);
        session.setRevision(session.getRevision() + 1);
        return result(session, singleton("visible", visible));
    }

    private ActionResult finish(GameActorContext actor, GameSession session) {
        requireSessionStatus(session, GameSession.RUNNING);
        Map<String, Object> recap = isRouteSession(session)
                ? buildClubRecap(session, requireActivity(session.getActivityId(), false)) : null;
        if (isRouteSession(session) && recap == null) {
            throw error("GAME_RECAP_UNAVAILABLE", "复盘数据尚未生成，请刷新后重试");
        }
        Date now = clock.now();
        runtimeMapper.closeStations(session.getId(), now);
        bumpSession(session, GameSession.FINISHED, session.getCurrentChapterId(), actor.getMemberId(), now);
        Map<String, Object> result = singleton("finishedAt", formatDate(now));
        if (recap != null) result.put("recap", recap);
        return result(session, result);
    }

    private GameSessionView playerView(GameActorContext actor, CmsActivity activity, GameSession session) {
        PlayerScope player = requirePlayer(actor, activity);
        if (isRouteSession(session)) {
            GameSessionView routeView = baseView(session, activity.getId(), "PLAYER", session.getStatus(),
                    session.getRevision(), session.getCurrentChapterId());
            Map<String, Object> routePlayer = isSharedRoute(session) ? sharedRoutePlayer(session, player.teamId,actor.getMemberId()) : new LinkedHashMap<>();
            if (isSharedRoute(session)) {
                ObjectNode manual = playerManualView(runControl(session), actor.getMemberId());
                if (manual != null) ((Map<String, Object>) routePlayer.get("run")).put("manual", objectMapper.convertValue(manual, Map.class));
            }
            routePlayer.put("playMode", "ROUTE");
            routePlayer.put("teamId", player.teamId);
            routeView.setPlayer(routePlayer);
            com.chengyinhub.business.domain.PlayTeam team = isSharedRoute(session) ? playTeamMapper.selectPlayTeamById(player.teamId) : null;
            if(isSharedRoute(session)) {
                boolean captain=team!=null && actor.getMemberId().equals(team.getLeaderMemberId());routePlayer.put("isCaptain",captain);
                for(String field:Arrays.asList("quests","recoveries","interactionTools")) {
                    JsonNode rows=objectMapper.valueToTree(routePlayer.get(field));
                    for(JsonNode row:rows)if(!captain && !Arrays.asList("SESSION_ENDED","TEAM_COMPLETED").contains(row.path("disabledReason").asText())) {
                        GameplayToolState.availability((ObjectNode)row,Reason.NOT_CAPTAIN,row.path("trustedPass").asBoolean());
                        if(row.path("actionSummary").isObject())GameplayToolState.availability((ObjectNode)row.path("actionSummary"),Reason.NOT_CAPTAIN,row.path("trustedPass").asBoolean());
                        if(row.path("facility").isObject())GameplayToolState.availability((ObjectNode)row.path("facility"),Reason.NOT_CAPTAIN,row.path("trustedPass").asBoolean());
                        if(row.path("actionSummary").path("facility").isObject())GameplayToolState.availability((ObjectNode)row.path("actionSummary").path("facility"),Reason.NOT_CAPTAIN,row.path("trustedPass").asBoolean());
                    }
                    routePlayer.put(field,"quests".equals(field)?objectMapper.convertValue(rows,List.class):rows);
                }
                ObjectNode worldView=routePlayer.get("world") instanceof ObjectNode?(ObjectNode)routePlayer.get("world"):null;
                if(worldView!=null)for(String field:Arrays.asList("trades","tradeTargets"))for(JsonNode row:worldView.path(field))if(!captain && !Arrays.asList("SESSION_ENDED","TEAM_COMPLETED").contains(row.path("disabledReason").asText())) {
                    GameplayToolState.availability((ObjectNode)row,Reason.NOT_CAPTAIN,row.path("trustedPass").asBoolean());((ObjectNode)row).put("disabled",true);
                }
                if (worldView != null && !captain) for (JsonNode plan : worldView.path("plans")) {
                    ObjectNode actions = (ObjectNode) plan.path("actions");
                    for (String field : Arrays.asList("canFund", "canVote", "canAccept", "canExecute")) actions.put(field, false);
                    if (!Arrays.asList("SESSION_ENDED", "TEAM_COMPLETED").contains(actions.path("disabledReason").asText())) actions.put("disabledReason", "NOT_CAPTAIN");
                }
            }
            List<String> actions = new ArrayList<>();
            if (team != null && actor.getMemberId().equals(team.getLeaderMemberId()) && GameSession.RUNNING.equals(session.getStatus()) && routePlayer.get("result") == null && !Boolean.TRUE.equals(routePlayer.get("naturallyCompleted"))) {
                actions.add("ITEM_RECEIVE");
                JsonNode progress = (JsonNode) routePlayer.get("gameplay");
                if (progress != null && progress.has("activeQuest")) actions.addAll(Arrays.asList("QUEST_CANCEL", "QUEST_RESOLVE"));
                else {
                    actions.addAll(Arrays.asList("ITEM_USE", "ITEM_DISCARD"));
                    if (!((List<?>) routePlayer.get("quests")).isEmpty()) actions.add("QUEST_START");
                }
            }
            if (isSharedRoute(session) && team != null && actor.getMemberId().equals(team.getLeaderMemberId())) {
                ObjectNode run = runControl(session);
                ObjectNode worldView = routePlayer.get("world") instanceof ObjectNode ? (ObjectNode) routePlayer.get("world") : null;
                if (worldView != null && (worldView.has("result") || Arrays.asList("VOTING", "ACCEPTING", "CONSENSUS", "SELECTED", "TIED").contains(worldView.path("stage").asText()))) actions.clear();
                if (!"RUNNING".equals(run.path("operationStatus").asText()) || !"ONLINE".equals(run.path("authorityMode").asText()) || run.path("exitedTeams").has(String.valueOf(player.teamId))) actions.clear();
                else if (GameSession.RUNNING.equals(session.getStatus()) && routePlayer.get("result") == null && !Boolean.TRUE.equals(routePlayer.get("naturallyCompleted"))
                        && !(routePlayer.get("world") instanceof ObjectNode && ((ObjectNode)routePlayer.get("world")).has("result"))) {
                    actions.add("ARRIVAL_ISSUE"); actions.add("TEAM_EXIT"); actions.addAll(cooperationPlayerActions(session, player.teamId));
                    if (routeGameplay(session).path("survival").path("recoveries").size() > 0) actions.add("RECOVER");
                }
                if ("MANUAL".equals(run.path("authorityMode").asText()) && "PAUSED".equals(run.path("operationStatus").asText()) && !run.path("exitedTeams").has(String.valueOf(player.teamId)) && !Boolean.TRUE.equals(routePlayer.get("naturallyCompleted"))) actions.add("MANUAL_SIGN");
            }
            if(isSharedRoute(session) && team!=null && actor.getMemberId().equals(team.getLeaderMemberId()) && GameSession.RUNNING.equals(session.getStatus())
                    && "RUNNING".equals(runControl(session).path("operationStatus").asText()) && "ONLINE".equals(runControl(session).path("authorityMode").asText())
                    && !runControl(session).path("exitedTeams").has(String.valueOf(player.teamId))) {
                TopicRouteEngine.State owner=routeOwnerState(requireRouteOwner(session,player.teamId));
                if(("COMPLETED".equals(owner.getStatus()) || owner.getThemeRuntime().has("result")) && owner.getThemeRuntime().path("pending").size()>0 && !actions.contains("ITEM_RECEIVE"))actions.add("ITEM_RECEIVE");
            }
            JsonNode zero=objectMapper.valueToTree(routePlayer.get("zeroHp"));for(JsonNode action:zero.path("allowedActions"))actions.add(action.asText());
            routeView.setAvailableActions(actions);
            return routeView;
        }
        GameTeamRoleAssignment assignment = runtimeMapper.selectRole(session.getId(), actor.getMemberId());
        JsonNode config = sessionConfig(session);
        Map<String, Object> playerMap = new LinkedHashMap<>();
        playerMap.put("teamId", player.teamId);
        playerMap.put("role", publicRole(config, assignment));
        Map<Long, GamePlayerNodeOutcome> outcomes = new LinkedHashMap<>();
        for (GamePlayerNodeOutcome outcome : safe(runtimeMapper.selectPlayerNodeOutcomes(
                session.getId(), actor.getMemberId()))) {
            if (outcome != null && session.getId().equals(outcome.getGameSessionId())
                    && actor.getMemberId().equals(outcome.getMemberId()) && outcome.getNodeId() != null) {
                outcomes.put(outcome.getNodeId(), outcome);
            }
        }
        Map<Long, Integer> hintLevels = playerHintLevels(session.getId(), actor.getMemberId());
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<GameStationRun> stations = safe(runtimeMapper.selectStations(session.getId()));
        for (GameStationRun station : stations) {
            nodes.add(playerStationView(actor, activity, session, station, assignment, config,
                    outcomes.get(station.getNodeId()), hintLevels.getOrDefault(station.getNodeId(), 0), stations));
        }
        playerMap.put("nodes", nodes);
        playerMap.put("story", publicStory(session, player.teamId, config));
        // SQL 只按同局 team_id 投影公开昵称/角色/协作状态，不选择手机号、经纬度或证据素材。
        playerMap.put("teamActions", safe(runtimeMapper.selectTeamActionProjection(session.getId(), player.teamId)));
        playerMap.put("mySubmissions", safe(runtimeMapper.selectMySubmissionProjection(session.getId(), actor.getMemberId())));
        if (Boolean.TRUE.equals(session.getLeaderboardVisible())) {
            Map<String, Object> leaderboard = new LinkedHashMap<>();
            leaderboard.put("visible", true);
            leaderboard.put("entries", safe(runtimeMapper.selectSessionLeaderboard(session.getId())));
            playerMap.put("leaderboard", leaderboard);
        }
        GameSessionView view = baseView(session, activity.getId(), "PLAYER", session.getStatus(),
                session.getRevision(), session.getCurrentChapterId());
        view.setAvailableActions(playerActions(session, assignment, config));
        view.setPlayer(playerMap);
        return view;
    }

    private Map<String, Object> playerStationView(GameActorContext actor, CmsActivity activity, GameSession session,
                                                  GameStationRun station, GameTeamRoleAssignment assignment,
                                                  JsonNode config, GamePlayerNodeOutcome outcome,
                                                  int currentHintLevel, List<GameStationRun> stations) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("stationId", station.getId());
        node.put("nodeId", station.getNodeId());
        node.put("nodeName", station.getNodeName());
        node.put("stationCode", station.getStationCode());
        node.put("status", station.getStatus());
        boolean arrived = hasArrived(actor, activity, station);
        Map<String, Object> task = publicTask(config, station);
        String taskCode = task == null ? null : String.valueOf(task.get("taskCode"));
        GameTaskSubmission active = StringUtils.isBlank(taskCode) ? null
                : runtimeMapper.selectActiveSubmission(session.getId(), actor.getMemberId(), station.getNodeId(), taskCode);
        Date now = clock.now();
        boolean playable = arrived && GameSession.RUNNING.equals(session.getStatus())
                && isConfirmedRole(assignment)
                && GameStationRun.ACTIVE.equals(station.getStatus())
                && isWithinActivityServiceWindow(activity, now) && isWithinStationServiceWindow(station, now);
        String personalState = playerStationState(station, active, outcome, playable);
        node.put("personalState", personalState);
        node.put("playable", playable);
        node.put("completionStatus", outcome == null ? null : outcome.getStatus());
        node.put("completionSource", outcome == null ? null : outcome.getCompletionSource());
        node.put("completionOrder", outcome == null ? null : outcome.getId());
        node.put("completedAt", outcome == null ? null : formatDate(outcome.getCompletedAt()));
        if (outcome == null && GameStationRun.PAUSED.equals(station.getStatus())) {
            // R9-38:没有兜底路线时玩家只剩一个干巴巴的"暂停"。后续安排(原因/预计恢复)
            // 是暂停的对外契约,和兜底路线无关,单独下发,由前端决定怎么展示。
            Map<String, Object> pause = new LinkedHashMap<>();
            pause.put("reasonCode", station.getPauseReasonCode());
            pause.put("reason", station.getPauseReason());
            pause.put("resumeEta", formatDate(station.getResumeEta()));
            node.put("pause", pause);
            Map<String, Object> fallback = playerFallbackView(session, station, config, stations);
            if (fallback != null) node.put("fallback", fallback);
        }
        if (playable) {
            node.put("clue", assignment == null ? null
                    : roleClue(config, assignment.getRoleCode(), station.getStationCode()));
            node.put("playerTask", task);
            JsonNode taskConfig = configuredTask(config, station);
            if (taskConfig != null && taskConfig.path("hintPolicy").isObject()) {
                node.put("hint", hintView(taskConfig.path("hintPolicy"), currentHintLevel, outcome));
            }
            node.put("allowedChoices", outcome != null
                    ? allowedChoices(config, assignment, station.getStationCode(), session.getCurrentChapterId())
                    : Collections.emptyList());
        }
        return node;
    }

    private Map<String, Object> playerFallbackView(GameSession session, GameStationRun source,
                                                   JsonNode config, List<GameStationRun> stations) {
        if (source.getFallbackNodeId() == null || StringUtils.isBlank(source.getFallbackPlanCode())
                || source.getFallbackPlanVersion() == null || source.getFallbackPlanVersion() <= 0) return null;
        GameStationRun target = null;
        for (GameStationRun candidate : safe(stations)) {
            if (candidate != null && session.getId().equals(candidate.getGameSessionId())
                    && source.getFallbackNodeId().equals(candidate.getNodeId())) {
                target = candidate;
                break;
            }
        }
        if (target == null || !GameStationRun.ACTIVE.equals(target.getStatus())
                || StringUtils.isBlank(target.getNodeName()) || !isApprovedFallbackBinding(config, source, target)) {
            return null;
        }
        for (JsonNode plan : config.path("fallbackPlans")) {
            if ("APPROVED".equals(upper(plan.path("status").asText()))
                    && source.getFallbackPlanCode().equals(upper(plan.path("planCode").asText()))
                    && source.getFallbackPlanVersion() == plan.path("version").asInt(0)
                    && source.getStationCode().equals(plan.path("sourceStationCode").asText())
                    && target.getStationCode().equals(plan.path("targetStationCode").asText())) {
                String playerMessage = text(plan.path("playerMessage").asText(), 200);
                if (StringUtils.isBlank(playerMessage)) return null;
                Map<String, Object> fallback = new LinkedHashMap<>();
                fallback.put("planCode", source.getFallbackPlanCode());
                fallback.put("planVersion", source.getFallbackPlanVersion());
                fallback.put("targetNodeId", target.getNodeId());
                fallback.put("targetNodeName", target.getNodeName());
                fallback.put("playerMessage", playerMessage);
                return fallback;
            }
        }
        return null;
    }

    private String playerStationState(GameStationRun station, GameTaskSubmission active,
                                      GamePlayerNodeOutcome outcome, boolean playable) {
        if (outcome != null) return outcome.getStatus();
        if (GameStationRun.PAUSED.equals(station.getStatus())) return "PAUSED";
        if (active != null && "PENDING".equals(active.getStatus())) return "AWAITING_VERIFICATION";
        if (playable) return "IN_PROGRESS";
        return "AVAILABLE";
    }

    private GameSessionView merchantView(GameActorContext actor, CmsActivity activity, GameSession session) {
        Long merchantMemberId = merchantOwnerMemberId(actor);
        List<GameStationRun> stations = safe(runtimeMapper.selectMerchantStations(session.getId(), merchantMemberId));
        if (stations.isEmpty()) throw error("GAME_FORBIDDEN_SCOPE", "当前商家不负责本场站点");
        JsonNode config = sessionConfig(session);
        Map<Long, Map<String, Object>> recaps = merchantStationRecaps(session.getId(), merchantMemberId);
        List<Map<String, Object>> stationViews = new ArrayList<>();
        for (GameStationRun station : stations) {
            Map<String, Object> stationView = stationView(station, config);
            Map<String, Object> recap = recaps.get(station.getId());
            if (recap != null) stationView.put("recap", recap);
            stationViews.add(stationView);
        }
        Map<String, Object> merchant = new LinkedHashMap<>();
        merchant.put("playMode", isRouteSession(session) ? "ROUTE" : "STORY");
        merchant.put("stations", stationViews);
        merchant.put("fallbackOptions", fallbackOptions(session, actor));
        GameSessionView view = baseView(session, activity.getId(), "MERCHANT", session.getStatus(),
                session.getRevision(), session.getCurrentChapterId());
        view.setAvailableActions(merchantActions(session, stations));
        view.setMerchant(merchant);
        return view;
    }

    private Map<Long, Map<String, Object>> merchantStationRecaps(Long sessionId, Long merchantMemberId) {
        Map<Long, Map<String, Object>> indexed = new LinkedHashMap<>();
        List<Map<String, Object>> rows = runtimeMapper.selectMerchantStationRecaps(sessionId, merchantMemberId);
        if (rows == null) return indexed;
        for (Map<String, Object> row : rows) {
            if (row == null) continue;
            Long stationRunId = positiveLong(row.get("stationRunId"));
            Map<String, Object> recap = merchantStationRecap(row);
            if (stationRunId != null && recap != null && !indexed.containsKey(stationRunId)) {
                indexed.put(stationRunId, recap);
            }
        }
        return indexed;
    }

    private Map<String, Object> merchantStationRecap(Map<String, Object> row) {
        String[] fields = {"arrivedPlayers", "submissionCount", "approvedCount", "rejectedCount",
                "recordedCount", "normalCompletedCount", "fallbackCompletedCount", "pauseEventCount",
                "authorizedContentCount"};
        Map<String, Object> recap = integerFields(row, fields);
        if (recap == null || !Integer.valueOf(0).equals(recap.get("authorizedContentCount"))
                || !"NO_PUBLIC_CONTENT".equals(row.get("contentPolicy"))) {
            return null;
        }
        recap.put("contentPolicy", "NO_PUBLIC_CONTENT");
        return recap;
    }

    private List<Map<String, Object>> fallbackOptions(GameSession session, GameActorContext actor) {
        List<Map<String, Object>> options = new ArrayList<>();
        Map<String, GameStationRun> stationsByCode = new LinkedHashMap<>();
        for (GameStationRun station : safe(runtimeMapper.selectStations(session.getId()))) {
            if (station != null && session.getId().equals(station.getGameSessionId())
                    && StringUtils.isNotBlank(station.getStationCode())) {
                stationsByCode.put(station.getStationCode(), station);
            }
        }
        for (JsonNode configured : sessionConfig(session).path("fallbackPlans")) {
            if (!"APPROVED".equals(upper(configured.path("status").asText()))) continue;
            GameStationRun source = stationsByCode.get(configured.path("sourceStationCode").asText());
            GameStationRun candidate = stationsByCode.get(configured.path("targetStationCode").asText());
            if (source == null || !merchantOwnerMemberId(actor).equals(source.getMerchantMemberId())
                    || !GameStationRun.ACTIVE.equals(source.getStatus())
                    || !isFallbackCandidate(session, merchantOwnerMemberId(actor), source.getNodeId(), candidate)
                    || StringUtils.isBlank(candidate.getNodeName())) continue;
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("sourceNodeId", source.getNodeId());
            option.put("planCode", upper(configured.path("planCode").asText()));
            option.put("planVersion", configured.path("version").asInt());
            option.put("nodeId", candidate.getNodeId());
            option.put("nodeName", candidate.getNodeName());
            option.put("playerMessage", configured.path("playerMessage").asText());
            options.add(option);
        }
        return options;
    }

    private boolean isFallbackCandidate(GameSession session, Long sourceMerchantId, Long sourceNodeId,
                                        GameStationRun candidate) {
        return candidate != null && session.getId().equals(candidate.getGameSessionId())
                && candidate.getNodeId() != null && !candidate.getNodeId().equals(sourceNodeId)
                && candidate.getMerchantMemberId() != null && candidate.getMerchantMemberId() > 0
                && !candidate.getMerchantMemberId().equals(sourceMerchantId)
                && GameStationRun.ACTIVE.equals(candidate.getStatus());
    }

    private FallbackPlan resolveFallbackPlan(GameSession session, GameStationRun source, Long sourceMerchantId,
                                             String planCode, Integer planVersion) {
        if (StringUtils.isBlank(planCode) || planVersion == null || planVersion <= 0) {
            throw error("GAME_BAD_REQUEST", "请选择已批准的备用方案版本");
        }
        JsonNode matched = null;
        for (JsonNode plan : sessionConfig(session).path("fallbackPlans")) {
            if (planCode.equals(upper(plan.path("planCode").asText()))
                    && planVersion == plan.path("version").asInt(0)
                    && "APPROVED".equals(upper(plan.path("status").asText()))
                    && source.getStationCode().equals(plan.path("sourceStationCode").asText())) {
                matched = plan;
                break;
            }
        }
        if (matched == null) throw error("GAME_FORBIDDEN_SCOPE", "备用方案未获批准或版本已失效");
        String targetStationCode = matched.path("targetStationCode").asText();
        GameStationRun target = null;
        for (GameStationRun candidate : safe(runtimeMapper.selectStations(session.getId()))) {
            if (targetStationCode.equals(candidate.getStationCode())) {
                target = candidate;
                break;
            }
        }
        if (!isFallbackCandidate(session, sourceMerchantId, source.getNodeId(), target)) {
            throw error("GAME_FORBIDDEN_SCOPE", "备用方案目标不是其他正常服务商家站点");
        }
        return new FallbackPlan(planCode, planVersion, matched.path("playerMessage").asText(), target);
    }

    private GameSessionView clubView(GameActorContext actor, CmsActivity activity, GameSession session) {
        requireClubOwner(actor, activity);
        JsonNode config = sessionConfig(session);
        List<Map<String, Object>> stations = new ArrayList<>();
        for (GameStationRun station : safe(runtimeMapper.selectStations(session.getId()))) {
            stations.add(stationView(station, config));
        }
        Map<String, Object> readiness = new LinkedHashMap<>();
        readiness.put("requiredStations", stations.size());
        readiness.put("readyStations", countStationStatus(stations, GameStationRun.READY, GameStationRun.ACTIVE));
        boolean hasAudience = runtimeMapper.countActiveTeams(activity.getId()) > 0;
        boolean teamsReady = hasAudience
                && (isRouteSession(session) || runtimeMapper.countTeamMembersWithoutRole(session.getId(), activity.getId()) == 0);
        readiness.put("teamsReady", teamsReady);
        readiness.put("blockers", readinessBlockers(session, stations, teamsReady));
        Map<String, Object> club = new LinkedHashMap<>();
        club.put("readiness", readiness);
        club.put("stations", stations);
        List<Map<String, Object>> memberRows = safe(runtimeMapper.selectClubMemberProjection(session.getId()));
        club.put("roles", memberRows);
        club.put("playMode", isRouteSession(session) ? "ROUTE" : "STORY");
        club.put("teams", clubTeams(memberRows, safe(isRouteSession(session)
                ? runtimeMapper.selectClubRouteTeamProgressProjection(session.getId())
                : runtimeMapper.selectClubTeamProgressProjection(session.getId()))));
        club.put("roleOptions", roleOptions(config));
        club.put("chapterOptions", chapterOptions(config, session.getCurrentChapterId()));
        club.put("broadcasts", broadcastViews(safe(runtimeMapper.selectBroadcasts(session.getId()))));
        // CU-C-76:入口「能不能发」以前只由场次状态决定,零成员的场子照样让人点进去吃一句笼统提示。
        club.put("broadcastBlocker", broadcastBlocker(session, hasAudience));
        club.put("leaderboardVisible", Boolean.TRUE.equals(session.getLeaderboardVisible()));
        if (isRouteSession(session)) {
            club.put("leaderboardRows", safe(playerNodeProgressService.selectActivityLeaderboard(
                    activity.getId(), null, 50)));
        }
        club.put("leaderboardUpdatedBy", session.getLeaderboardUpdatedBy());
        club.put("leaderboardUpdatedAt", formatDate(session.getLeaderboardUpdatedAt()));
        // Figma D8「现场事件」:待核验与已驳回的提交。查询里没有 evidence_json ——
        // 俱乐部看不到玩家提交的照片与答案,它能做的只是打回重交(见 clubRejectSubmission)。
        club.put("submissions", safe(runtimeMapper.selectClubSubmissionProjection(session.getId())));
        club.put("recap", buildClubRecap(session, activity));
        GameSessionView view = baseView(session, activity.getId(), "CLUB", session.getStatus(),
                session.getRevision(), session.getCurrentChapterId());
        view.setAvailableActions(clubActions(session));
        view.setClub(club);
        return view;
    }

    private Map<String, Object> buildClubRecap(GameSession session, CmsActivity activity) {
        if (isRouteSession(session) && GameSession.FINISHED.equals(session.getStatus())) {
            String saved = runtimeMapper.selectFinishedRecapJson(session.getId());
            if (StringUtils.isNotBlank(saved)) return readMap(saved);
        }
        Map<String, Object> aggregate = runtimeMapper.selectClubRecapAggregate(session.getId(), activity.getId());
        List<Map<String, Object>> stationRows = runtimeMapper.selectClubStationRecaps(session.getId());
        List<Map<String, Object>> collaborationRows = runtimeMapper.selectClubRoleCollaboration(
                session.getId(), activity.getId());
        if (aggregate == null || stationRows == null || collaborationRows == null) return null;

        Map<String, Object> funnel = integerFields(aggregate, "paidPlayers", "arrivedPlayers",
                "taskSubmitters", "normalCompleters", "fallbackCompleters", "finishedTeams");
        Map<String, Object> hints = integerFields(aggregate, "level1Uses", "level2Uses", "answerReveals");
        Map<String, Object> incidents = integerFields(aggregate, "merchantPauseEvents",
                "merchantFallbackCompletions", "playerRejectedSubmissions");
        Integer takeoverCount = nonNegativeInteger(aggregate.get("takeoverCount"));
        if (funnel == null || hints == null || incidents == null || takeoverCount == null) return null;

        List<Map<String, Object>> stations = new ArrayList<>();
        Set<Long> nodeIds = new HashSet<>();
        for (Map<String, Object> row : stationRows) {
            if (row == null) return null;
            Long nodeId = positiveLong(row.get("nodeId"));
            String nodeName = safeRecapText(row.get("nodeName"), 120);
            Map<String, Object> counts = integerFields(row, "arrivedPlayers", "submissionCount",
                    "normalCompletedCount", "fallbackCompletedCount", "rejectedCount", "pauseEventCount");
            if (nodeId == null || !nodeIds.add(nodeId) || nodeName == null || counts == null) return null;
            Map<String, Object> station = new LinkedHashMap<>();
            station.put("nodeId", nodeId);
            station.put("nodeName", nodeName);
            station.putAll(counts);
            stations.add(station);
        }
        if (stations.isEmpty()) return null;

        int requiredRoleCount = configuredRoles(session).size();
        if (requiredRoleCount < 1 && !isRouteSession(session)) return null;
        int eligibleTeams = 0;
        int completedTeams = 0;
        Set<Long> teamIds = new HashSet<>();
        for (Map<String, Object> row : collaborationRows) {
            if (row == null) return null;
            Long teamId = positiveLong(row.get("teamId"));
            Integer assigned = nonNegativeInteger(row.get("assignedRoleCount"));
            Integer contributing = nonNegativeInteger(row.get("contributedRoleCount"));
            if (teamId == null || !teamIds.add(teamId) || assigned == null || contributing == null) return null;
            if (requiredRoleCount > 0 && assigned >= requiredRoleCount) {
                eligibleTeams++;
                if (contributing >= requiredRoleCount) completedTeams++;
            }
        }
        Map<String, Object> collaboration = new LinkedHashMap<>();
        collaboration.put("eligibleTeams", eligibleTeams);
        collaboration.put("completedTeams", completedTeams);
        collaboration.put("ratePercent", eligibleTeams == 0 ? 0 : completedTeams * 100 / eligibleTeams);

        List<Map<String, Object>> metrics = new ArrayList<>();
        metrics.add(recapMetric("PAID_PLAYERS", isRouteSession(session) ? "持票玩家" : "付费玩家", funnel.get("paidPlayers"), "人"));
        metrics.add(recapMetric("ARRIVED_PLAYERS", "到场玩家", funnel.get("arrivedPlayers"), "人"));
        metrics.add(recapMetric("NORMAL_COMPLETERS", isRouteSession(session) ? "有完成进度" : "正常完成", funnel.get("normalCompleters"), "人"));
        metrics.add(recapMetric("FALLBACK_COMPLETERS", "兜底完成", funnel.get("fallbackCompleters"), "人"));
        metrics.add(recapMetric("FINISHED_TEAMS", "完成队伍", funnel.get("finishedTeams"), "队"));

        Map<String, Object> recap = new LinkedHashMap<>();
        recap.put("schemaVersion", "GAME_RECAP_V1");
        recap.put("generatedAt", formatDate(clock.now()));
        recap.put("metrics", metrics);
        recap.put("funnel", funnel);
        recap.put("hints", hints);
        recap.put("incidents", incidents);
        recap.put("collaboration", collaboration);
        recap.put("takeovers", singleton("count", takeoverCount));
        recap.put("stations", stations);
        recap.put("exportAvailable", true);
        return recap;
    }

    private Map<String, Object> recapMetric(String key, String label, Object value, String unit) {
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("key", key);
        metric.put("label", label);
        metric.put("value", value);
        metric.put("unit", unit);
        return metric;
    }

    private Map<String, Object> integerFields(Map<String, Object> source, String... fields) {
        if (source == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : fields) {
            Integer value = nonNegativeInteger(source.get(field));
            if (value == null) return null;
            result.put(field, value);
        }
        return result;
    }

    private Integer nonNegativeInteger(Object value) {
        if (!(value instanceof Number)) return null;
        long number = ((Number) value).longValue();
        return number >= 0 && number <= Integer.MAX_VALUE ? (int) number : null;
    }

    private String safeRecapText(Object value, int maxLength) {
        if (!(value instanceof String)) return null;
        String text = ((String) value).trim();
        return StringUtils.isNotBlank(text) && text.length() <= maxLength ? text : null;
    }

    private void preauthorize(GameActorContext actor, CmsActivity activity, GameSession session,
                              GameSessionCommand command) {
        String action = command.getAction();
        if (RUN_CONTROL_ACTIONS.contains(action)) {
            if ("TEAM_EXIT".equals(action) && "PLAYER".equals(actor.getPerspective())) {
                PlayerScope player = requirePlayer(actor, activity); requireRouteLeader(actor.getMemberId(), activity.getId(), player.teamId);
                if (!Objects.equals(player.teamId, positiveLong(command.getPayload().get("teamId")))) throw error("GAME_FORBIDDEN_SCOPE", "只能退出本队");
                return;
            }
            if ("STAFF_MANUAL_SIGN".equals(action)) {
                JsonNode signed = objectMapper.valueToTree(command.getPayload().get("command"));
                if (!Arrays.asList("ARRIVAL_CONFIRM", "ARRIVAL_ATTEST", "QUEST_GUIDE").contains(signed.path("action").asText())) throw error("GAME_FORBIDDEN_SCOPE", "工作人员只能签署真实核验动作");
                requireRunStaff(actor, activity, signed.path("action").asText()); return;
            }
            if (!"CLUB".equals(actor.getPerspective()) || !isSharedRoute(session)) throw error("GAME_FORBIDDEN_SCOPE", "仅本场获授权工作人员可操作");
            requireRunStaff(actor, activity, action);
            return;
        }
        if (SHARED_PLAYER_ACTIONS.contains(action) || cooperationAction(action)) {
            if (!"PLAYER".equals(actor.getPerspective()) || !isSharedRoute(session)) throw error("GAME_ACTION_UNSUPPORTED", "此动作仅适用于队伍共享局");
            PlayerScope player = requirePlayer(actor, activity);
            if(!"ITEM_RECEIVE".equals(action))requireNaturalPlayOpen(session, routeOwnerState(requireRouteOwner(session, player.teamId)));
            if(!Arrays.asList("ZERO_HP_STEPS","ZERO_HP_SYNC").contains(action))requireRouteLeader(actor.getMemberId(), activity.getId(), player.teamId);
            return;
        }
        if (CLUB_ACTIONS.contains(action)) {
            if (!"CLUB".equals(actor.getPerspective())) throw error("GAME_FORBIDDEN_SCOPE", "仅俱乐部主理人可执行该操作");
            requireClubOwner(actor, activity);
            if (!"PREPARE".equals(action) && session == null) throw error("GAME_SESSION_NOT_PREPARED", "本场游戏尚未准备");
            return;
        }
        if (MERCHANT_ACTIONS.contains(action)) {
            if (!"MERCHANT".equals(actor.getPerspective()) || session == null) {
                throw error("GAME_FORBIDDEN_SCOPE", "仅本站商家可执行该操作");
            }
            requireMerchantPermission(actor, "VERIFY_SUBMISSION".equals(action)
                    ? MerchantPermission.VERIFY : MerchantPermission.PROJECT_MANAGE);
            Long nodeId = "VERIFY_SUBMISSION".equals(action) ? null : command.getNodeId();
            if (nodeId != null) requireMerchantStation(actor, session, nodeId);
            return;
        }
        if (PLAYER_ACTIONS.contains(action)) {
            if (!"PLAYER".equals(actor.getPerspective()) || session == null) {
                throw error("GAME_FORBIDDEN_SCOPE", "仅本场玩家可执行该操作");
            }
            com.chengyinhub.business.service.support.CurrentPlayRegistration.require(activityMapper,
                    currentPlayRegistrationMapper,actor.getMemberId(),2L,activity.getId(),null,clock.now(),true);
            requirePlayer(actor, activity);
            if (!"CONFIRM_ROLE".equals(action)) requireConfirmedRoleForUpdate(session, actor.getMemberId());
            if (!"CONFIRM_ROLE".equals(action) && command.getNodeId() != null) {
                requirePlayableStation(activity, session, command.getNodeId());
            }
            return;
        }
        throw error("GAME_ACTION_UNSUPPORTED", "不支持的游戏动作");
    }

    private void authorizeView(GameActorContext actor, CmsActivity activity, GameSession session) {
        if ("CLUB".equals(actor.getPerspective())) {
            requireClubOwner(actor, activity);
        } else if ("PLAYER".equals(actor.getPerspective())) {
            requirePlayer(actor, activity);
        } else if ("MERCHANT".equals(actor.getPerspective())) {
            requireMerchantStationView(actor);
            if (session == null
                    || safe(runtimeMapper.selectMerchantStations(session.getId(), merchantOwnerMemberId(actor))).isEmpty()) {
                throw error("GAME_FORBIDDEN_SCOPE", "当前商家不负责本场站点");
            }
        } else {
            throw error("GAME_FORBIDDEN_SCOPE", "无权访问该游戏");
        }
    }

    private PlayerScope requirePlayer(GameActorContext actor, CmsActivity activity) {
        return new PlayerScope(sharedRunControl().requirePlayerTeam(actor, activity));
    }

    private GameTeamRoleAssignment requireConfirmedRoleForUpdate(GameSession session, Long memberId) {
        GameTeamRoleAssignment assignment = runtimeMapper.selectActiveRoleForUpdate(session.getId(), memberId);
        if (assignment == null) assignment = runtimeMapper.selectRole(session.getId(), memberId);
        if (!isConfirmedRole(assignment)) {
            throw error("GAME_ROLE_NOT_ASSIGNED", "尚未持有可执行游戏行动的已确认角色");
        }
        return assignment;
    }

    private boolean isConfirmedRole(GameTeamRoleAssignment assignment) {
        return assignment != null && "CONFIRMED".equals(assignment.getStatus());
    }

    private void requireClubOwner(GameActorContext actor, CmsActivity activity) {
        try {
            if (!capabilityService.hasCapability(actor.getMemberId(),
                    com.chengyinhub.business.domain.MemberCapability.CAP_CLUB_LEADER)) {
                throw error("GAME_FORBIDDEN_SCOPE", "仅该场俱乐部主理人可操作");
            }
        } catch (ServiceException denied) {
            throw error("GAME_FORBIDDEN_SCOPE", "仅该场俱乐部主理人可操作");
        }
        if (activity.getTopicId() == null || activity.getClubId() == null
                || !clubMemberService.isTopicOwner(activity.getTopicId(), activity.getClubId(), actor.getMemberId())) {
            throw error("GAME_FORBIDDEN_SCOPE", "仅该场俱乐部主理人可操作");
        }
    }

    private GameStationRun requireMerchantStation(GameActorContext actor, GameSession session, Long nodeId) {
        GameStationRun station = nodeId == null ? null : runtimeMapper.selectStation(session.getId(), nodeId);
        if (station == null || station.getMerchantMemberId() == null
                || !station.getMerchantMemberId().equals(merchantOwnerMemberId(actor))) {
            throw error("GAME_FORBIDDEN_SCOPE", "站点不属于当前商家");
        }
        return station;
    }

    /** 商家主体:员工操作落到所属商家(店主)名下;旧 actor 未解析商家时退回登录人本人。 */
    private Long merchantOwnerMemberId(GameActorContext actor) {
        Long ownerMemberId = actor == null ? null : actor.getMerchantOwnerMemberId();
        return ownerMemberId != null && ownerMemberId > 0 ? ownerMemberId : actor.getMemberId();
    }

    /** 站点动作权限:就绪/暂停等由 PROJECT_MANAGE 放行,核验提交由 VERIFY 放行。 */
    private void requireMerchantPermission(GameActorContext actor, String permission) {
        if (!MerchantPermissionPolicy.hasPermission(actor.getMerchantRoleCode(), permission)) {
            throw error("GAME_FORBIDDEN_SCOPE", "当前岗位无权执行该商家站点操作");
        }
    }

    /** 站点入口/投影的读权限:持有 VERIFY 或 PROJECT_MANAGE 任一即可。 */
    private void requireMerchantStationView(GameActorContext actor) {
        if (!MerchantPermissionPolicy.hasPermission(actor.getMerchantRoleCode(), MerchantPermission.VERIFY)
                && !MerchantPermissionPolicy.hasPermission(actor.getMerchantRoleCode(), MerchantPermission.PROJECT_MANAGE)) {
            throw error("GAME_FORBIDDEN_SCOPE", "当前岗位无权查看商家站点");
        }
    }

    private GameStationRun requirePlayableStation(CmsActivity activity, GameSession session, Long nodeId) {
        Long physicalNode = nodeId;
        if (isSharedRoute(session)) {
            boolean actualBinding = false;
            for (JsonNode quest : routeGameplay(session).path("quests")) if (Objects.equals(nodeId, bindingNode(session, quest))) actualBinding = true;
            if (!actualBinding) for (JsonNode quest : routeGameplay(session).path("quests")) if (Objects.equals(nodeId, quest.path("nodeId").asLong())) { physicalNode = bindingNode(session, quest); break; }
        }
        GameStationRun station = physicalNode == null ? null : runtimeMapper.selectStation(session.getId(), physicalNode);
        if (station == null) throw error("GAME_FORBIDDEN_SCOPE", "节点不属于本场游戏");
        if (GameStationRun.PAUSED.equals(station.getStatus())) throw error("GAME_STATION_PAUSED", "站点暂停中");
        if (!GameStationRun.ACTIVE.equals(station.getStatus())) throw error("GAME_STATION_NOT_READY", "站点尚未开始服务");
        Date now = evaluationTime();
        requireActivityServiceWindow(activity, now);
        requireStationServiceWindow(station, now);
        return station;
    }

    private void requireArrived(GameActorContext actor, CmsActivity activity, GameStationRun station) {
        if (!hasArrived(actor, activity, station)) {
            throw error("GAME_STATION_ARRIVAL_REQUIRED", "请先完成本站到店签到");
        }
    }

    private boolean hasArrived(GameActorContext actor, CmsActivity activity, GameStationRun station) {
        PlayerNodeProgress query = new PlayerNodeProgress();
        query.setMemberId(actor.getMemberId());
        query.setActivityId(activity.getId());
        query.setTopicId(activity.getTopicId());
        query.setNodeId(station.getNodeId());
        for (PlayerNodeProgress progress : safe(playerNodeProgressService.selectPlayerNodeProgressList(query))) {
            if (progress != null && progress.getCheckinTime() != null
                    && actor.getMemberId().equals(progress.getMemberId())
                    && activity.getId().equals(progress.getActivityId())
                    && activity.getTopicId().equals(progress.getTopicId())
                    && station.getNodeId().equals(progress.getNodeId())) {
                return true;
            }
        }
        return false;
    }

    private CmsActivity requireActivity(Long activityId, boolean rejectCancelled) {
        CmsActivity activity = activityId == null ? null : activityMapper.selectCmsActivityById(activityId);
        if (activity == null || Integer.valueOf(2).equals(activity.getDelFlag())
                || !Long.valueOf(1L).equals(activity.getStatus()) || activity.getTopicId() == null) {
            throw error("GAME_ACTIVITY_UNAVAILABLE", "活动不存在或不可用");
        }
        if (rejectCancelled && activity.getCancelTime() != null) {
            throw error("GAME_ACTIVITY_CANCELLED", "活动已取消");
        }
        return activity;
    }

    private void requireActivityServiceWindow(CmsActivity activity, Date now) {
        if (!isWithinActivityServiceWindow(activity, now)) {
            throw error("GAME_ACTIVITY_OUTSIDE_SERVICE_WINDOW", "当前不在活动服务时段");
        }
    }

    private void requireStationServiceWindow(GameStationRun station, Date now) {
        if (!isWithinStationServiceWindow(station, now)) {
            throw error("GAME_STATION_OUTSIDE_SERVICE_WINDOW", "当前不在站点服务时段");
        }
    }

    private boolean isWithinActivityServiceWindow(CmsActivity activity, Date now) {
        return activity != null && activity.getStartDate() != null && activity.getEndDate() != null
                && now != null && !now.before(activity.getStartDate()) && now.before(activity.getEndDate());
    }

    private boolean isWithinStationServiceWindow(GameStationRun station, Date now) {
        return station != null && station.getServiceStartAt() != null && station.getServiceEndAt() != null
                && now != null && !now.before(station.getServiceStartAt()) && now.before(station.getServiceEndAt());
    }

    private void requireActor(GameActorContext actor) {
        if (actor == null || actor.getMemberId() == null || actor.getMemberId() <= 0) {
            throw error("GAME_LOGIN_REQUIRED", "请先登录");
        }
    }

    private void validateCommand(GameSessionCommand command) {
        if (command == null || command.getActivityId() == null || command.getActivityId() <= 0
                || StringUtils.isBlank(command.getRequestId()) || command.getRequestId().length() > 64
                || StringUtils.isBlank(command.getAction())) {
            throw error("GAME_BAD_REQUEST", "游戏命令参数不完整");
        }
        if (!"PREPARE".equalsIgnoreCase(command.getAction())
                && (command.getExpectedRevision() == null || command.getExpectedRevision() < 0)) {
            throw error("GAME_BAD_REQUEST", "缺少当前版本号");
        }
    }

    private void requireSessionStatus(GameSession session, String... allowed) {
        if (session == null) throw error("GAME_SESSION_NOT_PREPARED", "本场游戏尚未准备");
        for (String status : allowed) if (status.equals(session.getStatus())) return;
        throw error("GAME_STATE_CONFLICT", "当前游戏状态不能执行该操作");
    }

    private void bumpSession(GameSession session, String toStatus, Long currentChapterId,
                             Long actorMemberId, Date now) {
        Integer expected = session.getRevision() == null ? 0 : session.getRevision();
        if (runtimeMapper.updateSessionCas(session.getId(), expected, session.getStatus(), toStatus,
                currentChapterId, actorMemberId, now) != 1) {
            throw error("GAME_STATE_CONFLICT", "游戏状态已变化，请刷新后重试");
        }
        session.setStatus(toStatus);
        session.setCurrentChapterId(currentChapterId);
        session.setRevision(expected + 1);
        if (GameSession.RUNNING.equals(toStatus) && session.getStartedAt() == null) {
            session.setStartedAt(now);
            session.setStartedBy(actorMemberId);
        }
        if (GameSession.FINISHED.equals(toStatus)) {
            session.setFinishedAt(now);
            session.setFinishedBy(actorMemberId);
        }
    }

    private String readinessStatus(GameSession session) {
        boolean stationsReady = runtimeMapper.countUnreadyStations(session.getId()) == 0;
        boolean hasTeams = runtimeMapper.countActiveTeams(session.getActivityId()) > 0;
        boolean rolesReady = isRouteSession(session)
                || runtimeMapper.countTeamMembersWithoutRole(session.getId(), session.getActivityId()) == 0;
        return stationsReady && hasTeams && rolesReady ? GameSession.READY : GameSession.PREPARING;
    }

    private List<String> readinessBlockers(GameSession session) {
        List<Map<String, Object>> stations = new ArrayList<>();
        for (GameStationRun station : safe(runtimeMapper.selectStations(session.getId()))) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("nodeName", station.getNodeName());
            value.put("status", station.getStatus());
            stations.add(value);
        }
        boolean teamsReady = runtimeMapper.countActiveTeams(session.getActivityId()) > 0
                && (isRouteSession(session) || runtimeMapper.countTeamMembersWithoutRole(session.getId(), session.getActivityId()) == 0);
        return readinessBlockers(session, stations, teamsReady);
    }

    private List<String> readinessBlockers(GameSession session, List<Map<String, Object>> stations,
                                           boolean teamsReady) {
        List<String> blockers = new ArrayList<>();
        if (stations.isEmpty()) blockers.add("尚未配置可运行站点");
        for (Map<String, Object> station : stations) {
            String status = upper(station.get("status"));
            if (GameStationRun.READY.equals(status)
                    || (GameSession.RUNNING.equals(session.getStatus()) && GameStationRun.ACTIVE.equals(status))) {
                continue;
            }
            String name = text(station.get("nodeName"), 120);
            if (StringUtils.isBlank(name)) name = "未命名站点";
            blockers.add(name + "（" + readinessStatusLabel(status) + "）");
        }
        if (runtimeMapper.countActiveTeams(session.getActivityId()) < 1) {
            blockers.add("尚无有效报名队伍");
        } else if (!teamsReady) {
            blockers.add("仍有有效队员未确认角色");
        }
        return blockers;
    }

    private String readinessStatusLabel(String status) {
        if (GameStationRun.INVITED.equals(status)) return "待商家接受";
        if (GameStationRun.ACCEPTED.equals(status)) return "准备项未完成";
        if (GameStationRun.PAUSED.equals(status)) return "已暂停";
        if (GameStationRun.CLOSED.equals(status)) return "已关闭";
        if (GameStationRun.ACTIVE.equals(status)) return "已激活但当前局未运行";
        return StringUtils.isBlank(status) ? "状态未知" : status;
    }

    private List<String> startStationBlockers(List<GameStationRun> stations, Date now) {
        List<String> blockers = new ArrayList<>();
        if (stations.isEmpty()) {
            blockers.add("尚未配置可运行站点");
            return blockers;
        }
        for (GameStationRun station : stations) {
            String name = text(station == null ? null : station.getNodeName(), 120);
            if (StringUtils.isBlank(name)) name = "未命名站点";
            String status = upper(station == null ? null : station.getStatus());
            if (!GameStationRun.READY.equals(status)) {
                blockers.add(name + "（" + readinessStatusLabel(status) + "）");
                continue;
            }
            if (!isWithinStationServiceWindow(station, now)) {
                blockers.add(name + "（站点服务时段：" + formatDate(station.getServiceStartAt())
                        + " 至 " + formatDate(station.getServiceEndAt()) + "）");
            }
        }
        return blockers;
    }

    private ReadyConfig readyConfig(Map<String, Object> payload, GameSession session, GameStationRun station) { return configuration().readyConfig(payload, session, station); }

    private void applyStationCodes(Long sessionId, List<GameStationRun> stations, JsonNode configuredStations) {
        Set<String> matchedCodes = new HashSet<>();
        for (GameStationRun station : stations) {
            JsonNode configured = configuredStation(configuredStations, null, station.getNodeName());
            if (configured == null || station.getMerchantMemberId() == null || station.getMerchantMemberId() <= 0) {
                throw error("GAME_CONFIG_INVALID", "每个游戏站点都必须唯一映射配置并指定有效商家");
            }
            String code = configured.path("code").asText();
            if (StringUtils.isBlank(code) || !matchedCodes.add(code)) {
                throw error("GAME_CONFIG_INVALID", "游戏站点编号为空或重复映射");
            }
            runtimeMapper.updateStationCode(sessionId, station.getNodeId(), code);
            station.setStationCode(code);
        }
        if (matchedCodes.size() != configuredStations.size()) {
            throw error("GAME_CONFIG_INVALID", "游戏配置站点未全部匹配主题节点");
        }
    }

    private JsonNode configuredStation(JsonNode stations, String stationCode, String nodeName) { return configuration().configuredStation(stations, stationCode, nodeName); }

    private JsonNode validateModuleConfig(String json) { return configuration().validateModuleConfig(json); }

    private JsonNode sessionConfig(GameSession session) { return configuration().sessionConfig(session); }

    private Map<String, String> configuredRoles(GameSession session) { return configuration().configuredRoles(session); }

    private Set<Long> configuredChapterIds(JsonNode config) { return configuration().configuredChapterIds(config); }

    private Long firstChapterId(JsonNode config) { return configuration().firstChapterId(config); }

    private boolean isLaterChapter(JsonNode config, Long currentChapterId, Long targetChapterId) { return configuration().isLaterChapter(config, currentChapterId, targetChapterId); }

    private boolean hasUnlockedChoices(JsonNode config, Long currentChapterId) { return configuration().hasUnlockedChoices(config, currentChapterId); }

    private List<Map<String, Object>> roleOptions(JsonNode config) { return configuration().roleOptions(config); }

    private List<Map<String, Object>> chapterOptions(JsonNode config, Long currentChapterId) { return configuration().chapterOptions(config, currentChapterId); }

    private List<Map<String, Object>> broadcastViews(List<GameBroadcast> broadcasts) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (GameBroadcast broadcast : broadcasts) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("broadcastId", broadcast.getId());
            value.put("senderMemberId", broadcast.getSenderMemberId());
            value.put("targetType", broadcast.getTargetType());
            value.put("targetId", broadcast.getTargetId());
            value.put("roleCode", broadcast.getRoleCode());
            value.put("content", broadcast.getContent());
            value.put("status", broadcast.getStatus());
            value.put("createdAt", formatDate(broadcast.getCreateTime()));
            value.put("updatedAt", formatDate(broadcast.getUpdateTime()));
            values.add(value);
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> clubTeams(List<Map<String, Object>> rows,
                                                List<Map<String, Object>> progressRows) {
        Map<Long, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> progress : progressRows) {
            Long teamId = positiveLong(progress.get("teamId"));
            if (teamId == null) continue;
            Map<String, Object> team = clubTeamProgress(progress);
            team.put("members", new ArrayList<Map<String, Object>>());
            grouped.put(teamId, team);
        }
        for (Map<String, Object> row : rows) {
            Long teamId = positiveLong(row.get("teamId"));
            if (teamId == null) continue;
            Map<String, Object> team = grouped.get(teamId);
            if (team == null) {
                team = new LinkedHashMap<>();
                team.put("teamId", teamId);
                team.put("teamName", row.get("teamName"));
                team.put("status", "PREPARING");
                team.put("members", new ArrayList<Map<String, Object>>());
                grouped.put(teamId, team);
            }
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("memberId", row.get("memberId"));
            member.put("memberName", row.get("memberName"));
            member.put("roleCode", row.get("roleCode"));
            member.put("status", row.get("status"));
            ((List<Map<String, Object>>) team.get("members")).add(member);
        }
        for (Map<String, Object> team : grouped.values()) {
            if (!(team.get("memberCount") instanceof Number)) {
                team.put("memberCount", ((List<?>) team.get("members")).size());
            }
        }
        return new ArrayList<>(grouped.values());
    }

    private Map<String, Object> clubTeamProgress(Map<String, Object> progress) {
        Map<String, Object> team = new LinkedHashMap<>(progress);
        Long stuckNodeId = positiveLong(team.remove("currentStuckNodeId"));
        String stuckNodeName = text(team.remove("currentStuckNodeName"), 120);
        String stationStatus = upper(team.remove("currentStuckStationStatus"));
        String submissionStatus = upper(team.remove("currentStuckSubmissionStatus"));
        Integer hintLevel = team.get("hintLevel") instanceof Number
                ? ((Number) team.get("hintLevel")).intValue() : null;
        if (hintLevel == null || hintLevel < 1 || hintLevel > 2) team.remove("hintLevel");

        Map<String, Object> stuckNode = null;
        if (stuckNodeId != null && StringUtils.isNotBlank(stuckNodeName)) {
            stuckNode = new LinkedHashMap<>();
            stuckNode.put("nodeId", stuckNodeId);
            stuckNode.put("nodeName", stuckNodeName);
            stuckNode.put("stationStatus", StringUtils.isBlank(stationStatus) ? null : stationStatus);
            stuckNode.put("submissionStatus", StringUtils.isBlank(submissionStatus) ? null : submissionStatus);
            team.put("currentStuckNode", stuckNode);
        }

        String recentRequestId = text(team.remove("recentEventRequestId"), 64);
        String recentAction = upper(team.remove("recentEventAction"));
        String recentOutcome = upper(team.remove("recentEventOutcome"));
        String recentReasonCode = upper(team.remove("recentEventReasonCode"));
        Object recentRevision = team.remove("recentEventRevision");
        Object recentAt = team.remove("recentEventAt");
        if (StringUtils.isNotBlank(recentRequestId) && StringUtils.isNotBlank(recentAction)
                && StringUtils.isNotBlank(recentOutcome) && recentAt instanceof Date) {
            Map<String, Object> recent = new LinkedHashMap<>();
            recent.put("requestId", recentRequestId);
            recent.put("action", recentAction);
            recent.put("outcome", recentOutcome);
            recent.put("reasonCode", StringUtils.isBlank(recentReasonCode) ? null : recentReasonCode);
            recent.put("revision", recentRevision instanceof Number ? ((Number) recentRevision).intValue() : null);
            recent.put("occurredAt", formatDate((Date) recentAt));
            team.put("recentEvent", recent);
        }

        if (StringUtils.isBlank(text(team.get("blockedReason"), 200)) && stuckNode != null) {
            if (GameStationRun.PAUSED.equals(stationStatus)) {
                team.put("blockedReason", stuckNodeName + "（已暂停）");
            } else if (GameStationRun.CLOSED.equals(stationStatus)) {
                team.put("blockedReason", stuckNodeName + "（已关闭）");
            } else if ("PENDING".equals(submissionStatus)) {
                team.put("blockedReason", stuckNodeName + "（等待本站核验）");
            } else if ("REJECTED".equals(submissionStatus)) {
                team.put("blockedReason", stuckNodeName + "（任务已驳回）");
            } else if (hintLevel != null && hintLevel >= 2) {
                team.put("blockedReason", stuckNodeName + "（已使用二级提示）");
            }
        }
        return team;
    }

    private JsonNode findChoice(JsonNode config, String choiceId, String stationCode, String roleCode,
                                Long currentChapterId) { return configuration().findChoice(config, choiceId, stationCode, roleCode, currentChapterId); }

    private Map<String, Object> choiceResult(String choiceId, boolean duplicate, JsonNode ending) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("choiceId", choiceId);
        data.put("duplicate", duplicate);
        data.put("ending", ending == null ? null : publicEnding(ending));
        return data;
    }

    private boolean allRequiredChoicesApplied(JsonNode config, ArrayNode appliedChoices) { return configuration().allRequiredChoicesApplied(config, appliedChoices); }

    private boolean choiceGroupAlreadyApplied(JsonNode config, ArrayNode appliedChoices, JsonNode nextChoice) { return configuration().choiceGroupAlreadyApplied(config, appliedChoices, nextChoice); }

    private GameStoryState storyState(GameSession session, Long teamId, JsonNode config) {
        GameStoryState state = runtimeMapper.selectStoryState(session.getId(), "TEAM", teamId);
        if (state != null) return state;
        state = new GameStoryState();
        state.setGameSessionId(session.getId());
        state.setOwnerType("TEAM");
        state.setOwnerId(teamId);
        state.setVariablesJson(writeJson(config.path("story").path("variables")));
        state.setAppliedChoicesJson("[]");
        state.setStatus("INITIAL");
        state.setRevision(0);
        state.setCreateTime(clock.now());
        state.setUpdateTime(clock.now());
        if (runtimeMapper.insertStoryState(state) != 1) {
            state = runtimeMapper.selectStoryStateForUpdate(session.getId(), "TEAM", teamId);
            if (state == null) throw error("GAME_STATE_CONFLICT", "剧情状态创建失败");
        }
        return state;
    }

    private static final class OutcomeChanges { private boolean added; }

    private GamePlayerNodeOutcome recoverSubmissionOutcome(GameSession session, GameStationRun station,
                                                            Long teamId, Long memberId,
                                                            GameTaskSubmission submission, JsonNode taskConfig,
                                                            Long receiptId) {
        OutcomeChanges changes = new OutcomeChanges();
        GamePlayerNodeOutcome outcome = outcomeForSubmission(session, station, teamId, memberId,
                submission, taskConfig, receiptId, changes);
        if (changes.added) {
            bumpSession(session, session.getStatus(), session.getCurrentChapterId(), memberId, clock.now());
        }
        return outcome;
    }

    private GamePlayerNodeOutcome outcomeForSubmission(GameSession session, GameStationRun station,
                                                       Long teamId, Long memberId,
                                                       GameTaskSubmission submission, JsonNode taskConfig,
                                                       Long receiptId) {
        return outcomeForSubmission(session, station, teamId, memberId, submission, taskConfig, receiptId, null);
    }

    private GamePlayerNodeOutcome outcomeForSubmission(GameSession session, GameStationRun station,
                                                       Long teamId, Long memberId,
                                                       GameTaskSubmission submission, JsonNode taskConfig,
                                                       Long receiptId, OutcomeChanges changes) {
        if (submission == null || "PENDING".equals(submission.getStatus())
                || "REJECTED".equals(submission.getStatus())) {
            return null;
        }
        String source;
        if ("RECORDED".equals(submission.getStatus())) {
            if (!"EVIDENCE_ONLY".equals(upper(taskConfig.path("completionPolicy").asText()))) {
                throw error("GAME_CONFIG_INVALID", "照片留档任务缺少完成策略");
            }
            source = "EVIDENCE_ONLY";
        } else if ("APPROVED".equals(submission.getStatus())) {
            source = submission.getDecidedBy() == null ? "AUTO_HASH" : "MERCHANT_APPROVAL";
        } else {
            return null;
        }
        GamePlayerNodeOutcome outcome = ensureOutcome(session, station, teamId, memberId,
                GamePlayerNodeOutcome.COMPLETED, source, submission.getId(), receiptId, null, null, changes);
        ensureFallbackSourceOutcomes(session, station, teamId, memberId, receiptId, changes);
        return outcome;
    }

    private GamePlayerNodeOutcome ensureOutcome(GameSession session, GameStationRun station, Long teamId,
                                                Long memberId, String status, String completionSource,
                                                Long submissionId, Long receiptId, String planCode,
                                                Integer planVersion) {
        return ensureOutcome(session, station, teamId, memberId, status, completionSource,
                submissionId, receiptId, planCode, planVersion, null);
    }

    private GamePlayerNodeOutcome ensureOutcome(GameSession session, GameStationRun station, Long teamId,
                                                Long memberId, String status, String completionSource,
                                                Long submissionId, Long receiptId, String planCode,
                                                Integer planVersion, OutcomeChanges changes) {
        GamePlayerNodeOutcome existing = runtimeMapper.selectPlayerNodeOutcome(
                session.getId(), memberId, station.getNodeId());
        if (existing != null) return existing;
        GamePlayerNodeOutcome outcome = new GamePlayerNodeOutcome();
        outcome.setGameSessionId(session.getId());
        outcome.setActivityId(session.getActivityId());
        outcome.setStationRunId(station.getId());
        outcome.setNodeId(station.getNodeId());
        outcome.setTeamId(teamId);
        outcome.setMemberId(memberId);
        outcome.setStatus(status);
        outcome.setCompletionSource(completionSource);
        outcome.setSourceSubmissionId(submissionId);
        outcome.setSourceReceiptId(receiptId);
        outcome.setFallbackPlanCode(planCode);
        outcome.setFallbackPlanVersion(planVersion);
        Date now = clock.now();
        outcome.setCompletedAt(now);
        outcome.setCreateTime(now);
        if (runtimeMapper.insertPlayerNodeOutcome(outcome) != 1) {
            existing = runtimeMapper.selectPlayerNodeOutcome(session.getId(), memberId, station.getNodeId());
            if (existing == null) throw error("GAME_STATE_CONFLICT", "节点完成结果落库失败");
            return existing;
        }
        if (changes != null) changes.added = true;
        return outcome;
    }

    private void ensureFallbackSourceOutcomes(GameSession session, GameStationRun completedTarget,
                                              Long teamId, Long memberId, Long receiptId) {
        ensureFallbackSourceOutcomes(session, completedTarget, teamId, memberId, receiptId, null);
    }

    private void ensureFallbackSourceOutcomes(GameSession session, GameStationRun completedTarget,
                                              Long teamId, Long memberId, Long receiptId, OutcomeChanges changes) {
        JsonNode config = sessionConfig(session);
        for (GameStationRun source : safe(runtimeMapper.selectStations(session.getId()))) {
            if (source == null || !session.getId().equals(source.getGameSessionId())
                    || !GameStationRun.PAUSED.equals(source.getStatus())
                    || !completedTarget.getNodeId().equals(source.getFallbackNodeId())
                    || StringUtils.isBlank(source.getFallbackPlanCode())
                    || source.getFallbackPlanVersion() == null || source.getFallbackPlanVersion() <= 0
                    || !isApprovedFallbackBinding(config, source, completedTarget)) {
                continue;
            }
            ensureOutcome(session, source, teamId, memberId,
                    GamePlayerNodeOutcome.FALLBACK_COMPLETED, "FALLBACK_PLAN", null, receiptId,
                    source.getFallbackPlanCode(), source.getFallbackPlanVersion(), changes);
        }
    }

    private boolean isApprovedFallbackBinding(JsonNode config, GameStationRun source,
                                              GameStationRun target) {
        if (source.getMerchantMemberId() == null || target.getMerchantMemberId() == null
                || source.getMerchantMemberId().equals(target.getMerchantMemberId())) return false;
        for (JsonNode plan : config.path("fallbackPlans")) {
            if ("APPROVED".equals(upper(plan.path("status").asText()))
                    && source.getFallbackPlanCode().equals(upper(plan.path("planCode").asText()))
                    && source.getFallbackPlanVersion() == plan.path("version").asInt(0)
                    && source.getStationCode().equals(plan.path("sourceStationCode").asText())
                    && target.getStationCode().equals(plan.path("targetStationCode").asText())) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> submissionResult(GameTaskSubmission submission,
                                                 GamePlayerNodeOutcome outcome, boolean duplicate) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("submissionId", submission.getId());
        result.put("status", submission.getStatus());
        result.put("duplicate", duplicate);
        addOutcomeResult(result, outcome);
        return result;
    }

    private void addOutcomeResult(Map<String, Object> result, GamePlayerNodeOutcome outcome) {
        if (outcome == null) return;
        result.put("completionStatus", outcome.getStatus());
        result.put("completionSource", outcome.getCompletionSource());
        result.put("completionOrder", outcome.getId());
        result.put("completedAt", formatDate(outcome.getCompletedAt()));
    }

    private void applyEffects(ObjectNode variables, JsonNode effects) {
        if (!effects.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> fields = effects.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> effect = fields.next();
            JsonNode value = effect.getValue();
            if (value.isNumber()) variables.put(effect.getKey(), variables.path(effect.getKey()).asInt(0) + value.asInt());
            else variables.set(effect.getKey(), value);
        }
    }

    /** 数组写法:借路线图 State 承载变量与 tag 求值,再投影回旧的状态 JSON;tag 走保留键。 */
    private void applyRouteEffects(ObjectNode variables, JsonNode effects) {
        TopicRouteEngine.State state = routeStoryState(variables);
        topicRouteEngine.applyEffects(effects, state);
        for (Map.Entry<String, Number> entry : state.getVariables().entrySet()) {
            double value = entry.getValue().doubleValue();
            if (value == Math.rint(value) && !Double.isInfinite(value)) variables.put(entry.getKey(), (long) value);
            else variables.put(entry.getKey(), value);
        }
        if (state.getTags().isEmpty()) return;
        ArrayNode tags = variables.putArray(STORY_TAGS_KEY);
        for (String tag : state.getTags()) tags.add(tag);
    }

    private TopicRouteEngine.State routeStoryState(ObjectNode variables) {
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        Iterator<Map.Entry<String, JsonNode>> fields = variables.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getValue().isNumber()) state.getVariables().put(field.getKey(), field.getValue().numberValue());
        }
        JsonNode tags = variables.path(STORY_TAGS_KEY);
        if (tags.isArray()) for (JsonNode tag : tags) {
            if (tag.isTextual() && !tag.asText().isEmpty()) state.getTags().add(tag.asText());
        }
        return state;
    }

    private JsonNode resolveEnding(JsonNode endings, ObjectNode variables) {
        JsonNode fallback = null;
        if (!endings.isArray()) return null;
        for (JsonNode ending : endings) {
            if (ending.path("fallback").asBoolean(false)) { fallback = ending; continue; }
            if (conditionsMatch(ending.path("conditions"), variables)) return ending;
        }
        return fallback;
    }

    private boolean conditionsMatch(JsonNode conditions, ObjectNode variables) {
        if (conditions.isArray()) return topicRouteEngine.conditionsPass(conditions, routeStoryState(variables));
        if (!conditions.isObject() || conditions.size() == 0) return false;
        Iterator<Map.Entry<String, JsonNode>> fields = conditions.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> condition = fields.next();
            int actual = variables.path(condition.getKey()).asInt(0);
            JsonNode rule = condition.getValue();
            if (rule.has("gte") && actual < rule.path("gte").asInt()) return false;
            if (rule.has("lte") && actual > rule.path("lte").asInt()) return false;
            if (rule.has("eq") && actual != rule.path("eq").asInt()) return false;
        }
        return true;
    }

    private Map<String, Object> publicRole(JsonNode config, GameTeamRoleAssignment assignment) { return configuration().publicRole(config, assignment); }

    private JsonNode configuredTask(JsonNode config, GameStationRun station) { return configuration().configuredTask(config, station); }

    private Map<String, Object> publicTask(JsonNode config, GameStationRun station) { return configuration().publicTask(config, station); }

    private JsonNode requireHintPolicy(JsonNode config, GameStationRun station) { return configuration().requireHintPolicy(config, station); }

    private Map<Long, Integer> playerHintLevels(Long sessionId, Long memberId) {
        Map<Long, Integer> levels = new LinkedHashMap<>();
        for (Map<String, Object> row : safe(runtimeMapper.selectPlayerHintLevels(sessionId, memberId))) {
            Long nodeId = positiveLong(row.get("nodeId"));
            int level = Convert.toInt(row.get("currentLevel"), 0);
            if (nodeId != null && level >= 1 && level <= 2) levels.put(nodeId, level);
        }
        return levels;
    }

    private int playerHintLevel(Long sessionId, Long memberId, Long nodeId) {
        return playerHintLevels(sessionId, memberId).getOrDefault(nodeId, 0);
    }

    private Map<String, Object> hintView(JsonNode policy, int currentLevel,
                                         GamePlayerNodeOutcome outcome) { return configuration().hintView(policy, currentLevel, outcome); }

    private String roleClue(JsonNode config, String roleCode, String stationCode) { return configuration().roleClue(config, roleCode, stationCode); }

    private List<Map<String, Object>> allowedChoices(JsonNode config, GameTeamRoleAssignment assignment,
                                                     String stationCode, Long currentChapterId) { return configuration().allowedChoices(config, assignment, stationCode, currentChapterId); }

    private Map<String, Object> publicStory(GameSession session, Long teamId, JsonNode config) {
        Map<String, Object> storyView = new LinkedHashMap<>();
        GameStoryState story = runtimeMapper.selectStoryState(session.getId(), "TEAM", teamId);
        Map<String, Object> visible = new LinkedHashMap<>();
        if (story != null) {
            ObjectNode variables = readObject(story.getVariablesJson());
            for (JsonNode key : config.path("story").path("publicVariables")) {
                if (key.isTextual() && variables.has(key.asText())) visible.put(key.asText(), scalar(variables.get(key.asText())));
            }
            storyView.put("status", story.getStatus());
            storyView.put("ending", publicEnding(findEnding(config, story.getEndingCode())));
        } else {
            storyView.put("status", "INITIAL");
            storyView.put("ending", null);
        }
        storyView.put("visibleVariables", visible);
        return storyView;
    }

    private JsonNode findEnding(JsonNode config, String endingCode) { return configuration().findEnding(config, endingCode); }

    private Map<String, Object> publicEnding(JsonNode ending) { return configuration().publicEnding(ending); }

    private Map<String, Object> stationView(GameStationRun station, JsonNode config) { return configuration().stationView(station, config); }

    private GameSessionView baseView(GameSession session, Long activityId, String perspective, String status,
                                     Integer revision, Long currentChapterId) {
        GameSessionView view = new GameSessionView();
        view.setSessionId(session == null ? null : session.getId());
        view.setActivityId(activityId);
        view.setPerspective(perspective);
        view.setStatus(status);
        view.setRevision(revision);
        view.setAuthorityEpoch(isSharedRoute(session) ? runEpoch(session) : null);
        view.setCurrentChapterId(currentChapterId);
        view.setSnapshotAt(formatDate(clock.now()));
        return view;
    }

    private List<String> playerActions(GameSession session, GameTeamRoleAssignment assignment, JsonNode config) {
        List<String> actions = new ArrayList<>();
        if (assignment != null && "ASSIGNED".equals(assignment.getStatus())
                && (GameSession.PREPARING.equals(session.getStatus()) || GameSession.READY.equals(session.getStatus()))) {
            actions.add("CONFIRM_ROLE");
        }
        if (GameSession.RUNNING.equals(session.getStatus()) && isConfirmedRole(assignment)) {
            if (hasUnlockedChoices(config, session.getCurrentChapterId())) actions.add("PLAYER_CHOICE");
            actions.add("PLAYER_SUBMIT");
            actions.add("PLAYER_HINT");
            actions.add("PLAYER_REVEAL");
        }
        return actions;
    }

    private List<String> merchantActions(GameSession session, List<GameStationRun> stations) {
        Set<String> actions = new HashSet<>();
        for (GameStationRun station : stations) {
            if (GameStationRun.INVITED.equals(station.getStatus())) {
                actions.add("STATION_ACCEPT");
                actions.add("STATION_DECLINE");
            }
            if (GameStationRun.ACCEPTED.equals(station.getStatus())) actions.add("STATION_READY");
            if (GameStationRun.ACTIVE.equals(station.getStatus())) {
                actions.add("STATION_PAUSE");
                actions.add("VERIFY_SUBMISSION");
            }
            if (GameStationRun.PAUSED.equals(station.getStatus())) actions.add("STATION_RESUME");
        }
        return new ArrayList<>(actions);
    }

    /**
     * CU-C-76(2026-09-24 走查):「定向广播」不能点的时候,要说清是哪一种不能。
     *
     * <p>空串=可发送;NOT_RUNNING=本场还没跑起来;NO_RECIPIENT=跑起来了但一个可送达玩家都没有。
     * 可送达的判据与 {@code insertBroadcastDeliveries} 的 ALL 分支同一条:本活动的有效队伍里有已付款队员,
     * 所以直接复用 countActiveTeams,不再前端自己猜。
     *
     * <p>这只是**说明位**,不是权限闸:真正的闸在 {@code broadcast()} 的
     * {@code requireSessionStatus} 与 insertBroadcastDeliveries 返回值上,这里返什么都不放行任何人。
     * 判"没跑起来"用 RUNNING 而不是服务端允许的 READY∪RUNNING,是因为 BROADCAST 这个能力位
     * 本来就只在 RUNNING 下给出({@link #clubActions}),两者必须说同一句话;
     * READY 态下入口同样不可点,文案落在「本场还没跑起来」这一档也是实话。
     */
    private String broadcastBlocker(GameSession session, boolean hasAudience) {
        if (!GameSession.RUNNING.equals(session.getStatus())) {
            return "NOT_RUNNING";
        }
        return hasAudience ? "" : "NO_RECIPIENT";
    }

    private List<String> clubActions(GameSession session) {
        List<String> actions = new ArrayList<>();
        if (isRouteSession(session)) {
            if (GameSession.PREPARING.equals(session.getStatus()) || GameSession.READY.equals(session.getStatus())
                    || GameSession.RUNNING.equals(session.getStatus())) actions.add("SET_LEADERBOARD_VISIBILITY");
            if ((GameSession.PREPARING.equals(session.getStatus()) || GameSession.READY.equals(session.getStatus()))
                    && GameSession.READY.equals(readinessStatus(session))) actions.add("START");
            if (GameSession.RUNNING.equals(session.getStatus())) {
                actions.add("BROADCAST");
                actions.add("FINISH");
                actions.add("CLUB_STATION_PAUSE");
                actions.add("CLUB_STATION_RESUME");
            }
            return actions;
        }
        if (GameSession.PREPARING.equals(session.getStatus()) || GameSession.READY.equals(session.getStatus())) {
            actions.add("ASSIGN_ROLES");
            actions.add("SET_LEADERBOARD_VISIBILITY");
        }
        if (GameSession.READY.equals(session.getStatus())) actions.add("START");
        if (GameSession.RUNNING.equals(session.getStatus())) {
            actions.add("UNLOCK_CHAPTER");
            actions.add("BROADCAST");
            actions.add("TAKEOVER_ROLE");
            actions.add("SET_LEADERBOARD_VISIBILITY");
            actions.add("FINISH");
            // 现场事件(D8):活动跑起来之后才谈得上暂停站点或打回提交
            actions.add("CLUB_STATION_PAUSE");
            actions.add("CLUB_STATION_RESUME");
            actions.add("CLUB_REJECT_SUBMISSION");
        }
        return actions;
    }

    private int countStationStatus(List<Map<String, Object>> stations, String... statuses) {
        int count = 0;
        Set<String> accepted = new HashSet<>(Arrays.asList(statuses));
        for (Map<String, Object> station : stations) if (accepted.contains(station.get("status"))) count++;
        return count;
    }

    private void authorizeSharedReceipt(GameActorContext caller, GameCommandReceipt receipt) {
        GameSession session = runtimeMapper.selectSessionByActivity(receipt.getActivityId());
        if (!isSharedRoute(session)) return;
        CmsActivity activity = requireActivity(receipt.getActivityId(), false);
        GameActorContext actor = new GameActorContext(caller.getMemberId(), receipt.getActorPerspective());
        if ("PLAYER".equals(actor.getPerspective())) {
            PlayerScope current = requirePlayer(actor, activity);
            JsonNode result = readObject(receipt.getResultJson());
            long originalTeam = result.path("receiptOwnerTeamId").asLong(result.path("teamId").asLong());
            if (originalTeam > 0 && originalTeam != current.teamId) throw error("GAME_FORBIDDEN_SCOPE", "该回执属于原队伍的私有状态");
            if (originalTeam <= 0 && "APPLIED".equals(receipt.getOutcome())
                    && !Objects.equals(actor.getMemberId(), receipt.getActorMemberId())) {
                throw error("GAME_FORBIDDEN_SCOPE", "旧回执无法确认由本人操作，不能读取私有结果");
            }
        } else if ("CLUB".equals(actor.getPerspective())) {
            if (RUN_CONTROL_ACTIONS.contains(receipt.getAction())) requireRunStaff(actor, activity, "STAFF_MANUAL_SIGN".equals(receipt.getAction()) ? "ARRIVAL_CONFIRM" : receipt.getAction());
            else requireClubOwner(actor, activity);
        } else if ("MERCHANT".equals(actor.getPerspective())) {
            if (!"MERCHANT".equals(caller.getPerspective())) throw error("GAME_FORBIDDEN_SCOPE", "请使用当前商家身份读取回执");
            authorizeView(caller, activity, session);
        } else throw error("GAME_FORBIDDEN_SCOPE", "当前账号不能查看此共享回执");
    }

    private GameCommandReceipt receiptClaim(GameActorContext actor, GameSessionCommand command,
                                            String requestHash, GameSession session) {
        GameCommandReceipt receipt = new GameCommandReceipt();
        receipt.setGameSessionId(session == null ? null : session.getId());
        receipt.setActivityId(command.getActivityId());
        receipt.setNodeId(command.getNodeId());
        receipt.setActorMemberId(actor.getMemberId());
        receipt.setActorPerspective(actor.getPerspective());
        receipt.setRequestId(command.getRequestId());
        receipt.setRequestHash(requestHash);
        receipt.setAction(command.getAction());
        receipt.setOutcome("PENDING");
        receipt.setRevision(command.getExpectedRevision());
        receipt.setCreateTime(clock.now());
        receipt.setUpdateTime(clock.now());
        return receipt;
    }

    private GameCommandReceiptView replay(GameCommandReceipt receipt, String requestHash) {
        if (!requestHash.equals(receipt.getRequestHash())) {
            throw error("GAME_IDEMPOTENCY_CONFLICT", "同一请求编号对应了不同命令");
        }
        return toReceiptView(receipt, true);
    }

    private GameCommandReceiptView toReceiptView(GameCommandReceipt receipt, boolean replayed) {
        GameCommandReceiptView view = new GameCommandReceiptView();
        view.setReceiptId(receipt.getId());
        view.setActivityId(receipt.getActivityId());
        view.setRequestId(receipt.getRequestId());
        view.setAction(receipt.getAction());
        view.setOutcome(receipt.getOutcome());
        view.setReasonCode(receipt.getReasonCode());
        view.setRevision(receipt.getRevision());
        view.setReplayed(replayed);
        view.setResult(objectMapper.convertValue(GameplayCooperationEngine.publicResult(
                objectMapper.valueToTree(readMap(receipt.getResultJson()))), Map.class));
        return view;
    }

    private String requestHash(GameSessionCommand command) { return sharedRunControl().requestHash(command); }

    private String jsonDigest(JsonNode node) { return sharedRunControl().jsonDigest(node); }

    private String canonicalJson(JsonNode node) throws Exception { return sharedRunControl().canonicalJson(node); }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value == null ? Collections.emptyMap() : value); }
        catch (Exception ex) { throw error("GAME_CONFIG_INVALID", "游戏状态无法序列化"); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        if (StringUtils.isBlank(json)) return new LinkedHashMap<>();
        try { return objectMapper.readValue(json, LinkedHashMap.class); }
        catch (Exception ex) { return new LinkedHashMap<>(); }
    }

    private ObjectNode readObject(String json) {
        try {
            JsonNode value = StringUtils.isBlank(json) ? objectMapper.createObjectNode() : objectMapper.readTree(json);
            return value instanceof ObjectNode ? (ObjectNode) value : objectMapper.createObjectNode();
        } catch (Exception ex) {
            throw error("GAME_CONFIG_INVALID", "剧情变量不可用");
        }
    }

    private ArrayNode readArray(String json) {
        try {
            JsonNode value = StringUtils.isBlank(json) ? objectMapper.createArrayNode() : objectMapper.readTree(json);
            return value instanceof ArrayNode ? (ArrayNode) value : objectMapper.createArrayNode();
        } catch (Exception ex) {
            throw error("GAME_CONFIG_INVALID", "剧情行动记录不可用");
        }
    }

    private boolean containsText(JsonNode array, String value) {
        for (JsonNode item : array) if (value.equals(item.asText())) return true;
        return false;
    }

    private Object scalar(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (value.isBoolean()) return value.asBoolean();
        if (value.isIntegralNumber()) return value.asLong();
        if (value.isFloatingPointNumber()) return value.asDouble();
        return value.asText();
    }

    private String upper(Object value) {
        return value == null ? null : String.valueOf(value).trim().toUpperCase();
    }

    private String text(Object value, int max) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        if (text.length() > max) throw error("GAME_BAD_REQUEST", "文本内容过长");
        return text;
    }

    private void validateEvidence(String inputType, Object rawEvidence) {
        if (!TASK_INPUT_TYPES.contains(inputType) || !(rawEvidence instanceof List)) {
            throw error("GAME_BAD_REQUEST", "任务证据类型不正确");
        }
        List<?> evidence = (List<?>) rawEvidence;
        if (evidence.isEmpty() || evidence.size() > 6) {
            throw error("GAME_BAD_REQUEST", "每次必须提交 1 至 6 项任务证据");
        }
        for (Object item : evidence) {
            if (!(item instanceof String)) throw error("GAME_BAD_REQUEST", "任务证据必须是字符串");
            String value = (String) item;
            if (!value.equals(value.trim()) || value.length() > 512 || !validEvidenceValue(inputType, value)) {
                throw error("GAME_BAD_REQUEST", "任务证据与配置的输入类型不匹配");
            }
        }
    }

    /**
     * R9-39:照片留档即完成,所以首次记录前每张都必须是当前玩家经现行上传链上传、真实存在的对象。
     * 伪造/不存在/他人上传 → 拒绝;存储不可用 → 可重试错误。二者都在任何写入之前抛出,事务回滚不留痕。
     */
    private void requirePhotoEvidenceUploaded(List<?> evidenceUrls, Long memberId) {
        for (Object url : evidenceUrls) {
            try {
                photoEvidenceVerifier.verify((String) url, memberId);
            } catch (IllegalArgumentException invalid) {
                throw error("GAME_TASK_EVIDENCE_INVALID", "照片未上传成功或不属于当前账号，请重新拍摄上传");
            } catch (IllegalStateException storageUnavailable) {
                log.error("照片证据上传事实校验暂不可用", storageUnavailable);
                throw error("GAME_EVIDENCE_STORAGE_UNAVAILABLE", "照片校验暂不可用，请稍后重试");
            }
        }
    }

    private boolean validEvidenceValue(String inputType, String value) {
        if ("TEXT".equals(inputType) || "SCAN".equals(inputType)) {
            String prefix = "TEXT".equals(inputType) ? "text:" : "scan:";
            if (!value.startsWith(prefix)) return false;
            String decoded = decodeCanonicalUriComponent(value.substring(prefix.length()));
            int maxDecodedLength = "TEXT".equals(inputType) ? 300 : 256;
            if (decoded == null || StringUtils.isBlank(decoded) || decoded.length() > maxDecodedLength) return false;
            for (int i = 0; i < decoded.length(); i++) {
                if (Character.isISOControl(decoded.charAt(i))) return false;
            }
            return true;
        }
        if (!"PHOTO".equals(inputType)) return false;
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || StringUtils.isBlank(uri.getHost())
                    || uri.getUserInfo() != null || uri.getFragment() != null) return false;
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            return path.endsWith(".jpg") || path.endsWith(".jpeg")
                    || path.endsWith(".png") || path.endsWith(".webp");
        } catch (Exception ex) {
            return false;
        }
    }

    private String decodeCanonicalUriComponent(String encoded) {
        if (StringUtils.isEmpty(encoded)) return null;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(encoded.length());
        for (int i = 0; i < encoded.length();) {
            char value = encoded.charAt(i);
            if (value == '%') {
                if (i + 2 >= encoded.length()) return null;
                char highChar = encoded.charAt(i + 1);
                char lowChar = encoded.charAt(i + 2);
                if (!isUpperHex(highChar) || !isUpperHex(lowChar)) return null;
                int high = Character.digit(highChar, 16);
                int low = Character.digit(lowChar, 16);
                bytes.write((high << 4) | low);
                i += 3;
                continue;
            }
            if (!isUriComponentSafe(value)) return null;
            bytes.write((byte) value);
            i++;
        }
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            return encoded.equals(encodeUriComponent(decoded)) ? decoded : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private String encodeUriComponent(String decoded) {
        StringBuilder encoded = new StringBuilder();
        for (byte value : decoded.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = value & 0xff;
            if (unsigned < 128 && isUriComponentSafe((char) unsigned)) {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(Character.forDigit((unsigned >>> 4) & 0xf, 16)));
                encoded.append(Character.toUpperCase(Character.forDigit(unsigned & 0xf, 16)));
            }
        }
        return encoded.toString();
    }

    private boolean isUpperHex(char value) {
        return (value >= '0' && value <= '9') || (value >= 'A' && value <= 'F');
    }

    private boolean isUriComponentSafe(char value) {
        return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z')
                || (value >= '0' && value <= '9') || "-_.!~*'()".indexOf(value) >= 0;
    }

    private void verifyAutomaticEvidence(JsonNode taskConfig, String inputType, List<?> evidence) {
        String expectedHash = taskConfig.path("expectedHash").asText();
        if ("PHOTO".equals(inputType) || !expectedHash.matches("[0-9a-f]{64}") || evidence.size() != 1) {
            throw error("GAME_CONFIG_INVALID", "自动核验任务配置不可用");
        }
        String encoded = (String) evidence.get(0);
        String payload = decodeCanonicalUriComponent(encoded.substring(encoded.indexOf(':') + 1));
        if (payload == null) throw error("GAME_TASK_EVIDENCE_INVALID", "任务答案未通过自动核验");
        try {
            byte[] expected = decodeHex(expectedHash);
            byte[] actual = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(expected, actual)) {
                throw error("GAME_TASK_EVIDENCE_INVALID", "任务答案未通过自动核验");
            }
        } catch (GameSessionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw error("GAME_CONFIG_INVALID", "自动核验任务配置不可用");
        }
    }

    private byte[] decodeHex(String value) {
        byte[] decoded = new byte[value.length() / 2];
        for (int i = 0; i < value.length(); i += 2) {
            int high = Character.digit(value.charAt(i), 16);
            int low = Character.digit(value.charAt(i + 1), 16);
            if (high < 0 || low < 0) throw error("GAME_CONFIG_INVALID", "自动核验摘要格式不正确");
            decoded[i / 2] = (byte) ((high << 4) | low);
        }
        return decoded;
    }

    private String formatDate(Date value) {
        return value == null ? null : DateUtils.parseDateToStr("yyyy-MM-dd HH:mm:ss", value);
    }

    private Long positiveLong(Object value) {
        Long parsed = Convert.toLong(value, 0L);
        return parsed > 0 ? parsed : null;
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? Collections.emptyList() : values;
    }

    private Map<String, Object> singleton(String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(key, value);
        return result;
    }

    private ActionResult result(GameSession session, Map<String, Object> data) {
        return new ActionResult(session, session.getRevision() == null ? 0 : session.getRevision(), data);
    }

    private void checkCommandText(Long memberId, String content, String fallbackMessage) {
        try {
            imService.checkUserGeneratedText(memberId, content);
        } catch (RuntimeException ex) {
            String message = ex.getMessage();
            String code = IWxContentSecurityService.MSG_BUSY.equals(message)
                    || IWxContentSecurityService.MSG_UNAVAILABLE.equals(message)
                    ? "GAME_CONTENT_UNAVAILABLE"
                    : IWxContentSecurityService.REVIEW_REQUIRED.equals(message)
                    ? "GAME_CONTENT_REVIEW_REQUIRED" : "GAME_CONTENT_UNSAFE";
            throw error(code, StringUtils.isBlank(message) ? fallbackMessage : message);
        }
    }

    private GameSessionException error(String code, String message) {
        return new GameSessionException(code, message);
    }

    private static final class PlayerScope {
        private final Long teamId;
        private PlayerScope(Long teamId) { this.teamId = teamId; }
    }

    private static final class FallbackPlan {
        private final String planCode;
        private final Integer version;
        private final String playerMessage;
        private final GameStationRun target;
        private FallbackPlan(String planCode, Integer version, String playerMessage, GameStationRun target) {
            this.planCode = planCode;
            this.version = version;
            this.playerMessage = playerMessage;
            this.target = target;
        }
    }

    private static final class ActionResult {
        private final GameSession session;
        private final Integer revision;
        private final Map<String, Object> result;
        private ActionResult(GameSession session, Integer revision, Map<String, Object> result) {
            this.session = session;
            this.revision = revision;
            this.result = result;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public com.chengyinhub.business.domain.route.TopicRouteState querySharedRouteState(Long memberId, Long topicId, Long activityId) {
        if (activityId == null || activityId <= 0) return null;
        GameSession session = runtimeMapper.selectSessionByActivity(activityId);
        if (!isSharedRoute(session)) return null;
        if (!Objects.equals(topicId, session.getTopicId())) throw error("GAME_FORBIDDEN_SCOPE", "活动与主题不匹配");
        CmsActivity activity = requireActivity(activityId, true);
        PlayerScope player = requirePlayer(GameActorContext.player(memberId), activity);
        if (persistedRunControl(session) == null) throw error("GAME_CONFIG_INVALID", "本局共享运行控制账不可用");
        TopicRouteEngine.State state = routeOwnerState(requireRouteOwner(session, player.teamId));
        return projectSharedRouteState(session, sessionConfig(session), state);
    }

    @Override
    @Transactional(readOnly = true)
    public JsonNode querySharedRouteSnapshot(Long memberId, Long topicId, Long activityId, Long nodeId) {
        if (activityId == null || activityId <= 0) return null;
        GameSession session = runtimeMapper.selectSessionByActivity(activityId);
        if (!isSharedRoute(session)) return null;
        if (!Objects.equals(topicId, session.getTopicId())) throw error("GAME_FORBIDDEN_SCOPE", "活动与主题不匹配");
        CmsActivity activity = requireActivity(activityId, true);
        PlayerScope player = requirePlayer(GameActorContext.player(memberId), activity);
        ObjectNode control = persistedRunControl(session);
        if (control == null) throw error("GAME_CONFIG_INVALID", "本局共享运行控制账不可用");
        int epoch = control.path("authorityEpoch").asInt(1);
        TopicRouteEngine.State state = routeOwnerState(requireRouteOwner(session, player.teamId));
        ObjectNode original = (ObjectNode) sessionConfig(session).deepCopy();
        NodeJourneySnapshot.requireContentSnapshot(original);
        ObjectNode snapshot = original.deepCopy();
        snapshot.put("gameSessionId", session.getId()).put("teamId", player.teamId).put("authorityEpoch", epoch);
        snapshot.set("contentTags", objectMapper.valueToTree(state.getTags()));
        JsonNode operation = state.getThemeRuntime().path("activeOperation");
        java.util.Set<Long> targets = new java.util.LinkedHashSet<>();
        if (nodeId != null) {
            if (nodeId <= 0) throw error("GAME_BAD_REQUEST", "节点身份不可用");
            targets.add(nodeId);
        } else {
            // One request captures RUN, TEAM and the original content once, not one run per node.
            for (JsonNode quest : routeGameplay(session).path("quests"))
                targets.add(control.path("bindings").path(quest.path("locationKey").asText())
                        .path("nodeId").asLong(quest.path("nodeId").asLong()));
            if (operation.isObject()) targets.add(operation.path("nodeId").asLong());
        }
        for (Long target : targets) {
            if (activeChallenge(session, state, target)) continue;
            Long source = null;
            if (operation.isObject() && operation.path("nodeId").asLong() == target) {
                if (operation.has("epoch") && operation.path("epoch").asInt() != epoch)
                    throw error("GAME_STATE_CONFLICT", "原操作权威版本已变更");
                source = operation.path("sourceNodeId").asLong();
            } else {
                String active = state.getThemeRuntime().path("gameplay").path("activeQuest").asText("");
                java.util.Set<Long> candidates = new java.util.LinkedHashSet<>();
                for (JsonNode quest : routeGameplay(session).path("quests")) {
                    long actual = control.path("bindings").path(quest.path("locationKey").asText())
                            .path("nodeId").asLong(quest.path("nodeId").asLong());
                    if (actual != target) continue;
                    candidates.add(quest.path("nodeId").asLong());
                    if (quest.path("questKey").asText().equals(active)) source = quest.path("nodeId").asLong();
                }
                if (source == null && candidates.size() == 1) source = candidates.iterator().next();
                if (source == null && candidates.size() > 1) {
                    if (nodeId != null) throw error("GAME_ROUTE_LOCKED", "本站内容需要原任务身份");
                    // Whole-page read omits an ambiguous physical binding; it cannot choose a future source.
                    for (String field : Arrays.asList("nodeConfigs", "nodeAnswers", "nodeJourney"))
                        snapshot.with(field).remove(String.valueOf(target));
                    continue;
                }
            }
            if (source != null && !Objects.equals(source, target)) {
                if (source <= 0 || runtimeMapper.selectStation(session.getId(), target) == null)
                    throw error("GAME_FORBIDDEN_SCOPE", "内容映射不属于本场站点");
                for (String field : Arrays.asList("nodeConfigs", "nodeAnswers", "nodeJourney")) {
                    JsonNode block = original.path(field).get(String.valueOf(source));
                    if (block == null) snapshot.with(field).remove(String.valueOf(target));
                    else snapshot.with(field).set(String.valueOf(target), block.deepCopy());
                }
            }
            JsonNode content = snapshot.path("nodeAnswers").get(String.valueOf(target));
            if (NodeJourneySnapshot.hasContent(content)
                    && (content.path("nodePresentation").path("topicId").asLong() != topicId
                    || content.path("nodePresentation").path("id").asLong() != (source == null ? target : source)))
                throw error("GAME_CONFIG_INVALID", "本局内容归属不可用");
        }
        return snapshot;
    }

    private com.chengyinhub.business.domain.route.TopicRouteState projectSharedRouteState(GameSession session,
            JsonNode snapshot, TopicRouteEngine.State state) {
        com.chengyinhub.business.domain.route.TopicRouteState view = new com.chengyinhub.business.domain.route.TopicRouteState();
        view.setSessionId(session.getId()); view.setVersion(session.getRevision());
        view.setRouteMode(snapshot.path("routeMode").asText("LINEAR")); view.setStatus(state.getStatus());
        view.setCurrentNodeId(state.getCurrentNodeId()); view.setRecommendedNodeId(state.getRecommendedNodeId());
        view.setNodeStates(state.getNodeStates()); view.setAttributes(JourneyInitialState.playerView(routeRules(session), state));
        return view;
    }

    private ObjectNode persistedRunControl(GameSession session) {
        GameStoryState owner = runOwner(session);
        return owner == null ? null : readObject(owner.getVariablesJson());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isOriginalSharedRouteEligible(Long memberId, Long topicId, Long activityId, Long nodeId,
                                                 Long sessionId, String runtimeInstance, Integer authorityEpoch) {
        return originalSharedRouteEligible(false, memberId, topicId, activityId, nodeId, sessionId, runtimeInstance, authorityEpoch);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public boolean isOriginalSharedRouteEligibleForUpdate(Long memberId, Long topicId, Long activityId, Long nodeId,
                                                          Long sessionId, String runtimeInstance, Integer authorityEpoch) {
        return originalSharedRouteEligible(true, memberId, topicId, activityId, nodeId, sessionId, runtimeInstance, authorityEpoch);
    }

    /** Guard/audit port boundary: game reason codes become business ServiceException, not unknown 500s. */
    private boolean originalSharedRouteEligible(boolean lock, Long memberId, Long topicId, Long activityId, Long nodeId,
                                                Long sessionId, String runtimeInstance, Integer authorityEpoch) {
        try {
            requireOriginalSharedIdentity(memberId, topicId, activityId, nodeId, sessionId, runtimeInstance, authorityEpoch);
            GameSession session = lock ? runtimeMapper.selectSessionByActivityForUpdate(activityId)
                    : runtimeMapper.selectSessionByActivity(activityId);
            return originalSharedRouteEligible(session, memberId, topicId, activityId, nodeId, sessionId, runtimeInstance, authorityEpoch);
        } catch (GameSessionException denied) {
            throw new ServiceException(denied.getMessage(), 409).setData(singleton("reasonCode", denied.getErrorCode()));
        }
    }

    private void requireOriginalSharedIdentity(Long memberId, Long topicId, Long activityId, Long nodeId,
                                               Long sessionId, String instance, Integer epoch) {
        if (memberId == null || memberId <= 0 || topicId == null || topicId <= 0 || activityId == null || activityId <= 0
                || nodeId == null || nodeId <= 0 || sessionId == null || sessionId <= 0
                || instance == null || instance.isBlank() || epoch == null || epoch < 0) {
            throw error("GAME_BAD_REQUEST", "原共享局身份不完整");
        }
    }

    /** No refresh, initialization, persistence, manual evaluation override or team FOR UPDATE. */
    private boolean originalSharedRouteEligible(GameSession session, Long memberId, Long topicId, Long activityId,
                                                Long nodeId, Long sessionId, String instance, Integer epoch) {
        if (session == null || !Objects.equals(sessionId, session.getId()) || !Objects.equals(activityId, session.getActivityId())
                || !Objects.equals(topicId, session.getTopicId()) || !isSharedRoute(session)
                || !GameSession.RUNNING.equals(session.getStatus())) return false;
        ObjectNode run = persistedRunControl(session);
        if (run == null) throw error("GAME_CONFIG_INVALID", "本局共享运行控制账不可用");
        if (epoch != run.path("authorityEpoch").asInt(1) || !"RUNNING".equals(run.path("operationStatus").asText())
                || !"ONLINE".equals(run.path("authorityMode").asText())) return false;
        CmsActivity activity = requireActivity(activityId, true);
        if (!Objects.equals(topicId, activity.getTopicId())) return false;
        PlayerScope player = requirePlayer(GameActorContext.player(memberId), activity);
        if (run.path("exitedTeams").has(String.valueOf(player.teamId))) return false;
        TopicRouteEngine.State state = routeOwnerState(requireRouteOwner(session, player.teamId));
        if (naturallyCompleted(session, state) || !instance.equals(sharedInstance(session, state, nodeId))) return false;
        boolean challenge = activeChallenge(session, state, nodeId);
        if (!challenge) requirePlayableStation(activity, session, nodeId);
        boolean configured = challenge || state.getThemeRuntime().path("activeOperation").path("nodeId").asLong() == nodeId;
        for (JsonNode quest : routeGameplay(session).path("quests")) {
            if (configured) break;
            if (bindingNode(session, quest) == nodeId && (state.getThemeRuntime().path("gameplay").path("activeQuest").asText("").isEmpty()
                    || quest.path("questKey").asText().equals(state.getThemeRuntime().path("gameplay").path("activeQuest").asText()))) {
                requireEligibleQuest(session, state, quest); configured = true; break;
            }
        }
        if (!configured && (routeGameplay(session).path("quests").size() > 0 || !Objects.equals(nodeId, state.getCurrentNodeId()))) return false;
        if ("WORLD_SHARED".equals(GameplayRuntimeConfig.getWorldMode(sessionConfig(session).path("themeConfig")))) {
            CooperationScope scope = cooperationScope(session, false);
            if (scope.world.getThemeRuntime().path("cooperation").has("result")) return false;
            try {
                if (!challenge && !isFinalOperation(state, nodeId) && cooperationEngine.isVotingFrozen(scope.world)) return false;
                cooperationEngine.requireAvailable(scope.world, player.teamId, state, objectMapper.createArrayNode());
            } catch (IllegalArgumentException | IllegalStateException unavailable) {
                throw error("GAME_RESOURCE_RESERVED", unavailable.getMessage());
            }
        }
        return true;
    }
}
