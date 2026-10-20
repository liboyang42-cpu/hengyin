package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.framework.datasource.DynamicDataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Authorized, isolated MySQL only; no Cloud/default-schema execution.
 * Actual controller transaction proxy + actual services/MyBatis XML + independent writer connection.
 * The writer models the already transactional content replacement, not the entire publish workflow.
 * Only identity and a barrier AFTER the real topic SELECT are supplied by test subclasses.
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class B02TopicEditDetailSnapshotMyBatisTest {
    private static final long TOPIC = 7001L;
    private static final long OWNER = 901L;
    private final MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.fromEnvironment("b02_edit_snapshot_it");
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private SqlSessionTemplate session;
    private boolean schemaCreated;

    @BeforeAll
    void setup() throws Exception {
        assertNotNull(System.getenv("CHENGYIN_TEST_MYSQL_HOST"), "Explicit authorized test host required");
        String suffix = System.getenv("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX");
        assertNotNull(suffix, "Explicit isolated test suffix required");
        assertFalse(suffix.trim().isEmpty());
        JdbcTemplate admin = admin();
        admin.execute("DROP DATABASE IF EXISTS " + mysql.databaseName());
        admin.execute("CREATE DATABASE " + mysql.databaseName() + " CHARACTER SET utf8mb4");
        schemaCreated = true;
        DriverManagerDataSource physical = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        // Match production's routing data source; readOnly alone must not move a query to a replica.
        dataSource = new DynamicDataSource(physical, Collections.<Object, Object>singletonMap("MASTER", physical));
        jdbc = new JdbcTemplate(dataSource);
        manager = new DataSourceTransactionManager(dataSource);
        createFixtureSchema();
        Configuration cfg = new Configuration(new Environment("b02-edit-snapshot-it",
                new SpringManagedTransactionFactory(), dataSource));
        cfg.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        for (Class<?> type : new Class<?>[]{CmsTopicMapper.class, CmsTopicChapterMapper.class,
                CmsTopicNodeMapper.class, OmsTicketMapper.class, ViewCollaboratorsMapper.class}) {
            cfg.addMapper(type);
            String resource = "mapper/business/" + type.getSimpleName() + ".xml";
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input, resource);
                new XMLMapperBuilder(input, cfg, resource, cfg.getSqlFragments()).parse();
            }
        }
        session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg));
    }

    @AfterAll
    void cleanup() {
        if (schemaCreated) admin().execute("DROP DATABASE IF EXISTS " + mysql.databaseName());
    }

    private JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()));
    }

    @BeforeEach
    void seed() {
        for (String table : new String[]{"b02_collaborator_rows", "oms_ticket", "cms_topic_node", "cms_topic_chapter", "cms_topic"}) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("INSERT INTO cms_topic(id,member_id,name,status,user_status,del_flag,is_draft,lifecycle,"
                + "product_type,config_version,route_mode,journey_story_json,route_graph_json,update_time)"
                + " VALUES (?,?,?,0,0,0,1,0,1,1,'BRANCH_GRAPH',?,?, '2026-10-15 00:00:01')",
                TOPIC, OWNER, "old-topic", story(11), graph(101));
        insertChildren(11, 101, 21, 801);
    }

    @Test
    void concurrentReplacementReturnsOneOldSnapshotThenIndependentRequestSeesNewSnapshot() throws Exception {
        Reader reader = reader(OWNER, true);
        Map<String, Object> old = interleave(reader, true);
        assertAggregate(old, 1, 11, 101, 21, 801);
        assertTrue(reader.service.sawReadOnlyRepeatableRead, "Actual annotation must be active at the SQL read");
        assertEquals(2L, jdbc.queryForObject("SELECT config_version FROM cms_topic WHERE id=?", Long.class, TOPIC));
        // A fresh controller invocation/SqlSession must not reuse the prior transaction's snapshot.
        assertAggregate(data(reader.controller.topicEditDetail(String.valueOf(TOPIC), null)), 2, 12, 102, 22, 802);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void negativeControlWithoutControllerTransactionReallyMixesOldTopicAndNewChildren() throws Exception {
        Reader reader = reader(OWNER, false);
        Map<String, Object> mixed = interleave(reader, false);
        CmsTopic topic = (CmsTopic) mixed.get("topic");
        assertEquals(Long.valueOf(1), topic.getConfigVersion());
        assertEquals(story(11), mixed.get("journeyStory"));
        assertEquals(Long.valueOf(11), mixed.get("openingChapterId"));
        assertChildren(mixed, 12, 102, 22, 802);
        assertFalse(reader.service.sawReadOnlyRepeatableRead);
    }

    @Test
    void ordinaryOwnerReadKeepsExistingShapeAndIdentifiers() {
        Reader reader = reader(OWNER, true);
        assertAggregate(data(reader.controller.topicEditDetail(String.valueOf(TOPIC), null)), 1, 11, 101, 21, 801);
    }

    @Test
    void strangerStillCannotReadTheAggregate() {
        Reader reader = reader(999L, true);
        AjaxResult result = reader.controller.topicEditDetail(String.valueOf(TOPIC), null);
        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals("无权编辑该主题", result.get(AjaxResult.MSG_TAG));
        assertFalse(result.containsKey(AjaxResult.DATA_TAG));
    }

    private Map<String, Object> interleave(Reader reader, boolean transactional) throws Exception {
        reader.service.pauseNextRead.set(true);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<AjaxResult> read = executor.submit(() -> reader.controller.topicEditDetail(String.valueOf(TOPIC), null));
        try {
            assertTrue(reader.service.topicRead.await(10, TimeUnit.SECONDS), "Reader must reach real topic SELECT");
            TransactionTemplate writer = new TransactionTemplate(manager);
            writer.setTimeout(10);
            writer.executeWithoutResult(status -> {
                if (transactional) {
                    assertNotEquals(reader.service.connectionId,
                            jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class), "Writer must use another physical connection");
                }
                jdbc.update("UPDATE cms_topic SET name='new-topic',config_version=2,journey_story_json=?,"
                        + "route_graph_json=?,update_time='2026-10-15 00:00:02' WHERE id=?", story(12), graph(102), TOPIC);
                jdbc.update("DELETE FROM cms_topic_node WHERE topic_id=?", TOPIC);
                jdbc.update("DELETE FROM cms_topic_chapter WHERE topic_id=?", TOPIC);
                jdbc.update("DELETE FROM oms_ticket WHERE owner_type=1 AND owner_id=?", TOPIC);
                jdbc.update("DELETE FROM b02_collaborator_rows WHERE data_id=?", TOPIC);
                insertChildren(12, 102, 22, 802);
            });
            reader.service.writerCommitted.countDown();
            return data(read.get(10, TimeUnit.SECONDS));
        } finally {
            reader.service.writerCommitted.countDown();
            read.cancel(true);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private Reader reader(long memberId, boolean transactional) {
        PausingTopicService topicService = new PausingTopicService();
        ReflectionTestUtils.setField(topicService, "cmsTopicMapper", session.getMapper(CmsTopicMapper.class));
        CmsTopicChapterServiceImpl chapters = new CmsTopicChapterServiceImpl();
        ReflectionTestUtils.setField(chapters, "cmsTopicChapterMapper", session.getMapper(CmsTopicChapterMapper.class));
        CmsTopicNodeServiceImpl nodes = new CmsTopicNodeServiceImpl();
        ReflectionTestUtils.setField(nodes, "cmsTopicNodeMapper", session.getMapper(CmsTopicNodeMapper.class));
        OmsTicketServiceImpl tickets = new OmsTicketServiceImpl();
        ReflectionTestUtils.setField(tickets, "omsTicketMapper", session.getMapper(OmsTicketMapper.class));
        ViewCollaboratorsServiceImpl collaborators = new ViewCollaboratorsServiceImpl();
        ReflectionTestUtils.setField(collaborators, "viewCollaboratorsMapper", session.getMapper(ViewCollaboratorsMapper.class));
        IdentityController target = new IdentityController(memberId);
        ReflectionTestUtils.setField(target, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(target, "cmsTopicChapterService", chapters);
        ReflectionTestUtils.setField(target, "cmsTopicNodeService", nodes);
        ReflectionTestUtils.setField(target, "omsTicketService", tickets);
        ReflectionTestUtils.setField(target, "viewCollaboratorsService", collaborators);
        if (!transactional) return new Reader(target, topicService);
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return new Reader((ApiTopicController) factory.getProxy(), topicService);
    }

    public static class IdentityController extends ApiTopicController {
        private final long memberId;
        public IdentityController(long memberId) { this.memberId = memberId; }
        @Override public Long getAppUserId() { return memberId; }
    }

    private class PausingTopicService extends CmsTopicServiceImpl {
        final AtomicBoolean pauseNextRead = new AtomicBoolean();
        final CountDownLatch topicRead = new CountDownLatch(1);
        final CountDownLatch writerCommitted = new CountDownLatch(1);
        volatile boolean sawReadOnlyRepeatableRead;
        volatile Long connectionId;

        @Override public CmsTopic selectCmsTopicById(Long id) {
            CmsTopic topic = super.selectCmsTopicById(id);
            if (pauseNextRead.compareAndSet(true, false)) {
                sawReadOnlyRepeatableRead = TransactionSynchronizationManager.isActualTransactionActive()
                        && TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                        && Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(
                                TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
                connectionId = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                topicRead.countDown();
                try {
                    if (!writerCommitted.await(15, TimeUnit.SECONDS)) throw new AssertionError("Writer did not commit");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
            return topic;
        }
    }

    private static class Reader {
        final ApiTopicController controller;
        final PausingTopicService service;
        Reader(ApiTopicController controller, PausingTopicService service) {
            this.controller = controller;
            this.service = service;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(AjaxResult result) {
        assertEquals(200, result.get(AjaxResult.CODE_TAG), result.toString());
        return (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
    }

    private void assertAggregate(Map<String, Object> data, long version, long chapter, long node, long ticket, long collaborator) {
        CmsTopic topic = (CmsTopic) data.get("topic");
        assertEquals(Long.valueOf(TOPIC), topic.getId());
        assertEquals(Long.valueOf(version), topic.getConfigVersion());
        assertEquals(version == 1 ? "old-topic" : "new-topic", topic.getName());
        assertEquals(story(chapter), data.get("journeyStory"));
        assertEquals(story(chapter), topic.getJourneyStoryJson());
        assertEquals(graph(node), topic.getRouteGraphJson());
        assertEquals(Long.valueOf(chapter), data.get("openingChapterId"));
        assertEquals("FULL", data.get("editScope"));
        assertChildren(data, chapter, node, ticket, collaborator);
    }

    @SuppressWarnings("unchecked")
    private void assertChildren(Map<String, Object> data, long chapterId, long nodeId, long ticketId, long collaborator) {
        List<CmsTopicChapter> chapters = (List<CmsTopicChapter>) data.get("chapters");
        assertEquals(1, chapters.size());
        assertEquals(Long.valueOf(chapterId), chapters.get(0).getId());
        assertEquals(1, chapters.get(0).getCmsTopicNodeList().size());
        assertEquals(Long.valueOf(nodeId), chapters.get(0).getCmsTopicNodeList().get(0).getId());
        assertEquals(Long.valueOf(chapterId), chapters.get(0).getCmsTopicNodeList().get(0).getChapterId());
        assertEquals(1, chapters.get(0).getBlocks().size());
        Map<String, Object> block = (Map<String, Object>) chapters.get(0).getBlocks().get(0);
        assertEquals(nodeId, ((Number) block.get("nodeId")).longValue());
        List<OmsTicket> tickets = (List<OmsTicket>) data.get("tickets");
        assertEquals(1, tickets.size());
        assertEquals(Long.valueOf(ticketId), tickets.get(0).getId());
        assertEquals(Collections.singletonList(collaborator), data.get("collaboratorIds"));
    }

    private void insertChildren(long chapter, long node, long ticket, long collaborator) {
        jdbc.update("INSERT INTO cms_topic_chapter(id,topic_id,name,sort_id,required,blocks) VALUES (?,?,'chapter',1,1,?)",
                chapter, TOPIC, "[{\"type\":\"node\",\"nodeId\":" + node + "}]");
        jdbc.update("INSERT INTO cms_topic_node(id,topic_id,chapter_id,name,status,sort_id) VALUES (?,?,?,'node',1,1)",
                node, TOPIC, chapter);
        jdbc.update("INSERT INTO oms_ticket(id,member_id,owner_type,owner_id,name,mode,del_flag) VALUES (?,?,1,?,'ticket',1,0)",
                ticket, OWNER, TOPIC);
        jdbc.update("INSERT INTO b02_collaborator_rows(id,data_type,data_id,member_id) VALUES (1,1,?,?)", TOPIC, collaborator);
    }

    private String story(long chapter) { return "{\"openingChapterId\":" + chapter + "}"; }
    private String graph(long node) { return "{\"startNodeId\":" + node + "}"; }

    private void createFixtureSchema() throws Exception {
        String base = new String(Files.readAllBytes(Paths.get("../sql/base_schema_20260818.sql")), StandardCharsets.UTF_8);
        for (String table : new String[]{"cms_topic", "cms_topic_chapter", "cms_topic_node", "oms_ticket", "cms_activity", "merchant_chapter_offer"}) {
            int start = base.indexOf("CREATE TABLE `" + table + "` (");
            assertTrue(start >= 0, "Missing fixture table " + table);
            java.util.regex.Matcher end = java.util.regex.Pattern.compile(";\\s*\\n").matcher(base);
            assertTrue(end.find(start), "Missing fixture terminator " + table);
            jdbc.execute(base.substring(start, end.start()));
        }
        // Post-base columns consumed by CURRENT production mapper XML; test-schema setup only.
        jdbc.execute("ALTER TABLE cms_topic ADD circle_theme_code VARCHAR(24), ADD source_template_topic_id BIGINT,"
                + " ADD complete_rule_json LONGTEXT, ADD route_mode VARCHAR(20), ADD route_graph_json LONGTEXT,"
                + " ADD config_version BIGINT, ADD published_route_graph_json LONGTEXT, ADD published_config_version BIGINT,"
                + " ADD game_module_config_json LONGTEXT, ADD journey_rules_json LONGTEXT, ADD journey_story_json LONGTEXT,"
                + " ADD theme_runtime_config_json LONGTEXT, ADD published_theme_runtime_config_json LONGTEXT,"
                + " ADD beta_flag TINYINT, ADD team_mode TINYINT, ADD team_max_members INT,"
                + " ADD club_coop_open TINYINT, ADD club_pinned TINYINT, ADD club_member_only TINYINT");
        jdbc.execute("ALTER TABLE cms_topic_chapter ADD atmosphere_preset VARCHAR(32), ADD audio_url VARCHAR(500),"
                + " ADD finish_time DATETIME, ADD circle_candidate_code VARCHAR(24), ADD circle_role VARCHAR(24),"
                + " ADD circle_policy VARCHAR(24), ADD circle_enabled TINYINT, ADD need_group TINYINT");
        jdbc.execute("ALTER TABLE cms_topic_node ADD unlock_after_node_id BIGINT");
        jdbc.execute("ALTER TABLE merchant_chapter_offer ADD withdraw_reason VARCHAR(500)");
        jdbc.execute("ALTER TABLE oms_ticket ADD source_ticket_id BIGINT, ADD sale_start_time DATETIME,"
                + " ADD sale_end_time DATETIME, ADD meeting_point_address VARCHAR(255),"
                + " ADD refund_supported TINYINT, ADD sync_with_theme TINYINT");
        // The repository has no view_collaborators DDL. Reproduce its mapper projection over fixture rows.
        jdbc.execute("CREATE TABLE b02_collaborator_rows(id BIGINT PRIMARY KEY,data_type BIGINT,data_id BIGINT,member_id BIGINT,"
                + "create_by VARCHAR(64),create_time DATETIME,update_by VARCHAR(64),update_time DATETIME,"
                + "member_nickname VARCHAR(64),member_avatar VARCHAR(255),member_phone VARCHAR(64),"
                + "member_real_name VARCHAR(64),member_sex INT,member_status INT) ENGINE=InnoDB");
        jdbc.execute("CREATE VIEW view_collaborators AS SELECT * FROM b02_collaborator_rows");
    }
}
