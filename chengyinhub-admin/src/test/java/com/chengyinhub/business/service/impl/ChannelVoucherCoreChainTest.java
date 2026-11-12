package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.exception.ChannelVoucherException;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.ChannelRedemptionGrantPortResolver;
import com.chengyinhub.business.service.support.PointsValuePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实签发→兑换→原注册writer→退款撤准入；只替换持久化边界，不替换adapter/准入服务。 */
class ChannelVoucherCoreChainTest {
    private final ChannelVoucherAccountMapper accounts = mock(ChannelVoucherAccountMapper.class);
    private final ChannelOrderVerificationMapper purchases = mock(ChannelOrderVerificationMapper.class);
    private final ChannelVoucherMapper vouchers = mock(ChannelVoucherMapper.class);
    private final ChannelRedemptionMapper receipts = mock(ChannelRedemptionMapper.class);
    private final ChannelRefundVerificationMapper refunds = mock(ChannelRefundVerificationMapper.class);
    private final MarketingProductMapper products = mock(MarketingProductMapper.class);
    private final CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
    private final CmsTopicMapper topics = mock(CmsTopicMapper.class);
    private final IProductRoleService roles = mock(IProductRoleService.class);
    private final ICmsTopicService topicService = mock(ICmsTopicService.class);
    private final IMarketingRevenuePostingService posting = mock(IMarketingRevenuePostingService.class);
    private final IMarketingChannelRevenueReversal reversal = mock(IMarketingChannelRevenueReversal.class);
    private final IPerkLedgerService perks = mock(IPerkLedgerService.class);
    private final OmsTicketMapper tickets = mock(OmsTicketMapper.class);
    private ChannelVoucher voucher;
    private ChannelRedemption receipt;
    private ChannelRefundVerification refund;
    private CmsRegistration registration;
    private ChannelVoucherServiceImpl issuance;
    private ChannelRedemptionServiceImpl redemption;
    private ChannelRefundServiceImpl refundService;

