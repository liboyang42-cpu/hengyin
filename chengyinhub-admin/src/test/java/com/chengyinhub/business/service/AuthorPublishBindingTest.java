package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.business.service.support.TopicSupplyReviewService;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthorPublishBindingTest {
    @Test void actualPublishServiceRejectsOldClientAndStaleConfigBeforeRegistryOrPublication() {
        CmsTopic topic = new CmsTopic();topic.setId(7L);topic.setMemberId(11L);topic.setConfigVersion(3L);
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"attributeBinding\":{\"schemaVersion\":1,\"registryVersion\":\"2\",\"enabledKeys\":[]},\"attributes\":[]}");
        TopicSupplyReviewService supply = mock(TopicSupplyReviewService.class);
        ThemeStateKeyService registry = mock(ThemeStateKeyService.class);
        when(supply.lockTopic(7L)).thenReturn(topic);
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        ReflectionTestUtils.setField(service,"supplyReviewService",supply);
        ReflectionTestUtils.setField(service,"themeStateKeyService",registry);
        assertEquals(409,assertThrows(ServiceException.class,()->service.publishDraft(7L,11L)).getCode());
        assertEquals(409,assertThrows(ServiceException.class,()->service.publishDraftWithBinding(7L,11L,2L,"2")).getCode());
        assertEquals(409,assertThrows(ServiceException.class,()->service.publishDraftWithBinding(7L,11L,3L,"3")).getCode());
        assertEquals(403,assertThrows(ServiceException.class,()->service.publishDraftWithBinding(7L,12L,3L,"2")).getCode());
        verifyNoInteractions(registry);verify(supply,times(4)).lockTopic(7L);verifyNoMoreInteractions(supply);
    }

    @Test void publishFreezesDefinitionWithThePublishedVersionRunsCheck() {
        CmsTopic topic = new CmsTopic();topic.setId(7L);topic.setMemberId(11L);topic.setConfigVersion(3L);topic.setPublishedConfigVersion(4L);
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"attributeBinding\":{\"schemaVersion\":1,\"registryVersion\":\"2\",\"enabledKeys\":[]},\"attributes\":[]}");
        TopicSupplyReviewService supply = mock(TopicSupplyReviewService.class);
        ThemeStateKeyService registry = mock(ThemeStateKeyService.class);
        when(supply.lockTopic(7L)).thenReturn(topic);
        ServiceException stop = new ServiceException("stop", 499);
        when(registry.freezeDefinition(anyLong(), anyLong(), anyString(), any())).thenThrow(stop);
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        ReflectionTestUtils.setField(service,"supplyReviewService",supply);
        ReflectionTestUtils.setField(service,"themeStateKeyService",registry);
        assertSame(stop,assertThrows(ServiceException.class,()->service.publishDraftWithBinding(7L,11L,3L,"2")));
        // 绑定仍按编辑版本 3 校验；冻结定义标发布后的 published_config_version 5（建局按它核对）。
        verify(registry).freezeDefinition(eq(7L),eq(5L),eq("2"),any());
    }
}
