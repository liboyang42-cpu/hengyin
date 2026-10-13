package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class TeamTopicConfigMapperSqlTest {
    private static final String TOPIC_XML = "mapper/business/CmsTopicMapper.xml";
    private static final String ACTIVITY_XML = "mapper/business/CmsActivityMapper.xml";

    @Test
    void topicConfigRoundTripsAndProjectsToActivity() throws Exception {
        Configuration topicCfg = parse(TOPIC_XML, CmsTopic.class);
        assertEquals("team_mode", columnFor(topicCfg, "CmsTopicResult", "teamMode"));
        assertEquals("team_max_members", columnFor(topicCfg, "CmsTopicResult", "teamMaxMembers"));

        CmsTopic topic = new CmsTopic();
        topic.setId(1L);
        topic.setTeamMode(2);
        topic.setTeamMaxMembers(3);
        String insert = sql(topicCfg, "com.chengyinhub.business.mapper.CmsTopicMapper.insertCmsTopic", topic);
        String update = sql(topicCfg, "com.chengyinhub.business.mapper.CmsTopicMapper.updateCmsTopic", topic);
        assertTrue(insert.contains("team_mode") && insert.contains("team_max_members"), insert);
        assertTrue(update.contains("team_mode") && update.contains("team_max_members"), update);

        Configuration activityCfg = parse(ACTIVITY_XML, CmsActivity.class);
        assertEquals("team_mode", columnFor(activityCfg, "CmsActivityResult", "teamMode"));
        assertEquals("team_max_members", columnFor(activityCfg, "CmsActivityResult", "teamMaxMembers"));
        String activity = sql(activityCfg,
                "com.chengyinhub.business.mapper.CmsActivityMapper.selectCmsActivityById", 9L);
        assertTrue(activity.contains("t.team_mode") && activity.contains("t.team_max_members"), activity);
    }

    @Test
    void topicItSchemaFixturesIncludeTeamConfigColumns() throws Exception {
        for (String resource : new String[]{"it-schema/cms-topic-it-schema.sql", "it-schema/complaint-it-schema.sql",
                "it-schema/explore-alliance-it-schema.sql"}) {
            try (InputStream in = Resources.getResourceAsStream(resource);
                 Scanner scanner = new Scanner(in, "UTF-8").useDelimiter("\\A")) {
                String schema = scanner.hasNext() ? scanner.next().toLowerCase() : "";
                assertTrue(schema.contains("`team_mode`"), resource + " 缺 team_mode");
                assertTrue(schema.contains("`team_max_members`"), resource + " 缺 team_max_members");
            }
        }
    }

    @Test
    void registrationFundsTopicFixtureIncludesTeamConfigColumns() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get("src", "test", "java", "com", "chengyinhub",
                "business", "service", "RegistrationFundsMybatisIntegrationTest.java")), StandardCharsets.UTF_8)
                .toLowerCase();
        int start = source.indexOf("\"create table cms_topic (");
        int end = source.indexOf("engine=innodb\"", start);
        assertTrue(start >= 0 && end > start, "RegistrationFunds cms_topic fixture 锚点失效");
        String schema = source.substring(start, end);
        assertTrue(schema.contains("team_mode tinyint not null default 0"), "RegistrationFunds fixture 缺 team_mode");
        assertTrue(schema.contains("team_max_members int not null default 4"), "RegistrationFunds fixture 缺 team_max_members");
    }

    private Configuration parse(String resource, Class<?> alias) throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias(alias.getSimpleName(), alias);
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
        }
        return cfg;
    }

    private String sql(Configuration cfg, String statement, Object parameter) {
        return cfg.getMappedStatement(statement).getBoundSql(parameter).getSql()
                .replaceAll("\\s+", " ").toLowerCase();
    }

    private String columnFor(Configuration cfg, String resultMapId, String property) {
        ResultMap map = cfg.getResultMap(resultMapId);
        for (ResultMapping mapping : map.getResultMappings()) {
            if (property.equals(mapping.getProperty())) return mapping.getColumn();
        }
        fail("resultMap 缺少 " + property);
        return null;
    }
}
