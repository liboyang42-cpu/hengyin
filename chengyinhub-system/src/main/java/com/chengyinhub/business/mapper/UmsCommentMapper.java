package com.chengyinhub.business.mapper;

import java.util.List;
import java.util.Map;
import com.chengyinhub.business.domain.UmsComment;
import org.apache.ibatis.annotations.Param;

/**
 * 用户评论Mapper接口
 * 
 * @author ruoyi
 * @date 2025-08-28
 */
public interface UmsCommentMapper 
{
    boolean hasCompletedPoi(@Param("poiId") Long poiId, @Param("memberId") Long memberId);
    int countPoiReviews(@Param("poiId") Long poiId, @Param("memberId") Long memberId);

    /**
     * 查询用户评论
     * 
     * @param id 用户评论主键
     * @return 用户评论
     */
    public UmsComment selectUmsCommentById(Long id);

/** 锁定评论当前状态，拒绝删除或隐藏后的互动。 */
    UmsComment selectUmsCommentByIdForUpdate(Long id);

    /** 3-11:主题(1)/活动(2)评价对象必须真实存在且未删除。 */
    boolean reviewTargetExists(@Param("ownerType") Long ownerType, @Param("ownerId") Long ownerId);

    /** 3-11:首评积分资格 = 已支付或已核销的报名;主题认本主题票及其下活动票,活动只认本活动票。 */
    boolean hasPaidOrVerifiedRegistration(@Param("ownerType") Long ownerType, @Param("ownerId") Long ownerId,
                                          @Param("memberId") Long memberId);



    public Double selectAvgRatingByCondition(UmsComment umsComment);

    public List<Map<String, Object>> selectRatingSummaryByOwners(@Param("ownerType") Long ownerType,
                                                                 @Param("ownerIds") List<Long> ownerIds);


    /**
     * 查询用户评论列表
     * 
     * @param umsComment 用户评论
     * @return 用户评论集合
     */
    public List<UmsComment> selectUmsCommentList(UmsComment umsComment);

    /**
     * 新增用户评论
     * 
     * @param umsComment 用户评论
     * @return 结果
     */
    public int insertUmsComment(UmsComment umsComment);

    /**
     * 修改用户评论
     * 
     * @param umsComment 用户评论
     * @return 结果
     */
    public int updateUmsComment(UmsComment umsComment);

    /**
     * 删除用户评论
     * 
     * @param id 用户评论主键
     * @return 结果
     */
    public int deleteUmsCommentById(Long id);

    /**
     * 批量删除用户评论
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteUmsCommentByIds(Long[] ids);

    /**
     * FX-10:会员对该主题/活动还有没有「有效参与」—— 报名仍有效(registration_status=2)且已支付或已核销。
     * 主题认本主题票 + 其下场次票;退款/取消的报名(registration_status=3)不算。
     */
    public boolean hasValidParticipation(@Param("ownerType") Long ownerType, @Param("ownerId") Long ownerId,
                                         @Param("memberId") Long memberId);

    /** FX-10:会员在该主题/活动下发过的评论 id(含已删,首评积分发在第一条上)。 */
    public List<Long> selectReviewCommentIds(@Param("ownerType") Long ownerType,
                                                       @Param("ownerId") Long ownerId,
                                                       @Param("memberId") Long memberId);
}
