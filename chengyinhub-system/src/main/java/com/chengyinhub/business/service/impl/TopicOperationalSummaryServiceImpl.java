package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.TopicOperationalListSummaryDTO;
import com.chengyinhub.business.domain.dto.TopicWorkspaceDTO;
import com.chengyinhub.business.mapper.TopicOperationalSummaryMapper;
import com.chengyinhub.business.service.ITopicOperationalSummaryService;
import com.chengyinhub.business.service.support.TopicRunReadinessPolicy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 主题列表的批量摘要；只读且不吞掉投影读取失败。 */
@Service
public class TopicOperationalSummaryServiceImpl implements ITopicOperationalSummaryService {

    private final TopicOperationalSummaryMapper summaryMapper;

    public TopicOperationalSummaryServiceImpl(TopicOperationalSummaryMapper summaryMapper) {
        this.summaryMapper = summaryMapper;
    }

    @Override
    public Map<Long, TopicOperationalListSummaryDTO> summarize(List<CmsTopic> topics) {
        if (topics == null || topics.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> topicIds = new LinkedHashSet<Long>();
        Map<Long, CmsTopic> topicsById = new LinkedHashMap<Long, CmsTopic>();
        for (CmsTopic topic : topics) {
            if (topic != null && topic.getId() != null) {
                topicIds.add(topic.getId());
                topicsById.put(topic.getId(), topic);
            }
        }
        if (topicIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Long, TopicOperationalListSummaryDTO> projections = new LinkedHashMap<Long, TopicOperationalListSummaryDTO>();
        List<TopicOperationalListSummaryDTO> rows = summaryMapper.selectByTopicIds(new ArrayList<Long>(topicIds));
        if (rows != null) {
            for (TopicOperationalListSummaryDTO row : rows) {
                if (row != null && row.getTopicId() != null) {
                    projections.put(row.getTopicId(), row);
                }
            }
        }

        Map<Long, TopicOperationalListSummaryDTO> result = new LinkedHashMap<Long, TopicOperationalListSummaryDTO>();
        for (Map.Entry<Long, CmsTopic> entry : topicsById.entrySet()) {
            TopicOperationalListSummaryDTO summary = projections.get(entry.getKey());
            if (summary == null) {
                continue;
            }
            applyDerivedStates(entry.getValue(), summary);
            result.put(entry.getKey(), summary);
        }
        return result;
    }

    private static void applyDerivedStates(CmsTopic topic, TopicOperationalListSummaryDTO summary) {
        summary.setStandardTicketCount(summary.getTicketCount());
        boolean selfPlayPassConfigured = Integer.valueOf(1).equals(topic.getSelfPlay())
                && topic.getSelfPlayPrice() != null
                && topic.getSelfPlayPrice().compareTo(java.math.BigDecimal.ZERO) > 0;
        summary.setSelfPlayPassConfigured(selfPlayPassConfigured);
        summary.setSelfPlayPassConfigurationInvalid(Integer.valueOf(1).equals(topic.getSelfPlay())
                && !selfPlayPassConfigured);
        summary.setSelfPlayPassPrice(selfPlayPassConfigured ? topic.getSelfPlayPrice() : null);
        summary.setSelfPlayPassQuota(selfPlayPassConfigured ? topic.getSelfPlayQuota() : null);
        if (selfPlayPassConfigured) {
            summary.setTicketCount(summary.getStandardTicketCount() + 1);
            if (summary.getMinTicketPrice() == null
                    || topic.getSelfPlayPrice().compareTo(summary.getMinTicketPrice()) < 0) {
                summary.setMinTicketPrice(topic.getSelfPlayPrice());
            }
            Integer quota = topic.getSelfPlayQuota();
            summary.setSelfPlayPassRemainingInventory(quota != null && quota > 0
                    ? Math.max(0, quota - summary.getSelfPlayPassOccupiedCount()) : null);
        } else {
            summary.setSelfPlayPassRemainingInventory(null);
        }

        summary.setClubFulfillmentState(deriveClubFulfillmentState(summary));
        summary.setMerchantSupplyState(deriveMerchantSupplyState(topic, summary));

        boolean contentStructureMissing = summary.getChapterCount() == 0
                || summary.getChapterWithoutNodeCount() > 0;
        boolean ticketConfigurationMissing = summary.getTicketCount() == 0;
        boolean requiredSupplyMissing = summary.getMissingRequiredChapterCount() > 0;
        boolean clubInvitePending = summary.getPendingClubInviteCount() > 0;
        boolean clubFulfillmentUnconfirmed = summary.getGuidedTicketCount() > 0
                && summary.getSignedClubCooperationCount() == 0;
        List<TopicWorkspaceDTO.RunBlocker> blockers = TopicRunReadinessPolicy.derive(topic,
                contentStructureMissing, ticketConfigurationMissing,
                summary.isSelfPlayPassConfigurationInvalid(), requiredSupplyMissing,
                clubInvitePending, clubFulfillmentUnconfirmed);
        summary.setRunBlockers(blockers);
        summary.setRunReadiness(TopicRunReadinessPolicy.classify(blockers));
    }

    private static TopicOperationalListSummaryDTO.ClubFulfillmentState deriveClubFulfillmentState(
            TopicOperationalListSummaryDTO summary) {
        if (summary.getActiveLeadSessionCount() > 0) {
            return TopicOperationalListSummaryDTO.ClubFulfillmentState.LEADING;
        }
        if (summary.getSignedClubCooperationCount() > 0) {
            return TopicOperationalListSummaryDTO.ClubFulfillmentState.SIGNED;
        }
        if (summary.getPendingClubInviteCount() > 0) {
            return TopicOperationalListSummaryDTO.ClubFulfillmentState.INVITED;
        }
        if (summary.getPendingClubApplicationCount() > 0) {
            return TopicOperationalListSummaryDTO.ClubFulfillmentState.APPLYING;
        }
        if (summary.getGuidedTicketCount() == 0) {
            return TopicOperationalListSummaryDTO.ClubFulfillmentState.NOT_REQUIRED;
        }
        return TopicOperationalListSummaryDTO.ClubFulfillmentState.UNCONFIRMED;
    }

    private static TopicOperationalListSummaryDTO.MerchantSupplyState deriveMerchantSupplyState(
            CmsTopic topic, TopicOperationalListSummaryDTO summary) {
        if (summary.getRequiredChapterCount() == 0) {
            return TopicOperationalListSummaryDTO.MerchantSupplyState.NOT_REQUIRED;
        }
        if (summary.getMissingRequiredChapterCount() == 0) {
            return TopicOperationalListSummaryDTO.MerchantSupplyState.READY;
        }
        if (summary.getSuppliedRequiredChapterCount() > 0 || summary.getActiveOfferCount() > 0) {
            return TopicOperationalListSummaryDTO.MerchantSupplyState.PARTIALLY_READY;
        }
        if (summary.getPendingCandidateCount() > 0 || summary.getApprovedCandidateCount() > 0) {
            return TopicOperationalListSummaryDTO.MerchantSupplyState.PENDING_ALLOCATION;
        }
        if (Integer.valueOf(1).equals(topic.getMerchantStatus())) {
            return TopicOperationalListSummaryDTO.MerchantSupplyState.RECRUITING;
        }
        return TopicOperationalListSummaryDTO.MerchantSupplyState.GAP;
    }
}
