package com.chengyinhub.business.payout;

import com.chengyinhub.business.domain.EcomCombineOrder;
import com.chengyinhub.business.domain.EcomCombineSettlement;
import com.chengyinhub.business.domain.EcomLedgerComponent;
import com.chengyinhub.business.domain.EcomOrderSeller;
import com.chengyinhub.business.domain.EcomSupplierPayable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** l-payout3：合单拆分、合单结算、单商户单转付（纯函数 + 内存 mapper）。 */
class CombinedPayoutTest {
    static final String SP = "1900000001";
    static final String SELLER_SUB = "1900000101";
    static final String PLATFORM_SUB = "1900000888";
    static final PayoutSwitches ON = new PayoutSwitches(true, true, true, SP, "wx_mini", "", 30, false, "none", true, PLATFORM_SUB);
    /** l-payout2 全开、只有合单关。 */
    static final PayoutSwitches L2_ONLY = new PayoutSwitches(true, true, true, SP, "wx_mini", "", 30, false, "none", false, PLATFORM_SUB);

    static SellerOfRecordPolicy.Decision seller() {
        return new SellerOfRecordPolicy.Decision(true, SELLER_SUB, SellerOfRecordPolicy.SellerType.MERCHANT, 5L, "SUB_MERCHANT");
    }

    static CombinedCheckoutPolicy.Contract percent(String rate) { return new CombinedCheckoutPolicy.Contract(1, new BigDecimal(rate), null); }

    static CombinedCheckoutPolicy.Contract fixed(String fee) { return new CombinedCheckoutPolicy.Contract(2, null, new BigDecimal(fee)); }

    @Nested
    class Switches {
        @Test
        void combinedNeedsRoutingProfitSharingAndAValidDistinctPlatformSubMerchant() {
            assertTrue(ON.combinedEnabled());
            assertFalse(L2_ONLY.combinedEnabled());
            assertFalse(PayoutSwitches.allOff().combinedEnabled());
            assertFalse(new PayoutSwitches(true, true, false, SP, "wx_mini", "", 30, false, "none", true, PLATFORM_SUB).combinedEnabled());
            assertFalse(new PayoutSwitches(true, false, true, SP, "wx_mini", "", 30, false, "none", true, PLATFORM_SUB).combinedEnabled());
            assertFalse(new PayoutSwitches(true, true, true, SP, "wx_mini", "", 30, false, "none", true, "").combinedEnabled());
            assertFalse(new PayoutSwitches(true, true, true, SP, "wx_mini", "", 30, false, "none", true, SP).combinedEnabled());
            assertFalse(new PayoutSwitches(true, true, true, SP, "wx_mini", "", 30, false, "none", true, "abc").combinedEnabled());
            // 旧 9 参构造（l-payout / l-payout2 测试与装配）合单恒关。
            assertFalse(new PayoutSwitches(true, true, true, SP, "wx_mini", "", 30, false, "none").combinedEnabled());
        }
    }

    @Nested
    class Checkout {
        @Test
        void switchOffOrSingleSellerKeepsTheLPayout2Path() {
            assertEquals(CombinedCheckoutPolicy.Path.SINGLE,
                    CombinedCheckoutPolicy.decide(seller(), 10000, new BigDecimal("0.15"), List.of(percent("10")), 1L, L2_ONLY).path());
            assertEquals("SINGLE_PAYEE", CombinedCheckoutPolicy.decide(seller(), 10000, new BigDecimal("0.15"), List.of(), 1L, ON).reason());
            SellerOfRecordPolicy.Decision platform = new SellerOfRecordPolicy.Decision(false, null,
                    SellerOfRecordPolicy.SellerType.PLATFORM, null, "SELLER_NOT_ONBOARDED");
            assertEquals(CombinedCheckoutPolicy.Path.SINGLE,
                    CombinedCheckoutPolicy.decide(platform, 10000, new BigDecimal("0.15"), List.of(percent("10")), 1L, ON).path());
        }

