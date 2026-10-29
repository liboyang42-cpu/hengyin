package com.chengyinhub.business.mapper;

import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.CmsTopic;

/**
 * 主题Mapper接口
 *
 * @author webxh
 * @date 2025-09-12
 */
public interface CmsTopicMapper
{
    /** Shared content/publication fence. The returned id must match the requested existing parent. */
    Long lockTopicContentBudget(@Param("id") Long id);

    /**
     * 查询主题
     *
     * @param id 主题主键
     * @return 主题
     */
    public CmsTopic selectCmsTopicById(Long id);

    /** Cancellation owner only; not exposed by the ordinary topic JSON/update mapping. */
    Long selectCancellationEpoch(@Param("id") Long id);
    Long selectCancellationEpochForUpdate(@Param("id") Long id);
    String selectCancellationReceiptForUpdate(@Param("id") Long id,@Param("epoch") Long epoch);
    int compareAndSetCancellationReceipt(@Param("id") Long id,@Param("epoch") Long epoch,@Param("requestHash") String requestHash,
                                        @Param("receipt") String receipt, @Param("first") boolean first);

    List<CmsTopic> selectTodoTopicEndDates(@Param("topicIds") java.util.Collection<Long> topicIds);

    /** 5-4 后台逐期开关窄读；避免把新列扩散进全部主题查询。 */
    Integer selectRoamReferralEnabledById(Long id);

    /** 仅读 product_type;定价预览判断自由探索开售闸用。 */
    Integer selectProductTypeById(Long id);

    /** R9-ACTIONS 开场通话窄读：只读 journey_story_json，列未落库时不影响其它主题查询。 */
    String selectJourneyStoryJsonById(Long id);

    /** 通用 patch 的冻结闸只需生命周期，避免为一次运营字段更新装载整题。 */
    CmsTopic selectCmsTopicLifecycleById(Long id);

    /** 公开详情：SQL 固定可见性 Gate，状态不接受调用方放宽。 */
    CmsTopic selectPublicCmsTopicById(Long id);

    /** 漫游·附近的局:附近已发布主题(城市定向 / 自由探索),按第一个有坐标的节点落点,只读叠加 */
    List<java.util.Map<String, Object>> selectNearbyTopics(@Param("lat") double lat, @Param("lng") double lng,
            @Param("minLat") double minLat, @Param("maxLat") double maxLat,
            @Param("minLng") double minLng, @Param("maxLng") double maxLng,
            @Param("radius") int radius, @Param("limit") int limit, @Param("viewerId") Long viewerId);

    /** O-08:返回 topicIds 中对 viewer 不可见的「仅俱乐部成员可见」主题 id(判据 = clubMemberOnlyGate)。 */
    List<Long> selectClubMemberOnlyHiddenTopicIds(@Param("topicIds") List<Long> topicIds,
            @Param("viewerMemberId") Long viewerMemberId);

    /** 地图附近可申请的公开队伍(play_team);放在本 mapper 以复用主题可见性闸片段。 */
    List<com.chengyinhub.business.domain.vo.PlayTeamNearbyVO> selectNearbyPublicTeams(
            @Param("viewerMemberId") Long viewerMemberId, @Param("lat") double lat, @Param("lng") double lng,
            @Param("minLat") double minLat, @Param("maxLat") double maxLat,
            @Param("minLng") double minLng, @Param("maxLng") double maxLng,
            @Param("radius") int radius, @Param("now") Date now, @Param("limit") int limit);

    /** 公开详情 + 既有 creator 预览 seam；非 owner 仍只能命中公开状态。 */
    CmsTopic selectCmsTopicDetailById(@Param("id") Long id, @Param("viewerMemberId") Long viewerMemberId);

    /** 商家公开详情：固定未删、审核通过、商家端已上架。 */
    CmsTopic selectMerchantVisibleCmsTopicById(Long id);

    /** 商家履约管理详情：仅已通过申请且供给仍生效的承接商家可读取下架主题。 */
    CmsTopic selectMerchantManagedCmsTopicById(@Param("id") Long id,
                                                @Param("merchantId") Long merchantId);

