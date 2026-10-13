package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.ViewCollaborators;

/**
 * 合作者视图Service接口
 * 
 * @author webxh
 * @date 2025-09-04
 */
public interface IViewCollaboratorsService 
{
    /**
     * 查询合作者视图
     * 
     * @param id 合作者视图主键
     * @return 合作者视图
     */
    public ViewCollaborators selectViewCollaboratorsById(Long id);

    /**
     * 查询合作者视图列表
     * 
     * @param viewCollaborators 合作者视图
     * @return 合作者视图集合
     */
    public List<ViewCollaborators> selectViewCollaboratorsList(ViewCollaborators viewCollaborators);

    /**
     * 新增合作者视图
     * 
     * @param viewCollaborators 合作者视图
     * @return 结果
     */
    public int insertViewCollaborators(ViewCollaborators viewCollaborators);

    /**
     * 修改合作者视图
     * 
     * @param viewCollaborators 合作者视图
     * @return 结果
     */
    public int updateViewCollaborators(ViewCollaborators viewCollaborators);

    /**
     * 批量删除合作者视图
     * 
     * @param ids 需要删除的合作者视图主键集合
     * @return 结果
     */
    public int deleteViewCollaboratorsByIds(Long[] ids);

    /**
     * 删除合作者视图信息
     * 
     * @param id 合作者视图主键
     * @return 结果
     */
    public int deleteViewCollaboratorsById(Long id);
}
