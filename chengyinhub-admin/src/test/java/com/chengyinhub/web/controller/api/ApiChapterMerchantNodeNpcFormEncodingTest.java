package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.impl.NodeNpcService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.LinkedHashMap;

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
 * iOS 客户端对 /api/merchant/chapter-node/npc/* 发 application/x-www-form-urlencoded，
 * 小程序发 application/json；服务端需同时兼容两种 Content-Type，不再回 415/500。
 */
class ApiChapterMerchantNodeNpcFormEncodingTest {

    private static final long PRINCIPAL = 9002L;
    private static final long OWNER = 9002L;
    private static final long NODE = 992021L;

    private NodeNpcService npcService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        npcService = mock(NodeNpcService.class);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        IUmsMemberService members = mock(IUmsMemberService.class);
        when(access.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(new MerchantAccessContext(PRINCIPAL, 8001L, OWNER, MerchantRoleCode.MERCHANT_OWNER));
        UmsMember member = new UmsMember();
        member.setWxOpenId("open_9002");
        when(members.selectUmsMemberById(OWNER)).thenReturn(member);

        when(npcService.detailForMerchant(OWNER, NODE)).thenReturn(new NpcProfile());
        when(npcService.save(eq(OWNER), eq(NODE), any(NpcProfile.class), eq("open_9002")))
                .thenReturn(new NpcProfile());
        when(npcService.voiceEnroll(PRINCIPAL, OWNER, NODE, "https://sample", "req-1", null, false))
                .thenReturn(new NpcProfile());
        when(npcService.voiceStatus(PRINCIPAL, OWNER, NODE, null)).thenReturn(new NpcProfile());
        when(npcService.voiceHistory(PRINCIPAL, OWNER, NODE)).thenReturn(Collections.emptyList());

        ApiChapterMerchantNodeController controller = new ApiChapterMerchantNodeController() {
            @Override
            public Long getAppUserId() {
                return PRINCIPAL;
            }
        };
        ReflectionTestUtils.setField(controller, "nodeNpcService", npcService);
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        ReflectionTestUtils.setField(controller, "umsMemberService", members);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void npcEndpointsAcceptBothFormUrlEncodedAndJson() throws Exception {
        mockMvc.perform(post("/api/merchant/chapter-node/npc/detail")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("nodeId", String.valueOf(NODE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/merchant/chapter-node/npc/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeId\":" + NODE + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/merchant/chapter-node/npc/save")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("nodeId", String.valueOf(NODE))
                        .param("name", "阿福"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/merchant/chapter-node/npc/voice/enroll")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("nodeId", String.valueOf(NODE))
                        .param("voiceSample", "https://sample")
                        .param("voiceRequestId", "req-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/merchant/chapter-node/npc/voice/status")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("nodeId", String.valueOf(NODE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/merchant/chapter-node/npc/voice/reset")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("nodeId", String.valueOf(NODE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(npcService, times(2)).detailForMerchant(OWNER, NODE);
        verify(npcService).voiceReset(OWNER, NODE);
    }
}
