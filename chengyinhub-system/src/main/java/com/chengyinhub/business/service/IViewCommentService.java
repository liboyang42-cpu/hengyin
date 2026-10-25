package com.chengyinhub.business.service;

import java.util.List;
import java.util.Map;
import com.chengyinhub.business.domain.ViewComment;

/**
 * VIEWService接口
 * 
 * @author ruoyi
 * @date 2025-08-29
 */
public interface IViewCommentService 
{
    /**
     * 查询VIEW
     * 
     * @param id VIEW主键
     * @return VIEW
     */
    public ViewComment selectViewCommentById(Long id);

    /**
     * 查询VIEW列表
     * 
     * @param viewComment VIEW
     * @return VIEW集合
     */
    public List<ViewComment> selectViewCommentList(ViewComment viewComment);

    /** 同一对象下，本页评论的可见直接回复数。 */
    List<ViewComment> selectVisibleReplyCountsByParents(Long ownerType, Long ownerId, List<Long> parentIds);

    /** 将公开评论汇总为 1-5 星数量分布。 */
    Map<Integer, Integer> summarizeRatingDistribution(List<ViewComment> comments);

    /**
     * 新增VIEW
     * 
     * @param viewComment VIEW
     * @return 结果
     */
    public int insertViewComment(ViewComment viewComment);

    /**
     * 修改VIEW
     * 
     * @param viewComment VIEW
     * @return 结果
     */
    public int updateViewComment(ViewComment viewComment);

    /**
     * 批量删除VIEW
     * 
     * @param ids 需要删除的VIEW主键集合
     * @return 结果
     */
    public int deleteViewCommentByIds(Long[] ids);

    /**
     * 删除VIEW信息
     * 
     * @param id VIEW主键
     * @return 结果
     */
    public int deleteViewCommentById(Long id);
}
