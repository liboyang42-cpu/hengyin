package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.SmsCouponHistoryMapper;
import com.chengyinhub.business.mapper.SmsCouponMapper;
import com.chengyinhub.business.service.impl.SmsCouponHistoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 真事务边界:审计写入失败必须让「券态回滚 + 计数回退 + 分成作废」整体回滚。
 * 用 H2 代理跑真事务,而不是只验 mock 调用序。
 */
class SmsCouponReverseTransactionBoundaryTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:coupon_reverse_audit_tx;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        jdbc.execute("drop all objects");
        jdbc.execute("create table coupon(id bigint primary key,owner_member_id bigint not null,use_count int not null)");
        jdbc.execute("create table coupon_history(id bigint primary key,coupon_id bigint not null,"
                + "use_status int not null,use_time datetime)");
        jdbc.execute("create table settlement(id bigint auto_increment primary key,history_id bigint not null,"
                + "status int not null,batch_id bigint not null,fail_reason varchar(64))");
        jdbc.execute("create table audit(id bigint auto_increment primary key,owner_member_id bigint not null,"
                + "actor_member_id bigint not null,action varchar(64) not null,request_id varchar(128) not null,"
                + "unique(owner_member_id,action,request_id))");
        jdbc.update("insert into coupon(id,owner_member_id,use_count) values (30,9001,1)");
        jdbc.update("insert into coupon_history(id,coupon_id,use_status,use_time) values (301,30,1,'2026-09-16 10:00:00')");
        jdbc.update("insert into settlement(history_id,status,batch_id) values (301,0,0)");
    }

    @Test
    void auditFailureRollsBackReversedStatusCountAndSettlementDelete() {
        ISmsCouponHistoryService service = service(false);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.reverseRedemption(301L, "店员扫错券", 77L));

        assertEquals("优惠券撤销核销审计写入失败,撤销已回滚", failure.getMessage());
        assertEquals(1, number("select use_status from coupon_history where id=301"));
        assertEquals(1, number("select use_count from coupon where id=30"));
        assertEquals(1, number("select count(*) from settlement where history_id=301"));
        assertEquals(0, number("select count(*) from audit"));
    }

    @Test
    void successCommitsReversedStatusCountSettlementDeleteAndAuditTogether() {
        ISmsCouponHistoryService service = service(true);

        assertTrue(service.reverseRedemption(301L, "店员扫错券", 77L));

        assertEquals(0, number("select use_status from coupon_history where id=301"));
        assertEquals(0, number("select use_count from coupon where id=30"));
        // 待结算分成本来就没进过批次:必须删除才能让「撤销→重核销」重新入账
        assertEquals(0, number("select count(*) from settlement where history_id=301"));
        assertEquals(1, number("select count(*) from audit where owner_member_id=9001 and actor_member_id=77"));
    }

    @Test
    void repeatedRedemptionCyclesWithSameStoredSecondKeepDistinctAuditAndReplayIsIdempotent() {
        jdbc.update("insert into audit(owner_member_id,actor_member_id,action,request_id) "
                + "values (9001,7002,'COUPON_REDEEM','coupon-redeem:301')");
        ISmsCouponHistoryService service = service(true);

        for (int cycle = 0; cycle < 3; cycle++) {
            assertTrue(service.reverseRedemption(301L, "测试撤销后重新核销", 77L));
            assertFalse(service.reverseRedemption(301L, "重复撤销", 77L));
            assertEquals(0, number("select use_status from coupon_history where id=301"));
            assertEquals(0, number("select use_count from coupon where id=30"));
            assertEquals(0, number("select count(*) from settlement"));
            assertEquals(ISmsCouponHistoryService.RedemptionResult.REDEEMED,
                    service.redeemCoupon(301L, 9001L, 11L, 7002L, "MERCHANT_CHECKIN"));
            assertEquals(ISmsCouponHistoryService.RedemptionResult.ALREADY_USED,
                    service.redeemCoupon(301L, 9001L, 11L, 7002L, "MERCHANT_CHECKIN"));
            assertEquals(1, number("select use_status from coupon_history where id=301"));
            assertEquals(1, number("select use_count from coupon where id=30"));
            assertEquals(1, number("select count(*) from settlement"));
            assertEquals(cycle + 1, number("select count(*) from audit where action='COUPON_REDEEM_REVERSE'"));
            assertEquals(cycle + 2, number("select count(*) from audit where action='COUPON_REDEEM'"));
        }
    }

    @SuppressWarnings("unchecked")
    private ISmsCouponHistoryService service(boolean auditSucceeds) {
        SmsCouponHistoryMapper histories = mock(SmsCouponHistoryMapper.class);
        SmsCouponMapper coupons = mock(SmsCouponMapper.class);
        ISettlementEntryService settlements = mock(ISettlementEntryService.class);
        BusinessAuditEventMapper audits = mock(BusinessAuditEventMapper.class);

        when(histories.selectSmsCouponHistoryById(301L)).thenAnswer(call -> {
            SmsCouponHistory row = new SmsCouponHistory();
            row.setId(301L);
            row.setCouponId(30L);
            row.setUseStatus(number("select use_status from coupon_history where id=301"));
            row.setUseTime(jdbc.queryForObject("select use_time from coupon_history where id=301",
                    java.sql.Timestamp.class));
            return row;
        });
        when(histories.reverseUsedCoupon(eq(301L), any())).thenAnswer(call -> jdbc.update(
                "update coupon_history set use_status=0 where id=? and use_status=1", (Object) call.getArgument(0)));
        // 固定数据库可存储的秒值，覆盖快速重核销时 use_time 不能区分周期的情况。
        when(histories.claimUnusedCoupon(eq(301L), any())).thenAnswer(call -> jdbc.update(
                "update coupon_history set use_status=1,use_time='2026-09-16 10:00:00' where id=? and use_status=0",
                (Object) call.getArgument(0)));
        when(coupons.selectSmsCouponById(30L)).thenAnswer(call -> {
            SmsCoupon row = new SmsCoupon();
            row.setId(30L);
            row.setMemberId(jdbc.queryForObject("select owner_member_id from coupon where id=30", Long.class));
            row.setScopeType(1);
            row.setMerchantId(11L);
            return row;
        });
        when(coupons.decrementUseCount(30L)).thenAnswer(call -> jdbc.update(
                "update coupon set use_count=use_count-1 where id=30 and use_count>0"));
        when(coupons.incrementUseCount(30L)).thenAnswer(call -> jdbc.update(
                "update coupon set use_count=use_count+1 where id=30"));
        doAnswer(call -> {
            SmsCouponHistory row = call.getArgument(0);
            jdbc.update("insert into settlement(history_id,status,batch_id) values (?,0,0)", row.getId());
            return null;
        }).when(settlements).recordCouponVerification(any(), any());
        doAnswer(call -> jdbc.update(
                "delete from settlement where history_id=? and status=0 and batch_id=0",
                (Object) call.getArgument(0))).when(settlements).deletePendingCouponVerification(301L);
        when(audits.insertBusinessAuditEvent(any())).thenAnswer(call -> {
            if (!auditSucceeds) return 0;
            BusinessAuditEvent event = call.getArgument(0);
            return jdbc.update("insert into audit(owner_member_id,actor_member_id,action,request_id) values (?,?,?,?)",
                    event.getOwnerMemberId(), event.getActorMemberId(), event.getAction(), event.getRequestId());
        });

        SmsCouponHistoryServiceImpl target = new SmsCouponHistoryServiceImpl();
        ReflectionTestUtils.setField(target, "smsCouponHistoryMapper", histories);
        ReflectionTestUtils.setField(target, "smsCouponMapper", coupons);
        ReflectionTestUtils.setField(target, "settlementEntryService", settlements);
        ReflectionTestUtils.setField(target, "businessAuditEventMapper", audits);
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(ISmsCouponHistoryService.class);
        factory.addAdvice(new TransactionInterceptor(transactionManager,
                new AnnotationTransactionAttributeSource()));
        return (ISmsCouponHistoryService) factory.getProxy();
    }

    private int number(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
