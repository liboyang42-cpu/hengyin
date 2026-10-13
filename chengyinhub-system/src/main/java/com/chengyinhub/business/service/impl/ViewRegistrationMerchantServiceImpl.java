package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.ViewRegistrationMerchantMapper;
import com.chengyinhub.business.domain.ViewRegistrationMerchant;
import com.chengyinhub.business.service.IViewRegistrationMerchantService;

/**
 * VIEWService业务层处理
 * 
 * @author ruoyi
 * @date 2026-01-31
 */
@Service
public class ViewRegistrationMerchantServiceImpl implements IViewRegistrationMerchantService 
{
    @Autowired
    private ViewRegistrationMerchantMapper viewRegistrationMerchantMapper;

    /**
     * 查询VIEW
     * 
     * @param id VIEW主键
     * @return VIEW
     */
    @Override
    public ViewRegistrationMerchant selectViewRegistrationMerchantById(Long id)
    {
        return viewRegistrationMerchantMapper.selectViewRegistrationMerchantById(id);
    }

    /**
     * 查询VIEW列表
     * 
     * @param viewRegistrationMerchant VIEW
     * @return VIEW
     */
    @Override
    public List<ViewRegistrationMerchant> selectViewRegistrationMerchantList(ViewRegistrationMerchant viewRegistrationMerchant)
    {
        return viewRegistrationMerchantMapper.selectViewRegistrationMerchantList(viewRegistrationMerchant);
    }


}
