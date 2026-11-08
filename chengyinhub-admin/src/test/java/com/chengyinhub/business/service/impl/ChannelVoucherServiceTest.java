package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ChannelOrderVerification;
import com.chengyinhub.business.domain.ChannelRedemption;
import com.chengyinhub.business.domain.ChannelVoucher;
import com.chengyinhub.business.domain.ChannelVoucherAccount;
import com.chengyinhub.business.domain.MarketingProduct;
import com.chengyinhub.business.domain.MarketingProductItem;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.dto.ChannelGrantRevocation;
import com.chengyinhub.business.domain.dto.ChannelVoucherIssueRequest;
import com.chengyinhub.business.domain.dto.ChannelVoucherRevokeRequest;
import com.chengyinhub.business.domain.dto.ChannelVoucherView;
import com.chengyinhub.business.exception.ChannelVoucherException;
import com.chengyinhub.business.mapper.ChannelOrderVerificationMapper;
import com.chengyinhub.business.mapper.ChannelRedemptionMapper;
import com.chengyinhub.business.mapper.ChannelVoucherAccountMapper;
import com.chengyinhub.business.mapper.ChannelVoucherMapper;
import com.chengyinhub.business.mapper.MarketingProductMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.ChannelRedemptionGrantPort;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.support.ChannelRedemptionGrantPortResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发行/撤销契约：商品与数量只能来自验证事实；第三方内容拒绝；
 * 一验证一码；未兑换释放库存；已兑换只经权威 grant 端口撤销；未接线则整体失败。
 */
class ChannelVoucherServiceTest {

    private static final long OPERATOR = 9L;
    private static final long ACCOUNT_ID = 3L;
    private static final long VERIFICATION_ID = 11L;
    private static final long PRODUCT_ID = 42L;
    private static final long VOUCHER_ID = 100L;

    private ChannelVoucherAccountMapper accounts;
    private ChannelOrderVerificationMapper verifications;
    private ChannelVoucherMapper vouchers;
    private ChannelRedemptionMapper redemptions;
    private MarketingProductMapper products;
    private OmsTicketMapper tickets;
    private IProductRoleService productRoles;
    private ChannelRedemptionGrantPortResolver grantPortResolver;
    private ChannelRedemptionGrantPort grantPort;
    private ChannelVoucherServiceImpl service;

    @BeforeEach
    void setUp() {
        accounts = mock(ChannelVoucherAccountMapper.class);
        verifications = mock(ChannelOrderVerificationMapper.class);
        vouchers = mock(ChannelVoucherMapper.class);
        redemptions = mock(ChannelRedemptionMapper.class);
        products = mock(MarketingProductMapper.class);
        tickets = mock(OmsTicketMapper.class);
        productRoles = mock(IProductRoleService.class);
        grantPortResolver = mock(ChannelRedemptionGrantPortResolver.class);
        grantPort = mock(ChannelRedemptionGrantPort.class);
        when(grantPortResolver.isWired()).thenReturn(true);
        when(grantPortResolver.port()).thenReturn(grantPort);
        service = new ChannelVoucherServiceImpl(accounts, verifications, vouchers, redemptions,
                products, tickets, productRoles, grantPortResolver);
        when(accounts.selectByIdForUpdate(ACCOUNT_ID)).thenReturn(activeAccount());
        when(verifications.selectByIdForUpdate(VERIFICATION_ID)).thenReturn(verifiedOrder());
        when(products.selectById(PRODUCT_ID)).thenReturn(product());
        when(products.selectItems(PRODUCT_ID)).thenReturn(Collections.singletonList(productItem()));
        when(productRoles.isPlatformOwned(7L)).thenReturn(true);
        when(vouchers.insert(any())).thenAnswer(invocation -> {
            ChannelVoucher voucher = invocation.getArgument(0);
            voucher.setId(VOUCHER_ID);
            return 1;
        });
        when(vouchers.markRevoked(anyLong(), any(), any())).thenReturn(1);
    }

