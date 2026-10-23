package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.SmsCouponHistory;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exact production mapper/entity with a synthetic in-memory schema; not full backend/MySQL evidence. */
public class SmsCouponHistoryValidityImmutabilityTest {
    private JdbcDataSource db;
    private SqlSessionFactory factory;
    @BeforeEach void setup() throws Exception {
        db = new JdbcDataSource();
        db.setURL("jdbc:h2:mem:coupon_validity_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        try (Connection connection = db.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE sms_coupon_history (id BIGINT PRIMARY KEY, coupon_id BIGINT, member_id BIGINT, coupon_code VARCHAR(100), qrcode_url VARCHAR(400), get_type INT, get_time TIMESTAMP(3), use_status INT, use_time TIMESTAMP(3), order_id BIGINT, order_sn VARCHAR(100), start_time TIMESTAMP(3), end_time TIMESTAMP(3), note VARCHAR(200), del_flag INT, create_by VARCHAR(100), create_time TIMESTAMP(3), update_by VARCHAR(100), update_time TIMESTAMP(3))");
        }
        Configuration configuration = new Configuration(new Environment("isolated", new JdbcTransactionFactory(), db));
        configuration.getTypeAliasRegistry().registerAlias("SmsCouponHistory", SmsCouponHistory.class);
        try (InputStream xml = getClass().getResourceAsStream("/mapper/business/SmsCouponHistoryMapper.xml")) {
            assertNotNull(xml);
            new XMLMapperBuilder(xml, configuration, "mapper/business/SmsCouponHistoryMapper.xml", configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        insert(1L, new Date(1000), new Date(9000));
    }
    @AfterEach void close() throws Exception {
        try (Connection connection = db.getConnection(); Statement sql = connection.createStatement()) { sql.execute("SHUTDOWN"); }
    }
    private void insert(Long id, Date start, Date end) {
        SmsCouponHistory row = new SmsCouponHistory(); row.setId(id); row.setCouponId(8L); row.setMemberId(7L);
        row.setCouponCode("SYNTHETIC-" + id); row.setUseStatus(0); row.setDelFlag(0); row.setStartTime(start); row.setEndTime(end);
        // Production insert obtains its database-generated ID. This fixture explicitly inserts the keyed test row.
        try (Connection connection = db.getConnection(); java.sql.PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO sms_coupon_history (id,coupon_id,member_id,coupon_code,use_status,del_flag,start_time,end_time) VALUES (?,?,?,?,0,0,?,?)")) {
            statement.setLong(1,id); statement.setLong(2,8); statement.setLong(3,7); statement.setString(4,row.getCouponCode());
            statement.setTimestamp(5,start == null ? null : new java.sql.Timestamp(start.getTime()));
            statement.setTimestamp(6,end == null ? null : new java.sql.Timestamp(end.getTime())); statement.executeUpdate();
        } catch (Exception error) { throw new RuntimeException(error); }
    }
    private SmsCouponHistory edit(Long id) { SmsCouponHistory row = new SmsCouponHistory(); row.setId(id); row.setUpdateTime(new Date(5000)); return row; }
    private int update(SmsCouponHistory request) {
        try (SqlSession session = factory.openSession(true)) { return session.getMapper(SmsCouponHistoryMapper.class).updateSmsCouponHistory(request); }
    }
    private SmsCouponHistory read(Long id) {
        try (SqlSession session = factory.openSession(true)) { return session.getMapper(SmsCouponHistoryMapper.class).selectSmsCouponHistoryById(id); }
    }
    @Test void earlierEndCannotRetroactivelyShortenIssuedCoupon() {
        SmsCouponHistory request = edit(1L); request.setEndTime(new Date(8000));
        assertEquals(0,update(request)); assertEquals(new Date(9000),read(1L).getEndTime());
    }
    @Test void laterEndCannotRewriteFrozenIssuedTermsEither() {
        SmsCouponHistory request = edit(1L); request.setEndTime(new Date(10000));
        assertEquals(0,update(request)); assertEquals(new Date(9000),read(1L).getEndTime());
    }
    @Test void laterStartCannotReduceIssuedUsageWindow() {
        SmsCouponHistory request = edit(1L); request.setStartTime(new Date(2000));
        assertEquals(0,update(request)); assertEquals(new Date(1000),read(1L).getStartTime());
    }
    @Test void earlierStartCannotRewriteFrozenIssuedTermsEither() {
        SmsCouponHistory request = edit(1L); request.setStartTime(new Date(500));
        assertEquals(0,update(request)); assertEquals(new Date(1000),read(1L).getStartTime());
    }
    @Test void dateMismatchRejectsEntireUpdateWithoutPartialStatusOrNoteMutation() {
        SmsCouponHistory request = edit(1L); request.setEndTime(new Date(8000)); request.setUseStatus(2); request.setNote("must not apply");
        assertEquals(0,update(request)); SmsCouponHistory stored=read(1L);
        assertEquals(0,stored.getUseStatus()); assertNull(stored.getNote()); assertNull(stored.getUpdateTime());
    }
    @Test void omittedNullableDatesKeepExistingTermsForOrdinaryMetadataUpdate() {
        SmsCouponHistory request = edit(1L); request.setNote("reviewed metadata");
        assertEquals(1,update(request)); SmsCouponHistory stored=read(1L);
        assertEquals(new Date(1000),stored.getStartTime()); assertEquals(new Date(9000),stored.getEndTime()); assertEquals("reviewed metadata",stored.getNote());
    }
    @Test void exactUnchangedDatesAllowExistingDateUnrelatedUpdate() {
        SmsCouponHistory request = edit(1L); request.setStartTime(new Date(1000)); request.setEndTime(new Date(9000)); request.setNote("reviewed metadata");
        assertEquals(1,update(request)); assertEquals("reviewed metadata",read(1L).getNote());
    }
    @Test void unknownLegacyStartCannotBeInventedByGenericEdit() {
        insert(2L,null,new Date(9000)); SmsCouponHistory request=edit(2L); request.setStartTime(new Date(1000));
        assertEquals(0,update(request)); assertNull(read(2L).getStartTime());
    }
    @Test void unknownLegacyEndCannotBeInventedByGenericEdit() {
        insert(2L,new Date(1000),null); SmsCouponHistory request=edit(2L); request.setEndTime(new Date(9000));
        assertEquals(0,update(request)); assertNull(read(2L).getEndTime());
    }
    @Test void bothUnknownLegacyDatesRemainUnknownAfterMetadataEdit() {
        insert(2L,null,null); SmsCouponHistory request=edit(2L); request.setNote("historical dates unknown");
        assertEquals(1,update(request)); SmsCouponHistory stored=read(2L); assertNull(stored.getStartTime()); assertNull(stored.getEndTime());
    }
    @Test void subsecondDifferenceCannotSlipThroughTheSqlGuard() {
        SmsCouponHistory request=edit(1L); request.setStartTime(new Date(1001));
        assertEquals(0,update(request)); assertEquals(new Date(1000),read(1L).getStartTime());
    }
    @Test void missingHistoryIDIsExplicitZeroRows() {
        SmsCouponHistory request=edit(999L); request.setNote("absent"); assertEquals(0,update(request));
    }
    @Test void existingAtomicRedemptionStillUsesOriginalFrozenWindow() {
        try(SqlSession session=factory.openSession(true)) {
            SmsCouponHistoryMapper mapper=session.getMapper(SmsCouponHistoryMapper.class);
            assertEquals(0,mapper.claimUnusedCoupon(1L,new Date(999)));
            assertEquals(1,mapper.claimUnusedCoupon(1L,new Date(5000)));
            assertEquals(0,mapper.claimUnusedCoupon(1L,new Date(5000)));
        }
        SmsCouponHistory stored=read(1L); assertEquals(1,stored.getUseStatus()); assertEquals(new Date(9000),stored.getEndTime());
    }
    @Test void existingAtomicExpirationDoesNotRewriteValidity() {
        try(SqlSession session=factory.openSession(true)) {
            assertEquals(1,session.getMapper(SmsCouponHistoryMapper.class).expireUnusedCoupon(1L,new Date(10000)));
        }
        SmsCouponHistory stored=read(1L); assertEquals(2,stored.getUseStatus()); assertEquals(new Date(1000),stored.getStartTime()); assertEquals(new Date(9000),stored.getEndTime());
    }
    @Test void concurrentStatusUpdateAndRetroactiveDateEditCannotShortenAfterLockWait() throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try(SqlSession first=factory.openSession(false)) {
            SmsCouponHistory current=edit(1L); current.setUseStatus(2);
            assertEquals(1,first.getMapper(SmsCouponHistoryMapper.class).updateSmsCouponHistory(current));
            CountDownLatch entered=new CountDownLatch(1);
            Future<Integer> attempted=executor.submit(()->{ entered.countDown(); SmsCouponHistory change=edit(1L); change.setEndTime(new Date(8000)); return update(change); });
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            // The immutable-date predicate may reject immediately without acquiring the
            // unrelated status writer's lock. Either early zero or post-lock zero is safe.
            try { assertEquals(0,attempted.get(100,TimeUnit.MILLISECONDS)); }
            catch (TimeoutException waitingForRowLock) { /* final zero is required below */ }
            first.commit(); assertEquals(0,attempted.get(5,TimeUnit.SECONDS));
            SmsCouponHistory stored=read(1L); assertEquals(2,stored.getUseStatus()); assertEquals(new Date(9000),stored.getEndTime());
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)); }
    }
    @Test void sameDatesPermitLegitimateMetadataUpdateAfterConcurrentStatusLock() throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try(SqlSession first=factory.openSession(false)) {
            SmsCouponHistory current=edit(1L); current.setUseStatus(2);
            assertEquals(1,first.getMapper(SmsCouponHistoryMapper.class).updateSmsCouponHistory(current));
            CountDownLatch entered=new CountDownLatch(1);
            Future<Integer> attempted=executor.submit(()->{
                entered.countDown(); SmsCouponHistory change=edit(1L);
                change.setStartTime(new Date(1000)); change.setEndTime(new Date(9000)); change.setNote("reviewed after status update");
                return update(change);
            });
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,()->attempted.get(100,TimeUnit.MILLISECONDS));
            first.commit(); assertEquals(1,attempted.get(5,TimeUnit.SECONDS));
            SmsCouponHistory stored=read(1L); assertEquals(2,stored.getUseStatus());
            assertEquals(new Date(1000),stored.getStartTime()); assertEquals(new Date(9000),stored.getEndTime());
            assertEquals("reviewed after status update",stored.getNote());
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)); }
    }
    @Test void unchangedDatesOnlyRemainAValidLegacyMapperCall() {
        SmsCouponHistory request=new SmsCouponHistory(); request.setId(1L);
        request.setStartTime(new Date(1000)); request.setEndTime(new Date(9000));
        assertEquals(1,update(request));
        assertEquals(new Date(1000),read(1L).getStartTime()); assertEquals(new Date(9000),read(1L).getEndTime());
    }
}
