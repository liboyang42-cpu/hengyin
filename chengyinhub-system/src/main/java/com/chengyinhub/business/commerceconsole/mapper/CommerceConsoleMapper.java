package com.chengyinhub.business.commerceconsole.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * T5 MON-16:营销商品(含城市包)、探索套票、俱乐部会费的后台查询与两项安全写(停售、停用会费方案)。
 * 只选运营需要的列:不读快照正文、报价签名、支付事实、领取码与权益正文。所有写都带前置状态条件。
 */
public interface CommerceConsoleMapper {

    // ---------------------------------------------------------------- 营销商品
    @Select("<script>select id, product_code, product_name, product_kind, purchase_kind, sale_city, fulfillment_route, "
            + "cash_price, points_price, validity_kind, valid_days, fixed_valid_from, fixed_valid_to, seat_count, "
            + "gift_allowed, status, sale_start_at, sale_end_at, terms_version, revision, update_time "
            + "from marketing_product <where>"
            + "<if test='status != null'> and status = #{status}</if>"
            + "<if test='kind != null'> and product_kind = #{kind}</if>"
            + "</where> order by id desc limit #{limit}</script>")
    List<Map<String, Object>> selectProducts(@Param("status") Integer status, @Param("kind") Integer kind,
                                             @Param("limit") int limit);

    @Select("select id, product_code, product_name, status, revision from marketing_product where id = #{id} for update")
    Map<String, Object> selectProductForUpdate(@Param("id") long id);

    /** 只能从在售(1)停到停售(2);改动递增 revision,已下的单按自己的快照履约。 */
    @Update("update marketing_product set status = 2, revision = revision + 1, update_time = now() "
            + "where id = #{id} and status = 1")
    int stopProductSale(@Param("id") long id);

    @Select("<script>select id, order_no, member_id, product_id, product_revision, fulfillment_kind, pay_mode, quantity, "
            + "total_cash_amount, total_points_amount, currency, status, fulfillment_state, create_time "
            + "from marketing_product_order <where>"
            + "<if test='productId != null'> and product_id = #{productId}</if>"
            + "<if test='status != null'> and status = #{status}</if>"
            + "</where> order by id desc limit #{limit}</script>")
    List<Map<String, Object>> selectProductOrders(@Param("productId") Long productId, @Param("status") Integer status,
                                                  @Param("limit") int limit);

    // ---------------------------------------------------------------- 探索套票(只读)
    @Select("<script>select o.id, o.registration_id, o.holder_member_id, o.topic_id, o.activity_id, o.policy_version, "
            + "o.paid_amount, o.valid_from, o.valid_to, o.create_time, "
            + "(select count(*) from explore_bundle_unit u where u.registration_id = o.registration_id) as unit_total, "
            + "(select count(*) from explore_bundle_unit u where u.registration_id = o.registration_id "
            + "and u.status = 'REDEEMED') as unit_redeemed "
            + "from explore_bundle_order o <where>"
            + "<if test='topicId != null'> and o.topic_id = #{topicId}</if>"
            + "</where> order by o.id desc limit #{limit}</script>")
    List<Map<String, Object>> selectBundleOrders(@Param("topicId") Long topicId, @Param("limit") int limit);

    // ---------------------------------------------------------------- 俱乐部会费
    @Select("<script>select p.id, p.club_id, p.name, p.duration_days, p.price_fen, p.grace_days, p.past_due_days, "
            + "p.exit_policy, p.refund_policy, p.status, p.version, p.update_time, "
            + "coalesce(s.enabled, 0) as club_dues_enabled "
            + "from club_membership_plan p left join club_membership_setting s on s.club_id = p.club_id "
            + "where p.del_flag = 0"
            + "<if test='clubId != null'> and p.club_id = #{clubId}</if>"
            + "<if test='status != null'> and p.status = #{status}</if>"
            + " order by p.id desc limit #{limit}</script>")
    List<Map<String, Object>> selectDuesPlans(@Param("clubId") Long clubId, @Param("status") String status,
                                              @Param("limit") int limit);

    @Select("select id, club_id, name, status, version from club_membership_plan where id = #{id} and del_flag = 0 for update")
    Map<String, Object> selectDuesPlanForUpdate(@Param("id") long id);

    /** 只能 ACTIVE → INACTIVE:不再开新账单;已付权益期按各自快照继续。 */
    @Update("update club_membership_plan set status = 'INACTIVE', version = version + 1, update_by = #{operator}, "
            + "update_time = now() where id = #{id} and status = 'ACTIVE' and del_flag = 0")
    int deactivateDuesPlan(@Param("id") long id, @Param("operator") String operator);

    @Select("<script>select id, invoice_no, club_id, member_id, plan_id, plan_name_snapshot, duration_days_snapshot, "
            + "amount_fen, refund_policy_snapshot, status, create_time "
            + "from club_membership_invoice <where>"
            + "<if test='clubId != null'> and club_id = #{clubId}</if>"
            + "<if test='status != null'> and status = #{status}</if>"
            + "</where> order by id desc limit #{limit}</script>")
    List<Map<String, Object>> selectDuesInvoices(@Param("clubId") Long clubId, @Param("status") String status,
                                                 @Param("limit") int limit);
}
