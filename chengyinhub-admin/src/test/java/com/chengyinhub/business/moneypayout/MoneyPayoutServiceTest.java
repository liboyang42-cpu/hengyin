package com.chengyinhub.business.moneypayout;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.MemberCashWithdraw;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UserPermission;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.moneypayout.mapper.PayoutBindingMapper;
import com.chengyinhub.business.moneypayout.mapper.PayoutRequestMapper;
import com.chengyinhub.business.moneyswitch.MoneyArea;
import com.chengyinhub.business.moneyswitch.MoneySwitchService;
import com.chengyinhub.business.opsconsole.OpsConsoleAudit;
import com.chengyinhub.business.payout.EcommercePersonalReceiverService;
import com.chengyinhub.business.service.ICashWithdrawService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IWeChatMiniSessionService;
import com.chengyinhub.business.service.support.WithdrawalFrozenAmountResolver;
import com.chengyinhub.common.exception.BusinessRefusal;
import com.chengyinhub.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** R6b: every money step is refused while the switch is closed; the open path reviews before it pays. */
class MoneyPayoutServiceTest {
    static final long MEMBER = 7L;
    static final long CLUB = 3L;
    static final String MINI_APPID = "wx-mini";
    static final PlatformTransactionManager TX = new DataSourceTransactionManager(
            new DriverManagerDataSource("jdbc:h2:mem:r6b_money_payout", "sa", ""));

    // ---------- money switch ----------

    static PayoutMoneyGate gate(boolean withdrawal, boolean payout, boolean transfer) {
        MoneySwitchService sw = mock(MoneySwitchService.class);
        Map<String, Boolean> snap = new LinkedHashMap<>();
        for (MoneyArea a : MoneyArea.values()) snap.put(a.key(), false);
        snap.put("withdrawal", withdrawal);
        snap.put("payout", payout);
        when(sw.snapshot()).thenReturn(snap);
        return new PayoutMoneyGate(sw, transfer);
    }

    @Test
    void gateReadsClosedWhenTheSwitchThrows() {
        MoneySwitchService sw = mock(MoneySwitchService.class);
        when(sw.snapshot()).thenThrow(new RuntimeException("db down"));
        PayoutMoneyGate g = new PayoutMoneyGate(sw, true);
        for (MoneyArea a : MoneyArea.values()) assertFalse(g.isOpen(a));
    }

    // ---------- requests ----------

    static class Requests {
        final PayoutRequestMapper mapper = mock(PayoutRequestMapper.class);
        final PayoutBindingMapper bindings = mock(PayoutBindingMapper.class);
        final UmsMemberMapper members = mock(UmsMemberMapper.class);
        final IRoleService roles = mock(IRoleService.class);
        final WithdrawalFrozenAmountResolver frozen = mock(WithdrawalFrozenAmountResolver.class);
        final WithdrawalFrozenAmountResolver.Frozen none = mock(WithdrawalFrozenAmountResolver.Frozen.class);
        final ICashWithdrawService transfers = mock(ICashWithdrawService.class);
        final OpsConsoleAudit audit = mock(OpsConsoleAudit.class);
        final UmsMember member = new UmsMember();
        final PayoutRequestService service;

        Requests(PayoutMoneyGate gate) {
            member.setId(MEMBER);
            member.setBalance(new BigDecimal("50.00"));
            member.setWxOpenId("mini-openid");
            UserPermission permission = new UserPermission();
            permission.setWithdrawable(true);
            when(roles.getPermission(any())).thenReturn(permission);
            when(none.total()).thenReturn(BigDecimal.ZERO);
            when(none.isEmpty()).thenReturn(true);
            when(frozen.resolve(MEMBER)).thenReturn(none);
            when(members.lockActiveMemberById(MEMBER)).thenReturn(MEMBER);
            when(members.selectUmsMemberById(MEMBER)).thenReturn(member);
            when(members.selectBalanceForUpdate(MEMBER)).thenReturn(new BigDecimal("50.00"));
            when(mapper.insert(any())).thenAnswer(inv -> {
                ((PayoutRequestMapper.PayoutRequestRow) inv.getArgument(0)).setId(11L);
                return 1;
            });
            service = new PayoutRequestService(mapper, bindings, members, roles, frozen, transfers, gate, audit, TX);
        }

