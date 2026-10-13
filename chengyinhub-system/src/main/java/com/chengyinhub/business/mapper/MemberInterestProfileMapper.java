package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MemberInterestProfile;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

/**
 * P3 会员兴趣画像 Mapper。
 */
public interface MemberInterestProfileMapper {
    MemberInterestProfile selectMemberInterestProfileByMemberId(Long memberId);

    int upsertMemberInterestProfile(MemberInterestProfile profile);

    int incrementMemberCategoryWeight(@Param("memberId") Long memberId,
                                      @Param("cityCode") String cityCode,
                                      @Param("categoryPath") String categoryPath,
                                      @Param("weight") Integer weight,
                                      @Param("lastEventAt") Date lastEventAt,
                                      @Param("createTime") Date createTime,
                                      @Param("updateTime") Date updateTime);
}
