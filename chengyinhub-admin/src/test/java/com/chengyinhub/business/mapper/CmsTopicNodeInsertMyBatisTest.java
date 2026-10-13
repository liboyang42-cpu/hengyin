package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsTopicNode;
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
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CmsTopicNodeInsertMyBatisTest
{
    @Test
    void insertWithUnlockAfterNodeIdKeepsColumnsAndValuesAligned() throws Exception
    {
        Configuration configuration = new Configuration();
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:topic_node_insert;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAlias("CmsTopicNode", CmsTopicNode.class);
        String resource = "mapper/business/CmsTopicNodeMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource))
        {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);

        try (SqlSession session = factory.openSession(true);
             Statement st = session.getConnection().createStatement())
        {
            st.execute("create table cms_topic_node (id bigint auto_increment primary key, "
                    + "topic_id bigint, unlock_after_node_id bigint)");
            CmsTopicNode node = new CmsTopicNode();
            node.setTopicId(101L);
            node.setUnlockAfterNodeId(99L);

            assertEquals(1, session.getMapper(CmsTopicNodeMapper.class).insertCmsTopicNode(node));
            try (ResultSet rs = st.executeQuery(
                    "select topic_id, unlock_after_node_id from cms_topic_node where id = " + node.getId()))
            {
                rs.next();
                assertEquals(101L, rs.getLong(1));
                assertEquals(99L, rs.getLong(2));
            }
        }
    }
}
