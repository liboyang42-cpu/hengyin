package com.chengyinhub.business.service;

import java.util.List;
import java.util.Map;
import com.chengyinhub.business.domain.UmsCreativeSquare;
import com.chengyinhub.business.domain.UmsCreativeSquareLike;
import com.chengyinhub.business.domain.UmsMemberFollow;
import com.chengyinhub.business.domain.ViewCreativeSquare;

/**
 * VIEWService接口
 * 
 * @author ruoyi
 * @date 2025-08-29
 */
public interface IViewCreativeSquareService 
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
     * 批量删除VIEW
     * 
     * @param ids 需要删除的VIEW主键集合
     * @return 结果
     */
    public int deleteViewCreativeSquareByIds(Long[] ids);

    /**
     * 删除VIEW信息
     * 
     * @param id VIEW主键
     * @return 结果
     */
    public int deleteViewCreativeSquareById(Long id);

    public List<UmsCreativeSquare> selectCreativeSquareBatchByIds(List<Long> ids);

    public List<UmsCreativeSquareLike> selectCreativeSquareLikesByMemberAndDataIds(Long memberId, List<Long> dataIds);

    public List<Long> selectCommunityBookmarkedPostIds(Long memberId, List<Long> postIds);

    public List<UmsMemberFollow> selectMemberFollowsByMemberAndFollowIds(Long memberId, List<Long> followMemberIds);

    public List<Map<String, Object>> selectSportActivitySummaries(List<Long> activityIds);

    public List<Map<String, Object>> selectSportTopicSummaries(List<Long> topicIds);

    public List<Map<String, Object>> selectNodeCountsByTopicIds(List<Long> topicIds);

    public List<Map<String, Object>> selectProgressCountsByMemberActivities(List<Map<String, Object>> pairs);

    public List<Map<String, Object>> selectProgressCountsByMemberTopics(List<Map<String, Object>> pairs);

    public List<Map<String, Object>> selectMedalCountsByMemberActivities(List<Map<String, Object>> pairs);

    public List<Map<String, Object>> selectMedalCountsByMemberTopics(List<Map<String, Object>> pairs);

    public List<Map<String, Object>> selectExplorePassesByMemberActivities(List<Map<String, Object>> pairs);

    public List<Map<String, Object>> selectRequiredChapterCountsByTopicIds(List<Long> topicIds);

    public List<Map<String, Object>> selectRedeemedRequiredChapterCountsByRegistrations(List<Long> registrationIds);
}
