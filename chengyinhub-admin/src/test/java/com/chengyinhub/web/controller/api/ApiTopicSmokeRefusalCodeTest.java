package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ITeamGroupService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * SMOKE-500-R4：主题端点的业务拒绝不再压成 500，文案原样保留。
 * 不存在 → 404；状态冲突（已结束 / 已开售 / 非 Beta / 有在途报名 / 俱乐部主题走俱乐部取消）→ 409。
 */
class ApiTopicSmokeRefusalCodeTest
{
    private static final Long ME = 19L;
    private ApiTopicController controller;
    private ICmsTopicService topicService;
    private ICmsTopicChapterService chapterService;
    private ITeamGroupService teamGroupService;

    @BeforeEach
    void setUp()
    {
        controller = spy(new ApiTopicController());
        topicService = mock(ICmsTopicService.class);
        chapterService = mock(ICmsTopicChapterService.class);
        teamGroupService = mock(ITeamGroupService.class);
        doReturn(ME).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "cmsTopicChapterService", chapterService);
        ReflectionTestUtils.setField(controller, "teamGroupService", teamGroupService);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService",
                mock(com.chengyinhub.business.service.IWxContentSecurityService.class));
    }

    private static void assertRefusal(AjaxResult r, int code, String msg)
    {
        assertEquals(code, r.get(AjaxResult.CODE_TAG));
        assertEquals(msg, r.get(AjaxResult.MSG_TAG));
    }

    @Test
    void clubTopicCancelAndPreviewReturn409()
    {
        String msg = "俱乐部主题请在俱乐部「主题设置 → 结束主题」里取消";
        when(teamGroupService.cancelTopicByHost(eq(42L), eq(ME), eq(ME), anyString()))
                .thenThrow(new ServiceException(msg, HttpStatus.CONFLICT));
        when(teamGroupService.countPaidPlayersForTopicCancel(42L, ME))
                .thenThrow(new ServiceException(msg, HttpStatus.CONFLICT));

        assertRefusal(controller.topicCancel("42", "E2E", null), HttpStatus.CONFLICT, msg);
        assertRefusal(controller.topicCancelPreview("42", null), HttpStatus.CONFLICT, msg);
    }

    @Test
    void deleteWithLiveRegistrationsReturns409()
    {
        String msg = "该主题的场次仍有待支付或已支付报名，请先处理场次报名，再删除主题";
        when(topicService.deleteTopicByOwner(42L, ME)).thenThrow(new ServiceException(msg, HttpStatus.CONFLICT));

        assertRefusal(controller.topicDelete("42", null), HttpStatus.CONFLICT, msg);
    }

    @Test
    void reportOnNonPublicTopicReturns404()
    {
        CmsTopic hidden = new CmsTopic();
        hidden.setId(42L);
        hidden.setStatus(0);
        when(topicService.selectCmsTopicById(42L)).thenReturn(hidden);

        assertRefusal(controller.topicReport("42", "E2E"), HttpStatus.NOT_FOUND, "主题不存在");
    }

    @Test
    void recruitToggleOnFinishedChapterReturns409()
    {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(7L);
        chapter.setTopicId(42L);
        chapter.setFinishTime(new Date());
        when(chapterService.selectCmsTopicChapterById(7L)).thenReturn(chapter);
        CmsTopic topic = new CmsTopic();
        topic.setId(42L);
        topic.setMemberId(ME);
        when(topicService.selectCmsTopicById(42L)).thenReturn(topic);

        assertRefusal(controller.setChapterRecruit("7", 1, null), HttpStatus.CONFLICT, "本章已结束，不能再改商家承接");
    }

    @Test
    void supplyTermsFrozenAfterSaleReturns409()
    {
        doThrow(new IllegalStateException("主题已开售，这一站的档位已冻结"))
                .when(chapterService).configureSupplyTermsAsOwner(anyLong(), anyLong(), any());

        assertRefusal(controller.setChapterSupplyTerms("7", "PERK", null, null, 3, null),
                HttpStatus.CONFLICT, "主题已开售，这一站的档位已冻结");
    }
}
