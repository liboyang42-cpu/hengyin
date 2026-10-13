package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MerchantClaim;
import com.chengyinhub.business.domain.MerchantClaimBatchCommand;
import com.chengyinhub.business.domain.MerchantClaimBatchResult;
import com.chengyinhub.business.domain.dto.MerchantClaimDTO;
import com.chengyinhub.business.exception.MerchantClaimException;
import com.chengyinhub.business.service.impl.MerchantClaimBatchServiceImpl;
import com.chengyinhub.business.service.impl.MerchantClaimBatchStore;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantClaimBatchServiceImplTest {
    @Test
    void partialFailureReturnsOneResultPerItemAndContinues() {
        IMerchantClaimService claimService = mock(IMerchantClaimService.class);
        MerchantClaimBatchStore store = mock(MerchantClaimBatchStore.class);
        MerchantClaimBatchCommand command = new MerchantClaimBatchCommand();
        command.setId(88L);
        when(store.open(anyLong(), anyString(), anyString(), anyString())).thenReturn(command);
        MerchantClaim approved = new MerchantClaim();
        approved.setStatus("APPROVED");
        when(claimService.audit(anyLong(), any(MerchantClaimDTO.AuditRequest.class))).thenAnswer(invocation -> {
            MerchantClaimDTO.AuditRequest request = invocation.getArgument(1);
            if (Long.valueOf(1L).equals(request.getClaimId())) {
                throw new MerchantClaimException("STATE_CONFLICT", "状态已变化", 409);
            }
            return approved;
        });
        MerchantClaim pending = new MerchantClaim();
        pending.setStatus("PENDING");
        when(claimService.get(1L)).thenReturn(pending);

        List<MerchantClaimBatchResult> results = new MerchantClaimBatchServiceImpl(claimService, store)
                .auditBatch(9L, batchRequest());

        assertEquals(2, results.size());
        assertFalse(results.get(0).isSuccess());
        assertEquals("STATE_CONFLICT", results.get(0).getCode());
        assertEquals("PENDING", results.get(0).getCurrentStatus());
        assertTrue(results.get(1).isSuccess());
        assertEquals("APPROVED", results.get(1).getCurrentStatus());
        verify(store, org.mockito.Mockito.times(2)).saveResult(any(MerchantClaimBatchResult.class));
    }

    @Test
    void exactReplayUsesStoredItemResultWithoutCallingAuditAgain() {
        IMerchantClaimService claimService = mock(IMerchantClaimService.class);
        MerchantClaimBatchStore store = mock(MerchantClaimBatchStore.class);
        MerchantClaimBatchCommand command = new MerchantClaimBatchCommand();
        command.setId(88L);
        when(store.open(anyLong(), anyString(), anyString(), anyString())).thenReturn(command);
        MerchantClaimBatchResult stored = new MerchantClaimBatchResult();
        stored.setClaimId(1L);
        stored.setSuccess(true);
        stored.setCode("OK");
        when(store.findResult(88L, 1L)).thenReturn(stored);
        when(store.findResult(88L, 2L)).thenReturn(stored);

        List<MerchantClaimBatchResult> results = new MerchantClaimBatchServiceImpl(claimService, store)
                .auditBatch(9L, batchRequest());

        assertEquals(2, results.size());
        verify(claimService, never()).audit(anyLong(), any(MerchantClaimDTO.AuditRequest.class));
        verify(store, never()).saveResult(any(MerchantClaimBatchResult.class));
    }

    private MerchantClaimDTO.BatchRequest batchRequest() {
        MerchantClaimDTO.BatchRequest request = new MerchantClaimDTO.BatchRequest();
        request.setRequestId("batch-stable-key");
        request.setAction("APPROVE");
        MerchantClaimDTO.BatchItem first = item(1L);
        MerchantClaimDTO.BatchItem second = item(2L);
        request.setItems(Arrays.asList(first, second));
        return request;
    }

    private MerchantClaimDTO.BatchItem item(Long id) {
        MerchantClaimDTO.BatchItem item = new MerchantClaimDTO.BatchItem();
        item.setClaimId(id);
        item.setExpectedStatus("PENDING");
        item.setExpectedVersion(0);
        return item;
    }
}