    /**
     * 行锁读主题(乙-2 资损:confirmPrice 锁价 与 acceptInvite 入伙 靠此行锁串行,
     * 消除"接受邀请挤在定价读单与 lifecycle=3 提交之间"的 TOCTOU 结算悬空窗)。须在事务内调用。
     */
    public CmsTopic selectCmsTopicByIdForUpdate(Long id);



    public List<CmsTopic> selectTopicsForSettlement();

    /** 双死线②:招募中(lifecycle=1)且招募截止已过的主题 */
    public List<CmsTopic> selectRecruitExpiredTopics(@Param("now") Date now);

    /** SELLING/RUNNING 的时间轴推进候选，逐条再行锁复核。 */
    List<CmsTopic> selectTopicsForSaleLifecycleAdvance();


    /**
     * 查询主题列表
     * 
     * @param cmsTopic 主题
     * @return 主题集合
     */
    public List<CmsTopic> selectCmsTopicList(CmsTopic cmsTopic);

    List<Map<String, Object>> selectUpcomingTopicCounts(@Param("clubIds") List<Long> clubIds,
                                                         @Param("now") Date now);

    /** 公开列表：SQL 固定未删、审核通过、已上架。 */
    List<CmsTopic> selectPublicCmsTopicList(CmsTopic cmsTopic);

    /**
     * 新增主题
     * 
     * @param cmsTopic 主题
     * @return 结果
     */
    public int insertCmsTopic(CmsTopic cmsTopic);

    /**
     * 修改主题
     * 
     * @param cmsTopic 主题
     * @return 结果
     */
    public int updateCmsTopic(CmsTopic cmsTopic);

    /** 路线定义唯一写口：支持显式清空 JSON，并用 config_version 防陈旧编辑覆盖。 */
    int updateRouteDefinition(@Param("id") Long id, @Param("routeMode") String routeMode,
                              @Param("routeGraphJson") String routeGraphJson,
                              @Param("expectedVersion") Long expectedVersion,
                              @Param("targetVersion") Long targetVersion,
                              @Param("updateTime") Date updateTime);

    /** 成功发布时把已校验草稿复制为运行快照，并独立递增发布版本。 */
    int publishRouteSnapshot(@Param("id") Long id,
                             @Param("publishedRouteGraphJson") String publishedRouteGraphJson,
                             @Param("updateTime") Date updateTime);

    /** 供给、候选或商家状态变化后使旧城市复核失效；NULL 必须用专用 SQL 显式写入。 */
    int clearCircleReviewedAt(@Param("id") Long id, @Param("updateTime") Date updateTime);

    /** 抽检池标记(#377 cms_topic.review_flag):1=先发后审放行但待抽检,0=抽检已通过/未打标。 */
    public int updateReviewFlag(@Param("id") Long id, @Param("reviewFlag") int reviewFlag);

    /** 读抽检池标记:草稿期机审存疑也会打 1,publish-draft 据此不写 check_status=1 并挂抽检单。 */
    public Integer selectReviewFlag(@Param("id") Long id);

    /** 上下架 CAS：只有当前值等于 expected 才翻转，禁止陈旧请求覆盖新状态(NULL 按 0 处理)。 */
    public int casUserStatus(@Param("id") Long id,
                             @Param("expected") Integer expected,
                             @Param("target") Integer target,
                             @Param("updateTime") Date updateTime);

    /** 生命周期 CAS：只有当前值等于 expected 才迁移，禁止陈旧请求覆盖新状态。 */
    public int transitionLifecycle(@Param("id") Long id,
                                   @Param("expected") Integer expected,
                                   @Param("target") Integer target,
                                   @Param("updateTime") Date updateTime);

    /** PRICING→SELLING 的唯一落点：生命周期 CAS 与首次实际开售时刻同一条 SQL 写入。 */
    int finalizeForSale(@Param("id") Long id, @Param("sellingStartedAt") Date sellingStartedAt,
                        @Param("updateTime") Date updateTime);

