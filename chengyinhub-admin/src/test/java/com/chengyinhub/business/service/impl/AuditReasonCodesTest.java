package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditRecord;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.AuditAppealMapper;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.support.AppMediaPublicationGuard;
import com.chengyinhub.business.service.support.AuditReasonCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FOLLOWUP-C-3(gap 12·02 P1-5 / 12·03 P2-2):审核决定可带固定清单原因编码 reasonCodes[],
 * 落 audit_record,作者时间线与驳回通知渲染成中文标签;清单外编码 400(IllegalArgumentException → 400)。
 */
class AuditReasonCodesTest {
    private static final long AUTHOR = 10L;
    private static final long REVIEWER = 900L;
    private static final long BIZ_ID = 501L;
    private static final long TASK = 74L;
    private static final int VERSION = 1;

    private AuditTaskMapper tasks;
    private AuditRecordMapper records;
    private CmsActivityMapper activities;
    private IImService im;
    private AuditTaskServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        tasks = mock(AuditTaskMapper.class);
        records = mock(AuditRecordMapper.class);
        activities = mock(CmsActivityMapper.class);
        im = mock(IImService.class);
        BusinessAuditEventMapper audits = mock(BusinessAuditEventMapper.class);
        service = new AuditTaskServiceImpl();
        ObjectProvider<IImService> imProvider = mock(ObjectProvider.class);
        when(imProvider.getObject()).thenReturn(im);
        ObjectProvider<ICmsTopicService> topicProvider = mock(ObjectProvider.class);
        when(topicProvider.getObject()).thenReturn(mock(ICmsTopicService.class));
        ReflectionTestUtils.setField(service, "imServiceProvider", imProvider);
        ReflectionTestUtils.setField(service, "cmsTopicServiceProvider", topicProvider);
        ReflectionTestUtils.setField(service, "auditTaskMapper", tasks);
        ReflectionTestUtils.setField(service, "auditRecordMapper", records);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", audits);
        ReflectionTestUtils.setField(service, "auditAppealMapper", mock(AuditAppealMapper.class));
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", mock(CmsTopicMapper.class));
        ReflectionTestUtils.setField(service, "mediaPublicationGuard", mock(AppMediaPublicationGuard.class));
        ReflectionTestUtils.setField(service, "clock",
                Clock.fixed(Instant.parse("2026-10-20T08:00:00Z"), ZoneOffset.UTC));
        when(records.insertAuditRecord(any(AuditRecord.class))).thenReturn(1);
        when(audits.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(1);
        AuditTask pending = new AuditTask();
        pending.setId(TASK);
        pending.setBizType(AuditTask.BIZ_ACTIVITY);
        pending.setBizId(BIZ_ID);
        pending.setSubmitterId(AUTHOR);
        pending.setSourceType("user_publish");
        pending.setStatus(AuditTask.STATUS_PENDING);
        pending.setVersion(VERSION);
        when(tasks.selectAuditTaskById(TASK)).thenReturn(pending);
        when(tasks.updateAuditTaskReviewCas(eq(TASK), eq(AuditTask.STATUS_PENDING), any(), eq(REVIEWER), eq(VERSION),
                any(Date.class))).thenReturn(1);
        CmsActivity activity = new CmsActivity();
        activity.setId(BIZ_ID);
        activity.setMemberId(AUTHOR);
        activity.setStatus(0L);
        activity.setCheckStatus(0);
        activity.setDelFlag(0);
        when(activities.selectCmsActivityById(BIZ_ID)).thenReturn(activity);
    }

    private static AuditReviewDTO reject(String reason, String... codes) {
        AuditReviewDTO request = new AuditReviewDTO();
        request.setStatus(AuditTask.STATUS_REJECTED);
        request.setReasonText(reason);
        request.setReasonCodes(codes == null ? null : Arrays.asList(codes));
        return request;
    }

    @Test
    void rejectionStoresNormalizedReasonCodesOnTheAuditRecord() {
        assertEquals(1, service.review(TASK, REVIEWER, reject("封面图含二维码，票价与详情不一致",
                "image_risk", " PRICE_ISSUE ", "IP_INFRINGEMENT", "IMAGE_RISK")));

        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(records).insertAuditRecord(record.capture());
        assertEquals("[\"IMAGE_RISK\",\"PRICE_ISSUE\",\"INTELLECTUAL_PROPERTY\"]", record.getValue().getReasonCodesJson());
        assertEquals(Arrays.asList("图片内容违规", "价格信息有误", "知识产权或内容盗用"), record.getValue().getReasonLabels());
        assertEquals("封面图含二维码，票价与详情不一致", record.getValue().getReasonText());
    }

