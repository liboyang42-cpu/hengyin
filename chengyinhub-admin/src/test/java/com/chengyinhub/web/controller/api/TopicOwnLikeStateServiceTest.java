package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsTopicLikeMapper;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.impl.CmsTopicLikeServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TopicOwnLikeStateServiceTest {
    private final CmsTopicLikeMapper mapper=mock(CmsTopicLikeMapper.class);
    private final ICmsTopicService topics=mock(ICmsTopicService.class);
    private final CmsTopicLikeServiceImpl service=new CmsTopicLikeServiceImpl();
    private final List<long[]> rows=new ArrayList<>();
    private boolean visible=true;
    private Runnable afterDelete=()->{};
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service,"cmsTopicLikeMapper",mapper);
        ReflectionTestUtils.setField(service,"cmsTopicService",topics);
        when(topics.selectCmsTopicDetailById(anyLong(),anyLong())).thenAnswer(call->{
            if(!visible)return null;
            CmsTopic topic=new CmsTopic(); topic.setId(call.getArgument(0)); return topic;
        });
        when(mapper.countOwnTopicLikes(anyLong(),anyLong())).thenAnswer(call->{
            long member=call.getArgument(0),topic=call.getArgument(1);
            return rows.stream().filter(row->row[0]==member&&row[1]==topic).count();
        });
        when(mapper.deleteOwnTopicLikes(anyLong(),anyLong())).thenAnswer(call->{
            long member=call.getArgument(0),topic=call.getArgument(1); int old=rows.size();
            rows.removeIf(row->row[0]==member&&row[1]==topic); int deleted=old-rows.size();
            afterDelete.run(); return deleted;
        });
    }
    @Test void ownStateCountsAnyLegacyLikeTypeWithoutChangingRows() {
        rows.add(new long[]{7,41,0}); rows.add(new long[]{7,41,1});
        assertTrue(service.readOwnTopicLikeState(7L,41L)); assertEquals(2,rows.size());
        verify(mapper,never()).deleteOwnTopicLikes(anyLong(),anyLong());
    }
    @Test void otherAccountOrTopicDoesNotBecomeOwnState() {
        rows.add(new long[]{8,41,1}); rows.add(new long[]{7,42,1});
        assertFalse(service.readOwnTopicLikeState(7L,41L)); assertEquals(2,rows.size());
    }
    @Test void cancelRemovesAllExactPairDuplicatesOnly() {
        rows.add(new long[]{7,41,0}); rows.add(new long[]{7,41,1});
        long[] otherMember={8,41,1},otherTopic={7,42,1};rows.add(otherMember);rows.add(otherTopic);
        assertFalse(service.cancelOwnTopicLike(7L,41L)); assertEquals(2,rows.size());
        assertSame(otherMember,rows.get(0));assertSame(otherTopic,rows.get(1));
        verify(mapper).deleteOwnTopicLikes(7L,41L);
    }
    @Test void repeatedCancellationNeverReInsertsAbsentState() {
        rows.add(new long[]{7,41,1});
        assertFalse(service.cancelOwnTopicLike(7L,41L));assertFalse(service.cancelOwnTopicLike(7L,41L));
        assertTrue(rows.isEmpty());verify(mapper,times(2)).deleteOwnTopicLikes(7L,41L);
        verify(mapper,never()).insertCmsTopicLike(any());
    }
    @Test void concurrentLegacyReAddIsReturnedTrueRatherThanForcedFalse() {
        rows.add(new long[]{7,41,1});afterDelete=()->rows.add(new long[]{7,41,0});
        assertTrue(service.cancelOwnTopicLike(7L,41L));assertEquals(1,rows.size());
        verify(mapper,times(1)).deleteOwnTopicLikes(7L,41L);
    }
    @Test void invalidOwnerOrTopicNeverReachesVisibilityOrMapper() {
        for(Long bad:new Long[]{null,0L,-1L}) {
            assertThrows(IllegalArgumentException.class,()->service.readOwnTopicLikeState(bad,41L));
            assertThrows(IllegalArgumentException.class,()->service.cancelOwnTopicLike(bad,41L));
            assertThrows(IllegalArgumentException.class,()->service.readOwnTopicLikeState(7L,bad));
            assertThrows(IllegalArgumentException.class,()->service.cancelOwnTopicLike(7L,bad));
        }
        verifyNoInteractions(topics,mapper);
    }
    @Test void inaccessibleTopicDoesNotReadPrivateLikeOrDelete() {
        visible=false;rows.add(new long[]{7,41,1});
        assertThrows(ServiceException.class,()->service.readOwnTopicLikeState(7L,41L));
        assertThrows(ServiceException.class,()->service.cancelOwnTopicLike(7L,41L));
        assertEquals(1,rows.size());verifyNoInteractions(mapper);
    }
    @Test void mismatchedVisibilityTargetCannotAuthorizeAnotherTopic() {
        CmsTopic wrong=new CmsTopic();wrong.setId(42L);
        when(topics.selectCmsTopicDetailById(41L,7L)).thenReturn(wrong);
        assertThrows(ServiceException.class,()->service.cancelOwnTopicLike(7L,41L));verifyNoInteractions(mapper);
    }
    @Test void lostAccessAfterReadDoesNotReturnTheLikeBit() {
        when(mapper.countOwnTopicLikes(7L,41L)).thenAnswer(call->{visible=false;return 1L;});
        assertThrows(ServiceException.class,()->service.readOwnTopicLikeState(7L,41L));
        verify(topics,times(2)).selectCmsTopicDetailById(41L,7L);
    }
    @Test void lostAccessAfterDeleteReturnsNoFabricatedSuccessfulState() {
        rows.add(new long[]{7,41,1});afterDelete=()->visible=false;
        assertThrows(ServiceException.class,()->service.cancelOwnTopicLike(7L,41L));
        assertTrue(rows.isEmpty());verify(mapper,never()).countOwnTopicLikes(anyLong(),anyLong());
    }
    @Test void observationFailureIsNotConvertedToFalseOrRetried() {
        when(mapper.countOwnTopicLikes(7L,41L)).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class,()->service.cancelOwnTopicLike(7L,41L));
        verify(mapper,times(1)).deleteOwnTopicLikes(7L,41L);verify(mapper,times(1)).countOwnTopicLikes(7L,41L);
    }
    @Test void negativeCountCannotBecomeFalseSuccess() {
        when(mapper.countOwnTopicLikes(7L,41L)).thenReturn(-1L);
        assertThrows(IllegalStateException.class,()->service.readOwnTopicLikeState(7L,41L));
    }
}
