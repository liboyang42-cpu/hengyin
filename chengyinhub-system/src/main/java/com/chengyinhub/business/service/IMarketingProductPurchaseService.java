package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.MarketingProductOrderRequest;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;

import java.util.List;
import java.util.Map;

/**
 * 商品购买来源记录（C06 商品侧）。
 *
 * <p><b>不建第二可玩真源</b>：权益/席位由 M2-F 的
 * {@code MarketingPassGrantPort}（{@code IMarketingProductFulfillmentService}）在
 * {@code cms_registration} 上产出。本服务只负责：商品报价签名校验、真实积分扣减、
 * 真实微信支付事实校验、购买来源与父子映射记录。
 *
 * <p>首次使用型商品购买时<b>不建可玩行</b>（{@code expires_at=null} 等于永久通行），
 * 由 {@link #activateOnUse} 原子激活后才创建 {@code expires_at = now + validDays}。
 */
public interface IMarketingProductPurchaseService {

    Map<String, Object> purchase(long callerMemberId, MarketingProductOrderRequest request);

    /**
     * 现金支付事实落地。订单号必须属于本域，事实必须经
     * {@code WechatVerifiedPaymentFactService.requireVerified} 校验且金额一致。
     */
    Map<String, Object> settleVerifiedCashPayment(String orderNo, long factId, String factHash);

    /**
     * C07 支付回调 owner 适配（由 router 在引入 MKO 分支后调用）。
     * 只接受 paid=true 且分额等于订单冻结现金额；失败返回 false 让原回调重试。
     */
    boolean handlePaidOrder(String orderNo, boolean paid, Integer paidAmountFen,
                            String transactionId, WechatVerifiedPaymentFact.Reference reference);

    /**
     * 现金预下单（复用既有微信 gateway，不新通道）。
     * 需 {@code marketing.product.cash-enabled=true} 且 MKO 路由已由 owner 合入；
     * JSAPI 用会员 wxOpenId，APP 不需要 openid。
     */
    Map<String, Object> prepareCashPayment(long callerMemberId, String orderNo, String payChannel);

    Map<String, Object> cancel(long callerMemberId, String orderNo);

    /**
     * C07 退款到账后的营销侧撤销（由 {@code MarketingProductRefundConsumer} 调用）。
     * 只撤销礼品领取来源并标记本单退款；原 registration/积分返还在原 C07 与原入口完成。
     */
    Map<String, Object> revokeAfterRefund(String orderNo, String reason);

    List<Map<String, Object>> myOrders(long callerMemberId, int limit);

    /** 首次使用激活（时间型通行证从第一次玩开始计时）；owner 由登录身份确定。 */
    Map<String, Object> activateOnUse(long callerMemberId, long topicId);
}
