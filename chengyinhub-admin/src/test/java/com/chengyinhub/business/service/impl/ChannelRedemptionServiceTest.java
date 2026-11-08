package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ChannelOrderVerification;
import com.chengyinhub.business.domain.ChannelRedemption;
import com.chengyinhub.business.domain.ChannelVoucher;
import com.chengyinhub.business.domain.ChannelVoucherAccount;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MarketingProduct;
import com.chengyinhub.business.domain.MarketingProductItem;
import com.chengyinhub.business.domain.dto.ChannelGrantCommand;
import com.chengyinhub.business.domain.dto.ChannelGrantResult;
import com.chengyinhub.business.domain.dto.ChannelRedeemRequest;
import com.chengyinhub.business.domain.dto.ChannelRedeemResult;
import com.chengyinhub.business.exception.ChannelVoucherException;
import com.chengyinhub.business.mapper.ChannelOrderVerificationMapper;
import com.chengyinhub.business.mapper.ChannelRedemptionMapper;
import com.chengyinhub.business.mapper.ChannelVoucherAccountMapper;
import com.chengyinhub.business.mapper.ChannelVoucherMapper;
import com.chengyinhub.business.mapper.MarketingProductMapper;
import com.chengyinhub.business.service.ChannelRedemptionGrantPort;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.support.ChannelRedemptionGrantPortResolver;
import com.chengyinhub.business.service.support.ChannelVoucherCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Arrays;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 兑换契约：一码一次；把 typed CHANNEL 事实与分摊交给权威 grant 端口；
 * 绝不自建营销订单/授权；未接线、第三方内容、外币、过期、他人已用全部拒绝。
 */
class ChannelRedemptionServiceTest {

    private static final long MEMBER = 7L;
    private static final long ACCOUNT_ID = 3L;
    private static final long VERIFICATION_ID = 11L;
    private static final long PRODUCT_ID = 42L;
    private static final long VOUCHER_ID = 100L;
    private static final long REGISTRATION_ID = 800L;
    private static final long REDEMPTION_ID = 900L;

    private ChannelVoucherMapper vouchers;
    private ChannelRedemptionMapper redemptions;
    private ChannelOrderVerificationMapper verifications;
    private ChannelVoucherAccountMapper accounts;
    private MarketingProductMapper products;
    private IProductRoleService productRoles;
    private ICmsTopicService topics;
    private ChannelRedemptionGrantPortResolver grantPortResolver;
    private ChannelRedemptionGrantPort grantPort;
    private ChannelRedemptionServiceImpl service;
    private String code;
    private String publicId;

    @BeforeEach
    void setUp() {
        vouchers = mock(ChannelVoucherMapper.class);
        redemptions = mock(ChannelRedemptionMapper.class);
        verifications = mock(ChannelOrderVerificationMapper.class);
        accounts = mock(ChannelVoucherAccountMapper.class);
        products = mock(MarketingProductMapper.class);
        productRoles = mock(IProductRoleService.class);
        topics = mock(ICmsTopicService.class);
        grantPortResolver = mock(ChannelRedemptionGrantPortResolver.class);
        grantPort = mock(ChannelRedemptionGrantPort.class);
        when(grantPortResolver.isWired()).thenReturn(true);
        when(grantPortResolver.port()).thenReturn(grantPort);
        service = new ChannelRedemptionServiceImpl(vouchers, redemptions, verifications, accounts,
                products, productRoles, topics, grantPortResolver);
        publicId = ChannelVoucherCodec.newPublicId();
        code = ChannelVoucherCodec.issue(publicId);
        when(accounts.selectById(ACCOUNT_ID)).thenReturn(activeAccount());
        when(verifications.selectByIdForUpdate(VERIFICATION_ID)).thenReturn(verifiedOrder("CNY"));
        when(products.selectById(PRODUCT_ID)).thenReturn(product());
        when(products.selectItems(PRODUCT_ID)).thenReturn(Collections.singletonList(productItem(7L, "作品 A")));
        when(productRoles.isPlatformOwned(7L)).thenReturn(true);
        when(topics.selectCmsTopicById(7L)).thenReturn(topic(7L, "10.00"));
        when(grantPort.grant(any())).thenReturn(grantResult());
        when(vouchers.markRedeemed(anyLong(), any())).thenReturn(1);
        when(vouchers.markInventoryConsumed(anyLong(), any())).thenReturn(1);
        when(redemptions.insert(any())).thenAnswer(invocation -> {
            ChannelRedemption redemption = invocation.getArgument(0);
            redemption.setId(REDEMPTION_ID);
            return 1;
        });
        when(vouchers.selectById(VOUCHER_ID)).thenReturn(voucher(ChannelVoucher.STATUS_REDEEMED));
    }

