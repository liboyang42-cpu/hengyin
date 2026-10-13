package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubWaitlistClaim;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.RegistrationQuoteResult;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClubWaitlistQuoteWiringTest {
    private static final Long MEMBER = 41L;
    private static final Long ACTIVITY = 31L;
    private static final Long TICKET = 51L;

    @Test
    void offeredHoldCanQuoteSoldOutTicketWithoutClaimOrSecondDeduction() {
        Fixture fixture = fixture(61L);

        RegistrationQuoteResult quote = fixture.service.quoteRegistrationForOffer(MEMBER, request(61L));

        assertEquals(0, new BigDecimal("80.00").compareTo(quote.getPayAmount()));
        verify(fixture.bridge).validateForQuote(eq(MEMBER), any(RegistrationRequest.class), any(Date.class));
    }

    @Test
    void offerIdentityIsBoundIntoQuoteSignatureAndOrdinaryQuoteRemainsSoldOut() {
        Fixture first = fixture(61L);
        Fixture second = fixture(62L);

        String firstSign = first.service.quoteRegistrationForOffer(MEMBER, request(61L)).getQuoteSign();
        String secondSign = second.service.quoteRegistrationForOffer(MEMBER, request(62L)).getQuoteSign();

        assertNotEquals(firstSign, secondSign);
        assertThrows(RuntimeException.class, () -> first.service.quoteRegistration(
                MEMBER, 2, ACTIVITY, TICKET, null, 0));
    }

    private Fixture fixture(Long offerId) {
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        OmsTicketMapper ticketMapper = mock(OmsTicketMapper.class);
        IClubWaitlistOrderBridge bridge = mock(IClubWaitlistOrderBridge.class);

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setStatus(1L);
        activity.setPublishStatus(1L);
        activity.setDelFlag(0);
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(activity);

        UmsMember member = new UmsMember();
        member.setId(MEMBER);
        member.setStatus(1L);
        member.setDelFlag(0);
        member.setPoint(0L);
        when(memberMapper.selectUmsMemberById(MEMBER)).thenReturn(member);

        OmsTicket ticket = new OmsTicket();
        ticket.setId(TICKET);
        ticket.setOwnerId(ACTIVITY);
        ticket.setOwnerType(2L);
        ticket.setDelFlag(0);
        ticket.setRemainingInventory(0L);
        ticket.setPrice(new BigDecimal("80.00"));
        when(ticketMapper.selectOmsTicketById(TICKET)).thenReturn(ticket);
        when(bridge.validateForQuote(eq(MEMBER), any(RegistrationRequest.class), any(Date.class)))
                .thenReturn(new ClubWaitlistClaim(offerId, MEMBER, ACTIVITY, TICKET,
                        new Date(System.currentTimeMillis() + 60_000L)));

        CmsRegistrationServiceImpl service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "umsMemberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "clubWaitlistOrderBridge", bridge);
        return new Fixture(service, bridge);
    }

    private RegistrationRequest request(Long offerId) {
        RegistrationRequest request = new RegistrationRequest();
        request.setOwnerType(2);
        request.setOwnerId(ACTIVITY);
        request.setTicketId(TICKET);
        request.setIsUsePoint(0);
        request.setWaitlistOfferId(offerId);
        request.setWaitlistOfferToken("opaque-offer-token");
        return request;
    }

    private static final class Fixture {
        private final CmsRegistrationServiceImpl service;
        private final IClubWaitlistOrderBridge bridge;

        private Fixture(CmsRegistrationServiceImpl service, IClubWaitlistOrderBridge bridge) {
            this.service = service;
            this.bridge = bridge;
        }
    }
}
