package com.chengyinhub.business.readcache;

import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.domain.vo.CityNodeVO;
import com.chengyinhub.business.mapper.ClubOpsMapper;
import com.chengyinhub.business.mapper.MarketplaceMapper;
import com.chengyinhub.business.mapper.RoamFogCellMapper;
import com.chengyinhub.business.mapper.RoamMapper;
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
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * T2 maps-backend (BE-30) read benchmark at the data layer, opt-in, never part of the normal suite.
 *
 * <p>Runs against an already seeded schema (see ci/perf/maps/README.md):
 * CHENGYIN_MYBATIS_IT_PORT / _USER / _PASSWORD, CHENGYIN_MAPS_BENCH_SCHEMA (the seeded schema),
 * optional CHENGYIN_REDIS_IT_PORT (loopback Redis for the tile cache; without it the cache runs in memory),
 * CHENGYIN_MAPS_BENCH_THREADS (default 16), CHENGYIN_MAPS_BENCH_SECONDS (default 10 per scenario).
 * It prints one line per scenario: requests, requests per second, p50 / p95 / p99 in milliseconds. No assertion on
 * speed: the numbers depend on the machine and are recorded in the receipt, not used as a gate.
 */
class MapReadLoadMySqlIT {
    private static final double CENTER_LAT = 30.2590, CENTER_LNG = 120.1500;
    private static final long BASE = 9_000_000L;

