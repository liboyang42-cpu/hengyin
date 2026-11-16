package com.chengyinhub.business.readcache;

import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.domain.vo.CityNodeVO;
import com.chengyinhub.business.mapper.RoamFogCellMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.session.SqlSessionManager;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2 maps-backend: the box reads return the same rows, in the same order, with the same fields cached and uncached.
 * Runs the base SQL (test resource LegacyMapReadsMapper.xml, copied from the base branch: hengyin #830's inner box
 * query ordered by node level then distance), the production mapper and the tile cache (cold and warm) side by side on H2 in MySQL mode, over a dense random district with every
 * visibility case, ties and places on tile edges. H2 evaluates the distance in DECFLOAT (slow, so the data set is
 * small here; with #830's derived table H2 re-sorts the box for every row's correlated sub-queries, about 1 s per row
 * at 60 rows, so boxes stay under 400 m). MySQL evaluates it in double like the tile cache. The full-size comparison on MySQL 8, including a
 * bit-for-bit check of the distance, is {@code MapBoxReadParityMySqlIT}.
 */
public class MapBoxReadParityTest {
    static final double S = 30.20, N = 30.26, W = 120.12, E = 120.19;
    public static final long MEMBER = 7L;
    private final ObjectMapper json = new ObjectMapper();
    private SqlSessionFactory factory;
    private SqlSessionManager sessions;
    private InMemoryReadCacheStore store;
    private ReadCache cache;
    private MapPoiTileCache tiles;

