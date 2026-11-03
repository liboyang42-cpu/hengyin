package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CommunityReportCase;
import com.chengyinhub.business.domain.CommunityReportTimeline;
import com.chengyinhub.business.domain.dto.CommunityPostRequests;
import com.chengyinhub.business.domain.dto.CommunityReportStageRequest;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.mapper.CommunityTrustMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.impl.CommunityTrustServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUDIT-G3-5(P3-4):举报单阶段与帖文处置原本是两套互不相通的状态。修复后举报以违规结案(RESOLVED + enforcement)时,
 * 同一事务里经 ICommunityPostService.enforceReportedTarget 落处置(处置行带 report_case_id),
 * 并把裁定写回举报单(decision_summary + 时间线 ENFORCEMENT_APPLIED,内部备注记处置 id)。
 */
class CommunityReportEnforcementBridgeTest {
    private static final long OPERATOR = 5L;
    private static final long REPORT = 301L;

    private CommunityTrustMapper mapper;
    private ICommunityPostService posts;
    private CommunityTrustServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mapper = mock(CommunityTrustMapper.class);
        CommunityPostMapper communityPosts = mock(CommunityPostMapper.class);
        posts = mock(ICommunityPostService.class);
        ObjectProvider<ICommunityPostService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(posts);
        service = new CommunityTrustServiceImpl();
        ReflectionTestUtils.setField(service, "communityTrustMapper", mapper);
        ReflectionTestUtils.setField(service, "communityPostMapper", communityPosts);
        ReflectionTestUtils.setField(service, "mmsMerchantMapper", mock(MmsMerchantMapper.class));
        ReflectionTestUtils.setField(service, "communityPostServiceProvider", provider);
        when(mapper.transitionReportCas(anyLong(), anyString(), anyString(), any(), any(), any())).thenReturn(1);
        when(mapper.insertReportTimeline(any())).thenReturn(1);
        when(communityPosts.insertTypedOutbox(anyString(), anyString(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(1);
    }

    @Test
    void violationResolutionEnforcesTheReportedPostAndWritesTheDecisionBack() {
        when(mapper.selectReportForUpdate(REPORT)).thenReturn(report("CONTENT", 7L));
        CommunityPostRequests.Enforcement enforcement = enforcement();
        when(posts.enforceReportedTarget(OPERATOR, REPORT, "CONTENT", 7L, enforcement)).thenReturn(21L);

        CommunityReportCase row = service.transitionReport(OPERATOR, resolve(enforcement));

        verify(posts).enforceReportedTarget(OPERATOR, REPORT, "CONTENT", 7L, enforcement);
        assertEquals("RESOLVED", row.getStage());
        assertEquals("违规成立，已处置：REMOVE", row.getDecisionSummary());
        verify(mapper).transitionReportCas(eq(REPORT), eq("INVESTIGATING"), eq("RESOLVED"), eq(2),
                eq("违规成立，已处置：REMOVE"), any());
        ArgumentCaptor<CommunityReportTimeline> timeline = ArgumentCaptor.forClass(CommunityReportTimeline.class);
        verify(mapper, atLeastOnce()).insertReportTimeline(timeline.capture());
        List<CommunityReportTimeline> rows = timeline.getAllValues();
        CommunityReportTimeline applied = rows.get(rows.size() - 1);
        assertEquals("ENFORCEMENT_APPLIED", applied.getEventType());
        assertTrue(applied.getInternalNote().contains("enforcementId=21"));
    }

    @Test
    void violationResolutionOfACommentReportEnforcesTheComment() {
        when(mapper.selectReportForUpdate(REPORT)).thenReturn(report("COMMENT", 55L));
        CommunityPostRequests.Enforcement enforcement = enforcement();
        when(posts.enforceReportedTarget(OPERATOR, REPORT, "COMMENT", 55L, enforcement)).thenReturn(41L);

        service.transitionReport(OPERATOR, resolve(enforcement));

        verify(posts).enforceReportedTarget(OPERATOR, REPORT, "COMMENT", 55L, enforcement);
    }

    @Test
    void resolutionWithoutViolationDoesNotEnforce() {
        when(mapper.selectReportForUpdate(REPORT)).thenReturn(report("CONTENT", 7L));

        service.transitionReport(OPERATOR, resolve(null));

        verify(posts, never()).enforceReportedTarget(anyLong(), anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void enforcementOnlyRidesAResolutionOfContentOrComment() {
        CommunityReportStageRequest triage = resolve(enforcement());
        triage.setNextStage("TRIAGED");
        assertEquals(400, assertThrows(ServiceException.class,
                () -> service.transitionReport(OPERATOR, triage)).getCode());

        when(mapper.selectReportForUpdate(REPORT)).thenReturn(report("MERCHANT", 201L));
        assertEquals(400, assertThrows(ServiceException.class,
                () -> service.transitionReport(OPERATOR, resolve(enforcement()))).getCode());
        verify(posts, never()).enforceReportedTarget(anyLong(), anyLong(), anyString(), anyLong(), any());
    }

    private CommunityReportCase report(String targetType, long targetId) {
        CommunityReportCase row = new CommunityReportCase();
        row.setId(REPORT);
        row.setReporterMemberId(101L);
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setStage("INVESTIGATING");
        row.setVersion(2);
        return row;
    }

    private CommunityReportStageRequest resolve(CommunityPostRequests.Enforcement enforcement) {
        CommunityReportStageRequest request = new CommunityReportStageRequest();
        request.setReportId(REPORT);
        request.setExpectedVersion(2);
        request.setNextStage("RESOLVED");
        request.setPublicNote("举报已处理");
        request.setRequestId("report-301-resolve-v1");
        request.setEnforcement(enforcement);
        return request;
    }

    private CommunityPostRequests.Enforcement enforcement() {
        CommunityPostRequests.Enforcement request = new CommunityPostRequests.Enforcement();
        request.setEnforcementType("REMOVE");
        request.setRuleCode("SAFETY");
        request.setGuidelineVersionId(5L);
        request.setReasonCode("HARM");
        request.setEvidenceJson("{\"reportId\":301}");
        request.setRequestId("report-301-enforce-v1");
        return request;
    }
}
