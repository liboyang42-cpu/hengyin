package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.ContentLandingMetadata;
import com.chengyinhub.business.mapper.ContentLandingMetadataMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M7 落地元信息的**行版本 CAS** 在真实 InnoDB 上的边界（审校版本 / 摘要并发）。
 *
 * <p>译文审校的并发已由 {@code ContentLandingReviewConcurrencyMySqlIT} 覆盖；这里补的是同一
 * 自然键的元信息写面：作者保存带 expectedVersion，真正决定"能不能覆盖"的是
 * {@code where topic_id=? and row_version=?} 和唯一键，而不是先读再写的 Java 判断。
 *
 * <p>覆盖的负例：
 * <ul>
 *   <li>同版本的两次并发保存只有一个成功，另一个 0 行（不能各自覆盖）；</li>
 *   <li>旧版本保存不得覆盖已推进的行；</li>
 *   <li>同一主题并发插入元信息只能成功一行，其余撞唯一键。</li>
 * </ul>
 *
 * <p>一类一库，跑完 DROP DATABASE 并回读不存在。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_MARKETING_DB_IT_APPROVED", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContentLandingMetadataVersionMySqlIT {

    private final String suite = "m7landing_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    private static final long TOPIC_ID = 5201L;

    private DriverManagerDataSource source;
    private JdbcTemplate db;
    private TransactionTemplate tx;
    private ContentLandingMetadataMapper mapper;

    @BeforeAll
    void schemaAndRealMapper() throws Exception {
        assertTrue(List.of("127.0.0.1", "localhost").contains(MybatisItDatabase.host()),
                "只许连一次性 scratch MySQL");
        source = P0AcceptanceSchema.dataSource(suite);
        db = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        assertEquals(MybatisItDatabase.databaseName(suite),
                db.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(com.chengyinhub.business.service.MysqlIntegrationTestConfig.isMysql8OrNewer(
                db.queryForObject("SELECT VERSION()", String.class)), "需要 Oracle MySQL >= 8");

        P0AcceptanceSchema.migrations(source, "migration_public_content_catalog_20261108.sql");
        mapper = P0AcceptanceSchema.sql(source, "ContentLandingMetadataMapper")
                .getMapper(ContentLandingMetadataMapper.class);
    }

    @AfterAll
    void cleanup() {
        if (db != null) {
            assertTrue(MybatisItDatabase.databaseName(suite).matches("chengyin_mybatis_m7landing_[a-z0-9_]+"));
            P0AcceptanceSchema.drop(db, suite);
        }
    }

    @BeforeEach
    void clearMetadata() {
        db.update("DELETE FROM content_landing_translation");
        db.update("DELETE FROM content_landing_metadata");
    }

    // ------------------------------------------------------------------ 并发 CAS

    @Test
    void concurrentSaveWithSameVersionOnlyOneWins() throws Exception {
        insertMetadata(TOPIC_ID, "initial");

        List<Long> results = runConcurrently(2, index -> tx.execute(status -> {
            ContentLandingMetadata row = metadata(TOPIC_ID, "summary-" + index);
            return (long) mapper.updateMetadataVersioned(row, 1L, new Date());
        }));

        assertEquals(1L, results.stream().filter(value -> value == 1L).count(),
                "同一 expectedVersion 的并发保存只能成功一次，实际=" + results);
        ContentLandingMetadata after = mapper.selectByTopicId(TOPIC_ID);
        assertEquals(2L, after.getRowVersion().longValue(), "版本只推进一次");
        assertTrue(after.getLandingSummary().startsWith("summary-"),
                "最终内容必须来自赢家，而不是初始值");
        int winner = results.indexOf(1L);
        assertEquals("summary-" + winner, after.getLandingSummary(),
                "落库内容必须与返回 1 行的那个线程一致");
    }

    @Test
    void staleSaveCannotOverwriteANewerVersion() {
        insertMetadata(TOPIC_ID, "initial");
        assertEquals(1, mapper.updateMetadataVersioned(metadata(TOPIC_ID, "approved"), 1L, new Date()));

        assertEquals(0, mapper.updateMetadataVersioned(metadata(TOPIC_ID, "stale-overwrite"), 1L, new Date()),
                "旧版本保存必须 0 行，不能条件失效后照样覆盖");
        ContentLandingMetadata after = mapper.selectByTopicId(TOPIC_ID);
        assertEquals("approved", after.getLandingSummary());
        assertEquals(2L, after.getRowVersion().longValue());
    }

    @Test
    void concurrentInsertForTheSameTopicKeepsOneRow() throws Exception {
        List<Long> results = runConcurrently(4, index -> tx.execute(status -> {
            try {
                insertMetadata(TOPIC_ID, "insert-" + index);
                return 1L;
            } catch (DataIntegrityViolationException duplicate) {
                return 0L;
            }
        }));

        assertEquals(1L, results.stream().filter(value -> value == 1L).count(),
                "uk_content_landing_metadata_topic 必须只放行一行");
        assertEquals(1, db.queryForObject(
                "SELECT COUNT(*) FROM content_landing_metadata WHERE topic_id=?", Integer.class, TOPIC_ID));
        assertNotNull(mapper.selectByTopicId(TOPIC_ID));
    }

    // ------------------------------------------------------------------ fixtures

    private void insertMetadata(long topicId, String summary) {
        ContentLandingMetadata row = metadata(topicId, summary);
        mapper.insertMetadata(row);
    }

    private ContentLandingMetadata metadata(long topicId, String summary) {
        ContentLandingMetadata row = new ContentLandingMetadata();
        row.setTopicId(topicId);
        row.setSourceLocale("zh-CN");
        row.setLandingSummary(summary);
        row.setGroupEligible(1);
        row.setSchoolEligible(0);
        row.setGroupMinPeople(8);
        row.setGroupMaxPeople(30);
        row.setAgeMin(8);
        row.setAgeMax(14);
        row.setAccessibilityNote("step-free");
        row.setStatus(1);
        row.setUpdateBy(900L);
        row.setCreateTime(new Date());
        row.setUpdateTime(new Date());
        return row;
    }

    private interface ConcurrentCall {
        long run(int index) throws Exception;
    }

    private List<Long> runConcurrently(int threads, ConcurrentCall call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    assertTrue(start.await(20, TimeUnit.SECONDS));
                    return call.run(index);
                }));
            }
            start.countDown();
            List<Long> results = new ArrayList<>();
            for (Future<Long> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        }
    }
}
