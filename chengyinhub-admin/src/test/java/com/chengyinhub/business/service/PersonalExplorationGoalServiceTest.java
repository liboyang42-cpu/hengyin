package com.chengyinhub.business.service;
import com.chengyinhub.business.domain.PersonalExplorationGoal;
import com.chengyinhub.business.mapper.PersonalExplorationGoalMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import org.springframework.dao.DuplicateKeyException;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class PersonalExplorationGoalServiceTest {
    PersonalExplorationGoalMapper mapper;
    PersonalExplorationGoalService service;
    long clock = Instant.parse("2026-10-12T00:00:00Z").toEpochMilli();
    final String request = "goal_original_0001";
    @BeforeEach void setup() {
        mapper = mock(PersonalExplorationGoalMapper.class);
        service = new PersonalExplorationGoalService(mapper) { @Override protected long now() { return clock; } };
        when(mapper.insert(any())).thenAnswer(call -> { ((PersonalExplorationGoal)call.getArgument(0)).setId(9L); return 1; });
    }
    PersonalExplorationGoal row() {
        PersonalExplorationGoal row = new PersonalExplorationGoal(); row.setId(9L); row.setMemberId(7L); row.setRequestId(request);
        row.setMetric("DISTINCT_ROUTES"); row.setTarget(2); row.setStartDate("2026-10-12"); row.setEndDateExclusive("2026-11-01"); row.setCreatedAtMs(clock); row.setCancelled(0); return row;
    }
    Map<String,Object> create() { return service.create(7L, request, "DISTINCT_ROUTES", 2, "2026-10-12", "2026-11-01"); }
    @Test void unauthenticatedAllOperationsNeverReachMapper() {
        assertThrows(ServiceException.class, () -> service.create(null, request, "DISTINCT_ROUTES", 2, "2026-10-12", "2026-11-01"));
        assertThrows(ServiceException.class, () -> service.list(null, null, 20));
        assertThrows(ServiceException.class, () -> service.detail(null, 9L));
        assertThrows(ServiceException.class, () -> service.cancel(null, 9L)); verifyNoInteractions(mapper);
    }
    @Test void impossibleAndExcessiveGoalsRejectedBeforeInsert() {
        assertThrows(ServiceException.class, () -> service.create(7L, request, "XP", 2, "2026-10-12", "2026-11-01"));
        assertThrows(ServiceException.class, () -> service.create(7L, request, "EXPLORATION_DAYS", 3, "2026-10-12", "2026-10-14"));
        assertThrows(ServiceException.class, () -> service.create(7L, request, "DISTINCT_ROUTES", 101, "2026-10-12", "2026-11-01"));
        assertThrows(ServiceException.class, () -> service.create(7L, request, "DISTINCT_ROUTES", 2, "2026-02-30", "2026-11-01")); verify(mapper, never()).insert(any());
    }
    @Test void pastStartAndLongHorizonAreRejected() {
        assertThrows(ServiceException.class, () -> service.create(7L, request, "DISTINCT_ROUTES", 2, "2026-10-11", "2026-11-01"));
        assertThrows(ServiceException.class, () -> service.create(7L, request, "DISTINCT_ROUTES", 2, "2027-09-01", "2027-11-01")); verify(mapper, never()).insert(any());
    }
    @Test void midnightUsesShanghaiRatherThanUtcToday() {
        clock = Instant.parse("2026-10-12T16:00:00Z").toEpochMilli();
        assertThrows(ServiceException.class, this::create);
        assertEquals("ACTIVE", service.create(7L, request, "DISTINCT_ROUTES", 1, "2026-10-13", "2026-10-14").get("state"));
    }
    @Test void completionQueryUsesHalfOpenShanghaiBoundsAndServerTime() {
        create(); verify(mapper).progress(7L, "DISTINCT_ROUTES", "2026-10-12 00:00:00", "2026-11-01 00:00:00", "2026-10-12 08:00:00.000");
    }
    @Test void missingFactsReturnZeroWithoutInventedBadgeOrRouteData() {
        Map<String,Object> result = create(); assertEquals(0L, result.get("progress")); assertEquals(false, result.get("privateCommemoration"));
        assertFalse(result.containsKey("title")); assertFalse(result.containsKey("routeIds")); assertFalse(result.containsKey("memberId"));
    }
    @Test void achievementIsPrivateCurrentAuthoritativeCount() {
        when(mapper.byId(7L, 9L)).thenReturn(row()); when(mapper.progress(anyLong(), anyString(), anyString(), anyString(), anyString())).thenReturn(2L, 1L);
        assertEquals("ACHIEVED", service.detail(7L, 9L).get("state")); assertEquals("ACTIVE", service.detail(7L, 9L).get("state"));
        verify(mapper, never()).insert(any());
    }
    @Test void replayAfterWindowEndsUsesSameGoalWithoutCreatingAgain() {
        when(mapper.byRequest(7L, request)).thenReturn(row()); clock = Instant.parse("2026-12-01T00:00:00Z").toEpochMilli();
        assertEquals("ENDED", create().get("state")); verify(mapper, never()).insert(any());
    }
    @Test void originalRequestCannotChangeAnyBoundContent() {
        when(mapper.byRequest(7L, request)).thenReturn(row());
        assertThrows(ServiceException.class, () -> service.create(7L, request, "EXPLORATION_DAYS", 2, "2026-10-12", "2026-11-01"));
        assertThrows(ServiceException.class, () -> service.create(7L, request, "DISTINCT_ROUTES", 3, "2026-10-12", "2026-11-01"));
        assertThrows(ServiceException.class, () -> service.create(7L, request, "DISTINCT_ROUTES", 2, "2026-10-13", "2026-11-01"));
        assertThrows(ServiceException.class, () -> service.create(7L, request, "DISTINCT_ROUTES", 2, "2026-10-12", "2026-11-02"));
        verify(mapper, never()).insert(any());
    }
    @Test void concurrentDuplicateRecoversWinner() {
        when(mapper.byRequest(7L, request)).thenReturn(null, row()); doThrow(new DuplicateKeyException("race")).when(mapper).insert(any());
        assertEquals("9", create().get("id")); verify(mapper, times(2)).byRequest(7L, request);
    }
    @Test void lostDuplicateWinnerAndUnconfirmedInsertAreNotSuccess() {
        doThrow(new DuplicateKeyException("race")).when(mapper).insert(any()); assertThrows(DuplicateKeyException.class, this::create);
        doReturn(0).when(mapper).insert(any()); assertThrows(IllegalStateException.class, this::create);
    }
    @Test void cancellationIsOwnerScopedAndCannotResurrectViaReplay() {
        PersonalExplorationGoal row = row(); when(mapper.byId(7L, 9L)).thenReturn(row);
        when(mapper.cancel(7L, 9L)).thenAnswer(call -> { row.setCancelled(1); return 1; });
        assertEquals("CANCELLED", service.cancel(7L, 9L).get("state")); when(mapper.byRequest(7L, request)).thenReturn(row);
        assertEquals("CANCELLED", create().get("state")); verify(mapper, never()).insert(any());
    }
    @Test void otherOwnersCannotReadOrCancel() {
        assertThrows(ServiceException.class, () -> service.detail(8L, 9L)); assertThrows(ServiceException.class, () -> service.cancel(8L, 9L));
        verify(mapper, never()).cancel(anyLong(), anyLong());
    }
    @Test void cancelledGoalNeverHasCommemorationEvenWithEnoughFacts() {
        PersonalExplorationGoal row = row(); row.setCancelled(1); when(mapper.byId(7L, 9L)).thenReturn(row);
        when(mapper.progress(anyLong(), anyString(), anyString(), anyString(), anyString())).thenReturn(20L);
        assertEquals(false, service.detail(7L, 9L).get("privateCommemoration"));
    }
    @Test void paginationHasStableStringIdsAndLimitPlusOne() {
        PersonalExplorationGoal row = row(); row.setId(9007199254740999L);
        when(mapper.page(7L, null, 2)).thenReturn(Arrays.asList(row, row()));
        Map<String,Object> page = service.list(7L, null, 1); assertEquals(true, page.get("hasMore")); assertEquals("9007199254740999", page.get("nextBeforeId"));
    }
    @Test void receiptMissingIsUncertainAndInvalidPageDoesNotQuery() {
        assertEquals(Integer.valueOf(404), assertThrows(ServiceException.class, () -> service.receipt(7L, request)).getCode());
        assertThrows(ServiceException.class, () -> service.list(7L, 0L, 20)); assertThrows(ServiceException.class, () -> service.list(7L, null, 51));
        verify(mapper, never()).page(anyLong(), any(), anyInt());
    }
}
