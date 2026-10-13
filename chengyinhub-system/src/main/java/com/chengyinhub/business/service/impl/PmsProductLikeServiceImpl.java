package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.PmsProductLikeMapper;
import com.chengyinhub.business.domain.PmsProductLike;
import com.chengyinhub.business.service.IPmsProductLikeService;

/**
 * 商品收藏Service业务层处理
 * 
 * @author webxh
 * @date 2025-08-30
 */
@Service
public class PmsProductLikeServiceImpl implements IPmsProductLikeService 
{
    @Autowired
    private PmsProductLikeMapper pmsProductLikeMapper;

    /**
     * 查询商品收藏
     * 
     * @param id 商品收藏主键
     * @return 商品收藏
     */
    @Override
    public PmsProductLike selectPmsProductLikeById(Long id)
    {
        return pmsProductLikeMapper.selectPmsProductLikeById(id);
    }

    /**
     * 查询商品收藏列表
     * 
     * @param pmsProductLike 商品收藏
     * @return 商品收藏
     */
    @Override
    public List<PmsProductLike> selectPmsProductLikeList(PmsProductLike pmsProductLike)
    {
        return pmsProductLikeMapper.selectPmsProductLikeList(pmsProductLike);
    }

    /**
     * 新增商品收藏
     * 
     * @param pmsProductLike 商品收藏
     * @return 结果
     */
    @Override
    public int insertPmsProductLike(PmsProductLike pmsProductLike)
    {
        pmsProductLike.setCreateTime(DateUtils.getNowDate());
        return pmsProductLikeMapper.insertPmsProductLike(pmsProductLike);
    }

    /**
     * 修改商品收藏
     * 
     * @param pmsProductLike 商品收藏
     * @return 结果
     */
    @Override
    public int updatePmsProductLike(PmsProductLike pmsProductLike)
    {
        pmsProductLike.setUpdateTime(DateUtils.getNowDate());
        return pmsProductLikeMapper.updatePmsProductLike(pmsProductLike);
    }

    /**
     * 批量删除商品收藏
     * 
     * @param ids 需要删除的商品收藏主键
     * @return 结果
     */
    @Override
    public int deletePmsProductLikeByIds(Long[] ids)
    {
        return pmsProductLikeMapper.deletePmsProductLikeByIds(ids);
    }

    /**
     * 删除商品收藏信息
     * 
     * @param id 商品收藏主键
     * @return 结果
     */
    @Override
    public int deletePmsProductLikeById(Long id)
    {
        return pmsProductLikeMapper.deletePmsProductLikeById(id);
    }
}
