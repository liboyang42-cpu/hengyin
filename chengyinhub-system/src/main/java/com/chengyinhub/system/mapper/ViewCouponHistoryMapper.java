package com.chengyinhub.system.mapper;

import java.util.List;
import com.chengyinhub.system.domain.ViewCouponHistory;

/**
 * VIEWMapper接口
 * 
 * @author ruoyi
 * @date 2025-10-16
 */
public interface ViewCouponHistoryMapper 
{
    /**
     * 查询VIEW
     * 
     * @param id VIEW主键
     * @return VIEW
     */
    public ViewCouponHistory selectViewCouponHistoryById(Long id);

    /**
     * 查询VIEW列表
     * 
     * @param viewCouponHistory VIEW
     * @return VIEW集合
     */
    public List<ViewCouponHistory> selectViewCouponHistoryList(ViewCouponHistory viewCouponHistory);

    /**
     * 新增VIEW
     * 
     * @param viewCouponHistory VIEW
     * @return 结果
     */
    public int insertViewCouponHistory(ViewCouponHistory viewCouponHistory);

    /**
     * 修改VIEW
     * 
     * @param viewCouponHistory VIEW
     * @return 结果
     */
    public int updateViewCouponHistory(ViewCouponHistory viewCouponHistory);

    /**
     * 删除VIEW
     * 
     * @param id VIEW主键
     * @return 结果
     */
    public int deleteViewCouponHistoryById(Long id);

    /**
     * 批量删除VIEW
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteViewCouponHistoryByIds(Long[] ids);
}
