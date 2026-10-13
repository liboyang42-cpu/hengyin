package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.vo.TopicCompletionAwardVO;
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
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 通关 XP 发放事实的真实 MyBatis 写入、唯一键和读回。 */
class TopicCompletionXpAwardMapperMyBatisTest
{
    private static SqlSessionFactory factory;

    @BeforeAll
    static void setUp() throws Exception
    {
        Configuration configuration = new Configuration();
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:topic_completion_xp_award;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/business/TopicCompletionXpAwardMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            statement.execute("create table topic_completion_xp_award (id bigint auto_increment primary key, " +
                    "member_id bigint not null, topic_id bigint not null, xp_amount int not null, " +
                    "granted_at timestamp not null, create_time timestamp not null, unique(member_id, topic_id))");
        }
    }

    @Test
    void persistsExactAmountReadsItBackAndRejectsDuplicate() throws Exception
    {
        Date grantedAt = new Date(1_755_244_320_000L);
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            statement.execute("delete from topic_completion_xp_award");
            TopicCompletionXpAwardMapper mapper = session.getMapper(TopicCompletionXpAwardMapper.class);

            assertEquals(1, mapper.insert(9L, 77L, 36, grantedAt));
            TopicCompletionAwardVO fact = mapper.selectOne(9L, 77L);
            assertEquals(36, fact.getEarnedXp());
            assertEquals(grantedAt, fact.getEarnedXpAt());
            assertThrows(org.apache.ibatis.exceptions.PersistenceException.class,
                    () -> mapper.insert(9L, 77L, 36, grantedAt),
                    "同一玩家同一期不能落第二条 XP 发放事实");
        }
    }
}
