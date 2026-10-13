package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.ViewMemberCart;

/**
 * VIEWService接口
 * 
 * @author ruoyi
 * @date 2025-09-03
 */
public interface IViewMemberCartService 
{
    /**
     * 查询VIEW
     * 
     * @param id VIEW主键
     * @return VIEW
     */
    public ViewMemberCart selectViewMemberCartById(Long id);

    /**
     * 查询VIEW列表
     * 
     * @param viewMemberCart VIEW
     * @return VIEW集合
     */
    public List<ViewMemberCart> selectViewMemberCartList(ViewMemberCart viewMemberCart);


    public List<ViewMemberCart> selectViewMemberCartByIds(Long[] ids);

    /**
     * 新增VIEW
     * 
     * @param viewMemberCart VIEW
     * @return 结果
     */
    public int insertViewMemberCart(ViewMemberCart viewMemberCart);

    /**
     * 修改VIEW
     * 
     * @param viewMemberCart VIEW
     * @return 结果
     */
    public int updateViewMemberCart(ViewMemberCart viewMemberCart);

    /**
     * 批量删除VIEW
     * 
     * @param ids 需要删除的VIEW主键集合
     * @return 结果
     */
    public int deleteViewMemberCartByIds(Long[] ids);

    /**
     * 删除VIEW信息
     * 
     * @param id VIEW主键
     * @return 结果
     */
    public int deleteViewMemberCartById(Long id);
}
