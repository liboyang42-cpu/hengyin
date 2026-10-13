package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MemberSpoilerReveal;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SpoilerProtectionMapper {
    Boolean selectRevealAll(@Param("memberId") Long memberId);
    int upsertPreference(@Param("memberId") Long memberId, @Param("revealAll") boolean revealAll);
    List<Long> selectRevealedContentIds(@Param("memberId") Long memberId,
                                        @Param("contentType") String contentType);
    MemberSpoilerReveal selectRevealByRequestId(@Param("memberId") Long memberId,
                                                 @Param("requestId") String requestId);
    int insertReveal(MemberSpoilerReveal reveal);
}
