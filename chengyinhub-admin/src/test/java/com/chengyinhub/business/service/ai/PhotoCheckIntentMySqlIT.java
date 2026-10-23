package com.chengyinhub.business.service.ai;

import com.chengyinhub.business.domain.PhotoCheckIntentRow;
import com.chengyinhub.business.mapper.PhotoCheckIntentMapper;
import com.chengyinhub.business.service.ConsentGuard;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real MySQL/production mapper/Spring transactions; provider, consent and media are controlled.
 * No real gameplay/Redis/provider integration is claimed by this fixture. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(45)
class PhotoCheckIntentMySqlIT {
    private static final String HASH = "a".repeat(64);
    @Configuration(proxyBeanMethods=false)
    @EnableTransactionManagement(proxyTargetClass=true)
    static class TxConfiguration { }
    private String adminUrl, schema, user, password;
    private boolean created;
    private DriverManagerDataSource dataSource;
    private DataSourceTransactionManager manager;
    private TransactionTemplate transaction;
    private JdbcTemplate jdbc;
    private AnnotationConfigApplicationContext context;
    private PhotoCheckIntentService service;
    private PhotoCheckIntentMapper mapper;
    private AiGatewayService gateway;
    private PhotoCheckVisionService vision;
    private ConsentGuard consent;
    private AppMediaReferenceGuard media;

