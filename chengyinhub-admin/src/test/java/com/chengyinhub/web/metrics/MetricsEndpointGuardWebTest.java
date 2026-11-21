package com.chengyinhub.web.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.common.core.domain.AjaxResult;

/**
 * R9 BE-27, on a real Tomcat with two ports: the scrape is served only on the management port (no login), the main
 * port answers 404 for it, health stays where it was, and envelope errors are counted.
 */
@SpringBootTest(classes = MetricsEndpointGuardWebTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "management.endpoints.web.exposure.include=health,info,prometheus",
                "management.server.address=127.0.0.1",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
                        + "org.springframework.boot.quartz.autoconfigure.QuartzAutoConfiguration,"
                        + "com.alibaba.druid.spring.boot4.autoconfigure.DruidDataSourceAutoConfigure,"
                        + "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration"})
class MetricsEndpointGuardWebTest
{
    private static final int MAIN = freePort();
    private static final int MANAGEMENT = freePort();
    private final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void ports(DynamicPropertyRegistry registry)
    {
        registry.add("server.port", () -> MAIN);
        registry.add("management.server.port", () -> MANAGEMENT);
    }

    private static int freePort()
    {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private HttpResponse<String> get(int port, String path) throws Exception
    {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void scrapeOnlyOnTheManagementPortAndEnvelopeErrorsAreCounted() throws Exception
    {
        assertEquals(401, get(MAIN, "/demo/refuse").statusCode(), "the stand-in app chain still requires a login");
        assertEquals(404, get(MAIN, "/actuator/prometheus").statusCode(), "never on the public port");

        HttpResponse<String> scrape = get(MANAGEMENT, "/actuator/prometheus");
        assertEquals(200, scrape.statusCode(), scrape.body());
        assertTrue(scrape.body().contains("jvm_memory"), "standard JVM metrics are exported");

        // health/info keep the application's own security rules on the management port (the stand-in chain wants a
        // login); only the scrape is opened, and only there.
        assertEquals(401, get(MANAGEMENT, "/actuator/health").statusCode());
        assertEquals(404, get(MAIN, "/actuator/prometheus/x").statusCode());
    }

    @Test
    void envelopeCodesBecomeLabelledCounters() throws Exception
    {
        App.open = true;
        try
        {
            get(MAIN, "/demo/refuse");
            get(MAIN, "/demo/refuse");
            get(MAIN, "/demo/ok");
            String plain = get(MAIN, "/demo/plain").body();
            assertTrue(plain.contains("\"errorKind\":\"SYSTEM\"") && plain.contains("\"code\":500"), plain);
        }
        finally
        {
            App.open = false;
        }
        String body = get(MANAGEMENT, "/actuator/prometheus").body();
        assertTrue(body.contains("cy_api_envelope_total{"), body.length() > 2000 ? body.substring(0, 2000) : body);
        assertTrue(body.lines().anyMatch(l -> l.startsWith("cy_api_envelope_total{") && l.contains("code_class=\"5xx\"")
                && l.contains("kind=\"BUSINESS\"") && l.contains("uri=\"/demo/refuse\"") && l.trim().endsWith(" 2.0")), "two refusals counted");
        assertTrue(body.lines().anyMatch(l -> l.startsWith("cy_api_envelope_total{") && l.contains("code_class=\"2xx\"")
                && l.contains("kind=\"NONE\"") && l.contains("uri=\"/demo/ok\"")), "a success counted");
        assertTrue(body.contains("http_server_requests_seconds_bucket"), "request latency histogram (SLO buckets) exported");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableWebSecurity
    @Import({MetricsEndpointGuard.class, ApiEnvelopeMetricsAdvice.class, App.Demo.class})
    static class App
    {
        static volatile boolean open;

        /** Stand-in for the application's catch-all chain: everything else needs a login (or the test switch). */
        @Bean
        @Order(Ordered.LOWEST_PRECEDENCE)
        SecurityFilterChain appChain(HttpSecurity http) throws Exception
        {
            return http.csrf(c -> c.disable())
                    .authorizeHttpRequests(r -> r.requestMatchers(request -> open).permitAll().anyRequest().authenticated())
                    .httpBasic(b -> { })
                    .build();
        }

        @RestController
        static class Demo
        {
            @GetMapping("/demo/refuse")
            AjaxResult refuse()
            {
                AjaxResult r = AjaxResult.error("活动未开始");
                r.put("errorKind", "BUSINESS");
                return r;
            }

            @GetMapping("/demo/plain")
            AjaxResult plain() { return AjaxResult.error("操作失败，请稍后重试"); }

            @GetMapping("/demo/ok")
            AjaxResult ok() { return AjaxResult.success(); }
        }
    }
}
