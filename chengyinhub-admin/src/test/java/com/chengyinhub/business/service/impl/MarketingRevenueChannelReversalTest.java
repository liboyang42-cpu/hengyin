package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.marketing.MarketingRevenueAllocation;
import com.chengyinhub.business.domain.marketing.MarketingRevenueAllocationLine;
import com.chengyinhub.business.exception.MarketingRevenueException;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.MarketingAttributionMapper;
import com.chengyinhub.business.mapper.MarketingRevenueAllocationLineMapper;
import com.chengyinhub.business.mapper.MarketingRevenueAllocationMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMarketingRevenueRuleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.MarketingChannelRefundFactByIdReader;
import com.chengyinhub.business.service.support.RevenueFactSource;
import com.chengyinhub.business.domain.dto.RevenueReversalResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 渠道退款冲销：事实按主键回读、不信任调用方金额、退款先到时保留可重试。
 *
 * <p>注：只证明服务层判定与写面顺序，不证明 InnoDB 并发/回滚。
 */
class MarketingRevenueChannelReversalTest {

    private static final long REGISTRATION_ID = 900L;
    private static final long CHANNEL_FACT_ID = 31L;

    private MarketingRevenueAllocationMapper allocations;
    private MarketingRevenueAllocationLineMapper lines;
    private MarketingChannelRefundFactByIdReader channelFacts;
    private MarketingRevenueAllocationServiceImpl service;

    @BeforeEach
    void setUp() {
        allocations = mock(MarketingRevenueAllocationMapper.class);
        lines = mock(MarketingRevenueAllocationLineMapper.class);
        channelFacts = mock(MarketingChannelRefundFactByIdReader.class);
        service = new MarketingRevenueAllocationServiceImpl(
                allocations, lines,
                mock(MarketingAttributionMapper.class),
                mock(CmsRegistrationMapper.class),
                mock(RefundApplicationMapper.class),
                mock(ICmsTopicService.class),
                mock(ICmsActivityService.class),
                mock(IMarketingRevenueRuleService.class),
                mock(IUmsMemberService.class),
                mock(RevenueFactSource.class));
        ReflectionTestUtils.setField(service, "channelRefundFactReader", channelFacts);
    }

    @Test
    void withoutAReaderNothingIsReversed() {
        ReflectionTestUtils.setField(service, "channelRefundFactReader", null);

        assertEquals("CHANNEL_FACT_UNVERIFIED", assertThrows(MarketingRevenueException.class,
                () -> service.reverseChannelRefund(CHANNEL_FACT_ID, REGISTRATION_ID)).getReasonCode());
        verify(allocations, never()).updateAfterReversal(anyLong(), anyLong(), any(), any());
    }

    @Test
    void unknownOrUnconfirmedFactsAreRefused() {
        when(channelFacts.requireVerifiedById(CHANNEL_FACT_ID)).thenReturn(null);
        assertEquals("CHANNEL_FACT_UNVERIFIED", assertThrows(MarketingRevenueException.class,
                () -> service.reverseChannelRefund(CHANNEL_FACT_ID, REGISTRATION_ID)).getReasonCode());

        when(channelFacts.requireVerifiedById(CHANNEL_FACT_ID))
                .thenReturn(fact(new BigDecimal("12.34"), "CNY", REGISTRATION_ID, false));
        assertEquals("CHANNEL_FACT_UNCONFIRMED", assertThrows(MarketingRevenueException.class,
                () -> service.reverseChannelRefund(CHANNEL_FACT_ID, REGISTRATION_ID)).getReasonCode());
    }

    @Test
    void factMustBelongToTheSameOrderAndCurrency() {
        when(channelFacts.requireVerifiedById(CHANNEL_FACT_ID))
                .thenReturn(fact(new BigDecimal("12.34"), "CNY", REGISTRATION_ID + 1, true));
        assertEquals("CHANNEL_FACT_ORDER_CONFLICT", assertThrows(MarketingRevenueException.class,
                () -> service.reverseChannelRefund(CHANNEL_FACT_ID, REGISTRATION_ID)).getReasonCode());

        when(channelFacts.requireVerifiedById(CHANNEL_FACT_ID))
                .thenReturn(fact(new BigDecimal("12.34"), "USD", REGISTRATION_ID, true));
        assertEquals("CHANNEL_FACT_CURRENCY_INVALID", assertThrows(MarketingRevenueException.class,
                () -> service.reverseChannelRefund(CHANNEL_FACT_ID, REGISTRATION_ID)).getReasonCode());

        when(channelFacts.requireVerifiedById(CHANNEL_FACT_ID))
                .thenReturn(fact(BigDecimal.ZERO, "CNY", REGISTRATION_ID, true));
        assertEquals("CHANNEL_FACT_AMOUNT_INVALID", assertThrows(MarketingRevenueException.class,
                () -> service.reverseChannelRefund(CHANNEL_FACT_ID, REGISTRATION_ID)).getReasonCode());
    }

    @Test
    void refundArrivingBeforeAccrualStaysRetryable() {
        when(channelFacts.requireVerifiedById(CHANNEL_FACT_ID))
                .thenReturn(fact(new BigDecimal("12.34"), "CNY", REGISTRATION_ID, true));
        when(allocations.selectByOrderForUpdate(any(), anyLong())).thenReturn(null);

        MarketingRevenueException rejection = assertThrows(MarketingRevenueException.class,
                () -> service.reverseChannelRefund(CHANNEL_FACT_ID, REGISTRATION_ID));

        assertEquals("ALLOCATION_NOT_FOUND", rejection.getReasonCode());
        // 关键：不是"永久吞掉"，而是可重试 —— 任务失败回写后会被重新领取。
        assertFalse(rejection.getMessage() == null || rejection.getMessage().isEmpty(),
                "必须带可读说明，便于对账定位");
    }

