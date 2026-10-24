package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.RegistrationSeat;
import com.chengyinhub.business.mapper.RegistrationSeatMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Reader/port and transaction-boundary tests; not full application startup or MySQL evidence. */
class RegistrationSeatSettlementReaderTest {
    @Test void preservesOriginalScopeCutoffCurrentReadOrderAndFactIdentities() {
        var mapper = mock(RegistrationSeatMapper.class);
        var reader = new RegistrationSeatSettlementReader(mapper);
        Date cutoff = new Date(1000);
        var fact = new RegistrationSeat(); fact.setId(101L); fact.setRegistrationId(31L);
        List<RegistrationSeat> original = List.of(fact);
        when(mapper.selectUnprovenMultiGroupOrdersForUpdate(41L, 61L, cutoff)).thenReturn(List.of());
        when(mapper.selectVerifiedForGroupForUpdate(41L, 61L, cutoff)).thenReturn(original);

        assertSame(original, reader.verifiedSeatsForGroup(41L, 61L, cutoff));
        var order = inOrder(mapper);
        order.verify(mapper).selectUnprovenMultiGroupOrdersForUpdate(41L, 61L, cutoff);
        order.verify(mapper).selectVerifiedForGroupForUpdate(41L, 61L, cutoff);
        verifyNoMoreInteractions(mapper);
    }

    @Test void rejectsMissingOrInvalidScopeBeforeAnyCurrentRead() {
        var mapper = mock(RegistrationSeatMapper.class);
        var reader = new RegistrationSeatSettlementReader(mapper);
        for (Long id : new Long[]{null, 0L, -1L}) {
            assertThrows(IllegalArgumentException.class, () -> reader.verifiedSeatsForGroup(id, 61L, new Date()));
            assertThrows(IllegalArgumentException.class, () -> reader.verifiedSeatsForGroup(41L, id, new Date()));
        }
        assertThrows(IllegalArgumentException.class, () -> reader.verifiedSeatsForGroup(41L, 61L, null));
        verifyNoInteractions(mapper);
    }

    @Test void requiresTheCallersOriginalTransactionBeforeAnyMapperRead() {
        var mapper = mock(RegistrationSeatMapper.class);
        var proxy = new ProxyFactory(new RegistrationSeatSettlementReader(mapper));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(mock(DataSource.class)),
                new AnnotationTransactionAttributeSource()));
        var reader = (RegistrationSeatSettlementReader) proxy.getProxy();

        assertThrows(IllegalTransactionStateException.class,
                () -> reader.verifiedSeatsForGroup(41L, 61L, new Date()));
        verifyNoInteractions(mapper);
    }

    @Test void doesNotTurnFailedLegacyEvidenceReadIntoZeroFulfillment() {
        var mapper = mock(RegistrationSeatMapper.class);
        var reader = new RegistrationSeatSettlementReader(mapper);
        Date cutoff = new Date();
        var failure = new IllegalStateException("original current read unavailable");
        when(mapper.selectUnprovenMultiGroupOrdersForUpdate(41L, 61L, cutoff)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> reader.verifiedSeatsForGroup(41L, 61L, cutoff)));
        verify(mapper, never()).selectVerifiedForGroupForUpdate(any(), any(), any());
    }
}
