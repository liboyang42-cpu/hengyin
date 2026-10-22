package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.MemberCapability;
import com.chengyinhub.business.domain.dto.GameActorContext;
import com.chengyinhub.business.domain.dto.GameCommandReceiptView;
import com.chengyinhub.business.domain.dto.GameSessionCommand;
import com.chengyinhub.business.it.ItSchema;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.GameSessionRuntimeMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.service.GameSessionException;
import com.chengyinhub.business.service.ICapabilityService;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IGameSessionRuntimeService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SC-SCAN-LEGAL / SC-CITY-END 真库夹具:独占临时库,真 DDL,真 mapper XML,真事务代理。
 *
 * <p>game_* / play_team* 用现有游戏 IT 的 {@code it-schema/generic-route-runtime-it-schema.sql}(抄自迁移);
 * 订单、库存、权益、奖励、到店进度和团核销台账逐表照抄 {@code sql/base_schema_20260818.sql} 的生产 DDL,
 * player_node_progress 再按序叠 {@code migration_20260820_questo_puzzle_atmosphere.sql} 与
 * {@code migration_theme_runtime_20261004.sql} 里对它的 ALTER(与线上同序)。
 * 只有活动行 (cms_activity) 与俱乐部/能力判定是 mock,和既有 GameSessionIndependentRevisionMySqlIT 相同。
 */
final class GameSessionLedgerMySqlFixture {

    static final long ACTIVITY = 70L, TOPIC = 80L, CLUB = 40L, SESSION = 50L, DIRECTOR = 88L;
    static final long ARCHIVE_NODE = 11L, CLOCK_NODE = 12L, MARKET_NODE = 13L;
    static final long ARCHIVE_RUN = 61L, CLOCK_RUN = 62L, MARKET_RUN = 63L;
    static final long ARCHIVE_MERCHANT = 310L, CLOCK_MERCHANT = 320L, MARKET_MERCHANT = 330L;
    static final long PLAYER_A = 7L, TEAM_A = 90L, PLAYER_B = 9L, TEAM_B = 99L;

    /** 订单 / 库存 / 权益 / 奖励 / 到店 / 商家团核销台账:游戏运行时一行都不许动。 */
    static final List<String> LEDGERS = List.of("cms_registration", "coop_order", "coop_group_verify",
            "merchant_chapter_offer", "registration_entitlement", "redemption", "reward_pending",
            "reward_drop_log", "cms_topic_completion_coupon_reward_log", "player_node_progress");

    private static final String[] BASE_TABLES = {"cms_registration", "coop_order", "coop_group_verify",
            "merchant_chapter_offer", "registration_entitlement", "redemption", "reward_pending",
            "reward_drop_log", "cms_topic_completion_coupon_reward_log", "player_node_progress", "ums_member"};

    static final String CONFIG = """
      {"schemaVersion":1,"roles":[{"code":"DETECTIVE","name":"侦探","clues":{"ARCHIVE":"档案线索","CLOCK_TOWER":"钟楼线索"}}],
       "chapters":[{"chapterId":1,"title":"第一章"}],
       "stations":[
        {"code":"ARCHIVE","nodeName":"旧档案馆","playerTask":{"taskCode":"ARCHIVE_TEXT","prompt":"输入档案年份",
          "inputType":"TEXT","verificationRequired":true}},
        {"code":"CLOCK_TOWER","nodeName":"旧钟楼","playerTask":{"taskCode":"CLOCK_TEXT","prompt":"输入钟面年份",
          "inputType":"TEXT","verificationRequired":true}},
        {"code":"MARKET","nodeName":"旧集市","playerTask":{"taskCode":"MARKET_TEXT","prompt":"输入摊位编号",
          "inputType":"TEXT","verificationRequired":true}}],
       "fallbackPlans":[{"planCode":"MARKET_TO_ARCHIVE","version":1,"status":"APPROVED",
          "sourceStationCode":"MARKET","targetStationCode":"ARCHIVE","playerMessage":"请前往旧档案馆完成替代任务"}],
       "story":{"variables":{"trust":0},"choices":[],"endings":[]}}
      """;

