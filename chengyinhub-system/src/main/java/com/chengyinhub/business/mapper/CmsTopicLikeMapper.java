package com.chengyinhub.business.mapper;

import java.util.List;
import com.chengyinhub.business.domain.CmsTopicLike;
import org.apache.ibatis.annotations.Param;

/**
 * 主题点赞Mapper接口
 * 
 * @author webxh
 * @date 2025-09-13
 */
public interface CmsTopicLikeMapper 
{
    int ensureOwnedLike(@Param("memberId") Long memberId, @Param("topicId") Long topicId);
    int deleteOwnedLike(@Param("memberId") Long memberId, @Param("topicId") Long topicId);

    /**
     * 查询用户点赞数
     *
     * @param member_id 用户ID
     * @return 结果
     */
    public int selectUmsMemberLikeNumByMemberId(Long member_id);

    long countOwnTopicLikes(@Param("memberId") Long memberId, @Param("topicId") Long topicId);

    int deleteOwnTopicLikes(@Param("memberId") Long memberId, @Param("topicId") Long topicId);


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

    public List<CmsTopicLike> selectCmsTopicLikesByMemberAndDataIds(@Param("memberId") Long memberId,
                                                                    @Param("dataIds") List<Long> dataIds);

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
     * 删除主题点赞
     * 
     * @param id 主题点赞主键
     * @return 结果
     */
    public int deleteCmsTopicLikeById(Long id);

    /**
     * 批量删除主题点赞
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteCmsTopicLikeByIds(Long[] ids);
}
