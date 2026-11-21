package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.SmsCoupon;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

/** R1 marketplace: opt-in deals layer, deal claims and the admin market lists. */
public interface MarketplaceMapper {

    /** Enabled, issuable deals whose merchant has a published city node inside the box. */
    List<Map<String, Object>> selectDealsInBox(@Param("minLat") double minLat, @Param("maxLat") double maxLat,
                                               @Param("minLng") double minLng, @Param("maxLng") double maxLng,
                                               @Param("memberId") Long memberId, @Param("limit") int limit);

    /** The coupon behind an enabled deal, locked for the claim; null when it is not an enabled public deal. */
    SmsCoupon selectEnabledDealCouponForUpdate(@Param("couponId") Long couponId);

    int insertClaimIgnore(@Param("couponId") Long couponId, @Param("memberId") Long memberId);

    Long selectClaimHistoryId(@Param("couponId") Long couponId, @Param("memberId") Long memberId);

    int markClaimDelivered(@Param("couponId") Long couponId, @Param("memberId") Long memberId,
                           @Param("historyId") Long historyId);

    // ---------- admin ----------

    List<Map<String, Object>> selectDealAdminList();

    int upsertDeal(@Param("couponId") Long couponId, @Param("enabled") int enabled,
                   @Param("operator") String operator);

    int deleteDeal(@Param("couponId") Long couponId);

    /** Merchant coupon that may be listed as a deal (exists, merchant-scoped, not deleted). */
    Integer countListableCoupon(@Param("couponId") Long couponId);

    /** Paid promotion-slot orders (status 1) with their refund state; nothing reads the entitlement. */
    List<Map<String, Object>> selectPromotionSlotPurchases();

    /** Aggregated check-ins at places outside the programme; no member identifiers leave the query. */
    List<Map<String, Object>> selectSalesLeads(@Param("since") Date since, @Param("minVisitors") int minVisitors,
                                               @Param("limit") int limit);

    // ---------- storefront ----------

    List<Map<String, Object>> selectPublishedProducts(@Param("merchantId") Long merchantId);

    List<Map<String, Object>> selectPublishedSkus(@Param("merchantId") Long merchantId);
}
