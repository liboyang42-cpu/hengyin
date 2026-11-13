package com.chengyinhub.business.platformpass.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
import java.util.List;
import java.util.Map;

/** 自玩票虚拟支付冻结行（cms_registration_xpay）。所有写带状态前置条件，返回影响行数。 */
public interface SelfPlayXpayMapper {
    String COLS = "id, registration_id, registration_no, member_id, product_id, offer_id, xpay_env, price_fen, state, "
            + "wx_order_id, paid_time, delivery_confirmed, refund_no, refunded_time, create_time, update_time";

    @Select("select " + COLS + " from cms_registration_xpay where registration_id = #{registrationId}")
    Map<String, Object> selectByRegistrationId(@Param("registrationId") long registrationId);

    @Select("select " + COLS + " from cms_registration_xpay where registration_no = #{registrationNo}")
    Map<String, Object> selectByRegistrationNo(@Param("registrationNo") String registrationNo);

    @Select("select " + COLS + " from cms_registration_xpay where registration_id = #{registrationId} for update")
    Map<String, Object> selectByRegistrationIdForUpdate(@Param("registrationId") long registrationId);

    /** 首次签发冻结；已有行时不覆盖（冻结值永不被后续配置变化改写）。 */
    @Insert("insert ignore into cms_registration_xpay (registration_id, registration_no, member_id, product_id, "
            + "offer_id, xpay_env, price_fen, state) values (#{registrationId}, #{registrationNo}, #{memberId}, "
            + "#{productId}, #{offerId}, #{env}, #{priceFen}, 0)")
    int insertFrozen(@Param("registrationId") long registrationId, @Param("registrationNo") String registrationNo,
                     @Param("memberId") long memberId, @Param("productId") String productId,
                     @Param("offerId") String offerId, @Param("env") int env, @Param("priceFen") long priceFen);

    /** 已付并开通；仅从 0（待付）或 4 以外迁移，重复调用 0 行。 */
    @Update("update cms_registration_xpay set state = 1, wx_order_id = #{wxOrderId}, paid_time = #{paidTime}, "
            + "delivery_confirmed = #{delivered} where id = #{id} and state in (0, 5)")
    int markPaid(@Param("id") long id, @Param("wxOrderId") String wxOrderId, @Param("paidTime") Date paidTime,
                 @Param("delivered") int delivered);

    @Update("update cms_registration_xpay set state = 4, wx_order_id = #{wxOrderId}, paid_time = #{paidTime} "
            + "where id = #{id} and state in (0, 5)")
    int markPaidOrphan(@Param("id") long id, @Param("wxOrderId") String wxOrderId, @Param("paidTime") Date paidTime);

    @Update("update cms_registration_xpay set state = 5 where id = #{id} and state = 0")
    int markUnpaidReleased(@Param("id") long id);

    @Update("update cms_registration_xpay set delivery_confirmed = 1 where id = #{id} and state in (1, 2, 3)")
    int markDelivered(@Param("id") long id);

    /** 退款申请：绑定退款号并进入退款中；同一退款号可重复提交（幂等），换号被拒。 */
    @Update("update cms_registration_xpay set state = 2, refund_no = #{refundNo} where id = #{id} "
            + "and ((state = 1 and refund_no is null) or (state = 4 and refund_no is null) "
            + "or (state = 2 and refund_no = #{refundNo}))")
    int markRefundRequested(@Param("id") long id, @Param("refundNo") String refundNo);

    /** 渠道明确拒绝退款：退回已付态并释放退款号。 */
    @Update("update cms_registration_xpay set state = 1, refund_no = null where id = #{id} and state = 2 "
            + "and refund_no = #{refundNo}")
    int markRefundRolledBack(@Param("id") long id, @Param("refundNo") String refundNo);

    @Update("update cms_registration_xpay set state = 3, refunded_time = #{at} where id = #{id} and state in (1, 2, 4)")
    int markRefunded(@Param("id") long id, @Param("at") Date at);

    /** 对账候选：未付已签发且超过宽限、已付未确认发货、退款中、已付但订单已关。 */
    @Select("select " + COLS + " from cms_registration_xpay where id > #{afterId} and ("
            + "(state = 0 and create_time < #{graceBefore}) or (state in (1, 2) and delivery_confirmed = 0) "
            + "or state = 2 or state = 4) order by id limit #{limit}")
    List<Map<String, Object>> selectReconcileCandidates(@Param("afterId") long afterId,
                                                         @Param("graceBefore") Date graceBefore,
                                                         @Param("limit") int limit);
}
