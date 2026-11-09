package com.chengyinhub.business.service.impl;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.VideoBountyAccrual;
import com.chengyinhub.business.domain.VideoBountyEvidence;
import com.chengyinhub.business.domain.VideoBountySubmission;
import com.chengyinhub.business.domain.VideoCampaign;
import com.chengyinhub.business.domain.dto.VideoBountyEvidenceRequest;
import com.chengyinhub.business.domain.dto.VideoBountyEvidenceView;
import com.chengyinhub.business.domain.dto.VideoBountyReviewRequest;
import com.chengyinhub.business.domain.dto.VideoBountySubmissionView;
import com.chengyinhub.business.domain.dto.VideoBountySubmitRequest;
import com.chengyinhub.business.domain.dto.VideoCampaignAttributionView;
import com.chengyinhub.business.domain.dto.VideoCampaignRequest;
import com.chengyinhub.business.domain.MarketingReferralCredential;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.exception.BountyTipException;
import com.chengyinhub.business.mapper.VideoBountyAccrualMapper;
import com.chengyinhub.business.mapper.VideoBountyEvidenceMapper;
import com.chengyinhub.business.mapper.VideoBountySubmissionMapper;
import com.chengyinhub.business.mapper.VideoCampaignMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMarketingAttributionService;
import com.chengyinhub.business.service.IVideoBountyService;
import com.chengyinhub.business.service.support.CanonicalDigest;
import com.chengyinhub.business.service.support.VideoBountyRuleCalculator;
import com.chengyinhub.business.service.support.VideoPlatformLinks;

/**
 * 视频内容悬赏实现。
 *
 * <p><b>钱边界(必须保留)</b>:本类<b>不写任何余额、支付、分账、券或权益</b>。它只做
 * "投稿 → 审核 → 外部证据 → 应计记录 → 幂等重算 → 申诉/撤销"这条可追溯链。
 * 应计行的 {@code payoutState} 恒为 {@link VideoBountyAccrual#PAYOUT_RECORDED_ONLY},
 * 因为现金兑付需要独立获批的通道集成,而本域没有、也不假装有。
 *
 * <p><b>播放事实来源</b>:服务从不"查平台"。一切播放数字都来自
 * {@link #recordEvidence} 录入的外部平台证据(报表编号/导出文件/人工核验),
 * 我方设备与账号数据不能作为证据 —— 方法签名要求 {@code sourceRef} 就是为了把这条写死。
 *
 * <p><b>反重复领取</b>:三层。
 * <ol>
 *   <li>{@code uk_video_canonical} 挡同活动同视频重复投稿;</li>
 *   <li>{@code selectByMemberAndCanonical} 挡同一作者换活动再投一次;</li>
 *   <li>{@code verifiedGroupKey}(运营核验)把跨平台搬运归到一组,封顶按组合计;
 *       作者自报的 {@code originGroupKey} 只作建议,不参与应计与封顶。</li>
 * </ol>
 *
 * @author chengyinhub
 */
@Service
public class VideoBountyServiceImpl implements IVideoBountyService
{
    private static final Logger log = LoggerFactory.getLogger(VideoBountyServiceImpl.class);

    /** 运营列表单页上限,防止一次拉全表。 */
    private static final int MAX_LIST_LIMIT = 200;

    private final VideoCampaignMapper campaignMapper;
    private final VideoBountySubmissionMapper submissionMapper;
    private final VideoBountyEvidenceMapper evidenceMapper;
    private final VideoBountyAccrualMapper accrualMapper;
    private final ICmsTopicService topicService;
    /**
     * 归因域(M0 唯一 writer)。本类只<b>读</b>它的聚合,不写归因表、不自建推荐码、
     * 不算佣金 —— 视频侧的"这条 campaign 带来多少成交"必须来自同一份归因事实。
     */
    private final IMarketingAttributionService attributionService;
    /** 校验活动归属 owner 是真实可用会员,避免把归因主体设成一个不存在的 id。 */
    private final UmsMemberMapper memberMapper;
    /** 原视频组核验留痕。缺失即拒绝核验(503),不留"改过但无记录"。 */
    private final com.chengyinhub.business.mapper.BusinessAuditEventMapper businessAuditEventMapper;

    public VideoBountyServiceImpl(VideoCampaignMapper campaignMapper,
                                  VideoBountySubmissionMapper submissionMapper,
                                  VideoBountyEvidenceMapper evidenceMapper,
                                  VideoBountyAccrualMapper accrualMapper,
                                  ICmsTopicService topicService,
                                  IMarketingAttributionService attributionService,
                                  UmsMemberMapper memberMapper,
                                  com.chengyinhub.business.mapper.BusinessAuditEventMapper businessAuditEventMapper) {
        this.campaignMapper = campaignMapper;
        this.submissionMapper = submissionMapper;
        this.evidenceMapper = evidenceMapper;
        this.accrualMapper = accrualMapper;
        this.topicService = topicService;
        this.attributionService = attributionService;
        this.memberMapper = memberMapper;
        this.businessAuditEventMapper = businessAuditEventMapper;
    }

    // ------------------------------------------------------------------ 投稿

    @Override
    @Transactional
    public VideoBountySubmissionView submit(long memberId, VideoBountySubmitRequest request) {
        if (memberId <= 0) {
            throw BountyTipException.unauthorized("LOGIN_REQUIRED", "请先登录");
        }
        if (request == null) {
            throw BountyTipException.badRequest("REQUEST_REQUIRED", "缺少投稿内容");
        }
        VideoCampaign campaign = requireActiveCampaign(request.getCampaignKey());
        requirePublishedTopic(request.getTopicId());

        String declaredPlatform = VideoPlatformLinks.normalizePlatform(request.getPlatform());
        String sourceUrl = request.getSourceUrl() == null ? "" : request.getSourceUrl().trim();
        VideoPlatformLinks links;
        try {
            links = VideoPlatformLinks.canonicalize(sourceUrl);
        } catch (VideoPlatformLinks.InvalidLinkException invalid) {
            throw BountyTipException.badRequest(invalid.getReasonCode(), invalid.getMessage());
        }
        // 作者声明的平台必须与链接一致:写错平台就能把同一视频算成两个不同组。
        if (!declaredPlatform.equals(links.getPlatform())) {
            throw BountyTipException.badRequest("PLATFORM_MISMATCH",
                    "链接平台与声明平台不一致:链接属于 " + links.getPlatform());
        }
        String originGroupKey;
        try {
            originGroupKey = VideoPlatformLinks.normalizeOriginGroupKey(
                    request.getOriginGroupKey(), links.getCanonicalVideoKey());
        } catch (VideoPlatformLinks.InvalidLinkException invalid) {
            throw BountyTipException.badRequest(invalid.getReasonCode(), invalid.getMessage());
        }

        // 跨活动重复投稿:换活动再投同一视频,等于绕开封顶。
        VideoBountySubmission existingAcrossCampaigns =
                submissionMapper.selectByMemberAndCanonical(memberId, links.getCanonicalVideoKey());
        if (existingAcrossCampaigns != null) {
            throw BountyTipException.conflict("VIDEO_ALREADY_SUBMITTED",
                    "这条视频已经投过稿,不能重复投稿");
        }

        Date now = new Date();
        VideoBountySubmission row = new VideoBountySubmission();
        row.setCampaignId(campaign.getId());
        row.setCampaignKey(campaign.getCampaignKey());
        row.setMemberId(memberId);
        row.setTopicId(request.getTopicId());
        row.setPlatform(links.getPlatform());
        row.setSourceUrl(sourceUrl);
        row.setCanonicalVideoKey(links.getCanonicalVideoKey());
        row.setOriginGroupKey(originGroupKey);
        row.setStatus(VideoBountySubmission.STATUS_SUBMITTED);
        row.setSubmittedAt(now);
        try {
            submissionMapper.insertSubmission(row);
        } catch (DuplicateKeyException duplicate) {
            // 唯一键 = 并发/重试时别人已投过。这不是错误状态,是可区分的拒绝。
            throw BountyTipException.conflict("VIDEO_ALREADY_SUBMITTED", "这条视频已经投过稿,不能重复投稿");
        }
        return VideoBountySubmissionView.of(row);
    }

