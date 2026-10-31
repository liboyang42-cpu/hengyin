package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ClubPost;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 2026-10-29 发现页全站帖文流(selectSiteFeed)的 SQL 口径:
 * 俱乐部未删且审核通过;不公开的俱乐部只对主理人和在册成员可见;帖子 del_flag=0;
 * viewer 与作者任一方拉黑都不出现;按 id 倒序,beforeId 翻页。
 */
class ClubPostSiteFeedMyBatisTest {
    private static final long VIEWER = 100L;
    private static final long AUTHOR = 11L;
    private static final long BLOCKED_BY_VIEWER = 12L;
    private static final long BLOCKS_VIEWER = 13L;

    private static final long PUBLIC_CLUB = 1L;
    private static final long PRIVATE_CLUB = 2L;
    private static final long PRIVATE_JOINED = 3L;
    private static final long PRIVATE_OWNED = 4L;
    private static final long PENDING_CLUB = 5L;
    private static final long DELETED_CLUB = 6L;
    private static final long PRIVATE_LEFT = 7L;
    private static final long NULL_VISIBILITY = 8L;

    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:club_post_site_feed_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        seed(new JdbcTemplate(ds));
        Configuration configuration = new Configuration(new Environment(
                "club-post-site-feed", new JdbcTransactionFactory(), ds));
        configuration.getTypeAliasRegistry().registerAlias("ClubPost", ClubPost.class);
        configuration.addMapper(ClubPostMapper.class);
        String resource = "mapper/business/ClubPostMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("missing " + resource);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void wholeSiteFeedHonoursClubVisibilityDeletionAndBlocksNewestFirst() {
        // 帖子 id = 俱乐部 id * 10 + 序号;见 seed()
        assertEquals(List.of(81L, 42L, 31L, 13L, 11L), ids(VIEWER, null, 50));
    }

    @Test
    void strangerSeesOnlyPublicClubs() {
        assertEquals(List.of(81L, 15L, 13L, 12L, 11L), ids(999L, null, 50),
                "非成员看不到不公开俱乐部;没有拉黑关系时两名被拉黑作者的帖子对他可见");
    }

    @Test
    void beforeIdPagesBackwardsAndLimitCaps() {
        assertEquals(List.of(81L, 42L), ids(VIEWER, null, 2));
        assertEquals(List.of(31L, 13L), ids(VIEWER, 42L, 2));
        assertEquals(List.of(11L), ids(VIEWER, 13L, 2));
    }

    private List<Long> ids(long viewer, Long beforeId, int limit) {
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(ClubPostMapper.class).selectSiteFeed(viewer, beforeId, limit)
                    .stream().map(ClubPost::getId).collect(Collectors.toList());
        }
    }

    private void seed(JdbcTemplate jdbc) {
        jdbc.execute("create table club(id bigint primary key,member_id bigint,name varchar(64),logo varchar(255),status int,public_visible int,del_flag int)");
        jdbc.execute("create table club_member(id bigint auto_increment primary key,club_id bigint,member_id bigint,status int,del_flag int)");
        jdbc.execute("create table im_block(id bigint auto_increment primary key,member_id bigint,blocked_member_id bigint)");
        jdbc.execute("create table ums_member(id bigint primary key,nickname varchar(64),avatar varchar(255))");
        jdbc.execute("create table club_post(id bigint primary key,club_id bigint,author_member_id bigint,content varchar(255),images varchar(2000),ref_type varchar(32),ref_id bigint,type int,like_count int,comment_count int,del_flag int,is_pinned int,pinned_at timestamp,version int,create_time timestamp,update_time timestamp)");
        jdbc.execute("create table club_post_revision(id bigint primary key,post_id bigint)");
        club(jdbc, PUBLIC_CLUB, 50L, 1, 1, 0);
        club(jdbc, PRIVATE_CLUB, 50L, 1, 0, 0);
        club(jdbc, PRIVATE_JOINED, 50L, 1, 0, 0);
        club(jdbc, PRIVATE_OWNED, VIEWER, 1, 0, 0);
        club(jdbc, PENDING_CLUB, 50L, 0, 1, 0);
        club(jdbc, DELETED_CLUB, 50L, 1, 1, 2);
        club(jdbc, PRIVATE_LEFT, 50L, 1, 0, 0);
        jdbc.update("insert into club(id,member_id,name,logo,status,public_visible,del_flag) values (?,50,'c','',1,null,0)", NULL_VISIBILITY);
        jdbc.update("insert into club_member(club_id,member_id,status,del_flag) values (?,?,1,0)", PRIVATE_JOINED, VIEWER);
        jdbc.update("insert into club_member(club_id,member_id,status,del_flag) values (?,?,1,2)", PRIVATE_LEFT, VIEWER);
        jdbc.update("insert into im_block(member_id,blocked_member_id) values (?,?)", VIEWER, BLOCKED_BY_VIEWER);
        jdbc.update("insert into im_block(member_id,blocked_member_id) values (?,?)", BLOCKS_VIEWER, VIEWER);
        jdbc.update("insert into ums_member values (?,'author','')", AUTHOR);
        post(jdbc, 11, PUBLIC_CLUB, AUTHOR, 0);
        post(jdbc, 12, PUBLIC_CLUB, BLOCKED_BY_VIEWER, 0);
        post(jdbc, 13, PUBLIC_CLUB, AUTHOR, 0);
        post(jdbc, 14, PUBLIC_CLUB, AUTHOR, 2);
        post(jdbc, 15, PUBLIC_CLUB, BLOCKS_VIEWER, 0);
        post(jdbc, 21, PRIVATE_CLUB, AUTHOR, 0);
        post(jdbc, 31, PRIVATE_JOINED, AUTHOR, 0);
        post(jdbc, 42, PRIVATE_OWNED, VIEWER, 0);
        post(jdbc, 51, PENDING_CLUB, AUTHOR, 0);
        post(jdbc, 61, DELETED_CLUB, AUTHOR, 0);
        post(jdbc, 71, PRIVATE_LEFT, AUTHOR, 0);
        post(jdbc, 81, NULL_VISIBILITY, AUTHOR, 0);
    }

    private void club(JdbcTemplate jdbc, long id, long owner, int status, int publicVisible, int delFlag) {
        jdbc.update("insert into club(id,member_id,name,logo,status,public_visible,del_flag) values (?,?,'c','',?,?,?)",
                id, owner, status, publicVisible, delFlag);
    }

    private void post(JdbcTemplate jdbc, long id, long clubId, long author, int delFlag) {
        jdbc.update("insert into club_post(id,club_id,author_member_id,content,images,type,like_count,comment_count,del_flag,is_pinned,version,create_time,update_time) "
                + "values (?,?,?,'post','',0,0,0,?,0,0,now(),now())", id, clubId, author, delFlag);
    }
}
