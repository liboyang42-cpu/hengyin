package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.domain.dto.TopicOperationalListSummaryDTO;
import com.chengyinhub.business.service.impl.TopicListProjectionServiceImpl;
import com.chengyinhub.common.constant.ProductSlot;
import com.chengyinhub.common.constant.RoleEntityType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 列表投影的公开 seam：Controller 不拼装跨服务运营事实。 */
class TopicListProjectionServiceImplTest {

    @Test
    void project_enrichesPublisherMerchantAndOperationalFactsInBatches() {
        ICmsRegistrationMerchantService merchantService = mock(ICmsRegistrationMerchantService.class);
        IProductRoleService roleService = mock(IProductRoleService.class);
        ITopicOperationalSummaryService summaryService = mock(ITopicOperationalSummaryService.class);
        ITopicOperationsScopeAuthorizer scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        ITopicListProjectionService service = new TopicListProjectionServiceImpl(
                merchantService, roleService, summaryService, scopeAuthorizer);

        CmsTopic official = topic(1L);
        CmsTopic historical = topic(2L);
        CmsTopic abnormal = topic(3L);
        List<CmsTopic> topics = Arrays.asList(official, historical, abnormal);
        when(scopeAuthorizer.canViewTopic(1L)).thenReturn(true);
        when(scopeAuthorizer.canViewTopic(2L)).thenReturn(true);
        when(scopeAuthorizer.canViewTopic(3L)).thenReturn(true);

        Map<Long, Integer> merchantCounts = new HashMap<Long, Integer>();
        merchantCounts.put(1L, 2);
        when(merchantService.countByTopics(Arrays.asList(1L, 2L, 3L))).thenReturn(merchantCounts);

        Map<Long, ProductRole> contentSlots = new HashMap<Long, ProductRole>();
        contentSlots.put(1L, role(1L, RoleEntityType.PLATFORM));
        contentSlots.put(3L, role(3L, RoleEntityType.MERCHANT));
        when(roleService.activeContentSlots(Arrays.asList(1L, 2L, 3L))).thenReturn(contentSlots);

        TopicOperationalListSummaryDTO officialSummary = new TopicOperationalListSummaryDTO();
        officialSummary.setTopicId(1L);
        officialSummary.setContentPublisherName("平台运营");
        Map<Long, TopicOperationalListSummaryDTO> summaries = new HashMap<Long, TopicOperationalListSummaryDTO>();
        summaries.put(1L, officialSummary);
        when(summaryService.summarize(anyList())).thenReturn(summaries);

        List<CmsTopic> projected = service.project(topics);

        assertSame(topics, projected, "全量有权时必须保留 PageHelper 原始分页容器");
        assertEquals(2, official.getMerchantCount());
        assertEquals(RoleEntityType.PLATFORM, official.getContentPublisherType());
        assertEquals("平台运营", official.getContentPublisherName());
        assertEquals(null, historical.getContentPublisherType(), "无槽不能猜成官方或广场");
        assertEquals(RoleEntityType.MERCHANT, abnormal.getContentPublisherType(),
                "异常槽必须交给前端显示“主体异常”，不能伪装成历史待归属");
    }

    @Test
    void projectFailsClosedBeforeReadingOperationalSourcesWhenTopicScopeIsUnavailable() {
        ICmsRegistrationMerchantService merchantService = mock(ICmsRegistrationMerchantService.class);
        IProductRoleService roleService = mock(IProductRoleService.class);
        ITopicOperationalSummaryService summaryService = mock(ITopicOperationalSummaryService.class);
        ITopicOperationsScopeAuthorizer scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        ITopicListProjectionService service = new TopicListProjectionServiceImpl(
                merchantService, roleService, summaryService, scopeAuthorizer);
        when(scopeAuthorizer.canViewTopic(1L)).thenReturn(false);

        List<CmsTopic> projected = service.project(Collections.singletonList(topic(1L)));

        assertTrue(projected.isEmpty());
        verifyNoInteractions(merchantService, roleService, summaryService);
    }

    private static CmsTopic topic(Long id) {
        CmsTopic topic = new CmsTopic();
        topic.setId(id);
        return topic;
    }

    private static ProductRole role(Long topicId, String entityType) {
        ProductRole role = new ProductRole();
        role.setTopicId(topicId);
        role.setSlot(ProductSlot.CONTENT);
        role.setEntityType(entityType);
        role.setStatus(1);
        return role;
    }
}
