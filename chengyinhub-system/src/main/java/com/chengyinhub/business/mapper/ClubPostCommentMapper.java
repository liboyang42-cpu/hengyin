package com.chengyinhub.business.mapper;

import java.util.List;
import com.chengyinhub.business.domain.ClubPostComment;

/**
 * 俱乐部动态评论 Mapper 接口
 */
public interface ClubPostCommentMapper
{
    /** 某动态的评论列表(join 作者,del_flag=0,正序) */
    public List<ClubPostComment> selectClubPostCommentList(ClubPostComment clubPostComment);

    public int insertClubPostComment(ClubPostComment clubPostComment);

    public ClubPostComment selectClubPostCommentById(Long id);

    /** 软删评论 del_flag=2 */
    public int deleteClubPostCommentById(Long id);

    /** 更新(审核通过恢复 del_flag=0 等);只更新非空字段 */
    public int updateClubPostComment(ClubPostComment clubPostComment);
}
