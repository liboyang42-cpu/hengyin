package com.chengyinhub.business.commerceconsole.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * W7 MERCHANT-12: club dues refund reviews and the operator's decision ({@code cy_dues_refund_decision}). The review
 * row ({@code club_membership_refund_review}) is never changed here. Every state change is compare-and-set.
 */
public interface DuesRefundDecisionMapper {

    @Select("<script>select v.id as reviewId, v.club_id as clubId, v.member_id as memberId, v.invoice_id as invoiceId, "
            + "v.reason as reason, v.create_time as createTime, i.invoice_no as invoiceNo, i.amount_fen as amountFen, "
            + "i.status as invoiceStatus, d.decision as decision, d.note as note, d.decided_by as decidedBy, "
            + "d.decided_at as decidedAt, d.refund_state as refundState "
            + "from club_membership_refund_review v join club_membership_invoice i on i.id = v.invoice_id "
            + "left join cy_dues_refund_decision d on d.review_id = v.id "
            + "<where><if test='undecided'> d.review_id is null</if></where> "
            + "order by v.id desc limit #{limit}</script>")
    List<Map<String, Object>> selectReviews(@Param("undecided") boolean undecided, @Param("limit") int limit);

    @Select("select v.id as reviewId, v.status as reviewStatus, v.invoice_id as invoiceId, i.out_trade_no as outTradeNo, "
            + "i.amount_fen as amountFen, i.status as invoiceStatus "
            + "from club_membership_refund_review v join club_membership_invoice i on i.id = v.invoice_id "
            + "where v.id = #{reviewId} for update")
    Map<String, Object> selectForDecision(@Param("reviewId") long reviewId);

    @Select("select review_id as reviewId, decision, refund_state as refundState, out_refund_no as outRefundNo, "
            + "refund_amount_fen as refundAmountFen from cy_dues_refund_decision where review_id = #{reviewId} for update")
    Map<String, Object> selectDecision(@Param("reviewId") long reviewId);

    @Insert("insert into cy_dues_refund_decision (review_id, decision, note, decided_by, decided_at, out_refund_no, "
            + "refund_amount_fen, refund_state, update_time) values (#{reviewId}, #{decision}, #{note}, #{operator}, now(), "
            + "#{outRefundNo}, #{amountFen}, #{refundState}, now())")
    int insertDecision(@Param("reviewId") long reviewId, @Param("decision") String decision, @Param("note") String note,
                       @Param("operator") String operator, @Param("outRefundNo") String outRefundNo,
                       @Param("amountFen") Long amountFen, @Param("refundState") String refundState);

    /** Only an approval whose request is still open (being sent, or with an unknown answer) records an answer. */
    @Update("update cy_dues_refund_decision set refund_state = #{refundState}, update_time = now() "
            + "where review_id = #{reviewId} and decision = 'APPROVED' and refund_state in ('SUBMITTING', 'UNKNOWN')")
    int updateRefundState(@Param("reviewId") long reviewId, @Param("refundState") String refundState);
}
