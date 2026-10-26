package com.chengyinhub.business.service;

import com.chengyinhub.business.access.merchant.MerchantAccessContext;

import java.util.List;
import java.util.Map;

/**
 * Doc2 F4 商家工作台(后端)。调用方先用 {@code IMerchantAccessService.require} 拿到对应权限的上下文再进来;
 * 本服务内所有读写再按上下文里的 merchantId / ownerMemberId 二次收口,不接受客户端传归属。
 *
 * <p>写操作一律带 requestId:同键同指纹重放返回原回执,同键不同指纹 409,超时可用 {@link #receipt} 读回。
 * 本服务不做任何资金动作,对账/冻结只读。
 */
public interface IMerchantDeskService {

    Map<String, Object> receipt(MerchantAccessContext access, String requestId);

    List<Map<String, Object>> stores(MerchantAccessContext access);

    Map<String, Object> anchor(MerchantAccessContext access);

    Map<String, Object> saveAnchor(MerchantAccessContext access, Map<String, Object> body);

    Map<String, Object> checkAnchor(MerchantAccessContext access, Map<String, Object> body);

    List<Map<String, Object>> products(MerchantAccessContext access);

    Map<String, Object> productDetail(MerchantAccessContext access, Long productId);

    Map<String, Object> saveProduct(MerchantAccessContext access, Map<String, Object> body);

    Map<String, Object> delistProduct(MerchantAccessContext access, Map<String, Object> body);

    Map<String, Object> saveSku(MerchantAccessContext access, Map<String, Object> body);

    List<Map<String, Object>> capacitySlots(MerchantAccessContext access, Long skuId, String fromDate, Integer days);

    Map<String, Object> capacitySlotPage(MerchantAccessContext access, Long skuId, String fromDate, Integer days,
                                         Long afterStartAt, Long afterSlotId);

    Map<String, Object> arrivalsToday(MerchantAccessContext access);

    List<Map<String, Object>> reviews(MerchantAccessContext access, Integer status);

    Map<String, Object> reviewDetail(MerchantAccessContext access, Long reviewId);

    Map<String, Object> submitReviewEvidence(MerchantAccessContext access, Map<String, Object> body);

    Map<String, Object> reconciliationSummary(MerchantAccessContext access);

    Map<String, Object> reconciliationClaims(MerchantAccessContext access, String route, Integer pageNum, Integer pageSize);

    List<Map<String, Object>> frozenBatches(MerchantAccessContext access);

    Map<String, Object> frozenBatchPage(MerchantAccessContext access, String afterPeriodYm, Long afterBatchId);
}
