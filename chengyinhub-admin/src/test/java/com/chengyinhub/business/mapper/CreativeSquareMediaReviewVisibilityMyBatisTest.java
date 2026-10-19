package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ViewCreativeSquare;
import com.chengyinhub.business.service.impl.ReviewedMediaVisibility;
import com.chengyinhub.business.service.impl.ViewCreativeSquareServiceImpl;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * CL-39：旧版创意广场(/api/creativesquare/list、/info)图片审核态与他人可见性。
 *
 * <p>写侧：ApiCreativeSquareController 文本同步机审通过后 status=1 发布即可见，pics 再 submitMediaCheck(9)
 * 异步送检；回调 risky → status=2 整帖下架，pass 不写广场表。主题同步帖(dataType=2)的图按主题(bizType=1)送检。
 * 读侧：他人只看 wx_content_callback_inbox 已落地 PASS 的图，正文照常；作者本人看全部图并带 mediaReviewState。
 */
class CreativeSquareMediaReviewVisibilityMyBatisTest {
    private static final long AUTHOR = 11L;
    private static final long OTHER = 8L;
    private static final long PENDING = 1L;
    private static final long FAILED = 2L;
    private static final long NEVER_CALLED_BACK = 3L;
    private static final long APPROVED = 4L;
    private static final long RISKY = 5L;
    private static final long MIXED = 6L;
    private static final long TOPIC_SYNC = 7L;
    private static final long TEXT_ONLY = 8L;
    private static final long TOPIC = 70L;

    @Test
    void pendingFailedAndNeverAnsweredMediaIsHiddenFromOthersWhilePassMediaAndTextStay() throws Exception {
        DriverManagerDataSource ds = dataSource();
        seed(new JdbcTemplate(ds));
        try (SqlSession session = factory(ds).openSession(true)) {
            ViewCreativeSquareServiceImpl service = service(session);
            for (Long viewer : new Long[] {OTHER, 0L}) {
                List<ViewCreativeSquare> rows = feed(session, viewer);
                service.applyMediaVisibility(rows, viewer);
                assertEquals(List.of(PENDING, FAILED, NEVER_CALLED_BACK, APPROVED, MIXED, TOPIC_SYNC, TEXT_ONLY), ids(rows),
                        "text was checked before publish: posts stay listed, risky (status=2) stays hidden");
                Map<Long, String> pics = pics(rows);
                assertEquals("", pics.get(PENDING), "queued media hidden from viewer " + viewer);
                assertEquals("", pics.get(FAILED), "failed submission hidden from viewer " + viewer);
                assertEquals("", pics.get(NEVER_CALLED_BACK), "never-answered media hidden from viewer " + viewer);
                assertEquals("https://cdn.example/approved.jpg", pics.get(APPROVED));
                assertEquals("https://cdn.example/a.jpg", pics.get(MIXED), "only the PASS image, separator kept");
                assertEquals("https://cdn.example/topic.jpg", pics.get(TOPIC_SYNC), "topic sync post uses the topic's PASS");
                assertNull(pics.get(TEXT_ONLY));
                assertEquals("cl39", rows.get(0).getContents());
                rows.forEach(r -> assertNull(r.getMediaReviewState(), "review state is author-only"));
            }
            // 详情页走同一方法(单元素列表)。
            session.clearCache();
            List<ViewCreativeSquare> detail = new java.util.ArrayList<>(List.of(
                    session.getMapper(ViewCreativeSquareMapper.class).selectViewCreativeSquareById(PENDING)));
            service.applyMediaVisibility(detail, OTHER);
            assertEquals("", detail.get(0).getPics());
        }
    }

