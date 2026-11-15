package com.chengyinhub.business.marketplace;

import com.chengyinhub.business.mapper.MarketplaceMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** R1 marketplace admin views: deals opt-in, promotion-slot purchases to decide on, sales leads (MKT-01/04/13). */
@Service
public class MarketplaceAdminService {
    public static final int DEFAULT_LEAD_DAYS = 90;
    public static final int MAX_LEAD_DAYS = 365;
    /** At least two different players before a place is listed, so nobody's private spot shows up. */
    public static final int MIN_LEAD_VISITORS = 2;
    public static final int MAX_LEAD_ROWS = 200;

    private final MarketplaceMapper mapper;

    @Autowired
    public MarketplaceAdminService(MarketplaceMapper mapper) {
        this.mapper = mapper;
    }

    public List<Map<String, Object>> deals() {
        return orEmpty(mapper.selectDealAdminList());
    }

    public void setDeal(Long couponId, boolean enabled, String operator) {
        if (couponId == null || couponId <= 0) throw new IllegalArgumentException("请填写优惠券编号");
        Integer listable = mapper.countListableCoupon(couponId);
        if (listable == null || listable == 0) throw new IllegalArgumentException("只能上架未删除的商家优惠券");
        mapper.upsertDeal(couponId, enabled ? 1 : 0, operator == null ? "" : operator);
    }

    public void removeDeal(Long couponId) {
        if (couponId == null || couponId <= 0) throw new IllegalArgumentException("请填写优惠券编号");
        mapper.deleteDeal(couponId);
    }

    public List<Map<String, Object>> promotionSlotPurchases() {
        return orEmpty(mapper.selectPromotionSlotPurchases());
    }

    public List<Map<String, Object>> salesLeads(Integer days, Integer minVisitors) {
        int window = clamp(days, DEFAULT_LEAD_DAYS, 1, MAX_LEAD_DAYS);
        int visitors = clamp(minVisitors, MIN_LEAD_VISITORS, MIN_LEAD_VISITORS, 1000);
        Date since = new Date(System.currentTimeMillis() - window * 86_400_000L);
        return orEmpty(mapper.selectSalesLeads(since, visitors, MAX_LEAD_ROWS));
    }

    static int clamp(Integer value, int fallback, int min, int max) {
        int v = value == null ? fallback : value;
        return Math.max(min, Math.min(max, v));
    }

    private static List<Map<String, Object>> orEmpty(List<Map<String, Object>> rows) {
        return rows == null ? Collections.emptyList() : rows;
    }
}