        @Test
        void multiPayeeOrderSplitsIntoSellerAndPlatformHeldSubOrders() {
            // 实付 100 元：佣金 15%、带队 10%、核销固定 8 元 / 座 × 2 座 → 平台自营子单 15 + 10 + 16 = 41 元，卖家子单 59 元。
            CombinedCheckoutPolicy.Decision d = CombinedCheckoutPolicy.decide(seller(), 10000, new BigDecimal("0.15"),
                    List.of(percent("10"), fixed("8.00")), 2L, ON);
            assertEquals(CombinedCheckoutPolicy.Path.COMBINED, d.path());
            assertEquals(1500, d.commissionFen());
            assertEquals(2600, d.reserveFen());
            assertEquals(List.of(new CombinedCheckoutPolicy.SubOrder(CombinedCheckoutPolicy.Role.SELLER, SELLER_SUB, 5900, true),
                    new CombinedCheckoutPolicy.SubOrder(CombinedCheckoutPolicy.Role.PLATFORM_HELD, PLATFORM_SUB, 4100, false)), d.subOrders());
        }

        @Test
        void percentReserveRoundsUpAndEverythingIsCapped() {
            // 33.33 元 × 7.5% = 2.49975 元 → 预留向上取 250 分。
            CombinedCheckoutPolicy.Decision d = CombinedCheckoutPolicy.decide(seller(), 3333, BigDecimal.ZERO, List.of(percent("7.5")), 1L, ON);
            assertEquals(250, d.reserveFen());
            assertEquals(3083, d.subOrders().get(0).amountFen());
            // 预留 + 佣金 ≥ 实付：卖家部分为 0，整单走平台商户号（现有行为）。
            assertEquals("NOTHING_CERTAIN_FOR_SELLER", CombinedCheckoutPolicy.decide(seller(), 1000, new BigDecimal("0.5"),
                    List.of(percent("60")), 1L, ON).reason());
        }

        @Test
        void unknownTermsOrRateFallBackToPlatform() {
            assertEquals("PLATFORM_RATE_INVALID", CombinedCheckoutPolicy.decide(seller(), 1000, null, List.of(percent("1")), 1L, ON).reason());
            assertEquals("PLATFORM_RATE_INVALID", CombinedCheckoutPolicy.decide(seller(), 1000, new BigDecimal("1.2"), List.of(percent("1")), 1L, ON).reason());
            assertEquals("CONTRACT_TERMS_UNKNOWN", CombinedCheckoutPolicy.decide(seller(), 1000, BigDecimal.ZERO,
                    List.of(new CombinedCheckoutPolicy.Contract(null, null, null)), 1L, ON).reason());
            assertEquals("CONTRACT_TERMS_UNKNOWN", CombinedCheckoutPolicy.decide(seller(), 1000, BigDecimal.ZERO, List.of(fixed("1")), null, ON).reason());
            SellerOfRecordPolicy.Decision self = new SellerOfRecordPolicy.Decision(true, PLATFORM_SUB,
                    SellerOfRecordPolicy.SellerType.MERCHANT, 5L, "SUB_MERCHANT");
            assertEquals("SELLER_IS_PLATFORM_SELF", CombinedCheckoutPolicy.decide(self, 1000, BigDecimal.ZERO, List.of(percent("1")), 1L, ON).reason());
        }

        @Test
        void subOrderAndRefundNumbersAreDeterministicAndWithinWechatLimits() {
            String a = CombinedCheckoutPolicy.subOutTradeNo("R-1", CombinedCheckoutPolicy.Role.SELLER);
            assertEquals(a, CombinedCheckoutPolicy.subOutTradeNo("R-1", CombinedCheckoutPolicy.Role.SELLER));
            assertFalse(a.equals(CombinedCheckoutPolicy.subOutTradeNo("R-1", CombinedCheckoutPolicy.Role.PLATFORM_HELD)));
            assertEquals(32, a.length());
            assertTrue(a.matches("[A-Za-z0-9]{32}"));
            assertEquals(32, CombinedCheckoutPolicy.subOutRefundNo("RF-1", CombinedCheckoutPolicy.Role.SELLER).length());
        }