    @Test
    void redeemHandsTypedChannelFactToAuthoritativeGrantPort() {
        stubRedeemableVoucher(voucher(ChannelVoucher.STATUS_ACTIVE));

        ChannelRedeemResult result = service.redeem(MEMBER, request("req-1"));

        assertNotNull(result.getReceiptNo());
        assertEquals(Long.valueOf(REGISTRATION_ID), result.getRegistrationId());
        assertEquals(Integer.valueOf(4), result.getSeatCount());
        assertFalse(result.getReplayed());
        ArgumentCaptor<ChannelGrantCommand> captor = ArgumentCaptor.forClass(ChannelGrantCommand.class);
        verify(grantPort).grant(captor.capture());
        ChannelGrantCommand command = captor.getValue();
        assertEquals(ChannelGrantCommand.SOURCE_CHANNEL, command.getSourceType());
        assertEquals(Long.valueOf(VERIFICATION_ID), command.getExternalFactId());
        assertEquals("raw-hash", command.getExternalFactHash());
        assertEquals("ORDER-1", command.getChannelOrderNo());
        assertEquals("CNY", command.getExternalCurrency());
        assertEquals(new BigDecimal("20.00"), command.getExternalAmount());
        assertEquals(Integer.valueOf(2), command.getQuantity());
        assertEquals(Integer.valueOf(4), command.getSeatCount());
        assertEquals(1, command.getTopics().size());
        assertEquals(new BigDecimal("20.00"), command.getTopics().get(0).getAllocatedAmount());
        ArgumentCaptor<ChannelRedemption> redemption = ArgumentCaptor.forClass(ChannelRedemption.class);
        verify(redemptions).insert(redemption.capture());
        assertEquals("[800]", redemption.getValue().getRegistrationIdsJson());
        assertEquals("[812]", redemption.getValue().getEntitlementIdsJson());
        verify(vouchers).markRedeemed(eq(VOUCHER_ID), any());
    }

    @Test
    void allocationAcrossTwoTopicsIsConserved() {
        ChannelOrderVerification verification = verifiedOrder("CNY");
        verification.setAmount(new BigDecimal("60.00"));
        when(verifications.selectByIdForUpdate(VERIFICATION_ID)).thenReturn(verification);
        when(products.selectItems(PRODUCT_ID)).thenReturn(Arrays.asList(
                productItem(7L, "作品 A"), productItem(8L, "作品 B")));
        when(productRoles.isPlatformOwned(8L)).thenReturn(true);
        when(topics.selectCmsTopicById(8L)).thenReturn(topic(8L, "20.00"));
        stubRedeemableVoucher(voucher(ChannelVoucher.STATUS_ACTIVE));

        service.redeem(MEMBER, request("req-1"));

        ArgumentCaptor<ChannelGrantCommand> captor = ArgumentCaptor.forClass(ChannelGrantCommand.class);
        verify(grantPort).grant(captor.capture());
        BigDecimal sum = captor.getValue().getTopics().stream()
                .map(t -> t.getAllocatedAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("60.00").compareTo(sum));
        assertEquals(0, new BigDecimal("20.00").compareTo(captor.getValue().getTopics().get(0).getAllocatedAmount()));
        assertEquals(0, new BigDecimal("40.00").compareTo(captor.getValue().getTopics().get(1).getAllocatedAmount()));
    }

    @Test
    void replaySameRequestReturnsReceiptWithoutGrantingAgain() {
        ChannelRedemption existing = activeRedemption();
        existing.setRequestFingerprint(ChannelVoucherCodec.sha256Hex(MEMBER + "|" + code));
        existing.setEntitlementIdsJson("[812]");
        when(redemptions.selectByMemberRequest(MEMBER, "req-1")).thenReturn(existing);

        ChannelRedeemResult result = service.redeem(MEMBER, request("req-1"));

        assertTrue(result.getReplayed());
        verify(grantPort, never()).grant(any());
        verify(vouchers, never()).markRedeemed(anyLong(), any());
    }

    @Test
    void sameRequestDifferentCodeIsConflict() {
        ChannelRedemption existing = activeRedemption();
        existing.setRequestFingerprint("different");
        when(redemptions.selectByMemberRequest(MEMBER, "req-1")).thenReturn(existing);

        ChannelVoucherException rejection = assertThrows(ChannelVoucherException.class,
                () -> service.redeem(MEMBER, request("req-1")));
        assertEquals("REQUEST_CONFLICT", rejection.getReasonCode());
    }

    @Test
    void tamperedCodeIsRejectedWithoutLeakingWhetherItExists() {
        when(vouchers.selectByPublicIdForUpdate(publicId)).thenReturn(voucher(ChannelVoucher.STATUS_ACTIVE));
        ChannelRedeemRequest request = request("req-1");
        request.setCode("cv1." + publicId + ".tampered-secret");

        ChannelVoucherException rejection = assertThrows(ChannelVoucherException.class,
                () -> service.redeem(MEMBER, request));
        assertEquals("INVALID_CODE", rejection.getReasonCode());
    }

