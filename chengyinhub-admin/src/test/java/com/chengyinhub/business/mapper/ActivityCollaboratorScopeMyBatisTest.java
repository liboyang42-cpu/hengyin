package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsCollaborators;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.sql.Statement;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ActivityCollaboratorScopeMyBatisTest {
    @Test
    void removalIsBoundToActivityTypeAndOwner() throws Exception {
        Configuration config = new Configuration();
        config.getTypeAliasRegistry().registerAlias("CmsCollaborators", CmsCollaborators.class);
        config.setEnvironment(new Environment("test", new JdbcTransactionFactory(), new UnpooledDataSource(
                "org.h2.Driver", "jdbc:h2:mem:activity_collaborator_scope;MODE=MySQL", "sa", "")));
        String xml = "mapper/business/CmsCollaboratorsMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(xml)) {
            new XMLMapperBuilder(input, config, xml, config.getSqlFragments()).parse();
        }
        try (SqlSession session = new SqlSessionFactoryBuilder().build(config).openSession()) {
            try (Statement sql = session.getConnection().createStatement()) {
                sql.execute("create table cms_activity(id bigint primary key, member_id bigint)");
                sql.execute("create table cms_collaborators(id bigint primary key, data_type bigint, data_id bigint, member_id bigint)");
                sql.execute("insert into cms_activity values(9,88),(10,99)");
                sql.execute("insert into cms_collaborators values(1,2,9,21),(2,2,9,22),(3,1,9,21),(4,2,10,22)");
            }
            CmsCollaboratorsMapper mapper = session.getMapper(CmsCollaboratorsMapper.class);
            assertEquals(0, mapper.deleteActivityCollaboratorsByOwner(9L, 99L));
            assertEquals(2, mapper.deleteActivityCollaboratorsByOwner(9L, 88L));
            try (Statement sql = session.getConnection().createStatement();
                 java.sql.ResultSet rows = sql.executeQuery("select count(*) from cms_collaborators")) {
                rows.next();
                assertEquals(2, rows.getInt(1));
            }
        }
    }
}
