package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.RegistrationSeatInvitationRequestRecord;
import org.apache.ibatis.annotations.Param;

/** 邀请/领取/撤销/释放的 requestId 幂等回执。 */
public interface RegistrationSeatInvitationRequestMapper {

    RegistrationSeatInvitationRequestRecord selectByRequestId(@Param("memberId") Long memberId,
                                                              @Param("requestId") String requestId);

    RegistrationSeatInvitationRequestRecord selectByRequestIdForUpdate(@Param("memberId") Long memberId,
                                                                      @Param("requestId") String requestId);
    int insert(RegistrationSeatInvitationRequestRecord row);
}
