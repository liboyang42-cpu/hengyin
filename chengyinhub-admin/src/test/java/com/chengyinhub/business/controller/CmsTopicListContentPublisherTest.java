package com.chengyinhub.business.controller;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ITopicListProjectionService;
import com.chengyinhub.common.constant.RoleEntityType;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.web.controller.business.CmsTopicController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 后台列表只投影来源类型；无槽与异常槽必须保留不同信号。 */
class CmsTopicListContentPublisherTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void list_delegatesCrossServiceFactsToListProjection() {
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        ITopicListProjectionService projectionService = mock(ITopicListProjectionService.class);
        com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer scopeAuthorizer =
                mock(com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer.class);
        when(scopeAuthorizer.hasGlobalTopicScope()).thenReturn(true);
        CmsTopicController controller = new CmsTopicController();
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "topicListProjectionService", projectionService);
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scopeAuthorizer);

        CmsTopic official = topic(1L);
        CmsTopic historical = topic(2L);
        CmsTopic abnormal = topic(3L);
        List<CmsTopic> topics = Arrays.asList(official, historical, abnormal);
        official.setContentPublisherType(RoleEntityType.PLATFORM);
        abnormal.setContentPublisherType(RoleEntityType.MERCHANT);
        when(topicService.selectCmsTopicList(any(CmsTopic.class))).thenReturn(topics);
        when(projectionService.project(topics)).thenReturn(topics);

        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        TableDataInfo result = controller.list(new CmsTopic());

        @SuppressWarnings("unchecked")
        List<CmsTopic> rows = (List<CmsTopic>) result.getRows();
        assertEquals(RoleEntityType.PLATFORM, rows.get(0).getContentPublisherType());
        assertEquals(null, rows.get(1).getContentPublisherType(), "无槽不能猜成官方或广场");
        assertEquals(RoleEntityType.MERCHANT, rows.get(2).getContentPublisherType(),
                "异常槽必须交给前端显示“主体异常”，不能伪装成历史待归属");
        verify(projectionService).project(topics);
    }

    private CmsTopic topic(Long id) {
        CmsTopic topic = new CmsTopic();
        topic.setId(id);
        return topic;
    }

}
