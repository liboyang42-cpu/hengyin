package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.MerchantReferralBountyService;
import com.chengyinhub.business.service.support.*;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** NOT_RUN. Real admission/resolver logic with mapper doubles; not MySQL locking or savepoint evidence. */
public class OWN_C04_21Test {
    @Test void currentFreezeReadFollowsBalanceLockAndBlocksNewlyFrozenMoney() {
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        MerchantReferralBountyMapper bounty = mock(MerchantReferralBountyMapper.class);
        PlayerWithdrawalCooldownMapper cooldown = mock(PlayerWithdrawalCooldownMapper.class);
        WithdrawalFrozenAmountResolver resolver = resolver(bounty, cooldown);
        when(members.selectBalanceForUpdate(7L)).thenReturn(new BigDecimal("130"));
        when(bounty.selectFrozenAmountsForUpdate(eq(7L), any())).thenReturn(Arrays.asList(null, new BigDecimal("10")));
        when(cooldown.selectUnexpiredAmountsForUpdate(7L)).thenReturn(Collections.singletonList(new BigDecimal("30")));
        WithdrawalBalanceAdmissionService.Admission outcome = new WithdrawalBalanceAdmissionService(members, resolver,
                mock(MemberCashWithdrawMapper.class)).debitBank(7L, new BigDecimal("100"));
        assertFalse(outcome.deducted);
        assertEquals(new BigDecimal("40"), outcome.frozen.total());
        assertEquals(new BigDecimal("130"), outcome.before);
        InOrder order = inOrder(members, bounty, cooldown);
        order.verify(members).selectBalanceForUpdate(7L);
        order.verify(bounty).selectFrozenAmountsForUpdate(eq(7L), any());
        order.verify(cooldown).selectUnexpiredAmountsForUpdate(7L);
        verify(members, never()).deductWithdrawableBalance(anyLong(), any(), any());
    }

    @Test void successfulDebitReturnsTheLockedBeforeAndAfterAndExactFloor() {
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        MerchantReferralBountyMapper bounty = mock(MerchantReferralBountyMapper.class);
        PlayerWithdrawalCooldownMapper cooldown = mock(PlayerWithdrawalCooldownMapper.class);
        when(members.selectBalanceForUpdate(7L)).thenReturn(new BigDecimal("160"));
        when(bounty.selectFrozenAmountsForUpdate(eq(7L), any())).thenReturn(Collections.singletonList(new BigDecimal("10")));
        when(cooldown.selectUnexpiredAmountsForUpdate(7L)).thenReturn(Collections.singletonList(new BigDecimal("30")));
        when(members.deductWithdrawableBalance(7L, new BigDecimal("100"), new BigDecimal("40"))).thenReturn(1);
        WithdrawalBalanceAdmissionService.Admission result = new WithdrawalBalanceAdmissionService(members,
                resolver(bounty, cooldown), mock(MemberCashWithdrawMapper.class)).debitBank(7L, new BigDecimal("100"));
        assertTrue(result.deducted);
        assertEquals(new BigDecimal("160"), result.before);
        assertEquals(new BigDecimal("60"), result.after);
        verify(members).deductWithdrawableBalance(7L, new BigDecimal("100"), new BigDecimal("40"));
    }

    @Test void cashTerminalOrFrozenOriginalCannotReachMemberDebit() {
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        MemberCashWithdrawMapper cash = mock(MemberCashWithdrawMapper.class);
        MemberCashWithdraw original = new MemberCashWithdraw();
        original.setId(1L); original.setMemberId(7L); original.setAmount(new BigDecimal("100"));
        original.setStatus(MemberCashWithdraw.STATUS_SUCCESS);
        when(cash.selectByIdForUpdate(1L)).thenReturn(original);
        WithdrawalFrozenAmountResolver resolver = mock(WithdrawalFrozenAmountResolver.class);
        WithdrawalBalanceAdmissionService admission = new WithdrawalBalanceAdmissionService(members, resolver, cash);
        assertFalse(admission.debitCash(1L, 7L, new BigDecimal("100")).currentRequestEligible);
        original.setStatus(MemberCashWithdraw.STATUS_PROCESSING); original.setFrozen(1);
        assertFalse(admission.debitCash(1L, 7L, new BigDecimal("100")).currentRequestEligible);
        verifyNoInteractions(members, resolver);
    }

