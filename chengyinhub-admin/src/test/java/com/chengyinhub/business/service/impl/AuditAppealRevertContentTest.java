package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditAppeal;
import com.chengyinhub.business.domain.AuditRecord;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.AuditRevertDTO;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.domain.vo.AuditRevertResultVO;
import com.chengyinhub.business.mapper.AuditAppealMapper;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTemplateLibraryMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.support.AppMediaPublicationGuard;
import com.chengyinhub.business.service.support.TemplateAuditSnapshot;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUDIT-G4：通用申诉(P3-3) + 内容类审核撤销(P0-5/P0-7) + 作者时间线(P2-8) + 申诉认领进入复核中。
 */
class AuditAppealRevertContentTest {
    private static final long AUTHOR = 10L;
    private static final long STRANGER = 11L;
    private static final long REPORTER = 77L;
    private static final long REVIEWER = 900L;
    private static final long BIZ_ID = 501L;
    private static final long REJECTED_TASK = 70L;
    private static final long APPEAL_TASK = 71L;
    private static final int VERSION = 1;
    private static final Instant NOW = Instant.parse("2026-10-20T08:00:00Z");

    private AuditTaskMapper tasks;
    private AuditRecordMapper records;
    private BusinessAuditEventMapper audits;
    private AuditAppealMapper appeals;
    private CmsActivityMapper activities;
    private CmsTopicMapper topics;
    private CmsMemberTemplateMapper templates;
    private CmsTemplateLibraryMapper libraries;
    private ICmsTopicService topicService;
    private IImService im;
    private AuditTaskServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        tasks = mock(AuditTaskMapper.class);
        records = mock(AuditRecordMapper.class);
        audits = mock(BusinessAuditEventMapper.class);
        appeals = mock(AuditAppealMapper.class);
        activities = mock(CmsActivityMapper.class);
        topics = mock(CmsTopicMapper.class);
        templates = mock(CmsMemberTemplateMapper.class);
        libraries = mock(CmsTemplateLibraryMapper.class);
        topicService = mock(ICmsTopicService.class);
        im = mock(IImService.class);
        service = new AuditTaskServiceImpl();
        ObjectProvider<IImService> imProvider = mock(ObjectProvider.class);
        when(imProvider.getObject()).thenReturn(im);
        ObjectProvider<ICmsTopicService> topicProvider = mock(ObjectProvider.class);
        when(topicProvider.getObject()).thenReturn(topicService);
        ReflectionTestUtils.setField(service, "imServiceProvider", imProvider);
        ReflectionTestUtils.setField(service, "cmsTopicServiceProvider", topicProvider);
        ReflectionTestUtils.setField(service, "auditTaskMapper", tasks);
        ReflectionTestUtils.setField(service, "auditRecordMapper", records);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", audits);
        ReflectionTestUtils.setField(service, "auditAppealMapper", appeals);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(service, "templateMapper", templates);
        ReflectionTestUtils.setField(service, "libraryMapper", libraries);
        ReflectionTestUtils.setField(service, "mediaPublicationGuard", mock(AppMediaPublicationGuard.class));
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
        when(records.insertAuditRecord(any(AuditRecord.class))).thenReturn(1);
        when(audits.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(1);
        when(appeals.insertAuditAppeal(any(AuditAppeal.class))).thenReturn(1);
        when(tasks.insertAuditTask(any(AuditTask.class))).thenAnswer(inv -> {
            ((AuditTask) inv.getArgument(0)).setId(APPEAL_TASK);
            return 1;
        });
    }

    // ───────────────────────── 申诉提交 ─────────────────────────

    @Test
    void onlyTheAuthorMayAppeal() {
        rejectedActivity();

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.submitAppeal(AuditTask.BIZ_ACTIVITY, BIZ_ID, STRANGER, "我没违规", null));

