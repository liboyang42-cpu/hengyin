package com.chengyinhub.business.payout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 记录调用、按预设回包的电商收付通桩。 */
class FakeEcommerceGateway implements EcommercePayGateway {
    final List<String> calls = new ArrayList<>();
    ApplymentStatus applyment;
    ShareResult share = new ShareResult("FINISHED", "W1", List.of());
    ShareResult query = new ShareResult("FINISHED", "W1", List.of());
    String returnResult = "SUCCESS";
    RuntimeException failNext;
    List<ProfitSharePlanner.Receiver> lastReceivers;

    private void maybeFail() { if (failNext != null) { RuntimeException e = failNext; failNext = null; throw e; } }

    @Override public Map<String, String> prepayJsapi(String s, String o, long t, String d, String p) { calls.add("prepay"); return Map.of(); }
    @Override public ApplymentSubmitted submitApplyment(String no, Map<String, Object> body) { calls.add("submit:" + no); maybeFail(); return new ApplymentSubmitted(2000001L); }
    @Override public ApplymentStatus queryApplyment(String no) { calls.add("query:" + no); maybeFail(); return applyment; }
    @Override public ShareResult createProfitSharing(String sub, String tx, String no, List<ProfitSharePlanner.Receiver> r, boolean finish) {
        calls.add("share:" + no + ":" + finish); lastReceivers = r; maybeFail(); return share;
    }
    @Override public ShareResult queryProfitSharing(String sub, String tx, String no) { calls.add("shareQuery:" + no); maybeFail(); return query; }
    @Override public ReturnResult createReturn(String sub, String no, String rno, String mchid, long fen, String d) {
        calls.add("return:" + mchid + ":" + fen); maybeFail(); return new ReturnResult(returnResult, "WR" + fen, "FAILED".equals(returnResult) ? "BALANCE_NOT_ENOUGH" : null);
    }
    @Override public ReturnResult queryReturn(String sub, String no, String rno) { calls.add("returnQuery"); return new ReturnResult(returnResult, null, null); }
    @Override public void finishProfitSharing(String sub, String tx, String no, String d) { calls.add("finish:" + no); maybeFail(); }
    @Override public RefundAccepted createRefund(String sub, String tx, String no, long r, long t, String reason) { calls.add("refund"); return new RefundAccepted("R1", no); }
}
