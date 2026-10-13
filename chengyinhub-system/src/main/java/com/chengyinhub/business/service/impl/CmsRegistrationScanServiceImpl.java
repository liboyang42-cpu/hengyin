package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.CmsRegistrationScanMapper;
import com.chengyinhub.business.domain.CmsRegistrationScan;
import com.chengyinhub.business.service.ICmsRegistrationScanService;

/**
 * 商家扫码记录Service业务层处理
 * 
 * @author ruoyi
 * @date 2025-10-17
 */
@Service
public class CmsRegistrationScanServiceImpl implements ICmsRegistrationScanService 
{
    @Autowired
    private CmsRegistrationScanMapper cmsRegistrationScanMapper;

    /**
     * 查询商家扫码记录
     * 
     * @param id 商家扫码记录主键
     * @return 商家扫码记录
     */
    @Override
    public CmsRegistrationScan selectCmsRegistrationScanById(String id)
    {
        return cmsRegistrationScanMapper.selectCmsRegistrationScanById(id);
    }

    /**
     * 查询商家扫码记录列表
     * 
     * @param cmsRegistrationScan 商家扫码记录
     * @return 商家扫码记录
     */
    @Override
    public List<CmsRegistrationScan> selectCmsRegistrationScanList(CmsRegistrationScan cmsRegistrationScan)
    {
        return cmsRegistrationScanMapper.selectCmsRegistrationScanList(cmsRegistrationScan);
    }

    /**
     * 新增商家扫码记录
     * 
     * @param cmsRegistrationScan 商家扫码记录
     * @return 结果
     */
    @Override
    public int insertCmsRegistrationScan(CmsRegistrationScan cmsRegistrationScan)
    {
        cmsRegistrationScan.setCreateTime(DateUtils.getNowDate());
        return cmsRegistrationScanMapper.insertCmsRegistrationScan(cmsRegistrationScan);
    }

    /**
     * 修改商家扫码记录
     * 
     * @param cmsRegistrationScan 商家扫码记录
     * @return 结果
     */
    @Override
    public int updateCmsRegistrationScan(CmsRegistrationScan cmsRegistrationScan)
    {
        cmsRegistrationScan.setUpdateTime(DateUtils.getNowDate());
        return cmsRegistrationScanMapper.updateCmsRegistrationScan(cmsRegistrationScan);
    }

    /**
     * 批量删除商家扫码记录
     * 
     * @param ids 需要删除的商家扫码记录主键
     * @return 结果
     */
    @Override
    public int deleteCmsRegistrationScanByIds(String[] ids)
    {
        return cmsRegistrationScanMapper.deleteCmsRegistrationScanByIds(ids);
    }

    /**
     * 删除商家扫码记录信息
     * 
     * @param id 商家扫码记录主键
     * @return 结果
     */
    @Override
    public int deleteCmsRegistrationScanById(String id)
    {
        return cmsRegistrationScanMapper.deleteCmsRegistrationScanById(id);
    }
}
