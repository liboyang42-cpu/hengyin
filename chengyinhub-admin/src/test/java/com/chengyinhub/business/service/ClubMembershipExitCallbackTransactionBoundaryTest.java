package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubMembershipInvoice;
import com.chengyinhub.business.domain.ClubMembershipPlan;
import com.chengyinhub.business.domain.ClubMembershipTerm;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.ClubMembershipMapper;
import com.chengyinhub.business.service.impl.ClubMembershipServiceImpl;
import com.chengyinhub.business.service.impl.ClubMembershipSnapshotCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClubMembershipExitCallbackTransactionBoundaryTest {
    private static final long CLUB = 7L;
    private static final long OWNER = 70L;
    private static final long MEMBER = 71L;
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final CountDownLatch memberDeletedBeforeCommit = new CountDownLatch(1);
    private final CountDownLatch releaseExitCommit = new CountDownLatch(1);
    private final CountDownLatch callbackReachedClubLock = new CountDownLatch(1);
    private JdbcTemplate jdbc;
    private PlatformTransactionManager transactions;
    private IClubMembershipService service;
    private ClubMembershipInvoice invoice;
    private final VerifiedPaymentProofFixtures proof=new VerifiedPaymentProofFixtures();

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:club_membership_exit_callback;MODE=MySQL;DB_CLOSE_DELAY=-1;"
                + "DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=5000");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        jdbc.execute("drop all objects");
        jdbc.execute("create table club(id bigint primary key, member_id bigint not null, status bigint not null, "
                + "del_flag int not null)");
        jdbc.execute("create table club_member(id bigint primary key, club_id bigint not null, member_id bigint not null, "
                + "status int not null, del_flag int not null)");
        jdbc.execute("create table granted_term(id bigint auto_increment primary key, status varchar(32) not null)");
        jdbc.execute("create table audit_event(id bigint auto_increment primary key, action varchar(64) not null)");
        jdbc.update("insert into club(id,member_id,status,del_flag) values (?,?,1,0)", CLUB, OWNER);
        jdbc.update("insert into club_member(id,club_id,member_id,status,del_flag) values (1,?,?,1,0)", CLUB, MEMBER);

        invoice = validPayingInvoice();
        service = proxiedService(dataBackedClubMapper(), dataBackedMemberMapper(), dataBackedMembershipMapper());
    }

    @AfterEach
    void tearDown() {
        releaseExitCommit.countDown();
        workers.shutdownNow();
    }

    @Test
    void paidCallbackWaitsForExitCommitAndCreatesOnlyCancelledTerm() throws Exception {
        TransactionTemplate exitTransaction = new TransactionTemplate(transactions);
        Future<?> exit = workers.submit(() -> exitTransaction.execute(status -> {
            jdbc.queryForObject("select id from club where id=? for update", Long.class, CLUB);
            jdbc.update("delete from club_member where club_id=? and member_id=?", CLUB, MEMBER);
            memberDeletedBeforeCommit.countDown();
            await(releaseExitCommit);
            return null;
        }));
        assertTrue(memberDeletedBeforeCommit.await(5, TimeUnit.SECONDS));

        Future<Boolean> callback = workers.submit(() -> service.handlePaidInvoice(
                invoice.getOutTradeNo(), true, 9900, "wx-tx-after-exit",
                proof.issue(invoice.getOutTradeNo(),9900,"wx-tx-after-exit")));
        assertTrue(callbackReachedClubLock.await(5, TimeUnit.SECONDS));
        assertFalse(callback.isDone(), "退出事务持有 club 锁时，支付回调必须等待，不能先发 ACTIVE 权益");

        releaseExitCommit.countDown();
        exit.get(5, TimeUnit.SECONDS);
        assertTrue(callback.get(5, TimeUnit.SECONDS));
        assertEquals(0, jdbc.queryForObject("select count(*) from club_member", Integer.class));
        assertEquals(ClubMembershipTerm.STATUS_CANCELLED,
                jdbc.queryForObject("select status from granted_term", String.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from audit_event", Integer.class));
    }

    private IClubMembershipService proxiedService(ClubMapper clubs, ClubMemberMapper members,
                                                   ClubMembershipMapper memberships) {
        ClubMembershipServiceImpl target = new ClubMembershipServiceImpl();
        ReflectionTestUtils.setField(target, "clubMapper", clubs);
        ReflectionTestUtils.setField(target,"verifiedPaymentFacts",proof.reader);
        ReflectionTestUtils.setField(target,"transactionManager",transactions);
        IWechatPayService localAttempt=mock(IWechatPayService.class);
        when(localAttempt.lockPaymentAttemptForUpdate(invoice.getOutTradeNo(),invoice.getOutTradeNo())).thenReturn(true);
        ReflectionTestUtils.setField(target,"wechatPayService",localAttempt);
        ReflectionTestUtils.setField(target, "clubMemberMapper", members);
        ReflectionTestUtils.setField(target, "clubMembershipMapper", memberships);
        ReflectionTestUtils.setField(target, "businessAuditEventMapper", auditMapper());
        ReflectionTestUtils.setField(target, "clock", Clock.fixed(
                Instant.parse("2026-08-23T16:00:00Z"), ZoneId.of("Asia/Shanghai")));
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(IClubMembershipService.class);
        factory.addAdvice(new TransactionInterceptor(
                transactions, new AnnotationTransactionAttributeSource()));
        return (IClubMembershipService) factory.getProxy();
    }

    private ClubMapper dataBackedClubMapper() {
        ClubMapper mapper = mock(ClubMapper.class);
        when(mapper.selectClubByIdForUpdate(CLUB)).thenAnswer(call -> {
            callbackReachedClubLock.countDown();
            return jdbc.queryForObject(
                    "select id,member_id,status,del_flag from club where id=? and del_flag=0 for update",
                    (rs, rowNum) -> {
                        Club row = new Club();
                        row.setId(rs.getLong("id"));
                        row.setMemberId(rs.getLong("member_id"));
                        row.setStatus(rs.getLong("status"));
                        row.setDelFlag(rs.getInt("del_flag"));
                        return row;
                    }, CLUB);
        });
        return mapper;
    }

    private ClubMemberMapper dataBackedMemberMapper() {
        ClubMemberMapper mapper = mock(ClubMemberMapper.class);
        when(mapper.selectByClubAndMemberForUpdate(CLUB, MEMBER)).thenAnswer(call -> {
            List<ClubMember> rows = jdbc.query(
                    "select id,club_id,member_id,status,del_flag from club_member "
                            + "where club_id=? and member_id=? and status=1 and del_flag=0 for update",
                    (rs, rowNum) -> {
                        ClubMember row = new ClubMember();
                        row.setId(rs.getLong("id"));
                        row.setClubId(rs.getLong("club_id"));
                        row.setMemberId(rs.getLong("member_id"));
                        row.setStatus(rs.getInt("status"));
                        row.setDelFlag(rs.getInt("del_flag"));
                        return row;
                    }, CLUB, MEMBER);
            return rows.isEmpty() ? null : rows.get(0);
        });
        return mapper;
    }

    private ClubMembershipMapper dataBackedMembershipMapper() {
        ClubMembershipMapper mapper = mock(ClubMembershipMapper.class);
        when(mapper.selectInvoiceByOutTradeNo(invoice.getOutTradeNo())).thenReturn(invoice);
        when(mapper.selectInvoiceByOutTradeNoForUpdate(invoice.getOutTradeNo())).thenReturn(invoice);
        when(mapper.markInvoicePaidCas(eq(invoice.getId()), eq(invoice.getVersion()),
                eq("wx-tx-after-exit"), any(Date.class)))
                .thenReturn(1);
        when(mapper.insertRefundReview(any())).thenReturn(1);
        when(mapper.bindInvoicePaymentProof(eq(invoice.getId()),eq("wx-tx-after-exit"),eq(9900L),
                eq(invoice.getSnapshotHash()),any(),any())).thenAnswer(call->{
            invoice.setStatus(ClubMembershipInvoice.STATUS_PAID);invoice.setTransactionId("wx-tx-after-exit");
            invoice.setVerifiedPaymentFactId(call.getArgument(4));invoice.setVerifiedPaymentFactHash(call.getArgument(5));return 1;
        });
        when(mapper.insertTerm(any(ClubMembershipTerm.class))).thenAnswer(call -> {
            ClubMembershipTerm term = call.getArgument(0, ClubMembershipTerm.class);
            jdbc.update("insert into granted_term(status) values (?)", term.getStatus());
            term.setId(201L);
            return 1;
        });
        return mapper;
    }

    private BusinessAuditEventMapper auditMapper() {
        BusinessAuditEventMapper mapper = mock(BusinessAuditEventMapper.class);
        when(mapper.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenAnswer(call -> {
            BusinessAuditEvent event = call.getArgument(0, BusinessAuditEvent.class);
            jdbc.update("insert into audit_event(action) values (?)", event.getAction());
            return 1;
        });
        return mapper;
    }

    private ClubMembershipInvoice validPayingInvoice() {
        ClubMembershipInvoice row = new ClubMembershipInvoice();
        row.setId(100L);
        row.setInvoiceNo("CLM01234567890123456789012345678");
        row.setOutTradeNo("CLM01234567890123456789012345678");
        row.setClubId(CLUB);
        row.setOwnerMemberIdSnapshot(OWNER);
        row.setMemberId(MEMBER);
        row.setPlanId(9L);
        row.setPlanVersionSnapshot(1);
        row.setPlanNameSnapshot("月度会员");
        row.setDurationDaysSnapshot(30);
        row.setAmountFen(9900L);
        row.setBenefitsSnapshot("[\"优先报名\"]");
        row.setGraceDaysSnapshot(3);
        row.setPastDueDaysSnapshot(30);
        row.setExitPolicySnapshot(ClubMembershipPlan.EXIT_POLICY_END_IMMEDIATELY);
        row.setRefundPolicySnapshot(ClubMembershipPlan.POLICY_MANUAL_REVIEW);
        row.setStatus(ClubMembershipInvoice.STATUS_PAYING);
        row.setVersion(2);
        row.setSnapshotHash(ClubMembershipSnapshotCodec.hash(row));
        return row;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("latch timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", interrupted);
        }
    }
}
