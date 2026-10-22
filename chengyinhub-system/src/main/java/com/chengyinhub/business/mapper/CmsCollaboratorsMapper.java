package com.chengyinhub.business.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.CmsCollaborators;

/**
 * 活动/主题合作者Mapper接口
 * 
 * @author webxh
 * @date 2025-09-02
 */
public interface CmsCollaboratorsMapper 
{
    int deleteActivityCollaboratorsByOwner(@Param("activityId") Long activityId,
                                           @Param("ownerMemberId") Long ownerMemberId);

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
     * 删除活动/主题合作者
     * 
     * @param id 活动/主题合作者主键
     * @return 结果
     */
    public int deleteCmsCollaboratorsById(Long id);

    /**
     * 批量删除活动/主题合作者
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteCmsCollaboratorsByIds(Long[] ids);
}
