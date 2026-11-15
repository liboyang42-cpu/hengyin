package com.chengyinhub.business.platformpass.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * R6a:通行证 iOS 内购的两张表({@code platform_pass_apple_order}、{@code platform_pass_apple_notification}),
 * 以及把 APPLE 渠道订单置已付的一条条件更新。见 {@code sql/migration_20261115_money_pass.sql}。
 * 所有写都带前置条件,返回影响行数供调用方判定。
 */
public interface PlatformPassAppleMapper {

    @Insert("insert into platform_pass_apple_order (order_no, member_id, plan_code, product_id, app_account_token, "
            + "bundle_id, environment, state, create_time, update_time) values (#{order_no}, #{member_id}, #{plan_code}, "
            + "#{product_id}, #{app_account_token}, #{bundle_id}, #{environment}, 'PENDING', now(3), now(3))")
    int insertAppleOrder(Map<String, Object> row);

    @Select("select * from platform_pass_apple_order where order_no = #{orderNo}")
    Map<String, Object> selectByOrderNo(@Param("orderNo") String orderNo);

    @Select("select * from platform_pass_apple_order where app_account_token = #{token} for update")
    Map<String, Object> selectByTokenForUpdate(@Param("token") String token);

    @Select("select * from platform_pass_apple_order where transaction_id = #{transactionId} for update")
    Map<String, Object> selectByTransactionForUpdate(@Param("transactionId") String transactionId);

    /** 首次绑定 Apple 交易;已绑定的行不改(transaction_id 唯一,一笔交易只开通一单)。 */
    @Update("update platform_pass_apple_order set transaction_id = #{transactionId}, original_transaction_id = #{originalId}, "
            + "price_milliunits = #{price}, currency = #{currency}, purchase_date = #{purchaseDate}, update_time = now(3) "
            + "where order_no = #{orderNo} and transaction_id is null")
    int bindTransaction(@Param("orderNo") String orderNo, @Param("transactionId") String transactionId,
                        @Param("originalId") String originalId, @Param("price") Long price,
                        @Param("currency") String currency, @Param("purchaseDate") Date purchaseDate);

    @Update("update platform_pass_apple_order set state = 'GRANTED', update_time = now(3) "
            + "where order_no = #{orderNo} and state = 'PENDING'")
    int markGranted(@Param("orderNo") String orderNo);

    @Update("update platform_pass_apple_order set state = 'REVOKED', revoked_at = #{revokedAt}, "
            + "revocation_reason = #{reason}, update_time = now(3) where order_no = #{orderNo} and state <> 'REVOKED'")
    int markRevoked(@Param("orderNo") String orderNo, @Param("revokedAt") Date revokedAt, @Param("reason") Integer reason);

    @Update("update platform_pass_apple_order set state = 'REVIEW', update_time = now(3) "
            + "where order_no = #{orderNo} and state = 'REVOKED'")
    int markReview(@Param("orderNo") String orderNo);

    /** APPLE 渠道订单置已付。只从待支付 / 已取消转入;已付、已退的行不动。 */
    @Update("update platform_pass_order set status = 1, transaction_id = #{transactionId}, paid_time = #{paidTime} "
            + "where id = #{id} and channel = 'APPLE' and status in (0, 2)")
    int markOrderPaid(@Param("id") long id, @Param("transactionId") String transactionId, @Param("paidTime") Date paidTime);

    /** T5 复核页:待复核(或已处理)的 App Store 订单,带订单状态;不含 appAccountToken。 */
    @Select("select a.order_no, a.member_id, a.plan_code, a.product_id, a.environment, a.transaction_id, "
            + "a.original_transaction_id, a.price_milliunits, a.currency, a.purchase_date, a.state, a.revoked_at, "
            + "a.revocation_reason, a.update_time, o.id as order_id, o.status as order_status, o.price_fen, "
            + "o.valid_days, o.refunded_time "
            + "from platform_pass_apple_order a left join platform_pass_order o on o.order_no = a.order_no "
            + "where a.state = #{state} order by a.update_time desc limit #{limit}")
    List<Map<String, Object>> selectByState(@Param("state") String state, @Param("limit") int limit);

    @Select("select notification_uuid, notification_type, subtype, state, reason_code, create_time, update_time "
            + "from platform_pass_apple_notification where transaction_id = #{transactionId} order by create_time")
    List<Map<String, Object>> selectNotificationsByTransaction(@Param("transactionId") String transactionId);

    @Select("select * from platform_pass_apple_order where order_no = #{orderNo} for update")
    Map<String, Object> selectByOrderNoForUpdate(@Param("orderNo") String orderNo);

    /** 只从 REVIEW 出:GRANTED(重新开通)或 REVOKED(维持退款)。 */
    @Update("update platform_pass_apple_order set state = #{state}, update_time = now(3) "
            + "where order_no = #{orderNo} and state = 'REVIEW'")
    int resolveReview(@Param("orderNo") String orderNo, @Param("state") String state);

    @Insert("insert ignore into platform_pass_apple_notification (notification_uuid, notification_type, subtype, "
            + "transaction_id, payload_hash, state, create_time, update_time) values (#{uuid}, #{type}, #{subtype}, "
            + "#{transactionId}, #{hash}, 'RECEIVED', now(3), now(3))")
    int insertNotification(@Param("uuid") String uuid, @Param("type") String type, @Param("subtype") String subtype,
                           @Param("transactionId") String transactionId, @Param("hash") String hash);

    @Select("select * from platform_pass_apple_notification where notification_uuid = #{uuid} for update")
    Map<String, Object> lockNotification(@Param("uuid") String uuid);

    @Update("update platform_pass_apple_notification set state = #{state}, reason_code = #{reason}, update_time = now(3) "
            + "where notification_uuid = #{uuid} and state <> 'APPLIED'")
    int notificationState(@Param("uuid") String uuid, @Param("state") String state, @Param("reason") String reason);
}
