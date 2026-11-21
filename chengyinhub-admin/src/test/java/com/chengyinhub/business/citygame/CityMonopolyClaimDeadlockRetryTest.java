package com.chengyinhub.business.citygame;

import com.chengyinhub.business.mapper.CityGameMapper;
import com.chengyinhub.business.service.impl.RoamLocationConsentService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.framework.aspectj.RateLimiterAspect;
import com.chengyinhub.framework.config.DeadlockRetryConfig;
import com.chengyinhub.web.controller.api.ApiCityMonopolyController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies bounded deadlock/lock-wait retry (3 attempts) around the transactional City Monopoly first-claim path,
 * including rollback of partial writes before retry, rejection of non-lock exceptions, and single RateLimiter counting
 * on the controller boundary.
 */
class CityMonopolyClaimDeadlockRetryTest {

    private static final long NOW = ZonedDateTime.of(2026, 11, 16, 12, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
            .toInstant().toEpochMilli();
    private static final double LAT = 23.129112;
    private static final double LNG = 113.264385;
    private static final long MEMBER_ID = 7L;

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate rawJdbc;
    private FaultyCounterJdbc counterJdbc;
    private CityMonopolyService service;
    private ApiCityMonopolyController controller;
    private RoamLocationConsentService consentService;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(Wiring.class);
        DataSource ds = context.getBean(DataSource.class);
        rawJdbc = new JdbcTemplate(ds);
        counterJdbc = context.getBean(FaultyCounterJdbc.class);
        service = context.getBean(CityMonopolyService.class);
        controller = context.getBean(ApiCityMonopolyController.class);
        consentService = context.getBean(RoamLocationConsentService.class);
        createSchema(rawJdbc);
        seedPointAndMember(rawJdbc);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        context.close();
    }

