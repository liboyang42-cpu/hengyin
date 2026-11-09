package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ChannelOrderVerification;
import com.chengyinhub.business.domain.ChannelVoucherAccount;
import com.chengyinhub.business.domain.dto.ChannelInboundCallbackView;
import com.chengyinhub.business.domain.dto.ChannelOrderVerificationRequest;
import com.chengyinhub.business.domain.dto.ChannelOrderVerificationView;
import com.chengyinhub.business.domain.dto.ChannelVerificationCommand;
import com.chengyinhub.business.domain.dto.ChannelVerificationOutcome;
import com.chengyinhub.business.exception.ChannelVoucherException;
import com.chengyinhub.business.mapper.ChannelOrderVerificationMapper;
import com.chengyinhub.business.mapper.ChannelVoucherAccountMapper;
import com.chengyinhub.business.service.ChannelOrderVerificationProvider;
import com.chengyinhub.business.service.IChannelVoucherService;
import com.chengyinhub.business.service.support.ChannelOrderVerificationProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证契约：默认未配置不启用；只有 provider VERIFIED 才写事实；
 * 自报金额与 provider 不一致直接拒绝；入站事件按 (provider,event) 幂等。
 */
class ChannelOrderVerificationServiceTest {

    private static final long OPERATOR = 9L;
    private static final long ACCOUNT_ID = 3L;

    private ChannelVoucherAccountMapper accounts;
    private ChannelOrderVerificationMapper verifications;
    private IChannelVoucherService vouchers;
    private ChannelOrderVerificationServiceImpl service;

    @BeforeEach
    void setUp() {
        accounts = mock(ChannelVoucherAccountMapper.class);
        verifications = mock(ChannelOrderVerificationMapper.class);
        vouchers = mock(IChannelVoucherService.class);
        service = new ChannelOrderVerificationServiceImpl(accounts, verifications,
                new ChannelOrderVerificationProviderRegistry(Collections.emptyList()), vouchers);
        when(accounts.selectById(ACCOUNT_ID)).thenReturn(activeAccount());
        when(accounts.selectByIdForUpdate(ACCOUNT_ID)).thenReturn(activeAccount());
        when(verifications.insert(any())).thenAnswer(invocation -> {
            ChannelOrderVerification row = invocation.getArgument(0);
            row.setId(11L);
            return 1;
        });
        when(verifications.markRejected(anyLong(), anyString(), any(), any())).thenReturn(1);
        when(verifications.markVerified(anyLong(), anyLong(), any(), any(), anyString(), any(),
                anyString(), any(), any(), any())).thenReturn(1);
    }

    @Test
    void noRealProviderRecordsRejectionAndNeverIssuesVoucher() {
        ChannelOrderVerificationView view = service.verifyByReference(OPERATOR, request("req-1", "ORDER-1"));

        assertEquals(ChannelOrderVerification.STATUS_REJECTED, view.getStatus());
        assertEquals("PROVIDER_NOT_CONFIGURED", view.getReasonCode());
        verify(vouchers, never()).issue(anyLong(), any());
        verify(verifications).markRejected(anyLong(), eq("PROVIDER_NOT_CONFIGURED"), any(), any());
    }

    @Test
    void verifiedProviderWritesMappedProductAndQuantity() {
        service = serviceWith(new FakeProvider(verified()));
        when(verifications.selectByAccountRequest(ACCOUNT_ID, "req-1")).thenReturn(null);
        when(verifications.selectByAccountOrderNo(ACCOUNT_ID, "ORDER-1")).thenReturn(null);

        ChannelOrderVerificationView view = service.verifyByReference(OPERATOR, request("req-1", "ORDER-1"));

        assertEquals(ChannelOrderVerification.STATUS_VERIFIED, view.getStatus());
        assertEquals(Long.valueOf(42L), view.getProductId());
        assertEquals(Integer.valueOf(2), view.getQuantity());
        verify(verifications).markVerified(anyLong(), eq(42L), eq(2), eq(new BigDecimal("99.00")),
                eq("CNY"), any(), anyString(), any(), any(), any());
    }

    @Test
    void callerDeclaredAmountCannotOverrideProviderAmount() {
        service = serviceWith(new FakeProvider(verified()));
        when(verifications.selectByAccountRequest(ACCOUNT_ID, "req-1")).thenReturn(null);
        when(verifications.selectByAccountOrderNo(ACCOUNT_ID, "ORDER-1")).thenReturn(null);
        ChannelOrderVerificationRequest request = request("req-1", "ORDER-1");
        request.setAmount(new BigDecimal("50.00"));

        ChannelOrderVerificationView view = service.verifyByReference(OPERATOR, request);

        assertEquals(ChannelOrderVerification.STATUS_REJECTED, view.getStatus());
        assertEquals("AMOUNT_MISMATCH", view.getReasonCode());
    }

