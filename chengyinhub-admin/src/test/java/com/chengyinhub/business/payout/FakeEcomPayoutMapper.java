package com.chengyinhub.business.payout;

import com.chengyinhub.business.domain.CreatorPayoutRequest;
import com.chengyinhub.business.domain.EcomLedgerComponent;
import com.chengyinhub.business.domain.EcomOrderSeller;
import com.chengyinhub.business.domain.EcomProfitShareLine;
import com.chengyinhub.business.domain.EcomProfitShareOrder;
import com.chengyinhub.business.domain.EcomProfitShareReturn;
import com.chengyinhub.business.domain.EcomRefund;
import com.chengyinhub.business.domain.EcomSubMerchant;
import com.chengyinhub.business.mapper.EcomPayoutMapper;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 内存版 mapper：只模拟本包用到的唯一键与 CAS 语义；真 SQL 由 EcomPayoutMapperMySqlIT 覆盖。 */
public class FakeEcomPayoutMapper implements EcomPayoutMapper {
    final List<EcomSubMerchant> subs = new ArrayList<>();
    final Map<String, Long> sellerMembers = new HashMap<>();
    final Map<Long, List<String>> memberSubMchids = new HashMap<>();
    public final List<EcomOrderSeller> trades = new ArrayList<>();
    final Map<Long, List<EcomLedgerComponent>> components = new HashMap<>();
    final List<EcomProfitShareOrder> orders = new ArrayList<>();
    final List<EcomProfitShareLine> lines = new ArrayList<>();
    final List<EcomProfitShareReturn> returns = new ArrayList<>();
    final List<CreatorPayoutRequest> payouts = new ArrayList<>();
    public final List<EcomRefund> refunds = new ArrayList<>();
    public final List<String> calls = new ArrayList<>();
    public java.math.BigDecimal partnerPortion = java.math.BigDecimal.ZERO;
    long seq = 100;

