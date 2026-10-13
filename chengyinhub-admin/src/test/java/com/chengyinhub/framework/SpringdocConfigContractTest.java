package com.chengyinhub.framework;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpringdocConfigContractTest
{
    @Test
    void apiDocsAndSwaggerUiAreDisabledUnlessExplicitlyEnabledByEnvironment() throws Exception
    {
        Path config = Paths.get(System.getProperty("user.dir"), "src/main/resources/application.yml");
        String yaml = new String(Files.readAllBytes(config), StandardCharsets.UTF_8);

        String setting = "enabled: ${CHENGYIN_SPRINGDOC_ENABLED:false}";
        assertEquals(2, yaml.split(java.util.regex.Pattern.quote(setting), -1).length - 1);
    }
}
