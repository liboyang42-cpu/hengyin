package com.chengyinhub.business.service.impl;

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
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.TopicRouteEngine;
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

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
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
import java.util.Set;
import java.util.TreeMap;

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
    private static final Set<String> PLAYER_ACTIONS = new HashSet<>(Arrays.asList(
            "CONFIRM_ROLE", "PLAYER_CHOICE", "PLAYER_SUBMIT", "PLAYER_HINT", "PLAYER_REVEAL"));
    private static final Logger log = LoggerFactory.getLogger(GameSessionRuntimeServiceImpl.class);
    private static final Set<String> TASK_INPUT_TYPES = new HashSet<>(Arrays.asList("TEXT", "SCAN", "PHOTO"));
    /**
     * 剧情规则数组写法(与路线图同一套)的条数上限与条件 op。故事配置的变量名与 tag 不受
     * 块配置的命名空间约束 —— 老主题的 trust / evidence 等变量必须在数组写法下继续可用。
     */
    private static final int STORY_RULE_MAX = 16;
    private static final Set<String> STORY_CONDITION_OPS = new HashSet<>(Arrays.asList(
            "EQ", "NE", "GT", "GTE", "LT", "LTE"));
    /**
     * ADD_TAG 的 tag 集合寄存键。game_story_state 只有 variables_json 一个自由状态列(没有 tag 列),
     * 不改表结构的前提下只能借用它;用 __ 前缀的保留键,旧对象写法永远不读写这个键,行为零变化。
     */
    private static final String STORY_TAGS_KEY = "__tags";

    @Autowired
    private GameSessionRuntimeMapper runtimeMapper;
    @Autowired
    private CmsActivityMapper activityMapper;
    @Autowired
    private CmsTopicMapper topicMapper;
    @Autowired
    private IClubMemberService clubMemberService;
    @Autowired
    private PlayTeamMapper playTeamMapper;

    @Autowired
    private com.chengyinhub.business.mapper.CmsRegistrationMapper currentPlayRegistrationMapper;
    @Autowired
    private IPlayerNodeProgressService playerNodeProgressService;
    @Autowired
    private IImService imService;

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

    @Override
    public boolean isGameModuleConfigEnabled(String configJson) {
        try {
            validateModuleConfig(configJson);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

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
        if (!GameSession.RUNNING.equals(session.getStatus())) {
            throw new IllegalStateException("本场带队尚未开局或已结束");
        }
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
        if ("PLAYER".equals(actor.getPerspective())) return playerView(actor, activity, session);
        throw error("GAME_FORBIDDEN_SCOPE", "无权查看该游戏视图");
    }

    @Override
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

        GameCommandReceipt existing = runtimeMapper.selectReceipt(command.getActivityId(), actor.getMemberId(),
                command.getRequestId());
        if (existing != null) return replay(existing, requestHash);

        CmsActivity activity = requireActivity(command.getActivityId(), true);
        GameSession session = runtimeMapper.selectSessionByActivity(command.getActivityId());
        preauthorize(actor, activity, session, command);
        if (!"PREPARE".equals(command.getAction())
                && (session == null || !command.getExpectedRevision().equals(session.getRevision()))) {
            throw error("GAME_STATE_CONFLICT", "客户端版本已过期，请刷新后重试");
        }

        GameCommandReceipt claim = receiptClaim(actor, command, requestHash, session);
        if (runtimeMapper.insertReceiptClaim(claim) != 1) {
            GameCommandReceipt concurrent = runtimeMapper.selectReceiptForUpdate(command.getActivityId(), actor.getMemberId(),
                    command.getRequestId());
            if (concurrent == null) throw error("GAME_STATE_CONFLICT", "命令正在处理中，请稍后读取回执");
            return replay(concurrent, requestHash);
        }

        ActionResult applied = dispatch(actor, activity, session, command, claim.getId());
        claim.setGameSessionId(applied.session.getId());
        claim.setOutcome("APPLIED");
        claim.setRevision(applied.revision);
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
        if (receipt != null) return toReceiptView(receipt, true);

        GameCommandReceiptView pending = new GameCommandReceiptView();
        pending.setActivityId(activityId);
        pending.setRequestId(requestId);
        pending.setOutcome("PENDING");
        pending.setReplayed(false);
        return pending;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordFailure(GameActorContext actor, GameSessionCommand command, GameSessionException failure) {
        if (actor == null || actor.getMemberId() == null || actor.getMemberId() <= 0 || command == null
                || command.getActivityId() == null || command.getActivityId() <= 0
                || StringUtils.isBlank(command.getRequestId()) || StringUtils.isBlank(command.getRequestHash())
                || StringUtils.isBlank(command.getAction()) || failure == null) {
            return;
        }
        GameCommandReceipt existing = runtimeMapper.selectReceipt(command.getActivityId(), actor.getMemberId(),
                command.getRequestId());
        if (existing != null) return;
        GameSession session = runtimeMapper.selectSessionByActivity(command.getActivityId());
        GameCommandReceipt failed = receiptClaim(actor, command, command.getRequestHash(), session);
        failed.setOutcome("FAILED");
        failed.setReasonCode(failure.getErrorCode());
        failed.setResultJson(writeJson(singleton("reason", failure.getMessage())));
        runtimeMapper.insertReceiptClaim(failed);
    }

    private ActionResult dispatch(GameActorContext actor, CmsActivity activity, GameSession session,
                                  GameSessionCommand command, Long receiptId) {
        switch (command.getAction()) {
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
        JsonNode config = route ? objectMapper.createObjectNode().put("schemaVersion", 1).put("playMode", "ROUTE")
                : validateModuleConfig(configJson);

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
            GameSession concurrent = runtimeMapper.selectSessionByActivity(activity.getId());
            if (concurrent == null) throw error("GAME_STATE_CONFLICT", "游戏准备失败");
            return result(concurrent, singleton("prepared", false));
        }
        runtimeMapper.insertStationsFromTopic(session.getId(), activity.getId(), activity.getTopicId(), now);
        List<GameStationRun> stations = safe(runtimeMapper.selectStations(session.getId()));
        if (stations.isEmpty()) throw error("GAME_CONFIG_INVALID", "主题没有可运行站点");
        if (route) {
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
        boolean concurrentAssignmentObserved = false;
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
                concurrentAssignmentObserved = true;
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
        } else if (concurrentAssignmentObserved) {
            GameSession current = runtimeMapper.selectSessionByActivity(activity.getId());
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
        if (GameStationRun.PAUSED.equals(station.getStatus())) return result(session, singleton("station", stationView(station, sessionConfig(session))));
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
        if (GameStationRun.PAUSED.equals(station.getStatus())) {
            return result(session, singleton("station", stationView(station, sessionConfig(session))));
        }
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
        if (!GameStationRun.PAUSED.equals(station.getStatus())) throw error("GAME_STATE_CONFLICT", "站点当前不能恢复");
        Date now = clock.now();
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
        if (!GameStationRun.PAUSED.equals(station.getStatus())) throw error("GAME_STATE_CONFLICT", "站点当前不能恢复");
        Date now = clock.now();
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
            GameStoryState concurrent = runtimeMapper.selectStoryState(session.getId(), "TEAM", player.teamId);
            if (concurrent != null && containsText(readArray(concurrent.getAppliedChoicesJson()), choiceId)) {
                GameSession current = runtimeMapper.selectSessionByActivity(activity.getId());
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
            GamePlayerNodeOutcome outcome = outcomeForSubmission(session, station, player.teamId,
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
                GamePlayerNodeOutcome outcome = outcomeForSubmission(session, station, player.teamId,
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
        int failed = runtimeMapper.countBroadcastDeliveries(broadcast.getId(), "FAILED");
        int pending = runtimeMapper.countBroadcastDeliveries(broadcast.getId(), "PENDING");
        String finalStatus = pending > 0 || (reached > 0 && failed > 0) ? "PARTIAL"
                : failed > 0 ? "FAILED" : "SENT";
        if (runtimeMapper.updateBroadcastStatus(broadcast.getId(), "SENDING", finalStatus, clock.now()) != 1) {
            throw error("GAME_STATE_CONFLICT", "广播终态记录失败");
        }
        broadcast.setStatus(finalStatus);
        bumpSession(session, session.getStatus(), session.getCurrentChapterId(), actor.getMemberId(), clock.now());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("broadcastId", broadcast.getId());
        data.put("reachedCount", reached);
        return result(session, data);
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
            Map<String, Object> routePlayer = new LinkedHashMap<>();
            routePlayer.put("playMode", "ROUTE");
            routePlayer.put("teamId", player.teamId);
            routeView.setPlayer(routePlayer);
            routeView.setAvailableActions(Collections.emptyList());
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
        if (playTeamMapper.hasPaidRegistration(actor.getMemberId(), 2, activity.getId()) < 1) {
            throw error("GAME_FORBIDDEN_SCOPE", "仅本场有效报名玩家可参与");
        }
        Long teamId = runtimeMapper.selectActiveTeamId(activity.getId(), actor.getMemberId());
        if (teamId == null || teamId <= 0) throw error("GAME_TEAM_REQUIRED", "请先加入本场队伍");
        return new PlayerScope(teamId);
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
        GameStationRun station = nodeId == null ? null : runtimeMapper.selectStation(session.getId(), nodeId);
        if (station == null) throw error("GAME_FORBIDDEN_SCOPE", "节点不属于本场游戏");
        if (GameStationRun.PAUSED.equals(station.getStatus())) throw error("GAME_STATION_PAUSED", "站点暂停中");
        if (!GameStationRun.ACTIVE.equals(station.getStatus())) throw error("GAME_STATION_NOT_READY", "站点尚未开始服务");
        Date now = clock.now();
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

    private ReadyConfig readyConfig(Map<String, Object> payload, GameSession session, GameStationRun station) {
        Date startAt = DateUtils.parseDate(payload.get("serviceStartAt"));
        Date endAt = DateUtils.parseDate(payload.get("serviceEndAt"));
        if ((startAt == null || endAt == null) && payload.get("serviceWindow") instanceof Map) {
            Map<?, ?> window = (Map<?, ?>) payload.get("serviceWindow");
            startAt = DateUtils.parseDate(window.get("start"));
            endAt = DateUtils.parseDate(window.get("end"));
        }
        Integer capacity = Convert.toInt(payload.get("capacity"), 0);
        Object checklist = payload.get("checklist");
        if (startAt == null || endAt == null || !endAt.after(startAt) || capacity < 1 || capacity > 10000
                || !(checklist instanceof List)) {
            throw error("GAME_STATION_CONFIG_INVALID", "请完整填写服务时间、容量和准备清单");
        }
        List<Map<String, Object>> canonicalChecklist = configuredChecklist(sessionConfig(session), station.getStationCode());
        Map<String, Map<String, Object>> configuredByCode = new LinkedHashMap<>();
        for (Map<String, Object> configured : canonicalChecklist) {
            configuredByCode.put(String.valueOf(configured.get("code")), configured);
        }
        Set<String> checked = new HashSet<>();
        for (Object item : (List<?>) checklist) {
            if (!(item instanceof Map)) throw error("GAME_STATION_CONFIG_INVALID", "准备清单格式不正确");
            Map<?, ?> row = (Map<?, ?>) item;
            String code = row.get("code") == null ? null : String.valueOf(row.get("code")).trim();
            if (StringUtils.isBlank(code) || !configuredByCode.containsKey(code) || !checked.add(code)) {
                throw error("GAME_STATION_CONFIG_INVALID", "准备清单包含未知或重复项目");
            }
            if (!Boolean.TRUE.equals(row.get("checked"))) {
                throw error("GAME_STATION_CONFIG_INVALID", "准备清单尚未全部完成");
            }
        }
        if (checked.size() != configuredByCode.size()) throw error("GAME_STATION_CONFIG_INVALID", "准备清单尚未全部完成");
        for (Map<String, Object> configured : canonicalChecklist) configured.put("checked", true);
        Map<String, Object> preparation = new LinkedHashMap<>();
        preparation.put("checklist", canonicalChecklist);
        preparation.put("note", text(payload.get("note"), 200));
        return new ReadyConfig(startAt, endAt, capacity, preparation);
    }

    private List<Map<String, Object>> configuredChecklist(JsonNode config, String stationCode) {
        JsonNode station = configuredStation(config.path("stations"), stationCode, null);
        if (station == null || !station.path("preparationChecklist").isArray()) return Collections.emptyList();
        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (JsonNode item : station.path("preparationChecklist")) {
            String code = item.path("code").asText();
            String label = item.path("label").asText();
            if (StringUtils.isBlank(code) || StringUtils.isBlank(label) || !codes.add(code)) {
                throw error("GAME_CONFIG_INVALID", "站点准备清单编号和文案必须唯一且非空");
            }
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("code", code);
            canonical.put("label", label);
            items.add(canonical);
        }
        return items;
    }

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

    private JsonNode configuredStation(JsonNode stations, String stationCode, String nodeName) {
        if (stations == null || !stations.isArray()) return null;
        for (JsonNode station : stations) {
            if (stationCode != null && stationCode.equals(station.path("code").asText())) return station;
            if (nodeName != null && nodeName.equals(station.path("nodeName").asText())) return station;
        }
        return null;
    }

    private JsonNode validateModuleConfig(String json) {
        if (StringUtils.isBlank(json) || json.getBytes(StandardCharsets.UTF_8).length > 65536) {
            throw error("GAME_CONFIG_INVALID", "主题未配置可用的游戏模组");
        }
        JsonNode config;
        try { config = objectMapper.readTree(json); }
        catch (Exception ex) { throw error("GAME_CONFIG_INVALID", "游戏模组配置不是有效 JSON"); }
        if (!config.isObject() || config.path("schemaVersion").asInt(-1) != 1
                || !config.path("roles").isArray() || config.path("roles").size() < 1
                || !config.path("stations").isArray() || config.path("stations").size() < 1) {
            throw error("GAME_CONFIG_INVALID", "游戏模组缺少版本、角色或站点");
        }
        Set<String> roles = new HashSet<>();
        for (JsonNode role : config.path("roles")) {
            String code = role.path("code").asText();
            if (StringUtils.isBlank(code) || !roles.add(code)) throw error("GAME_CONFIG_INVALID", "剧情角色编号重复或为空");
        }
        Set<String> stationCodes = new HashSet<>();
        Set<String> stationNames = new HashSet<>();
        for (JsonNode station : config.path("stations")) {
            String code = station.path("code").asText();
            String nodeName = station.path("nodeName").asText();
            if (StringUtils.isBlank(code) || StringUtils.isBlank(nodeName)
                    || !stationCodes.add(code) || !stationNames.add(nodeName)) {
                throw error("GAME_CONFIG_INVALID", "站点编号和主题节点名必须唯一且非空");
            }
            Set<String> checklistCodes = new HashSet<>();
            for (JsonNode item : station.path("preparationChecklist")) {
                String itemCode = item.path("code").asText();
                String label = item.path("label").asText();
                if (StringUtils.isBlank(itemCode) || StringUtils.isBlank(label) || !checklistCodes.add(itemCode)) {
                    throw error("GAME_CONFIG_INVALID", "站点准备清单编号和文案必须唯一且非空");
                }
            }
            JsonNode task = station.path("playerTask");
            String inputType = upper(task.path("inputType").asText());
            String merchantInstruction = station.path("merchantInstruction").asText();
            String hiddenInfoReminder = station.path("hiddenInfoReminder").asText();
            if (StringUtils.isBlank(task.path("taskCode").asText())
                    || StringUtils.isBlank(task.path("prompt").asText())
                    || !TASK_INPUT_TYPES.contains(inputType)
                    || StringUtils.isBlank(merchantInstruction) || merchantInstruction.length() > 200
                    || StringUtils.isBlank(hiddenInfoReminder) || hiddenInfoReminder.length() > 200) {
                throw error("GAME_CONFIG_INVALID", "每个站点都必须配置公开任务类型和安全运营说明");
            }
            boolean verificationRequired = task.path("verificationRequired").asBoolean(false);
            String completionPolicy = upper(task.path("completionPolicy").asText());
            if ("PHOTO".equals(inputType)
                    && (verificationRequired || !"EVIDENCE_ONLY".equals(completionPolicy))) {
                throw error("GAME_CONFIG_INVALID", "照片任务仅支持明确的证据留档完成策略");
            }
            if (!"PHOTO".equals(inputType) && !verificationRequired
                    && !task.path("expectedHash").asText().matches("[0-9a-f]{64}")) {
                throw error("GAME_CONFIG_INVALID", "自动核验仅支持带 expectedHash 的文字或扫码任务");
            }
            if (task.has("hintPolicy")) validateHintPolicy(task.path("hintPolicy"));
        }
        if (config.has("fallbackPlans") && !config.path("fallbackPlans").isArray()) {
            throw error("GAME_CONFIG_INVALID", "备用方案配置格式不正确");
        }
        Set<String> fallbackPlanKeys = new HashSet<>();
        for (JsonNode plan : config.path("fallbackPlans")) {
            String planCode = plan.path("planCode").asText();
            int version = plan.path("version").asInt(0);
            String sourceCode = plan.path("sourceStationCode").asText();
            String targetCode = plan.path("targetStationCode").asText();
            String message = plan.path("playerMessage").asText();
            if (!planCode.matches("[A-Z][A-Z0-9_]{0,63}") || version <= 0
                    || !fallbackPlanKeys.add(planCode + "#" + version)
                    || !"APPROVED".equals(plan.path("status").asText())
                    || !stationCodes.contains(sourceCode) || !stationCodes.contains(targetCode)
                    || sourceCode.equals(targetCode) || StringUtils.isBlank(message) || message.length() > 200) {
                throw error("GAME_CONFIG_INVALID", "备用方案必须是唯一、有效且已批准的站点映射");
            }
        }
        Set<Long> chapterIds = new HashSet<>();
        for (JsonNode chapter : config.path("chapters")) {
            long chapterId = chapter.path("chapterId").asLong(0);
            if (chapterId <= 0 || !chapterIds.add(chapterId)) {
                throw error("GAME_CONFIG_INVALID", "剧情章节编号必须唯一且为正数");
            }
        }
        Set<String> choiceIds = new HashSet<>();
        for (JsonNode choice : config.path("story").path("choices")) {
            String choiceId = choice.path("id").asText();
            String stationCode = choice.path("stationCode").asText();
            String choiceGroup = choice.path("choiceGroup").asText();
            long chapterId = choice.path("chapterId").asLong(0);
            if (StringUtils.isBlank(choiceId) || !choiceIds.add(choiceId)
                    || (StringUtils.isNotBlank(stationCode) && !stationCodes.contains(stationCode))
                    || (choice.has("chapterId") && !chapterIds.contains(chapterId))
                    || (StringUtils.isNotBlank(choiceGroup)
                        && !choiceGroup.matches("[A-Z][A-Z0-9_]{0,63}"))) {
                throw error("GAME_CONFIG_INVALID", "剧情行动编号必须唯一且站点必须有效");
            }
            JsonNode allowedRoles = choice.path("allowedRoles");
            if (choice.has("allowedRoles") && !allowedRoles.isArray()) {
                throw error("GAME_CONFIG_INVALID", "剧情行动角色范围格式不正确");
            }
            for (JsonNode allowedRole : allowedRoles) {
                if (!allowedRole.isTextual() || !roles.contains(allowedRole.asText())) {
                    throw error("GAME_CONFIG_INVALID", "剧情行动包含无效角色");
                }
            }
            validateStoryEffects(choice.path("effects"));
        }
        for (JsonNode ending : config.path("story").path("endings")) {
            validateStoryConditions(ending);
        }
        return config;
    }

    /**
     * 剧情行动效果:数组 = 路线图写法(SET / INC / ADD_TAG),按契约校验 op、整数范围与条数;
     * 对象 = 旧写法,原样放行。声明不合法时在准备阶段就拒绝,不落任何一局。
     */
    private void validateStoryEffects(JsonNode effects) {
        if (effects == null || effects.isNull() || !effects.isArray()) return;
        requireStoryRuleCount(effects, "剧情行动效果");
        for (JsonNode effect : effects) {
            if (!effect.isObject()) throw error("GAME_CONFIG_INVALID", "剧情行动效果条目格式不正确");
            String op = effect.path("op").asText();
            if ("ADD_TAG".equals(op)) {
                requireStoryTag(effect.path("value"), "剧情行动效果");
                continue;
            }
            if (!"SET".equals(op) && !"INC".equals(op)) {
                throw error("GAME_CONFIG_INVALID", "剧情行动效果的 op 只能是 SET、INC 或 ADD_TAG");
            }
            if (StringUtils.isBlank(effect.path("var").asText()) || !isStoryRuleValue(effect.path("value"))) {
                throw error("GAME_CONFIG_INVALID", "剧情行动效果必须带变量名和 -99 到 99 的整数");
            }
        }
    }

    /**
     * 结局条件:数组 = 路线图写法,按契约校验 op、整数范围与条数;对象 = 旧写法,原样放行。
     * 数组为空表示无条件通过,只有 fallback 结局允许,其余明说条件却不写条件的结局一律拒绝,
     * 避免留空静默命中第一个结局。NODE_COMPLETED 需要局内会话才能核实「本队已完成站点」,
     * 配置校验期取不到,故这里直接拒绝,不让一条无法核实的规则带进快照。
     */
    private void validateStoryConditions(JsonNode ending) {
        JsonNode conditions = ending.path("conditions");
        if (conditions == null || conditions.isNull() || !conditions.isArray()) return;
        if (conditions.size() == 0 && !ending.path("fallback").asBoolean(false)) {
            throw error("GAME_CONFIG_INVALID", "结局条件不能为空;兜底结局请用 fallback");
        }
        requireStoryRuleCount(conditions, "结局条件");
        for (JsonNode condition : conditions) {
            if (!condition.isObject()) throw error("GAME_CONFIG_INVALID", "结局条件条目格式不正确");
            String op = condition.path("op").asText();
            if ("NODE_COMPLETED".equals(op)) {
                throw error("GAME_CONFIG_INVALID", "结局条件暂不支持 NODE_COMPLETED");
            }
            if ("HAS_TAG".equals(op)) {
                requireStoryTag(condition.path("value"), "结局条件");
                continue;
            }
            if (!STORY_CONDITION_OPS.contains(op)) {
                throw error("GAME_CONFIG_INVALID", "结局条件的 op 只能是 EQ、NE、GT、GTE、LT、LTE 或 HAS_TAG");
            }
            if (StringUtils.isBlank(condition.path("var").asText()) || !isStoryRuleValue(condition.path("value"))) {
                throw error("GAME_CONFIG_INVALID", "结局条件必须带变量名和 -99 到 99 的整数");
            }
        }
    }

    private void requireStoryRuleCount(JsonNode rules, String label) {
        if (rules.size() > STORY_RULE_MAX) {
            throw error("GAME_CONFIG_INVALID", label + "最多配置 " + STORY_RULE_MAX + " 条");
        }
    }

    private void requireStoryTag(JsonNode value, String label) {
        String tag = value.isTextual() ? value.asText() : "";
        if (StringUtils.isBlank(tag)) {
            throw error("GAME_CONFIG_INVALID", label + "的标签必须是非空文本");
        }
    }

    private boolean isStoryRuleValue(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToInt()
                && value.asInt() >= -99 && value.asInt() <= 99;
    }

    private void validateHintPolicy(JsonNode policy) {
        if (!policy.isObject() || !policy.path("levels").isArray() || policy.path("levels").size() != 2) {
            throw error("GAME_CONFIG_INVALID", "玩家提示必须按一级、二级完整配置");
        }
        int expectedLevel = 1;
        for (JsonNode level : policy.path("levels")) {
            String text = level.path("text").asText();
            String impact = level.path("impactLabel").asText();
            if (level.path("level").asInt(0) != expectedLevel++ || StringUtils.isBlank(text)
                    || text.length() > 200 || StringUtils.isBlank(impact) || impact.length() > 200) {
                throw error("GAME_CONFIG_INVALID", "玩家提示级别、文案或影响说明不正确");
            }
        }
        JsonNode reveal = policy.path("reveal");
        String revealText = reveal.path("text").asText();
        String revealImpact = reveal.path("impactLabel").asText();
        if (!reveal.isObject() || StringUtils.isBlank(revealText) || revealText.length() > 200
                || StringUtils.isBlank(revealImpact) || revealImpact.length() > 200) {
            throw error("GAME_CONFIG_INVALID", "玩家答案揭示配置不正确");
        }
    }

    private JsonNode sessionConfig(GameSession session) {
        try { return objectMapper.readTree(session.getModuleConfigSnapshotJson()); }
        catch (Exception ex) { throw error("GAME_CONFIG_INVALID", "本场游戏配置快照不可用"); }
    }

    private Map<String, String> configuredRoles(GameSession session) {
        Map<String, String> roles = new LinkedHashMap<>();
        for (JsonNode role : sessionConfig(session).path("roles")) {
            roles.put(role.path("code").asText(), role.path("name").asText(role.path("code").asText()));
        }
        return roles;
    }

    private Set<Long> configuredChapterIds(JsonNode config) {
        Set<Long> ids = new HashSet<>();
        for (JsonNode chapter : config.path("chapters")) {
            long id = chapter.path("chapterId").asLong(0);
            if (id > 0) ids.add(id);
        }
        return ids;
    }

    private Long firstChapterId(JsonNode config) {
        for (JsonNode chapter : config.path("chapters")) {
            long id = chapter.path("chapterId").asLong(0);
            if (id > 0) return id;
        }
        return null;
    }

    private int chapterIndex(JsonNode config, Long chapterId) {
        if (chapterId == null) return -1;
        int index = 0;
        for (JsonNode chapter : config.path("chapters")) {
            if (chapterId.equals(chapter.path("chapterId").asLong())) return index;
            index++;
        }
        return -1;
    }

    private boolean isChapterUnlocked(JsonNode config, Long currentChapterId, JsonNode choice) {
        if (!choice.has("chapterId")) return true;
        int currentIndex = chapterIndex(config, currentChapterId);
        int choiceIndex = chapterIndex(config, choice.path("chapterId").asLong());
        return currentIndex >= 0 && choiceIndex >= 0 && choiceIndex <= currentIndex;
    }

    private boolean isLaterChapter(JsonNode config, Long currentChapterId, Long targetChapterId) {
        int targetIndex = chapterIndex(config, targetChapterId);
        int currentIndex = chapterIndex(config, currentChapterId);
        return targetIndex >= 0 && (currentIndex < 0 ? targetIndex == 0 : targetIndex > currentIndex);
    }

    private boolean hasUnlockedChoices(JsonNode config, Long currentChapterId) {
        for (JsonNode choice : config.path("story").path("choices")) {
            if (isChapterUnlocked(config, currentChapterId, choice)) return true;
        }
        return false;
    }

    private List<Map<String, Object>> roleOptions(JsonNode config) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (JsonNode role : config.path("roles")) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("roleCode", role.path("code").asText());
            value.put("roleName", role.path("name").asText(role.path("code").asText()));
            values.add(value);
        }
        return values;
    }

    private List<Map<String, Object>> chapterOptions(JsonNode config, Long currentChapterId) {
        List<Map<String, Object>> values = new ArrayList<>();
        int currentIndex = chapterIndex(config, currentChapterId);
        int index = 0;
        for (JsonNode chapter : config.path("chapters")) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("chapterId", chapter.path("chapterId").asLong());
            value.put("title", chapter.path("title").asText());
            value.put("unlocked", currentIndex >= 0 && index <= currentIndex);
            value.put("unlockable", currentIndex < 0 ? index == 0 : index > currentIndex);
            values.add(value);
            index++;
        }
        return values;
    }

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
                                Long currentChapterId) {
        if (StringUtils.isBlank(choiceId)) throw error("GAME_BAD_REQUEST", "请选择剧情行动");
        for (JsonNode choice : config.path("story").path("choices")) {
            if (!choiceId.equals(choice.path("id").asText())) continue;
            String configuredStation = choice.path("stationCode").asText();
            if (StringUtils.isNotBlank(configuredStation) && !configuredStation.equals(stationCode)) break;
            JsonNode roles = choice.path("allowedRoles");
            if (roles.isArray() && roles.size() > 0 && !containsText(roles, roleCode)) break;
            if (!isChapterUnlocked(config, currentChapterId, choice)) {
                throw error("GAME_CHAPTER_LOCKED", "剧情行动所在章节尚未开放");
            }
            return choice;
        }
        throw error("GAME_FORBIDDEN_SCOPE", "当前角色不能执行该剧情行动");
    }

    private Map<String, Object> choiceResult(String choiceId, boolean duplicate, JsonNode ending) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("choiceId", choiceId);
        data.put("duplicate", duplicate);
        data.put("ending", ending == null ? null : publicEnding(ending));
        return data;
    }

    private boolean allRequiredChoicesApplied(JsonNode config, ArrayNode appliedChoices) {
        Set<String> satisfied = new HashSet<>();
        for (JsonNode applied : appliedChoices) {
            if (!applied.isTextual()) continue;
            JsonNode configured = configuredChoice(config, applied.asText());
            if (configured == null) continue;
            String group = configured.path("choiceGroup").asText();
            satisfied.add(StringUtils.isBlank(group) ? configured.path("id").asText() : group);
        }
        for (JsonNode configured : config.path("story").path("choices")) {
            if (configured.has("required") && !configured.path("required").asBoolean()) continue;
            String choiceId = configured.path("id").asText();
            String group = configured.path("choiceGroup").asText();
            String requirement = StringUtils.isBlank(group) ? choiceId : group;
            if (StringUtils.isBlank(requirement) || !satisfied.contains(requirement)) return false;
        }
        return true;
    }

    private boolean choiceGroupAlreadyApplied(JsonNode config, ArrayNode appliedChoices, JsonNode nextChoice) {
        String group = nextChoice.path("choiceGroup").asText();
        if (StringUtils.isBlank(group)) return false;
        for (JsonNode applied : appliedChoices) {
            JsonNode configured = applied.isTextual() ? configuredChoice(config, applied.asText()) : null;
            if (configured != null && group.equals(configured.path("choiceGroup").asText())) return true;
        }
        return false;
    }

    private JsonNode configuredChoice(JsonNode config, String choiceId) {
        for (JsonNode choice : config.path("story").path("choices")) {
            if (choiceId.equals(choice.path("id").asText())) return choice;
        }
        return null;
    }

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
            state = runtimeMapper.selectStoryState(session.getId(), "TEAM", teamId);
            if (state == null) throw error("GAME_STATE_CONFLICT", "剧情状态创建失败");
        }
        return state;
    }

    private GamePlayerNodeOutcome outcomeForSubmission(GameSession session, GameStationRun station,
                                                       Long teamId, Long memberId,
                                                       GameTaskSubmission submission, JsonNode taskConfig,
                                                       Long receiptId) {
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
                GamePlayerNodeOutcome.COMPLETED, source, submission.getId(), receiptId, null, null);
        ensureFallbackSourceOutcomes(session, station, teamId, memberId, receiptId);
        return outcome;
    }

    private GamePlayerNodeOutcome ensureOutcome(GameSession session, GameStationRun station, Long teamId,
                                                Long memberId, String status, String completionSource,
                                                Long submissionId, Long receiptId, String planCode,
                                                Integer planVersion) {
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
        return outcome;
    }

    private void ensureFallbackSourceOutcomes(GameSession session, GameStationRun completedTarget,
                                              Long teamId, Long memberId, Long receiptId) {
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
                    source.getFallbackPlanCode(), source.getFallbackPlanVersion());
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

    private Map<String, Object> publicRole(JsonNode config, GameTeamRoleAssignment assignment) {
        if (assignment == null) return null;
        Map<String, Object> role = new LinkedHashMap<>();
        role.put("code", assignment.getRoleCode());
        role.put("name", assignment.getRoleName());
        role.put("status", assignment.getStatus());
        JsonNode configured = configuredRole(config, assignment.getRoleCode());
        if (configured != null && configured.hasNonNull("publicBrief")) role.put("publicBrief", configured.path("publicBrief").asText());
        return role;
    }

    private JsonNode configuredTask(JsonNode config, GameStationRun station) {
        JsonNode configured = configuredStation(config.path("stations"), station.getStationCode(), station.getNodeName());
        if (configured == null || !configured.path("playerTask").isObject()) return null;
        return configured.path("playerTask");
    }

    private Map<String, Object> publicTask(JsonNode config, GameStationRun station) {
        JsonNode task = configuredTask(config, station);
        String taskCode = task == null ? null : task.path("taskCode").asText();
        String prompt = task == null ? null : task.path("prompt").asText();
        String inputType = task == null ? null : upper(task.path("inputType").asText());
        if (StringUtils.isBlank(taskCode) || StringUtils.isBlank(prompt) || !TASK_INPUT_TYPES.contains(inputType)) {
            return null;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("taskCode", taskCode);
        value.put("prompt", prompt);
        value.put("inputType", inputType);
        value.put("verificationRequired", task.path("verificationRequired").asBoolean(false));
        String completionPolicy = upper(task.path("completionPolicy").asText());
        value.put("completionPolicy", StringUtils.isBlank(completionPolicy) ? null : completionPolicy);
        return value;
    }

    private JsonNode requireHintPolicy(JsonNode config, GameStationRun station) {
        JsonNode task = configuredTask(config, station);
        JsonNode policy = task == null ? null : task.path("hintPolicy");
        if (policy == null || !policy.isObject()) {
            throw error("GAME_HINT_UNAVAILABLE", "本站任务未配置提示与答案揭示");
        }
        return policy;
    }

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
                                         GamePlayerNodeOutcome outcome) {
        int safeLevel = Math.max(0, Math.min(2, currentLevel));
        List<Map<String, Object>> revealed = new ArrayList<>();
        String nextImpact = null;
        for (JsonNode configured : policy.path("levels")) {
            int level = configured.path("level").asInt(0);
            if (level > 0 && level <= safeLevel) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("level", level);
                value.put("text", configured.path("text").asText());
                revealed.add(value);
            } else if (level == safeLevel + 1) {
                nextImpact = configured.path("impactLabel").asText();
            }
        }
        Integer nextLevel = safeLevel < 2 ? safeLevel + 1 : null;
        Map<String, Object> hint = new LinkedHashMap<>();
        hint.put("currentLevel", safeLevel);
        hint.put("revealedTexts", revealed);
        hint.put("nextLevel", nextLevel);
        hint.put("nextImpactLabel", nextLevel == null ? null : nextImpact);
        hint.put("revealAvailable", safeLevel == 2 && outcome == null);
        hint.put("revealImpactLabel", policy.path("reveal").path("impactLabel").asText());
        return hint;
    }

    private String roleClue(JsonNode config, String roleCode, String stationCode) {
        JsonNode role = configuredRole(config, roleCode);
        if (role == null) return null;
        JsonNode clue = role.path("clues").path(stationCode);
        return clue.isMissingNode() || clue.isNull() ? null : clue.asText();
    }

    private JsonNode configuredRole(JsonNode config, String roleCode) {
        for (JsonNode role : config.path("roles")) if (roleCode.equals(role.path("code").asText())) return role;
        return null;
    }

    private List<Map<String, Object>> allowedChoices(JsonNode config, GameTeamRoleAssignment assignment,
                                                     String stationCode, Long currentChapterId) {
        if (assignment == null) return Collections.emptyList();
        List<Map<String, Object>> choices = new ArrayList<>();
        for (JsonNode choice : config.path("story").path("choices")) {
            String configuredStation = choice.path("stationCode").asText();
            if (StringUtils.isNotBlank(configuredStation) && !configuredStation.equals(stationCode)) continue;
            JsonNode roles = choice.path("allowedRoles");
            if (roles.isArray() && roles.size() > 0 && !containsText(roles, assignment.getRoleCode())) continue;
            if (!isChapterUnlocked(config, currentChapterId, choice)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", choice.path("id").asText());
            item.put("label", choice.path("label").asText());
            choices.add(item);
        }
        return choices;
    }

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

    private JsonNode findEnding(JsonNode config, String endingCode) {
        if (StringUtils.isBlank(endingCode)) return null;
        for (JsonNode ending : config.path("story").path("endings")) {
            if (endingCode.equals(ending.path("code").asText())) return ending;
        }
        return null;
    }

    private Map<String, Object> publicEnding(JsonNode ending) {
        if (ending == null || ending.isMissingNode()) return null;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("code", ending.path("code").asText());
        value.put("title", ending.path("title").asText());
        value.put("text", ending.path("text").asText());
        return value;
    }

    private Map<String, Object> stationView(GameStationRun station, JsonNode config) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("stationId", station.getId());
        view.put("nodeId", station.getNodeId());
        view.put("nodeName", station.getNodeName());
        view.put("stationCode", station.getStationCode());
        view.put("status", station.getStatus());
        view.put("revision", station.getRevision());
        view.put("serviceStartAt", formatDate(station.getServiceStartAt()));
        view.put("serviceEndAt", formatDate(station.getServiceEndAt()));
        view.put("capacity", station.getCapacity());
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("start", formatDate(station.getServiceStartAt()));
        window.put("end", formatDate(station.getServiceEndAt()));
        view.put("serviceWindow", window);
        Map<String, Object> preparation = readMap(station.getPreparationSnapshotJson());
        Object checklist = preparation.get("checklist");
        if (checklist == null) {
            JsonNode configured = configuredStation(config.path("stations"), station.getStationCode(), station.getNodeName());
            checklist = configured == null ? Collections.emptyList()
                    : objectMapper.convertValue(configured.path("preparationChecklist"), List.class);
        }
        view.put("preparationChecklist", checklist);
        view.put("pauseReasonCode", station.getPauseReasonCode());
        view.put("pauseReason", station.getPauseReason());
        view.put("resumeEta", formatDate(station.getResumeEta()));
        view.put("fallbackNodeId", station.getFallbackNodeId());
        view.put("fallbackPlanCode", station.getFallbackPlanCode());
        view.put("fallbackPlanVersion", station.getFallbackPlanVersion());
        view.put("pendingVerificationCount", station.getPendingVerificationCount() == null ? 0 : station.getPendingVerificationCount());
        // 暂停站点必须挑一个**已批准**的备用方案(resolveFallbackPlan 强制),
        // 但方案清单以前没下发,调用方无从挑起。这里只出已批准且源站是本站的那些,
        // 都是运营配置,不含任何机密。
        view.put("fallbackPlanOptions", approvedFallbackPlans(config, station));
        view.put("playable", GameStationRun.ACTIVE.equals(station.getStatus()));
        JsonNode configured = configuredStation(config.path("stations"), station.getStationCode(), station.getNodeName());
        view.put("playerTask", publicTask(config, station));
        view.put("merchantInstruction", configured == null ? null : safeOperationsText(configured, "merchantInstruction"));
        view.put("hiddenInfoReminder", configured == null ? null : safeOperationsText(configured, "hiddenInfoReminder"));
        return view;
    }

    /** 该站可用的已批准备用方案。只读配置投影,判据与 resolveFallbackPlan 逐条对齐。 */
    private List<Map<String, Object>> approvedFallbackPlans(JsonNode config, GameStationRun station) {
        List<Map<String, Object>> options = new ArrayList<>();
        if (station.getStationCode() == null) return options;
        for (JsonNode plan : config.path("fallbackPlans")) {
            if (!"APPROVED".equals(upper(plan.path("status").asText()))) continue;
            if (!station.getStationCode().equals(plan.path("sourceStationCode").asText())) continue;
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("planCode", upper(plan.path("planCode").asText()));
            option.put("version", plan.path("version").asInt(0));
            option.put("targetStationCode", plan.path("targetStationCode").asText());
            options.add(option);
        }
        return options;
    }

    private String safeOperationsText(JsonNode configured, String field) {
        String value = configured.path(field).asText();
        return StringUtils.isBlank(value) || value.length() > 200 ? null : value;
    }

    private GameSessionView baseView(GameSession session, Long activityId, String perspective, String status,
                                     Integer revision, Long currentChapterId) {
        GameSessionView view = new GameSessionView();
        view.setSessionId(session == null ? null : session.getId());
        view.setActivityId(activityId);
        view.setPerspective(perspective);
        view.setStatus(status);
        view.setRevision(revision);
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
        view.setResult(readMap(receipt.getResultJson()));
        return view;
    }

    private String requestHash(GameSessionCommand command) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("activityId", command.getActivityId());
        canonical.put("nodeId", command.getNodeId());
        canonical.put("expectedRevision", command.getExpectedRevision());
        canonical.put("action", command.getAction().trim().toUpperCase(Locale.ROOT));
        canonical.put("payload", command.getPayload());
        try {
            String json = canonicalJson(objectMapper.valueToTree(canonical));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder();
            for (byte b : bytes) value.append(String.format("%02x", b));
            return value.toString();
        } catch (Exception ex) {
            throw error("GAME_BAD_REQUEST", "命令内容无法生成校验指纹");
        }
    }

    private String canonicalJson(JsonNode node) throws Exception {
        if (node == null || node.isNull()) return "null";
        if (node.isArray()) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) out.append(',');
                out.append(canonicalJson(node.get(i)));
            }
            return out.append(']').toString();
        }
        if (node.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            node.fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue()));
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, JsonNode> entry : sorted.entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append(objectMapper.writeValueAsString(entry.getKey())).append(':')
                        .append(canonicalJson(entry.getValue()));
            }
            return out.append('}').toString();
        }
        return objectMapper.writeValueAsString(node);
    }

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

    private static final class ReadyConfig {
        private final Date startAt;
        private final Date endAt;
        private final Integer capacity;
        private final Map<String, Object> preparation;
        private ReadyConfig(Date startAt, Date endAt, Integer capacity, Map<String, Object> preparation) {
            this.startAt = startAt;
            this.endAt = endAt;
            this.capacity = capacity;
            this.preparation = preparation;
        }
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
}
