package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 实际 Spring 事务 + H2 行锁；边界 Mapper 接本地 SQL，通知只写隔离表，不调用外部发送。 */
class HangoutAuditTransactionTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private IAuditTaskService service;
    private IRoamHangoutService hangouts;
    private AtomicBoolean failNotice;
    private AtomicBoolean failAudit;
    private CountDownLatch reportLockRequested;

    @BeforeEach
    void setup() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:hangout_audit_"
                + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        manager = new DataSourceTransactionManager(ds);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table task(id bigint primary key,status int,version int)");
        jdbc.execute("create table hangout(id bigint primary key,status int,group_closed int)");
        jdbc.execute("create table audit_event(action varchar(80),request_id varchar(80),before_json varchar(2000),after_json varchar(2000),reason varchar(200),unique(action,request_id))");
        jdbc.execute("create table timeline(id bigint auto_increment primary key,action varchar(40))");
        jdbc.execute("create table notice(recipient bigint,extra varchar(4000))");
        jdbc.update("insert into task values(40,0,0)");
        jdbc.update("insert into hangout values(88,0,0)");
        failNotice = new AtomicBoolean(false);
        failAudit = new AtomicBoolean(false);
        AuditTaskServiceImpl target = new AuditTaskServiceImpl();
        AuditTaskMapper tasks = mock(AuditTaskMapper.class);
        when(tasks.selectAuditTaskById(40L)).thenAnswer(c -> jdbc.queryForObject("select * from task where id=40", (r, n) -> {
            AuditTask t = new AuditTask(); t.setId(40L); t.setStatus(r.getInt("status")); t.setVersion(r.getInt("version"));
            t.setBizType(AuditTask.BIZ_HANGOUT); t.setBizId(88L); t.setSourceType("report"); t.setSubmitterId(9L);
            return t;
        }));
        when(tasks.selectAuditTaskList(any())).thenAnswer(c -> Collections.singletonList(tasks.selectAuditTaskById(40L)));
        when(tasks.selectAuditTaskByIdForUpdate(40L)).thenAnswer(c -> {
            if (reportLockRequested != null) reportLockRequested.countDown();
            int status = jdbc.queryForObject("select status from task where id=40 for update", Integer.class);
            AuditTask t = new AuditTask(); t.setId(40L); t.setStatus(status); return t;
        });
        when(tasks.updateAuditTaskReviewCas(anyLong(), anyInt(), anyInt(), anyLong(), anyInt(), any())).thenAnswer(c ->
                jdbc.update("update task set status=?,version=version+1 where id=? and status=? and version=?",
                        c.getArgument(2, Object.class), c.getArgument(0, Object.class), c.getArgument(1, Object.class), c.getArgument(4, Object.class)));
        ReflectionTestUtils.setField(target, "auditTaskMapper", tasks);
        AuditRecordMapper records = mock(AuditRecordMapper.class);
        when(records.insertAuditRecord(any())).thenAnswer(c -> {
            AuditRecord record = c.getArgument(0); record.setId(1L);
            return jdbc.update("insert into timeline(action) values(?)", record.getAction());
        });
        when(records.selectAuditRecordList(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(target, "auditRecordMapper", records);
        BusinessAuditEventMapper events = mock(BusinessAuditEventMapper.class);
        when(events.insertBusinessAuditEvent(any())).thenAnswer(c -> {
            BusinessAuditEvent e = c.getArgument(0);
            if (failAudit.get()) throw new IllegalStateException("injected audit failure");
            return jdbc.update("insert into audit_event values(?,?,?,?,?)", e.getAction(), e.getRequestId(), e.getBeforeJson(), e.getAfterJson(), e.getReason());
        });
        when(events.selectByDomainActionRequestForUpdate(anyString(), anyLong(), anyString(), anyString())).thenAnswer(c -> {
            List<BusinessAuditEvent> list = jdbc.query("select * from audit_event where action=? and request_id=? for update", (r, n) -> {
                BusinessAuditEvent e = new BusinessAuditEvent(); e.setActorMemberId(100L); e.setBeforeJson(r.getString("before_json"));
                e.setAfterJson(r.getString("after_json")); e.setReason(r.getString("reason")); return e;
            }, c.getArgument(2, Object.class), c.getArgument(3, Object.class));
            return list.isEmpty() ? null : list.get(0);
        });
        ReflectionTestUtils.setField(target, "businessAuditEventMapper", events);
        RoamHangoutMapper mapper = mock(RoamHangoutMapper.class);
        when(mapper.selectByIdForUpdate(88L)).thenAnswer(c -> jdbc.queryForObject("select * from hangout where id=88 for update", (r, n) -> {
            RoamHangout h = new RoamHangout(); h.setId(88L); h.setMemberId(4L); h.setConversationId(500L); h.setStatus(r.getInt("status")); return h;
        }));
        when(mapper.updateStatus(anyLong(), anyInt())).thenAnswer(c -> jdbc.update("update hangout set status=? where id=?", c.getArgument(1, Object.class), c.getArgument(0, Object.class)));
        ReflectionTestUtils.setField(target, "roamHangoutMapper", mapper);
        ImConversationMemberMapper members = mock(ImConversationMemberMapper.class);
        ImConversationMember member = new ImConversationMember(); member.setMemberId(5L); member.setDeleted(1);
        when(members.selectByConversationForUpdate(500L)).thenReturn(Collections.singletonList(member));
        ReflectionTestUtils.setField(target, "imConversationMemberMapper", members);
        IImService im = mock(IImService.class);
        when(im.sendSystem(anyLong(), anyInt(), anyString(), anyString())).thenAnswer(c -> {
            if (failNotice.get() && count("notice") == 1) throw new IllegalStateException("injected notification failure");
            jdbc.update("insert into notice values(?,?)", c.getArgument(0, Object.class), c.getArgument(3, Object.class)); return 1L;
        });
        doAnswer(c -> { jdbc.update("update hangout set group_closed=1 where id=88"); return null; }).when(im).closeHangoutGroup(88L);
        ReflectionTestUtils.setField(target, "imServiceProvider", provider(im));
        RoamHangoutServiceImpl hangoutTarget = new RoamHangoutServiceImpl();
        ReflectionTestUtils.setField(hangoutTarget, "roamHangoutMapper", mapper);
        ReflectionTestUtils.setField(hangoutTarget, "imService", im);
        hangouts = proxy(hangoutTarget, IRoamHangoutService.class);
        ReflectionTestUtils.setField(target, "roamHangoutServiceProvider", provider(hangouts));
        service = proxy(target, IAuditTaskService.class);
    }

    @Test
    void failedSecondNoticeRollsBackDecisionAndCanRetryExactlyOnce() {
        failNotice.set(true);
        assertThrows(IllegalStateException.class, () -> service.review(40L, 100L, request(2)));
        assertEquals(0, value("select status from task"));
        assertEquals(0, count("timeline")); assertEquals(0, count("audit_event")); assertEquals(0, count("notice"));
        assertEquals(0, value("select status from hangout")); assertEquals(0, value("select group_closed from hangout"));
        failNotice.set(false);
        assertEquals(1, service.review(40L, 100L, request(2)));
        assertEquals(1, service.review(40L, 100L, request(2)));
        assertEquals(3, count("notice")); assertEquals(1, count("timeline")); assertEquals(1, count("audit_event"));
        assertEquals(2, value("select status from hangout")); assertEquals(1, value("select group_closed from hangout"));
    }

    @Test
    void failedFinalAuditRollsBackAlreadyWrittenNotificationsAndClosure() {
        failAudit.set(true);
        assertThrows(IllegalStateException.class, () -> service.review(40L, 100L, request(2)));
        assertEquals(0, value("select status from task")); assertEquals(0, count("notice"));
        assertEquals(0, value("select status from hangout")); assertEquals(0, value("select group_closed from hangout"));
        assertEquals(0, count("timeline")); assertEquals(0, count("audit_event"));
    }

    @Test
    void escalationReplayDoesNotDeclareTerminalResult() {
        assertEquals(1, service.review(40L, 100L, request(3)));
        assertEquals(1, service.review(40L, 100L, request(3)));
        assertEquals(3, value("select status from task")); assertEquals(0, count("notice")); assertEquals(1, count("timeline"));
        assertEquals(1, service.review(40L, 100L, request(2)));
        assertEquals(3, count("notice"));
    }

    @Test
    void concurrentSafetyPenaltyAndUnfoundedReportNeverReopenHangout() throws Exception {
        CountDownLatch penaltyApplied = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> penalty = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                hangouts.moderate(88L, true);
                penaltyApplied.countDown();
                try { if (!allowCommit.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertTrue(penaltyApplied.await(5, TimeUnit.SECONDS));
            Future<Integer> review = pool.submit(() -> service.review(40L, 100L, request(1)));
            assertEquals(1, review.get(5, TimeUnit.SECONDS));
            allowCommit.countDown(); penalty.get(5, TimeUnit.SECONDS);
            hangouts.moderate(88L, false);
            assertEquals(2, value("select status from hangout"));
            assertEquals(1, value("select group_closed from hangout"));
        } finally { allowCommit.countDown(); pool.shutdownNow(); }
    }

    @Test
    void reportArrivingDuringDecisionCannotBeSilentlyAppendedAfterNotification() throws Exception {
        CountDownLatch decisionWritten = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        reportLockRequested = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> decision = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                service.review(40L, 100L, request(2));
                decisionWritten.countDown();
                try { if (!allowCommit.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertTrue(decisionWritten.await(5, TimeUnit.SECONDS));
            Future<Long> report = pool.submit(() -> service.reportContent(AuditTask.BIZ_HANGOUT, 88L, 7L, "迟到举报"));
            assertTrue(reportLockRequested.await(5, TimeUnit.SECONDS));
            allowCommit.countDown(); decision.get(5, TimeUnit.SECONDS);
            ExecutionException rejected = assertThrows(ExecutionException.class, () -> report.get(5, TimeUnit.SECONDS));
            assertTrue(rejected.getCause() instanceof com.chengyinhub.common.exception.ServiceException);
            assertEquals(1, count("timeline")); assertEquals(3, count("notice"));
        } finally { allowCommit.countDown(); pool.shutdownNow(); }
    }

    private AuditReviewDTO request(int status) {
        AuditReviewDTO r = new AuditReviewDTO(); r.setStatus(status); r.setReasonText("核实后的处理原因"); r.setRequestId("review-40-" + status); return r;
    }
    private int count(String table) { return value("select count(*) from " + table); }
    private int value(String sql) { return jdbc.queryForObject(sql, Integer.class); }
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class); when(p.getObject()).thenReturn(value); return p;
    }
    private <T> T proxy(Object target, Class<T> type) {
        ProxyFactory p = new ProxyFactory(target);
        p.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return type.cast(p.getProxy());
    }
}
