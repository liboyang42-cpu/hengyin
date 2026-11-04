package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.domain.dto.TopicSettlementDTO;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ROUND10 j1 F3:主题分润拆分与活动同口径 —— 平台、发布者各自四舍五入,商家拿余数,
 * 三份之和恒等于销售额。¥97.50×3% 时旧代码给 2.93 + 94.58 = 97.51。
 */
class TopicSettlementSplitRemainderTest {

    @Test
    void halfFenSalesSplitSumsExactlyToSales() {
        SettlementRule rule = new SettlementRule();
        rule.setPlatformCommissionRate(new BigDecimal("0.0300"));
        rule.setMerchantRate(new BigDecimal("0.9700"));
        rule.setCreatorRate(new BigDecimal("0.0000"));
        rule.setInviterRate(BigDecimal.ZERO);
        CmsTopic topic = new CmsTopic();
        topic.setId(1L);
        BigDecimal sales = new BigDecimal("97.50");

        TopicSettlementDTO split = ReflectionTestUtils.invokeMethod(new CmsTopicServiceImpl(),
                "calculateCommissions", topic, rule, sales);

        assertEquals(new BigDecimal("2.93"), split.getPlatformCommissionAmount());
        assertEquals(new BigDecimal("94.57"), split.getMerchantTotalCommissionAmount());
        assertEquals(0, sales.compareTo(split.getPlatformCommissionAmount()
                .add(split.getMerchantTotalCommissionAmount())
                .add(split.getPublisherCommissionAmount())));
    }
}
