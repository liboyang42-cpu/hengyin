package com.chengyinhub.business.mapper;

import java.util.List;
import com.chengyinhub.business.domain.ViewRegistrationMerchant;

/**
 * VIEWMapper接口
 * 
 * @author ruoyi
 * @date 2026-01-31
 */
public interface ViewRegistrationMerchantMapper 
{
    /**
     * 查询VIEW
     * 
     * @param id VIEW主键
     * @return VIEW
     */
    public ViewRegistrationMerchant selectViewRegistrationMerchantById(Long id);

    /**
     * 查询VIEW列表
     * 
     * @param viewRegistrationMerchant VIEW
     * @return VIEW集合
     */
    public List<ViewRegistrationMerchant> selectViewRegistrationMerchantList(ViewRegistrationMerchant viewRegistrationMerchant);

}
