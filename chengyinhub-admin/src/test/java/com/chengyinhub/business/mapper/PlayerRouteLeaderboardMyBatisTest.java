package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.vo.LeaderboardItemVO;
import com.chengyinhub.business.service.support.LeaderboardRankPolicy;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * D-13(N-02)分支主题排行榜 SQL:到达结局(会话 COMPLETED)优先 → 结局总用时升序 →
 * 未到结局按本局完成节点数;只看每人本场最新一局;ENDED(失败收场)不算到达结局。
 */
class PlayerRouteLeaderboardMyBatisTest
{
    private static final long TOPIC = 300L;
    private static final long ACT = 500L;
    private static SqlSessionFactory factory;

    @BeforeAll
    static void setUp() throws Exception
    {
        Configuration configuration = new Configuration();
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:route_leaderboard;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
            st.execute("create table topic_route_session(id bigint primary key, activity_id bigint, topic_id bigint, " +
                    "member_id bigint, play_instance_id varchar(64), status int, create_time timestamp, completed_at timestamp)");
            st.execute("create table ums_member (id bigint primary key, nickname varchar(50), avatar varchar(255))");
            st.execute("create table player_node_progress (id bigint primary key, member_id bigint, activity_id bigint, " +
                    "topic_id bigint, node_id bigint, status int, checkin_time timestamp, del_flag int, play_instance_id varchar(64))");
            st.execute("insert into ums_member values (1,'快',''),(2,'慢',''),(3,'多节点未完',''),(4,'少节点未完',''),"
                    + "(5,'失败收场',''),(6,'重开一局',''),(7,'别场',''),(8,'并列快','')");
            // 1: 30 分钟到达结局,只走了 1 个节点(分支短路线) —— 不能因节点少被排到后面
            // 2: 60 分钟到达结局,走了 3 个节点
            // 3: 未结束,4 个节点;4: 未结束,1 个节点;5: ENDED(失败),5 个节点 —— 三者都没到结局,按节点数排
            // 6: 第一局 10 分钟通关,但最新一局在跑 ⇒ 只看最新一局 = 未到结局
            // 7: 别的场次 10 分钟通关,不得混入本场
            // 8: 与 1 同为 30 分钟 ⇒ 并列
            st.execute("insert into topic_route_session values "
                    + "(1,500,300,1,'default',1,timestamp '2026-10-01 10:00:00',timestamp '2026-10-01 10:30:00'),"
                    + "(2,500,300,2,'default',1,timestamp '2026-10-01 09:00:00',timestamp '2026-10-01 10:00:00'),"
                    + "(3,500,300,3,'default',0,timestamp '2026-10-01 09:00:00',null),"
                    + "(4,500,300,4,'default',0,timestamp '2026-10-01 09:00:00',null),"
                    + "(5,500,300,5,'default',2,timestamp '2026-10-01 09:00:00',timestamp '2026-10-01 09:20:00'),"
                    + "(6,500,300,6,'default',1,timestamp '2026-10-01 09:00:00',timestamp '2026-10-01 09:10:00'),"
                    + "(9,500,300,6,'replay-2',0,timestamp '2026-10-02 09:00:00',null),"
                    + "(7,501,300,7,'default',1,timestamp '2026-10-01 09:00:00',timestamp '2026-10-01 09:10:00'),"
                    + "(8,500,300,8,'default',1,timestamp '2026-10-01 11:00:00',timestamp '2026-10-01 11:30:00')");
            StringBuilder rows = new StringBuilder();
            long id = 1;
            int[][] done = {{1, 1}, {2, 3}, {3, 4}, {4, 1}, {5, 5}, {8, 1}};
            for (int[] d : done) {
                for (int n = 0; n < d[1]; n++) {
                    if (rows.length() > 0) rows.append(',');
                    rows.append("(").append(id++).append(',').append(d[0]).append(",500,300,").append(10 + n)
                            .append(",1,timestamp '2026-10-01 09:05:00',0,'default')");
                }
            }
            // 6 号第一局的完成行属于 default 实例,不得计入最新一局(replay-2)
            rows.append(",(").append(id++).append(",6,500,300,10,1,timestamp '2026-10-01 09:05:00',0,'default')");
            // 软删与未完成行不计分
            rows.append(",(").append(id++).append(",3,500,300,90,1,timestamp '2026-10-01 09:05:00',2,'default')");
            rows.append(",(").append(id).append(",3,500,300,91,0,timestamp '2026-10-01 09:05:00',0,'default')");
            st.execute("insert into player_node_progress(id,member_id,activity_id,topic_id,node_id,status,checkin_time,del_flag,play_instance_id) values " + rows);
        }
    }

    @Test
    void branchingBoardRanksReachedEndingFirstThenElapsedTimeWithTies()
    {
        try (SqlSession session = factory.openSession(true))
        {
            List<LeaderboardItemVO> rows = session.getMapper(PlayerNodeProgressMapper.class)
                    .selectRouteLeaderboard(ACT, TOPIC, 50);
            assertEquals(List.of(1L, 8L, 2L, 5L, 3L, 4L, 6L),
                    rows.stream().map(LeaderboardItemVO::getMemberId).collect(Collectors.toList()));
            assertEquals(Boolean.TRUE, rows.get(0).getReachedEnding());
            assertEquals(Long.valueOf(1800L), rows.get(0).getElapsedSeconds());
            assertEquals(Long.valueOf(1L), rows.get(0).getScore(), "score 仍是本局完成节点数");
            assertEquals(Long.valueOf(3600L), rows.get(2).getElapsedSeconds());
            assertEquals(Boolean.FALSE, rows.get(3).getReachedEnding(), "ENDED 失败收场不算到达结局");
            assertNull(rows.get(3).getElapsedSeconds());
            assertEquals(Long.valueOf(4L), rows.get(4).getScore(), "软删/未完成行不计分");
            assertEquals(Long.valueOf(0L), rows.get(6).getScore(), "只看最新一局及其实例的完成行");

            LeaderboardRankPolicy.assignRanks(rows, true);
            assertEquals(List.of(1, 1, 3, 4, 5, 6, 7),
                    rows.stream().map(LeaderboardItemVO::getRank).collect(Collectors.toList()),
                    "同用时并列同名次,下一名跳号");
        }
    }

    @Test
    void selfPlayBoardIsScopedToActivityZeroAndMyRowReadsLatestRun()
    {
        try (SqlSession session = factory.openSession(true))
        {
            PlayerNodeProgressMapper mapper = session.getMapper(PlayerNodeProgressMapper.class);
            assertEquals(List.of(7L), mapper.selectRouteLeaderboard(501L, TOPIC, 50).stream()
                    .map(LeaderboardItemVO::getMemberId).collect(Collectors.toList()));
            assertEquals(0, mapper.selectRouteLeaderboard(0L, TOPIC, 50).size());
            LeaderboardItemVO mine = mapper.selectRouteLeaderboardRow(ACT, TOPIC, 6L);
            assertEquals(Boolean.FALSE, mine.getReachedEnding());
            assertNull(mapper.selectRouteLeaderboardRow(ACT, TOPIC, 99L));
        }
    }
}
