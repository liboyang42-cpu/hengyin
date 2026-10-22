package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditAppeal;
import com.chengyinhub.business.domain.AuditRecord;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.mapper.AppMediaAssetMapper;
import com.chengyinhub.business.mapper.AuditAppealMapper;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.service.support.AppMediaPublicationGuard;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O5b-2:申诉证据附件。附件走既有媒体管线(OSS 上传登记 + 媒体内容安全审核),
 * 必须是申诉人本人上传且已 APPROVED,统一落成 media-asset:{id} 并进申诉记录;开关默认关,关时带附件被拒。
 */
class AuditAppealAttachmentTest {
    private static final long AUTHOR = 10L;
    private static final long OTHER = 11L;
    private static final long BIZ_ID = 501L;
    private static final long REJECTED_TASK = 70L;
    private static final long APPEAL_TASK = 71L;

    private AuditTaskMapper tasks;
    private AuditRecordMapper records;
    private AuditAppealMapper appeals;
    private AppMediaReferenceGuard guard;
    private AppMediaAssetMapper assets;
    private AuditTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        tasks = mock(AuditTaskMapper.class);
        records = mock(AuditRecordMapper.class);
        appeals = mock(AuditAppealMapper.class);
        CmsActivityMapper activities = mock(CmsActivityMapper.class);
        guard = mock(AppMediaReferenceGuard.class);
        assets = mock(AppMediaAssetMapper.class);
        service = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(service, "auditTaskMapper", tasks);
        ReflectionTestUtils.setField(service, "auditRecordMapper", records);
        ReflectionTestUtils.setField(service, "auditAppealMapper", appeals);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(service, "mediaPublicationGuard", mock(AppMediaPublicationGuard.class));
        ReflectionTestUtils.setField(service, "appealMediaReferenceGuard", guard);
        ReflectionTestUtils.setField(service, "appMediaAssetMapper", assets);
        ReflectionTestUtils.setField(service, "appealAttachmentsEnabled", true);
        ReflectionTestUtils.setField(service, "clock",
                Clock.fixed(Instant.parse("2026-10-22T08:00:00Z"), ZoneOffset.UTC));

