package com.chengyinhub.business.service;

import com.chengyinhub.business.collection.CollectionModelService;
import com.chengyinhub.business.collection.mapper.CollectionModelMapper;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.moneyswitch.MoneySwitchService;
import com.chengyinhub.business.moneyswitch.mapper.MoneySwitchMapper;
import com.chengyinhub.business.payout.EcommerceSellerResolver;
import com.chengyinhub.business.payout.PayoutSwitches;
import com.chengyinhub.business.payout.SellerOfRecordPolicy.Facts;
import com.chengyinhub.business.payout.SellerOfRecordPolicy.ProductKind;
import com.chengyinhub.business.payout.SellerOfRecordPolicy.SellerType;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.exception.BusinessRefusal;
import com.chengyinhub.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S2 collection model on the real order-creation path (same fixture as {@link ErqingGateTest}): with the money ceiling on,
 * a paid ticket of a club that has no bound sub-merchant is refused before the order row or stock is written (the refusal
 * the 2026-11-02 change removed); with the ceiling off the same order is created exactly as today.
 */
class CollectionModelOrderGateTest {
    private static final Long TOPIC = 700L;
    private static final Long ACTIVITY = 800L;
    private static final Long TICKET = 9L;
    private static final Long MEMBER = 1L;

    private CmsTopicMapper topicMapper;
    private CmsActivityMapper activityMapper;
    private OmsTicketMapper ticketMapper;
    private CmsRegistrationMapper registrationMapper;
    private EcommerceSellerResolver sellers;
    private CmsRegistrationServiceImpl service;

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        activityMapper = mock(CmsActivityMapper.class);
        ticketMapper = mock(OmsTicketMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        registrationMapper = mock(CmsRegistrationMapper.class);
        IProductRoleService productRoleService = mock(IProductRoleService.class);
        sellers = mock(EcommerceSellerResolver.class);

        service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "platformMemberId", 100000L);
        ReflectionTestUtils.setField(service, "growthService", mock(IGrowthService.class));
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "umsMemberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "productRoleService", productRoleService);
        when(productRoleService.isPlatformOwned(anyLong())).thenReturn(false);

        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setName("俱乐部主题");
        t.setProductType(ProductType.ORIENTEERING);
        t.setLifecycle(3);
        t.setSellingStartedAt(new Date(System.currentTimeMillis() - 60_000L));
        t.setSaleEndAt(new Date(System.currentTimeMillis() + 86_400_000L));
        t.setStatus(1);
        t.setUserStatus(1);
        t.setDelFlag(0);
        t.setFeePlatformRate(new BigDecimal("0.0300"));
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(t);

        CmsActivity a = new CmsActivity();
        a.setId(ACTIVITY);
        a.setName("俱乐部活动");
        a.setTopicId(TOPIC);
        a.setStatus(1L);
        a.setPublishStatus(1L);
        a.setDelFlag(0);
        a.setEndDate(new Date(System.currentTimeMillis() + 86400_000L));
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(a);

        OmsTicket ticket = new OmsTicket();
        ticket.setId(TICKET);
        ticket.setName("单人票");
        ticket.setOwnerId(ACTIVITY);
        ticket.setOwnerType(2L);
        ticket.setPrice(new BigDecimal("100.00"));
        ticket.setDelFlag(0);
        ticket.setMode(1);
        ticket.setRemainingInventory(100L);
        ticket.setStartTime(new Date(System.currentTimeMillis() + 3600_000L));
        ticket.setEndTime(new Date(System.currentTimeMillis() + 86400_000L));
        when(ticketMapper.selectOmsTicketById(TICKET)).thenReturn(ticket);

        UmsMember m = new UmsMember();
        m.setId(MEMBER);
        m.setPoint(0L);
        m.setStatus(1L);
        m.setDelFlag(0);
        when(memberMapper.selectUmsMemberById(MEMBER)).thenReturn(m);
        when(registrationMapper.insertCmsRegistration(any(CmsRegistration.class))).thenReturn(1);
        when(ticketMapper.deductInventory(TICKET, 1L)).thenReturn(1);

        // A club seller whose sub-merchant application is still under review.
        when(sellers.factsFor(2L, ACTIVITY)).thenReturn(
                new Facts(ProductKind.ACTIVITY_TICKET, false, SellerType.CLUB, 3L, "AUDITING", null));
    }

    /** {@code on}: the S2 model flag (W7 MERCHANT-3: its own flag, no longer the money ceiling). */
    private void model(boolean on) {
        PayoutSwitches rails = new PayoutSwitches(true, true, true, "1900000001", "wx_mini", "", 30, false, "none");
        CollectionModelService model = new CollectionModelService(new MoneySwitchService(mock(MoneySwitchMapper.class), false),
                rails, sellers, activityMapper, mock(ISettlementRuleService.class), mock(CollectionModelMapper.class),
                "SPLIT", "SPLIT", "SPLIT");
        model.setModelEnabled(on);
        ReflectionTestUtils.setField(service, "collectionModel", model);
    }

    @Test
    void ceilingOnRefusesTheUnboundClubBeforeAnyWrite() {
        model(true);
        BusinessRefusal refusal = assertThrows(BusinessRefusal.class, () ->
                service.createRegistration(MEMBER, 2, ACTIVITY, TICKET, 0, "张三", "13800000000", null, null));
        assertEquals(ErrorCode.SELLER_NOT_BOUND, refusal.getErrorCode());
        verify(registrationMapper, never()).insertCmsRegistration(any());
        verify(ticketMapper, never()).deductInventory(anyLong(), anyLong());
    }

    @Test
    void ceilingOffCreatesTheOrderExactlyAsToday() {
        model(false);
        service.createRegistration(MEMBER, 2, ACTIVITY, TICKET, 0, "张三", "13800000000", null, null);
        verify(registrationMapper).insertCmsRegistration(any(CmsRegistration.class));
        verify(sellers, never()).factsFor(any(), any());
    }

    @Test
    void freeTicketIsNeverRefused() {
        model(true);
        when(ticketMapper.selectOmsTicketById(TICKET)).thenAnswer(call -> {
            OmsTicket free = new OmsTicket();
            free.setId(TICKET);
            free.setName("免费票");
            free.setOwnerId(ACTIVITY);
            free.setOwnerType(2L);
            free.setPrice(BigDecimal.ZERO);
            free.setDelFlag(0);
            free.setMode(1);
            free.setRemainingInventory(100L);
            free.setStartTime(new Date(System.currentTimeMillis() + 3600_000L));
            free.setEndTime(new Date(System.currentTimeMillis() + 86400_000L));
            return free;
        });
        service.createRegistration(MEMBER, 2, ACTIVITY, TICKET, 0, "张三", "13800000000", null, null);
        verify(registrationMapper).insertCmsRegistration(any(CmsRegistration.class));
    }
}
