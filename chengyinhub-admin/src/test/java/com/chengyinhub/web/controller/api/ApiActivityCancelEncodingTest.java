package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.ActivityCancelResult;
import com.chengyinhub.business.service.ITeamGroupService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * iOS ActivityHostCancelHTTPAccess 向 /api/activity/cancel 与 /api/activity/cancel_preview
 * 发送 application/json（且 id 为数字），小程序/旧入口发 form；两边都需正确绑定参数。
 */
class ApiActivityCancelEncodingTest {

    private static final long PRINCIPAL = 9002L;
    private static final long OWNER = 9002L;
    private static final long ACTIVITY_ID = 992031L;

    private ITeamGroupService teamGroupService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        teamGroupService = mock(ITeamGroupService.class);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        IUmsMemberService members = mock(IUmsMemberService.class);
        IWxContentSecurityService wxSec = mock(IWxContentSecurityService.class);

        when(access.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(new MerchantAccessContext(PRINCIPAL, 8001L, OWNER, MerchantRoleCode.MERCHANT_OWNER));
        UmsMember member = new UmsMember();
        member.setWxOpenId("open_9002");
        when(members.selectUmsMemberById(PRINCIPAL)).thenReturn(member);
        when(wxSec.checkText(any(), any())).thenReturn(null);
        when(teamGroupService.countPaidPlayersForMerchantActivityCancel(ACTIVITY_ID, OWNER)).thenReturn(3);
        when(teamGroupService.cancelActivityByMerchantHost(eq(ACTIVITY_ID), eq(OWNER), eq(PRINCIPAL), eq("天气原因")))
                .thenReturn(new ActivityCancelResult(0, 0));

        ApiActivityController controller = new ApiActivityController() {
            @Override
            public Long getAppUserId() {
                return PRINCIPAL;
            }
        };
        ReflectionTestUtils.setField(controller, "teamGroupService", teamGroupService);
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        ReflectionTestUtils.setField(controller, "umsMemberService", members);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", wxSec);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void cancelAndPreviewAcceptBothJsonAndForm() throws Exception {
        mockMvc.perform(post("/api/activity/cancel_preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":" + ACTIVITY_ID + ",\"scope\":\"MERCHANT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.paidPlayers").value(3));

        mockMvc.perform(post("/api/activity/cancel_preview")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("id", String.valueOf(ACTIVITY_ID))
                        .param("scope", "MERCHANT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.paidPlayers").value(3));

        mockMvc.perform(post("/api/activity/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":" + ACTIVITY_ID + ",\"reason\":\"天气原因\",\"scope\":\"MERCHANT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/activity/cancel")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("id", String.valueOf(ACTIVITY_ID))
                        .param("reason", "天气原因")
                        .param("scope", "MERCHANT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(teamGroupService, times(2)).countPaidPlayersForMerchantActivityCancel(ACTIVITY_ID, OWNER);
        verify(teamGroupService, times(2)).cancelActivityByMerchantHost(ACTIVITY_ID, OWNER, PRINCIPAL, "天气原因");
    }
}
