package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.vo.ClubSessionGapVO;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真 MyBatis + H2 构造组织槽/场次事实，验证对账查询能让断链变红。 */
class ClubSessionReconciliationMyBatisTest {

    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("CmsActivity", CmsActivity.class);
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:club_session_reconciliation_" + System.nanoTime()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/business/CmsActivityMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true); Statement statement = session.getConnection().createStatement()) {
            statement.execute("create table coop_order (id bigint primary key, topic_id bigint, to_id bigint, to_type varchar(16), status int, del_flag int)");
            statement.execute("create table cms_activity (id bigint primary key, topic_id bigint, club_id bigint, del_flag int)");
            statement.execute("insert into coop_order values (1, 31, 21, 'club', 0, 0)");
        }
    }

    @Test
    void effectiveClubOrderWithOnlyUnboundActivityIsReported() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            session.getConnection().createStatement().execute(
                    "insert into cms_activity values (51, 31, null, 0)");

            List<ClubSessionGapVO> gaps = session.getMapper(CmsActivityMapper.class)
                    .selectClubOrderTopicsWithoutClubSession();

            assertEquals(1, gaps.size());
            assertEquals(Long.valueOf(31L), gaps.get(0).getTopicId());
            assertEquals(Long.valueOf(21L), gaps.get(0).getClubId());
        }
    }

    @Test
    void activityBoundToTheContractClubClosesTheGap() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            session.getConnection().createStatement().execute(
                    "insert into cms_activity values (51, 31, 21, 0)");

            assertTrue(session.getMapper(CmsActivityMapper.class)
                    .selectClubOrderTopicsWithoutClubSession().isEmpty());
        }
    }
}
