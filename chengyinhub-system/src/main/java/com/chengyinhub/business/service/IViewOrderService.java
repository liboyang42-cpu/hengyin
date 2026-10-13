package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.ViewOrder;

/**
 * VIEWService接口
 * 
 * @author ruoyi
 * @date 2025-08-28
 */
public interface IViewOrderService 
{
    /**
     * 查询VIEW
     * 
     * @param id VIEW主键
     * @return VIEW
     */
    public ViewOrder selectViewOrderById(Long id);

    /**
     * 查询VIEW列表
     * 
     * @param viewOrder VIEW
     * @return VIEW集合
     */
    public List<ViewOrder> selectViewOrderList(ViewOrder viewOrder);

    /**
     * 新增VIEW
     * 
     * @param viewOrder VIEW
     * @return 结果
     */
    public int insertViewOrder(ViewOrder viewOrder);

    /**
     * 修改VIEW
     * 
     * @param viewOrder VIEW
     * @return 结果
     */
    public int updateViewOrder(ViewOrder viewOrder);

    /**
     * 批量删除VIEW
     * 
     * @param ids 需要删除的VIEW主键集合
     * @return 结果
     */
    public int deleteViewOrderByIds(Long[] ids);

    /**
     * 删除VIEW信息
     * 
     * @param id VIEW主键
     * @return 结果
     */
    public int deleteViewOrderById(Long id);
}
