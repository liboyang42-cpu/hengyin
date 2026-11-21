package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.dto.seed.SeedGeoPoint;
import com.chengyinhub.business.domain.dto.seed.SeedPlace;
import com.chengyinhub.business.domain.dto.seed.SeedRoute;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Runs SeedContentMapper.xml against H2 (MySQL mode) with the columns it touches. */
class SeedContentMapperH2Test {
    private SqlSessionFactory factory;
    private DriverManagerDataSource ds;

    @BeforeEach void setUp() throws Exception {
        ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:seed_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("create table roam_poi (id bigint auto_increment primary key, name varchar(80), type tinyint, lat decimal(10,6), lng decimal(10,6),"
                    + " radius_m int, address varchar(255), description varchar(255), xp int, city_code varchar(20), status tinyint, audit_status tinyint,"
                    + " application_type tinyint, merchant_id bigint, template_id bigint, tags varchar(255), cover_img varchar(500), gallery_json text,"
                    + " photo_required tinyint, category varchar(40), opening_hours varchar(120), create_time datetime)");
            s.execute("create table cms_topic (id bigint auto_increment primary key, member_id bigint, topic_type int, product_type tinyint, name varchar(255),"
                    + " description varchar(8000), address_name varchar(200), location_count int, template_count int, total_mileage decimal(10,2),"
                    + " status tinyint, user_status tinyint, lifecycle tinyint, del_flag tinyint, is_draft tinyint, publish_mode varchar(16),"
                    + " journey_rules_json text, create_by varchar(64), create_time datetime)");
            s.execute("create table cms_topic_node (id bigint auto_increment primary key, topic_id bigint, chapter_id bigint, name varchar(255),"
                    + " description varchar(500), address varchar(255), longitude varchar(255), latitude varchar(255), img_url varchar(2048),"
                    + " template_id bigint, sort_id int, create_by varchar(64), create_time datetime)");
            s.execute("create table mms_merchant (id bigint primary key, status int, account_status int, del_flag tinyint, location_lat decimal(10,7), location_lng decimal(10,7))");
            s.execute("create table city_game_board (board_id varchar(96) primary key, title varchar(160), region_id varchar(96), lifecycle varchar(16),"
                    + " map_south decimal(16,12), map_west decimal(16,12), map_north decimal(16,12), map_east decimal(16,12), update_time datetime)");
            s.execute("create table city_game_point (board_id varchar(96), point_id varchar(96), latitude decimal(16,12), longitude decimal(16,12), del_flag tinyint)");
            s.execute("create table growth_mission (id bigint primary key, status char(1), daily_limit int)");
            s.execute("create table official_event (id bigint primary key, status tinyint, city varchar(32), activity_end datetime)");
        }
        Configuration configuration = new Configuration(new Environment("seed", new JdbcTransactionFactory(), ds));
        configuration.addMapper(SeedContentMapper.class);
        String resource = "mapper/business/SeedContentMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
    }

    private void exec(String... sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) { for (String q : sql) s.execute(q); }
    }

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    @Test void importedPlaceIsAHiddenOfficialDraftAndExports() {
        try (SqlSession session = factory.openSession(true)) {
            SeedContentMapper m = session.getMapper(SeedContentMapper.class);
            SeedPlace p = new SeedPlace();
            p.setName("孤山"); p.setLat(d("30.256000")); p.setLng(d("120.145000")); p.setAddress("孤山路"); p.setCityCode("330100");
            p.setCategory("景点"); p.setOpeningHours("全天"); p.setTags("山,湖");
            assertEquals(1, m.insertImportedPlace(p));
            assertNotNull(p.getId());
            List<SeedPlace> exported = m.selectOfficialPlacesForExport("330100");
            assertEquals(1, exported.size());
            SeedPlace back = exported.get(0);
            assertEquals(0, back.getStatus());
            assertEquals(3, back.getAuditStatus());
            assertEquals("景点", back.getCategory());
            assertEquals("全天", back.getOpeningHours());
            assertTrue(m.selectOfficialPlacesForExport("310100").isEmpty());
            assertEquals(1, m.selectPlacesInBox(d("30.2"), d("30.3"), d("120.1"), d("120.2")).size());
            assertEquals(1, m.selectPlacesByIds(List.of(p.getId(), 999L)).size());
        }
    }

    @Test void routeDraftAndNodesAndDensityReads() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            SeedContentMapper m = session.getMapper(SeedContentMapper.class);
            SeedRoute r = new SeedRoute();
            r.setMemberId(7L); r.setName("湖滨线"); r.setLocationCount(2); r.setTemplateCount(1); r.setTotalMileage(d("1.20"));
            r.setJourneyRulesJson("{}"); r.setCreateBy("ops"); r.setAddressName("北山街");
            assertEquals(1, m.insertRouteDraft(r));
            SeedPlace stop = new SeedPlace();
            stop.setName("断桥"); stop.setLat(d("30.259300")); stop.setLng(d("120.151000")); stop.setAddress("北山街");
            assertEquals(1, m.insertRouteNode(r.getId(), 1, stop, 11L, "ops"));
            exec("insert into cms_topic_node (topic_id, latitude, longitude, template_id) values (" + r.getId() + ", 'bad', '120.1', 0)");
            assertEquals(List.of("湖滨线"), m.selectExistingRouteNames(List.of("湖滨线", "别的")));
            List<SeedGeoPoint> nodes = m.selectRouteNodesInBox(d("30.2"), d("30.3"), d("120.1"), d("120.2"));
            assertEquals(1, nodes.size(), "malformed coordinates are skipped, not fatal");
            assertEquals(0, nodes.get(0).getFlag1(), "draft route is not live");
            assertEquals(1, nodes.get(0).getFlag2());

            exec("insert into mms_merchant values (5, 1, 1, 0, 30.25, 120.15)", "insert into mms_merchant values (6, 0, 1, 0, 30.25, 120.15)",
                    "insert into roam_poi (name, type, lat, lng, status, merchant_id, template_id) values ('店', 2, 30.25, 120.15, 1, 5, 9)",
                    "insert into city_game_board values ('b1', '西湖', 'r1', 'OPEN', 30.2, 120.1, 30.3, 120.2, now())",
                    "insert into city_game_board values ('b2', '旧', 'r1', 'ARCHIVED', 30.2, 120.1, 30.3, 120.2, now())",
                    "insert into city_game_point values ('b1', 'p1', 30.25, 120.15, 0), ('b1', 'p2', 30.25, 120.15, 1), ('b2', 'p3', 30.25, 120.15, 0)",
                    "insert into growth_mission values (1, '0', 1), (2, '1', 1), (3, '0', 0)",
                    "insert into official_event values (1, 2, '杭州', null), (2, 0, '杭州', null), (3, 3, '上海', null), (4, 1, null, null)");
            List<SeedGeoPoint> shops = m.selectLiveShopsInBox(d("30.2"), d("30.3"), d("120.1"), d("120.2"));
            assertEquals(1, shops.size());
            assertEquals(1, shops.get(0).getFlag1());
            List<SeedGeoPoint> board = m.selectBoardPointsInBox(d("30.2"), d("30.3"), d("120.1"), d("120.2"));
            assertEquals(1, board.size());
            assertEquals("b1", board.get(0).getKey());
            assertEquals(1, m.countActiveDailyMissions());
            assertEquals(2, m.countOpeningEvents("杭州"));
            assertEquals(3, m.countOpeningEvents(null));
            List<Map<String, Object>> areas = m.selectBoardAreas();
            assertEquals(1, areas.size());
            assertEquals("b1", areas.get(0).get("boardId"));
        }
    }
}
