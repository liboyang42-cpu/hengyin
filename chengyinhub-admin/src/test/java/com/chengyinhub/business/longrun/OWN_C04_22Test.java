package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
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
                when(f.provider.transferToBalance(any(), any(), any(), any(), any(), any())).thenAnswer(i -> {
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
                verify(f.admission).debitCash(1L, 7L, new BigDecimal("20.00"));
                verify(f.provider).transferToBalance(any(), any(), any(), any(), any(), any());
            }
        }
    }

    @Test void unchangedProcessingReceiptCanLegitimatelyReportZeroAffectedRows() {
        Fixture f = new Fixture();
        MemberCashWithdraw original = row(MemberCashWithdraw.STATUS_PROCESSING);
        original.setWechatBatchStatus("ACCEPTED"); f.stored.set(original);
        when(f.provider.transferToBalance(any(), any(), any(), any(), any(), any())).thenReturn(accepted());
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
            old.setCreateTime(new Date(0)); // A stale snapshot would otherwise trigger the existing timeout path.
            when(f.mapper.selectPendingForQuery(1)).thenReturn(Collections.singletonList(old));
            MemberCashWithdraw current = row(frozen ? MemberCashWithdraw.STATUS_PROCESSING : MemberCashWithdraw.STATUS_SUCCESS);
            current.setFrozen(frozen ? 1 : 0); current.setWechatBatchStatus("AUTHORITATIVE");
            when(f.provider.queryTransferResult("CW1", "CD1")).thenAnswer(i -> {
                f.stored.set(current);
                Map<String,String> result = new HashMap<>(); result.put("result", "UNKNOWN");
                result.put("batchStatus", "OLD-OBSERVATION"); return result;
            });
            when(f.mapper.updateReceiptIfProcessing(any())).thenReturn(0);
            assertEquals(1, f.service.syncPendingWithdrawals(1));
            verify(f.mapper).updateReceiptIfProcessing(any());
            verify(f.mapper, never()).updateMemberCashWithdraw(any());
            verify(f.mapper, never()).updateStatusIfProcessing(any());
            verify(f.members, never()).addBalance(anyLong(), any());
            assertEquals("AUTHORITATIVE", f.stored.get().getWechatBatchStatus());
        }
    }

    private static MemberCashWithdraw row(int status) {
        MemberCashWithdraw row = new MemberCashWithdraw(); row.setId(1L); row.setMemberId(7L);
        row.setDelFlag(0); row.setAmount(new BigDecimal("20.00")); row.setStatus(status); row.setOutBatchNo("CW1"); row.setOutDetailNo("CD1");
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
        final CashWithdrawServiceImpl service = new CashWithdrawServiceImpl();
        final MemberCashWithdrawMapper mapper = mock(MemberCashWithdrawMapper.class);
        final IUmsMemberService members = mock(IUmsMemberService.class);
        final IWechatPayService provider = mock(IWechatPayService.class);
        final WithdrawalBalanceAdmissionService admission = mock(WithdrawalBalanceAdmissionService.class);
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
            com.chengyinhub.business.service.impl.CashWithdrawEvidenceFixture evidence =
                    new com.chengyinhub.business.service.impl.CashWithdrawEvidenceFixture(mapper);
            when(admission.debitCash(eq(1L), eq(7L), any())).thenAnswer(i -> {
                evidence.seedDebit(1L); return admitted;
            });
            // master 设计：原提现单由 CashWithdrawServiceImpl 直接插入（无独立 FailureRecoveryService）。
            when(mapper.insertMemberCashWithdraw(any())).thenAnswer(i -> { i.getArgument(0, MemberCashWithdraw.class).setId(1L); return 1; });
            org.mockito.Mockito.doAnswer(i -> stored.get()).when(mapper).selectByIdForUpdate(1L);
            org.springframework.transaction.PlatformTransactionManager transactions = mock(org.springframework.transaction.PlatformTransactionManager.class);
            when(transactions.getTransaction(any())).thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
            ReflectionTestUtils.setField(service, "transactionManager", transactions);
            com.chengyinhub.common.config.WxPayConfig config = mock(com.chengyinhub.common.config.WxPayConfig.class);
            when(config.getAppid()).thenReturn("appid");
            ReflectionTestUtils.setField(service, "mapper", mapper);
            ReflectionTestUtils.setField(service, "umsMemberService", members);
            ReflectionTestUtils.setField(service, "wechatPayService", provider);
            ReflectionTestUtils.setField(service, "wxPayConfig", config);
            ReflectionTestUtils.setField(service, "balanceAdmissionService", admission);
            ReflectionTestUtils.setField(service, "frozenAmountResolver", resolver);
        }
    }
}