    @Test
    void unknownReasonCodeIsRefusedBeforeAnyStateChange() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.review(TASK, REVIEWER, reject("原因", "IMAGE_RISK", "NOT_A_CODE")));

        assertTrue(error.getMessage().contains("NOT_A_CODE"), error.getMessage());
        verify(tasks, never()).selectAuditTaskById(anyLong());
        verify(records, never()).insertAuditRecord(any());
    }

    @Test
    void reviewWithoutReasonCodesKeepsWorkingAndStoresNone() {
        assertEquals(1, service.review(TASK, REVIEWER, reject("信息不完整", (String[]) null)));

        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(records).insertAuditRecord(record.capture());
        assertNull(record.getValue().getReasonCodesJson());
        assertTrue(record.getValue().getReasonCodes().isEmpty());
    }

    @Test
    void rejectionNoticeRendersChineseLabelsBeforeTheFreeText() {
        service.review(TASK, REVIEWER, reject("地址写错了", "LOCATION_ISSUE", "FALSE_INFO"));

        ArgumentCaptor<String> extra = ArgumentCaptor.forClass(String.class);
        verify(im).sendSystem(eq(AUTHOR), any(), eq("审核未通过"), extra.capture());
        assertTrue(extra.getValue().contains("原因：地点信息有误、虚假信息；地址写错了"), extra.getValue());
        assertTrue(extra.getValue().contains("\"reasonCodes\":[\"LOCATION_ISSUE\",\"FALSE_INFORMATION\"]"), extra.getValue());
        assertTrue(extra.getValue().contains("\"reasonLabels\":[\"地点信息有误\",\"虚假信息\"]"), extra.getValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void authorTimelineReturnsReasonCodesAndLabels() {
        when(activities.selectCmsActivityById(BIZ_ID)).thenAnswer(inv -> {
            CmsActivity rejected = new CmsActivity();
            rejected.setId(BIZ_ID);
            rejected.setMemberId(AUTHOR);
            rejected.setStatus(2L);
            rejected.setCheckStatus(2);
            rejected.setDelFlag(0);
            return rejected;
        });
        AuditTask rejected = new AuditTask();
        rejected.setId(TASK);
        rejected.setBizType(AuditTask.BIZ_ACTIVITY);
        rejected.setBizId(BIZ_ID);
        rejected.setSubmitterId(AUTHOR);
        rejected.setSourceType("user_publish");
        rejected.setStatus(AuditTask.STATUS_REJECTED);
        rejected.setVersion(VERSION + 1);
        when(tasks.selectAuditTaskList(any(AuditTask.class))).thenReturn(Collections.singletonList(rejected));
        AuditRecord decision = new AuditRecord();
        decision.setId(5L);
        decision.setTaskId(TASK);
        decision.setAction(AuditRecord.ACTION_REJECT);
        decision.setOperatorId(REVIEWER);
        decision.setReasonText("图里有联系方式");
        decision.setReasonCodesJson("[\"IMAGE_RISK\",\"MINOR_SAFETY\"]");
        when(records.selectAuditRecordList(any(AuditRecord.class))).thenReturn(Collections.singletonList(decision));

        Map<String, Object> view = service.contentTimeline(AuditTask.BIZ_ACTIVITY, BIZ_ID, AUTHOR);

        List<Map<String, Object>> rounds = (List<Map<String, Object>>) view.get("rounds");
        AuditRecord item = ((List<AuditRecord>) rounds.get(0).get("timeline")).get(0);
        assertNull(item.getOperatorId(), "仍不暴露审核员");
        assertEquals(Arrays.asList("IMAGE_RISK", "MINOR_SAFETY"), item.getReasonCodes());
        assertEquals(Arrays.asList("图片内容违规", "未成年人安全"), item.getReasonLabels());
    }

    @Test
    void catalogIsFixedAndAlignedWithReportReasonCodes() {
        assertEquals(Arrays.asList("TEXT_RISK", "IMAGE_RISK", "FALSE_INFORMATION", "PRICE_ISSUE", "LOCATION_ISSUE",
                "INTELLECTUAL_PROPERTY", "MINOR_SAFETY", "OTHER"), new java.util.ArrayList<>(AuditReasonCodes.codes()));
        for (String shared : Arrays.asList("FALSE_INFORMATION", "INTELLECTUAL_PROPERTY", "MINOR_SAFETY", "OTHER")) {
            assertEquals(com.chengyinhub.business.service.support.CommunityReportPolicy.find(shared).getLabel(),
                    AuditReasonCodes.labels(Collections.singletonList(shared)).get(0), shared);
        }
        assertFalse(AuditReasonCodes.labels(Collections.singletonList("LEGACY_FREE_TEXT")).iterator().hasNext(),
                "历史/未知编码不补造标签");
    }
}
