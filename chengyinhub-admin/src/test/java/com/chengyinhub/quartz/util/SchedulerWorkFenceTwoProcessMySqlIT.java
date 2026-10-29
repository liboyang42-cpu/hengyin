package com.chengyinhub.quartz.util;

import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real separate JVMs and MySQL sessions. Probe counters/receipts are fixtures, never proof of domain money safety. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
class SchedulerWorkFenceTwoProcessMySqlIT {
    private JdbcTemplate admin, db;
    private DriverManagerDataSource source;
    private String schema, url, user, password, original;
    private boolean created;
    private final List<Child> children = new ArrayList<>();
    private final List<String> canonicalTargets = new ArrayList<>();

    @BeforeAll void database() throws Exception {
        assertEquals("github-hosted", System.getenv("RUNNER_ENVIRONMENT"));
        assertTrue(List.of("127.0.0.1", "localhost").contains(required("CHENGYIN_MYBATIS_IT_HOST")));
        int port = Integer.parseInt(required("CHENGYIN_MYBATIS_IT_PORT")); assertTrue(port > 0 && port <= 65535);
        user = required("CHENGYIN_MYBATIS_IT_USER"); password = System.getenv("CHENGYIN_MYBATIS_IT_PASSWORD");
        assertNotNull(password, "Explicit scratch password required, including an explicitly empty password");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/", options = "?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
        admin = new JdbcTemplate(new DriverManagerDataSource(base + "mysql" + options, user, password));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("SELECT VERSION()", String.class)));
        schema = "cy_wp14_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + schema); created = true;
        url = base + schema + options; source = new DriverManagerDataSource(url, user, password); db = new JdbcTemplate(source);
        assertEquals(schema, db.queryForObject("SELECT DATABASE()", String.class));
        db.execute("CREATE TABLE wp14_probe(original_id VARCHAR(32) PRIMARY KEY,attempts INT NOT NULL DEFAULT 0,effects INT NOT NULL DEFAULT 0) ENGINE=InnoDB");
        db.execute("CREATE TABLE wp14_probe_receipt(original_id VARCHAR(32) PRIMARY KEY) ENGINE=InnoDB");
    }

    @BeforeEach void original() {
        original = UUID.randomUUID().toString().replace("-", "");
        db.update("INSERT INTO wp14_probe(original_id) VALUES(?)", original);
    }

    @AfterEach void stopChildren() throws Throwable {
        Throwable failure = null;
        for (Child child : children) {
            try { child.close(); }
            catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        children.removeIf(child -> !child.process.isAlive());
        if (source != null) {
            try { assertReleased("leader", "all"); }
            catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            for (String canonical : canonicalTargets) {
                try { assertReleased("task", canonical); }
                catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            }
        }
        canonicalTargets.clear();
        if (failure != null) throw failure;
    }

    @AfterAll void cleanup() throws Throwable {
        stopChildren();
        if (created) {
            admin.execute("DROP DATABASE " + schema);
            assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, schema));
            created = false;
        }
    }

    @Test void lostTaskSessionBeforeAdmissionDoesNotEnterBusinessOrConsumeTheOriginalFire() throws Exception {
        Child first = child("wp14Task"), second = child("wp14Alias");
        sameCanonicalSeparateJvms(first, second);
        first.command("LEAD"); assertEquals("LEADER true", first.await("LEADER "));
        second.command("LEAD"); assertEquals("LEADER false", second.await("LEADER "));
        first.command("ARM_TASK_LOSS"); first.await("ARMED");
        first.command("RUN rejected FIRE"); first.await("TASK_SESSION_KILLED");
        String result = first.await("RESULT rejected ");
        assertEquals(0, effects(), "old candidate entered the body after GET_LOCK's task session had died");
        assertTrue(result.startsWith("RESULT rejected REJECTED"), result);
        assertNotNull(owner("leader", "all"), "task loss must not be confused with leader loss");
        first.command("RUN retry FIRE"); assertEquals("RESULT retry APPLIED", first.await("RESULT retry "));
        assertEquals(1, effects()); assertEquals(1, attempts());
    }

    @Test void survivingInFlightTaskExcludesTheCanonicalAliasInAnActualSuccessorJvm() throws Exception {
        Child first = child("wp14Task"), second = child("wp14Alias");
        sameCanonicalSeparateJvms(first, second);
        first.command("LEAD"); first.await("LEADER true");
        first.command("RUN old BLOCK_PLAIN"); first.await("ENTER old");
        Long work = owner("task", first.canonical); assertNotNull(work);
        killOwned("leader", "all");
        second.command("LEAD"); second.await("LEADER true");
        assertEquals(work, owner("task", first.canonical));
        second.command("RUN denied PLAIN"); assertEquals("RESULT denied REJECTED", second.await("RESULT denied "));
        assertEquals(0, effects());
        first.command("RELEASE old"); first.await("RELEASED old");
        assertEquals("RESULT old APPLIED", first.await("RESULT old "));
        assertEquals(1, effects()); assertEquals(1, attempts());
        assertReleased("task", first.canonical);
    }

    @Test void midActionSessionLossRequiresTheSameDurableBusinessIdentityDespiteANewLeader() throws Exception {
        Child first = child("wp14Task"), second = child("wp14Alias");
        sameCanonicalSeparateJvms(first, second);
        first.command("LEAD"); first.await("LEADER true");
        first.command("RUN old BLOCK_RECEIPT"); first.await("ENTER old");
        killOwned("leader", "all"); killOwned("task", first.canonical);
        second.command("LEAD"); second.await("LEADER true");
        second.command("RUN replacement RECEIPT"); assertEquals("RESULT replacement APPLIED", second.await("RESULT replacement "));
        assertEquals(1, effects());
        first.command("RELEASE old"); first.await("RELEASED old");
        assertTrue(first.await("RESULT old ").startsWith("RESULT old UNKNOWN"));
        assertEquals(2, attempts(), "a generic scheduler cannot retract an already-entered body");
        assertEquals(1, effects(), "only the original durable receipt, never lease expiry or the new epoch, authorizes the effect");
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM wp14_probe_receipt WHERE original_id=?", Integer.class, original));
    }

    private int effects() { return db.queryForObject("SELECT effects FROM wp14_probe WHERE original_id=?", Integer.class, original); }
    private int attempts() { return db.queryForObject("SELECT attempts FROM wp14_probe WHERE original_id=?", Integer.class, original); }
    private void sameCanonicalSeparateJvms(Child first, Child second) {
        assertNotEquals(first.process.pid(), second.process.pid()); assertNotEquals(ProcessHandle.current().pid(), first.process.pid());
        assertEquals(first.canonical, second.canonical, "different bean aliases and quotes must lock the same real target");
    }
    private Child child(String alias) throws Exception {
        Child value = new Child(alias); children.add(value); canonicalTargets.add(value.canonical); return value;
    }

    private Long owner(String kind, String target) throws Exception {
        try (Connection connection = source.getConnection(); PreparedStatement query = connection.prepareStatement("SELECT IS_USED_LOCK(?)")) {
            query.setQueryTimeout(3); query.setString(1, MySqlSessionLock.key(kind, schema, target));
            try (ResultSet row = query.executeQuery()) { assertTrue(row.next()); long value = row.getLong(1); return row.wasNull() ? null : value; }
        }
    }
    private void killOwned(String kind, String target) throws Exception {
        Long id = owner(kind, target); assertNotNull(id); assertTrue(id > 0);
        assertEquals(schema, admin.queryForObject("SELECT DB FROM information_schema.processlist WHERE ID=?", String.class, id));
        try (Connection control = DriverManager.getConnection(url, user, password); Statement statement = control.createStatement()) {
            statement.setQueryTimeout(3); statement.execute("KILL CONNECTION " + id);
        }
        assertReleased(kind, target);
    }
    private void assertReleased(String kind, String target) throws Exception {
        // Acquisition is the server teardown barrier; do not infer release from KILL returning or a local flag.
        try (MySqlSessionLock.Lease barrier = MySqlSessionLock.acquire(source, kind, target, 3)) {
            assertNotNull(barrier); assertTrue(barrier.isOwned());
        }
        assertNull(owner(kind, target));
    }
    private static String required(String name) {
        String value = System.getenv(name); assertNotNull(value, name); assertFalse(value.isBlank(), name); return value;
    }

    private final class Child implements AutoCloseable {
        final Process process;
        final PrintWriter commands;
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        final List<String> pending = new ArrayList<>();
        final Thread reader;
        final String canonical;
        Child(String alias) throws Exception {
            String classpath = System.getProperty("surefire.test.class.path");
            if (classpath == null || classpath.isBlank()) classpath = System.getProperty("java.class.path");
            assertNotNull(classpath); assertFalse(classpath.isBlank());
            ProcessBuilder launch = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", classpath, SchedulerFenceProcessProbe.class.getName());
            launch.redirectErrorStream(true);
            launch.environment().clear();
            launch.environment().put("RUNNER_ENVIRONMENT", "github-hosted");
            launch.environment().put("WP14_URL", url); launch.environment().put("WP14_SCHEMA", schema);
            launch.environment().put("WP14_ORIGINAL", original); launch.environment().put("WP14_ALIAS", alias);
            launch.environment().put("CHENGYIN_MYBATIS_IT_USER", user); launch.environment().put("CHENGYIN_MYBATIS_IT_PASSWORD", password);
            process = launch.start(); commands = new PrintWriter(process.getOutputStream(), true, StandardCharsets.UTF_8);
            reader = new Thread(() -> {
                try (BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line; (line = output.readLine()) != null;) if (line.startsWith("WP14 ")) events.add(line.substring(5));
                } catch (Exception failure) { events.add("FATAL OutputReader"); }
            }, "wp14-child-" + process.pid());
            reader.setDaemon(true); reader.start();
            try {
                String[] ready = await("READY ").split(" ");
                assertEquals(process.pid(), Long.parseLong(ready[1]));
                canonical = new String(Base64.getDecoder().decode(ready[2]), StandardCharsets.UTF_8);
                assertEquals(SchedulerFenceProcessProbe.coordinatorBytecodeHash(), ready[3], "both child and parent must execute the same candidate class bytes");
            } catch (Exception | AssertionError failure) {
                try { close(); } catch (Exception | AssertionError cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                if (process.isAlive()) children.add(this);
                throw failure;
            }
        }
        void command(String command) { commands.println(command); assertFalse(commands.checkError()); }
        String await(String prefix) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            for (;;) {
                for (int i = 0; i < pending.size(); i++) if (pending.get(i).startsWith(prefix)) return pending.remove(i);
                long remaining = deadline - System.nanoTime(); assertTrue(remaining > 0, "Missing child event: " + prefix);
                String next = events.poll(remaining, TimeUnit.NANOSECONDS); assertNotNull(next, "Missing child event: " + prefix);
                assertFalse(next.startsWith("FATAL "), next); pending.add(next);
            }
        }
        @Override public void close() throws Exception {
            boolean forced = false;
            try {
                if (process.isAlive()) commands.println("STOP");
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    forced = true; process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS));
                }
            } finally {
                if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
                commands.close(); reader.join(3000);
            }
            assertFalse(process.isAlive()); assertFalse(reader.isAlive()); assertEquals(0, process.exitValue());
            assertFalse(forced, "Owned scheduler child required forced termination");
        }
    }
}
