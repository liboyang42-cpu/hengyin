package com.chengyinhub.business.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "W2_OPS_MYSQL_JDBC_URL", matches = ".+")
class W2OperationsMySqlIT {
    @Test
    void migrationReplaysAndSameSubjectRequestRemainsUniqueUnderConcurrentInsert() throws Exception {
        try (Connection connection = connect()) {
            String schema;
            try (ResultSet rs = connection.createStatement().executeQuery("select database()")) { rs.next(); schema = rs.getString(1); }
            assertTrue(schema != null && schema.startsWith("w2_ops_"), "Only the dedicated W2 ops test schema may be used");
            connection.createStatement().execute("create table if not exists official_broadcast(id bigint auto_increment primary key,title varchar(128),audience varchar(32),channels varchar(32)) engine=InnoDB default charset=utf8mb4");
            Path migration = Paths.get("sql/migration_20261013_w2_official_broadcast_request.sql");
            if (!Files.exists(migration)) migration = Paths.get("chengyinhub-admin").resolve(migration);
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(migration.toFile()));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(migration.toFile()));
            try (ResultSet rs = connection.createStatement().executeQuery("select count(*) from information_schema.statistics where table_schema=database() and table_name='official_broadcast' and index_name='uk_broadcast_request' and non_unique=0")) {
                rs.next(); assertEquals(2, rs.getInt(1));
            }
            String key = "w2_" + java.util.UUID.randomUUID().toString().replace("-", "");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                Callable<Boolean> write = () -> { start.await(); try (Connection c = connect()) { return insert(c, "ADMIN:11", key); } };
                Future<Boolean> first = pool.submit(write), second = pool.submit(write); start.countDown();
                assertNotEquals(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS), "Exactly one concurrent insert must own the request");
                assertTrue(insert(connection, "ADMIN:12", key));
                assertTrue(insert(connection, "MEMBER:11", key));
                try (PreparedStatement p = connection.prepareStatement("select count(*) from official_broadcast where request_id=?")) {
                    p.setString(1, key); try (ResultSet rs = p.executeQuery()) { rs.next(); assertEquals(3, rs.getInt(1)); }
                }
            } finally {
                pool.shutdownNow();
                try (PreparedStatement p = connection.prepareStatement("delete from official_broadcast where request_id=?")) { p.setString(1, key); p.executeUpdate(); }
            }
        }
    }
    private boolean insert(Connection c, String scope, String id) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("insert into official_broadcast(title,audience,channels,request_scope,request_id) values ('unit','player','inapp',?,?)")) {
            p.setString(1, scope); p.setString(2, id); p.executeUpdate(); return true;
        } catch (SQLException duplicate) { if (duplicate.getErrorCode() == 1062) return false; throw duplicate; }
    }
    private Connection connect() throws SQLException {
        return DriverManager.getConnection(System.getenv("W2_OPS_MYSQL_JDBC_URL"), System.getenv("W2_OPS_MYSQL_USER"), System.getenv("W2_OPS_MYSQL_PASSWORD"));
    }
}