    @Test
    void authorStillSeesOwnUnreviewedMediaWithItsState() throws Exception {
        DriverManagerDataSource ds = dataSource();
        seed(new JdbcTemplate(ds));
        try (SqlSession session = factory(ds).openSession(true)) {
            List<ViewCreativeSquare> rows = feed(session, AUTHOR);
            service(session).applyMediaVisibility(rows, AUTHOR);
            Map<Long, String> pics = pics(rows);
            assertEquals("https://cdn.example/pending.jpg", pics.get(PENDING));
            assertEquals("https://cdn.example/failed.jpg", pics.get(FAILED));
            assertEquals("https://cdn.example/lost.jpg", pics.get(NEVER_CALLED_BACK));
            assertEquals("https://cdn.example/a.jpg;https://cdn.example/b.jpg", pics.get(MIXED));
            assertEquals("https://cdn.example/risky.jpg", pics.get(RISKY), "author keeps own rejected post (status=2)");
            Map<Long, String> states = new HashMap<>();
            rows.forEach(r -> { if (r.getMediaReviewState() != null) states.put(r.getId(), r.getMediaReviewState()); });
            assertEquals(Map.of(PENDING, "PENDING", FAILED, "PENDING", NEVER_CALLED_BACK, "PENDING", APPROVED, "PASS",
                    RISKY, "PENDING", MIXED, "PENDING", TOPIC_SYNC, "PASS"), states);
            assertEquals(Integer.valueOf(2), rows.stream().filter(r -> r.getId() == RISKY).findFirst().get().getStatus());
        }
    }

    private List<ViewCreativeSquare> feed(SqlSession session, Long viewer) {
        // 与 ApiCreativeSquareController.viewCreativeSquareList 相同的可见性参数。
        session.clearCache(); // 每次查询模拟一个新请求，避免一级缓存还回已裁剪的对象
        ViewCreativeSquare q = new ViewCreativeSquare();
        if (viewer != null && viewer > 0) q.setSelfPendingMemberId(viewer); else q.setStatus(1);
        q.setDelFlag(0);
        return session.getMapper(ViewCreativeSquareMapper.class).selectViewCreativeSquareList(q).stream()
                .sorted((a, b) -> Long.compare(a.getId(), b.getId())).collect(Collectors.toList());
    }

    private void seed(JdbcTemplate jdbc) {
        jdbc.execute("create table view_creative_square("
                + "id bigint primary key, member_id bigint, contents varchar(500), pics varchar(500), file_url varchar(500),"
                + "address varchar(255), longitude varchar(64), latitude varchar(64), data_id bigint, data_type bigint,"
                + "like_num bigint, nolike_num bigint, status int, is_crowdfunding int, del_flag int,"
                + "create_by varchar(64), create_time timestamp, update_by varchar(64), update_time timestamp,"
                + "member_nickname varchar(100), member_avatar varchar(500), member_user_type int, member_level_id bigint,"
                + "comment_count bigint, no_like_count bigint, like_count bigint)");
        jdbc.execute("create table ums_member_follow(member_id bigint, follow_member_id bigint)");
        jdbc.execute("create table im_block(member_id bigint, blocked_member_id bigint)");
        jdbc.execute("create table community_post_member_action(post_id bigint, member_id bigint, action_type varchar(32))");
        jdbc.execute("create table cms_topic(id bigint primary key, del_flag int, status int, user_status int)");
        jdbc.execute("create table wx_content_callback_inbox(id bigint auto_increment primary key,trace_hash varbinary(32),biz_type int,biz_id bigint,media_hash varbinary(32),verdict int,state int)");
        jdbc.update("insert into cms_topic values (?,0,1,1)", TOPIC);
        post(jdbc, PENDING, "https://cdn.example/pending.jpg", 1, 0L, 0L);
        post(jdbc, FAILED, "https://cdn.example/failed.jpg", 1, 0L, 0L);
        post(jdbc, NEVER_CALLED_BACK, "https://cdn.example/lost.jpg", 1, 0L, 0L);
        post(jdbc, APPROVED, "https://cdn.example/approved.jpg", 1, 0L, 0L);
        post(jdbc, RISKY, "https://cdn.example/risky.jpg", 2, 0L, 0L);
        post(jdbc, MIXED, "https://cdn.example/a.jpg;https://cdn.example/b.jpg", 1, 0L, 0L);
        post(jdbc, TOPIC_SYNC, "https://cdn.example/topic.jpg", 1, TOPIC, 2L);
        post(jdbc, TEXT_ONLY, null, 1, 0L, 0L);
        // PENDING：送检尚未登记(无 inbox 行)；FAILED：送检失败/inbox 登记失败(无行)；NEVER_CALLED_BACK：等回调(state=0)。
        inbox(jdbc, "t-lost", 9, NEVER_CALLED_BACK, "https://cdn.example/lost.jpg", null, 0);
        inbox(jdbc, "t-approved", 9, APPROVED, "https://cdn.example/approved.jpg", 0, 2);
        inbox(jdbc, "t-risky", 9, RISKY, "https://cdn.example/risky.jpg", 1, 2);
        inbox(jdbc, "t-a", 9, MIXED, "https://cdn.example/a.jpg", 0, 2);
        inbox(jdbc, "t-b", 9, MIXED, "https://cdn.example/b.jpg", 0, 4); // stale 忽略 ≠ PASS
        inbox(jdbc, "t-topic", 1, TOPIC, "https://cdn.example/topic.jpg", 0, 2);
        // 别人帖子的 PASS(同 URL、不同 biz_id)不能让本帖的图通过。
        inbox(jdbc, "t-foreign", 9, 99L, "https://cdn.example/pending.jpg", 0, 2);
    }

