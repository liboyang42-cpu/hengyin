package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.it.ItSchema;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real session/role/receipt rows and original business transactions, not a fake successful command. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT",matches="1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GameSessionIndependentRevisionMySqlIT {
    DriverManagerDataSource source;
    JdbcTemplate admin,db;
    String database;
    SqlSessionTemplate sql;
    GameSessionRuntimeMapper mapper;
    IGameSessionRuntimeService runtime;
    static final String CONFIG="""
      {"schemaVersion":1,"roles":[{"code":"DETECTIVE","name":"侦探"}],
       "chapters":[{"chapterId":1,"title":"第一章"}],
       "stations":[{"code":"CLOCK_TOWER","nodeName":"旧钟楼",
       "playerTask":{"taskCode":"CLOCK_TEXT","prompt":"输入年份","inputType":"TEXT","verificationRequired":false,
       "expectedHash":"d54123de468bd42ea00dafbd777f85fe5fa1ff6404d9838c007953c25c92a1c5",
       "hintPolicy":{"levels":[{"level":1,"text":"观察年份","impactLabel":"记录一级提示"},
       {"level":2,"text":"四位数字","impactLabel":"记录二级提示"}],"reveal":{"text":"1998","impactLabel":"兜底完成"}}}}],
       "story":{"variables":{"trust":0},"choices":[
         {"id":"FOLLOW_NOTE","label":"追查纸条","stationCode":"CLOCK_TOWER","allowedRoles":["DETECTIVE"],
          "choiceGroup":"CLOCK_DECISION","effects":{"trust":1}},
         {"id":"ASK_CLERK","label":"询问店员","stationCode":"CLOCK_TOWER","allowedRoles":["DETECTIVE"],
          "choiceGroup":"CLOCK_DECISION","effects":{"trust":2}}],"endings":[]}}
      """;
    @BeforeAll void setup() throws Exception {
        MysqlIntegrationTestConfig config=MysqlIntegrationTestConfig.fromEnvironment("independent_revision");
        admin=new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(),config.user(),config.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()",String.class)));
        assertEquals("REPEATABLE-READ",admin.queryForObject("select @@transaction_isolation",String.class));
        database="cy_independent_revision_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("create database "+database);
        source=new DriverManagerDataSource(config.databaseUrl().replace("/"+config.databaseName()+"?","/"+database+"?"),config.user(),config.password());
        db=new JdbcTemplate(source);
        try(Connection connection=source.getConnection()){ItSchema.apply(connection,"it-schema/generic-route-runtime-it-schema.sql");}
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/GameSessionRuntimeMapper.xml"),new ClassPathResource("mapper/business/PlayTeamMapper.xml"));
        sql=new SqlSessionTemplate(factory.getObject());mapper=sql.getMapper(GameSessionRuntimeMapper.class);
    }
    @BeforeEach void seed() {
        for(String table:List.of("game_command_receipt","game_player_node_outcome","game_task_submission","game_story_state","game_team_role_assignment","game_station_run","game_session","play_team_member","play_team","cms_registration","cms_activity"))db.update("delete from "+table);
        db.update("insert into cms_activity(id,topic_id) values(70,80)");
        db.update("insert into play_team(id,owner_type,owner_id,leader_member_id,status) values(90,2,70,7,2),(99,2,70,9,2)");
        db.update("insert into play_team_member(team_id,member_id,status) values(90,7,1),(99,9,1)");
        db.update("insert into cms_registration(member_id,owner_type,owner_id,topic_id,payment_status,registration_status) values(7,2,70,80,2,2),(9,2,70,80,2,2)");
        db.update("insert into game_session(id,activity_id,topic_id,club_id,module_config_snapshot_json,status,current_chapter_id,revision,prepared_by,create_time,update_time) values(50,70,80,40,?,'RUNNING',1,10,88,now(),now())",CONFIG);
        db.update("insert into game_station_run(id,game_session_id,activity_id,node_id,node_name,station_code,merchant_member_id,source_type,source_id,status,revision,service_start_at,service_end_at,create_time,update_time) values(60,50,70,11,'旧钟楼','CLOCK_TOWER',310,'CHAPTER_NODE',11,'ACTIVE',0,date_sub(now(),interval 1 hour),date_add(now(),interval 1 hour),now(),now())");
        db.update("insert into game_team_role_assignment(game_session_id,team_id,member_id,role_code,role_name,status,assigned_by,revision,create_time,update_time) values(50,90,7,'DETECTIVE','侦探','CONFIRMED',88,0,now(),now()),(50,99,9,'DETECTIVE','侦探','CONFIRMED',88,0,now(),now())");
        runtime=service(mapper);
    }
    @AfterAll void cleanup(){if(admin!=null && database!=null)admin.execute("drop database "+database);}
    IGameSessionRuntimeService service(GameSessionRuntimeMapper facts) {
        GameSessionRuntimeServiceImpl target=new GameSessionRuntimeServiceImpl();ReflectionTestUtils.setField(target,"runtimeMapper",facts);
        ReflectionTestUtils.setField(target,"playTeamMapper",sql.getMapper(PlayTeamMapper.class));
        CmsActivityMapper activities=mock(CmsActivityMapper.class);
        when(activities.selectCmsActivityById(70L)).thenAnswer(call->{CmsActivity a=new CmsActivity();a.setId(70L);a.setTopicId(80L);a.setClubId(40L);a.setStatus(1L);a.setDelFlag(0);a.setStartDate(new Date(System.currentTimeMillis()-3600000));a.setEndDate(new Date(System.currentTimeMillis()+3600000));return a;});
        when(activities.selectCmsActivityByIdForUpdate(70L)).thenAnswer(call->{CmsActivity a=new CmsActivity();a.setId(70L);a.setTopicId(80L);a.setClubId(40L);a.setStatus(1L);a.setDelFlag(0);a.setStartDate(new Date(System.currentTimeMillis()-3600000));a.setEndDate(new Date(System.currentTimeMillis()+3600000));return a;});
        ReflectionTestUtils.setField(target,"activityMapper",activities);
        com.chengyinhub.business.mapper.CmsRegistrationMapper currentRegistrations=mock(com.chengyinhub.business.mapper.CmsRegistrationMapper.class);
        CurrentRegistrationStubs.currentFromProbe(currentRegistrations); // A包当前资格锁读；归属与已付事实仍由真表 play_team/hasPaidRegistration 判定
        ReflectionTestUtils.setField(target,"currentPlayRegistrationMapper",currentRegistrations);
        ICapabilityService capability=mock(ICapabilityService.class);
        when(capability.hasCapability(88L,MemberCapability.CAP_CLUB_LEADER)).thenReturn(true);
        ReflectionTestUtils.setField(target,"capabilityService",capability);
        IClubMemberService clubs=mock(IClubMemberService.class);
        when(clubs.isTopicOwner(80L,40L,88L)).thenReturn(true);
        ReflectionTestUtils.setField(target,"clubMemberService",clubs);
        IPlayerNodeProgressService progress=mock(IPlayerNodeProgressService.class);
        when(progress.selectPlayerNodeProgressList(any())).thenAnswer(call->{PlayerNodeProgress p=call.getArgument(0);p.setCheckinTime(new Date());return Collections.singletonList(p);});
        ReflectionTestUtils.setField(target,"playerNodeProgressService",progress);
        ReflectionTestUtils.setField(target,"clock",(AdvancedGameClock)Date::new);
        ProxyFactory proxy=new ProxyFactory(target);proxy.setInterfaces(IGameSessionRuntimeService.class);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        return (IGameSessionRuntimeService)proxy.getProxy();
    }
    GameSessionCommand hint(String request,int expected,int level) {
        GameSessionCommand c=new GameSessionCommand();c.setActivityId(70L);c.setNodeId(11L);c.setRequestId(request);c.setExpectedRevision(expected);c.setAction("PLAYER_HINT");c.getPayload().put("level",level);return c;
    }
    long connectionId() throws Exception {Connection connection=DataSourceUtils.getConnection(source);try(var statement=connection.createStatement();var row=statement.executeQuery("select connection_id()")){assertTrue(row.next());assertEquals(database,connection.getCatalog());return row.getLong(1);}}
    Object invoke(java.lang.reflect.Method method,Object[] args) throws Throwable {try{return method.invoke(mapper,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}}
    boolean actualWait(long holder,long waiter) {
        if(holder<=0 || waiter<=0 || holder==waiter)return false;
        if(Boolean.TRUE.equals(db.queryForObject("select @@performance_schema",Boolean.class)))
            return db.queryForObject("select count(*) from performance_schema.data_lock_waits w join performance_schema.data_locks r on r.engine=w.engine and r.engine_lock_id=w.requesting_engine_lock_id join performance_schema.threads rt on rt.thread_id=w.requesting_thread_id join performance_schema.threads bt on bt.thread_id=w.blocking_thread_id where r.object_schema=? and r.object_name='game_session' and rt.processlist_id=? and bt.processlist_id=?",Integer.class,database,waiter,holder)>0;
        int waits=db.queryForObject("select count(*) from information_schema.innodb_trx t join information_schema.processlist p on p.id=t.trx_mysql_thread_id where p.db=? and t.trx_mysql_thread_id=? and t.trx_state='LOCK WAIT' and t.trx_requested_lock_id is not null and lower(t.trx_query) like '%from game_session%for update%'",Integer.class,database,waiter);
        int locks=db.queryForObject("select count(*) from information_schema.innodb_trx where trx_mysql_thread_id=? and trx_rows_locked>0",Integer.class,holder);
        return waits==1 && locks==1;
    }
    List<GameCommandReceiptView> twoTeamsSameProjection() throws Exception {
        CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);AtomicLong holder=new AtomicLong(),waiter=new AtomicLong();
        List<String> firstReads=new CopyOnWriteArrayList<>(),secondReads=new CopyOnWriteArrayList<>();
        GameSessionRuntimeMapper first=(GameSessionRuntimeMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{GameSessionRuntimeMapper.class},(p,m,a)->{
            firstReads.add(m.getName());Object result=invoke(m,a);if(m.getName().equals("selectSessionByActivityForUpdate")){holder.set(connectionId());held.countDown();assertTrue(release.await(15,TimeUnit.SECONDS));}return result;
        });
        GameSessionRuntimeMapper second=(GameSessionRuntimeMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{GameSessionRuntimeMapper.class},(p,m,a)->{
            secondReads.add(m.getName());if(m.getName().equals("selectSessionByActivityForUpdate"))waiter.set(connectionId());return invoke(m,a);
        });
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<GameCommandReceiptView> one=pool.submit(()->service(first).command(GameActorContext.player(7L),hint("scope-hint-first",10,1)));
            assertTrue(held.await(5,TimeUnit.SECONDS));
            Future<GameCommandReceiptView> two=pool.submit(()->service(second).command(GameActorContext.player(9L),hint("scope-hint-second",10,1)));
            boolean observed=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<deadline){assertFalse(one.isDone());assertFalse(two.isDone());if(actualWait(holder.get(),waiter.get())){observed=true;break;}Thread.sleep(150);}
            assertTrue(observed,"Actual second JDBC transaction must wait on the first game_session row lock");
            assertEquals("selectSessionByActivityForUpdate",firstReads.get(0));
            assertEquals(List.of("selectSessionByActivityForUpdate"),secondReads,
                    "The waiting transaction must not pin a repeatable-read snapshot through a prior receipt read");
            assertThrows(TimeoutException.class,()->two.get(150,TimeUnit.MILLISECONDS));
            release.countDown();return List.of(one.get(5,TimeUnit.SECONDS),two.get(5,TimeUnit.SECONDS));
        } finally {
            release.countDown();pool.shutdownNow();
            assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS),"Previous JDBC transactions must terminate before the next fixture reset");
        }
    }
    @Test void twoIndependentTeamHintsAtSameRevisionBothApplyUnderTheActualSessionMutex() throws Exception {
        List<GameCommandReceiptView> applied=twoTeamsSameProjection();
        assertEquals(List.of("APPLIED","APPLIED"),applied.stream().map(GameCommandReceiptView::getOutcome).toList());
        assertEquals(12,mapper.selectSessionByActivity(70L).getRevision());
        assertEquals(1,mapper.selectPlayerHintLevels(50L,7L).size());assertEquals(1,mapper.selectPlayerHintLevels(50L,9L).size());
        assertEquals(2,db.queryForObject("select count(*) from game_command_receipt where outcome='APPLIED'",Integer.class));
    }
    @Test void restoringTheOldGlobalEqualityGateReproducesTheSecondTeamsActualConflict() throws Exception {
        @SuppressWarnings("unchecked") Set<String> independent=(Set<String>)ReflectionTestUtils.getField(GameSessionRuntimeServiceImpl.class,"INDEPENDENT_PLAYER_ACTIONS");
        Set<String> saved=new HashSet<>(independent);
        try {independent.clear();ExecutionException failure=assertThrows(ExecutionException.class,this::twoTeamsSameProjection);
            assertInstanceOf(GameSessionException.class,failure.getCause());assertEquals("GAME_STATE_CONFLICT",((GameSessionException)failure.getCause()).getErrorCode());
            assertEquals(11,mapper.selectSessionByActivity(70L).getRevision());assertEquals(1,db.queryForObject("select count(*) from game_command_receipt where outcome='APPLIED'",Integer.class));
        } finally {independent.clear();independent.addAll(saved);}
        assertEquals(saved,independent);
        assertEquals(Set.of("PLAYER_HINT","PLAYER_REVEAL","PLAYER_SUBMIT","PLAYER_CHOICE"),independent);
        assertEquals("APPLIED",runtime.command(GameActorContext.player(9L),hint("scope-hint-restored",10,1)).getOutcome());
        assertEquals(12,mapper.selectSessionByActivity(70L).getRevision());
        assertEquals(2,db.queryForObject("select count(*) from game_command_receipt where outcome='APPLIED'",Integer.class));
        assertEquals(1,mapper.selectPlayerHintLevels(50L,7L).size());assertEquals(1,mapper.selectPlayerHintLevels(50L,9L).size());
    }
    @Test void sameMembersRepeatedLevelOneStillRejectsAndOriginalReceiptRemainsIdempotent() {
        GameSessionCommand original=hint("scope-original-id",10,1);assertEquals("APPLIED",runtime.command(GameActorContext.player(7L),original).getOutcome());
        GameSessionException wrongLevel=assertThrows(GameSessionException.class,()->runtime.command(GameActorContext.player(7L),hint("scope-other-id",10,1)));
        assertEquals("GAME_HINT_LEVEL_INVALID",wrongLevel.getErrorCode());assertTrue(runtime.command(GameActorContext.player(7L),original).isReplayed());
        GameSessionCommand changed=hint("scope-original-id",11,1);assertEquals("GAME_IDEMPOTENCY_CONFLICT",assertThrows(GameSessionException.class,()->runtime.command(GameActorContext.player(7L),changed)).getErrorCode());
        assertEquals(1,db.queryForObject("select count(*) from game_command_receipt",Integer.class));assertEquals(11,mapper.selectSessionByActivity(70L).getRevision());
    }
    @Test void futureRevisionPausedStationAndChangedActualRoleStillDenyWithoutEffects() {
        assertEquals("GAME_STATE_CONFLICT",assertThrows(GameSessionException.class,()->runtime.command(GameActorContext.player(7L),hint("scope-future-id",11,1))).getErrorCode());
        db.update("update game_station_run set status='PAUSED'");
        assertEquals("GAME_STATION_PAUSED",assertThrows(GameSessionException.class,()->runtime.command(GameActorContext.player(7L),hint("scope-paused-id",0,1))).getErrorCode());
        db.update("update game_station_run set status='ACTIVE'");db.update("update game_team_role_assignment set status='SUPERSEDED' where member_id=7");
        assertEquals("GAME_ROLE_NOT_ASSIGNED",assertThrows(GameSessionException.class,()->runtime.command(GameActorContext.player(7L),hint("scope-role-id",0,1))).getErrorCode());
        assertEquals(0,db.queryForObject("select count(*) from game_command_receipt",Integer.class));assertEquals(10,mapper.selectSessionByActivity(70L).getRevision());
    }
    @Test void failureAfterRealReceiptCompletionRollsBackOriginalReceiptAndRevisionTogether() {
        GameSessionRuntimeMapper failing=(GameSessionRuntimeMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{GameSessionRuntimeMapper.class},(p,m,a)->{
            Object result=invoke(m,a);if(m.getName().equals("completeReceipt"))db.update("insert into cms_activity(id,topic_id) values(70,80)");return result;
        });
        GameSessionCommand submit=action("scope-rollback-id",10,"PLAYER_SUBMIT");
        submit.getPayload().put("taskCode","CLOCK_TEXT");submit.getPayload().put("evidenceUrls",List.of("text:1998"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->service(failing).command(GameActorContext.player(7L),submit));
        assertEquals(0,db.queryForObject("select count(*) from game_command_receipt",Integer.class));assertEquals(10,mapper.selectSessionByActivity(70L).getRevision());
        assertEquals(0,db.queryForObject("select count(*) from game_task_submission",Integer.class));
        assertEquals(0,db.queryForObject("select count(*) from game_player_node_outcome",Integer.class));
        assertEquals(0,db.queryForObject("select count(*) from game_story_state",Integer.class));
        assertTrue(mapper.selectPlayerHintLevels(50L,7L).isEmpty());
        assertEquals("APPLIED",runtime.command(GameActorContext.player(7L),submit).getOutcome());
        assertEquals(11,mapper.selectSessionByActivity(70L).getRevision());
        assertEquals(1,db.queryForObject("select count(*) from game_command_receipt where outcome='APPLIED'",Integer.class));
        assertEquals(1,db.queryForObject("select count(*) from game_task_submission",Integer.class));
        assertEquals("COMPLETED",mapper.selectPlayerNodeOutcome(50L,7L,11L).getStatus());
    }

    GameSessionCommand action(String request,int expected,String action) {
        GameSessionCommand command=hint(request,expected,1);command.setAction(action);command.getPayload().clear();return command;
    }
    void assertGlobalAndOtherTeamUnchanged() {
        GameSession session=mapper.selectSessionByActivity(70L);
        assertEquals("RUNNING",session.getStatus());assertEquals(1L,session.getCurrentChapterId());
        assertEquals("ACTIVE",mapper.selectStation(50L,11L).getStatus());
        assertNull(mapper.selectPlayerNodeOutcome(50L,9L,11L));
        assertNull(mapper.selectStoryState(50L,"TEAM",99L));
        assertEquals(0,db.queryForObject("select count(*) from game_story_state where owner_type in ('WORLD','RUN')",Integer.class));
    }
    @Test void staleSubmitAfterOtherTeamHintCompletesOnlyItsOwnOutcomeAndReplaysOnce() {
        runtime.command(GameActorContext.player(9L),hint("submit-other-team-hint",10,1));
        GameSessionCommand submit=action("stale-submit-id",10,"PLAYER_SUBMIT");
        submit.getPayload().put("taskCode","CLOCK_TEXT");submit.getPayload().put("evidenceUrls",List.of("text:1998"));
        assertEquals("APPLIED",runtime.command(GameActorContext.player(7L),submit).getOutcome());
        assertEquals("COMPLETED",mapper.selectPlayerNodeOutcome(50L,7L,11L).getStatus());
        assertEquals(12,mapper.selectSessionByActivity(70L).getRevision());
        assertTrue(runtime.command(GameActorContext.player(7L),submit).isReplayed());
        assertEquals(1,db.queryForObject("select count(*) from game_task_submission",Integer.class));
        assertEquals(1,db.queryForObject("select count(*) from game_player_node_outcome",Integer.class));
        assertEquals(12,mapper.selectSessionByActivity(70L).getRevision());assertGlobalAndOtherTeamUnchanged();
    }
    @Test void staleRevealUsesCurrentPersonalHintLevelsAndWritesOnlyOwnFallbackOutcome() {
        runtime.command(GameActorContext.player(7L),hint("reveal-personal-level-one",10,1));
        runtime.command(GameActorContext.player(7L),hint("reveal-personal-level-two",10,2));
        runtime.command(GameActorContext.player(9L),hint("reveal-other-team-hint",10,1));
        GameSessionCommand reveal=action("stale-reveal-id",10,"PLAYER_REVEAL");
        assertEquals("APPLIED",runtime.command(GameActorContext.player(7L),reveal).getOutcome());
        assertEquals("FALLBACK_COMPLETED",mapper.selectPlayerNodeOutcome(50L,7L,11L).getStatus());
        assertEquals(14,mapper.selectSessionByActivity(70L).getRevision());assertGlobalAndOtherTeamUnchanged();
        assertEquals("GAME_TASK_ALREADY_COMPLETED",assertThrows(GameSessionException.class,
                ()->runtime.command(GameActorContext.player(7L),action("reveal-new-id",10,"PLAYER_REVEAL"))).getErrorCode());
        assertTrue(runtime.command(GameActorContext.player(7L),reveal).isReplayed());
        assertEquals(1,db.queryForObject("select count(*) from game_player_node_outcome",Integer.class));
    }
    @Test void staleChoiceUpdatesOwnTeamOnceAndStillRejectsResolvedChoiceGroup() throws Exception {
        GameSessionCommand submit=action("choice-submit-id",10,"PLAYER_SUBMIT");
        submit.getPayload().put("taskCode","CLOCK_TEXT");submit.getPayload().put("evidenceUrls",List.of("text:1998"));
        runtime.command(GameActorContext.player(7L),submit);
        runtime.command(GameActorContext.player(9L),hint("choice-other-team-hint",10,1));
        GameSessionCommand choice=action("stale-choice-id",10,"PLAYER_CHOICE");choice.getPayload().put("choiceId","FOLLOW_NOTE");
        assertEquals("APPLIED",runtime.command(GameActorContext.player(7L),choice).getOutcome());
        GameStoryState own=mapper.selectStoryState(50L,"TEAM",90L);
        assertEquals(1,new com.fasterxml.jackson.databind.ObjectMapper().readTree(own.getVariablesJson()).path("trust").asInt());
        assertEquals(13,mapper.selectSessionByActivity(70L).getRevision());assertGlobalAndOtherTeamUnchanged();
        GameSessionCommand otherChoice=action("choice-new-id",10,"PLAYER_CHOICE");otherChoice.getPayload().put("choiceId","ASK_CLERK");
        assertEquals("GAME_CHOICE_GROUP_RESOLVED",assertThrows(GameSessionException.class,
                ()->runtime.command(GameActorContext.player(7L),otherChoice)).getErrorCode());
        assertTrue(runtime.command(GameActorContext.player(7L),choice).isReplayed());
        assertEquals(1,mapper.selectStoryState(50L,"TEAM",90L).getRevision());
        assertEquals(13,mapper.selectSessionByActivity(70L).getRevision());
    }
    @Test void negativeVersionsAndStrictRoleDirectorAndMerchantCommandsStillRejectBeforeWrites() {
        assertEquals("GAME_BAD_REQUEST",assertThrows(GameSessionException.class,
                ()->runtime.command(GameActorContext.player(7L),hint("negative-revision-id",-1,1))).getErrorCode());
        assertEquals("GAME_STATE_CONFLICT",assertThrows(GameSessionException.class,
                ()->runtime.command(GameActorContext.player(7L),action("strict-confirm-role",9,"CONFIRM_ROLE"))).getErrorCode());
        assertEquals("GAME_STATE_CONFLICT",assertThrows(GameSessionException.class,
                ()->runtime.command(GameActorContext.club(88L),action("strict-director-unlock",9,"UNLOCK_CHAPTER"))).getErrorCode());
        assertEquals("GAME_STATE_CONFLICT",assertThrows(GameSessionException.class,
                ()->runtime.command(GameActorContext.merchant(310L),action("strict-station-ready",9,"STATION_READY"))).getErrorCode());
        // F-L3-01: VERIFY_SUBMISSION is now guarded by the submission row CAS, not the global revision;
        // without a pending submission it still rejects before any write.
        assertEquals("GAME_SUBMISSION_NOT_PENDING",assertThrows(GameSessionException.class,
                ()->runtime.command(GameActorContext.merchant(310L),action("object-verify-submission",9,"VERIFY_SUBMISSION"))).getErrorCode());
        assertEquals("GAME_STATE_CONFLICT",assertThrows(GameSessionException.class,
                ()->runtime.command(GameActorContext.merchant(310L),action("future-verify-submission",11,"VERIFY_SUBMISSION"))).getErrorCode());
        assertEquals(0,db.queryForObject("select count(*) from game_command_receipt",Integer.class));
        assertEquals(10,mapper.selectSessionByActivity(70L).getRevision());assertGlobalAndOtherTeamUnchanged();
    }

    @Test void merchantVerifyAfterOtherTeamProgressDecidesOnlyThatSubmissionOnce() {
        db.update("insert into game_task_submission(id,game_session_id,activity_id,station_run_id,node_id,team_id,member_id,task_code,evidence_json,status,revision,create_time,update_time) values(81,50,70,60,11,90,7,'CLOCK_TEXT','[\"text:1998\"]','PENDING',0,now(),now())");
        runtime.command(GameActorContext.player(9L),hint("verify-other-team-hint",10,1));
        assertEquals(11,mapper.selectSessionByActivity(70L).getRevision());
        GameSessionCommand verify=action("stale-merchant-verify",10,"VERIFY_SUBMISSION");
        verify.getPayload().put("submissionId",81L);verify.getPayload().put("decision","REJECT");verify.getPayload().put("reasonCode","BLURRY");
        assertEquals("APPLIED",runtime.command(GameActorContext.merchant(310L),verify).getOutcome());
        assertEquals("REJECTED",db.queryForObject("select status from game_task_submission where id=81",String.class));
        assertEquals(12,mapper.selectSessionByActivity(70L).getRevision());
        assertTrue(runtime.command(GameActorContext.merchant(310L),verify).isReplayed());
        GameSessionCommand again=action("second-merchant-verify",12,"VERIFY_SUBMISSION");
        again.getPayload().put("submissionId",81L);again.getPayload().put("decision","APPROVE");
        assertEquals("GAME_SUBMISSION_NOT_PENDING",assertThrows(GameSessionException.class,
                ()->runtime.command(GameActorContext.merchant(310L),again)).getErrorCode());
        assertNull(mapper.selectPlayerNodeOutcome(50L,7L,11L));
        assertEquals(12,mapper.selectSessionByActivity(70L).getRevision());
    }
}
