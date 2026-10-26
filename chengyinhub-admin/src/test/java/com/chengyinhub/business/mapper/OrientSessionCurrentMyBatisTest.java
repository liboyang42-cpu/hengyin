package com.chengyinhub.business.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class OrientSessionCurrentMyBatisTest {
    @Test
    void queryScopesMemberTopicAndOptionalActivityAndKeepsAllExistingRuns() throws Exception {
        Configuration config = new Configuration();
        config.setEnvironment(new Environment("test", new JdbcTransactionFactory(), new PooledDataSource(
                "org.h2.Driver", "jdbc:h2:mem:orient_current;MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "")));
        String resource = "mapper/business/OrientSessionMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, config, resource, config.getSqlFragments()).parse();
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(config);
        try (SqlSession session = factory.openSession(true); Statement sql = session.getConnection().createStatement()) {
            sql.execute("create table topic_route_session(id bigint primary key, activity_id bigint, topic_id bigint, "
                    + "member_id bigint, play_instance_id varchar(64), config_version bigint, status int, "
                    + "phase varchar(32), version int, completed_at timestamp)");
            sql.execute("insert into topic_route_session values "
                    + "(9223372036854775807,0,10,7,'default',1,0,null,0,null),"
                    + "(21,99,10,7,'default',1,1,'FINISHED',0,null),"
                    + "(22,99,10,7,'replay-1',1,0,null,0,null),"
                    + "(23,99,10,8,'default',1,0,null,0,null),"
                    + "(24,99,11,7,'default',1,0,null,0,null)");
            OrientSessionMapper mapper = session.getMapper(OrientSessionMapper.class);
            var all = mapper.selectOwnedSessions(7L, 10L, null);
            assertEquals(3, all.size());
            assertEquals(Long.MAX_VALUE, all.get(0).getId());
            assertTrue(all.stream().allMatch(row -> row.getMemberId() == 7L && row.getTopicId() == 10L));
            var activity = mapper.selectOwnedSessions(7L, 10L, 99L);
            assertEquals(2, activity.size());
            assertTrue(activity.stream().anyMatch(row -> row.getStatus() == 1));
            assertEquals(1, mapper.selectOwnedSessions(7L, 10L, 0L).size());
            assertTrue(mapper.selectOwnedSessions(9L, 10L, null).isEmpty());
            assertTrue(mapper.selectOwnedSessions(7L, 10L, 100L).isEmpty());
            try (var rows = sql.executeQuery("select count(*) from topic_route_session")) {
                assertTrue(rows.next());
                assertEquals(5, rows.getInt(1));
            }
            try (var rows = sql.executeQuery("select phase from topic_route_session where id=9223372036854775807")) {
                assertTrue(rows.next());
                assertNull(rows.getString(1));
            }
        }
    }
}
