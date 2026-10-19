package com.chengyinhub.web.service;

import com.chengyinhub.business.mapper.PlayerTopicTagMapper;
import com.chengyinhub.business.service.impl.PlayerTopicTagServiceImpl;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.InputStream;
import java.util.concurrent.*;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.PreferenceSubmitRequest;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real preference service and RC transaction; runtime/receipt persistence are focused mocks.
 * These are receipt-boundary tests, not database rollback or MySQL-lock proof. */
class ThemeFirstPreferenceReceiptContractTest {
    // One shared declaration per table; each caller still owns its independent H2 database.
    private static void createMemberTable(JdbcTemplate jdbc) {
        jdbc.execute("create table ums_member(id bigint primary key)");
    }
    private static void createAttemptTable(JdbcTemplate jdbc) {
        jdbc.execute("create table play_attempt_log(id bigint auto_increment primary key,member_id bigint,topic_id bigint,node_id bigint,activity_id bigint,attempt_type int,result int,wrong_answer varchar(255),duration_s int,result_json clob,idempotency_key varchar(128),created_at timestamp,unique(idempotency_key))");
    }
    static final ObjectMapper JSON=new ObjectMapper();
    static final String LEGACY="preference:7:10:20:40";
    static final String CONFIG="{\"steps\":[{\"key\":\"q\",\"type\":\"single\",\"title\":\"Choose\",\"options\":[{\"key\":\"A\",\"text\":\"Small\",\"scores\":{\"compact\":2}},{\"key\":\"B\",\"text\":\"Big\",\"scores\":{\"compact\":1}}]}],\"dimensions\":[\"compact\"],\"results\":{\"compact\":{\"title\":\"Compact\",\"body\":\"because {{choices}}\",\"nextStep\":\"Clear\",\"nextStepDays\":7}}}";
    static final class Fixture {
        final PreferencePlayService service=new PreferencePlayService();
        final PlayAttemptLogMapper attempts=mock(PlayAttemptLogMapper.class);
        final ITopicRouteRuntimeService runtime=mock(ITopicRouteRuntimeService.class);
        final IPlayerTopicTagService tags=mock(IPlayerTopicTagService.class);
        final Map<String,PlayAttemptLog> receipts=new LinkedHashMap<>();
        final AtomicReference<String> current=new AtomicReference<>("theme-first:10:20");
        final AtomicInteger completions=new AtomicInteger();
        final AtomicInteger preparations=new AtomicInteger();
        final CmsMemberTemplate template=new CmsMemberTemplate();
        final TransactionTemplate tx;
        Fixture() {
            template.setPreferenceJson(CONFIG);template.setValidationMethod(6);
            ReflectionTestUtils.setField(service,"attemptMapper",attempts);ReflectionTestUtils.setField(service,"tagService",tags);ReflectionTestUtils.setField(service,"routeRuntimeService",runtime);
            when(runtime.playInstances(anyList())).thenAnswer(c->instanceMap(c.getArgument(0),current.get()));
            when(runtime.assertCurrentPlayInstance(anyLong(),anyLong(),anyLong(),anyString())).thenAnswer(c->{if(!current.get().equals(c.getArgument(3)))throw new ServiceException("changed",409);return !"default".equals(current.get());});
            when(runtime.beginOriginalNodeAward(anyLong(),anyLong(),anyLong(),anyLong(),anyString())).thenAnswer(c->new ITopicRouteRuntimeService.NodeAwardPermit(c.getArgument(0),c.getArgument(1),c.getArgument(2),c.getArgument(3),c.getArgument(4),true));
            when(attempts.selectByIdempotencyKey(anyString())).thenAnswer(c->receipts.get(c.getArgument(0)));
            when(attempts.insertPlayAttemptLog(any())).thenAnswer(c->{PlayAttemptLog row=c.getArgument(0);row.setId((long)receipts.size()+1);receipts.put(row.getIdempotencyKey(),row);return 1;});
            when(tags.pendingForNode(anyLong(),anyLong(),anyLong())).thenReturn(Collections.emptyList());
            PooledDataSource ds=new PooledDataSource("org.h2.Driver","jdbc:h2:mem:preference_"+UUID.randomUUID(),"sa","");
            tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tx.setIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
        }
        PreferenceSubmitRequest request(){PreferenceSubmitRequest r=new PreferenceSubmitRequest();r.setChoices(Collections.singletonMap("q","A"));return r;}
        Map<String,Object> submit(){return submit(7L,10L,20L,40L,code->{completions.incrementAndGet();Map<String,Object> p=new LinkedHashMap<>();p.put("xp",0);p.put("completed",false);p.put("playInstanceId",current.get());return p;});}
        PreferencePlayService.CompletionFunction prepared(Long m,Long t,Long a,Long n,Function<String,Map<String,Object>> progress) {
            return new PreferencePlayService.CompletionFunction() {
                public ITopicRouteRuntimeService.NodeAwardPermit prepare() {
                    preparations.incrementAndGet();return runtime.beginOriginalNodeAward(m,t,a,n,current.get());
                }
                public Map<String,Object> apply(String outcome){return progress.apply(outcome);}
            };
        }
        Map<String,Object> submit(Long m,Long t,Long a,Long n,Function<String,Map<String,Object>> progress){return tx.execute(status->service.submit(m,t,a,n,template,request(),prepared(m,t,a,n,progress)));}
        Map<String,Object> submitRaw(Function<String,Map<String,Object>> progress){return tx.execute(status->service.submit(7L,10L,20L,40L,template,request(),progress));}
        PlayAttemptLog only(){assertEquals(1,receipts.size());return receipts.values().iterator().next();}
        PlayAttemptLog legacy(){PlayAttemptLog row=new PlayAttemptLog();row.setMemberId(7L);row.setTopicId(10L);row.setActivityId(20L);row.setNodeId(40L);row.setAttemptType(6);row.setResult(1);row.setIdempotencyKey(LEGACY);row.setResultJson("{\"evaluation\":{\"resultCode\":\"compact\"},\"progress\":{\"completed\":true,\"xp\":77}}");receipts.put(LEGACY,row);return row;}
    }
    static Map<String,String> instanceMap(List<TopicRouteSession> scopes,String instance) {
        assertNotNull(scopes);assertEquals(1,scopes.size(),"Read-only admission must request one exact owner tuple");
        TopicRouteSession scope=scopes.get(0);assertNotNull(scope);assertNotNull(scope.getMemberId());assertNotNull(scope.getTopicId());assertNotNull(scope.getActivityId());
        return Collections.singletonMap(ITopicRouteRuntimeService.sessionKey(scope.getMemberId(),scope.getTopicId(),scope.getActivityId()),instance);
    }
    static int xp(Map<String,Object> result){return ((Number)((Map<?,?>)result.get("progress")).get("xp")).intValue();}
    static String expectedKey(long m,long t,long a,long n,String instance)throws Exception {String canonical=JSON.writeValueAsString(Arrays.asList("preference-receipt-v1",m,t,a,n,instance));byte[] bytes=MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder("pref-v1:");for(int i=0;i<28;i++)s.append(String.format("%02x",bytes[i]&255));return s.toString();}

