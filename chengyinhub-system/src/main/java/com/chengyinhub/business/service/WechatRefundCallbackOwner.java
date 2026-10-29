package com.chengyinhub.business.service;

import java.util.Map;

/** Calls the existing refund owner's transaction; it does not infer payout or refund policy. */
@FunctionalInterface
public interface WechatRefundCallbackOwner {
    boolean apply(Map<String, String> verifiedFacts);
}
