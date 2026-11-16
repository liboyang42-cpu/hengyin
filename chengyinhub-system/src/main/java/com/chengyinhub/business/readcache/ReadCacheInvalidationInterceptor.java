package com.chengyinhub.business.readcache;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Invalidates the read cache on every MyBatis write to a source table ({@link ReadCacheNamespace#TABLES}),
 * whichever mapper or service made it, after the transaction commits. Reads and other tables are untouched.
 */
@Intercepts({@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})})
public class ReadCacheInvalidationInterceptor implements Interceptor {
    /** The table a single INSERT / REPLACE / UPDATE / DELETE statement writes. */
    private static final Pattern TARGET = Pattern.compile(
            "^\\s*(?:/\\*.*?\\*/\\s*)*(?:"
                    + "update\\s+(?:low_priority\\s+)?(?:ignore\\s+)?"
                    + "|insert\\s+(?:low_priority\\s+|delayed\\s+|high_priority\\s+)?(?:ignore\\s+)?(?:into\\s+)?"
                    + "|replace\\s+(?:low_priority\\s+|delayed\\s+)?(?:into\\s+)?"
                    + "|delete\\s+(?:low_priority\\s+)?(?:quick\\s+)?(?:ignore\\s+)?from\\s+)"
                    + "`?([A-Za-z0-9_$]+)`?(?:\\s*\\.\\s*`?([A-Za-z0-9_$]+)`?)?",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final ReadCache cache;

    public ReadCacheInvalidationInterceptor(ReadCache cache) {
        this.cache = cache;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object result = invocation.proceed();
        try {
            Object[] args = invocation.getArgs();
            MappedStatement ms = (MappedStatement) args[0];
            SqlCommandType type = ms.getSqlCommandType();
            if (type == SqlCommandType.INSERT || type == SqlCommandType.UPDATE || type == SqlCommandType.DELETE) {
                String ns = namespaceOf(ms.getBoundSql(args[1]).getSql());
                if (ns != null) cache.invalidateAfterCommit(ns);
            }
        } catch (RuntimeException e) {
            cache.failed("invalidate-hook", "?", e);
        }
        return result;
    }

    /** Namespace written by {@code sql}, or null. Package-visible for tests. */
    static String namespaceOf(String sql) {
        if (sql == null) return null;
        Matcher m = TARGET.matcher(sql.length() > 400 ? sql.substring(0, 400) : sql);
        if (!m.find()) return null;
        return ReadCacheNamespace.forTable(m.group(2) != null ? m.group(2) : m.group(1));
    }
}
