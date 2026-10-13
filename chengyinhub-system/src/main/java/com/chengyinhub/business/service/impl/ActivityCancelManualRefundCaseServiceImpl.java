package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ActivityCancelManualRefundCase;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.dto.ActivityCancelManualRefundAssessmentRequest;
import com.chengyinhub.business.domain.dto.ActivityCancelManualRefundHandleRequest;
import com.chengyinhub.business.domain.dto.ActivityCancelManualRefundReopenRequest;
import com.chengyinhub.business.domain.dto.ActivityCancelManualRefundReworkRequest;
import com.chengyinhub.business.domain.dto.ActivityCancelManualRefundVerifyRequest;
import com.chengyinhub.business.domain.vo.ActivityCancelManualRefundCaseVO;
import com.chengyinhub.business.mapper.ActivityCancelManualRefundCaseMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.service.IActivityCancelManualRefundCaseService;
import com.chengyinhub.business.service.IActivityCancelManualRefundEvidenceStore;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class ActivityCancelManualRefundCaseServiceImpl
        implements IActivityCancelManualRefundCaseService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern EVIDENCE_OBJECT_KEY = Pattern.compile(
            "upload/merchant-aftercare-evidence/[0-9a-f]{32}\\.(?:jpg|jpeg|png|gif)");
    private static final Pattern EVIDENCE_VERSION_ID = Pattern.compile(
            "[A-Za-z0-9._~+/=-]{1,256}");
    private static final Set<String> MANUAL_CHANNELS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("original_retry", "offline_transfer",
                    "wx_abnormal_change", "wx_abnormal_bankcard")));
    private static final String EVIDENCE_NOTICE = "凭证已复核不等于银行或微信到账";
    private static final String AUDIT_DOMAIN = "PLATFORM";
    private static final Long AUDIT_DOMAIN_ID = 1L;
    private static final String AUDIT_TARGET = "MANUAL_REFUND_CASE";
    private static final String AUDIT_ROLE = "REFUND_REVIEWER";
    private static final String ACTION_REWORK = "ACTIVITY_REFUND_REWORK";
    private static final String ACTION_REOPEN = "ACTIVITY_REFUND_REOPEN";
    private static final String ACTION_HANDLE_ARCHIVE = "ACTIVITY_REFUND_HANDLE_ARCHIVE";
    private static final String ACTION_ASSESSMENT_ARCHIVE = "ACTIVITY_REFUND_ASSESSMENT_ARCHIVE";
    private static final String ACTION_EVIDENCE_UPLOAD = "ACTIVITY_REFUND_EVIDENCE_UPLOAD";

    @Autowired
    private ActivityCancelManualRefundCaseMapper mapper;

    @Autowired
    private BusinessAuditEventMapper auditMapper;

    @Autowired
    private IActivityCancelManualRefundEvidenceStore evidenceStore;

    /** 必须与活动取消共用事务，避免活动已取消但人工退款责任丢失。 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public ActivityCancelManualRefundCaseVO recordCase(ActivityCancelManualRefundCase draft) {
        validateDraft(draft);
        try {
            if (mapper.insertCase(draft) != 1) {
                throw conflict("人工退款 case 写入失败，请重试");
            }
        } catch (DuplicateKeyException duplicate) {
            return exactDuplicateOrConflict(draft);
        }
        return exactDuplicateOrConflict(draft);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ActivityCancelManualRefundCaseVO findCase(Long activityId, String parentOrderNo) {
        requirePositive(activityId, "活动无效");
        requireText(parentOrderNo, 64, "父订单号无效");
        ActivityCancelManualRefundCase stored = mapper.selectByActivityAndParent(activityId, parentOrderNo);
        return stored == null ? null : toValidatedVo(stored);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ActivityCancelManualRefundCaseVO findTopicCase(Long topicId, String parentOrderNo) {
        requirePositive(topicId, "主题无效");
        requireText(parentOrderNo, 64, "父订单号无效");
        ActivityCancelManualRefundCase stored = mapper.selectByTopicAndParent(topicId, parentOrderNo);
        return stored == null ? null : toValidatedVo(stored);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ActivityCancelManualRefundCaseVO> list(Long clubId, String status) {
        if (clubId != null && clubId <= 0) {
            throw badRequest("俱乐部无效");
        }
        String normalizedStatus = normalizeOptionalStatus(status);
        List<ActivityCancelManualRefundCase> rows = mapper.selectList(clubId, normalizedStatus);
        List<ActivityCancelManualRefundCaseVO> result = new ArrayList<>();
        if (rows == null) {
            return result;
        }
        for (ActivityCancelManualRefundCase row : rows) {
            result.add(toValidatedVo(row));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ActivityCancelManualRefundCaseVO detail(Long caseId) {
        requirePositive(caseId, "case 无效");
        ActivityCancelManualRefundCase row = mapper.selectById(caseId);
        if (row == null) {
            throw new ServiceException("人工退款 case 不存在", 404);
        }
        ActivityCancelManualRefundCaseVO detail = toValidatedVo(row);
        if (row.getEvidenceObjectKey() != null) {
            EvidenceUpload evidence = storedEvidence(row);
            ensureEvidenceDigest(evidence);
            detail.setEvidencePreviewUrl(evidenceStore.shortLivedPreviewUrl(
                    evidence.objectKey, evidence.versionId));
        }
        return detail;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String prepareEvidenceUpload(Long uploaderUserId, Long caseId,
                                        Long expectedVersion, String requestId) {
        EvidenceUploadRequest input = normalizeEvidenceUploadRequest(
                uploaderUserId, caseId, expectedVersion, requestId, null, null);
        ActivityCancelManualRefundCase current = requireCase(input.caseId);
        BusinessAuditEvent replay = selectAudit(
                input.caseId, ACTION_EVIDENCE_UPLOAD, input.requestId);
        if (replay != null) {
            EvidenceUpload evidence = exactEvidenceUploadOrConflict(replay, current, input);
            ensureEvidenceDigest(evidence);
            return evidence.objectKey;
        }
        requireEvidenceUploadState(current, input.expectedVersion);
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String recordEvidenceUpload(Long uploaderUserId, Long caseId,
                                       Long expectedVersion, String requestId,
                                       String objectKey, String versionId) {
        EvidenceUploadRequest input = normalizeEvidenceUploadRequest(
                uploaderUserId, caseId, expectedVersion, requestId, objectKey, versionId);
        if (input.objectKey == null || input.versionId == null) {
            throw badRequest("凭证对象与版本标识不能为空");
        }
        ActivityCancelManualRefundCase current = requireCase(input.caseId);
        BusinessAuditEvent replay = selectAudit(
                input.caseId, ACTION_EVIDENCE_UPLOAD, input.requestId);
        if (replay != null) {
            EvidenceUpload evidence = exactEvidenceUploadOrConflict(replay, current, input);
            ensureEvidenceDigest(evidence);
            return evidence.objectKey;
        }
        requireEvidenceUploadState(current, input.expectedVersion);
        String serverSha256 = evidenceStore.sha256(input.objectKey, input.versionId);
        if (!SHA256.matcher(serverSha256 == null ? "" : serverSha256).matches()) {
            throw corrupt("凭证对象服务返回了无效摘要");
        }
        ActivityCancelManualRefundCase locked = mapper.selectByIdForUpdate(input.caseId);
        if (locked == null) throw conflict("case 状态已变化，请刷新");
        validateStored(locked);
        requireEvidenceUploadState(locked, input.expectedVersion);
        ObjectNode before = JSON.createObjectNode();
        before.put("expectedVersion", input.expectedVersion);
        ObjectNode after = JSON.createObjectNode();
        after.put("objectKey", input.objectKey);
        after.put("versionId", input.versionId);
        after.put("sha256", serverSha256);
        BusinessAuditEvent audit = evidenceUploadAudit(locked, input, before, after);
        try {
            if (auditMapper.insertBusinessAuditEvent(audit) != 1) {
                throw corrupt("凭证上传审计写入失败");
            }
        } catch (DuplicateKeyException duplicate) {
            BusinessAuditEvent raced = selectAudit(
                    input.caseId, ACTION_EVIDENCE_UPLOAD, input.requestId);
            EvidenceUpload evidence = exactEvidenceUploadOrConflict(raced, locked, input);
            ensureEvidenceDigest(evidence);
            return evidence.objectKey;
        }
        BusinessAuditEvent readback = selectAudit(
                input.caseId, ACTION_EVIDENCE_UPLOAD, input.requestId);
        EvidenceUpload evidence = exactEvidenceUploadOrConflict(readback, locked, input);
        if (!serverSha256.equals(evidence.sha256)) {
            throw corrupt("凭证上传审计权威回读不一致");
        }
        return evidence.objectKey;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ActivityCancelManualRefundCaseVO assess(
            Long assessorUserId, ActivityCancelManualRefundAssessmentRequest request) {
        requirePositive(assessorUserId, "后台操作人无效");
        Assessment input = normalizeAssessment(request);

        ActivityCancelManualRefundCase replay = mapper
                .selectByAssessmentRequestId(input.requestId);
        if (replay != null) {
            return exactAssessmentReplayOrConflict(replay, assessorUserId, input);
        }
        BusinessAuditEvent archived = selectAudit(
                input.caseId, ACTION_ASSESSMENT_ARCHIVE, input.requestId);
        if (archived != null) {
            return exactArchivedAssessmentReplayOrConflict(archived, assessorUserId, input);
        }

        ActivityCancelManualRefundCase current = mapper.selectById(input.caseId);
        if (current == null) {
            throw new ServiceException("人工退款 case 不存在", 404);
        }
        validateStored(current);
        if (!ActivityCancelManualRefundCase.STATUS_PENDING_ASSESSMENT.equals(current.getStatus())
                || !input.expectedVersion.equals(current.getVersion())) {
            throw conflict("case 状态或版本已变化，请刷新");
        }
        validateAssessedAmount(input, current.getPaidCashAmountFen());

        Date assessedAt = new Date();
        int changed;
        try {
            changed = mapper.assessCase(input.caseId, input.expectedVersion, input.decision,
                    input.amountFen, input.note, assessorUserId, input.requestId, assessedAt);
        } catch (DuplicateKeyException duplicate) {
            ActivityCancelManualRefundCase raced = mapper
                    .selectByAssessmentRequestId(input.requestId);
            return exactAssessmentReplayOrConflict(raced, assessorUserId, input);
        }
        ActivityCancelManualRefundCase readback = mapper.selectById(input.caseId);
        if (changed != 1) {
            return exactAssessmentReplayOrConflict(readback, assessorUserId, input);
        }
        return exactAssessmentReplayOrConflict(readback, assessorUserId, input);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ActivityCancelManualRefundCaseVO handle(
            Long handlerUserId, ActivityCancelManualRefundHandleRequest request) {
        requirePositive(handlerUserId, "后台处理人无效");
        ManualHandle input = normalizeHandle(request);
        ActivityCancelManualRefundCase replay = mapper.selectByHandleRequestId(input.requestId);
        if (replay != null) return exactHandleReplayOrConflict(replay, handlerUserId, input);
        BusinessAuditEvent archived = selectAudit(
                input.caseId, ACTION_HANDLE_ARCHIVE, input.requestId);
        if (archived != null) {
            return exactArchivedHandleReplayOrConflict(archived, handlerUserId, input);
        }

        ActivityCancelManualRefundCase current = mapper.selectById(input.caseId);
        if (current == null) throw new ServiceException("人工退款 case 不存在", 404);
        validateStored(current);
        if (!ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_REQUIRED.equals(current.getStatus())
                || !input.expectedVersion.equals(current.getVersion())) {
            throw conflict("case 状态或版本已变化，请刷新");
        }
        if (!input.actualAmountFen.equals(current.getAssessedRefundAmountFen())) {
            throw badRequest("实际人工退款金额必须与已评估金额一致");
        }
        EvidenceUpload evidence = exactEvidenceUploadOrConflict(
                selectAudit(input.caseId, ACTION_EVIDENCE_UPLOAD, input.requestId), current,
                evidenceUploadRequest(handlerUserId, input));
        ensureEvidenceDigest(evidence);
        Date handledAt = new Date();
        int changed;
        try {
            changed = mapper.handleCase(input.caseId, input.expectedVersion,
                    input.actualAmountFen, input.channel, input.evidenceObjectKey, evidence.sha256,
                    input.note, handlerUserId, input.requestId, handledAt);
        } catch (DuplicateKeyException duplicate) {
            return exactHandleReplayOrConflict(
                    mapper.selectByHandleRequestId(input.requestId), handlerUserId, input);
        }
        ActivityCancelManualRefundCase readback = mapper.selectById(input.caseId);
        if (changed != 1) return exactHandleReplayOrConflict(readback, handlerUserId, input);
        return exactHandleReplayOrConflict(readback, handlerUserId, input);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ActivityCancelManualRefundCaseVO verify(
            Long verifierUserId, ActivityCancelManualRefundVerifyRequest request) {
        requirePositive(verifierUserId, "后台复核人无效");
        ManualVerify input = normalizeVerify(request);
        ActivityCancelManualRefundCase replay = mapper.selectByVerifyRequestId(input.requestId);
        if (replay != null) return exactVerifyReplayOrConflict(replay, verifierUserId, input);

        ActivityCancelManualRefundCase current = mapper.selectById(input.caseId);
        if (current == null) throw new ServiceException("人工退款 case 不存在", 404);
        validateStored(current);
        if (!ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_RECORDED.equals(current.getStatus())
                || !input.expectedVersion.equals(current.getVersion())) {
            throw conflict("case 状态或版本已变化，请刷新");
        }
        if (verifierUserId.equals(current.getHandledByUserId())) {
            throw new ServiceException("处理人与复核人必须是两名不同操作员", 403);
        }
        EvidenceUpload evidence = storedEvidence(current);
        ensureEvidenceDigest(evidence);
        Date verifiedAt = new Date();
        int changed;
        try {
            changed = mapper.verifyCase(input.caseId, input.expectedVersion,
                    evidence.sha256, input.note, verifierUserId,
                    input.requestId, verifiedAt);
        } catch (DuplicateKeyException duplicate) {
            return exactVerifyReplayOrConflict(
                    mapper.selectByVerifyRequestId(input.requestId), verifierUserId, input);
        }
        ActivityCancelManualRefundCase readback = mapper.selectById(input.caseId);
        if (changed != 1) return exactVerifyReplayOrConflict(readback, verifierUserId, input);
        return exactVerifyReplayOrConflict(readback, verifierUserId, input);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ActivityCancelManualRefundCaseVO rework(
            Long reviewerUserId, ActivityCancelManualRefundReworkRequest request) {
        requirePositive(reviewerUserId, "后台复核人无效");
        Correction input = normalizeCorrection(request == null ? null : request.getCaseId(),
                request == null ? null : request.getExpectedVersion(),
                request == null ? null : request.getReason(),
                request == null ? null : request.getRequestId(), "凭证退回请求不能为空");
        BusinessAuditEvent replay = selectAudit(input.caseId, ACTION_REWORK, input.requestId);
        if (replay != null) return exactCorrectionReplayOrConflict(replay, reviewerUserId, input);

        ActivityCancelManualRefundCase current = mapper.selectByIdForUpdate(input.caseId);
        if (current == null) throw new ServiceException("人工退款 case 不存在", 404);
        validateStored(current);
        replay = selectAudit(input.caseId, ACTION_REWORK, input.requestId);
        if (replay != null) return exactCorrectionReplayOrConflict(replay, reviewerUserId, input);
        if (!ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_RECORDED.equals(current.getStatus())
                || !input.expectedVersion.equals(current.getVersion())) {
            throw conflict("case 状态或版本已变化，请刷新");
        }
        if (reviewerUserId.equals(current.getHandledByUserId())) {
            throw new ServiceException("凭证处理人与退回审核人必须是两名不同操作员", 403);
        }
        Date now = new Date();
        appendHandleArchive(current, input.reason, now);
        appendTransitionAudit(current, reviewerUserId, ACTION_REWORK, input, now,
                ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_REQUIRED);
        if (mapper.reworkCase(input.caseId, input.expectedVersion, now) != 1) {
            throw conflict("case 状态或版本已变化，请刷新");
        }
        ActivityCancelManualRefundCase readback = mapper.selectById(input.caseId);
        validateStored(readback);
        if (!ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_REQUIRED.equals(readback.getStatus())
                || readback.getVersion() == null
                || readback.getVersion() != input.expectedVersion + 1) {
            throw corrupt("凭证退回权威回读不一致");
        }
        return toValidatedVo(readback);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ActivityCancelManualRefundCaseVO reopenAssessment(
            Long reviewerUserId, ActivityCancelManualRefundReopenRequest request) {
        requirePositive(reviewerUserId, "后台复核人无效");
        Correction input = normalizeCorrection(request == null ? null : request.getCaseId(),
                request == null ? null : request.getExpectedVersion(),
                request == null ? null : request.getReason(),
                request == null ? null : request.getRequestId(), "重新评估请求不能为空");
        BusinessAuditEvent replay = selectAudit(input.caseId, ACTION_REOPEN, input.requestId);
        if (replay != null) return exactCorrectionReplayOrConflict(replay, reviewerUserId, input);

        ActivityCancelManualRefundCase current = mapper.selectByIdForUpdate(input.caseId);
        if (current == null) throw new ServiceException("人工退款 case 不存在", 404);
        validateStored(current);
        replay = selectAudit(input.caseId, ACTION_REOPEN, input.requestId);
        if (replay != null) return exactCorrectionReplayOrConflict(replay, reviewerUserId, input);
        if (!ActivityCancelManualRefundCase.STATUS_NO_REFUND.equals(current.getStatus())
                || !input.expectedVersion.equals(current.getVersion())) {
            throw conflict("case 状态或版本已变化，请刷新");
        }
        if (reviewerUserId.equals(current.getAssessedByUserId())) {
            throw new ServiceException("原判额人与重新评估审核人必须是两名不同操作员", 403);
        }
        Date now = new Date();
        appendAssessmentArchive(current, input.reason, now);
        appendTransitionAudit(current, reviewerUserId, ACTION_REOPEN, input, now,
                ActivityCancelManualRefundCase.STATUS_PENDING_ASSESSMENT);
        if (mapper.reopenAssessment(input.caseId, input.expectedVersion, now) != 1) {
            throw conflict("case 状态或版本已变化，请刷新");
        }
        ActivityCancelManualRefundCase readback = mapper.selectById(input.caseId);
        validateStored(readback);
        if (!ActivityCancelManualRefundCase.STATUS_PENDING_ASSESSMENT.equals(readback.getStatus())
                || readback.getVersion() == null
                || readback.getVersion() != input.expectedVersion + 1) {
            throw corrupt("重新评估权威回读不一致");
        }
        return toValidatedVo(readback);
    }

    private ActivityCancelManualRefundCaseVO exactDuplicateOrConflict(
            ActivityCancelManualRefundCase draft) {
        ActivityCancelManualRefundCase stored = draft.getTopicId() != null
                ? mapper.selectByTopicAndParent(draft.getTopicId(), draft.getParentOrderNo())
                : mapper.selectByActivityAndParent(draft.getActivityId(), draft.getParentOrderNo());
        validateStored(stored);
        if (!sameSnapshot(draft, stored)) {
            throw conflict("同一父订单已有不同人工退款快照，拒绝覆盖");
        }
        return toValidatedVo(stored);
    }

    private ActivityCancelManualRefundCaseVO exactAssessmentReplayOrConflict(
            ActivityCancelManualRefundCase stored, Long assessorUserId, Assessment input) {
        validateStored(stored);
        boolean exact = stored != null
                && input.caseId.equals(stored.getId())
                && assessmentDecisionStillVisible(input.decision, stored.getStatus())
                && input.amountFen.equals(stored.getAssessedRefundAmountFen())
                && input.note.equals(stored.getAssessmentNote())
                && assessorUserId.equals(stored.getAssessedByUserId())
                && input.requestId.equals(stored.getAssessmentRequestId())
                && stored.getVersion() != null
                && stored.getVersion() >= input.expectedVersion + 1;
        if (!exact) {
            throw conflict("请求标识已使用或 case 状态已变化，请刷新");
        }
        return toValidatedVo(stored);
    }

    private ActivityCancelManualRefundCaseVO exactHandleReplayOrConflict(
            ActivityCancelManualRefundCase stored, Long handlerUserId, ManualHandle input) {
        validateStored(stored);
        boolean exact = input.caseId.equals(stored.getId())
                && (ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_RECORDED.equals(stored.getStatus())
                    || ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_EVIDENCE_VERIFIED
                    .equals(stored.getStatus()))
                && input.actualAmountFen.equals(stored.getActualRefundAmountFen())
                && input.channel.equals(stored.getManualChannel())
                && input.evidenceObjectKey.equals(stored.getEvidenceObjectKey())
                && input.note.equals(stored.getHandleNote())
                && handlerUserId.equals(stored.getHandledByUserId())
                && input.requestId.equals(stored.getHandleRequestId())
                && stored.getVersion() != null
                && stored.getVersion() >= input.expectedVersion + 1;
        if (!exact) throw conflict("处理请求标识已使用或 case 状态已变化，请刷新");
        ensureEvidenceDigest(storedEvidence(stored));
        return toValidatedVo(stored);
    }

    private boolean assessmentDecisionStillVisible(String decision, String currentStatus) {
        if (ActivityCancelManualRefundCase.STATUS_NO_REFUND.equals(decision)) {
            return ActivityCancelManualRefundCase.STATUS_NO_REFUND.equals(currentStatus);
        }
        return ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_REQUIRED.equals(currentStatus)
                || ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_RECORDED.equals(currentStatus)
                || ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_EVIDENCE_VERIFIED.equals(currentStatus);
    }

    private ActivityCancelManualRefundCaseVO exactVerifyReplayOrConflict(
            ActivityCancelManualRefundCase stored, Long verifierUserId, ManualVerify input) {
        validateStored(stored);
        boolean exact = input.caseId.equals(stored.getId())
                && ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_EVIDENCE_VERIFIED
                .equals(stored.getStatus())
                && input.note.equals(stored.getVerificationNote())
                && verifierUserId.equals(stored.getVerifiedByUserId())
                && input.requestId.equals(stored.getVerifyRequestId())
                && stored.getVersion() != null
                && stored.getVersion() == input.expectedVersion + 1;
        if (!exact) throw conflict("复核请求标识已使用或 case 状态已变化，请刷新");
        ensureEvidenceDigest(storedEvidence(stored));
        return toValidatedVo(stored);
    }

    private ActivityCancelManualRefundCaseVO exactArchivedAssessmentReplayOrConflict(
            BusinessAuditEvent audit, Long assessorUserId, Assessment input) {
        JsonNode before = auditBefore(audit);
        boolean exact = exactAuditEnvelope(audit, ACTION_ASSESSMENT_ARCHIVE,
                input.caseId, assessorUserId, input.requestId)
                && input.expectedVersion.equals(longValue(before, "expectedVersion"))
                && input.decision.equals(textValue(before, "decision"))
                && input.amountFen.equals(longValue(before, "amountFen"))
                && input.note.equals(textValue(before, "note"));
        if (!exact) throw conflict("评估请求标识已归档且参数不一致");
        return currentCaseAfterArchivedReplay(input.caseId);
    }

    private ActivityCancelManualRefundCaseVO exactArchivedHandleReplayOrConflict(
            BusinessAuditEvent audit, Long handlerUserId, ManualHandle input) {
        JsonNode before = auditBefore(audit);
        boolean exact = exactAuditEnvelope(audit, ACTION_HANDLE_ARCHIVE,
                input.caseId, handlerUserId, input.requestId)
                && input.expectedVersion.equals(longValue(before, "expectedVersion"))
                && input.actualAmountFen.equals(longValue(before, "actualAmountFen"))
                && input.channel.equals(textValue(before, "channel"))
                && input.evidenceObjectKey.equals(textValue(before, "evidenceObjectKey"))
                && input.note.equals(textValue(before, "note"));
        if (!exact) throw conflict("处理请求标识已归档且参数不一致");
        return currentCaseAfterArchivedReplay(input.caseId);
    }

    private ActivityCancelManualRefundCaseVO exactCorrectionReplayOrConflict(
            BusinessAuditEvent audit, Long reviewerUserId, Correction input) {
        JsonNode before = auditBefore(audit);
        boolean exact = exactAuditEnvelope(audit, audit.getAction(), input.caseId,
                reviewerUserId, input.requestId)
                && (ACTION_REWORK.equals(audit.getAction()) || ACTION_REOPEN.equals(audit.getAction()))
                && input.expectedVersion.equals(longValue(before, "expectedVersion"))
                && input.reason.equals(audit.getReason());
        if (!exact) throw conflict("纠错请求标识已使用且参数不一致");
        return currentCaseAfterArchivedReplay(input.caseId);
    }

    private ActivityCancelManualRefundCaseVO currentCaseAfterArchivedReplay(Long caseId) {
        ActivityCancelManualRefundCase current = mapper.selectById(caseId);
        if (current == null) throw corrupt("归档请求对应 case 回读为空");
        return toValidatedVo(current);
    }

    private ActivityCancelManualRefundCase requireCase(Long caseId) {
        ActivityCancelManualRefundCase current = mapper.selectById(caseId);
        if (current == null) throw new ServiceException("人工退款 case 不存在", 404);
        validateStored(current);
        return current;
    }

    private void requireEvidenceUploadState(ActivityCancelManualRefundCase current,
                                            Long expectedVersion) {
        if (!ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_REQUIRED.equals(current.getStatus())
                || !expectedVersion.equals(current.getVersion())) {
            throw conflict("case 状态或版本已变化，请刷新");
        }
    }

    private EvidenceUploadRequest evidenceUploadRequest(Long handlerUserId, ManualHandle input) {
        return normalizeEvidenceUploadRequest(handlerUserId, input.caseId,
                input.expectedVersion, input.requestId, input.evidenceObjectKey, null);
    }

    private EvidenceUploadRequest normalizeEvidenceUploadRequest(
            Long uploaderUserId, Long caseId, Long expectedVersion, String requestId,
            String objectKey, String versionId) {
        requirePositive(uploaderUserId, "后台上传人无效");
        EvidenceUploadRequest input = new EvidenceUploadRequest();
        input.uploaderUserId = uploaderUserId;
        input.caseId = requireCaseId(caseId);
        input.expectedVersion = requireVersion(expectedVersion);
        input.requestId = normalizeRequestId(requestId);
        if (objectKey == null && versionId != null) {
            throw badRequest("凭证版本标识不能脱离对象标识");
        }
        if (objectKey != null) {
            input.objectKey = objectKey.trim();
            if (!EVIDENCE_OBJECT_KEY.matcher(input.objectKey).matches()) {
                throw badRequest("凭证对象标识无效");
            }
        }
        if (versionId != null) {
            input.versionId = versionId.trim();
            if (!EVIDENCE_VERSION_ID.matcher(input.versionId).matches()) {
                throw badRequest("凭证版本标识无效");
            }
        }
        return input;
    }

    private EvidenceUpload exactEvidenceUploadOrConflict(
            BusinessAuditEvent audit, ActivityCancelManualRefundCase current,
            EvidenceUploadRequest input) {
        if (audit == null) {
            throw conflict("凭证未由当前操作人在本 case 当前版本上传");
        }
        JsonNode before = auditBefore(audit);
        JsonNode after = auditAfter(audit);
        EvidenceUpload evidence = new EvidenceUpload();
        evidence.objectKey = textValue(after, "objectKey");
        evidence.versionId = textValue(after, "versionId");
        evidence.sha256 = textValue(after, "sha256");
        if (evidence.objectKey == null || !EVIDENCE_OBJECT_KEY.matcher(evidence.objectKey).matches()
                || evidence.versionId == null
                || !EVIDENCE_VERSION_ID.matcher(evidence.versionId).matches()
                || evidence.sha256 == null || !SHA256.matcher(evidence.sha256).matches()) {
            throw corrupt("凭证上传审计损坏");
        }
        boolean exact = exactAuditEnvelope(audit, ACTION_EVIDENCE_UPLOAD,
                input.caseId, input.uploaderUserId, input.requestId)
                && Objects.equals(audit.getOwnerMemberId(), current.getOwnerMemberId())
                && Objects.equals(audit.getRelationId(), relationId(current))
                && input.expectedVersion.equals(longValue(before, "expectedVersion"))
                && (input.objectKey == null || input.objectKey.equals(evidence.objectKey))
                && (input.versionId == null || input.versionId.equals(evidence.versionId));
        if (!exact) {
            throw conflict("凭证上传请求已绑定其他 case、版本或操作人");
        }
        return evidence;
    }

    private EvidenceUpload storedEvidence(ActivityCancelManualRefundCase row) {
        long expectedVersion = ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_RECORDED
                .equals(row.getStatus()) ? row.getVersion() - 1L : row.getVersion() - 2L;
        EvidenceUploadRequest input = normalizeEvidenceUploadRequest(
                row.getHandledByUserId(), row.getId(), expectedVersion,
                row.getHandleRequestId(), row.getEvidenceObjectKey(), null);
        try {
            EvidenceUpload evidence = exactEvidenceUploadOrConflict(
                    selectAudit(row.getId(), ACTION_EVIDENCE_UPLOAD, row.getHandleRequestId()),
                    row, input);
            if (!Objects.equals(evidence.sha256, row.getEvidenceSha256())) {
                throw corrupt("case 与凭证上传审计摘要不一致");
            }
            return evidence;
        } catch (ServiceException mismatch) {
            if (Integer.valueOf(409).equals(mismatch.getCode())) {
                throw corrupt("case 缺少精确匹配的凭证上传审计");
            }
            throw mismatch;
        }
    }

    private void ensureEvidenceDigest(EvidenceUpload evidence) {
        String serverSha256 = evidenceStore.sha256(evidence.objectKey, evidence.versionId);
        if (!SHA256.matcher(serverSha256 == null ? "" : serverSha256).matches()) {
            throw corrupt("凭证对象服务返回了无效摘要");
        }
        if (!evidence.sha256.equals(serverSha256)) {
            throw conflict("凭证固定版本摘要不一致，case 未推进");
        }
    }

    private BusinessAuditEvent evidenceUploadAudit(
            ActivityCancelManualRefundCase row, EvidenceUploadRequest input,
            ObjectNode before, ObjectNode after) {
        BusinessAuditEvent audit = new BusinessAuditEvent();
        audit.setDomainType(AUDIT_DOMAIN);
        audit.setDomainId(AUDIT_DOMAIN_ID);
        audit.setOwnerMemberId(row.getOwnerMemberId());
        audit.setActorMemberId(input.uploaderUserId);
        audit.setActorRoleCode(AUDIT_ROLE);
        audit.setAction(ACTION_EVIDENCE_UPLOAD);
        audit.setTargetType(AUDIT_TARGET);
        audit.setTargetId(row.getId());
        audit.setRelationId(relationId(row));
        audit.setBeforeJson(json(before));
        audit.setAfterJson(json(after));
        audit.setReason("人工退款凭证上传回读通过");
        audit.setRequestId(input.requestId);
        audit.setCreateTime(new Date());
        return audit;
    }

    private BusinessAuditEvent selectAudit(Long caseId, String action, String requestId) {
        return auditMapper.selectByDomainActionRequestForUpdate(
                AUDIT_DOMAIN, AUDIT_DOMAIN_ID, action, requestId);
    }

    private void appendHandleArchive(ActivityCancelManualRefundCase row,
                                     String reason, Date now) {
        EvidenceUpload evidence = storedEvidence(row);
        ObjectNode before = JSON.createObjectNode();
        before.put("expectedVersion", row.getVersion() - 1);
        before.put("status", row.getStatus());
        before.put("actualAmountFen", row.getActualRefundAmountFen());
        before.put("channel", row.getManualChannel());
        before.put("evidenceObjectKey", row.getEvidenceObjectKey());
        before.put("evidenceSha256", row.getEvidenceSha256());
        before.put("evidenceVersionId", evidence.versionId);
        before.put("note", row.getHandleNote());
        before.put("handledByUserId", row.getHandledByUserId());
        before.put("handleRequestId", row.getHandleRequestId());
        appendAudit(row, row.getHandledByUserId(), ACTION_HANDLE_ARCHIVE,
                row.getHandleRequestId(), reason, before,
                stateJson(ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_REQUIRED,
                        row.getVersion() + 1), now);
    }

    private void appendAssessmentArchive(ActivityCancelManualRefundCase row,
                                          String reason, Date now) {
        ObjectNode before = JSON.createObjectNode();
        before.put("expectedVersion", row.getVersion() - 1);
        before.put("status", row.getStatus());
        before.put("decision", ActivityCancelManualRefundCase.STATUS_NO_REFUND);
        before.put("amountFen", row.getAssessedRefundAmountFen());
        before.put("note", row.getAssessmentNote());
        before.put("assessedByUserId", row.getAssessedByUserId());
        before.put("assessmentRequestId", row.getAssessmentRequestId());
        appendAudit(row, row.getAssessedByUserId(), ACTION_ASSESSMENT_ARCHIVE,
                row.getAssessmentRequestId(), reason, before,
                stateJson(ActivityCancelManualRefundCase.STATUS_PENDING_ASSESSMENT,
                        row.getVersion() + 1), now);
    }

    private void appendTransitionAudit(ActivityCancelManualRefundCase row,
                                       Long reviewerUserId, String action,
                                       Correction input, Date now, String nextStatus) {
        ObjectNode before = JSON.createObjectNode();
        before.put("expectedVersion", input.expectedVersion);
        before.put("status", row.getStatus());
        appendAudit(row, reviewerUserId, action, input.requestId, input.reason,
                before, stateJson(nextStatus, input.expectedVersion + 1), now);
    }

    private ObjectNode stateJson(String status, Long version) {
        ObjectNode after = JSON.createObjectNode();
        after.put("status", status);
        after.put("version", version);
        return after;
    }

    private void appendAudit(ActivityCancelManualRefundCase row, Long actorUserId,
                             String action, String requestId, String reason,
                             ObjectNode before, ObjectNode after, Date now) {
        BusinessAuditEvent audit = new BusinessAuditEvent();
        audit.setDomainType(AUDIT_DOMAIN);
        audit.setDomainId(AUDIT_DOMAIN_ID);
        audit.setOwnerMemberId(row.getOwnerMemberId());
        audit.setActorMemberId(actorUserId);
        audit.setActorRoleCode(AUDIT_ROLE);
        audit.setAction(action);
        audit.setTargetType(AUDIT_TARGET);
        audit.setTargetId(row.getId());
        audit.setRelationId(relationId(row));
        audit.setBeforeJson(json(before));
        audit.setAfterJson(json(after));
        audit.setReason(reason);
        audit.setRequestId(requestId);
        audit.setCreateTime(now);
        try {
            if (auditMapper.insertBusinessAuditEvent(audit) != 1) {
                throw corrupt("人工退款纠错审计写入失败");
            }
        } catch (DuplicateKeyException duplicate) {
            throw conflict("人工退款纠错请求标识已使用，请刷新");
        }
    }

    private String json(ObjectNode value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception impossible) {
            throw corrupt("人工退款审计序列化失败");
        }
    }

    private JsonNode auditBefore(BusinessAuditEvent audit) {
        if (audit == null || audit.getBeforeJson() == null) throw corrupt("人工退款归档审计损坏");
        try {
            JsonNode value = JSON.readTree(audit.getBeforeJson());
            if (value == null || !value.isObject()) throw new IllegalArgumentException();
            return value;
        } catch (Exception invalid) {
            throw corrupt("人工退款归档审计损坏");
        }
    }

    private JsonNode auditAfter(BusinessAuditEvent audit) {
        if (audit == null || audit.getAfterJson() == null) throw corrupt("人工退款上传审计损坏");
        try {
            JsonNode value = JSON.readTree(audit.getAfterJson());
            if (value == null || !value.isObject()) throw new IllegalArgumentException();
            return value;
        } catch (Exception invalid) {
            throw corrupt("人工退款上传审计损坏");
        }
    }

    private boolean exactAuditEnvelope(BusinessAuditEvent audit, String action,
                                       Long caseId, Long actorUserId, String requestId) {
        return audit != null && AUDIT_DOMAIN.equals(audit.getDomainType())
                && AUDIT_DOMAIN_ID.equals(audit.getDomainId())
                && action.equals(audit.getAction())
                && AUDIT_TARGET.equals(audit.getTargetType())
                && caseId.equals(audit.getTargetId())
                && actorUserId.equals(audit.getActorMemberId())
                && requestId.equals(audit.getRequestId());
    }

    private Long longValue(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value != null && value.isIntegralNumber() && value.canConvertToLong()
                ? value.longValue() : null;
    }

    private String textValue(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private void validateDraft(ActivityCancelManualRefundCase row) {
        if (row == null) throw badRequest("人工退款快照不能为空");
        requireExactlyOneOwner(row, false);
        requirePositive(row.getOwnerMemberId(), "活动归属无效");
        requirePositive(row.getActorMemberId(), "取消操作人无效");
        if (row.getClubId() != null && row.getClubId() <= 0) throw badRequest("俱乐部无效");
        requireText(row.getParentOrderNo(), 64, "父订单号无效");
        requirePositive(row.getPayerRegistrationId(), "支付主单无效");
        requirePositive(row.getPayerMemberId(), "付款人无效");
        if (row.getPaidCashAmountFen() == null || row.getPaidCashAmountFen() < 0) {
            throw badRequest("实付现金快照无效");
        }
        List<Long> verified = parseIds(row.getVerifiedRegistrationIdsJson(), 400);
        List<Long> unverified = unverifiedIds(row, 400);
        validateEligibility(row.getPayerRegistrationId(), verified, unverified, 400);
        validateChapterSnapshot(row, verified, unverified, 400);
        if (!ActivityCancelManualRefundCase.STATUS_PENDING_ASSESSMENT.equals(row.getStatus())
                || row.getAssessedRefundAmountFen() != null || row.getAssessmentNote() != null
                || row.getAssessedByUserId() != null || row.getAssessmentRequestId() != null
                || row.getAssessedAt() != null || hasHandleFields(row) || hasVerifyFields(row)) {
            throw badRequest("新 case 必须处于未评估状态");
        }
    }

    private void validateStored(ActivityCancelManualRefundCase row) {
        if (row == null) throw corrupt("人工退款 case 回读为空");
        try {
            requirePositive(row.getId(), "case 主键损坏");
            requireExactlyOneOwner(row, true);
            requirePositive(row.getOwnerMemberId(), "归属快照损坏");
            requirePositive(row.getActorMemberId(), "操作人快照损坏");
            if (row.getClubId() != null && row.getClubId() <= 0) throw corrupt("俱乐部快照损坏");
            requireText(row.getParentOrderNo(), 64, "父订单快照损坏");
            requirePositive(row.getPayerRegistrationId(), "支付主单快照损坏");
            requirePositive(row.getPayerMemberId(), "付款人快照损坏");
            if (row.getPaidCashAmountFen() == null || row.getPaidCashAmountFen() < 0
                    || row.getVersion() == null || row.getVersion() < 0) {
                throw corrupt("金额或版本快照损坏");
            }
            List<Long> verified = parseIds(row.getVerifiedRegistrationIdsJson(), 500);
            List<Long> unverified = unverifiedIds(row, 500);
            validateEligibility(row.getPayerRegistrationId(), verified, unverified, 500);
            validateChapterSnapshot(row, verified, unverified, 500);
            validateStoredState(row);
        } catch (ServiceException e) {
            if (Integer.valueOf(500).equals(e.getCode())) throw e;
            throw corrupt(e.getMessage());
        }
    }

    private void validateStoredState(ActivityCancelManualRefundCase row) {
        if (ActivityCancelManualRefundCase.STATUS_PENDING_ASSESSMENT.equals(row.getStatus())) {
            if (row.getAssessedRefundAmountFen() != null || row.getAssessmentNote() != null
                    || row.getAssessedByUserId() != null || row.getAssessmentRequestId() != null
                    || row.getAssessedAt() != null || hasHandleFields(row) || hasVerifyFields(row)
                    || !isEvenVersion(row.getVersion())) {
                throw corrupt("未评估 case 状态损坏");
            }
            return;
        }
        if (!ActivityCancelManualRefundCase.STATUS_NO_REFUND.equals(row.getStatus())
                && !ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_REQUIRED.equals(row.getStatus())
                && !ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_RECORDED.equals(row.getStatus())
                && !ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_EVIDENCE_VERIFIED.equals(row.getStatus())) {
            throw corrupt("case 状态非法");
        }
        if (row.getAssessedRefundAmountFen() == null || row.getAssessmentNote() == null
                || row.getAssessmentNote().trim().isEmpty() || row.getAssessmentNote().length() > 500
                || row.getAssessedByUserId() == null || row.getAssessedByUserId() <= 0
                || row.getAssessmentRequestId() == null
                || !REQUEST_ID.matcher(row.getAssessmentRequestId()).matches()
                || row.getAssessedAt() == null) {
            throw corrupt("case 评估回读损坏");
        }
        if (ActivityCancelManualRefundCase.STATUS_NO_REFUND.equals(row.getStatus())) {
            if (row.getAssessedRefundAmountFen() != 0L || !isOddVersion(row.getVersion())
                    || hasHandleFields(row) || hasVerifyFields(row)) {
                throw corrupt("NO_REFUND 终态损坏");
            }
            return;
        } else if (row.getAssessedRefundAmountFen() <= 0
                || row.getAssessedRefundAmountFen() > row.getPaidCashAmountFen()) {
            throw corrupt("人工退款判额越界");
        }
        if (ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_REQUIRED.equals(row.getStatus())) {
            if (!isOddVersion(row.getVersion()) || hasHandleFields(row) || hasVerifyFields(row)) {
                throw corrupt("待人工处理 case 状态损坏");
            }
            return;
        }
        validateHandleFields(row);
        if (ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_RECORDED.equals(row.getStatus())) {
            if (!isEvenVersion(row.getVersion()) || row.getVersion() < 2L || hasVerifyFields(row)) {
                throw corrupt("人工退款凭证记录状态损坏");
            }
            return;
        }
        if (!isOddVersion(row.getVersion()) || row.getVersion() < 3L || row.getVerificationNote() == null
                || row.getVerificationNote().trim().isEmpty() || row.getVerificationNote().length() > 500
                || row.getVerifiedByUserId() == null || row.getVerifiedByUserId() <= 0
                || row.getVerifiedByUserId().equals(row.getHandledByUserId())
                || row.getVerifyRequestId() == null
                || !REQUEST_ID.matcher(row.getVerifyRequestId()).matches()
                || row.getVerifiedAt() == null) {
            throw corrupt("人工退款凭证复核状态损坏");
        }
    }

    private void validateHandleFields(ActivityCancelManualRefundCase row) {
        if (row.getActualRefundAmountFen() == null
                || !row.getActualRefundAmountFen().equals(row.getAssessedRefundAmountFen())
                || !MANUAL_CHANNELS.contains(row.getManualChannel())
                || row.getEvidenceObjectKey() == null
                || !EVIDENCE_OBJECT_KEY.matcher(row.getEvidenceObjectKey()).matches()
                || row.getEvidenceSha256() == null || !SHA256.matcher(row.getEvidenceSha256()).matches()
                || row.getHandleNote() == null || row.getHandleNote().trim().isEmpty()
                || row.getHandleNote().length() > 500
                || row.getHandledByUserId() == null || row.getHandledByUserId() <= 0
                || row.getHandleRequestId() == null || !REQUEST_ID.matcher(row.getHandleRequestId()).matches()
                || row.getHandledAt() == null) {
            throw corrupt("人工退款凭证记录损坏");
        }
    }

    private boolean hasHandleFields(ActivityCancelManualRefundCase row) {
        return row.getActualRefundAmountFen() != null || row.getManualChannel() != null
                || row.getEvidenceObjectKey() != null || row.getEvidenceSha256() != null
                || row.getHandleNote() != null
                || row.getHandledByUserId() != null || row.getHandleRequestId() != null
                || row.getHandledAt() != null;
    }

    private boolean hasVerifyFields(ActivityCancelManualRefundCase row) {
        return row.getVerificationNote() != null || row.getVerifiedByUserId() != null
                || row.getVerifyRequestId() != null || row.getVerifiedAt() != null;
    }

    private boolean isEvenVersion(Long version) {
        return version != null && version >= 0 && version % 2 == 0;
    }

    private boolean isOddVersion(Long version) {
        return version != null && version > 0 && version % 2 == 1;
    }

    private ActivityCancelManualRefundCaseVO toValidatedVo(ActivityCancelManualRefundCase row) {
        validateStored(row);
        ActivityCancelManualRefundCaseVO vo = new ActivityCancelManualRefundCaseVO();
        vo.setId(row.getId());
        vo.setClubId(row.getClubId());
        vo.setActivityId(row.getActivityId());
        vo.setTopicId(row.getTopicId());
        vo.setOwnerMemberId(row.getOwnerMemberId());
        vo.setActorMemberId(row.getActorMemberId());
        vo.setParentOrderNo(row.getParentOrderNo());
        vo.setPayerRegistrationId(row.getPayerRegistrationId());
        vo.setPayerMemberId(row.getPayerMemberId());
        vo.setPaidCashAmountFen(row.getPaidCashAmountFen());
        vo.setVerifiedRegistrationIds(parseIds(row.getVerifiedRegistrationIdsJson(), 500));
        vo.setUnverifiedRegistrationIds(unverifiedIds(row, 500));
        vo.setCaseType(row.getCaseType());
        vo.setChapterSnapshotJson(row.getChapterSnapshotJson());
        vo.setStatus(row.getStatus());
        vo.setAssessedRefundAmountFen(row.getAssessedRefundAmountFen());
        vo.setAssessmentNote(row.getAssessmentNote());
        vo.setAssessedByUserId(row.getAssessedByUserId());
        vo.setAssessmentRequestId(row.getAssessmentRequestId());
        vo.setAssessedAt(row.getAssessedAt());
        vo.setActualRefundAmountFen(row.getActualRefundAmountFen());
        vo.setManualChannel(row.getManualChannel());
        vo.setEvidenceSha256(row.getEvidenceSha256());
        vo.setHandleNote(row.getHandleNote());
        vo.setHandledByUserId(row.getHandledByUserId());
        vo.setHandleRequestId(row.getHandleRequestId());
        vo.setHandledAt(row.getHandledAt());
        vo.setVerificationNote(row.getVerificationNote());
        vo.setVerifiedByUserId(row.getVerifiedByUserId());
        vo.setVerifyRequestId(row.getVerifyRequestId());
        vo.setVerifiedAt(row.getVerifiedAt());
        vo.setEvidenceVerificationNotice(EVIDENCE_NOTICE);
        vo.setVersion(row.getVersion());
        vo.setCreateTime(row.getCreateTime());
        vo.setUpdateTime(row.getUpdateTime());
        return vo;
    }

    private boolean sameSnapshot(ActivityCancelManualRefundCase left,
                                 ActivityCancelManualRefundCase right) {
        return Objects.equals(left.getCaseType(), right.getCaseType())
                && sameChapterSnapshot(left.getChapterSnapshotJson(), right.getChapterSnapshotJson())
                && Objects.equals(left.getClubId(), right.getClubId())
                && Objects.equals(left.getActivityId(), right.getActivityId())
                && Objects.equals(left.getTopicId(), right.getTopicId())
                && Objects.equals(left.getOwnerMemberId(), right.getOwnerMemberId())
                && Objects.equals(left.getActorMemberId(), right.getActorMemberId())
                && Objects.equals(left.getParentOrderNo(), right.getParentOrderNo())
                && Objects.equals(left.getPayerRegistrationId(), right.getPayerRegistrationId())
                && Objects.equals(left.getPayerMemberId(), right.getPayerMemberId())
                && Objects.equals(left.getPaidCashAmountFen(), right.getPaidCashAmountFen())
                && parseIds(left.getVerifiedRegistrationIdsJson(), 400)
                .equals(parseIds(right.getVerifiedRegistrationIdsJson(), 500))
                && unverifiedIds(left, 400)
                .equals(unverifiedIds(right, 500));
    }

    private boolean sameChapterSnapshot(String left, String right) {
        if (left == null || right == null) return left == right;
        try {
            return JSON.readTree(left).equals(JSON.readTree(right));
        } catch (Exception invalid) {
            throw badRequest("章节快照无效");
        }
    }

    private Assessment normalizeAssessment(ActivityCancelManualRefundAssessmentRequest request) {
        if (request == null) throw badRequest("评估请求不能为空");
        requirePositive(request.getCaseId(), "case 无效");
        if (request.getExpectedVersion() == null || request.getExpectedVersion() < 0
                || request.getExpectedVersion() == Long.MAX_VALUE) {
            throw badRequest("case 版本无效");
        }
        String decision = request.getDecision() == null ? "" : request.getDecision().trim();
        if (!ActivityCancelManualRefundCase.STATUS_NO_REFUND.equals(decision)
                && !ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_REQUIRED.equals(decision)) {
            throw badRequest("decision 仅支持 NO_REFUND 或 MANUAL_REFUND_REQUIRED");
        }
        String note = request.getNote() == null ? "" : request.getNote().trim();
        requireText(note, 500, "评估说明不能为空且不得超过 500 字");
        String requestId = request.getRequestId() == null ? "" : request.getRequestId().trim();
        if (!REQUEST_ID.matcher(requestId).matches()) throw badRequest("请求标识无效");
        if (request.getAssessedRefundAmountFen() == null
                || request.getAssessedRefundAmountFen() < 0) {
            throw badRequest("评估退款金额无效");
        }
        Assessment out = new Assessment();
        out.caseId = request.getCaseId();
        out.expectedVersion = request.getExpectedVersion();
        out.decision = decision;
        out.amountFen = request.getAssessedRefundAmountFen();
        out.note = note;
        out.requestId = requestId;
        return out;
    }

    private void validateAssessedAmount(Assessment input, Long paidCashAmountFen) {
        if (ActivityCancelManualRefundCase.STATUS_NO_REFUND.equals(input.decision)) {
            if (input.amountFen != 0L) throw badRequest("NO_REFUND 的判额必须为 0 分");
            return;
        }
        if (input.amountFen <= 0 || paidCashAmountFen == null
                || input.amountFen > paidCashAmountFen) {
            throw badRequest("人工退款判额必须大于 0 且不得超过实付现金快照");
        }
    }

    private List<Long> unverifiedIds(ActivityCancelManualRefundCase row, int errorCode) {
        if (ActivityCancelManualRefundCase.TYPE_PARTIAL_CHAPTERS.equals(row.getCaseType())
                && "[]".equals(row.getUnverifiedRegistrationIdsJson())) return Collections.emptyList();
        return parseIds(row.getUnverifiedRegistrationIdsJson(), errorCode);
    }

    private void validateChapterSnapshot(ActivityCancelManualRefundCase row, List<Long> verified,
                                         List<Long> unverified, int errorCode) {
        if (ActivityCancelManualRefundCase.TYPE_MIXED_TICKETS.equals(row.getCaseType())) {
            if (row.getChapterSnapshotJson() != null) throw new ServiceException("多票工单不能携带章节快照", errorCode);
            return;
        }
        if (!ActivityCancelManualRefundCase.TYPE_PARTIAL_CHAPTERS.equals(row.getCaseType())) {
            throw new ServiceException("售后工单类型无效", errorCode);
        }
        try {
            JsonNode chapters = JSON.readTree(row.getChapterSnapshotJson());
            if (chapters == null || !chapters.isArray() || chapters.size() < 2) throw new IllegalArgumentException();
            Set<Long> registrations = new HashSet<>(verified); registrations.addAll(unverified);
            Set<String> keys = new HashSet<>();
            Set<Long> seen = new HashSet<>(), usedRegistrations = new HashSet<>();
            int used = 0, pending = 0;
            for (JsonNode chapter : chapters) {
                Long registrationId = longValue(chapter, "registrationId"), chapterId = longValue(chapter, "chapterId");
                Long status = longValue(chapter, "status");
                if (registrationId == null || !registrations.contains(registrationId)
                        || chapterId == null || chapterId <= 0
                        || !keys.add(registrationId + ":" + chapterId)
                        || status == null || (status != 0 && status != 1)) throw new IllegalArgumentException();
                seen.add(registrationId);
                if (status == 1) { used++; usedRegistrations.add(registrationId); } else pending++;
            }
            if (used == 0 || pending == 0 || !seen.equals(registrations)
                    || !usedRegistrations.equals(new HashSet<>(verified))) throw new IllegalArgumentException();
        } catch (Exception invalid) {
            throw new ServiceException("部分履约章节快照损坏", errorCode);
        }
    }

    private List<Long> parseIds(String json, int errorCode) {
        try {
            JsonNode root = JSON.readTree(json);
            if (root == null || !root.isArray() || root.size() == 0) {
                throw new IllegalArgumentException();
            }
            List<Long> result = new ArrayList<>();
            long previous = 0;
            for (JsonNode item : root) {
                if (!item.isIntegralNumber() || !item.canConvertToLong()) {
                    throw new IllegalArgumentException();
                }
                long id = item.longValue();
                if (id <= 0 || id <= previous) throw new IllegalArgumentException();
                result.add(id);
                previous = id;
            }
            return result;
        } catch (Exception e) {
            throw new ServiceException("报名资格快照损坏", errorCode);
        }
    }

    private void validateEligibility(Long payerRegistrationId, List<Long> verified,
                                     List<Long> unverified, int errorCode) {
        Set<Long> all = new HashSet<>(verified);
        for (Long id : unverified) {
            if (!all.add(id)) throw new ServiceException("报名资格快照重复", errorCode);
        }
        if (!all.contains(payerRegistrationId)) {
            throw new ServiceException("支付主单不在报名资格快照中", errorCode);
        }
    }

    private String normalizeOptionalStatus(String status) {
        if (status == null || status.trim().isEmpty()) return null;
        String normalized = status.trim();
        if (!ActivityCancelManualRefundCase.STATUS_PENDING_ASSESSMENT.equals(normalized)
                && !ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_REQUIRED.equals(normalized)
                && !ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_RECORDED.equals(normalized)
                && !ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_EVIDENCE_VERIFIED.equals(normalized)
                && !ActivityCancelManualRefundCase.STATUS_NO_REFUND.equals(normalized)) {
            throw badRequest("case 状态筛选无效");
        }
        return normalized;
    }

    private ManualHandle normalizeHandle(ActivityCancelManualRefundHandleRequest request) {
        if (request == null) throw badRequest("人工退款处理请求不能为空");
        ManualHandle out = new ManualHandle();
        out.caseId = requireCaseId(request.getCaseId());
        out.expectedVersion = requireVersion(request.getExpectedVersion());
        if (request.getActualRefundAmountFen() == null || request.getActualRefundAmountFen() <= 0) {
            throw badRequest("实际人工退款金额必须大于 0");
        }
        out.actualAmountFen = request.getActualRefundAmountFen();
        out.channel = request.getChannel() == null ? "" : request.getChannel().trim();
        if (!MANUAL_CHANNELS.contains(out.channel)) throw badRequest("人工退款渠道无效");
        out.evidenceObjectKey = request.getEvidenceObjectKey() == null
                ? "" : request.getEvidenceObjectKey().trim();
        if (!EVIDENCE_OBJECT_KEY.matcher(out.evidenceObjectKey).matches()) {
            throw badRequest("凭证对象标识无效");
        }
        out.note = normalizeNote(request.getNote(), "人工处理说明不能为空且不得超过 500 字");
        out.requestId = normalizeRequestId(request.getRequestId());
        return out;
    }

    private ManualVerify normalizeVerify(ActivityCancelManualRefundVerifyRequest request) {
        if (request == null) throw badRequest("人工退款复核请求不能为空");
        ManualVerify out = new ManualVerify();
        out.caseId = requireCaseId(request.getCaseId());
        out.expectedVersion = requireVersion(request.getExpectedVersion());
        out.note = normalizeNote(request.getNote(), "复核说明不能为空且不得超过 500 字");
        out.requestId = normalizeRequestId(request.getRequestId());
        return out;
    }

    private Long requireCaseId(Long caseId) {
        requirePositive(caseId, "case 无效");
        return caseId;
    }

    private Long requireVersion(Long version) {
        if (version == null || version < 0 || version == Long.MAX_VALUE) {
            throw badRequest("case 版本无效");
        }
        return version;
    }

    private Correction normalizeCorrection(Long caseId, Long expectedVersion,
                                           String reason, String requestId,
                                           String nullMessage) {
        if (caseId == null && expectedVersion == null && reason == null && requestId == null) {
            throw badRequest(nullMessage);
        }
        Correction out = new Correction();
        out.caseId = requireCaseId(caseId);
        out.expectedVersion = requireVersion(expectedVersion);
        out.reason = normalizeNote(reason, "纠错原因不能为空且不得超过 500 字");
        out.requestId = normalizeRequestId(requestId);
        return out;
    }

    private String normalizeNote(String value, String message) {
        String normalized = value == null ? "" : value.trim();
        requireText(normalized, 500, message);
        return normalized;
    }

    private String normalizeRequestId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!REQUEST_ID.matcher(normalized).matches()) throw badRequest("请求标识无效");
        return normalized;
    }

    /** FX-05:工单恰好挂一个对象 —— 场次(activity_id)或主题(topic_id),与库上 identity CHECK 同口径。 */
    private void requireExactlyOneOwner(ActivityCancelManualRefundCase row, boolean stored) {
        boolean activity = row.getActivityId() != null;
        boolean topic = row.getTopicId() != null;
        if (activity == topic) {
            throw stored ? corrupt("工单归属快照损坏") : badRequest("工单必须恰好挂一个场次或主题");
        }
        requirePositive(activity ? row.getActivityId() : row.getTopicId(),
                stored ? "工单归属快照损坏" : (activity ? "活动无效" : "主题无效"));
    }

    /** 审计 relation_id:场次工单记场次 id,主题工单记主题 id。 */
    private static Long relationId(ActivityCancelManualRefundCase row) {
        return row.getActivityId() != null ? row.getActivityId() : row.getTopicId();
    }

    private void requirePositive(Long value, String message) {
        if (value == null || value <= 0) throw badRequest(message);
    }

    private void requireText(String value, int max, String message) {
        if (value == null || value.trim().isEmpty() || value.length() > max) {
            throw badRequest(message);
        }
    }

    private ServiceException badRequest(String message) { return new ServiceException(message, 400); }
    private ServiceException conflict(String message) { return new ServiceException(message, 409); }
    private ServiceException corrupt(String message) { return new ServiceException(message, 500); }

    private static final class Assessment {
        private Long caseId;
        private Long expectedVersion;
        private String decision;
        private Long amountFen;
        private String note;
        private String requestId;
    }

    private static final class ManualHandle {
        private Long caseId;
        private Long expectedVersion;
        private Long actualAmountFen;
        private String channel;
        private String evidenceObjectKey;
        private String note;
        private String requestId;
    }

    private static final class ManualVerify {
        private Long caseId;
        private Long expectedVersion;
        private String note;
        private String requestId;
    }

    private static final class EvidenceUploadRequest {
        private Long uploaderUserId;
        private Long caseId;
        private Long expectedVersion;
        private String requestId;
        private String objectKey;
        private String versionId;
    }

    private static final class EvidenceUpload {
        private String objectKey;
        private String versionId;
        private String sha256;
    }

    private static final class Correction {
        private Long caseId;
        private Long expectedVersion;
        private String reason;
        private String requestId;
    }
}
