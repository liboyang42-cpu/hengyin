package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.mapper.SmsCouponHistoryMapper;
import com.chengyinhub.business.mapper.SmsCouponMapper;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.impl.SmsCouponHistoryServiceImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** NOT_RUN. Real production XML on H2 expresses commit ordering, not real MySQL concurrency. */
class E04_03Test {
    private JdbcTemplate jdbc;
    private SmsCouponMapper coupons;
    private ISmsCouponHistoryService service;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:e04_03;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        ds.setPassword("");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE sms_coupon(id BIGINT PRIMARY KEY, member_id BIGINT, del_flag INT, status INT,"
                + "publish_count BIGINT, receive_count BIGINT, update_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE sms_coupon_history(id BIGINT AUTO_INCREMENT PRIMARY KEY, coupon_id BIGINT, member_id BIGINT,"
                + "coupon_code VARCHAR(100), get_type INT, get_time TIMESTAMP, use_status INT, create_time TIMESTAMP)");
        jdbc.update("INSERT INTO sms_coupon(id,member_id,del_flag,status,publish_count,receive_count) VALUES (30,99,0,1,10,0)");

        Configuration config = new Configuration(new Environment("e04-03",
                new SpringManagedTransactionFactory(), ds));
        config.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        addMapper(config, SmsCouponMapper.class, "mapper/business/SmsCouponMapper.xml");
        addMapper(config, SmsCouponHistoryMapper.class, "mapper/business/SmsCouponHistoryMapper.xml");
        SqlSessionTemplate template = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        coupons = template.getMapper(SmsCouponMapper.class);
        SmsCouponHistoryServiceImpl target = new SmsCouponHistoryServiceImpl();
        ReflectionTestUtils.setField(target, "smsCouponMapper", coupons);
        ReflectionTestUtils.setField(target, "smsCouponHistoryMapper", template.getMapper(SmsCouponHistoryMapper.class));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(ISmsCouponHistoryService.class);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),
                new AnnotationTransactionAttributeSource()));
        service = (ISmsCouponHistoryService) proxy.getProxy();
    }

    @Test
    void stopCommittedAfterLimitedSnapshotPreventsNewHistoryAndStockMutation() {
        SmsCoupon stale = activeSnapshot(10L);
        // Same production stop SQL used by the real endpoint; commits before reservation starts.
        assertEquals(1, coupons.stopIssuingCoupon(30L, 99L));
        assertNull(service.grantCouponWithReceipt(7001L, stale));
        assertNoGrant(4);
    }

    @Test
    void currentStoppedOrDisabledStatusRejectsEveryUnlimitedSnapshotKind() {
        for (int status : new int[]{3, 4}) {
            for (Long limit : new Long[]{null, 0L, -1L}) {
                jdbc.update("UPDATE sms_coupon SET status=?, publish_count=? WHERE id=30", status, limit);
                assertNull(service.grantCouponWithReceipt(7001L, activeSnapshot(limit)));
                assertNoGrant(status);
            }
        }
    }

    @Test
    void reservationDatabaseFailureStillThrowsInsteadOfBecomingOrdinaryRejection() {
        jdbc.execute("ALTER TABLE sms_coupon ADD CONSTRAINT reject_increment CHECK (receive_count=0)");
        for (Long limit : new Long[]{10L, 0L}) {
            jdbc.update("UPDATE sms_coupon SET publish_count=? WHERE id=30", limit);
            assertThrows(RuntimeException.class, () -> service.grantCouponWithReceipt(7001L, activeSnapshot(limit)));
            assertNoGrant(1);
        }
    }

    @Test
    void nullCurrentStatusAndNonpositiveLimitsKeepLegacyIssuanceAndActualCounts() {
        for (Long limit : new Long[]{null, 0L, -1L}) {
            jdbc.update("UPDATE sms_coupon SET status=NULL, publish_count=?, receive_count=NULL WHERE id=30", limit);
            Long id = service.grantCouponWithReceipt(7001L, activeSnapshot(limit));
            assertNotNull(id);
            assertEquals(1L, jdbc.queryForObject("SELECT receive_count FROM sms_coupon WHERE id=30", Long.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sms_coupon_history WHERE id=? AND member_id=7001 AND coupon_id=30", Integer.class, id));
        }
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM sms_coupon_history", Integer.class));
    }

    @Test
    void legacyStatisticalIncrementStillUpdatesStoppedRowsWithoutIssuingAnything() {
        jdbc.update("UPDATE sms_coupon SET status=4 WHERE id=30");
        assertEquals(1, coupons.incrementReceiveCount(30L));
        assertEquals(1L, jdbc.queryForObject("SELECT receive_count FROM sms_coupon WHERE id=30", Long.class));
        assertEquals(4, jdbc.queryForObject("SELECT status FROM sms_coupon WHERE id=30", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sms_coupon_history", Integer.class));
    }

    private void assertNoGrant(int status) {
        assertEquals(status, jdbc.queryForObject("SELECT status FROM sms_coupon WHERE id=30", Integer.class));
        assertEquals(0L, jdbc.queryForObject("SELECT receive_count FROM sms_coupon WHERE id=30", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sms_coupon_history", Integer.class));
    }

    private SmsCoupon activeSnapshot(Long publishCount) {
        SmsCoupon coupon = new SmsCoupon();
        coupon.setId(30L);
        coupon.setStatus(1);
        coupon.setPublishCount(publishCount);
        return coupon;
    }

    private void addMapper(Configuration config, Class<?> type, String resource) throws Exception {
        config.addMapper(type);
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            new XMLMapperBuilder(in, config, resource, config.getSqlFragments()).parse();
        }
    }
}
