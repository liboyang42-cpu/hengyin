package com.chengyinhub.framework;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggingConfigContractTest
{
    @Test
    void applicationLogsAreInfoByDefault() throws Exception
    {
        Path config = Paths.get(System.getProperty("user.dir"), "src/main/resources/application.yml");
        String yaml = new String(Files.readAllBytes(config), StandardCharsets.UTF_8);

        assertTrue(yaml.contains("com.chengyinhub: ${CHENGYIN_LOG_LEVEL:info}"));
    }
}
