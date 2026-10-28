package com.chengyinhub.business.service.impl;

import java.util.Map;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.business.service.RefundRequestResult;
import com.wechat.pay.java.core.exception.ValidationException;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.refund.model.RefundNotification;
import com.wechat.pay.java.service.refund.model.Status;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Existing API facade. Inbound callbacks depend on owners; owners depend only on the outbound SDK port. */
@Service
public class WechatPayServiceImpl implements IWechatPayService {
    private static final Logger logger = LoggerFactory.getLogger(WechatPayServiceImpl.class);
    @Autowired private WechatPaymentSdkGateway gateway;
    @Autowired private WechatPaymentCallbackRouter callbacks;
    @Autowired private AlarmService alarmService;

    /** Compatibility lifecycle entry for existing explicit fixtures; production SDK initializes itself once. */
    public void init() { gateway.init(); }

    @Override
    public boolean isReady(){ return gateway.isReady(); }

    @Override
    public boolean isAppReady(){ return gateway.isAppReady(); }

    @Override
    public RefundRequestResult submitRefund(String outTradeNo, String outRefundNo,
                                            java.math.BigDecimal refundAmount,
                                            java.math.BigDecimal totalAmount, String reason){ return gateway.submitRefund(outTradeNo, outRefundNo, refundAmount, totalAmount, reason); }

    @Override
    public RefundRequestResult submitRefund(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference originalPayment,
                                            String outTradeNo, String outRefundNo, java.math.BigDecimal refundAmount,
                                            java.math.BigDecimal totalAmount, String reason) {
        return gateway.submitRefund(originalPayment,outTradeNo,outRefundNo,refundAmount,totalAmount,reason);
    }

    @Override
    public Map<String, String> transferToBalance(String outBatchNo, String outDetailNo,
                                                  java.math.BigDecimal amountYuan, String openid, String remark,
                                                  String appid){ return gateway.transferToBalance(outBatchNo, outDetailNo, amountYuan, openid, remark, appid); }

    @Override
    public Map<String, String> queryTransferResult(String outBatchNo, String outDetailNo){ return gateway.queryTransferResult(outBatchNo, outDetailNo); }

    @Override
    public Map<String, String> transferToBalance(CashWithdrawTransferIntent intent) { return gateway.transferToBalance(intent); }

    @Override
    public Map<String, String> queryTransferResult(CashWithdrawTransferIntent intent) { return gateway.queryTransferResult(intent); }

    @Override
    public Map<String, String> createOrder(CmsRegistration registration){ return gateway.createOrder(registration); }

    @Override
    public Map<String, String> createAppOrder(CmsRegistration registration){ return gateway.createAppOrder(registration); }

    @Override
    public Map<String, String> createGenericAppOrder(String outTradeNo, java.math.BigDecimal amountYuan,
                                                      String description){ return gateway.createGenericAppOrder(outTradeNo, amountYuan, description); }

    @Override
    public Map<String, String> createGenericOrder(String outTradeNo, java.math.BigDecimal amountYuan, String description, String openid){ return gateway.createGenericOrder(outTradeNo, amountYuan, description, openid); }

    @Override
    public String queryRefundStatus(String outRefundNo){ return gateway.queryRefundStatus(outRefundNo); }

    @Override
    public Map<String,String> queryRefundFacts(String outRefundNo){ return gateway.queryRefundFacts(outRefundNo); }

    @Override
    public Map<String,String> queryRefundFacts(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference originalPayment, String outRefundNo) {
        return gateway.queryRefundFacts(originalPayment,outRefundNo);
    }

    @Override
    public Map<String, String> queryOrderStatus(String outTradeNo){ return gateway.queryOrderStatus(outTradeNo); }

    @Override
    public boolean closeOrder(String outTradeNo){ return gateway.closeOrder(outTradeNo); }

    @Override
    public boolean closePaymentAttempt(String businessOutTradeNo, String observedWxOutTradeNo){ return gateway.closePaymentAttempt(businessOutTradeNo, observedWxOutTradeNo); }

    @Override
    public boolean lockPaymentAttemptForUpdate(String businessOutTradeNo, String observedWxOutTradeNo){ return gateway.lockPaymentAttemptForUpdate(businessOutTradeNo, observedWxOutTradeNo); }

    @Override
    public boolean lockClosablePaymentAttemptForUpdate(String businessOutTradeNo,String observedWxOutTradeNo) {
        return gateway.lockClosablePaymentAttemptForUpdate(businessOutTradeNo,observedWxOutTradeNo);
    }