    private void post(JdbcTemplate jdbc, long id, String pics, int status, long dataId, long dataType) {
        jdbc.update("insert into view_creative_square(id, member_id, contents, pics, status, del_flag, data_id, data_type) "
                + "values (?, ?, 'cl39', ?, ?, 0, ?, ?)", id, AUTHOR, pics, status, dataId, dataType);
    }

    private void inbox(JdbcTemplate jdbc, String trace, int bizType, long bizId, String url, Integer verdict, int state) {
        jdbc.update("insert into wx_content_callback_inbox(trace_hash,biz_type,biz_id,media_hash,verdict,state) values (?,?,?,?,?,?)",
                sha(trace), bizType, bizId, sha(url), verdict, state);
    }

    private static byte[] sha(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ViewCreativeSquareServiceImpl service(SqlSession session) {
        ViewCreativeSquareServiceImpl service = new ViewCreativeSquareServiceImpl();
        ReflectionTestUtils.setField(service, "viewCreativeSquareMapper", session.getMapper(ViewCreativeSquareMapper.class));
        ReflectionTestUtils.setField(service, "reviewedMediaVisibility",
                new ReviewedMediaVisibility(session.getMapper(ReviewedMediaMapper.class)));
        return service;
    }

    private static List<Long> ids(List<ViewCreativeSquare> rows) {
        return rows.stream().map(ViewCreativeSquare::getId).collect(Collectors.toList());
    }

    private static Map<Long, String> pics(List<ViewCreativeSquare> rows) {
        Map<Long, String> out = new HashMap<>();
        rows.forEach(r -> out.put(r.getId(), r.getPics()));
        return out;
    }

    private DriverManagerDataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:square_media_review_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        return ds;
    }

    private SqlSessionFactory factory(DriverManagerDataSource dataSource) throws Exception {
        Configuration configuration = new Configuration(new Environment(
                "square-media-review", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAlias("ViewCreativeSquare", ViewCreativeSquare.class);
        configuration.addMapper(ViewCreativeSquareMapper.class);
        configuration.addMapper(ReviewedMediaMapper.class);
        for (String resource : new String[] {"mapper/business/ViewCreativeSquareMapper.xml",
                "mapper/business/ReviewedMediaMapper.xml"}) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (input == null) throw new IllegalStateException("missing " + resource);
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
