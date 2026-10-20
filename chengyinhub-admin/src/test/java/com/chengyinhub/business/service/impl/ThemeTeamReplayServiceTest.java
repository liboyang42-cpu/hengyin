package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.support.*;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual runtime/coordinator with mocked database boundaries. Transaction proof is a separate DB test. */
class ThemeTeamReplayServiceTest {
    private TopicRouteSessionMapper routes;
    private ThemeTeamRunMapper runs;
    private PlayTeamMapper teams;
    private CmsTopicMapper topics;
    private CmsActivityMapper activities;
    private CmsRegistrationMapper registrations;
    private CmsActivity activity;
    private PlayerNodeProgressMapper progress;
    private TopicRouteRuntimeServiceImpl runtime;
    private PlayTeam team;
    private final Map<Long,TopicRouteSession> sessions=new LinkedHashMap<>();
    private final Map<Long,ThemeTeamRun> groups=new LinkedHashMap<>();
    private final List<ThemeTeamRun.Member> links=new ArrayList<>();
    private final AtomicLong ids=new AtomicLong(1000);
    private List<PlayTeamMember> members;
    private final ObjectMapper json=new ObjectMapper();

    @BeforeEach void setup() {
        sessions.clear();groups.clear();links.clear();ids.set(1000);
        routes=mock(TopicRouteSessionMapper.class);runs=mock(ThemeTeamRunMapper.class);teams=mock(PlayTeamMapper.class);topics=mock(CmsTopicMapper.class);progress=mock(PlayerNodeProgressMapper.class);
        CmsTopicNodeMapper nodes=mock(CmsTopicNodeMapper.class);activities=mock(CmsActivityMapper.class);registrations=mock(CmsRegistrationMapper.class);
        runtime=new TopicRouteRuntimeServiceImpl(routes,topics,nodes,progress,activities,registrations);
        ThemeRuntimeFeaturePolicy policy=new ThemeRuntimeFeaturePolicy();ReflectionTestUtils.setField(policy,"enabled",true);ReflectionTestUtils.setField(runtime,"themeRuntimePolicy",policy);
        ReflectionTestUtils.setField(runtime,"themeTeamRunMapper",runs);ReflectionTestUtils.setField(runtime,"themeTeamCoordinator",new ThemeTeamRunCoordinator(teams,runs));
        CmsTopic topic=new CmsTopic();topic.setId(10L);topic.setDelFlag(0);topic.setRouteMode("LINEAR");topic.setPublishedConfigVersion(1L);
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true,\"hp\":{\"enabled\":true,\"max\":10,\"init\":6},\"recovery\":{\"stageRestHpFloor\":0,\"stageRestLuckFloor\":0,\"stationEndIfHurt\":0}}");
        topic.setPublishedThemeRuntimeConfigJson("{\"schemaVersion\":1,\"capacity\":0,\"replayEnabled\":true,\"items\":[],\"rotation\":\"ROUND_ROBIN\",\"currentScene\":\"first\",\"scenes\":[{\"id\":\"first\",\"name\":\"第一幕\"},{\"id\":\"second\",\"name\":\"第二幕\"}]}");
        when(topics.selectCmsTopicById(10L)).thenReturn(topic);
        CmsTopicNode node=new CmsTopicNode();node.setId(11L);node.setTopicId(10L);node.setStatus(1L);node.setSortId(1L);
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
        activity=new CmsActivity();activity.setId(5L);activity.setTopicId(10L);activity.setStatus(1L);activity.setDelFlag(0);
        when(activities.selectCmsActivityById(5L)).thenReturn(activity);
        CmsRegistration registration=new CmsRegistration();registration.setTicketId(1L);registration.setPurchaseKind(0);
        when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(registration));
        team=new PlayTeam();team.setId(33L);team.setOwnerType(2);team.setOwnerId(5L);team.setLeaderMemberId(7L);team.setStatus(0);
        members=new ArrayList<>(Arrays.asList(member(8),member(7)));
        when(teams.selectPlayTeamByIdForUpdate(33L)).thenReturn(team);when(teams.selectPlayTeamById(33L)).thenReturn(team);
        when(teams.selectActiveMembersForUpdate(33L)).thenAnswer(i->new ArrayList<>(members));
        when(teams.selectActiveMember(eq(33L),anyLong())).thenAnswer(i->members.stream().filter(m->m.getMemberId().equals(i.getArgument(1))).findFirst().orElse(null));
        sessions.put(101L,old(101,7));sessions.put(102L,old(102,8));
        when(routes.selectCurrentByOwnerForUpdate(eq(5L),eq(10L),anyLong())).thenAnswer(i->latest(i.getArgument(2)));
        when(routes.selectCurrentByOwner(eq(5L),eq(10L),anyLong())).thenAnswer(i->latest(i.getArgument(2)));
        when(routes.selectByOwnerForUpdate(eq(5L),eq(10L),anyLong(),anyString())).thenAnswer(i->owned(i.getArgument(2),i.getArgument(3)));
        when(routes.selectByOwner(eq(5L),eq(10L),anyLong(),anyString())).thenAnswer(i->owned(i.getArgument(2),i.getArgument(3)));
        when(routes.selectAccessibleById(anyLong(),anyLong())).thenAnswer(i->{TopicRouteSession s=sessions.get(i.getArgument(0));return s!=null&&s.getMemberId().equals(i.getArgument(1))?s:null;});
        when(routes.selectAccessibleByIdForUpdate(anyLong(),anyLong())).thenAnswer(i->{TopicRouteSession row=sessions.get(i.getArgument(0));return row!=null&&row.getMemberId().equals(i.getArgument(1))?row:null;});
        when(routes.insertSession(any())).thenAnswer(i->{TopicRouteSession s=i.getArgument(0);s.setId(ids.incrementAndGet());sessions.put(s.getId(),s);return 1;});
        when(routes.countRuns(eq(5L),eq(10L),anyLong())).thenAnswer(i->(int)sessions.values().stream().filter(s->s.getMemberId().equals(i.getArgument(2))).count());
        when(runs.selectCurrentByTeamForUpdate(33L,5L,10L)).thenAnswer(i->groups.values().stream().reduce((a,b)->b).orElse(null));
        when(runs.selectCurrentByTeam(33L,5L,10L)).thenAnswer(i->groups.values().stream().reduce((a,b)->b).orElse(null));
        when(runs.selectByRequestForUpdate(eq(33L),eq(5L),eq(10L),anyString())).thenAnswer(i->groups.values().stream().filter(g->g.getRequestKey().equals(i.getArgument(3))).findFirst().orElse(null));
        when(runs.selectBySourceForUpdate(eq(33L),eq(5L),eq(10L),anyLong())).thenAnswer(i->groups.values().stream().filter(g->Objects.equals(g.getSourceSessionId(),i.getArgument(3))).findFirst().orElse(null));
        when(runs.selectByPreviousRunForUpdate(eq(33L),eq(5L),eq(10L),anyLong())).thenAnswer(i->groups.values().stream().filter(g->Objects.equals(g.getPreviousRunId(),i.getArgument(3))).findFirst().orElse(null));
        when(runs.selectByIdForUpdate(anyLong())).thenAnswer(i->groups.get(i.getArgument(0)));
        when(runs.insertRun(any())).thenAnswer(i->{ThemeTeamRun g=i.getArgument(0);g.setId(501L+groups.size());groups.put(g.getId(),g);return 1;});
        when(runs.assignPlayInstance(anyLong(),anyString())).thenReturn(1);
        when(runs.insertMember(any())).thenAnswer(i->{links.add(i.getArgument(0));return 1;});
        when(runs.selectMembers(anyLong())).thenAnswer(i->{List<ThemeTeamRun.Member> out=new ArrayList<>();for(ThemeTeamRun.Member m:links)if(m.getRunId().equals(i.getArgument(0)))out.add(m);return out;});
        when(runs.selectMembersForUpdate(anyLong())).thenAnswer(i->{List<ThemeTeamRun.Member> out=new ArrayList<>();for(ThemeTeamRun.Member m:links)if(m.getRunId().equals(i.getArgument(0)))out.add(m);return out;});
        when(runs.selectBySessionId(anyLong(),anyLong())).thenAnswer(i->groupFor(i.getArgument(0),i.getArgument(1)));
        when(runs.selectByCurrentMember(anyLong(),eq(5L),eq(10L))).thenAnswer(i->{TopicRouteSession s=latest(i.getArgument(0));return s==null?null:groupFor(s.getId(),s.getMemberId());});
        when(progress.selectPlayerNodeProgressListForInstance(any(),anyString())).thenReturn(Collections.emptyList());
    }
    private PlayTeamMember member(long id){PlayTeamMember m=new PlayTeamMember();m.setTeamId(33L);m.setMemberId(id);m.setStatus(1);m.setRole(id==7?1:0);return m;}
    private TopicRouteSession old(long id,long owner){TopicRouteSession s=new TopicRouteSession();s.setId(id);s.setMemberId(owner);s.setActivityId(5L);s.setTopicId(10L);s.setPlayInstanceId("default");s.setStatus(TopicRouteSession.STATUS_ENDED);s.setVersion(0);s.setConfigSnapshotJson("{\"themeConfig\":{},\"journeyRules\":{\"schemaVersion\":1}}");s.setStateJson("{\"status\":\"ENDED\"}");return s;}
    private TopicRouteSession latest(Long owner){return sessions.values().stream().filter(s->s.getMemberId().equals(owner)).max(Comparator.comparing(TopicRouteSession::getId)).orElse(null);}
    private TopicRouteSession owned(Long owner,String instance){return sessions.values().stream().filter(s->s.getMemberId().equals(owner)&&instance.equals(s.getPlayInstanceId())).findFirst().orElse(null);}
    private ThemeTeamRun groupFor(Long session,Long owner){for(ThemeTeamRun.Member m:links)if(session.equals(m.getSessionId())&&owner.equals(m.getMemberId()))return groups.get(m.getRunId());return null;}
    private Map<String,Object> replay(String key){return runtime.replayThemeTeam(7L,33L,5L,10L,101L,null,key);}

    @Test void wholeCohortGetsOneSceneAndTokenButSeparatePersonalStates()throws Exception {
        Map<String,Object> view=replay("r1");assertEquals(501L,view.get("teamRunId"));assertEquals("team-replay:501",view.get("playInstanceId"));assertEquals("第二幕",view.get("sceneName"));
        assertEquals(2,links.size());assertEquals(4,sessions.size());assertEquals("team-replay:501",latest(8L).getPlayInstanceId());
        assertEquals(latest(7L).getConfigSnapshotJson(),latest(8L).getConfigSnapshotJson());assertNotEquals(latest(7L).getId(),latest(8L).getId());assertEquals(6,json.readTree(latest(7L).getStateJson()).path("variables").path("sys.hp").asInt());
        assertEquals("{\"status\":\"ENDED\"}",sessions.get(101L).getStateJson());assertFalse(view.containsKey("members"));
        assertTrue(runtime.isReplayInstance(7L,10L,5L,"team-replay:501"));verify(progress,never()).insertPlayerNodeProgress(any());
    }
    @Test void repeatedKeyReturnsSameOwnResultWithoutOpeningAnotherGeneration(){Map<String,Object> first=replay("same");Map<String,Object> again=replay("same");assertEquals(first,again);assertEquals(1,groups.size());assertEquals(4,sessions.size());verify(runs,times(1)).insertRun(any());}
    @Test void sameKeyChangedSourceFails(){replay("same");assertThrows(ServiceException.class,()->runtime.replayThemeTeam(7L,33L,5L,10L,null,null,"same"));assertEquals(1,groups.size());}
    @Test void onlyCurrentLeaderCanReset(){assertThrows(ServiceException.class,()->runtime.replayThemeTeam(8L,33L,5L,10L,102L,null,"r"));verify(runs,never()).insertRun(any());}
    @Test void activeMemberBlocksWholeReplayBeforeAnyInsert(){sessions.get(102L).setStatus(0);assertThrows(ServiceException.class,()->replay("r"));verify(runs,never()).insertRun(any());verify(routes,never()).insertSession(any());}
    @Test void departedUnfinishedMemberIsNotResetAndNewMemberWithoutOldRunCanJoin(){sessions.get(102L).setStatus(0);members=Arrays.asList(member(7),member(9));replay("r");assertEquals(2,links.size());assertEquals(102L,latest(8L).getId());assertEquals(0,latest(8L).getStatus());assertNull(links.stream().filter(m->m.getMemberId()==9L).findFirst().get().getPreviousSessionId());assertFalse(runtime.isReplayInstance(9L,10L,5L,"team-replay:501"));}
    @Test void newLeaderWithoutOldRunUsesActionKeyAndDoesNotGetPermanentlyStuck(){sessions.remove(101L);Map<String,Object> view=runtime.replayThemeTeam(7L,33L,5L,10L,null,null,"new-leader");assertEquals(501L,view.get("teamRunId"));assertEquals(view,runtime.replayThemeTeam(7L,33L,5L,10L,null,null,"new-leader"));}
    @Test void nullSourceCannotSilentlyResetAnExistingLeaderRun(){assertThrows(ServiceException.class,()->runtime.replayThemeTeam(7L,33L,5L,10L,null,null,"r"));verify(runs,never()).insertRun(any());}
    @Test void defaultOffRejectsBeforeAnyTeamOrStorageRead(){ThemeRuntimeFeaturePolicy off=new ThemeRuntimeFeaturePolicy();ReflectionTestUtils.setField(runtime,"themeRuntimePolicy",off);assertThrows(ServiceException.class,()->replay("r"));verifyNoInteractions(teams,runs);}
    @Test void currentMemberLockOrderIsTeamThenMembersThenGroupThenSortedRoutes(){replay("r");org.mockito.InOrder order=inOrder(teams,runs,routes);order.verify(teams).selectPlayTeamByIdForUpdate(33L);order.verify(teams).selectActiveMembersForUpdate(33L);order.verify(runs).selectCurrentByTeamForUpdate(33L,5L,10L);order.verify(routes).selectCurrentByOwnerForUpdate(5L,10L,7L);order.verify(routes).selectCurrentByOwnerForUpdate(5L,10L,8L);}
    @Test void personalReplayCannotResetATeamGeneration(){replay("r");TopicRouteSession current=latest(7L);assertThrows(ServiceException.class,()->runtime.replayTheme(7L,current.getId()));}
    @Test void firstGroupMemberStillChecksOriginalPersonalNodeCompletion(){members=Arrays.asList(member(7),member(9));replay("r");assertTrue(runtime.isOriginalNodeAwardEligible(9L,10L,5L,11L,"team-replay:501"));PlayerNodeProgress old=new PlayerNodeProgress();old.setStatus(1);when(progress.selectPlayerNodeProgressListForInstance(any(),eq("default"))).thenReturn(Collections.singletonList(old));assertFalse(runtime.isOriginalNodeAwardEligible(9L,10L,5L,11L,"team-replay:501"));}
    private void rejectedReceipt(String path,boolean cancelled) {
        replay("first");
        Long source=latest(7L).getId();latest(7L).setStatus(TopicRouteSession.STATUS_ENDED);latest(8L).setStatus(TopicRouteSession.STATUS_ENDED);
        Map<String,Object> expected=runtime.replayThemeTeam(7L,33L,5L,10L,source,501L,"second");
        String key="action".equals(path)?"second":"retry-"+path;
        if("previous".equals(path))doReturn(null).when(runs).selectBySourceForUpdate(33L,5L,10L,source);
        // Each tested branch really returns the receipt when current eligibility is valid.
        assertEquals(expected,runtime.replayThemeTeam(7L,33L,5L,10L,source,501L,key));
        assertEquals(2,groups.size());assertEquals(6,sessions.size());
        if(cancelled)activity.setCancelTime(new Date());else when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());
        clearInvocations(runs,routes);
        IllegalStateException error=assertThrows(IllegalStateException.class,()->runtime.replayThemeTeam(7L,33L,5L,10L,source,501L,key));
        assertEquals(cancelled?"活动已取消":"请先报名并完成支付",error.getMessage());
        verify(runs,never()).selectByRequestForUpdate(anyLong(),anyLong(),anyLong(),anyString());
        verify(runs,never()).selectBySourceForUpdate(anyLong(),anyLong(),anyLong(),anyLong());
        verify(runs,never()).selectByPreviousRunForUpdate(anyLong(),anyLong(),anyLong(),anyLong());
        verify(runs,never()).insertRun(any());verify(routes,never()).insertSession(any());
        assertEquals(2,groups.size());assertEquals(6,sessions.size());
    }
    @Test void actionReceiptRejectsRevokedRegistration(){rejectedReceipt("action",false);}
    @Test void actionReceiptRejectsCancelledActivity(){rejectedReceipt("action",true);}
    @Test void sourceReceiptRejectsRevokedRegistration(){rejectedReceipt("source",false);}
    @Test void sourceReceiptRejectsCancelledActivity(){rejectedReceipt("source",true);}
    @Test void previousGenerationReceiptRejectsRevokedRegistration(){rejectedReceipt("previous",false);}
    @Test void previousGenerationReceiptRejectsCancelledActivity(){rejectedReceipt("previous",true);}
    private org.springframework.jdbc.core.JdbcTemplate database(boolean failSecondMember) throws Exception {
        org.h2.jdbcx.JdbcDataSource ds=new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:team_replay_"+System.nanoTime()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");ds.setUser("sa");
        org.springframework.jdbc.core.JdbcTemplate jdbc=new org.springframework.jdbc.core.JdbcTemplate(ds);
        jdbc.execute("create table play_team(id bigint primary key,owner_type int,owner_id bigint,leader_member_id bigint,invite_code varchar(80),title varchar(100),join_mode int,max_members int,joined_count int,status int,expire_time timestamp,create_time timestamp,update_time timestamp)");
        jdbc.execute("create table play_team_member(id bigint primary key,team_id bigint,member_id bigint,role int,status int,apply_message varchar(500),apply_expire_time timestamp,create_time timestamp,update_time timestamp,unique(team_id,member_id))");
        jdbc.execute("create table topic_route_session(id bigint primary key auto_increment,activity_id bigint,topic_id bigint,member_id bigint,play_instance_id varchar(64),config_version bigint,config_snapshot_json clob,state_json clob,status int,current_node_id bigint,recommended_node_id bigint,seed bigint,version int,completed_at timestamp,create_time timestamp,update_time timestamp,unique(activity_id,topic_id,member_id,play_instance_id)"+(failSecondMember?",constraint reject_second_new_run check(member_id<>8 or play_instance_id='default')":"")+")");
        jdbc.execute("create table theme_team_run(id bigint primary key auto_increment,team_id bigint,activity_id bigint,topic_id bigint,previous_run_id bigint,source_session_id bigint,request_key varchar(80),request_digest varchar(64),play_instance_id varchar(64),generation int,config_version bigint,config_snapshot_json clob,create_time timestamp,unique(team_id,activity_id,topic_id,request_key),unique(team_id,activity_id,topic_id,source_session_id),unique(team_id,activity_id,topic_id,generation),unique(previous_run_id),unique(play_instance_id))");
        jdbc.execute("create table theme_team_run_member(run_id bigint,member_id bigint,previous_session_id bigint,session_id bigint,primary key(run_id,member_id),unique(session_id),unique(run_id,previous_session_id))");
        jdbc.update("insert into play_team(id,owner_type,owner_id,leader_member_id,status) values(33,2,5,7,0)");
        jdbc.update("insert into play_team_member(id,team_id,member_id,role,status) values(1,33,7,1,1),(2,33,8,0,1)");
        for(TopicRouteSession old:sessions.values())jdbc.update("insert into topic_route_session(id,activity_id,topic_id,member_id,play_instance_id,config_snapshot_json,state_json,status,version,config_version) values(?,5,10,?,'default',?,?,2,0,1)",old.getId(),old.getMemberId(),old.getConfigSnapshotJson(),old.getStateJson());
        org.mybatis.spring.SqlSessionFactoryBean factory=new org.mybatis.spring.SqlSessionFactoryBean();factory.setDataSource(ds);
        factory.setTransactionFactory(new org.mybatis.spring.transaction.SpringManagedTransactionFactory());
        factory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/business/ThemeTeamRunMapper.xml"),new org.springframework.core.io.ClassPathResource("mapper/business/TopicRouteSessionMapper.xml"),new org.springframework.core.io.ClassPathResource("mapper/business/PlayTeamMapper.xml"));
        org.mybatis.spring.SqlSessionTemplate sql=new org.mybatis.spring.SqlSessionTemplate(factory.getObject());
        routes=sql.getMapper(TopicRouteSessionMapper.class);runs=sql.getMapper(ThemeTeamRunMapper.class);teams=sql.getMapper(PlayTeamMapper.class);
        ReflectionTestUtils.setField(runtime,"routeMapper",routes);ReflectionTestUtils.setField(runtime,"themeTeamRunMapper",runs);ReflectionTestUtils.setField(runtime,"themeTeamCoordinator",new ThemeTeamRunCoordinator(teams,runs));
        transaction=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return jdbc;
    }
    private org.springframework.transaction.support.TransactionTemplate transaction;

    @Test @org.junit.jupiter.api.Tag("h2") void realSpringMyBatisH2CommitsTheWholeGeneration() throws Exception {
        org.springframework.jdbc.core.JdbcTemplate db=database(false);
        Map<String,Object> result=transaction.execute(status->replay("database"));
        assertEquals(1,db.queryForObject("select count(*) from theme_team_run",Integer.class));assertEquals(2,db.queryForObject("select count(*) from theme_team_run_member",Integer.class));
        assertEquals(2,db.queryForObject("select count(*) from topic_route_session where play_instance_id=?",Integer.class,result.get("playInstanceId")));
        assertEquals(2,db.queryForObject("select count(*) from topic_route_session where play_instance_id='default' and status=2",Integer.class));
    }
    @Test @org.junit.jupiter.api.Tag("h2") void realSpringMyBatisH2RollsBackFirstMemberAndGroupWhenSecondInsertFails() throws Exception {
        org.springframework.jdbc.core.JdbcTemplate db=database(true);assertThrows(RuntimeException.class,()->transaction.execute(status->replay("rollback")));
        assertEquals(0,db.queryForObject("select count(*) from theme_team_run",Integer.class));assertEquals(0,db.queryForObject("select count(*) from theme_team_run_member",Integer.class));assertEquals(2,db.queryForObject("select count(*) from topic_route_session",Integer.class));
        assertEquals(2,db.queryForObject("select count(*) from topic_route_session where status=2",Integer.class));
    }
    @Test @org.junit.jupiter.api.Tag("h2") void realH2ConcurrentRetriesReturnOneGeneration() throws Exception {
        org.springframework.jdbc.core.JdbcTemplate db=database(false);java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.List<java.util.concurrent.Future<Map<String,Object>>> results=new ArrayList<>();
            for(int i=0;i<2;i++)results.add(pool.submit(()->{start.await();return transaction.execute(status->replay("concurrent"));}));
            start.countDown();Map<String,Object> first=results.get(0).get(10,java.util.concurrent.TimeUnit.SECONDS);Map<String,Object> second=results.get(1).get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(first,second);assertEquals(1,db.queryForObject("select count(*) from theme_team_run",Integer.class));assertEquals(2,db.queryForObject("select count(*) from theme_team_run_member",Integer.class));
        } finally { pool.shutdownNow(); }
    }
    @Test void ordinaryNodeInATeamGenerationDoesNotBecomeAMissingTemplateError() {
        replay("ordinary-node");assertNull(runtime.resolveTeamGameScope(7L,10L,5L,11L,33L,false));
        assertEquals(4,sessions.size());
    }
}
