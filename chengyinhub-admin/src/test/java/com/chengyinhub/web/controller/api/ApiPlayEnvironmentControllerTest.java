package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.support.rpg.TopicWeatherEventService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class ApiPlayEnvironmentControllerTest {

    @Test
    void environmentRequiresAuthAndValidTopicAndDelegatesReadOnly() {
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        TopicWeatherEventService envService = mock(TopicWeatherEventService.class);
        ApiPlayEnvironmentController controller = spy(new ApiPlayEnvironmentController());
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "topicWeatherEventService", envService);

        doThrow(new RuntimeException("no token")).when(controller).getAppUserId();
        AjaxResult unauth = controller.environment("10");
        assertEquals(401, unauth.get(AjaxResult.CODE_TAG));
        assertEquals("ENV_LOGIN_REQUIRED", unauth.get("errorCode"));

        doReturn(7L).when(controller).getAppUserId();
        AjaxResult bad = controller.environment("abc");
        assertEquals(400, bad.get(AjaxResult.CODE_TAG));
        assertEquals("ENV_BAD_REQUEST", bad.get("errorCode"));

        when(topicService.selectCmsTopicById(999L)).thenReturn(null);
        AjaxResult missing = controller.environment("999");
        assertEquals(404, missing.get(AjaxResult.CODE_TAG));
        assertEquals("ENV_TOPIC_NOT_FOUND", missing.get("errorCode"));

        CmsTopic topic = new CmsTopic();
        topic.setId(10L);
        when(topicService.selectCmsTopicById(10L)).thenReturn(topic);
        when(envService.playerEnvironmentView(topic))
                .thenReturn(Collections.<String, Object>singletonMap("sourceStatus", "AVAILABLE"));
        AjaxResult ok = controller.environment("10");
        assertEquals(200, ok.get(AjaxResult.CODE_TAG));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) ok.get(AjaxResult.DATA_TAG);
        assertEquals("AVAILABLE", data.get("sourceStatus"));
    }
}
