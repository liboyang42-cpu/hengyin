package com.chengyinhub.business.payout;

import com.chengyinhub.business.domain.EcomCombineOrder;
import com.chengyinhub.business.domain.EcomCombineRefund;
import com.chengyinhub.business.domain.EcomCombineReturn;
import com.chengyinhub.business.domain.EcomCombineSettlement;
import com.chengyinhub.business.domain.EcomCombineSubOrder;
import com.chengyinhub.business.domain.EcomSupplierPayable;
import com.chengyinhub.business.mapper.EcomCombineMapper;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 内存版合单 mapper：只模拟唯一键与 CAS 语义；真 SQL 由 EcomCombineMapperMySqlIT 覆盖。 */
public class FakeEcomCombineMapper implements EcomCombineMapper {
    public final Map<String, EcomCombineOrder> orders = new HashMap<>();
    public final List<EcomCombineSubOrder> subs = new ArrayList<>();
    public final List<EcomCombineRefund> refunds = new ArrayList<>();
    public final List<EcomCombineSettlement> settlements = new ArrayList<>();
    public final List<EcomCombineReturn> returns = new ArrayList<>();
    public final List<EcomSupplierPayable> payables = new ArrayList<>();
    /** topicId → 已付合单（含 registrationId）。 */
    public final Map<Long, List<String>> topicOrders = new HashMap<>();
    public final Map<String, Long> registrationIds = new HashMap<>();
    public BigDecimal combinedPortion = BigDecimal.ZERO;
    public int combinedFacts = 0;
    public final List<String> calls = new ArrayList<>();
    long seq = 500;

