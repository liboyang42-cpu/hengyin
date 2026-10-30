package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.domain.UmsMemberFollow;
import com.chengyinhub.business.mapper.UmsMemberFollowMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 帖文卡头像「+」关注(dw29 帖文第三轮):俱乐部帖文流给出查看者对作者的关注态。 */
class ClubPostFollowProjectionTest {

    private static ClubPost post(long id, long author) {
        ClubPost p = new ClubPost();
        p.setId(id);
        p.setAuthorMemberId(author);
        return p;
    }

    @Test
    void marksFollowedAuthorsAndSelfAsFollowed() {
        ApiClubController controller = new ApiClubController();
        UmsMemberFollowMapper mapper = mock(UmsMemberFollowMapper.class);
        UmsMemberFollow follow = new UmsMemberFollow();
        follow.setFollowMemberId(7L);
        when(mapper.selectUmsMemberFollowsByMemberAndFollowMemberIds(eq(1L), anyList()))
                .thenReturn(Collections.singletonList(follow));
        ReflectionTestUtils.setField(controller, "umsMemberFollowMapper", mapper);

        ClubPost followed = post(10L, 7L), stranger = post(11L, 8L), mine = post(12L, 1L), again = post(13L, 7L);
        List<ClubPost> posts = Arrays.asList(followed, stranger, mine, again);
        ReflectionTestUtils.invokeMethod(controller, "markViewerFollows", posts, 1L);

        assertEquals(Integer.valueOf(1), followed.getIsFollowTheUser());
        assertEquals(Integer.valueOf(0), stranger.getIsFollowTheUser());
        assertEquals(Integer.valueOf(1), mine.getIsFollowTheUser(), "自己的帖子不出「+」");
        assertEquals(Integer.valueOf(1), again.getIsFollowTheUser());
        // 作者去重、不查自己:一次批量查询
        verify(mapper).selectUmsMemberFollowsByMemberAndFollowMemberIds(1L, Arrays.asList(7L, 8L));
    }

    @Test
    void guestViewerLeavesFollowStateUnset() {
        ApiClubController controller = new ApiClubController();
        UmsMemberFollowMapper mapper = mock(UmsMemberFollowMapper.class);
        ReflectionTestUtils.setField(controller, "umsMemberFollowMapper", mapper);
        ClubPost p = post(10L, 7L);
        ReflectionTestUtils.invokeMethod(controller, "markViewerFollows", Collections.singletonList(p), 0L);
        assertNull(p.getIsFollowTheUser());
        verify(mapper, never()).selectUmsMemberFollowsByMemberAndFollowMemberIds(org.mockito.ArgumentMatchers.anyLong(), anyList());
    }
}
