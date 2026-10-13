package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubPostLike;

/**
 * 俱乐部动态点赞 Service 接口
 */
public interface IClubPostLikeService
{
    public ClubPostLike selectByPostAndMember(Long postId, Long memberId);

    public int insertClubPostLike(ClubPostLike clubPostLike);

    public int deleteByPostAndMember(Long postId, Long memberId);
}
