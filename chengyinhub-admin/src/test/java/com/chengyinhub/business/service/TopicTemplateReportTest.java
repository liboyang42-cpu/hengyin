package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.AuditRecord;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTemplateLibraryMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.impl.AuditTaskServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.api.ApiTemplateController;
import com.chengyinhub.web.controller.api.ApiTopicController;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUDIT-G2-2:主题、模板广场、个人玩法(公开可读的)都要有举报入口;复用 reportContent
 * (汇聚到同一张未结单 + 每次 AuditRecord(action=REPORT)),同一举报人对同一轮只记一次。
 */
class TopicTemplateReportTest {

    /** 服务层:按举报人去重 + 举报单的处置口径。 */
    @Nested
    class Service {
        private final AuditTaskMapper taskMapper = mock(AuditTaskMapper.class);
        private final AuditRecordMapper recordMapper = mock(AuditRecordMapper.class);
        private final CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        private final CmsMemberTemplateMapper templateMapper = mock(CmsMemberTemplateMapper.class);
        private final CmsTemplateLibraryMapper libraryMapper = mock(CmsTemplateLibraryMapper.class);
        private final AuditTaskServiceImpl svc = new AuditTaskServiceImpl();
        private final List<AuditRecord> records = new ArrayList<>();

        Service() {
            com.chengyinhub.business.mapper.BusinessAuditEventMapper events =
                    mock(com.chengyinhub.business.mapper.BusinessAuditEventMapper.class);
            ReflectionTestUtils.setField(svc, "auditTaskMapper", taskMapper);
            ReflectionTestUtils.setField(svc, "auditRecordMapper", recordMapper);
            ReflectionTestUtils.setField(svc, "businessAuditEventMapper", events);
            ReflectionTestUtils.setField(svc, "cmsTopicMapper", topicMapper);
            ReflectionTestUtils.setField(svc, "templateMapper", templateMapper);
            ReflectionTestUtils.setField(svc, "libraryMapper", libraryMapper);
            @SuppressWarnings("unchecked")
            ObjectProvider<IImService> im = mock(ObjectProvider.class);
            when(im.getObject()).thenReturn(mock(IImService.class));
            ReflectionTestUtils.setField(svc, "imServiceProvider", im);
            @SuppressWarnings("unchecked")
            ObjectProvider<ICmsTopicService> topics = mock(ObjectProvider.class);
            when(topics.getObject()).thenReturn(mock(ICmsTopicService.class));
            ReflectionTestUtils.setField(svc, "cmsTopicServiceProvider", topics);
            when(events.insertBusinessAuditEvent(any())).thenReturn(1);
            when(recordMapper.insertAuditRecord(any())).thenAnswer(inv -> {
                records.add(inv.getArgument(0));
                return 1;
            });
            when(recordMapper.selectAuditRecordList(any())).thenAnswer(inv -> {
                AuditRecord q = inv.getArgument(0);
                List<AuditRecord> out = new ArrayList<>();
                for (AuditRecord r : records) {
                    if (q.getTaskId().equals(r.getTaskId())
                            && (q.getAction() == null || q.getAction().equals(r.getAction()))) out.add(r);
                }
                return out;
            });
            when(taskMapper.insertAuditTask(any())).thenAnswer(inv -> {
                AuditTask t = inv.getArgument(0);
                t.setId(55L);
                when(taskMapper.selectAuditTaskList(any())).thenReturn(Collections.singletonList(t));
                return 1;
            });
            when(taskMapper.updateAuditTaskReviewCas(anyLong(), anyInt(), anyInt(), anyLong(), anyInt(), any())).thenReturn(1);
        }

        @Test
        void sameReporterIsCountedOncePerOpenRound() {
            assertTrue(svc.reportContentOnce(AuditTask.BIZ_TOPIC, 880L, 7L, "虚假信息"));
            assertFalse(svc.reportContentOnce(AuditTask.BIZ_TOPIC, 880L, 7L, "虚假信息"), "同一人重复举报不再记");
            assertTrue(svc.reportContentOnce(AuditTask.BIZ_TOPIC, 880L, 8L, "色情低俗内容"));

            assertEquals(2, records.size());
            for (AuditRecord r : records) assertEquals("REPORT", r.getAction());
            verify(taskMapper).insertAuditTask(any());
        }

        @Test
        void blankReasonIsRefused() {
            assertThrows(IllegalArgumentException.class,
                    () -> svc.reportContentOnce(AuditTask.BIZ_TEMPLATE_LIBRARY, 191L, 7L, "  "));
            verify(taskMapper, never()).insertAuditTask(any());
        }

