package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AUDIT-G1-2:活动编辑重提刷新唯一开放内容单,不撞开放任务唯一约束,也不吞掉举报单。 */
class AuditTaskActivityRefreshTest {

    private AuditTaskMapper taskMapper;
    private AuditRecordMapper recordMapper;
    private AuditTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(AuditTaskMapper.class);
        recordMapper = mock(AuditRecordMapper.class);
        service = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(service, "auditTaskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "auditRecordMapper", recordMapper);
        when(taskMapper.refreshOpenAuditSnapshot(anyLong(), anyString(), anyString(), anyString(), anyLong(), any()))
                .thenReturn(1);
        when(recordMapper.insertAuditRecord(any())).thenReturn(1);
    }

    private void givenOpen(String sourceType) {
        AuditTask open = new AuditTask();
        open.setId(55L);
        open.setBizType(AuditTask.BIZ_ACTIVITY);
        open.setBizId(300L);
        open.setSourceType(sourceType);
        open.setStatus(AuditTask.STATUS_PENDING);
        when(taskMapper.selectOpenAuditTaskForUpdate(AuditTask.BIZ_ACTIVITY, 300L)).thenReturn(open);
    }

    @Test
    void openSpotCheckTask_isRefreshedAsBlockingResubmission() {
        givenOpen("post_review_flag");
        assertEquals(55L, service.submitOrRefreshActivity(300L, 9L, "user_republish", "{\"v\":2}"));
        verify(taskMapper).refreshOpenAuditSnapshot(eq(55L), eq(AuditTask.BIZ_ACTIVITY), eq("user_republish"),
                eq("{\"v\":2}"), eq(9L), any());
        verify(taskMapper, never()).insertAuditTask(any());
    }

    @Test
    void openPublishTask_isRefreshedNotDuplicated() {
        givenOpen("user_publish");
        assertEquals(55L, service.submitOrRefreshActivity(300L, 9L, "user_republish", "{}"));
        verify(taskMapper, never()).insertAuditTask(any());
    }

    @Test
    void spotCheckFlag_doesNotDowngradeOpenBlockingTask() {
        givenOpen("user_republish");
        service.submitOrRefreshActivity(300L, 9L, "post_review_flag", "{}");
        verify(taskMapper).refreshOpenAuditSnapshot(eq(55L), eq(AuditTask.BIZ_ACTIVITY), eq("user_republish"),
                eq("{}"), eq(9L), any());
    }

    @Test
    void openReportTask_isNotOverwritten() {
        givenOpen("report");
        assertThrows(IllegalStateException.class,
                () -> service.submitOrRefreshActivity(300L, 9L, "user_republish", "{}"));
        verify(taskMapper, never()).refreshOpenAuditSnapshot(anyLong(), anyString(), anyString(), anyString(), anyLong(), any());
    }

    @Test
    void noOpenTask_createsOne() {
        service.submitOrRefreshActivity(300L, 9L, "user_republish", "{}");
        verify(taskMapper).insertAuditTask(any());
    }
}
