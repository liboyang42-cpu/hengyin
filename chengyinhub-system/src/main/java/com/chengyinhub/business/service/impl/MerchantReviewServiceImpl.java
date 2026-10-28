package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.MerchantReview;
import com.chengyinhub.business.domain.MerchantReviewEligibility;
import com.chengyinhub.business.domain.MerchantReviewReport;
import com.chengyinhub.business.domain.dto.MerchantReviewCreateRequest;
import com.chengyinhub.business.domain.dto.MerchantReviewReplyDeleteRequest;
import com.chengyinhub.business.domain.dto.MerchantReviewReplyRequest;
import com.chengyinhub.business.domain.dto.MerchantReviewReportRequest;
import com.chengyinhub.business.domain.vo.MerchantReviewEligibilityVO;
import com.chengyinhub.business.domain.vo.MerchantReviewPageVO;
import com.chengyinhub.business.domain.vo.MerchantReviewVO;
import com.chengyinhub.business.domain.vo.MerchantReviewWriteResultVO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.MerchantReviewMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IMerchantReviewService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class MerchantReviewServiceImpl implements IMerchantReviewService {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int REVIEW_MIN_LENGTH = 2;
    private static final int REVIEW_MAX_LENGTH = 1000;
    private static final int REPLY_MAX_LENGTH = 500;
    private static final int REPORT_MAX_LENGTH = 500;
    private static final int MAX_IMAGES = 9;
    private static final int MAX_URL_LENGTH = 500;
    private static final int MAX_REQUEST_ID_LENGTH = 64;
    private static final String OSS_ENDPOINT_PROPERTY = "CHENGYIN_OSS_ENDPOINT";
    private static final String OSS_BUCKET_PROPERTY = "CHENGYIN_OSS_BUCKET_NAME";
    private static final String SUBMISSION_REVIEW_REASON = "新评价发布复核";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MANAGE_FILTER_ALL = "all";
    private static final List<String> MANAGE_FILTERS = Collections.unmodifiableList(
            Arrays.asList(MANAGE_FILTER_ALL, "pending", "low", "photo", "platform"));

    @Autowired private MerchantReviewMapper merchantReviewMapper;
    @Autowired private IMerchantAccessService merchantAccessService;
    @Autowired private IAuditTaskService auditTaskService;
    @Autowired private BusinessAuditEventMapper businessAuditEventMapper;
    @Autowired private Environment environment;
    @Autowired private com.chengyinhub.business.service.IWxContentSecurityService wxContentSecurityService;
    @Autowired private com.chengyinhub.business.service.IUmsMemberService umsMemberService;

    @Override
    public MerchantReviewPageVO publicList(Long viewerMemberId, Long merchantRowId,
                                           Integer pageNum, Integer pageSize) {
        return publicList(viewerMemberId, merchantRowId, pageNum, pageSize, null);
    }

    @Override
    public MerchantReviewPageVO publicList(Long viewerMemberId, Long merchantRowId,
                                           Integer pageNum, Integer pageSize, Long registrationId) {
        requirePositive(merchantRowId, "merchantRowId");
        if (registrationId != null) requirePositive(registrationId, "registrationId");
        Page page = page(pageNum, pageSize);
        long total = merchantReviewMapper.countPublicReviews(merchantRowId);
        List<MerchantReview> rows = total == 0 ? Collections.<MerchantReview>emptyList()
                : merchantReviewMapper.selectPublicReviews(merchantRowId, page.offset, page.size);
        Map<String, Object> summary = merchantReviewMapper.selectPublicRatingSummary(merchantRowId);

        MerchantReviewPageVO out = basePage("public", page, total, rows, viewerMemberId != null && viewerMemberId > 0,
                false);
        out.setAverageRating(decimalValue(summary, "averageRating"));
        applyReviewStats(out, merchantRowId);
        MerchantReviewEligibilityVO eligibility = new MerchantReviewEligibilityVO();
        eligibility.setRequestedRegistrationId(registrationId);
        if (viewerMemberId == null || viewerMemberId <= 0) {
            eligibility.setCanCreate(false);
            eligibility.setReasonCode("LOGIN_REQUIRED");
        } else {
            int activeRows = merchantReviewMapper.countActiveMerchantRowsForRow(merchantRowId);
            if (activeRows == 0) {
                eligibility.setCanCreate(false);
                eligibility.setReasonCode("MERCHANT_UNAVAILABLE");
            } else if (activeRows > 1) {
                eligibility.setCanCreate(false);
                eligibility.setReasonCode("AMBIGUOUS_LEGACY_MERCHANT_SCOPE");
            } else {
                MerchantReviewEligibility eligible = registrationId == null
                        ? merchantReviewMapper.selectLatestEligibility(viewerMemberId, merchantRowId)
                        : merchantReviewMapper.selectEligibilityForRegistration(viewerMemberId, merchantRowId, registrationId);
                eligibility.setCanCreate(eligible != null);
                eligibility.setReasonCode(eligible == null ? "NO_UNUSED_ACTIVE_REDEMPTION" : "ELIGIBLE");
                eligibility.setRegistrationId(eligible == null ? null : eligible.getRegistrationId());
            }
        }
        out.setEligibility(eligibility);
        return out;
    }

    @Override
    public MerchantReviewPageVO manageList(Long principalMemberId, String filter, Integer pageNum, Integer pageSize) {
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.MARKETING_READ);
        String manageFilter = normalizeManageFilter(filter);
        Page page = page(pageNum, pageSize);
        long total = merchantReviewMapper.countManageReviews(access.getMerchantId(), manageFilter);
        List<MerchantReview> rows = total == 0 ? Collections.<MerchantReview>emptyList()
                : merchantReviewMapper.selectManageReviews(access.getMerchantId(), manageFilter, page.offset, page.size);
        MerchantReviewPageVO out = basePage("manage", page, total, rows,
                access.hasPermission(MerchantPermission.MARKETING_WRITE), true);
        out.setAverageRating(decimalValue(merchantReviewMapper.selectPublicRatingSummary(access.getMerchantId()), "averageRating"));
        applyReviewStats(out, access.getMerchantId());
        out.setFilter(manageFilter);
        out.setFilterCounts(manageFilterCounts(access.getMerchantId()));
        out.setEligibility(null);
        return out;
    }

    private String normalizeManageFilter(String filter) {
        if (filter == null || filter.trim().isEmpty()) return MANAGE_FILTER_ALL;
        String value = filter.trim();
        if (!MANAGE_FILTERS.contains(value)) throw badRequest("filter 不合法");
        return value;
    }

    private Map<String, Integer> manageFilterCounts(Long merchantRowId) {
        Map<String, Object> raw = merchantReviewMapper.selectManageFilterCounts(merchantRowId);
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("all", intValue(raw, "all_count"));
        counts.put("pending", intValue(raw, "pending_count"));
        counts.put("low", intValue(raw, "low_count"));
        counts.put("photo", intValue(raw, "photo_count"));
        counts.put("platform", intValue(raw, "platform_count"));
        return counts;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantReviewWriteResultVO create(Long authorMemberId, MerchantReviewCreateRequest request) {
        requirePositive(authorMemberId, "authorMemberId");
        NormalizedCreate input = normalizeCreate(request);
        if (!authorMemberId.equals(merchantReviewMapper.lockActiveMember(authorMemberId))) {
            throw forbidden("当前会员状态不可评价");
        }

        MerchantReview existing = merchantReviewMapper.selectByAuthorRequestForUpdate(
                authorMemberId, input.requestId);
        if (existing != null) {
            ensureCreateReplay(authorMemberId, input, existing);
            return writeResult(existing, true, null);
        }

        int activeRows = merchantReviewMapper.countActiveMerchantRowsForRow(input.merchantRowId);
        if (activeRows == 0) throw forbidden("目标门店当前不可评价");
        if (activeRows > 1) throw ambiguousLegacyMerchantScope();
        MerchantReviewEligibility eligibility = merchantReviewMapper.selectEligibility(
                authorMemberId, input.merchantRowId, input.registrationId);
        if (eligibility == null) {
            throw forbidden("仅限该报名人凭目标商家的生效核销评价");
        }
        if (!input.merchantRowId.equals(eligibility.getMerchantRowId())) {
            throw forbidden("核销门店归属无法确认");
        }
        MerchantReview duplicate = merchantReviewMapper.selectByRegistrationMerchant(
                input.registrationId, eligibility.getMerchantOwnerMemberId());
        if (duplicate != null) throw conflict("该次核销已评价，不能修改或重复评价");

        Date now = new Date();
        MerchantReview review = new MerchantReview();
        review.setMerchantRowId(eligibility.getMerchantRowId());
        review.setMerchantOwnerMemberId(eligibility.getMerchantOwnerMemberId());
        review.setRegistrationId(eligibility.getRegistrationId());
        review.setRedemptionId(eligibility.getRedemptionId());
        review.setAuthorMemberId(authorMemberId);
        review.setRating(input.rating);
        review.setContent(input.content);
        review.setImagesJson(input.imagesJson);
        review.setStatus(MerchantReview.STATUS_PENDING_REVIEW);
        review.setCreateRequestId(input.requestId);
        review.setCreateFingerprint(input.fingerprint);
        review.setVersion(0);
        review.setCreateTime(now);
        review.setUpdateTime(now);
        try {
            if (merchantReviewMapper.insertReview(review) != 1 || review.getId() == null) {
                throw conflict("评价未保存，请重试");
            }
        } catch (DuplicateKeyException e) {
            MerchantReview winner = merchantReviewMapper.selectByAuthorRequestForUpdate(
                    authorMemberId, input.requestId);
            if (winner != null) {
                ensureCreateReplay(authorMemberId, input, winner);
                return writeResult(winner, true, null);
            }
            throw conflict("该次核销已评价，不能修改或重复评价");
        }
        Long auditTaskId = auditTaskService.submitSafetyHold(AuditTask.BIZ_MERCHANT_REVIEW,
                review.getId(), authorMemberId, "NORMAL", submissionSnapshot(review));
        if (auditTaskId == null || auditTaskId <= 0) {
            throw conflict("评价复核任务未建立，请重试");
        }
        writeBusinessAudit(review, authorMemberId, "PLAYER", "MERCHANT_REVIEW_SUBMITTED",
                review.getRedemptionId(), review.getCreateRequestId(), "verified redemption review submitted for moderation",
                "{\"rating\":" + review.getRating() + ",\"status\":\"PENDING_REVIEW\"}");
        return writeResult(review, false, auditTaskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantReviewWriteResultVO reply(Long principalMemberId, MerchantReviewReplyRequest request) {
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.MARKETING_WRITE);
        NormalizedReply input = normalizeReply(request);
        MerchantReview review = merchantReviewMapper.selectReviewByIdForUpdate(input.reviewId);
        if (review == null || !access.getMerchantId().equals(review.getMerchantRowId())
                || !access.getOwnerMemberId().equals(review.getMerchantOwnerMemberId())) {
            throw notFound();
        }
        if (input.requestId.equals(review.getReplyRequestId())) {
            if (!access.getPrincipalMemberId().equals(review.getReplyActorMemberId())
                    || !input.fingerprint.equals(review.getReplyFingerprint())) {
                throw conflict("requestId 已用于其他回复");
            }
            return writeResult(review, true, null);
        }
        if (!MerchantReview.STATUS_VISIBLE.equals(review.getStatus())) {
            throw conflict("评价尚未公开或已下架，不能公开回复");
        }
        if (review.getReplyContent() != null) throw conflict("每条评价只允许一次公开回复");
        if (!input.expectedVersion.equals(review.getVersion())) throw conflict("评价已变化，请刷新后重试");
        checkReplyText(principalMemberId, input.content);
        if (merchantReviewMapper.replyCas(review.getId(), access.getMerchantId(), access.getOwnerMemberId(), input.content,
                access.getPrincipalMemberId(), access.getRoleCode(), input.requestId,
                input.fingerprint, input.expectedVersion) != 1) {
            throw conflict("回复冲突，请刷新后重试");
        }
        applyReplyFields(review, access, input);
        writeBusinessAudit(review, access.getPrincipalMemberId(), access.getRoleCode(),
                "MERCHANT_REVIEW_REPLIED", null, input.requestId, "merchant public reply",
                "{\"reviewId\":" + review.getId() + ",\"version\":" + review.getVersion() + "}");
        return writeResult(review, false, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantReviewWriteResultVO updateReply(Long principalMemberId, MerchantReviewReplyRequest request) {
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.MARKETING_WRITE);
        NormalizedReply input = normalizeReply(request);
        MerchantReview review = merchantReviewMapper.selectReviewByIdForUpdate(input.reviewId);
        if (review == null || !access.getMerchantId().equals(review.getMerchantRowId())
                || !access.getOwnerMemberId().equals(review.getMerchantOwnerMemberId())) {
            throw notFound();
        }
        if (input.requestId.equals(review.getReplyRequestId())) {
            if (!access.getPrincipalMemberId().equals(review.getReplyActorMemberId())
                    || !input.fingerprint.equals(review.getReplyFingerprint())) {
                throw conflict("requestId 已用于其他回复");
            }
            return writeResult(review, true, null);
        }
        if (!MerchantReview.STATUS_VISIBLE.equals(review.getStatus())) {
            throw conflict("评价尚未公开或已下架，不能修改回复");
        }
        if (review.getReplyContent() == null) throw conflict("这条评价还没有公开回复，不能修改");
        if (!input.expectedVersion.equals(review.getVersion())) throw conflict("评价已变化，请刷新后重试");
        checkReplyText(principalMemberId, input.content);
        if (merchantReviewMapper.updateReplyCas(review.getId(), access.getMerchantId(),
                access.getOwnerMemberId(), input.content, access.getPrincipalMemberId(), access.getRoleCode(),
                input.requestId, input.fingerprint, input.expectedVersion) != 1) {
            throw conflict("回复冲突，请刷新后重试");
        }
        applyReplyFields(review, access, input);
        writeBusinessAudit(review, access.getPrincipalMemberId(), access.getRoleCode(),
                "MERCHANT_REVIEW_REPLY_UPDATED", null, input.requestId, "merchant public reply updated",
                "{\"reviewId\":" + review.getId() + ",\"version\":" + review.getVersion() + "}");
        return writeResult(review, false, null);
    }

    private void checkReplyText(Long principalMemberId, String content) {
        com.chengyinhub.business.domain.UmsMember member = umsMemberService.selectUmsMemberById(principalMemberId);
        String risk = wxContentSecurityService.checkText(content, member == null ? null : member.getWxOpenId());
        if (risk != null) throw badRequest(risk);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantReviewWriteResultVO deleteReply(Long principalMemberId, MerchantReviewReplyDeleteRequest request) {
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.MARKETING_WRITE);
        NormalizedReplyDelete input = normalizeReplyDelete(request);
        MerchantReview review = merchantReviewMapper.selectReviewByIdForUpdate(input.reviewId);
        if (review == null || !access.getMerchantId().equals(review.getMerchantRowId())
                || !access.getOwnerMemberId().equals(review.getMerchantOwnerMemberId())) {
            throw notFound();
        }
        // 已经没有回复 = 上一次删除已生效(重试/双端同时点)。按回执返回成功,不报「冲突」——
        // 用户要的是「这条回复没了」,现在的状态就是他要的结果。
        if (review.getReplyContent() == null) return writeResult(review, true, null);
        if (!MerchantReview.STATUS_VISIBLE.equals(review.getStatus())) {
            throw conflict("评价尚未公开或已下架，不能删除回复");
        }
        if (!input.expectedVersion.equals(review.getVersion())) throw conflict("评价已变化，请刷新后重试");
        if (merchantReviewMapper.deleteReplyCas(review.getId(), access.getMerchantId(),
                access.getOwnerMemberId(), input.expectedVersion) != 1) {
            throw conflict("回复冲突，请刷新后重试");
        }
        review.setReplyContent(null);
        review.setReplyActorMemberId(null);
        review.setReplyActorRoleCode(null);
        review.setReplyRequestId(null);
        review.setReplyFingerprint(null);
        review.setRepliedAt(null);
        review.setVersion(input.expectedVersion + 1);
        writeBusinessAudit(review, access.getPrincipalMemberId(), access.getRoleCode(),
                "MERCHANT_REVIEW_REPLY_DELETED", null, input.requestId, "merchant public reply deleted",
                "{\"reviewId\":" + review.getId() + ",\"version\":" + review.getVersion() + "}");
        return writeResult(review, false, null);
    }

    /** reply/updateReply 共用的回执字段回写,避免两条路径各写一份而漂移。 */
    private void applyReplyFields(MerchantReview review, MerchantAccessContext access, NormalizedReply input) {
        review.setReplyContent(input.content);
        review.setReplyActorMemberId(access.getPrincipalMemberId());
        review.setReplyActorRoleCode(access.getRoleCode());
        review.setReplyRequestId(input.requestId);
        review.setReplyFingerprint(input.fingerprint);
        review.setVersion(input.expectedVersion + 1);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantReviewWriteResultVO report(Long reporterMemberId, MerchantReviewReportRequest request) {
        requirePositive(reporterMemberId, "reporterMemberId");
        NormalizedReport input = normalizeReport(request);
        return reportInternal(reporterMemberId, "PLAYER", input, null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantReviewWriteResultVO reportAsMerchant(Long principalMemberId, MerchantReviewReportRequest request) {
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.MARKETING_WRITE);
        NormalizedReport input = normalizeReport(request);
        return reportInternal(access.getPrincipalMemberId(), access.getRoleCode(), input,
                access.getMerchantId(), access.getOwnerMemberId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int moderateFromAudit(Long reviewId, String expectedStatus, String targetStatus, String reasonText) {
        requirePositive(reviewId, "reviewId");
        if (!MerchantReview.STATUS_PENDING_REVIEW.equals(expectedStatus)
                && !MerchantReview.STATUS_VISIBLE.equals(expectedStatus)
                && !MerchantReview.STATUS_HIDDEN.equals(expectedStatus)) {
            throw badRequest("评价审核预期状态非法");
        }
        boolean restoresRejectedSubmission = MerchantReview.STATUS_HIDDEN.equals(expectedStatus)
                && MerchantReview.STATUS_PENDING_REVIEW.equals(targetStatus);
        if (!MerchantReview.STATUS_VISIBLE.equals(targetStatus)
                && !MerchantReview.STATUS_HIDDEN.equals(targetStatus)
                && !restoresRejectedSubmission) {
            throw badRequest("评价审核目标状态非法");
        }
        MerchantReview review = merchantReviewMapper.selectReviewByIdForUpdate(reviewId);
        if (review == null) throw new ServiceException("评价不存在", HttpStatus.NOT_FOUND);
        if (!expectedStatus.equals(review.getStatus())) throw conflict("评价审核状态已变化");
        String reason = normalizeOptional(reasonText, REPORT_MAX_LENGTH, "审核原因");
        int rows = merchantReviewMapper.moderateCas(reviewId, expectedStatus, targetStatus,
                MerchantReview.STATUS_VISIBLE.equals(targetStatus) ? null : reason, review.getVersion());
        if (rows != 1) throw conflict("评价审核状态 CAS 失败");
        return rows;
    }

    private MerchantReviewWriteResultVO reportInternal(Long reporterMemberId, String actorRole,
                                                       NormalizedReport input, Long expectedMerchantRow,
                                                       Long expectedMerchantOwner) {
        if (!reporterMemberId.equals(merchantReviewMapper.lockActiveMember(reporterMemberId))) {
            throw forbidden("当前会员状态不可举报");
        }
        MerchantReviewReport existing = merchantReviewMapper.selectReportByReporterRequestForUpdate(
                reporterMemberId, input.requestId);
        if (existing != null) {
            if ((expectedMerchantRow != null && !expectedMerchantRow.equals(existing.getMerchantRowId()))
                    || (expectedMerchantOwner != null
                    && !expectedMerchantOwner.equals(existing.getMerchantOwnerMemberId()))
                    || (input.merchantMemberId != null
                    && !input.merchantMemberId.equals(existing.getMerchantOwnerMemberId()))) {
                throw notFound();
            }
            String fingerprint = reportFingerprint(reporterMemberId, actorRole, existing.getMerchantRowId(),
                    existing.getMerchantOwnerMemberId(), input);
            if (!fingerprint.equals(existing.getRequestFingerprint())) {
                throw conflict("requestId 已用于其他举报");
            }
            return reportResult(existing, true);
        }

        MerchantReview review = merchantReviewMapper.selectReviewByIdForUpdate(input.reviewId);
        if (review == null
                || (expectedMerchantRow != null && !expectedMerchantRow.equals(review.getMerchantRowId()))
                || (expectedMerchantOwner != null
                && !expectedMerchantOwner.equals(review.getMerchantOwnerMemberId()))
                || (input.merchantMemberId != null
                && !input.merchantMemberId.equals(review.getMerchantOwnerMemberId()))) {
            throw notFound();
        }
        if (!MerchantReview.STATUS_VISIBLE.equals(review.getStatus())) {
            throw conflict("评价已下架，无需重复举报");
        }
        if (!input.expectedVersion.equals(review.getVersion())) throw conflict("评价已变化，请刷新后重试");
        String fingerprint = reportFingerprint(reporterMemberId, actorRole, review.getMerchantRowId(),
                review.getMerchantOwnerMemberId(), input);

        Long taskId = auditTaskService.reportContent(AuditTask.BIZ_MERCHANT_REVIEW,
                review.getId(), reporterMemberId, input.reason);
        if (taskId == null || taskId <= 0) throw conflict("举报任务未建立，请重试");
        MerchantReviewReport report = new MerchantReviewReport();
        report.setReviewId(review.getId());
        report.setMerchantRowId(review.getMerchantRowId());
        report.setMerchantOwnerMemberId(review.getMerchantOwnerMemberId());
        report.setReporterMemberId(reporterMemberId);
        report.setReporterRoleCode(actorRole);
        report.setReasonText(input.reason);
        report.setRequestId(input.requestId);
        report.setRequestFingerprint(fingerprint);
        report.setAuditTaskId(taskId);
        report.setCreateTime(new Date());
        if (merchantReviewMapper.insertReport(report) != 1 || report.getId() == null) {
            throw conflict("举报回执未保存，请重试");
        }
        writeBusinessAudit(review, reporterMemberId, actorRole, "MERCHANT_REVIEW_REPORTED",
                report.getId(), input.requestId, "merchant review reported",
                "{\"reviewId\":" + review.getId() + ",\"auditTaskId\":" + taskId + "}");
        return reportResult(report, false);
    }

    private MerchantReviewPageVO basePage(String mode, Page page, long total, List<MerchantReview> rows,
                                          boolean canAct, boolean manage) {
        MerchantReviewPageVO out = new MerchantReviewPageVO();
        out.setMode(mode);
        out.setPageNum(page.number);
        out.setPageSize(page.size);
        out.setTotal(total);
        List<MerchantReviewVO> items = new ArrayList<>();
        if (rows != null) {
            for (MerchantReview row : rows) items.add(toVO(row, canAct, manage));
        }
        out.setItems(items);
        out.setHasMore((long) page.offset + items.size() < total);
        return out;
    }

    private void applyReviewStats(MerchantReviewPageVO out, Long merchantRowId) {
        Map<String, Object> stats = merchantReviewMapper.selectReviewStats(merchantRowId);
        out.setPendingReplyCount(intValue(stats, "pendingReplyCount"));
        out.setMonthNewCount(intValue(stats, "monthNewCount"));
        out.setReplyRatePct(intValue(stats, "replyRatePct"));
    }

    private MerchantReviewVO toVO(MerchantReview row, boolean canAct, boolean manage) {
        MerchantReviewVO out = new MerchantReviewVO();
        out.setId(row.getId());
        out.setRating(row.getRating());
        out.setContent(row.getContent());
        out.setImageUrls(parseImages(row.getImagesJson()));
        out.setAuthorNickname(maskNickname(row.getAuthorNickname()));
        out.setAuthorAvatar(row.getAuthorAvatar());
        out.setVerifiedRedemption(true);
        out.setStatus(row.getStatus());
        out.setMerchantReply(row.getReplyContent());
        out.setRepliedAt(row.getRepliedAt());
        out.setCreateTime(row.getCreateTime());
        out.setVersion(row.getVersion());
        out.setCanReply(manage && canAct && MerchantReview.STATUS_VISIBLE.equals(row.getStatus())
                && row.getReplyContent() == null);
        out.setCanEditReply(manage && canAct && MerchantReview.STATUS_VISIBLE.equals(row.getStatus())
                && row.getReplyContent() != null);
        out.setCanReport(canAct && MerchantReview.STATUS_VISIBLE.equals(row.getStatus()));
        out.setUnderPlatformReview(manage && Integer.valueOf(1).equals(row.getUnderPlatformReview()));
        return out;
    }

    private NormalizedCreate normalizeCreate(MerchantReviewCreateRequest request) {
        if (request == null) throw badRequest("评价参数不能为空");
        requirePositive(request.getMerchantRowId(), "merchantRowId");
        requirePositive(request.getRegistrationId(), "registrationId");
        if (request.getRating() == null || request.getRating() < 1 || request.getRating() > 5) {
            throw badRequest("rating 必须在 1 到 5 之间");
        }
        String content = normalizeRequired(request.getContent(), REVIEW_MIN_LENGTH, REVIEW_MAX_LENGTH, "评价正文");
        List<String> images = normalizeImages(request.getImageUrls());
        String imagesJson;
        try {
            imagesJson = JSON.writeValueAsString(images);
        } catch (Exception e) {
            throw badRequest("评价图片格式不合法");
        }
        String requestId = normalizeRequestId(request.getRequestId());
        String fingerprint = fingerprint("create", request.getMerchantRowId(), request.getRegistrationId(),
                request.getRating(), content, imagesJson);
        return new NormalizedCreate(request.getMerchantRowId(), request.getRegistrationId(),
                request.getRating(), content, imagesJson, requestId, fingerprint);
    }

    private NormalizedReply normalizeReply(MerchantReviewReplyRequest request) {
        if (request == null) throw badRequest("回复参数不能为空");
        requirePositive(request.getReviewId(), "reviewId");
        requireVersion(request.getExpectedVersion());
        String content = normalizeRequired(request.getContent(), 1, REPLY_MAX_LENGTH, "回复内容");
        String requestId = normalizeRequestId(request.getRequestId());
        String fingerprint = fingerprint("reply", request.getReviewId(), request.getExpectedVersion(), content);
        return new NormalizedReply(request.getReviewId(), content, request.getExpectedVersion(), requestId, fingerprint);
    }

    private NormalizedReplyDelete normalizeReplyDelete(MerchantReviewReplyDeleteRequest request) {
        if (request == null) throw badRequest("删除回复参数不能为空");
        requirePositive(request.getReviewId(), "reviewId");
        requireVersion(request.getExpectedVersion());
        String requestId = normalizeRequestId(request.getRequestId());
        return new NormalizedReplyDelete(request.getReviewId(), request.getExpectedVersion(), requestId);
    }

    private NormalizedReport normalizeReport(MerchantReviewReportRequest request) {
        if (request == null) throw badRequest("举报参数不能为空");
        requirePositive(request.getReviewId(), "reviewId");
        requireVersion(request.getExpectedVersion());
        String reason = normalizeRequired(request.getReason(), 2, REPORT_MAX_LENGTH, "举报原因");
        return new NormalizedReport(request.getReviewId(), request.getMerchantMemberId(), reason,
                request.getExpectedVersion(), normalizeRequestId(request.getRequestId()));
    }

    private void ensureCreateReplay(Long authorMemberId, NormalizedCreate input, MerchantReview existing) {
        if (!authorMemberId.equals(existing.getAuthorMemberId())
                || !input.merchantRowId.equals(existing.getMerchantRowId())
                || !input.registrationId.equals(existing.getRegistrationId())
                || !input.fingerprint.equals(existing.getCreateFingerprint())) {
            throw conflict("requestId 已用于其他评价");
        }
    }

    private MerchantReviewWriteResultVO writeResult(MerchantReview review, boolean replayed, Long taskId) {
        MerchantReviewWriteResultVO out = new MerchantReviewWriteResultVO();
        out.setReviewId(review.getId());
        out.setStatus(review.getStatus());
        out.setVersion(review.getVersion());
        out.setReplayed(replayed);
        out.setAuditTaskId(taskId);
        return out;
    }

    private MerchantReviewWriteResultVO reportResult(MerchantReviewReport report, boolean replayed) {
        MerchantReviewWriteResultVO out = new MerchantReviewWriteResultVO();
        out.setReviewId(report.getReviewId());
        out.setStatus("PENDING_PLATFORM_REVIEW");
        out.setReplayed(replayed);
        out.setAuditTaskId(report.getAuditTaskId());
        return out;
    }

    private void writeBusinessAudit(MerchantReview review, Long actorMemberId, String actorRole,
                                    String action, Long relationId, String requestId,
                                    String reason, String afterJson) {
        BusinessAuditEvent event = new BusinessAuditEvent();
        event.setDomainType("MERCHANT");
        event.setDomainId(review.getMerchantRowId());
        event.setOwnerMemberId(review.getMerchantOwnerMemberId());
        event.setActorMemberId(actorMemberId);
        event.setActorRoleCode(actorRole);
        event.setAction(action);
        event.setTargetType("MERCHANT_REVIEW");
        event.setTargetId(review.getId());
        event.setRelationId(relationId);
        event.setAfterJson(afterJson);
        event.setReason(reason);
        event.setRequestId(requestId);
        event.setCreateTime(new Date());
        if (businessAuditEventMapper.insertBusinessAuditEvent(event) != 1) {
            throw conflict("业务审计写入失败");
        }
    }

    private List<String> normalizeImages(List<String> source) {
        if (source == null) return Collections.emptyList();
        if (source.size() > MAX_IMAGES) throw badRequest("评价图片最多 9 张");
        List<String> images = new ArrayList<>();
        for (String raw : source) {
            if (raw == null) throw badRequest("评价图片地址不合法");
            String value = raw.trim();
            if (value.isEmpty() || value.length() > MAX_URL_LENGTH || !isApprovedUploadUrl(value)) {
                throw badRequest("评价图片必须来自城瘾安全上传链路");
            }
            images.add(value);
        }
        return images;
    }

    /**
     * 只接受现有 /api/common/uploadOSS 返回的预签名 URL。这里验证的是上传来源，
     * 不是内容安全结论；内容仍须经过统一平台复核后才可公开。
     */
    private boolean isApprovedUploadUrl(String value) {
        String endpoint = environment == null ? null : environment.getProperty(OSS_ENDPOINT_PROPERTY);
        String bucket = environment == null ? null : environment.getProperty(OSS_BUCKET_PROPERTY);
        if (isBlank(endpoint) || isBlank(bucket) || !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) {
            return false;
        }
        try {
            URI endpointUri = URI.create(endpoint.contains("://") ? endpoint : "https://" + endpoint);
            String endpointHost = normalizedHost(endpointUri.getHost());
            URI imageUri = new URI(value);
            String imageHost = normalizedHost(imageUri.getHost());
            String expectedHost = endpointHost.startsWith(bucket.toLowerCase(Locale.ROOT) + ".")
                    ? endpointHost : bucket.toLowerCase(Locale.ROOT) + "." + endpointHost;
            if (!"https".equalsIgnoreCase(imageUri.getScheme())
                    || imageUri.getUserInfo() != null
                    || imageUri.getPort() != -1
                    || !expectedHost.equals(imageHost)
                    || imageUri.getFragment() != null
                    || imageUri.getRawPath() == null
                    || !imageUri.getRawPath().matches("/upload/[0-9a-f]{32}\\.(?:jpg|jpeg|png|gif)")) {
                return false;
            }
            Set<String> queryNames = queryNames(imageUri.getRawQuery());
            boolean v1Signature = queryNames.contains("OSSAccessKeyId")
                    && queryNames.contains("Expires") && queryNames.contains("Signature");
            boolean v4Signature = queryNames.contains("x-oss-signature-version")
                    && queryNames.contains("x-oss-credential") && queryNames.contains("x-oss-date")
                    && queryNames.contains("x-oss-expires") && queryNames.contains("x-oss-signature");
            return v1Signature || v4Signature;
        } catch (IllegalArgumentException | java.net.URISyntaxException e) {
            return false;
        }
    }

    private String submissionSnapshot(MerchantReview review) {
        try {
            java.util.LinkedHashMap<String, Object> snapshot = new java.util.LinkedHashMap<>();
            snapshot.put("bizType", AuditTask.BIZ_MERCHANT_REVIEW);
            snapshot.put("reviewId", review.getId());
            snapshot.put("merchantRowId", review.getMerchantRowId());
            snapshot.put("merchantMemberId", review.getMerchantOwnerMemberId());
            snapshot.put("rating", review.getRating());
            snapshot.put("content", review.getContent());
            snapshot.put("imageUrls", parseImages(review.getImagesJson()));
            snapshot.put("status", MerchantReview.STATUS_PENDING_REVIEW);
            return JSON.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw badRequest("评价复核快照生成失败");
        }
    }

    private Set<String> queryNames(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return Collections.emptySet();
        Set<String> names = new HashSet<>();
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && equals < pair.length() - 1) names.add(pair.substring(0, equals));
        }
        return names;
    }

    private String normalizedHost(String host) {
        if (host == null || host.trim().isEmpty()) throw new IllegalArgumentException("host missing");
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        return normalized.endsWith(".") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private List<String> parseImages(String json) {
        if (json == null || json.trim().isEmpty()) return Collections.emptyList();
        try {
            List<String> values = JSON.readValue(json, new TypeReference<List<String>>() { });
            return values == null ? Collections.<String>emptyList() : values;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private String maskNickname(String nickname) {
        String value = nickname == null ? "" : nickname.trim();
        if (value.isEmpty()) return "城瘾玩家";
        int[] points = value.codePoints().toArray();
        if (points.length == 1) return new String(points, 0, 1) + "*";
        return new String(points, 0, 1) + "***" + new String(points, points.length - 1, 1);
    }

    private BigDecimal decimalValue(Map<String, Object> values, String key) {
        if (values == null) return null;
        Object value = values.get(key);
        if (value == null) {
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                if (key.equalsIgnoreCase(entry.getKey())) { value = entry.getValue(); break; }
            }
        }
        if (value == null) return null;
        try { return new BigDecimal(String.valueOf(value)); }
        catch (NumberFormatException e) { return null; }
    }

    private int intValue(Map<String, Object> values, String key) {
        BigDecimal value = decimalValue(values, key);
        if (value == null) return 0;
        return Math.max(0, value.intValue());
    }

    private Page page(Integer pageNum, Integer pageSize) {
        int number = pageNum == null ? 1 : pageNum;
        int size = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (number < 1) throw badRequest("pageNum 不合法");
        if (size < 1 || size > MAX_PAGE_SIZE) throw badRequest("pageSize 必须在 1 到 50 之间");
        try { return new Page(number, size, Math.multiplyExact(number - 1, size)); }
        catch (ArithmeticException e) { throw badRequest("分页参数过大"); }
    }

    private String normalizeRequestId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_REQUEST_ID_LENGTH
                || !normalized.matches("[A-Za-z0-9._:-]+")) {
            throw badRequest("requestId 格式不合法");
        }
        return normalized;
    }

    private String normalizeRequired(String value, int min, int max, String label) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() < min || normalized.length() > max) {
            throw badRequest(label + "长度必须在 " + min + " 到 " + max + " 字之间");
        }
        return normalized;
    }

    private String normalizeOptional(String value, int max, String label) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > max) throw badRequest(label + "不能超过 " + max + " 字");
        return normalized;
    }

    private String fingerprint(Object... parts) {
        StringBuilder source = new StringBuilder();
        for (Object part : parts) {
            String value = part == null ? "<null>" : String.valueOf(part);
            source.append(value.length()).append(':').append(value).append('|');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String reportFingerprint(Long reporterMemberId, String actorRole, Long merchantRowId,
                                     Long merchantOwnerMemberId, NormalizedReport input) {
        return fingerprint("report", reporterMemberId, actorRole, merchantRowId, merchantOwnerMemberId,
                input.reviewId, input.expectedVersion, input.reason);
    }

    /** TODO(merchant-row-snapshot): redemption 未保存 merchant_row_id 前，同 owner 多门店不得猜测归属。 */
    private ServiceException ambiguousLegacyMerchantScope() {
        return forbidden("该商家主体存在多个活跃门店，历史核销尚无门店快照，暂不能创建评价");
    }

    private void requirePositive(Long value, String label) {
        if (value == null || value <= 0) throw badRequest(label + " 不合法");
    }

    private void requireVersion(Integer version) {
        if (version == null || version < 0) throw badRequest("expectedVersion 不合法");
    }

    private ServiceException badRequest(String message) { return new ServiceException(message, HttpStatus.BAD_REQUEST); }
    private ServiceException forbidden(String message) { return new ServiceException(message, HttpStatus.FORBIDDEN); }
    private ServiceException conflict(String message) { return new ServiceException(message, HttpStatus.CONFLICT); }
    private ServiceException notFound() { return new ServiceException("评价不存在或无权访问", HttpStatus.NOT_FOUND); }

    private static final class Page {
        private final int number;
        private final int size;
        private final int offset;
        private Page(int number, int size, int offset) { this.number = number; this.size = size; this.offset = offset; }
    }

    private static final class NormalizedCreate {
        private final Long merchantRowId;
        private final Long registrationId;
        private final Integer rating;
        private final String content;
        private final String imagesJson;
        private final String requestId;
        private final String fingerprint;
        private NormalizedCreate(Long merchantRowId, Long registrationId, Integer rating, String content,
                                 String imagesJson, String requestId, String fingerprint) {
            this.merchantRowId = merchantRowId; this.registrationId = registrationId; this.rating = rating;
            this.content = content; this.imagesJson = imagesJson; this.requestId = requestId; this.fingerprint = fingerprint;
        }
    }

    private static final class NormalizedReply {
        private final Long reviewId;
        private final String content;
        private final Integer expectedVersion;
        private final String requestId;
        private final String fingerprint;
        private NormalizedReply(Long reviewId, String content, Integer expectedVersion,
                                String requestId, String fingerprint) {
            this.reviewId = reviewId; this.content = content; this.expectedVersion = expectedVersion;
            this.requestId = requestId; this.fingerprint = fingerprint;
        }
    }

    private static final class NormalizedReplyDelete {
        private final Long reviewId;
        private final Integer expectedVersion;
        private final String requestId;
        private NormalizedReplyDelete(Long reviewId, Integer expectedVersion, String requestId) {
            this.reviewId = reviewId; this.expectedVersion = expectedVersion; this.requestId = requestId;
        }
    }

    private static final class NormalizedReport {
        private final Long reviewId;
        private final Long merchantMemberId;
        private final String reason;
        private final Integer expectedVersion;
        private final String requestId;
        private NormalizedReport(Long reviewId, Long merchantMemberId, String reason,
                                 Integer expectedVersion, String requestId) {
            this.reviewId = reviewId; this.merchantMemberId = merchantMemberId; this.reason = reason;
            this.expectedVersion = expectedVersion; this.requestId = requestId;
        }
    }
}