    @Override
    public String handlePayCallback(String requestBody, Map<String, String> headers) {
        boolean verifiedNotification = false;
        String safeReference = "UNKNOWN";
        try {
            Transaction transaction = gateway.parsePaymentNotification(requestBody, headers);
            verifiedNotification = true;
            safeReference = transaction != null && transaction.getOutTradeNo() != null
                    && transaction.getOutTradeNo().matches("[A-Za-z0-9_-]{1,64}")
                    ? transaction.getOutTradeNo() : "UNKNOWN";

            // 验签只证明通知来自微信，不能证明这笔钱属于本订单创建时的应用/商户/币种。
            // 四条路线共享同一道事实闸门；按原商户/原单号查询微信创建单据，不猜默认 appid。
            Map<String, String> resultMap = gateway.verifiedPaymentFacts(transaction);
            if (resultMap == null) {
                alarmService.alarmThrottled("payment.callback.facts", "支付回调原单事实缺失或不符",
                        "order=" + safeReference + " result=FAIL");
                return "{\"code\": \"FAIL\",\"message\": \"支付事实核验失败\"}";
            }
            WechatPaymentCallbackRouter.Outcome outcome = callbacks.payment(resultMap);
            String result = outcome.result();
            String route = outcome.route();
            String otn = resultMap.get("out_trade_no");

            // [P0-资损] 按业务处理结果回 ACK:处理失败必须回 FAIL 触发微信重试,否则掉单
            // (原实现无论 result 如何都回 SUCCESS,业务失败后微信不再通知 → 用户付了钱但订单未处理)
            if (!"SUCCESS".equalsIgnoreCase(result)) {
                String reference = otn != null && otn.matches("[A-Za-z0-9_-]{1,64}") ? otn : "UNKNOWN";
                logger.error("Verified payment callback failed route={} order={}; returning FAIL", route, reference);
                // Registration lookup reports its own missing-order event; do not emit the same failure twice.
                if (!"registration".equals(route)) {
                    alarmService.alarmThrottled("payment.callback." + route, "支付回调业务订单处理失败",
                            "route=" + route + " order=" + reference + " result=FAIL");
                }
                return "{\"code\": \"FAIL\",\"message\": \"业务处理失败,请重试\"}";
            }

            // 返回成功响应
            return "{\"code\": \"SUCCESS\",\"message\": \"成功\"}";
        } catch (WechatPaymentCallbackRouter.RoutingException e) {
            alarmService.alarmThrottled("payment.callback.routing", "支付回调业务路由不唯一",
                    "order=" + safeReference + " matches=" + e.matches() + " result=FAIL");
            return "{\"code\": \"FAIL\",\"message\": \"支付路由核验失败\"}";
        } catch (ValidationException e) {
            logger.warn("微信支付回调签名验证失败 type={}", e.getClass().getSimpleName());
            // 未验签的请求体不可信：只发固定事件，不带任何请求内容；按事件限频防止伪造请求刷屏。
            alarmService.alarmThrottled("payment.callback.signature", "支付回调验签失败",
                    "type=" + e.getClass().getSimpleName() + " result=FAIL");
            return "{\"code\": \"FAIL\",\"message\": \"签名验证失败\"}";
        } catch (Exception e) {
            logger.warn("微信支付回调处理异常 order={} type={}", safeReference, e.getClass().getSimpleName());
            if (verifiedNotification) {
                alarmService.alarmThrottled("payment.callback.business_exception", "支付回调业务处理异常",
                        "order=" + safeReference + " type=" + e.getClass().getSimpleName() + " result=FAIL");
            }
            return "{\"code\": \"FAIL\",\"message\": \"支付结果暂无法核验，请重试\"}";
        }
    }

    @Override
    public String handleRefundCallback(String requestBody, Map<String, String> headers) {
        boolean verifiedRefund = false;
        String safeRefundReference = "UNKNOWN";
        try {
            RefundNotification notif = gateway.parseRefundNotification(requestBody, headers);
            verifiedRefund = true;
            safeRefundReference = notif.getOutRefundNo() != null
                    && notif.getOutRefundNo().matches("[A-Za-z0-9_-]{1,64}") ? notif.getOutRefundNo() : "UNKNOWN";
            Status st = notif.getRefundStatus();
            Map<String,String> facts=gateway.verifiedRefundFacts(notif);
            if (!gateway.matchesRefundNotification(notif,facts)) {
                gateway.recordRejectedRefundNotification(notif,facts);
                alarmService.alarmThrottled("refund.callback.facts", "退款回调原单事实缺失或不符",
                        "refund="+safeRefundReference+" result=FAIL");
                return "{\"code\": \"FAIL\",\"message\": \"退款事实暂无法核验\"}";
            }
            boolean ok=callbacks.refund(facts);
            if (!ok) {
                logger.error("微信退款回调业务处理失败,回 FAIL 触发重试: out_refund_no={}", notif.getOutRefundNo());
                alarmService.alarmThrottled("refund.callback.failed", "退款回调业务处理失败",
                        "refund=" + safeRefundReference + " result=FAIL");
                return "{\"code\": \"FAIL\",\"message\": \"业务处理失败,请重试\"}";
            }
            return "{\"code\": \"SUCCESS\",\"message\": \"成功\"}";
        } catch (ValidationException e) {
            logger.error("微信退款回调签名验证失败", e);
            alarmService.alarmThrottled("refund.callback.signature", "退款回调验签失败",
                    "type=" + e.getClass().getSimpleName() + " result=FAIL");
            return "{\"code\": \"FAIL\",\"message\": \"签名验证失败: " + e.getMessage() + "\"}";
        } catch (Exception e) {
            logger.error("微信退款回调处理异常", e);
            if (verifiedRefund) {
                alarmService.alarmThrottled("refund.callback.exception", "退款回调业务处理异常",
                        "refund=" + safeRefundReference + " type=" + e.getClass().getSimpleName() + " result=FAIL");
            }
            return "{\"code\": \"FAIL\",\"message\": \"处理异常: " + e.getMessage() + "\"}";
        }
    }

    @Override
    public String refreshPayoutFromWx(String outRefundNo) {
        Map<String, String> facts = queryRefundFacts(outRefundNo);
        return callbacks.refreshRefund(outRefundNo, facts);
    }

    String freezePaymentIntent(String outTradeNo, String mchid, String appid, String tradeType,
                             String currency, Integer totalFen, String openid, String description, String notifyUrl) {
        return gateway.freezePaymentIntent(outTradeNo, mchid, appid, tradeType, currency, totalFen,
                openid, description, notifyUrl);
    }
}
