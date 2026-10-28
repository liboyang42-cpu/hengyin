package com.chengyinhub.business.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.NodeDTO;
import com.chengyinhub.business.domain.dto.TopicCreateDTO;
import com.chengyinhub.business.domain.vo.TopicBundleSubmitResultVO;

/**
 * 主题Service接口
 * 
 * @author webxh
 * @date 2025-09-12
 */
public interface ICmsTopicService 
{

    /**
     * 复制主题
     * @param id 原主题ID
     * @param params 复制参数（可选）
     * @return 新主题ID
     */
    Long copyCmsTopic(Long id, Map<String, Object> params);

    /**
     * 复制主题及路线给指定俱乐部,并下架原主题;三步必须在同一事务内完成。
     *
     * @param id 原主题 ID
     * @param memberId 发起人会员 ID
     * @param clubId 承接俱乐部 ID
     * @return 新主题 ID
     */
    Long transferToClub(Long id, Long memberId, Long clubId);


    /**
     * CMS 官方入口复制主题：只允许复制平台自营源，并强制使用平台收款账号和 PLATFORM 内容槽。
     *
     * @param id 原主题 ID
     * @param params 仅接受复制范围参数，不接受会员归属
     * @return 新主题 ID
     */
    Long copyOfficialCmsTopic(Long id, Map<String, Object> params);

    List<CmsTopic> selectTopicsForSettlement();

    /**
     * 执行主题结算
     * @return 结算的主题数量
     */
    int executeTopicSettlement();

    /**
     * 双死线②:处理招募到期主题。招募中(lifecycle=1)且招募截止已过者,
     * 0 接受→回草稿(lifecycle=0),≥1 接受→进入定价(lifecycle=2)。
     * 由 TopicRecruitDeadlineTask 定时调用。
     * @return 处理的主题数量
     */
    int processRecruitDeadlines();

    /** 按冻结售卖目录推进 SELLING→RUNNING→SETTLING。 */
    int advanceSaleLifecycle();

    /**
     * B4:T+7 打款。扫 coop_settlement 里到期(payable_time≤now)且待打款(status=0)的结算单,
     * 打款进商家余额、写流水、置 status=1。由 CoopSettlementPayoutTask 定时调用。
     * @return 打款笔数
     */
    int payoutDueCoopSettlements();

    /**
     * 查询主题
     * 
     * @param id 主题主键
     * @return 主题
     */
    public CmsTopic selectCmsTopicById(Long id);

    /** 商家待办窄投影：只读指定主题的 id/endDate，空集合不查询。 */
    Map<Long, java.util.Date> selectTodoTopicEndDates(java.util.Collection<Long> topicIds);

    /** 后台逐期漫游导流开关窄读。 */
    Integer selectRoamReferralEnabledById(Long id);

    /** 查询对普通用户公开可见的主题；隐藏状态统一返回 null。 */
    CmsTopic selectPublicCmsTopicById(Long id);

    /** 普通用户只见公开状态；创建者可复用既有身份 seam 预览自己的未删主题。 */
    CmsTopic selectCmsTopicDetailById(Long id, Long viewerMemberId);

    /** 商家招募详情：隐藏状态统一返回 null。 */
    CmsTopic selectMerchantVisibleCmsTopicById(Long id);

    /** 已承接商家的履约详情：主题下架后仍可管理自己的申请与点位。 */
    CmsTopic selectMerchantManagedCmsTopicById(Long id, Long merchantId);

    /**
     * 查询主题列表
     * 
     * @param cmsTopic 主题
     * @return 主题集合
     */
    public List<CmsTopic> selectCmsTopicList(CmsTopic cmsTopic);

    /** 按俱乐部批量统计尚未结束的团，供发现页卡片展示。 */
    Map<Long, Integer> selectUpcomingTopicCounts(List<Long> clubIds);

