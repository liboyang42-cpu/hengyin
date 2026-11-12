package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.SeatRecipientFact;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IRegistrationSeatRecipientEligibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 已领取内含席位进入原活动/自玩读口；财务行仍归买家。 */
class PlayCompletionFactsSeatRecipientTest {

    private PlayCompletionFacts facts;
    private ICmsRegistrationService registrations;
    private IRegistrationSeatRecipientEligibility seats;

    @BeforeEach
    void setup() {
        facts = new PlayCompletionFacts();
        registrations = mock(ICmsRegistrationService.class);
        seats = mock(IRegistrationSeatRecipientEligibility.class);
        ReflectionTestUtils.setField(facts, "cmsRegistrationService", registrations);
        ReflectionTestUtils.setField(facts, "seatRecipientEligibility", seats);
        when(registrations.selectCmsRegistrationList(any())).thenReturn(List.of());
    }

    @Test
    void claimedSeatMakesActivityRegistrationTrueWithoutBuyerRow() {
        when(seats.playFacts(eq(99L), eq(2L), eq(61L))).thenReturn(List.of(fact(31L, 2, 99L)));
        assertTrue(facts.isRegistered(99L, 61L));
        verify(registrations).selectCmsRegistrationList(any());
    }

    @Test
    void pendingOrOtherSeatDoesNotMakeRegistrationTrue() {
        when(seats.playFacts(eq(99L), eq(2L), eq(61L))).thenReturn(List.of());
        assertFalse(facts.isRegistered(99L, 61L));
    }

    @Test
    void claimedSeatReturnsOriginalSelfPlayRowForReading() {
        CmsRegistration row = new CmsRegistration();
        row.setId(31L); row.setOwnerType(1L); row.setOwnerId(41L); row.setMemberId(51L);
        row.setRegistrationStatus(2); row.setPaymentStatus(2);
        row.setExpiresAt(new Date(System.currentTimeMillis() + 86_400_000L));
        when(seats.playFacts(eq(99L), eq(1L), eq(41L))).thenReturn(List.of(fact(31L, 2, 99L)));
        when(registrations.selectCmsRegistrationById(31L)).thenReturn(row);

        CmsRegistration pass = facts.validSelfPlayPass(99L, 41L);
        assertSame(row, pass);
        assertEquals(Long.valueOf(51L), pass.getMemberId());
        assertEquals(Integer.valueOf(2), pass.getPaymentStatus());
    }

    private SeatRecipientFact fact(Long registrationId, Integer seatNo, Long invitee) {
        SeatRecipientFact fact = new SeatRecipientFact();
        fact.setInvitationId(900L);
        fact.setRegistrationId(registrationId);
        fact.setSeatNo(seatNo);
        fact.setInviteeMemberId(invitee);
        return fact;
    }
}
