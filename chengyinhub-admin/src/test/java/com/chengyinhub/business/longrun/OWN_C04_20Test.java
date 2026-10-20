package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.MerchantPayableBatch;
import com.chengyinhub.business.mapper.MerchantPayableBatchMapper;
import com.chengyinhub.business.service.impl.MerchantPayableServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** NOT_RUN. Actual service branch regression; mapper doubles do not establish MySQL concurrency semantics. */
class OWN_C04_20Test {
    private MerchantPayableBatchMapper mapper = mock(MerchantPayableBatchMapper.class);
    private MerchantPayableServiceImpl service = new MerchantPayableServiceImpl();

    private MerchantPayableBatch readAs(String status, String confirmer) {
        ReflectionTestUtils.setField(service, "batchMapper", mapper);
        MerchantPayableBatch batch = new MerchantPayableBatch();
        batch.setId(1L);
        batch.setStatus(status);
        batch.setConfirmedBy(confirmer);
        batch.setAmountTotal(new BigDecimal("10.00"));
        when(mapper.selectById(1L)).thenReturn(batch);
        return batch;
    }

    @Test void pendingReadCannotReachSqlThatCouldSucceedAfterConcurrentConfirmation() {
        readAs(MerchantPayableBatch.STATUS_PENDING, null);
        // Models only the dangerous return value if SQL were reached after another request confirmed.
        when(mapper.markPaid(1L, "ops", "voucher")).thenReturn(1);
        assertFalse(service.markBatchPaid(1L, "ops", "voucher"));
        verify(mapper, never()).markPaid(anyLong(), anyString(), anyString());
    }

    @Test void confirmedReadStillRejectsSameTrimmedActor() {
        readAs(MerchantPayableBatch.STATUS_CONFIRMED, " ops ");
        assertThrows(IllegalStateException.class, () -> service.markBatchPaid(1L, "ops", "voucher"));
        verify(mapper, never()).markPaid(anyLong(), anyString(), anyString());
    }

    @Test void confirmedDistinctActorsRetainOriginalCasResult() {
        readAs(MerchantPayableBatch.STATUS_CONFIRMED, "maker");
        when(mapper.markPaid(1L, "checker", "voucher")).thenReturn(0, 1);
        assertFalse(service.markBatchPaid(1L, "checker", "voucher"));
        assertTrue(service.markBatchPaid(1L, "checker", "voucher"));
        verify(mapper, times(2)).markPaid(1L, "checker", "voucher");
    }

    @Test void alreadyPaidAndMissingStatusCannotReachPaymentCas() {
        MerchantPayableBatch batch = readAs(MerchantPayableBatch.STATUS_PAID, "maker");
        assertFalse(service.markBatchPaid(1L, "checker", "voucher"));
        batch.setStatus(null);
        assertFalse(service.markBatchPaid(1L, "checker", "voucher"));
        verify(mapper, never()).markPaid(anyLong(), anyString(), anyString());
    }
}
