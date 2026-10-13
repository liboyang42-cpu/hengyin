package com.chengyinhub.business.mapper;

import java.util.List;
import com.chengyinhub.business.domain.CmsSetting;

/**
 * 参数设置Mapper接口
 * 
 * @author ruoyi
 * @date 2025-11-17
 */
public interface CmsSettingMapper 
{
    /**
     * 查询参数设置
     * 
     * @param id 参数设置主键
     * @return 参数设置
     */
    public CmsSetting selectCmsSettingById(Long id);

    /**
     * 查询参数设置列表
     * 
     * @param cmsSetting 参数设置
     * @return 参数设置集合
     */
    public List<CmsSetting> selectCmsSettingList(CmsSetting cmsSetting);

    /**
     * 新增参数设置
     * 
     * @param cmsSetting 参数设置
     * @return 结果
     */
    public int insertCmsSetting(CmsSetting cmsSetting);

    /**
     * 修改参数设置
     * 
     * @param cmsSetting 参数设置
     * @return 结果
     */
    public int updateCmsSetting(CmsSetting cmsSetting);

    /**
     * 删除参数设置
     * 
     * @param id 参数设置主键
     * @return 结果
     */
    public int deleteCmsSettingById(Long id);

    /**
     * 批量删除参数设置
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteCmsSettingByIds(Long[] ids);
}