    @Test void cashAdmissionLocksOriginalBeforeMemberAndFreezeSources() {
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        MemberCashWithdrawMapper cash = mock(MemberCashWithdrawMapper.class);
        MerchantReferralBountyMapper bounty = mock(MerchantReferralBountyMapper.class);
        PlayerWithdrawalCooldownMapper cooldown = mock(PlayerWithdrawalCooldownMapper.class);
        MemberCashWithdraw original = new MemberCashWithdraw();
        original.setId(1L); original.setMemberId(7L); original.setAmount(new BigDecimal("100"));
        original.setStatus(MemberCashWithdraw.STATUS_PROCESSING);
        when(cash.selectByIdForUpdate(1L)).thenReturn(original);
        when(members.selectBalanceForUpdate(7L)).thenReturn(new BigDecimal("160"));
        when(bounty.selectFrozenAmountsForUpdate(eq(7L), any())).thenReturn(Collections.emptyList());
        when(cooldown.selectUnexpiredAmountsForUpdate(7L)).thenReturn(Collections.emptyList());
        when(members.deductWithdrawableBalance(7L, new BigDecimal("100"), BigDecimal.ZERO)).thenReturn(1);
        assertTrue(new WithdrawalBalanceAdmissionService(members, resolver(bounty, cooldown), cash)
                .debitCash(1L, 7L, new BigDecimal("100")).deducted);
        InOrder order = inOrder(cash, members, bounty, cooldown);
        order.verify(cash).selectByIdForUpdate(1L);
        order.verify(members).selectBalanceForUpdate(7L);
        order.verify(bounty).selectFrozenAmountsForUpdate(eq(7L), any());
        order.verify(cooldown).selectUnexpiredAmountsForUpdate(7L);
        order.verify(members).deductWithdrawableBalance(7L, new BigDecimal("100"), BigDecimal.ZERO);
    }

    @Test void currentFreezeReadFailureNeverBecomesZeroOrReachesDebit() {
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        MerchantReferralBountyMapper bounty = mock(MerchantReferralBountyMapper.class);
        PlayerWithdrawalCooldownMapper cooldown = mock(PlayerWithdrawalCooldownMapper.class);
        when(members.selectBalanceForUpdate(7L)).thenReturn(new BigDecimal("160"));
        when(bounty.selectFrozenAmountsForUpdate(eq(7L), any())).thenThrow(new IllegalStateException("current read failed"));
        WithdrawalBalanceAdmissionService service = new WithdrawalBalanceAdmissionService(members,
                resolver(bounty, cooldown), mock(MemberCashWithdrawMapper.class));
        assertThrows(IllegalStateException.class, () -> service.debitBank(7L, new BigDecimal("100")));
        verify(members, never()).deductWithdrawableBalance(anyLong(), any(), any());
        verifyNoInteractions(cooldown);
    }

