package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.CmsActivityLikeMapper;
import com.chengyinhub.business.domain.CmsActivityLike;
import com.chengyinhub.business.service.ICmsActivityLikeService;

/**
 * 活动点赞Service业务层处理
 * 
 * @author webxh
 * @date 2025-08-31
 */
@Service
public class CmsActivityLikeServiceImpl implements ICmsActivityLikeService 
{
    @Autowired
    private CmsActivityLikeMapper cmsActivityLikeMapper;


    /**
     * 查询用户点赞数
     *
     * @param member_id 用户ID
     * @return 结果
     */
    public int selectUmsMemberLikeNumByMemberId(Long member_id){
        return cmsActivityLikeMapper.selectUmsMemberLikeNumByMemberId(member_id);
    }



    /**
     * 查询活动点赞
     * 
     * @param id 活动点赞主键
     * @return 活动点赞
     */
    @Override
    public CmsActivityLike selectCmsActivityLikeById(Long id)
    {
        return cmsActivityLikeMapper.selectCmsActivityLikeById(id);
    }

    /**
     * 查询活动点赞列表
     * 
     * @param cmsActivityLike 活动点赞
     * @return 活动点赞
     */
    @Override
    public List<CmsActivityLike> selectCmsActivityLikeList(CmsActivityLike cmsActivityLike)
    {
        return cmsActivityLikeMapper.selectCmsActivityLikeList(cmsActivityLike);
    }

    @Override
    public List<CmsActivityLike> selectCmsActivityLikesByMemberAndDataIds(Long memberId, List<Long> dataIds) {
        return cmsActivityLikeMapper.selectCmsActivityLikesByMemberAndDataIds(memberId, dataIds);
    }

    /**
     * 新增活动点赞
     * 
     * @param cmsActivityLike 活动点赞
     * @return 结果
     */
    @Override
    public int insertCmsActivityLike(CmsActivityLike cmsActivityLike)
    {
        cmsActivityLike.setCreateTime(DateUtils.getNowDate());
        return cmsActivityLikeMapper.insertCmsActivityLike(cmsActivityLike);
    }

    /**
     * 修改活动点赞
     * 
     * @param cmsActivityLike 活动点赞
     * @return 结果
     */
    @Override
    public int updateCmsActivityLike(CmsActivityLike cmsActivityLike)
    {
        cmsActivityLike.setUpdateTime(DateUtils.getNowDate());
        return cmsActivityLikeMapper.updateCmsActivityLike(cmsActivityLike);
    }

    /**
     * 批量删除活动点赞
     * 
     * @param ids 需要删除的活动点赞主键
     * @return 结果
     */
    @Override
    public int deleteCmsActivityLikeByIds(Long[] ids)
    {
        return cmsActivityLikeMapper.deleteCmsActivityLikeByIds(ids);
    }

    /**
     * 删除活动点赞信息
     * 
     * @param id 活动点赞主键
     * @return 结果
     */
    @Override
    public int deleteCmsActivityLikeById(Long id)
    {
        return cmsActivityLikeMapper.deleteCmsActivityLikeById(id);
    }
}
