package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ChannelRedemption;
import com.chengyinhub.business.domain.ChannelRefundVerification;
import com.chengyinhub.business.mapper.ChannelRedemptionMapper;
import com.chengyinhub.business.mapper.ChannelRefundVerificationMapper;
import com.chengyinhub.business.service.MarketingChannelRefundFactByIdReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 按主键回读渠道退款事实：只认 VERIFIED、回填原注册单范围、且**不因已消费而读不到**。
 */
class ChannelMarketingRefundFactByIdReaderTest {

    private static final long FACT_ID = 21L;
    private static final long REG_A = 800L;
    private static final long REG_B = 801L;

    private ChannelRefundVerificationMapper refunds;
    private ChannelRedemptionMapper redemptions;
    private ChannelMarketingRefundFactByIdReaderImpl reader;

    @BeforeEach
    void setUp() {
        refunds = mock(ChannelRefundVerificationMapper.class);
        redemptions = mock(ChannelRedemptionMapper.class);
        reader = new ChannelMarketingRefundFactByIdReaderImpl(refunds, redemptions);
    }

    @Test
    void verifiedFactCarriesScopeAndPurchaseAssociation() {
        when(refunds.selectById(FACT_ID)).thenReturn(fact(1, 1));
        when(redemptions.selectByChannelOrderNo("ORDER-1")).thenReturn(redemption("[800,801]", null));

        MarketingChannelRefundFactByIdReader.Fact result = reader.requireVerifiedById(FACT_ID);

        assertEquals("ORDER-1", result.parentOrderNo);
        assertEquals("acme", result.providerKey);
        assertEquals("REF-1", result.channelRefundNo);
        assertEquals(Long.valueOf(REG_A), result.registrationId);
        assertEquals(2, result.registrationIds.size());
        assertTrue(result.fullRefund);
        assertTrue(result.confirmed);
        assertEquals(0, new BigDecimal("20.00").compareTo(result.amount));
    }

    @Test
    void consumedFactIsStillReadableForLaterQueueRetry() {
        // consumed 标记只表示"撤销+入队已走过一次"，不代表不能再被消费侧复核。
        when(refunds.selectById(FACT_ID)).thenReturn(fact(1, 1));
        when(redemptions.selectByChannelOrderNo("ORDER-1")).thenReturn(redemption("[800]", null));

        assertTrue(reader.requireVerifiedById(FACT_ID).confirmed);
    }

    @Test
    void unverifiedOrPartialRefundsAreNotFacts() {
        when(refunds.selectById(FACT_ID)).thenReturn(fact(0, 1));
        assertNull(reader.requireVerifiedById(FACT_ID));

        when(refunds.selectById(FACT_ID)).thenReturn(fact(2, 0));
        assertNull(reader.requireVerifiedById(FACT_ID));
    }

    @Test
    void snapshotFallbackUsesPrimaryRegistrationAndDamagedSnapshotIsExplicit() {
        when(refunds.selectById(FACT_ID)).thenReturn(fact(1, 1));
        when(redemptions.selectByChannelOrderNo("ORDER-1")).thenReturn(redemption(null, REG_A));
        assertEquals(1, reader.requireVerifiedById(FACT_ID).registrationIds.size());

        when(redemptions.selectByChannelOrderNo("ORDER-1")).thenReturn(redemption("[800,not-a-number]", null));
        assertTrue(reader.requireVerifiedById(FACT_ID).registrationIds.isEmpty());
    }

    @Test
    void missingFactReadsAsNull() {
        assertNull(reader.requireVerifiedById(FACT_ID));
        assertFalse(FACT_ID < 0);
    }

    private ChannelRefundVerification fact(int status, int fullRefund) {
        ChannelRefundVerification row = new ChannelRefundVerification();
        row.setId(FACT_ID);
        row.setAccountId(3L);
        row.setPurchaseVerificationId(11L);
        row.setChannelKey("acme");
        row.setProviderKey("acme");
        row.setChannelOrderNo("ORDER-1");
        row.setChannelRefundNo("REF-1");
        row.setEventId("EV-REF-1");
        row.setAmount(new BigDecimal("20.00"));
        row.setCurrency("CNY");
        row.setFullRefund(fullRefund);
        row.setStatus(status);
        row.setConsumed(1);
        row.setRawHash("raw-hash");
        return row;
    }

    private ChannelRedemption redemption(String registrationIdsJson, Long primary) {
        ChannelRedemption redemption = new ChannelRedemption();
        redemption.setId(900L);
        redemption.setChannelOrderNo("ORDER-1");
        redemption.setRegistrationIdsJson(registrationIdsJson);
        redemption.setRegistrationId(primary);
        return redemption;
    }
}
