package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.WechatVerifiedPaymentFactMapper;
import com.chengyinhub.business.service.IChannelPurchasePaymentFactReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 渠道付款事实接入：只认渠道域已验证事实，且必须证明整单分摊守恒。
 *
 * <p>守的负例：不得伪造微信事实、不得把整单金额重复计入每一行、不得默认 0 费用、
 * 未支付/已退款行不得应计。
 */
class ChannelPaymentFactSourceTest {

    private static final String REG_NO = "T202611080001";
    private static final String ORDER_NO = "CH-ORDER-1";
    private static final String HASH = "a".repeat(64);

    private WechatVerifiedPaymentFactMapper wxFacts;
    private IChannelPurchasePaymentFactReader channelFacts;
    private CmsRegistrationMapper registrations;
    private WechatRevenueFactSource source;

    @BeforeEach
    void setUp() {
        wxFacts = mock(WechatVerifiedPaymentFactMapper.class);
        channelFacts = mock(IChannelPurchasePaymentFactReader.class);
        registrations = mock(CmsRegistrationMapper.class);
        source = new WechatRevenueFactSource(wxFacts, channelFacts, registrations);
        when(wxFacts.selectSuccessesByBusiness(anyString())).thenReturn(Collections.emptyList());
    }

    @Test
    void channelFactYieldsTheRowsOwnShareNotTheWholeOrderAmount() {
        when(channelFacts.verifiedPaymentOf(ORDER_NO)).thenReturn(channelFact(6000L, "CNY", HASH));
        when(registrations.selectByParentOrderNo(ORDER_NO)).thenReturn(Arrays.asList(
                row(1L, REG_NO, "20.00"), row(2L, "T2", "40.00")));

        Optional<RevenueFactSource.PaymentFact> fact = source.verifiedPayment(row(1L, REG_NO, "20.00"));

        assertTrue(fact.isPresent(), "整单分摊守恒时，本行应按自己的分摊额入账");
        assertEquals(2000L, fact.get().amountFen(), "只能是本行 20.00，不能是整单 60.00");
        assertEquals("CNY", fact.get().currency());
        assertEquals(HASH, fact.get().factHash());
        assertTrue(fact.get().transactionId().startsWith("channel:"),
                "来源标记必须表明是渠道事实，不能伪装成微信交易号");
    }

    @Test
    void allocationSumMustEqualVerifiedOrderAmount() {
        when(channelFacts.verifiedPaymentOf(ORDER_NO)).thenReturn(channelFact(6000L, "CNY", HASH));
        // 分摊之和 60.00 vs 实测 59.99：事实不完整，不得入账。
        when(registrations.selectByParentOrderNo(ORDER_NO)).thenReturn(Arrays.asList(
                row(1L, REG_NO, "20.00"), row(2L, "T2", "39.99")));

        assertFalse(source.verifiedPayment(row(1L, REG_NO, "20.00")).isPresent());
    }

    @Test
    void unknownOrUnverifiedChannelFactIsNotAFact() {
        when(channelFacts.verifiedPaymentOf(ORDER_NO)).thenReturn(null);
        assertFalse(source.verifiedPayment(row(1L, REG_NO, "20.00")).isPresent());

        when(channelFacts.verifiedPaymentOf(ORDER_NO)).thenReturn(channelFact(0L, "CNY", HASH));
        assertFalse(source.verifiedPayment(row(1L, REG_NO, "20.00")).isPresent());
    }

    @Test
    void nonCnyOrMalformedHashIsRejected() {
        when(channelFacts.verifiedPaymentOf(ORDER_NO)).thenReturn(channelFact(2000L, "USD", HASH));
        when(registrations.selectByParentOrderNo(ORDER_NO)).thenReturn(Collections.singletonList(
                row(1L, REG_NO, "20.00")));
        assertFalse(source.verifiedPayment(row(1L, REG_NO, "20.00")).isPresent(),
                "币种不是 CNY 时收入分配口径不一致，不得入账");

        when(channelFacts.verifiedPaymentOf(ORDER_NO)).thenReturn(channelFact(2000L, "CNY", "not-a-hash"));
        assertFalse(source.verifiedPayment(row(1L, REG_NO, "20.00")).isPresent(),
                "摘要不是 64 位 hex 时无法做快照幂等，视同无事实");
    }

