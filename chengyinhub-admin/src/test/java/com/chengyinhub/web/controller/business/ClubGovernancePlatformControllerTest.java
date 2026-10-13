package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.ClubGovernanceBan;
import com.chengyinhub.business.domain.ClubGovernanceCase;
import com.chengyinhub.business.domain.dto.ClubGovernancePlatformBanRequest;
import com.chengyinhub.business.domain.dto.ClubGovernancePlatformReviewRequest;
import com.chengyinhub.business.service.ClubGovernanceService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClubGovernancePlatformControllerTest {
    private static final long OPERATOR = 7001L;

    @Test
    void permanentBanAndCaseReviewAreGuardedByPlatformAuditPermission() throws Exception {
        Method ban = ClubGovernancePlatformController.class.getMethod(
                "permanentBan", ClubGovernancePlatformBanRequest.class);
        Method review = ClubGovernancePlatformController.class.getMethod(
                "review", ClubGovernancePlatformReviewRequest.class);
        assertTrue(ban.getAnnotation(PreAuthorize.class).value().contains("business:audit:review"));
        assertTrue(review.getAnnotation(PreAuthorize.class).value().contains("business:audit:review"));
    }

    @Test
    void platformEndpointsUseAuthenticatedSysUserAndNeverAcceptAnActorFromTheBody() {
        ClubGovernanceService service = mock(ClubGovernanceService.class);
        ClubGovernancePlatformController controller = new ClubGovernancePlatformController() {
            @Override public Long getUserId() { return OPERATOR; }
        };
        ReflectionTestUtils.setField(controller, "clubGovernanceService", service);
        ClubGovernanceBan row = new ClubGovernanceBan();
        row.setId(81L);
        row.setClubId(11L);
        row.setTargetMemberId(902L);
        row.setStatus(ClubGovernanceBan.STATUS_ACTIVE);
        row.setSourceType(ClubGovernanceBan.SOURCE_PLATFORM);
        when(service.platformPermanentBan(OPERATOR, 11L, 902L, "平台裁决", "pban-1"))
                .thenReturn(row);
        ClubGovernancePlatformBanRequest ban = new ClubGovernancePlatformBanRequest();
        ban.setClubId(11L);
        ban.setTargetMemberId(902L);
        ban.setReason("平台裁决");
        ban.setRequestId("pban-1");
        assertEquals(200, controller.permanentBan(ban).get(AjaxResult.CODE_TAG));

        ClubGovernancePlatformReviewRequest review = new ClubGovernancePlatformReviewRequest();
        review.setCaseId(101L);
        review.setVersion(2);
        review.setApproved(true);
        review.setReason("申诉成立");
        review.setRequestId("review-1");
        assertEquals(200, controller.review(review).get(AjaxResult.CODE_TAG));
        verify(service).platformPermanentBan(OPERATOR, 11L, 902L, "平台裁决", "pban-1");
        verify(service).reviewPlatformCase(OPERATOR, 101L, 2, true, "申诉成立", "review-1");
    }

    @Test
    void onlyPlatformQueueProjectionContainsControlledEvidence() throws Exception {
        ClubGovernanceService service = mock(ClubGovernanceService.class);
        ClubGovernancePlatformController controller = new ClubGovernancePlatformController();
        ReflectionTestUtils.setField(controller, "clubGovernanceService", service);
        ClubGovernanceCase row = new ClubGovernanceCase();
        row.setId(101L);
        row.setClubId(11L);
        row.setCaseType(ClubGovernanceCase.TYPE_REPORT);
        row.setFilerMemberId(901L);
        row.setTargetType(ClubGovernanceCase.TARGET_ACTIVITY);
        row.setTargetId(501L);
        row.setEvidenceJson("{\"orderNo\":\"CY-1\"}");
        row.setStatus(ClubGovernanceCase.STATUS_PENDING);
        row.setVersion(0);
        when(service.listPlatformCases(null, null, 11L)).thenReturn(Collections.singletonList(row));

        AjaxResult result = controller.cases(null, null, 11L);

        String json = new ObjectMapper().writeValueAsString(result.get(AjaxResult.DATA_TAG));
        assertTrue(json.contains("evidenceJson"));
        assertTrue(json.contains("orderNo"));
    }
}
