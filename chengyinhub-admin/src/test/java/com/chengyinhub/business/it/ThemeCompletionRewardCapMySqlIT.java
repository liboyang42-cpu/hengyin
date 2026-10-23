package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.service.IAdvancedGameRuntimeService;
import com.chengyinhub.business.service.impl.AdvancedGameRuntimeServiceImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #478-3 主题通关奖封顶的判据在真 MySQL 上成立:读的是 game_runtime_session 里冻结的配置快照与本局 state_json
 * (JSON 列),按「本人、本场、本主题、已完成节点」逐站判。任一站靠设备自报 / 拍照兜底 / 静态码完成 ⇒ 封顶。
 */
class ThemeCompletionRewardCapMySqlIT {

    private static final long MEMBER = 7L;
    private static final long OTHER = 8L;
    private static final long ACTIVITY = 9L;
    private static final long TOPIC = 19L;

    @Test
    void anyCompletedSelfReportedNodeCapsTheThemeBonusReadFromRealSessionJson() throws Exception {
        try (Connection connection = DriverManager.getConnection(MybatisItDatabase.url("theme_completion_cap"),
                MybatisItDatabase.user(), MybatisItDatabase.password())) {
            execute(connection, "CREATE TABLE topic_route_session (id BIGINT PRIMARY KEY, member_id BIGINT, "
                    + "topic_id BIGINT, activity_id BIGINT, play_instance_id VARCHAR(64)) ENGINE=InnoDB");
            execute(connection, "CREATE TABLE cms_member_template (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "title VARCHAR(255), preference_json JSON NULL) ENGINE=InnoDB");
            execute(connection, "CREATE TABLE cms_template_library (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "title VARCHAR(255), preference_json JSON NULL) ENGINE=InnoDB");
            ItSchema.applyMigrations(connection, "sql/migration_20260822_advanced_game_runtime.sql");
            execute(connection, "ALTER TABLE game_runtime_session ADD play_instance_id VARCHAR(64) NOT NULL DEFAULT 'default'");
            // 本人没有个人局时回落查团局(selectTeamByMember),团成员表按真 SQL 参与连接
            execute(connection, "CREATE TABLE play_team_member (team_id BIGINT, member_id BIGINT, status INT) ENGINE=InnoDB");

            // 29 = 估值(服务端可判);30 = 反应(设备自报);31 = 拍照,视觉当场 PASS;32 = 拍照,兜底放行;
            // 33 = 本人没玩、只有别人的自报局。全部 status=1(已完成)。
            insert(connection, 29, MEMBER, "{\"schemaVersion\":1,\"estimate\":{\"enabled\":true}}", "{}");
            insert(connection, 30, MEMBER, "{\"schemaVersion\":1,\"reaction\":{\"enabled\":true}}", "{}");
            insert(connection, 31, MEMBER, "{\"schemaVersion\":1,\"photoCheck\":{\"enabled\":true}}",
                    "{\"photoCheck\":{\"verdict\":\"PASS\"}}");
            insert(connection, 32, MEMBER, "{\"schemaVersion\":1,\"photoCheck\":{\"enabled\":true}}",
                    "{\"photoCheck\":{\"verdict\":\"FALLBACK_PASS\"}}");
            insert(connection, 33, OTHER, "{\"schemaVersion\":1,\"compass\":{\"enabled\":true}}", "{}");

            Configuration config = new Configuration();
            String resource = "mapper/business/GameRuntimeSessionMapper.xml";
            try (InputStream xml = getClass().getClassLoader().getResourceAsStream(resource)) {
                new XMLMapperBuilder(xml, config, resource, config.getSqlFragments()).parse();
            }
            try (SqlSession sql = new SqlSessionFactoryBuilder().build(config).openSession(connection)) {
                AdvancedGameRuntimeServiceImpl target = new AdvancedGameRuntimeServiceImpl();
                ReflectionTestUtils.setField(target, "sessionMapper", sql.getMapper(GameRuntimeSessionMapper.class));
                CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
                for (long id = 29; id <= 34; id++) {
                    CmsTopicNode node = new CmsTopicNode();
                    node.setId(id); node.setTopicId(TOPIC);
                    when(nodes.selectCmsTopicNodeById(id)).thenReturn(node);
                }
                ReflectionTestUtils.setField(target, "nodeMapper", nodes);
                IAdvancedGameRuntimeService service = target;

                assertFalse(service.anyNodeRewardCappedToXpBadge(MEMBER, ACTIVITY, TOPIC, Arrays.asList(29L, 31L, 34L)),
                        "服务端可判 + 视觉真 PASS + 没有高级玩法的站 ⇒ 通关奖不封顶");
                assertTrue(service.anyNodeRewardCappedToXpBadge(MEMBER, ACTIVITY, TOPIC, Arrays.asList(29L, 30L)),
                        "任一站设备自报 ⇒ 通关奖封顶");
                assertTrue(service.anyNodeRewardCappedToXpBadge(MEMBER, ACTIVITY, TOPIC, Arrays.asList(31L, 32L)),
                        "拍照兜底放行 ⇒ 通关奖封顶");
                assertFalse(service.anyNodeRewardCappedToXpBadge(MEMBER, ACTIVITY, TOPIC, Collections.singletonList(33L)),
                        "别人的自报局不能封本人的通关奖");
                assertTrue(service.anyNodeRewardCappedToXpBadge(OTHER, ACTIVITY, TOPIC, Collections.singletonList(33L)));
                assertFalse(service.anyNodeRewardCappedToXpBadge(MEMBER, ACTIVITY + 1, TOPIC, Arrays.asList(30L, 32L)),
                        "别的场次的局不算本场的完成证据");

                // 视觉复核把兜底改判为 PASS 后(真 JSON 列回读),同一组节点不再封顶
                execute(connection, "UPDATE game_runtime_session SET state_json=JSON_SET(state_json,'$.photoCheck.verdict','PASS') "
                        + "WHERE node_id=32");
                sql.clearCache();
                assertFalse(service.anyNodeRewardCappedToXpBadge(MEMBER, ACTIVITY, TOPIC, Arrays.asList(31L, 32L)));
            }
        }
    }

    private static void insert(Connection connection, long nodeId, long ownerId, String configJson, String stateJson)
            throws SQLException {
        execute(connection, "INSERT INTO game_runtime_session(activity_id,topic_id,node_id,template_id,owner_type,owner_id,"
                + "config_snapshot_json,state_json,status,version,started_at,completed_at,create_time,update_time) VALUES ("
                + ACTIVITY + "," + TOPIC + "," + nodeId + ",39,1," + ownerId + ",'" + configJson + "','" + stateJson
                + "',1,2,NOW(),NOW(),NOW(),NOW())");
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
