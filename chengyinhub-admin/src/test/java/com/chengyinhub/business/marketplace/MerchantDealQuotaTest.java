package com.chengyinhub.business.marketplace;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.merchantgap.MerchantGapException;
import com.chengyinhub.business.merchantplans.MerchantPlan;
import com.chengyinhub.business.merchantplans.MerchantPlanService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** T5: plan feature dealsOffersPerMonth. Free (quota 0) = today: a shop cannot put a coupon into the deals layer. */
class MerchantDealQuotaTest {
    static final long MERCHANT = 70L;

    JdbcTemplate jdbc = mock(JdbcTemplate.class);

    MerchantDealQuotaService service(Object quota) {
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(anyLong(), anyString())).thenReturn(new MerchantAccessContext(7L, MERCHANT, 7L, "OWNER"));
        MerchantDealQuotaService s = new MerchantDealQuotaService(jdbc, access);
        if (quota != null) {
            MerchantPlanService plans = mock(MerchantPlanService.class);
            if (quota instanceof RuntimeException e) when(plans.feature(anyLong(), anyString())).thenThrow(e);
            else when(plans.feature(MERCHANT, MerchantPlan.F_DEALS_OFFERS_PER_MONTH)).thenReturn(quota);
            s.plans(plans);
        }
        return s;
    }

    void counts(int own, int listed, int used) {
        when(jdbc.queryForObject(startsWith("SELECT COUNT(*) FROM sms_coupon"), eq(Integer.class), any(Object[].class))).thenReturn(own);
        when(jdbc.queryForObject(startsWith("SELECT COUNT(*) FROM marketplace_deal WHERE"), eq(Integer.class), any(Object[].class))).thenReturn(listed);
        when(jdbc.queryForObject(startsWith("SELECT COUNT(*) FROM marketplace_deal d"), eq(Integer.class), any(Object[].class))).thenReturn(used);
    }

    @Test
    void freeMissingOrBrokenPlansKeepTodaysBehaviour() {
        for (Object quota : new Object[] {0, null, new RuntimeException("db down")}) {
            MerchantDealQuotaService s = service(quota);
            MerchantGapException e = assertThrows(MerchantGapException.class, () -> s.submit(7L, Map.of("couponId", 5)));
            assertEquals("PLAN_FEATURE_NOT_INCLUDED", e.getErrorCode());
            assertEquals(0, s.view(7L).get("quota"));
        }
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void aPaidPlanSubmitsPausedWithinTheMonthlyQuota() {
        counts(1, 0, 1);
        Map<String, Object> out = service(2).submit(7L, Map.of("couponId", 5));
        assertEquals("WAITING_FOR_REVIEW", out.get("state"));
        verify(jdbc).update(contains("VALUES (?, 0, 0, ?, NOW(), ?, NOW())"), eq(5L), eq("merchant:70"), eq("merchant:70"));
    }

    @Test
    void theQuotaIsEnforced() {
        counts(1, 0, 2);
        assertEquals("DEAL_QUOTA_USED", assertThrows(MerchantGapException.class,
                () -> service(2).submit(7L, Map.of("couponId", 5))).getErrorCode());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void onlyOwnUnlistedCoupons() {
        counts(0, 0, 0);
        assertEquals("COUPON_NOT_FOUND", assertThrows(MerchantGapException.class,
                () -> service(2).submit(7L, Map.of("couponId", 5))).getErrorCode());
        counts(1, 1, 0);
        assertEquals("DEAL_ALREADY_SUBMITTED", assertThrows(MerchantGapException.class,
                () -> service(2).submit(7L, Map.of("couponId", 5))).getErrorCode());
        assertThrows(MerchantGapException.class, () -> service(2).submit(7L, Map.of("couponId", 5, "x", 1)));
        assertThrows(MerchantGapException.class, () -> service(2).submit(7L, Map.of("couponId", "5")));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void theMonthRunsInShanghai() {
        assertEquals("2026-11-30T16:00:00Z",
                MerchantDealQuotaService.monthStart(Instant.parse("2026-11-30T16:30:00Z")).toInstant().toString());
    }
}
