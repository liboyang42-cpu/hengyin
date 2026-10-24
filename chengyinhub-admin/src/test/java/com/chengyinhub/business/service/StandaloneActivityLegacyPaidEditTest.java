package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.dto.ActivityTicketRequest;
import com.chengyinhub.business.domain.dto.CmsActivityPublishRequest;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.CmsActivityServiceImpl;
import com.chengyinhub.business.service.impl.MemberIdentityService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR10 复审:非平台账号的存量收费独立活动,编辑时收款闸只拦「扩大收费面」的改动
 * (新增收费票 / 0 元改收费 / 涨价),纯文字等改动必须能保存;平台账号不受影响。
 *
 * <p>负控:把编辑口径换回全量 assertStandalonePaidTicketsCollectible ⇒ textOnlyEdit 用例红。
 */
class StandaloneActivityLegacyPaidEditTest {

    private static final long OWNER = 9L;
    private static final long PLATFORM = 1L;
    private static final long ACTIVITY = 300L;
    private static final long PAID_TICKET = 77L;
    private static final long FREE_TICKET = 78L;
    private static final Date START = new Date(1_800_000_000_000L);
    private static final Date END = new Date(1_800_003_600_000L);

    private CmsActivityMapper activityMapper;
    private OmsTicketMapper ticketMapper;
    private CmsActivityServiceImpl service;

    @BeforeEach
    void setUp() {
        activityMapper = mock(CmsActivityMapper.class);
        ticketMapper = mock(OmsTicketMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        service = new CmsActivityServiceImpl();
        ReflectionTestUtils.setField(service, "memberIdentityService", mock(MemberIdentityService.class));
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "platformMemberId", PLATFORM);

        CmsActivity a = new CmsActivity();
        a.setId(ACTIVITY);
        a.setMemberId(OWNER);
        a.setDelFlag(0);
        a.setStartDate(START);
        a.setEndDate(END);
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(a);
        when(activityMapper.updateCmsActivityByOwner(ArgumentMatchers.any())).thenReturn(1);
        when(ticketMapper.selectOmsTicketList(ArgumentMatchers.any()))
                .thenReturn(Arrays.asList(stored(PAID_TICKET, "199.00"), stored(FREE_TICKET, "0")));
        when(registrationMapper.selectOpenSaleTicketIdsByActivity(ACTIVITY)).thenReturn(Collections.emptyList());
    }

    private OmsTicket stored(long id, String price) {
        OmsTicket t = new OmsTicket();
        t.setId(id);
        t.setOwnerType(2L);
        t.setOwnerId(ACTIVITY);
        t.setDelFlag(0);
        t.setName("票" + id);
        t.setPrice(new BigDecimal(price));
        t.setStartTime(START);
        t.setEndTime(END);
        t.setTotalInventory(10L);
        t.setRemainingInventory(10L);
        return t;
    }

    private ActivityTicketRequest req(Long id, String price) {
        ActivityTicketRequest r = new ActivityTicketRequest();
        r.setId(id);
        r.setName("票" + id);
        r.setPrice(new BigDecimal(price));
        r.setStartTime(START);
        r.setEndTime(END);
        r.setTotalStock(10);
        return r;
    }

    private CmsActivityPublishRequest request(ActivityTicketRequest... tickets) {
        CmsActivityPublishRequest r = new CmsActivityPublishRequest();
        r.setId(ACTIVITY);
        r.setName("改过的活动名");
        r.setDescription("改过的简介");
        r.setStartDate(START);
        r.setEndDate(END);
        r.setTickets(Arrays.asList(tickets));
        return r;
    }

    private void assertRejected(CmsActivityPublishRequest request) {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.updateActivityByOwner(request, OWNER, "owner", false));
        assertTrue(e.getMessage().contains("票价只能是 0"), e.getMessage());
        verify(ticketMapper, never()).updateOmsTicket(any(OmsTicket.class));
        verify(ticketMapper, never()).insertOmsTicket(any(OmsTicket.class));
        verify(activityMapper, never()).updateCmsActivityByOwner(any());
    }

    @Test
    void legacyPaid_textOnlyEdit_passes() {
        assertEquals(1, service.updateActivityByOwner(
                request(req(PAID_TICKET, "199.00"), req(FREE_TICKET, "0")), OWNER, "owner", false));
        verify(activityMapper).updateCmsActivityByOwner(any());
    }

    @Test
    void legacyPaid_lowerPrice_passes() {
        assertEquals(1, service.updateActivityByOwner(
                request(req(PAID_TICKET, "149.00"), req(FREE_TICKET, "0")), OWNER, "owner", false));
    }

    @Test
    void legacyPaid_addNewPaidTicket_rejected() {
        assertRejected(request(req(PAID_TICKET, "199.00"), req(FREE_TICKET, "0"), req(null, "50.00")));
    }

    @Test
    void legacyPaid_raisePrice_rejected() {
        assertRejected(request(req(PAID_TICKET, "299.00"), req(FREE_TICKET, "0")));
    }

    @Test
    void legacyPaid_freeTicketTurnedPaid_rejected() {
        assertRejected(request(req(PAID_TICKET, "199.00"), req(FREE_TICKET, "10.00")));
    }

    @Test
    void platformMember_unaffected() {
        ReflectionTestUtils.setField(service, "platformMemberId", OWNER);
        assertEquals(1, service.updateActivityByOwner(
                request(req(PAID_TICKET, "299.00"), req(FREE_TICKET, "10.00"), req(null, "50.00")),
                OWNER, "owner", false));
    }
}
