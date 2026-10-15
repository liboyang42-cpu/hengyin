package com.chengyinhub.framework;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityConfigHeaderContractTest {

    @Test
    void securityConfigSetsStrictCrossOriginReferrerPolicy() throws Exception {
        Path current = Paths.get(System.getProperty("user.dir"));
        Path root = Files.exists(current.resolve("chengyinhub-framework")) ? current : current.getParent();
        String source = new String(Files.readAllBytes(root.resolve("chengyinhub-framework/src/main/java/com/chengyinhub/framework/config/SecurityConfig.java")), StandardCharsets.UTF_8);

        assertTrue(source.contains("ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN"));
    }

    @Test
    void securityConfigRequiresAuthenticationForCommonUploadEndpoints() throws Exception {
        Path current = Paths.get(System.getProperty("user.dir"));
        Path root = Files.exists(current.resolve("chengyinhub-framework")) ? current : current.getParent();
        String source = new String(Files.readAllBytes(root.resolve("chengyinhub-framework/src/main/java/com/chengyinhub/framework/config/SecurityConfig.java")), StandardCharsets.UTF_8);

        assertTrue(source.contains(".requestMatchers(legacyMatchers(\"/api/common/upload\", \"/api/common/upload/\", \"/api/common/uploads\", \"/api/common/uploads/\", \"/api/common/uploadOSS\", \"/api/common/uploadOSS/\")).authenticated()"));
    }
}
