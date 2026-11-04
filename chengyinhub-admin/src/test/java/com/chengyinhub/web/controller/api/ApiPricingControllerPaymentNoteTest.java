package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.vo.TopicSaleReviewProgress;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ITopicCommercialReviewService;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P-01 → F-04(j2)：俱乐部自己的付费主题能开售，也能在线收款(assertCollectible 自 2026-11-02 起只拒查不到票款归属的单)。
 * 旧断言「锁价回执附『平台正在办理支付资质』」编码的是改前的决定，已随业主拍板改为：回执不再提收不了款。
 */
class ApiPricingControllerPaymentNoteTest {

    private static final long TOPIC = 77L;
    private static final long MEMBER = 9003L;
    private ApiPricingController controller;

    @BeforeEach
    void setUp() {
        controller = new ApiPricingController();
        ICmsTopicService topics = mock(ICmsTopicService.class);
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setProductType(ProductType.ORIENTEERING);
        when(topics.selectCmsTopicById(TOPIC)).thenReturn(topic);
        ITopicCommercialReviewService review = mock(ITopicCommercialReviewService.class);
        TopicSaleReviewProgress progress = mock(TopicSaleReviewProgress.class);
        when(progress.getTaskId()).thenReturn(501L);
        when(review.submitSaleReviewIfReady(TOPIC, MEMBER)).thenReturn(progress);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topics);
        ReflectionTestUtils.setField(controller, "topicCommercialReviewService", review);
    }

    @Test
    void paidClubThemeNoLongerSaysPlayersCannotPay() {
        String message = ReflectionTestUtils.invokeMethod(controller, "afterConfirmMessage", TOPIC, MEMBER);
        assertEquals("终价已确认，已提交开售审核，审核通过后开售", message);
        assertFalse(message.contains("支付资质"));
        assertFalse(message.contains("不能在线付款"));
    }
}
