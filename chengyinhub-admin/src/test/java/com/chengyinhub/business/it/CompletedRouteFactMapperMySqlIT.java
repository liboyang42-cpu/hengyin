package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.CompletedRouteFactMapper;
import com.chengyinhub.business.service.support.CompletedRouteFactReader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G:真实迁移 + MyBatis + MySQL 查询。只在已批准 CI/VPS 的显式配置回环测试库运行。
 * 不使用 H2、Assumptions 或跳过；没有配置时失败，不擅自使用本机默认数据库。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompletedRouteFactMapperMySqlIT {
    private JdbcTemplate db;
    private CompletedRouteFactMapper mapper;
    private CompletedRouteFactReader reader;

    @BeforeAll
    void schema() throws Exception {
        String host = System.getenv("CHENGYIN_MYBATIS_IT_HOST");
        assertNotNull(host, "必须由已批准测试环境显式配置 MySQL host");
        assertTrue("127.0.0.1".equals(host) || "localhost".equals(host), "只允许隔离的回环测试实例");
        String suite = "g_completed_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        DriverManagerDataSource source = new DriverManagerDataSource(MybatisItDatabase.url(suite),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        db = new JdbcTemplate(source);
        db.execute("CREATE TABLE cms_topic(id BIGINT PRIMARY KEY,complete_rule_json JSON NULL)");
        try (Connection connection = source.getConnection()) {
            ItSchema.applyMigrations(connection, "sql/migration_20260823_topic_route_graph.sql");
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mapper/business/CompletedRouteFactMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(CompletedRouteFactMapper.class);
        reader = new CompletedRouteFactReader(mapper);

        insert(1, 7, 500, 300, "default", 1, 3);
        insert(2, 7, 500, 300, "replay:1", 0, 3);
        insert(3, 7, 501, 301, "default", 1, 8);
        insert(4, 7, 0, 302, "default", 1, 9);
        insert(5, 7, 502, 303, "default", 2, 4);
        insert(6, 7, 503, 304, "default", 0, 4);
        insert(7, 7, 504, 305, "default", 1, 4);
        insert(8, 7, 504, 305, "replay:7", 2, 4);
        insert(9, 7, 600, 302, "default", 1, 9);
        insert(99, 8, 501, 301, "default", 1, 8);
    }

    private void insert(long id, long member, long activity, long topic, String instance, int status, long config) {
        db.update("INSERT INTO topic_route_session(id,member_id,activity_id,topic_id,play_instance_id,"
                + "config_version,config_snapshot_json,state_json,status,seed,version,completed_at,create_time,update_time)"
                + " VALUES(?,?,?,?,?,?,JSON_OBJECT(),JSON_OBJECT(),?,1,0,"
                + "CASE WHEN ?=1 THEN '2026-10-01 11:00:00' ELSE NULL END,"
                + "'2026-10-01 10:00:00','2026-10-01 11:00:00')",
                id, member, activity, topic, instance, config, status, status);
    }

    @Test
    void currentCompletedSetExcludesOldSuccessRunningEndedAndOtherMembers() {
        List<TopicRouteSession> rows = reader.findCurrentCompletedRoutes(7L);

        assertEquals(List.of(3L, 4L, 9L), rows.stream().map(TopicRouteSession::getId).toList(),
                "必须先取最新局再筛完成；他人同scope的更大id不覆盖本人");
        assertTrue(rows.stream().allMatch(row -> row.getMemberId() == 7L));
        assertEquals(List.of(0L, 600L), rows.stream().filter(row -> row.getTopicId() == 302L)
                .map(TopicRouteSession::getActivityId).toList());
        assertEquals(List.of(99L), reader.findCurrentCompletedRoutes(8L).stream()
                .map(TopicRouteSession::getId).toList());
    }

    @Test
    void zeroNodeRowsStillDiscoverRouteCompletionAndPreserveFrozenVersion() {
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name='player_node_progress'", Integer.class).intValue());
        TopicRouteSession row = reader.findCurrentCompletedRoutes(7L).get(0);

        assertEquals(3L, row.getId().longValue());
        assertEquals(8L, row.getConfigVersion().longValue());
        assertEquals("default", row.getPlayInstanceId());
        assertEquals(TopicRouteSession.STATUS_COMPLETED, row.getStatus().intValue());
        assertNotNull(row.getCompletedAt());
    }

    @Test
    void missingMemberNeverFallsBackToAllOwners() {
        assertTrue(mapper.selectCurrentCompletedRoutes(null).isEmpty());
        assertTrue(mapper.selectCurrentCompletedRoutes(0L).isEmpty());
        assertTrue(reader.findCurrentCompletedRoutes(9L).isEmpty());
    }

    @Test
    void repeatedReadLeavesSessionsAndSourceReceiptsUnchanged() {
        List<Map<String, Object>> before = db.queryForList("SELECT id,status,version,"
                + "CAST(state_json AS CHAR) AS state_json FROM topic_route_session ORDER BY id");
        Integer receipts = db.queryForObject("SELECT COUNT(*) FROM topic_route_action", Integer.class);

        assertEquals(reader.findCurrentCompletedRoutes(7L).stream().map(TopicRouteSession::getId).toList(),
                reader.findCurrentCompletedRoutes(7L).stream().map(TopicRouteSession::getId).toList());

        assertEquals(before, db.queryForList("SELECT id,status,version,"
                + "CAST(state_json AS CHAR) AS state_json FROM topic_route_session ORDER BY id"));
        assertEquals(receipts, db.queryForObject("SELECT COUNT(*) FROM topic_route_action", Integer.class));
    }
}
