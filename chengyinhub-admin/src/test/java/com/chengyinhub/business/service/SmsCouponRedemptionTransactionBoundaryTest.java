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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SmsCouponRedemptionTransactionBoundaryTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:coupon_redemption_audit_tx;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        jdbc.execute("drop all objects");
        jdbc.execute("create table coupon(id bigint primary key,owner_member_id bigint not null,use_count int not null)");
        jdbc.execute("create table coupon_history(id bigint primary key,coupon_id bigint not null,use_status int not null)");
        jdbc.execute("create table settlement(id bigint auto_increment primary key,history_id bigint not null)");
        jdbc.execute("create table audit(id bigint auto_increment primary key,owner_member_id bigint not null,actor_member_id bigint not null)");
        jdbc.update("insert into coupon(id,owner_member_id,use_count) values (30,9001,0)");
        jdbc.update("insert into coupon_history(id,coupon_id,use_status) values (301,30,0)");
    }

    @Test
    void auditFailureRollsBackClaimSettlementAndCount() {
        ISmsCouponHistoryService service = service(false);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.redeemCoupon(301L, 9001L, 11L, 7002L, "MERCHANT_CHECKIN"));

        assertEquals("优惠券核销审计写入失败", failure.getMessage());
        assertEquals(0, number("select use_status from coupon_history where id=301"));
        assertEquals(0, number("select use_count from coupon where id=30"));
        assertEquals(0, number("select count(*) from settlement"));
        assertEquals(0, number("select count(*) from audit"));
    }

    @Test
    void successCommitsOwnerSettlementAndActualActorAuditTogether() {
        ISmsCouponHistoryService service = service(true);

        assertEquals(ISmsCouponHistoryService.RedemptionResult.REDEEMED,
                service.redeemCoupon(301L, 9001L, 11L, 7002L, "MERCHANT_CHECKIN"));

        assertEquals(1, number("select use_status from coupon_history where id=301"));
        assertEquals(1, number("select use_count from coupon where id=30"));
        assertEquals(1, number("select count(*) from settlement where history_id=301"));
        assertEquals(1, number("select count(*) from audit where owner_member_id=9001 and actor_member_id=7002"));
    }

    @SuppressWarnings("unchecked")
    private ISmsCouponHistoryService service(boolean auditSucceeds) {
        SmsCouponHistoryMapper histories = mock(SmsCouponHistoryMapper.class);
        SmsCouponMapper coupons = mock(SmsCouponMapper.class);
        ISettlementEntryService settlements = mock(ISettlementEntryService.class);
        BusinessAuditEventMapper audits = mock(BusinessAuditEventMapper.class);

        when(histories.selectSmsCouponHistoryByIdForUpdate(301L)).thenAnswer(call -> {
            SmsCouponHistory row = new SmsCouponHistory();
            row.setId(301L);
            row.setCouponId(30L);
            row.setUseStatus(number("select use_status from coupon_history where id=301"));
            return row;
        });
        when(coupons.selectSmsCouponById(30L)).thenAnswer(call -> {
            SmsCoupon row = new SmsCoupon();
            row.setId(30L);
            row.setMemberId(jdbc.queryForObject("select owner_member_id from coupon where id=30", Long.class));
            row.setScopeType(1);
            row.setMerchantId(11L);
            return row;
        });
        when(histories.claimUnusedCoupon(any(), any())).thenAnswer(call -> jdbc.update(
                "update coupon_history set use_status=1 where id=? and use_status=0",
                (Object) call.getArgument(0)));
        doAnswer(call -> {
            SmsCouponHistory row = call.getArgument(0);
            jdbc.update("insert into settlement(history_id) values (?)", row.getId());
            return null;
        }).when(settlements).recordCouponVerification(any(), any());
        when(coupons.incrementUseCount(30L)).thenAnswer(call -> jdbc.update(
                "update coupon set use_count=use_count+1 where id=30"));
        when(audits.insertBusinessAuditEvent(any())).thenAnswer(call -> {
            if (!auditSucceeds) return 0;
            BusinessAuditEvent event = call.getArgument(0);
            return jdbc.update("insert into audit(owner_member_id,actor_member_id) values (?,?)",
                    event.getOwnerMemberId(), event.getActorMemberId());
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
