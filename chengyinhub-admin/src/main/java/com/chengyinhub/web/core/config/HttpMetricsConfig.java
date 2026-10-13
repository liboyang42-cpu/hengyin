package com.chengyinhub.web.core.config;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

import java.util.Arrays;

@Configuration(proxyBeanMethods = false)
public class HttpMetricsConfig {
    @Bean
    public MeterFilter boundedHttpMethodTags() {
        return new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                String method = id.getTag("method");
                if ("http.server.requests".equals(id.getName()) && method != null
                        && Arrays.stream(HttpMethod.values()).noneMatch(value -> value.name().equals(method)) && !"CONNECT".equals(method)) {
                    return id.withTag(Tag.of("method", "UNKNOWN"));
                }
                return id;
            }
        };
    }
}
