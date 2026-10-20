package com.chengyinhub.business.community;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.domain.dto.CommunityPostRequests;
import com.chengyinhub.business.domain.dto.SafetyDecision;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.service.CommunityTrustService;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICommunityMediaReceiptService;
import com.chengyinhub.business.service.IContentSafetyGate;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.impl.CommunityPostServiceImpl;
import com.chengyinhub.business.service.support.CommunityPostLifecyclePolicy;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审核分级 P2:community_post.audit_level / audit_before_publish / review_flag 的写入口径,
 * 以及图片异步机审命中风险时复用帖文生命周期 HIDE(带乐观版本)。
 */
class CommunityPostAuditLevelTest {
    private static final String IMAGE = "https://cdn.example/community/image.jpg";

    private CommunityPostMapper mapper;
    private IContentSafetyGate safetyGate;
    private IAuditTaskService auditTaskService;
    private IWxContentSecurityService wx;
    private CommunityPostServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mapper = mock(CommunityPostMapper.class);
        safetyGate = mock(IContentSafetyGate.class);
        auditTaskService = mock(IAuditTaskService.class);
        ICommunityMediaReceiptService receipts = mock(ICommunityMediaReceiptService.class);
        service = new CommunityPostServiceImpl(mapper, new CommunityPostLifecyclePolicy(), safetyGate,
                auditTaskService, mock(CommunityTrustService.class), receipts);
        wx = mock(IWxContentSecurityService.class);
        ObjectProvider<IWxContentSecurityService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(wx);
        service.setWxContentSecurityProvider(provider);

        when(mapper.countActiveMemberEnforcement(anyLong(), anyList(), any())).thenReturn(0);
        when(mapper.countRecentPosts(anyLong(), any())).thenReturn(0);
        when(mapper.selectCommandReceipt(anyLong(), anyString())).thenReturn(null);
        when(mapper.insertCommandReceipt(anyLong(), anyString(), anyString(), anyLong(), anyString(), any()))
                .thenReturn(1);
        when(mapper.insertOutbox(anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.insertTypedOutbox(anyString(), anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.countMemberNotificationDeliverable(anyLong(), anyLong())).thenReturn(1);
        when(mapper.countNotificationDeliverable(anyLong(), anyLong(), anyLong(), any())).thenReturn(1);
        when(mapper.selectActiveGuideline()).thenReturn(Collections.<String, Object>singletonMap("ID", 5L));
        when(mapper.countGuidelineAck(9L, 5L)).thenReturn(1);
        when(mapper.selectReferences(7L)).thenReturn(Collections.emptyList());
        when(mapper.selectMedia(7L)).thenReturn(Collections.emptyList());
        when(mapper.selectById(7L, 9L)).thenReturn(post("PUBLISHED", 4));
        when(mapper.transitionCas(anyLong(), anyString(), anyString(), anyInt(), any(), any(),
                anyInt(), anyInt(), anyInt(), any())).thenReturn(1);
    }

    @Test
    void machinePassPublishWritesAuditLevelOne() {
        publishWith(SafetyDecision.pass(), Collections.<Map<String, Object>>emptyList());

        verify(mapper).updateAuditMarks(eq(7L), eq(1), isNull(), isNull(), any());
    }

    @Test
    void flaggedPassPublishGoesToSpotCheckPool() {
        publishWith(SafetyDecision.passFlagged("TEXT_REVIEW"), Collections.<Map<String, Object>>emptyList());

        verify(mapper).updateAuditMarks(eq(7L), eq(1), isNull(), eq(1), any());
    }

    @Test
    void heldPublishWritesNoAuditLevelUntilHumanApproval() {
        publishWith(SafetyDecision.hold("TEXT_REVIEW", 31L), Collections.<Map<String, Object>>emptyList());

        verify(mapper, never()).updateAuditMarks(anyLong(), any(), any(), any(), any());
    }

    @Test
    void directPublishWithImagesSubmitsThemForAsyncCheckAsCommunityPost() {
        Map<String, Object> media = new HashMap<>();
        media.put("derived_object_key", IMAGE);

        publishWith(SafetyDecision.pass(), Collections.singletonList(media));

        verify(wx).submitMediaCheckOne(IWxContentSecurityService.BIZ_TYPE_COMMUNITY_POST, 7L, IMAGE, "openid-9");
    }

    @Test
    void humanApprovalOfHeldPostMarksHumanLevelAndReviewedBeforePublish() {
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post("CHECKING", 4));

        service.applyAuditDecision(7L, true);

        verify(mapper).updateAuditMarks(eq(7L), eq(2), eq(1), isNull(), any());
    }

