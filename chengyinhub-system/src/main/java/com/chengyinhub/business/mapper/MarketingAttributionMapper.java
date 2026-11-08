package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MarketingAttribution;
import org.apache.ibatis.annotations.Param;

/**
 * 下单来源快照。只允许 insert 与按单读 —— 没有 update/delete 方法,
 * 归因一旦写下不可被后来者改写(同单第二来源由唯一键拒绝)。
 */
public interface MarketingAttributionMapper {

    int insertAttribution(MarketingAttribution attribution);

    MarketingAttribution selectByOrder(@Param("orderType") Integer orderType,
                                       @Param("orderId") Long orderId);

    /**
     * 该会员是否为(campaignKey + target)持有服务端签发的凭据。返回凭据条数(&gt;0 即已绑定)。
     * 只有绑定过的 campaign 才允许做成交聚合 —— 客户端传同名 key 不算来源证明。
     */
    int countOwnedCredentials(@Param("sharerMemberId") Long sharerMemberId,
                              @Param("campaignKey") String campaignKey,
                              @Param("targetType") Integer targetType,
                              @Param("targetId") Long targetId);

    /**
     * 已绑定 campaign 的成交聚合。只回计数与合计,**不返回**任何 orderId/memberId。
     * 仅统计调用者本人作为归因分享人、且凭据 campaignKey+target 完全一致的报名单。
     */
    com.chengyinhub.business.domain.dto.CampaignAttributionAggregate aggregateOwnedCampaign(
            @Param("sharerMemberId") Long sharerMemberId,
            @Param("campaignKey") String campaignKey,
            @Param("targetType") Integer targetType,
            @Param("targetId") Long targetId);
}