    /** 查询公开主题；可见性由 Mapper 固定，忽略调用方携带的状态字段。 */
    List<CmsTopic> selectPublicCmsTopicList(CmsTopic cmsTopic);

    /**
     * 新增主题
     * 
     * @param cmsTopic 主题
     * @return 结果
     */
    /**
     * 上下架 CAS：库中 user_status 等于 expected 时才翻转到 target，返回受影响行数（0 = 状态已被别人改掉）。
     *
     * <p>为什么不复用 {@link #updateCmsTopic}：那条是无条件写，读到的态与写下去的态之间有窗口，
     * 而这个窗口里真有别的写者 —— 内容安全回调命中违规会自动下架（{@code IWxContentSecurityService}）。
     */
    public int casUserStatus(Long topicId, Integer expected, Integer target);

    /** 自动过审不得替代退出后的主办显式恢复；返回业务阻断理由，不撤销审核结论。 */
    String topicSupplyAutoOnlineBlockReason(Long topicId);

    /**
     * [6-09] 主题可见性变化时联动广场同步帖(data_type=2):不可见 → 帖子隐藏;恢复公开 → 按原规则放回。
     *
     * <p>覆盖所有改主题可见性的入口:上下架 CAS、删除、审核驳回/过审、内容安全自动下架、
     * 发布草稿、移交下架、后台删除。读取端另有一道兜底过滤(存量行与漏掉的入口)。
     */
    void syncCreativeSquarePostVisibility(Long topicId, boolean visible);

    /**
     * AUDIT-G2-1:内容安全下架留置中的主题申请重新上架 —— 不直接上架,只确保有一张 BIZ_TOPIC 人工复核单
     * (已有未结 BIZ_TOPIC 单则复用,否则新建 source=content_safety_relist)。过审才解除留置并恢复在架。
     *
     * @return 复核单 id
     */
    Long requestContentSafetyRelistReview(Long topicId, Long submitterId);

    public int insertCmsTopic(CmsTopic cmsTopic);

    /**
     * [Task 2] 后台发主题 —— 发布者身份决定归属。
     *
     * <p>「官方发的就是官方发的,俱乐部发的就是俱乐部发的」:内容槽 = 发布者(显式声明,不反推),
     * 票款归内容槽持有者(定稿 §6.5-B)。与 {@link #insertCmsTopic} 的区别是后者恒定 PLATFORM,
     * 只能表达「官方自己发」。
     *
     * @param req      发布者声明 + 主题内容
     * @param operator 操作人(落 create_by)
     * @return 新主题 id
     * @throws IllegalArgumentException 发布者声明缺失/自相矛盾/收款人无从确定
     */
    public Long createTopicByPublisher(com.chengyinhub.business.domain.dto.CmsTopicCreateDTO req, String operator);

    /**
     * 修改主题
     *
     * @param cmsTopic 主题
     * @return 结果
     */
    public int updateCmsTopic(CmsTopic cmsTopic);

    /**
     * 批量删除主题
     * 
     * @param ids 需要删除的主题主键集合
     * @return 结果
     */
    public int deleteCmsTopicByIds(Long[] ids);

    /**
     * 删除主题信息
     * 
     * @param id 主题主键
     * @return 结果
     */
    public int deleteCmsTopicById(Long id);

    /**
     * 创建主题
     * @param dto  TopicCreateDTO
     * @param memberId 会员ID
     * @param username  会员名称
     * @return 新建主题ID（供内容安全图片送检等后置处理使用）
     */
    public Long createTopic(TopicCreateDTO dto, Long memberId, String username);

    /**
     * V2 章节故事流建题入口。与旧入口分开，只有本方法会物化章节 blocks。
     */
    public Long createTopicV2(TopicCreateDTO dto, Long memberId, String username);

    /** V2 建题并返回本次组合审核任务合同。 */
    TopicBundleSubmitResultVO createTopicV2WithReview(TopicCreateDTO dto, Long memberId, String username);

