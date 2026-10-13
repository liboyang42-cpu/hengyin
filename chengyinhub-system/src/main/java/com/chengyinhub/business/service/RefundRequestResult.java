package com.chengyinhub.business.service;

/** 微信退款发起结果；UNKNOWN 绝不能降级为确定拒绝或人工退款。 */
public enum RefundRequestResult {
    ACCEPTED,
    DEFINITIVE_FAILED,
    UNKNOWN
}
