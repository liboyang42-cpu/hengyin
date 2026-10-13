package com.chengyinhub.business.mapper;

import java.util.List;
import com.chengyinhub.business.domain.PmsSku;

/**
 * sku信息Mapper接口
 * 
 * @author ruoyi
 * @date 2025-08-28
 */
public interface PmsSkuMapper 
{
    /**
     * 查询sku信息
     * 
     * @param id sku信息主键
     * @return sku信息
     */
    public PmsSku selectPmsSkuById(Long id);

    /**
     * 查询sku信息列表
     * 
     * @param pmsSku sku信息
     * @return sku信息集合
     */
    public List<PmsSku> selectPmsSkuList(PmsSku pmsSku);

    /**
     * 新增sku信息
     * 
     * @param pmsSku sku信息
     * @return 结果
     */
    public int insertPmsSku(PmsSku pmsSku);

    /**
     * 修改sku信息
     * 
     * @param pmsSku sku信息
     * @return 结果
     */
    public int updatePmsSku(PmsSku pmsSku);

    /**
     * P0-C 原子扣库存:仅 stock>=qty 才扣,返回受影响行数(0=库存不足)。
     * 取代 updatePmsSku 的 `stock=#{stock}` 绝对赋值(读改写并发超卖 + 负数量刷库存)。
     */
    public int deductStock(@org.apache.ibatis.annotations.Param("id") Long id,
                           @org.apache.ibatis.annotations.Param("qty") Long qty);

    /**
     * 仅在 SKU 仍属于给定商品且库存充足时原子扣减。
     */
    public int deductStockForProduct(@org.apache.ibatis.annotations.Param("id") Long id,
                                     @org.apache.ibatis.annotations.Param("productId") Long productId,
                                     @org.apache.ibatis.annotations.Param("qty") Long qty);

    /**
     * 删除sku信息
     * 
     * @param id sku信息主键
     * @return 结果
     */
    public int deletePmsSkuById(Long id);

    /**
     * 批量删除sku信息
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deletePmsSkuByIds(Long[] ids);
}
