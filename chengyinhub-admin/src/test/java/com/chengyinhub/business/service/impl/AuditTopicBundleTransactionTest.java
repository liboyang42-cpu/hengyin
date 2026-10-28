package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditRecord;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IQuotaService;
import com.chengyinhub.business.service.support.TopicBundleReviewService;
import com.chengyinhub.common.exception.ServiceException;
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

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 真实 Spring 事务证明组合审核任一发布副作用失败时不会留下部分通过。 */
class AuditTopicBundleTransactionTest {
    private JdbcTemplate jdbc;
    private IAuditTaskService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:topic_bundle_"
                + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table task(id bigint primary key,status int,version int)");
        jdbc.execute("create table topic(id bigint primary key,status int,user_status int,route_published int,plaza_visible int)");
        jdbc.execute("create table template(id bigint primary key,draft_status int,status int)");
        jdbc.execute("create table timeline(id bigint auto_increment primary key,action varchar(40))");
        jdbc.execute("create table notice(id bigint auto_increment primary key)");
        jdbc.execute("create table shell(id bigint auto_increment primary key,topic_id bigint)");
        jdbc.update("insert into task values(9,0,3)");
        jdbc.update("insert into topic values(77,0,0,0,0)");
        jdbc.update("insert into template values(31,0,0)");

        AuditTaskServiceImpl target = new AuditTaskServiceImpl();
        AuditTaskMapper tasks = mock(AuditTaskMapper.class);
        when(tasks.selectAuditTaskById(9L)).thenAnswer(invocation -> task(false));
        when(tasks.selectAuditTaskByIdForUpdate(9L)).thenAnswer(invocation -> task(true));
        when(tasks.updateAuditTaskReviewCas(anyLong(), anyInt(), anyInt(), anyLong(), anyInt(), any()))
                .thenAnswer(invocation -> jdbc.update(
                        "update task set status=?,version=version+1 where id=? and status=? and version=?",
                        invocation.getArgument(2), invocation.getArgument(0),
                        invocation.getArgument(1), invocation.getArgument(4)));
        ReflectionTestUtils.setField(target, "auditTaskMapper", tasks);

