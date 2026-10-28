package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsRegistrationScanMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.business.service.impl.RegistrationCheckoutServiceImpl;
import com.chengyinhub.business.service.support.CommittedBusinessNotice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Date;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验收 SC-FREE-CHECKOUT(F-L4-01 / F-W2-01 / F-L4-09)。
 *
 * <ul>
 *   <li>用户发布的独立付费活动:重新拉起支付也在任何微信预下单之前以「暂不支持在线支付」拒绝;</li>
 *   <li>平台发布的独立付费活动照常报价;</li>
 *   <li>「报名成功」站内信只在支付成功(含零元单确认)之后发,重复确认不再发。</li>
 * </ul>
 * 建单/报价拒绝已由 ErqingGateTest 覆盖,这里不重复。
 */
class StandaloneCheckoutSignupNoticeAcceptanceTest {

    private static final Long ACTIVITY = 800L;
    private static final Long TICKET = 9L;
    private static final Long MEMBER = 1L;
    private static final Long REG = 6001L;
    private static final Long PLATFORM = 100000L;

    private CmsActivityMapper activityMapper;
    private OmsTicketMapper ticketMapper;
    private UmsMemberMapper memberMapper;
    private CmsRegistrationMapper registrationMapper;
    private IImService imService;
    private ISystemNotificationIntentService intents;
    private INotifyService notifyService;
    private IWechatPayService wechat;
    private CmsRegistrationServiceImpl service;

