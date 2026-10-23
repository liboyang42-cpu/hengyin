package com.chengyinhub.business.it;

import org.junit.jupiter.api.Test;
import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.IAdvancedGameRuntimeService;
import com.chengyinhub.business.service.impl.AdvancedGameRuntimeServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import java.io.InputStream;
import java.util.Date;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

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

    @Test
    void zeroChallengeHistoryRequiresExactCurrentRunReceiptOrFrozenOrigin() throws Exception {
        try (Connection connection = DriverManager.getConnection(MybatisItDatabase.url("advanced_zero_origin"),
                MybatisItDatabase.user(), MybatisItDatabase.password())) {
            createTemplateTables(connection);
            ItSchema.applyMigrations(connection, "sql/migration_20260822_advanced_game_runtime.sql");
            execute(connection, "ALTER TABLE game_runtime_session ADD play_instance_id VARCHAR(64) NOT NULL DEFAULT 'default',"
                    + " DROP INDEX uk_runtime_owner,ADD UNIQUE KEY uk_runtime_owner(activity_id,topic_id,node_id,owner_type,owner_id,play_instance_id)");
            execute(connection, "CREATE TABLE topic_route_action(id BIGINT PRIMARY KEY,session_id BIGINT,member_id BIGINT,node_id BIGINT,outcome_code VARCHAR(32),response_json JSON) ENGINE=InnoDB");
            execute(connection, "INSERT INTO topic_route_session(id,activity_id,topic_id,member_id,play_instance_id) VALUES(88,9,19,49,'route:88'),(89,9,19,49,'route:89')");
            execute(connection, "INSERT INTO topic_route_action VALUES(1,88,49,29,'CHALLENGE','{\"instanceId\":\"old\"}')");
            execute(connection, "INSERT INTO game_runtime_session(id,play_instance_id,activity_id,topic_id,node_id,template_id,owner_type,owner_id,config_snapshot_json,state_json,status,version,started_at,create_time,update_time) VALUES"
                    + "(51,'challenge:old',9,19,29,39,1,49,'{}','{\"zeroChallengeInstance\":\"old\"}',2,3,NOW(),NOW(),NOW()),"
                    + "(52,'game:50:challenge:old',9,19,29,39,2,88,'{}','{\"zeroChallengeInstance\":\"old\",\"runtimeAuthorityEpoch\":3}',2,3,NOW(),NOW(),NOW())");
            Configuration config = new Configuration();
            String resource = "mapper/business/GameRuntimeSessionMapper.xml";
            try (InputStream xml = getClass().getClassLoader().getResourceAsStream(resource)) {
                new XMLMapperBuilder(xml, config, resource, config.getSqlFragments()).parse();
            }
            try (SqlSession sql = new SqlSessionFactoryBuilder().build(config).openSession(connection)) {
                GameRuntimeSessionMapper mapper = sql.getMapper(GameRuntimeSessionMapper.class);
                assertEquals(51L, mapper.selectLatestZeroChallenge(9L,19L,29L,1,49L,null,88L,null).getId());
                assertEquals(null, mapper.selectLatestZeroChallenge(9L,19L,29L,1,49L,null,89L,null), "其他同账号局不能接管旧尝试");
                assertEquals(null, mapper.selectLatestZeroChallenge(9L,19L,29L,1,50L,null,88L,null));
                assertEquals(null, mapper.selectLatestZeroChallenge(9L,19L,30L,1,49L,null,88L,null));
                assertEquals(52L, mapper.selectLatestZeroChallenge(9L,19L,29L,2,88L,50L,null,3).getId());
                assertEquals(null, mapper.selectLatestZeroChallenge(9L,19L,29L,2,88L,51L,null,3));
                assertEquals(null, mapper.selectLatestZeroChallenge(9L,19L,29L,2,88L,50L,null,4));
                execute(connection, "UPDATE topic_route_action SET outcome_code='ASK' WHERE id=1"); sql.clearCache();
                assertEquals(null, mapper.selectLatestZeroChallenge(9L,19L,29L,1,49L,null,88L,null));
                execute(connection, "UPDATE game_runtime_session SET state_json='{\"zeroChallengeInstance\":\"old\",\"originalRouteSessionId\":88}' WHERE id=51"); sql.clearCache();
                assertEquals(51L, mapper.selectLatestZeroChallenge(9L,19L,29L,1,49L,null,88L,null).getId());
                execute(connection, "UPDATE topic_route_action SET outcome_code='CHALLENGE' WHERE id=1");
                execute(connection, "UPDATE game_runtime_session SET state_json='{\"zeroChallengeInstance\":\"old\",\"originalRouteSessionId\":89}' WHERE id=51"); sql.clearCache();
                assertEquals(null, mapper.selectLatestZeroChallenge(9L,19L,29L,1,49L,null,88L,null), "已有不同origin不能被旧回执覆盖");
            }
        }
    }

    @Test
    void legacyFrozenBranchCommitsOnceAndInvalidOutcomeRollsBack() throws Exception {
        String url = MybatisItDatabase.url("legacy_game_runtime");
        PooledDataSource source = new PooledDataSource("com.mysql.cj.jdbc.Driver", url,
                MybatisItDatabase.user(), MybatisItDatabase.password());
        try {
            try (Connection connection = source.getConnection()) {
                createTemplateTables(connection);
                ItSchema.applyMigrations(connection, "sql/migration_20260822_advanced_game_runtime.sql",
                        "sql/migration_m9_play_attempt_log.sql", "sql/migration_20260820_new_city_preference_chain.sql", "sql/migration_20261023_advanced_command_fingerprint.sql");
                execute(connection, "ALTER TABLE game_runtime_session ADD play_instance_id VARCHAR(64) NOT NULL DEFAULT 'default'");
                execute(connection, "CREATE TABLE play_team_member (team_id BIGINT, member_id BIGINT, status INT) ENGINE=InnoDB");
            }
            Configuration config = new Configuration();
            config.getTypeAliasRegistry().registerAlias("PlayAttemptLog", PlayAttemptLog.class);
            config.setEnvironment(new Environment("it", new SpringManagedTransactionFactory(), source));
            for (String name : new String[]{"GameRuntimeSession", "PlayAttemptLog"}) {
                String resource = "mapper/business/" + name + "Mapper.xml";
                try (InputStream xml = getClass().getClassLoader().getResourceAsStream(resource)) {
                    new XMLMapperBuilder(xml, config, resource, config.getSqlFragments()).parse();
                }
            }
            SqlSessionTemplate sql = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
            AdvancedGameRuntimeServiceImpl target = new AdvancedGameRuntimeServiceImpl();
        ReflectionTestUtils.setField(target, "transactionManager", new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
            ReflectionTestUtils.setField(target, "sessionMapper", sql.getMapper(GameRuntimeSessionMapper.class));
            ReflectionTestUtils.setField(target, "attemptMapper", sql.getMapper(PlayAttemptLogMapper.class));
            // 只验证冻结会话的持久化；报名、节点与活动入口使用夹具，不冒充真实业务验收。
            CmsTopicNode node = new CmsTopicNode();
            node.setId(29L); node.setTopicId(19L); node.setTemplateId(39L);
            CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
            when(nodes.selectCmsTopicNodeById(29L)).thenReturn(node);
            CmsActivity activity = new CmsActivity(); activity.setTopicId(19L);
            CmsActivityMapper activities = mock(CmsActivityMapper.class);
            when(activities.selectCmsActivityById(9L)).thenReturn(activity);
            when(activities.selectCmsActivityByIdForUpdate(9L)).thenReturn(activity); // A包当前资格按 activity→registration 锁读
            PlayTeamMapper teams = mock(PlayTeamMapper.class);
            when(teams.hasPaidRegistration(7L, 2, 9L)).thenReturn(1);
            CmsMemberTemplateMapper templates = mock(CmsMemberTemplateMapper.class);
            ReflectionTestUtils.setField(target, "nodeMapper", nodes);
            ReflectionTestUtils.setField(target, "activityMapper", activities);
            CmsRegistrationMapper currentRegistrations = mock(CmsRegistrationMapper.class);
            com.chengyinhub.business.service.impl.CurrentRegistrationStubs.currentFromProbe(currentRegistrations);
            ReflectionTestUtils.setField(target, "registrationMapper", currentRegistrations);
            ReflectionTestUtils.setField(target, "teamMapper", teams);
            ReflectionTestUtils.setField(target, "templateMapper", templates);
            ProxyFactory factory = new ProxyFactory(target);
            factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                    new AnnotationTransactionAttributeSource()));
            IAdvancedGameRuntimeService service = (IAdvancedGameRuntimeService) factory.getProxy();
            JdbcTemplate jdbc = new JdbcTemplate(source);
            String missing = "{\"schemaVersion\":1,\"branch\":{\"enabled\":true,\"startStepId\":\"start\",\"steps\":["
                    + "{\"id\":\"start\",\"terminal\":false,\"options\":[{\"id\":\"go\",\"label\":\"继续\",\"nextStepId\":\"end\"}]},"
                    + "{\"id\":\"end\",\"terminal\":true,\"options\":[]}]}}";
            String[] configs = {missing, missing.replace("\"terminal\":true", "\"terminal\":true,\"outcomeCode\":null"),
                    missing.replace("\"options\":[]}]", "\"options\":[]},{\"id\":\"other\",\"terminal\":true,\"options\":[]}]"),
                    missing.replace("\"terminal\":true", "\"terminal\":true,\"outcomeCode\":\" \"")};
            String[] codes = {"COMPLETED", "COMPLETED", "end"};
            for (int i = 0; i < configs.length; i++) {
                jdbc.update("DELETE FROM game_runtime_session");
                jdbc.update("DELETE FROM play_attempt_log");
                jdbc.update("INSERT INTO game_runtime_session(id,activity_id,topic_id,node_id,template_id,owner_type,owner_id,"
                        + "config_snapshot_json,state_json,status,version,started_at,create_time,update_time) "
                        + "VALUES (51,9,19,29,39,1,7,?,'{\"branch\":{\"currentStepId\":\"start\"}}',0,0,NOW(),NOW(),NOW())", configs[i]);
                try (Connection readback = DriverManager.getConnection(url, MybatisItDatabase.user(), MybatisItDatabase.password())) {
                    String frozen = scalar(readback, "SELECT config_snapshot_json FROM game_runtime_session WHERE id=51");
                    assertEquals(51L, service.start(7L, 9L, 19L, 29L, null).get("sessionId"));
                    String key = "legacy-mysql-" + i;
                    service.act(7L, 51L, 0, key, "CHOOSE", Collections.singletonMap("optionId", "go"));
                    String originalHash = scalar(readback, "SELECT request_fingerprint FROM play_attempt_log WHERE idempotency_key='" + key + "'");
                    assertTrue(originalHash.matches("[0-9a-f]{64}"));
                    assertThrows(ServiceException.class, () -> service.act(7L, 51L, 1, key, "CHOOSE", Collections.singletonMap("optionId", "go")));
                    assertThrows(ServiceException.class, () -> service.act(7L, 51L, 0, key, "CHOOSE", Collections.singletonMap("optionId", "changed")));
                    assertThrows(ServiceException.class, () -> service.act(7L, 51L, 0, key, "DRAW", Collections.emptyMap()));
                    assertEquals(originalHash, scalar(readback, "SELECT request_fingerprint FROM play_attempt_log WHERE idempotency_key='" + key + "'"));
                    assertEquals("1", scalar(readback, "SELECT version FROM game_runtime_session WHERE id=51"));
                    assertEquals("end", scalar(readback, "SELECT JSON_UNQUOTE(JSON_EXTRACT(state_json,'$.branch.currentStepId')) FROM game_runtime_session WHERE id=51"));
                    if (i < codes.length) {
                        assertEquals(codes[i], service.completeGate(7L, 9L, 19L, 29L));
                        assertEquals(codes[i], service.completeGate(7L, 9L, 19L, 29L));
                        service.act(7L, 51L, 0, key, "CHOOSE", Collections.singletonMap("optionId", "go"));
                        assertEquals("1:2:1", scalar(readback, "SELECT CONCAT(status,':',version,':',completed_at IS NOT NULL) FROM game_runtime_session WHERE id=51"));
                    } else {
                        assertThrows(ServiceException.class, () -> service.completeGate(7L, 9L, 19L, 29L));
                        assertEquals("0:1:1", scalar(readback, "SELECT CONCAT(status,':',version,':',completed_at IS NULL) FROM game_runtime_session WHERE id=51"),
                                "显式空结局拒绝后，先写的完成状态必须由真实事务回滚");
                    }
                    assertEquals(frozen, scalar(readback, "SELECT config_snapshot_json FROM game_runtime_session WHERE id=51"));
                    assertEquals("1", scalar(readback, "SELECT COUNT(*) FROM game_runtime_session"));
                    assertEquals("1", scalar(readback, "SELECT COUNT(*) FROM play_attempt_log"));
                }
            }
            verifyNoInteractions(templates);
        } finally {
            source.forceCloseAll();
        }
    }

    @Test
    void allLegacyMultiplayerPathsPersistTurnsAndReplayAtEveryAllowedTeamSize() throws Exception {
        Map<String, JsonNode> seeds = new LinkedHashMap<>();
        ObjectMapper json = new ObjectMapper();
        Pattern rows = Pattern.compile("INSERT INTO seed_merchant_game_(?:a8|b27)_config\\s+\\([^)]*\\)"
                + "\\s+VALUES\\s*\\('([^']+)', '((?:''|[^'])*)'", Pattern.DOTALL);
        int seedCount = 0;
        for (String batch : new String[]{"a8", "b27"}) {
            String text = new String(Files.readAllBytes(Paths.get(
                    "sql/migration_seed_merchant_game_" + batch + "_20260823.sql")), StandardCharsets.UTF_8);
            Matcher matcher = rows.matcher(text);
            while (matcher.find()) {
                seedCount++;
                JsonNode config = json.readTree(matcher.group(2).replace("''", "'"));
                if (config.path("multiplayer").path("enabled").asBoolean()) {
                    assertTrue(seeds.put(matcher.group(1), config) == null, "种子不能重复");
                }
            }
        }
        assertEquals(35, seedCount, "A8/B27必须全部进入配置分母");
        assertEquals(18, seeds.size(), "不能漏掉旧多人模板");
        String url = MybatisItDatabase.url("legacy_multiplayer_runtime");
        PooledDataSource source = new PooledDataSource("com.mysql.cj.jdbc.Driver", url,
                MybatisItDatabase.user(), MybatisItDatabase.password());
        try {
            try (Connection connection = source.getConnection()) {
                createTemplateTables(connection);
                ItSchema.applyMigrations(connection, "sql/migration_20260822_advanced_game_runtime.sql",
                        "sql/migration_m9_play_attempt_log.sql", "sql/migration_20260820_new_city_preference_chain.sql", "sql/migration_20261023_advanced_command_fingerprint.sql",
                        "sql/migration_20260820_play_team_core.sql");
                execute(connection, "ALTER TABLE game_runtime_session ADD play_instance_id VARCHAR(64) NOT NULL DEFAULT 'default'");
                execute(connection, "ALTER TABLE play_team ADD invite_code VARCHAR(64)");
                execute(connection, "ALTER TABLE play_team_member ADD apply_expire_time DATETIME");
                execute(connection, "CREATE TABLE cms_activity (id BIGINT PRIMARY KEY, topic_id BIGINT, del_flag INT) ENGINE=InnoDB");
                execute(connection, "INSERT INTO cms_activity VALUES (9,19,0)");
                execute(connection, "CREATE TABLE cms_registration (member_id BIGINT, owner_type INT, owner_id BIGINT, "
                        + "payment_status INT, registration_status INT) ENGINE=InnoDB");
                execute(connection, "CREATE TABLE ums_member (id BIGINT PRIMARY KEY, nickname VARCHAR(64), avatar VARCHAR(255)) ENGINE=InnoDB");
                for (long member = 7; member <= 10; member++) {
                    execute(connection, "INSERT INTO ums_member VALUES (" + member + ",'fixture-member','')");
                    execute(connection, "INSERT INTO cms_registration VALUES (" + member + ",2,9,2,2)");
                }
            }
            Configuration config = new Configuration();
            config.getTypeAliasRegistry().registerAlias("PlayAttemptLog", PlayAttemptLog.class);
            config.setEnvironment(new Environment("it", new SpringManagedTransactionFactory(), source));
            for (String name : new String[]{"GameRuntimeSession", "PlayAttemptLog", "PlayTeam"}) {
                String resource = "mapper/business/" + name + "Mapper.xml";
                try (InputStream xml = getClass().getClassLoader().getResourceAsStream(resource)) {
                    new XMLMapperBuilder(xml, config, resource, config.getSqlFragments()).parse();
                }
            }
            SqlSessionTemplate sql = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
            AdvancedGameRuntimeServiceImpl target = new AdvancedGameRuntimeServiceImpl();
        ReflectionTestUtils.setField(target, "transactionManager", new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
            ReflectionTestUtils.setField(target, "sessionMapper", sql.getMapper(GameRuntimeSessionMapper.class));
            ReflectionTestUtils.setField(target, "attemptMapper", sql.getMapper(PlayAttemptLogMapper.class));
            ReflectionTestUtils.setField(target, "teamMapper", sql.getMapper(PlayTeamMapper.class));
            // 活动/节点/模板入口是夹具；成员、报名、会话、动作日志和事务使用真实SQL。
            CmsTopicNode node = new CmsTopicNode();
            node.setId(29L); node.setTopicId(19L); node.setTemplateId(39L);
            CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
            when(nodes.selectCmsTopicNodeById(29L)).thenReturn(node);
            CmsActivity activity = new CmsActivity(); activity.setTopicId(19L);
            CmsActivityMapper activities = mock(CmsActivityMapper.class);
            when(activities.selectCmsActivityById(9L)).thenReturn(activity);
            when(activities.selectCmsActivityByIdForUpdate(9L)).thenReturn(activity); // A包当前资格按 activity→registration 锁读
            CmsMemberTemplate template = new CmsMemberTemplate(); template.setId(39L);
            CmsMemberTemplateMapper templates = mock(CmsMemberTemplateMapper.class);
            when(templates.selectCmsMemberTemplateById(39L)).thenReturn(template);
            ReflectionTestUtils.setField(target, "nodeMapper", nodes);
            ReflectionTestUtils.setField(target, "activityMapper", activities);
            CmsRegistrationMapper currentRegistrations = mock(CmsRegistrationMapper.class);
            com.chengyinhub.business.service.impl.CurrentRegistrationStubs.currentFromProbe(currentRegistrations);
            ReflectionTestUtils.setField(target, "registrationMapper", currentRegistrations);
            ReflectionTestUtils.setField(target, "templateMapper", templates);
            ProxyFactory factory = new ProxyFactory(target);
            factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                    new AnnotationTransactionAttributeSource()));
            IAdvancedGameRuntimeService service = (IAdvancedGameRuntimeService) factory.getProxy();
            JdbcTemplate jdbc = new JdbcTemplate(source);
            int cases = 0;
            int races = 0;
            for (Map.Entry<String, JsonNode> seed : seeds.entrySet()) {
                JsonNode multi = seed.getValue().path("multiplayer");
                for (List<String> path : branchPaths(seed.getValue().path("branch"),
                        seed.getValue().path("branch").path("startStepId").asText(), Collections.emptySet())) {
                    for (int players = multi.path("minPlayers").asInt(); players <= multi.path("maxPlayers").asInt(); players++) {
                        String label = seed.getKey() + ":" + players + ":" + path;
                        for (String table : new String[]{"game_runtime_session", "play_attempt_log", "play_team_member", "play_team"}) {
                            jdbc.update("DELETE FROM " + table);
                        }
                        template.setAdvancedConfigJson(seed.getValue().toString());
                        jdbc.update("INSERT INTO play_team(id,owner_type,owner_id,leader_member_id,status,joined_count,max_members) "
                                + "VALUES (51,2,9,7,2,?,?)", players, players);
                        for (long member = 7; member < 7 + players; member++) {
                            jdbc.update("INSERT INTO play_team_member(team_id,member_id,role,status,create_time) VALUES (51,?,?,1,NOW())",
                                    member, member == 7 ? 1 : 0);
                        }
                        Map<String, Object> view = service.start(7L, 9L, 19L, 29L, 51L);
                        Long sessionId = (Long) view.get("sessionId");
                        assertEquals(2, view.get("ownerType"), label);
                        assertEquals(51L, view.get("ownerId"), label);
                        Map<?, ?> assignments = (Map<?, ?>) ((Map<?, ?>) view.get("multiplayer")).get("roleAssignments");
                        assertEquals(players, assignments.size(), label);
                        for (long member = 8; member < 7 + players; member++) {
                            Map<String, Object> joined = service.start(member, 9L, 19L, 29L, null);
                            assertEquals(sessionId, joined.get("sessionId"), label);
                            Map<?, ?> publicRoles = (Map<?, ?>) joined.get("multiplayer");
                            assertFalse(publicRoles.containsKey("roleAssignments"), label);
                            String roleId = assignments.get(String.valueOf(member)).toString();
                            assertEquals(roleId, publicRoles.get("myRole"), label);
                            for (JsonNode role : multi.path("roles")) {
                                if (roleId.equals(role.path("id").asText())) {
                                    assertEquals(role.path("label").asText(), publicRoles.get("myRoleLabel"), label);
                                }
                            }
                        }
                        try (Connection readback = DriverManager.getConnection(url, MybatisItDatabase.user(), MybatisItDatabase.password())) {
                            String frozen = scalar(readback, "SELECT config_snapshot_json FROM game_runtime_session");
                            template.setAdvancedConfigJson("{}");
                            assertThrows(ServiceException.class, () -> service.completeGate(7L, 9L, 19L, 29L), label);
                            assertThrows(IllegalStateException.class, () -> service.act(8L, sessionId, 0,
                                    "wrong-turn", "COMPLETE_UNIT", Collections.singletonMap("unitId", "wrong")), label);
                            assertEquals("0:0", scalar(readback, "SELECT CONCAT(version,':',completed_units) FROM game_runtime_session"), label);
                            assertEquals("0", scalar(readback, "SELECT COUNT(*) FROM play_attempt_log"), label);
                            int version = 0;
                            String expectedOutcome = null;
                            JsonNode seedConfig = seed.getValue();
                            for (int draw = 0; draw < seedConfig.path("random").path("drawCount").asInt(0); draw++) {
                                service.act(7L, sessionId, version++, "draw-" + draw, "DRAW", Collections.emptyMap());
                            }
                            if (seedConfig.path("branch").path("enabled").asBoolean()) {
                                String stepId = seedConfig.path("branch").path("startStepId").asText();
                                int steps = 0;
                                while (true) {
                                    JsonNode step = null;
                                    for (JsonNode row : seedConfig.path("branch").path("steps")) {
                                        if (stepId.equals(row.path("id").asText())) step = row;
                                    }
                                    assertTrue(step != null && steps <= seedConfig.path("branch").path("steps").size(), label);
                                    if (step.path("terminal").asBoolean()) {
                                        expectedOutcome = step.path("outcomeCode").asText();
                                        assertFalse(expectedOutcome.isEmpty(), label);
                                        break;
                                    }
                                    JsonNode choice = null;
                                    for (JsonNode option : step.path("options")) {
                                        if (path.get(steps).equals(option.path("id").asText())) choice = option;
                                    }
                                    assertTrue(choice != null, label);
                                    service.act(7L, sessionId, version++, "choose-" + steps++, "CHOOSE",
                                            Collections.singletonMap("optionId", choice.path("id").asText()));
                                    stepId = choice.path("nextStepId").asText();
                                }
                                assertEquals(path.size(), steps, label);
                            }
                            int requiredTurns = multi.path("requiredTurns").asInt();
                            int scoreBeforeTurns = Integer.parseInt(scalar(readback, "SELECT score FROM game_runtime_session"));
                            for (int turn = 0; turn < requiredTurns; turn++) {
                                String expectedRole = multi.path("turnOrder").get(turn % multi.path("turnOrder").size()).asText();
                                Long actor = null;
                                for (Map.Entry<?, ?> assigned : assignments.entrySet()) {
                                    if (expectedRole.equals(assigned.getValue())) { actor = Long.valueOf(assigned.getKey().toString()); break; }
                                }
                                assertTrue(actor != null, label);
                                String key = "turn-" + turn;
                                Map<String, Object> payload = Collections.singletonMap("unitId", "unit-" + turn);
                                if (turn == 0 && players == 4 && "team_energy_relay".equals(seed.getKey())) {
                                    assertConcurrentUnitCommitsOnce(service, target, source, readback, sessionId, version, payload);
                                    races++;
                                    version++;
                                } else {
                                    service.act(actor, sessionId, version, key, "COMPLETE_UNIT", payload);
                                    service.act(actor, sessionId, version++, key, "COMPLETE_UNIT", payload);
                                }
                                assertEquals(String.valueOf(turn + 1), scalar(readback, "SELECT completed_units FROM game_runtime_session"), label);
                                assertEquals(String.valueOf(version), scalar(readback, "SELECT COUNT(*) FROM play_attempt_log"), label);
                            }
                            int staleVersion = version - 1;
                            assertThrows(ServiceException.class, () -> service.act(7L, sessionId, staleVersion,
                                    "stale-turn", "COMPLETE_UNIT", Collections.singletonMap("unitId", "stale")), label);
                            assertEquals(String.valueOf(version), scalar(readback, "SELECT COUNT(*) FROM play_attempt_log"), label);
                            assertEquals(String.valueOf(scoreBeforeTurns + requiredTurns * multi.path("unitScore").asInt()),
                                    scalar(readback, "SELECT score FROM game_runtime_session"), label);
                            String outcome = service.completeGate(7L, 9L, 19L, 29L);
                            assertEquals(expectedOutcome, outcome, label);
                            assertEquals(outcome, service.completeGate(8L, 9L, 19L, 29L), label);
                            assertEquals("1:" + (version + 1) + ":1", scalar(readback,
                                    "SELECT CONCAT(status,':',version,':',completed_at IS NOT NULL) FROM game_runtime_session"), label);
                            assertEquals("1", scalar(readback, "SELECT COUNT(*) FROM game_runtime_session"), label);
                            assertEquals(frozen, scalar(readback, "SELECT config_snapshot_json FROM game_runtime_session"), label);
                            jdbc.update("UPDATE play_team_member SET status=2 WHERE team_id=51 AND member_id=8");
                            assertThrows(IllegalStateException.class, () -> service.state(8L, sessionId), label);
                        }
                        cases++;
                    }
                }
            }
            assertEquals(84, cases, "18款的28条完整路径必须覆盖2/3/4人全部允许人数");
            assertEquals(1, races, "必须执行真实并发，不能只跑串行重复请求");
        } finally {
            source.forceCloseAll();
        }
    }

    private static List<List<String>> branchPaths(JsonNode branch, String stepId, Set<String> visited) {
        if (!branch.path("enabled").asBoolean()) return Collections.singletonList(Collections.emptyList());
        assertFalse(visited.contains(stepId), "种子出现循环分支时须另定义完整游玩场景");
        JsonNode step = null;
        for (JsonNode row : branch.path("steps")) {
            if (stepId.equals(row.path("id").asText())) step = row;
        }
        assertTrue(step != null, "分支必须可达存在的步骤");
        if (step.path("terminal").asBoolean()) return Collections.singletonList(Collections.emptyList());
        Set<String> nextVisited = new HashSet<>(visited);
        nextVisited.add(stepId);
        List<List<String>> paths = new ArrayList<>();
        for (JsonNode option : step.path("options")) {
            for (List<String> tail : branchPaths(branch, option.path("nextStepId").asText(), nextVisited)) {
                List<String> path = new ArrayList<>();
                path.add(option.path("id").asText());
                path.addAll(tail);
                paths.add(path);
            }
        }
        assertFalse(paths.isEmpty(), "非终点必须能到达结局");
        return paths;
    }

    private static void assertConcurrentUnitCommitsOnce(IAdvancedGameRuntimeService service,
            AdvancedGameRuntimeServiceImpl target, PooledDataSource source, Connection readback,
            Long sessionId, int version, Map<String, Object> payload) throws Exception {
        CountDownLatch bothRead = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ThreadLocal<Boolean> waiting = ThreadLocal.withInitial(() -> false);
        AdvancedGameClock clock = (AdvancedGameClock) ReflectionTestUtils.getField(target, "clock");
        ReflectionTestUtils.setField(target, "clock", (AdvancedGameClock) () -> {
            if (!waiting.get()) {
                waiting.set(true);
                bothRead.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS), "两次请求必须被明确释放"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }
            return clock.now();
        });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            // 两名同角色队员都已读取同一版本后再放行，证明数据库事务实际重叠。
            Future<Map<String, Object>> first = workers.submit(() ->
                    service.act(7L, sessionId, version, "race-7", "COMPLETE_UNIT", payload));
            Future<Map<String, Object>> second = workers.submit(() ->
                    service.act(9L, sessionId, version, "race-9", "COMPLETE_UNIT", payload));
            assertTrue(bothRead.await(5, TimeUnit.SECONDS), "两次请求必须都进入活动时间校验");
            assertFalse(first.isDone());
            assertFalse(second.isDone());
            assertTrue(source.getPoolState().getActiveConnectionCount() >= 2, "必须有两条活跃数据库连接");
            assertEquals(String.valueOf(version), scalar(readback, "SELECT version FROM game_runtime_session"));
            release.countDown();
            int succeeded = 0;
            int conflicted = 0;
            for (Future<Map<String, Object>> future : java.util.Arrays.asList(first, second)) {
                try {
                    Map<String, Object> result = future.get(10, TimeUnit.SECONDS);
                    assertEquals(version + 1, result.get("version"));
                    succeeded++;
                } catch (ExecutionException failure) {
                    assertTrue(failure.getCause() instanceof IllegalStateException);
                    assertEquals("状态已更新，请刷新后重试", failure.getCause().getMessage());
                    conflicted++;
                }
            }
            assertEquals(1, succeeded);
            assertEquals(1, conflicted);
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "本轮工作线程必须终止");
            ReflectionTestUtils.setField(target, "clock", clock);
        }
        String winner = scalar(readback, "SELECT member_id FROM play_attempt_log WHERE idempotency_key IN ('race-7','race-9')");
        service.act(Long.valueOf(winner), sessionId, version, "race-" + winner, "COMPLETE_UNIT", payload);
        assertEquals("1", scalar(readback, "SELECT COUNT(*) FROM play_attempt_log WHERE idempotency_key IN ('race-7','race-9')"));
        assertEquals(String.valueOf(version + 1), scalar(readback, "SELECT version FROM game_runtime_session"));
        assertEquals("1", scalar(readback, "SELECT completed_units FROM game_runtime_session"));
    }

    private static void createTemplateTables(Connection connection) throws SQLException {
        // 三个独立测试库共用同一份路由会话结构，新增列只在此处补齐。
        execute(connection, "CREATE TABLE topic_route_session (id BIGINT PRIMARY KEY, member_id BIGINT, "
                + "topic_id BIGINT, activity_id BIGINT, play_instance_id VARCHAR(64)) ENGINE=InnoDB");
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
