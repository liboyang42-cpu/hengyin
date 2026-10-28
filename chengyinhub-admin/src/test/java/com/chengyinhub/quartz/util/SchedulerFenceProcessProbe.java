package com.chengyinhub.quartz.util;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.wall.WallConfig;
import com.alibaba.druid.wall.WallFilter;
import com.chengyinhub.common.utils.spring.SpringUtils;
import com.chengyinhub.quartz.service.MySqlSchedulerCoordinator;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Test-only child JVM. Real coordinator/MySQL/Druid; no application, provider or production schema. */
public final class SchedulerFenceProcessProbe {
    public static final class ProbeTask { public void run(String originalId) { } }
    private static final PrintWriter EVENTS = new PrintWriter(System.out, true, StandardCharsets.UTF_8);

    public static void main(String[] args) {
        try { run(); }
        catch (Throwable failure) { event("FATAL " + failure.getClass().getSimpleName()); System.exit(2); }
    }

    private static void run() throws Exception {
        if (!"github-hosted".equals(required("RUNNER_ENVIRONMENT")))
            throw new IllegalStateException("Controlled runner required");
        String schema = required("WP14_SCHEMA"), original = required("WP14_ORIGINAL");
        if (!schema.matches("cy_wp14_[a-f0-9]{32}") || !original.matches("[a-f0-9]{32}"))
            throw new IllegalArgumentException("Owned fixture identity required");
        String alias = required("WP14_ALIAS");
        if (!("wp14Task".equals(alias) || "wp14Alias".equals(alias))) throw new IllegalArgumentException("Fixture alias");
        String url = required("WP14_URL"), user = required("CHENGYIN_MYBATIS_IT_USER");
        String password = System.getenv("CHENGYIN_MYBATIS_IT_PASSWORD");
        if (password == null || !url.startsWith("jdbc:mysql://127.0.0.1:")) throw new IllegalArgumentException("Loopback fixture only");
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("wp14Task", new ProbeTask()); beans.registerAlias("wp14Task", "wp14Alias");
        new SpringUtils().postProcessBeanFactory(beans);
        String target = alias + ".run(" + ("wp14Task".equals(alias) ? "'" + original + "'" : "\"" + original + "\"") + ")";
        String canonical = CanonicalJobTarget.canonical(target);
        DruidDataSource source = new DruidDataSource();
        source.setUrl(url); source.setUsername(user); source.setPassword(password);
        source.setMaxActive(8); source.setMinIdle(0); source.setInitialSize(0); source.setMaxWait(3000);
        WallConfig config = new WallConfig(); config.setMultiStatementAllow(false);
        WallFilter wall = new WallFilter(); wall.setDbType("mysql"); wall.setConfig(config);
        source.setProxyFilters(List.of(wall)); source.init();
        try (Connection connection = source.getConnection()) {
            if (!schema.equals(connection.getCatalog())) throw new IllegalStateException("Wrong fixture schema");
        }
        FaultSource locks = new FaultSource(source, schema, canonical, url, user, password);
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        MySqlSchedulerCoordinator coordinator = new MySqlSchedulerCoordinator(locks, timer);
        // As in the shipped handoff IT, commands drive leadership/clock deterministically; SQL remains real.
        var running = MySqlSchedulerCoordinator.class.getDeclaredField("running");
        running.setAccessible(true); running.setBoolean(coordinator, true);
        var activatedAt = MySqlSchedulerCoordinator.class.getDeclaredField("activatedAt");
        activatedAt.setAccessible(true);
        Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();
        JdbcTemplate db = new JdbcTemplate(source);
        event("READY " + ProcessHandle.current().pid() + " "
                + Base64.getEncoder().encodeToString(canonical.getBytes(StandardCharsets.UTF_8)) + " " + coordinatorBytecodeHash());
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            for (String command; (command = input.readLine()) != null;) {
                String[] fields = command.split(" ");
                if ("STOP".equals(fields[0])) break;
                if ("LEAD".equals(fields[0])) {
                    coordinator.maintainLeadership();
                    // Only the admission case uses fire=2; this fixture does not measure clock skew/misfire policy.
                    if (coordinator.isLeader()) activatedAt.setLong(coordinator, 0L);
                    event("LEADER " + coordinator.isLeader());
                } else if ("ARM_TASK_LOSS".equals(fields[0])) {
                    locks.ownershipReads.set(0); locks.armed.set(true); event("ARMED");
                } else if ("RELEASE".equals(fields[0]) && fields.length == 2) {
                    CountDownLatch gate = gates.get(fields[1]);
                    if (gate == null) throw new IllegalArgumentException("Missing owned gate");
                    gate.countDown(); event("RELEASED " + fields[1]);
                } else if ("RUN".equals(fields[0]) && fields.length == 3) {
                    String label = fields[1], mode = fields[2];
                    if (!label.matches("[a-z]+") || !List.of("PLAIN", "FIRE", "BLOCK_PLAIN", "RECEIPT", "BLOCK_RECEIPT").contains(mode))
                        throw new IllegalArgumentException("Unknown fixture operation");
                    CountDownLatch gate = new CountDownLatch(mode.startsWith("BLOCK_") ? 1 : 0);
                    gates.put(label, gate);
                    workers.submit(() -> {
                        AtomicBoolean entered = new AtomicBoolean();
                        try {
                            boolean executed = coordinator.execute(target, "FIRE".equals(mode) ? new Date(2) : null, () -> {
                                entered.set(true); event("ENTER " + label);
                                try {
                                    if (!gate.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Unreleased fixture gate");
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt(); throw new IllegalStateException("Fixture interrupted", interrupted);
                                }
                                if (mode.endsWith("RECEIPT")) {
                                    new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(tx -> {
                                        db.update("UPDATE wp14_probe SET attempts=attempts+1 WHERE original_id=?", original);
                                        if (db.update("INSERT IGNORE INTO wp14_probe_receipt(original_id) VALUES(?)", original) == 1)
                                            db.update("UPDATE wp14_probe SET effects=effects+1 WHERE original_id=?", original);
                                    });
                                } else db.update("UPDATE wp14_probe SET attempts=attempts+1,effects=effects+1 WHERE original_id=?", original);
                                event("EFFECT " + label);
                            });
                            event("RESULT " + label + (executed ? " APPLIED" : " REJECTED"));
                        } catch (Throwable failure) {
                            event("RESULT " + label + (entered.get() ? " UNKNOWN " : " REJECTED ") + failure.getClass().getSimpleName());
                        }
                    });
                } else throw new IllegalArgumentException("Unknown fixture command");
            }
        } finally {
            workers.shutdownNow();
            try {
                if (!workers.awaitTermination(10, TimeUnit.SECONDS)) throw new IllegalStateException("Owned workers did not exit");
            } finally {
                try { coordinator.stop(); }
                finally { timer.shutdownNow(); source.close(); }
            }
        }
        event("STOPPED");
    }

