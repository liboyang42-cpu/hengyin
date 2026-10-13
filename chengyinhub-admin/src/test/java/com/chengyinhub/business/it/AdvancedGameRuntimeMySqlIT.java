package com.chengyinhub.business.it;

import org.junit.jupiter.api.Test;
import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import java.io.InputStream;
import java.util.Date;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 高级玩法配置与运行会话必须在真实 MySQL JSON/唯一键语义上成立。 */
class AdvancedGameRuntimeMySqlIT {

    @Test
    void migrationCreatesJsonColumnsRuntimeStateAndOwnerUniqueness() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                MybatisItDatabase.url("advanced_game_runtime"),
                MybatisItDatabase.user(), MybatisItDatabase.password())) {
            createTemplateTables(connection);
            ItSchema.applyMigrations(connection, "sql/migration_20260822_advanced_game_runtime.sql");
            execute(connection, "ALTER TABLE game_runtime_session ADD play_instance_id VARCHAR(64) NOT NULL DEFAULT 'default'");
            execute(connection, "CREATE TABLE topic_route_session (id BIGINT PRIMARY KEY, member_id BIGINT, "
                    + "topic_id BIGINT, activity_id BIGINT, play_instance_id VARCHAR(64)) ENGINE=InnoDB");

            assertEquals("json", scalar(connection,
                    "SELECT DATA_TYPE FROM information_schema.columns WHERE table_schema=DATABASE() "
                            + "AND table_name='cms_member_template' AND column_name='advanced_config_json'"));
            assertEquals("json", scalar(connection,
                    "SELECT DATA_TYPE FROM information_schema.columns WHERE table_schema=DATABASE() "
                            + "AND table_name='cms_template_library' AND column_name='advanced_config_json'"));
            assertEquals("json", scalar(connection,
                    "SELECT DATA_TYPE FROM information_schema.columns WHERE table_schema=DATABASE() "
                            + "AND table_name='game_runtime_session' AND column_name='state_json'"));

            assertThrows(SQLException.class, () -> execute(connection,
                    "INSERT INTO cms_member_template(title,advanced_config_json) VALUES ('bad','')"),
                    "负控：JSON 列必须真的拒绝空串");

            execute(connection, "INSERT INTO game_runtime_session(activity_id,topic_id,node_id,template_id,"
                    + "owner_type,owner_id,config_snapshot_json,state_json,status,version,started_at,create_time,update_time) "
                    + "VALUES (9,19,29,39,1,49,'{\"schemaVersion\":1}','{}',0,0,NOW(),NOW(),NOW())");
            assertThrows(SQLException.class, () -> execute(connection,
                    "INSERT INTO game_runtime_session(activity_id,topic_id,node_id,template_id,"
                            + "owner_type,owner_id,config_snapshot_json,state_json,status,version,started_at,create_time,update_time) "
                            + "VALUES (9,19,29,39,1,49,'{\"schemaVersion\":1}','{}',0,0,NOW(),NOW(),NOW())"),
                    "同一场次/节点/所有者只能有一条权威会话");

            execute(connection, "UPDATE game_runtime_session SET status=2 WHERE owner_id=49");
            Configuration config = new Configuration();
            String resource = "mapper/business/GameRuntimeSessionMapper.xml";
            try (InputStream xml = getClass().getClassLoader().getResourceAsStream(resource)) {
                new XMLMapperBuilder(xml, config, resource, config.getSqlFragments()).parse();
            }
            try (SqlSession sql = new SqlSessionFactoryBuilder().build(config).openSession(connection)) {
                GameRuntimeSessionMapper mapper = sql.getMapper(GameRuntimeSessionMapper.class);
                GameRuntimeSession attempt = mapper.selectByOwner(9L, 19L, 29L, 1, 49L);
                String frozen = attempt.getConfigSnapshotJson();
                // 即使调用方误带新规则，重试 SQL 也不能改写本局模板与配置。
                attempt.setTemplateId(999L);
                attempt.setConfigSnapshotJson("{\"schemaVersion\":1,\"changed\":true}");
                attempt.setStateJson("{\"retry\":true}");
                attempt.setStartedAt(new Date());
                attempt.setUpdateTime(new Date());
                assertEquals(1, mapper.restartTerminalCas(attempt));
                assertEquals(0, mapper.restartTerminalCas(attempt), "旧版本重试不能覆盖新尝试");
                sql.clearCache();
                GameRuntimeSession stored = mapper.selectByOwner(9L, 19L, 29L, 1, 49L);
                assertEquals(39L, stored.getTemplateId());
                assertEquals(frozen, stored.getConfigSnapshotJson());
                assertEquals(1, stored.getVersion());
                assertEquals(0, stored.getStatus());
            }
        }
    }

    private static void createTemplateTables(Connection connection) throws SQLException {
        execute(connection, "CREATE TABLE cms_member_template (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "title VARCHAR(255), preference_json JSON NULL) ENGINE=InnoDB");
        execute(connection, "CREATE TABLE cms_template_library (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "title VARCHAR(255), preference_json JSON NULL) ENGINE=InnoDB");
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
