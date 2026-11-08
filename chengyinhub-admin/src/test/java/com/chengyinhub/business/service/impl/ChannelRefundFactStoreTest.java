package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ChannelRefundVerification;
import com.chengyinhub.business.exception.ChannelVoucherException;
import com.chengyinhub.business.mapper.ChannelRefundVerificationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重复外部退款事件/退款单号：载荷一致才幂等，载荷变了必须冲突，绝不覆盖原始事实。
 */
class ChannelRefundFactStoreTest {

    private ChannelRefundVerificationMapper mapper;
    private ChannelRefundFactStore store;

    @BeforeEach
    void setUp() {
        mapper = mock(ChannelRefundVerificationMapper.class);
        store = new ChannelRefundFactStore(mapper);
    }

    @Test
    void identicalReplayReturnsOriginalWithoutInserting() {
        ChannelRefundVerification existing = fact("20.00", 1);
        existing.setId(9L);
        when(mapper.selectByProviderEvent("acme", "EV-1")).thenReturn(existing);

        ChannelRefundVerification stored = store.store(fact("20.00", 1));

        assertEquals(Long.valueOf(9L), stored.getId());
        verify(mapper, never()).insert(any());
    }

    @Test
    void sameEventWithChangedPayloadIsConflict() {
        when(mapper.selectByProviderEvent("acme", "EV-1")).thenReturn(fact("20.00", 1));

        ChannelVoucherException rejection = assertThrows(ChannelVoucherException.class,
                () -> store.store(fact("21.00", 1)));
        assertEquals("CHANNEL_REFUND_EVENT_CONFLICT", rejection.getReasonCode());
        verify(mapper, never()).insert(any());
    }

    @Test
    void sameRefundNumberWithDifferentFactIsConflict() {
        when(mapper.selectByProviderEvent("acme", "EV-1")).thenReturn(null);
        when(mapper.selectByAccountRefundNo(3L, "REF-1")).thenReturn(fact("20.00", 0));

        ChannelVoucherException rejection = assertThrows(ChannelVoucherException.class,
                () -> store.store(fact("20.00", 1)));
        assertEquals("CHANNEL_REFUND_NO_CONFLICT", rejection.getReasonCode());
        verify(mapper, never()).insert(any());
    }

    @Test
    void partialFlagDifferenceCountsAsChangedPayload() {
        when(mapper.selectByProviderEvent("acme", "EV-1")).thenReturn(fact("20.00", 1));

        assertEquals("CHANNEL_REFUND_EVENT_CONFLICT", assertThrows(ChannelVoucherException.class,
                () -> store.store(fact("20.00", 0))).getReasonCode());
    }

    private ChannelRefundVerification fact(String amount, int fullRefund) {
        ChannelRefundVerification row = new ChannelRefundVerification();
        row.setAccountId(3L);
        row.setChannelKey("acme");
        row.setProviderKey("acme");
        row.setChannelOrderNo("ORDER-1");
        row.setChannelRefundNo("REF-1");
        row.setEventId("EV-1");
        row.setAmount(new BigDecimal(amount));
        row.setCurrency("CNY");
        row.setFullRefund(fullRefund);
        row.setStatus(ChannelRefundVerification.STATUS_VERIFIED);
        row.setRawHash("h");
        return row;
    }
}
