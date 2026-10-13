package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.CmsCollaborators;

/**
 * 活动/主题合作者Service接口
 * 
 * @author webxh
 * @date 2025-09-02
 */
public interface ICmsCollaboratorsService 
{
    /**
     * 查询活动/主题合作者
     * 
     * @param id 活动/主题合作者主键
     * @return 活动/主题合作者
     */
    public CmsCollaborators selectCmsCollaboratorsById(Long id);

    /**
     * 查询活动/主题合作者列表
     * 
     * @param cmsCollaborators 活动/主题合作者
     * @return 活动/主题合作者集合
     */
    public List<CmsCollaborators> selectCmsCollaboratorsList(CmsCollaborators cmsCollaborators);

    /**
     * 新增活动/主题合作者
     * 
     * @param cmsCollaborators 活动/主题合作者
     * @return 结果
     */
    public int insertCmsCollaborators(CmsCollaborators cmsCollaborators);

    /**
     * 修改活动/主题合作者
     * 
     * @param cmsCollaborators 活动/主题合作者
     * @return 结果
     */
    public int updateCmsCollaborators(CmsCollaborators cmsCollaborators);

    /**
     * 批量删除活动/主题合作者
     * 
     * @param ids 需要删除的活动/主题合作者主键集合
     * @return 结果
     */
    public int deleteCmsCollaboratorsByIds(Long[] ids);

    /**
     * 删除活动/主题合作者信息
     * 
     * @param id 活动/主题合作者主键
     * @return 结果
     */
    public int deleteCmsCollaboratorsById(Long id);
}
