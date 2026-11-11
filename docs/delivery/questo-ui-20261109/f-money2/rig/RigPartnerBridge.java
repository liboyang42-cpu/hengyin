package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.payout.EcommercePayGateway;
import com.chengyinhub.business.service.RefundRequestResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * [f-money2 rig] 只编进 rig 的 exploded jar,仓库产品码不引用。
 *
 * <p>rig 用 E2eWechatPayStub 顶替了直连商户号通道(没有商户私钥),它也顶掉了 WechatPaymentSdkGateway /
 * WechatPayServiceImpl 里「先问电商收付通适配器」的那几处分支。本类把那几处分支原样接回:下单、退款受理、
 * 退款查单先交给产品真实的 {@link EcommercePartnerPaymentAdapter}(开关、卖家判定、意图冻结、原单核对全是产品代码),
 * 电商支付 / 退款通知按 WechatPayServiceImpl 同一顺序「验签解析 → 查原单核对写事实 → 真实业务路由」。
 * 适配器关闭或该单不是卖家收款单时返回 null,调用方回落直连通道模拟。
 */
@Component
public class RigPartnerBridge {
    @Autowired(required = false) @Lazy private EcommercePartnerPaymentAdapter partner;
    @Autowired @Lazy private EcommercePayGateway ecommerceGateway;
    @Autowired @Lazy private WechatPaymentCallbackRouter callbacks;
    @Autowired @Lazy private WechatVerifiedPaymentFactService verifiedFacts;

    private boolean on() { return partner != null && partner.enabled(); }

    public Map<String, String> createOrder(CmsRegistration registration, String platformMchid) {
        return on() ? partner.createOrder(registration, platformMchid) : null;
    }

    public RefundRequestResult submitRefund(WechatVerifiedPaymentFact.Reference original, String outTradeNo,
                                            String outRefundNo, long refundFen, long totalFen, String reason) {
        if (!on()) return null;
        WechatVerifiedPaymentFact paid = verifiedFacts.requireVerified(original).facts();
        var seller = partner.sellerForPaidFact(paid);
        if (seller == null) return null;
        if (!paid.businessOutTradeNo().equals(outTradeNo)) return RefundRequestResult.UNKNOWN;
        return partner.submitRefund(paid, seller, outRefundNo, refundFen, totalFen, reason);
    }

    /** 返回 null 表示不是卖家收款单;非卖家单由调用方处理。 */
    public Map<String, String> queryRefundFacts(WechatVerifiedPaymentFact.Reference original, String outRefundNo, boolean[] handled) {
        handled[0] = false;
        if (!on()) return null;
        WechatVerifiedPaymentFact paid = verifiedFacts.requireVerified(original).facts();
        var seller = partner.sellerForPaidFact(paid);
        if (seller == null) return null;
        handled[0] = true;
        return partner.queryRefundFacts(paid, seller, outRefundNo);
    }

    public String ecommercePayCallback(String body, Map<String, String> headers) {
        if (!on()) return "{\"code\": \"FAIL\",\"message\": \"未开通\"}";
        EcommercePayGateway.PartnerOrder notice = ecommerceGateway.parsePaymentNotification(body, headers);
        Map<String, String> facts = partner.verifiedNotificationFacts(notice);
        if (facts == null) return "{\"code\": \"FAIL\",\"message\": \"支付事实核验失败\"}";
        String result = callbacks.payment(facts).result();
        return "SUCCESS".equalsIgnoreCase(result) ? "{\"code\": \"SUCCESS\",\"message\": \"成功\"}"
                : "{\"code\": \"FAIL\",\"message\": \"业务处理失败,请重试\"}";
    }

    public String ecommerceRefundCallback(String body, Map<String, String> headers) {
        if (!on()) return "{\"code\": \"FAIL\",\"message\": \"未开通\"}";
        EcommercePayGateway.RefundNotice notice = ecommerceGateway.parseRefundNotification(body, headers);
        Map<String, String> facts = partner.verifiedRefundNoticeFacts(notice);
        if (facts == null || facts.get("outRefundNo") == null || !callbacks.refund(facts))
            return "{\"code\": \"FAIL\",\"message\": \"退款事实暂无法核验\"}";
        return "{\"code\": \"SUCCESS\",\"message\": \"成功\"}";
    }
}