    @Test
    void boundMemberCodeCannotBeUsedByAnotherAccount() {
        ChannelVoucher bound = voucher(ChannelVoucher.STATUS_ACTIVE);
        bound.setSubjectMode(ChannelVoucher.SUBJECT_BOUND_MEMBER);
        bound.setBoundMemberId(8L);
        stubRedeemableVoucher(bound);

        ChannelVoucherException rejection = assertThrows(ChannelVoucherException.class,
                () -> service.redeem(MEMBER, request("req-1")));
        assertEquals("SUBJECT_MISMATCH", rejection.getReasonCode());
    }

    @Test
    void expiredCodeIsRejected() {
        ChannelVoucher expired = voucher(ChannelVoucher.STATUS_ACTIVE);
        expired.setExpiresAt(new Date(System.currentTimeMillis() - 1_000L));
        stubRedeemableVoucher(expired);

        ChannelVoucherException rejection = assertThrows(ChannelVoucherException.class,
                () -> service.redeem(MEMBER, request("req-1")));
        assertEquals("CODE_EXPIRED", rejection.getReasonCode());
    }

    @Test
    void alreadyRedeemedByOtherAccountIsRejected() {
        stubRedeemableVoucher(voucher(ChannelVoucher.STATUS_REDEEMED));
        ChannelRedemption other = activeRedemption();
        other.setMemberId(8L);
        when(redemptions.selectByVoucherId(VOUCHER_ID)).thenReturn(other);

        ChannelVoucherException rejection = assertThrows(ChannelVoucherException.class,
                () -> service.redeem(MEMBER, request("req-1")));
        assertEquals("ALREADY_REDEEMED_BY_OTHER", rejection.getReasonCode());
    }

    @Test
    void thirdPartyContentIsRejectedEvenWithValidCode() {
        stubRedeemableVoucher(voucher(ChannelVoucher.STATUS_ACTIVE));
        when(productRoles.isPlatformOwned(7L)).thenReturn(false);

        ChannelVoucherException rejection = assertThrows(ChannelVoucherException.class,
                () -> service.redeem(MEMBER, request("req-1")));
        assertEquals("PRODUCT_UNAVAILABLE", rejection.getReasonCode());
        verify(grantPort, never()).grant(any());
    }

    @Test
    void foreignCurrencyIsRejectedWithoutImplicitFx() {
        when(verifications.selectByIdForUpdate(VERIFICATION_ID)).thenReturn(verifiedOrder("USD"));
        stubRedeemableVoucher(voucher(ChannelVoucher.STATUS_ACTIVE));

        ChannelVoucherException rejection = assertThrows(ChannelVoucherException.class,
                () -> service.redeem(MEMBER, request("req-1")));
        assertEquals("CURRENCY_UNSUPPORTED", rejection.getReasonCode());
        verify(grantPort, never()).grant(any());
    }

    @Test
    void unwiredGrantPortFailsClosedAndDoesNotMarkVoucher() {
        stubRedeemableVoucher(voucher(ChannelVoucher.STATUS_ACTIVE));
        when(grantPortResolver.isWired()).thenReturn(false);

        ChannelVoucherException rejection = assertThrows(ChannelVoucherException.class,
                () -> service.redeem(MEMBER, request("req-1")));

        assertEquals("GRANT_PORT_NOT_WIRED", rejection.getReasonCode());
        verify(vouchers, never()).markRedeemed(anyLong(), any());
        verify(redemptions, never()).insert(any());
    }

    @Test
    void localMarketingOrderTablesAreNeverTouched() {
        stubRedeemableVoucher(voucher(ChannelVoucher.STATUS_ACTIVE));
        service.redeem(MEMBER, request("req-1"));
        // 本用例守护的是“没有 marketing_product_order / marketing_entitlement 依赖”：
        // 构造器已不接收这些 mapper，能编译即证明不再直写第二套真源。
        verify(grantPort).grant(any());
    }

