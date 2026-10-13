package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.ViewOrderMapper;
import com.chengyinhub.business.domain.ViewOrder;
import com.chengyinhub.business.service.IViewOrderService;

/**
 * VIEWService业务层处理
 * 
 * @author ruoyi
 * @date 2025-08-28
 */
@Service
public class ViewOrderServiceImpl implements IViewOrderService 
{
    @Autowired
    private ViewOrderMapper viewOrderMapper;

    /**
     * 查询VIEW
     * 
     * @param id VIEW主键
     * @return VIEW
     */
    @Override
    public ViewOrder selectViewOrderById(Long id)
    {
        return viewOrderMapper.selectViewOrderById(id);
    }

    /**
     * 查询VIEW列表
     * 
     * @param viewOrder VIEW
     * @return VIEW
     */
    @Override
    public List<ViewOrder> selectViewOrderList(ViewOrder viewOrder)
    {
        return viewOrderMapper.selectViewOrderList(viewOrder);
    }

    /**
     * 新增VIEW
     * 
     * @param viewOrder VIEW
     * @return 结果
     */
    @Override
    public int insertViewOrder(ViewOrder viewOrder)
    {
        viewOrder.setCreateTime(DateUtils.getNowDate());
        return viewOrderMapper.insertViewOrder(viewOrder);
    }

    /**
     * 修改VIEW
     * 
     * @param viewOrder VIEW
     * @return 结果
     */
    @Override
    public int updateViewOrder(ViewOrder viewOrder)
    {
        viewOrder.setUpdateTime(DateUtils.getNowDate());
        return viewOrderMapper.updateViewOrder(viewOrder);
    }

    /**
     * 批量删除VIEW
     * 
     * @param ids 需要删除的VIEW主键
     * @return 结果
     */
    @Override
    public int deleteViewOrderByIds(Long[] ids)
    {
        return viewOrderMapper.deleteViewOrderByIds(ids);
    }

    /**
     * 删除VIEW信息
     * 
     * @param id VIEW主键
     * @return 结果
     */
    @Override
    public int deleteViewOrderById(Long id)
    {
        return viewOrderMapper.deleteViewOrderById(id);
    }
}