    /**
     * A3-7(P0)CAS 原子领取待结算主题:0→1 条件置位,返回受影响行数。
     * 抢到(=1)才结算,抢不到(=0)说明已被结算/正在结算 ⇒ 跳过,防并发/重入重复入账。
     * 照抄活动线 claimActivityForSettlement。
     */
    public int claimTopicForSettlement(@Param("id") Long id);

    /**
     * 删除主题
     * 
     * @param id 主题主键
     * @return 结果
     */
    public int deleteCmsTopicById(Long id);

    /**
     * 批量删除主题
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteCmsTopicByIds(Long[] ids);

    /**
     * 查询用户发布主题数
     *
     * @param member_id 用户ID
     * @return 结果
     */
    public int selectCmsTopicNumByMemberId(Long member_id);

    /**
     * 查询用户「在架公开主题」数(user_status=1 且非 selfPlay),用于上架额度校验。
     *
     * @param memberId  用户ID
     * @param excludeId 排除的主题ID(重复上架幂等,可为 null)
     * @return 在架公开主题数
     */
    public int selectOnlineThemeNumByMemberId(@org.apache.ibatis.annotations.Param("memberId") Long memberId,
                                              @org.apache.ibatis.annotations.Param("excludeId") Long excludeId);

    /** T7 主题模板库:已另存为模板且审核通过的主题(依赖 migration_topic_template) */
    public java.util.List<CmsTopic> selectTopicTemplateList();

    /** 匿名详情只读：同一查询同时完成公共模板 eligibility 与详情快照读取。 */
    public CmsTopic selectPublicTopicTemplateById(Long id);

    /** T7 读某主题的模板标记(/use 门禁) */
    public Integer selectTopicIsTemplate(Long id);

    /** T7 另存为模板/取消:仅作者本人可标记 */
    public int updateTopicIsTemplate(@org.apache.ibatis.annotations.Param("id") Long id,
                                     @org.apache.ibatis.annotations.Param("memberId") Long memberId,
                                     @org.apache.ibatis.annotations.Param("flag") Integer flag);

    /** B1 草稿:隔离置位 is_draft */
    public int updateTopicDraftState(@org.apache.ibatis.annotations.Param("id") Long id,
                                     @org.apache.ibatis.annotations.Param("flag") Integer flag);

    /** B2 我的草稿主题 id 集合 */
    public java.util.List<Long> selectDraftTopicIds(Long memberId);

    List<CmsTopic> selectPublicNodeCountsByTopicIds(@Param("topicIds") List<Long> topicIds);

    /** B3 读某主题是否草稿(publish-draft 门禁) */
    public Integer selectTopicIsDraft(Long id);

    /** B3 竞态安全清草稿:仅当前确为草稿才清,返回受影响行数 */
    public int clearDraftIfDraft(Long id);

    /** Beta 转正式:仅当前确在 Beta 期(beta_flag=1)才清,返回受影响行数 */
    public int graduateBetaIfBeta(Long id);

    /**
     * 俱乐部对自己主题的三个展示/招商开关(Figma J4)。
     * where 带 club_id:拿别的俱乐部的 topicId 过来改不动一行,而不是靠上层记得校验。
     */
    int updateClubDisplaySettings(@Param("topicId") Long topicId,
                                  @Param("clubId") Long clubId,
                                  @Param("clubCoopOpen") Integer clubCoopOpen,
                                  @Param("clubPinned") Integer clubPinned,
                                  @Param("clubMemberOnly") Integer clubMemberOnly);

    /**
     * 审核撤销/申诉复核：把审核决定写下的状态 CAS 回待审未上架(status=0/check_status=0/user_status=0)。
     * configVersion 非空时(主题包快照)，提交后又改过配置的主题返回 0 行。
     */
    int reopenAuditDecision(@Param("id") Long id, @Param("fromStatus") Integer fromStatus,
                            @Param("fromCheckStatus") Integer fromCheckStatus,
                            @Param("configVersion") Long configVersion);

    /** 内容安全留置(AUDIT-INT-7,content_safety_hold):图片机审违规置 1,作者新提交经人工过审清 0。 */
    int markContentSafetyHold(@Param("id") Long id);

    int clearContentSafetyHold(@Param("id") Long id);

    Integer selectContentSafetyHold(@Param("id") Long id);
}
