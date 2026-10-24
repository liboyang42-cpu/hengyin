package com.chengyinhub.business.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Only the newly created test schema is mutated; no scheduler or current application is started. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MissingBusinessSeedsMySqlIT {
    private final MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.fromEnvironment("missing_business_seeds_it");
    private JdbcTemplate admin, db;
    private DriverManagerDataSource source;
    private boolean created;
    private String migration;
    @BeforeAll void setup() throws Exception {
        admin = new JdbcTemplate(source(mysql.adminUrl()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        admin.execute("create database " + mysql.databaseName() + " character set utf8mb4");
        created = true;
        source = source(mysql.databaseUrl()); db = new JdbcTemplate(source);
        Path root = Files.exists(Path.of("sql/ry_20250522.sql")) ? Path.of("") : Path.of("..");
        String base = Files.readString(root.resolve("sql/base_schema_20260818.sql"));
        for (String table : new String[]{"sys_menu", "user_permission", "sys_role_menu"}) {
            int start = base.indexOf("CREATE TABLE `" + table + "`");
            int end = base.indexOf(';', start);
            assertTrue(start >= 0 && end > start);
            db.execute(base.substring(start, end + 1));
        }
        migration = Files.readString(root.resolve("chengyinhub-admin/sql/migration_20261012_missing_business_menu.sql"))
                + "\n" + Files.readString(root.resolve("chengyinhub-admin/sql/migration_20261012_missing_role_permission_defaults.sql"));
    }

    @BeforeEach void clearOnlyOwnedFixtureRows() { db.execute("delete from sys_role_menu"); db.execute("delete from sys_menu"); db.execute("delete from user_permission"); }
    @AfterAll void cleanup() {
        if (created) {
            admin.execute("drop database " + mysql.databaseName());
            assertEquals(0, admin.queryForObject("select count(*) from information_schema.schemata where schema_name=?",
                    Integer.class, mysql.databaseName()));
        }
    }

    @Test void realMissingMenusAndFinalDefaultsAreInsertedOnceWithoutRoleGrants() throws Exception {
        applyMigration(); applyMigration();
        assertEquals(67, db.queryForObject("select count(*) from sys_menu where menu_type='C'", Integer.class));
        assertEquals(211, db.queryForObject("select count(*) from sys_menu where menu_type='F'", Integer.class));
        assertEquals(3, db.queryForObject("select count(*) from user_permission", Integer.class));
        assertEquals(1, db.queryForObject("select withdrawable from user_permission where role='player'", Integer.class));
        assertEquals(0, db.queryForObject("select count(*) from sys_role_menu", Integer.class));
        assertEquals("business/membertemplate/index", db.queryForObject("select component from sys_menu where perms='business:membertemplate:list'", String.class));
    }
    @Test void existingPausedCustomMenuQuotaAndRoleGrantsRemainByteForByte() throws Exception {
        db.update("insert into sys_menu(menu_id,menu_name,parent_id,path,component,menu_type,visible,status,perms) values(9901,'custom',0,'custom-path','custom/component','C','1','1','business:merchant:list')");
        db.update("insert into sys_role_menu(role_id,menu_id) values(2,9901)");
        db.update("insert into user_permission(role,max_themes,max_events,max_coupons,can_create_theme,can_create_event,can_publish_coupon,withdrawable) values('player',8,0,4,1,0,0,0)");
        Map<String,Object> menu = db.queryForMap("select * from sys_menu where menu_id=9901");
        Map<String,Object> quota = db.queryForMap("select * from user_permission where role='player'");
        List<Map<String,Object>> grants = db.queryForList("select * from sys_role_menu");
        applyMigration(); applyMigration();
        assertEquals(menu, db.queryForMap("select * from sys_menu where menu_id=9901"));
        assertEquals(quota, db.queryForMap("select * from user_permission where role='player'"));
        assertEquals(grants, db.queryForList("select * from sys_role_menu"));
    }
    @Test void duplicateMenuPermissionFailsBeforeMissingRowsAreCreated() {
        db.update("insert into sys_menu(menu_name,menu_type,perms) values('one','C','business:merchant:list'),('two','C','business:merchant:list')");
        assertThrows(org.springframework.jdbc.datasource.init.ScriptException.class, this::applyMigration);
        assertEquals(2, db.queryForObject("select count(*) from sys_menu", Integer.class));
        assertEquals(0, db.queryForObject("select count(*) from user_permission", Integer.class));
    }
    @Test void duplicateNullDefaultQuotaRollsBackAllEarlierMenuInsertsAndPreservesRows() {
        db.update("insert into user_permission(role,max_themes,max_events,max_coupons,can_create_theme,can_create_event,can_publish_coupon) values('player',1,0,0,1,0,0),('player',2,0,0,1,0,0)");
        List<Map<String,Object>> before = db.queryForList("select * from user_permission order by id");
        assertThrows(org.springframework.jdbc.datasource.init.ScriptException.class, this::applyMigration);
        assertEquals(0, db.queryForObject("select count(*) from sys_menu", Integer.class));
        assertEquals(before, db.queryForList("select * from user_permission order by id"));
    }

    private void applyMigration() throws Exception {
        long expected = migration.lines().filter(line -> line.trim().equals("-- runner: assert=scalar-true")).count();
        assertEquals(0L, expected);
        AtomicInteger observed = new AtomicInteger();
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
            Connection checked = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                Object result;
                try { result = method.invoke(connection, args); } catch (InvocationTargetException e) { throw e.getCause(); }
                if (!method.getName().equals("createStatement")) return result;
                Statement statement = (Statement) result;
                return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class}, (p, operation, parameters) -> {
                    Object value;
                    try { value = operation.invoke(statement, parameters); } catch (InvocationTargetException e) { throw e.getCause(); }
                    if (operation.getName().equals("execute") && parameters != null && parameters[0] instanceof String sql
                            && sql.stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT ")) {
                        assertEquals(Boolean.TRUE, value);
                        try (ResultSet rows = statement.getResultSet()) {
                            assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); assertFalse(rows.next());
                        }
                        observed.incrementAndGet();
                    }
                    return value;
                });
            });
            ScriptUtils.executeSqlScript(checked, new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8)));
            assertEquals(expected, observed.get());
            connection.commit();
            } catch (Exception | AssertionError error) { connection.rollback(); throw error; }
        }
    }
    private DriverManagerDataSource source(String url) { return new DriverManagerDataSource(url, mysql.user(), mysql.password()); }
}
