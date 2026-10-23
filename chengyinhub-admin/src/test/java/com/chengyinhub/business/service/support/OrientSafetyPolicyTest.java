package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.OrientSafetyRule;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrientSafetyPolicyTest {

    private static final Date ADULT = at(1990, 1, 1, 0, 0);
    private static final Date MINOR = at(2012, 6, 1, 0, 0);

    @Test
    void daySessionAdultIsAllowedAndInsuranceIsNeverRequired() {
        OrientSafetyPolicy.Decision d = OrientSafetyPolicy.evaluate(null, at(2026, 10, 24, 10, 0),
                at(2026, 10, 24, 15, 0), at(2026, 10, 24, 9, 0), ADULT, false);
        assertTrue(d.allowed());
        assertFalse(d.night);
        assertFalse(d.insuranceRequired());

        OrientSafetyRule rule = new OrientSafetyRule();
        rule.setInsuranceRequired(1); // 即便库里被手改成 1,也不能变成阻断项
        assertTrue(OrientSafetyPolicy.evaluate(rule, at(2026, 10, 24, 10, 0), null,
                at(2026, 10, 24, 9, 0), ADULT, false).allowed());
    }

    @Test
    void minorsCannotEnterNightSessionsEvenWithGuardian() {
        OrientSafetyPolicy.Decision d = OrientSafetyPolicy.evaluate(null, at(2026, 10, 24, 19, 0),
                at(2026, 10, 24, 21, 0), at(2026, 10, 24, 9, 0), MINOR, true);
        assertFalse(d.allowed());
        assertTrue(d.night);
        assertEquals(OrientSafetyPolicy.MINOR_NIGHT_FORBIDDEN, d.blockers.get(0).get("code"));
    }

    @Test
    void unknownBirthdayIsBlockedAtNightButNotDuringTheDay() {
        assertEquals(OrientSafetyPolicy.AGE_UNVERIFIED_NIGHT, OrientSafetyPolicy.evaluate(null,
                at(2026, 10, 24, 2, 0), null, at(2026, 10, 24, 1, 0), null, false).blockers.get(0).get("code"));
        assertTrue(OrientSafetyPolicy.evaluate(null, at(2026, 10, 24, 10, 0), null,
                at(2026, 10, 24, 9, 0), null, false).allowed());
    }

    @Test
    void minorsNeedGuardianForDaySessions() {
        OrientSafetyPolicy.Decision noGuardian = OrientSafetyPolicy.evaluate(null, at(2026, 10, 24, 10, 0), null,
                at(2026, 10, 24, 9, 0), MINOR, false);
        assertEquals(OrientSafetyPolicy.GUARDIAN_REQUIRED, noGuardian.blockers.get(0).get("code"));
        assertTrue(OrientSafetyPolicy.evaluate(null, at(2026, 10, 24, 10, 0), null,
                at(2026, 10, 24, 9, 0), MINOR, true).allowed());
    }

    @Test
    void ageIsJudgedAtSessionStartNotToday() {
        Date turns18 = at(2008, 10, 25, 0, 0);
        assertEquals(OrientSafetyPolicy.GUARDIAN_REQUIRED, OrientSafetyPolicy.evaluate(null,
                at(2026, 10, 24, 10, 0), null, at(2026, 10, 1, 9, 0), turns18, false).blockers.get(0).get("code"));
        assertTrue(OrientSafetyPolicy.evaluate(null, at(2026, 10, 25, 10, 0), null,
                at(2026, 10, 1, 9, 0), turns18, false).allowed());
    }

    @Test
    void weatherBlockStopsPlayUntilItExpires() {
        OrientSafetyRule rule = new OrientSafetyRule();
        rule.setWeatherBlock(1);
        rule.setWeatherReason("台风橙色预警");
        rule.setWeatherBlockUntil(at(2026, 10, 24, 12, 0));
        OrientSafetyPolicy.Decision during = OrientSafetyPolicy.evaluate(rule, at(2026, 10, 24, 10, 0), null,
                at(2026, 10, 24, 10, 0), ADULT, false);
        assertEquals(OrientSafetyPolicy.WEATHER_BLOCKED, during.blockers.get(0).get("code"));
        assertEquals("台风橙色预警", during.blockers.get(0).get("message"));
        assertTrue(OrientSafetyPolicy.evaluate(rule, at(2026, 10, 24, 13, 0), null,
                at(2026, 10, 24, 13, 0), ADULT, false).allowed());
    }

    @Test
    void nightWindowHandlesMidnightWrapAndCustomWindows() {
        assertTrue(OrientSafetyPolicy.overlapsNight(at(2026, 10, 24, 17, 0), at(2026, 10, 24, 20, 30), 1200, 360));
        assertTrue(OrientSafetyPolicy.overlapsNight(at(2026, 10, 24, 5, 0), at(2026, 10, 24, 5, 30), 1200, 360));
        assertFalse(OrientSafetyPolicy.overlapsNight(at(2026, 10, 24, 6, 0), at(2026, 10, 24, 19, 59), 1200, 360));
        assertTrue(OrientSafetyPolicy.overlapsNight(at(2026, 10, 24, 10, 0), at(2026, 10, 26, 10, 0), 1200, 360),
                "跨天场次必然经过夜间");
        assertTrue(OrientSafetyPolicy.overlapsNight(at(2026, 10, 24, 22, 30), at(2026, 10, 24, 22, 30), 1320, 1380),
                "不跨午夜的自定义窗口 22:00-23:00");
        assertFalse(OrientSafetyPolicy.overlapsNight(at(2026, 10, 24, 23, 0), at(2026, 10, 24, 23, 0), 600, 600),
                "起止相同 = 不设夜间");
    }

    @Test
    void sosIgnoresReputationByConstruction() throws Exception {
        assertTrue(OrientSafetyPolicy.sosAllowed());
        Method sos = OrientSafetyPolicy.class.getMethod("sosAllowed");
        assertEquals(0, sos.getParameterCount(), "SOS 判定不得接受信誉/处罚等任何输入");
        OrientSafetyRule rule = new OrientSafetyRule();
        rule.setWeatherBlock(1);
        OrientSafetyPolicy.Decision blocked = OrientSafetyPolicy.evaluate(rule, at(2026, 10, 24, 2, 0), null,
                at(2026, 10, 24, 2, 0), MINOR, false);
        assertFalse(blocked.allowed());
        assertTrue(blocked.sosAvailable(), "被安全闸拦下时 SOS 仍然可用");
    }

    private static Date at(int y, int mo, int d, int h, int mi) {
        return Date.from(LocalDateTime.of(y, mo, d, h, mi).atZone(OrientSafetyPolicy.ZONE).toInstant());
    }
}
