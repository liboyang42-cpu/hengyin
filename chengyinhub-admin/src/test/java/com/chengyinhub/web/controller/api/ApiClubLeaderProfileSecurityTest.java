package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubLeader;
import com.chengyinhub.business.domain.vo.PublicClubVO;
import com.chengyinhub.business.service.ICapabilityService;
import com.chengyinhub.business.service.IClubLeaderService;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiClubLeaderProfileSecurityTest {
    private static final long MEMBER_ID = 42L;
    private static final Date SERVER_CREATE_TIME = new Date(12_345L);

    private ApiClubController controller;
    private MockMvc mvc;
    private IClubLeaderService leaderService;
    private IClubService clubService;
    private IClubMemberService memberService;
    private ICmsTopicService topicService;
    private ICapabilityService capabilityService;
    private AtomicReference<ClubLeader> persisted;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiClubController());
        // X03:建俱乐部现在会先过「建群」能力位闸。mock 默认返回 null = 放行,
        // 本用例断言的是身份越权,不是能力位。
        ReflectionTestUtils.setField(controller, "capabilityGuard", mock(com.chengyinhub.business.service.ICapabilityGuard.class));
        doReturn(MEMBER_ID).when(controller).getAppUserId();
        leaderService = mock(IClubLeaderService.class);
        clubService = mock(IClubService.class);
        memberService = mock(IClubMemberService.class);
        topicService = mock(ICmsTopicService.class);
        capabilityService = mock(ICapabilityService.class);
        persisted = new AtomicReference<>();

        ReflectionTestUtils.setField(controller, "clubLeaderService", leaderService);
        ReflectionTestUtils.setField(controller, "clubService", clubService);
        ReflectionTestUtils.setField(controller, "clubMemberService", memberService);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "capabilityService", capabilityService);
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "roleService", mock(IRoleService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", mock(IWxContentSecurityService.class));


        when(leaderService.selectClubLeaderByMemberId(MEMBER_ID))
                .thenAnswer(call -> persisted.get());
        when(leaderService.insertClubLeader(any(ClubLeader.class))).thenAnswer(call -> {
            ClubLeader row = call.getArgument(0, ClubLeader.class);
            row.setId(77L);
            row.setCreateTime(SERVER_CREATE_TIME);
            persisted.set(row);
            return 1;
        });
        when(leaderService.updateClubLeader(any(ClubLeader.class))).thenAnswer(call -> {
            applyMapperPatch(persisted.get(), call.getArgument(0, ClubLeader.class));
            return 1;
        });
        when(clubService.saveLeaderProfileWithinIdentityMutex(any(Long.class), any(ClubLeader.class)))
                .thenAnswer(call -> {
                    Long memberId = call.getArgument(0, Long.class);
                    ClubLeader patch = call.getArgument(1, ClubLeader.class);
                    ClubLeader current = persisted.get();
                    if (current == null) {
                        patch.setId(77L);
                        patch.setMemberId(memberId);
                        patch.setLevel(0);
                        patch.setTags(null);
                        patch.setStatus(1);
                        patch.setDelFlag(0);
                        patch.setCreateTime(SERVER_CREATE_TIME);
                        persisted.set(patch);
                    } else {
                        applyMapperPatch(current, patch);
                    }
                    return persisted.get();
                });
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void nullBodyReturnsAStableBusinessErrorWithoutAnyProfileOrCapabilityWrite() throws Exception {
        mvc.perform(post("/api/club/become-leader")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("请填写主理人资料"));

        assertNull(persisted.get());
        verify(leaderService, never()).insertClubLeader(any());
        verify(leaderService, never()).updateClubLeader(any());
        verify(capabilityService, never()).grant(any(), any(), any());
    }

    @Test
    void firstBecomeLeaderIgnoresEveryServerOwnedFieldAndPublicProjectionUsesServerDefaults()
            throws Exception {
        mvc.perform(post("/api/club/become-leader")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousJson("新主理人")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        ClubLeader stored = persisted.get();
        assertEquals(77L, stored.getId());
        assertEquals(MEMBER_ID, stored.getMemberId());
        assertEquals("新主理人", stored.getLeaderName());
        assertEquals(0, stored.getLevel());
        assertNull(stored.getTags());
        assertEquals(1, stored.getStatus());
        assertNull(stored.getAuditBy());
        assertNull(stored.getAuditTime());
        assertEquals(0, stored.getDelFlag());
        assertNull(stored.getCreateBy());
        assertEquals(SERVER_CREATE_TIME, stored.getCreateTime());
        assertNull(stored.getUpdateBy());
        assertNull(stored.getUpdateTime());
        assertNull(stored.getRemark());

        Club owned = new Club();
        owned.setId(88L);
        owned.setMemberId(MEMBER_ID);
        owned.setName("公开俱乐部");
        when(clubService.selectClubById(88L)).thenReturn(owned);
        when(memberService.countByClub(88L)).thenReturn(0);
        when(memberService.selectByClubAndMember(88L, MEMBER_ID)).thenReturn(null);
        when(memberService.selectMembershipRecord(88L, MEMBER_ID)).thenReturn(null);
        when(topicService.selectCmsTopicList(any())).thenReturn(Collections.emptyList());
        when(clubService.isJoinPolicySupported()).thenReturn(false);
        Club request = new Club();
        request.setId(88L);

        AjaxResult detail = controller.detail(request);

        PublicClubVO publicClub = (PublicClubVO) detail.get("data");
        assertEquals(0, publicClub.getLevel());
        assertNull(publicClub.getTags());
    }

    @Test
    void profileEditCannotOverwriteExistingPlatformOrOwnershipMetadata() throws Exception {
        ClubLeader existing = new ClubLeader();
        existing.setId(77L);
        existing.setMemberId(MEMBER_ID);
        existing.setLeaderName("旧昵称");
        existing.setLevel(4);
        existing.setTags("平台认证");
        existing.setStatus(1);
        existing.setAuditBy("platform-admin");
        existing.setAuditTime(new Date(222_222L));
        existing.setDelFlag(0);
        existing.setCreateBy("system");
        existing.setCreateTime(SERVER_CREATE_TIME);
        existing.setUpdateBy("platform-admin");
        existing.setUpdateTime(new Date(333_333L));
        existing.setRemark("平台备注");
        persisted.set(existing);

        mvc.perform(post("/api/club/become-leader")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousJson("允许修改的新昵称")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        ClubLeader stored = persisted.get();
        assertEquals(77L, stored.getId());
        assertEquals(MEMBER_ID, stored.getMemberId());
        assertEquals("允许修改的新昵称", stored.getLeaderName());
        assertEquals(4, stored.getLevel());
        assertEquals("平台认证", stored.getTags());
        assertEquals(1, stored.getStatus());
        assertEquals("platform-admin", stored.getAuditBy());
        assertEquals(new Date(222_222L), stored.getAuditTime());
        assertEquals(0, stored.getDelFlag());
        assertEquals("system", stored.getCreateBy());
        assertEquals(SERVER_CREATE_TIME, stored.getCreateTime());
        assertEquals("platform-admin", stored.getUpdateBy());
        assertEquals(new Date(333_333L), stored.getUpdateTime());
        assertEquals("平台备注", stored.getRemark());
    }

    @Test
    void legacyLeaderProfileCannotBypassMerchantIdentityWhenEditingProfile() throws Exception {
        persisted.set(activeLeaderProfile());
        when(clubService.saveLeaderProfileWithinIdentityMutex(any(Long.class), any(ClubLeader.class)))
                .thenThrow(new ServiceException(
                        "您已注册为商户,一个账户不能同时是商户与俱乐部主理人", HttpStatus.CONFLICT));

        mvc.perform(post("/api/club/become-leader")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousJson("商家不得恢复主理人")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.msg").value("您已注册为商户,一个账户不能同时是商户与俱乐部主理人"));

        verify(leaderService, never()).updateClubLeader(any());
        verify(capabilityService, never()).grant(any(), any(), any());
    }

    @Test
    void legacyLeaderProfileCannotBypassMerchantIdentityWhenCreatingClub() throws Exception {
        persisted.set(activeLeaderProfile());
        when(clubService.insertClubWithinOwnerQuota(any(Club.class), any(Integer.class)))
                .thenThrow(new ServiceException(
                        "您已注册为商户,一个账户不能同时是商户与俱乐部主理人", HttpStatus.CONFLICT));

        mvc.perform(post("/api/club/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"不应创建的俱乐部\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.msg").value("您已注册为商户,一个账户不能同时是商户与俱乐部主理人"));

        verify(clubService).insertClubWithinOwnerQuota(any(), any(Integer.class));
    }

    private ClubLeader activeLeaderProfile() {
        ClubLeader existing = new ClubLeader();
        existing.setId(77L);
        existing.setMemberId(MEMBER_ID);
        existing.setLeaderName("历史主理人");
        existing.setStatus(1);
        existing.setLevel(0);
        existing.setDelFlag(0);
        return existing;
    }

    private String maliciousJson(String leaderName) {
        return "{"
                + "\"id\":999,\"memberId\":888,\"leaderName\":\"" + leaderName + "\","
                + "\"level\":5,\"tags\":\"伪造官方标签\",\"status\":9,"
                + "\"auditBy\":\"attacker\",\"auditTime\":\"2026-08-25 12:00:00\","
                + "\"createBy\":\"attacker\",\"createTime\":\"2026-08-25 12:00:00\","
                + "\"updateBy\":\"attacker\",\"updateTime\":\"2026-08-25 12:00:00\","
                + "\"remark\":\"forged\",\"delFlag\":2} ";
    }

    private void applyMapperPatch(ClubLeader target, ClubLeader patch) {
        if (patch.getLeaderName() != null) target.setLeaderName(patch.getLeaderName());
        if (patch.getMemberId() != null) target.setMemberId(patch.getMemberId());
        if (patch.getLevel() != null) target.setLevel(patch.getLevel());
        if (patch.getTags() != null) target.setTags(patch.getTags());
        if (patch.getStatus() != null) target.setStatus(patch.getStatus());
        if (patch.getAuditBy() != null) target.setAuditBy(patch.getAuditBy());
        if (patch.getAuditTime() != null) target.setAuditTime(patch.getAuditTime());
        if (patch.getUpdateBy() != null) target.setUpdateBy(patch.getUpdateBy());
        if (patch.getUpdateTime() != null) target.setUpdateTime(patch.getUpdateTime());
        if (patch.getRemark() != null) target.setRemark(patch.getRemark());
    }
}
