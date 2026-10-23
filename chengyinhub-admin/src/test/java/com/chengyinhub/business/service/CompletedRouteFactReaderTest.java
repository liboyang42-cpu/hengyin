package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.CompletedRouteFactMapper;
import com.chengyinhub.business.service.support.CompletedRouteFactReader;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** G:测试真实 reader 的来源边界；SQL 最新局与主体隔离由专属 MySQL IT 验证。 */
class CompletedRouteFactReaderTest {
    @Test
    void missingOrAnonymousMemberDoesNotBecomeAnUnscopedQuery() {
        CompletedRouteFactMapper mapper = mock(CompletedRouteFactMapper.class);
        CompletedRouteFactReader reader = new CompletedRouteFactReader(mapper);

        assertThrows(IllegalArgumentException.class, () -> reader.findCurrentCompletedRoutes(null));
        assertThrows(IllegalArgumentException.class, () -> reader.findCurrentCompletedRoutes(0L));
        assertThrows(IllegalArgumentException.class, () -> reader.findCurrentCompletedRoutes(-1L));
        verifyNoInteractions(mapper);
    }

    @Test
    void routeOnlyFactsAreDiscoveredInOneQueryWithFrozenIdentityIntact() {
        CompletedRouteFactMapper mapper = mock(CompletedRouteFactMapper.class);
        CompletedRouteFactReader reader = new CompletedRouteFactReader(mapper);
        TopicRouteSession activity = fact(41L, 501L, 301L, "default", 8L);
        TopicRouteSession self = fact(42L, 0L, 302L, "replay:30", 9L);
        when(mapper.selectCurrentCompletedRoutes(7L)).thenReturn(List.of(activity, self));

        List<TopicRouteSession> facts = reader.findCurrentCompletedRoutes(7L);

        assertEquals(List.of(41L, 42L), facts.stream().map(TopicRouteSession::getId).toList());
        assertEquals("replay:30", facts.get(1).getPlayInstanceId());
        assertEquals(9L, facts.get(1).getConfigVersion().longValue());
        assertEquals(new Date(1_000_000L), facts.get(1).getCompletedAt());
        verify(mapper, times(1)).selectCurrentCompletedRoutes(7L);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void sourceDifferencePreservesActivityAndTopicInsteadOfFlatteningByTopic() {
        CompletedRouteFactMapper mapper = mock(CompletedRouteFactMapper.class);
        CompletedRouteFactReader reader = new CompletedRouteFactReader(mapper);
        when(mapper.selectCurrentCompletedRoutes(7L)).thenReturn(List.of(
                fact(41L, 501L, 301L, "default", 8L),
                fact(42L, 0L, 302L, "default", 9L),
                fact(43L, 502L, 302L, "default", 9L)));
        Set<String> progressAndTicketCandidates = Set.of("501:301");

        Set<String> routeOnly = reader.findCurrentCompletedRoutes(7L).stream()
                .map(row -> row.getActivityId() + ":" + row.getTopicId())
                .filter(scope -> !progressAndTicketCandidates.contains(scope))
                .collect(Collectors.toSet());

        assertEquals(Set.of("0:302", "502:302"), routeOnly,
                "自玩与同主题另一场次均是来源差集，不得合成一个虚构活动");
        verify(mapper, times(1)).selectCurrentCompletedRoutes(7L);
        verifyNoMoreInteractions(mapper);
    }

    private TopicRouteSession fact(long id, long activity, long topic, String instance, long version) {
        TopicRouteSession row = new TopicRouteSession();
        row.setId(id);
        row.setMemberId(7L);
        row.setActivityId(activity);
        row.setTopicId(topic);
        row.setPlayInstanceId(instance);
        row.setConfigVersion(version);
        row.setStatus(TopicRouteSession.STATUS_COMPLETED);
        row.setCompletedAt(new Date(1_000_000L));
        return row;
    }
}
