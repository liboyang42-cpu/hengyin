package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.CmsPointsSetting;

/**
 * 积分规则与分润设置Service接口
 * 
 * @author ruoyi
 * @date 2026-01-31
 */
public interface ICmsPointsSettingService 
{
    /**
     * 查询积分规则与分润设置
     * 
     * @param id 积分规则与分润设置主键
     * @return 积分规则与分润设置
     */
    public CmsPointsSetting selectCmsPointsSettingById(Long id);

    /**
     * 查询积分规则与分润设置列表
     * 
     * @param cmsPointsSetting 积分规则与分润设置
     * @return 积分规则与分润设置集合
     */
    public List<CmsPointsSetting> selectCmsPointsSettingList(CmsPointsSetting cmsPointsSetting);

    /**
     * 新增积分规则与分润设置
     * 
     * @param cmsPointsSetting 积分规则与分润设置
     * @return 结果
     */
    public int insertCmsPointsSetting(CmsPointsSetting cmsPointsSetting);

    /**
     * 修改积分规则与分润设置
     * 
     * @param cmsPointsSetting 积分规则与分润设置
     * @return 结果
     */
    public int updateCmsPointsSetting(CmsPointsSetting cmsPointsSetting);

    /**
     * 批量删除积分规则与分润设置
     * 
     * @param ids 需要删除的积分规则与分润设置主键集合
     * @return 结果
     */
    public int deleteCmsPointsSettingByIds(Long[] ids);

    /**
     * 删除积分规则与分润设置信息
     * 
     * @param id 积分规则与分润设置主键
     * @return 结果
     */
    public int deleteCmsPointsSettingById(Long id);
}
