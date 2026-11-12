package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CreatorIncomeDetail;
import com.chengyinhub.business.domain.CreatorProfile;
import com.chengyinhub.business.domain.vo.CreatorCenterVO;
import com.chengyinhub.business.domain.vo.CreatorRevenueLedgerVO;
import com.chengyinhub.business.mapper.CreatorIncomeDetailMapper;
import com.chengyinhub.business.mapper.CreatorMetricDailyMapper;
import com.chengyinhub.business.mapper.CreatorProfileMapper;
import com.chengyinhub.business.service.ICreatorRevenueReadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 创作者中心收益状态：读到 = AVAILABLE，读不到 = UNKNOWN 且金额为 null。
 *
 * <p>核心回归点：以前收益读取失败会静默变成空列表（等价「0 收入」）；现在必须显式未知，
 * 同时不打断中心页其它字段，也不改 {@code recentIncome} 的原语义。
 */
class CreatorCenterRevenueStateTest {

    private CreatorProfileMapper profileMapper;
    private CreatorMetricDailyMapper metricMapper;
    private CreatorIncomeDetailMapper incomeMapper;
    private ICreatorRevenueReadService revenueService;
    private CreatorCenterServiceImpl service;

    @BeforeEach
    void setUp() {
        profileMapper = mock(CreatorProfileMapper.class);
        metricMapper = mock(CreatorMetricDailyMapper.class);
        incomeMapper = mock(CreatorIncomeDetailMapper.class);
        revenueService = mock(ICreatorRevenueReadService.class);
        service = new CreatorCenterServiceImpl();
        ReflectionTestUtils.setField(service, "creatorProfileMapper", profileMapper);
        ReflectionTestUtils.setField(service, "creatorMetricDailyMapper", metricMapper);
        ReflectionTestUtils.setField(service, "creatorIncomeDetailMapper", incomeMapper);
        ReflectionTestUtils.setField(service, "creatorRevenueReadService", revenueService);

        when(profileMapper.selectByMemberId(1001L)).thenReturn(profile());
        when(incomeMapper.selectRecentIncome(9L)).thenReturn(List.of(legacyIncome()));
    }

    @Test
    void availableLedgerIsExposedWithAccrualBasis() {
        CreatorRevenueLedgerVO ledger = CreatorRevenueLedgerVO.available(new Date(), new Date(),
                List.of(new CreatorRevenueLedgerVO.Entry(1001L, new BigDecimal("100.00"),
                        new BigDecimal("-20.00"), new BigDecimal("80.00"), 2L, 1L, 7L)),
                new CreatorRevenueLedgerVO.Totals(new BigDecimal("100.00"), new BigDecimal("-20.00"),
                        new BigDecimal("80.00")),
                new CreatorRevenueLedgerVO.Totals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                new Date(), List.of("v1"));
        when(revenueService.readForMember(eq(1001L), any(), any())).thenReturn(ledger);

        CreatorCenterVO vo = service.getCenter(1001L);

        assertEquals(CreatorRevenueLedgerVO.State.AVAILABLE, vo.getRevenueState());
        assertNotNull(vo.getRevenue());
        assertEquals(new BigDecimal("80.00"),
                vo.getRevenue().getAttributed().netAmount());
        assertEquals("ACCRUAL_ORIGINAL_LEDGER", vo.getRevenue().getRevenueBasis());
        assertNull(vo.getRevenueUnavailableReason());
    }

    @Test
    void readFailureBecomesExplicitUnknownInsteadOfZeroIncome() {
        when(revenueService.readForMember(eq(1001L), any(), any()))
                .thenThrow(new IllegalStateException("原账本毛额查询返回 null"));

        CreatorCenterVO vo = service.getCenter(1001L);

        assertEquals(CreatorRevenueLedgerVO.State.UNKNOWN, vo.getRevenueState());
        assertNull(vo.getRevenue(), "未知不得展示为 0 收入");
        assertTrue(vo.getRevenueUnavailableReason().contains("原账本收益读取失败"));
        // 其它字段照常可用：不因为收益读不到而让整个中心页失败。
        assertEquals("approved", vo.getApplyStatus());
        assertEquals(1, vo.getRecentIncome().size(), "recentIncome 原语义不变");
    }

    @Test
    void missingRevenueReaderIsUnknownNotZero() {
        ReflectionTestUtils.setField(service, "creatorRevenueReadService", null);

        CreatorCenterVO vo = service.getCenter(1001L);

        assertEquals(CreatorRevenueLedgerVO.State.UNKNOWN, vo.getRevenueState());
        assertNull(vo.getRevenue());
    }

    @Test
    void unavailableLedgerReturnedAsUnknownKeepsReason() {
        when(revenueService.readForMember(eq(1001L), any(), any()))
                .thenReturn(CreatorRevenueLedgerVO.unknown(new Date(), new Date(), "口径版本不可读"));

        CreatorCenterVO vo = service.getCenter(1001L);

        assertEquals(CreatorRevenueLedgerVO.State.UNKNOWN, vo.getRevenueState());
        assertEquals("口径版本不可读", vo.getRevenueUnavailableReason());
        assertNull(vo.getRevenue());
    }

    @Test
    void profilelessMemberKeepsNotAppliedWithoutTouchingRevenue() {
        when(profileMapper.selectByMemberId(2002L)).thenReturn(null);

        CreatorCenterVO vo = service.getCenter(2002L);

        assertEquals("not_applied", vo.getApplyStatus());
        assertEquals(CreatorRevenueLedgerVO.State.UNKNOWN, vo.getRevenueState());
        assertNull(vo.getRevenue());
    }

    private static CreatorProfile profile() {
        CreatorProfile profile = new CreatorProfile();
        profile.setId(9L);
        profile.setMemberId(1001L);
        profile.setCreatorName("城市记录者");
        profile.setApplyStatus(1);
        return profile;
    }

    private static CreatorIncomeDetail legacyIncome() {
        CreatorIncomeDetail detail = new CreatorIncomeDetail();
        detail.setAmount(new BigDecimal("12.30"));
        return detail;
    }
}
