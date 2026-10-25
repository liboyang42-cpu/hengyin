package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.MerchantCrmCampaign;
import com.chengyinhub.business.domain.MerchantCrmCampaignCandidate;
import com.chengyinhub.business.domain.MerchantCrmCampaignRecipient;
import com.chengyinhub.business.domain.MerchantCrmMarketingConsent;
import com.chengyinhub.business.domain.MerchantCrmSegment;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.dto.MerchantCrmCampaignPreviewRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmCampaignRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmCustomerQuery;
import com.chengyinhub.business.domain.dto.MerchantCrmSegmentFilter;
import com.chengyinhub.business.domain.vo.MerchantCrmCampaignPreviewVO;
import com.chengyinhub.business.domain.vo.MerchantCrmCampaignVO;
import com.chengyinhub.business.domain.vo.MerchantCrmCouponOptionVO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.MerchantCrmAppMapper;
import com.chengyinhub.business.mapper.MerchantCrmCampaignMapper;
import com.chengyinhub.business.mapper.MerchantCrmMarketingConsentMapper;
import com.chengyinhub.business.mapper.MerchantCrmSegmentMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.IMerchantCrmCampaignService;
import com.chengyinhub.business.service.IMerchantCrmRecipientDeliveryService;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class MerchantCrmCampaignServiceImpl implements IMerchantCrmCampaignService {
    static final int RECIPIENT_LIMIT = 200;
    static final int PROCESSING_LEASE_SECONDS = 300;
    static final int RECOVERY_LIMIT = 20;
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{6,64}");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private IMerchantAccessService accessService;
    @Autowired private MerchantCrmSegmentMapper segmentMapper;
    @Autowired private MerchantCrmAppMapper appMapper;
    @Autowired private MerchantCrmCampaignMapper campaignMapper;
    @Autowired private MerchantCrmMarketingConsentMapper consentMapper;
    @Autowired private MerchantCrmCouponGrantAdapter couponGrantAdapter;
    @Autowired private IMerchantCrmRecipientDeliveryService recipientDeliveryService;
    @Autowired private MmsMerchantMapper merchantMapper;
    @Autowired private BusinessAuditEventMapper auditMapper;
    @Autowired private MerchantCrmCampaignClaimTransactionService claimTransactionService;
    @Autowired private com.chengyinhub.business.service.IWxContentSecurityService wxSec;
    @Autowired private com.chengyinhub.business.service.IUmsMemberService umsMemberService;

    @Override
    public List<MerchantCrmCouponOptionVO> coupons(Long principalMemberId) {
        MerchantAccessContext access = accessService.require(principalMemberId, MerchantPermission.COUPON_MANAGE);
        return couponGrantAdapter.list(access.getOwnerMemberId(), access.getMerchantId());
    }

    @Override
    public MerchantCrmCampaignPreviewVO preview(Long principalMemberId,
                                                 MerchantCrmCampaignPreviewRequest request) {
        MerchantAccessContext access = accessService.require(principalMemberId, MerchantPermission.MARKETING_WRITE);
        String channel = channel(request == null ? null : request.getChannel());
        requireChannelPermission(access, channel);
        MerchantCrmSegment segment = ownedSegment(access.getMerchantId(), request == null ? null : request.getSegmentId());
        MerchantCrmCustomerQuery query = query(segment);
        query.setMerchantId(access.getMerchantId());
        long total = appMapper.selectOwnedCustomerCount(access.getOwnerMemberId(), query);
        List<MerchantCrmCampaignCandidate> candidates = candidates(access.getOwnerMemberId(), query);
        int consented = 0;
        int frequencyLimited = 0;
        for (MerchantCrmCampaignCandidate candidate : candidates) {
            if (consentMapper.selectLatestOptIn(candidate.getMemberId(), access.getOwnerMemberId(),
                    access.getMerchantId(), channel) != null) {
                consented++;
                if (campaignMapper.countDeliveredToday(access.getMerchantId(), candidate.getMemberId(), channel, -1L) > 0) {
                    frequencyLimited++;
                }
            }
        }
        MerchantCrmCampaignPreviewVO out = new MerchantCrmCampaignPreviewVO();
        out.setSegmentId(segment.getId());
        out.setChannel(channel);
        out.setTotalCount((int) Math.min(Integer.MAX_VALUE, total));
        out.setConsentedCount(consented);
        out.setFrequencyLimitedCount(frequencyLimited);
        out.setDeliverableCount(consented - frequencyLimited);
        out.setRecipientLimit(RECIPIENT_LIMIT);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantCrmCampaignVO create(Long principalMemberId, MerchantCrmCampaignRequest request) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        MerchantAccessContext access = accessService.require(principalMemberId, MerchantPermission.MARKETING_WRITE);
        String channel = channel(request == null ? null : request.getChannel());
        requireChannelPermission(access, channel);
        MerchantCrmSegment segment = ownedSegment(access.getMerchantId(), request == null ? null : request.getSegmentId());
        Long couponId = request == null ? null : request.getCouponId();
        if (MerchantCrmMarketingConsent.COUPON.equals(channel)) {
            couponGrantAdapter.requireOwnedActive(access.getOwnerMemberId(), access.getMerchantId(), couponId);
        }
        else if (couponId != null) throw new ServiceException("站内消息触达不可携带优惠券", 400);
        String title = text(request == null ? null : request.getTitle(), 60, "请填写触达标题");
        String content = text(request == null ? null : request.getContent(), 500, "请填写触达内容");
        String requestId = requestId(request == null ? null : request.getRequestId());

        MerchantCrmCampaign replay = campaignMapper.selectByRequestId(access.getMerchantId(), requestId);
        if (replay != null) return exactReplay(replay, access.getMerchantId(), principalMemberId, segment.getId(), channel,
                couponId, title, content, false);
        // F-L5C-02:触达标题与正文以系统消息卡发给顾客,与 /broadcast 同一道机审闸,审不了按 fail-close 拦截。
        String securityError = com.chengyinhub.business.service.IWxContentSecurityService.checkTextAsMember(wxSec,
                umsMemberService, principalMemberId,
                com.chengyinhub.business.service.IWxContentSecurityService.joinText(title, content));
        if (securityError != null) {
            boolean unavailable = com.chengyinhub.business.service.IWxContentSecurityService.isDetectionUnavailable(securityError);
            throw new ServiceException(securityError, unavailable ? 503 : 400);
        }

        MerchantCrmCustomerQuery query = query(segment);
        query.setMerchantId(access.getMerchantId());
        List<MerchantCrmCampaignCandidate> candidates = candidates(access.getOwnerMemberId(), query);
        MmsMerchant merchant = merchantMapper.selectMmsMerchantById(access.getMerchantId());
        if (merchant == null || !access.getOwnerMemberId().equals(merchant.getMemberId())) {
            throw new ServiceException("商家主体已变化，请重新登录", 409);
        }
        MerchantCrmCampaign campaign = new MerchantCrmCampaign();
        campaign.setOwnerMemberId(access.getOwnerMemberId());
        campaign.setMerchantRowId(access.getMerchantId());
        campaign.setMerchantNameSnapshot(merchant.getName() == null || merchant.getName().trim().isEmpty()
                ? "商家" : merchant.getName().trim());
        campaign.setSegmentId(segment.getId());
        campaign.setSegmentFilterJson(segment.getFilterJson());
        campaign.setChannel(channel);
        campaign.setCouponId(couponId);
        campaign.setTitle(title);
        campaign.setContent(content);
        campaign.setActorMemberId(principalMemberId);
        campaign.setActorRoleCode(access.getRoleCode());
        campaign.setRequestId(requestId);
        campaign.setStatus(MerchantCrmCampaign.READY);
        campaign.setRecipientCount(candidates.size());
        campaign.setDeliveredCount(0);
        campaign.setNoConsentCount(0);
        campaign.setFrequencySkippedCount(0);
        campaign.setFailedCount(0);
        campaign.setCreateTime(new Date());
        try {
            if (campaignMapper.insertCampaign(campaign) != 1) throw new ServiceException("触达任务创建失败", 409);
        } catch (DuplicateKeyException e) {
            // 唯一键冲突后的回执须越过当前事务的旧快照。
            MerchantCrmCampaign existing = campaignMapper.selectByRequestIdForShare(access.getMerchantId(), requestId);
            return exactReplay(existing, access.getMerchantId(), principalMemberId, segment.getId(), channel,
                    couponId, title, content, true);
        }
        for (MerchantCrmCampaignCandidate candidate : candidates) {
            MerchantCrmMarketingConsent consent = consentMapper.selectLatestOptIn(
                    candidate.getMemberId(), access.getOwnerMemberId(), access.getMerchantId(), channel);
            MerchantCrmCampaignRecipient recipient = new MerchantCrmCampaignRecipient();
            recipient.setCampaignId(campaign.getId());
            recipient.setMerchantId(access.getMerchantId());
            recipient.setMemberId(candidate.getMemberId());
            recipient.setCustomerNameSnapshot(candidate.getName());
            recipient.setChannel(channel);
            recipient.setConsentEventIdSnapshot(consent == null ? null : consent.getId());
            if (campaignMapper.insertRecipient(recipient) != 1) throw new ServiceException("触达名单冻结失败", 409);
        }
        appendAudit(campaign, "CRM_CAMPAIGN_CREATED", requestId,
                principalMemberId, access.getRoleCode());
        return toVO(campaign, new ArrayList<MerchantCrmCampaignRecipient>());
    }

    @Override
    public MerchantCrmCampaignVO dispatch(Long principalMemberId, Long campaignId) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        MerchantAccessContext access = accessService.require(principalMemberId, MerchantPermission.MARKETING_WRITE);
        MerchantCrmCampaign campaign = ownedCampaign(access, campaignId);
        requireChannelPermission(access, campaign.getChannel());
        if (MerchantCrmCampaign.SUCCESS.equals(campaign.getStatus())) return detailWithAccess(access, campaignId);
        if (!MerchantCrmCampaign.READY.equals(campaign.getStatus())) {
            throw new ServiceException("触达任务当前不可发送；部分失败请使用重试", 409);
        }
        String processingToken = token();
        claimTransactionService.claimDispatch(campaign, principalMemberId, access.getRoleCode(),
                processingToken, PROCESSING_LEASE_SECONDS);
        // 外部渠道调用必须在短事务提交后执行；失败由租约恢复，不得回滚已持久化的 claim/audit receipt。
        process(campaign, processingToken);
        return detailWithAccess(access, campaignId);
    }

    @Override
    public MerchantCrmCampaignVO retry(Long principalMemberId, Long campaignId) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        MerchantAccessContext access = accessService.require(principalMemberId, MerchantPermission.MARKETING_WRITE);
        MerchantCrmCampaign campaign = ownedCampaign(access, campaignId);
        requireChannelPermission(access, campaign.getChannel());
        if (!MerchantCrmCampaign.PARTIAL_FAILED.equals(campaign.getStatus())) {
            throw new ServiceException("当前任务没有可重试的失败收件人", 409);
        }
        String processingToken = token();
        int reset = claimTransactionService.claimRetry(campaign, principalMemberId,
                access.getRoleCode(), processingToken, PROCESSING_LEASE_SECONDS,
                "crm-campaign-" + campaignId + "-retry-" + System.currentTimeMillis());
        if (reset > 0) process(campaign, processingToken);
        else campaignMapper.finalizeCampaign(access.getMerchantId(), campaignId, processingToken);
        return detailWithAccess(access, campaignId);
    }

    @Override
    public MerchantCrmCampaignVO detail(Long principalMemberId, Long campaignId) {
        MerchantAccessContext access = accessService.require(principalMemberId, MerchantPermission.MARKETING_READ);
        return detailWithAccess(access, campaignId);
    }

    @Override
    public List<MerchantCrmCampaignVO> list(Long principalMemberId) {
        MerchantAccessContext access = accessService.require(principalMemberId, MerchantPermission.MARKETING_READ);
        List<MerchantCrmCampaign> rows = campaignMapper.selectOwnedCampaigns(access.getMerchantId(), 20);
        List<MerchantCrmCampaignVO> out = new ArrayList<>();
        if (rows != null) for (MerchantCrmCampaign row : rows) {
            out.add(toVO(row, new ArrayList<MerchantCrmCampaignRecipient>()));
        }
        return out;
    }

    @Override
    public int recoverStaleProcessing() {
        if (com.chengyinhub.common.config.RestoreDispatchGuard.isRestoreMode()) return 0;
        List<MerchantCrmCampaign> stale = campaignMapper.selectStaleProcessingCampaigns(RECOVERY_LIMIT);
        int recovered = 0;
        if (stale == null) return recovered;
        for (MerchantCrmCampaign campaign : stale) {
            String processingToken = token();
            long expectedVersion = campaign.getVersion() == null ? 0L : campaign.getVersion();
            if (!isActiveCampaignMerchant(campaign)) {
                claimTransactionService.invalidateStale(campaign);
                continue;
            }
            if (campaignMapper.reclaimStaleCampaign(campaign.getMerchantRowId(), campaign.getId(),
                    campaign.getOwnerMemberId(), expectedVersion, processingToken,
                    PROCESSING_LEASE_SECONDS) != 1) {
                // 状态可能在预检与 CAS 之间变化；终态 SQL 自带 NOT EXISTS + version fence。
                claimTransactionService.invalidateStale(campaign);
                continue;
            }
            if (process(campaign, processingToken)) recovered++;
        }
        return recovered;
    }

    private boolean isActiveCampaignMerchant(MerchantCrmCampaign campaign) {
        if (campaign == null || campaign.getMerchantRowId() == null || campaign.getOwnerMemberId() == null) {
            return false;
        }
        MmsMerchant merchant = merchantMapper.selectMmsMerchantById(campaign.getMerchantRowId());
        return merchant != null
                && Objects.equals(campaign.getMerchantRowId(), merchant.getId())
                && Objects.equals(campaign.getOwnerMemberId(), merchant.getMemberId())
                && Long.valueOf(1L).equals(merchant.getStatus())
                && Long.valueOf(1L).equals(merchant.getAccountStatus())
                && Integer.valueOf(0).equals(merchant.getDelFlag());
    }

    private boolean process(MerchantCrmCampaign campaign, String processingToken) {
        Long merchantId = campaign.getMerchantRowId();
        Long campaignId = campaign.getId();
        List<Long> ids = campaignMapper.selectPendingRecipientIds(merchantId, campaignId, RECIPIENT_LIMIT);
        if (ids != null) for (Long recipientId : ids) {
            if (campaignMapper.renewCampaignLease(merchantId, campaignId, processingToken,
                    PROCESSING_LEASE_SECONDS) != 1) {
                if (claimTransactionService.invalidateClaimed(campaign, processingToken)) {
                    return false;
                }
                throw new ServiceException("触达任务租约已被其他处理器接管", 409);
            }
            try {
                recipientDeliveryService.deliver(merchantId, campaignId, recipientId);
            } catch (RuntimeException e) {
                recipientDeliveryService.recordFailure(merchantId, campaignId, recipientId, e);
            }
        }
        if (campaignMapper.finalizeCampaign(merchantId, campaignId, processingToken) != 1) {
            throw new ServiceException("触达任务汇总失败，请刷新", 409);
        }
        return true;
    }

    private MerchantCrmCampaignVO detailWithAccess(MerchantAccessContext access, Long campaignId) {
        MerchantCrmCampaign campaign = ownedCampaign(access, campaignId);
        List<MerchantCrmCampaignRecipient> recipients = campaignMapper.selectOwnedRecipients(
                access.getMerchantId(), campaignId, RECIPIENT_LIMIT);
        return toVO(campaign, recipients == null ? new ArrayList<MerchantCrmCampaignRecipient>() : recipients);
    }

    private MerchantCrmCampaign ownedCampaign(MerchantAccessContext access, Long campaignId) {
        if (campaignId == null || campaignId <= 0) throw new ServiceException("触达任务无效", 400);
        MerchantCrmCampaign campaign = campaignMapper.selectOwnedCampaign(access.getMerchantId(), campaignId);
        if (campaign == null || !Objects.equals(access.getMerchantId(), campaign.getMerchantRowId())) {
            throw new ServiceException("触达任务不存在或不属于当前商家", 404);
        }
        return campaign;
    }

    private MerchantCrmSegment ownedSegment(Long merchantId, Long segmentId) {
        if (segmentId == null || segmentId <= 0) throw new ServiceException("请选择保存分群", 400);
        MerchantCrmSegment segment = segmentMapper.selectOwnedSegmentById(merchantId, segmentId);
        if (segment == null) throw new ServiceException("分群不存在或不属于当前商家", 404);
        return segment;
    }

    private List<MerchantCrmCampaignCandidate> candidates(Long ownerMemberId, MerchantCrmCustomerQuery query) {
        List<MerchantCrmCampaignCandidate> rows = appMapper.selectOwnedCampaignRecipientCandidates(
                ownerMemberId, query, RECIPIENT_LIMIT);
        return rows == null ? new ArrayList<MerchantCrmCampaignCandidate>() : rows;
    }

    private MerchantCrmCustomerQuery query(MerchantCrmSegment segment) {
        try {
            MerchantCrmSegmentFilter filter = JSON.readValue(segment.getFilterJson(), MerchantCrmSegmentFilter.class);
            MerchantCrmCustomerQuery query = new MerchantCrmCustomerQuery();
            query.setPageNum(1);
            query.setPageSize(50);
            query.setSegment(filter.getSegment());
            query.setTagId(filter.getTagId());
            query.setSourceType(filter.getSourceType());
            query.setSourceStart(filter.getSourceStart());
            query.setSourceEnd(filter.getSourceEnd());
            if (filter.getSourceStart() != null) {
                query.setSourceStartTime(Timestamp.valueOf(LocalDate.parse(filter.getSourceStart()).atStartOfDay()));
            }
            if (filter.getSourceEnd() != null) {
                query.setSourceEndExclusive(Timestamp.valueOf(LocalDate.parse(filter.getSourceEnd()).plusDays(1).atStartOfDay()));
            }
            return query;
        } catch (Exception e) {
            throw new ServiceException("保存分群条件已损坏", 500);
        }
    }

    private MerchantCrmCampaignVO exactReplay(MerchantCrmCampaign row, Long merchantRowId,
                                               Long actor, Long segmentId,
                                               String channel, Long couponId, String title,
                                               String content, boolean duplicate) {
        if (row == null || !Objects.equals(merchantRowId, row.getMerchantRowId())
                || !actor.equals(row.getActorMemberId()) || !segmentId.equals(row.getSegmentId())
                || !channel.equals(row.getChannel()) || !Objects.equals(couponId, row.getCouponId())
                || !title.equals(row.getTitle()) || !content.equals(row.getContent())) {
            throw new ServiceException("请求标识已用于其他触达任务", 409);
        }
        List<MerchantCrmCampaignRecipient> recipients = duplicate
                ? campaignMapper.selectOwnedRecipients(row.getMerchantRowId(), row.getId(), RECIPIENT_LIMIT)
                : new ArrayList<MerchantCrmCampaignRecipient>();
        return toVO(row, recipients == null ? new ArrayList<MerchantCrmCampaignRecipient>() : recipients);
    }

    private MerchantCrmCampaignVO toVO(MerchantCrmCampaign row, List<MerchantCrmCampaignRecipient> recipients) {
        MerchantCrmCampaignVO out = new MerchantCrmCampaignVO();
        out.setId(row.getId());
        out.setSegmentId(row.getSegmentId());
        out.setChannel(row.getChannel());
        out.setCouponId(row.getCouponId());
        out.setTitle(row.getTitle());
        out.setStatus(row.getStatus());
        out.setRecipientCount(zero(row.getRecipientCount()));
        out.setDeliveredCount(zero(row.getDeliveredCount()));
        out.setNoConsentCount(zero(row.getNoConsentCount()));
        out.setFrequencySkippedCount(zero(row.getFrequencySkippedCount()));
        out.setFailedCount(zero(row.getFailedCount()));
        out.setRetryableCount(zero(row.getRetryableCount()));
        out.setCreateTime(row.getCreateTime());
        List<MerchantCrmCampaignVO.RecipientReceipt> receipts = new ArrayList<>();
        for (MerchantCrmCampaignRecipient recipient : recipients) {
            MerchantCrmCampaignVO.RecipientReceipt receipt = new MerchantCrmCampaignVO.RecipientReceipt();
            receipt.setRecipientId(recipient.getId());
            receipt.setCustomerName(recipient.getCustomerNameSnapshot());
            receipt.setStatus(recipient.getStatus());
            receipt.setFailureCode(recipient.getFailureCode());
            receipt.setFailureMessage(recipient.getFailureMessage());
            receipt.setMessageReceiptId(recipient.getMessageReceiptId());
            receipt.setCouponHistoryId(recipient.getCouponHistoryId());
            receipt.setDeliveredAt(recipient.getDeliveredAt());
            receipts.add(receipt);
        }
        out.setRecipients(receipts);
        return out;
    }

    private void appendAudit(MerchantCrmCampaign campaign, String action, String requestId,
                             Long actorMemberId, String actorRoleCode) {
        BusinessAuditEvent audit = new BusinessAuditEvent();
        audit.setDomainType("MERCHANT");
        audit.setDomainId(campaign.getMerchantRowId());
        audit.setOwnerMemberId(campaign.getOwnerMemberId());
        audit.setActorMemberId(actorMemberId);
        audit.setActorRoleCode(actorRoleCode);
        audit.setAction(action);
        audit.setTargetType("CRM_CAMPAIGN");
        audit.setTargetId(campaign.getId());
        audit.setRelationId(campaign.getSegmentId());
        audit.setAfterJson("{\"channel\":\"" + campaign.getChannel() + "\",\"recipientCount\":"
                + zero(campaign.getRecipientCount()) + "}");
        audit.setRequestId(requestId);
        audit.setCreateTime(new Date());
        if (auditMapper.insertBusinessAuditEvent(audit) != 1) {
            throw new ServiceException("触达操作审计写入失败", 409);
        }
    }

    private void requireChannelPermission(MerchantAccessContext access, String channel) {
        if (MerchantCrmMarketingConsent.COUPON.equals(channel)
                && !access.hasPermission(MerchantPermission.COUPON_MANAGE)) {
            throw new ServiceException("无优惠券管理权限", 403);
        }
    }

    private String channel(String value) {
        String out = value == null ? "" : value.trim().toUpperCase();
        if (!MerchantCrmMarketingConsent.IN_APP.equals(out)
                && !MerchantCrmMarketingConsent.COUPON.equals(out)) {
            throw new ServiceException("触达渠道无效", 400);
        }
        return out;
    }

    private String text(String value, int max, String message) {
        String out = value == null ? "" : value.trim();
        if (out.isEmpty() || out.length() > max) throw new ServiceException(message, 400);
        return out;
    }

    private String requestId(String value) {
        String out = value == null ? "" : value.trim();
        if (!REQUEST_ID.matcher(out).matches()) throw new ServiceException("请求标识无效", 400);
        return out;
    }

    private int zero(Integer value) { return value == null ? 0 : value; }

    private String token() { return UUID.randomUUID().toString().replace("-", ""); }
}
