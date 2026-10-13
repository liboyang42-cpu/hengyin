package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;

public interface MemberInvitationMapper {

    int bindInviterIfAbsent(@Param("memberId") Long memberId,
                            @Param("inviterId") Long inviterId);
}
