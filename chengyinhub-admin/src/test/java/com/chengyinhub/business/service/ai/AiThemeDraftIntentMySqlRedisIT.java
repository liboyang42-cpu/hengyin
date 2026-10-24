package com.chengyinhub.business.service.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.ai.*;
import com.chengyinhub.business.domain.vo.ai.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.AiCreatorServiceImpl;
import com.chengyinhub.business.service.impl.AiThemeDraftRequestService;
import com.chengyinhub.framework.config.RedisConfig;
import com.chengyinhub.common.config.AiProviderProperties;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.beans.BeanUtils;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real E/A services, MySQL commits/CAS/mappers and Redis Lua; no model/security HTTP. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(45)
class AiThemeDraftIntentMySqlRedisIT {
    private static final String MIGRATION="migration_ai_theme_draft_request_intent_20261024.sql";
    private static final String ANSWER="{\"plans\":[{\"title\":\"原草稿\",\"storyline\":\"路线故事\",\"nodes\":[{\"merchantName\":\"第一站\"}]}]}";
    private String adminUrl,user,password,schema;
    private final Set<String> ownedSchemas=new LinkedHashSet<>(),ownedKeys=new HashSet<>();
    private JdbcTemplate db;
    private DataSourceTransactionManager transactions;
    private AiThemeDraftIntentMapper intents;
    private AiQuotaReservationMapper quotas;
    private org.apache.ibatis.session.Configuration mappedConfiguration;
    private final Set<Long> ownedMembers=new HashSet<>();
    private LettuceConnectionFactory redisFactory;
    private RedisTemplate<Object,Object> production;
    private StringRedisTemplate raw;
    private AiNotSentGatewayFixture fixture;
    private AiCreatorServiceImpl creator;
    private final AtomicReference<String> role=new AtomicReference<>("player");
    private final AtomicBoolean bodyWriteFault=new AtomicBoolean(),ambiguousClaimCommit=new AtomicBoolean(),ambiguousOutcomeCommit=new AtomicBoolean();
    private final List<AnnotationConfigApplicationContext> contexts=new ArrayList<>();
    private AiThemeDraftRequestService service;
    private long member;
    private String prepareKey;

