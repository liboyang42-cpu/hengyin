package com.chengyinhub.business.payout;

import com.chengyinhub.business.domain.EcomLedgerComponent;
import com.chengyinhub.business.domain.EcomOrderSeller;
import com.chengyinhub.business.domain.EcomProfitShareLine;
import com.chengyinhub.business.domain.EcomProfitShareOrder;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * f-money 待决定 2：电商收付通不支持二级商户之间直接分账（业主 2026-11-11 确认）。
 * 合单开关关闭、分账计划需要分给卖家以外的二级商户时，提交前失败关闭：整单转人工（MANUAL / SUB_MERCHANT_SHARE_UNSUPPORTED），
 * 不向微信提交任何分账（平台佣金也不分），钱冻结在卖家二级商户。
 */
class SubMerchantShareFailClosedTest {
    static final String SP = CombinedPayoutTest.SP;
    static final String SELLER_SUB = CombinedPayoutTest.SELLER_SUB;
    static final String OTHER_SUB = "1900000202";
    static final PayoutSwitches ON = CombinedPayoutTest.ON;
    static final PayoutSwitches L2_ONLY = CombinedPayoutTest.L2_ONLY;
    static final String REASON = ProfitSharePlanner.SUB_TO_SUB_UNSUPPORTED;

    final FakeEcomPayoutMapper mapper = new FakeEcomPayoutMapper();
    final FakeEcomCombineMapper combineMapper = new FakeEcomCombineMapper();
    final FakeEcommerceGateway gateway = new FakeEcommerceGateway();

    SubMerchantShareFailClosedTest() {
        EcomOrderSeller t = new EcomOrderSeller();
        t.setOutTradeNo("REG1"); t.setSubMchid(SELLER_SUB); t.setSellerType("MERCHANT"); t.setSellerId(5L);
        t.setTotalFen(10000L); t.setTransactionId("4200000001"); t.setRegistrationId(77L);
        mapper.trades.add(t);
        mapper.sellerMembers.put("MERCHANT:5", 55L);
        mapper.memberSubMchids.put(66L, List.of(OTHER_SUB));
    }

    static EcomLedgerComponent comp(long id, String type, String kind, Long member, String yuan) {
        EcomLedgerComponent c = new EcomLedgerComponent();
        c.setSettlementId(id); c.setLineType(type); c.setReceiverKind(kind); c.setReceiverMemberId(member);
        c.setOriginalAmount(new BigDecimal(yuan));
        return c;
    }

    void thirdPartyLedger() {
        mapper.components.put(77L, List.of(comp(1, "PLATFORM", "ACCOUNTING", null, "15.00"),
                comp(2, "MERCHANT_VERIFY", "MEMBER", 66L, "10.00"), comp(3, "INITIATOR_MARGIN", "MEMBER", 55L, "75.00")));
    }

    void platformOnlyLedger() {
        mapper.components.put(77L, List.of(comp(1, "PLATFORM", "ACCOUNTING", null, "15.00"),
                comp(2, "INITIATOR_MARGIN", "MEMBER", 55L, "85.00")));
    }

    EcommerceProfitShareService service(PayoutSwitches switches) {
        EcommerceProfitShareService s = new EcommerceProfitShareService(mapper, gateway, switches, TransactionOperations.withoutTransaction());
        s.setCombineMapper(combineMapper);
        return s;
    }

    boolean anyShareCall() {
        return gateway.calls.stream().anyMatch(c -> c.startsWith("share:") || c.startsWith("finish:"));
    }

    @Test
    void thirdPartySubMerchantWithCombinedOffGoesManualAndNothingIsSubmitted() {
        thirdPartyLedger();
        EcommerceProfitShareService s = service(L2_ONLY);
        assertEquals(1, s.planTopic(1));
        EcomProfitShareOrder order = mapper.orders.get(0);
        assertEquals("MANUAL", order.getState());
        assertEquals(REASON, order.getManualReason());
        assertEquals(0L, order.getSharedFen());
        assertTrue(mapper.lines.stream().noneMatch(l -> "SUB_MERCHANT".equals(l.getReceiverKind())));

        assertEquals(0, s.submitPlanned(10));
        assertEquals(0, s.syncProcessing(10));
        assertFalse(anyShareCall(), "合单关时不得向微信提交任何分账：" + gateway.calls);
        assertTrue(gateway.calls.isEmpty());
        assertTrue(combineMapper.payables.isEmpty());
    }

