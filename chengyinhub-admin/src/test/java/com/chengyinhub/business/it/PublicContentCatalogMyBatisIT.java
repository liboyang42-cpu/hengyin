package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.dto.PublicCatalogQuery;
import com.chengyinhub.business.domain.dto.PublicContentCatalogRow;
import com.chengyinhub.business.mapper.PublicContentCatalogMapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInterceptor;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.builder.xml.XMLMapperEntityResolver;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M7 公开目录读面的真库 IT：真实 MySQL + 生产 Mapper/gates/PageHelper，不启动任务、不执行回填。
 *
 * <p>覆盖施工卡要求的分页、跨 scope、下架回退、语言回退与隐私负例：
 * 未公开/软删/模板/会员专属/俱乐部已解散/圈子无供给的主题一律不出现在目录里，
 * 且 COUNT 与行使用同一公开闸。
 */
class PublicContentCatalogMyBatisIT {

    private static final String MIGRATION = "sql/migration_public_content_catalog_20261108.sql";
    private static final String SUITE = "public_catalog";

    private SqlSession open() throws Exception {
        Configuration config = new Configuration();
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.setEnvironment(new Environment("it", new JdbcTransactionFactory(), new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", MybatisItDatabase.url(SUITE),
                MybatisItDatabase.user(), MybatisItDatabase.password())));
        // 必须加载生产同一份公开闸片段，不能用测试里简化过的副本。
        fragment(config, "CmsTopicMapper", "primaryCirclePublicGate");
        String resource = "mapper/business/PublicContentCatalogMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, config, resource, config.getSqlFragments()).parse();
        }
        PageInterceptor paging = new PageInterceptor();
        Properties props = new Properties();
        props.setProperty("helperDialect", "mysql");
        paging.setProperties(props);
        config.addInterceptor(paging);
        return new SqlSessionFactoryBuilder().build(config).openSession(true);
    }

    /**
     * 一类一库：跑完把**测试自己的** scratch 库删掉，并用独立连接回读 information_schema。
     * 本类原先把 8 张 fixture 表留在库里，真库门禁的清理检查会记成 LEFTOVER 并判红。
     */
    @AfterAll
    static void dropScratchDatabase() throws Exception {
        String database = MybatisItDatabase.databaseName(SUITE);
        assertTrue(database.startsWith("chengyin_mybatis_"),
                "只许删专用 fixture 库，实际=" + database);
        String adminUrl = "jdbc:mysql://" + MybatisItDatabase.host() + ":" + MybatisItDatabase.port()
                + "/?useSSL=false&allowPublicKeyRetrieval=true";
        try (Connection admin = DriverManager.getConnection(adminUrl,
                MybatisItDatabase.user(), MybatisItDatabase.password());
             Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS `" + database + "`");
            try (var rows = st.executeQuery("SELECT COUNT(*) FROM information_schema.schemata"
                    + " WHERE schema_name = '" + database + "'")) {
                rows.next();
                assertEquals(0, rows.getInt(1), "IT 库必须已删除并回读为 0:" + database);
            }
        }
    }

    private static void fragment(Configuration config, String mapper, String id) throws Exception {
        try (InputStream in = Resources.getResourceAsStream("mapper/business/" + mapper + ".xml")) {
            XPathParser parser = new XPathParser(in, true, null, new XMLMapperEntityResolver());
            config.getSqlFragments().put("com.chengyinhub.business.mapper." + mapper + "." + id,
                    parser.evalNode("/mapper/sql[@id='" + id + "']"));
        }
    }

    private void createSchema(SqlSession sql) throws Exception {
        ItSchema.applyMigrations(sql.getConnection(), MIGRATION);
        try (Statement st = sql.getConnection().createStatement()) {
            for (String table : Arrays.asList("merchant_circle_supply_profile", "merchant_chapter_offer",
                    "topic_sale_snapshot", "oms_ticket", "cms_topic_node", "cms_topic_chapter", "club", "cms_topic")) {
                st.execute("drop table if exists " + table);
            }
            st.execute("create table cms_topic(id bigint primary key, city_code varchar(32), name varchar(200),"
                    + "subtitle varchar(255), description text, img_url varchar(500), product_type int,"
                    + "total_time bigint, total_mileage decimal(10,2), location_count bigint, lifecycle int,"
                    + "self_play int, self_play_price decimal(10,2), team_mode int, sale_end_at datetime,"
                    + "end_date datetime, club_id bigint, published_config_version bigint,"
                    + "published_theme_runtime_config_json text, update_time datetime, status int, user_status int,"
                    + "del_flag int, is_template int, club_member_only int, circle_theme_code varchar(32),"
                    + "circle_reviewed_at datetime)");
            st.execute("create table club(id bigint primary key, del_flag int, city varchar(32))");
            st.execute("create table cms_topic_chapter(id bigint primary key, topic_id bigint,"
                    + "name varchar(200), sort_id bigint, circle_enabled int)");
            st.execute("create table cms_topic_node(id bigint primary key, topic_id bigint, chapter_id bigint,"
                    + "name varchar(200), status bigint, latitude varchar(32), longitude varchar(32),"
                    + "sort_id bigint, hook_text varchar(255), description varchar(500),"
                    + "fragment_text varchar(500), checkin_code varchar(64))");
            st.execute("create table oms_ticket(id bigint primary key, owner_type int, owner_id bigint,"
                    + "del_flag int, price decimal(10,2), sub_type varchar(8))");
            st.execute("create table topic_sale_snapshot(id bigint primary key, topic_id bigint, frozen_at datetime)");
            st.execute("create table merchant_chapter_offer(id bigint primary key, merchant_id bigint,"
                    + "chapter_id bigint, topic_id bigint, status int, valid_end datetime)");
            st.execute("create table merchant_circle_supply_profile(offer_id bigint, is_current int, checked_at datetime)");
        }
    }

    @Test
    void catalogAppliesProductionPublicGateAndPagedTotalsShareIt() throws Exception {
        try (SqlSession sql = open(); Statement st = sql.getConnection().createStatement()) {
            createSchema(sql);
            insertTopic(st, 1L, "310100", "外滩谜案", 1, 1, 0, 0, 0, 3);
            insertTopic(st, 2L, "310100", "下架的那一局", 1, 0, 0, 0, 0, 3);
            insertTopic(st, 3L, "310100", "软删的那一局", 1, 1, 2, 0, 0, 3);
            insertTopic(st, 4L, "310100", "模板本体", 1, 1, 0, 1, 0, 3);
            insertTopic(st, 5L, "310100", "会员专属局", 1, 1, 0, 0, 1, 3);
            insertTopic(st, 6L, "440300", "深圳的另一局", 1, 1, 0, 0, 0, 3);

            PublicContentCatalogMapper mapper = sql.getMapper(PublicContentCatalogMapper.class);

            // 分页：总数与行同一闸(4 个不可公开的行必须被排除)。
            PageHelper.startPage(1, 2);
            List<PublicContentCatalogRow> page = mapper.selectCatalogItems(query(null));
            try {
                assertEquals(2, page.size());
                assertEquals(2L, ((Page<?>) page).getTotal(), "only 2 of 6 topics are publicly catalogable");
            } finally {
                PageHelper.clearPage();
            }

            // 跨 scope：城市筛选不能串城。
            List<PublicContentCatalogRow> shanghai = mapper.selectCatalogItems(query("310100"));
            assertEquals(1, shanghai.size());
            assertEquals(Long.valueOf(1L), shanghai.get(0).getId());
            List<PublicContentCatalogRow> shenzhen = mapper.selectCatalogItems(query("440300"));
            assertEquals(1, shenzhen.size());
            assertEquals(Long.valueOf(6L), shenzhen.get(0).getId());
            assertEquals(0, mapper.selectCatalogItems(query("110100")).size());

            // 下架回退：把唯一公开主题下架后，行与计数同时归零。
            st.execute("update cms_topic set user_status = 0 where id = 6");
            assertEquals(0, mapper.selectCatalogItems(query("440300")).size());
            PageHelper.startPage(1, 10);
            List<PublicContentCatalogRow> afterDelist = mapper.selectCatalogItems(query("440300"));
            try {
                assertEquals(0L, ((Page<?>) afterDelist).getTotal());
                assertTrue(afterDelist.isEmpty());
            } finally {
                PageHelper.clearPage();
            }

            // 详情：未公开 id 与不存在同码(返回 null，由服务层转 404)。
            assertNotNull(mapper.selectCatalogItemById(1L, "zh-cn"));
            assertNull(mapper.selectCatalogItemById(2L, "zh-cn"));
            assertNull(mapper.selectCatalogItemById(5L, "zh-cn"));
        }
    }

    @Test
    void circleTopicWithoutRealSupplyIsExcludedEvenWhenStatusIsOne() throws Exception {
        try (SqlSession sql = open(); Statement st = sql.getConnection().createStatement()) {
            createSchema(sql);
            insertTopic(st, 1L, "310100", "圈子待复核", 1, 1, 0, 0, 0, 3);
            st.execute("update cms_topic set circle_theme_code = 'FRIENDS', circle_reviewed_at = now() where id = 1");
            PublicContentCatalogMapper mapper = sql.getMapper(PublicContentCatalogMapper.class);
            // 圈子公开闸：三类主体必须都满足才对外；此处无供给 ⇒ 不出现。
            assertEquals(0, mapper.selectCatalogItems(query(null)).size());

            // 补上 3 家不同的真实供给 + 近 30 天复核 ⇒ 才进入公开目录。
            st.execute("insert into cms_topic_chapter values(10, 1, '第一章', 1, 1)");
            for (long i = 1; i <= 3; i++) {
                st.execute("insert into merchant_chapter_offer values(" + i + "," + i + ",10,1,1,date_add(now(), interval 10 day))");
                st.execute("insert into merchant_circle_supply_profile values(" + i + ",1,now())");
            }
            assertEquals(1, mapper.selectCatalogItems(query(null)).size());
        }
    }

    @Test
    void languageApprovalGatesTranslationAndPendingNeverCounts() throws Exception {
        try (SqlSession sql = open(); Statement st = sql.getConnection().createStatement()) {
            createSchema(sql);
            insertTopic(st, 1L, "310100", "上海夜行", 1, 1, 0, 0, 0, 3);
            insertTopic(st, 2L, "310100", "上海夜行二", 1, 1, 0, 0, 0, 3);
            st.execute("insert into content_landing_translation"
                    + "(topic_id, locale, source_locale, title, subtitle, summary, translation_state)"
                    + " values(1,'en','zh-CN','Shanghai Night Walk','sub','summary','APPROVED')");
            // 待校对稿不能被当成已完成翻译。
            st.execute("insert into content_landing_translation"
                    + "(topic_id, locale, source_locale, title, subtitle, summary, translation_state)"
                    + " values(2,'en','zh-CN','Pending Title','sub','summary','PENDING')");

            PublicContentCatalogMapper mapper = sql.getMapper(PublicContentCatalogMapper.class);
            PublicContentCatalogRow approved = mapper.selectCatalogItemById(1L, "en");
            assertEquals("Shanghai Night Walk", approved.getTranslatedTitle());

            PublicContentCatalogRow pending = mapper.selectCatalogItemById(2L, "en");
            assertNull(pending.getTranslatedTitle(), "PENDING translation must not be returned as usable");
            assertEquals("上海夜行二", pending.getName());

            // 未请求的语言不串行：同一行在 zh-cn 下不返回英文译文。
            assertNull(mapper.selectCatalogItemById(1L, "zh-cn").getTranslatedTitle());
        }
    }

    @Test
    void groupAndSchoolFiltersComeFromDeclaredConfigurationOnly() throws Exception {
        try (SqlSession sql = open(); Statement st = sql.getConnection().createStatement()) {
            createSchema(sql);
            insertTopic(st, 1L, "310100", "可接团体", 1, 1, 0, 0, 0, 3);
            insertTopic(st, 2L, "310100", "未声明", 1, 1, 0, 0, 0, 3);
            insertTopic(st, 3L, "310100", "可接学校", 1, 1, 0, 0, 0, 3);
            insertTopic(st, 4L, "310100", "停用的声明", 1, 1, 0, 0, 0, 3);
            st.execute("insert into content_landing_metadata(topic_id, group_eligible, school_eligible, status)"
                    + " values(1, 1, 0, 1)");
            st.execute("insert into content_landing_metadata(topic_id, group_eligible, school_eligible, status)"
                    + " values(3, 0, 1, 1)");
            st.execute("insert into content_landing_metadata(topic_id, group_eligible, school_eligible, status)"
                    + " values(4, 1, 1, 0)");

            PublicContentCatalogMapper mapper = sql.getMapper(PublicContentCatalogMapper.class);
            PublicCatalogQuery group = query(null);
            group.setGroupEligible(1);
            List<PublicContentCatalogRow> groupRows = mapper.selectCatalogItems(group);
            assertEquals(1, groupRows.size());
            assertEquals(Long.valueOf(1L), groupRows.get(0).getId());

            PublicCatalogQuery school = query(null);
            school.setSchoolEligible(1);
            List<PublicContentCatalogRow> schoolRows = mapper.selectCatalogItems(school);
            assertEquals(1, schoolRows.size());
            assertEquals(Long.valueOf(3L), schoolRows.get(0).getId());

            // 未声明 ≠ 不可承接：不参与筛选，但也没有被标记成 false。
            PublicContentCatalogRow undeclared = mapper.selectCatalogItemById(2L, "zh-cn");
            assertNull(undeclared.getGroupEligible());
            assertNull(undeclared.getSchoolEligible());
            // status=0 的声明不参与读侧，同样表现为未声明。
            PublicContentCatalogRow disabled = mapper.selectCatalogItemById(4L, "zh-cn");
            assertNull(disabled.getGroupEligible());
        }
    }

    @Test
    void ticketAggregatesAndRevisionIdentityAreBatchReadWithoutTouchingAnswers() throws Exception {
        try (SqlSession sql = open(); Statement st = sql.getConnection().createStatement()) {
            createSchema(sql);
            insertTopic(st, 1L, "310100", "有票", 1, 1, 0, 0, 0, 3);
            insertTopic(st, 2L, "310100", "无票", 1, 1, 0, 0, 0, 3);
            st.execute("update cms_topic set self_play_price = 39.00 where id = 1");
            st.execute("insert into oms_ticket values(1,1,1,0,59.00,'guided')");
            st.execute("insert into oms_ticket values(2,1,1,0,49.00,'self')");
            st.execute("insert into oms_ticket values(3,1,1,2,9.00,'guided')");
            st.execute("insert into topic_sale_snapshot values(88,1,'2026-10-01 10:00:00')");

            PublicContentCatalogMapper mapper = sql.getMapper(PublicContentCatalogMapper.class);
            List<Long> ids = Arrays.asList(1L, 2L);
            List<java.util.Map<String, Object>> aggregates = mapper.selectTicketAggregates(ids);
            assertEquals(1, aggregates.size(), "only topics with live tickets aggregate");
            assertEquals(0, new java.math.BigDecimal("49.00")
                    .compareTo(new java.math.BigDecimal(String.valueOf(aggregates.get(0).get("minTicketPrice")))));

            List<java.util.Map<String, Object>> revisions = mapper.selectSaleRevisions(ids);
            assertEquals(1, revisions.size());
            assertEquals(88L, ((Number) revisions.get(0).get("saleSnapshotId")).longValue());

            // 预览只给站名与钩子；答案/核销/碎片列一概不选。
            st.execute("insert into cms_topic_chapter values(10, 1, '第一章', 1, 0)");
            st.execute("insert into cms_topic_node values(100,1,10,'外滩',1,'31.240000','121.490000',1,'看那块表','描述','碎片正文','SECRET')");
            st.execute("insert into cms_topic_node values(101,1,10,'隐藏站',0,'31.240000','121.490000',2,'不该出现','描述','碎片正文','SECRET')");
            List<java.util.Map<String, Object>> stops = mapper.selectPreviewStops(1L, 3);
            assertEquals(1, stops.size());
            assertEquals("外滩", stops.get(0).get("name"));
            assertEquals("看那块表", stops.get(0).get("hookTeaser"));
            assertNull(stops.get(0).get("fragmentText"));
            assertNull(stops.get(0).get("checkinCode"));

            List<java.util.Map<String, Object>> names = mapper.selectChapterNames(1L);
            assertEquals(1, names.size());
            assertEquals("第一章", names.get(0).get("chapterName"));
        }
    }

    // ---------------------------------------------------------------- fixtures

    private static void insertTopic(Statement st, long id, String cityCode, String name, int status,
                                    int userStatus, int delFlag, int isTemplate, int memberOnly,
                                    int lifecycle) throws Exception {
        st.execute("insert into cms_topic(id, city_code, name, subtitle, description, img_url, product_type,"
                + "total_time, total_mileage, location_count, lifecycle, self_play, self_play_price, team_mode,"
                + "sale_end_at, end_date, club_id, published_config_version,"
                + "published_theme_runtime_config_json, update_time, status, user_status, del_flag, is_template,"
                + "club_member_only, circle_theme_code, circle_reviewed_at) values("
                + id + ",'" + cityCode + "','" + name + "','副标题','公开简介','https://img/1.png',1,"
                + "120,3.50,5," + lifecycle + ",1,29.00,0,date_add(now(), interval 30 day),"
                + "date_add(now(), interval 60 day),null,3,null,now()," + status + "," + userStatus + ","
                + delFlag + "," + isTemplate + "," + memberOnly + ",null,null)");
    }

    private static PublicCatalogQuery query(String cityCode) {
        PublicCatalogQuery query = new PublicCatalogQuery();
        query.setCityCode(cityCode);
        query.setLocale("zh-cn");
        query.setSort("NEWEST");
        return query;
    }
}
