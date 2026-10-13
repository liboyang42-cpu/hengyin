package com.chengyinhub.business.controller;

import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.web.controller.api.ApiTopicController;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * /api/topic/info-to-user 对**本主题承接方**的放行边界。
 *
 * <p>背景:分支图主题下这条接口原本无差别 {@code chapterVO.setNodes(emptyList())},
 * 承接商家自己那一站过审了也永远看不到(Figma 代码待改第 2 条)。2026-09-05 改成按
 * nodeId 白名单放行,白名单由 {@code selectOwnFulfillments} 产出。
 *
 * <p>本测试盯的就是白名单的**构造判据** —— 放行范围一旦写宽,就是把别的商家(甚至
 * 玩家)的 HIDDEN 分支节点整片漏出去。节点过滤那一步只是
 * {@code whitelist.contains(nodeId)},风险全集中在这里。
 */
class TopicInfoFulfillmentVisibilityTest {

    private static CmsRegistrationMerchant row(Long nodeId) {
        CmsRegistrationMerchant r = new CmsRegistrationMerchant();
        r.setNodeId(nodeId);
        return r;
    }

    @SuppressWarnings("unchecked")
    private static List<CmsRegistrationMerchant> invoke(ApiTopicController controller, Long topicId, Long memberId) {
        return (List<CmsRegistrationMerchant>) ReflectionTestUtils.invokeMethod(
                controller, "selectOwnFulfillments", topicId, memberId);
    }

    @Test
    void 白名单只查本人本主题的中标承接单() {
        ApiTopicController controller = new ApiTopicController();
        ICmsRegistrationMerchantService service = mock(ICmsRegistrationMerchantService.class);
        List<CmsRegistrationMerchant> captured = new ArrayList<>();
        doReturn(Arrays.asList(row(11L), row(12L))).when(service)
                .selectCmsRegistrationMerchantList(any(CmsRegistrationMerchant.class));
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantService", service);

        List<CmsRegistrationMerchant> rows = invoke(controller, 9L, 7L);
        assertEquals(2, rows.size());

        org.mockito.ArgumentCaptor<CmsRegistrationMerchant> probe =
                org.mockito.ArgumentCaptor.forClass(CmsRegistrationMerchant.class);
        org.mockito.Mockito.verify(service).selectCmsRegistrationMerchantList(probe.capture());
        CmsRegistrationMerchant p = probe.getValue();
        assertEquals(9L, p.getTopicId(), "没按主题收窄 = 会把别的主题的承接单也算进白名单");
        assertEquals(7L, p.getMemberId(), "没按人收窄 = 会把别的商家的节点放行给当前用户");
        // auditStatus 是**调配状态**(0待调配/1中标/2落选),不是 status(报名材料审核状态)。
        // 用 status 会让「材料过审但没被选中」的商家也看到节点。
        assertEquals(Long.valueOf(1L), p.getAuditStatus(), "必须是中标(auditStatus=1),不是报名过审");
        assertEquals(Integer.valueOf(0), p.getDelFlag(), "被删的承接单不能继续放行节点");
        captured.add(p);
    }

    @Test
    void 未登录或缺参一律空集合并且不查库() {
        ApiTopicController controller = new ApiTopicController();
        ICmsRegistrationMerchantService service = mock(ICmsRegistrationMerchantService.class);
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantService", service);

        assertTrue(invoke(controller, 9L, null).isEmpty(), "未登录必须回落到 teaser");
        assertTrue(invoke(controller, 9L, 0L).isEmpty(), "memberId=0 是「没登录」的常见形态");
        assertTrue(invoke(controller, null, 7L).isEmpty());
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never())
                .selectCmsRegistrationMerchantList(any(CmsRegistrationMerchant.class));
    }

    @Test
    void 查库返回null时按空集处理不得NPE() {
        ApiTopicController controller = new ApiTopicController();
        ICmsRegistrationMerchantService service = mock(ICmsRegistrationMerchantService.class);
        doReturn(null).when(service).selectCmsRegistrationMerchantList(any(CmsRegistrationMerchant.class));
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantService", service);

        assertTrue(invoke(controller, 9L, 7L).isEmpty());
    }
}
