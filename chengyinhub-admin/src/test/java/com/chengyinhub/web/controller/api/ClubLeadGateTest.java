package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.ClubLeadState;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1 带领场推进闸测试(服务端强校验,不靠前端禁按钮):
 * 集合中禁推进 / 未放行章节被拒 / 已放行与更早章节放行 / 非带领场与自玩不拦。
 */
class ClubLeadGateTest {

    private ApiPlayProgressController controller;
    private ClubLeadMapper clubLeadMapper;
    private ICmsTopicChapterService chapterService;

    @BeforeEach
    void setUp() {
        controller = new ApiPlayProgressController();
        clubLeadMapper = mock(ClubLeadMapper.class);
        chapterService = mock(ICmsTopicChapterService.class);
        PlayNodeAccessTestFixtures.setField(controller, "clubLeadMapper", clubLeadMapper);
        PlayNodeAccessTestFixtures.setField(controller, "cmsTopicChapterService", chapterService);
    }

    private String gate(Long actId, CmsTopicNode node) {
        return com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.invokeMethod(controller, "leadGateError", actId, node);
    }

    private ClubLeadState state(int status, Long currentChapterId) {
        ClubLeadState st = new ClubLeadState();
        st.setId(1L);
        st.setActivityId(100L);
        st.setLeaderMemberId(9L);
        st.setStatus(status);
        st.setCurrentChapterId(currentChapterId);
        return st;
    }

    private CmsTopicNode node(Long chapterId) {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(5L);
        n.setChapterId(chapterId);
        return n;
    }

    private CmsTopicChapter chapter(Long id, Long sortId) {
        CmsTopicChapter c = new CmsTopicChapter();
        c.setId(id);
        c.setSortId(sortId);
        return c;
    }

    @Test
    void gatheringBlocksAllProgress() {
        when(clubLeadMapper.selectByActivity(100L)).thenReturn(state(1, null));
        assertNotNull(gate(100L, node(11L)), "集合中(status=1)必须拒绝推进");
    }

    @Test
    void lockedChapterRejected_unlockedAndEarlierAllowed() {
        when(clubLeadMapper.selectByActivity(100L)).thenReturn(state(2, 20L)); // 已放行第2章(id=20)
        when(chapterService.selectCmsTopicChapterById(20L)).thenReturn(chapter(20L, 2L));
        when(chapterService.selectCmsTopicChapterById(30L)).thenReturn(chapter(30L, 3L));
        when(chapterService.selectCmsTopicChapterById(10L)).thenReturn(chapter(10L, 1L));

        assertNotNull(gate(100L, node(30L)), "第3章未放行,必须拒");
        assertNull(gate(100L, node(20L)), "当前章可推进");
        assertNull(gate(100L, node(10L)), "更早章节可补打");
    }

    @Test
    void noLeadStateOrSettledOrSelfPlayNotBlocked() {
        when(clubLeadMapper.selectByActivity(100L)).thenReturn(null);
        assertNull(gate(100L, node(11L)), "非带领场不拦");

        when(clubLeadMapper.selectByActivity(100L)).thenReturn(state(3, 20L));
        assertNull(gate(100L, node(11L)), "已结算不拦");

        assertNull(gate(0L, node(11L)), "自玩会话(actId=0)不拦");
        assertNull(gate(null, node(11L)), "无 actId 不拦");
    }
    @Test
    void routeRuntimeOverridesStaleLegacyGatheringAndChapterState() {
        com.chengyinhub.business.service.IGameSessionRuntimeService runtime = mock(
                com.chengyinhub.business.service.IGameSessionRuntimeService.class);
        PlayNodeAccessTestFixtures.setField(controller, "gameSessionRuntimeService", runtime);
        when(clubLeadMapper.selectByActivity(100L)).thenReturn(state(1, 20L));
        when(runtime.routeSessionStatus(100L)).thenReturn("RUNNING");
        assertNull(gate(100L, node(30L)), "普通路线开局后不受旧集合和章节状态阻断");
        for (String status : new String[]{"PREPARING", "READY", "FINISHED", "CANCELLED"}) {
            when(runtime.routeSessionStatus(100L)).thenReturn(status);
            assertEquals("本场带队尚未开局或已结束", gate(100L, node(30L)));
        }
        when(runtime.routeSessionStatus(100L)).thenReturn(null);
        assertNotNull(gate(100L, node(30L)), "原带队玩法仍保留章节校验");
    }

}
