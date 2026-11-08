package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MarketingPromotionRefundFact;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 退款到账事实读写 + 促销成交订单解析。只读写 marketing_promotion_refund_fact,
 * 并对 cms_registration 做<b>只读</b>关联(解析 parent_order_no,不改任何列)。
 */
public interface MarketingPromotionRefundFactMapper {

    int upsertRefundFact(MarketingPromotionRefundFact fact);

    /** 该单已到账的退款合计(分)。无事实返回 0。 */
    long sumRefundedFen(@Param("orderType") Integer orderType, @Param("orderId") Long orderId);

    /** 该单各事实里出现过的原支付总额(分),去重;用于发现"同一单报了不同总额"的冲突。 */
    List<Long> distinctTotalFen(@Param("orderType") Integer orderType, @Param("orderId") Long orderId);

    /**
     * 解析这笔退款属于哪张促销成交订单。
     *
     * <p>组合/多票订单的促销快照写在<b>首单</b>上,而退款可能落在同 parent_order_no 的另一张上;
     * 直接拿被退 id 查快照会漏累计、漏释放。这里按 {@code parent_order_no} 归并到同一订单,
     * 并要求 {@code member_id} 相同 —— 只认这两条既有事实,不猜 id、不跨会员合并。
     *
     * @return 命中 0 条 = 与本域无关;1 条 = 该订单;>1 条 = 一单出现多份促销快照(数据异常,调用方必须拒绝合并)
     */
    List<Long> resolvePromoOrderIds(@Param("orderType") Integer orderType,
                                    @Param("registrationId") Long registrationId);
}
