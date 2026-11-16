package com.chengyinhub.business.readcache;

import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.domain.vo.CityNodeVO;
import com.chengyinhub.business.mapper.ClubOpsMapper;
import com.chengyinhub.business.mapper.MarketplaceMapper;
import com.chengyinhub.business.mapper.RoamFogCellMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.sponsoredquest.mapper.SponsoredQuestMapper;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2 maps-backend on MySQL 8 (opt-in: CHENGYIN_MYBATIS_IT_PORT / _USER / _PASSWORD, loopback only).
 * On a fresh schema (base schema + the map migrations) seeded by ci/perf/maps/seed_dense_district.py:
 * <ol>
 *   <li>records every box read before the new indexes (legacy pois SQL, legacy fog SQL, clubs nearby, deals,
 *       sponsored quests) over random boxes;</li>
 *   <li>applies migration_20261115_maps_backend.sql twice (idempotent);</li>
 *   <li>requires the same answers from the base SQL (#830), the production mapper, the tile cache (cold and warm) and the
 *       unchanged reads, and EXPLAIN to use the new indexes;</li>
 *   <li>requires the Java distance used by the tile cache to equal MySQL's, bit for bit, for every seeded place.</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(600)
class MapBoxReadParityMySqlIT {
    private static final double CENTER_LAT = 30.2590, CENTER_LNG = 120.1500;
    private static final long BASE = 9_000_000L;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private String schema;
    private SqlSessionFactory factory;
    private SqlSessionManager sessions;
    private Path sql;

    @BeforeAll
    void setUp() throws Exception {
        int port = Integer.parseInt(required("CHENGYIN_MYBATIS_IT_PORT"));
        String user = required("CHENGYIN_MYBATIS_IT_USER");
        String password = required("CHENGYIN_MYBATIS_IT_PASSWORD");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=LOCAL&allowMultiQueries=false";
        admin = new JdbcTemplate(new DriverManagerDataSource(base + "mysql" + options, user, password));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        schema = "cy_maps_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create database " + schema + " default character set utf8mb4 collate utf8mb4_0900_ai_ci");
        DriverManagerDataSource source = new DriverManagerDataSource(base + schema + options, user, password);
        db = new JdbcTemplate(source);
        sql = Paths.get("chengyinhub-admin/sql");
        Path root = Paths.get(".");
        if (!Files.isDirectory(sql)) { sql = Paths.get("sql"); root = Paths.get(".."); }
        Path baseSchema = root.resolve("sql/base_schema_20260818.sql");
        ResourceDatabasePopulator schemaSetup = new ResourceDatabasePopulator(new FileSystemResource(baseSchema),
                new FileSystemResource(sql.resolve("migration_club_open_settings_20260909.sql")),
                new FileSystemResource(sql.resolve("migration_20261115_marketplace.sql")),
                new FileSystemResource(sql.resolve("migration_20261115_sponsored_quests.sql")),
                new FileSystemResource(sql.resolve("migration_20261115_club_ops.sql")));
        schemaSetup.setSeparator(";");
        schemaSetup.execute(source);
        // fog_map's backfill trips ONLY_FULL_GROUP_BY on an empty schema (reported in the receipt); the table is all we need.
        db.execute("CREATE TABLE IF NOT EXISTS roam_member_fog_cell (member_id bigint NOT NULL, cell_key varchar(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, cell_prefix5 char(5) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, first_revealed_at datetime NOT NULL, last_revealed_at datetime NOT NULL, PRIMARY KEY (member_id, cell_key), KEY idx_member_prefix (member_id, cell_prefix5)) ENGINE=InnoDB");

        Path seed = Files.createTempFile("maps-seed", ".sql");
        Process p = new ProcessBuilder("python3", root.resolve("ci/perf/maps/seed_dense_district.py").toString(),
                "--database", schema, "--places", "4000", "--members", "8", "--clubs", "200")
                .redirectOutput(seed.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        assertEquals(0, p.waitFor());
        ResourceDatabasePopulator seeding = new ResourceDatabasePopulator(new FileSystemResource(seed));
        seeding.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
        seeding.execute(source);
        Files.deleteIfExists(seed);
        // The base schema predates the place-card columns that #830's /pois projection reads (added on real databases by
        // migration_20261025_official_poi_quotes and others): add them here, then vary them and node_level (incl. null).
        for (String[] c : new String[][]{{"gallery_json", "text"}, {"quotes_json", "text"}, {"photo_credit", "varchar(255)"}, {"badge_code", "varchar(80)"}}) {
            if (db.queryForObject("select count(*) from information_schema.columns where table_schema = database() and table_name = 'roam_poi' and column_name = ?", Integer.class, c[0]) == 0) {
                db.execute("alter table roam_poi add column " + c[0] + " " + c[1] + " null");
            }
        }
        db.update("update roam_poi set gallery_json = case when id % 5 = 0 then null else concat('[\"/g/', id, 'a.jpg\",\"/g/', id, 'b.jpg\",\"/g/', id, 'c.jpg\",\"/g/', id, 'd.jpg\"]') end, quotes_json = case when id % 7 = 0 then concat('[{\"text\":\"q', id, '\"}]') end, photo_credit = case when id % 8 = 0 then concat('photo ', id) end, badge_code = case when id % 6 = 0 then concat('badge_', id) end, node_level = case when id % 9 = 0 then null else id % 4 end");

        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("RoamPoi", RoamPoi.class);
        configuration.getTypeAliasRegistry().registerAlias("RoamSession", RoamSession.class);
        configuration.getTypeAliasRegistry().registerAlias("CityNodeVO", CityNodeVO.class);
        configuration.setEnvironment(new Environment("it", new JdbcTransactionFactory(),
                new PooledDataSource("com.mysql.cj.jdbc.Driver", base + schema + options, user, password)));
        for (String resource : List.of("mapper/business/RoamMapper.xml", "mapper/business/RoamFogCellMapper.xml",
                "mapper/business/ClubOpsMapper.xml", "mapper/business/MarketplaceMapper.xml", "mapper/legacy/LegacyMapReadsMapper.xml")) {
            try (InputStream in = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        configuration.addMapper(SponsoredQuestMapper.class);
        factory = new SqlSessionFactoryBuilder().build(configuration);
        sessions = SqlSessionManager.newInstance(factory);
    }

    @AfterAll
    void tearDown() {
        if (schema != null) admin.execute("drop database if exists " + schema);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required for this MySQL IT");
        return value;
    }

    private record Box(double[] b, Long member, int limit) {
    }

    private List<Box> boxes() {
        Random rnd = new Random(20261115);
        List<Box> out = new ArrayList<>();
        for (int i = 0; i < 160; i++) {
            double lat = CENTER_LAT - 0.035 + rnd.nextDouble() * 0.07, lng = CENTER_LNG - 0.04 + rnd.nextDouble() * 0.08;
            int r = i % 4 == 0 ? 3000 : 150 + rnd.nextInt(4500);
            Long member = i % 3 == 0 ? 0L : BASE + (i % 8);
            out.add(new Box(MapBoxReadParityTest.box(lat, lng, r), member, i % 5 == 0 ? 40 : i % 5 == 1 ? 501 : 500));
        }
        return out;
    }

    private Map<String, String> snapshot() throws Exception {
        Map<String, String> out = new HashMap<>();
        List<Box> boxes = boxes();
        try (SqlSession s = factory.openSession(true)) {
            for (int i = 0; i < boxes.size(); i++) {
                Box x = boxes.get(i);
                Map<String, Object> p = new HashMap<>();
                p.put("minLat", x.b()[0]); p.put("maxLat", x.b()[1]); p.put("minLng", x.b()[2]); p.put("maxLng", x.b()[3]);
                p.put("memberId", x.member()); p.put("limit", x.limit());
                out.put("pois" + i, json.writeValueAsString(s.selectList("legacy.MapReads.selectPoisNearFiltered", p)));
                p.put("tag", "coffee");
                out.put("poisTag" + i, json.writeValueAsString(s.selectList("legacy.MapReads.selectPoisNearFiltered", p)));
                out.put("clubs" + i, json.writeValueAsString(s.getMapper(ClubOpsMapper.class).selectLocatedClubs(
                        BigDecimal.valueOf(x.b()[0]), BigDecimal.valueOf(x.b()[1]), BigDecimal.valueOf(x.b()[2]), BigDecimal.valueOf(x.b()[3]), 100)));
                out.put("deals" + i, json.writeValueAsString(s.getMapper(MarketplaceMapper.class).selectDealsInBox(
                        x.b()[0], x.b()[1], x.b()[2], x.b()[3], x.member() == 0L ? null : x.member(), 100)));
                out.put("sponsored" + i, json.writeValueAsString(s.getMapper(SponsoredQuestMapper.class).selectLiveInBox(
                        x.b()[0], x.b()[1], x.b()[2], x.b()[3], 20)));
            }
            List<String> prefixes = db.queryForList("select distinct left(geohash7, 5) from roam_fog_tile where member_id = ? order by 1", String.class, BASE + 1);
            for (int i = 0; i + 3 <= prefixes.size(); i += 3) {
                Map<String, Object> p = new HashMap<>();
                p.put("memberId", BASE + 1); p.put("prefixes", prefixes.subList(i, i + 3)); p.put("limit", 20000);
                out.put("fog" + i, json.writeValueAsString(s.selectList("legacy.MapReads.selectRecentCellsByPrefixes", p)));
            }
        }
        return out;
    }

    @Test
    void boxReadsAreIdenticalBeforeAndAfterAndIndexesAreUsed() throws Exception {
        Map<String, String> before = snapshot();
        long nonEmpty = before.entrySet().stream().filter(e -> e.getKey().startsWith("pois") && !e.getValue().equals("[]")).count();
        assertTrue(nonEmpty > 100, "boxes must hit places: " + nonEmpty);
        assertTrue(before.entrySet().stream().anyMatch(e -> e.getKey().startsWith("clubs") && !e.getValue().equals("[]")));
        assertTrue(before.entrySet().stream().anyMatch(e -> e.getKey().startsWith("deals") && !e.getValue().equals("[]")));
        assertTrue(before.entrySet().stream().anyMatch(e -> e.getKey().startsWith("fog") && !e.getValue().equals("[]")));

        String migration = Files.readString(sql.resolve("migration_20261115_maps_backend.sql"));
        assertTrue(migration.contains("idx_roam_poi_player_geo"));
        for (int run = 0; run < 2; run++) {
            new ResourceDatabasePopulator(new FileSystemResource(sql.resolve("migration_20261115_maps_backend.sql")))
                    .execute(db.getDataSource());
        }
        assertEquals(1, db.queryForObject("select count(distinct index_name) from information_schema.statistics where table_schema = database() and table_name = 'roam_poi' and index_name = 'idx_roam_poi_player_geo'", Integer.class));

        // Unchanged SQL (legacy pois, clubs, deals, sponsored, legacy fog) answers the same with the new indexes.
        assertEquals(before, snapshot());

        InMemoryReadCacheStore store = new InMemoryReadCacheStore();
        ReadCache cache = new ReadCache(store, new ReadCache.Settings(true, Map.of()));
        MapPoiTileCache tiles = new MapPoiTileCache(sessions.getMapper(RoamMapper.class), cache, 64, 3000);
        List<Box> boxes = boxes();
        try (SqlSession s = factory.openSession(true)) {
            RoamMapper roam = s.getMapper(RoamMapper.class);
            for (int i = 0; i < boxes.size(); i++) {
                Box x = boxes.get(i);
                double[] b = x.b();
                assertEquals(before.get("pois" + i), json.writeValueAsString(roam.selectPoisNear(b[0], b[1], b[2], b[3], x.member(), x.limit())), "production SQL box " + i);
                assertEquals(before.get("poisTag" + i), json.writeValueAsString(roam.selectPoisNearFiltered(b[0], b[1], b[2], b[3], x.member(), x.limit(), null, null, "coffee")), "filtered box " + i);
                assertEquals(before.get("pois" + i), json.writeValueAsString(tiles.near(b[0], b[1], b[2], b[3], x.member(), x.limit())), "tile cache box " + i);
                assertEquals(before.get("pois" + i), json.writeValueAsString(tiles.near(b[0], b[1], b[2], b[3], x.member(), x.limit())), "tile cache warm box " + i);
            }
            List<String> prefixes = db.queryForList("select distinct left(geohash7, 5) from roam_fog_tile where member_id = ? order by 1", String.class, BASE + 1);
            for (int i = 0; i + 3 <= prefixes.size(); i += 3) {
                assertEquals(before.get("fog" + i), json.writeValueAsString(s.getMapper(RoamFogCellMapper.class)
                        .selectRecentCellsByPrefixes(BASE + 1, prefixes.subList(i, i + 3), 20000)), "fog " + i);
            }
        }
        assertTrue(cache.hits() > 0);

        // #830's inner query for a street-sized box (about 700 m): the box range must come from idx_roam_poi_player_geo.
        // (A box over most of the seeded district is answered by a table scan, which MySQL rightly prefers there.)
        String plan = String.join("\n", db.queryForList("explain format=tree select p.id, p.name, p.type, p.lat, p.lng, p.radius_m, p.address, p.description, p.xp, p.coupon_template_id, p.city_code, p.status, p.audit_status, p.application_type, p.applicant_merchant_id, p.audit_reason, p.create_time, p.merchant_id, p.template_id, p.node_level, p.tags, p.cover_img, p.sort, p.gallery_json, p.quotes_json, p.photo_credit, p.badge_code, (power(p.lat - (30.256 + 30.262) / 2, 2) + power((p.lng - (120.146 + 120.153) / 2) * cos(radians((30.256 + 30.262) / 2)), 2)) as near_dist from roam_poi p where p.status = 1 and (p.application_type is null or p.application_type = 2 or (p.application_type in (1, 3) and p.audit_status = 1)) and p.lat between 30.256 and 30.262 and p.lng between 120.146 and 120.153 order by coalesce(p.node_level, 0) desc, near_dist asc, p.id asc limit 501", String.class));
        System.out.println("MAPPLAN pois inner\n" + plan);
        assertTrue(plan.contains("Index range scan on p using idx_roam_poi_player_geo"), plan);
        String clubPlan = String.join("\n", db.queryForList("explain format=tree select (select a.id from cms_activity a where a.club_id = c.id and a.del_flag = 0 and a.status = 1 and a.publish_status = 1 and a.cancel_time is null order by a.start_date desc, a.id desc limit 1) from club c", String.class));
        assertTrue(clubPlan.contains("idx_cms_activity_club_start"), clubPlan);
        String fogPlan = String.join("\n", db.queryForList("explain format=tree select f.geohash7 from roam_fog_tile f where f.member_id = " + (BASE + 1) + " and (f.geohash7 like concat('wtmkq', '%') or f.geohash7 like concat('wtmkr', '%'))", String.class));
        assertTrue(fogPlan.contains("range scan") && fogPlan.contains("uk_member_tile"), fogPlan);
    }

    /**
     * GET /api/roam/pois through the real controller on MySQL, cached and uncached, for guests and members: the 3 km
     * default and a 20 km box over the dense district (more than 500 places: "truncated": true and the 500 kept are
     * the highest node level, then nearest).
     */
    @Test
    void controllerAnswersAreIdenticalCachedAndUncached() throws Exception {
        RoamMapper mapper = sessions.getMapper(RoamMapper.class);
        ReadCache cache = new ReadCache(new InMemoryReadCacheStore(), new ReadCache.Settings(true, Map.of()));
        MapPoiTileCache tiles = new MapPoiTileCache(mapper, cache, 64, 3000);
        boolean truncated = false;
        for (long viewer : new long[]{0L, BASE + 1, BASE + 2}) {
            for (double[] p : new double[][]{{CENTER_LAT, CENTER_LNG}, {CENTER_LAT + 0.02, CENTER_LNG - 0.03}}) {
                for (Integer r : new Integer[]{null, 1200, 20000}) {
                    var plain = com.chengyinhub.web.controller.api.RoamPoisReadCacheParityTest.controller(mapper, viewer, null);
                    var cached = com.chengyinhub.web.controller.api.RoamPoisReadCacheParityTest.controller(mapper, viewer, tiles);
                    String expected = com.chengyinhub.web.controller.api.RoamPoisReadCacheParityTest.pois(plain, p[0], p[1], r);
                    String where = "viewer " + viewer + " r=" + r;
                    assertEquals(expected, com.chengyinhub.web.controller.api.RoamPoisReadCacheParityTest.pois(cached, p[0], p[1], r), "cold " + where);
                    assertEquals(expected, com.chengyinhub.web.controller.api.RoamPoisReadCacheParityTest.pois(cached, p[0], p[1], r), "warm " + where);
                    if (expected.contains("\"truncated\":true")) truncated = true;
                    if (viewer == 0L) assertTrue(!expected.contains("\"found\"") && !expected.contains("\"pendingRedeem\""), where);
                }
            }
        }
        assertTrue(truncated, "a 20 km box over 4,000 places must be truncated at 500");
        assertTrue(cache.hits() > 0);
    }

    /**
     * The tile cache ranks in Java. Java's cos / pow and MySQL's libm can differ in the last bit (measured here on
     * MySQL 8.0.46 / glibc), so the distance is required to agree within a few ULP, far below the near-tie margin
     * (1e-12 relative) under which the tile cache hands the request back to MySQL.
     */
    @Test
    void javaDistanceAgreesWithMySqlWithinTheNearTieMargin() {
        Random rnd = new Random(3);
        long compared = 0, exact = 0, maxUlps = 0;
        for (int i = 0; i < 60; i++) {
            double[] b = MapBoxReadParityTest.box(CENTER_LAT - 0.03 + rnd.nextDouble() * 0.06, CENTER_LNG - 0.035 + rnd.nextDouble() * 0.07, 200 + rnd.nextInt(5000));
            BigDecimal s = BigDecimal.valueOf(b[0]), n = BigDecimal.valueOf(b[1]), w = BigDecimal.valueOf(b[2]), e = BigDecimal.valueOf(b[3]);
            BigDecimal cLat = s.add(n).divide(BigDecimal.valueOf(2)), cLng = w.add(e).divide(BigDecimal.valueOf(2));
            double k = Math.cos(Math.toRadians(cLat.doubleValue()));
            // The production statement's own near_dist, with the box bound the way MyBatis binds it (double parameters).
            String q = "select id, lat, lng, power(lat - (? + ?) / 2, 2) + power((lng - (? + ?) / 2) * cos(radians((? + ?) / 2)), 2) as d from roam_poi";
            for (Map<String, Object> row : db.queryForList(q, b[0], b[1], b[2], b[3], b[0], b[1])) {
                RoamPoi p = new RoamPoi();
                p.setLat((BigDecimal) row.get("lat"));
                p.setLng((BigDecimal) row.get("lng"));
                double mysql = ((Number) row.get("d")).doubleValue();
                double java = MapPoiTileCache.squaredDistance(p, cLat, cLng, k);
                long ulps = Math.abs(Double.doubleToLongBits(mysql) - Double.doubleToLongBits(java));
                assertTrue(ulps <= 8, "place " + row.get("id") + " box " + i + ": mysql " + mysql + " java " + java);
                assertTrue(Math.abs(mysql - java) <= MapPoiTileCache.NEAR_TIE * Math.abs(mysql) / 1000, "margin");
                if (ulps == 0) exact++;
                maxUlps = Math.max(maxUlps, ulps);
                compared++;
            }
        }
        System.out.println("MAPDIST compared " + compared + " bit-identical " + exact + " max ulps " + maxUlps);
        assertTrue(compared >= 60 * 4000);
    }
}
