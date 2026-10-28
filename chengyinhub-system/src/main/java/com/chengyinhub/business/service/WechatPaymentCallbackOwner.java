package com.chengyinhub.business.service;

import java.util.Map;

/** Verified facts in, existing domain transaction out. SUCCESS means its own durable mutation committed. */
public interface WechatPaymentCallbackOwner {
    String route();
    boolean claims(String businessOutTradeNo);
    String apply(Map<String, String> verifiedFacts);
}
