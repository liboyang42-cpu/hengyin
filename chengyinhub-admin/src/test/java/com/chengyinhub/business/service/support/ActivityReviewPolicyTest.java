package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.dto.ActivityTicketRequest;
import com.chengyinhub.business.domain.dto.CmsActivityPublishRequest;
import com.chengyinhub.business.domain.dto.PublishSafetyRequest.ReviewMode;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 2026-10-20 分级审核:活动审核模式与收费判定。 */
class ActivityReviewPolicyTest {
    private static final ActivityReviewPolicy.Publisher CLUB = new ActivityReviewPolicy.Publisher(false, false);
    private static final ActivityReviewPolicy.Publisher MERCHANT = new ActivityReviewPolicy.Publisher(true, true);

    private ISysConfigService config;
    private ActivityReviewPolicy policy;

    @BeforeEach
    void setUp() {
        config = mock(ISysConfigService.class);
        policy = new ActivityReviewPolicy();
        ReflectionTestUtils.setField(policy, "configService", config);
        when(config.selectConfigByKey(ActivityReviewPolicy.CONFIG_HIGH_RISK_CATEGORIES)).thenReturn("");
        when(config.selectConfigByKey(ActivityReviewPolicy.CONFIG_PRE_REVIEW_ALL)).thenReturn("false");
    }

    private static CmsActivityPublishRequest request(String categoryIds) {
        CmsActivityPublishRequest r = new CmsActivityPublishRequest();
        r.setName("周末徒步");
        r.setCategoryIds(categoryIds);
        return r;
    }

    @Test
    void ordinaryActivityIsPostReview() {
        assertEquals(ReviewMode.POST_REVIEW, policy.modeOf(request("3"), CLUB));
        assertEquals(ReviewMode.POST_REVIEW, policy.modeOf(request("3"), MERCHANT));
        assertEquals(ReviewMode.POST_REVIEW, policy.modeOf(request(null), CLUB));
    }

    @Test
    void highRiskCategoryIsPreReview() {
        when(config.selectConfigByKey(ActivityReviewPolicy.CONFIG_HIGH_RISK_CATEGORIES)).thenReturn(" 12, 7 ,");

        assertEquals(ReviewMode.PRE_REVIEW, policy.modeOf(request("3,7"), CLUB));
        assertEquals(ReviewMode.PRE_REVIEW, policy.modeOf(request("12"), MERCHANT));
        // 只按完整 id 匹配,17 / 1 不是 7 / 12
        assertEquals(ReviewMode.POST_REVIEW, policy.modeOf(request("17,1"), CLUB));
    }

    @Test
    void preReviewAllValveMakesEverythingPreReview() {
        when(config.selectConfigByKey(ActivityReviewPolicy.CONFIG_PRE_REVIEW_ALL)).thenReturn(" TRUE ");

        assertEquals(ReviewMode.PRE_REVIEW, policy.modeOf(request("3"), CLUB));
        assertEquals(ReviewMode.PRE_REVIEW, policy.modeOf(request(null), MERCHANT));
    }

    @Test
    void unapprovedMerchantIsPreReview() {
        assertEquals(ReviewMode.PRE_REVIEW,
                policy.modeOf(request("3"), new ActivityReviewPolicy.Publisher(true, false)));
    }

    @Test
    void missingPublisherOrRequestIsPreReview() {
        assertEquals(ReviewMode.PRE_REVIEW, policy.modeOf(request("3"), null));
        assertEquals(ReviewMode.PRE_REVIEW, policy.modeOf(null, CLUB));
    }

    @Test
    void configReadFailureIsPreReview() {
        when(config.selectConfigByKey(ActivityReviewPolicy.CONFIG_PRE_REVIEW_ALL))
                .thenThrow(new RuntimeException("redis down"));
        assertEquals(ReviewMode.PRE_REVIEW, policy.modeOf(request("3"), CLUB));

        doReturn("false").when(config).selectConfigByKey(ActivityReviewPolicy.CONFIG_PRE_REVIEW_ALL);
        doThrow(new RuntimeException("redis down"))
                .when(config).selectConfigByKey(ActivityReviewPolicy.CONFIG_HIGH_RISK_CATEGORIES);
        assertEquals(ReviewMode.PRE_REVIEW, policy.modeOf(request("3"), CLUB));
    }

    @Test
    void missingConfigRowsKeepPostReview() {
        when(config.selectConfigByKey(ActivityReviewPolicy.CONFIG_HIGH_RISK_CATEGORIES)).thenReturn(null);
        when(config.selectConfigByKey(ActivityReviewPolicy.CONFIG_PRE_REVIEW_ALL)).thenReturn(null);

        assertEquals(ReviewMode.POST_REVIEW, policy.modeOf(request("3"), CLUB));
    }

    @Test
    void hasChargeOnlyWhenAnyTicketPriceIsPositive() {
        CmsActivityPublishRequest r = request("3");
        assertFalse(policy.hasCharge(r));
        assertFalse(policy.hasCharge(null));
        r.setTickets(Arrays.asList(ticket("0"), null, ticket(null)));
        assertFalse(policy.hasCharge(r));
        r.setTickets(Arrays.asList(ticket("0"), ticket("0.01")));
        assertTrue(policy.hasCharge(r));
    }

    private static ActivityTicketRequest ticket(String price) {
        ActivityTicketRequest t = new ActivityTicketRequest();
        t.setName("票");
        t.setPrice(price == null ? null : new BigDecimal(price));
        return t;
    }
}
