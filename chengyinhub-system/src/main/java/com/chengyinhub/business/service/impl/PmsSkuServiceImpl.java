package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.PmsSkuMapper;
import com.chengyinhub.business.domain.PmsSku;
import com.chengyinhub.business.service.IPmsSkuService;

/**
 * sku信息Service业务层处理
 * 
 * @author ruoyi
 * @date 2025-08-28
 */
@Service
public class PmsSkuServiceImpl implements IPmsSkuService 
{
    @Autowired
    private PmsSkuMapper pmsSkuMapper;

    /**
     * 查询sku信息
     * 
     * @param id sku信息主键
     * @return sku信息
     */
    @Override
    public PmsSku selectPmsSkuById(Long id)
    {
        return pmsSkuMapper.selectPmsSkuById(id);
    }

    /**
     * 查询sku信息列表
     * 
     * @param pmsSku sku信息
     * @return sku信息
     */
    @Override
    public List<PmsSku> selectPmsSkuList(PmsSku pmsSku)
    {
        return pmsSkuMapper.selectPmsSkuList(pmsSku);
    }

    /**
     * 新增sku信息
     * 
     * @param pmsSku sku信息
     * @return 结果
     */
    @Override
    public int insertPmsSku(PmsSku pmsSku)
    {
        pmsSku.setCreateTime(DateUtils.getNowDate());
        return pmsSkuMapper.insertPmsSku(pmsSku);
    }

    /**
     * 修改sku信息
     * 
     * @param pmsSku sku信息
     * @return 结果
     */
    @Override
    public int updatePmsSku(PmsSku pmsSku)
    {
        pmsSku.setUpdateTime(DateUtils.getNowDate());
        return pmsSkuMapper.updatePmsSku(pmsSku);
    }

    @Override
    public int deductStock(Long id, Long qty)
    {
        return pmsSkuMapper.deductStock(id, qty);
    }

    @Override
    public int deductStockForProduct(Long id, Long productId, Long qty)
    {
        return pmsSkuMapper.deductStockForProduct(id, productId, qty);
    }

    /**
     * 批量删除sku信息
     * 
     * @param ids 需要删除的sku信息主键
     * @return 结果
     */
    @Override
    public int deletePmsSkuByIds(Long[] ids)
    {
        return pmsSkuMapper.deletePmsSkuByIds(ids);
    }

    /**
     * 删除sku信息信息
     * 
     * @param id sku信息主键
     * @return 结果
     */
    @Override
    public int deletePmsSkuById(Long id)
    {
        return pmsSkuMapper.deletePmsSkuById(id);
    }
}
