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
import java.math.BigDecimal;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 旧现金分润基数与活动壳证据必须保持两个互不混用的 SQL 口径。 */
class TandianriLegacyCashBasisMyBatisTest {

    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("CmsRegistration", CmsRegistration.class);
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:tandianri_cash_basis_" + System.nanoTime()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        try (InputStream in = Resources.getResourceAsStream("mapper/business/CmsRegistrationMapper.xml")) {
            new XMLMapperBuilder(in, configuration, "CmsRegistrationMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true);
                Statement statement = session.getConnection().createStatement()) {
            statement.execute("create table cms_registration (id bigint primary key, member_id bigint, "
                    + "owner_id bigint, owner_type int, payment_status int, registration_status int, "
                    + "verification_status int, payable_amount decimal(10,2))");
            statement.execute("create table cms_activity (id bigint primary key, topic_id bigint, del_flag int)");
            statement.execute("create table cms_registration_scan (registration_id bigint, member_id bigint, "
                    + "registration_type int)");
            statement.execute("insert into cms_activity values (201, 23, 0), (202, 23, 1)");
            statement.execute("insert into cms_registration values "
                    + "(1, 11, 23, 1, 2, 2, 1, 100.00), "
                    + "(2, 12, 201, 2, 2, 2, 1, 88.00), "
                    + "(3, 13, 202, 2, 2, 2, 1, 999.00)");
        }
    }

    @Test
    void h8ExplicitDeviation_classicCashBasisStaysDirectWhileEvidenceIncludesOnlyActiveActivityShell() {
        try (SqlSession session = factory.openSession(true)) {
            CmsRegistrationMapper mapper = session.getMapper(CmsRegistrationMapper.class);

            assertEquals(new BigDecimal("100.00"), mapper.selectTotalSalesByTopicId(23L),
                    "经典 product_type=1 现金分润仍只消费主题直挂票，禁止活动壳造成双派");
            assertEquals(new BigDecimal("188.00"),
                    mapper.selectTotalSalesEvidenceByTopicIdIncludingActivityShell(23L),
                    "只读证据可覆盖未删除活动壳，但不得流回旧现金链");
        }
    }
}