    @Override public EcomSubMerchant selectSubMerchant(String t, Long id) {
        return subs.stream().filter(s -> s.getOwnerType().equals(t) && s.getOwnerId().equals(id)).findFirst().orElse(null);
    }
    @Override public EcomSubMerchant selectSubMerchantById(Long id) {
        return subs.stream().filter(s -> s.getId().equals(id)).findFirst().orElse(null);
    }
    @Override public List<EcomSubMerchant> selectSubMerchantsInProgress(int limit) {
        return subs.stream().filter(s -> List.of("CHECKING", "ACCOUNT_NEED_VERIFY", "AUDITING", "NEED_SIGN").contains(s.getApplymentState())).toList();
    }
    @Override public int insertSubMerchant(EcomSubMerchant row) {
        if (selectSubMerchant(row.getOwnerType(), row.getOwnerId()) != null) throw new DuplicateKeyException("owner");
        row.setId(++seq); row.setApplymentState("DRAFT"); subs.add(row); return 1;
    }
    @Override public int markApplymentSubmitted(Long id, Long applymentId) {
        EcomSubMerchant s = selectSubMerchantById(id);
        if (s == null || !"DRAFT".equals(s.getApplymentState())) return 0;
        s.setApplymentId(applymentId); s.setApplymentState("CHECKING"); return 1;
    }
    @Override public int updateApplymentStatus(Long id, String expected, String state, String desc, String signUrl, String subMchid, Long applymentId) {
        EcomSubMerchant s = selectSubMerchantById(id);
        if (s == null || !expected.equals(s.getApplymentState())) return 0;
        if (s.getSubMchid() != null && subMchid != null && !s.getSubMchid().equals(subMchid)) return 0;
        s.setApplymentState(state); s.setApplymentStateDesc(desc); s.setSignUrl(signUrl);
        if (s.getSubMchid() == null) s.setSubMchid(subMchid);
        if (s.getApplymentId() == null) s.setApplymentId(applymentId);
        return 1;
    }
    @Override public int touchApplymentQueried(Long id, String desc, String signUrl) {
        EcomSubMerchant s = selectSubMerchantById(id); s.setApplymentStateDesc(desc); s.setSignUrl(signUrl); return 1;
    }
    @Override public List<String> selectFinishedSubMchidsForMember(Long memberId) { return memberSubMchids.getOrDefault(memberId, List.of()); }
    @Override public Long selectSellerMemberId(String t, Long id) { return sellerMembers.get(t + ":" + id); }
    @Override public int insertOrderSeller(EcomOrderSeller row) { trades.add(row); return 1; }
    @Override public EcomOrderSeller selectOrderSeller(String no) { return trades.stream().filter(t -> t.getOutTradeNo().equals(no)).findFirst().orElse(null); }
    @Override public int bindOrderSellerTransaction(String no, String tx) {
        EcomOrderSeller t = selectOrderSeller(no);
        if (t == null || t.getTransactionId() != null) return 0;
        t.setTransactionId(tx); return 1;
    }
    @Override public List<EcomOrderSeller> selectPaidSubMerchantTradesForTopic(Long topicId) {
        return trades.stream().filter(t -> t.getTransactionId() != null).toList();
    }
    @Override public List<EcomLedgerComponent> selectLedgerComponents(Long topicId, Long reg) { return components.getOrDefault(reg, List.of()); }
    @Override public int insertShareOrder(EcomProfitShareOrder row) {
        if (orders.stream().anyMatch(o -> o.getOutOrderNo().equals(row.getOutOrderNo()))) throw new DuplicateKeyException("order");
        row.setId(++seq); row.setAttempts(0); orders.add(row); return 1;
    }
    @Override public EcomProfitShareOrder selectShareOrder(String no, Long topicId) {
        return orders.stream().filter(o -> o.getOutTradeNo().equals(no) && o.getTopicId().equals(topicId)).findFirst().orElse(null);
    }
    @Override public List<EcomProfitShareOrder> selectShareOrdersByTrade(String no) { return orders.stream().filter(o -> o.getOutTradeNo().equals(no)).toList(); }
    @Override public List<EcomProfitShareOrder> selectShareOrdersByState(String state, int limit) { return orders.stream().filter(o -> o.getState().equals(state)).toList(); }
    @Override public int updateShareOrderState(Long id, String expected, String state, String wxOrderId, String reason) {
        for (EcomProfitShareOrder o : orders) if (o.getId().equals(id) && o.getState().equals(expected)) {
            o.setState(state); if (wxOrderId != null) o.setWxOrderId(wxOrderId); o.setManualReason(reason);
            o.setAttempts(o.getAttempts() + 1); return 1;
        }
        return 0;
    }
    @Override public int insertShareLine(EcomProfitShareLine row) { row.setId(++seq); lines.add(row); return 1; }
    @Override public List<EcomProfitShareLine> selectShareLines(Long id) { return lines.stream().filter(l -> l.getShareOrderId().equals(id)).toList(); }
    @Override public int insertReturn(EcomProfitShareReturn row) {
        if (selectReturn(row.getOutReturnNo()) != null) throw new DuplicateKeyException("return");
        row.setId(++seq); row.setState("PROCESSING"); returns.add(row); return 1;
    }
    @Override public EcomProfitShareReturn selectReturn(String no) { return returns.stream().filter(r -> r.getOutReturnNo().equals(no)).findFirst().orElse(null); }
    @Override public List<EcomProfitShareReturn> selectReturnsByShareOrder(Long id) { return returns.stream().filter(r -> r.getShareOrderId().equals(id)).toList(); }
    @Override public int updateReturnState(Long id, String expected, String state, String wxNo, String reason) {
        for (EcomProfitShareReturn r : returns) if (r.getId().equals(id) && r.getState().equals(expected)) {
            r.setState(state); r.setWxReturnNo(wxNo); r.setFailReason(reason); return 1;
        }
        return 0;
    }
    @Override public int insertCreatorPayout(CreatorPayoutRequest row) {
        if (selectCreatorPayout(row.getSourceType(), row.getSourceId(), row.getCreatorMemberId()) != null) throw new DuplicateKeyException("payout");
        row.setId(++seq); row.setState("REQUESTED"); payouts.add(row); return 1;
    }
    @Override public CreatorPayoutRequest selectCreatorPayout(String t, Long id, Long m) {
        return payouts.stream().filter(p -> p.getSourceType().equals(t) && p.getSourceId().equals(id)
                && Objects.equals(p.getCreatorMemberId(), m)).findFirst().orElse(null);
    }
    @Override public int updateCreatorPayoutState(Long id, String expected, String state, String ref, String reason) {
        for (CreatorPayoutRequest p : payouts) if (p.getId().equals(id) && p.getState().equals(expected)) {
            p.setState(state); p.setProviderRef(ref); p.setFailReason(reason); return 1;
        }
        return 0;
    }

