package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ChannelOrderVerification;
import com.chengyinhub.business.domain.ChannelRedemption;
import com.chengyinhub.business.domain.ChannelRefundVerification;
import com.chengyinhub.business.domain.ChannelVoucher;
import com.chengyinhub.business.domain.ChannelVoucherAccount;
import com.chengyinhub.business.domain.dto.ChannelInboundCallbackView;
import com.chengyinhub.business.domain.dto.ChannelVerificationOutcome;
import com.chengyinhub.business.mapper.ChannelOrderVerificationMapper;
import com.chengyinhub.business.mapper.ChannelRedemptionMapper;
import com.chengyinhub.business.mapper.ChannelRefundVerificationMapper;
import com.chengyinhub.business.mapper.ChannelVoucherAccountMapper;
import com.chengyinhub.business.mapper.ChannelVoucherMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.ChannelRevocationOutcome;
import com.chengyinhub.business.service.IMarketingProductFulfillmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 渠道退款：只接受 provider 已确认事实；全额才消费 M2 撤准入；部分退款不擅自撤权。 */
class ChannelRefundServiceTest {

    private static final long ACCOUNT_ID = 3L;
    private static final long PURCHASE_ID = 11L;
    private static final long REFUND_ID = 21L;
    private static final long VOUCHER_ID = 100L;

    private ChannelVoucherAccountMapper accounts;
    private ChannelOrderVerificationMapper purchases;
    private ChannelRefundVerificationMapper refunds;
    private ChannelRefundFactStore store;
    private ChannelVoucherMapper vouchers;
    private ChannelRedemptionMapper redemptions;
    private OmsTicketMapper tickets;
    private IMarketingProductFulfillmentService fulfillment;
    private ChannelRefundServiceImpl service;

    @BeforeEach
    void setUp() {
        accounts = mock(ChannelVoucherAccountMapper.class);
        purchases = mock(ChannelOrderVerificationMapper.class);
        refunds = mock(ChannelRefundVerificationMapper.class);
        store = mock(ChannelRefundFactStore.class);
        vouchers = mock(ChannelVoucherMapper.class);
        redemptions = mock(ChannelRedemptionMapper.class);
        tickets = mock(OmsTicketMapper.class);
        fulfillment = mock(IMarketingProductFulfillmentService.class);
        service = new ChannelRefundServiceImpl(accounts, purchases, refunds, store,
                vouchers, redemptions, tickets, fulfillment);
        when(accounts.selectByKey("acme")).thenReturn(activeAccount());
        when(purchases.selectByAccountOrderNo(ACCOUNT_ID, "ORDER-1")).thenReturn(purchase());
        when(purchases.selectByIdForUpdate(PURCHASE_ID)).thenReturn(purchase());
        when(vouchers.selectByVerificationForUpdate(PURCHASE_ID)).thenAnswer(inv -> vouchers.selectById(VOUCHER_ID));
        when(vouchers.selectByIdForUpdate(VOUCHER_ID)).thenAnswer(inv -> vouchers.selectById(VOUCHER_ID));
        when(refunds.insert(any())).thenReturn(1);
    }

    @Test
    void fullVerifiedRefundConsumesM2AndMarksLocalReceiptRevoked() {
        when(store.store(any())).thenReturn(refund(ChannelRefundVerification.STATUS_VERIFIED, 1));
        ChannelRevocationOutcome consumed = new ChannelRevocationOutcome();
        consumed.replayed = false;
        consumed.reasonCode = "REVOKED";
        when(fulfillment.consumeVerifiedChannelRefund(any())).thenReturn(consumed);
        when(redemptions.selectByVerificationForUpdate(PURCHASE_ID)).thenReturn(activeRedemption());
        when(vouchers.selectById(VOUCHER_ID)).thenReturn(redeemedVoucher());

        ChannelInboundCallbackView view = service.handleVerifiedRefund("acme", refundOutcome(), "{}");

        assertTrue(view.getAccepted());
        assertEquals("REVOKED", view.getReasonCode());
        verify(fulfillment).consumeVerifiedChannelRefund(any());
        verify(store).markConsumed(REFUND_ID);
        verify(redemptions).markRevoked(anyLong(), any(), any(), any());
        verify(vouchers).markRevoked(anyLong(), any(), any());
    }

    @Test
    void partialRefundIsRecordedButNeverRevokesAccess() {
        when(store.store(any())).thenReturn(refund(ChannelRefundVerification.STATUS_REJECTED, 0));
        ChannelVerificationOutcome partial = refundOutcome();
        partial.setAmount(new BigDecimal("10.00"));
        partial.setFullRefund(Boolean.FALSE);

        ChannelInboundCallbackView view = service.handleVerifiedRefund("acme", partial, "{}");

        assertFalse(view.getAccepted());
        assertEquals("PARTIAL_REFUND_UNSUPPORTED", view.getReasonCode());
        verify(fulfillment, never()).consumeVerifiedChannelRefund(any());
        verify(redemptions, never()).markRevoked(anyLong(), any(), any(), any());
    }