    @Override
    public List<VideoBountySubmissionView> listMine(long memberId, String campaignKey) {
        if (memberId <= 0) {
            throw BountyTipException.unauthorized("LOGIN_REQUIRED", "请先登录");
        }
        Long campaignId = null;
        if (campaignKey != null && !campaignKey.isBlank()) {
            VideoCampaign campaign = campaignMapper.selectByCampaignKey(campaignKey.trim());
            if (campaign == null) {
                throw BountyTipException.notFound("CAMPAIGN_NOT_FOUND", "悬赏活动不存在");
            }
            campaignId = campaign.getId();
        }
        List<VideoBountySubmission> rows = submissionMapper.selectByMember(memberId, campaignId);
        List<VideoBountySubmissionView> views = new ArrayList<>(rows.size());
        for (VideoBountySubmission row : rows) views.add(VideoBountySubmissionView.of(row));
        return views;
    }

    @Override
    @Transactional
    public VideoBountySubmissionView appeal(long memberId, long submissionId, String note) {
        VideoBountySubmission row = requireOwnSubmission(memberId, submissionId);
        if (!Integer.valueOf(VideoBountySubmission.STATUS_REJECTED).equals(row.getStatus())) {
            throw BountyTipException.conflict("APPEAL_NOT_ALLOWED", "只有被拒绝的投稿可以申诉");
        }
        String trimmed = note == null ? "" : note.trim();
        if (trimmed.isEmpty()) {
            throw BountyTipException.badRequest("APPEAL_NOTE_REQUIRED", "请填写申诉说明");
        }
        if (submissionMapper.markAppealingIfRejected(submissionId, memberId, trimTo(trimmed, 500)) != 1) {
            throw BountyTipException.conflict("APPEAL_NOT_ALLOWED", "投稿状态已变化,请刷新后重试");
        }
        return VideoBountySubmissionView.of(submissionMapper.selectById(submissionId));
    }

    @Override
    @Transactional
    public VideoBountySubmissionView cancel(long memberId, long submissionId) {
        requireOwnSubmission(memberId, submissionId);
        // 已通过的投稿不能由作者自己取消:那等于自己抹掉已计应计。走运营撤销。
        if (submissionMapper.markCancelledByOwner(submissionId, memberId) != 1) {
            throw BountyTipException.conflict("CANCEL_NOT_ALLOWED",
                    "该投稿当前状态不允许取消;已通过的投稿需联系运营撤销");
        }
        return VideoBountySubmissionView.of(submissionMapper.selectById(submissionId));
    }

    // ------------------------------------------------------------------ 审核

    @Override
    @Transactional
    public VideoBountySubmissionView review(String reviewerRef, long submissionId,
                                           VideoBountyReviewRequest request) {
        requireReviewerRef(reviewerRef);
        if (request == null || request.getDecision() == null) {
            throw BountyTipException.badRequest("DECISION_REQUIRED", "缺少审核决定");
        }
        VideoBountySubmission row = submissionMapper.selectById(submissionId);
        if (row == null) {
            throw BountyTipException.notFound("SUBMISSION_NOT_FOUND", "投稿不存在");
        }
        VideoCampaign campaign = campaignMapper.selectById(row.getCampaignId());
        if (campaign == null) {
            throw BountyTipException.notFound("CAMPAIGN_NOT_FOUND", "悬赏活动不存在");
        }

        String decision = request.getDecision().trim().toUpperCase(java.util.Locale.ROOT);
        Date now = new Date();
        if ("APPROVE".equals(decision)) {
            Date publishedAt = request.getPublishedAt();
            if (publishedAt == null) {
                throw BountyTipException.badRequest("PUBLISHED_AT_REQUIRED", "通过审核必须填写真实发布时间");
            }
            if (publishedAt.after(now)) {
                throw BountyTipException.badRequest("PUBLISHED_AT_IN_FUTURE", "发布时间不能晚于当前时间");
            }
            int windowDays = campaign.getWindowDays() == null ? 7 : campaign.getWindowDays();
            Date windowEndsAt = VideoBountyRuleCalculator.windowEnd(publishedAt, windowDays);
            int affected;
            if (Integer.valueOf(VideoBountySubmission.STATUS_APPEALING).equals(row.getStatus())) {
                affected = submissionMapper.markApprovedIfAppealing(submissionId, publishedAt, windowEndsAt,
                        reviewerRef, now, trimTo(request.getReviewNote(), 500));
            } else {
                affected = submissionMapper.markApprovedIfPending(submissionId, publishedAt, windowEndsAt,
                        reviewerRef, now, trimTo(request.getReviewNote(), 500));
            }
            // CAS 失败 = 别人已改过状态(并发审核/作者取消)。明确冲突,不静默成功。
            if (affected != 1) {
                throw BountyTipException.conflict("REVIEW_CONFLICT", "投稿状态已变化,请刷新后重试");
            }
        } else if ("REJECT".equals(decision)) {
            String note = trimTo(request.getReviewNote(), 500);
            if (note == null || note.isEmpty()) {
                throw BountyTipException.badRequest("REVIEW_NOTE_REQUIRED", "拒绝必须填写理由");
            }
            if (submissionMapper.markRejectedIfPending(submissionId, reviewerRef, now, note) != 1) {
                throw BountyTipException.conflict("REVIEW_CONFLICT", "投稿状态已变化,请刷新后重试");
            }
        } else {
            throw BountyTipException.badRequest("DECISION_INVALID", "审核决定只有 APPROVE / REJECT");
        }
        return VideoBountySubmissionView.of(submissionMapper.selectById(submissionId));
    }

    @Override
    @Transactional
    public VideoBountySubmissionView revoke(String reviewerRef, long submissionId, String note) {
        requireReviewerRef(reviewerRef);
        String reason = trimTo(note, 500);
        if (reason == null || reason.isEmpty()) {
            throw BountyTipException.badRequest("REVOKE_NOTE_REQUIRED", "撤销必须填写理由");
        }
        if (submissionMapper.markRevokedIfApproved(submissionId, reviewerRef, new Date(), reason) != 1) {
            throw BountyTipException.conflict("REVOKE_NOT_ALLOWED", "只有已通过的投稿可以撤销");
        }
        // 应计不删除,只置 REVERSED:历史仍在,金额仍可复现。
        accrualMapper.reverseActiveBySubmission(submissionId, "revoked: " + reason);
        return VideoBountySubmissionView.of(submissionMapper.selectById(submissionId));
    }

    // ------------------------------------------------------------------ 证据

