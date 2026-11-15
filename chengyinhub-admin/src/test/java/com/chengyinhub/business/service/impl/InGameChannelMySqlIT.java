package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.mapper.CityGameMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.ImBlockMapper;
import com.chengyinhub.business.mapper.ImConversationMapper;
import com.chengyinhub.business.mapper.ImConversationMemberMapper;
import com.chengyinhub.business.mapper.ImMessageMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentCallbackInboxService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Real MySQL 8 integration test for in-game channels:
 * registration auto-join, play team/activity/roam/city-team channel resolve,
 * game event system messages (checkin/claim/takeover/finish), unread badges,
 * and content-safety/report/membership moderation guards.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(45)
class InGameChannelMySqlIT
{
    private static final long PLAYER_A = 101L;
    private static final long PLAYER_B = 102L;
    private static final long OUTSIDER = 103L;
    private static final long ACTIVITY_ID = 501L;
    private static final long TEAM_ID = 301L;
    private static final String BOARD_ID = "board-sh-1";
    private static final String CITY_TEAM_ID = "city-team-uuid-1";

    private JdbcTemplate admin;
    private JdbcTemplate db;
    private String schema;
    private boolean created;

    private IImService im;
    private InGameChannelService inGame;
    private IWxContentSecurityService safety;
    private IAuditTaskService audit;
    private IUmsMemberService profiles;