    @Test
    void unconfirmedRefundIsRejectedBeforeAnyFactIsStored() {
        ChannelVerificationOutcome pending = refundOutcome();
        pending.setRefundConfirmed(Boolean.FALSE);

        ChannelInboundCallbackView view = service.handleVerifiedRefund("acme", pending, "{}");

        assertFalse(view.getAccepted());
        assertEquals("REFUND_NOT_CONFIRMED", view.getReasonCode());
        verify(store, never()).store(any());
    }

    @Test
    void compensationRedrivesVerifiedButUnconsumedFactAndMarksConsumed() {
        ChannelRefundVerification pending = refund(ChannelRefundVerification.STATUS_VERIFIED, 1);
        when(refunds.selectVerifiedUnconsumed(20)).thenReturn(java.util.Collections.singletonList(pending));
        when(redemptions.selectByVerificationForUpdate(PURCHASE_ID)).thenReturn(activeRedemption());
        ChannelRevocationOutcome consumed = new ChannelRevocationOutcome();
        consumed.replayed = true;
        consumed.reasonCode = "ALREADY_REVOKED";
        when(fulfillment.consumeVerifiedChannelRefund(any())).thenReturn(consumed);

        assertEquals(1, service.retryPendingConsumptions(20));
        verify(fulfillment).consumeVerifiedChannelRefund(any());
        verify(store).markConsumed(REFUND_ID);
    }

