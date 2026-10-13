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
                    execute(db, "CREATE TABLE cms_activity (id BIGINT PRIMARY KEY, topic_id BIGINT, del_flag INT)");
                    execute(db, "CREATE TABLE cms_registration (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                            + "member_id BIGINT, owner_type INT, owner_id BIGINT, payment_status INT, "
                            + "registration_status INT, expires_at DATETIME(6), pay_expire_time DATETIME(6))");
                    execute(db, "INSERT INTO cms_activity VALUES (51,31,0),(52,31,1)");
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