    @Test
    void issueBindsProductQuantityAndSeatsAndReturnsCodeOnce() {
        ChannelVoucherView view = service.issue(OPERATOR, issueRequest("iss-1"));

        assertNotNull(view.getCode());
        assertTrue(view.getCode().startsWith("cv1."));
        assertTrue(view.getCodeAvailable());
        assertEquals(Integer.valueOf(4), view.getSeatCount());
        assertEquals(Long.valueOf(PRODUCT_ID), view.getProductId());
        ArgumentCaptor<ChannelVoucher> captor = ArgumentCaptor.forClass(ChannelVoucher.class);
        verify(vouchers).insert(captor.capture());
        assertEquals(64, captor.getValue().getCodeHash().length());
        assertNotEquals(view.getCode(), captor.getValue().getCodeHash());
    }

    @Test
    void issueRejectsThirdPartyProduct() {
        when(productRoles.isPlatformOwned(7L)).thenReturn(false);

        ChannelVoucherException rejection = assertThrows(ChannelVoucherException.class,
                () -> service.issue(OPERATOR, issueRequest("iss-1")));
        assertEquals("PRODUCT_UNAVAILABLE", rejection.getReasonCode());
        verify(vouchers, never()).insert(any());
    }

    @Test
    void issueRejectsUnverifiedChannelOrder() {
        ChannelOrderVerification pending = verifiedOrder();
        pending.setStatus(ChannelOrderVerification.STATUS_PENDING);
        when(verifications.selectByIdForUpdate(VERIFICATION_ID)).thenReturn(pending);

        ChannelVoucherException rejection = assertThrows(ChannelVoucherException.class,
                () -> service.issue(OPERATOR, issueRequest("iss-1")));
        assertEquals("VERIFICATION_NOT_VERIFIED", rejection.getReasonCode());
    }

    @Test
    void issueRejectsFirstUseValidityInsteadOfFakingPermanentGrant() {
        MarketingProduct product = product();
        product.setValidityKind(MarketingProduct.VALIDITY_ACTIVATE_ON_FIRST_USE);
        when(products.selectById(PRODUCT_ID)).thenReturn(product);

        ChannelVoucherException rejection = assertThrows(ChannelVoucherException.class,
                () -> service.issue(OPERATOR, issueRequest("iss-1")));
        assertEquals("PRODUCT_VALIDITY_UNSUPPORTED", rejection.getReasonCode());
    }

    @Test
    void issueReservesExistingTicketInventory() {
        ChannelVoucherIssueRequest request = issueRequest("iss-1");
        request.setInventoryTicketId(5L);
        request.setInventoryQuantity(2);
        when(tickets.selectOmsTicketById(5L)).thenReturn(new OmsTicket());
        when(tickets.deductInventory(5L, 2L)).thenReturn(1);

        ChannelVoucherView view = service.issue(OPERATOR, request);

        assertEquals(Integer.valueOf(ChannelVoucher.INVENTORY_RESERVED), view.getInventoryState());
        verify(tickets).deductInventory(5L, 2L);
    }

    @Test
    void revokeUnredeemedVoucherReleasesInventoryWithoutCallingGrantPort() {
        ChannelVoucher voucher = voucher(ChannelVoucher.STATUS_ACTIVE);
        voucher.setInventoryTicketId(5L);
        voucher.setInventoryQuantity(2);
        voucher.setInventoryState(ChannelVoucher.INVENTORY_RESERVED);
        when(vouchers.selectByIdForUpdate(VOUCHER_ID)).thenReturn(voucher);
        when(tickets.restoreInventory(5L, 2L)).thenReturn(1);

        ChannelVoucherView view = service.revoke(OPERATOR, VOUCHER_ID, revokeRequest());

        assertEquals(ChannelVoucher.STATUS_REVOKED, view.getStatus());
        verify(grantPort, never()).revoke(any());
        verify(tickets).restoreInventory(5L, 2L);
    }

