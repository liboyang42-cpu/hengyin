package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.ClubLeader;
import com.chengyinhub.business.mapper.AppMediaAssetMapper;
import com.chengyinhub.business.service.ICapabilityService;
import com.chengyinhub.business.service.IClubLeaderService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 资质图可不填；非空时必须是当前账号上传且审核通过的持久媒体，非法提交不建档、不发角色。 */
class ApiClubBecomeLeaderCertImagesAcceptanceTest {
    private static final long MEMBER_ID = 42L;
    private static final String REQUIRED = "\"leaderName\":\"阿城\",\"phone\":\"13800000000\",\"experience\":\"1-3次\"";

    private MockMvc mvc;
    private IClubService clubService;
    private IRoleService roleService;
    private IClubLeaderService clubLeaderService;
    private AppMediaAssetMapper assets;

    @BeforeEach
    void setUp() {
        ApiClubController controller = spy(new ApiClubController());
        doReturn(MEMBER_ID).when(controller).getAppUserId();
        clubService = mock(IClubService.class);
        roleService = mock(IRoleService.class);
        clubLeaderService = mock(IClubLeaderService.class);
        assets = mock(AppMediaAssetMapper.class);
        AppMediaReferenceGuard guard = new AppMediaReferenceGuard();
        ReflectionTestUtils.setField(guard, "mapper", assets);
        ReflectionTestUtils.setField(controller, "mediaReferences", guard);
        ReflectionTestUtils.setField(controller, "leaderCertificateAssets", assets);
        ReflectionTestUtils.setField(controller, "clubService", clubService);
        ReflectionTestUtils.setField(controller, "roleService", roleService);
        ReflectionTestUtils.setField(controller, "clubLeaderService", clubLeaderService);
        ReflectionTestUtils.setField(controller, "capabilityService", mock(ICapabilityService.class));
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", mock(IWxContentSecurityService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void emptyCertImagesAfterDeletingLastFailedImageStillCreatesTheProfile() throws Exception {
        assertEquals("", submit("{" + REQUIRED + ",\"hasGuideCert\":0,\"certImages\":\"\"}").getCertImages());
        verifyNoInteractions(assets);
    }

    @Test
    void omittedCertImagesStillCreatesTheProfile() throws Exception {
        assertNull(submit("{" + REQUIRED + "}").getCertImages());
        verifyNoInteractions(assets);
    }

    @Test
    void guideCertificateFlagDoesNotMakeImagesMandatory() throws Exception {
        assertNull(submit("{" + REQUIRED + ",\"hasGuideCert\":1}").getCertImages());
        verifyNoInteractions(assets);
    }

    @Test
    void localTempCertificatePathsAreRejectedWithoutWrites() throws Exception {
        for (String path : new String[]{"wxfile://tmp/failed-cert.jpg", "file:///tmp/cert.jpg", "/tmp/cert.jpg"}) {
            reject(path, 422, "媒体来源未经审核，请重新上传");
        }
        verifyNoInteractions(assets);
    }

    @Test
    void malformedAndUnregisteredReferencesAreRejectedWithoutWrites() throws Exception {
        for (String reference : new String[]{"media-asset:bad", "media-asset:9999999999999999999",
                "media-asset:99", "https://uploads.example/unregistered.jpg"}) {
            reject(reference, 422, "媒体来源未经审核，请重新上传");
        }
    }

    @Test
    void approvedForeignCertificateCannotBeReusedEvenThoughOrdinaryPublicMediaCan() throws Exception {
        when(assets.select(9L)).thenReturn(asset(9L, MEMBER_ID + 1, "APP", "APPROVED"));
        reject("media-asset:9", 403, "资质图片不属于当前账号，请重新上传");
    }

    @Test
    void approvedSystemCertificateIsNotTheApplicantsUpload() throws Exception {
        when(assets.select(9L)).thenReturn(asset(9L, MEMBER_ID, "SYSTEM", "APPROVED"));
        reject("media-asset:9", 403, "资质图片不属于当前账号，请重新上传");
    }

    @Test
    void ownUnapprovedCertificatesAreRejectedWithoutWrites() throws Exception {
        for (String state : new String[]{"HOLD", "REJECTED", "UNAVAILABLE"}) {
            when(assets.select(9L)).thenReturn(asset(9L, MEMBER_ID, "APP", state));
            reject("media-asset:9", 422, "媒体尚未审核通过，暂不能公开");
        }
    }

    @Test
    void approvedOwnCertificateUrlPersistsCanonicalReferenceImmediately() throws Exception {
        String key = "upload/0123456789abcdef0123456789abcdef.png";
        Map<String, Object> own = asset(9L, MEMBER_ID, "APP", "APPROVED");
        when(assets.selectByObjectKey(key)).thenReturn(own);
        when(assets.select(9L)).thenReturn(own);
        assertEquals("media-asset:9", submitWithImages("https://uploads.example/" + key).getCertImages());
    }

    @Test
    void everyCertificateInTheListMustBelongToTheApplicant() throws Exception {
        when(assets.select(9L)).thenReturn(asset(9L, MEMBER_ID, "APP", "APPROVED"));
        when(assets.select(10L)).thenReturn(asset(10L, MEMBER_ID + 1, "APP", "APPROVED"));
        reject("media-asset:9;media-asset:10", 403, "资质图片不属于当前账号，请重新上传");
    }

    @Test
    void approvedOwnCertificateListIsNormalizedAndSaved() throws Exception {
        when(assets.select(9L)).thenReturn(asset(9L, MEMBER_ID, "APP", "APPROVED"));
        when(assets.select(10L)).thenReturn(asset(10L, MEMBER_ID, "APP", "APPROVED"));
        assertEquals("media-asset:9;media-asset:10", submitWithImages("media-asset:9, media-asset:10").getCertImages());
    }

    private Map<String, Object> asset(long id, long member, String ownerType, String state) {
        return Map.of("id", id, "memberId", member, "ownerType", ownerType, "reviewState", state);
    }

    private ClubLeader submitWithImages(String images) throws Exception {
        return submit("{" + REQUIRED + ",\"hasGuideCert\":1,\"certImages\":\"" + images + "\"}");
    }

    private void reject(String images, int code, String message) throws Exception {
        mvc.perform(post("/api/club/become-leader").contentType(MediaType.APPLICATION_JSON)
                        .content("{" + REQUIRED + ",\"hasGuideCert\":1,\"certImages\":\"" + images + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.msg").value(message));
        verifyNoInteractions(clubService, roleService, clubLeaderService);
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
