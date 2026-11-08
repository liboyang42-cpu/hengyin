package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MarketingTier;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/** 人数阶梯价读写。只读写 marketing_tier。 */
public interface MarketingTierMapper {

    List<MarketingTier> selectActiveByScope(@Param("ownerType") Integer ownerType,
                                            @Param("ownerId") Long ownerId,
                                            @Param("now") Date now);

    List<MarketingTier> selectTierList(MarketingTier query);

    MarketingTier selectTierById(@Param("id") Long id);

    /**
     * 按业务键 (owner_type, owner_id, ticket_id, min_quantity) 取<b>含已软删</b>的那一行。
     *
     * <p>为什么含已软删:{@code uk_tier_range} 不含 {@code del_flag},软删只是把 del_flag 置 2,
     * 行还在库里。软删后按同配置重建如果直接 insert,唯一键会 1062 —— 所以建单前先用它把
     * 「可复活的那一行」找出来,而不是改唯一键或改删除策略。
     */
    MarketingTier selectTierByNaturalKeyIncludingDeleted(@Param("ownerType") Integer ownerType,
                                                         @Param("ownerId") Long ownerId,
                                                         @Param("ticketId") Long ticketId,
                                                         @Param("minQuantity") Integer minQuantity);

    /**
     * 复活已软删的阶梯行并写入本次配置(沿用既有 softdelete 惯例:只复活,不新建第二行)。
     *
     * <p>{@code where id = ? and del_flag = 2} 是并发闸:同一区间两个请求同时复活,
     * 只有一个 update 命中,另一个拿到 0 行由调用方拒绝,不会变成「两行同区间」。
     * {@code create_time} 不覆盖:那条事实属于原配置的首次创建。
     */
    int reactivateTier(MarketingTier tier);

    int insertTier(MarketingTier tier);

    int updateTier(MarketingTier tier);

    int deleteTierByIds(@Param("ids") Long[] ids);
}
