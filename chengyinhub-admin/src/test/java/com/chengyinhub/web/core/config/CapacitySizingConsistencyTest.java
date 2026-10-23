package com.chengyinhub.web.core.config;

import com.chengyinhub.framework.config.ThreadPoolConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * O-17 容量与依赖恢复的静态一致性检查：只读打包进 jar 的 application.yml / application-druid.yml 默认值
 * （${ENV:默认} 取默认），不连库、不改配置。线上若用环境变量覆盖，覆盖值须同样满足这些不等式。
 *
 * <p>不等式的依据见 docs/delivery/doc2-o11-o17.md「O-17」；数值不是压测结论。
 */
class CapacitySizingConsistencyTest {
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{[A-Z0-9_]+:(.*)}$");
    private static final Path CLIENT = Paths.get("../chengyinhub-xcx/utils/transport/request-client.js");
    private static final Path SMOKE = Paths.get("../ci/upgrade-startup-smoke.sh");
    private static final Path POOL_PROBE = Paths.get("../ci/pool_recovery_probe.py");

    private final Properties config = load();

    @Test
    void druidPoolIsBoundedAndFailsFastBeforeTheMiniProgramGivesUp() {
        int maxActive = intValue("spring.datasource.druid.maxActive");
        int minIdle = intValue("spring.datasource.druid.minIdle");
        int initialSize = intValue("spring.datasource.druid.initialSize");
        long maxWait = longValue("spring.datasource.druid.maxWait");
        long socketTimeout = longValue("spring.datasource.druid.socketTimeout");
        long clientTimeout = miniProgramDefaultTimeoutMs();

        assertTrue(maxActive > 0 && minIdle <= maxActive && initialSize <= maxActive, "池上下限不自洽");
        // 池满时必须有限等待（负值 = 无限等待，F-W3-05 的整站卡死根因），并在客户端超时一半以内失败，
        // 留出处理与网络时间，客户端拿到的是明确的 500 而不是自己的超时。
        assertTrue(maxWait > 0 && maxWait * 2 <= clientTimeout,
                "Druid maxWait=" + maxWait + " 须 >0 且 ≤ 小程序默认超时 " + clientTimeout + " 的一半");
        assertTrue(socketTimeout >= maxWait, "socketTimeout 不应短于取连接等待");
    }

    @Test
    void requestThreadsAndQueueStayWithinTenTimesTheConnectionPool() {
        int maxActive = intValue("spring.datasource.druid.maxActive");
        int threads = intValue("server.tomcat.threads.max");
        int acceptCount = intValue("server.tomcat.accept-count");
        int minSpare = intValue("server.tomcat.threads.min-spare");
        // application.yml 注释：线程收敛到「DB 池的 10 倍」量级；超过则高峰时大量线程排队等连接（F-W3-05/F-L6-13）。
        assertTrue(threads >= maxActive, "请求线程少于连接数会让连接池闲置");
        assertTrue(threads <= 10 * maxActive, "tomcat threads.max=" + threads + " 超过 10×maxActive=" + 10 * maxActive);
        assertTrue(acceptCount <= 10 * maxActive, "accept-count=" + acceptCount + " 超过 10×maxActive");
        assertTrue(minSpare <= threads, "min-spare 不能大于 max");
    }

    @Test
    void redisWaitsAreFiniteAndShorterThanTheClientTimeout() {
        long clientTimeout = miniProgramDefaultTimeoutMs();
        long poolWait = DurationStyle.detectAndParse(value("spring.data.redis.lettuce.pool.max-wait")).toMillis();
        long commandTimeout = DurationStyle.detectAndParse(value("spring.data.redis.timeout")).toMillis();
        int redisMaxActive = intValue("spring.data.redis.lettuce.pool.max-active");
        assertTrue(poolWait > 0 && poolWait < clientTimeout, "Redis 池等待须有限（-1 = 无限阻塞）且短于客户端超时");
        assertTrue(commandTimeout > 0 && commandTimeout < clientTimeout, "Redis 命令超时须有限且短于客户端超时");
        assertTrue(redisMaxActive > 0, "Redis 池上限须为正");
    }

    @Test
    void asyncExecutorIsBoundedAndPushesBackInsteadOfDropping() {
        ThreadPoolTaskExecutor executor = new ThreadPoolConfig().threadPoolTaskExecutor();
        executor.initialize();
        try {
            ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
            assertTrue(pool.getCorePoolSize() <= pool.getMaximumPoolSize());
            assertTrue(executor.getQueueCapacity() > 0 && executor.getQueueCapacity() < Integer.MAX_VALUE, "队列须有界");
            assertTrue(pool.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.CallerRunsPolicy,
                    "满载时由调用方执行（背压），不能静默丢任务");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void poolRecoveryGateExercisesTheShippedPoolDefaults() {
        String smoke = read(SMOKE);
        String probe = read(POOL_PROBE);
        String maxActive = "--spring.datasource.druid.maxActive=" + intValue("spring.datasource.druid.maxActive");
        String maxWait = "--spring.datasource.druid.maxWait=" + longValue("spring.datasource.druid.maxWait");
        String socket = "--spring.datasource.druid.socketTimeout=" + longValue("spring.datasource.druid.socketTimeout");
        for (String token : new String[] {maxActive, maxWait, socket}) {
            assertTrue(smoke.contains(token), "连接池恢复门禁的启动参数须等于打包默认值: " + token);
            assertTrue(probe.contains("'" + token + "'"), "探针校验的启动参数须等于打包默认值: " + token);
        }
        assertTrue(smoke.contains("ci/pool_recovery_probe.py\" \"$RUN_NAME\" \"$OUT\""),
                "upgrade-startup-smoke.sh 必须真正调用连接池探针，否则 Isolated pool recovery 只是启动冒烟");
    }

    private long miniProgramDefaultTimeoutMs() {
        Matcher matcher = Pattern.compile("Number\\(deps\\.defaultTimeoutMs\\)\\s*\\|\\|\\s*(\\d+)").matcher(read(CLIENT));
        assertTrue(matcher.find(), "找不到小程序默认请求超时");
        return Long.parseLong(matcher.group(1));
    }

    private int intValue(String key) {
        return Integer.parseInt(value(key));
    }

    private long longValue(String key) {
        return Long.parseLong(value(key));
    }

    private String value(String key) {
        String raw = config.getProperty(key);
        assertNotNull(raw, "缺少配置 " + key);
        Matcher matcher = PLACEHOLDER.matcher(raw.trim());
        return matcher.matches() ? matcher.group(1) : raw.trim();
    }

    private static Properties load() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"), new ClassPathResource("application-druid.yml"));
        return yaml.getObject();
    }

    private static String read(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError("读取失败: " + path, e);
        }
    }
}
