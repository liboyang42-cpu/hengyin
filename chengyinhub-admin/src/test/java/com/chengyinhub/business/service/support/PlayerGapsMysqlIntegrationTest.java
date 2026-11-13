package com.chengyinhub.business.service.support;

import com.chengyinhub.business.mapper.PlayerGapsMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Follow lists, route-action receipt and route-egg collection on real MySQL 8 with baseline DDL. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_GAPS_A_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlayerGapsMysqlIntegrationTest {
    private static final MysqlIntegrationTestConfig MYSQL = MysqlIntegrationTestConfig.fromEnvironment("player_gaps_it");
    private DataSource dataSource;
    private PlayerGapsService service;

    @BeforeAll
    void createDatabase() throws Exception {
        DriverManagerDataSource admin = ds(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
            s.execute("CREATE DATABASE " + MYSQL.databaseName() + " CHARACTER SET utf8mb4");
        }
        dataSource = ds(MYSQL.databaseUrl());
        Configuration configuration = new Configuration(new Environment("player-gaps-it", new SpringManagedTransactionFactory(), dataSource));
        configuration.addMapper(PlayerGapsMapper.class);
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("mapper/business/PlayerGapsMapper.xml")) {
            assertNotNull(in);
            new XMLMapperBuilder(in, configuration, "mapper/business/PlayerGapsMapper.xml", configuration.getSqlFragments()).parse();
        }
        service = new PlayerGapsService(new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration)).getMapper(PlayerGapsMapper.class));
    }

    @BeforeEach
    void reset() throws Exception {
        execute("DROP TABLE IF EXISTS ums_member", "DROP TABLE IF EXISTS ums_member_follow", "DROP TABLE IF EXISTS member_collectible",
                "DROP TABLE IF EXISTS npc_route_egg", "DROP TABLE IF EXISTS topic_route_action");
        String baseline = Files.readString(Paths.get("../sql/base_schema_20260818.sql"));
        execute(table(baseline, "ums_member"), table(baseline, "ums_member_follow"), table(baseline, "member_collectible"), table(baseline, "npc_route_egg"));
        String route = Files.readString(Paths.get("sql/migration_20260823_topic_route_graph.sql"));
        Matcher m = Pattern.compile("CREATE TABLE IF NOT EXISTS topic_route_action \\([\\s\\S]*?\\) ENGINE=InnoDB[^;]*;").matcher(route);
        assertTrue(m.find());
        execute(m.group());
        execute("INSERT INTO ums_member(id, nickname, avatar, role, status, del_flag) VALUES (1,'我','a1','player',1,0),(2,'阿二','a2','player',1,0),"
                        + "(3,'阿三','a3','player',1,0),(4,'注销','a4','player',1,2),(5,'阿五','a5','player',1,0)",
                // 1 follows 2, 3 (twice: legacy duplicate), 4 (deleted); 2 and 5 follow 1.
                "INSERT INTO ums_member_follow(member_id, follow_member_id) VALUES (1,2),(1,3),(1,3),(1,4),(2,1),(5,1)",
                "INSERT INTO npc_route_egg(id, topic_id, seq, text, status) VALUES (11, 900, 1, '审核过的彩蛋', 1), (12, 900, 2, '待审', 0)",
                "INSERT INTO topic_route_action(session_id, member_id, node_id, action_id, receipt_id, outcome_code, response_json, create_time)"
                        + " VALUES (77, 1, 5, 'act-1', 'r1', 'APPLIED', '{\"routeVersion\":3}', NOW(3))");
    }

    @Test
    void followListsAreOwnKeysetPagesWithoutDuplicatesOrDeletedMembers() {
        Map<String, Object> first = service.followList(1L, "following", "0", "1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) first.get("rows");
        assertEquals(1L, first.get("owner_id"));
        assertEquals(1, rows.size());
        assertEquals(2L, rows.get(0).get("id"));
        assertEquals(true, first.get("has_more"));
        assertEquals(2L, first.get("next_cursor"));
        Map<String, Object> second = service.followList(1L, "following", "2", "50");
        assertEquals(1, ((List<?>) second.get("rows")).size(), "duplicate follow row and deleted member collapse");
        assertEquals(false, second.get("has_more"));
        assertNull(second.get("next_cursor"));

        Map<String, Object> followers = service.followList(1L, "followers", "0", "50");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> f = (List<Map<String, Object>>) followers.get("rows");
        assertEquals(List.of(2L, 5L), f.stream().map(r -> r.get("id")).toList());
        assertEquals(true, f.get(0).get("isFollow"));
        assertEquals(false, f.get(1).get("isFollow"));
        assertThrows(ServiceException.class, () -> service.followList(1L, "friends", "0", "50"));
        assertThrows(ServiceException.class, () -> service.followList(1L, "following", "0", "51"));
    }

    @Test
    void routeActionReadsOnlyTheCallersOwnReceipt() {
        Map<String, Object> row = service.routeAction(1L, 77L, 1L, "act-1");
        assertEquals(77L, row.get("sessionId"));
        assertTrue(((String) row.get("responseJson")).contains("routeVersion"));
        assertEquals(403, assertThrows(ServiceException.class, () -> service.routeAction(2L, 77L, 1L, "act-1")).getCode());
        assertEquals(404, assertThrows(ServiceException.class, () -> service.routeAction(1L, 77L, 1L, "act-2")).getCode());
    }

    @Test
    void eggCollectionStoresReviewedTextOnceAndRefusesUnreviewed() throws Exception {
        service.collectEgg(1L, 11L, 900L);
        service.collectEgg(1L, 11L, 900L);
        assertEquals(1, count("SELECT COUNT(*) FROM member_collectible WHERE member_id = 1 AND coll_type = 2 AND ref_id = 11"));
        assertEquals(1, count("SELECT COUNT(*) FROM member_collectible WHERE content = '审核过的彩蛋'"));
        assertEquals(404, assertThrows(ServiceException.class, () -> service.collectEgg(1L, 12L, 900L)).getCode());
        assertEquals(400, assertThrows(ServiceException.class, () -> service.collectEgg(1L, 11L, 901L)).getCode());
    }

    private static String table(String baseline, String name) {
        Matcher m = Pattern.compile("CREATE TABLE `" + name + "` \\([\\s\\S]*?\\) ENGINE=InnoDB[^;]*;").matcher(baseline);
        assertTrue(m.find(), name);
        return m.group();
    }

    private long count(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void execute(String... sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String statement : sql) s.execute(statement);
        }
    }

    private DriverManagerDataSource ds(String url) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(url);
        ds.setUsername(MYSQL.user());
        ds.setPassword(MYSQL.password());
        return ds;
    }
}
