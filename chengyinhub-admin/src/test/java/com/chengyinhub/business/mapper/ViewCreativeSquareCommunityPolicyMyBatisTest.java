package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ViewCreativeSquare;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 真实 MyBatis + H2 回归：关注与双向 Block 必须改变查询结果。 */
class ViewCreativeSquareCommunityPolicyMyBatisTest
{
    @Test
    void followingFeedReturnsOnlyFollowedAndMutuallyVisibleAuthors() throws Exception
    {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        insertPost(jdbc, 1L, 11L, "followed-visible");
        insertPost(jdbc, 2L, 12L, "not-followed");
        insertPost(jdbc, 3L, 13L, "followed-but-blocked");
        jdbc.update("insert into ums_member_follow(member_id, follow_member_id) values (?, ?), (?, ?)",
                8L, 11L, 8L, 13L);
        jdbc.update("insert into im_block(member_id, blocked_member_id) values (?, ?)", 13L, 8L);

        ViewCreativeSquare query = new ViewCreativeSquare();
        query.setStatus(1);
        query.setDelFlag(0);
        query.setFollowingMemberId(8L);
        query.setViewerMemberId(8L);

        try (SqlSession session = factory(dataSource).openSession(true))
        {
            List<ViewCreativeSquare> rows = session.getMapper(ViewCreativeSquareMapper.class)
                    .selectViewCreativeSquareList(query);
            assertEquals(1, rows.size());
            assertEquals(Long.valueOf(1L), rows.get(0).getId());
            assertEquals("followed-visible", rows.get(0).getContents());

            jdbc.update("insert into im_block(member_id, blocked_member_id) values (?, ?)", 8L, 11L);
            // 第二次查询模拟新请求；MyBatis 同一 SqlSession 的一级缓存不会感知外部 JDBC 写入。
            session.clearCache();
            assertEquals(0, session.getMapper(ViewCreativeSquareMapper.class)
                    .selectViewCreativeSquareList(query).size(),
                    "查看者拉黑作者后也必须立即从 Feed 消失");
        }
    }

    @Test
    void favoriteFeedReturnsOnlyBookmarkedPosts() throws Exception
    {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        insertPost(jdbc, 1L, 11L, "saved");
        insertPost(jdbc, 2L, 12L, "not-saved");
        jdbc.update("insert into ums_creative_square(id,member_id) values (1,11)");
        jdbc.update("insert into community_post(id,author_id,client_request_id,safety_labels_json) values (1,11,'legacy-square-1','[\"LEGACY_MIGRATED\"]')");
        jdbc.update("insert into community_post_member_action(post_id, member_id, action_type) values (1, 8, 'BOOKMARK')");

        ViewCreativeSquare query = new ViewCreativeSquare();
        query.setStatus(1);
        query.setDelFlag(0);
        query.setViewerMemberId(8L);
        query.setFavoriteOnly(true);

        try (SqlSession session = factory(dataSource).openSession(true))
        {
            List<ViewCreativeSquare> rows = session.getMapper(ViewCreativeSquareMapper.class)
                    .selectViewCreativeSquareList(query);
            assertEquals(1, rows.size());
            assertEquals(Long.valueOf(1L), rows.get(0).getId());
        }
    }

    private static DriverManagerDataSource dataSource()
    {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:square_community_policy;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static void createSchema(JdbcTemplate jdbc)
    {
        jdbc.execute("drop all objects");
        jdbc.execute("create table view_creative_square("
                + "id bigint primary key, member_id bigint, contents varchar(500), pics varchar(500), file_url varchar(500),"
                + "address varchar(255), longitude varchar(64), latitude varchar(64), data_id bigint, data_type bigint,"
                + "like_num bigint, nolike_num bigint, status int, is_crowdfunding int, del_flag int,"
                + "create_by varchar(64), create_time timestamp, update_by varchar(64), update_time timestamp,"
                + "member_nickname varchar(100), member_avatar varchar(500), member_user_type int, member_level_id bigint,"
                + "comment_count bigint, no_like_count bigint, like_count bigint)");
        jdbc.execute("create alias JSON_TYPE for \"com.chengyinhub.business.longrun.E02_23Test.jsonType\"");
        jdbc.execute("create alias JSON_CONTAINS for \"com.chengyinhub.business.longrun.E02_23Test.jsonContains\"");
        jdbc.execute("create table ums_creative_square(id bigint primary key,member_id bigint)");
        jdbc.execute("create table community_post(id bigint primary key,author_id bigint,client_request_id varchar(96),safety_labels_json varchar(500))");
        jdbc.execute("create table ums_member_follow(member_id bigint, follow_member_id bigint)");
        jdbc.execute("create table im_block(member_id bigint, blocked_member_id bigint)");
        jdbc.execute("create table community_post_member_action(post_id bigint, member_id bigint, action_type varchar(32))");
        // [6-09] 读取端兜底子查询引用 cms_topic(data_type=2 主题帖可见性),H2 里必须有表;
        // 本用例帖子 data_type 为 null,子查询只需列齐 SQL 里出现的列。
        jdbc.execute("create table cms_topic(id bigint primary key, del_flag int, status int, user_status int)");
    }

    private static void insertPost(JdbcTemplate jdbc, Long id, Long memberId, String contents)
    {
        jdbc.update("insert into view_creative_square(id, member_id, contents, status, del_flag) values (?, ?, ?, 1, 0)",
                id, memberId, contents);
    }

    private static SqlSessionFactory factory(DriverManagerDataSource dataSource) throws Exception
    {
        Configuration configuration = new Configuration(new Environment(
                "square-community-policy", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAlias("ViewCreativeSquare", ViewCreativeSquare.class);
        configuration.addMapper(ViewCreativeSquareMapper.class);
        String resource = "mapper/business/ViewCreativeSquareMapper.xml";
        try (InputStream input = ViewCreativeSquareCommunityPolicyMyBatisTest.class
                .getClassLoader().getResourceAsStream(resource))
        {
            if (input == null)
            {
                throw new IllegalStateException("missing " + resource);
            }
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
