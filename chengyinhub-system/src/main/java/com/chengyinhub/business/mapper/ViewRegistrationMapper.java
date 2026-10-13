package com.chengyinhub.business.mapper;

import java.util.List;
import com.chengyinhub.business.domain.ViewRegistration;

/**
 * VIEWMapper接口
 * 
 * @author ruoyi
 * @date 2025-09-24
 */
public interface ViewRegistrationMapper 
{
    /**
     * 查询VIEW
     * 
     * @param id VIEW主键
     * @return VIEW
     */
    public ViewRegistration selectViewRegistrationById(Long id);

    /**
     * 查询VIEW列表
     * 
     * @param viewRegistration VIEW
     * @return VIEW集合
     */
    public List<ViewRegistration> selectViewRegistrationList(ViewRegistration viewRegistration);

    /** 查询报名展示字段，不读取或解密个人隐私与核销凭证。 */
    public List<ViewRegistration> selectRegistrationSummaries(ViewRegistration viewRegistration);

    /**
     * 新增VIEW
     * 
     * @param viewRegistration VIEW
     * @return 结果
     */
    public int insertViewRegistration(ViewRegistration viewRegistration);

    /**
     * 修改VIEW
     * 
     * @param viewRegistration VIEW
     * @return 结果
     */
    public int updateViewRegistration(ViewRegistration viewRegistration);

    /**
     * 删除VIEW
     * 
     * @param id VIEW主键
     * @return 结果
     */
    public int deleteViewRegistrationById(Long id);

    /**
     * 批量删除VIEW
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteViewRegistrationByIds(Long[] ids);
}
