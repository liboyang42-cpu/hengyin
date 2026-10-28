package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.NpcChatRequest;
import com.chengyinhub.business.mapper.NpcChatLogMapper;
import com.chengyinhub.business.mapper.NpcChatRequestMapper;
import com.chengyinhub.business.service.impl.NpcChatWriteService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Admission clock and old-row non-renewal; not a database lock/concurrency proof. */
class OWN_C04_09Test {
    private NpcChatWriteService writes(NpcChatRequestMapper requests) {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        NpcChatWriteService writes=new NpcChatWriteService(requests,mock(NpcChatLogMapper.class),transactions);
        org.springframework.test.util.ReflectionTestUtils.setField(writes,"asrIntents",mock(com.chengyinhub.business.mapper.NpcAsrIntentMapper.class));
        return writes;
    }
    private NpcChatRequest request() {
        NpcChatRequest request = new NpcChatRequest();
        request.setUserId(1001L); request.setSessionId(99L);
        request.setRequestId("00000000-0000-4000-8000-000000000009");
        request.setLeaseToken("original-new-request-token");
        request.setLeaseUntil(new Date(0));
        return request;
    }
    @Test void freshAdmissionStartsItsTwentySecondsAfterLockAcquisition() {
        NpcChatRequestMapper requests = mock(NpcChatRequestMapper.class);
        NpcChatRequest request = request();
        AtomicLong acquiredAt = new AtomicLong();
        when(requests.lockChatUser(1001L)).thenAnswer(inv -> {
            acquiredAt.set(System.currentTimeMillis()); return 1001L;
        });
        when(requests.selectByUserAndRequestId(1001L, request.getRequestId())).thenReturn(null, request);
        when(requests.insertNpcChatRequest(request)).thenAnswer(inv -> {
            assertTrue(request.getLeaseUntil().getTime() >= acquiredAt.get() + 20_000L);
            assertTrue(request.getLeaseUntil().getTime() <= System.currentTimeMillis() + 20_000L);
            assertEquals("original-new-request-token", request.getLeaseToken());
            return 1;
        });
        assertSame(request, writes(requests).admit(request, "timeout"));
        verify(requests).insertNpcChatRequest(request);
    }
    @Test void expiredExistingReceiptIsNotRenewedOrReinserted() {
        NpcChatRequestMapper requests = mock(NpcChatRequestMapper.class);
        NpcChatRequest existing = request();
        Date oldDeadline = existing.getLeaseUntil();
        when(requests.lockChatUser(1001L)).thenReturn(1001L);
        when(requests.selectByUserAndRequestId(1001L, existing.getRequestId())).thenReturn(existing);
        assertSame(existing, writes(requests).admit(request(), "timeout"));
        assertSame(oldDeadline, existing.getLeaseUntil());
        assertEquals(0L, existing.getLeaseUntil().getTime());
        verify(requests, never()).insertNpcChatRequest(any());
        verify(requests, never()).expireProcessingByUserAndSession(anyLong(), anyLong(), anyString());
    }
}