    // ---- l-payout2 ----
    @Override public int insertRefund(EcomRefund row) {
        calls.add("insertRefund:" + row.getOutRefundNo());
        if (selectRefund(row.getOutRefundNo()) != null) throw new DuplicateKeyException("refund");
        EcomRefund copy = new EcomRefund();
        copy.setOutRefundNo(row.getOutRefundNo()); copy.setOutTradeNo(row.getOutTradeNo()); copy.setSubMchid(row.getSubMchid());
        copy.setTransactionId(row.getTransactionId()); copy.setRefundFen(row.getRefundFen()); copy.setTotalFen(row.getTotalFen());
        copy.setState("RETURNING"); refunds.add(copy); return 1;
    }
    @Override public EcomRefund selectRefund(String no) {
        EcomRefund r = refunds.stream().filter(x -> x.getOutRefundNo().equals(no)).findFirst().orElse(null);
        if (r == null) return null;
        EcomRefund copy = new EcomRefund();
        copy.setOutRefundNo(r.getOutRefundNo()); copy.setOutTradeNo(r.getOutTradeNo()); copy.setSubMchid(r.getSubMchid());
        copy.setTransactionId(r.getTransactionId()); copy.setRefundFen(r.getRefundFen()); copy.setTotalFen(r.getTotalFen());
        copy.setState(r.getState()); copy.setRefundId(r.getRefundId()); copy.setFailReason(r.getFailReason());
        return copy;
    }
    @Override public List<EcomRefund> selectRefundsByTrade(String no) { return refunds.stream().filter(r -> r.getOutTradeNo().equals(no)).toList(); }
    @Override public List<EcomRefund> selectRefundsByStates(List<String> states, int limit) { return refunds.stream().filter(r -> states.contains(r.getState())).toList(); }
    @Override public int updateRefundState(String no, String expected, String state, String refundId, String reason) {
        for (EcomRefund r : refunds) if (r.getOutRefundNo().equals(no) && r.getState().equals(expected)) {
            r.setState(state); if (refundId != null) r.setRefundId(refundId); r.setFailReason(reason); return 1;
        }
        return 0;
    }
    /** 内存版不知道结算头到期时间：计划中的都算到期（到期 SQL 由真库 IT 覆盖）。 */
    @Override public List<EcomProfitShareOrder> selectDuePlannedShareOrders(java.util.Date now, int limit) { return selectShareOrdersByState("PLANNED", limit); }
    @Override public List<Long> selectSettledTopicsMissingSharePlans(int limit) { return List.of(); }
    @Override public java.math.BigDecimal sumPartnerPortionForSettlement(Long settlementId) { calls.add("partnerPortion:" + settlementId); return partnerPortion; }
    @Override public int countPartnerPaymentFact(Long paymentFactId) { calls.add("partnerFact:" + paymentFactId); return 0; }
    @Override public List<EcomSubMerchant> selectSubMerchantsForAdmin(String state, int limit) { return subs; }
    @Override public List<EcomProfitShareOrder> selectShareOrdersForAdmin(String state, Long topicId, int limit) { return orders; }
    @Override public List<EcomProfitShareReturn> selectReturnsByState(String state, int limit) {
        return returns.stream().filter(r -> state == null || r.getState().equals(state)).toList();
    }
    @Override public EcomProfitShareOrder selectShareOrderById(Long id) { return orders.stream().filter(o -> o.getId().equals(id)).findFirst().orElse(null); }
    @Override public EcomProfitShareReturn selectReturnById(Long id) { return returns.stream().filter(r -> r.getId().equals(id)).findFirst().orElse(null); }
    @Override public int deleteShareLines(Long id) { int before = lines.size(); lines.removeIf(l -> l.getShareOrderId().equals(id)); return before - lines.size(); }
    @Override public int replanShareOrder(Long id, String state, long sharedFen, String planHash, String reason) {
        for (EcomProfitShareOrder o : orders) if (o.getId().equals(id) && "MANUAL".equals(o.getState()) && o.getWxOrderId() == null) {
            o.setState(state); o.setSharedFen(sharedFen); o.setPlanHash(planHash); o.setManualReason(reason); return 1;
        }
        return 0;
    }
}
