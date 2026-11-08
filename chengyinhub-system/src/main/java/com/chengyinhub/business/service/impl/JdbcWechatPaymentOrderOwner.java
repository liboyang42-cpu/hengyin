package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.WechatPaymentOrderOwner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Reuses the original four row-lock queries. No SDK, business service or side effects. */
@Component
public class JdbcWechatPaymentOrderOwner implements WechatPaymentOrderOwner {
    @Autowired private org.springframework.jdbc.core.JdbcTemplate paymentIntentJdbc;
    @Override
    public Snapshot lock(String businessNo) {
        String type;
        String sql;
        boolean cents = false;
        if (businessNo.startsWith("CLM")) {
            type = "CLM"; cents = true;
            sql = "SELECT id,amount_fen AS amount,CASE WHEN status='PAYING' THEN 1 ELSE 0 END AS payable FROM club_membership_invoice WHERE out_trade_no=? FOR UPDATE";
        } else if (businessNo.startsWith("DEP")) {
            type = "DEP";
            sql = "SELECT id,deposit_amount AS amount,CASE WHEN deposit_status=1 AND status=1 AND del_flag=0 THEN 1 ELSE 0 END AS payable FROM coop_invite WHERE deposit_out_trade_no=? FOR UPDATE";
        } else if (businessNo.startsWith("TPLTM_") || businessNo.startsWith("PROMOT_") || businessNo.startsWith("CUSTOM_")) {
            type = "COMMERCE";
            sql = "SELECT id,pay_amount AS amount,CASE WHEN status=0 THEN 1 ELSE 0 END AS payable FROM oms_order WHERE order_sn=? FOR UPDATE";
        } else if (businessNo.startsWith("MKO")) {
            // 营销商品订单(现金单)。type 由这次真实命中的行决定(该行确实存在于
            // marketing_product_order),不是凭前缀返回一个未经核对的字符串:
            // 同一个值会被写进 wx_pay_order_intent.biz_type,
            // WechatVerifiedPaymentFactService.matchesFrozenIntent 再用本处返回值反查比对。
            // ⚠️ 必须 <= 16 字符:wx_pay_order_intent.biz_type 是 VARCHAR(16) ascii。
            // payable 只认「待现金支付 + 现金模式」:积分单或已付/已退订单一律不可支付,
            // 避免把已结算过的订单再当成可支付意图。
            type = "MARKETING";
            sql = "SELECT id,total_cash_amount AS amount,"
                    + "CASE WHEN status=0 AND pay_mode=1 AND total_cash_amount IS NOT NULL AND total_cash_amount>0"
                    + " THEN 1 ELSE 0 END AS payable "
                    + "FROM marketing_product_order WHERE order_no=? FOR UPDATE";
        } else {
            type = "REGISTRATION";
            sql = "SELECT id,wechat_payment_amount AS amount,CASE WHEN payment_status=1 AND registration_status=1 THEN 1 ELSE 0 END AS payable FROM cms_registration WHERE registration_no=? FOR UPDATE";
        }
        var rows = paymentIntentJdbc.queryForList(sql, businessNo);
        if (rows.isEmpty()) throw new WechatPaymentOrderOwner.MissingOrderException();
        if (rows.size() != 1 || !(rows.get(0).get("id") instanceof Number id)
                || rows.get(0).get("amount") == null) throw new IllegalStateException("已提交业务支付身份缺失");
        var row = rows.get(0);
        boolean payable = row.get("payable") instanceof Number flag && flag.intValue() == 1;
        return new Snapshot(type, id, new java.math.BigDecimal(row.get("amount").toString()), cents, payable);
    }
}
