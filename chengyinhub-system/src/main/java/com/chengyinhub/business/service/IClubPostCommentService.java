package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.ClubPostComment;

/**
 * 俱乐部动态评论 Service 接口
 */
public interface IClubPostCommentService
{
    public List<ClubPostComment> selectClubPostCommentList(ClubPostComment clubPostComment);

    public int insertClubPostComment(ClubPostComment clubPostComment);

    public ClubPostComment selectClubPostCommentById(Long id);

    public int deleteClubPostCommentById(Long id);

    /** 更新(审核通过恢复 del_flag=0 等) */
    public int updateClubPostComment(ClubPostComment clubPostComment);
}
