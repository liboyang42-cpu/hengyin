package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.BankWithdrawalPreflightRequest;
import com.chengyinhub.business.domain.dto.WithdrawalDTO;
import com.chengyinhub.business.exception.FundActionPreflightException;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.business.service.IFundActionPreflightService;
import com.chengyinhub.business.service.IUmsMemberWithdrawalService;
import com.chengyinhub.common.config.ComplianceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BankWithdrawalCommandServiceImplTest {
    private IFundActionPreflightService preflight;
    private IUmsMemberWithdrawalService withdrawals;
    private IConsentRecordService consents;
    private BankWithdrawalCommandServiceImpl service;

    @BeforeEach
    void setUp() {
        preflight = mock(IFundActionPreflightService.class);
        withdrawals = mock(IUmsMemberWithdrawalService.class);
        consents = mock(IConsentRecordService.class);
        ComplianceProperties compliance = new ComplianceProperties();
        compliance.setDocVersions(Collections.singletonMap("bank_account_collection", "v1.0"));
        service = new BankWithdrawalCommandServiceImpl(preflight, withdrawals, consents, compliance);
    }

    @Test
    void rejectsMissingCurrentSeparateConsentBeforeTouchingFunds() {
        WithdrawalDTO request = request();
        when(consents.hasCurrentAgreement(7L, "bank_account_collection", "withdrawal", "v1.0"))
                .thenReturn(false);

        FundActionPreflightException error = assertThrows(FundActionPreflightException.class,
                () -> service.submit(request, 7L, "member-7"));

        assertEquals("WITHDRAWAL_CONSENT_REQUIRED", error.getBusinessCode());
        verify(preflight, never()).consumeBankWithdrawal(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
        verify(withdrawals, never()).withdrawalRequest(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void consumesExactConfirmedIntentBeforeCreatingWithdrawalInTheSameCommand() {
        WithdrawalDTO request = request();
        when(consents.hasCurrentAgreement(7L, "bank_account_collection", "withdrawal", "v1.0"))
                .thenReturn(true);
        when(withdrawals.withdrawalRequest(request, 7L, "member-7", "v1.0")).thenReturn(91L);

        Long id = service.submit(request, 7L, "member-7");

        assertEquals(91L, id);
        ArgumentCaptor<BankWithdrawalPreflightRequest> actual =
                ArgumentCaptor.forClass(BankWithdrawalPreflightRequest.class);
        InOrder order = inOrder(preflight, withdrawals);
        order.verify(preflight).consumeBankWithdrawal(
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(55L), actual.capture());
        // F0425：建单固定本次校验通过的同意文本版本，而不是只在入口校验后丢弃。
        order.verify(withdrawals).withdrawalRequest(request, 7L, "member-7", "v1.0");
        assertEquals(request.getRequestId(), actual.getValue().getRequestId());
        assertEquals(request.getWithdrawalAmount(), actual.getValue().getAmount());
        assertEquals(request.getBankAccount(), actual.getValue().getBankAccount());
    }

    private WithdrawalDTO request() {
        WithdrawalDTO request = new WithdrawalDTO();
        request.setChallengeId(55L);
        request.setRequestId("wd-contract-1");
        request.setWithdrawalAmount(new BigDecimal("25.00"));
        request.setRealname("张三");
        request.setBankName("城瘾银行");
        request.setBankAccount("6222020000001234");
        request.setMobilephone("18800000001");
        return request;
    }
}
