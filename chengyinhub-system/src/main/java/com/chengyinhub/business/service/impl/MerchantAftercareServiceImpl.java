package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.MerchantAftercareListRow;
import com.chengyinhub.business.domain.MerchantAftercareResponse;
import com.chengyinhub.business.domain.MerchantRefundPolicySnapshot;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.dto.MerchantAftercareRespondRequest;
import com.chengyinhub.business.domain.vo.MerchantAftercareDetailVO;
import com.chengyinhub.business.domain.vo.MerchantAftercareListItemVO;
import com.chengyinhub.business.domain.vo.MerchantAftercareListVO;
import com.chengyinhub.business.domain.vo.MerchantAftercareResponseVO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.MerchantAftercareMapper;
import com.chengyinhub.business.mapper.MerchantAftercareTimeoutMapper;
import com.chengyinhub.business.service.IMerchantAftercareService;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
public class MerchantAftercareServiceImpl implements IMerchantAftercareService {
    private static final Logger log = LoggerFactory.getLogger(MerchantAftercareServiceImpl.class);
    private static final int CONTENT_MAX_LENGTH = 500;
    private static final int REQUEST_ID_MAX_LENGTH = 64;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    /** 商家售后响应窗口:超过仍未给出同意/驳回意见,自动转平台客服。 */
    static final int MERCHANT_RESPONSE_WINDOW_HOURS = 24;

    @Autowired private IMerchantAccessService merchantAccessService;
    @Autowired private MerchantAftercareMapper merchantAftercareMapper;
    @Autowired private MerchantAftercareTimeoutMapper merchantAftercareTimeoutMapper;
    @Autowired private BusinessAuditEventMapper businessAuditEventMapper;
    @Autowired private MerchantAftercareEvidenceUrlSigner evidenceUrlSigner;
    @Autowired private INotifyService notifyService;