    @Override
    @Transactional
    public VideoBountyEvidenceView recordEvidence(String operator, long submissionId,
                                                  VideoBountyEvidenceRequest request) {
        VideoBountySubmission row = submissionMapper.selectById(submissionId);
        if (row == null) {
            throw BountyTipException.notFound("SUBMISSION_NOT_FOUND", "投稿不存在");
        }
        if (!Integer.valueOf(VideoBountySubmission.STATUS_APPROVED).equals(row.getStatus())) {
            throw BountyTipException.conflict("EVIDENCE_NOT_ALLOWED", "只有已通过的投稿可以录入播放证据");
        }
        if (request == null) {
            throw BountyTipException.badRequest("REQUEST_REQUIRED", "缺少证据内容");
        }
        Integer evidenceType = request.getEvidenceType() == null
                ? VideoBountyEvidence.TYPE_MANUAL_VERIFIED : request.getEvidenceType();
        if (evidenceType != VideoBountyEvidence.TYPE_MANUAL_VERIFIED
                && evidenceType != VideoBountyEvidence.TYPE_PLATFORM_EXPORT) {
            throw BountyTipException.badRequest("EVIDENCE_TYPE_INVALID", "证据类型不合法");
        }
        String sourceRef = trimTo(request.getSourceRef(), 200);
        if (sourceRef == null || sourceRef.isEmpty()) {
            // 没有可追溯的外部来源 = 我方自证,一律不收。
            throw BountyTipException.badRequest("SOURCE_REF_REQUIRED",
                    "必须提供外部可信来源(平台报表编号/导出文件/核验截图摘要)");
        }
        Date periodStart = request.getPeriodStart();
        Date periodEnd = request.getPeriodEnd();
        if (periodStart == null || periodEnd == null) {
            throw BountyTipException.badRequest("PERIOD_REQUIRED", "必须提供统计区间");
        }
        if (periodEnd.before(periodStart)) {
            throw BountyTipException.badRequest("PERIOD_INVALID", "统计区间终点早于起点");
        }
        Long views = request.getViews();
        if (views == null || views < 0) {
            throw BountyTipException.badRequest("VIEWS_INVALID", "播放次数必须是非负整数");
        }
        int organic = Boolean.FALSE.equals(request.getOrganic()) ? 0 : 1;
        int verifiable = Boolean.FALSE.equals(request.getVerifiable()) ? 0 : 1;
        String capturedBy = trimTo(operator, 64);
        if (capturedBy == null || capturedBy.isEmpty()) {
            throw BountyTipException.unauthorized("OPERATOR_REQUIRED", "缺少录入人");
        }

        VideoBountyEvidence evidence = new VideoBountyEvidence();
        evidence.setSubmissionId(submissionId);
        evidence.setEvidenceType(evidenceType);
        evidence.setSourceRef(sourceRef);
        evidence.setCapturedBy(capturedBy);
        evidence.setPeriodStart(periodStart);
        evidence.setPeriodEnd(periodEnd);
        evidence.setViews(views);
        evidence.setOrganic(organic);
        evidence.setVerifiable(verifiable);
        evidence.setNote(trimTo(request.getNote(), 500));
        evidence.setEvidenceHash(evidenceHash(evidence, submissionId));

        VideoBountyEvidence stored = evidenceMapper.selectByHash(evidence.getEvidenceHash());
        if (stored == null) {
            try {
                evidenceMapper.insertEvidence(evidence);
                stored = evidence;
            } catch (DuplicateKeyException duplicate) {
                // 并发同内容录入:唯一键保证只落一行。
                stored = evidenceMapper.selectByHash(evidence.getEvidenceHash());
            }
        }
        if (stored == null) {
            throw new IllegalStateException("证据录入失败");
        }
        return VideoBountyEvidenceView.of(stored);
    }

    // ------------------------------------------------------------------ 原视频组核验

    @Override
    @Transactional
    public VideoBountySubmissionView verifyOriginalGroup(String reviewerRef, long submissionId,
                                                         String verifiedGroupKey, String sourceRef,
                                                         Integer expectedVersion, String reason) {
        requireReviewerRef(reviewerRef);
        if (reason == null || reason.trim().isEmpty()) {
            throw BountyTipException.badRequest("REASON_REQUIRED", "核验必须填写理由");
        }
        if (sourceRef == null || sourceRef.trim().isEmpty()) {
            throw BountyTipException.badRequest("SOURCE_REF_REQUIRED", "核验必须填写证据引用");
        }
        String groupKey = verifiedGroupKey == null || verifiedGroupKey.trim().isEmpty()
                ? null : verifiedGroupKey.trim().toLowerCase(java.util.Locale.ROOT);
        if (groupKey != null && !groupKey.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw BountyTipException.badRequest("GROUP_KEY_MALFORMED",
                    "原视频组键只允许小写字母、数字、下划线、连字符(≤64)");
        }
        VideoBountySubmission target = submissionMapper.selectByIdForUpdate(submissionId);
        if (target == null) {
            throw BountyTipException.notFound("SUBMISSION_NOT_FOUND", "投稿不存在");
        }
        if (!Integer.valueOf(VideoBountySubmission.STATUS_APPROVED).equals(target.getStatus())) {
            throw BountyTipException.conflict("GROUP_VERIFY_NOT_ALLOWED", "只有已通过的投稿可以核验原视频组");
        }
        // 已经按旧组计过应计就不能改组:否则「先按小组计满、再并组」等于把封顶抬高一倍。
        VideoBountyAccrual active = accrualMapper.selectActiveBySubmission(submissionId);
        if (active != null
                && !java.util.Objects.equals(active.getGroupKey(), groupKey)) {
            throw BountyTipException.conflict("GROUP_LOCKED_BY_ACCRUAL",
                    "该投稿已有有效应计，原视频组不可再变更");
        }
        requireSameAuthorGroup(target, groupKey);
        int currentVersion = target.getVerifiedGroupVersion() == null ? 0 : target.getVerifiedGroupVersion();
        int expected = expectedVersion == null ? currentVersion : expectedVersion;
        if (expected != currentVersion) {
            throw BountyTipException.conflict("GROUP_VERIFY_VERSION_CONFLICT",
                    "核验版本冲突，请重新读取后重试");
        }
        java.util.Date now = new java.util.Date();
        Long operatorUserId = operatorUserId(reviewerRef);
        String ref = trimTo(sourceRef, 200);
        int updated = submissionMapper.markGroupVerified(
                submissionId, groupKey, operatorUserId, now, ref, currentVersion);
        if (updated == 0) {
            VideoBountySubmission fresh = submissionMapper.selectById(submissionId);
            boolean sameConclusion = fresh != null
                    && java.util.Objects.equals(fresh.getVerifiedGroupKey(), groupKey)
                    && java.util.Objects.equals(fresh.getVerifiedSourceRef(), ref);
            if (sameConclusion) {
                // 同一结论重投:幂等返回,不重复记审计。
                return VideoBountySubmissionView.of(fresh);
            }
            throw BountyTipException.conflict("GROUP_VERIFY_VERSION_CONFLICT",
                    "核验版本冲突，请重新读取后重试");
        }
        writeGroupVerifyAudit(target, groupKey, ref, reason.trim(), operatorUserId);
        VideoBountySubmission after = submissionMapper.selectById(submissionId);
        return VideoBountySubmissionView.of(after == null ? target : after);
    }

    // ------------------------------------------------------------------ 应计

