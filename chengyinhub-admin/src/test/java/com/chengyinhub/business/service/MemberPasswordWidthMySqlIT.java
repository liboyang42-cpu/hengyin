package com.chengyinhub.business.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT", matches="1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberPasswordWidthMySqlIT {
    private final MysqlIntegrationTestConfig config=MysqlIntegrationTestConfig.fromEnvironment("member_password_width");
    private JdbcTemplate admin,db;
    private DriverManagerDataSource source;
    private boolean created;
    private String migration,baseDdl;
    @BeforeAll void setup() throws Exception {
        admin=new JdbcTemplate(source(config.adminUrl()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("SELECT VERSION()",String.class)));
        admin.execute("CREATE DATABASE "+config.databaseName()+" CHARACTER SET utf8mb4"); created=true;
        source=source(config.databaseUrl());db=new JdbcTemplate(source);
        Path root=Files.exists(Paths.get("sql/base_schema_20260818.sql"))?Paths.get(""):Paths.get("..");
        String snapshot=Files.readString(root.resolve("sql/base_schema_20260818.sql"));
        int from=snapshot.indexOf("CREATE TABLE `ums_member` (");int close=snapshot.indexOf("\n) ENGINE",from);int end=snapshot.indexOf(';',close);
        assertTrue(from>=0 && close>from && end>close);baseDdl=snapshot.substring(from,end+1);
        migration=Files.readString(root.resolve("chengyinhub-admin/sql/migration_20261012_member_password_width.sql"));
    }
    @BeforeEach void reset() {db.execute("DROP TABLE IF EXISTS ums_member");db.execute(baseDdl);}
    @AfterAll void cleanup() {
        if(created) {admin.execute("DROP DATABASE "+config.databaseName());assertEquals(0,admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,config.databaseName()));}
    }
    @Test void actualBaseColumnAcceptsBcryptAndPreservesOldValuesAndShape() throws Exception {
        db.update("INSERT INTO ums_member(id,password) VALUES(1,?)","fixture legacy password");
        Map<String,Object> before=shape();assertEquals(50L,((Number)before.get("CHARACTER_MAXIMUM_LENGTH")).longValue());
        applyMigration();Map<String,Object> after=shape();
        for(String key:List.of("CHARACTER_SET_NAME","COLLATION_NAME","IS_NULLABLE","COLUMN_DEFAULT","COLUMN_COMMENT")) assertEquals(before.get(key),after.get(key),key);
        assertEquals("fixture legacy password",db.queryForObject("SELECT password FROM ums_member WHERE id=1",String.class));
        BCryptPasswordEncoder bcrypt=new BCryptPasswordEncoder();String hash=bcrypt.encode("fixture replacement password");assertEquals(60,hash.length());
        assertEquals(1,db.update("UPDATE ums_member SET password=? WHERE id=1",hash));
        String persisted=db.queryForObject("SELECT password FROM ums_member WHERE id=1",String.class);assertEquals(hash,persisted);assertTrue(bcrypt.matches("fixture replacement password",persisted));
        applyMigration();assertEquals(after,shape());assertEquals(hash,db.queryForObject("SELECT password FROM ums_member WHERE id=1",String.class));
    }
    @Test void widerColumnAndItsNullableDefaultAndCommentArePreserved() throws Exception {
        db.execute("ALTER TABLE ums_member MODIFY password VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'fixture' COMMENT 'custom password column'");
        Map<String,Object> before=shape();applyMigration();assertEquals(before,shape());
    }
    @Test void unexpectedColumnShapeIsRejectedBeforeAnyAlter() throws Exception {
        db.execute("ALTER TABLE ums_member MODIFY password TEXT NULL");Map<String,Object> before=shape();
        assertThrows(AssertionError.class,this::applyMigration);assertEquals(before,shape());
    }
    private Map<String,Object> shape() {return db.queryForMap("SELECT DATA_TYPE,CHARACTER_MAXIMUM_LENGTH,CHARACTER_SET_NAME,COLLATION_NAME,IS_NULLABLE,COLUMN_DEFAULT,COLUMN_COMMENT FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ums_member' AND column_name='password'");}
    private DriverManagerDataSource source(String url) {return new DriverManagerDataSource(url,config.user(),config.password());}
    private void applyMigration() throws Exception {
        // ScriptUtils handles comments/quoted semicolons. Every standalone SELECT in this forward is a scalar assertion.
        long expected = migration.lines().filter(line -> line.trim().equals("-- runner: assert=scalar-true")).count();
        assertEquals(2L, expected, "New migration assertions require an explicit test contract update");
        AtomicInteger observed = new AtomicInteger();
        try (Connection connection = source.getConnection()) {
            Connection checked = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                Object result;
                try { result = method.invoke(connection, args); }
                catch (InvocationTargetException e) { throw e.getCause(); }
                if (!method.getName().equals("createStatement")) return result;
                Statement statement = (Statement) result;
                return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                        (statementProxy, operation, parameters) -> {
                    Object value;
                    try { value = operation.invoke(statement, parameters); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                    if (operation.getName().equals("execute") && parameters != null
                            && parameters[0] instanceof String sql
                            && sql.stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT ")) {
                        assertEquals(Boolean.TRUE, value, "runner assertion must return a result");
                        try (ResultSet rows = statement.getResultSet()) {
                            assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); assertFalse(rows.next());
                        }
                        observed.incrementAndGet();
                    }
                    return value;
                });
            });
            ScriptUtils.executeSqlScript(checked, new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8)));
            assertEquals(expected, observed.get(), "All scalar assertions must actually run and be checked");
        }
    }

}