    @Test
    void revokeRedeemedVoucherDelegatesToAuthoritativeGrantPort() {
        ChannelVoucher voucher = voucher(ChannelVoucher.STATUS_REDEEMED);
        when(vouchers.selectByIdForUpdate(VOUCHER_ID)).thenReturn(voucher);
        when(redemptions.selectByVoucherIdForUpdate(VOUCHER_ID)).thenReturn(activeRedemption());
        when(redemptions.markRevoked(anyLong(), any(), any(), any())).thenReturn(1);
        com.chengyinhub.business.domain.dto.ChannelGrantResult replayed =
                new com.chengyinhub.business.domain.dto.ChannelGrantResult();
        replayed.setReplayed(Boolean.TRUE);
        when(grantPort.revoke(any())).thenReturn(replayed);

        ChannelVoucherView view = service.revoke(OPERATOR, VOUCHER_ID, revokeRequest());

        assertEquals(ChannelVoucher.STATUS_REVOKED, view.getStatus());
        ArgumentCaptor<ChannelGrantRevocation> captor = ArgumentCaptor.forClass(ChannelGrantRevocation.class);
        verify(grantPort).revoke(captor.capture());
        assertEquals(Long.valueOf(800L), captor.getValue().getPrimaryRegistrationId());
        assertEquals(2, captor.getValue().getRegistrationIds().size());
        verify(redemptions).markRevoked(eq(900L), eq("渠道取消"), any(), any());
    }

    @Test
    void revokeRedeemedVoucherFailsClosedWhenGrantPortIsNotWired() {
        ChannelVoucher voucher = voucher(ChannelVoucher.STATUS_REDEEMED);
        when(vouchers.selectByIdForUpdate(VOUCHER_ID)).thenReturn(voucher);
        when(redemptions.selectByVoucherIdForUpdate(VOUCHER_ID)).thenReturn(activeRedemption());
        when(grantPortResolver.isWired()).thenReturn(false);

        ChannelVoucherException rejection = assertThrows(ChannelVoucherException.class,
                () -> service.revoke(OPERATOR, VOUCHER_ID, revokeRequest()));

        assertEquals("GRANT_PORT_NOT_WIRED", rejection.getReasonCode());
        verify(vouchers, never()).markRevoked(anyLong(), any(), any());
        verify(redemptions, never()).markRevoked(anyLong(), any(), any(), any());
    }

    @Test
    void revocableChannelIsPendingRefundAndDoesNotRevokeAccessYet() {
        ChannelVoucher voucher = voucher(ChannelVoucher.STATUS_REDEEMED);
        when(vouchers.selectByIdForUpdate(VOUCHER_ID)).thenReturn(voucher);
        when(redemptions.selectByVoucherIdForUpdate(VOUCHER_ID)).thenReturn(activeRedemption());
        com.chengyinhub.business.domain.dto.ChannelGrantResult assessment =
                new com.chengyinhub.business.domain.dto.ChannelGrantResult();
        assessment.setRevocable(Boolean.TRUE);
        assessment.setReplayed(Boolean.FALSE);
        assessment.setReasonCode("REFUND_REQUIRED");
        assessment.setRegistrationIds(java.util.Arrays.asList(800L, 801L));
        when(grantPort.revoke(any())).thenReturn(assessment);

        ChannelVoucherView view = service.revoke(OPERATOR, VOUCHER_ID, revokeRequest());

        assertEquals(ChannelVoucher.STATUS_REDEEMED, view.getStatus());
        assertEquals(Boolean.TRUE, view.getRevokePending());
        assertEquals("REFUND_REQUIRED", view.getRevokeReasonCode());
        verify(redemptions, never()).markRevoked(anyLong(), any(), any(), any());
        verify(vouchers, never()).markRevoked(anyLong(), any(), any());
    }

    @Test
    void revokeUsedEntitlementPropagatesAndDoesNotMarkAnything() {
        ChannelVoucher voucher = voucher(ChannelVoucher.STATUS_REDEEMED);
        when(vouchers.selectByIdForUpdate(VOUCHER_ID)).thenReturn(voucher);
        when(redemptions.selectByVoucherIdForUpdate(VOUCHER_ID)).thenReturn(activeRedemption());
        doThrow(ChannelVoucherException.conflict("ENTITLEMENT_IN_USE", "已有使用记录"))
                .when(grantPort).revoke(any());

        ChannelVoucherException rejection = assertThrows(ChannelVoucherException.class,
                () -> service.revoke(OPERATOR, VOUCHER_ID, revokeRequest()));

        assertEquals("ENTITLEMENT_IN_USE", rejection.getReasonCode());
        verify(redemptions, never()).markRevoked(anyLong(), any(), any(), any());
        verify(vouchers, never()).markRevoked(anyLong(), any(), any());
    }

