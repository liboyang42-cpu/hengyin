package com.chengyinhub.security;

import com.chengyinhub.web.core.config.HttpMetricsConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcObservationAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import io.micrometer.core.instrument.Tag;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class HttpMetricsCardinalityTest {
    private static final String[] STANDARD = {"GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "OPTIONS", "TRACE", "PATCH"};

    @Test
    void customMethodsAreBoundedAndStandardMethodsAndOtherMetersRemainDistinct() {
        try (ServletWebServerApplicationContext context = start()) {
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            for (int i = 0; i < 1000; i++) {
                record(registry, "CUSTOM" + i);
            }
            for (String method : STANDARD) {
                record(registry, method);
                assertNotNull(registry.find("http.server.requests").tag("method", method).timer());
            }
            assertEquals(10, registry.find("http.server.requests").timers().size());
            assertEquals(1000, registry.get("http.server.requests").tag("method", "UNKNOWN").timer().count());
            Timer.builder("other.metric").tag("method", "application-method").register(registry);
            assertNotNull(registry.find("other.metric").tag("method", "application-method").timer());
        }
    }

    @Test
    void realLoopbackRequestsCannotCreateOneMeterPerCustomMethod() throws Exception {
        try (ServletWebServerApplicationContext context = start()) {
            assertFalse(context.getBean(TomcatServletWebServerFactory.class).isDisableMBeanRegistry(),
                    "独立 HTTP 夹具必须沿用生产的 MBean 注册配置，不能禁用同 JVM 后续探针");
            int port = context.getWebServer().getPort();
            for (int i = 0; i < 100; i++) {
                try (Socket socket = new Socket("127.0.0.1", port)) {
                    socket.setSoTimeout(5000);
                    socket.getOutputStream().write(("CUSTOM" + i + " /probe HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().flush();
                    String response = StreamUtils.copyToString(socket.getInputStream(), StandardCharsets.ISO_8859_1);
                    assertTrue(response.startsWith("HTTP/1.1 200"), response);
                }
            }
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            assertEquals(1, registry.find("http.server.requests").timers().size());
            assertEquals(100, registry.get("http.server.requests").tag("method", "UNKNOWN").timer().count());
        }
    }

    private static void record(MeterRegistry registry, String method) {
        Timer.builder("http.server.requests").tags(Collections.singletonList(Tag.of("method", method)))
                .register(registry).record(1, TimeUnit.MILLISECONDS);
    }

    private static ServletWebServerApplicationContext start() {
        SpringApplication application = new SpringApplication(TestApplication.class);
        application.setRegisterShutdownHook(false);
        // 与 application.yml 一致开启 Tomcat MBean 注册：默认关闭会全局不可逆地 disableRegistry，
        // 同一 JVM 中后跑的 TomcatMetricsProbeMBeanRegistryTest 将取不到线程池 MBean。
        return (ServletWebServerApplicationContext) application.run("--server.port=0", "--server.address=127.0.0.1",
                "--server.tomcat.mbeanregistry.enabled=true",
                "--spring.config.location=optional:classpath:/metrics-canary-does-not-exist.properties",
                "--server.tomcat.mbeanregistry.enabled=true",
                "--spring.main.banner-mode=off", "--logging.level.root=ERROR");
    }

    @Configuration(proxyBeanMethods = false)
    @Import({ProbeController.class, HttpMetricsConfig.class})
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, MetricsAutoConfiguration.class, SimpleMetricsExportAutoConfiguration.class,
            WebMvcObservationAutoConfiguration.class, ObservationAutoConfiguration.class})
    static class TestApplication { }

    @RestController
    static class ProbeController {
        @RequestMapping("/probe")
        String probe() { return "ok"; }
    }
}
