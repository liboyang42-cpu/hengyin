package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.ClubLeadState;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.framework.web.exception.GlobalExceptionHandler;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import com.chengyinhub.business.service.IGameSessionRuntimeService;
import java.util.Map;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
        verify(clubLeadMapper, never()).selectByActivity(100L);
        for (String status : new String[]{"PREPARING", "READY", "FINISHED", "CANCELLED"}) {
            when(runtime.routeSessionStatus(100L)).thenReturn(status);
            assertEquals("本场带队尚未开局或已结束", gate(100L, node(30L)));
        }
        when(runtime.routeSessionStatus(100L)).thenReturn(null);
        assertNotNull(gate(100L, node(30L)), "原带队玩法仍保留章节校验");
    }

    @Test void storageFailureIsSafeRetryableNotRunRatherThanNonLead() {
        when(clubLeadMapper.selectByActivity(100L)).thenThrow(
                new DataAccessResourceFailureException("SELECT internal_table WHERE secret=value"));
        assertUnavailable(() -> gate(100L, node(11L)));
        verifyNoInteractions(chapterService);
    }

    @Test void missingMandatoryMapperCannotGrantNonLeadAdmission() {
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "clubLeadMapper", null);
        assertUnavailable(() -> gate(100L, node(11L)));
    }

    @Test void runtimeLookupFailureDoesNotFallBackToLegacyAdmission() {
        IGameSessionRuntimeService runtime = mock(IGameSessionRuntimeService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "gameSessionRuntimeService", runtime);
        when(runtime.routeSessionStatus(100L)).thenThrow(new DataAccessResourceFailureException("internal runtime SQL"));
        assertUnavailable(() -> gate(100L, node(11L)));
        verifyNoInteractions(clubLeadMapper, chapterService);
    }

    @Test void chapterDependencyFailureDoesNotGrantAnUnopenedChapter() {
        when(clubLeadMapper.selectByActivity(100L)).thenReturn(state(2, 20L));
        when(chapterService.selectCmsTopicChapterById(20L)).thenThrow(new IllegalStateException("internal dependency details"));
        assertUnavailable(() -> gate(100L, node(30L)));
    }

    @Test void existingPermissionRefusalKeepsItsCodeAndIsNeverRelabeledUnknownOrNotRun() {
        ServiceException forbidden = new ServiceException("无权读取本场状态", 403);
        when(clubLeadMapper.selectByActivity(100L)).thenThrow(forbidden);
        ServiceException actual = assertThrowsExactly(ServiceException.class, () -> gate(100L, node(11L)));
        assertSame(forbidden, actual); assertEquals(Integer.valueOf(403), actual.getCode()); assertNull(actual.getData());
    }

    @Test void selfPlayStillBypassesAllLeadDependencies() {
        IGameSessionRuntimeService runtime = mock(IGameSessionRuntimeService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "gameSessionRuntimeService", runtime);
        assertNull(gate(0L, node(11L))); assertNull(gate(null, node(11L)));
        verifyNoInteractions(runtime, clubLeadMapper, chapterService);
    }

    private void assertUnavailable(org.junit.jupiter.api.function.Executable operation) {
        ServiceException failure = assertThrowsExactly(ServiceException.class, operation);
        assertEquals(Integer.valueOf(503), failure.getCode());
        assertEquals("带领状态暂时不可用，请稍后重试", failure.getMessage());
        assertEquals(Map.of("outcome", "NOT_RUN"), failure.getData());
        assertNull(failure.getCause()); assertNull(failure.getDetailMessage());
        var response = new GlobalExceptionHandler().handleServiceException(failure, new MockHttpServletRequest());
        assertEquals(503, response.get("code")); assertEquals(failure.getMessage(), response.get("msg"));
        assertEquals(Map.of("outcome", "NOT_RUN"), response.get("data"));
    }

}
