package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.PmsSku;

/**
 * sku信息Service接口
 * 
 * @author ruoyi
 * @date 2025-08-28
 */
public interface IPmsSkuService 
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
     */
    public int deductStock(Long id, Long qty);

    /**
     * 按 SKU 与商品归属原子扣库存；归属或库存已变化时返回 0。
     */
    public int deductStockForProduct(Long id, Long productId, Long qty);

    /**
     * 批量删除sku信息
     * 
     * @param ids 需要删除的sku信息主键集合
     * @return 结果
     */
    public int deletePmsSkuByIds(Long[] ids);

    /**
     * 删除sku信息信息
     * 
     * @param id sku信息主键
     * @return 结果
     */
    public int deletePmsSkuById(Long id);
}
