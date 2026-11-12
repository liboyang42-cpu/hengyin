package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MarketingOrderAdjustment;
import org.apache.ibatis.annotations.Param;

/**
 * 订单促销成交快照读写。只读写 marketing_order_adjustment。
 *
 * <p>插入只允许一次(唯一键 order_type+order_id);读取是退款与对账的唯一入口 ——
 * 它们读快照,不重算当前配置。
 */
public interface MarketingOrderAdjustmentMapper {

    int insertAdjustment(MarketingOrderAdjustment row);

    MarketingOrderAdjustment selectByOrder(@Param("orderType") Integer orderType,
                                           @Param("orderId") Long orderId);

    /** 同一会员已用该供给的订单数(用于 max_per_member 限次)。 */
    int countByOfferAndMember(@Param("offerId") Long offerId, @Param("memberId") Long memberId);

    /**
     * 与 {@link #countByOfferAndMember} 同口径,但走<b>当前读</b>({@code for update})。
     *
     * <p>为什么必须用当前读:建单事务在进入这里之前已经执行过普通读(主题/票/会员),
     * REPEATABLE READ 下普通读会用事务早先建立的快照 —— 并发那一笔刚提交的使用事实看不见,
     * 数还是 0,限次照样被突破。调用方先锁 {@code marketing_offer} 行,再用这条语句取最新已提交计数。
     */
    int countByOfferAndMemberForUpdate(@Param("offerId") Long offerId, @Param("memberId") Long memberId);
}
