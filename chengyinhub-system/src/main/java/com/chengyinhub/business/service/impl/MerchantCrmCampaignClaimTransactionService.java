package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.MerchantCrmCampaign;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.MerchantCrmCampaignMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/** 只收口 campaign claim/reset 与严格 actor 审计；任何外部投递都不得进入这里。 */
@Service
public class MerchantCrmCampaignClaimTransactionService {
    @Autowired private MerchantCrmCampaignMapper campaignMapper;
    @Autowired private BusinessAuditEventMapper auditMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void claimDispatch(MerchantCrmCampaign campaign, Long actorMemberId,
                              String actorRoleCode, String processingToken, int leaseSeconds) {
        if (campaignMapper.markCampaignProcessing(campaign.getMerchantRowId(), campaign.getId(),
                MerchantCrmCampaign.READY, processingToken, leaseSeconds) != 1) {
            throw new ServiceException("触达任务已被其他操作处理", 409);
        }
        appendAudit(campaign, "CRM_CAMPAIGN_DISPATCHED",
                "crm-campaign-" + campaign.getId() + "-dispatch", actorMemberId, actorRoleCode);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int claimRetry(MerchantCrmCampaign campaign, Long actorMemberId,
                          String actorRoleCode, String processingToken, int leaseSeconds,
                          String auditRequestId) {
        if (campaignMapper.markCampaignProcessing(campaign.getMerchantRowId(), campaign.getId(),
                MerchantCrmCampaign.PARTIAL_FAILED, processingToken, leaseSeconds) != 1) {
            throw new ServiceException("触达任务已被其他操作处理", 409);
        }
        int reset = campaignMapper.resetRetryableRecipients(
                campaign.getMerchantRowId(), campaign.getId());
        appendAudit(campaign, "CRM_CAMPAIGN_RETRIED", auditRequestId,
                actorMemberId, actorRoleCode);
        return reset;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean invalidateStale(MerchantCrmCampaign campaign) {
        long expectedVersion = campaign.getVersion() == null ? 0L : campaign.getVersion();
        if (campaignMapper.invalidateStaleCampaign(campaign.getMerchantRowId(), campaign.getId(),
                campaign.getOwnerMemberId(), expectedVersion) != 1) {
            return false;
        }
        appendInvalidationAudit(campaign,
                "crm-campaign-" + campaign.getId() + "-invalid-v" + expectedVersion);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean invalidateClaimed(MerchantCrmCampaign campaign, String processingToken) {
        if (campaignMapper.invalidateClaimedCampaign(campaign.getMerchantRowId(), campaign.getId(),
                campaign.getOwnerMemberId(), processingToken) != 1) {
            return false;
        }
        appendInvalidationAudit(campaign,
                "crm-campaign-" + campaign.getId() + "-invalid-claim");
        return true;
    }

    private void appendInvalidationAudit(MerchantCrmCampaign campaign, String requestId) {
        BusinessAuditEvent audit = new BusinessAuditEvent();
        audit.setDomainType("MERCHANT");
        audit.setDomainId(campaign.getMerchantRowId());
        audit.setOwnerMemberId(campaign.getOwnerMemberId());
        audit.setActorMemberId(campaign.getOwnerMemberId());
        audit.setActorRoleCode("SYSTEM_RECOVERY");
        audit.setAction("CRM_CAMPAIGN_INVALIDATED");
        audit.setTargetType("CRM_CAMPAIGN");
        audit.setTargetId(campaign.getId());
        audit.setRelationId(campaign.getSegmentId());
        audit.setBeforeJson("{\"status\":\"PROCESSING\"}");
        audit.setAfterJson("{\"status\":\"NO_ELIGIBLE\","
                + "\"error\":\"MERCHANT_INACTIVE_OR_SCOPE_CHANGED\"}");
        audit.setReason("商家不存在、已停用、已删除或归属已变化");
        audit.setRequestId(requestId);
        audit.setCreateTime(new Date());
        if (auditMapper.insertBusinessAuditEvent(audit) != 1) {
            throw new ServiceException("失效触达任务审计写入失败", 409);
        }
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
                + (campaign.getRecipientCount() == null ? 0 : campaign.getRecipientCount()) + "}");
        audit.setRequestId(requestId);
        audit.setCreateTime(new Date());
        if (auditMapper.insertBusinessAuditEvent(audit) != 1) {
            throw new ServiceException("触达操作审计写入失败", 409);
        }
    }
}
