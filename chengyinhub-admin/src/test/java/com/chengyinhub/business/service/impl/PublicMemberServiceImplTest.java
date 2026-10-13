package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.UmsMemberFollow;
import com.chengyinhub.business.domain.vo.PublicMemberVO;
import com.chengyinhub.business.mapper.PublicMemberMapper;
import com.chengyinhub.business.service.ICmsActivityLikeService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicLikeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IUmsCreativeSquareLikeService;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.business.service.IUmsMemberFollowService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicMemberServiceImplTest {

    @Test
    void userTypeZeroKeepsSwaggerAllMembersContract() {
        PublicMemberMapper mapper = mock(PublicMemberMapper.class);
        when(mapper.selectPublicMemberList(null, "搜索词"))
                .thenReturn(Collections.emptyList());

        PublicMemberServiceImpl service = new PublicMemberServiceImpl();
        ReflectionTestUtils.setField(service, "publicMemberMapper", mapper);

        assertTrue(service.selectPublicMemberList("0", "搜索词").isEmpty());
        verify(mapper).selectPublicMemberList(null, "搜索词");
    }

    @Test
    void blankNicknameUsesStableNonPiiPublicFallback() {
        PublicMemberMapper mapper = mock(PublicMemberMapper.class);
        PublicMemberVO member = new PublicMemberVO();
        member.setId(42L);
        member.setNickname("   ");
        when(mapper.selectInvitedPublicMemberList("7"))
                .thenReturn(Collections.singletonList(member));

        PublicMemberServiceImpl service = new PublicMemberServiceImpl();
        ReflectionTestUtils.setField(service, "publicMemberMapper", mapper);

        assertEquals("用户42",
                service.selectInvitedPublicMemberList(7L).get(0).getNickname());
    }

    @Test
    void anonymousDetailNeverQueriesViewerFollowRelation() {
        PublicMemberMapper mapper = mock(PublicMemberMapper.class);
        PublicMemberVO member = new PublicMemberVO();
        member.setId(7L);
        member.setNickname("阿岚");
        when(mapper.selectPublicMemberById(7L)).thenReturn(member);

        IUmsMemberFollowService follows = mock(IUmsMemberFollowService.class);
        PublicMemberServiceImpl service = new PublicMemberServiceImpl();
        ReflectionTestUtils.setField(service, "publicMemberMapper", mapper);
        ReflectionTestUtils.setField(service, "umsMemberFollowService", follows);
        ReflectionTestUtils.setField(service, "cmsTopicService", mock(ICmsTopicService.class));
        ReflectionTestUtils.setField(service, "cmsActivityService", mock(ICmsActivityService.class));
        ReflectionTestUtils.setField(service, "cmsActivityLikeService", mock(ICmsActivityLikeService.class));
        ReflectionTestUtils.setField(service, "cmsTopicLikeService", mock(ICmsTopicLikeService.class));
        ReflectionTestUtils.setField(service, "umsCreativeSquareLikeService", mock(IUmsCreativeSquareLikeService.class));
        ReflectionTestUtils.setField(service, "umsMemberBalanceDetailService", mock(IUmsMemberBalanceDetailService.class));

        PublicMemberVO result = service.selectPublicMemberById(7L);

        assertEquals(7L, (long) result.getId());
        assertNull(result.getIsFollow(), "匿名读不该带观看者关系字段");
        verify(follows, never()).selectUmsMemberFollowList(any(UmsMemberFollow.class));
    }

    @Test
    void anonymousDetailRejectsMissingOrInvalidMemberId() {
        PublicMemberMapper mapper = mock(PublicMemberMapper.class);
        PublicMemberServiceImpl service = new PublicMemberServiceImpl();
        ReflectionTestUtils.setField(service, "publicMemberMapper", mapper);

        assertNull(service.selectPublicMemberById(null));
        assertNull(service.selectPublicMemberById(0L));
        verify(mapper, never()).selectPublicMemberById(anyLong());
    }
}
