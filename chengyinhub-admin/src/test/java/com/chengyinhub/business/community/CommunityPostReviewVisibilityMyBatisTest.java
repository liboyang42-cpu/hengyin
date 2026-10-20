package com.chengyinhub.business.community;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CL-39(F-L5C-08/02/03)：社区帖文与回复在「待审 / 驳回 / 审核失败」时他人不可见。
 *
 * <p>状态来源(CommunityPostServiceImpl + ContentSafetyGateImpl)：
 * <ul>
 *   <li>待审：含图片或文本 HOLD → lifecycle=CHECKING，community_post_media.review_state=HOLD；
 *       已发布帖编辑后 → CHECKING，新媒体 PENDING。</li>
 *   <li>审核失败：文本机审 MSG_BUSY/MSG_UNAVAILABLE/异常/关闭 → 同样 HOLD → CHECKING(fail-safe)。</li>
 *   <li>驳回：applyAuditDecision(false) → lifecycle=REMOVED，media=REJECTED，搜索投影 HIDDEN；
 *       回复驳回 → comment.lifecycle=REMOVED。</li>
 * </ul>
 * 他人读路径：selectFeed(全部分区 + 关键词搜索)、selectById(详情/分享落地)、selectBookmarkedFeed、
 * selectMedia(图片)、selectComments(回复区)、recomputeCounts(回复计数)。
 */
class CommunityPostReviewVisibilityMyBatisTest {
    private static final long AUTHOR = 11L;
    private static final long OTHER = 8L;
    private static final long PENDING = 101L;
    private static final long FAILED = 102L;
    private static final long REJECTED = 103L;
    private static final long APPROVED = 104L;
    private static final String[] FEED_MODES = {"LATEST", "FOLLOWING", "NEARBY", "TOPIC", "COMMUNITY",
            "FEATURED", "TRENDING", "FOR_YOU"};