    @Test
    void humanApprovalOfHiddenPostIsHumanLevelButNotReviewedBeforePublish() {
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post("HIDDEN", 4));

        service.applyAuditDecision(7L, true);

        verify(mapper).updateAuditMarks(eq(7L), eq(2), isNull(), isNull(), any());
    }

    @Test
    void humanRejectionWritesNoAuditLevel() {
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post("CHECKING", 4));

        service.applyAuditDecision(7L, false);

        verify(mapper, never()).updateAuditMarks(anyLong(), any(), any(), any(), any());
    }

    @Test
    void riskyImageHidesPublishedPostWithExpectedVersionAndQueuesHumanReview() {
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post("PUBLISHED", 4));
        when(auditTaskService.submitSafetyHold(eq(AuditTask.BIZ_COMMUNITY_POST), eq(7L), eq(9L), eq("HIGH"), anyString()))
                .thenReturn(55L);

        assertTrue(service.applyRiskyMediaResult(7L));

        verify(mapper).transitionCas(eq(7L), eq("PUBLISHED"), eq("HIDDEN"), eq(4), isNull(), isNull(),
                eq(0), eq(0), eq(0), any());
        verify(mapper).upsertSearchDocument(eq(7L), eq("HIDDEN"), any());
        verify(auditTaskService).submitSafetyHold(eq(AuditTask.BIZ_COMMUNITY_POST), eq(7L), eq(9L), eq("HIGH"), anyString());
        verify(mapper).insertOutbox(anyString(), eq(7L), eq("POST_MEDIA_RISKY_HIDDEN"), anyString(), any());
    }

    @Test
    void riskyImageHidesLimitedPostToo() {
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post("LIMITED", 6));

        assertTrue(service.applyRiskyMediaResult(7L));

        verify(mapper).transitionCas(eq(7L), eq("LIMITED"), eq("HIDDEN"), eq(6), isNull(), isNull(),
                eq(0), eq(0), eq(0), any());
    }

    @Test
    void riskyImageOnPostAwaitingHumanReviewChangesNothing() {
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post("CHECKING", 4));

        assertTrue(service.applyRiskyMediaResult(7L));

        verify(mapper, never()).transitionCas(anyLong(), anyString(), anyString(), anyInt(), any(), any(),
                anyInt(), anyInt(), anyInt(), any());
        verify(auditTaskService, never()).submitSafetyHold(anyString(), anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void riskyImageLosingTheVersionRaceIsRejectedForRetry() {
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post("PUBLISHED", 4));
        when(mapper.transitionCas(eq(7L), eq("PUBLISHED"), eq("HIDDEN"), eq(4), any(), any(),
                anyInt(), anyInt(), anyInt(), any())).thenReturn(0);

        ServiceException error = assertThrows(ServiceException.class, () -> service.applyRiskyMediaResult(7L));

        assertEquals(Integer.valueOf(409), error.getCode());
        verify(auditTaskService, never()).submitSafetyHold(anyString(), anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void activeMediaUrlsAreTheDerivedKeysSubmittedForCheck() {
        Map<String, Object> media = new HashMap<>();
        media.put("derived_object_key", IMAGE);
        when(mapper.selectMediaForSafety(7L)).thenReturn(Collections.singletonList(media));

        assertEquals(Collections.singletonList(IMAGE), service.activeMediaUrls(7L));
    }

    private void publishWith(SafetyDecision decision, java.util.List<Map<String, Object>> media) {
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post("DRAFT", 3));
        when(mapper.selectMediaForSafety(7L)).thenReturn(media);
        when(mapper.selectMemberWxOpenId(9L)).thenReturn("openid-9");
        when(safetyGate.decide(any())).thenReturn(decision);
        CommunityPostRequests.State state = new CommunityPostRequests.State();
        state.setExpectedVersion(3);
        state.setRequestId("publish-request-1");
        service.publish(9L, null, 7L, state);
    }

    private static CommunityPost post(String lifecycle, int version) {
        CommunityPost post = new CommunityPost();
        post.setId(7L);
        post.setAuthorId(9L);
        post.setBody("正文");
        post.setAudience("PUBLIC");
        post.setCommentPolicy("EVERYONE");
        post.setLocationPrecision("NONE");
        post.setLifecycle(lifecycle);
        post.setInteractionEnabled(1);
        post.setGuidelineVersionId(5L);
        post.setVersion(version);
        return post;
    }
}
