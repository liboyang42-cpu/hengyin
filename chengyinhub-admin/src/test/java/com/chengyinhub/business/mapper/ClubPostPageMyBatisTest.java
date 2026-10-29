package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.service.impl.ClubPostServiceImpl;
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
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P0-82:俱乐部动态流按页取。首页 = 置顶帖(按置顶时间)+ 最新 limit 条非置顶帖;
 * 翻页按 beforeId 游标取更早的非置顶帖,置顶帖不重复出现;已删帖与别的俱乐部不混入。
 */
class ClubPostPageMyBatisTest {
    private static final long CLUB = 7L;
    private static final long OTHER_CLUB = 8L;

    @Test
    void firstPageIsPinnedThenNewestAndCursorWalksOlderPostsWithoutRepeats() throws Exception {
        DriverManagerDataSource ds = dataSource();
        seed(new JdbcTemplate(ds));
        try (SqlSession session = factory(ds).openSession(true)) {
            ClubPostServiceImpl service = new ClubPostServiceImpl();
            ReflectionTestUtils.setField(service, "clubPostMapper", session.getMapper(ClubPostMapper.class));

            assertEquals(List.of(3L, 9L, 8L, 7L), ids(service.selectClubPostPage(CLUB, null, 3)),
                    "first page: pinned post first, then the 3 newest unpinned posts");
            assertEquals(List.of(6L, 5L, 2L), ids(service.selectClubPostPage(CLUB, 7L, 3)),
                    "second page: older unpinned posts only, deleted post 4 skipped");
            assertEquals(List.of(1L), ids(service.selectClubPostPage(CLUB, 2L, 3)),
                    "last page shorter than limit tells the client there is no more");
            assertEquals(List.of(), ids(service.selectClubPostPage(CLUB, 1L, 3)));
        }
    }

    private void seed(JdbcTemplate jdbc) {
        jdbc.execute("create table club(id bigint primary key,name varchar(64),logo varchar(255),del_flag int)");
        jdbc.execute("create table ums_member(id bigint primary key,nickname varchar(64),avatar varchar(255))");
        jdbc.execute("create table club_post(id bigint primary key,club_id bigint,author_member_id bigint,content varchar(255),images varchar(2000),ref_type varchar(32),ref_id bigint,type int,like_count int,comment_count int,repost_count int default 0,del_flag int,is_pinned int,pinned_at timestamp,version int,create_time timestamp,update_time timestamp)");
        jdbc.execute("create table club_post_revision(id bigint primary key,post_id bigint)");
        jdbc.update("insert into club values (?,'club','',0),(?,'other','',0)", CLUB, OTHER_CLUB);
        jdbc.update("insert into ums_member values (11,'author','')");
        for (long id = 1; id <= 9; id++) {
            jdbc.update("insert into club_post(id,club_id,author_member_id,content,type,like_count,comment_count,del_flag,is_pinned,version,create_time,update_time) "
                    + "values (?,?,11,'post',0,0,0,?,?,0,now(),now())",
                    id, CLUB, id == 4 ? 2 : 0, id == 3 ? 1 : 0);
        }
        jdbc.update("update club_post set pinned_at=now(), type=2 where id=3");
        jdbc.update("insert into club_post(id,club_id,author_member_id,content,type,like_count,comment_count,del_flag,is_pinned,version,create_time,update_time) "
                + "values (10,?,11,'other club',0,0,0,0,0,0,now(),now())", OTHER_CLUB);
    }

    private static List<Long> ids(List<ClubPost> rows) {
        return rows.stream().map(ClubPost::getId).collect(Collectors.toList());
    }

    private DriverManagerDataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:club_post_page_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        return ds;
    }

    private SqlSessionFactory factory(DriverManagerDataSource dataSource) throws Exception {
        Configuration configuration = new Configuration(new Environment(
                "club-post-page", new JdbcTransactionFactory(), dataSource));
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