        AuditRecordMapper records = mock(AuditRecordMapper.class);
        when(records.insertAuditRecord(any())).thenAnswer(invocation -> jdbc.update(
                "insert into timeline(action) values(?)", ((AuditRecord) invocation.getArgument(0)).getAction()));
        when(records.selectAuditRecordList(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(target, "auditRecordMapper", records);
        BusinessAuditEventMapper events = mock(BusinessAuditEventMapper.class);
        when(events.selectByDomainActionRequestForUpdate(anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(null);
        when(events.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(1);
        ReflectionTestUtils.setField(target, "businessAuditEventMapper", events);

        TopicBundleReviewService bundle = mock(TopicBundleReviewService.class);
        doAnswer(invocation -> {
            jdbc.update("update template set draft_status=1,status=1 where id=31");
            return null;
        }).when(bundle).applyDecision(77L, 5L, "{\"schemaVersion\":2}", true);
        ReflectionTestUtils.setField(target, "topicBundleReviewService", bundle);

        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsTopic topic = new CmsTopic();
        topic.setId(77L);
        topic.setMemberId(5L);
        when(topics.selectCmsTopicById(77L)).thenReturn(topic);
        when(topics.selectCmsTopicByIdForUpdate(77L)).thenReturn(topic);
        when(topics.updateCmsTopic(any())).thenAnswer(invocation -> {
            CmsTopic update = invocation.getArgument(0);
            return jdbc.update("update topic set status=?,user_status=? where id=77",
                    update.getStatus(), update.getUserStatus());
        });
        ReflectionTestUtils.setField(target, "cmsTopicMapper", topics);
        com.chengyinhub.business.service.support.AppMediaPublicationGuard mediaGuard =
                com.chengyinhub.business.service.support.MediaPublicationTestFixtures.template(5L);
        com.chengyinhub.business.mapper.CmsTopicChapterMapper mediaChapters =
                mock(com.chengyinhub.business.mapper.CmsTopicChapterMapper.class);
        com.chengyinhub.business.mapper.CmsTopicNodeMapper mediaNodes =
                mock(com.chengyinhub.business.mapper.CmsTopicNodeMapper.class);
        when(mediaChapters.selectCmsTopicChapterList(any())).thenReturn(java.util.Collections.emptyList());
        when(mediaNodes.selectCmsTopicNodeList(any())).thenReturn(java.util.Collections.emptyList());
        ReflectionTestUtils.setField(mediaGuard, "topics", topics);
        ReflectionTestUtils.setField(mediaGuard, "chapters", mediaChapters);
        ReflectionTestUtils.setField(mediaGuard, "nodes", mediaNodes);
        ReflectionTestUtils.setField(target, "mediaPublicationGuard", mediaGuard);
        IQuotaService quota = mock(IQuotaService.class);
        when(quota.lockAndCheckThemeOnline(5L, 77L)).thenReturn(null);
        ReflectionTestUtils.setField(target, "quotaService", quota);

        ICmsTopicService topicService = mock(ICmsTopicService.class);
        doAnswer(invocation -> { jdbc.update("update topic set route_published=1 where id=77"); return null; })
                .when(topicService).publishApprovedRouteSnapshot(77L);
        doAnswer(invocation -> { jdbc.update("update topic set plaza_visible=1 where id=77"); return null; })
                .when(topicService).syncCreativeSquarePostVisibility(77L, true);
        doAnswer(invocation -> {
            jdbc.update("insert into shell(topic_id) values(77)");
            throw new IllegalStateException("injected shell failure");
        }).when(topicService).ensureSelfHostActivityShell(77L);
        ReflectionTestUtils.setField(target, "cmsTopicServiceProvider", provider(topicService));

        IImService im = mock(IImService.class);
        when(im.sendSystem(anyLong(), anyInt(), anyString(), anyString())).thenAnswer(invocation -> {
            jdbc.update("insert into notice default values");
            return 1L;
        });
        ReflectionTestUtils.setField(target, "imServiceProvider", provider(im));

        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (IAuditTaskService) proxy.getProxy();
    }

    @Test
    void failureAfterTemplateTopicRouteAndPlazaWritesRollsEverythingBack() {
        AuditReviewDTO request = new AuditReviewDTO();
        request.setStatus(AuditTask.STATUS_APPROVED);
        request.setRequestId("topic-bundle-approve-9");
        request.setExpectedVersion(3);

        ServiceException failure = assertThrows(ServiceException.class, () -> service.review(9L, 100L, request));
        assertEquals("injected shell failure", failure.getDetailMessage());

        assertEquals(0, value("select status from task"));
        assertEquals(3, value("select version from task"));
        assertEquals(0, value("select status from template"));
        assertEquals(0, value("select draft_status from template"));
        assertEquals(0, value("select status from topic"));
        assertEquals(0, value("select route_published from topic"));
        assertEquals(0, value("select plaza_visible from topic"));
        assertEquals(0, value("select count(*) from shell"));
        assertEquals(0, value("select count(*) from timeline"));
        assertEquals(0, value("select count(*) from notice"));
    }

    private AuditTask task(boolean lock) {
        String sql = "select status,version from task where id=9" + (lock ? " for update" : "");
        return jdbc.queryForObject(sql, (result, row) -> {
            AuditTask task = new AuditTask();
            task.setId(9L);
            task.setBizType(AuditTask.BIZ_TOPIC);
            task.setBizId(77L);
            task.setSubmitterId(5L);
            task.setSourceType("topic_bundle_v2");
            task.setSnapshotJson("{\"schemaVersion\":2}");
            task.setStatus(result.getInt("status"));
            task.setVersion(result.getInt("version"));
            return task;
        });
    }

    private int value(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(value);
        return provider;
    }
}
