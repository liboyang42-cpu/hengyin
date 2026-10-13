package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.ICommunityPostService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiCommunityPostFeatureGateTest {
    @Test
    void closedReadAndWriteFlagsStopBeforeCommunityService() {
        ICommunityPostService service = mock(ICommunityPostService.class);
        CommunityPostFeatureGate gate = mock(CommunityPostFeatureGate.class);
        ApiCommunityPostController controller = spy(new ApiCommunityPostController(service, gate));
        doReturn(8L).when(controller).getAppUserId();
        when(gate.canRead(8L)).thenReturn(false);
        when(gate.canWrite(8L)).thenReturn(false);

        AjaxResult read = controller.guideline();
        AjaxResult write = controller.create(null);

        assertEquals(404, read.get("code"));
        assertEquals(404, write.get("code"));
        verify(service, never()).activeGuideline();
        verify(service, never()).createDraft(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void closedWriteFlagDoesNotBlockSafetyAndDataRightsActions() {
        ICommunityPostService service = mock(ICommunityPostService.class);
        CommunityPostFeatureGate gate = mock(CommunityPostFeatureGate.class);
        ApiCommunityPostController controller = spy(new ApiCommunityPostController(service, gate));
        doReturn(8L).when(controller).getAppUserId();
        when(gate.canWrite(8L)).thenReturn(false);

        controller.delete(21L, null);
        controller.withdrawLocation(21L, null);
        controller.mute(9L, null);
        controller.report(null);
        controller.appeal(null);

        verify(service).delete(8L, 21L, null);
        verify(service).withdrawLocation(8L, 21L, null);
        verify(service).mute(8L, 9L, null);
        verify(service).report(8L, null);
        verify(service).appeal(8L, null);
        verify(gate, never()).canWrite(8L);
    }
}
