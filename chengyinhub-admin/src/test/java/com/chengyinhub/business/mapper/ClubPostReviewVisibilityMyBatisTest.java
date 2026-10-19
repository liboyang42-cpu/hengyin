package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.service.impl.ClubPostServiceImpl;
import com.chengyinhub.business.service.impl.ReviewedMediaVisibility;
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
 * CL-39：俱乐部动态图片审核态与他人可见性。
 *
 * <p>状态来源：ClubPostServiceImpl.insertClubPostInActiveClub / 编辑 → club_post_media_review_outbox
 * (0 待派发 / 1 派发中 / 2 已送检等回调 / 3 可重试失败 / 4 过期 / 5 毒任务)；
 * ClubPostMediaReviewServiceImpl 经 submitMediaCheckOne 送检，回调 WxContentSecurityServiceImpl
 * 对 BIZ_TYPE_CLUB_POST 只处理 risky → deleteClubPostById(del_flag=2)，pass 不写任何状态。
 * club_post 本身没有审核列，读侧只看 del_flag。
 *
 * <p>他人读路径：selectClubPostList(/api/club/post/list，标注「公开」)、
 * selectFeedByClubIds(/api/club/feed 聚合流)。
 *
 * <p>驳回(risky 回调)已证明隐藏。待审 / 送检失败 / 回调永不到达原先对他人可见(#346 钉住的 DEFECT_CL39)；
 * 修复后读侧按 wx_content_callback_inbox 的 PASS 事实(ReviewedMediaVisibility)只向他人下发 PASS 图片，
 * 正文照常，作者本人保留全部图片并带 mediaReviewState。
 */
class ClubPostReviewVisibilityMyBatisTest {
    private static final long CLUB = 7L;
    private static final long PENDING = 1L;
    private static final long FAILED = 2L;
    private static final long REJECTED = 3L;
    private static final long APPROVED = 4L;
    private static final long NEVER_CALLED_BACK = 5L;
    private static final long MIXED = 6L;
    private static final long AUTHOR = 11L;
    private static final long OTHER = 8L;

    @Test
    void rejectedMediaPostIsHiddenAndApprovedPostIsShownOnClubPageAndAggregateFeed() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        seed(jdbc);
        try (SqlSession session = factory(ds).openSession(true)) {
            ClubPostMapper mapper = session.getMapper(ClubPostMapper.class);
            ClubPost q = new ClubPost();
            q.setClubId(CLUB);
            List<Long> clubPage = ids(mapper.selectClubPostList(q));
            List<Long> feed = ids(mapper.selectFeedByClubIds(List.of(CLUB), 50));
            assertEquals(false, clubPage.contains(REJECTED), "risky callback (del_flag=2) hides post on club page");
            assertEquals(false, feed.contains(REJECTED), "risky callback (del_flag=2) hides post in aggregate feed");
            assertEquals(true, clubPage.contains(APPROVED));
            assertEquals(true, feed.contains(APPROVED));
        }
    }

    @Test
    void pendingFailedAndNeverCalledBackMediaIsHiddenFromOtherMembersWhileAuthorKeepsItWithState_CL39() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        seed(jdbc);
        try (SqlSession session = factory(ds).openSession(true)) {
            ClubPostMapper mapper = session.getMapper(ClubPostMapper.class);
            ClubPostServiceImpl service = service(session);
            ClubPost q = new ClubPost();
            q.setClubId(CLUB);
            for (Long viewer : new Long[] {OTHER, null}) {
                // 每次查询模拟一个新请求：一级缓存会把上一轮已就地裁剪的对象原样还回来。
                session.clearCache();
                List<ClubPost> page = mapper.selectClubPostList(q);
                service.applyMediaVisibility(page, viewer);
                session.clearCache();
                List<ClubPost> feed = mapper.selectFeedByClubIds(List.of(CLUB), 50);
                service.applyMediaVisibility(feed, viewer);
                for (List<ClubPost> rows : List.of(page, feed)) {
                    // 正文已同步机审，帖子照常列出；未 PASS 的图片 URL 不下发给他人。
                    assertEquals(List.of(MIXED, NEVER_CALLED_BACK, APPROVED, FAILED, PENDING), ids(rows));
                    Map<Long, String> images = images(rows);
                    assertEquals("", images.get(PENDING), "queued media hidden from viewer " + viewer);
                    assertEquals("", images.get(FAILED), "failed dispatch hidden from viewer " + viewer);
                    assertEquals("", images.get(NEVER_CALLED_BACK), "never-answered media hidden from viewer " + viewer);
                    assertEquals("https://cdn.example/approved.jpg", images.get(APPROVED));
                    assertEquals("https://cdn.example/approved.jpg", images.get(MIXED), "only the PASS image of a mixed post");
                    assertEquals("post", rows.get(0).getContent());
                    rows.forEach(p -> assertNull(p.getMediaReviewState(), "review state is author-only"));
                }
            }
            session.clearCache();
            List<ClubPost> own = mapper.selectClubPostList(q);
            service.applyMediaVisibility(own, AUTHOR);
            Map<Long, String> images = images(own);
            assertEquals("https://cdn.example/pending.jpg", images.get(PENDING), "author keeps own unreviewed media");
            assertEquals("https://cdn.example/failed.jpg", images.get(FAILED));
            assertEquals("https://cdn.example/lost.jpg", images.get(NEVER_CALLED_BACK));
            assertEquals("https://cdn.example/approved.jpg,https://cdn.example/held.jpg", images.get(MIXED));
            Map<Long, String> states = own.stream().collect(Collectors.toMap(ClubPost::getId, ClubPost::getMediaReviewState));
            assertEquals(Map.of(PENDING, "PENDING", FAILED, "PENDING", NEVER_CALLED_BACK, "PENDING",
                    MIXED, "PENDING", APPROVED, "PASS"), states);
        }
    }

    @Test
    void rejectedPostIsAlsoGoneFromItsAuthorsClubPage_noStateShown() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        seed(jdbc);
        try (SqlSession session = factory(ds).openSession(true)) {
            ClubPost q = new ClubPost();
            q.setClubId(CLUB);
            // 列表没有 viewer 维度：作者看不到自己被驳回的动态，也拿不到「已驳回」状态(作者侧缺口，非泄露)。
            assertEquals(false, ids(session.getMapper(ClubPostMapper.class).selectClubPostList(q)).contains(REJECTED));
        }
    }

    private void seed(JdbcTemplate jdbc) {
        jdbc.execute("create table club(id bigint primary key,name varchar(64),logo varchar(255),del_flag int)");
        jdbc.execute("create table ums_member(id bigint primary key,nickname varchar(64),avatar varchar(255))");
        jdbc.execute("create table club_post(id bigint primary key,club_id bigint,author_member_id bigint,content varchar(255),images varchar(2000),ref_type varchar(32),ref_id bigint,type int,like_count int,comment_count int,del_flag int,is_pinned int,pinned_at timestamp,version int,create_time timestamp,update_time timestamp)");
        jdbc.execute("create table club_post_revision(id bigint primary key,post_id bigint)");
        jdbc.execute("create table club_post_media_review_outbox(id bigint primary key,post_id bigint,state int,create_time timestamp)");
        jdbc.update("insert into club values (?,'club','',0)", CLUB);
        jdbc.update("insert into ums_member values (11,'author','')");
        post(jdbc, PENDING, "pending.jpg", 0);
        post(jdbc, FAILED, "failed.jpg", 0);
        post(jdbc, REJECTED, "rejected.jpg", 2);
        post(jdbc, APPROVED, "approved.jpg", 0);
        post(jdbc, NEVER_CALLED_BACK, "lost.jpg", 0);
        post(jdbc, MIXED, "approved.jpg", 0);
        jdbc.update("insert into club_post_media_review_outbox values "
                + "(1,?,0,now()),(2,?,5,now()),(3,?,2,now()),(4,?,2,now()),(5,?,2,dateadd('DAY',-30,now()))",
                PENDING, FAILED, REJECTED, APPROVED, NEVER_CALLED_BACK);
        jdbc.update("update club_post set create_time=dateadd('DAY',-30,now()) where id=?", NEVER_CALLED_BACK);
        jdbc.update("update club_post set images=? where id=?",
                "https://cdn.example/approved.jpg,https://cdn.example/held.jpg", MIXED);
        // 回调 inbox：送检登记 media_hash=SHA-256(url)；state 2=已落地，verdict 0=pass / 1=risky。
        // PENDING / FAILED 从未送检成功 ⇒ 无 inbox 行；NEVER_CALLED_BACK 等回调(state=0)；MIXED 一张 pass、一张转人工(state=5)。
        jdbc.execute("create table wx_content_callback_inbox(id bigint auto_increment primary key,trace_hash varbinary(32),biz_type int,biz_id bigint,media_hash varbinary(32),verdict int,state int)");
        inbox(jdbc, "t-approved", APPROVED, "approved.jpg", 0, 2);
        inbox(jdbc, "t-rejected", REJECTED, "rejected.jpg", 1, 2);
        inbox(jdbc, "t-lost", NEVER_CALLED_BACK, "lost.jpg", null, 0);
        inbox(jdbc, "t-mixed-pass", MIXED, "approved.jpg", 0, 2);
        inbox(jdbc, "t-mixed-held", MIXED, "held.jpg", 0, 5);
        // 同一 URL 在别的业务类型下 PASS 不算本帖通过。
        jdbc.update("insert into wx_content_callback_inbox(trace_hash,biz_type,biz_id,media_hash,verdict,state) values (?,9,?,?,0,2)",
                sha("t-other-biz"), PENDING, sha("https://cdn.example/pending.jpg"));
    }

    private void inbox(JdbcTemplate jdbc, String trace, long postId, String image, Integer verdict, int state) {
        jdbc.update("insert into wx_content_callback_inbox(trace_hash,biz_type,biz_id,media_hash,verdict,state) values (?,3,?,?,?,?)",
                sha(trace), postId, sha("https://cdn.example/" + image), verdict, state);
    }

    private static byte[] sha(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void post(JdbcTemplate jdbc, long id, String image, int delFlag) {
        jdbc.update("insert into club_post(id,club_id,author_member_id,content,images,type,like_count,comment_count,del_flag,is_pinned,version,create_time,update_time) "
                + "values (?,?,11,'post',?,0,0,0,?,0,0,now(),now())", id, CLUB, "https://cdn.example/" + image, delFlag);
    }

    private static Map<Long, String> images(List<ClubPost> rows) {
        Map<Long, String> out = new HashMap<>();
        rows.forEach(p -> out.put(p.getId(), p.getImages()));
        return out;
    }

    private static ClubPostServiceImpl service(SqlSession session) {
        ClubPostServiceImpl service = new ClubPostServiceImpl();
        ReflectionTestUtils.setField(service, "clubPostMapper", session.getMapper(ClubPostMapper.class));
        ReflectionTestUtils.setField(service, "reviewedMediaVisibility",
                new ReviewedMediaVisibility(session.getMapper(ReviewedMediaMapper.class)));
        return service;
    }

    private static List<Long> ids(List<ClubPost> rows) {
        return rows.stream().map(ClubPost::getId).collect(Collectors.toList());
    }

    private DriverManagerDataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:club_post_review_visibility_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        return ds;
    }

    private SqlSessionFactory factory(DriverManagerDataSource dataSource) throws Exception {
        Configuration configuration = new Configuration(new Environment(
                "club-post-review-visibility", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAlias("ClubPost", ClubPost.class);
        configuration.addMapper(ClubPostMapper.class);
        configuration.addMapper(ReviewedMediaMapper.class);
        for (String resource : new String[] {"mapper/business/ClubPostMapper.xml", "mapper/business/ReviewedMediaMapper.xml"}) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (input == null) throw new IllegalStateException("missing " + resource);
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
