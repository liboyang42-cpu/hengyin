package com.chengyinhub.business.payout;

import com.chengyinhub.business.domain.EcomLedgerComponent;
import com.chengyinhub.business.domain.EcomOrderSeller;
import com.chengyinhub.business.domain.EcomPersonalReceiver;
import com.chengyinhub.business.domain.EcomProfitShareLine;
import com.chengyinhub.business.domain.EcomProfitShareOrder;
import com.chengyinhub.business.domain.EcomProfitShareReturn;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EcommercePersonalProfitShareServiceTest {
    private static final String ACCOUNT = "1900000001";
    private static final String APPID = "wxsp";

    static class Bindings extends FakeEcomPayoutMapper {
        EcomPersonalReceiver binding;
        final List<Long> reads = new ArrayList<>();
        @Override public EcomPersonalReceiver selectByMemberAndAppid(Long memberId, String appid) {
            reads.add(memberId);
            assertEquals(APPID, appid);
            return binding;
        }
        @Override public EcomPersonalReceiver selectByMemberAndAppidForUpdate(Long memberId, String appid) {
            throw new AssertionError("Planning must not lock or mutate binding state");
        }
        @Override public int insertVerified(EcomPersonalReceiver receiver) {
            throw new AssertionError("Planning must not create bindings");
        }
        void verified(long memberId, String appid) {
            binding = new EcomPersonalReceiver();
            binding.setMemberId(memberId);
            binding.setAppid(appid);
            binding.setOpenid(ACCOUNT);
            binding.setState("VERIFIED");
        }
    }

    static class Gateway extends FakeEcommerceGateway {
        boolean failFinish;
        ProfitSharePlanner.Receiver queriedReturnReceiver;
        @Override public ReturnResult queryReturn(String sub, String no, String returnNo, ProfitSharePlanner.Receiver receiver) {
            queriedReturnReceiver = receiver;
            return super.queryReturn(sub, no, returnNo, receiver);
        }
        @Override public void finishProfitSharing(String sub, String tx, String no, String description) {
            super.finishProfitSharing(sub, tx, no, description);
            if (failFinish) throw new IllegalStateException("uncertain finish");
        }
    }

    static class Fixture {
        final FakeEcomPayoutMapper mapper = new FakeEcomPayoutMapper();
        final Gateway gateway = new Gateway();
        final Bindings bindings = new Bindings();
        final EcommerceProfitShareService service = service(switches(true));
        Fixture() {
            EcomOrderSeller trade = new EcomOrderSeller();
            trade.setOutTradeNo("REG1"); trade.setTransactionId("TX1"); trade.setRegistrationId(77L);
            trade.setSubMchid("seller"); trade.setSellerType("MERCHANT"); trade.setSellerId(5L); trade.setTotalFen(10000L);
            mapper.trades.add(trade);
            mapper.sellerMembers.put("MERCHANT:5", 55L);
            mapper.components.put(77L, List.of(component(1, "ACCOUNTING", null, "10.00", null),
                    component(2, "MEMBER", 66L, "20.00", 7L), component(3, "MEMBER", 55L, "70.00", null)));
            bindings.verified(66L, APPID);
        }
        EcommerceProfitShareService service(PayoutSwitches switches) {
            EcommerceProfitShareService result = new EcommerceProfitShareService(mapper, gateway, switches,
                    TransactionOperations.withoutTransaction());
            result.setPersonalReceiverMapper(bindings);
            return result;
        }
        EcomProfitShareOrder plan() {
            assertEquals(1, service.planTopic(9));
            return mapper.orders.get(0);
        }
        EcomProfitShareOrder submit() {
            EcomProfitShareOrder order = plan();
            assertEquals(1, service.submitPlanned(10));
            assertEquals("SUCCESS", order.getState());
            return order;
        }
    }

    private static PayoutSwitches switches(boolean personal) {
        return new PayoutSwitches(true, true, true, ACCOUNT, APPID, "", 30, false, "none")
                .withPersonalClubEnabled(personal);
    }
    private static EcomLedgerComponent component(long id, String kind, Long member, String amount, Long clubId) {
        EcomLedgerComponent c = new EcomLedgerComponent();
        c.setSettlementId(id); c.setLineType(clubId == null ? "PLATFORM" : "CLUB_SESSION");
        c.setReceiverKind(kind); c.setReceiverMemberId(member); c.setOriginalAmount(new BigDecimal(amount));
        c.setClubId(clubId); c.setClubName(clubId == null ? null : "Frozen club");
        return c;
    }

    @Test
    void merchantAndPersonalSameAccountNeverMergeAndProvenanceIsPersisted() {
        Fixture f = new Fixture();
        EcomProfitShareOrder order = f.submit();
        assertEquals(List.of(new ProfitSharePlanner.Receiver(ACCOUNT, 1000),
                new ProfitSharePlanner.Receiver("PERSONAL_OPENID", ACCOUNT, APPID, 2000)), f.gateway.lastReceivers);
        EcomProfitShareLine personal = f.mapper.lines.stream()
                .filter(l -> "PERSONAL_OPENID".equals(l.getReceiverType())).findFirst().orElseThrow();
        assertEquals(APPID, personal.getReceiverAppid());
        assertEquals(66L, personal.getReceiverMemberId());
        assertEquals(7L, personal.getClubId());
        assertEquals("Frozen club", personal.getClubName());
        assertEquals(3000L, order.getSharedFen());
        assertEquals(List.of(66L), f.bindings.reads);
        assertTrue(f.gateway.calls.get(0).endsWith(":false"));
        assertTrue(f.gateway.calls.get(1).startsWith("finish:CYF"));
        assertNotEquals("finish:" + order.getOutOrderNo(), f.gateway.calls.get(1));
    }

    @Test
    void missingBindingHoldsWholeOrderAndReplanUsesSameFrozenMember() {
        Fixture f = new Fixture();
        f.bindings.binding = null;
        EcomProfitShareOrder order = f.plan();
        assertEquals("MANUAL", order.getState());
        assertEquals("PERSONAL_BINDING_REQUIRED", order.getManualReason());
        assertEquals(0, f.service.submitPlanned(10));
        assertTrue(f.gateway.calls.isEmpty());
        f.mapper.sellerMembers.put("CLUB:7", 99L);
        f.bindings.verified(66L, APPID);
        assertEquals("PLANNED", f.service.replan(order.getId()));
        assertEquals(List.of(66L, 66L), f.bindings.reads);
        assertTrue(f.mapper.lines.stream().anyMatch(l -> "PERSONAL_OPENID".equals(l.getReceiverType())
                && Long.valueOf(66L).equals(l.getReceiverMemberId())));
        assertEquals("NOT_REPLANNABLE", f.service.replan(order.getId()));
        assertEquals(1, f.service.submitPlanned(10));
        assertEquals("SUCCESS", order.getState());
    }

    @Test
    void wrongAppidIsReplannableAndUnverifiedOrWrongMemberCannotReroute() {
        Fixture wrongApp = new Fixture();
        wrongApp.bindings.verified(66L, "another-app");
        EcomProfitShareOrder order = wrongApp.plan();
        assertEquals("PERSONAL_BINDING_APPID_INVALID", order.getManualReason());
        wrongApp.bindings.verified(66L, APPID);
        assertEquals("PLANNED", wrongApp.service.replan(order.getId()));
        for (boolean wrongMember : List.of(false, true)) {
            Fixture f = new Fixture();
            if (wrongMember) f.bindings.binding.setMemberId(99L);
            else f.bindings.binding.setState("PENDING");
            assertEquals("PERSONAL_BINDING_REQUIRED", f.plan().getManualReason());
            assertTrue(f.gateway.calls.isEmpty());
        }
    }

    @Test
    void switchOffDoesNotReadBindingAndPreservesLegacyMerchantPlan() {
        Fixture f = new Fixture();
        f.mapper.components.put(77L, List.of(component(1, "ACCOUNTING", null, "10.00", null),
                component(2, "MEMBER", 55L, "90.00", 7L)));
        EcommerceProfitShareService off = f.service(switches(false));
        assertEquals(1, off.planTopic(9));
        assertTrue(f.bindings.reads.isEmpty());
        assertEquals(1, off.submitPlanned(10));
        assertEquals(List.of(new ProfitSharePlanner.Receiver(ACCOUNT, 1000)), f.gateway.lastReceivers);
        assertEquals("SUCCESS", f.mapper.orders.get(0).getState());
        assertTrue(f.gateway.calls.get(0).endsWith(":true"));
    }

    @Test
    void switchOffBlocksFrozenPersonalSubmitAndReplanButReconcilesSubmittedPersonal() {
        Fixture f = new Fixture();
        EcomProfitShareOrder order = f.plan();
        EcommerceProfitShareService off = f.service(switches(false));
        assertEquals(0, off.submitPlanned(10));
        assertEquals("PLANNED", order.getState());
        assertTrue(f.gateway.calls.isEmpty());
        f.gateway.failNext = new RuntimeException("timeout");
        assertEquals(1, f.service.submitPlanned(10));
        assertEquals("PROCESSING", order.getState());
        assertEquals(1, off.syncProcessing(10));
        assertEquals("SUCCESS", order.getState());
        assertEquals(List.of(66L), f.bindings.reads);
        Fixture held = new Fixture();
        held.bindings.binding = null;
        EcomProfitShareOrder hold = held.plan();
        assertEquals("SWITCH_OFF", held.service(switches(false)).replan(hold.getId()));
        assertEquals(List.of(66L), held.bindings.reads);
    }

    @Test
    void uncertainPersonalRemainderFinishStaysProcessingAndRetriesSameFinishBeforeSuccess() {
        Fixture f = new Fixture();
        EcomProfitShareOrder order = f.plan();
        f.gateway.failFinish = true;
        f.service.submitPlanned(10);
        assertEquals("PROCESSING", order.getState());
        String finish = f.gateway.calls.get(1);
        assertEquals(32, finish.substring("finish:".length()).length());
        f.service.syncProcessing(10);
        assertEquals("PROCESSING", order.getState());
        assertEquals(finish, f.gateway.calls.get(3));
        f.gateway.failFinish = false;
        f.service.syncProcessing(10);
        assertEquals("SUCCESS", order.getState());
        assertEquals(finish, f.gateway.calls.get(5));
        assertEquals(1, f.gateway.calls.stream().filter(c -> c.startsWith("share:")).count());
    }

    @Test
    void finalQueriesRequireExactIdentityAmountMultiset() {
        List<List<EcommercePayGateway.ReceiverResult>> invalid = Arrays.asList(null, List.of(),
                List.of(merchantResult(1000)), List.of(merchantResult(1000), merchantResult(2000)),
                List.of(merchantResult(1000), personalResult("wrong-app", 2000)),
                List.of(merchantResult(1000), personalResult(APPID, 1999)),
                List.of(merchantResult(1000), new EcommercePayGateway.ReceiverResult(
                        "PERSONAL_OPENID", "different-openid", APPID, 2000, "SUCCESS", null)),
                List.of(merchantResult(1000), personalResult(APPID, 2000), personalResult(APPID, 2000)),
                Arrays.asList(merchantResult(1000), null));
        for (List<EcommercePayGateway.ReceiverResult> results : invalid) {
            Fixture f = new Fixture();
            EcomProfitShareOrder order = f.plan();
            order.setState("PROCESSING");
            f.gateway.query = new EcommercePayGateway.ShareResult("FINISHED", "WX1", results);
            assertEquals(1, f.service.syncProcessing(10));
            assertEquals("MANUAL", order.getState(), String.valueOf(results));
            assertEquals("WECHAT_SHARE_RECEIVERS_MISMATCH", order.getManualReason());
            assertEquals(EcommerceProfitShareService.ReturnOutcome.PENDING,
                    f.service.returnForRefund("REG1", "RF1", 10000, 0));
            assertTrue(f.mapper.returns.isEmpty());
            assertFalse(f.gateway.calls.stream().anyMatch(c -> c.startsWith("finish:")));
        }
    }

    @Test
    void legacyMerchantFinalQueryStillValidatesWhenPersonalSwitchOff() {
        Fixture f = new Fixture();
        f.mapper.components.put(77L, List.of(component(1, "ACCOUNTING", null, "10.00", null),
                component(3, "MEMBER", 55L, "90.00", null)));
        EcommerceProfitShareService off = f.service(switches(false));
        off.planTopic(9);
        f.mapper.orders.get(0).setState("PROCESSING");
        f.gateway.query = new EcommercePayGateway.ShareResult("FINISHED", "WX1", List.of());
        off.syncProcessing(10);
        assertEquals("MANUAL", f.mapper.orders.get(0).getState());
        assertTrue(f.bindings.reads.isEmpty());
    }

    @Test
    void zeroShareLegacyFinishAcceptsEmptyButRejectsUnexpectedReceiver() {
        Fixture f = new Fixture();
        f.mapper.components.put(77L, List.of(component(3, "MEMBER", 55L, "100.00", null)));
        assertEquals("SUCCESS", f.submit().getState());
        assertTrue(f.gateway.calls.stream().anyMatch(c -> c.startsWith("finish:")));
        Fixture unexpected = new Fixture();
        unexpected.mapper.components.put(77L, List.of(component(3, "MEMBER", 55L, "100.00", null)));
        EcomProfitShareOrder order = unexpected.plan();
        order.setState("PROCESSING");
        unexpected.gateway.query = new EcommercePayGateway.ShareResult("FINISHED", "WX1", List.of(merchantResult(1)));
        unexpected.service.syncProcessing(10);
        assertEquals("MANUAL", order.getState());
    }

    @Test
    void duplicateCallbacksAndQueriesDoNotAdvanceFinishOrResubmitAgain() {
        Fixture f = new Fixture();
        EcomProfitShareOrder order = f.submit();
        int attempts = order.getAttempts();
        int calls = f.gateway.calls.size();
        EcommercePayGateway.ShareResult receipt = new EcommercePayGateway.ShareResult("FINISHED", "WX1",
                List.of(merchantResult(1000), personalResult(APPID, 2000)));
        f.service.applyResult(order.getId(), receipt);
        f.service.applyResult(order.getId(), receipt);
        assertEquals(attempts, order.getAttempts());
        assertEquals(0, f.service.syncProcessing(10));
        assertEquals(0, f.service.submitPlanned(10));
        assertEquals(calls, f.gateway.calls.size());
    }

    @Test
    void refundPersistsTypedIdentitiesAndUnsupportedPersonalFailureWithoutCollision() {
        Fixture f = new Fixture();
        EcomProfitShareOrder order = f.submit();
        assertEquals(EcommerceProfitShareService.ReturnOutcome.FAILED,
                f.service.returnForRefund("REG1", "RF1", 10000, 0));
        assertEquals(2, f.mapper.returns.size());
        EcomProfitShareReturn merchant = f.mapper.returns.stream()
                .filter(r -> "MERCHANT_ID".equals(r.getReturnType())).findFirst().orElseThrow();
        EcomProfitShareReturn personal = f.mapper.returns.stream()
                .filter(r -> "PERSONAL_OPENID".equals(r.getReturnType())).findFirst().orElseThrow();
        assertEquals(EcommerceProfitShareService.outReturnNo(order.getId(), "RF1", ACCOUNT), merchant.getOutReturnNo());
        assertEquals("", merchant.getReturnAppid());
        assertEquals("SUCCESS", merchant.getState());
        assertEquals(1000L, merchant.getAmountFen());
        assertEquals(ACCOUNT, personal.getReturnMchid());
        assertEquals(APPID, personal.getReturnAppid());
        assertEquals(2000L, personal.getAmountFen());
        assertEquals("FAILED", personal.getState());
        assertEquals("PERSONAL_PROFITSHARING_RETURN_UNSUPPORTED", personal.getFailReason());
        assertNotEquals(merchant.getOutReturnNo(), personal.getOutReturnNo());
        int calls = f.gateway.calls.size();
        assertEquals(EcommerceProfitShareService.ReturnOutcome.FAILED,
                f.service(switches(false)).returnForRefund("REG1", "RF1", 10000, 0));
        assertEquals(EcommerceProfitShareService.ReturnOutcome.FAILED, f.service.retryReturn(personal.getId()));
        assertEquals(calls, f.gateway.calls.size());
        assertEquals(2, f.mapper.returns.size());
        personal.setState("PROCESSING");
        assertEquals(1, f.service(switches(false)).syncReturns(10));
        assertEquals("FAILED", personal.getState());
        assertEquals("PERSONAL_PROFITSHARING_RETURN_UNSUPPORTED", personal.getFailReason());
        assertEquals(calls, f.gateway.calls.size());
    }

    @Test
    void personalRefundHashIncludesAppidAndLegacyMerchantHashIsUnchanged() {
        ProfitSharePlanner.Receiver merchant = new ProfitSharePlanner.Receiver(ACCOUNT, 1000);
        ProfitSharePlanner.Receiver personal = new ProfitSharePlanner.Receiver("PERSONAL_OPENID", ACCOUNT, APPID, 1000);
        ProfitSharePlanner.Receiver otherApp = new ProfitSharePlanner.Receiver("PERSONAL_OPENID", ACCOUNT, "wx-other", 1000);
        assertEquals(EcommerceProfitShareService.outReturnNo(1, "RF", ACCOUNT),
                EcommerceProfitShareService.outReturnNo(1, "RF", merchant));
        assertNotEquals(EcommerceProfitShareService.outReturnNo(1, "RF", merchant),
                EcommerceProfitShareService.outReturnNo(1, "RF", personal));
        assertNotEquals(EcommerceProfitShareService.outReturnNo(1, "RF", personal),
                EcommerceProfitShareService.outReturnNo(1, "RF", otherApp));
    }

    @Test
    void merchantReturnRetryUsesPersistedIdentityEvenIfShareLinesChange() {
        Fixture f = new Fixture();
        f.submit();
        f.gateway.returnResult = "FAILED";
        assertEquals(EcommerceProfitShareService.ReturnOutcome.FAILED,
                f.service.returnForRefund("REG1", "RF1", 10000, 0));
        EcomProfitShareReturn merchant = f.mapper.returns.stream()
                .filter(r -> "MERCHANT_ID".equals(r.getReturnType())).findFirst().orElseThrow();
        f.mapper.lines.forEach(l -> l.setReceiverMchid("changed-live-account"));
        f.gateway.returnResult = "SUCCESS";
        assertEquals(EcommerceProfitShareService.ReturnOutcome.ALL_RETURNED, f.service.retryReturn(merchant.getId()));
        assertEquals("return:" + ACCOUNT + ":1000", f.gateway.calls.get(f.gateway.calls.size() - 1));
        int calls = f.gateway.calls.size();
        assertEquals(EcommerceProfitShareService.ReturnOutcome.NOT_APPLICABLE, f.service.retryReturn(merchant.getId()));
        assertEquals(calls, f.gateway.calls.size());
    }

    @Test
    void sessionPlanningAndMissingBindingReplanUseFrozenNegativeNamespace() {
        Fixture f = new Fixture();
        List<EcomLedgerComponent> frozen = List.of(component(-1, "ACCOUNTING", null, "10.00", null),
                component(-2, "MEMBER", 66L, "20.00", 7L), component(-3, "MEMBER", 55L, "70.00", null));
        int[] sessionReads = {0};
        int[] received = {0};
        int[] completed = {0};
        var sessions = new FakeEcomPayoutMapper() {
            @Override public List<Long> selectMissingActivityIds(int limit) { sessionReads[0]++; return List.of(42L); }
            @Override public List<EcomOrderSeller> selectPaidTrades(Long activityId) {
                assertEquals(42L, activityId);
                sessionReads[0]++;
                return f.mapper.trades;
            }
            @Override public List<EcomLedgerComponent> selectComponents(Long activityId, Long registrationId) {
                sessionReads[0]++;
                assertEquals(42L, activityId);
                assertEquals(77L, registrationId);
                return frozen;
            }
        };
        var details = mock(com.chengyinhub.business.mapper.SettlementDetailMapper.class);
        var activities = mock(com.chengyinhub.business.mapper.CmsActivityMapper.class);
        when(details.markShareDetailsReceived(anyLong())).thenAnswer(i -> {
            Long shareOrderId = i.getArgument(0);
            assertEquals("SUCCESS", f.mapper.selectShareOrderById(shareOrderId).getState());
            assertTrue(f.gateway.calls.get(f.gateway.calls.size() - 1).startsWith("finish:CYF"));
            received[0]++;
            return 1;
        });
        when(activities.completeActivityIfReceived(anyLong())).thenAnswer(i -> {
            assertEquals(1, received[0]);
            completed[0]++;
            return 1;
        });
        f.service.setSessionWriteMappers(details, activities);
        f.service.setSessionPayoutMapper(sessions);
        EcommerceProfitShareService off = f.service(switches(false));
        off.setSessionWriteMappers(details, activities);
        off.setSessionPayoutMapper(sessions);
        assertEquals(0, off.planActivity(42));
        assertEquals(0, off.planMissing(10));
        assertEquals(0, sessionReads[0]);
        f.bindings.binding = null;
        assertEquals(1, f.service.planMissing(10));
        assertEquals(0, f.service.planActivity(42));
        EcomProfitShareOrder order = f.mapper.orders.get(0);
        assertEquals(-42L, order.getTopicId());
        assertEquals("PERSONAL_BINDING_REQUIRED", order.getManualReason());
        assertEquals("SWITCH_OFF", off.replan(order.getId()));
        f.bindings.verified(66L, APPID);
        assertEquals("PLANNED", f.service.replan(order.getId()));
        assertTrue(f.mapper.lines.stream().allMatch(l -> l.getSettlementId() < 0));
        assertTrue(f.mapper.lines.stream().anyMatch(l -> Long.valueOf(7L).equals(l.getClubId())));
        assertEquals(0, received[0]);
        f.gateway.failFinish = true;
        assertEquals(1, f.service.submitPlanned(10));
        assertEquals("PROCESSING", order.getState());
        assertEquals(0, received[0]);
        assertTrue(f.gateway.calls.get(0).endsWith(":false"));
        f.gateway.failFinish = false;
        assertEquals(1, off.syncProcessing(10));
        assertEquals("SUCCESS", order.getState());
        assertEquals(1, received[0]);
        assertEquals(1, completed[0]);
        assertEquals(0, off.syncProcessing(10));
        f.service.applyResult(order.getId(), new EcommercePayGateway.ShareResult("FINISHED", "W1",
                List.of(merchantResult(1000), personalResult(APPID, 2000))));
        assertEquals(1, received[0]);
        assertEquals(1, completed[0]);
    }

    @Test
    void differentPersonalAppidsRemainSeparateInFrozenShareAndRefundGroups() {
        Fixture f = new Fixture();
        EcomProfitShareOrder order = f.plan();
        EcomProfitShareLine first = f.mapper.lines.stream()
                .filter(l -> "PERSONAL_OPENID".equals(l.getReceiverType())).findFirst().orElseThrow();
        first.setAmountFen(1000L);
        EcomProfitShareLine other = new EcomProfitShareLine();
        other.setShareOrderId(order.getId()); other.setSettlementId(4L); other.setLineType("CLUB_SESSION");
        other.setReceiverKind("PERSONAL"); other.setReceiverType("PERSONAL_OPENID");
        other.setReceiverMchid(ACCOUNT); other.setReceiverAppid("wx-other"); other.setAmountFen(1000L);
        f.mapper.insertShareLine(other);
        assertEquals(1, f.service.submitPlanned(10));
        assertEquals(3, f.gateway.lastReceivers.size());
        assertTrue(f.gateway.lastReceivers.contains(new ProfitSharePlanner.Receiver("PERSONAL_OPENID", ACCOUNT, APPID, 1000)));
        assertTrue(f.gateway.lastReceivers.contains(new ProfitSharePlanner.Receiver("PERSONAL_OPENID", ACCOUNT, "wx-other", 1000)));
        assertEquals(EcommerceProfitShareService.ReturnOutcome.FAILED,
                f.service.returnForRefund("REG1", "RF1", 10000, 0));
        assertEquals(3, f.mapper.returns.size());
        assertEquals(3, f.mapper.returns.stream().map(EcomProfitShareReturn::getOutReturnNo).distinct().count());
        assertEquals(2, f.mapper.returns.stream().filter(r -> "FAILED".equals(r.getState())).count());
    }

    @Test
    void returnQueryUsesFrozenPersistedIdentityRatherThanCurrentShareLines() {
        Fixture f = new Fixture();
        f.submit();
        f.gateway.returnResult = "PROCESSING";
        assertEquals(EcommerceProfitShareService.ReturnOutcome.FAILED,
                f.service.returnForRefund("REG1", "RF1", 10000, 0));
        f.mapper.lines.forEach(l -> l.setReceiverMchid("changed-live-account"));
        f.gateway.returnResult = "SUCCESS";
        assertEquals(1, f.service.syncReturns(10));
        assertEquals(new ProfitSharePlanner.Receiver(ACCOUNT, 1000), f.gateway.queriedReturnReceiver);
    }

    private static EcommercePayGateway.ReceiverResult merchantResult(long amount) {
        return new EcommercePayGateway.ReceiverResult(ACCOUNT, amount, "SUCCESS", null);
    }
    private static EcommercePayGateway.ReceiverResult personalResult(String appid, long amount) {
        return new EcommercePayGateway.ReceiverResult("PERSONAL_OPENID", ACCOUNT, appid, amount, "SUCCESS", null);
    }
}
