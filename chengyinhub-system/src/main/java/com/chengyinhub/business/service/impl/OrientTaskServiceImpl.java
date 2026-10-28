package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.OrientTaskAlternative;
import com.chengyinhub.business.domain.OrientTaskAttempt;
import com.chengyinhub.business.domain.OrientTaskCompletion;
import com.chengyinhub.business.mapper.OrientMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IOrientGeoService;
import com.chengyinhub.business.service.IOrientSafetyService;
import com.chengyinhub.business.service.IOrientTaskService;
import com.chengyinhub.business.service.support.CanonicalDigest;
import com.chengyinhub.business.service.support.OrientRejection;
import com.chengyinhub.business.service.support.OrientSwitches;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;
import com.chengyinhub.common.utils.geo.Wgs84Point;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.IOrientScoringService;
import java.util.regex.Pattern;

@Service
public class OrientTaskServiceImpl implements IOrientTaskService {

    static final int PENDING_REVIEW = 0;
    static final int COMPLETED = 1;
    static final int REJECTED = 2;
    static final int ALREADY_COMPLETED = 3;
    static final int SUPERSEDED = 4;

    static final String VIA_PRIMARY = "PRIMARY";
    private static final String DEFAULT_INSTANCE = "default";
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_.:-]{8,64}");
    private static final Pattern INSTANCE_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");

    @Autowired
    private OrientMapper orientMapper;
    @Autowired
    private PlayTeamMapper playTeamMapper;
    @Autowired
    private ICmsTopicService topicService;
    @Autowired
    private ICmsTopicNodeService topicNodeService;
    @Autowired
    private ICmsActivityService activityService;
    @Autowired
    private IOrientGeoService geoService;
    @Autowired
    private IOrientSafetyService safetyService;
    @Autowired
    private OrientSwitches switches;

    @Autowired
    private TopicRouteSessionMapper routeMapper;
    @Autowired
    private IOrientScoringService scoringService;

    PhotoVerifier photoVerifier = FileOssUploadUtils::verifyUploadedPhoto;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> configureAlternative(Long memberId, Long topicId, Long taskId, String typeRaw,
                                                    String promptText, String answer, Boolean enabled) {
        switches.require(switches.altTaskOn());
        OrientOwnership.requireTopicOwner(topicService, memberId, topicId);
        CmsTopicNode node = requireTask(topicId, taskId);
        AltType type = parseType(typeRaw);
        String prompt = trimToNull(promptText);
        if (prompt != null && prompt.length() > 500) {
            throw new OrientRejection(OrientRejection.BAD_REQUEST, "提示文字不能超过 500 字");
        }
        String answerHash = null;
        if (type == AltType.PHOTO) {
            if (trimToNull(answer) != null) {
                throw new OrientRejection(OrientRejection.BAD_REQUEST, "拍照替代不需要答案");
            }
        } else {
            String normalized = normalizeAnswer(answer);
            if (normalized.isEmpty()) {
                throw new OrientRejection(OrientRejection.BAD_REQUEST, type == AltType.SCAN ? "请填写扫码内容" : "请填写答案");
            }
            answerHash = CanonicalDigest.sha256Hex(normalized);
        }
        Date now = new Date();
        OrientTaskAlternative existing = orientMapper.selectAlternative(node.getId(), type.name());
        OrientTaskAlternative row = existing == null ? new OrientTaskAlternative() : existing;
        row.setPromptText(prompt);
        row.setAnswerHash(answerHash);
        row.setEnabled(Boolean.FALSE.equals(enabled) ? 0 : 1);
        row.setUpdateTime(now);
        if (existing == null) {
            row.setTopicId(topicId);
            row.setTaskId(node.getId());
            row.setAlternativeTaskType(type.name());
            row.setCreateBy(memberId);
            row.setCreateTime(now);
            try {
                orientMapper.insertAlternative(row);
            } catch (DuplicateKeyException race) {
                OrientTaskAlternative winner = orientMapper.selectAlternative(node.getId(), type.name());
                row.setId(winner.getId());
                orientMapper.updateAlternative(row);
            }
        } else {
            orientMapper.updateAlternative(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", node.getId());
        data.put("alternativeTaskType", type.name());
        data.put("enabled", row.getEnabled() == 1);
        data.put("promptText", prompt);
        return data;
    }

    @Override
    public Map<String, Object> taskState(Long memberId, Long topicId, Long activityId, String playInstanceIdRaw,
                                         Long taskId) {
        switches.require(switches.altTaskOn());
        Scope scope = requireParticipantScope(memberId, topicId, activityId, playInstanceIdRaw, taskId);
        String via = completedVia(scope);
        List<Map<String, Object>> alternatives = new ArrayList<>();
        for (OrientTaskAlternative alt : orientMapper.selectAlternativesByTask(taskId)) {
            if (!Integer.valueOf(1).equals(alt.getEnabled())) continue;
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("alternativeTaskType", alt.getAlternativeTaskType());
            a.put("promptText", alt.getPromptText());
            alternatives.add(a);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        data.put("completed", via != null);
        data.put("completedVia", via);
        data.put("photoPendingReview", orientMapper.countPendingPhoto(memberId, scope.activityId, topicId,
                scope.playInstanceId, taskId) > 0);
        data.put("scoreKey", taskId);
        data.put("alternatives", alternatives);
        return data;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> receipt(Long memberId, Long topicId, Long activityId, String playInstanceId,
                                       Long taskId, String idempotencyKey) {
        switches.require(switches.altTaskOn());
        String key = idempotencyKey == null ? "" : idempotencyKey.trim();
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new OrientRejection(OrientRejection.BAD_REQUEST, "缺少或非法的原请求号");
        }
        Scope scope = requireParticipantScope(memberId, topicId, activityId, playInstanceId, taskId);
        OrientTaskAttempt attempt = orientMapper.selectAttemptByIdempotencyKey(memberId, key);
        if (attempt == null || !java.util.Objects.equals(attempt.getMemberId(), memberId)
                || !java.util.Objects.equals(attempt.getTopicId(), scope.topicId)
                || !java.util.Objects.equals(attempt.getActivityId(), scope.activityId)
                || !java.util.Objects.equals(attempt.getTaskId(), scope.taskId)
                || !java.util.Objects.equals(attempt.getPlayInstanceId(), scope.playInstanceId)) {
            throw new OrientRejection(OrientRejection.NOT_FOUND, "尚未查到本次请求回执");
        }
        Map<String, Object> result = outcome(attempt, completedVia(scope), true);
        result.put("idempotencyKey", key);
        result.put("topicId", scope.topicId);
        result.put("activityId", scope.activityId);
        result.put("playInstanceId", scope.playInstanceId);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submit(SubmitCommand cmd) {
        switches.require(switches.altTaskOn());
        String key = cmd.idempotencyKey == null ? "" : cmd.idempotencyKey.trim();
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new OrientRejection(OrientRejection.BAD_REQUEST, "缺少或非法的幂等键(idempotencyKey,8-64 位)");
        }
        AltType type = parseType(cmd.alternativeTaskType);
        Scope scope = requireParticipantScope(cmd.memberId, cmd.topicId, cmd.activityId, cmd.playInstanceId, cmd.taskId);
        OrientTaskAlternative alt = orientMapper.selectAlternative(cmd.taskId, type.name());
        if (alt == null || !Integer.valueOf(1).equals(alt.getEnabled())) {
            throw new OrientRejection(OrientRejection.ALT_NOT_CONFIGURED, "该任务没有开放这种完成方式");
        }
        String payload = payloadDigest(type, cmd);
        String fingerprint = CanonicalDigest.sha256Hex(CanonicalDigest.join(
                String.valueOf(cmd.memberId), String.valueOf(scope.activityId), String.valueOf(scope.topicId),
                scope.playInstanceId, String.valueOf(cmd.taskId), type.name(), payload,
                String.valueOf(cmd.coordSys), String.valueOf(cmd.lng), String.valueOf(cmd.lat)));
        OrientTaskAttempt replay = orientMapper.selectAttemptByIdempotencyKey(cmd.memberId, key);
        if (replay != null) return replayOf(replay, fingerprint);

        safetyService.assertAllowed(cmd.memberId, scope.topic, scope.activityId);

        OrientTaskAttempt attempt = new OrientTaskAttempt();
        attempt.setMemberId(cmd.memberId);
        attempt.setActivityId(scope.activityId);
        attempt.setTopicId(scope.topicId);
        attempt.setPlayInstanceId(scope.playInstanceId);
        attempt.setTaskId(cmd.taskId);
        attempt.setAlternativeTaskType(type.name());
        attempt.setIdempotencyKey(key);
        attempt.setRequestFingerprint(fingerprint);
        attempt.setNodeGeoDirty(0);
        String nodeGeoStatus = applyPosition(attempt, scope.node, cmd);

        String via = completedVia(scope);
        if (via == null && isDelisted(scope.node)) {
            throw new OrientRejection(OrientRejection.TASK_DELISTED, "该站已下架，暂时无法完成");
        }
        if (via != null) {
            attempt.setStatus(ALREADY_COMPLETED);
        } else if (type == AltType.PHOTO) {
            if (orientMapper.countPendingPhoto(cmd.memberId, scope.activityId, scope.topicId,
                    scope.playInstanceId, cmd.taskId) > 0) {
                throw new OrientRejection(OrientRejection.PHOTO_PENDING, "上一张照片还在审核中");
            }
            verifyPhoto(cmd.picUrl, cmd.memberId);
            attempt.setPicUrl(cmd.picUrl.trim());
            attempt.setStatus(PENDING_REVIEW);
        } else {
            boolean ok = alt.getAnswerHash() != null && MessageDigest.isEqual(
                    alt.getAnswerHash().getBytes(StandardCharsets.US_ASCII),
                    payload.getBytes(StandardCharsets.US_ASCII));
            attempt.setStatus(ok ? COMPLETED : REJECTED);
        }
        Date now = new Date();
        attempt.setCreateTime(now);
        attempt.setUpdateTime(now);
        try {
            orientMapper.insertAttempt(attempt);
        } catch (DuplicateKeyException sameKeyRace) {
            OrientTaskAttempt winner = orientMapper.selectAttemptByIdempotencyKey(cmd.memberId, key);
            if (winner == null) throw sameKeyRace;
            return replayOf(winner, fingerprint);
        }
        if (attempt.getStatus() == COMPLETED && !insertCompletion(scope, type.name(), attempt.getId(), now)) {
            orientMapper.updateAttemptStatus(attempt.getId(), COMPLETED, SUPERSEDED, null, now);
            attempt.setStatus(SUPERSEDED);
        }
        Map<String, Object> data = outcome(attempt, completedVia(scope), false);
        Map<String, Object> geo = new LinkedHashMap<>();
        geo.put("coordSys", attempt.getReportCoordSys());
        geo.put("distanceM", attempt.getDistanceM());
        geo.put("nodeGeoStatus", nodeGeoStatus);
        data.put("geo", geo);
        return data;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> review(Long reviewerId, Long attemptId, boolean approve) {
        switches.require(switches.altTaskOn());
        OrientOwnership.requireLogin(reviewerId);
        OrientTaskAttempt attempt = attemptId == null ? null : orientMapper.selectAttemptById(attemptId);
        if (attempt == null || !AltType.PHOTO.name().equals(attempt.getAlternativeTaskType())) {
            throw new OrientRejection(OrientRejection.NOT_FOUND, "提交不存在");
        }
        CmsTopic topic = OrientOwnership.requireTopicOwner(topicService, reviewerId, attempt.getTopicId());
        if (reviewerId.equals(attempt.getMemberId())) {
            throw new OrientRejection(OrientRejection.NOT_OWNER, "不能审核自己的提交");
        }
        // The locator carries no authority. Never substitute current/default for the original run.
        OrientTaskAttempt hint = attempt;
        if (hint.getPlayInstanceId() == null || DEFAULT_INSTANCE.equals(hint.getPlayInstanceId())) {
            throw new OrientRejection(OrientRejection.BAD_REQUEST, "旧提交缺少原局绑定，不能自动审核写入");
        }
        TopicRouteSession original = routeMapper.selectByOwner(hint.getActivityId(), hint.getTopicId(),
                hint.getMemberId(), hint.getPlayInstanceId());
        if (original == null) throw new OrientRejection(OrientRejection.NOT_FOUND, "原对局不存在");
        TopicRouteSession locked = routeMapper.selectAccessibleByIdForUpdate(original.getId(), hint.getMemberId());
        attempt = orientMapper.selectAttemptByIdForUpdate(attemptId);
        if (locked == null || attempt == null
                || !Objects.equals(hint.getId(), attempt.getId())
                || !Objects.equals(hint.getMemberId(), attempt.getMemberId())
                || !Objects.equals(hint.getActivityId(), attempt.getActivityId())
                || !Objects.equals(hint.getTopicId(), attempt.getTopicId())
                || !Objects.equals(hint.getPlayInstanceId(), attempt.getPlayInstanceId())
                || !Objects.equals(hint.getTaskId(), attempt.getTaskId())
                || !Objects.equals(hint.getAlternativeTaskType(), attempt.getAlternativeTaskType())
                || !Objects.equals(hint.getIdempotencyKey(), attempt.getIdempotencyKey())
                || !Objects.equals(hint.getRequestFingerprint(), attempt.getRequestFingerprint())
                || !Objects.equals(locked.getMemberId(), attempt.getMemberId())
                || !Objects.equals(locked.getActivityId(), attempt.getActivityId())
                || !Objects.equals(locked.getTopicId(), attempt.getTopicId())
                || !Objects.equals(locked.getPlayInstanceId(), attempt.getPlayInstanceId())) {
            throw new OrientRejection(OrientRejection.IDEMPOTENCY_CONFLICT, "原提交身份已变化");
        }
        Scope scope = new Scope(topic, null, attempt.getActivityId(), attempt.getTopicId(),
                attempt.getPlayInstanceId(), attempt.getTaskId(), attempt.getMemberId());
        int status = attempt.getStatus();
        if (status != PENDING_REVIEW) {
            boolean sameDecision = approve ? (status == COMPLETED || status == SUPERSEDED) : status == REJECTED;
            if (!sameDecision) {
                throw new OrientRejection(OrientRejection.ATTEMPT_NOT_PENDING, "该提交已审核");
            }
            return outcome(attempt, completedVia(scope), true);
        }
        if (!Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(locked.getStatus())) {
            throw new OrientRejection(OrientRejection.ATTEMPT_NOT_PENDING, "原对局已结束，不能追加审核结果");
        }
        Date now = new Date();
        int target;
        if (!approve) {
            target = REJECTED;
        } else if (completedVia(scope) != null) {
            target = SUPERSEDED;
        } else {
            target = COMPLETED;
        }
        if (orientMapper.updateAttemptStatus(attempt.getId(), PENDING_REVIEW, target, reviewerId, now) != 1) {
            throw new OrientRejection(OrientRejection.ATTEMPT_NOT_PENDING, "该提交已审核");
        }
        if (target == COMPLETED && !insertCompletion(scope, AltType.PHOTO.name(), attempt.getId(), now)) {
            if (orientMapper.updateAttemptStatus(attempt.getId(), COMPLETED, SUPERSEDED, reviewerId, now) != 1) {
                throw new OrientRejection(OrientRejection.ATTEMPT_NOT_PENDING, "替代完成状态已变化");
            }
            target = SUPERSEDED;
        }
        attempt.setStatus(target);
        if (target == COMPLETED && scoringService.isActiveFor(topic)) {
            scoringService.syncSession(attempt.getMemberId(), locked.getId(),
                    "lifecycle:alternative:" + attempt.getId());
        }
        return outcome(attempt, completedVia(scope), false);
    }

    // ------------------------------------------------------------------

    /** 会话作用域。completion 唯一键 = (member, activity, topic, playInstance, task)。 */
    static final class Scope {
        final CmsTopic topic;
        final CmsTopicNode node;
        final long activityId;
        final long topicId;
        final String playInstanceId;
        final long taskId;
        final long memberId;

        Scope(CmsTopic topic, CmsTopicNode node, long activityId, long topicId, String playInstanceId,
              long taskId, long memberId) {
            this.topic = topic;
            this.node = node;
            this.activityId = activityId;
            this.topicId = topicId;
            this.playInstanceId = playInstanceId;
            this.taskId = taskId;
            this.memberId = memberId;
        }
    }

    private Scope requireParticipantScope(Long memberId, Long topicId, Long activityIdRaw, String playInstanceIdRaw,
                                          Long taskId) {
        OrientOwnership.requireLogin(memberId);
        CmsTopic topic = OrientOwnership.requireTopic(topicService, topicId);
        OrientOwnership.requireOrienteering(topic);
        CmsTopicNode node = requireTask(topicId, taskId);
        long activityId = activityIdRaw == null ? 0L : activityIdRaw;
        if (activityId < 0) throw new OrientRejection(OrientRejection.BAD_REQUEST, "参数有误");
        if (activityId > 0) {
            CmsActivity activity = activityService.selectCmsActivityById(activityId);
            if (activity == null || !topicId.equals(activity.getTopicId())) {
                throw new OrientRejection(OrientRejection.NOT_FOUND, "场次不存在");
            }
        }
        // 归属:只能在自己已付费报名的主题/场次里提交或查询(同组队建队资格的口径)。
        int ownerType = activityId > 0 ? 2 : 1;
        long ownerId = activityId > 0 ? activityId : topicId;
        if (playTeamMapper.hasPaidRegistration(memberId, ownerType, ownerId) <= 0) {
            throw new OrientRejection(OrientRejection.NOT_PARTICIPANT, "请先报名该主题");
        }
        String instance = playInstanceIdRaw == null || playInstanceIdRaw.trim().isEmpty()
                ? DEFAULT_INSTANCE : playInstanceIdRaw.trim();
        if (!INSTANCE_ID.matcher(instance).matches()) {
            throw new OrientRejection(OrientRejection.BAD_REQUEST, "参数有误");
        }
        return new Scope(topic, node, activityId, topicId, instance, taskId, memberId);
    }

    private CmsTopicNode requireTask(Long topicId, Long taskId) {
        CmsTopicNode node = taskId == null || taskId <= 0 ? null : topicNodeService.selectCmsTopicNodeById(taskId);
        if (node == null || topicId == null || !topicId.equals(node.getTopicId())) {
            throw new OrientRejection(OrientRejection.NOT_FOUND, "任务不存在");
        }
        return node;
    }

    /** 该作用域内任务已经由哪条路径完成;null = 未完成。两条来源:定向完成事实、既有主路径进度。 */
    private String completedVia(Scope scope) {
        OrientTaskCompletion done = orientMapper.selectCompletion(scope.memberId, scope.activityId, scope.topicId,
                scope.playInstanceId, scope.taskId);
        if (done != null) return done.getCompletedVia();
        if (orientMapper.countLegacyNodeDone(scope.memberId, scope.activityId, scope.topicId,
                scope.playInstanceId, scope.taskId) > 0) {
            return VIA_PRIMARY;
        }
        return null;
    }

    private boolean insertCompletion(Scope scope, String via, Long attemptId, Date now) {
        OrientTaskCompletion completion = new OrientTaskCompletion();
        completion.setMemberId(scope.memberId);
        completion.setActivityId(scope.activityId);
        completion.setTopicId(scope.topicId);
        completion.setPlayInstanceId(scope.playInstanceId);
        completion.setTaskId(scope.taskId);
        completion.setCompletedVia(via);
        completion.setSourceAttemptId(attemptId);
        completion.setCompletedTime(now);
        try {
            orientMapper.insertCompletion(completion);
            return true;
        } catch (DuplicateKeyException otherPathWon) {
            return false;
        }
    }

    /** 位置只在边界转一次 WGS84;节点坐标不干净时不算距离、也不当 0。距离只记录,围栏判定归 F1c LocationGuard。 */
    private String applyPosition(OrientTaskAttempt attempt, CmsTopicNode node, SubmitCommand cmd) {
        boolean reported = cmd.lng != null || cmd.lat != null || trimToNull(cmd.coordSys) != null;
        if (!reported) return null;
        Wgs84Point at = geoService.reportedToWgs84(cmd.coordSys, cmd.lng, cmd.lat);
        attempt.setReportCoordSys(cmd.coordSys.trim().toUpperCase(Locale.ROOT));
        attempt.setWgs84Lng(OrientGeoServiceImpl.scale(at.lng()));
        attempt.setWgs84Lat(OrientGeoServiceImpl.scale(at.lat()));
        IOrientGeoService.NodeGeo nodeGeo = geoService.resolveNode(node);
        if (nodeGeo.clean()) {
            attempt.setDistanceM(geoService.distanceMeters(at, nodeGeo.point()));
        } else if (!IOrientGeoService.NodeGeo.UNNORMALIZED.equals(nodeGeo.status())
                && !IOrientGeoService.NodeGeo.STALE.equals(nodeGeo.status())) {
            attempt.setNodeGeoDirty(1);
        }
        return nodeGeo.status();
    }

    private void verifyPhoto(String picUrl, Long memberId) {
        if (picUrl == null || picUrl.trim().isEmpty()) {
            throw new OrientRejection(OrientRejection.PHOTO_INVALID, "请先上传照片");
        }
        try {
            photoVerifier.verify(picUrl.trim(), memberId);
        } catch (IllegalArgumentException invalid) {
            throw new OrientRejection(OrientRejection.PHOTO_INVALID,
                    invalid.getMessage() == null ? "照片无效，请重新拍摄上传" : invalid.getMessage());
        } catch (OrientRejection e) {
            throw e;
        } catch (IllegalStateException unavailable) {
            throw new OrientRejection(OrientRejection.PHOTO_INVALID, "照片校验服务暂不可用，请稍后重试");
        }
    }

    private Map<String, Object> replayOf(OrientTaskAttempt existing, String fingerprint) {
        if (!fingerprint.equals(existing.getRequestFingerprint())) {
            throw new OrientRejection(OrientRejection.IDEMPOTENCY_CONFLICT, "同一幂等键对应了不同的提交内容");
        }
        Scope scope = new Scope(null, null, existing.getActivityId(), existing.getTopicId(),
                existing.getPlayInstanceId(), existing.getTaskId(), existing.getMemberId());
        return outcome(existing, completedVia(scope), true);
    }

    static Map<String, Object> outcome(OrientTaskAttempt attempt, String completedVia, boolean replay) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("attemptId", attempt.getId());
        data.put("taskId", attempt.getTaskId());
        data.put("alternativeTaskType", attempt.getAlternativeTaskType());
        data.put("outcome", outcomeName(attempt.getStatus()));
        // 任务是否已对该成员完成 —— 以完成事实为准,不以本次提交状态为准(待审照片不算)。
        data.put("completed", completedVia != null);
        data.put("completedVia", completedVia);
        data.put("scoreKey", attempt.getTaskId());
        data.put("replay", replay);
        if (attempt.getStatus() != null && attempt.getStatus() == REJECTED) {
            data.put("errorCode", OrientRejection.ALT_VERIFY_FAILED);
        }
        return data;
    }

    static String outcomeName(Integer status) {
        if (status == null) return "UNKNOWN";
        switch (status) {
            case PENDING_REVIEW: return "PENDING_REVIEW";
            case COMPLETED: return "COMPLETED";
            case REJECTED: return "REJECTED";
            case ALREADY_COMPLETED: return "ALREADY_COMPLETED";
            case SUPERSEDED: return "SUPERSEDED";
            default: return "UNKNOWN";
        }
    }

    private static String payloadDigest(AltType type, SubmitCommand cmd) {
        switch (type) {
            case SCAN: return CanonicalDigest.sha256Hex(normalizeAnswer(cmd.code));
            case QUIZ: return CanonicalDigest.sha256Hex(normalizeAnswer(cmd.answer));
            default: return CanonicalDigest.sha256Hex(cmd.picUrl == null ? "" : cmd.picUrl.trim());
        }
    }

    /** 答案/扫码内容归一:NFKC(全角转半角)+ 去首尾空白 + 内部空白折叠 + 小写。 */
    static String normalizeAnswer(String raw) {
        if (raw == null) return "";
        String n = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ");
        return n.toLowerCase(Locale.ROOT);
    }

    static AltType parseType(String raw) {
        if (raw != null) {
            try {
                return AltType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        throw new OrientRejection(OrientRejection.ALT_TYPE_INVALID, "完成方式只能是 SCAN / PHOTO / QUIZ");
    }

    private static boolean isDelisted(CmsTopicNode node) {
        return node != null && node.getStatus() != null && !Long.valueOf(1L).equals(node.getStatus());
    }

    private static String trimToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}
