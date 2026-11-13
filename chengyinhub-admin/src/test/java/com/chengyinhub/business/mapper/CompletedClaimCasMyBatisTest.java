package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.TopicRouteSession;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Date;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the real mapper XML against isolated H2, not a production database. */
class CompletedClaimCasMyBatisTest {
    private SqlSessionFactory factory;

    @BeforeEach void setUp() throws Exception {
        Configuration configuration = new Configuration();
        PooledDataSource ds = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:completed_claim_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), ds));
        String resource = "mapper/business/TopicRouteSessionMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession sql = factory.openSession(true); Statement st = sql.getConnection().createStatement()) {
            st.execute("create table topic_route_session(id bigint primary key,member_id bigint,version int,status int,"
                    + "state_json varchar(500),current_node_id bigint,recommended_node_id bigint,"
                    + "completed_at timestamp,update_time timestamp)");
            st.execute("insert into topic_route_session values(99,7,4,1,'before',11,12,"
                    + "timestamp '2026-11-01 01:00:00',timestamp '2026-11-01 01:00:00')");
        }
    }

    private TopicRouteSession claim() {
        TopicRouteSession row = new TopicRouteSession();
        row.setId(99L); row.setMemberId(7L); row.setVersion(4); row.setStatus(1);
        row.setStateJson("after"); row.setUpdateTime(new Date(1793577600000L));
        // Deliberately different: completed-claim SQL must never persist these fields.
        row.setCompletedAt(null); row.setCurrentNodeId(88L); row.setRecommendedNodeId(89L);
        return row;
    }

    @Test void completedClaimChangesOnlyStateAndVersionAndKeepsTerminalFacts() throws Exception {
        try (SqlSession sql = factory.openSession(true)) {
            assertEquals(1, sql.getMapper(TopicRouteSessionMapper.class).updateCompletedClaimCas(claim()));
            try (Statement st = sql.getConnection().createStatement(); ResultSet rs = st.executeQuery("select * from topic_route_session")) {
                assertTrue(rs.next()); assertEquals("after", rs.getString("state_json"));
                assertEquals(5, rs.getInt("version")); assertEquals(1, rs.getInt("status"));
                assertEquals(1793577600000L, rs.getTimestamp("update_time").getTime());
                assertEquals(11, rs.getLong("current_node_id")); assertEquals(12, rs.getLong("recommended_node_id"));
                assertEquals("2026-11-01 01:00:00.0", rs.getTimestamp("completed_at").toString());
            }
        }
    }

    @Test void staleVersionAndForeignOwnerCannotWrite() {
        try (SqlSession sql = factory.openSession(true)) {
            TopicRouteSessionMapper mapper = sql.getMapper(TopicRouteSessionMapper.class);
            TopicRouteSession foreign = claim(); foreign.setMemberId(8L);
            assertEquals(0, mapper.updateCompletedClaimCas(foreign));
            TopicRouteSession stale = claim(); stale.setVersion(3);
            assertEquals(0, mapper.updateCompletedClaimCas(stale));
            assertEquals(1, mapper.updateCompletedClaimCas(claim()));
            assertEquals(0, mapper.updateCompletedClaimCas(claim()), "same expected version cannot win twice");
        }
    }

    @Test void completedClaimRejectsRunningAndOtherTerminalDatabaseStates() throws Exception {
        try (SqlSession sql = factory.openSession(true); Statement st = sql.getConnection().createStatement()) {
            for (int status : new int[]{0, 2, 3}) {
                st.executeUpdate("update topic_route_session set status=" + status);
                assertEquals(0, sql.getMapper(TopicRouteSessionMapper.class).updateCompletedClaimCas(claim()));
            }
        }
    }

    @Test void callerCannotUseCompletedClaimToRequestAnotherStatus() {
        try (SqlSession sql = factory.openSession(true)) {
            for (Integer status : new Integer[]{null, 0, 2, 3}) {
                TopicRouteSession row = claim(); row.setStatus(status);
                assertEquals(0, sql.getMapper(TopicRouteSessionMapper.class).updateCompletedClaimCas(row));
            }
        }
    }

    @Test void originalRunningCasRemainsClosedToCompletedRows() {
        try (SqlSession sql = factory.openSession(true)) {
            assertEquals(0, sql.getMapper(TopicRouteSessionMapper.class).updateStateCas(claim()));
        }
    }

    @Test void completedClaimCanBeRolledBackWithItsTransaction() throws Exception {
        try (SqlSession sql = factory.openSession(false)) {
            assertEquals(1, sql.getMapper(TopicRouteSessionMapper.class).updateCompletedClaimCas(claim()));
            sql.rollback();
        }
        try (SqlSession sql = factory.openSession(true); Statement st = sql.getConnection().createStatement();
             ResultSet rs = st.executeQuery("select state_json,version,status from topic_route_session")) {
            assertTrue(rs.next()); assertEquals("before", rs.getString(1));
            assertEquals(4, rs.getInt(2)); assertEquals(1, rs.getInt(3));
        }
    }
}
