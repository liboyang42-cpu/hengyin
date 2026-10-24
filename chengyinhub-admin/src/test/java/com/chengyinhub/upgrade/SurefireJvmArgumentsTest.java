package com.chengyinhub.upgrade;

import java.lang.management.ManagementFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SurefireJvmArgumentsTest {
    @Test
    void actualTestJvmUsesTheDeclaredHeapLimit() {
        List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
        assertTrue(arguments.stream().anyMatch(value -> value.startsWith("-Xmx")),
                "Surefire test JVM must have an explicit heap limit");
        for (String declared : System.getProperty("argLine", "").trim().split("\\s+")) {
            if (declared.startsWith("-Xmx")) {
                assertTrue(arguments.contains(declared),
                        "Surefire test JVM must use the heap limit declared in argLine");
            }
        }
    }
}