    private ChannelVoucherIssueRequest issueRequest(String requestId) {
        ChannelVoucherIssueRequest request = new ChannelVoucherIssueRequest();
        request.setVerificationId(VERIFICATION_ID);
        request.setRequestId(requestId);
        request.setExpiresAt(new Date(System.currentTimeMillis() + 86_400_000L));
        request.setSubjectMode(ChannelVoucher.SUBJECT_OPEN);
        return request;
    }

    private ChannelVoucherRevokeRequest revokeRequest() {
        ChannelVoucherRevokeRequest request = new ChannelVoucherRevokeRequest();
        request.setReason("渠道取消");
        request.setSource("CHANNEL");
        return request;
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

    private ChannelOrderVerification verifiedOrder() {
        ChannelOrderVerification verification = new ChannelOrderVerification();
        verification.setId(VERIFICATION_ID);
        verification.setAccountId(ACCOUNT_ID);
        verification.setChannelKey("acme");
        verification.setProviderKey("acme");
        verification.setChannelOrderNo("ORDER-1");
        verification.setStatus(ChannelOrderVerification.STATUS_VERIFIED);
        verification.setProductId(PRODUCT_ID);
        verification.setQuantity(2);
        verification.setAmount(new BigDecimal("99.00"));
        verification.setCurrency("CNY");
        verification.setVerifiedAt(new Date());
        return verification;
    }

    private MarketingProduct product() {
        MarketingProduct product = new MarketingProduct();
        product.setId(PRODUCT_ID);
        product.setProductCode("CITY-PASS");
        product.setProductName("城市通行证");
        product.setCashPrice(new BigDecimal("10.00"));
        product.setSeatCount(2);
        product.setValidityKind(MarketingProduct.VALIDITY_NONE);
        product.setStatus(MarketingProduct.STATUS_ON_SALE);
        product.setTermsVersion("v1");
        product.setRevision(3L);
        return product;
    }

    private MarketingProductItem productItem() {
        MarketingProductItem item = new MarketingProductItem();
        item.setProductId(PRODUCT_ID);
        item.setTopicId(7L);
        item.setTopicTitle("作品 A");
        item.setContentRevision(5L);
        return item;
    }

    private ChannelVoucher voucher(int status) {
        ChannelVoucher voucher = new ChannelVoucher();
        voucher.setId(VOUCHER_ID);
        voucher.setAccountId(ACCOUNT_ID);
        voucher.setVerificationId(VERIFICATION_ID);
        voucher.setPublicId("0123456789abcdef01234567");
        voucher.setCodeHash("hash");
        voucher.setProductId(PRODUCT_ID);
        voucher.setProductRevision(3L);
        voucher.setQuantity(2);
        voucher.setSeatCount(4);
        voucher.setSubjectMode(ChannelVoucher.SUBJECT_OPEN);
        voucher.setStatus(status);
        voucher.setIssuedAt(new Date());
        voucher.setExpiresAt(new Date(System.currentTimeMillis() + 86_400_000L));
        voucher.setInventoryState(ChannelVoucher.INVENTORY_NONE);
        return voucher;
    }

    private ChannelRedemption activeRedemption() {
        ChannelRedemption redemption = new ChannelRedemption();
        redemption.setId(900L);
        redemption.setVoucherId(VOUCHER_ID);
        redemption.setMemberId(7L);
        redemption.setRegistrationId(800L);
        redemption.setRegistrationIdsJson("[800,801]");
        redemption.setEntitlementIdsJson("[812]");
        redemption.setStatus(ChannelRedemption.STATUS_ACTIVE);
        return redemption;
    }
}