    @Test
    void benchmarkMapReads() throws Exception {
        String url = "jdbc:mysql://127.0.0.1:" + env("CHENGYIN_MYBATIS_IT_PORT", null) + "/" + env("CHENGYIN_MAPS_BENCH_SCHEMA", null)
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=LOCAL";
        int threads = Integer.parseInt(env("CHENGYIN_MAPS_BENCH_THREADS", "16"));
        int seconds = Integer.parseInt(env("CHENGYIN_MAPS_BENCH_SECONDS", "10"));
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("RoamPoi", RoamPoi.class);
        configuration.getTypeAliasRegistry().registerAlias("RoamSession", RoamSession.class);
        configuration.getTypeAliasRegistry().registerAlias("CityNodeVO", CityNodeVO.class);
        PooledDataSource pool = new PooledDataSource("com.mysql.cj.jdbc.Driver", url,
                env("CHENGYIN_MYBATIS_IT_USER", null), env("CHENGYIN_MYBATIS_IT_PASSWORD", null));
        pool.setPoolMaximumActiveConnections(threads + 4);
        pool.setPoolMaximumIdleConnections(threads + 4);
        configuration.setEnvironment(new Environment("bench", new JdbcTransactionFactory(), pool));
        for (String resource : List.of("mapper/business/RoamMapper.xml", "mapper/business/RoamFogCellMapper.xml",
                "mapper/business/ClubOpsMapper.xml", "mapper/business/MarketplaceMapper.xml", "mapper/legacy/LegacyMapReadsMapper.xml")) {
            try (InputStream in = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        SqlSessionManager sessions = SqlSessionManager.newInstance(factory);

        ReadCacheStore store;
        LettuceConnectionFactory redis = null;
        String redisPort = System.getenv("CHENGYIN_REDIS_IT_PORT");
        if (redisPort != null && !redisPort.isBlank()) {
            redis = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", Integer.parseInt(redisPort)));
            redis.afterPropertiesSet();
            redis.start();
            StringRedisTemplate template = new StringRedisTemplate(redis);
            template.afterPropertiesSet();
            store = new RedisReadCacheStore(template);
        } else {
            store = new InMemoryReadCacheStore();
        }
        ReadCache cache = new ReadCache(store, new ReadCache.Settings(true, Map.of()));
        cache.invalidate(ReadCacheNamespace.POI); // start from a fresh version
        MapPoiTileCache tiles = new MapPoiTileCache(sessions.getMapper(RoamMapper.class), cache, 64, 3000);
        RoamMapper roam = sessions.getMapper(RoamMapper.class);
        RoamFogCellMapper fog = sessions.getMapper(RoamFogCellMapper.class);
        ClubOpsMapper clubs = sessions.getMapper(ClubOpsMapper.class);
        MarketplaceMapper deals = sessions.getMapper(MarketplaceMapper.class);
        System.out.println("MAPBENCH store=" + (redis != null ? "redis" : "memory") + " threads=" + threads + " seconds=" + seconds
                + " places=" + count(factory));

        run("pois legacy SQL (3 km)", threads, seconds, r -> {
            double[] b = box(r);
            try (SqlSession s = factory.openSession(true)) {
                Map<String, Object> p = new HashMap<>();
                p.put("minLat", b[0]); p.put("maxLat", b[1]); p.put("minLng", b[2]); p.put("maxLng", b[3]);
                p.put("memberId", member(r)); p.put("limit", 500);
                s.selectList("legacy.MapReads.selectPoisNearFiltered", p);
            }
        });
        run("pois two-phase SQL (3 km)", threads, seconds, r -> {
            double[] b = box(r);
            roam.selectPoisNear(b[0], b[1], b[2], b[3], member(r), 500);
        });
        run("pois tile cache (3 km)", threads, seconds, r -> {
            double[] b = box(r);
            tiles.near(b[0], b[1], b[2], b[3], member(r), 500);
        });
        run("fog kept cells (member, ~3 km)", threads, seconds, r -> {
            double lat = CENTER_LAT + (r.nextDouble() - 0.5) * 0.04, lng = CENTER_LNG + (r.nextDouble() - 0.5) * 0.05;
            List<String> prefixes = new ArrayList<>(com.chengyinhub.business.support.RoamGeohashCover.prefixes(lat - 0.013, lng - 0.016, lat + 0.013, lng + 0.016));
            fog.selectRetainedCellsByPrefixes(member(r) == 0L ? BASE : member(r), prefixes, 20001);
        });
        run("clubs nearby (box)", threads, seconds, r -> {
            double[] b = box(r);
            clubs.selectLocatedClubs(BigDecimal.valueOf(b[0]), BigDecimal.valueOf(b[1]), BigDecimal.valueOf(b[2]), BigDecimal.valueOf(b[3]), 100);
        });
        run("deals (box)", threads, seconds, r -> {
            double[] b = box(r);
            deals.selectDealsInBox(b[0], b[1], b[2], b[3], member(r), 100);
        });
        System.out.println("MAPBENCH cache hits=" + cache.hits() + " misses=" + cache.misses() + " errors=" + cache.errors());
        if (redis != null) redis.destroy();
        pool.forceCloseAll();
    }

    private static long member(Random r) {
        return r.nextInt(3) == 0 ? 0L : BASE + r.nextInt(50);
    }

    private static double[] box(Random r) {
        double lat = CENTER_LAT + (r.nextDouble() - 0.5) * 0.05, lng = CENTER_LNG + (r.nextDouble() - 0.5) * 0.06;
        return MapBoxReadParityTest.box(lat, lng, 3000);
    }

    private static int count(SqlSessionFactory factory) throws Exception {
        try (SqlSession s = factory.openSession(true);
             var rs = s.getConnection().createStatement().executeQuery("select count(*) from roam_poi")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void run(String name, int threads, int seconds, Consumer<Random> call) throws Exception {
        for (int i = 0; i < 20; i++) call.accept(new Random(i)); // warm-up
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        List<Future<long[]>> parts = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            parts.add(pool.submit(() -> {
                Random r = new Random(ThreadLocalRandom.current().nextLong());
                long[] lat = new long[1 << 16];
                int n = 0;
                while (System.nanoTime() < end) {
                    long start = System.nanoTime();
                    call.accept(r);
                    if (n == lat.length) lat = Arrays.copyOf(lat, n * 2);
                    lat[n++] = System.nanoTime() - start;
                }
                return Arrays.copyOf(lat, n);
            }));
        }
        List<Long> all = new ArrayList<>();
        for (Future<long[]> f : parts) for (long v : f.get()) all.add(v);
        pool.shutdown();
        all.sort(Long::compare);
        int n = all.size();
        System.out.printf("MAPBENCH %-32s n=%7d rps=%8.1f p50=%7.2fms p95=%7.2fms p99=%7.2fms%n", name, n, n / (double) seconds,
                all.get(n / 2) / 1e6, all.get((int) (n * 0.95)) / 1e6, all.get(Math.min(n - 1, (int) (n * 0.99))) / 1e6);
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            if (fallback == null) throw new IllegalStateException(name + " is required for this benchmark");
            return fallback;
        }
        return v;
    }
}
