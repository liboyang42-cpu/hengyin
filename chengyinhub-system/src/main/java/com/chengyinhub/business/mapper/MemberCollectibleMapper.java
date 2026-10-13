package com.chengyinhub.business.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.MemberCollectible;

/**
 * FE-01 玩家收集(碎片/彩蛋)Mapper
 *
 * @author cy
 * @date 2026-07-07
 */
public interface MemberCollectibleMapper
{
    /** 某玩家某类型的收集列表(按收集时间倒序)。collType 为 null 时返回全部类型。 */
    List<MemberCollectible> selectListByMember(@Param("memberId") Long memberId, @Param("collType") Integer collType);

    /** 幂等判重:同一 (member, type, ref) 是否已收集。 */
    int countOne(@Param("memberId") Long memberId, @Param("collType") Integer collType, @Param("refId") Long refId);

    int insertMemberCollectible(MemberCollectible memberCollectible);

    /** 取消收集(取消收藏用):按 (member, type, ref) 删除。 */
    int deleteOne(@Param("memberId") Long memberId, @Param("collType") Integer collType, @Param("refId") Long refId);
}
