package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.UmsCommentLikeMapper;
import com.chengyinhub.business.domain.UmsCommentLike;
import com.chengyinhub.business.service.IUmsCommentLikeService;

/**
 * 评论点赞Service业务层处理
 * 
 * @author ruoyi
 * @date 2025-08-28
 */
@Service
public class UmsCommentLikeServiceImpl implements IUmsCommentLikeService 
{
    @Autowired
    private UmsCommentLikeMapper umsCommentLikeMapper;

    /**
     * 查询评论点赞
     * 
     * @param id 评论点赞主键
     * @return 评论点赞
     */
    @Override
    public UmsCommentLike selectUmsCommentLikeById(Long id)
    {
        return umsCommentLikeMapper.selectUmsCommentLikeById(id);
    }

    /**
     * 查询评论点赞列表
     * 
     * @param umsCommentLike 评论点赞
     * @return 评论点赞
     */
    @Override
    public List<UmsCommentLike> selectUmsCommentLikeList(UmsCommentLike umsCommentLike)
    {
        return umsCommentLikeMapper.selectUmsCommentLikeList(umsCommentLike);
    }

    /**
     * 新增评论点赞
     * 
     * @param umsCommentLike 评论点赞
     * @return 结果
     */
    @Override
    public int insertUmsCommentLike(UmsCommentLike umsCommentLike)
    {
        umsCommentLike.setCreateTime(DateUtils.getNowDate());
        return umsCommentLikeMapper.insertUmsCommentLike(umsCommentLike);
    }

    /**
     * 修改评论点赞
     * 
     * @param umsCommentLike 评论点赞
     * @return 结果
     */
    @Override
    public int updateUmsCommentLike(UmsCommentLike umsCommentLike)
    {
        umsCommentLike.setUpdateTime(DateUtils.getNowDate());
        return umsCommentLikeMapper.updateUmsCommentLike(umsCommentLike);
    }

    /**
     * 批量删除评论点赞
     * 
     * @param ids 需要删除的评论点赞主键
     * @return 结果
     */
    @Override
    public int deleteUmsCommentLikeByIds(Long[] ids)
    {
        return umsCommentLikeMapper.deleteUmsCommentLikeByIds(ids);
    }

    /**
     * 删除评论点赞信息
     * 
     * @param id 评论点赞主键
     * @return 结果
     */
    @Override
    public int deleteUmsCommentLikeById(Long id)
    {
        return umsCommentLikeMapper.deleteUmsCommentLikeById(id);
    }
}
