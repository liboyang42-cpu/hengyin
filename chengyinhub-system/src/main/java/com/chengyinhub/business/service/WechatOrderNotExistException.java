package com.chengyinhub.business.service;

/**
 * 微信查单明确返回 ORDER_NOT_EXIST(商户号下没有这个 out_trade_no)。
 * 仍是 RuntimeException:既有调用方按「查单失败」处理不受影响;只有 PAYING 超时对账据此判「微信侧无单 = 未支付」。
 */
public class WechatOrderNotExistException extends RuntimeException {

    /**
     * 微信侧查不到单时的宽限:{@code pay_expire_time} 之后再等这么久才按「未支付」关单。
     * 宽限内预下单请求可能仍在途,查不到不代表之后不会建出来。
     *
     * <p><b>取 2 小时而不是 30 分钟</b>(清单 5):宽限的意义是「等到微信侧不可能再冒出这笔单」,
     * 而这个时点由 prepay_id 的有效期(2 小时)决定,不是由「预下单请求在途多久」决定 ——
     * 预下单成功返回过 prepay_id、回包丢在网络上的情况下,用户手里那个 prepay_id 在 2 小时内
     * 随时可以拉起支付,订单也随之在微信侧出现。取 30 分钟就是在这段窗口里
     * 「查不到 = 判它没付」并关单回补库存/积分,而用户随后仍可能付款成功 ⇒ 关掉的单收到钱。
     * 宽限只影响「多晚关单」,不影响钱的安全,宁可长。
     */
    public static final long GRACE_MILLIS = 2 * 60 * 60 * 1000L;
    public WechatOrderNotExistException(String message, Throwable cause) {
        super(message, cause);
    }
}
