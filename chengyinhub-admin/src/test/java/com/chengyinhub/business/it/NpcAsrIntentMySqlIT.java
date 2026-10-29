package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.ai.SpeechToTextService;
import com.chengyinhub.business.service.impl.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Explicit -Dtest opt-in, real MySQL/mappers/short transactions; provider double, no model/quota/HTTP acceptance.
 * Apply the accompanying NpcChatWriteService.admit review hunk before running the admission case. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NpcAsrIntentMySqlIT {
    static final String RID="e2596106-82d9-4a4f-9ff2-058414599f90";
    static final byte[] AUDIO={1,2,3};
    String schema;DriverManagerDataSource source;JdbcTemplate db;
    DataSourceTransactionManager transactions;NpcAsrIntentMapper rows;NpcChatRequestMapper requests;
    NpcChatWriteService writes;SpeechToTextService speech;
    com.chengyinhub.business.service.ai.AiGatewayService quota;
    @BeforeAll void connect() throws Exception {
        assertEquals("github-hosted",System.getenv("RUNNER_ENVIRONMENT"));
        for(String key:List.of("HOST","PORT","USER","PASSWORD","DB_SUFFIX"))
            assertNotNull(System.getenv("CHENGYIN_MYBATIS_IT_"+key),"Explicit isolated connection required: "+key);
        assertTrue(List.of("127.0.0.1","localhost").contains(MybatisItDatabase.host()));
        String suite="d_npc_asr_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        schema=MybatisItDatabase.databaseName(suite);assertTrue(schema.matches("chengyin_mybatis_d_npc_asr_[a-f0-9]{12}[A-Za-z0-9_]*") && schema.length()<=64);
        source=P0AcceptanceSchema.dataSource(suite);db=new JdbcTemplate(source);
        assertEquals(schema,db.queryForObject("SELECT DATABASE()",String.class));
        assertTrue(db.queryForObject("SELECT VERSION()",String.class).startsWith("8."));
        P0AcceptanceSchema.baseTables(db,"ums_member","npc_chat_log");
        P0AcceptanceSchema.migrations(source,"migration_npc_chat_request_20260722.sql",
                "migration_20261012_npc_chat_media_asset.sql","migration_20261024_d_npc_asr_intent.sql","migration_20261024_d_npc_asr_shared_quota.sql");
        SqlSessionTemplate sql=P0AcceptanceSchema.sql(source,"NpcChatRequestMapper","NpcChatLogMapper","NpcAsrIntentMapper");
        rows=sql.getMapper(NpcAsrIntentMapper.class);requests=sql.getMapper(NpcChatRequestMapper.class);
        transactions=new DataSourceTransactionManager(source);writes=new NpcChatWriteService(requests,sql.getMapper(NpcChatLogMapper.class),transactions);
        ReflectionTestUtils.setField(writes,"asrIntents",rows);
        db.update("INSERT INTO ums_member(id) VALUES(7),(8)");
    }
    @BeforeEach void clearOnlyThisOwnedScratch() {
        db.update("DELETE FROM npc_chat_log");db.update("DELETE FROM npc_chat_request");db.update("DELETE FROM npc_asr_intent");
        speech=mock(SpeechToTextService.class);when(speech.transcribe(any(),anyString())).thenReturn("原转写");
        quota=mock(com.chengyinhub.business.service.ai.AiGatewayService.class);
        when(quota.planQuotaReservation(anyLong(),eq("npc_shop_chat"),anyString())).thenAnswer(c ->
                "ai:gen:limit:gen:"+c.getArgument(0)+":20261013|"+c.getArgument(2));
        when(quota.consumePlannedQuota(anyLong(),eq("npc_shop_chat"),anyString())).thenAnswer(c -> c.getArgument(2));
    }
    @AfterAll void cleanOnlyOwnedDatabase() throws Exception {
        if(source==null)return;
        try(Connection connection=source.getConnection();Statement statement=connection.createStatement()) {
            assertEquals(schema,connection.getCatalog());assertTrue(schema.matches("chengyin_mybatis_d_npc_asr_[a-f0-9]{12}[A-Za-z0-9_]*"));
            statement.execute("DROP DATABASE `"+schema+"`");
            try(PreparedStatement check=connection.prepareStatement("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?")){
                check.setString(1,schema);try(ResultSet result=check.executeQuery()){assertTrue(result.next());assertEquals(0,result.getInt(1));}
            }
        }
    }
    NpcAsrIntentService service(){return new NpcAsrIntentService(rows,requests,transactions,speech,quota);}
    NpcAsrIntent recognize(){return service().recognize(7L,RID,21L,41L,AUDIO,"wav");}
    int count(){return db.queryForObject("SELECT COUNT(*) FROM npc_asr_intent",Integer.class);}
    @Test void committedClaimIsVisibleToAnIndependentConnectionAndOnlyOneConcurrentCallSends() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(speech.transcribe(any(),anyString())).thenAnswer(c->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            try(Connection observer=source.getConnection();Statement sql=observer.createStatement();ResultSet result=sql.executeQuery("SELECT COUNT(*) FROM npc_asr_intent WHERE state='IN_FLIGHT'")){
                assertTrue(result.next());assertEquals(1,result.getInt(1));
            }
            entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return "原转写";
        });
        try(var workers=Executors.newFixedThreadPool(2)){
            Future<NpcAsrIntent> first=workers.submit(this::recognize);
            try {assertTrue(entered.await(10,TimeUnit.SECONDS));assertEquals("IN_FLIGHT",workers.submit(this::recognize).get(10,TimeUnit.SECONDS).getState());}
            finally{release.countDown();}
            assertEquals("RECEIVED",first.get(10,TimeUnit.SECONDS).getState());
        }
        assertEquals("原转写",recognize().getTranscript());assertEquals(1,count());verify(speech,times(1)).transcribe(any(),anyString());
    }
    @Test void unknownAndEmptyResultsSurviveColdOwnersWithoutAnotherSend() {
        when(speech.transcribe(any(),anyString())).thenThrow(new IllegalStateException("controlled response loss"));
        assertEquals("UNKNOWN",recognize().getState());assertEquals("ASR_OUTCOME_UNKNOWN",service().readOutcome("shop",21L,RID,7L).getAsrReason());
        assertEquals("UNKNOWN",recognize().getState());verify(speech,times(1)).transcribe(any(),anyString());
        doReturn((String) null).when(speech).transcribe(any(),anyString());
        var empty=service().recognize(8L,RID,21L,41L,AUDIO,"wav");assertEquals("",empty.getTranscript());
        assertEquals("ASR_EMPTY_TEXT",service().readOutcome("shop",21L,RID,8L).getErrorCode());
        service().recognize(8L,RID,21L,41L,AUDIO,"wav");assertEquals(2,count());verify(speech,times(2)).transcribe(any(),anyString());
    }
    @Test void commitSucceededButAckLostStopsBeforeProviderAndRetainsTheOriginalClaim() {
        AtomicBoolean lose=new AtomicBoolean(true);
        DataSourceTransactionManager uncertain=new DataSourceTransactionManager(source){
            @Override protected void doCommit(DefaultTransactionStatus status){super.doCommit(status);if(lose.getAndSet(false))throw new IllegalStateException("controlled commit ACK loss");}
        };
        var original=new NpcAsrIntentService(rows,requests,uncertain,speech,quota);
        assertThrows(IllegalStateException.class,()->original.recognize(7L,RID,21L,41L,AUDIO,"wav"));
        assertEquals(1,count());assertEquals("IN_FLIGHT",recognize().getState());verifyNoInteractions(speech);
    }
    @Test void actualFinishSqlFailurePreservesTheCommittedClaimAndForbidsResend() {
        db.execute("CREATE TRIGGER d_asr_finish_fault BEFORE UPDATE ON npc_asr_intent FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='controlled ASR save failure'");
        try{assertThrows(RuntimeException.class,this::recognize);}finally{db.execute("DROP TRIGGER d_asr_finish_fault");}
        assertEquals("IN_FLIGHT",recognize().getState());assertNull(service().read(7L,RID,21L).getTranscript());
        assertEquals(1,count());verify(speech,times(1)).transcribe(any(),anyString());
    }
    @Test void realUserDeletionWhileProviderRunsCannotResurrectTheBody() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(speech.transcribe(any(),anyString())).thenAnswer(c->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return "must not survive deletion";});
        try(var workers=Executors.newSingleThreadExecutor()){
            Future<NpcAsrIntent> first=workers.submit(this::recognize);
            try{assertTrue(entered.await(10,TimeUnit.SECONDS));assertEquals(0,writes.deleteForUser(7L));}
            finally{release.countDown();}
            var stored=first.get(10,TimeUnit.SECONDS);assertTrue(stored.isBodyErased());assertNull(stored.getTranscript());assertEquals("UNKNOWN",stored.getState());
        }
        assertTrue(recognize().isBodyErased());assertEquals(1,count());verify(speech,times(1)).transcribe(any(),anyString());
    }
    @Test void bodyExpiryAndDifferentInputKeepThePermanentIdentity() {
        recognize();db.update("UPDATE npc_asr_intent SET body_expires_at=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE user_id=7 AND request_id=?",RID);
        assertEquals("ASR_TEXT_UNAVAILABLE",service().readOutcome("shop",21L,RID,7L).getErrorCode());
        assertNull(service().read(7L,RID,21L).getTranscript());
        assertEquals("原转写",db.queryForObject("SELECT transcript FROM npc_asr_intent WHERE user_id=7",String.class),"GET projects expiry without writing");
        assertEquals(1,rows.redactExpired(10),"existing retention writer clears only expired bodies");
        assertNull(db.queryForObject("SELECT transcript FROM npc_asr_intent WHERE user_id=7",String.class));
        assertThrows(IllegalArgumentException.class,()->service().recognize(7L,RID,22L,41L,AUDIO,"wav"));
        assertThrows(IllegalArgumentException.class,()->service().recognize(7L,RID,21L,41L,new byte[]{9},"wav"));
        assertTrue(recognize().isBodyErased());assertFalse(service().beginHandoff(7L,RID,21L,41L));assertEquals(1,count());
        verify(speech,times(1)).transcribe(any(),anyString());
    }
    @Test void originalTextAdmissionIsAtomicAndCannotRestartAfterOriginalReceiptRetention() {
        recognize();assertTrue(service().beginHandoff(7L,RID,21L,41L));assertFalse(service().beginHandoff(7L,RID,21L,41L));
        db.execute("CREATE TRIGGER d_asr_text_admit_fault BEFORE INSERT ON npc_chat_request FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='controlled original text insert failure'");
        try{assertThrows(RuntimeException.class,()->writes.admitShop(textRequest(),"timeout",41L,"原转写").request());}finally{db.execute("DROP TRIGGER d_asr_text_admit_fault");}
        assertEquals(0,db.queryForObject("SELECT text_admitted FROM npc_asr_intent WHERE user_id=7",Integer.class));
        assertNotNull(writes.admitShop(textRequest(),"timeout",41L,"原转写").request());
        assertEquals(1,db.queryForObject("SELECT text_admitted FROM npc_asr_intent WHERE user_id=7",Integer.class));
        assertEquals(1,requests.deleteNpcChatRequestsBefore(new java.util.Date(System.currentTimeMillis()+86400000),1));
        assertThrows(IllegalStateException.class,()->writes.admitShop(textRequest(),"timeout",41L,"原转写").request());
        assertEquals(1,count());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM npc_chat_request",Integer.class));
        assertEquals("ASR_TEXT_HANDOFF_STARTED",service().readOutcome("shop",21L,RID,7L).getAsrReason());
        assertEquals(0,writes.deleteForUser(7L));assertEquals(1,count());verify(speech,times(1)).transcribe(any(),anyString());
    }
    static NpcChatRequest textRequest(){
        NpcChatRequest row=new NpcChatRequest();row.setUserId(7L);row.setRequestId(RID);row.setSessionId(-21L);
        row.setStatus("PROCESSING");row.setRetryable(false);row.setAi(true);row.setSafetyDecision("NOT_RUN");row.setLeaseToken(UUID.randomUUID().toString());row.setActiveSlot(1);return row;
    }

    @Test void notSentRefundProofSurvivesItsWakeFailureAndColdReadWithoutAnotherProviderCall() {
        when(speech.transcribe(any(),anyString())).thenThrow(new com.chengyinhub.business.service.ai.AiServiceException(
                "controlled not sent",new com.chengyinhub.business.service.ai.AiProviderNotSentException()));
        doThrow(new IllegalStateException("controlled refund wake failure")).when(quota).refundNpcAsrNotSent(7L,RID);
        assertEquals("NOT_SENT",recognize().getState());
        String reservation=db.queryForObject("SELECT quota_reservation FROM npc_asr_intent WHERE user_id=7",String.class);
        assertNotNull(reservation);assertEquals(1,db.queryForObject("SELECT quota_refund_pending FROM npc_asr_intent WHERE user_id=7",Integer.class));
        assertEquals("NOT_SENT",service().read(7L,RID,21L).getState());assertEquals("NOT_SENT",recognize().getState());
        assertEquals(reservation,db.queryForObject("SELECT quota_reservation FROM npc_asr_intent WHERE user_id=7",String.class));
        verify(speech,times(1)).transcribe(any(),anyString());verify(quota,times(1)).consumePlannedQuota(7L,"npc_shop_chat",reservation);
    }
    @Test void originalTextWinnerAndFrozenShopMismatchNeverGetAnotherQuotaOrAdmission() {
        writes.admit(textRequest(),"timeout");
        assertThrows(IllegalStateException.class,this::recognize);assertEquals(0,count());verifyNoInteractions(quota,speech);
        db.update("DELETE FROM npc_chat_request");recognize();assertTrue(service().beginHandoff(7L,RID,21L,41L));
        assertThrows(IllegalStateException.class,()->writes.admitShop(textRequest(),"timeout",42L,"原转写"));
        assertThrows(IllegalStateException.class,()->writes.admitShop(textRequest(),"timeout",41L,"替换正文"));
        assertEquals(0,db.queryForObject("SELECT text_admitted FROM npc_asr_intent WHERE user_id=7",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM npc_chat_request",Integer.class));
        assertNotNull(writes.admitShop(textRequest(),"timeout",41L,"原转写").quota());
        verify(quota,times(1)).consumePlannedQuota(anyLong(),anyString(),anyString());
    }
    @Test void unknownNeverCreatesARefundProofAndRemainsQuotaProcessing() {
        when(speech.transcribe(any(),anyString())).thenThrow(new com.chengyinhub.business.service.ai.AiServiceException("controlled timeout"));
        assertEquals("UNKNOWN",recognize().getState());assertEquals(0,db.queryForObject("SELECT quota_refund_pending FROM npc_asr_intent WHERE user_id=7",Integer.class));
        var result=service().readOutcome("shop",21L,RID,7L);assertEquals("PROCESSING",result.getQuotaStatus());
        assertEquals("REQUEST_OUTCOME_UNKNOWN",result.getErrorCode());assertTrue(result.getSafeText().contains("额度状态处理中"));
        verify(quota,never()).refundNpcAsrNotSent(anyLong(),anyString());
    }

}
