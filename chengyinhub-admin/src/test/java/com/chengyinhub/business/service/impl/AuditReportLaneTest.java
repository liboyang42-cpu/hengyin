package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditRecord;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.support.TopicSupplyReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUDIT-INT-5(#383 × #376/#380/#381):举报单有自己的开放槽(open_guard=2,见
 * migration_audit_report_lane_20261020.sql),与内容审核单(open_guard=1)可以并存。
 * 举报不再并进开着的内容审核单;开着的举报单也不再挡作者编辑/重提;内容审核单不再并进开着的举报单。
 */
class AuditReportLaneTest {

    private static final long TOPIC = 501L;
    private static final long AUTHOR = 10L;
    private static final long REPORTER = 77L;

    private AuditTaskMapper tasks;
    private AuditRecordMapper records;
    private AuditTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        tasks = mock(AuditTaskMapper.class);
        records = mock(AuditRecordMapper.class);
        service = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(service, "auditTaskMapper", tasks);
        ReflectionTestUtils.setField(service, "auditRecordMapper", records);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(AUTHOR);
        when(topics.selectCmsTopicById(TOPIC)).thenReturn(topic);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        when(records.insertAuditRecord(any(AuditRecord.class))).thenReturn(1);
        when(tasks.refreshOpenAuditSnapshot(any(), any(), any(), any(), any(), any())).thenReturn(1);
    }

    private static AuditTask open(long id, String source) {
        AuditTask t = new AuditTask();
        t.setId(id);
        t.setBizType(AuditTask.BIZ_TOPIC);
        t.setBizId(TOPIC);
        t.setSourceType(source);
        t.setStatus(AuditTask.STATUS_PENDING);
        t.setVersion(1);
        return t;
    }

    @Test
    void reportOnTopicUnderContentReviewGetsItsOwnTask() {
        when(tasks.selectAuditTaskList(any(AuditTask.class)))
                .thenReturn(Collections.singletonList(open(70L, "topic_bundle_v2")));
        when(tasks.insertAuditTask(any(AuditTask.class))).thenAnswer(inv -> {
            ((AuditTask) inv.getArgument(0)).setId(71L);
            return 1;
        });

        Long taskId = service.reportContent(AuditTask.BIZ_TOPIC, TOPIC, REPORTER, "侵权");

        assertEquals(Long.valueOf(71L), taskId, "举报不能并进开着的内容审核单");
        ArgumentCaptor<AuditTask> created = ArgumentCaptor.forClass(AuditTask.class);
        verify(tasks).insertAuditTask(created.capture());
        assertEquals("report", created.getValue().getSourceType());
    }

    @Test
    void secondReportJoinsTheOpenReportTaskNotTheReviewTask() {
        when(tasks.selectAuditTaskList(any(AuditTask.class)))
                .thenReturn(Arrays.asList(open(72L, "report"), open(70L, "topic_bundle_v2")));

        assertEquals(Long.valueOf(72L), service.reportContent(AuditTask.BIZ_TOPIC, TOPIC, REPORTER, "侵权"));
        verify(tasks, never()).insertAuditTask(any());
    }

    @Test
    void safetyHoldDoesNotReuseAnOpenReportTask() {
        when(tasks.selectAuditTaskList(any(AuditTask.class)))
                .thenReturn(Collections.singletonList(open(72L, "report")));
        when(tasks.insertAuditTask(any(AuditTask.class))).thenAnswer(inv -> {
            ((AuditTask) inv.getArgument(0)).setId(73L);
            return 1;
        });

        Long taskId = service.submitSafetyHold(AuditTask.BIZ_TOPIC, TOPIC, AUTHOR, "NORMAL", "{}");

        assertNotEquals(Long.valueOf(72L), taskId, "内容安全复核单不能借用举报单");
    }

    @Test
    void openReportDoesNotBlockTopicBundleSubmitOrEditLock() {
        // 内容槽(selectOpenAuditTaskForUpdate 只看 open_guard=1)里没有单:举报单不在这条查询里
        when(tasks.selectOpenAuditTaskForUpdate(AuditTask.BIZ_TOPIC, TOPIC)).thenReturn(null);
        when(tasks.insertAuditTask(any(AuditTask.class))).thenAnswer(inv -> {
            ((AuditTask) inv.getArgument(0)).setId(74L);
            return 1;
        });

        assertEquals(Boolean.FALSE, service.lockOpenTopicBundleTask(TOPIC));
        assertEquals(Long.valueOf(74L), service.submitOrRefreshTopicBundle(TOPIC, AUTHOR, "{\"topic\":{}}"));
    }

    @Test
    void supplyReplacementDoesNotCancelOpenReports() {
        AuditTaskMapper mapper = mock(AuditTaskMapper.class);
        AuditTask report = open(72L, "report");
        AuditTask bundle = open(70L, "topic_bundle_v2");
        when(mapper.selectTopicPublicationTasksForUpdate(TOPIC)).thenReturn(Arrays.asList(bundle, report));
        when(mapper.cancelTopicPublicationTaskCas(70L, 1)).thenReturn(1);
        AuditRecordMapper recordMapper = mock(AuditRecordMapper.class);
        when(recordMapper.insertAuditRecord(any())).thenReturn(1);
        TopicSupplyReviewService supply = new TopicSupplyReviewService(mapper, recordMapper,
                mock(CmsTopicMapper.class), mock(com.chengyinhub.business.mapper.CmsTopicChapterMapper.class));

        supply.invalidate(TOPIC, AUTHOR);

        verify(mapper).cancelTopicPublicationTaskCas(70L, 1);
        verify(mapper, never()).cancelTopicPublicationTaskCas(72L, 1);
    }
}
