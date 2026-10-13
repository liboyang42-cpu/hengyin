package com.chengyinhub.common.constant;

import java.math.BigDecimal;

/**
 * 活动报名模块常量定义
 *
 * @author webxh
 * @date 2025-09-04
 */
public class CmsConstants {
    /**
     * 积分抵扣比例：1积分 = 0.01元
     */
    public static final BigDecimal POINT_DEDUCT_RATE = new BigDecimal("0.02");

    /**
     * 最大积分抵扣比例：不超过总金额的80%（若同时有比例和金额限制，取两者最小值）
     */
    public static final BigDecimal MAX_DEDUCT_RATIO = new BigDecimal("0.8");

    // 新增：积分抵扣最大金额（50元）
    /**
     * 积分抵扣最大金额限制：50元（无论积分多少，单次最多抵扣50元）
     */
    public static final BigDecimal MAX_POINT_DEDUCT_AMOUNT = new BigDecimal("50.00");

    /**
     * 报价签名盐（M0-1 实扣报价一致）。仅服务端使用、绝不下发客户端；
     * 客户端只回传 quote 接口返回的 quoteSign，不参与签名计算。用于报价防篡改 + 陈旧报价检测。
     */
    public static final String QUOTE_SIGN_SALT = "cy#quote$2026@salt!v1";

    /**
     * 优惠券动态二维码有效期（秒）。前端 60s 刷新，服务端留 90s 容差防扫码延迟误杀。
     */
    public static final long COUPON_QR_TTL_SEC = 90L;

}
