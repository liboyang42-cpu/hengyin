package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CreatorProfile;
import com.chengyinhub.business.domain.dto.CreatorProfileApplyDTO;
import com.chengyinhub.business.domain.dto.CreatorReviewDTO;
import com.chengyinhub.business.domain.vo.CreatorCenterVO;

import java.util.List;

/**
 * P3 创作者中心服务。
 */
public interface ICreatorCenterService {
    CreatorCenterVO getCenter(Long memberId);

    int apply(Long memberId, CreatorProfileApplyDTO request);

    int aggregateDaily(String statDate);

    List<CreatorProfile> selectAdminCreatorList(CreatorProfile query);

    int review(CreatorReviewDTO request, Long reviewerId);
}
