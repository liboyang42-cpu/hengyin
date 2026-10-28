package com.chengyinhub.business.service.compliance;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.MinorGuardianAuthorizationMapper;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.impl.MinorProtectionServiceImpl;
import com.chengyinhub.business.service.minor.AgeBand;
import com.chengyinhub.business.service.minor.MinorDecision;
import com.chengyinhub.business.service.minor.MinorPolicy;
import com.chengyinhub.business.service.minor.OrienteeringParticipation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** DOC2 F7 未成年模式规则（业主定稿）与执行挂点的缺省关闭。 */
class MinorProtectionServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);
    private static final long KID_6 = 1L, KID_10 = 2L, TEEN_16 = 3L, ADULT = 4L, UNKNOWN = 5L, PARENT = 6L;

    private IUmsMemberService members;
    private MinorGuardianAuthorizationMapper mapper;
    private ComplianceFeatureSwitches switches;
    private MinorProtectionServiceImpl service;

    @BeforeEach
    void setUp() {
        members = mock(IUmsMemberService.class);
        mapper = mock(MinorGuardianAuthorizationMapper.class);
        switches = mock(ComplianceFeatureSwitches.class);
        service = new MinorProtectionServiceImpl(members, mapper, switches);
        service.setClock(Clock.fixed(ZonedDateTime.of(TODAY.atTime(10, 0), MinorPolicy.ZONE).toInstant(), MinorPolicy.ZONE));
        born(KID_6, TODAY.minusYears(6));
        born(KID_10, TODAY.minusYears(10));
        born(TEEN_16, TODAY.minusYears(16));
        born(ADULT, TODAY.minusYears(30));
        born(PARENT, TODAY.minusYears(38));
        born(UNKNOWN, null);
    }

    @Test
    void ageBandBoundariesFollowTheOwnerRules() {
        assertEquals(AgeBand.UNDER_8, AgeBand.of(TODAY.minusYears(8).plusDays(1), TODAY));
        assertEquals(AgeBand.AGE_8_15, AgeBand.of(TODAY.minusYears(8), TODAY));
        assertEquals(AgeBand.AGE_8_15, AgeBand.of(TODAY.minusYears(16).plusDays(1), TODAY));
        assertEquals(AgeBand.AGE_16_17, AgeBand.of(TODAY.minusYears(16), TODAY));
        assertEquals(AgeBand.AGE_16_17, AgeBand.of(TODAY.minusYears(18).plusDays(1), TODAY));
        assertEquals(AgeBand.ADULT, AgeBand.of(TODAY.minusYears(18), TODAY));
        assertEquals(AgeBand.UNKNOWN, AgeBand.of(null, TODAY));
        assertEquals(AgeBand.UNKNOWN, AgeBand.of(TODAY.plusDays(1), TODAY), "未来生日不可信");
        assertEquals(AgeBand.UNKNOWN, service.ageBand(UNKNOWN));
        assertEquals(AgeBand.UNKNOWN, service.ageBand(404L), "查无此人也不是成年人");
    }

    @Test
    void sqlDateBirthdayDoesNotBlowUp() {
        UmsMember m = new UmsMember();
        m.setBirthday(java.sql.Date.valueOf(TODAY.minusYears(10)));
        when(members.selectUmsMemberById(77L)).thenReturn(m);
        assertEquals(AgeBand.AGE_8_15, service.ageBand(77L));
    }

    // ── 支付 ─────────────────────────────────────────────────────────

    @Test
    void unknownAgeNeverDefaultsToAdultPayment() {
        MinorDecision d = service.checkPurchase(UNKNOWN);
        assertFalse(d.allowed());
        assertEquals(MinorDecision.AGE_UNKNOWN, d.errorCode());
        assertTrue(service.guardianRequired(UNKNOWN));
    }

    @Test
    void under8CannotPayAloneEvenWithAuthorisation() {
        when(mapper.countActive(anyLong(), anyString(), anyString(), any())).thenReturn(1);
        MinorDecision d = service.checkPurchase(KID_6);
        assertFalse(d.allowed());
        assertEquals(MinorDecision.UNDER_8_GUARDIAN_PURCHASE, d.errorCode());
    }

    @Test
    void minorsNeedAnActivePurchaseAuthorisationUnderTheCurrentPolicyVersion() {
        assertEquals(MinorDecision.PURCHASE_CONSENT_REQUIRED, service.checkPurchase(KID_10).errorCode());
        assertEquals(MinorDecision.PURCHASE_CONSENT_REQUIRED, service.checkPurchase(TEEN_16).errorCode());

        when(mapper.countActive(eq(KID_10), eq("PURCHASE"), eq(MinorPolicy.POLICY_VERSION), any())).thenReturn(1);
        assertTrue(service.checkPurchase(KID_10).allowed());
        assertTrue(service.checkPurchase(ADULT).allowed());
    }

    @Test
    void purchaseConsentAndInfoProcessingConsentAreSeparate() {
        when(mapper.countActive(eq(KID_10), eq("PURCHASE"), anyString(), any())).thenReturn(1);
        when(mapper.countActive(eq(KID_10), eq("INFO_PROCESS"), anyString(), any())).thenReturn(0);

        assertTrue(service.checkPurchase(KID_10).allowed());
        MinorDecision info = service.checkInfoProcessing(KID_10);
        assertFalse(info.allowed(), "购买同意不能代替个人信息处理同意");
        assertEquals(MinorDecision.INFO_PROCESS_CONSENT_REQUIRED, info.errorCode());
    }

    // ── 城市定向 ─────────────────────────────────────────────────────

    @Test
    void age8To15PlaysOnlyDaytimeLowRiskRoutesWithAGuardianInTheTeam() {
        guardianOf(PARENT, KID_10);
        assertTrue(service.checkOrienteering(KID_10, play(true, 9, 12, KID_10, PARENT)).allowed());

        assertEquals(MinorDecision.ROUTE_NOT_LOW_RISK,
                service.checkOrienteering(KID_10, play(false, 9, 12, KID_10, PARENT)).errorCode());
        assertEquals(MinorDecision.CURFEW,
                service.checkOrienteering(KID_10, play(true, 17, 19, KID_10, PARENT)).errorCode());
        assertEquals(MinorDecision.GUARDIAN_IN_TEAM_REQUIRED,
                service.checkOrienteering(KID_10, play(true, 9, 12, KID_10, ADULT)).errorCode(),
                "同队有成年人但不是本人监护人，不算");
        when(mapper.countActive(eq(KID_10), eq("SOLO_DAYTIME_PLAY"), anyString(), any())).thenReturn(1);
        assertEquals(MinorDecision.GUARDIAN_IN_TEAM_REQUIRED,
                service.checkOrienteering(KID_10, play(true, 9, 12, KID_10)).errorCode(),
                "8-15 岁没有单独参与这条路");
    }

    @Test
    void under8AlsoNeedsTheGuardianInTheTeam() {
        guardianOf(PARENT, KID_6);
        assertTrue(service.checkOrienteering(KID_6, play(true, 9, 12, KID_6, PARENT)).allowed());
        assertEquals(MinorDecision.GUARDIAN_IN_TEAM_REQUIRED,
                service.checkOrienteering(KID_6, play(true, 9, 12, KID_6)).errorCode());
    }

    @Test
    void age16To17MayPlayDaytimeLowRiskRoutesAloneOnlyAfterAuthorisation() {
        assertEquals(MinorDecision.SOLO_AUTHORIZATION_REQUIRED,
                service.checkOrienteering(TEEN_16, play(true, 9, 12, TEEN_16)).errorCode());
        when(mapper.countActive(eq(TEEN_16), eq("SOLO_DAYTIME_PLAY"), eq(MinorPolicy.POLICY_VERSION), any())).thenReturn(1);
        assertTrue(service.checkOrienteering(TEEN_16, play(true, 9, 12, TEEN_16)).allowed());
        assertEquals(MinorDecision.CURFEW, service.checkOrienteering(TEEN_16, play(true, 20, 22, TEEN_16)).errorCode());
        assertEquals(MinorDecision.ROUTE_NOT_LOW_RISK,
                service.checkOrienteering(TEEN_16, play(false, 9, 12, TEEN_16)).errorCode());
    }

    @Test
    void adultsAreUnaffectedAndUnknownAgeIsRefused() {
        assertTrue(service.checkOrienteering(ADULT, play(false, 21, 23, ADULT)).allowed());
        assertEquals(MinorDecision.AGE_UNKNOWN, service.checkOrienteering(UNKNOWN, play(true, 9, 12, UNKNOWN)).errorCode());
    }

    @Test
    void curfewWindowIsSameDayDaytimeOnly() {
        LocalDateTime day = TODAY.atStartOfDay();
        assertTrue(service.withinDaytime(day.withHour(8), day.withHour(18)));
        assertFalse(service.withinDaytime(day.withHour(7).withMinute(59), day.withHour(10)));
        assertFalse(service.withinDaytime(day.withHour(10), day.withHour(18).withMinute(1)));
        assertFalse(service.withinDaytime(day.withHour(10), day.plusDays(1).withHour(10)), "跨天不算白天");
        assertFalse(service.withinDaytime(day.withHour(10), null), "结束时间未知不能确认白天");
        assertFalse(service.withinDaytime(day.withHour(12), day.withHour(10)));
    }

    // ── 开关 ─────────────────────────────────────────────────────────

    @Test
    void enforcementHooksAreNoOpsWhileTheSwitchesAreOff() {
        when(switches.minorOrienteeringGateOn()).thenReturn(false);

        assertTrue(service.enforceOrienteeringIfEnabled(KID_6, play(false, 22, 23, KID_6)).allowed());
        verifyNoInteractions(members, mapper);
    }

    @Test
    void enforcementHooksApplyTheRulesOnceSwitchedOn() {
        when(switches.minorOrienteeringGateOn()).thenReturn(true);

        assertEquals(MinorDecision.ROUTE_NOT_LOW_RISK,
                service.enforceOrienteeringIfEnabled(KID_10, play(false, 9, 12, KID_10)).errorCode());
    }

    // ── 夹具 ─────────────────────────────────────────────────────────

    private void born(long id, LocalDate birthday) {
        UmsMember m = new UmsMember();
        m.setBirthday(birthday == null ? null : Date.from(birthday.atStartOfDay(MinorPolicy.ZONE).toInstant()));
        when(members.selectUmsMemberById(id)).thenReturn(m);
    }

    private void guardianOf(long guardian, long ward) {
        when(mapper.countActiveBetween(eq(guardian), eq(ward), eq(MinorPolicy.POLICY_VERSION), any())).thenReturn(1);
    }

    private static OrienteeringParticipation play(boolean lowRisk, int fromHour, int toHour, Long... team) {
        LocalDateTime day = TODAY.atStartOfDay();
        return new OrienteeringParticipation(lowRisk, day.withHour(fromHour), day.withHour(toHour), List.of(team));
    }
}
