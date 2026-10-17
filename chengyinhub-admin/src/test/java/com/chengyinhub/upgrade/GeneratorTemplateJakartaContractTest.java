package com.chengyinhub.upgrade;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boot 4 / Jakarta EE：代码生成器模板生成的 Java 源必须引用 jakarta.*，否则生成代码在当前框架下无法编译。
 * javax.sql 等仍属 JDK 的包不在此列。
 */
class GeneratorTemplateJakartaContractTest {

    private static final Pattern REMOVED_JAVAX = Pattern.compile(
            "\\bjavax\\.(servlet|validation|annotation\\.(PostConstruct|PreDestroy|Resource)|persistence|transaction)\\b");

    @Test
    void generatedJavaTemplatesUseJakartaNamespaces() throws IOException {
        Path root = Paths.get("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("chengyinhub-generator/src/main/resources/vm"))) {
            root = root.getParent();
        }
        assertNotNull(root, "chengyinhub-generator templates not found");
        Path vm = root.resolve("chengyinhub-generator/src/main/resources/vm");
        List<Path> templates;
        try (Stream<Path> files = Files.walk(vm)) {
            templates = files.filter(p -> p.toString().endsWith(".java.vm")).sorted().collect(Collectors.toList());
        }
        assertTrue(templates.stream().anyMatch(p -> p.endsWith(Paths.get("java", "controller.java.vm"))),
                "controller template must be scanned");
        for (Path template : templates) {
            String source = new String(Files.readAllBytes(template), StandardCharsets.UTF_8);
            assertFalse(REMOVED_JAVAX.matcher(source).find(), template + " still imports a removed javax namespace");
        }
        String controller = new String(Files.readAllBytes(vm.resolve("java/controller.java.vm")), StandardCharsets.UTF_8);
        assertTrue(controller.contains("import jakarta.servlet.http.HttpServletResponse;"));
    }
}
