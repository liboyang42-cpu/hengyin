package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.RoamExploreDayCandidate;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.vo.RoamExploreDayCandidateVO;
import com.chengyinhub.business.mapper.RoamExploreDayCandidateMapper;
import com.chengyinhub.business.service.IRoamExploreDayCandidateService;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.service.support.CommercialReviewStatusSource;
import com.chengyinhub.business.service.support.MerchantPublishedTopicTransferGate;
import com.chengyinhub.business.service.support.SaleAvailabilityPolicy;
import com.chengyinhub.common.constant.ProductType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.util.Date;
import java.util.List;

/** 首次发现商家后的自由探索活动壳白名单；任一事实未知均拒绝。 */
@Service
public class RoamExploreDayCandidateServiceImpl implements IRoamExploreDayCandidateService {
    static final int MAX_DISTANCE_M = 1500;
    private final RoamExploreDayCandidateMapper mapper;
    private final MerchantPublishedTopicTransferGate transferGate;
    private final CommercialReviewStatusSource reviewStatusSource;
    private final Long platformMemberId;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RoamExploreDayCandidateServiceImpl(RoamExploreDayCandidateMapper mapper,
                                              MerchantPublishedTopicTransferGate transferGate,
                                              CommercialReviewStatusSource reviewStatusSource,
                                              @Value("${chengyin.platform.member-id:100000}") Long platformMemberId) {
        this.mapper = mapper;
        this.transferGate = transferGate;
        this.reviewStatusSource = reviewStatusSource;
        this.platformMemberId = platformMemberId;
    }

    @Override
    public RoamExploreDayCandidateVO findNearestEligible(double lat, double lng) {
        if (!validCoordinate(lat, lng)) return null;
        double dLat = MAX_DISTANCE_M / 111320.0;
        double dLng = MAX_DISTANCE_M / (111320.0 * Math.max(0.2, Math.cos(Math.toRadians(lat))));
        List<RoamExploreDayCandidate> rows = mapper.selectPotentialCandidates(
                lat - dLat, lat + dLat, lng - dLng, lng + dLng);
        if (rows == null) return null;
        RoamExploreDayCandidate nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        Date now = new Date();
        for (RoamExploreDayCandidate row : rows) {
            double distance = eligibilityDistance(row, lat, lng, now);
            if (distance <= MAX_DISTANCE_M && distance < nearestDistance) {
                nearest = row;
                nearestDistance = distance;
            }
        }
        return nearest == null ? null : toVo(nearest, nearestDistance);
    }

    private double eligibilityDistance(RoamExploreDayCandidate row, double lat, double lng, Date now) {
        if (row == null || row.getActivityId() == null || row.getActivityId() <= 0
                || row.getTopicId() == null || row.getTopicId() <= 0
                || row.getTicketId() == null || row.getTicketId() <= 0) return Double.MAX_VALUE;
        if (!Integer.valueOf(ProductType.FREE_EXPLORE).equals(row.getProductType())) return Double.MAX_VALUE;
        boolean platformBuilt = platformMemberId != null && platformMemberId.equals(row.getTopicMemberId())
                && row.getMerchantMemberId() == null;
        boolean activeMerchantBuilt = row.getTopicMemberId() != null
                && row.getTopicMemberId().equals(row.getMerchantMemberId())
                && Long.valueOf(1L).equals(row.getMerchantStatus())
                && Integer.valueOf(0).equals(row.getMerchantDelFlag());
        if (!platformBuilt && !activeMerchantBuilt) return Double.MAX_VALUE;
        if (!Integer.valueOf(TopicLifecycleState.SELLING.getCode()).equals(row.getTopicLifecycle())
                || !Integer.valueOf(0).equals(row.getTopicDelFlag())) {
            return Double.MAX_VALUE;
        }
        if (!AuditTask.STATUS_APPROVED.equals(row.getProjectReviewStatus())
                || !AuditTask.STATUS_APPROVED.equals(row.getSaleReviewStatus())) return Double.MAX_VALUE;
        if (!Integer.valueOf(1).equals(row.getRoamReferralEnabled())) return Double.MAX_VALUE;
        if (!Long.valueOf(1L).equals(row.getActivityStatus())
                || !Long.valueOf(1L).equals(row.getActivityPublishStatus())
                || row.getActivityCancelTime() != null || !Integer.valueOf(0).equals(row.getActivityDelFlag())) {
            return Double.MAX_VALUE;
        }
        if (!Long.valueOf(2L).equals(row.getTicketOwnerType())
                || !row.getActivityId().equals(row.getTicketOwnerId())
                || !Integer.valueOf(0).equals(row.getTicketDelFlag())
                || row.getRemainingInventory() == null || row.getRemainingInventory() <= 0) return Double.MAX_VALUE;
        if (row.getGatherLat() == null || row.getGatherLng() == null) return Double.MAX_VALUE;

        CmsTopic topic = topic(row);
        OmsTicket ticket = ticket(row);
        try {
            reviewStatusSource.assertProjectApprovedReadOnly(row.getTopicId(), projectTarget(row));
            reviewStatusSource.assertSaleApproved(row.getTopicId());
            SaleAvailabilityPolicy.assertSellable(topic, ticket, now);
            transferGate.assertTransferred(topic);
        } catch (RuntimeException rejected) {
            return Double.MAX_VALUE;
        }
        double distance = haversineM(lat, lng, row.getGatherLat().doubleValue(), row.getGatherLng().doubleValue());
        return Double.isFinite(distance) ? distance : Double.MAX_VALUE;
    }

