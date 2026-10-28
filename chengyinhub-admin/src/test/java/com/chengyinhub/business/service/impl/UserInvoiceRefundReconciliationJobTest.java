package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.UserInvoiceOrderLine;
import com.chengyinhub.business.mapper.UserInvoiceMapper;
import com.chengyinhub.business.mapper.UserInvoiceProcessingMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserInvoiceRefundReconciliationJobTest {
    @Test void failedFactDoesNotBlockOtherInvoicesAndRetriesOnNextPass() {
        var mapper=mock(UserInvoiceProcessingMapper.class); var invoices=mock(UserInvoiceMapper.class);
        var consumer=mock(UserInvoiceProcessingService.class);
        when(mapper.refundPauseCandidates(0)).thenReturn(List.of(1L,2L),List.of(1L));
        doThrow(new IllegalStateException("isolated failure")).doNothing().when(consumer).acceptRegistrationRefundFact(1L);
        var job=new UserInvoiceRefundReconciliationJob(mapper,invoices,consumer);
        job.reconcilePendingRefundFacts(); job.reconcilePendingRefundFacts();
        verify(consumer,times(2)).acceptRegistrationRefundFact(1L); verify(consumer).acceptRegistrationRefundFact(2L);
    }
    @Test void boundedCursorWrapAndRestartRevisitDurableFacts() {
        var mapper=mock(UserInvoiceProcessingMapper.class); var invoices=mock(UserInvoiceMapper.class);
        var consumer=mock(UserInvoiceProcessingService.class);
        when(mapper.refundPauseCandidates(0)).thenReturn(java.util.stream.LongStream.rangeClosed(1,50).boxed().toList());
        when(mapper.refundPauseCandidates(50)).thenReturn(List.of());
        var job=new UserInvoiceRefundReconciliationJob(mapper,invoices,consumer);
        job.reconcilePendingRefundFacts(); job.reconcilePendingRefundFacts(); job.reconcilePendingRefundFacts();
        new UserInvoiceRefundReconciliationJob(mapper,invoices,consumer).reconcilePendingRefundFacts();
        verify(mapper,times(3)).refundPauseCandidates(0); verify(mapper).refundPauseCandidates(50);
    }
    @Test void scanFailureIsRetriedWithoutAcknowledgingAnyRegistration() {
        var mapper=mock(UserInvoiceProcessingMapper.class); var consumer=mock(UserInvoiceProcessingService.class);
        when(mapper.refundPauseCandidates(0)).thenThrow(new IllegalStateException("isolated scan failure")).thenReturn(List.of(1L));
        var job=new UserInvoiceRefundReconciliationJob(mapper,mock(UserInvoiceMapper.class),consumer);
        job.reconcilePendingRefundFacts(); job.reconcilePendingRefundFacts();
        verify(mapper,times(2)).refundPauseCandidates(0); verify(consumer).acceptRegistrationRefundFact(1L);
    }
    @Test void manualRecoveryChecksScopeBeforeFactsAndReturnsFreshReadback() {
        var mapper=mock(UserInvoiceProcessingMapper.class); var invoices=mock(UserInvoiceMapper.class);
        var consumer=mock(UserInvoiceProcessingService.class);
        var job=new UserInvoiceRefundReconciliationJob(mapper,invoices,consumer);
        when(consumer.detail(903L,7L)).thenThrow(new IllegalStateException("scope denied"));
        assertThrows(IllegalStateException.class,()->job.refreshApplication(903L,7L));
        verifyNoInteractions(invoices);
        UserInvoiceOrderLine line=new UserInvoiceOrderLine(); line.setOrderId(9L);
        when(invoices.selectOrderLines(7L)).thenReturn(List.of(line));
        when(consumer.detail(901L,7L)).thenReturn(Map.of("status","PROCESSING"),Map.of("status","PAUSED"));
        assertEquals("PAUSED",job.refreshApplication(901L,7L).get("status"));
        var order=inOrder(consumer,invoices);
        order.verify(consumer).detail(901L,7L); order.verify(invoices).selectOrderLines(7L);
        order.verify(consumer).acceptRegistrationRefundFact(9L); order.verify(consumer).detail(901L,7L);
    }
}