        @Test
        void refundSplitsProportionallyAndNeverExceedsWhatIsLeftOnASubOrder() {
            assertArrayEquals(new long[] { 2950, 2050 }, CombinedCheckoutPolicy.splitRefund(new long[] { 5900, 4100 }, new long[] { 0, 0 }, 5000));
            assertArrayEquals(new long[] { 5900, 4100 }, CombinedCheckoutPolicy.splitRefund(new long[] { 5900, 4100 }, new long[] { 0, 0 }, 10000));
            // 1 分钱的退款落在余数最大的子单上，总和精确。
            long[] one = CombinedCheckoutPolicy.splitRefund(new long[] { 5900, 4100 }, new long[] { 0, 0 }, 1);
            assertEquals(1, Arrays.stream(one).sum());
            // 卖家子单已退满：剩余退款只能落在平台自营子单。
            assertArrayEquals(new long[] { 0, 100 }, CombinedCheckoutPolicy.splitRefund(new long[] { 5900, 4100 }, new long[] { 5900, 0 }, 100));
            assertNull(CombinedCheckoutPolicy.splitRefund(new long[] { 5900, 4100 }, new long[] { 5900, 4000 }, 101));
        }
    }

    static ProfitSharePlanner.Component comp(long id, String type, String kind, Long member, String yuan) {
        return new ProfitSharePlanner.Component(id, type, kind, member, new BigDecimal(yuan));
    }

    @Nested
    class Settle {
        final CombinedSettlementPlanner.Order order = new CombinedSettlementPlanner.Order("REG1", "TX-S", 5900, 4100, 55L);

        @Test
        void unusedReserveIsPaidBackToSellerAsSupplierPayable() {
            // 账本：平台 15，带队俱乐部 10，核销商家 8，发起方 67。平台自营子单 41 ≥ 15 + 10 + 8 = 33，多出 8 元付回卖家。
            CombinedSettlementPlanner.Plan p = CombinedSettlementPlanner.plan(order, List.of(comp(1, "PLATFORM", "ACCOUNTING", null, "15.00"),
                    comp(2, "LEAD_FEE", "MEMBER", 66L, "10.00"), comp(3, "MERCHANT_VERIFY", "MEMBER", 77L, "8.00"),
                    comp(4, "INITIATOR_GUARANTEE", "MEMBER", 55L, "7.00"), comp(5, "INITIATOR_MARGIN", "MEMBER", 55L, "60.00")), 30);
            assertTrue(p.ready());
            assertEquals(0, p.shortfallFen());
            assertEquals(800, p.surplusFen());
            assertEquals(List.of(new CombinedSettlementPlanner.Payable(2, "LEAD_FEE", 66L, 1000),
                    new CombinedSettlementPlanner.Payable(3, "MERCHANT_VERIFY", 77L, 800),
                    new CombinedSettlementPlanner.Payable(4, "SURPLUS", 55L, 800)), p.payables());
        }

        @Test
        void contractAddedAfterCheckoutIsCoveredFromSellerSubOrderWithinCap() {
            // 平台 15 + 核销 30 = 45 > 41：差 4 元从卖家子单分给平台（4 / 59 < 30%）。
            CombinedSettlementPlanner.Plan p = CombinedSettlementPlanner.plan(order, List.of(comp(1, "PLATFORM", "ACCOUNTING", null, "15.00"),
                    comp(3, "MERCHANT_VERIFY", "MEMBER", 77L, "30.00"), comp(5, "INITIATOR_MARGIN", "MEMBER", 55L, "55.00")), 30);
            assertTrue(p.ready());
            assertEquals(400, p.shortfallFen());
            assertEquals(List.of(new CombinedSettlementPlanner.Payable(3, "MERCHANT_VERIFY", 77L, 3000)), p.payables());
            CombinedSettlementPlanner.Plan over = CombinedSettlementPlanner.plan(order, List.of(comp(1, "PLATFORM", "ACCOUNTING", null, "15.00"),
                    comp(3, "MERCHANT_VERIFY", "MEMBER", 77L, "50.00"), comp(5, "INITIATOR_MARGIN", "MEMBER", 55L, "35.00")), 30);
            assertEquals("SHARE_RATIO_EXCEEDED", over.manualReason());
        }