    @Test
    void sameRequestWithDifferentFingerprintIsConflict() {
        ChannelOrderVerification existing = new ChannelOrderVerification();
        existing.setId(11L);
        existing.setRequestFingerprint("different");
        when(verifications.selectByAccountRequest(ACCOUNT_ID, "req-1")).thenReturn(existing);

        assertThrows(ChannelVoucherException.class,
                () -> service.verifyByReference(OPERATOR, request("req-1", "ORDER-1")));
    }

    @Test
    void duplicateInboundEventIsIdempotentAndDoesNotWriteAgain() {
        ChannelOrderVerification existing = new ChannelOrderVerification();
        existing.setId(11L);
        existing.setChannelOrderNo("ORDER-1");
        existing.setStatus(ChannelOrderVerification.STATUS_VERIFIED);
        when(verifications.selectByProviderEvent("acme", "EV-1")).thenReturn(existing);
        service = serviceWith(new FakeProvider(inboundVerified()));

        ChannelInboundCallbackView view = service.handleInbound("acme", headers(), "{\"event\":\"EV-1\"}");

        assertTrue(view.getAccepted());
        assertTrue(view.getReplayed());
        verify(verifications, never()).insert(any());
    }

    @Test
    void inboundWithUnknownChannelIsRecordedRejectedWithoutIssuing() {
        when(accounts.selectByKey("acme")).thenReturn(null);
        service = serviceWith(new FakeProvider(inboundVerified()));

        ChannelInboundCallbackView view = service.handleInbound("acme", headers(), "{\"event\":\"EV-1\"}");

        assertFalse(view.getAccepted());
        assertEquals("ACCOUNT_NOT_FOUND", view.getReasonCode());
        ArgumentCaptor<ChannelOrderVerification> captor = ArgumentCaptor.forClass(ChannelOrderVerification.class);
        verify(verifications).insert(captor.capture());
        assertEquals(ChannelOrderVerification.STATUS_REJECTED, captor.getValue().getStatus());
        verify(vouchers, never()).issue(anyLong(), any());
    }


    @Test
    void pendingRefundDoesNotRevokeThePurchaseFactNeededByRefundConsumer() {
        ChannelOrderVerification purchase = new ChannelOrderVerification();
        purchase.setId(11L);
        purchase.setStatus(ChannelOrderVerification.STATUS_VERIFIED);
        when(verifications.selectByIdForUpdate(11L)).thenReturn(purchase);
        com.chengyinhub.business.domain.dto.ChannelVoucherView pending = new com.chengyinhub.business.domain.dto.ChannelVoucherView();
        pending.setRevokePending(true);
        when(vouchers.revokeByVerification(11L, "refund", "CHANNEL")).thenReturn(pending);
        var result = service.revokeVerification(9L, 11L, "refund", "revoke-1");
        assertEquals(Integer.valueOf(ChannelOrderVerification.STATUS_VERIFIED), result.getStatus());
        assertEquals("REFUND_REQUIRED", result.getReasonCode());
        verify(verifications, never()).markRevoked(anyLong(), anyString(), any());
    }

    private ChannelOrderVerificationServiceImpl serviceWith(ChannelOrderVerificationProvider provider) {
        return new ChannelOrderVerificationServiceImpl(accounts, verifications,
                new ChannelOrderVerificationProviderRegistry(Collections.singletonList(provider)), vouchers);
    }

    private ChannelVoucherAccount activeAccount() {
        ChannelVoucherAccount account = new ChannelVoucherAccount();
        account.setId(ACCOUNT_ID);
        account.setChannelKey("acme");
        account.setContractRef("CT-2026-001");
        account.setStatus(ChannelVoucherAccount.STATUS_ACTIVE);
        account.setEffectiveFrom(new Date(System.currentTimeMillis() - 86_400_000L));
        return account;
    }

    private ChannelOrderVerificationRequest request(String requestId, String orderNo) {
        ChannelOrderVerificationRequest request = new ChannelOrderVerificationRequest();
        request.setAccountId(ACCOUNT_ID);
        request.setProviderKey("acme");
        request.setChannelOrderNo(orderNo);
        request.setRequestId(requestId);
        return request;
    }

    private ChannelVerificationOutcome verified() {
        ChannelVerificationOutcome outcome = new ChannelVerificationOutcome();
        outcome.setStatus(ChannelOrderVerification.STATUS_VERIFIED);
        outcome.setChannelKey("acme");
        outcome.setChannelOrderNo("ORDER-1");
        outcome.setAmount(new BigDecimal("99.00"));
        outcome.setCurrency("CNY");
        outcome.setProductId(42L);
        outcome.setQuantity(2);
        return outcome;
    }

    private ChannelVerificationOutcome inboundVerified() {
        ChannelVerificationOutcome outcome = verified();
        outcome.setEventId("EV-1");
        return outcome;
    }

    private Map<String, String> headers() {
        return new HashMap<>();
    }

    private static final class FakeProvider implements ChannelOrderVerificationProvider {
        private final ChannelVerificationOutcome outcome;

        private FakeProvider(ChannelVerificationOutcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public String providerKey() {
            return "acme";
        }

        @Override
        public ChannelVerificationOutcome verify(ChannelVerificationCommand command) {
            assertNotNull(command);
            return outcome;
        }
    }
}
