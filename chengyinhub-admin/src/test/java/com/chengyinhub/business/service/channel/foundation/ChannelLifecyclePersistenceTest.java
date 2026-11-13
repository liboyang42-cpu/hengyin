package com.chengyinhub.business.service.channel.foundation;

import com.chengyinhub.business.mapper.channel.foundation.ChannelLifecycleMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real mapper/transactions with isolated H2; production MySQL DDL and authority are not simulated as verified. */
class ChannelLifecyclePersistenceTest {
    private PooledDataSource ds;
    private JdbcTemplate jdbc;
    private ChannelLifecycleMapper real;
    private ChannelLifecycleMapper mapper;
    private ChannelLifecyclePersistence.ManagementAuthority authority;
    private ChannelLifecyclePersistence service;
    private final ObjectMapper json=new ObjectMapper();

    @BeforeEach void setup() throws Exception {
        ds=new PooledDataSource("org.h2.Driver","jdbc:h2:mem:channel_lifecycle_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        Configuration cfg=new Configuration();
        cfg.setEnvironment(new Environment("test",new SpringManagedTransactionFactory(),ds));
        String resource="mapper/business/channel/foundation/ChannelLifecycleMapper.xml";
        try(InputStream in=Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in,cfg,resource,cfg.getSqlFragments()).parse();
        }
        real=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg)).getMapper(ChannelLifecycleMapper.class);
        mapper=mock(ChannelLifecycleMapper.class,org.mockito.AdditionalAnswers.delegatesTo(real));
        authority=mock(ChannelLifecyclePersistence.ManagementAuthority.class);
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("create table im_channel(id bigint primary key,guild_kind int,guild_id bigint,status int,del_flag int,"
                +"archive_reason varchar(64),archived_at timestamp,perm_version int,opt_in_default int,update_time timestamp)");
        jdbc.execute("create table im_channel_lifecycle_receipt(operator_id bigint,request_id varchar(64),payload_fingerprint varchar(64),response_json clob,create_time timestamp,primary key(operator_id,request_id))");
        jdbc.execute("create table im_channel_op_log(id bigint auto_increment primary key,channel_id bigint,action varchar(32),from_status int,to_status int,reason varchar(120),operator_id bigint,request_id varchar(64),create_time timestamp)");
        jdbc.update("insert into im_channel values(100,1,42,0,0,null,null,7,1,null)");
        service=new ChannelLifecyclePersistence(mapper,new TransactionTemplate(new DataSourceTransactionManager(ds)),authority,
                Clock.fixed(Instant.parse("2026-11-05T07:00:00Z"),ZoneOffset.UTC),json);
    }
    @AfterEach void close() { if(ds!=null) { jdbc.execute("drop all objects"); ds.forceCloseAll(); } }
    private ChannelLifecyclePersistence.ArchiveCommand command(String request,boolean archive,int version) {
        return new ChannelLifecyclePersistence.ArchiveCommand(9,1,42,100,version,archive,request,"finished event");
    }
    private int status() { return jdbc.queryForObject("select status from im_channel where id=100",Integer.class); }
    private int version() { return jdbc.queryForObject("select perm_version from im_channel where id=100",Integer.class); }
    private int receipts() { return jdbc.queryForObject("select count(*) from im_channel_lifecycle_receipt",Integer.class); }
    private int audits() { return jdbc.queryForObject("select count(*) from im_channel_op_log",Integer.class); }

    @Test void archiveCommitsStateVersionReasonAndAuditWithOriginalReceipt() {
        Map<String,Object> response=service.archive(command("archive",true,7));
        assertEquals(1,status()); assertEquals(8,version()); assertEquals(1,audits()); assertEquals(1,receipts());
        assertEquals(false,response.get("unchanged")); assertEquals("manual",response.get("archive_reason"));
        assertEquals("2026-11-05 07:00:00.000",response.get("archived_at"));
        assertEquals(0,jdbc.queryForObject("select opt_in_default from im_channel where id=100",Integer.class));
        assertEquals("finished event",jdbc.queryForObject("select reason from im_channel_op_log",String.class));
        assertEquals(0,jdbc.queryForObject("select from_status from im_channel_op_log",Integer.class));
        assertEquals(1,jdbc.queryForObject("select to_status from im_channel_op_log",Integer.class));
    }

    @Test void sameOriginalRequestReplaysBeforeCurrentStateAndDoesNotAuditAgain() {
        Map<String,Object> first=service.archive(command("original",true,7));
        service.archive(command("unarchive",false,8));
        Map<String,Object> replay=service.archive(command("original",true,7));
        first.put("replayed",true); assertEquals(json.valueToTree(first),json.valueToTree(replay));
        assertEquals(0,status()); assertEquals(9,version()); assertEquals(2,audits()); assertEquals(2,receipts());
        verify(authority,times(3)).requireCurrentManagement(9,1,42,100);
    }

    @Test void reusedRequestCannotChangeCommandBinding() {
        service.archive(command("same",true,7));
        assertThrows(IllegalArgumentException.class,()->service.archive(command("same",false,8)));
        assertEquals(1,status()); assertEquals(1,audits()); assertEquals(1,receipts());
    }

    @Test void currentAuthorityIsRequiredBeforeReplayAndBeforeAnySql() {
        service.archive(command("original",true,7));
        clearInvocations(mapper);
        doThrow(new SecurityException("denied")).when(authority).requireCurrentManagement(9,1,42,100);
        assertThrows(SecurityException.class,()->service.archive(command("original",true,7)));
        assertThrows(SecurityException.class,()->service.archive(command("new",false,8)));
        verifyNoInteractions(mapper); assertEquals(1,audits()); assertEquals(1,receipts());
    }

    @Test void guildMismatchAndAllInaccessibleStatusesFailWithoutReceipt() {
        assertThrows(IllegalStateException.class,()->service.archive(new ChannelLifecyclePersistence.ArchiveCommand(9,2,42,100,7,true,"foreign","reason")));
        for(int blocked:new int[]{2,3,4}) {
            jdbc.update("update im_channel set status=? where id=100",blocked);
            assertThrows(IllegalStateException.class,()->service.archive(command("blocked"+blocked,true,7)));
        }
        jdbc.update("update im_channel set status=0,del_flag=1 where id=100");
        assertThrows(IllegalStateException.class,()->service.archive(command("deleted",true,7)));
        assertEquals(0,receipts()); assertEquals(0,audits());
    }

    @Test void automaticallyArchivedChannelCannotBeUnarchived() {
        jdbc.update("update im_channel set status=1,archive_reason='topic_closed' where id=100");
        assertThrows(IllegalStateException.class,()->service.archive(command("immutable",false,7)));
        assertEquals(1,status()); assertEquals(7,version()); assertEquals(0,receipts()); assertEquals(0,audits());
    }

    @Test void unchangedAndStaleCommandsPersistTheirOwnOriginalOutcomeWithoutAudit() {
        Map<String,Object> stale=service.archive(command("stale",true,6));
        assertEquals(true,stale.get("unchanged")); assertEquals(0,status()); assertEquals(7,version());
        service.archive(command("winner",true,7));
        Map<String,Object> replay=service.archive(command("stale",true,6));
        assertEquals(0,((Number)replay.get("status")).intValue()); assertEquals(true,replay.get("replayed"));
        Map<String,Object> sameState=service.archive(command("already",true,8));
        assertEquals(true,sameState.get("unchanged")); assertEquals(1,audits()); assertEquals(3,receipts());
    }

    @Test void auditFailureRollsBackStateAndReservation() {
        doAnswer(call->{ real.appendAudit(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3),call.getArgument(4),call.getArgument(5),call.getArgument(6)); throw new IllegalStateException("after audit SQL"); })
                .when(mapper).appendAudit(any(),anyInt(),anyString(),anyString(),anyLong(),anyString(),any());
        assertThrows(IllegalStateException.class,()->service.archive(command("audit-fail",true,7)));
        assertEquals(0,status()); assertEquals(7,version()); assertEquals(0,audits()); assertEquals(0,receipts());
    }

    @Test void receiptFailureRollsBackStateAndAudit() {
        doAnswer(call->{ real.finishReceipt(call.getArgument(0),call.getArgument(1),call.getArgument(2)); throw new IllegalStateException("after receipt SQL"); })
                .when(mapper).finishReceipt(anyLong(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->service.archive(command("receipt-fail",true,7)));
        assertEquals(0,status()); assertEquals(7,version()); assertEquals(0,audits()); assertEquals(0,receipts());
    }

    @Test void zeroCasReturnsUnchangedWithoutInventingAnAudit() {
        doReturn(0).when(mapper).archiveCas(any(),anyString(),any());
        Map<String,Object> response=service.archive(command("zero-cas",true,7));
        assertEquals(true,response.get("unchanged")); assertEquals(0,status()); assertEquals(7,version());
        assertEquals(0,audits()); assertEquals(1,receipts());
    }

    @Test void duplicateConcurrentCommandsHaveOneMutationAndAudit() throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Map<String,Object>> action=()->{start.await(); return service.archive(command("race",true,7));};
            var one=pool.submit(action); var two=pool.submit(action); start.countDown();
            var first=one.get(10,java.util.concurrent.TimeUnit.SECONDS); var second=two.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(first.get("replayed"),second.get("replayed"));
            assertEquals(1,status()); assertEquals(8,version()); assertEquals(1,audits()); assertEquals(1,receipts());
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)); }
    }

    @Test void missingAuthorityAndInvalidReasonCannotCreateAnExecutableCommand() {
        assertThrows(NullPointerException.class,()->new ChannelLifecyclePersistence(mapper,new TransactionTemplate(),null,Clock.systemUTC(),json));
        assertThrows(IllegalArgumentException.class,()->new ChannelLifecyclePersistence.ArchiveCommand(9,1,42,100,7,true,"request"," "));
        assertThrows(IllegalArgumentException.class,()->new ChannelLifecyclePersistence.ArchiveCommand(9,1,42,100,7,true,"bad key","reason"));
        assertEquals(0,receipts()); verifyNoInteractions(mapper);
    }
    @Test void misconfiguredTransactionCannotWriteOutsideAtomicBoundary() {
        TransactionTemplate disabled=new TransactionTemplate(new DataSourceTransactionManager(ds));
        disabled.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        ChannelLifecyclePersistence unsafe=new ChannelLifecyclePersistence(mapper,disabled,authority,Clock.systemUTC(),json);
        assertThrows(IllegalStateException.class,()->unsafe.archive(command("no-transaction",true,7)));
        verifyNoInteractions(mapper); verifyNoInteractions(authority); assertEquals(0,receipts());
    }
    @Test void snapshotResolverRunsInsideActualTransactionAndRechecksBeforeReplay() {
        var active=new java.util.concurrent.atomic.AtomicBoolean(true);
        var loads=new java.util.concurrent.atomic.AtomicInteger();
        var source=new ChannelSnapshotManagementAuthority((actor,kind,guild,id)-> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            loads.incrementAndGet();
            var channel=new ChannelDefinition(id,kind,guild,0,ChannelDefinition.Kind.TEXT,"test",null,
                    1,false,ChannelDefinition.Status.ACTIVE,2,0,true,0,0,0,null,false);
            return new ChannelManagementResolver.Snapshot(ChannelManagementResolver.Encoding.FORMAL_25_V1,
                    actor,kind,guild,true,true,active.get(),false,Instant.parse("2026-11-05T07:00:00Z"),
                    new ChannelManagementResolver.Role(1,1,false,1L | (1L<<12)),java.util.List.of(),java.util.List.of(),
                    java.util.List.of(new ChannelManagementResolver.Layer(channel,java.util.List.of())));
        });
        var resolved=new ChannelLifecyclePersistence(mapper,new TransactionTemplate(new DataSourceTransactionManager(ds)),source,
                Clock.fixed(Instant.parse("2026-11-05T07:00:00Z"),ZoneOffset.UTC),json);
        resolved.archive(command("resolved",true,7));
        active.set(false);
        assertThrows(SecurityException.class,()->resolved.archive(command("resolved",true,7)));
        assertEquals(2,loads.get());
        assertEquals(1,jdbc.queryForObject("select count(*) from im_channel_op_log",Integer.class));
    }
}