    @Override
    @Transactional
    public VideoBountyAccrual recomputeAccrual(long submissionId) {
        VideoBountySubmission target = submissionMapper.selectById(submissionId);
        if (target == null) {
            throw BountyTipException.notFound("SUBMISSION_NOT_FOUND", "投稿不存在");
        }
        if (!Integer.valueOf(VideoBountySubmission.STATUS_APPROVED).equals(target.getStatus())) {
            throw BountyTipException.conflict("ACCRUAL_NOT_ALLOWED", "只有已通过的投稿可以计算应计");
        }
        // 先锁活动行:后面的"已用预算 + 本次应计"必须在同一把锁下读,否则两个并发重算
        // 会各自读到旧合计,双双通过预算检查 ⇒ 超发。锁粒度是单个活动,量小可接受。
        VideoCampaign campaign = campaignMapper.selectByCampaignKeyForUpdate(target.getCampaignKey());
        if (campaign == null) {
            throw BountyTipException.notFound("CAMPAIGN_NOT_FOUND", "悬赏活动不存在");
        }
        VideoBountyRuleCalculator.Rule rule = new VideoBountyRuleCalculator.Rule(
                campaign.getRatePerThousand(),
                campaign.getPerVideoCap(),
                campaign.getWindowDays() == null ? 7 : campaign.getWindowDays(),
                campaign.getCurrency());

        // 分组只认运营核验结论:作者自报的 originGroupKey 只作建议,不能决定封顶。
        // 未核验 ⇒ 不生成确定应计(不猜、不按作者自报兜底),由运营先核验再说。
        String verifiedGroupKey = target.getVerifiedGroupKey();
        if (verifiedGroupKey == null || verifiedGroupKey.trim().isEmpty()) {
            throw BountyTipException.conflict("ACCRUAL_GROUP_UNVERIFIED",
                    "该投稿的原视频组尚未核验，不能生成应计");
        }
        // 同组所有有效投稿一起算:封顶是"同一原视频跨平台合计",单独算一条必然超发。
        List<VideoBountySubmission> group =
                submissionMapper.selectActiveByVerifiedGroup(campaign.getId(), verifiedGroupKey);
        if (group.isEmpty()) group = List.of(target);

        // 组内每个投稿的实体与播放基数:插入兄弟行时要用**它自己的**窗口,不能借 target 的。
        Map<Long, VideoBountySubmission> groupById = new LinkedHashMap<>(group.size() * 2);
        Map<Long, Long> viewsById = new LinkedHashMap<>(group.size() * 2);
        List<VideoBountyRuleCalculator.Contribution> contributions = new ArrayList<>(group.size());
        for (VideoBountySubmission submission : group) {
            long views = validViews(submission);
            groupById.put(submission.getId(), submission);
            viewsById.put(submission.getId(), Long.valueOf(views));
            contributions.add(new VideoBountyRuleCalculator.Contribution(submission.getId(), views));
        }
        VideoBountyRuleCalculator.GroupCalculation calculation =
                VideoBountyRuleCalculator.compute(rule, contributions);
        VideoBountyRuleCalculator.Allocation requested = calculation.forSubmission(submissionId);
        if (requested == null) {
            throw new IllegalStateException("应计计算缺少本投稿的分配结果");
        }

        Long watermark = evidenceMapper.selectMaxIdByVerifiedGroup(campaign.getId(), verifiedGroupKey);
        long evidenceWatermark = watermark == null ? 0L : watermark;

        // ===== 整组一次算完、一次写全 =====
        // 封顶是「组内合计 <= per_video_cap」,额度按组内播放量比例分摊 => 一条投稿变化会改变
        // 全组每条的应得额。只写自己那一条、把别人作废,会把「越封顶」修成「漏算」:
        // A 10万->50、B 10万->50;B 涨到 30万后,正解是 A 25 + B 75 = 100,而不是 A 0 + B 75。
        // 因此这里为组内**每个**投稿都落一条新行,再整组替换旧行。
        List<VideoBountyRuleCalculator.Allocation> allocations = calculation.getAllocations();
        BigDecimal[] groupPayable = applyCampaignBudgetOnce(campaign, verifiedGroupKey, calculation);

        // 组组成的真实输入身份:活跃成员 + 各自播放基数。
        // ⚠️ 只靠 maxEvidenceId 不够:B 带**更旧**的 evidence id 加入 A 的组时组内 max 不变,
        // 旧幂等键会原样重现并被当成"本次结果"复用 ⇒ A 旧额 + B 新增超封顶。
        String compositionDigest = groupCompositionDigest(group, viewsById);
        // 代次 = 该组既有应计行的最大 id。单调、只随插入增长,故"有插入的两次不同代"必不同;
        // 同一状态重复重算不插入 ⇒ 不需要新 key。用它替代随机键,也避免回到旧组成时撞上
        // 已 SUPERSEDED 的旧 key(旧 key 行 id 必小于当前代次)。
        Long maxAccrualId = accrualMapper.selectMaxIdByGroup(campaign.getId(), verifiedGroupKey);
        long generation = maxAccrualId == null ? 0L : maxAccrualId;

        List<Long> keepIds = new ArrayList<>(allocations.size());
        VideoBountyAccrual result = null;
        for (int i = 0; i < allocations.size(); i++) {
            VideoBountyRuleCalculator.Allocation allocation = allocations.get(i);
            VideoBountySubmission owner = groupById.get(allocation.getSubmissionId());
            if (owner == null) {
                if (allocation.getSubmissionId() != submissionId) {
                    throw new IllegalStateException("整组应计缺少投稿实体 submissionId="
                            + allocation.getSubmissionId());
                }
                owner = target;
            }
            BigDecimal payable = groupPayable[i];

            // 复用判据是**输入事实全等**,不是"幂等键命中":键命中只能说明同一输入算过,
            // 而旧实现把键当成了结果缓存 —— 组组成变化未进键时会复用旧额(超封顶的根因)。
            VideoBountyAccrual active = accrualMapper.selectActiveBySubmission(allocation.getSubmissionId());
            if (active != null && sameAccrualInputs(active, campaign, verifiedGroupKey, rule, allocation,
                    calculation, evidenceWatermark, owner, payable)) {
                keepIds.add(active.getId());
                if (allocation.getSubmissionId() == submissionId) result = active;
                continue;
            }

            String idempotencyKey = accrualIdempotencyKey(campaign.getId(), verifiedGroupKey,
                    campaign.getRuleVersion(), compositionDigest, evidenceWatermark,
                    allocation.getSubmissionId(), generation);
            VideoBountyAccrual row = new VideoBountyAccrual();
            row.setSubmissionId(allocation.getSubmissionId());
            row.setCampaignId(campaign.getId());
            row.setGroupKey(verifiedGroupKey);
            row.setRuleVersion(campaign.getRuleVersion());
            row.setBasisViews(allocation.getViews());
            row.setRatePerThousand(rule.getRatePerThousand());
            row.setPerVideoCap(rule.getPerVideoCap());
            row.setGroupBasisViews(calculation.getGroupViews());
            row.setGrossAmount(allocation.getGrossAmount());
            row.setPayableAmount(payable);
            row.setCurrency(rule.getCurrency());
            // 恒为"仅记录":本域没有兑付通道,写成待兑付/已打款就是假账。
            row.setPayoutState(VideoBountyAccrual.PAYOUT_RECORDED_ONLY);
            row.setIdempotencyKey(idempotencyKey);
            // 这一行代表**owner 这个投稿**,窗口必须是它自己的;用 target 会把兄弟行写成别人的窗口。
            row.setWindowStart(owner.getPublishedAt());
            row.setWindowEnd(owner.getWindowEndsAt());
            row.setEvidenceWatermark(evidenceWatermark);
            row.setNote(payable.compareTo(allocation.getPayableAmount()) < 0
                    ? "clamped by campaign budget cap" : null);
            try {
                accrualMapper.insertAccrual(row);
            } catch (DuplicateKeyException duplicate) {
                // 并发:对手方已写入同一键。只有仍是有效值才可复用 —— 已 SUPERSEDED 的旧值
                // 不得被当成当前结果(那既会复活历史,也会让本轮丢掉这条兄弟行)。
                VideoBountyAccrual winner = accrualMapper.selectByIdempotencyKey(idempotencyKey);
                if (winner == null) {
                    throw duplicate;
                }
                if (!isActive(winner)) {
                    throw new IllegalStateException(
                            "应计幂等键已被历史作废行占用，拒绝复用 submissionId="
                                    + allocation.getSubmissionId());
                }
                keepIds.add(winner.getId());
                if (allocation.getSubmissionId() == submissionId) result = winner;
                continue;
            }
            keepIds.add(row.getId());
            if (allocation.getSubmissionId() == submissionId) result = row;
        }

        // 组内旧行里不在保留集的全部作废:组内只留本轮这一套(同 watermark + ruleVersion)。
        accrualMapper.supersedeActiveByGroupExceptIds(campaign.getId(), verifiedGroupKey, keepIds);

        if (result == null) {
            // 本投稿本轮没有有效行:读当前有效值,绝不把已 SUPERSEDED 的旧水位行当作当前 active 返回。
            VideoBountyAccrual active = accrualMapper.selectActiveBySubmission(submissionId);
            if (active == null) {
                throw new IllegalStateException("整组重算后本投稿没有有效应计，需人工核对");
            }
            return active;
        }
        return result;
    }

    /** 组组成的规范摘要(按 id 升序的 "id:views"),即本组计算的真实输入身份。 */
    static String groupCompositionDigest(List<VideoBountySubmission> group, Map<Long, Long> viewsById) {
        StringBuilder canonical = new StringBuilder("video-bounty-group-v1");
        for (VideoBountySubmission member : group) {
            Long views = viewsById.get(member.getId());
            canonical.append('|').append(member.getId()).append(':')
                    .append(views == null ? -1L : views.longValue());
        }
        return CanonicalDigest.sha256Hex(canonical.toString());
    }

    /**
     * 应计行幂等键。定长 64 字符(原 joined 串在 groupKey 达到 64 时已接近列上限 VARCHAR(160))。
     *
     * <p>组成摘要 + 代次是这次修复的核心:缺前者会让"组成变了但组内 max 证据号没变"的
     * 旧行被当成本次结果复用;缺后者会让"回到旧组成"撞上已 SUPERSEDED 的旧键。
     */
    static String accrualIdempotencyKey(long campaignId, String groupKey, String ruleVersion,
                                        String compositionDigest, long evidenceWatermark,
                                        long submissionId, long generation) {
        return CanonicalDigest.sha256Hex(CanonicalDigest.join(
                String.valueOf(campaignId),
                groupKey,
                ruleVersion,
                compositionDigest,
                String.valueOf(evidenceWatermark),
                String.valueOf(submissionId),
                String.valueOf(generation)));
    }

