package com.chengyinhub.system.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.system.mapper.ViewCouponHistoryMapper;
import com.chengyinhub.system.domain.ViewCouponHistory;
import com.chengyinhub.system.service.IViewCouponHistoryService;

/**
 * VIEWService业务层处理
 * 
 * @author ruoyi
 * @date 2025-10-16
 */
@Service
public class ViewCouponHistoryServiceImpl implements IViewCouponHistoryService 
{
    @Autowired
    private ViewCouponHistoryMapper viewCouponHistoryMapper;

    /**
     * 查询VIEW
     * 
     * @param id VIEW主键
     * @return VIEW
     */
    @Override
    public ViewCouponHistory selectViewCouponHistoryById(Long id)
    {
        return viewCouponHistoryMapper.selectViewCouponHistoryById(id);
    }

    /**
     * 查询VIEW列表
     * 
     * @param viewCouponHistory VIEW
     * @return VIEW
     */
    @Override
    public List<ViewCouponHistory> selectViewCouponHistoryList(ViewCouponHistory viewCouponHistory)
    {
        return viewCouponHistoryMapper.selectViewCouponHistoryList(viewCouponHistory);
    }

    /**
     * 新增VIEW
     * 
     * @param viewCouponHistory VIEW
     * @return 结果
     */
    @Override
    public int insertViewCouponHistory(ViewCouponHistory viewCouponHistory)
    {
        return viewCouponHistoryMapper.insertViewCouponHistory(viewCouponHistory);
    }

    /**
     * 修改VIEW
     * 
     * @param viewCouponHistory VIEW
     * @return 结果
     */
    @Override
    public int updateViewCouponHistory(ViewCouponHistory viewCouponHistory)
    {
        return viewCouponHistoryMapper.updateViewCouponHistory(viewCouponHistory);
    }

    /**
     * 批量删除VIEW
     * 
     * @param ids 需要删除的VIEW主键
     * @return 结果
     */
    @Override
    public int deleteViewCouponHistoryByIds(Long[] ids)
    {
        return viewCouponHistoryMapper.deleteViewCouponHistoryByIds(ids);
    }

    /**
     * 删除VIEW信息
     * 
     * @param id VIEW主键
     * @return 结果
     */
    @Override
    public int deleteViewCouponHistoryById(Long id)
    {
        return viewCouponHistoryMapper.deleteViewCouponHistoryById(id);
    }
}
