package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Persistence receipt, independent of registration policy or external refund dispatch. */
class RefundWriteReceiptTest {
    @Test
    void rejectsZeroOrMultipleRowsEvenWithAnAssignedId() {
        for (int rows : new int[] {0, 2}) {
            RefundApplicationMapper mapper = mock(RefundApplicationMapper.class);
            when(mapper.insertRefundApplication(any())).thenAnswer(call -> {
                RefundApplication inserted = call.getArgument(0);
                inserted.setId(42L);
                return rows;
            });
            assertThrows(IllegalStateException.class, () -> service(mapper).saveRefund(request()));
            verify(mapper).insertRefundApplication(any());
        }
    }

    @Test
    void rejectsOneRowWithoutGeneratedId() {
        RefundApplicationMapper mapper = mock(RefundApplicationMapper.class);
        when(mapper.insertRefundApplication(any())).thenReturn(1);
        assertThrows(IllegalStateException.class, () -> service(mapper).saveRefund(request()));
    }

    @Test
    void rejectsNonPositiveGeneratedId() {
        for (long id : new long[] {0L, -1L}) {
            RefundApplicationMapper mapper = mock(RefundApplicationMapper.class);
            when(mapper.insertRefundApplication(any())).thenAnswer(call -> {
                RefundApplication inserted = call.getArgument(0);
                inserted.setId(id);
                return 1;
            });
            assertThrows(IllegalStateException.class, () -> service(mapper).saveRefund(request()));
        }
    }

    @Test
    void returnsOriginalRowCountAndPreservesExactGeneratedIdentityAndIntent() {
        RefundApplicationMapper mapper = mock(RefundApplicationMapper.class);
        when(mapper.insertRefundApplication(any())).thenAnswer(call -> {
            RefundApplication inserted = call.getArgument(0);
            inserted.setId(9007199254740993L);
            return 1;
        });
        RefundApplication request = request();
        assertEquals(1, service(mapper).saveRefund(request));
        verify(mapper).insertRefundApplication(request);
        assertEquals(Long.valueOf(9007199254740993L), request.getId());
        assertEquals("EXISTING-REFUND-NO", request.getRefundNo());
        assertEquals("existing-intent", request.getIdempotencyKey());
        assertEquals(new BigDecimal("1.230"), request.getRefundAmount());
        assertEquals(Integer.valueOf(0), request.getStatus());
        assertEquals(Integer.valueOf(RefundApplication.PAYOUT_DISPATCH_PENDING), request.getPayoutStatus());
    }

    private RefundServiceImpl service(RefundApplicationMapper mapper) {
        RefundServiceImpl service = new RefundServiceImpl();
        ReflectionTestUtils.setField(service, "refundApplicationMapper", mapper);
        return service;
    }

    private RefundApplication request() {
        RefundApplication request = new RefundApplication();
        request.setSourceType("receipt-test");
        request.setSourceId(99L);
        request.setRefundAmount(new BigDecimal("1.230"));
        request.setRefundNo("EXISTING-REFUND-NO");
        request.setIdempotencyKey("existing-intent");
        return request;
    }
}
