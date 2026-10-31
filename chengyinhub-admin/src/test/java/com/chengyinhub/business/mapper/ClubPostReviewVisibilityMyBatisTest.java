package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ClubPost;
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
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * <p>驳回(risky 回调)已证明隐藏；待审 / 送检失败 / 回调永不到达对他人可见。用户 2026-10-20 决定动态类内容
 * 「一般先发后审」：先公开、驳回后下架，所以这是预期行为，下面的 publishFirst 用例把它钉住。
 */
class ClubPostReviewVisibilityMyBatisTest {
    private static final long CLUB = 7L;
    private static final long PENDING = 1L;
    private static final long FAILED = 2L;
    private static final long REJECTED = 3L;
    private static final long APPROVED = 4L;
    private static final long NEVER_CALLED_BACK = 5L;

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
    void publishFirstPolicyShowsPendingFailedAndUnansweredMediaPostsToOtherMembers() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        seed(jdbc);
        try (SqlSession session = factory(ds).openSession(true)) {
            ClubPostMapper mapper = session.getMapper(ClubPostMapper.class);
            ClubPost q = new ClubPost();
            q.setClubId(CLUB);
            // 先发后审：审核未完成的动态与图片对所有人可见，驳回回调到达后才下架。
            assertEquals(List.of(NEVER_CALLED_BACK, APPROVED, FAILED, PENDING), ids(mapper.selectClubPostList(q)),
                    "publish-first: club page lists posts whose media review is pending/failed/never answered");
            assertEquals(List.of(NEVER_CALLED_BACK, APPROVED, FAILED, PENDING),
                    ids(mapper.selectFeedByClubIds(List.of(CLUB), 50)),
                    "publish-first: aggregate feed lists posts whose media review is pending/failed/never answered");
            assertEquals("https://cdn.example/pending.jpg", mapper.selectClubPostById(PENDING).getImages(),
                    "publish-first: the image is shown before its review comes back");
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
        jdbc.execute("create table club_post(id bigint primary key,club_id bigint,author_member_id bigint,content varchar(255),images varchar(2000),ref_type varchar(32),ref_id bigint,type int,like_count int,comment_count int,repost_count int default 0,del_flag int,is_pinned int,pinned_at timestamp,version int,create_time timestamp,update_time timestamp)");
        jdbc.execute("create table club_post_revision(id bigint primary key,post_id bigint)");
        jdbc.execute("create table club_post_media_review_outbox(id bigint primary key,post_id bigint,state int,create_time timestamp)");
        jdbc.update("insert into club values (?,'club','',0)", CLUB);
        jdbc.update("insert into ums_member values (11,'author','')");
        post(jdbc, PENDING, "pending.jpg", 0);
        post(jdbc, FAILED, "failed.jpg", 0);
        post(jdbc, REJECTED, "rejected.jpg", 2);
        post(jdbc, APPROVED, "approved.jpg", 0);
        post(jdbc, NEVER_CALLED_BACK, "lost.jpg", 0);
        jdbc.update("insert into club_post_media_review_outbox values "
                + "(1,?,0,now()),(2,?,5,now()),(3,?,2,now()),(4,?,2,now()),(5,?,2,dateadd('DAY',-30,now()))",
                PENDING, FAILED, REJECTED, APPROVED, NEVER_CALLED_BACK);
        jdbc.update("update club_post set create_time=dateadd('DAY',-30,now()) where id=?", NEVER_CALLED_BACK);
    }

    private void post(JdbcTemplate jdbc, long id, String image, int delFlag) {
        jdbc.update("insert into club_post(id,club_id,author_member_id,content,images,type,like_count,comment_count,del_flag,is_pinned,version,create_time,update_time) "
                + "values (?,?,11,'post',?,0,0,0,?,0,0,now(),now())", id, CLUB, "https://cdn.example/" + image, delFlag);
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
        String resource = "mapper/business/ClubPostMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("missing " + resource);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
