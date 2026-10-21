package com.chengyinhub.framework.privacy;

import com.alibaba.druid.pool.DruidAbstractDataSource;
import com.alibaba.druid.pool.DruidConnectionHolder;
import com.alibaba.druid.pool.DruidPooledConnection;
import com.alibaba.druid.spring.boot4.autoconfigure.DruidDataSourceWrapper;
import com.chengyinhub.business.home.PrivateHomeLogScope;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import javax.sql.ConnectionEvent;
import javax.sql.ConnectionEventListener;

/** Druid 1.2.23 pool error boundary; the StatFilter alone cannot protect dataSource.lastError. */
public final class PrivateHomeDruidDataSource extends DruidDataSourceWrapper {
    private static final AtomicLongFieldUpdater<DruidAbstractDataSource> ERRORS =
        AtomicLongFieldUpdater.newUpdater(DruidAbstractDataSource.class, "errorCount");

    @Override public void handleConnectionException(DruidPooledConnection connection, Throwable error, String sql) throws SQLException {
        if (!PrivateHomeLogScope.active() && !PrivateHomeStatFilter.privateSql(sql)) {
            super.handleConnectionException(connection, error, sql); return;
        }
        DruidConnectionHolder holder = connection.getConnectionHolder();
        if (holder == null) return;
        // Keep the pinned upstream ordering and counters, but no raw exception is ever assigned
        // to lastError, connection listeners, lastFatalError or the pooled connection's disable cause.
        ERRORS.incrementAndGet(this);
        SQLException safe = PrivateHomeStatFilter.safeError(error);
        lastError = safe; lastErrorTimeMillis = System.currentTimeMillis();
        if (error instanceof SQLException) {
            ConnectionEvent event = new ConnectionEvent(connection, safe);
            for (ConnectionEventListener listener : holder.getConnectionEventListeners()) listener.connectionErrorOccurred(event);
            // Classify using the ORIGINAL vendor exception so message/type-based fatal detection
            // is unchanged. Only the sanitized error reaches retention and fatal-discard handling.
            if (exceptionSorter != null && exceptionSorter.isExceptionFatal((SQLException) error))
                handleFatalError(connection, safe, "PRIVATE_HOME_SQL");
        }
        throw safe;
    }
}
