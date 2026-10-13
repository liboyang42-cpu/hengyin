package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.ClubLifecycleCommandResultVO;

public interface IClubLifecycleCommandService {
    ClubLifecycleCommandResultVO removeMember(Long clubId, Long memberId, Long actorMemberId,
                                               String requestId);

    ClubLifecycleCommandResultVO quit(Long clubId, Long memberId, String requestId);

    ClubLifecycleCommandResultVO dissolve(Long clubId, Long ownerId, boolean confirmed,
                                           boolean memberConsequencesConfirmed, String requestId);
}
