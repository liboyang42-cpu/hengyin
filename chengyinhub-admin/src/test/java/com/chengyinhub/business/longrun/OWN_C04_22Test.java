package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent;
import com.chengyinhub.business.mapper.MemberCashWithdrawMapper;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.business.service.support.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** NOT_RUN. Actual caller with mapper/channel doubles; no claim of real SQL CAS or database concurrency. */
class OWN_C04_22Test {
    @Test void lateAcceptedAndAmbiguousReturnOriginalTerminalOrFrozenFacts() {
        for (boolean accepted : new boolean[]{true, false}) {
            for (int state : new int[]{MemberCashWithdraw.STATUS_SUCCESS, MemberCashWithdraw.STATUS_FAILED_REFUNDED,
                    MemberCashWithdraw.STATUS_PROCESSING}) {
                Fixture f = new Fixture();
                MemberCashWithdraw newer = row(state);
                newer.setFrozen(state == MemberCashWithdraw.STATUS_PROCESSING ? 1 : 0);
                newer.setWechatBatchStatus("AUTHORITATIVE");
                newer.setWechatDetailId("final-detail");
                when(f.provider.transferToBalance(any(com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent.class))).thenAnswer(i -> {
                    newer.setBalanceDeductId(f.stored.get().getBalanceDeductId());
                    f.stored.set(newer); // Another observer committed while the original channel call was in flight.
                    return accepted ? accepted() : ambiguous();
                });
                when(f.mapper.updateReceiptIfProcessing(any())).thenReturn(0);
                MemberCashWithdraw actual = f.service.withdraw(7L, new BigDecimal("20"));
                assertSame(newer, actual);
                assertEquals("AUTHORITATIVE", actual.getWechatBatchStatus());
                assertEquals("final-detail", actual.getWechatDetailId());
                ArgumentCaptor<MemberCashWithdraw> observation = ArgumentCaptor.forClass(MemberCashWithdraw.class);
                verify(f.mapper).updateReceiptIfProcessing(observation.capture());
                assertNull(observation.getValue().getStatus(), "An observation must never reset the lifecycle");
                // master 在插入后以 updateMemberCashWithdraw 写入 CW/CD 原业务号；只断言不改写生命周期状态。
                verify(f.mapper, never()).updateMemberCashWithdraw(org.mockito.ArgumentMatchers.argThat(w -> w.getStatus() != null));
                verify(f.mapper, never()).updateStatusIfProcessing(any());
                verify(f.members, never()).addBalance(anyLong(), any());
                verify(f.admission).debitCash(1L, 7L, new BigDecimal("20.00"), "unit-merchant", "appid", "城瘾分润提现");
                verify(f.provider).transferToBalance(any(com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent.class));
            }
        }
    }

    @Test void unchangedProcessingReceiptCanLegitimatelyReportZeroAffectedRows() {
        Fixture f = new Fixture();
        MemberCashWithdraw original = row(MemberCashWithdraw.STATUS_PROCESSING);
        original.setWechatBatchStatus("ACCEPTED"); f.stored.set(original);
        when(f.provider.transferToBalance(any(com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent.class))).thenReturn(accepted());
        when(f.mapper.updateReceiptIfProcessing(any())).thenReturn(0);
        assertSame(original, f.service.withdraw(7L, new BigDecimal("20")));
        // master 在插入后以 updateMemberCashWithdraw 写入 CW/CD 原业务号；只断言不改写生命周期状态。
                verify(f.mapper, never()).updateMemberCashWithdraw(org.mockito.ArgumentMatchers.argThat(w -> w.getStatus() != null));
        verify(f.members, never()).addBalance(anyLong(), any());
    }

