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
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AC1 · P0 城市跑卡的后端半边(真 MySQL 行锁 + 原事务代理):
 * <ul>
 *   <li>恢复竞态:两个俱乐部终端在同一版本上同时 CLUB_STATION_RESUME 同一暂停站点,恰好一方 APPLIED,
 *       另一方得到明确的 STATION_ALREADY_ACTIVE;站点只迁移一次、全局 revision 只加一;</li>
 *   <li>冻结守卫:FINISH 后玩家提交/提示、俱乐部恢复/暂停一律拒绝,所有 game_* 表与 FINISH 后快照逐行一致。</li>
 * </ul>
 * 夹具沿用 {@code GameSessionIndependentRevisionMySqlIT} 的 it-schema 与 service 装配。
 */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT",matches="1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class P0CityRunChainMySqlIT {
    static final List<String> GAME_TABLES=List.of("game_command_receipt","game_player_node_outcome","game_task_submission",
            "game_story_state","game_team_role_assignment","game_station_run","game_session");
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
       "hintPolicy":{"levels":[{"level":1,"text":"观察年份","impactLabel":"记录一级提示"}],
       "reveal":{"text":"1998","impactLabel":"兜底完成"}}}}],
       "story":{"variables":{},"choices":[],"endings":[]}}
      """;

    @BeforeAll void setup() throws Exception {
        MysqlIntegrationTestConfig config=MysqlIntegrationTestConfig.fromEnvironment("p0_city_run");
        admin=new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(),config.user(),config.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()",String.class)));
        database="cy_p0_city_run_"+UUID.randomUUID().toString().replace("-","");
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
        db.update("insert into play_team(id,owner_type,owner_id,leader_member_id,status) values(90,2,70,7,2)");
        db.update("insert into play_team_member(team_id,member_id,status) values(90,7,1)");
        db.update("insert into cms_registration(member_id,owner_type,owner_id,topic_id,payment_status,registration_status) values(7,2,70,80,2,2)");
        db.update("insert into game_session(id,activity_id,topic_id,club_id,module_config_snapshot_json,status,current_chapter_id,revision,prepared_by,create_time,update_time) values(50,70,80,40,?,'RUNNING',1,10,88,now(),now())",CONFIG);
        db.update("insert into game_station_run(id,game_session_id,activity_id,node_id,node_name,station_code,merchant_member_id,source_type,source_id,status,revision,pause_reason_code,service_start_at,service_end_at,create_time,update_time) values(60,50,70,11,'旧钟楼','CLOCK_TOWER',310,'CHAPTER_NODE',11,'PAUSED',3,'MERCHANT_BUSY',?,?,now(),now())",
                // 服务窗口用 JVM 时间写入:JVM 与 MySQL 时区不同(UTC / Asia/Shanghai 两档)时也一致。
                new java.sql.Timestamp(System.currentTimeMillis()-3600000),new java.sql.Timestamp(System.currentTimeMillis()+3600000));
        db.update("insert into game_team_role_assignment(game_session_id,team_id,member_id,role_code,role_name,status,assigned_by,revision,create_time,update_time) values(50,90,7,'DETECTIVE','侦探','CONFIRMED',88,0,now(),now())");
        runtime=service();
    }
    @AfterAll void cleanup(){if(admin!=null && database!=null)admin.execute("drop database "+database);}

    IGameSessionRuntimeService service() {
        GameSessionRuntimeServiceImpl target=new GameSessionRuntimeServiceImpl();ReflectionTestUtils.setField(target,"runtimeMapper",mapper);
        ReflectionTestUtils.setField(target,"playTeamMapper",sql.getMapper(PlayTeamMapper.class));
        CmsActivityMapper activities=mock(CmsActivityMapper.class);
        when(activities.selectCmsActivityById(70L)).thenAnswer(call->activity());
        when(activities.selectCmsActivityByIdForUpdate(70L)).thenAnswer(call->activity());
        ReflectionTestUtils.setField(target,"activityMapper",activities);
        CmsRegistrationMapper currentRegistrations=mock(CmsRegistrationMapper.class);
        CurrentRegistrationStubs.currentFromProbe(currentRegistrations);
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
    static CmsActivity activity() {
        CmsActivity a=new CmsActivity();a.setId(70L);a.setTopicId(80L);a.setClubId(40L);a.setStatus(1L);a.setDelFlag(0);
        a.setStartDate(new Date(System.currentTimeMillis()-3600000));a.setEndDate(new Date(System.currentTimeMillis()+3600000));return a;
    }
    static GameSessionCommand command(String request,int expected,String action) {
        GameSessionCommand c=new GameSessionCommand();c.setActivityId(70L);c.setNodeId(11L);c.setRequestId(request);c.setExpectedRevision(expected);c.setAction(action);return c;
    }
    String snapshot() {
        StringBuilder rows=new StringBuilder();
        for(String table:GAME_TABLES)rows.append(table).append(db.queryForList("select * from "+table+" order by id")).append('\n');
        return rows.toString();
    }

    @Test void twoClubTerminalsResumingThePausedStationTogetherTransitionItExactlyOnce() throws Exception {
        CyclicBarrier barrier=new CyclicBarrier(2);ExecutorService pool=Executors.newFixedThreadPool(2);
        List<Object> outcomes=new CopyOnWriteArrayList<>();
        try {
            List<Future<?>> calls=new ArrayList<>();
            for(String request:List.of("resume-terminal-a","resume-terminal-b")) calls.add(pool.submit(()->{
                barrier.await(10,TimeUnit.SECONDS);
                try{outcomes.add(runtime.command(GameActorContext.club(88L),command(request,10,"CLUB_STATION_RESUME")).getOutcome());}
                catch(GameSessionException conflict){outcomes.add(conflict.getErrorCode());}
                return null;
            }));
            for(Future<?> call:calls)call.get(30,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
        assertEquals(2,outcomes.size());
        assertEquals(1,Collections.frequency(outcomes,"APPLIED"),"两个终端同时恢复只能一方生效:"+outcomes);
        assertEquals(1,Collections.frequency(outcomes,GameSessionException.STATION_ALREADY_ACTIVE),"后到的一方必须得到明确的无变化:"+outcomes);
        GameStationRun station=mapper.selectStation(50L,11L);
        assertEquals("ACTIVE",station.getStatus());
        assertEquals(4,db.queryForObject("select revision from game_station_run where id=60",Integer.class),"站点只迁移一次");
        assertEquals(11,mapper.selectSessionByActivity(70L).getRevision(),"全局版本只前进一次");
        assertEquals(1,db.queryForObject("select count(*) from game_command_receipt where outcome='APPLIED'",Integer.class));
    }

    @Test void finishedSessionIsFrozenAgainstPlayerAndStaffWrites() {
        assertEquals("APPLIED",runtime.command(GameActorContext.club(88L),command("finish-run",10,"FINISH")).getOutcome());
        assertEquals("FINISHED",mapper.selectSessionByActivity(70L).getStatus());
        String frozen=snapshot();

        GameSessionCommand submit=command("after-finish-submit",11,"PLAYER_SUBMIT");
        submit.getPayload().put("taskCode","CLOCK_TEXT");submit.getPayload().put("evidenceUrls",List.of("text:1998"));
        GameSessionCommand hint=command("after-finish-hint",11,"PLAYER_HINT");hint.getPayload().put("level",1);
        for(GameSessionCommand late:List.of(submit,hint,command("after-finish-reveal",11,"PLAYER_REVEAL")))
            assertThrows(GameSessionException.class,()->runtime.command(GameActorContext.player(7L),late),late.getAction());
        for(String action:List.of("CLUB_STATION_RESUME","CLUB_STATION_PAUSE","FINISH","UNLOCK_CHAPTER"))
            assertThrows(GameSessionException.class,()->runtime.command(GameActorContext.club(88L),command("after-finish-"+action,11,action)),action);
        assertEquals(frozen,snapshot(),"冻结后任何命令都不得改动 game_* 表");
        assertTrue(runtime.command(GameActorContext.club(88L),command("finish-run",10,"FINISH")).isReplayed(),"原 FINISH 只回放");
        assertEquals(frozen,snapshot());
    }
}
