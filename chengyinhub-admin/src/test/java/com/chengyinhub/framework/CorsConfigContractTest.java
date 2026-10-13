package com.chengyinhub.framework;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorsConfigContractTest
{
    @Test
    void adminLoginOriginIsAllowedButUntrustedOriginIsRejected() throws Exception
    {
        org.springframework.beans.factory.config.YamlPropertiesFactoryBean yaml =
                new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
        yaml.setResources(new org.springframework.core.io.ClassPathResource("application.yml"));
        String origins = new org.springframework.mock.env.MockEnvironment().resolveRequiredPlaceholders(
                yaml.getObject().getProperty("security.cors.allowed-origins"));
        com.chengyinhub.framework.config.ResourcesConfig config =
                new com.chengyinhub.framework.config.ResourcesConfig();
        org.springframework.test.util.ReflectionTestUtils.setField(config, "allowedOrigins", origins);
        for (String origin : new String[] {"https://houtai.chengyinhub.com", "https://untrusted.invalid"}) {
            org.springframework.mock.web.MockHttpServletRequest request =
                    new org.springframework.mock.web.MockHttpServletRequest("OPTIONS", "/login");
            request.addHeader("Origin", origin);
            request.addHeader("Access-Control-Request-Method", "POST");
            request.addHeader("Access-Control-Request-Headers", "content-type");
            org.springframework.mock.web.MockHttpServletResponse response =
                    new org.springframework.mock.web.MockHttpServletResponse();
            config.corsFilter().doFilter(request, response, new org.springframework.mock.web.MockFilterChain());
            org.junit.jupiter.api.Assertions.assertEquals(origin.contains("untrusted") ? 403 : 200,
                    response.getStatus());
        }
    }

    @Test
    void corsDoesNotAllowEveryOriginOrCredentialedCrossOriginRequests() throws Exception
    {
        Path source = Paths.get(System.getProperty("user.dir"), "../chengyinhub-framework/src/main/java/com/chengyinhub/framework/config/ResourcesConfig.java");
        String java = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);

        assertFalse(java.contains("addAllowedOriginPattern(\"*\")"));
        assertTrue(java.contains("config.addAllowedOrigin(origin)"));
        assertTrue(java.contains("config.setAllowCredentials(false)"));
    }
}
