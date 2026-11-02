package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.vo.TopicSaleReviewProgress;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.ITopicCommercialReviewService;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P-01：俱乐部自己的付费主题能开售，但在线收款仍关；锁价回执要如实告诉主办方。 */
class ApiPricingControllerPaymentNoteTest {

    private static final long TOPIC = 77L;
    private static final long MEMBER = 9003L;
    private ApiPricingController controller;
    private IProductRoleService roles;

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
        roles = mock(IProductRoleService.class);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topics);
        ReflectionTestUtils.setField(controller, "topicCommercialReviewService", review);
        ReflectionTestUtils.setField(controller, "productRoleService", roles);
    }

    private String message(boolean paid) {
        return ReflectionTestUtils.invokeMethod(controller, "afterConfirmMessage", TOPIC, MEMBER, paid);
    }

    @Test
    void paidClubThemeSaysPaymentIsNotOpenYet() {
        when(roles.isPlatformOwned(TOPIC)).thenReturn(false);
        assertEquals("终价已确认，已提交开售审核，审核通过后开售。" + ApiPricingController.PAYMENT_NOT_OPEN_NOTE,
                message(true));
    }

    @Test
    void platformThemeAndFreeThemeKeepTheOldMessage() {
        when(roles.isPlatformOwned(TOPIC)).thenReturn(true);
        assertFalse(message(true).contains("支付资质"));
        String free = message(false);
        assertFalse(free.contains("支付资质"));
        assertTrue(free.startsWith("终价已确认"));
    }

    @Test
    void freeThemeDoesNotEvenAsk() {
        message(false);
        verify(roles, never()).isPlatformOwned(TOPIC);
    }
}
