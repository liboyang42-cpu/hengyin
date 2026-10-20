package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PublicTopicStationCountTest {
    @Test
    void publicCardsUseCurrentVisibleNodesInsteadOfSavedSnapshot() {
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodes);
        CmsTopic topic = new CmsTopic();
        topic.setId(42L);
        topic.setLocationCount(1L);
        topic.setTemplateCount(99L);
        when(topics.selectPublicCmsTopicList(any())).thenReturn(Collections.singletonList(topic));
        CmsTopic currentCounts = new CmsTopic();
        currentCounts.setId(42L);
        currentCounts.setLocationCount(2L);
        currentCounts.setTemplateCount(1L);
        when(topics.selectPublicNodeCountsByTopicIds(Collections.singletonList(42L)))
                .thenReturn(Collections.singletonList(currentCounts));

        assertEquals(Long.valueOf(2L), service.selectPublicCmsTopicList(new CmsTopic()).get(0).getLocationCount());
        assertEquals(Long.valueOf(1L), topic.getTemplateCount());
        // The current service sends only the exact topic IDs to the visibility-filtered batch query.
        verify(topics).selectPublicNodeCountsByTopicIds(Collections.singletonList(42L));
        verifyNoInteractions(nodes); // Do not regress to one node query per public card.

        when(topics.selectPublicNodeCountsByTopicIds(Collections.singletonList(42L)))
                .thenReturn(Collections.emptyList());
        assertEquals(Long.valueOf(0L), service.selectPublicCmsTopicList(new CmsTopic()).get(0).getLocationCount());
        assertEquals(Long.valueOf(0L), topic.getTemplateCount());
        verify(topics, times(2)).selectPublicNodeCountsByTopicIds(Collections.singletonList(42L));
        verifyNoInteractions(nodes);
    }

    @Test
    void publicCountsSqlRetainsVisibilityAndWithdrawnOfferFilters() throws Exception {
        String mapper = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "../chengyinhub-system/src/main/resources/mapper/business/CmsTopicMapper.xml")),
                java.nio.charset.StandardCharsets.UTF_8);
        String batchSql = mapper.substring(mapper.indexOf("<select id=\"selectPublicNodeCountsByTopicIds\""));
        batchSql = batchSql.substring(0, batchSql.indexOf("</select>"));
        org.junit.jupiter.api.Assertions.assertTrue(batchSql.contains("status = 1")
                && batchSql.contains("merchant_member_id is null or node_audit_status = 1")
                && batchSql.contains("collection=\"topicIds\"")
                && batchSql.contains("withdrawn.withdraw_reason")
                && !batchSql.contains("#{merchantMemberId}"),
                "批量计数仍须取公开可见节点，不受商家本人身份限制，并排除已撤回供给");
    }
}
