package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.UmsComment;
import com.chengyinhub.business.mapper.UmsCommentMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommentPublicationServicePoiTest {
    private final UmsCommentMapper facts = mock(UmsCommentMapper.class);
    private final IUmsCommentService comments = mock(IUmsCommentService.class);
    private final IUmsMemberPointsDetailService points = mock(IUmsMemberPointsDetailService.class);

    private CommentPublicationService service() {
        return new CommentPublicationService(mock(ContentInteractionGuard.class), comments,
                mock(IUmsMemberService.class), mock(ICmsPointsSettingService.class), points,
                mock(UmsMemberMapper.class), facts);
    }
    private UmsComment review() {
        UmsComment c = new UmsComment(); c.setOwnerType(4L); c.setOwnerId(42L); c.setMemberId(7L);
        c.setRating(4L); c.setReplyId(0L); c.setContents("测试评价"); return c;
    }

    @Test void requiresAnOpenPoiAndACompletedVisit() {
        assertEquals("据点未开放或已下线", service().publish(review()));
        when(facts.reviewTargetExists(4L,42L)).thenReturn(true);
        assertEquals("完成据点挑战后才可评价", service().publish(review()));
        verifyNoInteractions(comments, points);
    }
    @Test void requiresExplicitRatingAndRejectsDuplicateReview() {
        when(facts.reviewTargetExists(4L,42L)).thenReturn(true);
        when(facts.hasCompletedPoi(42L,7L)).thenReturn(true);
        UmsComment c = review(); c.setRating(0L);
        assertEquals("请选择1至5星评分", service().publish(c));
        when(facts.countPoiReviews(42L,7L)).thenReturn(1);
        assertEquals("你已评价过这个据点", service().publish(review()));
        verifyNoInteractions(comments, points);
    }
    @Test void publishesCompletedPlayersReviewWithoutPayingForPraise() {
        when(facts.reviewTargetExists(4L,42L)).thenReturn(true);
        when(facts.hasCompletedPoi(42L,7L)).thenReturn(true);
        when(comments.insertUmsComment(any())).thenReturn(1);
        UmsComment c = review(); assertNull(service().publish(c));
        assertEquals(1,c.getStatus()); assertEquals(0,c.getDelFlag());
        verify(comments).insertUmsComment(c); verifyNoInteractions(points);
    }

    @Test void rejectsEmptyOrOversizedReviewsBeforeWriting() {
        UmsComment c = review(); c.setContents(" ");
        assertEquals("评价内容须为1至500字", service().publish(c));
        c.setContents(new String(new char[501]).replace('\0', 'a'));
        assertEquals("评价内容须为1至500字", service().publish(c));
        verifyNoInteractions(comments, points);
    }
}