    private static void event(String message) { EVENTS.println("WP14 " + message); }
    static String coordinatorBytecodeHash() throws Exception {
        try (var bytes = MySqlSchedulerCoordinator.class.getResourceAsStream("MySqlSchedulerCoordinator.class")) {
            if (bytes == null) throw new IllegalStateException("Coordinator class bytes unavailable");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.readAllBytes()));
        }
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing test binding: " + name);
        return value;
    }

    /** Fault only the real task session at the second leader recheck; never forge any JDBC return value. */
    private static final class FaultSource extends DelegatingDataSource {
        final AtomicBoolean armed = new AtomicBoolean();
        final AtomicInteger ownershipReads = new AtomicInteger();
        private final AtomicInteger connections = new AtomicInteger();
        private final DruidDataSource raw;
        private final String schema, canonical, url, user, password;
        FaultSource(DruidDataSource raw, String schema, String canonical, String url, String user, String password) {
            super(raw); this.raw = raw; this.schema = schema; this.canonical = canonical;
            this.url = url; this.user = user; this.password = password;
        }
        @Override public Connection getConnection() throws SQLException {
            Connection actual = super.getConnection();
            if (connections.incrementAndGet() != 1) return actual;
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if ("prepareStatement".equals(method.getName()) && args != null
                        && "SELECT IS_USED_LOCK(?) = CONNECTION_ID()".equals(args[0])
                        && armed.get() && ownershipReads.incrementAndGet() == 2 && armed.compareAndSet(true, false)) {
                    killOwnedTaskSession();
                }
                try { return method.invoke(actual, args); }
                catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
        }
        private void killOwnedTaskSession() throws SQLException {
            String key = MySqlSessionLock.key("task", schema, canonical);
            try (Connection control = DriverManager.getConnection(url, user, password);
                 PreparedStatement query = control.prepareStatement("SELECT IS_USED_LOCK(?)")) {
                query.setQueryTimeout(3); query.setString(1, key);
                long id;
                try (ResultSet row = query.executeQuery()) {
                    if (!row.next() || (id = row.getLong(1)) <= 0 || row.wasNull()) throw new SQLException("Expected owned task session");
                }
                try (PreparedStatement scope = control.prepareStatement("SELECT DB FROM information_schema.processlist WHERE ID=?")) {
                    scope.setQueryTimeout(3); scope.setLong(1, id);
                    try (ResultSet row = scope.executeQuery()) {
                        if (!row.next() || !schema.equals(row.getString(1))) throw new SQLException("Task session outside owned schema");
                    }
                }
                try (Statement kill = control.createStatement()) { kill.setQueryTimeout(3); kill.execute("KILL CONNECTION " + id); }
            }
            // Real server release barrier, not a sleep, mocked ownership or enlarged timeout.
            try (MySqlSessionLock.Lease barrier = MySqlSessionLock.acquire(raw, "task", canonical, 3)) {
                if (barrier == null || !barrier.isOwned()) throw new SQLException("Task session did not release");
            }
            event("TASK_SESSION_KILLED");
        }
    }
}