    @Test void cashCallerDoesNotSubmitOrOverwriteAnIneligibleOriginalReceipt() {
        com.chengyinhub.business.service.impl.CashWithdrawServiceImpl caller =
                new com.chengyinhub.business.service.impl.CashWithdrawServiceImpl();
        MemberCashWithdrawMapper cash = mock(MemberCashWithdrawMapper.class);
        IUmsMemberService members = mock(IUmsMemberService.class);
        IWechatPayService provider = mock(IWechatPayService.class);
        UmsMember member = new UmsMember(); member.setId(7L); member.setWxOpenId("openid");
        member.setBalance(new BigDecimal("160"));
        when(members.selectUmsMemberById(7L)).thenReturn(member);
        com.chengyinhub.common.config.WxPayConfig config = mock(com.chengyinhub.common.config.WxPayConfig.class);
        when(config.getAppid()).thenReturn("wx-appid");
        MerchantReferralBountyService bounty = mock(MerchantReferralBountyService.class);
        IPlayerWithdrawalCooldownService cooldown = mock(IPlayerWithdrawalCooldownService.class);
        when(bounty.frozenAmount(7L)).thenReturn(BigDecimal.ZERO);
        when(cooldown.frozenAmount(7L)).thenReturn(BigDecimal.ZERO);
        com.chengyinhub.business.service.impl.CashWithdrawFailureRecoveryService original =
                mock(com.chengyinhub.business.service.impl.CashWithdrawFailureRecoveryService.class);
        doAnswer(i -> { i.getArgument(0, MemberCashWithdraw.class).setId(1L); return null; }).when(original).prepare(any());
        WithdrawalBalanceAdmissionService admission = mock(WithdrawalBalanceAdmissionService.class);
        when(admission.debitCash(eq(1L), eq(7L), any())).thenReturn(
                new WithdrawalBalanceAdmissionService.Admission(null, null, null, false, false));
        ReflectionTestUtils.setField(caller, "mapper", cash);
        ReflectionTestUtils.setField(caller, "umsMemberService", members);
        ReflectionTestUtils.setField(caller, "wechatPayService", provider);
        ReflectionTestUtils.setField(caller, "wxPayConfig", config);
        ReflectionTestUtils.setField(caller, "failureRecoveryService", original);
        ReflectionTestUtils.setField(caller, "frozenAmountResolver", new WithdrawalFrozenAmountResolver(bounty, cooldown));
        ReflectionTestUtils.setField(caller, "balanceAdmissionService", admission);
        assertThrows(IllegalStateException.class, () -> caller.withdraw(7L, new BigDecimal("100")));
        verifyNoInteractions(provider);
        verify(cash, never()).updateMemberCashWithdraw(any());
        verify(cash, never()).updateStatusIfProcessing(any());
        verify(members, never()).deductWithdrawableBalance(anyLong(), any(), any());
        verify(members, never()).addBalance(anyLong(), any());
    }

    @Test void bountyFailureRollsBackItsNestedBoundaryAfterMemberThenBountyLock() {
        MerchantReferralBountyMapper bounty = mock(MerchantReferralBountyMapper.class);
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        IUmsMemberService memberService = mock(IUmsMemberService.class);
        org.springframework.transaction.PlatformTransactionManager manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        org.springframework.transaction.TransactionStatus status = new org.springframework.transaction.support.SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        MerchantReferralBounty pending = new MerchantReferralBounty();
        pending.setId(3L); pending.setInviterMemberId(7L); pending.setMerchantMemberId(9L);
        pending.setStatus(MerchantReferralBounty.STATUS_PENDING);
        pending.setMerchantRegisteredAt(new Date(System.currentTimeMillis() - 1000));
        pending.setWindowExpiresAt(new Date(System.currentTimeMillis() + 86400000));
        when(bounty.selectPaidIdsByInviterForUpdate(7L)).thenReturn(Collections.emptyList());
        when(bounty.selectByMerchantMemberId(9L)).thenReturn(pending);
        when(bounty.selectByMerchantMemberIdForUpdate(9L)).thenReturn(pending);
        when(members.selectBalanceForUpdate(7L)).thenReturn(new BigDecimal("50"));
        UmsMember inviter = new UmsMember(); inviter.setId(7L); inviter.setBalance(new BigDecimal("50"));
        when(memberService.selectUmsMemberById(7L)).thenReturn(inviter);
        when(bounty.countVerifiedHeadsByMerchantInWindow(eq(9L), any(), any())).thenReturn(3);
        when(bounty.sumVerifiedPaidByMerchantInWindow(eq(9L), any(), any())).thenReturn(new BigDecimal("100"));
        when(bounty.selectVerifiedRegistrationIdsByMerchantInWindow(eq(9L), any(), any(), anyInt()))
                .thenReturn(Arrays.asList(11L, 12L, 13L));
        when(bounty.markPaidIfPending(eq(3L), any(), any(), any(), any(), any())).thenReturn(1);
        when(memberService.addBalance(eq(7L), any())).thenThrow(new IllegalStateException("fixture write failure"));
        MerchantReferralBountyService service = new MerchantReferralBountyService();
        ReflectionTestUtils.setField(service, "bountyMapper", bounty);
        ReflectionTestUtils.setField(service, "umsMemberMapper", members);
        ReflectionTestUtils.setField(service, "umsMemberService", memberService);
        ReflectionTestUtils.setField(service, "transactionManager", manager);
        assertDoesNotThrow(() -> service.onVerification(9L));
        org.mockito.ArgumentCaptor<org.springframework.transaction.TransactionDefinition> definition =
                org.mockito.ArgumentCaptor.forClass(org.springframework.transaction.TransactionDefinition.class);
        verify(manager).getTransaction(definition.capture());
        assertEquals(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED,
                definition.getValue().getPropagationBehavior());
        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
        InOrder order = inOrder(members, bounty);
        order.verify(members).selectBalanceForUpdate(7L);
        order.verify(bounty).selectByMerchantMemberIdForUpdate(9L);
        order.verify(bounty).markPaidIfPending(eq(3L), any(), any(), any(), any(), any());
    }

