package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.*;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Inbound owner adapters only. No SDK, SQL, channel inference or transaction spanning domain calls. */
@Component
public class WechatPaymentCallbackRouter {
    public record Outcome(String route, String result) { }
    public static final class RoutingException extends IllegalStateException {
        private final int matches;
        RoutingException(int matches) { super("Payment owner match count=" + matches); this.matches = matches; }
        public int matches() { return matches; }
    }
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
                        ack(paid(facts) && club != null && club.supportsOutTradeNo(facts.get("out_trade_no"))
                                && club.handlePaidInvoice(facts.get("out_trade_no"), paid(facts),
                                        cents(facts), facts.get("transaction_id"), verifiedPaymentReference(facts)))),
                new Owner("merchant-commerce", no -> commerce != null && commerce.supportsOutTradeNo(no), facts ->
                        ack(paid(facts) && commerce.handlePaidOrder(facts.get("out_trade_no"), paid(facts),
                                cents(facts), facts.get("transaction_id"), verifiedPaymentReference(facts)))),
                new Owner("coop-deposit", no -> no != null && no.startsWith("DEP"), facts ->
                        ack(coop != null && paid(facts) && "CNY".equals(facts.get("currency"))
                                && coop.markDepositPaid(facts.get("out_trade_no"),
                                        cents(facts) == null ? null : cents(facts).longValue(), facts.get("transaction_id"),
                                        new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference(
                                                Long.parseLong(facts.get("verified_fact_id")),facts.get("verified_fact_hash"))))),
                new Owner("registration", no -> no != null && registration != null && registration.getByRegistrationNo(no) != null, facts ->
                        registration == null ? "FAIL" : registration.handleWechatPayCallback(facts)));
        depositRefund = facts -> coop != null && coop.handleDepositRefundResult(facts);
        registrationRefund = facts -> refunds != null && refunds.handleRefundNotify(facts);
    }

    /** Explicit assembly seam for route tests; production uses the four existing owners above. */
    WechatPaymentCallbackRouter(List<WechatPaymentCallbackOwner> owners,
            WechatRefundCallbackOwner depositRefund, WechatRefundCallbackOwner registrationRefund) {
        this.owners = List.copyOf(owners);
        this.depositRefund = depositRefund;
        this.registrationRefund = registrationRefund;
    }

    public Outcome payment(Map<String, String> verifiedFacts) {
        if (verifiedFacts == null || verifiedFacts.get("out_trade_no") == null
                || verifiedFacts.get("out_trade_no").isBlank()) throw new RoutingException(0);
        Map<String, String> facts = Collections.unmodifiableMap(new HashMap<>(verifiedFacts));
        String no = facts.get("out_trade_no");
        List<WechatPaymentCallbackOwner> matches = new ArrayList<>();
        // Resolve every claim before invoking any mutation. List order must never choose an owner.
        for (WechatPaymentCallbackOwner owner : owners) {
            if (owner.claims(no)) matches.add(owner);
        }
        if (matches.size() != 1) throw new RoutingException(matches.size());
        WechatPaymentCallbackOwner owner = matches.get(0);
        return new Outcome(owner.route(), owner.apply(facts));
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
    private static com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference verifiedPaymentReference(Map<String,String> facts) {
        if (facts.get("verified_fact_id")==null || facts.get("verified_fact_hash")==null)
            throw new IllegalArgumentException("原成功支付事实引用必填");
        return new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference(
                Long.parseLong(facts.get("verified_fact_id")), facts.get("verified_fact_hash"));
    }

}
