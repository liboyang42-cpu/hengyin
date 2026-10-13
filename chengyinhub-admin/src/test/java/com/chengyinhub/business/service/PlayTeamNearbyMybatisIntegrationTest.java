package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.vo.PlayTeamNearbyVO;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.impl.PlayTeamServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 地图附近公开队伍:真 MySQL 跑 CmsTopicMapper.selectNearbyPublicTeams(与公开主题列表共用圈子闸与俱乐部私密闸片段)。
 * 钉住 5 条规则、坐标降级、主题可见性闸、拉黑、以及响应里绝不出现邀请码。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlayTeamNearbyMybatisIntegrationTest {
    private static final long VIEWER = 500L;
    private static final double LAT = 31.2300000;
    private static final double LNG = 121.4700000;

    private final MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.fromEnvironment("play_team_nearby_it");
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private IPlayTeamService service;
    private long nextId = 1;

    @BeforeAll void setup() throws Exception {
        new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()))
                .execute("CREATE DATABASE " + mysql.databaseName());
        dataSource = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        jdbc = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/migration_20260820_play_team_core.sql"));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/migration_20260822_play_team_invite_code.sql"));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/migration_20260915_play_team_public_apply.sql"));
        }
        String base = new String(Files.readAllBytes(Paths.get("../sql/base_schema_20260818.sql")), StandardCharsets.UTF_8);
        for (String table : Arrays.asList("cms_registration", "audit_task", "cms_activity", "oms_ticket", "cms_topic",
                "cms_topic_node", "cms_topic_chapter", "merchant_chapter_offer", "club", "club_member", "im_block", "ums_member")) {
            int start = base.indexOf("CREATE TABLE `" + table + "` (");
            assertTrue(start >= 0, table);
            // 注释里可能有半角分号(cms_activity.club_id),以表选项行行尾的分号为准。
            jdbc.execute(base.substring(start, base.indexOf(";\n", base.indexOf(") ENGINE=", start))));
        }
        // 基线快照之后的迁移补列(与生产同名同型),以及基线里没有的圈层供给表。
        jdbc.execute("ALTER TABLE cms_topic ADD COLUMN circle_theme_code VARCHAR(24) NULL, ADD COLUMN circle_reviewed_at DATETIME NULL,"
                + " ADD COLUMN club_member_only TINYINT(1) NOT NULL DEFAULT 0");
        jdbc.execute("ALTER TABLE cms_topic_chapter ADD COLUMN circle_enabled TINYINT NOT NULL DEFAULT 1");
        jdbc.execute("CREATE TABLE merchant_circle_supply_profile (id BIGINT PRIMARY KEY, offer_id BIGINT, is_current TINYINT, checked_at DATETIME)");

        Configuration configuration = new Configuration(new Environment("nearby", new SpringManagedTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAlias("CmsTopic", CmsTopic.class);
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("mapper/business/CmsTopicMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "mapper/business/CmsTopicMapper.xml", configuration.getSqlFragments()).parse();
        }
        CmsTopicMapper mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration)).getMapper(CmsTopicMapper.class);
        PlayTeamServiceImpl target = new PlayTeamServiceImpl();
        ReflectionTestUtils.setField(target, "cmsTopicMapper", mapper);
        service = target;
    }

    @AfterAll void cleanup() {
        if (dataSource == null) return;
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()));
        admin.execute("DROP DATABASE " + mysql.databaseName());
        assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
                Integer.class, mysql.databaseName()));
    }

    @BeforeEach void reset() {
        for (String table : Arrays.asList("play_team_member", "play_team", "cms_registration", "audit_task", "cms_activity",
                "oms_ticket", "cms_topic", "cms_topic_node", "club", "club_member", "im_block", "ums_member")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("INSERT INTO ums_member (id, nickname, avatar) VALUES (1, '小周', 'https://img/leader.jpg'), (2, '阿杰', 'https://img/2.jpg')");
    }

    @Test void coordinatesFallBackFromGatherPointToActivityToFirstTopicNodeAndTeamsWithoutAnyAreDropped() throws Exception {
        long gather = team(topic(), activity(null, null), 4);
        jdbc.update("INSERT INTO oms_ticket (id, owner_type, owner_id, gather_lat, gather_lng, meeting_point, del_flag) VALUES (?, 2, ?, 31.2310000, 121.4710000, '外滩源集合', 0)",
                nextId++, activityOf(gather));
        long byActivity = team(topic(), activity("31.2320000", "121.4720000"), 4);
        long topicWithNode = topic();
        jdbc.update("INSERT INTO cms_topic_node (id, topic_id, sort_id, latitude, longitude) VALUES (?, ?, 2, '31.2400000', '121.4800000'), (?, ?, 1, '31.2330000', '121.4730000'), (?, ?, 0, '', '')",
                nextId++, topicWithNode, nextId++, topicWithNode, nextId++, topicWithNode);
        long byNode = team(topicWithNode, activity("", ""), 4);
        long noCoordinate = team(topic(), activity(null, null), 4);
        jdbc.update("UPDATE play_team_member SET status = 1 WHERE team_id = ?", gather);
        jdbc.update("INSERT INTO play_team_member (team_id, member_id, role, status) VALUES (?, 2, 0, 1)", gather);
        jdbc.update("UPDATE play_team SET joined_count = 2 WHERE id = ?", gather);

        Map<Long, PlayTeamNearbyVO> seen = nearby();
        assertEquals("GATHER", seen.get(gather).getCoordSource());
        assertEquals("外滩源集合", seen.get(gather).getAddressName());
        assertEquals(0, new java.math.BigDecimal("31.2310000").compareTo(seen.get(gather).getLatitude()));
        assertEquals("ACTIVITY", seen.get(byActivity).getCoordSource());
        assertEquals("TOPIC_NODE", seen.get(byNode).getCoordSource());
        assertEquals(0, new java.math.BigDecimal("31.2330000").compareTo(seen.get(byNode).getLatitude()), "取 sort_id 最小且有坐标的节点");
        assertFalse(seen.containsKey(noCoordinate), "三级坐标都没有 ⇒ 不出点");
        assertEquals(Arrays.asList("https://img/leader.jpg", "https://img/2.jpg"), seen.get(gather).getMemberAvatars());

        String json = new ObjectMapper().writeValueAsString(service.nearby(VIEWER, LAT, LNG, 5000));
        assertFalse(json.toLowerCase().contains("invite"), json);
        for (String code : jdbc.queryForList("SELECT invite_code FROM play_team", String.class)) {
            assertFalse(json.contains(code), "邀请码不得出现在响应里");
        }
        assertFalse(json.contains("memberAvatarsJoined"), json);
        assertFalse(json.contains("leaderMemberId") || json.contains("ownerType"), json);
    }

    @Test void onlyRecruitingPublicNotFullNotStartedUnreviewedTeamsAppear() {
        long visible = team(topic(), activity("31.2310000", "121.4710000"), 4);
        long inviteOnly = team(topic(), activity("31.2310000", "121.4710000"), 4);
        jdbc.update("UPDATE play_team SET join_mode = 1 WHERE id = ?", inviteOnly);
        long full = team(topic(), activity("31.2310000", "121.4710000"), 2);
        jdbc.update("UPDATE play_team SET joined_count = 2 WHERE id = ?", full);
        long locked = teamWithStatus(1);
        long started = teamWithStatus(2);
        long ended = teamWithStatus(3);
        long disbanded = teamWithStatus(4);
        long sessionStarted = team(topic(), activity("31.2310000", "121.4710000"), 4);
        jdbc.update("UPDATE cms_activity SET start_date = ? WHERE id = ?", new Date(System.currentTimeMillis() - 60000), activityOf(sessionStarted));
        long cancelled = team(topic(), activity("31.2310000", "121.4710000"), 4);
        jdbc.update("UPDATE cms_activity SET cancel_time = NOW() WHERE id = ?", activityOf(cancelled));
        long unpublished = team(topic(), activity("31.2310000", "121.4710000"), 4);
        jdbc.update("UPDATE cms_activity SET publish_status = 0 WHERE id = ?", activityOf(unpublished));
        long pendingReview = team(topic(), activity("31.2310000", "121.4710000"), 4);
        report(pendingReview, 0);
        long escalatedReview = team(topic(), activity("31.2310000", "121.4710000"), 4);
        report(escalatedReview, 3);
        long reviewClosed = team(topic(), activity("31.2310000", "121.4710000"), 4);
        report(reviewClosed, 1);
        long blocked = team(topic(), activity("31.2310000", "121.4710000"), 4, 9L);
        jdbc.update("INSERT INTO im_block (member_id, blocked_member_id) VALUES (9, ?)", VIEWER);
        long farAway = team(topic(), activity("31.4000000", "121.4710000"), 4);

        Map<Long, PlayTeamNearbyVO> seen = nearby();
        assertTrue(seen.containsKey(visible));
        assertTrue(seen.containsKey(reviewClosed), "审核单结束后恢复显示");
        for (long hidden : new long[]{inviteOnly, full, locked, started, ended, disbanded, sessionStarted, cancelled,
                unpublished, pendingReview, escalatedReview, blocked, farAway}) {
            assertFalse(seen.containsKey(hidden), "不应出现 teamId=" + hidden);
        }
        assertEquals(2, seen.size());
    }

    @Test void topicVisibilityGatesHideClubPrivateAndUnreviewedCircleTopics() {
        jdbc.update("INSERT INTO club (id, member_id, name, del_flag) VALUES (70, 1, '私密俱乐部', 0), (71, 1, '已解散', 2)");
        long clubPrivate = topic();
        jdbc.update("UPDATE cms_topic SET club_id = 70, club_member_only = 1 WHERE id = ?", clubPrivate);
        long privateTeam = team(clubPrivate, activity("31.2310000", "121.4710000"), 4);
        long deadClub = topic();
        jdbc.update("UPDATE cms_topic SET club_id = 71 WHERE id = ?", deadClub);
        long deadClubTeam = team(deadClub, activity("31.2310000", "121.4710000"), 4);
        long circle = topic();
        jdbc.update("UPDATE cms_topic SET circle_theme_code = 'DATE', circle_reviewed_at = NULL WHERE id = ?", circle);
        long circleTeam = team(circle, activity("31.2310000", "121.4710000"), 4);
        long offline = topic();
        jdbc.update("UPDATE cms_topic SET user_status = 0 WHERE id = ?", offline);
        long offlineTeam = team(offline, activity("31.2310000", "121.4710000"), 4);

        Map<Long, PlayTeamNearbyVO> seen = nearby();
        assertFalse(seen.containsKey(privateTeam), "俱乐部私密主题的队伍不能对非成员出现(9-11 日报 P1)");
        assertFalse(seen.containsKey(deadClubTeam));
        assertFalse(seen.containsKey(circleTeam), "未复核的圈层主题不公开");
        assertFalse(seen.containsKey(offlineTeam));

        jdbc.update("INSERT INTO club_member (club_id, member_id, status, del_flag) VALUES (70, ?, 1, 0)", VIEWER);
        assertTrue(nearby().containsKey(privateTeam), "俱乐部成员可见");
    }

    @Test void viewerRelationTicketAndLeaderOnlyPendingCount() {
        long topicId = topic();
        long activityId = activity("31.2310000", "121.4710000");
        long team = team(topicId, activityId, 4);
        Date later = new Date(System.currentTimeMillis() + 3600000);
        jdbc.update("INSERT INTO play_team_member (team_id, member_id, role, status, apply_expire_time) VALUES (?, ?, 0, 0, ?), (?, 3, 0, 0, ?)",
                team, VIEWER, later, team, new Date(System.currentTimeMillis() - 1000));
        PlayTeamNearbyVO asApplicant = nearby().get(team);
        assertEquals("PENDING", asApplicant.getViewerStatus());
        assertFalse(asApplicant.getViewerHasTicket());
        assertNull(asApplicant.getPendingCount(), "非队长看不到申请数");

        jdbc.update("INSERT INTO cms_registration (registration_no, owner_type, owner_id, ticket_id, ticket_price, member_id, original_amount, total_amount, payable_amount, payment_status, registration_status, pay_expire_time) VALUES ('t1', 2, ?, 1, 0, ?, 0, 0, 0, 2, 2, NOW())",
                activityId, VIEWER);
        jdbc.update("UPDATE play_team_member SET status = 4 WHERE member_id = ?", VIEWER);
        PlayTeamNearbyVO rejected = nearby().get(team);
        assertEquals("REJECTED", rejected.getViewerStatus());
        assertTrue(rejected.getViewerHasTicket());

        jdbc.update("UPDATE play_team_member SET status = 5 WHERE member_id = ?", VIEWER);
        assertEquals("NONE", nearby().get(team).getViewerStatus());

        jdbc.update("UPDATE play_team SET leader_member_id = ? WHERE id = ?", VIEWER, team);
        jdbc.update("UPDATE play_team_member SET status = 0, apply_expire_time = ? WHERE member_id = 3", later);
        PlayTeamNearbyVO asLeader = nearby().get(team);
        assertEquals("LEADER", asLeader.getViewerStatus());
        assertEquals(1, asLeader.getPendingCount());
    }

    private Map<Long, PlayTeamNearbyVO> nearby() {
        List<PlayTeamNearbyVO> list = service.nearby(VIEWER, LAT, LNG, 5000);
        return list.stream().collect(Collectors.toMap(PlayTeamNearbyVO::getTeamId, Function.identity()));
    }

    private long topic() {
        long id = nextId++;
        jdbc.update("INSERT INTO cms_topic (id, name, product_type, status, user_status, del_flag, is_template) VALUES (?, '外滩夜行路线', 1, 1, 1, 0, 0)", id);
        return id;
    }

    private long activity(String lat, String lng) {
        long id = nextId++;
        jdbc.update("INSERT INTO cms_activity (id, topic_id, name, status, publish_status, del_flag, start_date, end_date, latitude, longitude, address_name) VALUES (?, NULL, '周五 19:30 场', 1, 1, 0, ?, ?, ?, ?, '外滩')",
                id, new Date(System.currentTimeMillis() + 86400000), new Date(System.currentTimeMillis() + 90000000), lat, lng);
        return id;
    }

    private long team(long topicId, long activityId, int cap) {
        return team(topicId, activityId, cap, 1L);
    }

    private long team(long topicId, long activityId, int cap, long leader) {
        jdbc.update("UPDATE cms_activity SET topic_id = ? WHERE id = ?", topicId, activityId);
        long id = nextId++;
        jdbc.update("INSERT INTO play_team (id, owner_type, owner_id, leader_member_id, invite_code, title, join_mode, max_members, joined_count, status, expire_time) VALUES (?, 2, ?, ?, ?, '搭子', 2, ?, 1, 0, ?)",
                id, activityId, leader, "INV" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 20), cap,
                new Date(System.currentTimeMillis() + 86400000));
        jdbc.update("INSERT INTO play_team_member (team_id, member_id, role, status) VALUES (?, ?, 1, 1)", id, leader);
        return id;
    }

    private long teamWithStatus(int status) {
        long id = team(topic(), activity("31.2310000", "121.4710000"), 4);
        jdbc.update("UPDATE play_team SET status = ? WHERE id = ?", status, id);
        return id;
    }

    private long activityOf(long teamId) {
        return jdbc.queryForObject("SELECT owner_id FROM play_team WHERE id = ?", Long.class, teamId);
    }

    private void report(long teamId, int status) {
        jdbc.update("INSERT INTO audit_task (biz_type, biz_id, status, submitted_at) VALUES ('TEAM', ?, ?, NOW())", teamId, status);
    }
}
