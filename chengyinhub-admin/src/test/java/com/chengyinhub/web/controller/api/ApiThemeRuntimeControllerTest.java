package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ApiThemeRuntimeControllerTest {
    @Test void routesUseLoggedInOwnerAndRejectMissingRequests() {
        ApiThemeRuntimeController api=spy(new ApiThemeRuntimeController());ITopicRouteRuntimeService runtime=mock(ITopicRouteRuntimeService.class);
        ReflectionTestUtils.setField(api,"runtime",runtime);doReturn(7L).when(api).getAppUserId();
        api.state(10L,0L,3L);verify(runtime).themeView(7L,10L,0L,3L);
        ApiThemeRuntimeController.Action request=new ApiThemeRuntimeController.Action();request.sessionId=3L;request.expectedVersion=2;request.actionId="use-1";request.operation="USE";request.contentId="food";
        api.action(request);verify(runtime).themeAction(7L,3L,2,"use-1","USE","food");api.replay(request);verify(runtime).replayTheme(7L,3L);
        api.card(3L);verify(runtime).themeCard(7L,3L);api.history(10L);verify(runtime).themeHistory(7L,10L);
        assertThrows(IllegalArgumentException.class,()->api.action(null));assertThrows(IllegalArgumentException.class,()->api.replay(null));
        doReturn(null).when(api).getAppUserId();assertThrows(RuntimeException.class,()->api.state(10L,0L,null));
    }
}
