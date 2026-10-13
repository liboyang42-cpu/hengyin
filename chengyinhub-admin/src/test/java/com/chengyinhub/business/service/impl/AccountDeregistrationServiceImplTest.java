package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AccountDeregistration;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.OmsOrder;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.vo.DeregistrationStatusVO;
import com.chengyinhub.business.mapper.AccountDeregistrationMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.mapper.OmsOrderMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.ICashWithdrawService;
import com.chengyinhub.business.service.IAppSessionRegistryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccountDeregistrationServiceImplTest {

    @Test
    void memberExposesDeregistrationStatusForAuthenticationChecks() {
        UmsMember member = new UmsMember();

        member.setDeregisterStatus("PENDING");

        assertEquals("PENDING", member.getDeregisterStatus());
    }

    @Test
    void applyStartsSevenDayCoolingOffForEligibleAccount() {
        AccountDeregistrationMapper deregistrationMapper = mock(AccountDeregistrationMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        OmsOrderMapper orderMapper = mock(OmsOrderMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        UmsMember member = new UmsMember();
        member.setId(1001L);
        member.setBalance(BigDecimal.ZERO);
        when(memberMapper.selectUmsMemberById(1001L)).thenReturn(member);
        when(orderMapper.selectOmsOrderList(any(OmsOrder.class))).thenReturn(Collections.emptyList());
        when(registrationMapper.selectCmsRegistrationList(any(CmsRegistration.class))).thenReturn(Collections.emptyList());
        when(deregistrationMapper.selectLatestByMemberId(1001L)).thenReturn(null);
        when(deregistrationMapper.insert(any(AccountDeregistration.class))).thenReturn(1);
        ICashWithdrawService cashWithdrawService = mock(ICashWithdrawService.class);
        IAppSessionRegistryService registry = mock(IAppSessionRegistryService.class);
        when(registry.beginMemberMutation(1001L)).thenReturn("mutation-1001");

        AccountDeregistrationServiceImpl service = new AccountDeregistrationServiceImpl();
        ReflectionTestUtils.setField(service, "deregistrationMapper", deregistrationMapper);
        ReflectionTestUtils.setField(service, "memberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "registrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "cashWithdrawService", cashWithdrawService);
        ReflectionTestUtils.setField(service, "memberWithdrawalService", mock(com.chengyinhub.business.service.IUmsMemberWithdrawalService.class));
        ReflectionTestUtils.setField(service, "appSessionRegistryService", registry);
        ReflectionTestUtils.setField(service, "roamHangoutService", mock(com.chengyinhub.business.service.IRoamHangoutService.class));

        DeregistrationStatusVO status = service.apply(1001L);

        assertEquals("PENDING", status.getStatus());
        assertNotNull(status.getExecuteAfter());
        ArgumentCaptor<AccountDeregistration> captor = ArgumentCaptor.forClass(AccountDeregistration.class);
        verify(deregistrationMapper).insert(captor.capture());
        assertEquals("PENDING", captor.getValue().getStatus());
        assertEquals(1001L, captor.getValue().getMemberId());
        verify(deregistrationMapper).markMemberPending(eq(1001L), any(java.util.Date.class), any(java.util.Date.class));
        InOrder order = inOrder(registry, deregistrationMapper);
        order.verify(registry).beginMemberMutation(1001L);
        order.verify(deregistrationMapper).markMemberPending(eq(1001L), any(Date.class), any(Date.class));
        order.verify(registry).revokeAll(1001L);
        order.verify(registry).finishMemberMutationAfterTransaction(1001L, "mutation-1001");
    }

    @Test
    void applyStopsBeforeDatabaseWritesWhenSessionRevocationFails() {
        AccountDeregistrationMapper deregistrationMapper = mock(AccountDeregistrationMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        OmsOrderMapper orderMapper = mock(OmsOrderMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        UmsMember member = new UmsMember();
        member.setId(1001L);
        member.setBalance(BigDecimal.ZERO);
        when(memberMapper.selectUmsMemberById(1001L)).thenReturn(member);
        when(orderMapper.selectOmsOrderList(any(OmsOrder.class))).thenReturn(Collections.emptyList());
        when(registrationMapper.selectCmsRegistrationList(any(CmsRegistration.class))).thenReturn(Collections.emptyList());
        IAppSessionRegistryService registry = mock(IAppSessionRegistryService.class);
        doThrow(new IllegalStateException("redis unavailable")).when(registry).beginMemberMutation(1001L);
        AccountDeregistrationServiceImpl service = new AccountDeregistrationServiceImpl();
        ReflectionTestUtils.setField(service, "deregistrationMapper", deregistrationMapper);
        ReflectionTestUtils.setField(service, "memberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "registrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "cashWithdrawService", mock(ICashWithdrawService.class));
        ReflectionTestUtils.setField(service, "memberWithdrawalService", mock(com.chengyinhub.business.service.IUmsMemberWithdrawalService.class));
        ReflectionTestUtils.setField(service, "appSessionRegistryService", registry);
        ReflectionTestUtils.setField(service, "roamHangoutService", mock(com.chengyinhub.business.service.IRoamHangoutService.class));

        assertThrows(IllegalStateException.class, () -> service.apply(1001L));

        verify(deregistrationMapper, never()).insert(any(AccountDeregistration.class));
        verify(deregistrationMapper, never()).markMemberPending(eq(1001L), any(Date.class), any(Date.class));
    }

    @Test
    void precheckBlocksMerchantAndClubRoles() {
        AccountDeregistrationMapper deregistrationMapper = mock(AccountDeregistrationMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        OmsOrderMapper orderMapper = mock(OmsOrderMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        UmsMember merchant = new UmsMember();
        merchant.setId(1001L);
        merchant.setBalance(BigDecimal.ZERO);
        merchant.setRole("merchant");
        when(memberMapper.selectUmsMemberById(1001L)).thenReturn(merchant);
        when(orderMapper.selectOmsOrderList(any(OmsOrder.class))).thenReturn(Collections.emptyList());
        when(registrationMapper.selectCmsRegistrationList(any(CmsRegistration.class))).thenReturn(Collections.emptyList());
        ICashWithdrawService cashWithdrawService = mock(ICashWithdrawService.class);

        AccountDeregistrationServiceImpl service = new AccountDeregistrationServiceImpl();
        ReflectionTestUtils.setField(service, "deregistrationMapper", deregistrationMapper);
        ReflectionTestUtils.setField(service, "memberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "registrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "cashWithdrawService", cashWithdrawService);
        ReflectionTestUtils.setField(service, "memberWithdrawalService", mock(com.chengyinhub.business.service.IUmsMemberWithdrawalService.class));

        DeregistrationStatusVO status = service.precheck(1001L);

        assertEquals("BLOCKED", status.getStatus());
        assertEquals("持有商家身份，请先处理商家业务", status.getBlockers().get(0));
    }

    /**
     * P0-2 复现:用户走通道B(银行提现 /api/withdrawal)已把余额扣光(balance=0)并留下 status=0 待审核单,
     * 该单钱已扣未打款、姓名/银行卡是打款依据。此时申请注销——余额=0、通道A无在途——若不查通道B 会误判 ELIGIBLE,
     * 冷静期后匿名化会抹掉收款人 PII → 款项卡死无法打款 + 账号无法申诉 = 资损。
     * 期望:precheck 因通道B在途而 BLOCKED。
     */
    @Test
    void precheckBlocksWhenChannelBBankWithdrawalPending() {
        AccountDeregistrationMapper deregistrationMapper = mock(AccountDeregistrationMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        OmsOrderMapper orderMapper = mock(OmsOrderMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        UmsMember member = new UmsMember();
        member.setId(1001L);
        member.setBalance(BigDecimal.ZERO); // 余额已被通道B提现扣光
        when(memberMapper.selectUmsMemberById(1001L)).thenReturn(member);
        when(orderMapper.selectOmsOrderList(any(OmsOrder.class))).thenReturn(Collections.emptyList());
        when(registrationMapper.selectCmsRegistrationList(any(CmsRegistration.class))).thenReturn(Collections.emptyList());
        ICashWithdrawService cashWithdrawService = mock(ICashWithdrawService.class);
        when(cashWithdrawService.hasPendingWithdraw(1001L)).thenReturn(false); // 通道A 无在途
        com.chengyinhub.business.service.IUmsMemberWithdrawalService memberWithdrawalService =
                mock(com.chengyinhub.business.service.IUmsMemberWithdrawalService.class);
        when(memberWithdrawalService.hasPendingWithdrawal(1001L)).thenReturn(true); // 通道B 待审核在途

        AccountDeregistrationServiceImpl service = new AccountDeregistrationServiceImpl();
        ReflectionTestUtils.setField(service, "deregistrationMapper", deregistrationMapper);
        ReflectionTestUtils.setField(service, "memberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "registrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "cashWithdrawService", cashWithdrawService);
        ReflectionTestUtils.setField(service, "memberWithdrawalService", memberWithdrawalService);

        DeregistrationStatusVO status = service.precheck(1001L);

        assertEquals("BLOCKED", status.getStatus());
        assertTrue(status.getBlockers().stream().anyMatch(b -> b.contains("银行提现")),
                "通道B在途银行提现必须拦截注销,避免款项卡死+PII被抹");
    }

    @Test
    void executeDueAnonymizesAllPii_whenNoBlockers() {
        AccountDeregistrationMapper d = mock(AccountDeregistrationMapper.class);
        UmsMemberMapper m = mock(UmsMemberMapper.class);
        OmsOrderMapper o = mock(OmsOrderMapper.class);
        CmsRegistrationMapper r = mock(CmsRegistrationMapper.class);
        ICashWithdrawService c = mock(ICashWithdrawService.class);
        PlatformTransactionManager t = mock(PlatformTransactionManager.class);
        AlarmService a = mock(AlarmService.class);
        AccountDeregistration due = new AccountDeregistration();
        due.setId(9L); due.setMemberId(1001L);
        when(d.selectDue(any(Date.class))).thenReturn(Collections.singletonList(due));
        when(d.complete(eq(9L), any(Date.class))).thenReturn(1);
        UmsMember member = new UmsMember();
        member.setId(1001L); member.setBalance(BigDecimal.ZERO); member.setDelFlag(0);
        when(m.selectUmsMemberById(1001L)).thenReturn(member);
        when(o.selectOmsOrderList(any(OmsOrder.class))).thenReturn(Collections.emptyList());
        when(r.selectCmsRegistrationList(any(CmsRegistration.class))).thenReturn(Collections.emptyList());
        when(c.hasPendingWithdraw(1001L)).thenReturn(false);
        when(t.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        AccountDeregistrationServiceImpl service = wired(d, m, o, r, c, t, a);
        assertEquals(1, service.executeDue(new Date()));

        verify(d).anonymizeMember(1001L);
        verify(d).anonymizeRegistrations(1001L);
        verify(d).anonymizeOrders(1001L);
        verify(d).anonymizeMemberWithdrawal(1001L); // ★提现姓名/银行卡/手机号 PII
        verify(d).anonymizeCashWithdraw(1001L);     // ★openid
        verify(d).anonymizeMerchantLead(1001L);     // ★商家lead玩家PII
        CommunityPostMapper communityPosts = (CommunityPostMapper)
                ReflectionTestUtils.getField(service, "communityPostMapper");
        verify(communityPosts).prepareAccountPostsForErasure(eq(1001L), any(Date.class));
        verify(communityPosts).scrubAccountPostRevisions(1001L);
        verify(communityPosts).scrubAccountAppeals(1001L);
        verify(communityPosts).scrubAccountGovernanceContent(1001L);
        verify(communityPosts).anonymizeAccountAppeals(1001L);
        verify(communityPosts).scrubAccountOutbox(1001L);
        verify(communityPosts).orphanAccountMedia(eq(1001L), any(Date.class), any(Date.class));
        verify(communityPosts).enqueueAccountErasureJobs(eq(1001L), eq("account-deregister-9"), any(Date.class));
        verify(communityPosts).anonymizeAccountPostAuthors(eq(1001L), any(Date.class));
        verify(communityPosts).anonymizeAccountComments(eq(1001L), any(Date.class));
        verify(communityPosts).clearAccountReplyTargets(eq(1001L), any(Date.class));
        verify(communityPosts).deleteAccountPostActions(1001L);
        verify(communityPosts).deleteAccountCommentActions(1001L);
        verify(communityPosts).deleteAccountMutes(1001L);
        verify(communityPosts).deleteAccountNotificationActors(1001L);
        verify(communityPosts).reconcileNotificationsAfterActorErasure(eq(1001L), any());
        verify(communityPosts).deleteAccountNotifications(1001L);
        verify(communityPosts).deleteAccountGovernanceState(1001L);
        verify(communityPosts).deleteAccountNotificationPreferences(1001L);
        verify(communityPosts).deleteAccountCommandReceipts(1001L);
        verify(communityPosts).deleteAccountGuidelineAcks(1001L);
        verify(communityPosts).deleteAccountMentions(1001L);
        verify(d).deleteMemberIdentity(1001L);      // ★发布者实名(姓名+身份证号密文)整行删掉
        verify(d).complete(eq(9L), any(Date.class));
        IAppSessionRegistryService registry = (IAppSessionRegistryService)
                ReflectionTestUtils.getField(service, "appSessionRegistryService");
        InOrder order = inOrder(registry, d);
        order.verify(registry).beginMemberMutation(1001L);
        order.verify(d).anonymizeMember(1001L);
        order.verify(registry).revokeAll(1001L);
        order.verify(registry).finishMemberMutationAfterTransaction(1001L, "mutation-1001");
    }

    @Test
    void executeDueBlockedByLateFunds_unfreezesNotAnonymize() {
        // 冷静期内有余额入账 → 执行期阻断:解冻+失败+告警,绝不匿名化(防余额冻死)
        AccountDeregistrationMapper d = mock(AccountDeregistrationMapper.class);
        UmsMemberMapper m = mock(UmsMemberMapper.class);
        OmsOrderMapper o = mock(OmsOrderMapper.class);
        CmsRegistrationMapper r = mock(CmsRegistrationMapper.class);
        ICashWithdrawService c = mock(ICashWithdrawService.class);
        PlatformTransactionManager t = mock(PlatformTransactionManager.class);
        AlarmService a = mock(AlarmService.class);
        AccountDeregistration due = new AccountDeregistration();
        due.setId(9L); due.setMemberId(1001L);
        when(d.selectDue(any(Date.class))).thenReturn(Collections.singletonList(due));
        UmsMember member = new UmsMember();
        member.setId(1001L); member.setBalance(new BigDecimal("50")); member.setDelFlag(0);
        when(m.selectUmsMemberById(1001L)).thenReturn(member);
        when(o.selectOmsOrderList(any(OmsOrder.class))).thenReturn(Collections.emptyList());
        when(r.selectCmsRegistrationList(any(CmsRegistration.class))).thenReturn(Collections.emptyList());
        when(c.hasPendingWithdraw(1001L)).thenReturn(false);
        when(t.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        AccountDeregistrationServiceImpl service = wired(d, m, o, r, c, t, a);
        assertEquals(0, service.executeDue(new Date()));

        verify(d, never()).anonymizeMember(anyLong());
        verify(d).markMemberNormal(1001L);
        verify(d).markFailed(eq(9L), anyString(), any(Date.class));
        verify(a).alarm(anyString(), anyString());
    }

    private AccountDeregistrationServiceImpl wired(AccountDeregistrationMapper d, UmsMemberMapper m,
            OmsOrderMapper o, CmsRegistrationMapper r, ICashWithdrawService c,
            PlatformTransactionManager t, AlarmService a) {
        AccountDeregistrationServiceImpl s = new AccountDeregistrationServiceImpl();
        ReflectionTestUtils.setField(s, "deregistrationMapper", d);
        ReflectionTestUtils.setField(s, "memberMapper", m);
        ReflectionTestUtils.setField(s, "orderMapper", o);
        ReflectionTestUtils.setField(s, "registrationMapper", r);
        ReflectionTestUtils.setField(s, "cashWithdrawService", c);
        ReflectionTestUtils.setField(s, "memberWithdrawalService", mock(com.chengyinhub.business.service.IUmsMemberWithdrawalService.class));
        ReflectionTestUtils.setField(s, "transactionManager", t);
        ReflectionTestUtils.setField(s, "alarmService", a);
        IAppSessionRegistryService registry = mock(IAppSessionRegistryService.class);
        when(registry.beginMemberMutation(anyLong())).thenAnswer(invocation ->
                "mutation-" + invocation.getArgument(0));
        ReflectionTestUtils.setField(s, "appSessionRegistryService", registry);
        ReflectionTestUtils.setField(s, "communityPostMapper", mock(CommunityPostMapper.class));
        ReflectionTestUtils.setField(s, "roamHangoutService", mock(com.chengyinhub.business.service.IRoamHangoutService.class));
        return s;
    }

    @Test
    void precheckBlocksPendingWithdraw() {
        AccountDeregistrationMapper deregistrationMapper = mock(AccountDeregistrationMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        OmsOrderMapper orderMapper = mock(OmsOrderMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        ICashWithdrawService cashWithdrawService = mock(ICashWithdrawService.class);
        UmsMember member = new UmsMember();
        member.setId(1001L);
        member.setBalance(BigDecimal.ZERO);
        when(memberMapper.selectUmsMemberById(1001L)).thenReturn(member);
        when(orderMapper.selectOmsOrderList(any(OmsOrder.class))).thenReturn(Collections.emptyList());
        when(registrationMapper.selectCmsRegistrationList(any(CmsRegistration.class))).thenReturn(Collections.emptyList());
        when(cashWithdrawService.hasPendingWithdraw(1001L)).thenReturn(true);

        AccountDeregistrationServiceImpl service = new AccountDeregistrationServiceImpl();
        ReflectionTestUtils.setField(service, "deregistrationMapper", deregistrationMapper);
        ReflectionTestUtils.setField(service, "memberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "registrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "cashWithdrawService", cashWithdrawService);
        ReflectionTestUtils.setField(service, "memberWithdrawalService", mock(com.chengyinhub.business.service.IUmsMemberWithdrawalService.class));

        DeregistrationStatusVO status = service.precheck(1001L);

        assertEquals("BLOCKED", status.getStatus());
        assertEquals("存在处理中的提现，请等到账后再申请注销", status.getBlockers().get(0));
    }
}
