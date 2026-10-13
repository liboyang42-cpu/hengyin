package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MerchantCircleSupplyProfile;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantCircleSupplyProfileMapper;
import com.chengyinhub.business.service.ICircleThemeSupplyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Date;
import java.util.List;

/** 圈层实例公开面：有效商户不在3—4家时不暴露错误配置或半成品。 */
@Service
public class CircleThemeSupplyServiceImpl implements ICircleThemeSupplyService {
    private static final long REVIEW_FRESHNESS_MILLIS = 30L * 24 * 60 * 60 * 1000;
    @Autowired
    private CmsTopicMapper topicMapper;

    @Autowired
    private MerchantCircleSupplyProfileMapper supplyMapper;

    @Override
    public List<MerchantCircleSupplyProfile> listPublicSupplies(Long topicId) {
        if (topicId == null) return Collections.emptyList();
        CmsTopic topic = topicMapper.selectCmsTopicById(topicId);
        if (topic == null || blank(topic.getCircleThemeCode())
                || !Integer.valueOf(1).equals(topic.getStatus())
                || !Integer.valueOf(1).equals(topic.getUserStatus())
                || Integer.valueOf(2).equals(topic.getDelFlag())
                || topic.getCircleReviewedAt() == null
                || new Date().getTime() - topic.getCircleReviewedAt().getTime() > REVIEW_FRESHNESS_MILLIS) {
            return Collections.emptyList();
        }
        int merchantCount = supplyMapper.countEffectiveMerchantsByTopicId(topicId);
        if (merchantCount < 3 || merchantCount > 4) {
            return Collections.emptyList();
        }
        List<MerchantCircleSupplyProfile> supplies = supplyMapper.selectEffectiveByTopicId(topicId);
        return supplies == null ? Collections.emptyList() : supplies;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
