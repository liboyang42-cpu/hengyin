package com.chengyinhub.business.platformpass;

import com.alibaba.fastjson2.JSONObject;

/** 小程序虚拟支付服务端接口。实现只做 HTTP + 签名，不做业务判定。 */
public interface XpayGateway {
    /** POST /xpay/query_order；返回微信原始 JSON（含 errcode 与 order）。传输失败抛异常。 */
    JSONObject queryOrder(String openid, String orderNo, int env);

    /** POST /xpay/refund_order；返回微信原始 JSON。 */
    JSONObject refundOrder(String openid, String orderNo, String refundOrderNo, long leftFee, long refundFee,
                           String reason, int env);

    /** POST /xpay/notify_provide_goods：发货推送未送达而由回查确认时补报已发货。 */
    JSONObject notifyProvideGoods(String orderNo, int env);
}
