package com.chengyinhub.business.pointsgov;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.domain.CmsPointsSetting;
import com.chengyinhub.business.domain.UmsComment;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.UmsCommentMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.CommentPublicationService;
import com.chengyinhub.business.service.ContentInteractionGuard;
import com.chengyinhub.business.service.ICmsPointsSettingService;
import com.chengyinhub.business.service.IUmsCommentService;
import com.chengyinhub.business.service.IUmsMemberPointsDetailService;
import com.chengyinhub.business.service.IUmsMemberService;

/** R7: the first-review reward (event 4) asks governance before crediting; a refusal still publishes the review. */
class CommentRewardGovernanceWiringTest {

    private final IUmsCommentService comments = mock(IUmsCommentService.class);
    private final IUmsMemberService members = mock(IUmsMemberService.class);
    private final IUmsMemberPointsDetailService details = mock(IUmsMemberPointsDetailService.class);
    private final UmsCommentMapper facts = mock(UmsCommentMapper.class);
    private final PointsGovernanceService governance = mock(PointsGovernanceService.class);
    private CommentPublicationService service;

    @BeforeEach
    void setUp() {
        ICmsPointsSettingService settings = mock(ICmsPointsSettingService.class);
        CmsPointsSetting rule = new CmsPointsSetting();
        rule.setValue(new BigDecimal("8"));
        rule.setStatus(1L);
        when(settings.selectCmsPointsSettingById(4L)).thenReturn(rule);
        UmsMember member = new UmsMember();
        member.setId(7L);
        member.setPoint(100L);
        when(members.selectUmsMemberById(7L)).thenReturn(member);
        when(members.addPoints(7L, 8)).thenReturn(true);
        when(details.insertUmsMemberPointsDetail(any())).thenReturn(1);
        when(comments.insertUmsComment(any())).thenReturn(1);
        when(comments.selectUmsCommentList(any())).thenReturn(List.of(new UmsComment()));
        when(facts.reviewTargetExists(1L, 42L)).thenReturn(true);
        when(facts.hasPaidOrVerifiedRegistration(1L, 42L, 7L)).thenReturn(true);
        service = new CommentPublicationService(mock(ContentInteractionGuard.class), comments, members, settings, details,
                mock(UmsMemberMapper.class), facts);
        ReflectionTestUtils.setField(service, "governance", governance);
    }

    private UmsComment review() {
        UmsComment c = new UmsComment();
        c.setOwnerType(1L); c.setOwnerId(42L); c.setMemberId(7L); c.setRating(5L); c.setReplyId(0L); c.setContents("好玩");
        return c;
    }

    @Test
    void refusedRewardStillPublishesButCreditsNothing() {
        when(governance.admit(7L, 4L, 8)).thenReturn(new PointsGovernanceService.Admission(false, "DAILY_CAP", "COMMUNITY"));
        assertNull(service.publish(review()));
        verify(comments).insertUmsComment(any());
        verify(members, never()).addPoints(anyLong(), anyInt());
        verify(details, never()).insertUmsMemberPointsDetail(any());
    }

    @Test
    void admittedRewardIsCredited() {
        when(governance.admit(7L, 4L, 8)).thenReturn(new PointsGovernanceService.Admission(true, "ADMITTED", "COMMUNITY"));
        assertNull(service.publish(review()));
        verify(members).addPoints(7L, 8);
        verify(details).insertUmsMemberPointsDetail(any());
    }
}
