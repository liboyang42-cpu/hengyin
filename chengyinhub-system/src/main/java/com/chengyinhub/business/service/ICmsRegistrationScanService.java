package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.CmsRegistrationScan;

/**
 * 商家扫码记录Service接口
 * 
 * @author ruoyi
 * @date 2025-10-17
 */
public interface ICmsRegistrationScanService 
{
    /**
     * 查询商家扫码记录
     * 
     * @param id 商家扫码记录主键
     * @return 商家扫码记录
     */
    public CmsRegistrationScan selectCmsRegistrationScanById(String id);

    /**
     * 查询商家扫码记录列表
     * 
     * @param cmsRegistrationScan 商家扫码记录
     * @return 商家扫码记录集合
     */
    public List<CmsRegistrationScan> selectCmsRegistrationScanList(CmsRegistrationScan cmsRegistrationScan);

    /**
     * 新增商家扫码记录
     * 
     * @param cmsRegistrationScan 商家扫码记录
     * @return 结果
     */
    public int insertCmsRegistrationScan(CmsRegistrationScan cmsRegistrationScan);

    /**
     * 修改商家扫码记录
     * 
     * @param cmsRegistrationScan 商家扫码记录
     * @return 结果
     */
    public int updateCmsRegistrationScan(CmsRegistrationScan cmsRegistrationScan);

    /**
     * 批量删除商家扫码记录
     * 
     * @param ids 需要删除的商家扫码记录主键集合
     * @return 结果
     */
    public int deleteCmsRegistrationScanByIds(String[] ids);

    /**
     * 删除商家扫码记录信息
     * 
     * @param id 商家扫码记录主键
     * @return 结果
     */
    public int deleteCmsRegistrationScanById(String id);
}
