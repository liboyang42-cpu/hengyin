package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 稿 468:1126「章节控制 → 开放报名」的后端闸。
 *
 * <p>这一行开关的是 cms_topic_chapter.recruit_enabled(2026-09-10 用户确认:
 * 说的是「开放**商家承接**这一章」,不是玩家买票 —— 玩家票在本仓是主题级的)。
 *
 * <p>本用例钉三件事,少任何一件这个端点就是个洞:
 * ① 只有主题归属人能动;
 * ② patch **只带** id 与 recruitEnabled —— mapper 是部分更新,多带一个字段就会一起写进库;
 * ③ 业务规则一条都不在控制器里重写,服务层抛什么就原样回什么。
 */
class ApiTopicChapterRecruitToggleTest
{
    private ApiTopicController controller;
    private ICmsTopicService topicService;
    private ICmsTopicChapterService chapterService;

    @BeforeEach
    void setUp()
    {
        controller = spy(new ApiTopicController());
        topicService = mock(ICmsTopicService.class);
        chapterService = mock(ICmsTopicChapterService.class);
        doReturn(19L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "cmsTopicChapterService", chapterService);
    }

    private void givenChapterOwnedBy(long ownerMemberId)
    {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(7L);
        chapter.setTopicId(42L);
        chapter.setRecruitEnabled(0);
        when(chapterService.selectCmsTopicChapterById(7L)).thenReturn(chapter);

        CmsTopic topic = new CmsTopic();
        topic.setId(42L);
        topic.setMemberId(ownerMemberId);
        when(topicService.selectCmsTopicById(42L)).thenReturn(topic);
    }

    @Test
    void 归属人才能开关_别人一律拒()
    {
        givenChapterOwnedBy(999L);   // 主题属于别人

        AjaxResult result = controller.setChapterRecruit("7", 1, null);

        assertNotEquals(200, result.get("code"), "不是自己的主题必须拒绝");
        verify(chapterService, never()).setChapterRecruitAsOwner(any(), any(), any());
        verify(chapterService, never()).updateCmsTopicChapter(any());
    }

    @Test
    void 归属人开关时走带owner的那条语句()
    {
        givenChapterOwnedBy(19L);
        when(chapterService.setChapterRecruitAsOwner(7L, 19L, 1)).thenReturn(1);

        AjaxResult result = controller.setChapterRecruit("7", 1, null);
        assertEquals(200, result.get("code"));

        /* ★ 2026-09-11:不再走通用的 updateCmsTopicChapter —— 那条是裸 where id = #{id},
           而上面的归属判断是「先读后写」,两次请求之间归属可能已经变了。
           setChapterRecruitAsOwner 把 owner 与「未结束」一起压进 WHERE,
           顺带也就只写 recruit_enabled 一列,不会顺手改章节名/归属/条款档。 */
        verify(chapterService).setChapterRecruitAsOwner(7L, 19L, 1);
        verify(chapterService, never()).updateCmsTopicChapter(any());
    }

    /* 归属在两次请求之间被改掉时,SQL 匹配不到行、返回 0。端点必须判失败,
       不能因为「没抛异常」就回 200 —— 那是谎报成功。 */
    @Test
    void SQL写0行时端点必须判失败()
    {
        givenChapterOwnedBy(19L);
        when(chapterService.setChapterRecruitAsOwner(7L, 19L, 1)).thenReturn(0);

        assertNotEquals(200, controller.setChapterRecruit("7", 1, null).get("code"));
    }

    @Test
    void 服务层拒绝的理由原样回给前端()
    {
        givenChapterOwnedBy(19L);
        when(chapterService.setChapterRecruitAsOwner(any(), any(), any()))
                .thenThrow(new IllegalStateException("章节已有3条商家申请和2条供给记录，不能关闭商家承接"));

        AjaxResult result = controller.setChapterRecruit("7", 0, null);

        assertNotEquals(200, result.get("code"));
        // 「操作失败」四个字帮不了主办方 —— 他需要知道是因为已经有商家申请了
        assertTrue(String.valueOf(result.get("msg")).contains("已有3条商家申请"),
                "服务层写清楚的理由不该被压成通用错误:" + result.get("msg"));
    }

    @Test
    void 非法入参不进服务层()
    {
        givenChapterOwnedBy(19L);

        assertNotEquals(200, controller.setChapterRecruit("0", 1, null).get("code"));
        assertNotEquals(200, controller.setChapterRecruit("7", null, null).get("code"));
        assertNotEquals(200, controller.setChapterRecruit("7", 2, null).get("code"),
                "enabled 只有 0/1 两个合法值");
        verify(chapterService, never()).setChapterRecruitAsOwner(any(), any(), any());
        verify(chapterService, never()).updateCmsTopicChapter(any());
    }
}
