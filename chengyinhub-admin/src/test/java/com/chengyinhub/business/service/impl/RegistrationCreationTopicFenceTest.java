package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.IClubWaitlistOrderBridge;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Only the pre-claim guard/ordering boundary; successful creation remains covered by existing funds tests. */
class RegistrationCreationTopicFenceTest {
    private CmsRegistrationServiceImpl service;
    private CmsActivityMapper activities;
    private CmsTopicMapper topics;
    private IClubWaitlistOrderBridge waitlist;
    private CmsActivity original;
    private RegistrationRequest request;
    private final IllegalStateException stop = new IllegalStateException("fixture stops at current activity read");

    @BeforeEach void fixtures() {
        service = new CmsRegistrationServiceImpl(); activities = mock(CmsActivityMapper.class);
        topics = mock(CmsTopicMapper.class); waitlist = mock(IClubWaitlistOrderBridge.class);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(service, "clubWaitlistOrderBridge", waitlist);
        original = new CmsActivity(); original.setId(5L); original.setTopicId(7L); original.setMemberId(70L);
        when(activities.selectCmsActivityById(5L)).thenReturn(original);
        when(activities.selectCmsActivityByIdForUpdate(5L)).thenThrow(stop);
        CmsTopic topic = new CmsTopic(); topic.setId(7L); when(topics.selectCmsTopicByIdForUpdate(7L)).thenReturn(topic);
        request = new RegistrationRequest(); request.setOwnerType(2); request.setOwnerId(5L);
    }

    @Test void topicPrecedesWaitlistClaimWhileActivityCurrentLockKeepsTheOriginalPostClaimOrder() {
        assertSame(stop, assertThrows(IllegalStateException.class, () -> service.create(11L, request, "request-1", "a".repeat(64))));
        var order = inOrder(topics, waitlist, activities);
        order.verify(topics).selectCmsTopicByIdForUpdate(7L);
        order.verify(waitlist).claimForOrder(eq(11L), same(request), any());
        order.verify(activities).selectCmsActivityByIdForUpdate(5L);
    }

    @Test void changedActivityParentAfterClaimRejectsBeforeAnyRegistrationIsCreated() {
        CmsActivity current = new CmsActivity(); current.setId(5L); current.setTopicId(8L); current.setMemberId(70L);
        doReturn(current).when(activities).selectCmsActivityByIdForUpdate(5L);
        assertThrows(ServiceException.class, () -> service.create(11L, request, "request-1", "a".repeat(64)));
        verify(waitlist).claimForOrder(eq(11L), same(request), any());
        verify(waitlist, never()).bindRegistration(any(), anyLong());
    }

    @Test void missingOriginalActivityNeverClaimsAnOfferOrBorrowsATopicId() {
        when(activities.selectCmsActivityById(5L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.create(11L, request, "request-1", "a".repeat(64)));
        verifyNoInteractions(waitlist, topics);
    }

    @Test void standaloneActivityDoesNotAcquireAnUnrelatedEqualNumericTopic() {
        original.setTopicId(null);
        assertSame(stop, assertThrows(IllegalStateException.class, () -> service.create(11L, request, "request-1", "a".repeat(64))));
        verifyNoInteractions(topics); verify(waitlist).claimForOrder(eq(11L), same(request), any());
    }

    @Test void wrongTopicReadbackNeverClaimsAnOffer() {
        CmsTopic wrong = new CmsTopic(); wrong.setId(8L); when(topics.selectCmsTopicByIdForUpdate(7L)).thenReturn(wrong);
        assertThrows(ServiceException.class, () -> service.create(11L, request, "request-1", "a".repeat(64)));
        verifyNoInteractions(waitlist);
    }

    @Test void selfPlayKeepsItsExistingTopicOwnerPathRatherThanUsingActivityResolution() {
        request.setOwnerType(1); request.setOwnerId(7L);
        when(waitlist.claimForOrder(eq(11L), same(request), any())).thenThrow(stop);
        assertSame(stop, assertThrows(IllegalStateException.class, () -> service.create(11L, request, "request-1", "a".repeat(64))));
        verifyNoInteractions(activities, topics);
    }
}
