package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;

import com.chengyinhub.business.domain.UmsMemberProfileVersion;

/** 个人资料版本化提交记录(#25)。表 ums_member_profile_version,见 migration_member_profile_version_20261022.sql。 */
public interface UmsMemberProfileVersionMapper
{
    /** 版本号 = 该会员当前最大版本 + 1;并发撞唯一键由调用方重试。 */
    int insertNextVersion(UmsMemberProfileVersion row);

    UmsMemberProfileVersion selectLatest(@Param("memberId") Long memberId);

    /** 最近一次改了头像的版本;从没改过返回 null。 */
    UmsMemberProfileVersion selectLatestAvatarVersion(@Param("memberId") Long memberId);
}
