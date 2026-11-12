package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.VideoBountyAccrual;
import com.chengyinhub.business.exception.BountyTipException;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.VideoBountyAccrualMapper;
import com.chengyinhub.business.mapper.VideoBountyEvidenceMapper;
import com.chengyinhub.business.mapper.VideoBountySubmissionMapper;
import com.chengyinhub.business.mapper.VideoCampaignMapper;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMarketingAttributionService;
import com.chengyinhub.business.service.impl.VideoBountyServiceImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * M6 视频悬赏的「跨平台原视频组封顶」在真实 InnoDB 上的口径。
 *
 * <p>单测能证明计算器会把 120 元封到 100 元，但证明不了两条投稿的应计行同时存在时
 * 组内合计仍然 ≤ 封顶、活动预算锁真的串行化、核验版本 CAS 只有一个赢家。
 *
 * <p>覆盖的负例：
 * <ul>
 *   <li>同一 verified_group_key 的两个平台投稿共享一个 per_video_cap，而不是各领一份；</li>
 *   <li>未核验(verified_group_key=NULL)时即使作者自报 origin_group_key 也拒绝应计；</li>
 *   <li>两个不同原视频组并发应计，总预算上限由活动行锁串行化，不得超发；</li>
 *   <li>核验版本 CAS 并发只有一个成功；</li>
 *   <li>同一证据水位重复重算幂等，只有新证据才产生新行并替代旧行；</li>
 *   <li>ME-03：两条投稿自报 origin 不同但同 verified 组时，水位必须按 verified 组推进
 *       （100k+100k → 50+50；B 涨到 300k → A25+B75=100，其他组不变）。</li>
 * </ul>
 *
 * <p>一类一库，跑完 DROP DATABASE 并回读不存在。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_MARKETING_DB_IT_APPROVED", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VideoBountyGroupCapMySqlIT {

    private final String suite = "m6cap_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    private static final long MEMBER = 6601L;
    private static final long TOPIC = 4242L;

    private DriverManagerDataSource source;
    private JdbcTemplate db;
    private TransactionTemplate tx;
    private VideoCampaignMapper campaignMapper;
    private VideoBountySubmissionMapper submissionMapper;
    private VideoBountyEvidenceMapper evidenceMapper;
    private VideoBountyAccrualMapper accrualMapper;
    private VideoBountyServiceImpl service;

    private long campaignId;
    private String campaignKey;

    @BeforeAll
    void schemaAndRealService() throws Exception {
        assertTrue(List.of("127.0.0.1", "localhost").contains(MybatisItDatabase.host()),
                "只许连一次性 scratch MySQL");
        source = P0AcceptanceSchema.dataSource(suite);
        db = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        assertEquals(MybatisItDatabase.databaseName(suite),
                db.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(com.chengyinhub.business.service.MysqlIntegrationTestConfig.isMysql8OrNewer(
                db.queryForObject("SELECT VERSION()", String.class)), "需要 Oracle MySQL >= 8");

        P0AcceptanceSchema.migrations(source,
                "migration_video_bounty_host_tip_20261108.sql",
                "migration_video_bounty_group_verification_20261108.sql");

        var session = P0AcceptanceSchema.sql(source,
                "VideoCampaignMapper", "VideoBountySubmissionMapper",
                "VideoBountyEvidenceMapper", "VideoBountyAccrualMapper");
        campaignMapper = session.getMapper(VideoCampaignMapper.class);
        submissionMapper = session.getMapper(VideoBountySubmissionMapper.class);
        evidenceMapper = session.getMapper(VideoBountyEvidenceMapper.class);
        accrualMapper = session.getMapper(VideoBountyAccrualMapper.class);

        service = new VideoBountyServiceImpl(campaignMapper, submissionMapper, evidenceMapper, accrualMapper,
                mock(ICmsTopicService.class), mock(IMarketingAttributionService.class),
                mock(UmsMemberMapper.class), mock(BusinessAuditEventMapper.class));
    }

    @AfterAll
    void cleanup() {
        if (db != null) {
            assertTrue(MybatisItDatabase.databaseName(suite).matches("chengyin_mybatis_m6cap_[a-z0-9_]+"));
            P0AcceptanceSchema.drop(db, suite);
        }
    }

    @BeforeEach
    void clearOwnedTables() {
        for (String table : List.of("video_bounty_accrual", "video_bounty_evidence",
                "video_bounty_submission", "video_bounty_campaign")) {
            db.update("DELETE FROM " + table);
        }
    }

    // ------------------------------------------------------------------ 跨平台 share cap

    @Test
    void crossPlatformSubmissionsShareOnePerVideoCap() {
        campaignId = insertCampaign("cap-200", new BigDecimal("2.00"), new BigDecimal("100.00"),
                new BigDecimal("300.00"), 30);
        long youtube = insertApprovedSubmission("youtube", "yt-1", "g-merge", "g-merge");
        long tiktok = insertApprovedSubmission("tiktok", "tt-1", "g-merge", "g-merge");
        insertEvidence(youtube, 30_000L);
        insertEvidence(tiktok, 30_000L);

        service.recomputeAccrual(youtube);
        service.recomputeAccrual(tiktok);

        // 跨平台同组：组内有效应计合计必须恰好用满封顶，而不是每条各领 60 元。
        List<java.util.Map<String, Object>> active = db.queryForList(
                "SELECT payable_amount, group_basis_views FROM video_bounty_accrual"
                        + " WHERE campaign_id=? AND group_key=? AND payout_state IN (0,1,2)",
                campaignId, "g-merge");
        assertEquals(2, active.size(), "整组重算后两条投稿都必须保留有效应计");
        BigDecimal total = BigDecimal.ZERO;
        for (var row : active) {
            total = total.add((BigDecimal) row.get("payable_amount"));
            assertEquals(60_000L, ((Number) row.get("group_basis_views")).longValue(),
                    "组基数必须是两条投稿的播放合计");
        }
        assertEquals(0, new BigDecimal("100.00").compareTo(total),
                "同组有效应计合计必须恰好等于 100 元封顶，实际=" + total);
    }

    // ------------------------------------------------------------------ 未核验不猜

    @Test
    void unverifiedSubmissionCannotAccrueEvenWithDeclaredOriginGroup() {
        campaignId = insertCampaign("cap-unverified", new BigDecimal("2.00"), new BigDecimal("100.00"),
                null, 30);
        long submission = insertApprovedSubmission("youtube", "yt-unverified", "author-declared", null);
        insertEvidence(submission, 10_000L);

        BountyTipException rejected = assertThrows(BountyTipException.class,
                () -> service.recomputeAccrual(submission));
        assertEquals("ACCRUAL_GROUP_UNVERIFIED", rejected.getReasonCode(),
                "作者自报的 origin_group_key 不能替代运营核验");
        assertEquals(0, db.queryForObject(
                "SELECT COUNT(*) FROM video_bounty_accrual", Integer.class));
    }

    // ------------------------------------------------------------------ 预算串行化

    @Test
    void concurrentGroupsCannotExceedTheCampaignBudget() throws Exception {
        campaignId = insertCampaign("cap-budget", new BigDecimal("2.00"), new BigDecimal("1000.00"),
                new BigDecimal("100.00"), 30);
        long groupA = insertApprovedSubmission("youtube", "yt-a", "group-a", "group-a");
        long groupB = insertApprovedSubmission("tiktok", "tt-b", "group-b", "group-b");
        insertEvidence(groupA, 40_000L); // 80.00
        insertEvidence(groupB, 40_000L); // 80.00

        runConcurrently(2, index -> tx.execute(status -> {
            service.recomputeAccrual(index == 0 ? groupA : groupB);
            return 1L;
        }));

        BigDecimal total = db.queryForObject(
                "SELECT COALESCE(SUM(payable_amount),0) FROM video_bounty_accrual WHERE payout_state IN (0,1,2)",
                BigDecimal.class);
        assertEquals(0, new BigDecimal("100.00").compareTo(total),
                "两笔并发应计合计必须被活动预算封在 100.00，实际=" + total);
    }

    // ------------------------------------------------------------------ 核验 CAS

    @Test
    void concurrentGroupVerificationOnlyOneVersionWins() throws Exception {
        campaignId = insertCampaign("cap-cas", new BigDecimal("2.00"), new BigDecimal("100.00"), null, 30);
        long submission = insertApprovedSubmission("youtube", "yt-cas", "g-cas", null);

        List<Long> results = runConcurrently(2, index -> tx.execute(status ->
                (long) submissionMapper.markGroupVerified(submission, "g-cas-verified", 900L + index,
                        new Date(), "evidence-" + index, 0)));

        assertEquals(1L, results.stream().filter(value -> value == 1L).count(),
                "同一 verified_group_version 只能有一个核验赢家，实际=" + results);
        assertEquals(1, db.queryForObject(
                "SELECT verified_group_version FROM video_bounty_submission WHERE id=?", Integer.class, submission));
        assertNotNull(submissionMapper.selectById(submission).getVerifiedGroupKey());
    }

    // ------------------------------------------------------------------ 证据水位幂等

    @Test
    void sameEvidenceWatermarkIsIdempotentAndNewEvidenceSupersedes() {
        campaignId = insertCampaign("cap-water", new BigDecimal("2.00"), new BigDecimal("100.00"), null, 30);
        long submission = insertApprovedSubmission("youtube", "yt-water", "g-water", "g-water");
        insertEvidence(submission, 10_000L); // 20.00

        VideoBountyAccrual first = service.recomputeAccrual(submission);
        VideoBountyAccrual replay = service.recomputeAccrual(submission);
        assertEquals(first.getId(), replay.getId(), "同一证据水位重算必须幂等");
        assertEquals(1, db.queryForObject(
                "SELECT COUNT(*) FROM video_bounty_accrual WHERE submission_id=? AND payout_state IN (0,1,2)",
                Integer.class, submission));

        insertEvidence(submission, 15_000L); // 合计 25,000 → 50.00
        VideoBountyAccrual second = service.recomputeAccrual(submission);
        assertEquals(1, db.queryForObject(
                "SELECT COUNT(*) FROM video_bounty_accrual WHERE submission_id=? AND payout_state IN (0,1,2)",
                Integer.class, submission), "新证据必须替代旧行，而不是叠加两行有效应计");
        assertEquals(0, new BigDecimal("50.00").compareTo(second.getPayableAmount()),
                "新应计必须按新水位重算，实际=" + second.getPayableAmount());
        VideoBountyAccrual superseded = accrualMapper.selectBySubmission(submission).stream()
                .filter(row -> row.getId().equals(first.getId())).findFirst().orElseThrow();
        assertEquals(VideoBountyAccrual.PAYOUT_SUPERSEDED, superseded.getPayoutState().intValue(),
                "旧水位应计必须保留为 SUPERSEDED，不能被删除或改写金额");
        assertEquals(0, new BigDecimal("20.00").compareTo(superseded.getPayableAmount()),
                "历史行金额必须保持当时口径");
    }

    // ------------------------------------------------------------------ 核验组水位（ME-03）

    /**
     * 两条投稿的**作者自报 origin 不同**、但被运营核验进同一 verified 组时，
     * 证据水位必须按 verified 组取。
     *
     * <p>修复前 {@code VideoBountyEvidenceMapper.selectMaxIdByGroup} 过滤的是
     * {@code s.origin_group_key}：这两条都不等于 verified 键 ⇒ 水位恒为 0，新增证据不改变
     * {@code idempotency_key}，重算被幂等短路成旧应计。本用例因此会在修复前变红 —— 这是有意的，
     * 不允许把已知缺陷绕开当绿。
     */
    @Test
    void verifiedGroupWatermarkTracksNewEvidenceAcrossDifferentAuthorOrigins() {
        campaignId = insertCampaign("cap-origin", new BigDecimal("2.00"),
                new BigDecimal("100.00"), null, 30);
        long a = insertApprovedSubmission("youtube", "yt-origin-a", "author-a", "merged-1");
        long b = insertApprovedSubmission("tiktok", "tt-origin-b", "author-b", "merged-1");
        long other = insertApprovedSubmission("reels", "rl-origin-c", "author-c", "other-group");
        insertEvidence(a, 100_000L);
        insertEvidence(b, 100_000L);
        insertEvidence(other, 50_000L);

        service.recomputeAccrual(a);
        service.recomputeAccrual(b);
        service.recomputeAccrual(other);

        // 100k + 100k = 400.00 毛额 > cap 100 ⇒ 封顶后各 50.00
        assertEquals(0, new BigDecimal("50.00").compareTo(activePayable(a)),
                "A 应得 50.00，实际=" + activePayable(a));
        assertEquals(0, new BigDecimal("50.00").compareTo(activePayable(b)),
                "B 应得 50.00，实际=" + activePayable(b));
        long watermarkBefore = activeWatermark(b);
        long otherAccrualId = activeAccrualId(other);
        BigDecimal otherPayableBefore = activePayable(other);

        // B 追加 20 万播放：组基数 100k + 300k = 400k，cap 100 ⇒ A 25 / B 75
        insertEvidence(b, 200_000L);
        service.recomputeAccrual(b);

        assertTrue(activeWatermark(b) > watermarkBefore,
                "新证据必须推进 verified 组水位，否则重算会被幂等键短路");
        assertEquals(0, new BigDecimal("25.00").compareTo(activePayable(a)),
                "组内 A 必须按新组基数重算为 25.00，实际=" + activePayable(a));
        assertEquals(0, new BigDecimal("75.00").compareTo(activePayable(b)),
                "组内 B 必须为 75.00，实际=" + activePayable(b));
        BigDecimal groupTotal = activePayable(a).add(activePayable(b));
        assertEquals(0, new BigDecimal("100.00").compareTo(groupTotal),
                "A25 + B75 必须恰好等于封顶 100.00，实际=" + groupTotal);

        // 其他组不受影响：同一行、同金额、仍有效。
        assertEquals(otherAccrualId, activeAccrualId(other), "另一组不得被这次重算改写");
        assertEquals(0, otherPayableBefore.compareTo(activePayable(other)), "另一组金额不得变化");
    }

    /** 该投稿当前唯一有效应计（0 行或多行都是缺陷，queryForMap 会直接抛错）。 */
    private java.util.Map<String, Object> activeRow(long submissionId) {
        return db.queryForMap("SELECT id, payable_amount, evidence_watermark, payout_state"
                + " FROM video_bounty_accrual WHERE submission_id=? AND payout_state IN (0,1,2)", submissionId);
    }

    private BigDecimal activePayable(long submissionId) {
        return (BigDecimal) activeRow(submissionId).get("payable_amount");
    }

    private long activeWatermark(long submissionId) {
        return ((Number) activeRow(submissionId).get("evidence_watermark")).longValue();
    }

    private long activeAccrualId(long submissionId) {
        return ((Number) activeRow(submissionId).get("id")).longValue();
    }

    // ------------------------------------------------------------------ fixtures

    private long insertCampaign(String keySuffix, BigDecimal rate, BigDecimal perVideoCap,
                                BigDecimal budgetCap, int windowDays) {
        campaignKey = "it-" + keySuffix + "-" + UUID.randomUUID().toString().substring(0, 8);
        db.update("INSERT INTO video_bounty_campaign"
                        + " (campaign_key,title,status,owner_member_id,currency,rate_per_thousand,per_video_cap,"
                        + " window_days,total_budget_cap,cash_payout_enabled,rule_version,create_time,update_time)"
                        + " VALUES(?,?,1,?,'CNY',?,?,?,?,0,'v1',NOW(),NOW())",
                campaignKey, "IT campaign", MEMBER, rate, perVideoCap, windowDays, budgetCap);
        return db.queryForObject("SELECT id FROM video_bounty_campaign WHERE campaign_key=?",
                Long.class, campaignKey);
    }

    private long insertApprovedSubmission(String platform, String canonicalKey, String originGroupKey,
                                          String verifiedGroupKey) {
        Date publishedAt = new Date(System.currentTimeMillis() - Duration.ofDays(2).toMillis());
        Date windowEndsAt = new Date(publishedAt.getTime() + Duration.ofDays(30).toMillis());
        db.update("INSERT INTO video_bounty_submission"
                        + " (campaign_id,campaign_key,member_id,topic_id,platform,source_url,canonical_video_key,"
                        + " origin_group_key,status,submitted_at,published_at,window_ends_at,del_flag,"
                        + " verified_group_key,verified_by,verified_at,verified_source_ref,verified_group_version,"
                        + " create_time,update_time)"
                        + " VALUES(?,?,?,?,?,?,?,?,2,NOW(),?,?,'0',?,?,?,?,?,NOW(),NOW())",
                campaignId, campaignKey, MEMBER, TOPIC, platform,
                "https://example.com/" + canonicalKey, canonicalKey, originGroupKey,
                publishedAt, windowEndsAt, verifiedGroupKey,
                901L, new Date(), "it-evidence",
                verifiedGroupKey == null ? 0 : 1);
        return db.queryForObject("SELECT id FROM video_bounty_submission WHERE campaign_id=? AND canonical_video_key=?",
                Long.class, campaignId, canonicalKey);
    }

    private void insertEvidence(long submissionId, long views) {
        String hash = sha256(submissionId + ":" + views + ":" + UUID.randomUUID());
        db.update("INSERT INTO video_bounty_evidence"
                        + " (submission_id,evidence_type,source_ref,captured_by,period_start,period_end,"
                        + " views,organic,verifiable,evidence_hash,create_time)"
                        + " VALUES(?,2,'report-it','it-importer',DATE_SUB(NOW(), INTERVAL 1 DAY),NOW(),?,1,1,?,NOW())",
                submissionId, views, hash);
    }

    private interface ConcurrentCall {
        Long run(int index) throws Exception;
    }

    private List<Long> runConcurrently(int threads, ConcurrentCall call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    assertTrue(start.await(20, TimeUnit.SECONDS));
                    Long value = call.run(index);
                    return value == null ? 0L : value;
                }));
            }
            start.countDown();
            List<Long> results = new ArrayList<>();
            for (Future<Long> future : futures) {
                results.add(future.get(90, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
