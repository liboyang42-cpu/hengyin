package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.BizEventLog;
import com.chengyinhub.business.domain.vo.RecommendationItemVO;
import com.chengyinhub.business.mapper.MemberInterestProfileMapper;
import com.chengyinhub.business.mapper.RecommendationScoreDailyMapper;
import com.chengyinhub.business.service.IRecommendationService;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * P3 推荐服务实现。
 */
@Service
public class RecommendationServiceImpl implements IRecommendationService {

    @Autowired
    private RecommendationScoreDailyMapper recommendationScoreDailyMapper;

    @Autowired
    private MemberInterestProfileMapper memberInterestProfileMapper;

    @Override
    public void updateProfileFromEvent(BizEventLog event) {
        if (event == null || event.getMemberId() == null) {
            return;
        }
        int weight = eventWeight(event.getEventName());
        if (weight <= 0) {
            return;
        }
        String key = categoryKey(event.getBizType());
        Date now = DateUtils.getNowDate();
        memberInterestProfileMapper.incrementMemberCategoryWeight(
                event.getMemberId(),
                event.getCityCode(),
                "$." + key,
                weight,
                event.getOccurredAt() == null ? new Date() : event.getOccurredAt(),
                now,
                now);
    }

    @Override
    public List<RecommendationItemVO> listForMember(Long memberId, String candidateType, int limit) {
        int safeLimit = limit <= 0 || limit > 50 ? 20 : limit;
        List<RecommendationItemVO> candidates = recommendationScoreDailyMapper.selectCandidateItems(memberId, candidateType, safeLimit);
        List<RecommendationItemVO> result = new ArrayList<>();
        for (RecommendationItemVO item : candidates) {
            if (isVisible(item)) {
                result.add(item);
            }
        }
        if (result.isEmpty()) {
            for (RecommendationItemVO item : recommendationScoreDailyMapper.selectColdStartItems(candidateType, safeLimit)) {
                if (isVisible(item)) {
                    result.add(item);
                }
            }
        }
        return result;
    }

    private int eventWeight(String eventName) {
        if ("community_post_qualified_view".equals(eventName)) return 1;
        if ("content_view".equals(eventName)) return 1;
        if ("content_like".equals(eventName)) return 3;
        if ("content_favorite".equals(eventName)) return 4;
        if ("signup_submit".equals(eventName)) return 5;
        // owner 2026-10-23 D2(P0-33/P0-77):付费不再压过浏览,与 content_view 同权;打分任务保持暂停
        if ("payment_success".equals(eventName)) return 1;
        return 0;
    }

    private String categoryKey(String bizType) {
        if (bizType != null && bizType.matches("[a-z][a-z0-9_]{0,39}")) {
            return bizType;
        }
        return "general";
    }

    private boolean isVisible(RecommendationItemVO item) {
        if (item == null) return false;
        if (item.getStatus() == null || item.getStatus() != 1) return false;
        if (item.getPublishStatus() != null && item.getPublishStatus() != 1) return false;
        return item.getDelFlag() == null || item.getDelFlag() == 0;
    }
}