    @Test
    void verifiedFactProducesReversalLinesUnderItsOwnEventRef() {
        when(channelFacts.requireVerifiedById(CHANNEL_FACT_ID))
                .thenReturn(fact(new BigDecimal("12.34"), "CNY", REGISTRATION_ID, true));
        MarketingRevenueAllocation allocation = allocation();
        when(allocations.selectByOrderForUpdate(any(), anyLong())).thenReturn(allocation);
        when(lines.selectByEvent(allocation.getId(), "channel-refund:" + CHANNEL_FACT_ID))
                .thenReturn(new ArrayList<>());
        when(lines.selectByAllocationId(allocation.getId())).thenReturn(accrueLines());
        when(lines.batchInsert(any())).thenAnswer(call -> ((List<?>) call.getArgument(0)).size());
        when(allocations.updateAfterReversal(anyLong(), anyLong(), any(), any())).thenReturn(1);
        when(allocations.selectById(allocation.getId())).thenReturn(allocation);

        RevenueReversalResult result = service.reverseChannelRefund(CHANNEL_FACT_ID, REGISTRATION_ID);

        assertFalse(result.isReplayed());
        verify(lines).batchInsert(any());
        ArgumentCaptor<List<MarketingRevenueAllocationLine>> captured =
                ArgumentCaptor.forClass(List.class);
        verify(lines).batchInsert(captured.capture());
        assertTrue(captured.getValue().stream()
                        .allMatch(l -> ("channel-refund:" + CHANNEL_FACT_ID).equals(l.getEventRef())),
                "渠道冲销必须用自己的事件命名空间，不得复用 refund_application id");
        assertTrue(captured.getValue().stream()
                        .allMatch(l -> l.getEventAmountFen() != null && l.getEventAmountFen() == 1234L),
                "事件金额来自回读事实，不是调用方传入");
    }

    @Test
    void replayOfTheSameChannelFactDoesNotDoubleReverse() {
        MarketingRevenueAllocation allocation = allocation();
        when(channelFacts.requireVerifiedById(CHANNEL_FACT_ID))
                .thenReturn(fact(new BigDecimal("12.34"), "CNY", REGISTRATION_ID, true));
        when(allocations.selectByOrderForUpdate(any(), anyLong())).thenReturn(allocation);
        MarketingRevenueAllocationLine existing = new MarketingRevenueAllocationLine();
        existing.setEntryType(MarketingRevenueAllocationLine.ENTRY_REVERSE);
        existing.setEventRef("channel-refund:" + CHANNEL_FACT_ID);
        existing.setEventAmountFen(1234L);
        existing.setAmountFen(-1234L);
        when(lines.selectByEvent(allocation.getId(), "channel-refund:" + CHANNEL_FACT_ID))
                .thenReturn(List.of(existing));

        RevenueReversalResult result = service.reverseChannelRefund(CHANNEL_FACT_ID, REGISTRATION_ID);

        assertTrue(result.isReplayed());
        verify(lines, never()).batchInsert(any());
        verify(allocations, never()).updateAfterReversal(anyLong(), anyLong(), any(), any());
    }

    @Test
    void identifiersAreValidatedBeforeAnyLookup() {
        assertEquals("CHANNEL_FACT_REQUIRED", assertThrows(MarketingRevenueException.class,
                () -> service.reverseChannelRefund(0L, REGISTRATION_ID)).getReasonCode());
        assertEquals("ORDER_REQUIRED", assertThrows(MarketingRevenueException.class,
                () -> service.reverseChannelRefund(CHANNEL_FACT_ID, null)).getReasonCode());
        verify(channelFacts, never()).requireVerifiedById(anyLong());
    }

    // ------------------------------------------------------------------ helpers

    private MarketingChannelRefundFactByIdReader.Fact fact(BigDecimal amount, String currency,
                                                            Long registrationId, boolean confirmed) {
        return new MarketingChannelRefundFactByIdReader.Fact(CHANNEL_FACT_ID, "h".repeat(64),
                "CHREF-1", registrationId, amount, currency, confirmed);
    }

    private MarketingRevenueAllocation allocation() {
        MarketingRevenueAllocation allocation = new MarketingRevenueAllocation();
        allocation.setId(5L);
        allocation.setOrderType(Integer.valueOf(MarketingRevenueAllocation.ORDER_REGISTRATION));
        allocation.setOrderId(Long.valueOf(REGISTRATION_ID));
        allocation.setCurrency("CNY");
        allocation.setPaidFen(Long.valueOf(5000L));
        allocation.setNetFen(Long.valueOf(4000L));
        allocation.setReversedFen(Long.valueOf(0L));
        allocation.setStatus(Integer.valueOf(MarketingRevenueAllocation.STATUS_ACCRUED));
        return allocation;
    }

    private List<MarketingRevenueAllocationLine> accrueLines() {
        List<MarketingRevenueAllocationLine> rows = new ArrayList<>();
        rows.add(line(11L, "author", 2400L));
        rows.add(line(12L, "platform", 1600L));
        return rows;
    }

    private MarketingRevenueAllocationLine line(Long id, String role, long amountFen) {
        MarketingRevenueAllocationLine line = new MarketingRevenueAllocationLine();
        line.setId(id);
        line.setEntryType(MarketingRevenueAllocationLine.ENTRY_ACCRUE);
        line.setLineRole(role);
        line.setAmountFen(Long.valueOf(amountFen));
        line.setCurrency("CNY");
        return line;
    }
}
