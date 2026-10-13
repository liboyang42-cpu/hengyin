package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.GrowthMissionCompleteDTO;
import com.chengyinhub.business.mapper.GrowthMissionMapper;
import com.chengyinhub.business.mapper.GrowthMissionProgressMapper;
import com.chengyinhub.business.mapper.MemberGrowthMapper;
import com.chengyinhub.business.mapper.StoreVerifyGrowthDeliveryMapper;
import com.chengyinhub.business.service.impl.GrowthServiceImpl;
import com.chengyinhub.business.task.StoreVerifyGrowthTask;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GrowthMissionProofMybatisIntegrationTest {
    private String database;
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private DataSource dataSource;
    private IGrowthService service;
    private StoreVerifyGrowthDeliveryMapper deliveryMapper;
    private StoreVerifyGrowthTask task;
    private TransactionTemplate transactions;

    @BeforeAll
    void database() throws Exception {
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("r923");
        if (!config.adminUrl().startsWith("jdbc:mysql://127.0.0.1:")
                && !config.adminUrl().startsWith("jdbc:mysql://localhost:")) {
            throw new IllegalStateException("localhost scratch only");
        }
        database = "chengyin_r923_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        admin.execute("CREATE DATABASE " + database);
        DriverManagerDataSource source = new DriverManagerDataSource(
                config.databaseUrl().replace("/" + config.databaseName() + "?", "/" + database + "?"),
                config.user(), config.password());
        dataSource = source;
        db = new JdbcTemplate(source);
        db.execute("CREATE TABLE growth_mission(id BIGINT PRIMARY KEY AUTO_INCREMENT, mission_code VARCHAR(80) NOT NULL UNIQUE,"
                + " mission_name VARCHAR(120) NOT NULL, mission_desc VARCHAR(255), trigger_event VARCHAR(80) NOT NULL,"
                + " exp_reward INT NOT NULL DEFAULT 0, daily_limit INT NOT NULL DEFAULT 1, status CHAR(1) NOT NULL,"
                + " sort_no INT NOT NULL DEFAULT 0, create_time DATETIME, update_time DATETIME) ENGINE=InnoDB");
        db.execute("CREATE TABLE growth_mission_progress(id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT NOT NULL,"
                + " mission_code VARCHAR(80) NOT NULL, biz_type VARCHAR(40) NOT NULL DEFAULT '', biz_id BIGINT NOT NULL DEFAULT 0,"
                + " progress_date DATE NOT NULL, complete_count INT NOT NULL, reward_exp INT NOT NULL, create_time DATETIME,"
                + " update_time DATETIME, UNIQUE KEY uk_growth_progress_idem(member_id,mission_code,biz_type,biz_id,progress_date)) ENGINE=InnoDB");
        db.execute("CREATE TABLE member_growth(id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT NOT NULL UNIQUE,"
                + " level_no INT NOT NULL, exp_value INT NOT NULL, last_active_time DATETIME, create_time DATETIME,"
                + " update_time DATETIME) ENGINE=InnoDB");
        db.execute("CREATE TABLE cms_registration(id BIGINT PRIMARY KEY, member_id BIGINT NOT NULL,"
                + " payment_status INT, verification_status INT) ENGINE=InnoDB");
        db.execute("CREATE TABLE sys_job(job_id BIGINT PRIMARY KEY AUTO_INCREMENT,job_name VARCHAR(64),"
                + "job_group VARCHAR(64),invoke_target VARCHAR(500),cron_expression VARCHAR(255),"
                + "misfire_policy CHAR(1),concurrent CHAR(1),status CHAR(1),create_by VARCHAR(64),"
                + "create_time DATETIME,remark VARCHAR(500)) ENGINE=InnoDB");
        executeMigration(source, "sql/migration_store_verify_growth_delivery_20260913.sql");
        executeMigration(source, "sql/migration_store_verify_growth_delivery_job_20260913.sql");

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(
                new ClassPathResource("mapper/business/GrowthMissionMapper.xml"),
                new ClassPathResource("mapper/business/GrowthMissionProgressMapper.xml"),
                new ClassPathResource("mapper/business/MemberGrowthMapper.xml"),
                new ClassPathResource("mapper/business/StoreVerifyGrowthDeliveryMapper.xml"));
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        deliveryMapper = sql.getMapper(StoreVerifyGrowthDeliveryMapper.class);
        GrowthServiceImpl target = new GrowthServiceImpl();
        ReflectionTestUtils.setField(target, "growthMissionMapper", sql.getMapper(GrowthMissionMapper.class));
        ReflectionTestUtils.setField(target, "growthMissionProgressMapper", sql.getMapper(GrowthMissionProgressMapper.class));
        ReflectionTestUtils.setField(target, "memberGrowthMapper", sql.getMapper(MemberGrowthMapper.class));
        ReflectionTestUtils.setField(target, "storeVerifyGrowthDeliveryMapper", deliveryMapper);
        DataSourceTransactionManager tx = new DataSourceTransactionManager(source);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        service = (IGrowthService) proxy.getProxy();
        transactions = new TransactionTemplate(tx);
        task = new StoreVerifyGrowthTask(deliveryMapper, service);
    }

    @AfterAll
    void cleanup() {
        if (database == null || admin == null) return;
        admin.execute("DROP DATABASE " + database);
        assertEquals(0, admin.queryForObject(
                "SELECT count(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, database));
    }

    @BeforeEach
    void setup() {
        db.update("DELETE FROM store_verify_growth_delivery");
        db.update("DELETE FROM growth_mission_progress");
        db.update("DELETE FROM member_growth");
        db.update("DELETE FROM growth_mission");
        db.update("DELETE FROM cms_registration");
        db.update("INSERT INTO growth_mission(mission_code,mission_name,trigger_event,exp_reward,status)"
                + " VALUES('RENAMED_STORE_TASK','到店核验','store_verify',7,'0')");
        db.update("INSERT INTO growth_mission(mission_code,mission_name,trigger_event,exp_reward,status)"
                + " VALUES('DAILY_CHECKIN','每日签到','daily_checkin',3,'0')");
        db.update("INSERT INTO growth_mission(mission_code,mission_name,trigger_event,exp_reward,status)"
                + " VALUES('DISABLED_STORE_TASK','已停用到店核验','store_verify',99,'1')");
    }

    @Test
    void storeVerificationCannotBeClaimedByClientMissionCode() {
        GrowthMissionCompleteDTO request = new GrowthMissionCompleteDTO();
        request.setMissionCode("RENAMED_STORE_TASK");

        ServiceException rejection = assertThrows(ServiceException.class,
                () -> service.completeMission(9009L, request));
        assertEquals("该任务需完成到店核验后由系统发放", rejection.getMessage());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM growth_mission_progress", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM member_growth", Integer.class));
    }

    @Test
    void storeEventCollationAliasesCannotBypassVerification() {
        for (String event : new String[] { "STORE_VERIFY", "stóre_verify" }) {
            db.update("UPDATE growth_mission SET trigger_event=? WHERE mission_code='RENAMED_STORE_TASK'", event);
            GrowthMissionCompleteDTO request = new GrowthMissionCompleteDTO();
            request.setMissionCode("RENAMED_STORE_TASK");
            assertThrows(ServiceException.class, () -> service.completeMission(9009L, request));
            assertEquals(0, service.applyEvent(9009L, event, "registration", 501L));
            assertEquals(0, db.queryForObject("SELECT count(*) FROM member_growth", Integer.class));
        }
        db.update("INSERT INTO cms_registration VALUES(501,9009,2,1)");
        assertEquals(1, service.applyEvent(9009L, "STORE_VERIFY", "registration", 501L));
        db.update("UPDATE growth_mission_progress SET progress_date=date_sub(curdate(), interval 1 day)");
        assertEquals(0, service.applyEvent(9009L, "stóre_verify", "registration", 501L));
        assertEquals(7, db.queryForObject("SELECT exp_value FROM member_growth WHERE member_id=9009", Integer.class));
    }

    @Test
    void verifiedStoreEventAwardsOnceAndDuplicateDoesNotAwardAgain() {
        db.update("INSERT INTO cms_registration VALUES(501,9009,2,1)");

        assertEquals(1, service.applyEvent(9009L, "store_verify", "registration", 501L));
        assertEquals(0, service.applyEvent(9009L, "store_verify", "registration", 501L));

        assertEquals(7, db.queryForObject("SELECT exp_value FROM member_growth WHERE member_id=9009", Integer.class));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM growth_mission_progress", Integer.class));
        assertEquals("registration", db.queryForObject("SELECT biz_type FROM growth_mission_progress", String.class));
        assertEquals(501L, db.queryForObject("SELECT biz_id FROM growth_mission_progress", Long.class));
    }

    @Test
    void retryOfYesterdayVerificationDoesNotAwardAgain() {
        db.update("INSERT INTO cms_registration VALUES(501,9009,2,1)");
        db.update("INSERT INTO growth_mission_progress(member_id,mission_code,biz_type,biz_id,progress_date,complete_count,reward_exp)"
                + " VALUES(9009,'RENAMED_STORE_TASK','registration',501,date_sub(curdate(), interval 1 day),1,7)");
        assertEquals(0, service.applyEvent(9009L, "store_verify", "registration", 501L));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM growth_mission_progress", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM member_growth", Integer.class));
    }

    @Test
    void storeEventWithoutVerifiedRegistrationDoesNotAward() {
        assertEquals(0, service.applyEvent(9009L, "store_verify", "registration", 501L));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM growth_mission_progress", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM member_growth", Integer.class));
    }

    @Test
    void legitimateSelfServiceMissionStillWorks() {
        GrowthMissionCompleteDTO request = new GrowthMissionCompleteDTO();
        request.setMissionCode("DAILY_CHECKIN");

        assertEquals(1, service.completeMission(9009L, request));
        assertEquals(3, db.queryForObject("SELECT exp_value FROM member_growth WHERE member_id=9009", Integer.class));
    }

    @Test
    void disabledStoreEventDoesNotAward() {
        db.update("UPDATE growth_mission SET status='1' WHERE mission_code='RENAMED_STORE_TASK'");

        assertEquals(0, service.applyEvent(9009L, "store_verify", "registration", 501L));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM growth_mission_progress", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM member_growth", Integer.class));
    }

    @Test
    void queuedVerificationRecoversWithoutQrOrCandidateStateAfterLostResponse() {
        db.update("INSERT INTO cms_registration VALUES(501,9009,2,1)");
        enqueue(501L);

        assertEquals(0, db.queryForObject("SELECT count(*) FROM growth_mission_progress", Integer.class));
        task.run();

        assertEquals(7, db.queryForObject("SELECT exp_value FROM member_growth WHERE member_id=9009", Integer.class));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM growth_mission_progress", Integer.class));
        assertEquals(1, db.queryForObject(
                "SELECT count(*) FROM store_verify_growth_delivery WHERE registration_id=501 AND processed_at IS NOT NULL",
                Integer.class));
    }

    @Test
    void failedAwardRollsBackThenTaskRecordsFailureAndNextRunRecovers() {
        db.update("INSERT INTO cms_registration VALUES(501,9009,2,1)");
        enqueue(501L);
        db.execute("ALTER TABLE member_growth ADD CONSTRAINT ck_growth_reward_failure CHECK (exp_value = 0)");

        try {
            task.run();

            assertEquals(0, db.queryForObject("SELECT count(*) FROM growth_mission_progress", Integer.class));
            assertEquals(0, db.queryForObject("SELECT count(*) FROM member_growth", Integer.class));
            assertEquals(1, db.queryForObject("SELECT attempt_count FROM store_verify_growth_delivery WHERE registration_id=501",
                    Integer.class));
            assertEquals(1, db.queryForObject("SELECT last_error IS NOT NULL AND processed_at IS NULL "
                    + "FROM store_verify_growth_delivery WHERE registration_id=501", Integer.class));
        } finally {
            db.execute("ALTER TABLE member_growth DROP CHECK ck_growth_reward_failure");
        }
        db.update("UPDATE store_verify_growth_delivery SET next_attempt_at=now() WHERE registration_id=501");
        task.run();

        assertEquals(7, db.queryForObject("SELECT exp_value FROM member_growth WHERE member_id=9009", Integer.class));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM growth_mission_progress", Integer.class));
        assertEquals(1, db.queryForObject("SELECT processed_at IS NOT NULL AND last_error IS NULL "
                + "FROM store_verify_growth_delivery WHERE registration_id=501", Integer.class));
    }

    @Test
    void concurrentCrossDayDeliveryAwardsExactlyOnceUsingEventDate() throws Exception {
        db.update("INSERT INTO cms_registration VALUES(501,9009,2,1)");
        enqueue(501L);
        db.update("UPDATE store_verify_growth_delivery SET create_time=date_sub(now(),interval 1 day) "
                + "WHERE registration_id=501");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> deliverAfter(ready, start, 501L));
            Future<Integer> second = pool.submit(() -> deliverAfter(ready, start, 501L));
            ready.await();
            start.countDown();
            Set<Integer> results = new HashSet<>(Arrays.asList(first.get(), second.get()));

            assertEquals(new HashSet<>(Arrays.asList(0, 1)), results);
            assertEquals(1, db.queryForObject("SELECT count(*) FROM growth_mission_progress", Integer.class));
            assertEquals(7, db.queryForObject("SELECT exp_value FROM member_growth WHERE member_id=9009", Integer.class));
            assertEquals(1, db.queryForObject("SELECT progress_date=date_sub(curdate(),interval 1 day) "
                    + "FROM growth_mission_progress", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void emptyMissionSnapshotIsNotRewrittenByLaterConfiguration() {
        db.update("DELETE FROM growth_mission");
        db.update("INSERT INTO cms_registration VALUES(501,9009,2,1)");
        enqueue(501L);
        assertEquals("[]", db.queryForObject(
                "SELECT missions_json FROM store_verify_growth_delivery WHERE registration_id=501", String.class));
        db.update("INSERT INTO growth_mission(mission_code,mission_name,trigger_event,exp_reward,status)"
                + " VALUES('LATER_STORE_TASK','后来配置','store_verify',88,'0')");

        assertEquals(0, service.deliverStoreVerification(501L));

        assertEquals(0, db.queryForObject("SELECT count(*) FROM growth_mission_progress", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM member_growth", Integer.class));
        assertEquals(1, db.queryForObject("SELECT processed_at IS NOT NULL "
                + "FROM store_verify_growth_delivery WHERE registration_id=501", Integer.class));
    }

    @Test
    void historicalAwardPreventsQueuedRedeliveryAcrossDays() {
        db.update("INSERT INTO cms_registration VALUES(501,9009,2,1)");
        enqueue(501L);
        db.update("UPDATE store_verify_growth_delivery SET create_time=date_sub(now(),interval 1 day) "
                + "WHERE registration_id=501");
        db.update("INSERT INTO growth_mission_progress(member_id,mission_code,biz_type,biz_id,progress_date,complete_count,reward_exp)"
                + " VALUES(9009,'RENAMED_STORE_TASK','registration',501,date_sub(curdate(),interval 2 day),1,7)");

        assertEquals(0, service.deliverStoreVerification(501L));

        assertEquals(1, db.queryForObject("SELECT count(*) FROM growth_mission_progress", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM member_growth", Integer.class));
        assertEquals(1, db.queryForObject("SELECT processed_at IS NOT NULL "
                + "FROM store_verify_growth_delivery WHERE registration_id=501", Integer.class));
    }

    @Test
    void legacyVerifiedRegistrationWithoutQueueIsNotAutoAwarded() {
        db.update("INSERT INTO cms_registration VALUES(501,9009,2,1)");

        task.run();

        assertEquals(0, db.queryForObject("SELECT count(*) FROM store_verify_growth_delivery", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM growth_mission_progress", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM member_growth", Integer.class));
    }

    @Test
    void unverifiedOrUnpaidRegistrationCannotBeEnqueued() {
        db.update("INSERT INTO cms_registration VALUES(501,9009,2,0),(502,9009,1,1)");

        assertThrows(IllegalStateException.class, () -> enqueue(501L));
        assertThrows(IllegalStateException.class, () -> enqueue(502L));
        assertThrows(IllegalStateException.class, () -> enqueue(999L));

        assertEquals(0, db.queryForObject("SELECT count(*) FROM store_verify_growth_delivery", Integer.class));
    }

    @Test
    void jobMigrationRegistersOneRunnableTaskIdempotentlyInScratch() throws Exception {
        executeMigration(dataSource, "sql/migration_store_verify_growth_delivery_job_20260913.sql");

        assertEquals(1, db.queryForObject("SELECT count(*) FROM sys_job "
                + "WHERE invoke_target='storeVerifyGrowthTask.run()'", Integer.class));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM sys_job WHERE invoke_target='storeVerifyGrowthTask.run()' "
                + "AND cron_expression='0 * * * * ?' AND misfire_policy='3' AND concurrent='1' AND status='0'",
                Integer.class));
    }

    private void enqueue(Long registrationId) {
        transactions.executeWithoutResult(status -> service.enqueueStoreVerification(registrationId));
    }

    private int deliverAfter(CountDownLatch ready, CountDownLatch start, Long registrationId) throws Exception {
        ready.countDown();
        assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
        return service.deliverStoreVerification(registrationId);
    }

    private static void executeMigration(DataSource source, String path) throws Exception {
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(path));
        }
    }
}
