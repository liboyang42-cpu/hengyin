package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubEventCampaign;
import com.chengyinhub.business.domain.ClubEventDelivery;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.service.IClubEventNotificationTransactionService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Collections;
import java.util.List;

@Service
public class ClubEventNotificationTransactionService implements IClubEventNotificationTransactionService {
    private final ClubEventOpsMapper mapper;
    private final IImService imService;
    private final IClubAccessService accessService;
    private final ClubMapper clubMapper;

    public ClubEventNotificationTransactionService(ClubEventOpsMapper mapper, IImService imService) {
        this(mapper, imService, null, null);
    }

    @Autowired
    public ClubEventNotificationTransactionService(ClubEventOpsMapper mapper, IImService imService,
                                                   IClubAccessService accessService, ClubMapper clubMapper) {
        this.mapper = mapper;
        this.imService = imService;
        this.accessService = accessService;
        this.clubMapper = clubMapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public ClubEventCampaign create(ClubEventCampaign campaign, List<Long> recipients, Date now) {
        if (campaign == null || campaign.getActorMemberId() == null || recipients == null || now == null) {
            throw new IllegalStateException("notification campaign scope missing");
        }
        requireAuthorized(campaign.getActorMemberId(), campaign);
        if (mapper.insertCampaign(campaign) != 1 || campaign.getId() == null) {
            throw new IllegalStateException("notification campaign receipt missing");
        }
        for (Long recipient : recipients) {
            ClubEventDelivery delivery = new ClubEventDelivery();
            delivery.setCampaignId(campaign.getId());
            delivery.setRecipientMemberId(recipient);
            delivery.setChannel("IN_APP");
            delivery.setStatus(ClubEventDelivery.PENDING);
            delivery.setAttemptCount(0);
            delivery.setVersion(0);
            if (mapper.insertDelivery(delivery) != 1 || delivery.getId() == null
                    || mapper.insertOutbox(delivery.getId(), now) != 1) {
                throw new IllegalStateException("notification recipient receipt missing");
            }
        }
        return campaign;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public List<ClubEventDelivery> claim(ClubEventCampaign campaign, int limit, Date leaseUntil) {
        ClubEventCampaign current = requireCurrentCampaign(campaign);
        return claimCurrent(current, limit, leaseUntil);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public List<ClubEventDelivery> claimAuthorized(Long actorMemberId, ClubEventCampaign campaign,
                                                   int limit, Date leaseUntil) {
        requireAuthorized(actorMemberId, campaign);
        ClubEventCampaign current = requireCurrentCampaign(campaign);
        return claimCurrent(current, limit, leaseUntil);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean deliver(ClubEventCampaign campaign, Long deliveryId) {
        if (campaign == null || campaign.getId() == null || deliveryId == null) {
            throw new IllegalStateException("notification delivery scope missing");
        }
        ClubEventDelivery delivery = mapper.selectDeliveryByIdForUpdate(deliveryId);
        if (delivery == null || !campaign.getId().equals(delivery.getCampaignId())) {
            throw new IllegalStateException("notification delivery scope mismatch");
        }
        if (ClubEventDelivery.SENT.equals(delivery.getStatus())
                || ClubEventDelivery.SKIPPED_INELIGIBLE.equals(delivery.getStatus())) return false;
        if (delivery.getRecipientMemberId() == null || delivery.getVersion() == null
                || delivery.getAttemptCount() == null || delivery.getAttemptCount() < 0
                || campaign.getClubId() == null || campaign.getAudienceType() == null
                || !(ClubEventDelivery.PENDING.equals(delivery.getStatus())
                || ClubEventDelivery.FAILED.equals(delivery.getStatus()))) {
            throw new IllegalStateException("notification delivery state mismatch");
        }
        if (delivery.getAttemptCount() >= 5) return false;
        if (mapper.isEligibleNotificationRecipient(campaign.getId(), campaign.getClubId(), campaign.getActivityId(),
                campaign.getAudienceType(), delivery.getRecipientMemberId()) != 1) {
            if (mapper.markDeliverySkippedIneligibleCas(delivery.getId(), delivery.getVersion()) != 1
                    || mapper.completeOutbox(delivery.getId()) != 1) {
                throw new IllegalStateException("notification ineligible receipt missing");
            }
            return false;
        }
        Long messageId = imService.sendSystem(delivery.getRecipientMemberId(), 1,
                campaign.getTitle() + "\n" + campaign.getContent(), extraJson(campaign));
        Date sentAt = new Date();
        if (messageId == null || messageId <= 0
                || mapper.markDeliverySentCas(delivery.getId(), delivery.getVersion(), messageId, sentAt) != 1
                || mapper.completeOutbox(delivery.getId()) != 1) {
            throw new IllegalStateException("notification success receipt missing");
        }
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean recordFailure(Long campaignId, Long deliveryId, String safeError, Date nextAttemptAt) {
        if (campaignId == null || deliveryId == null || safeError == null || nextAttemptAt == null) {
            throw new IllegalStateException("notification failure receipt scope missing");
        }
        ClubEventDelivery delivery = mapper.selectDeliveryByIdForUpdate(deliveryId);
        if (delivery == null || !campaignId.equals(delivery.getCampaignId())) {
            throw new IllegalStateException("notification failure receipt scope mismatch");
        }
        if (ClubEventDelivery.SENT.equals(delivery.getStatus())) return false;
        if (delivery.getVersion() == null || delivery.getAttemptCount() == null
                || delivery.getAttemptCount() < 0
                || !(ClubEventDelivery.PENDING.equals(delivery.getStatus())
                || ClubEventDelivery.FAILED.equals(delivery.getStatus()))) {
            throw new IllegalStateException("notification failure receipt state mismatch");
        }
        if (delivery.getAttemptCount() >= 5) return false;
        if (mapper.markDeliveryFailedCas(delivery.getId(), delivery.getVersion(), safeError, nextAttemptAt) != 1
                || mapper.rescheduleOutbox(delivery.getId(), nextAttemptAt) != 1) {
            throw new IllegalStateException("notification failure receipt missing");
        }
        return true;
    }

    private List<ClubEventDelivery> claimCurrent(ClubEventCampaign campaign, int limit, Date leaseUntil) {
        if (limit <= 0 || limit > 500 || leaseUntil == null || !leaseUntil.after(new Date())) {
            throw new IllegalStateException("notification claim scope missing");
        }
        List<ClubEventDelivery> rows = mapper.selectDispatchableByCampaign(campaign.getId(), limit);
        if (rows == null || rows.isEmpty()) return Collections.emptyList();
        for (ClubEventDelivery row : rows) {
            if (row == null || row.getId() == null || !campaign.getId().equals(row.getCampaignId())
                    || row.getRecipientMemberId() == null || row.getVersion() == null
                    || row.getAttemptCount() == null || row.getAttemptCount() < 0
                    || !(ClubEventDelivery.PENDING.equals(row.getStatus())
                    || ClubEventDelivery.FAILED.equals(row.getStatus()))
                    || mapper.claimNotificationOutbox(row.getId(), leaseUntil) != 1) {
                throw new IllegalStateException("notification claim receipt missing");
            }
        }
        return rows;
    }

    private ClubEventCampaign requireCurrentCampaign(ClubEventCampaign expected) {
        if (expected == null || expected.getId() == null || expected.getClubId() == null
                || expected.getAudienceType() == null) {
            throw new IllegalStateException("notification campaign scope missing");
        }
        ClubEventCampaign current = mapper.selectCampaignById(expected.getId());
        if (current == null || !expected.getId().equals(current.getId())
                || !expected.getClubId().equals(current.getClubId())
                || !same(expected.getActivityId(), current.getActivityId())
                || !expected.getAudienceType().equals(current.getAudienceType())
                || !same(expected.getChannel(), current.getChannel())) {
            throw new IllegalStateException("notification campaign scope mismatch");
        }
        return current;
    }

    private void requireAuthorized(Long actorMemberId, ClubEventCampaign campaign) {
        if (actorMemberId == null || actorMemberId <= 0 || campaign == null || campaign.getClubId() == null
                || accessService == null || clubMapper == null) {
            throw new IllegalStateException("notification authorization scope missing");
        }
        Club locked = clubMapper.selectClubByIdForUpdate(campaign.getClubId());
        if (locked == null || !campaign.getClubId().equals(locked.getId())
                || locked.getMemberId() == null || !Long.valueOf(1L).equals(locked.getStatus())
                || !Integer.valueOf(0).equals(locked.getDelFlag())) {
            throw forbidden();
        }
        ClubAccessContext access = accessService.require(actorMemberId, campaign.getClubId(),
                campaign.getActivityId(), notificationPermission(campaign.getActivityId()));
        if (access == null || !actorMemberId.equals(access.getPrincipalMemberId())
                || !campaign.getClubId().equals(access.getClubId())
                || !same(campaign.getActivityId(), access.getActivityId())
                || !locked.getMemberId().equals(access.getOwnerMemberId())) {
            throw forbidden();
        }
    }

    private String notificationPermission(Long activityId) {
        return activityId == null ? ClubPermission.NOTIFY_SEND : ClubPermission.EVENT_OPERATE;
    }

    private boolean same(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private ServiceException forbidden() {
        return new ServiceException("俱乐部活动管理权限不足", HttpStatus.FORBIDDEN);
    }

    private String extraJson(ClubEventCampaign campaign) {
        return "{\"type\":\"CLUB_CAMPAIGN\",\"campaignId\":" + campaign.getId()
                + ",\"clubId\":" + campaign.getClubId()
                + (campaign.getActivityId() == null ? "" : ",\"activityId\":" + campaign.getActivityId()) + "}";
    }
}
