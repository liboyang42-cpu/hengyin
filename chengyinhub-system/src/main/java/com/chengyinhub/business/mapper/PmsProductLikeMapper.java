package com.chengyinhub.business.mapper;

import java.util.List;
import com.chengyinhub.business.domain.PmsProductLike;

/**
 * 商品收藏Mapper接口
 * 
 * @author webxh
 * @date 2025-08-30
 */
public interface PmsProductLikeMapper 
{
    /**
     * 查询商品收藏
     * 
     * @param id 商品收藏主键
     * @return 商品收藏
     */
    public PmsProductLike selectPmsProductLikeById(Long id);

    /**
     * 查询商品收藏列表
     * 
     * @param pmsProductLike 商品收藏
     * @return 商品收藏集合
     */
    public List<PmsProductLike> selectPmsProductLikeList(PmsProductLike pmsProductLike);

    /**
     * 新增商品收藏
     * 
     * @param pmsProductLike 商品收藏
     * @return 结果
     */
    public int insertPmsProductLike(PmsProductLike pmsProductLike);

    /**
     * 修改商品收藏
     * 
     * @param pmsProductLike 商品收藏
     * @return 结果
     */
    public int updatePmsProductLike(PmsProductLike pmsProductLike);

    /**
     * 删除商品收藏
     * 
     * @param id 商品收藏主键
     * @return 结果
     */
    public int deletePmsProductLikeById(Long id);

    /**
     * 批量删除商品收藏
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deletePmsProductLikeByIds(Long[] ids);
}
