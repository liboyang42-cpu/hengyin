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

    /** 被驳回的申请重新提交:仅本人、仅 apply_status=2 时回到待审。 */
    int resubmitRejectedCreatorProfile(CreatorProfile profile);
}