        Map<String, Object> row(String status) {
            Map<String, Object> r = new HashMap<>();
            r.put("id", 11L);
            r.put("memberId", MEMBER);
            r.put("amount", new BigDecimal("12.50"));
            r.put("status", status);
            return r;
        }
    }

    @Test
    void closedSwitchRefusesSubmitAndApproveAndSendsNothing() {
        Requests f = new Requests(gate(false, false, true));
        BusinessRefusal submit = assertThrows(BusinessRefusal.class, () -> f.service.submit(MEMBER, "12.50"));
        assertEquals(ErrorCode.FEATURE_OFF, submit.getErrorCode());
        BusinessRefusal approve = assertThrows(BusinessRefusal.class, () -> f.service.approve(11L, 1L, "admin", "ok"));
        assertEquals(ErrorCode.FEATURE_OFF, approve.getErrorCode());
        verify(f.mapper, never()).insert(any());
        verify(f.mapper, never()).reviewPending(anyLong(), anyString(), anyString(), anyString());
        verifyNoInteractions(f.transfers);
        assertEquals(false, f.service.overview(MEMBER).get("withdrawalOpen"));
    }

    @Test
    void openSwitchButClosedTransferRefusesApprove() {
        Requests f = new Requests(gate(true, false, false));
        BusinessRefusal approve = assertThrows(BusinessRefusal.class, () -> f.service.approve(11L, 1L, "admin", "ok"));
        assertEquals(ErrorCode.FEATURE_OFF, approve.getErrorCode());
        verifyNoInteractions(f.transfers);
    }

    @Test
    void submitQueuesARequestWithoutMovingMoney() {
        Requests f = new Requests(gate(true, false, false));
        when(f.mapper.selectById(11L)).thenReturn(f.row("PENDING"));
        Map<String, Object> out = f.service.submit(MEMBER, "12.50");
        assertEquals("PENDING", out.get("status"));
        verify(f.mapper).insert(argThat(r -> new BigDecimal("12.50").equals(r.getAmount()) && r.getMemberId() == MEMBER));
        verifyNoInteractions(f.transfers);
        verify(f.members, never()).deductBalance(any(), any());
    }

    @Test
    void submitRefusesShortBalanceSecondOpenRequestNoWechatAndBadAmounts() {
        Requests f = new Requests(gate(true, false, false));
        assertThrows(BusinessRefusal.class, () -> f.service.submit(MEMBER, "50.01"));
        when(f.mapper.countOpenByMember(MEMBER)).thenReturn(1);
        assertEquals(ErrorCode.CONFLICT, assertThrows(BusinessRefusal.class, () -> f.service.submit(MEMBER, "5")).getErrorCode());
        when(f.mapper.countOpenByMember(MEMBER)).thenReturn(0);
        f.member.setWxOpenId(null);
        assertThrows(BusinessRefusal.class, () -> f.service.submit(MEMBER, "5"));
        for (String bad : new String[] {"0.99", "abc", "1.001", "20000.01"})
            assertEquals(ErrorCode.BAD_REQUEST, assertThrows(BusinessRefusal.class, () -> f.service.submit(MEMBER, bad)).getErrorCode());
        verify(f.mapper, never()).insert(any());
    }

    @Test
    void submitUsesTheWalletBoundFromTheApp() {
        Requests f = new Requests(gate(true, false, false));
        f.member.setWxOpenId(null);
        when(f.bindings.selectWallet(MEMBER)).thenReturn(Map.of("openid", "bound-openid"));
        when(f.mapper.selectById(11L)).thenReturn(f.row("PENDING"));
        f.service.submit(MEMBER, "5");
        verify(f.mapper).insert(any());
    }

