package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPlayPredictService;
import com.chengyinhub.business.service.IPricingService;
import com.chengyinhub.business.service.impl.MemberIdentityService;
import com.chengyinhub.business.service.impl.MerchantCommerceService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SMOKE-500-R4：商家侧 catch 后 error(e.getMessage()) 把参数错误/状态冲突压成 500。
 * 这里锁住订单状态、竞猜结算、确认终价三条路径改为 4xx，且原文案不变。
 */
class MerchantSmokeRefusalCodeTest {
    private static final long PRINCIPAL = 9002L;
    private static final long OWNER = 9001L;
    private static final long MERCHANT_ROW = 11L;

    private static IMerchantAccessService access() {
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(eq(PRINCIPAL), anyString())).thenReturn(
                new MerchantAccessContext(PRINCIPAL, MERCHANT_ROW, OWNER, MerchantRoleCode.MERCHANT_MANAGER));
        return access;
    }

    @Test
    void foreignOrUnknownCommerceOrderIsBadRequest() {
        MerchantCommerceService commerce = mock(MerchantCommerceService.class);
        when(commerce.orderStatus(OWNER, MERCHANT_ROW, "smoke"))
                .thenThrow(new IllegalArgumentException("无权查看该订单"));
        ApiMerchantCommerceController controller = new ApiMerchantCommerceController() {
            @Override
            public Long getAppUserId() { return PRINCIPAL; }
        };
        ReflectionTestUtils.setField(controller, "merchantAccessService", access());
        ReflectionTestUtils.setField(controller, "commerceService", commerce);

        AjaxResult result = controller.orderStatus(Map.of("orderSn", "smoke"));

        assertEquals(400, result.get(AjaxResult.CODE_TAG));
        assertEquals("无权查看该订单", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void settlingNonPredictNodeIsBadRequest() {
        IPlayPredictService predict = mock(IPlayPredictService.class);
        when(predict.settle(eq(992021L), any(), eq("A"), eq(OWNER)))
                .thenThrow(new IllegalArgumentException("这个点位不是预测玩法"));
        ICmsTopicNodeService nodes = mock(ICmsTopicNodeService.class);
        CmsTopicNode node = new CmsTopicNode();
        node.setMerchantMemberId(OWNER);
        when(nodes.selectCmsTopicNodeById(992021L)).thenReturn(node);
        ApiPredictSettleController controller = new ApiPredictSettleController() {
            @Override
            public Long getAppUserId() { return PRINCIPAL; }
        };
        ReflectionTestUtils.setField(controller, "merchantAccessService", access());
        ReflectionTestUtils.setField(controller, "playPredictService", predict);
        ReflectionTestUtils.setField(controller, "nodeService", nodes);

        AjaxResult result = controller.settle("992021", "2026-11-14", "A");

        assertEquals(400, result.get(AjaxResult.CODE_TAG));
        assertEquals("这个点位不是预测玩法", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void confirmingPriceOutsidePricingStageIsConflict() {
        ICmsTopicService topics = mock(ICmsTopicService.class);
        CmsTopic topic = new CmsTopic();
        topic.setId(992001L);
        topic.setMemberId(PRINCIPAL);
        when(topics.selectCmsTopicById(992001L)).thenReturn(topic);
        MemberIdentityService identity = mock(MemberIdentityService.class);
        when(identity.isRegistered(PRINCIPAL)).thenReturn(true);
        IPricingService pricing = mock(IPricingService.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("主题未在定价阶段,不能确认终价"))
                .when(pricing).confirmPrice(eq(992001L), any(), any(), any(), any(), any());
        ApiPricingController controller = new ApiPricingController() {
            @Override
            public Long getAppUserId() { return PRINCIPAL; }
        };
        ReflectionTestUtils.setField(controller, "cmsTopicService", topics);
        ReflectionTestUtils.setField(controller, "memberIdentityService", identity);
        ReflectionTestUtils.setField(controller, "pricingService", pricing);
        Map<String, Object> body = new HashMap<>();
        body.put("topicId", 992001L);
        body.put("guidedPrice", new BigDecimal("29.0"));

        AjaxResult result = controller.confirm(body);

        assertEquals(409, result.get(AjaxResult.CODE_TAG));
        assertEquals("主题未在定价阶段,不能确认终价", result.get(AjaxResult.MSG_TAG));
    }
}
