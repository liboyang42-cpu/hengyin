package com.chengyinhub.business.payout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 记录调用、按预设回包的电商收付通桩。 */
public class FakeEcommerceGateway implements EcommercePayGateway {
    public final List<String> calls = new ArrayList<>();
    public PartnerOrder partnerOrder;
    public RefundQuery refundQuery;
    public RuntimeException refundFailure;
    ApplymentStatus applyment;
    ShareResult share;
    ShareResult query;
    public String returnResult = "SUCCESS";
    RuntimeException failNext;
    List<ProfitSharePlanner.Receiver> lastReceivers;

    private void maybeFail() { if (failNext != null) { RuntimeException e = failNext; failNext = null; throw e; } }

    @Override public Map<String, String> prepayJsapi(String s, String o, long t, String d, String p) { calls.add("prepay"); return Map.of(); }
    @Override public ApplymentSubmitted submitApplyment(String no, Map<String, Object> body) { calls.add("submit:" + no); maybeFail(); return new ApplymentSubmitted(2000001L); }
    @Override public ApplymentStatus queryApplyment(String no) { calls.add("query:" + no); maybeFail(); return applyment; }
    @Override public ShareResult createProfitSharing(String sub, String tx, String no, List<ProfitSharePlanner.Receiver> r, boolean finish) {
        calls.add("share:" + no + ":" + finish); lastReceivers = r; maybeFail(); return share == null ? successfulShare() : share;
    }
    @Override public ShareResult queryProfitSharing(String sub, String tx, String no) { calls.add("shareQuery:" + no); maybeFail(); return query == null ? successfulShare() : query; }
    private ShareResult successfulShare() {
        return new ShareResult("FINISHED", "W1", lastReceivers == null ? List.of() : lastReceivers.stream()
                .map(r -> new ReceiverResult(r.type(), r.account(), r.appid(), r.amountFen(), "SUCCESS", null)).toList());
    }
    @Override public ReturnResult createReturn(String sub, String no, String rno, String mchid, long fen, String d) {
        calls.add("return:" + mchid + ":" + fen); maybeFail(); return new ReturnResult(returnResult, "WR" + fen, "FAILED".equals(returnResult) ? "BALANCE_NOT_ENOUGH" : null);
    }
    @Override public ReturnResult queryReturn(String sub, String no, String rno) { calls.add("returnQuery"); return new ReturnResult(returnResult, null, null); }
    @Override public void finishProfitSharing(String sub, String tx, String no, String d) { calls.add("finish:" + no); maybeFail(); }
    @Override public RefundAccepted createRefund(String sub, String tx, String no, long r, long t, String reason) {
        calls.add("refund:" + no + ":" + r + "/" + t);
        if (refundFailure != null) { RuntimeException e = refundFailure; refundFailure = null; throw e; }
        return new RefundAccepted("R1", no);
    }
    @Override public String paymentNotifyUrl() { return "https://api.example.com/api/registration/ecommerce-callback"; }
    @Override public PartnerOrder queryPartnerOrder(String sub, String no) {
        calls.add("partnerQuery:" + no);
        if (partnerOrder == null) throw new PartnerOrderNotFound("ORDER_NOT_EXIST", null);
        return partnerOrder;
    }
    @Override public void closePartnerOrder(String sub, String no) { calls.add("partnerClose:" + no); }
    @Override public PartnerOrder parsePaymentNotification(String body, Map<String, String> headers) { calls.add("parsePay"); return partnerOrder; }
    @Override public RefundNotice parseRefundNotification(String body, Map<String, String> headers) { calls.add("parseRefund"); return null; }
    @Override public RefundQuery queryRefund(String sub, String no) {
        calls.add("refundQuery:" + no);
        if (refundQueries.containsKey(no)) return refundQueries.get(no);
        if (refundQuery == null) throw new RefundNotFound("RESOURCE_NOT_EXISTS", null);
        return refundQuery;
    }

    // ---- l-payout3 合单
    public CombinedOrder combinedOrder;
    public List<CombinedSubRequest> lastCombinedSubs;
    public final Map<String, RefundQuery> refundQueries = new java.util.HashMap<>();

    @Override public Map<String, String> prepayCombinedJsapi(String no, List<CombinedSubRequest> subs, String openid) {
        calls.add("combinePrepay:" + no); lastCombinedSubs = subs; return Map.of("package", "prepay_id=P");
    }
    @Override public CombinedOrder queryCombinedOrder(String no) {
        calls.add("combineQuery:" + no);
        if (combinedOrder == null) throw new CombinedOrderNotFound("ORDER_NOT_EXIST", null);
        return combinedOrder;
    }
    @Override public void closeCombinedOrder(String no, List<CombinedSubRequest> subs) { calls.add("combineClose:" + no); }
    @Override public String parseCombinedNotification(String body, Map<String, String> headers) { calls.add("parseCombine"); return body; }
    @Override public String combinedNotifyUrl() { return "https://api.example.com/api/registration/ecommerce-combine-callback"; }
}
