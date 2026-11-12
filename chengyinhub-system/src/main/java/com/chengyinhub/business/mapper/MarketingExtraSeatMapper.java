package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.dto.MarketingExtraSeatOrderRow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 加座（route=3）来源查询。
 *
 * <p>只读 {@code marketing_product_order}（+ 原单号 / 礼品状态一次 JOIN），
 * 不是第二权益真源：可玩准入仍然只在 {@code cms_registration}
 * （原 grant + 礼品单源单准入）。
 *
 * <p>本接口**没有容量计数**：自玩增购不存在座位资源，也没有可读的容量配置，
 * 所以不统计总加座件数、不与任何 team 配置比较。
 */
public interface MarketingExtraSeatMapper {

    /**
     * 某原单上是否已有一位加座成交给同一受赠人（防重复加座/重复准入）。
     *
     * <p>这是"同一人不得拿两份"的唯一判定。调用方须先锁原单行
     * （{@code selectByOrderNoForUpdate}），让并发下单在同一把锁上串行；本方法自身不加锁。
     */
    int countPaidExtraSeatForRecipient(@Param("originalOrderId") Long originalOrderId,
                                       @Param("recipientMemberId") Long recipientMemberId);

    /** 我买过的加座（含待支付与已退款），一次 JOIN 带出原单号与礼品领取状态。 */
    List<MarketingExtraSeatOrderRow> selectExtraSeatsByMember(@Param("memberId") Long memberId,
                                                              @Param("limit") int limit);
}