    @BeforeEach
    void wireRealCore() {
        ChannelVoucherAccount account = new ChannelVoucherAccount();
        account.setId(1L);
        account.setChannelKey("core-channel");
        account.setContractRef("reviewed-contract");
        account.setStatus(ChannelVoucherAccount.STATUS_ACTIVE);
        account.setEffectiveFrom(new Date(System.currentTimeMillis() - 86_400_000L));
        when(accounts.selectById(1L)).thenReturn(account);
        when(accounts.selectByIdForUpdate(1L)).thenReturn(account);
        when(accounts.selectByKey("core-channel")).thenReturn(account);

        // 受控fixture代表已验真外单；没有本地造支付成功的生产入口。
        ChannelOrderVerification purchase = new ChannelOrderVerification();
        purchase.setId(2L);
        purchase.setAccountId(1L);
        purchase.setChannelKey("core-channel");
        purchase.setProviderKey("core-provider");
        purchase.setChannelOrderNo("CORE-ORDER");
        purchase.setProductId(3L);
        purchase.setQuantity(1);
        purchase.setAmount(new BigDecimal("10.00"));
        purchase.setCurrency("CNY");
        purchase.setRawHash("a".repeat(64));
        purchase.setStatus(ChannelOrderVerification.STATUS_VERIFIED);
        when(purchases.selectById(2L)).thenReturn(purchase);
        when(purchases.selectByIdForUpdate(2L)).thenReturn(purchase);
        when(purchases.selectByAccountOrderNo(1L, "CORE-ORDER")).thenReturn(purchase);
        when(purchases.selectVerifiedByChannelOrderNo("CORE-ORDER")).thenReturn(List.of(purchase));

        MarketingProduct product = new MarketingProduct();
        product.setId(3L);
        product.setRevision(1L);
        product.setCashPrice(new BigDecimal("10.00"));
        product.setSeatCount(1);
        product.setValidityKind(MarketingProduct.VALIDITY_NONE);
        product.setTermsVersion("original-terms");
        product.setStatus(MarketingProduct.STATUS_ON_SALE);
        when(products.selectById(3L)).thenReturn(product);
        MarketingProductItem item = new MarketingProductItem();
        item.setTopicId(4L);
        item.setProductId(3L);
        item.setContentRevision(1L);
        when(products.selectItems(3L)).thenReturn(List.of(item));
        CmsTopic topic = new CmsTopic();
        topic.setId(4L);
        topic.setName("core topic");
        topic.setSelfPlayPrice(new BigDecimal("10.00"));
        topic.setFeePlatformRate(new BigDecimal("0.10"));
        topic.setStartDate(new Date(System.currentTimeMillis() + 86_400_000L));
        topic.setEndDate(new Date(System.currentTimeMillis() + 172_800_000L));
        when(topics.selectCmsTopicByIdForUpdate(4L)).thenReturn(topic);
        when(topicService.selectCmsTopicById(4L)).thenReturn(topic);
        when(roles.isPlatformOwned(4L)).thenReturn(true);

        when(vouchers.insert(any())).thenAnswer(call -> { voucher = call.getArgument(0); voucher.setId(5L); return 1; });
        when(vouchers.selectByVerification(2L)).thenAnswer(call -> voucher);
        when(vouchers.selectByVerificationForUpdate(2L)).thenAnswer(call -> voucher);
        when(vouchers.selectByPublicId(anyString())).thenAnswer(call -> voucher);
        when(vouchers.selectById(5L)).thenAnswer(call -> voucher);
        when(vouchers.selectByIdForUpdate(5L)).thenAnswer(call -> voucher);
        when(vouchers.markRedeemed(eq(5L), any())).thenAnswer(call -> { voucher.setStatus(ChannelVoucher.STATUS_REDEEMED); return 1; });
        when(vouchers.markRevoked(eq(5L), any(), any())).thenAnswer(call -> { voucher.setStatus(ChannelVoucher.STATUS_REVOKED); return 1; });
        when(receipts.insert(any())).thenAnswer(call -> { receipt = call.getArgument(0); receipt.setId(6L); return 1; });
        when(receipts.selectByMemberRequest(eq(7L), any())).thenAnswer(call -> receipt);
        when(receipts.selectByVoucherId(5L)).thenAnswer(call -> receipt);
        when(receipts.selectByVoucherIdForUpdate(5L)).thenAnswer(call -> receipt);
        when(receipts.selectByChannelOrderNo("CORE-ORDER")).thenAnswer(call -> receipt);
        when(receipts.selectByVerificationForUpdate(2L)).thenAnswer(call -> receipt);
        when(receipts.markRevoked(eq(6L), any(), any(), any())).thenAnswer(call -> { receipt.setStatus(ChannelRedemption.STATUS_REVOKED); return 1; });
        when(registrations.insertCmsRegistration(any())).thenAnswer(call -> { registration = call.getArgument(0); registration.setId(8L); return 1; });
        when(registrations.selectCmsRegistrationById(8L)).thenAnswer(call -> registration);
        when(registrations.selectCmsRegistrationList(any())).thenAnswer(call -> registration == null ? List.of() : List.of(registration));
        when(registrations.claimRegistrationRefunded(eq(8L), any(), any())).thenAnswer(call -> {
            if (Integer.valueOf(3).equals(registration.getRegistrationStatus())) return 0;
            registration.setRegistrationStatus(3); return 1;
        });
        when(refunds.insert(any())).thenAnswer(call -> { refund = call.getArgument(0); refund.setId(9L); return 1; });
        when(refunds.selectById(9L)).thenAnswer(call -> refund);
        when(refunds.selectByIdForUpdate(9L)).thenAnswer(call -> refund);
        when(refunds.selectByProviderEvent(any(), any())).thenAnswer(call -> refund);
        when(refunds.markConsumed(eq(9L), any(), any())).thenAnswer(call -> { refund.setConsumed(1); return 1; });
        when(refunds.markNoRevenueToReverse(eq(9L), any(), any(), any())).thenAnswer(call -> { refund.setConsumed(1); return 1; });

        MarketingProductFulfillmentServiceImpl fulfillment = new MarketingProductFulfillmentServiceImpl();
        ReflectionTestUtils.setField(fulfillment, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(fulfillment, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(fulfillment, "productOrderMapper", mock(MarketingProductOrderMapper.class));
        ReflectionTestUtils.setField(fulfillment, "productRoleService", roles);
        ReflectionTestUtils.setField(fulfillment, "perkLedgerService", perks);
        ReflectionTestUtils.setField(fulfillment, "pointsValuePolicy", PointsValuePolicy.codeDefaults());
        ReflectionTestUtils.setField(fulfillment, "marketingRevenuePostingService", posting);
        ReflectionTestUtils.setField(fulfillment, "channelRevenueReversal", reversal);
        ReflectionTestUtils.setField(fulfillment, "channelFactReader", new ChannelMarketingFactReaderImpl(purchases, vouchers, receipts));
        ReflectionTestUtils.setField(fulfillment, "channelRefundFactReader", new ChannelMarketingRefundFactReaderImpl(refunds, purchases));
        var ports = new ChannelRedemptionGrantPortResolver(List.of(new ChannelRegistrationGrantAdapter(fulfillment, registrations)));
        issuance = new ChannelVoucherServiceImpl(accounts, purchases, vouchers, receipts, products, tickets, roles, topicService, ports);
        redemption = new ChannelRedemptionServiceImpl(vouchers, receipts, purchases, accounts, products, roles, topicService, ports);
        refundService = new ChannelRefundServiceImpl(accounts, purchases, refunds, new ChannelRefundFactStore(refunds), vouchers, receipts, tickets, fulfillment);
    }

    @Test
    void issuedCodeUsesOriginalRegistrationWriterAndRefundRevokesThatSameRow() {
        ChannelVoucherView issued = issue();
        ChannelRedeemRequest request = new ChannelRedeemRequest();
        request.setCode(issued.getCode()); request.setRequestId("redeem");
        ChannelRedeemResult first = redemption.redeem(7L, request);
        assertEquals(Long.valueOf(8L), first.getRegistrationId());
        assertEquals(Long.valueOf(7L), registration.getMemberId());
        assertEquals("CORE-ORDER", registration.getParentOrderNo());
        assertEquals(2, registration.getRegistrationStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(registration.getWechatPaymentAmount()));
        assertTrue(redemption.redeem(7L, request).getReplayed());
        verify(registrations, times(1)).insertCmsRegistration(any());
        verify(posting, times(1)).enqueueAccrual(8L);

        ChannelVoucherRevokeRequest revoke = new ChannelVoucherRevokeRequest();
        revoke.setReason("full refund"); revoke.setRequestId("revoke");
        assertTrue(issuance.revoke(1L, 5L, revoke).getRevokePending());
        assertEquals(2, registration.getRegistrationStatus());
        assertTrue(refundService.handleVerifiedRefund("core-provider", refundOutcome(), "{}").getAccepted());
        assertEquals(3, registration.getRegistrationStatus());
        assertEquals(Integer.valueOf(ChannelRedemption.STATUS_REVOKED), receipt.getStatus());
        assertEquals(Integer.valueOf(ChannelVoucher.STATUS_REVOKED), voucher.getStatus());
        assertTrue(refundService.handleVerifiedRefund("core-provider", refundOutcome(), "{}").getReplayed());
        verify(registrations, times(1)).claimRegistrationRefunded(eq(8L), any(), any());
        verify(reversal, times(1)).enqueueChannelReversal(any(), any(), any(), anyLong(), any());
    }

    @Test
    void refundFirstClosesCodeWithoutEverWritingRegistration() {
        ChannelVoucherView issued = issue();
        assertTrue(refundService.handleVerifiedRefund("core-provider", refundOutcome(), "{}").getAccepted());
        ChannelRedeemRequest request = new ChannelRedeemRequest();
        request.setCode(issued.getCode()); request.setRequestId("late");
        assertEquals("CODE_REVOKED", assertThrows(ChannelVoucherException.class,
                () -> redemption.redeem(7L, request)).getReasonCode());
        verify(registrations, never()).insertCmsRegistration(any());
    }

    private ChannelVoucherView issue() {
        ChannelVoucherIssueRequest request = new ChannelVoucherIssueRequest();
        request.setVerificationId(2L); request.setRequestId("issue");
        return issuance.issue(1L, request);
    }

    private ChannelVerificationOutcome refundOutcome() {
        ChannelVerificationOutcome outcome = new ChannelVerificationOutcome();
        outcome.setEventKind(ChannelVerificationOutcome.EVENT_REFUND);
        outcome.setRefundConfirmed(true); outcome.setFullRefund(true);
        outcome.setEventId("REF-EVENT"); outcome.setChannelKey("core-channel");
        outcome.setChannelOrderNo("CORE-ORDER"); outcome.setChannelRefundNo("REF-ORDER");
        outcome.setAmount(new BigDecimal("10.00")); outcome.setCurrency("CNY");
        return outcome;
    }
}
