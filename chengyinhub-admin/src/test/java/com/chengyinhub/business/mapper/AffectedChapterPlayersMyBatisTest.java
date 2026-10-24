package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.RegistrationEntitlement;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.sql.Statement;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Actual XML recipient selection: valid paid unredeemed facts only, never the whole route audience. */
class AffectedChapterPlayersMyBatisTest {
    @Test
    void onlyAffectedPaidUnredeemedPlayersAreSelectedAndDeduplicated() throws Exception {
        UnpooledDataSource source = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:affected_chapter_" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        Configuration config = new Configuration();
        config.setEnvironment(new Environment("fixture", new JdbcTransactionFactory(), source));
        config.getTypeAliasRegistry().registerAlias("RegistrationEntitlement", RegistrationEntitlement.class);
        String resource = "mapper/business/RegistrationEntitlementMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, config, resource, config.getSqlFragments()).parse();
        }
        try (SqlSession session = new SqlSessionFactoryBuilder().build(config).openSession(true);
             Statement sql = session.getConnection().createStatement()) {
            sql.execute("create table cms_registration(id bigint primary key,member_id bigint,owner_type int,owner_id bigint,topic_id bigint,payment_status int,registration_status int)");
            sql.execute("create table cms_activity(id bigint primary key,topic_id bigint)");
            sql.execute("create table registration_entitlement(registration_id bigint,topic_id bigint,chapter_id bigint,status int)");
            sql.execute("insert into cms_activity values(71,10),(72,11)");
            sql.execute("insert into cms_registration values"
                    + "(1,601,1,10,10,2,2),(2,601,1,10,10,2,2),(3,602,2,71,null,2,2),"
                    + "(4,603,1,10,10,1,2),(5,604,1,10,10,2,3),(6,605,1,11,10,2,2),"
                    + "(7,606,2,72,10,2,2),(8,607,1,10,10,2,2),(9,608,1,10,10,2,2)");
            sql.execute("insert into registration_entitlement values"
                    + "(1,10,33,0),(2,10,33,0),(3,10,33,0),(4,10,33,0),(5,10,33,0),"
                    + "(6,10,33,0),(7,10,33,0),(8,10,33,1),(9,10,34,0)");
            RegistrationEntitlementMapper mapper = session.getMapper(RegistrationEntitlementMapper.class);
            assertEquals(List.of(601L,602L), mapper.selectAffectedPaidMembers(10L,33L));
            assertEquals(List.of(), mapper.selectAffectedPaidMembers(11L,33L));
        }
    }
}
