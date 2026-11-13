package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.vo.CommunityReportSubjectVO;
import com.chengyinhub.business.domain.vo.CommunityReportAppealReceiptVO;
import com.chengyinhub.web.controller.business.CommunityTrustPlatformController;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommunityTrustControllerSecurityTest {
    @Test
    void recoveryFilterUsesTheLoginPrincipalAndAnonymousRequestsDoNotReachTheService() {
        var service = org.mockito.Mockito.mock(com.chengyinhub.business.service.CommunityTrustService.class);
        var controller = org.mockito.Mockito.spy(new ApiCommunityTrustController());
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "communityTrustService", service);
        org.mockito.Mockito.doReturn(null).when(controller).getAppUserId();
        org.junit.jupiter.api.Assertions.assertEquals(401, controller.myReports(null, "report-key-123").get("code"));
        org.mockito.Mockito.verifyNoInteractions(service);
        org.mockito.Mockito.doReturn(101L).when(controller).getAppUserId();
        org.mockito.Mockito.when(service.listMyReports(101L, null, "report-key-123"))
                .thenReturn(java.util.Collections.emptyList());
        org.junit.jupiter.api.Assertions.assertEquals(200, controller.myReports(null, "report-key-123").get("code"));
        org.mockito.Mockito.verify(service).listMyReports(101L, null, "report-key-123");
    }

    @Test
    void alternateContentReportEndpointCannotBypassPostVisibilityAndOwnership() {
        var trust = org.mockito.Mockito.mock(com.chengyinhub.business.service.CommunityTrustService.class);
        var posts = org.mockito.Mockito.mock(com.chengyinhub.business.service.ICommunityPostService.class);
        var controller = org.mockito.Mockito.spy(new ApiCommunityTrustController());
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "communityTrustService", trust);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "communityPostService", posts);
        org.mockito.Mockito.doReturn(101L).when(controller).getAppUserId();
        for (String type : new String[]{"CONTENT", " comment "}) {
            var request = new com.chengyinhub.business.domain.dto.CommunityReportSubmitRequest();
            request.setTargetType(type);
            request.setTargetId(7L);
            org.mockito.Mockito.when(posts.report(101L, request))
                    .thenThrow(new com.chengyinhub.common.exception.ServiceException("内容不可见", 404));

            org.junit.jupiter.api.Assertions.assertEquals(404, controller.submitReport(request).get("code"));
            org.mockito.Mockito.verify(posts).report(101L, request);
        }
        org.mockito.Mockito.verifyNoInteractions(trust);
    }

    @Test
    void nonContentReportsRetainTheExistingTrustPathAndReporterProjection() {
        var trust = org.mockito.Mockito.mock(com.chengyinhub.business.service.CommunityTrustService.class);
        var posts = org.mockito.Mockito.mock(com.chengyinhub.business.service.ICommunityPostService.class);
        var controller = org.mockito.Mockito.spy(new ApiCommunityTrustController());
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "communityTrustService", trust);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "communityPostService", posts);
        org.mockito.Mockito.doReturn(101L).when(controller).getAppUserId();
        var request = new com.chengyinhub.business.domain.dto.CommunityReportSubmitRequest();
        request.setTargetType("MEMBER");
        request.setTargetId(102L);
        var row = new com.chengyinhub.business.domain.CommunityReportCase();
        row.setId(5L);
        row.setTargetType("MEMBER");
        row.setTargetId(102L);
        row.setStage("SUBMITTED");
        org.mockito.Mockito.when(trust.submitReport(101L, request)).thenReturn(row);

        var result = controller.submitReport(request);

        org.junit.jupiter.api.Assertions.assertEquals(200, result.get("code"));
        org.junit.jupiter.api.Assertions.assertTrue(result.get("data") instanceof com.chengyinhub.business.domain.vo.CommunityReportVO);
        org.mockito.Mockito.verify(trust).submitReport(101L, request);
        org.mockito.Mockito.verifyNoInteractions(posts);
    }

    private static final String REVIEW_PERMISSION = "business:audit:review";
    private static final String LIST_PERMISSION = "business:audit:list";

    @Test
    void subjectProjectionTypeCannotSerializeReporterOrFreeFormEvidenceFields() {
        assertFalse(hasDeclaredField(CommunityReportSubjectVO.class, "reporterMemberId"),
                "被举报方响应类型不得声明举报人标识字段");
        assertFalse(hasDeclaredField(CommunityReportSubjectVO.class, "description"),
                "被举报方响应类型不得声明举报自由文本字段");
        assertFalse(hasDeclaredField(CommunityReportSubjectVO.class, "evidenceAssetIds"),
                "被举报方响应类型不得声明举报证据字段");
        assertFalse(hasDeclaredField(CommunityReportAppealReceiptVO.class, "description"),
                "被举报方申诉回执不得声明举报正文");
        assertFalse(hasDeclaredField(CommunityReportAppealReceiptVO.class, "evidenceAssetIds"),
                "被举报方申诉回执不得声明举报证据");
    }

    @Test
    void platformReadAndWriteEndpointsCarrySeparateExistingAuditPermissions() throws Exception {
        Method reports = CommunityTrustPlatformController.class.getMethod(
                "reports", String.class, String.class);
        Method contributions = CommunityTrustPlatformController.class.getMethod(
                "contributions", String.class, Long.class);
        Method transition = CommunityTrustPlatformController.class.getMethod(
                "transitionReport", com.chengyinhub.business.domain.dto.CommunityReportStageRequest.class);
        Method review = CommunityTrustPlatformController.class.getMethod(
                "reviewContribution", com.chengyinhub.business.domain.dto.MerchantContributionReviewRequest.class);

        assertTrue(reports.getAnnotation(PreAuthorize.class).value().contains(LIST_PERMISSION),
                "举报平台列表必须复用只读审核权限");
        assertTrue(contributions.getAnnotation(PreAuthorize.class).value().contains(LIST_PERMISSION),
                "众包平台列表必须复用只读审核权限");
        assertTrue(transition.getAnnotation(PreAuthorize.class).value().contains(REVIEW_PERMISSION),
                "举报状态CAS必须要求审核写权限");
        assertTrue(review.getAnnotation(PreAuthorize.class).value().contains(REVIEW_PERMISSION),
                "众包审核与合并必须要求审核写权限");
    }

    private boolean hasDeclaredField(Class<?> type, String name) {
        for (java.lang.reflect.Field field : type.getDeclaredFields()) {
            if (name.equals(field.getName())) return true;
        }
        return false;
    }
}
