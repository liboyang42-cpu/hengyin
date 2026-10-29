package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.domain.ClubPostRepost;
import com.chengyinhub.business.service.impl.ClubPostRepostServiceImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dw29 L8 转发:真 SQL 跑 ClubPostRepostServiceImpl + 两个 mapper XML。
 * 去重(同一人同一帖至多一条有效转发、重复转发不重复计数)、撤销(置 del_flag=2,再转发复用同一行)、
 * 转发数跟着真实状态走,以及动态流取转发条目的范围与时间窗。
 */
class ClubPostRepostMyBatisTest {
    private static final long CLUB = 7L, OTHER_CLUB = 8L, DISSOLVED_CLUB = 9L;
    private static final long POST = 100L, DELETED_POST = 101L, OTHER_POST = 102L, DISSOLVED_POST = 103L;
    private static final long ME = 11L, MATE = 12L, OUTSIDER = 13L, PENDING = 14L;

    private SqlSession session;
    private JdbcTemplate jdbc;
    private ClubPostRepostServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = dataSource();
        jdbc = new JdbcTemplate(ds);
        seed();
        session = factory(ds).openSession(true);
        service = new ClubPostRepostServiceImpl();
        ReflectionTestUtils.setField(service, "clubPostRepostMapper", session.getMapper(ClubPostRepostMapper.class));
        ReflectionTestUtils.setField(service, "clubPostMapper", session.getMapper(ClubPostMapper.class));
    }

    @AfterEach
    void tearDown() {
        session.close();
    }

    @Nested
    class Dedup {
        @Test
        void repeatedRepostKeepsOneActiveRowAndCountsOnce() {
            assertTrue(service.setReposted(POST, ME, true));
            assertFalse(service.setReposted(POST, ME, true), "second repost is a no-op");
            assertEquals(1, rows(POST, ME));
            assertEquals(1, repostCount(POST));
            assertTrue(service.isReposted(POST, ME));
        }

        @Test
        void eachMemberRepostsIndependently() {
            service.setReposted(POST, ME, true);
            service.setReposted(POST, MATE, true);
            assertEquals(2, repostCount(POST));
            assertEquals(Set.of(POST), service.selectRepostedPostIds(MATE, List.of(POST, OTHER_POST)));
        }
    }

    @Nested
    class Undo {
        @Test
        void undoSoftDeletesAndRepostAgainReusesTheSameRowWithFreshTime() {
            service.setReposted(POST, ME, true);
            Long firstId = jdbc.queryForObject("select id from club_post_repost where post_id=? and member_id=?", Long.class, POST, ME);
            jdbc.update("update club_post_repost set create_time=? where id=?", ts(1), firstId);

            assertTrue(service.setReposted(POST, ME, false));
            assertFalse(service.isReposted(POST, ME));
            assertEquals(Integer.valueOf(2), jdbc.queryForObject("select del_flag from club_post_repost where id=?", Integer.class, firstId));
            assertEquals(0, repostCount(POST));
            assertFalse(service.setReposted(POST, ME, false), "undo twice is a no-op");
            assertEquals(0, repostCount(POST), "count never goes below the real state");

            assertTrue(service.setReposted(POST, ME, true));
            assertEquals(1, rows(POST, ME), "re-repost reactivates, never a second row");
            assertEquals(firstId, jdbc.queryForObject("select id from club_post_repost where post_id=? and member_id=?", Long.class, POST, ME));
            Timestamp refreshed = jdbc.queryForObject("select create_time from club_post_repost where id=?", Timestamp.class, firstId);
            assertTrue(refreshed.after(ts(1)), "repost time moves to the new repost");
            assertEquals(1, repostCount(POST));
        }

        @Test
        void undoWithoutRepostChangesNothing() {
            assertFalse(service.setReposted(POST, ME, false));
            assertEquals(0, rows(POST, ME));
            assertEquals(0, repostCount(POST));
        }
    }

    @Nested
    class Feed {
        @Test
        void feedTakesActiveRepostsByCurrentMembersNewestFirstWithinWindow() {
            insertRepost(1, POST, ME, 10, 0);
            insertRepost(2, OTHER_POST, MATE, 30, 0);
            insertRepost(3, POST, MATE, 20, 2);       // 已撤销
            insertRepost(4, POST, OUTSIDER, 40, 0);   // 不是成员
            insertRepost(5, POST, PENDING, 50, 0);    // 申请中,不在册

            List<ClubPostRepost> all = service.selectFeedReposts(List.of(CLUB), null, null, 20);
            assertEquals(List.of(2L, 1L), all.stream().map(ClubPostRepost::getId).collect(Collectors.toList()));
            assertEquals("mate", all.get(0).getNickname());

            assertEquals(List.of(2L), ids(service.selectFeedReposts(List.of(CLUB), ts(30), null, 20)), "since is inclusive");
            assertEquals(List.of(1L), ids(service.selectFeedReposts(List.of(CLUB), null, ts(30), 20)), "until is exclusive");
            assertEquals(List.of(2L), ids(service.selectFeedReposts(List.of(CLUB), null, null, 1)));
            assertEquals(List.of(), ids(service.selectFeedReposts(List.of(), null, null, 20)), "empty scope never reaches SQL");
        }

        @Test
        void originalsSkipDeletedPostsAndDissolvedClubs() {
            List<ClubPost> originals = service.selectOriginalPosts(List.of(POST, DELETED_POST, OTHER_POST, DISSOLVED_POST));
            assertEquals(Set.of(POST, OTHER_POST), originals.stream().map(ClubPost::getId).collect(Collectors.toSet()));
            ClubPost other = originals.stream().filter(p -> p.getId() == OTHER_POST).findFirst().orElseThrow();
            assertEquals("other", other.getClubName());
            assertEquals("author", other.getNickname());
        }
    }

    private void seed() {
        jdbc.execute("create table club(id bigint primary key,name varchar(64),logo varchar(255),del_flag int)");
        jdbc.execute("create table ums_member(id bigint primary key,nickname varchar(64),avatar varchar(255))");
        jdbc.execute("create table club_member(id bigint auto_increment primary key,club_id bigint,member_id bigint,status int,del_flag int)");
        jdbc.execute("create table club_post(id bigint primary key,club_id bigint,author_member_id bigint,content varchar(255),images varchar(2000),ref_type varchar(32),ref_id bigint,type int,like_count int,comment_count int,repost_count int default 0,del_flag int,is_pinned int,pinned_at timestamp,version int,create_time timestamp,update_time timestamp)");
        jdbc.execute("create table club_post_revision(id bigint primary key,post_id bigint)");
        jdbc.execute("create table club_post_repost(id bigint auto_increment primary key,post_id bigint not null,member_id bigint not null,create_time timestamp,del_flag tinyint not null default 0,unique(post_id,member_id))");
        jdbc.update("insert into club values (?,'club','',0),(?,'other','',0),(?,'gone','',2)", CLUB, OTHER_CLUB, DISSOLVED_CLUB);
        jdbc.update("insert into ums_member values (10,'author',''),(?,'me',''),(?,'mate',''),(?,'outsider',''),(?,'pending','')", ME, MATE, OUTSIDER, PENDING);
        jdbc.update("insert into club_member(club_id,member_id,status,del_flag) values (?,?,1,0),(?,?,1,0),(?,?,0,0),(?,?,1,0)",
                CLUB, ME, CLUB, MATE, CLUB, PENDING, OTHER_CLUB, OUTSIDER);
        post(POST, CLUB, 0);
        post(DELETED_POST, CLUB, 2);
        post(OTHER_POST, OTHER_CLUB, 0);
        post(DISSOLVED_POST, DISSOLVED_CLUB, 0);
    }

    private void post(long id, long club, int delFlag) {
        jdbc.update("insert into club_post(id,club_id,author_member_id,content,type,like_count,comment_count,repost_count,del_flag,is_pinned,version,create_time,update_time) "
                + "values (?,?,10,'post',0,0,0,0,?,0,0,now(),now())", id, club, delFlag);
    }

    private void insertRepost(long id, long post, long member, int minute, int delFlag) {
        jdbc.update("insert into club_post_repost(id,post_id,member_id,create_time,del_flag) values (?,?,?,?,?)",
                id, post, member, ts(minute), delFlag);
    }

    private int rows(long post, long member) {
        return jdbc.queryForObject("select count(*) from club_post_repost where post_id=? and member_id=?", Integer.class, post, member);
    }

    private int repostCount(long post) {
        return jdbc.queryForObject("select repost_count from club_post where id=?", Integer.class, post);
    }

    private static Timestamp ts(int minute) {
        return Timestamp.valueOf(String.format("2026-10-29 10:%02d:00", minute));
    }

    private static List<Long> ids(List<ClubPostRepost> rows) {
        return rows.stream().map(ClubPostRepost::getId).collect(Collectors.toList());
    }

    private DriverManagerDataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:club_post_repost_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        return ds;
    }

    private SqlSessionFactory factory(DriverManagerDataSource dataSource) throws Exception {
        Configuration configuration = new Configuration(new Environment(
                "club-post-repost", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAlias("ClubPost", ClubPost.class);
        configuration.getTypeAliasRegistry().registerAlias("ClubPostRepost", ClubPostRepost.class);
        configuration.addMapper(ClubPostMapper.class);
        configuration.addMapper(ClubPostRepostMapper.class);
        for (String resource : new String[] {"mapper/business/ClubPostMapper.xml", "mapper/business/ClubPostRepostMapper.xml"}) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (input == null) throw new IllegalStateException("missing " + resource);
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
