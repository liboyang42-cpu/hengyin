package com.chengyinhub.business.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.domain.WxPaymentCallbackOutbox;
import com.chengyinhub.business.mapper.WxPaymentCallbackOutboxMapper;

class WxPaymentCallbackOutboxServiceImplTest
{
    private static WxPaymentCallbackOutboxServiceImpl serviceWith(WxPaymentCallbackOutboxMapper mapper)
    {
        WxPaymentCallbackOutboxServiceImpl service = new WxPaymentCallbackOutboxServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", mapper);
        return service;
    }

    @Test
    void moneyAffectingEnqueueLandsInManualRequiredAndNeverPending()
    {
        WxPaymentCallbackOutboxMapper mapper = mock(WxPaymentCallbackOutboxMapper.class);
        when(mapper.insertIgnore(any(WxPaymentCallbackOutbox.class))).thenReturn(1);
        WxPaymentCallbackOutboxServiceImpl service = serviceWith(mapper);

        assertTrue(service.enqueue(WxPaymentCallbackOutbox.BIZ_TYPE_TRANSFER_QUERY, 9L, "BATCH-1", true));

        ArgumentCaptor<WxPaymentCallbackOutbox> captor =
                ArgumentCaptor.forClass(WxPaymentCallbackOutbox.class);
        verify(mapper).insertIgnore(captor.capture());
        assertEquals(Integer.valueOf(WxPaymentCallbackOutbox.STATE_MANUAL_REQUIRED), captor.getValue().getState());
        assertEquals(Boolean.TRUE, captor.getValue().getMoneyAffecting());
        assertEquals(Integer.valueOf(3), captor.getValue().getMaxAttempts());
    }

    @Test
    void claimForRetrySkipsMoneyAffectingRowEvenIfClaimSqlLeaksIt()
    {
        WxPaymentCallbackOutboxMapper mapper = mock(WxPaymentCallbackOutboxMapper.class);
        WxPaymentCallbackOutbox leaked = new WxPaymentCallbackOutbox();
        leaked.setId(5L);
        leaked.setMoneyAffecting(true);
        leaked.setFenceToken(0L);
        leaked.setAttemptCount(0);
        when(mapper.selectClaimableForUpdate(10)).thenReturn(new ArrayList<>(Collections.singletonList(leaked)));
        WxPaymentCallbackOutboxServiceImpl service = serviceWith(mapper);

        assertTrue(service.claimForRetry(10).isEmpty());
        verify(mapper, never()).claimById(anyLong(), anyString());
    }

    @Test
    void claimForRetryReturnsClaimedRowWithOwnerFenceAndAttemptIncremented()
    {
        WxPaymentCallbackOutboxMapper mapper = mock(WxPaymentCallbackOutboxMapper.class);
        WxPaymentCallbackOutbox candidate = new WxPaymentCallbackOutbox();
        candidate.setId(7L);
        candidate.setMoneyAffecting(false);
        candidate.setFenceToken(2L);
        candidate.setAttemptCount(1);
        when(mapper.selectClaimableForUpdate(10)).thenReturn(new ArrayList<>(Collections.singletonList(candidate)));
        when(mapper.claimById(org.mockito.ArgumentMatchers.eq(7L), anyString())).thenReturn(1);
        WxPaymentCallbackOutboxServiceImpl service = serviceWith(mapper);

        List<WxPaymentCallbackOutbox> claimed = service.claimForRetry(10);

        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);
        verify(mapper).claimById(org.mockito.ArgumentMatchers.eq(7L), owner.capture());
        assertEquals(1, claimed.size());
        assertEquals(Integer.valueOf(WxPaymentCallbackOutbox.STATE_DISPATCHING), claimed.get(0).getState());
        assertEquals(Long.valueOf(3L), claimed.get(0).getFenceToken());
        assertEquals(Integer.valueOf(2), claimed.get(0).getAttemptCount());
        assertEquals(owner.getValue(), claimed.get(0).getOwnerToken());
    }

    @Test
    void claimForRetryRejectsNonPositiveLimitWithoutQuerying()
    {
        WxPaymentCallbackOutboxMapper mapper = mock(WxPaymentCallbackOutboxMapper.class);
        WxPaymentCallbackOutboxServiceImpl service = serviceWith(mapper);

        assertTrue(service.claimForRetry(0).isEmpty());
        verify(mapper, never()).selectClaimableForUpdate(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void terminalWritesRequireRealOwnershipToken()
    {
        WxPaymentCallbackOutboxMapper mapper = mock(WxPaymentCallbackOutboxMapper.class);
        WxPaymentCallbackOutboxServiceImpl service = serviceWith(mapper);

        assertFalse(service.complete(1L, "owner", 0L));
        assertFalse(service.complete(null, "owner", 1L));
        assertFalse(service.retryOrEscalate(1L, "", 1L, "WX_TIMEOUT"));
        assertFalse(service.escalateToManual(1L, "owner", -1L, "MANUAL"));

        verify(mapper, never()).complete(anyLong(), anyString(), anyLong());
        verify(mapper, never()).retryOrEscalate(anyLong(), anyString(), anyLong(), anyString());
        verify(mapper, never()).escalateToManual(anyLong(), anyString(), anyLong(), anyString());
    }

    @Test
    void retryOrEscalateTruncatesErrorCodeToColumnWidth()
    {
        WxPaymentCallbackOutboxMapper mapper = mock(WxPaymentCallbackOutboxMapper.class);
        when(mapper.retryOrEscalate(org.mockito.ArgumentMatchers.eq(1L), anyString(),
                org.mockito.ArgumentMatchers.eq(2L), anyString())).thenReturn(1);
        WxPaymentCallbackOutboxServiceImpl service = serviceWith(mapper);

        assertTrue(service.retryOrEscalate(1L, "owner", 2L,
                "0123456789012345678901234567890123456789012345678901234567890123456789"));

        ArgumentCaptor<String> errorCode = ArgumentCaptor.forClass(String.class);
        verify(mapper).retryOrEscalate(org.mockito.ArgumentMatchers.eq(1L), anyString(),
                org.mockito.ArgumentMatchers.eq(2L), errorCode.capture());
        assertEquals(64, errorCode.getValue().length());
    }

    @Test
    void invalidEnqueueArgumentsNeverTouchDatabase()
    {
        WxPaymentCallbackOutboxMapper mapper = mock(WxPaymentCallbackOutboxMapper.class);
        WxPaymentCallbackOutboxServiceImpl service = serviceWith(mapper);

        assertFalse(service.enqueue(0, 1L, "KEY", false));
        assertFalse(service.enqueue(1, null, "KEY", false));
        assertFalse(service.enqueue(1, 0L, "KEY", false));
        assertFalse(service.enqueue(1, 1L, "  ", false));
        assertFalse(service.enqueue(1, 1L, repeat('x', 129), false));

        verify(mapper, never()).insertIgnore(any(WxPaymentCallbackOutbox.class));
    }

    private static String repeat(char value, int times)
    {
        StringBuilder out = new StringBuilder(times);
        for (int i = 0; i < times; i++) out.append(value);
        return out.toString();
    }
}
