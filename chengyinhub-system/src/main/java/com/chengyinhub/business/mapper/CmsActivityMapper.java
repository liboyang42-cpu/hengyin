package com.chengyinhub.business.mapper;

import java.util.List;
import java.util.Map;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.vo.ClubSessionGapVO;
import org.apache.ibatis.annotations.Param;

/**
 * 活动Mapper接口
 * 
 * @author webxh
 * @date 2025-08-31
 */
public interface CmsActivityMapper 
{


    /**
     * 获取需要结算的活动列表
     * @return 活动列表
     */
    List<CmsActivity> selectActivitiesForSettlement();

    /** 只读解析主题下未删除活动壳 ID；供证据查询先解析、后只锁 registration。 */
    List<Long> selectActiveActivityIdsByTopicId(@Param("topicId") Long topicId);

    /**
     * HF-02(A-29): 原子领取活动进入结算(is_settlement 0→1)。
     * 返回影响行数:1=领取成功可结算;0=已被其它结算流程领取(并发/重复触发),应跳过,防止重复入账。
     * @param id 活动ID
     * @return 影响行数
     */
    int claimActivityForSettlement(@Param("id") Long id);

    /**
     * 查询活动
     * 
     * @param id 活动主键
     * @return 活动
     */
    public CmsActivity selectCmsActivityById(Long id);

    /** 复制活动票前锁定父活动，串行化同一活动的幂等检查与插入。 */
    CmsActivity selectCmsActivityByIdForUpdate(Long id);

    /** Cancellation owner only, after current authorization/scope locks. Never a generic entity patch. */
    String selectCancellationReceiptForUpdate(@Param("id") Long id);
    int compareAndSetCancellationReceipt(@Param("id") Long id, @Param("requestHash") String requestHash,
                                        @Param("receipt") String receipt, @Param("first") boolean first);

    /** 公开详情：SQL 固定可见性 Gate，状态不接受调用方放宽。 */
    CmsActivity selectPublicCmsActivityById(Long id);

    /** 公开详情 + 既有 creator 预览 seam；非 owner 仍只能命中公开状态。 */
    CmsActivity selectCmsActivityDetailById(@Param("id") Long id, @Param("viewerMemberId") Long viewerMemberId);

    /**
     * 查询活动列表
     * 
     * @param cmsActivity 活动
     * @return 活动集合
     */
    public List<CmsActivity> selectCmsActivityList(CmsActivity cmsActivity);

    List<Map<String, Object>> selectSignedUpRegistrationCounts(@Param("activityIds") List<Long> activityIds);

    List<Map<String, Object>> selectTopicSignedUpRegistrationCounts(@Param("topicIds") List<Long> topicIds);

    List<Map<String, Object>> selectPendingVerificationCounts(@Param("activityIds") List<Long> activityIds);

    /** 公开列表：SQL 固定未删、审核通过、已上架。 */
    List<CmsActivity> selectPublicCmsActivityList(CmsActivity cmsActivity);

    /**
     * 新增活动
     * 
     * @param cmsActivity 活动
     * @return 结果
     */
    public int insertCmsActivity(CmsActivity cmsActivity);

    /**
     * 修改活动
     * 
     * @param cmsActivity 活动
     * @return 结果
     */
    public int updateCmsActivity(CmsActivity cmsActivity);

    /**
     * 小程序内容更新：只改可编辑字段，WHERE 必须同时钉 id 与 member_id。
     * member_id 只出现在 WHERE，不写进 SET。
     */
    int updateCmsActivityByOwner(CmsActivity cmsActivity);

    /**
     * 承接方领队改集合时间/地点:「仍未售出」与写入是同一条条件 UPDATE(#334 F7)。
     * 未售出 = 本场 cms_registration 没有 payment_status ∈ {0,1,2} 的单(与 countOpenSaleRegistrations 同口径)。
     * 返回 0 = 已售出锁定(或场次不存在),调用方不得再补写。
     */
    int updateOpsFieldsIfUnsold(CmsActivity cmsActivity);

    /**
     * 删除活动
     * 
     * @param id 活动主键
     * @return 结果
     */
    public int deleteCmsActivityById(Long id);

    /**
     * 批量删除活动
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteCmsActivityByIds(Long[] ids);

    /**
     * 查询用户发布活动数
     *
     * @param member_id 用户ID
     * @return 结果
     */
    public int selectCmsActivityNumByMemberId(Long member_id);

    /** 生效 club 合作单下仍无 topic+club 带队场次的资损告警差异。 */
    List<ClubSessionGapVO> selectClubOrderTopicsWithoutClubSession();

    /**
     * 审核撤销/申诉复核：把审核决定写下的状态 CAS 回待审(0/0/0)。
     * decidedAt 非空时，决定之后被编辑过(update_time 更晚)的活动返回 0 行，视为内容已变化。
     */
    int reopenAuditDecision(@Param("id") Long id, @Param("fromStatus") Long fromStatus,
                            @Param("fromCheckStatus") Integer fromCheckStatus,
                            @Param("fromPublishStatus") Long fromPublishStatus,
                            @Param("decidedAt") java.util.Date decidedAt);

    /** 内容安全留置(AUDIT-INT-7,content_safety_hold):图片机审违规置 1,作者新提交经人工过审清 0。 */
    int markContentSafetyHold(@Param("id") Long id);

    int clearContentSafetyHold(@Param("id") Long id);

    Integer selectContentSafetyHold(@Param("id") Long id);
}
