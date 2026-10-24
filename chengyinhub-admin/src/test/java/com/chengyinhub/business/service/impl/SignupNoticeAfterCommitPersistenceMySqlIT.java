package com.chengyinhub.business.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.CommittedBusinessNotice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Public payment callback -> committed notice queue -> independent QR/IM database writes.
 * Registration payment facts use a JDBC fixture adapter. The three production IM mappers,
 * notification dispatcher, both service methods and repository IM migrations are real MySQL.
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
public class SignupNoticeAfterCommitPersistenceMySqlIT {
    private final MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.fromEnvironment(
            "signup_notice_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
    private JdbcTemplate admin;
    private boolean schemaCreated;
    private DriverManagerDataSource source;
    private JdbcTemplate db;
    private TransactionTemplate transaction;
    private ICmsRegistrationService registrationService;
    private final List<Runnable> queued = new ArrayList<>();
    private ScheduledExecutorService executor;
    private AlarmService alarm;
    private final AtomicBoolean failIm = new AtomicBoolean();
    private final AtomicBoolean failQr = new AtomicBoolean();
    private final AtomicReference<Throwable> unexpectedImFailure = new AtomicReference<>();
    private Connection unrelatedConnection;

    @BeforeEach
    void setup() throws Exception {
        admin = new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        admin.execute("create database " + mysql.databaseName() + " character set utf8mb4");
        schemaCreated = true;
        source = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        db = new JdbcTemplate(source);
        db.execute("create table registration_fact(id bigint primary key, payment_status int, registration_status int, transaction_id varchar(64), qrcode_url varchar(256), verification_code varchar(64))");
        db.update("insert into registration_fact values(99,1,1,null,null,null)");
        Path sqlRoot = Paths.get("chengyinhub-admin/sql");
        if (!sqlRoot.toFile().isDirectory()) sqlRoot = Paths.get("sql");
        new ResourceDatabasePopulator(new FileSystemResource(sqlRoot.resolve("migration_im.sql").toFile()),
                new FileSystemResource(sqlRoot.resolve("migration_im_send_idempotency_20260913.sql").toFile()),
                new FileSystemResource(sqlRoot.resolve("migration_20261012_im_media_visibility.sql").toFile())).execute(source);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        executor = mock(ScheduledExecutorService.class);
        when(executor.schedule(any(Runnable.class), eq(10L), eq(TimeUnit.MILLISECONDS))).thenAnswer(call -> {
            queued.add(call.getArgument(0));
            return mock(ScheduledFuture.class);
        });
        alarm = mock(AlarmService.class);
        CommittedBusinessNotice notices = new CommittedBusinessNotice(mock(INotifyService.class), alarm, manager, executor);

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/ImConversationMapper.xml"),
                new ClassPathResource("mapper/business/ImConversationMemberMapper.xml"),
                new ClassPathResource("mapper/business/ImMessageMapper.xml"));
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        ImConversationMemberMapper members = sql.getMapper(ImConversationMemberMapper.class);
        ImServiceImpl im = spy(new ImServiceImpl());
        ReflectionTestUtils.setField(im, "conversationMapper", sql.getMapper(ImConversationMapper.class));
        ReflectionTestUtils.setField(im, "messageMapper", sql.getMapper(ImMessageMapper.class));
        ReflectionTestUtils.setField(im, "memberMapper", members);
        doAnswer(call -> {
            assertIndependentTaskTransaction();
            Object result;
            try { result = call.callRealMethod(); }
            catch (Throwable fixtureFailure) { unexpectedImFailure.set(fixtureFailure); throw fixtureFailure; }
            if (failIm.get()) throw new IllegalStateException("fixture IM post-insert failure");
            return result;
        }).when(im).sendSystem(anyLong(), anyInt(), anyString(), anyString());

        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        when(registrations.selectByRegistrationNo("REG99")).thenAnswer(call -> readRegistration());
        when(registrations.selectCmsRegistrationById(99L)).thenAnswer(call -> readRegistration());
        when(registrations.selectCmsRegistrationByIdForUpdate(99L)).thenAnswer(call -> readRegistration());
        when(registrations.claimPaymentSuccess(eq(99L), anyString(), any())).thenAnswer(call -> db.update(
                "update registration_fact set payment_status=2, registration_status=2, transaction_id=? where id=99 and payment_status=1",
                (String) call.getArgument(1)));
        when(registrations.updateCmsRegistration(any())).thenAnswer(call -> {
            assertIndependentTaskTransaction();
            CmsRegistration r = call.getArgument(0);
            int rows = db.update("update registration_fact set qrcode_url=?, verification_code=? where id=?", r.getQrcodeUrl(), r.getVerificationCode(), r.getId());
            if (failQr.get()) throw new IllegalStateException("fixture QR post-update failure");
            return rows;
        });
        CmsRegistrationServiceImpl target = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(target, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(target, "imService", im);
        ReflectionTestUtils.setField(target, "committedBusinessNotice", notices);
        // W3:回调入口走 inbox fence(业务在 fence 自己的事务里提交),这里用直通桩;
        // 「支付事务提交后才投递」的边界由下方 payInsideTransaction 经同一状态机在调用方事务内验证。
        ReflectionTestUtils.setField(target, "wxPaymentCallbackInboxService",
                com.chengyinhub.business.service.PaymentCallbackInboxStubs.passThrough(manager));
        ReflectionTestUtils.setField(target, "settlementEntryService", mock(ISettlementEntryService.class));
        ReflectionTestUtils.setField(target, "umsMemberMapper", mock(UmsMemberMapper.class));
        ReflectionTestUtils.setField(target, "cmsPointsSettingService", mock(ICmsPointsSettingService.class));
        ReflectionTestUtils.setField(target, "cmsRegistrationScanMapper", mock(CmsRegistrationScanMapper.class));
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsTopic topic = new CmsTopic(); topic.setId(88L);
        when(topics.selectCmsTopicById(88L)).thenReturn(topic);
        ReflectionTestUtils.setField(target, "cmsTopicMapper", topics);
        QrcodeServiceImpl qr = mock(QrcodeServiceImpl.class);
        when(qr.generateQrcode(anyString(), eq(300), eq(300))).thenReturn("fixture-qrcode");
        ReflectionTestUtils.setField(target, "qrcodeService", qr);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(ICmsRegistrationService.class);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        registrationService = (ICmsRegistrationService) proxy.getProxy();
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schemaCreated) {
            admin.execute("drop database " + mysql.databaseName());
            assertEquals(0, admin.queryForObject("select count(*) from information_schema.schemata where schema_name=?", Integer.class, mysql.databaseName()));
        }
    }

