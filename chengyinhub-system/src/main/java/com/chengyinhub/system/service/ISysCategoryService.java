package com.chengyinhub.system.service;

import java.util.List;
import com.chengyinhub.system.domain.SysCategory;

/**
 * 活动、主题等分类Service接口
 * 
 * @author webxh
 * @date 2025-08-31
 */
public interface ISysCategoryService 
{
    /**
     * 查询活动、主题等分类
     * 
     * @param id 活动、主题等分类主键
     * @return 活动、主题等分类
     */
    public SysCategory selectSysCategoryById(Long id);

    /**
     * 查询活动、主题等分类列表
     * 
     * @param sysCategory 活动、主题等分类
     * @return 活动、主题等分类集合
     */
    public List<SysCategory> selectSysCategoryList(SysCategory sysCategory);

    /**
     * 新增活动、主题等分类
     * 
     * @param sysCategory 活动、主题等分类
     * @return 结果
     */
    public int insertSysCategory(SysCategory sysCategory);

    /**
     * 修改活动、主题等分类
     * 
     * @param sysCategory 活动、主题等分类
     * @return 结果
     */
    public int updateSysCategory(SysCategory sysCategory);

    /**
     * 批量删除活动、主题等分类
     * 
     * @param ids 需要删除的活动、主题等分类主键集合
     * @return 结果
     */
    public int deleteSysCategoryByIds(Long[] ids);

    /**
     * 删除活动、主题等分类信息
     * 
     * @param id 活动、主题等分类主键
     * @return 结果
     */
    public int deleteSysCategoryById(Long id);

    /**
     * 根据ids查询
     * @param categoryIds
     * @return
     */
    public List<SysCategory> selectSysCategoryListByIds(String[] categoryIds);
}