        assertEquals(Integer.valueOf(HttpStatus.FORBIDDEN), error.getCode());
        assertEquals(AuditTaskServiceImpl.ERROR_APPEAL_FORBIDDEN, error.getDetailMessage());
        verify(tasks, never()).insertAuditTask(any());
        verify(appeals, never()).insertAuditAppeal(any());
    }

    @Test
    void appealOnlyAgainstARejectionOrTakeDown() {
        CmsActivity live = activity(1L, 1);
        when(activities.selectCmsActivityById(BIZ_ID)).thenReturn(live);
        when(tasks.selectAuditTaskList(any(AuditTask.class)))
                .thenReturn(Collections.singletonList(task(REJECTED_TASK, AuditTask.STATUS_APPROVED, "user_publish")));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.submitAppeal(AuditTask.BIZ_ACTIVITY, BIZ_ID, AUTHOR, "我没违规", null));

        assertEquals(AuditTaskServiceImpl.ERROR_APPEAL_NOT_ELIGIBLE, error.getDetailMessage());
        verify(tasks, never()).insertAuditTask(any());
    }

    @Test
    void authorAppealQueuesAppealTaskWithRejectedSnapshotAndStandardDue() {
        rejectedActivity();

        AuditAppeal appeal = service.submitAppeal(AuditTask.BIZ_ACTIVITY, BIZ_ID, AUTHOR, "  活动信息真实  ",
                "{\"images\":[\"https://img.example/1.png\"]}");

        ArgumentCaptor<AuditTask> task = ArgumentCaptor.forClass(AuditTask.class);
        verify(tasks).insertAuditTask(task.capture());
        assertEquals(AuditTaskServiceImpl.SOURCE_APPEAL, task.getValue().getSourceType());
        assertEquals(AuditTask.BIZ_ACTIVITY, task.getValue().getBizType());
        assertEquals(Long.valueOf(AUTHOR), task.getValue().getSubmitterId());
        assertEquals(AuditTask.STATUS_PENDING, task.getValue().getStatus());
        assertEquals("{\"bizType\":\"ACTIVITY\"}", task.getValue().getSnapshotJson());
        Date due = Date.from(NOW.plusSeconds(24 * 3600L));
        assertEquals(due, task.getValue().getDeadlineAt(), "无紧急举报原因走 24h");
        assertEquals(Long.valueOf(APPEAL_TASK), appeal.getTaskId());
        assertEquals(AuditAppeal.STATE_SUBMITTED, appeal.getState());
        assertEquals("活动信息真实", appeal.getReason());
        assertEquals(due, appeal.getDueAt());
        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(records).insertAuditRecord(record.capture());
        assertEquals(AuditRecord.ACTION_SUBMIT, record.getValue().getAction());
        assertEquals(Long.valueOf(APPEAL_TASK), record.getValue().getTaskId());
    }

    @Test
    void appealAgainstUrgentReportTakeDownGetsFifteenMinuteDue() {
        rejectedActivity();
        AuditRecord report = new AuditRecord();
        report.setTaskId(REJECTED_TASK);
        report.setAction(AuditRecord.ACTION_REPORT);
        report.setOperatorId(REPORTER);
        report.setReasonCode("DANGEROUS");
        when(records.selectAuditRecordList(any(AuditRecord.class))).thenReturn(Collections.singletonList(report));

        AuditAppeal appeal = service.submitAppeal(AuditTask.BIZ_ACTIVITY, BIZ_ID, AUTHOR, "已整改", null);

        assertEquals(Date.from(NOW.plusSeconds(15 * 60L)), appeal.getDueAt());
    }

    @Test
    void oneAppealPerContentPerMember() {
        rejectedActivity();
        when(appeals.selectByBizMember(AuditTask.BIZ_ACTIVITY, BIZ_ID, AUTHOR)).thenReturn(new AuditAppeal());

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.submitAppeal(AuditTask.BIZ_ACTIVITY, BIZ_ID, AUTHOR, "再申诉一次", null));
        assertEquals(AuditTaskServiceImpl.ERROR_APPEAL_DUPLICATE, error.getDetailMessage());
        verify(tasks, never()).insertAuditTask(any());

        // 并发双提交：预检都通过，唯一键 uk_audit_appeal_biz_member 拦下第二条。
        when(appeals.selectByBizMember(AuditTask.BIZ_ACTIVITY, BIZ_ID, AUTHOR)).thenReturn(null);
        when(appeals.insertAuditAppeal(any(AuditAppeal.class))).thenThrow(new DuplicateKeyException("uk"));
        ServiceException race = assertThrows(ServiceException.class,
                () -> service.submitAppeal(AuditTask.BIZ_ACTIVITY, BIZ_ID, AUTHOR, "并发", null));
        assertEquals(Integer.valueOf(HttpStatus.CONFLICT), race.getCode());
        assertEquals(AuditTaskServiceImpl.ERROR_APPEAL_DUPLICATE, race.getDetailMessage());
    }

    // ───────────────────────── 申诉裁决 ─────────────────────────

    @Test
    void acceptedAppealRestoresContentThroughApprovalPathAndNotifiesAuthor() {
        AuditAppeal appeal = openAppealTask(AuditTask.BIZ_ACTIVITY);
        when(activities.reopenAuditDecision(BIZ_ID, 2L, 2, 0L, null)).thenReturn(1);

        assertEquals(1, service.review(APPEAL_TASK, REVIEWER, decide(AuditTask.STATUS_APPROVED, null)));

        verify(activities).reopenAuditDecision(BIZ_ID, 2L, 2, 0L, null);
        ArgumentCaptor<CmsActivity> restored = ArgumentCaptor.forClass(CmsActivity.class);
        verify(activities).updateCmsActivity(restored.capture());
        assertEquals(Long.valueOf(1L), restored.getValue().getStatus());
        assertEquals(Long.valueOf(1L), restored.getValue().getPublishStatus());
        verify(appeals).finishAppeal(eq(appeal.getId()), eq(AuditAppeal.STATE_ACCEPTED), eq(REVIEWER), any(Date.class));
        verify(im).sendSystem(eq(AUTHOR), any(), eq("申诉已通过"), contains("你的申诉成立"));
    }

    @Test
    void rejectedAppealKeepsContentDown() {
        AuditAppeal appeal = openAppealTask(AuditTask.BIZ_ACTIVITY);

        assertEquals(1, service.review(APPEAL_TASK, REVIEWER, decide(AuditTask.STATUS_REJECTED, "仍违规")));

        verify(activities, never()).reopenAuditDecision(any(), any(), any(), any(), any());
        verify(activities, never()).updateCmsActivity(any());
        verify(appeals).finishAppeal(eq(appeal.getId()), eq(AuditAppeal.STATE_REJECTED), eq(REVIEWER), any(Date.class));
        verify(im).sendSystem(eq(AUTHOR), any(), eq("申诉未通过"), contains("原处理维持"));
    }

    @Test
    void acceptedAppealOnChangedContentRollsBack() {
        openAppealTask(AuditTask.BIZ_ACTIVITY);
        when(activities.reopenAuditDecision(BIZ_ID, 2L, 2, 0L, null)).thenReturn(0);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.review(APPEAL_TASK, REVIEWER, decide(AuditTask.STATUS_APPROVED, null)));

        assertEquals(AuditTaskServiceImpl.ERROR_APPEAL_STALE, error.getDetailMessage());
        verify(tasks, never()).updateAuditTaskReviewCas(any(), any(), any(), any(), any(), any());
        verify(appeals, never()).finishAppeal(any(), any(), any(), any());
    }

    @Test
    void acceptedTemplateAppealReopensThenPassesPendingSnapshotCheck() {
        CmsMemberTemplate rejected = template(2L, 2, 0L);
        CmsMemberTemplate reopened = template(0L, 0, 0L);
        String snapshot = TemplateAuditSnapshot.personal(rejected);
        AuditTask appealTask = task(APPEAL_TASK, AuditTask.STATUS_PENDING, AuditTaskServiceImpl.SOURCE_APPEAL);
        appealTask.setBizType(AuditTask.BIZ_TEMPLATE);
        appealTask.setSnapshotJson(snapshot);
        when(tasks.selectAuditTaskById(APPEAL_TASK)).thenReturn(appealTask);
        when(tasks.selectAuditTaskByIdForUpdate(APPEAL_TASK)).thenReturn(appealTask);
        when(appeals.selectByTaskIdForUpdate(APPEAL_TASK)).thenReturn(appeal(AuditTask.BIZ_TEMPLATE));
        when(templates.selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(BIZ_ID)))
                .thenReturn(Collections.singletonList(rejected), Collections.singletonList(rejected),
                        Collections.singletonList(reopened));
        when(templates.reopenAuditDecision(BIZ_ID, AUTHOR, 2L, 2, 0L)).thenReturn(1);
        when(templates.applyAuditDecision(any(CmsMemberTemplate.class), eq(AUTHOR))).thenReturn(1);
        reviewCasSucceeds(AuditTask.STATUS_APPROVED);
        when(appeals.finishAppeal(any(), any(), any(), any())).thenReturn(1);
        AuditReviewDTO decision = decide(AuditTask.STATUS_APPROVED, null);
        decision.setExpectedVersion(VERSION);

        assertEquals(1, service.review(APPEAL_TASK, REVIEWER, decision));

        verify(templates).reopenAuditDecision(BIZ_ID, AUTHOR, 2L, 2, 0L);
        ArgumentCaptor<CmsMemberTemplate> applied = ArgumentCaptor.forClass(CmsMemberTemplate.class);
        verify(templates).applyAuditDecision(applied.capture(), eq(AUTHOR));
        assertEquals(Long.valueOf(1L), applied.getValue().getStatus());
    }

    @Test
    void claimingAnAppealTaskMovesAppealToReviewing() {
        when(tasks.claimAuditTask(eq(APPEAL_TASK), eq(REVIEWER), any(Date.class))).thenReturn(1);
        when(tasks.selectAuditTaskById(APPEAL_TASK))
                .thenReturn(task(APPEAL_TASK, AuditTask.STATUS_PENDING, AuditTaskServiceImpl.SOURCE_APPEAL));

        service.claim(APPEAL_TASK, REVIEWER);

        verify(appeals).markReviewing(APPEAL_TASK);
    }

    @Test
    void claimingAnOrdinaryTaskLeavesAppealsAlone() {
        when(tasks.claimAuditTask(eq(REJECTED_TASK), eq(REVIEWER), any(Date.class))).thenReturn(1);
        when(tasks.selectAuditTaskById(REJECTED_TASK))
                .thenReturn(task(REJECTED_TASK, AuditTask.STATUS_PENDING, "user_publish"));

        service.claim(REJECTED_TASK, REVIEWER);

        verify(appeals, never()).markReviewing(any());
    }

    // ───────────────────────── 撤销 ─────────────────────────

    @Test
    void activityRejectionRevertInsideWindowReopensContent() {
        AuditRecord decision = revertableDecision(AuditTask.BIZ_ACTIVITY, AuditTask.STATUS_REJECTED, 300L);
        when(activities.reopenAuditDecision(BIZ_ID, 2L, 2, 0L, decision.getCreateTime())).thenReturn(1);

        AuditRevertResultVO result = service.revert(REJECTED_TASK, REVIEWER, false, revertRequest());

        assertEquals(AuditTask.STATUS_PENDING, result.getStatus());
        verify(activities).reopenAuditDecision(BIZ_ID, 2L, 2, 0L, decision.getCreateTime());
    }

    @Test
    void activityApprovalRevertInsideWindowTakesItBackToPending() {
        AuditRecord decision = revertableDecision(AuditTask.BIZ_ACTIVITY, AuditTask.STATUS_APPROVED, 300L);
        when(activities.reopenAuditDecision(BIZ_ID, 1L, 1, 1L, decision.getCreateTime())).thenReturn(1);

        assertEquals(AuditTask.STATUS_PENDING,
                service.revert(REJECTED_TASK, REVIEWER, false, revertRequest()).getStatus());
    }

    @Test
    void revertOutsideWindowIsRefused() {
        revertableDecision(AuditTask.BIZ_ACTIVITY, AuditTask.STATUS_REJECTED, 11 * 60L);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.revert(REJECTED_TASK, REVIEWER, false, revertRequest()));

        assertEquals(AuditTaskServiceImpl.ERROR_REVERT_EXPIRED, error.getDetailMessage());
        verify(tasks, never()).revertAuditTaskDecisionCas(any(), any(), any(), any(), any());
        verify(activities, never()).reopenAuditDecision(any(), any(), any(), any(), any());
    }

    @Test
    void revertAfterContentChangedIsRefused() {
        revertableDecision(AuditTask.BIZ_ACTIVITY, AuditTask.STATUS_REJECTED, 300L);
        when(activities.reopenAuditDecision(any(), any(), any(), any(), any())).thenReturn(0);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.revert(REJECTED_TASK, REVIEWER, false, revertRequest()));

        assertEquals(Integer.valueOf(HttpStatus.CONFLICT), error.getCode());
        assertEquals(AuditTaskServiceImpl.ERROR_REVERT_STALE, error.getDetailMessage());
        verify(records, never()).insertAuditRecord(any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void revertAfterANewerReviewRoundIsRefused() {
        revertableDecision(AuditTask.BIZ_ACTIVITY, AuditTask.STATUS_REJECTED, 300L);
        when(tasks.selectAuditTaskList(any(AuditTask.class))).thenReturn(Arrays.asList(
                task(REJECTED_TASK + 5, AuditTask.STATUS_PENDING, "user_publish"),
                task(REJECTED_TASK, AuditTask.STATUS_REJECTED, "user_publish")));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.revert(REJECTED_TASK, REVIEWER, false, revertRequest()));

        assertEquals(AuditTaskServiceImpl.ERROR_REVERT_STALE, error.getDetailMessage());
        verify(activities, never()).reopenAuditDecision(any(), any(), any(), any(), any());
    }

    @Test
    void topicApprovalRevertHidesTopicAndSquarePosts() {
        revertableDecision(AuditTask.BIZ_TOPIC, AuditTask.STATUS_APPROVED, 120L);
        when(activities.selectActiveActivityIdsByTopicId(BIZ_ID)).thenReturn(Collections.emptyList());
        when(topics.reopenAuditDecision(BIZ_ID, 1, 1, null)).thenReturn(1);

        assertEquals(AuditTask.STATUS_PENDING,
                service.revert(REJECTED_TASK, REVIEWER, false, revertRequest()).getStatus());

        verify(topics).reopenAuditDecision(BIZ_ID, 1, 1, null);
        verify(topicService).syncCreativeSquarePostVisibility(BIZ_ID, false);
    }

    @Test
    void topicRejectionRevertUsesBundleConfigVersionCas() {
        AuditTask task = task(REJECTED_TASK, AuditTask.STATUS_REJECTED, "topic_bundle_v2");
        task.setBizType(AuditTask.BIZ_TOPIC);
        task.setSnapshotJson("{\"schemaVersion\":2,\"bizType\":\"TOPIC\",\"topic\":{\"id\":501,\"configVersion\":7},"
                + "\"bundledTemplates\":[]}");
        revertable(task, AuditRecord.ACTION_REJECT, 120L);
        when(topics.reopenAuditDecision(BIZ_ID, 2, 2, 7L)).thenReturn(0);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.revert(REJECTED_TASK, REVIEWER, false, revertRequest()));

        assertEquals(AuditTaskServiceImpl.ERROR_REVERT_STALE, error.getDetailMessage(), "提交后改过配置 = 内容已变化");
        verify(topics).reopenAuditDecision(BIZ_ID, 2, 2, 7L);
    }

    @Test
    void topicApprovalRevertRefusedOnceASellableActivityExists() {
        revertableDecision(AuditTask.BIZ_TOPIC, AuditTask.STATUS_APPROVED, 120L);
        when(activities.selectActiveActivityIdsByTopicId(BIZ_ID)).thenReturn(Collections.singletonList(9L));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.revert(REJECTED_TASK, REVIEWER, false, revertRequest()));

        assertEquals(AuditTaskServiceImpl.ERROR_REVERT_STATE, error.getDetailMessage());
        verify(topics, never()).reopenAuditDecision(any(), any(), any(), any());
    }

    @Test
    void templateRejectionRevertRequiresUnchangedSnapshot() {
        CmsMemberTemplate rejected = template(2L, 2, 0L);
        AuditTask task = task(REJECTED_TASK, AuditTask.STATUS_REJECTED, "auto_gate");
        task.setBizType(AuditTask.BIZ_TEMPLATE);
        task.setSnapshotJson(TemplateAuditSnapshot.personal(rejected));
        revertable(task, AuditRecord.ACTION_REJECT, 60L);
        when(tasks.selectAuditTaskByIdForUpdate(REJECTED_TASK)).thenReturn(task);
        when(templates.selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(BIZ_ID)))
                .thenReturn(Collections.singletonList(rejected));
        when(templates.reopenAuditDecision(BIZ_ID, AUTHOR, 2L, 2, 0L)).thenReturn(1);

        assertEquals(AuditTask.STATUS_PENDING,
                service.revert(REJECTED_TASK, REVIEWER, false, revertRequest()).getStatus());

        CmsMemberTemplate edited = template(2L, 2, 0L);
        edited.setTitle("作者改过标题");
        when(templates.selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(BIZ_ID)))
                .thenReturn(Collections.singletonList(edited));
        ServiceException error = assertThrows(ServiceException.class,
                () -> service.revert(REJECTED_TASK, REVIEWER, false, revertRequest()));
        assertEquals(AuditTaskServiceImpl.ERROR_REVERT_STALE, error.getDetailMessage());
    }

    @Test
    void templateLibraryApprovalRevertReopensProjection() {
        CmsTemplateLibrary approved = library(1L, 1L);
        AuditTask task = task(REJECTED_TASK, AuditTask.STATUS_APPROVED, "template_market");
        task.setBizType(AuditTask.BIZ_TEMPLATE_LIBRARY);
        task.setSnapshotJson(TemplateAuditSnapshot.market(approved));
        revertable(task, AuditRecord.ACTION_APPROVE, 60L);
        when(tasks.selectAuditTaskByIdForUpdate(REJECTED_TASK)).thenReturn(task);
        when(libraries.selectCmsTemplateLibraryById(BIZ_ID)).thenReturn(approved);
        when(libraries.selectByMemberTemplateForUpdate(600L)).thenReturn(Collections.singletonList(approved));
        when(libraries.reopenAuditDecision(BIZ_ID, AUTHOR, 600L, 1L, 1L)).thenReturn(1);

        assertEquals(AuditTask.STATUS_PENDING,
                service.revert(REJECTED_TASK, REVIEWER, false, revertRequest()).getStatus());
        verify(libraries).reopenAuditDecision(BIZ_ID, AUTHOR, 600L, 1L, 1L);
    }

    @Test
    void appealDecisionsAreNotRevertable() {
        AuditTask task = task(APPEAL_TASK, AuditTask.STATUS_APPROVED, AuditTaskServiceImpl.SOURCE_APPEAL);
        when(tasks.selectAuditTaskById(APPEAL_TASK)).thenReturn(task);
        AuditRevertDTO request = revertRequest();

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.revert(APPEAL_TASK, REVIEWER, true, request));

        assertEquals(AuditTaskServiceImpl.ERROR_REVERT_TYPE, error.getDetailMessage());
        verify(tasks, never()).revertAuditTaskDecisionCas(any(), any(), any(), any(), any());
    }

    /**
     * AUDIT-INT-2(#383 × #384):举报单裁决的是「已公开内容是否违规」,不是作者提交的待审版本。
     * reopenContent 一律写回 0/0/0 待审,而举报单「不成立」走 applyModeration 早退不动内容
     * ⇒ 撤销后再判一次,在架主题就永久停在 status=0。举报单(含内容安全复核上架单)不开放撤销。
     */
    @Test
    void reportDecisionsAreNotRevertable() {
        for (Integer status : Arrays.asList(AuditTask.STATUS_APPROVED, AuditTask.STATUS_REJECTED)) {
            for (String source : Arrays.asList("report", "content_safety_relist")) {
                AuditTask task = task(REJECTED_TASK, status, source);
                task.setBizType(AuditTask.BIZ_TOPIC);
                revertable(task, AuditTask.STATUS_APPROVED.equals(status)
                        ? AuditRecord.ACTION_APPROVE : AuditRecord.ACTION_REJECT, 60L);
                when(activities.selectActiveActivityIdsByTopicId(BIZ_ID)).thenReturn(Collections.emptyList());
                when(topics.reopenAuditDecision(any(), any(), any(), any())).thenReturn(1);

                ServiceException error = assertThrows(ServiceException.class,
                        () -> service.revert(REJECTED_TASK, REVIEWER, true, revertRequest()), source + "/" + status);

                assertEquals(AuditTaskServiceImpl.ERROR_REVERT_TYPE, error.getDetailMessage(), source + "/" + status);
            }
        }
        verify(topics, never()).reopenAuditDecision(any(), any(), any(), any());
        verify(tasks, never()).revertAuditTaskDecisionCas(any(), any(), any(), any(), any());
    }

    /**
     * AUDIT-INT-3(#376/#380 × #384):先发后审的内容在抽检单(post_review_flag)「通过」前已经在架。
     * 撤销通过会把它 CAS 回 0/0/0 待审,造出一个从未存在过的「下架待审」态(活动已售票也照样下架)。
     * 抽检通过不开放撤销;抽检驳回(已把内容下架)的撤销仍照常回待审。
     */
    @Test
    void postReviewSpotCheckApprovalIsNotRevertableButRejectionStillIs() {
        AuditTask approved = task(REJECTED_TASK, AuditTask.STATUS_APPROVED, "post_review_flag");
        revertable(approved, AuditRecord.ACTION_APPROVE, 60L);
        when(activities.reopenAuditDecision(any(), any(), any(), any(), any())).thenReturn(1);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.revert(REJECTED_TASK, REVIEWER, true, revertRequest()));
        assertEquals(AuditTaskServiceImpl.ERROR_REVERT_TYPE, error.getDetailMessage());
        verify(activities, never()).reopenAuditDecision(any(), any(), any(), any(), any());

        AuditTask rejected = task(REJECTED_TASK, AuditTask.STATUS_REJECTED, "post_review_flag");
        AuditRecord decision = revertable(rejected, AuditRecord.ACTION_REJECT, 60L);
        when(activities.reopenAuditDecision(BIZ_ID, 2L, 2, 0L, decision.getCreateTime())).thenReturn(1);

        assertEquals(AuditTask.STATUS_PENDING,
                service.revert(REJECTED_TASK, REVIEWER, true, revertRequest()).getStatus());
    }

    /**
     * AUDIT-INT-6(#381 × #384):被驳回的活动有未结申诉时作者修改重提 —— 作者选择改内容即放弃申诉:
     * 申诉审核单撤销、申诉记录置 WITHDRAWN、通知作者,编辑照常建新审核单,不再整单回滚报通用错误。
     */
    @Test
    void authorEditWhileAppealOpenWithdrawsAppealAndContinues() {
        AuditTask appealTask = task(APPEAL_TASK, AuditTask.STATUS_PENDING, AuditTaskServiceImpl.SOURCE_APPEAL);
        when(tasks.selectOpenAuditTaskForUpdate(AuditTask.BIZ_ACTIVITY, BIZ_ID)).thenReturn(appealTask);
        when(tasks.cancelOpenAuditTaskCas(APPEAL_TASK, VERSION)).thenReturn(1);
        when(appeals.withdrawByTask(eq(APPEAL_TASK), any(Date.class))).thenReturn(1);
        when(tasks.insertAuditTask(any(AuditTask.class))).thenAnswer(inv -> {
            ((AuditTask) inv.getArgument(0)).setId(72L);
            return 1;
        });

        Long taskId = service.submitOrRefreshActivity(BIZ_ID, AUTHOR, "user_republish", "{\"bizType\":\"ACTIVITY\"}");

        assertEquals(Long.valueOf(72L), taskId);
        verify(tasks).cancelOpenAuditTaskCas(APPEAL_TASK, VERSION);
        verify(appeals).withdrawByTask(eq(APPEAL_TASK), any(Date.class));
        verify(im).sendSystem(eq(AUTHOR), any(), eq("申诉已撤回"), contains("申诉已自动撤回"));
        ArgumentCaptor<AuditTask> created = ArgumentCaptor.forClass(AuditTask.class);
        verify(tasks).insertAuditTask(created.capture());
        assertEquals("user_republish", created.getValue().getSourceType());
    }

    @Test
    void topicEditLockWithdrawsOpenAppealInsteadOfThrowing() {
        AuditTask appealTask = task(APPEAL_TASK, AuditTask.STATUS_PENDING, AuditTaskServiceImpl.SOURCE_APPEAL);
        appealTask.setBizType(AuditTask.BIZ_TOPIC);
        when(tasks.selectOpenAuditTaskForUpdate(AuditTask.BIZ_TOPIC, BIZ_ID)).thenReturn(appealTask);
        when(tasks.cancelOpenAuditTaskCas(APPEAL_TASK, VERSION)).thenReturn(1);
        when(appeals.withdrawByTask(eq(APPEAL_TASK), any(Date.class))).thenReturn(1);

        assertFalse(service.lockOpenTopicBundleTask(BIZ_ID));

        verify(appeals).withdrawByTask(eq(APPEAL_TASK), any(Date.class));
        verify(im).sendSystem(eq(AUTHOR), any(), eq("申诉已撤回"), contains("申诉已自动撤回"));
    }

    /** AUDIT-INT-9:withdrawOpenAppeal 只撤申诉单(WITHDRAWN + 通知作者);没有未结申诉或开着的是别的单则不动。 */
    @Test
    void withdrawOpenAppealClosesOnlyAppealTasks() {
        AuditTask appealTask = task(APPEAL_TASK, AuditTask.STATUS_PENDING, AuditTaskServiceImpl.SOURCE_APPEAL);
        appealTask.setBizType(AuditTask.BIZ_TEMPLATE_LIBRARY);
        when(tasks.selectOpenAuditTaskForUpdate(AuditTask.BIZ_TEMPLATE_LIBRARY, BIZ_ID)).thenReturn(appealTask);
        when(tasks.cancelOpenAuditTaskCas(APPEAL_TASK, VERSION)).thenReturn(1);
        when(appeals.withdrawByTask(eq(APPEAL_TASK), any(Date.class))).thenReturn(1);

        assertTrue(service.withdrawOpenAppeal(AuditTask.BIZ_TEMPLATE_LIBRARY, BIZ_ID, "你撤下了被申诉的内容"));
        verify(appeals).withdrawByTask(eq(APPEAL_TASK), any(Date.class));
        verify(im).sendSystem(eq(AUTHOR), any(), eq("申诉已撤回"), contains("你撤下了被申诉的内容"));

        AuditTask review = task(73L, AuditTask.STATUS_PENDING, "template_market");
        when(tasks.selectOpenAuditTaskForUpdate(AuditTask.BIZ_TEMPLATE, BIZ_ID)).thenReturn(review);
        assertFalse(service.withdrawOpenAppeal(AuditTask.BIZ_TEMPLATE, BIZ_ID, "x"));
        verify(tasks, never()).cancelOpenAuditTaskCas(eq(73L), any());
    }

    /**
     * AUDIT-INT-7:内容安全留置(content_safety_hold=1)的内容,申诉不能成立恢复、不能撤销恢复,也不能提交申诉;
     * 作者必须换图重新提交,由人工审核新版本(通过时清留置)。
     */
    @Test
    void acceptedAppealCannotRepublishContentUnderSafetyHold() {
        openAppealTask(AuditTask.BIZ_ACTIVITY);
        when(activities.selectContentSafetyHold(BIZ_ID)).thenReturn(1);
        when(activities.reopenAuditDecision(any(), any(), any(), any(), any())).thenReturn(1);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.review(APPEAL_TASK, REVIEWER, decide(AuditTask.STATUS_APPROVED, null)));

        assertEquals(AuditTaskServiceImpl.ERROR_CONTENT_SAFETY_HOLD, error.getDetailMessage());
        verify(activities, never()).reopenAuditDecision(any(), any(), any(), any(), any());
        verify(activities, never()).updateCmsActivity(any());
    }

    @Test
    void revertCannotReopenContentUnderSafetyHold() {
        revertableDecision(AuditTask.BIZ_TOPIC, AuditTask.STATUS_REJECTED, 60L);
        when(topics.selectContentSafetyHold(BIZ_ID)).thenReturn(1);
        when(topics.reopenAuditDecision(any(), any(), any(), any())).thenReturn(1);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.revert(REJECTED_TASK, REVIEWER, true, revertRequest()));

        assertEquals(AuditTaskServiceImpl.ERROR_CONTENT_SAFETY_HOLD, error.getDetailMessage());
        verify(topics, never()).reopenAuditDecision(any(), any(), any(), any());
    }

    @Test
    void appealIsRefusedUpFrontForContentUnderSafetyHold() {
        rejectedActivity();
        when(activities.selectContentSafetyHold(BIZ_ID)).thenReturn(1);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.submitAppeal(AuditTask.BIZ_ACTIVITY, BIZ_ID, AUTHOR, "我没违规", null));

        assertEquals(AuditTaskServiceImpl.ERROR_CONTENT_SAFETY_HOLD, error.getDetailMessage());
        verify(appeals, never()).insertAuditAppeal(any());
    }

    @Test
    void humanApprovalOfAFreshSubmissionClearsSafetyHold() {
        AuditTask resubmitted = task(74L, AuditTask.STATUS_PENDING, "user_republish");
        when(tasks.selectAuditTaskById(74L)).thenReturn(resubmitted);
        when(tasks.updateAuditTaskReviewCas(eq(74L), eq(AuditTask.STATUS_PENDING), eq(AuditTask.STATUS_APPROVED),
                eq(REVIEWER), eq(VERSION), any(Date.class))).thenReturn(1);

        assertEquals(1, service.review(74L, REVIEWER, decide(AuditTask.STATUS_APPROVED, null)));

        verify(activities).clearContentSafetyHold(BIZ_ID);
    }

    /**
     * AUDIT-INT-4(#383 × #384):举报成立下架模板时,先把下架前的公开态记进举报单快照(preTakedown),
     * 申诉成立按它原样恢复:原来上架的回到上架,原来没上架的保持不上架;申诉能成立,不再恒 409。
     */
    @Test
    void upheldTemplateReportRecordsPreTakedownStateBeforeTakingDown() throws Exception {
        CmsMemberTemplate listed = template(1L, 1, 1L);
        listed.setReason("");
        CmsTemplateLibrary projection = library(1L, 1L);
        projection.setId(601L);
        projection.setMemberTemplateId(BIZ_ID);
        AuditTask report = task(APPEAL_TASK, AuditTask.STATUS_PENDING, "report");
        report.setBizType(AuditTask.BIZ_TEMPLATE);
        report.setSnapshotJson("{\"bizType\":\"TEMPLATE\",\"bizId\":501,\"reason\":\"侵权\"}");
        when(tasks.selectAuditTaskById(APPEAL_TASK)).thenReturn(report);
        when(tasks.selectAuditTaskByIdForUpdate(APPEAL_TASK)).thenReturn(report);
        when(templates.selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(BIZ_ID)))
                .thenReturn(Collections.singletonList(listed));
        when(templates.selectCmsMemberTemplateById(BIZ_ID)).thenReturn(listed);
        when(libraries.selectByMemberTemplateForUpdate(BIZ_ID)).thenReturn(Collections.singletonList(projection));
        when(templates.takeDownReported(eq(BIZ_ID), any())).thenReturn(1);
        when(tasks.updateTaskSnapshot(eq(APPEAL_TASK), any())).thenReturn(1);
        reviewCasSucceeds(AuditTask.STATUS_REJECTED);
        AuditReviewDTO decision = decide(AuditTask.STATUS_REJECTED, "侵权成立");
        decision.setExpectedVersion(VERSION);

        assertEquals(1, service.review(APPEAL_TASK, REVIEWER, decision));

        ArgumentCaptor<String> snapshot = ArgumentCaptor.forClass(String.class);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(tasks, templates);
        order.verify(tasks).updateTaskSnapshot(eq(APPEAL_TASK), snapshot.capture());
        order.verify(templates).takeDownReported(eq(BIZ_ID), any());
        com.fasterxml.jackson.databind.JsonNode pre = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(snapshot.getValue()).path("preTakedown");
        assertEquals("侵权", new com.fasterxml.jackson.databind.ObjectMapper().readTree(snapshot.getValue())
                .path("reason").asText(), "举报证据不丢");
        assertEquals(1, pre.path("template").path("status").asInt());
        assertEquals(1, pre.path("template").path("publishStatus").asInt());
        assertEquals(601L, pre.path("libraries").get(0).path("id").asLong());
        assertEquals(1, pre.path("libraries").get(0).path("publishStatus").asInt());
    }

    @Test
    void acceptedAppealOnReportTakedownRestoresTheListedState() {
        acceptReportTakedownAppeal(1L);

        verify(templates).restoreReportTakedown(BIZ_ID, AUTHOR, 1L, 1, 1L, "");
        verify(libraries).restoreReportTakedown(601L, 1L, 1L, "");
        verify(templates, never()).reopenAuditDecision(any(), any(), any(), any(), any());
        verify(templates, never()).applyAuditDecision(any(), any());
        verify(appeals).finishAppeal(eq(3L), eq(AuditAppeal.STATE_ACCEPTED), eq(REVIEWER), any(Date.class));
    }

    @Test
    void acceptedAppealOnReportTakedownKeepsAnUnlistedTemplateUnlisted() {
        acceptReportTakedownAppeal(0L);

        verify(templates).restoreReportTakedown(BIZ_ID, AUTHOR, 1L, 1, 0L, "");
    }

    private void acceptReportTakedownAppeal(Long prePublishStatus) {
        CmsMemberTemplate takenDown = template(2L, 2, 0L);
        String snapshot = "{\"bizType\":\"TEMPLATE\",\"bizId\":501,\"reason\":\"侵权\",\"preTakedown\":"
                + "{\"template\":{\"status\":1,\"checkStatus\":1,\"publishStatus\":" + prePublishStatus
                + ",\"reason\":\"\"},\"libraries\":[{\"id\":601,\"status\":1,\"publishStatus\":1,\"reason\":\"\"}]}}";
        AuditTask appealTask = task(APPEAL_TASK, AuditTask.STATUS_PENDING, AuditTaskServiceImpl.SOURCE_APPEAL);
        appealTask.setBizType(AuditTask.BIZ_TEMPLATE);
        appealTask.setSnapshotJson(snapshot);
        when(tasks.selectAuditTaskById(APPEAL_TASK)).thenReturn(appealTask);
        when(tasks.selectAuditTaskByIdForUpdate(APPEAL_TASK)).thenReturn(appealTask);
        when(appeals.selectByTaskIdForUpdate(APPEAL_TASK)).thenReturn(appeal(AuditTask.BIZ_TEMPLATE));
        when(templates.selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(BIZ_ID)))
                .thenReturn(Collections.singletonList(takenDown));
        when(templates.restoreReportTakedown(any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(libraries.restoreReportTakedown(any(), any(), any(), any())).thenReturn(1);
        reviewCasSucceeds(AuditTask.STATUS_APPROVED);
        when(appeals.finishAppeal(any(), any(), any(), any())).thenReturn(1);
        AuditReviewDTO decision = decide(AuditTask.STATUS_APPROVED, null);
        decision.setExpectedVersion(VERSION);

        assertEquals(1, service.review(APPEAL_TASK, REVIEWER, decision));
    }

    // ───────────────────────── 作者时间线 ─────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void timelineIsOwnerOnlyAndMasksReporterAndReviewer() {
        when(activities.selectCmsActivityById(BIZ_ID)).thenReturn(activity(2L, 2));
        AuditTask reportTask = task(REJECTED_TASK, AuditTask.STATUS_REJECTED, "report");
        reportTask.setSubmitterId(REPORTER);
        when(tasks.selectAuditTaskList(any(AuditTask.class))).thenReturn(Collections.singletonList(reportTask));
        AuditRecord report = record(1L, AuditRecord.ACTION_REPORT, REPORTER);
        AuditRecord reject = record(2L, AuditRecord.ACTION_REJECT, REVIEWER);
        when(records.selectAuditRecordList(any(AuditRecord.class))).thenReturn(Arrays.asList(reject, report));

        ServiceException forbidden = assertThrows(ServiceException.class,
                () -> service.contentTimeline(AuditTask.BIZ_ACTIVITY, BIZ_ID, STRANGER));
        assertEquals(Integer.valueOf(HttpStatus.FORBIDDEN), forbidden.getCode());

        Map<String, Object> view = service.contentTimeline(AuditTask.BIZ_ACTIVITY, BIZ_ID, AUTHOR);
        List<Map<String, Object>> rounds = (List<Map<String, Object>>) view.get("rounds");
        assertEquals(1, rounds.size());
        assertEquals("REPORT", rounds.get(0).get("kind"));
        assertNull(((AuditTask) rounds.get(0).get("task")).getSubmitterId(), "不暴露举报人");
        assertNull(((AuditTask) rounds.get(0).get("task")).getSnapshotJson());
        List<AuditRecord> timeline = (List<AuditRecord>) rounds.get(0).get("timeline");
        assertEquals(1, timeline.size(), "举报记录不进作者时间线");
        assertEquals(AuditRecord.ACTION_REJECT, timeline.get(0).getAction());
        assertNull(timeline.get(0).getOperatorId(), "不暴露审核员");
        assertEquals("原因", timeline.get(0).getReasonText());
        assertTrue((Boolean) view.get("canAppeal"));
        assertNull(view.get("appeal"));
    }

    @Test
    void timelineShowsAppealStateAndClosesAppealEntry() {
        when(activities.selectCmsActivityById(BIZ_ID)).thenReturn(activity(2L, 2));
        when(tasks.selectAuditTaskList(any(AuditTask.class))).thenReturn(Collections.singletonList(
                task(APPEAL_TASK, AuditTask.STATUS_PENDING, AuditTaskServiceImpl.SOURCE_APPEAL)));
        AuditAppeal appeal = appeal(AuditTask.BIZ_ACTIVITY);
        appeal.setState(AuditAppeal.STATE_REVIEWING);
        when(appeals.selectByBizMember(AuditTask.BIZ_ACTIVITY, BIZ_ID, AUTHOR)).thenReturn(appeal);

        Map<String, Object> view = service.contentTimeline(AuditTask.BIZ_ACTIVITY, BIZ_ID, AUTHOR);

        assertEquals(AuditAppeal.STATE_REVIEWING, ((Map<?, ?>) view.get("appeal")).get("state"));
        assertFalse((Boolean) view.get("canAppeal"));
    }

    /**
     * AUDIT-INT-5 收尾:举报单在独立槽,新开的举报不改变「最近一次审核结论」。作者时间线的 canAppeal
     * 必须与 submitAppeal 同口径(跳过未结举报单),否则页面说不能申诉、接口却能提交。
     */
    @Test
    void timelineStillOffersAppealWhenOnlyAnOpenReportIsNewerThanTheRejection() {
        when(activities.selectCmsActivityById(BIZ_ID)).thenReturn(activity(2L, 2));
        AuditTask report = task(75L, AuditTask.STATUS_PENDING, "report");
        AuditTask rejected = task(REJECTED_TASK, AuditTask.STATUS_REJECTED, "user_publish");
        when(tasks.selectAuditTaskList(any(AuditTask.class))).thenReturn(java.util.Arrays.asList(report, rejected));

        Map<String, Object> view = service.contentTimeline(AuditTask.BIZ_ACTIVITY, BIZ_ID, AUTHOR);

        assertTrue((Boolean) view.get("canAppeal"));
    }

    // ───────────────────────── fixtures ─────────────────────────

    private void rejectedActivity() {
        when(activities.selectCmsActivityById(BIZ_ID)).thenReturn(activity(2L, 2));
        AuditTask rejected = task(REJECTED_TASK, AuditTask.STATUS_REJECTED, "user_publish");
        rejected.setSnapshotJson("{\"bizType\":\"ACTIVITY\"}");
        when(tasks.selectAuditTaskList(any(AuditTask.class))).thenReturn(Collections.singletonList(rejected));
    }

    private AuditAppeal openAppealTask(String bizType) {
        AuditTask appealTask = task(APPEAL_TASK, AuditTask.STATUS_PENDING, AuditTaskServiceImpl.SOURCE_APPEAL);
        appealTask.setBizType(bizType);
        when(tasks.selectAuditTaskById(APPEAL_TASK)).thenReturn(appealTask);
        AuditAppeal appeal = appeal(bizType);
        when(appeals.selectByTaskIdForUpdate(APPEAL_TASK)).thenReturn(appeal);
        when(appeals.finishAppeal(any(), any(), any(), any())).thenReturn(1);
        reviewCasSucceeds(null);
        return appeal;
    }

    private void reviewCasSucceeds(Integer status) {
        when(tasks.updateAuditTaskReviewCas(eq(APPEAL_TASK), eq(AuditTask.STATUS_PENDING),
                status == null ? any() : eq(status), eq(REVIEWER), eq(VERSION), any(Date.class))).thenReturn(1);
    }

    private AuditRecord revertableDecision(String bizType, Integer status, long secondsAgo) {
        AuditTask task = task(REJECTED_TASK, status, "user_publish");
        task.setBizType(bizType);
        return revertable(task, AuditTask.STATUS_APPROVED.equals(status)
                ? AuditRecord.ACTION_APPROVE : AuditRecord.ACTION_REJECT, secondsAgo);
    }

    private AuditRecord revertable(AuditTask task, String action, long secondsAgo) {
        task.setUpdateTime(Date.from(NOW.minusSeconds(secondsAgo)));
        AuditRecord decision = record(801L, action, REVIEWER);
        decision.setCreateTime(Date.from(NOW.minusSeconds(secondsAgo)));
        when(tasks.selectAuditTaskById(task.getId())).thenReturn(task);
        when(tasks.selectAuditTaskList(any(AuditTask.class))).thenReturn(Collections.singletonList(task));
        when(records.selectAuditRecordList(any(AuditRecord.class))).thenReturn(Collections.singletonList(decision));
        when(tasks.revertAuditTaskDecisionCas(eq(task.getId()), eq(task.getStatus()), eq(VERSION),
                any(Date.class), any(Date.class))).thenReturn(1);
        return decision;
    }

    private AuditRevertDTO revertRequest() {
        AuditRevertDTO request = new AuditRevertDTO();
        request.setExpectedVersion(VERSION);
        request.setIdempotencyKey("revert-g4");
        request.setReason("误判");
        return request;
    }

    private static AuditReviewDTO decide(Integer status, String reason) {
        AuditReviewDTO request = new AuditReviewDTO();
        request.setStatus(status);
        request.setReasonText(reason);
        return request;
    }

    private static AuditTask task(long id, Integer status, String source) {
        AuditTask task = new AuditTask();
        task.setId(id);
        task.setBizType(AuditTask.BIZ_ACTIVITY);
        task.setBizId(BIZ_ID);
        task.setSubmitterId(AUTHOR);
        task.setSourceType(source);
        task.setStatus(status);
        task.setVersion(VERSION);
        task.setSnapshotJson("{\"internal\":true}");
        return task;
    }

    private static AuditAppeal appeal(String bizType) {
        AuditAppeal appeal = new AuditAppeal();
        appeal.setId(3L);
        appeal.setBizType(bizType);
        appeal.setBizId(BIZ_ID);
        appeal.setTaskId(APPEAL_TASK);
        appeal.setMemberId(AUTHOR);
        appeal.setState(AuditAppeal.STATE_SUBMITTED);
        return appeal;
    }

    private static AuditRecord record(long id, String action, long operator) {
        AuditRecord record = new AuditRecord();
        record.setId(id);
        record.setTaskId(REJECTED_TASK);
        record.setAction(action);
        record.setOperatorId(operator);
        record.setReasonText("原因");
        record.setCreateTime(Date.from(NOW.minusSeconds(id)));
        return record;
    }

    private static CmsActivity activity(Long status, Integer checkStatus) {
        CmsActivity activity = new CmsActivity();
        activity.setId(BIZ_ID);
        activity.setMemberId(AUTHOR);
        activity.setStatus(status);
        activity.setCheckStatus(checkStatus);
        activity.setDelFlag(0);
        return activity;
    }

    private static CmsMemberTemplate template(Long status, Integer checkStatus, Long publishStatus) {
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(BIZ_ID);
        template.setMemberId(AUTHOR);
        template.setTitle("寻宝");
        template.setStatus(status);
        template.setCheckStatus(checkStatus);
        template.setPublishStatus(publishStatus);
        template.setDraftStatus(1);
        template.setDelFlag(0);
        return template;
    }

    private static CmsTemplateLibrary library(Long status, Long publishStatus) {
        CmsTemplateLibrary row = new CmsTemplateLibrary();
        row.setId(BIZ_ID);
        row.setMemberId(AUTHOR);
        row.setMemberTemplateId(600L);
        row.setTitle("寻宝");
        row.setStatus(status);
        row.setPublishStatus(publishStatus);
        row.setDelFlag(0);
        return row;
    }
}
