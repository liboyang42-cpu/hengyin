package com.chengyinhub.business.service;

import com.chengyinhub.business.mapper.PlayTeamMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Explicitly enabled, loopback-only MySQL fixture. Never uses or recreates a shared schema. */
class NpcCurrentRegistrationMySqlIT {
    @Test
    void currentNpcQueryHonorsExpiryRenewalAndUncachedReadsWithoutChangingSharedRules() throws Exception {
        assumeTrue(Boolean.getBoolean("chengyin.npc.expiry.mysql.it"));
        String host = System.getenv("CHENGYIN_MYBATIS_IT_HOST");
        String port = System.getenv("CHENGYIN_MYBATIS_IT_PORT");
        assertEquals("127.0.0.1", host, "Only the explicitly selected task MySQL loopback is allowed");
        assertNotNull(port, "Set the task MySQL port explicitly");
        assertTrue(port.matches("[0-9]{1,5}"));
        int portNumber = Integer.parseInt(port);
        assertTrue(portNumber > 0 && portNumber <= 65535);
        String user = System.getenv("CHENGYIN_MYBATIS_IT_USER");
        String password = System.getenv("CHENGYIN_MYBATIS_IT_PASSWORD");
        assertNotNull(user);
        assertNotNull(password);
        String schema = "chengyin_npc_expiry_it_" + UUID.randomUUID().toString().replace("-", "");
        String base = "jdbc:mysql://" + host + ":" + port + "/";
        String params = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        boolean created = false;
        try (Connection admin = DriverManager.getConnection(base + params, user, password)) {
            try {
                execute(admin, "CREATE DATABASE `" + schema + "`");
                created = true;
                SqlSessionFactory factory = factory(base + schema + params, user, password);
                try (SqlSession session = factory.openSession(true)) {
                    Connection db = session.getConnection();
                    assertEquals(schema, db.getCatalog());
                    execute(db, "CREATE TABLE cms_activity (id BIGINT PRIMARY KEY, topic_id BIGINT, del_flag INT, end_date DATETIME(6), cancel_time DATETIME(6))");
                    execute(db, "CREATE TABLE cms_registration (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                            + "member_id BIGINT, owner_type INT, owner_id BIGINT, payment_status INT, "
                            + "registration_status INT, purchase_kind INT, expires_at DATETIME(6), pay_expire_time DATETIME(6))");
                    execute(db, "INSERT INTO cms_activity (id,topic_id,del_flag) VALUES (51,31,0),(52,31,1)");
                    execute(db, "INSERT INTO cms_registration "
                            + "(member_id,owner_type,owner_id,payment_status,registration_status,expires_at,pay_expire_time) VALUES "
                            + "(1,1,31,2,2,NOW(6)-INTERVAL 1 DAY,NOW(6)+INTERVAL 1 DAY),"
                            + "(2,1,31,2,2,NOW(6)+INTERVAL 1 DAY,NOW(6)-INTERVAL 1 DAY),"
                            + "(3,1,31,2,2,NULL,NULL),"
                            + "(4,2,51,2,2,NOW(6)-INTERVAL 1 DAY,NULL),"
                            + "(5,2,52,2,2,NULL,NULL),"
                            + "(6,1,31,1,2,NULL,NULL),(7,1,31,2,3,NULL,NULL),"
                            + "(8,1,99,2,2,NULL,NULL),(9,1,31,2,2,NOW(),NULL)");
                    PlayTeamMapper mapper = session.getMapper(PlayTeamMapper.class);
                    assertEquals(0, mapper.hasCurrentNpcRegistration(1L, 31L));
                    assertEquals(1, mapper.hasPaidRegistration(1L, 1, 31L), "Shared paid eligibility stays expiry-agnostic");
                    assertEquals(1, mapper.hasCurrentNpcRegistration(2L, 31L), "Payment deadline is not the pass expiry");
                    assertEquals(1, mapper.hasCurrentNpcRegistration(3L, 31L), "Legacy NULL remains valid");
                    assertEquals(1, mapper.hasCurrentNpcRegistration(4L, 31L), "Activity registration rule is unchanged");
                    assertEquals(1, mapper.hasPaidRegistration(4L, 2, 51L));
                    for (long member = 5; member <= 9; member++) {
                        assertEquals(0, mapper.hasCurrentNpcRegistration(member, 31L), "Rejected member " + member);
                    }
                    assertEquals(0, mapper.hasCurrentNpcRegistration(99L, 31L));
                    execute(db, "INSERT INTO cms_registration "
                            + "(member_id,owner_type,owner_id,payment_status,registration_status,expires_at) "
                            + "VALUES (1,1,31,2,2,NOW(6)+INTERVAL 90 DAY)");
                    assertEquals(1, mapper.hasCurrentNpcRegistration(1L, 31L), "Renewal alongside the old expired row restores entry");

                    execute(db, "INSERT INTO cms_registration "
                            + "(member_id,owner_type,owner_id,payment_status,registration_status,expires_at) "
                            + "VALUES (10,1,31,2,2,NOW(6)+INTERVAL 5 SECOND)");
                    assertEquals(1, mapper.hasCurrentNpcRegistration(10L, 31L));
                    // Raw JDBC deliberately leaves the MyBatis session cache untouched.
                    try (Statement statement = db.createStatement(); ResultSet result = statement.executeQuery(
                            "SELECT SLEEP(GREATEST(0,TIMESTAMPDIFF(MICROSECOND,NOW(6),expires_at)/1000000)+1.1) "
                                    + "FROM cms_registration WHERE member_id=10")) {
                        assertTrue(result.next());
                        assertEquals(0, result.getInt(1));
                    }
                    assertEquals(0, mapper.hasCurrentNpcRegistration(10L, 31L), "Same SqlSession must recheck after ASR crosses expiry");

                    // Fixed database-session time proves the equality boundary without a scheduling race.
                    execute(db, "SET timestamp = 2000000000");
                    try {
                        execute(db, "INSERT INTO cms_activity (id,topic_id,del_flag,end_date) VALUES "
                                + "(61,31,0,NOW()+INTERVAL 1 DAY),(62,31,0,NOW()),"
                                + "(63,31,0,NOW()-INTERVAL 1 DAY),(64,31,0,NULL)");
                        execute(db, "INSERT INTO cms_registration "
                                + "(member_id,owner_type,owner_id,payment_status,registration_status,purchase_kind,expires_at) VALUES "
                                + "(21,2,61,2,2,3,NOW()-INTERVAL 1 DAY),(22,2,62,2,2,3,NULL),"
                                + "(23,2,63,2,2,3,NULL),(24,2,64,2,2,3,NULL),"
                                + "(25,2,64,2,2,1,NULL),(26,2,63,2,2,NULL,NULL),"
                                + "(27,2,63,2,2,2,NULL),(28,1,31,2,2,3,NOW()+INTERVAL 1 DAY)");
                        try (Statement statement = db.createStatement(); ResultSet result = statement.executeQuery(
                                "SELECT end_date = NOW() FROM cms_activity WHERE id=62")) {
                            assertTrue(result.next());
                            assertTrue(result.getBoolean(1), "The equality case must actually equal database NOW");
                        }
                        assertEquals(1, mapper.hasCurrentNpcRegistration(21L, 31L), "Explore uses activity end, not order expires_at");
                        assertEquals(0, mapper.hasCurrentNpcRegistration(22L, 31L), "Exactly at activity end is closed");
                        assertEquals(0, mapper.hasCurrentNpcRegistration(23L, 31L), "Past activity end is closed");
                        assertEquals(0, mapper.hasCurrentNpcRegistration(24L, 31L), "Missing activity end is not eligible");
                        assertEquals(1, mapper.hasCurrentNpcRegistration(25L, 31L), "Ordinary activity ticket keeps its old NULL rule");
                        assertEquals(1, mapper.hasCurrentNpcRegistration(26L, 31L), "Legacy NULL kind keeps its old activity rule");
                        assertEquals(1, mapper.hasCurrentNpcRegistration(27L, 31L), "Other activity kinds keep their old rule");
                        assertEquals(1, mapper.hasCurrentNpcRegistration(28L, 31L), "Owner-one still uses its original pass rule");
                        assertEquals(1, mapper.hasPaidRegistration(22L, 2, 62L), "Shared paid/history predicate stays unchanged");
                        execute(db, "UPDATE cms_activity SET end_date=NOW()+INTERVAL 1 DAY WHERE id=64");
                        assertEquals(1, mapper.hasCurrentNpcRegistration(24L, 31L), "Completing the date restores eligibility in the same SqlSession");
                        execute(db, "UPDATE cms_activity SET end_date=NOW() WHERE id=61");
                        assertEquals(0, mapper.hasCurrentNpcRegistration(21L, 31L), "Same-session uncached read sees newly ended activity");
                        execute(db, "UPDATE cms_activity SET end_date=NOW()+INTERVAL 1 DAY WHERE id=61");
                        assertEquals(1, mapper.hasCurrentNpcRegistration(21L, 31L), "A future end restores eligibility without replacing the order");
                        execute(db, "UPDATE cms_activity SET cancel_time=NOW() WHERE id=61");
                        assertEquals(0, mapper.hasCurrentNpcRegistration(21L, 31L), "Cancelled explore activity loses NPC eligibility before its end");
                        assertEquals(1, mapper.hasPaidRegistration(21L, 2, 61L), "Shared paid/history predicate stays unchanged on cancellation");
                        execute(db, "UPDATE cms_activity SET cancel_time=NULL WHERE id=61");
                        assertEquals(1, mapper.hasCurrentNpcRegistration(21L, 31L), "Same-session uncached read observes cancellation being cleared");
                    } finally {
                        execute(db, "SET timestamp = 0");
                    }
                }
            } finally {
                if (created) execute(admin, "DROP DATABASE `" + schema + "`");
            }
        }
    }

    private static SqlSessionFactory factory(String url, String user, String password) throws Exception {
        Configuration configuration = new Configuration();
        configuration.setCacheEnabled(true);
        configuration.setEnvironment(new Environment("npc-expiry-it", new JdbcTransactionFactory(),
                new UnpooledDataSource("com.mysql.cj.jdbc.Driver", url, user, password)));
        String resource = "mapper/business/PlayTeamMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