    @BeforeAll void connect() throws Exception {
        int mysqlPort=Integer.parseInt(required("CHENGYIN_AI_MYSQL_IT_PORT",false));
        assertTrue(mysqlPort>0&&mysqlPort<=65535); user=required("CHENGYIN_AI_MYSQL_IT_USER",false);
        password=required("CHENGYIN_AI_MYSQL_IT_PASSWORD",true);
        adminUrl="jdbc:mysql://127.0.0.1:"+mysqlPort+"/?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
        try(Connection c=DriverManager.getConnection(adminUrl,user,password)) {
            assertEquals("MySQL",c.getMetaData().getDatabaseProductName()); assertTrue(c.getMetaData().getDatabaseMajorVersion()>=8);
        }
        schema=newSchema("main");
        DriverManagerDataSource source=source(schema); db=new JdbcTemplate(source); applyMigration(db,migration());
        db.execute(statements(Files.readString(sqlDirectory().resolve("migration_ai_quota_reservation_recovery_20261013.sql"))).get(0));
        org.apache.ibatis.session.Configuration config=new org.apache.ibatis.session.Configuration();
        config.setEnvironment(new Environment("theme-intent",new SpringManagedTransactionFactory(),source));
        for(String path:List.of("mapper/business/AiThemeDraftIntentMapper.xml","mapper/business/AiQuotaReservationMapper.xml"))
            try(InputStream xml=Resources.getResourceAsStream(path)) {new XMLMapperBuilder(xml,config,path,config.getSqlFragments()).parse();}
        SqlSessionTemplate session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        mappedConfiguration=config;
        intents=session.getMapper(AiThemeDraftIntentMapper.class); quotas=session.getMapper(AiQuotaReservationMapper.class);
        transactions=spy(new DataSourceTransactionManager(source));
        doAnswer(c->{c.callRealMethod();
            if(ambiguousClaimCommit.get()&&db.queryForObject("SELECT COUNT(*) FROM ai_theme_draft_request_intent WHERE member_id=? AND state='PROCESSING'",Integer.class,member)>0
                    &&ambiguousClaimCommit.compareAndSet(true,false)) throw new IllegalStateException("controlled committed-claim acknowledgement loss");
            if(ambiguousOutcomeCommit.get()&&db.queryForObject("SELECT COUNT(*) FROM ai_theme_draft_request_intent WHERE member_id=? AND state='SUCCEEDED'",Integer.class,member)>0
                    &&ambiguousOutcomeCommit.compareAndSet(true,false)) throw new IllegalStateException("controlled committed-outcome acknowledgement loss");
            return null;}).when(transactions).commit(any());
        int redisPort=Integer.parseInt(required("CHENGYIN_AI_REDIS_IT_PORT",false));
        int database=Integer.parseInt(required("CHENGYIN_AI_REDIS_IT_DATABASE",false));
        assertTrue(redisPort>0&&redisPort<=65535&&database>=0&&database<=15);
        RedisStandaloneConfiguration r=new RedisStandaloneConfiguration("127.0.0.1",redisPort);r.setDatabase(database);
        String redisPassword=System.getenv("CHENGYIN_AI_REDIS_IT_PASSWORD");if(redisPassword!=null&&!redisPassword.isEmpty())r.setPassword(redisPassword);
        redisFactory=new LettuceConnectionFactory(r);redisFactory.afterPropertiesSet();
        production=new RedisConfig().redisTemplate(redisFactory);raw=new StringRedisTemplate(redisFactory);
    }
    @BeforeEach void originalMember() {
        member=1000000000000L+Math.floorMod(UUID.randomUUID().getMostSignificantBits(),8000000000000L);
        ownedMembers.add(member);
        prepareKey=UUID.randomUUID().toString();role.set("player");bodyWriteFault.set(false);ambiguousClaimCommit.set(false);ambiguousOutcomeCommit.set(false);
        fixture=new AiNotSentGatewayFixture(member);
        AiProviderProperties properties=new AiProviderProperties();properties.setDefaultProvider("deepseek");
        AiProviderProperties.ProviderConfig provider=new AiProviderProperties.ProviderConfig();
        provider.setEnabled(true);provider.setModel("controlled-model");properties.setProviders(Map.of("deepseek",provider));
        ReflectionTestUtils.setField(fixture.gateway,"providerProps",properties);
        ReflectionTestUtils.setField(fixture.gateway,"sceneRouter",new AiSceneRouter());
        ReflectionTestUtils.setField(fixture.gateway,"llmClientsByBeanName",Map.of("deepseekLlmClient",fixture.model));
        Object modelConfig=ReflectionTestUtils.getField(fixture.gateway,"aiModelConfig");
        ReflectionTestUtils.setField(modelConfig,"enabled",true);ReflectionTestUtils.setField(modelConfig,"timeoutMs",8000);
        AiQuotaRecoveryService recovery=new AiQuotaRecoveryService(quotas,production,transactions,org.mockito.Mockito.mock(com.chengyinhub.business.mapper.NpcAsrIntentMapper.class));
        ReflectionTestUtils.setField(fixture.gateway,"redisTemplate",production);
        ReflectionTestUtils.setField(fixture.gateway,"quotaRecovery",recovery);
        UmsMember original=new UmsMember();original.setId(member);original.setStatus(1L);original.setDelFlag(0);original.setRole("player");
        IUmsMemberService members=mock(IUmsMemberService.class);
        when(members.selectUmsMemberById(member)).thenAnswer(c->{original.setRole(role.get());return original;});
        ReflectionTestUtils.setField(fixture.gateway,"umsMemberService",members);
        IRoleService roles=mock(IRoleService.class);when(roles.resolveRole(any())).thenAnswer(c->role.get());
        creator=spy(new AiCreatorServiceImpl());
        ReflectionTestUtils.setField(creator,"umsMemberService",members);ReflectionTestUtils.setField(creator,"roleService",roles);
        ReflectionTestUtils.setField(creator,"gateway",fixture.gateway);ReflectionTestUtils.setField(creator,"redisTemplate",production);
        ReflectionTestUtils.setField(creator,"quotaRecovery",recovery);
        ReflectionTestUtils.setField(creator,"wxContentSecurityService",fixture.safety);
        when(fixture.model.chat(anyString(),anyString())).thenReturn(ANSWER);
        service=wiredService();
        for(String pool:List.of("ai:theme:draft:player:","ai:gen:limit:gen:")) {
            String counter=pool+member+":"+LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.BASIC_ISO_DATE);
            assertFalse(Boolean.TRUE.equals(raw.hasKey(counter)),"refuse existing task counter");ownedKeys.add(counter);ownedKeys.add(counter+":generation");
        }
    }
    private AiThemeDraftRequestService wiredService() {return wiredService(intents);}
    private AiThemeDraftRequestService wiredService(AiThemeDraftIntentMapper selectedMapper) {
        RedisTemplate<Object,Object> bodyBoundary=new RedisTemplate<>() {
            @Override public <T>T execute(RedisScript<T> script,RedisSerializer<?> args,RedisSerializer<T> result,List<Object> keys,Object...values) {
                if(bodyWriteFault.get()&&values.length==2)throw new IllegalStateException("controlled body-cache write failure");
                return production.execute(script,args,result,keys,values);
            }
        };
        AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("theme-it",Map.of("ai.themeDraftIntent.enabled","true")));
        // Completed spies/mocks registered as singletons: Spring must not populate their @Autowired fields.
        context.getBeanFactory().registerSingleton("creator",creator);context.getBeanFactory().registerSingleton("intentMapper",selectedMapper);
        context.getBeanFactory().registerSingleton("transactions",transactions);context.getBeanFactory().registerSingleton("redis",bodyBoundary);
        context.register(AiThemeDraftRequestService.class);context.refresh();contexts.add(context);
        return context.getBean(AiThemeDraftRequestService.class);
    }
    @AfterEach void cleanOwnKeys() {
        ambiguousClaimCommit.set(false);ambiguousOutcomeCommit.set(false);bodyWriteFault.set(false);
        for(var c:contexts)c.close();contexts.clear();
        if(db!=null&&raw!=null&&member>0) {
            for(Long owner:ownedMembers)
            for(var row:db.queryForList("SELECT request_id,counter_key,receipt_key FROM ai_theme_draft_request_intent WHERE member_id=?",owner)) {
                String id=String.valueOf(row.get("request_id"));assertTrue(id.matches("td2_[a-f0-9]{32}"));
                ownedKeys.add(bodyKey("context",owner,id));ownedKeys.add(bodyKey("result",owner,id));
                String counter=String.valueOf(row.get("counter_key"));assertTrue(counter.contains(":"+owner+":"));
                ownedKeys.add(counter);ownedKeys.add(counter+":generation");ownedKeys.add(String.valueOf(row.get("receipt_key")));
            }
            for(String key:ownedKeys)raw.delete(key);
            for(String key:ownedKeys)assertFalse(Boolean.TRUE.equals(raw.hasKey(key)),"owned key cleanup readback");
            ownedKeys.clear();
            assertTrue(ownedSchemas.contains(schema));
            for(Long owner:ownedMembers) {
                db.update("DELETE FROM ai_theme_draft_request_intent WHERE member_id=?",owner);
                db.update("DELETE FROM ai_quota_reservation WHERE member_id=?",owner);
            }
            ownedMembers.clear();
        }
    }
    @AfterAll void dropOnlyOwnedSchemas() throws Exception {
        for(var c:contexts)c.close();
        try {
            for(String owned:ownedSchemas) {
                try(Connection c=DriverManager.getConnection(adminUrl,user,password);Statement s=c.createStatement()) {s.execute("DROP DATABASE `"+owned+"`");}
                assertEquals(0,new JdbcTemplate(new DriverManagerDataSource(adminUrl,user,password)).queryForObject(
                        "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,owned));
            }
        } finally {if(redisFactory!=null)redisFactory.destroy();}
    }

    @Test void successSurvivesCacheLossAndRequestReplay() {
        var first=prepare();var original=row(first);assertEquals(0,quotaRows());
        verifyNoInteractions(fixture.safety,fixture.logs);verify(fixture.model,never()).chat(anyString(),anyString());
        var result=execute(first);assertEquals(AiThemeDraftIntentState.SUCCEEDED,result.state());assertTrue(result.resultAvailable());
        assertEquals("原草稿",result.result().getDraft().getTitle());assertEquals("1",raw.opsForValue().get(String.valueOf(original.get("counter_key"))));
        assertEquals(1,quotaRows());assertEquals(original.get("nonce"),quotaRow().get("nonce"));
        assertEquals("player-draft",quotaRow().get("pool"));assertEquals(first.requestId(),prepare().requestId());
        assertEquals(result.result().getTraceId(),execute(first).result().getTraceId());
        raw.delete(bodyKey("context",first.requestId()));raw.delete(bodyKey("result",first.requestId()));
        service=wiredService();var read=service.readIntent(member,first.requestId());
        assertEquals(AiThemeDraftIntentState.SUCCEEDED,read.state());assertFalse(read.resultAvailable());
        assertEquals("AI_DRAFT_RESULT_UNAVAILABLE",read.reasonCode());refusal(first,"AI_DRAFT_RESULT_UNAVAILABLE");
        assertEquals(original.get("nonce"),row(first).get("nonce"));verify(fixture.model,times(1)).chat(anyString(),anyString());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name='ai_theme_draft_request_intent' AND data_type IN ('json','text','longtext','mediumtext')",Integer.class,schema));
        assertFalse(row(first).values().toString().contains("private-idea"));
    }
    @Test void genPoolConsumesPersistedPlanWithoutPlayerDoubleCharge() {
        role.set("club");var first=prepare();var original=row(first);assertEquals(0,quotaRows());
        execute(first);assertEquals(1,quotaRows());assertEquals("gen",quotaRow().get("pool"));
        assertEquals(original.get("nonce"),quotaRow().get("nonce"));assertEquals(original.get("receipt_key"),quotaRow().get("receipt_key"));
        assertEquals("1",raw.opsForValue().get(String.valueOf(original.get("counter_key"))));
        verify(fixture.model,times(1)).chat(anyString(),anyString());
    }
    @Test void concurrentPrepareCommitsOneOriginalIdentityWithoutEconomicEffects() throws Exception {
        CountDownLatch planned=new CountDownLatch(2),release=new CountDownLatch(1);
        doAnswer(c->{Object plan=c.callRealMethod();planned.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return plan;})
                .when(creator).planThemeDraft(eq(member),anyString());
        AiThemeDraftRequestService other=wiredService();ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<AiThemeDraftIntentView> one=pool.submit(this::prepare);
            Future<AiThemeDraftIntentView> two=pool.submit(()->other.prepare(member,new AiThemeDraftPrepareReq(prepareKey,"private-idea",null,null)));
            assertTrue(planned.await(10,TimeUnit.SECONDS));release.countDown();
            var a=one.get(10,TimeUnit.SECONDS);var b=two.get(10,TimeUnit.SECONDS);assertEquals(a.requestId(),b.requestId());
            assertNotEquals(a.replayed(),b.replayed());assertEquals(a.requestId(),service.readPrepared(member,prepareKey).requestId());
        } finally {release.countDown();pool.shutdownNow();}
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ai_theme_draft_request_intent WHERE member_id=?",Integer.class,member));
        assertEquals(0,quotaRows());verify(fixture.model,never()).chat(anyString(),anyString());verifyNoInteractions(fixture.safety,fixture.logs);
    }
    @Test void concurrentClaimRunsRealCreatorOnceAndLoserOnlySeesInFlight() throws Exception {
        var first=prepare();String nonce=String.valueOf(row(first).get("nonce"));
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(fixture.model.chat(anyString(),anyString())).thenAnswer(c->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return ANSWER;});
        AiThemeDraftRequestService other=wiredService();ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            Future<AiThemeDraftIntentView> winner=pool.submit(()->execute(first));assertTrue(entered.await(10,TimeUnit.SECONDS));
            var loser=assertThrows(AiThemeDraftRequestService.IntentRejectedException.class,()->other.execute(member,new AiThemeDraftExecuteReq(first.requestId())));
            assertEquals("AI_DRAFT_IN_PROGRESS",loser.getErrorCode());assertEquals(AiThemeDraftIntentState.PROCESSING,loser.getIntent().state());
            db.update("UPDATE ai_theme_draft_request_intent SET claimed_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 4 MINUTE) WHERE request_id=?",first.requestId());
            refusal(first,"AI_DRAFT_OUTCOME_UNKNOWN");release.countDown();assertEquals(AiThemeDraftIntentState.SUCCEEDED,winner.get(10,TimeUnit.SECONDS).state());
        } finally {release.countDown();pool.shutdownNow();}
        assertEquals(nonce,row(first).get("nonce"));assertEquals(1,quotaRows());verify(fixture.model,times(1)).chat(anyString(),anyString());
    }
    @Test void typedUnknownSurvivesBodyDeletionAndRestartWithoutRefundOrResend() {
        when(fixture.model.chat(anyString(),anyString())).thenThrow(new IllegalStateException("controlled post-dispatch unknown"));
        var first=prepare();refusal(first,"AI_DRAFT_OUTCOME_UNKNOWN");var original=row(first);
        raw.delete(bodyKey("context",first.requestId()));service=wiredService();
        assertEquals(first.requestId(),service.readPrepared(member,prepareKey).requestId());refusal(first,"AI_DRAFT_OUTCOME_UNKNOWN");
        assertEquals(AiThemeDraftIntentState.UNKNOWN,prepare().state());assertEquals("1",raw.opsForValue().get(String.valueOf(original.get("counter_key"))));
        assertEquals(original.get("nonce"),quotaRow().get("nonce"));verify(fixture.model,times(1)).chat(anyString(),anyString());
    }
    @Test void typedNotSentRefundsOriginalPlanOnceButKeepsDurableFailedIdentity() {
        when(fixture.model.chat(anyString(),anyString())).thenThrow(new AiProviderNotSentException());
        var first=prepare();var result=execute(first);assertEquals(AiThemeDraftIntentState.FAILED,result.state());
        assertEquals("0",raw.opsForValue().get(String.valueOf(row(first).get("counter_key"))));
        assertEquals("REFUNDED",quotaRow().get("state"));assertEquals(row(first).get("nonce"),quotaRow().get("nonce"));
        assertEquals(AiThemeDraftIntentState.FAILED,execute(first).state());assertEquals(first.requestId(),prepare().requestId());
        assertEquals(1,quotaRows());verify(fixture.model,times(1)).chat(anyString(),anyString());
    }
    @Test void prepareAndOwnerRefusalsHaveNoQuotaReviewOrModelEffects() {
        var first=prepare();assertEquals(first.requestId(),service.readPrepared(member,prepareKey).requestId());
        var conflict=assertThrows(AiThemeDraftRequestService.IntentRejectedException.class,()->service.prepare(member,new AiThemeDraftPrepareReq(prepareKey,"different",null,null)));
        assertEquals("AI_DRAFT_REQUEST_CONFLICT",conflict.getErrorCode());
        var hidden=assertThrows(AiThemeDraftRequestService.IntentRejectedException.class,()->service.readIntent(member+1,first.requestId()));
        var absent=assertThrows(AiThemeDraftRequestService.IntentRejectedException.class,()->service.readIntent(member+1,"td2_"+"f".repeat(32)));
        assertEquals(hidden.getErrorCode(),absent.getErrorCode());
        assertThrows(AiThemeDraftRequestService.IntentRejectedException.class,()->service.execute(member+1,new AiThemeDraftExecuteReq(first.requestId())));
        for(String id:new String[]{null,prepareKey,"td2_"+"f".repeat(32)})
            assertThrows(AiThemeDraftRequestService.IntentRejectedException.class,()->service.execute(member,new AiThemeDraftExecuteReq(id)));
        assertEquals(0,quotaRows());verifyNoInteractions(fixture.safety,fixture.logs);verify(fixture.model,never()).chat(anyString(),anyString());
    }
    @Test void changedRoleAndConfigRejectBeforeAnyQuotaOrExternalEffects() {
        var first=prepare();var original=row(first);role.set("club");refusal(first,"AI_DRAFT_AUTHORITY_CHANGED");
        assertEquals(original.get("nonce"),row(first).get("nonce"));assertEquals("NOT_SENT",row(first).get("provider_state"));
        role.set("player");prepareKey=UUID.randomUUID().toString();var second=prepare();
        Object config=ReflectionTestUtils.getField(fixture.gateway,"aiModelConfig");ReflectionTestUtils.setField(config,"dailyLimit",21);
        refusal(second,"AI_DRAFT_CONFIG_CHANGED");assertEquals(0,quotaRows());verifyNoInteractions(fixture.safety,fixture.logs);verify(fixture.model,never()).chat(anyString(),anyString());
    }
    @Test void bodyWriteFailureKeepsOriginalPrepareAndCommittedClaimAckLossNeverExecutes() {
        bodyWriteFault.set(true);assertThrows(AiThemeDraftRequestService.StateUnavailableException.class,this::prepare);bodyWriteFault.set(false);
        var lost=service.readPrepared(member,prepareKey);assertEquals(AiThemeDraftIntentState.FAILED,lost.state());assertEquals(lost.requestId(),prepare().requestId());
        prepareKey=UUID.randomUUID().toString();var first=prepare();ambiguousClaimCommit.set(true);
        assertThrows(AiThemeDraftRequestService.StateUnavailableException.class,()->execute(first));
        assertEquals("PROCESSING",row(first).get("state"));refusal(first,"AI_DRAFT_IN_PROGRESS");
        assertEquals(0,quotaRows());verifyNoInteractions(fixture.safety,fixture.logs);verify(fixture.model,never()).chat(anyString(),anyString());
    }
    @Test void fixedAbsoluteTtlExpiredSuccessAndPrecisePurgeKeepMetadata() {
        var first=prepare();long contextTtl=raw.getExpire(bodyKey("context",first.requestId()),TimeUnit.MILLISECONDS);
        assertTrue(contextTtl>Duration.ofHours(23).toMillis()&&contextTtl<=Duration.ofHours(24).toMillis());
        assertEquals(first.requestId(),prepare().requestId());assertTrue(raw.getExpire(bodyKey("context",first.requestId()),TimeUnit.MILLISECONDS)<=contextTtl);
        execute(first);assertTrue(raw.getExpire(bodyKey("result",first.requestId()),TimeUnit.MILLISECONDS)>Duration.ofHours(23).toMillis());
        String sentinel="ai:theme:draft:v2:sentinel:"+member;assertFalse(Boolean.TRUE.equals(raw.hasKey(sentinel)));ownedKeys.add(sentinel);raw.opsForValue().set(sentinel,"keep");
        // Exclusive test fixture ages only result retention, not the immutable original quota plan.
        db.update("UPDATE ai_theme_draft_request_intent SET result_expires_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE request_id=?",first.requestId());
        raw.persist(bodyKey("result",first.requestId())); // adversarial missing-TTL residue, not production write behavior
        var expired=service.readIntent(member,first.requestId());assertEquals(AiThemeDraftIntentState.SUCCEEDED,expired.state());
        assertFalse(expired.resultAvailable());assertEquals("AI_DRAFT_RESULT_EXPIRED",expired.reasonCode());refusal(first,"AI_DRAFT_RESULT_EXPIRED");
        ReflectionTestUtils.setField(service,"intentEnabled",false);assertEquals(1,service.purgeExpiredBodies(1));
        assertFalse(Boolean.TRUE.equals(raw.hasKey(bodyKey("result",first.requestId()))));assertEquals("keep",raw.opsForValue().get(sentinel));
        assertNotNull(row(first).get("result_hash"));assertNotNull(row(first).get("result_purged_at"));
        assertEquals(0,service.purgeExpiredBodies(1));assertEquals(1,quotaRows());verify(fixture.model,times(1)).chat(anyString(),anyString());
    }
    @Test void committedOutcomeLostReplyAndFailedResultCacheKeepOriginalKnownFact() {
        var first=prepare();ambiguousOutcomeCommit.set(true);
        assertThrows(AiThemeDraftRequestService.StateUnavailableException.class,()->execute(first));
        assertEquals("SUCCEEDED",row(first).get("state"));assertTrue(service.readIntent(member,first.requestId()).resultAvailable());
        assertTrue(execute(first).resultAvailable());
        prepareKey=UUID.randomUUID().toString();var second=prepare();bodyWriteFault.set(true);
        refusal(second,"AI_DRAFT_RESULT_UNAVAILABLE");bodyWriteFault.set(false);
        assertEquals("SUCCEEDED",row(second).get("state"));assertNotNull(row(second).get("result_hash"));
        assertEquals(AiThemeDraftIntentState.SUCCEEDED,service.readIntent(member,second.requestId()).state());
        refusal(second,"AI_DRAFT_RESULT_UNAVAILABLE");assertEquals(2,quotaRows());verify(fixture.model,times(2)).chat(anyString(),anyString());
    }
    @Test void repeatableReadCachedNullThenCurrentLockFindsOriginalPrepare() throws Exception {
        TransactionTemplate outer=new TransactionTemplate(transactions);outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try { outer.execute(status->{
            assertNull(intents.selectByPrepareKey(member,prepareKey));
            Future<AiThemeDraftIntentView> inserted=pool.submit(this::prepare);
            AiThemeDraftIntentView first;
            try {first=inserted.get(10,TimeUnit.SECONDS);}catch(Exception failure){throw new AssertionError(failure);}
            assertNull(intents.selectByPrepareKey(member,prepareKey),"ordinary RR read keeps old snapshot");
            var current=intents.selectByPrepareKeyForUpdate(member,prepareKey);assertNotNull(current);assertEquals(first.requestId(),current.getRequestId());
            assertEquals(first.requestId(),service.readPrepared(member,prepareKey).requestId());return null;
        }); } finally {pool.shutdownNow();}
        assertEquals(0,quotaRows());verifyNoInteractions(fixture.safety,fixture.logs);verify(fixture.model,never()).chat(anyString(),anyString());
    }
    @Test void expiredOriginalPlanRefusesWithoutQuotaAndContextPurgeUsesOriginalDeadline() {
        // Backdated immutable test intent, never a modified live plan and never a fake Gateway.
        Instant created=Instant.now().minus(Duration.ofHours(25)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        LocalDate day=LocalDate.ofInstant(created,ZoneId.of("Asia/Shanghai"));String d=day.format(DateTimeFormatter.BASIC_ISO_DATE);
        String nonce=UUID.randomUUID().toString().replace("-","");String counter="ai:theme:draft:player:"+member+":"+d;
        AiThemeDraftPlan plan=new AiThemeDraftPlan(member,"player","player-draft",d,counter,nonce,
                "ai:player:theme:draft:reservation:"+counter+":"+nonce,counter+"|"+nonce,"a".repeat(64),"b".repeat(64),
                day.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
        AiThemeDraftIntentRow row=new AiThemeDraftIntentRow();row.setMemberId(member);row.setPrepareKey(prepareKey);
        row.setRequestId("td2_"+UUID.randomUUID().toString().replace("-",""));row.setRequestHash("c".repeat(64));
        ReflectionTestUtils.invokeMethod(AiThemeDraftRequestService.class,"copyPlan",row,plan);
        row.setPlanHash(ReflectionTestUtils.invokeMethod(AiThemeDraftRequestService.class,"planHash",plan));
        row.setState("PREPARED");row.setCreatedAt(created);row.setContextExpiresAt(created.plus(Duration.ofHours(24)));
        new TransactionTemplate(transactions).execute(status->intents.insertPrepared(row));
        String context=bodyKey("context",row.getRequestId());assertFalse(Boolean.TRUE.equals(raw.hasKey(context)));ownedKeys.add(context);raw.opsForValue().set(context,"stale-test-residue");
        var original=service.readPrepared(member,prepareKey);refusal(original,"AI_DRAFT_PLAN_EXPIRED");
        assertEquals("NOT_SENT",row(original).get("provider_state"));assertEquals(nonce,row(original).get("nonce"));
        assertEquals(1,service.purgeExpiredBodies(1));assertFalse(Boolean.TRUE.equals(raw.hasKey(context)));
        assertNotNull(row(original).get("context_purged_at"));assertEquals(0,quotaRows());
        verifyNoInteractions(fixture.safety,fixture.logs);verify(fixture.model,never()).chat(anyString(),anyString());
    }
    @Test void ordinaryInputFailureAndInvalidDraftRemainFailedAndNeverRegenerate() {
        when(fixture.safety.checkText(anyString(),anyString())).thenThrow(new AiServiceException(AiServiceException.Reason.UNKNOWN,"controlled input unavailable"));
        var input=prepare();assertEquals(AiThemeDraftIntentState.FAILED,execute(input).state());
        assertEquals(AiThemeDraftIntentState.FAILED,execute(input).state());verify(fixture.model,never()).chat(anyString(),anyString());
        reset(fixture.safety);prepareKey=UUID.randomUUID().toString();when(fixture.model.chat(anyString(),anyString())).thenReturn("{}");
        var invalid=prepare();assertEquals(AiThemeDraftIntentState.FAILED,execute(invalid).state());
        String nonce=String.valueOf(row(invalid).get("nonce"));assertEquals(AiThemeDraftIntentState.FAILED,execute(invalid).state());
        assertEquals(nonce,row(invalid).get("nonce"));verify(fixture.model,times(1)).chat(anyString(),anyString());
    }
    @Test void schemaGlobalCheckConflictFailsBeforeDdlAndPostcheckFailureIsNotHidden() throws Exception {
        JdbcTemplate conflict=new JdbcTemplate(source(newSchema("conflict")));
        conflict.execute("CREATE TABLE foreign_check_owner(x INT,CONSTRAINT chk_atdi_v2_identity CHECK(x>0))");
        assertThrows(RuntimeException.class,()->applyMigration(conflict,migration()));
        assertEquals(0,conflict.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='ai_theme_draft_request_intent'",Integer.class));
        JdbcTemplate partial=new JdbcTemplate(source(newSchema("partial")));
        String broken=migration().replace("table_name='ai_theme_draft_request_intent')=31","table_name='ai_theme_draft_request_intent')=32");
        assertNotEquals(broken,migration());assertThrows(RuntimeException.class,()->applyMigration(partial,broken));
        assertEquals(1,partial.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='ai_theme_draft_request_intent'",Integer.class));
        assertThrows(RuntimeException.class,()->applyMigration(partial,migration()));
        assertEquals(0,partial.queryForObject("SELECT COUNT(*) FROM ai_theme_draft_request_intent",Integer.class));
    }
    @Test void nullCheckBypassesAndDuplicateIdentityAreRejectedByRealSchema() {
        var first=prepare();execute(first);
        assertThrows(RuntimeException.class,()->db.update("UPDATE ai_theme_draft_request_intent SET provider_state=NULL WHERE request_id=?",first.requestId()));
        assertThrows(RuntimeException.class,()->db.update("UPDATE ai_theme_draft_request_intent SET claim_token=NULL WHERE request_id=?",first.requestId()));
        assertEquals("SUCCEEDED",row(first).get("state"));assertEquals("RECEIVED",row(first).get("provider_state"));
        assertThrows(RuntimeException.class,()->db.update("INSERT INTO ai_theme_draft_request_intent SELECT * FROM ai_theme_draft_request_intent WHERE request_id=?",first.requestId()));
        verify(fixture.model,times(1)).chat(anyString(),anyString());
    }
    @Test void recoveryFindsLostSuccessfulReplyWithDeadlineAndDescendingOwnerPages() {
        var first=prepare();var completed=execute(first);AiThemeDraftIntentRow template=intents.selectOwned(member,first.requestId());
        List<Long> expected=new ArrayList<>();expected.add(template.getId());
        for(int i=0;i<62;i++) {
            String state=List.of("PREPARED","PROCESSING","SUCCEEDED","FAILED","UNKNOWN").get(i%5);
            expected.add(seedRecovery(template,member,state,i==0||i==2).getId());
        }
        ownedMembers.add(member+1);AiThemeDraftIntentRow foreign=seedRecovery(template,member+1,"PREPARED",false);
        var before=db.queryForList("SELECT * FROM ai_theme_draft_request_intent ORDER BY id");
        String originalBody=raw.opsForValue().get(bodyKey("result",first.requestId()));
        ReflectionTestUtils.setField(service,"intentEnabled",false);clearInvocations(creator,fixture.model,fixture.safety,fixture.logs);
        List<Long> shown=new ArrayList<>();Long cursor=null;boolean expiredPrepared=false,expiredSuccess=false;
        AiThemeDraftRecoveryItem recovered=null;
        // No journal/request identity is passed into the recovery call: only authenticated owner + page cursor.
        for(int page=0;page<4;page++) {
            var list=service.readRecovery(member,cursor);assertTrue(list.size()<=21);
            for(var item:list) {
                assertNotEquals(foreign.getRequestId(),item.requestId());
                if(item.state()==AiThemeDraftIntentState.PREPARED&&item.executeBefore().isBefore(Instant.now()))expiredPrepared=true;
                if(item.state()==AiThemeDraftIntentState.SUCCEEDED&&item.resultExpiresAt().isBefore(Instant.now()))expiredSuccess=true;
                if(item.requestId().equals(first.requestId()))recovered=item;
            }
            var display=list.subList(0,Math.min(20,list.size()));display.forEach(i->shown.add(i.cursor()));
            if(list.size()<=20)break;
            cursor=display.get(display.size()-1).cursor(); // 20th displayed; never the unseen 21st
        }
        expected.sort(Comparator.reverseOrder());assertEquals(expected,shown);assertTrue(expiredPrepared);assertTrue(expiredSuccess);
        assertNotNull(recovered);assertEquals(AiThemeDraftIntentState.SUCCEEDED,recovered.state());
        assertEquals(completed.resultExpiresAt(),recovered.resultExpiresAt());
        assertEquals(List.of(foreign.getRequestId()),service.readRecovery(member+1,null).stream().map(AiThemeDraftRecoveryItem::requestId).toList());
        assertTrue(service.readRecovery(member+2,null).isEmpty());
        assertEquals(before,db.queryForList("SELECT * FROM ai_theme_draft_request_intent ORDER BY id"));
        assertEquals(originalBody,raw.opsForValue().get(bodyKey("result",first.requestId())));
        assertEquals(1,quotaRows());verifyNoInteractions(creator,fixture.model,fixture.safety,fixture.logs);
        // The original GET, not the list, retrieves the original result after cold recovery.
        assertTrue(service.readIntent(member,recovered.requestId()).resultAvailable());verifyNoInteractions(creator,fixture.model);
    }
    @Test void recoveryMapperExplainUsesOwnerIdRangeAndFixedNewestLookahead() {
        var first=prepare();AiThemeDraftIntentRow template=intents.selectOwned(member,first.requestId());
        for(int i=0;i<30;i++)seedRecovery(template,member,List.of("PREPARED","SUCCEEDED","UNKNOWN").get(i%3),false);
        for(Long cursor:Arrays.asList(null,template.getId()+20)) {
            Map<String,Object> params=new HashMap<>();params.put("memberId",member);params.put("beforeId",cursor);
            var sql=mappedConfiguration.getMappedStatement("com.chengyinhub.business.mapper.AiThemeDraftIntentMapper.selectRecovery").getBoundSql(params);
            Object[] args=sql.getParameterMappings().stream().map(v->params.get(v.getProperty())).toArray();
            String plan=db.queryForObject("EXPLAIN FORMAT=JSON "+sql.getSql(),String.class,args);
            assertEquals(1,assertRecoveryRange(JSON.parse(plan)));System.out.println("THEME_RECOVERY_EXPLAIN "+plan);
        }
        assertEquals(21,service.readRecovery(member,null).size());
    }
    @Test void recoveryNonPositiveCursorAndRealDatabaseFailureAreNotEmptyResults() throws Exception {
        for(Long cursor:List.of(0L,-1L)) {
            var invalid=assertThrows(AiThemeDraftRequestService.IntentRejectedException.class,()->service.readRecovery(member,cursor));
            assertEquals(Integer.valueOf(400),invalid.getCode());
        }
        String absent="cy_td2_absent_"+UUID.randomUUID().toString().replace("-","");
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,absent));
        org.apache.ibatis.session.Configuration broken=new org.apache.ibatis.session.Configuration();
        broken.setEnvironment(new Environment("absent-theme-schema",new SpringManagedTransactionFactory(),source(absent)));
        String path="mapper/business/AiThemeDraftIntentMapper.xml";
        try(InputStream xml=Resources.getResourceAsStream(path)){new XMLMapperBuilder(xml,broken,path,broken.getSqlFragments()).parse();}
        var failingMapper=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(broken)).getMapper(AiThemeDraftIntentMapper.class);
        AiThemeDraftRequestService failed=wiredService(failingMapper);
        var unavailable=assertThrows(AiThemeDraftRequestService.StateUnavailableException.class,()->failed.readRecovery(member,null));
        Throwable cause=unavailable;while(cause!=null&&!(cause instanceof SQLException))cause=cause.getCause();
        assertInstanceOf(SQLException.class,cause);assertEquals(1049,((SQLException)cause).getErrorCode());
        assertEquals(0,quotaRows());verifyNoInteractions(fixture.safety,fixture.logs);verify(fixture.model,never()).chat(anyString(),anyString());
    }

    private AiThemeDraftIntentRow seedRecovery(AiThemeDraftIntentRow template,long owner,String state,boolean expired) {
        AiThemeDraftIntentRow row=new AiThemeDraftIntentRow();BeanUtils.copyProperties(template,row);row.setId(null);
        row.setClaimToken(null);row.setClaimedAt(null);row.setCompletedAt(null);row.setProviderState(null);row.setReasonCode(null);
        row.setResultHash(null);row.setResultExpiresAt(null);row.setOutcomeHash(null);
        row.setMemberId(owner);row.setPrepareKey(UUID.randomUUID().toString());row.setRequestId("td2_"+UUID.randomUUID().toString().replace("-",""));
        Instant created=Instant.now().minus(expired?Duration.ofHours(25):Duration.ZERO).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        LocalDate day=LocalDate.ofInstant(created,ZoneId.of("Asia/Shanghai"));String d=day.format(DateTimeFormatter.BASIC_ISO_DATE);
        String nonce=UUID.randomUUID().toString().replace("-","");String counter="ai:theme:draft:player:"+owner+":"+d;
        AiThemeDraftPlan plan=new AiThemeDraftPlan(owner,"player","player-draft",d,counter,nonce,
                "ai:player:theme:draft:reservation:"+counter+":"+nonce,counter+"|"+nonce,template.getCreatorConfigHash(),template.getGatewayConfigHash(),
                day.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
        if(!ownedKeys.contains(counter)) {
            assertFalse(Boolean.TRUE.equals(raw.hasKey(counter)));assertFalse(Boolean.TRUE.equals(raw.hasKey(counter+":generation")));
            ownedKeys.add(counter);ownedKeys.add(counter+":generation");
        }
        assertFalse(Boolean.TRUE.equals(raw.hasKey(plan.receiptKey())));ownedKeys.add(plan.receiptKey());
        ReflectionTestUtils.invokeMethod(AiThemeDraftRequestService.class,"copyPlan",row,plan);
        row.setPlanHash(ReflectionTestUtils.invokeMethod(AiThemeDraftRequestService.class,"planHash",plan));
        row.setCreatedAt(created);row.setContextExpiresAt(created.plus(Duration.ofHours(24)));row.setState("PREPARED");
        new TransactionTemplate(transactions).execute(status->intents.insertPrepared(row));
        String claim=UUID.randomUUID().toString().replace("-","");
        if("PROCESSING".equals(state))db.update("UPDATE ai_theme_draft_request_intent SET state='PROCESSING',claim_token=?,claimed_at=? WHERE id=?",claim,Timestamp.from(created),row.getId());
        else if("SUCCEEDED".equals(state))db.update("UPDATE ai_theme_draft_request_intent SET state='SUCCEEDED',claim_token=?,claimed_at=?,provider_state='RECEIVED',outcome_hash=?,completed_at=?,result_hash=?,result_expires_at=? WHERE id=?",
                claim,Timestamp.from(created),"e".repeat(64),Timestamp.from(created),"d".repeat(64),Timestamp.from(created.plus(Duration.ofHours(24))),row.getId());
        else if(!"PREPARED".equals(state))db.update("UPDATE ai_theme_draft_request_intent SET state=?,claim_token=?,claimed_at=?,provider_state=?,reason_code=?,outcome_hash=?,completed_at=? WHERE id=?",
                state,"UNKNOWN".equals(state)?claim:null,"UNKNOWN".equals(state)?Timestamp.from(created):null,
                "UNKNOWN".equals(state)?"UNKNOWN":"NOT_SENT","AI_DRAFT_RECOVERY","e".repeat(64),Timestamp.from(created),row.getId());
        return row;
    }
    private static int assertRecoveryRange(Object node) {
        int count=0;
        if(node instanceof com.alibaba.fastjson2.JSONObject object) {
            if("ai_theme_draft_request_intent".equals(object.getString("table_name"))) {
                assertEquals("idx_atdi_v2_recovery",object.getString("key"));
                assertTrue(Set.of("range","ref").contains(object.getString("access_type")),object.toJSONString());count++;
            }
            for(Object value:object.values())count+=assertRecoveryRange(value);
        } else if(node instanceof JSONArray array)for(Object value:array)count+=assertRecoveryRange(value);
        return count;
    }

    private AiThemeDraftIntentView prepare(){return service.prepare(member,new AiThemeDraftPrepareReq(prepareKey,"private-idea",null,null));}
    private AiThemeDraftIntentView execute(AiThemeDraftIntentView v){return service.execute(member,new AiThemeDraftExecuteReq(v.requestId()));}
    private void refusal(AiThemeDraftIntentView v,String error){var e=assertThrows(AiThemeDraftRequestService.IntentRejectedException.class,()->execute(v));assertEquals(error,e.getErrorCode());assertEquals(v.requestId(),e.getIntent().requestId());}
    private Map<String,Object> row(AiThemeDraftIntentView v){return db.queryForMap("SELECT * FROM ai_theme_draft_request_intent WHERE member_id=? AND request_id=?",member,v.requestId());}
    private int quotaRows(){return db.queryForObject("SELECT COUNT(*) FROM ai_quota_reservation WHERE member_id=?",Integer.class,member);}
    private Map<String,Object> quotaRow(){return db.queryForMap("SELECT * FROM ai_quota_reservation WHERE member_id=?",member);}
    private String bodyKey(String kind,String id){return bodyKey(kind,member,id);}
    private static String bodyKey(String kind,long owner,String id){return "ai:theme:draft:v2:"+kind+":"+owner+":"+id;}
    private String newSchema(String suffix)throws Exception{
        String owned="cy_td2_"+suffix+"_"+UUID.randomUUID().toString().replace("-","");
        try(Connection c=DriverManager.getConnection(adminUrl,user,password);Statement s=c.createStatement()){s.execute("CREATE DATABASE `"+owned+"` CHARACTER SET utf8mb4");ownedSchemas.add(owned);}return owned;
    }
    private DriverManagerDataSource source(String name){return new DriverManagerDataSource(adminUrl.replace("/?","/"+name+"?"),user,password);}
    private static Path sqlDirectory(){Path p=Paths.get("chengyinhub-admin/sql");return Files.isDirectory(p)?p:Paths.get("sql");}
    private static String migration(){try{return Files.readString(sqlDirectory().resolve(MIGRATION));}catch(Exception e){throw new IllegalStateException(e);}}
    private static List<String> statements(String sql){return Arrays.stream(sql.replaceAll("(?m)--[^\\r\\n]*","").split(";")).map(String::trim).filter(s->!s.isEmpty()).toList();}
    private static void applyMigration(JdbcTemplate target,String sql){for(String s:statements(sql)){if(s.startsWith("SELECT"))assertEquals(1,target.queryForObject(s,Integer.class));else target.execute(s);}}
    private static String required(String name,boolean empty){String value=System.getenv(name);assertNotNull(value,"explicit task configuration: "+name);if(!empty)assertFalse(value.isBlank(),name);return value;}

    @Test void genLatePlanRejectedAfterRealRetainIsFailedWithoutUnsentProofOrReplayEffects() {
        role.set("club");var prepared=prepare();Map<String,Object> original=row(prepared);
        assertEquals("gen",original.get("pool"));assertEquals(0,quotaRows());
        String receipt=String.valueOf(original.get("receipt_key"));
        AiQuotaRecoveryService actual=(AiQuotaRecoveryService)ReflectionTestUtils.getField(fixture.gateway,"quotaRecovery");
        AiQuotaRecoveryService observed=spy(actual);AtomicBoolean injected=new AtomicBoolean();
        ReflectionTestUtils.setField(fixture.gateway,"quotaRecovery",observed);
        ReflectionTestUtils.setField(creator,"quotaRecovery",observed);
        doAnswer(call->{
            // The real original reserve/Lua and INPUT_REVIEW_STARTED already committed.
            assertEquals("RESERVED",quotaRow().get("state"));
            assertEquals("INPUT_REVIEW_STARTED",quotaRow().get("stage"));
            assertOriginalGenIdentity(prepared,original);
            call.callRealMethod(); // real independent SQL RETAINED, never a fake success
            assertEquals("RETAINED",quotaRow().get("state"));
            assertEquals("INPUT_REJECTED",quotaRow().get("decision_reason"));
            if(injected.compareAndSet(false,true))throw new AiThemeDraftPlanRejectedException(
                    AiThemeDraftPlanRejectedException.Code.CHECK_UNAVAILABLE);
            return null;
        }).when(observed).retain(receipt,"INPUT_REJECTED");
        when(fixture.safety.checkText(anyString(),anyString())).thenReturn("内容违规");

        var failed=execute(prepared);assertTrue(injected.get());
        assertEquals(AiThemeDraftIntentState.FAILED,failed.state());assertEquals("AI_DRAFT_FAILED",failed.reasonCode());
        assertNull(row(prepared).get("provider_state"),"a post-consumption failure is not zero-effect NOT_SENT");
        assertEquals("RETAINED",quotaRow().get("state"));assertEquals("RESULT_CLASSIFIED",quotaRow().get("stage"));
        assertOriginalGenIdentity(prepared,original);
        String originalRedisReceipt=raw.opsForValue().get(receipt);assertNotNull(originalRedisReceipt);assertNotEquals("0",originalRedisReceipt);
        int reviews=mockingDetails(fixture.safety).getInvocations().size();
        assertEquals(AiThemeDraftIntentState.FAILED,execute(prepared).state());
        assertEquals(prepared.requestId(),prepare().requestId());
        assertEquals(AiThemeDraftIntentState.FAILED,service.readIntent(member,prepared.requestId()).state());
        assertEquals(reviews,mockingDetails(fixture.safety).getInvocations().size());
        assertEquals(originalRedisReceipt,raw.opsForValue().get(receipt));assertOriginalGenIdentity(prepared,original);
        verify(creator,times(1)).themeDraftPrepared(any(),eq(member),any());
        verify(fixture.model,never()).chat(anyString(),anyString());
        verify(observed,times(1)).prepare(member,"theme_draft","gen",String.valueOf(original.get("counter_key")),receipt);
        verify(observed,times(1)).reserved(receipt,1L);verify(observed,times(1)).retain(receipt,"INPUT_REJECTED");
        verify(observed,never()).requireRefund(anyString(),anyString());
    }

    @Test void genReceivedOutputReviewFailurePersistsReceivedAndRetainsOriginalQuotaOnReplay() {
        role.set("club");var prepared=prepare();Map<String,Object> original=row(prepared);
        assertEquals("gen",original.get("pool"));assertEquals(0,quotaRows());
        String receipt=String.valueOf(original.get("receipt_key"));
        AiQuotaRecoveryService actual=(AiQuotaRecoveryService)ReflectionTestUtils.getField(fixture.gateway,"quotaRecovery");
        AiQuotaRecoveryService observed=spy(actual);AtomicBoolean outputReviewFailed=new AtomicBoolean();
        ReflectionTestUtils.setField(fixture.gateway,"quotaRecovery",observed);
        ReflectionTestUtils.setField(creator,"quotaRecovery",observed);
        when(fixture.safety.checkText(anyString(),anyString())).thenAnswer(call->{
            if(ANSWER.equals(call.getArgument(0))) {
                outputReviewFailed.set(true);throw new IllegalStateException("controlled received-output review outage");
            }
            return null;
        });

        var failed=execute(prepared);assertTrue(outputReviewFailed.get());
        assertEquals(AiThemeDraftIntentState.FAILED,failed.state());assertEquals("AI_DRAFT_FAILED",failed.reasonCode());
        assertFalse(failed.resultAvailable());assertEquals("RECEIVED",row(prepared).get("provider_state"));
        assertEquals("RETAINED",quotaRow().get("state"));assertEquals("OUTPUT_REVIEW_UNKNOWN",quotaRow().get("decision_reason"));
        assertEquals("RESULT_CLASSIFIED",quotaRow().get("stage"));assertOriginalGenIdentity(prepared,original);
        String originalRedisReceipt=raw.opsForValue().get(receipt);assertNotNull(originalRedisReceipt);assertNotEquals("0",originalRedisReceipt);
        int reviews=mockingDetails(fixture.safety).getInvocations().size();
        assertEquals(AiThemeDraftIntentState.FAILED,execute(prepared).state());assertEquals(prepared.requestId(),prepare().requestId());
        assertEquals(AiThemeDraftIntentState.FAILED,service.readPrepared(member,prepareKey).state());
        assertEquals(reviews,mockingDetails(fixture.safety).getInvocations().size());
        assertEquals(originalRedisReceipt,raw.opsForValue().get(receipt));assertOriginalGenIdentity(prepared,original);
        verify(creator,times(1)).themeDraftPrepared(any(),eq(member),any());
        verify(fixture.model,times(1)).chat(anyString(),anyString());
        verify(observed,times(1)).prepare(member,"theme_draft","gen",String.valueOf(original.get("counter_key")),receipt);
        verify(observed,times(1)).reserved(receipt,1L);verify(observed,times(1)).retain(receipt,"OUTPUT_REVIEW_UNKNOWN");
        verify(observed,never()).requireRefund(anyString(),anyString());
    }

    private void assertOriginalGenIdentity(AiThemeDraftIntentView intent,Map<String,Object> original) {
        Map<String,Object> current=row(intent),quota=quotaRow();
        for(String field:List.of("member_id","role","pool","original_day","counter_key","nonce","receipt_key","reservation",
                "request_id","prepare_key","request_hash","product_type","plan_hash","creator_config_hash","gateway_config_hash","execute_before","context_expires_at"))
            assertEquals(original.get(field),current.get(field),"original intent binding: "+field);
        for(String field:List.of("member_id","pool","original_day","counter_key","nonce","receipt_key"))
            assertEquals(original.get(field),quota.get(field),"original quota binding: "+field);
        assertEquals("gen",quota.get("pool"));assertEquals(1,quotaRows());
        assertEquals("1",raw.opsForValue().get(String.valueOf(original.get("counter_key"))));
    }

    @Test void originalProductTypeRemainsAcrossPrepareExecutionRecoveryAndNullableUnknown() {
        role.set("club");int executed=0;
        for (Integer mode : Arrays.asList(null,1,2)) {
            prepareKey=UUID.randomUUID().toString();var request=new AiThemeDraftPrepareReq(prepareKey,"private-idea",mode,null);
            var prepared=service.prepare(member,request);Map<String,Object> original=row(prepared);
            assertEquals(mode,original.get("product_type"));assertEquals(mode,prepared.productType());
            assertEquals(mode,service.readPrepared(member,prepareKey).productType());assertEquals(mode,service.readIntent(member,prepared.requestId()).productType());
            int priorQuota=quotaRows();Integer different=mode==null?Integer.valueOf(1):mode==1?Integer.valueOf(2):null;
            var conflict=assertThrows(AiThemeDraftRequestService.IntentRejectedException.class,
                    ()->service.prepare(member,new AiThemeDraftPrepareReq(prepareKey,"private-idea",different,null)));
            assertEquals("AI_DRAFT_REQUEST_CONFLICT",conflict.getErrorCode());assertEquals(priorQuota,quotaRows());
            assertThrows(RuntimeException.class,()->db.update("UPDATE ai_theme_draft_request_intent SET product_type=3 WHERE request_id=?",prepared.requestId()));
            assertEquals(mode,row(prepared).get("product_type"));
            doAnswer(call->{
                var input=JSON.parseObject((String)call.getArgument(1));assertEquals(mode,input.getInteger("productType"));return ANSWER;
            }).when(fixture.model).chat(anyString(),anyString());
            var completed=execute(prepared);executed++;
            assertEquals(AiThemeDraftIntentState.SUCCEEDED,completed.state());assertEquals(mode,completed.productType());
            assertEquals(mode,service.readIntent(member,prepared.requestId()).productType());assertEquals(mode,service.readPrepared(member,prepareKey).productType());
            var recovery=service.readRecovery(member,null).stream().filter(v->v.requestId().equals(prepared.requestId())).findFirst().orElseThrow();
            assertEquals(mode,recovery.productType());assertEquals(completed.resultExpiresAt(),recovery.resultExpiresAt());
            assertEquals(prepared.requestId(),service.prepare(member,request).requestId());assertEquals(mode,execute(prepared).productType());
            raw.delete(bodyKey("result",prepared.requestId()));var missing=service.readIntent(member,prepared.requestId());
            assertEquals(AiThemeDraftIntentState.SUCCEEDED,missing.state());assertFalse(missing.resultAvailable());assertEquals(mode,missing.productType());
            Map<String,Object> persisted=row(prepared);
            for(String field:List.of("member_id","pool","original_day","counter_key","nonce","receipt_key","request_hash","plan_hash","product_type"))
                assertEquals(original.get(field),persisted.get(field),"original fact: "+field);
            var quota=db.queryForMap("SELECT * FROM ai_quota_reservation WHERE member_id=? AND receipt_key=?",member,original.get("receipt_key"));
            assertEquals(original.get("nonce"),quota.get("nonce"));assertEquals("RETAINED",quota.get("state"));
            assertEquals(executed,quotaRows());verify(fixture.model,times(executed)).chat(anyString(),anyString());
        }
        assertNull(service.readPrepared(member,UUID.randomUUID().toString()).productType());
        int count=quotaRows();
        for(Integer invalid:List.of(0,3)) {
            var rejected=assertThrows(AiThemeDraftRequestService.IntentRejectedException.class,
                    ()->service.prepare(member,new AiThemeDraftPrepareReq(UUID.randomUUID().toString(),"private-idea",invalid,null)));
            assertEquals(Integer.valueOf(400),rejected.getCode());assertEquals("AI_DRAFT_INVALID_REQUEST",rejected.getErrorCode());
        }
        assertEquals(count,quotaRows());verify(fixture.model,times(3)).chat(anyString(),anyString());
        verify(creator,times(3)).themeDraftPrepared(any(),eq(member),any());
    }

    @Test void committedPrepareAckLossKeepsOriginalIdentityWithoutContextOrExternalEffects() throws Exception {
        var request=new AiThemeDraftPrepareReq(prepareKey,"private-prepare-ack-loss",2,List.of("original-node"));
        var lostAck=new IllegalStateException("controlled committed-prepare acknowledgement loss");
        AtomicBoolean failOnce=new AtomicBoolean(true);
        AtomicReference<AiThemeDraftIntentRow> inserted=new AtomicReference<>();
        AiThemeDraftIntentMapper boundary=mock(AiThemeDraftIntentMapper.class,
                org.mockito.AdditionalAnswers.delegatesTo(intents));
        doAnswer(call->{
            AiThemeDraftIntentRow pending=call.getArgument(0);
            int count=intents.insertPrepared(pending); // Original mapped INSERT, generated key and transaction.
            assertEquals(1,count); inserted.set(pending);
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isSynchronizationActive());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    if(failOnce.compareAndSet(true,false)) throw lostAck;
                }
            });
            return count;
        }).when(boundary).insertPrepared(any(AiThemeDraftIntentRow.class));
        service=wiredService(boundary);
        var unavailable=assertThrows(AiThemeDraftRequestService.StateUnavailableException.class,
                ()->service.prepare(member,request));
        assertSame(lostAck,unavailable.getCause()); assertFalse(failOnce.get());
        AiThemeDraftIntentRow original=inserted.get(); assertNotNull(original);
        Map<String,Object> committed=new LinkedHashMap<>();
        // Direct DriverManagerDataSource connection: never reuse Spring's thread-bound connection/snapshot.
        try(Connection connection=source(schema).getConnection();
                PreparedStatement query=connection.prepareStatement(
                        "SELECT * FROM ai_theme_draft_request_intent WHERE member_id=? AND prepare_key=?")) {
            assertTrue(connection.getAutoCommit()); query.setLong(1,member); query.setString(2,prepareKey);
            try(ResultSet found=query.executeQuery()) {
                assertTrue(found.next(),"original INSERT must already be committed on another connection");
                for(int column=1;column<=found.getMetaData().getColumnCount();column++)
                    committed.put(found.getMetaData().getColumnLabel(column),found.getObject(column));
                assertEquals(original.getExecuteBefore(),found.getTimestamp("execute_before").toInstant());
                assertFalse(found.next(),"only one identity may exist for the original owner/key");
            }
        }
        assertEquals(member,((Number)committed.get("member_id")).longValue());
        assertEquals(original.getId().longValue(),((Number)committed.get("id")).longValue());
        assertEquals(prepareKey,committed.get("prepare_key")); assertEquals(original.getRequestId(),committed.get("request_id"));
        assertEquals(2,((Number)committed.get("product_type")).intValue()); assertEquals("PREPARED",committed.get("state"));
        assertEquals("player",committed.get("role")); assertEquals("player-draft",committed.get("pool"));
        for(var binding:Map.ofEntries(
                Map.entry("request_hash",original.getRequestHash()),Map.entry("plan_hash",original.getPlanHash()),
                Map.entry("original_day",original.getOriginalDay()),Map.entry("counter_key",original.getCounterKey()),
                Map.entry("nonce",original.getNonce()),Map.entry("receipt_key",original.getReceiptKey()),
                Map.entry("reservation",original.getReservation()),Map.entry("creator_config_hash",original.getCreatorConfigHash()),
                Map.entry("gateway_config_hash",original.getGatewayConfigHash())).entrySet())
            assertEquals(binding.getValue(),committed.get(binding.getKey()),binding.getKey());
        assertEquals(0,quotaRows()); verifyNoInteractions(fixture.safety,fixture.logs);
        verify(fixture.model,never()).chat(anyString(),anyString());
        var recovered=service.readPrepared(member,prepareKey);
        assertEquals(original.getRequestId(),recovered.requestId()); assertEquals(Integer.valueOf(2),recovered.productType());
        assertEquals(AiThemeDraftIntentState.PREPARED,recovered.state());
        assertFalse(recovered.contextAvailable()); assertFalse(recovered.resultAvailable()); assertNull(recovered.result());
        assertEquals(recovered.requestId(),service.prepare(member,request).requestId());
        var refused=execute(recovered); // Missing original body must not be recreated or sent.
        assertEquals(AiThemeDraftIntentState.FAILED,refused.state());
        assertEquals("AI_DRAFT_CONTEXT_UNAVAILABLE",refused.reasonCode());
        assertFalse(refused.contextAvailable()); assertFalse(refused.resultAvailable()); assertNull(refused.result());
        assertEquals("NOT_SENT",row(recovered).get("provider_state"));
        assertEquals(recovered.requestId(),service.readPrepared(member,prepareKey).requestId());
        assertEquals(AiThemeDraftIntentState.FAILED,service.readPrepared(member,prepareKey).state());
        assertEquals(recovered.requestId(),service.prepare(member,request).requestId());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ai_theme_draft_request_intent WHERE member_id=?",Integer.class,member));
        var finalRow=row(recovered);
        for(String field:List.of("id","member_id","prepare_key","request_id","request_hash","product_type","plan_hash","role","pool",
                "original_day","counter_key","nonce","receipt_key","reservation","creator_config_hash","gateway_config_hash",
                "created_at","execute_before","context_expires_at"))
            assertEquals(String.valueOf(committed.get(field)),String.valueOf(finalRow.get(field)),"original metadata: "+field);
        for(String key:List.of(original.getCounterKey(),original.getCounterKey()+":generation",original.getReceiptKey(),
                bodyKey("context",original.getRequestId()),bodyKey("result",original.getRequestId())))
            assertFalse(Boolean.TRUE.equals(raw.hasKey(key)),"no original quota or body may be created");
        for(String key:ownedKeys) assertFalse(Boolean.TRUE.equals(raw.hasKey(key)),"both task quota pools stay untouched");
        assertEquals(0,quotaRows()); verifyNoInteractions(fixture.safety,fixture.logs);
        verify(fixture.model,never()).chat(anyString(),anyString());
        verify(creator,times(1)).planThemeDraft(eq(member),anyString());
        verify(creator,never()).themeDraftPrepared(any(),anyLong(),any());
        verify(boundary,times(1)).insertPrepared(any(AiThemeDraftIntentRow.class));
    }
}
