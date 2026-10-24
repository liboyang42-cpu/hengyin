package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Focused claim preconditions. Real first-claim/amount/stock/points behavior remains in the unchanged funds IT. */
class RegistrationPaymentClaimFenceTest {
    private CmsRegistrationServiceImpl service;
    private CmsRegistrationMapper registrations;
    private CmsTopicMapper topics;
    private WechatVerifiedPaymentFactService facts;
    private CmsRegistration observed, current;

    @BeforeEach void fixture() {
        service = new CmsRegistrationServiceImpl(); registrations = mock(CmsRegistrationMapper.class);
        topics = mock(CmsTopicMapper.class); facts = mock(WechatVerifiedPaymentFactService.class);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", mock(CmsActivityMapper.class));
        ReflectionTestUtils.setField(service, "verifiedPaymentFacts", facts);
        observed = original(); current = original();
        when(registrations.selectCmsRegistrationById(1L)).thenReturn(observed);
        when(registrations.selectCmsRegistrationByIdForUpdate(1L)).thenReturn(current);
        CmsTopic topic = new CmsTopic(); topic.setId(7L);
        when(topics.selectCmsTopicByIdForUpdate(7L)).thenReturn(topic);
    }

    @Test void committedSuccessIsCheckedUnderTopicAndOriginalOrderBeforeAnyClaim() {
        when(facts.hasConfirmedExternalSuccess("REG1")).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> service.beginPaymentAttempt(1L, 11L));

        var order = inOrder(topics, registrations, facts);
        order.verify(topics).selectCmsTopicByIdForUpdate(7L);
        order.verify(registrations).selectCmsRegistrationByIdForUpdate(1L);
        order.verify(facts).hasConfirmedExternalSuccess("REG1");
        verify(registrations, never()).claimPaymentProcessing(anyLong(), anyLong(), any());
        verify(registrations, never()).closeExpired(anyLong(), anyString(), anyInt());
        assertEquals(0, current.getPaymentStatus());
    }

    @Test void changedCurrentMemberIsRejectedBeforeFactReadOrClaim() {
        current.setMemberId(22L);

        assertThrows(RuntimeException.class, () -> service.beginPaymentAttempt(1L, 11L));

        verifyNoInteractions(facts);
        verify(registrations, never()).claimPaymentProcessing(anyLong(), anyLong(), any());
    }

    @Test void currentPaidStateCannotReuseAnOlderPendingSnapshotToClaimAgain() {
        current.setPaymentStatus(2); current.setRegistrationStatus(2);

        assertThrows(RuntimeException.class, () -> service.beginPaymentAttempt(1L, 11L));

        verify(facts).hasConfirmedExternalSuccess("REG1");
        verify(registrations, never()).claimPaymentProcessing(anyLong(), anyLong(), any());
        assertEquals(2, current.getPaymentStatus());
        assertEquals(0, observed.getPaymentStatus());
    }

    private CmsRegistration original() {
        CmsRegistration row = new CmsRegistration(); row.setId(1L); row.setRegistrationNo("REG1");
        row.setOwnerType(1L); row.setOwnerId(7L); row.setMemberId(11L);
        row.setPaymentStatus(0); row.setRegistrationStatus(1); return row;
    }
}
