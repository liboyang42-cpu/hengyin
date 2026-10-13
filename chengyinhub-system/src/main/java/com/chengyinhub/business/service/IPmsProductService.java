package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.PmsProduct;
import com.chengyinhub.business.domain.dto.ProductPublishStatusDTO;

/**
 * 积分商城商品Service接口
 * 
 * @author ruoyi
 * @date 2025-08-25
 */
public interface IPmsProductService 
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
     * 商品目录上下架，不复用会写 SKU 的通用商品编辑接口。
     *
     * @param request 仅含商品标识和目标状态
     * @param operator 后台服务端登录用户名
     * @return 结果
     */
    public int updateProductPublishStatus(ProductPublishStatusDTO request, String operator);

    /**
     * 批量删除积分商城商品
     * 
     * @param ids 需要删除的积分商城商品主键集合
     * @return 结果
     */
    public int deletePmsProductByIds(Long[] ids);

    /**
     * 删除积分商城商品信息
     * 
     * @param id 积分商城商品主键
     * @return 结果
     */
    public int deletePmsProductById(Long id);
}
