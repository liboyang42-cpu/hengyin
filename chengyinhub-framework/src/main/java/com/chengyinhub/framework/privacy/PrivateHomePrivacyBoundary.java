package com.chengyinhub.framework.privacy;

import com.chengyinhub.business.home.PrivateHomeLogScope;
import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Includes binding/filter errors and the service transaction/commit, including non-HTTP callers. */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PrivateHomePrivacyBoundary extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(PrivateHomePrivacyBoundary.class);
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI().substring(request.getContextPath().length());
        }
        path = path.replaceAll(";[^/]*", "");
        return !(path.equals("/api/native/home") || path.startsWith("/api/native/home/"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                             FilterChain chain) throws ServletException, IOException {
        long start = System.nanoTime();
        try (PrivateHomeLogScope ignored = PrivateHomeLogScope.open()) {
            response.setHeader("Cache-Control", "no-store"); response.setHeader("Pragma", "no-cache");
            try { chain.doFilter(request, response); }
            catch (Throwable error) {
                LOG.error("private-home boundary failure", error);
                // Containers may log escaping exceptions after this thread scope has closed.
                // Never propagate a request/SQL-bearing message, cause or suppressed chain.
                if (error instanceof Error) throw safeError((Error) error);
                if (error instanceof IOException) throw new IOException("PRIVATE_HOME_REQUEST_FAILED");
                throw new ServletException("PRIVATE_HOME_REQUEST_FAILED");
            }
        } finally {
            // Never use URI, query, body, label, owner, request ID or exception message here.
            PrivateHomeLogFilter.httpSummary(response.getStatus(), elapsed(start));
        }
    }
    @Around("execution(public * com.chengyinhub.business.home.PrivateHomeService.*(..))")
    public Object service(ProceedingJoinPoint invocation) throws Throwable {
        long start = System.nanoTime(); boolean succeeded = false;
        try (PrivateHomeLogScope ignored = PrivateHomeLogScope.open()) {
            try {
                Object value = invocation.proceed(); succeeded = true; return value;
            } catch (Throwable error) {
                LOG.error("private-home service failure", error);
                if (error instanceof Error) throw safeError((Error) error);
                String code = error instanceof com.chengyinhub.common.exception.ServiceException ? error.getMessage() : null;
                if (code == null || !code.matches("(?:HOME|RESOURCE|REQUEST|ARRIVAL|AUTHORITY)_[A-Z_]+")) code = "HOME_UNAVAILABLE";
                // Internal callers may log failures after this scope closes; carry no original cause/data.
                throw new com.chengyinhub.common.exception.ServiceException(code);
            }
        } finally {
            PrivateHomeLogFilter.serviceSummary(invocation.getSignature().getName(), succeeded, elapsed(start));
        }
    }
    private static Error safeError(Error error) {
        if (error instanceof ThreadDeath) return new ThreadDeath();
        if (error instanceof VirtualMachineError) return new PrivateHomeVirtualMachineError();
        return new Error("PRIVATE_HOME_FAILED");
    }
    private static final class PrivateHomeVirtualMachineError extends VirtualMachineError {
        private PrivateHomeVirtualMachineError() { super("PRIVATE_HOME_FAILED"); }
    }
    private static long elapsed(long start) { return (System.nanoTime() - start) / 1000000L; }
}
