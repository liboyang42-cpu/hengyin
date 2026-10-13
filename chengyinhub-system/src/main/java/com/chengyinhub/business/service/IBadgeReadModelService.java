package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.BadgeWallV2VO;

public interface IBadgeReadModelService {
    BadgeWallV2VO buildWallV2(Long memberId);
}
