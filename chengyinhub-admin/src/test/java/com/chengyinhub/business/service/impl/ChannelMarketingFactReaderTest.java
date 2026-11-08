package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ChannelOrderVerification;
import com.chengyinhub.business.domain.ChannelVoucher;
import com.chengyinhub.business.mapper.ChannelOrderVerificationMapper;
import com.chengyinhub.business.mapper.ChannelRedemptionMapper;
import com.chengyinhub.business.mapper.ChannelVoucherMapper;
import com.chengyinhub.business.service.MarketingChannelFactReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 渠道事实回读：只认库内 VERIFIED 行，金额/主体/绑定人/已绑定全部核对。 */
class ChannelMarketingFactReaderTest {

    private static final long FACT_ID = 11L;
    private static final long VOUCHER_ID = 100L;
    private static final long MEMBER = 7L;

    private ChannelOrderVerificationMapper verifications;
    private ChannelVoucherMapper vouchers;
    private ChannelRedemptionMapper redemptions;
    private ChannelMarketingFactReaderImpl reader;

    @BeforeEach
    void setUp() {
        verifications = mock(ChannelOrderVerificationMapper.class);
        vouchers = mock(ChannelVoucherMapper.class);
        redemptions = mock(ChannelRedemptionMapper.class);
        reader = new ChannelMarketingFactReaderImpl(verifications, vouchers, redemptions);
        when(verifications.selectByIdForUpdate(FACT_ID)).thenReturn(verified());
        when(vouchers.selectByVerification(FACT_ID)).thenReturn(voucher(ChannelVoucher.SUBJECT_OPEN, null));
    }

    @Test
    void returnsServerFactAndUnboundWhenNoRedemptionExists() {
        MarketingChannelFactReader.ChannelFact fact = reader.requireVerified(query(new BigDecimal("20.00")));

        assertEquals("ORDER-1", fact.externalOrderNo);
        assertEquals(new BigDecimal("20.00"), fact.amount);
        assertEquals("CNY", fact.currency);
        assertEquals("EV-1", fact.providerTransactionId);
        assertFalse(fact.bound);
    }

    @Test
    void mismatchedAmountIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> reader.requireVerified(query(new BigDecimal("21.00"))));
    }

    @Test
    void boundMemberCodeCannotBeReadByAnotherMember() {
        when(vouchers.selectByVerification(FACT_ID))
                .thenReturn(voucher(ChannelVoucher.SUBJECT_BOUND_MEMBER, 8L));

        assertThrows(IllegalStateException.class,
                () -> reader.requireVerified(query(new BigDecimal("20.00"))));
    }

    @Test
    void verifiedFactAlwaysWinsOverCallerFields() {
        ChannelOrderVerification pending = verified();
        pending.setStatus(ChannelOrderVerification.STATUS_PENDING);
        when(verifications.selectByIdForUpdate(FACT_ID)).thenReturn(pending);

        assertThrows(IllegalStateException.class,
                () -> reader.requireVerified(query(new BigDecimal("20.00"))));
    }

    private MarketingChannelFactReader.ChannelFactQuery query(BigDecimal amount) {
        return new MarketingChannelFactReader.ChannelFactQuery(FACT_ID, "acme", "ORDER-1", MEMBER,
                42L, 2, amount, "CNY");
    }

    private ChannelOrderVerification verified() {
        ChannelOrderVerification row = new ChannelOrderVerification();
        row.setId(FACT_ID);
        row.setAccountId(3L);
        row.setChannelKey("acme");
        row.setProviderKey("acme");
        row.setChannelOrderNo("ORDER-1");
        row.setEventId("EV-1");
        row.setProductId(42L);
        row.setQuantity(2);
        row.setAmount(new BigDecimal("20.00"));
        row.setCurrency("CNY");
        row.setStatus(ChannelOrderVerification.STATUS_VERIFIED);
        row.setVerifiedAt(new Date());
        return row;
    }

    private ChannelVoucher voucher(int subjectMode, Long boundMemberId) {
        ChannelVoucher voucher = new ChannelVoucher();
        voucher.setId(VOUCHER_ID);
        voucher.setVerificationId(FACT_ID);
        voucher.setSubjectMode(subjectMode);
        voucher.setBoundMemberId(boundMemberId);
        voucher.setStatus(ChannelVoucher.STATUS_ACTIVE);
        return voucher;
    }

    @Test
    void boundIsTrueOnceRedemptionExists() {
        ChannelVoucher voucher = voucher(ChannelVoucher.SUBJECT_OPEN, null);
        when(vouchers.selectByVerification(FACT_ID)).thenReturn(voucher);
        com.chengyinhub.business.domain.ChannelRedemption redemption =
                new com.chengyinhub.business.domain.ChannelRedemption();
        redemption.setStatus(com.chengyinhub.business.domain.ChannelRedemption.STATUS_ACTIVE);
        when(redemptions.selectByVoucherId(VOUCHER_ID)).thenReturn(redemption);

        assertTrue(reader.requireVerified(query(new BigDecimal("20.00"))).bound);
    }
}
