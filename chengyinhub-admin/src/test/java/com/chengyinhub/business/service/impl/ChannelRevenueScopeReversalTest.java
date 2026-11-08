package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.RevenueReversalResult;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 渠道退款按「原注册单范围」冲销：多作品包逐行冲满各自净额、缺应计整笔待重试、
 * 重放不双记、三次退款的分摊尾差守恒。
 *
 * <p>注：本单测使用内存假 mapper，**不证明** InnoDB 回滚/并发；事务语义由 @Transactional 与集成侧保证。
 */
class ChannelRevenueScopeReversalTest {

    private static final long FACT_ID = 31L;
    private static final long REG_A = 800L;
    private static final long REG_B = 801L;

    private MarketingRevenueAllocationMapper allocations;
    private MarketingRevenueAllocationLineMapper lines;
    private MarketingChannelRefundFactByIdReader facts;
    private MarketingRevenueAllocationServiceImpl service;
    private final List<MarketingRevenueAllocation> allocationStore = new ArrayList<>();
    private final List<MarketingRevenueAllocationLine> lineStore = new ArrayList<>();
    private long nextLineId = 100L;

    @BeforeEach
    void setUp() {
        allocations = mock(MarketingRevenueAllocationMapper.class);
        lines = mock(MarketingRevenueAllocationLineMapper.class);
        facts = mock(MarketingChannelRefundFactByIdReader.class);
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
        ReflectionTestUtils.setField(service, "channelRefundFactReader", facts);
        wireInMemoryMappers();
    }

    @Test
    void fullChannelRefundReversesEveryRegistrationOfTheOrder() {
        seedAllocation(REG_A, 5000L, 4000L, line(REG_A, "author", 2400L), line(REG_A, "platform", 1600L));
        seedAllocation(REG_B, 3000L, 2400L, line(REG_B, "author", 1440L), line(REG_B, "platform", 960L));
        when(facts.requireVerifiedById(FACT_ID)).thenReturn(fact(true, Arrays.asList(REG_A, REG_B)));

        RevenueReversalResult result = service.reverseChannelRefundFact(FACT_ID);

        assertEquals(0, new BigDecimal("64.00").compareTo(result.getReversedAmount()),
                "两行净额 40.00 + 24.00 必须全部冲回");
        assertEquals(MarketingRevenueAllocation.STATUS_REVERSED, allocationOf(REG_A).getStatus());
        assertEquals(MarketingRevenueAllocation.STATUS_REVERSED, allocationOf(REG_B).getStatus());
        // 每行的事件额是各自实付，不被订单总额重复计一次。
        assertEquals(Long.valueOf(5000L), eventAmountOf(REG_A));
        assertEquals(Long.valueOf(3000L), eventAmountOf(REG_B));
    }

    @Test
    void missingAccrualKeepsWholeBatchRetryable() {
        seedAllocation(REG_A, 5000L, 4000L, line(REG_A, "author", 2400L), line(REG_A, "platform", 1600L));
        when(facts.requireVerifiedById(FACT_ID)).thenReturn(fact(true, Arrays.asList(REG_A, REG_B)));

        MarketingRevenueException rejection = assertThrows(MarketingRevenueException.class,
                () -> service.reverseChannelRefundFact(FACT_ID));

        assertEquals("ALLOCATION_NOT_FOUND", rejection.getReasonCode());
        // 真实事务回滚由 DB 保证（本单测不含事务，只证明判定与失败原因可区分）。
        assertTrue(rejection.getHttpStatus() >= 400);
    }

    @Test
    void replayOfSameFactDoesNotDoubleReverse() {
        seedAllocation(REG_A, 5000L, 4000L, line(REG_A, "author", 2400L), line(REG_A, "platform", 1600L));
        when(facts.requireVerifiedById(FACT_ID)).thenReturn(fact(true, Arrays.asList(REG_A)));

        service.reverseChannelRefundFact(FACT_ID);
        int linesAfterFirst = lineStore.size();
        RevenueReversalResult replay = service.reverseChannelRefundFact(FACT_ID);

        assertTrue(replay.isReplayed());
        assertEquals(linesAfterFirst, lineStore.size(), "重放不得追加分录");
        assertEquals(Long.valueOf(4000L), allocationOf(REG_A).getReversedFen());
    }

    @Test
    void partialRefundIsNotSilentlyReversed() {
        seedAllocation(REG_A, 5000L, 4000L, line(REG_A, "author", 2400L), line(REG_A, "platform", 1600L));
        when(facts.requireVerifiedById(FACT_ID)).thenReturn(fact(false, Arrays.asList(REG_A)));

        assertEquals("PARTIAL_CHANNEL_REFUND_UNSUPPORTED", assertThrows(MarketingRevenueException.class,
                () -> service.reverseChannelRefundFact(FACT_ID)).getReasonCode());
        verify(lines, never()).batchInsert(any());
    }

    @Test
    void threeWayCentSplitsStayConservedAcrossThreeRefunds() {
        // 0.03 元实付，三方分成权重 1:1:1：每次退款 0.01，三次后退满，分摊尾差不得漂移。
        MarketingRevenueAllocation allocation = seedAllocation(REG_A, 3L, 3L,
                line(REG_A, "author", 1L), line(REG_A, "platform", 1L), line(REG_A, "referral", 1L));
        long refunded = 0;
        for (long factId = 31L; factId <= 33L; factId++) {
            when(facts.requireVerifiedById(factId)).thenReturn(
                    new MarketingChannelRefundFactByIdReader.Fact(factId, "h" + factId, "REF-" + factId,
                            REG_A, new BigDecimal("0.01"), "CNY", true));
            service.reverseChannelRefund(factId, REG_A);
            refunded += 1;
            assertEquals(Long.valueOf(refunded), allocation.getReversedFen(), "每次退款只能累计 1 分");
            long lineSum = lineStore.stream()
                    .filter(l -> l.getReversalOfLineId() != null)
                    .mapToLong(l -> -l.getAmountFen())
                    .sum();
            assertEquals(allocation.getReversedFen().longValue(), lineSum,
                    "分录合计必须等于累计冲销额（尾差守恒）");
        }
        assertEquals(Long.valueOf(3L), allocation.getReversedFen());
        assertEquals(MarketingRevenueAllocation.STATUS_REVERSED, allocation.getStatus());
        assertEquals(3, lineStore.stream().filter(l -> l.getReversalOfLineId() != null).count(),
                "三次退款各产生一条反向分录，三方合计刚好退满");
    }

