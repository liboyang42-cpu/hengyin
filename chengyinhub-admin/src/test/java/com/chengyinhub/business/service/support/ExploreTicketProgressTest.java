package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.RegistrationEntitlement;
import com.chengyinhub.common.constant.PurchaseKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** P-03:③ 探索票核销一家后仍是进行中的票,必选章全核销或场次结束才算用完。 */
class ExploreTicketProgressTest {

    private static List<RegistrationEntitlement> ents(int... statuses) {
        List<RegistrationEntitlement> out = new ArrayList<>();
        for (int s : statuses) { RegistrationEntitlement e = new RegistrationEntitlement(); e.setStatus(s); out.add(e); }
        return out;
    }

    private static CmsRegistration explore(Map<String, Object> progress) {
        CmsRegistration r = new CmsRegistration();
        r.setPurchaseKind(PurchaseKind.EXPLORE_PASS);
        r.setPaymentStatus(2);
        r.setVerificationStatus(1);           // 第一家核销后 CAS 置 1(退款闸口径,不动)
        r.setFirstSuccessfulScanAt(new Date());
        r.setExploreProgress(progress);
        return r;
    }

    private final Date now = new Date();
    private final Date later = new Date(now.getTime() + 3600_000L);
    private final Date earlier = new Date(now.getTime() - 3600_000L);

    @Test void oneOfFourRedeemedStaysActive() {
        Map<String, Object> p = ExploreTicketProgress.of(ents(1, 0, 0, 0), false, later, now);
        assertEquals(1, p.get("redeemed"));
        assertEquals(4, p.get("required"));
        assertEquals(Boolean.TRUE, p.get("active"));
        assertFalse(ExploreTicketProgress.listCompleted(explore(p)));
    }

    @Test void allRedeemedIsCompleted() {
        Map<String, Object> p = ExploreTicketProgress.of(ents(1, 1, 1, 1), true, later, now);
        assertEquals(Boolean.FALSE, p.get("active"));
        assertTrue(ExploreTicketProgress.listCompleted(explore(p)));
    }

    @Test void sessionEndedIsCompletedEvenIfShopsLeft() {
        Map<String, Object> p = ExploreTicketProgress.of(ents(1, 0, 0, 0), false, earlier, now);
        assertEquals(Boolean.TRUE, p.get("ended"));
        assertTrue(ExploreTicketProgress.listCompleted(explore(p)));
    }

    @Test void invalidatedChaptersDoNotCount() {
        Map<String, Object> p = ExploreTicketProgress.of(ents(1, 2, 0), false, later, now);
        assertEquals(2, p.get("required"));
    }

    @Test void noEntitlementsKeepsOldRule() {
        assertNull(ExploreTicketProgress.of(ents(), false, later, now));
        assertTrue(ExploreTicketProgress.listCompleted(explore(null)));
        CmsRegistration city = new CmsRegistration();
        city.setPaymentStatus(2); city.setFirstSuccessfulScanAt(new Date());
        assertTrue(ExploreTicketProgress.listCompleted(city));
        CmsRegistration fresh = new CmsRegistration(); fresh.setPaymentStatus(2);
        assertFalse(ExploreTicketProgress.listCompleted(fresh));
    }
}