    @Test
    void compensationKeepsFactUnconsumedWhenConsumerStillFails() {
        ChannelRefundVerification pending = refund(ChannelRefundVerification.STATUS_VERIFIED, 1);
        when(refunds.selectVerifiedUnconsumed(10)).thenReturn(java.util.Collections.singletonList(pending));
        when(redemptions.selectByVerificationForUpdate(PURCHASE_ID)).thenReturn(activeRedemption());
        when(fulfillment.consumeVerifiedChannelRefund(any()))
                .thenThrow(new IllegalStateException("撤权链路暂不可用"));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> service.retryPendingConsumptions(10));
        verify(store, never()).markConsumed(anyLong());
    }

    @Test
    void compensationClosesRefundWithoutGrantedAccessExplicitly() {
        ChannelRefundVerification pending = refund(ChannelRefundVerification.STATUS_VERIFIED, 1);
        when(refunds.selectVerifiedUnconsumed(5)).thenReturn(java.util.Collections.singletonList(pending));
        when(redemptions.selectByVerificationForUpdate(PURCHASE_ID)).thenReturn(null);

        assertEquals(1, service.retryPendingConsumptions(5));
        verify(store).markNoRevenueToReverse(REFUND_ID, "NO_REVENUE_TO_REVERSE");
        verify(fulfillment, never()).consumeVerifiedChannelRefund(any());
    }


    @Test
    void refundBeforeRedemptionRevokesIssuedVoucherWithoutGranting() {
        ChannelVoucher issued = redeemedVoucher();
        issued.setStatus(ChannelVoucher.STATUS_ACTIVE);
        when(vouchers.selectById(VOUCHER_ID)).thenReturn(issued);
        when(store.store(any())).thenReturn(refund(ChannelRefundVerification.STATUS_VERIFIED, 1));
        ChannelInboundCallbackView result = service.handleVerifiedRefund("acme", refundOutcome(), "{}");
        assertTrue(result.getAccepted());
        assertEquals("NO_REVENUE_TO_REVERSE", result.getReasonCode());
        verify(vouchers).markRevoked(org.mockito.ArgumentMatchers.eq(VOUCHER_ID), org.mockito.ArgumentMatchers.eq("REFUND"), any());
        verify(fulfillment, never()).consumeVerifiedChannelRefund(any());
    }

    @Test
    void refundBeforeIssuanceClosesVerificationAndCannotMintLater() {
        when(store.store(any())).thenReturn(refund(ChannelRefundVerification.STATUS_VERIFIED, 1));
        assertTrue(service.handleVerifiedRefund("acme", refundOutcome(), "{}").getAccepted());
        verify(purchases).markRevoked(org.mockito.ArgumentMatchers.eq(PURCHASE_ID),
                org.mockito.ArgumentMatchers.eq("CHANNEL_REFUND"), any());
        verify(fulfillment, never()).consumeVerifiedChannelRefund(any());
    }

    @Test
    void successfulCompensationAlsoSynchronizesVoucherAndReceipt() {
        when(refunds.selectVerifiedUnconsumed(1)).thenReturn(java.util.List.of(refund(ChannelRefundVerification.STATUS_VERIFIED, 1)));
        when(redemptions.selectByVerificationForUpdate(PURCHASE_ID)).thenReturn(activeRedemption());
        when(vouchers.selectById(VOUCHER_ID)).thenReturn(redeemedVoucher());
        when(fulfillment.consumeVerifiedChannelRefund(any())).thenReturn(new ChannelRevocationOutcome());
        assertEquals(1, service.retryPendingConsumptions(1));
        verify(vouchers).markRevoked(anyLong(), any(), any());
        verify(redemptions).markRevoked(anyLong(), any(), any(), any());
    }

    @Test
    void nullCompensationOutcomeNeverMarksConsumed() {
        when(refunds.selectVerifiedUnconsumed(1)).thenReturn(java.util.List.of(refund(ChannelRefundVerification.STATUS_VERIFIED, 1)));
        when(redemptions.selectByVerificationForUpdate(PURCHASE_ID)).thenReturn(activeRedemption());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> service.retryPendingConsumptions(1));
        verify(store, never()).markConsumed(anyLong());
        verify(vouchers, never()).markRevoked(anyLong(), any(), any());
    }

    private ChannelVerificationOutcome refundOutcome() {
        ChannelVerificationOutcome outcome = new ChannelVerificationOutcome();
        outcome.setEventKind(ChannelVerificationOutcome.EVENT_REFUND);
        outcome.setStatus(ChannelOrderVerification.STATUS_VERIFIED);
        outcome.setEventId("EV-REF-1");
        outcome.setChannelKey("acme");
        outcome.setChannelOrderNo("ORDER-1");
        outcome.setChannelRefundNo("REF-1");
        outcome.setAmount(new BigDecimal("20.00"));
        outcome.setCurrency("CNY");
        outcome.setRefundConfirmed(Boolean.TRUE);
        outcome.setFullRefund(Boolean.TRUE);
        return outcome;
    }

    private ChannelVoucherAccount activeAccount() {
        ChannelVoucherAccount account = new ChannelVoucherAccount();
        account.setId(ACCOUNT_ID);
        account.setChannelKey("acme");
        account.setContractRef("CT-001");
        account.setStatus(ChannelVoucherAccount.STATUS_ACTIVE);
        account.setEffectiveFrom(new Date(System.currentTimeMillis() - 86_400_000L));
        return account;
    }

    private ChannelOrderVerification purchase() {
        ChannelOrderVerification row = new ChannelOrderVerification();
        row.setId(PURCHASE_ID);
        row.setAccountId(ACCOUNT_ID);
        row.setChannelKey("acme");
        row.setProviderKey("acme");
        row.setChannelOrderNo("ORDER-1");
        row.setStatus(ChannelOrderVerification.STATUS_VERIFIED);
        row.setAmount(new BigDecimal("20.00"));
        row.setCurrency("CNY");
        row.setProductId(42L);
        row.setQuantity(2);
        return row;
    }

    private ChannelRefundVerification refund(int status, int fullRefund) {
        ChannelRefundVerification row = new ChannelRefundVerification();
        row.setId(REFUND_ID);
        row.setAccountId(ACCOUNT_ID);
        row.setPurchaseVerificationId(PURCHASE_ID);
        row.setChannelKey("acme");
        row.setProviderKey("acme");
        row.setChannelOrderNo("ORDER-1");
        row.setChannelRefundNo("REF-1");
        row.setEventId("EV-REF-1");
        row.setAmount(new BigDecimal("20.00"));
        row.setCurrency("CNY");
        row.setFullRefund(fullRefund);
        row.setStatus(status);
        row.setConsumed(0);
        row.setReasonCode(status == ChannelRefundVerification.STATUS_REJECTED
                ? "PARTIAL_REFUND_UNSUPPORTED" : null);
        return row;
    }

    private ChannelRedemption activeRedemption() {
        ChannelRedemption redemption = new ChannelRedemption();
        redemption.setId(900L);
        redemption.setVoucherId(VOUCHER_ID);
        redemption.setMemberId(7L);
        redemption.setChannelOrderNo("ORDER-1");
        redemption.setStatus(ChannelRedemption.STATUS_ACTIVE);
        return redemption;
    }

    private ChannelVoucher redeemedVoucher() {
        ChannelVoucher voucher = new ChannelVoucher();
        voucher.setId(VOUCHER_ID);
        voucher.setStatus(ChannelVoucher.STATUS_REDEEMED);
        voucher.setInventoryState(ChannelVoucher.INVENTORY_NONE);
        return voucher;
    }
}
