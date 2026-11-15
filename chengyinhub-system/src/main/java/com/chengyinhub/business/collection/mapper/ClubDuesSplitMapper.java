package com.chengyinhub.business.collection.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * K1 club dues split ledger ({@code cy_collection_dues_order}, see {@code sql/migration_20261115_club_dues.sql}): one row
 * per dues invoice paid to the club's own sub-merchant, with the platform take rate frozen when the order was placed.
 */
public interface ClubDuesSplitMapper {

    @Insert("insert ignore into cy_collection_dues_order (out_trade_no, invoice_id, club_id, sub_mchid, total_fen, take_rate, state) "
            + "values (#{outTradeNo}, #{invoiceId}, #{clubId}, #{subMchid}, #{totalFen}, #{takeRate}, 'OPEN')")
    int insertOrder(@Param("outTradeNo") String outTradeNo, @Param("invoiceId") Long invoiceId, @Param("clubId") Long clubId,
                    @Param("subMchid") String subMchid, @Param("totalFen") long totalFen,
                    @Param("takeRate") BigDecimal takeRate);

    @Select("select out_trade_no as outTradeNo, invoice_id as invoiceId, club_id as clubId, sub_mchid as subMchid, "
            + "total_fen as totalFen, take_rate as takeRate, state, share_order_id as shareOrderId, note "
            + "from cy_collection_dues_order where out_trade_no = #{outTradeNo}")
    Map<String, Object> selectOrder(@Param("outTradeNo") String outTradeNo);

    @Update("update cy_collection_dues_order set state = #{state}, share_order_id = coalesce(#{shareOrderId}, share_order_id), "
            + "note = #{note} where out_trade_no = #{outTradeNo} and state = #{expected}")
    int updateOrderState(@Param("outTradeNo") String outTradeNo, @Param("expected") String expected,
                         @Param("state") String state, @Param("shareOrderId") Long shareOrderId, @Param("note") String note);

    /**
     * Dues orders due for profit sharing: the invoice is PAID, the WeChat transaction is bound, the payment is past the
     * existing hold ({@code FundsHoldPolicy.WITHDRAWABLE_AFTER_END_DAYS} after payment) and no refund review is pending.
     */
    @Select("select x.out_trade_no as outTradeNo, x.invoice_id as invoiceId, x.club_id as clubId, x.sub_mchid as subMchid, "
            + "x.total_fen as totalFen, x.take_rate as takeRate, s.transaction_id as transactionId "
            + "from cy_collection_dues_order x "
            + "join ecom_order_seller s on cast(s.out_trade_no as binary) = cast(x.out_trade_no as binary) "
            + "join club_membership_invoice i on i.id = x.invoice_id "
            + "where x.state = 'OPEN' and s.transaction_id is not null and i.status = 'PAID' and i.paid_at is not null "
            + "and i.paid_at <= date_sub(#{now}, interval ${@com.chengyinhub.business.support.FundsHoldPolicy@WITHDRAWABLE_AFTER_END_DAYS} day) "
            + "and not exists (select 1 from club_membership_refund_review v where v.invoice_id = x.invoice_id "
            + "                and v.status = 'PENDING_REVIEW') "
            + "order by x.created_at limit #{limit}")
    List<Map<String, Object>> selectDueOrders(@Param("now") java.util.Date now, @Param("limit") int limit);

    @Select("select count(1) from cy_collection_dues_order where out_trade_no = #{outTradeNo}")
    int countByOutTradeNo(@Param("outTradeNo") String outTradeNo);

    /** Admin console: the dues split ledger with what each party received so far (money facts from the payment rails only). */
    @Select("<script>select x.out_trade_no as outTradeNo, x.invoice_id as invoiceId, x.club_id as clubId, "
            + "x.total_fen as totalFen, x.take_rate as takeRate, x.state as collectionState, x.note as note, "
            + "x.created_at as createdAt, o.id as shareOrderId, o.state as shareState, o.manual_reason as manualReason, "
            + "coalesce((select sum(l.amount_fen) from ecom_profit_share_line l where l.share_order_id = o.id "
            + "          and l.receiver_kind = 'PLATFORM'), 0) as platformPlannedFen, "
            + "case when o.state = 'SUCCESS' then coalesce((select sum(l.amount_fen) from ecom_profit_share_line l "
            + "          where l.share_order_id = o.id and l.receiver_kind = 'PLATFORM'), 0) else 0 end as platformReceivedFen, "
            + "coalesce((select sum(rt.amount_fen) from ecom_profit_share_return rt where rt.share_order_id = o.id "
            + "          and rt.state = 'SUCCESS'), 0) as platformReturnedFen, "
            + "coalesce((select sum(f.refund_fen) from ecom_refund f where f.out_trade_no = x.out_trade_no "
            + "          and f.state = 'SUCCESS'), 0) as refundedFen "
            + "from cy_collection_dues_order x "
            + "left join ecom_profit_share_order o on o.id = x.share_order_id "
            + "<where><if test=\"clubId != null\"> x.club_id = #{clubId}</if>"
            + "<if test=\"state != null and state != ''\"> and x.state = #{state}</if></where> "
            + "order by x.created_at desc limit #{limit}</script>")
    List<Map<String, Object>> selectLedger(@Param("clubId") Long clubId, @Param("state") String state,
                                           @Param("limit") int limit);
}
