package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.SmsCoupon;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CouponMerchantScopeMyBatisTest {
    @Test
    void merchantScopedPublishIsImmediatelyReadableByCrmWhileLegacyClubCouponIsExcluded() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:coupon_merchant_scope;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop all objects");
        jdbc.execute(
                "create table sms_coupon(id bigint auto_increment primary key,name varchar(100),coupon_type int,platform int,"
                        + "amount decimal(12,2),min_point decimal(12,2),start_time timestamp,end_time timestamp,use_type int,"
                        + "publish_count bigint,use_count bigint,receive_count bigint,enable_time timestamp,code varchar(100),"
                        + "scope_type int,member_id bigint,merchant_id bigint,status int,del_flag int,description varchar(500),"
                        + "create_by varchar(64),create_time timestamp,update_by varchar(64),update_time timestamp)");

        SqlSessionFactory factory = factory(dataSource);
        try (SqlSession session = factory.openSession(true)) {
            SmsCouponMapper mapper = session.getMapper(SmsCouponMapper.class);
            SmsCoupon merchant = coupon("商家员工新建券", 9001L);
            merchant.setScopeType(1);
            merchant.setMerchantId(11L);
            assertEquals(1, mapper.insertSmsCoupon(merchant));

            SmsCoupon legacyClub = coupon("俱乐部历史券", 9001L);
            assertEquals(1, mapper.insertSmsCoupon(legacyClub));

            SmsCoupon otherMerchant = coupon("同 owner 的 B 店券", 9001L);
            otherMerchant.setScopeType(1);
            otherMerchant.setMerchantId(12L);
            assertEquals(1, mapper.insertSmsCoupon(otherMerchant));

            List<SmsCoupon> crmOptions = mapper.selectOwnedActiveCoupons(9001L, 11L, 20);
            assertEquals(1, crmOptions.size());
            assertEquals(merchant.getId(), crmOptions.get(0).getId());
            assertEquals(1, crmOptions.get(0).getScopeType());
            assertEquals(11L, crmOptions.get(0).getMerchantId());
            assertEquals(null, mapper.selectOwnedActiveCoupon(9001L, 11L, otherMerchant.getId()),
                    "A 店员工不能选择同 owner 的 B 店券");
            assertEquals(otherMerchant.getId(),
                    mapper.selectOwnedActiveCoupon(9001L, 12L, otherMerchant.getId()).getId());

            SmsCoupon query = new SmsCoupon();
            query.setMemberId(9001L);
            query.setScopeType(1);
            query.setMerchantId(11L);
            query.setDelFlag(0);
            query.getParams().put("issuableOnly", true);
            assertEquals(merchant.getId(), mapper.selectSmsCouponList(query).get(0).getId());
            for (String unavailable : new String[] {
                    "receive_count=10", "receive_count=11", "status=4", "del_flag=1",
                    "start_time=DATEADD('DAY',1,CURRENT_TIMESTAMP)",
                    "enable_time=DATEADD('DAY',1,CURRENT_TIMESTAMP)",
                    "end_time=DATEADD('DAY',-1,CURRENT_TIMESTAMP)"}) {
                jdbc.update("update sms_coupon set receive_count=0,status=1,del_flag=0,"
                        + "start_time=null,enable_time=null,end_time=null where id=?", merchant.getId());
                jdbc.update("update sms_coupon set " + unavailable + " where id=?", merchant.getId());
                session.clearCache();
                assertEquals(0, mapper.selectSmsCouponList(query).size(), unavailable);
                assertEquals(0, mapper.selectOwnedActiveCoupons(9001L, 11L, 20).size(), unavailable);
            }
            jdbc.update("update sms_coupon set end_time=null,receive_count=10 where id=?", merchant.getId());
            session.clearCache();
            query.getParams().clear();
            assertEquals(1, mapper.selectSmsCouponList(query).size(), "管理列表仍保留耗尽券");
            query.getParams().put("issuableOnly", true);
            jdbc.update("update sms_coupon set publish_count=0 where id=?", merchant.getId());
            session.clearCache();
            assertEquals(1, mapper.selectSmsCouponList(query).size(), "既有无限库存语义不变");
        }
    }

    private static SmsCoupon coupon(String name, Long ownerMemberId) {
        SmsCoupon row = new SmsCoupon();
        row.setName(name);
        row.setCouponType(0);
        row.setMemberId(ownerMemberId);
        row.setStatus(1);
        row.setDelFlag(0);
        row.setPublishCount(10L);
        row.setReceiveCount(0L);
        row.setStartTime(new Date(System.currentTimeMillis() - 60_000L));
        row.setEndTime(new Date(System.currentTimeMillis() + 60_000L));
        return row;
    }

    private static SqlSessionFactory factory(DriverManagerDataSource dataSource) throws Exception {
        Configuration configuration = new Configuration(new Environment(
                "coupon-scope", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        configuration.addMapper(SmsCouponMapper.class);
        String resource = "mapper/business/SmsCouponMapper.xml";
        try (InputStream in = CouponMerchantScopeMyBatisTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing " + resource);
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
