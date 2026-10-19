package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantSubscription;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** M0153: run the production mapper XML against an actual SQL engine, not a mocked mapper. */
class Batch1AMerchantSubscriptionMyBatisTest {

    @Test
    void activeReadHonorsStartEndAndStatusWhileManagementReadRemainsUnfiltered() throws Exception {
        try (SqlSession session = factory().openSession(true)) {
            try (Statement statement = session.getConnection().createStatement()) {
                statement.execute("create table merchant_subscription ("
                        + "id bigint primary key, merchant_id bigint, subscription_type varchar(32),"
                        + "start_date datetime null, end_date datetime null, max_usage int, used_count int,"
                        + "is_active int, remark varchar(255), create_time datetime, update_time datetime)");
                statement.execute("insert into merchant_subscription values "
                        + "(1,10,'CAPACITY',dateadd('DAY',-1,current_timestamp),dateadd('DAY',1,current_timestamp),10,0,1,null,current_timestamp,current_timestamp),"
                        + "(2,10,'CAPACITY',dateadd('DAY',1,current_timestamp),dateadd('DAY',2,current_timestamp),20,0,1,null,current_timestamp,current_timestamp),"
                        + "(3,10,'CAPACITY',dateadd('DAY',-2,current_timestamp),dateadd('DAY',-1,current_timestamp),30,0,1,null,current_timestamp,current_timestamp),"
                        + "(4,10,'CAPACITY',dateadd('DAY',-1,current_timestamp),dateadd('DAY',1,current_timestamp),40,0,0,null,current_timestamp,current_timestamp),"
                        + "(5,10,'CAPACITY',null,null,50,0,1,null,current_timestamp,current_timestamp)");
            }
            MerchantSubscriptionMapper mapper = session.getMapper(MerchantSubscriptionMapper.class);
            MerchantSubscription active = query(1);
            assertEquals(ids(5L, 1L), mapper.selectMerchantSubscriptionList(active).stream()
                    .map(MerchantSubscription::getId).collect(Collectors.toList()),
                    "active read includes effective and legacy-null grants only");

            assertEquals(ids(5L, 4L, 3L, 2L, 1L), mapper.selectMerchantSubscriptionList(query(null)).stream()
                    .map(MerchantSubscription::getId).collect(Collectors.toList()),
                    "management read without isActive still sees future, expired and disabled rows");
        }
    }

    private MerchantSubscription query(Integer isActive) {
        MerchantSubscription query = new MerchantSubscription();
        query.setMerchantId(10L);
        query.setSubscriptionType("CAPACITY");
        query.setIsActive(isActive);
        return query;
    }

    private List<Long> ids(Long... ids) {
        return java.util.Arrays.asList(ids);
    }

    private SqlSessionFactory factory() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("MerchantSubscription", MerchantSubscription.class);
        UnpooledDataSource dataSource = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:batch1a_subscription;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        try (InputStream input = Resources.getResourceAsStream("mapper/business/MerchantSubscriptionMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "mapper/business/MerchantSubscriptionMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