    @Test
    void successfulPaymentQueuesOnlyAfterCommitAndBothIndependentWritesReallyPersist() throws Exception {
        payInsideTransaction(false);
        assertEquals(2, queued.size(), "QR and signup IM must both be queued after commit");
        assertPaid();
        assertEquals(0, messages());
        assertNull(db.queryForObject("select qrcode_url from registration_fact where id=99", String.class));
        // A caller transaction is deliberately rolled back around queue execution: REQUIRES_NEW must
        // commit the QR/IM writes and must not accidentally join this transaction.
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            unrelatedConnection = DataSourceUtils.getConnection(source);
            queued.forEach(Runnable::run);
            throw new IllegalStateException("fixture unrelated rollback");
        }));
        assertPaid();
        assertEquals("fixture-qrcode", db.queryForObject("select qrcode_url from registration_fact where id=99", String.class));
        assertTrue(db.queryForObject("select verification_code from registration_fact where id=99", String.class).startsWith("VER"));
        assertNoUnexpectedImFailure();
        assertEquals(1, messages());
        assertEquals(1, db.queryForObject("select unread_count from im_conversation_member where member_id=9", Integer.class));
        assertEquals(88L, new ObjectMapper().readTree(db.queryForObject("select extra_json from im_message", String.class)).path("topicId").asLong());
        assertEquals("报名成功", db.queryForObject("select content from im_message", String.class));
        assertEquals("SUCCESS", registrationService.handleWechatPayCallback(callback()));
        assertEquals(2, queued.size(), "replaying an already completed payment must not enqueue again");
        verifyNoInteractions(alarm);
    }

    @Test
    void rollbackOfThePaymentTransactionDoesNotQueueQrOrSendNotice() {
        assertThrows(IllegalStateException.class, () -> payInsideTransaction(true));
        assertTrue(queued.isEmpty());
        assertEquals(1, db.queryForObject("select payment_status from registration_fact where id=99", Integer.class));
        assertEquals(1, db.queryForObject("select registration_status from registration_fact where id=99", Integer.class));
        assertEquals(0, messages());
        verifyNoInteractions(executor, alarm);
    }

    @Test
    void imFailureAfterActualInsertRollsBackOnlyNoticeAndKeepsCommittedPaymentAndQr() {
        failIm.set(true);
        payInsideTransaction(false);
        queued.forEach(Runnable::run);
        assertPaid();
        assertEquals("fixture-qrcode", db.queryForObject("select qrcode_url from registration_fact where id=99", String.class));
        assertEquals(0, messages());
        assertEquals(0, db.queryForObject("select count(*) from im_conversation", Integer.class));
        verify(alarm).alarm(eq("业务通知未送达"), contains("registration-signup"));
    }

    @Test
    void qrFailureAfterActualUpdateRollsBackOnlyQrAndDoesNotLoseSignupNotice() {
        failQr.set(true);
        payInsideTransaction(false);
        queued.forEach(Runnable::run);
        assertPaid();
        assertNull(db.queryForObject("select qrcode_url from registration_fact where id=99", String.class));
        assertNoUnexpectedImFailure();
        assertEquals(1, messages());
        verify(alarm).alarm(eq("业务通知未送达"), contains("registration-qrcode"));
    }

    @Test
    void rejectedQueueDoesNotTurnCommittedPaymentIntoFailure() {
        when(executor.schedule(any(Runnable.class), eq(10L), eq(TimeUnit.MILLISECONDS)))
                .thenThrow(new RejectedExecutionException("fixture executor stopped"));
        assertDoesNotThrow(() -> payInsideTransaction(false));
        assertPaid();
        assertTrue(queued.isEmpty());
        assertEquals(0, messages());
        verify(alarm, times(2)).alarm(eq("业务通知未送达"), anyString());
    }

    private void payInsideTransaction(boolean rollback) {
        transaction.execute(status -> {
            // 回调入口不允许在外层未提交事务内领取 inbox;同一支付状态机的事务内入口是它。
            assertEquals("SUCCESS", registrationService.applyConfirmedWechatPaymentWithinTransaction(callback()));
            assertTrue(queued.isEmpty(), "external delivery must not enqueue while payment can still roll back");
            assertEquals(0, messages());
            if (rollback) throw new IllegalStateException("fixture payment rollback");
            return null;
        });
    }

    private Map<String, String> callback() {
        Map<String, String> values = new HashMap<>();
        values.put("out_trade_no", "REG99"); values.put("transaction_id", "fixture-tx99");
        values.put("trade_state", "SUCCESS"); values.put("total", "6800");
        return values;
    }

    private CmsRegistration readRegistration() {
        return db.queryForObject("select * from registration_fact where id=99", (row, n) -> {
            CmsRegistration r = new CmsRegistration(); r.setId(99L); r.setRegistrationNo("REG99");
            r.setMemberId(9L); r.setOwnerType(1L); r.setOwnerId(88L);
            r.setWechatPaymentAmount(new BigDecimal("68.00"));
            r.setPaymentStatus(row.getInt("payment_status")); r.setRegistrationStatus(row.getInt("registration_status"));
            r.setTransactionId(row.getString("transaction_id")); r.setQrcodeUrl(row.getString("qrcode_url"));
            return r;
        });
    }

    private void assertIndependentTaskTransaction() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        if (unrelatedConnection != null) assertNotSame(unrelatedConnection, DataSourceUtils.getConnection(source));
        assertPaid();
    }

    private void assertPaid() {
        assertEquals(2, db.queryForObject("select payment_status from registration_fact where id=99", Integer.class));
        assertEquals(2, db.queryForObject("select registration_status from registration_fact where id=99", Integer.class));
        assertEquals("fixture-tx99", db.queryForObject("select transaction_id from registration_fact where id=99", String.class));
    }

    private int messages() { return db.queryForObject("select count(*) from im_message", Integer.class); }

    private void assertNoUnexpectedImFailure() {
        assertNull(unexpectedImFailure.get(), () -> {
            StringBuilder causes = new StringBuilder("Synthetic MySQL notification fixture failed: ");
            for (Throwable cause = unexpectedImFailure.get(); cause != null; cause = cause.getCause()) {
                causes.append(cause).append("\n");
            }
            return causes.toString();
        });
    }
}