    @Test
    void pendingFailedAndRejectedPostsAreHiddenFromEveryOtherViewerReadPath() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        seedPosts(jdbc);
        try (SqlSession session = factory(ds).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            for (String mode : FEED_MODES) {
                List<Long> ids = ids(mapper.selectFeed(feedQuery(OTHER, mode)));
                assertEquals(List.of(APPROVED), ids, "feed mode " + mode + " must show only the approved post");
            }
            CommunityPostFeedQuery search = feedQuery(OTHER, "LATEST");
            search.setKeyword("cl39");
            assertEquals(List.of(APPROVED), ids(mapper.selectFeed(search)), "keyword search");
            CommunityPostFeedQuery byAuthor = feedQuery(OTHER, "LATEST");
            byAuthor.setAuthorId(AUTHOR);
            assertEquals(List.of(APPROVED), ids(mapper.selectFeed(byAuthor)), "author profile timeline");
            assertEquals(List.of(APPROVED), ids(mapper.selectBookmarkedFeed(feedQuery(OTHER, "BOOKMARKS"))),
                    "bookmarks made before review must not leak un-approved posts");
            for (long id : new long[] {PENDING, FAILED, REJECTED}) {
                assertNull(mapper.selectById(id, OTHER), "detail/share landing for post " + id);
                assertNull(mapper.selectById(id, 0L), "anonymous detail/share landing for post " + id);
            }
            assertNotNull(mapper.selectById(APPROVED, OTHER));
            assertNotNull(mapper.selectById(APPROVED, 0L));
        }
    }

    @Test
    void authorKeepsOwnPendingFailedAndRejectedPostsWithTheirState() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        seedPosts(jdbc);
        try (SqlSession session = factory(ds).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            assertEquals("CHECKING", mapper.selectById(PENDING, AUTHOR).getLifecycle());
            assertEquals("CHECKING", mapper.selectById(FAILED, AUTHOR).getLifecycle());
            assertEquals("REMOVED", mapper.selectById(REJECTED, AUTHOR).getLifecycle());
            Map<Long, String> own = mapper.selectOwnPosts(feedQuery(AUTHOR, "OWN_POSTS")).stream()
                    .collect(Collectors.toMap(CommunityPost::getId, CommunityPost::getLifecycle));
            assertEquals(Map.of(PENDING, "CHECKING", FAILED, "CHECKING", REJECTED, "REMOVED",
                    APPROVED, "PUBLISHED"), own);
            assertEquals(List.of(REJECTED, FAILED, PENDING), ids(mapper.selectAuthorDrafts(AUTHOR, null, 20)),
                    "author workspace lists the review states");
            assertEquals(List.of(APPROVED), ids(mapper.selectFeed(feedQuery(AUTHOR, "LATEST"))),
                    "public feed stays public-only even for the author");
        }
    }

    @Test
    void publishedPostOnlyExposesPassMediaWhileAuthorStillSeesUnapprovedPostMedia() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        seedPosts(jdbc);
        // 已发布帖子编辑追加的图片仍在审(PENDING)、机审失败转人工(HOLD)、驳回(REJECTED)、通过(PASS)。
        jdbc.update("insert into community_post_media(id,post_id,media_type,derived_object_key,sort_order,review_state,retention_state) values "
                + "(1,?,'IMAGE','pending.jpg',1,'PENDING','ACTIVE'),(2,?,'IMAGE','hold.jpg',2,'HOLD','ACTIVE'),"
                + "(3,?,'IMAGE','rejected.jpg',3,'REJECTED','ACTIVE'),(4,?,'IMAGE','pass.jpg',4,'PASS','ACTIVE'),"
                + "(5,?,'IMAGE','own-hold.jpg',1,'HOLD','ACTIVE')",
                APPROVED, APPROVED, APPROVED, APPROVED, PENDING);
        try (SqlSession session = factory(ds).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            List<Object> visible = mapper.selectMedia(APPROVED).stream()
                    .map(row -> row.get("derived_object_key")).collect(Collectors.toList());
            assertEquals(List.of("pass.jpg"), visible);
            // 未发布帖子的媒体只有能先读到帖子的人(作者)才能拿到：详情闸已对他人返回 null。
            assertNull(mapper.selectById(PENDING, OTHER));
            assertNotNull(mapper.selectById(PENDING, AUTHOR));
            assertEquals("HOLD", mapper.selectMedia(PENDING).get(0).get("review_state"));
        }
    }

    @Test
    void pendingAndRejectedRepliesAreHiddenFromOthersAndExcludedFromCounts() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        seedPosts(jdbc);
        jdbc.update("insert into community_post_comment(id,post_id,author_id,root_id,parent_id,body,lifecycle,author_approval_state,version,like_count,create_time) values "
                + "(1,?,12,null,null,'pending reply','CHECKING','VISIBLE',0,0,now()),"
                + "(2,?,12,null,null,'rejected reply','REMOVED','VISIBLE',0,0,now()),"
                + "(3,?,13,null,null,'approved reply','PUBLISHED','VISIBLE',0,0,now())",
                APPROVED, APPROVED, APPROVED);
        try (SqlSession session = factory(ds).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            assertEquals(List.of(3L), commentIds(mapper.selectComments(APPROVED, OTHER, null, 20)));
            assertEquals(List.of(3L), commentIds(mapper.selectComments(APPROVED, AUTHOR, null, 20)),
                    "post author does not see someone else's un-approved reply");
            List<Map<String, Object>> own = mapper.selectComments(APPROVED, 12L, null, 20);
            assertEquals(List.of(3L, 1L), commentIds(own), "reply author keeps the pending reply");
            assertEquals("CHECKING", own.get(1).get("lifecycle"));
            assertEquals(1, mapper.recomputeCounts(APPROVED));
            assertEquals(1, jdbc.queryForObject("select comment_count from community_post where id=?",
                    Integer.class, APPROVED));
        }
    }

    @Test
    void reviewThatNeverCompletesKeepsPostHiddenRegardlessOfAge() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        seedPosts(jdbc);
        // 送审回调/人工结论永远不到：没有任何「超时即发布」的读侧兜底，帖子与媒体保持 CHECKING/HOLD。
        jdbc.update("update community_post set create_time=dateadd('DAY',-30,now()),update_time=dateadd('DAY',-30,now()),"
                + "published_at=dateadd('DAY',-30,now()) where id in (?,?)", PENDING, FAILED);
        jdbc.update("insert into community_post_media(id,post_id,media_type,derived_object_key,sort_order,review_state,retention_state,create_time) values "
                + "(9,?,'IMAGE','stale-hold.jpg',1,'HOLD','ACTIVE',dateadd('DAY',-30,now()))", PENDING);
        try (SqlSession session = factory(ds).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            for (String mode : FEED_MODES) {
                assertTrue(!ids(mapper.selectFeed(feedQuery(OTHER, mode))).contains(PENDING), mode);
                assertTrue(!ids(mapper.selectFeed(feedQuery(OTHER, mode))).contains(FAILED), mode);
            }
            assertNull(mapper.selectById(PENDING, OTHER));
            assertNull(mapper.selectById(FAILED, OTHER));
            assertEquals("CHECKING", mapper.selectById(PENDING, AUTHOR).getLifecycle());
        }
    }

    private void seedPosts(JdbcTemplate jdbc) {
        schema(jdbc);
        // HOLD 发布：CHECKING 且推荐/搜索/互动开关为 0(publish 中 transitionCas 的取值)。
        post(jdbc, PENDING, "CHECKING", 0, "cl39 pending media");
        post(jdbc, FAILED, "CHECKING", 0, "cl39 text review unavailable");
        post(jdbc, REJECTED, "REMOVED", 0, "cl39 rejected");
        post(jdbc, APPROVED, "PUBLISHED", 1, "cl39 approved");
        // 无论搜索投影是否已同步为 HIDDEN，帖文本身状态必须先挡住(投影写入失败也不泄露)。
        for (long id : new long[] {PENDING, FAILED, REJECTED, APPROVED}) {
            jdbc.update("insert into community_search_document(document_type,document_id,visibility_state,search_text) values ('POST',?,'VISIBLE','cl39')", id);
            jdbc.update("insert into community_post_topic(post_id,topic_code) values (?,'cl39-topic')", id);
            jdbc.update("insert into community_featured_entry(post_id,collection_code,starts_at,ends_at) values (?,'CITY_PICK',dateadd('DAY',-1,now()),null)", id);
            jdbc.update("insert into community_post_member_action(post_id,member_id,action_type) values (?,?,'BOOKMARK')", id, OTHER);
        }
        jdbc.update("insert into ums_member_follow(member_id,follow_member_id) values (?,?)", OTHER, AUTHOR);
        jdbc.update("insert into club_member(member_id,club_id,status,del_flag) values (?,7,1,0)", OTHER);
    }

    private CommunityPostFeedQuery feedQuery(long viewer, String mode) {
        CommunityPostFeedQuery q = new CommunityPostFeedQuery();
        q.setViewerId(viewer);
        q.setMode(mode);
        q.setLimit(20);
        q.setCityCode("SH");
        q.setTopicCode("cl39-topic");
        q.setCommunityId(7L);
        q.setCollectionCode("CITY_PICK");
        return q;
    }

    private static List<Long> ids(List<CommunityPost> rows) {
        return rows.stream().map(CommunityPost::getId).collect(Collectors.toList());
    }

    private static List<Long> commentIds(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> ((Number) row.get("id")).longValue()).collect(Collectors.toList());
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
        ds.setUrl("jdbc:h2:mem:community_review_visibility_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        return ds;
    }

    private SqlSessionFactory factory(DriverManagerDataSource dataSource) throws Exception {
        Configuration configuration = new Configuration(new Environment(
                "community-review-visibility", new JdbcTransactionFactory(), dataSource));
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
