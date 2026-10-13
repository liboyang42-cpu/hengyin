package com.chengyinhub.business.mapper;

import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.PmsProduct;

/**
 * 积分商城商品Mapper接口
 * 
 * @author ruoyi
 * @date 2025-08-25
 */
public interface PmsProductMapper 
{
    /**
     * 查询积分商城商品
     * 
     * @param id 积分商城商品主键
     * @return 积分商城商品
     */
    public PmsProduct selectPmsProductById(Long id);

    /**
     * 查询积分商城商品列表
     * 
     * @param pmsProduct 积分商城商品
     * @return 积分商城商品集合
     */
    public List<PmsProduct> selectPmsProductList(PmsProduct pmsProduct);

    /**
     * 新增积分商城商品
     * 
     * @param pmsProduct 积分商城商品
     * @return 结果
     */
    public int insertPmsProduct(PmsProduct pmsProduct);

    /**
     * 修改积分商城商品
     * 
     * @param pmsProduct 积分商城商品
     * @return 结果
     */
    public int updatePmsProduct(PmsProduct pmsProduct);

    /**
     * 商品目录上下架：避免复用携带 SKU 的通用商品编辑写口。
     *
     * @param id 商品 ID
     * @param expectedPublishStatus 读取时的状态快照，用于条件更新
     * @param publishStatus 目标状态
     * @param updateBy 后台操作人
     * @param updateTime 后台操作时间
     * @return 结果
     */
    public int updatePmsProductPublishStatus(@Param("id") Long id,
                                             @Param("expectedPublishStatus") Integer expectedPublishStatus,
                                             @Param("publishStatus") Integer publishStatus,
                                             @Param("updateBy") String updateBy,
                                             @Param("updateTime") Date updateTime);

    /**
     * 删除积分商城商品
     * 
     * @param id 积分商城商品主键
     * @return 结果
     */
    public int deletePmsProductById(Long id);

    /**
     * 批量删除积分商城商品
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deletePmsProductByIds(Long[] ids);
}
