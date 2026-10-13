package com.chengyinhub.web.task.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import com.chengyinhub.business.metrics.MetricSample;

/**
 * TomcatMetricsProbe 依赖内嵌 Tomcat 的 ThreadPool MBean,而该注册表 Spring Boot 默认关闭
 * (server.tomcat.mbeanregistry.enabled,官方为省内存关的)。
 *
 * 2026-08-01 生产实证:因为没开这个开关,探针自上线起每轮必抛
 * IllegalStateException("tomcat thread pool mbean missing"),每次吐 3 条 ERROR + 堆栈,
 * 把日志刷到 3.1GB 并让告警通道恒响。application.yml 已置 enabled: true。
 *
 * 两条断言各锁一半:
 *   ① 开关开着时,probe 查的 ObjectName 与 Tomcat 实际注册的 MBean 对得上(否则改 probe 也白改);
 *   ② application.yml 真的把开关打开了(删掉那行即红 —— 这是防回归的那道闸)。
 *
 * ⚠️ 为什么没有「关掉开关则必失败」的反向 case:Tomcat 的 Registry 启停是**全局静态且不可逆**的
 * —— 同一 JVM 里先跑哪个方向,另一个方向就固定失灵(两边都实测过)。这种受全局状态摆布的
 * 测试会假红/假绿,比没有更坏。反向证据来自生产本身:修复前它每轮都在失败。
 */
class TomcatMetricsProbeMBeanRegistryTest {

    @Test
    void 开启MBean注册时能采到线程池指标() {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(0);
        factory.setDisableMBeanRegistry(false);
        WebServer server = factory.getWebServer();
        server.start();
        try {
            Date now = new Date();
            List<MetricSample> samples = new TomcatMetricsProbe().sample(now, now, now);
            assertEquals(2, samples.size(), "应采到 busy 与 current 两个指标");
            assertTrue(samples.stream().anyMatch(s -> "tomcat.threads.busy".equals(s.getMetricKey())));
            assertTrue(samples.stream().anyMatch(s -> "tomcat.threads.current".equals(s.getMetricKey())));
        } finally {
            server.stop();
        }
    }

    /**
     * 走 Spring 真正的属性绑定,而不是把 YAML 当 Map 查字符串键 —— 后者只能抓到「整行被删」,
     * 属性名写错(如 mbean-registry)或挪错层级时它照样绿,而生产照旧恒失败。
     * 这里绑到 TomcatServerProperties 本尊:名字对不上就绑不进来,isEnabled() 即为 false。
     */
    @Test
    void application_yml必须真能绑出mbeanregistry开启() {
        StandardEnvironment environment = new StandardEnvironment();
        List<PropertySource<?>> sources;
        try {
            sources = new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml"));
        } catch (Exception e) {
            throw new IllegalStateException("读不到 application.yml", e);
        }
        assertTrue(!sources.isEmpty(), "application.yml 未加载出任何属性源");
        sources.forEach(environment.getPropertySources()::addLast);

        TomcatServerProperties server = Binder.get(environment)
                .bind("server.tomcat", TomcatServerProperties.class)
                .orElseThrow(() -> new AssertionError("application.yml 里绑不出 server.* 配置"));

        assertEquals(true, server.getMbeanregistry().isEnabled(),
                "server.tomcat.mbeanregistry.enabled 必须为 true,否则 TomcatMetricsProbe 每轮必失败并刷屏");
    }
}
