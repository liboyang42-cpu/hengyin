package com.chengyinhub.web.core.config;

import com.chengyinhub.common.observability.CriticalPathCode;
import com.chengyinhub.common.observability.CriticalPathEvents;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * O-11：把关键路径失败事件的进程内计数暴露为 Actuator 指标 chengyin.critical.path.failures{code,kind}。
 * 标签只取枚举（7 个码 × 2 种 kind），基数固定；只注册指标，不改 management 暴露范围。
 */
@Configuration(proxyBeanMethods = false)
public class CriticalPathMetrics {
    public static final String METER = "chengyin.critical.path.failures";

    @Bean
    public MeterBinder criticalPathFailureMeters() {
        return CriticalPathMetrics::bind;
    }

    static void bind(MeterRegistry registry) {
        for (CriticalPathCode code : CriticalPathCode.values()) {
            for (CriticalPathEvents.Kind kind : CriticalPathEvents.Kind.values()) {
                FunctionCounter.builder(METER, code, c -> CriticalPathEvents.count(c, kind))
                        .tag("code", code.name())
                        .tag("kind", kind.name())
                        .description("关键路径失败事件累计次数（O-11）")
                        .register(registry);
            }
        }
    }
}