    /**
     * 创作者编辑自己的主题(小程序 publish/fabu 带 ?id= 进来的那条路)。
     *
     * <p>三档编辑窗口见 {@link com.chengyinhub.business.util.TopicEditWindow}:
     * 草稿/待审/驳回 → 全字段重建;已过审开卖 → 只收白名单文案与图,日期/结构/票价拒收;
     * 主题已开始 → 只读。
     *
     * <p>闸全部在本方法内(不在 controller):{@code getAppUserId} 不可 mock,
     * 闸留在 controller 就只能靠肉眼证明它在路径上。
     *
     * @param dto      主题内容 + 被编辑主题 id
     * @param memberId 当前登录会员(归属校验用)
     * @param username 操作人(落 update_by)
     * @return 被编辑的主题 id
     * @throws RuntimeException 主题不存在 / 非本人 / 已开始 / 已开卖却改了锁定字段 / 已有付费报名
     */
    public Long updateTopicByOwner(com.chengyinhub.business.domain.dto.TopicUpdateDTO dto,
                                   Long memberId, String username);

    /**
     * V2 章节故事流编辑入口。章节、节点、blocks 与派生 sortId 在同一事务中整体重建。
     */
    public Long updateTopicByOwnerV2(com.chengyinhub.business.domain.dto.TopicUpdateDTO dto,
                                     Long memberId, String username);

    /** V2 编辑并返回本次组合审核任务合同。 */
    TopicBundleSubmitResultVO updateTopicByOwnerV2WithReview(
            com.chengyinhub.business.domain.dto.TopicUpdateDTO dto, Long memberId, String username);


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
    public int selectOnlineThemeNumByMemberId(Long memberId, Long excludeId);

    /** T7 主题模板库列表(已另存为模板且审核通过) */
    public java.util.List<CmsTopic> selectTopicTemplateList();

    /**
     * T7 主题模板库列表(带 chapterCount/gameCount 富化),供发布广场 tt-card meta 与详情页用。
     * productType 已在实体字段中,本方法保证非 NULL(空→经典定向兜底)。
     */
    public java.util.List<com.chengyinhub.business.domain.vo.TopicTemplateListItemVO> buildTopicTemplateListVO();

    /**
     * 同上,但 recommendedOnly=true 时只回 template_status=VERIFIED 的模板(推荐口径)。
     * 货架 SQL 本身已是 VERIFIED-only(T7 闸);这里是 service 层第二道闸,
     * 防止日后货架放开实验/试点模板浏览时,推荐口径被连带放宽。
     */
    public java.util.List<com.chengyinhub.business.domain.vo.TopicTemplateListItemVO> buildTopicTemplateListVO(boolean recommendedOnly);

    /**
     * T7 主题模板聚合详情:白名单组装的 TopicTemplateDetailVO,含章节→节点→玩法子树 + 归一化路线剪影。
     * 永不出答案/提示/打卡码/精确坐标/storyJson。
     *
     * @param id 主题模板 ID
     * @return 聚合 VO,不存在/非模板返 null
     */
    public com.chengyinhub.business.domain.vo.TopicTemplateDetailVO buildTopicTemplateDetail(Long id, boolean viewerIsMerchant);

    /**
     * 模板详情的招募视图。商家传自身受控品类后，只会得到匹配章节的 recruitStatus；
     * 发布者传 null 可查看自己主题下全部章节的审核概览。
     *
     * @param viewerCanViewRecruitment 服务端已判定的招募信息查看权限
     * @param merchantCategoryId 商家档案品类；null 表示不按品类过滤
     */
    public com.chengyinhub.business.domain.vo.TopicTemplateDetailVO buildTopicTemplateDetail(
            Long id, boolean viewerCanViewRecruitment, Integer merchantCategoryId);

