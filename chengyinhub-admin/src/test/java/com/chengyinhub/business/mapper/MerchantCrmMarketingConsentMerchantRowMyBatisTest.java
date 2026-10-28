package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.vo.MerchantCrmConsentMerchantVO;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MerchantCrmMarketingConsentMerchantRowMyBatisTest {
    private static final String XML = "mapper/business/MerchantCrmMarketingConsentMapper.xml";

    @Test
    void sameOwnerMultipleActiveStoresFailClosedWithoutAuthoritativeRegistrationRowScope() throws Exception {
        try (SqlSession session = factory().openSession(false)) {
            createSchema(session);
            seed(session);
            MerchantCrmMarketingConsentMapper mapper = session.getMapper(MerchantCrmMarketingConsentMapper.class);

            assertEquals(0, mapper.selectEligibleMerchants(41L).size(),
                    "owner 级报名不能被猜成 A/B 任一门店的营销同意");
            assertNull(mapper.selectEligibleMerchant(41L, 77L));
            assertNull(mapper.selectEligibleMerchant(41L, 78L));
            session.rollback();
        }
    }

    @Test
    void soleActiveStoreCanUseTheRealOwnerRegistrationRelationship() throws Exception {
        try (SqlSession session = factory().openSession(false)) {
            createSchema(session);
            seed(session);
            try (Statement statement = session.getConnection().createStatement()) {
                statement.execute("update mms_merchant set status=0 where id=78");
            }
            MerchantCrmMarketingConsentMapper mapper = session.getMapper(MerchantCrmMarketingConsentMapper.class);

            List<MerchantCrmConsentMerchantVO> rows = mapper.selectEligibleMerchants(41L);
            assertEquals(1, rows.size());
            assertEquals(Long.valueOf(77L), rows.get(0).getMerchantRowId());
            assertEquals(Long.valueOf(900L), rows.get(0).getMerchantOwnerMemberId());
            assertEquals("/profile/upload/a-logo.png", rows.get(0).getMerchantLogo(), "营销消息页分组头读商家行上的 Logo");
            assertEquals(Boolean.FALSE, rows.get(0).getInAppOptedIn());
            assertEquals(Boolean.FALSE, rows.get(0).getCouponOptedIn());
            assertNull(mapper.selectEligibleMerchant(41L, 78L));
            session.rollback();
        }
    }

    private SqlSessionFactory factory() throws Exception {
        Configuration configuration = new Configuration();
        UnpooledDataSource dataSource = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:merchant_crm_consent_row;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        try (InputStream input = Resources.getResourceAsStream(XML)) {
            new XMLMapperBuilder(input, configuration, XML, configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private void createSchema(SqlSession session) throws Exception {
        try (Statement statement = session.getConnection().createStatement()) {
            statement.execute("drop all objects");
            statement.execute("create table mms_merchant(id bigint primary key, member_id bigint not null, "
                    + "name varchar(100), status int not null, account_status int not null, del_flag int not null, logo varchar(500))");
            statement.execute("create table cms_registration(id bigint primary key, member_id bigint not null, "
                    + "payment_status int not null, owner_type int not null, owner_id bigint not null)");
            statement.execute("create table cms_topic(id bigint primary key, member_id bigint not null, "
                    + "product_type int, del_flag int not null)");
            statement.execute("create table cms_registration_merchant(id bigint primary key, topic_id bigint not null, "
                    + "member_id bigint not null, audit_status int not null, del_flag int not null)");
            statement.execute("create table cms_activity(id bigint primary key, topic_id bigint, member_id bigint not null, "
                    + "del_flag int not null)");
            statement.execute("create table merchant_crm_marketing_consent(id bigint auto_increment primary key, "
                    + "member_id bigint not null, merchant_id bigint not null, merchant_row_id bigint not null, "
                    + "channel varchar(16) not null, event_type varchar(16) not null, request_id varchar(64) not null, "
                    + "create_time timestamp not null)");
        }
    }

    private void seed(SqlSession session) throws Exception {
        try (Statement statement = session.getConnection().createStatement()) {
            statement.execute("insert into mms_merchant values "
                    + "(77,900,'A店',1,1,0,'/profile/upload/a-logo.png'),(78,900,'B店',1,1,0,null)");
            statement.execute("insert into cms_registration values (301,41,2,2,31)");
            statement.execute("insert into cms_activity values (31,null,900,0)");
        }
    }
}
