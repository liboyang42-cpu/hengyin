package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.ClubLeader;
import com.chengyinhub.business.service.ICapabilityService;
import com.chengyinhub.business.service.IClubLeaderService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LANE AC3 SC-CL-APPLY 服务端半边:小程序删掉最后一张上传失败的资质图后以 {@code certImages:""} 提交
 * (club-apply-state-contract.test.js「删除最后一张失败资质图后恢复无资质提交」),
 * /become-leader 必须照常建档,资质图不是必填。
 *
 * <p>另钉一条现状(DEFECT 记录,不在本 lane 修):/become-leader 对 certImages 不做任何媒体归属/形态校验,
 * 本机临时路径 {@code wxfile://} 原样进入主理人档案;同一控制器建团时的 logo/cover/caseImages 走
 * {@code reviewedMedia}。客户端已拦截(club-apply-state-contract.test.js「资质图片上传失败或仍在上传时
 * 不得提交本机临时路径」),但服务端没有兜底。
 */
class ApiClubBecomeLeaderCertImagesAcceptanceTest {
    private static final long MEMBER_ID = 42L;
    private static final String REQUIRED = "\"leaderName\":\"阿城\",\"phone\":\"13800000000\",\"experience\":\"1-3次\"";

    private MockMvc mvc;
    private IClubService clubService;

    @BeforeEach
    void setUp() {
        ApiClubController controller = spy(new ApiClubController());
        doReturn(MEMBER_ID).when(controller).getAppUserId();
        clubService = mock(IClubService.class);
        ReflectionTestUtils.setField(controller, "clubService", clubService);
        ReflectionTestUtils.setField(controller, "roleService", mock(IRoleService.class));
        ReflectionTestUtils.setField(controller, "clubLeaderService", mock(IClubLeaderService.class));
        ReflectionTestUtils.setField(controller, "capabilityService", mock(ICapabilityService.class));
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", mock(IWxContentSecurityService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void emptyCertImagesAfterDeletingLastFailedImageStillCreatesTheProfile() throws Exception {
        assertEquals("", submit("{" + REQUIRED + ",\"hasGuideCert\":0,\"certImages\":\"\"}").getCertImages());
    }

    @Test
    void omittedCertImagesStillCreatesTheProfile() throws Exception {
        assertEquals(null, submit("{" + REQUIRED + "}").getCertImages());
    }

    @Test
    void defectRecordedLocalTempCertPathIsPersistedWithoutServerSideMediaCheck() throws Exception {
        // DEFECT(记录现状):期望服务端拒绝或剔除 wxfile:// 本机路径;现状原样写进主理人档案。
        assertEquals("wxfile://tmp/failed-cert.jpg",
                submit("{" + REQUIRED + ",\"hasGuideCert\":1,\"certImages\":\"wxfile://tmp/failed-cert.jpg\"}").getCertImages());
    }

    private ClubLeader submit(String json) throws Exception {
        mvc.perform(post("/api/club/become-leader").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        ArgumentCaptor<ClubLeader> profile = ArgumentCaptor.forClass(ClubLeader.class);
        verify(clubService).saveLeaderProfileWithinIdentityMutex(eq(MEMBER_ID), profile.capture());
        return profile.getValue();
    }
}
