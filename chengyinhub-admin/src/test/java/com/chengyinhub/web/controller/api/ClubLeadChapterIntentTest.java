package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.club.ClubEventLeadResolver;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 控制器合同测试；真实并发/回滚仍需受控 MySQL 验证。 */
class ClubLeadChapterIntentTest {
    private ApiClubLeadController controller;
    private ClubLeadMapper states;
    private GameSessionRuntimeMapper receipts;
    private ClubEventLeadResolver leaders;
    private CmsActivity activity;
    private ClubLeadState state;
    private final Map<String, GameCommandReceipt> saved = new HashMap<>();

    @BeforeEach void setup() {
        saved.clear(); controller = spy(new ApiClubLeadController()); doReturn(9L).when(controller).getAppUserId();
        states = mock(ClubLeadMapper.class); receipts = mock(GameSessionRuntimeMapper.class); leaders = mock(ClubEventLeadResolver.class);
        ICmsActivityService activities = mock(ICmsActivityService.class); CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        ICmsTopicChapterService chapters = mock(ICmsTopicChapterService.class);
        ReflectionTestUtils.setField(controller, "clubLeadMapper", states); ReflectionTestUtils.setField(controller, "commandReceiptMapper", receipts);
        ReflectionTestUtils.setField(controller, "cmsActivityService", activities); ReflectionTestUtils.setField(controller, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(controller, "cmsTopicChapterService", chapters); ReflectionTestUtils.setField(controller, "clubEventLeadResolver", leaders);
        activity = new CmsActivity(); activity.setId(100L); activity.setClubId(5L); activity.setTopicId(1L); activity.setDelFlag(0);
        when(activities.selectCmsActivityById(100L)).thenReturn(activity); when(activityMapper.selectCmsActivityByIdForUpdate(100L)).thenReturn(activity);
        when(leaders.resolveLeaderMemberId(activity)).thenReturn(9L); when(states.lockClubOwner(5L)).thenReturn(9L);
        state = new ClubLeadState(); state.setId(1L); state.setActivityId(100L); state.setLeaderMemberId(9L); state.setCurrentChapterId(10L); state.setStatus(2);
        when(states.selectByActivityForUpdate(100L)).thenReturn(state);
        when(chapters.selectCmsTopicChapterList(any())).thenAnswer(i -> new ArrayList<>(Arrays.asList(chapter(10), chapter(20), chapter(30))));
        when(states.unlockChapterCas(eq(100L), anyLong(), eq(9L), nullable(Long.class), anyLong())).thenAnswer(i -> {
            if (!Objects.equals(state.getCurrentChapterId(), i.getArgument(3))) return 0;
            state.setCurrentChapterId(i.getArgument(4)); return 1;
        });
        when(receipts.selectReceiptForUpdate(eq(100L), eq(9L), anyString())).thenAnswer(i -> saved.get(i.getArgument(2)));
        when(receipts.selectReceipt(eq(100L), eq(9L), anyString())).thenAnswer(i -> saved.get(i.getArgument(2)));
        when(receipts.insertReceiptClaim(any())).thenAnswer(i -> { GameCommandReceipt row = i.getArgument(0); row.setId(1L); saved.put(row.getRequestId(), row); return 1; });
        when(receipts.completeReceipt(any())).thenReturn(1);
    }
    private CmsTopicChapter chapter(long id) { CmsTopicChapter row = new CmsTopicChapter(); row.setId(id); row.setSortId(id); row.setName("chapter-" + id); return row; }

    @Test void sequentialReplayNeverSkipsAndChangedExpectedOrTargetConflicts() {
        AjaxResult first = controller.unlockChapter("100", "one", 10L, 20L);
        assertEquals(200, first.get("code")); assertEquals(20L, state.getCurrentChapterId());
        assertEquals(first, controller.unlockChapter("100", "one", 10L, 20L));
        assertEquals(first, controller.unlockChapterReceipt("100", "one"));
        assertEquals(409, controller.unlockChapter("100", "one", 20L, 30L).get("code"));
        assertEquals(20L, state.getCurrentChapterId());
        verify(states, times(1)).unlockChapterCas(anyLong(), anyLong(), anyLong(), any(), anyLong());
    }
    @Test void staleExpectedIllegalTargetAndMissingFieldsNeverWrite() {
        assertEquals(409, controller.unlockChapter("100", "stale", 0L, 10L).get("code"));
        assertNotEquals(200, controller.unlockChapter("100", "skip", 10L, 30L).get("code"));
        assertNotEquals(200, controller.unlockChapter("100").get("code"));
        assertNotEquals(200, controller.unlockChapter("100", "missing", null, 20L).get("code"));
        verify(states, never()).unlockChapterCas(anyLong(), anyLong(), anyLong(), any(), anyLong());
        verify(receipts, never()).insertReceiptClaim(any());
    }
    @Test void firstChapterExplicitZeroAndLastChapterCannotAdvance() {
        state.setCurrentChapterId(null);
        AjaxResult first = controller.unlockChapter("100", "first", 0L, 10L);
        assertEquals(200, first.get("code"));
        assertEquals(first, controller.unlockChapter("100", "first", 0L, 10L));
        assertEquals(first, controller.unlockChapterReceipt("100", "first"));
        state.setCurrentChapterId(30L);
        assertNotEquals(200, controller.unlockChapter("100", "last", 30L, 40L).get("code"));
        assertEquals(30L, state.getCurrentChapterId());
    }
    @Test void largeChapterIdsHaveTheSameFirstReplayAndReadReceipt() {
        ICmsTopicChapterService chapters = (ICmsTopicChapterService) ReflectionTestUtils.getField(controller, "cmsTopicChapterService");
        long expected = (long) Integer.MAX_VALUE + 10;
        long target = expected + 1;
        state.setCurrentChapterId(expected);
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(new ArrayList<>(Arrays.asList(chapter(expected), chapter(target))));
        AjaxResult first = controller.unlockChapter("100", "large", expected, target);
        assertEquals(200, first.get("code"));
        assertEquals(first, controller.unlockChapter("100", "large", expected, target));
        assertEquals(first, controller.unlockChapterReceipt("100", "large"));
        assertEquals(target, ((Map<?, ?>) first.get("data")).get("targetChapterId"));
    }
    @Test void handoverRevokesOldLeaderBeforeReplayAndStatusMustBeRunning() {
        controller.unlockChapter("100", "one", 10L, 20L);
        when(leaders.resolveLeaderMemberId(activity)).thenReturn(8L);
        assertNotEquals(200, controller.unlockChapter("100", "one", 10L, 20L).get("code"));
        when(leaders.resolveLeaderMemberId(activity)).thenReturn(9L); state.setStatus(3);
        assertEquals(200, controller.unlockChapter("100", "one", 10L, 20L).get("code"));
        assertNotEquals(200, controller.unlockChapter("100", "new", 20L, 30L).get("code"));
    }
    @Test void receiptFailureMustThrowSoTransactionRollsBack() {
        when(receipts.completeReceipt(any())).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> controller.unlockChapter("100", "one", 10L, 20L));
        // 这里只验证异常边界，不把 mock 状态当成事务回滚证据。
    }
}
