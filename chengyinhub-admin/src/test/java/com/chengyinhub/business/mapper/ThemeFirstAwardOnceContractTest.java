package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.business.service.support.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Production evidence SQL/member lock with real H2 RC transactions. This is not MySQL verification. */
class ThemeFirstAwardOnceContractTest {
    private static String element(String file,String tag,String id) throws Exception {
        try(InputStream in=ThemeFirstAwardOnceContractTest.class.getClassLoader().getResourceAsStream("mapper/business/"+file)) {
            assertNotNull(in);ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] chunk=new byte[4096];int n;
            while((n=in.read(chunk))!=-1)b.write(chunk,0,n);String xml=new String(b.toByteArray(),StandardCharsets.UTF_8);
            java.util.regex.Matcher match=java.util.regex.Pattern.compile("<"+tag+"[^>]* id=\""+id+"\"[^>]*>").matcher(xml);assertTrue(match.find(),id);int start=match.start();
            return xml.substring(start,xml.indexOf("</"+tag+">",start)+tag.length()+3);
        }
    }
    private static void mapper(Configuration cfg,String namespace,String body) {
        String xml="<?xml version=\"1.0\"?><!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\"><mapper namespace=\""+namespace+"\">"+body+"</mapper>";
        new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),cfg,namespace,cfg.getSqlFragments()).parse();
    }
    private static final class Fixture {
        final PooledDataSource ds=new PooledDataSource("org.h2.Driver","jdbc:h2:mem:first_award_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa","");
        final JdbcTemplate jdbc=new JdbcTemplate(ds);final TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        final TopicRouteSessionMapper routes=mock(TopicRouteSessionMapper.class);final TopicRouteRuntimeServiceImpl runtime;
        final Map<String,TopicRouteSession> current=new ConcurrentHashMap<>();
        Fixture() throws Exception {
            jdbc.execute("create table ums_member(id bigint primary key)");jdbc.update("insert into ums_member values(7),(8)");
            jdbc.execute("create table player_node_progress(id bigint auto_increment primary key,member_id bigint,activity_id bigint,topic_id bigint,node_id bigint,play_instance_id varchar(64),status int,del_flag int,checkin_time timestamp,pic_url varchar(100),review_status int,longitude varchar(50),latitude varchar(50),puzzle_score int,completion_mode varchar(40),create_time timestamp,unique(member_id,activity_id,node_id,play_instance_id))");
            jdbc.execute("create table topic_route_session(id bigint primary key,member_id bigint,activity_id bigint,topic_id bigint,play_instance_id varchar(64),config_snapshot_json clob)");
            jdbc.execute("create table theme_team_run(id bigint primary key,team_id bigint,activity_id bigint,topic_id bigint,previous_run_id bigint,source_session_id bigint,request_key varchar(80),request_digest varchar(100),play_instance_id varchar(64),generation int,config_version bigint,config_snapshot_json clob,create_time timestamp)");
            jdbc.execute("create table theme_team_run_member(run_id bigint,member_id bigint,previous_session_id bigint,session_id bigint)");
            jdbc.execute("create table reward_drop_log(member_id bigint,activity_id bigint,topic_id bigint,node_id bigint,scene_key varchar(40))");
            jdbc.execute("create table business_audit_event(domain_type varchar(40),domain_id bigint,actor_type varchar(40),actor_member_id bigint,action varchar(40),target_type varchar(40),relation_id bigint)");
            jdbc.execute("create table synthetic_award_receipt(id bigint auto_increment primary key,member_id bigint,activity_id bigint,node_id bigint)");
            Configuration cfg=new Configuration();cfg.getTypeAliasRegistry().registerAlias("PlayerNodeProgress",PlayerNodeProgress.class);cfg.setEnvironment(new Environment("h2",new SpringManagedTransactionFactory(),ds));
            mapper(cfg,PlayerNodeProgressMapper.class.getName(),element("PlayerNodeProgressMapper.xml","select","selectOriginalNodeAwardEvidence")+element("PlayerNodeProgressMapper.xml","resultMap","FirstAwardSession")+element("PlayerNodeProgressMapper.xml","select","selectOriginalInstanceAwardSessions")+element("PlayerNodeProgressMapper.xml","sql","selectPlayerNodeProgressVo")+element("PlayerNodeProgressMapper.xml","resultMap","PlayerNodeProgressResult")+element("PlayerNodeProgressMapper.xml","select","selectPlayerNodeProgressListForInstance"));
            mapper(cfg,ThemeTeamRunMapper.class.getName(),element("ThemeTeamRunMapper.xml","resultMap","Run")+element("ThemeTeamRunMapper.xml","resultMap","Member")+element("ThemeTeamRunMapper.xml","sql","columns")+element("ThemeTeamRunMapper.xml","select","selectBySessionId")+element("ThemeTeamRunMapper.xml","select","selectMembers"));
            mapper(cfg,UmsMemberMapper.class.getName(),element("UmsMemberMapper.xml","select","lockMemberById"));
            SqlSessionTemplate sql=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg));
            runtime=new TopicRouteRuntimeServiceImpl(routes,mock(CmsTopicMapper.class),mock(CmsTopicNodeMapper.class),mock(CmsMemberTemplateMapper.class),sql.getMapper(PlayerNodeProgressMapper.class),mock(CmsActivityMapper.class),mock(CmsRegistrationMapper.class),null,null,()->1L,()->1,Clock.systemUTC(),RouteReachabilityProvider.UNKNOWN);
            ThemeRuntimeFeaturePolicy policy=new ThemeRuntimeFeaturePolicy();ReflectionTestUtils.setField(policy,"enabled",true);ReflectionTestUtils.setField(runtime,"themeRuntimePolicy",policy);ReflectionTestUtils.setField(runtime,"originalAwardMemberMapper",sql.getMapper(UmsMemberMapper.class));ReflectionTestUtils.setField(runtime,"themeTeamRunMapper",sql.getMapper(ThemeTeamRunMapper.class));
            when(routes.selectCurrentByOwnerForUpdate(anyLong(),anyLong(),anyLong())).thenAnswer(c->current.get(key(c.getArgument(2),c.getArgument(1),c.getArgument(0))));
            when(routes.selectByOwner(anyLong(),anyLong(),anyLong(),anyString())).thenAnswer(c->{TopicRouteSession r=current.get(key(c.getArgument(2),c.getArgument(1),c.getArgument(0)));return r!=null&&r.getPlayInstanceId().equals(c.getArgument(3))?r:null;});
            when(routes.selectCurrentCandidatesByOwners(anyList())).thenAnswer(c->{List<TopicRouteSession> out=new ArrayList<>();for(TopicRouteSession s:(List<TopicRouteSession>)c.getArgument(0)){TopicRouteSession r=current.get(key(s.getMemberId(),s.getTopicId(),s.getActivityId()));if(r!=null)out.add(r);}return out;});
            tx.setIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);first(7,10,20);first(7,11,20);first(7,10,21);
        }
        String key(Long member,Long topic,Long activity){return member+":"+topic+":"+activity;}
        TopicRouteSession first(long member,long topic,long activity) {
            TopicRouteSession s=new TopicRouteSession();s.setMemberId(member);s.setTopicId(topic);s.setActivityId(activity);s.setConfigSnapshotJson("{\"themeConfig\":{}}");ThemeFirstIdentity.stampNew(s);s.setId(member*10000+topic*100+activity);current.put(key(member,topic,activity),s);
            jdbc.update("insert into topic_route_session values(?,?,?,?,?,?)",s.getId(),member,activity,topic,s.getPlayInstanceId(),s.getConfigSnapshotJson());return s;
        }
        void progress(long member,long activity,Long topic,long node,String instance,int status,int deleted){jdbc.update("insert into player_node_progress(member_id,activity_id,topic_id,node_id,play_instance_id,status,del_flag) values(?,?,?,?,?,?,?)",member,activity,topic,node,instance,status,deleted);}
        boolean eligible(long activity,long topic){return tx.execute(s->runtime.beginOriginalNodeAward(7L,topic,activity,40L,"theme-first:"+topic+":"+activity).isEligible());}
        boolean consume(long activity,long topic,boolean rollback){return tx.execute(status->{boolean eligible=runtime.beginOriginalNodeAward(7L,topic,activity,40L,"theme-first:"+topic+":"+activity).isEligible();if(eligible){progress(7,activity,topic,40,"theme-first:"+topic+":"+activity,1,0);jdbc.update("insert into synthetic_award_receipt(member_id,activity_id,node_id) values(7,?,40)",activity);}if(rollback)throw new IllegalStateException("injected post-award failure");return eligible;});}
    }
    @Test void existingLegacyDefaultOpeningKeepsItsOriginalNodeOpportunity() throws Exception {
        Fixture f=new Fixture();TopicRouteSession legacy=new TopicRouteSession();legacy.setId(900L);legacy.setMemberId(7L);legacy.setTopicId(10L);legacy.setActivityId(20L);legacy.setPlayInstanceId("default");legacy.setConfigSnapshotJson("{\"openingChapter\":{\"chapterId\":90,\"blocks\":[]}}");
        f.current.put(f.key(7L,10L,20L),legacy);when(f.routes.selectByOwnerForUpdate(20L,10L,7L,"default")).thenReturn(legacy);
        assertTrue(f.tx.execute(status->f.runtime.beginOriginalNodeAward(7L,10L,20L,40L,"default").isEligible()).booleanValue());
    }
    @Test void exactOldOpportunitySuppressesFirstEvenMissingTopicOrSoftDeleted() throws Exception {
        Fixture f=new Fixture();f.progress(7,20,null,40,"default",1,1);assertFalse(f.eligible(20,10));assertTrue(f.eligible(21,10));
    }
    @Test void otherActivityMemberNodeOrReplayDoesNotSuppressOriginalOpportunity() throws Exception {
        Fixture f=new Fixture();f.progress(7,21,10L,40,"default",1,0);f.progress(8,20,10L,40,"default",1,0);f.progress(7,20,10L,41,"default",1,0);f.progress(7,20,10L,40,"replay:88",1,0);assertTrue(f.eligible(20,10));
    }
    @Test void pendingOldRowAloneDoesNotConsumeAward() throws Exception {Fixture f=new Fixture();f.progress(7,20,10L,40,"default",0,0);assertTrue(f.eligible(20,10));}
    @Test void exactDropWithOnlyPendingRowFailsClosedButOtherSceneAndActivityDoNot() throws Exception {
        Fixture f=new Fixture();f.progress(7,20,10L,40,"default",0,0);f.jdbc.update("insert into reward_drop_log values(7,21,10,40,'topic_node'),(7,20,11,40,'topic_node'),(7,20,10,40,'other')");assertTrue(f.eligible(20,10));
        f.jdbc.update("insert into reward_drop_log values(7,20,10,40,'topic_node')");assertThrows(RuntimeException.class,()->f.eligible(20,10));assertEquals(0,f.jdbc.queryForObject("select count(*) from synthetic_award_receipt",Integer.class));
    }
    @Test void templateCouponUsesOriginalActivityKeyNotGlobalNodeVeto() throws Exception {
        Fixture f=new Fixture();f.jdbc.update("insert into business_audit_event values('TOPIC_NODE',40,'MEMBER',7,'NODE_COUPON_GRANT','SMS_COUPON',21)");assertTrue(f.eligible(20,10));
        f.jdbc.update("insert into business_audit_event values('TOPIC_NODE',40,'MEMBER',7,'NODE_COUPON_GRANT','SMS_COUPON',20)");assertThrows(RuntimeException.class,()->f.eligible(20,10));
    }
    @Test void verifiedFirstConsumesSameOldActivityKeyAcrossTopicOnlyAsNegativeEvidence() throws Exception {
        Fixture f=new Fixture();f.progress(7,20,11L,40,"theme-first:11:20",1,1);assertFalse(f.eligible(20,10));assertTrue(f.eligible(21,10));
    }
    @Test void prefixOrOrphanCannotForgePersistedFirstOrigin() throws Exception {
        Fixture f=new Fixture();f.progress(7,20,12L,40,"theme-first:12:20",1,0);assertThrows(RuntimeException.class,()->f.eligible(20,10));
        f.jdbc.update("delete from player_node_progress");f.progress(7,20,11L,40,"theme-first:11:20",1,0);f.jdbc.update("update topic_route_session set config_snapshot_json='{\"themeConfig\":{}}' where topic_id=11");assertThrows(RuntimeException.class,()->f.eligible(20,10));
    }
    @Test void actualOuterIsolationIsRequiredAndReadOnlyOrMissingTransactionRejects() throws Exception {
        Fixture f=new Fixture();assertThrows(IllegalStateException.class,()->f.runtime.beginOriginalNodeAward(7L,10L,20L,40L,"theme-first:10:20"));
        f.tx.setIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);assertThrows(IllegalStateException.class,()->f.eligible(20,10));
        f.tx.setIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);f.tx.setReadOnly(true);assertThrows(IllegalStateException.class,()->f.eligible(20,10));
    }
    @Test void transactionRollbackLeavesNeitherProgressNorReceiptAndRetryCanConsume() throws Exception {
        Fixture f=new Fixture();assertThrows(IllegalStateException.class,()->f.consume(20,10,true));assertEquals(0,f.jdbc.queryForObject("select count(*) from player_node_progress",Integer.class));assertEquals(0,f.jdbc.queryForObject("select count(*) from synthetic_award_receipt",Integer.class));assertTrue(f.consume(20,10,false));assertFalse(f.eligible(20,10));
    }
    @Test void memberBarrierSerializesTwoFirstNamespacesWithoutChangingCrossActivityEligibility() throws Exception {
        Fixture f=new Fixture();ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);List<Future<Boolean>> results=new ArrayList<>();
        try {for(long topic:new long[]{10,11})results.add(pool.submit(()->{start.await();return f.consume(20,topic,false);}));start.countDown();int winners=0;for(Future<Boolean> r:results)if(r.get(10,TimeUnit.SECONDS))winners++;assertEquals(1,winners);assertEquals(1,f.jdbc.queryForObject("select count(*) from synthetic_award_receipt",Integer.class));assertTrue(f.consume(21,10,false));assertEquals(2,f.jdbc.queryForObject("select count(*) from synthetic_award_receipt",Integer.class));}finally{pool.shutdownNow();}
    }

    @Test void legitimateOriginalTeamCompletionMustConsumeSameActivityNodeBeforeDifferentTopicFirst() throws Exception {
        Fixture f=new Fixture();
        // Production replayThemeTeam admits a new teammate with no previous personal route.
        // That teammate receives a team-replay instance with previousSessionId null and countRuns=1.
        f.jdbc.update("delete from topic_route_session where topic_id=11");
        TopicRouteSession team=new TopicRouteSession();team.setId(811L);team.setMemberId(7L);team.setTopicId(11L);team.setActivityId(20L);team.setPlayInstanceId("team-replay:88");team.setConfigSnapshotJson("{\"themeConfig\":{}}");
        f.current.put(f.key(7L,11L,20L),team);
        f.jdbc.update("insert into topic_route_session values(811,7,20,11,'team-replay:88','{\"themeConfig\":{}}')");
        ThemeTeamRun run=new ThemeTeamRun();run.setId(88L);run.setTeamId(33L);run.setActivityId(20L);run.setTopicId(11L);run.setPlayInstanceId("team-replay:88");
        f.jdbc.update("insert into theme_team_run(id,team_id,activity_id,topic_id,play_instance_id,generation) values(88,33,20,11,'team-replay:88',1)");
        f.jdbc.update("insert into theme_team_run_member values(88,7,null,811)");
        when(f.routes.countRuns(20L,11L,7L)).thenReturn(1);
        when(f.routes.selectCurrentByOwner(anyLong(),anyLong(),anyLong())).thenAnswer(c->f.current.get(f.key(c.getArgument(2),c.getArgument(1),c.getArgument(0))));
        ThemeTeamRunCoordinator coordinator=mock(ThemeTeamRunCoordinator.class);when(coordinator.guardCurrent(7L,20L,11L)).thenReturn(run);ReflectionTestUtils.setField(f.runtime,"themeTeamCoordinator",coordinator);
        Boolean first=f.tx.execute(status->{boolean allowed=f.runtime.beginOriginalNodeAward(7L,11L,20L,40L,"team-replay:88").isEligible();
            if(allowed){f.progress(7,20,11L,40,"team-replay:88",1,0);f.jdbc.update("insert into synthetic_award_receipt(member_id,activity_id,node_id) values(7,20,40)");}return allowed;});
        assertTrue(first,"The existing production policy admits an original teammate opportunity");
        // Only topic differs. Original reward identity intentionally remains member/activity/node.
        when(f.routes.countRuns(20L,11L,7L)).thenReturn(999);clearInvocations(f.routes);
        assertFalse(f.eligible(20,10),"Original team receipt must suppress a second award under FIRST after historical topic relocation");
        verify(f.routes,never()).countRuns(20L,11L,7L);
    }

    private void historicalTeam(Fixture f,Long previous) {
        f.jdbc.update("delete from topic_route_session where topic_id=11");
        f.jdbc.update("insert into topic_route_session values(811,7,20,11,'team-replay:88','{\"themeConfig\":{}}')");
        f.jdbc.update("insert into theme_team_run(id,team_id,activity_id,topic_id,play_instance_id,generation) values(88,33,20,11,'team-replay:88',1)");
        f.jdbc.update("insert into theme_team_run_member values(88,7,?,811)",previous);
        f.progress(7,20,11L,40,"team-replay:88",1,1);
    }
    @Test void genuineReplayCohortDoesNotConsumeOriginalOpportunity() throws Exception {
        Fixture f=new Fixture();historicalTeam(f,700L);assertTrue(f.eligible(20,10));
    }
    @Test void originalTeamHistorySurvivesCurrentRunCountButNotDifferentActivity() throws Exception {
        Fixture f=new Fixture();historicalTeam(f,null);when(f.routes.countRuns(anyLong(),anyLong(),anyLong())).thenReturn(999);
        assertFalse(f.eligible(20,10));assertTrue(f.eligible(21,10));verify(f.routes,never()).countRuns(anyLong(),anyLong(),anyLong());
    }
    @Test void orphanOrMisboundTeamHistoryFailsClosed() throws Exception {
        for(String corruption:new String[]{"orphan","topic","activity","token","team","member","session","negativePrevious"}) {
            Fixture f=new Fixture();historicalTeam(f,null);
            switch(corruption) {
                case "orphan":f.jdbc.update("delete from topic_route_session where id=811");break;
                case "topic":f.jdbc.update("update theme_team_run set topic_id=12 where id=88");break;
                case "activity":f.jdbc.update("update theme_team_run set activity_id=21 where id=88");break;
                case "token":f.jdbc.update("update theme_team_run set play_instance_id='team-replay:99' where id=88");break;
                case "team":f.jdbc.update("update theme_team_run set team_id=0 where id=88");break;
                case "member":f.jdbc.update("update theme_team_run_member set member_id=8 where run_id=88");break;
                case "session":f.jdbc.update("update theme_team_run_member set session_id=812 where run_id=88");break;
                case "negativePrevious":f.jdbc.update("update theme_team_run_member set previous_session_id=-1 where run_id=88");break;
            }
            assertThrows(RuntimeException.class,()->f.eligible(20,10),corruption);
        }
    }

    @Test void rolledBackOriginalTeamCompletionCannotBlockANewOpportunity() throws Exception {
        Fixture f=new Fixture();assertThrows(IllegalStateException.class,()->f.tx.execute(status->{historicalTeam(f,null);throw new IllegalStateException("rollback original completion");}));
        assertTrue(f.eligible(20,10));assertEquals(0,f.jdbc.queryForObject("select count(*) from player_node_progress",Integer.class));
        assertEquals(0,f.jdbc.queryForObject("select count(*) from theme_team_run",Integer.class));assertEquals(0,f.jdbc.queryForObject("select count(*) from theme_team_run_member",Integer.class));
    }
}
