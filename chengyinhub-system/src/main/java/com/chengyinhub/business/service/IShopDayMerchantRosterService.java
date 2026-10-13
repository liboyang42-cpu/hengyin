package com.chengyinhub.business.service;

import java.util.Map;

public interface IShopDayMerchantRosterService {
    /** 两参默认不下发手机号；有权调用方必须显式使用三参重载。 */
    Map<String, Object> list(Long merchantMemberId, Long topicId);

    Map<String, Object> list(Long merchantMemberId, Long topicId, boolean sensitiveContactVisible);
}
