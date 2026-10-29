package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.impl.NodeNpcService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 节点 NPC 端点的门禁合同(CR-927):
 * 节点归属走解析后的 ownerMemberId，声音素材与单独同意保留真实登录者；没权限不落到 service;
 * openid 透传给内容安全;未配过 NPC 的 status 端点回 0 而不是报错。
 */
class ApiChapterMerchantNodeNpcAccessTest {

    private static final long PRINCIPAL = 901L;
    private static final long OWNER = 900L;
    private static final long NODE = 31L;
    private static final String OPENID = "o_openid";

    private ApiChapterMerchantNodeController controller(NodeNpcService npc, IMerchantAccessService access,
                                                        IUmsMemberService members) {
        ApiChapterMerchantNodeController controller = new ApiChapterMerchantNodeController() {
            @Override
            public Long getAppUserId() {
                return PRINCIPAL;
            }
        };
        ReflectionTestUtils.setField(controller, "nodeNpcService", npc);
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        ReflectionTestUtils.setField(controller, "umsMemberService", members);
        return controller;
    }

    private static MerchantAccessContext ownerAccess() {
        return new MerchantAccessContext(PRINCIPAL, 11L, OWNER, MerchantRoleCode.MERCHANT_MANAGER);
    }

    private static ApiChapterMerchantNodeController.NodeNpcRequest request(Long nodeId) {
        ApiChapterMerchantNodeController.NodeNpcRequest req =
                new ApiChapterMerchantNodeController.NodeNpcRequest();
        req.setNodeId(nodeId);
        req.setName("阿福");
        req.setAvatar("px1:p01");
        req.setGreeting("欢迎光临");
        return req;
    }

    @Test
    void saveUsesResolvedOwnerAndPassesOpenid() {
        NodeNpcService npc = mock(NodeNpcService.class);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        IUmsMemberService members = mock(IUmsMemberService.class);
        when(access.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE)).thenReturn(ownerAccess());
        UmsMember member = new UmsMember();
        member.setWxOpenId(OPENID);
        when(members.selectUmsMemberById(OWNER)).thenReturn(member);
        when(npc.save(eq(OWNER), eq(NODE), any(NpcProfile.class), eq(OPENID)))
                .thenReturn(new NpcProfile());

        AjaxResult result = controller(npc, access, members).npcSave(request(NODE));

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(npc).save(eq(OWNER), eq(NODE), any(NpcProfile.class), eq(OPENID));
        verify(npc, never()).save(eq(PRINCIPAL), any(), any(), any());
    }

    @Test
    void saveRejectedWhenMerchantInactive() {
        NodeNpcService npc = mock(NodeNpcService.class);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenThrow(new ServiceException("商家身份无效或权限不足", HttpStatus.FORBIDDEN));

        AjaxResult result = controller(npc, access, null).npcSave(request(NODE));

        assertEquals(HttpStatus.FORBIDDEN, result.get(AjaxResult.CODE_TAG));
        verify(npc, never()).save(any(), any(), any(), any());
    }

    @Test
    void saveRejectsMissingNodeIdBeforeService() {
        NodeNpcService npc = mock(NodeNpcService.class);
        AjaxResult result = controller(npc, mock(IMerchantAccessService.class), null).npcSave(request(null));
        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertNull(result.get(AjaxResult.DATA_TAG));
        verify(npc, never()).save(any(), any(), any(), any());
    }

    @Test
    void detailSurfacesGateFailureMessage() {
        NodeNpcService npc = mock(NodeNpcService.class);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE)).thenReturn(ownerAccess());
        when(npc.detailForMerchant(OWNER, NODE))
                .thenThrow(new IllegalStateException("承接已失效或未生效，不能编辑节点内容"));

        AjaxResult result = controller(npc, access, null).npcDetail(request(NODE));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("承接已失效或未生效，不能编辑节点内容", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void voiceStatusWithoutNpcReturnsConfiguredZero() {
        NodeNpcService npc = mock(NodeNpcService.class);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE)).thenReturn(ownerAccess());
        when(npc.voiceStatus(PRINCIPAL, OWNER, NODE, null)).thenReturn(null);

        AjaxResult result = controller(npc, access, null).npcVoiceStatus(request(NODE));

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        assertEquals(NodeNpcService.VOICE_NONE, data.get("voiceStatus"), "没配过也回 0,前端按未配置渲染");
    }

    @Test
    void voiceEnrollKeepsManagerActorSeparateFromNodeOwner() {
        NodeNpcService npc = mock(NodeNpcService.class);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE)).thenReturn(ownerAccess());
        ApiChapterMerchantNodeController.NodeNpcRequest req = request(NODE);
        req.setVoiceSample("/profile/voice/a.mp3");

        AjaxResult result = controller(npc, access, null).npcVoiceEnroll(req);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(npc).voiceEnroll(PRINCIPAL, OWNER, NODE, "/profile/voice/a.mp3", null, null, false);
    }
}
