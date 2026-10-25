package com.chengyinhub.business.service.impl;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.ViewCommentMapper;
import com.chengyinhub.business.domain.ViewComment;
import com.chengyinhub.business.service.IViewCommentService;

/**
 * VIEWService业务层处理
 * 
 * @author ruoyi
 * @date 2025-08-29
 */
@Service
public class ViewCommentServiceImpl implements IViewCommentService 
{
    @Autowired
    private ViewCommentMapper viewCommentMapper;

    /**
     * 查询VIEW
     * 
     * @param id VIEW主键
     * @return VIEW
     */
    @Override
    public ViewComment selectViewCommentById(Long id)
    {
        return viewCommentMapper.selectViewCommentById(id);
    }

    /**
     * 查询VIEW列表
     * 
     * @param viewComment VIEW
     * @return VIEW
     */
    @Override
    public List<ViewComment> selectViewCommentList(ViewComment viewComment)
    {
        return viewCommentMapper.selectViewCommentList(viewComment);
    }

    @Override
    public List<ViewComment> selectVisibleReplyCountsByParents(Long ownerType, Long ownerId, List<Long> parentIds)
    {
        return viewCommentMapper.selectVisibleReplyCountsByParents(ownerType, ownerId, parentIds);
    }

    @Override
    public Map<Integer, Integer> summarizeRatingDistribution(List<ViewComment> comments)
    {
        Map<Integer, Integer> distribution = new LinkedHashMap<>();
        for (int star = 5; star >= 1; star--)
        {
            distribution.put(star, 0);
        }
        if (comments == null)
        {
            return distribution;
        }
        for (ViewComment comment : comments)
        {
            if (comment == null || comment.getRating() == null)
            {
                continue;
            }
            int star = Math.max(1, Math.min(5, comment.getRating().intValue()));
            distribution.put(star, distribution.get(star) + 1);
        }
        return distribution;
    }

    /**
     * 新增VIEW
     * 
     * @param viewComment VIEW
     * @return 结果
     */
    @Override
    public int insertViewComment(ViewComment viewComment)
    {
        viewComment.setCreateTime(DateUtils.getNowDate());
        return viewCommentMapper.insertViewComment(viewComment);
    }

    /**
     * 修改VIEW
     * 
     * @param viewComment VIEW
     * @return 结果
     */
    @Override
    public int updateViewComment(ViewComment viewComment)
    {
        viewComment.setUpdateTime(DateUtils.getNowDate());
        return viewCommentMapper.updateViewComment(viewComment);
    }

    /**
     * 批量删除VIEW
     * 
     * @param ids 需要删除的VIEW主键
     * @return 结果
     */
    @Override
    public int deleteViewCommentByIds(Long[] ids)
    {
        return viewCommentMapper.deleteViewCommentByIds(ids);
    }

    /**
     * 删除VIEW信息
     * 
     * @param id VIEW主键
     * @return 结果
     */
    @Override
    public int deleteViewCommentById(Long id)
    {
        return viewCommentMapper.deleteViewCommentById(id);
    }
}
