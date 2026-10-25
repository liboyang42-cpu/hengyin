package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.*;
import com.chengyinhub.common.config.RestoreDispatchGuard;
import com.wechat.pay.java.service.transferbatch.TransferBatchService;
import com.wechat.pay.java.service.transferbatch.model.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Fixed child JVMs exercise the actual startup environment, never a mocked static guard.
 * Mapper/SDK doubles are explicit unit boundaries, not MySQL, provider-verification or rollback evidence. */
class RestoreMoneyDispatchTest {
    @Test void restoreRefusesFreshFinancialOwnersBeforeAnyEconomicWriteOrNumberAllocation() throws Exception { child("true", "fresh"); }
    @Test void invalidStartupFlagFailsClosedAtFinancialAdmission() throws Exception { child("not-a-boolean", "invalid"); }
    @Test void explicitlyEmptyStartupFlagAlsoFailsClosed() throws Exception { child("", "invalid"); }
    @Test void normalFalseStillAdmitsAFirstOriginalSubmission() throws Exception { child("false", "normal"); }
    @Test void absentFlagPreservesTheNormalPath() throws Exception { child(null, "normal"); }
    @Test void restoreKeepsOriginalUnknownAndNeverCertifiesNotSentOrCreditsIt() throws Exception { child("true", "unknown"); }
    @Test void restoreCanReadAndRetainLateOriginalSuccessWithoutDispatchOrCredit() throws Exception { child("true", "success"); }
    @Test void restoreRetainsFinalFailureEvidenceButPausesAutomaticCredit() throws Exception { child("true", "fail"); }
    @Test void restoreRefundCallbackRetainsVerifiedFactAndInboxButDoesNotConsumeOrMarkDone() throws Exception { child("true", "callback"); }
    @Test void restorePayoutRecoveryQueriesOnlyTheExistingOriginalNumberWithoutClaimOrAllocation() throws Exception { child("true", "payout"); }

    @Test void masterV1OriginalUnknownRemainsQueryOnlyWithoutInventingLedgerEvidence() throws Exception { child("true", "master-UNKNOWN"); }
    @Test void masterV1LateSuccessRetainsItsOwnOriginalHashAndHasNoSecondEconomicEffect() throws Exception { child("true", "master-SUCCESS"); }
    @Test void masterV1FinalFailureStillCannotCreditDuringRestore() throws Exception { child("true", "master-FAIL"); }

    @Test void restoreSchedulerStillQueriesOriginalsWithoutCloseReleaseOrFalseAppliedOutcome() throws Exception { child("true", "scheduler"); }

    @Test void restoreRefundReconciliationKeepsOriginalQueryButNeverInventsAMissingRefundNumber() throws Exception { child("true", "refund-scheduler"); }

    @Test void adjacentOwnersPauseBeforeTheirOwnNumbersOrEconomicEffects() throws Exception { child("true", "adjacent"); }