        CmsActivity rejectedActivity = new CmsActivity();
        rejectedActivity.setId(BIZ_ID);
        rejectedActivity.setMemberId(AUTHOR);
        rejectedActivity.setStatus(2L);
        rejectedActivity.setCheckStatus(2);
        rejectedActivity.setDelFlag(0);
        when(activities.selectCmsActivityById(BIZ_ID)).thenReturn(rejectedActivity);
        AuditTask rejected = new AuditTask();
        rejected.setId(REJECTED_TASK);
        rejected.setBizType(AuditTask.BIZ_ACTIVITY);
        rejected.setBizId(BIZ_ID);
        rejected.setSubmitterId(AUTHOR);
        rejected.setSourceType("user_publish");
        rejected.setStatus(AuditTask.STATUS_REJECTED);
        rejected.setVersion(1);
        rejected.setSnapshotJson("{\"bizType\":\"ACTIVITY\"}");
        when(tasks.selectAuditTaskList(any(AuditTask.class))).thenReturn(Collections.singletonList(rejected));
        when(tasks.insertAuditTask(any(AuditTask.class))).thenAnswer(inv -> {
            ((AuditTask) inv.getArgument(0)).setId(APPEAL_TASK);
            return 1;
        });
        when(appeals.insertAuditAppeal(any(AuditAppeal.class))).thenReturn(1);
        when(records.insertAuditRecord(any(AuditRecord.class))).thenReturn(1);
    }

    @Test
    void ownApprovedAttachmentsAreNormalizedAndStoredWithTheAppeal() {
        approvedAsset(31L, "media-asset:31", AUTHOR);
        approvedAsset(32L, "/profile/upload/2026/10/22/abc.jpg", AUTHOR);

        service.submitAppeal(AuditTask.BIZ_ACTIVITY, BIZ_ID, AUTHOR, "图里是现场照片,不是广告",
                "{\"note\":\"见附件\"}", Arrays.asList("media-asset:31", "/profile/upload/2026/10/22/abc.jpg",
                        "media-asset:31", " "));

        ArgumentCaptor<AuditAppeal> appeal = ArgumentCaptor.forClass(AuditAppeal.class);
        verify(appeals).insertAuditAppeal(appeal.capture());
        assertEquals("{\"evidence\":{\"note\":\"见附件\"},\"attachments\":[\"media-asset:31\",\"media-asset:32\"]}",
                appeal.getValue().getEvidenceJson());
        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(records).insertAuditRecord(record.capture());
        assertTrue(record.getValue().getSnapshotJson().contains("media-asset:32"),
                "审核员在申诉单记录里看得到附件引用(经 /business/media-review 预览)");
        verify(guard).requireAttachment(AUTHOR, "media-asset:31", true);
    }

    @Test
    void withoutAttachmentsTheEvidenceShapeIsUnchanged() {
        service.submitAppeal(AuditTask.BIZ_ACTIVITY, BIZ_ID, AUTHOR, "理由", "{\"note\":\"x\"}",
                Collections.emptyList());

        ArgumentCaptor<AuditAppeal> appeal = ArgumentCaptor.forClass(AuditAppeal.class);
        verify(appeals).insertAuditAppeal(appeal.capture());
        assertEquals("{\"note\":\"x\"}", appeal.getValue().getEvidenceJson());
        verify(guard, never()).requireAttachment(anyLong(), anyString(), anyBoolean());
    }

    @Test
    void attachmentNotYetCleanedByContentSafetyIsRejected() {
        when(guard.requireAttachment(AUTHOR, "media-asset:40", true))
                .thenThrow(new ServiceException("媒体尚未审核通过，暂不能公开", 422));

        ServiceException error = assertThrows(ServiceException.class, () -> service.submitAppeal(
                AuditTask.BIZ_ACTIVITY, BIZ_ID, AUTHOR, "理由", null, Collections.singletonList("media-asset:40")));

        assertEquals(AuditTaskServiceImpl.ERROR_APPEAL_ATTACHMENT_INVALID, error.getDetailMessage());
        verify(tasks, never()).insertAuditTask(any());
        verify(appeals, never()).insertAuditAppeal(any());
    }

    /** 守卫对「他人上传但已过审」的媒体放行(公开内容可引用),申诉证据额外要求本人上传。 */
    @Test
    void someoneElsesApprovedMediaIsNotAcceptedAsEvidence() {
        approvedAsset(50L, "media-asset:50", OTHER);

        ServiceException error = assertThrows(ServiceException.class, () -> service.submitAppeal(
                AuditTask.BIZ_ACTIVITY, BIZ_ID, AUTHOR, "理由", null, Collections.singletonList("media-asset:50")));

        assertEquals(AuditTaskServiceImpl.ERROR_APPEAL_ATTACHMENT_INVALID, error.getDetailMessage());
        verify(appeals, never()).insertAuditAppeal(any());
    }

    @Test
    void tooManyAttachmentsAreRejected() {
        ServiceException error = assertThrows(ServiceException.class, () -> service.submitAppeal(
                AuditTask.BIZ_ACTIVITY, BIZ_ID, AUTHOR, "理由", null,
                Arrays.asList("media-asset:1", "media-asset:2", "media-asset:3", "media-asset:4",
                        "media-asset:5", "media-asset:6", "media-asset:7")));

        assertEquals(AuditTaskServiceImpl.ERROR_APPEAL_BAD_REQUEST, error.getDetailMessage());
        verify(guard, never()).requireAttachment(anyLong(), anyString(), anyBoolean());
    }

    @Test
    void switchOffRefusesAttachmentsInsteadOfDroppingThem() {
        ReflectionTestUtils.setField(service, "appealAttachmentsEnabled", false);

        ServiceException error = assertThrows(ServiceException.class, () -> service.submitAppeal(
                AuditTask.BIZ_ACTIVITY, BIZ_ID, AUTHOR, "理由", null, Collections.singletonList("media-asset:31")));

        assertEquals(AuditTaskServiceImpl.ERROR_APPEAL_ATTACHMENTS_DISABLED, error.getDetailMessage());
        verify(appeals, never()).insertAuditAppeal(any());
    }

    @Test
    void legacyFiveArgumentEntryStillWorksWithSwitchOff() {
        ReflectionTestUtils.setField(service, "appealAttachmentsEnabled", false);

        service.submitAppeal(AuditTask.BIZ_ACTIVITY, BIZ_ID, AUTHOR, "理由", null);

        verify(appeals).insertAuditAppeal(any(AuditAppeal.class));
    }

    private void approvedAsset(long id, String reference, long owner) {
        when(guard.requireAttachment(eq(AUTHOR), eq(reference), eq(true))).thenReturn("media-asset:" + id);
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("ownerType", "APP");
        row.put("memberId", owner);
        row.put("reviewState", "APPROVED");
        when(assets.select(id)).thenReturn(row);
    }
}
