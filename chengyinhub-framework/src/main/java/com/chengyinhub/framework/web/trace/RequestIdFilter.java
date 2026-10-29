package com.chengyinhub.framework.web.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.chengyinhub.common.utils.LogCorrelation;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 请求 ID(F-L6-10):每个请求在日志 MDC 里带 requestId,并通过 X-Request-Id 响应头回传,
 * 用户报障时可按同一个 ID 关联前后端日志。客户端传入的值只在格式安全时沿用(防日志注入),否则重新生成。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolve(request.getHeader(HEADER));
        try (LogCorrelation.Scope ignored = LogCorrelation.open(Map.of(MDC_KEY, requestId))) {
            response.setHeader(HEADER, requestId);
            chain.doFilter(request, response);
        }
    }

    static String resolve(String incoming) {
        if (incoming != null && SAFE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
