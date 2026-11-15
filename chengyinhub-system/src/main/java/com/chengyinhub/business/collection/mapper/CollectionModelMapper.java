package com.chengyinhub.business.collection.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * S2 collection model tables ({@code cy_collection_order}, {@code cy_collection_refusal}) and the read-only joins the
 * split settlement and the admin console need. See {@code sql/migration_20261115_collection_model.sql}.
 */
public interface CollectionModelMapper {

    @Insert("insert ignore into cy_collection_order (registration_no, registration_id, owner_type, owner_id, seller_type, "
            + "seller_id, sub_mchid, total_fen, take_rate, state) values (#{registrationNo}, #{registrationId}, #{ownerType}, "
            + "#{ownerId}, #{sellerType}, #{sellerId}, #{subMchid}, #{totalFen}, #{takeRate}, 'OPEN')")
    int insertOrder(@Param("registrationNo") String registrationNo, @Param("registrationId") Long registrationId,
                    @Param("ownerType") long ownerType, @Param("ownerId") Long ownerId,
                    @Param("sellerType") String sellerType, @Param("sellerId") Long sellerId,
                    @Param("subMchid") String subMchid, @Param("totalFen") long totalFen,
                    @Param("takeRate") BigDecimal takeRate);

    @Select("select registration_no as registrationNo, registration_id as registrationId, owner_type as ownerType, "
            + "owner_id as ownerId, seller_type as sellerType, seller_id as sellerId, sub_mchid as subMchid, "
            + "total_fen as totalFen, take_rate as takeRate, state, share_order_id as shareOrderId, note "
            + "from cy_collection_order where registration_no = #{registrationNo}")
    Map<String, Object> selectOrder(@Param("registrationNo") String registrationNo);

    @Update("update cy_collection_order set state = #{state}, share_order_id = coalesce(#{shareOrderId}, share_order_id), "
            + "note = #{note} where registration_no = #{registrationNo} and state = #{expected}")
    int updateOrderState(@Param("registrationNo") String registrationNo, @Param("expected") String expected,
                         @Param("state") String state, @Param("shareOrderId") Long shareOrderId, @Param("note") String note);

    /** Activity ticket sales collected by a seller sub-merchant that the activity wallet settlement must leave out (paid, not refunded, not held). */
    @Select("select coalesce(sum(r.payable_amount), 0) from cms_registration r "
            + "join cy_collection_order x on x.registration_id = r.id "
            + "where r.owner_type = 2 and r.owner_id = #{activityId} and r.payment_status = 2 and r.registration_status = 2 "
            + "and not exists (select 1 from cms_activity_settlement_hold h where h.registration_id = r.id and h.status = 0)")
    BigDecimal sumSplitPaidSalesForActivity(@Param("activityId") Long activityId);

    /** Same, for partially refunded split sales that the activity wallet settlement would add back as retained money. */
    @Select("select coalesce(sum(r.payable_amount - ra.refunded), 0) from cms_registration r "
            + "join cy_collection_order x on x.registration_id = r.id "
            + "join (select source_id, sum(refund_amount) as refunded from refund_application "
            + "      where source_type = 'registration' and status = 1 and del_flag = 0 group by source_id) ra on ra.source_id = r.id "
            + "where r.owner_type = 2 and r.owner_id = #{activityId} and r.payment_status = 4 and r.registration_status = 3 "
            + "and ra.refunded < r.payable_amount")
    BigDecimal sumSplitRetainedSalesForActivity(@Param("activityId") Long activityId);

    @Select("select transaction_id from ecom_order_seller where out_trade_no = #{registrationNo}")
    String selectTransactionId(@Param("registrationNo") String registrationNo);

    @Select("select count(1) from cy_collection_order where registration_id = #{registrationId}")
    int countByRegistrationId(@Param("registrationId") Long registrationId);

