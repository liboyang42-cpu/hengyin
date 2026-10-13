package com.chengyinhub.quartz.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit-only JDBC session model. The separate MySQL IT checks server and Druid behaviour. */
public final class SchedulerJdbcFixture {
    public final DataSource source = mock(DataSource.class);
    public final Map<String, Integer> locks = new HashMap<>();
    public final Map<Integer, Connection> connections = new HashMap<>();
    public final AtomicInteger ids = new AtomicInteger();
    public boolean abortFails;
    public SchedulerJdbcFixture() {
        try { when(source.getConnection()).thenAnswer(call -> connection()); }
        catch (SQLException impossible) { throw new AssertionError(impossible); }
    }
    private Connection connection() throws SQLException {
        int id = ids.incrementAndGet();
        Connection connection = mock(Connection.class); connections.put(id, connection);
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0); PreparedStatement query = mock(PreparedStatement.class);
            String[] name = new String[1];
            doAnswer(set -> { name[0] = set.getArgument(1); return null; }).when(query).setString(eq(1), anyString());
            when(query.executeQuery()).thenAnswer(execute -> {
                ResultSet result = mock(ResultSet.class); when(result.next()).thenReturn(true, false);
                if (sql.equals("SELECT DATABASE()")) { when(result.getString(1)).thenReturn("task_schema"); return result; }
                long value;
                synchronized (locks) {
                    Integer owner = locks.get(name[0]);
                    if (sql.startsWith("SELECT GET_LOCK")) {
                        value = owner == null || owner == id ? 1 : 0;
                        if (value == 1) locks.put(name[0], id);
                    } else if (sql.startsWith("SELECT RELEASE_LOCK")) {
                        value = owner != null && owner == id ? 1 : 0;
                        if (value == 1) locks.remove(name[0]);
                    } else value = owner != null && owner == id ? 1 : 0;
                }
                when(result.getLong(1)).thenReturn(value); when(result.wasNull()).thenReturn(false); return result;
            });
            return query;
        });
        doAnswer(call -> {
            if (abortFails) throw new SQLException("unit-abort-failed");
            synchronized (locks) { locks.entrySet().removeIf(entry -> entry.getValue() == id); }
            return null;
        }).when(connection).abort(any());
        return connection;
    }
    public void loseAllSessions() { synchronized (locks) { locks.clear(); } }
}