    @Override
    public MerchantAftercareListVO list(Long principalMemberId, String bucket,
                                        Integer pageNum, Integer pageSize) {
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.AFTERCARE_READ);
        String normalizedBucket = normalizeBucket(bucket);
        int normalizedPageNum = pageNum == null ? 1 : pageNum;
        int normalizedPageSize = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (normalizedPageNum < 1) throw badRequest("pageNum 不合法");
        if (normalizedPageSize < 1 || normalizedPageSize > MAX_PAGE_SIZE) {
            throw badRequest("pageSize 必须在 1 到 " + MAX_PAGE_SIZE + " 之间");
        }
        int offset;
        try {
            offset = Math.multiplyExact(normalizedPageNum - 1, normalizedPageSize);
        } catch (ArithmeticException e) {
            throw badRequest("分页参数过大");
        }
        long total = merchantAftercareMapper.countOwnedRefundList(
                access.getMerchantId(), access.getOwnerMemberId(), normalizedBucket);
        List<MerchantAftercareListRow> rows = total == 0 ? java.util.Collections.<MerchantAftercareListRow>emptyList()
                : merchantAftercareMapper.selectOwnedRefundList(
                        access.getMerchantId(), access.getOwnerMemberId(), normalizedBucket, offset, normalizedPageSize);
        List<MerchantAftercareListItemVO> items = new ArrayList<>();
        if (rows != null) {
            for (MerchantAftercareListRow row : rows) items.add(listItem(access, row));
        }
        MerchantAftercareListVO out = new MerchantAftercareListVO();
        out.setBucket(normalizedBucket);
        out.setPageNum(normalizedPageNum);
        out.setPageSize(normalizedPageSize);
        out.setTotal(total);
        out.setHasMore((long) offset + items.size() < total);
        out.setItems(items);
        return out;
    }

    @Override
    public MerchantAftercareDetailVO detail(Long principalMemberId, Long refundId) {
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.AFTERCARE_READ);
        requireRefundId(refundId);
        RefundApplication refund = merchantAftercareMapper.selectOwnedRefundById(
                access.getMerchantId(), access.getOwnerMemberId(), refundId);
        if (refund == null) throw notFound();
        List<MerchantAftercareResponse> responses = merchantAftercareMapper.selectResponses(
                access.getMerchantId(), refundId);
        MerchantRefundPolicySnapshot policy = null;
        if ("registration".equals(refund.getSourceType()) && refund.getSourceId() != null) {
            policy = merchantAftercareMapper.selectOwnedRefundPolicySnapshot(
                    access.getMerchantId(), access.getOwnerMemberId(), refundId);
        }
        MerchantAftercareDetailVO out = detailVO(access, refund, responses, policy);
        applyDisplay(access, merchantAftercareMapper.selectOwnedRefundDisplay(
                access.getMerchantId(), access.getOwnerMemberId(), refundId), out);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantAftercareResponseVO respond(Long principalMemberId, Long refundId,
                                               MerchantAftercareRespondRequest request) {
        String decision = requireDecision(request);
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, requiredPermission(decision));
        requireRoleDecision(access, decision);
        requireRefundId(refundId);
        NormalizedResponse normalized = normalize(request);
        Long lockedMerchantId = merchantAftercareMapper.lockActiveMerchantOwner(
                access.getMerchantId(), access.getOwnerMemberId());
        if (!access.getMerchantId().equals(lockedMerchantId)) {
            throw new ServiceException("商家身份无效或权限不足", HttpStatus.FORBIDDEN);
        }

        MerchantAftercareResponse existing = merchantAftercareMapper.selectByRequestIdForUpdate(
                access.getMerchantId(), normalized.requestId);
        if (existing != null) {
            ensureExactReplay(access, refundId, normalized, existing);
            RefundApplication current = merchantAftercareMapper.selectOwnedRefundById(
                    access.getMerchantId(), access.getOwnerMemberId(), refundId);
            if (current == null) throw notFound();
            return responseVO(access, current, existing);
        }

        RefundApplication refund = merchantAftercareMapper.selectOwnedRefundByIdForUpdate(
                access.getMerchantId(), access.getOwnerMemberId(), refundId);
        if (refund == null) throw notFound();
        if (!Integer.valueOf(0).equals(refund.getStatus())) {
            throw conflict("平台已完成审核，不能再追加商家意见");
        }
        requireEvidenceBoundTo(normalized.evidenceUrl, access.getMerchantId(), refundId);

        MerchantAftercareResponse response = new MerchantAftercareResponse();
        response.setRefundApplicationId(refundId);
        response.setMerchantId(access.getMerchantId());
        response.setMerchantOwnerMemberId(access.getOwnerMemberId());
        response.setActorMemberId(access.getPrincipalMemberId());
        response.setActorRoleCode(access.getRoleCode());
        response.setDecision(normalized.decision);
        response.setContent(normalized.content);
        response.setEvidenceUrl(normalized.evidenceUrl);
        response.setRequestId(normalized.requestId);
        response.setCreateTime(new Date());

        try {
            if (merchantAftercareMapper.insertResponse(response) != 1 || response.getId() == null) {
                throw conflict("售后意见未保存，请重试");
            }
        } catch (DuplicateKeyException e) {
            MerchantAftercareResponse concurrent = merchantAftercareMapper.selectByRequestIdForUpdate(
                    access.getMerchantId(), normalized.requestId);
            ensureExactReplay(access, refundId, normalized, concurrent);
            return responseVO(access, refund, concurrent);
        }

        writeAudit(access, response);
        return responseVO(access, refund, response);
    }

    @Override
    public Long requireEvidenceUploadTarget(Long principalMemberId, Long refundId) {
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.AFTERCARE_READ);
        requireRefundId(refundId);
        RefundApplication refund = merchantAftercareMapper.selectOwnedRefundById(
                access.getMerchantId(), access.getOwnerMemberId(), refundId);
        if (refund == null) throw notFound();
        if (!Integer.valueOf(0).equals(refund.getStatus())) {
            throw conflict("平台已完成审核，不能再追加商家意见");
        }
        if (allowedDecisions(access).isEmpty()) {
            throw new ServiceException("当前岗位无权提交售后凭证", HttpStatus.FORBIDDEN);
        }
        return access.getMerchantId();
    }

    /**
     * 商家售后超时巡检:建单超过 24 小时、商家侧仍无同意/驳回意见的待审退款单,标记为「已转平台客服」,
     * 并给玩家发一条站内信。
     *
     * <p>幂等靠 {@code markPlatformTakeover} 的条件更新(platform_takeover_at is null):并发或重复扫描时
     * 只有拿到 1 行的 worker 负责通知,其余跳过 —— 先标后通知,保证「转过的不重复转」。
     * 单条异常不拖垮整批(逐条捕),本轮未成功的下轮自然重扫。
     */
    @Override
    public int escalateStaleMerchantResponses() {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.add(java.util.Calendar.HOUR_OF_DAY, -MERCHANT_RESPONSE_WINDOW_HOURS);
        final Date cutoff = calendar.getTime();
        List<RefundApplication> stale;
        try {
            stale = merchantAftercareTimeoutMapper.selectStaleMerchantResponse(cutoff);
        } catch (Exception e) {
            log.error("售后超时转平台:扫描失败 cutoff={}", cutoff, e);
            throw new IllegalStateException("售后超时扫描失败", e);
        }
        if (stale == null || stale.isEmpty()) return 0;
        int escalated = 0;
        for (RefundApplication refund : stale) {
            if (refund == null || refund.getId() == null) continue;
            try {
                Date now = new Date();
                if (merchantAftercareTimeoutMapper.markPlatformTakeover(refund.getId(), now) != 1) {
                    // 已被并发 worker 转走或状态已变:不重复通知
                    continue;
                }
                escalated++;
                notifyPlayerPlatformTakeover(refund);
                log.info("[售后超时转平台] refundId={} merchantId={} memberId={}",
                        refund.getId(), refund.getMerchantId(), refund.getMemberId());
            } catch (Exception e) {
                log.error("售后超时转平台:单条处理失败 refundId={}", refund.getId(), e);
            }
        }
        return escalated;
    }

    /** 玩家通知是旁路:失败只留痕,不回滚已提交的转平台标记,更不把本轮扫描打红。 */
    private void notifyPlayerPlatformTakeover(RefundApplication refund) {
        Long memberId = refund.getMemberId();
        if (memberId == null || memberId <= 0) {
            log.warn("[售后超时转平台] 通知跳过:memberId 无效 refundId={}", refund.getId());
            return;
        }
        String amount = refund.getRefundAmount() == null ? ""
                : refund.getRefundAmount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        try {
            if (!notifyService.notifyAftercarePlatformTakeover(memberId, amount)) {
                log.warn("[售后超时转平台] 通知未送达 refundId={} memberId={}", refund.getId(), memberId);
            }
        } catch (Exception e) {
            log.error("[售后超时转平台] 通知异常 refundId={} memberId={}", refund.getId(), memberId, e);
        }
    }

    private MerchantAftercareDetailVO detailVO(MerchantAccessContext access,
                                                RefundApplication refund,
                                                List<MerchantAftercareResponse> rows,
                                                MerchantRefundPolicySnapshot policy) {
        List<MerchantAftercareResponse> safeRows = rows == null
                ? java.util.Collections.<MerchantAftercareResponse>emptyList() : rows;
        String opinion = merchantOpinion(safeRows);
        String processing = processing(refund);
        boolean refunded = refunded(refund);
        List<MerchantAftercareResponseVO> responses = new ArrayList<>();
        for (MerchantAftercareResponse row : safeRows) {
            responses.add(MerchantAftercareResponseVO.from(
                    row, signedEvidenceUrl(row), processing, opinion, refunded));
        }

        MerchantAftercareDetailVO out = new MerchantAftercareDetailVO();
        out.setRefundId(refund.getId());
        out.setRefundNo(refund.getRefundNo());
        out.setSourceType(refund.getSourceType());
        out.setSourceId(refund.getSourceId());
        out.setRefundAmount(refund.getRefundAmount());
        out.setReason(refund.getReason());
        out.setRefundStatus(refund.getStatus());
        out.setPayoutStatus(refund.getPayoutStatus());
        out.setProcessing(processing);
        out.setMerchantOpinion(opinion);
        List<String> allowedDecisions = Integer.valueOf(0).equals(refund.getStatus())
                ? allowedDecisions(access) : java.util.Collections.<String>emptyList();
        out.setAllowedDecisions(allowedDecisions);
        out.setCanRespond(!allowedDecisions.isEmpty());
        out.setCreateTime(refund.getCreateTime());
        if (policy != null) {
            out.setRefundPolicyCode(policy.getPolicyCode());
            out.setRefundPolicyVersion(policy.getPolicyVersion());
            out.setRefundDeadline(policy.getRefundDeadline());
        }
        out.setResponses(responses);
        return out;
    }

    private MerchantAftercareListItemVO listItem(MerchantAccessContext access,
                                                  MerchantAftercareListRow row) {
        MerchantAftercareListItemVO out = new MerchantAftercareListItemVO();
        out.setRefundId(row.getId());
        out.setRefundNo(row.getRefundNo());
        out.setSourceType(row.getSourceType());
        out.setSourceId(row.getSourceId());
        out.setRefundAmount(row.getRefundAmount());
        out.setReason(row.getReason());
        String opinion = row.getMerchantOpinion() == null ? "PENDING" : row.getMerchantOpinion();
        out.setMerchantOpinion(opinion);
        out.setProcessing(processing(row.getStatus(), row.getPayoutStatus()));
        out.setRefunded(refunded(row.getStatus(), row.getPayoutStatus()));
        out.setBucket(bucket(row.getStatus(), row.getPayoutStatus(), opinion));
        out.setCanRespond(Integer.valueOf(0).equals(row.getStatus())
                && !allowedDecisions(access).isEmpty());
        out.setCreateTime(row.getCreateTime());
        out.setActivityTitle(row.getActivityTitle());
        out.setCustomerNickname(visibleNickname(access, row.getCustomerNickname()));
        out.setPlatformTakeoverAt(row.getPlatformTakeoverAt());
        return out;
    }

    private void applyDisplay(MerchantAccessContext access, MerchantAftercareListRow display,
                              MerchantAftercareDetailVO out) {
        if (display == null) return;
        out.setActivityTitle(display.getActivityTitle());
        out.setCustomerNickname(visibleNickname(access, display.getCustomerNickname()));
        out.setPlatformTakeoverAt(display.getPlatformTakeoverAt());
    }

    /** 申请人昵称与商家客户页同口径:没有 CRM 读权限的岗位(如财务)只看售后事实,不看是谁。 */
    private String visibleNickname(MerchantAccessContext access, String nickname) {
        return access != null && access.hasPermission(MerchantPermission.CRM_READ) ? nickname : null;
    }

    private MerchantAftercareResponseVO responseVO(MerchantAccessContext access,
                                                    RefundApplication refund,
                                                    MerchantAftercareResponse response) {
        String opinion;
        if (MerchantAftercareResponse.DECISION_AGREE.equals(response.getDecision())
                || MerchantAftercareResponse.DECISION_REJECT.equals(response.getDecision())) {
            opinion = response.getDecision();
        } else {
            MerchantAftercareResponse latest = merchantAftercareMapper.selectLatestOpinion(
                    access.getMerchantId(), refund.getId());
            opinion = latest == null ? "PENDING" : latest.getDecision();
        }
        return MerchantAftercareResponseVO.from(
                response, signedEvidenceUrl(response), processing(refund), opinion, refunded(refund));
    }

    private String processing(RefundApplication refund) {
        return refund == null ? "UNKNOWN" : processing(refund.getStatus(), refund.getPayoutStatus());
    }

    private String processing(Integer status, Integer payout) {
        if (status == null) return "UNKNOWN";
        if (Integer.valueOf(0).equals(status)) return "WAITING_PLATFORM_REVIEW";
        if (Integer.valueOf(2).equals(status)) return "PLATFORM_REJECTED";
        if (!Integer.valueOf(1).equals(status)) return "UNKNOWN";
        if (Integer.valueOf(RefundApplication.PAYOUT_SUCCESS).equals(payout)
                || Integer.valueOf(RefundApplication.PAYOUT_MANUAL_VERIFIED).equals(payout)) return "REFUNDED";
        if (Integer.valueOf(RefundApplication.PAYOUT_PROCESSING).equals(payout)
                || Integer.valueOf(RefundApplication.PAYOUT_DISPATCH_PENDING).equals(payout)
                || Integer.valueOf(RefundApplication.PAYOUT_DISPATCHING).equals(payout)) return "REFUND_PROCESSING";
        if (Integer.valueOf(RefundApplication.PAYOUT_PENDING_MANUAL).equals(payout)) return "MANUAL_REFUND_PENDING";
        if (Integer.valueOf(RefundApplication.PAYOUT_MANUAL_HANDLED).equals(payout)) return "MANUAL_REFUND_REVIEW";
        return "UNKNOWN";
    }

    private boolean refunded(RefundApplication refund) {
        return refund != null && refunded(refund.getStatus(), refund.getPayoutStatus());
    }

    private boolean refunded(Integer status, Integer payoutStatus) {
        if (!Integer.valueOf(1).equals(status)) return false;
        return Integer.valueOf(RefundApplication.PAYOUT_SUCCESS).equals(payoutStatus)
                || Integer.valueOf(RefundApplication.PAYOUT_MANUAL_VERIFIED).equals(payoutStatus);
    }

    private String bucket(Integer status, Integer payoutStatus, String opinion) {
        if (Integer.valueOf(0).equals(status)) {
            return "PENDING".equals(opinion) ? "PENDING" : "PROCESSING";
        }
        if (Integer.valueOf(1).equals(status) && !refunded(status, payoutStatus)) return "PROCESSING";
        return "COMPLETED";
    }

    private String normalizeBucket(String bucket) {
        String value = bucket == null ? "PENDING" : bucket.trim().toUpperCase(java.util.Locale.ROOT);
        if ("PENDING".equals(value) || "PROCESSING".equals(value)
                || "COMPLETED".equals(value) || "ALL".equals(value)) return value;
        throw badRequest("bucket 仅允许 PENDING/PROCESSING/COMPLETED/ALL");
    }

    private String merchantOpinion(List<MerchantAftercareResponse> rows) {
        String opinion = "PENDING";
        for (MerchantAftercareResponse row : rows) {
            if (MerchantAftercareResponse.DECISION_AGREE.equals(row.getDecision())
                    || MerchantAftercareResponse.DECISION_REJECT.equals(row.getDecision())) {
                opinion = row.getDecision();
            }
        }
        return opinion;
    }

    private NormalizedResponse normalize(MerchantAftercareRespondRequest request) {
        if (request == null || !isDecision(request.getDecision())) {
            throw badRequest("decision 仅允许 AGREE/REJECT/EVIDENCE");
        }
        String content = normalizeOptional(request.getContent(), CONTENT_MAX_LENGTH, "意见内容");
        if (MerchantAftercareResponse.DECISION_REJECT.equals(request.getDecision()) && content == null) {
            throw badRequest("拒绝意见不能为空");
        }
        String evidenceUrl = normalizeEvidence(request);
        if (MerchantAftercareResponse.DECISION_EVIDENCE.equals(request.getDecision())
                && (evidenceUrl == null || evidenceUrl.isEmpty())) {
            throw badRequest("补充凭证必须先上传凭证");
        }
        String requestId = request.getRequestId() == null ? "" : request.getRequestId().trim();
        if (requestId.isEmpty() || requestId.length() > REQUEST_ID_MAX_LENGTH
                || !requestId.matches("[A-Za-z0-9._:-]+")) {
            throw badRequest("requestId 格式不合法");
        }
        return new NormalizedResponse(request.getDecision(), content, evidenceUrl, requestId);
    }

    private boolean isDecision(String decision) {
        return MerchantAftercareResponse.DECISION_AGREE.equals(decision)
                || MerchantAftercareResponse.DECISION_REJECT.equals(decision)
                || MerchantAftercareResponse.DECISION_EVIDENCE.equals(decision);
    }

    private String requireDecision(MerchantAftercareRespondRequest request) {
        if (request == null || !isDecision(request.getDecision())) {
            throw badRequest("decision 仅允许 AGREE/REJECT/EVIDENCE");
        }
        return request.getDecision();
    }

    private String requiredPermission(String decision) {
        return MerchantAftercareResponse.DECISION_EVIDENCE.equals(decision)
                ? MerchantPermission.AFTERCARE_EVIDENCE : MerchantPermission.AFTERCARE_DECIDE;
    }

    private void requireRoleDecision(MerchantAccessContext access, String decision) {
        String role = access == null ? null : access.getRoleCode();
        boolean ownerOrManager = MerchantRoleCode.MERCHANT_OWNER.equals(role)
                || MerchantRoleCode.MERCHANT_MANAGER.equals(role);
        boolean financeEvidence = MerchantRoleCode.MERCHANT_FINANCE.equals(role)
                && MerchantAftercareResponse.DECISION_EVIDENCE.equals(decision);
        if (!ownerOrManager && !financeEvidence) {
            throw new ServiceException("当前岗位无权提交该售后意见", HttpStatus.FORBIDDEN);
        }
    }

    private List<String> allowedDecisions(MerchantAccessContext access) {
        List<String> decisions = new ArrayList<>();
        if (access == null) return decisions;
        String role = access.getRoleCode();
        if ((MerchantRoleCode.MERCHANT_OWNER.equals(role)
                || MerchantRoleCode.MERCHANT_MANAGER.equals(role))
                && access.hasPermission(MerchantPermission.AFTERCARE_DECIDE)) {
            decisions.add(MerchantAftercareResponse.DECISION_AGREE);
            decisions.add(MerchantAftercareResponse.DECISION_REJECT);
        }
        if ((MerchantRoleCode.MERCHANT_OWNER.equals(role)
                || MerchantRoleCode.MERCHANT_MANAGER.equals(role)
                || MerchantRoleCode.MERCHANT_FINANCE.equals(role))
                && access.hasPermission(MerchantPermission.AFTERCARE_EVIDENCE)) {
            decisions.add(MerchantAftercareResponse.DECISION_EVIDENCE);
        }
        return decisions;
    }

    private String normalizeEvidence(MerchantAftercareRespondRequest request) {
        if (request.getEvidenceUrl() != null && !request.getEvidenceUrl().trim().isEmpty()) {
            throw badRequest("evidenceUrl 已停用，请上传后提交 evidenceKeys");
        }
        List<String> keys = request.getEvidenceKeys();
        if (keys != null && keys.size() > 1) {
            throw badRequest("当前售后记录一次仅支持一项凭证");
        }
        if (keys == null || keys.isEmpty()) return null;
        String key = keys.get(0) == null ? null : keys.get(0).trim();
        if (!MerchantAftercareEvidenceUrlSigner.isEvidenceObjectKey(key)) {
            throw badRequest("售后凭证对象标识不合法");
        }
        return key;
    }

    /**
     * W6f:首次写入前核对凭证 key 未被其他退款单引用,且对象元数据绑定本门店 + 本退款单(不限上传者、不限时效)。
     * 不符/无绑定旧对象/不存在 → 400;存储不可用 → 503 可重试,事务内尚未写任何行。replay 已在前面短路。
     */
    private void requireEvidenceBoundTo(String evidenceKey, Long merchantId, Long refundId) {
        if (evidenceKey == null) return;
        if (merchantAftercareMapper.countEvidenceKeyUsedByOtherRefund(evidenceKey, refundId) > 0) {
            throw badRequest("该凭证已用于其他售后单，请重新上传");
        }
        try {
            evidenceUrlSigner.requireBoundTo(evidenceKey, merchantId, refundId);
        } catch (IllegalArgumentException notBound) {
            throw badRequest("售后凭证不属于本售后单，请重新上传");
        } catch (IllegalStateException storageUnavailable) {
            log.error("售后凭证绑定校验暂不可用", storageUnavailable);
            throw new ServiceException("凭证存储暂不可用，请稍后重试", 503);
        }
    }

    /** 返回 null 时 VO 据原始 key 判为 UNAVAILABLE(有凭证但暂不可查看),不再悄悄当作无凭证。 */
    private String signedEvidenceUrl(MerchantAftercareResponse response) {
        if (response == null || !MerchantAftercareEvidenceUrlSigner.isEvidenceObjectKey(response.getEvidenceUrl())) {
            return null;
        }
        try {
            // 读取也核对绑定:不属于该记录门店+退款单(含修复前落库的外店 key、无绑定旧对象)则不签发
            evidenceUrlSigner.requireBoundTo(response.getEvidenceUrl(), response.getMerchantId(),
                    response.getRefundApplicationId());
        } catch (IllegalArgumentException notBound) {
            log.warn("售后凭证绑定不符或无绑定旧对象，标记暂不可查看，responseId={}", response.getId());
            return null;
        } catch (RuntimeException verifyFailure) {
            log.error("售后凭证绑定校验失败，标记暂不可查看，responseId={}", response.getId(), verifyFailure);
            return null;
        }
        try {
            return evidenceUrlSigner.sign(response.getEvidenceUrl());
        } catch (RuntimeException signingFailure) {
            log.error("售后凭证短期访问地址生成失败，标记暂不可查看，responseId={}",
                    response.getId(), signingFailure);
            return null;
        }
    }

    private String normalizeOptional(String value, int maxLength, String label) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > maxLength) throw badRequest(label + "不能超过" + maxLength + "字");
        return normalized;
    }

    private void ensureExactReplay(MerchantAccessContext access, Long refundId,
                                   NormalizedResponse normalized, MerchantAftercareResponse existing) {
        if (existing == null
                || !refundId.equals(existing.getRefundApplicationId())
                || !access.getMerchantId().equals(existing.getMerchantId())
                || !access.getOwnerMemberId().equals(existing.getMerchantOwnerMemberId())
                || !access.getPrincipalMemberId().equals(existing.getActorMemberId())
                || !same(normalized.decision, existing.getDecision())
                || !same(normalized.content, existing.getContent())
                || !same(normalized.evidenceUrl, existing.getEvidenceUrl())) {
            throw conflict("requestId 已用于其他售后请求");
        }
    }

    private boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private void writeAudit(MerchantAccessContext access, MerchantAftercareResponse response) {
        BusinessAuditEvent event = new BusinessAuditEvent();
        event.setDomainType("MERCHANT");
        event.setDomainId(access.getMerchantId());
        event.setOwnerMemberId(access.getOwnerMemberId());
        event.setActorMemberId(access.getPrincipalMemberId());
        event.setActorRoleCode(access.getRoleCode());
        event.setAction("MERCHANT_AFTERCARE_RESPONDED");
        event.setTargetType("REFUND_APPLICATION");
        event.setTargetId(response.getRefundApplicationId());
        event.setRelationId(response.getId());
        event.setAfterJson("{\"decision\":\"" + response.getDecision() + "\",\"responseId\":"
                + response.getId() + "}");
        event.setReason("merchant aftercare opinion");
        event.setRequestId(response.getRequestId());
        event.setCreateTime(new Date());
        if (businessAuditEventMapper.insertBusinessAuditEvent(event) != 1) {
            throw conflict("业务审计写入失败");
        }
    }

    private void requireRefundId(Long refundId) {
        if (refundId == null || refundId <= 0) throw badRequest("refundId 不合法");
    }

    private ServiceException badRequest(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    private ServiceException notFound() {
        return new ServiceException("售后单不存在或无权访问", HttpStatus.NOT_FOUND);
    }

    private ServiceException conflict(String message) {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }

    private static final class NormalizedResponse {
        private final String decision;
        private final String content;
        private final String evidenceUrl;
        private final String requestId;

        private NormalizedResponse(String decision, String content, String evidenceUrl, String requestId) {
            this.decision = decision;
            this.content = content;
            this.evidenceUrl = evidenceUrl;
            this.requestId = requestId;
        }
    }
}
