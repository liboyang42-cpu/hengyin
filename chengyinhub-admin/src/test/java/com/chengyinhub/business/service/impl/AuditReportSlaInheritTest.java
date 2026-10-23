package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O5b-3:举报 → 处置审核单继承首响时钟。原因命中社区举报政策时,处置单 deadline 收紧到
 * 「举报时刻 + 首响时限」(新建单与汇入已开着的单同口径);只收紧不放宽;开关默认关。
 */
class AuditReportSlaInheritTest {

    private static final long MINUTE = 60_000L;

    private AuditTaskMapper auditTaskMapper;
    private AuditTaskServiceImpl svc;

    @BeforeEach
    void setUp() {
        auditTaskMapper = mock(AuditTaskMapper.class);
        AuditRecordMapper auditRecordMapper = mock(AuditRecordMapper.class);
        svc = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(svc, "auditTaskMapper", auditTaskMapper);
        ReflectionTestUtils.setField(svc, "auditRecordMapper", auditRecordMapper);
        ReflectionTestUtils.setField(svc, "reportSlaInheritEnabled", true);
        when(auditTaskMapper.selectAuditTaskList(any())).thenReturn(Collections.emptyList());
        when(auditTaskMapper.insertAuditTask(any())).thenAnswer(inv -> {
            ((AuditTask) inv.getArgument(0)).setId(1L);
            return 1;
        });
        when(auditRecordMapper.insertAuditRecord(any())).thenReturn(1);
    }

    @Test
    void urgentReasonCodeTightensNewReportTaskToFifteenMinutes() {
        long before = System.currentTimeMillis();
        svc.reportContent(AuditTask.BIZ_SQUARE, 9L, 2L, "MINOR_SAFETY");
        long after = System.currentTimeMillis();

        long minutes = tightenedMinutesFor(1L, before, after);
        assertEquals(15, minutes);
    }

    @Test
    void policyLabelMatchesLikeTheCode() {
        long before = System.currentTimeMillis();
        svc.reportContent(AuditTask.BIZ_SQUARE, 9L, 2L, "自伤或自杀风险");
        long after = System.currentTimeMillis();

        assertEquals(15, tightenedMinutesFor(1L, before, after));
    }

    /** 举报汇入已开着的内容单(非拆槽类型)时,时钟带进那张单 —— 处置它的就是那张单。 */
    @Test
    void reportMergedIntoAnOpenTaskCarriesItsClockIntoThatTask() {
        AuditTask open = new AuditTask();
        open.setId(5L);
        open.setBizType(AuditTask.BIZ_COMMUNITY_POST);
        open.setBizId(9L);
        open.setStatus(AuditTask.STATUS_PENDING);
        open.setSourceType("auto_gate");
        when(auditTaskMapper.selectAuditTaskList(any())).thenReturn(Collections.singletonList(open));

        long before = System.currentTimeMillis();
        svc.reportContent(AuditTask.BIZ_COMMUNITY_POST, 9L, 2L, "DANGEROUS");
        long after = System.currentTimeMillis();

        verify(auditTaskMapper, never()).insertAuditTask(any());
        assertEquals(15, tightenedMinutesFor(5L, before, after));
    }

    @Test
    void standardReasonCarriesTwentyFourHourClock() {
        long before = System.currentTimeMillis();
        svc.reportContent(AuditTask.BIZ_SQUARE, 9L, 2L, "spam");
        long after = System.currentTimeMillis();

        assertEquals(24 * 60, tightenedMinutesFor(1L, before, after));
    }

    @Test
    void freeTextReasonOutsideThePolicyLeavesDeadlineAlone() {
        svc.reportContent(AuditTask.BIZ_SQUARE, 9L, 2L, "用户举报");

        verify(auditTaskMapper, never()).tightenOpenDeadline(anyLong(), any());
    }

    @Test
    void switchOffLeavesDeadlineAlone() {
        ReflectionTestUtils.setField(svc, "reportSlaInheritEnabled", false);

        svc.reportContent(AuditTask.BIZ_SQUARE, 9L, 2L, "MINOR_SAFETY");

        verify(auditTaskMapper, never()).tightenOpenDeadline(anyLong(), any());
    }

    @Test
    void reasonResolutionIsDeterministic() {
        assertEquals("MINOR_SAFETY", AuditTaskServiceImpl.reportPolicyReason(" minor_safety ").getCode());
        assertEquals("SPAM", AuditTaskServiceImpl.reportPolicyReason("垃圾广告或站外导流").getCode());
        assertNull(AuditTaskServiceImpl.reportPolicyReason("广告"));
        assertNull(AuditTaskServiceImpl.reportPolicyReason(null));
    }

    /** SQL 只收紧未结单(PENDING/ESCALATED),不放宽、不碰版本号(不干扰审核 CAS)。 */
    @Test
    void tightenSqlOnlyShortensOpenTasks() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get("..", "chengyinhub-system", "src", "main",
                "resources", "mapper", "business", "AuditTaskMapper.xml")), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");
        int start = xml.indexOf("<update id=\"tightenOpenDeadline\">");
        String statement = xml.substring(start, xml.indexOf("</update>", start));
        assertTrue(statement.contains("status in (0, 3)"), statement);
        assertTrue(statement.contains("deadline_at is null or deadline_at &gt; #{deadline}"), statement);
        assertTrue(!statement.contains("version"), statement);
    }

    private long tightenedMinutesFor(Long taskId, long before, long after) {
        ArgumentCaptor<Date> deadline = ArgumentCaptor.forClass(Date.class);
        verify(auditTaskMapper).tightenOpenDeadline(eq(taskId), deadline.capture());
        long at = deadline.getValue().getTime();
        long minutes = Math.round((at - before) / (double) MINUTE);
        assertTrue(at >= before + minutes * MINUTE && at <= after + minutes * MINUTE, "首响截止应锚在举报时刻");
        return minutes;
    }
}
