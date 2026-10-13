package com.chengyinhub.upgrade;

import com.chengyinhub.framework.config.RedisConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SupportedStackCacheAutoConfigurationTest {
    @Test
    void existingRedisConfigSelectsRedisCacheManagerWithoutOpeningConnections() {
        RedisConnectionFactory connection = mock(RedisConnectionFactory.class);
        new ApplicationContextRunner()
                .withBean(RedisConnectionFactory.class, () -> connection)
                .withUserConfiguration(RedisConfig.class)
                .withConfiguration(AutoConfigurations.of(CacheAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(CacheManager.class);
                    assertThat(context.getBean(CacheManager.class)).isInstanceOf(RedisCacheManager.class);
                    verifyNoInteractions(connection);
                });
    }

    @Test
    void missingCacheAutoConfigurationReproducesStartupFailure() {
        RedisConnectionFactory connection = mock(RedisConnectionFactory.class);
        new ApplicationContextRunner()
                .withBean(RedisConnectionFactory.class, () -> connection)
                .withUserConfiguration(RedisConfig.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("CacheManager");
                    verifyNoInteractions(connection);
                });
    }
}