        @Test
        void anythingInconsistentGoesManual() {
            assertEquals("LEDGER_CONSERVATION", CombinedSettlementPlanner.plan(order, List.of(comp(1, "PLATFORM", "ACCOUNTING", null, "15.00")), 30).manualReason());
            assertEquals("SELLER_MEMBER_UNKNOWN", CombinedSettlementPlanner.plan(new CombinedSettlementPlanner.Order("REG1", "TX", 5900, 4100, null),
                    List.of(comp(1, "PLATFORM", "ACCOUNTING", null, "100.00")), 30).manualReason());
            assertEquals("NO_LEDGER_COMPONENTS", CombinedSettlementPlanner.plan(order, List.of(), 30).manualReason());
            assertEquals("LEDGER_RECEIVER_UNKNOWN", CombinedSettlementPlanner.plan(order, List.of(comp(1, "X", "OTHER", null, "100.00")), 30).manualReason());
            assertEquals("LEDGER_AMOUNT_INVALID", CombinedSettlementPlanner.plan(order, List.of(comp(1, "PLATFORM", "ACCOUNTING", null, "0.001")), 30).manualReason());
        }
    }

    EcomLedgerComponent ledger(long id, String type, String kind, Long member, String yuan) {
        EcomLedgerComponent c = new EcomLedgerComponent();
        c.setSettlementId(id); c.setLineType(type); c.setReceiverKind(kind); c.setReceiverMemberId(member);
        c.setOriginalAmount(new BigDecimal(yuan));
        return c;
    }

    @Nested
    class SettlementService {
        final FakeEcomCombineMapper mapper = new FakeEcomCombineMapper();
        final FakeEcomPayoutMapper payoutMapper = new FakeEcomPayoutMapper();
        final FakeEcommerceGateway gateway = new FakeEcommerceGateway();
        final EcommerceCombinedSettlementService service = new EcommerceCombinedSettlementService(mapper, payoutMapper, gateway, ON,
                TransactionOperations.withoutTransaction());

        SettlementService() {
            EcomCombineOrder o = new EcomCombineOrder();
            o.setOutTradeNo("REG1"); o.setSellerSubMchid(SELLER_SUB); o.setSellerType("MERCHANT"); o.setSellerId(5L);
            o.setPlatformSubMchid(PLATFORM_SUB); o.setTotalFen(10000L); o.setSellerFen(5900L); o.setHeldFen(4100L);
            o.setCommissionFen(1500L); o.setReserveFen(2600L); o.setCombineOutTradeNo("REG1"); o.setSellerTransactionId("TX-S");
            mapper.orders.put("REG1", o);
            mapper.topicOrders.put(1L, List.of("REG1"));
            mapper.registrationIds.put("REG1", 77L);
            payoutMapper.sellerMembers.put("MERCHANT:5", 55L);
        }

        void ledger(String verifyYuan, String marginYuan) {
            payoutMapper.components.put(77L, List.of(CombinedPayoutTest.this.ledger(1, "PLATFORM", "ACCOUNTING", null, "15.00"),
                    CombinedPayoutTest.this.ledger(3, "MERCHANT_VERIFY", "MEMBER", 66L, verifyYuan),
                    CombinedPayoutTest.this.ledger(5, "INITIATOR_MARGIN", "MEMBER", 55L, marginYuan)));
        }

        @Test
        void switchOffReadsAndCallsNothing() {
            EcommerceCombinedSettlementService off = new EcommerceCombinedSettlementService(mapper, payoutMapper, gateway, L2_ONLY,
                    TransactionOperations.withoutTransaction());
            assertFalse(off.active());
            assertEquals(0, off.planTopic(1));
            assertEquals(0, off.submitDue(10));
            assertEquals(0, off.syncProcessing(10));
            assertEquals(0, off.planMissing(10));
            assertTrue(off.payables(null, 10).isEmpty());
            assertFalse(off.markPaid(1, "BANK-1", "ops"));
            assertTrue(mapper.settlements.isEmpty());
            assertTrue(gateway.calls.isEmpty());
        }

