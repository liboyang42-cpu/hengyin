package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CreatorProfile;

import java.util.List;

/**
 * P3 创作者档案 Mapper。
 */
public interface CreatorProfileMapper {
    CreatorProfile selectByMemberId(Long memberId);

    List<CreatorProfile> selectAdminCreatorList(CreatorProfile query);

    int insertCreatorProfile(CreatorProfile profile);

    int updatePendingCreatorProfile(CreatorProfile profile);
}
