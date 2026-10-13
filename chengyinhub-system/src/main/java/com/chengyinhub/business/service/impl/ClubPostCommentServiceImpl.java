package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.ClubPostCommentMapper;
import com.chengyinhub.business.domain.ClubPostComment;
import com.chengyinhub.business.service.IClubPostCommentService;

/**
 * 俱乐部动态评论 Service 业务层处理
 */
@Service
public class ClubPostCommentServiceImpl implements IClubPostCommentService
{
    @Autowired
    private ClubPostCommentMapper clubPostCommentMapper;

    @Override
    public List<ClubPostComment> selectClubPostCommentList(ClubPostComment clubPostComment)
    {
        return clubPostCommentMapper.selectClubPostCommentList(clubPostComment);
    }

    @Override
    public int insertClubPostComment(ClubPostComment clubPostComment)
    {
        clubPostComment.setCreateTime(DateUtils.getNowDate());
        return clubPostCommentMapper.insertClubPostComment(clubPostComment);
    }

    @Override
    public ClubPostComment selectClubPostCommentById(Long id)
    {
        return clubPostCommentMapper.selectClubPostCommentById(id);
    }

    @Override
    public int deleteClubPostCommentById(Long id)
    {
        return clubPostCommentMapper.deleteClubPostCommentById(id);
    }

    @Override
    public int updateClubPostComment(ClubPostComment clubPostComment)
    {
        return clubPostCommentMapper.updateClubPostComment(clubPostComment);
    }
}
