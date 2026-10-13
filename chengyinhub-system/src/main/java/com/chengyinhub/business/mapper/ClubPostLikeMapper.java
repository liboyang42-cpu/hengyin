package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.ClubPostLike;

/**
 * 俱乐部动态点赞 Mapper 接口
 */
public interface ClubPostLikeMapper
{
    public ClubPostLike selectByPostAndMember(@Param("postId") Long postId, @Param("memberId") Long memberId);

    public int insertClubPostLike(ClubPostLike clubPostLike);

    public int deleteByPostAndMember(@Param("postId") Long postId, @Param("memberId") Long memberId);
}