    /**
     * 既有有效行是否**恰好代表**本次计算结果(输入事实全等才可复用)。
     *
     * <p>比较项覆盖 calculator 的全部输入与结果:组成员基数与组基数、规则版本/费率/封顶、
     * 该投稿自己的窗口与证据水位、以及夹预算后的应得额与币种。任一处不等都必须重算 ——
     * 这正是"B 加入后 A 的 100 必须变成 75"的判据。
     */
    static boolean sameAccrualInputs(VideoBountyAccrual row, VideoCampaign campaign, String groupKey,
                                     VideoBountyRuleCalculator.Rule rule,
                                     VideoBountyRuleCalculator.Allocation allocation,
                                     VideoBountyRuleCalculator.GroupCalculation calculation,
                                     long evidenceWatermark, VideoBountySubmission owner,
                                     BigDecimal payable) {
        if (row == null || row.getId() == null) return false;
        if (!equalsLong(row.getCampaignId(), campaign.getId())) return false;
        if (!equalsLong(row.getSubmissionId(), allocation.getSubmissionId())) return false;
        if (!equalsText(row.getGroupKey(), groupKey)) return false;
        if (!equalsText(row.getRuleVersion(), campaign.getRuleVersion())) return false;
        if (!equalsText(row.getCurrency(), rule.getCurrency())) return false;
        if (!equalsLong(row.getEvidenceWatermark(), Long.valueOf(evidenceWatermark))) return false;
        if (!equalsLong(row.getBasisViews(), Long.valueOf(allocation.getViews()))) return false;
        if (!equalsLong(row.getGroupBasisViews(), Long.valueOf(calculation.getGroupViews()))) return false;
        if (!equalsDecimal(row.getRatePerThousand(), rule.getRatePerThousand())) return false;
        if (!equalsDecimal(row.getPerVideoCap(), rule.getPerVideoCap())) return false;
        if (!equalsDecimal(row.getPayableAmount(), payable)) return false;
        if (!equalsTime(row.getWindowStart(), owner.getPublishedAt())) return false;
        return equalsTime(row.getWindowEnd(), owner.getWindowEndsAt());
    }

    private static boolean equalsLong(Long left, Long right) {
        return left == null ? right == null : left.equals(right);
    }

