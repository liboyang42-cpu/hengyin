package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual sharing API/Spring transactions/MySQL migrations, durable restart and ordering controls. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT",matches="1")
class RoamPresenceRevokeDurabilityMySqlIT {
    private final MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.fromEnvironment(
            "presence_audit_" + UUID.randomUUID().toString().replace("-", "").substring(0,8));
    private JdbcTemplate admin, db;
    private boolean schemaCreated;
    private Path sqlRoot;
    private DataSourceTransactionManager manager;
    private RoamPresenceRevokeIntentMapper intents;
    private ConsentRecordMapper consentMapper;
    private UmsMemberMapper members;
    private ConsentRecordServiceImpl consents;
    private RoamPresenceRevokeAuditService audits;
    private AlarmService alarm;
    private SharingController target;
    private ApiRoamController api;

    public static class SharingController extends ApiRoamController {
        final List<Runnable> queue = new ArrayList<>();
        @Override Long uid() { return 9L; }
        @Override protected void enqueuePresenceAudit(Long memberId, Runnable task) { queue.add(task); }
    }

    @BeforeEach void setup() throws Exception {
        admin = new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(),mysql.user(),mysql.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()",String.class)));
        admin.execute("create database " + mysql.databaseName() + " character set utf8mb4"); schemaCreated=true;
        DriverManagerDataSource source = new DriverManagerDataSource(mysql.databaseUrl(),mysql.user(),mysql.password());
        db = new JdbcTemplate(source);
        sqlRoot = Paths.get("chengyinhub-admin/sql"); if (!sqlRoot.toFile().isDirectory()) sqlRoot=Paths.get("sql");
        migrate("migration_compliance_consent_deregister_20260711.sql", "migration_merchant_onsite_consent_scope_20260815.sql",
                "migration_roam.sql","migration_roam_presence_20260910.sql",
                "migration_20261012_roam_presence_privacy.sql","migration_20261012_roam_presence_revoke_intent.sql");
        db.execute("create table ums_member(id bigint primary key)"); db.update("insert into ums_member values(9)");
        db.update("insert into roam_session(id,member_id,status,presence_visible) values(77,9,0,0),(78,10,0,1)");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/RoamMapper.xml"),
                new ClassPathResource("mapper/business/ConsentRecordMapper.xml"),
                new ClassPathResource("mapper/business/UmsMemberMapper.xml"),
                new ClassPathResource("mapper/business/RoamPresenceRevokeIntentMapper.xml"));
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        intents=sql.getMapper(RoamPresenceRevokeIntentMapper.class); consentMapper=sql.getMapper(ConsentRecordMapper.class);
        members=sql.getMapper(UmsMemberMapper.class); manager=new DataSourceTransactionManager(source);
        alarm=mock(AlarmService.class);
        audits=new RoamPresenceRevokeAuditService(intents,consentMapper,members,alarm,manager);
        consents=new ConsentRecordServiceImpl(); ReflectionTestUtils.setField(consents,"consentRecordMapper",consentMapper);
        target=new SharingController(); ReflectionTestUtils.setField(target,"roamMapper",sql.getMapper(RoamMapper.class));
        ReflectionTestUtils.setField(target,"consentRecordService",consents);
        // 开启分享前要求现行漫游定位同意(F-L5C-01);本 IT 关注撤回持久化,定位同意闸用桩放行。
        ReflectionTestUtils.setField(target,"locationConsent",mock(com.chengyinhub.business.service.impl.RoamLocationConsentService.class));
        ReflectionTestUtils.setField(target,"presenceRevokeAuditService",audits); ReflectionTestUtils.setField(target,"alarmService",alarm);
        ProxyFactory proxy=new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        api=(ApiRoamController)proxy.getProxy();
    }

    private void migrate(String... files) {
        List<FileSystemResource> resources=new ArrayList<>();
        for(String file:files) resources.add(new FileSystemResource(sqlRoot.resolve(file).toFile()));
        new ResourceDatabasePopulator(resources.toArray(new FileSystemResource[0])).execute(manager==null
                ? new DriverManagerDataSource(mysql.databaseUrl(),mysql.user(),mysql.password()) : manager.getDataSource());
    }

    @AfterEach void cleanup() {
        if(admin!=null && schemaCreated) {
            admin.execute("drop database " + mysql.databaseName());
            assertEquals(0,admin.queryForObject("select count(*) from information_schema.schemata where schema_name=?",Integer.class,mysql.databaseName()));
        }
    }

    @Test void lostProcessQueueIsRecoveredFromDurableCloseWithoutReopeningSharing() {
        agree(); String request=close();
        assertEquals(0,sharing()); assertEquals(0,auditCount(request)); assertEquals(0,state(request));
        assertNull(consents.latest(9L,"privacy_policy","roam_presence"),"pending REVOKE must not pretend the earlier AGREE remains current");
        target.queue.clear(); // Simulated restart loses every volatile task.
        RoamPresenceRevokeAuditService restarted=new RoamPresenceRevokeAuditService(intents,consentMapper,members,alarm,manager);
        restarted.retryDue();
        assertEquals(2,state(request)); assertEquals(1,auditCount(request)); assertEquals(0,sharing());
        assertEquals("REVOKE",consents.latest(9L,"privacy_policy","roam_presence").getEventType());
        assertEquals(1,db.queryForObject("select presence_visible from roam_session where id=78",Integer.class));
        assertEquals(1,db.queryForObject("select count(*) from consent_record c join roam_presence_revoke_intent e on e.request_id=c.request_id where c.request_id=? and c.occurred_at=cast(e.occurred_at as datetime)",Integer.class,request));
    }

    @Test void brokenAuditTableCannotPreventClosingAndCanBeRecoveredLater() {
        agree(); db.execute("rename table consent_record to broken_consent_record");
        String request=close(); assertEquals(0,sharing()); assertEquals(0,state(request));
        assertFalse(audits.deliver(request)); assertEquals(0,state(request)); assertEquals(0,sharing());
        db.execute("rename table broken_consent_record to consent_record"); due(request); audits.retryDue();
        assertEquals(2,state(request)); assertEquals(1,auditCount(request)); assertEquals(0,sharing());
        verify(alarm).alarm(eq("位置分享撤回记录失败"),anyString());
    }

    @Test void sameSecondLateRevokeNeverOverridesLaterAgreementOrNewerClose() {
        agree(); String oldClose=close(); agree(); sameSecond();
        assertTrue(audits.deliver(oldClose));
        assertEquals("AGREE",consents.latest(9L,"privacy_policy","roam_presence").getEventType());
        assertTrue(consents.hasCurrentAgreement(9L,"privacy_policy","roam_presence","roam-presence-v1"));
        String middleClose=close(); agree(); String latestClose=close(); sameSecond();
        assertTrue(audits.deliver(latestClose)); assertTrue(audits.deliver(middleClose));
        assertFalse(audits.deliver(latestClose));
        ConsentRecord latest=consents.latest(9L,"privacy_policy","roam_presence");
        assertEquals(latestClose,latest.getRequestId()); assertEquals("REVOKE",latest.getEventType());
        assertFalse(consents.hasCurrentAgreement(9L,"privacy_policy","roam_presence","roam-presence-v1"));
        for(String request:Arrays.asList(oldClose,middleClose,latestClose)) assertEquals(1,auditCount(request));
        assertEquals(0,sharing());
    }

    @Test void twoWorkersCanClaimAndPersistTheSameRevokeOnlyOnce() throws Exception {
        agree(); String request=close(); ExecutorService workers=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            Callable<Boolean> run=()->{start.await();return new RoamPresenceRevokeAuditService(intents,consentMapper,members,alarm,manager).deliver(request);};
            Future<Boolean> a=workers.submit(run),b=workers.submit(run); start.countDown();
            assertEquals(1,(a.get(15,TimeUnit.SECONDS)?1:0)+(b.get(15,TimeUnit.SECONDS)?1:0));
            assertEquals(1,auditCount(request)); assertEquals(2,state(request));
            assertEquals(1,db.queryForObject("select attempt_count from roam_presence_revoke_intent where request_id=?",Integer.class,request));
        } finally {workers.shutdownNow();}
    }

    @Test void expiredLeaseIsReclaimedAndOldWorkerCannotCompleteIt() {
        agree(); String request=close(); assertEquals(1,intents.claim(request,"old-worker"));
        assertFalse(audits.deliver(request));
        db.update("update roam_presence_revoke_intent set lease_until=date_sub(now(6),interval 1 second) where request_id=?",request);
        assertTrue(audits.deliver(request)); assertEquals(0,intents.complete(request,"old-worker"));
        assertEquals(1,auditCount(request)); assertEquals(2,state(request));
    }

    @Test void failedMainTransactionRollsBackCloseAndIntentWithoutScheduling() {
        agree(); target.queue.clear();
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(status->{
            api.changePresenceVisibility(null,false,null);
            throw new IllegalStateException("fixture close transaction rollback");
        }));
        assertEquals(1,sharing()); assertTrue(target.queue.isEmpty());
        assertEquals(0,db.queryForObject("select count(*) from roam_presence_revoke_intent where event_type='REVOKE'",Integer.class));
    }

    @Test void existingHistoryForwardIsRepeatableAndDoesNotReplaceLivePreference() {
        db.update("insert into consent_record(member_id,doc_type,doc_version,scene,event_type,occurred_at,request_id,create_time) values(9,'privacy_policy','roam-presence-v1','roam_presence','REVOKE',now(),'legacy-close',now())");
        migrate("migration_20261012_roam_presence_revoke_intent.sql");
        assertEquals("legacy-close",consents.latest(9L,"privacy_policy","roam_presence").getRequestId());
        agree(); long before=db.queryForObject("select count(*) from roam_presence_revoke_intent",Long.class);
        migrate("migration_20261012_roam_presence_revoke_intent.sql");
        assertEquals(before,db.queryForObject("select count(*) from roam_presence_revoke_intent",Long.class));
        assertEquals("AGREE",consents.latest(9L,"privacy_policy","roam_presence").getEventType());
        assertEquals(1,sharing());
    }

    @Test void repeatedAuditFailureEscalatesHonestlyWithoutLosingClosedFact() {
        agree(); db.execute("rename table consent_record to broken_consent_record"); String request=close();
        for(int attempt=0;attempt<3;attempt++){due(request);assertFalse(audits.deliver(request));}
        assertEquals(3,state(request)); assertEquals(0,sharing());
        assertFalse(audits.deliver(request));
        db.execute("rename table broken_consent_record to consent_record"); assertEquals(0,auditCount(request));
        verify(alarm,times(3)).alarm(eq("位置分享撤回记录失败"),anyString());
    }

    @Test void blockedAuditInsertDoesNotHoldTheSharingOwnerLockOrDelayClosing() throws Exception {
        agree(); String oldClose=close(); agree();
        CountDownLatch enteringInsert=new CountDownLatch(1);
        ConsentRecordMapper observed=mock(ConsentRecordMapper.class,org.mockito.AdditionalAnswers.delegatesTo(consentMapper));
        doAnswer(call->{enteringInsert.countDown();return consentMapper.insertConsentRecord(call.getArgument(0));})
                .when(observed).insertConsentRecord(any(ConsentRecord.class));
        RoamPresenceRevokeAuditService worker=new RoamPresenceRevokeAuditService(intents,observed,members,alarm,manager);
        ExecutorService threads=Executors.newFixedThreadPool(2);
        Future<Boolean> delivery=null;
        try(Connection block=db.getDataSource().getConnection()) {
            block.setAutoCommit(false);
            // An uncommitted unique UUID blocks the real MySQL audit insert until rollback.
            try(PreparedStatement statement=block.prepareStatement("insert into consent_record(member_id,doc_type,doc_version,scene,event_type,occurred_at,request_id,create_time) values(9,'privacy_policy','roam-presence-v1','roam_presence','REVOKE',now(),?,now())")) {
                statement.setString(1,oldClose); statement.executeUpdate();
            }
            try {
                delivery=threads.submit(()->worker.deliver(oldClose));
                assertTrue(enteringInsert.await(5,TimeUnit.SECONDS));
                Future<String> closing=threads.submit(this::close);
                String newClose=closing.get(3,TimeUnit.SECONDS);
                assertEquals(0,sharing()); assertEquals(0,state(newClose));
                assertFalse(delivery.isDone(),"the audit insert must really remain blocked while the close commits");
            } finally {block.rollback();}
            assertTrue(delivery.get(10,TimeUnit.SECONDS));
            assertEquals(0,sharing()); assertEquals(1,auditCount(oldClose));
        } finally {
            threads.shutdown();
            assertTrue(threads.awaitTermination(15,TimeUnit.SECONDS),"fixture workers must finish before scratch schema cleanup");
        }
    }

    @Test void repeatedProcessCrashesEscalateAfterCommitWithoutHoldingADatabaseTransactionForAlarm() {
        agree(); String request=close();
        for(int attempt=0;attempt<3;attempt++) {
            assertEquals(1,intents.claim(request,"crashed-worker-"+attempt));
            db.update("update roam_presence_revoke_intent set lease_until=date_sub(now(6),interval 1 second) where request_id=?",request);
        }
        doAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),
                    "external alarm must not retain the expired claim's transaction or database lock");
            return null;
        }).when(alarm).alarm(eq("位置分享撤回记录失败"),anyString());
        assertFalse(audits.deliver(request)); assertEquals(3,state(request)); assertEquals(0,sharing());
        assertEquals(0,auditCount(request));
        verify(alarm).alarm(eq("位置分享撤回记录失败"),anyString());
    }

    private void agree(){ assertEquals(true,api.changePresenceVisibility("77",true,"roam-presence-v1").get("data")); }
    private String close(){assertEquals(false,api.changePresenceVisibility(null,false,null).get("data"));return db.queryForObject("select request_id from roam_presence_revoke_intent where member_id=9 order by id desc limit 1",String.class);}
    private int sharing(){return db.queryForObject("select presence_visible from roam_session where id=77",Integer.class);}
    private int state(String request){return db.queryForObject("select state from roam_presence_revoke_intent where request_id=?",Integer.class,request);}
    private int auditCount(String request){return db.queryForObject("select count(*) from consent_record where request_id=?",Integer.class,request);}
    private void due(String request){db.update("update roam_presence_revoke_intent set next_attempt_at=date_sub(now(6),interval 1 second) where request_id=?",request);}
    private void sameSecond(){db.update("update consent_record set occurred_at='2026-10-12 00:00:00'");db.update("update roam_presence_revoke_intent set occurred_at='2026-10-12 00:00:00'");}
}
