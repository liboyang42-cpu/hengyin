package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.domain.dto.TopicOperationalListSummaryDTO;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.ITopicListProjectionService;
import com.chengyinhub.business.service.ITopicOperationalSummaryService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 主题列表的批量只读投影，集中列表页需要的跨服务事实。 */
@Service
public class TopicListProjectionServiceImpl implements ITopicListProjectionService {

    private final ICmsRegistrationMerchantService merchantService;
    private final IProductRoleService productRoleService;
    private final ITopicOperationalSummaryService operationalSummaryService;
    private final ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer;

    public TopicListProjectionServiceImpl(ICmsRegistrationMerchantService merchantService,
                                          IProductRoleService productRoleService,
                                          ITopicOperationalSummaryService operationalSummaryService,
                                          ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer) {
        this.merchantService = merchantService;
        this.productRoleService = productRoleService;
        this.operationalSummaryService = operationalSummaryService;
        this.topicOperationsScopeAuthorizer = topicOperationsScopeAuthorizer;
    }

    @Override
    public List<CmsTopic> project(List<CmsTopic> topics) {
        if (topics == null || topics.isEmpty()) {
            return topics == null ? Collections.<CmsTopic>emptyList() : topics;
        }

        List<CmsTopic> scopedTopics = new ArrayList<CmsTopic>();
        for (CmsTopic topic : topics) {
            if (topic != null && topic.getId() != null
                    && topicOperationsScopeAuthorizer.canViewTopic(topic.getId())) {
                scopedTopics.add(topic);
            }
        }
        if (scopedTopics.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> topicIds = new ArrayList<Long>();
        for (CmsTopic topic : scopedTopics) {
            topicIds.add(topic.getId());
        }

        Map<Long, Integer> merchantCounts = merchantService.countByTopics(topicIds);
        Map<Long, ProductRole> contentSlots = productRoleService.activeContentSlots(topicIds);
        Map<Long, TopicOperationalListSummaryDTO> summaries = operationalSummaryService.summarize(scopedTopics);
        merchantCounts = merchantCounts == null ? Collections.<Long, Integer>emptyMap() : merchantCounts;
        contentSlots = contentSlots == null ? Collections.<Long, ProductRole>emptyMap() : contentSlots;
        summaries = summaries == null ? Collections.<Long, TopicOperationalListSummaryDTO>emptyMap() : summaries;

        for (CmsTopic topic : scopedTopics) {
            topic.setMerchantCount(merchantCounts.getOrDefault(topic.getId(), 0));
            ProductRole contentSlot = contentSlots.get(topic.getId());
            TopicOperationalListSummaryDTO summary = summaries.get(topic.getId());
            if (contentSlot != null) {
                topic.setContentPublisherType(contentSlot.getEntityType());
                if (summary != null) {
                    topic.setContentPublisherName(summary.getContentPublisherName());
                    topic.setContentPublisherLeaderName(summary.getContentPublisherLeaderName());
                }
            }
            topic.setOperationSummary(summary);
        }

        // PageHelper 将总数挂在原始 List 容器上。当前授权模型只有全局超管或拒绝两种结果，
        // 因而全量有权时必须保留该容器，不能把正确 rows 换成丢 total 的 ArrayList。
        return scopedTopics.size() == topics.size() ? topics : scopedTopics;
    }
}