    /**
     * Split orders that are due for profit sharing: paid with a bound WeChat transaction, not yet planned, and past the
     * existing T+N hold ({@code FundsHoldPolicy.WITHDRAWABLE_AFTER_END_DAYS} after the event end; for a topic without an
     * end date, after the order), with no open complaint and no active settlement hold on the registration.
     */
    @Select("select x.registration_no as registrationNo, x.registration_id as registrationId, x.owner_type as ownerType, "
            + "x.owner_id as ownerId, x.seller_type as sellerType, x.seller_id as sellerId, x.sub_mchid as subMchid, "
            + "x.total_fen as totalFen, x.take_rate as takeRate, s.transaction_id as transactionId "
            + "from cy_collection_order x "
            + "join ecom_order_seller s on cast(s.out_trade_no as binary) = cast(x.registration_no as binary) "
            + "join cms_registration r on r.id = x.registration_id "
            + "left join cms_activity a on x.owner_type = 2 and a.id = x.owner_id "
            + "left join cms_topic t on x.owner_type = 1 and t.id = x.owner_id "
            + "where x.state = 'OPEN' and s.transaction_id is not null and r.payment_status in (2, 3, 4) "
            + "and coalesce(case when x.owner_type = 2 then a.end_date else t.end_date end, x.created_at) "
            + "    <= date_sub(#{now}, interval ${@com.chengyinhub.business.support.FundsHoldPolicy@WITHDRAWABLE_AFTER_END_DAYS} day) "
            + "and not exists (select 1 from coop_complaint c where c.registration_id = r.id and c.del_flag = 0 and c.status < 3) "
            + "and not exists (select 1 from cms_activity_settlement_hold h where h.registration_id = r.id and h.status = 0) "
            + "order by x.created_at limit #{limit}")
    List<Map<String, Object>> selectDueOrders(@Param("now") java.util.Date now, @Param("limit") int limit);

    @Select("select coalesce(sum(refund_fen), 0) from ecom_refund where out_trade_no = #{outTradeNo} and state = 'SUCCESS'")
    Long sumSucceededRefundFen(@Param("outTradeNo") String outTradeNo);

    @Select("select count(1) from ecom_refund where out_trade_no = #{outTradeNo} and state not in ('SUCCESS', 'CLOSED', 'ABNORMAL')")
    int countOpenRefunds(@Param("outTradeNo") String outTradeNo);

    @Insert("insert into cy_collection_refusal (seller_type, seller_id, reason, refused_count, first_at, last_at) "
            + "values (#{sellerType}, #{sellerId}, #{reason}, 1, now(), now()) "
            + "on duplicate key update refused_count = refused_count + 1, last_at = now()")
    int recordRefusal(@Param("sellerType") String sellerType, @Param("sellerId") long sellerId, @Param("reason") String reason);

    // ---- admin console (read only) ----

    @Select("<script>select seller_type as sellerType, seller_id as sellerId, reason, refused_count as refusedCount, "
            + "first_at as firstAt, last_at as lastAt from cy_collection_refusal order by last_at desc limit #{limit}</script>")
    List<Map<String, Object>> selectRefusals(@Param("limit") int limit);

    /** Per-seller collection status: every merchant and club that sells, with its sub-merchant binding state. */
    @Select("<script>select v.sellerType, v.sellerId, v.sellerName, sm.applyment_state as applymentState, "
            + "case when sm.applyment_state = 'FINISH' and sm.sub_mchid is not null then 1 else 0 end as bound, "
            + "(select coalesce(sum(f.refused_count), 0) from cy_collection_refusal f "
            + "  where f.seller_type = v.sellerType and f.seller_id = v.sellerId) as refusedCount "
            + "from (select 'MERCHANT' as sellerType, m.id as sellerId, m.name as sellerName from mms_merchant m "
            + "      where m.del_flag = 0 and m.status = 1 "
            + "      union all "
            + "      select 'CLUB' as sellerType, c.id as sellerId, c.name as sellerName from club c "
            + "      where c.del_flag = 0 and c.status = 1) v "
            + "left join ecom_sub_merchant sm on sm.owner_type = v.sellerType and sm.owner_id = v.sellerId "
            + "<where>"
            + "<if test=\"sellerType != null and sellerType != ''\"> v.sellerType = #{sellerType}</if>"
            + "<if test=\"bound != null and bound\"> and sm.applyment_state = 'FINISH' and sm.sub_mchid is not null</if>"
            + "<if test=\"bound != null and !bound\"> and (sm.applyment_state is null or sm.applyment_state != 'FINISH' or sm.sub_mchid is null)</if>"
            + "</where> order by refusedCount desc, v.sellerType, v.sellerId limit #{limit}</script>")
    List<Map<String, Object>> selectSellerStatus(@Param("sellerType") String sellerType, @Param("bound") Boolean bound,
                                                 @Param("limit") int limit);

