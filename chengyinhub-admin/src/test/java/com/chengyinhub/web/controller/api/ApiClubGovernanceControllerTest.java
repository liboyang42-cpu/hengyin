package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.ClubGovernanceBan;
import com.chengyinhub.business.domain.ClubGovernanceCase;
import com.chengyinhub.business.domain.ClubGovernanceOwnerTransfer;
import com.chengyinhub.business.domain.dto.ClubGovernanceAppealRequest;
import com.chengyinhub.business.domain.dto.ClubGovernanceBanRequest;
import com.chengyinhub.business.domain.dto.ClubGovernanceListRequest;
import com.chengyinhub.business.domain.dto.ClubGovernanceOwnerTransferRequest;
import com.chengyinhub.business.domain.dto.ClubGovernanceReportRequest;
import com.chengyinhub.business.domain.dto.ClubGovernanceUnbanRequest;
import com.chengyinhub.business.service.ClubGovernanceService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiClubGovernanceControllerTest {
    private static final long PRINCIPAL = 901L;
    private static final long CLUB = 11L;

    @Test
    void listReturnsWhitelistedHistoryWithoutInternalRequestKeys() throws Exception {
        ClubGovernanceService service = mock(ClubGovernanceService.class);
        ClubGovernanceBan row = row();
        row.setBanRequestId("server-idempotency-key");
        when(service.list(PRINCIPAL, CLUB)).thenReturn(Collections.singletonList(row));
        ClubGovernanceListRequest request = new ClubGovernanceListRequest();
        request.setClubId(CLUB);

        AjaxResult result = controller(service).list(request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        String json = new ObjectMapper().writeValueAsString(result.get(AjaxResult.DATA_TAG));
        assertFalse(json.contains("server-idempotency-key"));
        assertFalse(json.contains("banRequestId"));
        verify(service).list(PRINCIPAL, CLUB);
    }

    @Test
    void banAndUnbanAlwaysUseAuthenticatedPrincipal() {
        ClubGovernanceService service = mock(ClubGovernanceService.class);
        ClubGovernanceBan row = row();
        Date expiresAt = new Date(System.currentTimeMillis() + 86_400_000L);
        when(service.ban(PRINCIPAL, CLUB, 902L, "线下骚扰", expiresAt, "ban-1")).thenReturn(row);
        ApiClubGovernanceController controller = controller(service);

        ClubGovernanceBanRequest ban = new ClubGovernanceBanRequest();
        ban.setClubId(CLUB);
        ban.setTargetMemberId(902L);
        ban.setReason("线下骚扰");
        ban.setExpiresAt(expiresAt);
        ban.setRequestId("ban-1");
        assertEquals(200, controller.ban(ban).get(AjaxResult.CODE_TAG));
        verify(service).ban(PRINCIPAL, CLUB, 902L, "线下骚扰", expiresAt, "ban-1");

        ClubGovernanceUnbanRequest unban = new ClubGovernanceUnbanRequest();
        unban.setClubId(CLUB);
        unban.setBanId(81L);
        unban.setVersion(2);
        unban.setReason("复核通过");
        unban.setRequestId("unban-1");
        assertEquals(200, controller.unban(unban).get(AjaxResult.CODE_TAG));
        verify(service).unban(PRINCIPAL, CLUB, 81L, 2, "复核通过", "unban-1");
    }

    @Test
    void serviceDenialKeepsItsFailClosedStatusCode() {
        ClubGovernanceService service = mock(ClubGovernanceService.class);
        when(service.list(PRINCIPAL, CLUB)).thenThrow(new ServiceException("权限不足", 403));
        ClubGovernanceListRequest request = new ClubGovernanceListRequest();
        request.setClubId(CLUB);

        AjaxResult result = controller(service).list(request);

        assertEquals(403, result.get(AjaxResult.CODE_TAG));
    }

    @Test
    void myCasesNeverExposeControlledEvidence() throws Exception {
        ClubGovernanceService service = mock(ClubGovernanceService.class);
        ClubGovernanceCase row = new ClubGovernanceCase();
        row.setId(101L);
        row.setClubId(CLUB);
        row.setCaseType(ClubGovernanceCase.TYPE_REPORT);
        row.setFilerMemberId(PRINCIPAL);
        row.setSubjectMemberId(902L);
        row.setTargetType(ClubGovernanceCase.TARGET_MEMBER);
        row.setTargetId(902L);
        row.setEvidenceJson("{\"messageId\":\"771\"}");
        row.setStatus(ClubGovernanceCase.STATUS_PENDING);
        row.setVersion(0);
        when(service.listMyCases(PRINCIPAL, CLUB)).thenReturn(Collections.singletonList(row));
        ClubGovernanceListRequest request = new ClubGovernanceListRequest();
        request.setClubId(CLUB);

        AjaxResult result = controller(service).myCases(request);

        String json = new ObjectMapper().writeValueAsString(result.get(AjaxResult.DATA_TAG));
        assertFalse(json.contains("evidenceJson"));
        assertFalse(json.contains("messageId"));
    }

    @Test
    void transferReportAndAppealAlwaysUseTheAuthenticatedPrincipal() {
        ClubGovernanceService service = mock(ClubGovernanceService.class);
        ApiClubGovernanceController controller = controller(service);
        ClubGovernanceOwnerTransfer transferRow = new ClubGovernanceOwnerTransfer();
        transferRow.setId(91L);
        transferRow.setClubId(CLUB);
        transferRow.setFromOwnerMemberId(PRINCIPAL);
        transferRow.setToOwnerMemberId(902L);
        when(service.transferOwner(PRINCIPAL, CLUB, 902L, "交接", "transfer-1"))
                .thenReturn(transferRow);
        ClubGovernanceOwnerTransferRequest transfer = new ClubGovernanceOwnerTransferRequest();
        transfer.setClubId(CLUB);
        transfer.setTargetMemberId(902L);
        transfer.setReason("交接");
        transfer.setRequestId("transfer-1");
        assertEquals(200, controller.transferOwner(transfer).get(AjaxResult.CODE_TAG));

        ClubGovernanceCase caseRow = new ClubGovernanceCase();
        caseRow.setId(101L);
        caseRow.setClubId(CLUB);
        caseRow.setFilerMemberId(PRINCIPAL);
        caseRow.setSubjectMemberId(902L);
        caseRow.setCaseType(ClubGovernanceCase.TYPE_REPORT);
        caseRow.setStatus(ClubGovernanceCase.STATUS_PENDING);
        Map<String, String> evidence = Collections.singletonMap("messageId", "771");
        when(service.submitReport(PRINCIPAL, CLUB, ClubGovernanceCase.TARGET_MEMBER, 902L,
                evidence, "安全风险", "report-1"))
                .thenReturn(caseRow);
        ClubGovernanceReportRequest report = new ClubGovernanceReportRequest();
        report.setClubId(CLUB);
        report.setTargetType(ClubGovernanceCase.TARGET_MEMBER);
        report.setTargetId(902L);
        report.setEvidence(evidence);
        report.setReason("安全风险");
        report.setRequestId("report-1");
        assertEquals(200, controller.report(report).get(AjaxResult.CODE_TAG));

        caseRow.setCaseType(ClubGovernanceCase.TYPE_APPEAL);
        when(service.submitAppeal(PRINCIPAL, CLUB, "事实有误", "appeal-1")).thenReturn(caseRow);
        ClubGovernanceAppealRequest appeal = new ClubGovernanceAppealRequest();
        appeal.setClubId(CLUB);
        appeal.setReason("事实有误");
        appeal.setRequestId("appeal-1");
        assertEquals(200, controller.appeal(appeal).get(AjaxResult.CODE_TAG));

        verify(service).transferOwner(PRINCIPAL, CLUB, 902L, "交接", "transfer-1");
        verify(service).submitReport(PRINCIPAL, CLUB, ClubGovernanceCase.TARGET_MEMBER, 902L,
                evidence, "安全风险", "report-1");
        verify(service).submitAppeal(PRINCIPAL, CLUB, "事实有误", "appeal-1");
    }

    // CU-C-55:端上改发 epoch 毫秒后,「７天」就是服务端 7×24 小时,不再随设备时区漂移。
    @Test
    void banExpiryPrefersEpochMillisFromTheClient() {
        ClubGovernanceService service = mock(ClubGovernanceService.class);
        ClubGovernanceBan row = row();
        long epochMs = System.currentTimeMillis() + 7L * 24L * 60L * 60L * 1000L;
        when(service.ban(PRINCIPAL, CLUB, 902L, "线下骚扰", new Date(epochMs), "ban-1")).thenReturn(row);

        ClubGovernanceBanRequest ban = new ClubGovernanceBanRequest();
        ban.setClubId(CLUB);
        ban.setTargetMemberId(902L);
        ban.setReason("线下骚扰");
        ban.setExpiresAtEpochMs(epochMs);
        // 两个字段同时给时以绝对时刻为准:老端的时间串只是回落,不能赢过 epoch
        ban.setExpiresAt(new Date(epochMs - 3_600_000L));
        ban.setRequestId("ban-1");

        assertEquals(200, controller(service).ban(ban).get(AjaxResult.CODE_TAG));
        verify(service).ban(PRINCIPAL, CLUB, 902L, "线下骚扰", new Date(epochMs), "ban-1");
    }

    // CU-C-31 / CU-C-84:申诉资格判据(本人当前有没有有效封禁)跟工单一并下发,
    // 前端才有依据决定要不要渲染申诉表单,而不是等提交那一刻被拒。
    @Test
    void myCasesCarriesTheActiveBanButNotInternalKeys() throws Exception {
        ClubGovernanceService service = mock(ClubGovernanceService.class);
        ClubGovernanceBan ban = row();
        ban.setBanRequestId("server-idempotency-key");
        when(service.listMyCases(PRINCIPAL, CLUB)).thenReturn(Collections.emptyList());
        when(service.activeBan(PRINCIPAL, CLUB)).thenReturn(ban);
        ClubGovernanceListRequest request = new ClubGovernanceListRequest();
        request.setClubId(CLUB);

        // data 必须仍是数组:已发布的小程序按数组解析,改成对象会让旧版申诉页整页报错
        AjaxResult bannedResult = controller(service).myCases(request);
        assertTrue(bannedResult.get(AjaxResult.DATA_TAG) instanceof java.util.List, "data 必须保持工单数组(兼容旧版小程序)");
        String banned = new ObjectMapper().writeValueAsString(bannedResult);
        assertTrue(banned.contains("\"activeBan\":{"), "有有效封禁时要把这条封禁投影给申诉页");
        assertFalse(banned.contains("server-idempotency-key"));
        assertFalse(banned.contains("banRequestId"));

        ClubGovernanceService clean = mock(ClubGovernanceService.class);
        when(clean.listMyCases(PRINCIPAL, CLUB)).thenReturn(Collections.emptyList());
        String none = new ObjectMapper().writeValueAsString(controller(clean).myCases(request));
        assertTrue(none.contains("\"activeBan\":null"),
                "没有有效封禁时字段要在且为 null,前端才分得清「没有」和「这版后端没下发」");
    }

    private ApiClubGovernanceController controller(ClubGovernanceService service) {
        ApiClubGovernanceController controller = new ApiClubGovernanceController() {
            @Override public Long getAppUserId() { return PRINCIPAL; }
        };
        ReflectionTestUtils.setField(controller, "clubGovernanceService", service);
        return controller;
    }

    private ClubGovernanceBan row() {
        ClubGovernanceBan row = new ClubGovernanceBan();
        row.setId(81L);
        row.setClubId(CLUB);
        row.setTargetMemberId(902L);
        row.setStatus(ClubGovernanceBan.STATUS_ACTIVE);
        row.setSourceType(ClubGovernanceBan.SOURCE_CLUB);
        row.setBanReason("线下骚扰");
        row.setBannedAt(new Date());
        row.setVersion(2);
        return row;
    }
}
