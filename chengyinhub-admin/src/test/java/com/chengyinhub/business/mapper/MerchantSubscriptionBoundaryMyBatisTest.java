package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantSubscription;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 执行真实XML的H2测试；不代替MySQL时钟/续订受影响行数验证。 */
class MerchantSubscriptionBoundaryMyBatisTest {
    @Test void activeEntitlementsExcludeFutureStartAndFinishedSubscription() throws Exception {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:subscription_boundary_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table merchant_subscription(id bigint,merchant_id bigint,subscription_type varchar(64),start_date timestamp,end_date timestamp,max_usage int,used_count int,is_active int,remark varchar(128),create_time timestamp,update_time timestamp)");
        jdbc.update("insert into merchant_subscription(id,merchant_id,subscription_type,start_date,end_date,is_active) values(1,7,'city_node',dateadd('DAY',1,now()),null,1)");
        jdbc.update("insert into merchant_subscription(id,merchant_id,subscription_type,start_date,end_date,is_active) values(2,7,'city_node',null,now(),1)");
        jdbc.update("insert into merchant_subscription(id,merchant_id,subscription_type,start_date,end_date,is_active) values(3,7,'city_node',now(),dateadd('DAY',1,now()),1)");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(ds);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/MerchantSubscriptionMapper.xml"));
        MerchantSubscriptionMapper mapper = new SqlSessionTemplate(factory.getObject()).getMapper(MerchantSubscriptionMapper.class);
        MerchantSubscription probe = new MerchantSubscription(); probe.setMerchantId(7L); probe.setIsActive(1);
        java.util.List<MerchantSubscription> current = mapper.selectMerchantSubscriptionList(probe);
        assertEquals(1,current.size()); assertEquals(3L,current.get(0).getId());
        probe.setIsActive(null);
        assertEquals(3,mapper.selectMerchantSubscriptionList(probe).size(),"历史管理读取保留未来/已结束事实");
    }
}
