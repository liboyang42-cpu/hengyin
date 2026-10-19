package com.chengyinhub.business.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.chengyinhub.business.domain.UmsCreativeSquare;
import com.chengyinhub.business.domain.UmsCreativeSquareLike;
import com.chengyinhub.business.domain.UmsMemberFollow;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.StringUtils;
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

    @Autowired
    private ReviewedMediaVisibility reviewedMediaVisibility;

    /**
     * CL-39：广场帖 pics 发布即 status=1、图片异步送检(bizType=9)，回调 PASS 不写广场表。
     * 他人只看 inbox 已落地 PASS 的图；主题同步帖(dataType=2)的图按主题(bizType=1)送检，主题的 PASS 同样算数。
     * 风险回调仍走既有 status=2 整帖下架，这里不改。
     */
    @Override
    public void applyMediaVisibility(List<ViewCreativeSquare> list, Long viewerId)
    {
        if (list == null || list.isEmpty()) return;
        List<Long> squareIds = new ArrayList<>();
        List<Long> topicIds = new ArrayList<>();
        for (ViewCreativeSquare square : list) {
            if (square == null || square.getId() == null || StringUtils.isEmpty(square.getPics())) continue;
            squareIds.add(square.getId());
            if (Long.valueOf(2L).equals(square.getDataType()) && square.getDataId() != null && square.getDataId() > 0) {
                topicIds.add(square.getDataId());
            }
        }
        if (squareIds.isEmpty()) return;
        Map<Long, Set<String>> passedSquare = reviewedMediaVisibility.passedMediaHashes(
                IWxContentSecurityService.BIZ_TYPE_SQUARE, squareIds);
        Map<Long, Set<String>> passedTopic = reviewedMediaVisibility.passedMediaHashes(
                IWxContentSecurityService.BIZ_TYPE_TOPIC, topicIds);
        for (ViewCreativeSquare square : list) {
            if (square == null || !squareIds.contains(square.getId())) continue;
            Set<String> hashes = new HashSet<>(passedSquare.getOrDefault(square.getId(), Collections.<String>emptySet()));
            if (Long.valueOf(2L).equals(square.getDataType())) {
                hashes.addAll(passedTopic.getOrDefault(square.getDataId(), Collections.<String>emptySet()));
            }
            boolean allPassed = ReviewedMediaVisibility.allPassed(square.getPics(), hashes);
            if (viewerId != null && viewerId > 0 && viewerId.equals(square.getMemberId())) {
                square.setMediaReviewState(allPassed ? ReviewedMediaVisibility.STATE_PASS
                        : ReviewedMediaVisibility.STATE_PENDING);
            } else if (!allPassed) {
                square.setPics(ReviewedMediaVisibility.passedOnly(square.getPics(), hashes));
            }
        }
    }

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