    @Test
    void approvePaysThroughTheExistingTransferAfterTheReviewCommits() {
        Requests f = new Requests(gate(true, false, true));
        when(f.mapper.selectById(11L)).thenReturn(f.row("PENDING"));
        when(f.mapper.reviewPending(11L, "PAYING", "admin", "checked")).thenReturn(1);
        when(f.bindings.selectWallet(MEMBER)).thenReturn(Map.of("openid", "bound-openid"));
        MemberCashWithdraw sent = new MemberCashWithdraw();
        sent.setId(99L);
        when(f.transfers.withdraw(MEMBER, new BigDecimal("12.50"), "bound-openid")).thenReturn(sent);
        f.service.approve(11L, 1L, "admin", "checked");
        var order = inOrder(f.mapper, f.audit, f.transfers);
        order.verify(f.mapper).reviewPending(11L, "PAYING", "admin", "checked");
        order.verify(f.audit).record(eq(1L), eq("PAYOUT_REQUEST_APPROVE"), eq("PAYOUT_REQUEST"), eq(11L), any(), any(), eq("checked"));
        order.verify(f.transfers).withdraw(MEMBER, new BigDecimal("12.50"), "bound-openid");
        verify(f.mapper).markSent(11L, 99L);
    }

    @Test
    void approveRecordsATransferRefusalAsFailed() {
        Requests f = new Requests(gate(true, false, true));
        when(f.mapper.selectById(11L)).thenReturn(f.row("PENDING"));
        when(f.mapper.reviewPending(anyLong(), anyString(), anyString(), anyString())).thenReturn(1);
        when(f.transfers.withdraw(anyLong(), any(), any())).thenThrow(new IllegalStateException("可用余额不足"));
        f.service.approve(11L, 1L, "admin", "checked");
        verify(f.mapper).markFailed(11L, "可用余额不足");
        verify(f.mapper, never()).markSent(anyLong(), any());
    }

    @Test
    void secondReviewerLosesAndNothingIsSent() {
        Requests f = new Requests(gate(true, false, true));
        when(f.mapper.selectById(11L)).thenReturn(f.row("PAYING"));
        when(f.mapper.reviewPending(anyLong(), anyString(), anyString(), anyString())).thenReturn(0);
        assertEquals(ErrorCode.CONFLICT,
                assertThrows(BusinessRefusal.class, () -> f.service.approve(11L, 1L, "admin", "x")).getErrorCode());
        verifyNoInteractions(f.transfers);
        verifyNoInteractions(f.audit);
    }

    @Test
    void rejectMovesNoMoneyNeedsANoteAndWorksWithTheSwitchClosed() {
        Requests f = new Requests(gate(false, false, false));
        when(f.mapper.selectById(11L)).thenReturn(f.row("PENDING"));
        assertThrows(BusinessRefusal.class, () -> f.service.reject(11L, 1L, "admin", " "));
        when(f.mapper.reviewPending(11L, "REJECTED", "admin", "dup")).thenReturn(1);
        f.service.reject(11L, 1L, "admin", "dup");
        verify(f.audit).record(eq(1L), eq("PAYOUT_REQUEST_REJECT"), any(), eq(11L), any(), any(), eq("dup"));
        verifyNoInteractions(f.transfers);
    }

    @Test
    void cancelIsOwnerAndPendingOnly() {
        Requests f = new Requests(gate(false, false, false));
        when(f.mapper.cancelPending(11L, MEMBER)).thenReturn(0);
        assertThrows(BusinessRefusal.class, () -> f.service.cancel(MEMBER, 11L));
        when(f.mapper.cancelPending(11L, MEMBER)).thenReturn(1);
        f.service.cancel(MEMBER, 11L);
        verifyNoInteractions(f.transfers);
    }

    // ---------- binding codes ----------

    static class Binding {
        final PayoutBindingMapper mapper = mock(PayoutBindingMapper.class);
        final UmsMemberMapper members = mock(UmsMemberMapper.class);
        final ClubMapper clubs = mock(ClubMapper.class);
        final IWeChatMiniSessionService sessions = mock(IWeChatMiniSessionService.class);
        final EcommercePersonalReceiverService receivers = mock(EcommercePersonalReceiverService.class);
        final UmsMember member = new UmsMember();
        final PayoutBindingService service;