    @Test void oldDefaultReceiptCannotBypassFirstCompletionOrLeakOldXp() throws Exception {
        Fixture f=new Fixture();f.legacy();Map<String,Object> out=f.submit();assertEquals(1,f.completions.get());assertEquals(0,xp(out));assertEquals(2,f.receipts.size());assertFalse(out.containsKey("_serverPreferenceScope"));
        assertTrue(f.receipts.containsKey(expectedKey(7,10,20,40,"theme-first:10:20")));verify(f.attempts,never()).selectByIdempotencyKey(LEGACY);
    }
    @Test void defaultKeepsItsHistoricalKeyAndLegacyReceipt() {
        Fixture f=new Fixture();f.current.set("default");f.legacy();Map<String,Object> out=f.submit();assertEquals(77,xp(out));assertEquals(0,f.completions.get());assertEquals(Boolean.FALSE,out.get("firstTime"));
    }
    @Test void sameInstanceRetriesAreDeterministicButFirstAndReplayHaveDifferentKeys() throws Exception {
        Fixture f=new Fixture();Map<String,Object> first=f.submit();String firstKey=f.only().getIdempotencyKey();Map<String,Object> retry=f.submit();assertEquals(1,f.completions.get());assertEquals(first.get("progress"),retry.get("progress"));assertEquals(Boolean.FALSE,retry.get("firstTime"));
        f.current.set("team-replay:88");Map<String,Object> replay=f.submit();assertEquals(2,f.completions.get());assertEquals(2,f.receipts.size());assertNotEquals(firstKey,expectedKey(7,10,20,40,"team-replay:88"));assertEquals("team-replay:88",((Map<?,?>)replay.get("progress")).get("playInstanceId"));
        for(String key:f.receipts.keySet())assertTrue(key.length()<=64,"Existing idempotency column must suffice");
    }
    @Test void allScopeCoordinatesParticipateInBoundedDeterministicKey() throws Exception {
        Fixture f=new Fixture();f.submit();String first=f.only().getIdempotencyKey();assertEquals(expectedKey(7,10,20,40,f.current.get()),first);
        for(long[] ids:new long[][]{{8,10,20,40},{7,11,20,40},{7,10,21,40},{7,10,20,41}}){f.submit(ids[0],ids[1],ids[2],ids[3],code->Collections.singletonMap("xp",0));assertTrue(f.receipts.containsKey(expectedKey(ids[0],ids[1],ids[2],ids[3],f.current.get())));}
        assertEquals(5,f.receipts.size());
    }
    @Test void wrongOrMissingReceiptScopeNeverReturnsStoredProgress() throws Exception {
        for(String field:new String[]{"memberId","topicId","activityId","nodeId","schemaVersion","playInstanceId","idempotencyKey","missing"}) {
            Fixture f=new Fixture();f.submit();PlayAttemptLog row=f.only();ObjectNode root=(ObjectNode)JSON.readTree(row.getResultJson());ObjectNode scope=(ObjectNode)root.get("_serverPreferenceScope");assertNotNull(scope);
            if("missing".equals(field))root.remove("_serverPreferenceScope");else if(scope.path(field).isNumber())scope.put(field,999);else scope.put(field,"wrong");row.setResultJson(root.toString());
            assertThrows(RuntimeException.class,()->f.submit(),field);assertEquals(1,f.completions.get(),field);verify(f.attempts,never()).selectByIdempotencyKey(LEGACY);
        }
    }
    @Test void wrongReceiptDatabaseOwnerActivityAndTokenAreRejected() {
        for(String field:new String[]{"member","topic","activity","node","attempt","result","key"}) {
            Fixture f=new Fixture();f.submit();PlayAttemptLog row=f.only();
            switch(field){case "member":row.setMemberId(8L);break;case "topic":row.setTopicId(11L);break;case "activity":row.setActivityId(21L);break;case "node":row.setNodeId(41L);break;case "attempt":row.setAttemptType(1);break;case "result":row.setResult(0);break;case "key":row.setIdempotencyKey("wrong");break;}
            assertThrows(RuntimeException.class,()->f.submit(),field);assertEquals(1,f.completions.get(),field);
        }
    }
    @Test void changedCurrentPointerBeforeReceiptReturnFailsWithoutDefaultFallback() {
        Fixture f=new Fixture();f.submit();
        doThrow(new ServiceException("changed",409)).when(f.runtime).assertCurrentPlayInstance(7L,10L,20L,"theme-first:10:20");
        assertThrows(RuntimeException.class,()->f.submit());assertEquals(1,f.completions.get());assertEquals(1,f.preparations.get());verify(f.attempts,never()).selectByIdempotencyKey(LEGACY);
    }
    @Test void scopedReceiptReadFailureDoesNotReturnOldDefaultXp() {
        Fixture f=new Fixture();f.legacy();when(f.attempts.selectByIdempotencyKey(argThat(k->!LEGACY.equals(k)))).thenThrow(new IllegalStateException("receipt read unavailable"));
        assertThrows(IllegalStateException.class,()->f.submit());assertEquals(0,f.completions.get());verify(f.attempts,never()).selectByIdempotencyKey(LEGACY);
    }
    @Test void progressFailureLeavesNoNewReceiptAndNeverReturnsOldXp() {
        Fixture f=new Fixture();f.legacy();assertThrows(IllegalStateException.class,()->f.submit(7L,10L,20L,40L,code->{throw new IllegalStateException("progress failed");}));assertEquals(1,f.receipts.size());verify(f.attempts,never()).insertPlayAttemptLog(any());
    }
    @Test void zeroRowReceiptInsertMustFailRatherThanClaimDeterministicSuccess() {
        Fixture f=new Fixture();doReturn(0).when(f.attempts).insertPlayAttemptLog(any());assertThrows(IllegalStateException.class,()->f.submit());assertTrue(f.receipts.isEmpty());
    }
    @Test void trailingJsonAndDuplicateScopeKeysAreNotValidPersistedReceipts() throws Exception {
        for(boolean duplicate:new boolean[]{false,true}){Fixture f=new Fixture();f.submit();PlayAttemptLog row=f.only();String json=row.getResultJson();
            row.setResultJson(duplicate?json.replace("\"memberId\":7","\"memberId\":999,\"memberId\":7"):json+" {}");
            assertThrows(IllegalStateException.class,()->f.submit(),duplicate?"duplicate scope key":"trailing JSON");assertEquals(1,f.completions.get());}
    }
    /** Production receipt SQL and real shared member barrier. Runtime admission is modeled per
     * invocation; this does not prove current-route admission, real runtime lock order, or MySQL. */
    static final class SqlFixture {
        final PreferencePlayService service=new PreferencePlayService();
        final ITopicRouteRuntimeService runtime=mock(ITopicRouteRuntimeService.class);
        final IPlayerTopicTagService tags=mock(IPlayerTopicTagService.class);
        final ThreadLocal<String> admittedInstance=new ThreadLocal<>();
        final AtomicInteger callbacks=new AtomicInteger();
        final CmsMemberTemplate template=new CmsMemberTemplate();
        final org.springframework.jdbc.core.JdbcTemplate jdbc;
        final TransactionTemplate tx;
        SqlFixture() throws Exception {
            PooledDataSource ds=new PooledDataSource("org.h2.Driver","jdbc:h2:mem:preference_sql_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa","");
            jdbc=new org.springframework.jdbc.core.JdbcTemplate(ds);
            createMemberTable(jdbc);jdbc.update("insert into ums_member values(7)");
            createAttemptTable(jdbc);
            jdbc.execute("create table synthetic_preference_progress(member_id bigint,activity_id bigint,node_id bigint,play_instance_id varchar(64),xp int,unique(member_id,activity_id,node_id,play_instance_id))");
            jdbc.execute("create table synthetic_preference_award(member_id bigint,activity_id bigint,node_id bigint,play_instance_id varchar(64),xp int,unique(member_id,activity_id,node_id,play_instance_id))");
            org.apache.ibatis.session.Configuration cfg=new org.apache.ibatis.session.Configuration();
            cfg.getTypeAliasRegistry().registerAlias("PlayAttemptLog",PlayAttemptLog.class);
            cfg.setEnvironment(new org.apache.ibatis.mapping.Environment("h2",new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),ds));
            String resource="mapper/business/PlayAttemptLogMapper.xml";
            try(java.io.InputStream in=ThemeFirstPreferenceReceiptContractTest.class.getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(in);new org.apache.ibatis.builder.xml.XMLMapperBuilder(in,cfg,resource,cfg.getSqlFragments()).parse();
            }
            org.mybatis.spring.SqlSessionTemplate sql=new org.mybatis.spring.SqlSessionTemplate(new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(cfg));
            ReflectionTestUtils.setField(service,"attemptMapper",sql.getMapper(PlayAttemptLogMapper.class));ReflectionTestUtils.setField(service,"tagService",tags);ReflectionTestUtils.setField(service,"routeRuntimeService",runtime);
            template.setPreferenceJson(CONFIG);template.setValidationMethod(6);
            when(tags.pendingForNode(anyLong(),anyLong(),anyLong())).thenReturn(Collections.emptyList());
            when(runtime.playInstances(anyList())).thenAnswer(c->{
                List<TopicRouteSession> scopes=c.getArgument(0);assertEquals(1,scopes.size());TopicRouteSession scope=scopes.get(0);
                assertEquals(Long.valueOf(7),scope.getMemberId());assertEquals(Long.valueOf(10),scope.getTopicId());assertEquals(Long.valueOf(20),scope.getActivityId());
                // The production current-session mapper flushes the transaction-local MyBatis cache.
                sql.clearCache();return instanceMap(scopes,admittedInstance.get());
            });
            when(runtime.assertCurrentPlayInstance(eq(7L),eq(10L),eq(20L),anyString())).thenAnswer(c->{assertEquals(admittedInstance.get(),c.getArgument(3));return !"default".equals(c.getArgument(3));});
            when(runtime.beginOriginalNodeAward(eq(7L),eq(10L),eq(20L),eq(40L),anyString())).thenAnswer(c->{
                assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED),org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
                assertEquals(Long.valueOf(7),jdbc.queryForObject("select id from ums_member where id=7 for update",Long.class));
                // Model beginOriginalNodeAward's existing flushCache evidence reads after its member lock.
                sql.clearCache();
                return new ITopicRouteRuntimeService.NodeAwardPermit(7L,10L,20L,40L,c.getArgument(4),true);
            });
            tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tx.setIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
        }
        Map<String,Object> submit(String instance,int xp) {
            admittedInstance.set(instance);
            try{return tx.execute(status->{PreferenceSubmitRequest request=new PreferenceSubmitRequest();request.setChoices(Collections.singletonMap("q","A"));
                return service.submit(7L,10L,20L,40L,template,request,new PreferencePlayService.CompletionFunction() {
                    public ITopicRouteRuntimeService.NodeAwardPermit prepare(){return runtime.beginOriginalNodeAward(7L,10L,20L,40L,instance);}
                    public Map<String,Object> apply(String code) {
                    callbacks.incrementAndGet();jdbc.update("insert into synthetic_preference_progress values(7,20,40,?,?)",instance,xp);jdbc.update("insert into synthetic_preference_award values(7,20,40,?,?)",instance,xp);
                    Map<String,Object> progress=new LinkedHashMap<>();progress.put("xp",xp);progress.put("playInstanceId",instance);progress.put("completed",true);return progress;
                    }
                });
            });}finally{admittedInstance.remove();}
        }
        int count(String table){return jdbc.queryForObject("select count(*) from "+table,Integer.class);}
        void requireCounts(int expected){assertEquals(expected,count("play_attempt_log"));assertEquals(expected,count("synthetic_preference_progress"));assertEquals(expected,count("synthetic_preference_award"));}
    }
    @Test void realReceiptAndProgressAndAwardRollBackAfterTagFailureThenRetrySucceeds() throws Exception {
        SqlFixture f=new SqlFixture();ObjectNode config=(ObjectNode)JSON.readTree(CONFIG);ObjectNode tag=config.putObject("tagOutput");tag.put("code","space_constraints");tag.put("fromDimension",true);tag.put("purpose","Reuse");tag.put("recipientLabel","Next");tag.put("revocable",true);f.template.setPreferenceJson(config.toString());
        doThrow(new IllegalStateException("injected tag failure after receipt insert")).when(f.tags).upsertPending(anyLong(),anyLong(),anyString(),anyString(),anyLong());
        assertThrows(IllegalStateException.class,()->f.submit("theme-first:10:20",12));assertEquals(1,f.callbacks.get());f.requireCounts(0);
        doReturn(null).when(f.tags).upsertPending(anyLong(),anyLong(),anyString(),anyString(),anyLong());
        assertEquals(12,xp(f.submit("theme-first:10:20",12)));assertEquals(2,f.callbacks.get());f.requireCounts(1);
    }
    @Test void realMemberBarrierMakesConcurrentSameInstanceInvokeCompletionOnce() throws Exception {
        SqlFixture f=new SqlFixture();java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
        try {java.util.List<java.util.concurrent.Future<Map<String,Object>>> futures=new ArrayList<>();for(int i=0;i<2;i++)futures.add(pool.submit(()->{start.await();return f.submit("theme-first:10:20",12);}));start.countDown();int firsts=0;
            for(java.util.concurrent.Future<Map<String,Object>> future:futures){Map<String,Object> result=future.get(10,java.util.concurrent.TimeUnit.SECONDS);assertEquals(12,xp(result));if(Boolean.TRUE.equals(result.get("firstTime")))firsts++;}
            assertEquals(1,firsts);assertEquals(1,f.callbacks.get());f.requireCounts(1);
        }finally{pool.shutdownNow();}
    }
    @Test void realConcurrentLegacyAndFirstReceiptsNeverReturnEachOthersXp() throws Exception {
        SqlFixture f=new SqlFixture();assertEquals(77,xp(f.submit("default",77)));f.requireCounts(1);
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
        try {java.util.concurrent.Future<Map<String,Object>> old=pool.submit(()->{start.await();return f.submit("default",999);});java.util.concurrent.Future<Map<String,Object>> fresh=pool.submit(()->{start.await();return f.submit("theme-first:10:20",0);});start.countDown();
            Map<String,Object> legacy=old.get(10,java.util.concurrent.TimeUnit.SECONDS),first=fresh.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(77,xp(legacy));assertEquals(Boolean.FALSE,legacy.get("firstTime"));assertEquals(0,xp(first));assertEquals(Boolean.TRUE,first.get("firstTime"));assertEquals(2,f.callbacks.get());f.requireCounts(2);
            assertEquals(77,f.jdbc.queryForObject("select xp from synthetic_preference_award where play_instance_id='default'",Integer.class));assertEquals(0,f.jdbc.queryForObject("select xp from synthetic_preference_award where play_instance_id='theme-first:10:20'",Integer.class));
        }finally{pool.shutdownNow();}
    }
    @Test void storedDefaultReceiptReturnsEvenWhenLegacyAdvancedPreparationWouldReject() {
        Fixture f=new Fixture();f.current.set("default");f.legacy();AtomicInteger prepared=new AtomicInteger();
        Map<String,Object> result=f.submitRaw(new PreferencePlayService.CompletionFunction() {
            public ITopicRouteRuntimeService.NodeAwardPermit prepare(){prepared.incrementAndGet();throw new ServiceException("legacy advanced no longer available",409);}
            public Map<String,Object> apply(String code){throw new AssertionError("An existing receipt must never apply completion");}
        });
        assertEquals(77,xp(result));assertEquals(Boolean.FALSE,result.get("firstTime"));assertEquals(0,prepared.get());
        verify(f.runtime,never()).beginOriginalNodeAward(anyLong(),anyLong(),anyLong(),anyLong(),anyString());
    }
    @Test void missingOrWrongExactReadOnlyTupleFailsBeforeReceiptOrPreparation() {
        List<Map<String,String>> invalid=new ArrayList<>();invalid.add(null);invalid.add(Collections.emptyMap());
        invalid.add(Collections.singletonMap(ITopicRouteRuntimeService.sessionKey(8L,10L,20L),"default"));
        invalid.add(Collections.singletonMap(ITopicRouteRuntimeService.sessionKey(7L,11L,20L),"default"));
        invalid.add(Collections.singletonMap(ITopicRouteRuntimeService.sessionKey(7L,10L,21L),"default"));
        invalid.add(Collections.singletonMap(ITopicRouteRuntimeService.sessionKey(7L,10L,20L)," "));
        invalid.add(Collections.singletonMap(ITopicRouteRuntimeService.sessionKey(7L,10L,20L),null));
        for(Map<String,String> map:invalid){Fixture f=new Fixture();f.legacy();doReturn(map).when(f.runtime).playInstances(anyList());
            assertThrows(RuntimeException.class,()->f.submit());assertEquals(0,f.preparations.get());assertEquals(0,f.completions.get());verifyNoInteractions(f.attempts);
        }
    }
    @Test void unpreparedFunctionCannotCreateNewReceiptOrAcquireMemberFirst() {
        Fixture f=new Fixture();AtomicBoolean applied=new AtomicBoolean();
        assertThrows(RuntimeException.class,()->f.submitRaw(code->{applied.set(true);return Collections.singletonMap("xp",99);}));
        assertFalse(applied.get());assertTrue(f.receipts.isEmpty());verify(f.runtime,never()).beginOriginalNodeAward(anyLong(),anyLong(),anyLong(),anyLong(),anyString());
        verify(f.attempts,never()).insertPlayAttemptLog(any());
    }
    @Test void receiptAppearingWhilePreparingIsReturnedWithoutApplyingAgain() throws Exception {
        Fixture f=new Fixture();f.submit();PlayAttemptLog receipt=f.only();String key=receipt.getIdempotencyKey();f.receipts.clear();AtomicInteger preparations=new AtomicInteger();AtomicBoolean applied=new AtomicBoolean();
        Map<String,Object> result=f.submitRaw(new PreferencePlayService.CompletionFunction() {
            public ITopicRouteRuntimeService.NodeAwardPermit prepare(){preparations.incrementAndGet();f.receipts.put(key,receipt);return f.runtime.beginOriginalNodeAward(7L,10L,20L,40L,"theme-first:10:20");}
            public Map<String,Object> apply(String code){applied.set(true);throw new AssertionError("A winning receipt appeared while waiting for the member barrier");}
        });
        assertEquals(1,preparations.get());assertFalse(applied.get());assertEquals(Boolean.FALSE,result.get("firstTime"));assertEquals(0,xp(result));assertEquals(1,f.receipts.size());
    }
    @Test void missingOrWrongPreparedPermitCannotWriteOrReuseAnotherScope() {
        for(ITopicRouteRuntimeService.NodeAwardPermit permit:Arrays.asList(null,
                new ITopicRouteRuntimeService.NodeAwardPermit(8L,10L,20L,40L,"theme-first:10:20",true),
                new ITopicRouteRuntimeService.NodeAwardPermit(7L,11L,20L,40L,"theme-first:10:20",true),
                new ITopicRouteRuntimeService.NodeAwardPermit(7L,10L,20L,41L,"theme-first:10:20",true),
                new ITopicRouteRuntimeService.NodeAwardPermit(7L,10L,21L,40L,"theme-first:10:20",true),
                new ITopicRouteRuntimeService.NodeAwardPermit(7L,10L,20L,40L,"team-replay:88",true),
                new ITopicRouteRuntimeService.NodeAwardPermit(7L,10L,20L,40L,"theme-first:10:20",false))) {
            Fixture f=new Fixture();AtomicBoolean applied=new AtomicBoolean();
            assertThrows(RuntimeException.class,()->f.submitRaw(new PreferencePlayService.CompletionFunction(){
                public ITopicRouteRuntimeService.NodeAwardPermit prepare(){return permit;}
                public Map<String,Object> apply(String code){applied.set(true);return Collections.singletonMap("xp",99);}
            }));assertFalse(applied.get());assertTrue(f.receipts.isEmpty());verify(f.attempts,never()).insertPlayAttemptLog(any());
        }
    }
    @Test void newCompletionAlwaysPreparesGameBeforeTheServiceMemberBarrier() {
        for(String instance:new String[]{"theme-first:10:20","default"}) {
            Fixture f=new Fixture();f.current.set(instance);List<String> order=new ArrayList<>();
            doAnswer(call->{order.add("member");return new ITopicRouteRuntimeService.NodeAwardPermit(7L,10L,20L,40L,instance,true);})
                    .when(f.runtime).beginOriginalNodeAward(7L,10L,20L,40L,instance);
            f.submitRaw(new PreferencePlayService.CompletionFunction() {
                public ITopicRouteRuntimeService.NodeAwardPermit prepare(){order.add("team");order.add("game");return f.runtime.beginOriginalNodeAward(7L,10L,20L,40L,instance);}
                public Map<String,Object> apply(String code){order.add("apply");return Collections.singletonMap("xp",0);}
            });
            assertEquals(Arrays.asList("team","game","member","member","apply"),order,instance);
            verify(f.runtime,never()).playInstance(anyLong(),anyLong(),anyLong());
        }
    }
    private static String tiedConfig() throws Exception {
        ObjectNode config=(ObjectNode)JSON.readTree(CONFIG);
        ((com.fasterxml.jackson.databind.node.ArrayNode)config.path("dimensions")).add("open");
        ((ObjectNode)config.path("results")).set("open",config.path("results").path("compact").deepCopy());
        ObjectNode a=(ObjectNode)config.path("steps").get(0).path("options").get(0).path("scores");a.put("compact",1);a.put("open",1);
        ObjectNode b=(ObjectNode)config.path("steps").get(0).path("options").get(1).path("scores");b.put("compact",0);b.put("open",2);
        ObjectNode tie=config.path("steps").get(0).deepCopy();tie.put("key","tradeoff");
        ((ObjectNode)tie.path("options").get(0).path("scores")).put("open",0);
        ((ObjectNode)tie.path("options").get(1).path("scores")).put("open",1);config.set("tiebreak",tie);return config.toString();
    }
    @Test void tiebreakRefreshRejectsChangedInstanceAndReturnsAConcurrentExactReceipt() throws Exception {
        Fixture changed=new Fixture();changed.template.setPreferenceJson(tiedConfig());AtomicInteger reads=new AtomicInteger();
        doAnswer(c->{if(reads.incrementAndGet()==2)changed.current.set("replay:88");return instanceMap(c.getArgument(0),changed.current.get());}).when(changed.runtime).playInstances(anyList());
        assertThrows(IllegalStateException.class,changed::submit);assertEquals(0,changed.preparations.get());assertEquals(0,changed.completions.get());assertTrue(changed.receipts.isEmpty());
        Fixture winner=new Fixture();winner.current.set("default");winner.template.setPreferenceJson(tiedConfig());AtomicInteger winnerReads=new AtomicInteger();
        doAnswer(c->{if(winnerReads.incrementAndGet()==2)winner.legacy();return instanceMap(c.getArgument(0),winner.current.get());}).when(winner.runtime).playInstances(anyList());
        Map<String,Object> result=winner.submit();assertEquals(Boolean.FALSE,result.get("firstTime"));assertEquals(77,xp(result));assertFalse(result.containsKey("needsTiebreak"));
        assertEquals(0,winner.preparations.get());assertEquals(0,winner.completions.get());verify(winner.runtime,never()).beginOriginalNodeAward(anyLong(),anyLong(),anyLong(),anyLong(),anyString());
    }
    @Test void invalidChoicesAreRejectedBeforeCompletionPreparation() {
        Fixture f=new Fixture();PreferenceSubmitRequest invalid=f.request();invalid.setChoices(Collections.singletonMap("q","unrecognized"));
        assertThrows(IllegalArgumentException.class,()->f.tx.execute(status->f.service.submit(7L,10L,20L,40L,f.template,invalid,
                f.prepared(7L,10L,20L,40L,code->{throw new AssertionError("Invalid choices cannot progress");}))));
        assertEquals(0,f.preparations.get());assertEquals(0,f.completions.get());assertTrue(f.receipts.isEmpty());
        verify(f.runtime,never()).beginOriginalNodeAward(anyLong(),anyLong(),anyLong(),anyLong(),anyString());
    }

    @Test void revokeCommittedDuringPreparationCannotBeConsumedAsConfirmed() throws Exception {
        PooledDataSource ds=new PooledDataSource("org.h2.Driver","jdbc:h2:mem:inherit_revoke_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa","");
        JdbcTemplate jdbc=new JdbcTemplate(ds);
        createMemberTable(jdbc);jdbc.update("insert into ums_member values(7)");
        jdbc.execute("create table player_topic_tag(id bigint primary key,member_id bigint,topic_id bigint,tag_code varchar(50),tag_value varchar(50),source_node_id bigint,status int,create_time timestamp,update_time timestamp)");
        jdbc.update("insert into player_topic_tag values(1,7,10,'space_constraints','compact',30,1,now(),now())");
        createAttemptTable(jdbc);
        jdbc.execute("create table synthetic_progress(member_id bigint, node_id bigint)");
        Configuration cfg=new Configuration();cfg.getTypeAliasRegistry().registerAlias("PlayerTopicTag",PlayerTopicTag.class);cfg.getTypeAliasRegistry().registerAlias("PlayAttemptLog",PlayAttemptLog.class);
        cfg.setEnvironment(new Environment("h2",new SpringManagedTransactionFactory(),ds));
        for(String name:Arrays.asList("PlayerTopicTagMapper.xml","PlayAttemptLogMapper.xml")) {
            String path="mapper/business/"+name;try(InputStream in=getClass().getClassLoader().getResourceAsStream(path)){assertNotNull(in);new XMLMapperBuilder(in,cfg,path,cfg.getSqlFragments()).parse();}
        }
        SqlSessionTemplate sql=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg));
        PlayerTopicTagServiceImpl tags=new PlayerTopicTagServiceImpl();ReflectionTestUtils.setField(tags,"mapper",sql.getMapper(PlayerTopicTagMapper.class));
        ITopicRouteRuntimeService runtime=mock(ITopicRouteRuntimeService.class);
        when(runtime.playInstances(anyList())).thenAnswer(call->{sql.clearCache();return Collections.singletonMap(ITopicRouteRuntimeService.sessionKey(7L,10L,20L),"theme-first:10:20");});
        when(runtime.assertCurrentPlayInstance(7L,10L,20L,"theme-first:10:20")).thenReturn(true);
        when(runtime.beginOriginalNodeAward(7L,10L,20L,40L,"theme-first:10:20")).thenAnswer(call->{assertEquals(7L,jdbc.queryForObject("select id from ums_member where id=7 for update",Long.class));sql.clearCache();return new ITopicRouteRuntimeService.NodeAwardPermit(7L,10L,20L,40L,"theme-first:10:20",true);});
        PreferencePlayService service=new PreferencePlayService();ReflectionTestUtils.setField(service,"routeRuntimeService",runtime);ReflectionTestUtils.setField(service,"tagService",tags);ReflectionTestUtils.setField(service,"attemptMapper",sql.getMapper(PlayAttemptLogMapper.class));
        CmsMemberTemplate template=new CmsMemberTemplate();template.setPreferenceJson("{\"steps\":[{\"key\":\"q\",\"type\":\"single\",\"title\":\"Choose\",\"options\":[{\"key\":\"A\",\"text\":\"Small\",\"scores\":{\"compact\":2}},{\"key\":\"B\",\"text\":\"Big\",\"scores\":{\"compact\":1}}]}],\"dimensions\":[\"compact\"],\"results\":{\"compact\":{\"title\":\"Compact\",\"body\":\"because {{choices}}\",\"nextStep\":\"Clear\",\"nextStepDays\":7}},\"tagConsumes\":[\"space_constraints\"]}");
        PreferenceSubmitRequest request=new PreferenceSubmitRequest();request.setReuseTagCode("space_constraints");
        TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tx.setIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
        ExecutorService other=Executors.newSingleThreadExecutor();AtomicInteger applied=new AtomicInteger();
        try {
            Runnable submit=()->tx.execute(status->service.submit(7L,10L,20L,40L,template,request,new PreferencePlayService.CompletionFunction(){
                public ITopicRouteRuntimeService.NodeAwardPermit prepare() {
                    try {other.submit(()->tags.revoke(7L,1L)).get(5,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}
                    assertEquals(2,jdbc.queryForObject("select status from player_topic_tag where id=1",Integer.class));
                    return runtime.beginOriginalNodeAward(7L,10L,20L,40L,"theme-first:10:20");
                }
                public Map<String,Object> apply(String code){applied.incrementAndGet();jdbc.update("insert into synthetic_progress values(7,40)");return Collections.singletonMap("completed",true);}
            }));
            try {submit.run();} catch(IllegalArgumentException expected) {
                assertEquals(0,applied.get());assertEquals(0,jdbc.queryForObject("select count(*) from play_attempt_log",Integer.class));
                System.out.println("INDEPENDENT_INHERITED_TAG_REVOKED_BEFORE_MEMBER=REJECTED");return;
            }
            System.out.println("INDEPENDENT_INHERITED_TAG_REVOKED_BEFORE_MEMBER=ACCEPTED status="+jdbc.queryForObject("select status from player_topic_tag where id=1",Integer.class)+" progress="+jdbc.queryForObject("select count(*) from synthetic_progress",Integer.class)+" receipt="+jdbc.queryForObject("select count(*) from play_attempt_log",Integer.class));
            fail("A tag revoked and committed before preparation/member acquisition must not create completion from cached confirmed data");
        }finally{other.shutdownNow();}
    }
    private static void allowInheritedCompact(Fixture f) throws Exception {
        ObjectNode config=(ObjectNode)JSON.readTree(CONFIG);config.putArray("tagConsumes").add("space_constraints");f.template.setPreferenceJson(config.toString());
    }
    private static PlayerTopicTag confirmedCompact() {
        PlayerTopicTag tag=new PlayerTopicTag();tag.setId(1L);tag.setMemberId(7L);tag.setTopicId(10L);tag.setTagCode("space_constraints");tag.setTagValue("compact");tag.setStatus(1);return tag;
    }
    @Test void inheritedTagIsFirstReadAfterPreparationAndKeepsLateConfirmationLegal() throws Exception {
        for (boolean initiallyConfirmed : new boolean[]{false,true}) {
            Fixture f=new Fixture();allowInheritedCompact(f);AtomicBoolean prepared=new AtomicBoolean();
            AtomicReference<PlayerTopicTag> currentTag=new AtomicReference<>(initiallyConfirmed?confirmedCompact():null);
            when(f.tags.confirmedForConsumer(eq(7L),eq(10L),anySet())).thenAnswer(c->{
                assertTrue(prepared.get(),"Mutable confirmed facts must not be read before preparation/member wait");
                return currentTag.get()==null?Collections.emptyList():Collections.singletonList(currentTag.get());
            });
            PreferenceSubmitRequest request=f.request();request.setReuseTagCode("space_constraints");
            Map<String,Object> result=f.tx.execute(status->f.service.submit(7L,10L,20L,40L,f.template,request,new PreferencePlayService.CompletionFunction(){
                public ITopicRouteRuntimeService.NodeAwardPermit prepare(){prepared.set(true);currentTag.set(confirmedCompact());return f.runtime.beginOriginalNodeAward(7L,10L,20L,40L,f.current.get());}
                public Map<String,Object> apply(String code){assertEquals("compact",code);f.completions.incrementAndGet();return Collections.singletonMap("completed",true);}
            }));
            assertEquals(Boolean.TRUE,result.get("firstTime"));assertEquals(1,f.completions.get());assertEquals(1,f.receipts.size());
            verify(f.tags,times(1)).confirmedForConsumer(eq(7L),eq(10L),anySet());
        }
    }
    @Test void inheritedValueChangedToAnUnusableBranchDuringPreparationCannotUseCachedOldValue() throws Exception {
        Fixture f=new Fixture();allowInheritedCompact(f);PlayerTopicTag tag=confirmedCompact();
        when(f.tags.confirmedForConsumer(eq(7L),eq(10L),anySet())).thenReturn(Collections.singletonList(tag));
        PreferenceSubmitRequest request=f.request();request.setReuseTagCode("space_constraints");
        assertThrows(IllegalArgumentException.class,()->f.tx.execute(status->f.service.submit(7L,10L,20L,40L,f.template,request,new PreferencePlayService.CompletionFunction(){
            public ITopicRouteRuntimeService.NodeAwardPermit prepare(){tag.setTagValue("no_longer_a_declared_result");return f.runtime.beginOriginalNodeAward(7L,10L,20L,40L,f.current.get());}
            public Map<String,Object> apply(String code){f.completions.incrementAndGet();throw new AssertionError("Changed invalid inherited value must not complete");}
        })));
        assertEquals(0,f.completions.get());assertTrue(f.receipts.isEmpty());verify(f.attempts,never()).insertPlayAttemptLog(any());
    }

}
