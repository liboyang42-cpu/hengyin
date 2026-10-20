package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.service.impl.NodeNpcService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SC-NPC-NODE 验收(端点侧):没有 PROJECT_MANAGE 的身份(组织者 / 普通玩家)调用
 * /npc/save、/npc/voice/enroll、/npc/voice/reset、/npc/detail 全部在
 * {@link IMerchantAccessService#require} 处被拒(403),NodeNpcService 零调用。
 */
class NodeNpcManageRoleAcceptanceTest {

    private static final long NODE = 31L;

    private static ApiChapterMerchantNodeController controller(long principal, NodeNpcService npc,
                                                                IMerchantAccessService access) {
        ApiChapterMerchantNodeController c = new ApiChapterMerchantNodeController() {
            @Override
            public Long getAppUserId() {
                return principal;
            }
        };
        ReflectionTestUtils.setField(c, "nodeNpcService", npc);
        ReflectionTestUtils.setField(c, "merchantAccessService", access);
        return c;
    }

    private static ApiChapterMerchantNodeController.NodeNpcRequest body() {
        ApiChapterMerchantNodeController.NodeNpcRequest r = new ApiChapterMerchantNodeController.NodeNpcRequest();
        r.setNodeId(NODE);
        r.setName("冒名");
        r.setAvatar("px1:p01");
        r.setGreeting("我来改");
        r.setVoiceSample("/profile/a.mp3");
        return r;
    }

    @Test
    void organizerAndPlayerCannotManageStationNpc() {
        long organizer = 501L;
        long player = 502L;
        for (long principal : new long[]{organizer, player}) {
            NodeNpcService npc = mock(NodeNpcService.class);
            IMerchantAccessService access = mock(IMerchantAccessService.class);
            when(access.require(principal, MerchantPermission.PROJECT_MANAGE))
                    .thenThrow(new ServiceException("商家身份无效或权限不足", HttpStatus.FORBIDDEN));
            ApiChapterMerchantNodeController c = controller(principal, npc, access);

            @SuppressWarnings("unchecked")
            Function<ApiChapterMerchantNodeController.NodeNpcRequest, AjaxResult>[] endpoints = new Function[]{
                    (Function<ApiChapterMerchantNodeController.NodeNpcRequest, AjaxResult>) c::npcSave,
                    (Function<ApiChapterMerchantNodeController.NodeNpcRequest, AjaxResult>) c::npcVoiceEnroll,
                    (Function<ApiChapterMerchantNodeController.NodeNpcRequest, AjaxResult>) c::npcVoiceReset,
                    (Function<ApiChapterMerchantNodeController.NodeNpcRequest, AjaxResult>) c::npcDetail,
            };
            for (Function<ApiChapterMerchantNodeController.NodeNpcRequest, AjaxResult> endpoint : endpoints) {
                AjaxResult result = endpoint.apply(body());
                assertEquals(HttpStatus.FORBIDDEN, result.get(AjaxResult.CODE_TAG), result.toString());
            }
            verify(access, org.mockito.Mockito.times(4)).require(principal, MerchantPermission.PROJECT_MANAGE);
            verifyNoInteractions(npc);
        }
    }
}
