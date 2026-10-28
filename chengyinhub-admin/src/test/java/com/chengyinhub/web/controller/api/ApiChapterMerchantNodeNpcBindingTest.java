package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.service.impl.NodeNpcService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 节点 NPC 端点的 MVC 绑定合同:小程序一律用 JSON 体发请求(`app.sendRequest` + Content-Type: application/json)。
 *
 * <p>为什么单列一条:Mockito 直调 controller 方法的测试**绕过参数绑定**,
 * 端点签名写错(比如把 @RequestBody 漏掉)在那种测试里永远绿 —— 而线上每个请求都会是「参数有误」。
 * 这条走真 MockMvc,锁的是「JSON 体里的 nodeId 真能被绑上」。
 */
class ApiChapterMerchantNodeNpcBindingTest {

    private static final long PRINCIPAL = 901L;
    private static final long OWNER = 900L;
    private static final long NODE = 31L;

    private ApiChapterMerchantNodeController controller(NodeNpcService npc, IMerchantAccessService access) {
        ApiChapterMerchantNodeController controller = new ApiChapterMerchantNodeController() {
            @Override
            public Long getAppUserId() {
                return PRINCIPAL;
            }
        };
        ReflectionTestUtils.setField(controller, "nodeNpcService", npc);
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        return controller;
    }

    private static IMerchantAccessService ownerAccess() {
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(new MerchantAccessContext(PRINCIPAL, 11L, OWNER, MerchantRoleCode.MERCHANT_MANAGER));
        return access;
    }

    @Test
    void detailBindsNodeIdFromJsonBody() throws Exception {
        NodeNpcService npc = mock(NodeNpcService.class);
        NpcProfile row = new NpcProfile();
        row.setId(555L);
        row.setName("阿福");
        when(npc.detailForMerchant(OWNER, NODE)).thenReturn(row);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(npc, ownerAccess())).build();

        mvc.perform(post("/api/merchant/chapter-node/npc/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeId\":" + NODE + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("阿福"));

        verify(npc).detailForMerchant(OWNER, NODE);
    }

    @Test
    void saveBindsJsonBody() throws Exception {
        NodeNpcService npc = mock(NodeNpcService.class);
        when(npc.save(eq(OWNER), eq(NODE), any(NpcProfile.class), any())).thenReturn(new NpcProfile());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(npc, ownerAccess())).build();

        mvc.perform(post("/api/merchant/chapter-node/npc/save")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeId\":" + NODE + ",\"name\":\"阿福\",\"avatar\":\"px1:p01\",\"greeting\":\"欢迎\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(npc).save(eq(OWNER), eq(NODE), any(NpcProfile.class), any());
    }

    @Test
    void voiceStatusBindsJsonBodyAndReturnsZeroWhenAbsent() throws Exception {
        NodeNpcService npc = mock(NodeNpcService.class);
        when(npc.voiceStatus(PRINCIPAL, OWNER, NODE, null)).thenReturn(null);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(npc, ownerAccess())).build();

        mvc.perform(post("/api/merchant/chapter-node/npc/voice/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeId\":" + NODE + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.voiceStatus").value(0));

        verify(npc).voiceStatus(PRINCIPAL, OWNER, NODE, null);
    }
}
