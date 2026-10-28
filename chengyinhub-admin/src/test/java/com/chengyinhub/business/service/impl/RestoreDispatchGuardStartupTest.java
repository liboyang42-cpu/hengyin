package com.chengyinhub.business.service.impl;

import com.chengyinhub.common.config.RestoreDispatchGuard;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** SOURCE only until hosted execution: real child environment, no reflection/env mutation. */
class RestoreDispatchGuardStartupTest {
    @Test void defaultAndExactFalseRetainNormalDispatch() throws Exception {
        child(null, "NORMAL");
        child("false", "NORMAL");
    }

    @Test void restoreAndMalformedStartupConfigurationFailClosed() throws Exception {
        for (String value : new String[] {"true", "TRUE", "False", " false", "true ", "", "0", "unknown"})
            child(value, "RECOVERY_DISPATCH_BLOCKED");
    }

    private void child(String value, String expected) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, Child.class.getName());
        for (String key : new String[]{"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"})
            builder.environment().remove(key);
        builder.environment().remove("CHENGYIN_RESTORE_MODE");
        if (value != null) builder.environment().put("CHENGYIN_RESTORE_MODE", value);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "owned guard child deadline");
            assertEquals(0, process.exitValue(), "owned guard child failed");
            String output = new String(process.getInputStream().readNBytes(4097), StandardCharsets.UTF_8);
            assertTrue(expected.equals(output.strip()), "fixed safe child outcome differs");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "owned guard child cleanup");
            }
        }
    }

    public static final class Child {
        public static void main(String[] args) {
            boolean mode = RestoreDispatchGuard.isRestoreMode();
            // A client/system property cannot reopen this process's startup environment.
            System.setProperty("CHENGYIN_RESTORE_MODE", mode ? "false" : "true");
            System.setProperty("chengyin.restore.mode", mode ? "false" : "true");
            if (mode != RestoreDispatchGuard.isRestoreMode()) throw new AssertionError("mode changed");
            try {
                RestoreDispatchGuard.requireDispatchAllowed();
                if (mode) throw new AssertionError("restore dispatch allowed");
                System.out.println("NORMAL");
            } catch (RestoreDispatchGuard.Blocked blocked) {
                if (!mode || !"RECOVERY_DISPATCH_BLOCKED".equals(blocked.getMessage())
                        || blocked.getCause() != null) throw new AssertionError("blocked contract changed");
                System.out.println("RECOVERY_DISPATCH_BLOCKED");
            }
        }
    }
}
