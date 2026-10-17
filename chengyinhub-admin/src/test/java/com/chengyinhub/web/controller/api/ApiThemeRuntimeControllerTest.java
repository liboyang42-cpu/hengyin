package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ApiThemeRuntimeControllerTest {
    @Test void supportMatrixUsesThePublishPolicyAndHasNoSessionOrWrite() throws Exception {
        ApiThemeRuntimeController api = new ApiThemeRuntimeController();
        assertEquals(com.chengyinhub.business.service.support.GameplaySupportMatrix.view(), api.supportMatrix().get("data"));
        assertEquals("/support-matrix", ApiThemeRuntimeController.class.getMethod("supportMatrix").getAnnotation(org.springframework.web.bind.annotation.GetMapping.class).value()[0]);
    }
    @Test void routesUseLoggedInOwnerAndRejectMissingRequests() {
        ApiThemeRuntimeController api=spy(new ApiThemeRuntimeController());ITopicRouteRuntimeService runtime=mock(ITopicRouteRuntimeService.class);
        ReflectionTestUtils.setField(api,"runtime",runtime);doReturn(7L).when(api).getAppUserId();
        api.state(10L,0L,3L);verify(runtime).themeView(7L,10L,0L,3L);
        ApiThemeRuntimeController.Action request=new ApiThemeRuntimeController.Action();request.sessionId=3L;request.expectedVersion=2;request.actionId="use-1";request.operation="USE";request.contentId="food";
        api.action(request);verify(runtime).themeAction(7L,3L,2,"use-1","USE","food",null);api.replay(request);verify(runtime).replayTheme(7L,3L);
        request.operation="QUEST_RESOLVE";request.contentId="north";request.choiceKey="food";
        request.costOption=1;
        assertThrows(IllegalArgumentException.class,()->api.action(request));
        api.gameplayAction(request);verify(runtime).gameplayAction(7L,3L,2,"use-1","QUEST_RESOLVE","north","food",null,null,1);
        api.gameplayPreview(request);verify(runtime).gameplayPreview(7L,3L,2,"north","food",null,1,"QUEST_RESOLVE");
        assertThrows(IllegalArgumentException.class,()->api.gameplayPreview(null));
        assertThrows(IllegalArgumentException.class,()->api.gameplayAction(null));
        api.card(3L);verify(runtime).themeCard(7L,3L);api.history(10L);verify(runtime).themeHistory(7L,10L);
        assertThrows(IllegalArgumentException.class,()->api.action(null));assertThrows(IllegalArgumentException.class,()->api.replay(null));
        doReturn(null).when(api).getAppUserId();assertThrows(RuntimeException.class,()->api.state(10L,0L,null));
    }
    @Test void costOptionRejectsFractionalAndTextIndexes() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals(1,json.readValue("{\"costOption\":1}",ApiThemeRuntimeController.Action.class).costOption);
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,()->json.readValue("{\"costOption\":1.5}",ApiThemeRuntimeController.Action.class));
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,()->json.readValue("{\"costOption\":\"1\"}",ApiThemeRuntimeController.Action.class));
    }

}
