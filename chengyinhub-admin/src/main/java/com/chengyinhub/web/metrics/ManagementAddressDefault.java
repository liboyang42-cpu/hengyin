package com.chengyinhub.web.metrics;

import java.util.Map;
import java.util.Objects;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * R9 BE-27 default for the management address, applied only where Spring Boot accepts one.
 *
 * <p>The management server shares the application port unless {@code CHENGYIN_MANAGEMENT_PORT} moves it. Spring Boot
 * refuses to start when {@code management.server.address} is set and the management port is the application port
 * ("Management-specific server address cannot be configured ..."), so application.yml cannot carry a fixed
 * {@code address: 127.0.0.1}: with the default ports the jar would not start. This sets the address only when the
 * management port is separate: {@code CHENGYIN_MANAGEMENT_ADDRESS} when given, else {@code 127.0.0.1}, so the scrape
 * port is never public by default. An explicit {@code management.server.address} always wins.
 */
public class ManagementAddressDefault implements EnvironmentPostProcessor, Ordered
{
    static final String ADDRESS = "management.server.address";
    static final String ADDRESS_ENV = "CHENGYIN_MANAGEMENT_ADDRESS";
    static final String LOOPBACK = "127.0.0.1";
    static final String SOURCE = "chengyinManagementAddressDefault";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application)
    {
        String serverPort = environment.getProperty("server.port", "8080");
        String managementPort = environment.getProperty("management.server.port", serverPort);
        if (Objects.equals(serverPort.trim(), managementPort.trim()) || "-1".equals(managementPort.trim()))
        {
            return; // same port (or management HTTP off): Boot accepts no address here
        }
        if (hasText(environment.getProperty(ADDRESS)))
        {
            return;
        }
        String configured = environment.getProperty(ADDRESS_ENV);
        environment.getPropertySources().addLast(
                new MapPropertySource(SOURCE, Map.of(ADDRESS, hasText(configured) ? configured.trim() : LOOPBACK)));
    }

    private static boolean hasText(String value)
    {
        return value != null && !value.isBlank();
    }

    @Override
    public int getOrder()
    {
        return Ordered.LOWEST_PRECEDENCE; // after application.yml and profiles are loaded
    }
}
