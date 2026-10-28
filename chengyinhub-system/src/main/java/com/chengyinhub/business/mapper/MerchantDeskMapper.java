package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Doc2 F4 商家工作台 SQL。
 *
 * <p>归属口径(两套 id,别混):
 * <ul>
 *   <li>{@code merchantId} = mms_merchant.id:门店、pms_product / pms_sku、预约规则、核销锚点</li>
 *   <li>{@code ownerMemberId} = 商家 Owner memberId:redemption / merchant_chapter_offer / settlement_claim /
 *       merchant_payable(_batch) / verification_review / 回执</li>
 * </ul>
 * 每一条语句的 WHERE 都带其中之一(由 {@code MerchantDeskMapperSqlTest} 钉住),没有「只按 id」的读写。
 */
public interface MerchantDeskMapper {

    // ---- 回执 ----
    Map<String, Object> selectReceipt(@Param("ownerMemberId") Long ownerMemberId, @Param("requestId") String requestId);

    int insertReceipt(Map<String, Object> receipt);

    Long selectReceiptSkuProductId(@Param("merchantId") Long merchantId, @Param("skuId") Long skuId);

    Long selectReceiptEvidenceReviewId(@Param("ownerMemberId") Long ownerMemberId, @Param("evidenceId") Long evidenceId);

    // ---- 门店 ----
    Map<String, Object> selectStore(@Param("merchantId") Long merchantId);

    Map<String, Object> selectAnchor(@Param("merchantId") Long merchantId);

    int insertAnchor(Map<String, Object> anchor);

    int updateAnchor(Map<String, Object> anchor);

    // ---- 商品 / SKU / 容量 ----
    List<Map<String, Object>> selectProducts(@Param("merchantId") Long merchantId);

    Map<String, Object> selectProduct(@Param("merchantId") Long merchantId, @Param("productId") Long productId);

    Map<String, Object> selectProductForUpdate(@Param("merchantId") Long merchantId, @Param("productId") Long productId);

    int insertProduct(Map<String, Object> product);

    int updateProduct(Map<String, Object> product);

    int delistProduct(@Param("merchantId") Long merchantId, @Param("productId") Long productId,
                      @Param("updateBy") String updateBy, @Param("updateTime") Date updateTime);

    List<Map<String, Object>> selectSkus(@Param("merchantId") Long merchantId, @Param("productId") Long productId);

    Map<String, Object> selectSkuForUpdate(@Param("merchantId") Long merchantId, @Param("skuId") Long skuId);

    int insertSku(Map<String, Object> sku);

    int updateSku(Map<String, Object> sku);

    Map<String, Object> selectBookingRule(@Param("merchantId") Long merchantId, @Param("skuId") Long skuId);

    int insertBookingRule(Map<String, Object> rule);

    int updateBookingRule(Map<String, Object> rule);

    List<Map<String, Object>> selectCapacitySlots(@Param("merchantId") Long merchantId, @Param("skuId") Long skuId,
                                                  @Param("from") Date from, @Param("to") Date to);

    List<Map<String, Object>> selectCapacitySlotPage(@Param("merchantId") Long merchantId, @Param("skuId") Long skuId,
            @Param("from") Date from, @Param("to") Date to, @Param("afterStart") Date afterStart,
            @Param("afterSlotId") Long afterSlotId);

    // ---- 今日到店 ----
    List<Map<String, Object>> selectRedeemedBetween(@Param("ownerMemberId") Long ownerMemberId,
                                                    @Param("from") Date from, @Param("to") Date to);

    List<Map<String, Object>> selectOpenEntitlementsByLiveOffer(@Param("ownerMemberId") Long ownerMemberId);

    // ---- 复核结论 / 补充材料 ----
    List<Map<String, Object>> selectReviews(@Param("ownerMemberId") Long ownerMemberId, @Param("status") Integer status);

    Map<String, Object> selectReview(@Param("ownerMemberId") Long ownerMemberId, @Param("reviewId") Long reviewId);

    Map<String, Object> selectReviewForUpdate(@Param("ownerMemberId") Long ownerMemberId, @Param("reviewId") Long reviewId);

    List<Map<String, Object>> selectEvidence(@Param("ownerMemberId") Long ownerMemberId, @Param("reviewId") Long reviewId);

    int insertEvidence(Map<String, Object> evidence);

    // ---- 对账(只读) ----
    List<Map<String, Object>> selectClaimSummary(@Param("ownerMemberId") Long ownerMemberId);

    List<Map<String, Object>> selectClaims(@Param("ownerMemberId") Long ownerMemberId, @Param("route") String route,
                                           @Param("offset") int offset, @Param("limit") int limit);

    Map<String, Object> selectPayableClaimCoverage(@Param("ownerMemberId") Long ownerMemberId);

    List<Map<String, Object>> selectFrozenBatches(@Param("ownerMemberId") Long ownerMemberId);

    List<Map<String, Object>> selectFrozenBatchPage(@Param("ownerMemberId") Long ownerMemberId,
            @Param("afterPeriodYm") String afterPeriodYm, @Param("afterBatchId") Long afterBatchId);
}