        @Test
        void planIsIdempotentAndNoShortfallFinishesSellerSubOrder() {
            ledger("10.00", "75.00");
            assertEquals(1, service.planTopic(1));
            assertEquals(0, service.planTopic(1));
            EcomCombineSettlement s = mapper.settlements.get(0);
            assertEquals("PLANNED", s.getState());
            assertEquals(0L, s.getShortfallFen());
            assertEquals(1600L, s.getSurplusFen());
            assertEquals(List.of("MERCHANT_VERIFY:66:1000", "SURPLUS:55:1600"),
                    mapper.payables.stream().map(p -> p.getLineType() + ":" + p.getPayeeMemberId() + ":" + p.getAmountFen()).toList());
            assertEquals(1, service.submitDue(10));
            assertEquals(List.of("finish:" + s.getOutOrderNo()), gateway.calls);
            assertEquals("SUCCESS", s.getState());
            assertEquals(0, service.submitDue(10));
        }

        @Test
        void shortfallIsSharedToPlatformAndReturnedBeforeASellerRefund() {
            ledger("30.00", "55.00");
            service.planTopic(1);
            EcomCombineSettlement s = mapper.settlements.get(0);
            assertEquals(400L, s.getShortfallFen());
            service.submitDue(10);
            assertEquals("share:" + s.getOutOrderNo() + ":true", gateway.calls.get(0));
            assertEquals(List.of(new ProfitSharePlanner.Receiver(SP, 400)), gateway.lastReceivers);
            assertEquals("SUCCESS", s.getState());
            // 卖家子单全额退款：先回退 4 元补差；同一业务退款单号重复调用不再回退。
            EcomCombineOrder row = mapper.selectCombineOrder("REG1");
            assertEquals(EcommerceProfitShareService.ReturnOutcome.ALL_RETURNED, service.returnForRefund(row, "RF1", 5900, 0));
            assertEquals(EcommerceProfitShareService.ReturnOutcome.ALL_RETURNED, service.returnForRefund(row, "RF1", 5900, 0));
            assertEquals(1, gateway.calls.stream().filter(c -> c.startsWith("return:")).count());
            assertEquals("return:" + SP + ":400", gateway.calls.get(gateway.calls.size() - 1));
        }

        @Test
        void refundBeforeSubmissionFreezesPlanAndRefundBeforePlanningGoesManual() {
            ledger("10.00", "75.00");
            service.planTopic(1);
            EcomCombineOrder row = mapper.selectCombineOrder("REG1");
            assertEquals(EcommerceProfitShareService.ReturnOutcome.ALL_RETURNED, service.returnForRefund(row, "RF1", 100, 0));
            assertEquals("MANUAL", mapper.settlements.get(0).getState());
            assertEquals("REFUNDED_BEFORE_SHARE", mapper.settlements.get(0).getManualReason());
            assertEquals(0, service.submitDue(10));
            assertTrue(gateway.calls.isEmpty());
        }

        @Test
        void ledgerMismatchGoesManualWithoutPayables() {
            ledger("10.00", "70.00");
            service.planTopic(1);
            assertEquals("MANUAL", mapper.settlements.get(0).getState());
            assertEquals("LEDGER_CONSERVATION", mapper.settlements.get(0).getManualReason());
            assertTrue(mapper.payables.isEmpty());
        }

        @Test
        void payableCanBeMarkedPaidOnceWithAReference() {
            ledger("10.00", "75.00");
            service.planTopic(1);
            EcomSupplierPayable p = mapper.payables.get(0);
            assertFalse(service.markPaid(p.getId(), " ", "ops"));
            assertTrue(service.markPaid(p.getId(), "BANK-20261111-1", "ops"));
            assertFalse(service.markPaid(p.getId(), "BANK-20261111-1", "ops"));
            assertEquals("PAID", p.getState());
        }
    }

