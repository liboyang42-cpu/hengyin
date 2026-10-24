package com.chengyinhub.quartz.util;

import com.chengyinhub.quartz.service.SchedulerJdbcFixture;
import java.sql.Connection;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MySqlSessionLockTest {
    @Test void explicitReleaseTerminatesTheSamePhysicalSessionAndSchemasHaveSeparateKeys() throws Exception {
        SchedulerJdbcFixture jdbc = new SchedulerJdbcFixture();
        MySqlSessionLock.Lease lease = MySqlSessionLock.acquire(jdbc.source, "leader", "all", 0);
        assertNotNull(lease); assertTrue(lease.isOwned()); assertEquals(1, jdbc.ids.get());
        Connection physical = jdbc.connections.get(1); lease.close(); lease.close();
        verify(physical, times(1)).abort(any()); verify(physical, times(1)).close(); assertFalse(lease.isOwned());
        assertNotEquals(MySqlSessionLock.key("leader", "one", "all"), MySqlSessionLock.key("leader", "two", "all"));
        assertTrue(MySqlSessionLock.key("task", "schema", "x".repeat(1000)).length() <= 64);
    }
    @Test void configurationMutexSurvivesResourceCloseUntilTransactionCompletion() throws Exception {
        SchedulerJdbcFixture jdbc = new SchedulerJdbcFixture();
        TransactionSynchronizationManager.initSynchronization(); TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            MySqlSessionLock.Lease lease = MySqlSessionLock.acquire(jdbc.source, "jobdef", "definitions", 0).untilTransactionCompletion();
            lease.close(); assertTrue(lease.isOwned()); verify(jdbc.connections.get(1), never()).abort(any());
            assertNull(MySqlSessionLock.acquire(jdbc.source, "jobdef", "definitions", 0));
            for (TransactionSynchronization callback : TransactionSynchronizationManager.getSynchronizations())
                callback.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            assertFalse(lease.isOwned()); verify(jdbc.connections.get(1)).abort(any());
            try (MySqlSessionLock.Lease next = MySqlSessionLock.acquire(jdbc.source, "jobdef", "definitions", 0)) { assertNotNull(next); }
        } finally { TransactionSynchronizationManager.clearSynchronization(); TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
    @Test void unknownPhysicalTerminationMustNotRecycleTheConnection() throws Exception {
        SchedulerJdbcFixture jdbc = new SchedulerJdbcFixture();
        MySqlSessionLock.Lease lease = MySqlSessionLock.acquire(jdbc.source, "task", "target", 0);
        jdbc.abortFails = true; jdbc.loseAllSessions(); lease.close();
        verify(jdbc.connections.get(1)).abort(any()); verify(jdbc.connections.get(1), never()).close(); assertFalse(lease.isOwned());
    }
}
