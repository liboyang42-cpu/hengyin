package com.chengyinhub.business.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import static org.junit.jupiter.api.Assertions.*;

/** Actual forward SQL and transaction rollback, only in a newly created disposable MySQL schema. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT",matches="1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LegacyCategoryForwardGuardMySqlIT {
    private final MysqlIntegrationTestConfig mysql=MysqlIntegrationTestConfig.fromEnvironment("legacy_category_guard_it");
    private DriverManagerDataSource source;
    private JdbcTemplate admin,db;
    private boolean created;
    private String migration;

    @BeforeAll void setup() throws Exception {
        admin=new JdbcTemplate(source(mysql.adminUrl()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()",String.class)));
        admin.execute("create database "+mysql.databaseName()+" character set utf8mb4 collate utf8mb4_general_ci");created=true;
        source=source(mysql.databaseUrl());db=new JdbcTemplate(source);
        db.execute("create table cms_template_library(id bigint primary key,title varchar(128),member_id bigint,del_flag int,create_by varchar(64),activity_categoryids varchar(1000)) engine=InnoDB");
        db.execute("create table sys_category(id bigint primary key,category_name varchar(64),type int,status int,del_flag int) engine=InnoDB");
        Path root=Files.exists(Path.of("sql/ry_20250522.sql"))?Path.of(""):Path.of("..");
        migration=Files.readString(root.resolve("chengyinhub-admin/sql/migration_20261012_legacy_game_v51_category_collation.sql"));
    }
    @BeforeEach void resetOwnedFacts() {
        db.execute("drop trigger if exists leave_category_empty");db.execute("delete from cms_template_library");db.execute("delete from sys_category");
        db.execute("insert into sys_category values(41,'美食烹饪',1,1,0),(42,'娱乐达人',1,1,0)");
        db.execute("insert into cms_template_library values(1,'闭眼味觉师',100000,0,'seed_merchant_game_v51_20260827',null),"
                +"(2,'午夜电台',100000,0,'seed_merchant_game_v51_20260827',''),(3,'闭眼味觉师',222,0,'author','999')");
    }
    @AfterAll void cleanup() {
        if(created){admin.execute("drop database "+mysql.databaseName());assertEquals(0,admin.queryForObject("select count(*) from information_schema.schemata where schema_name=?",Integer.class,mysql.databaseName()));}
    }
    @Test void successAndRepeatResolveUniqueCategoriesWithoutOverwritingAuthorFacts() throws Exception {
        apply();apply();assertEquals("41",category(1));assertEquals("42",category(2));assertEquals("999",category(3));
        db.execute("update cms_template_library set activity_categoryids='777' where id=1");apply();assertEquals("777",category(1));
    }
    @Test void missingCategoryFailsBeforeAnyPersistentUpdate() {
        db.execute("delete from sys_category where id=42");assertThrows(Exception.class,this::apply);
        assertNull(category(1));assertEquals("",category(2));assertEquals("999",category(3));
    }
    @Test void wrongSeedOwnershipFailsRatherThanEditingAnUnrelatedAuthor() {
        db.execute("update cms_template_library set create_by='another-author' where id=2");
        assertThrows(Exception.class,this::apply);assertNull(category(1));assertEquals("",category(2));
    }
    @Test void postUpdateReadbackCollisionRollsBackEarlierUpdatesInTheSameTransaction() {
        db.execute("create trigger leave_category_empty before update on cms_template_library for each row "
                +"set NEW.activity_categoryids=if(NEW.id=2,'',NEW.activity_categoryids)");
        assertThrows(Exception.class,this::apply);assertNull(category(1));assertEquals("",category(2));assertEquals("999",category(3));
        db.execute("drop trigger leave_category_empty");
        assertDoesNotThrow(this::apply);assertEquals("41",category(1));assertEquals("42",category(2));
    }
    private void apply() throws Exception {
        try(Connection connection=source.getConnection()){
            connection.setAutoCommit(false);
            try{ScriptUtils.executeSqlScript(connection,new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8)));connection.commit();}
            catch(Exception|Error failure){connection.rollback();throw failure;}
        }
    }
    private String category(long id){return db.queryForObject("select activity_categoryids from cms_template_library where id=?",String.class,id);}
    private DriverManagerDataSource source(String url){return new DriverManagerDataSource(url,mysql.user(),mysql.password());}
}
