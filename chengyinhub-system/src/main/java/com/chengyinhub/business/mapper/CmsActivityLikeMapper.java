package com.chengyinhub.business.mapper;

import java.util.List;
import com.chengyinhub.business.domain.CmsActivityLike;
import org.apache.ibatis.annotations.Param;

/**
 * 活动点赞Mapper接口
 * 
 * @author webxh
 * @date 2025-08-31
 */
public interface CmsActivityLikeMapper 
{

    /**
     * 查询用户点赞数
     *
     * @param member_id 用户ID
     * @return 结果
     */
    public int selectUmsMemberLikeNumByMemberId(Long member_id);


    /**
     * 查询活动点赞
     * 
     * @param id 活动点赞主键
     * @return 活动点赞
     */
    public CmsActivityLike selectCmsActivityLikeById(Long id);

    /**
     * 查询活动点赞列表
     * 
     * @param cmsActivityLike 活动点赞
     * @return 活动点赞集合
     */
    public List<CmsActivityLike> selectCmsActivityLikeList(CmsActivityLike cmsActivityLike);

    public List<CmsActivityLike> selectCmsActivityLikesByMemberAndDataIds(@Param("memberId") Long memberId,
                                                                          @Param("dataIds") List<Long> dataIds);

    /**
     * 新增活动点赞
     * 
     * @param cmsActivityLike 活动点赞
     * @return 结果
     */
    public int insertCmsActivityLike(CmsActivityLike cmsActivityLike);

    /**
     * 修改活动点赞
     * 
     * @param cmsActivityLike 活动点赞
     * @return 结果
     */
    public int updateCmsActivityLike(CmsActivityLike cmsActivityLike);

    /**
     * 删除活动点赞
     * 
     * @param id 活动点赞主键
     * @return 结果
     */
    public int deleteCmsActivityLikeById(Long id);

    /**
     * 批量删除活动点赞
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteCmsActivityLikeByIds(Long[] ids);
}
