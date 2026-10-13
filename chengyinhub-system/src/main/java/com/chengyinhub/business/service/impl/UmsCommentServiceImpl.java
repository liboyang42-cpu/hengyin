package com.chengyinhub.business.service.impl;

import java.util.List;
import java.util.Map;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.UmsCommentMapper;
import com.chengyinhub.business.domain.UmsComment;
import com.chengyinhub.business.service.IUmsCommentService;

/**
 * 用户评论Service业务层处理
 * 
 * @author ruoyi
 * @date 2025-08-28
 */
@Service
public class UmsCommentServiceImpl implements IUmsCommentService 
{
    @Autowired
    private UmsCommentMapper umsCommentMapper;

    /**
     * 查询用户评论
     * 
     * @param id 用户评论主键
     * @return 用户评论
     */
    @Override
    public UmsComment selectUmsCommentById(Long id)
    {
        return umsCommentMapper.selectUmsCommentById(id);
    }

    @Override
    public Double selectAvgRatingByCondition(UmsComment umsComment)
    {
        return umsCommentMapper.selectAvgRatingByCondition(umsComment);
    }

    @Override
    public List<Map<String, Object>> selectRatingSummaryByOwners(Long ownerType, List<Long> ownerIds) {
        return umsCommentMapper.selectRatingSummaryByOwners(ownerType, ownerIds);
    }



    /**
     * 查询用户评论列表
     * 
     * @param umsComment 用户评论
     * @return 用户评论
     */
    @Override
    public List<UmsComment> selectUmsCommentList(UmsComment umsComment)
    {
        return umsCommentMapper.selectUmsCommentList(umsComment);
    }

    /**
     * 新增用户评论
     * 
     * @param umsComment 用户评论
     * @return 结果
     */
    @Override
    public int insertUmsComment(UmsComment umsComment)
    {
        umsComment.setCreateTime(DateUtils.getNowDate());
        return umsCommentMapper.insertUmsComment(umsComment);
    }

    /**
     * 修改用户评论
     * 
     * @param umsComment 用户评论
     * @return 结果
     */
    @Override
    public int updateUmsComment(UmsComment umsComment)
    {
        umsComment.setUpdateTime(DateUtils.getNowDate());
        return umsCommentMapper.updateUmsComment(umsComment);
    }

    /**
     * 批量删除用户评论
     * 
     * @param ids 需要删除的用户评论主键
     * @return 结果
     */
    @Override
    public int deleteUmsCommentByIds(Long[] ids)
    {
        return umsCommentMapper.deleteUmsCommentByIds(ids);
    }

    /**
     * 删除用户评论信息
     * 
     * @param id 用户评论主键
     * @return 结果
     */
    @Override
    public int deleteUmsCommentById(Long id)
    {
        return umsCommentMapper.deleteUmsCommentById(id);
    }
}
