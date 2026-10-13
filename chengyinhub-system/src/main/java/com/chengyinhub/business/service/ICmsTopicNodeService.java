package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.CmsTopicNode;

/**
 * 主题节点（路线）Service接口
 * 
 * @author ruoyi
 * @date 2025-09-12
 */
public interface ICmsTopicNodeService 
{
    /**
     * 查询主题节点（路线）
     * 
     * @param id 主题节点（路线）主键
     * @return 主题节点（路线）
     */
    public CmsTopicNode selectCmsTopicNodeById(Long id);

    /**
     * 查询主题节点（路线）列表
     * 
     * @param cmsTopicNode 主题节点（路线）
     * @return 主题节点（路线）集合
     */
    public List<CmsTopicNode> selectCmsTopicNodeList(CmsTopicNode cmsTopicNode);

    /**
     * 新增主题节点（路线）
     * 
     * @param cmsTopicNode 主题节点（路线）
     * @return 结果
     */
    public int insertCmsTopicNode(CmsTopicNode cmsTopicNode);

    /**
     * 修改主题节点（路线）
     * 
     * @param cmsTopicNode 主题节点（路线）
     * @return 结果
     */
    public int updateCmsTopicNode(CmsTopicNode cmsTopicNode);

    /**
     * 批量删除主题节点（路线）
     * 
     * @param ids 需要删除的主题节点（路线）主键集合
     * @return 结果
     */
    public int deleteCmsTopicNodeByIds(Long[] ids);

    /**
     * 删除主题节点（路线）信息
     * 
     * @param id 主题节点（路线）主键
     * @return 结果
     */
    public int deleteCmsTopicNodeById(Long id);
}
