package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.RoamExploreDayCandidateVO;

public interface IRoamExploreDayCandidateService {
    RoamExploreDayCandidateVO findNearestEligible(double lat, double lng);
}
