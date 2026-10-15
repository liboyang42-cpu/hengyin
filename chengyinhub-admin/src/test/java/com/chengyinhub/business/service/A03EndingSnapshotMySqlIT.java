package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.route.NodeOutcome;
import com.chengyinhub.business.it.ItSchema;
import com.chengyinhub.business.it.MybatisItDatabase;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.NpcProfileService;
import com.chengyinhub.business.service.impl.PlayJourneyStoryServiceImpl;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.business.service.support.RouteReachabilityProvider;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real MySQL regression. Cloud must not execute this class. No mapper/service mocks. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_ENDING_MYSQL_IT", matches = "1")
class A03EndingSnapshotMySqlIT {
    private static final String SUITE = "a03_ending_snapshot";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RULES = "{\"schemaVersion\":1,\"checkEnabled\":true,\"hp\":{\"enabled\":true,\"max\":10,\"init\":8}}";
    // Two unconditional matches deliberately retain JourneyStory's first-match policy.
    private static final String STORY = "{\"schemaVersion\":1,\"ending\":{\"endings\":["
            + "{\"code\":\"first\",\"title\":\"first title\",\"chapterId\":88},"
            + "{\"code\":\"second\",\"title\":\"second title\",\"chapterId\":89},"
            + "{\"code\":\"fallback\",\"title\":\"fallback title\",\"fallback\":true}]}}";
    private static PooledDataSource dataSource;
    private static SqlSessionTemplate sql;
    private static ITopicRouteRuntimeService runtime;
    private static PlayJourneyStoryServiceImpl story;
    private static String database;
    private static boolean created;
    private static String adminUrl;

