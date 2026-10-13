package com.chengyinhub.framework;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DruidConsoleConfigContractTest
{
    @Test
    void druidConsoleIsDisabledUnlessExplicitlyEnabledByEnvironment() throws Exception
    {
        Path config = Paths.get(System.getProperty("user.dir"), "src/main/resources/application-druid.yml");
        String yaml = new String(Files.readAllBytes(config), StandardCharsets.UTF_8);

        assertTrue(yaml.contains("enabled: ${CHENGYIN_DRUID_CONSOLE_ENABLED:false}"));
        assertTrue(yaml.contains("allow: ${CHENGYIN_DRUID_ALLOWED_IPS:127.0.0.1}"));
        assertTrue(yaml.contains("login-password: ${CHENGYIN_DRUID_PASSWORD}"));
    }
}
