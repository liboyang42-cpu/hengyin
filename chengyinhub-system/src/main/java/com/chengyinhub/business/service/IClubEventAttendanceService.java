package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubEventAttendanceCorrection;
import com.chengyinhub.business.domain.dto.ClubAttendanceCorrectionRequest;

import java.util.Map;

public interface IClubEventAttendanceService {
    Map<String, Object> roster(Long actorMemberId, Long clubId, Long activityId);
    ClubEventAttendanceCorrection correct(Long actorMemberId, ClubAttendanceCorrectionRequest request);
}
