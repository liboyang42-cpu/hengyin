package com.chengyinhub.business.payout;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.EcomPersonalReceiver;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.EcomPayoutMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IWeChatMiniSessionService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EcommercePersonalReceiverServiceTest {
    private static final long MEMBER = 55L;
    private static final long CLUB = 9L;
    private static final String APPID = "wx-platform";
    private static final String OPENID = "fresh-mini-owner";

    @Test
    void switchOffBindRecordsConsentWithoutProviderCallAndStatusReportsPayoutsNotStarted() {
        Fixture f = new Fixture();
        when(f.switches.personalClubEnabled()).thenReturn(false);
        when(f.receivers.insertConsented(any())).thenReturn(1);
        Map<String, Object> result = f.bind();
        ArgumentCaptor<EcomPersonalReceiver> saved = ArgumentCaptor.forClass(EcomPersonalReceiver.class);
        verify(f.receivers).insertConsented(saved.capture());
        assertEquals(OPENID, saved.getValue().getOpenid());
        assertEquals("CONSENTED", saved.getValue().getState());
        verifyNoInteractions(f.gateway);
        verify(f.receivers, never()).insertVerified(any());
        assertEquals("CONSENTED", result.get("receiverState"));
        assertEquals(false, result.get("payoutEnabled"));
        assertEquals(false, result.get("paused"));
        assertFalse(result.containsKey("openid"));
    }

    @Test
    void consentedRowIsUpgradedThroughProviderOnceSwitchIsOn() {
        Fixture f = new Fixture();
        EcomPersonalReceiver row = f.verified(OPENID);
        row.setState("CONSENTED");
        row.setConsentedAt(new java.util.Date(1000));
        when(f.receivers.selectByMemberAndAppidForUpdate(MEMBER, APPID)).thenReturn(row);
        when(f.receivers.promoteConsentedToVerified(any())).thenReturn(1);
        Map<String, Object> result = f.bind();
        verify(f.gateway).addPersonalReceiver(OPENID, APPID);
        verify(f.receivers).promoteConsentedToVerified(any());
        verify(f.receivers, never()).insertVerified(any());
        assertEquals("VERIFIED", result.get("receiverState"));
        assertEquals(false, result.get("paused"));
    }

    @Test
    void statusShowsPausedWhenSwitchOnButBindingNotConfirmed() {
        Fixture f = new Fixture();
        EcomPersonalReceiver row = f.verified(OPENID);
        row.setState("CONSENTED");
        when(f.receivers.selectByMemberAndAppid(MEMBER, APPID)).thenReturn(row);
        Map<String, Object> status = f.service.ownerStatus(MEMBER, CLUB);
        assertEquals(true, status.get("paused"));
        assertEquals("PERSONAL_BINDING_REQUIRED", status.get("pauseReason"));
        when(f.receivers.selectByMemberAndAppid(MEMBER, APPID)).thenReturn(null);
        assertEquals("UNBOUND", f.service.ownerStatus(MEMBER, CLUB).get("receiverState"));
    }

    @Test
    void statusWorksWithSwitchOffAndReportsPayoutDisabled() {
        Fixture f = new Fixture();
        when(f.switches.personalClubEnabled()).thenReturn(false);
        when(f.receivers.selectByMemberAndAppid(MEMBER, APPID)).thenReturn(f.verified(OPENID));
        Map<String, Object> status = f.service.ownerStatus(MEMBER, CLUB);
        assertEquals(true, status.get("available"));
        assertEquals(false, status.get("payoutEnabled"));
        assertEquals(false, status.get("paused"));
    }

    @Test
    void unbindDeletesOnlyWhenNoPayoutIsInFlightAndOnlyForOwner() {
        Fixture f = new Fixture();
        when(f.receivers.selectByMemberAndAppidForUpdate(MEMBER, APPID)).thenReturn(f.verified(OPENID));
        when(f.receivers.deleteReceiverIfIdle(MEMBER, APPID)).thenReturn(0);
        assertEquals(EcommercePersonalReceiverService.IN_FLIGHT_MESSAGE,
                assertThrows(ServiceException.class, () -> f.service.unbind(MEMBER, CLUB)).getMessage());
        when(f.receivers.deleteReceiverIfIdle(MEMBER, APPID)).thenReturn(1);
        assertEquals("UNBOUND", f.service.unbind(MEMBER, CLUB).get("receiverState"));
        f.club.setMemberId(99L);
        assertThrows(ServiceException.class, () -> f.service.unbind(MEMBER, CLUB));
        assertThrows(ServiceException.class, () -> f.service.unbind(null, CLUB));
        verify(f.receivers, times(2)).deleteReceiverIfIdle(MEMBER, APPID);
    }

    @Test
    void unbindWithoutBindingIsANoop() {
        Fixture f = new Fixture();
        assertEquals("UNBOUND", f.service.unbind(MEMBER, CLUB).get("receiverState"));
        verify(f.receivers, never()).deleteReceiverIfIdle(any(), any());
    }

    @Test
    void explicitConsentAndFreshCodeAreMandatory() {
        Fixture f = new Fixture();
        for (Boolean consent : new Boolean[] { false, null }) {
            assertThrows(ServiceException.class, () -> f.service.bind(MEMBER, CLUB, "code", consent));
        }
        assertThrows(ServiceException.class, () -> f.service.bind(MEMBER, CLUB, " ", true));
        assertThrows(ServiceException.class, () -> f.service.bind(null, CLUB, "code", true));
        verify(f.sessions, never()).exchange(any());
        verifyNoInteractions(f.members, f.clubs, f.gateway, f.receivers);
    }

    @Test
    void nonownerAndMissingClubCannotExchangeOrRegisterOrReadStatus() {
        Fixture f = new Fixture();
        f.club.setMemberId(99L);
        assertThrows(ServiceException.class, f::bind);
        assertThrows(ServiceException.class, () -> f.service.ownerStatus(MEMBER, CLUB));
        when(f.clubs.selectClubByIdForUpdate(CLUB)).thenReturn(null);
        assertThrows(ServiceException.class, f::bind);
        verify(f.sessions, never()).exchange(any());
        verifyNoInteractions(f.gateway, f.receivers);
    }

    @Test
    void disabledMemberCannotBind() {
        Fixture f = new Fixture();
        when(f.members.lockActiveMemberById(MEMBER)).thenReturn(null);
        assertThrows(ServiceException.class, f::bind);
        verifyNoInteractions(f.clubs, f.gateway, f.receivers);
        verify(f.sessions, never()).exchange(any());
    }

    @Test
    void appidMismatchFailsBeforeNetworkAndDatabaseWrites() {
        Fixture f = new Fixture();
        when(f.sessions.miniAppId()).thenReturn("wx-other");
        assertEquals("PERSONAL_RECEIVER_APPID_MISMATCH", assertThrows(ServiceException.class, f::bind).getMessage());
        verify(f.sessions, never()).exchange(any());
        verifyNoInteractions(f.members, f.clubs, f.gateway, f.receivers);
    }

    @Test
    void exchangedIdentityMustMatchCanonicalMiniOpenidNotAppIdentity() {
        Fixture f = new Fixture();
        f.member.setAppOpenId("app-openid");
        when(f.sessions.exchange("fresh-code")).thenReturn(new IWeChatMiniSessionService.WeChatMiniSession("app-openid", "secret"));
        assertEquals("PERSONAL_RECEIVER_IDENTITY_MISMATCH", assertThrows(ServiceException.class, f::bind).getMessage());
        verifyNoInteractions(f.gateway);
        verify(f.receivers, never()).insertVerified(any());
        f.member.setWxOpenId(null);
        assertThrows(ServiceException.class, f::bind);
    }

    @Test
    void historicalRawIdentityNeverBecomesVerifiedWithoutFreshExchange() {
        Fixture f = new Fixture();
        when(f.sessions.exchange("fresh-code")).thenThrow(new IllegalStateException("provider secret"));
        assertEquals("WECHAT_FRESH_CODE_EXCHANGE_FAILED", assertThrows(ServiceException.class, f::bind).getMessage());
        verifyNoInteractions(f.gateway);
        verify(f.receivers, never()).insertVerified(any());
    }

    @Test
    void providerRejectionDoesNotPersistOrLeakIdentity() {
        Fixture f = new Fixture();
        doThrow(new IllegalStateException(OPENID)).when(f.gateway).addPersonalReceiver(OPENID, APPID);
        assertEquals("PERSONAL_RECEIVER_PROVIDER_NOT_CONFIRMED", assertThrows(ServiceException.class, f::bind).getMessage());
        verify(f.receivers, never()).insertVerified(any());
    }

    @Test
    void successfulBindingPersistsOnlyAfterRealGatewayAcceptanceWithoutExposingIdentity() {
        Fixture f = new Fixture();
        doAnswer(invocation -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            verify(f.receivers, never()).insertVerified(any());
            return null;
        }).when(f.gateway).addPersonalReceiver(OPENID, APPID);
        Map<String, Object> result = f.bind();
        ArgumentCaptor<EcomPersonalReceiver> saved = ArgumentCaptor.forClass(EcomPersonalReceiver.class);
        verify(f.receivers).insertVerified(saved.capture());
        EcomPersonalReceiver row = saved.getValue();
        assertEquals(MEMBER, row.getMemberId());
        assertEquals(APPID, row.getAppid());
        assertEquals(OPENID, row.getOpenid());
        assertEquals("VERIFIED", row.getState());
        assertNotNull(row.getConsentedAt());
        assertNotNull(row.getVerifiedAt());
        assertFalse(row.getVerifiedAt().before(row.getConsentedAt()));
        assertEquals("PERSONAL_OPENID", result.get("receiverType"));
        assertEquals("VERIFIED", result.get("receiverState"));
        assertFalse(result.containsKey("openid"));
        assertFalse(result.containsKey("appid"));
        assertFalse(result.containsKey("sellerState"));
        var ordered = inOrder(f.members, f.clubs, f.sessions, f.gateway, f.receivers);
        ordered.verify(f.members).lockActiveMemberById(MEMBER);
        ordered.verify(f.clubs).selectClubByIdForUpdate(CLUB);
        ordered.verify(f.sessions).exchange("fresh-code");
        ordered.verify(f.gateway).addPersonalReceiver(OPENID, APPID);
        ordered.verify(f.receivers).insertVerified(any());
    }

    @Test
    void sameIdentityIsIdempotentButStillRequiresFreshConsentAndExchange() {
        Fixture f = new Fixture();
        EcomPersonalReceiver row = f.verified(OPENID);
        when(f.receivers.selectByMemberAndAppidForUpdate(MEMBER, APPID)).thenReturn(row);
        assertEquals("VERIFIED", f.bind().get("receiverState"));
        verify(f.sessions).exchange("fresh-code");
        verifyNoInteractions(f.gateway);
        verify(f.receivers, never()).insertVerified(any());
        assertThrows(ServiceException.class, () -> f.service.bind(MEMBER, CLUB, "fresh-code", false));
    }

    @Test
    void verifiedIdentityCannotBeReplacedEvenAfterCanonicalMemberIdentityChanges() {
        Fixture f = new Fixture();
        when(f.receivers.selectByMemberAndAppidForUpdate(MEMBER, APPID)).thenReturn(f.verified("original-identity"));
        assertEquals("PERSONAL_RECEIVER_IDENTITY_IMMUTABLE", assertThrows(ServiceException.class, f::bind).getMessage());
        verifyNoInteractions(f.gateway);
        verify(f.receivers, never()).insertVerified(any());
    }

    @Test
    void statusIsMemberScopedAndNewClubOwnerDoesNotInheritOldOwnerBinding() {
        Fixture f = new Fixture();
        when(f.receivers.selectByMemberAndAppid(MEMBER, APPID)).thenReturn(f.verified(OPENID));
        Map<String, Object> status = f.service.ownerStatus(MEMBER, CLUB);
        assertEquals("VERIFIED", status.get("receiverState"));
        assertFalse(status.containsValue(OPENID));
        f.club.setMemberId(99L);
        assertThrows(ServiceException.class, () -> f.service.ownerStatus(MEMBER, CLUB));
        assertEquals("UNBOUND", f.service.ownerStatus(99L, CLUB).get("receiverState"));
        verify(f.receivers).selectByMemberAndAppid(99L, APPID);
        verify(f.receivers, never()).insertVerified(any());
    }

    @Test void ownerTransferDuringProviderAcceptancePreventsBindingCommit() {
        Fixture f = new Fixture();
        doAnswer(call -> { f.club.setMemberId(99L); return null; })
                .when(f.gateway).addPersonalReceiver(OPENID, APPID);
        assertEquals("CLUB_OWNER_REQUIRED", assertThrows(ServiceException.class, f::bind).getMessage());
        verify(f.receivers, never()).insertVerified(any());
    }

    @Test void ambientTransactionCannotDispatchReceiverRegistration() {
        Fixture f = new Fixture();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, f::bind);
            verifyNoInteractions(f.members, f.clubs, f.gateway, f.receivers);
            verify(f.sessions, never()).exchange(any());
        } finally { org.springframework.transaction.support.TransactionSynchronizationManager.clear(); }
    }

    private static class Fixture {
        final EcomPayoutMapper receivers = mock(EcomPayoutMapper.class);
        final ClubMapper clubs = mock(ClubMapper.class);
        final UmsMemberMapper members = mock(UmsMemberMapper.class);
        final IWeChatMiniSessionService sessions = mock(IWeChatMiniSessionService.class);
        final EcommercePayGateway gateway = mock(EcommercePayGateway.class);
        final PayoutSwitches switches = mock(PayoutSwitches.class);
        final Club club = new Club();
        final UmsMember member = new UmsMember();
        final EcommercePersonalReceiverService service = new EcommercePersonalReceiverService(receivers, clubs, members, sessions, gateway, switches);

        Fixture() {
            org.springframework.test.util.ReflectionTestUtils.setField(service, "transactions",
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                            new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:dec_c_receiver_binding", "sa", "")));
            club.setId(CLUB);
            club.setMemberId(MEMBER);
            member.setId(MEMBER);
            member.setWxOpenId(OPENID);
            when(switches.personalClubEnabled()).thenReturn(true);
            when(switches.spAppid()).thenReturn(APPID);
            when(sessions.miniAppId()).thenReturn(APPID);
            when(sessions.exchange("fresh-code")).thenReturn(new IWeChatMiniSessionService.WeChatMiniSession(OPENID, "server-only-secret"));
            when(members.lockActiveMemberById(MEMBER)).thenReturn(MEMBER);
            when(members.selectUmsMemberById(MEMBER)).thenReturn(member);
            when(clubs.selectClubByIdForUpdate(CLUB)).thenReturn(club);
            when(clubs.selectClubById(CLUB)).thenReturn(club);
            when(receivers.insertVerified(any())).thenReturn(1);
        }

        Map<String, Object> bind() { return service.bind(MEMBER, CLUB, "fresh-code", true); }

        EcomPersonalReceiver verified(String openid) {
            EcomPersonalReceiver row = new EcomPersonalReceiver();
            row.setMemberId(MEMBER);
            row.setAppid(APPID);
            row.setOpenid(openid);
            row.setState("VERIFIED");
            return row;
        }
    }
}
