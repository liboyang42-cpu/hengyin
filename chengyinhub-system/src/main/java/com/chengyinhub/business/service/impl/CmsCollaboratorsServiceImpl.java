package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.CmsCollaboratorsMapper;
import com.chengyinhub.business.domain.CmsCollaborators;
import com.chengyinhub.business.service.ICmsCollaboratorsService;

/**
 * 活动/主题合作者Service业务层处理
 * 
 * @author webxh
 * @date 2025-09-02
 */
@Service
public class CmsCollaboratorsServiceImpl implements ICmsCollaboratorsService 
{
    @Autowired
    private CmsCollaboratorsMapper cmsCollaboratorsMapper;

    /**
     * 查询活动/主题合作者
     * 
     * @param id 活动/主题合作者主键
     * @return 活动/主题合作者
     */
    @Override
    public CmsCollaborators selectCmsCollaboratorsById(Long id)
    {
        return cmsCollaboratorsMapper.selectCmsCollaboratorsById(id);
    }

    /**
     * 查询活动/主题合作者列表
     * 
     * @param cmsCollaborators 活动/主题合作者
     * @return 活动/主题合作者
     */
    @Override
    public List<CmsCollaborators> selectCmsCollaboratorsList(CmsCollaborators cmsCollaborators)
    {
        return cmsCollaboratorsMapper.selectCmsCollaboratorsList(cmsCollaborators);
    }

    /**
     * 新增活动/主题合作者
     * 
     * @param cmsCollaborators 活动/主题合作者
     * @return 结果
     */
    @Override
    public int insertCmsCollaborators(CmsCollaborators cmsCollaborators)
    {
        cmsCollaborators.setCreateTime(DateUtils.getNowDate());
        return cmsCollaboratorsMapper.insertCmsCollaborators(cmsCollaborators);
    }

    /**
     * 修改活动/主题合作者
     * 
     * @param cmsCollaborators 活动/主题合作者
     * @return 结果
     */
    @Override
    public int updateCmsCollaborators(CmsCollaborators cmsCollaborators)
    {
        cmsCollaborators.setUpdateTime(DateUtils.getNowDate());
        return cmsCollaboratorsMapper.updateCmsCollaborators(cmsCollaborators);
    }

    /**
     * 批量删除活动/主题合作者
     * 
     * @param ids 需要删除的活动/主题合作者主键
     * @return 结果
     */
    @Override
    public int deleteCmsCollaboratorsByIds(Long[] ids)
    {
        return cmsCollaboratorsMapper.deleteCmsCollaboratorsByIds(ids);
    }

    /**
     * 删除活动/主题合作者信息
     * 
     * @param id 活动/主题合作者主键
     * @return 结果
     */
    @Override
    public int deleteCmsCollaboratorsById(Long id)
    {
        return cmsCollaboratorsMapper.deleteCmsCollaboratorsById(id);
    }
}
