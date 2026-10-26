package com.chengyinhub.web.service.audit;

import com.chengyinhub.business.domain.GameCommandReceipt;
import com.chengyinhub.business.domain.PlayArriveGuardLog;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.GameSessionRuntimeMapper;
import com.chengyinhub.business.mapper.PlayArriveGuardLogMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.GuardArrivalEvidenceReader;
import com.chengyinhub.business.service.support.GuardAppealEligibility;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/** Consumes immutable PLAY_BASIC_ARRIVE receipts. Never refreshes or creates a runtime. */
@Service
public class GuardArrivalEvidenceReaderImpl implements GuardArrivalEvidenceReader {
    private final PlayArriveGuardLogMapper logs;
    private final GameSessionRuntimeMapper commands;
    private final TopicRouteSessionMapper routes;
    private final com.chengyinhub.business.service.IGameSessionRuntimeService shared;
    private final com.chengyinhub.web.service.play.PlayBasicIntentApplication basicIntents;

    // Resolve the real transactional entry only when validating a personal appeal.
    // Eager construction closes AuditTask -> reader -> basic intent -> activity -> AuditTask.
    public GuardArrivalEvidenceReaderImpl(PlayArriveGuardLogMapper logs, GameSessionRuntimeMapper commands,
                                         TopicRouteSessionMapper routes,
                                         @org.springframework.context.annotation.Lazy
                                         com.chengyinhub.web.service.play.PlayBasicIntentApplication basicIntents,
                                         com.chengyinhub.business.service.IGameSessionRuntimeService shared) {
        this.logs = logs; this.commands = commands; this.routes = routes; this.basicIntents = basicIntents; this.shared = shared;
    }

    @Override
    @Transactional(readOnly = true)
    public Origin readOwned(Long memberId, Long guardLogId, String arrivalRequestId) {
        return read(memberId, guardLogId, arrivalRequestId, false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public Origin lockOwned(Long memberId, Long guardLogId, String arrivalRequestId) {
        return read(memberId, guardLogId, arrivalRequestId, true);
    }

    private Origin read(Long memberId, Long guardLogId, String actionId, boolean lock) {
        if (memberId == null || memberId <= 0 || guardLogId == null || guardLogId <= 0
                || actionId == null || !actionId.matches("[A-Za-z0-9:_-]{1,80}")) throw new ServiceException("原到达引用无效", 400);
        PlayArriveGuardLog log = logs.selectByIdAndMemberId(guardLogId, memberId);
        if (log == null || !memberId.equals(log.getMemberId()) || log.getActivityId() == null
                || log.getTopicId() == null || log.getNodeId() == null) throw new ServiceException("没有本人守卫引用", 403);
        // Same persisted key as PlayBasicIntentApplication.basicRequestId; no latest/current-node fallback.
        String requestId;
        try { requestId = "play:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(actionId.getBytes(StandardCharsets.UTF_8))).substring(0, 48); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        GameCommandReceipt command = commands.selectReceipt(log.getActivityId(), memberId, requestId);
        if (command == null) throw new ServiceException("原命令结果待确认，请保留原请求", 520);
        if (!memberId.equals(command.getActorMemberId()) || !Objects.equals(log.getActivityId(), command.getActivityId())
                || !Objects.equals(log.getNodeId(), command.getNodeId()) || !requestId.equals(command.getRequestId())
                || !"PLAYER".equals(command.getActorPerspective()) || !"PLAY_BASIC_ARRIVE".equals(command.getAction()))
            throw new ServiceException("原到达命令不属于本次守卫引用", 403);
        if (!"FAILED".equals(command.getOutcome())) throw new ServiceException("缺少原到达拒绝终态", 409);
        JsonNode receipt;
        try { receipt = new ObjectMapper().readTree(command.getResultJson()); }
        catch (java.io.IOException | IllegalArgumentException invalid) { throw new ServiceException("原到达回执不可读", 409); }
        if (!GuardAppealEligibility.matchesOwnedRejection(memberId, log, receipt)
                || !receipt.path("intentTerminal").asBoolean(false)) throw new ServiceException("原到达拒绝身份不完整", 409);
        if (receipt.has("runtimeInstance")) {
            Long sessionId = Long.valueOf(receipt.path("routeSessionId").asText());
            String instance = receipt.path("runtimeInstance").asText();
            Integer epoch = receipt.path("authorityEpoch").asInt();
            boolean active = lock ? shared.isOriginalSharedRouteEligibleForUpdate(memberId, log.getTopicId(), log.getActivityId(),
                    log.getNodeId(), sessionId, instance, epoch)
                    : shared.isOriginalSharedRouteEligible(memberId, log.getTopicId(), log.getActivityId(),
                    log.getNodeId(), sessionId, instance, epoch);
            return new Origin(receipt, active);
        }
        if (lock) {
            // Reuse the original entry's member registration/content scope checks for a new appeal/decision.
            // Result reads above remain independent of subsequently changed registration/content eligibility.
            com.chengyinhub.common.core.domain.AjaxResult owned = basicIntents.basicActionReceipt(memberId,
                    log.getActivityId().toString(), log.getTopicId().toString(), log.getNodeId().toString(), actionId);
            if (!receipt.equals(new ObjectMapper().valueToTree(owned))) throw new ServiceException("原局参与资格已变化", 403);
        }
        long sessionId = Long.parseLong(receipt.path("routeSessionId").asText());
        // Match executeBasicIntent order: activity aggregate -> exact personal route -> audit rows.
        if (lock) commands.selectSessionByActivityForUpdate(log.getActivityId());
        TopicRouteSession session = lock ? routes.selectAccessibleByIdForUpdate(sessionId, memberId)
                : routes.selectAccessibleById(sessionId, memberId);
        boolean same = session != null && Objects.equals(session.getId(), sessionId)
                && memberId.equals(session.getMemberId()) && Objects.equals(log.getActivityId(), session.getActivityId())
                && Objects.equals(log.getTopicId(), session.getTopicId());
        return new Origin(receipt, same && Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus()));
    }
}