    @BeforeAll
    static void setup() throws Exception {
        // Validate identifiers before creating a fresh schema; never take over an existing schema.
        assertEquals("127.0.0.1", System.getenv("CHENGYIN_MYBATIS_IT_HOST"), "explicit loopback scratch required");
        String suffix = System.getenv("CHENGYIN_MYBATIS_IT_DB_SUFFIX");
        assertNotNull(suffix, "unique scratch suffix required");
        assertTrue(suffix.matches("_[a-z0-9_]{1,24}"), "suffix must be an isolated SQL-safe identifier");
        assertNotNull(System.getenv("CHENGYIN_MYBATIS_IT_USER"), "explicit test account required");
        assertFalse(System.getenv("CHENGYIN_MYBATIS_IT_USER").trim().isEmpty(), "test account cannot inherit root fallback");
        assertNotNull(System.getenv("CHENGYIN_MYBATIS_IT_PORT"), "explicit scratch port required");
        assertNotNull(System.getenv("CHENGYIN_MYBATIS_IT_PASSWORD"), "explicit test password required (empty allowed)");
        assertTrue(MybatisItDatabase.port().matches("[0-9]{1,5}"));
        adminUrl = "jdbc:mysql://127.0.0.1:" + MybatisItDatabase.port()
                + "/?useSSL=false&allowPublicKeyRetrieval=true";
        try (Connection c = DriverManager.getConnection(adminUrl, MybatisItDatabase.user(), MybatisItDatabase.password());
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT VERSION()")) {
            assertTrue(rs.next());
            assertTrue(rs.getString(1).startsWith("8.0."), "requires real MySQL 8.0, never H2");
        }
        database = MybatisItDatabase.databaseName(SUITE);
        assertTrue(database.matches("chengyin_mybatis_a03_ending_snapshot_[a-z0-9_]{1,24}"));
        try (Connection c = DriverManager.getConnection(adminUrl, MybatisItDatabase.user(), MybatisItDatabase.password());
             Statement st = c.createStatement()) {
            // A name collision must fail without touching the existing schema.
            st.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
            created = true;
        }
        String url = "jdbc:mysql://127.0.0.1:" + MybatisItDatabase.port() + "/" + database
                + "?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true";
        dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver", url,
                MybatisItDatabase.user(), MybatisItDatabase.password());
        try (Connection c = dataSource.getConnection()) {
            ItSchema.apply(c, "it-schema/cms-topic-it-schema.sql");
            ItSchema.apply(c, "it-schema/journey-full-flow-it-schema.sql");
            ItSchema.apply(c, "it-schema/merchant-chapter-offer-it-schema.sql");
            ItSchema.applyMigrations(c, "sql/migration_theme_state_key_20260916.sql",
                    "sql/migration_20260823_topic_route_graph.sql",
                    "sql/migration_20260930_registration_first_successful_scan.sql",
                    "sql/migration_ordinary_chapter_offer_withdraw_20261003.sql",
                    "sql/migration_20261003_merchant_offer_withdrawal_columns.sql");
        }
        Configuration cfg = new Configuration(new Environment("a03-ending", new SpringManagedTransactionFactory(), dataSource));
        cfg.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        for (String mapper : Arrays.asList("CmsTopicMapper", "CmsTopicChapterMapper", "CmsTopicNodeMapper",
                "CmsMemberTemplateMapper", "CmsRegistrationMapper", "PlayerNodeProgressMapper",
                "ThemeStateKeyMapper", "NpcProfileMapper", "TopicRouteSessionMapper")) {
            String resource = "mapper/business/" + mapper + ".xml";
            try (InputStream in = A03EndingSnapshotMySqlIT.class.getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(in, resource);
                new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
            }
        }
        sql = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg));
        NpcProfileService npc = new NpcProfileService();
        ReflectionTestUtils.setField(npc, "npcProfileMapper", sql.getMapper(NpcProfileMapper.class));
        story = new PlayJourneyStoryServiceImpl(sql.getMapper(CmsTopicMapper.class),
                sql.getMapper(CmsTopicChapterMapper.class), sql.getMapper(CmsTopicNodeMapper.class),
                sql.getMapper(TopicRouteSessionMapper.class), new ThemeStateKeyService(sql.getMapper(ThemeStateKeyMapper.class)), npc);
        TopicRouteRuntimeServiceImpl target = new TopicRouteRuntimeServiceImpl(sql.getMapper(TopicRouteSessionMapper.class),
                sql.getMapper(CmsTopicMapper.class), sql.getMapper(CmsTopicNodeMapper.class),
                sql.getMapper(CmsMemberTemplateMapper.class), sql.getMapper(PlayerNodeProgressMapper.class),
                null, sql.getMapper(CmsRegistrationMapper.class), null, null,
                () -> 12345L, () -> 10, Clock.systemUTC(), RouteReachabilityProvider.UNKNOWN);
        ReflectionTestUtils.setField(target, "cmsTopicChapterMapper", sql.getMapper(CmsTopicChapterMapper.class));
        ReflectionTestUtils.setField(target, "themeStateKeyMapper", sql.getMapper(ThemeStateKeyMapper.class));
        // Real Spring injection omitted by the older JourneyFullFlow fixture: exercise terminal story archival too.
        ReflectionTestUtils.setField(target, "themeStoryService", story);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(ITopicRouteRuntimeService.class);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        runtime = (ITopicRouteRuntimeService) proxy.getProxy();
    }

    @BeforeEach
    void seed() throws Exception {
        for (String table : Arrays.asList("topic_route_action", "topic_route_session", "player_node_progress",
                "cms_registration", "theme_state_key", "cms_member_template", "cms_topic_node", "cms_topic_chapter", "cms_topic")) {
            execute("DELETE FROM " + table);
        }
        execute("INSERT INTO cms_topic(id,member_id,name,route_mode,published_config_version,journey_rules_json,journey_story_json,status,user_status,del_flag)"
                + " VALUES(700,97,'ending fixture','LINEAR',1,?,?,1,1,0)", RULES, STORY);
        execute("INSERT INTO cms_topic_chapter(id,topic_id,name,sort_id,blocks) VALUES(77,700,'route',1,'[]')");
        execute("INSERT INTO cms_topic_node(id,topic_id,chapter_id,name,status,sort_id) VALUES(21,700,77,'station',1,1)");
        execute("INSERT INTO cms_topic_chapter(id,topic_id,name,sort_id,blocks) VALUES(88,700,'old chapter',2,?),"
                + "(89,700,'unselected chapter',3,?)",
                "[{\"type\":\"text\",\"content\":\"old body\"},{\"type\":\"text\",\"content\":\"private body\",\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.secret\"}}]",
                "[{\"type\":\"text\",\"content\":\"unselected body\"}]");
        execute("INSERT INTO cms_registration(member_id,owner_type,owner_id,topic_id,registration_status,ticket_id,purchase_kind,create_time)"
                + " VALUES(97,1,700,700,2,1,0,NOW())");
    }

    @Test
    void newSessionFreezesBodyAndPublicEndingProjectsOnlyFirstChosenChapter() throws Exception {
        runtime.state(97L, 700L, 0L);
        String frozen = session().getConfigSnapshotJson();
        assertEquals("old chapter", JSON.readTree(frozen).path("endingChapters").path("88").path("name").asText());
        assertTrue(JSON.readTree(frozen).path("endingChapters").has("89"));
        editAuthorChapter();
        complete();
        Map<String, Object> ending = story.ending(97L, 700L, 0L);
        assertEquals("first", ending.get("code"));
        assertProjectedOldBody(JSON.valueToTree(ending).path("chapter"));
        assertEquals(frozen, session().getConfigSnapshotJson(), "completion must not rewrite the frozen content version");
        assertEquals("new chapter", sql.getMapper(CmsTopicChapterMapper.class).selectCmsTopicChapterById(88L).getName());
    }

    @Test
    void persistedMissingChapterDoesNotRefillButLegacyMissingKeyKeepsLiveFallback() throws Exception {
        runtime.state(97L, 700L, 0L);
        complete();
        editAuthorChapter();
        ObjectNode frozen = (ObjectNode) JSON.readTree(session().getConfigSnapshotJson());
        ((ObjectNode) frozen.path("endingChapters")).remove("88");
        execute("UPDATE topic_route_session SET config_snapshot_json=? WHERE topic_id=700", frozen.toString());
        assertNull(story.ending(97L, 700L, 0L).get("chapter"), "present key with only unselected chapter must not refill live body");
        frozen.set("endingChapters", JSON.createObjectNode());
        execute("UPDATE topic_route_session SET config_snapshot_json=? WHERE topic_id=700", frozen.toString());
        assertNull(story.ending(97L, 700L, 0L).get("chapter"));
        frozen.remove("endingChapters");
        execute("UPDATE topic_route_session SET config_snapshot_json=? WHERE topic_id=700", frozen.toString());
        JsonNode legacy = JSON.valueToTree(story.ending(97L, 700L, 0L)).path("chapter");
        assertEquals("new chapter", legacy.path("name").asText());
        assertEquals("new body", legacy.path("blocks").get(0).path("content").asText());
        execute("DELETE FROM cms_topic_chapter WHERE id=88");
        assertNull(story.ending(97L, 700L, 0L).get("chapter"), "legacy fallback cannot reconstruct deleted content");
    }

    @Test
    void terminalThemeStoryMustUseSessionFrozenBodyAfterAuthorEdit() throws Exception {
        execute("UPDATE cms_topic SET published_theme_runtime_config_json=? WHERE id=700", "{\"schemaVersion\":1}");
        runtime.state(97L, 700L, 0L);
        assertEquals("old chapter", JSON.readTree(session().getConfigSnapshotJson()).path("endingChapters").path("88").path("name").asText());
        editAuthorChapter();
        complete();
        // Independent real mapper readback: verify the durable terminal result, not a helper-only projection.
        JsonNode result = JSON.readTree(session().getStateJson()).path("themeRuntime").path("result");
        assertTrue(result.isObject(), "terminal runtime must archive a result");
        assertEquals("first", result.path("endingId").asText());
        assertProjectedOldBody(result.path("story").path("chapter"));
        assertEquals(result.path("story"), JSON.valueToTree(story.ending(97L, 700L, 0L)).path("story"));
    }

    private static void assertProjectedOldBody(JsonNode chapter) {
        assertEquals(88L, chapter.path("id").asLong());
        assertEquals("old chapter", chapter.path("name").asText(), "author edits must not alter this session's ending body");
        assertEquals(1, chapter.path("blocks").size());
        assertEquals("old body", chapter.path("blocks").get(0).path("content").asText());
        assertFalse(chapter.toString().contains("private body"));
        assertFalse(chapter.toString().contains("unselected"));
    }

    private static void complete() throws Exception {
        execute("INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,status,del_flag,create_time) VALUES(97,0,700,21,1,0,NOW())");
        runtime.advance(97L, 700L, 0L, session().getVersion(), "finish-21", "CHOICE", new NodeOutcome(21L, "COMPLETED", "receipt-21"));
        assertEquals(TopicRouteSession.STATUS_COMPLETED, session().getStatus().intValue());
    }

    private static void editAuthorChapter() throws Exception {
        execute("UPDATE cms_topic_chapter SET name='new chapter',blocks=? WHERE id=88",
                "[{\"type\":\"text\",\"content\":\"new body\"}]");
    }

    private static TopicRouteSession session() {
        TopicRouteSession row = sql.getMapper(TopicRouteSessionMapper.class).selectByOwner(0L, 700L, 97L, "default");
        assertNotNull(row);
        return row;
    }

    private static void execute(String statement, String... params) throws Exception {
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(statement)) {
            for (int i = 0; i < params.length; i++) st.setString(i + 1, params[i]);
            st.executeUpdate();
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (dataSource != null) dataSource.forceCloseAll();
        if (!created) return;
        assertTrue(database.matches("chengyin_mybatis_a03_ending_snapshot_[a-z0-9_]{1,24}"));
        try (Connection c = DriverManager.getConnection(adminUrl, MybatisItDatabase.user(), MybatisItDatabase.password());
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE `" + database + "`");
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='" + database + "'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "scratch cleanup must be verified");
            }
        }
    }
}
