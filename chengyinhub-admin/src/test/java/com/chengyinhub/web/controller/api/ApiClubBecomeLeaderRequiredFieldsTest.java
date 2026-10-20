package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.ICapabilityService;
import com.chengyinhub.business.service.IClubLeaderService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V5a SC-CL-APPLY:/become-leader 的必填项与小程序 canNext 同口径(姓名、电话、组织经验),
 * 服务端缺一项就拒绝,不建主理人档案、不发 club 角色。
 */
class ApiClubBecomeLeaderRequiredFieldsTest {
    private static final long MEMBER_ID = 42L;

    private MockMvc mvc;
    private IClubService clubService;
    private IRoleService roleService;

    @BeforeEach
    void setUp() {
        ApiClubController controller = spy(new ApiClubController());
        doReturn(MEMBER_ID).when(controller).getAppUserId();
        clubService = mock(IClubService.class);
        roleService = mock(IRoleService.class);
        ReflectionTestUtils.setField(controller, "clubService", clubService);
        ReflectionTestUtils.setField(controller, "roleService", roleService);
        ReflectionTestUtils.setField(controller, "clubLeaderService", mock(IClubLeaderService.class));
        ReflectionTestUtils.setField(controller, "capabilityService", mock(ICapabilityService.class));
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", mock(IWxContentSecurityService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void blankLeaderNameAndPhoneAreRefusedWithoutCreatingAProfileOrGrantingTheRole() throws Exception {
        expectRefused("{\"leaderName\":\"\",\"phone\":\"\"}", "请填写主理人姓名");
    }

    @Test
    void whitespaceOnlyPhoneIsRefused() throws Exception {
        expectRefused("{\"leaderName\":\"阿城\",\"phone\":\"   \",\"experience\":\"1-3次\"}", "请填写联系电话");
    }

    @Test
    void missingExperienceIsRefused() throws Exception {
        expectRefused("{\"leaderName\":\"阿城\",\"phone\":\"13800000000\"}", "请选择组织经验");
    }

    @Test
    void completeRequiredFieldsStillReachTheProfileWrite() throws Exception {
        mvc.perform(post("/api/club/become-leader").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaderName\":\"阿城\",\"phone\":\"13800000000\",\"experience\":\"1-3次\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(clubService).saveLeaderProfileWithinIdentityMutex(org.mockito.ArgumentMatchers.eq(MEMBER_ID), any());
    }

    private void expectRefused(String json, String message) throws Exception {
        mvc.perform(post("/api/club/become-leader").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value(message));
        verify(clubService, never()).saveLeaderProfileWithinIdentityMutex(anyLong(), any());
        verify(clubService, never()).onboardLeaderAndInsertClubWithinIdentityMutex(anyLong(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(roleService, never()).changeRole(anyLong(), anyString(), anyLong());
    }
}