        Binding(PayoutMoneyGate gate, String prefix) {
            member.setId(MEMBER);
            Club club = new Club();
            club.setId(CLUB);
            club.setMemberId(MEMBER);
            when(clubs.selectClubById(CLUB)).thenReturn(club);
            when(members.lockActiveMemberById(MEMBER)).thenReturn(MEMBER);
            when(members.selectUmsMemberById(MEMBER)).thenReturn(member);
            when(mapper.insert(anyString(), anyString(), anyLong(), anyLong(), any())).thenReturn(1);
            when(sessions.miniAppId()).thenReturn(MINI_APPID);
            service = new PayoutBindingService(mapper, members, clubs, sessions, receivers, gate, TX, 300, prefix);
        }

        Map<String, Object> code(String purpose, String status, String openid, long clubId) {
            Map<String, Object> r = new HashMap<>();
            r.put("id", 5L);
            r.put("purpose", purpose);
            r.put("memberId", MEMBER);
            r.put("clubId", clubId);
            r.put("status", status);
            r.put("claimedOpenid", openid);
            r.put("expiresAt", new Date(System.currentTimeMillis() + 60_000));
            return r;
        }
    }

    @Test
    void closedSwitchRefusesEveryBindingWriteAndCallsNoWechat() {
        Binding f = new Binding(gate(false, false, false), "https://example.test/bind");
        String code = PayoutBindingService.newCode();
        when(f.mapper.selectByHash(PayoutBindingService.hash(code))).thenReturn(f.code("WALLET", "ISSUED", null, 0));
        when(f.mapper.selectLatest(MEMBER, "WALLET", 0L)).thenReturn(f.code("WALLET", "CLAIMED", "o", 0));
        assertEquals(ErrorCode.FEATURE_OFF, assertThrows(BusinessRefusal.class, () -> f.service.issue(MEMBER, "WALLET", null)).getErrorCode());
        assertEquals(ErrorCode.FEATURE_OFF, assertThrows(BusinessRefusal.class, () -> f.service.issue(MEMBER, "CLUB_RECEIVER", CLUB)).getErrorCode());
        assertEquals(ErrorCode.FEATURE_OFF, assertThrows(BusinessRefusal.class, () -> f.service.claim(code, "js")).getErrorCode());
        assertEquals(ErrorCode.FEATURE_OFF, assertThrows(BusinessRefusal.class, () -> f.service.confirm(MEMBER, "WALLET", null)).getErrorCode());
        verify(f.sessions, never()).exchange(anyString());
        verify(f.mapper, never()).insert(anyString(), anyString(), anyLong(), anyLong(), any());
        verify(f.mapper, never()).upsertWallet(anyLong(), anyString(), anyString());
        verifyNoInteractions(f.receivers);
    }

    @Test
    void issueStoresOnlyTheHashAndBuildsTheQrLink() {
        Binding f = new Binding(gate(true, false, false), "https://example.test/bind");
        Map<String, Object> out = f.service.issue(MEMBER, "wallet", null);
        String code = (String) out.get("code");
        assertTrue(PayoutBindingService.CODE.matcher(code).matches());
        assertEquals("https://example.test/bind?bc=" + code, out.get("qrContent"));
        verify(f.mapper).cancelOpen(MEMBER, "WALLET");
        verify(f.mapper).insert(eq(PayoutBindingService.hash(code)), eq("WALLET"), eq(MEMBER), eq(0L), any());
        verify(f.mapper, never()).insert(eq(code), anyString(), anyLong(), anyLong(), any());
        assertNull(new Binding(gate(true, false, false), "http://insecure/x").service.issue(MEMBER, "WALLET", null).get("qrContent"));
    }

    @Test
    void clubReceiverCodeNeedsTheOwner() {
        Binding f = new Binding(gate(false, true, false), "");
        assertThrows(BusinessRefusal.class, () -> f.service.issue(8L, "CLUB_RECEIVER", CLUB));
        assertNotNull(f.service.issue(MEMBER, "CLUB_RECEIVER", CLUB).get("code"));
    }

