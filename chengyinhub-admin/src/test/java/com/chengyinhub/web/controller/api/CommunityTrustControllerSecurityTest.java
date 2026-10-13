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
