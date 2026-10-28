package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthorAttributeRegistryReaderTest {
    @Test void ownerIsCheckedUnderTopicLockBeforeRegistryAccess() {
        CmsTopicMapper topics=mock(CmsTopicMapper.class);ThemeStateKeyService registry=mock(ThemeStateKeyService.class);
        CmsTopic topic=new CmsTopic();topic.setMemberId(11L);topic.setConfigVersion(9007199254740993L);
        when(topics.selectCmsTopicByIdForUpdate(7L)).thenReturn(topic);
        AuthorAttributeRegistryReader reader=new AuthorAttributeRegistryReader(topics,registry);
        assertEquals(403,assertThrows(ServiceException.class,()->reader.read(7L,12L)).getCode());
        verifyNoInteractions(registry);
        when(registry.readBinding(7L,java.util.Collections.emptyList()))
                .thenReturn(new ObjectMapper().createObjectNode().put("registryVersion","9007199254740995"));
        when(registry.listByTopic(7L)).thenReturn(java.util.Collections.emptyList());
        var result=reader.read(7L,11L);
        assertEquals("9007199254740993",result.path("configVersion").textValue());
        assertEquals("9007199254740995",result.path("registryVersion").textValue());
        assertTrue(result.path("definitions").isArray());
        var order=inOrder(topics,registry);
        order.verify(topics,times(2)).selectCmsTopicByIdForUpdate(7L);
        order.verify(registry).readBinding(7L,java.util.Collections.emptyList());order.verify(registry).listByTopic(7L);
    }
}
