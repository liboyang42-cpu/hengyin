package com.chengyinhub.framework.web.exception;

import com.chengyinhub.business.service.impl.AlarmService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Runtime failures use fixed event keys; no URL, headers, SQL or exception message leaves the request. */
@Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 20)
public class RuntimeFailureAlarmFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RuntimeFailureAlarmFilter.class);
    private final AlarmService alarms;
    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.metrics.HttpRequestMetrics metrics;

    public RuntimeFailureAlarmFilter(AlarmService alarms) { this.alarms = alarms; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (RuntimeException | ServletException failure) {
            systemFailure(failure);
            throw failure;
        } finally {
            if (metrics != null) metrics.recordResponse(response.getStatus());
            if (response.getStatus() >= 500) {
                report("runtime.http.5xx", "HTTP服务失败", "status=" + response.getStatus());
            }
        }
    }

    public void systemFailure(Throwable failure) {
        if (metrics != null) metrics.recordSystemException();
        if (!connectionFailure(failure)) report("runtime.request.unhandled", "服务请求异常", "type=" + failure.getClass().getSimpleName());
    }

    public boolean connectionFailure(Throwable failure) {
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            String type = failure.getClass().getName();
            if (type.equals("com.alibaba.druid.pool.GetConnectionTimeoutException")
                    || failure instanceof org.springframework.jdbc.CannotGetJdbcConnectionException
                    || failure instanceof java.sql.SQLTransientConnectionException) {
                report("runtime.db.connection", "数据库连接异常", "type=" + failure.getClass().getSimpleName());
                return true;
            }
            if (failure instanceof org.springframework.data.redis.RedisConnectionFailureException
                    || type.equals("org.springframework.data.redis.connection.PoolException")
                    || type.equals("io.lettuce.core.RedisConnectionException")) {
                report("runtime.redis.connection", "缓存连接异常", "type=" + failure.getClass().getSimpleName());
                return true;
            }
        }
        return false;
    }

    private void report(String key, String title, String detail) {
        try { alarms.alarmThrottled(key, title, detail); }
        catch (RuntimeException failure) { log.error("Runtime alarm failed event={} type={}", key, failure.getClass().getSimpleName()); }
    }
}
