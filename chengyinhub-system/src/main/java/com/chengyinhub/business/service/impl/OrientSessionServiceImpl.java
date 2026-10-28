package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.OrientPhase;
import com.chengyinhub.business.domain.OrientSessionClock;
import com.chengyinhub.business.domain.OrientSessionRow;
import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.OrientSessionMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.IOrientSessionService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.OrientTaskEvidenceVerifier;
import com.chengyinhub.business.service.support.OrientSessionException;
import com.chengyinhub.business.service.support.TopicRouteSessionScope;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Doc2 F1a 城市定向会话生命周期。会话行仍由既有路线运行时创建(state 懒建局 + 报名/场次校验),
 * 这里只叠加 phase、服务端计时事实与回执;回执复用 topic_route_action(action_id 以 orient: 打头)。
 * 开关关闭时一律 ORIENT_SESSION_DISABLED,不读 phase 列也不读 orient_session_clock。
 */
@Service
public class OrientSessionServiceImpl implements IOrientSessionService {
    static final String ACTION_PREFIX = "orient:";
    static final String RECEIPT_TERMINAL = "orient:terminal";
    static final String RECEIPT_TASK_PREFIX = "orient:task:";
    static final String RECEIPT_ACTION_PREFIX = "orient:a:";
    static final Set<String> ALTERNATIVE_TASK_TYPES = new HashSet<String>(Arrays.asList("SCAN", "PHOTO", "QUIZ"));
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_.:-]{8,64}");
    private static final Pattern REASON_CODE = Pattern.compile("[A-Z0-9_]{1,48}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OrientSessionFeatureGate gate;
    private final OrientSessionMapper orientMapper;
    private final TopicRouteSessionMapper routeMapper;
    private final ITopicRouteRuntimeService routeRuntime;
    private final CmsTopicMapper topicMapper;
    private final CmsTopicNodeMapper nodeMapper;
    private final OrientTaskEvidenceVerifier verifier;
    private final Clock clock;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private com.chengyinhub.business.service.IOrientScoringService orientScoringService;


    @Autowired
    public OrientSessionServiceImpl(OrientSessionFeatureGate gate, OrientSessionMapper orientMapper,
                                    TopicRouteSessionMapper routeMapper, ITopicRouteRuntimeService routeRuntime,
                                    CmsTopicMapper topicMapper, CmsTopicNodeMapper nodeMapper,
                                    OrientTaskEvidenceVerifier verifier) {
        this(gate, orientMapper, routeMapper, routeRuntime, topicMapper, nodeMapper, verifier,
                Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    OrientSessionServiceImpl(OrientSessionFeatureGate gate, OrientSessionMapper orientMapper,
                             TopicRouteSessionMapper routeMapper, ITopicRouteRuntimeService routeRuntime,
                             CmsTopicMapper topicMapper, CmsTopicNodeMapper nodeMapper,
                             OrientTaskEvidenceVerifier verifier, Clock clock) {
        this.gate = gate;
        this.orientMapper = orientMapper;
        this.routeMapper = routeMapper;
        this.routeRuntime = routeRuntime;
        this.topicMapper = topicMapper;
        this.nodeMapper = nodeMapper;
        this.verifier = verifier;
        this.clock = clock;
    }

    @Override
    public boolean enabled() {
        return gate.isEnabled();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> prepare(Long memberId, Long topicId, Long activityId, String idempotencyKey) {
        requireEnabled();
        String key = requireKey(idempotencyKey);
        if (!positive(memberId) || !positive(topicId) || (activityId != null && activityId < 0)) throw badRequest("参数有误");
        long act = activityId == null ? 0L : activityId;
        requireOrienteeringTopic(topicId);
        requirePlayable(memberId, topicId, act);
        String instance = routeRuntime.playInstance(memberId, topicId, act);
        if (instance != null && instance.startsWith("game:")) {
            throw new OrientSessionException("ORIENT_TEAM_UNSUPPORTED", 409, "组队定向暂不支持该流程");
        }
        TopicRouteSession current = TopicRouteSessionScope.findCurrent(routeMapper, memberId, topicId, act);
        if (current == null) {
            throw new OrientSessionException("ORIENT_SESSION_UNAVAILABLE", 409, "该主题没有路线局,无法进入定向流程");
        }
        OrientSessionRow row = lockOwned(memberId, current.getId());
        String fingerprint = fingerprint("PREPARE", memberId, topicId, act);
        Map<String, Object> replayed = replay(row, memberId, key, fingerprint);
        if (replayed != null) return replayed;
        OrientSessionClock sessionClock = orientMapper.selectClock(row.getId(), memberId);
        OrientPhase phase = OrientPhase.derive(row.getStatus(), row.getPhase(), sessionClock != null);
        if (phase.isTerminal()) throw terminal(row, phase, sessionClock);
        if (row.getPhase() == null) {
            // 存量进行中局首次进入新流程:把推导结果落成显式 phase,之后不再依赖推导。
            orientMapper.updateRunningPhase(row.getId(), memberId, phase.name(), now());
            row.setPhase(phase.name());
        }
        Map<String, Object> result = view(row, phase, sessionClock);
        result.put("ackConfigVersion", row.getConfigVersion());
        record(row, memberId, key, 0L, RECEIPT_ACTION_PREFIX + key, "ORIENT_PREPARE", fingerprint, result);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> start(Long memberId, Long sessionId, Long ackConfigVersion, String idempotencyKey) {
        requireEnabled();
        String key = requireKey(idempotencyKey);
        OrientSessionRow row = lockOwned(memberId, sessionId);
        requireOrienteeringTopic(row.getTopicId());
        String fingerprint = fingerprint("START", memberId, sessionId, ackConfigVersion);
        Map<String, Object> replayed = replay(row, memberId, key, fingerprint);
        if (replayed != null) return replayed;
        OrientSessionClock sessionClock = orientMapper.selectClock(row.getId(), memberId);
        OrientPhase phase = OrientPhase.derive(row.getStatus(), row.getPhase(), sessionClock != null);
        if (phase.isTerminal()) throw terminal(row, phase, sessionClock);
        boolean resumed = sessionClock != null;
        if (!resumed) {
            // 只有首次开赛要求确认本局规则版本;重进直接续上已有计时,不重置。
            requirePlayable(memberId, row.getTopicId(), row.getActivityId());
            if (ackConfigVersion == null || !ackConfigVersion.equals(row.getConfigVersion())) {
                Map<String, Object> data = new LinkedHashMap<String, Object>();
                data.put("sessionId", row.getId());
                data.put("ackConfigVersion", row.getConfigVersion());
                throw new OrientSessionException("ORIENT_ACK_REQUIRED", 409, "请先确认本局规则", data);
            }
            Date startedAt = now();
            sessionClock = new OrientSessionClock();
            sessionClock.setSessionId(row.getId());
            sessionClock.setMemberId(memberId);
            sessionClock.setStartedAt(startedAt);
            sessionClock.setCreateTime(startedAt);
            sessionClock.setUpdateTime(startedAt);
            try {
                orientMapper.insertClock(sessionClock);
            } catch (DuplicateKeyException raced) {
                sessionClock = orientMapper.selectClock(row.getId(), memberId);
                if (sessionClock == null) throw raced;
                resumed = true;
            }
        }
        if (!OrientPhase.RUNNING.name().equals(row.getPhase())) {
            if (orientMapper.updateRunningPhase(row.getId(), memberId, OrientPhase.RUNNING.name(), now()) != 1) {
                throw new OrientSessionException("ORIENT_SESSION_CONFLICT", 409, "本局状态已变化,请刷新");
            }
            row.setPhase(OrientPhase.RUNNING.name());
        }
        Map<String, Object> result = view(row, OrientPhase.RUNNING, sessionClock);
        result.put("resumed", resumed);
        record(row, memberId, key, 0L, RECEIPT_ACTION_PREFIX + key, "ORIENT_START", fingerprint, result);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> abandon(Long memberId, Long sessionId, String idempotencyKey) {
        return finish(memberId, sessionId, idempotencyKey, OrientPhase.ABANDONED, TopicRouteSession.STATUS_ENDED,
                "ORIENT_ABANDON", null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> disqualify(Long memberId, Long sessionId, String reasonCode, String idempotencyKey) {
        if (reasonCode == null || !REASON_CODE.matcher(reasonCode).matches()) throw badRequest("取消资格原因码无效");
        return finish(memberId, sessionId, idempotencyKey, OrientPhase.DISQUALIFIED, OrientPhase.STATUS_DISQUALIFIED,
                "ORIENT_DISQUALIFY", reasonCode);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> taskResult(Long memberId, Long sessionId, Long taskId, String alternativeTaskType,
                                          String idempotencyKey) {
        requireEnabled();
        String key = requireKey(idempotencyKey);
        if (!positive(taskId)) throw badRequest("任务参数有误");
        String alternative = alternativeTaskType == null || alternativeTaskType.trim().isEmpty()
                ? null : alternativeTaskType.trim().toUpperCase(Locale.ROOT);
        if (alternative != null && !ALTERNATIVE_TASK_TYPES.contains(alternative)) throw badRequest("替代任务类型无效");
        OrientSessionRow row = lockOwned(memberId, sessionId);
        requireOrienteeringTopic(row.getTopicId());
        String fingerprint = fingerprint("TASK", memberId, sessionId, taskId, alternative);
        Map<String, Object> replayed = replay(row, memberId, key, fingerprint);
        if (replayed != null) return replayed;
        OrientSessionClock sessionClock = orientMapper.selectClock(row.getId(), memberId);
        OrientPhase phase = OrientPhase.derive(row.getStatus(), row.getPhase(), sessionClock != null);
        // 路线最后一个节点由既有推进入口完成时局会先落 FINISHED,这时仍允许把该任务结果记上。
        if (phase == OrientPhase.ABANDONED || phase == OrientPhase.DISQUALIFIED) throw terminal(row, phase, sessionClock);
        if (sessionClock == null) throw new OrientSessionException("ORIENT_SESSION_NOT_STARTED", 409, "本局还没开赛");
        CmsTopicNode node = nodeMapper.selectCmsTopicNodeById(taskId);
        if (node == null || !row.getTopicId().equals(node.getTopicId())) {
            throw new OrientSessionException("ORIENT_TASK_NOT_FOUND", 404, "任务不存在");
        }
        TopicRouteAction completed = routeMapper.selectActionByReceipt(row.getId(), RECEIPT_TASK_PREFIX + taskId);
        if (completed != null) {
            throw new OrientSessionException("ORIENT_TASK_ALREADY_COMPLETED", 409, "该任务本局已完成", storedResult(completed));
        }
        Date doneAt = verifier.verifiedCompletion(new OrientTaskEvidenceVerifier.Context(memberId, row.getActivityId(),
                row.getTopicId(), row.getId(), row.getPlayInstanceId(), taskId, alternative, sessionClock.getStartedAt()));
        if (doneAt == null || doneAt.before(sessionClock.getStartedAt())
                || (phase == OrientPhase.FINISHED && row.getCompletedAt() != null && doneAt.after(row.getCompletedAt()))) {
            throw new OrientSessionException("ORIENT_TASK_EVIDENCE_MISSING", 409, "服务端还没有该任务的完成记录");
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sessionId", row.getId());
        result.put("taskId", taskId);
        result.put("alternativeTaskType", alternative);
        result.put("receiptId", RECEIPT_TASK_PREFIX + taskId);
        result.put("completedAt", doneAt.getTime());
        result.put("elapsedMs", Math.max(0L, doneAt.getTime() - sessionClock.getStartedAt().getTime()));
        result.put("phase", phase.name());
        if (orientScoringService != null && orientScoringService.isActiveFor(topicMapper.selectCmsTopicById(row.getTopicId()))) {
            orientScoringService.syncSession(memberId, sessionId, "lifecycle:task:" + sessionId + ":" + taskId);
        }
        record(row, memberId, key, taskId, RECEIPT_TASK_PREFIX + taskId, "ORIENT_TASK", fingerprint, result);
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> current(Long memberId, Long topicId, Long activityId) {
        requireEnabled();
        if (!positive(memberId) || !positive(topicId) || (activityId != null && activityId < 0)) {
            throw badRequest("参数有误");
        }
        requireOrienteeringTopic(topicId);
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (OrientSessionRow row : orientMapper.selectOwnedSessions(memberId, topicId, activityId)) {
            OrientSessionClock sessionClock = orientMapper.selectClock(row.getId(), memberId);
            sessions.add(view(row, OrientPhase.derive(row.getStatus(), row.getPhase(), sessionClock != null), sessionClock));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessions", sessions);
        return result;
    }

    @Override
    public Map<String, Object> view(Long memberId, Long sessionId) {
        requireEnabled();
        OrientSessionRow row = owned(memberId, sessionId);
        OrientSessionClock sessionClock = orientMapper.selectClock(row.getId(), memberId);
        return view(row, OrientPhase.derive(row.getStatus(), row.getPhase(), sessionClock != null), sessionClock);
    }

    @Override
    public Map<String, Object> receipt(Long memberId, Long sessionId, String idempotencyKey) {
        requireEnabled();
        String key = requireKey(idempotencyKey);
        OrientSessionRow row = owned(memberId, sessionId);
        TopicRouteAction action = routeMapper.selectActionByAction(row.getId(), memberId, ACTION_PREFIX + key);
        if (action == null) throw new OrientSessionException("ORIENT_RECEIPT_NOT_FOUND", 404, "没有这次请求的回执");
        Map<String, Object> result = storedResult(action);
        result.put("replayed", true);
        return result;
    }

    private Map<String, Object> finish(Long memberId, Long sessionId, String idempotencyKey, OrientPhase target,
                                       int status, String outcome, String reasonCode) {
        requireEnabled();
        String key = requireKey(idempotencyKey);
        OrientSessionRow row = lockOwned(memberId, sessionId);
        requireOrienteeringTopic(row.getTopicId());
        String fingerprint = fingerprint(outcome, memberId, sessionId, reasonCode);
        Map<String, Object> replayed = replay(row, memberId, key, fingerprint);
        if (replayed != null) return replayed;
        OrientSessionClock sessionClock = orientMapper.selectClock(row.getId(), memberId);
        OrientPhase phase = OrientPhase.derive(row.getStatus(), row.getPhase(), sessionClock != null);
        Map<String, Object> result;
        if (phase.isTerminal()) {
            if (phase != target) throw terminal(row, phase, sessionClock);
            // 已是同一终态:换键重试也回同一份终局结果,不再改库。
            TopicRouteAction first = routeMapper.selectActionByReceipt(row.getId(), RECEIPT_TERMINAL);
            result = first != null ? storedResult(first) : view(row, phase, sessionClock);
            record(row, memberId, key, 0L, RECEIPT_ACTION_PREFIX + key, outcome, fingerprint, result);
            return result;
        }
        Date endedAt = now();
        if (orientMapper.terminate(row.getId(), memberId, status, target.name(), endedAt) != 1) {
            throw new OrientSessionException("ORIENT_SESSION_CONFLICT", 409, "本局状态已变化,请刷新");
        }
        if (sessionClock != null) {
            long elapsed = Math.max(0L, endedAt.getTime() - sessionClock.getStartedAt().getTime());
            orientMapper.closeClock(row.getId(), memberId, endedAt, elapsed);
            sessionClock.setEndedAt(endedAt);
            sessionClock.setElapsedMs(elapsed);
        }
        row.setStatus(status);
        row.setPhase(target.name());
        row.setCompletedAt(endedAt);
        result = view(row, target, sessionClock);
        if (reasonCode != null) result.put("reasonCode", reasonCode);
        record(row, memberId, key, 0L, RECEIPT_TERMINAL, outcome, fingerprint, result);
        return result;
    }

    private Map<String, Object> view(OrientSessionRow row, OrientPhase phase, OrientSessionClock sessionClock) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("sessionId", row.getId());
        view.put("topicId", row.getTopicId());
        view.put("activityId", row.getActivityId());
        view.put("playInstanceId", row.getPlayInstanceId());
        view.put("phase", phase.name());
        view.put("status", row.getStatus());
        view.put("configVersion", row.getConfigVersion());
        Long startedAt = sessionClock == null ? null : sessionClock.getStartedAt().getTime();
        view.put("startedAt", startedAt);
        Long elapsed = null;
        if (sessionClock != null) {
            if (sessionClock.getElapsedMs() != null) elapsed = sessionClock.getElapsedMs();
            else if (phase.isTerminal() && row.getCompletedAt() != null) elapsed = row.getCompletedAt().getTime() - startedAt;
            else if (!phase.isTerminal()) elapsed = clock.millis() - startedAt;
            if (elapsed != null) elapsed = Math.max(0L, elapsed);
        }
        view.put("elapsedMs", elapsed);
        view.put("serverNow", clock.millis());
        return view;
    }

    private OrientSessionException terminal(OrientSessionRow row, OrientPhase phase, OrientSessionClock sessionClock) {
        return new OrientSessionException("ORIENT_SESSION_TERMINAL", 409, "本局已结束", view(row, phase, sessionClock));
    }

    private Map<String, Object> replay(OrientSessionRow row, Long memberId, String key, String fingerprint) {
        TopicRouteAction action = routeMapper.selectActionByAction(row.getId(), memberId, ACTION_PREFIX + key);
        if (action == null) return null;
        Map<String, Object> envelope = readEnvelope(action);
        if (!fingerprint.equals(envelope.get("fingerprint"))) {
            throw new OrientSessionException("ORIENT_IDEMPOTENCY_CONFLICT", 409, "幂等键已用于另一请求");
        }
        Map<String, Object> result = resultOf(envelope);
        result.put("replayed", true);
        return result;
    }

    private void record(OrientSessionRow row, Long memberId, String key, Long nodeId, String receiptId, String outcome,
                        String fingerprint, Map<String, Object> result) {
        Map<String, Object> envelope = new LinkedHashMap<String, Object>();
        envelope.put("op", outcome);
        envelope.put("fingerprint", fingerprint);
        envelope.put("result", result);
        TopicRouteAction action = new TopicRouteAction();
        action.setSessionId(row.getId());
        action.setMemberId(memberId);
        action.setNodeId(nodeId);
        action.setActionId(ACTION_PREFIX + key);
        action.setReceiptId(receiptId);
        action.setOutcomeCode(outcome);
        action.setCreateTime(now());
        try {
            action.setResponseJson(JSON.writeValueAsString(envelope));
            routeMapper.insertAction(action);
        } catch (DuplicateKeyException duplicate) {
            throw new OrientSessionException("ORIENT_SESSION_CONFLICT", 409, "请求重复提交,请按幂等键读取回执");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("回执序列化失败", e);
        }
    }

    private Map<String, Object> storedResult(TopicRouteAction action) {
        return resultOf(readEnvelope(action));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resultOf(Map<String, Object> envelope) {
        Object result = envelope.get("result");
        return result instanceof Map ? new LinkedHashMap<String, Object>((Map<String, Object>) result)
                : new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readEnvelope(TopicRouteAction action) {
        try {
            return JSON.readValue(action.getResponseJson(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("回执损坏", e);
        }
    }

    private OrientSessionRow lockOwned(Long memberId, Long sessionId) {
        if (!positive(memberId) || !positive(sessionId)) throw badRequest("参数有误");
        OrientSessionRow row = orientMapper.selectSessionForUpdate(sessionId, memberId);
        if (row == null) throw new OrientSessionException("ORIENT_SESSION_NOT_FOUND", 404, "本局不存在或无权操作");
        return row;
    }

    private OrientSessionRow owned(Long memberId, Long sessionId) {
        if (!positive(memberId) || !positive(sessionId)) throw badRequest("参数有误");
        OrientSessionRow row = orientMapper.selectSession(sessionId, memberId);
        if (row == null) throw new OrientSessionException("ORIENT_SESSION_NOT_FOUND", 404, "本局不存在或无权查看");
        return row;
    }

    /** 报名/场次/路线发布等可玩性校验沿用既有路线运行时(state 内的 requireScope),不在这里另判。 */
    private void requirePlayable(Long memberId, Long topicId, Long activityId) {
        try {
            routeRuntime.state(memberId, topicId, activityId);
        } catch (ServiceException e) {
            throw new OrientSessionException("ORIENT_ACCESS_DENIED", e.getCode() == null ? 403 : e.getCode(), e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new OrientSessionException("ORIENT_NOT_PLAYABLE", 400, e.getMessage());
        }
    }

    private void requireEnabled() {
        if (!gate.isEnabled()) throw new OrientSessionException("ORIENT_SESSION_DISABLED", 404, "城市定向新流程暂未开放");
    }

    private void requireOrienteeringTopic(Long topicId) {
        CmsTopic topic = topicMapper.selectCmsTopicById(topicId);
        if (topic == null) throw new OrientSessionException("ORIENT_TOPIC_NOT_FOUND", 404, "主题不存在");
        // product_type 为空(存量未回填)不按票 mode 猜,一律不进新流程。
        if (!Integer.valueOf(ProductType.ORIENTEERING).equals(topic.getProductType())) {
            throw new OrientSessionException("ORIENT_PRODUCT_TYPE_UNSUPPORTED", 400, "该主题不是城市定向");
        }
    }

    private static String requireKey(String key) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new OrientSessionException("ORIENT_IDEMPOTENCY_KEY_REQUIRED", 400, "缺少有效的幂等键");
        }
        return key;
    }

    private static OrientSessionException badRequest(String message) {
        return new OrientSessionException("ORIENT_BAD_REQUEST", 400, message);
    }

    static String fingerprint(Object... parts) {
        StringBuilder source = new StringBuilder();
        for (Object part : parts) source.append(part == null ? "" : part.toString()).append('\u001f');
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(source.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Date now() {
        return new Date(clock.millis());
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }
}
