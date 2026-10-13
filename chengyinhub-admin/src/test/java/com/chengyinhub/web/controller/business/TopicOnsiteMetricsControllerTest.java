package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ITopicOnsiteMetricsService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopicOnsiteMetricsControllerTest {
    @Test
    void readOnlyEndpointFailsBeforeLoadingTopicWhenObjectScopeIsMissing() {
        TopicOnsiteMetricsController controller = new TopicOnsiteMetricsController();
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        ITopicOnsiteMetricsService metricsService = mock(ITopicOnsiteMetricsService.class);
        ITopicOperationsScopeAuthorizer scope = mock(ITopicOperationsScopeAuthorizer.class);
        ReflectionTestUtils.setField(controller, "topicService", topicService);
        ReflectionTestUtils.setField(controller, "topicOnsiteMetricsService", metricsService);
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scope);
        when(scope.canViewTopic(23L)).thenReturn(false);

        AjaxResult result = controller.get(23L);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(topicService, never()).selectCmsTopicById(23L);
        verify(metricsService, never()).get(23L);
    }
}
