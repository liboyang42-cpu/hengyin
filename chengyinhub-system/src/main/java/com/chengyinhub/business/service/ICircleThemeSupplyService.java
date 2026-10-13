package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MerchantCircleSupplyProfile;

import java.util.List;

/** 玩家可见的圈层城市实例供给。 */
public interface ICircleThemeSupplyService {
    /** 少于3家不同有效商户时返回空列表，等价于自动隐藏该实例。 */
    List<MerchantCircleSupplyProfile> listPublicSupplies(Long topicId);
}
