package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.dto.CreatorRevenueRow;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * 原账本收入只读投影。
 *
 * <p>只读：无写入语句。分项表 {@code coop_settlement_original_component} 是原订单不可变分项，
 * 冲回表 {@code coop_settlement_original_adjustment} 通过 component_id 回指原分项，因此
 * 「谁的收入」不需要再猜：{@code receiver_member_id}。
 */
public interface CreatorRevenueLedgerMapper {

    /** 期间内原分项毛额，按收款会员分组；receiver_member_id 为 null 的 ACCOUNTING 行单独成组。 */
    List<CreatorRevenueRow> selectGrossByReceiver(@Param("from") Date from,
                                                 @Param("toExclusive") Date toExclusive);

    /** 期间内冲回额（adjustment.amount 恒为负），经 component_id 归到原分项收款会员。 */
    List<CreatorRevenueRow> selectRefundedByReceiver(@Param("from") Date from,
                                                     @Param("toExclusive") Date toExclusive);

    /** 期间内出现过的计算版本，供对账时确认口径来源。 */
    List<String> selectCalculationVersions(@Param("from") Date from,
                                           @Param("toExclusive") Date toExclusive);
}