    @Test
    void serviceClaimCommandRetriesDeadlockTwiceAndSucceedsOnThirdAttempt() {
        Map<String, Object> quote = service.claimQuote(MEMBER_ID, Map.of("boardId", "B", "pointId", "P1"));
        counterJdbc.failTimes(2, () -> new DeadlockLoserDataAccessException("Deadlock found when trying to get lock", null));

        Map<String, Object> out = service.claimCommand(MEMBER_ID, claimBody(quote));

        assertEquals("CLAIMED", out.get("outcome"), String.valueOf(out));
        assertEquals(3, counterJdbc.attempts(), "two deadlocks + one successful third attempt");
        @SuppressWarnings("unchecked")
        Map<String, Object> prop = (Map<String, Object>) out.get("property");
        assertEquals(1, prop.get("deedSerial"));
        assertEquals(1, count("SELECT COUNT(*) FROM roam_territory"));
        assertEquals(1, count("SELECT COUNT(*) FROM city_claim_evidence"));
        assertEquals(1, count("SELECT COUNT(*) FROM city_points_flow"));
        assertEquals(1, count("SELECT last_serial FROM city_deed_counter WHERE board_id = 'B' AND district_id = 'D1'"));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void serviceClaimCommandRetriesLockWaitTimeout() {
        Map<String, Object> quote = service.claimQuote(MEMBER_ID, Map.of("boardId", "B", "pointId", "P1"));
        counterJdbc.failTimes(1, () -> new CannotAcquireLockException("Lock wait timeout exceeded"));

        Map<String, Object> out = service.claimCommand(MEMBER_ID, claimBody(quote));

        assertEquals("CLAIMED", out.get("outcome"), String.valueOf(out));
        assertEquals(2, counterJdbc.attempts());
        assertEquals(1, count("SELECT COUNT(*) FROM roam_territory"));
    }

    @Test
    void serviceClaimCommandGivesUpAfterThreeAttemptsAndLeavesNothingCommitted() {
        Map<String, Object> quote = service.claimQuote(MEMBER_ID, Map.of("boardId", "B", "pointId", "P1"));
        counterJdbc.failTimes(5, () -> new DeadlockLoserDataAccessException("persistent deadlock", null));

        assertThrows(DeadlockLoserDataAccessException.class,
                () -> service.claimCommand(MEMBER_ID, claimBody(quote)));

        assertEquals(3, counterJdbc.attempts(), "bounded to 3 attempts");
        assertEquals(0, count("SELECT COUNT(*) FROM roam_territory"));
        assertEquals(0, count("SELECT COUNT(*) FROM city_claim_evidence"));
        assertEquals(0, count("SELECT COUNT(*) FROM city_points_flow"));
        assertEquals(0, count("SELECT COUNT(*) FROM city_deed_counter"));
        assertEquals(0, count("SELECT COUNT(*) FROM city_monopoly_quote WHERE used_at IS NOT NULL"));
    }

    @Test
    void nonLockExceptionIsNotRetried() {
        Map<String, Object> quote = service.claimQuote(MEMBER_ID, Map.of("boardId", "B", "pointId", "P1"));
        counterJdbc.failTimes(3, () -> new DuplicateKeyException("duplicate"));

        assertThrows(DuplicateKeyException.class,
                () -> service.claimCommand(MEMBER_ID, claimBody(quote)));

        assertEquals(1, counterJdbc.attempts(), "non-lock exception must not retry");
        assertEquals(0, count("SELECT COUNT(*) FROM roam_territory"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void controllerClaimCommandRetriesOutsideControllerTransactionAndCountsRateLimitOnce() {
        Map<String, Object> quote = service.claimQuote(MEMBER_ID, Map.of("boardId", "B", "pointId", "P1"));
        counterJdbc.failTimes(2, () -> new CannotAcquireLockException("1205"));

        AjaxResult result = controller.claimCommand(claimBody(quote));

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        assertEquals("CLAIMED", data.get("outcome"));
        assertEquals(3, counterJdbc.attempts());
        verify(consentService, times(3)).requireCurrentAgreement(MEMBER_ID);
        RedisTemplate<Object, Object> redis = context.getBean(RedisTemplate.class);
        verify(redis, times(1)).execute(any(RedisScript.class), anyList(), any(), any());
        assertEquals(1, count("SELECT COUNT(*) FROM roam_territory"));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    private int count(String sql) {
        Integer n = rawJdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }

    private static Map<String, Object> claimBody(Map<String, Object> quote) {
        return Map.of(
                "requestId", "REQ-CLAIM-RETRY-1",
                "quoteId", quote.get("quoteId"),
                "quoteHash", quote.get("quoteHash"),
                "evidence", Map.ofEntries(
                        Map.entry("lat", LAT),
                        Map.entry("lng", LNG),
                        Map.entry("hAcc", 5.0),
                        Map.entry("inAppCamera", true),
                        Map.entry("capturedAt", NOW - 10_000L),
                        Map.entry("captureLat", LAT),
                        Map.entry("captureLng", LNG),
                        Map.entry("phash", "0123456789abcdef"),
                        Map.entry("recaptureScore", 0.0),
                        Map.entry("photoAssetId", "/upload/deed.jpg"),
                        Map.entry("samples", List.of(
                                Map.of("t", NOW - 90_000L, "lat", LAT, "lng", LNG),
                                Map.of("t", NOW - 45_000L, "lat", LAT, "lng", LNG),
                                Map.of("t", NOW, "lat", LAT, "lng", LNG)))));
    }

    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE ums_member (id BIGINT PRIMARY KEY, create_time DATETIME(3), phone VARCHAR(32), union_id VARCHAR(64), del_flag INT)");
        jdbc.execute("CREATE TABLE member_growth (member_id BIGINT PRIMARY KEY, level_no INT)");
        jdbc.execute("CREATE TABLE city_abuse_signal (id BIGINT AUTO_INCREMENT PRIMARY KEY, member_id BIGINT, action VARCHAR(16), status VARCHAR(12), window_to DATETIME(3))");
        jdbc.execute("CREATE TABLE city_game_point (board_id VARCHAR(96), point_id VARCHAR(96), title VARCHAR(160), latitude DOUBLE, longitude DOUBLE, tier INT, district_id VARCHAR(64), merchant_id BIGINT, place_id BIGINT, del_flag INT)");
        jdbc.execute("CREATE TABLE city_game_property (board_id VARCHAR(96), property_id VARCHAR(96), point_id VARCHAR(96))");
        jdbc.execute("CREATE TABLE city_district (board_id VARCHAR(96), district_id VARCHAR(64), name VARCHAR(96), color_token VARCHAR(64), ownable_count INT, safety_blocked INT)");
        jdbc.execute("CREATE TABLE roam_fog_tile (member_id BIGINT, geohash7 VARCHAR(16))");
        jdbc.execute("CREATE TABLE city_monopoly_quote (quote_id CHAR(36) PRIMARY KEY, member_id BIGINT, kind VARCHAR(16), target_key VARCHAR(160), cost BIGINT, payload_json VARCHAR(4000), rules_hash CHAR(64), quote_hash CHAR(64), expires_at DATETIME(3), used_at DATETIME(3))");
        jdbc.execute("CREATE TABLE city_monopoly_receipt (member_id BIGINT, request_id VARCHAR(96), action VARCHAR(24), body_hash CHAR(64), response_json VARCHAR(4000), PRIMARY KEY (member_id, request_id))");
        jdbc.execute("CREATE TABLE city_points_flow (id BIGINT AUTO_INCREMENT PRIMARY KEY, member_id BIGINT, season_id VARCHAR(96), delta BIGINT, state VARCHAR(12), kind VARCHAR(16), ref_type VARCHAR(24), ref_id VARCHAR(96), points_business_key VARCHAR(128), settled_at DATETIME(3), UNIQUE (member_id, kind, ref_type, ref_id))");
        jdbc.execute("CREATE TABLE city_member_season (member_id BIGINT, season_id VARCHAR(96), earned BIGINT DEFAULT 0, spent BIGINT DEFAULT 0, debt BIGINT DEFAULT 0, confirmed_rent BIGINT DEFAULT 0, first_claims INT DEFAULT 0, takeovers INT DEFAULT 0, defenses INT DEFAULT 0, holding_score DOUBLE DEFAULT 0, version BIGINT DEFAULT 1, PRIMARY KEY (member_id, season_id))");
        jdbc.execute("CREATE TABLE city_claim_evidence (id BIGINT AUTO_INCREMENT PRIMARY KEY, board_id VARCHAR(96), target_key VARCHAR(160), territory_id CHAR(36), member_id BIGINT, kind VARCHAR(12), photo_asset_id VARCHAR(500), phash BIGINT, captured_at DATETIME(3), lat DOUBLE, lng DOUBLE, h_acc DOUBLE, sample_count INT, dwell_s INT, max_speed_mps DOUBLE, simulated INT, attest_ok INT, merchant_qr INT, risk_score DOUBLE, verdict VARCHAR(16), reasons VARCHAR(500), created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3))");
        jdbc.execute("CREATE TABLE city_risk_signal (id BIGINT AUTO_INCREMENT PRIMARY KEY, member_id BIGINT, kind VARCHAR(32), score DOUBLE, ref_id VARCHAR(96), detail_json VARCHAR(4000), action VARCHAR(16))");
        jdbc.execute("CREATE TABLE city_season (season_id VARCHAR(96) NOT NULL, region_id VARCHAR(96) NOT NULL DEFAULT 'default', starts_at DATETIME(3) NOT NULL, ends_at DATETIME(3) NOT NULL, phase VARCHAR(12) NOT NULL DEFAULT 'OPENING', status VARCHAR(12) NOT NULL DEFAULT 'OPEN', config_hash CHAR(64), carry_rate DECIMAL(4,2) NOT NULL DEFAULT 0.20, carry_cap INT NOT NULL DEFAULT 300, settled_at DATETIME(3), PRIMARY KEY (season_id, region_id))");
        jdbc.execute("CREATE TABLE city_deed_counter (board_id VARCHAR(96) NOT NULL, district_id VARCHAR(64) NOT NULL, last_serial INT NOT NULL DEFAULT 0, PRIMARY KEY (board_id, district_id))");
        jdbc.execute("CREATE TABLE roam_territory (territory_id CHAR(36) PRIMARY KEY, geohash7 VARCHAR(16), poi_id BIGINT, board_id VARCHAR(96), season_id VARCHAR(96), property_id VARCHAR(96), point_id VARCHAR(96), title VARCHAR(160), city_code VARCHAR(32), latitude DOUBLE, longitude DOUBLE, radius_m BIGINT, owner_member_id BIGINT, claim_photo_url VARCHAR(500), claim_request_id VARCHAR(96), checkin_toll_points BIGINT, owner_revenue_points BIGINT, revenue_mode VARCHAR(16), checkin_count INT, revenue_total_points BIGINT, status VARCHAR(16), version INT, claimed_at DATETIME(3), released_at DATETIME(3), protected_until DATETIME(3), rules_kind VARCHAR(16), tier INT, upgrade_level INT, invested BIGINT, district_id VARCHAR(64), last_owner_visit_at DATETIME(3), decay_state VARCHAR(16), claim_evidence_id BIGINT, deed_serial INT)");
    }

    private static void seedPointAndMember(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO ums_member(id, create_time, phone, union_id, del_flag) VALUES (?,?,?,?,0)",
                MEMBER_ID, new Timestamp(NOW - 200L * 86_400_000L), "13800138000", null);
        jdbc.update("INSERT INTO member_growth(member_id, level_no) VALUES (?,?)", MEMBER_ID, 20);
        jdbc.update("INSERT INTO city_game_point(board_id, point_id, title, latitude, longitude, tier, district_id, merchant_id, place_id, del_flag) VALUES (?,?,?,?,?,?,?,?,?,0)",
                "B", "P1", "骑楼口", LAT, LNG, 1, "D1", null, null);
        jdbc.update("INSERT INTO city_district(board_id, district_id, name, color_token, ownable_count, safety_blocked) VALUES (?,?,?,?,?,0)",
                "B", "D1", "老城街区", "district.c1", 3);
    }

    static class FaultyCounterJdbc extends JdbcTemplate {
        private final AtomicInteger remainingFailures = new AtomicInteger();
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicReference<java.util.function.Supplier<RuntimeException>> failure = new AtomicReference<>();

        FaultyCounterJdbc(DataSource dataSource) {
            super(dataSource);
        }

        void failTimes(int times, java.util.function.Supplier<RuntimeException> supplier) {
            attempts.set(0);
            remainingFailures.set(times);
            failure.set(supplier);
        }

        int attempts() {
            return attempts.get();
        }

        private static String h2Sql(String sql) {
            return sql == null ? null : sql.replace("<=>", "IS NOT DISTINCT FROM");
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            return super.queryForObject(h2Sql(sql), requiredType, args);
        }

        @Override
        public java.util.List<Map<String, Object>> queryForList(String sql, Object... args) {
            return super.queryForList(h2Sql(sql), args);
        }

        @Override
        public int update(String sql, Object... args) {
            if (sql != null && sql.contains("INSERT INTO city_deed_counter")) {
                attempts.incrementAndGet();
                if (remainingFailures.getAndDecrement() > 0) {
                    throw failure.get().get();
                }
            }
            return super.update(h2Sql(sql), args);
        }
    }

    @Configuration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({DeadlockRetryConfig.class, RateLimiterAspect.class})
    static class Wiring {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:claim_retry_" + UUID.randomUUID().toString().replace("-", "")
                            + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
                    "sa", "");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        FaultyCounterJdbc jdbcTemplate(DataSource dataSource) {
            return new FaultyCounterJdbc(dataSource);
        }

        @Bean
        CityGameMapper cityGameMapper() {
            CityGameMapper mapper = mock(CityGameMapper.class);
            when(mapper.selectMemberPoints(anyLong())).thenReturn(1_000L);
            when(mapper.deductMemberPoints(anyLong(), anyLong())).thenReturn(1);
            return mapper;
        }

        @Bean
        CityMonopolyService cityMonopolyService(FaultyCounterJdbc jdbc, CityGameMapper mapper) {
            CityMonopolyConfig cfg = CityMonopolyConfig.withOverrides(Map.of(
                    "city.monopoly.claim.require_app_attest", "false"));
            return new CityMonopolyService(jdbc, mapper, CityMonopolyConfigService.fixed(cfg),
                    null, () -> NOW, faces -> 20);
        }

        @Bean
        RoamLocationConsentService roamLocationConsentService() {
            RoamLocationConsentService consent = mock(RoamLocationConsentService.class);
            doAnswer(inv -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive(),
                        "consent check must run inside the active outer transaction");
                return null;
            }).when(consent).requireCurrentAgreement(anyLong());
            return consent;
        }

        @Bean
        ApiCityMonopolyController apiCityMonopolyController(CityMonopolyService service,
                                                            RoamLocationConsentService consent) {
            return new ApiCityMonopolyController(service, consent) {
                @Override
                public Long getAppUserId() {
                    return MEMBER_ID;
                }
            };
        }

        @Bean
        @SuppressWarnings("unchecked")
        RedisTemplate<Object, Object> redisTemplate() {
            RedisTemplate<Object, Object> redis = mock(RedisTemplate.class);
            when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);
            return redis;
        }

        @Bean
        @SuppressWarnings("unchecked")
        RedisScript<Long> limitScript() {
            return mock(RedisScript.class);
        }
    }
}
