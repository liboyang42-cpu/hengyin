package com.chengyinhub.business.service.impl;

import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.ClubPostLikeMapper;
import com.chengyinhub.business.domain.ClubPostLike;
import com.chengyinhub.business.service.IClubPostLikeService;

/**
 * 俱乐部动态点赞 Service 业务层处理
 */
@Service
public class ClubPostLikeServiceImpl implements IClubPostLikeService
{
    @Autowired
    private ClubPostLikeMapper clubPostLikeMapper;

    @Override
    public ClubPostLike selectByPostAndMember(Long postId, Long memberId)
    {
        return clubPostLikeMapper.selectByPostAndMember(postId, memberId);
    }

    @Override
    public int insertClubPostLike(ClubPostLike clubPostLike)
    {
        clubPostLike.setCreateTime(DateUtils.getNowDate());
        return clubPostLikeMapper.insertClubPostLike(clubPostLike);
    }

    @Override
    public int deleteByPostAndMember(Long postId, Long memberId)
    {
        return clubPostLikeMapper.deleteByPostAndMember(postId, memberId);
    }
}
