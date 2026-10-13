package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.ChapterBlockDTO;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.TopicUpdateDTO;
import com.chengyinhub.business.domain.vo.TopicBundleSubmitResultVO;
import com.chengyinhub.business.exception.ChapterFlowValidationException;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IQuotaService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** V2 章节流契约错误必须稳定返回 400，不得混成通用 500。 */
class ApiTopicChapterFlowV2ControllerTest
{
    private ApiTopicController controller;
    private ICmsTopicService topicService;
    private IWxContentSecurityService wxSecurity;

    @BeforeEach
    void setUp()
    {
        controller = spy(new ApiTopicController());
        topicService = mock(ICmsTopicService.class);
        wxSecurity = mock(IWxContentSecurityService.class);
        doReturn(19L).when(controller).getAppUserId();
        doReturn("tester").when(controller).getAppUserName();
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", wxSecurity);
        // X03:updateTopic 现在会先过节点数额度闸,mock 返回 null=放行,
        // 本用例断言的是章节流契约错误的 400 语义,不该被额度闸挡在前面。
        ReflectionTestUtils.setField(controller, "quotaService", mock(IQuotaService.class));
    }

    /**
     * R19:叙事搬进章节故事流后,beat 块正文必须与主题剧情同一道内容安全闸 ——
     * 命中时拦在保存前,提示带字段名(storyFlow...),且不落任何写操作。
     *
     * <p>负控:删掉 updateTopic 里 chapterFlowV2 的 JourneyReviewTexts.check 分支,本用例立刻红。
     */
    @Test
    void beatBlockViolatingTextRejectedBeforeSave()
    {
        when(wxSecurity.checkText(anyString(), any())).thenReturn(null);
        when(wxSecurity.checkText(contains("违规台词"), any())).thenReturn("内容包含违规信息");
        TopicUpdateDTO dto = new TopicUpdateDTO();
        ChapterDTO chapter = new ChapterDTO();
        ChapterBlockDTO prompt = new ChapterBlockDTO();
        prompt.setType("text");
        prompt.setBeat("brief");
        prompt.setField("question");
        prompt.setNodeIndex(0);
        prompt.setContent("违规台词:来路不明");
        chapter.setBlocks(Collections.singletonList(prompt));
        dto.setChapters(Collections.singletonList(chapter));

        AjaxResult result = controller.updateTopicV2(dto);

        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("违规"),
                "命中的正文必须在保存前被拦下:" + result.get(AjaxResult.MSG_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("storyFlow.chapter1.brief.question"),
                "提示必须带字段名,作者才知道改哪:" + result.get(AjaxResult.MSG_TAG));
        verify(topicService, never()).updateTopicByOwnerV2WithReview(any(), anyLong(), anyString());
    }

    @Test
    void nodeIndexBoundsViolationReturnsBadRequest()
    {
        when(topicService.updateTopicByOwnerV2WithReview(any(), anyLong(), anyString()))
                .thenThrow(new ChapterFlowValidationException("第 1 章故事流不合法: nodeIndex 越界: 1"));

        AjaxResult result = controller.updateTopicV2(new TopicUpdateDTO());

        assertEquals(HttpStatus.BAD_REQUEST, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("nodeIndex 越界"),
                "A4 必须把具体下标闸文案返回给客户端");
    }

    @Test
    void optionalStoryChapterReturnsBadRequest()
    {
        when(topicService.updateTopicByOwnerV2WithReview(any(), anyLong(), anyString()))
                .thenThrow(new ChapterFlowValidationException("第 1 章故事流不合法: required 必须为 1"));

        AjaxResult result = controller.updateTopicV2(new TopicUpdateDTO());

        assertEquals(HttpStatus.BAD_REQUEST, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("required 必须为 1"),
                "A11 必须把具体必选章闸文案返回给客户端");
    }

    @Test
    void successfulV2SubmissionReturnsTheBundleReviewContract()
    {
        TopicBundleSubmitResultVO submitted = new TopicBundleSubmitResultVO(
                77L, 301L, "PENDING", java.util.Arrays.asList(91L, 92L));
        when(topicService.updateTopicByOwnerV2WithReview(any(), anyLong(), anyString()))
                .thenReturn(submitted);

        AjaxResult result = controller.updateTopicV2(new TopicUpdateDTO());

        assertEquals("已提交审核", result.get(AjaxResult.MSG_TAG));
        assertEquals(submitted, result.get(AjaxResult.DATA_TAG));
        assertEquals(77L, ((TopicBundleSubmitResultVO) result.get(AjaxResult.DATA_TAG)).getTopicId());
        assertEquals(301L, ((TopicBundleSubmitResultVO) result.get(AjaxResult.DATA_TAG)).getAuditTaskId());
        assertEquals("PENDING", ((TopicBundleSubmitResultVO) result.get(AjaxResult.DATA_TAG)).getReviewState());
        assertEquals(java.util.Arrays.asList(91L, 92L),
                ((TopicBundleSubmitResultVO) result.get(AjaxResult.DATA_TAG)).getBundledTemplateIds());
    }
}