    // ------------------------------------------------------------------ wiring

    private void wireInMemoryMappers() {
        when(allocations.selectByOrderForUpdate(any(), anyLong())).thenAnswer(invocation -> {
            Integer orderType = invocation.getArgument(0);
            Long orderId = invocation.getArgument(1);
            return allocationStore.stream()
                    .filter(row -> orderType.equals(row.getOrderType()) && orderId.equals(row.getOrderId()))
                    .findFirst().orElse(null);
        });
        when(allocations.selectById(anyLong())).thenAnswer(invocation -> find(invocation.getArgument(0)));
        when(allocations.updateAfterReversal(anyLong(), anyLong(), any(), any())).thenAnswer(invocation -> {
            MarketingRevenueAllocation target = find(invocation.getArgument(0));
            if (target == null) return 0;
            target.setReversedFen(invocation.getArgument(1));
            target.setStatus(invocation.getArgument(2));
            return 1;
        });
        when(lines.selectByAllocationId(anyLong())).thenAnswer(invocation -> {
            long allocationId = invocation.getArgument(0);
            List<MarketingRevenueAllocationLine> rows = new ArrayList<>();
            for (MarketingRevenueAllocationLine row : lineStore) {
                if (row.getAllocationId() == null || row.getAllocationId() != allocationId) continue;
                rows.add(row);
            }
            return rows;
        });
        when(lines.selectByEvent(anyLong(), any())).thenAnswer(invocation -> {
            long allocationId = invocation.getArgument(0);
            String eventRef = invocation.getArgument(1);
            List<MarketingRevenueAllocationLine> rows = new ArrayList<>();
            for (MarketingRevenueAllocationLine row : lineStore) {
                if (row.getAllocationId() == null || row.getAllocationId() != allocationId) continue;
                if (eventRef.equals(row.getEventRef())) rows.add(row);
            }
            return rows;
        });
        when(lines.batchInsert(any())).thenAnswer(invocation -> {
            List<MarketingRevenueAllocationLine> rows = invocation.getArgument(0);
            for (MarketingRevenueAllocationLine row : rows) {
                row.setId(nextLineId++);
                lineStore.add(row);
            }
            return rows.size();
        });
    }

    private MarketingRevenueAllocation find(long allocationId) {
        for (MarketingRevenueAllocation row : allocationStore) {
            if (row.getId() != null && row.getId() == allocationId) {
                return row;
            }
        }
        return null;
    }

    private MarketingRevenueAllocation allocationOf(long registrationId) {
        for (MarketingRevenueAllocation row : allocationStore) {
            if (row.getOrderId() != null && row.getOrderId() == registrationId) {
                return row;
            }
        }
        return null;
    }

    private Long eventAmountOf(long registrationId) {
        long allocationId = allocationOf(registrationId).getId();
        for (MarketingRevenueAllocationLine row : lineStore) {
            if (row.getAllocationId() != null && row.getAllocationId() == allocationId
                    && row.getEventAmountFen() != null) {
                return row.getEventAmountFen();
            }
        }
        return null;
    }

    private MarketingRevenueAllocation seedAllocation(long registrationId, long paidFen, long netFen,
                                                      MarketingRevenueAllocationLine... accrueLines) {
        MarketingRevenueAllocation allocation = new MarketingRevenueAllocation();
        allocation.setId(registrationId == REG_A ? 5L : 6L);
        allocation.setOrderType(Integer.valueOf(MarketingRevenueAllocation.ORDER_REGISTRATION));
        allocation.setOrderId(Long.valueOf(registrationId));
        allocation.setCurrency("CNY");
        allocation.setPaidFen(Long.valueOf(paidFen));
        allocation.setNetFen(Long.valueOf(netFen));
        allocation.setReversedFen(Long.valueOf(0L));
        allocation.setStatus(Integer.valueOf(MarketingRevenueAllocation.STATUS_ACCRUED));
        allocationStore.add(allocation);
        lineStore.addAll(Arrays.asList(accrueLines));
        return allocation;
    }

    private MarketingRevenueAllocationLine line(long registrationId, String role, long amountFen) {
        MarketingRevenueAllocationLine row = new MarketingRevenueAllocationLine();
        row.setId(nextLineId++);
        row.setAllocationId(Long.valueOf(registrationId == REG_A ? 5L : 6L));
        row.setEntryType(Integer.valueOf(MarketingRevenueAllocationLine.ENTRY_ACCRUE));
        row.setLineRole(role);
        row.setAmountFen(Long.valueOf(amountFen));
        row.setCurrency("CNY");
        row.setCreateTime(new Date());
        return row;
    }

    private MarketingChannelRefundFactByIdReader.Fact fact(boolean fullRefund, List<Long> scope) {
        return new MarketingChannelRefundFactByIdReader.Fact(FACT_ID, "h31", "REF-31",
                scope.isEmpty() ? null : scope.get(0), new BigDecimal("80.00"), "CNY", true,
                "ORDER-1", "acme", fullRefund, scope);
    }
}