    @Override public int insertCombineOrder(EcomCombineOrder row) {
        calls.add("insertCombineOrder");
        if (orders.containsKey(row.getOutTradeNo())) throw new DuplicateKeyException("dup");
        EcomCombineOrder c = new EcomCombineOrder();
        org.springframework.beans.BeanUtils.copyProperties(row, c);
        orders.put(row.getOutTradeNo(), c);
        return 1;
    }
    @Override public EcomCombineOrder selectCombineOrder(String no) {
        calls.add("selectCombineOrder");
        EcomCombineOrder o = orders.get(no);
        if (o == null) return null;
        EcomCombineOrder c = new EcomCombineOrder();
        org.springframework.beans.BeanUtils.copyProperties(o, c);
        return c;
    }
    @Override public int bindCombinePaid(String no, String combineNo, String tx) {
        EcomCombineOrder o = orders.get(no);
        if (o == null || o.getCombineOutTradeNo() != null || o.getSellerTransactionId() != null) return 0;
        o.setCombineOutTradeNo(combineNo);
        o.setSellerTransactionId(tx);
        return 1;
    }
    @Override public int insertSubOrder(EcomCombineSubOrder row) {
        for (EcomCombineSubOrder s : subs) {
            if (s.getOutTradeNo().equals(row.getOutTradeNo())
                    || (s.getCombineOutTradeNo().equals(row.getCombineOutTradeNo()) && s.getRole().equals(row.getRole())))
                throw new DuplicateKeyException("dup");
        }
        row.setId(++seq);
        subs.add(row);
        return 1;
    }
    @Override public List<EcomCombineSubOrder> selectSubOrders(String combineNo) {
        return subs.stream().filter(s -> s.getCombineOutTradeNo().equals(combineNo))
                .sorted(Comparator.comparing(s -> "SELLER".equals(s.getRole()) ? 0 : 1)).map(s -> {
                    EcomCombineSubOrder c = new EcomCombineSubOrder();
                    org.springframework.beans.BeanUtils.copyProperties(s, c);
                    return c;
                }).toList();
    }
    @Override public int bindSubOrderTransaction(String combineNo, String role, String tx) {
        for (EcomCombineSubOrder s : subs) {
            if (s.getCombineOutTradeNo().equals(combineNo) && s.getRole().equals(role) && s.getTransactionId() == null) {
                s.setTransactionId(tx);
                return 1;
            }
        }
        return 0;
    }
    @Override public int insertRefund(EcomCombineRefund row) {
        for (EcomCombineRefund r : refunds) {
            if (r.getOutRefundNo().equals(row.getOutRefundNo())
                    || (r.getParentOutRefundNo().equals(row.getParentOutRefundNo()) && r.getRole().equals(row.getRole())))
                throw new DuplicateKeyException("dup");
        }
        refunds.add(row);
        return 1;
    }
    private EcomCombineRefund copyRefund(EcomCombineRefund r) {
        EcomCombineRefund c = new EcomCombineRefund();
        org.springframework.beans.BeanUtils.copyProperties(r, c);
        return c;
    }
    @Override public EcomCombineRefund selectRefund(String no) {
        return refunds.stream().filter(r -> r.getOutRefundNo().equals(no)).findFirst().map(this::copyRefund).orElse(null);
    }
    @Override public List<EcomCombineRefund> selectRefundsByParent(String parent) {
        return refunds.stream().filter(r -> r.getParentOutRefundNo().equals(parent))
                .sorted(Comparator.comparing(r -> "SELLER".equals(r.getRole()) ? 0 : 1)).map(this::copyRefund).toList();
    }
    @Override public List<EcomCombineRefund> selectRefundsByTrade(String no) {
        return refunds.stream().filter(r -> r.getBusinessOutTradeNo().equals(no)).map(this::copyRefund).toList();
    }
    @Override public int updateRefundState(String no, String expected, String state, String refundId, String failReason) {
        for (EcomCombineRefund r : refunds) {
            if (r.getOutRefundNo().equals(no) && r.getState().equals(expected)) {
                r.setState(state);
                if (refundId != null) r.setRefundId(refundId);
                r.setFailReason(failReason);
                return 1;
            }
        }
        return 0;
    }
    @Override public List<EcomCombineOrder> selectPaidCombineOrdersForTopic(Long topicId) {
        List<EcomCombineOrder> out = new ArrayList<>();
        for (String no : topicOrders.getOrDefault(topicId, List.of())) {
            EcomCombineOrder o = selectCombineOrder(no);
            if (o == null || o.getSellerTransactionId() == null) continue;
            o.setRegistrationId(registrationIds.get(no));
            out.add(o);
        }
        return out;
    }
    @Override public List<Long> selectSettledTopicsMissingCombineSettlements(int limit) { return List.of(); }
    @Override public int insertSettlement(EcomCombineSettlement row) {
        for (EcomCombineSettlement s : settlements) {
            if (s.getOutOrderNo().equals(row.getOutOrderNo())
                    || (s.getBusinessOutTradeNo().equals(row.getBusinessOutTradeNo()) && s.getTopicId().equals(row.getTopicId())))
                throw new DuplicateKeyException("dup");
        }
        row.setId(++seq);
        settlements.add(row);
        return 1;
    }
    @Override public EcomCombineSettlement selectSettlement(String no, Long topicId) {
        return settlements.stream().filter(s -> s.getBusinessOutTradeNo().equals(no) && s.getTopicId().equals(topicId)).findFirst().orElse(null);
    }
    @Override public EcomCombineSettlement selectSettlementById(Long id) {
        return settlements.stream().filter(s -> s.getId().equals(id)).findFirst().orElse(null);
    }
    @Override public List<EcomCombineSettlement> selectSettlementsByTrade(String no) {
        return settlements.stream().filter(s -> s.getBusinessOutTradeNo().equals(no)).toList();
    }
    @Override public List<EcomCombineSettlement> selectDuePlannedSettlements(Date now, int limit) {
        return settlements.stream().filter(s -> "PLANNED".equals(s.getState())).toList();
    }
    @Override public List<EcomCombineSettlement> selectSettlementsByState(String state, int limit) {
        return settlements.stream().filter(s -> state.equals(s.getState())).toList();
    }
    @Override public int updateSettlementState(Long id, String expected, String state, String wxOrderId, String manualReason) {
        for (EcomCombineSettlement s : settlements) {
            if (s.getId().equals(id) && s.getState().equals(expected)) {
                s.setState(state);
                if (wxOrderId != null) s.setWxOrderId(wxOrderId);
                if (manualReason != null) s.setManualReason(manualReason);
                return 1;
            }
        }
        return 0;
    }
    @Override public int insertReturn(EcomCombineReturn row) {
        for (EcomCombineReturn r : returns) {
            if (r.getOutReturnNo().equals(row.getOutReturnNo())
                    || (Objects.equals(r.getCombineSettlementId(), row.getCombineSettlementId())
                        && r.getParentOutRefundNo().equals(row.getParentOutRefundNo())))
                throw new DuplicateKeyException("dup");
        }
        returns.add(row);
        return 1;
    }
    @Override public EcomCombineReturn selectReturn(String no) {
        return returns.stream().filter(r -> r.getOutReturnNo().equals(no)).findFirst().orElse(null);
    }
    @Override public List<EcomCombineReturn> selectReturnsBySettlement(Long id) {
        return returns.stream().filter(r -> r.getCombineSettlementId().equals(id)).toList();
    }
    @Override public int updateReturnState(String no, String expected, String state, String wxReturnNo, String failReason) {
        for (EcomCombineReturn r : returns) {
            if (r.getOutReturnNo().equals(no) && r.getState().equals(expected)) {
                r.setState(state);
                if (wxReturnNo != null) r.setWxReturnNo(wxReturnNo);
                r.setFailReason(failReason);
                return 1;
            }
        }
        return 0;
    }
    @Override public int insertPayable(EcomSupplierPayable row) {
        for (EcomSupplierPayable p : payables) {
            if (p.getSource().equals(row.getSource()) && p.getBusinessOutTradeNo().equals(row.getBusinessOutTradeNo())
                    && p.getTopicId().equals(row.getTopicId()) && p.getSettlementId().equals(row.getSettlementId())
                    && p.getPayeeMemberId().equals(row.getPayeeMemberId()) && p.getLineType().equals(row.getLineType()))
                throw new DuplicateKeyException("dup");
        }
        row.setId(++seq);
        row.setState("PENDING");
        payables.add(row);
        return 1;
    }
    @Override public List<EcomSupplierPayable> selectPayablesByTrade(String no) {
        return payables.stream().filter(p -> p.getBusinessOutTradeNo().equals(no)).toList();
    }
    @Override public List<EcomSupplierPayable> selectPayablesForAdmin(String state, int limit) {
        return payables.stream().filter(p -> state == null || state.equals(p.getState())).toList();
    }
    @Override public int markPayablePaid(Long id, String ref, String by) {
        for (EcomSupplierPayable p : payables) {
            if (p.getId().equals(id) && "PENDING".equals(p.getState())) {
                p.setState("PAID");
                p.setPaidReference(ref);
                p.setPaidBy(by);
                return 1;
            }
        }
        return 0;
    }
    @Override public int holdPayablesForRefund(String no) {
        int n = 0;
        for (EcomSupplierPayable p : payables) {
            if (p.getBusinessOutTradeNo().equals(no) && "PENDING".equals(p.getState())) { p.setState("HOLD_REFUND"); n++; }
        }
        return n;
    }
    @Override public BigDecimal sumCombinedPortionForSettlement(Long settlementId) { calls.add("sumCombined"); return combinedPortion; }
    @Override public int countCombinedPaymentFact(Long paymentFactId) { calls.add("countCombined"); return combinedFacts; }
}