    private static void child(String flag, String scenario) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        ProcessBuilder launch = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m", "-cp", classpath, Probe.class.getName(), scenario);
        launch.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        if (flag == null) launch.environment().remove("CHENGYIN_RESTORE_MODE");
        else launch.environment().put("CHENGYIN_RESTORE_MODE", flag);
        Process owned = launch.start();
        try {
            assertTrue(owned.waitFor(45, TimeUnit.SECONDS), "fixed restore-money child did not exit: " + scenario);
            assertEquals(0, owned.exitValue(), "fixed restore-money contract failed: " + scenario
                    + "; " + Probe.describeExit(owned.exitValue()));
        } finally {
            if (owned.isAlive()) {
                owned.destroyForcibly();
                assertTrue(owned.waitFor(10, TimeUnit.SECONDS), "owned restore-money child cleanup incomplete");
            }
        }
    }

    public static final class Probe {
        private enum Phase { SETUP, ADMISSION, CHANNEL_CALL, MANUAL_HOLD, VERIFY_ORIGINAL }
        private static Phase phase = Phase.SETUP;
        private static final String[] FAILURE_TYPES = {"ASSERTION", "NULL_VALUE", "STATE", "ARGUMENT", "SQL", "OTHER"};
        // Fixed codes below 128 carry no exception message, environment, SQL or provider payload.
        private static int failureCode(Throwable failure) {
            int type = failure instanceof AssertionError ? 0 : failure instanceof NullPointerException ? 1
                    : failure instanceof IllegalStateException ? 2 : failure instanceof IllegalArgumentException ? 3
                    : failure instanceof java.sql.SQLException || failure instanceof org.springframework.dao.DataAccessException ? 4 : 5;
            return 20 + phase.ordinal() * 8 + type;
        }
        private static String describeExit(int exit) {
            if (exit == 0) return "childExit=0,phase=COMPLETE,type=NONE";
            int code = exit - 20;
            if (code < 0 || code / 8 >= Phase.values().length || code % 8 >= FAILURE_TYPES.length)
                return "childExit=" + exit + ",phase=UNKNOWN,type=UNKNOWN";
            return "childExit=" + exit + ",phase=" + Phase.values()[code / 8].name()
                    + ",type=" + FAILURE_TYPES[code % 8];
        }
        public static void main(String[] args) {
            try {
                assertEquals(1, args.length);
                switch (args[0]) {
                    case "fresh", "invalid" -> fresh();
                    case "normal" -> normal();
                    case "adjacent" -> adjacent();
                    case "unknown", "success", "fail" -> cashObservation(args[0]);
                    case "master-UNKNOWN", "master-SUCCESS", "master-FAIL" -> masterObservation(args[0].substring(7));
                    case "callback" -> callback();
                    case "payout" -> payout();
                    case "scheduler" -> scheduler();
                    case "refund-scheduler" -> refundScheduler();
                    default -> throw new AssertionError("unknown fixed scenario");
                }
            } catch (Throwable failure) {
                System.err.println("RESTORE_MONEY_CONTRACT_FAILED");
                System.exit(failureCode(failure));
            }
        }

        private static void blocked(Runnable action) {
            RestoreDispatchGuard.Blocked pause = assertThrows(RestoreDispatchGuard.Blocked.class, action::run);
            assertEquals("RECOVERY_DISPATCH_BLOCKED", pause.getMessage());
        }

        private static void fresh() {
            phase = Phase.ADMISSION;
            var rows = mock(MemberCashWithdrawMapper.class);
            var cash = new CashWithdrawServiceImpl();
            ReflectionTestUtils.setField(cash, "mapper", rows);
            blocked(() -> cash.withdraw(9L, new BigDecimal("30.00")));
            verifyNoInteractions(rows);
            var bank = new UmsMemberWithdrawalServiceImpl();
            var bankRows = mock(UmsMemberWithdrawalMapper.class);
            ReflectionTestUtils.setField(bank, "umsMemberWithdrawalMapper", bankRows);
            blocked(() -> bank.withdrawalRequest(null, 9L, "actor"));
            blocked(() -> bank.withdrawPendingByMember(1L, 9L));
            blocked(() -> bank.reviewWithdrawal(null, 9L));
            blocked(() -> bank.confirmPayout(null, 9L));
            verifyNoInteractions(bankRows);
            var members = mock(UmsMemberMapper.class);
            var frozen = mock(WithdrawalFrozenAmountResolver.class);
            var admission = new WithdrawalBalanceAdmissionService(members, frozen, rows,
                    mock(CashWithdrawTransferIntentMapper.class));
            blocked(() -> admission.debitBank(9L, BigDecimal.TEN));
            blocked(() -> admission.debitCash(1L, 9L, BigDecimal.TEN, "merchant", "wx-original", "提现"));
            verifyNoInteractions(members, frozen, rows);
            var registrationRows = mock(CmsRegistrationMapper.class);
            var registration = new CmsRegistrationServiceImpl();
            ReflectionTestUtils.setField(registration, "cmsRegistrationMapper", registrationRows);
            blocked(() -> registration.create(9L, new RegistrationRequest(), "request", "fingerprint"));
            blocked(() -> registration.beginPaymentAttempt(1L, 9L));
            blocked(() -> registration.preparePaymentRetry(1L, 9L));
            blocked(() -> registration.completeZeroAmountPayment("REG1"));
            blocked(() -> registration.cancelRegistration(1L, 9L, "actor"));
            blocked(() -> registration.closeExpiredOne(1L));
            verifyNoInteractions(registrationRows);
            var refundRows = mock(RefundApplicationMapper.class);
            var refund = new RefundServiceImpl();
            ReflectionTestUtils.setField(refund, "refundApplicationMapper", refundRows);
            blocked(() -> refund.saveRefund(new RefundApplication()));
            blocked(() -> refund.reviewRefund(null, 1L));
            blocked(() -> refund.verifyManualPayout(1L, 2L));
            verifyNoInteractions(refundRows);
            var team = new TeamGroupServiceImpl();
            ReflectionTestUtils.setField(team, "cmsRegistrationMapper", registrationRows);
            blocked(() -> team.cancelPaidRegistration(1L, 9L));
            blocked(() -> team.cancelActivityByOwner(1L, 9L, "cancel"));
            verifyNoInteractions(registrationRows);
            var sdk = mock(TransferBatchService.class);
            var gateway = new WechatPaymentSdkGateway();
            ReflectionTestUtils.setField(gateway, "transferBatchService", sdk);
            blocked(() -> gateway.transferToBalance(request()));
            blocked(() -> gateway.transferToBalance("CW81", "CD81", new BigDecimal("30.00"), "openid-original", "提现", "wx-original"));
            blocked(() -> gateway.createOrder(new CmsRegistration()));
            blocked(() -> gateway.createGenericOrder("REG1", BigDecimal.TEN, "订单", "openid-original"));
            blocked(() -> gateway.createGenericAppOrder("REG1", BigDecimal.TEN, "订单"));
            blocked(() -> gateway.submitRefund("REG1", "RF1", BigDecimal.ONE, BigDecimal.TEN, "退款"));
            blocked(() -> gateway.closePaymentAttempt("REG1", "REG1"));
            verifyNoInteractions(sdk);
            var intentJdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
            var intents = new WechatPaymentIntentStore();
            ReflectionTestUtils.setField(intents, "paymentIntentJdbc", intentJdbc);
            blocked(() -> intents.freezePaymentIntent("REG1", "merchant", "wx-original", "JSAPI", "CNY", 1000,
                    "openid-original", "订单", "https://notify.invalid/fixture", () -> fail("historical SDK probe forbidden")));
            verifyNoInteractions(intentJdbc);
        }

        private static void adjacent() {
            blocked(() -> new MerchantCommerceService(false).createOrder(9L, 1L, "openid-original", "premium_template", "original"));
            blocked(() -> new ClubMembershipServiceImpl().renew(9L, null));
            blocked(() -> new OmsOrderServiceImpl().insertOmsOrder(null));
            blocked(() -> new UmsMemberCartServiceImpl().settleCart(9L, "actor", "1", null, "1"));
            blocked(() -> new CoopInviteServiceImpl().prepareDeposit(1L, 9L));
            blocked(() -> new CoopInviteServiceImpl().authorizeDepositRefundRetry(1L, 9L));
            blocked(() -> new CoopInviteServiceImpl().createCoopOrder(null));
            blocked(() -> new CoopDepositRefundService().requestLocked(null));
            blocked(() -> new CmsTopicServiceImpl().executeTopicSettlement());
            blocked(() -> new CmsTopicServiceImpl().payoutDueCoopSettlements());
            var clubAccess = mock(IClubAccessService.class);
            var clubSessions = mock(IClubSessionService.class);
            var clubEvents = mock(ClubEventOpsMapper.class);
            var clubMembers = mock(ClubMemberMapper.class);
            var clubLeads = mock(ClubLeadMapper.class);
            var clubRoles = mock(IClubRoleAssignmentService.class);
            var clubGroups = mock(ITeamGroupService.class);
            var club = new ClubEventOpsServiceImpl(clubAccess, new ClubEventScheduleMaterializer(),
                    clubSessions, clubEvents, clubMembers, clubLeads, clubRoles, clubGroups);
            blocked(() -> club.cancelOccurrence(9L, "actor", null));
            verifyNoInteractions(clubAccess, clubSessions, clubEvents, clubMembers, clubLeads, clubRoles, clubGroups);
        }

        private static CashWithdrawTransferIntent request() {
            CashWithdrawTransferIntent unsigned = CashWithdrawTransferIntent.fromRequest(81L, "merchant", "wx-original",
                    "CW81", "CD81", new BigDecimal("30.00"), "openid-original", "城瘾分润提现");
            return new CashWithdrawTransferIntent(81L, 101L, unsigned.mchid(), unsigned.appid(), unsigned.outBatchNo(),
                    unsigned.outDetailNo(), unsigned.amountFen(), unsigned.openid(), unsigned.effectiveRemark(),
                    unsigned.requestVersion(), unsigned.requestHash());
        }

        private static WechatPaymentSdkGateway gateway(TransferBatchService sdk) throws Exception {
            var result = new WechatPaymentSdkGateway();
            // The actual master-V1 query also checks the configured merchant. Use the same
            // explicit original fixture channel as the immutable DTO and captured SDK binding.
            var config = new com.chengyinhub.common.config.WxPayConfig();
            ReflectionTestUtils.setField(config, "merchantId", "merchant");
            ReflectionTestUtils.setField(config, "appid", "wx-original");
            ReflectionTestUtils.setField(result, "wxPayConfig", config);
            Class<?> type = Class.forName(WechatPaymentSdkGateway.class.getName() + "$TransferClientBinding");
            var constructor = type.getDeclaredConstructor(TransferBatchService.class, String.class, String.class, String.class);
            constructor.setAccessible(true);
            ReflectionTestUtils.setField(result, "transferClientBinding", constructor.newInstance(sdk, "merchant", "wx-original", null));
            return result;
        }

        private static void normal() throws Exception {
            assertFalse(RestoreDispatchGuard.isRestoreMode());
            TransferBatchService sdk = mock(TransferBatchService.class);
            var reply = new InitiateBatchTransferResponse();
            reply.setOutBatchNo("CW81");
            reply.setBatchId("provider-batch"); reply.setBatchStatus("ACCEPTED");
            when(sdk.initiateBatchTransfer(any())).thenReturn(reply);
            Map<String, String> result = gateway(sdk).transferToBalance(request());
            assertEquals("true", result.get("ok"));
            assertEquals(request().requestHash(), result.get("requestHash"));
            verify(sdk, times(1)).initiateBatchTransfer(argThat(r -> "CW81".equals(r.getOutBatchNo())
                    && r.getTotalAmount() == 3000 && r.getTotalNum() == 1
                    && "CD81".equals(r.getTransferDetailList().get(0).getOutDetailNo())));
        }

        private static void serve(TransferBatchService sdk, String state) {
            var batch = new TransferBatchGet(); batch.setMchid("merchant"); batch.setAppid("wx-original");
            batch.setOutBatchNo("CW81"); batch.setBatchId("provider-batch"); batch.setBatchStatus("FINISHED");
            batch.setTotalAmount(3000L); batch.setTotalNum(1);
            var entity = new TransferBatchEntity(); entity.setTransferBatch(batch);
            when(sdk.getTransferBatchByOutNo(any())).thenReturn(entity);
            var detail = new TransferDetailEntity(); detail.setMchid("merchant"); detail.setAppid("wx-original");
            detail.setOutBatchNo("CW81"); detail.setOutDetailNo("CD81"); detail.setBatchId("provider-batch");
            detail.setDetailId("provider-detail"); detail.setDetailStatus(state); detail.setTransferAmount(3000L);
            detail.setOpenid("openid-original");
            when(sdk.getTransferDetailByOutNo(any())).thenReturn(detail);
        }

        private static void cashObservation(String scenario) throws Exception {
            assertTrue(RestoreDispatchGuard.isRestoreMode());
            var sdk = mock(TransferBatchService.class);
            var original = new MemberCashWithdraw();
            original.setId(81L); original.setMemberId(9L); original.setAmount(new BigDecimal("30.00"));
            original.setOutBatchNo("CW81"); original.setOutDetailNo("CD81"); original.setOpenid("openid-original");
            original.setStatus(MemberCashWithdraw.STATUS_PROCESSING); original.setDelFlag(0);
            var mapper = mock(MemberCashWithdrawMapper.class);
            when(mapper.selectById(81L)).thenReturn(original);
            when(mapper.selectByIdForUpdate(81L)).thenReturn(original);
            var ledger = new ArrayList<CashWithdrawFundFact>();
            var debit = CashWithdrawFundFact.of(original, CashWithdrawFundFact.DEBIT);
            debit.setId(100L); debit.setBalanceBefore(new BigDecimal("100.00")); debit.setBalanceAfter(new BigDecimal("70.00"));
            original.setBalanceDeductId(100L); ledger.add(debit);
            var submit = CashWithdrawFundFact.of(original, CashWithdrawFundFact.SUBMIT_INTENT);
            submit.setId(101L); submit.bindTransferIntent(request()); ledger.add(submit);
            when(mapper.selectFundFacts(81L)).thenReturn(ledger);
            when(mapper.insertFundFact(any())).thenAnswer(call -> {
                CashWithdrawFundFact fact = call.getArgument(0); fact.setId(100L + ledger.size()); ledger.add(fact); return 1;
            });
            when(mapper.confirmChannelSuccess(any())).thenAnswer(call -> {
                MemberCashWithdraw paid = call.getArgument(0); original.setStatus(paid.getStatus()); return 1;
            });
            var channel = gateway(sdk);
            if ("unknown".equals(scenario)) {
                var unknown = CashWithdrawFundFact.of(original, CashWithdrawFundFact.UNKNOWN);
                unknown.setId(102L); unknown.bindTransferIntent(request()); unknown.setSubmissionIntentId(101L); ledger.add(unknown);
                when(sdk.getTransferBatchByOutNo(any())).thenThrow(new IllegalStateException("controlled timeout"));
            } else serve(sdk, "success".equals(scenario) ? "SUCCESS" : "FAIL");
            var facade = new WechatPayServiceImpl();
            ReflectionTestUtils.setField(facade, "gateway", channel);
            var service = new CashWithdrawServiceImpl();
            ReflectionTestUtils.setField(service, "mapper", mapper);
            ReflectionTestUtils.setField(service, "transferIntents", mock(CashWithdrawTransferIntentMapper.class));
            ReflectionTestUtils.setField(service, "transactionManager", WechatVerifiedFactTestSupport.unitBusinessTransactions());
            ReflectionTestUtils.setField(service, "wechatPayService", facade);
            var members = mock(IUmsMemberService.class);
            ReflectionTestUtils.setField(service, "umsMemberService", members);
            ReflectionTestUtils.setField(service, "alarmService", mock(AlarmService.class));
            service.requery(81L);
            service.requery(81L); // Original late/duplicate observation cannot authorize another submit or credit.
            blocked(() -> service.manualRefund(81L, "maker", "restore pause"));
            blocked(() -> service.manualRefund(81L, "checker", "restore pause"));
            assertEquals(1, ledger.stream().filter(f -> CashWithdrawFundFact.DEBIT.equals(f.getKind())).count());
            assertEquals(1, ledger.stream().filter(f -> CashWithdrawFundFact.SUBMIT_INTENT.equals(f.getKind())).count());
            assertFalse(ledger.stream().anyMatch(f -> CashWithdrawFundFact.REFUND.equals(f.getKind())
                    || CashWithdrawFundFact.NOT_ACCEPTED.equals(f.getKind())));
            if ("success".equals(scenario)) assertEquals(MemberCashWithdraw.STATUS_SUCCESS, original.getStatus());
            else if ("fail".equals(scenario)) assertTrue(ledger.stream().anyMatch(f -> CashWithdrawFundFact.FINAL_FAIL.equals(f.getKind())));
            else assertTrue(ledger.stream().anyMatch(f -> CashWithdrawFundFact.UNKNOWN.equals(f.getKind())));
            verify(sdk, never()).initiateBatchTransfer(any()); verifyNoInteractions(members);
            assertEquals("CW81", original.getOutBatchNo()); assertEquals("CD81", original.getOutDetailNo());
        }

        private static void masterObservation(String outcome) throws Exception {
            phase = Phase.SETUP;
            var original = new MemberCashWithdraw(); original.setId(81L); original.setMemberId(9L);
            original.setAmount(new BigDecimal("30.00")); original.setOutBatchNo("CW81");
            original.setOutDetailNo("CD81"); original.setOpenid("openid-original");
            original.setStatus(MemberCashWithdraw.STATUS_PROCESSING); original.setDelFlag(0);
            var unsigned = CashWithdrawTransferIntent.create(original, "merchant", "wx-original", "城瘾分润提现",
                    new BigDecimal("100.00"), new BigDecimal("70.00"));
            var snapshot = new CashWithdrawTransferIntent(81L, 9L, unsigned.mchid(), unsigned.appid(), unsigned.outBatchNo(),
                    unsigned.outDetailNo(), unsigned.amountFen(), unsigned.openid(), unsigned.remark(), unsigned.requestVersion(),
                    unsigned.requestHash(), unsigned.balanceBefore(), unsigned.balanceAfter(), true, false);
            snapshot.requireMatches(original);
            var rows = mock(MemberCashWithdrawMapper.class);
            when(rows.selectById(81L)).thenReturn(original); when(rows.selectByIdForUpdate(81L)).thenReturn(original);
            when(rows.selectFundFacts(81L)).thenReturn(List.of());
            when(rows.updateSuccessIfPending(any())).thenAnswer(call -> {
                MemberCashWithdraw paid = call.getArgument(0); original.setStatus(paid.getStatus()); return 1;
            });
            when(rows.updateReceiptIfProcessing(any())).thenReturn(1);
            var snapshots = mock(CashWithdrawTransferIntentMapper.class);
            when(snapshots.selectByWithdrawId(81L)).thenReturn(snapshot);
            var sdk = mock(TransferBatchService.class);
            if ("UNKNOWN".equals(outcome)) when(sdk.getTransferBatchByOutNo(any())).thenThrow(new IllegalStateException("controlled timeout"));
            else serve(sdk, outcome);
            var facade = new WechatPayServiceImpl(); ReflectionTestUtils.setField(facade, "gateway", gateway(sdk));
            var service = new CashWithdrawServiceImpl(); ReflectionTestUtils.setField(service, "mapper", rows);
            ReflectionTestUtils.setField(service, "transferIntents", snapshots);
            ReflectionTestUtils.setField(service, "transactionManager", WechatVerifiedFactTestSupport.unitBusinessTransactions());
            ReflectionTestUtils.setField(service, "wechatPayService", facade);
            ReflectionTestUtils.setField(service, "alarmService", mock(AlarmService.class));
            var members = mock(IUmsMemberService.class); ReflectionTestUtils.setField(service, "umsMemberService", members);
            phase = Phase.CHANNEL_CALL;
            service.requery(81L); service.requery(81L);
            phase = Phase.MANUAL_HOLD;
            blocked(() -> service.manualRefund(81L, "maker", "restore pause"));
            blocked(() -> service.manualRefund(81L, "checker", "restore pause"));
            phase = Phase.VERIFY_ORIGINAL;
            assertEquals(snapshot, snapshots.selectByWithdrawId(81L)); assertEquals(unsigned.requestHash(), snapshot.requestHash());
            assertTrue(snapshot.hasDebitSnapshot()); assertNull(original.getBalanceDeductId()); assertNull(original.getBalanceRefundId());
            assertEquals("SUCCESS".equals(outcome) ? MemberCashWithdraw.STATUS_SUCCESS : MemberCashWithdraw.STATUS_PROCESSING, original.getStatus());
            verify(sdk, times("SUCCESS".equals(outcome) ? 1 : 2)).getTransferBatchByOutNo(any());
            verify(sdk, times("UNKNOWN".equals(outcome) ? 0 : "SUCCESS".equals(outcome) ? 1 : 2)).getTransferDetailByOutNo(any());
            verify(rows, never()).insertFundFact(any()); verify(snapshots, never()).insert(any());
            verify(sdk, never()).initiateBatchTransfer(any()); verifyNoInteractions(members);
        }

        private static void callback() {
            var facts = WechatVerifiedFactTestSupport.memoryHistoricalFacts();
            var fact = new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.REFUND, "merchant", "wx-original",
                    "REG1", "REG1", "RF1", "provider-refund", "provider-payment", "JSAPI", "CNY", 1000, 300,
                    Instant.parse(WechatVerifiedFactTestSupport.SUCCESS_TIME), null, "SUCCESS");
            var ref = facts.recordVerified(fact);
            var refund = new RefundApplication(); refund.setId(2L); refund.setSourceId(1L); refund.setSourceType("registration");
            refund.setRefundNo("RF1"); refund.setRefundAmount(new BigDecimal("3.00"));
            refund.setPayoutStatus(RefundApplication.PAYOUT_PROCESSING);
            var registration = new CmsRegistration(); registration.setId(1L); registration.setRegistrationNo("REG1");
            registration.setWechatPaymentAmount(BigDecimal.TEN);
            var refunds = mock(RefundApplicationMapper.class); when(refunds.selectByRefundNo("RF1")).thenReturn(refund);
            var registrations = mock(CmsRegistrationMapper.class); when(registrations.selectCmsRegistrationById(1L)).thenReturn(registration);
            var inbox = mock(IWxPaymentCallbackInboxService.class);
            var service = new RefundServiceImpl();
            ReflectionTestUtils.setField(service, "refundApplicationMapper", refunds);
            ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrations);
            ReflectionTestUtils.setField(service, "verifiedPaymentFacts", facts);
            ReflectionTestUtils.setField(service, "wxPaymentCallbackInboxService", inbox);
            Map<String, String> observed = Map.ofEntries(Map.entry("status", "SUCCESS"), Map.entry("outRefundNo", "RF1"),
                    Map.entry("outTradeNo", "REG1"), Map.entry("wxOutTradeNo", "REG1"), Map.entry("refundId", "provider-refund"),
                    Map.entry("transactionId", "provider-payment"), Map.entry("mchid", "merchant"), Map.entry("appid", "wx-original"),
                    Map.entry("tradeType", "JSAPI"), Map.entry("currency", "CNY"), Map.entry("refund", "300"), Map.entry("total", "1000"),
                    Map.entry("verified_fact_id", Long.toString(ref.id())), Map.entry("verified_fact_hash", ref.hash()));
            assertFalse(service.handleRefundNotify(observed));
            assertEquals(fact, facts.requireVerified(ref).facts());
            verify(inbox).registerVerified("REFUND:RF1:SUCCESS", WxPaymentCallbackInbox.BIZ_TYPE_REFUND, 2L, "SUCCESS", ref);
            verify(inbox, never()).processVerified(anyString(), any(), any());
            verify(refunds, never()).updatePayoutResult(anyLong(), any(), any());
            assertEquals(RefundApplication.PAYOUT_PROCESSING, refund.getPayoutStatus());
        }

        private static void refundScheduler() {
            var missing = new RefundApplication(); missing.setId(1L); missing.setSourceType("registration");
            missing.setSourceId(81L); missing.setPayoutStatus(RefundApplication.PAYOUT_PROCESSING);
            var retained = new RefundApplication(); retained.setId(2L); retained.setSourceType("registration");
            retained.setSourceId(82L); retained.setPayoutStatus(RefundApplication.PAYOUT_PROCESSING); retained.setRefundNo("RF-ORIGINAL");
            var rows = mock(RefundApplicationMapper.class);
            when(rows.countStuckPayoutDispatch(anyInt())).thenReturn(0);
            when(rows.selectStalePayoutScanUpperBound(anyInt())).thenReturn(2L);
            when(rows.selectStalePayoutProcessingAfter(anyInt(), eq(0L), eq(2L), eq(200))).thenReturn(List.of(missing, retained));
            var channel = mock(IWechatPayService.class);
            var payout = mock(IRefundPayoutService.class);
            var task = new com.chengyinhub.business.task.RefundReconcileTask();
            ReflectionTestUtils.setField(task, "refundApplicationMapper", rows);
            ReflectionTestUtils.setField(task, "wechatPayService", channel);
            ReflectionTestUtils.setField(task, "refundPayoutService", payout);
            ReflectionTestUtils.setField(task, "coopInviteService", mock(ICoopInviteService.class));
            ReflectionTestUtils.setField(task, "alarmService", mock(AlarmService.class));
            task.reconcile();
            verify(channel).refreshPayoutFromWx("RF-ORIGINAL"); verifyNoMoreInteractions(channel);
            verify(rows, never()).ensureRefundNo(anyLong(), anyString()); verifyNoInteractions(payout);
            assertNull(missing.getRefundNo()); assertEquals("RF-ORIGINAL", retained.getRefundNo());
            assertEquals(RefundApplication.PAYOUT_PROCESSING, retained.getPayoutStatus());
        }

        private static void scheduler() {
            var registrations = mock(ICmsRegistrationService.class);
            when(registrations.listExpiredPayingIds(0L)).thenReturn(List.of(1L, 2L));
            when(registrations.listExpiredPayingIds(2L)).thenReturn(List.of());
            for (long id : new long[]{1L, 2L}) {
                var row = new CmsRegistration(); row.setId(id); row.setRegistrationNo("REG" + id);
                row.setPaymentStatus(1); row.setRegistrationStatus(1);
                when(registrations.selectCmsRegistrationById(id)).thenReturn(row);
            }
            var channel = mock(IWechatPayService.class); when(channel.isReady()).thenReturn(true);
            when(channel.queryOrderStatus("REG1")).thenReturn(Map.of("out_trade_no", "REG1", "wx_out_trade_no", "REG1", "trade_state", "NOTPAY"));
            var success = Map.of("out_trade_no", "REG2", "wx_out_trade_no", "REG2", "trade_state", "SUCCESS");
            when(channel.queryOrderStatus("REG2")).thenReturn(success);
            // This wrapper's fact validation/inbox contract is exercised by callback()/the SQL case;
            // here the real scheduler must retain its call but never pretend economic application.
            when(registrations.handleWechatPayCallback(success)).thenReturn("FAIL");
            var alarm = mock(AlarmService.class);
            var task = new com.chengyinhub.business.task.OrderCloseTask();
            ReflectionTestUtils.setField(task, "cmsRegistrationService", registrations);
            ReflectionTestUtils.setField(task, "wechatPayService", channel);
            ReflectionTestUtils.setField(task, "alarmService", alarm);
            task.reconcileExpiredPaying();
            verify(channel).queryOrderStatus("REG1"); verify(channel).queryOrderStatus("REG2");
            verify(registrations).handleWechatPayCallback(success);
            verify(channel, never()).closePaymentAttempt(anyString(), anyString());
            verify(registrations, never()).settleClosedPayingOne(any(), anyString(), anyString(), anyString(), anyInt());
            verify(registrations, never()).settlePayingOne(anyLong(), anyString(), anyString(), anyInt());
            verifyNoInteractions(alarm);
        }

        private static void payout() {
            var row = new RefundApplication(); row.setId(2L); row.setStatus(1); row.setSourceType("registration");
            row.setSourceId(1L); row.setRefundAmount(new BigDecimal("3.00")); row.setRefundNo("RF1");
            row.setPayoutStatus(RefundApplication.PAYOUT_DISPATCHING);
            var rows = mock(RefundApplicationMapper.class); when(rows.selectRefundApplicationById(2L)).thenReturn(row);
            var reader = mock(RegistrationPaymentConsumptionReadService.class);
            var receipt = new RegistrationPaymentFactReceipt(1L, "REG1", 5L, "a".repeat(64), "provider-payment");
            when(reader.findConsumedPayment(1L)).thenReturn(Optional.of(receipt));
            var tx = mock(PlatformTransactionManager.class); when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            var channel = mock(IWechatPayService.class);
            var service = new RefundPayoutServiceImpl();
            ReflectionTestUtils.setField(service, "refundApplicationMapper", rows);
            ReflectionTestUtils.setField(service, "paymentConsumption", reader);
            ReflectionTestUtils.setField(service, "payoutTransactions", tx);
            ReflectionTestUtils.setField(service, "wechatPayService", channel);
            service.payoutAfterReview(2L);
            verify(channel).queryRefundFacts(receipt.reference(), "RF1");
            verify(channel, never()).submitRefund(any(), anyString(), anyString(), any(), any(), any());
            verify(rows, never()).claimPayoutDispatch(anyLong(), anyLong(), anyString());
            verify(rows, never()).ensureRefundNo(anyLong(), anyString());
            row.setRefundNo(null);
            service.payoutAfterReview(2L);
            verifyNoMoreInteractions(channel);
            assertNull(row.getRefundNo()); assertEquals(RefundApplication.PAYOUT_DISPATCHING, row.getPayoutStatus());
        }
    }
}
