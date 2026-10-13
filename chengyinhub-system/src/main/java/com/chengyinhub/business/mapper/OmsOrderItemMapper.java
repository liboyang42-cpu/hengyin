package com.chengyinhub.business.mapper;

import java.util.List;
import com.chengyinhub.business.domain.OmsOrderItem;

/**
 * 订单中所包含的商品Mapper接口
 * 
 * @author ruoyi
 * @date 2025-08-25
 */
public interface OmsOrderItemMapper 
{
    /**
     * 查询订单中所包含的商品
     * 
     * @param id 订单中所包含的商品主键
     * @return 订单中所包含的商品
     */
    public OmsOrderItem selectOmsOrderItemById(Long id);

    /**
     * 查询订单中所包含的商品列表
     * 
     * @param omsOrderItem 订单中所包含的商品
     * @return 订单中所包含的商品集合
     */
    public List<OmsOrderItem> selectOmsOrderItemList(OmsOrderItem omsOrderItem);

    /**
     * 新增订单中所包含的商品
     * 
     * @param omsOrderItem 订单中所包含的商品
     * @return 结果
     */
    public int insertOmsOrderItem(OmsOrderItem omsOrderItem);

    /**
     * 修改订单中所包含的商品
     * 
     * @param omsOrderItem 订单中所包含的商品
     * @return 结果
     */
    public int updateOmsOrderItem(OmsOrderItem omsOrderItem);

    /**
     * 删除订单中所包含的商品
     * 
     * @param id 订单中所包含的商品主键
     * @return 结果
     */
    public int deleteOmsOrderItemById(Long id);

    /**
     * 批量删除订单中所包含的商品
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteOmsOrderItemByIds(Long[] ids);
}
