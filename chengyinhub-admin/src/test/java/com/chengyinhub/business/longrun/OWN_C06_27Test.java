package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.support.TopicRouteSessionScope;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. New current lookup is opt-in; legacy default lookup keeps its contract. */
class OWN_C06_27Test {
    private TopicRouteSessionMapper mapper;
    private TopicRouteSession original,current;
    @BeforeEach void setup() {
        mapper=mock(TopicRouteSessionMapper.class);original=row(30L,7L,20L,0L);current=row(31L,7L,20L,0L);
        when(mapper.selectByOwner(0L,20L,7L,"default")).thenReturn(original);
        when(mapper.selectCurrentByOwner(0L,20L,7L)).thenReturn(current);
    }
    private TopicRouteSession row(long id,long member,long topic,Long activity) {
        TopicRouteSession row=new TopicRouteSession();row.setId(id);row.setMemberId(member);
        row.setTopicId(topic);row.setActivityId(activity);row.setStatus(TopicRouteSession.STATUS_RUNNING);return row;
    }
    @Test void originalFindStillReturnsDefaultWhileOptInReturnsCurrent() {
        assertSame(original,TopicRouteSessionScope.find(mapper,7L,20L,0L));
        assertSame(current,TopicRouteSessionScope.findCurrent(mapper,7L,20L,0L));
    }
    @Test void currentMissingUsesOnlyOriginalDefaultFallback() {
        when(mapper.selectCurrentByOwner(0L,20L,7L)).thenReturn(null);
        assertSame(original,TopicRouteSessionScope.findCurrent(mapper,7L,20L,0L));
        when(mapper.selectByOwner(0L,20L,7L,"default")).thenReturn(null);
        assertNull(TopicRouteSessionScope.findCurrent(mapper,7L,20L,0L));
    }
    @Test void endedLatestRemainsLatestAndDoesNotFallBackToRunningOriginal() {
        current.setStatus(TopicRouteSession.STATUS_ENDED);
        assertSame(current,TopicRouteSessionScope.findCurrent(mapper,7L,20L,0L));
        verify(mapper,never()).selectByOwner(anyLong(),anyLong(),anyLong(),anyString());
    }
    @Test void omittedActivityResolvesUniqueDefaultScopeThenItsCurrent() {
        when(mapper.selectMemberTopicSessions(7L,20L)).thenReturn(Collections.singletonList(original));
        assertSame(original,TopicRouteSessionScope.find(mapper,7L,20L,null));
        assertSame(current,TopicRouteSessionScope.findCurrent(mapper,7L,20L,null));
    }
    @Test void omittedActivityEmptyAndMultipleScopesKeepOriginalBehavior() {
        when(mapper.selectMemberTopicSessions(7L,20L)).thenReturn(null);
        assertNull(TopicRouteSessionScope.findCurrent(mapper,7L,20L,null));
        when(mapper.selectMemberTopicSessions(7L,20L)).thenReturn(Collections.emptyList());
        assertNull(TopicRouteSessionScope.findCurrent(mapper,7L,20L,null));
        when(mapper.selectMemberTopicSessions(7L,20L)).thenReturn(Arrays.asList(original,row(32L,7L,20L,8L)));
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->TopicRouteSessionScope.findCurrent(mapper,7L,20L,null));
        assertEquals("存在多个场次，请指定 activityId",error.getMessage());
        verify(mapper,never()).selectCurrentByOwner(anyLong(),anyLong(),anyLong());
    }
    @Test void currentOwnerTopicAndActivityMismatchRemainErrors() {
        for(TopicRouteSession wrong:Arrays.asList(row(40L,8L,20L,0L),row(40L,7L,21L,0L),row(40L,7L,20L,8L))) {
            when(mapper.selectCurrentByOwner(0L,20L,7L)).thenReturn(wrong);
            assertThrows(ServiceException.class,()->TopicRouteSessionScope.findCurrent(mapper,7L,20L,0L));
        }
    }
    @Test void invalidParametersAndForeignLegacyScopeAreRejectedBeforeCurrentLookup() {
        assertThrows(IllegalArgumentException.class,()->TopicRouteSessionScope.findCurrent(mapper,null,20L,0L));
        assertThrows(IllegalArgumentException.class,()->TopicRouteSessionScope.findCurrent(mapper,0L,20L,0L));
        assertThrows(IllegalArgumentException.class,()->TopicRouteSessionScope.findCurrent(mapper,7L,null,0L));
        assertThrows(IllegalArgumentException.class,()->TopicRouteSessionScope.findCurrent(mapper,7L,0L,0L));
        assertThrows(IllegalArgumentException.class,()->TopicRouteSessionScope.findCurrent(mapper,7L,20L,-1L));
        when(mapper.selectMemberTopicSessions(7L,20L)).thenReturn(Collections.singletonList(row(40L,8L,20L,0L)));
        assertThrows(ServiceException.class,()->TopicRouteSessionScope.findCurrent(mapper,7L,20L,null));
        verify(mapper,never()).selectCurrentByOwner(anyLong(),anyLong(),anyLong());
    }
}