    @Test void staleUnknownQueryCannotWriteTerminalReceiptOrEscalateFromOldSnapshot() {
        for (boolean frozen : new boolean[]{false, true}) {
            Fixture f = new Fixture();
            MemberCashWithdraw old = row(MemberCashWithdraw.STATUS_PROCESSING);
            old.setCreateTime(new Date(0));
            // Persisted ff2b-only original below; the stale list snapshot is not a second debit proof.
            when(f.mapper.selectPendingForQuery(1)).thenReturn(Collections.singletonList(old));
            f.stored.get().setCreateTime(new Date(0));
            f.evidence.seedSubmissionIntent(1L);
            CashWithdrawTransferIntent originalIntent = f.mapper.selectFundFacts(1L).stream()
                    .filter(fact -> CashWithdrawFundFact.SUBMIT_INTENT.equals(fact.getKind()))
                    .findFirst().orElseThrow().transferIntent();
            originalIntent.requireValid();
            assertNull(f.transferEvidence.get(1L), "This historical source remains FUND_FACT-only");
            MemberCashWithdraw current = row(frozen ? MemberCashWithdraw.STATUS_PROCESSING : MemberCashWithdraw.STATUS_SUCCESS);
            current.setFrozen(frozen ? 1 : 0); current.setWechatBatchStatus("AUTHORITATIVE");
            when(f.provider.queryTransferResult(org.mockito.ArgumentMatchers.argThat(i -> i != null && "CW1".equals(i.outBatchNo()) && "CD1".equals(i.outDetailNo())))).thenAnswer(i -> {
                assertEquals(originalIntent, i.getArgument(0), "Query the committed original identity/hash, not current config");
                Map<String,String> result = new HashMap<>(); result.put("result", "UNKNOWN");
                result.put("batchStatus", "OLD-OBSERVATION"); return result;
            });
            when(f.mapper.updateReceiptIfProcessing(any())).thenAnswer(i -> {
                // UNKNOWN has passed the current evidence lock. The concurrent writer wins
                // immediately before this receipt CAS, so this is the intended stale-CAS window.
                assertEquals(MemberCashWithdraw.STATUS_PROCESSING, f.stored.get().getStatus());
                current.setBalanceDeductId(f.stored.get().getBalanceDeductId());
                f.stored.set(current);
                return 0;
            });
            assertEquals(1, f.service.syncPendingWithdrawals(1));
            ArgumentCaptor<MemberCashWithdraw> observation = ArgumentCaptor.forClass(MemberCashWithdraw.class);
            verify(f.mapper).updateReceiptIfProcessing(observation.capture());
            assertNull(observation.getValue().getStatus(), "A stale receipt cannot write a lifecycle status");
            assertEquals("OLD-OBSERVATION", observation.getValue().getWechatBatchStatus());
            verify(f.provider).queryTransferResult(originalIntent);
            verify(f.mapper, never()).updateMemberCashWithdraw(any());
            verify(f.mapper, never()).updateStatusIfProcessing(any());
            verify(f.members, never()).addBalance(anyLong(), any());
            assertEquals("AUTHORITATIVE", f.stored.get().getWechatBatchStatus());
            assertEquals(frozen ? MemberCashWithdraw.STATUS_PROCESSING : MemberCashWithdraw.STATUS_SUCCESS,
                    f.stored.get().getStatus());
            assertEquals(frozen ? 1 : 0, f.stored.get().getFrozen());
            assertEquals(originalIntent.requestHash(), originalIntent.canonicalRequestHash());
            assertEquals(1, f.mapper.selectFundFacts(1L).stream()
                    .filter(fact -> CashWithdrawFundFact.DEBIT.equals(fact.getKind())).count());
            verify(f.mapper, never()).insertFundFact(argThat(fact -> CashWithdrawFundFact.DEBIT.equals(fact.getKind())));
        }
    }

    @Test void terminalOrFreezeCommittedDuringQueryIsRefusedBeforeAnyNewObservation() {
        for (boolean frozen : new boolean[]{false, true}) {
            Fixture f = new Fixture();
            MemberCashWithdraw original = f.stored.get(); original.setCreateTime(new Date(0));
            when(f.mapper.selectPendingForQuery(1)).thenReturn(Collections.singletonList(original));
            f.evidence.seedSubmissionIntent(1L);
            CashWithdrawTransferIntent intent = f.mapper.selectFundFacts(1L).stream()
                    .filter(fact -> CashWithdrawFundFact.SUBMIT_INTENT.equals(fact.getKind()))
                    .findFirst().orElseThrow().transferIntent();
            MemberCashWithdraw newer = row(frozen ? MemberCashWithdraw.STATUS_PROCESSING : MemberCashWithdraw.STATUS_SUCCESS);
            newer.setFrozen(frozen ? 1 : 0); newer.setWechatBatchStatus("AUTHORITATIVE");
            newer.setBalanceDeductId(original.getBalanceDeductId());
            when(f.provider.queryTransferResult(intent)).thenAnswer(call -> {
                f.stored.set(newer); // Preserve the earlier interleaving as well as the receipt-CAS window above.
                return Map.of("result", "UNKNOWN", "batchStatus", "OLD-OBSERVATION");
            });

            assertEquals(1, f.service.syncPendingWithdrawals(1));

            verify(f.provider).queryTransferResult(intent);
            verify(f.mapper, never()).insertFundFact(any());
            verify(f.mapper, never()).updateReceiptIfProcessing(any());
            verify(f.mapper, never()).updateStatusIfProcessing(any());
            verify(f.members, never()).addBalance(anyLong(), any());
            assertSame(newer, f.stored.get());
            assertEquals("AUTHORITATIVE", newer.getWechatBatchStatus());
            assertEquals(original.getBalanceDeductId(), newer.getBalanceDeductId());
        }
    }