    @Test
    void wechatTransactionIdBlocksTheChannelFallback() {
        CmsRegistration wechatRow = row(1L, REG_NO, "20.00");
        wechatRow.setTransactionId("4200001234202611080000000001");

        assertFalse(source.verifiedPayment(wechatRow).isPresent());
        // 有微信交易号的单不得拿渠道事实顶替（否则等于伪造微信事实）。
        org.mockito.Mockito.verify(channelFacts, org.mockito.Mockito.never())
                .verifiedPaymentOf(anyString());
    }

    @Test
    void unpaidOrRefundedRowIsNotAccrued() {
        CmsRegistration refunded = row(1L, REG_NO, "20.00");
        refunded.setPaymentStatus(4);
        when(channelFacts.verifiedPaymentOf(ORDER_NO)).thenReturn(channelFact(2000L, "CNY", HASH));
        when(registrations.selectByParentOrderNo(ORDER_NO)).thenReturn(Collections.singletonList(refunded));

        assertFalse(source.verifiedPayment(refunded).isPresent(), "已退款行不得再应计收入");
    }

    @Test
    void zeroShareOrMissingSiblingsIsNotAFact() {
        CmsRegistration zero = row(1L, REG_NO, "0.00");
        when(channelFacts.verifiedPaymentOf(ORDER_NO)).thenReturn(channelFact(2000L, "CNY", HASH));
        when(registrations.selectByParentOrderNo(ORDER_NO)).thenReturn(Collections.singletonList(zero));
        assertFalse(source.verifiedPayment(zero).isPresent());

        when(registrations.selectByParentOrderNo(ORDER_NO)).thenReturn(Collections.emptyList());
        assertFalse(source.verifiedPayment(row(1L, REG_NO, "20.00")).isPresent());
    }

    @Test
    void channelBranchIsClosedWhenTheReaderIsNotWired() {
        WechatRevenueFactSource wechatOnly = new WechatRevenueFactSource(wxFacts);
        CmsRegistration row = row(1L, REG_NO, "20.00");
        assertFalse(wechatOnly.verifiedPayment(row).isPresent(),
                "未装配渠道 reader 时只走微信事实，不可能凭空产生渠道事实");
    }

    @Test
    void wechatFactStillWinsWhenPresent() {
        Map<String, Object> wxRow = new HashMap<>();
        wxRow.put("fact_kind", "PAYMENT");
        wxRow.put("verification_outcome", "VERIFIED");
        wxRow.put("verification_version", 1);
        wxRow.put("verification_method", "SDK_QUERY_ORIGINAL_ORDER");
        wxRow.put("amount_fen", 2000L);
        wxRow.put("total_fen", 2000L);
        wxRow.put("currency", "CNY");
        wxRow.put("transaction_id", "4200001234202611080000000001");
        wxRow.put("fact_hash", HASH);
        when(wxFacts.selectSuccessesByBusiness(REG_NO)).thenReturn(List.of(wxRow));

        CmsRegistration row = row(1L, REG_NO, "20.00");
        row.setTransactionId("4200001234202611080000000001");
        Optional<RevenueFactSource.PaymentFact> fact = source.verifiedPayment(row);

        assertTrue(fact.isPresent());
        assertEquals("4200001234202611080000000001", fact.get().transactionId());
        org.mockito.Mockito.verify(channelFacts, org.mockito.Mockito.never())
                .verifiedPaymentOf(eq(ORDER_NO));
    }

    private IChannelPurchasePaymentFactReader.PaymentFact channelFact(long fen, String currency, String hash) {
        return new IChannelPurchasePaymentFactReader.PaymentFact(77L, ORDER_NO, "acme", fen, currency, hash);
    }

    private CmsRegistration row(long id, String registrationNo, String payable) {
        CmsRegistration registration = new CmsRegistration();
        registration.setId(id);
        registration.setRegistrationNo(registrationNo);
        registration.setParentOrderNo(ORDER_NO);
        registration.setPayableAmount(new BigDecimal(payable));
        registration.setPaymentStatus(2);
        return registration;
    }
}
