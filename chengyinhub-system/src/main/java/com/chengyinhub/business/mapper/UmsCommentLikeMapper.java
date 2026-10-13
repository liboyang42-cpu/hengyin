package com.chengyinhub.business.mapper;

import java.util.List;
import com.chengyinhub.business.domain.UmsCommentLike;

/**
 * 评论点赞Mapper接口
 * 
 * @author ruoyi
 * @date 2025-08-28
 */
public interface UmsCommentLikeMapper 
{
    /**
     * 查询评论点赞
     * 
     * @param id 评论点赞主键
     * @return 评论点赞
     */
    public UmsCommentLike selectUmsCommentLikeById(Long id);

    /**
     * 查询评论点赞列表
     * 
     * @param umsCommentLike 评论点赞
     * @return 评论点赞集合
     */
    public List<UmsCommentLike> selectUmsCommentLikeList(UmsCommentLike umsCommentLike);

    /**
     * 新增评论点赞
     * 
     * @param umsCommentLike 评论点赞
     * @return 结果
     */
    public int insertUmsCommentLike(UmsCommentLike umsCommentLike);

    /**
     * 修改评论点赞
     * 
     * @param umsCommentLike 评论点赞
     * @return 结果
     */
    public int updateUmsCommentLike(UmsCommentLike umsCommentLike);

    /**
     * 删除评论点赞
     * 
     * @param id 评论点赞主键
     * @return 结果
     */
    public int deleteUmsCommentLikeById(Long id);

    /**
     * 批量删除评论点赞
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteUmsCommentLikeByIds(Long[] ids);
}
