package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.CmsTopicLikeMapper;
import com.chengyinhub.business.domain.CmsTopicLike;
import com.chengyinhub.business.service.ICmsTopicLikeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.common.exception.ServiceException;

/**
 * 主题点赞Service业务层处理
 * 
 * @author webxh
 * @date 2025-09-13
 */
@Service
public class CmsTopicLikeServiceImpl implements ICmsTopicLikeService 
{
    @Autowired
    private CmsTopicLikeMapper cmsTopicLikeMapper;

<<<<<<< public
    @Override
    public int setLiked(Long memberId, Long topicId, boolean liked) {
        if (memberId == null || memberId <= 0 || topicId == null || topicId <= 0) {
            throw new IllegalArgumentException("点赞身份或主题参数不完整");
        }
        return liked ? cmsTopicLikeMapper.ensureOwnedLike(memberId, topicId)
                : cmsTopicLikeMapper.deleteOwnedLike(memberId, topicId);
    }
=======
    @Autowired
    private ICmsTopicService cmsTopicService;
>>>>>>> private-github/featu


    /**
     * 查询用户点赞数
     *
     * @param member_id 用户ID
     * @return 结果
     */
    public int selectUmsMemberLikeNumByMemberId(Long member_id){
        return cmsTopicLikeMapper.selectUmsMemberLikeNumByMemberId(member_id);
    }

    /**
     * 查询主题点赞
     * 
     * @param id 主题点赞主键
     * @return 主题点赞
     */
    @Override
    public CmsTopicLike selectCmsTopicLikeById(Long id)
    {
        return cmsTopicLikeMapper.selectCmsTopicLikeById(id);
    }

    /**
     * 查询主题点赞列表
     * 
     * @param cmsTopicLike 主题点赞
     * @return 主题点赞
     */
    @Override
    public List<CmsTopicLike> selectCmsTopicLikeList(CmsTopicLike cmsTopicLike)
    {
        return cmsTopicLikeMapper.selectCmsTopicLikeList(cmsTopicLike);
    }

    @Override
    public List<CmsTopicLike> selectCmsTopicLikesByMemberAndDataIds(Long memberId, List<Long> dataIds) {
        return cmsTopicLikeMapper.selectCmsTopicLikesByMemberAndDataIds(memberId, dataIds);
    }

    @Override
    public boolean readOwnTopicLikeState(Long memberId, Long topicId) {
        requireVisibleOwnTopic(memberId, topicId);
        long count = cmsTopicLikeMapper.countOwnTopicLikes(memberId, topicId);
        if (count < 0) throw new IllegalStateException("收藏状态不可用");
        requireVisibleOwnTopic(memberId, topicId);
        return count > 0;
    }

    @Override
    public boolean cancelOwnTopicLike(Long memberId, Long topicId) {
        requireVisibleOwnTopic(memberId, topicId);
        // Single fixed-pair DELETE also removes legacy duplicates; never toggles or inserts.
        cmsTopicLikeMapper.deleteOwnTopicLikes(memberId, topicId);
        // A fresh observation, not forced false or a permanent/CAS cancellation guarantee.
        return readOwnTopicLikeState(memberId, topicId);
    }

    private void requireVisibleOwnTopic(Long memberId, Long topicId) {
        if (memberId == null || memberId <= 0 || topicId == null || topicId <= 0) {
            throw new IllegalArgumentException("当前用户和主题参数无效");
        }
        CmsTopic visible = cmsTopicService.selectCmsTopicDetailById(topicId, memberId);
        if (visible == null || !topicId.equals(visible.getId())) {
            throw new ServiceException("主题不可访问");
        }
    }

    /**
     * 新增主题点赞
     * 
     * @param cmsTopicLike 主题点赞
     * @return 结果
     */
    @Override
    public int insertCmsTopicLike(CmsTopicLike cmsTopicLike)
    {
        cmsTopicLike.setCreateTime(DateUtils.getNowDate());
        return cmsTopicLikeMapper.insertCmsTopicLike(cmsTopicLike);
    }

    /**
     * 修改主题点赞
     * 
     * @param cmsTopicLike 主题点赞
     * @return 结果
     */
    @Override
    public int updateCmsTopicLike(CmsTopicLike cmsTopicLike)
    {
        cmsTopicLike.setUpdateTime(DateUtils.getNowDate());
        return cmsTopicLikeMapper.updateCmsTopicLike(cmsTopicLike);
    }

    /**
     * 批量删除主题点赞
     * 
     * @param ids 需要删除的主题点赞主键
     * @return 结果
     */
    @Override
    public int deleteCmsTopicLikeByIds(Long[] ids)
    {
        return cmsTopicLikeMapper.deleteCmsTopicLikeByIds(ids);
    }

    /**
     * 删除主题点赞信息
     * 
     * @param id 主题点赞主键
     * @return 结果
     */
    @Override
    public int deleteCmsTopicLikeById(Long id)
    {
        return cmsTopicLikeMapper.deleteCmsTopicLikeById(id);
    }
}
