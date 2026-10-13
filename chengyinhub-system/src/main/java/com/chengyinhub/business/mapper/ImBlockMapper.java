package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.ImBlock;

/**
 * IM 拉黑 Mapper
 */
public interface ImBlockMapper
{
    public int insertImBlock(ImBlock block);

    public int deleteImBlock(@Param("memberId") Long memberId, @Param("blockedMemberId") Long blockedMemberId);

    public ImBlock selectOne(@Param("memberId") Long memberId, @Param("blockedMemberId") Long blockedMemberId);

    /** 双向是否存在拉黑(任一方向) */
    public int existsBetween(@Param("a") Long a, @Param("b") Long b);
}
