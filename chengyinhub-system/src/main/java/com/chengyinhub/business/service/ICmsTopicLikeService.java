package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.CmsTopicLike;

/**
 * 主题点赞Service接口
 * 
 * @author webxh
 * @date 2025-09-13
 */
public interface ICmsTopicLikeService 
{

    /**
     * 查询用户点赞数
     *
     * @param member_id 用户ID
     * @return 结果
     */
    public int selectUmsMemberLikeNumByMemberId(Long member_id);


    /**
     * 查询主题点赞
     * 
     * @param id 主题点赞主键
     * @return 主题点赞
     */
    public CmsTopicLike selectCmsTopicLikeById(Long id);

    /**
     * 查询主题点赞列表
     * 
     * @param cmsTopicLike 主题点赞
     * @return 主题点赞集合
     */
    public List<CmsTopicLike> selectCmsTopicLikeList(CmsTopicLike cmsTopicLike);

    public List<CmsTopicLike> selectCmsTopicLikesByMemberAndDataIds(Long memberId, List<Long> dataIds);

    /**
     * 新增主题点赞
     * 
     * @param cmsTopicLike 主题点赞
     * @return 结果
     */
    public int insertCmsTopicLike(CmsTopicLike cmsTopicLike);

    /**
     * 修改主题点赞
     * 
     * @param cmsTopicLike 主题点赞
     * @return 结果
     */
    public int updateCmsTopicLike(CmsTopicLike cmsTopicLike);

    /**
     * 批量删除主题点赞
     * 
     * @param ids 需要删除的主题点赞主键集合
     * @return 结果
     */
    public int deleteCmsTopicLikeByIds(Long[] ids);

    /**
     * 删除主题点赞信息
     * 
     * @param id 主题点赞主键
     * @return 结果
     */
    public int deleteCmsTopicLikeById(Long id);
}
