package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.exception.TopicPricingRuleMissingException;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPricingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ApiPricingPreviewRuleTest {
    private ApiPricingController controller(Long owner, IPricingService pricing) {
        ApiPricingController controller = new ApiPricingController() {
            @Override public Long getAppUserId() { return 7L; }
        };
        CmsTopic topic = new CmsTopic();
        topic.setId(81L);
        topic.setMemberId(owner);
        ICmsTopicService topics = mock(ICmsTopicService.class);
        when(topics.selectCmsTopicById(81L)).thenReturn(topic);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topics);
        ReflectionTestUtils.setField(controller, "pricingService", pricing);
        return controller;
    }

    @ParameterizedTest
    @ValueSource(strings = {"guided", "self", "free"})
    void missingRuleIsActionableAndNeverFallsBackToAPrice(String subType) {
        IPricingService pricing = mock(IPricingService.class);
        when(pricing.previewTopic(eq(81L), any(), any(), eq(subType)))
                .thenThrow(new TopicPricingRuleMissingException("internal settlement_rule details"));
        var result = controller(7L, pricing).preview(Map.of("topicId", 81L, "subType", subType));
        assertEquals(500, result.get("code"));
        assertEquals("平台费率暂未配置，请联系平台后重试", result.get("msg"));
        assertEquals(null, result.get("data"));
    }

    @Test
    void nonOwnerCannotReadPricingEvenWhenRuleIsMissing() {
        IPricingService pricing = mock(IPricingService.class);
        var result = controller(8L, pricing).preview(Map.of("topicId", 81L));
        assertEquals("仅主题发布者可定价", result.get("msg"));
        verifyNoInteractions(pricing);
    }
}
