package com.chengyinhub.framework.privacy;

import com.alibaba.druid.filter.stat.StatFilter;
import com.alibaba.druid.proxy.jdbc.StatementProxy;
import java.sql.SQLException;
import java.util.Locale;

/** Retains Druid SQL statistics/timing, but never retains private-home bind values or raw errors. */
public final class PrivateHomeStatFilter extends StatFilter {
    static boolean privateSql(String sql) {
        return sql != null && sql.toLowerCase(Locale.ROOT).contains("player_private_home");
    }
    @Override protected String buildSlowParameters(StatementProxy statement) {
        return privateSql(statement.getLastExecuteSql()) ? "[\"private-home:redacted\"]" : super.buildSlowParameters(statement);
    }
    @Override protected void statement_executeErrorAfter(StatementProxy statement, String sql, Throwable error) {
        if (!privateSql(sql)) { super.statement_executeErrorAfter(statement, sql, error); return; }
        // Druid keeps lastError in SQL/connection/statement statistics, outside the logging pipeline.
        super.statement_executeErrorAfter(statement, sql, safeError(error));
    }
    static SQLException safeError(Throwable error) {
        SQLException original = error instanceof SQLException ? (SQLException) error : null;
        String state = original == null ? null : original.getSQLState();
        if (state != null && !state.matches("[A-Z0-9]{5}")) state = null;
        SQLException safe = new SQLException("PRIVATE_HOME_SQL_ERROR", state, original == null ? 0 : original.getErrorCode());
        safe.setStackTrace(new StackTraceElement[0]);
        return safe;
    }
}