    /**
     * Split ledger: one row per split order with what each party received so far. Money facts come only from the
     * profit-sharing order, its lines, returns and the refunds recorded by the payment rails.
     */
    @Select("<script>select x.registration_no as registrationNo, x.owner_type as ownerType, x.owner_id as ownerId, "
            + "x.seller_type as sellerType, x.seller_id as sellerId, x.total_fen as totalFen, x.take_rate as takeRate, "
            + "x.state as collectionState, x.note as note, x.created_at as createdAt, s.transaction_id as transactionId, "
            + "o.id as shareOrderId, o.state as shareState, o.manual_reason as manualReason, "
            + "coalesce((select sum(l.amount_fen) from ecom_profit_share_line l where l.share_order_id = o.id "
            + "          and l.receiver_kind = 'PLATFORM'), 0) as platformPlannedFen, "
            + "case when o.state = 'SUCCESS' then coalesce((select sum(l.amount_fen) from ecom_profit_share_line l "
            + "          where l.share_order_id = o.id and l.receiver_kind = 'PLATFORM'), 0) else 0 end as platformReceivedFen, "
            + "coalesce((select sum(rt.amount_fen) from ecom_profit_share_return rt where rt.share_order_id = o.id "
            + "          and rt.state = 'SUCCESS'), 0) as platformReturnedFen, "
            + "coalesce((select sum(f.refund_fen) from ecom_refund f where f.out_trade_no = x.registration_no "
            + "          and f.state = 'SUCCESS'), 0) as refundedFen "
            + "from cy_collection_order x "
            + "left join ecom_order_seller s on cast(s.out_trade_no as binary) = cast(x.registration_no as binary) "
            + "left join ecom_profit_share_order o on (o.id = x.share_order_id "
            + "     or (x.share_order_id is null and cast(o.out_trade_no as binary) = cast(x.registration_no as binary))) "
            + "<where>"
            + "<if test=\"sellerType != null and sellerType != ''\"> x.seller_type = #{sellerType}</if>"
            + "<if test=\"sellerId != null\"> and x.seller_id = #{sellerId}</if>"
            + "<if test=\"state != null and state != ''\"> and x.state = #{state}</if>"
            + "</where> order by x.created_at desc limit #{limit}</script>")
    List<Map<String, Object>> selectLedger(@Param("sellerType") String sellerType, @Param("sellerId") Long sellerId,
                                           @Param("state") String state, @Param("limit") int limit);

    /** Failures queue: split orders whose profit sharing is in manual review, and failed profit-sharing returns. */
    @Select("select 'SHARE' as kind, o.id as id, o.out_trade_no as registrationNo, x.seller_type as sellerType, "
            + "x.seller_id as sellerId, o.state as state, o.manual_reason as reason, o.total_fen as amountFen "
            + "from ecom_profit_share_order o "
            + "join cy_collection_order x on cast(x.registration_no as binary) = cast(o.out_trade_no as binary) "
            + "where o.state = 'MANUAL' "
            + "union all "
            + "select 'RETURN' as kind, rt.id as id, o.out_trade_no as registrationNo, x.seller_type as sellerType, "
            + "x.seller_id as sellerId, rt.state as state, rt.fail_reason as reason, rt.amount_fen as amountFen "
            + "from ecom_profit_share_return rt join ecom_profit_share_order o on o.id = rt.share_order_id "
            + "join cy_collection_order x on cast(x.registration_no as binary) = cast(o.out_trade_no as binary) "
            + "where rt.state = 'FAILED' "
            + "order by id desc limit #{limit}")
    List<Map<String, Object>> selectFailures(@Param("limit") int limit);
}
