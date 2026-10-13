package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.ViewMemberCartMapper;
import com.chengyinhub.business.domain.ViewMemberCart;
import com.chengyinhub.business.service.IViewMemberCartService;

/**
 * VIEWService业务层处理
 * 
 * @author ruoyi
 * @date 2025-09-03
 */
@Service
public class ViewMemberCartServiceImpl implements IViewMemberCartService 
{
    @Autowired
    private ViewMemberCartMapper viewMemberCartMapper;

    /**
     * 查询VIEW
     * 
     * @param id VIEW主键
     * @return VIEW
     */
    @Override
    public ViewMemberCart selectViewMemberCartById(Long id)
    {
        return viewMemberCartMapper.selectViewMemberCartById(id);
    }

    /**
     * 查询VIEW列表
     * 
     * @param viewMemberCart VIEW
     * @return VIEW
     */
    @Override
    public List<ViewMemberCart> selectViewMemberCartList(ViewMemberCart viewMemberCart)
    {
        return viewMemberCartMapper.selectViewMemberCartList(viewMemberCart);
    }

    public List<ViewMemberCart> selectViewMemberCartByIds(Long[] ids)
    {
        return viewMemberCartMapper.selectViewMemberCartByIds(ids);
    }

    /**
     * 新增VIEW
     * 
     * @param viewMemberCart VIEW
     * @return 结果
     */
    @Override
    public int insertViewMemberCart(ViewMemberCart viewMemberCart)
    {
        viewMemberCart.setCreateTime(DateUtils.getNowDate());
        return viewMemberCartMapper.insertViewMemberCart(viewMemberCart);
    }

    /**
     * 修改VIEW
     * 
     * @param viewMemberCart VIEW
     * @return 结果
     */
    @Override
    public int updateViewMemberCart(ViewMemberCart viewMemberCart)
    {
        viewMemberCart.setUpdateTime(DateUtils.getNowDate());
        return viewMemberCartMapper.updateViewMemberCart(viewMemberCart);
    }

    /**
     * 批量删除VIEW
     * 
     * @param ids 需要删除的VIEW主键
     * @return 结果
     */
    @Override
    public int deleteViewMemberCartByIds(Long[] ids)
    {
        return viewMemberCartMapper.deleteViewMemberCartByIds(ids);
    }

    /**
     * 删除VIEW信息
     * 
     * @param id VIEW主键
     * @return 结果
     */
    @Override
    public int deleteViewMemberCartById(Long id)
    {
        return viewMemberCartMapper.deleteViewMemberCartById(id);
    }
}
