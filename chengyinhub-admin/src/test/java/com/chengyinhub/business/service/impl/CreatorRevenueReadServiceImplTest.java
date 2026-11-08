package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.CreatorRevenueRow;
import com.chengyinhub.business.domain.vo.CreatorRevenueLedgerVO;
import com.chengyinhub.business.mapper.CreatorRevenueLedgerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 原账本收入只读口径：按 receiver_kind 判会员范围、冲回可归到原分项、读失败不假零。
 */
class CreatorRevenueReadServiceImplTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private CreatorRevenueLedgerMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = mock(CreatorRevenueLedgerMapper.class);
        when(mapper.selectGrossByReceiver(any(), any())).thenReturn(List.of());
        when(mapper.selectRefundedByReceiver(any(), any())).thenReturn(List.of());
        when(mapper.selectCalculationVersions(any(), any())).thenReturn(List.of());
    }

    private CreatorRevenueReadServiceImpl service() {
        return new CreatorRevenueReadServiceImpl(mapper,
                Clock.fixed(Instant.parse("2026-11-08T00:00:00Z"), ZONE));
    }

    @Test
    void grossMinusSettledRefundIsNetAndKeepsTraceIds() {
        when(mapper.selectGrossByReceiver(any(), any())).thenReturn(List.of(
                row("MEMBER", 1001L, "100.00", "0", 2L, 1L, 9L, new Date()),
                row("MEMBER", 1001L, "50.00", "0", 1L, 1L, 9L, new Date())));
        when(mapper.selectRefundedByReceiver(any(), any())).thenReturn(List.of(
                row("MEMBER", 1001L, "0", "-20.00", 1L, 1L, 4L, new Date())));
        when(mapper.selectCalculationVersions(any(), any())).thenReturn(List.of("v1"));

        CreatorRevenueLedgerVO ledger = service().read(null, null);

        assertTrue(ledger.isAvailable());
        assertEquals(CreatorRevenueLedgerVO.State.AVAILABLE, ledger.getState());
        assertEquals(1, ledger.getEntries().size());
        CreatorRevenueLedgerVO.Entry entry = ledger.getEntries().get(0);
        assertEquals(Long.valueOf(1001L), entry.memberId());
        assertEquals(new BigDecimal("150.00"), entry.grossAmount());
        assertEquals(new BigDecimal("-20.00"), entry.refundedAmount());
        assertEquals(new BigDecimal("130.00"), entry.netAmount());
        assertEquals(3L, entry.componentCount());
        assertEquals(Long.valueOf(9L), entry.maxComponentId());
        assertEquals(List.of("v1"), ledger.getCalculationVersions());
    }

    @Test
    void accountingReceiverNeverEntersMemberOutput() {
        when(mapper.selectGrossByReceiver(any(), any())).thenReturn(List.of(
                row("MEMBER", 1001L, "100.00", "0", 1L, 1L, 9L, new Date()),
                row("ACCOUNTING", null, "300.00", "0", 3L, 3L, 9L, new Date())));

        CreatorRevenueLedgerVO ledger = service().read(null, null);

        assertEquals(1, ledger.getEntries().size(), "ACCOUNTING 不得进会员输出");
        assertEquals(Long.valueOf(1001L), ledger.getEntries().get(0).memberId());
        assertEquals(new BigDecimal("300.00"), ledger.getUnattributed().grossAmount());
        assertEquals(new BigDecimal("300.00"), ledger.getUnattributed().netAmount());
    }

    @Test
    void memberKindWithoutPositiveIdFallsIntoUnattributed() {
        when(mapper.selectGrossByReceiver(any(), any())).thenReturn(List.of(
                row("MEMBER", null, "40.00", "0", 1L, 1L, 3L, new Date()),
                row("MEMBER", 0L, "10.00", "0", 1L, 1L, 3L, new Date())));

        CreatorRevenueLedgerVO ledger = service().read(null, null);

        assertTrue(ledger.getEntries().isEmpty());
        assertEquals(new BigDecimal("50.00"), ledger.getUnattributed().grossAmount());
    }

    @Test
    void readFailureThrowsInsteadOfReturningZero() {
        when(mapper.selectGrossByReceiver(any(), any())).thenReturn(null);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service().read(null, null));
        assertTrue(failure.getMessage().contains("0"));
    }

    @Test
    void refundReadFailureThrows() {
        when(mapper.selectRefundedByReceiver(any(), any())).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> service().read(null, null));
    }

    @Test
    void versionReadFailureThrows() {
        when(mapper.selectCalculationVersions(any(), any())).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> service().read(null, null));
    }

    @Test
    void unknownStateCarriesNoAmounts() {
        CreatorRevenueLedgerVO unknown = CreatorRevenueLedgerVO.unknown(new Date(), new Date(), "原账本不可读");

        assertEquals(CreatorRevenueLedgerVO.State.UNKNOWN, unknown.getState());
        assertFalse(unknown.isAvailable());
        assertNull(unknown.getAttributed(), "未知不得给 0 金额");
        assertNull(unknown.getUnattributed());
        assertTrue(unknown.getEntries().isEmpty());
        assertEquals("原账本不可读", unknown.getUnavailableReason());
    }

    @Test
    void memberScopeShowsOnlyThatMemberAndNeverAccounting() {
        when(mapper.selectGrossByReceiver(any(), any())).thenReturn(List.of(
                row("MEMBER", 1001L, "100.00", "0", 1L, 1L, 9L, new Date()),
                row("MEMBER", 2002L, "70.00", "0", 1L, 1L, 9L, new Date()),
                row("ACCOUNTING", null, "999.00", "0", 9L, 9L, 9L, new Date())));

        CreatorRevenueLedgerVO mine = service().readForMember(1001L, null, null);

        assertEquals(1, mine.getEntries().size());
        assertEquals(new BigDecimal("100.00"), mine.getAttributed().grossAmount());
        assertEquals(new BigDecimal("100.00"), mine.getAttributed().netAmount());
        // 平台/ACCOUNTING 应计不属于任何个体创作者：会员视角不展示，净额也不被它污染。
        assertEquals(new BigDecimal("0"), mine.getUnattributed().netAmount());
    }

    @Test
    void memberWithoutLedgerRowsReadsAsZeroButStillAvailable() {
        CreatorRevenueLedgerVO mine = service().readForMember(3003L, null, null);

        assertTrue(mine.isAvailable(), "期间内无分项是已知的 0，不是读失败");
        assertTrue(mine.getEntries().isEmpty());
        assertEquals(new BigDecimal("0"), mine.getAttributed().netAmount());
    }

    @Test
    void revenueBasisIsAccrualNotCash() {
        CreatorRevenueLedgerVO ledger = service().read(null, null);

        assertEquals("ACCRUAL_ORIGINAL_LEDGER", ledger.getRevenueBasis());
        assertTrue(ledger.getRevenueBasisNote().contains("不等于实收"));
        assertTrue(ledger.getRevenueBasisNote().contains("可提现"));
        assertEquals("ORIGINAL_LEDGER_RECEIVER_MEMBER", ledger.getScope());
    }

    private static CreatorRevenueRow row(String receiverKind, Long memberId, String gross, String refunded,
                                         Long components, Long settlements, Long maxId, Date watermark) {
        CreatorRevenueRow row = new CreatorRevenueRow();
        row.setReceiverKind(receiverKind);
        row.setMemberId(memberId);
        row.setGrossAmount(new BigDecimal(gross));
        row.setRefundedAmount(new BigDecimal(refunded));
        row.setComponentCount(components);
        row.setSettlementCount(settlements);
        row.setMaxComponentId(maxId);
        row.setWatermarkAt(watermark);
        return row;
    }
}