    private static String required(String name) {
        String value=System.getenv(name); assertNotNull(value,"explicit task configuration required: "+name);
        if (!name.endsWith("_PASSWORD")) assertFalse(value.isBlank(), "empty task configuration: "+name);
        return value;
    }
    @BeforeAll void connect() throws Exception {
        int port=Integer.parseInt(required("CHENGYIN_AI_MYSQL_IT_PORT")); assertTrue(port>0 && port<=65535);
        user=required("CHENGYIN_AI_MYSQL_IT_USER"); password=required("CHENGYIN_AI_MYSQL_IT_PASSWORD");
        adminUrl="jdbc:mysql://127.0.0.1:"+port+"/?serverTimezone=UTC";
        schema="chengyin_photo_intent_"+UUID.randomUUID().toString().replace("-", "");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password); Statement statement=admin.createStatement()) {
            assertTrue(admin.getMetaData().getDatabaseMajorVersion()>=8,"MySQL 8+ required");
            assertTrue(admin.getMetaData().getDatabaseProductName().contains("MySQL"),"not a replacement database");
            statement.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4"); created=true;
        }
        dataSource=new DriverManagerDataSource(adminUrl.replace("/?","/"+schema+"?"),user,password);
        jdbc=new JdbcTemplate(dataSource);
        Path migration=Paths.get("chengyinhub-admin/sql/migration_photo_check_intent_20261023.sql");
        if(!Files.exists(migration)) migration=Paths.get("sql/migration_photo_check_intent_20261023.sql");
        String ddl=Files.readString(migration).split(";",2)[0]; jdbc.execute(ddl);
        // Minimal C-owned receipt table contract only. C's actual gameplay integration is separate.
        jdbc.execute("CREATE TABLE play_attempt_log (id BIGINT PRIMARY KEY, member_id BIGINT NOT NULL, "
                +"idempotency_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL UNIQUE, request_fingerprint CHAR(64) COLLATE utf8mb4_bin NULL, attempt_type INT NOT NULL)");
        jdbc.execute("CREATE TABLE photo_local_effect_probe (id BIGINT PRIMARY KEY)");
        org.apache.ibatis.session.Configuration cfg=new org.apache.ibatis.session.Configuration();
        cfg.setEnvironment(new Environment("photo-intent",new SpringManagedTransactionFactory(),dataSource));
        try(InputStream xml=Resources.getResourceAsStream("mapper/business/PhotoCheckIntentMapper.xml")) {
            new XMLMapperBuilder(xml,cfg,"mapper/business/PhotoCheckIntentMapper.xml",cfg.getSqlFragments()).parse();
        }
        mapper=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg)).getMapper(PhotoCheckIntentMapper.class);
        manager=new DataSourceTransactionManager(dataSource); transaction=new TransactionTemplate(manager);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        gateway=mock(AiGatewayService.class); vision=mock(PhotoCheckVisionService.class);
        consent=mock(ConsentGuard.class); media=mock(AppMediaReferenceGuard.class);
        context=new AnnotationConfigApplicationContext(); context.register(TxConfiguration.class);
        // These dependencies are already built. In particular, Spring must not populate
        // @Autowired fields inherited by the concrete Mockito Vision/Media mocks.
        context.getBeanFactory().registerSingleton("transactionManager",manager);
        context.getBeanFactory().registerSingleton(PhotoCheckIntentMapper.class.getName(),mapper);
        context.getBeanFactory().registerSingleton(AiGatewayService.class.getName(),gateway);
        context.getBeanFactory().registerSingleton(PhotoCheckVisionService.class.getName(),vision);
        context.getBeanFactory().registerSingleton(ConsentGuard.class.getName(),consent);
        context.getBeanFactory().registerSingleton(AppMediaReferenceGuard.class.getName(),media);
        context.registerBean(PhotoCheckIntentService.class);
        context.refresh(); service=context.getBean(PhotoCheckIntentService.class);
    }
    @BeforeEach void cleanOwnedScratchAndStub() {
        jdbc.update("DELETE FROM photo_check_intent"); jdbc.update("DELETE FROM play_attempt_log"); jdbc.update("DELETE FROM photo_local_effect_probe");
        reset(gateway,vision,consent,media);
        when(media.requireAttachment(eq(7L),anyString(),eq(true))).thenReturn("media-asset:42");
        when(media.processingUrl("media-asset:42",7L,"IMAGE")).thenReturn("transient-url-never-persist");
        when(gateway.planQuotaReservation(eq(7L),eq("photo_check"),anyString())).thenAnswer(call ->
                "ai:gen:limit:photo:7:20261023|"+call.getArgument(2));
    }
    @AfterAll void close() throws Exception {
        if(context!=null) context.close();
        if(created) try(Connection admin=DriverManager.getConnection(adminUrl,user,password); Statement statement=admin.createStatement()) {
            statement.execute("DROP DATABASE `"+schema+"`");
        }
    }
    private PhotoCheckIntentCommand command() { return PhotoCheckIntentServiceTest.command("original-key","submitted-url",HASH); }
    private PhotoCheckIntentView prepare() { return transaction.execute(status->service.prepare(command())); }
    private String raw(String sql,Object...args) throws Exception {
        try(Connection independent=dataSource.getConnection(); PreparedStatement query=independent.prepareStatement(sql)) {
            for(int i=0;i<args.length;i++) query.setObject(i+1,args[i]);
            try(ResultSet rows=query.executeQuery()) { assertTrue(rows.next()); return rows.getString(1); }
        }
    }
    @SuppressWarnings("unchecked") private void normalReview() {
        when(vision.reviewPrepared(any(),any(),any(),any(),any())).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"no business tx during provider work");
            assertEquals("IN_FLIGHT",raw("SELECT state FROM photo_check_intent"));
            assertEquals(call.getArgument(3),raw("SELECT reservation FROM photo_check_intent"));
            Consumer<PhotoCheckVisionService.InputSnapshot> snapshot=call.getArgument(4);
            snapshot.accept(new PhotoCheckVisionService.InputSnapshot("e".repeat(64),"configured-model","prompt-v1","encoder-v1"));
            assertEquals("e".repeat(64),raw("SELECT encoded_input_sha256 FROM photo_check_intent"));
            return new PhotoCheckVisionService.PreparedReview(PhotoCheckVisionResult.concluded(true,87,"original-result"),"RECEIVED","RESULT_RECEIVED");
        });
    }
    private void receipt(long id,String fingerprint) {
        jdbc.update("INSERT INTO play_attempt_log VALUES (?,7,'original-key',?,8)",id,fingerprint);
    }
    @Test void springEnforcesMandatoryAndNeverBeforeAnyMutation() {
        assertThrows(IllegalTransactionStateException.class,()->service.prepare(command()));
        assertThrows(IllegalTransactionStateException.class,()->service.markApplied(1L,HASH,99L));
        assertThrows(IllegalTransactionStateException.class,()->transaction.execute(status->service.executeOnce(7L,1L,HASH)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM photo_check_intent",Integer.class)); verifyNoInteractions(gateway,vision,media,consent);
    }
    @Test void prepareRollbackAndSecurityRejectionsHaveNoDurableIntentOrQuota() {
        transaction.execute(status->{service.prepare(command());status.setRollbackOnly();return null;});
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM photo_check_intent",Integer.class));
        doThrow(new ServiceException("withdrawn consent")).when(consent).requireCurrentAgreement(7L,"photo_model_processing","object_photo");
        assertThrows(ServiceException.class,this::prepare);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM photo_check_intent",Integer.class));
        verify(gateway,never()).consumePlannedQuota(any(),any(),any()); verifyNoInteractions(vision);
    }
    @Test void canonicalSnapshotAndGlobalNonceIdentityAreDurableAndReplayDoesNotReplan() throws Exception {
        PhotoCheckIntentView prepared=prepare(); String nonce=raw("SELECT nonce FROM photo_check_intent");
        assertTrue(nonce.matches("[0-9a-f]{32}"));
        assertEquals("ai:gen:limit:photo:7:20261023|"+nonce,raw("SELECT reservation FROM photo_check_intent"));
        assertEquals("20261023",raw("SELECT original_day FROM photo_check_intent"));
        assertEquals("media-asset:42",raw("SELECT image_reference FROM photo_check_intent"));
        assertFalse(raw("SELECT context_json FROM photo_check_intent").contains("submitted-url"));
        assertEquals(prepared.intentId(),prepare().intentId()); verify(gateway,times(1)).planQuotaReservation(any(),any(),any());
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->jdbc.update(
                "INSERT INTO photo_check_intent (member_id,session_id,action_key,request_fingerprint,context_json,image_reference,reservation,original_day,nonce,state,created_at,updated_at) "
                +"SELECT 99,session_id,'another-key',request_fingerprint,context_json,image_reference,reservation,original_day,nonce,state,created_at,updated_at FROM photo_check_intent"));
    }
    @Test void changedFingerprintCannotReplaceOriginalKeyContextOrNonce() throws Exception {
        prepare(); String nonce=raw("SELECT nonce FROM photo_check_intent");
        assertThrows(ServiceException.class,()->transaction.execute(status->service.prepare(
                PhotoCheckIntentServiceTest.command("original-key","changed-reference","f".repeat(64)))));
        assertEquals(nonce,raw("SELECT nonce FROM photo_check_intent")); assertEquals(HASH,raw("SELECT request_fingerprint FROM photo_check_intent"));
        verify(gateway,times(1)).planQuotaReservation(any(),any(),any()); verifyNoInteractions(vision);
    }
    @Test void competingPreparesKeepOneOriginalUnderRepeatableRead() throws Exception {
        CyclicBarrier snapshotBarrier=new CyclicBarrier(2); ExecutorService workers=Executors.newFixedThreadPool(2);
        Callable<PhotoCheckIntentView> call=()->transaction.execute(status->{
            jdbc.queryForObject("SELECT COUNT(*) FROM photo_check_intent",Integer.class); // establish old RR snapshot
            try {snapshotBarrier.await(10,TimeUnit.SECONDS);} catch(Exception failure) {throw new IllegalStateException(failure);}
            return service.prepare(command());
        });
        try {Future<PhotoCheckIntentView> one=workers.submit(call),two=workers.submit(call);
            assertEquals(one.get(15,TimeUnit.SECONDS).intentId(),two.get(15,TimeUnit.SECONDS).intentId());
        } finally {workers.shutdownNow();}
        assertEquals("1",raw("SELECT COUNT(*) FROM photo_check_intent")); verifyNoInteractions(vision);
    }
    @Test void concurrentExecutionRunsOnceAndLoserOnlySeesInFlight() throws Exception {
        PhotoCheckIntentView prepared=prepare(); CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(vision.reviewPrepared(any(),any(),any(),any(),any())).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); entered.countDown();
            assertTrue(release.await(10,TimeUnit.SECONDS));
            return new PhotoCheckVisionService.PreparedReview(PhotoCheckVisionResult.degraded("first-actual"),"UNKNOWN","PROVIDER_RESULT_UNKNOWN");
        });
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {Future<PhotoCheckIntentView> first=worker.submit(()->service.executeOnce(7L,prepared.intentId(),HASH));
            assertTrue(entered.await(10,TimeUnit.SECONDS)); PhotoCheckIntentView concurrent=service.executeOnce(7L,prepared.intentId(),HASH);
            assertEquals(PhotoCheckIntentState.IN_FLIGHT,concurrent.state());assertNull(concurrent.result());
            release.countDown(); PhotoCheckIntentView stored=first.get(10,TimeUnit.SECONDS);
            assertEquals(PhotoCheckIntentState.RESULT_READY,stored.state());assertTrue(stored.result().isDegraded());
            assertEquals("UNKNOWN",stored.providerState());
        } finally {release.countDown();worker.shutdownNow();}
        service.executeOnce(7L,prepared.intentId(),HASH); verify(vision,times(1)).reviewPrepared(any(),any(),any(),any(),any());
    }
    @Test void independentOriginalResultSurvivesFinalLocalRollbackAndAppliesAtomicallyLater() throws Exception {
        PhotoCheckIntentView prepared=prepare();normalReview();service.executeOnce(7L,prepared.intentId(),HASH);
        assertThrows(IllegalStateException.class,()->transaction.execute(status->{
            receipt(99L,HASH);jdbc.update("INSERT INTO photo_local_effect_probe VALUES (1)");
            service.markApplied(prepared.intentId(),HASH,99L);throw new IllegalStateException("injected final CAS/log failure");
        }));
        assertEquals("RESULT_READY",raw("SELECT state FROM photo_check_intent"));
        assertEquals("0",raw("SELECT COUNT(*) FROM play_attempt_log"));assertEquals("0",raw("SELECT COUNT(*) FROM photo_local_effect_probe"));
        assertEquals("original-result",service.read(7L,prepared.intentId(),HASH).result().getReason());
        transaction.execute(status->{receipt(99L,HASH);jdbc.update("INSERT INTO photo_local_effect_probe VALUES (1)");service.markApplied(prepared.intentId(),HASH,99L);return null;});
        transaction.execute(status->{service.markApplied(prepared.intentId(),HASH,99L);return null;});
        assertEquals("APPLIED",raw("SELECT state FROM photo_check_intent"));assertEquals("99",raw("SELECT attempt_log_id FROM photo_check_intent"));
        verify(vision,times(1)).reviewPrepared(any(),any(),any(),any(),any());verify(gateway,times(1)).planQuotaReservation(any(),any(),any());
    }
    @Test void receiptMustMatchOriginalMemberKeyFingerprintTypeAndReadyState() {
        PhotoCheckIntentView prepared=prepare(); normalReview();service.executeOnce(7L,prepared.intentId(),HASH);
        receipt(99L,"c".repeat(64));
        assertThrows(ServiceException.class,()->transaction.execute(status->{service.markApplied(prepared.intentId(),HASH,99L);return null;}));
        jdbc.update("UPDATE play_attempt_log SET request_fingerprint=?,member_id=99 WHERE id=99",HASH);
        assertThrows(ServiceException.class,()->transaction.execute(status->{service.markApplied(prepared.intentId(),HASH,99L);return null;}));
        jdbc.update("UPDATE play_attempt_log SET member_id=7,attempt_type=4 WHERE id=99");
        assertThrows(ServiceException.class,()->transaction.execute(status->{service.markApplied(prepared.intentId(),HASH,99L);return null;}));
        jdbc.update("UPDATE play_attempt_log SET attempt_type=8,idempotency_key='other-key' WHERE id=99");
        assertThrows(ServiceException.class,()->transaction.execute(status->{service.markApplied(prepared.intentId(),HASH,99L);return null;}));
        jdbc.update("UPDATE play_attempt_log SET idempotency_key='original-key' WHERE id=99");
        transaction.execute(status->{service.markApplied(prepared.intentId(),HASH,99L);return null;});
        assertThrows(ServiceException.class,()->transaction.execute(status->{service.markApplied(prepared.intentId(),HASH,100L);return null;}));
    }
    @Test void restartAndAncientInFlightOrUnknownNeverAllocateOrResend() throws Exception {
        PhotoCheckIntentView prepared=prepare(); String nonce=raw("SELECT nonce FROM photo_check_intent");
        PhotoCheckIntentService restarted=new PhotoCheckIntentService(mapper,gateway,vision,consent,media,manager);
        for(String state:new String[]{"IN_FLIGHT","UNKNOWN"}) {
            jdbc.update("UPDATE photo_check_intent SET state=?, updated_at='2000-01-01 00:00:00'",state);
            PhotoCheckIntentView seen=restarted.executeOnce(7L,prepared.intentId(),HASH);
            assertEquals(state,seen.state().name());assertNull(seen.result());assertEquals(nonce,raw("SELECT nonce FROM photo_check_intent"));
        }
        verify(gateway,times(1)).planQuotaReservation(any(),any(),any());verifyNoInteractions(vision);
    }
    @Test void readWrongOwnerHashAndCorruptedSessionFailClosedWithoutWrites() throws Exception {
        PhotoCheckIntentView prepared=prepare();String originalUpdated=raw("SELECT updated_at FROM photo_check_intent");
        assertThrows(ServiceException.class,()->service.read(99L,prepared.intentId(),HASH));
        assertThrows(ServiceException.class,()->service.read(7L,prepared.intentId(),"d".repeat(64)));
        assertEquals(PhotoCheckIntentState.PREPARED,service.read(7L,prepared.intentId(),HASH).state());
        assertEquals(originalUpdated,raw("SELECT updated_at FROM photo_check_intent"));
        jdbc.update("UPDATE photo_check_intent SET session_id=999");
        assertThrows(ServiceException.class,()->service.read(7L,prepared.intentId(),HASH));verifyNoInteractions(vision);
    }
}
