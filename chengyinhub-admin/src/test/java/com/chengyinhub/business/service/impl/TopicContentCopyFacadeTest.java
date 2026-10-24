package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.ITopicContentCopyService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TopicContentCopyFacadeTest {
    @Test void genericFacadePreservesCommandAndResultIdentity() {
        CmsTopicServiceImpl facade = new CmsTopicServiceImpl();
        ITopicContentCopyService owner = mock(ITopicContentCopyService.class);
        ReflectionTestUtils.setField(facade, "topicContentCopyService", owner);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("copyType", 3); params.put("memberId", 2000L);
        Long id = 9001L;
        when(owner.copyCmsTopic(100L, params)).thenReturn(id);
        assertSame(id, facade.copyCmsTopic(100L, params));
        verify(owner).copyCmsTopic(eq(100L), same(params));
        verifyNoMoreInteractions(owner);
    }

    @Test void officialFacadeLeavesAuthorizationAndFailureWithApplicationOwner() {
        CmsTopicServiceImpl facade = new CmsTopicServiceImpl();
        ITopicContentCopyService owner = mock(ITopicContentCopyService.class);
        ReflectionTestUtils.setField(facade, "topicContentCopyService", owner);
        Map<String, Object> params = Map.of("memberId", 2000L);
        IllegalArgumentException failure = new IllegalArgumentException("official owner rejection");
        when(owner.copyOfficialCmsTopic(100L, params)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> facade.copyOfficialCmsTopic(100L, params)));
        verify(owner).copyOfficialCmsTopic(eq(100L), same(params));
        verifyNoMoreInteractions(owner);
    }
}
