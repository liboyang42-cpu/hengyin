package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.*;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Inbound owner adapters only. No SDK, SQL, channel inference or transaction spanning domain calls. */
@Component
public class WechatPaymentCallbackRouter {
    public record Outcome(String route, String result) { }
    private record Owner(String route, Predicate<String> accepts,
                         Function<Map<String, String>, String> mutation) implements WechatPaymentCallbackOwner {
        @Override public boolean claims(String no) { return accepts.test(no); }
        @Override public String apply(Map<String, String> facts) { return mutation.apply(facts); }
    }
    private final List<WechatPaymentCallbackOwner> owners;
    private final WechatRefundCallbackOwner depositRefund;
    private final WechatRefundCallbackOwner registrationRefund;

    @Autowired
    public WechatPaymentCallbackRouter(IClubMembershipService club, IMerchantCommerceService commerce,
            ICoopInviteService coop, ICmsRegistrationService registration, IRefundService refunds) {
        // Reserve CLM even when malformed/unknown: it must never fall through to registration.
        owners = List.of(
                new Owner("club-membership", no -> no != null && no.startsWith("CLM"), facts ->
                        ack(club != null && club.supportsOutTradeNo(facts.get("out_trade_no"))
                                && club.handlePaidInvoice(facts.get("out_trade_no"), paid(facts),
                                        cents(facts), facts.get("transaction_id")))),
                new Owner("merchant-commerce", no -> commerce != null && commerce.supportsOutTradeNo(no), facts ->
                        ack(commerce.handlePaidOrder(facts.get("out_trade_no"), paid(facts),
                                cents(facts), facts.get("transaction_id")))),
                new Owner("coop-deposit", no -> no != null && no.startsWith("DEP"), facts ->
                        ack(coop != null && paid(facts) && "CNY".equals(facts.get("currency"))
                                && coop.markDepositPaid(facts.get("out_trade_no"),
                                        cents(facts) == null ? null : cents(facts).longValue(), facts.get("transaction_id")))),
                new Owner("registration", no -> true, facts ->
                        registration == null ? "FAIL" : registration.handleWechatPayCallback(facts)));
        depositRefund = facts -> coop != null && coop.handleDepositRefundResult(facts);
        registrationRefund = facts -> refunds != null && refunds.handleRefundNotify(facts);
    }

    public Outcome payment(Map<String, String> verifiedFacts) {
        String no = verifiedFacts.get("out_trade_no");
        for (WechatPaymentCallbackOwner owner : owners) {
            if (owner.claims(no)) return new Outcome(owner.route(), owner.apply(verifiedFacts));
        }
        throw new IllegalStateException("No existing payment owner");
    }

    public boolean refund(Map<String, String> facts) {
        String no = facts.get("outRefundNo");
        return no != null && no.startsWith("RDEP") ? depositRefund.apply(facts) : registrationRefund.apply(facts);
    }

    public String refreshRefund(String requestedNo, Map<String, String> facts) {
        if (facts == null || requestedNo == null || !requestedNo.equals(facts.get("outRefundNo"))) return null;
        if (requestedNo.startsWith("RDEP") && !requestedNo.substring(1).equals(facts.get("outTradeNo"))) return null;
        return refund(facts) ? facts.get("status") : null;
    }

    private static boolean paid(Map<String, String> facts) { return "SUCCESS".equals(facts.get("trade_state")); }
    private static Integer cents(Map<String, String> facts) {
        return facts.get("total") == null ? null : Integer.valueOf(facts.get("total"));
    }
    private static String ack(boolean committed) { return committed ? "SUCCESS" : "FAIL"; }
}
