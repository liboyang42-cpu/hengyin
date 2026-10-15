package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.BizFunnelDaily;
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
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 真实 MyBatis + H2 回归：试点窗口的核心分母不受帖文后续状态变化影响。 */
public class BizFunnelDailyMapperMyBatisTest {
    @Test
    void pilotDenominatorSurvivesHideDeleteAndAuthorErasure() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        insertPost(jdbc, 10L, 100L, "PUBLISHED");
        insertPost(jdbc, 20L, 200L, "PUBLISHED");
        insertQualifiedView(jdbc, 1L, 10L, LocalDateTime.of(2026, 9, 2, 10, 0));
        insertQualifiedView(jdbc, 1L, 10L, LocalDateTime.of(2026, 9, 3, 10, 0));
        insertQualifiedView(jdbc, 1L, 20L, LocalDateTime.of(2026, 9, 4, 10, 0));
        insertQualifiedView(jdbc, 2L, 20L, LocalDateTime.of(2026, 9, 5, 10, 0));

        try (SqlSession session = factory(dataSource).openSession(true)) {
            BizFunnelDailyMapper mapper = session.getMapper(BizFunnelDailyMapper.class);
            assertMetric(mapper.selectCommunityQualifiedPilotMetric("2026-09-01", "2026-09-28"),
                    2L, 3L, 2L, 2L);

            jdbc.update("update community_post set lifecycle = 'HIDDEN' where id = 10");
            session.clearCache();
            assertMetric(mapper.selectCommunityQualifiedPilotMetric("2026-09-01", "2026-09-28"),
                    2L, 3L, 2L, 2L);

            jdbc.update("delete from community_post where id = 10");
            session.clearCache();
            assertMetric(mapper.selectCommunityQualifiedPilotMetric("2026-09-01", "2026-09-28"),
                    2L, 3L, 2L, 1L);

            jdbc.update("update community_post set author_id = 0 where id = 20");
            session.clearCache();
            assertMetric(mapper.selectCommunityQualifiedPilotMetric("2026-09-01", "2026-09-28"),
                    2L, 3L, 2L, 0L);
        }
    }

    @Test
    void pilotWindowIncludesWholeEndDateAndExcludesAdjacentDays() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        insertPost(jdbc, 10L, 100L, "PUBLISHED");
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0);
        LocalDateTime nextDay = LocalDateTime.of(2026, 10, 1, 0, 0);
        insertQualifiedView(jdbc, 1L, 10L, start.minusSeconds(1));
        insertQualifiedView(jdbc, 2L, 10L, start);
        insertQualifiedView(jdbc, 3L, 10L, nextDay.minusNanos(1000));
        insertQualifiedView(jdbc, 4L, 10L, nextDay);

        try (SqlSession session = factory(dataSource).openSession(true)) {
            BizFunnelDailyMapper mapper = session.getMapper(BizFunnelDailyMapper.class);
            Map<String, Object> metric = mapper.selectCommunityQualifiedPilotMetric("2026-09-01", "2026-09-30");
            assertMetric(metric, 2L, 2L, 1L, 1L);
            assertEquals(Timestamp.valueOf(start), metric.get("FIRST_QUALIFIED_AT"));
            assertEquals(Timestamp.valueOf(nextDay.minusNanos(1000)), metric.get("LAST_QUALIFIED_AT"));
        }
    }

    private static void assertMetric(Map<String, Object> metric,
                                     long users,
                                     long userPosts,
                                     long posts,
                                     long currentAuthors) {
        assertEquals(users, number(metric, "QUALIFIED_USER_COUNT"));
        assertEquals(userPosts, number(metric, "QUALIFIED_USER_POST_COUNT"));
        assertEquals(posts, number(metric, "QUALIFIED_POST_COUNT"));
        assertEquals(currentAuthors, number(metric, "CURRENT_QUALIFIED_AUTHOR_COUNT"));
    }

    private static long number(Map<String, Object> metric, String key) {
        return ((Number) metric.get(key)).longValue();
    }

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:community_funnel_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=FALSE;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("create table biz_event_log("
                + "id bigint auto_increment primary key, event_name varchar(80), biz_type varchar(40),"
                + "biz_id bigint, member_id bigint, occurred_at timestamp)");
        jdbc.execute("create table community_post("
                + "id bigint primary key, author_id bigint, lifecycle varchar(20))");
    }

    private static void insertPost(JdbcTemplate jdbc, long id, long authorId, String lifecycle) {
        jdbc.update("insert into community_post(id, author_id, lifecycle) values (?, ?, ?)",
                id, authorId, lifecycle);
    }

    private static void insertQualifiedView(JdbcTemplate jdbc,
                                            long memberId,
                                            long postId,
                                            LocalDateTime occurredAt) {
        jdbc.update("insert into biz_event_log(event_name, biz_type, biz_id, member_id, occurred_at) "
                        + "values ('community_post_qualified_view', 'community_post', ?, ?, ?)",
                postId, memberId, Timestamp.valueOf(occurredAt));
    }

    private static SqlSessionFactory factory(DriverManagerDataSource dataSource) throws Exception {
        Configuration configuration = new Configuration(new Environment(
                "community-funnel", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAlias("BizFunnelDaily", BizFunnelDaily.class);
        configuration.addMapper(BizFunnelDailyMapper.class);
        String resource = "mapper/business/BizFunnelDailyMapper.xml";
        try (InputStream input = BizFunnelDailyMapperMyBatisTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("missing " + resource);
            }
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
