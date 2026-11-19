package com.chengyinhub.business.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.CmsTopicNode;

/**
 * 主题节点（路线）Mapper接口
 * 
 * @author ruoyi
 * @date 2025-09-12
 */
public interface CmsTopicNodeMapper 
{
    /** All physically retained content, independent of merchant/player visibility. */
    Long countContentByTopicForUpdate(@Param("topicId") Long topicId);

    /** 发布前试检查的只读计数:同表同条件,不加锁(理由见 {@link CmsTopicChapterMapper#countContentByTopic})。 */
    Long countContentByTopic(@Param("topicId") Long topicId);

    /** Current ownership proof after the parent fence, independent of visibility filters. */
    CmsTopicNode selectContentByIdForUpdate(@Param("id") Long id);

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

    /** 主题组合审核按稳定顺序锁定节点。 */
    List<CmsTopicNode> selectByTopicForUpdate(@Param("topicId") Long topicId);

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
     * 删除主题节点（路线）
     * 
     * @param id 主题节点（路线）主键
     * @return 结果
     */
    public int deleteCmsTopicNodeById(Long id);

    /**
     * 批量删除主题节点（路线）
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteCmsTopicNodeByIds(Long[] ids);

    /** F10 撤回自己承接:只清作者本人在该点位上的承接归属与审核痕迹(带 owner 条件)。 */
    int releaseAuthorSelfSupplyBinding(@Param("id") Long id, @Param("authorMemberId") Long authorMemberId);
    /** 自由探索到访配置(单个节点)。列由 migration_20261118_fe_story_visit_gate.sql 新增。 */
    com.chengyinhub.business.domain.FreeExploreVisitConfig selectVisitConfigById(@org.apache.ibatis.annotations.Param("nodeId") Long nodeId);

    /** 自由探索到访配置(整个主题),/api/play/nodes 投影用。 */
    List<com.chengyinhub.business.domain.FreeExploreVisitConfig> selectVisitConfigByTopicId(@org.apache.ibatis.annotations.Param("topicId") Long topicId);

    /** 自由探索作者设置地点到访方式与半径(FE-STORY 7.10);只改这两列。 */
    int updateVisitConfig(@org.apache.ibatis.annotations.Param("nodeId") Long nodeId,
                          @org.apache.ibatis.annotations.Param("visitCheck") Integer visitCheck,
                          @org.apache.ibatis.annotations.Param("visitRadiusM") Integer visitRadiusM);
}