    @Test
    void claimProvesTheOpenidButBindsNothing() {
        Binding f = new Binding(gate(true, false, false), "");
        String code = PayoutBindingService.newCode();
        String hash = PayoutBindingService.hash(code);
        when(f.mapper.selectByHash(hash)).thenReturn(f.code("WALLET", "ISSUED", null, 0));
        when(f.mapper.selectByHashForUpdate(hash)).thenReturn(f.code("WALLET", "ISSUED", null, 0));
        when(f.sessions.exchange("js")).thenReturn(new IWeChatMiniSessionService.WeChatMiniSession("scanned", "secret"));
        when(f.mapper.claim(5L, "scanned")).thenReturn(1);
        Map<String, Object> out = f.service.claim(code, "js");
        assertEquals("CLAIMED", out.get("status"));
        assertFalse(out.toString().contains("scanned"));
        verify(f.mapper, never()).upsertWallet(anyLong(), anyString(), anyString());
    }

    @Test
    void expiredOrUnknownCodeIsRefusedBeforeWechat() {
        Binding f = new Binding(gate(true, true, false), "");
        String code = PayoutBindingService.newCode();
        Map<String, Object> expired = f.code("WALLET", "ISSUED", null, 0);
        expired.put("expiresAt", new Date(System.currentTimeMillis() - 1000));
        when(f.mapper.selectByHash(PayoutBindingService.hash(code))).thenReturn(expired);
        assertEquals(ErrorCode.GONE, assertThrows(BusinessRefusal.class, () -> f.service.claim(code, "js")).getErrorCode());
        assertThrows(BusinessRefusal.class, () -> f.service.claim("short", "js"));
        verify(f.sessions, never()).exchange(anyString());
    }

    @Test
    void confirmTiesTheClaimedWalletToTheAppAccount() {
        Binding f = new Binding(gate(true, false, false), "");
        when(f.mapper.selectLatest(MEMBER, "WALLET", 0L)).thenReturn(f.code("WALLET", "CLAIMED", "scanned", 0));
        when(f.mapper.confirm(5L, MEMBER)).thenReturn(1);
        assertEquals("CONFIRMED", f.service.confirm(MEMBER, "WALLET", null).get("status"));
        verify(f.mapper).upsertWallet(MEMBER, MINI_APPID, "scanned");
    }

    @Test
    void confirmRefusesAWechatOtherThanTheAccountsOwn() {
        Binding f = new Binding(gate(true, false, false), "");
        f.member.setWxOpenId("own");
        when(f.mapper.selectLatest(MEMBER, "WALLET", 0L)).thenReturn(f.code("WALLET", "CLAIMED", "someone-else", 0));
        when(f.mapper.confirm(5L, MEMBER)).thenReturn(1);
        assertThrows(BusinessRefusal.class, () -> f.service.confirm(MEMBER, "WALLET", null));
        verify(f.mapper, never()).upsertWallet(anyLong(), anyString(), anyString());
    }

    @Test
    void confirmBeforeAnyScanIsRefused() {
        Binding f = new Binding(gate(true, true, false), "");
        when(f.mapper.selectLatest(MEMBER, "CLUB_RECEIVER", CLUB)).thenReturn(f.code("CLUB_RECEIVER", "ISSUED", null, CLUB));
        assertEquals(ErrorCode.CONFLICT,
                assertThrows(BusinessRefusal.class, () -> f.service.confirm(MEMBER, "CLUB_RECEIVER", CLUB)).getErrorCode());
        verifyNoInteractions(f.receivers);
    }

    @Test
    void confirmBindsTheClubReceiverThroughTheExistingService() {
        Binding f = new Binding(gate(false, true, false), "");
        when(f.mapper.selectLatest(MEMBER, "CLUB_RECEIVER", CLUB)).thenReturn(f.code("CLUB_RECEIVER", "CLAIMED", "scanned", CLUB));
        when(f.receivers.bindFromAppCode(MEMBER, CLUB, "scanned")).thenReturn(Map.of("receiverState", "CONSENTED"));
        Map<String, Object> out = f.service.confirm(MEMBER, "CLUB_RECEIVER", CLUB);
        assertEquals("CONFIRMED", out.get("status"));
        verify(f.mapper).confirm(5L, MEMBER);
    }
}
