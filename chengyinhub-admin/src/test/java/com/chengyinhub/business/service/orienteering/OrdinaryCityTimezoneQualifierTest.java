package com.chengyinhub.business.service.orienteering;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.service.orienteering.PublishedRuleSelector.FrozenPublishedRule;
import com.chengyinhub.business.service.support.OrientSafetyPolicy;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrdinaryCityTimezoneQualifierTest {

    private final OrdinaryCityTimezoneQualifier qualifier = new OrdinaryCityTimezoneQualifier();
    private final PublishedRuleSelector selector = new PublishedRuleSelector();

    @Test
    void cityTimezoneIsAuthoritativeAndClientZoneCannotOverride() {
        CmsTopic course = PublishedRuleSelectorTest.publishedCity(7L, 9L);
        Club club = new Club();
        club.setCity("上海");
        assertEquals(OrdinaryCityTimezoneQualifier.CITY_ZONE, qualifier.qualify(course, club, null));
        assertEquals(OrienteeringEventException.TIMEZONE_NOT_CITY,
                assertThrows(OrienteeringEventException.class,
                        () -> qualifier.qualify(course, club, ZoneId.of("America/Los_Angeles"))).getErrorCode());
    }

    @Test
    void perItemConfirmRejectsBlanketAgreeAndRequiresEveryItem() {
        FrozenPublishedRule frozen = selector.select(PublishedRuleSelectorTest.publishedCity(7L, 9L));
        Date start = at(2026, 10, 24, 10, 0);
        OrienteeringEventException blanket = assertThrows(OrienteeringEventException.class,
                () -> qualifier.confirm(frozen, OrdinaryCityTimezoneQualifier.CITY_ZONE, List.of("ALL"),
                        null, start, at(2026, 10, 24, 15, 0), start, at(1990, 1, 1, 0, 0), false));
        assertEquals(OrienteeringEventException.SAFETY_UNCONFIRMED, blanket.getErrorCode());

        OrienteeringEventException missing = assertThrows(OrienteeringEventException.class,
                () -> qualifier.confirm(frozen, OrdinaryCityTimezoneQualifier.CITY_ZONE,
                        List.of(OrdinaryCityTimezoneQualifier.ITEM_PUBLISHED_RULES),
                        null, start, at(2026, 10, 24, 15, 0), start, at(1990, 1, 1, 0, 0), false));
        assertEquals(OrienteeringEventException.SAFETY_UNCONFIRMED, missing.getErrorCode());

        OrdinaryCityTimezoneQualifier.SafetyConfirmation ok = qualifier.confirm(frozen,
                OrdinaryCityTimezoneQualifier.CITY_ZONE, adultDayItems(), null, start,
                at(2026, 10, 24, 15, 0), start, at(1990, 1, 1, 0, 0), false);
        assertTrue(ok.complete());
        assertEquals(7L, ok.frozenRuleVersion);
        assertEquals(frozen.digest, ok.frozenRuleDigest);
    }

    @Test
    void safetyBlockersCannotBeConfirmedThrough() {
        FrozenPublishedRule frozen = selector.select(PublishedRuleSelectorTest.publishedCity(7L, 9L));
        Date night = at(2026, 10, 24, 22, 0);
        OrienteeringEventException blocked = assertThrows(OrienteeringEventException.class,
                () -> qualifier.confirm(frozen, OrdinaryCityTimezoneQualifier.CITY_ZONE, adultDayItems(),
                        null, night, night, night, at(2012, 6, 1, 0, 0), true));
        assertEquals(OrientSafetyPolicy.MINOR_NIGHT_FORBIDDEN, blocked.getErrorCode());
    }

    static List<String> adultDayItems() {
        return List.of(OrdinaryCityTimezoneQualifier.ITEM_PUBLISHED_RULES,
                OrdinaryCityTimezoneQualifier.ITEM_CITY_TIMEZONE,
                OrdinaryCityTimezoneQualifier.ITEM_DAY_NIGHT,
                OrdinaryCityTimezoneQualifier.ITEM_WEATHER,
                OrdinaryCityTimezoneQualifier.ITEM_INSURANCE_OPTIONAL);
    }

    static Date at(int year, int month, int day, int hour, int minute) {
        return Date.from(LocalDateTime.of(year, month, day, hour, minute)
                .atZone(OrdinaryCityTimezoneQualifier.CITY_ZONE).toInstant());
    }
}
