package com.chengyinhub.quartz.util;

import com.chengyinhub.common.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** A pinned MySQL session, never a transaction-scoped lock or a pooled close-only lease. */
public final class MySqlSessionLock {
    private static final Logger log = LoggerFactory.getLogger(MySqlSessionLock.class);
    private MySqlSessionLock() { }

    public static String key(String kind, String schema, String target) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((schema + "\n" + target).getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder("cy:").append(kind).append(':');
            for (int i = 0; i < 24; i++) value.append(String.format("%02x", hash[i] & 255));
            return value.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    public static Lease acquire(DataSource source, String kind, String target, int waitSeconds) {
        Connection connection = null;
        try {
            connection = source.getConnection();
            // Druid's abort delegates to the physical connection. A lost/unknown session must not return to its pool alive.
            connection.setNetworkTimeout(Runnable::run, 5000);
            String schema;
            try (PreparedStatement query = connection.prepareStatement("SELECT DATABASE()")) {
                query.setQueryTimeout(3);
                try (ResultSet row = query.executeQuery()) {
                    if (!row.next() || (schema = row.getString(1)) == null || schema.isEmpty())
                        throw new SQLException("Scheduling needs a selected master schema");
                }
            }
            Lease lease = new Lease(connection, key(kind, schema, target));
            Long acquired = lease.scalar("SELECT GET_LOCK(?, ?)", waitSeconds);
            if (Long.valueOf(1).equals(acquired)) return lease;
            lease.closeNow();
            if (Long.valueOf(0).equals(acquired)) return null;
            throw new SQLException("Named lock acquisition is unknown");
        } catch (SQLException failure) {
            terminate(connection);
            throw new ServiceException("调度数据库会话不可用");
        }
    }

    private static void terminate(Connection connection) {
        if (connection == null) return;
        try {
            connection.abort(Runnable::run);
        } catch (SQLException abortFailure) {
            // Retain the handle instead of recycling a session whose named locks are unknown.
            log.error("调度会话物理终止失败，连接不归还池", abortFailure);
            return;
        }
        try { connection.close(); } catch (SQLException closeFailure) { log.warn("调度会话关闭失败", closeFailure); }
    }

    public static final class Lease implements AutoCloseable {
        private final Connection connection;
        private final String name;
        private boolean closed;
        private boolean deferred;

        private Lease(Connection connection, String name) { this.connection = connection; this.name = name; }

        private synchronized Long scalar(String sql, Integer wait) throws SQLException {
            if (closed) throw new SQLException("Lock session is closed");
            try (PreparedStatement query = connection.prepareStatement(sql)) {
                query.setQueryTimeout(3);
                query.setString(1, name);
                if (wait != null) query.setInt(2, wait);
                try (ResultSet row = query.executeQuery()) {
                    if (!row.next()) throw new SQLException("Missing lock result");
                    long value = row.getLong(1);
                    return row.wasNull() ? null : value;
                }
            }
        }

        public synchronized boolean isOwned() {
            try { return Long.valueOf(1).equals(scalar("SELECT IS_USED_LOCK(?) = CONNECTION_ID()", null)); }
            catch (SQLException failure) { return false; }
        }

        public synchronized Lease untilTransactionCompletion() {
            if (TransactionSynchronizationManager.isActualTransactionActive()
                    && TransactionSynchronizationManager.isSynchronizationActive()) {
                deferred = true;
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) { closeNow(); }
                });
            }
            return this;
        }

        @Override public synchronized void close() { if (!deferred) closeNow(); }

        private synchronized void closeNow() {
            if (closed) return;
            try { scalar("SELECT RELEASE_LOCK(?)", null); }
            catch (SQLException failure) { log.warn("调度锁释放结果未知，将终止物理会话"); }
            finally { closed = true; terminate(connection); }
        }
    }
}
