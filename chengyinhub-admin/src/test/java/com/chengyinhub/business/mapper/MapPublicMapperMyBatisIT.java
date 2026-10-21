package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.map.MapPublicItemFacts;
import com.chengyinhub.business.domain.map.MapPublicRow;
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
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地 fixture 上的真实 MapPublic SQL。只连 127.0.0.1 的 chengyin_mybatis_* 库。
 * 已审/未审/关闭/删除、同号不同来源、账号投影、bbox、重复页和最后一页都在这里。
 */
class MapPublicMapperMyBatisIT {

    private static final BigDecimal A_MIN_LAT = new BigDecimal("31.22");
    private static final BigDecimal A_MAX_LAT = new BigDecimal("31.24");
    private static final BigDecimal A_MIN_LNG = new BigDecimal("121.46");
    private static final BigDecimal A_MAX_LNG = new BigDecimal("121.49");
    private static final BigDecimal B_MIN_LAT = new BigDecimal("32.00");
    private static final BigDecimal B_MAX_LAT = new BigDecimal("32.10");
    private static final BigDecimal B_MIN_LNG = new BigDecimal("121.00");
    private static final BigDecimal B_MAX_LNG = new BigDecimal("121.10");

    @Test
    void publicSqlHidesUnapprovedClosedAndDeletedRows() throws Exception {
        String jdbcUrl = com.chengyinhub.business.it.MybatisItDatabase.url("map_public");
        requireDedicatedLocalFixture(jdbcUrl);
        SqlSessionFactory factory = factory(jdbcUrl);
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            try {
                for (String ddl : DDL) statement.execute(ddl);
                for (String dml : DML) statement.execute(dml);
                MapPublicMapper mapper = session.getMapper(MapPublicMapper.class);

                long inserts = status(statement, "Com_insert");
                long updates = status(statement, "Com_update");
                long deletes = status(statement, "Com_delete");
                List<Long> roam = ids(mapper.selectRoamPois(77L, A_MIN_LAT, A_MAX_LAT, A_MIN_LNG, A_MAX_LNG, null, 50));
                List<Long> roamAgain = ids(mapper.selectRoamPois(77L, A_MIN_LAT, A_MAX_LAT, A_MIN_LNG, A_MAX_LNG, null, 50));
                assertEquals(List.of(1L, 4L, 5L, 6L, 7L, 12L), roam);
                assertEquals(roam, roamAgain, "同一页重复读取结果相同");
                assertEquals(List.of(5L, 6L, 7L, 12L),
                        ids(mapper.selectRoamPois(77L, A_MIN_LAT, A_MAX_LAT, A_MIN_LNG, A_MAX_LNG, 4L, 50)));
                assertEquals(List.of(12L),
                        ids(mapper.selectRoamPois(77L, A_MIN_LAT, A_MAX_LAT, A_MIN_LNG, A_MAX_LNG, 7L, 50)));
                assertTrue(mapper.selectRoamPois(77L, A_MIN_LAT, A_MAX_LAT, A_MIN_LNG, A_MAX_LNG, 12L, 50).isEmpty());
                assertEquals(inserts, status(statement, "Com_insert"));
                assertEquals(updates, status(statement, "Com_update"));
                assertEquals(deletes, status(statement, "Com_delete"));

                List<MapPublicRow> player = mapper.selectRoamPois(77L, A_MIN_LAT, A_MAX_LAT, A_MIN_LNG, A_MAX_LNG, null, 50);
                assertEquals(Boolean.TRUE, flag(player, 4L).getFound());
                assertEquals(Boolean.FALSE, flag(player, 4L).getPendingRedeem());
                assertEquals(Boolean.TRUE, flag(player, 5L).getFound());
                assertEquals(Boolean.TRUE, flag(player, 5L).getPendingRedeem());
                assertEquals(Boolean.FALSE, flag(player, 6L).getFound());
                assertEquals("face.png", flag(player, 1L).getNpcAvatar());
                assertNull(flag(player, 4L).getNpcAvatar());
                assertEquals("4100000001", flag(player, 1L).getVisibilityVersion());
                assertEquals("4100000004", flag(player, 4L).getVisibilityVersion());
                assertEquals("4100000005", flag(player, 5L).getVisibilityVersion());
                assertNull(flag(player, 12L).getVisibilityVersion());
                assertEquals("unfound", MapPublicItemFacts.visualState(flag(player, 1L)));
                assertEquals("found", MapPublicItemFacts.visualState(flag(player, 4L)));
                assertEquals("pending_redeem", MapPublicItemFacts.visualState(flag(player, 5L)));
                assertTrue(MapPublicItemFacts.actions(flag(player, 1L)).isEmpty());
                List<MapPublicRow> stranger = mapper.selectRoamPois(88L, A_MIN_LAT, A_MAX_LAT, A_MIN_LNG, A_MAX_LNG, null, 50);
                assertEquals(Boolean.FALSE, flag(stranger, 4L).getFound());
                assertEquals(Boolean.TRUE, flag(stranger, 6L).getFound());
                assertEquals(Boolean.FALSE, flag(stranger, 6L).getPendingRedeem());
                assertEquals("found", MapPublicItemFacts.visualState(flag(stranger, 6L)));
                assertEquals("unfound", MapPublicItemFacts.visualState(flag(player, 6L)));
                assertFalse(ids(mapper.selectRoamPois(77L, A_MIN_LAT, new BigDecimal("31.229"), A_MIN_LNG, A_MAX_LNG, null, 50))
                        .contains(1L), "收紧 bbox 后框外的已公开点不再出现");

                statement.execute("insert into roam_poi(id,name,type,lat,lng,status,application_type)"
                        + " values (15,'临时点',1,31.2300000,121.4730000,1,null)");
                session.clearCache();
                List<MapPublicRow> withTemporary = mapper.selectRoamPois(77L, A_MIN_LAT, A_MAX_LAT, A_MIN_LNG, A_MAX_LNG, null, 50);
                assertTrue(ids(withTemporary).contains(15L));
                assertNull(flag(withTemporary, 15L).getVisibilityVersion());
                long foundRows = count(statement, "roam_poi_found");
                statement.execute("delete from roam_poi where id = 15");
                session.clearCache();
                assertFalse(ids(mapper.selectRoamPois(77L, A_MIN_LAT, A_MAX_LAT, A_MIN_LNG, A_MAX_LNG, null, 50)).contains(15L));
                assertEquals(foundRows, count(statement, "roam_poi_found"));

                assertEquals(List.of(30L, 31L),
                        ids(mapper.selectRoamPois(77L, B_MIN_LAT, B_MAX_LAT, B_MIN_LNG, B_MAX_LNG, null, 2)));
                assertEquals(List.of(32L),
                        ids(mapper.selectRoamPois(77L, B_MIN_LAT, B_MAX_LAT, B_MIN_LNG, B_MAX_LNG, 31L, 2)));
                assertTrue(mapper.selectRoamPois(77L, B_MIN_LAT, B_MAX_LAT, B_MIN_LNG, B_MAX_LNG, 32L, 2).isEmpty());

                List<MapPublicRow> stores = mapper.selectStores(A_MIN_LAT, A_MAX_LAT, A_MIN_LNG, A_MAX_LNG, null, 50);
                assertEquals(List.of(4L, 56L, 57L), ids(stores));
                assertEquals(0, flag(stores, 4L).getBusinessStatus());
                assertEquals("2026-03-01T08:00:00", flag(stores, 4L).getVisibilityVersion());
                assertEquals("closed", MapPublicItemFacts.visualState(flag(stores, 4L)));
                assertTrue(MapPublicItemFacts.actions(flag(stores, 4L)).isEmpty());
                assertEquals("2026-03-02T09:00:00", flag(stores, 56L).getVisibilityVersion());
                assertEquals("open", MapPublicItemFacts.visualState(flag(stores, 56L)));
                assertEquals(List.of(MapPublicItemFacts.NAVIGATE), MapPublicItemFacts.actions(flag(stores, 56L)));
                assertNull(flag(stores, 57L).getVisibilityVersion());
                assertTrue(MapPublicItemFacts.actions(flag(stores, 57L)).isEmpty());
                assertEquals(List.of(40L, 41L), ids(mapper.selectStores(B_MIN_LAT, B_MAX_LAT, B_MIN_LNG, B_MAX_LNG, null, 2)));
                assertEquals(List.of(42L), ids(mapper.selectStores(B_MIN_LAT, B_MAX_LAT, B_MIN_LNG, B_MAX_LNG, 41L, 2)));
                assertTrue(mapper.selectStores(B_MIN_LAT, B_MAX_LAT, B_MIN_LNG, B_MAX_LNG, 42L, 2).isEmpty());

                List<MapPublicRow> activities = mapper.selectActivities(A_MIN_LAT, A_MAX_LAT, A_MIN_LNG, A_MAX_LNG, null, 50);
                assertEquals(List.of(4L, 16L, 17L, 18L), ids(activities));
                assertEquals("2026-04-01T10:00:00", flag(activities, 4L).getVisibilityVersion());
                assertEquals("notStarted", MapPublicItemFacts.visualState(flag(activities, 4L)));
                assertEquals("running", MapPublicItemFacts.visualState(flag(activities, 16L)));
                assertNull(flag(activities, 18L).getVisibilityVersion());
                assertTrue(MapPublicItemFacts.actions(flag(activities, 4L)).isEmpty());
                assertEquals(ids(mapper.selectActivities(A_MIN_LAT, A_MAX_LAT, A_MIN_LNG, A_MAX_LNG, null, 50)),
                        ids(mapper.selectActivities(A_MIN_LAT, A_MAX_LAT, A_MIN_LNG, A_MAX_LNG, null, 50)));
                assertEquals(List.of(20L, 21L),
                        ids(mapper.selectActivities(B_MIN_LAT, B_MAX_LAT, B_MIN_LNG, B_MAX_LNG, null, 2)));
                assertEquals(List.of(22L),
                        ids(mapper.selectActivities(B_MIN_LAT, B_MAX_LAT, B_MIN_LNG, B_MAX_LNG, 21L, 2)));
                assertTrue(mapper.selectActivities(B_MIN_LAT, B_MAX_LAT, B_MIN_LNG, B_MAX_LNG, 22L, 2).isEmpty());

                assertEquals("roam_poi", flag(player, 4L).getSourceType());
                assertEquals("store", flag(mapper.selectStores(A_MIN_LAT, A_MAX_LAT, A_MIN_LNG, A_MAX_LNG, null, 50), 4L)
                        .getSourceType());
                assertEquals("activity", flag(mapper.selectActivities(A_MIN_LAT, A_MAX_LAT, A_MIN_LNG, A_MAX_LNG, null, 50), 4L)
                        .getSourceType());
            } finally {
                for (String drop : DROPS) statement.execute(drop);
            }
        }
    }

    private static MapPublicRow flag(List<MapPublicRow> rows, long id) {
        for (MapPublicRow row : rows) {
            if (row.getSourceId() == id) return row;
        }
        throw new AssertionError("missing " + id);
    }

    private static List<Long> ids(List<MapPublicRow> rows) {
        List<Long> ids = new ArrayList<>();
        for (MapPublicRow row : rows) ids.add(row.getSourceId());
        return ids;
    }

    private static long count(Statement statement, String table) throws Exception {
        try (ResultSet rs = statement.executeQuery("select count(*) from " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long status(Statement statement, String name) throws Exception {
        try (ResultSet rs = statement.executeQuery("show session status like '" + name + "'")) {
            rs.next();
            return rs.getLong(2);
        }
    }

    private static SqlSessionFactory factory(String jdbcUrl) throws Exception {
        Configuration configuration = new Configuration();
        PooledDataSource dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver", jdbcUrl,
                System.getProperty("chengyin.mybatis.it.user", com.chengyinhub.business.it.MybatisItDatabase.user()),
                System.getProperty("chengyin.mybatis.it.password", com.chengyinhub.business.it.MybatisItDatabase.password()));
        configuration.setEnvironment(new Environment("map-public-it", new JdbcTransactionFactory(), dataSource));
        try (InputStream input = Resources.getResourceAsStream("mapper/business/MapPublicMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "mapper/business/MapPublicMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    static void requireDedicatedLocalFixture(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.matches(
                "^jdbc:mysql://127\\.0\\.0\\.1:\\d{1,5}/chengyin_mybatis_[a-z0-9_]+(?:\\?.*)?$")) {
            throw new IllegalStateException("拒绝非专用本地 fixture JDBC:仅允许 127.0.0.1:<port>/chengyin_mybatis_*");
        }
    }

    private static final String[] DROPS = {
            "drop table if exists roam_poi_reward_log",
            "drop table if exists roam_poi_found",
            "drop table if exists npc_profile",
            "drop table if exists roam_poi",
            "drop table if exists cms_activity",
            "drop table if exists cms_topic",
            "drop table if exists club",
            "drop table if exists mms_merchant"
    };

    private static final String[] DDL = {
            "drop table if exists roam_poi_reward_log",
            "drop table if exists roam_poi_found",
            "drop table if exists npc_profile",
            "drop table if exists roam_poi",
            "drop table if exists cms_activity",
            "drop table if exists cms_topic",
            "drop table if exists club",
            "drop table if exists mms_merchant",
            "create table roam_poi (id bigint primary key, name varchar(80), type int, lat decimal(12,7),"
                    + " lng decimal(12,7), address varchar(100), cover_img varchar(100), status int,"
                    + " audit_status int, application_type int, merchant_id bigint, visibility_version bigint)",
            "create table roam_poi_found (id bigint primary key auto_increment, member_id bigint, poi_id bigint)",
            "create table roam_poi_reward_log (id bigint primary key auto_increment, poi_id bigint, member_id bigint,"
                    + " coupon_id bigint, status int)",
            "create table npc_profile (id bigint primary key, scope_type int, scope_id bigint, avatar varchar(100),"
                    + " enabled int, audit_status int, del_flag varchar(2), sort int)",
            "create table mms_merchant (id bigint primary key, name varchar(80), logo varchar(100), address varchar(100),"
                    + " location_lat decimal(12,7), location_lng decimal(12,7), status int, account_status int,"
                    + " del_flag int, business_status int, location_verified int, update_time datetime)",
            "create table club (id bigint primary key, del_flag int)",
            "create table cms_topic (id bigint primary key, del_flag int, status int, user_status int, club_id bigint)",
            "create table cms_activity (id bigint primary key, name varchar(80), img_url varchar(100),"
                    + " address_name varchar(100), longitude varchar(40), latitude varchar(40), status int,"
                    + " publish_status int, del_flag int, cancel_time datetime, start_date datetime, end_date datetime,"
                    + " topic_id bigint, club_id bigint, update_time datetime)"
    };

    private static final String[] DML = {
            "insert into npc_profile(id,scope_type,scope_id,avatar,enabled,audit_status,del_flag,sort)"
                    + " values (1,2,99,'face.png',1,1,'0',1)",
            // 已公开地标。商家 99 已删除且未启用，roam 片段不看商家闸，点仍在。
            "insert into roam_poi(id,name,type,lat,lng,status,application_type,merchant_id)"
                    + " values (1,'地标',1,31.2300000,121.4730000,1,null,99)",
            "insert into roam_poi(id,name,type,lat,lng,status,application_type,audit_status)"
                    + " values (2,'未审',2,31.2300000,121.4730000,1,1,0)",
            "insert into roam_poi(id,name,type,lat,lng,status,application_type,audit_status)"
                    + " values (3,'驳回',2,31.2300000,121.4730000,1,1,2)",
            "insert into roam_poi(id,name,type,lat,lng,status,application_type,audit_status)"
                    + " values (4,'已审',2,31.2300000,121.4730000,1,1,1)",
            "insert into roam_poi(id,name,type,lat,lng,status,application_type,audit_status)"
                    + " values (5,'待核销',2,31.2300000,121.4730000,1,1,1)",
            "insert into roam_poi(id,name,type,lat,lng,status,application_type,audit_status)"
                    + " values (6,'别人到过',2,31.2300000,121.4730000,1,1,1)",
            "insert into roam_poi(id,name,type,lat,lng,status,application_type,audit_status)"
                    + " values (7,'认领',2,31.2300000,121.4730000,1,2,0)",
            "insert into roam_poi(id,name,type,lat,lng,status,application_type,audit_status)"
                    + " values (8,'关闭',2,31.2300000,121.4730000,0,1,1)",
            "insert into roam_poi(id,name,type,lat,lng,status,application_type)"
                    + " values (9,'框外',1,31.2500000,121.4730000,1,null)",
            "insert into roam_poi(id,name,type,lat,lng,status,application_type) values"
                    + " (30,'页1',1,32.0100000,121.0500000,1,null),"
                    + " (31,'页2',1,32.0200000,121.0500000,1,null),"
                    + " (32,'页3',1,32.0300000,121.0500000,1,null)",
            "insert into roam_poi_found(member_id,poi_id) values (77,4)",
            "insert into roam_poi_reward_log(poi_id,member_id,coupon_id,status) values (5,77,9,0)",
            "insert into roam_poi_reward_log(poi_id,member_id,coupon_id,status) values (6,88,1,1)",
            "insert into mms_merchant(id,name,logo,address,location_lat,location_lng,status,account_status,del_flag,business_status)"
                    + " values (4,'打烊仍公开','logo','址',31.2300000,121.4730000,1,1,0,0)",
            "insert into mms_merchant(id,name,location_lat,location_lng,status,account_status,del_flag,business_status)"
                    + " values (50,'已删',31.2300000,121.4730000,1,1,2,1),"
                    + " (51,'未审店',31.2300000,121.4730000,0,1,0,1),"
                    + " (52,'审核失败',31.2300000,121.4730000,2,1,0,1),"
                    + " (53,'账号停用',31.2300000,121.4730000,1,0,0,1),"
                    + " (54,'账号关闭',31.2300000,121.4730000,1,2,0,1),"
                    + " (55,'无坐标',null,null,1,1,0,1),"
                    + " (99,'已删商家',31.2300000,121.4730000,0,0,2,0)",
            "insert into mms_merchant(id,name,location_lat,location_lng,status,account_status,del_flag,business_status)"
                    + " values (40,'店页1',32.0100000,121.0500000,1,1,0,1),"
                    + " (41,'店页2',32.0200000,121.0500000,1,1,0,1),"
                    + " (42,'店页3',32.0300000,121.0500000,1,1,0,1)",
            "insert into club(id,del_flag) values (9,0),(70,1)",
            "insert into cms_topic(id,del_flag,status,user_status,club_id) values (61,0,0,1,null),(62,0,1,1,null),(60,0,1,1,70)",
            "insert into cms_activity(id,name,img_url,address_name,longitude,latitude,status,publish_status,del_flag,"
                    + "cancel_time,end_date,topic_id,club_id) values"
                    + " (2,'未审','p','a','121.4730000','31.2300000',0,1,0,null,'2027-06-01',null,null),"
                    + " (3,'已删','p','a','121.4730000','31.2300000',1,1,2,null,'2027-06-01',null,null),"
                    + " (4,'已公开','p','a','121.4730000','31.2300000',1,1,0,null,'2027-06-01',null,null),"
                    + " (5,'已取消','p','a','121.4730000','31.2300000',1,1,0,'2026-01-01','2027-06-01',null,null),"
                    + " (6,'已结束','p','a','121.4730000','31.2300000',1,1,0,null,'2020-01-01',null,null),"
                    + " (7,'未发布','p','a','121.4730000','31.2300000',1,0,0,null,'2027-06-01',null,null),"
                    + " (8,'社群','p','a','121.4730000','31.2300000',1,1,0,null,'2027-06-01',null,9),"
                    + " (9,'父主题未公开','p','a','121.4730000','31.2300000',1,1,0,null,'2027-06-01',61,null),"
                    + " (10,'框外','p','a','121.4730000','31.2500000',1,1,0,null,'2027-06-01',null,null),"
                    + " (11,'坏坐标','p','a','pending','pending',1,1,0,null,'2027-06-01',null,null),"
                    + " (14,'死俱乐部父主题','p','a','121.4730000','31.2300000',1,1,0,null,'2027-06-01',60,null),"
                    + " (16,'父主题在架','p','a','121.4730000','31.2300000',1,1,0,null,'2027-06-01',62,null),"
                    + " (17,'无父主题','p','a','121.4730000','31.2300000',1,1,0,null,'2027-06-01',0,null),"
                    + " (18,'无版本','p','a','121.4730000','31.2300000',1,1,0,null,'2027-06-01',null,null),"
                    + " (20,'活页1','p','a','121.0500000','32.0100000',1,1,0,null,'2027-06-01',null,null),"
                    + " (21,'活页2','p','a','121.0500000','32.0200000',1,1,0,null,'2027-06-01',null,null),"
                    + " (22,'活页3','p','a','121.0500000','32.0300000',1,1,0,null,'2027-06-01',null,null)",
            "insert into roam_poi(id,name,type,lat,lng,status,application_type,visibility_version)"
                    + " values (12,'无版本',1,31.2300000,121.4730000,1,null,null)",
            "update roam_poi set visibility_version = 4100000001 where id = 1",
            "update roam_poi set visibility_version = 4100000004 where id = 4",
            "update roam_poi set visibility_version = 4100000005 where id = 5",
            "update roam_poi set visibility_version = 4100000006 where id = 6",
            "update roam_poi set visibility_version = 4100000007 where id = 7",
            "update roam_poi set visibility_version = 4100000030 where id = 30",
            "update roam_poi set visibility_version = 4100000031 where id = 31",
            "update roam_poi set visibility_version = 4100000032 where id = 32",
            "update mms_merchant set update_time = '2026-03-01 08:00:00', location_verified = 0 where id = 4",
            "insert into mms_merchant(id,name,location_lat,location_lng,status,account_status,del_flag,business_status,"
                    + "location_verified,update_time) values"
                    + " (56,'已选点',31.2300000,121.4730000,1,1,0,1,1,'2026-03-02 09:00:00'),"
                    + " (57,'无版本店',31.2300000,121.4730000,1,1,0,1,2,null)",
            "update cms_activity set update_time = '2026-04-01 10:00:00', start_date = '2027-01-01 00:00:00' where id = 4",
            "update cms_activity set update_time = '2026-04-02 11:00:00', start_date = '2026-01-01 00:00:00' where id = 16",
            "update cms_activity set update_time = '2026-04-03 12:00:00' where id = 17"
    };
}
