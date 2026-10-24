package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.TopicQueryService;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageInterceptor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.*;
import com.chengyinhub.business.it.MybatisItDatabase;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real public list + PageHelper + node XML on disposable MySQL. No mocked query/service. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PublicTopicStationCountMySqlIT {
    private String database;
    private JdbcTemplate admin;
    private CmsTopicNodeMapper nodes;
    private TopicQueryService service;
    private final QueryCounter counter = new QueryCounter();

    @BeforeAll void setup() throws Exception {
        assertTrue("127.0.0.1".equals(MybatisItDatabase.host()) || "localhost".equals(MybatisItDatabase.host()),
                "Only localhost scratch MySQL");
        String suite = "nodecount_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String url = MybatisItDatabase.url(suite);
        database = MybatisItDatabase.databaseName(suite);
        admin = new JdbcTemplate(new DriverManagerDataSource(url.replace("/" + database + "?", "/mysql?"), MybatisItDatabase.user(), MybatisItDatabase.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("SELECT VERSION()", String.class)));
        DriverManagerDataSource source = new DriverManagerDataSource(url, MybatisItDatabase.user(), MybatisItDatabase.password());
        try (Connection connection = source.getConnection(); Statement st = connection.createStatement()) {
            schema(st);
            for (int i = 1; i <= 24; i++) {
                st.execute("INSERT INTO cms_topic (id,name,status,user_status,merchant_status,del_flag,is_template,location_count,route_mode)"
                        + " VALUES (" + i + ",'synthetic',1,1,1,0,0,99,'LINEAR')");
                // Topic 24 deliberately has no nodes. Every other topic has two chapters.
                if (i == 24) continue;
                for (int j = 0; j < 10; j++) {
                    String merchant = j < 2 || j == 7 || j == 8 ? "NULL" : "700";
                    String audit = j == 0 || j == 6 ? "NULL" : j < 4 || j == 9 ? "1" : j == 4 || j == 8 ? "0" : "2";
                    String enabled = j == 2 || j == 8 ? "0" : j == 9 ? "NULL" : "1";
                    // Eligible: organizer NULL/approved/rejected audit (0,1,7) + approved merchant (3).
                    // Disabled/null-status nodes and pending/rejected/unaudited merchants never count.
                    st.execute("INSERT INTO cms_topic_node(id,topic_id,chapter_id,merchant_member_id,node_audit_status,status,sort_id)"
                            + " VALUES(" + (i * 100 + j) + "," + i + "," + (i * 10 + j % 2) + ","
                            + merchant + "," + audit + "," + enabled + "," + j + ")");
                }
            }
            st.execute("UPDATE cms_topic SET route_mode='BRANCH_GRAPH', published_route_graph_json='{\"schemaVersion\":1}' WHERE id=23");
            st.execute("INSERT INTO cms_topic_like(id,data_id,member_id) VALUES(1,23,901),(2,24,901),(3,900,901)");
            // Off-page data must not accidentally enter the aggregate or public topic page.
            st.execute("INSERT INTO cms_topic (id,member_id,name,status,user_status,del_flag,is_template,location_count) VALUES(900,902,'private',0,1,0,0,99)");
            st.execute("INSERT INTO cms_topic_node(id,topic_id,merchant_member_id,status) VALUES(9000,900,NULL,1)");
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/CmsTopicMapper.xml"),
                new ClassPathResource("mapper/business/CmsTopicNodeMapper.xml"));
        PageInterceptor paging = new PageInterceptor();
        Properties properties = new Properties(); properties.setProperty("helperDialect", "mysql");
        paging.setProperties(properties);
        factory.setPlugins(counter, paging);
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        nodes = session.getMapper(CmsTopicNodeMapper.class);
        service = new TopicQueryService();
        ReflectionTestUtils.setField(service, "cmsTopicMapper", session.getMapper(CmsTopicMapper.class));
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodes);
    }

    @AfterAll void cleanup() {
        PageHelper.clearPage();
        if (database != null) {
            admin.execute("DROP DATABASE " + database);
            assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                    Integer.class, database));
        }
    }

    @Test void multiplePageSizesUseExactlyOneNodeAggregateForEnabledPublicationQualifiedTotal() {
        for (int pageSize : new int[]{1, 5, 20, 24}) {
            counter.sql.clear();
            PageHelper.startPage(1, pageSize);
            List<CmsTopic> page;
            try { page = service.selectPublicCmsTopicList(new CmsTopic()); }
            finally { PageHelper.clearPage(); }
            assertEquals(pageSize, page.size());
            Page<?> metadata = (Page<?>) page;
            assertEquals(24L, metadata.getTotal());
            assertEquals(1, metadata.getPageNum());
            assertEquals(pageSize, metadata.getPageSize());
            assertEquals(3, counter.sql.size(), "one page count + page select + aggregate; pageSize=" + pageSize);
            assertEquals(1, counter.sql.stream().filter(s -> s.contains("from cms_topic_node")).count());
            assertTrue(counter.sql.get(2).contains("count(*) as location_count"));
            assertEquals(0L, page.get(0).getLocationCount());
            for (CmsTopic topic : page) {
                CmsTopicNode legacy = new CmsTopicNode(); legacy.setTopicId(topic.getId()); legacy.setStatus(1L);
                List<CmsTopicNode> oldNodes = nodes.selectCmsTopicNodeList(legacy);
                assertEquals((long) oldNodes.size(), topic.getLocationCount());
                assertEquals(topic.getId() == 24 ? 0L : 4L, topic.getLocationCount());
                // The total is across all chapters, without consuming any player visibility state.
                long chapterDetailCount = 0;
                for (long chapterId : new long[]{topic.getId() * 10, topic.getId() * 10 + 1}) {
                    CmsTopicNode chapter = new CmsTopicNode(); chapter.setChapterId(chapterId); chapter.setStatus(1L);
                    chapterDetailCount += nodes.selectCmsTopicNodeList(chapter).size();
                }
                assertEquals(chapterDetailCount, topic.getLocationCount());
            }
        }
    }

    @Test void secondAndOutOfRangePagesPreserveMetadata() {
        counter.sql.clear();
        PageHelper.startPage(2, 5);
        List<CmsTopic> second;
        try { second = service.selectPublicCmsTopicList(new CmsTopic()); }
        finally { PageHelper.clearPage(); }
        assertEquals(Arrays.asList(19L, 18L, 17L, 16L, 15L), second.stream().map(CmsTopic::getId).toList());
        Page<?> metadata = (Page<?>) second;
        assertEquals(24L, metadata.getTotal());
        assertEquals(2, metadata.getPageNum());
        assertEquals(5, metadata.getPageSize());
        assertEquals(3, counter.sql.size());
        assertTrue(second.stream().allMatch(t -> Long.valueOf(4L).equals(t.getLocationCount())));
        counter.sql.clear();
        PageHelper.startPage(6, 5);
        List<CmsTopic> beyond;
        try { beyond = service.selectPublicCmsTopicList(new CmsTopic()); }
        finally { PageHelper.clearPage(); }
        assertTrue(beyond.isEmpty());
        assertEquals(24L, ((Page<?>) beyond).getTotal());
        assertEquals(6, ((Page<?>) beyond).getPageNum());
        assertEquals(5, ((Page<?>) beyond).getPageSize());
        assertEquals(0, counter.sql.stream().filter(sql -> sql.contains("from cms_topic_node")).count());
    }

    @Test void actualDetailServiceRefreshesStoredCountOnlyAfterVisibilityCheck() {
        // Every public row was persisted with location_count=99, including the empty topic.
        List<CmsTopic> cards = service.selectPublicCmsTopicList(new CmsTopic());
        for (long id : new long[]{23, 24}) {
            counter.sql.clear();
            CmsTopic detail = service.selectCmsTopicDetailById(id, 901L);
            assertNotNull(detail);
            assertEquals(id == 24 ? 0L : 4L, detail.getLocationCount());
            assertEquals(cards.stream().filter(t -> t.getId() == id).findFirst().orElseThrow().getLocationCount(),
                    detail.getLocationCount());
            assertEquals(2, counter.sql.size());
        }
        counter.sql.clear();
        assertNull(service.selectCmsTopicDetailById(900L, 901L));
        assertNull(service.selectCmsTopicDetailById(900L, null));
        assertNull(service.selectCmsTopicDetailById(999L, 901L));
        assertEquals(0, counter.sql.stream().filter(sql -> sql.contains("from cms_topic_node")).count());
        counter.sql.clear();
        assertEquals(1L, service.selectCmsTopicDetailById(900L, 902L).getLocationCount());
        assertEquals(2, counter.sql.size());
    }

    @Test void likedAndManagementPagesUseSameTotalWithExistingFiltersAndConstantQueryBudget() {
        for (int pageSize : new int[]{1, 5, 20}) {
            CmsTopic liked = new CmsTopic(); liked.setIsLike(1); liked.setIsLikeMemberId(901L);
            counter.sql.clear(); PageHelper.startPage(1, pageSize);
            List<CmsTopic> rows;
            try { rows = service.selectPublicCmsTopicList(liked); } finally { PageHelper.clearPage(); }
            assertEquals(2L, ((Page<?>) rows).getTotal(), "liked private topic stays denied");
            assertEquals(3, counter.sql.size());
            assertTrue(rows.stream().allMatch(t -> t.getId() == 24 ? t.getLocationCount() == 0L : t.getLocationCount() == 4L));
            CmsTopic owner = new CmsTopic(); owner.setMemberId(902L); owner.setDelFlag(0);
            counter.sql.clear(); PageHelper.startPage(1, pageSize);
            try { rows = service.selectCmsTopicList(owner); } finally { PageHelper.clearPage(); }
            assertEquals(1L, ((Page<?>) rows).getTotal());
            assertEquals(900L, rows.get(0).getId());
            assertEquals(1L, rows.get(0).getLocationCount());
            assertEquals(3, counter.sql.size());
        }
    }

    @Test void merchantVisibleAndManagedDetailCountOnlyAfterExistingAccessGate() {
        counter.sql.clear();
        assertEquals(4L, service.selectMerchantVisibleCmsTopicById(23L).getLocationCount());
        assertEquals(2, counter.sql.size());
        counter.sql.clear();
        assertNull(service.selectMerchantVisibleCmsTopicById(900L));
        assertNull(service.selectMerchantManagedCmsTopicById(900L, 901L));
        assertEquals(0, counter.sql.stream().filter(sql -> sql.contains("from cms_topic_node")).count());
        admin.update("INSERT INTO " + database + ".merchant_chapter_offer(id,topic_id,chapter_id,merchant_id,status) VALUES(90,900,90,902,1)");
        admin.update("INSERT INTO " + database + ".merchant_chapter_application(id,topic_id,chapter_id,merchant_id,status,del_flag) VALUES(90,900,90,902,1,0)");
        try {
            counter.sql.clear();
            assertEquals(1L, service.selectMerchantManagedCmsTopicById(900L, 902L).getLocationCount());
            assertEquals(2, counter.sql.size());
        } finally {
            admin.update("DELETE FROM " + database + ".merchant_chapter_application WHERE id=90");
            admin.update("DELETE FROM " + database + ".merchant_chapter_offer WHERE id=90");
        }
    }

    @Test void publicTemplateShelfAndDetailUseRealEligibilityAndAggregateWithoutPrivateFallback() {
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", org.mockito.Mockito.mock(CmsTopicChapterMapper.class));
        ReflectionTestUtils.setField(service, "cmsTopicTemplateMetaMapper", org.mockito.Mockito.mock(CmsTopicTemplateMetaMapper.class));
        admin.update("UPDATE " + database + ".cms_topic SET is_template=1 WHERE id IN (23,24,900)");
        admin.update("INSERT INTO " + database + ".cms_topic_template_meta(topic_id,del_flag,template_status) VALUES(23,0,'VERIFIED'),(24,0,'VERIFIED'),(900,0,'VERIFIED')");
        try {
            counter.sql.clear();
            var shelf = service.buildTopicTemplateListVO(false);
            assertEquals(2, shelf.size(), "private owner draft never enters public template shelf");
            assertEquals(0L, shelf.get(0).getLocationCount());
            assertEquals(4L, shelf.get(1).getLocationCount());
            assertEquals(1, counter.sql.stream().filter(sql -> sql.contains("count(*) as location_count")).count());
            for (long id : new long[]{23L, 24L}) {
                counter.sql.clear();
                var detail = service.buildPublicTopicTemplateDetail(id, null, false, null);
                assertEquals(id == 23L ? 4L : 0L, detail.getLocationCount());
                assertTrue(detail.getChapters().isEmpty());
                assertEquals(2, counter.sql.size(), "one public eligibility row + one aggregate");
            }
            counter.sql.clear();
            assertNull(service.buildPublicTopicTemplateDetail(900L, 902L, false, null), "even owner uses public eligibility on this endpoint");
            assertNull(service.buildPublicTopicTemplateDetail(1L, null, false, null), "ordinary non-template cannot use template info");
            assertEquals(0, counter.sql.stream().filter(sql -> sql.contains("from cms_topic_node")).count());
            admin.update("UPDATE " + database + ".cms_topic_template_meta SET template_status='EXPERIMENTAL' WHERE topic_id=23");
            counter.sql.clear();
            // 2026-10-22 owner 裁决:货架/info 不再要求 VERIFIED,非 VERIFIED(此处 EXPERIMENTAL)的已发布模板同样可见
            assertNotNull(service.buildPublicTopicTemplateDetail(23L, null, false, null));
        } finally {
            admin.update("DELETE FROM " + database + ".cms_topic_template_meta WHERE topic_id IN (23,24,900)");
            admin.update("UPDATE " + database + ".cms_topic SET is_template=0 WHERE id IN (23,24,900)");
        }
    }

    @Test void emptyPageDoesNotIssueNodeQueryAndEmptyIdsNeverScanOtherTopics() {
        counter.sql.clear();
        CmsTopic missing = new CmsTopic(); missing.setName("no-matching-synthetic-topic");
        PageHelper.startPage(1, 20);
        try { assertTrue(service.selectPublicCmsTopicList(missing).isEmpty()); }
        finally { PageHelper.clearPage(); }
        assertEquals(0, counter.sql.stream().filter(s -> s.contains("from cms_topic_node")).count());
    }

    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
    public static class QueryCounter implements Interceptor {
        final List<String> sql = new ArrayList<>();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            sql.add(((StatementHandler) invocation.getTarget()).getBoundSql().getSql().toLowerCase(Locale.ROOT));
            return invocation.proceed();
        }
    }

    private void schema(Statement st) throws Exception {
        st.execute("DROP TABLE IF EXISTS cms_topic");
        st.execute("CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, member_id BIGINT, club_id BIGINT,"
                + " topic_type INT, product_type INT, circle_theme_code VARCHAR(24),"
                + " source_template_topic_id BIGINT, complete_rule_json JSON, route_mode VARCHAR(20),"
                + " route_graph_json JSON, config_version BIGINT, published_route_graph_json JSON,"
                + " published_config_version BIGINT, game_module_config_json JSON,"
                + " theme_runtime_config_json JSON, published_theme_runtime_config_json JSON,"
                + " journey_rules_json JSON,"
                + " journey_story_json JSON,"
                + " fee_platform_rate DECIMAL(10,4), fee_guarantee_base_rate DECIMAL(10,4),"
                + " fee_guarantee_step_rate DECIMAL(10,4), fee_guarantee_max_merchant TINYINT,"
                + " topic_id BIGINT, name VARCHAR(120), subtitle VARCHAR(120), description TEXT,"
                + " address_name VARCHAR(120), total_time INT, total_mileage DECIMAL(10,2),"
                + " location_count INT, template_count INT, start_date DATETIME, end_date DATETIME,"
                + " img_url VARCHAR(255), img_arr TEXT, category_ids VARCHAR(100), status TINYINT,"
                + " reason VARCHAR(255), user_status TINYINT, merchant_status TINYINT,"
                + " open_club_pool TINYINT, merchant_sign_up_start_date DATETIME,"
                + " merchant_sign_up_end_date DATETIME, del_flag TINYINT, is_sync TINYINT,"
                + " is_recommend TINYINT, check_status TINYINT, is_settlement TINYINT,"
                + " publish_mode VARCHAR(16), beta_flag TINYINT, audio_url VARCHAR(500),"
                + " audio_duration INT, self_play TINYINT, self_play_price DECIMAL(10,2),"
                + " self_play_quota INT, team_mode TINYINT, team_max_members INT, xp_budget INT,"
                + " finish_medal_name VARCHAR(40), finish_medal_img VARCHAR(255),"
                + " complete_reward_coupon_id BIGINT, recruit_deadline DATETIME,"
                + " club_coop_open TINYINT NOT NULL DEFAULT 1, club_pinned TINYINT NOT NULL DEFAULT 0,"
                + " club_member_only TINYINT NOT NULL DEFAULT 0,"
                + " recruit_extend_count TINYINT, lifecycle TINYINT, sale_end_at DATETIME,"
                + " selling_started_at DATETIME, source_topic_id BIGINT, circle_reviewed_at DATETIME,"
                + " is_template TINYINT, create_by VARCHAR(64), create_time DATETIME,"
                + " update_by VARCHAR(64), update_time DATETIME)");
        st.execute("CREATE TABLE cms_topic_like(id BIGINT PRIMARY KEY,data_id BIGINT,member_id BIGINT)");
        st.execute("CREATE TABLE cms_topic_template_meta(topic_id BIGINT PRIMARY KEY,del_flag INT,template_status VARCHAR(20))");
        st.execute("CREATE TABLE merchant_chapter_application(id BIGINT PRIMARY KEY,topic_id BIGINT,chapter_id BIGINT,merchant_id BIGINT,status INT,del_flag INT)");
        st.execute("DROP TABLE IF EXISTS club");
        st.execute("CREATE TABLE club (id BIGINT PRIMARY KEY, member_id BIGINT, del_flag INT)");
        // 「只对成员可见」那道闸引用成员表与报名表。本 fixture 的主题 club_member_only 全是 0、
        // 短路后取不到数,但 MySQL 解析阶段同样要求表存在 —— 缺了会假红。
        st.execute("DROP TABLE IF EXISTS club_member");
        st.execute("CREATE TABLE club_member (id BIGINT PRIMARY KEY, club_id BIGINT, member_id BIGINT,"
                + " status INT, del_flag INT)");
        // 「已付款的人放行」那条现在按 owner_type 分两档,场次票要经 cms_activity 回溯源主题。
        st.execute("DROP TABLE IF EXISTS cms_activity");
        st.execute("CREATE TABLE cms_activity (id BIGINT PRIMARY KEY, topic_id BIGINT, club_id BIGINT,"
                + " del_flag TINYINT NOT NULL DEFAULT 0)");
        st.execute("DROP TABLE IF EXISTS cms_registration");
        st.execute("CREATE TABLE cms_registration (id BIGINT PRIMARY KEY, owner_type INT, owner_id BIGINT,"
                + " member_id BIGINT, topic_id BIGINT, del_flag INT NOT NULL DEFAULT 0,"
                + " registration_status INT, payment_status INT)");
        // 圈层闸的子查询要引用这三张表 —— 本 fixture 的主题 circle_theme_code 全是 NULL、
        // 短路后根本不会取数,但 MySQL 解析阶段仍要求表存在,缺了会假红。
        st.execute("DROP TABLE IF EXISTS merchant_chapter_offer");
        st.execute("CREATE TABLE merchant_chapter_offer (id BIGINT PRIMARY KEY, topic_id BIGINT,"
                + " chapter_id BIGINT, merchant_id BIGINT, status INT, valid_end DATETIME, withdraw_reason VARCHAR(255))");
        st.execute("DROP TABLE IF EXISTS merchant_circle_supply_profile");
        st.execute("CREATE TABLE merchant_circle_supply_profile (id BIGINT PRIMARY KEY,"
                + " offer_id BIGINT, is_current TINYINT, checked_at DATETIME)");
        st.execute("DROP TABLE IF EXISTS cms_topic_chapter");
        st.execute("CREATE TABLE cms_topic_chapter (id BIGINT PRIMARY KEY, circle_enabled TINYINT)");
        st.execute("CREATE TABLE cms_topic_node (id BIGINT PRIMARY KEY, topic_id BIGINT, chapter_id BIGINT, name VARCHAR(255), description VARCHAR(255), address VARCHAR(255), longitude VARCHAR(255), latitude VARCHAR(255), img_url VARCHAR(255), node_time BIGINT, template_id BIGINT, merchant_member_id BIGINT, node_audit_status BIGINT, node_audit_reason VARCHAR(255), node_audit_member_id BIGINT, node_audit_time DATETIME, checkin_code VARCHAR(255), business_time VARCHAR(255), status BIGINT, sort_id BIGINT, xp_value BIGINT, hook_text VARCHAR(255), card_hook_long VARCHAR(255), fragment_text VARCHAR(255), unlock_after_node_id BIGINT, create_by VARCHAR(255), create_time DATETIME, update_by VARCHAR(255), update_time DATETIME) ENGINE=InnoDB");
    }
}
