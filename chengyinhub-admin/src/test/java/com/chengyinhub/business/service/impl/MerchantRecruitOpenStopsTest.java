package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M-28:锁价后的自由探索主题没有可承接章节,不算「开放商机」;城市定向与未知生命周期不受影响。 */
class MerchantRecruitOpenStopsTest {

    private static CmsTopic topic(int productType, Integer lifecycle) {
        CmsTopic t = new CmsTopic();
        t.setProductType(productType);
        t.setLifecycle(lifecycle);
        return t;
    }

    @Test
    void freeExploreOnSaleIsNotAnOpenOpportunity() {
        assertTrue(MerchantAggregateReadServiceImpl.chapterTermsFrozen(topic(2, 3)), "售票中");
        assertTrue(MerchantAggregateReadServiceImpl.chapterTermsFrozen(topic(2, 2)), "待定价");
        assertFalse(MerchantAggregateReadServiceImpl.chapterTermsFrozen(topic(2, 1)), "招募中仍开放");
        assertFalse(MerchantAggregateReadServiceImpl.chapterTermsFrozen(topic(2, null)), "生命周期未读到不隐藏");
        assertFalse(MerchantAggregateReadServiceImpl.chapterTermsFrozen(topic(1, 3)), "城市定向不走章节招募");
        assertFalse(MerchantAggregateReadServiceImpl.chapterTermsFrozen(null));
    }
}
