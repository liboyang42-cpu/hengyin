package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.domain.dto.CommunityPostRequests;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.service.IContentSafetyGate;
import com.chengyinhub.business.service.impl.CommunityPostServiceImpl;
import com.chengyinhub.business.service.support.CommunityPostLifecyclePolicy;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN: service admission regression, not a database/quit endpoint verification. */
class E02_14Test {
    @Test
    void publishingExistingCommunityDraftRechecksMembershipBeforeSafetyOrWrites() {
        CommunityPostMapper mapper = mock(CommunityPostMapper.class);
        IContentSafetyGate safety = mock(IContentSafetyGate.class);
        CommunityPostServiceImpl service = new CommunityPostServiceImpl(mapper,
                new CommunityPostLifecyclePolicy(), safety, null, null, null);
        CommunityPost draft = new CommunityPost();
        draft.setId(7L);
        draft.setAuthorId(11L);
        draft.setCommunityId(55L);
        draft.setAudience("COMMUNITY");
        draft.setLifecycle("DRAFT");
        draft.setVersion(3);
        draft.setBody("synthetic retained draft");
        when(mapper.selectByIdForUpdate(7L)).thenReturn(draft);
        when(mapper.selectCommandReceipt(11L, "synthetic-publish-after-quit")).thenReturn(null);
        when(mapper.countCommunityMember(11L, 55L)).thenReturn(0);
        CommunityPostRequests.State request = new CommunityPostRequests.State();
        request.setRequestId("synthetic-publish-after-quit");
        request.setExpectedVersion(3);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.publish(11L, "synthetic-openid", 7L, request));

        assertEquals(Integer.valueOf(403), error.getCode());
        verify(mapper).countCommunityMember(11L, 55L);
        verifyNoInteractions(safety);
        verify(mapper, never()).countGuidelineAck(anyLong(), anyLong());
        verify(mapper, never()).transitionCas(anyLong(), anyString(), anyString(), anyInt(),
                any(), any(), any(), any(), any(), any());
        verify(mapper, never()).insertCommandReceipt(anyLong(), anyString(), anyString(), anyLong(), anyString(), any());
        assertEquals("DRAFT", draft.getLifecycle());
        assertEquals("synthetic retained draft", draft.getBody());
    }
}
