package com.chengyinhub.business.community;

import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.domain.community.CommunityPostFeedQuery;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.CommunityPostServiceImpl;
import com.chengyinhub.business.service.support.CommunityPostLifecyclePolicy;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommunityPostPersonalReadsTest {
    private CommunityPostMapper mapper;
    private CommunityPostServiceImpl service;
    @BeforeEach void setup() {
        mapper = mock(CommunityPostMapper.class);
        service = new CommunityPostServiceImpl(mapper, new CommunityPostLifecyclePolicy(),
                mock(IContentSafetyGate.class), mock(IAuditTaskService.class),
                mock(CommunityTrustService.class), mock(ICommunityMediaReceiptService.class));
    }
    private CommunityPost post(long id) {
        CommunityPost p = new CommunityPost(); p.setId(id); p.setAuthorId(8L);
        p.setLifecycle("PUBLISHED"); p.setCommentPolicy("EVERYONE"); return p;
    }
    @Test void followingStateUsesRealRelationAndAnonymousIsFalse() {
        CommunityPost p = post(7); p.setAuthorId(11L);
        when(mapper.selectById(7L,8L)).thenReturn(p);
        when(mapper.selectById(7L,0L)).thenReturn(p);
        when(mapper.countFollow(8L,11L)).thenReturn(1);
        assertEquals(true,service.detail(8L,7L).get("viewerFollowing"));
        assertEquals(false,service.detail(0L,7L).get("viewerFollowing"));
        verify(mapper,never()).countFollow(0L,11L);
    }
    @Test void onlyOwnerReceivesRealTopicCodesForRoundTripEditing() {
        CommunityPost p = post(7);
        when(mapper.selectById(7L,8L)).thenReturn(p);
        when(mapper.selectById(7L,0L)).thenReturn(p);
        when(mapper.selectTopicCodes(7L)).thenReturn(Arrays.asList("CITY", "WALK"));
        assertEquals(Arrays.asList("CITY", "WALK"),service.detail(8L,7L).get("topicCodes"));
        assertEquals(Collections.emptyList(),service.detail(0L,7L).get("topicCodes"));
        verify(mapper,times(1)).selectTopicCodes(7L);
    }
    @Test void personalListsRejectAnonymousBeforeMapper() {
        assertThrows(ServiceException.class, () -> service.bookmarks(0L, null, 20));
        assertThrows(ServiceException.class, () -> service.ownPosts(null, null, 20));
        verifyNoInteractions(mapper);
    }
    @Test void bookmarkPageProbesOneExtraAndRetainsHydratedBundles() {
        when(mapper.selectBookmarkedFeed(any())).thenReturn(Arrays.asList(post(30), post(20), post(10)));
        Map<String,Object> result = service.bookmarks(8L, 40L, 2);
        assertEquals(true, result.get("hasMore")); assertEquals(20L, result.get("nextCursor"));
        List<?> items = (List<?>) result.get("items"); assertEquals(2, items.size());
        assertTrue(((Map<?,?>) items.get(0)).containsKey("media"));
        assertTrue(((Map<?,?>) items.get(0)).containsKey("references"));
        ArgumentCaptor<CommunityPostFeedQuery> q = ArgumentCaptor.forClass(CommunityPostFeedQuery.class);
        verify(mapper).selectBookmarkedFeed(q.capture());
        assertEquals(8L, q.getValue().getViewerId()); assertEquals(40L,q.getValue().getCursor());
        assertEquals(3,q.getValue().getLimit());
        verify(mapper,never()).selectMedia(10L);
    }
    @Test void ownPostsEmptyPageHasNoCursorAndUsesBoundedLimit() {
        Map<String,Object> result = service.ownPosts(8L,null,500);
        assertEquals(false,result.get("hasMore")); assertNull(result.get("nextCursor"));
        assertEquals(0,((List<?>)result.get("items")).size());
        ArgumentCaptor<CommunityPostFeedQuery> q = ArgumentCaptor.forClass(CommunityPostFeedQuery.class);
        verify(mapper).selectOwnPosts(q.capture()); assertEquals(8L,q.getValue().getViewerId());
        assertEquals(51,q.getValue().getLimit());
    }
    private Map<String,Object> comment(long id, Long parent, Long root) {
        Map<String,Object> c = new HashMap<>(); c.put("id",id); c.put("parent_id",parent); c.put("root_id",root); return c;
    }
    @Test void commentsLimitCountsRootsAndKeepsTheirWholeReplyGroups() {
        when(mapper.selectById(7L,8L)).thenReturn(post(7));
        when(mapper.selectComments(7L,8L,null,3)).thenReturn(Arrays.asList(
                comment(30,null,null),comment(31,30L,30L),comment(32,31L,30L),
                comment(20,null,null),comment(21,20L,20L),comment(10,null,null),comment(11,10L,10L)));
        Map<String,Object> result=service.comments(8L,7L,null,2);
        assertEquals(true,result.get("hasMore")); assertEquals(20L,result.get("nextCursor"));
        List<?> rows=(List<?>)result.get("items"); assertEquals(5,rows.size());
        assertEquals(21L,((Map<?,?>)rows.get(4)).get("id"));
        verify(mapper).selectComments(7L,8L,null,3);
    }
    @Test void commentsRepliesAloneDoNotInventAnotherRootPage() {
        when(mapper.selectById(7L,8L)).thenReturn(post(7));
        when(mapper.selectComments(7L,8L,null,2)).thenReturn(Arrays.asList(comment(30,null,null),comment(31,30L,30L)));
        Map<String,Object> result=service.comments(8L,7L,null,1);
        assertEquals(false,result.get("hasMore")); assertEquals(30L,result.get("nextCursor"));
        assertEquals(2,((List<?>)result.get("items")).size());
    }
}
