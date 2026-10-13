package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.OmsOrderMapper;
import com.chengyinhub.business.domain.OmsOrder;
import com.chengyinhub.business.service.IOmsOrderService;

/**
 * 订单Service业务层处理
 * 
 * @author ruoyi
 * @date 2025-08-25
 */
@Service
public class OmsOrderServiceImpl implements IOmsOrderService 
{
    @Autowired
    private OmsOrderMapper omsOrderMapper;

    /**
     * 查询订单
     * 
     * @param id 订单主键
     * @return 订单
     */
    @Override
    public OmsOrder selectOmsOrderById(Long id)
    {
        return omsOrderMapper.selectOmsOrderById(id);
    }

    /**
     * 查询订单列表
     * 
     * @param omsOrder 订单
     * @return 订单
     */
    @Override
    public List<OmsOrder> selectOmsOrderList(OmsOrder omsOrder)
    {
        return omsOrderMapper.selectOmsOrderList(omsOrder);
    }

    /**
     * 新增订单
     * 
     * @param omsOrder 订单
     * @return 结果
     */
    @Override
    public int insertOmsOrder(OmsOrder omsOrder)
    {
        omsOrder.setCreateTime(DateUtils.getNowDate());
        return omsOrderMapper.insertOmsOrder(omsOrder);
    }

    /**
     * 修改订单
     * 
     * @param omsOrder 订单
     * @return 结果
     */
    @Override
    public int updateOmsOrder(OmsOrder omsOrder)
    {
        omsOrder.setUpdateTime(DateUtils.getNowDate());
        return omsOrderMapper.updateOmsOrder(omsOrder);
    }

    /**
     * 批量删除订单
     * 
     * @param ids 需要删除的订单主键
     * @return 结果
     */
    @Override
    public int deleteOmsOrderByIds(Long[] ids)
    {
        return omsOrderMapper.deleteOmsOrderByIds(ids);
    }

    /**
     * 删除订单信息
     * 
     * @param id 订单主键
     * @return 结果
     */
    @Override
    public int deleteOmsOrderById(Long id)
    {
        return omsOrderMapper.deleteOmsOrderById(id);
    }
}
