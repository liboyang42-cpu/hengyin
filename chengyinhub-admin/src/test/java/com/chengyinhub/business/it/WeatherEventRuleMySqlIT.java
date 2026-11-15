package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.WeatherEventRule;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.WeatherEventRuleMapper;
import com.chengyinhub.business.service.support.rpg.TopicEnvironmentService;
import com.chengyinhub.business.service.support.rpg.TopicWeatherEventService;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real MySQL IT for {@code migration_20261114_weather_event_rule.sql},
 * {@link WeatherEventRuleMapper}, and {@link TopicWeatherEventService}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WeatherEventRuleMySqlIT {

    private String database;
    private DriverManagerDataSource dataSource;
    private SqlSession session;
    private WeatherEventRuleMapper ruleMapper;

    @BeforeAll
    void setup() throws Exception {
        assumeTrue("1".equals(System.getenv("CHENGYIN_WEATHER_MYSQL_IT")),
                "set CHENGYIN_WEATHER_MYSQL_IT=1");
        assertTrue("127.0.0.1".equals(MybatisItDatabase.host()) || "localhost".equals(MybatisItDatabase.host()),
                "Only localhost scratch MySQL");
        String suite = "weather_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String url = MybatisItDatabase.url(suite) + "&allowMultiQueries=true";
        database = MybatisItDatabase.databaseName(suite);
        dataSource = new DriverManagerDataSource(url, MybatisItDatabase.user(), MybatisItDatabase.password());

        String migrationSql = Files.readString(Path.of("sql/migration_20261114_weather_event_rule.sql"));
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            // Apply migration twice to prove additive idempotency.
            s.execute(migrationSql);
            s.execute(migrationSql);
        }

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/WeatherEventRuleMapper.xml"));
        session = new SqlSessionTemplate(factory.getObject());
        ruleMapper = session.getMapper(WeatherEventRuleMapper.class);
    }

    @AfterAll
    void tearDown() {
        if (database == null) {
            return;
        }
        String adminUrl = MybatisItDatabase.url(database).replace("/" + database + "?", "/mysql?");
        JdbcTemplate admin = new JdbcTemplate(
                new DriverManagerDataSource(adminUrl, MybatisItDatabase.user(), MybatisItDatabase.password()));
        admin.execute("DROP DATABASE IF EXISTS " + database);
    }

    @Test
    void migrationTableAndRoundTripCrudEvaluateAndRollbackWorkOnRealMySql() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer tableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'weather_event_rule'",
                Integer.class, database);
        assertEquals(1, tableCount);

        CmsTopic topic = new CmsTopic();
        topic.setId(7701L);
        topic.setAddressName("杭州");
        topic.setJourneyRulesJson("");
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        when(topicMapper.updateCmsTopic(any(CmsTopic.class))).thenAnswer(inv -> {
            CmsTopic patch = inv.getArgument(0);
            topic.setJourneyRulesJson(patch.getJourneyRulesJson());
            return 1;
        });

        Clock rainClock = Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC);
        TopicEnvironmentService envService = new TopicEnvironmentService(city -> "rain", rainClock);
        TopicWeatherEventService service = new TopicWeatherEventService(envService, ruleMapper, topicMapper, rainClock);

        TopicWeatherEventService.RuleInput r1 = new TopicWeatherEventService.RuleInput();
        r1.setRuleName("雨天隐藏站");
        r1.setConditionKey("weather");
        r1.setConditionValues(List.of("rain"));
        r1.setUnlockNodeId(9001L);
        r1.setTaskLabel("雨天限定");
        r1.setEnabled(true);

        TopicWeatherEventService.RuleInput r2 = new TopicWeatherEventService.RuleInput();
        r2.setRuleName("晴天露台站");
        r2.setConditionKey("weather");
        r2.setConditionValues(List.of("clear"));
        r2.setUnlockNodeId(9002L);
        r2.setTaskLabel("晴天露台");
        r2.setEnabled(true);

        TopicWeatherEventService.SaveRequest req = new TopicWeatherEventService.SaveRequest();
        req.setEnabled(true);
        req.setKeys(List.of("weather", "daypart"));
        req.setRules(List.of(r1, r2));

        Map<String, Object> adminView = service.adminSaveConfig(topic, req);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> saved = (List<Map<String, Object>>) adminView.get("rules");
        assertEquals(2, saved.size());

        List<WeatherEventRule> dbRows = ruleMapper.selectActiveByTopicId(7701L);
        assertEquals(2, dbRows.size());

        Map<Long, String> locks = service.weatherLockedNodes(topic);
        assertFalse(locks.containsKey(9001L));
        assertTrue(locks.containsKey(9002L));

        // Verify rollback script cleanly drops table on isolated DB.
        String rollbackSql = Files.readString(Path.of("sql/bak_weather_event_rule_20261114_rollback.sql"));
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(rollbackSql);
        }
        Integer afterRollback = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'weather_event_rule'",
                Integer.class, database);
        assertEquals(0, afterRollback);
    }
}
