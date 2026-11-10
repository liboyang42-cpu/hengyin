package com.chengyinhub.business.payout;

import com.chengyinhub.business.domain.EcomLedgerComponent;
import com.chengyinhub.business.domain.EcomOrderSeller;
import com.chengyinhub.business.domain.EcomProfitShareOrder;
import com.chengyinhub.business.domain.EcomSubMerchant;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EcommercePayoutServicesTest {
    static final PayoutSwitches ON = new PayoutSwitches(true, true, true, "1900000001", "wxsp", "", 30, false, "none");

    @Nested
    class SubMerchant {
        final FakeEcomPayoutMapper mapper = new FakeEcomPayoutMapper();
        final FakeEcommerceGateway gateway = new FakeEcommerceGateway();
        final EcommerceSubMerchantService service = new EcommerceSubMerchantService(mapper, gateway, ON);

        SubMerchant() { mapper.sellerMembers.put("MERCHANT:5", 55L); }

        @Test
        void switchOffRefusesAllWritesAndShowsOnlyUnavailableReason() {
            EcommerceSubMerchantService off = new EcommerceSubMerchantService(mapper, gateway, PayoutSwitches.allOff());
            assertThrows(ServiceException.class, () -> off.register("MERCHANT", 5L, "admin"));
            assertThrows(ServiceException.class, () -> off.refresh(1L));
            assertEquals(Map.of("available", false, "unavailableReason", "PLATFORM_NOT_OPEN"), off.ownerView("MERCHANT", 5L));
            assertEquals(0, off.refreshInProgress(10));
            assertTrue(gateway.calls.isEmpty());
        }

        @Test
        void registerIsIdempotentAndRejectsUnknownOwner() {
            EcomSubMerchant a = service.register("MERCHANT", 5L, "admin");
            EcomSubMerchant b = service.register("MERCHANT", 5L, "admin");
            assertEquals(a.getId(), b.getId());
            assertEquals("CYAPM5", a.getOutRequestNo());
            assertEquals("DRAFT", a.getApplymentState());
            assertThrows(ServiceException.class, () -> service.register("CLUB", 9L, "admin"));
            assertThrows(ServiceException.class, () -> service.register("PERSON", 5L, "admin"));
        }

        @Test
        void submitOnlyFromDraftThenRefreshAdvancesToFinish() {
            EcomSubMerchant row = service.register("MERCHANT", 5L, "admin");
            service.submit(row.getId(), Map.of("organization_type", "2401"));
            assertEquals("CHECKING", mapper.selectSubMerchantById(row.getId()).getApplymentState());
            assertThrows(ServiceException.class, () -> service.submit(row.getId(), Map.of()));

            gateway.applyment = new EcommercePayGateway.ApplymentStatus(2000001L, "NEED_SIGN", "待签约", "https://pay.weixin.qq.com/sign", null);
            service.refresh(row.getId());
            Map<String, Object> view = service.ownerView("MERCHANT", 5L);
            assertEquals("NEED_SIGN", view.get("state"));
            assertEquals("https://pay.weixin.qq.com/sign", view.get("signUrl"));

            // FINISH 没带 sub_mchid：不前进
            gateway.applyment = new EcommercePayGateway.ApplymentStatus(2000001L, "FINISH", "完成", null, null);
            service.refresh(row.getId());
            assertEquals("NEED_SIGN", mapper.selectSubMerchantById(row.getId()).getApplymentState());

            gateway.applyment = new EcommercePayGateway.ApplymentStatus(2000001L, "FINISH", "完成", null, "1900000101");
            service.refresh(row.getId());
            view = service.ownerView("MERCHANT", 5L);
            assertEquals("FINISH", view.get("state"));
            assertEquals("19****0101", view.get("subMchidMasked"));
            assertNull(view.get("signUrl"));
            assertEquals(true, view.get("sellerRouting"));

            // 终态不回退
            gateway.applyment = new EcommercePayGateway.ApplymentStatus(2000001L, "AUDITING", "", null, null);
            service.refresh(row.getId());
            assertEquals("FINISH", mapper.selectSubMerchantById(row.getId()).getApplymentState());
        }

        @Test
        void unknownWechatStateIsIgnored() {
            EcomSubMerchant row = service.register("MERCHANT", 5L, "admin");
            service.submit(row.getId(), Map.of());
            gateway.applyment = new EcommercePayGateway.ApplymentStatus(1L, "SOMETHING_NEW", "", null, null);
            service.refresh(row.getId());
            assertEquals("CHECKING", mapper.selectSubMerchantById(row.getId()).getApplymentState());
        }

        @Test
        void ownerViewBeforeRegistrationSaysNone() {
            assertEquals("NONE", service.ownerView("MERCHANT", 5L).get("state"));
        }
    }

    @Nested
    class ProfitShare {
        final FakeEcomPayoutMapper mapper = new FakeEcomPayoutMapper();
        final FakeEcommerceGateway gateway = new FakeEcommerceGateway();
        final EcommerceProfitShareService service = new EcommerceProfitShareService(mapper, gateway, ON, TransactionOperations.withoutTransaction());

        ProfitShare() {
            EcomOrderSeller t = new EcomOrderSeller();
            t.setOutTradeNo("REG1"); t.setSubMchid("1900000101"); t.setSellerType("MERCHANT"); t.setSellerId(5L);
            t.setTotalFen(10000L); t.setTransactionId("4200000001"); t.setRegistrationId(77L);
            mapper.trades.add(t);
            mapper.sellerMembers.put("MERCHANT:5", 55L);
            mapper.memberSubMchids.put(66L, List.of("1900000202"));
            mapper.components.put(77L, List.of(comp(1, "PLATFORM", "ACCOUNTING", null, "15.00"),
                    comp(2, "MERCHANT_VERIFY", "MEMBER", 66L, "10.00"), comp(3, "INITIATOR_MARGIN", "MEMBER", 55L, "75.00")));
        }

        EcomLedgerComponent comp(long id, String type, String kind, Long member, String yuan) {
            EcomLedgerComponent c = new EcomLedgerComponent();
            c.setSettlementId(id); c.setLineType(type); c.setReceiverKind(kind); c.setReceiverMemberId(member);
            c.setOriginalAmount(new BigDecimal(yuan));
            return c;
        }

        @Test
        void switchOffDoesNothing() {
            EcommerceProfitShareService off = new EcommerceProfitShareService(mapper, gateway, PayoutSwitches.allOff(), TransactionOperations.withoutTransaction());
            assertEquals(0, off.planTopic(1));
            assertEquals(0, off.submitPlanned(10));
            assertEquals(0, off.syncProcessing(10));
            assertEquals(EcommerceProfitShareService.ReturnOutcome.SWITCH_OFF, off.returnForRefund("REG1", "RF1", 100, 0));
            assertTrue(mapper.orders.isEmpty());
            assertTrue(gateway.calls.isEmpty());
        }

        @Test
        void planIsIdempotentAndSubmitSplitsFromLedger() {
            assertEquals(1, service.planTopic(1));
            assertEquals(0, service.planTopic(1));
            EcomProfitShareOrder order = mapper.orders.get(0);
            assertEquals("PLANNED", order.getState());
            assertEquals(2500L, order.getSharedFen());
            assertEquals(3, mapper.lines.size());

            assertEquals(1, service.submitPlanned(10));
            assertEquals("SUCCESS", order.getState());
            assertEquals(List.of(new ProfitSharePlanner.Receiver("1900000001", 1500), new ProfitSharePlanner.Receiver("1900000202", 1000)),
                    gateway.lastReceivers);
            assertEquals(0, service.submitPlanned(10)); // 已成功不再提交
        }

        @Test
        void uncertainSubmitStaysProcessingThenSyncSettles() {
            service.planTopic(1);
            gateway.failNext = new RuntimeException("timeout");
            service.submitPlanned(10);
            EcomProfitShareOrder order = mapper.orders.get(0);
            assertEquals("PROCESSING", order.getState());
            gateway.query = new EcommercePayGateway.ShareResult("PROCESSING", "W1", List.of());
            service.syncProcessing(10);
            assertEquals("PROCESSING", order.getState());
            gateway.query = new EcommercePayGateway.ShareResult("FINISHED", "W1",
                    List.of(new EcommercePayGateway.ReceiverResult("1900000001", 1500, "SUCCESS", null)));
            service.syncProcessing(10);
            assertEquals("SUCCESS", order.getState());
        }

        @Test
        void closedReceiverGoesManual() {
            service.planTopic(1);
            gateway.share = new EcommercePayGateway.ShareResult("FINISHED", "W1",
                    List.of(new EcommercePayGateway.ReceiverResult("1900000202", 1000, "CLOSED", "RECEIVER_INVALID")));
            service.submitPlanned(10);
            assertEquals("MANUAL", mapper.orders.get(0).getState());
        }

        @Test
        void nothingToShareFinishesInsteadOfSplitting() {
            mapper.components.put(77L, List.of(comp(3, "INITIATOR_MARGIN", "MEMBER", 55L, "100.00")));
            service.planTopic(1);
            service.submitPlanned(10);
            assertTrue(gateway.calls.stream().anyMatch(c -> c.startsWith("finish:")));
            assertFalse(gateway.calls.stream().anyMatch(c -> c.startsWith("share:")));
            assertEquals("SUCCESS", mapper.orders.get(0).getState());
        }

        @Test
        void unroutableReceiverIsPersistedAsManualAndNeverSubmitted() {
            mapper.memberSubMchids.clear();
            service.planTopic(1);
            assertEquals("MANUAL", mapper.orders.get(0).getState());
            assertEquals("RECEIVER_NOT_ONBOARDED", mapper.orders.get(0).getManualReason());
            service.submitPlanned(10);
            assertTrue(gateway.calls.isEmpty());
        }

        @Test
        void ambiguousMemberWithTwoSubMerchantsGoesManual() {
            mapper.memberSubMchids.put(66L, List.of("1900000202", "1900000303"));
            service.planTopic(1);
            assertEquals("MANUAL", mapper.orders.get(0).getState());
        }

        @Test
        void refundReturnsSplitsFirstAndIsIdempotent() {
            service.planTopic(1);
            service.submitPlanned(10);
            assertEquals(EcommerceProfitShareService.ReturnOutcome.ALL_RETURNED, service.returnForRefund("REG1", "RF1", 10000, 0));
            assertEquals(2, mapper.returns.size());
            long returned = mapper.returns.stream().mapToLong(r -> r.getAmountFen()).sum();
            assertEquals(2500, returned);
            int callsBefore = gateway.calls.size();
            assertEquals(EcommerceProfitShareService.ReturnOutcome.ALL_RETURNED, service.returnForRefund("REG1", "RF1", 10000, 0));
            assertEquals(2, mapper.returns.size());
            assertEquals(callsBefore, gateway.calls.size());
        }

        @Test
        void failedReturnBlocksRefund() {
            service.planTopic(1);
            service.submitPlanned(10);
            gateway.returnResult = "FAILED";
            assertEquals(EcommerceProfitShareService.ReturnOutcome.FAILED, service.returnForRefund("REG1", "RF2", 5000, 0));
            gateway.returnResult = "PROCESSING";
            assertEquals(EcommerceProfitShareService.ReturnOutcome.PENDING, service.returnForRefund("REG1", "RF3", 1000, 5000));
        }

        @Test
        void refundBeforeShareFreezesPlan() {
            service.planTopic(1);
            assertEquals(EcommerceProfitShareService.ReturnOutcome.ALL_RETURNED, service.returnForRefund("REG1", "RF1", 10000, 0));
            assertEquals("MANUAL", mapper.orders.get(0).getState());
            assertEquals("REFUNDED_BEFORE_SHARE", mapper.orders.get(0).getManualReason());
            service.submitPlanned(10);
            assertFalse(gateway.calls.stream().anyMatch(c -> c.startsWith("share:")));
        }

        @Test
        void tradeWithoutShareIsNotApplicableAndSwitchOffWithSharesBlocksRefund() {
            assertEquals(EcommerceProfitShareService.ReturnOutcome.NOT_APPLICABLE, service.returnForRefund("REGX", "RF1", 100, 0));
            service.planTopic(1);
            EcommerceProfitShareService off = new EcommerceProfitShareService(mapper, gateway, PayoutSwitches.allOff(), TransactionOperations.withoutTransaction());
            assertEquals(EcommerceProfitShareService.ReturnOutcome.PENDING, off.returnForRefund("REG1", "RF1", 100, 0));
        }

        @Test
        void outNumbersAreStableAndWithinWechatLength() {
            assertEquals(EcommerceProfitShareService.outOrderNo("REG1", 1), EcommerceProfitShareService.outOrderNo("REG1", 1));
            assertTrue(EcommerceProfitShareService.outOrderNo("REG1", 1).length() <= 64);
            assertTrue(EcommerceProfitShareService.outReturnNo(1, "RF1", "1900000001").matches("[A-Za-z0-9]{1,64}"));
        }
    }

    @Nested
    class Creator {
        final FakeEcomPayoutMapper mapper = new FakeEcomPayoutMapper();

        @Test
        void disabledByDefault() {
            CreatorPayoutService service = new CreatorPayoutService(mapper, PayoutSwitches.allOff(), List.of(new DisabledCreatorPayoutProvider()));
            assertThrows(ServiceException.class, () -> service.request(1, "CREATOR_REVENUE", 2, 100));
            assertTrue(mapper.payouts.isEmpty());
        }

        @Test
        void selectedProviderIsCalledOnceAndRequestIsIdempotent() {
            int[] calls = {0};
            CreatorPayoutProvider acme = new CreatorPayoutProvider() {
                @Override public String code() { return "acme"; }
                @Override public Result submit(Request r) { calls[0]++; return new Result("SUBMITTED", "P-" + r.outPayoutNo(), null); }
                @Override public Result query(String no) { return new Result("SUCCEEDED", null, null); }
            };
            PayoutSwitches on = new PayoutSwitches(false, false, false, "", "", "", 30, true, "acme");
            CreatorPayoutService service = new CreatorPayoutService(mapper, on, List.of(new DisabledCreatorPayoutProvider(), acme));
            assertEquals("SUBMITTED", service.request(1, "CREATOR_REVENUE", 2, 100).getState());
            assertEquals("SUBMITTED", service.request(1, "CREATOR_REVENUE", 2, 100).getState());
            assertEquals(1, calls[0]);
            assertThrows(ServiceException.class, () -> service.request(1, "CREATOR_REVENUE", 2, 200));
            assertThrows(ServiceException.class, () -> service.request(1, "bad type", 2, 100));
        }

        @Test
        void unknownProviderCodeIsRefused() {
            PayoutSwitches on = new PayoutSwitches(false, false, false, "", "", "", 30, true, "ghost");
            CreatorPayoutService service = new CreatorPayoutService(mapper, on, List.of(new DisabledCreatorPayoutProvider()));
            assertThrows(ServiceException.class, () -> service.request(1, "CREATOR_REVENUE", 2, 100));
        }
    }
}