        private AuditTask reportTask(String bizType, Long bizId) {
            AuditTask task = new AuditTask();
            task.setId(9L);
            task.setBizType(bizType);
            task.setBizId(bizId);
            task.setSubmitterId(7L);
            task.setSourceType("report");
            task.setStatus(AuditTask.STATUS_PENDING);
            task.setVersion(0);
            task.setSnapshotJson("{\"bizType\":\"" + bizType + "\"}");
            when(taskMapper.selectAuditTaskById(9L)).thenReturn(task);
            when(taskMapper.selectAuditTaskByIdForUpdate(9L)).thenReturn(task);
            return task;
        }

        private AuditReviewDTO decision(Integer status) {
            AuditReviewDTO d = new AuditReviewDTO();
            d.setStatus(status);
            d.setExpectedVersion(0);
            d.setReasonText("举报审核");
            return d;
        }

        @Test
        void dismissingTopicReportLeavesTopicUntouched() {
            reportTask(AuditTask.BIZ_TOPIC, 880L);
            CmsTopic live = new CmsTopic();
            live.setId(880L); live.setStatus(1); live.setUserStatus(1); live.setCheckStatus(1);
            when(topicMapper.selectCmsTopicById(880L)).thenReturn(live);

            svc.review(9L, 1L, decision(AuditTask.STATUS_APPROVED));

            verify(topicMapper, never()).updateCmsTopic(any());
        }

        @Test
        void upheldTopicReportTakesTopicDown() {
            reportTask(AuditTask.BIZ_TOPIC, 880L);
            when(topicMapper.updateCmsTopic(any())).thenReturn(1);

            svc.review(9L, 1L, decision(AuditTask.STATUS_REJECTED));

            ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
            verify(topicMapper).updateCmsTopic(cap.capture());
            assertEquals(2, cap.getValue().getStatus());
            assertEquals(0, cap.getValue().getUserStatus());
        }

        /** 已上架的广场模板被举报:不能卡在「仅待审可裁决」的快照校验上,要能处置。 */
        @Test
        void libraryReportCanBeDismissedAndUpheldOnPublishedRow() {
            reportTask(AuditTask.BIZ_TEMPLATE_LIBRARY, 191L);
            CmsTemplateLibrary row = new CmsTemplateLibrary();
            row.setId(191L); row.setMemberTemplateId(91L); row.setMemberId(70L);
            row.setDelFlag(0); row.setStatus(1L); row.setPublishStatus(1L);
            when(libraryMapper.selectCmsTemplateLibraryById(191L)).thenReturn(row);
            CmsMemberTemplate owner = new CmsMemberTemplate();
            owner.setId(91L); owner.setMemberId(70L); owner.setDelFlag(0); owner.setDraftStatus(1);
            owner.setStatus(1L); owner.setCheckStatus(1);
            when(templateMapper.selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(91L)))
                    .thenReturn(Collections.singletonList(owner));
            when(libraryMapper.selectByMemberTemplateForUpdate(91L)).thenReturn(Collections.singletonList(row));
            when(libraryMapper.takeDownReported(eq(191L), anyString())).thenReturn(1);

            svc.review(9L, 1L, decision(AuditTask.STATUS_REJECTED));

            verify(libraryMapper).takeDownReported(191L, "举报审核");
            verify(libraryMapper, never()).applyAuditDecision(any(), anyLong());
        }

        @Test
        void libraryReportDismissalChangesNothing() {
            reportTask(AuditTask.BIZ_TEMPLATE_LIBRARY, 191L);
            CmsTemplateLibrary row = new CmsTemplateLibrary();
            row.setId(191L); row.setMemberTemplateId(91L); row.setMemberId(70L);
            row.setDelFlag(0); row.setStatus(1L); row.setPublishStatus(1L);
            when(libraryMapper.selectCmsTemplateLibraryById(191L)).thenReturn(row);
            when(templateMapper.selectCmsMemberTemplateByIdsForUpdate(any())).thenReturn(Collections.emptyList());
            when(libraryMapper.selectByMemberTemplateForUpdate(91L)).thenReturn(Collections.singletonList(row));

            svc.review(9L, 1L, decision(AuditTask.STATUS_APPROVED));

            verify(libraryMapper, never()).takeDownReported(anyLong(), anyString());
            verify(libraryMapper, never()).applyAuditDecision(any(), anyLong());
        }

        @Test
        void upheldPersonalTemplateReportTakesTemplateAndItsShelfRowsDown() {
            reportTask(AuditTask.BIZ_TEMPLATE, 91L);
            CmsMemberTemplate owner = new CmsMemberTemplate();
            owner.setId(91L); owner.setMemberId(70L); owner.setDelFlag(0); owner.setDraftStatus(1);
            owner.setStatus(1L); owner.setCheckStatus(1); owner.setPublishStatus(1L);
            when(templateMapper.selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(91L)))
                    .thenReturn(Collections.singletonList(owner));
            when(libraryMapper.selectByMemberTemplateForUpdate(91L)).thenReturn(Collections.emptyList());
            when(templateMapper.takeDownReported(eq(91L), anyString())).thenReturn(1);

