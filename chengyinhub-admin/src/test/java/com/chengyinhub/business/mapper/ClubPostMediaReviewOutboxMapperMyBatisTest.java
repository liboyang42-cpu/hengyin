package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ClubPostMediaReviewOutbox;
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
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real MyBatis coverage for the outbox lease and fencing contract. */
public class ClubPostMediaReviewOutboxMapperMyBatisTest {

    private static final String WORKER_A = repeat('a', 32);
    private static final String WORKER_B = repeat('b', 32);
    private static final String TRACE_HASH = repeat('c', 64);
    private static final String MEDIA_HASH = repeat('d', 64);
    private static final String MAPPER_XML =
            "mapper/business/ClubPostMediaReviewOutboxMapper.xml";

    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        Configuration configuration = new Configuration();
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:club_post_media_outbox_" + System.nanoTime()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        try (InputStream input = Resources.getResourceAsStream(MAPPER_XML)) {
            new XMLMapperBuilder(input, configuration, MAPPER_XML,
                    configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);

        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            statement.execute("create alias if not exists HEX for \""
                    + getClass().getName() + ".hex\"");
            statement.execute("create alias if not exists UNHEX for \""
                    + getClass().getName() + ".unhex\"");
            statement.execute("create table club_post_media_review_outbox ("
                    + "id bigint auto_increment primary key,"
                    + "post_id bigint not null, post_version int not null,"
                    + "submitter_member_id bigint not null, media_hash binary(32) not null,"
                    + "state tinyint not null, owner_token binary(16), fence_token bigint not null,"
                    + "lease_until timestamp(3), attempt_count int not null, max_attempts int not null,"
                    + "next_attempt_at timestamp(3) not null, trace_hash binary(32),"
                    + "last_error_code varchar(64), create_time timestamp(3) not null,"
                    + "update_time timestamp(3) not null,"
                    + "unique(post_id, post_version, media_hash), unique(trace_hash))");
        }
    }

    @Test
    void twoWorkersClaimingTheSameDueRowAtTheSameTimeHaveExactlyOneWinner() throws Exception {
        long id = seedPending();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<ClaimResult> first = workers.submit(() -> claimAfterBarrier(id, WORKER_A, ready, start));
            Future<ClaimResult> second = workers.submit(() -> claimAfterBarrier(id, WORKER_B, ready, start));

            assertTrue(ready.await(5, TimeUnit.SECONDS), "both workers must reach the claim barrier");
            start.countDown();
            ClaimResult firstResult = first.get(5, TimeUnit.SECONDS);
            ClaimResult secondResult = second.get(5, TimeUnit.SECONDS);

            assertEquals(1, firstResult.updatedRows + secondResult.updatedRows,
                    "a due row must be leased to exactly one worker");
            ClaimResult winner = firstResult.updatedRows == 1 ? firstResult : secondResult;
            ClaimResult loser = firstResult.updatedRows == 0 ? firstResult : secondResult;
            try (SqlSession session = factory.openSession(true)) {
                ClubPostMediaReviewOutboxMapper mapper =
                        session.getMapper(ClubPostMediaReviewOutboxMapper.class);
                ClubPostMediaReviewOutbox owned = mapper.selectOwned(id, winner.ownerToken);
                assertNotNull(owned);
                assertEquals(Long.valueOf(1L), owned.getFenceToken());
                assertNull(mapper.selectOwned(id, loser.ownerToken));
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void provenPreDispatchRetryAllowsOnlyTheNewOwnerAndFenceToSubmit() throws Exception {
        long id = seedPending();
        try (SqlSession session = factory.openSession(true)) {
            ClubPostMediaReviewOutboxMapper mapper =
                    session.getMapper(ClubPostMediaReviewOutboxMapper.class);
            assertEquals(1, mapper.claim(id, WORKER_A));
            assertEquals(Long.valueOf(1L), mapper.selectOwned(id, WORKER_A).getFenceToken());
            assertEquals(1, mapper.markRetryable(id, WORKER_A, 1L,
                    new java.util.Date(System.currentTimeMillis() - 1000),
                    ClubPostMediaReviewOutbox.PRE_DISPATCH_UNAVAILABLE));
        }

        try (SqlSession session = factory.openSession(true)) {
            ClubPostMediaReviewOutboxMapper mapper =
                    session.getMapper(ClubPostMediaReviewOutboxMapper.class);
            assertEquals(1, mapper.claim(id, WORKER_B));
            ClubPostMediaReviewOutbox reclaimed = mapper.selectOwned(id, WORKER_B);
            assertNotNull(reclaimed);
            assertEquals(Long.valueOf(2L), reclaimed.getFenceToken(),
                    "every successful claim must advance the fencing token");

            assertEquals(0, mapper.markSubmitted(id, WORKER_A, 1L, TRACE_HASH),
                    "the expired owner and its old fence must not complete the row");
            assertEquals(1, mapper.markSubmitted(id, WORKER_B, 2L, TRACE_HASH),
                    "only the current owner and fence may complete the row");
        }
    }

    @Test
    void fifthClaimMayCompleteButCannotScheduleASixthAttempt() throws Exception {
        long id = seedPending();
        update("update club_post_media_review_outbox set attempt_count=4 where id=" + id);
        try (SqlSession session = factory.openSession(true)) {
            ClubPostMediaReviewOutboxMapper mapper = session.getMapper(ClubPostMediaReviewOutboxMapper.class);
            assertEquals(1, mapper.claim(id, WORKER_A));
            assertEquals(5, mapper.selectOwned(id, WORKER_A).getAttemptCount());
            assertEquals(0, mapper.markRetryable(id, WORKER_A, 1L, new java.util.Date(0),
                    ClubPostMediaReviewOutbox.PRE_DISPATCH_UNAVAILABLE));
            assertEquals(1, mapper.markSubmitted(id, WORKER_A, 1L, TRACE_HASH));
            assertEquals(0, mapper.claim(id, WORKER_B));
        }
        assertEquals(5, number("select attempt_count from club_post_media_review_outbox where id=" + id));
        assertEquals(2, number("select state from club_post_media_review_outbox where id=" + id));
    }

    @Test
    void expiredProcessingStopsBelowAndAtBudgetAndRetainsLateOriginalTrace() throws Exception {
        long id = seedPending();
        try (SqlSession session = factory.openSession(true)) {
            ClubPostMediaReviewOutboxMapper mapper = session.getMapper(ClubPostMediaReviewOutboxMapper.class);
            assertEquals(1, mapper.claim(id, WORKER_A));
        }
        expireLease(id);
        try (SqlSession session = factory.openSession(true)) {
            ClubPostMediaReviewOutboxMapper mapper = session.getMapper(ClubPostMediaReviewOutboxMapper.class);
            assertTrue(mapper.selectDueIds(50).isEmpty());
            assertEquals(0, mapper.claim(id, WORKER_B));
            assertEquals(1, mapper.retireUnsafeById(id));
            assertEquals(0, mapper.claim(id, WORKER_B));
            assertEquals(1, mapper.markManualReview(id, WORKER_A, 1L, TRACE_HASH,
                    ClubPostMediaReviewOutbox.OUTCOME_UNKNOWN));
            assertEquals(0, mapper.markManualReview(id, WORKER_A, 1L, repeat('e',64),
                    ClubPostMediaReviewOutbox.OUTCOME_UNKNOWN));
        }
        assertEquals(1, number("select attempt_count from club_post_media_review_outbox where id=" + id));
        assertEquals(5, number("select state from club_post_media_review_outbox where id=" + id));
        update("update club_post_media_review_outbox set attempt_count=5 where id=" + id);
        try (SqlSession session = factory.openSession(true)) {
            assertEquals(0, session.getMapper(ClubPostMediaReviewOutboxMapper.class).claim(id, WORKER_B));
        }
    }

    @Test
    void exhaustedPendingAndAmbiguousLegacyRetryAreNeverDueOrClaimable() throws Exception {
        long id = seedPending();
        update("update club_post_media_review_outbox set attempt_count=5 where id=" + id);
        try (SqlSession session = factory.openSession(true)) {
            ClubPostMediaReviewOutboxMapper mapper = session.getMapper(ClubPostMediaReviewOutboxMapper.class);
            assertTrue(mapper.selectDueIds(50).isEmpty());
            assertEquals(0, mapper.claim(id, WORKER_A));
            assertEquals(1, mapper.retireUnsafeById(id));
        }
        assertEquals(5, number("select state from club_post_media_review_outbox where id=" + id));
        update("update club_post_media_review_outbox set state=3,attempt_count=1,"
                + "last_error_code='WX_NO_RECEIPT' where id=" + id);
        try (SqlSession session = factory.openSession(true)) {
            ClubPostMediaReviewOutboxMapper mapper = session.getMapper(ClubPostMediaReviewOutboxMapper.class);
            assertTrue(mapper.selectDueIds(50).isEmpty());
            assertEquals(0, mapper.claim(id, WORKER_A));
            assertEquals(1, mapper.retireUnsafeById(id));
        }
        assertEquals(1, number("select attempt_count from club_post_media_review_outbox where id=" + id));
        assertEquals(5, number("select state from club_post_media_review_outbox where id=" + id));
    }

    private void update(String sql) throws Exception {
        try (SqlSession session = factory.openSession(true); Statement statement=session.getConnection().createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private int number(String sql) throws Exception {
        try (SqlSession session = factory.openSession(true); Statement statement=session.getConnection().createStatement();
             java.sql.ResultSet rows=statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private ClaimResult claimAfterBarrier(long id, String ownerToken,
                                          CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try (SqlSession session = factory.openSession(true)) {
            int rows = session.getMapper(ClubPostMediaReviewOutboxMapper.class).claim(id, ownerToken);
            return new ClaimResult(ownerToken, rows);
        }
    }

    private long seedPending() throws Exception {
        try (SqlSession session = factory.openSession(true);
             PreparedStatement statement = session.getConnection().prepareStatement(
                     "insert into club_post_media_review_outbox "
                             + "(post_id, post_version, submitter_member_id, media_hash, state, fence_token, "
                             + "attempt_count, max_attempts, next_attempt_at, create_time, update_time) "
                             + "values (101, 1, 201, ?, 0, 0, 0, 5, "
                             + "DATEADD('SECOND', -1, CURRENT_TIMESTAMP(3)), CURRENT_TIMESTAMP(3), "
                             + "CURRENT_TIMESTAMP(3))", Statement.RETURN_GENERATED_KEYS)) {
            statement.setBytes(1, unhex(MEDIA_HASH));
            assertEquals(1, statement.executeUpdate());
            try (java.sql.ResultSet keys = statement.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private void expireLease(long id) throws Exception {
        try (SqlSession session = factory.openSession(true);
             PreparedStatement statement = session.getConnection().prepareStatement(
                     "update club_post_media_review_outbox "
                             + "set lease_until = DATEADD('SECOND', -1, CURRENT_TIMESTAMP(3)) where id = ?")) {
            statement.setLong(1, id);
            assertEquals(1, statement.executeUpdate());
        }
    }

    /** H2 shim for MySQL HEX used by the unmodified production result map. */
    public static String hex(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02X", value & 0xff));
        }
        return result.toString();
    }

    /** H2 shim for MySQL UNHEX used by the unmodified production claim SQL. */
    public static byte[] unhex(String value) {
        if (value == null) {
            return null;
        }
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            result.append(value);
        }
        return result.toString();
    }

    private static final class ClaimResult {
        private final String ownerToken;
        private final int updatedRows;

        private ClaimResult(String ownerToken, int updatedRows) {
            this.ownerToken = ownerToken;
            this.updatedRows = updatedRows;
        }
    }
}
