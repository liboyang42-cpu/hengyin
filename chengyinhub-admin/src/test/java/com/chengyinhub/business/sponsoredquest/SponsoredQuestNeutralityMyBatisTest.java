package com.chengyinhub.business.sponsoredquest;

import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.domain.vo.CityNodeVO;
import com.chengyinhub.business.mapper.RoamFogCellMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.sponsoredquest.mapper.SponsoredQuestMapper;
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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Owner rule "paid placement never touches the fog map" for S3b: runs the real roam pin read (rank and keyword search)
 * and the real fog cell reads on H2 (MySQL mode) without a campaign, then with a live, fully funded campaign that
 * sponsors the lowest-ranked place, and requires identical results. The sponsored mapper is run on the same data to
 * prove the campaign really is live while the comparison is made.
 */
class SponsoredQuestNeutralityMyBatisTest {
    private static final double MIN_LAT = 30.0, MAX_LAT = 30.4, MIN_LNG = 120.0, MAX_LNG = 120.4;
    private static final long MEMBER = 7L;
    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("RoamPoi", RoamPoi.class);
        configuration.getTypeAliasRegistry().registerAlias("RoamSession", RoamSession.class);
        configuration.getTypeAliasRegistry().registerAlias("CityNodeVO", CityNodeVO.class);
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:sq_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        for (String resource : List.of("mapper/business/RoamMapper.xml", "mapper/business/RoamFogCellMapper.xml")) {
            try (InputStream in = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        configuration.addMapper(SponsoredQuestMapper.class);
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true); Statement st = session.getConnection().createStatement()) {
            st.execute("create table roam_poi (id bigint primary key, name varchar(80), type tinyint, lat decimal(10,6), lng decimal(10,6), radius_m int, address varchar(255), description varchar(255), xp int, coupon_template_id bigint, city_code varchar(20), status tinyint, create_time timestamp, merchant_id bigint, template_id bigint, node_level tinyint, tags varchar(255), cover_img varchar(500), sort int, audit_status tinyint, application_type tinyint, applicant_merchant_id bigint, audit_reason varchar(200), gallery_json text, quotes_json text, photo_credit text, photo_required int, badge_code varchar(80))");
            st.execute("create table mms_merchant (id bigint primary key, name varchar(80), category_id bigint, category_ids varchar(255))");
            st.execute("create table npc_profile (id bigint primary key, avatar varchar(255), scope_type int, scope_id bigint, enabled int, audit_status int, del_flag char(1), sort int)");
            st.execute("create table roam_poi_found (poi_id bigint, member_id bigint)");
            st.execute("create table roam_poi_reward_log (poi_id bigint, member_id bigint, status int, coupon_id bigint)");
            st.execute("create table roam_member_fog_cell (member_id bigint, cell_key varchar(16), cell_prefix5 varchar(5), first_revealed_at timestamp, last_revealed_at timestamp, primary key (member_id, cell_key))");
            st.execute("create table sponsored_quest_campaign (id bigint auto_increment primary key, sponsor_name varchar(64) not null, sponsor_merchant_id bigint, title varchar(64) not null, content varchar(1000) not null, cover_img varchar(500), budget_cent bigint not null default 0, spent_cent bigint not null default 0, start_time timestamp not null, end_time timestamp not null, status tinyint not null default 0, create_by varchar(64) default '', create_time timestamp not null, update_by varchar(64) default '', update_time timestamp)");
            st.execute("create table sponsored_quest_place (campaign_id bigint not null, poi_id bigint not null, sort int not null default 0, primary key (campaign_id, poi_id))");
            st.execute("create table sponsored_quest_charge (id bigint auto_increment primary key, campaign_id bigint not null, ref_key varchar(80) not null unique, amount_cent bigint not null, reason varchar(255) default '', create_by varchar(64) default '', create_time timestamp not null)");
            // Five published places at growing distance from the box center; 'Cafe' matches the keyword search for three.
            st.execute("insert into roam_poi (id, name, type, lat, lng, status, tags, sort) values"
                    + " (1, 'Cafe One', 1, 30.20, 120.20, 1, 'coffee', 0),"
                    + " (2, 'Park', 1, 30.21, 120.21, 1, 'green', 0),"
                    + " (3, 'Cafe Two', 1, 30.25, 120.25, 1, 'coffee', 0),"
                    + " (4, 'Museum', 1, 30.30, 120.30, 1, 'art', 0),"
                    + " (5, 'Cafe Far', 1, 30.38, 120.38, 1, 'coffee', 0)");
            st.execute("insert into roam_poi_found (poi_id, member_id) values (2, 7)");
            st.execute("insert into roam_member_fog_cell values"
                    + " (7, 'wtmkq0a', 'wtmkq', timestamp '2026-11-01 10:00:00', timestamp '2026-11-10 10:00:00'),"
                    + " (7, 'wtmkq0b', 'wtmkq', timestamp '2026-11-02 10:00:00', timestamp '2026-11-12 10:00:00')");
        }
    }

    private record Snapshot(List<Long> pins, List<Boolean> found, List<Long> search, List<Long> tagged,
                            List<String> fogAll, List<String> fogByPrefix) {
    }

    private Snapshot snapshot() {
        try (SqlSession session = factory.openSession(true)) {
            RoamMapper roam = session.getMapper(RoamMapper.class);
            RoamFogCellMapper fog = session.getMapper(RoamFogCellMapper.class);
            List<RoamPoi> pins = roam.selectPoisNear(MIN_LAT, MAX_LAT, MIN_LNG, MAX_LNG, MEMBER, 100);
            List<Boolean> found = new ArrayList<>();
            for (RoamPoi p : pins) found.add(p.getFound());
            return new Snapshot(ids(pins), found,
                    ids(roam.selectPoisNearFiltered(MIN_LAT, MAX_LAT, MIN_LNG, MAX_LNG, MEMBER, 100, "Cafe", null, null)),
                    ids(roam.selectPoisNearFiltered(MIN_LAT, MAX_LAT, MIN_LNG, MAX_LNG, MEMBER, 100, null, null, "coffee")),
                    fog.selectRetainedCells(MEMBER, 100),
                    fog.selectRetainedCellsByPrefixes(MEMBER, List.of("wtmkq"), 100));
        }
    }

    private static List<Long> ids(List<RoamPoi> rows) {
        List<Long> out = new ArrayList<>();
        for (RoamPoi p : rows) out.add(p.getId());
        return out;
    }

    private void addLiveCampaignSponsoring(long... poiIds) throws Exception {
        try (SqlSession session = factory.openSession(true); Statement st = session.getConnection().createStatement()) {
            st.execute("insert into sponsored_quest_campaign (id, sponsor_name, title, content, budget_cent, spent_cent, start_time, end_time, status, create_time)"
                    + " values (900, 'Brand', 'Coffee walk', 'Visit three cafes', 1000000, 0,"
                    + " dateadd('DAY', -1, now()), dateadd('DAY', 7, now()), 1, now())");
            int sort = 0;
            for (long id : poiIds) {
                st.execute("insert into sponsored_quest_place (campaign_id, poi_id, sort) values (900, " + id + ", " + (sort++) + ")");
            }
        }
    }

    @Test
    void liveCampaignChangesNeitherPinRankNorSearchOrderNorFog() throws Exception {
        Snapshot without = snapshot();
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), without.pins(), "pins rank by distance to the box center");
        assertEquals(List.of(1L, 3L, 5L), without.search());
        assertEquals(2, without.fogAll().size());

        // Sponsor the farthest place (lowest rank) and a mid-ranked one: a paid boost would move them up.
        addLiveCampaignSponsoring(5L, 3L);
        try (SqlSession session = factory.openSession(true)) {
            List<Map<String, Object>> live = session.getMapper(SponsoredQuestMapper.class)
                    .selectLiveInBox(MIN_LAT, MAX_LAT, MIN_LNG, MAX_LNG, 20);
            assertEquals(1, live.size(), "the campaign is live during the comparison");
        }
        Snapshot with = snapshot();
        assertEquals(without, with, "a live campaign must not change pins, their order, search results or the fog");
    }

    @Test
    void liveReadReturnsTheCampaignOnlyInsideItsWindowAndAlwaysLabelled() throws Exception {
        addLiveCampaignSponsoring(5L, 3L);
        try (SqlSession session = factory.openSession(true)) {
            SponsoredQuestService service = new SponsoredQuestService(session.getMapper(SponsoredQuestMapper.class));
            List<Map<String, Object>> rows = service.liveInBox(MIN_LAT, MAX_LAT, MIN_LNG, MAX_LNG);
            assertEquals(1, rows.size());
            Map<String, Object> row = rows.get(0);
            assertEquals("广告", row.get("label"));
            assertEquals("Ad", row.get("labelEn"));
            assertEquals(Boolean.TRUE, row.get("sponsored"));
            List<?> places = (List<?>) row.get("places");
            assertEquals(2, places.size());
            assertEquals(5L, ((Map<?, ?>) places.get(0)).get("poiId"), "places keep the campaign's own order");

            exec(session, "update sponsored_quest_campaign set status = 2 where id = 900");
            assertTrue(service.liveInBox(MIN_LAT, MAX_LAT, MIN_LNG, MAX_LNG).isEmpty(), "paused campaigns are not shown");
            exec(session, 
                    "update sponsored_quest_campaign set status = 1, end_time = dateadd('DAY', -1, now()) where id = 900");
            assertTrue(service.liveInBox(MIN_LAT, MAX_LAT, MIN_LNG, MAX_LNG).isEmpty(), "ended windows are not shown");
            exec(session, 
                    "update sponsored_quest_campaign set end_time = dateadd('DAY', 7, now()), spent_cent = budget_cent where id = 900");
            assertTrue(service.liveInBox(MIN_LAT, MAX_LAT, MIN_LNG, MAX_LNG).isEmpty(), "a spent budget stops the campaign");
            exec(session, "update roam_poi set status = 0 where id in (3, 5)");
            exec(session, "update sponsored_quest_campaign set spent_cent = 0 where id = 900");
            assertTrue(service.liveInBox(MIN_LAT, MAX_LAT, MIN_LNG, MAX_LNG).isEmpty(), "unpublished places are never shown");
        }
    }

    /** Raw JDBC change, then drop MyBatis' session cache so the next read sees it. */
    private static void exec(SqlSession session, String sql) throws Exception {
        try (Statement st = session.getConnection().createStatement()) {
            st.execute(sql);
        }
        session.clearCache();
    }

    @Test
    void chargeLedgerIsIdempotentAndBudgetBoundOnH2() throws Exception {
        addLiveCampaignSponsoring(1L);
        try (SqlSession session = factory.openSession(true)) {
            SponsoredQuestMapper mapper = session.getMapper(SponsoredQuestMapper.class);
            assertEquals(1, mapper.insertChargeIgnore(900L, "ref-0001", 400_000L, "week 1", "op"));
            assertEquals(0, mapper.insertChargeIgnore(900L, "ref-0001", 400_000L, "week 1", "op"));
            assertEquals(1, mapper.addSpentWithinBudget(900L, 400_000L));
            assertEquals(1, mapper.addSpentWithinBudget(900L, 600_000L));
            assertEquals(0, mapper.addSpentWithinBudget(900L, 1L), "never past the budget");
            assertEquals(0, mapper.addSpentWithinBudget(900L, -5L), "never negative");
            assertFalse(mapper.selectCharges(900L, 10).isEmpty());
        }
    }
}
