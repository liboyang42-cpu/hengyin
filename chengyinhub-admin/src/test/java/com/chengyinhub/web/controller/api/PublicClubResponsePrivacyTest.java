package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.service.IClubLeaderService;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class PublicClubResponsePrivacyTest {

    private ApiClubController controller;
    private IClubService clubService;
    private IClubMemberService memberService;
    private ICmsTopicService topicService;
    private Club sensitiveClub;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiClubController());
        doThrow(new RuntimeException("anonymous")).when(controller).getAppUserId();
        clubService = mock(IClubService.class);
        memberService = mock(IClubMemberService.class);
        topicService = mock(ICmsTopicService.class);
        ReflectionTestUtils.setField(controller, "clubService", clubService);
        ReflectionTestUtils.setField(controller, "clubMemberService", memberService);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "clubLeaderService", mock(IClubLeaderService.class));

        sensitiveClub = new Club();
        sensitiveClub.setId(42L);
        sensitiveClub.setMemberId(998877L);
        sensitiveClub.setName("公开夜跑团");
        sensitiveClub.setLogo("https://cdn/logo.png");
        sensitiveClub.setCover("https://cdn/cover.png");
        sensitiveClub.setDescription("公开简介");
        sensitiveClub.setClubType("夜跑");
        sensitiveClub.setCity("上海");
        sensitiveClub.setStyle("轻松");
        sensitiveClub.setLeaderName("主理人公开称呼");
        sensitiveClub.setPhone("13800138000");
        sensitiveClub.setAddress("申请时填写的详细住址");
        sensitiveClub.setJoinReason("敏感申请理由");
        sensitiveClub.setCertImages("https://cdn/private-cert.png");
        sensitiveClub.setRejectReason("内部驳回原因");
        sensitiveClub.setAuditBy("internal-auditor");
        when(memberService.countByClub(42L)).thenReturn(0);
        when(memberService.selectByClubAndMember(any(), any())).thenReturn(null);
        when(topicService.selectCmsTopicList(any())).thenReturn(Collections.emptyList());
    }

    @Test
    void publicListReturnsWhitelistVoWithoutOwnerContactOrApplicationFields() throws Exception {
        when(clubService.selectClubList(any())).thenReturn(Collections.singletonList(sensitiveClub));

        AjaxResult result = controller.list(new Club());

        Object first = ((java.util.List<?>) result.get("data")).get(0);
        assertEquals("PublicClubVO", first.getClass().getSimpleName());
        assertPublicJsonIsSafe(new ObjectMapper().writeValueAsString(result));
    }

    @Test
    void publicDetailReturnsWhitelistVoWithoutOwnerContactOrApplicationFields() throws Exception {
        when(clubService.selectClubById(42L)).thenReturn(sensitiveClub);
        Club request = new Club();
        request.setId(42L);

        AjaxResult result = controller.detail(request);

        assertEquals("PublicClubVO", result.get("data").getClass().getSimpleName());
        assertPublicJsonIsSafe(new ObjectMapper().writeValueAsString(result));
    }

    @Test
    void ownerAuthenticatedEndpointStillReturnsOwnContactDetails() throws Exception {
        doReturn(998877L).when(controller).getAppUserId();
        when(clubService.selectClubsByMemberId(998877L))
                .thenReturn(Collections.singletonList(sensitiveClub));
        when(memberService.selectClubIdsByMember(998877L)).thenReturn(Collections.emptyList());
        when(topicService.selectUpcomingTopicCounts(Collections.singletonList(42L)))
                .thenReturn(Collections.emptyMap());

        AjaxResult result = controller.my();
        Map<?, ?> data = (Map<?, ?>) result.get("data");
        Object owned = ((java.util.List<?>) data.get("owned")).get(0);
        String json = new ObjectMapper().writeValueAsString(owned);

        assertEquals("Club", owned.getClass().getSimpleName());
        assertTrue(json.contains("13800138000"), "主理人自己的受权端点仍应返回原始联系电话");
    }

    private void assertPublicJsonIsSafe(String json) {
        assertFalse(json.contains("13800138000"), "公开响应不得包含主理人电话");
        assertFalse(json.contains("详细住址"), "公开响应不得包含申请地点");
        assertFalse(json.contains("敏感申请理由"), "公开响应不得包含申请理由");
        assertFalse(json.contains("private-cert"), "公开响应不得包含资质原图");
        assertFalse(json.contains("内部驳回原因"), "公开响应不得包含审核内部字段");
        assertFalse(json.contains("internal-auditor"), "公开响应不得包含审核人");
        assertTrue(json.contains("998877"), "公开成员治理仍需主理人会员 ID 识别 owner 行");
        assertTrue(json.contains("公开夜跑团"), "公开资料仍需可展示");
    }
}
