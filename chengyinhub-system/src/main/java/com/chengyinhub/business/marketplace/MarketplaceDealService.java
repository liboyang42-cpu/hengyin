package com.chengyinhub.business.marketplace;

import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.mapper.MarketplaceMapper;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R1 marketplace deals layer (MKT-04). Players opt in on the map; an operator opts each merchant coupon in.
 * Paid placement never reaches the fog map: this service is not read by fog, pins or their ordering.
 */
@Service
public class MarketplaceDealService {
    /** Largest box side in degrees (~55 km north-south): one city view, never a country scrape. */
    public static final double MAX_SPAN_DEGREES = 0.5;
    public static final int MAX_ROWS = 100;

    private final MarketplaceMapper mapper;
    private final ISmsCouponHistoryService couponHistoryService;

    @Autowired
    public MarketplaceDealService(MarketplaceMapper mapper, ISmsCouponHistoryService couponHistoryService) {
        this.mapper = mapper;
        this.couponHistoryService = couponHistoryService;
    }

    public List<Map<String, Object>> dealsInBox(Double minLat, Double maxLat, Double minLng, Double maxLng, Long memberId) {
        requireBox(minLat, maxLat, minLng, maxLng);
        List<Map<String, Object>> rows = mapper.selectDealsInBox(minLat, maxLat, minLng, maxLng,
                memberId != null && memberId > 0 ? memberId : null, MAX_ROWS);
        if (rows == null) return Collections.emptyList();
        for (Map<String, Object> row : rows) row.put("claimed", truthy(row.get("claimed")));
        return rows;
    }

    /**
     * One claim per member per offer; stock and the issue window are enforced by the existing atomic issue path.
     * The claim row and the coupon history are written in one transaction, so a refused issue leaves no claim.
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> claim(Long memberId, Long couponId) {
        if (memberId == null || memberId <= 0) throw new DealClaimException(DealClaimException.Reason.SIGN_IN, "请先登录");
        if (couponId == null || couponId <= 0) throw new DealClaimException(DealClaimException.Reason.UNAVAILABLE, "该优惠暂不可领取");
        SmsCoupon coupon = mapper.selectEnabledDealCouponForUpdate(couponId);
        if (coupon == null) throw new DealClaimException(DealClaimException.Reason.UNAVAILABLE, "该优惠暂不可领取");
        if (mapper.insertClaimIgnore(couponId, memberId) == 0) {
            Long existing = mapper.selectClaimHistoryId(couponId, memberId);
            if (existing != null) return receipt(couponId, existing, true);
            // A row without a history id cannot survive a commit (same transaction); fall through and deliver it.
        }
        Long historyId = couponHistoryService.grantCouponWithReceipt(memberId, coupon);
        if (historyId == null) {
            throw new DealClaimException(DealClaimException.Reason.SOLD_OUT, "这张优惠已领完或不在领取时间");
        }
        if (mapper.markClaimDelivered(couponId, memberId, historyId) != 1) {
            throw new IllegalStateException("优惠领取记录写入失败");
        }
        return receipt(couponId, historyId, false);
    }

    static void requireBox(Double minLat, Double maxLat, Double minLng, Double maxLng) {
        if (minLat == null || maxLat == null || minLng == null || maxLng == null
                || !Double.isFinite(minLat) || !Double.isFinite(maxLat) || !Double.isFinite(minLng) || !Double.isFinite(maxLng)
                || minLat < -90 || maxLat > 90 || minLng < -180 || maxLng > 180
                || minLat >= maxLat || minLng >= maxLng) {
            throw new IllegalArgumentException("地图范围无效");
        }
        // Clients clamp each side to 0.25° around a center; allow floating-point rounding on that sum.
        if (maxLat - minLat > MAX_SPAN_DEGREES + 1e-9 || maxLng - minLng > MAX_SPAN_DEGREES + 1e-9) {
            throw new IllegalArgumentException("地图范围太大，请放大地图后再看优惠");
        }
    }

    private static Map<String, Object> receipt(Long couponId, Long historyId, boolean alreadyClaimed) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("couponId", couponId);
        out.put("historyId", historyId);
        out.put("alreadyClaimed", alreadyClaimed);
        return out;
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        return value != null && "true".equalsIgnoreCase(String.valueOf(value));
    }
}
