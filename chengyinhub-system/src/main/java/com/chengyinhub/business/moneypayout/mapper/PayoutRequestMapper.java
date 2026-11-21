package com.chengyinhub.business.moneypayout.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Withdrawal requests ({@code cy_payout_request}). Every state change is a compare-and-set on the current status,
 * so only one reviewer (or the member's own cancel) can win. See {@code sql/migration_20261115_money_payout.sql}.
 */
public interface PayoutRequestMapper {

    String COLUMNS = "r.id, r.member_id as memberId, r.amount, r.status, r.cash_withdraw_id as cashWithdrawId, "
            + "r.review_by as reviewBy, r.review_note as reviewNote, r.fail_reason as failReason, "
            + "r.create_time as createTime, r.review_time as reviewTime, r.update_time as updateTime, "
            + "w.status as transferStatus";

    @Insert("insert into cy_payout_request (member_id, amount, status, create_time, update_time) "
            + "values (#{memberId}, #{amount}, 'PENDING', now(), now())")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(PayoutRequestRow row);

    @Select("select count(1) from cy_payout_request where member_id = #{memberId} and status in ('PENDING', 'PAYING')")
    int countOpenByMember(@Param("memberId") Long memberId);

    @Select("select " + COLUMNS + " from cy_payout_request r left join member_cash_withdraw w on w.id = r.cash_withdraw_id "
            + "where r.id = #{id}")
    Map<String, Object> selectById(@Param("id") Long id);

    @Select("select " + COLUMNS + " from cy_payout_request r left join member_cash_withdraw w on w.id = r.cash_withdraw_id "
            + "where r.member_id = #{memberId} order by r.id desc limit #{limit}")
    List<Map<String, Object>> selectByMember(@Param("memberId") Long memberId, @Param("limit") int limit);

    @Select("<script>select " + COLUMNS + ", m.nickname as nickname "
            + "from cy_payout_request r left join member_cash_withdraw w on w.id = r.cash_withdraw_id "
            + "left join ums_member m on m.id = r.member_id "
            + "<where><if test='status != null'>r.status = #{status}</if></where> order by r.id desc limit #{limit}</script>")
    List<Map<String, Object>> selectAdminList(@Param("status") String status, @Param("limit") int limit);

    @Update("update cy_payout_request set status = 'CANCELLED', update_time = now() "
            + "where id = #{id} and member_id = #{memberId} and status = 'PENDING'")
    int cancelPending(@Param("id") Long id, @Param("memberId") Long memberId);

    @Update("update cy_payout_request set status = #{to}, review_by = #{by}, review_note = #{note}, "
            + "review_time = now(), update_time = now() where id = #{id} and status = 'PENDING'")
    int reviewPending(@Param("id") Long id, @Param("to") String to, @Param("by") String by, @Param("note") String note);

    @Update("update cy_payout_request set status = 'SENT', cash_withdraw_id = #{cashWithdrawId}, update_time = now() "
            + "where id = #{id} and status = 'PAYING'")
    int markSent(@Param("id") Long id, @Param("cashWithdrawId") Long cashWithdrawId);

    @Update("update cy_payout_request set status = 'FAILED', fail_reason = #{reason}, update_time = now() "
            + "where id = #{id} and status = 'PAYING'")
    int markFailed(@Param("id") Long id, @Param("reason") String reason);

    /** Row used only for the insert. */
    class PayoutRequestRow {
        private Long id;
        private Long memberId;
        private BigDecimal amount;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getMemberId() { return memberId; }
        public void setMemberId(Long memberId) { this.memberId = memberId; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
    }
}