    /**
     * 匿名模板详情：公共 eligibility 与主题详情取自同一条 fail-closed 查询，
     * 并由同一事务快照继续组装公开白名单子树。
     */
    public com.chengyinhub.business.domain.vo.TopicTemplateDetailVO buildPublicTopicTemplateDetail(
            Long id, Long viewerMemberId, boolean viewerIsMerchant, Integer merchantCategoryId);

    /** T7 读某主题是否为模板 */
    public Integer selectTopicIsTemplate(Long id);

    /** T7 另存为模板/取消(仅作者本人),flag=1 标记 / 0 取消 */
    public int saveAsTemplate(Long id, Long memberId, Integer flag);

    /** B1 草稿:置位 is_draft(flag=1 草稿 / 0 取消草稿) */
    public int updateTopicDraftState(Long id, Integer flag);

    /** B2 我的草稿主题 id 集合 */
    public java.util.List<Long> selectDraftTopicIds(Long memberId);

    /** B3 读某主题是否草稿(1=草稿) */
    public Integer selectTopicIsDraft(Long id);

    /** 先发后审打标/抽检通过:写 cms_topic.review_flag(1=进入抽检池,0=已通过)。 */
    public void markReviewFlag(Long topicId, boolean flagged);

    /** 图片内容安全违规留置(AUDIT-INT-7):置 cms_topic.content_safety_hold=1,不改人工驳回原因。 */
    public void markContentSafetyHold(Long topicId);

    /** B3 发布草稿:竞态安全(仅当前确为草稿才发)清 is_draft,按 publishMode 直发/提审;非草稿返 false */
    public boolean publishDraft(Long id);
    boolean publishDraft(Long id, Long expectedOwnerId);
    TopicBundleSubmitResultVO publishDraftWithBinding(Long id, Long expectedOwnerId,
            Long expectedConfigVersion, String expectedRegistryVersion);
    boolean canResubmitSupplyReview(Long id, Long ownerId);

    /** V2 发布回执；专业版返回组合审核任务与冻结玩法 id。 */
    TopicBundleSubmitResultVO publishDraftWithReview(Long id);
    TopicBundleSubmitResultVO publishDraftWithReview(Long id, Long expectedOwnerId);

    /** 内容审核真正通过后，将当前草稿路线经发布门禁固化为玩家可读快照。 */
    void publishApprovedRouteSnapshot(Long topicId);

    /**
     * R19-PENDINGSHELL:内容审核通过后补建自办活动壳(幂等)。
     *
     * <p>壳是公开可下单的(status=1/publish_status=1),不能在建题/发布草稿时就建 —— pro 主题那时还在
     * 待审(status=0),建壳等于审核通过前玩家已能下单。过审分支回调本方法补建;已有壳/未过审/进商家池不动。
     */
    void ensureSelfHostActivityShell(Long topicId);

    /** 圈层模板副本 8 选 3—4；只允许主题 owner 配置。 */
    public int configureCircleCandidates(Long topicId, Long memberId, java.util.List<String> candidateCodes);

    /** 城市实例内容主体复核当前有效供给并写回权威时间。 */
    public java.util.Date reviewCircleSupplies(Long topicId, Long memberId);

    /** Beta 转正式:竞态安全(仅当前确在 Beta 期才清)置 beta_flag=0,返回受影响行数(0=不在Beta) */
    public int graduateBeta(Long topicId);

    /**
     * [C3] 主题创建者删除自己的主题:归属校验 + <b>有付费报名即拒</b>。
     *
     * <p>口径同活动侧({@code ApiActivityController:441}):删除只软删、不退款 ⇒ 钱原地悬空,
     * 「删除」不该隐含大额资金操作。拦 payment_status ∈ {1 支付中, 2 已支付};
     * 4(已退款)不拦 —— 钱已结清,报名单还在当凭证。
     *
     * @throws RuntimeException 主题不存在 / 非本人 / 有付费报名
     */
    boolean deleteTopicByOwner(Long topicId, Long memberId);

}
