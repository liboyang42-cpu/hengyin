package com.chengyinhub.business.mapper;

import java.util.List;
import java.util.Map;
import com.chengyinhub.business.domain.UmsCreativeSquare;
import com.chengyinhub.business.domain.UmsCreativeSquareLike;
import com.chengyinhub.business.domain.UmsMemberFollow;
import com.chengyinhub.business.domain.ViewCreativeSquare;
import org.apache.ibatis.annotations.Param;

/**
 * VIEWMapper接口
 * 
 * @author ruoyi
 * @date 2025-08-29
 */
public interface ViewCreativeSquareMapper 
{
    /**
     * 查询VIEW
     * 
     * @param id VIEW主键
     * @return VIEW
     */
    public ViewCreativeSquare selectViewCreativeSquareById(Long id);

    /**
     * 查询VIEW列表
     * 
     * @param viewCreativeSquare VIEW
     * @return VIEW集合
     */
    public List<ViewCreativeSquare> selectViewCreativeSquareList(ViewCreativeSquare viewCreativeSquare);

    /**
     * 新增VIEW
     * 
     * @param viewCreativeSquare VIEW
     * @return 结果
     */
    public int insertViewCreativeSquare(ViewCreativeSquare viewCreativeSquare);

    /**
     * 修改VIEW
     * 
     * @param viewCreativeSquare VIEW
     * @return 结果
     */
    public int updateViewCreativeSquare(ViewCreativeSquare viewCreativeSquare);

    /**
     * 删除VIEW
     * 
     * @param id VIEW主键
     * @return 结果
     */
    public int deleteViewCreativeSquareById(Long id);

    /**
     * 批量删除VIEW
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteViewCreativeSquareByIds(Long[] ids);

    public List<UmsCreativeSquare> selectCreativeSquareBatchByIds(@Param("ids") List<Long> ids);

    public List<UmsCreativeSquareLike> selectCreativeSquareLikesByMemberAndDataIds(@Param("memberId") Long memberId,
                                                                                  @Param("dataIds") List<Long> dataIds);

    public List<Long> selectCommunityBookmarkedPostIds(@Param("memberId") Long memberId,
                                                       @Param("postIds") List<Long> postIds);

    public List<UmsMemberFollow> selectMemberFollowsByMemberAndFollowIds(@Param("memberId") Long memberId,
                                                                         @Param("followMemberIds") List<Long> followMemberIds);

    public List<Map<String, Object>> selectSportActivitySummaries(@Param("activityIds") List<Long> activityIds);

    public List<Map<String, Object>> selectSportTopicSummaries(@Param("topicIds") List<Long> topicIds);

    public List<Map<String, Object>> selectNodeCountsByTopicIds(@Param("topicIds") List<Long> topicIds);

    public List<Map<String, Object>> selectProgressCountsByMemberActivities(@Param("pairs") List<Map<String, Object>> pairs);

    public List<Map<String, Object>> selectProgressCountsByMemberTopics(@Param("pairs") List<Map<String, Object>> pairs);

    public List<Map<String, Object>> selectMedalCountsByMemberActivities(@Param("pairs") List<Map<String, Object>> pairs);

    public List<Map<String, Object>> selectMedalCountsByMemberTopics(@Param("pairs") List<Map<String, Object>> pairs);

    public List<Map<String, Object>> selectExplorePassesByMemberActivities(@Param("pairs") List<Map<String, Object>> pairs);

    public List<Map<String, Object>> selectRequiredChapterCountsByTopicIds(@Param("topicIds") List<Long> topicIds);

    public List<Map<String, Object>> selectRedeemedRequiredChapterCountsByRegistrations(@Param("registrationIds") List<Long> registrationIds);
}
