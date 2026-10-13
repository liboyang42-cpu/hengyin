package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MerchantSubscription;
import com.chengyinhub.business.domain.OfficialBroadcast;
import com.chengyinhub.business.mapper.MerchantSubscriptionMapper;
import com.chengyinhub.business.mapper.OfficialBroadcastLogMapper;
import com.chengyinhub.business.mapper.OfficialBroadcastMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.io.InputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OperationsMapperFactsTest {
    @Test
    void activeSubscriptionConsumersExcludeFutureAndExpiredGrants() throws Exception {
        DriverManagerDataSource ds = dataSource(); JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table merchant_subscription(id bigint primary key, merchant_id bigint, subscription_type varchar(50), start_date timestamp, end_date timestamp, max_usage int, used_count int, is_active int, remark varchar(100), create_time timestamp, update_time timestamp)");
        jdbc.update("insert into merchant_subscription(id,merchant_id,subscription_type,start_date,end_date,is_active) values (1,7,'city_node',null,null,1),(2,7,'city_node',DATEADD('DAY',1,now()),null,1),(3,7,'city_node',null,DATEADD('DAY',-1,now()),1),(4,7,'city_node',null,null,0),(5,8,'city_node',null,null,1),(6,7,'city_node',DATEADD('DAY',-1,now()),DATEADD('DAY',1,now()),1)");
        try (SqlSession session = factory(ds, MerchantSubscription.class, "MerchantSubscriptionMapper").openSession()) {
            MerchantSubscription query = new MerchantSubscription(); query.setMerchantId(7L); query.setIsActive(1);
            List<MerchantSubscription> active = session.getMapper(MerchantSubscriptionMapper.class).selectMerchantSubscriptionList(query);
            assertEquals(2, active.size()); assertEquals(Long.valueOf(6), active.get(0).getId());
            query.setIsActive(null);
            assertEquals(5, session.getMapper(MerchantSubscriptionMapper.class).selectMerchantSubscriptionList(query).size(), "history reads retain future and expired records");
        }
    }

    @Test
    void channelStatsKeepRoleAndChannelSeparateAndDoNotInventFailures() throws Exception {
        DriverManagerDataSource ds = dataSource(); JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table official_broadcast_log(id bigint primary key,broadcast_id bigint,member_id bigint,role varchar(16),channel varchar(16),reached int,clicked int,create_time timestamp)");
        jdbc.update("insert into official_broadcast_log values (1,11,7,'player','inapp',1,1,now()),(2,11,7,'player','subscribe',0,0,now()),(3,11,8,'merchant','inapp',0,0,now()),(4,12,9,'player','inapp',1,0,now())");
        try (SqlSession session = factory(ds, null, "OfficialBroadcastLogMapper").openSession()) {
            List<Map<String, Object>> rows = session.getMapper(OfficialBroadcastLogMapper.class).selectChannelStats(11L);
            assertEquals(3, rows.size());
            Map<String, Object> subscribe = rows.stream().filter(r -> "subscribe".equals(r.get("channel"))).findFirst().get();
            assertEquals(1, ((Number) subscribe.get("unconfirmed")).intValue());
            assertEquals(0, ((Number) subscribe.get("recorded_success")).intValue());
            assertFalse(subscribe.containsKey("failed"));
        }
    }

    @Test
    void requestMapperScopesRoundtripAndUniqueKeyIsAuthoritative() throws Exception {
        DriverManagerDataSource ds = dataSource(); JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table official_broadcast(id bigint auto_increment primary key,request_scope varchar(40),request_id varchar(64),event_id bigint,title varchar(128),audience varchar(32),copy_mode int,content_json text,channels varchar(32),city varchar(32),scheduled_time timestamp,status int,reached_count int,sender_account bigint,create_time timestamp, unique(request_scope,request_id))");
        try (SqlSession session = factory(ds, OfficialBroadcast.class, "OfficialBroadcastMapper").openSession(true)) {
            OfficialBroadcastMapper mapper = session.getMapper(OfficialBroadcastMapper.class);
            OfficialBroadcast bc = new OfficialBroadcast(); bc.setTitle("notice"); bc.setRequestScope("ADMIN:1"); bc.setRequestId("same"); bc.setChannels("inapp"); bc.setAudience("player");
            mapper.insertOfficialBroadcast(bc);
            assertEquals(bc.getId(), mapper.selectByRequest("ADMIN:1", "same").getId());
            assertNull(mapper.selectByRequest("ADMIN:2", "same"));
            assertThrows(Exception.class, () -> mapper.insertOfficialBroadcast(bc));
            bc.setRequestScope("MEMBER:1"); mapper.insertOfficialBroadcast(bc);
            assertNotNull(mapper.selectByRequest("MEMBER:1", "same"));
        }
    }

    private DriverManagerDataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource(); ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"); return ds;
    }
    private SqlSessionFactory factory(DriverManagerDataSource ds, Class<?> alias, String mapper) throws Exception {
        Configuration c = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        if (alias != null) c.getTypeAliasRegistry().registerAlias(alias.getSimpleName(), alias);
        c.getTypeAliasRegistry().registerAlias("OfficialBroadcastLog", com.chengyinhub.business.domain.OfficialBroadcastLog.class);
        String path = "mapper/business/" + mapper + ".xml";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            new XMLMapperBuilder(in, c, path, c.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(c);
    }
}
