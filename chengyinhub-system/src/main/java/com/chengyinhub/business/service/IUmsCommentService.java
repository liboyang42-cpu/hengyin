package com.chengyinhub.business.service;

import java.util.List;
import java.util.Map;
import com.chengyinhub.business.domain.UmsComment;

/**
 * 用户评论Service接口
 * 
 * @author ruoyi
 * @date 2025-08-28
 */
public interface IUmsCommentService 
{
    /**
     * 查询用户评论
     * 
     * @param id 用户评论主键
     * @return 用户评论
     */
    public UmsComment selectUmsCommentById(Long id);

    public Double selectAvgRatingByCondition(UmsComment umsComment);

    public List<Map<String, Object>> selectRatingSummaryByOwners(Long ownerType, List<Long> ownerIds);

    /**
     * 查询用户评论列表
     * 
     * @param umsComment 用户评论
     * @return 用户评论集合
     */
    public List<UmsComment> selectUmsCommentList(UmsComment umsComment);

    /**
     * 新增用户评论
     * 
     * @param umsComment 用户评论
     * @return 结果
     */
    public int insertUmsComment(UmsComment umsComment);

    /**
     * 修改用户评论
     * 
     * @param umsComment 用户评论
     * @return 结果
     */
    public int updateUmsComment(UmsComment umsComment);

    /**
     * 批量删除用户评论
     * 
     * @param ids 需要删除的用户评论主键集合
     * @return 结果
     */
    public int deleteUmsCommentByIds(Long[] ids);

    /**
     * 删除用户评论信息
     * 
     * @param id 用户评论主键
     * @return 结果
     */
    public int deleteUmsCommentById(Long id);
}
