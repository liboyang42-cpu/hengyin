package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.marketing.MarketingRevenuePostingTask;
import com.chengyinhub.business.exception.MarketingRevenueException;
import com.chengyinhub.business.mapper.MarketingRevenuePostingTaskMapper;
import com.chengyinhub.business.service.MarketingChannelRefundFactByIdReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * typed 渠道冲销入队：事实回读校验、typed 冲突、部分退款拒绝、同事实只入一条任务。
 */
class ChannelRevenueReversalServiceTest {

    private static final long FACT_ID = 31L;
    private static final long REG_A = 800L;

    private MarketingChannelRefundFactByIdReader facts;
    private MarketingRevenuePostingTaskMapper tasks;
    private ChannelRevenueReversalServiceImpl service;

    @BeforeEach
    void setUp() {
        facts = mock(MarketingChannelRefundFactByIdReader.class);
        tasks = mock(MarketingRevenuePostingTaskMapper.class);
        service = new ChannelRevenueReversalServiceImpl(facts, tasks);
        when(facts.requireVerifiedById(FACT_ID)).thenReturn(fact(true, true, Arrays.asList(REG_A, 801L)));
        when(tasks.selectByKindAndRef(any(), any())).thenAnswer(invocation -> {
            MarketingRevenuePostingTask stored = new MarketingRevenuePostingTask();
            stored.setId(77L);
            stored.setTaskKind(invocation.getArgument(0));
            stored.setRefId(invocation.getArgument(1));
            return stored;
        });
    }

    @Test
    void enqueueIsMandatoryAndUsesDedicatedChannelKind() {
        Method method;
        try {
            method = ChannelRevenueReversalServiceImpl.class.getMethod("enqueueChannelReversal",
                    String.class, String.class, String.class, long.class, String.class);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError(missing);
        }
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertNotNull(transactional, "入队必须显式声明事务传播");
        assertEquals(Propagation.MANDATORY, transactional.propagation(),
                "渠道冲销入队必须与原事务同生共死");

        Long id = service.enqueueChannelReversal("ORDER-1", "acme", "REF-1", FACT_ID, "raw-hash");

        assertEquals(Long.valueOf(77L), id);
        ArgumentCaptor<MarketingRevenuePostingTask> captor =
                ArgumentCaptor.forClass(MarketingRevenuePostingTask.class);
        verify(tasks).enqueue(captor.capture());
        assertEquals(MarketingRevenuePostingTask.KIND_CHANNEL_REVERSE, captor.getValue().getTaskKind());
        assertEquals(Long.valueOf(FACT_ID), captor.getValue().getRefId());
        assertEquals(Long.valueOf(REG_A), captor.getValue().getOrderId());
    }

    @Test
    void unverifiedOrPartialFactsNeverEnqueue() {
        when(facts.requireVerifiedById(FACT_ID)).thenReturn(null);
        assertEquals("CHANNEL_REFUND_FACT_UNVERIFIED", assertThrows(MarketingRevenueException.class,
                () -> service.enqueueChannelReversal("ORDER-1", "acme", "REF-1", FACT_ID, "raw-hash"))
                .getReasonCode());

        when(facts.requireVerifiedById(FACT_ID)).thenReturn(fact(true, false, Collections.singletonList(REG_A)));
        assertEquals("PARTIAL_CHANNEL_REFUND_UNSUPPORTED", assertThrows(MarketingRevenueException.class,
                () -> service.enqueueChannelReversal("ORDER-1", "acme", "REF-1", FACT_ID, "raw-hash"))
                .getReasonCode());
        verify(tasks, never()).enqueue(any());
    }

    @Test
    void changedPayloadOrDuplicateRefundNumberIsConflict() {
        assertEquals("CHANNEL_REFUND_ORDER_CONFLICT", assertThrows(MarketingRevenueException.class,
                () -> service.enqueueChannelReversal("ORDER-2", "acme", "REF-1", FACT_ID, "raw-hash"))
                .getReasonCode());
        assertEquals("CHANNEL_REFUND_PROVIDER_CONFLICT", assertThrows(MarketingRevenueException.class,
                () -> service.enqueueChannelReversal("ORDER-1", "other", "REF-1", FACT_ID, "raw-hash"))
                .getReasonCode());
        assertEquals("CHANNEL_REFUND_NO_CONFLICT", assertThrows(MarketingRevenueException.class,
                () -> service.enqueueChannelReversal("ORDER-1", "acme", "REF-2", FACT_ID, "raw-hash"))
                .getReasonCode());
        assertEquals("CHANNEL_REFUND_HASH_CONFLICT", assertThrows(MarketingRevenueException.class,
                () -> service.enqueueChannelReversal("ORDER-1", "acme", "REF-1", FACT_ID, "other-hash"))
                .getReasonCode());
        verify(tasks, never()).enqueue(any());
    }

    @Test
    void emptyScopeIsExplicitlyNotReversible() {
        when(facts.requireVerifiedById(FACT_ID)).thenReturn(fact(true, true, Collections.emptyList()));

        assertEquals("CHANNEL_REFUND_SCOPE_EMPTY", assertThrows(MarketingRevenueException.class,
                () -> service.enqueueChannelReversal("ORDER-1", "acme", "REF-1", FACT_ID, "raw-hash"))
                .getReasonCode());
    }

    private MarketingChannelRefundFactByIdReader.Fact fact(boolean confirmed, boolean fullRefund,
                                                           java.util.List<Long> scope) {
        return new MarketingChannelRefundFactByIdReader.Fact(FACT_ID, "raw-hash", "REF-1",
                scope.isEmpty() ? null : scope.get(0), new BigDecimal("20.00"), "CNY", confirmed,
                "ORDER-1", "acme", fullRefund, scope);
    }
}
