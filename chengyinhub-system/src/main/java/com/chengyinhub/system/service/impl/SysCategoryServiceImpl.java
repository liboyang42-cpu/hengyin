package com.chengyinhub.system.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.system.mapper.SysCategoryMapper;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;

/**
 * 活动、主题等分类Service业务层处理
 * 
 * @author webxh
 * @date 2025-08-31
 */
@Service
public class SysCategoryServiceImpl implements ISysCategoryService 
{
    @Autowired
    private SysCategoryMapper sysCategoryMapper;

    /**
     * 查询活动、主题等分类
     * 
     * @param id 活动、主题等分类主键
     * @return 活动、主题等分类
     */
    @Override
    public SysCategory selectSysCategoryById(Long id)
    {
        return sysCategoryMapper.selectSysCategoryById(id);
    }

    /**
     * 查询活动、主题等分类列表
     * 
     * @param sysCategory 活动、主题等分类
     * @return 活动、主题等分类
     */
    @Override
    public List<SysCategory> selectSysCategoryList(SysCategory sysCategory)
    {
        return sysCategoryMapper.selectSysCategoryList(sysCategory);
    }

    /**
     * 新增活动、主题等分类
     * 
     * @param sysCategory 活动、主题等分类
     * @return 结果
     */
    @Override
    public int insertSysCategory(SysCategory sysCategory)
    {
        sysCategory.setCreateTime(DateUtils.getNowDate());
        return sysCategoryMapper.insertSysCategory(sysCategory);
    }

    /**
     * 修改活动、主题等分类
     * 
     * @param sysCategory 活动、主题等分类
     * @return 结果
     */
    @Override
    public int updateSysCategory(SysCategory sysCategory)
    {
        sysCategory.setUpdateTime(DateUtils.getNowDate());
        return sysCategoryMapper.updateSysCategory(sysCategory);
    }

    /**
     * 批量删除活动、主题等分类
     * 
     * @param ids 需要删除的活动、主题等分类主键
     * @return 结果
     */
    @Override
    public int deleteSysCategoryByIds(Long[] ids)
    {
        return sysCategoryMapper.deleteSysCategoryByIds(ids);
    }

    /**
     * 删除活动、主题等分类信息
     * 
     * @param id 活动、主题等分类主键
     * @return 结果
     */
    @Override
    public int deleteSysCategoryById(Long id)
    {
        return sysCategoryMapper.deleteSysCategoryById(id);
    }

    /**
     *  根据ids查询分类
     * @param categoryIds
     * @return
     */
    @Override
    public List<SysCategory> selectSysCategoryListByIds(String[] categoryIds) {
        return sysCategoryMapper.selectSysCategoryListByIds(categoryIds);
    }
}
