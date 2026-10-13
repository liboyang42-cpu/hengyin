package com.chengyinhub.business.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 官方通知定向圈人 Mapper:按角色 × 城市圈 memberId。
 * 玩家=ums_member.role='player'(城市取 work_address 模糊)/ 俱乐部=club.city / 商家=mms_merchant.address|city_role。
 * city 为 null 时不加城市过滤(全国全体该角色)。
 *
 * @author cy
 * @date 2026-07-08
 */
public interface OfficialAudienceMapper
{
    List<Long> selectPlayerIds(@Param("city") String city);

    List<Long> selectClubMemberIds(@Param("city") String city);

    List<Long> selectMerchantMemberIds(@Param("city") String city);
}
