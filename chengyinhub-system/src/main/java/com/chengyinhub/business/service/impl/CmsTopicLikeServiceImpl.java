package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.CmsTopicLikeMapper;
import com.chengyinhub.business.domain.CmsTopicLike;
import com.chengyinhub.business.service.ICmsTopicLikeService;

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
