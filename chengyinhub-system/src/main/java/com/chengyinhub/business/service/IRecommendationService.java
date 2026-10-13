package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.BizEventLog;
import com.chengyinhub.business.domain.vo.RecommendationItemVO;

import java.util.List;

/**
 * P3 推荐服务。
 */
public interface IRecommendationService {
    void updateProfileFromEvent(BizEventLog event);

    List<RecommendationItemVO> listForMember(Long memberId, String candidateType, int limit);
}
