package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.OfficialEvent;
import com.chengyinhub.business.domain.dto.OfficialEventV2DraftRequest;
import com.chengyinhub.business.service.IOfficialEventV2Service;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiOfficialEventControllerV2DraftTest {

    @Test
    void draftCreatorCanContinueSavingBeforeInvitedOrganizerAccepts() {
        IOfficialEventV2Service v2 = mock(IOfficialEventV2Service.class);
        ISysConfigService config = mock(ISysConfigService.class);
        ApiOfficialEventController controller = spy(new ApiOfficialEventController());
        doReturn(101L).when(controller).getAppUserId();
        when(config.selectConfigByKey("official_publish_whitelist")).thenReturn("101");
        ReflectionTestUtils.setField(controller, "officialEventV2Service", v2);
        ReflectionTestUtils.setField(controller, "configService", config);

        OfficialEvent draft = new OfficialEvent();
        draft.setId(7L);
        OfficialEventV2DraftRequest request = new OfficialEventV2DraftRequest();
        request.setEvent(draft);
        when(v2.canOperateControlledEvent(7L, 101L)).thenReturn(true);
        when(v2.saveDraft(request, 101L)).thenReturn(draft);

        AjaxResult result = controller.saveV2Draft(request);

        assertTrue(result.isSuccess());
        verify(v2).saveDraft(request, 101L);
    }
}
