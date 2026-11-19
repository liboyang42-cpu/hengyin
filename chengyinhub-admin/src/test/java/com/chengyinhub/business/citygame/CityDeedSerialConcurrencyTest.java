package com.chengyinhub.business.citygame;

import com.chengyinhub.business.mapper.CityGameMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * CITY-GAPS-R3 item 3: deed serials are unique per district under concurrent first claims. Runs the production
 * {@link CityMonopolyService#issueDeedSerial} SQL on H2 (MySQL mode, row locks) with real concurrent transactions.
 */
class CityDeedSerialConcurrencyTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private CityMonopolyService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:deed_" + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE city_deed_counter (board_id varchar(96) NOT NULL, district_id varchar(64) NOT NULL, "
                + "last_serial int NOT NULL DEFAULT 0, PRIMARY KEY (board_id, district_id))");
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        service = new CityMonopolyService(jdbc, mock(CityGameMapper.class), CityMonopolyConfigService.fixed(CityMonopolyConfig.defaults()),
                null, System::currentTimeMillis, faces -> 1);
    }

    @Test
    void concurrentFirstClaimsInOneDistrictGetDistinctConsecutiveSerials() throws Exception {
        int claims = 12;
        ExecutorService pool = Executors.newFixedThreadPool(claims);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < claims; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return tx.execute(s -> {
                    Integer serial = service.issueDeedSerial("B", "D1");
                    try { Thread.sleep(15); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return serial;
                });
            }));
        }
        start.countDown();
        List<Integer> serials = new ArrayList<>();
        for (Future<Integer> f : futures) serials.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        Collections.sort(serials);
        List<Integer> expected = new ArrayList<>();
        for (int i = 1; i <= claims; i++) expected.add(i);
        assertEquals(expected, serials, "every first claim gets its own number, no gaps, no duplicates");
        assertEquals(claims, jdbc.queryForObject("SELECT last_serial FROM city_deed_counter WHERE board_id = 'B' AND district_id = 'D1'", Integer.class));
    }

    @Test
    void districtsCountIndependentlyAndARolledBackClaimReturnsItsNumber() {
        assertEquals(Integer.valueOf(1), tx.<Integer>execute(s -> service.issueDeedSerial("B", "D1")));
        assertEquals(Integer.valueOf(1), tx.<Integer>execute(s -> service.issueDeedSerial("B", "D2")));
        assertEquals(Integer.valueOf(1), tx.<Integer>execute(s -> service.issueDeedSerial("B2", "D1")));
        tx.execute(s -> {
            assertEquals(2, service.issueDeedSerial("B", "D1"));
            s.setRollbackOnly(); // e.g. INSUFFICIENT_POINTS after the serial was drawn
            return null;
        });
        assertEquals(Integer.valueOf(2), tx.<Integer>execute(s -> service.issueDeedSerial("B", "D1")), "the rolled-back number is issued again");
        Set<Integer> d1 = new TreeSet<>(jdbc.queryForList("SELECT last_serial FROM city_deed_counter WHERE district_id = 'D1'", Integer.class));
        assertEquals(Set.of(1, 2), d1);
    }

    @Test
    void migrationIsGuardedAdditiveAndKeepsLiveDeedsUnique() throws Exception {
        Path p = Paths.get("sql/migration_20261126_city_gaps_r3.sql");
        if (!Files.exists(p)) p = Paths.get("chengyinhub-admin/sql/migration_20261126_city_gaps_r3.sql");
        String sql = Files.readString(p, StandardCharsets.UTF_8);
        assertTrue(sql.contains("column_name='deed_serial'"), "ADD COLUMN is guarded by information_schema");
        assertTrue(sql.contains("GENERATED ALWAYS AS (IF(status = ''ACTIVE'', deed_serial, NULL))"));
        assertTrue(sql.contains("ADD UNIQUE KEY uk_roam_territory_deed (board_id, district_id, deed_serial_live)"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `city_deed_counter`"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `city_onsite_task_submission`"));
        assertTrue(sql.contains("UNIQUE KEY `uk_city_onsite_task_member_ref` (`member_id`, `task_ref`)"));
        assertTrue(sql.contains("INSERT IGNORE INTO city_district"), "district backfill never overwrites names set by operations");
        assertTrue(sql.contains("-- Rollback"));
        String upper = sql.toUpperCase();
        for (String forbidden : List.of("DROP TABLE CITY", "DROP COLUMN", "TRUNCATE", "UMS_MEMBER_POINTS", "LEDGER")) {
            String executable = String.join("\n", upper.lines().filter(l -> !l.trim().startsWith("--")).toList());
            assertFalse(executable.contains(forbidden), forbidden);
        }
    }
}
