package com.chengyinhub.web.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** x-be-test: the management address is only defaulted where Spring Boot accepts one (separate management port). */
class ManagementAddressDefaultTest
{
    private static StandardEnvironment env(Map<String, Object> props)
    {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", new HashMap<>(props)));
        new ManagementAddressDefault().postProcessEnvironment(environment, null);
        return environment;
    }

    @Test
    void samePortSetsNoAddressSoTheJarCanStart()
    {
        assertNull(env(Map.of("server.port", "8120", "management.server.port", "8120")).getProperty(ManagementAddressDefault.ADDRESS));
        assertNull(env(Map.of("server.port", "8120")).getProperty(ManagementAddressDefault.ADDRESS), "default: management port = server port");
        assertNull(env(Map.of()).getProperty(ManagementAddressDefault.ADDRESS));
    }

    @Test
    void separatePortIsBoundToLoopbackByDefault()
    {
        assertEquals("127.0.0.1",
                env(Map.of("server.port", "8120", "management.server.port", "9091")).getProperty(ManagementAddressDefault.ADDRESS));
    }

    @Test
    void separatePortTakesTheConfiguredAddress()
    {
        assertEquals("10.0.0.5", env(Map.of("server.port", "8120", "management.server.port", "9091",
                ManagementAddressDefault.ADDRESS_ENV, "10.0.0.5")).getProperty(ManagementAddressDefault.ADDRESS));
    }

    @Test
    void anExplicitAddressAlwaysWins()
    {
        assertEquals("192.168.1.2", env(Map.of("server.port", "8120", "management.server.port", "9091",
                ManagementAddressDefault.ADDRESS, "192.168.1.2")).getProperty(ManagementAddressDefault.ADDRESS));
    }

    @Test
    void managementHttpOffSetsNothing()
    {
        assertNull(env(Map.of("server.port", "8120", "management.server.port", "-1")).getProperty(ManagementAddressDefault.ADDRESS));
    }

    @Test
    void applicationYmlNoLongerCarriesAFixedAddressAndThePostProcessorIsRegistered() throws Exception
    {
        Path root = Path.of(System.getProperty("user.dir"));
        Path resources = Files.isDirectory(root.resolve("src/main/resources")) ? root.resolve("src/main/resources")
                : root.resolve("chengyinhub-admin/src/main/resources");
        String yml = Files.readString(resources.resolve("application.yml"), StandardCharsets.UTF_8);
        int management = yml.indexOf("\nmanagement:");
        assertTrue(management >= 0);
        String block = yml.substring(management, yml.indexOf("\n  endpoints:", management));
        assertFalse(block.lines().anyMatch(l -> l.trim().startsWith("address:")),
                "a fixed management.server.address stops the jar when the management port is the application port");
        String factories = Files.readString(resources.resolve("META-INF/spring.factories"), StandardCharsets.UTF_8);
        assertTrue(factories.contains("org.springframework.boot.EnvironmentPostProcessor")
                && factories.contains(ManagementAddressDefault.class.getName()));
    }
}
