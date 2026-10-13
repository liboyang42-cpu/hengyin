package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

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
 * 稿 468:1126「章节控制 → 结束本章」。
 *
 * <p>2026-09-10 用户定的闸:<b>只有发起人能点</b>。发起人 = 主题归属人,
 * 与同组「开放报名」同一把锁 —— 那一组的组标题本来就写着「只有主办方能动」。
 *
 * <p>这是一条不可逆写,所以除了权限还要钉三件事:
 * ① 已结束的再点一次原样拒掉,<b>不覆盖已经记下的时刻</b>(结算要按那个时间对账);
 * ② 落库走 {@code finishChapterAsOwner} 而不是通用的 {@code updateCmsTopicChapter} ——
 *    后者是裸 {@code where id = #{id}};前者把 owner 与 {@code finish_time is null}
 *    一起压进 WHERE,并发两次只能成一次,且写 0 行时端点必须判失败;
 * ③ 结束之后那条招商开关要被挡住,否则「结束」就退化成一个可以来回拨的开关。
 */
class ApiTopicChapterFinishTest
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

    private CmsTopicChapter givenChapter(long ownerMemberId, Date finishedAt)
    {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(7L);
        chapter.setTopicId(42L);
        chapter.setRecruitEnabled(1);
        chapter.setFinishTime(finishedAt);
        when(chapterService.selectCmsTopicChapterById(7L)).thenReturn(chapter);

        CmsTopic topic = new CmsTopic();
        topic.setId(42L);
        topic.setMemberId(ownerMemberId);
        when(topicService.selectCmsTopicById(42L)).thenReturn(topic);
        return chapter;
    }

    @Test
    void 只有发起人能结束_承接方点了也不行()
    {
        givenChapter(999L, null);   // 主题属于别人

        AjaxResult result = controller.finishChapter("7", null);

        assertNotEquals(200, result.get("code"), "不是发起人必须拒绝");
        assertTrue(String.valueOf(result.get("msg")).contains("发起人"),
                "拒绝的理由要说清是谁能点,不能只回「无权操作」:" + result.get("msg"));
        verify(chapterService, never()).updateCmsTopicChapter(any());
    }

    @Test
    void 发起人结束时只写结束时刻这一个字段()
    {
        givenChapter(19L, null);
        when(chapterService.finishChapterAsOwner(7L, 19L)).thenReturn(1);

        AjaxResult result = controller.finishChapter("7", null);
        assertEquals(200, result.get("code"));

        /* ★ 2026-09-11:不再走 updateCmsTopicChapter(裸 where id=#{id})。
           归属与「未结束」这两道闸都压在 SQL 的 WHERE 里 —— Java 侧判一次是先读后写,
           并发两次都能过,第二次会覆盖掉结算要用的那个时刻。 */
        verify(chapterService).finishChapterAsOwner(7L, 19L);
        verify(chapterService, never()).updateCmsTopicChapter(any());
    }

    @Test
    void 已结束的再点一次不覆盖原来的时刻()
    {
        givenChapter(19L, new Date(1000L));

        AjaxResult result = controller.finishChapter("7", null);

        assertNotEquals(200, result.get("code"));
        // 覆盖会把结算对账的基准时间改掉,而这个动作本身「看上去成功了」——最坏的一种静默错误
        verify(chapterService, never()).finishChapterAsOwner(any(), any());
        verify(chapterService, never()).updateCmsTopicChapter(any());
    }

    /* 并发那一路读到的还是「未结束」(先读后写),Java 侧那句 if 放它过去 ——
       这时唯一挡住覆盖的是 SQL 的 finish_time is null,它以 rows=0 的形式回来。
       端点必须把 0 当失败,不能因为「没抛异常」就回 200。 */
    @Test
    void 并发抢到第二名时SQL返回0_端点必须判失败()
    {
        givenChapter(19L, null);
        when(chapterService.finishChapterAsOwner(7L, 19L)).thenReturn(0);

        AjaxResult result = controller.finishChapter("7", null);

        assertNotEquals(200, result.get("code"), "写了 0 行还回 200 = 谎报成功");
    }

    @Test
    void 结束之后招商开关被挡住_不然结束就成了可来回拨的开关()
    {
        givenChapter(19L, new Date(1000L));

        AjaxResult result = controller.setChapterRecruit("7", 1, null);

        assertNotEquals(200, result.get("code"), "已结束的章节不能再开放商家承接");
        verify(chapterService, never()).updateCmsTopicChapter(any());
    }

    @Test
    void 非法入参不进服务层()
    {
        givenChapter(19L, null);

        assertNotEquals(200, controller.finishChapter("0", null).get("code"));
        assertNotEquals(200, controller.finishChapter(null, null).get("code"));
        verify(chapterService, never()).updateCmsTopicChapter(any());
    }
}