    @BeforeAll
    void setup() throws Exception
    {
        int port = Integer.parseInt(required("CHENGYIN_MYBATIS_IT_PORT"));
        String user = required("CHENGYIN_MYBATIS_IT_USER");
        String password = System.getenv("CHENGYIN_MYBATIS_IT_PASSWORD");
        assertNotNull(password, "CHENGYIN_MYBATIS_IT_PASSWORD must be provided");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        admin = new JdbcTemplate(new DriverManagerDataSource(base + "mysql" + options, user, password));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        schema = "cy_ingame_ch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        admin.execute("create database " + schema);
        created = true;
        DataSource source = new DriverManagerDataSource(base + schema + options, user, password);
        db = new JdbcTemplate(source);

        Path sql = Paths.get("chengyinhub-admin/sql");
        if (!Files.isDirectory(sql)) sql = Paths.get("sql");
        new ResourceDatabasePopulator(
                new FileSystemResource(sql.resolve("migration_im.sql")),
                new FileSystemResource(sql.resolve("migration_im_send_idempotency_20260913.sql")),
                new FileSystemResource(sql.resolve("migration_20261012_im_media_visibility.sql")),
                new FileSystemResource(sql.resolve("migration_20260820_play_team_core.sql")),
                new FileSystemResource(sql.resolve("migration_20260822_play_team_invite_code.sql")),
                new FileSystemResource(sql.resolve("migration_20260915_play_team_public_apply.sql")),
                new FileSystemResource(sql.resolve("migration_20261113_gaps_a_city_game.sql"))
        ).execute(source);

        db.execute("create table ums_member(id bigint primary key,name varchar(64),nickname varchar(64),avatar varchar(255),wx_open_id varchar(128))");
        db.execute("create table cms_activity(id bigint primary key,topic_id bigint,member_id bigint,name varchar(128),del_flag int default 0)");
        db.execute("create table cms_registration(id bigint primary key,owner_type bigint,owner_id bigint,member_id bigint,payment_status int,registration_status int,activity_title varchar(128))");

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(
                new ClassPathResource("mapper/business/ImConversationMapper.xml"),
                new ClassPathResource("mapper/business/ImConversationMemberMapper.xml"),
                new ClassPathResource("mapper/business/ImMessageMapper.xml"),
                new ClassPathResource("mapper/business/ImBlockMapper.xml"),
                new ClassPathResource("mapper/business/PlayTeamMapper.xml"),
                new ClassPathResource("mapper/business/CityGameMapper.xml")
        );
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(source);

        safety = mock(IWxContentSecurityService.class);
        IWxContentCallbackInboxService inbox = mock(IWxContentCallbackInboxService.class);
        audit = mock(IAuditTaskService.class);
        profiles = mock(IUmsMemberService.class);
        CmsActivityMapper cmsActivityMapper = mock(CmsActivityMapper.class);
        CmsActivity seededAct = new CmsActivity();
        seededAct.setId(ACTIVITY_ID);
        seededAct.setTopicId(11L);
        seededAct.setMemberId(PLAYER_A);
        seededAct.setName("外滩寻宝局");
        when(cmsActivityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(seededAct);
        CmsTopicMapper cmsTopicMapper = mock(CmsTopicMapper.class);

        ImServiceImpl imTarget = new ImServiceImpl();
        ReflectionTestUtils.setField(imTarget, "conversationMapper", session.getMapper(ImConversationMapper.class));
        ReflectionTestUtils.setField(imTarget, "memberMapper", session.getMapper(ImConversationMemberMapper.class));
        ReflectionTestUtils.setField(imTarget, "messageMapper", session.getMapper(ImMessageMapper.class));
        ReflectionTestUtils.setField(imTarget, "blockMapper", session.getMapper(ImBlockMapper.class));
        ReflectionTestUtils.setField(imTarget, "playTeamMapper", session.getMapper(PlayTeamMapper.class));
        ReflectionTestUtils.setField(imTarget, "umsMemberService", profiles);
        ReflectionTestUtils.setField(imTarget, "wxContentSecurityService", safety);
        ReflectionTestUtils.setField(imTarget, "wxContentCallbackInboxService", inbox);
        ReflectionTestUtils.setField(imTarget, "auditTaskService", audit);

        InGameChannelService inGameTarget = new InGameChannelService();
        ReflectionTestUtils.setField(inGameTarget, "conversationMapper", session.getMapper(ImConversationMapper.class));
        ReflectionTestUtils.setField(inGameTarget, "memberMapper", session.getMapper(ImConversationMemberMapper.class));
        ReflectionTestUtils.setField(inGameTarget, "messageMapper", session.getMapper(ImMessageMapper.class));
        ReflectionTestUtils.setField(inGameTarget, "umsMemberService", profiles);
        ReflectionTestUtils.setField(inGameTarget, "cmsActivityMapper", cmsActivityMapper);
        ReflectionTestUtils.setField(inGameTarget, "cmsTopicMapper", cmsTopicMapper);
        ReflectionTestUtils.setField(inGameTarget, "playTeamMapper", session.getMapper(PlayTeamMapper.class));
        ReflectionTestUtils.setField(inGameTarget, "cityGameMapper", session.getMapper(CityGameMapper.class));

        im = (IImService) transactional(imTarget, txManager);
        ReflectionTestUtils.setField(inGameTarget, "imService", im);
        inGame = (InGameChannelService) transactional(inGameTarget, txManager);
        ReflectionTestUtils.setField(imTarget, "inGameChannelService", inGame);
    }

    @BeforeEach
    void seed()
    {
        for (String t : List.of("im_message", "im_conversation_member", "im_conversation", "im_block",
                "play_team_member", "play_team", "city_game_participation", "city_game_team", "city_game_board",
                "cms_registration", "cms_activity", "ums_member"))
        {
            db.update("delete from " + t);
        }
        reset(safety, audit, profiles);
        db.update("insert into ums_member(id,name,nickname,wx_open_id) values(101,'pa','玩家甲','oa'),(102,'pb','玩家乙','ob'),(103,'pc','路人丙','oc')");
        db.update("insert into cms_activity(id,topic_id,member_id,name,del_flag) values(501,11,101,'外滩寻宝局',0)");
        db.update("insert into cms_registration(id,owner_type,owner_id,member_id,payment_status,registration_status,activity_title) values(9001,2,501,101,2,2,'外滩寻宝局'),(9002,2,501,102,2,2,'外滩寻宝局')");
        db.update("insert into play_team(id,owner_type,owner_id,leader_member_id,invite_code,title,join_mode,max_members,joined_count,status,create_time,update_time) values(301,2,501,101,?,'先锋一队',1,4,2,0,now(),now())", "c".repeat(32));
        db.update("insert into play_team_member(team_id,member_id,role,status,create_time,update_time) values(301,101,1,1,now(),now()),(301,102,0,1,now(),now())");
        db.update("insert into city_game_board(board_id,game_id,region_id,season_id,rules_release_id,release_id,rules_hash,rule_version,"
                + "lifecycle,title,revision,board_version,event_sequence,dice_id,dice_faces,dice_weights,actions_per_round,max_rounds,"
                + "roll_cost,acquisition_cost,task_cost,service_cost,challenge_cost,"
                + "acquire_points,challenge_points,holding_yield,goal_score,holding_limit,protection_seconds,quote_valid_seconds,term_seconds,"
                + "min_members,max_members,rules_id,rules_text,challenge_rule_id,protection_text,safety_statement,valid_from,"
                + "map_datum,map_south,map_west,map_north,map_east) values("
                + "?,?,?,?,?,11,?,'v1','OPEN','上海棋盘',1,1,0,'d1','[1,2]','[1,1]',2,3,"
                + "1,2,0,0,0,30,20,5,1,2,300,300,86400,1,4,'rules-1','规则正文','ch-1','保护说明','安全声明',"
                + "'2026-11-01 00:00:00','GCJ02',31.2,121.4,31.3,121.5)",
                BOARD_ID, "city-game", "shanghai", "s1", "r1", "0".repeat(64));
        db.update("insert into city_game_team(team_id,board_id,captain_member_id,invite_code,state,version) values(?,?,101,?,'CONFIRMED',1)",
                CITY_TEAM_ID, BOARD_ID, "INV123");
        db.update("insert into city_game_participation(participation_id,board_id,team_id,member_id,present,confirmed,membership_version) values('p1',?,?,101,1,1,1),('p2',?,?,102,1,1,1)",
                BOARD_ID, CITY_TEAM_ID, BOARD_ID, CITY_TEAM_ID);
    }

    @Test
    void endToEndInGameChannelsSystemEventsUnreadBadgesAndSafetyGuards()
    {
        // 1) Registration confirmation auto-joins activity channel
        CmsRegistration regA = new CmsRegistration();
        regA.setId(9001L);
        regA.setOwnerType(2L);
        regA.setOwnerId(ACTIVITY_ID);
        regA.setMemberId(PLAYER_A);
        regA.setActivityTitle("外滩寻宝局");
        CmsRegistration regB = new CmsRegistration();
        regB.setId(9002L);
        regB.setOwnerType(2L);
        regB.setOwnerId(ACTIVITY_ID);
        regB.setMemberId(PLAYER_B);
        regB.setActivityTitle("外滩寻宝局");
        Long actConvId = inGame.onRegistrationConfirmed(regA);
        assertEquals(actConvId, inGame.onRegistrationConfirmed(regB));

        // 2) Play checkin event posts system message to BOTH play team & activity channel and increments Player B unread
        inGame.postPlayEvent(InGameChannelService.EVENT_CHECKIN, PLAYER_A, ACTIVITY_ID, null, "海关大楼", null);
        Map<String, Object> actSummaryB = inGame.getSummary(PLAYER_B, "activity", ACTIVITY_ID, null, null, null, null, null);
        assertTrue(Boolean.TRUE.equals(actSummaryB.get("exists")));
        assertEquals(1, ((Number) actSummaryB.get("unread")).intValue());

        Map<String, Object> teamChannel = inGame.resolveChannel(PLAYER_B, "team", null, null, TEAM_ID, null, null, null);
        Long teamConvId = ((Number) teamChannel.get("conversationId")).longValue();
        assertTrue(((Number) teamChannel.get("unread")).intValue() >= 1);

        // 3) Roam district channel resolves & receives claim/takeover system events
        Map<String, Object> roamB = inGame.resolveChannel(PLAYER_B, "roam", null, null, null, "徐汇区", null, null);
        Long roamConvId = ((Number) roamB.get("conversationId")).longValue();
        inGame.postRoamEvent(InGameChannelService.EVENT_CLAIM, PLAYER_A, "徐汇区", "武康大楼", null);
        Map<String, Object> roamSummaryB = inGame.getSummary(PLAYER_B, "roam", null, null, null, "徐汇区", null, null);
        assertEquals(1, ((Number) roamSummaryB.get("unread")).intValue());

        // 4) CITY team channel resolves, rejects non-team outsider, posts takeover & finish, supports IM send + report
        assertThrows(ServiceException.class,
                () -> inGame.resolveChannel(OUTSIDER, "city_team", null, null, null, null, CITY_TEAM_ID, BOARD_ID));
        Map<String, Object> cityChannelB = inGame.resolveChannel(PLAYER_B, "city_team", null, null, null, null, CITY_TEAM_ID, BOARD_ID);
        Long cityConvId = ((Number) cityChannelB.get("conversationId")).longValue();
        inGame.postCityGameEvent(InGameChannelService.EVENT_TAKEOVER, PLAYER_A, BOARD_ID, CITY_TEAM_ID, "陆家嘴", "6:3");
        inGame.postCityGameEvent(InGameChannelService.EVENT_FINISH, PLAYER_A, BOARD_ID, CITY_TEAM_ID, "上海棋盘", "本轮已收官");

        @SuppressWarnings("unchecked")
        List<ImMessage> cityMsgs = (List<ImMessage>) im.listMessages(PLAYER_B, cityConvId, null, 20).get("list");
        assertEquals(2, cityMsgs.size());
        assertEquals(0L, cityMsgs.get(0).getSenderId());
        assertTrue(cityMsgs.get(0).getContent().contains("[接管]"));
        assertTrue(cityMsgs.get(1).getContent().contains("[通关]"));

        // Mark read clears badge
        im.markRead(PLAYER_B, cityConvId);
        Map<String, Object> citySummaryAfterRead = inGame.getSummary(PLAYER_B, "city_team", null, null, null, null, CITY_TEAM_ID, BOARD_ID);
        assertEquals(0, ((Number) citySummaryAfterRead.get("unread")).intValue());

        // Player A sends chat message -> content security + unread + Player B reports it through existing guard
        ImMessage sent = im.send(PLAYER_A, cityConvId, null, ImMessage.MSG_TEXT, "准备冲下一格", null);
        im.report(PLAYER_B, sent.getId(), "垃圾广告");
        verify(safety).checkText(eq("准备冲下一格"), any());
        verify(audit).reportContent("IM_MESSAGE", sent.getId(), PLAYER_B, "垃圾广告");

        // Outsider cannot read roam/team/city channels without joining/membership
        assertThrows(ServiceException.class, () -> im.listMessages(OUTSIDER, roamConvId, null, 20));
        assertThrows(ServiceException.class, () -> im.listMessages(OUTSIDER, teamConvId, null, 20));
    }

    @AfterAll
    void teardown()
    {
        if (created && admin != null && schema != null)
        {
            admin.execute("drop database if exists " + schema);
        }
    }

    private static Object transactional(Object target, DataSourceTransactionManager txManager)
    {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(false);
        factory.addAdvice(new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource()));
        return factory.getProxy();
    }

    private static String required(String key)
    {
        String v = System.getenv(key);
        if (v == null || v.isEmpty()) throw new IllegalStateException("Missing env " + key);
        return v;
    }
}
