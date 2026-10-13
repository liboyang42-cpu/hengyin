package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsRegistration;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真 MyBatis + H2 验证主题剧情访问权查询；不冒充生产 MySQL 证据。 */
class CmsRegistrationStoryAccessMyBatisTest {
    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("CmsRegistration", CmsRegistration.class);
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:story_access_" + System.nanoTime()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/business/CmsRegistrationMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            statement.execute("create table cms_registration("
                    + "id bigint primary key, member_id bigint, owner_type bigint, owner_id bigint, topic_id bigint,"
                    + "purchase_kind int, payment_status int, registration_status int, expires_at timestamp)");
            statement.execute("create table cms_activity(id bigint primary key, topic_id bigint)");
            statement.execute("create table registration_entitlement("
                    + "registration_id bigint, topic_id bigint, status int)");
            statement.execute("insert into cms_activity values(11, 7)");
        }
    }

    @Test
    void exploreTicketUnlocksOnlyAfterActiveEntitlementExists() throws Exception {
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            CmsRegistrationMapper mapper = session.getMapper(CmsRegistrationMapper.class);
            assertFalse(mapper.hasActiveTopicStoryAccess(7L, 9L));

            statement.execute("insert into cms_registration values(21,9,2,11,null,3,2,2,null)");
            session.clearCache();
            assertFalse(mapper.hasActiveTopicStoryAccess(7L, 9L),
                    "仅有报名主表而无权益回读时必须保持锁定");

            statement.execute("insert into registration_entitlement values(21,7,0)");
            session.clearCache();
            assertTrue(mapper.hasActiveTopicStoryAccess(7L, 9L),
                    "支付成功且权益有效时才能解锁完整剧情");

            mapper.deleteCmsRegistrationById(21L);
            session.clearCache();
            assertFalse(mapper.hasActiveTopicStoryAccess(7L, 9L),
                    "物理删除的报名不得继续解锁剧情");

            statement.execute("insert into cms_registration values(21,9,2,11,null,3,2,2,null)");
            statement.executeUpdate("update registration_entitlement set status=2 where registration_id=21");
            session.clearCache();
            assertFalse(mapper.hasActiveTopicStoryAccess(7L, 9L),
                    "退款或取消导致权益失效后必须重新锁定");
        }
    }
}
