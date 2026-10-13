package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.vo.PublicMemberVO;
import com.chengyinhub.business.mapper.PublicMemberMapper;
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
import java.sql.Connection;
import java.sql.Statement;
import static org.junit.jupiter.api.Assertions.*;

/** Actual public projection and existing mutual-follow query over a task-owned MySQL fixture. */
class PublicMemberFriendCountMySqlIT {
    @Test void publicProfileCountsDistinctActiveReciprocalPeersAndNeverReturnsRelationIds() throws Exception {
        PooledDataSource source = new PooledDataSource("com.mysql.cj.jdbc.Driver", MybatisItDatabase.url("public_member_friend"),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        try {
            try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
                assertTrue(connection.getMetaData().getDatabaseProductVersion().startsWith("8.0"));
                sql.execute("CREATE TABLE ums_member(id BIGINT PRIMARY KEY,nickname VARCHAR(64),avatar VARCHAR(255),introduction VARCHAR(255),case_pics TEXT,case_introducton TEXT,tag_ids VARCHAR(64),role VARCHAR(16),status INT,del_flag INT,create_time DATETIME)");
                sql.execute("CREATE TABLE player_growth(member_id BIGINT PRIMARY KEY,level INT)");
                sql.execute("CREATE TABLE ums_member_follow(id BIGINT PRIMARY KEY,member_id BIGINT,follow_member_id BIGINT)");
                sql.execute("INSERT INTO ums_member(id,nickname,role,status,del_flag) VALUES(1,'主体','player',1,0),(2,'互关','player',1,0),(3,'停用','player',0,0),(4,'单向','player',1,0),(5,'已删除','player',1,1),(6,'另一个互关','club',1,0)");
                sql.execute("INSERT INTO ums_member_follow VALUES(1,1,2),(2,2,1),(3,1,2),(4,1,3),(5,3,1),(6,1,4),(7,1,5),(8,5,1),(9,1,1),(10,1,6),(11,6,1)");
                // Setup uses JDBC auto-commit; the mapper below reads through a separate connection.
                assertTrue(connection.getAutoCommit());
            }
            Configuration config = new Configuration();
            config.setEnvironment(new Environment("it",new JdbcTransactionFactory(),source));
            String resource="mapper/business/PublicMemberMapper.xml";
            try (InputStream stream=Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(stream,config,resource,config.getSqlFragments()).parse();
            }
            SqlSessionFactory factory=new SqlSessionFactoryBuilder().build(config);
            try (SqlSession session=factory.openSession()) {
                PublicMemberMapper mapper=session.getMapper(PublicMemberMapper.class);
                PublicMemberVO member=mapper.selectPublicMemberById(1L);
                assertEquals(2,member.getFriendNum());
                assertNull(member.getIsFollow());
                assertNull(mapper.selectPublicMemberById(3L));
                assertNull(mapper.selectPublicMemberById(5L));
                String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(member);
                assertFalse(json.contains("followMemberId"));
                assertFalse(json.contains("password"));
                assertTrue(json.contains("\"friendNum\":2"));
            }
        } finally { source.forceCloseAll(); }
    }
}
