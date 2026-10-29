package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.AppMediaPublicationGuard;
import com.chengyinhub.web.controller.api.ApiTopicController;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.Collections;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

/** Production topic mapper, author controller/CAS and Spring audit transaction on controlled MySQL.
 * Audit-task facts use a JDBC adapter; permissions, media and unrelated shell/square/IM are doubles.
 * This is not a complete audit mapper, HTTP, financial or process-kill acceptance test. */
class AuditTopicCurrentPublicationMySqlIT {
    @Test
    void rrSpotCheckApprovalKeepsTheAuthorsAlreadyCommittedOfflineDecision() throws Exception {
        try (var f = new TopicContentGraphMySqlFixture()) {
            f.open();
            f.tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            // Only this disposable scratch needs the existing production narrow-update columns.
            f.db.execute("alter table cms_topic add column review_flag int not null default 0, "
                    + "add column content_safety_hold int not null default 0");
            f.db.update("update cms_topic set is_draft=0,status=1,user_status=1,check_status=1,"
                    + "published_config_version=7,beta_flag=0,review_flag=1 where id=100");
            f.db.execute("create table audit_rr_task_fact(id bigint primary key,status int,version int) engine=InnoDB");
            f.db.update("insert into audit_rr_task_fact values(9,0,0)");
            ExecutorService author = Executors.newSingleThreadExecutor();
            CountDownLatch snapshotEstablished = new CountDownLatch(1);
            try {
                ApiTopicController controller = new ApiTopicController() {
                    @Override public Long getAppUserId() { return 42L; }
                };
                ReflectionTestUtils.setField(controller, "cmsTopicService", f.service);
                Future<?> offline = author.submit(() -> {
                    assertTrue(snapshotEstablished.await(10, TimeUnit.SECONDS));
                    assertEquals(200, controller.topicUpdateUserStatus("100", "1", null).get("code"));
                    // This connection is outside both service transactions: author has really committed.
                    assertEquals(0, new JdbcTemplate(f.manager.getDataSource()).queryForObject(
                            "select user_status from cms_topic where id=100", Integer.class));
                    return null;
                });
                AuditTaskMapper tasks = mock(AuditTaskMapper.class);
                when(tasks.selectAuditTaskById(9L)).thenAnswer(call -> {
                    // Actual nonlocking InnoDB read at review's first task lookup creates the old RR view.
                    assertEquals(0, f.db.queryForObject("select status from audit_rr_task_fact where id=9", Integer.class));
                    snapshotEstablished.countDown();
                    offline.get(15, TimeUnit.SECONDS);
                    AuditTask task = new AuditTask(); task.setId(9L); task.setBizType(AuditTask.BIZ_TOPIC);
                    task.setBizId(100L); task.setSubmitterId(42L); task.setStatus(AuditTask.STATUS_PENDING);
                    task.setVersion(0); task.setSourceType("post_review_flag"); return task;
                });
                when(tasks.selectTopicPublicationTasksForUpdate(100L)).thenAnswer(call -> {
                    f.db.queryForList("select id from audit_rr_task_fact where id=9 for update");
                    return Collections.emptyList();
                });
                when(tasks.updateAuditTaskReviewCas(anyLong(), anyInt(), anyInt(), anyLong(), anyInt(), any()))
                        .thenAnswer(call -> f.db.update("update audit_rr_task_fact set status=?,version=version+1 "
                                        + "where id=? and status=? and version=?", call.getArgument(2),
                                call.getArgument(0), call.getArgument(1), call.getArgument(4)));
                CmsTopicMapper auditTopics = spy(f.topics);
                doAnswer(call -> {
                    var stale = f.topics.selectCmsTopicById(100L);
                    assertEquals(1, stale.getUserStatus(), "the audit must actually observe the old RR view");
                    return stale;
                }).when(auditTopics).selectCmsTopicById(100L);
                AuditTaskServiceImpl target = new AuditTaskServiceImpl();
                AuditRecordMapper records = mock(AuditRecordMapper.class);
                BusinessAuditEventMapper events = mock(BusinessAuditEventMapper.class);
                when(records.insertAuditRecord(any())).thenReturn(1);
                when(events.insertBusinessAuditEvent(any())).thenReturn(1);
                ICmsTopicService publication = mock(ICmsTopicService.class, delegatesTo(f.service));
                doNothing().when(publication).syncCreativeSquarePostVisibility(anyLong(), anyBoolean());
                doNothing().when(publication).ensureSelfHostActivityShell(anyLong());
                ReflectionTestUtils.setField(target, "auditTaskMapper", tasks);
                ReflectionTestUtils.setField(target, "auditRecordMapper", records);
                ReflectionTestUtils.setField(target, "businessAuditEventMapper", events);
                ReflectionTestUtils.setField(target, "cmsTopicMapper", auditTopics);
                ReflectionTestUtils.setField(target, "quotaService", mock(IQuotaService.class));
                ReflectionTestUtils.setField(target, "mediaPublicationGuard", mock(AppMediaPublicationGuard.class));
                ReflectionTestUtils.setField(target, "cmsTopicServiceProvider", provider(publication));
                ReflectionTestUtils.setField(target, "imServiceProvider", provider(mock(IImService.class)));
                ProxyFactory proxy = new ProxyFactory(target); proxy.setInterfaces(IAuditTaskService.class);
                proxy.addAdvice(new TransactionInterceptor(f.manager, new AnnotationTransactionAttributeSource()));
                IAuditTaskService audit = (IAuditTaskService) proxy.getProxy();
                AuditReviewDTO request = new AuditReviewDTO(); request.setStatus(AuditTask.STATUS_APPROVED);
                request.setRequestId("rr-author-offline"); request.setReasonText("approved");
                Integer reviewed = f.tx.execute(tx -> audit.review(9L, 11L, request));
                assertEquals(Integer.valueOf(1), reviewed);
                JdbcTemplate committed = new JdbcTemplate(f.manager.getDataSource());
                assertEquals(0, committed.queryForObject("select user_status from cms_topic where id=100", Integer.class));
                assertEquals(7L, committed.queryForObject("select published_config_version from cms_topic where id=100", Long.class));
                assertEquals(0, committed.queryForObject("select beta_flag from cms_topic where id=100", Integer.class));
                assertEquals(0, committed.queryForObject("select review_flag from cms_topic where id=100", Integer.class));
                assertEquals(1, committed.queryForObject("select check_status from cms_topic where id=100", Integer.class));
                assertEquals(1, committed.queryForObject("select status from audit_rr_task_fact where id=9", Integer.class));
                verify(auditTopics).selectCmsTopicByIdForUpdate(100L);
                verify(publication).syncCreativeSquarePostVisibility(100L, false);
                verify(publication, never()).syncCreativeSquarePostVisibility(100L, true);
                verify(publication, never()).publishApprovedRouteSnapshot(anyLong());
            } finally {
                snapshotEstablished.countDown(); author.shutdownNow();
                assertTrue(author.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> result = mock(ObjectProvider.class); when(result.getObject()).thenReturn(value); return result;
    }
}
