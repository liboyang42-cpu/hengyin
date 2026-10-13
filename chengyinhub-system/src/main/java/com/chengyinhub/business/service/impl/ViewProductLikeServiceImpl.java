package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.ViewProductLikeMapper;
import com.chengyinhub.business.domain.ViewProductLike;
import com.chengyinhub.business.service.IViewProductLikeService;

/**
 * VIEWService业务层处理
 * 
 * @author webxh
 * @date 2025-08-30
 */
@Service
public class ViewProductLikeServiceImpl implements IViewProductLikeService 
{
    @Autowired
    private ViewProductLikeMapper viewProductLikeMapper;

    /**
     * 查询VIEW
     * 
     * @param id VIEW主键
     * @return VIEW
     */
    @Override
    public ViewProductLike selectViewProductLikeById(Long id)
    {
        return viewProductLikeMapper.selectViewProductLikeById(id);
    }

    /**
     * 查询VIEW列表
     * 
     * @param viewProductLike VIEW
     * @return VIEW
     */
    @Override
    public List<ViewProductLike> selectViewProductLikeList(ViewProductLike viewProductLike)
    {
        return viewProductLikeMapper.selectViewProductLikeList(viewProductLike);
    }

    /**
     * 新增VIEW
     * 
     * @param viewProductLike VIEW
     * @return 结果
     */
    @Override
    public int insertViewProductLike(ViewProductLike viewProductLike)
    {
        viewProductLike.setCreateTime(DateUtils.getNowDate());
        return viewProductLikeMapper.insertViewProductLike(viewProductLike);
    }

    /**
     * 修改VIEW
     * 
     * @param viewProductLike VIEW
     * @return 结果
     */
    @Override
    public int updateViewProductLike(ViewProductLike viewProductLike)
    {
        viewProductLike.setUpdateTime(DateUtils.getNowDate());
        return viewProductLikeMapper.updateViewProductLike(viewProductLike);
    }

    /**
     * 批量删除VIEW
     * 
     * @param ids 需要删除的VIEW主键
     * @return 结果
     */
    @Override
    public int deleteViewProductLikeByIds(Long[] ids)
    {
        return viewProductLikeMapper.deleteViewProductLikeByIds(ids);
    }

    /**
     * 删除VIEW信息
     * 
     * @param id VIEW主键
     * @return 结果
     */
    @Override
    public int deleteViewProductLikeById(Long id)
    {
        return viewProductLikeMapper.deleteViewProductLikeById(id);
    }
}
