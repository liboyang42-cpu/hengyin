package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.OmsOrderItemMapper;
import com.chengyinhub.business.domain.OmsOrderItem;
import com.chengyinhub.business.service.IOmsOrderItemService;

/**
 * 订单中所包含的商品Service业务层处理
 * 
 * @author ruoyi
 * @date 2025-08-25
 */
@Service
public class OmsOrderItemServiceImpl implements IOmsOrderItemService 
{
    @Autowired
    private OmsOrderItemMapper omsOrderItemMapper;

    /**
     * 查询订单中所包含的商品
     * 
     * @param id 订单中所包含的商品主键
     * @return 订单中所包含的商品
     */
    @Override
    public OmsOrderItem selectOmsOrderItemById(Long id)
    {
        return omsOrderItemMapper.selectOmsOrderItemById(id);
    }

    /**
     * 查询订单中所包含的商品列表
     * 
     * @param omsOrderItem 订单中所包含的商品
     * @return 订单中所包含的商品
     */
    @Override
    public List<OmsOrderItem> selectOmsOrderItemList(OmsOrderItem omsOrderItem)
    {
        return omsOrderItemMapper.selectOmsOrderItemList(omsOrderItem);
    }

    /**
     * 新增订单中所包含的商品
     * 
     * @param omsOrderItem 订单中所包含的商品
     * @return 结果
     */
    @Override
    public int insertOmsOrderItem(OmsOrderItem omsOrderItem)
    {
        omsOrderItem.setCreateTime(DateUtils.getNowDate());
        return omsOrderItemMapper.insertOmsOrderItem(omsOrderItem);
    }

    /**
     * 修改订单中所包含的商品
     * 
     * @param omsOrderItem 订单中所包含的商品
     * @return 结果
     */
    @Override
    public int updateOmsOrderItem(OmsOrderItem omsOrderItem)
    {
        omsOrderItem.setUpdateTime(DateUtils.getNowDate());
        return omsOrderItemMapper.updateOmsOrderItem(omsOrderItem);
    }

    /**
     * 批量删除订单中所包含的商品
     * 
     * @param ids 需要删除的订单中所包含的商品主键
     * @return 结果
     */
    @Override
    public int deleteOmsOrderItemByIds(Long[] ids)
    {
        return omsOrderItemMapper.deleteOmsOrderItemByIds(ids);
    }

    /**
     * 删除订单中所包含的商品信息
     * 
     * @param id 订单中所包含的商品主键
     * @return 结果
     */
    @Override
    public int deleteOmsOrderItemById(Long id)
    {
        return omsOrderItemMapper.deleteOmsOrderItemById(id);
    }
}
