package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.service.impl.TopicQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PublicTopicStationCountTest {
    @Test
    void publicCardsUseCurrentVisibleNodesInsteadOfSavedSnapshot() throws Exception {
        TopicQueryService service = new TopicQueryService();
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodes);
        CmsTopic topic = new CmsTopic();
        topic.setId(42L);
        topic.setLocationCount(1L);
        when(topics.selectPublicCmsTopicList(any())).thenReturn(Collections.singletonList(topic));
        CmsTopic currentCount = new CmsTopic();
        currentCount.setId(42L);
        currentCount.setLocationCount(2L);
        currentCount.setTemplateCount(0L);
        when(topics.selectPublicNodeCountsByTopicIds(any())).thenReturn(Collections.singletonList(currentCount));

        assertEquals(Long.valueOf(2L), service.selectPublicCmsTopicList(new CmsTopic()).get(0).getLocationCount());
        verify(topics).selectPublicNodeCountsByTopicIds(Collections.singletonList(42L));
        verifyNoInteractions(nodes);
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

        when(topics.selectPublicNodeCountsByTopicIds(any())).thenReturn(Collections.emptyList());
        assertEquals(Long.valueOf(0L), service.selectPublicCmsTopicList(new CmsTopic()).get(0).getLocationCount());
    }
}
