package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.ViewRegistrationMapper;
import com.chengyinhub.business.domain.ViewRegistration;
import com.chengyinhub.business.service.IViewRegistrationService;

/**
 * VIEWService业务层处理
 * 
 * @author ruoyi
 * @date 2025-09-24
 */
@Service
public class ViewRegistrationServiceImpl implements IViewRegistrationService 
{
    @Autowired
    private ViewRegistrationMapper viewRegistrationMapper;

    /**
     * 查询VIEW
     * 
     * @param id VIEW主键
     * @return VIEW
     */
    @Override
    public ViewRegistration selectViewRegistrationById(Long id)
    {
        return viewRegistrationMapper.selectViewRegistrationById(id);
    }

    /**
     * 查询VIEW列表
     * 
     * @param viewRegistration VIEW
     * @return VIEW
     */
    @Override
    public List<ViewRegistration> selectViewRegistrationList(ViewRegistration viewRegistration)
    {
        return viewRegistrationMapper.selectViewRegistrationList(viewRegistration);
    }

    @Override
    public List<ViewRegistration> selectRegistrationSummaries(ViewRegistration viewRegistration)
    {
        return viewRegistrationMapper.selectRegistrationSummaries(viewRegistration);
    }

    /**
     * 新增VIEW
     * 
     * @param viewRegistration VIEW
     * @return 结果
     */
    @Override
    public int insertViewRegistration(ViewRegistration viewRegistration)
    {
        viewRegistration.setCreateTime(DateUtils.getNowDate());
        return viewRegistrationMapper.insertViewRegistration(viewRegistration);
    }

    /**
     * 修改VIEW
     * 
     * @param viewRegistration VIEW
     * @return 结果
     */
    @Override
    public int updateViewRegistration(ViewRegistration viewRegistration)
    {
        viewRegistration.setUpdateTime(DateUtils.getNowDate());
        return viewRegistrationMapper.updateViewRegistration(viewRegistration);
    }

    /**
     * 批量删除VIEW
     * 
     * @param ids 需要删除的VIEW主键
     * @return 结果
     */
    @Override
    public int deleteViewRegistrationByIds(Long[] ids)
    {
        return viewRegistrationMapper.deleteViewRegistrationByIds(ids);
    }

    /**
     * 删除VIEW信息
     * 
     * @param id VIEW主键
     * @return 结果
     */
    @Override
    public int deleteViewRegistrationById(Long id)
    {
        return viewRegistrationMapper.deleteViewRegistrationById(id);
    }
}
