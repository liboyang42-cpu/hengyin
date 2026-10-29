package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.dto.RegistrationPaymentFactReceipt;
import com.chengyinhub.business.mapper.CmsRegistrationPaymentFactMapper;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Unit identity/current-mapper boundary only; setting the transaction flag does not prove MySQL isolation. */
class RegistrationPaymentConsumptionReadServiceTest {
    private final CmsRegistrationPaymentFactMapper mapper = mock(CmsRegistrationPaymentFactMapper.class);
    private final RegistrationPaymentConsumptionReadService reader = new RegistrationPaymentConsumptionReadService(mapper);
    private boolean priorTransaction;
    private final String hash = "a".repeat(64);

    @BeforeEach void enterCaller() {
        priorTransaction = TransactionSynchronizationManager.isActualTransactionActive();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
    @AfterEach void restoreCaller() {
        TransactionSynchronizationManager.setActualTransactionActive(priorTransaction);
    }

    @Test void outsideTransactionCannotReadOrOpenAnIndependentSnapshot() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThrows(IllegalStateException.class, () -> reader.findConsumedPayment(1L));
        verifyNoInteractions(mapper);
    }
    @Test void missingOrNonpositivePayerDoesNotTouchStorage() {
        for (Long id : Arrays.asList(null, 0L, -1L))
            assertThrows(IllegalArgumentException.class, () -> reader.findConsumedPayment(id));
        verifyNoInteractions(mapper);
    }
    @Test void onlyAbsentCurrentReceiptIsReportedAsAbsent() {
        assertTrue(reader.findConsumedPayment(1L).isEmpty());
        verify(mapper).selectByRegistrationForUpdate(1L);
        verifyNoMoreInteractions(mapper);
    }
    @Test void originalReceiptAndReferenceAreReturnedWithoutReconstruction() {
        var original = new RegistrationPaymentFactReceipt(1L, "REG1", 9L, hash, "WX1");
        when(mapper.selectByRegistrationForUpdate(1L)).thenReturn(original);
        assertSame(original, reader.findConsumedPayment(1L).orElseThrow());
        assertEquals(9L, original.reference().id());
        assertEquals(hash, original.reference().hash());
        verify(mapper).selectByRegistrationForUpdate(1L);
        verifyNoMoreInteractions(mapper);
    }
    @Test void wrongPayerAndIncompleteBusinessIdentityDoNotFallBackToAnotherReceipt() {
        for (var row : List.of(
                new RegistrationPaymentFactReceipt(2L, "REG1", 9L, hash, "WX1"),
                new RegistrationPaymentFactReceipt(1L, "", 9L, hash, "WX1"),
                new RegistrationPaymentFactReceipt(1L, "REG1", 9L, hash, " "))) {
            when(mapper.selectByRegistrationForUpdate(1L)).thenReturn(row);
            assertThrows(IllegalStateException.class, () -> reader.findConsumedPayment(1L));
        }
    }
    @Test void malformedFactReferenceIsNotTreatedAsNoLocalConsumption() {
        when(mapper.selectByRegistrationForUpdate(1L))
                .thenReturn(new RegistrationPaymentFactReceipt(1L, "REG1", 9L, "bad-hash", "WX1"));
        assertThrows(IllegalArgumentException.class, () -> reader.findConsumedPayment(1L));
    }
}
