package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.dto.TopicUpdateDTO;
import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IQuotaService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.web.controller.api.ApiTopicController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/** NOT_RUN. Prewrite rejection versus uncertain write outcome classification. */
class OWN_C03_24Test {

    private ApiTopicController controller;
    private ICmsTopicService topicService;
    private IWxContentSecurityService contentSecurity;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiTopicController());
        topicService = mock(ICmsTopicService.class);
        IQuotaService quotaService = mock(IQuotaService.class);
        contentSecurity = mock(IWxContentSecurityService.class);
        doReturn(19L).when(controller).getAppUserId();
        doReturn("tester").when(controller).getAppUserName();
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", contentSecurity);
        ReflectionTestUtils.setField(controller, "quotaService", quotaService);
        ReflectionTestUtils.setField(controller, "merchantAccessService", mock(IMerchantAccessService.class));
        // RUN-52 发布者实名:本用例测的是「业务闸文案能不能原样透到用户面前」,实名闸在这里放行
        // (mock 的 checkRegistered 返回 null = 已登记)。
        ReflectionTestUtils.setField(controller, "memberIdentityService",
                mock(com.chengyinhub.business.service.impl.MemberIdentityService.class));
        when(quotaService.checkCreate(anyLong(), anyString())).thenReturn(null);
        when(contentSecurity.checkText(any(), any())).thenReturn(null);
        com.chengyinhub.web.controller.api.TopicContentSafetyTestFixtures.wire(controller, contentSecurity);
    }

    /** 自由探索:绕开经典定向那段俱乐部门禁,直达 service 调用,让抛出的异常走到 catch。 */
    private TopicUpdateDTO freeExploreDto() {
        TopicUpdateDTO dto = new TopicUpdateDTO();
        dto.setId(77L);
        dto.setProductType(ProductType.FREE_EXPLORE);
        dto.setName("探店日");
        return dto;
    }

    private void rejected(AjaxResult result) {
        assertEquals(400, result.get(AjaxResult.CODE_TAG));
        org.mockito.Mockito.verify(topicService).selectCmsTopicById(77L);
        org.mockito.Mockito.verifyNoMoreInteractions(topicService);
    }
    @Test void titleRejectionIsKnownBeforeWrite() {
        when(contentSecurity.checkText(any(), any())).thenReturn("内容包含违规信息");
        rejected(controller.updateTopic(freeExploreDto()));
    }
    @Test void storyRejectionIsKnownBeforeWrite() {
        TopicUpdateDTO dto = freeExploreDto();
        dto.setJourneyStory("{\"call\":{\"opener\":\"违规台词\"}}");
        when(contentSecurity.checkText(any(), any())).thenReturn(null, "内容包含违规信息");
        rejected(controller.updateTopic(dto));
    }
    @Test void chapterTextRejectionIsKnownBeforeV2Write() {
        TopicUpdateDTO dto = freeExploreDto();
        com.chengyinhub.business.domain.dto.ChapterDTO chapter = new com.chengyinhub.business.domain.dto.ChapterDTO();
        com.chengyinhub.business.domain.dto.ChapterBlockDTO block = new com.chengyinhub.business.domain.dto.ChapterBlockDTO();
        block.setType("text"); block.setBeat("brief"); block.setField("question");
        block.setContent("违规台词"); block.setNodeIndex(0);
        chapter.setBlocks(java.util.Collections.singletonList(block));
        dto.setChapters(java.util.Collections.singletonList(chapter));
        when(contentSecurity.checkText(any(), any())).thenReturn(null, "内容包含违规信息");
        rejected(controller.updateTopicV2(dto));
    }
    @Test void serviceFailureRemainsUncertain() {
        when(topicService.updateTopicByOwner(any(), anyLong(), anyString())).thenThrow(new IllegalStateException("保存失败"));
        assertEquals(500, controller.updateTopic(freeExploreDto()).get(AjaxResult.CODE_TAG));
    }
    @Test void mediaFailureAfterWriteRemainsUncertain() {
        when(topicService.updateTopicByOwner(any(), anyLong(), anyString())).thenReturn(77L);
        org.mockito.Mockito.doThrow(new IllegalStateException("媒体失败"))
                .when(contentSecurity).submitMediaCheck(org.mockito.ArgumentMatchers.anyInt(), anyLong(), any(), any());
        // update's post-write media error is translated by the real HTTP exception advice,
        // not the service-only catch inside the controller method.
        IllegalStateException mediaFailure = assertThrows(IllegalStateException.class,
                () -> controller.updateTopic(freeExploreDto()));
        AjaxResult response = new com.chengyinhub.framework.web.exception.GlobalExceptionHandler()
                .handleRuntimeException(mediaFailure,
                        new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/topic/update"));
        assertEquals(500, response.get(AjaxResult.CODE_TAG));
        org.mockito.Mockito.verify(topicService).updateTopicByOwner(any(), anyLong(), anyString());
    }
    @Test void loginClassificationIsUnchanged() {
        doReturn(null).when(controller).getAppUserId();
        assertEquals(500, controller.updateTopic(freeExploreDto()).get(AjaxResult.CODE_TAG));
        org.mockito.Mockito.verifyNoInteractions(topicService);
    }
}
