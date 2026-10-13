package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.SettlementBatch;
import com.chengyinhub.business.domain.SettlementDetail;
import com.chengyinhub.business.domain.dto.SettlementGenerateDTO;
import com.chengyinhub.business.mapper.SettlementBatchMapper;
import com.chengyinhub.business.mapper.SettlementDetailMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementServiceImplTest {

    private SettlementServiceImpl service(SettlementBatchMapper batchMapper, SettlementDetailMapper detailMapper) {
        SettlementServiceImpl service = new SettlementServiceImpl();
        ReflectionTestUtils.setField(service, "settlementBatchMapper", batchMapper);
        ReflectionTestUtils.setField(service, "settlementDetailMapper", detailMapper);
        return service;
    }

    private SettlementGenerateDTO request() {
        SettlementGenerateDTO request = new SettlementGenerateDTO();
        request.setMerchantId(9L);
        request.setBatchNo("S20260619001");
        request.setIdempotencyKey("merchant-9-20260619");
        return request;
    }

    private SettlementDetail detail(String receiverType, Long receiverId, String amount) {
        SettlementDetail d = new SettlementDetail();
        d.setReceiverType(receiverType);
        d.setReceiverId(receiverId);
        d.setSettlementAmount(new BigDecimal(amount));
        d.setStatus(0);
        return d;
    }

    @Test
    void generateBatchReturnsExistingBatchForSameIdempotencyKey() {
        SettlementBatchMapper batchMapper = mock(SettlementBatchMapper.class);
        SettlementDetailMapper detailMapper = mock(SettlementDetailMapper.class);
        SettlementServiceImpl service = service(batchMapper, detailMapper);
        SettlementBatch existing = new SettlementBatch();
        existing.setId(88L);
        existing.setMerchantId(9L);
        when(batchMapper.selectByIdempotencyKey("merchant-9-20260619")).thenReturn(existing);

        Long batchId = service.generateBatch(request());

        assertEquals(88L, batchId);
        verify(batchMapper, never()).insertSettlementBatch(any(SettlementBatch.class));
        verify(detailMapper, never()).bindBatch(any(), any());
    }

    @Test
    void generateBatchConsumesPendingDetailsIntoNewBatch() {
        SettlementBatchMapper batchMapper = mock(SettlementBatchMapper.class);
        SettlementDetailMapper detailMapper = mock(SettlementDetailMapper.class);
        SettlementServiceImpl service = service(batchMapper, detailMapper);
        when(batchMapper.selectByIdempotencyKey("merchant-9-20260619")).thenReturn(null);
        when(detailMapper.selectPendingByMerchant(9L)).thenReturn(Arrays.asList(
                detail("merchant", 9L, "108.00"), detail("platform", 0L, "12.00")));
        when(batchMapper.insertSettlementBatch(any(SettlementBatch.class))).thenAnswer(invocation -> {
            SettlementBatch batch = invocation.getArgument(0);
            batch.setId(66L);
            return 1;
        });
        when(detailMapper.bindBatch(66L, 9L)).thenReturn(2);
        when(detailMapper.selectDetailList(org.mockito.ArgumentMatchers.argThat(
                query -> query != null && Long.valueOf(66L).equals(query.getBatchId()))))
                .thenReturn(Arrays.asList(detail("merchant", 9L, "108.00"), detail("platform", 0L, "12.00")));

        when(batchMapper.updateBatchTotals(eq(66L), any(), eq(2L))).thenReturn(1);
        Long batchId = service.generateBatch(request());

        assertEquals(66L, batchId);
        // 插入时先落占位金额(0/0),真实 total_amount/detail_count 只能来自 bindBatch 实际绑定的行——
        // 这正是修复 P1-6-S块 1.1 幽灵批次的关键:不能在 bindBatch 之前就把快照算好的数字写死。
        verify(batchMapper).insertSettlementBatch(org.mockito.ArgumentMatchers.argThat(batch ->
                BigDecimal.ZERO.compareTo(batch.getTotalAmount()) == 0
                        && Long.valueOf(0L).equals(batch.getDetailCount())));
        verify(detailMapper).bindBatch(66L, 9L);
        verify(batchMapper).updateBatchTotals(org.mockito.ArgumentMatchers.eq(66L),
                org.mockito.ArgumentMatchers.argThat(amount -> new BigDecimal("120.00").compareTo(amount) == 0),
                org.mockito.ArgumentMatchers.eq(2L));
    }

    @Test
    void generateBatchThrowsWhenNoPendingDetails() {
        SettlementBatchMapper batchMapper = mock(SettlementBatchMapper.class);
        SettlementDetailMapper detailMapper = mock(SettlementDetailMapper.class);
        SettlementServiceImpl service = service(batchMapper, detailMapper);
        when(batchMapper.selectByIdempotencyKey("merchant-9-20260619")).thenReturn(null);
        when(detailMapper.selectPendingByMerchant(9L)).thenReturn(Collections.emptyList());

        assertThrows(IllegalStateException.class, () -> service.generateBatch(request()));
        verify(batchMapper, never()).insertSettlementBatch(any(SettlementBatch.class));
    }

    /**
     * P1-6-S块 1.1 幽灵批次修复的核心断言:即便 selectPendingByMerchant 的预检非空（比如两次
     * 并发调用都读到了同一份待结算快照），只要 bindBatch 的实际生效行数是 0（说明这些明细已经
     * 被并发赢家绑走），这次调用就必须直接失败，绝不能留下一个 total_amount 非零、却零绑定明细的
     * 幽灵批次——不再依赖 mock 无法验证的"事务是否真的回滚"，而是断言插入的占位批次永远不会被
     * updateBatchTotals 回填出非零金额。
     */
    @Test
    void generateBatchThrowsWhenBindBatchLosesRaceEvenIfPrecheckSawPendingDetails() {
        SettlementBatchMapper batchMapper = mock(SettlementBatchMapper.class);
        SettlementDetailMapper detailMapper = mock(SettlementDetailMapper.class);
        SettlementServiceImpl service = service(batchMapper, detailMapper);
        when(batchMapper.selectByIdempotencyKey("merchant-9-20260619")).thenReturn(null);
        when(detailMapper.selectPendingByMerchant(9L)).thenReturn(Arrays.asList(
                detail("merchant", 9L, "108.00"), detail("platform", 0L, "12.00")));
        when(batchMapper.insertSettlementBatch(any(SettlementBatch.class))).thenAnswer(invocation -> {
            SettlementBatch batch = invocation.getArgument(0);
            batch.setId(67L);
            return 1;
        });
        when(detailMapper.bindBatch(67L, 9L)).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.generateBatch(request()));
        verify(batchMapper, never()).updateBatchTotals(any(), any(), any());
    }

    @Test
    void generateBatchRequiresMerchantAndBatch() {
        SettlementServiceImpl service = service(mock(SettlementBatchMapper.class), mock(SettlementDetailMapper.class));

        assertThrows(IllegalArgumentException.class, () -> service.generateBatch(new SettlementGenerateDTO()));
    }

    /**
     * P0-28 防双花:confirmBatch 只流转账本状态,不再逐条打款。
     * 打款由系统1(settleActivity/settleTopic)独占;系统2 若也打款,同一笔 gross 会被切两次。
     *
     * <p>⚠️「不打款」这件事本测试守不住 —— 余额 mapper 已从 SettlementServiceImpl 拆除,
     * 任何 verify(never()) 都恒真。真正的守卫是 {@code SettlementPayoutDecommissionedTest}(源码接线断言)。
     */
    @Test
    void confirmBatchMarksSettledLedgerOnly() {
        SettlementBatchMapper batchMapper = mock(SettlementBatchMapper.class);
        SettlementDetailMapper detailMapper = mock(SettlementDetailMapper.class);
        SettlementServiceImpl service = service(batchMapper, detailMapper);
        SettlementBatch batch = new SettlementBatch();
        batch.setId(66L);
        batch.setStatus(0);
        when(batchMapper.selectById(66L)).thenReturn(batch);
        when(batchMapper.updateBatchStatusIfPending(eq(66L), eq(1), eq(7L), any())).thenReturn(1);

        int rows = service.confirmBatch(66L, 7L);

        assertEquals(1, rows);
        verify(detailMapper).markBatchSettled(66L);
    }

    @Test
    void confirmBatchIsIdempotentWhenAlreadyConfirmed() {
        SettlementBatchMapper batchMapper = mock(SettlementBatchMapper.class);
        SettlementDetailMapper detailMapper = mock(SettlementDetailMapper.class);
        SettlementServiceImpl service = service(batchMapper, detailMapper);
        SettlementBatch batch = new SettlementBatch();
        batch.setId(66L);
        when(batchMapper.selectById(66L)).thenReturn(batch);
        when(batchMapper.updateBatchStatusIfPending(eq(66L), eq(1), eq(7L), any())).thenReturn(0);

        int rows = service.confirmBatch(66L, 7L);

        assertEquals(0, rows);
        verify(detailMapper, never()).markBatchSettled(any());
    }
    @Test
    void replayCannotReturnAnotherMerchantsBatch() {
        SettlementBatchMapper batches = mock(SettlementBatchMapper.class);
        SettlementDetailMapper details = mock(SettlementDetailMapper.class);
        SettlementBatch foreign = new SettlementBatch();
        foreign.setId(88L); foreign.setMerchantId(10L);
        when(batches.selectByIdempotencyKey("merchant-9-20260619")).thenReturn(foreign);
        assertThrows(IllegalArgumentException.class, () -> service(batches, details).generateBatch(request()));
        verify(details, never()).selectPendingByMerchant(any());
    }

    @Test
    void lostTotalsWriteFailsInsteadOfLeavingZeroBatch() {
        SettlementBatchMapper batches = mock(SettlementBatchMapper.class);
        SettlementDetailMapper details = mock(SettlementDetailMapper.class);
        when(details.selectPendingByMerchant(9L)).thenReturn(Collections.singletonList(detail("merchant", 9L, "12.00")));
        when(batches.insertSettlementBatch(any())).thenAnswer(invocation -> {
            ((SettlementBatch) invocation.getArgument(0)).setId(66L); return 1;
        });
        when(details.bindBatch(66L, 9L)).thenReturn(1);
        when(details.selectDetailList(any())).thenReturn(Collections.singletonList(detail("merchant", 9L, "12.00")));
        when(batches.updateBatchTotals(eq(66L), any(), eq(1L))).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service(batches, details).generateBatch(request()));
    }
}