    private TopicLifecycleState projectTarget(RoamExploreDayCandidate row) {
        try {
            JsonNode root = objectMapper.readTree(row.getProjectReviewSnapshotJson());
            JsonNode target = root == null ? null : root.get("requestedTarget");
            if (target == null || target.asText().isEmpty()) throw new IllegalArgumentException("缺少立项目标");
            TopicLifecycleState state = TopicLifecycleState.valueOf(target.asText());
            if (state != TopicLifecycleState.RECRUITING && state != TopicLifecycleState.PRICING) {
                throw new IllegalArgumentException("非法立项目标");
            }
            return state;
        } catch (Exception invalid) {
            throw new IllegalStateException("立项审核快照不可用于漫游导流", invalid);
        }
    }

    private static CmsTopic topic(RoamExploreDayCandidate row) {
        CmsTopic topic = new CmsTopic();
        topic.setId(row.getTopicId());
        topic.setMemberId(row.getTopicMemberId());
        topic.setProductType(row.getProductType());
        topic.setLifecycle(row.getTopicLifecycle());
        topic.setDelFlag(row.getTopicDelFlag());
        topic.setSellingStartedAt(row.getSellingStartedAt());
        topic.setSaleEndAt(row.getSaleEndAt());
        return topic;
    }

    private static OmsTicket ticket(RoamExploreDayCandidate row) {
        OmsTicket ticket = new OmsTicket();
        ticket.setId(row.getTicketId());
        ticket.setOwnerType(row.getTicketOwnerType());
        ticket.setOwnerId(row.getTicketOwnerId());
        ticket.setSignupDeadline(row.getTicketSignupDeadline());
        ticket.setRemainingInventory(row.getRemainingInventory());
        ticket.setDelFlag(row.getTicketDelFlag());
        return ticket;
    }

    private static RoamExploreDayCandidateVO toVo(RoamExploreDayCandidate row, double distance) {
        RoamExploreDayCandidateVO vo = new RoamExploreDayCandidateVO();
        vo.setActivityId(row.getActivityId());
        vo.setTitle(row.getTitle());
        vo.setSubtitle(row.getSubtitle());
        vo.setCoverImg(row.getCoverImg());
        vo.setMeetingPoint(row.getMeetingPoint());
        vo.setDistanceM((int) Math.round(distance));
        return vo;
    }

    private static boolean validCoordinate(double lat, double lng) {
        return Double.isFinite(lat) && Double.isFinite(lng) && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180;
    }

    private static double haversineM(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * 6371000d * Math.asin(Math.sqrt(a));
    }
}