    @BeforeEach
    void setUp() {
        activityMapper = mock(CmsActivityMapper.class);
        ticketMapper = mock(OmsTicketMapper.class);
        memberMapper = mock(UmsMemberMapper.class);
        registrationMapper = mock(CmsRegistrationMapper.class);
        com.chengyinhub.business.service.impl.RegistrationScopeFixture
                .currentReads(registrationMapper, activityMapper, null);
        imService = mock(IImService.class);
        intents = mock(ISystemNotificationIntentService.class);
        when(intents.findIntentId(anyString())).thenReturn(null);
        when(intents.enqueue(anyLong(), any(), anyString(), any(), anyString())).thenReturn(11L);
        notifyService = mock(INotifyService.class);
        wechat = mock(IWechatPayService.class);
        when(wechat.isReady()).thenReturn(true);

        service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "platformMemberId", PLATFORM);
        ReflectionTestUtils.setField(service, "growthService", mock(IGrowthService.class));
        ReflectionTestUtils.setField(service, "cmsTopicMapper", mock(CmsTopicMapper.class));
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "umsMemberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "verifiedPaymentFacts",
                com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.memoryHistoricalFacts());
        ReflectionTestUtils.setField(service, "productRoleService", mock(IProductRoleService.class));
        ReflectionTestUtils.setField(service, "settlementEntryService", mock(ISettlementEntryService.class));
        ReflectionTestUtils.setField(service, "cmsPointsSettingService", mock(ICmsPointsSettingService.class));
        ReflectionTestUtils.setField(service, "cmsRegistrationScanMapper", mock(CmsRegistrationScanMapper.class));
        ReflectionTestUtils.setField(service, "imService", imService);
        ReflectionTestUtils.setField(service, "systemNotificationIntents", intents);
        ReflectionTestUtils.setField(service, "notifyService", notifyService);
        CommittedBusinessNotice notice = mock(CommittedBusinessNotice.class);
        // 回放单测手工触发的 afterCommit；这里只验证是否投递、投递几次，不证明数据库事务。
        doAnswer(inv -> "registration-signup".equals(inv.getArgument(0))
                ? ((Supplier<?>) inv.getArgument(2)).get() : null)
                .when(notice).dispatchInNewTransaction(anyString(), any(), any());
        ReflectionTestUtils.setField(service, "committedBusinessNotice", notice);
        when(imService.deliverSystemNotification(11L)).thenReturn(1L);

        UmsMember m = new UmsMember();
        m.setId(MEMBER);
        m.setStatus(1L);
        m.setDelFlag(0);
        when(memberMapper.selectUmsMemberById(MEMBER)).thenReturn(m);
    }

    private CmsActivity givenStandaloneActivity(Long publisher) {
        CmsActivity a = new CmsActivity();
        a.setId(ACTIVITY);
        a.setName("独立活动");
        a.setMemberId(publisher);
        a.setStatus(1L);
        a.setPublishStatus(1L);
        a.setDelFlag(0);
        a.setEndDate(new Date(System.currentTimeMillis() + 86_400_000L));
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(a);
        return a;
    }

    private void givenTicket(BigDecimal price) {
        OmsTicket t = new OmsTicket();
        t.setId(TICKET);
        t.setName("票");
        t.setOwnerId(ACTIVITY);
        t.setOwnerType(2L);
        t.setPrice(price);
        t.setDelFlag(0);
        t.setMode(1);
        t.setRemainingInventory(100L);
        t.setStartTime(new Date(System.currentTimeMillis() + 3_600_000L));
        t.setEndTime(new Date(System.currentTimeMillis() + 86_400_000L));
        when(ticketMapper.selectOmsTicketById(TICKET)).thenReturn(t);
    }

    private CmsRegistration pendingOrder(BigDecimal payable) {
        CmsRegistration r = new CmsRegistration();
        r.setId(REG);
        r.setMemberId(MEMBER);
        r.setOwnerType(2L);
        r.setOwnerId(ACTIVITY);
        r.setTicketId(TICKET);
        r.setRegistrationNo("R" + REG);
        r.setRegistrationStatus(1);
        r.setPaymentStatus(0);
        r.setPayableAmount(payable);
        r.setFeePlatformRate(new BigDecimal("0.10"));
        r.setPayExpireTime(new Date(System.currentTimeMillis() + 600_000L));
        return r;
    }

    private RegistrationCheckoutServiceImpl checkout() {
        RegistrationCheckoutServiceImpl checkout = new RegistrationCheckoutServiceImpl();
        ReflectionTestUtils.setField(checkout, "registrationService", service);
        ReflectionTestUtils.setField(checkout, "registrationOrderService", mock(IRegistrationOrderService.class));
        ReflectionTestUtils.setField(checkout, "wechatPayService", wechat);
        return checkout;
    }

    @Test
    void userPublishedStandalonePaidOrderIsRefusedBeforeAnyWechatPrepayOnRetry() {
        givenStandaloneActivity(7L);
        givenTicket(new BigDecimal("100.00"));
        when(registrationMapper.selectCmsRegistrationById(REG)).thenReturn(pendingOrder(new BigDecimal("100.00")));

        RuntimeException e = assertThrows(RuntimeException.class, () -> checkout().retryPayment(MEMBER, REG));

        assertTrue(e.getMessage().contains("暂不支持在线支付"), e.getMessage());
        verify(wechat, never()).createOrder(any());
        verify(wechat, never()).createAppOrder(any());
        verify(registrationMapper, never()).claimPaymentProcessing(anyLong(), anyLong(), any());
    }

    @Test
    void platformPublishedStandalonePaidActivityStillQuotesAndRetries() {
        givenStandaloneActivity(PLATFORM);
        givenTicket(new BigDecimal("100.00"));

        assertEquals(0, new BigDecimal("100.00").compareTo(
                service.quoteRegistration(MEMBER, 2, ACTIVITY, TICKET, null, 0).getPayAmount()));
        when(registrationMapper.selectCmsRegistrationById(REG)).thenReturn(pendingOrder(new BigDecimal("100.00")));
        assertEquals(REG, service.preparePaymentRetry(REG, MEMBER).getId());
    }

    @Test
    void signupNoticeIsSentOnlyOnceAfterZeroAmountConfirmation() {
        givenStandaloneActivity(PLATFORM);
        CmsRegistration zero = pendingOrder(BigDecimal.ZERO);
        when(registrationMapper.selectByRegistrationNo("R" + REG)).thenReturn(zero);
        CmsRegistration paid = pendingOrder(BigDecimal.ZERO);
        paid.setPaymentStatus(2);
        paid.setRegistrationStatus(2);
        paid.setPaymentTime(new Date());
        when(registrationMapper.selectCmsRegistrationById(REG)).thenReturn(paid);
        org.mockito.Mockito.doReturn(paid).when(registrationMapper).selectCmsRegistrationByIdForUpdate(REG);
        when(registrationMapper.claimPaymentSuccess(eq(REG), any(), any())).thenReturn(1, 0);

        // Direct-new unit fixture supplies only synchronization; real rollback/persistence is tested in MySQL IT.
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            assertEquals("SUCCESS", service.completeZeroAmountPayment("R" + REG));
            service.completeZeroAmountPayment("R" + REG);
            verify(imService, never()).deliverSystemNotification(anyLong());
            for (org.springframework.transaction.support.TransactionSynchronization callback :
                    org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) callback.afterCommit();
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(intents, times(1)).enqueue(eq(MEMBER), any(), eq("报名成功"), any(), eq("registration-signup:" + REG));
        verify(imService, times(1)).deliverSystemNotification(11L);
        verify(imService, never()).sendSystem(anyLong(), any(), anyString(), any());
        verify(notifyService, times(1)).notifySignupSuccess(eq(MEMBER), any());
    }

    @Test
    void paidOrderCreationDoesNotSendSignupNoticeBeforePayment() {
        CmsRegistration created = pendingOrder(new BigDecimal("100.00"));
        IRegistrationOrderService orders = mock(IRegistrationOrderService.class);
        com.chengyinhub.business.domain.dto.RegistrationOrderResult result =
                mock(com.chengyinhub.business.domain.dto.RegistrationOrderResult.class);
        when(result.getPrimary()).thenReturn(created);
        when(result.getRegistrations()).thenReturn(java.util.Collections.singletonList(created));
        when(orders.createOrReplay(eq(MEMBER), any())).thenReturn(result);
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        when(registrations.beginPaymentAttempt(REG, MEMBER)).thenReturn(created);
        RegistrationCheckoutServiceImpl checkout = new RegistrationCheckoutServiceImpl();
        ReflectionTestUtils.setField(checkout, "registrationService", registrations);
        ReflectionTestUtils.setField(checkout, "registrationOrderService", orders);
        ReflectionTestUtils.setField(checkout, "wechatPayService", wechat);

        checkout.createOrReplay(MEMBER, new com.chengyinhub.business.domain.dto.RegistrationRequest());

        verify(wechat).createOrder(created);
        verify(registrations, never()).completeZeroAmountPayment(anyString());
        verify(registrations, never()).handleWechatPayCallback(any());
        verify(imService, never()).sendSystem(anyLong(), any(), anyString(), any());
        verify(intents, never()).enqueue(any(), any(), any(), any(), any());
    }
}
