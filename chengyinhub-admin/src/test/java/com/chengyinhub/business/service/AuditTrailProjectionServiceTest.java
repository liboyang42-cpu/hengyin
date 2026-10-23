package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.AuditTrailEntryVO;
import com.chengyinhub.business.mapper.AuditTrailMapper;
import com.chengyinhub.business.service.support.AuditTrailProjectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** DOC2 O5c-(a):统一留痕读投影 —— 默认关、按类型路由来源、按时间稳定合并、只读。 */
class AuditTrailProjectionServiceTest {

    private AuditTrailMapper mapper;
    private AuditTrailProjectionService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AuditTrailMapper.class);
        service = new AuditTrailProjectionService(mapper);
        ReflectionTestUtils.setField(service, "enabled", true);
    }

    @Test
    void defaultOffTouchesNoTable() {
        AuditTrailProjectionService fresh = new AuditTrailProjectionService(mapper);
        assertFalse(fresh.isEnabled());
        assertThrows(IllegalStateException.class, () -> fresh.trail("TOPIC", 1L));
        verifyNoInteractions(mapper);
    }

    @Test
    void invalidArgumentsAreRejectedBeforeAnyQuery() {
        assertThrows(IllegalArgumentException.class, () -> service.trail(null, 1L));
        assertThrows(IllegalArgumentException.class, () -> service.trail("TOPIC';--", 1L));
        assertThrows(IllegalArgumentException.class, () -> service.trail("TOPIC", 0L));
        assertThrows(IllegalArgumentException.class, () -> service.trail("TOPIC", null));
        verifyNoInteractions(mapper);
    }

    @Test
    void topicReadsOnlyUnifiedAuditSources() {
        service.trail("TOPIC", 9L);
        verify(mapper).selectAuditRecordTrail(eq("TOPIC"), eq(9L), anyInt());
        verify(mapper).selectBusinessAuditEventTrail(eq("TOPIC"), eq(9L), anyInt());
        verify(mapper, never()).selectPostEnforcementTrail(anyLong(), anyInt());
        verify(mapper, never()).selectReportTimelineTrail(anyString(), anyLong(), anyInt());
        verify(mapper, never()).selectOfficialEventAuditTrail(anyLong(), anyInt());
    }

    @Test
    void communityPostAddsEnforcementAndContentReportTimeline() {
        service.trail("COMMUNITY_POST", 5L);
        verify(mapper).selectPostEnforcementTrail(eq(5L), anyInt());
        verify(mapper).selectReportTimelineTrail(eq("CONTENT"), eq(5L), anyInt());
        verify(mapper, never()).selectOfficialEventAuditTrail(anyLong(), anyInt());
    }

    @Test
    void activityAddsActivityReportTimelineAndOfficialEventAddsItsLog() {
        service.trail("ACTIVITY", 6L);
        verify(mapper).selectReportTimelineTrail(eq("ACTIVITY"), eq(6L), anyInt());
        service.trail("OFFICIAL_EVENT", 7L);
        verify(mapper).selectOfficialEventAuditTrail(eq(7L), anyInt());
        verify(mapper, never()).selectPostEnforcementTrail(anyLong(), anyInt());
    }

    @Test
    void mergesAllSourcesByTimeThenSourceThenIdWithNullTimesLast() {
        when(mapper.selectAuditRecordTrail(any(), any(), anyInt())).thenReturn(Arrays.asList(
                row(AuditTrailEntryVO.SOURCE_AUDIT_RECORD, 2L, 2000), row(AuditTrailEntryVO.SOURCE_AUDIT_RECORD, 1L, 1000)));
        when(mapper.selectBusinessAuditEventTrail(any(), any(), anyInt())).thenReturn(Collections.singletonList(
                row(AuditTrailEntryVO.SOURCE_BUSINESS_AUDIT_EVENT, 1L, 2000)));
        when(mapper.selectPostEnforcementTrail(any(), anyInt())).thenReturn(Arrays.asList(
                row(AuditTrailEntryVO.SOURCE_POST_ENFORCEMENT, 3L, 1500), row(AuditTrailEntryVO.SOURCE_POST_ENFORCEMENT, 4L, -1)));
        when(mapper.selectReportTimelineTrail(any(), any(), anyInt())).thenReturn(Collections.singletonList(
                row(AuditTrailEntryVO.SOURCE_REPORT_TIMELINE, 8L, 500)));
        List<String> order = service.trail("COMMUNITY_POST", 5L).stream()
                .map(e -> e.getSource() + "#" + e.getSourceId()).collect(Collectors.toList());
        assertEquals(Arrays.asList("REPORT_TIMELINE#8", "AUDIT_RECORD#1", "POST_ENFORCEMENT#3",
                "AUDIT_RECORD#2", "BUSINESS_AUDIT_EVENT#1", "POST_ENFORCEMENT#4"), order);
    }

    @Test
    void totalIsCapped() {
        List<AuditTrailEntryVO> many = new ArrayList<AuditTrailEntryVO>();
        for (long i = 1; i <= 300; i++) many.add(row(AuditTrailEntryVO.SOURCE_AUDIT_RECORD, i, i));
        when(mapper.selectAuditRecordTrail(any(), any(), anyInt())).thenReturn(many);
        when(mapper.selectBusinessAuditEventTrail(any(), any(), anyInt())).thenReturn(many);
        assertEquals(500, service.trail("TOPIC", 1L).size());
    }

    private static AuditTrailEntryVO row(String source, Long id, long millis) {
        AuditTrailEntryVO e = new AuditTrailEntryVO();
        e.setSource(source);
        e.setSourceId(id);
        e.setEventTime(millis < 0 ? null : new Date(millis));
        return e;
    }
}
