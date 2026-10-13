package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.CityNodeVO;

/** 商家装修与玩家搜索节点之间的索引同步、公开节点读取。 */
public interface IMerchantNodeDiscoveryService {

    CityNodeVO findActiveNode(Long merchantId);

    int syncTags(Long merchantId, String tags);
}