    @Test
    void reRunningTheT14JobIsIdempotentAndNeverSubmits() {
        thirdPartyLedger();
        EcommerceProfitShareService s = service(L2_ONLY);
        s.planTopic(1);
        for (int i = 0; i < 3; i++) {
            assertEquals(0, s.planTopic(1));
            assertEquals(0, s.planMissing(10));
            assertEquals(0, s.submitPlanned(10));
            assertEquals(0, s.syncProcessing(10));
        }
        assertEquals(1, mapper.orders.size());
        assertEquals("MANUAL", mapper.orders.get(0).getState());
        assertEquals(REASON, mapper.orders.get(0).getManualReason());
        assertTrue(gateway.calls.isEmpty());
    }

    @Test
    void legacyPlannedRowWithSubMerchantReceiverIsFrozenBeforeSubmit() {
        // 本次改动前已落下的 PLANNED 计划（含二级商户接收方）：提交前转人工，不调微信；重跑仍不提交。
        EcomProfitShareOrder row = new EcomProfitShareOrder();
        row.setOutOrderNo(EcommerceProfitShareService.outOrderNo("REG1", 1)); row.setOutTradeNo("REG1"); row.setTopicId(1L);
        row.setSubMchid(SELLER_SUB); row.setTransactionId("4200000001"); row.setTotalFen(10000L); row.setSharedFen(2500L);
        row.setPlanHash("legacy"); row.setState("PLANNED");
        mapper.insertShareOrder(row);
        mapper.insertShareLine(line(row.getId(), 1, "PLATFORM", SP, null, 1500));
        mapper.insertShareLine(line(row.getId(), 2, "SUB_MERCHANT", OTHER_SUB, 66L, 1000));
        mapper.insertShareLine(line(row.getId(), 3, "RETAINED", SELLER_SUB, 55L, 7500));

        EcommerceProfitShareService s = service(L2_ONLY);
        assertEquals(0, s.submitPlanned(10));
        assertEquals("MANUAL", row.getState());
        assertEquals(REASON, row.getManualReason());
        assertEquals(0, s.submitPlanned(10));
        assertTrue(gateway.calls.isEmpty());
    }

    @Test
    void platformOnlyCommissionIsUnchanged() {
        platformOnlyLedger();
        EcommerceProfitShareService s = service(L2_ONLY);
        assertEquals(1, s.planTopic(1));
        assertEquals("PLANNED", mapper.orders.get(0).getState());
        assertEquals(1, s.submitPlanned(10));
        assertEquals(List.of(new ProfitSharePlanner.Receiver(SP, 1500)), gateway.lastReceivers);
        assertEquals("SUCCESS", mapper.orders.get(0).getState());
    }

    @Test
    void combinedOnStillRelaysThroughThePlatform() {
        thirdPartyLedger();
        EcommerceProfitShareService s = service(ON);
        assertEquals(1, s.planTopic(1));
        assertEquals(1, s.submitPlanned(10));
        assertEquals(List.of(new ProfitSharePlanner.Receiver(SP, 2500)), gateway.lastReceivers);
        assertEquals("SUCCESS", mapper.orders.get(0).getState());
        assertEquals(1, combineMapper.payables.size());
    }

    @Test
    void allSwitchesOffNeitherReadsNorWrites() {
        thirdPartyLedger();
        EcommerceProfitShareService s = service(PayoutSwitches.allOff());
        assertEquals(0, s.planTopic(1));
        assertEquals(0, s.submitPlanned(10));
        assertTrue(mapper.orders.isEmpty());
        assertTrue(gateway.calls.isEmpty());
    }

    @Test
    void replanStaysManualWhileCombinedOffAndRelaysOnceCombinedIsOn() {
        thirdPartyLedger();
        service(L2_ONLY).planTopic(1);
        long id = mapper.orders.get(0).getId();
        assertEquals("MANUAL:" + REASON, service(L2_ONLY).replan(id));
        assertTrue(gateway.calls.isEmpty());
        assertEquals("PLANNED", service(ON).replan(id));
        assertEquals(2500L, mapper.orders.get(0).getSharedFen());
    }

    @Test
    void refundOnTheFrozenOrderReturnsNothingAndRefundsFromTheSeller() {
        thirdPartyLedger();
        EcommerceProfitShareService s = service(L2_ONLY);
        s.planTopic(1);
        assertEquals(EcommerceProfitShareService.ReturnOutcome.ALL_RETURNED, s.returnForRefund("REG1", "RF1", 10000, 0));
        assertEquals("REFUNDED_BEFORE_SHARE", mapper.orders.get(0).getManualReason());
        assertTrue(gateway.calls.isEmpty());
    }

    static EcomProfitShareLine line(long orderId, long settlementId, String kind, String mchid, Long member, long fen) {
        EcomProfitShareLine l = new EcomProfitShareLine();
        l.setShareOrderId(orderId); l.setSettlementId(settlementId); l.setLineType("T" + settlementId);
        l.setReceiverKind(kind); l.setReceiverMchid(mchid); l.setReceiverMemberId(member); l.setAmountFen(fen);
        return l;
    }
}
