package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.UmsCreativeSquareLikeMapper;
import com.chengyinhub.business.domain.UmsCreativeSquareLike;
import com.chengyinhub.business.service.IUmsCreativeSquareLikeService;

/**
 * 创意广场点赞Service业务层处理
 * 
 * @author ruoyi
 * @date 2025-08-29
 */
@Service
public class UmsCreativeSquareLikeServiceImpl implements IUmsCreativeSquareLikeService 
{
    @Autowired
    private UmsCreativeSquareLikeMapper umsCreativeSquareLikeMapper;

    /**
     * 查询用户点赞数
     *
     * @param member_id 用户ID
     * @return 结果
     */
    public int selectUmsMemberLikeNumByMemberId(Long member_id){
        return umsCreativeSquareLikeMapper.selectUmsMemberLikeNumByMemberId(member_id);
    }

    /**
     * 查询创意广场点赞
     * 
     * @param id 创意广场点赞主键
     * @return 创意广场点赞
     */
    @Override
    public UmsCreativeSquareLike selectUmsCreativeSquareLikeById(Long id)
    {
        return umsCreativeSquareLikeMapper.selectUmsCreativeSquareLikeById(id);
    }

    /**
     * 查询创意广场点赞列表
     * 
     * @param umsCreativeSquareLike 创意广场点赞
     * @return 创意广场点赞
     */
    @Override
    public List<UmsCreativeSquareLike> selectUmsCreativeSquareLikeList(UmsCreativeSquareLike umsCreativeSquareLike)
    {
        return umsCreativeSquareLikeMapper.selectUmsCreativeSquareLikeList(umsCreativeSquareLike);
    }

    /**
     * 新增创意广场点赞
     * 
     * @param umsCreativeSquareLike 创意广场点赞
     * @return 结果
     */
    @Override
    public int insertUmsCreativeSquareLike(UmsCreativeSquareLike umsCreativeSquareLike)
    {
        umsCreativeSquareLike.setCreateTime(DateUtils.getNowDate());
        return umsCreativeSquareLikeMapper.insertUmsCreativeSquareLike(umsCreativeSquareLike);
    }

    /**
     * 修改创意广场点赞
     * 
     * @param umsCreativeSquareLike 创意广场点赞
     * @return 结果
     */
    @Override
    public int updateUmsCreativeSquareLike(UmsCreativeSquareLike umsCreativeSquareLike)
    {
        umsCreativeSquareLike.setUpdateTime(DateUtils.getNowDate());
        return umsCreativeSquareLikeMapper.updateUmsCreativeSquareLike(umsCreativeSquareLike);
    }

    /**
     * 批量删除创意广场点赞
     * 
     * @param ids 需要删除的创意广场点赞主键
     * @return 结果
     */
    @Override
    public int deleteUmsCreativeSquareLikeByIds(Long[] ids)
    {
        return umsCreativeSquareLikeMapper.deleteUmsCreativeSquareLikeByIds(ids);
    }

    /**
     * 删除创意广场点赞信息
     * 
     * @param id 创意广场点赞主键
     * @return 结果
     */
    @Override
    public int deleteUmsCreativeSquareLikeById(Long id)
    {
        return umsCreativeSquareLikeMapper.deleteUmsCreativeSquareLikeById(id);
    }
}