    JdbcTemplate admin, db;
    String database;
    DriverManagerDataSource source;
    SqlSessionTemplate sql;
    GameSessionRuntimeMapper mapper;
    IGameSessionRuntimeService runtime;
    /** 代理背后的真实现:导演台 IT 在它上面补装 topicMapper / imService(本夹具其余用法不需要)。 */
    GameSessionRuntimeServiceImpl impl;

    void open(String suite) throws Exception {
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment(suite);
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        database = "cy_" + suite + "_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create database " + database + " character set utf8mb4");
        source = new DriverManagerDataSource(config.databaseUrl().replace("/" + config.databaseName() + "?",
                "/" + database + "?"), config.user(), config.password());
        db = new JdbcTemplate(source);
        try (Connection connection = source.getConnection()) {
            ItSchema.apply(connection, "it-schema/generic-route-runtime-it-schema.sql");
        }
        assertEquals(database, db.queryForObject("select database()", String.class));
        // 夹具里的 cms_registration 是 6 列桩表;这里换成生产 DDL,订单对账读的才是真列。
        db.execute("drop table cms_registration");
        String base = new String(Files.readAllBytes(Paths.get("../sql/base_schema_20260818.sql")), StandardCharsets.UTF_8);
        for (String table : BASE_TABLES) db.execute(statement(base, "CREATE TABLE `" + table + "` ("));
        for (String migration : List.of("sql/migration_20260820_questo_puzzle_atmosphere.sql",
                "sql/migration_theme_runtime_20261004.sql")) {
            String script = new String(Files.readAllBytes(Paths.get(migration)), StandardCharsets.UTF_8);
            db.execute(statement(script, "ALTER TABLE player_node_progress"));
        }

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/GameSessionRuntimeMapper.xml"),
                new ClassPathResource("mapper/business/PlayTeamMapper.xml"),
                new ClassPathResource("mapper/business/PlayerNodeProgressMapper.xml"));
        sql = new SqlSessionTemplate(factory.getObject());
        mapper = sql.getMapper(GameSessionRuntimeMapper.class);
        runtime = service();
    }

    void close() {
        if (admin != null && database != null) admin.execute("drop database " + database);
    }

    static String statement(String script, String head) {
        int start = script.indexOf(head);
        assertTrue(start >= 0, "真 DDL 里找不到:" + head);
        // 列注释里有分号(如 coop_order.invite_id),所以从表尾 ") ENGINE=" 之后找行末分号。
        int tail = head.startsWith("CREATE TABLE") ? script.indexOf("\n) ENGINE=", start) : start;
        return script.substring(start, script.indexOf(";\n", tail));
    }

    /** 两名已付费玩家各一队、三站(旧档案馆 / 旧钟楼 ACTIVE,旧集市 PAUSED)、RUNNING 局 revision=10。 */
    void seed() {
        for (String table : List.of("game_command_receipt", "game_player_node_outcome", "game_task_submission",
                "game_story_state", "game_team_role_assignment", "game_station_run", "game_session",
                "play_team_member", "play_team", "cms_activity")) db.update("delete from " + table);
        for (String table : LEDGERS) db.update("delete from " + table);
        db.update("insert into cms_activity(id,topic_id) values(?,?)", ACTIVITY, TOPIC);
        db.update("insert into play_team(id,owner_type,owner_id,leader_member_id,status) values(?,2,?,?,2),(?,2,?,?,2)",
                TEAM_A, ACTIVITY, PLAYER_A, TEAM_B, ACTIVITY, PLAYER_B);
        db.update("insert into play_team_member(team_id,member_id,role,status) values(?,?,1,1),(?,?,1,1)",
                TEAM_A, PLAYER_A, TEAM_B, PLAYER_B);
        db.update("insert into cms_registration(id,registration_no,owner_type,owner_id,ticket_id,ticket_price,member_id,"
                + "original_amount,total_amount,payable_amount,pay_expire_time,payment_status,registration_status,create_time)"
                + " values(501,'REG-IT-A',2,?,601,39.00,?,39.00,39.00,39.00,now(),2,2,now()),"
                + "(502,'REG-IT-B',2,?,601,39.00,?,39.00,39.00,39.00,now(),2,2,now())",
                ACTIVITY, PLAYER_A, ACTIVITY, PLAYER_B);
        db.update("insert into game_session(id,activity_id,topic_id,club_id,module_config_snapshot_json,status,"
                + "current_chapter_id,revision,prepared_by,started_by,started_at,create_time,update_time)"
                + " values(?,?,?,?,?,'RUNNING',1,10,?,?,now(),now(),now())",
                SESSION, ACTIVITY, TOPIC, CLUB, CONFIG, DIRECTOR, DIRECTOR);
        station(ARCHIVE_RUN, ARCHIVE_NODE, "旧档案馆", "ARCHIVE", ARCHIVE_MERCHANT, "ACTIVE");
        station(CLOCK_RUN, CLOCK_NODE, "旧钟楼", "CLOCK_TOWER", CLOCK_MERCHANT, "ACTIVE");
        station(MARKET_RUN, MARKET_NODE, "旧集市", "MARKET", MARKET_MERCHANT, "PAUSED");
        db.update("update game_station_run set pause_reason_code='STAFF_ABSENT',pause_reason='店员离岗' where id=?", MARKET_RUN);
        db.update("insert into game_team_role_assignment(game_session_id,team_id,member_id,role_code,role_name,status,"
                + "assigned_by,revision,create_time,update_time) values(?,?,?,'DETECTIVE','侦探','CONFIRMED',?,0,now(),now()),"
                + "(?,?,?,'DETECTIVE','侦探','CONFIRMED',?,0,now(),now())",
                SESSION, TEAM_A, PLAYER_A, DIRECTOR, SESSION, TEAM_B, PLAYER_B, DIRECTOR);
    }

    private void station(long id, long node, String name, String code, long merchant, String status) {
        db.update("insert into game_station_run(id,game_session_id,activity_id,node_id,node_name,station_code,"
                + "merchant_member_id,source_type,source_id,status,revision,service_start_at,service_end_at,create_time,update_time)"
                + " values(?,?,?,?,?,?,?,'CHAPTER_NODE',?,?,0,date_sub(now(),interval 1 hour),date_add(now(),interval 1 hour),now(),now())",
                id, SESSION, ACTIVITY, node, name, code, merchant, node, status);
    }

    /** 到店签到行:游戏局唯一读 player_node_progress 的地方就是"是否已到店"。 */
    void arrive(long member, long node) {
        db.update("insert into player_node_progress(member_id,activity_id,topic_id,node_id,status,checkin_time,del_flag,create_time)"
                + " values(?,?,?,?,1,now(),0,now())", member, ACTIVITY, TOPIC, node);
    }

    IGameSessionRuntimeService service() {
        GameSessionRuntimeServiceImpl target = new GameSessionRuntimeServiceImpl();
        impl = target;
        ReflectionTestUtils.setField(target, "runtimeMapper", mapper);
        ReflectionTestUtils.setField(target, "playTeamMapper", sql.getMapper(PlayTeamMapper.class));
        CmsActivityMapper activities = mock(CmsActivityMapper.class);
        org.mockito.stubbing.Answer<CmsActivity> activity = call -> {
            CmsActivity a = new CmsActivity();
            a.setId(ACTIVITY); a.setTopicId(TOPIC); a.setClubId(CLUB); a.setStatus(1L); a.setDelFlag(0);
            a.setStartDate(new Date(System.currentTimeMillis() - 3600000));
            a.setEndDate(new Date(System.currentTimeMillis() + 3600000));
            return a;
        };
        when(activities.selectCmsActivityById(ACTIVITY)).thenAnswer(activity);
        // 移植分支 A包当前资格先锁读活动行(CurrentPlayRegistration)，同 GameSessionIndependentRevisionMySqlIT。
        when(activities.selectCmsActivityByIdForUpdate(ACTIVITY)).thenAnswer(activity);
        ReflectionTestUtils.setField(target, "activityMapper", activities);
        com.chengyinhub.business.mapper.CmsRegistrationMapper currentRegistrations =
                mock(com.chengyinhub.business.mapper.CmsRegistrationMapper.class);
        CurrentRegistrationStubs.currentFromProbe(currentRegistrations); // 归属与已付事实仍由真表 play_team/hasPaidRegistration 判定
        ReflectionTestUtils.setField(target, "currentPlayRegistrationMapper", currentRegistrations);
        ICapabilityService capability = mock(ICapabilityService.class);
        when(capability.hasCapability(DIRECTOR, MemberCapability.CAP_CLUB_LEADER)).thenReturn(true);
        ReflectionTestUtils.setField(target, "capabilityService", capability);
        IClubMemberService clubs = mock(IClubMemberService.class);
        when(clubs.isTopicOwner(TOPIC, CLUB, DIRECTOR)).thenReturn(true);
        ReflectionTestUtils.setField(target, "clubMemberService", clubs);
        // 到店判定走真 PlayerNodeProgressServiceImpl + 真 PlayerNodeProgressMapper.xml。
        PlayerNodeProgressServiceImpl progress = new PlayerNodeProgressServiceImpl();
        ReflectionTestUtils.setField(progress, "playerNodeProgressMapper", sql.getMapper(PlayerNodeProgressMapper.class));
        ReflectionTestUtils.setField(target, "playerNodeProgressService", progress);
        ReflectionTestUtils.setField(target, "clock", (AdvancedGameClock) Date::new);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(IGameSessionRuntimeService.class);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        return (IGameSessionRuntimeService) proxy.getProxy();
    }

    int revision() {
        return db.queryForObject("select revision from game_session where id=?", Integer.class, SESSION);
    }

    GameSessionCommand command(String requestId, String action, long nodeId) {
        GameSessionCommand c = new GameSessionCommand();
        c.setActivityId(ACTIVITY);
        c.setNodeId(nodeId);
        c.setRequestId(requestId);
        c.setExpectedRevision(revision());
        c.setAction(action);
        return c;
    }

    GameCommandReceiptView submit(long member, long nodeId, String taskCode, String answer) {
        GameSessionCommand c = command("submit-" + member + "-" + nodeId, "PLAYER_SUBMIT", nodeId);
        c.getPayload().put("taskCode", taskCode);
        c.getPayload().put("evidenceUrls", List.of("text:" + answer));
        return runtime.command(GameActorContext.player(member), c);
    }

    long pendingSubmission(long member, long nodeId) {
        return db.queryForObject("select id from game_task_submission where game_session_id=? and member_id=? and node_id=?"
                + " and status='PENDING'", Long.class, SESSION, member, nodeId);
    }

    GameSessionCommand verify(String requestId, long nodeId, long submissionId, String decision) {
        GameSessionCommand c = command(requestId, "VERIFY_SUBMISSION", nodeId);
        c.getPayload().put("submissionId", submissionId);
        c.getPayload().put("decision", decision);
        if ("REJECT".equals(decision)) c.getPayload().put("reasonCode", "BLURRY");
        return c;
    }

    /** 与 ApiGameSessionController#command 相同:业务拒绝后再记一张 FAILED 回执。 */
    GameSessionException commandLikeController(GameActorContext actor, GameSessionCommand command) {
        try {
            runtime.command(actor, command);
        } catch (GameSessionException failure) {
            runtime.recordFailure(actor, command, failure);
            return failure;
        }
        throw new AssertionError("expected the command to be refused: " + command.getAction());
    }

    /** 表内容指纹:整表按 id 读出;对账靠"前后完全相等",不靠挑几列。 */
    Map<String, List<Map<String, Object>>> snapshot(List<String> tables) {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : tables) rows.put(table, db.queryForList("select * from " + table + " order by id"));
        return rows;
    }
}
