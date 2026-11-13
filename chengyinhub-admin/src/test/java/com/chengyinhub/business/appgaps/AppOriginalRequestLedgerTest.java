package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.appgaps.mapper.AppOriginalRequestMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AppOriginalRequestLedgerTest {
    static class NoTx extends AbstractPlatformTransactionManager {
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object t, TransactionDefinition d) {}
        protected void doCommit(DefaultTransactionStatus s) {}
        protected void doRollback(DefaultTransactionStatus s) {}
    }
    static final String RID = "11111111-1111-4111-8111-111111111111", SHA = "c".repeat(64);

    @Test void rejectsMalformedIdentity() {
        AppOriginalRequestLedger l = new AppOriginalRequestLedger(mock(AppOriginalRequestMapper.class), new NoTx());
        assertEquals(400, assertThrows(ServiceException.class, () -> l.run("p", 1, "has space", "", SHA, () -> Map.of())).getCode());
        assertEquals(400, assertThrows(ServiceException.class, () -> l.run("p", 1, "x".repeat(65), "", SHA, () -> Map.of())).getCode());
        assertEquals(400, assertThrows(ServiceException.class, () -> l.run("p", 1, RID, "", "ABC", () -> Map.of())).getCode());
        assertEquals(401, assertThrows(ServiceException.class, () -> l.run("p", 0, RID, "", SHA, () -> Map.of())).getCode());
    }

    @Test void firstRunCommitsAndRecordsResultJson() {
        AppOriginalRequestLedgerMapperStub m = new AppOriginalRequestLedgerMapperStub();
        AppOriginalRequestLedger l = new AppOriginalRequestLedger(m.mock, new NoTx());
        var fact = l.run("p", 5, RID, "s", SHA, () -> Map.of("id", 3));
        assertEquals("COMMITTED", fact.state());
        verify(m.mock).finish(eq(77L), eq("COMMITTED"), eq("{\"id\":3}"), isNull(), isNull());
    }

    @Test void rejectionIsStoredWithCodeAndNotRethrown() {
        AppOriginalRequestLedgerMapperStub m = new AppOriginalRequestLedgerMapperStub();
        AppOriginalRequestLedger l = new AppOriginalRequestLedger(m.mock, new NoTx());
        var fact = l.run("p", 5, RID, "s", SHA, () -> { throw new ServiceException("满员", 422); });
        assertEquals("REJECTED", fact.state()); assertEquals(422, fact.errorCode());
        verify(m.mock, times(2)).insertIgnore(argThat(r -> "s".equals(r.get("scope"))));
    }

    @Test void replayWithDifferentDigestIsConflict() {
        AppOriginalRequestMapper mapper = mock(AppOriginalRequestMapper.class);
        when(mapper.insertIgnore(any())).thenReturn(0);
        Map<String, Object> row = new HashMap<>(Map.of("state", "COMMITTED", "requestId", RID, "scopeKey", "s", "payloadSha256", "d".repeat(64)));
        when(mapper.select("p", 5L, RID)).thenReturn(row);
        AppOriginalRequestLedger l = new AppOriginalRequestLedger(mapper, new NoTx());
        ServiceException e = assertThrows(ServiceException.class, () -> l.run("p", 5, RID, "s", SHA, () -> Map.of()));
        assertEquals(409, e.getCode());
    }

    static class AppOriginalRequestLedgerMapperStub {
        final AppOriginalRequestMapper mock = mock(AppOriginalRequestMapper.class);
        AppOriginalRequestLedgerMapperStub() {
            when(mock.insertIgnore(any())).thenAnswer(a -> { ((Map<String, Object>) a.getArgument(0)).put("id", 77L); return 1; });
            when(mock.finish(anyLong(), anyString(), any(), any(), any())).thenReturn(1);
        }
    }
}
