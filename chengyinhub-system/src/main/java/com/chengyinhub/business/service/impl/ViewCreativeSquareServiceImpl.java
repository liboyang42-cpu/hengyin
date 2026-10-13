package com.chengyinhub.business.service.impl;

import java.util.List;
import java.util.Map;
import com.chengyinhub.business.domain.UmsCreativeSquare;
import com.chengyinhub.business.domain.UmsCreativeSquareLike;
import com.chengyinhub.business.domain.UmsMemberFollow;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.ViewCreativeSquareMapper;
import com.chengyinhub.business.domain.ViewCreativeSquare;
import com.chengyinhub.business.service.IViewCreativeSquareService;

/**
 * VIEWService业务层处理
 * 
 * @author ruoyi
 * @date 2025-08-29
 */
@Service
public class ViewCreativeSquareServiceImpl implements IViewCreativeSquareService 
{
    @Autowired
    private ViewCreativeSquareMapper viewCreativeSquareMapper;

    /**
     * 查询VIEW
     * 
     * @param id VIEW主键
     * @return VIEW
     */
    @Override
    public ViewCreativeSquare selectViewCreativeSquareById(Long id)
    {
        return viewCreativeSquareMapper.selectViewCreativeSquareById(id);
    }

    /**
     * 查询VIEW列表
     * 
     * @param viewCreativeSquare VIEW
     * @return VIEW
     */
    @Override
    public List<ViewCreativeSquare> selectViewCreativeSquareList(ViewCreativeSquare viewCreativeSquare)
    {
        return viewCreativeSquareMapper.selectViewCreativeSquareList(viewCreativeSquare);
    }

    /**
     * 新增VIEW
     * 
     * @param viewCreativeSquare VIEW
     * @return 结果
     */
    @Override
    public int insertViewCreativeSquare(ViewCreativeSquare viewCreativeSquare)
    {
        viewCreativeSquare.setCreateTime(DateUtils.getNowDate());
        return viewCreativeSquareMapper.insertViewCreativeSquare(viewCreativeSquare);
    }

    /**
     * 修改VIEW
     * 
     * @param viewCreativeSquare VIEW
     * @return 结果
     */
    @Override
    public int updateViewCreativeSquare(ViewCreativeSquare viewCreativeSquare)
    {
        viewCreativeSquare.setUpdateTime(DateUtils.getNowDate());
        return viewCreativeSquareMapper.updateViewCreativeSquare(viewCreativeSquare);
    }

    /**
     * 批量删除VIEW
     * 
     * @param ids 需要删除的VIEW主键
     * @return 结果
     */
    @Override
    public int deleteViewCreativeSquareByIds(Long[] ids)
    {
        return viewCreativeSquareMapper.deleteViewCreativeSquareByIds(ids);
    }

    /**
     * 删除VIEW信息
     * 
     * @param id VIEW主键
     * @return 结果
     */
    @Override
    public int deleteViewCreativeSquareById(Long id)
    {
        return viewCreativeSquareMapper.deleteViewCreativeSquareById(id);
    }

    @Override
    public List<UmsCreativeSquare> selectCreativeSquareBatchByIds(List<Long> ids) {
        return viewCreativeSquareMapper.selectCreativeSquareBatchByIds(ids);
    }

    @Override
    public List<UmsCreativeSquareLike> selectCreativeSquareLikesByMemberAndDataIds(Long memberId, List<Long> dataIds) {
        return viewCreativeSquareMapper.selectCreativeSquareLikesByMemberAndDataIds(memberId, dataIds);
    }

    @Override
    public List<Long> selectCommunityBookmarkedPostIds(Long memberId, List<Long> postIds) {
        return viewCreativeSquareMapper.selectCommunityBookmarkedPostIds(memberId, postIds);
    }

    @Override
    public List<UmsMemberFollow> selectMemberFollowsByMemberAndFollowIds(Long memberId, List<Long> followMemberIds) {
        return viewCreativeSquareMapper.selectMemberFollowsByMemberAndFollowIds(memberId, followMemberIds);
    }

    @Override
    public List<Map<String, Object>> selectSportActivitySummaries(List<Long> activityIds) {
        return viewCreativeSquareMapper.selectSportActivitySummaries(activityIds);
    }

    @Override
    public List<Map<String, Object>> selectSportTopicSummaries(List<Long> topicIds) {
        return viewCreativeSquareMapper.selectSportTopicSummaries(topicIds);
    }

    @Override
    public List<Map<String, Object>> selectNodeCountsByTopicIds(List<Long> topicIds) {
        return viewCreativeSquareMapper.selectNodeCountsByTopicIds(topicIds);
    }

    @Override
    public List<Map<String, Object>> selectProgressCountsByMemberActivities(List<Map<String, Object>> pairs) {
        return viewCreativeSquareMapper.selectProgressCountsByMemberActivities(pairs);
    }

    @Override
    public List<Map<String, Object>> selectProgressCountsByMemberTopics(List<Map<String, Object>> pairs) {
        return viewCreativeSquareMapper.selectProgressCountsByMemberTopics(pairs);
    }

    @Override
    public List<Map<String, Object>> selectMedalCountsByMemberActivities(List<Map<String, Object>> pairs) {
        return viewCreativeSquareMapper.selectMedalCountsByMemberActivities(pairs);
    }

    @Override
    public List<Map<String, Object>> selectMedalCountsByMemberTopics(List<Map<String, Object>> pairs) {
        return viewCreativeSquareMapper.selectMedalCountsByMemberTopics(pairs);
    }

    @Override
    public List<Map<String, Object>> selectExplorePassesByMemberActivities(List<Map<String, Object>> pairs) {
        return viewCreativeSquareMapper.selectExplorePassesByMemberActivities(pairs);
    }

    @Override
    public List<Map<String, Object>> selectRequiredChapterCountsByTopicIds(List<Long> topicIds) {
        return viewCreativeSquareMapper.selectRequiredChapterCountsByTopicIds(topicIds);
    }

    @Override
    public List<Map<String, Object>> selectRedeemedRequiredChapterCountsByRegistrations(List<Long> registrationIds) {
        return viewCreativeSquareMapper.selectRedeemedRequiredChapterCountsByRegistrations(registrationIds);
    }
}
