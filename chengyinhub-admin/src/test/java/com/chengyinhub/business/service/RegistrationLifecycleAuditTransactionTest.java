package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.dto.BusinessAuditLogQueryDTO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.service.impl.BusinessAuditLogQueryServiceImpl;
import com.chengyinhub.business.service.support.RegistrationLifecycleAudit;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.managed.ManagedTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.Reader;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;

/** Real audit mapper + shared JDBC transaction; not the whole payment/refund service graph. */
public class RegistrationLifecycleAuditTransactionTest {
    protected JdbcDataSource ds;
    protected TransactionTemplate tx;
    protected SqlSessionFactory sessions;

    @BeforeEach
    void setUp() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:w10" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("create alias SHA2 for \"com.chengyinhub.business.service.RegistrationLifecycleAuditTransactionTest.sha2\"");
            s.execute("create table cms_registration(id bigint primary key, payment_status int, registration_status int)");
            s.execute("insert into cms_registration values(12,0,1),(13,0,1)");
            // Query-only projection of the existing task identity used by the audit-log exclusion.
            s.execute("create table audit_task(id bigint primary key, biz_type varchar(40) not null, source_type varchar(40))");
            s.execute("create table business_audit_event(id bigint auto_increment primary key,"
                    + "domain_type varchar(16) not null,domain_id bigint not null,owner_member_id bigint not null,"
                    + "actor_type varchar(16) not null default 'UNKNOWN',actor_member_id bigint not null,"
                    + "actor_role_code varchar(32) not null,action varchar(64) not null,target_type varchar(32) not null,"
                    + "target_id bigint,relation_id bigint,before_json varchar(4000),after_json varchar(4000),"
                    + "reason varchar(500),request_id varchar(64) not null,create_time timestamp not null,"
                    + "unique(domain_type,domain_id,action,request_id))");
        }
        Configuration config = new Configuration(new Environment("test", new ManagedTransactionFactory(), ds));
        config.addMapper(BusinessAuditEventMapper.class);
        Path root = Paths.get(System.getProperty("w10.mapperRoot", System.getProperty("user.dir")));
        if ("chengyinhub-admin".equals(root.getFileName().toString())) root = root.getParent();
        Path xml = root.resolve("chengyinhub-system/src/main/resources/mapper/business/BusinessAuditEventMapper.xml");
        try (Reader reader = Files.newBufferedReader(xml)) {
            new XMLMapperBuilder(reader, config, xml.toString(), config.getSqlFragments()).parse();
        }
        sessions = new SqlSessionFactoryBuilder().build(config);
    }

    public static String sha2(String input, int bits) throws Exception {
        if (bits != 256) throw new IllegalArgumentException("Only SHA-256 is used by this mapper");
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte value : hash) hex.append(String.format("%02x", value & 255));
        return hex.toString();
    }

    @Test
    void appendWithoutTransactionFailsBeforeAccessingMapper() {
        assertThrows(IllegalStateException.class, () -> RegistrationLifecycleAudit.append(null, event(12)));
    }

    @Test
    void repeatedClaimAppendsOnceAndUnknownProjectionSurvives() throws Exception {
        transact((c, mapper) -> {
            assertEquals(1, claim(c, 12));
            BusinessAuditEvent sameEvent = event(12);
            RegistrationLifecycleAudit.append(mapper, sameEvent);
            assertEquals(0, claim(c, 12));
            RegistrationLifecycleAudit.append(mapper, sameEvent);
        });
        assertEquals(1, count("business_audit_event"));
        try (SqlSession session = sessions.openSession()) {
            BusinessAuditLogQueryServiceImpl reader = new BusinessAuditLogQueryServiceImpl();
            java.lang.reflect.Field field = reader.getClass().getDeclaredField("businessAuditEventMapper");
            field.setAccessible(true);
            field.set(reader, session.getMapper(BusinessAuditEventMapper.class));
            BusinessAuditEvent projected = reader.query(new BusinessAuditLogQueryDTO(), true).getItems().get(0);
            assertEquals("UNKNOWN", projected.getActorType());
            assertEquals(0L, projected.getActorId());
            assertTrue(projected.getAfterJson().contains("\"content_family_id\":null"));
            assertTrue(projected.getAfterJson().contains("\"metric_eligible\":false"));
        }
    }

    @Test
    void failedSecondAppendRollsBackBothClaimsAndFirstEvent() throws Exception {
        assertThrows(RuntimeException.class, () -> transact((c, mapper) -> {
            assertEquals(1, claim(c, 12));
            RegistrationLifecycleAudit.append(mapper, event(12));
            assertEquals(1, claim(c, 13));
            BusinessAuditEvent invalid = event(13);
            invalid.setTargetType(null); // actual DB NOT NULL failure
            RegistrationLifecycleAudit.append(mapper, invalid);
        }));
        assertEquals(0, count("business_audit_event"));
        assertEquals(2, count("cms_registration where payment_status=0 and registration_status=1"));
    }

    @Test
    void laterFailureRollsBackEligibilityAndAudit() throws Exception {
        assertThrows(RuntimeException.class, () -> transact((c, mapper) -> {
            assertEquals(1, claim(c, 12));
            RegistrationLifecycleAudit.append(mapper, event(12));
            try (Statement s = c.createStatement()) {
                s.executeUpdate("update cms_registration set payment_status=4, registration_status=3 where id=12");
            }
            Date now = new Date();
            RegistrationLifecycleAudit.append(mapper,
                    RegistrationLifecycleAudit.changed(12L, 23L, 4, 3, now, now, "REFUND_INVALIDATED"));
            throw new IllegalStateException("simulated downstream eligibility failure");
        }));
        assertEquals(0, count("business_audit_event"));
        assertEquals(2, count("cms_registration where payment_status=0 and registration_status=1"));
    }

    @Test
    void concurrentCasLoserCannotAppendAnotherEvent() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch claimed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch secondStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Future<?> first = pool.submit(() -> transact((c, mapper) -> {
                assertEquals(1, claim(c, 12));
                claimed.countDown();
                assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
                RegistrationLifecycleAudit.append(mapper, event(12));
            }));
            assertTrue(claimed.await(5, java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.Future<?> second = pool.submit(() -> transact((c, mapper) -> {
                secondStarted.countDown();
                assertEquals(0, claim(c, 12));
                // Losing callback does not create an event; actual service branches have the same winner gate.
            }));
            assertTrue(secondStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            release.countDown();
            first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            second.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertEquals(1, count("business_audit_event"));
    }

    private BusinessAuditEvent event(long id) {
        Date now = new Date();
        return RegistrationLifecycleAudit.changed(id, 23L, 2, 2, now, now, "PAYMENT_CONFIRMED");
    }

    private int claim(Connection c, long id) throws SQLException {
        // Minimal state-claim fixture, not a replacement or validation of the full payment mapper SQL.
        try (PreparedStatement p = c.prepareStatement("update cms_registration set payment_status=2,"
                + "registration_status=2 where id=? and payment_status in (0,1) and registration_status=1")) {
            p.setLong(1, id);
            return p.executeUpdate();
        }
    }

    protected int count(String table) throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select count(*) from " + table)) {
            rs.next(); return rs.getInt(1);
        }
    }

    protected void transact(Work work) {
        final SqlSession[] opened = new SqlSession[1];
        try {
            tx.execute(status -> {
                Connection c = DataSourceUtils.getConnection(ds);
                opened[0] = sessions.openSession(c);
                try { work.run(c, opened[0].getMapper(BusinessAuditEventMapper.class)); }
                catch (Exception e) { throw new IllegalStateException(e); }
                return null;
            });
        } finally { if (opened[0] != null) opened[0].close(); }
    }

    protected interface Work { void run(Connection c, BusinessAuditEventMapper mapper) throws Exception; }
}
