package com.chengyinhub.business.service.ai;

import com.chengyinhub.business.mapper.AiQuotaReservationMapper;
import com.chengyinhub.business.service.ai.impl.AiGatewayServiceImpl;
import com.chengyinhub.framework.config.RedisConfig;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 专用VPS显式门控：真实Mapper/独立事务/生产Redis序列化和独立SQL+raw Redis回读。 */
@EnabledIfEnvironmentVariable(named="CHENGYIN_AI_RECOVERY_IT",matches="1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(45)
class AiQuotaRecoveryMySqlRedisIT {
    private String adminUrl,schema,user,password;
    private boolean created;
    private DriverManagerDataSource dataSource;
    private AiQuotaReservationMapper mapper;
    private DataSourceTransactionManager transactions;
    private LettuceConnectionFactory redisFactory;
    private RedisTemplate<Object,Object> production;
    private StringRedisTemplate raw;
    private AiQuotaRecoveryService service;
    private final Set<String> owned=new HashSet<>();
    private String counter,receipt;

    private String required(String name) {
        String value=System.getenv(name);assertNotNull(value,"explicit task configuration required: "+name);return value;
    }
    @BeforeAll void connect() throws Exception {
        int mysqlPort=Integer.parseInt(required("CHENGYIN_AI_MYSQL_IT_PORT"));
        assertTrue(mysqlPort>0 && mysqlPort<=65535);
        user=required("CHENGYIN_AI_MYSQL_IT_USER"); password=required("CHENGYIN_AI_MYSQL_IT_PASSWORD");
        adminUrl="jdbc:mysql://127.0.0.1:"+mysqlPort+"/?serverTimezone=UTC";
        schema="chengyin_ai_recovery_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);Statement statement=admin.createStatement()) {
            statement.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4");created=true;
        }
        dataSource=new DriverManagerDataSource(adminUrl.replace("/?","/"+schema+"?"),user,password);
        Path migration=Paths.get("chengyinhub-admin/sql/migration_ai_quota_reservation_recovery_20261013.sql");
        if(!Files.exists(migration)) migration=Paths.get("sql/migration_ai_quota_reservation_recovery_20261013.sql");
        String ddl=new String(Files.readAllBytes(migration),StandardCharsets.UTF_8).split(";",2)[0];
        try(Connection connection=dataSource.getConnection();Statement statement=connection.createStatement()) { statement.execute(ddl); }
        Configuration cfg=new Configuration();
        cfg.setEnvironment(new Environment("task",new SpringManagedTransactionFactory(),dataSource));
        try(InputStream xml=Resources.getResourceAsStream("mapper/business/AiQuotaReservationMapper.xml")) {
            new XMLMapperBuilder(xml,cfg,"mapper/business/AiQuotaReservationMapper.xml",cfg.getSqlFragments()).parse();
        }
        mapper=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg)).getMapper(AiQuotaReservationMapper.class);
        transactions=new DataSourceTransactionManager(dataSource);
        int redisPort=Integer.parseInt(required("CHENGYIN_AI_REDIS_IT_PORT"));
        int database=Integer.parseInt(required("CHENGYIN_AI_REDIS_IT_DATABASE"));
        assertTrue(redisPort>0 && redisPort<=65535 && database>=0 && database<=15);
        RedisStandaloneConfiguration config=new RedisStandaloneConfiguration("127.0.0.1",redisPort);config.setDatabase(database);
        String redisPassword=System.getenv("CHENGYIN_AI_REDIS_IT_PASSWORD");
        if(redisPassword!=null && !redisPassword.isEmpty()) config.setPassword(redisPassword);
        redisFactory=new LettuceConnectionFactory(config);redisFactory.afterPropertiesSet();
        production=new RedisConfig().redisTemplate(redisFactory);raw=new StringRedisTemplate(redisFactory);
        service=new AiQuotaRecoveryService(mapper,production,transactions,org.mockito.Mockito.mock(com.chengyinhub.business.mapper.NpcAsrIntentMapper.class));
    }
    @BeforeEach void identity() {
        String nonce=UUID.randomUUID().toString().replace("-","");
        long member=1000000000000L+Math.floorMod(UUID.randomUUID().getMostSignificantBits(),8000000000000L);
        counter="ai:gen:limit:gen:"+member+":20261013";
        receipt="ai:gen:reservation:"+counter+":"+nonce;
        for(String key:Arrays.asList(counter,counter+":generation",receipt)) {
            assertFalse(Boolean.TRUE.equals(raw.hasKey(key)),"refusing preexisting task key");owned.add(key);
        }
        service.prepare(member,"theme_draft","gen",counter,receipt);
    }
    @AfterEach void cleanupKeys() { if(raw!=null) { for(String key:owned) raw.delete(key);owned.clear(); } }
    @AfterAll void cleanup() throws Exception {
        if(redisFactory!=null) redisFactory.destroy();
        if(created) try(Connection admin=DriverManager.getConnection(adminUrl,user,password);Statement statement=admin.createStatement()) {
            statement.execute("DROP DATABASE `"+schema+"`");
        }
    }
    @SuppressWarnings("unchecked") private Long charge() {
        RedisScript<Long> script=(RedisScript<Long>)ReflectionTestUtils.getField(new AiGatewayServiceImpl(),"RESERVE_QUOTA_SCRIPT");
        return production.execute(script,Arrays.<Object>asList(counter,counter+":generation",receipt),86400,20,0);
    }
    private void reserve() { Long used=charge();assertEquals(1L,used.longValue());service.reserved(receipt,used); }
    private void sql(String statement) throws Exception {
        try(Connection independent=dataSource.getConnection();Statement writer=independent.createStatement()) { writer.execute(statement); }
    }
    private String state() throws Exception {
        try(Connection independent=dataSource.getConnection();PreparedStatement query=independent.prepareStatement(
                "select state from ai_quota_reservation where receipt_key=?")) {
            query.setString(1,receipt);try(ResultSet rs=query.executeQuery()) {assertTrue(rs.next());return rs.getString(1);}
        }
    }
    // 只推进本用例自己的原收据：同类共用一个scratch库，无条件更新会让后续用例的runDue重处理前序行并在其清理后重建Redis收据。
    private void forceDue() throws Exception { sql("update ai_quota_reservation set request_lease_until=date_sub(now(3),interval 1 second),next_attempt_at=now(3) where receipt_key='"+receipt+"'"); }

    @Test void restartAfterChargeBeforeReceiptPersistenceCancelsExactNonce() throws Exception {
        charge();forceDue();
        new AiQuotaRecoveryService(mapper,production,transactions,org.mockito.Mockito.mock(com.chengyinhub.business.mapper.NpcAsrIntentMapper.class)).runDue();
        assertEquals("0",raw.opsForValue().get(counter));assertEquals("0",raw.opsForValue().get(receipt));
        assertEquals("REFUNDED",state());
    }
    @Test void durableDefinitiveFailureSurvivesRedisOutageAndRestart() throws Exception {
        reserve();
        RedisTemplate<Object,Object> unavailable=new RedisTemplate<Object,Object>() {
            @Override public <T> T execute(RedisScript<T> script,RedisSerializer<?> argsSerializer,
                    RedisSerializer<T> resultSerializer,List<Object> keys,Object... args) {
                throw new IllegalStateException("task-injected Redis outage");
            }
        };
        AiQuotaRecoveryService failed=new AiQuotaRecoveryService(mapper,unavailable,transactions,org.mockito.Mockito.mock(com.chengyinhub.business.mapper.NpcAsrIntentMapper.class));
        assertThrows(IllegalStateException.class,()->failed.requireRefund(receipt,"SERVICE_FAILURE"));
        assertEquals("REFUND_DUE",state());assertEquals("1",raw.opsForValue().get(counter));forceDue();
        new AiQuotaRecoveryService(mapper,production,transactions,org.mockito.Mockito.mock(com.chengyinhub.business.mapper.NpcAsrIntentMapper.class)).runDue();
        assertEquals("REFUNDED",state());assertEquals("0",raw.opsForValue().get(counter));
        service.runDue();assertEquals("0",raw.opsForValue().get(counter));
    }
    @Test void expiredReceiptAndExistingCounterIsUncertainEvenBeforeReview() throws Exception {
        charge();raw.delete(receipt);forceDue();service.runDue();
        assertEquals("UNCERTAIN",state());assertEquals("1",raw.opsForValue().get(counter));
    }
    @Test void crashAfterModelStartedDoesNotRefundUnknownOutcome() throws Exception {
        reserve();service.stage(receipt,"MODEL_STARTED");forceDue();service.runDue();
        assertEquals("UNCERTAIN",state());assertEquals("1",raw.opsForValue().get(counter));
    }
    @Test void recreatedCounterCannotBeDebitedByOldGeneration() throws Exception {
        reserve();String oldGeneration=raw.opsForValue().get(counter+":generation");
        raw.delete(counter);raw.delete(counter+":generation");
        raw.opsForValue().set(counter,"1");raw.opsForValue().set(counter+":generation",oldGeneration+"-new");
        service.requireRefund(receipt,"SERVICE_FAILURE");
        assertEquals("1",raw.opsForValue().get(counter));assertEquals("RESOLVED_NO_ACTIVE_RESERVATION",state());
    }
    @Test void concurrentWorkersRefundOneReservationOnlyOnce() throws Exception {
        reserve();sql("update ai_quota_reservation set state='REFUND_DUE',stage='RESULT_CLASSIFIED' where receipt_key='"+receipt+"'");forceDue();
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try { Future<?> one=workers.submit(service::runDue),two=workers.submit(service::runDue);one.get();two.get(); }
        finally {workers.shutdownNow();}
        assertEquals("0",raw.opsForValue().get(counter));assertEquals("REFUNDED",state());
    }
    @Test void retainedViolationNeverEntersRecoveryDebit() throws Exception {
        reserve();service.retain(receipt,"INPUT_BLOCKED");forceDue();service.runDue();
        assertEquals("RETAINED",state());assertEquals("1",raw.opsForValue().get(counter));
    }

    @Test void asrStageIsStickyAndUnknownNeverCompensatesAfterRequestLeaseExpires() throws Exception {
        reserve();service.stage(receipt,"ASR_STARTED");service.stage(receipt,"INPUT_REVIEW_STARTED");
        assertThrows(IllegalStateException.class,()->service.requireRefund(receipt,"SERVICE_FAILURE"));
        assertEquals("RESERVED",state());assertEquals("1",raw.opsForValue().get(counter));
        forceDue();service.runDue();assertEquals("UNCERTAIN",state());assertEquals("1",raw.opsForValue().get(counter));
        assertEquals("ASR_STARTED",mapper.selectByReceipt(receipt).getStage());
    }
    @Test void coldNotSentProofRefundsTheActualOriginalRedisReceiptOnlyOnce() throws Exception {
        reserve();service.stage(receipt,"ASR_STARTED");forceDue();
        sql("update ai_quota_reservation set biz_type='npc_shop_chat' where receipt_key='"+receipt+"'");
        var original=new com.chengyinhub.business.domain.NpcAsrIntent();original.setId(1L);
        var bound=mapper.selectByReceipt(receipt);original.setUserId(bound.getMemberId());
        original.setRequestId("e2596106-82d9-4a4f-9ff2-058414599f90");original.setNodeId(21L);original.setMerchantMemberId(41L);
        original.setClaimToken(bound.getNonce());original.setState("NOT_SENT");original.setQuotaRefundPending(true);
        original.setQuotaReservation(counter+"|"+bound.getNonce());
        var asr=org.mockito.Mockito.mock(com.chengyinhub.business.mapper.NpcAsrIntentMapper.class);
        org.mockito.Mockito.when(asr.selectQuotaRefundPending(100)).thenAnswer(call->original.isQuotaRefundPending()
                ?java.util.List.of(original):java.util.List.of());
        org.mockito.Mockito.when(asr.selectQuotaRefundProof(original.getUserId(),original.getRequestId())).thenReturn(original);
        org.mockito.Mockito.when(asr.completeQuotaRefund(original.getUserId(),original.getRequestId(),original.getQuotaReservation()))
                .thenAnswer(call->{original.setQuotaRefundPending(false);return 1;});
        var restarted=new AiQuotaRecoveryService(mapper,production,transactions,asr);
        restarted.runDue();assertEquals("REFUNDED",state());assertEquals("0",raw.opsForValue().get(counter));assertFalse(original.isQuotaRefundPending());
        restarted.runDue();assertEquals("0",raw.opsForValue().get(counter));assertEquals("REFUNDED",state());
    }

}
