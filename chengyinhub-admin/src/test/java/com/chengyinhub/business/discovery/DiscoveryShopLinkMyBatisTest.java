package com.chengyinhub.business.discovery;

import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.domain.community.CommunityPostFeedQuery;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * K2 discovery (MKT-09): shop-visit posts link a shop (roam place or merchant), and the shop page lists them.
 * The link is stored once and survives every moderation state; the shop page shows the post only while the feeds would.
 */
class DiscoveryShopLinkMyBatisTest {
    private static final long AUTHOR = 11L;
    private static final long OTHER = 8L;
    private static final long PENDING = 101L;
    private static final long FAILED = 102L;
    private static final long REJECTED = 103L;
    private static final long APPROVED = 104L;
    private static final long MERCHANT_LINKED = 105L;
    private static final long INVALIDATED = 106L;
    private static final long PLACE = 501L;
    private static final long LONE_PLACE = 502L;
    private static final long MERCHANT = 601L;

    @Test
    void shopPageShowsOnlyVisiblePostsAndLinksSurviveEveryModerationState() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        seed(jdbc);
        try (SqlSession session = factory(ds).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            Date now = new Date();
            for (long id : new long[] {PENDING, FAILED, REJECTED, APPROVED}) {
                assertEquals(1, mapper.insertReference(id, "POI", PLACE, "{}", "PUBLIC_SAFE", now));
            }
            assertEquals(1, mapper.insertReference(MERCHANT_LINKED, "MERCHANT", MERCHANT, "{}", "PUBLIC_SAFE", now));
            assertEquals(1, mapper.insertReference(INVALIDATED, "POI", PLACE, "{}", "PUBLIC_SAFE", now));
            jdbc.update("update community_post_reference set invalidated_at=now() where post_id=?", INVALIDATED);
            session.clearCache();

            // Held, failed-review and rejected posts never show on the shop page, for others or for the author.
            assertEquals(List.of(MERCHANT_LINKED, APPROVED), ids(mapper.selectFeed(place(OTHER, "POI", PLACE))));
            assertEquals(List.of(MERCHANT_LINKED, APPROVED), ids(mapper.selectFeed(place(AUTHOR, "POI", PLACE))));
            assertEquals(List.of(MERCHANT_LINKED, APPROVED), ids(mapper.selectFeed(place(0L, "POI", PLACE))),
                    "anonymous viewer");
            // A merchant page also lists posts linked to its places.
            assertEquals(List.of(MERCHANT_LINKED, APPROVED), ids(mapper.selectFeed(place(OTHER, "MERCHANT", MERCHANT))));
            assertEquals(List.of(), ids(mapper.selectFeed(place(OTHER, "POI", LONE_PLACE))));

            // Approval later: the link was kept through review, so the post appears without being re-linked.
            jdbc.update("update community_post set lifecycle='PUBLISHED' where id=?", PENDING);
            session.clearCache(); // the rows changed outside MyBatis
            assertEquals(List.of(MERCHANT_LINKED, APPROVED, PENDING), ids(mapper.selectFeed(place(OTHER, "POI", PLACE))));
            // Taken down after publishing: hidden again, and the link row stays for an appeal or a restore.
            jdbc.update("update community_post set lifecycle='REMOVED' where id=?", APPROVED);
            session.clearCache(); // the rows changed outside MyBatis
            assertEquals(List.of(MERCHANT_LINKED, PENDING), ids(mapper.selectFeed(place(OTHER, "POI", PLACE))));
            jdbc.update("update community_post set lifecycle='PUBLISHED' where id=?", APPROVED);
            session.clearCache(); // the rows changed outside MyBatis
            assertEquals(List.of(MERCHANT_LINKED, APPROVED, PENDING), ids(mapper.selectFeed(place(OTHER, "POI", PLACE))));
            for (long id : new long[] {PENDING, FAILED, REJECTED, APPROVED}) {
                assertEquals(1, jdbc.queryForObject("select count(*) from community_post_reference where post_id=?"
                        + " and reference_type='POI' and reference_id=? and invalidated_at is null", Integer.class, id, PLACE),
                        "link of post " + id + " survives moderation");
            }
            // Public feeds are unchanged by the new mode.
            CommunityPostFeedQuery latest = place(OTHER, "POI", PLACE);
            latest.setMode("LATEST");
            assertEquals(List.of(INVALIDATED, MERCHANT_LINKED, APPROVED, PENDING), ids(mapper.selectFeed(latest)));
        }
    }

    @Test
    void merchantTargetIsCheckedLikeThePublicShopHome() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        seed(jdbc);
        try (SqlSession session = factory(ds).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            assertEquals(1, mapper.countReferenceTarget("MERCHANT", MERCHANT, AUTHOR));
            assertEquals(0, mapper.countReferenceTarget("MERCHANT", 602L, AUTHOR), "closed merchant");
            assertEquals(0, mapper.countReferenceTarget("MERCHANT", 999L, AUTHOR), "unknown merchant");
            Map<String, Object> snapshot = mapper.selectReferenceSnapshot("MERCHANT", MERCHANT, AUTHOR);
            assertNotNull(snapshot);
            assertEquals("Tea House", snapshot.get("title"));
            assertNull(mapper.selectReferenceSnapshot("MERCHANT", 602L, AUTHOR));
        }
    }

    @Test
    void shopPickerListsApprovedPlacesAndPlacelessMerchantsNearestFirst() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        seed(jdbc);
        try (SqlSession session = factory(ds).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            List<String> near = keys(mapper.selectShopOptions(null, 31.2300, 121.4700, 20));
            // 503 is waiting for review and 504 is closed; merchant 601 is picked through its place 501; 602 is closed.
            assertEquals(List.of("POI:501", "MERCHANT:603", "POI:502"), near);
            assertEquals(List.of("POI:502"), keys(mapper.selectShopOptions("Park", 31.2300, 121.4700, 20)));
            assertEquals(List.of(), keys(mapper.selectShopOptions(null, 40.0, 116.0, 20)), "other city");
            assertEquals(List.of("POI:502", "POI:501", "MERCHANT:603"), keys(mapper.selectShopOptions(null, null, null, 20)));
        }
    }

    private void seed(JdbcTemplate jdbc) {
        schema(jdbc);
        post(jdbc, PENDING, "CHECKING", 0, "pending visit");
        post(jdbc, FAILED, "CHECKING", 0, "review unavailable");
        post(jdbc, REJECTED, "REMOVED", 0, "rejected visit");
        post(jdbc, APPROVED, "PUBLISHED", 1, "approved visit");
        post(jdbc, MERCHANT_LINKED, "PUBLISHED", 1, "merchant visit");
        post(jdbc, INVALIDATED, "PUBLISHED", 1, "unlinked visit");
        jdbc.execute("create table community_post_reference(id bigint auto_increment primary key,post_id bigint,reference_type varchar(24),reference_id bigint,snapshot_json varchar(255),privacy_snapshot varchar(32),invalidated_at timestamp,create_time timestamp)");
        jdbc.execute("create table roam_poi(id bigint primary key,name varchar(64),address varchar(128),description varchar(128),city_code varchar(16),merchant_id bigint,status int,application_type int,audit_status int,lat double,lng double,cover_img varchar(128))");
        jdbc.execute("create table mms_merchant(id bigint primary key,name varchar(64),address varchar(128),cover_image varchar(128),logo varchar(128),status int,account_status int,del_flag int,location_lat double,location_lng double)");
        jdbc.update("insert into roam_poi values (501,'Tea House Garden','1 Road',null,'SH',601,1,null,null,31.2301,121.4701,null),"
                + "(502,'Park Corner','2 Road',null,'SH',null,1,2,null,31.2600,121.5000,null),"
                + "(503,'Waiting Shop','3 Road',null,'SH',null,1,1,0,31.2300,121.4700,null),"
                + "(504,'Closed Spot','4 Road',null,'SH',null,0,null,null,31.2300,121.4700,null)");
        jdbc.update("insert into mms_merchant values (601,'Tea House','1 Road','','',1,1,0,31.2301,121.4701),"
                + "(602,'Closed Cafe','5 Road','','',0,1,0,31.2300,121.4700),"
                + "(603,'Noodle Bar','6 Road','','',1,1,0,31.2400,121.4800)");
    }

    private static CommunityPostFeedQuery place(long viewer, String type, long id) {
        CommunityPostFeedQuery q = new CommunityPostFeedQuery();
        q.setViewerId(viewer);
        q.setMode("PLACE");
        q.setPlaceType(type);
        q.setPlaceId(id);
        q.setLimit(20);
        return q;
    }

    private static List<String> keys(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> r.get("reference_type") + ":" + ((Number) r.get("reference_id")).longValue())
                .collect(Collectors.toList());
    }

    private static List<Long> ids(List<CommunityPost> rows) {
        return rows.stream().map(CommunityPost::getId).collect(Collectors.toList());
    }

    private void schema(JdbcTemplate jdbc) {
        jdbc.execute("create table community_post(id bigint primary key,author_id bigint,community_id bigint,client_request_id varchar(96),body varchar(100),title varchar(100),post_type varchar(32),audience varchar(16),comment_policy varchar(16),reply_approval_enabled int,slow_mode_seconds int,lifecycle varchar(16),location_precision varchar(16),city_code varchar(32),poi_name varchar(100),approximate_geohash varchar(16),recommendation_eligible int,search_eligible int,interaction_enabled int,disclosure_type varchar(24),safety_labels_json varchar(100),guideline_version_id bigint,like_count int,comment_count int,bookmark_count int,share_count int,published_at timestamp,edited_at timestamp,deleted_at timestamp,version int,create_time timestamp,update_time timestamp)");
        jdbc.execute("create table community_post_media(id bigint primary key,post_id bigint,media_type varchar(16),object_key varchar(255),derived_object_key varchar(255),mime_type varchar(64),byte_size bigint,width int,height int,duration_ms int,sort_order int,alt_text varchar(255),review_state varchar(16),retention_state varchar(16),create_time timestamp,update_time timestamp)");
        jdbc.execute("create table ums_member(id bigint primary key,nickname varchar(100),avatar varchar(100))");
        jdbc.execute("create table ums_member_follow(member_id bigint,follow_member_id bigint)");
        jdbc.execute("create table im_block(member_id bigint,blocked_member_id bigint)");
        jdbc.execute("create table community_member_mute(member_id bigint,muted_member_id bigint)");
        jdbc.execute("create table club_member(member_id bigint,club_id bigint,status int,del_flag int)");
        jdbc.execute("create table community_post_member_action(post_id bigint,member_id bigint,action_type varchar(32))");
        jdbc.execute("create table community_post_topic(post_id bigint,topic_code varchar(64))");
        jdbc.execute("create table community_featured_entry(post_id bigint,collection_code varchar(64),starts_at timestamp,ends_at timestamp)");
        jdbc.execute("create table community_search_document(document_type varchar(16),document_id bigint,visibility_state varchar(16),search_text varchar(255))");
        jdbc.execute("create table community_post_comment(id bigint primary key,post_id bigint,author_id bigint,root_id bigint,parent_id bigint,replied_to_member_id bigint,body varchar(100),lifecycle varchar(16),author_approval_state varchar(16),version int,like_count int,create_time timestamp)");
        jdbc.execute("create table community_post_comment_action(comment_id bigint,member_id bigint,action_type varchar(32),request_id varchar(96),create_time timestamp)");
        jdbc.update("insert into ums_member values (11,'author',''),(12,'replier',''),(13,'other',''),(8,'viewer','')");
    }

    private void post(JdbcTemplate jdbc, long id, String lifecycle, int eligible, String body) {
        jdbc.update("insert into community_post(id,author_id,community_id,body,post_type,audience,comment_policy,reply_approval_enabled,slow_mode_seconds,lifecycle,location_precision,city_code,approximate_geohash,recommendation_eligible,search_eligible,interaction_enabled,disclosure_type,safety_labels_json,guideline_version_id,like_count,comment_count,bookmark_count,share_count,published_at,version,create_time,update_time) values (?,?,7,?,'MOMENT','PUBLIC','EVERYONE',0,0,?,'APPROXIMATE','SH','wx4g',1,1,?,'NONE','[]',1,0,0,0,0,now(),0,now(),now())",
                id, AUTHOR, body, lifecycle, eligible);
    }

    private DriverManagerDataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:discovery_shop_link_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        return ds;
    }

    private SqlSessionFactory factory(DriverManagerDataSource dataSource) throws Exception {
        Configuration configuration = new Configuration(new Environment(
                "discovery-shop-link", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAlias("CommunityPost", CommunityPost.class);
        configuration.addMapper(CommunityPostMapper.class);
        String resource = "mapper/business/CommunityPostMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("missing " + resource);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
