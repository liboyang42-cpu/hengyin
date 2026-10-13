package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.UmsMemberSettlementDebt;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberSettlementDebtMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 9-16 晚拍板第 2 条:欠款抵扣不能把结算卡死,也不能把差额静默吞掉。 */
class SettlementDebtLedgerTest {

    private final UmsMemberSettlementDebtMapper debtMapper = mock(UmsMemberSettlementDebtMapper.class);
    private final UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
    private final UmsMemberBalanceDetailMapper detailMapper = mock(UmsMemberBalanceDetailMapper.class);
    private final SettlementDebtLedger ledger = new SettlementDebtLedger(debtMapper, memberMapper, detailMapper);

    /** 人工倒扣允许负余额:此时抵扣额封顶到余额(≤0 就不抵),否则 deductBalance 恒失败、结算每轮回滚卡死。 */
    @Test
    void negativeBalanceSkipsOffsetInsteadOfWedgingSettlement() {
        when(memberMapper.selectBalanceForUpdate(5L)).thenReturn(new BigDecimal("-10.00"));
        UmsMemberSettlementDebt debt = new UmsMemberSettlementDebt();
        debt.setId(1L);
        debt.setAmount(new BigDecimal("30.00"));
        debt.setRepaidAmount(BigDecimal.ZERO);
        when(debtMapper.selectOpenByMemberForUpdate(5L)).thenReturn(Collections.singletonList(debt));

        assertEquals(0, BigDecimal.ZERO.compareTo(ledger.offsetFromSettlement(5L, new BigDecimal("90.00"), 7L)));
        verify(memberMapper, never()).deductBalance(anyLong(), any());
        verify(debtMapper, never()).addRepaid(anyLong(), any());
    }

    @Test
    void shortfallWithoutPayeeFailsClosed() {
        assertThrows(IllegalStateException.class,
                () -> ledger.record(null, SettlementDebtLedger.SOURCE_REFUND, 1L, new BigDecimal("5")));
        verify(debtMapper, never()).insertIgnore(any());
    }
}
