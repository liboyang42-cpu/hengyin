package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.service.support.TicketUsableWindow;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.PurchaseKind;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** F13 票夹:usableFrom / usableUntil / usableState / clearState 由服务端按 Asia/Shanghai 算好下发。 */
class RegistrationQueryUsableWindowTest {
    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");

    @Test
    void topicTicketUsesTopicDatesAtShanghaiDayBoundaries() {
        CmsRegistration row = topicRow(ProductType.ORIENTEERING, day(2026, 12, 1), day(2026, 12, 31));
        TicketUsableWindow.stamp(row, at(2026, 11, 30, 23, 59));
        assertEquals("2026-12-01 00:00:00", row.getUsableFrom());
        assertEquals("2026-12-31 23:59:59", row.getUsableUntil());
        assertEquals(TicketUsableWindow.UPCOMING, row.getUsableState());

        TicketUsableWindow.stamp(row, at(2026, 12, 1, 0, 0));
        assertEquals(TicketUsableWindow.USABLE, row.getUsableState());
        TicketUsableWindow.stamp(row, at(2026, 12, 31, 23, 59));
        assertEquals(TicketUsableWindow.USABLE, row.getUsableState());
        TicketUsableWindow.stamp(row, at(2027, 1, 1, 0, 0));
        assertEquals(TicketUsableWindow.EXPIRED, row.getUsableState());
    }

    @Test
    void passHasNoStartAndUsesExpiresAt() {
        CmsRegistration row = topicRow(ProductType.ORIENTEERING, day(2026, 12, 1), day(2026, 12, 31));
        row.setExpiresAt(at(2027, 2, 28, 10, 0));
        TicketUsableWindow.stamp(row, at(2026, 11, 20, 9, 0));
        assertNull(row.getUsableFrom());
        assertEquals("2027-02-28 10:00:00", row.getUsableUntil());
        assertEquals(TicketUsableWindow.USABLE, row.getUsableState());
    }

    @Test
    void topicWithoutEndDateNeverExpires() {
        CmsRegistration row = topicRow(ProductType.ORIENTEERING, null, null);
        TicketUsableWindow.stamp(row, at(2030, 1, 1, 0, 0));
        assertNull(row.getUsableFrom());
        assertNull(row.getUsableUntil());
        assertEquals(TicketUsableWindow.USABLE, row.getUsableState());
    }

    @Test
    void sessionStartingLaterTodayStaysGreyUntilTheExactMinute() {
        CmsRegistration row = new CmsRegistration();
        row.setOwnerType(2L);
        CmsActivity activity = new CmsActivity();
        activity.setStartDate(at(2026, 11, 18, 14, 30));
        activity.setEndDate(at(2026, 11, 18, 18, 0));
        row.setCmsActivity(activity);
        TicketUsableWindow.stamp(row, at(2026, 11, 18, 14, 29));
        assertEquals("2026-11-18 14:30:00", row.getUsableFrom());
        assertEquals(TicketUsableWindow.UPCOMING, row.getUsableState());
        TicketUsableWindow.stamp(row, at(2026, 11, 18, 14, 30));
        assertEquals(TicketUsableWindow.USABLE, row.getUsableState());
        assertEquals(TicketUsableWindow.NONE, row.getClearState());
    }

    @Test
    void explorePassIsImmediatelyUsableAndHasNoClearState() {
        CmsRegistration row = new CmsRegistration();
        row.setOwnerType(2L);
        row.setPurchaseKind(PurchaseKind.EXPLORE_PASS);
        CmsActivity activity = new CmsActivity();
        activity.setStartDate(at(2026, 12, 1, 9, 0));
        activity.setEndDate(at(2026, 12, 2, 18, 0));
        row.setCmsActivity(activity);
        TicketUsableWindow.stamp(row, at(2026, 11, 18, 9, 0));
        assertNull(row.getUsableFrom());
        assertEquals(TicketUsableWindow.USABLE, row.getUsableState());
        assertEquals(TicketUsableWindow.NONE, row.getClearState());
    }

    @Test
    void clearStateIsALabelOnlyForCityOrienteering() {
        CmsRegistration city = topicRow(ProductType.ORIENTEERING, null, null);
        TicketUsableWindow.stamp(city, at(2026, 11, 18, 9, 0));
        assertEquals(TicketUsableWindow.NOT_CLEARED, city.getClearState());
        city.setTopicCompleted(Boolean.TRUE);
        // 已通关且已核销的票仍然可用(不限次数),只是标签变了
        city.setVerificationStatus(1);
        TicketUsableWindow.stamp(city, at(2026, 11, 18, 9, 0));
        assertEquals(TicketUsableWindow.CLEARED, city.getClearState());
        assertEquals(TicketUsableWindow.USABLE, city.getUsableState());

        CmsRegistration explore = topicRow(ProductType.FREE_EXPLORE, null, null);
        TicketUsableWindow.stamp(explore, at(2026, 11, 18, 9, 0));
        assertEquals(TicketUsableWindow.NONE, explore.getClearState());
    }

    private static CmsRegistration topicRow(int productType, Date start, Date end) {
        CmsRegistration row = new CmsRegistration();
        row.setOwnerType(1L);
        CmsTopic topic = new CmsTopic();
        topic.setProductType(productType);
        topic.setStartDate(start);
        topic.setEndDate(end);
        row.setCmsTopic(topic);
        return row;
    }

    private static Date day(int y, int m, int d) {
        return at(y, m, d, 0, 0);
    }

    private static Date at(int y, int m, int d, int h, int min) {
        return Date.from(LocalDateTime.of(y, m, d, h, min).atZone(SH).toInstant());
    }
}
