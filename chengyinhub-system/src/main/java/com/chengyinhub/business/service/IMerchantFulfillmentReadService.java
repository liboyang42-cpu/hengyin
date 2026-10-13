package com.chengyinhub.business.service;

import java.util.List;
import java.util.Map;

/**
 * 商家履约侧只读投影。
 */
public interface IMerchantFulfillmentReadService {

    /**
     * 查询商家已中标主题下尚未结束的场次。
     *
     * @param memberId 当前商家
     * @param topicId 可选主题过滤；不属于当前商家中标主题时返回空列表
     * @return 场次、俱乐部、已售人数与预计到店区间
     */
    List<Map<String, Object>> listUpcomingRuns(Long memberId, Long topicId);
}
