package com.chengyinhub.web.controller.api;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiLoginControllerSecurityContractTest {

    @Test
    void doesNotExposeLegacyOpenIdExchangeEndpoint() {
        boolean hasLegacyEndpoint = Arrays.stream(ApiLoginController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(PostMapping.class))
                .filter(annotation -> annotation != null)
                .flatMap(annotation -> Arrays.stream(annotation.value()))
                .anyMatch("/getonenid"::equals);

        assertFalse(hasLegacyEndpoint);
    }

    @Test
    void phoneBindingErrorsNeverRevealWeChatOrAccountDetails() throws Exception {
        Path source = Paths.get("src/main/java/com/chengyinhub/web/controller/api/ApiLoginController.java");
        String content = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);

        assertFalse(content.contains("手机号 \" + phone + \" 已被账号"));
        assertFalse(content.contains("获取失败:\" + result2"));
        assertFalse(content.contains("return AjaxResult.success(phone);"),
                "bound phone must go in data, not AjaxResult.success(String msg)");
        assertTrue(content.contains("AjaxResult.success(\"操作成功\", phone)"));
    }
}
