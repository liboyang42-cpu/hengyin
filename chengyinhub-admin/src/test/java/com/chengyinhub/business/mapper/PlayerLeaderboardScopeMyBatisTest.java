package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.vo.LeaderboardItemVO;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerLeaderboardScopeMyBatisTest
{
    private static SqlSessionFactory factory;

    @BeforeAll
    static void setUp() throws Exception
    {
        Configuration configuration = new Configuration();
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:leaderboard_scope;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAlias("PlayerNodeProgress", PlayerNodeProgress.class);
        String resource = "mapper/business/PlayerNodeProgressMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource))
        {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true);
             Statement st = session.getConnection().createStatement())
        {
            st.execute("create table topic_route_session(id bigint primary key,activity_id bigint,topic_id bigint,member_id bigint,play_instance_id varchar(64))");
            st.execute("create table ums_member (id bigint primary key, nickname varchar(50), avatar varchar(255))");
            st.execute("create table player_node_progress (id bigint primary key, member_id bigint, activity_id bigint, " +
                    "topic_id bigint, node_id bigint, status int, checkin_time timestamp, del_flag int)");
            st.execute("insert into ums_member values (1, '自玩玩家', 'a'), (2, '活动玩家', 'b')");
            st.execute("alter table player_node_progress add column play_instance_id varchar(64) default 'default'");
            st.execute("insert into player_node_progress(id,member_id,activity_id,topic_id,node_id,status,checkin_time,del_flag) values " +
                    "(1, 1, 0, 300, 11, 1, timestamp '2026-08-21 10:00:00', 0)," +
                    "(2, 2, 500, 300, 12, 1, timestamp '2026-08-21 10:01:00', 0)");
        }
    }

    @Test
    void topicLeaderboardOnlyCountsSelfPlaySession()
    {
        try (SqlSession session = factory.openSession(true))
        {
            List<LeaderboardItemVO> rows = session.getMapper(PlayerNodeProgressMapper.class)
                    .selectActivityLeaderboard(null, 300L, 50);
            assertEquals(1, rows.size());
            assertEquals(Long.valueOf(1L), rows.get(0).getMemberId());
        }
    }

    @Test
    void activityLeaderboardStillCountsItsOwnSession()
    {
        try (SqlSession session = factory.openSession(true))
        {
            List<LeaderboardItemVO> rows = session.getMapper(PlayerNodeProgressMapper.class)
                    .selectActivityLeaderboard(500L, null, 50);
            assertEquals(1, rows.size());
            assertEquals(Long.valueOf(2L), rows.get(0).getMemberId());
        }
    }
}