    @Nested
    class Relay {
        final FakeEcomPayoutMapper mapper = new FakeEcomPayoutMapper();
        final FakeEcomCombineMapper combineMapper = new FakeEcomCombineMapper();
        final FakeEcommerceGateway gateway = new FakeEcommerceGateway();

        Relay() {
            EcomOrderSeller t = new EcomOrderSeller();
            t.setOutTradeNo("REG1"); t.setSubMchid(SELLER_SUB); t.setSellerType("MERCHANT"); t.setSellerId(5L);
            t.setTotalFen(10000L); t.setTransactionId("4200000001"); t.setRegistrationId(77L);
            mapper.trades.add(t);
            mapper.sellerMembers.put("MERCHANT:5", 55L);
            mapper.memberSubMchids.put(66L, List.of("1900000202"));
            mapper.components.put(77L, List.of(ledger(1, "PLATFORM", "ACCOUNTING", null, "15.00"),
                    ledger(2, "MERCHANT_VERIFY", "MEMBER", 66L, "10.00"), ledger(3, "INITIATOR_MARGIN", "MEMBER", 55L, "75.00")));
        }

        EcommerceProfitShareService service(PayoutSwitches switches) {
            EcommerceProfitShareService s = new EcommerceProfitShareService(mapper, gateway, switches, TransactionOperations.withoutTransaction());
            s.setCombineMapper(combineMapper);
            return s;
        }

        @Test
        void combinedOffKeepsLPayout2DirectSplitToTheOtherSubMerchant() {
            EcommerceProfitShareService s = service(L2_ONLY);
            s.planTopic(1);
            s.submitPlanned(10);
            assertEquals(List.of(new ProfitSharePlanner.Receiver(SP, 1500), new ProfitSharePlanner.Receiver("1900000202", 1000)),
                    gateway.lastReceivers);
            assertTrue(combineMapper.payables.isEmpty());
        }

        @Test
        void combinedOnRelaysOtherMerchantShareThroughPlatformAndRecordsPayableAfterSuccess() {
            EcommerceProfitShareService s = service(ON);
            s.planTopic(1);
            assertTrue(combineMapper.payables.isEmpty()); // 分账成功前不落应付
            s.submitPlanned(10);
            assertEquals(List.of(new ProfitSharePlanner.Receiver(SP, 2500)), gateway.lastReceivers);
            assertEquals(1, combineMapper.payables.size());
            EcomSupplierPayable p = combineMapper.payables.get(0);
            assertEquals("RELAY", p.getSource());
            assertEquals(66L, p.getPayeeMemberId());
            assertEquals(1000L, p.getAmountFen());
            assertEquals("MERCHANT_VERIFY", p.getLineType());
        }

        @Test
        void relayDoesNotNeedTheReceiverToBeOnboarded() {
            mapper.memberSubMchids.clear();
            ProfitSharePlanner.Plan off = ProfitSharePlanner.plan(new ProfitSharePlanner.Order("REG1", "TX", SELLER_SUB, 10000, java.util.Set.of(55L)),
                    List.of(comp(1, "PLATFORM", "ACCOUNTING", null, "15.00"), comp(2, "MERCHANT_VERIFY", "MEMBER", 66L, "10.00"),
                            comp(3, "INITIATOR_MARGIN", "MEMBER", 55L, "75.00")), SP, 30, m -> java.util.Optional.empty());
            assertEquals("RECEIVER_NOT_ONBOARDED", off.manualReason());
            ProfitSharePlanner.Plan relay = ProfitSharePlanner.plan(new ProfitSharePlanner.Order("REG1", "TX", SELLER_SUB, 10000, java.util.Set.of(55L)),
                    List.of(comp(1, "PLATFORM", "ACCOUNTING", null, "15.00"), comp(2, "MERCHANT_VERIFY", "MEMBER", 66L, "10.00"),
                            comp(3, "INITIATOR_MARGIN", "MEMBER", 55L, "75.00")), SP, 30, m -> java.util.Optional.empty(), true);
            assertTrue(relay.ready());
            assertEquals(2500, relay.sharedFen());
            assertEquals(List.of(new ProfitSharePlanner.Receiver(SP, 2500)), relay.receivers());
        }
    }
}
