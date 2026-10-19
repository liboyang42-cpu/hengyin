package com.chengyinhub.business.service;

import com.chengyinhub.business.mapper.PlayTeamMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
class NpcSelfPlayExpiryMySqlIT
{
    @Test
    void currentNpcQueryHonorsSelfPlayExpiryAndKeepsSharedAndActivitySemantics() throws Exception
    {
        MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.fromEnvironment(
                "npc_pass_expiry_" + Long.toString(System.nanoTime(), 36));
        assertTrue(mysql.adminUrl().startsWith("jdbc:mysql://127.0.0.1:"));
        boolean created = false;
        try (Connection admin = DriverManager.getConnection(mysql.adminUrl(), mysql.user(), mysql.password());
             Statement ddl = admin.createStatement()) {
            assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.getMetaData().getDatabaseProductVersion()));
            try {
                ddl.execute("CREATE DATABASE `" + mysql.databaseName() + "` CHARACTER SET utf8mb4");
                created = true;
                Configuration config = new Configuration(new Environment("npc-pass-expiry", new JdbcTransactionFactory(),
                        new UnpooledDataSource("com.mysql.cj.jdbc.Driver", mysql.databaseUrl(), mysql.user(), mysql.password())));
                String resource = "mapper/business/PlayTeamMapper.xml";
                try (InputStream xml = Resources.getResourceAsStream(resource)) {
                    new XMLMapperBuilder(xml, config, resource, config.getSqlFragments()).parse();
                }
                try (SqlSession session = new SqlSessionFactoryBuilder().build(config).openSession(true);
                     Statement sql = session.getConnection().createStatement()) {
                    sql.execute("SET time_zone = '+00:00'");
                    sql.execute("SET timestamp = 1700000000");
                    String schema = new String(Files.readAllBytes(Paths.get("../sql/base_schema_20260818.sql")), StandardCharsets.UTF_8);
                    int start = schema.indexOf("CREATE TABLE `cms_registration` (");
                    assertTrue(start >= 0);
                    sql.execute(schema.substring(start, schema.indexOf(";", start)));
                    sql.execute("CREATE TABLE cms_activity (id bigint primary key, topic_id bigint, del_flag int)");
                    sql.execute("INSERT INTO cms_activity VALUES (10,20,0)");
                    insert(sql, 1, 1, 20, 101, 1699999999L);
                    PlayTeamMapper mapper = session.getMapper(PlayTeamMapper.class);
                    assertEquals(0, mapper.hasCurrentNpcRegistration(101L, 20L));
                    assertEquals(1, mapper.hasPaidRegistration(101L, 1, 20L), "shared team query retains its old meaning");
                    sql.execute("UPDATE cms_registration SET expires_at=from_unixtime(1700000000) WHERE id=1");
                    assertEquals(0, mapper.hasCurrentNpcRegistration(101L, 20L), "end boundary is exclusive");
                    sql.execute("UPDATE cms_registration SET expires_at=from_unixtime(1700000001),"
                            + "pay_expire_time=from_unixtime(1600000000) WHERE id=1");
                    assertEquals(1, mapper.hasCurrentNpcRegistration(101L, 20L), "paid usage does not use the old payment deadline");
                    sql.execute("SET timestamp = 1700000001");
                    assertEquals(0, mapper.hasCurrentNpcRegistration(101L, 20L), "same session must not reuse stale positive cache");
                    sql.execute("UPDATE cms_registration SET expires_at=NULL WHERE id=1");
                    assertEquals(1, mapper.hasCurrentNpcRegistration(101L, 20L), "legacy NULL expiry remains valid");
                    sql.execute("UPDATE cms_registration SET payment_status=0 WHERE id=1");
                    assertEquals(0, mapper.hasCurrentNpcRegistration(101L, 20L));
                    sql.execute("UPDATE cms_registration SET payment_status=4 WHERE id=1");
                    assertEquals(0, mapper.hasCurrentNpcRegistration(101L, 20L));
                    sql.execute("UPDATE cms_registration SET payment_status=2,registration_status=3 WHERE id=1");
                    assertEquals(0, mapper.hasCurrentNpcRegistration(101L, 20L));
                    sql.execute("UPDATE cms_registration SET registration_status=2,expires_at=from_unixtime(1699999999) WHERE id=1");
                    insert(sql, 2, 1, 20, 101, 1700000010L);
                    assertEquals(1, mapper.hasCurrentNpcRegistration(101L, 20L), "renewal restores entry without rewriting the old pass");
                    assertEquals(0, mapper.hasCurrentNpcRegistration(102L, 20L));
                    assertEquals(0, mapper.hasCurrentNpcRegistration(101L, 21L));
                    insert(sql, 3, 2, 10, 102, 1699999999L);
                    assertEquals(1, mapper.hasCurrentNpcRegistration(102L, 20L), "activity ticket does not inherit the self-play expiry rule");
                    sql.execute("UPDATE cms_activity SET del_flag=1 WHERE id=10");
                    assertEquals(0, mapper.hasCurrentNpcRegistration(102L, 20L));
                    try (ResultSet count = sql.executeQuery("SELECT count(*) FROM cms_registration")) {
                        assertTrue(count.next());
                        assertEquals(3, count.getInt(1), "qualification reads never delete purchase history");
                    }
                }
            } finally {
                if (created) ddl.execute("DROP DATABASE `" + mysql.databaseName() + "`");
            }
        }
    }

    private void insert(Statement sql, int id, int ownerType, int ownerId, int memberId, long expires) throws Exception
    {
        sql.execute("INSERT INTO cms_registration (id,registration_no,owner_type,owner_id,ticket_id,ticket_price,"
                + "member_id,original_amount,total_amount,payable_amount,payment_status,registration_status,"
                + "pay_expire_time,expires_at,purchase_kind) VALUES ("
                + id + ",'npc-pass-" + id + "'," + ownerType + "," + ownerId + ",0,1," + memberId
                + ",1,1,1,2,2,from_unixtime(1800000000),from_unixtime(" + expires + ")," + (ownerType == 1 ? 2 : 1) + ")");
    }
}