    private static boolean equalsText(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static boolean equalsDecimal(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private static boolean equalsTime(Date left, Date right) {
        if (left == null || right == null) return left == right;
        return left.getTime() == right.getTime();
    }

    /** 仍算有效的应计状态:只有 SUPERSEDED(3) / REVERSED(4) 之外的才是当前值。 */
    private static boolean isActive(VideoBountyAccrual accrual) {
        Integer state = accrual.getPayoutState();
        return state == null
                || (!Integer.valueOf(3).equals(state) && !Integer.valueOf(4).equals(state));
    }

    /**
     * 活动总预算只对整组应计夹一次,并把夹后的组总额按各条应得额比例分摊回去。
     *
     * <p>两件事都要:<ol>
     *   <li>预算按组外已用额计算 —— 本组旧行即将整组替换,若算进已用额,
     *       预算打满时新应计会被压成 0(越封顶修成漏算);</li>
     *   <li>分摊在组内做一次 —— 逐条各自 clamp 会让"组总额已超预算"被反复触发,
     *       且尾差与比例都不守恒。</li>
     * </ol>
     */
    private BigDecimal[] applyCampaignBudgetOnce(VideoCampaign campaign, String groupKey,
                                                 VideoBountyRuleCalculator.GroupCalculation calculation) {
        List<VideoBountyRuleCalculator.Allocation> allocations = calculation.getAllocations();
        BigDecimal[] payable = new BigDecimal[allocations.size()];
        for (int i = 0; i < allocations.size(); i++) {
            payable[i] = allocations.get(i).getPayableAmount();
        }
        if (campaign.getTotalBudgetCap() == null) {
            return payable;
        }
        BigDecimal usedElsewhere = campaignMapper.sumActiveAccrualExcludingGroup(campaign.getId(), groupKey);
        if (usedElsewhere == null) usedElsewhere = BigDecimal.ZERO;
        BigDecimal remaining = campaign.getTotalBudgetCap().subtract(usedElsewhere);
        if (remaining.signum() <= 0) {
            java.util.Arrays.fill(payable,
                    BigDecimal.ZERO.setScale(VideoBountyRuleCalculator.MONEY_SCALE));
            return payable;
        }
        BigDecimal groupTarget = calculation.getPayableTotal().min(remaining);
        long[] weights = new long[allocations.size()];
        long weightSum = 0L;
        for (int i = 0; i < allocations.size(); i++) {
            weights[i] = allocations.get(i).getPayableAmount()
                    .movePointRight(VideoBountyRuleCalculator.MONEY_SCALE).longValueExact();
            weightSum = Math.addExact(weightSum, weights[i]);
        }
        long target = groupTarget.movePointRight(VideoBountyRuleCalculator.MONEY_SCALE).longValueExact();
        if (target >= weightSum) {
            return payable;
        }
        long[] split = com.chengyinhub.business.service.support.RevenueMoney.allocate(target, weights);
        for (int i = 0; i < split.length; i++) {
            payable[i] = BigDecimal.valueOf(split[i], VideoBountyRuleCalculator.MONEY_SCALE);
        }
        return payable;
    }

    @Override
    public List<VideoBountyAccrual> listMyAccruals(long memberId) {
        if (memberId <= 0) {
            throw BountyTipException.unauthorized("LOGIN_REQUIRED", "请先登录");
        }
        List<VideoBountySubmission> submissions = submissionMapper.selectByMember(memberId, null);
        List<VideoBountyAccrual> accruals = new ArrayList<>();
        for (VideoBountySubmission submission : submissions) {
            accruals.addAll(accrualMapper.selectBySubmission(submission.getId()));
        }
        return accruals;
    }

    // ------------------------------------------------------------------ 运营查询

    @Override
    public List<VideoBountySubmission> adminListSubmissions(Long campaignId, Integer status, int offset, int limit) {
        return submissionMapper.selectAdminPage(campaignId, status, nonNegative(offset), clampLimit(limit));
    }

    @Override
    public List<VideoBountyAccrual> adminListAccruals(Long campaignId, int offset, int limit) {
        return accrualMapper.selectAdminPage(campaignId, nonNegative(offset), clampLimit(limit));
    }

    @Override
    @Transactional
    public VideoCampaign createCampaign(String operatorRef, VideoCampaignRequest request) {
        requireOperatorRef(operatorRef);
        if (request == null) {
            throw BountyTipException.badRequest("REQUEST_REQUIRED", "缺少活动配置");
        }
        String campaignKey = trimTo(request.getCampaignKey(), 40);
        if (campaignKey == null || campaignKey.isEmpty()) {
            throw BountyTipException.badRequest("CAMPAIGN_KEY_REQUIRED", "缺少活动标识");
        }
        if (!campaignKey.matches("[A-Za-z0-9][A-Za-z0-9_-]*")) {
            throw BountyTipException.badRequest("CAMPAIGN_KEY_MALFORMED",
                    "活动标识只允许字母、数字、下划线、连字符");
        }
        if (campaignMapper.selectByCampaignKey(campaignKey) != null) {
            throw BountyTipException.conflict("CAMPAIGN_KEY_TAKEN", "活动标识已存在");
        }
        String title = trimTo(request.getTitle(), 120);
        if (title == null || title.isEmpty()) {
            throw BountyTipException.badRequest("TITLE_REQUIRED", "缺少活动标题");
        }
        VideoCampaign row = new VideoCampaign();
        row.setCampaignKey(campaignKey);
        row.setTitle(title);
        row.setDescription(trimTo(request.getDescription(), 1000));
        // 新建一律草稿:先配好规则与预算,再由显式的状态流转开放投稿。
        row.setStatus(VideoCampaign.STATUS_DRAFT);
        row.setOwnerMemberId(requireOwnerMember(request.getOwnerMemberId()));
        row.setCurrency(normalizeCurrency(request.getCurrency()));
        row.setRatePerThousand(requireNonNegative(request.getRatePerThousand(), "ratePerThousand"));
        row.setPerVideoCap(requireNonNegative(request.getPerVideoCap(), "perVideoCap"));
        row.setWindowDays(requireWindowDays(request.getWindowDays()));
        row.setTotalBudgetCap(request.getTotalBudgetCap());
        row.setCashPayoutEnabled(0);   // 现金兑付默认关:不因为建了活动就允许付钱
        row.setRuleVersion(requireRuleVersion(request.getRuleVersion()));
        row.setConfigJson(trimTo(request.getConfigJson(), 4000));
        try {
            campaignMapper.insertCampaign(row);
        } catch (DuplicateKeyException duplicate) {
            throw BountyTipException.conflict("CAMPAIGN_KEY_TAKEN", "活动标识已存在");
        }
        return campaignMapper.selectById(row.getId());
    }

    @Override
    @Transactional
    public VideoCampaign updateCampaign(String operatorRef, long campaignId, VideoCampaignRequest request) {
        requireOperatorRef(operatorRef);
        if (request == null) {
            throw BountyTipException.badRequest("REQUEST_REQUIRED", "缺少活动配置");
        }
        VideoCampaign current = campaignMapper.selectById(campaignId);
        if (current == null) {
            throw BountyTipException.notFound("CAMPAIGN_NOT_FOUND", "悬赏活动不存在");
        }
        // 币种是活动口径,不能靠一次更新悄悄换掉 —— 历史应计按旧币种记录。
        if (request.getCurrency() != null
                && !normalizeCurrency(request.getCurrency()).equals(current.getCurrency())) {
            throw BountyTipException.conflict("CURRENCY_LOCKED", "活动币种不可修改;请新建活动");
        }
        BigDecimal rate = requireNonNegative(request.getRatePerThousand(), "ratePerThousand");
        BigDecimal cap = requireNonNegative(request.getPerVideoCap(), "perVideoCap");
        int windowDays = requireWindowDays(request.getWindowDays());
        boolean ruleChanged = current.getRatePerThousand() == null
                || current.getRatePerThousand().compareTo(rate) != 0
                || current.getPerVideoCap() == null || current.getPerVideoCap().compareTo(cap) != 0
                || current.getWindowDays() == null || current.getWindowDays() != windowDays;
        if (ruleChanged) {
            BigDecimal accrued = campaignMapper.sumActiveAccrual(campaignId);
            if (accrued != null && accrued.signum() > 0) {
                // 已有应计还改规则 = 追溯改写历史口径。明确拒绝,让运营新建活动或新规则版本。
                throw BountyTipException.conflict("RULE_LOCKED_BY_ACCRUAL",
                        "该活动已有应计记录,不能修改费率/封顶/窗口;请新建活动");
            }
        }
        // 归属变更会改变归因主体(谁有权看这条 campaign 的成交)。已有应计时锁死,
        // 否则等于把别人的归因读权限换人 —— 这是权限变更,不能由一次普通更新完成。
        // 不传 ownerMemberId = 保持不变(不会被这次更新清空)。
        if (request.getOwnerMemberId() != null) {
            Long requestedOwner = requireOwnerMember(request.getOwnerMemberId());
            if (!java.util.Objects.equals(requestedOwner, current.getOwnerMemberId())) {
                BigDecimal attributed = campaignMapper.sumActiveAccrual(campaignId);
                if (attributed != null && attributed.signum() > 0) {
                    throw BountyTipException.conflict("OWNER_LOCKED_BY_ACCRUAL",
                            "该活动已有应计记录,不能变更归属 owner;请新建活动");
                }
                current.setOwnerMemberId(requestedOwner);
            }
        }
        current.setTitle(requireTitle(request.getTitle()));
        current.setDescription(trimTo(request.getDescription(), 1000));
        current.setTotalBudgetCap(request.getTotalBudgetCap());
        // 现金兑付开关只允许"关→关/关→开"?不:本域不实现兑付,任何非 0 值都拒绝写入。
        Integer requestedPayout = request.getCashPayoutEnabled();
        if (requestedPayout != null && requestedPayout != 0) {
            throw BountyTipException.badRequest("CASH_PAYOUT_NOT_AVAILABLE",
                    "现金兑付尚未启用:本域只记录应计,不允许打开兑付开关");
        }
        current.setCashPayoutEnabled(0);
        current.setRatePerThousand(rate);
        current.setPerVideoCap(cap);
        current.setWindowDays(windowDays);
        current.setRuleVersion(requireRuleVersion(request.getRuleVersion()));
        current.setConfigJson(trimTo(request.getConfigJson(), 4000));
        campaignMapper.updateCampaign(current);
        return campaignMapper.selectById(campaignId);
    }

    @Override
    @Transactional
    public VideoCampaign changeCampaignStatus(String operatorRef, long campaignId, int targetStatus) {
        requireOperatorRef(operatorRef);
        if (targetStatus != VideoCampaign.STATUS_DRAFT && targetStatus != VideoCampaign.STATUS_ACTIVE
                && targetStatus != VideoCampaign.STATUS_PAUSED && targetStatus != VideoCampaign.STATUS_CLOSED) {
            throw BountyTipException.badRequest("STATUS_INVALID", "活动状态不合法");
        }
        VideoCampaign current = campaignMapper.selectById(campaignId);
        if (current == null) {
            throw BountyTipException.notFound("CAMPAIGN_NOT_FOUND", "悬赏活动不存在");
        }
        if (campaignMapper.updateStatusIfCurrent(campaignId, current.getStatus(), targetStatus) != 1) {
            throw BountyTipException.conflict("STATUS_CONFLICT", "活动状态已变化,请刷新后重试");
        }
        return campaignMapper.selectById(campaignId);
    }

    @Override
    public List<VideoCampaign> listCampaigns(Integer status) {
        return campaignMapper.selectList(status);
    }

    @Override
    public List<java.util.Map<String, Object>> listOpenCampaigns() {
        List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
        List<VideoCampaign> open = campaignMapper.selectOpen();
        if (open == null) return rows;
        for (VideoCampaign campaign : open) {
            // 与投稿同一判据:只认进行中;防御式再判一次,mapper 条件漂移时不把暂停/关闭的活动露给作者。
            if (campaign == null || !Integer.valueOf(VideoCampaign.STATUS_ACTIVE).equals(campaign.getStatus())
                    || !"0".equals(campaign.getDelFlag() == null ? "0" : campaign.getDelFlag())) continue;
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("campaignKey", campaign.getCampaignKey());
            row.put("title", campaign.getTitle());
            row.put("description", campaign.getDescription());
            row.put("windowDays", campaign.getWindowDays());
            rows.add(row);
        }
        return rows;
    }

    // ------------------------------------------------------------------ 成交归因(只读归因域)

    /**
     * {@inheritDoc}
     *
     * <p>聚合主体永远是<b>投稿行上记录的作者</b>(服务端读),目标永远是<b>投稿行的作品主题</b>。
     * 客户端无法把 sharer 或 target 换成别人/别的作品 —— 这正是"裸 campaignKey 不能当来源证明"的落地。
     */
    @Override
    public VideoCampaignAttributionView submissionAttribution(long memberId, long submissionId) {
        if (memberId <= 0) {
            throw BountyTipException.unauthorized("LOGIN_REQUIRED", "请先登录");
        }
        VideoBountySubmission submission = submissionMapper.selectById(submissionId);
        // 他人投稿按"不存在"处理:不泄露是否存在。
        if (submission == null || !java.util.Objects.equals(submission.getMemberId(), memberId)) {
            throw BountyTipException.notFound("SUBMISSION_NOT_FOUND", "投稿不存在");
        }
        return aggregateFor(submission);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code reviewerRef} 只用于"你是否有权看",<b>不</b>作为聚合主体:
     * 聚合主体仍是投稿作者(服务端读)。超管亦是如此 —— 它看到的是该作者的归因,
     * 而不是拿自己的 id 去查一个空集(那会静默显示 0 成交)。
     */
    @Override
    public VideoCampaignAttributionView reviewedAttribution(String reviewerRef, long submissionId) {
        requireReviewerRef(reviewerRef);
        VideoBountySubmission submission = submissionMapper.selectById(submissionId);
        if (submission == null) {
            throw BountyTipException.notFound("SUBMISSION_NOT_FOUND", "投稿不存在");
        }
        VideoCampaign campaign = campaignMapper.selectById(submission.getCampaignId());
        if (campaign == null) {
            throw BountyTipException.notFound("CAMPAIGN_NOT_FOUND", "悬赏活动不存在");
        }
        // 除了具备审阅权限的后台账号(sys:),只有该活动的真实 owner 本人可以看。
        // owner 是从 DB 读出来的,不是请求里带的 —— 客户端不能自称 owner。
        boolean isBackoffice = reviewerRef.startsWith("sys:");
        boolean isCampaignOwner = campaign.getOwnerMemberId() != null
                && reviewerRef.equals("member:" + campaign.getOwnerMemberId());
        if (!isBackoffice && !isCampaignOwner) {
            throw BountyTipException.forbidden("ATTRIBUTION_FORBIDDEN", "无权查看该活动的成交归因");
        }
        return aggregateFor(submission);
    }

    /**
     * {@inheritDoc}
     *
     * <p>owner 口径:必须是 {@code campaign.ownerMemberId},且 {@code topicId} 必须真的出现在
     * 该活动下<b>已通过</b>的投稿里。否则任何会员都能拿别人的 campaignKey/任意 topic 去探数字。
     */
    @Override
    public VideoCampaignAttributionView ownerCampaignAttribution(long memberId, long campaignId, long topicId) {
        if (memberId <= 0) {
            throw BountyTipException.unauthorized("LOGIN_REQUIRED", "请先登录");
        }
        VideoCampaign campaign = campaignMapper.selectById(campaignId);
        if (campaign == null) {
            throw BountyTipException.notFound("CAMPAIGN_NOT_FOUND", "悬赏活动不存在");
        }
        if (campaign.getOwnerMemberId() == null) {
            return VideoCampaignAttributionView.ownerNotSet(
                    campaign.getCampaignKey(), MarketingReferralCredential.TARGET_TOPIC, topicId);
        }
        if (!campaign.getOwnerMemberId().equals(memberId)) {
            throw BountyTipException.forbidden("ATTRIBUTION_FORBIDDEN", "无权查看该活动的成交归因");
        }
        if (topicId <= 0 || !campaignHasApprovedTopic(campaignId, topicId)) {
            // 目标 scope 校验:不在本活动已通过投稿里的主题,一律拒绝(否则可借任意 topic 探测)。
            throw BountyTipException.forbidden("TARGET_OUT_OF_SCOPE", "该主题不属于这个悬赏活动");
        }
        return VideoCampaignAttributionView.from(attributionService.aggregateOwnedCampaign(
                memberId, campaign.getCampaignKey(), MarketingReferralCredential.TARGET_TOPIC, topicId));
    }

    /**
     * 用投稿行自带的作者 + 作品主题去读归因。两者都来自 DB,不接受调用方覆盖。
     */
    private VideoCampaignAttributionView aggregateFor(VideoBountySubmission submission) {
        if (submission.getMemberId() == null || submission.getMemberId() <= 0
                || submission.getTopicId() == null || submission.getTopicId() <= 0
                || submission.getCampaignKey() == null || submission.getCampaignKey().isBlank()) {
            return VideoCampaignAttributionView.unbound(
                    submission.getCampaignKey(),
                    MarketingReferralCredential.TARGET_TOPIC,
                    submission.getTopicId());
        }
        return VideoCampaignAttributionView.from(attributionService.aggregateOwnedCampaign(
                submission.getMemberId(),
                submission.getCampaignKey(),
                MarketingReferralCredential.TARGET_TOPIC,
                submission.getTopicId()));
    }

    /** 该活动下是否真有这个主题的已通过投稿(目标 scope 的判定依据)。 */
    private boolean campaignHasApprovedTopic(long campaignId, long topicId) {
        return submissionMapper.countApprovedByCampaignAndTopic(campaignId, topicId) > 0;
    }

    /**
     * 校验活动归属 owner:必须是真实存在且可用的会员。
     *
     * <p>为什么必须校验:owner 决定<b>谁能读这条 campaign 的成交归因</b>。
     * 允许填一个不存在的 id,就等于把归因主体指向一个没有人的位置 —— 读面永远是空,
     * 看起来像"0 成交",实际是配置错了。null 表示未指定(允许,但读面会明确标未配置)。
     */
    private Long requireOwnerMember(Long ownerMemberId) {
        if (ownerMemberId == null) return null;
        if (ownerMemberId <= 0) {
            throw BountyTipException.badRequest("OWNER_INVALID", "活动归属会员不合法");
        }
        UmsMember member = memberMapper.selectUmsMemberById(ownerMemberId);
        if (member == null || !Long.valueOf(1L).equals(member.getStatus())) {
            throw BountyTipException.badRequest("OWNER_UNAVAILABLE", "活动归属会员不存在或不可用");
        }
        return ownerMemberId;
    }

    /**
     * 运营身份:{@code sys:<id>}(后台账号)。与会员 {@code member:<id>} 是两个空间,
     * 混用会让"同一串数字"既是运营又是买家。
     */
    private static void requireOperatorRef(String operatorRef) {
        if (operatorRef == null || !operatorRef.matches("(sys|member):[1-9][0-9]{0,18}")) {
            throw BountyTipException.unauthorized("OPERATOR_REQUIRED", "缺少合法的运营人标识");
        }
    }

    /**
     * 审核人标识必须带 realm 前缀({@code sys:} / {@code member:})。
     *
     * <p>后台运营是 sys_user、会员是 ums_member,两套 id 空间。只存裸数字会让
     * "1 号"同时指两个人 ⇒ 审计无法回答"这单是谁审的"。缺前缀直接拒绝,不猜。
     */
    private static void requireReviewerRef(String reviewerRef) {
        if (reviewerRef == null || !reviewerRef.matches("(sys|member):[1-9][0-9]{0,18}")) {
            throw BountyTipException.unauthorized("REVIEWER_REQUIRED", "缺少合法的审核人标识");
        }
    }

    /** 从 {@code sys:123} / {@code member:123} 取出数字 id;前缀已由 requireReviewerRef 校验。 */
    private static Long operatorUserId(String reviewerRef) {
        int colon = reviewerRef.indexOf(':');
        return Long.valueOf(reviewerRef.substring(colon + 1));
    }

    /**
     * 组键只能在同一活动下的<b>同一作者</b>之间成立。
     *
     * <p>否则核验人可以把 A 的投稿并进 B 的组：既可用别人的 cap 额度压掉另一作者的应计，
     * 也能把两条无关视频凑成一组。跨作者合并属另一套合同，不在本方法里放开。
     */
    private void requireSameAuthorGroup(VideoBountySubmission target, String groupKey) {
        if (groupKey == null || target.getCampaignId() == null) {
            return;
        }
        List<VideoBountySubmission> members =
                submissionMapper.selectActiveByVerifiedGroup(target.getCampaignId(), groupKey);
        if (members == null) {
            return;
        }
        for (VideoBountySubmission member : members) {
            if (member.getId() != null && member.getId().equals(target.getId())) {
                continue;
            }
            if (!java.util.Objects.equals(member.getMemberId(), target.getMemberId())) {
                throw BountyTipException.conflict("GROUP_AUTHOR_MISMATCH",
                        "该原视频组已属于其他作者，不能跨作者合并封顶");
            }
        }
    }

    /** 原视频组核验留痕:操作者、证据引用、理由、前/后组键;写不进去就整笔失败。 */
    private void writeGroupVerifyAudit(VideoBountySubmission before, String afterGroupKey,
                                       String sourceRef, String reason, Long operatorUserId) {
        if (businessAuditEventMapper == null) {
            throw BountyTipException.conflict("AUDIT_UNAVAILABLE", "核验审计不可用，已拒绝本次操作");
        }
        com.chengyinhub.business.domain.BusinessAuditEvent audit =
                new com.chengyinhub.business.domain.BusinessAuditEvent();
        audit.setDomainType("VIDEO_BOUNTY");   // business_audit_event.domain_type 是 VARCHAR(16)
        audit.setDomainId(before.getId());
        audit.setOwnerMemberId(before.getMemberId());
        audit.setActorType(com.chengyinhub.business.domain.BusinessAuditEvent.ACTOR_TYPE_SYS_USER);
        audit.setActorMemberId(operatorUserId);
        audit.setActorRoleCode("SYS_USER");
        audit.setAction("VIDEO_BOUNTY_GROUP_VERIFY");
        audit.setTargetType("VIDEO_BOUNTY_SUBMISSION");
        audit.setTargetId(before.getId());
        audit.setBeforeJson("{\"verifiedGroupKey\":"
                + (before.getVerifiedGroupKey() == null ? "null" : "\"" + before.getVerifiedGroupKey() + "\"")
                + ",\"authorOriginGroupKey\":"
                + (before.getOriginGroupKey() == null ? "null" : "\"" + before.getOriginGroupKey() + "\"")
                + "}");
        audit.setAfterJson("{\"verifiedGroupKey\":"
                + (afterGroupKey == null ? "null" : "\"" + afterGroupKey + "\"")
                + ",\"verifiedSourceRef\":\"" + sourceRef + "\"}");
        audit.setReason(reason);
        audit.setRequestId("video-bounty-group-verify:" + before.getId()
                + ":" + (before.getVerifiedGroupVersion() == null ? 0 : before.getVerifiedGroupVersion()));
        audit.setCreateTime(new java.util.Date());
        if (businessAuditEventMapper.insertBusinessAuditEvent(audit) != 1) {
            throw BountyTipException.conflict("AUDIT_WRITE_FAILED", "核验审计写入失败，已回滚");
        }
    }

    private static String requireTitle(String title) {
        String trimmed = trimTo(title, 120);
        if (trimmed == null || trimmed.isEmpty()) {
            throw BountyTipException.badRequest("TITLE_REQUIRED", "缺少活动标题");
        }
        return trimmed;
    }

    private static String normalizeCurrency(String currency) {
        String normalized = trimTo(currency, 3);
        if (normalized == null || !normalized.matches("[A-Z]{3}")) {
            throw BountyTipException.badRequest("CURRENCY_INVALID", "币种必须是 3 位大写 ISO-4217 代码");
        }
        return normalized;
    }

    private static BigDecimal requireNonNegative(BigDecimal value, String field) {
        if (value == null || value.signum() < 0) {
            throw BountyTipException.badRequest("AMOUNT_INVALID", field + " 必须是非负金额");
        }
        return value;
    }

    private static int requireWindowDays(Integer windowDays) {
        if (windowDays == null || windowDays <= 0 || windowDays > 365) {
            throw BountyTipException.badRequest("WINDOW_INVALID", "统计窗口必须是 1..365 天");
        }
        return windowDays;
    }

    private static String requireRuleVersion(String ruleVersion) {
        String trimmed = trimTo(ruleVersion, 24);
        if (trimmed == null || !trimmed.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw BountyTipException.badRequest("RULE_VERSION_REQUIRED", "缺少合法的规则版本标识");
        }
        return trimmed;
    }

    // ------------------------------------------------------------------ 内部

    /** 窗口内有效自然播放合计:只算有机 + 可核验 + 整段落在窗口内的证据。 */
    private long validViews(VideoBountySubmission submission) {
        long total = 0L;
        List<VideoBountyEvidence> evidenceRows = evidenceMapper.selectBySubmission(submission.getId());
        for (VideoBountyEvidence evidence : evidenceRows) {
            if (!Integer.valueOf(1).equals(evidence.getOrganic())) continue;
            if (!Integer.valueOf(1).equals(evidence.getVerifiable())) continue;
            if (!VideoBountyRuleCalculator.fullyInsideWindow(evidence.getPeriodStart(), evidence.getPeriodEnd(),
                    submission.getPublishedAt(), submission.getWindowEndsAt())) {
                // 区间跨窗口边界时无法拆分:宁可少计,也不把窗口外播放算进来。
                log.debug("[video-bounty] evidence {} outside window of submission {}",
                        evidence.getId(), submission.getId());
                continue;
            }
            total += evidence.getViews() == null ? 0L : evidence.getViews();
        }
        return total;
    }

    /**
     * 活动预算封顶。没设预算上限时原样返回(不假设已批准支出);
     * 设了则把本条压到剩余预算内,绝不超发。
     */
    private BigDecimal clampToCampaignBudget(VideoCampaign campaign, BigDecimal payable) {
        if (campaign.getTotalBudgetCap() == null) return payable;
        BigDecimal used = campaignMapper.sumActiveAccrual(campaign.getId());
        if (used == null) used = BigDecimal.ZERO;
        BigDecimal remaining = campaign.getTotalBudgetCap().subtract(used);
        if (remaining.signum() <= 0) return BigDecimal.ZERO.setScale(VideoBountyRuleCalculator.MONEY_SCALE);
        return payable.min(remaining);
    }

    private VideoCampaign requireActiveCampaign(String campaignKey) {
        if (campaignKey == null || campaignKey.isBlank()) {
            throw BountyTipException.badRequest("CAMPAIGN_REQUIRED", "缺少悬赏活动");
        }
        VideoCampaign campaign = campaignMapper.selectByCampaignKey(campaignKey.trim());
        if (campaign == null) {
            throw BountyTipException.notFound("CAMPAIGN_NOT_FOUND", "悬赏活动不存在");
        }
        if (!Integer.valueOf(VideoCampaign.STATUS_ACTIVE).equals(campaign.getStatus())) {
            throw BountyTipException.conflict("CAMPAIGN_NOT_ACTIVE", "悬赏活动当前不接受投稿");
        }
        return campaign;
    }

    /** 作品归属必须是一条真实已上架主题 —— 投稿不能指向不存在的作品。 */
    private void requirePublishedTopic(Long topicId) {
        if (topicId == null || topicId <= 0) {
            throw BountyTipException.badRequest("TOPIC_REQUIRED", "必须选择作品归属的主题");
        }
        CmsTopic topic = topicService.selectCmsTopicById(topicId);
        if (topic == null || !Integer.valueOf(1).equals(topic.getStatus())) {
            throw BountyTipException.notFound("TOPIC_NOT_FOUND", "作品归属的主题不存在或未上架");
        }
    }

    private VideoBountySubmission requireOwnSubmission(long memberId, long submissionId) {
        if (memberId <= 0) {
            throw BountyTipException.unauthorized("LOGIN_REQUIRED", "请先登录");
        }
        VideoBountySubmission row = submissionMapper.selectById(submissionId);
        // 他人投稿一律按"不存在"处理:不泄露是否存在。
        if (row == null || !java.util.Objects.equals(row.getMemberId(), memberId)) {
            throw BountyTipException.notFound("SUBMISSION_NOT_FOUND", "投稿不存在");
        }
        return row;
    }

    /**
     * 证据内容摘要:录入幂等键。
     *
     * <p>注意包含 submissionId 与 sourceRef —— 同一投稿同一区间同样的播放数,
     * 若来源不同(两次独立核验)也应各留一条:那是两条证据,不是重复。
     */
    private String evidenceHash(VideoBountyEvidence evidence, long submissionId) {
        String canonical = CanonicalDigest.join(
                String.valueOf(submissionId),
                String.valueOf(evidence.getEvidenceType()),
                evidence.getSourceRef(),
                String.valueOf(evidence.getPeriodStart().getTime()),
                String.valueOf(evidence.getPeriodEnd().getTime()),
                String.valueOf(evidence.getViews()),
                String.valueOf(evidence.getOrganic()),
                String.valueOf(evidence.getVerifiable()));
        return sha256(canonical);
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format("%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private static int nonNegative(int value) {
        return Math.max(0, value);
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) return 20;
        return Math.min(limit, MAX_LIST_LIMIT);
    }

    private static String trimTo(String text, int max) {
        if (text == null) return null;
        String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
