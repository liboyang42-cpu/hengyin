package com.chengyinhub.business.service;

import java.util.Map;

/** 商家后台一级页的只读聚合。 */
public interface IMerchantAggregateReadService {

    Map<String, Object> relationHome(Long memberId, Long topicId,
                                     Double originLat, Double originLng, int limit);

    /**
     * 营销首页聚合。券卡数字与点开卡片落到的商家券列表同源,所以除了归属人
     * ({@code memberId})还要带上商家节点 id —— 只有 {@code member_id} 数不出「这一个商家」的券。
     */
    Map<String, Object> marketingHome(Long memberId, Long merchantId, int windowDays);

    /** 兼容旧路由；返回与 marketing-home.analytics 同一份独立事件合同。 */
    Map<String, Object> funnel(Long memberId, int windowDays);
}
