package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.ViewRegistration;
import com.chengyinhub.business.mapper.ViewRegistrationMapper;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.service.impl.CmsActivityServiceImpl;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ViewRegistrationSummaryMyBatisTest {
    @Test
    void actualActivityCountsKeepPaymentAndSignupRulesWithoutDecryptingPii() throws Exception {
        Configuration config = new Configuration();
        config.getTypeAliasRegistry().registerAlias("CmsRegistration", CmsRegistration.class);
        config.setEnvironment(new Environment("test", new JdbcTransactionFactory(),
                new PooledDataSource("org.h2.Driver", "jdbc:h2:mem:registration_counts;MODE=MySQL", "sa", "")));
        String resource = "mapper/business/CmsRegistrationMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        CmsRegistration probe = new CmsRegistration();
        probe.setOwnerType(2L);
        probe.setOwnerId(33L);
        probe.setPaymentStatus(2);
        String prefix = CmsRegistrationMapper.class.getName() + ".";
        String full = config.getMappedStatement(prefix + "selectCmsRegistrationList").getBoundSql(probe).getSql();
        String count = config.getMappedStatement(prefix + "countCmsRegistrationList").getBoundSql(probe).getSql();
        assertEquals(full.substring(full.indexOf("WHERE"), full.indexOf("order by")).trim(),
                count.substring(count.indexOf("WHERE")).trim());
        assertFalse(count.contains("real_name") || count.contains("phone") || count.contains("verification_code"));
        try (SqlSession session = new SqlSessionFactoryBuilder().build(config).openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            statement.execute("create table cms_registration (owner_type bigint, owner_id bigint, "
                    + "payment_status int, registration_status int, real_name varchar(80))");
            statement.execute("insert into cms_registration values "
                    + "(2,33,2,2,'v1:unreadable'),(2,33,0,1,'v1:unreadable'),(2,33,1,1,'v1:unreadable'),"
                    + "(2,33,3,4,null),(2,33,4,3,null),(1,33,2,2,null),(2,34,2,2,null)");
            CmsActivityServiceImpl service = new CmsActivityServiceImpl();
            ReflectionTestUtils.setField(service, "cmsRegistrationMapper", session.getMapper(CmsRegistrationMapper.class));
            assertEquals(1, service.countPaidRegistrations(33L));
            assertEquals(1, service.countPendingRegistrations(33L));
            assertEquals(3, service.countOpenSaleRegistrations(33L));
            assertEquals(1, service.countSignedUpRegistrations(33L));
            assertEquals(0, service.countOpenSaleRegistrations(35L));
        }
    }

    @Test
    void unreadablePiiDoesNotBlockSummariesAndExistingScopeFiltersRemain() throws Exception {
        Configuration config = new Configuration();
        config.getTypeAliasRegistry().registerAlias("ViewRegistration", ViewRegistration.class);
        config.setEnvironment(new Environment("test", new JdbcTransactionFactory(),
                new PooledDataSource("org.h2.Driver", "jdbc:h2:mem:registration_summary;MODE=MySQL", "sa", "")));
        String resource = "mapper/business/ViewRegistrationMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        ViewRegistration probe = new ViewRegistration();
        probe.setOwnerType(2L);
        probe.setOwnerId(33L);
        probe.setTicketId(83L);
        probe.setMemberId(9004L);
        probe.setRegistrationStatus(2);
        String prefix = ViewRegistrationMapper.class.getName() + ".";
        String fullSql = config.getMappedStatement(prefix + "selectViewRegistrationList").getBoundSql(probe).getSql();
        String summarySql = config.getMappedStatement(prefix + "selectRegistrationSummaries").getBoundSql(probe).getSql();
        assertEquals(fullSql.substring(fullSql.indexOf("WHERE")), summarySql.substring(summarySql.indexOf("WHERE")));
        assertFalse(summarySql.contains("real_name") || summarySql.contains("phone")
                || summarySql.contains("email") || summarySql.contains("address")
                || summarySql.contains("verification_code") || summarySql.contains("qrcode_url"));
        try (SqlSession session = new SqlSessionFactoryBuilder().build(config).openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            statement.execute("create table view_registration (id bigint, member_id bigint, avatar varchar(80), "
                    + "nickname varchar(80), payment_status int, verification_status int, owner_type bigint, "
                    + "owner_id bigint, ticket_id bigint, registration_status int, real_name varchar(80), phone varchar(80))");
            statement.execute("insert into view_registration values "
                    + "(1,9004,'avatar','玩家',2,1,2,33,83,2,'v1:unreadable','v1:unreadable'),"
                    + "(2,9004,'avatar','主题',2,0,1,33,83,2,null,null),"
                    + "(3,9004,'avatar','其他活动',2,0,2,34,83,2,null,null),"
                    + "(4,9004,'avatar','其他票',2,0,2,33,84,2,null,null),"
                    + "(5,9008,'avatar','其他玩家',2,0,2,33,83,2,null,null),"
                    + "(6,9004,'avatar','未支付',1,0,2,33,83,1,null,null)");
            List<ViewRegistration> rows = session.getMapper(ViewRegistrationMapper.class).selectRegistrationSummaries(probe);
            assertEquals(1, rows.size());
            ViewRegistration row = rows.get(0);
            assertEquals(1L, row.getId());
            assertEquals("玩家", row.getNickname());
            assertEquals(2, row.getPaymentStatus());
            assertEquals(1, row.getVerificationStatus());
            assertNull(row.getRealName());
            assertNull(row.getPhone());
            assertNull(row.getVerificationCode());
            probe.setMemberId(9009L);
            assertTrue(session.getMapper(ViewRegistrationMapper.class).selectRegistrationSummaries(probe).isEmpty());
        }
    }
}
