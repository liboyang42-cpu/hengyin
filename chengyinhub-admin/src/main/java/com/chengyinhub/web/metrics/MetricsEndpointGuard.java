package com.chengyinhub.web.metrics;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * R9 BE-27: the Prometheus scrape endpoint is never served on the public application port.
 *
 * <ul>
 *   <li>Main port: a first-in-chain filter answers 404 for {@code /actuator/prometheus}, before Spring Security.
 *       Filters of the main context do not run on a separate management server, so this never hides the real scrape.</li>
 *   <li>Management port (only when {@code CHENGYIN_MANAGEMENT_PORT} differs from {@code server.port}, bound to
 *       {@code CHENGYIN_MANAGEMENT_ADDRESS}, default 127.0.0.1): a dedicated security chain lets the scraper read
 *       {@code /actuator/prometheus} without an app login. It matches on the local port, so the same path on the main
 *       port never reaches it.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class MetricsEndpointGuard
{
    public static final String PROMETHEUS_PATH = "/actuator/prometheus";

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> prometheusMainPortGuard()
    {
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(new MainPortFilter());
        registration.addUrlPatterns(PROMETHEUS_PATH, PROMETHEUS_PATH + "/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("prometheusMainPortGuard");
        return registration;
    }

    /** Scraper access on the management port only. Ordered before the application chain. */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain prometheusManagementPortChain(HttpSecurity http,
            @Value("${server.port:8080}") int serverPort,
            @Value("${management.server.port:${server.port:8080}}") int managementPort) throws Exception
    {
        return http
                .securityMatcher(request -> isManagementScrape(request, serverPort, managementPort))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }

    static boolean isManagementScrape(HttpServletRequest request, int serverPort, int managementPort)
    {
        if (managementPort == serverPort || managementPort <= 0) return false;
        String path = request.getRequestURI();
        return request.getLocalPort() == managementPort && path != null
                && (path.equals(PROMETHEUS_PATH) || path.startsWith(PROMETHEUS_PATH + "/"));
    }

    static final class MainPortFilter extends OncePerRequestFilter
    {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException
        {
            // Written directly: sendError would re-dispatch to /error, where the app chain answers 401 instead.
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":404,\"msg\":\"请求路径不存在\"}");
        }
    }
}
