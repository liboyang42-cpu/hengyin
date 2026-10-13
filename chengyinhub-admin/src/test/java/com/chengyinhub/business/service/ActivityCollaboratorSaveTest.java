package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.CmsActivityPublishRequest;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.CmsActivityServiceImpl;
import com.chengyinhub.business.service.support.SettlementDebtLedger;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityCollaboratorSaveTest {
    private CmsActivityServiceImpl service;
    private CmsActivityMapper activities;
    private CmsCollaboratorsMapper collaborators;
    private IUmsMemberService members;

    @BeforeEach
    void setup() {
        service = new CmsActivityServiceImpl();
        activities = mock(CmsActivityMapper.class);
        collaborators = mock(CmsCollaboratorsMapper.class);
        members = mock(IUmsMemberService.class);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(service, "cmsCollaboratorsMapper", collaborators);
        ReflectionTestUtils.setField(service, "umsMemberService", members);
        ReflectionTestUtils.setField(service, "settlementDebtLedger", mock(SettlementDebtLedger.class));
        ReflectionTestUtils.setField(service, "auditTaskService", mock(IAuditTaskService.class));
        when(activities.insertCmsActivity(any())).thenAnswer(call -> {
            call.getArgument(0, CmsActivity.class).setId(9L); return 1;
        });
        when(collaborators.insertCmsCollaborators(any())).thenReturn(1);
        when(members.selectUmsMemberById(21L)).thenReturn(new UmsMember());
        when(members.selectUmsMemberById(22L)).thenReturn(new UmsMember());
    }

    @Test
    void publishSavesSelectedMembersOnceInsteadOfCreator() {
        CmsActivityPublishRequest request = request(Arrays.asList(21L, 22L, 21L));
        assertEquals(Long.valueOf(9L), service.publishActivity(request, 88L, "creator"));
        ArgumentCaptor<CmsCollaborators> saved = ArgumentCaptor.forClass(CmsCollaborators.class);
        verify(collaborators, times(2)).insertCmsCollaborators(saved.capture());
        assertEquals(Arrays.asList(21L, 22L), saved.getAllValues().stream()
                .map(CmsCollaborators::getMemberId).collect(Collectors.toList()));
        assertTrue(saved.getAllValues().stream().allMatch(row -> row.getDataType() == 2L && row.getDataId() == 9L));
    }

    @Test
    void editReplacesAndEmptyListRemovesButAbsentListPreserves() {
        when(activities.updateCmsActivityByOwner(any())).thenReturn(1);
        service.updateActivityByOwner(request(Arrays.asList(22L)), 88L, "creator");
        verify(collaborators).deleteActivityCollaboratorsByOwner(9L, 88L);
        verify(collaborators).insertCmsCollaborators(argThat(row -> Long.valueOf(22L).equals(row.getMemberId())));
        clearInvocations(collaborators);
        service.updateActivityByOwner(request(Collections.emptyList()), 88L, "creator");
        verify(collaborators).deleteActivityCollaboratorsByOwner(9L, 88L);
        verify(collaborators, never()).insertCmsCollaborators(any());
        clearInvocations(collaborators);
        service.updateActivityByOwner(request(null), 88L, "creator");
        verifyNoInteractions(collaborators);
    }

    @Test
    void rejectedOwnerCannotChangeCollaborators() {
        when(activities.updateCmsActivityByOwner(any())).thenReturn(0);
        assertThrows(ServiceException.class,
                () -> service.updateActivityByOwner(request(Arrays.asList(21L)), 99L, "other"));
        verifyNoInteractions(collaborators);
    }

    @Test
    void invalidMemberAndFailedInsertDoNotReportSuccess() {
        assertThrows(ServiceException.class, () -> service.publishActivity(request(Arrays.asList(0L)), 88L, "creator"));
        assertThrows(ServiceException.class, () -> service.publishActivity(request(Arrays.asList(404L)), 88L, "creator"));
        when(collaborators.insertCmsCollaborators(any())).thenReturn(0);
        assertThrows(ServiceException.class, () -> service.publishActivity(request(Arrays.asList(21L)), 88L, "creator"));
    }

    private CmsActivityPublishRequest request(List<Long> ids) {
        CmsActivityPublishRequest request = new CmsActivityPublishRequest();
        request.setId(9L);
        request.setName("署名测试");
        request.setCollaborators(ids);
        return request;
    }
}
