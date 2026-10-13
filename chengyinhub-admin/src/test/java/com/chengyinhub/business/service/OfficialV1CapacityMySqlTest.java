package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.OfficialEventServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 真MySQL原名额/旧RR快照竞争；原V1免费报名不增加付款入口。 */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT", matches="1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OfficialV1CapacityMySqlTest {
    private String database;
    private boolean createdDatabase;
    private JdbcTemplate admin, db;
    private EventSignupMapper signups;
    private OfficialEventServiceImpl service;
    private TransactionTemplate tx;
    @BeforeAll void setup() throws Exception {
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("official_capacity");
        if (!config.adminUrl().startsWith("jdbc:mysql://127.0.0.1:")
                && !config.adminUrl().startsWith("jdbc:mysql://localhost:")) throw new IllegalStateException("scratch localhost only");
        database = "chengyin_official_capacity_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        admin.execute("CREATE DATABASE " + database);
        createdDatabase = true;
        DriverManagerDataSource source = new DriverManagerDataSource(config.databaseUrl().replace(
                "/"+config.databaseName()+"?", "/"+database+"?"), config.user(), config.password());
        db = new JdbcTemplate(source); tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        db.execute("CREATE TABLE official_event(id BIGINT PRIMARY KEY,status INT,signup_limit INT) ENGINE=InnoDB");
        db.execute("CREATE TABLE event_signup(id BIGINT AUTO_INCREMENT PRIMARY KEY,event_id BIGINT NOT NULL,member_id BIGINT NOT NULL,"
                +"status INT,progress INT,reward_claimed INT,create_time DATETIME,update_time DATETIME,UNIQUE KEY uk_event_member(event_id,member_id)) ENGINE=InnoDB");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/EventSignupMapper.xml"));
        signups = new SqlSessionTemplate(factory.getObject()).getMapper(EventSignupMapper.class);
        OfficialEventMapper events = mock(OfficialEventMapper.class);
        when(events.selectOfficialEventByIdForUpdate(1L)).thenAnswer(call -> db.queryForObject(
                "SELECT id,status,signup_limit FROM official_event WHERE id=1 FOR UPDATE", (rs,n) -> {
                    OfficialEvent e = new OfficialEvent(); e.setId(rs.getLong(1)); e.setStatus(rs.getInt(2)); e.setSignupLimit(rs.getInt(3)); return e;
                }));
        service = new OfficialEventServiceImpl(); ReflectionTestUtils.setField(service,"eventMapper",events);
        ReflectionTestUtils.setField(service,"signupMapper",signups);
    }
    @BeforeEach void reset() {
        db.update("DELETE FROM event_signup"); db.update("DELETE FROM official_event");
        db.update("INSERT INTO official_event(id,status,signup_limit) VALUES (1,?,1)",OfficialEvent.ST_SIGNUP);
    }
    @AfterAll void cleanup() { if(createdDatabase) { admin.execute("DROP DATABASE "+database); createdDatabase=false; } }
    @Test void outerOldSnapshotCannotOversellAfterWaitingForOriginalEventLock() throws Exception {
        CountDownLatch snapshot = new CountDownLatch(1), committed = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> old = pool.submit(() -> tx.execute(status -> {
                assertEquals(0,signups.countByEvent(1L)); snapshot.countDown(); await(committed);
                return service.signup(1L,101L);
            }));
            assertTrue(snapshot.await(5,TimeUnit.SECONDS));
            Future<String> winner = pool.submit(() -> tx.execute(status -> service.signup(1L,102L)));
            assertNull(winner.get(10,TimeUnit.SECONDS)); committed.countDown();
            assertEquals("报名人数已满",old.get(10,TimeUnit.SECONDS));
            assertEquals(1,db.queryForObject("SELECT count(*) FROM event_signup WHERE event_id=1",Integer.class));
            assertEquals(102L,db.queryForObject("SELECT member_id FROM event_signup",Long.class));
        } finally { committed.countDown(); pool.shutdownNow(); }
    }
    @Test void originalFreeSignupReentersWithoutSecondSeatOrPayment() {
        assertNull(tx.execute(status -> service.signup(1L,103L)));
        Long originalId=db.queryForObject("SELECT id FROM event_signup",Long.class);
        assertNull(tx.execute(status -> service.signup(1L,103L)));
        assertEquals(originalId,db.queryForObject("SELECT id FROM event_signup",Long.class));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM event_signup",Integer.class));
    }
    private static void await(CountDownLatch latch) {
        try { if(!latch.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("worker timeout"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
