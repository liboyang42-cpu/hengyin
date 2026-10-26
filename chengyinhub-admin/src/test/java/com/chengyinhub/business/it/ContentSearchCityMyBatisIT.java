package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.vo.ContentSearchItemVO;
import com.chengyinhub.business.mapper.ContentSearchMapper;
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

import java.io.InputStream;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL + production Mapper/gates/PageHelper. No task, backfill or rollback tool invocation. */
class ContentSearchCityMyBatisIT {
    private static final List<String> TYPES = Arrays.asList("TOPIC", "ACTIVITY", "POST");

    private SqlSession open() throws Exception {
        Configuration config = new Configuration();
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.setEnvironment(new Environment("it", new JdbcTransactionFactory(), new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", MybatisItDatabase.url("content_search_city"),
                MybatisItDatabase.user(), MybatisItDatabase.password())));
        // Load the exact shared production fragments, not simplified test copies of public gates.
        fragment(config, "CmsTopicMapper", "primaryCirclePublicGate");
        fragment(config, "CmsActivityMapper", "livePublicParentTopic");
        String resource = "mapper/business/ContentSearchMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, config, resource, config.getSqlFragments()).parse();
        }
        PageInterceptor paging = new PageInterceptor();
        Properties props = new Properties(); props.setProperty("helperDialect", "mysql");
        paging.setProperties(props); config.addInterceptor(paging);
        return new SqlSessionFactoryBuilder().build(config).openSession(true);
    }

    private static void fragment(Configuration config, String mapper, String id) throws Exception {
        try (InputStream in = Resources.getResourceAsStream("mapper/business/" + mapper + ".xml")) {
            XPathParser parser = new XPathParser(in, true, null, new XMLMapperEntityResolver());
            config.getSqlFragments().put("com.chengyinhub.business.mapper." + mapper + "." + id,
                    parser.evalNode("/mapper/sql[@id='" + id + "']"));
        }
    }

    @Test
    void sourceCityChangesAndClearsReachRowsAndPageTotalsWithoutRefreshingProjection() throws Exception {
        try (SqlSession sql = open(); Statement st = sql.getConnection().createStatement()) {
            ItSchema.applyMigrations(sql.getConnection(), "sql/migration_o14_content_search_document_20261022.sql");
            // Minimal source fixture columns used by the real gates, all in this suite's isolated schema.
            st.execute("create table cms_topic(id bigint primary key,city_code varchar(32),status int,user_status int,"
                    + "del_flag int,is_template int,club_member_only int,club_id bigint,circle_theme_code varchar(32),circle_reviewed_at datetime)");
            st.execute("create table cms_activity(id bigint primary key,city_code varchar(32),status int,publish_status int,"
                    + "del_flag int,cancel_time datetime,topic_id bigint,club_id bigint)");
            st.execute("create table community_post(id bigint primary key,city_code varchar(32),lifecycle varchar(32),"
                    + "audience varchar(32),search_eligible int,author_id bigint)");
            st.execute("create table community_search_document(document_type varchar(16),document_id bigint,visibility_state varchar(16))");
            st.execute("create table club(id bigint primary key,del_flag int)");
            st.execute("create table merchant_chapter_offer(id bigint primary key,merchant_id bigint,chapter_id bigint,topic_id bigint,status int,valid_end datetime)");
            st.execute("create table merchant_circle_supply_profile(offer_id bigint,is_current int,checked_at datetime)");
            st.execute("create table cms_topic_chapter(id bigint primary key,circle_enabled int)");
            st.execute("create table im_block(member_id bigint,blocked_member_id bigint)");
            st.execute("create table community_member_mute(member_id bigint,muted_member_id bigint)");
            // Same numeric ID in all three sources catches cross-type city joins.
            st.execute("insert into cms_topic values(1,'310100',1,1,0,0,0,null,null,null)");
            st.execute("insert into cms_activity values(1,'310100',1,1,0,null,1,null)");
            st.execute("insert into community_post values(1,'310100','PUBLISHED','PUBLIC',1,9)");
            st.execute("insert into community_search_document values('POST',1,'VISIBLE')");
            for (String type : TYPES) st.execute("insert into content_search_document "
                    + "(document_type,document_id,title,search_text,city_code,visibility_state,source_time,indexed_at) values('"
                    + type + "',1,'match','match','310100','VISIBLE','2026-01-01 00:00:00','2026-01-01 00:00:00')");
            ContentSearchMapper mapper = sql.getMapper(ContentSearchMapper.class);
            assertEquals(3, search(mapper, "310100").size());
            String[] tables = {"cms_topic", "cms_activity", "community_post"};
            String[] cities = {"440100", "320500", "110100"};
            for (int i = 0; i < tables.length; i++) st.execute("update " + tables[i] + " set city_code='" + cities[i] + "' where id=1");
            assertTrue(search(mapper, "310100").isEmpty(), "stale projection city must stop matching immediately");
            for (int i = 0; i < TYPES.size(); i++) {
                List<ContentSearchItemVO> rows = search(mapper, cities[i]);
                assertEquals(1, rows.size()); assertEquals(TYPES.get(i).toLowerCase(java.util.Locale.ROOT), rows.get(0).getType());
                assertEquals(cities[i], rows.get(0).getCityCode());
                try {
                    PageHelper.startPage(1, 1);
                    List<ContentSearchItemVO> paged = search(mapper, cities[i]);
                    assertEquals(1L, ((Page<?>) paged).getTotal(), "count and rows must use the same current-city fact");
                    assertEquals(cities[i], paged.get(0).getCityCode());
                } finally { PageHelper.clearPage(); }
                assertEquals(cities[i], mapper.selectUnifiedSearch("match", Collections.singletonList(TYPES.get(i)), null, 7L)
                        .get(0).getCityCode());
            }
            // NULL is current fact, including an activity whose parent still has a city.
            for (int i = tables.length - 1; i >= 0; i--) {
                st.execute("update " + tables[i] + " set city_code=null where id=1");
                assertTrue(search(mapper, cities[i]).isEmpty());
                assertNull(mapper.selectUnifiedSearch("match", Collections.singletonList(TYPES.get(i)), null, 7L)
                        .get(0).getCityCode());
            }
            assertEquals(3, search(mapper, null).size());
            // Existing type, public visibility, viewer-block and mute policies remain authoritative.
            st.execute("insert into im_block values(9,7)");
            assertEquals(2, search(mapper, null).size());
            st.execute("delete from im_block"); st.execute("insert into community_member_mute values(7,9)");
            assertEquals(2, search(mapper, null).size());
            st.execute("delete from community_member_mute");
            st.execute("update community_post set audience='FOLLOWERS'");
            assertEquals(2, search(mapper, null).size());
            st.execute("update cms_topic set user_status=0");
            assertTrue(search(mapper, null).isEmpty(), "hidden parent also excludes activity");
            st.execute("delete from cms_topic"); st.execute("delete from cms_activity"); st.execute("delete from community_post");
            assertTrue(search(mapper, null).isEmpty());
            try (java.sql.ResultSet result = st.executeQuery("select count(*) from content_search_document "
                    + "where city_code='310100' and visibility_state='VISIBLE' and indexed_at='2026-01-01 00:00:00'")) {
                assertTrue(result.next()); assertEquals(3, result.getInt(1), "read-side repair must not rewrite projection");
            }
        }
    }

    private static List<ContentSearchItemVO> search(ContentSearchMapper mapper, String city) {
        return mapper.selectUnifiedSearch("match", TYPES, city, 7L);
    }
}