    @Test
    void reconciliationExposesCountsAndLocatableIssues() {
        Map<String, Object> verified = new HashMap<>();
        verified.put("verifiedOrderCount", 2);
        verified.put("verifiedAmount", new BigDecimal("198.00"));
        verified.put("pendingVerificationCount", 1);
        Map<String, Object> vouchersSummary = new HashMap<>();
        vouchersSummary.put("voucherCount", 2);
        vouchersSummary.put("redeemedCount", 1);
        vouchersSummary.put("activeVoucherCount", 1);
        vouchersSummary.put("revokedVoucherCount", 0);
        Map<String, Object> issue = new HashMap<>();
        issue.put("issueType", "VERIFIED_WITHOUT_VOUCHER");
        issue.put("channelOrderNo", "ORDER-2");
        issue.put("amount", new BigDecimal("99.00"));
        issue.put("status", 1);
        when(verifications.selectReconciliationSummary(ACCOUNT_ID, null, null)).thenReturn(verified);
        when(vouchers.selectReconciliationSummary(ACCOUNT_ID, null, null)).thenReturn(vouchersSummary);
        when(vouchers.selectReconciliationIssues(ACCOUNT_ID, null, null, 200))
                .thenReturn(Collections.singletonList(issue));

        com.chengyinhub.business.domain.dto.ChannelReconciliationView view =
                service.reconcile(ACCOUNT_ID, null, null);

        assertEquals(Integer.valueOf(2), view.getVerifiedOrderCount());
        assertEquals(Integer.valueOf(2), view.getVoucherCount());
        assertEquals(Integer.valueOf(1), view.getUnredeemedCount());
        assertEquals(1, view.getIssues().size());
        assertEquals("VERIFIED_WITHOUT_VOUCHER", view.getIssues().get(0).getIssueType());
        assertEquals("ORDER-2", view.getIssues().get(0).getChannelOrderNo());
    }

    private void stubRedeemableVoucher(ChannelVoucher voucher) {
        when(redemptions.selectByMemberRequest(MEMBER, "req-1")).thenReturn(null);
        when(vouchers.selectByPublicIdForUpdate(publicId)).thenReturn(voucher);
        when(vouchers.selectById(VOUCHER_ID)).thenReturn(voucher);
    }

    private ChannelRedeemRequest request(String requestId) {
        ChannelRedeemRequest request = new ChannelRedeemRequest();
        request.setCode(code);
        request.setRequestId(requestId);
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

    private ChannelOrderVerification verifiedOrder(String currency) {
        ChannelOrderVerification verification = new ChannelOrderVerification();
        verification.setId(VERIFICATION_ID);
        verification.setAccountId(ACCOUNT_ID);
        verification.setChannelKey("acme");
        verification.setProviderKey("acme");
        verification.setChannelOrderNo("ORDER-1");
        verification.setStatus(ChannelOrderVerification.STATUS_VERIFIED);
        verification.setProductId(PRODUCT_ID);
        verification.setQuantity(2);
        verification.setAmount(new BigDecimal("20.00"));
        verification.setCurrency(currency);
        verification.setRawHash("raw-hash");
        verification.setVerifiedAt(new Date());
        return verification;
    }

    private ChannelVoucher voucher(int status) {
        ChannelVoucher voucher = new ChannelVoucher();
        voucher.setId(VOUCHER_ID);
        voucher.setAccountId(ACCOUNT_ID);
        voucher.setVerificationId(VERIFICATION_ID);
        voucher.setPublicId(publicId);
        voucher.setCodeHash(ChannelVoucherCodec.sha256Hex(code));
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

    private MarketingProductItem productItem(long topicId, String title) {
        MarketingProductItem item = new MarketingProductItem();
        item.setProductId(PRODUCT_ID);
        item.setTopicId(topicId);
        item.setTopicTitle(title);
        item.setContentRevision(5L);
        return item;
    }

    private CmsTopic topic(long id, String selfPlayPrice) {
        CmsTopic topic = new CmsTopic();
        topic.setId(id);
        topic.setSelfPlayPrice(new BigDecimal(selfPlayPrice));
        return topic;
    }

    private ChannelGrantResult grantResult() {
        ChannelGrantResult result = new ChannelGrantResult();
        result.setPrimaryRegistrationId(REGISTRATION_ID);
        result.setPrimaryRegistrationNo("T202611080001");
        result.setRegistrationIds(Collections.singletonList(REGISTRATION_ID));
        result.setEntitlementIds(Collections.singletonList(812L));
        result.setGrantRef("grant-1");
        result.setReplayed(Boolean.FALSE);
        result.setPendingActivation(Boolean.FALSE);
        result.setGrantedAt(new Date());
        return result;
    }

    private ChannelRedemption activeRedemption() {
        ChannelRedemption redemption = new ChannelRedemption();
        redemption.setId(REDEMPTION_ID);
        redemption.setVoucherId(VOUCHER_ID);
        redemption.setMemberId(MEMBER);
        redemption.setChannelOrderNo("ORDER-1");
        redemption.setRegistrationId(REGISTRATION_ID);
        redemption.setRegistrationNo("T202611080001");
        redemption.setRegistrationIdsJson("[800]");
        redemption.setReceiptNo("CR202611080000000001");
        redemption.setRequestId("req-1");
        redemption.setStatus(ChannelRedemption.STATUS_ACTIVE);
        redemption.setRedeemedAt(new Date());
        return redemption;
    }
}
