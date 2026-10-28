package com.chengyinhub.business.service.ai;

import java.io.InputStream;
import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.mapper.NpcVoiceEnrollmentMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual MySQL/current locks/CAS and actual service; provider double only. Explicit root execution required. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(45)
class NpcVoiceEnrollmentMySqlIT
{
    private String adminUrl,user,password,schema;
    private boolean owned;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private VoiceCloneClient provider;
    private NpcVoiceEnrollmentService first,second;
    private static final String SAMPLE="media-asset:73";

    private static String required(String name) {
        String value=System.getenv(name);assertNotNull(value,"explicit test configuration required: "+name);
        if(!name.endsWith("_PASSWORD"))assertFalse(value.isBlank());return value;
    }
    @BeforeAll void connect() throws Exception {
        int port=Integer.parseInt(required("CHENGYIN_AI_MYSQL_IT_PORT"));assertTrue(port>0&&port<=65535);
        user=required("CHENGYIN_AI_MYSQL_IT_USER");password=required("CHENGYIN_AI_MYSQL_IT_PASSWORD");
        adminUrl="jdbc:mysql://127.0.0.1:"+port+"/?serverTimezone=UTC";
        schema="chengyin_voice_intent_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);Statement statement=admin.createStatement()) {
            assertTrue(admin.getMetaData().getDatabaseProductName().contains("MySQL"));assertTrue(admin.getMetaData().getDatabaseMajorVersion()>=8);
            statement.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4");owned=true;
        }
        dataSource=new DriverManagerDataSource(adminUrl.replace("/?","/"+schema+"?"),user,password);jdbc=new JdbcTemplate(dataSource);
        // Minimal existing profile storage columns used by the REAL existing voice UPDATE and original resultMap.
        jdbc.execute("CREATE TABLE npc_profile (id BIGINT PRIMARY KEY,scope_type INT NOT NULL,scope_id BIGINT NOT NULL,"
                +"del_flag CHAR(1) NOT NULL DEFAULT '0',voice_version BIGINT NOT NULL DEFAULT 0,voice_id VARCHAR(96),"
                +"voice_status INT NOT NULL DEFAULT 0,voice_sample VARCHAR(255),audit_status INT NOT NULL DEFAULT 1,"
                +"update_time DATETIME(6)) ENGINE=InnoDB");
        Path migration=Paths.get("chengyinhub-admin/sql/migration_npc_voice_enrollment_intent_20261024.sql");
        if(!Files.exists(migration))migration=Paths.get("sql/migration_npc_voice_enrollment_intent_20261024.sql");
        try(Connection connection=dataSource.getConnection()) {ScriptUtils.executeSqlScript(connection,new FileSystemResource(migration));}
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=? "
                +"AND table_name='npc_voice_enrollment_intent' AND constraint_type='CHECK' AND enforced='YES'",Integer.class,schema));
        Path attemptsMigration=migration.resolveSibling("migration_npc_voice_confirmed_attempt_20261024.sql");
        try(Connection connection=dataSource.getConnection()) {ScriptUtils.executeSqlScript(connection,new FileSystemResource(attemptsMigration));}
        org.apache.ibatis.session.Configuration config=new org.apache.ibatis.session.Configuration();
        config.setLocalCacheScope(LocalCacheScope.SESSION);
        config.setEnvironment(new Environment("voice-intent",new SpringManagedTransactionFactory(),dataSource));
        for(String name:new String[]{"NpcProfileMapper","NpcVoiceEnrollmentMapper"}) {
            String resource="mapper/business/"+name+".xml";
            try(InputStream xml=Resources.getResourceAsStream(resource)){new XMLMapperBuilder(xml,config,resource,config.getSqlFragments()).parse();}
        }
        SqlSessionTemplate sessions=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        provider=mock(VoiceCloneClient.class);
        first=new NpcVoiceEnrollmentService(sessions.getMapper(NpcVoiceEnrollmentMapper.class),sessions.getMapper(NpcProfileMapper.class),
                provider,new DataSourceTransactionManager(dataSource));
        second=new NpcVoiceEnrollmentService(sessions.getMapper(NpcVoiceEnrollmentMapper.class),sessions.getMapper(NpcProfileMapper.class),
                provider,new DataSourceTransactionManager(dataSource));
    }
    @BeforeEach void cleanOwnedTables() {
        jdbc.update("DELETE FROM npc_voice_enrollment_intent");jdbc.update("DELETE FROM npc_profile");
        jdbc.update("INSERT INTO npc_profile(id,scope_type,scope_id) VALUES(555,2,31),(556,4,32)");
        reset(provider);when(provider.usable()).thenReturn(true);when(provider.isReady(anyString())).thenReturn(true);
    }
    @AfterAll void cleanup() throws Exception {
        if(owned)try(Connection admin=DriverManager.getConnection(adminUrl,user,password);Statement statement=admin.createStatement()) {
            statement.execute("DROP DATABASE `"+schema+"`");
            try(PreparedStatement check=admin.prepareStatement("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?")) {
                check.setString(1,schema);try(ResultSet rows=check.executeQuery()){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
            }
        }
    }
    private NpcProfile enroll(NpcVoiceEnrollmentService instance,int scope) {
        return instance.enroll(88L,scope,scope==2?31L:32L,scope==2?555L:556L,SAMPLE,()->"approved-private-provider-input");
    }
    private String state(Long npc) {return jdbc.queryForObject("SELECT state FROM npc_voice_enrollment_intent WHERE npc_id=?",String.class,npc);}

    @Test void twoInstancesCommitOneClaimBeforeExternalCallAndLoserOnlyReadsOriginal() throws Exception {
        CountDownLatch sent=new CountDownLatch(1),release=new CountDownLatch(1);
        when(provider.enroll(anyString())).thenAnswer(i->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            // Independent auto-commit connection proves both identity and profile changes are already visible.
            assertEquals("IN_FLIGHT",state(555L));assertEquals(1,jdbc.queryForObject("SELECT voice_status FROM npc_profile WHERE id=555",Integer.class));
            sent.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return "voice-original";});
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            Future<NpcProfile> winner=executor.submit(()->enroll(first,2));assertTrue(sent.await(10,TimeUnit.SECONDS));
            NpcProfile loser=enroll(second,2);assertEquals("IN_FLIGHT",loser.getVoiceIntentState());Long id=loser.getVoiceIntentId();
            release.countDown();assertEquals("READY",winner.get(10,TimeUnit.SECONDS).getVoiceIntentState());
            assertEquals(id,enroll(second,2).getVoiceIntentId());assertEquals("READY",state(555L));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM npc_voice_enrollment_intent",Integer.class));verify(provider,times(1)).enroll(anyString());
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO npc_voice_enrollment_intent "
                +"(request_id,npc_id,scope_type,scope_id,actor_member_id,sample_sha256,sample_reference,state,profile_version,created_at,updated_at) "
                +"SELECT request_id,npc_id,scope_type,scope_id,actor_member_id,sample_sha256,sample_reference,'PREPARED',profile_version,NOW(),NOW() "
                +"FROM npc_voice_enrollment_intent WHERE npc_id=555"));
        } finally {release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));}
    }

    @Test void dispatchedUnknownAndTrustedNotSentAreDistinctDurableFactsWithNoReplay() {
        doThrow(new AiServiceException("response lost",new java.net.SocketTimeoutException())).when(provider).enroll(anyString());
        assertEquals("UNKNOWN",enroll(first,2).getVoiceIntentState());Long id=enroll(second,2).getVoiceIntentId();
        assertEquals("UNKNOWN",state(555L));assertEquals(SAMPLE,jdbc.queryForObject("SELECT voice_sample FROM npc_profile WHERE id=555",String.class));
        jdbc.update("UPDATE npc_profile SET voice_id=NULL,voice_status=0,voice_version=voice_version+1 WHERE id=555");
        assertEquals(id,enroll(second,2).getVoiceIntentId());
        assertThrows(NpcVoiceEnrollmentService.Rejected.class,()->second.enroll(88L,2,31L,555L,"media-asset:74",()->"unused"));
        doThrow(new AiServiceException("local admission",new AiProviderNotSentException())).when(provider).enroll(anyString());
        assertEquals("NOT_SENT",enroll(first,4).getVoiceIntentState());assertEquals("NOT_SENT",state(556L));
        assertEquals("VOICE_PROVIDER_NOT_SENT",enroll(second,4).getVoiceReasonCode());
        verify(provider,times(2)).enroll(anyString());verify(provider,never()).isReady(anyString());
    }

    @Test void knownIdIsCommittedBeforeQueryAndResetCannotReactivateIt() {
        when(provider.enroll(anyString())).thenReturn("voice-original");
        when(provider.isReady("voice-original")).thenAnswer(i->{
            assertEquals("PENDING",state(556L));assertEquals("voice-original",jdbc.queryForObject(
                    "SELECT voice_id FROM npc_voice_enrollment_intent WHERE npc_id=556",String.class));
            throw new AiServiceException("query response lost");});
        assertEquals("PENDING",enroll(first,4).getVoiceIntentState());
        assertEquals("voice-original",second.status(4,32L,556L).getVoiceId());
        jdbc.update("UPDATE npc_profile SET voice_id=NULL,voice_status=0,voice_version=voice_version+1 WHERE id=556");
        doReturn(true).when(provider).isReady("voice-original");
        NpcProfile result=second.status(4,32L,556L);assertEquals("READY",result.getVoiceIntentState());
        assertEquals(0,result.getVoiceStatus());assertNull(result.getVoiceId());
        assertEquals("voice-original",jdbc.queryForObject("SELECT voice_id FROM npc_voice_enrollment_intent WHERE npc_id=556",String.class));
        assertEquals(0,enroll(first,4).getVoiceStatus());verify(provider,times(1)).enroll(anyString());
    }
    @Test void confirmedIndependentSameSampleKeepsOriginalAndDatabaseRejectsSameRequestDifferentSample() {
        doThrow(new AiServiceException("unknown")).when(provider).enroll(anyString());
        NpcProfile original=enroll(first,2);
        String request="00000000-0000-4000-8000-000000000001";
        doReturn("voice-independent").when(provider).enroll(anyString());
        NpcProfile next=first.enroll(88L,2,31L,555L,"media-asset:73",request,original.getVoiceIntentId(),true,()->"approved");
        assertEquals("READY",next.getVoiceIntentState()); assertNotEquals(original.getVoiceIntentId(),next.getVoiceIntentId());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM npc_voice_enrollment_intent",Integer.class));
        assertEquals("UNKNOWN",jdbc.queryForObject("SELECT state FROM npc_voice_enrollment_intent WHERE id=?",String.class,original.getVoiceIntentId()));
        assertEquals(next.getVoiceIntentId(),second.enroll(88L,2,31L,555L,"media-asset:73",request,original.getVoiceIntentId(),true,()->"never-sign").getVoiceIntentId());
        assertThrows(NpcVoiceEnrollmentService.Rejected.class,()->second.enroll(88L,2,31L,555L,"media-asset:74",request,original.getVoiceIntentId(),true,()->"never-sign"));
        assertThrows(NpcVoiceEnrollmentService.Rejected.class,()->second.enroll(88L,2,31L,555L,"media-asset:73",
                "00000000-0000-4000-8000-000000000002",original.getVoiceIntentId(),true,()->"never-sign"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(
                "UPDATE npc_voice_enrollment_intent SET request_id=? WHERE id=?",request,original.getVoiceIntentId()));
        assertEquals("voice-independent",jdbc.queryForObject("SELECT voice_id FROM npc_profile WHERE id=555",String.class));
        verify(provider,times(2)).enroll(anyString());
    }

}
