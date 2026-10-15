package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.CommunityReportAppeal;
import com.chengyinhub.business.domain.CommunityReportCase;
import com.chengyinhub.business.domain.CommunityReportTimeline;
import com.chengyinhub.business.domain.ContributionRewardLedger;
import com.chengyinhub.business.domain.MerchantContribution;
import com.chengyinhub.business.domain.MerchantContributionReview;
import com.chengyinhub.business.domain.MerchantContributionSupplement;
import com.chengyinhub.business.domain.MerchantSafetyAggregate;
import com.chengyinhub.business.domain.MerchantSafetyDimensionAggregate;
import com.chengyinhub.business.domain.MerchantSafetyRating;
import com.chengyinhub.business.domain.MerchantSafetyRatingDimension;
import com.chengyinhub.business.domain.MerchantSafetyPolicy;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.dto.CommunityReportAppealRequest;
import com.chengyinhub.business.domain.dto.CommunityReportStageRequest;
import com.chengyinhub.business.domain.dto.CommunityReportSubmitRequest;
import com.chengyinhub.business.domain.dto.MerchantContributionReviewRequest;
import com.chengyinhub.business.domain.dto.MerchantContributionSubmitRequest;
import com.chengyinhub.business.domain.dto.MerchantContributionSupplementRequest;
import com.chengyinhub.business.domain.dto.MerchantSafetyRatingRequest;
import com.chengyinhub.business.domain.vo.CommunityReportSubjectVO;
import com.chengyinhub.business.domain.vo.CommunityReportVO;
import com.chengyinhub.business.domain.vo.CommunityReportPlatformVO;
import com.chengyinhub.business.domain.vo.MerchantContributionVO;
import com.chengyinhub.business.domain.vo.MerchantSafetySummaryVO;
import com.chengyinhub.business.mapper.CommunityTrustMapper;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.CommunityTrustService;
import com.chengyinhub.business.service.support.CommunityReportPolicy;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class CommunityTrustServiceImpl implements CommunityTrustService {
    private static final Set<String> REPORT_TARGETS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            CommunityReportCase.TARGET_MEMBER, CommunityReportCase.TARGET_MERCHANT,
            CommunityReportCase.TARGET_CLUB, CommunityReportCase.TARGET_ACTIVITY,
            CommunityReportCase.TARGET_CONTENT, CommunityReportCase.TARGET_COMMENT,
            CommunityReportCase.TARGET_SAFETY_RATING)));
    private static final Set<String> REPORT_REASONS = CommunityReportPolicy.codes();
    private static final Set<String> REPORT_STAGES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "SUBMITTED", "TRIAGED", "INVESTIGATING", "RESOLVED", "CLOSED", "APPEALED")));
    private static final Set<String> CONTRIBUTION_FIELDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "address", "businessTime", "description", "website")));
    private static final Set<String> ISSUE_TYPES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "CLOSED", "ADDRESS", "BUSINESS_TIME", "CONTENT", "OTHER")));
    private static final Set<String> CONTRIBUTION_STATUSES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "PENDING", "NEEDS_EVIDENCE", "APPROVED", "REJECTED", "MERGED")));
    private static final Set<String> SAFETY_LEVELS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            MerchantSafetyRating.LEVEL_SAFE, MerchantSafetyRating.LEVEL_NEUTRAL,
            MerchantSafetyRating.LEVEL_UNSAFE)));

    @Autowired private CommunityTrustMapper communityTrustMapper;
    @Autowired private CommunityPostMapper communityPostMapper;
    @Autowired private MmsMerchantMapper mmsMerchantMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommunityReportCase submitReport(Long principalMemberId, CommunityReportSubmitRequest request) {
        requirePrincipal(principalMemberId);
        if (request == null) throw badRequest("缺少举报参数");
        String targetType = upper(request.getTargetType());
        if (!REPORT_TARGETS.contains(targetType) || !positive(request.getTargetId())) {
            throw badRequest("举报对象不合法");
        }
        if (CommunityReportCase.TARGET_MEMBER.equals(targetType)
                && principalMemberId.equals(request.getTargetId())) {
            throw badRequest("不能举报自己");
        }
        String reasonCode = upper(request.getReasonCode());
        if (!REPORT_REASONS.contains(reasonCode)) throw badRequest("举报原因不合法");
        CommunityReportPolicy.Reason policyReason = CommunityReportPolicy.find(reasonCode);
        String policyVersion = request.getPolicyVersion();
        if (policyVersion == null || policyVersion.trim().isEmpty()) {
            policyVersion = CommunityReportPolicy.VERSION;
        } else {
            policyVersion = policyVersion.trim();
        }
        String description = requireText(request.getDescription(), 2000, "举报说明不能为空");
        List<Long> evidence = normalizeEvidence(request.getEvidenceAssetIds());
        String requestId = requireKey(request.getRequestId(), "举报幂等键不能为空");
        String legacyPayloadHash = hash(targetType + "|" + request.getTargetId() + "|" + reasonCode
                + "|" + description + "|" + JSON.toJSONString(evidence));
        String payloadHash = hash(targetType + "|" + request.getTargetId() + "|" + reasonCode
                + "|" + policyVersion
                + "|" + description + "|" + JSON.toJSONString(evidence));
        CommunityReportCase replay = communityTrustMapper.selectReportByRequestForUpdate(
                principalMemberId, requestId);
        if (replay != null) {
            boolean legacyWindowReplay = (replay.getPolicyVersion() == null
                    || replay.getPolicyVersion().trim().isEmpty()
                    || CommunityReportPolicy.LEGACY_VERSION.equals(replay.getPolicyVersion()))
                    && legacyPayloadHash.equals(replay.getPayloadHash());
            if (!payloadHash.equals(replay.getPayloadHash()) && !legacyWindowReplay) {
                throw conflict("幂等键已用于其他请求");
            }
            return replay;
        }
        if (!CommunityReportPolicy.VERSION.equals(policyVersion)) {
            throw badRequest("举报规则版本已更新，请刷新后重试");
        }

        Date now = new Date();
        CommunityReportCase row = new CommunityReportCase();
        row.setReporterMemberId(principalMemberId);
        row.setTargetType(targetType);
        row.setTargetId(request.getTargetId());
        row.setReasonCode(reasonCode);
        row.setPolicyVersion(policyVersion);
        row.setReasonLabelSnapshot(policyReason.getLabel());
        row.setPrioritySnapshot(policyReason.getPriority());
        row.setFirstResponseMinutesSnapshot(policyReason.getFirstResponseMinutes());
        row.setDescription(description);
        row.setEvidenceAssetIdsJson(JSON.toJSONString(evidence));
        row.setAnonymityPolicy(CommunityReportCase.ANONYMITY_SUBJECT_HIDDEN);
        row.setStage(CommunityReportCase.STAGE_SUBMITTED);
        row.setRequestId(requestId);
        row.setPayloadHash(payloadHash);
        row.setVersion(0);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        if (communityTrustMapper.insertReport(row) != 1 || !positive(row.getId())) {
            throw conflict("举报受理失败，请重试");
        }
        CommunityReportTimeline timeline = timeline(row.getId(), "SUBMIT:" + requestId,
                hash("SUBMIT|" + payloadHash),
                "REPORTER", "SUBMITTED", null, CommunityReportCase.STAGE_SUBMITTED,
                "举报已受理", null, now);
        if (communityTrustMapper.insertReportTimeline(timeline) != 1) {
            throw conflict("举报时间线写入失败");
        }
        enqueueReportProgress(row, "REPORT_RECEIVED", "举报已受理", now);
        return row;
    }

    @Override
    public Map<String, Object> getReportTaxonomy() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("policyVersion", CommunityReportPolicy.VERSION);
        result.put("targetTypes", Arrays.asList("MEMBER", "MERCHANT", "CLUB", "ACTIVITY", "CONTENT", "COMMENT", "SAFETY_RATING"));
        result.put("reasonCodes", CommunityReportPolicy.orderedCodes());
        result.put("reasons", CommunityReportPolicy.publicReasons());
        result.put("anonymityPolicy", CommunityReportCase.ANONYMITY_SUBJECT_HIDDEN);
        result.put("publicStages", Arrays.asList("RECEIVED", "IN_REVIEW", "DECIDED"));
        result.put("emergencyReasonCodes", CommunityReportPolicy.emergencyCodes());
        result.put("emergencyGuidanceCode", "CONTACT_LOCAL_EMERGENCY_SERVICES");
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommunityReportCase appealReport(Long principalMemberId, CommunityReportAppealRequest request) {
        requirePrincipal(principalMemberId);
        if (request == null || !positive(request.getReportId())) throw badRequest("申诉参数不完整");
        String reason = requireText(request.getReason(), 1000, "申诉说明不能为空");
        String requestId = requireKey(request.getRequestId(), "申诉幂等键不能为空");
        String payloadHash = hash(request.getReportId() + "|" + reason);
        CommunityReportCase row = communityTrustMapper.selectReportForOwnerForUpdate(
                request.getReportId(), principalMemberId);
        String actorType = "REPORTER";
        if (row == null) {
            CommunityReportSubjectVO subject = communityTrustMapper.selectSubjectReportForUpdate(
                    request.getReportId(), principalMemberId);
            if (subject == null) throw forbidden();
            row = new CommunityReportCase();
            row.setId(subject.getId());
            row.setStage(subject.getStage());
            row.setVersion(subject.getVersion());
            actorType = "SUBJECT";
        }
        CommunityReportAppeal replay = communityTrustMapper.selectAppealByRequestForUpdate(
                principalMemberId, requestId);
        if (replay != null) {
            if (!request.getReportId().equals(replay.getReportId())
                    || !payloadHash.equals(replay.getPayloadHash())) throw conflict("申诉幂等键已用于其他请求");
            return row;
        }
        String from = row.getStage();
        if (!CommunityReportCase.STAGE_RESOLVED.equals(from)
                && !CommunityReportCase.STAGE_CLOSED.equals(from)) {
            throw conflict("当前举报阶段不允许申诉");
        }
        if (communityTrustMapper.appealReportCas(row.getId(), from,
                CommunityReportCase.STAGE_APPEALED, row.getVersion()) != 1) {
            throw conflict("举报阶段已变化，请刷新");
        }
        Date now = new Date();
        CommunityReportAppeal appeal = new CommunityReportAppeal();
        appeal.setReportId(row.getId());
        appeal.setAppellantMemberId(principalMemberId);
        appeal.setReason(reason);
        appeal.setRequestId(requestId);
        appeal.setPayloadHash(payloadHash);
        appeal.setCreateTime(now);
        if (communityTrustMapper.insertReportAppeal(appeal) != 1) throw conflict("申诉受理失败");
        if (communityTrustMapper.insertReportTimeline(timeline(row.getId(), "APPEAL:" + requestId,
                payloadHash,
                actorType,
                "APPEALED", from, CommunityReportCase.STAGE_APPEALED,
                "申诉已受理", null, now)) != 1) throw conflict("申诉时间线写入失败");
        row.setStage(CommunityReportCase.STAGE_APPEALED);
        row.setVersion(row.getVersion() + 1);
        return row;
    }

    @Override
    public List<CommunityReportVO> listMyReports(Long principalMemberId, String stage) {
        return listMyReports(principalMemberId, stage, null);
    }

    @Override
    public List<CommunityReportVO> listMyReports(Long principalMemberId, String stage, String requestId) {
        requirePrincipal(principalMemberId);
        String normalizedStage = optionalUpper(stage);
        if (normalizedStage != null && !REPORT_STAGES.contains(normalizedStage)) {
            throw badRequest("举报阶段筛选不合法");
        }
        List<CommunityReportVO> result = new ArrayList<>();
        String normalizedRequestId = requestId == null ? null : requireKey(requestId, "举报幂等键无效");
        for (CommunityReportCase row : communityTrustMapper.selectReportsForOwner(
                principalMemberId, normalizedStage, normalizedRequestId)) {
            result.add(CommunityReportVO.from(row));
        }
        return result;
    }

    @Override
    public CommunityReportVO getMyReport(Long principalMemberId, Long reportId) {
        requirePrincipal(principalMemberId);
        if (!positive(reportId)) throw badRequest("举报编号不合法");
        CommunityReportCase row = communityTrustMapper.selectReportForOwner(reportId, principalMemberId);
        if (row == null) throw forbidden();
        CommunityReportVO vo = CommunityReportVO.from(row);
        vo.setTimeline(communityTrustMapper.selectPublicTimeline(reportId));
        return vo;
    }

    @Override
    public CommunityReportSubjectVO getSubjectReport(Long principalMemberId, Long reportId) {
        requirePrincipal(principalMemberId);
        if (!positive(reportId)) throw badRequest("举报编号不合法");
        CommunityReportSubjectVO vo = communityTrustMapper.selectSubjectReport(reportId, principalMemberId);
        if (vo == null) throw forbidden();
        vo.setPublicStage(CommunityReportVO.publicStage(vo.getStage()));
        vo.setTimeline(communityTrustMapper.selectPublicTimeline(reportId));
        return vo;
    }

    @Override
    public List<CommunityReportPlatformVO> listPlatformReports(String stage, String targetType) {
        String normalizedStage = optionalUpper(stage);
        String normalizedTarget = optionalUpper(targetType);
        if (normalizedStage != null && !REPORT_STAGES.contains(normalizedStage)) throw badRequest("举报阶段筛选不合法");
        if (normalizedTarget != null && !REPORT_TARGETS.contains(normalizedTarget)) throw badRequest("举报对象筛选不合法");
        List<CommunityReportPlatformVO> rows = new ArrayList<>(
                communityTrustMapper.selectPlatformReports(normalizedStage, normalizedTarget));
        Date now = new Date();
        for (CommunityReportPlatformVO row : rows) CommunityReportPolicy.enrich(row, now);
        rows.sort((left, right) -> {
            int priority = Integer.compare(reportPriorityRank(left), reportPriorityRank(right));
            if (priority != 0) return priority;
            int overdue = Boolean.compare(!Boolean.TRUE.equals(left.getFirstResponseOverdue()),
                    !Boolean.TRUE.equals(right.getFirstResponseOverdue()));
            if (overdue != 0) return overdue;
            int deadline = compareNullableDates(left.getFirstResponseDueAt(), right.getFirstResponseDueAt());
            if (deadline != 0) return deadline;
            return compareNullableLongs(left.getId(), right.getId());
        });
        return rows;
    }

    private static int reportPriorityRank(CommunityReportPlatformVO row) {
        return row != null && CommunityReportPolicy.PRIORITY_URGENT.equals(row.getPriority()) ? 0 : 1;
    }

    private static int compareNullableDates(Date left, Date right) {
        if (left == right) return 0;
        if (left == null) return 1;
        if (right == null) return -1;
        return left.compareTo(right);
    }

    private static int compareNullableLongs(Long left, Long right) {
        if (left == right) return 0;
        if (left == null) return 1;
        if (right == null) return -1;
        return left.compareTo(right);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommunityReportCase transitionReport(Long operatorUserId, CommunityReportStageRequest request) {
        if (!positive(operatorUserId) || request == null || !positive(request.getReportId())
                || request.getExpectedVersion() == null || request.getExpectedVersion() < 0) {
            throw badRequest("举报阶段参数不完整");
        }
        String requestId = requireKey(request.getRequestId(), "阶段变更幂等键不能为空");
        String eventKey = "STAGE:" + requestId;
        String next = upper(request.getNextStage());
        String publicNote = requireText(request.getPublicNote(), 500, "公开进度说明不能为空");
        String internalNote = optionalText(request.getInternalNote(), 1000);
        String decisionSummary = optionalText(request.getDecisionSummary(), 500);
        String transitionHash = hash(request.getReportId() + "|" + request.getExpectedVersion()
                + "|" + next + "|" + publicNote + "|" + internalNote + "|" + decisionSummary
                + "|" + dateMillis(request.getEstimatedResolveAt()));
        CommunityReportCase row = communityTrustMapper.selectReportForUpdate(request.getReportId());
        if (row == null) throw badRequest("举报不存在");
        CommunityReportTimeline replay = communityTrustMapper.selectTimelineByEventKeyForUpdate(
                row.getId(), eventKey);
        if (replay != null) {
            if (!transitionHash.equals(replay.getPayloadHash())) throw conflict("阶段幂等键已用于其他变更");
            return row;
        }
        if (!allowedStageTransition(row.getStage(), next)) throw conflict("举报阶段跳变不合法");
        if (!request.getExpectedVersion().equals(row.getVersion())) throw conflict("举报版本已变化");
        if (communityTrustMapper.transitionReportCas(row.getId(), row.getStage(), next,
                request.getExpectedVersion(), decisionSummary, request.getEstimatedResolveAt()) != 1) {
            throw conflict("举报阶段已变化，请刷新");
        }
        if (communityTrustMapper.insertReportTimeline(timeline(row.getId(), eventKey, transitionHash, "PLATFORM",
                "STAGE_CHANGED", row.getStage(), next, publicNote, internalNote, new Date())) != 1) {
            throw conflict("举报时间线写入失败");
        }
        row.setStage(next);
        row.setDecisionSummary(decisionSummary);
        row.setEstimatedResolveAt(request.getEstimatedResolveAt());
        row.setVersion(row.getVersion() + 1);
        enqueueReportProgress(row, "REPORT_STAGE_" + next, publicNote, new Date());
        return row;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantContribution submitContribution(Long principalMemberId,
                                                     MerchantContributionSubmitRequest request) {
        requirePrincipal(principalMemberId);
        if (request == null || !positive(request.getMerchantId())) throw badRequest("贡献参数不完整");
        MmsMerchant currentMerchant = mmsMerchantMapper.selectMmsMerchantById(request.getMerchantId());
        if (!activeMerchant(currentMerchant)) {
            throw badRequest("商家不存在");
        }
        String type = upper(request.getContributionType());
        if (!MerchantContribution.TYPE_INFO_UPDATE.equals(type)
                && !MerchantContribution.TYPE_ISSUE_REPORT.equals(type)) {
            throw badRequest("贡献类型不合法");
        }
        TreeMap<String, String> changes = normalizeContributionFields(request.getFieldChanges());
        String issueType = optionalUpper(request.getIssueType());
        if (MerchantContribution.TYPE_INFO_UPDATE.equals(type) && changes.isEmpty()) {
            throw badRequest("信息补充至少包含一个字段");
        }
        if (MerchantContribution.TYPE_ISSUE_REPORT.equals(type) && !ISSUE_TYPES.contains(issueType)) {
            throw badRequest("问题类型不合法");
        }
        String description = requireText(request.getDescription(), 2000, "贡献说明不能为空");
        List<Long> evidence = normalizeEvidence(request.getEvidenceAssetIds());
        String requestId = requireKey(request.getIdempotencyKey(), "贡献幂等键不能为空");
        String fieldJson = JSON.toJSONString(changes);
        String evidenceJson = JSON.toJSONString(evidence);
        String payloadHash = hash(request.getMerchantId() + "|" + type + "|" + fieldJson
                + "|" + issueType + "|" + description + "|" + evidenceJson);
        MerchantContribution replay = communityTrustMapper.selectContributionByRequestForUpdate(
                principalMemberId, requestId);
        if (replay != null) return samePayload(replay.getPayloadHash(), payloadHash, replay);
        String scopeKey = hash("MERCHANT_CONTRIBUTION|" + request.getMerchantId());
        if (communityTrustMapper.selectContributionByScopeForUpdate(
                principalMemberId, request.getMerchantId(), scopeKey) != null) {
            throw conflict("你已提交过该商家的贡献，请在原记录补证");
        }
        Date now = new Date();
        MerchantContribution row = new MerchantContribution();
        row.setMerchantId(request.getMerchantId());
        row.setContributorMemberId(principalMemberId);
        row.setContributionType(type);
        row.setFieldChangesJson(fieldJson);
        row.setBaseValuesJson(JSON.toJSONString(snapshotMerchantFields(currentMerchant, changes.keySet())));
        row.setIssueType(issueType);
        row.setDescription(description);
        row.setEvidenceAssetIdsJson(evidenceJson);
        row.setIdempotencyKey(requestId);
        row.setScopeKey(scopeKey);
        row.setPayloadHash(payloadHash);
        row.setReviewStatus(MerchantContribution.STATUS_PENDING);
        row.setCanSupplement(false);
        row.setVersion(0);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        if (communityTrustMapper.insertContribution(row) != 1 || !positive(row.getId())) {
            throw conflict("贡献受理失败，请重试");
        }
        return row;
    }

    @Override
    public Map<String, Object> getContributionTaxonomy() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contributionTypes", Arrays.asList("INFO_UPDATE", "ISSUE_REPORT"));
        result.put("fieldCodes", Arrays.asList("address", "businessTime", "description", "website"));
        result.put("issueTypes", Arrays.asList("CLOSED", "ADDRESS", "BUSINESS_TIME", "CONTENT", "OTHER"));
        result.put("reviewStatuses", Arrays.asList("PENDING", "APPROVED", "REJECTED", "NEEDS_EVIDENCE", "MERGED"));
        result.put("rewardStatuses", Arrays.asList("PENDING_REVIEW", "PROVISIONAL", "CREDITED", "VOIDED", "NOT_CREDITED"));
        result.put("rewardCreditPolicy", "AFTER_REVIEW");
        result.put("rewardPointsKnown", false);
        return result;
    }

    @Override
    public List<MerchantContributionVO> listMyContributions(Long principalMemberId) {
        requirePrincipal(principalMemberId);
        List<MerchantContributionVO> result = new ArrayList<>();
        for (MerchantContribution row : communityTrustMapper.selectContributionsForOwner(principalMemberId)) {
            result.add(MerchantContributionVO.from(row,
                    communityTrustMapper.selectRewardLedgerByContribution(row.getId())));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantContribution supplementContribution(Long principalMemberId,
                                                        MerchantContributionSupplementRequest request) {
        requirePrincipal(principalMemberId);
        if (request == null || !positive(request.getContributionId())
                || request.getExpectedVersion() == null || request.getExpectedVersion() < 0) {
            throw badRequest("补证参数不完整");
        }
        String description = requireText(request.getDescription(), 2000, "补证说明不能为空");
        List<Long> evidence = normalizeEvidence(request.getEvidenceAssetIds());
        String requestId = requireKey(request.getRequestId(), "补证幂等键不能为空");
        String evidenceJson = JSON.toJSONString(evidence);
        String payloadHash = hash(request.getContributionId() + "|" + request.getExpectedVersion()
                + "|" + description + "|" + evidenceJson);
        MerchantContributionSupplement replay =
                communityTrustMapper.selectContributionSupplementByRequestForUpdate(
                        principalMemberId, requestId);
        if (replay != null) {
            if (!request.getContributionId().equals(replay.getContributionId())
                    || !request.getExpectedVersion().equals(replay.getExpectedVersion())
                    || !payloadHash.equals(replay.getPayloadHash())) {
                throw conflict("补证幂等键已用于其他请求");
            }
            MerchantContribution receipt = communityTrustMapper.selectContributionForOwner(
                    request.getContributionId(), principalMemberId);
            if (receipt == null) throw forbidden();
            return receipt;
        }
        MerchantContribution row = communityTrustMapper.selectContributionForOwnerForUpdate(
                request.getContributionId(), principalMemberId);
        if (row == null) throw forbidden();
        if (!Boolean.TRUE.equals(row.getCanSupplement())
                || !(MerchantContribution.STATUS_NEEDS_EVIDENCE.equals(row.getReviewStatus())
                || MerchantContribution.STATUS_REJECTED.equals(row.getReviewStatus()))) {
            throw conflict("当前贡献状态不可补证");
        }
        Date now = new Date();
        if (communityTrustMapper.supplementContributionCas(row.getId(), row.getReviewStatus(),
                request.getExpectedVersion(), description, evidenceJson, now) != 1) {
            throw conflict("贡献状态已变化，请刷新");
        }
        MerchantContributionSupplement supplement = new MerchantContributionSupplement();
        supplement.setContributionId(row.getId());
        supplement.setContributorMemberId(principalMemberId);
        supplement.setDescription(description);
        supplement.setEvidenceAssetIdsJson(evidenceJson);
        supplement.setRequestId(requestId);
        supplement.setPayloadHash(payloadHash);
        supplement.setExpectedVersion(request.getExpectedVersion());
        supplement.setCreateTime(now);
        if (communityTrustMapper.insertContributionSupplement(supplement) != 1) {
            throw conflict("贡献补证记录写入失败");
        }
        row.setDescription(description);
        row.setEvidenceAssetIdsJson(evidenceJson);
        row.setReviewStatus(MerchantContribution.STATUS_PENDING);
        row.setReviewReason(null);
        row.setCanSupplement(false);
        row.setVersion(row.getVersion() + 1);
        row.setUpdateTime(now);
        return row;
    }

    @Override
    public MerchantContributionVO getMyContribution(Long principalMemberId, Long contributionId) {
        requirePrincipal(principalMemberId);
        if (!positive(contributionId)) throw badRequest("贡献编号不合法");
        MerchantContribution row = communityTrustMapper.selectContributionForOwner(
                contributionId, principalMemberId);
        if (row == null) throw forbidden();
        return MerchantContributionVO.from(row,
                communityTrustMapper.selectRewardLedgerByContribution(row.getId()));
    }

    @Override
    public List<MerchantContribution> listPlatformContributions(String status, Long merchantId) {
        String normalizedStatus = optionalUpper(status);
        if (normalizedStatus != null && !CONTRIBUTION_STATUSES.contains(normalizedStatus)) {
            throw badRequest("贡献状态筛选不合法");
        }
        return communityTrustMapper.selectPlatformContributions(normalizedStatus, merchantId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantContribution reviewContribution(Long reviewerUserId,
                                                     MerchantContributionReviewRequest request) {
        if (!positive(reviewerUserId) || request == null || !positive(request.getContributionId())
                || request.getExpectedVersion() == null || request.getExpectedVersion() < 0) {
            throw badRequest("贡献审核参数不完整");
        }
        String action = upper(request.getAction());
        String reason = requireText(request.getReason(), 500, "审核原因不能为空");
        String requestId = requireKey(request.getRequestId(), "审核幂等键不能为空");
        if (request.getRewardPoints() != null) {
            throw conflict("奖励口径尚未冻结，本批禁止接入真实积分发放");
        }
        String reviewHash = hash(request.getContributionId() + "|" + request.getExpectedVersion()
                + "|" + action + "|" + reason);
        MerchantContribution row = communityTrustMapper.selectContributionForUpdate(request.getContributionId());
        if (row == null) throw badRequest("贡献记录不存在");
        MerchantContributionReview replay = communityTrustMapper.selectContributionReviewByRequestForUpdate(
                reviewerUserId, requestId);
        if (replay != null) {
            if (!row.getId().equals(replay.getContributionId())
                    || !reviewHash.equals(replay.getPayloadHash())) {
                throw conflict("审核幂等键已用于其他操作");
            }
            return row;
        }
        if (!request.getExpectedVersion().equals(row.getVersion())) throw conflict("贡献版本已变化");
        if (MerchantContributionReviewRequest.ACTION_MERGE.equals(action)) {
            return mergeContribution(reviewerUserId, request, row, reason, requestId, reviewHash);
        }
        String next;
        boolean canSupplement;
        if (MerchantContributionReviewRequest.ACTION_APPROVE.equals(action)) {
            next = MerchantContribution.STATUS_APPROVED;
            canSupplement = false;
        } else if (MerchantContributionReviewRequest.ACTION_REJECT.equals(action)) {
            next = MerchantContribution.STATUS_REJECTED;
            canSupplement = true;
        } else if (MerchantContributionReviewRequest.ACTION_NEEDS_EVIDENCE.equals(action)) {
            next = MerchantContribution.STATUS_NEEDS_EVIDENCE;
            canSupplement = true;
        } else {
            throw badRequest("审核动作不合法");
        }
        if (!MerchantContribution.STATUS_PENDING.equals(row.getReviewStatus())
                && !MerchantContribution.STATUS_NEEDS_EVIDENCE.equals(row.getReviewStatus())) {
            throw conflict("当前贡献状态不可审核");
        }
        if (communityTrustMapper.reviewContributionCas(row.getId(), row.getReviewStatus(), next,
                row.getVersion(), reviewerUserId, reason, canSupplement) != 1) {
            throw conflict("贡献状态已变化，请刷新");
        }
        insertReview(row, reviewerUserId, action, reason, requestId, reviewHash,
                request.getExpectedVersion());
        if (MerchantContributionReviewRequest.ACTION_APPROVE.equals(action)) {
            insertReward(row, ContributionRewardLedger.STATUS_PROVISIONAL);
        } else if (MerchantContributionReviewRequest.ACTION_REJECT.equals(action)) {
            insertReward(row, ContributionRewardLedger.STATUS_VOIDED);
        }
        row.setReviewStatus(next);
        row.setReviewReason(reason);
        row.setCanSupplement(canSupplement);
        row.setVersion(row.getVersion() + 1);
        return row;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantSafetyRating submitSafetyRating(Long principalMemberId,
                                                    MerchantSafetyRatingRequest request) {
        requirePrincipal(principalMemberId);
        if (request == null || !positive(request.getMerchantId())) throw badRequest("安全评分参数不完整");
        MmsMerchant merchant = mmsMerchantMapper.selectMmsMerchantById(request.getMerchantId());
        if (!activeMerchant(merchant)) {
            throw badRequest("商家不存在");
        }
        Long eligibilitySourceId = communityTrustMapper.selectVerifiedVisitForSafety(
                principalMemberId, request.getMerchantId());
        if (!positive(eligibilitySourceId)) {
            throw forbidden("仅真实核销到店记录可提交安全感评分");
        }
        String overallLevel = upper(request.getOverallLevel());
        if (!SAFETY_LEVELS.contains(overallLevel)) throw badRequest("安全档位不合法");
        String reason = requireText(request.getReason(), 1000, "安全评分理由不能为空");
        String requestId = requireKey(request.getIdempotencyKey(), "安全评分幂等键不能为空");
        String period = currentUtcPeriod();
        String payloadHash = hash(request.getMerchantId() + "|" + overallLevel + "|" + reason
                + "|" + period);
        MerchantSafetyRating replay = communityTrustMapper.selectSafetyRatingByRequestForUpdate(
                principalMemberId, requestId);
        if (replay != null) return samePayload(replay.getPayloadHash(), payloadHash, replay);
        if (communityTrustMapper.selectSafetyRatingByPeriodForUpdate(
                principalMemberId, request.getMerchantId(), period) != null) {
            throw conflict("本自然月已评价该商家，请勿重复提交");
        }
        MerchantSafetyRating row = new MerchantSafetyRating();
        row.setMemberId(principalMemberId);
        row.setMerchantId(request.getMerchantId());
        row.setOverallLevel(overallLevel);
        row.setReason(reason);
        row.setIdempotencyKey(requestId);
        row.setPayloadHash(payloadHash);
        row.setRatingPeriod(period);
        row.setEligibilityType("VERIFIED_VISIT");
        row.setEligibilitySourceId(eligibilitySourceId);
        row.setCreateTime(new Date());
        if (communityTrustMapper.insertSafetyRating(row) != 1 || !positive(row.getId())) {
            throw conflict("安全评分受理失败，请重试");
        }
        MerchantSafetyRatingDimension dimension = new MerchantSafetyRatingDimension();
        dimension.setRatingId(row.getId());
        dimension.setDimensionCode(MerchantSafetyRating.DIMENSION_OVERALL);
        dimension.setLevel(overallLevel);
        if (communityTrustMapper.insertSafetyRatingDimension(dimension) != 1) {
            throw conflict("安全评分维度写入失败");
        }
        return row;
    }

    @Override
    public MerchantSafetySummaryVO getSafetySummary(Long merchantId) {
        if (!positive(merchantId)) throw badRequest("商家编号不合法");
        if (!activeMerchant(mmsMerchantMapper.selectMmsMerchantById(merchantId))) {
            throw badRequest("商家不存在");
        }
        MerchantSafetyPolicy policy = communityTrustMapper.selectActiveSafetyPolicy();
        if (policy == null) {
            MerchantSafetySummaryVO pending = new MerchantSafetySummaryVO();
            pending.setMerchantId(merchantId);
            pending.setSampleStatus("POLICY_PENDING");
            pending.setLevel(null);
            pending.setDimensions(Collections.emptyList());
            pending.setDisclaimerCode("PERCEPTION_NOT_GUARANTEE");
            return pending;
        }
        Date cutoff = cutoffDays(policy.getWindowDays());
        MerchantSafetyAggregate raw = communityTrustMapper.selectSafetyAggregate(merchantId, cutoff);
        int sampleCount = raw == null || raw.getSampleCount() == null ? 0 : raw.getSampleCount();
        MerchantSafetySummaryVO result = new MerchantSafetySummaryVO();
        result.setMerchantId(merchantId);
        result.setSampleCount(sampleCount);
        result.setMinimumSampleCount(policy.getMinimumSampleCount());
        result.setWindowDays(policy.getWindowDays());
        result.setLatestAt(raw == null ? null : raw.getLatestAt());
        result.setDisclaimerCode("PERCEPTION_NOT_GUARANTEE");
        if (sampleCount < policy.getMinimumSampleCount() || raw.getAverageScore() == null) {
            result.setSampleStatus("INSUFFICIENT");
            result.setLevel(null);
            result.setDimensions(Collections.emptyList());
            return result;
        }
        result.setSampleStatus("SUFFICIENT");
        result.setLevel(levelForScore(raw.getAverageScore(), policy));
        List<MerchantSafetySummaryVO.Dimension> dimensions = new ArrayList<>();
        for (MerchantSafetyDimensionAggregate dimension :
                communityTrustMapper.selectSafetyDimensionAggregates(merchantId, cutoff)) {
            if (dimension.getSampleCount() == null
                    || dimension.getSampleCount() < policy.getMinimumSampleCount()
                    || dimension.getAverageScore() == null) continue;
            MerchantSafetySummaryVO.Dimension item = new MerchantSafetySummaryVO.Dimension();
            item.setCode(dimension.getDimensionCode());
            item.setSampleCount(dimension.getSampleCount());
            item.setLevel(levelForScore(dimension.getAverageScore(), policy));
            dimensions.add(item);
        }
        result.setDimensions(dimensions);
        return result;
    }

    @Override
    public Map<String, Object> getSafetyTaxonomy() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dimensionCode", MerchantSafetyRating.DIMENSION_OVERALL);
        result.put("levels", Arrays.asList(MerchantSafetyRating.LEVEL_UNSAFE,
                MerchantSafetyRating.LEVEL_NEUTRAL, MerchantSafetyRating.LEVEL_SAFE));
        MerchantSafetyPolicy policy = communityTrustMapper.selectActiveSafetyPolicy();
        result.put("policyStatus", policy == null ? "PENDING" : "ACTIVE");
        result.put("minimumSampleCount", policy == null ? null : policy.getMinimumSampleCount());
        result.put("windowDays", policy == null ? null : policy.getWindowDays());
        result.put("eligibilityCode", "VERIFIED_VISIT");
        result.put("disclaimerCode", "PERCEPTION_NOT_GUARANTEE");
        return result;
    }

    private MerchantContribution mergeContribution(Long reviewerUserId,
                                                     MerchantContributionReviewRequest request,
                                                     MerchantContribution row,
                                                     String reason, String requestId,
                                                     String reviewHash) {
        if (!MerchantContribution.STATUS_APPROVED.equals(row.getReviewStatus())) {
            throw conflict("只有已通过贡献可以合并");
        }
        if (MerchantContribution.TYPE_ISSUE_REPORT.equals(row.getContributionType())) {
            throw conflict("问题上报尚未冻结处置动作，不得标记为已合并");
        }
        if (communityTrustMapper.selectMerchantIdForUpdate(row.getMerchantId()) == null) {
            throw conflict("商家已不存在或不可更新");
        }
        if (communityTrustMapper.mergeContributionCas(row.getId(), row.getVersion()) != 1) {
            throw conflict("贡献状态已变化，请刷新");
        }
        Map<String, String> changes = JSON.parseObject(row.getFieldChangesJson(), Map.class);
        if (changes != null && !changes.isEmpty()) {
            MmsMerchant current = mmsMerchantMapper.selectMmsMerchantById(row.getMerchantId());
            Map<String, String> base = JSON.parseObject(row.getBaseValuesJson(), Map.class);
            if (!snapshotMerchantFields(current, changes.keySet()).equals(base)) {
                throw conflict("商家资料已变化，请重新核对贡献后再合并");
            }
            MmsMerchant merchant = new MmsMerchant();
            merchant.setId(row.getMerchantId());
            for (Map.Entry<String, String> change : changes.entrySet()) {
                applyMerchantField(merchant, change.getKey(), change.getValue());
            }
            if (mmsMerchantMapper.updateMmsMerchant(merchant) != 1) {
                throw conflict("商家资料合并失败");
            }
        }
        insertReview(row, reviewerUserId, MerchantContributionReviewRequest.ACTION_MERGE,
                reason, requestId, reviewHash, request.getExpectedVersion());
        row.setReviewStatus(MerchantContribution.STATUS_MERGED);
        row.setVersion(row.getVersion() + 1);
        return row;
    }

    private void insertReview(MerchantContribution row, Long reviewerUserId, String action,
                              String reason, String requestId, String payloadHash,
                              Integer expectedVersion) {
        MerchantContributionReview review = new MerchantContributionReview();
        review.setContributionId(row.getId());
        review.setReviewerUserId(reviewerUserId);
        review.setAction(action);
        review.setReason(reason);
        review.setRewardPoints(null);
        review.setRequestId(requestId);
        review.setPayloadHash(payloadHash);
        review.setExpectedVersion(expectedVersion);
        review.setCreateTime(new Date());
        if (communityTrustMapper.insertContributionReview(review) != 1) {
            throw conflict("贡献审核记录写入失败");
        }
    }

    private void insertReward(MerchantContribution row, String status) {
        if (communityTrustMapper.selectRewardLedgerByContribution(row.getId()) != null) return;
        Date now = new Date();
        ContributionRewardLedger reward = new ContributionRewardLedger();
        reward.setContributionId(row.getId());
        reward.setContributorMemberId(row.getContributorMemberId());
        reward.setBusinessKey("CONTRIBUTION_REWARD:" + row.getId());
        reward.setStatus(status);
        reward.setPoints(null);
        reward.setCreateTime(now);
        reward.setUpdateTime(now);
        if (communityTrustMapper.insertRewardLedger(reward) != 1) {
            throw conflict("贡献奖励状态账写入失败");
        }
    }

    private void applyMerchantField(MmsMerchant merchant, String field, String value) {
        if ("address".equals(field)) merchant.setAddress(value);
        else if ("businessTime".equals(field)) merchant.setBusinessTime(value);
        else if ("description".equals(field)) merchant.setDescription(value);
        else if ("website".equals(field)) merchant.setWebsite(value);
        else throw badRequest("存在不可合并的商家字段");
    }

    private TreeMap<String, String> normalizeContributionFields(Map<String, String> fields) {
        TreeMap<String, String> result = new TreeMap<>();
        if (fields == null) return result;
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (!CONTRIBUTION_FIELDS.contains(entry.getKey())) throw badRequest("贡献字段不在白名单");
            result.put(entry.getKey(), requireText(entry.getValue(), contributionFieldLimit(entry.getKey()),
                    "贡献字段值不能为空"));
        }
        return result;
    }

    private int contributionFieldLimit(String field) {
        if ("address".equals(field)) return 200;
        if ("businessTime".equals(field)) return 50;
        if ("website".equals(field)) return 255;
        return 2000;
    }

    private Map<String, String> snapshotMerchantFields(MmsMerchant merchant, Set<String> fields) {
        Map<String, String> result = new TreeMap<>();
        for (String field : fields) {
            if ("address".equals(field)) result.put(field, merchant.getAddress());
            else if ("businessTime".equals(field)) result.put(field, merchant.getBusinessTime());
            else if ("description".equals(field)) result.put(field, merchant.getDescription());
            else if ("website".equals(field)) result.put(field, merchant.getWebsite());
        }
        return result;
    }

    private boolean activeMerchant(MmsMerchant merchant) {
        return merchant != null && Integer.valueOf(0).equals(merchant.getDelFlag())
                && Long.valueOf(1L).equals(merchant.getStatus());
    }

    private List<Long> normalizeEvidence(List<Long> source) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        throw conflict("证据附件归属与脱敏链路尚未接通，本批拒绝附件并保留纯文字提交");
    }

    private void enqueueReportProgress(CommunityReportCase row, String action, String publicNote, Date now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("recipientId", row.getReporterMemberId());
        payload.put("postId", null);
        payload.put("actorId", 0L);
        payload.put("reportId", row.getId());
        payload.put("action", action);
        payload.put("publicStage", CommunityReportVO.publicStage(row.getStage()));
        payload.put("publicNote", publicNote);
        payload.put("notificationType", "SYSTEM_GOVERNANCE");
        payload.put("aggregateKey", "REPORT:" + row.getId() + ":" + action);
        if (communityPostMapper.insertTypedOutbox(UUID.randomUUID().toString(), "COMMUNITY_NOTIFICATION",
                row.getReporterMemberId(), "NOTIFICATION_REQUESTED", JSON.toJSONString(payload), now) != 1) {
            throw conflict("举报进度通知入队失败");
        }
    }

    private boolean allowedStageTransition(String from, String to) {
        return CommunityReportCase.STAGE_SUBMITTED.equals(from) && CommunityReportCase.STAGE_TRIAGED.equals(to)
                || CommunityReportCase.STAGE_TRIAGED.equals(from) && CommunityReportCase.STAGE_INVESTIGATING.equals(to)
                || CommunityReportCase.STAGE_INVESTIGATING.equals(from) && CommunityReportCase.STAGE_RESOLVED.equals(to)
                || CommunityReportCase.STAGE_RESOLVED.equals(from) && CommunityReportCase.STAGE_CLOSED.equals(to)
                || CommunityReportCase.STAGE_APPEALED.equals(from) && CommunityReportCase.STAGE_INVESTIGATING.equals(to);
    }

    private CommunityReportTimeline timeline(Long reportId, String eventKey, String payloadHash,
                                               String actorType,
                                               String eventType, String fromStage, String toStage,
                                               String publicNote, String internalNote, Date now) {
        CommunityReportTimeline row = new CommunityReportTimeline();
        row.setReportId(reportId);
        row.setEventKey(eventKey);
        row.setPayloadHash(payloadHash);
        row.setActorType(actorType);
        row.setEventType(eventType);
        row.setFromStage(fromStage);
        row.setToStage(toStage);
        row.setPublicNote(publicNote);
        row.setInternalNote(internalNote);
        row.setCreateTime(now);
        return row;
    }

    private long dateMillis(Date value) {
        return value == null ? 0L : value.getTime();
    }

    private String levelForScore(Double score, MerchantSafetyPolicy policy) {
        if (score >= policy.getSafeMinimumScore()) return MerchantSafetyRating.LEVEL_SAFE;
        if (score >= policy.getNeutralMinimumScore()) return MerchantSafetyRating.LEVEL_NEUTRAL;
        return MerchantSafetyRating.LEVEL_UNSAFE;
    }

    private String currentUtcPeriod() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMM");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private Date cutoffDays(int days) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.add(Calendar.DAY_OF_YEAR, -days);
        return calendar.getTime();
    }

    private String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : bytes) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private <T> T samePayload(String existingHash, String expectedHash, T receipt) {
        if (!expectedHash.equals(existingHash)) throw conflict("幂等键已用于其他请求");
        return receipt;
    }

    private String requireKey(String value, String message) {
        String normalized = requireText(value, 96, message);
        if (normalized.length() < 8) throw badRequest(message);
        return normalized;
    }

    private String requireText(String value, int max, String message) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > max) throw badRequest(message);
        return normalized;
    }

    private String optionalText(String value, int max) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > max) throw badRequest("文本长度超限");
        return normalized;
    }

    private String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private String optionalUpper(String value) {
        String normalized = upper(value);
        return normalized.isEmpty() ? null : normalized;
    }

    private void requirePrincipal(Long principal) {
        if (!positive(principal)) throw forbidden();
    }

    private boolean positive(Long value) { return value != null && value > 0; }

    private ServiceException badRequest(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    private ServiceException conflict(String message) {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }

    private ServiceException forbidden() {
        return new ServiceException("无权访问该记录", HttpStatus.FORBIDDEN);
    }

    private ServiceException forbidden(String message) {
        return new ServiceException(message, HttpStatus.FORBIDDEN);
    }
}