            svc.review(9L, 1L, decision(AuditTask.STATUS_REJECTED));

            verify(templateMapper).takeDownReported(91L, "举报审核");
            verify(libraryMapper).takeDownReportedByMemberTemplate(91L, "举报审核");
            verify(templateMapper, never()).applyAuditDecision(any(), anyLong());
        }
    }

    /** 入口:只能举报公开可见的内容;未登录拒。 */
    @Nested
    class Endpoints {
        private final IAuditTaskService audit = mock(IAuditTaskService.class);

        @Test
        void topicReportOnPublicTopic() {
            ApiTopicController c = spy(new ApiTopicController());
            ICmsTopicService topics = mock(ICmsTopicService.class);
            doReturn(7L).when(c).getAppUserId();
            ReflectionTestUtils.setField(c, "cmsTopicService", topics);
            ReflectionTestUtils.setField(c, "auditTaskService", audit);
            CmsTopic t = new CmsTopic();
            t.setId(880L); t.setMemberId(19L); t.setStatus(1); t.setUserStatus(1); t.setDelFlag(0);
            when(topics.selectCmsTopicById(880L)).thenReturn(t);
            when(topics.selectTopicIsDraft(880L)).thenReturn(0);
            when(audit.reportContentOnce(AuditTask.BIZ_TOPIC, 880L, 7L, "虚假信息")).thenReturn(true);

            AjaxResult r = c.topicReport("880", "虚假信息");

            assertTrue(r.isSuccess(), String.valueOf(r));
            verify(audit).reportContentOnce(AuditTask.BIZ_TOPIC, 880L, 7L, "虚假信息");

            t.setUserStatus(0);
            AjaxResult hidden = c.topicReport("880", "虚假信息");
            assertFalse(hidden.isSuccess(), "未公开主题不开放举报(避免借举报枚举/给下架主题建单)");
        }

        @Test
        void topicReportRequiresLogin() {
            ApiTopicController c = spy(new ApiTopicController());
            doReturn(0L).when(c).getAppUserId();
            ReflectionTestUtils.setField(c, "auditTaskService", audit);
            assertEquals("请先登录", c.topicReport("880", "虚假信息").get(AjaxResult.MSG_TAG));
            verify(audit, never()).reportContentOnce(anyString(), anyLong(), anyLong(), anyString());
        }

        private ApiTemplateController templateController() {
            ApiTemplateController c = spy(new ApiTemplateController());
            doReturn(7L).when(c).getAppUserId();
            ReflectionTestUtils.setField(c, "auditTaskService", audit);
            return c;
        }

        @Test
        void libraryReportOnlyForShelfVisibleRows() {
            ApiTemplateController c = templateController();
            ICmsTemplateLibraryService library = mock(ICmsTemplateLibraryService.class);
            ReflectionTestUtils.setField(c, "templateLibraryService", library);
            CmsTemplateLibrary row = new CmsTemplateLibrary();
            row.setId(191L); row.setDelFlag(0); row.setStatus(1L); row.setPublishStatus(1L);
            when(library.selectCmsTemplateLibraryById(191L)).thenReturn(row);
            when(audit.reportContentOnce(AuditTask.BIZ_TEMPLATE_LIBRARY, 191L, 7L, "知识产权或内容盗用")).thenReturn(false);

            AjaxResult r = c.libraryReport("191", "知识产权或内容盗用");
            assertTrue(r.isSuccess());
            verify(audit).reportContentOnce(AuditTask.BIZ_TEMPLATE_LIBRARY, 191L, 7L, "知识产权或内容盗用");

            row.setPublishStatus(0L);
            assertFalse(c.libraryReport("191", "知识产权或内容盗用").isSuccess());
        }

        @Test
        void personalTemplateReportOnlyForPubliclyReadableTemplates() {
            ApiTemplateController c = templateController();
            ICmsMemberTemplateService members = mock(ICmsMemberTemplateService.class);
            ReflectionTestUtils.setField(c, "memberTemplateService", members);
            CmsMemberTemplate tpl = new CmsMemberTemplate();
            tpl.setId(91L); tpl.setDelFlag(0); tpl.setDraftStatus(1); tpl.setStatus(1L); tpl.setPublishStatus(1L);
            when(members.selectCmsMemberTemplateById(91L)).thenReturn(tpl);
            when(audit.reportContentOnce(AuditTask.BIZ_TEMPLATE, 91L, 7L, "其他")).thenReturn(true);

            assertTrue(c.personalTemplateReport("91", "其他").isSuccess());
            verify(audit).reportContentOnce(AuditTask.BIZ_TEMPLATE, 91L, 7L, "其他");

            tpl.setStatus(0L);
            assertFalse(c.personalTemplateReport("91", "其他").isSuccess(), "待审/草稿模板不公开,不开放举报");
        }
    }
}