    private static MemberCashWithdraw row(int status) {
        MemberCashWithdraw row = new MemberCashWithdraw(); row.setId(1L); row.setMemberId(7L);
        row.setOpenid("openid"); row.setDelFlag(0); row.setAmount(new BigDecimal("20.00")); row.setStatus(status); row.setOutBatchNo("CW1"); row.setOutDetailNo("CD1");
        return row;
    }
    private static Map<String,String> accepted() {
        Map<String,String> result = new HashMap<>(); result.put("ok", "true");
        result.put("batchId", "accepted-batch"); result.put("batchStatus", "ACCEPTED"); return result;
    }
    private static Map<String,String> ambiguous() {
        Map<String,String> result = new HashMap<>(); result.put("ok", "false"); result.put("ambiguous", "true"); return result;
    }
    private static final class Fixture {
        final com.chengyinhub.business.service.impl.CashWithdrawEvidenceFixture evidence;
        final CashWithdrawServiceImpl service = new CashWithdrawServiceImpl();
        final MemberCashWithdrawMapper mapper = mock(MemberCashWithdrawMapper.class);
        final IUmsMemberService members = mock(IUmsMemberService.class);
        final IWechatPayService provider = mock(IWechatPayService.class);
        final WithdrawalBalanceAdmissionService admission = mock(WithdrawalBalanceAdmissionService.class);
        final com.chengyinhub.business.service.CashWithdrawIntentTestSupport transferEvidence = new com.chengyinhub.business.service.CashWithdrawIntentTestSupport();
        final AtomicReference<MemberCashWithdraw> stored = new AtomicReference<>(row(MemberCashWithdraw.STATUS_PROCESSING));
        Fixture() {
            UmsMember member = new UmsMember(); member.setId(7L); member.setWxOpenId("openid"); member.setBalance(new BigDecimal("100"));
            when(members.selectUmsMemberById(7L)).thenReturn(member);
            MerchantReferralBountyService bounty = mock(MerchantReferralBountyService.class);
            IPlayerWithdrawalCooldownService cooldown = mock(IPlayerWithdrawalCooldownService.class);
            when(bounty.frozenAmount(7L)).thenReturn(BigDecimal.ZERO);
            when(cooldown.frozenAmount(7L)).thenReturn(BigDecimal.ZERO);
            WithdrawalFrozenAmountResolver resolver = new WithdrawalFrozenAmountResolver(bounty, cooldown);
            WithdrawalFrozenAmountResolver.Frozen frozen = resolver.resolve(7L);
            WithdrawalBalanceAdmissionService.Admission admitted = new WithdrawalBalanceAdmissionService.Admission(
                    new BigDecimal("100"), new BigDecimal("80"), frozen, true);
            when(mapper.selectById(1L)).thenAnswer(i -> stored.get());
            evidence = new com.chengyinhub.business.service.impl.CashWithdrawEvidenceFixture(mapper);
            when(admission.debitCash(eq(1L), eq(7L), any(), any(), any(), any())).thenAnswer(i -> {
                evidence.seedDebit(1L);
                com.chengyinhub.business.longrun.OWN_C04_21Test.seedSnapshot(transferEvidence, stored.get(),
                        "unit-merchant", "appid", false);
                return admitted;
            });
            // master 设计：原提现单由 CashWithdrawServiceImpl 直接插入（无独立 FailureRecoveryService）。
            when(mapper.insertMemberCashWithdraw(any())).thenAnswer(i -> { i.getArgument(0, MemberCashWithdraw.class).setId(1L); return 1; });
            org.mockito.Mockito.doAnswer(i -> stored.get()).when(mapper).selectByIdForUpdate(1L);
            org.springframework.transaction.PlatformTransactionManager transactions = mock(org.springframework.transaction.PlatformTransactionManager.class);
            when(transactions.getTransaction(any())).thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
            ReflectionTestUtils.setField(service, "transactionManager", transactions);
            com.chengyinhub.common.config.WxPayConfig config = mock(com.chengyinhub.common.config.WxPayConfig.class);
            when(config.getAppid()).thenReturn("appid");
            when(config.getMerchantId()).thenReturn("unit-merchant");
            ReflectionTestUtils.setField(service, "transferIntents", transferEvidence.mapper);
            ReflectionTestUtils.setField(service, "mapper", mapper);
            ReflectionTestUtils.setField(service, "umsMemberService", members);
            ReflectionTestUtils.setField(service, "wechatPayService", provider);
            ReflectionTestUtils.setField(service, "wxPayConfig", config);
            ReflectionTestUtils.setField(service, "balanceAdmissionService", admission);
            ReflectionTestUtils.setField(service, "frozenAmountResolver", resolver);
        }
    }
}