    @BeforeEach
    public void setUp() throws Exception {
        store = new InMemoryReadCacheStore();
        cache = new ReadCache(store, new ReadCache.Settings(true, Map.of()));
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("RoamPoi", RoamPoi.class);
        configuration.getTypeAliasRegistry().registerAlias("RoamSession", RoamSession.class);
        configuration.getTypeAliasRegistry().registerAlias("CityNodeVO", CityNodeVO.class);
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:maps_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        for (String resource : List.of("mapper/business/RoamMapper.xml", "mapper/business/RoamFogCellMapper.xml",
                "mapper/legacy/LegacyMapReadsMapper.xml")) {
            try (InputStream in = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        configuration.addInterceptor(new ReadCacheInvalidationInterceptor(cache));
        factory = new SqlSessionFactoryBuilder().build(configuration);
        sessions = SqlSessionManager.newInstance(factory);
        tiles = new MapPoiTileCache(sessions.getMapper(RoamMapper.class), cache, 64, 3000);
        seed(new Random(20261115), 700);
    }

    public SqlSessionManager sessions() {
        return sessions;
    }

    public ReadCache cache() {
        return cache;
    }

    public InMemoryReadCacheStore store() {
        return store;
    }

    /** Dense district: every visibility case, places on 0.02-degree tile edges, places sharing one point. */
    private void seed(Random rnd, int places) throws Exception {
        try (SqlSession session = factory.openSession(true); Statement st = session.getConnection().createStatement()) {
            // MySQL functions the box reads use that H2 lacks.
            st.execute("create alias if not exists find_in_set for \"" + MapBoxReadParityTest.class.getName() + ".findInSet\"");
            st.execute("create alias if not exists date_sub for \"" + MapBoxReadParityTest.class.getName() + ".dateSub\"");
            st.execute("create table roam_poi (id bigint primary key, name varchar(80), type tinyint, lat decimal(10,6), lng decimal(10,6), radius_m int, address varchar(255), description varchar(255), xp int, coupon_template_id bigint, city_code varchar(20), status tinyint, create_time timestamp, merchant_id bigint, template_id bigint, node_level tinyint, tags varchar(255), cover_img varchar(500), sort int, audit_status tinyint, application_type tinyint, applicant_merchant_id bigint, audit_reason varchar(200), gallery_json text, quotes_json text, photo_credit text, photo_required int, badge_code varchar(80))");
            st.execute("create table mms_merchant (id bigint primary key, name varchar(80), category_id bigint, category_ids varchar(255))");
            st.execute("create table npc_profile (id bigint primary key, avatar varchar(255), scope_type int, scope_id bigint, enabled int, audit_status int, del_flag char(1), sort int)");
            st.execute("create table roam_poi_found (poi_id bigint, member_id bigint)");
            st.execute("create table roam_poi_reward_log (poi_id bigint, member_id bigint, status int, coupon_id bigint)");
            st.execute("create table roam_session (id bigint primary key, member_id bigint, status int, finished_at timestamp)");
            st.execute("create table roam_fog_tile (id bigint auto_increment primary key, member_id bigint, session_id bigint, geohash7 varchar(12), revealed_at timestamp)");
            int merchants = places / 5;
            try (PreparedStatement m = session.getConnection().prepareStatement("insert into mms_merchant values (?, ?, ?, ?)");
                 PreparedStatement n = session.getConnection().prepareStatement("insert into npc_profile values (?, ?, 2, ?, ?, ?, '0', ?)")) {
                for (int i = 0; i < merchants; i++) {
                    m.setLong(1, i + 1); m.setString(2, i % 4 == 0 ? "Cafe shop " + i : "Shop " + i);
                    m.setLong(3, 1 + i % 6); m.setString(4, i % 5 == 0 ? "2,3" : null); m.addBatch();
                    if (i % 2 == 0) {
                        n.setLong(1, i + 1); n.setString(2, "preset:npc_" + i % 9); n.setLong(3, i + 1);
                        n.setInt(4, i % 11 == 0 ? 0 : 1); n.setInt(5, i % 7 == 0 ? 0 : 1); n.setInt(6, i % 3); n.addBatch();
                        n.setLong(1, 100000 + i); n.setString(2, "preset:npc_alt_" + i); n.setLong(3, i + 1);
                        n.setInt(4, 1); n.setInt(5, 1); n.setInt(6, 5); n.addBatch();
                    }
                }
                m.executeBatch();
                n.executeBatch();
            }
            List<double[]> points = new ArrayList<>();
            try (PreparedStatement p = session.getConnection().prepareStatement(
                    "insert into roam_poi (id, name, type, lat, lng, radius_m, xp, city_code, status, create_time, merchant_id, node_level, tags, cover_img, sort, audit_status, application_type, gallery_json, quotes_json, photo_credit, badge_code) values (?, ?, ?, ?, ?, 80, 20, '330100', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (int i = 0; i < places; i++) {
                    double lat = round6(S + rnd.nextDouble() * (N - S)), lng = round6(W + rnd.nextDouble() * (E - W));
                    if (i % 37 == 0) lat = round6(Math.round(lat / 0.02) * 0.02);
                    if (i % 41 == 0) lng = round6(Math.round(lng / 0.02) * 0.02);
                    if (i % 29 == 0 && !points.isEmpty()) { lat = points.get(points.size() - 1)[0]; lng = points.get(points.size() - 1)[1]; }
                    points.add(new double[]{lat, lng});
                    int kind = i % 20;
                    p.setLong(1, i + 1);
                    p.setString(2, (i % 6 == 0 ? "Cafe " : "Place ") + i);
                    p.setInt(3, i % 3 == 0 ? 2 : 1);
                    p.setBigDecimal(4, new java.math.BigDecimal(String.format("%.6f", lat)));
                    p.setBigDecimal(5, new java.math.BigDecimal(String.format("%.6f", lng)));
                    p.setInt(6, kind == 0 ? 0 : 1);
                    p.setTimestamp(7, new Timestamp(1_763_000_000_000L + i * 60_000L));
                    if (i % 3 == 0) p.setLong(8, 1 + i % merchants); else p.setNull(8, java.sql.Types.BIGINT);
                    if (i % 9 == 0) p.setNull(9, java.sql.Types.TINYINT); else p.setInt(9, i % 4); // null and 0 rank alike
                    p.setString(10, i % 4 == 0 ? "coffee,green" : "art");
                    p.setString(11, "/images/p" + i % 7 + ".jpg");
                    p.setInt(12, i % 10);
                    Integer type = kind == 1 || kind == 2 || kind == 3 ? Integer.valueOf(1) : kind == 4 ? Integer.valueOf(3) : kind == 5 ? Integer.valueOf(2) : null;
                    Integer audit = kind == 1 ? Integer.valueOf(0) : kind == 2 ? Integer.valueOf(2) : kind == 3 || kind == 4 ? Integer.valueOf(1) : null;
                    if (audit == null) p.setNull(13, java.sql.Types.TINYINT); else p.setInt(13, audit);
                    if (type == null) p.setNull(14, java.sql.Types.TINYINT); else p.setInt(14, type);
                    p.setString(15, i % 5 == 0 ? null : "[\"/g/" + i + "a.jpg\",\"/g/" + i + "b.jpg\",\"/g/" + i + "c.jpg\",\"/g/" + i + "d.jpg\"]");
                    p.setString(16, i % 7 == 0 ? "[{\"text\":\"q" + i + "\"}]" : null);
                    p.setString(17, i % 8 == 0 ? "photo " + i : null);
                    p.setString(18, i % 6 == 0 ? "badge_" + i : null);
                    p.addBatch();
                }
                p.executeBatch();
            }
            for (int i = 0; i < places; i += 13) st.execute("insert into roam_poi_found values (" + (i + 1) + ", " + MEMBER + ")");
            for (int i = 0; i < places; i += 17) {
                st.execute("insert into roam_poi_reward_log values (" + (i + 1) + ", " + MEMBER + ", " + (i % 3) + ", " + (i % 2 == 0 ? 0 : 5) + ")");
            }
            st.execute("insert into roam_poi_found values (5, 8)");
            st.execute("insert into roam_session values (1, " + MEMBER + ", 0, null), (2, " + MEMBER + ", 1, now()), (3, " + MEMBER + ", 1, timestamp '2020-01-01 00:00:00')");
            String[] cells = {"wtmkq0a", "wtmkq0b", "wtmkr1c", "WTMKS2D", "wtmkt3e", "wtmku4f", "wtmkq0a"};
            for (int i = 0; i < cells.length; i++) {
                st.execute("insert into roam_fog_tile (member_id, session_id, geohash7, revealed_at) values (" + MEMBER + ", " + (1 + i % 3) + ", '" + cells[i] + "', now())");
            }
        }
    }

    /** MySQL FIND_IN_SET for H2. */
    public static int findInSet(String needle, String list) {
        if (needle == null || list == null) return 0;
        String[] parts = list.split(",", -1);
        for (int i = 0; i < parts.length; i++) if (parts[i].equals(needle)) return i + 1;
        return 0;
    }

    /** MySQL DATE_SUB(t, INTERVAL n DAY) for H2 (the reads here only subtract days). */
    public static Timestamp dateSub(Timestamp t, org.h2.api.Interval interval) {
        return t == null ? null : Timestamp.from(t.toInstant().minus(java.time.Duration.ofDays(interval.getLeading())));
    }

    static double round6(double v) {
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }

    private List<RoamPoi> legacy(double[] box, Long member, int limit, String keyword, Long category, String tag) {
        try (SqlSession s = factory.openSession(true)) {
            Map<String, Object> p = new HashMap<>();
            p.put("minLat", box[0]); p.put("maxLat", box[1]); p.put("minLng", box[2]); p.put("maxLng", box[3]);
            p.put("memberId", member); p.put("limit", limit);
            p.put("keyword", keyword); p.put("categoryId", category); p.put("tag", tag);
            return s.selectList("legacy.MapReads.selectPoisNearFiltered", p);
        }
    }

    private List<RoamPoi> current(double[] box, Long member, int limit, String keyword, Long category, String tag) {
        try (SqlSession s = factory.openSession(true)) {
            return s.getMapper(RoamMapper.class).selectPoisNearFiltered(box[0], box[1], box[2], box[3], member, limit, keyword, category, tag);
        }
    }

    /** The box the controller builds for a point and radius (ApiRoamController.pois). */
    static double[] box(double lat, double lng, int r) {
        double dLat = r / 111320.0;
        double dLng = r / (111320.0 * Math.max(0.2, Math.cos(Math.toRadians(lat))));
        return new double[]{lat - dLat, lat + dLat, lng - dLng, lng + dLng};
    }

    private String text(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    @Test
    void unfilteredReadIsIdenticalForSqlAndCacheOverRandomBoxes() throws Exception {
        Random rnd = new Random(7);
        int compared = 0, nonEmpty = 0;
        for (int i = 0; i < 40; i++) {
            double lat = S - 0.01 + rnd.nextDouble() * (N - S + 0.02), lng = W - 0.01 + rnd.nextDouble() * (E - W + 0.02);
            int r = 120 + rnd.nextInt(260);
            double[] b = box(lat, lng, r);
            Long member = i % 3 == 0 ? 0L : MEMBER;
            int limit = i % 4 == 0 ? 25 : i % 4 == 1 ? 501 : 500; // 501 = what the controller asks (A5.2 truncated)
            String expected = text(legacy(b, member, limit, null, null, null));
            assertEquals(expected, text(current(b, member, limit, null, null, null)), "production SQL, box " + i);
            assertEquals(expected, text(tiles.near(b[0], b[1], b[2], b[3], member, limit)), "tile cache, box " + i);
            assertEquals(expected, text(tiles.near(b[0], b[1], b[2], b[3], member, limit)), "tile cache warm, box " + i);
            compared++;
            if (!expected.equals("[]")) nonEmpty++;
        }
        assertEquals(40, compared);
        assertTrue(nonEmpty > 15, "the boxes must actually hit places: " + nonEmpty);
        assertTrue(cache.hits() > 0 && cache.misses() > 0);
    }

    @Test
    void truncationAtLimitKeepsTheSameNearestPlacesIncludingTies() throws Exception {
        double[] b = box(30.23, 120.155, 380);
        int all = legacy(b, MEMBER, 500, null, null, null).size();
        assertTrue(all >= 4, "the box must hold several places: " + all);
        List<RoamPoi> before = legacy(b, MEMBER, all - 1, null, null, null);
        assertEquals(all - 1, before.size());
        assertEquals(text(before), text(tiles.near(b[0], b[1], b[2], b[3], MEMBER, all - 1)));
        assertEquals(text(before), text(current(b, MEMBER, all - 1, null, null, null)));
    }

    @Test
    void filteredReadsAreIdentical() throws Exception {
        Random rnd = new Random(11);
        for (int i = 0; i < 20; i++) {
            double[] b = box(S + rnd.nextDouble() * (N - S), W + rnd.nextDouble() * (E - W), 200 + rnd.nextInt(200));
            String kw = i % 3 == 0 ? "Cafe" : null;
            Long cat = i % 3 == 1 ? Long.valueOf(2 + i % 4) : null;
            String tag = i % 3 == 2 ? "coffee" : null;
            assertEquals(text(legacy(b, MEMBER, 500, kw, cat, tag)), text(current(b, MEMBER, 500, kw, cat, tag)), "filtered box " + i);
        }
    }

    // The fog prefix read (selectRecentCellsByPrefixes) uses MySQL's "interval 30 day", which H2 cannot parse;
    // its before / after parity runs on MySQL in MapBoxReadParityMySqlIT.

    @Test
    void writesToPlacesOrAvatarsInvalidateTilesButMemberWritesDoNot() throws Exception {
        double[] b = box(30.23, 120.155, 380);
        RoamPoi first = tiles.near(b[0], b[1], b[2], b[3], MEMBER, 500).get(0);
        long entries = store.entries(ReadCacheNamespace.POI);
        assertTrue(entries > 0);
        String version = cache.version(ReadCacheNamespace.POI);

        // A member's own write (found) is not shared data: the tile entries stay, yet the mark is fresh.
        try (SqlSession s = factory.openSession(true)) {
            s.insert("legacy.MapReads.insertFound", Map.of("poiId", first.getId(), "memberId", 99L));
        }
        assertEquals(version, cache.version(ReadCacheNamespace.POI));
        assertTrue(tiles.near(b[0], b[1], b[2], b[3], 99L, 500).get(0).getFound());

        // Any mapper writing roam_poi moves the version on: the next read shows the new name.
        try (SqlSession s = factory.openSession(true)) {
            s.update("legacy.MapReads.renamePoi", Map.of("id", first.getId(), "name", "Renamed place"));
        }
        assertNotEquals(version, cache.version(ReadCacheNamespace.POI));
        RoamPoi renamed = tiles.near(b[0], b[1], b[2], b[3], MEMBER, 500).get(0);
        assertEquals("Renamed place", renamed.getName());
        assertEquals(text(legacy(b, MEMBER, 500, null, null, null)), text(tiles.near(b[0], b[1], b[2], b[3], MEMBER, 500)));

        // npc_profile (shop avatar shown on the map) too: a visible shop with an avatar, in a small box around it.
        double[] shop;
        Long merchant;
        try (SqlSession s = factory.openSession(true); Statement st = s.getConnection().createStatement();
             java.sql.ResultSet rs = st.executeQuery("select p.lat, p.lng, p.merchant_id from roam_poi p join npc_profile n on n.scope_id = p.merchant_id and n.scope_type = 2 and n.enabled = 1 and n.audit_status = 1 and n.del_flag = '0' where p.status = 1 and p.application_type is null order by p.id limit 1")) {
            assertTrue(rs.next());
            shop = box(rs.getDouble(1), rs.getDouble(2), 150);
            merchant = rs.getLong(3);
        }
        String before = text(tiles.near(shop[0], shop[1], shop[2], shop[3], MEMBER, 500));
        assertTrue(before.contains("preset:npc_"), before);
        String v2 = cache.version(ReadCacheNamespace.POI);
        try (SqlSession s = factory.openSession(true)) {
            s.update("legacy.MapReads.renameAvatar", Map.of("scopeId", merchant, "avatar", "preset:npc_new"));
        }
        assertNotEquals(v2, cache.version(ReadCacheNamespace.POI));
        String after = text(tiles.near(shop[0], shop[1], shop[2], shop[3], MEMBER, 500));
        assertTrue(after.contains("preset:npc_new"), after);
        assertEquals(text(legacy(shop, MEMBER, 500, null, null, null)), after);
    }

    @Test
    void uncommittedWriteInvalidatesOnlyOnCommit() throws Exception {
        double[] b = box(30.23, 120.155, 380);
        tiles.near(b[0], b[1], b[2], b[3], MEMBER, 500);
        String version = cache.version(ReadCacheNamespace.POI);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            cache.invalidateAfterCommit(ReadCacheNamespace.POI);
            cache.invalidateAfterCommit(ReadCacheNamespace.POI);
            assertEquals(version, cache.version(ReadCacheNamespace.POI), "not before commit");
            var syncs = org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, syncs.size(), "one pending invalidation per transaction");
            syncs.forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
        assertEquals(Long.parseLong(version) + 1, Long.parseLong(cache.version(ReadCacheNamespace.POI)));

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            cache.invalidateAfterCommit(ReadCacheNamespace.POI);
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
        assertEquals(Long.parseLong(version) + 1, Long.parseLong(cache.version(ReadCacheNamespace.POI)), "a rollback keeps the cache");
    }

    @Test
    void switchOffRedisDownDenseTileAndHugeBoxAllReadMySqlWithTheSameAnswer() throws Exception {
        double[] b = box(30.23, 120.155, 380);
        String expected = text(legacy(b, MEMBER, 500, null, null, null));

        ReadCache off = new ReadCache(store, new ReadCache.Settings(false, Map.of()));
        int before = store.calls.get();
        assertEquals(expected, text(new MapPoiTileCache(sessions.getMapper(RoamMapper.class), off, 64, 3000)
                .near(b[0], b[1], b[2], b[3], MEMBER, 500)));
        assertEquals(before, store.calls.get(), "switched off: Redis is not touched");

        store.down = true;
        assertEquals(expected, text(tiles.near(b[0], b[1], b[2], b[3], MEMBER, 500)));
        assertTrue(cache.errors() > 0);
        store.down = false;

        MapPoiTileCache dense = new MapPoiTileCache(sessions.getMapper(RoamMapper.class), cache, 64, 10);
        assertEquals(expected, text(dense.near(b[0], b[1], b[2], b[3], MEMBER, 500)));
        assertTrue(store.values.containsValue(MapPoiTileCache.DENSE), "a dense tile is remembered, not re-counted");
        assertEquals(expected, text(dense.near(b[0], b[1], b[2], b[3], MEMBER, 500)));

        double[] corner = box(30.24, 120.16, 380); // straddles a tile corner: 4 tiles
        String cornerExpected = text(legacy(corner, MEMBER, 500, null, null, null));
        MapPoiTileCache few = new MapPoiTileCache(sessions.getMapper(RoamMapper.class), cache, 2, 3000);
        long entries = store.entries(ReadCacheNamespace.POI);
        assertEquals(cornerExpected, text(few.near(corner[0], corner[1], corner[2], corner[3], MEMBER, 500)));
        assertEquals(entries, store.entries(ReadCacheNamespace.POI), "a box over max-tiles goes straight to MySQL");
    }

    @Test
    void tileEntriesHoldNoMemberData() {
        double[] b = box(30.23, 120.155, 380);
        tiles.near(b[0], b[1], b[2], b[3], MEMBER, 500);
        for (Map.Entry<String, String> e : store.values.entrySet()) {
            if (!e.getKey().startsWith(ReadCache.PREFIX + ReadCacheNamespace.POI + ":")) continue;
            assertFalse(e.getValue().contains("\"found\":true"), e.getKey());
            assertFalse(e.getValue().contains("\"pendingRedeem\":true"), e.getKey());
        }
    }
}
