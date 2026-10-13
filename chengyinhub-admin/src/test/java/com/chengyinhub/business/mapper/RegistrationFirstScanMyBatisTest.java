package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsRegistration;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/** 真 MyBatis + 隔离 H2：扫码完成只写本次订单、首次不可覆盖，不改变核销。不是生产 MySQL 证据。 */
class RegistrationFirstScanMyBatisTest {
    private SqlSessionFactory factory;
    private final Date first = new Date(1900000000000L);

    @BeforeEach
    void setUp() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("CmsRegistration", CmsRegistration.class);
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:first_scan_" + System.nanoTime()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/business/CmsRegistrationMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true); Statement statement = session.getConnection().createStatement()) {
            statement.execute("create table cms_registration(id bigint primary key, member_id bigint, owner_type bigint,"
                    + "owner_id bigint, payment_status int, registration_status int, expires_at timestamp,"
                    + "first_successful_scan_at timestamp, verification_status int, verification_time timestamp, verifier_id bigint)");
            statement.execute("insert into cms_registration(id,member_id,owner_type,owner_id,payment_status,registration_status,verification_status)"
                    + "values(21,9,2,11,2,2,0),(22,9,2,11,2,2,0),(23,10,2,11,2,2,0),(24,9,1,7,2,2,0),(25,9,2,11,0,1,0),(26,9,2,11,4,3,0)");
            statement.execute("update cms_registration set expires_at='2020-01-01 00:00:00' where id=24");
        }
    }

    @Test
    void onlyTheChosenOrderCompletesAndRepeatedScanCannotOverwriteFirstTime() throws Exception {
        try (SqlSession session = factory.openSession(true); Statement statement = session.getConnection().createStatement()) {
            CmsRegistrationMapper mapper = session.getMapper(CmsRegistrationMapper.class);
            assertEquals(1, mapper.markFirstSuccessfulScan(21L, 9L, 2L, 11L, first));
            assertEquals(0, mapper.markFirstSuccessfulScan(21L, 9L, 2L, 11L, new Date(first.getTime() + 10000L)));
            try (ResultSet row = statement.executeQuery("select first_successful_scan_at,verification_status,verification_time,verifier_id from cms_registration where id=21")) {
                assertTrue(row.next());
                assertEquals(first, row.getTimestamp(1));
                assertEquals(0, row.getInt(2));
                assertNull(row.getTimestamp(3));
                assertNull(row.getObject(4));
            }
            assertNull(scanTime(statement, 22));
        }
    }

    @Test
    void wrongMemberScopePendingCancelledAndExpiredPassCannotGainTimestamp() throws Exception {
        try (SqlSession session = factory.openSession(true); Statement statement = session.getConnection().createStatement()) {
            CmsRegistrationMapper mapper = session.getMapper(CmsRegistrationMapper.class);
            assertEquals(0, mapper.markFirstSuccessfulScan(23L, 9L, 2L, 11L, first));
            assertEquals(0, mapper.markFirstSuccessfulScan(21L, 9L, 2L, 12L, first));
            assertEquals(0, mapper.markFirstSuccessfulScan(21L, 9L, 1L, 11L, first));
            assertEquals(0, mapper.markFirstSuccessfulScan(24L, 9L, 1L, 7L, first));
            assertEquals(0, mapper.markFirstSuccessfulScan(25L, 9L, 2L, 11L, first));
            assertEquals(0, mapper.markFirstSuccessfulScan(26L, 9L, 2L, 11L, first));
            for (int id = 21; id <= 26; id++) assertNull(scanTime(statement, id));
        }
    }

    @Test
    void completionTimestampRollsBackWithTheScanTransaction() throws Exception {
        try (SqlSession session = factory.openSession(false)) {
            assertEquals(1, session.getMapper(CmsRegistrationMapper.class)
                    .markFirstSuccessfulScan(21L, 9L, 2L, 11L, first));
            session.rollback();
        }
        try (SqlSession session = factory.openSession(true); Statement statement = session.getConnection().createStatement()) {
            assertNull(scanTime(statement, 21));
        }
    }

    private Date scanTime(Statement statement, int id) throws Exception {
        try (ResultSet row = statement.executeQuery("select first_successful_scan_at from cms_registration where id=" + id)) {
            assertTrue(row.next());
            return row.getTimestamp(1);
        }
    }
}