    private static WithdrawalFrozenAmountResolver resolver(MerchantReferralBountyMapper bounty,
                                                            PlayerWithdrawalCooldownMapper cooldown) {
        WithdrawalFrozenAmountResolver resolver = new WithdrawalFrozenAmountResolver(
                mock(MerchantReferralBountyService.class), mock(IPlayerWithdrawalCooldownService.class));
        ReflectionTestUtils.setField(resolver, "bountyMapper", bounty);
        ReflectionTestUtils.setField(resolver, "cooldownMapper", cooldown);
        return resolver;
    }

    /** Legacy focused fixture adapter only: retain its existing rule stubs and exact debit assertions.
     * It makes no claim about current-read visibility or transaction/locking behavior. */
    public static WithdrawalBalanceAdmissionService bankFixture(UmsMemberMapper members, WithdrawalFrozenAmountResolver resolver) {
        doAnswer(i -> { UmsMember member = members.selectUmsMemberById(i.getArgument(0));
            return member == null ? null : (member.getBalance() == null ? BigDecimal.ZERO : member.getBalance()); }).when(members).selectBalanceForUpdate(anyLong());
        fixtureSources(resolver);
        return new WithdrawalBalanceAdmissionService(members, resolver, mock(MemberCashWithdrawMapper.class));
    }
    public static WithdrawalBalanceAdmissionService cashFixture(IUmsMemberService service, MemberCashWithdrawMapper cash,
                                                               WithdrawalFrozenAmountResolver resolver) {
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        when(members.selectBalanceForUpdate(anyLong())).thenAnswer(i -> {
            UmsMember member = service.selectUmsMemberById(i.getArgument(0));
            return member == null ? null : (member.getBalance() == null ? BigDecimal.ZERO : member.getBalance());
        });
        when(members.deductWithdrawableBalance(anyLong(), any(), any())).thenAnswer(i ->
                service.deductWithdrawableBalance(i.getArgument(0), i.getArgument(1), i.getArgument(2)) ? 1 : 0);
        when(cash.selectByIdForUpdate(anyLong())).thenAnswer(i -> cash.selectById(i.getArgument(0)));
        fixtureSources(resolver);
        return new WithdrawalBalanceAdmissionService(members, resolver, cash);
    }
    private static void fixtureSources(WithdrawalFrozenAmountResolver resolver) {
        MerchantReferralBountyMapper bounty = mock(MerchantReferralBountyMapper.class);
        PlayerWithdrawalCooldownMapper cooldown = mock(PlayerWithdrawalCooldownMapper.class);
        when(bounty.selectFrozenAmountsForUpdate(anyLong(), any())).thenAnswer(i ->
                Collections.singletonList(resolver.resolve(i.getArgument(0)).getReferralBounty()));
        when(cooldown.selectUnexpiredAmountsForUpdate(anyLong())).thenAnswer(i ->
                Collections.singletonList(resolver.resolve(i.getArgument(0)).getPlayerComplaintCooldown()));
        ReflectionTestUtils.setField(resolver, "bountyMapper", bounty);
        ReflectionTestUtils.setField(resolver, "cooldownMapper", cooldown);
    }
}
