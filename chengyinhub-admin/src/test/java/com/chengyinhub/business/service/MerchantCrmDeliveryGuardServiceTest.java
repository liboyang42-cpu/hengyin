package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MerchantCrmDeliveryGuard;
import com.chengyinhub.business.mapper.MerchantCrmCampaignMapper;
import com.chengyinhub.business.service.impl.MerchantCrmDeliveryGuardService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantCrmDeliveryGuardServiceTest {
    private static final long MERCHANT = 77L;
    private static final long MEMBER = 41L;
    private MerchantCrmCampaignMapper mapper;
    private MerchantCrmDeliveryGuardService service;

    @BeforeEach
    void setUp() {
        mapper = mock(MerchantCrmCampaignMapper.class);
        service = new MerchantCrmDeliveryGuardService();
        ReflectionTestUtils.setField(service, "campaignMapper", mapper);
    }

    @Test
    void anotherCampaignOwningDailyGuardFailsClosedWithoutReclaim() {
        MerchantCrmDeliveryGuard existing = guard(18L, 19L, MerchantCrmDeliveryGuard.RESERVED, 3L);
        when(mapper.insertDeliveryGuard(any())).thenReturn(0);
        when(mapper.selectDeliveryGuardForUpdate(MERCHANT, MEMBER, "IN_APP")).thenReturn(existing);

        MerchantCrmDeliveryGuardService.Claim claim = service.claim(MERCHANT, 8L, 9L, MEMBER, "IN_APP");

        assertEquals(MerchantCrmDeliveryGuardService.Outcome.DAILY_CAP, claim.getOutcome());
        verify(mapper, never()).reclaimReservedDeliveryGuard(any(), any(), anyString(), any());
    }

    @Test
    void expiredReservationForSameRecipientUsesVersionCas() {
        MerchantCrmDeliveryGuard existing = guard(8L, 9L, MerchantCrmDeliveryGuard.RESERVED, 3L);
        when(mapper.insertDeliveryGuard(any())).thenReturn(0);
        when(mapper.selectDeliveryGuardForUpdate(MERCHANT, MEMBER, "IN_APP")).thenReturn(existing);
        when(mapper.reclaimReservedDeliveryGuard(any(), any(), anyString(), any())).thenReturn(1);

        MerchantCrmDeliveryGuardService.Claim claim = service.claim(MERCHANT, 8L, 9L, MEMBER, "IN_APP");

        assertEquals(MerchantCrmDeliveryGuardService.Outcome.ACQUIRED, claim.getOutcome());
        assertEquals(4L, claim.getVersion());
        verify(mapper).reclaimReservedDeliveryGuard(org.mockito.ArgumentMatchers.eq(101L),
                org.mockito.ArgumentMatchers.eq(3L), anyString(), org.mockito.ArgumentMatchers.eq(120));
    }

    @Test
    void staleDispatchBecomesUnknownAndCannotBeSentAgain() {
        MerchantCrmDeliveryGuard existing = guard(8L, 9L, MerchantCrmDeliveryGuard.DISPATCHING, 4L);
        when(mapper.insertDeliveryGuard(any())).thenReturn(0);
        when(mapper.selectDeliveryGuardForUpdate(MERCHANT, MEMBER, "IN_APP")).thenReturn(existing);
        when(mapper.markStaleDeliveryGuardOutcomeUnknown(101L, 4L, "DELIVERY_OUTCOME_UNKNOWN"))
                .thenReturn(1);

        MerchantCrmDeliveryGuardService.Claim claim = service.claim(MERCHANT, 8L, 9L, MEMBER, "IN_APP");

        assertEquals(MerchantCrmDeliveryGuardService.Outcome.UNCERTAIN, claim.getOutcome());
    }

    @Test
    void deliveredGuardFromPreviousDayRepairsReceiptInsteadOfCreatingNewSend() {
        MerchantCrmDeliveryGuard previous = guard(8L, 9L, MerchantCrmDeliveryGuard.DELIVERED, 2L);
        previous.setMessageReceiptId(88L);
        when(mapper.insertDeliveryGuard(any())).thenReturn(0);
        when(mapper.selectDeliveryGuardForUpdate(MERCHANT, MEMBER, "IN_APP")).thenReturn(null);
        when(mapper.selectDeliveryGuardByRecipientForUpdate(MERCHANT, 8L, 9L)).thenReturn(previous);

        MerchantCrmDeliveryGuardService.Claim claim = service.claim(MERCHANT, 8L, 9L, MEMBER, "IN_APP");

        assertEquals(MerchantCrmDeliveryGuardService.Outcome.DELIVERED, claim.getOutcome());
        assertEquals(88L, claim.getMessageReceiptId());
    }

    @Test
    void recipientFallbackFailsClosedWhenPersistedMemberOrChannelDiffers() {
        when(mapper.insertDeliveryGuard(any())).thenReturn(0);
        when(mapper.selectDeliveryGuardForUpdate(MERCHANT, MEMBER, "IN_APP")).thenReturn(null);
        MerchantCrmDeliveryGuard wrongMember = guard(8L, 9L, MerchantCrmDeliveryGuard.DELIVERED, 2L);
        wrongMember.setMemberId(42L);
        when(mapper.selectDeliveryGuardByRecipientForUpdate(MERCHANT, 8L, 9L)).thenReturn(wrongMember);

        ServiceException memberError = assertThrows(ServiceException.class,
                () -> service.claim(MERCHANT, 8L, 9L, MEMBER, "IN_APP"));
        assertEquals(409, memberError.getCode());

        MerchantCrmDeliveryGuard wrongChannel = guard(8L, 9L, MerchantCrmDeliveryGuard.DELIVERED, 2L);
        wrongChannel.setChannel("COUPON");
        when(mapper.selectDeliveryGuardByRecipientForUpdate(MERCHANT, 8L, 9L)).thenReturn(wrongChannel);
        ServiceException channelError = assertThrows(ServiceException.class,
                () -> service.claim(MERCHANT, 8L, 9L, MEMBER, "IN_APP"));
        assertEquals(409, channelError.getCode());

        verify(mapper, never()).reclaimReservedDeliveryGuard(any(), any(), anyString(), any());
        verify(mapper, never()).markStaleDeliveryGuardOutcomeUnknown(any(), any(), anyString());
    }

    @Test
    void deliveredGuardReceiptJoinsChannelTransactionInsteadOfCommittingAheadOfIt() throws Exception {
        Transactional annotation = MerchantCrmDeliveryGuardService.class
                .getMethod("markDelivered", MerchantCrmDeliveryGuardService.Claim.class, Long.class, Long.class)
                .getAnnotation(Transactional.class);

        assertEquals(Propagation.REQUIRED, annotation.propagation());
    }

    private MerchantCrmDeliveryGuard guard(Long campaignId, Long recipientId, String status, Long version) {
        MerchantCrmDeliveryGuard row = new MerchantCrmDeliveryGuard();
        row.setId(101L);
        row.setMerchantId(MERCHANT);
        row.setMemberId(MEMBER);
        row.setChannel("IN_APP");
        row.setCampaignId(campaignId);
        row.setRecipientId(recipientId);
        row.setStatus(status);
        row.setVersion(version);
        return row;
    }
}
